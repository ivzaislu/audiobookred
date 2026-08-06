# REST API AudioBookRed

Собственные REST-маршруты, кроме `/health`, требуют заголовок:

```text
X-Api-Key: <API_KEY>
```

Torznab API использует отдельные правила авторизации, описанные в [torznab.md](torznab.md).

## Состояние

```text
GET /health
GET /health/live
GET /health/ready
```

`/health/live` проверяет, что процесс отвечает. `/health/ready` дополнительно
проверяет PostgreSQL и обязательные ключи `app_migrations`; Docker healthcheck
использует readiness endpoint.

Пример:

```bash
curl -fsS http://127.0.0.1:9117/health/ready
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

## Rutor

Rutor зарегистрирован под ключом `rutor` и по умолчанию выключен. Общие
маршруты имеют тот же формат:

```text
GET  /api/v1/sources/rutor/crawl/status
GET  /api/v1/sources/rutor/settings
PUT  /api/v1/sources/rutor/settings
POST /api/v1/sources/rutor/page-map
POST /api/v1/sources/rutor/bootstrap/discover
POST /api/v1/sources/rutor/incremental/enqueue
POST /api/v1/sources/rutor/reconcile
POST /api/v1/sources/rutor/work
GET  /api/v1/sources/rutor/completeness
GET  /api/v1/sources/rutor/metadata/status
POST /api/v1/sources/rutor/metadata/backfill
POST /api/v1/sources/rutor/metadata/reparse
POST /api/v1/sources/rutor/topics/retry-failed
```

Первое включение через CLI:

```bash
sudo audiobookred-source rutor set enabled true
sudo audiobookred-source rutor page-map
sudo audiobookred-source rutor latest
sudo audiobookred-source rutor work
```

Magnet/infohash, размер и пиры Rutor получаются прямо из listing. Detail worker
открывает `/torrent/{id}` только для новой раздачи, изменившегося названия или
размера, неудачной попытки либо metadata parser backfill. Он сохраняет автора,
чтецов, жанры, издательство, продолжительность, аудиоформат и битрейт.

## Ошибки авторизации

При отсутствующем или неверном ключе собственный REST API возвращает:

```json
{
  "error": "invalid_api_key"
}
```

со статусом `401 Unauthorized`.

## Удалённые legacy endpoints

Начиная с версии 0.20.2 старые metadata-only и отдельные Magnet endpoints
возвращают `410 Gone`, потому что они не создавали полноценные записи либо
дублировали основной pipeline:

```text
POST /api/v1/sources/rutracker/import
POST /api/v1/sources/rutracker/import-html
GET  /api/v1/sources/rutracker/magnets/status
POST /api/v1/sources/rutracker/magnets/import
POST /api/v1/sources/rutracker/magnets/reset-failures
```

Используйте `incremental/enqueue`, `bootstrap/discover`, `work` и
`topics/retry-failed`.

## Обновление карты страниц источника

`POST /api/v1/sources/{source}/page-map` повторно определяет количество страниц
категорий и обновляет `source_crawl_state.bootstrap_last_page`, не создавая
задания полного обхода.
