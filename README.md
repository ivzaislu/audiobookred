# AudioBookRed

AudioBookRed — агрегатор метаданных аудиокниг на .NET 9 и PostgreSQL. Проект индексирует источники через очереди, хранит torrent-метаданные, предоставляет REST API и браузерный интерфейс с взаимозависимыми фасетными фильтрами.

Репозиторий: `ivzaislu/audiobookred`
Текущая версия приложения: **0.17.5**

## Возможности

- полнотекстовый и фасетный поиск;
- фильтры по автору, чтецу, серии, формату, качеству, году и источнику;
- канонизация авторов, чтецов и серий;
- дедупликация по `info_hash`;
- PostgreSQL-очереди страниц и отдельных тем с lease/retry;
- полный `discover`, восстановительный `reconcile` и почасовой `latest`;
- RuTracker через прямое соединение, прокси или собственный Cloudflare Worker;
- Docker Compose, cron, logrotate, диагностика, обновление и резервное копирование.

Проект вдохновлён архитектурными идеями JacRed, но реализован как отдельное приложение для каталога аудиокниг.

## Требования

- Linux-сервер;
- Docker Engine;
- Docker Compose v2;
- `git`, `curl`, `python3`;
- не менее 3 ГБ свободного места для первой Docker-сборки;
- свободный порт `9117` либо другой порт в `.env`.

Проверка:

```bash
docker --version
docker compose version
df -h /
```

Не используйте `docker volume prune`, `docker compose down -v` и `docker system prune --volumes`: PostgreSQL хранится в Docker volume.

## Установка из GitHub

### Автоматическая установка

Скрипт клонирует ветку `main` в `/opt/audiobookred`, создаёт `.env`, генерирует секреты, устанавливает CLI, cron и logrotate, затем собирает контейнеры:

```bash
curl -fsSL \
  https://raw.githubusercontent.com/ivzaislu/audiobookred/main/scripts/install-from-github.sh \
  | sudo bash
```

Другой каталог:

```bash
curl -fsSL \
  https://raw.githubusercontent.com/ivzaislu/audiobookred/main/scripts/install-from-github.sh \
  | sudo bash -s -- --dir /srv/audiobookred
```

### Обычный git clone

```bash
sudo git clone https://github.com/ivzaislu/audiobookred.git /opt/audiobookred
cd /opt/audiobookred
sudo bash install.sh
```

GitHub может не сохранять исполняемый бит при загрузке через веб-интерфейс, поэтому в документации скрипты запускаются через `bash`.

## Переход существующей установки на GitHub

Для установки, ранее распакованной из ZIP или патча, выполните:

```bash
curl -fsSL \
  https://raw.githubusercontent.com/ivzaislu/audiobookred/main/scripts/migrate-existing-install.sh \
  | sudo bash -s -- /root/AudioBookRed
```

Скрипт:

1. создаёт резервную копию PostgreSQL;
2. перемещает старый каталог в `/root/AudioBookRed.pre-git-ДАТА`;
3. клонирует этот репозиторий на прежнее место;
4. переносит `.env`, `backups/` и Compose override;
5. запускает установку.

Docker volume не удаляется и не копируется. Благодаря фиксированному Compose project `audiobookred` новая Git-установка использует существующую базу.

## Конфигурация

Установщик создаёт `.env` из `.env.example`. Секреты не должны попадать в Git.

```bash
sudo nano /opt/audiobookred/.env
```

Основные параметры:

```dotenv
API_KEY=...
DB_PASSWORD=...
AUDIOBOOKRED_PORT=9117

RUTRACKER_ALIAS_URL=https://YOUR-WORKER.workers.dev
RUTRACKER_ALIAS_TOKEN=YOUR_PROXY_TOKEN
```

Для прямого режима вместо Worker:

```dotenv
RUTRACKER_BASE_URL=https://rutracker.org
RUTRACKER_USERNAME=
RUTRACKER_PASSWORD=
```

После изменения `.env`:

