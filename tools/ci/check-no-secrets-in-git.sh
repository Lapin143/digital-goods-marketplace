#!/usr/bin/env bash
# ST-14: в Git не должно быть ключей, сертификатов и файлов секретов (они создаются при запуске: make certs secrets).
set -uo pipefail
cd "$(dirname "$(readlink -f "$0")")/../.."
bad=$(git ls-files | grep -E '(^|/)(secrets|\.pki)/|\.(key|pem|p12|pfx|jks|keystore|crt|cer)$' || true)
if [ -n "$bad" ]; then
  echo "В репозитории отслеживаются файлы, похожие на ключи и секреты:"
  echo "$bad"
  exit 1
fi
echo "ок: в Git нет файлов ключей, сертификатов и каталогов secrets/ и .pki/"
