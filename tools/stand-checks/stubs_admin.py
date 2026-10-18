#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Клиент команд управления заглушками внешних систем для проверок на стенде (шаг 4 среза 2 Ф4, Р-146).

Заглушки (`tools/external-stubs`, порт 8444, команды `/admin/...`) задают платёжному шлюзу, почте и SMS режим ответа, сбрасываются между тестами и
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
import sys
import time
import urllib.parse

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import kc_client as K  # noqa: E402

ADMIN_URL = 'https://external-stubs:8444'
SYSTEMS = ('payment', 'email', 'sms', 'vkid')


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
        """Дожидается отложенных исходов прошлого теста, очищает платежи, письма, SMS, журнал и возвращает режимы по умолчанию."""
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

    # --------------------------------------------------------------------------------------------- журнал
    def journal(self, since=0, system=None, kind=None):
        query = {'since': since}
        if system:
            query['system'] = system
        if kind:
            query['kind'] = kind
        return self._call('GET', '/admin/journal?' + urllib.parse.urlencode(query))
