# -*- coding: utf-8 -*-
"""Сверка compose.yaml с документами: что в Compose, то и в проекте.

    python3 tools/docs-checks/check_compose.py            проверка того, что в compose.yaml есть сейчас
    python3 tools/docs-checks/check_compose.py --complete то же, плюс в compose.yaml обязаны быть все контейнеры из документов
    python3 tools/docs-checks/check_compose.py --images   напечатать образы из compose.yaml (для проверки тегов в реестре)

Источники (docs/05-architecture/c4-deployment.md, docs/09-operations/memory-budget.md, docs/09-operations/versions.md,
infra/pki/inventory.json, Makefile):
  - каждый контейнер compose.yaml описан в документе, профиль совпадает с группой в c4-deployment.md (2.2) и memory-budget.md (2.1);
  - mem_limit и memswap_limit равны лимиту из memory-budget.md (принцип 3: своп выключен);
  - образ равен образу из versions.md (3), тега latest нет, точный номер версии указан;
  - наборы в Makefile раскрываются в те же профили, что в c4-deployment.md (раздел 3);
  - сети: data и obs закрыты (internal), edge только у api-gateway, контейнеры хранилищ только в data, принадлежность
    сервисов к data как в c4-deployment.md (4.2);
  - порты на хост: ни одного в compose.yaml, кроме api-gateway; отладочные порты только в compose.debug.yaml на 127.0.0.1
    и только из c4-deployment.md (4.3);
  - секреты: контейнер получает только свои секреты из inventory.json и свои tls_*, список секретов compose.yaml равен перечню;
  - защита контейнера: пользователь не root, cap_drop ALL, read_only, no-new-privileges, у контейнера есть проверка готовности
    (кроме разовых заданий), в окружении нет значений секретов;
  - тома: именованные только из c4-deployment.md (2.2) или отложенные с причиной, привязки только на чтение;
  - зависимости: цель depends_on существует, у service_healthy есть healthcheck.
Нужен PyYAML.
"""
import json
import os
import re
import sys

import yaml

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import docslib as L

COMPOSE = os.environ.get('DGM_COMPOSE_FILE') or os.path.join(L.REPO, 'compose.yaml')   # переменная нужна самопроверке контроля
DEBUG = os.path.join(L.REPO, 'compose.debug.yaml')
MAKEFILE = os.path.join(L.REPO, 'Makefile')
INVENTORY = os.path.join(L.REPO, 'infra', 'pki', 'inventory.json')
DEP = os.path.join(L.ARCH, 'c4-deployment.md')
MB = os.path.join(L.OPS_DIR, 'memory-budget.md')
VER = os.path.join(L.OPS_DIR, 'versions.md')

R1_PROFILES = ['infra', 'stubs', 'auth', 'gateway', 'purchase', 'platform', 'storage']
ONE_SHOT = {'kafka-init', 'storage-init'}     # завершаются сами, проверки готовности нет
# Образы без оболочки и wget (distroless): проверку готовности Docker задать нечем, готовность проверяет make obs-check по /ready
NO_HEALTHCHECK = {'loki': 'образ distroless', 'tempo': 'образ distroless'}
# Контейнеры стека наблюдения без собственного сертификата: говорят только внутри закрытой сети obs, без TLS
# (c4-deployment.md, решение 22). Сертификат нужен тем, кто пересекает границу: prometheus (клиент mTLS к сервисам) и alloy (приём OTLP).
OBS_NO_CERT = {'alertmanager', 'grafana', 'loki', 'tempo'}
JAVA_SERVICES = {'catalog-service', 'inventory-service', 'order-service', 'payment-service', 'delivery-service', 'platform-service',
                 'api-gateway'}                  # входят в сеть obs: метрики и OTLP (c4-deployment.md 4.2)
BUILT_FROM_REPO = {'catalog-service', 'inventory-service', 'order-service', 'payment-service', 'delivery-service',
                   'platform-service', 'api-gateway', 'web-app', 'external-stubs', 'keycloak'}   # образы собираются из репозитория: dgm/<имя>:${IMAGE_TAG:-dev}
