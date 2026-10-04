#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Клиент Keycloak для проверок стенда: вход по коду авторизации с PKCE (как браузер), TOTP, Admin REST. Только стандартная библиотека.

Вход проходит настоящий поток браузера: страница входа, форма пароля, форма второго фактора, переход на redirect_uri с кодом, обмен кода
на токены. Так проверяется то, что делает realm (поток с условным вторым фактором, области, утверждения), а не только настройки в файле.

Адреса. Токены выпускаются для внешнего адреса (https://localhost:8443/auth, issuer в токене), а запросы идут туда, где Keycloak слушает
на самом деле (отладочный порт контейнера). Соответствие задаёт таблица маршрутов Route: «запрос к host:port с префиксом пути идёт на
scheme://host:port». Проверка сертификата идёт по имени из URL (localhost), а не по адресу подключения.
"""
import base64
import hashlib
import hmac
import html
import html.parser
import http.client
import json
import re
import secrets
import socket
import ssl
import struct
import time
import urllib.parse


class Route:
    def __init__(self, host, port, prefix='/', target='https://127.0.0.1:443'):
        t = urllib.parse.urlsplit(target)
        self.host, self.port, self.prefix = host, port, prefix
        self.scheme, self.thost, self.tport = t.scheme, t.hostname, t.port or (443 if t.scheme == 'https' else 80)

    def matches(self, host, port, path):
        return host == self.host and port == self.port and path.startswith(self.prefix)


class Response:
    def __init__(self, status, headers, body):
        self.status, self.headers, self.body = status, headers, body

    @property
    def text(self):
        return self.body.decode('utf-8', 'replace')

    def json(self):
        return json.loads(self.body.decode('utf-8'))

    @property
    def location(self):
        return self.headers.get('location')


def _connection_class(base, target):
    class Conn(base):
        def connect(self):
            sock = socket.create_connection(target, self.timeout)
            if isinstance(self, http.client.HTTPSConnection):
                sock = self._context.wrap_socket(sock, server_hostname=self.host)
            self.sock = sock
    return Conn


class Net:
    """HTTP(S) с хранилищем cookie и таблицей маршрутов, переходы не выполняет сам."""

    def __init__(self, routes=(), cafile=None, timeout=30):
        self.routes = list(routes)
        self.ctx = ssl.create_default_context(cafile=cafile) if cafile else ssl.create_default_context()
        self.timeout = timeout
        self.cookies = {}

    def _route(self, scheme, host, port, path):
        for r in self.routes:
            if r.matches(host, port, path):
                return r.scheme, (r.thost, r.tport)
        return scheme, (host, port)

    def _cookie_header(self, host):
        jar = self.cookies.get(host, {})
        return '; '.join('%s=%s' % kv for kv in jar.items())

    def _store_cookies(self, host, msg):
        jar = self.cookies.setdefault(host, {})
        for line in msg.get_all('Set-Cookie') or []:
            first, *attrs = [p.strip() for p in line.split(';')]
            name, _, value = first.partition('=')
            expired = any(a.lower() == 'max-age=0' for a in attrs) or value == ''
            if expired:
                jar.pop(name, None)
            else:
                jar[name] = value

    def request(self, method, url, form=None, json_body=None, headers=None, body=None):
        u = urllib.parse.urlsplit(url)
        port = u.port or (443 if u.scheme == 'https' else 80)
        scheme, target = self._route(u.scheme, u.hostname, port, u.path)
        base = http.client.HTTPSConnection if scheme == 'https' else http.client.HTTPConnection
        cls = _connection_class(base, target)
        kw = {'context': self.ctx} if scheme == 'https' else {}
        conn = cls(u.hostname, port, timeout=self.timeout, **kw)
        h = {'Accept': 'text/html,application/json', 'Connection': 'close'}
        if headers:
            h.update(headers)
        if form is not None:
            body = urllib.parse.urlencode(form).encode()
            h['Content-Type'] = 'application/x-www-form-urlencoded'
        elif json_body is not None:
            body = json.dumps(json_body).encode()
            h['Content-Type'] = 'application/json'
        cookie = self._cookie_header(u.hostname)
        if cookie:
            h['Cookie'] = cookie
        path = u.path or '/'
        if u.query:
            path += '?' + u.query
        try:
            conn.request(method, path, body=body, headers=h)
            r = conn.getresponse()
            data = r.read()
            self._store_cookies(u.hostname, r.msg)
            return Response(r.status, {k.lower(): v for k, v in r.getheaders()}, data)
        finally:
            conn.close()


# --------------------------------------------------------------------------------------------------- токены, PKCE, TOTP
def b64url(data):
    return base64.urlsafe_b64encode(data).rstrip(b'=').decode()


def decode_jwt(token):
    """Заголовок и тело JWT без проверки подписи (подпись проверяет шлюз, здесь разбираются утверждения)."""
    parts = token.split('.')
    pad = lambda s: s + '=' * (-len(s) % 4)
    return (json.loads(base64.urlsafe_b64decode(pad(parts[0]))), json.loads(base64.urlsafe_b64decode(pad(parts[1]))))


def pkce_pair():
    verifier = b64url(secrets.token_bytes(48))
    return verifier, b64url(hashlib.sha256(verifier.encode()).digest())


def totp_code(secret, counter, digits=6):
    """HOTP по RFC 4226 с ключом secret (bytes). Keycloak хранит секрет TOTP строкой и считает по её байтам."""
    mac = hmac.new(secret, struct.pack('>Q', counter), hashlib.sha1).digest()
    off = mac[-1] & 0x0F
    num = (struct.unpack('>I', mac[off:off + 4])[0] & 0x7FFFFFFF) % (10 ** digits)
    return str(num).zfill(digits)


class TotpClock:
    """Выдаёт код для следующего свободного шага: тот же код Keycloak во второй раз не принимает (codeReusable=false)."""

    def __init__(self, period=30):
        self.period = period
        self.used = {}

    def next_code(self, secret_text, now=time.time):
        secret = secret_text.encode()
        step = int(now() // self.period)
        counter = max(step, self.used.get(secret_text, -1) + 1)
        if counter > step + 1:                      # окно просмотра вперёд один шаг: ждём начала следующего
            time.sleep((counter - step) * self.period - (now() % self.period) + 0.5)
            step = int(now() // self.period)
            counter = max(step, counter)
        self.used[secret_text] = counter
        return totp_code(secret, counter)


# --------------------------------------------------------------------------------------------------- разбор страниц Keycloak
class _Forms(html.parser.HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.forms, self._cur = [], None

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if tag == 'form':
            self._cur = {'id': a.get('id', ''), 'action': a.get('action', ''), 'inputs': {}}
            self.forms.append(self._cur)
        elif tag == 'input' and self._cur is not None and a.get('name'):
            if a.get('type') in ('submit', 'button', 'checkbox', 'radio', 'image'):
                return
            self._cur['inputs'].setdefault(a['name'], a.get('value', ''))

    def handle_endtag(self, tag):
        if tag == 'form':
            self._cur = None


def forms_of(page):
    p = _Forms()
    p.feed(page)
    return p.forms


def page_kind(page):
    """Что за страница: форма пароля, второй фактор, настройка TOTP, ошибка, другое."""
    names = {n for f in forms_of(page) for n in f['inputs']}
    if 'otp' in names and 'totpSecret' not in names:
        return 'otp'
    if 'totpSecret' in names or 'kc-totp-settings-form' in page:
        return 'totp_setup'
    if 'password' in names and ('username' in names or 'password-new' not in names):
        return 'login'
    if 'kc-error-message' in page or 'id="kc-error"' in page:
        return 'error'
    return 'other'


def page_message(page):
    """Текст сообщения об ошибке или предупреждения на странице Keycloak (для вывода при сбое)."""
    m = re.search(r'id="(?:input-error|kc-error-message)[^>]*>(.*?)</', page, re.S) or re.search(r'class="kc-feedback-text"[^>]*>(.*?)</', page, re.S)
    return html.unescape(re.sub(r'\s+', ' ', re.sub(r'<[^>]+>', ' ', m.group(1)))).strip() if m else ''


class LoginResult:
    def __init__(self, kind, tokens=None, page='', response=None, location=None):
        self.kind, self.tokens, self.page, self.response, self.location = kind, tokens, page, response, location

    @property
    def ok(self):
        return self.kind == 'tokens'

    def access(self):
        return decode_jwt(self.tokens['access_token'])[1]

    def id_claims(self):
        return decode_jwt(self.tokens['id_token'])[1]


class Keycloak:
    """Вход и административные вызовы. public: внешний адрес Keycloak (https://localhost:8443/auth), realm dgm."""

    def __init__(self, net, public='https://localhost:8443/auth', realm='dgm', redirect_base='https://localhost:8443', totp=None):
        self.net, self.public, self.realm, self.redirect_base = net, public.rstrip('/'), realm, redirect_base
        self.totp = totp or TotpClock()

    @property
    def issuer(self):
        return '%s/realms/%s' % (self.public, self.realm)

    def _follow(self, resp, stop_prefix, limit=12):
        """Идёт по переходам, пока не получит страницу (200), ответ с кодом на redirect_uri или ошибку."""
        for _ in range(limit):
            loc = resp.location
            if resp.status in (301, 302, 303, 307) and loc:
                loc = urllib.parse.urljoin(self.public + '/', loc)
                if loc.startswith(stop_prefix):
                    return resp, loc
                resp = self.net.request('GET', loc)
                continue
            return resp, None
        raise RuntimeError('слишком много переходов')

    def authorize(self, client_id, redirect_uri=None, scope='openid', extra=None):
        redirect_uri = redirect_uri or self.redirect_base + '/callback'
        verifier, challenge = pkce_pair()
        q = {'client_id': client_id, 'redirect_uri': redirect_uri, 'response_type': 'code', 'scope': scope,
             'state': secrets.token_urlsafe(8), 'nonce': secrets.token_urlsafe(8), 'code_challenge': challenge, 'code_challenge_method': 'S256'}
        q.update(extra or {})
        url = '%s/protocol/openid-connect/auth?%s' % (self.issuer, urllib.parse.urlencode(q))
        resp, loc = self._follow(self.net.request('GET', url), redirect_uri)
        return resp, loc, verifier, redirect_uri

    def exchange(self, client_id, code, verifier, redirect_uri):
        r = self.net.request('POST', self.issuer + '/protocol/openid-connect/token', form={
            'grant_type': 'authorization_code', 'client_id': client_id, 'code': code, 'redirect_uri': redirect_uri, 'code_verifier': verifier})
        return r

    def login(self, client_id, username, password, otp_secret=None, scope='openid', extra=None, otp_tries=3):
        """Полный вход. Результат: kind = tokens | otp_required | totp_setup | login_error | error | other."""
        self.net.cookies.clear()
        resp, loc, verifier, redirect_uri = self.authorize(client_id, scope=scope, extra=extra)
        if loc is None:
            kind = page_kind(resp.text)
            if kind != 'login':
                return LoginResult('error' if kind == 'error' else kind, page=resp.text, response=resp)
            form = next((f for f in forms_of(resp.text) if 'password' in f['inputs']), None)
            if not form:
                return LoginResult('other', page=resp.text, response=resp)
            data = dict(form['inputs'], username=username, password=password)
            action = urllib.parse.urljoin(self.public + '/', form['action'])
            resp, loc = self._follow(self.net.request('POST', action, form=data), redirect_uri)
        otp_left = otp_tries
        for _ in range(3):
            if loc is not None:
                break
            kind = page_kind(resp.text)
            if kind == 'otp':
                if otp_left <= 0:
                    return LoginResult('otp_rejected', page=resp.text, response=resp)
                otp_left -= 1
                if not otp_secret:
                    return LoginResult('otp_required', page=resp.text, response=resp)
                form = next(f for f in forms_of(resp.text) if 'otp' in f['inputs'])
                data = dict(form['inputs'], otp=self.totp.next_code(otp_secret))
                action = urllib.parse.urljoin(self.public + '/', form['action'])
                resp, loc = self._follow(self.net.request('POST', action, form=data), redirect_uri)
            elif kind == 'totp_setup':
                return LoginResult('totp_setup', page=resp.text, response=resp)
            elif kind == 'login':
                return LoginResult('login_error', page=resp.text, response=resp)
            else:
                return LoginResult('error' if kind == 'error' else 'other', page=resp.text, response=resp)
        if loc is None:
            return LoginResult('other', page=resp.text, response=resp)
        return self._finish(client_id, loc, verifier, redirect_uri, resp)

    def _finish(self, client_id, loc, verifier, redirect_uri, resp):
        q = urllib.parse.parse_qs(urllib.parse.urlsplit(loc).query)
        if 'code' not in q:
            return LoginResult('error', page=loc, response=resp, location=loc)
        t = self.exchange(client_id, q['code'][0], verifier, redirect_uri)
        if t.status != 200:
            return LoginResult('error', page=t.text, response=t)
        return LoginResult('tokens', tokens=t.json(), response=t)

    def login_idp(self, client_id, idp, scope='openid'):
        """Вход через внешнего провайдера (kc_idp_hint): переходы Keycloak, провайдер, возврат. Пароль не вводится."""
        self.net.cookies.clear()
        resp, loc, verifier, redirect_uri = self.authorize(client_id, scope=scope, extra={'kc_idp_hint': idp})
        if loc is None:
            kind = page_kind(resp.text)
            return LoginResult('error' if kind == 'error' else kind, page=resp.text, response=resp)
        return self._finish(client_id, loc, verifier, redirect_uri, resp)

    def refresh(self, client_id, refresh_token):
        return self.net.request('POST', self.issuer + '/protocol/openid-connect/token', form={
            'grant_type': 'refresh_token', 'client_id': client_id, 'refresh_token': refresh_token})


class Admin:
    """Admin REST через realm master (администратор из секрета keycloak_admin)."""

    def __init__(self, kc, user, password):
        self.kc, self.user, self.password = kc, user, password
        self.token, self.expires = None, 0

    def _token(self):
        if self.token and time.time() < self.expires - 10:
            return self.token
        r = self.kc.net.request('POST', self.kc.public + '/realms/master/protocol/openid-connect/token', form={
            'grant_type': 'password', 'client_id': 'admin-cli', 'username': self.user, 'password': self.password})
        if r.status != 200:
            raise RuntimeError('токен администратора: %s %s' % (r.status, r.text[:200]))
        j = r.json()
        self.token, self.expires = j['access_token'], time.time() + j['expires_in']
        return self.token

    def call(self, method, path, json_body=None):
        r = self.kc.net.request(method, '%s/admin%s' % (self.kc.public, path), json_body=json_body,
                                headers={'Authorization': 'Bearer ' + self._token()})
        return r

    def realm(self, path, method='GET', json_body=None):
        return self.call(method, '/realms/%s%s' % (self.kc.realm, path), json_body)

    def clear_attacks(self, user_id):
        """Снять временную блокировку после неудачных входов (счётчик защиты от перебора)."""
        return self.realm('/attack-detection/brute-force/users/%s' % user_id, 'DELETE')


def from_env(env=None):
    """Клиент по переменным окружения: KC_PUBLIC, KC_TARGET, STUBS_TARGET, KC_CA, KC_ADMIN_USER, KC_ADMIN_PASSWORD_FILE.

    KC_PUBLIC     внешний адрес (издатель токенов), по умолчанию https://localhost:8443/auth
    KC_TARGET     где Keycloak слушает на самом деле, например https://127.0.0.1:18445 (отладочный порт). Пусто: идти по KC_PUBLIC
    STUBS_TARGET  где слушают заглушки, например https://127.0.0.1:18443. Нужен для /vkid, который в стенде проксирует шлюз
    STUBS_ADMIN_TARGET  административный порт заглушек (https://127.0.0.1:18444): письма Keycloak смотрятся по адресу external-stubs:8444
    KC_CA         файл центра сертификации, по умолчанию secrets/tls_ca.crt
    """
    import os
    env = env if env is not None else os.environ
    public = env.get('KC_PUBLIC', 'https://localhost:8443/auth')
    u = urllib.parse.urlsplit(public)
    port = u.port or (443 if u.scheme == 'https' else 80)
    routes = []
    if env.get('KC_TARGET'):
        routes.append(Route(u.hostname, port, u.path or '/', env['KC_TARGET']))
    if env.get('STUBS_TARGET'):
        routes.append(Route(u.hostname, port, '/vkid', env['STUBS_TARGET']))
    if env.get('STUBS_ADMIN_TARGET'):
        routes.append(Route('external-stubs', 8444, '/', env['STUBS_ADMIN_TARGET']))
    ca = env.get('KC_CA') or os.path.join(os.environ.get('DGM_SECRETS_DIR', 'secrets'), 'tls_ca.crt')
    net = Net(routes, cafile=ca if os.path.exists(ca) else None)
    kc = Keycloak(net, public=public, redirect_base='%s://%s' % (u.scheme, u.netloc))
    pw_file = env.get('KC_ADMIN_PASSWORD_FILE') or os.path.join(os.environ.get('DGM_SECRETS_DIR', 'secrets'), 'keycloak_admin')
    password = env.get('KC_ADMIN_PASSWORD') or (open(pw_file, encoding='utf-8').read().strip() if os.path.exists(pw_file) else '')
    admin = Admin(kc, env.get('KC_ADMIN_USER', 'dgm-admin'), password)
    return kc, admin
