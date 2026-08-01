# REST API AudioBookRed

Собственные REST-маршруты, кроме `/health`, требуют заголовок:

```text
X-Api-Key: <API_KEY>
```

Torznab API использует отдельные правила авторизации, описанные в [torznab.md](torznab.md).

## Состояние

```text
GET /health
```

Пример:

```bash
curl -fsS http://127.0.0.1:9117/health
```

## Каталог

```text
GET  /api/v1/search
GET  /api/v1/releases
POST /api/v1/releases
POST /api/v1/parse-title
GET  /api/v1/stats
POST /api/v1/stats/refresh
```

Пример фасетного поиска:

```bash
API_KEY="$(sed -n 's/^API_KEY=//p' .env | tail -1)"

curl -fsS -G \
  -H "X-Api-Key: $API_KEY" \
  --data-urlencode "q=лукьяненко" \
  --data-urlencode "limit=100" \
  http://127.0.0.1:9117/api/v1/search \
  | python3 -m json.tool
```

Основные параметры поиска:

```text
q
author
narrator
series
source
audioFormat
quality
year
magnet
sort
limit
```

## RuTracker

Состояние и диагностика:

```text
GET  /api/v1/sources/rutracker/status
GET  /api/v1/sources/rutracker/network/status
POST /api/v1/sources/rutracker/network/probe
GET  /api/v1/sources/rutracker/categories
GET  /api/v1/sources/rutracker/crawl/status
GET  /api/v1/sources/rutracker/completeness
GET  /api/v1/sources/rutracker/events
```

Управление очередями:

```text
POST /api/v1/sources/rutracker/bootstrap/discover
POST /api/v1/sources/rutracker/reconcile
POST /api/v1/sources/rutracker/incremental/enqueue
POST /api/v1/sources/rutracker/work
POST /api/v1/sources/rutracker/jobs/retry-failed
POST /api/v1/sources/rutracker/topics/retry-failed
POST /api/v1/sources/rutracker/maintenance
```

Настройки:

```text
GET /api/v1/sources/rutracker/settings
PUT /api/v1/sources/rutracker/settings
```

Для обычного администрирования предпочтительнее CLI `audiobookred-source`, поскольку он уже добавляет API key, таймауты и форматирование JSON.

## Ошибки авторизации

При отсутствующем или неверном ключе собственный REST API возвращает:

```json
{
  "error": "invalid_api_key"
}
```

со статусом `401 Unauthorized`.
