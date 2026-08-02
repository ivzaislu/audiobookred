#!/usr/bin/env bash
set -Eeuo pipefail

REPOSITORY="https://github.com/ivzaislu/audiobookred.git"
BRANCH="main"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
START=true
INSTALL_CRON=true
REPLACE_CRON=false
BUILD=true
HEALTH_TIMEOUT_SECONDS=900

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

project_version() {
  sed -n 's:.*<Version>\([^<][^<]*\)</Version>.*:\1:p' \
    "$ROOT/src/AudioBookRed.Api/AudioBookRed.Api.csproj" | head -n 1
}

migrate_existing_cron() {
  local cron_file="/etc/cron.d/audiobookred"
  local old_latest_line='17 * * * * root /usr/bin/flock -n /run/lock/audiobookred-rutracker-latest.lock /usr/bin/timeout 3m /usr/local/sbin/audiobookred-source rutracker latest >> /var/log/audiobookred-rutracker-latest.log 2>&1'
  local new_latest_line='17 4 * * * root /usr/bin/flock -n /run/lock/audiobookred-rutracker-latest.lock /usr/bin/timeout 3m /usr/local/sbin/audiobookred-source rutracker latest >> /var/log/audiobookred-rutracker-latest.log 2>&1'
  local old_worker_line='* * * * * root /usr/bin/flock -n /run/lock/audiobookred-rutracker-worker.lock /usr/bin/timeout 9m /usr/local/sbin/audiobookred-source rutracker work 3 >> /var/log/audiobookred-rutracker-worker.log 2>&1'
  local new_worker_line='* * * * * root /usr/bin/flock -n /run/lock/audiobookred-rutracker-worker.lock /usr/bin/timeout 9m /usr/local/sbin/audiobookred-source rutracker work >> /var/log/audiobookred-rutracker-worker.log 2>&1'
  local backup=""

  [[ -f "$cron_file" ]] || return 0

  backup_once() {
    if [[ -z "$backup" ]]; then
      backup="${cron_file}.backup-$(date +%Y%m%d-%H%M%S)"
      cp -a "$cron_file" "$backup"
    fi
  }

  replace_exact_cron_line() {
    local old_line="$1"
    local new_line="$2"

    backup_once
    python3 - "$cron_file" "$old_line" "$new_line" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
old = sys.argv[2]
new = sys.argv[3]
text = path.read_text()
if text.count(old) != 1:
    raise SystemExit(f"ожидалась одна cron-строка, найдено {text.count(old)}")
path.write_text(text.replace(old, new))
PY
  }

  if grep -Fxq "$old_latest_line" "$cron_file"; then
    replace_exact_cron_line "$old_latest_line" "$new_latest_line"
    echo "Cron latest перенесён с ежечасного запуска на ежедневный 04:17; backup: $backup"
  elif grep -Fxq "$new_latest_line" "$cron_file"; then
    echo "Cron latest уже настроен на ежедневный запуск в 04:17."
  elif grep -Fq 'audiobookred-source rutracker latest' "$cron_file"; then
    echo "Предупреждение: обнаружено пользовательское расписание rutracker latest; оно сохранено без изменений." >&2
  fi

  if grep -Fxq "$old_worker_line" "$cron_file"; then
    replace_exact_cron_line "$old_worker_line" "$new_worker_line"
    echo "Cron worker переведён на runtime workerJobLimit; backup: $backup"
  elif grep -Fxq "$new_worker_line" "$cron_file"; then
    echo "Cron worker уже использует runtime workerJobLimit."
  elif grep -Fq 'audiobookred-source rutracker work' "$cron_file"; then
    echo "Предупреждение: обнаружена пользовательская команда rutracker work; она сохранена без изменений." >&2
  fi

  [[ -z "$backup" ]] || chmod 0644 "$cron_file"
}

show_api_diagnostics() {
  local api_id health_status

  echo
  echo "Состояние Compose:" >&2
  docker compose --env-file .env ps >&2 || true

  api_id="$(docker compose --env-file .env ps -q api 2>/dev/null || true)"
  if [[ -n "$api_id" ]]; then
    health_status="$(docker inspect "$api_id" \
      --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}not-configured{{end}}' \
      2>/dev/null || true)"
    echo "API container health: ${health_status:-unknown}" >&2

    docker inspect "$api_id" \
      --format '{{if .State.Health}}{{range .State.Health.Log}}{{.End}} exit={{.ExitCode}} {{printf "%q" .Output}}{{println}}{{end}}{{end}}' \
      2>/dev/null | tail -n 10 >&2 || true
  fi

  echo "Последние логи API:" >&2
  docker compose --env-file .env logs --tail=200 api >&2 || true
}

