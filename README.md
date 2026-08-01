# AudioBookRed

AudioBookRed — самостоятельный агрегатор метаданных аудиокниг на .NET 9 и PostgreSQL. Проект индексирует источники через очереди, хранит torrent-метаданные, предоставляет REST API и браузерный интерфейс с взаимозависимыми фасетными фильтрами.

Текущая версия: **0.17.5**.

## Возможности

- полнотекстовый и фасетный поиск по всей базе;
- взаимозависимые фильтры по автору, чтецу, серии, формату, качеству, году и источнику;
- канонизация авторов, чтецов и серий с поддержкой псевдонимов;
- раздельное хранение названия серии и номера книги;
- дедупликация по `info_hash`;
- обязательная magnet-ссылка для публичного каталога;
- PostgreSQL-очереди страниц и отдельных тем с lease/retry;
- полный `discover`, восстановительный `reconcile` и почасовой `latest`;
- статистика, журнал событий и контроль полноты импорта;
- RuTracker через прямое соединение, прокси или собственный Cloudflare Worker;
- Docker Compose, cron, logrotate и резервное копирование PostgreSQL.

Проект вдохновлён архитектурными идеями JacRed, но реализован как отдельное приложение для каталога аудиокниг.

## Требования

- Linux-сервер;
- Docker Engine;
- Docker Compose v2;
- `curl`, `python3`;
- свободный порт `9117`.

Перед сборкой проверьте свободное место:

```bash
df -h /
```

Не удаляйте Docker volumes командами `docker volume prune`, `docker compose down -v` или `docker system prune --volumes`: в volume хранится PostgreSQL.

## Быстрый запуск

```bash
git clone <URL_РЕПОЗИТОРИЯ> AudioBookRed
cd AudioBookRed
sudo ./install.sh
```

При первом запуске `install.sh` создаёт `.env`, генерирует `API_KEY` и `DB_PASSWORD`, устанавливает CLI, cron и logrotate, затем собирает контейнеры.

Проверьте `.env` перед импортом RuTracker:

```bash
nano .env
```

UI:

```text
http://SERVER_IP:9117/ui/
```

Проверка API:

```bash
curl -sS http://127.0.0.1:9117/health | python3 -m json.tool
```

Ожидаемый ответ:

```json
{
  "status": "ok",
  "service": "audiobookred",
  "version": "0.17.5-audiobookred"
}
```

## Настройка RuTracker

### Cloudflare Worker

Рекомендуемый вариант — развернуть `cloudflare-worker/index.js` в своём Cloudflare Worker и создать secret:

```text
PROXY_TOKEN
```

После развёртывания заполните:

```dotenv
RUTRACKER_ALIAS_URL=https://YOUR-WORKER.workers.dev
RUTRACKER_ALIAS_TOKEN=YOUR_PROXY_TOKEN
```

Worker разрешает только:

```text
/forum/viewforum.php
/forum/viewtopic.php
/forum/tracker.php
```

### Прямой режим

Без `RUTRACKER_ALIAS_URL` приложение использует прямую авторизованную сессию:

```dotenv
RUTRACKER_USERNAME=
RUTRACKER_PASSWORD=
```

При необходимости задайте `RUTRACKER_PROXY_URL` и данные прокси.

После изменения `.env` пересоздайте API:

```bash
docker compose up -d --force-recreate api
```

## Первый полный импорт

```bash
sudo audiobookred-source rutracker discover
```

`discover` определяет число страниц во всех настроенных категориях и ставит их в PostgreSQL-очередь. Установленный cron-worker обрабатывает очередь каждую минуту.

Наблюдение:

```bash
sudo audiobookred-source rutracker status
sudo audiobookred-source rutracker completeness
tail -f /var/log/audiobookred-rutracker-worker.log
```

Восстановительный проход без очистки каталога:

```bash
sudo audiobookred-source rutracker reconcile
```

Почасовая проверка первых страниц:

```bash
sudo audiobookred-source rutracker latest
```

## CLI источников

```bash
sudo audiobookred-source rutracker status
sudo audiobookred-source rutracker events 30
sudo audiobookred-source rutracker completeness
sudo audiobookred-source rutracker discover
sudo audiobookred-source rutracker reconcile
sudo audiobookred-source rutracker latest
sudo audiobookred-source rutracker retry-failed
sudo audiobookred-source rutracker retry-topics
sudo audiobookred-source rutracker settings
sudo audiobookred-source rutracker stats-refresh
```

Настройки производительности применяются без пересборки:

```bash
sudo audiobookred-source rutracker set workerJobLimit 4
sudo audiobookred-source rutracker set pageConcurrency 4
sudo audiobookred-source rutracker set detailConcurrency 4
sudo audiobookred-source rutracker set requestDelayMilliseconds 150
```

Значение `work N` в `/etc/cron.d/audiobookred` должно соответствовать выбранному `workerJobLimit`.

## REST API

Все маршруты, кроме `/health` и Swagger, требуют заголовок:

```text
X-Api-Key: <API_KEY>
```

Основные endpoints:

```text
GET  /api/v1/search
GET  /api/v1/releases
POST /api/v1/releases
POST /api/v1/parse-title
GET  /api/v1/stats
POST /api/v1/stats/refresh
GET  /api/v1/sources/rutracker/crawl/status
GET  /api/v1/sources/rutracker/completeness
POST /api/v1/sources/rutracker/bootstrap/discover
POST /api/v1/sources/rutracker/reconcile
POST /api/v1/sources/rutracker/incremental/enqueue
POST /api/v1/sources/rutracker/work
```

Swagger:

```text
http://SERVER_IP:9117/swagger
```

Пример фасетного поиска:

```bash
API_KEY="$(sed -n 's/^API_KEY=//p' .env)"

curl -sS -G \
  -H "X-Api-Key: $API_KEY" \
  --data-urlencode "q=лукьяненко" \
  --data-urlencode "limit=100" \
  http://127.0.0.1:9117/api/v1/search \
  | python3 -m json.tool
```

## Расписание

`install.sh` устанавливает `/etc/cron.d/audiobookred`:

- worker очереди — каждую минуту;
- `latest` — каждый час в `:17`;
- статистика — каждые 10 минут;
- повтор упавших страниц — каждый час в `:23`;
- обслуживание — ежедневно в `03:42`.

Полный `discover` автоматически не запускается.

## Логи

```bash
docker compose logs -f --tail=200 api
tail -f /var/log/audiobookred-rutracker-worker.log
tail -f /var/log/audiobookred-rutracker-latest.log
tail -f /var/log/audiobookred-rutracker-retry.log
tail -f /var/log/audiobookred-maintenance.log
```

Docker-логи ограничены ротацией `10m × 3` для каждого контейнера.

## Резервная копия

```bash
sudo ./backup-db.sh
```

Дамп и SHA256 создаются в каталоге `backups/`.

Восстановление дампа:

```bash
docker compose exec -T db sh -lc \
  'pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --clean --if-exists' \
  < backups/audiobookred-YYYYMMDD-HHMMSS.dump
```

## Структура репозитория

```text
AudioBookRed/
├── src/AudioBookRed.Api/       API, crawler и UI
├── scripts/audiobookred-source CLI управления источниками
├── cloudflare-worker/          Worker-прокси RuTracker
├── cron/                       cron и logrotate
├── docker-compose.yml
├── Dockerfile
├── install.sh
└── backup-db.sh
```