# Если образ собирается Compose (раздел build), базы сборки из versions.md задаются аргументами сборки; значение по умолчанию в Dockerfile
# и переменная Makefile (нужна командам вне Compose, например make stubs-test; None, если такой переменной нет) обязаны совпадать с ним.
# Элемент: (аргумент сборки, Dockerfile, переменная Makefile, строка versions.md, в которой записан образ)
BASE_IMAGE_ARGS = {
    'external-stubs': [('NODE_IMAGE', 'docker/Dockerfile.stubs', 'NODE_IMAGE', 'external-stubs')],
    'web-app': [('NGINX_IMAGE', 'docker/Dockerfile.web', 'NGINX_IMAGE', 'web-app')],
    'keycloak': [('KEYCLOAK_IMAGE', 'docker/Dockerfile.keycloak', None, 'keycloak'),
                 ('JDK_IMAGE', 'docker/Dockerfile.keycloak', None, 'keycloak-spi'),
                 ('UBI_IMAGE', 'docker/Dockerfile.keycloak', None, 'keycloak-curl')],
}
DEFERRED = {'backup-job': 'Ф6', 'certbot': 'Ф6'}          # контейнеры, которых в Ф3 нет
DEFERRED_VOLUMES = {'pgarchive': 'Ф6, вместе с backup-job', 'backups': 'Ф6', 'letsencrypt': 'Ф6'}
STORAGE_SERVICES = {'postgres', 'redis', 'kafka', 'kafka-init', 'object-storage', 'storage-init'}
ALLOWED_HOST_PORTS = {'api-gateway': {'8443:8443'}}
SECRETISH = re.compile(r'(PASSWORD|SECRET|TOKEN|PRIVATE|_KEY)', re.I)
UNLIMITED = {'ports'}


def parse_mem(rep):
    t = L.find_table(L.read(MB), 'Контейнер', 'Лимит, МБ')
    if not t:
        rep.err('memory-budget', 'нет таблицы лимитов')
        return {}
    out = {}
    for r in t['rows']:
        alias = L.backticked(r[1])[0]
        out[alias] = dict(group=L.backticked(r[2])[0], limit=int(re.search(r'\d+', r[3]).group(0)))
    return out


def parse_dep(rep):
    text = L.read(DEP)
    t22 = L.find_table(text, 'Контейнер', 'Алиас')
    cont = {}
    for r in t22['rows']:
        alias = L.backticked(r[1])[0]
        cont[alias] = dict(profile=L.backticked(r[3])[0], volumes=L.backticked(r[5]), vol_cell=r[5])
    sets = {}
    for t in L.tables(text):
        if t['header'][:2] == ['Набор', 'Профили']:
            for r in t['rows']:
                sets[L.backticked(r[0])[0]] = r[1]
    # принадлежность сервисов к data из 4.2
    m = re.search(r'Принадлежность сервисов к сети `data`:(.*?)\n\n', text, re.S)
    data_services = {}
    if m:
        for name, what in re.findall(r'`([a-z-]+)` \(([^)]*)\)', m.group(1)):
            data_services[name] = what
    # отладочные порты из 4.3
    debug_ports = set()
    for t in L.tables(text):
        if t['header'][:2] == ['Среда', 'Порт хоста']:
            for r in t['rows']:
                if 'отлад' in r[0]:
                    debug_ports |= set(re.findall(r'\d+', r[1]))
    return cont, sets, data_services, debug_ports


def expand_sets(raw):
    """Раскрывает наборы из таблицы c4-deployment.md в списки профилей."""
    res = {}

    def prof(name, depth=0):
        if depth > 5:
            raise ValueError('цикл в наборах')
        cell = raw[name]
        if 'все профили R1' in cell:
            return list(R1_PROFILES)
        out = []
        for tok in L.backticked(cell):
            if tok in raw:
                for p in prof(tok, depth + 1):
                    if p not in out:
                        out.append(p)
            elif tok not in out:
                out.append(tok)
        return out
    for n in raw:
        res[n] = prof(n)
    return res


def parse_makefile(rep):
    text = L.read(MAKEFILE)
    raw = dict(re.findall(r'^SET_([\w-]+)\s*:?=\s*(.+)$', text, re.M))
    res = {}
    for n in raw:
        v = raw[n].strip()
        for _ in range(5):
            v = re.sub(r'\$\(SET_([\w-]+)\)', lambda m: raw.get(m.group(1), ''), v)
        res[n] = [p for p in v.split(',') if p]
    return res


