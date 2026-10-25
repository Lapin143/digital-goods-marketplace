#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Каркас сервисов (libs/service-kit) против документов: код не расходится с тем, что записано в конвенциях, DDL и ADR.

Проверяется (шаг 11 Ф3):
  1. Реестр типов проблем в коде (enum ProblemType) совпадает с таблицей 9.1 conventions.md: коды, статусы HTTP, названия.
  2. Таблицы outbox, processed_event и idempotency_key в миграциях сервисов совпадают с DDL в docs/07-data/ddl (одинаковые во всех
     шести базах).
  3. Столбцы в SQL каркаса (запись и чтение Outbox, таблица обработанных событий, ключи идемпотентности) есть в DDL: переименование
     столбца в документе без правки кода, и наоборот, ловится здесь, а не на стенде. Вставка задаёт все обязательные столбцы.
  4. Метрики Outbox, названные в ADR-005, есть в коде публикатора.
  5. Идемпотентность (шаг 3 Ф4): типы проблем из ADR-006 есть в реестре, заголовки ключа и подписи вебхука в коде совпадают с
     описанием в components.yaml, срок хранения ключей в коде равен сроку в ADR-006.
  6. Вызов соседа (шаг 3 среза 2, Р-140): значения клиента по умолчанию (попытка, число попыток, паузы, окно и порог предохранителя)
     равны таблице ADR-015; повторы идут по тем же кодам ответа, что в ADR-015; исключения и метрики клиента на месте; помощник аренды
     использует SKIP LOCKED; множества Redis (Р-141) на месте.
  7. Параметры платформы (шаг 7 среза 3, правило 19): ключ параметра из начальных значений V5__audit_admin.sql назван в коде сервиса только
     в классах настроек (*Settings.java) и подключения (*Configuration.java). Прямое чтение через @Value или Environment в другом месте
     обходит слушатель platform.config, и значение перестаёт быть живым. platform-service читает параметры из собственной базы и не проверяется.
  8. Профиль fast-time (Р-175): параметр платформы, который профиль меняет, перечислен в dgm.config.pinned-keys того же файла; иначе
     платформа при старте переиздаст значение по умолчанию, слушатель его применит, и сокращённый срок пропадёт. Закреплять ключ, которого
     нет среди параметров платформы, нельзя (опечатка закрепила бы пустое место).
  9. Строка журнала о применении значения (шаг 8 среза 3): проверка стенда parameters_checks.py узнаёт о применении параметра по строке
     «Параметр <ключ> = <значение> (было …», которую пишет ConfigRegistry (метрики сервисов на стенде full с хоста недоступны). Начало строки
     в коде и в проверке одно и то же, иначе проверка ждала бы строку, которой нет.
Запуск: python3 tools/docs-checks/check_kit.py
"""
import glob
import os
import re
import sys

import yaml

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import docslib as L

KIT = os.path.join(L.REPO, 'libs', 'service-kit', 'src', 'main', 'java', 'dgm', 'kit')
CONVENTIONS = os.path.join(L.ARCH, 'conventions.md')
ADR_005 = os.path.join(L.ARCH, 'adr', 'ADR-005-transactional-outbox.md')
ADR_006 = os.path.join(L.ARCH, 'adr', 'ADR-006-idempotency.md')
ADR_015 = os.path.join(L.ARCH, 'adr', 'ADR-015-payment-gateway-integration.md')
COMPONENTS = os.path.join(L.DOCS, '06-api', 'openapi', 'components.yaml')
DDL_DIR = os.path.join(L.DOCS, '07-data', 'ddl')

# слова SQL и функции, которые не являются столбцами
SQL_WORDS = set('''select insert into values update set delete from where and or not is null order by limit in now make_interval secs
interval cast as jsonb uuid text on conflict do nothing returning count min max coalesce desc asc
pg_try_advisory_lock pg_advisory_unlock'''.split())
TABLES = {'outbox', 'processed_event', 'idempotency_key'}

ENUM_RX = re.compile(r'^\s+([A-Z][A-Z0-9_]*)\("([a-z0-9-]+)",\s*(\d{3}),\s*"((?:[^"\\]|\\.)*)"\)\s*[,;]', re.M)
STMT_RX = re.compile(r'((?:"(?:[^"\\]|\\.)*"\s*\+?\s*)+)')
LIT_RX = re.compile(r'"((?:[^"\\]|\\.)*)"')


def java_files(sub):
    return sorted(glob.glob(os.path.join(KIT, sub, '*.java')))


def problem_types(rep):
    where = 'ProblemType.java / conventions.md 9.1'
    code = {}
    for m in ENUM_RX.finditer(L.read(os.path.join(KIT, 'problem', 'ProblemType.java'))):
        code[m.group(2)] = (int(m.group(3)), m.group(4))
    t = L.table_after(L.read(CONVENTIONS), r'^### 9\.1\. Реестр типов проблем')
    if not t:
        rep.err(where, 'в conventions.md не найдена таблица 9.1')
        return
    doc = {}
    for r in t['rows']:
        doc[r[0].strip('`')] = (int(r[1]), r[2])
    for k in sorted(set(doc) - set(code)):
        rep.err(where, 'тип «%s» есть в документе и нет в коде' % k)
    for k in sorted(set(code) - set(doc)):
        rep.err(where, 'тип «%s» есть в коде и нет в документе: сначала добавляется в реестр 9.1' % k)
    for k in sorted(set(doc) & set(code)):
        if doc[k][0] != code[k][0]:
            rep.err(where, '«%s»: статус в документе %d, в коде %d' % (k, doc[k][0], code[k][0]))
        if doc[k][1] != code[k][1]:
            rep.err(where, '«%s»: название в документе «%s», в коде «%s»' % (k, doc[k][1], code[k][1]))
    rep.fact('типов проблем: в документе %d, в коде %d' % (len(doc), len(code)))


def table_columns(sql, table):
    """Столбцы таблицы public.<table> из create table: имя -> (не null, есть значение по умолчанию или identity)."""
    m = re.search(r'create table (?:public\.)?%s \((.*?)\n\);' % re.escape(table), sql, re.S)
    if not m:
        return None
    cols = {}
    depth = 0           # скобки незавершённого ограничения: его строки не столбцы
    for line in m.group(1).split('\n'):
        line = line.strip()
        if depth > 0:
            depth += line.count('(') - line.count(')')
            continue
        if not line or line.startswith('--'):
            continue
        if line.startswith('constraint'):
            depth = max(0, line.count('(') - line.count(')'))
            continue
        parts = line.rstrip(',').split()
        low = line.lower()
        cols[parts[0]] = ('not null' in low or 'primary key' in low, ' default ' in low or 'generated always' in low)
    return cols


def ddl_tables(rep):
    where = 'DDL outbox, processed_event и idempotency_key'
    result = {}
    for table in sorted(TABLES):
        seen = {}
        for f in sorted(glob.glob(os.path.join(DDL_DIR, '*.sql'))):
            cols = table_columns(L.read(f), table)
            if cols is None:
                rep.err(where, 'в %s нет таблицы %s' % (L.rel(f), table))
            else:
                seen[os.path.basename(f)] = cols
        shapes = {tuple(sorted(c.items())) for c in seen.values()}
        if len(shapes) > 1:
            rep.err(where, 'таблица %s в разных базах разная: служебные таблицы должны быть одинаковы' % table)
        if seen:
            result[table] = next(iter(seen.values()))
        rep.fact('%s: %d столбцов, одинаковая в %d базах' % (table, len(result.get(table, {})), len(seen)))
    return result


def sql_statements(path):
    text = L.read(path)
    # без комментариев javadoc и строк: только литералы подряд через +
    out = []
    for m in STMT_RX.finditer(text):
        s = ''.join(LIT_RX.findall(m.group(1)))
        if re.match(r'\s*(select|insert|update|delete)\b', s, re.I) and any(t in s for t in TABLES):
            out.append(s)
    return out


def sql_columns(rep, cols):
    where = 'SQL каркаса и DDL'
    files = java_files('outbox') + java_files('events') + java_files('idempotency')
    total = 0
    for f in files:
        for s in sql_statements(f):
            total += 1
            table = next(t for t in sorted(TABLES) if re.search(r'\b%s\b' % t, s))
            known = set(cols.get(table, {}))
            # без приведений типов (::jsonb) и без строковых литералов ('in_progress')
            bare = re.sub(r"'[^']*'", '', re.sub(r'::\w+', '', s.lower()))
            tokens = {w for w in re.findall(r'[a-z_][a-z_0-9]*', bare)}
            unknown = sorted(tokens - SQL_WORDS - TABLES - known)
            if unknown:
                rep.err(where, '%s: в SQL «%s…» нет в DDL %s: %s' % (os.path.basename(f), s[:48], table, ', '.join(unknown)))
            m = re.match(r'\s*insert into %s \(([^)]*)\)' % table, s, re.I)
            if m:
                given = {c.strip() for c in m.group(1).split(',')}
                required = {c for c, (nn, dflt) in cols[table].items() if nn and not dflt}
                for c in sorted(required - given):
                    rep.err(where, '%s: запись в %s не задаёт обязательный столбец %s' % (os.path.basename(f), table, c))
    if total == 0:
        rep.err(where, 'в каркасе не найдено ни одного запроса к служебным таблицам: разбор устарел')
    rep.fact('запросов к служебным таблицам проверено: %d' % total)


def metrics(rep):
    where = 'ADR-005 и OutboxRelay.java'
    adr = L.read(ADR_005)
    names = sorted(set(re.findall(r'`(outbox_[a-z_]+)`', adr)))
    code = L.read(os.path.join(KIT, 'outbox', 'OutboxRelay.java'))
    if not names:
        rep.err(where, 'в ADR-005 не найдено ни одной метрики outbox_*')
    for n in names:
        if '"%s"' % n not in code:
            rep.err(where, 'метрика %s названа в ADR-005, а в публикаторе её нет' % n)
    rep.fact('метрик Outbox в ADR-005: %d' % len(names))


def const(text, name):
    m = re.search(r'\b%s\s*=\s*"([^"]*)"' % re.escape(name), text)
    return m.group(1) if m else None


def idempotency(rep):
    where = 'ADR-006, components.yaml и код идемпотентности'
    adr = L.read(ADR_006)
    # 1. типы проблем из ADR-006 есть в реестре кода
    registry = set(re.findall(r'^\s+[A-Z][A-Z0-9_]*\("([a-z0-9-]+)"', L.read(os.path.join(KIT, 'problem', 'ProblemType.java')), re.M))
    named = sorted(set(re.findall(r'`((?:idempotency-key|request)-[a-z-]+)`', adr)))
    if len(named) < 3:
        rep.err(where, 'в ADR-006 не найдены три типа проблем идемпотентности (найдено %d)' % len(named))
    for n in named:
        if n not in registry:
            rep.err(where, 'тип проблемы %s назван в ADR-006, а в реестре кода его нет' % n)
    # 2. заголовки
    components = L.read(COMPONENTS)
    filt = L.read(os.path.join(KIT, 'idempotency', 'IdempotencyFilter.java'))
    key_header = const(filt, 'KEY_HEADER')
    if not key_header or not re.search(r'^\s+IdempotencyKey:\s*\n\s+name:\s*%s\s*$' % re.escape(key_header), components, re.M):
        rep.err(where, 'заголовок ключа в коде (%s) не совпадает с параметром IdempotencyKey в components.yaml' % key_header)
    signature = L.read(os.path.join(KIT, 'crypto', 'WebhookSignature.java'))
    for const_name, scheme_rx in (('SIGNATURE_HEADER', r'^\s+webhookSignature:.*?name:\s*%s\b'), ('TIMESTAMP_HEADER', None)):
        value = const(signature, const_name)
        if not value:
            rep.err(where, 'в WebhookSignature.java не найдена константа %s' % const_name)
        elif scheme_rx and not re.search(scheme_rx % re.escape(value), components, re.M | re.S):
            rep.err(where, 'заголовок подписи в коде (%s) не совпадает со схемой webhookSignature в components.yaml' % value)
        elif const_name == 'TIMESTAMP_HEADER' and value not in components:
            rep.err(where, 'заголовок метки времени %s не описан в components.yaml' % value)
    # 3. срок хранения 24 часа: в ADR и в коде
    guard = L.read(os.path.join(KIT, 'idempotency', 'IdempotencyGuard.java'))
    if not re.search(r'RETENTION\s*=\s*Duration\.ofHours\(24\)', guard):
        rep.err(where, 'срок хранения ключей в IdempotencyGuard.RETENTION не равен 24 часам, как в ADR-006')
    if 'Срок хранения 24 часа' not in adr:
        rep.err(where, 'в ADR-006 не найдена фраза про срок хранения 24 часа: сверка устарела')
    rep.fact('идемпотентность: типов проблем в ADR-006 %d, заголовки и срок хранения сверены' % len(named))


def peers(rep):
    where = 'ADR-015 и клиент вызова соседа'
    adr = L.read(ADR_015)
    client = L.read(os.path.join(KIT, 'client', 'PeerClient.java'))
    # 1. значения по умолчанию против таблицы ADR-015
    m_attempt = re.search(r'Тайм-аут одной попытки \| (\d+) секунд', adr)
    m_tries = re.search(r'Попыток \| До (\d+), паузы (\d+) мс и (\d+) мс', adr)
    m_breaker = re.search(r'(\d+)% ошибок на последних (\d+) вызовах, пауза (\d+) с', adr)
    if not (m_attempt and m_tries and m_breaker):
        rep.err(where, 'в таблице ADR-015 не найдены тайм-аут, число попыток, паузы или предохранитель: сверка устарела')
        return
    attempt = int(m_attempt.group(1))
    tries, p1, p2 = int(m_tries.group(1)), int(m_tries.group(2)), int(m_tries.group(3))
    ratio, window, pause = int(m_breaker.group(1)), int(m_breaker.group(2)), int(m_breaker.group(3))
    m = re.search(r'Duration attempt = Duration\.ofSeconds\((\d+)\)', client)
    if not m or int(m.group(1)) != attempt:
        rep.err(where, 'тайм-аут попытки по умолчанию в PeerClient.Settings.of (%s) не равен %d с из ADR-015' % (m.group(1) if m else 'не найден', attempt))
    m = re.search(r'RetryPolicy\.parse\("([^"]+)"', client)
    expected = '%dms,%dms' % (p1, p2)
    if not m or m.group(1) != expected:
        rep.err(where, 'паузы повторов по умолчанию в PeerClient (%s) не равны «%s» из ADR-015' % (m.group(1) if m else 'не найдены', expected))
    elif len(m.group(1).split(',')) + 1 != tries:
        rep.err(where, 'число попыток по умолчанию (%d) не равно %d из ADR-015' % (len(m.group(1).split(',')) + 1, tries))
    m = re.search(r'new Settings\(peer, base, attempt, total, retry, (\d+), ([0-9.]+), Duration\.ofSeconds\((\d+)\)\)', client)
    if not m:
        rep.err(where, 'в PeerClient.Settings.of не найдены окно, порог и пауза предохранителя')
    else:
        if int(m.group(1)) != window:
            rep.err(where, 'окно предохранителя по умолчанию (%s) не равно %d вызовам из ADR-015' % (m.group(1), window))
        if round(float(m.group(2)) * 100) != ratio:
            rep.err(where, 'порог предохранителя по умолчанию (%s) не равен %d %% из ADR-015' % (m.group(2), ratio))
        if int(m.group(3)) != pause:
            rep.err(where, 'пауза предохранителя по умолчанию (%s с) не равна %d с из ADR-015' % (m.group(3), pause))
    # 2. что повторяется: сеть, 5xx и 429, не 4xx (ADR-015)
    if 'Повторяются сетевые ошибки, 5xx и 429, не повторяются 4xx' not in adr:
        rep.err(where, 'в ADR-015 не найдена фраза про то, что повторяется: сверка устарела')
    if not re.search(r'status == 429 \|\| status >= 500', client):
        rep.err(where, 'PeerClient повторяет не те ответы, что названы в ADR-015 (ожидается 429 и 5xx)')
    # 3. типизированные ошибки и метрики из руководства среза 2 (3.1)
    for name in ('DependencyUnavailableException', 'DependencyRejectedException', 'CircuitBreaker'):
        if not os.path.exists(os.path.join(KIT, 'client', name + '.java')):
            rep.err(where, 'в dgm.kit.client нет класса %s' % name)
    for metric in ('dgm_client_requests_total', 'dgm_client_breaker_state'):
        if '"%s"' % metric not in client:
            rep.err(where, 'метрика %s не регистрируется в PeerClient' % metric)
    # 4. аренда строк берёт работу с пропуском занятых (правило 15), множества Redis по Р-141
    claim = L.read(os.path.join(KIT, 'jdbc', 'RowClaim.java'))
    if claim.count('for update skip locked') < 2:
        rep.err(where, 'RowClaim не использует for update skip locked в обоих запросах (аренда и блокировка): два экземпляра сервиса будут ждать друг друга')
    redis = L.read(os.path.join(KIT, 'redis', 'KitRedis.java'))
    for method in ('zadd', 'zrangeByScore', 'zrem', 'zcard'):
        if not re.search(r'public \w+(?:<[^>]+>)? %s\(' % method, redis):
            rep.err(where, 'в KitRedis нет метода %s (Р-141)' % method)
    rep.fact('вызов соседа: попытка %d с, попыток %d, паузы %d и %d мс, предохранитель %d %% на %d вызовах, пауза %d с' %
             (attempt, tries, p1, p2, ratio, window, pause))


PARAMETERS_SQL = os.path.join(L.REPO, 'services', 'platform-service', 'src', 'main', 'resources', 'db', 'migration', 'audit_admin',
                              'V5__audit_admin.sql')
PARAMETER_ROW_RX = re.compile(r"^\s+\('([a-z][a-z0-9.\-]*)',", re.M)
SERVICES_DIR = os.path.join(L.REPO, 'services')


def parameter_keys():
    """Ключи параметров платформы из начальных значений: перечень «ключ, описание, единица, значение, …» после insert в platform_parameter."""
    text = L.read(PARAMETERS_SQL)
    start = text.find('insert into audit_admin.platform_parameter')
    if start < 0:
        return None
    end = text.find(';', start)
    return PARAMETER_ROW_RX.findall(text[start:end])


def config_keys(rep):
    where = 'параметры платформы: чтение только через ConfigValue (правило 19)'
    keys = parameter_keys()
    if not keys:
        rep.err(where, 'в %s не найден перечень начальных значений platform_parameter' % L.rel(PARAMETERS_SQL))
        return
    checked = 0
    for service in sorted(os.listdir(SERVICES_DIR)):
        if service == 'platform-service':
            continue
        root = os.path.join(SERVICES_DIR, service, 'src', 'main', 'java')
        for path in sorted(glob.glob(os.path.join(root, '**', '*.java'), recursive=True)):
            name = os.path.basename(path)
            if name.endswith('Settings.java') or name.endswith('Configuration.java'):
                continue
            checked += 1
            text = L.read(path)
            for key in keys:
                if re.search(r'"[^"\n]*(?<![A-Za-z0-9.\-])%s(?![A-Za-z0-9.\-])[^"\n]*"' % re.escape(key), text):
                    rep.err(where, '%s: ключ параметра «%s» назван вне класса настроек или подключения: читайте значение из настроек сервиса '
                            '(ConfigValue), прямое чтение обходит слушатель platform.config' % (L.rel(path), key))
    rep.fact('параметров платформы %d: прямого чтения в %d файлах сервисов нет' % (len(keys), checked))


def flatten(node, prefix=''):
    out = {}
    if isinstance(node, dict):
        for k, v in node.items():
            out.update(flatten(v, prefix + str(k) + '.' if isinstance(v, dict) else prefix + str(k)))
    else:
        out[prefix] = node
    return out


def pinned_keys(rep):
    where = 'fast-time: параметры платформы закреплены (Р-175)'
    keys = parameter_keys()
    if not keys:
        return
    known = set(keys)
    profiles = sorted(glob.glob(os.path.join(SERVICES_DIR, '*', 'src', 'main', 'resources', 'application-fast-time.yml')))
    changed = 0
    for path in profiles:
        with open(path, encoding='utf-8') as f:
            data = yaml.safe_load(f) or {}
        flat = flatten(data)
        raw = flat.get('dgm.config.pinned-keys') or []
        if isinstance(raw, str):          # допустима и запись «ключ1,ключ2»
            raw = raw.split(',')
        pinned = {str(k).strip() for k in raw}
        for key in sorted(set(flat) & known):
            changed += 1
            if key not in pinned:
                rep.err(where, '%s: профиль задаёт параметр платформы «%s», но не закрепляет его в dgm.config.pinned-keys: слушатель вернул бы '
                        'значение платформы' % (L.rel(path), key))
        for key in sorted(pinned - known):
            rep.err(where, '%s: закреплён ключ «%s», которого нет среди параметров платформы' % (L.rel(path), key))
    rep.fact('профилей fast-time %d, параметров платформы в них %d, все закреплены' % (len(profiles), changed))


def applied_line(rep):
    where = 'слушатель параметров: строка журнала о применении (читает проверка стенда)'
    registry = os.path.join(KIT, 'config', 'ConfigRegistry.java')
    check = os.path.join(L.REPO, 'tools', 'stand-checks', 'parameters_checks.py')
    with open(registry, encoding='utf-8') as f:
        written = re.search(r'LOG\.info\("(Параметр \{\} = \{\} \()', f.read())
    with open(check, encoding='utf-8') as f:
        waited = re.search(r"prefix = '(Параметр %s = %s \()'", f.read())
    if not written:
        rep.err(where, '%s: нет строки LOG.info("Параметр {} = {} (было …"; проверка стенда ждёт её' % L.rel(registry))
    elif not waited:
        rep.err(where, '%s: нет префикса «Параметр %%s = %%s (», по которому проверка считает применения' % L.rel(check))
    elif written.group(1).replace('{}', '%s') != waited.group(1):
        rep.err(where, 'ConfigRegistry пишет «%s», а проверка стенда ждёт «%s»' % (written.group(1), waited.group(1)))
    else:
        rep.fact('строка о применении параметра в ConfigRegistry и в parameters_checks.py одна: «%s…»' % written.group(1))


def main():
    rep = L.Report('Каркас сервисов против документов')
    problem_types(rep)
    cols = ddl_tables(rep)
    if cols:
        sql_columns(rep, cols)
    metrics(rep)
    idempotency(rep)
    peers(rep)
    config_keys(rep)
    pinned_keys(rep)
    applied_line(rep)
    return rep.finish()


if __name__ == '__main__':
    sys.exit(main())
