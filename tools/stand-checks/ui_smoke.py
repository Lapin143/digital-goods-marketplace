#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Дымовой сценарий интерфейса на стенде (контрольные точки К4 и К8; US-2.2, US-3.3 – US-3.5, US-5.1 – US-5.4, US-5.9, ST-13).

    make ui-smoke

Нужен тот же стенд, что для make seller-approval-check: набор full с отладочным файлом (make up SET=full DEBUG=1, затем make keycloak-users) и собранный
образ web-app (в CI его собирает задание images, при локальном запуске его собирает make up). Браузер настоящий (Chromium под управлением Playwright), страница
та, которую отдаёт шлюз на https://localhost:8443; через API готовятся только данные, а решения принимаются щелчками по экранам:

  модератор   вход через Keycloak с паролем и кодом TOTP (клиент dgm-staff), очередь заявок, карточка с документом, «Одобрить» с подтверждением; очередь
              товаров, «Опубликовать» одного товара и «Отклонить» с причиной другого. Каждое решение проверяется по API: статус заявки или товара,
              причина у продавца;
  гость       без входа находит опубликованный товар по названию на витрине, открывает карточку, видит цену и «Нет в наличии», отклонённого товара не видит;
  покупатель  (срез 2, К8) входит через Keycloak, выбирает количество, нажимает «Купить», на странице платёжной заглушки нажимает «Оплатить», возвращается на экран
              заказа, дожидается «Выдан», проверяет, что на экране нет значений ключей, а они пришли письмом, и находит заказ в истории. Две ветки: отказ в оплате
              (причина на экране, «Оформить заново», ключи вернулись в продажу) и истёкший резерв (возврат к оплате из заказа не создаёт второго платежа, потом резерв
              заканчивается, заказ отменён с причиной). Заглушки в режиме hold (исход выбирает щелчок) и возвращаются в исходный режим.

Данные модератора и гостя готовятся так же, как в seller_approval_checks.py: два одноразовых покупателя подают заявки (одну одобряет модератор в браузере, другую через API,
её продавец заводит два товара). Для покупки берётся продавец и товар с запасом ключей из orders_checks.py (secrets/purchase_fixture.json), покупатели одноразовые с подтверждённым телефоном. Тексты кнопок и сообщений берутся из словаря интерфейса (ru.json), поэтому правка словаря не ломает сценарий. Если шаг упал, в журнал
попадают адрес страницы, её текст, сообщения консоли браузера и ответы с кодом 400 и выше, а снимок экрана ложится в каталог UI_REPORT_DIR (по умолчанию build/ui-smoke).

Поддержка и администрирование (срез 3, К11; make ui-smoke-support, переменная UI_SCOPE=support): покупатель с выданным заказом нажимает «Не получил ключ», оператор поддержки
(вход с TOTP) находит обращение по номеру заказа, берёт его в работу и повторно отправляет ключ, обращение решает доставка, покупатель видит итог без перезагрузки;
вторая цепочка: оператор начинает смену e-mail, покупатель вводит код из SMS и код из письма (оба берутся из заглушек), карточка оператора показывает только результат проверок;
администратор меняет параметр orders.max-unpaid, видит 412 при чужой правке раньше, находит запись в журнале аудита и возвращает значение по умолчанию. Страницы и ответы API оператора
проверяются на отсутствие ключа, кодов и адресов покупателя. Нужен тот же стенд (набор full с fast-up, DEBUG=1, make keycloak-users) и пользователи support-1 и admin-1.

