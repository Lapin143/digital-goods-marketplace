#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Сквозные проверки заявки продавца и документов на настоящем стенде (шаг 10 Ф4; US-2.1, ST-12 в части заголовков скачивания, T-36).

    make sellers-check

Нужен набор full (платформа, Keycloak, шлюз, каталог, объектное хранилище) с отладочным файлом: make up SET=full DEBUG=1, затем
make keycloak-users. Проверка идёт настоящим входом и настоящими запросами через шлюз; общие тестовые пользователи не трогаются: заявка
создаётся от одноразового покупателя, которого проверка регистрирует через страницу регистрации. Что проверяется:

  заявка        черновик, ETag и If-Match, устаревшая версия, подача без документа и с документом, срок рассмотрения 3 суток;
  документы     загрузка PDF через шлюз, текст под видом PDF и слишком большое тело отвергаются, лишний файл не принимается;
  ссылка        подписанная ссылка открывается через /files шлюза без токена, отдаёт тот же файл, с заголовками «только скачать» (Р-23),
                токен пользователя до хранилища не доходит, чужая подпись и запрос без подписи отвергаются;
  удаление      после удаления документа ссылка не выдаётся, а файл из хранилища исчез.

Тип файла по содержимому, границы размера (2 МБ и 2 МБ + 1 байт), одиннадцатый файл, пауза после отказа и срок ссылки на 301-й секунде
проверяют интеграционные тесты каталога (SellerOnboardingIT): им нужны управляемое время и прямой доступ к базе. Только стандартная библиотека Python.
"""
import json
import os
import re
import sys
import time
import urllib.parse
import uuid
from datetime import datetime

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import accounts_checks as A  # noqa: E402
import gateway_checks as G  # noqa: E402
import kc_client as K  # noqa: E402

expect = G.expect
BASE = '/api/v1/seller-applications'
CURRENT = BASE + '/current'
FORM = {
    'sellerType': 'self_employed',
    'name': 'Студия «Лунный свет»',
    'payoutDetails': {'recipientName': 'Иванов Иван Иванович', 'bankName': 'Банк «Пример»', 'bik': '044525000', 'accountNumber': '40817810000000000001'},
    'assortmentDescription': 'Ключи к инди-играм для Steam',
}
MIB = 1024 * 1024


# --------------------------------------------------------------------------------------------------- вспомогательное
def auth(token, **extra):
    headers = dict(G.bearer(token))
    headers.update(extra)
    return headers


def post_json(path, token, body=None):
    headers = auth(token, **{'Content-Type': 'application/json', 'Idempotency-Key': str(uuid.uuid4())})
    return G.call('POST', path, headers=headers, body=json.dumps(body).encode() if body is not None else None)


def put_json(path, token, etag, body):
    headers = auth(token, **{'Content-Type': 'application/json', 'If-Match': etag})
    return G.call('PUT', path, headers=headers, body=json.dumps(body).encode())


def multipart(file_name, content):
    """Тело формы с одной частью file; Content-Type части произвольный, сервис его не использует."""
    boundary = '----dgm' + uuid.uuid4().hex
    head = ('--%s\r\nContent-Disposition: form-data; name="file"; filename="%s"\r\nContent-Type: application/pdf\r\n\r\n' % (boundary, file_name)).encode()
    tail = ('\r\n--%s--\r\n' % boundary).encode()
    return 'multipart/form-data; boundary=' + boundary, head + content + tail


def upload(token, file_name, content):
    content_type, body = multipart(file_name, content)
    headers = auth(token, **{'Content-Type': content_type, 'Idempotency-Key': str(uuid.uuid4())})
    return G.call('POST', CURRENT + '/documents', headers=headers, body=body, timeout=60)


def pdf(size):
    """Начало PDF и двоичный хвост: тип определяется по первым байтам."""
    head = b'%PDF-1.7\n'
    tail = bytes((i * 31 + 7) % 256 for i in range(size - len(head)))
    return head + tail


def code_of(response):
    try:
        return response.json().get('code')
    except ValueError:
        return None


def instant(text):
    return datetime.fromisoformat(text.replace('Z', '+00:00'))


def storage_path(url):
    """Адрес ссылки для запроса к шлюзу: путь и строка запроса без имени узла."""
    parsed = urllib.parse.urlsplit(url)
    return parsed.path + ('?' + parsed.query if parsed.query else '')


# --------------------------------------------------------------------------------------------------- проверки
def application_checks(token):
    print('== Заявка: черновик, ETag, подача (US-2.1; SM-05, T1 и T2; TC-201, TC-202, TC-203, TC-204, TC-205)')
    none = G.call('GET', CURRENT, headers=auth(token))
    expect('до создания заявки профиля нет: 404', none.status == 404, none.text[:200])

    created = post_json(BASE, token, {'sellerType': 'self_employed', 'name': 'Студия'})
    expect('черновик создан: 201, статус draft, Location и ETag', created.status == 201 and created.json().get('status') == 'draft'
           and created.header('Location') == CURRENT and re.fullmatch(r'"\d+"', created.header('ETag') or ''), '%s %s' % (created.status, created.text[:200]))
    second = post_json(BASE, token, FORM)
    expect('вторая заявка не создаётся (INV-39): 409 state-conflict', second.status == 409 and code_of(second) == 'state-conflict', '%s %s' % (second.status, second.text[:200]))

    etag = created.header('ETag')
    saved = put_json(CURRENT, token, etag, FORM)
    expect('анкета сохранена с If-Match: 200, версия выросла', saved.status == 200 and saved.header('ETag') not in (None, etag), '%s %s' % (saved.status, saved.text[:200]))
    stale = put_json(CURRENT, token, etag, FORM)
    expect('устаревшая версия: 412 version-conflict', stale.status == 412 and code_of(stale) == 'version-conflict', '%s %s' % (stale.status, stale.text[:200]))
    nothing = G.call('PUT', CURRENT, headers=auth(token, **{'Content-Type': 'application/json'}), body=json.dumps(FORM).encode())
    expect('без If-Match: 428 precondition-required', nothing.status == 428 and code_of(nothing) == 'precondition-required', '%s %s' % (nothing.status, nothing.text[:200]))

    early = post_json(CURRENT + '/submit', token)
    pointers = [e.get('pointer') for e in (early.json().get('errors') or [])] if early.status == 422 else []
    expect('подача без документа: 422, поле /documents', early.status == 422 and pointers == ['/documents'], '%s %s' % (early.status, early.text[:200]))
    return saved.header('ETag')


def document_checks(token):
    print('== Документы: загрузка через шлюз, отказы по типу и размеру (TC-206)')
    content = pdf(4096)
    uploaded = upload(token, 'справка.pdf', content)
    ok = uploaded.status == 201 and uploaded.json().get('contentType') == 'application/pdf' and uploaded.json().get('sizeBytes') == len(content)
    expect('PDF принят: 201, тип application/pdf, размер совпал', ok, '%s %s' % (uploaded.status, uploaded.text[:200]))
    if not ok:
        return None
    document_id = uploaded.json()['documentId']
    expect('Location указывает на документ', (uploaded.header('Location') or '').endswith('/documents/' + document_id), uploaded.header('Location'))

    text = upload(token, 'письмо.pdf', 'Это просто текст, а не PDF'.encode())
    expect('текст под видом .pdf: 415 unsupported-media-type', text.status == 415 and code_of(text) == 'unsupported-media-type', '%s %s' % (text.status, text.text[:200]))
    empty = upload(token, 'пусто.pdf', b'')
    expect('пустой файл: 422', empty.status == 422, '%s %s' % (empty.status, empty.text[:200]))
    over = upload(token, 'больше.pdf', pdf(2 * MIB + 1))
    expect('2 МБ + 1 байт: 413 payload-too-large', over.status == 413 and code_of(over) == 'payload-too-large', '%s %s' % (over.status, over.text[:200]))
    headers = auth(token, **{'Content-Type': 'multipart/form-data; boundary=x', 'Content-Length': str(8 * MIB), 'Idempotency-Key': str(uuid.uuid4())})
    huge = G.raw_call('POST', CURRENT + '/documents', headers)
    expect('заявленное тело в 8 МБ шлюз отвергает, не дожидаясь тела: 413', huge.status == 413, huge.status)

    state = G.call('GET', CURRENT, headers=auth(token)).json()
    expect('отвергнутые файлы в заявку не попали: один документ', len(state.get('documents') or []) == 1, state.get('documents'))
    return document_id, content


def link_checks(token, document_id, content):
    print('== Ссылка на документ: открывается через /files шлюза, только скачать (Р-23, T-36; TC-207)')
    issued = G.call('GET', '%s/documents/%s/link' % (CURRENT, document_id), headers=auth(token))
    body = issued.json() if issued.status == 200 else {}
    url = body.get('url', '')
    expect('ссылка выдана: 200, адрес на /files шлюза', issued.status == 200 and '/files/seller-documents/' in url and 'X-Amz-Signature=' in url, '%s %s' % (issued.status, issued.text[:200]))
    if issued.status != 200:
        return None
    left = (instant(body['expiresAt']) - datetime.now(instant(body['expiresAt']).tzinfo)).total_seconds()
    expect('срок ссылки около 5 минут (осталось %d с)' % left, 270 < left <= 300, left)

    path = storage_path(url)
    file = G.call('GET', path)
    expect('файл открывается по ссылке без токена и совпадает с загруженным', file.status == 200 and file.body == content, '%s, %d байт' % (file.status, len(file.body)))
    expect('Content-Disposition: attachment (открыть в браузере нельзя)', (file.header('Content-Disposition') or '').lower().startswith('attachment'), file.header('Content-Disposition'))
    expect('Content-Security-Policy запрещает исполнение и встраивание', "default-src 'none'" in (file.header('Content-Security-Policy') or '')
           and 'sandbox' in (file.header('Content-Security-Policy') or ''), file.header('Content-Security-Policy'))
    expect('Cache-Control: private, no-store', 'no-store' in (file.header('Cache-Control') or ''), file.header('Cache-Control'))

    with_token = G.call('GET', path, headers=auth(token))
    expect('токен пользователя до хранилища не доходит: запрос с Authorization тоже даёт файл', with_token.status == 200 and with_token.body == content,
           '%s %s' % (with_token.status, with_token.text[:160]))
    forged = G.call('GET', re.sub(r'X-Amz-Signature=[0-9a-f]+', 'X-Amz-Signature=' + '0' * 64, path))
    expect('чужая подпись: файл не отдаётся (%s)' % forged.status, forged.status in (400, 401, 403) and forged.body != content, forged.status)
    anonymous = G.call('GET', path.split('?')[0])
    expect('запрос без подписи: бакет закрытый, файл не отдаётся (%s)' % anonymous.status, anonymous.status in (400, 401, 403) and anonymous.body != content, anonymous.status)
    return path


def removal_checks(token, document_id, path):
    print('== Удаление документа и подача заявки (TC-207)')
    removed = G.call('DELETE', '%s/documents/%s' % (CURRENT, document_id), headers=auth(token))
    expect('документ удалён: 204', removed.status == 204, '%s %s' % (removed.status, removed.text[:200]))
    again = G.call('DELETE', '%s/documents/%s' % (CURRENT, document_id), headers=auth(token))
    expect('повторное удаление: 404', again.status == 404, again.status)
    gone = G.call('GET', '%s/documents/%s/link' % (CURRENT, document_id), headers=auth(token))
    expect('ссылка на удалённый документ не выдаётся: 404', gone.status == 404, gone.status)
    old = G.call('GET', path)
    expect('прежняя ссылка файла больше не даёт: объект удалён из хранилища (%s)' % old.status, old.status == 404, old.status)

    content = pdf(2048)
    replaced = upload(token, 'новая справка.pdf', content)
    expect('новый документ принят: 201', replaced.status == 201, '%s %s' % (replaced.status, replaced.text[:200]))
    submitted = post_json(CURRENT + '/submit', token)
    body = submitted.json() if submitted.status == 200 else {}
    expect('заявка подана: 200, статус on_review', submitted.status == 200 and body.get('status') == 'on_review', '%s %s' % (submitted.status, submitted.text[:200]))
    if body:
        period = (instant(body['reviewDeadlineAt']) - instant(body['submittedAt'])).total_seconds()
        expect('срок рассмотрения 3 суток от подачи (%d с)' % period, period == 3 * 24 * 3600, period)
    edit = put_json(CURRENT, token, submitted.header('ETag') or '"1"', FORM)
    expect('на проверке анкету не правят: 409 state-conflict', edit.status == 409 and code_of(edit) == 'state-conflict', '%s %s' % (edit.status, edit.text[:200]))
    late = upload(token, 'позже.pdf', pdf(512))
    expect('на проверке документы не добавляют: 409', late.status == 409, '%s %s' % (late.status, late.text[:200]))


def main():
    kc, admin = K.from_env(dict(os.environ))
    stamp = int(time.time())
    print('== Одноразовый покупатель')
    buyer = A.new_buyer(kc, admin, stamp, 'seller')
    if not buyer:
        return 1
    r = kc.login('dgm-web', buyer['email'], buyer['password'])
    if not expect('вход одноразового покупателя: токен получен', r.ok, A.S.describe(r)):
        return 1
    token = r.tokens['access_token']
    application_checks(token)
    uploaded = document_checks(token)
    if uploaded:
        path = link_checks(token, *uploaded)
        if path:
            removal_checks(token, uploaded[0], path)
    print('\nПроверок успешно: %d, с ошибками: %d' % (G.PASSED, len(G.FAILED)))
    for name in G.FAILED:
        print('  - %s' % name)
    if os.environ.get('GITHUB_ACTIONS'):
        print('::notice title=sellers_checks::Проверок успешно: %d, с ошибками: %d%s' % (
            G.PASSED, len(G.FAILED), ('; не прошли: ' + '|'.join(G.FAILED)) if G.FAILED else ''))
    return 1 if G.FAILED else 0


if __name__ == '__main__':
    sys.exit(main())
