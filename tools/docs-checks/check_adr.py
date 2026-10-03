# -*- coding: utf-8 -*-
"""Проверка записей архитектурных решений (ADR).

Что проверяется:
  1. Каждый файл ADR-NNN-*.md: заголовок, таблица полей (статус, дата, требования), обязательные разделы,
     не меньше двух вариантов с плюсами и минусами, плюсы и минусы в последствиях.
  2. Индекс в adr/README.md: все файлы перечислены, статус совпадает, нумерация без пропусков (001..022).
  3. Ссылки на ADR во всех документах ведут на существующий файл или на запись индекса «запланировано».
  4. Требования FT-/NFT-, названные в ADR, есть в требованиях v1.5.
  5. Связанные ADR существуют (зеркальные ссылки не требуются: ссылка идёт от решения к тому, от чего оно зависит).
"""
import glob
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import docslib as L

ADR_DIR = os.path.join(L.ARCH, 'adr')
REQUIRED_SECTIONS = ['Контекст', 'Рассмотренные варианты', 'Решение', 'Последствия', 'Когда пересмотреть']
STATUS_OK = {'Принято'}


def main():
    rep = L.Report('ADR')
    ft, nft = L.requirement_ids()
    files = sorted(glob.glob(os.path.join(ADR_DIR, 'ADR-[0-9][0-9][0-9]-*.md')))
    adr = {}
    for f in files:
        name = os.path.basename(f)
        num = name[4:7]
        text = L.read(f)
        adr[num] = dict(file=name, text=text)
        m = re.match(r'# ADR-(\d{3})\. (.+)', text)
        if not m:
            rep.err(name, 'нет заголовка «# ADR-NNN. Название»')
            continue
        if m.group(1) != num:
            rep.err(name, 'номер в заголовке %s не совпадает с номером в имени файла' % m.group(1))
        meta = L.find_table(text, 'Поле')
        fields = {}
        if not meta:
            rep.err(name, 'нет таблицы полей')
        else:
            for r in meta['rows']:
                if len(r) >= 2:
                    fields[r[0]] = r[1]
        for need in ('Статус', 'Дата', 'Требования'):
            if need not in fields or not fields[need].strip():
                rep.err(name, 'нет поля «%s»' % need)
        adr[num]['fields'] = fields
        if fields.get('Статус') and fields['Статус'] not in STATUS_OK:
            rep.err(name, 'статус «%s» не «Принято» (все ADR фазы 2 должны быть приняты)' % fields['Статус'])
        if fields.get('Дата') and not re.match(r'^\d{4}-\d{2}-\d{2}$', fields['Дата']):
            rep.err(name, 'дата «%s» не в формате ГГГГ-ММ-ДД' % fields['Дата'])
        # разделы
        titles = [t for t, _ in L.sections(text)]
        for sec in REQUIRED_SECTIONS:
            if not any(t.startswith(sec) for t in titles):
                rep.err(name, 'нет раздела «%s»' % sec)
        body = L.section(text, r'^Рассмотренные варианты')
        if body:
            tb = L.tables(body)
            if not tb:
                rep.err(name, 'в «Рассмотренных вариантах» нет таблицы')
            elif tb[0]['header'][:3] == ['Вариант', 'Плюсы', 'Минусы']:
                if len(tb[0]['rows']) < 2:
                    rep.err(name, 'в «Рассмотренных вариантах» меньше двух вариантов')
                for r in tb[0]['rows']:
                    if len(r) < 3 or not r[1].strip() or not r[2].strip():
                        rep.err(name, 'у варианта «%s» пустые плюсы или минусы' % (r[0][:40] if r else '?'))
            elif len(tb[0]['header']) >= 3 and tb[0]['header'][0] == 'Критерий':
                # матрица: критерии по строкам, варианты по столбцам (не меньше двух вариантов)
                for r in tb[0]['rows']:
                    if len(r) != len(tb[0]['header']) or any(not c.strip() for c in r):
                        rep.err(name, 'в матрице вариантов пустая ячейка в строке «%s»' % (r[0][:40] if r else '?'))
            else:
                rep.err(name, 'таблица вариантов: «Вариант | Плюсы | Минусы» или матрица «Критерий | Вариант 1 | Вариант 2 …»')
        cons = L.section(text, r'^Последствия')
        if cons is not None:
            if '**Плюсы:**' not in cons or '**Минусы и риски:**' not in cons:
                rep.err(name, 'в последствиях нет «Плюсы» и «Минусы и риски»')
        dec = L.section(text, r'^Решение')
        if dec is not None and 'Выбран' not in dec and 'выбран' not in dec:
            rep.err(name, 'в разделе «Решение» не названо, что выбрано')
        rv = L.section(text, r'^Когда пересмотреть')
        if rv is not None and len(rv.strip()) < 20:
            rep.err(name, 'пустое условие пересмотра')
        # требования
        for rid in re.findall(r'\b(FT-\d+\.\d+)\b', fields.get('Требования', '')):
            if rid not in ft:
                rep.err(name, 'требование %s не найдено в requirements_v1.5.md' % rid)
        for rid in re.findall(r'\b(NFT-\d+\.\d+)\b', fields.get('Требования', '')):
            if rid not in nft:
                rep.err(name, 'требование %s не найдено в requirements_v1.5.md' % rid)

    # ----- индекс
    idx_text = L.read(os.path.join(ADR_DIR, 'README.md'))
    idx = L.find_table(idx_text, 'ADR', 'Статус')
    planned = set()
    index_nums = []
    if not idx:
        rep.err('adr/README.md', 'нет таблицы индекса')
    else:
        for r in idx['rows']:
            m = re.match(r'^\[?(\d{3})\]?', r[0])
            if not m:
                rep.err('adr/README.md', 'строка индекса без номера: %s' % r[0])
                continue
            num = m.group(1)
            index_nums.append(num)
            link = re.search(r'\]\(([^)]+)\)', r[0])
            if link:
                target = link.group(1)
                if not os.path.exists(os.path.join(ADR_DIR, target)):
                    rep.err('adr/README.md', 'ссылка %s ведёт на несуществующий файл' % target)
                if num not in adr:
                    rep.err('adr/README.md', 'ADR-%s есть в индексе, но нет файла' % num)
                else:
                    if adr[num]['file'] != target:
                        rep.err('adr/README.md', 'ADR-%s: ссылка %s, а файл %s' % (num, target, adr[num]['file']))
                    st = adr[num].get('fields', {}).get('Статус')
                    if st and st != r[2]:
                        rep.err('adr/README.md', 'ADR-%s: статус в индексе «%s», в файле «%s»' % (num, r[2], st))
                    title = re.match(r'# ADR-\d{3}\. (.+)', adr[num]['text'])
                    if title and title.group(1).strip() != r[1].strip():
                        rep.err('adr/README.md', 'ADR-%s: тема в индексе «%s» не совпадает с заголовком «%s»'
                                % (num, r[1], title.group(1).strip()))
            else:
                if num != '001' and not r[2].startswith('Запланировано'):
                    rep.err('adr/README.md', 'ADR-%s без ссылки, но статус «%s»' % (num, r[2]))
                planned.add(num)
        if index_nums != ['%03d' % i for i in range(1, 23)]:
            rep.err('adr/README.md', 'нумерация индекса не 001..022 подряд: %s' % index_nums)
    for num in adr:
        if num not in index_nums:
            rep.err('adr/README.md', 'файл ADR-%s не внесён в индекс' % num)

    # ----- ссылки на ADR по всем документам
    known = set(adr) | planned
    total_refs = 0
    for f in glob.glob(os.path.join(L.DOCS, '**', '*.md'), recursive=True):
        text = L.read(f)
        for m in re.finditer(r'ADR-(\d{3})', text):
            total_refs += 1
            if m.group(1) not in known:
                rep.err(L.rel(f), 'ссылка на несуществующий ADR-%s' % m.group(1))

    rep.fact('ADR: %d файлов, %d записей в индексе (запланировано: %d), ссылок на ADR в документах: %d'
             % (len(adr), len(index_nums), len(planned), total_refs))
    return rep.finish()


if __name__ == '__main__':
    sys.exit(main())
