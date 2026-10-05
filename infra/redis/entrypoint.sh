#!/bin/sh
# Запуск Redis под пользователем redis (UID 999) с файловой системой только для чтения.
# Собирает файл пользователей ACL из секретов: в файле хранятся только SHA-256 паролей, не сами пароли.
#
# Права по сервисам (ADR-022). Ключи разделены префиксами, чужой префикс сервису недоступен:
#   gateway    request_rate_limiter.* (ограничение частоты, имена задаёт Spring Cloud Gateway), apikey:* (R2)
#   catalog    catalog:*              (кэш карточек и поиска)
#   inventory  reservation:*          (таймеры резервов, ADR-012)
#   platform   platform:*             (одноразовые коды и счётчики, ADR-010)
#   admin      всё                    (проверка готовности и администрирование, только в контейнере redis)
# Команды сервисов: чтение, запись и скрипты Lua. Опасные команды (FLUSHALL, KEYS, CONFIG, DEBUG и прочие) запрещены.
# Шлюзу дополнительно разрешена TIME: скрипт Lua ограничителя частоты Spring Cloud Gateway читает время сервера Redis; без неё скрипт
# отвечает NOPERM на каждый запрос, а шлюз при этом молча пропускает запросы без лимита (отказ открытым).
set -eu

D=/run/dgm
S=/run/secrets
ACL="$D/users.acl"

hash() { printf '%s' "$(cat "$S/$1")" | sha256sum | cut -d' ' -f1; }

# Минимум команд, без которых клиент не подключится (Lettuce шлёт HELLO и CLIENT SETINFO)
BASE="-@all +@read +@write +@scripting -@dangerous +hello +ping +echo +auth +reset +select +client|setinfo +client|setname +client|id"

user_line() {  # имя секрет шаблоны-ключей [дополнительные-команды]
  printf 'user %s on #%s %s resetchannels %s %s\n' "$1" "$(hash "$2")" "$3" "$BASE" "${4:-}"
}

{
  echo "user default off"
  printf 'user admin on #%s ~* &* +@all\n' "$(hash redis_admin)"
  user_line gateway   redis_gateway   "~request_rate_limiter.* ~apikey:*" "+time"
  user_line catalog   redis_catalog   "~catalog:*"
  user_line inventory redis_inventory "~reservation:*"
  user_line platform  redis_platform  "~platform:*"
} > "$ACL"

exec redis-server /etc/dgm/redis/redis.conf
