# -*- coding: utf-8 -*-
"""Проверка user stories: покрытие FT, структура, ссылки. Запуск: python3 check_us.py"""
import glob, os, re, sys

D = os.path.join(os.environ.get('REPO') or os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..')), 'docs')
req = open(D + '/02-requirements/requirements_v1.5.md', encoding='utf-8').read()

ft_rel = {}
for m in re.finditer(r'^\|\s*(FT-\d+\.\d+)\s*\|.*\|\s*([^|]+?)\s*\|\s*$', req, re.M):
    ft_rel[m.group(1)] = m.group(2)
NFT = set(re.findall(r'^\|\s*(NFT-\d+\.\d+)\s*\|', req, re.M))
BG = set(re.findall(r'^\|\s*(BG-\d+)\s*\|', req, re.M))

# модели и процессы
sm = {}
for f in glob.glob(D + '/03-processes/SM-*.md'):
    t = open(f, encoding='utf-8').read()
    n = re.search(r'^# (SM-\d+)\.', t, re.M).group(1)
    sm[n] = set(re.findall(r'^\| (T\d+) \|', t, re.M))
bp = {}
for f in glob.glob(D + '/03-processes/BPMN-0*.md'):
    t = open(f, encoding='utf-8').read()
    n = re.search(r'^# (BPMN-\d+)\.', t, re.M).group(1)
    bp[n] = set(re.findall(r'^\| (E\d+) \|', t, re.M))

ROLES = ['гость', 'покупатель', 'продавец', 'модератор', 'оператор поддержки', 'администратор', 'система']
problems = []


def err(f, s, msg):
    problems.append('%s %s: %s' % (os.path.basename(f), s, msg))


stories = {}  # id -> dict
files = sorted(glob.glob(D + '/02-requirements/US-[0-9][0-9]-*.md'))
for f in files:
    t = open(f, encoding='utf-8').read()
    epic = int(re.search(r'US-(\d+)-', os.path.basename(f)).group(1))
    m = re.search(r'^# Эпик (\d+)\.', t, re.M)
    if not m or int(m.group(1)) != epic:
        err(f, '', 'номер эпика в заголовке не совпадает с именем файла')
    parts = re.split(r'^## (US-\d+\.\d+)\. ', t, flags=re.M)
    head = parts[0]
    listed = re.findall(r'^\| (US-\d+\.\d+) \| (.+?) \| (R[123][^|]*?) \| (.+?) \|$', head, re.M)
    sects = []
    for i in range(1, len(parts), 2):
        sects.append((parts[i], parts[i + 1]))
    ids = [s[0] for s in sects]
    exp = ['US-%d.%d' % (epic, k) for k in range(1, len(ids) + 1)]
    if ids != exp:
        err(f, '', 'нумерация историй %s вместо %s' % (ids, exp))
    if [l[0] for l in listed] != ids:
        err(f, '', 'список историй вверху не совпадает с заголовками: %s' % [l[0] for l in listed])
    lst = {l[0]: l for l in listed}
    for sid, body in sects:
        title = body.split('\n', 1)[0].strip()
        fields = dict(re.findall(r'^\| (ID|Релиз|Требования|Процесс или use case) \| (.+?) \|$', body, re.M))
        for k in ('ID', 'Релиз', 'Требования', 'Процесс или use case'):
            if k not in fields:
                err(f, sid, 'нет поля ' + k)
        if fields.get('ID') != sid:
            err(f, sid, 'ID в таблице не совпадает с заголовком')
        rel = fields.get('Релиз', '')
        reqs = fields.get('Требования', '')
        ft = set(re.findall(r'(?<!N)\bFT-\d+\.\d+\b', reqs))
        nft = set(re.findall(r'\bNFT-\d+\.\d+\b', reqs))
        bg = set(re.findall(r'\bBG-\d+\b', reqs))
        for x in ft:
            if x not in ft_rel:
                err(f, sid, 'нет требования ' + x)
        for x in nft:
            if x not in NFT:
                err(f, sid, 'нет требования ' + x)
        for x in bg:
            if x not in BG:
                err(f, sid, 'нет цели ' + x)
        if not ft and not nft:
            err(f, sid, 'нет ни одного FT или NFT в требованиях')
        # список вверху
        if sid in lst:
            l = lst[sid]
            if l[2].strip() != rel.strip():
                err(f, sid, 'релиз в списке «%s» и в карточке «%s» различаются' % (l[2], rel))
            lft = set(re.findall(r'(?<!N)\bFT-\d+\.\d+\b', l[3])) | set(re.findall(r'\bNFT-\d+\.\d+\b', l[3]))
            if not lft <= (ft | nft):
                err(f, sid, 'в списке есть требования, которых нет в карточке: %s' % sorted(lft - ft - nft))
        # история
        hist = re.search(r'### История\n\n(.+?)\n\n', body, re.S)
        if not hist or not re.match(r'\*\*Как\*\* .+, \*\*я хочу\*\* .+, \*\*чтобы\*\* .+\.$', hist.group(1)):
            err(f, sid, 'история не в формате «Как..., я хочу..., чтобы...»')
        else:
            who = re.match(r'\*\*Как\*\* (.+?), \*\*я хочу\*\*', hist.group(1)).group(1).lower()
            if not any(r in who for r in ROLES):
                err(f, sid, 'роль «%s» не из раздела 3' % who)
        # сценарии
        scs = re.findall(r'^\*\*Сценарий (\d+)\. (.+?)\*\*\n((?:- .+\n?)+)', body, re.M)
        n_expected = len(re.findall(r'^\*\*Сценарий ', body, re.M))
        if len(scs) != n_expected:
            err(f, sid, 'не все сценарии разобраны (%d из %d)' % (len(scs), n_expected))
        nums = [int(s[0]) for s in scs]
        if nums != list(range(1, len(scs) + 1)):
            err(f, sid, 'нумерация сценариев %s' % nums)
        for num, name, lines in scs:
            kinds = re.findall(r'^- \*\*(Дано|Когда|Тогда|И)\*\*', lines, re.M)
            if kinds[:3] != ['Дано', 'Когда', 'Тогда']:
                err(f, sid, 'сценарий %s: нет порядка Дано, Когда, Тогда' % num)
            if kinds.count('Дано') != 1 or kinds.count('Когда') != 1 or kinds.count('Тогда') != 1:
                err(f, sid, 'сценарий %s: Дано, Когда и Тогда должны быть по одному' % num)
        isR1 = 'R1' in rel
        if isR1 and len(scs) < 2:
            err(f, sid, 'в R1 нужно минимум 2 сценария')
        if not isR1 and len(scs) < 1:
            err(f, sid, 'нужен хотя бы один сценарий')
        # ссылки на процессы
        proc = fields.get('Процесс или use case', '')
        for m in re.finditer(r'(BPMN-\d+)\s*\(([^)]*)\)', proc):
            if m.group(1) not in bp:
                err(f, sid, 'нет процесса ' + m.group(1))
                continue
            for e in re.findall(r'\bE\d+\b', m.group(2)):
                if e not in bp[m.group(1)]:
                    err(f, sid, 'нет исключения %s в %s' % (e, m.group(1)))
        for m in re.finditer(r'\bBPMN-\d+\b', proc):
            if m.group(0) not in bp:
                err(f, sid, 'нет процесса ' + m.group(0))
        for m in re.finditer(r'\bUC-(\d+)\b', proc):
            if not 1 <= int(m.group(1)) <= 5:
                err(f, sid, 'нет use case ' + m.group(0))
        stories[sid] = dict(rel=rel, ft=ft, nft=nft, bg=bg, title=title, file=os.path.basename(f), scs=len(scs))

# перекрёстные ссылки US-X.Y и SM-NN/Tn во всех US
for f in files:
    t = open(f, encoding='utf-8').read()
    for m in re.finditer(r'\bUS-(\d+\.\d+)\b', t):
        if 'US-' + m.group(1) not in stories:
            err(f, '', 'ссылка на несуществующую историю US-' + m.group(1))
    for m in re.finditer(r'\b(SM-\d+)/(T\d+)\b', t):
        if m.group(2) not in sm.get(m.group(1), set()):
            err(f, '', 'нет перехода ' + m.group(0))
    for m in re.finditer(r'\bSM-(\d+)\b', t):
        if 'SM-' + m.group(1) not in sm:
            err(f, '', 'нет статусной модели SM-' + m.group(1))
    for m in re.finditer(r'\bUC-(\d+)\b', t):
        if not 1 <= int(m.group(1)) <= 5:
            err(f, '', 'нет use case ' + m.group(0))

# покрытие
cov = {}
for sid, s in stories.items():
    for x in s['ft']:
        cov.setdefault(x, []).append(sid)
uncovered_r1 = []
uncovered_other = []
for x, rel in sorted(ft_rel.items(), key=lambda kv: [int(p) for p in kv[0][3:].split('.')]):
    r1 = 'R1' in rel
    users = cov.get(x, [])
    if r1:
        has_r1_story = any('R1' in stories[u]['rel'] for u in users)
        if not has_r1_story:
            uncovered_r1.append((x, rel))
    elif not users:
        uncovered_other.append((x, rel))
print('историй всего:', len(stories), ' R1:', sum('R1' in s['rel'] for s in stories.values()),
      ' R2:', sum(s['rel'].startswith('R2') for s in stories.values()),
      ' R3:', sum(s['rel'].startswith('R3') for s in stories.values()))
print('FT всего:', len(ft_rel), ' R1:', sum('R1' in r for r in ft_rel.values()))
print('R1-требования без R1-истории:', uncovered_r1)
print('R2/R3-требования без истории:', uncovered_other)
used_nft = set()
for s in stories.values():
    used_nft |= s['nft']
print('NFT без истории:', sorted(NFT - used_nft))
print('истории без сценариев исключения не проверяются автоматически; сценариев всего:', sum(s['scs'] for s in stories.values()))
for p in problems:
    print('  -', p)
print('проблем:', len(problems) + len(uncovered_r1) + len(uncovered_other))
sys.exit(1 if (problems or uncovered_r1 or uncovered_other) else 0)
