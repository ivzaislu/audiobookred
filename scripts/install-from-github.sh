#!/usr/bin/env bash
set -Eeuo pipefail

REPOSITORY="https://github.com/ivzaislu/audiobookred.git"
INSTALL_DIR="${AUDIOBOOKRED_DIR:-/opt/audiobookred}"
BRANCH="${AUDIOBOOKRED_BRANCH:-main}"
START=true
INSTALL_CRON=true

usage() {
  cat <<'TXT'
Установка AudioBookRed непосредственно из GitHub.

Использование:
  curl -fsSL https://raw.githubusercontent.com/ivzaislu/audiobookred/main/scripts/install-from-github.sh | sudo bash

Или с параметрами:
  sudo bash install-from-github.sh [--dir PATH] [--branch NAME] [--no-start] [--no-cron]
TXT
}

while (($#)); do
  case "$1" in
    --dir)
      [[ $# -ge 2 ]] || { echo "После --dir требуется путь" >&2; exit 2; }
      INSTALL_DIR="$2"; shift 2 ;;
    --branch)
      [[ $# -ge 2 ]] || { echo "После --branch требуется имя ветки" >&2; exit 2; }
      BRANCH="$2"; shift 2 ;;
    --no-start) START=false; shift ;;
    --no-cron) INSTALL_CRON=false; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Неизвестный параметр: $1" >&2; usage >&2; exit 2 ;;
  esac
done

fail() {
  echo "Ошибка: $*" >&2
  exit 1
}

[[ $EUID -eq 0 ]] || fail "запустите скрипт от root"
[[ "$INSTALL_DIR" = /* ]] || fail "--dir должен быть абсолютным путём"
[[ "$BRANCH" =~ ^[A-Za-z0-9._/-]+$ ]] || fail "недопустимое имя ветки"
command -v git >/dev/null 2>&1 || fail "git не установлен"
command -v docker >/dev/null 2>&1 || fail "Docker не установлен"
docker compose version >/dev/null 2>&1 || fail "требуется Docker Compose v2"

umask 077
mkdir -p "$(dirname "$INSTALL_DIR")"

if [[ -d "$INSTALL_DIR/.git" ]]; then
  origin="$(git -C "$INSTALL_DIR" remote get-url origin 2>/dev/null || true)"
  case "$origin" in
    "$REPOSITORY"|git@github.com:ivzaislu/audiobookred.git) ;;
    *) fail "$INSTALL_DIR уже является другим Git-репозиторием: ${origin:-origin не задан}" ;;
  esac

  if [[ -n "$(git -C "$INSTALL_DIR" status --porcelain --untracked-files=normal)" ]]; then
    fail "в $INSTALL_DIR есть локальные изменения; сохраните их перед установкой"
  fi

  git -C "$INSTALL_DIR" fetch --prune --tags origin
  if git -C "$INSTALL_DIR" show-ref --verify --quiet "refs/heads/$BRANCH"; then
    git -C "$INSTALL_DIR" checkout "$BRANCH"
  else
    git -C "$INSTALL_DIR" checkout -b "$BRANCH" "origin/$BRANCH"
  fi
  git -C "$INSTALL_DIR" pull --ff-only origin "$BRANCH"
elif [[ -e "$INSTALL_DIR" ]] && [[ -n "$(find "$INSTALL_DIR" -mindepth 1 -maxdepth 1 -print -quit 2>/dev/null)" ]]; then
  fail "$INSTALL_DIR существует и не является Git checkout. Используйте пустой каталог или выберите другой путь через --dir."
else
  rm -rf "$INSTALL_DIR"
  git clone --branch "$BRANCH" --single-branch "$REPOSITORY" "$INSTALL_DIR"
fi

args=()
$START || args+=(--no-start)
$INSTALL_CRON || args+=(--no-cron)

bash "$INSTALL_DIR/install.sh" "${args[@]}"

echo "Git checkout: $INSTALL_DIR"
echo "Repository: $REPOSITORY"
echo "Branch: $BRANCH"
