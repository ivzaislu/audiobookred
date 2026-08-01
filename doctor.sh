#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FULL=false

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
    [[ -z "$(git -C "$ROOT" status --porcelain --untracked-files=normal)" ]] \
      && ok "Git checkout без локальных изменений" || warn "В Git checkout есть локальные изменения"
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

  port="$(sed -n 's/^AUDIOBOOKRED_PORT=//p' .env | tail -n 1 | tr -d '\r\n')"
  port="${port:-9117}"
  if command -v curl >/dev/null 2>&1 && curl -fsS "http://127.0.0.1:$port/health" >/tmp/audiobookred-doctor-health.json 2>/dev/null; then
    ok "API health check на порту $port"
    python3 -m json.tool /tmp/audiobookred-doctor-health.json 2>/dev/null || cat /tmp/audiobookred-doctor-health.json
    rm -f /tmp/audiobookred-doctor-health.json
  else
    err "API не отвечает на http://127.0.0.1:$port/health"
  fi

  volume="$(docker volume ls --filter label=com.docker.compose.project=audiobookred --filter label=com.docker.compose.volume=audiobookred-db -q | head -n 1)"
  [[ -n "$volume" ]] && ok "PostgreSQL volume: $volume" || warn "volume audiobookred-db не найден по Compose labels"
fi

[[ -x /usr/local/sbin/audiobookred-source ]] && ok "CLI установлен" || warn "CLI /usr/local/sbin/audiobookred-source не установлен"
[[ -f /etc/default/audiobookred ]] && ok "/etc/default/audiobookred установлен" || warn "/etc/default/audiobookred отсутствует"
[[ -f /etc/cron.d/audiobookred ]] && ok "cron установлен" || warn "cron не установлен"
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
