#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Дымовой сценарий интерфейса на стенде (контрольная точка К4, шаг 19 Ф4; US-2.2, US-3.3 – US-3.5, ST-13).

    make ui-smoke

Нужен тот же стенд, что для make seller-approval-check: набор full с отладочным файлом (make up SET=full DEBUG=1, затем make keycloak-users) и собранный
образ web-app (в CI его собирает задание images, при локальном запуске его собирает make up). Браузер настоящий (Chromium под управлением Playwright), страница
та, которую отдаёт шлюз на https://localhost:8443; через API готовятся только данные, а решения принимаются щелчками по экранам:

  модератор   вход через Keycloak с паролем и кодом TOTP (клиент dgm-staff), очередь заявок, карточка с документом, «Одобрить» с подтверждением; очередь
              товаров, «Опубликовать» одного товара и «Отклонить» с причиной другого. Каждое решение проверяется по API: статус заявки или товара,
              причина у продавца;
  гость       без входа находит опубликованный товар по названию на витрине, открывает карточку, видит цену и «Нет в наличии», отклонённого товара не видит.

Данные готовятся так же, как в seller_approval_checks.py: два одноразовых покупателя подают заявки (одну одобряет модератор в браузере, другую через API,
её продавец заводит два товара). Тексты кнопок и сообщений берутся из словаря интерфейса (ru.json), поэтому правка словаря не ломает сценарий. Если шаг упал, в журнал
попадают адрес страницы, её текст, сообщения консоли браузера и ответы с кодом 400 и выше, а снимок экрана ложится в каталог UI_REPORT_DIR (по умолчанию build/ui-smoke).

