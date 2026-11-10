# -*- coding: utf-8 -*-
"""Помощники и сценарии VK ID без e-mail из accounts_checks.py без стенда (шаг 16 Ф5, К16): письма и коды, разбор текста письма, порядок диалога.

Сценарии идут против двойника Keycloak, платформы и SMTP-заглушки: двойник живёт по правилам шага «адрес, затем код» (три письма на адрес за
окно, общий счётчик с регистрацией, занятый адрес после верного кода). Он не доказывает, что настоящий стенд ведёт себя так же: он ловит ошибки самой
проверки (опечатки, порядок диалогов, обращение к полям) до дорогого запуска.
"""
import os
import re
import sys
import unittest
import urllib.parse

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import accounts_checks as A  # noqa: E402
import gateway_checks as G  # noqa: E402

LETTER = {'subject': A.VK_LETTER, 'text': 'Здравствуйте!\n\nКод подтверждения адреса e-mail для входа на Маркетплейс цифровых товаров через VK ID: 048213\n\nКод действует 5 минут.'}


class Response:
    def __init__(self, body, status=200):
        self.status, self._body = status, body
        self.text = str(body)

    def json(self):
        return self._body


class World:
    """Состояние двойника: письма по адресам, записи Keycloak, привязки VK и счётчик писем (три на адрес)."""

    def __init__(self):
        self.letters, self.users, self.links, self.sub, self.serial = {}, {}, {}, None, 0

    def send(self, address, subject):
        mails = self.letters.setdefault(address, [])
        if len(mails) >= 3:
            return False
        self.serial += 1
        code = '%06d' % (100000 + self.serial * 7919 % 800000)
        text = 'Код подтверждения адреса e-mail для входа на Маркетплейс цифровых товаров через VK ID: %s' % code if subject == A.VK_LETTER else 'Подтвердите адрес'
        mails.append({'subject': subject, 'text': text})
        return True

    def add_user(self, email):
        user = {'id': 'u%d' % (len(self.users) + 1), 'email': email, 'emailVerified': True}
        self.users[email] = user
        return user


class Net:
    def __init__(self, world):
        self.world, self.cookies = world, type('Cookies', (), {'clear': lambda self: None})()

    def request(self, method, url, **kwargs):
        if method == 'PUT' and '/admin/modes/vkid' in url:
            self.world.sub = (kwargs.get('json_body') or {}).get('profile', {}).get('sub')
            return Response({})
        if method == 'GET' and '/admin/emails?to=' in url:
            return Response(list(self.world.letters.get(urllib.parse.unquote(url.split('to=')[1]), [])))
        return Response({}, 404)


class Tokens:
    ok, kind, page = True, 'tokens', ''

    def __init__(self, user):
        self.user = user

    def access(self):
        return {'sub': self.user['id'], 'realm_access': {'roles': ['buyer']}, 'amr': ['vk']}


class Dialog:
    def __init__(self, world, sub):
        self.world, self.sub, self.state, self.address, self.message, self.loc, self.user = world, sub, 'vk_email', None, '', None, None

    @property
    def kind(self):
        return {'vk_email': 'vk_email', 'vk_code': 'vk_code', 'denied': 'error', 'redirect': 'redirect'}[self.state]

    @property
    def page(self):
        if self.state == 'vk_code':
            return '<form id="kc-vk-email-code-form" action="/w?session_code=abc"><p>Если адрес %s указан верно</p><input name="code" value=""><span class="kc-feedback-text">%s</span></form>' % (self.address, self.message)
        if self.state == 'vk_email':
            return '<form id="kc-vk-email-form" action="/v?session_code=abc"><input name="email" value=""><span class="kc-feedback-text">%s</span></form>' % self.message
        return '<div id="kc-error-message">%s</div>' % self.message

    def _issue(self, address, resend):
        if self.world.send(address, A.VK_LETTER):
            self.state, self.address, self.message = 'vk_code', address, ''
        else:
            self.state, self.address, self.message = ('vk_code' if resend else 'vk_email'), address, 'Слишком много запросов кода для этого адреса. Повторите через 10 мин.'

    def post(self, button=None, **fields):
        if button == 'resend':
            self._issue(self.address, True)
        elif 'email' in fields:
            address = fields['email'].strip().lower()
            if not re.match(r'^\S+@\S+\.\S+$', address):
                self.state, self.message = 'vk_email', 'Введите адрес электронной почты, например name@mail.example.'
            else:
                self._issue(address, False)
        elif 'code' in fields:
            current = A.vk_code_of(self.world.letters[self.address][-1]) if self.address in self.world.letters else None
            if fields['code'] != current:
                self.message = 'Код неверный. Проверьте цифры и попробуйте ещё раз.'
            elif self.address in self.world.users:
                self.state, self.message = 'denied', 'Не удалось завершить вход, обратитесь в поддержку.'
            else:
                self.user = self.world.add_user(self.address)
                self.world.links[self.sub] = self.user['id']
                self.state, self.loc = 'redirect', 'https://localhost:8443/callback?code=1'
        return self

    def tokens(self):
        return Tokens(self.user)


class Kc:
    def __init__(self, world):
        self.world, self.net = world, Net(world)

    def start(self, client_id, scope='openid', extra=None, clear_cookies=True):
        return Dialog(self.world, self.world.sub)

    def login_idp(self, client_id, idp, scope='openid'):
        user = next(u for u in self.world.users.values() if self.world.links.get(self.world.sub) == u['id'])
        return Tokens(user)


