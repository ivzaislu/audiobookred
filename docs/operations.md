# Эксплуатация AudioBookRed

Все команды предполагают установку в `/opt/audiobookred`. Для другого каталога замените путь.

## Состояние контейнеров

```bash
cd /opt/audiobookred
docker compose --env-file .env ps
docker compose --env-file .env logs --tail=200 api
```

Проверка приложения:

```bash
curl -fsS http://127.0.0.1:9117/health | python3 -m json.tool
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

Значение `work N` в `/etc/cron.d/audiobookred` должно соответствовать `workerJobLimit`.

## Atom discovery worker

Atom worker по умолчанию отключён. Он получает свежие темы из Atom feed и передаёт их в основной pipeline RuTracker:

```text
Atom feed
  → source_topic_jobs
  → RuTracker detail processor
  → magnet и метаданные
  → audiobook_releases
```

Он не создаёт отдельную параллельную базу и использует те же ограничения, повторные попытки и дедупликацию, что и обычный crawler.

Включение:

```bash
cd /opt/audiobookred
sed -i 's/^RUTRACKER_ATOM_ENABLED=.*/RUTRACKER_ATOM_ENABLED=true/' .env
docker compose --env-file .env up -d --force-recreate api
```

Статус:

```bash
API_KEY="$(sed -n 's/^API_KEY=//p' .env | tail -1)"

curl -fsS \
  -H "X-Api-Key: $API_KEY" \
  http://127.0.0.1:9117/api/v1/sources/rutracker/atom/status \
  | python3 -m json.tool
```

Ручной запуск одного прохода:

```bash
curl -fsS -X POST \
  -H "X-Api-Key: $API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"maxEntries":20}' \
  http://127.0.0.1:9117/api/v1/sources/rutracker/atom/import \
  | python3 -m json.tool
```

Параллельные ручной и фоновый проходы не выполняются: второй запрос получает `409 Conflict`.

`RUTRACKER_MAGNET_ENABLED` пока следует оставлять `false`. Отдельный legacy Magnet worker не используется; magnet получает основной detail processor из очереди тем.

## Обновление

```bash
cd /opt/audiobookred
sudo bash update.sh
```

Дополнительные параметры:

```bash
sudo bash update.sh --no-backup
sudo bash update.sh --no-prune
sudo bash update.sh --branch main
```

`update.sh` отказывается обновлять checkout с локальными изменениями и использует только fast-forward pull.

## Диагностика

```bash
cd /opt/audiobookred
sudo bash doctor.sh
sudo bash doctor.sh --full
```

Проверяются Git checkout, `.env`, Docker Compose, API, PostgreSQL, подключённый volume, CLI, cron и logrotate.

## Резервное копирование

```bash
cd /opt/audiobookred
sudo bash backup-db.sh
```

Оставить только пять последних дампов:

```bash
sudo bash backup-db.sh --keep 5
```

Дампы и файлы SHA-256 сохраняются в `backups/`. Перед завершением дамп проверяется через `pg_restore -l`.

Рекомендуется регулярно копировать каталог `backups/` за пределы сервера.

## Восстановление

Восстановление полностью заменяет текущую базу:

```bash
cd /opt/audiobookred
sudo bash restore-db.sh backups/audiobookred-YYYYMMDD-HHMMSS.dump --yes
```

По умолчанию сначала создаётся страховочный дамп текущей базы. API останавливается на время восстановления и запускается после успешной операции.

## Расписание

`install.sh` устанавливает `/etc/cron.d/audiobookred`:

- worker очереди — каждую минуту;
- получение новых раздач — каждый час;
- обновление статистики — каждые 10 минут;
- повтор упавших страниц — каждый час;
- обслуживание — ежедневно.

Полный импорт автоматически не запускается:

```bash
sudo audiobookred-source rutracker discover
```

Для замены изменённого вручную cron шаблоном:

```bash
cd /opt/audiobookred
sudo bash install.sh --no-start --replace-cron
```

## Логи

```bash
docker compose --env-file .env logs -f --tail=200 api
sudo tail -f /var/log/audiobookred-rutracker-worker.log
sudo tail -f /var/log/audiobookred-rutracker-latest.log
sudo tail -f /var/log/audiobookred-rutracker-retry.log
sudo tail -f /var/log/audiobookred-maintenance.log
```

## Безопасная очистка места

```bash
docker builder prune -a -f
docker image prune -f
docker container prune -f
journalctl --vacuum-size=200M
```

Не удаляйте `/var/lib/docker/overlay2` вручную. Не используйте команды, удаляющие Docker volumes.

## Удаление

Удалить системную интеграцию и остановить контейнеры, сохранив код и базу:

```bash
cd /opt/audiobookred
sudo bash uninstall.sh
```

Удалить также каталог проекта:

```bash
sudo bash uninstall.sh --remove-code
```

Удаление данных PostgreSQL требует отдельного параметра и интерактивного подтверждения:

```bash
sudo bash uninstall.sh --purge-data --remove-code
```

Перед удалением данных обязательно создайте и сохраните резервную копию.

<!-- patch-06-atom-dedup -->
## Atom discovery без лишних запросов к Worker

Начиная с версии 0.20.0 Atom-проход только обнаруживает темы. Состояние
`topic_id + fingerprint(title, size, URL)` хранится в PostgreSQL, поэтому
повторные записи feed получают статус `skipped` и не открывают `viewtopic.php`.
Новые и действительно изменившиеся темы ставятся в `source_topic_jobs`, после
чего обычный минутный worker получает magnet с существующими retry/lease.

Рекомендуемый режим:

```dotenv
RUTRACKER_ATOM_ENABLED=true
RUTRACKER_ATOM_INTERVAL_MINUTES=15
RUTRACKER_ATOM_MAX_ENTRIES=10
RUTRACKER_MAGNET_ENABLED=false
```

Контрольный `rutracker latest` запускается ежедневно в 04:17 и проверяет две
первые страницы каждой категории. Он обновляет сиды и личи из `viewforum.php`
без detail-запроса для каждой темы.

<!-- patch-07-startup-migrations -->
## Одноразовые миграции базы

Версия 0.20.1 отделяет быструю инициализацию схемы от операций по всему каталогу.
Migration runner использует `app_migrations` и PostgreSQL advisory lock. На уже
подготовленной базе состояние принимается без повторного обновления всех строк.

Настройки:

```dotenv
DB_MIGRATION_TIMEOUT_SECONDS=600
AUDIOBOOKRED_STARTUP_TIMEOUT_SECONDS=900
```

Основные ключи миграций:

```text
audiobook-search-text-v1
audiobook-normalized-series-v1
audiobook-magnet-required-v1
audiobook-infohash-dedup-v1
audiobook-core-indexes-v1
```

Проверка после обновления:

```bash
bash doctor.sh --full
docker compose --env-file .env logs --since=10m api | grep 'Database migration'
```
