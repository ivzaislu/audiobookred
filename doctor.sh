#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FULL=false
TMP_DIR=""

cleanup() {
  [[ -n "$TMP_DIR" ]] && rm -rf "$TMP_DIR" 2>/dev/null || true
}
trap cleanup EXIT

for arg in "$@"; do
  case "$arg" in
    --full) FULL=true ;;
    -h|--help)
      echo "Использование: bash doctor.sh [--full]"
      exit 0 ;;
    *) echo "Неизвестный параметр: $arg" >&2; exit 2 ;;
  esac
done

errors=0
warnings=0
ok() { printf 'OK    %s\n' "$*"; }
warn() { printf 'WARN  %s\n' "$*"; warnings=$((warnings + 1)); }
err() { printf 'ERROR %s\n' "$*"; errors=$((errors + 1)); }

printf 'AudioBookRed doctor\n'
printf 'Каталог: %s\n\n' "$ROOT"

if command -v git >/dev/null 2>&1; then
  if [[ -d "$ROOT/.git" ]]; then
    origin="$(git -C "$ROOT" remote get-url origin 2>/dev/null || true)"
    commit="$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || true)"
    branch="$(git -C "$ROOT" branch --show-current 2>/dev/null || true)"
    [[ "$origin" == "https://github.com/ivzaislu/audiobookred.git" || "$origin" == "git@github.com:ivzaislu/audiobookred.git" ]] \
      && ok "Git origin: $origin" || warn "Git origin: ${origin:-не задан}"
    ok "Git: ${branch:-detached}@${commit:-unknown}"

    git_status="$(git -C "$ROOT" status --porcelain --untracked-files=normal)"
    if [[ -z "$git_status" ]]; then
      ok "Git checkout без локальных изменений"
    else
      warn "В Git checkout есть локальные изменения"
      printf '%s\n' "$git_status"
      git -C "$ROOT" diff --summary || true
    fi
  else
    warn "каталог не является Git checkout; update.sh не сможет обновлять проект"
  fi
else
  err "git не установлен"
fi

if command -v docker >/dev/null 2>&1; then
  ok "Docker: $(docker --version 2>/dev/null)"
  docker compose version >/dev/null 2>&1 && ok "Docker Compose v2 доступен" || err "Docker Compose v2 недоступен"
else
  err "Docker не установлен"
fi

command -v curl >/dev/null 2>&1 && ok "curl доступен" || err "curl не установлен"
command -v python3 >/dev/null 2>&1 && ok "python3 доступен" || err "python3 не установлен"

if [[ -f "$ROOT/.env.example" ]]; then
  ok ".env.example найден"
elif [[ -f "$ROOT/.env" ]]; then
  warn ".env.example отсутствует; работающая установка не блокируется, но новая установка будет неполной"
else
  err "не найдены ни $ROOT/.env, ни $ROOT/.env.example"
fi

api_key=""
if [[ -f "$ROOT/.env" ]]; then
  mode="$(stat -c '%a' "$ROOT/.env" 2>/dev/null || true)"
  [[ "$mode" == "600" ]] && ok ".env найден, права 600" || warn ".env найден, рекомендуемые права 600; текущие ${mode:-unknown}"
  api_key="$(sed -n 's/^API_KEY=//p' "$ROOT/.env" | tail -n 1 | tr -d '\r\n')"
  db_password="$(sed -n 's/^DB_PASSWORD=//p' "$ROOT/.env" | tail -n 1 | tr -d '\r\n')"
  [[ -n "$api_key" && "$api_key" != "change-me" ]] && ok "API_KEY настроен" || err "API_KEY не настроен"
  [[ -n "$db_password" && "$db_password" != "change-me" ]] && ok "DB_PASSWORD настроен" || err "DB_PASSWORD не настроен"
else
  err "не найден $ROOT/.env"
fi

free_kb="$(df -Pk "$ROOT" 2>/dev/null | awk 'NR==2 {print $4}')"
if [[ "$free_kb" =~ ^[0-9]+$ ]]; then
  free_gb="$(awk -v kb="$free_kb" 'BEGIN {printf "%.1f", kb/1024/1024}')"
  if (( free_kb < 1048576 )); then
    err "свободно только $free_gb ГБ"
  elif (( free_kb < 3145728 )); then
    warn "свободно $free_gb ГБ; для сборок желательно не меньше 3 ГБ"
  else
    ok "свободное место: $free_gb ГБ"
  fi
fi

TMP_DIR="$(mktemp -d)"