Переменные: UI_BASE (адрес шлюза, по умолчанию https://localhost:8443), UI_REPORT_DIR, UI_TIMEOUT_MS (ожидание элемента, по умолчанию 20000), UI_NO_SANDBOX=1
(запуск Chromium без песочницы, если среда её не разрешает), остальные те же, что у make seller-approval-check. Пакет playwright ставится из tools/stand-checks/requirements-ui.txt,
браузер командой python3 -m playwright install --with-deps chromium. Остальные проверки стенда обходятся стандартной библиотекой Python, этой нужен Playwright.
"""
import json
import os
import re
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import accounts_checks as A  # noqa: E402
import gateway_checks as G  # noqa: E402
import kc_client as K  # noqa: E402
import seller_approval_checks as SAC  # noqa: E402
import sellers_checks as SC  # noqa: E402

try:
    from playwright.sync_api import expect as pw_expect, sync_playwright
except ImportError:  # пакет не установлен: main() скажет об этом и вернёт 2
    pw_expect = sync_playwright = None

BASE = os.environ.get('UI_BASE', 'https://localhost:8443').rstrip('/')
REPORT = os.environ.get('UI_REPORT_DIR', os.path.join('build', 'ui-smoke'))
TIMEOUT = int(os.environ.get('UI_TIMEOUT_MS', '20000'))
DICTIONARY = os.path.join(HERE, '..', '..', 'frontend', 'web-app', 'src', 'i18n', 'ru.json')
with open(DICTIONARY, encoding='utf-8') as _file:
    RU = json.load(_file)


def T(key, **values):
    """Текст интерфейса по ключу словаря."""
    return RU[key].format(**values) if values else RU[key]


# --------------------------------------------------------------------------------------------------- браузер
class Watch:
    """Что видел браузер: консоль, ошибки страницы, ответы с кодом 400 и выше и обрывы запросов. Печатается только при сбое шага."""

    def __init__(self, page):
        self.lines = []
        page.on('console', lambda m: self.lines.append('консоль %s: %s' % (m.type, m.text[:200])) if m.type in ('error', 'warning') else None)
        page.on('pageerror', lambda e: self.lines.append('ошибка страницы: %s' % str(e)[:200]))
        page.on('response', lambda r: self.lines.append('ответ %d %s %s' % (r.status, r.request.method, r.url[:140])) if r.status >= 400 else None)
        page.on('requestfailed', lambda r: self.lines.append('обрыв %s %s: %s' % (r.method, r.url[:140], r.failure)))


def visible(locator, timeout=None):
    pw_expect(locator).to_be_visible(timeout=timeout or TIMEOUT)


def snapshot(page, watch, name):
    """Диагностика упавшего шага: адрес, заголовки, текст страницы, сообщения браузера, снимок экрана."""
    try:
        print('        | страница: %s' % page.url)
        print('        | текст: %s' % re.sub(r'\s+', ' ', page.inner_text('body', timeout=3000))[:600])
    except Exception as failure:  # страница могла закрыться
        print('        | страницу прочитать не удалось: %s' % str(failure)[:160])
    for line in watch.lines[-12:]:
        print('        | %s' % line)
    try:
        os.makedirs(REPORT, exist_ok=True)
        page.screenshot(path=os.path.join(REPORT, re.sub(r'[^0-9A-Za-zА-Яа-я]+', '-', name).strip('-')[:60] + '.png'), full_page=True)
    except Exception:
        pass


def step(page, watch, name, action):
    """Один шаг сценария: исключение Playwright или проверки считается ошибкой шага, а не падением всего сценария."""
    try:
        action()
    except Exception as failure:
        G.bad(name, '%s: %s' % (failure.__class__.__name__, str(failure).strip().splitlines()[0][:300] if str(failure).strip() else ''))
        snapshot(page, watch, name)
        return False
    G.ok(name)
    return True


def open_from_queue(page, label):
    """Открывает запись очереди по названию ссылки; если очередь длиннее страницы, подгружает следующие по «Показать ещё»."""
    visible(page.locator('table').first)
    for _ in range(20):
        link = page.get_by_role('link', name=label, exact=True)
        if link.count() > 0:
            link.first.click()
            return
        more = page.get_by_role('button', name=T('catalog.more'), exact=True)
        if more.count() == 0:
            break
        more.click()
        page.wait_for_timeout(800)
    raise AssertionError('в очереди нет записи «%s»' % label)


def decide(page, option_label, confirm_done, reason=None):
    """Решение в панели: нажать действие, при необходимости вписать причину, подтвердить, дождаться сообщения."""
    page.get_by_role('button', name=option_label, exact=True).click()
    group = page.get_by_role('group', name=option_label, exact=True)
    visible(group)
    if reason is not None:
        group.get_by_role('textbox').fill(reason)
    group.get_by_role('button', name=T('moderation.decision.confirm'), exact=True).click()
    visible(page.get_by_text(confirm_done, exact=True))


def sign_in_staff(page, kc, admin, user):
    """Вход сотрудника через Keycloak: пароль, затем код TOTP. Раздел /staff сам отправляет неавторизованного на Keycloak."""
    page.goto(BASE + '/staff', wait_until='domcontentloaded')
    page.wait_for_url(re.compile(r'/auth/realms/dgm/'), timeout=TIMEOUT * 2)
    admin.clear_attacks(user['id'])
    page.locator('input[name=username]').fill(user['email'])
    page.locator('input[name=password]').fill(user['password'])
    page.locator('#kc-login').click()
    otp = page.locator('input[name=otp]')
    for attempt in range(3):
        visible(otp, TIMEOUT * 2)
        otp.fill(kc.totp.next_code(user['otp_secret']))
        page.locator('#kc-login').click()
        try:
            page.wait_for_url(re.compile(r'^' + re.escape(BASE) + r'/(oidc/callback/staff|staff)'), timeout=TIMEOUT)
            return
        except Exception:
            if attempt == 2:
                raise
    raise AssertionError('Keycloak не принял код TOTP')


# --------------------------------------------------------------------------------------------------- данные
def money_pattern():
    return re.compile(r'1\s499')


def prepare(kc, admin, moderator, stamp):
    """Заявка для одобрения в браузере; одобренный через API продавец с двумя поданными товарами. None, если подготовка сорвалась."""
    print('== Подготовка данных через API: заявка на проверке, продавец, два товара на модерации')
    form = dict(SC.FORM, name='Студия Смоук %d' % stamp)
    pending = SAC.applicant(kc, admin, stamp, 'ui-pending', form)
    candidate = SAC.applicant(kc, admin, stamp, 'ui-seller')
    if not pending or not candidate:
        return None
    approved = SAC.decide(moderator, candidate['sellerId'], 'approve')
    G.expect('заявка будущего продавца одобрена через API: 200', approved.status == 200, '%s %s' % (approved.status, approved.text[:200]))
    if not G.expect('роль seller и обязательный TOTP выставлены платформой', A.eventually(lambda: SAC.keycloak_is_seller(admin, candidate['id'], candidate['sellerId']), wait=45)):
        return None
    seller_token = SAC.first_login_and_cabinet(kc, candidate)
    if not seller_token:
        return None
    products = []
    for tag in ('опубликуем', 'отклоним'):
        title = 'Смоук %s %d: ключ Steam' % (tag, stamp)
        created = SC.post_json(SAC.CABINET, seller_token, SAC.product_form(title))
        submitted = SC.post_json('%s/%s/submit' % (SAC.CABINET, created.json().get('productId')), seller_token) if created.status == 201 else created
        if not G.expect('товар «%s» заведён и подан на модерацию' % title, created.status == 201 and submitted.status == 200 and submitted.json().get('status') == 'on_moderation',
                        '%s %s' % (created.status, created.text[:200])):
            return None
        products.append({'id': created.json()['productId'], 'title': title})
    return {'application': pending, 'form': form, 'seller_token': seller_token, 'publish': products[0], 'reject': products[1]}


# --------------------------------------------------------------------------------------------------- сценарии
def moderator_scenario(browser, kc, admin, user, moderator, data):
    print('== Модератор в браузере: вход с TOTP, решения по заявке и товарам (К4, US-2.2, US-3.3, ST-13)')
    context = browser.new_context(ignore_https_errors=True, locale='ru-RU', timezone_id='Europe/Moscow')
    context.set_default_timeout(TIMEOUT)
    page = context.new_page()
    watch = Watch(page)
    try:
        if not step(page, watch, 'вход модератора через Keycloak с паролем и кодом TOTP открывает рабочее место',
                    lambda: (sign_in_staff(page, kc, admin, user), visible(page.get_by_role('link', name=T('moderation.home.applications'), exact=True)))):
            return
        name, sid = data['form']['name'], data['application']['sellerId']

        def application():
            page.get_by_role('link', name=T('moderation.home.applications'), exact=True).click()
            visible(page.get_by_role('heading', name=T('moderation.applications.title'), level=1, exact=True))
            open_from_queue(page, name)
            visible(page.get_by_role('heading', name=name, level=1, exact=True))
            visible(page.locator('a[href*="/files/seller-documents/"]').first)
            decide(page, T('moderation.application.approve'), T('moderation.application.approve.done'))
        step(page, watch, 'заявка: очередь, карточка с ссылкой на документ, «Одобрить» и подтверждение, сообщение «%s»' % T('moderation.application.approve.done'), application)
        card = G.call('GET', '%s/%s' % (SAC.QUEUE, sid), headers=SC.auth(moderator.token()))
        G.expect('по API заявка одобрена решением из браузера', card.status == 200 and card.json().get('status') == 'approved', '%s %s' % (card.status, card.text[:200]))

        def publish():
            page.get_by_role('link', name=T('moderation.applications.back'), exact=True).click()
            page.get_by_role('link', name=T('moderation.back'), exact=True).click()
            page.get_by_role('link', name=T('moderation.home.products'), exact=True).click()
            visible(page.get_by_role('heading', name=T('moderation.products.title'), level=1, exact=True))
            open_from_queue(page, data['publish']['title'])
            visible(page.get_by_role('heading', name=data['publish']['title'], level=1, exact=True))
            decide(page, T('moderation.product.approve'), T('moderation.product.approve.done'))
            visible(page.get_by_role('button', name=T('moderation.product.block'), exact=True))
        step(page, watch, 'товар: очередь, «Опубликовать», сообщение «%s», у опубликованного появилась «Заблокировать»' % T('moderation.product.approve.done'), publish)
        card = G.call('GET', '%s/%s' % (SAC.STAFF_PRODUCTS, data['publish']['id']), headers=SC.auth(moderator.token()))
        G.expect('по API товар опубликован', card.status == 200 and card.json().get('status') == 'published', '%s %s' % (card.status, card.text[:200]))

        reason = 'В описании не указан регион активации'

        def reject():
            page.get_by_role('link', name=T('moderation.products.back'), exact=True).click()
            open_from_queue(page, data['reject']['title'])
            visible(page.get_by_role('heading', name=data['reject']['title'], level=1, exact=True))
            decide(page, T('moderation.product.reject'), T('moderation.product.reject.done'), reason=reason)
        step(page, watch, 'товар: «Отклонить» с причиной, сообщение «%s»' % T('moderation.product.reject.done'), reject)
        own = G.call('GET', '%s/%s' % (SAC.CABINET, data['reject']['id']), headers=SC.auth(data['seller_token']))
        G.expect('по API товар отклонён, причина из браузера видна продавцу', own.status == 200 and own.json().get('status') == 'rejected' and own.json().get('rejectionReason') == reason,
                 '%s %s' % (own.status, own.text[:200]))
    finally:
        context.close()


def guest_scenario(browser, data):
    print('== Гость без входа: витрина находит опубликованный товар (К4, US-3.3 – US-3.5)')
    context = browser.new_context(ignore_https_errors=True, locale='ru-RU', timezone_id='Europe/Moscow')
    context.set_default_timeout(TIMEOUT)
    page = context.new_page()
    watch = Watch(page)
    title = data['publish']['title']
    try:
        def search():
            page.goto(BASE + '/products', wait_until='domcontentloaded')
            visible(page.get_by_role('heading', name=T('catalog.title'), level=1, exact=True))
            link = page.get_by_role('link', name=title, exact=True)
            for attempt in range(5):
                page.get_by_label(T('catalog.filter.q'), exact=True).fill(title)
                page.get_by_role('button', name=T('catalog.filter.apply'), exact=True).click()
                try:
                    visible(link, 6000)
                    return
                except Exception:
                    if attempt == 4:
                        raise
        step(page, watch, 'каталог: поиск по названию находит опубликованный товар', search)

        def card():
            page.get_by_role('link', name=title, exact=True).click()
            visible(page.get_by_role('heading', name=title, level=1, exact=True))
            visible(page.get_by_text(money_pattern()).first)
            visible(page.get_by_text(T('catalog.outOfStock'), exact=True))
        step(page, watch, 'карточка товара: название, цена 1 499 ₽ и «%s»' % T('catalog.outOfStock'), card)

        hidden = G.call('GET', '%s/%s' % (SAC.STOREFRONT, data['reject']['id']))
        G.expect('отклонённого товара на витрине нет: 404', hidden.status == 404, '%s %s' % (hidden.status, hidden.text[:160]))

        def gone():
            page.goto('%s/products/%s' % (BASE, data['reject']['id']), wait_until='domcontentloaded')
            visible(page.get_by_role('heading', name=T('product.notFound.title'), level=1, exact=True))
        step(page, watch, 'страница отклонённого товара говорит «%s»' % T('product.notFound.title'), gone)
    finally:
        context.close()


# --------------------------------------------------------------------------------------------------- запуск
def main():
    if sync_playwright is None:
        print('Нет пакета playwright: python3 -m pip install -r tools/stand-checks/requirements-ui.txt && python3 -m playwright install --with-deps chromium', file=sys.stderr)
        return 2
    kc, admin = K.from_env(dict(os.environ))
    people = G.users()
    if 'moderator-1' not in people:
        print('Нет тестового пользователя moderator-1 (make keycloak-users)', file=sys.stderr)
        return 2
    stamp = int(time.time())
    moderator = SAC.Staff(kc, admin, people['moderator-1'])
    moderator.token()
    data = prepare(kc, admin, moderator, stamp)
    if data:
        with sync_playwright() as playwright:
            browser = playwright.chromium.launch(args=['--no-sandbox'] if os.environ.get('UI_NO_SANDBOX') else [])
            try:
                moderator_scenario(browser, kc, admin, people['moderator-1'], moderator, data)
                guest_scenario(browser, data)
            finally:
                browser.close()
    print('\nПроверок успешно: %d, с ошибками: %d' % (G.PASSED, len(G.FAILED)))
    for name in G.FAILED:
        print('  - %s' % name)
    if os.environ.get('GITHUB_ACTIONS'):
        print('::notice title=ui_smoke::Проверок успешно: %d, с ошибками: %d%s' % (G.PASSED, len(G.FAILED), ('; не прошли: ' + '|'.join(G.FAILED)) if G.FAILED else ''))
    return 1 if G.FAILED or not data else 0


if __name__ == '__main__':
    sys.exit(main())