```bash
cd /opt/audiobookred
sudo docker compose --env-file .env up -d --force-recreate api
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

## Первый импорт RuTracker

```bash
sudo audiobookred-source rutracker discover
```

Наблюдение:

```bash
sudo audiobookred-source rutracker status
sudo audiobookred-source rutracker completeness
sudo tail -f /var/log/audiobookred-rutracker-worker.log
```

Восстановительный проход:

```bash
sudo audiobookred-source rutracker reconcile
```

Почасовая проверка новых раздач уже установлена в cron. Ручной запуск:

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

Значение `work N` в `/etc/cron.d/audiobookred` должно соответствовать `workerJobLimit`.

## Обновление из репозитория

```bash
cd /opt/audiobookred
sudo bash update.sh
```

`update.sh`:

- проверяет официальный `origin`;
- отказывается обновлять checkout с локальными изменениями;
- создаёт дамп базы;
- выполняет `git pull --ff-only`;
- пересобирает API;
- сохраняет `.env`, PostgreSQL volume и изменённый вручную cron;
- проверяет `/health`;
- очищает только неиспользуемый build cache и dangling images.

Параметры:

```bash
sudo bash update.sh --no-backup
sudo bash update.sh --no-prune
sudo bash update.sh --branch main
```

## Диагностика

```bash
cd /opt/audiobookred
sudo bash doctor.sh
sudo bash doctor.sh --full
```

Проверяются Git remote, свободное место, `.env`, Docker Compose, API, PostgreSQL, volume, CLI, cron и logrotate. Режим `--full` также показывает Docker disk usage и крупные JSON-логи.

## Резервное копирование

```bash
cd /opt/audiobookred
sudo bash backup-db.sh
```

Оставить только пять последних дампов:

```bash
sudo bash backup-db.sh --keep 5
```

Дамп и SHA256 создаются в `backups/`. Перед завершением скрипт проверяет дамп через `pg_restore -l`.

## Восстановление базы

Операция полностью заменяет текущую базу:

```bash
cd /opt/audiobookred
sudo bash restore-db.sh backups/audiobookred-YYYYMMDD-HHMMSS.dump --yes
```

По умолчанию перед восстановлением создаётся страховочный дамп. API останавливается на время операции. При наличии `.sha256` контрольная сумма проверяется автоматически.

## Удаление

Остановить контейнеры и удалить системную интеграцию, сохранив базу и исходники:

```bash
cd /opt/audiobookred
sudo bash uninstall.sh
```

Удалить также каталог проекта:

```bash
sudo bash uninstall.sh --remove-code
```

Удаление PostgreSQL volume возможно только с отдельным параметром и ручным вводом подтверждающей фразы:

```bash
sudo bash uninstall.sh --purge-data --remove-code
```

## Безопасная очистка места

```bash
docker builder prune -a -f
docker image prune -f
docker container prune -f
journalctl --vacuum-size=200M
```

Не удаляйте `/var/lib/docker/overlay2` вручную и не очищайте Docker volumes.

## Расписание

`install.sh` устанавливает `/etc/cron.d/audiobookred`:

- worker очереди — каждую минуту;
- `latest` — каждый час в `:17`;
- статистика — каждые 10 минут;
- повтор упавших страниц — каждый час в `:23`;
- обслуживание — ежедневно в `03:42`.

При повторном запуске установщик сохраняет существующий cron. Для возврата к шаблону:

```bash
sudo bash install.sh --no-start --replace-cron
```

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

## Структура репозитория

```text
audiobookred/
├── src/AudioBookRed.Api/          API, crawler и UI
├── scripts/audiobookred-source    CLI управления источниками
├── scripts/install-from-github.sh установка из GitHub
├── scripts/migrate-existing-install.sh
├── cloudflare-worker/             Worker-прокси RuTracker
├── cron/                          cron и logrotate
├── docker-compose.yml
├── Dockerfile
├── install.sh
├── update.sh
├── doctor.sh
├── backup-db.sh
├── restore-db.sh
└── uninstall.sh
```
