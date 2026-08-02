# AudioBookRed

AudioBookRed — самостоятельный каталог и поисковый сервис метаданных аудиокниг на .NET 9 и PostgreSQL.

Приложение индексирует RuTracker, сохраняет метаданные раздач, предоставляет браузерный интерфейс, собственный REST API и Torznab API для совместимых клиентов.

## Возможности

- полнотекстовый и фасетный поиск;
- фильтры по автору, чтецу, серии, формату, качеству, году и источнику;
- канонизация авторов, чтецов и серий;
- дедупликация по `info_hash`;
- PostgreSQL-очереди с повторными попытками;
- полный импорт, восстановительный проход и регулярное получение новых раздач;
- прямое подключение к RuTracker, proxy или Cloudflare Worker;
- Torznab API с категорией `3030 Audio/Audiobook`;
- Docker Compose, CLI, cron, logrotate, диагностика и резервное копирование.

## Требования

- Linux-сервер;
- Docker Engine;
- Docker Compose v2;
- `git`, `curl`, `python3`;
- не менее 3 ГБ свободного места для первой сборки;
- свободный порт `9117` либо другой порт, заданный в `.env`.

Не используйте `docker compose down -v`, `docker volume prune` и `docker system prune --volumes`: база PostgreSQL хранится в Docker volume.

## Установка

```bash
sudo git clone https://github.com/ivzaislu/audiobookred.git /opt/audiobookred
cd /opt/audiobookred
sudo bash install.sh
```

Автоматическая установка:

```bash
curl -fsSL \
  https://raw.githubusercontent.com/ivzaislu/audiobookred/main/scripts/install-from-github.sh \
  | sudo bash
```

Установщик создаёт `.env`, генерирует `API_KEY` и `DB_PASSWORD`, устанавливает CLI, cron и logrotate, собирает и запускает контейнеры.

## Конфигурация

```bash
sudo nano /opt/audiobookred/.env
```

Основные параметры:

```dotenv
API_KEY=...
DB_PASSWORD=...
AUDIOBOOKRED_PORT=9117

RUTRACKER_BASE_URL=https://rutracker.org
RUTRACKER_USERNAME=
RUTRACKER_PASSWORD=
```

Для собственного Worker:

```dotenv
RUTRACKER_ALIAS_URL=https://YOUR-WORKER.workers.dev
RUTRACKER_ALIAS_TOKEN=YOUR_PROXY_TOKEN
```

После изменения `.env`:

```bash
cd /opt/audiobookred
sudo docker compose --env-file .env up -d --force-recreate api
```

## Запуск и проверка

UI:

```text
http://SERVER_IP:9117/ui/
```

Проверка API:

```bash
curl -fsS http://127.0.0.1:9117/health | python3 -m json.tool
```

Первый полный импорт:

```bash
sudo audiobookred-source rutracker discover
```

Состояние импорта:

```bash
sudo audiobookred-source rutracker status
sudo audiobookred-source rutracker completeness
sudo tail -f /var/log/audiobookred-rutracker-worker.log
```

## Torznab

URL индексатора:

```text
http://SERVER_IP:9117/torznab/api
```

API key должен совпадать с `API_KEY` из `.env`. Категория аудиокниг — `3030`.

Проверка:

```bash
cd /opt/audiobookred
bash scripts/test-torznab.sh
```

Подробное описание: [docs/torznab.md](docs/torznab.md).

## Обновление

```bash
cd /opt/audiobookred
sudo bash update.sh
```

Перед обновлением создаётся резервная копия базы, затем выполняется `git pull --ff-only`, пересборка и health check.

## Проверка изменений

Для каждого push и pull request GitHub Actions выполняет сборку .NET 9,
модульные тесты, проверку Bash-синтаксиса, `docker compose config` и сборку
Docker-образа.

Локально:

```bash
dotnet test tests/AudioBookRed.Api.Tests/AudioBookRed.Api.Tests.csproj -c Release
```

## Документация

- [Эксплуатация, резервное копирование и восстановление](docs/operations.md)
- [Собственный REST API](docs/api.md)
- [Torznab API](docs/torznab.md)

## Структура

```text
audiobookred/
├── src/AudioBookRed.Api/          приложение, API, индексатор и UI
├── scripts/audiobookred-source    CLI управления источниками
├── scripts/install-from-github.sh установка из GitHub
├── cloudflare-worker/             необязательный proxy для RuTracker
├── cron/                          cron и logrotate
├── docs/                          эксплуатационная документация
├── docker-compose.yml
├── Dockerfile
├── install.sh
├── update.sh
├── doctor.sh
├── backup-db.sh
├── restore-db.sh
└── uninstall.sh
```


### Быстрое Atom-обнаружение

Atom feed проверяется часто, но постоянная дедупликация в PostgreSQL не даёт
неизменённым темам расходовать Cloudflare Worker. Сиды и личи обновляются
суточным двухстраничным обходом категорий; новые/изменившиеся темы проходят
через общую очередь `source_topic_jobs`.


### Надёжный запуск и одноразовые миграции

Начиная с версии 0.20.1 обычный перезапуск выполняет только быструю проверку
схемы. Полные backfill, очистка дублей и построение основных индексов проходят
через `app_migrations`, защищены PostgreSQL advisory lock и не повторяются после
успешного завершения.
