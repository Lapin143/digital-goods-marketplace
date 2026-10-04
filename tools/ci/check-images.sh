#!/usr/bin/env bash
# Теги образов из versions.md (раздел 3) и базовый образ Java существуют в реестрах (versions.md, правило 6).
# Запрос идёт к реестру (docker manifest inspect), тег из описания в документе не считается доказательством.
set -uo pipefail
cd "$(dirname "$0")/../.."

images=$(python3 - <<'PY'
import sys
sys.path.insert(0, 'tools/docs-checks')
import check_compose as C
imgs = set(C.images_from_versions().values())
import re
mk = open('Makefile', encoding='utf-8').read()
m = re.search(r'^JAVA_IMAGE\s*\?=\s*(\S+)', mk, re.M)
if m:
    imgs.add(m.group(1))
# Образы инструментов проверки (versions.md, раздел 6): первый столбец таблицы «Инструмент», в нём `репозиторий:тег`
sys.path.insert(0, 'tools/docs-checks')
import docslib as L
t = L.find_table(L.read(C.VER), 'Инструмент', 'Назначение')
for r in t['rows']:
    for img in L.backticked(r[0]):
        if ':' in img and '/' in img:
            imgs.add(img)
print('\n'.join(sorted(imgs)))
PY
) || { echo "::error title=check-images::не удалось получить список образов"; exit 2; }

missing=()
for img in $images; do
  found=0
  for attempt in 1 2 3; do
    if docker manifest inspect "$img" >/dev/null 2>&1; then found=1; break; fi
    sleep 3
  done
  if [ "$found" = 1 ]; then echo "есть:     $img"; else echo "НЕТ В РЕЕСТРЕ: $img"; missing+=("$img"); fi
done

# Образы compose.yaml входят в этот перечень (check_compose.py сверяет их с versions.md)
if [ ${#missing[@]} -ne 0 ]; then
  echo "Нет тегов: ${missing[*]}"
  exit 1
fi
echo "Все теги образов найдены: $(wc -w <<<"$images")"
