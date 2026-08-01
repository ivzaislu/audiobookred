#!/usr/bin/env bash
set -Eeuo pipefail

REPOSITORY="https://github.com/ivzaislu/audiobookred.git"
BRANCH="main"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
START=true
INSTALL_CRON=true
REPLACE_CRON=false
BUILD=true

usage() {
  cat <<'TXT'
Установка AudioBookRed из текущего checkout.

Использование:
  sudo bash install.sh [параметры]

Параметры:
  --no-start       установить конфигурацию и CLI, но не запускать контейнеры
  --no-cron        не устанавливать cron и logrotate
  --replace-cron   заменить существующий /etc/cron.d/audiobookred шаблоном
  --skip-build     запустить существующий образ без пересборки
  -h, --help       показать справку
TXT
}

for arg in "$@"; do
  case "$arg" in
    --no-start) START=false ;;
    --no-cron) INSTALL_CRON=false ;;
    --replace-cron) REPLACE_CRON=true ;;
    --skip-build) BUILD=false ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Неизвестный параметр: $arg" >&2; usage >&2; exit 2 ;;
  esac
done

fail() {
  echo "Ошибка: $*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "не найдена команда $1"
}

generate_secret() {
  local bytes="$1"
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -hex "$bytes"
  else
    head -c "$bytes" /dev/urandom | sha256sum | awk '{print $1}'
  fi
}

read_env_value() {
  local key="$1"
  sed -n "s/^${key}=//p" "$ROOT/.env" | tail -n 1 | tr -d '\r\n'
}

[[ $EUID -eq 0 ]] || fail "запустите скрипт от root"
require_command docker
require_command curl
require_command python3
require_command awk
require_command sed
require_command sha256sum
docker compose version >/dev/null 2>&1 || fail "требуется Docker Compose v2"

cd "$ROOT"
[[ -f docker-compose.yml ]] || fail "не найден $ROOT/docker-compose.yml"
[[ -f scripts/audiobookred-source ]] || fail "не найден scripts/audiobookred-source"

# Шаблон нужен только для новой установки. Существующий рабочий .env
# не должен блокироваться из-за отсутствующего .env.example.
if [[ ! -f .env ]]; then
  [[ -f .env.example ]] || fail "не найден $ROOT/.env.example; обновите checkout из $REPOSITORY"
fi

chmod +x install.sh backup-db.sh restore-db.sh update.sh uninstall.sh doctor.sh \
  scripts/audiobookred-source scripts/install-from-github.sh scripts/migrate-existing-install.sh

if [[ -d .git ]]; then
  origin="$(git remote get-url origin 2>/dev/null || true)"
  if [[ -n "$origin" && "$origin" != "$REPOSITORY" && "$origin" != "git@github.com:ivzaislu/audiobookred.git" ]]; then
    echo "Предупреждение: origin указывает на $origin, ожидается $REPOSITORY" >&2
  fi
fi

if [[ ! -f .env ]]; then
  umask 077
  cp .env.example .env
  api_key="$(generate_secret 32)"
  db_password="$(generate_secret 24)"
  sed -i "s/^API_KEY=.*/API_KEY=$api_key/" .env
  sed -i "s/^DB_PASSWORD=.*/DB_PASSWORD=$db_password/" .env
  chmod 600 .env
  echo "Создан $ROOT/.env с новыми API_KEY и DB_PASSWORD."
  echo "Перед первым импортом заполните настройки RuTracker в .env."
else
  chmod 600 .env
fi

api_key="$(read_env_value API_KEY)"
db_password="$(read_env_value DB_PASSWORD)"
port="$(read_env_value AUDIOBOOKRED_PORT)"
port="${port:-9117}"

[[ -n "$api_key" && "$api_key" != "change-me" ]] || fail "задайте безопасный API_KEY в $ROOT/.env"
[[ -n "$db_password" && "$db_password" != "change-me" ]] || fail "задайте безопасный DB_PASSWORD в $ROOT/.env"
[[ "$port" =~ ^[0-9]+$ ]] && (( port >= 1 && port <= 65535 )) || fail "AUDIOBOOKRED_PORT должен быть числом 1..65535"

free_kb="$(df -Pk "$ROOT" | awk 'NR==2 {print $4}')"
if [[ "$free_kb" =~ ^[0-9]+$ ]]; then
  if (( free_kb < 1048576 )); then
    fail "свободно меньше 1 ГБ; очистите старый Docker build cache перед сборкой"
  elif (( free_kb < 3145728 )); then
    echo "Предупреждение: свободно меньше 3 ГБ. Перед сборкой рекомендуется: docker builder prune -a -f" >&2
  fi
fi

docker compose --env-file .env config >/dev/null

{
  printf 'AUDIOBOOKRED_ROOT=%q\n' "$ROOT"
  printf 'AUDIOBOOKRED_URL=%q\n' "http://127.0.0.1:$port"
  printf 'AUDIOBOOKRED_REPOSITORY=%q\n' "$REPOSITORY"
  printf 'AUDIOBOOKRED_BRANCH=%q\n' "$BRANCH"
} > /etc/default/audiobookred
chmod 644 /etc/default/audiobookred
install -m 0755 scripts/audiobookred-source /usr/local/sbin/audiobookred-source

if $INSTALL_CRON; then
  if [[ ! -f /etc/cron.d/audiobookred ]] || $REPLACE_CRON; then
    install -m 0644 cron/audiobookred.cron.example /etc/cron.d/audiobookred
    echo "Установлен /etc/cron.d/audiobookred"
  else
    echo "Существующий /etc/cron.d/audiobookred сохранён. Для замены используйте --replace-cron."
  fi
  install -m 0644 cron/audiobookred.logrotate.example /etc/logrotate.d/audiobookred
fi

for file in \
  /var/log/audiobookred-rutracker-worker.log \
  /var/log/audiobookred-rutracker-latest.log \
  /var/log/audiobookred-rutracker-retry.log \
  /var/log/audiobookred-maintenance.log; do
  touch "$file"
  chmod 640 "$file"
done

if ! $START; then
  echo "Системная интеграция AudioBookRed установлена. Контейнеры не запускались."
  exit 0
fi

if $BUILD; then
  docker compose --env-file .env pull db
  docker compose --env-file .env build --pull api
fi

docker compose --env-file .env up -d --remove-orphans

echo "Ожидание API на порту $port..."
health_file="$(mktemp)"
trap 'rm -f "$health_file"' EXIT
for _ in $(seq 1 90); do
  if curl -fsS "http://127.0.0.1:$port/health" >"$health_file" 2>/dev/null; then
    python3 -m json.tool "$health_file"
    host_ip="$(hostname -I 2>/dev/null | awk '{print $1}')"
    echo "AudioBookRed запущен."
    echo "UI: http://${host_ip:-SERVER_IP}:$port/ui/"
    echo "Обновление: cd '$ROOT' && sudo bash update.sh"
    exit 0
  fi
  sleep 2
done

echo "API не ответил за 180 секунд. Последние логи:" >&2
docker compose --env-file .env logs --tail=200 api >&2
exit 4
