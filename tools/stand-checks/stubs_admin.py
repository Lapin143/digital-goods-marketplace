#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Клиент команд управления заглушками внешних систем для проверок на стенде (шаг 4 среза 2 Ф4, Р-146).

Заглушки (`tools/external-stubs`, порт 8444, команды `/admin/...`) задают платёжному шлюзу, почте, SMS и «системе продавца» режим ответа, сбрасываются между тестами и
показывают, что платформа им прислала. Сценарии среза 2 (оплата, поздняя оплата, отказ шлюза, потерянный вебхук, письмо с ключами, недоставка) меняют
режим и обязаны вернуть его: чужой режим, оставшийся после упавшего сценария, ломает следующий. Поэтому режим меняется через контекст:

    admin = StubsAdmin.from_env()
    admin.reset()                                   # в начале сценария
    with admin.payment_mode(scenario='hold'):       # на выходе режим платежа возвращается к умолчаниям, даже если внутри было исключение
        ...
    admin.confirm_payment(payment_id)               # поздняя оплата по команде
    admin.wait_email(to='buyer@example.test', subject='Ваш заказ')

Модуль не знает платформы: он говорит только с административным портом заглушек. Настройка: STUBS_ADMIN_TARGET (где слушает порт, по умолчанию
https://127.0.0.1:18444, отладочный порт набора), KC_CA (файл центра сертификации, по умолчанию secrets/tls_ca.crt). Только стандартная библиотека Python.
"""
import contextlib
import json
import os
import re
import sys
import time
import urllib.parse

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import kc_client as K  # noqa: E402

ADMIN_URL = 'https://external-stubs:8444'
SYSTEMS = ('payment', 'email', 'sms', 'vkid', 'seller')


class StubsError(RuntimeError):
    """Заглушка ответила не так, как ждёт сценарий."""


class StubsAdmin:
    """Команды управления заглушками. `net` это объект с методом `request(method, url, headers=, body=)`, ответ с полями `status`, `text`, методом `json()`."""

    def __init__(self, net, base=ADMIN_URL, poll_seconds=0.4):
        self.net = net
        self.base = base.rstrip('/')
        self.poll_seconds = poll_seconds

    @classmethod
    def from_env(cls, env=None):
        env = env if env is not None else os.environ
        target = env.get('STUBS_ADMIN_TARGET') or 'https://127.0.0.1:18444'
        secrets_dir = os.environ.get('DGM_SECRETS_DIR', 'secrets')
        ca = env.get('KC_CA') or os.path.join(secrets_dir, 'tls_ca.crt')
        net = K.Net([K.Route('external-stubs', 8444, '/', target)], cafile=ca if os.path.exists(ca) else None)
        return cls(net)

    # --------------------------------------------------------------------------------------------- общее
    def _call(self, method, path, body=None, expect=(200,)):
        data = json.dumps(body).encode('utf-8') if body is not None else None
        headers = {'Accept': 'application/json'}
        if data is not None:
            headers['Content-Type'] = 'application/json'
        r = self.net.request(method, self.base + path, headers=headers, body=data)
        if r.status not in expect:
            raise StubsError('%s %s: код %s, ожидалось %s: %s' % (method, path, r.status, '/'.join(str(x) for x in expect), r.text[:300]))
        try:
            return r.json() if r.text.strip() else {}
        except ValueError:
            raise StubsError('%s %s: ответ не JSON: %s' % (method, path, r.text[:200]))

    @staticmethod
    def _items(body):
        return body if isinstance(body, list) else (body.get('items') or [])

    # --------------------------------------------------------------------------------------------- режимы и сброс
    def reset(self):
        """Дожидается отложенных исходов прошлого теста, очищает платежи, письма, SMS, подключения продавцов, журнал и возвращает режимы по умолчанию."""
        return self._call('POST', '/admin/reset')

    def idle(self):
        """Ждёт, пока заглушка отправит отложенные вебхуки."""
        return self._call('POST', '/admin/idle')

    def modes(self):
        return self._call('GET', '/admin/modes')

    def set_mode(self, system, **fields):
        """Заменяет режим системы целиком: поля, которых нет в вызове, возвращаются к умолчанию заглушки."""
        if system not in SYSTEMS:
            raise ValueError('нет системы «%s», есть %s' % (system, ', '.join(SYSTEMS)))
        return self._call('PUT', '/admin/modes/%s' % system, fields)

    def restore_mode(self, system):
        """Режим системы по умолчанию (пустой режим заменяет прежний целиком)."""
        return self.set_mode(system)

    @contextlib.contextmanager
    def mode(self, system, **fields):
        """Задаёт режим на время блока и возвращает умолчания на выходе, в том числе при исключении."""
        self.set_mode(system, **fields)
        try:
            yield self
        finally:
            self.restore_mode(system)

    def payment_mode(self, **fields):
        """Режим платёжного шлюза: scenario (success, reject, hold, expire), delayMs, create={...}, webhook={...}, refund={...}."""
        return self.mode('payment', **fields)

    def email_mode(self, **fields):
        """Режим почты: behavior (accept, temp_error, perm_error), failFirst, status (delivered, failed, none), webhook={...}."""
        return self.mode('email', **fields)

    # --------------------------------------------------------------------------------------------- платежи
    def payments(self):
        return self._items(self._call('GET', '/admin/payments'))

    def payment(self, payment_id):
        return self._call('GET', '/admin/payments/%s' % urllib.parse.quote(payment_id, safe=''))

    def find_payment(self, predicate=None, wait=15.0):
        """Первый платёж, для которого predicate истинен (по умолчанию любой); ждёт до `wait` секунд: платёж появляется после вызова платформы."""
        deadline = time.time() + wait
        while True:
            for p in self.payments():
                if predicate is None or predicate(p):
                    return p
            if time.time() >= deadline:
                raise StubsError('платёж не появился в заглушке за %.0f с' % wait)
            time.sleep(self.poll_seconds)

    def _payment_command(self, payment_id, action, overrides):
        return self._call('POST', '/admin/payments/%s/%s' % (urllib.parse.quote(payment_id, safe=''), action), overrides or {})

    def confirm_payment(self, payment_id, **overrides):
        """Исход «оплачен» по команде: поздняя оплата, оплата после отказа. overrides: repeat, parallel, badSignature, timestampOffsetSeconds, amountDelta, send."""
        return self._payment_command(payment_id, 'confirm', overrides)

    def decline_payment(self, payment_id, **overrides):
        return self._payment_command(payment_id, 'decline', overrides)

    def expire_payment(self, payment_id, **overrides):
        return self._payment_command(payment_id, 'expire', overrides)

    def complete_refund(self, payment_id, **overrides):
        """Ручной возврат в кабинете шлюза: возврат помечается выполненным, уходит вебхук refund.completed."""
        return self._payment_command(payment_id, 'refund-complete', overrides)

    def resend_webhook(self, payment_id, event_type, **overrides):
        """Уведомление заданного типа без смены статуса платежа: повтор, перестановка, неизвестный тип. event_type, например, payment.paid."""
        return self._payment_command(payment_id, 'webhook', dict(overrides, type=event_type))

    # --------------------------------------------------------------------------------------------- письма и SMS
    def emails(self, to=None, sender=None, subject=None):
        query = {k: v for k, v in (('to', to), ('sender', sender), ('subject', subject)) if v}
        suffix = '?' + urllib.parse.urlencode(query) if query else ''
        return self._items(self._call('GET', '/admin/emails' + suffix))

    def wait_email(self, count=1, wait=20.0, **query):
        """Ждёт, пока писем по условию станет не меньше `count`; возвращает их. Нет за `wait` секунд: StubsError с тем, что нашлось."""
        deadline = time.time() + wait
        while True:
            found = self.emails(**query)
            if len(found) >= count:
                return found
            if time.time() >= deadline:
                raise StubsError('писем %s: найдено %d из %d за %.0f с' % (query, len(found), count, wait))
            time.sleep(self.poll_seconds)

    def email_status(self, message_id, status, **overrides):
        """Статус письма вебхуком: accepted, delivered, failed (для failed можно передать reason)."""
        return self._call('POST', '/admin/emails/%s/status' % urllib.parse.quote(message_id, safe=''), dict(overrides, status=status))

    def sms(self, to=None, everything=False):
        query = {k: v for k, v in (('to', to), ('all', '1' if everything else None)) if v}
        suffix = '?' + urllib.parse.urlencode(query) if query else ''
        return self._items(self._call('GET', '/admin/sms' + suffix))

    @staticmethod
    def sms_code(item):
        """Одноразовый код из SMS: поле `code`, которое заглушка выделяет сама, а если его нет, шесть цифр подряд из текста; нет кода: None."""
        if item.get('code'):
            return str(item['code'])
        found = re.search(r'(?<!\d)(\d{6})(?!\d)', item.get('text') or '')
        return found.group(1) if found else None

    def wait_sms(self, to, count=1, wait=20.0, everything=False):
        """Ждёт, пока SMS на номер `to` станет не меньше `count` (как wait_email); возвращает их. `everything` берёт и недоставленные.
        Так стенд проходит путь покупателя (смена e-mail по коду из SMS, срез 3), не заглядывая в Redis платформы."""
        deadline = time.time() + wait
        while True:
            found = self.sms(to=to, everything=everything)
            if len(found) >= count:
                return found
            if time.time() >= deadline:
                raise StubsError('SMS на %s: найдено %d из %d за %.0f с' % (to, len(found), count, wait))
            time.sleep(self.poll_seconds)

    # --------------------------------------------------------------------------------------------- «система продавца» (Ф5, ADR-028)
    def seller_mode(self, request=None, webhook=None):
        """Режим «системы продавца» на время блока (на выходе умолчания). request: behavior (answer, silent, error, duplicate, http500, refuse,
        reject), failFirst, thenBehavior, answerDelayMs, repeatDelayMs, keysDelta, errorCode; webhook: failFirst, failure, failStatus, delayMs."""
        fields = {}
        if request is not None:
            fields['request'] = request
        if webhook is not None:
            fields['webhook'] = webhook
        return self.mode('seller', **fields)

    def connect_seller(self, slug, secret, api_key=None, previous_secret=None):
        """Сообщает заглушке, кто такой продавец `slug`: секрет подписи (тот, что платформа показала при подключении системы) и API-ключ (тот, что
        выпущен в кабинете; по нему заглушка отвечает на запросы выдачи). Секрет и ключ остаются в памяти заглушки и обратно не возвращаются.
        Сброс (`reset`) забывает подключение. `previous_secret` оставляет действующим и прежний секрет (проверка смены секрета)."""
        body = {'secret': secret}
        if api_key is not None:
            body['apiKey'] = api_key
        if previous_secret is not None:
            body['previousSecret'] = previous_secret
        return self._call('PUT', '/admin/sellers/%s' % urllib.parse.quote(slug, safe=''), body)

    def disconnect_seller(self, slug):
        return self._call('DELETE', '/admin/sellers/%s' % urllib.parse.quote(slug, safe=''))

    def sellers(self):
        """Подключённые продавцы: slug, есть ли ключ, сколько секретов, сколько запросов и webhook принято. Секретов и ключей в ответе нет."""
        return self._items(self._call('GET', '/admin/sellers'))

    def seller_requests(self, slug, request_id=None, order_number=None):
        """Запросы выдачи, принятые «системой продавца» (с подписью и меткой времени, прошедшими проверку): число попыток платформы, исходы,
        лишние поля тела (`extraFields`, ждём пусто: персональных данных в запросе нет) и ответы заглушки платформе (`answers`: вид, код HTTP,
        тип проблемы, значения ключей, которые заглушка прислала, чтобы сверить их с письмом покупателя)."""
        query = {k: v for k, v in (('requestId', request_id), ('orderNumber', order_number)) if v}
        suffix = '?' + urllib.parse.urlencode(query) if query else ''
        return self._items(self._call('GET', '/admin/sellers/%s/requests%s' % (urllib.parse.quote(slug, safe=''), suffix)))

    def wait_seller_request(self, slug, count=1, wait=20.0, **query):
        """Ждёт, пока «система продавца» примет не меньше `count` запросов по условию (request_id, order_number)."""
        deadline = time.time() + wait
        while True:
            found = self.seller_requests(slug, **query)
            if len(found) >= count:
                return found
            if time.time() >= deadline:
                raise StubsError('запросов продавцу %s %s: найдено %d из %d за %.0f с' % (slug, query, len(found), count, wait))
            time.sleep(self.poll_seconds)

    def wait_seller_answers(self, slug, request_id, count=1, wait=30.0):
        """Ждёт, пока заглушка отправит платформе не меньше `count` ответов на запрос `request_id`; возвращает запись запроса."""
        deadline = time.time() + wait
        while True:
            found = self.seller_requests(slug, request_id=request_id)
            if found and len(found[0].get('answers') or []) >= count:
                return found[0]
            if time.time() >= deadline:
                have = len(found[0].get('answers') or []) if found else 0
                raise StubsError('ответов продавца %s на %s: %d из %d за %.0f с' % (slug, request_id, have, count, wait))
            time.sleep(self.poll_seconds)

    def answer_seller_request(self, slug, request_id, **body):
        """Ответ продавца по команде: поздний ответ после окна, чужие ключи, отказ, лишнее поле. body: keys, error, count, extra, repeat.
        Без полей заглушка присылает те же ключи, что в режиме `answer`. Возвращает ответы платформы."""
        path = '/admin/sellers/%s/requests/%s/answer' % (urllib.parse.quote(slug, safe=''), urllib.parse.quote(request_id, safe=''))
        return self._call('POST', path, body)

    def seller_webhooks(self, slug, type=None, event_id=None):
        """Webhook, принятые «системой продавца»: `items` (по одному на событие, с числом попыток и дублей) и `attempts` (каждая попытка с исходом)."""
        query = {k: v for k, v in (('type', type), ('eventId', event_id)) if v}
        suffix = '?' + urllib.parse.urlencode(query) if query else ''
        return self._call('GET', '/admin/sellers/%s/webhooks%s' % (urllib.parse.quote(slug, safe=''), suffix))

    def wait_seller_webhooks(self, slug, count=1, wait=30.0, **query):
        """Ждёт, пока принято не меньше `count` событий по условию (type, event_id); возвращает ответ `seller_webhooks`."""
        deadline = time.time() + wait
        while True:
            found = self.seller_webhooks(slug, **query)
            if len(found.get('items') or []) >= count:
                return found
            if time.time() >= deadline:
                raise StubsError('webhook продавцу %s %s: принято %d из %d за %.0f с' % (slug, query, len(found.get('items') or []), count, wait))
            time.sleep(self.poll_seconds)

    def seller_rejected(self, slug):
        """Вызовы платформы, которые «система продавца» отвергла (подпись, метка времени, тело) с причиной: так видно, что платформа подписывает неверно."""
        return self._items(self._call('GET', '/admin/sellers/%s/rejected' % urllib.parse.quote(slug, safe='')))

    # --------------------------------------------------------------------------------------------- журнал
    def journal(self, since=0, system=None, kind=None):
        query = {'since': since}
        if system:
            query['system'] = system
        if kind:
            query['kind'] = kind
        return self._call('GET', '/admin/journal?' + urllib.parse.urlencode(query))