wait_for_api() {
  local port="$1"
  local expected_version="$2"
  local deadline=$((SECONDS + HEALTH_TIMEOUT_SECONDS))
  local health_file api_id health_status actual_version status

  health_file="$(mktemp)"
  api_id="$(docker compose --env-file .env ps -q api 2>/dev/null || true)"

  while (( SECONDS < deadline )); do
    if curl --fail --silent --show-error \
      --connect-timeout 3 --max-time 10 \
      "http://127.0.0.1:$port/health/ready" >"$health_file" 2>/dev/null; then

      read -r status actual_version < <(
        python3 - "$health_file" <<'PY'
import json
import sys

try:
    payload = json.load(open(sys.argv[1], encoding="utf-8"))
except Exception:
    print("", "")
else:
    print(payload.get("status", ""), payload.get("version", ""))
PY
      )

      if [[ "$status" == "ok" && "$actual_version" == "$expected_version" ]]; then
        python3 -m json.tool "$health_file"
        rm -f "$health_file"
        return 0
      fi
    fi

    if [[ -n "$api_id" ]]; then
      health_status="$(docker inspect "$api_id" \
        --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}not-configured{{end}}' \
        2>/dev/null || true)"
      if [[ "$health_status" == "unhealthy" ]]; then
        rm -f "$health_file"
        return 1
      fi
    fi

    sleep 2
  done

  rm -f "$health_file"
  return 1
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
[[ -f src/AudioBookRed.Api/AudioBookRed.Api.csproj ]] || fail "не найден файл проекта API"

# Шаблон нужен только для новой установки. Существующий рабочий .env
# не должен блокироваться из-за отсутствующего .env.example.
if [[ ! -f .env ]]; then
  [[ -f .env.example ]] || fail "не найден $ROOT/.env.example; обновите checkout из $REPOSITORY"
fi

# Не меняем режимы отслеживаемых Git-файлов: это делало checkout грязным
# на серверах, где executable bit учитывается. Все repo-скрипты вызываются через bash.
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
expected_version="$(project_version)"
startup_timeout="$(read_env_value AUDIOBOOKRED_STARTUP_TIMEOUT_SECONDS)"
startup_timeout="${startup_timeout:-900}"
[[ "$startup_timeout" =~ ^[0-9]+$ ]] && (( startup_timeout >= 60 && startup_timeout <= 3600 )) \
  || fail "AUDIOBOOKRED_STARTUP_TIMEOUT_SECONDS должен быть числом 60..3600"
HEALTH_TIMEOUT_SECONDS="$startup_timeout"

[[ -n "$api_key" && "$api_key" != "change-me" ]] || fail "задайте безопасный API_KEY в $ROOT/.env"
[[ -n "$db_password" && "$db_password" != "change-me" ]] || fail "задайте безопасный DB_PASSWORD в $ROOT/.env"
[[ "$port" =~ ^[0-9]+$ ]] && (( port >= 1 && port <= 65535 )) || fail "AUDIOBOOKRED_PORT должен быть числом 1..65535"
[[ -n "$expected_version" ]] || fail "не удалось определить версию проекта"

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
    migrate_existing_cron
    echo "Существующий /etc/cron.d/audiobookred сохранён. Для полной замены используйте --replace-cron."
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

echo "Ожидание готовности AudioBookRed $expected_version на порту $port..."
if ! wait_for_api "$port" "$expected_version"; then
  show_api_diagnostics
  fail "API не стал готов за ${HEALTH_TIMEOUT_SECONDS} секунд"
fi

api_id="$(docker compose --env-file .env ps -q api 2>/dev/null || true)"
if [[ -n "$api_id" ]]; then
  health_status="$(docker inspect "$api_id" \
    --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}not-configured{{end}}' \
    2>/dev/null || true)"
  echo "API container health: ${health_status:-unknown}"
fi

host_ip="$(hostname -I 2>/dev/null | awk '{print $1}')"
echo "AudioBookRed запущен."
echo "UI: http://${host_ip:-SERVER_IP}:$port/ui/"
echo "Обновление: cd '$ROOT' && sudo bash update.sh"
