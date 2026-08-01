#!/usr/bin/env bash
set -Eeuo pipefail

REPOSITORY="https://github.com/ivzaislu/audiobookred.git"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BRANCH="main"
BACKUP=true
PRUNE=true
HEALTH_TIMEOUT_SECONDS=240

usage() {
  cat <<'TXT'
Безопасное обновление AudioBookRed из GitHub.

Использование:
  sudo bash update.sh [параметры]

Параметры:
  --branch NAME    обновиться из указанной ветки вместо main
  --no-backup      не создавать резервную копию PostgreSQL
  --no-prune       не очищать неиспользуемый build cache после обновления
  -h, --help       показать справку

Скрипт не удаляет Docker volumes и отказывается работать при незакоммиченных
изменениях в Git checkout.
TXT
}

while (($#)); do
  case "$1" in
    --branch)
      [[ $# -ge 2 ]] || { echo "После --branch требуется имя ветки" >&2; exit 2; }
      BRANCH="$2"; shift 2 ;;
    --no-backup) BACKUP=false; shift ;;
    --no-prune) PRUNE=false; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Неизвестный параметр: $1" >&2; usage >&2; exit 2 ;;
  esac
done

# Git может заменить update.sh во время pull. Продолжаем из временной копии.
if [[ "${AUDIOBOOKRED_UPDATE_STAGE:-0}" != "1" ]]; then
  tmp="$(mktemp /tmp/audiobookred-update.XXXXXX)"
  cp "$0" "$tmp"
  chmod 700 "$tmp"
  stage_args=(--branch "$BRANCH")
  $BACKUP || stage_args+=(--no-backup)
  $PRUNE || stage_args+=(--no-prune)
  exec env AUDIOBOOKRED_UPDATE_STAGE=1 AUDIOBOOKRED_UPDATE_ROOT="$ROOT" \
    AUDIOBOOKRED_UPDATE_TEMP="$tmp" bash "$tmp" "${stage_args[@]}"
fi

ROOT="${AUDIOBOOKRED_UPDATE_ROOT:?}"
health_file=""

cleanup() {
  [[ -n "${health_file:-}" ]] && rm -f "$health_file" 2>/dev/null || true
  [[ -n "${AUDIOBOOKRED_UPDATE_TEMP:-}" ]] && rm -f "$AUDIOBOOKRED_UPDATE_TEMP" 2>/dev/null || true
}
trap cleanup EXIT

fail() {
  echo "Ошибка: $*" >&2
  exit 1
}

project_version() {
  sed -n 's:.*<Version>\([^<][^<]*\)</Version>.*:\1:p' \
    "$ROOT/src/AudioBookRed.Api/AudioBookRed.Api.csproj" | head -n 1
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

    echo "Последние проверки healthcheck:" >&2
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
  local api_id health_status actual_version status

  health_file="$(mktemp)"
  api_id="$(docker compose --env-file .env ps -q api 2>/dev/null || true)"

  while (( SECONDS < deadline )); do
    if curl --fail --silent --show-error \
      --connect-timeout 3 --max-time 10 \
      "http://127.0.0.1:$port/health" >"$health_file" 2>/dev/null; then

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
        return 0
      fi
    fi

    if [[ -n "$api_id" ]]; then
      health_status="$(docker inspect "$api_id" \
        --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}not-configured{{end}}' \
        2>/dev/null || true)"
      if [[ "$health_status" == "unhealthy" ]]; then
        return 1
      fi
    fi

    sleep 2
  done

  return 1
}

[[ $EUID -eq 0 ]] || fail "запустите скрипт от root"
command -v git >/dev/null 2>&1 || fail "git не установлен"
command -v docker >/dev/null 2>&1 || fail "Docker не установлен"
command -v curl >/dev/null 2>&1 || fail "curl не установлен"
command -v python3 >/dev/null 2>&1 || fail "python3 не установлен"
docker compose version >/dev/null 2>&1 || fail "требуется Docker Compose v2"
[[ "$BRANCH" =~ ^[A-Za-z0-9._/-]+$ ]] || fail "недопустимое имя ветки"

cd "$ROOT"
[[ -d .git ]] || fail "$ROOT не является Git checkout"
[[ -f .env ]] || fail "не найден $ROOT/.env"

origin="$(git remote get-url origin 2>/dev/null || true)"
case "$origin" in
  "$REPOSITORY"|git@github.com:ivzaislu/audiobookred.git) ;;
  *) fail "origin указывает на '${origin:-не задан}', ожидается $REPOSITORY" ;;
esac

if [[ -n "$(git status --porcelain --untracked-files=normal)" ]]; then
  echo "В checkout есть локальные изменения:" >&2
  git status --short >&2
  git diff --summary >&2 || true
  fail "сохраните или отмените изменения перед обновлением"
fi

free_kb="$(df -Pk "$ROOT" | awk 'NR==2 {print $4}')"
if [[ "$free_kb" =~ ^[0-9]+$ ]] && (( free_kb < 1572864 )); then
  fail "свободно меньше 1.5 ГБ; выполните docker builder prune -a -f и повторите"
fi

old_commit="$(git rev-parse --short HEAD)"

if $BACKUP; then
  echo "Создание резервной копии базы перед обновлением..."
  bash "$ROOT/backup-db.sh" --keep 5
fi

echo "Получение обновлений из $REPOSITORY, ветка $BRANCH..."
git fetch --prune --tags origin
if git show-ref --verify --quiet "refs/heads/$BRANCH"; then
  git checkout "$BRANCH"
else
  git checkout -b "$BRANCH" "origin/$BRANCH"
fi
git pull --ff-only origin "$BRANCH"
new_commit="$(git rev-parse --short HEAD)"
expected_version="$(project_version)"
[[ -n "$expected_version" ]] || fail "не удалось определить версию проекта"

# Обновляем CLI, /etc/default, logrotate и при необходимости cron.
# install.sh больше не меняет режимы отслеживаемых Git-файлов.
bash "$ROOT/install.sh" --no-start

if [[ -n "$(git status --porcelain --untracked-files=normal)" ]]; then
  echo "Установщик неожиданно изменил Git checkout:" >&2
  git status --short >&2
  git diff --summary >&2 || true
  fail "обновление остановлено до пересборки контейнеров"
fi

# Сначала собираем новый API. Работающий контейнер продолжает обслуживать запросы.
docker compose --env-file .env pull db
docker compose --env-file .env build --pull api
docker compose --env-file .env up -d --remove-orphans

port="$(sed -n 's/^AUDIOBOOKRED_PORT=//p' .env | tail -n 1 | tr -d '\r\n')"
port="${port:-9117}"

echo "Ожидание готовности AudioBookRed $expected_version на порту $port..."
if ! wait_for_api "$port" "$expected_version"; then
  show_api_diagnostics
  echo "Предыдущий commit: $old_commit; текущий commit: $new_commit" >&2
  exit 4
fi

api_id="$(docker compose --env-file .env ps -q api 2>/dev/null || true)"
if [[ -n "$api_id" ]]; then
  health_status="$(docker inspect "$api_id" \
    --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}not-configured{{end}}' \
    2>/dev/null || true)"
  echo "API container health: ${health_status:-unknown}"
fi

if $PRUNE; then
  docker builder prune -f >/dev/null || true
  docker image prune -f >/dev/null || true
fi

echo "AudioBookRed обновлён: $old_commit -> $new_commit"
echo "Версия: $expected_version"
echo "Ветка: $BRANCH"
df -h /
