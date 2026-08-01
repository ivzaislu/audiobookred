#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DUMP=""
CONFIRMED=false
SAFETY_BACKUP=true

usage() {
  cat <<'TXT'
Восстановление PostgreSQL AudioBookRed из custom-format дампа.

Использование:
  sudo bash restore-db.sh DUMP_FILE --yes [--no-safety-backup]

Параметры:
  --yes               обязательное подтверждение полной замены текущей базы
  --no-safety-backup  не создавать дамп текущей базы перед восстановлением
  -h, --help          показать справку

При наличии файла DUMP_FILE.sha256 его контрольная сумма проверяется автоматически.
API останавливается на время восстановления и запускается после успешной операции.
TXT
}

while (($#)); do
  case "$1" in
    --yes) CONFIRMED=true; shift ;;
    --no-safety-backup) SAFETY_BACKUP=false; shift ;;
    -h|--help) usage; exit 0 ;;
    --*) echo "Неизвестный параметр: $1" >&2; usage >&2; exit 2 ;;
    *)
      [[ -z "$DUMP" ]] || { echo "Укажите только один файл дампа" >&2; exit 2; }
      DUMP="$1"; shift ;;
  esac
done

fail() {
  echo "Ошибка: $*" >&2
  exit 1
}

[[ $EUID -eq 0 ]] || fail "запустите скрипт от root"
[[ -n "$DUMP" ]] || { usage >&2; exit 2; }
$CONFIRMED || fail "для восстановления требуется параметр --yes"
command -v docker >/dev/null 2>&1 || fail "Docker не установлен"
command -v sha256sum >/dev/null 2>&1 || fail "sha256sum не найден"
command -v curl >/dev/null 2>&1 || fail "curl не установлен"
command -v python3 >/dev/null 2>&1 || fail "python3 не установлен"
docker compose version >/dev/null 2>&1 || fail "требуется Docker Compose v2"

cd "$ROOT"
[[ -f .env ]] || fail "не найден $ROOT/.env"
[[ -f "$DUMP" ]] || fail "не найден дамп $DUMP"
DUMP="$(cd "$(dirname "$DUMP")" && pwd)/$(basename "$DUMP")"

if [[ -f "$DUMP.sha256" ]]; then
  echo "Проверка SHA256..."
  (cd "$(dirname "$DUMP")" && sha256sum -c "$(basename "$DUMP").sha256")
fi

docker compose --env-file .env up -d db >/dev/null
for _ in $(seq 1 60); do
  if docker compose --env-file .env exec -T db sh -lc \
    'pg_isready -q -U "$POSTGRES_USER" -d "$POSTGRES_DB"'; then
    break
  fi
  sleep 2
done
docker compose --env-file .env exec -T db sh -lc \
  'pg_isready -q -U "$POSTGRES_USER" -d "$POSTGRES_DB"' || fail "PostgreSQL не готов"

docker compose --env-file .env exec -T db pg_restore -l <"$DUMP" >/dev/null || fail "файл не является корректным pg_dump custom-format"

if $SAFETY_BACKUP; then
  echo "Создание страховочной копии текущей базы..."
  bash "$ROOT/backup-db.sh" --keep 5
fi

echo "Остановка API..."
docker compose --env-file .env stop api >/dev/null 2>&1 || true

restore_failed=true
on_exit() {
  if $restore_failed; then
    echo "Восстановление не завершено. API оставлен остановленным." >&2
    echo "Исправьте ошибку и повторите восстановление либо верните страховочный дамп." >&2
  fi
}
trap on_exit EXIT

echo "Пересоздание базы audiobookred..."
docker compose --env-file .env exec -T db sh -lc \
  'dropdb --if-exists --force -U "$POSTGRES_USER" "$POSTGRES_DB" && createdb -U "$POSTGRES_USER" -O "$POSTGRES_USER" "$POSTGRES_DB"'

echo "Восстановление $DUMP..."
docker compose --env-file .env exec -T db sh -lc \
  'pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --exit-on-error --no-owner --no-acl' <"$DUMP"

restore_failed=false
trap - EXIT

echo "Запуск API..."
docker compose --env-file .env up -d api >/dev/null

port="$(sed -n 's/^AUDIOBOOKRED_PORT=//p' .env | tail -n 1 | tr -d '\r\n')"
port="${port:-9117}"
for _ in $(seq 1 90); do
  if curl -fsS "http://127.0.0.1:$port/health" >/tmp/audiobookred-restore-health.json 2>/dev/null; then
    python3 -m json.tool /tmp/audiobookred-restore-health.json
    rm -f /tmp/audiobookred-restore-health.json
    echo "База восстановлена, API запущен."
    exit 0
  fi
  sleep 2
done

echo "База восстановлена, но API не прошёл health check. Последние логи:" >&2
docker compose --env-file .env logs --tail=200 api >&2
exit 4