class Admin:
    def __init__(self, world):
        self.world = world

    def realm(self, path, method='GET', json_body=None):
        query = urllib.parse.parse_qs(urllib.parse.urlsplit(path).query)
        if path.startswith('/users?idpAlias=vkid'):
            sub = query['idpUserId'][0]
            return Response([u for u in self.world.users.values() if self.world.links.get(sub) == u['id']])
        if path.startswith('/users?email='):
            user = self.world.users.get(query['email'][0])
            return Response([user] if user else [])
        if path.endswith('/federated-identity'):
            uid = path.split('/')[2]
            return Response([{'identityProvider': 'vkid'} for sub, linked in self.world.links.items() if linked == uid])
        return Response({}, 404)


class PureHelpersTest(unittest.TestCase):
    def test_code_comes_from_the_phrase_after_vk_id(self):
        self.assertEqual(A.vk_code_of(LETTER), '048213')

    def test_code_keeps_the_leading_zero_and_ignores_longer_numbers(self):
        letter = {'text': 'Запрос 20261110123456 и код 007120 для входа'}
        self.assertEqual(A.vk_code_of(letter), '007120')

    def test_two_different_six_digit_numbers_do_not_give_a_guess(self):
        self.assertIsNone(A.vk_code_of({'text': 'код 111111 или 222222'}))

    def test_a_number_inside_a_hex_identifier_is_not_a_code(self):
        self.assertIsNone(A.vk_code_of({'text': 'трасса 4bf92f3577b34da6a3ce929d0e0e4736 без кода'}))
        self.assertIsNone(A.vk_code_of({'text': ''}))
        self.assertIsNone(A.vk_code_of(None))

    def test_letters_are_filtered_by_subject(self):
        world = World()
        world.send('a@example.test', A.VK_LETTER)
        world.send('a@example.test', 'Подтверждение адреса')
        net = Net(world)
        self.assertEqual(len(A.letters_to(net, 'a@example.test')), 2)
        self.assertEqual(len(A.vk_letters(net, 'a@example.test')), 1)

    def test_waiting_for_letters_stops_at_the_deadline(self):
        net = Net(World())
        self.assertEqual(A.vk_letters(net, 'nobody@example.test', at_least=1, wait=0.3), [])

    def test_address_is_replaced_before_pages_are_compared(self):
        one = '<p>Если адрес a@example.test указан верно</p><input value="x">'
        two = '<p>Если адрес other@example.test указан верно</p><input value="y">'
        self.assertEqual(A.without_address(one, 'a@example.test'), A.without_address(two, 'other@example.test'))

    def test_a_scenario_that_raises_is_reported_and_does_not_stop_the_run(self):
        before = len(G.FAILED)
        self.assertIsNone(A.guarded('проба', lambda: 1 / 0))
        self.assertEqual(len(G.FAILED), before + 1)
        del G.FAILED[before:]


class ScenarioTest(unittest.TestCase):
    """Сценарии VK ID без e-mail против двойника: ни одной красной проверки и ни одного исключения."""

    def setUp(self):
        self.world, self.failed = World(), len(G.FAILED)
        self.kc, self.admin = Kc(self.world), Admin(self.world)
        self.real = (A.new_buyer, A.register)
        A.new_buyer = lambda kc, admin, stamp, tag: self.owner(tag, stamp)
        A.register = lambda kc, email, password: self.world.send(email, 'Подтверждение адреса')

    def tearDown(self):
        A.new_buyer, A.register = self.real
        del G.FAILED[self.failed:]

    def owner(self, tag, stamp):
        email = 'acc-%s-%d@example.test' % (tag, stamp)
        user = self.world.add_user(email)
        self.world.send(email, 'Подтверждение адреса')
        return {'email': email, 'password': 'x', 'id': user['id']}

    def new_failures(self):
        return G.FAILED[self.failed:]

    def test_free_address_flow_creates_the_account_and_links_vk(self):
        A.vk_without_email(self.kc, self.admin, 1700000001)
        self.assertEqual(self.new_failures(), [])
        self.assertIn('vk_noemail_1700000001', self.world.links)

    def test_busy_address_is_denied_without_a_link(self):
        A.vk_busy_address(self.kc, self.admin, 1700000002)
        self.assertEqual(self.new_failures(), [])
        self.assertNotIn('vk_busy_1700000002', self.world.links)

    def test_fourth_letter_is_withheld_and_registration_adds_none(self):
        A.vk_letter_limit(self.kc, self.admin, 1700000003)
        self.assertEqual(self.new_failures(), [])
        self.assertEqual(len(self.world.letters['vk-quota-1700000003@example.test']), 3)

    def test_timing_scenario_runs_through_all_six_dialogs(self):
        A.vk_timing(self.kc, self.admin, 1700000004)
        self.assertEqual(self.new_failures(), [])

    def test_a_broken_flow_is_red_not_silent(self):
        """Двойник, который пускает занятый адрес в привязку, обязан дать красную проверку."""
        original = Dialog.post

        def lax(dialog, button=None, **fields):
            if 'code' in fields and dialog.address in dialog.world.users:
                dialog.world.links[dialog.sub] = dialog.world.users[dialog.address]['id']
            return original(dialog, button, **fields)

        Dialog.post = lax
        try:
            A.vk_busy_address(self.kc, self.admin, 1700000005)
        finally:
            Dialog.post = original
        self.assertTrue(self.new_failures())


if __name__ == '__main__':
    unittest.main()
