#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUTPUT_DIR="$ROOT/backups"
KEEP=0

usage() {
  cat <<'TXT'
Резервное копирование PostgreSQL AudioBookRed.

Использование:
  bash backup-db.sh [--output-dir PATH] [--keep N]

Параметры:
  --output-dir PATH  каталог для дампа; по умолчанию ./backups
  --keep N           оставить только N последних дампов; 0 — ничего не удалять
  -h, --help         показать справку
TXT
}

while (($#)); do
  case "$1" in
    --output-dir)
      [[ $# -ge 2 ]] || { echo "После --output-dir требуется путь" >&2; exit 2; }
      OUTPUT_DIR="$2"; shift 2 ;;
    --keep)
      [[ $# -ge 2 ]] || { echo "После --keep требуется число" >&2; exit 2; }
      KEEP="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Неизвестный параметр: $1" >&2; usage >&2; exit 2 ;;
  esac
done

[[ "$KEEP" =~ ^[0-9]+$ ]] || { echo "--keep должен быть целым числом" >&2; exit 2; }
command -v docker >/dev/null 2>&1 || { echo "Docker не установлен" >&2; exit 1; }
docker compose version >/dev/null 2>&1 || { echo "Требуется Docker Compose v2" >&2; exit 1; }
command -v sha256sum >/dev/null 2>&1 || { echo "sha256sum не найден" >&2; exit 1; }

cd "$ROOT"
[[ -f .env ]] || { echo "Не найден $ROOT/.env" >&2; exit 1; }
docker compose --env-file .env config >/dev/null
mkdir -p "$OUTPUT_DIR"
OUTPUT_DIR="$(cd "$OUTPUT_DIR" && pwd)"
chmod 700 "$OUTPUT_DIR" 2>/dev/null || true

docker compose --env-file .env up -d db >/dev/null
for _ in $(seq 1 60); do
  if docker compose --env-file .env exec -T db sh -lc \
    'pg_isready -q -U "$POSTGRES_USER" -d "$POSTGRES_DB"'; then
    break
  fi
  sleep 2
done

docker compose --env-file .env exec -T db sh -lc \
  'pg_isready -q -U "$POSTGRES_USER" -d "$POSTGRES_DB"' || {
    echo "PostgreSQL не готов к резервному копированию" >&2
    exit 3
  }

timestamp="$(date +%Y%m%d-%H%M%S)"
out="$OUTPUT_DIR/audiobookred-$timestamp.dump"
tmp="$out.partial.$$"
cleanup() { rm -f "$tmp"; }
trap cleanup EXIT

echo "Создание дампа: $out"
docker compose --env-file .env exec -T db sh -lc \
  'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc --no-owner --no-acl' >"$tmp"

[[ -s "$tmp" ]] || { echo "Получен пустой дамп" >&2; exit 4; }
docker compose --env-file .env exec -T db pg_restore -l <"$tmp" >/dev/null
mv "$tmp" "$out"
chmod 600 "$out"
(
  cd "$OUTPUT_DIR"
  sha256sum "$(basename "$out")" >"$(basename "$out").sha256"
)
chmod 600 "$out.sha256"

if (( KEEP > 0 )); then
  mapfile -t old_dumps < <(find "$OUTPUT_DIR" -maxdepth 1 -type f -name 'audiobookred-*.dump' -printf '%T@ %p\n' | sort -nr | awk -v keep="$KEEP" 'NR > keep {sub(/^[^ ]+ /, ""); print}')
  for old in "${old_dumps[@]:-}"; do
    [[ -n "$old" ]] || continue
    rm -f -- "$old" "$old.sha256"
  done
fi

size="$(du -h "$out" | awk '{print $1}')"
echo "Резервная копия создана: $out ($size)"
echo "SHA256: $out.sha256"