Переменные: UI_PURCHASE_REPEAT (сколько раз подряд проходит покупка до выдачи, по умолчанию 1, в CI 3: серия К8), UI_AUTH_GAP (пауза в секундах перед каждым сценарием покупателя, по умолчанию 30: лимит входа шлюза 30 запросов в минуту с адреса), UI_VIDEO=1 (запись экрана каждого сценария в UI_REPORT_DIR/video/moderator.webm, guest.webm, buyer-1.webm … buyer-N.webm, refusal.webm и expired.webm; запись не должна ронять проверку, при сбое печатается замечание; в CI включена, видео лежит в артефакте запуска), UI_BASE (адрес шлюза, по умолчанию https://localhost:8443), UI_REPORT_DIR, UI_TIMEOUT_MS (ожидание элемента, по умолчанию 20000), UI_NO_SANDBOX=1
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
import audit_checks as AU  # noqa: E402
import email_change_checks as EC  # noqa: E402
import gateway_checks as G  # noqa: E402
import kc_client as K  # noqa: E402
import orders_checks as O  # noqa: E402
import purchase_checks as P  # noqa: E402
import seller_approval_checks as SAC  # noqa: E402
import sellers_checks as SC  # noqa: E402
import support_checks as SU  # noqa: E402

try:
    from playwright.sync_api import expect as pw_expect, sync_playwright
except ImportError:  # пакет не установлен: main() скажет об этом и вернёт 2
    pw_expect = sync_playwright = None

BASE = os.environ.get('UI_BASE', 'https://localhost:8443').rstrip('/')
REPORT = os.environ.get('UI_REPORT_DIR', os.path.join('build', 'ui-smoke'))
TIMEOUT = int(os.environ.get('UI_TIMEOUT_MS', '20000'))
VIDEO = os.environ.get('UI_VIDEO') == '1'
PURCHASE_REPEAT = max(1, int(os.environ.get('UI_PURCHASE_REPEAT', '1')))
# Шлюз пускает на входные страницы и токены Keycloak (/auth/realms) 30 запросов в минуту с одного адреса (группа auth, NFT-3.5). Один сценарий покупки
# тратит 10 – 15: токен покупателя, страница входа, обмен кода, затем по одному возврату к Keycloak при каждом полном переходе на страницу заглушки и обратно.
# Лимит не отключают (его проверяет gateway_checks.py), сценарии идут с паузой, которая возвращает ведро: первый запуск в CI без неё получил 429 на входе
AUTH_GAP = float(os.environ.get('UI_AUTH_GAP', '30'))
UUID = r'[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}'
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


def new_context(browser):
    """Контекст браузера для одного сценария; при UI_VIDEO=1 экран записывается (1280 на 720). Если запись не стартует, сценарий идёт без неё."""
    options = dict(ignore_https_errors=True, locale='ru-RU', timezone_id='Europe/Moscow', viewport={'width': 1280, 'height': 720})
    if VIDEO:
        try:
            return browser.new_context(record_video_dir=os.path.join(REPORT, 'video'), record_video_size={'width': 1280, 'height': 720}, **options)
        except Exception as e:  # noqa: BLE001 запись экрана вспомогательная, проверку она ронять не должна
            print('замечание: запись экрана не включена (%s)' % str(e).splitlines()[0][:160])
    return browser.new_context(**options)


def close_context(context, page, name):
    """Закрывает контекст; видео сценария сохраняется как REPORT/video/<name>.webm."""
    video = page.video if VIDEO else None
    context.close()
    if video:
        try:
            os.replace(video.path(), os.path.join(REPORT, 'video', name + '.webm'))
        except Exception as e:  # noqa: BLE001
            print('замечание: видео сценария %s не сохранено (%s)' % (name, str(e).splitlines()[0][:160]))


# --------------------------------------------------------------------------------------------------- сценарии
def moderator_scenario(browser, kc, admin, user, moderator, data):
    print('== Модератор в браузере: вход с TOTP, решения по заявке и товарам (К4, US-2.2, US-3.3, ST-13; TC-222, TC-328)')
    context = new_context(browser)
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
        close_context(context, page, 'moderator')


def guest_scenario(browser, data):
    print('== Гость без входа: витрина находит опубликованный товар (К4, US-3.3 – US-3.5; TC-326)')
    context = new_context(browser)
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
        close_context(context, page, 'guest')


# --------------------------------------------------------------------------------------------------- покупатель (К8)
def sign_in_buyer(page, admin, buyer, button):
    """Вход покупателя с карточки товара: кнопка «Войти, чтобы купить», страница Keycloak, возврат на ту же карточку."""
    page.get_by_role('button', name=button, exact=True).click()
    page.wait_for_url(re.compile(r'/auth/realms/dgm/'), timeout=TIMEOUT * 2)
    admin.clear_attacks(buyer.id)
    page.locator('input[name=username]').fill(buyer.email)
    page.locator('input[name=password]').fill(buyer.data['password'])
    page.locator('#kc-login').click()
    page.wait_for_url(re.compile(r'^' + re.escape(BASE) + r'/products/'), timeout=TIMEOUT * 2)


def open_product(page, pid):
    page.goto('%s/products/%s' % (BASE, pid), wait_until='domcontentloaded')


def buy(page, quantity=None):
    """Форма покупки: количество, «Купить» и переход на страницу платёжной заглушки. Возвращает адрес страницы оплаты."""
    field = page.get_by_label(T('purchase.quantity'), exact=True)
    visible(field)
    if quantity is not None:
        field.fill(str(quantity))
    page.get_by_role('button', name=T('purchase.buy'), exact=True).click()
    page.wait_for_url(re.compile(r'/payment/pay/'), timeout=TIMEOUT)
    visible(page.get_by_role('heading', name='Платёжный шлюз (заглушка)', level=1, exact=True))
    return page.url


def order_id_on_stub_page(page):
    found = re.search(r'заказ (%s)' % UUID, page.inner_text('body'))
    if not found:
        raise AssertionError('на странице оплаты нет номера заказа')
    return found.group(1)


def back_to_platform(page, button):
    """Кнопка на странице заглушки, затем 303 на платформу: адрес /orders/<id> ведёт на экран заказа, вход обновляется по сессии Keycloak без пароля."""
    page.get_by_role('button', name=button, exact=True).click()
    page.wait_for_url(re.compile(r'^' + re.escape(BASE) + r'/account/orders/' + UUID + r'$'), timeout=TIMEOUT * 2)
    visible(page.get_by_role('heading', name=re.compile(r'^' + re.escape(RU['order.title']).replace(re.escape('{number}'), r'\d+') + r'$'), level=1))


def order_number(page):
    return re.search(r'\d+', page.get_by_role('heading', level=1).first.inner_text()).group(0)


def purchase_scenario(browser, fx, buyer, name, pid):
    """Покупка до выдачи: количество 2, оплата на странице заглушки, «Выдан», письмо с ключами, запись в истории (US-5.1, US-5.3, US-5.9, US-6.1)."""
    print('== Покупатель в браузере (%s): карточка, «Купить», оплата, экран заказа до «Выдан», история (К8; US-5.1, US-5.3, US-5.9)' % name)
    stubs, admin = fx['stubs'], fx['admin']
    quantity = 2
    context = new_context(browser)
    context.set_default_timeout(TIMEOUT)
    page = context.new_page()
    watch = Watch(page)
    state = {}
    try:
        def sign_in():
            open_product(page, pid)
            visible(page.get_by_role('button', name=T('purchase.signIn'), exact=True))
            sign_in_buyer(page, admin, buyer, T('purchase.signIn'))
            visible(page.get_by_label(T('purchase.quantity'), exact=True))
        if not step(page, watch, 'карточка товара: гость видит «%s», после входа появляется форма количества' % T('purchase.signIn'), sign_in):
            return

        def total():
            page.get_by_label(T('purchase.quantity'), exact=True).fill(str(quantity))
            visible(page.get_by_text(re.compile(r'Итого:\s*2\s998')).first)
        if not step(page, watch, 'количество %d: итог 2 998 ₽ считается из цены карточки' % quantity, total):
            return

        def start():
            state['pay_url'] = buy(page, quantity)
            visible(page.get_by_text(re.compile(r'2998\.00 RUB')).first)
            state['order_id'] = order_id_on_stub_page(page)
        if not step(page, watch, '«%s»: переход на страницу платёжной заглушки, сумма 2998.00 RUB' % T('purchase.buy'), start):
            return
        oid = state['order_id']
        O.expect('заказ из браузера создан в статусе awaiting_payment, резерв активен на две штуки', O.reservations_of(oid) == [('active', '', quantity)], str(O.reservations_of(oid)))

        def pay():
            back_to_platform(page, 'Оплатить')
            state['number'] = order_number(page)
            visible(page.get_by_text(T('order.status.issued'), exact=True), 60000)
            visible(page.get_by_text(T('order.state.issued', address=buyer.email), exact=True))
        if not step(page, watch, '«Оплатить» на заглушке, возврат на экран заказа, статус «%s» без перезагрузки страницы' % T('order.status.issued'), pay):
            return

        letter, keys = P.key_letter('UI', stubs, buyer.email, quantity)
        text = page.inner_text('body')
        O.expect('на экране заказа нет значений ключей: они пришли только письмом (INV-20, FT-5.4)', bool(keys) and not any(key in text for key in keys), str(keys)[:200])
        order = O.get_order(buyer, oid)
        O.expect('по API заказ issued, ключей в ответе нет', order.status == 200 and order.json().get('status') == 'issued' and 'keys' not in order.json(), O.describe(order))

        def history():
            page.get_by_role('link', name=T('nav.orders'), exact=True).click()
            visible(page.get_by_role('heading', name=T('orders.title'), level=1, exact=True))
            row = page.get_by_role('row').filter(has=page.get_by_role('link', name=state['number'], exact=True))
            visible(row)
            visible(row.get_by_text(T('order.status.issued'), exact=True))
        step(page, watch, 'история заказов: заказ № %s со статусом «%s»' % (state.get('number', '?'), T('order.status.issued')), history)
        fx['emails'].append(buyer.email)
    finally:
        close_context(context, page, name)


def refusal_scenario(browser, fx, buyer, pid):
    """Отказ в оплате: причина на экране, «Оформить заново», ключи вернулись в продажу (US-5.4, FT-6.3), то же, что проверяет orders-check, но глазами покупателя."""
    print('== Покупатель в браузере: отказ в оплате, причина на экране, повторный заказ (К8; US-5.4)')
    admin = fx['admin']
    before = O.keys_of(pid)
    context = new_context(browser)
    context.set_default_timeout(TIMEOUT)
    page = context.new_page()
    watch = Watch(page)
    try:
        def start():
            open_product(page, pid)
            sign_in_buyer(page, admin, buyer, T('purchase.signIn'))
            buy(page)
        if not step(page, watch, 'вход и «%s» на одну штуку: страница платёжной заглушки' % T('purchase.buy'), start):
            return

        def declined():
            back_to_platform(page, 'Отклонить')
            visible(page.get_by_text(T('order.status.cancelled'), exact=True), 60000)
            visible(page.get_by_text(T('order.state.cancelled', reason=T('order.cancel.payment_declined')), exact=True))
            O.expect('у отменённого заказа нет кнопки «%s»' % T('order.pay'), page.get_by_role('button', name=T('order.pay'), exact=True).count() == 0)
        if not step(page, watch, '«Отклонить» на заглушке: экран заказа показывает «%s» и причину отказа' % T('order.status.cancelled'), declined):
            return
        O.expect('ключи вернулись в продажу: свободных столько же, сколько до заказа', A.eventually(lambda: O.keys_of(pid) == before, wait=30), '%s %s' % (before, O.keys_of(pid)))

        def again():
            page.get_by_role('link', name=T('order.buyAgain'), exact=True).click()
            page.wait_for_url(re.compile(r'/products/' + pid + r'$'))
            visible(page.get_by_label(T('purchase.quantity'), exact=True))
            visible(page.get_by_role('button', name=T('purchase.buy'), exact=True))
        step(page, watch, '«%s» ведёт на карточку товара с формой покупки' % T('order.buyAgain'), again)
    finally:
        close_context(context, page, 'refusal')


def expired_scenario(browser, fx, buyer, pid):
    """Истёкший резерв: закрыл страницу оплаты, вернулся к оплате из заказа (второго платежа нет), не заплатил, резерв кончился (US-5.3, US-5.4)."""
    print('== Покупатель в браузере: возврат к оплате из заказа и истёкший резерв (К8; US-5.3, US-5.4)')
    stubs, admin = fx['stubs'], fx['admin']
    before = O.keys_of(pid)
    context = new_context(browser)
    context.set_default_timeout(TIMEOUT)
    page = context.new_page()
    watch = Watch(page)
    state = {}
    try:
        def start():
            open_product(page, pid)
            sign_in_buyer(page, admin, buyer, T('purchase.signIn'))
            state['pay_url'] = buy(page)
            state['order_id'] = order_id_on_stub_page(page)
        if not step(page, watch, 'вход и «%s»: страница платёжной заглушки, платить не будем' % T('purchase.buy'), start):
            return
        oid = state['order_id']

        def back_to_order():
            page.goto('%s/account/orders/%s' % (BASE, oid), wait_until='domcontentloaded')
            visible(page.get_by_text(T('order.status.awaiting_payment'), exact=True), TIMEOUT * 2)
            visible(page.get_by_role('button', name=T('order.pay'), exact=True))
        if not step(page, watch, 'экран заказа после закрытой страницы оплаты: «%s», остаток резерва и «%s»' % (T('order.status.awaiting_payment'), T('order.pay')), back_to_order):
            return

        def pay_again():
            page.get_by_role('button', name=T('order.pay'), exact=True).click()
            page.wait_for_url(re.compile(r'/payment/pay/'), timeout=TIMEOUT)
        step(page, watch, '«%s» из заказа открывает ту же страницу оплаты' % T('order.pay'), pay_again)
        O.expect('адрес страницы оплаты прежний, у шлюза один платёж по заказу: второй не создавался', page.url == state['pay_url'] and len(O.stub_payments(stubs, oid)) == 1,
                 '%s %s' % (page.url, len(O.stub_payments(stubs, oid))))

        def expired():
            page.goto('%s/account/orders/%s' % (BASE, oid), wait_until='domcontentloaded')
            visible(page.get_by_text(T('order.status.cancelled'), exact=True), 120000)
            visible(page.get_by_text(T('order.state.cancelled', reason=T('order.cancel.reservation_expired')), exact=True))
            visible(page.get_by_role('link', name=T('order.buyAgain'), exact=True))
        step(page, watch, 'резерв закончился без оплаты: экран заказа показывает «%s», причину и «%s»' % (T('order.status.cancelled'), T('order.buyAgain')), expired)
        O.expect('резерв released по сроку, ключи свободны', A.eventually(lambda: O.reservations_of(oid) == [('released', 'expired', 1)] and O.keys_of(pid) == before, wait=30),
                 '%s %s %s' % (O.reservations_of(oid), before, O.keys_of(pid)))
    finally:
        close_context(context, page, 'expired')


def purchase_scenarios(browser, fx):
    pid, kc, admin, stubs = fx['plenty'], fx['kc'], fx['admin'], fx['stubs']
    stubs.reset()
    names = ['buyer-%d' % n for n in range(1, PURCHASE_REPEAT + 1)] + ['refusal', 'expired']
    A.eventually(lambda: not (O.keys_of(pid) or {}).get('reserved'), wait=60, step=2.0)   # резервы прошлых проверок успевают истечь
    with stubs.payment_mode(scenario='hold'):
        for name in names:
            print('   пауза %d с перед сценарием «%s»: лимит входа на шлюзе 30 запросов в минуту с адреса' % (AUTH_GAP, name))
            time.sleep(AUTH_GAP)
            buyer = P.new_buyer(kc, admin, fx['stamp'], 'ui-' + name)   # покупатель заводится перед своим сценарием: пачка токенов заранее съела бы ведро
            if not buyer:
                G.bad('покупатель для сценария «%s» заведён' % name, 'не заведён')
                continue
            if name == 'refusal':
                refusal_scenario(browser, fx, buyer, pid)
            elif name == 'expired':
                expired_scenario(browser, fx, buyer, pid)
            else:
                purchase_scenario(browser, fx, buyer, name, pid)
    O.modes_are_default(stubs, 'UI')


# --------------------------------------------------------------------------------------------------- поддержка и администрирование (К11)
class Recorder:
    """Ответы API, которые получил браузер: по ним проверяется, что оператору не пришло ни ключей, ни кодов, ни адресов покупателя."""

    def __init__(self, page):
        self.texts = []
        page.on('response', self._keep)

    def _keep(self, response):
        try:
            if '/api/' in response.url and response.request.resource_type in ('fetch', 'xhr'):
                self.texts.append(response.text())
        except Exception:  # noqa: BLE001 ответ без тела (переход, обрыв) пропускается
            pass


def leaks(texts, needles):
    """Какие из значений needles нашлись хоть в одном из texts."""
    return [needle for needle in needles if needle and any(needle in text for text in texts)]


def sign_in_buyer_at(page, admin, buyer, path):
    """Вход покупателя на защищённой странице: раздел сам отправляет на Keycloak и после входа возвращает на тот же адрес."""
    page.goto(BASE + path, wait_until='domcontentloaded')
    page.wait_for_url(re.compile(r'/auth/realms/dgm/'), timeout=TIMEOUT * 2)
    admin.clear_attacks(buyer.id)
    page.locator('input[name=username]').fill(buyer.email)
    page.locator('input[name=password]').fill(buyer.data['password'])
    page.locator('#kc-login').click()
    page.wait_for_url(re.compile(r'^' + re.escape(BASE + path) + r'$'), timeout=TIMEOUT * 2)


def report_in_browser(page, watch, admin, buyer, oid, state):
    """Покупатель в браузере: заказ «Выдан», «Не получил ключ», «Открыть обращение», карточка обращения «Принято». Адрес карточки кладёт в state."""
    def open_order():
        sign_in_buyer_at(page, admin, buyer, '/account/orders/' + oid)
        visible(page.get_by_text(T('order.status.issued'), exact=True).first, 60000)
        visible(page.get_by_role('button', name=T('ticket.report'), exact=True))
    if not step(page, watch, 'покупатель входит и видит заказ «%s» с кнопкой «%s»' % (T('order.status.issued'), T('ticket.report')), open_order):
        return False

    def report():
        page.get_by_role('button', name=T('ticket.report'), exact=True).click()
        visible(page.get_by_text(T('ticket.report.created'), exact=True))
        page.get_by_role('link', name=T('ticket.report.open'), exact=True).click()
        visible(page.get_by_role('heading', name=T('ticket.title'), level=1, exact=True))
        visible(page.get_by_text(T('ticket.state.created'), exact=True))
        visible(page.get_by_text(T('ticket.status.created'), exact=True).first)
        state['ticket_url'] = page.url
    return step(page, watch, '«%s»: обращение принято, карточка обращения со статусом «%s»' % (T('ticket.report'), T('ticket.status.created')), report)


def operator_takes(page, watch, number):
    """Оператор в браузере: очередь, поиск по короткому номеру заказа, «Взять в работу», карточка обращения у оператора."""
    def take():
        page.get_by_role('link', name=T('supportQueue.home'), exact=True).click()
        visible(page.get_by_role('heading', name=T('supportQueue.title'), level=1, exact=True))
        page.get_by_label(T('supportQueue.search'), exact=True).fill(number)
        page.get_by_role('button', name=T('supportQueue.search.submit'), exact=True).click()
        row = page.get_by_role('row').filter(has=page.get_by_role('link', name=number, exact=True))
        visible(row)
        row.get_by_role('button', name=T('supportQueue.take'), exact=True).click()
        visible(page.get_by_role('heading', name=T('supportCard.title', number=number), level=1, exact=True))
        visible(page.get_by_text(T('supportCard.operator.me'), exact=True).first)
        visible(page.get_by_text(T('supportCard.status.in_progress'), exact=True).first)
    return step(page, watch, 'очередь оператора: поиск по номеру заказа %s, «%s», карточка обращения «%s»' % (number, T('supportQueue.take'), T('supportCard.status.in_progress')), take)


def operator_act(page, action, done, field=None, value=None):
    """Действие по обращению: кнопка, панель подтверждения (с полем, если оно есть), «Подтвердить», сообщение об успехе."""
    page.get_by_role('button', name=action, exact=True).click()
    group = page.get_by_role('group', name=action, exact=True)
    visible(group)
    if field is not None:
        group.get_by_label(field, exact=True).fill(value)
    group.get_by_role('button', name=T('supportCard.confirm'), exact=True).click()
    visible(page.get_by_text(done, exact=True))


def staff_context(browser):
    """Контекст и страница сотрудника, наблюдатель за браузером и запись ответов API. Перед сценарием пауза: лимит входа на шлюзе (F4-22)."""
    time.sleep(AUTH_GAP)
    context = new_context(browser)
    context.set_default_timeout(TIMEOUT)
    page = context.new_page()
    return context, page, Watch(page), Recorder(page)


def support_resend_scenario(browser, ctx):
    """Обращение «Не получил ключ» и повторная отправка глазами покупателя и оператора (US-7.1 – US-7.3, FT-7.2; TC-015)."""
    fx, stubs, kc, admin = ctx.fx, ctx.stubs, ctx.kc, ctx.admin
    print('== Поддержка в браузере: покупатель создаёт обращение, оператор берёт его и повторно отправляет ключ, обращение решено доставкой (К11; US-7.1 – US-7.3)')
    stubs.reset()
    buyer, order, letters = SU.silent_order(fx, 'ui-sp')
    if not buyer:
        return
    oid, number = order['orderId'], str(order['orderNumber'])
    keys = P.KEY_LINE.findall(P.body_of(letters[0]))
    fx['emails'].append(buyer.email)
    state = {}
    time.sleep(AUTH_GAP)
    buyer_context = new_context(browser)
    buyer_context.set_default_timeout(TIMEOUT)
    buyer_page = buyer_context.new_page()
    buyer_watch = Watch(buyer_page)
    operator = None
    try:
        if not report_in_browser(buyer_page, buyer_watch, admin, buyer, oid, state):
            return
        O.expect('по API создано одно обращение покупателя: источник buyer, причина key_not_received, статус created',
                 P.tickets_of(oid) == [('buyer', 'key_not_received', 'created', '')], str(P.tickets_of(oid)))

        operator = staff_context(browser)
        _, page, watch, recorder = operator
        if not step(page, watch, 'вход оператора через Keycloak с паролем и кодом TOTP открывает рабочее место с очередью обращений',
                    lambda: (sign_in_staff(page, kc, admin, ctx.people[SU.OPERATOR]), visible(page.get_by_role('link', name=T('supportQueue.home'), exact=True)))):
            return
        if not operator_takes(page, watch, number):
            return
        card_text = []

        def resend():
            operator_act(page, T('supportCard.resend'), T('supportCard.resend.done'))
            card_text.append(page.inner_text('body'))
        if not step(page, watch, '«%s»: подтверждение и сообщение «%s»' % (T('supportCard.resend'), T('supportCard.resend.done')), resend):
            return

        def resolved():
            visible(page.get_by_text(T('supportCard.resolved'), exact=True), 150000)
            visible(page.get_by_text(T('supportCard.status.resolved'), exact=True).first)
            visible(page.get_by_text(T('supportCard.delivery.delivered'), exact=True).first)
            card_text.append(page.inner_text('body'))
        step(page, watch, 'карточка сама показывает результат: статус «%s», выдача «%s», действий не осталось' % (T('supportCard.status.resolved'), T('supportCard.delivery.delivered')), resolved)
        O.expect('по API обращение решено доставкой: resolved, решила система',
                 A.eventually(lambda: [t[2:] for t in (P.tickets_of(oid) or [])] == [('resolved', 'system')], wait=SU.RESOLVE_SECONDS, step=2.0), str(P.tickets_of(oid)))
        again = P.wait_letters(stubs, count=2, wait=60, to=buyer.email, sender='delivery')
        O.expect('письмо с ключом пришло второй раз, ключи те же, новый ключ не выпускался',
                 len(again) == 2 and P.KEY_LINE.findall(P.body_of(again[1])) == keys and bool(keys), str([m.get('subject') for m in again]))

        found = leaks(card_text + recorder.texts, keys + [buyer.email])
        O.expect('на страницах оператора и в ответах API нет ни ключа, ни адреса покупателя (INV-23); ответов браузера перехвачено: %d' % len(recorder.texts),
                 not found and len(recorder.texts) > 0, str(found))

        def buyer_sees():
            visible(buyer_page.get_by_text(T('ticket.status.resolved'), exact=True).first, 120000)
            visible(buyer_page.get_by_text(T('ticket.resolution.system.resend'), exact=True).first)
        step(buyer_page, buyer_watch, 'покупатель без перезагрузки видит «%s» и итог «%s»' % (T('ticket.status.resolved'), T('ticket.resolution.system.resend')), buyer_sees)
    finally:
        if operator:
            close_context(operator[0], operator[1], 'support-resend-operator')
        close_context(buyer_context, buyer_page, 'support-resend-buyer')


def support_email_change_scenario(browser, ctx):
    """Смена e-mail с вводом обоих кодов из заглушек: оператор начинает, покупатель вводит коды сам, ключ уходит на новый адрес (US-1.7, US-7.4; TC-016)."""
    fx, stubs, kc, admin = ctx.fx, ctx.stubs, ctx.kc, ctx.admin
    print('== Поддержка в браузере: смена e-mail, покупатель вводит код из SMS и код из письма, оператор видит только результаты проверок (К11; US-7.4)')
    stubs.reset()
    who = EC.prepared_buyer(ctx, 'ui-ec')
    if not who:
        return
    buyer, oid, number, old_email = who['buyer'], who['oid'], str(who['number']), who['email']
    new_email = 'ui-new-%d@example.test' % ctx.stamp
    ctx.addresses.append(new_email)
    fx['emails'].append(new_email)
    state = {}
    secrets = [old_email, new_email] + who['keys']
    time.sleep(AUTH_GAP)
    buyer_context = new_context(browser)
    buyer_context.set_default_timeout(TIMEOUT)
    buyer_page = buyer_context.new_page()
    buyer_watch = Watch(buyer_page)
    operator = None
    try:
        if not report_in_browser(buyer_page, buyer_watch, admin, buyer, oid, state):
            return
        operator = staff_context(browser)
        _, page, watch, recorder = operator
        if not step(page, watch, 'вход оператора через Keycloak с паролем и кодом TOTP',
                    lambda: (sign_in_staff(page, kc, admin, ctx.people[SU.OPERATOR]), visible(page.get_by_role('link', name=T('supportQueue.home'), exact=True)))):
            return
        if not operator_takes(page, watch, number):
            return
        before = EC.sms_count(ctx, who['phone'])
        card_text = []

        def start():
            operator_act(page, T('supportCard.emailChange'), T('supportCard.emailChange.done'), T('supportCard.emailChange.field'), new_email)
            visible(page.get_by_text(T('supportCard.change.awaiting_phone_code'), exact=True).first)
            card_text.append(page.inner_text('body'))
            if new_email in card_text[-1] or '***@' not in card_text[-1]:
                raise AssertionError('в карточке оператора должна быть только маска нового адреса')
        if not step(page, watch, '«%s»: адрес вводится в панели, сообщение «%s», этап «%s», в карточке только маска адреса' %
                    (T('supportCard.emailChange'), T('supportCard.emailChange.done'), T('supportCard.change.awaiting_phone_code')), start):
            return
        phone_code = EC.new_codes(ctx, who['phone'], before)
        if not O.expect('SMS с кодом пришло на подтверждённый номер покупателя (шесть цифр)', bool(phone_code) and EC.SIX_DIGITS.fullmatch(phone_code) is not None, str(phone_code)):
            return
        secrets.append(phone_code)

        def phone_step():
            field = buyer_page.get_by_label(T('emailChange.code.phone'), exact=True)
            try:
                visible(field, 30000)
            except Exception:  # форма появляется после очередного опроса статуса; перечитываем страницу
                buyer_page.goto(state['ticket_url'], wait_until='domcontentloaded')
                visible(field, 60000)
            field.fill(phone_code)
            buyer_page.get_by_role('button', name=T('emailChange.verify'), exact=True).click()
            visible(buyer_page.get_by_label(T('emailChange.code.email'), exact=True), 60000)
        if not step(buyer_page, buyer_watch, 'покупатель вводит код из SMS и переходит ко второму шагу: «%s»' % T('emailChange.code.email'), phone_step):
            return
        try:
            letters = stubs.wait_email(to=new_email, subject=EC.CODE_SUBJECT)
        except Exception as failure:  # noqa: BLE001 письма нет: дальше код брать неоткуда
            G.bad('письмо с кодом на новый адрес не пришло', str(failure)[-200:])
            return
        found = EC.SIX_DIGITS.search(P.body_of(letters[0]))
        email_code = found.group(1) if found else None
        if not O.expect('письмо с кодом на новый адрес одно, в нём шесть цифр', len(letters) == 1 and bool(email_code), str([m.get('subject') for m in letters])):
            return
        secrets.append(email_code)

        def email_step():
            buyer_page.get_by_label(T('emailChange.code.email'), exact=True).fill(email_code)
            buyer_page.get_by_role('button', name=T('emailChange.verify'), exact=True).click()
            visible(buyer_page.get_by_text(re.compile(r'^' + re.escape(T('emailChange.completed', address='\0').split('\0')[0]))).first, 120000)
        step(buyer_page, buyer_watch, 'покупатель вводит код из письма: смена завершена, адрес в сообщении замаскирован', email_step)
        O.expect('покупатель на экране не видит нового адреса целиком (INV-44)', new_email not in buyer_page.inner_text('body'), '')

        def operator_sees():
            pw_expect(page.get_by_text(T('supportCard.check.passed'), exact=True)).to_have_count(2, timeout=120000)
            visible(page.get_by_text(T('supportCard.change.completed'), exact=True).first)
            visible(page.get_by_text(T('supportCard.resolved'), exact=True), 150000)
            card_text.append(page.inner_text('body'))
        step(page, watch, 'карточка оператора сама показывает обе проверки «%s», этап «%s» и итог «%s»' %
             (T('supportCard.check.passed'), T('supportCard.change.completed'), T('supportCard.resolved')), operator_sees)

        moved = A.eventually(lambda: (A.keycloak_user(admin, new_email) or {}).get('id') == who['data']['id'], wait=30, step=2.0)
        O.expect('в Keycloak у того же пользователя новый e-mail', bool(moved), '')
        keys = P.wait_letters(stubs, wait=90, to=new_email, sender='delivery')
        O.expect('ключ ушёл на новый адрес одним письмом, ключ тот же', len(keys) == 1 and P.KEY_LINE.findall(P.body_of(keys[0])) == who['keys'], str([m.get('subject') for m in keys]))
        found = leaks(card_text + recorder.texts, secrets)
        O.expect('на страницах оператора и в ответах API нет кодов, адресов покупателя и ключа (INV-23, US-7.4); ответов браузера перехвачено: %d' % len(recorder.texts),
                 not found and len(recorder.texts) > 0, str(found))
    finally:
        if operator:
            close_context(operator[0], operator[1], 'support-email-operator')
        close_context(buyer_context, buyer_page, 'support-email-buyer')
        P.email_is_default(stubs, 'UI поддержка')


def admin_scenario(browser, ctx):
    """Администратор в браузере: правка параметра, чужая правка раньше (412), запись в журнале, возврат к умолчанию (US-8.3, US-8.6; TC-806, TC-810)."""
    kc, admin = ctx.kc, ctx.admin
    key = 'orders.max-unpaid'
    print('== Администратор в браузере: правка параметра %s, чужая правка раньше, журнал, возврат к умолчанию (К11; US-8.3, US-8.6)' % key)
    before = EC.parameter(ctx, key)
    if not O.expect('параметр %s читается через API, значение по умолчанию задано' % key, before is not None, ''):
        return
    first, other = before['value'] + 1, before['value'] + 3
    if first > before['maxValue'] or other > before['maxValue']:
        G.bad('для сценария нужно место в границах параметра', str(before))
        return
    time.sleep(AUTH_GAP)
    context = new_context(browser)
    context.set_default_timeout(TIMEOUT)
    page = context.new_page()
    watch = Watch(page)
    unit = T('admin.unitName.count')
    try:
        def open_parameters():
            sign_in_staff(page, kc, admin, ctx.people['admin-1'])
            visible(page.get_by_role('link', name=T('admin.home.audit'), exact=True))
            page.get_by_role('link', name=T('admin.home.parameters'), exact=True).click()
            visible(page.get_by_role('heading', name=T('admin.params.title'), level=1, exact=True))
            visible(page.get_by_role('button', name=T('admin.params.edit.aria', name=key), exact=True))
        if not step(page, watch, 'вход администратора с TOTP: в рабочем месте параметры и журнал, таблица параметров открыта', open_parameters):
            return

        def edit():
            page.get_by_role('button', name=T('admin.params.edit.aria', name=key), exact=True).click()
            field = page.get_by_label(T('admin.params.edit.label', unit=unit), exact=True)
            field.fill(str(first))
            page.get_by_role('button', name=T('admin.params.save'), exact=True).click()
            visible(page.get_by_text(T('admin.params.saved', name=key, value=str(first), version=before['version'] + 1), exact=True))
        step(page, watch, 'правка %s: %d → %d, сообщение с версией %d' % (key, before['value'], first, before['version'] + 1), edit)
        current = EC.parameter(ctx, key) or {}
        O.expect('по API значение и версия из браузера: %d, версия %d' % (first, before['version'] + 1), current.get('value') == first and current.get('version') == before['version'] + 1, str(current))

        def conflict():
            page.get_by_role('button', name=T('admin.params.edit.aria', name=key), exact=True).click()
            page.get_by_label(T('admin.params.edit.label', unit=unit), exact=True).fill(str(first + 1))
            answer = EC.put_parameter(ctx, key, other)   # другой администратор успел раньше
            O.expect('чужая правка прошла по API: 200', answer is not None and answer.status == 200, O.describe(answer) if answer else '')
            page.get_by_role('button', name=T('admin.params.save'), exact=True).click()
            visible(page.get_by_text(T('admin.params.conflict', name=key), exact=True))
            visible(page.get_by_role('button', name=T('admin.params.edit.aria', name=key), exact=True))
        step(page, watch, 'чужая правка успела раньше: сообщение «%s», правка закрыта' % T('admin.params.conflict', name=key), conflict)
        current = EC.parameter(ctx, key) or {}
        O.expect('чужое значение осталось, правка из браузера ничего не перезаписала', current.get('value') == other, str(current))

        def journal():
            page.get_by_role('link', name=T('moderation.back'), exact=True).click()
            page.get_by_role('link', name=T('admin.home.audit'), exact=True).click()
            visible(page.get_by_role('heading', name=T('audit.title'), level=1, exact=True))
            page.get_by_label(T('audit.filter.objectId'), exact=True).fill(key)
            page.get_by_label(T('audit.filter.action'), exact=True).fill('parameter.changed')
            line = T('audit.change.line', field='value', before=str(before['value']), after=str(first))
            for attempt in range(6):
                page.get_by_role('button', name=T('audit.filter.apply'), exact=True).click()
                try:
                    visible(page.get_by_text(line, exact=True).first, 8000)
                    return
                except Exception:
                    if attempt == 5:
                        raise
        step(page, watch, 'журнал: по объекту и действию видна запись «%s»' % T('audit.change.line', field='value', before=str(before['value']), after=str(first)), journal)
        records = EC.journal_by(ctx, objectType='parameter', objectId=key)
        pairs = [((AU.change_of(r, 'value') or {}).get('before'), (AU.change_of(r, 'value') or {}).get('after')) for r in records]
        O.expect('в журнале по API обе правки: %s → %s и %s → %s' % (before['value'], first, first, other), (str(before['value']), str(first)) in pairs and (str(first), str(other)) in pairs, str(pairs[:6]))
        viewed = EC.journal_by(ctx, action='audit.viewed')
        O.expect('открытие журнала в браузере записано в журнал: audit.viewed с ролью admin', any(r.get('actorRole') == 'admin' for r in viewed), str(len(viewed)))

        def reset():
            page.get_by_role('link', name=T('moderation.back'), exact=True).click()
            page.get_by_role('link', name=T('admin.home.parameters'), exact=True).click()
            visible(page.get_by_role('heading', name=T('admin.params.title'), level=1, exact=True))
            expected = (EC.parameter(ctx, key) or {}).get('version', 0) + 1
            page.get_by_role('button', name=T('admin.params.reset.button', name=key), exact=True).click()
            group = page.get_by_role('group', name=T('admin.params.reset.aria', name=key), exact=True)
            visible(group)
            group.get_by_role('button', name=T('admin.params.reset.confirmButton'), exact=True).click()
            visible(page.get_by_text(T('admin.params.saved', name=key, value=str(before['defaultValue']), version=expected), exact=True))
        step(page, watch, '«%s»: значение по умолчанию %d сохранено' % (T('admin.params.reset'), before['defaultValue']), reset)
    finally:
        close_context(context, page, 'admin')
        current = EC.parameter(ctx, key)
        if current and current['value'] != current['defaultValue']:
            EC.put_parameter(ctx, key, current['defaultValue'])
        current = EC.parameter(ctx, key) or {}
        O.expect('параметр %s равен значению по умолчанию (иначе проверка красная)' % key, bool(current) and current.get('value') == current.get('defaultValue'), str(current))


def support_scenarios(browser, ctx):
    """Сценарии поддержки и администрирования идут по одному: входы с паузой UI_AUTH_GAP, лимит запросов кода с IP поднят на время сценариев."""
    if not O.guarded('raise_ip_limit', EC.raise_ip_limit, ctx):
        G.bad('Лимит с одного IP не поднят, сценарии поддержки в браузере не запускались')
        return
    try:
        for scenario in (support_resend_scenario, support_email_change_scenario, admin_scenario):
            O.guarded(scenario.__name__, scenario, browser, ctx)
    finally:
        O.guarded('restore_ip_limit', EC.restore_ip_limit, ctx)


def support_main():
    kc, admin = K.from_env(dict(os.environ))
    people = G.users()
    for name in ('admin-1', SU.OPERATOR):
        if name not in people:
            print('Нет тестового пользователя %s (make keycloak-users)' % name, file=sys.stderr)
            return 2
    stamp = int(time.time())
    fx = O.context(kc, admin, people, stamp, scarce_needed=False)
    if fx:
        fx['people'] = people
        fx['orders'] = []
        ctx = EC.Context(kc, admin, people, stamp, fx)
        ctx.operator.token()
        ctx.administrator.token()
        with sync_playwright() as playwright:
            browser = playwright.chromium.launch(args=['--no-sandbox'] if os.environ.get('UI_NO_SANDBOX') else [])
            try:
                support_scenarios(browser, ctx)
            finally:
                browser.close()
    else:
        G.bad('Продавец и товары для заказов не заведены, сценарии поддержки в браузере не запускались')
    return finish(bool(fx))


def finish(ready):
    print('\nПроверок успешно: %d, с ошибками: %d' % (G.PASSED, len(G.FAILED)))
    for name in G.FAILED:
        print('  - %s' % name)
    if os.environ.get('GITHUB_ACTIONS'):
        print('::notice title=ui_smoke::Проверок успешно: %d, с ошибками: %d%s' % (G.PASSED, len(G.FAILED), ('; не прошли: ' + '|'.join(G.FAILED)) if G.FAILED else ''))
    return 1 if G.FAILED or not ready else 0


# --------------------------------------------------------------------------------------------------- запуск
def main():
    if sync_playwright is None:
        print('Нет пакета playwright: python3 -m pip install -r tools/stand-checks/requirements-ui.txt && python3 -m playwright install --with-deps chromium', file=sys.stderr)
        return 2
    if os.environ.get('UI_SCOPE') == 'support':
        return support_main()
    kc, admin = K.from_env(dict(os.environ))
    people = G.users()
    if 'moderator-1' not in people:
        print('Нет тестового пользователя moderator-1 (make keycloak-users)', file=sys.stderr)
        return 2
    stamp = int(time.time())
    moderator = SAC.Staff(kc, admin, people['moderator-1'])
    moderator.token()
    data = prepare(kc, admin, moderator, stamp)
    fx = O.context(kc, admin, people, stamp, scarce_needed=False)
    if data or fx:
        with sync_playwright() as playwright:
            browser = playwright.chromium.launch(args=['--no-sandbox'] if os.environ.get('UI_NO_SANDBOX') else [])
            try:
                if data:
                    moderator_scenario(browser, kc, admin, people['moderator-1'], moderator, data)
                    guest_scenario(browser, data)
                if fx:
                    O.guarded('purchase_scenarios', purchase_scenarios, browser, fx)
            finally:
                browser.close()
    if not fx:
        G.bad('Продавец и товары для покупки не заведены, сценарии покупателя не запускались')
    return finish(bool(data and fx))


if __name__ == '__main__':
    sys.exit(main())
