#!/usr/bin/env bash
set -Eeuo pipefail

REPOSITORY="https://github.com/ivzaislu/audiobookred.git"
BRANCH="main"
SOURCE_DIR="/root/AudioBookRed"
BACKUP_DB=true

if (($#)) && [[ "$1" != --* ]]; then
  SOURCE_DIR="$1"
  shift
fi
while (($#)); do
  case "$1" in
    --branch)
      [[ $# -ge 2 ]] || { echo "После --branch требуется имя ветки" >&2; exit 2; }
      BRANCH="$2"; shift 2 ;;
    --no-backup) BACKUP_DB=false; shift ;;
    -h|--help)
      cat <<'TXT'
Переход существующей архивной установки AudioBookRed на Git checkout.

Использование:
  curl -fsSL https://raw.githubusercontent.com/ivzaislu/audiobookred/main/scripts/migrate-existing-install.sh \
    | sudo bash -s -- /root/AudioBookRed

Скрипт перемещает старый каталог в PATH.pre-git-ДАТА, клонирует официальный
репозиторий на прежнее место, переносит .env и backups и запускает install.sh.
PostgreSQL volume не удаляется и не копируется. Скрипт создаёт локальный
Docker Compose override, который подключает Git-установку к старому volume.
TXT
      exit 0 ;;
    *) echo "Неизвестный параметр: $1" >&2; exit 2 ;;
  esac
done

fail() {
  echo "Ошибка: $*" >&2
  exit 1
}

[[ $EUID -eq 0 ]] || fail "запустите скрипт от root"
[[ "$SOURCE_DIR" = /* ]] || fail "путь установки должен быть абсолютным"
[[ "$BRANCH" =~ ^[A-Za-z0-9._/-]+$ ]] || fail "недопустимое имя ветки"
command -v git >/dev/null 2>&1 || fail "git не установлен"
command -v docker >/dev/null 2>&1 || fail "Docker не установлен"
docker compose version >/dev/null 2>&1 || fail "требуется Docker Compose v2"

[[ -d "$SOURCE_DIR" ]] || fail "не найден каталог $SOURCE_DIR"
[[ ! -d "$SOURCE_DIR/.git" ]] || fail "$SOURCE_DIR уже является Git checkout; используйте update.sh"
[[ -f "$SOURCE_DIR/.env" ]] || fail "не найден $SOURCE_DIR/.env"
[[ -f "$SOURCE_DIR/docker-compose.yml" ]] || fail "не найден docker-compose.yml"

echo "Определение существующего PostgreSQL volume..."
cd "$SOURCE_DIR"
docker compose --env-file .env up -d db >/dev/null
for _ in $(seq 1 60); do
  db_id="$(docker compose --env-file .env ps -q db 2>/dev/null || true)"
  [[ -n "$db_id" ]] && break
  sleep 2
done
[[ -n "${db_id:-}" ]] || fail "не найден контейнер PostgreSQL"
volume_name="$(docker inspect "$db_id" --format '{{range .Mounts}}{{if eq .Destination "/var/lib/postgresql/data"}}{{.Name}}{{end}}{{end}}')"
[[ -n "$volume_name" ]] || fail "не удалось определить PostgreSQL volume"
[[ "$volume_name" =~ ^[A-Za-z0-9_.-]+$ ]] || fail "небезопасное имя volume: $volume_name"
echo "Будет сохранён volume: $volume_name"

if $BACKUP_DB; then
  [[ -f "$SOURCE_DIR/backup-db.sh" ]] || fail "не найден старый backup-db.sh; используйте --no-backup только при наличии внешней копии"
  echo "Создание резервной копии перед миграцией..."
  bash "$SOURCE_DIR/backup-db.sh"
fi

timestamp="$(date +%Y%m%d-%H%M%S)"
OLD_DIR="${SOURCE_DIR}.pre-git-${timestamp}"
[[ ! -e "$OLD_DIR" ]] || fail "резервный каталог уже существует: $OLD_DIR"

mv "$SOURCE_DIR" "$OLD_DIR"
rollback_clone() {
  if [[ ! -d "$SOURCE_DIR/.git" ]]; then
    rm -rf "$SOURCE_DIR"
    mv "$OLD_DIR" "$SOURCE_DIR"
    echo "Исходный каталог восстановлен после ошибки клонирования." >&2
  fi
}
trap rollback_clone ERR

git clone --branch "$BRANCH" --single-branch "$REPOSITORY" "$SOURCE_DIR"
trap - ERR

cp -a "$OLD_DIR/.env" "$SOURCE_DIR/.env"
chmod 600 "$SOURCE_DIR/.env"

if [[ -d "$OLD_DIR/backups" ]]; then
  rm -rf "$SOURCE_DIR/backups"
  mv "$OLD_DIR/backups" "$SOURCE_DIR/backups"
fi

# Репозиторный Compose остаётся универсальным для новых установок, а локальный
# override привязывает мигрированную установку к фактическому старому volume.
cat > "$SOURCE_DIR/docker-compose.override.yml" <<YAML
volumes:
  audiobookred-db:
    external: true
    name: $volume_name
YAML
chmod 600 "$SOURCE_DIR/docker-compose.override.yml"

bash "$SOURCE_DIR/install.sh"

echo "Миграция завершена."
echo "Git checkout: $SOURCE_DIR"
echo "Старые исходники сохранены: $OLD_DIR"
echo "После проверки их можно удалить: rm -rf '$OLD_DIR'"
