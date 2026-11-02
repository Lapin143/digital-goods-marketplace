# -*- coding: utf-8 -*-
"""Собирает docs/07-data/ddl/<service>.sql из общего блока infra.sql, части сервиса (R1) и необязательной части R2 (parts/<часть>.r2.sql)."""
import os
import sys

REPO = os.environ.get('REPO') or os.path.abspath(os.path.join(os.path.dirname(os.path.realpath(__file__)), '..', '..', '..'))
PARTS = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'parts')
SERVICES = [
    ('catalog-service', 'catalog_db', 'catalog', 'модули seller_onboarding и catalog'),
    ('inventory-service', 'inventory_db', 'inventory', 'модуль inventory'),
    ('order-service', 'order_db', 'order', 'модуль orders'),
    ('payment-service', 'payment_db', 'payment', 'модуль payments'),
    ('delivery-service', 'delivery_db', 'delivery', 'модуль delivery'),
    ('platform-service', 'platform_db', 'platform', 'модули identity, support, notification, audit_admin'),
]

HEADER = """-- ============================================================================
-- {service}: база {db} ({modules})
-- Физическая модель данных R1 и R2, PostgreSQL 16. Файл собран из общего служебного блока, части R1 и блоков R2 (их разделы начинаются со слов «R2, <схема>»).
-- Выполняется с нуля одним проходом: psql -v ON_ERROR_STOP=1 -d {db} -f {service}.sql
-- Нужны права создания схем, ролей и расширений. В проекте файл разбит на миграции Flyway по схемам (decomposition.md, правило 7),
-- здесь они сведены вместе, чтобы модель читалась и проверялась целиком.
-- Описание: docs/07-data/{service}-schema.md
-- ============================================================================

"""


def read_r2(part):
    """Текст части R2 сервиса или None, если у сервиса нет изменений R2."""
    path = os.path.join(PARTS, part + '.r2.sql')
    return open(path, encoding='utf-8').read() if os.path.exists(path) else None


def body_of(infra, part):
    """Тело документа DDL без шапки: служебный блок, часть R1 и, если есть, часть R2. Одна функция для сборки и для проверок."""
    text = infra.rstrip() + '\n\n' + open(os.path.join(PARTS, part + '.sql'), encoding='utf-8').read().rstrip()
    r2 = read_r2(part)
    if r2 is not None:
        text += '\n\n' + r2.rstrip()
    return text + '\n'


def build():
    infra = open(os.path.join(PARTS, 'infra.sql'), encoding='utf-8').read()
    out_dir = os.path.join(REPO, 'docs', '07-data', 'ddl')
    os.makedirs(out_dir, exist_ok=True)
    for service, db, part, modules in SERVICES:
        text = HEADER.format(service=service, db=db, modules=modules) + body_of(infra, part)
        open(os.path.join(out_dir, service + '.sql'), 'w', encoding='utf-8').write(text)
        print('собрано', service + '.sql', len(text.splitlines()), 'строк')


if __name__ == '__main__':
    build()
