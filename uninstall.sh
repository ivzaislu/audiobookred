#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REMOVE_CODE=false
PURGE_DATA=false
KEEP_CONTAINERS=false

usage() {
  cat <<'TXT'
Удаление AudioBookRed.

Использование:
  sudo bash uninstall.sh [параметры]

По умолчанию скрипт останавливает контейнеры и удаляет cron/CLI/logrotate,
но СОХРАНЯЕТ PostgreSQL volume, .env, резервные копии и исходный каталог.

Параметры:
  --remove-code      удалить каталог проекта после остановки
  --keep-containers  удалить системную интеграцию, но оставить контейнеры запущенными
  --purge-data       также удалить PostgreSQL volume; требуется ручное подтверждение
  -h, --help         показать справку
TXT
}

for arg in "$@"; do
  case "$arg" in
    --remove-code) REMOVE_CODE=true ;;
    --keep-containers) KEEP_CONTAINERS=true ;;
    --purge-data) PURGE_DATA=true ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Неизвестный параметр: $arg" >&2; usage >&2; exit 2 ;;
  esac
done

fail() {
  echo "Ошибка: $*" >&2
  exit 1
}

[[ $EUID -eq 0 ]] || fail "запустите скрипт от root"

if $PURGE_DATA && $KEEP_CONTAINERS; then
  fail "--purge-data несовместим с --keep-containers"
fi

# Для удаления собственного каталога продолжаем из временной копии.
if $REMOVE_CODE && [[ "${AUDIOBOOKRED_UNINSTALL_STAGE:-0}" != "1" ]]; then
  tmp="$(mktemp /tmp/audiobookred-uninstall.XXXXXX)"
  cp "$0" "$tmp"
  chmod 700 "$tmp"
  args=(--remove-code)
  $PURGE_DATA && args+=(--purge-data)
  $KEEP_CONTAINERS && args+=(--keep-containers)
  exec env AUDIOBOOKRED_UNINSTALL_STAGE=1 AUDIOBOOKRED_UNINSTALL_ROOT="$ROOT" \
    AUDIOBOOKRED_UNINSTALL_TEMP="$tmp" bash "$tmp" "${args[@]}"
fi
ROOT="${AUDIOBOOKRED_UNINSTALL_ROOT:-$ROOT}"
trap 'rm -f "${AUDIOBOOKRED_UNINSTALL_TEMP:-}" 2>/dev/null || true' EXIT

if $PURGE_DATA; then
  [[ -t 0 ]] || fail "удаление данных разрешено только из интерактивного терминала"
  echo "ВНИМАНИЕ: будут безвозвратно удалены все записи PostgreSQL AudioBookRed."
  echo "Сначала рекомендуется выполнить: sudo bash $ROOT/backup-db.sh"
  read -r -p 'Введите DELETE AUDIOBOOKRED DATA: ' confirmation
  [[ "$confirmation" == "DELETE AUDIOBOOKRED DATA" ]] || fail "подтверждение не совпало"
fi

volume_name=""
if ! $KEEP_CONTAINERS && command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1 \
  && [[ -f "$ROOT/docker-compose.yml" ]]; then
  cd "$ROOT"
  if $PURGE_DATA; then
    if [[ -f .env ]]; then
      docker compose --env-file .env up -d db >/dev/null 2>&1 || true
      db_id="$(docker compose --env-file .env ps -q db 2>/dev/null || true)"
    else
      db_id="$(docker compose ps -q db 2>/dev/null || true)"
    fi
    if [[ -n "${db_id:-}" ]]; then
      volume_name="$(docker inspect "$db_id" --format '{{range .Mounts}}{{if eq .Destination "/var/lib/postgresql/data"}}{{.Name}}{{end}}{{end}}' 2>/dev/null || true)"
    fi
  fi

  if [[ -f .env ]]; then
    docker compose --env-file .env down --remove-orphans
  else
    docker compose down --remove-orphans || true
  fi
fi

rm -f /etc/cron.d/audiobookred
rm -f /etc/logrotate.d/audiobookred
rm -f /etc/default/audiobookred
rm -f /usr/local/sbin/audiobookred-source

if $PURGE_DATA; then
  volumes=()
  if [[ -n "$volume_name" ]]; then
    volumes+=("$volume_name")
  else
    mapfile -t volumes < <(docker volume ls \
      --filter label=com.docker.compose.project=audiobookred \
      --filter label=com.docker.compose.volume=audiobookred-db -q)
  fi
  if ((${#volumes[@]} == 0)); then
    echo "PostgreSQL volume не найден. Данные не удалены." >&2
  else
    printf 'Удаление volume: %s\n' "${volumes[@]}"
    docker volume rm "${volumes[@]}"
  fi
fi

if $REMOVE_CODE; then
  case "$ROOT" in
    /|/root|/home|/opt|/usr|/var|"") fail "отказ удалять опасный путь '$ROOT'" ;;
  esac
  rm -rf -- "$ROOT"
  echo "Каталог проекта удалён: $ROOT"
else
  echo "Каталог проекта сохранён: $ROOT"
  echo "PostgreSQL volume сохранён."
fi

echo "Системная интеграция AudioBookRed удалена."
