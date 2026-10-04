#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Частный центр сертификации и секреты стенда (ADR-022).

    python3 infra/pki/dgm_pki.py ca                 создать центр сертификации, если его нет
    python3 infra/pki/dgm_pki.py certs [--force]    выпустить сертификаты контейнеров (пропускает действующие)
    python3 infra/pki/dgm_pki.py secrets [--force]  создать секреты по перечню (пропускает существующие)
    python3 infra/pki/dgm_pki.py verify             проверить сертификаты и секреты, код выхода 0 если всё в порядке
    python3 infra/pki/dgm_pki.py status             таблица сроков сертификатов

Перечень контейнеров и секретов: infra/pki/inventory.json. Нужны Python 3 и openssl 3, внешних пакетов нет.

Где что лежит (каталоги меняются переменными окружения):
  DGM_PKI_DIR      по умолчанию .pki/      закрытый ключ и сертификат центра. В контейнеры не попадает, в Git не хранится
  DGM_SECRETS_DIR  по умолчанию secrets/   файлы секретов Docker: tls_<контейнер>.key, tls_<контейнер>.crt, tls_ca.crt и прочие
  DGM_SECRET_MODE  по умолчанию 0444       права файлов секретов (см. README: почему не 0400)
"""
import argparse
import base64
import datetime as dt
import json
import os
import re
import secrets as pysecrets
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, '..', '..'))
INVENTORY = os.path.join(HERE, 'inventory.json')


def pki_dir():
    return os.environ.get('DGM_PKI_DIR') or os.path.join(REPO, '.pki')


def secrets_dir():
    return os.environ.get('DGM_SECRETS_DIR') or os.path.join(REPO, 'secrets')


def secret_mode():
    return int(os.environ.get('DGM_SECRET_MODE', '0444'), 8)


def load_inventory():
    with open(INVENTORY, encoding='utf-8') as f:
        return json.load(f)


def openssl(*args, input_text=None):
    r = subprocess.run(['openssl', *args], capture_output=True, text=True, input=input_text)
    if r.returncode != 0:
        raise RuntimeError('openssl %s: %s' % (' '.join(args[:3]), r.stderr.strip()))
    return r.stdout


def read_text(path):
    with open(path, encoding='utf-8') as f:
        return f.read()


def write_file(path, data, mode):
    """Записывает файл без хвостового перевода строки у секретов и с нужными правами. Существующий файл заменяется."""
    if os.path.lexists(path):
        os.unlink(path)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w', encoding='utf-8', newline='\n') as f:
        f.write(data)
    os.chmod(path, mode)


def ensure_dir(path, mode=0o700):
    os.makedirs(path, exist_ok=True)
    os.chmod(path, mode)


# ----------------------------------------------------------------------------------------------------------------
# Центр сертификации
# ----------------------------------------------------------------------------------------------------------------
def ca_paths():
    d = pki_dir()
    return os.path.join(d, 'ca.key'), os.path.join(d, 'ca.crt')


def cmd_ca(args, inv=None):
    inv = inv or load_inventory()
    key, crt = ca_paths()
    ensure_dir(pki_dir())
    have_key, have_crt = os.path.exists(key), os.path.exists(crt)
    if have_key and have_crt:
        print('центр сертификации уже есть: %s' % crt)
        return 0
    if have_key != have_crt:
        print('в %s остался только один из файлов центра сертификации, удалите оба и повторите' % pki_dir(), file=sys.stderr)
        return 2
    ca = inv['ca']
    tmp_key = openssl('genpkey', '-algorithm', 'EC', '-pkeyopt', 'ec_paramgen_curve:P-256')
    write_file(key, tmp_key, 0o400)
    days = str(int(ca['years'] * 365 + ca['years'] // 4))
    out = openssl('req', '-x509', '-new', '-key', key, '-sha256', '-days', days,
                  '-subj', '/O=%s/CN=%s' % (ca['organization'], ca['common_name']),
                  '-addext', 'basicConstraints=critical,CA:TRUE,pathlen:0',
                  '-addext', 'keyUsage=critical,keyCertSign,cRLSign',
                  '-addext', 'subjectKeyIdentifier=hash')
    write_file(crt, out, 0o444)
    print('создан центр сертификации: %s (срок %d лет, ключ %s)' % (crt, ca['years'], key))
    return 0


# ----------------------------------------------------------------------------------------------------------------
# Сертификаты контейнеров
# ----------------------------------------------------------------------------------------------------------------
def parse_cert(path):
    """Достаёт из сертификата то, что проверяется: даты, алгоритм, кривую, SAN, EKU, CA-флаг, субъект."""
    text = openssl('x509', '-in', path, '-noout', '-text')
    dates = openssl('x509', '-in', path, '-noout', '-startdate', '-enddate', '-subject')
    info = {}
    m = re.search(r'notBefore=(.+)', dates)
    info['not_before'] = dt.datetime.strptime(m.group(1).strip(), '%b %d %H:%M:%S %Y %Z').replace(tzinfo=dt.timezone.utc)
    m = re.search(r'notAfter=(.+)', dates)
    info['not_after'] = dt.datetime.strptime(m.group(1).strip(), '%b %d %H:%M:%S %Y %Z').replace(tzinfo=dt.timezone.utc)
    info['subject'] = re.search(r'subject=(.+)', dates).group(1).strip()
    m = re.search(r'CN\s*=\s*([^,/\n]+)', info['subject'])
    info['cn'] = m.group(1).strip() if m else ''
    info['alg'] = 'ecdsa' if 'id-ecPublicKey' in text else 'other'
    m = re.search(r'ASN1 OID:\s*(\S+)', text)
    info['curve'] = m.group(1) if m else ''
    m = re.search(r'Subject Alternative Name:\s*\n\s*(.+)', text)
    info['san'] = [x.strip() for x in m.group(1).split(',')] if m else []
    info['eku'] = 'TLS Web Server Authentication' in text, 'TLS Web Client Authentication' in text
    info['is_ca'] = 'CA:TRUE' in text
    info['sig'] = re.search(r'Signature Algorithm:\s*(\S+)', text).group(1)
    return info


def days_left(info, now=None):
    now = now or dt.datetime.now(dt.timezone.utc)
    return (info['not_after'] - now).total_seconds() / 86400


def public_key_of_key(path):
    return openssl('pkey', '-in', path, '-pubout')


def public_key_of_cert(path):
    return openssl('x509', '-in', path, '-noout', '-pubkey')


def verify_chain(cert, ca_crt):
    """Цепочка проверяется для обоих назначений: контейнер бывает и сервером, и клиентом."""
    for purpose in ('sslserver', 'sslclient'):
        try:
            openssl('verify', '-CAfile', ca_crt, '-purpose', purpose, '-x509_strict', cert)
        except RuntimeError as e:
            return str(e)
    return None


def issue(name, extra_san, days, inv):
    key_f, crt_f = ca_paths()
    d = secrets_dir()
    ensure_dir(d)
    out_key = os.path.join(d, 'tls_%s.key' % name)
    out_crt = os.path.join(d, 'tls_%s.crt' % name)
    san = ['DNS:%s' % name, 'DNS:localhost', 'IP:127.0.0.1'] + ['DNS:%s' % s for s in extra_san]
    with tempfile.TemporaryDirectory() as tmp:
        k = os.path.join(tmp, 'k.pem')
        csr = os.path.join(tmp, 'c.csr')
        ext = os.path.join(tmp, 'ext.cnf')
        crt = os.path.join(tmp, 'c.crt')
        with open(k, 'w') as f:
            f.write(openssl('genpkey', '-algorithm', 'EC', '-pkeyopt', 'ec_paramgen_curve:P-256'))
        openssl('req', '-new', '-key', k, '-subj', '/O=%s/CN=%s' % (inv['ca']['organization'], name), '-out', csr)
        with open(ext, 'w') as f:
            f.write('basicConstraints=critical,CA:FALSE\n'
                    'keyUsage=critical,digitalSignature\n'
                    'extendedKeyUsage=serverAuth,clientAuth\n'
                    'subjectKeyIdentifier=hash\n'
                    'authorityKeyIdentifier=keyid\n'
                    'subjectAltName=%s\n' % ','.join(san))
        openssl('x509', '-req', '-in', csr, '-CA', ca_paths()[1], '-CAkey', key_f, '-CAcreateserial',
                '-CAserial', os.path.join(pki_dir(), 'ca.srl'), '-days', str(days), '-sha256', '-extfile', ext, '-out', crt)
        write_file(out_key, read_text(k), secret_mode())
        write_file(out_crt, read_text(crt), 0o444)
    return out_key, out_crt


def cert_is_current(name, inv):
    """Сертификат действует, подписан текущим центром, ключ подходит и срок не вышел за порог обновления."""
    d = secrets_dir()
    key = os.path.join(d, 'tls_%s.key' % name)
    crt = os.path.join(d, 'tls_%s.crt' % name)
    if not (os.path.exists(key) and os.path.exists(crt)):
        return False
    try:
        if verify_chain(crt, ca_paths()[1]) is not None:
            return False
        if public_key_of_key(key) != public_key_of_cert(crt):
            return False
        return days_left(parse_cert(crt)) > inv['renew_before_days']
    except RuntimeError:
        return False


def cmd_certs(args):
    inv = load_inventory()
    rc = cmd_ca(None, inv)
    if rc:
        return rc
    d = secrets_dir()
    ensure_dir(d)
    write_file(os.path.join(d, 'tls_ca.crt'), read_text(ca_paths()[1]), 0o444)
    issued = kept = 0
    for c in inv['containers']:
        if not args.force and cert_is_current(c['name'], inv):
            kept += 1
            continue
        issue(c['name'], c.get('san', []), inv['cert_days'], inv)
        issued += 1
        print('выпущен сертификат: %s (%d дней)' % (c['name'], inv['cert_days']))
    print('сертификаты: выпущено %d, оставлено действующих %d, всего %d' % (issued, kept, len(inv['containers'])))
    return 0


# ----------------------------------------------------------------------------------------------------------------
# Секреты
# ----------------------------------------------------------------------------------------------------------------
def generate(kind):
    if kind == 'password':
        return pysecrets.token_urlsafe(24)                       # 32 символа, без специальных знаков
    if kind == 'key32':
        return base64.b64encode(pysecrets.token_bytes(32)).decode()   # 256 бит
    if kind == 'hex32':
        return pysecrets.token_hex(32)
    raise ValueError('неизвестный вид секрета: %s' % kind)


def cmd_secrets(args):
    inv = load_inventory()
    d = secrets_dir()
    ensure_dir(d)
    made = kept = 0
    for s in inv['secrets']:
        path = os.path.join(d, s['name'])
        if os.path.exists(path) and not args.force:
            kept += 1
            continue
        write_file(path, generate(s['kind']), secret_mode())
        made += 1
    print('секреты: создано %d, оставлено существующих %d, всего %d (каталог %s)' % (made, kept, len(inv['secrets']), d))
    return 0


# ----------------------------------------------------------------------------------------------------------------
# Проверка
# ----------------------------------------------------------------------------------------------------------------
def cmd_verify(args):
    inv = load_inventory()
    problems = []
    key_f, crt_f = ca_paths()
    d = secrets_dir()
    if not os.path.exists(crt_f):
        problems.append('нет сертификата центра сертификации %s' % crt_f)
    else:
        ca = parse_cert(crt_f)
        if not ca['is_ca']:
            problems.append('сертификат центра сертификации не помечен CA:TRUE')
        if ca['curve'] != 'prime256v1':
            problems.append('центр сертификации не на кривой P-256: %s' % ca['curve'])
        if os.path.exists(key_f) and (os.stat(key_f).st_mode & 0o077):
            problems.append('закрытый ключ центра сертификации доступен не только владельцу: %s' % key_f)
    if os.path.exists(os.path.join(d, 'ca.key')) or os.path.exists(os.path.join(d, 'tls_ca.key')):
        problems.append('закрытый ключ центра сертификации лежит среди секретов контейнеров')
    expected_files = {'tls_ca.crt'}
    for c in inv['containers']:
        n = c['name']
        key, crt = os.path.join(d, 'tls_%s.key' % n), os.path.join(d, 'tls_%s.crt' % n)
        expected_files |= {'tls_%s.key' % n, 'tls_%s.crt' % n}
        if not (os.path.exists(key) and os.path.exists(crt)):
            problems.append('%s: нет ключа или сертификата' % n)
            continue
        try:
            info = parse_cert(crt)
        except RuntimeError as e:
            problems.append('%s: сертификат не читается (%s)' % (n, e))
            continue
        err = verify_chain(crt, os.path.join(d, 'tls_ca.crt')) if os.path.exists(os.path.join(d, 'tls_ca.crt')) else 'нет tls_ca.crt'
        if err:
            problems.append('%s: цепочка не проверена (%s)' % (n, err.splitlines()[-1]))
        if info['alg'] != 'ecdsa' or info['curve'] != 'prime256v1':
            problems.append('%s: ключ не ECDSA P-256 (%s, %s)' % (n, info['alg'], info['curve']))
        if info['eku'] != (True, True):
            problems.append('%s: нет назначений serverAuth и clientAuth' % n)
        if info['is_ca']:
            problems.append('%s: сертификат контейнера помечен как CA' % n)
        if 'DNS:%s' % n not in info['san']:
            problems.append('%s: в SAN нет собственного имени' % n)
        if info['cn'] != n:
            problems.append('%s: CN сертификата «%s» не равен имени контейнера' % (n, info['cn']))
        life = (info['not_after'] - info['not_before']).days
        if life > inv['cert_days'] + 1:
            problems.append('%s: срок сертификата %d дней, больше %d' % (n, life, inv['cert_days']))
        if days_left(info) <= 0:
            problems.append('%s: сертификат истёк' % n)
        elif days_left(info) < 14:
            problems.append('%s: до конца срока меньше 14 дней, нужен перевыпуск (make certs)' % n)
        try:
            if public_key_of_key(key) != public_key_of_cert(crt):
                problems.append('%s: ключ не подходит к сертификату' % n)
        except RuntimeError as e:
            problems.append('%s: ключ не читается (%s)' % (n, e))
    values = {}
    for s in inv['secrets']:
        p = os.path.join(d, s['name'])
        expected_files.add(s['name'])
        if not os.path.exists(p):
            problems.append('нет секрета %s' % s['name'])
            continue
        v = read_text(p)
        if v != v.strip() or '\n' in v:
            problems.append('секрет %s содержит пробелы или перевод строки' % s['name'])
        if len(v) < 24:
            problems.append('секрет %s короче 24 символов' % s['name'])
        if v in values:
            problems.append('секреты %s и %s одинаковы' % (s['name'], values[v]))
        values[v] = s['name']
    if os.path.isdir(d):
        extra = sorted(set(os.listdir(d)) - expected_files)
        if extra:
            problems.append('лишние файлы в каталоге секретов: %s' % ', '.join(extra))
    if problems:
        print('ПРОВЕРКА НЕ ПРОЙДЕНА:')
        for p in problems:
            print('  - ' + p)
        return 1
    print('проверка пройдена: центр сертификации, %d сертификатов, %d секретов' % (len(inv['containers']), len(inv['secrets'])))
    return 0


def cmd_status(args):
    inv = load_inventory()
    d = secrets_dir()
    print('%-20s %-12s %s' % ('контейнер', 'осталось', 'истекает'))
    for c in inv['containers']:
        crt = os.path.join(d, 'tls_%s.crt' % c['name'])
        if not os.path.exists(crt):
            print('%-20s %-12s %s' % (c['name'], '-', 'нет сертификата'))
            continue
        info = parse_cert(crt)
        print('%-20s %-12s %s' % (c['name'], '%d дн.' % days_left(info), info['not_after'].date()))
    return 0


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest='cmd', required=True)
    for name, fn, force in (('ca', cmd_ca, False), ('certs', cmd_certs, True), ('secrets', cmd_secrets, True),
                            ('verify', cmd_verify, False), ('status', cmd_status, False)):
        p = sub.add_parser(name)
        if force:
            p.add_argument('--force', action='store_true', help='перевыпустить или пересоздать даже существующее')
        p.set_defaults(fn=fn)
    args = ap.parse_args(argv)
    try:
        return args.fn(args)
    except RuntimeError as e:
        print('ошибка: %s' % e, file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main())
