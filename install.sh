#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
START=true
INSTALL_CRON=true

for arg in "$@"; do
  case "$arg" in
    --no-start) START=false ;;
    --no-cron) INSTALL_CRON=false ;;
    *) echo "Неизвестный параметр: $arg" >&2; exit 2 ;;
  esac
done

[[ $EUID -eq 0 ]] || { echo "Запустите от root." >&2; exit 1; }
command -v docker >/dev/null 2>&1 || { echo "Docker не установлен." >&2; exit 1; }
docker compose version >/dev/null 2>&1 || { echo "Требуется Docker Compose v2." >&2; exit 1; }
command -v curl >/dev/null 2>&1 || { echo "curl не установлен." >&2; exit 1; }
command -v python3 >/dev/null 2>&1 || { echo "python3 не установлен." >&2; exit 1; }

cd "$ROOT"
chmod +x install.sh backup-db.sh scripts/audiobookred-source

if [[ ! -f .env ]]; then
  cp .env.example .env
  if command -v openssl >/dev/null 2>&1; then
    api_key="$(openssl rand -hex 32)"
    db_password="$(openssl rand -hex 24)"
    sed -i "s/^API_KEY=.*/API_KEY=$api_key/" .env
    sed -i "s/^DB_PASSWORD=.*/DB_PASSWORD=$db_password/" .env
  fi
  chmod 600 .env
  echo "Создан $ROOT/.env. Проверьте настройки RuTracker перед первым импортом."
fi

api_key="$(sed -n 's/^API_KEY=//p' .env | tail -1 | tr -d '\r\n')"
db_password="$(sed -n 's/^DB_PASSWORD=//p' .env | tail -1 | tr -d '\r\n')"
if [[ -z "$api_key" || "$api_key" == "change-me" || -z "$db_password" || "$db_password" == "change-me" ]]; then
  echo "Задайте безопасные API_KEY и DB_PASSWORD в $ROOT/.env." >&2
  exit 3
fi

# Сохраняем фактический путь проекта для установленного CLI.
cat > /etc/default/audiobookred <<ENV
AUDIOBOOKRED_ROOT=$ROOT
AUDIOBOOKRED_URL=http://127.0.0.1:9117
ENV
chmod 644 /etc/default/audiobookred
install -m 0755 scripts/audiobookred-source /usr/local/sbin/audiobookred-source

if $INSTALL_CRON; then
  install -m 0644 cron/audiobookred.cron.example /etc/cron.d/audiobookred
  install -m 0644 cron/audiobookred.logrotate.example /etc/logrotate.d/audiobookred
fi

mkdir -p /var/log
for file in \
  /var/log/audiobookred-rutracker-worker.log \
  /var/log/audiobookred-rutracker-latest.log \
  /var/log/audiobookred-rutracker-retry.log \
  /var/log/audiobookred-maintenance.log; do
  touch "$file"
done

if ! $START; then
  echo "CLI и системные задачи установлены."
  exit 0
fi

df -h /
docker compose up -d --build --force-recreate

echo "Ожидание API..."
for _ in $(seq 1 90); do
  if curl -fsS http://127.0.0.1:9117/health >/tmp/audiobookred-health.json 2>/dev/null; then
    python3 -m json.tool /tmp/audiobookred-health.json
    echo "UI: http://$(hostname -I | awk '{print $1}'):9117/ui/"
    exit 0
  fi
  sleep 2
done

echo "API не ответил за 180 секунд. Последние логи:" >&2
docker compose logs --tail=200 api >&2
exit 4