def images_from_versions():
    t = L.find_table(L.read(VER), 'Контейнер', 'Образ')
    out = {}
    for r in t['rows']:
        img = L.backticked(r[1])
        if not img or ':' not in img[0]:
            continue
        for alias in L.backticked(r[0]):
            out[alias] = img[0]
    return out


def load_yaml(path):
    with open(path, encoding='utf-8') as f:
        return yaml.safe_load(f)


def main():
    rep = L.Report('compose')
    complete = '--complete' in sys.argv
    dc = load_yaml(COMPOSE)
    dbg = load_yaml(DEBUG) if os.path.exists(DEBUG) else {'services': {}, 'networks': {}}
    services = dc.get('services', {})

    if '--images' in sys.argv:
        for name, s in services.items():
            print(s['image'] if 'image' in s else '')
        return 0

    limits = parse_mem(rep)
    dep, raw_sets, data_services, debug_ports = parse_dep(rep)
    doc_sets = expand_sets(raw_sets)
    inv = json.load(open(INVENTORY, encoding='utf-8'))
    inv_secrets = {s['name']: s for s in inv['secrets']}
    inv_containers = {c['name'] for c in inv['containers']}
    inv_jobs = {j['name'] for j in inv.get('jobs', [])}   # разовые задания без собственного сертификата (входят по паролю из секрета)
    inv_nocert = {r['name'] for r in inv.get('readers_without_cert', [])}   # читают секреты, но сертификата у них нет (стек наблюдения без TLS)
    for n in sorted(inv_nocert):
        if n not in OBS_NO_CERT:
            rep.err('inventory.json', 'readers_without_cert: %s не входит в OBS_NO_CERT (контейнеры стека наблюдения без TLS)' % n)
        if n in inv_containers or n in inv_jobs:
            rep.err('inventory.json', 'readers_without_cert: %s одновременно в containers или jobs' % n)
    for name, info in inv_secrets.items():
        for r in info['readers']:
            if r not in inv_containers and r not in inv_jobs and r not in inv_nocert:
                rep.err('inventory.json', 'секрет %s: читатель %s не описан ни в containers, ни в jobs, ни в readers_without_cert' % (name, r))
    versions = images_from_versions()

    # --- наборы Makefile и документа
    mk = parse_makefile(rep)
    for n, profs in doc_sets.items():
        if n not in mk:
            rep.err('Makefile', 'нет набора %s из c4-deployment.md' % n)
        elif sorted(mk[n]) != sorted(profs):
            rep.err('Makefile', 'набор %s: профили %s, в c4-deployment.md %s' % (n, sorted(mk[n]), sorted(profs)))
    for n in mk:
        if n not in doc_sets:
            rep.err('Makefile', 'набор %s не описан в c4-deployment.md' % n)

    # --- сети
    nets = dc.get('networks', {})
    for n in ('edge', 'app', 'data', 'obs'):
        if n not in nets:
            rep.err('networks', 'нет сети %s' % n)
    for n in ('data', 'obs'):
        if not (nets.get(n) or {}).get('internal'):
            rep.err('networks', 'сеть %s должна быть закрыта (internal: true): c4-deployment.md 4.2' % n)
    for n in ('edge', 'app'):
        if (nets.get(n) or {}).get('internal'):
            rep.err('networks', 'сеть %s не должна быть internal: через неё идёт вход и выход сервисов' % n)

    # --- состав
    present = set(services)
    for alias in sorted(present):
        if alias not in dep:
            rep.err('compose', 'контейнер %s не описан в c4-deployment.md (2.2)' % alias)
        if alias not in limits:
            rep.err('compose', 'у контейнера %s нет лимита в memory-budget.md (2.1)' % alias)
    if complete:
        for alias in sorted(dep):
            if alias not in present and alias not in DEFERRED:
                rep.err('compose', 'в compose.yaml нет контейнера %s из c4-deployment.md' % alias)
    for alias, why in DEFERRED.items():
        if alias in present:
            rep.err('compose', 'контейнер %s отложен до %s, в Ф3 его быть не должно' % (alias, why))

    # --- разбор каждого контейнера
    all_vols = set()
    for alias in sorted(present):
        s = services[alias]
        d = dep.get(alias)
        lim = limits.get(alias)
        w = 'compose.yaml, %s' % alias

        # профиль
        if d and s.get('profiles') != [d['profile']]:
            rep.err(w, 'профили %s, в c4-deployment.md %s' % (s.get('profiles'), d['profile']))
        if d and lim and d['profile'] != lim['group']:
            rep.err(w, 'профиль %s в c4-deployment.md, группа %s в memory-budget.md' % (d['profile'], lim['group']))

        # память
        for key in ('mem_limit', 'memswap_limit'):
            v = str(s.get(key, ''))
            m = re.fullmatch(r'(\d+)m', v)
            if not m:
                rep.err(w, '%s задан как «%s», нужно целое число мегабайт с суффиксом m (например 512m)' % (key, v))
            elif lim and int(m.group(1)) != lim['limit']:
                rep.err(w, '%s %s МБ, в memory-budget.md %d МБ' % (key, m.group(1), lim['limit']))

        # образ
        img = s.get('image')
        if not img:
            rep.err(w, 'нет image')
        if alias in BUILT_FROM_REPO:
            if img != 'dgm/%s:${IMAGE_TAG:-dev}' % alias:
                rep.err(w, 'образ собирается из репозитория, ожидалось dgm/%s:${IMAGE_TAG:-dev}, сейчас %s' % (alias, img))
            for arg, dockerfile, make_var, ver_alias in BASE_IMAGE_ARGS.get(alias, []):
                base = versions.get(ver_alias)
                build = s.get('build') or {}
                if not build:
                    rep.err(w, 'нет раздела build: образ должен собираться при make up')
                elif (build.get('args') or {}).get(arg) != base:
                    rep.err(w, 'build.args.%s = %s, в versions.md (%s) %s' % (arg, (build.get('args') or {}).get(arg), ver_alias, base))
                df = re.search(r'^ARG %s=(\S+)' % arg, L.read(os.path.join(L.REPO, dockerfile)), re.M)
                if not df or df.group(1) != base:
                    rep.err(dockerfile, 'значение по умолчанию ARG %s = %s, в versions.md (%s) %s' % (arg, df.group(1) if df else None, ver_alias, base))
                if make_var:
                    mk_var = re.search(r'^%s\s*\??=\s*(\S+)' % make_var, L.read(MAKEFILE), re.M)
                    if not mk_var or mk_var.group(1) != base:
                        rep.err('Makefile', 'переменная %s = %s, в versions.md (%s) %s' % (make_var, mk_var.group(1) if mk_var else None, ver_alias, base))
        elif img:
            if img.endswith(':latest') or ':' not in img:
                rep.err(w, 'образ %s без точной версии' % img)
            want = versions.get(alias)
            if want is None:
                rep.err(w, 'образа нет в versions.md (раздел 3)')
            elif img != want:
                rep.err(w, 'образ %s, в versions.md %s' % (img, want))

        # защита
        user = str(s.get('user', ''))
        if not re.fullmatch(r'[1-9]\d*(:[1-9]?\d*)?', user):
            rep.err(w, 'user «%s»: нужен числовой пользователь не root' % user)
        if s.get('cap_drop') != ['ALL']:
            rep.err(w, 'cap_drop должен быть [ALL]')
        if s.get('read_only') is not True:
            rep.err(w, 'read_only должен быть true')
        if 'no-new-privileges:true' not in (s.get('security_opt') or []):
            rep.err(w, 'нужна security_opt no-new-privileges:true')
        if s.get('privileged') or s.get('network_mode') == 'host' or s.get('pid') == 'host':
            rep.err(w, 'privileged, network_mode host и pid host запрещены')
        if alias in NO_HEALTHCHECK:
            if s.get('healthcheck'):
                rep.err(w, 'у контейнера задан healthcheck, а образ без оболочки (%s): проверка не сможет выполниться' % NO_HEALTHCHECK[alias])
        elif alias not in ONE_SHOT and not s.get('healthcheck'):
            rep.err(w, 'нет healthcheck (c4-deployment.md 2.2, столбец «Проверка готовности»)')
        if not str(s.get('restart', '')).startswith('${DGM_RESTART') and alias not in ONE_SHOT:
            rep.err(w, 'restart должен быть ${DGM_RESTART:-no}: на сервере unless-stopped, на ноутбуке no (c4-deployment.md 7)')

        # окружение без секретов
        env = s.get('environment') or {}
        if isinstance(env, list):
            env = dict(e.split('=', 1) if '=' in e else (e, '') for e in env)
        for k, v in env.items():
            if SECRETISH.search(k) and not k.endswith('_FILE') and str(v) not in ('', 'false', 'true'):
                rep.err(w, 'переменная окружения %s похожа на секрет, секреты передаются файлами (NFT-3.2)' % k)

        # порты
        for p in s.get('ports') or []:
            if str(p) not in ALLOWED_HOST_PORTS.get(alias, set()):
                rep.err(w, 'порт %s публикуется на хост, в compose.yaml это допустимо только для %s (отладочные порты в compose.debug.yaml)'
                        % (p, ALLOWED_HOST_PORTS))

        # сети
        sn = s.get('networks') or []
        if isinstance(sn, dict):
            sn = list(sn)
        for n in sn:
            if n not in nets:
                rep.err(w, 'сеть %s не объявлена' % n)
        if not sn:
            rep.err(w, 'не указана сеть')
        if 'edge' in sn and alias != 'api-gateway':
            rep.err(w, 'в сети edge только api-gateway (c4-deployment.md 4.2)')
        if alias in STORAGE_SERVICES and set(sn) != {'data'}:
            rep.err(w, 'хранилище должно быть только в сети data, сейчас %s' % sn)
        if alias in JAVA_SERVICES and 'obs' not in sn:
            rep.err(w, 'сервис должен быть в сети obs: метрики и OTLP (c4-deployment.md 4.2)')
        if alias not in JAVA_SERVICES and alias not in OBS_NO_CERT and alias not in ('prometheus', 'alloy') and 'obs' in sn:
            rep.err(w, 'в сети obs только сервисы Java и контейнеры стека наблюдения (c4-deployment.md 4.2)')
        if alias in BUILT_FROM_REPO and alias != 'api-gateway':
            if 'app' not in sn:
                rep.err(w, 'сервис должен быть в сети app')
            if (alias in data_services) != ('data' in sn):
                rep.err(w, 'принадлежность к data %s, в c4-deployment.md 4.2 %s' % ('data' in sn, alias in data_services))

        # тома
        for v in s.get('volumes') or []:
            src, _, rest = str(v).partition(':')
            if src.startswith('.') or src.startswith('/'):
                if not str(v).endswith(':ro'):
                    rep.err(w, 'привязка %s должна быть только для чтения (:ro)' % v)
            else:
                all_vols.add(src)
                if d and src not in d['volumes']:
                    rep.err(w, 'том %s не описан в c4-deployment.md (2.2): %s' % (src, d['volumes']))
        if d:
            have = {str(v).split(':')[0] for v in (s.get('volumes') or []) if not str(v).startswith('.')}
            for need in d['volumes']:
                if need not in have and need not in DEFERRED_VOLUMES:
                    rep.err(w, 'том %s из c4-deployment.md не подключён' % need)

        # зависимости
        for dn, cond in (s.get('depends_on') or {}).items():
            if dn not in services:
                rep.err(w, 'depends_on: нет контейнера %s' % dn)
            elif cond.get('condition') == 'service_healthy' and not services[dn].get('healthcheck'):
                rep.err(w, 'depends_on %s: service_healthy, но у него нет healthcheck' % dn)

        # секреты
        mounted = set()
        for sec in s.get('secrets') or []:
            name = sec if isinstance(sec, str) else sec.get('source')
            mounted.add(name)
            m = re.fullmatch(r'tls_(.+)\.(key|crt)', name)
            if alias in OBS_NO_CERT and (name == 'tls_ca.crt' or m):
                rep.err(w, 'у контейнера стека наблюдения без TLS подключён %s: сертификат ему не нужен (OBS_NO_CERT)' % name)
                continue
            if name == 'tls_ca.crt' or m:
                if m and name != 'tls_ca.crt' and m.group(1) != alias:
                    rep.err(w, 'секрет %s чужого контейнера' % name)
                continue
            if name not in inv_secrets:
                rep.err(w, 'секрет %s не описан в inventory.json' % name)
            elif alias not in inv_secrets[name]['readers']:
                rep.err(w, 'секрет %s: читатели по inventory.json %s, контейнера %s среди них нет' % (name, inv_secrets[name]['readers'], alias))
        for name, info in inv_secrets.items():
            if alias in info['readers'] and name not in mounted:
                rep.err(w, 'секрет %s по inventory.json читает %s, но не подключён' % (name, alias))
        if alias in inv_jobs:
            if alias in inv_containers:
                rep.err(w, 'разовое задание (inventory.json, jobs) не должно быть в списке containers: сертификат ему не выпускается')
            if alias not in ONE_SHOT:
                rep.err(w, 'в inventory.json (jobs) значится %s, а это не разовое задание' % alias)
            for suffix in ('key', 'crt'):
                if 'tls_%s.%s' % (alias, suffix) in mounted:
                    rep.err(w, 'у разового задания нет своего сертификата, подключён tls_%s.%s' % (alias, suffix))
        elif alias in OBS_NO_CERT:
            if alias in inv_containers:
                rep.err(w, 'контейнер без TLS (OBS_NO_CERT) не должен быть в inventory.json (containers): сертификат ему не нужен')
        elif alias in inv_containers:
            for suffix in ('key', 'crt'):
                if 'tls_%s.%s' % (alias, suffix) not in mounted:
                    rep.err(w, 'не подключён tls_%s.%s' % (alias, suffix))
        else:
            rep.err(w, 'контейнера нет в inventory.json (containers), сертификат не будет выпущен')
        if 'tls_ca.crt' not in mounted and alias not in OBS_NO_CERT:
            rep.err(w, 'не подключён tls_ca.crt')

    # --- список секретов compose.yaml равен перечню
    top = set((dc.get('secrets') or {}).keys())
    want = {'tls_ca.crt'} | set(inv_secrets)
    for c in inv_containers:
        want |= {'tls_%s.key' % c, 'tls_%s.crt' % c}
    for n in sorted(want - top):
        rep.err('compose.yaml, secrets', 'секрет %s из inventory.json не объявлен' % n)
    for n in sorted(top - want):
        rep.err('compose.yaml, secrets', 'секрет %s не описан в inventory.json' % n)
    for n, v in (dc.get('secrets') or {}).items():
        f = str(v.get('file', ''))
        if not f.endswith('/' + n):
            rep.err('compose.yaml, secrets', '%s: файл «%s» должен называться так же, как секрет' % (n, f))

    # --- именованные тома объявлены, лишних нет
    declared = set((dc.get('volumes') or {}).keys())
    for v in sorted(all_vols - declared):
        rep.err('compose.yaml, volumes', 'том %s используется, но не объявлен' % v)
    for v in sorted(declared - all_vols):
        rep.err('compose.yaml, volumes', 'том %s объявлен, но не используется' % v)

    # --- compose.debug.yaml
    for alias, s in (dbg.get('services') or {}).items():
        if alias not in services:
            rep.err('compose.debug.yaml', 'нет контейнера %s в compose.yaml' % alias)
        for p in s.get('ports') or []:
            m = re.fullmatch(r'127\.0\.0\.1:(\d+):\d+', str(p))
            if not m:
                rep.err('compose.debug.yaml', '%s: порт %s должен быть привязан к 127.0.0.1' % (alias, p))
            elif m.group(1) not in debug_ports:
                rep.err('compose.debug.yaml', '%s: порта %s нет среди отладочных в c4-deployment.md (4.3): %s' % (alias, m.group(1), sorted(debug_ports)))
        for n in s.get('networks') or []:
            if n not in (dbg.get('networks') or {}) and n not in nets:
                rep.err('compose.debug.yaml', '%s: сеть %s не объявлена' % (alias, n))

    # --- суммы по группам при полном составе
    by_group = {}
    for alias in present:
        if alias in limits:
            by_group.setdefault(limits[alias]['group'], 0)
            by_group[limits[alias]['group']] += limits[alias]['limit']
    if complete:
        mem = L.read(MB)
        t = L.find_table(mem, 'Группа', 'Сумма, МБ')
        for r in t['rows']:
            g = L.backticked(r[0])[0]
            if g in DEFERRED_GROUPS_SKIP:
                continue
            if by_group.get(g) != int(re.search(r'\d+', r[2]).group(0)):
                rep.err('memory-budget 2.2', 'группа %s: в compose.yaml %s МБ, в таблице %s' % (g, by_group.get(g), r[2]))

    rep.fact('compose.yaml: %d контейнеров (%s), секретов %d, лимиты по группам %s%s'
             % (len(present), ', '.join(sorted(present)), len(top), by_group, ', полный состав' if complete else ''))
    return rep.finish()


DEFERRED_GROUPS_SKIP = {'ops'}

if __name__ == '__main__':
    sys.exit(main())