if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1 && [[ -f "$ROOT/.env" ]]; then
  cd "$ROOT"
  if docker compose --env-file .env config >/dev/null 2>&1; then
    ok "docker-compose.yml корректен"
  else
    err "docker compose config завершился ошибкой"
  fi

  ps_output="$(docker compose --env-file .env ps 2>&1 || true)"
  printf '\n%s\n' "$ps_output"

  if docker compose --env-file .env exec -T db sh -lc 'pg_isready -q -U "$POSTGRES_USER" -d "$POSTGRES_DB"' >/dev/null 2>&1; then
    ok "PostgreSQL отвечает"
  else
    err "PostgreSQL не отвечает"
  fi

  api_id="$(docker compose --env-file .env ps -q api 2>/dev/null || true)"
  if [[ -n "$api_id" ]]; then
    health_status="$(docker inspect "$api_id" \
      --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}not-configured{{end}}' \
      2>/dev/null || true)"
    case "$health_status" in
      healthy) ok "API container health: healthy" ;;
      starting) warn "API container health: starting" ;;
      not-configured) warn "API container healthcheck не настроен; требуется пересборка после патча 4" ;;
      *) err "API container health: ${health_status:-unknown}" ;;
    esac
  else
    err "контейнер API не найден"
  fi

  port="$(sed -n 's/^AUDIOBOOKRED_PORT=//p' .env | tail -n 1 | tr -d '\r\n')"
  port="${port:-9117}"
  health_file="$TMP_DIR/health.json"

  if command -v curl >/dev/null 2>&1 && curl --fail --silent --show-error \
    --connect-timeout 3 --max-time 10 \
    "http://127.0.0.1:$port/health" >"$health_file" 2>/dev/null; then
    ok "API health endpoint на порту $port"
    if command -v python3 >/dev/null 2>&1; then
      python3 -m json.tool "$health_file" 2>/dev/null || {
        cat "$health_file"
        warn "health endpoint вернул некорректный JSON"
      }
    else
      cat "$health_file"
    fi
  else
    err "API не отвечает на http://127.0.0.1:$port/health"
  fi

  # Определяем фактически подключённый volume через контейнер БД.
  # Это работает и для обычного Compose volume, и для external volume из override.
  db_id="$(docker compose --env-file .env ps -q db 2>/dev/null || true)"
  volume=""
  if [[ -n "$db_id" ]]; then
    volume="$(docker inspect "$db_id" --format '{{range .Mounts}}{{if eq .Destination "/var/lib/postgresql/data"}}{{.Name}}{{end}}{{end}}' 2>/dev/null || true)"
  fi
  [[ -n "$volume" ]] && ok "PostgreSQL volume: $volume" || warn "не удалось определить PostgreSQL volume контейнера db"

  if $FULL && [[ -n "$api_key" ]] && command -v curl >/dev/null 2>&1 && command -v python3 >/dev/null 2>&1; then
    caps_file="$TMP_DIR/caps.xml"
    if curl --fail --silent --show-error --get \
      --connect-timeout 3 --max-time 20 \
      --data-urlencode 't=caps' \
      --data-urlencode "apikey=$api_key" \
      "http://127.0.0.1:$port/torznab/api" >"$caps_file" 2>/dev/null; then

      if python3 - "$caps_file" <<'PY'
import sys
import xml.etree.ElementTree as ET

root = ET.parse(sys.argv[1]).getroot()
assert root.tag == "caps"
assert root.find("./categories/category/subcat[@id='3030']") is not None
assert root.find("./searching/book-search[@available='yes']") is not None
PY
      then
        ok "Torznab caps"
      else
        err "Torznab caps вернул неожиданный XML"
      fi
    else
      err "Torznab caps недоступен"
    fi
  fi
fi

[[ -x /usr/local/sbin/audiobookred-source ]] && ok "CLI установлен" || warn "CLI /usr/local/sbin/audiobookred-source не установлен"

if [[ -f /etc/default/audiobookred ]]; then
  ok "/etc/default/audiobookred установлен"
  expected_root_line="AUDIOBOOKRED_ROOT=$ROOT"
  if grep -Fxq "$expected_root_line" /etc/default/audiobookred; then
    ok "системный путь совпадает с checkout"
  else
    warn "AUDIOBOOKRED_ROOT в /etc/default/audiobookred не совпадает с $ROOT"
  fi
else
  warn "/etc/default/audiobookred отсутствует"
fi

if [[ -f /etc/cron.d/audiobookred ]]; then
  ok "cron установлен"
  if grep -Eq '^17[[:space:]]+4[[:space:]]+\*[[:space:]]+\*[[:space:]]+\*[[:space:]]+root[[:space:]].*audiobookred-source rutracker latest' /etc/cron.d/audiobookred; then
    ok "rutracker latest: ежедневно в 04:17"
  elif grep -Fq 'audiobookred-source rutracker latest' /etc/cron.d/audiobookred; then
    warn "rutracker latest использует другое расписание; рекомендуется ежедневно в 04:17"
  else
    warn "cron-задача rutracker latest не найдена"
  fi
else
  warn "cron не установлен"
fi
[[ -f /etc/logrotate.d/audiobookred ]] && ok "logrotate установлен" || warn "logrotate не установлен"

if $FULL && command -v docker >/dev/null 2>&1; then
  printf '\nDocker disk usage:\n'
  docker system df || true
  printf '\nСамые большие Docker JSON-логи:\n'
  find /var/lib/docker/containers -type f -name '*-json.log' -printf '%s %p\n' 2>/dev/null \
    | sort -nr | head -n 10 | awk '{printf "%.1f MiB %s\n", $1/1024/1024, $2}' || true
fi

printf '\nИтог: ошибок %d, предупреждений %d\n' "$errors" "$warnings"
(( errors == 0 ))
