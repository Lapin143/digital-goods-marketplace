# -*- coding: utf-8 -*-
"""Проверка use cases: поля, ссылки, симметрия с user stories. Запуск: python3 check_uc.py"""
import glob, os, re, sys

D = os.path.join(os.environ.get('REPO') or os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..')), 'docs')
RQ = D + '/02-requirements'
req = open(RQ + '/requirements_v1.6.md', encoding='utf-8').read()
FT = set(re.findall(r'^\|\s*(FT-\d+\.\d+)\s*\|', req, re.M))
NFT = set(re.findall(r'^\|\s*(NFT-\d+\.\d+)\s*\|', req, re.M))
BG = set(re.findall(r'^\|\s*(BG-\d+)\s*\|', req, re.M))

sm = {}
sm_file = {}
for f in glob.glob(D + '/03-processes/SM-*.md'):
    t = open(f, encoding='utf-8').read()
    n = re.search(r'^# (SM-\d+)\.', t, re.M).group(1)
    sm[n] = set(re.findall(r'^\| (T\d+) \|', t, re.M))
    sm_file[n] = os.path.basename(f)
bp = {}
bp_file = {}
for f in glob.glob(D + '/03-processes/BPMN-0*.md'):
    t = open(f, encoding='utf-8').read()
    n = re.search(r'^# (BPMN-\d+)\.', t, re.M).group(1)
    bp[n] = set(re.findall(r'^\| (E\d+) \|', t, re.M))
    bp_file[n] = os.path.basename(f)

# user stories
us = {}
us_file = {}
for f in sorted(glob.glob(RQ + '/US-[0-9][0-9]-*.md')):
    t = open(f, encoding='utf-8').read()
    parts = re.split(r'^## (US-\d+\.\d+)\. ', t, flags=re.M)
    for i in range(1, len(parts), 2):
        sid, body = parts[i], parts[i + 1]
        fields = dict(re.findall(r'^\| (Релиз|Требования|Процесс или use case) \| (.+?) \|$', body, re.M))
        us[sid] = dict(
            rel=fields.get('Релиз', ''),
            reqs=set(re.findall(r'\b(?:N?FT-\d+\.\d+)\b', fields.get('Требования', ''))),
            ucs=set(re.findall(r'\bUC-\d+\b', fields.get('Процесс или use case', ''))),
            proc=fields.get('Процесс или use case', ''))
        us_file[sid] = os.path.basename(f)

problems = []
warns = []


def err(f, msg):
    problems.append('%s: %s' % (f, msg))


ucs = {}
for f in sorted(glob.glob(RQ + '/UC-[0-9][0-9]-*.md')):
    base = os.path.basename(f)
    t = open(f, encoding='utf-8').read()
    m = re.match(r'# (UC-\d+)\. (.+)\n', t)
    if not m:
        err(base, 'нет заголовка «# UC-NN. Название»')
        continue
    uid = m.group(1)
    if not base.startswith(uid + '-'):
        err(base, 'имя файла не совпадает с ID')
    # таблица шапки
    head = t.split('\n## ', 1)[0]
    fields = dict(re.findall(r'^\| (.+?) \| (.+?) \|$', head, re.M))
    need = ['ID', 'Актор', 'Цель', 'Предусловия', 'Постусловия', 'Триггер', 'Требования', 'Процесс', 'Статусные модели', 'User stories']
    for k in need:
        if k not in fields:
            err(base, 'нет поля «%s»' % k)
    if fields.get('ID') != uid:
        err(base, 'ID в таблице не совпадает с заголовком')
    # разделы
    secs = re.findall(r'^## (.+)$', t, re.M)
    exp = ['Основной сценарий', 'Альтернативные сценарии', 'Исключения', 'Нефункциональные ограничения']
    if secs != exp:
        err(base, 'разделы %s вместо %s' % (secs, exp))
    # требования
    reqs = set(re.findall(r'\b(?:N?FT-\d+\.\d+)\b', fields.get('Требования', '')))
    for x in reqs:
        if x not in FT and x not in NFT:
            err(base, 'нет требования ' + x)
    # весь текст: FT/NFT/BG существуют и входят в шапку
    for x in set(re.findall(r'\bN?FT-\d+\.\d+\b', t)):
        if x not in FT and x not in NFT:
            err(base, 'в тексте нет требования ' + x)
        elif x not in reqs:
            err(base, 'требование %s упомянуто в тексте, но нет в поле «Требования»' % x)
    for x in set(re.findall(r'\bBG-\d+\b', t)):
        if x not in BG:
            err(base, 'нет цели ' + x)
    for x in reqs:
        body_wo_head = t[len(head):]
        if x not in body_wo_head:
            warns.append('%s: требование %s в шапке, но не использовано в сценариях' % (base, x))
    # процесс: ссылка и E
    proc = fields.get('Процесс', '')
    pm = re.search(r'\[(BPMN-\d+)\]\(\.\./03-processes/(BPMN-[^)]+\.md)\)', proc)
    if not pm:
        err(base, 'нет ссылки на BPMN в поле «Процесс»')
    else:
        if pm.group(1) not in bp or bp_file[pm.group(1)] != pm.group(2):
            err(base, 'ссылка на процесс неверна: ' + pm.group(0))
        if not os.path.exists(os.path.join(D, '03-processes', pm.group(2))):
            err(base, 'нет файла ' + pm.group(2))
    # E-номера всех BPMN из текста
    bps_in_head = re.findall(r'BPMN-\d+', proc)
    for e in re.findall(r'\bE(\d+)\b', proc):
        if not any('E' + e in bp.get(b, set()) for b in bps_in_head):
            err(base, 'исключение E%s не существует в %s' % (e, bps_in_head))
    for mm in re.finditer(r'\bE(\d+) – E(\d+)\b', proc):
        for k in range(int(mm.group(1)), int(mm.group(2)) + 1):
            if not any('E%d' % k in bp.get(b, set()) for b in bps_in_head):
                err(base, 'в диапазоне нет E%d' % k)
    # статусные модели: ссылки
    smf = fields.get('Статусные модели', '')
    for mm in re.finditer(r'\[(SM-\d+)\]\(\.\./03-processes/(SM-[^)]+\.md)\)', smf):
        if mm.group(1) not in sm or sm_file[mm.group(1)] != mm.group(2):
            err(base, 'неверная ссылка ' + mm.group(0))
    linked_sm = set(re.findall(r'\[(SM-\d+)\]', smf))
    # SM в тексте: существуют и перечислены в шапке
    for mm in re.finditer(r'\b(SM-\d+)/(T\d+)\b', t):
        if mm.group(2) not in sm.get(mm.group(1), set()):
            err(base, 'нет перехода ' + mm.group(0))
    for x in set(re.findall(r'\bSM-\d+\b', t)):
        if x not in sm:
            err(base, 'нет статусной модели ' + x)
        elif x not in linked_sm:
            err(base, '%s упомянута в тексте, но нет в поле «Статусные модели»' % x)
    for x in linked_sm:
        if x not in set(re.findall(r'\bSM-\d+\b', t[len(head):])):
            warns.append('%s: %s в шапке, но не использована в сценариях' % (base, x))
    # user stories
    usf = fields.get('User stories', '')
    linked = re.findall(r'\[(US-\d+\.\d+)\]\(([^)]+)\)', usf)
    ids = [x[0] for x in linked]
    if len(ids) != len(set(ids)):
        err(base, 'повтор историй в поле «User stories»')
    for sid, link in linked:
        if sid not in us:
            err(base, 'нет истории ' + sid)
        elif us_file[sid] != link:
            err(base, 'ссылка %s ведёт на %s вместо %s' % (sid, link, us_file[sid]))
    for x in set(re.findall(r'\bUS-\d+\.\d+\b', t)):
        if x not in us:
            err(base, 'нет истории ' + x)
    # симметрия: истории, которые называют этот UC
    back = {sid for sid, s in us.items() if uid in s['ucs']}
    if set(ids) != back:
        err(base, 'симметрия с US: в UC %s, а в US указывают на UC %s' % (sorted(set(ids) - back), sorted(back - set(ids))))
    # требования UC покрыты историями UC
    cov = set()
    for sid in ids:
        if sid in us:
            cov |= us[sid]['reqs']
    miss = {x for x in reqs if x not in cov}
    if miss:
        warns.append('%s: требования UC, которых нет в связанных историях: %s' % (base, sorted(miss)))
    # шаги основного сценария
    sec = re.search(r'## Основной сценарий\n\n(.*?)\n## ', t, re.S).group(1)
    steps = re.findall(r'^(\d+)\. ', sec, re.M)
    if [int(s) for s in steps] != list(range(1, len(steps) + 1)):
        err(base, 'нумерация основного сценария %s' % steps)
    n = len(steps)
    alt = re.search(r'## Альтернативные сценарии\n\n(.*?)\n## ', t, re.S).group(1)
    exc = re.search(r'## Исключения\n\n(.*?)\n## ', t, re.S).group(1)
    for name, block in (('альтернативы', alt), ('исключения', exc)):
        labels = re.findall(r'^- \*\*(\d+)([a-z])\.\*\*', block, re.M)
        if not labels:
            err(base, 'нет пунктов в разделе «%s»' % name)
        seen = set()
        for num, let in labels:
            if int(num) > n:
                err(base, '%s %s%s ссылается на шаг, которого нет (шагов %d)' % (name, num, let, n))
            if (num, let) in seen:
                err(base, 'повтор метки %s%s' % (num, let))
            seen.add((num, let))
        cnt = len(re.findall(r'^- \*\*', block, re.M))
        if cnt != len(labels):
            err(base, 'в разделе «%s» есть пункты без метки шага' % name)
    # таблица НФ-ограничений
    nf = t.split('## Нефункциональные ограничения', 1)[1]
    rows = re.findall(r'^\| (?!Ограничение|---)(.+?) \| (.+?) \| (.+?) \|$', nf, re.M)
    if not rows:
        err(base, 'пустая таблица нефункциональных ограничений')
    for a, b, c in rows:
        for x in re.findall(r'\b(?:N?FT-\d+\.\d+|BG-\d+)\b', c):
            if x not in FT and x not in NFT and x not in BG:
                err(base, 'в таблице НФ нет требования ' + x)
    ucs[uid] = dict(file=base, stories=ids, steps=n, title=m.group(2))

# ссылки на UC в US ведут на существующие файлы
for sid, s in us.items():
    for x in s['ucs']:
        if x not in ucs:
            err(us_file[sid], '%s: нет use case %s' % (sid, x))
    # если ссылка уже превращена в Markdown-ссылку, путь должен быть верным
    for mm in re.finditer(r'\[(UC-\d+)\]\(([^)]+)\)', s['proc']):
        if mm.group(1) in ucs and ucs[mm.group(1)]['file'] != mm.group(2):
            err(us_file[sid], '%s: ссылка %s ведёт на %s' % (sid, mm.group(1), mm.group(2)))

# R1-истории, связанные с BPMN-01, 02, 04, должны иметь UC? Информационно.
no_uc = [sid for sid, s in us.items() if 'R1' in s['rel'] and not s['ucs'] and re.search(r'BPMN-0[124]', s['proc'])]
if no_uc:
    warns.append('R1-истории по процессам BPMN-01, 02, 04 без use case: %s' % no_uc)

print('use cases:', len(ucs))
for k, v in sorted(ucs.items()):
    print(' ', k, v['title'], '| шагов', v['steps'], '| историй', len(v['stories']))
print('предупреждения:')
for w in warns:
    print('  ~', w)
print('ошибки:')
for p in problems:
    print('  -', p)
print('проблем:', len(problems))
sys.exit(1 if problems else 0)
