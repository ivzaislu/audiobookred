# Управляемый reparse метаданных RuTracker

Версия: 0.23.1.

## Назначение

Reparse повторно загружает страницу уже известной темы и пропускает её через
актуальный detail-парсер. Он не удаляет запись, magnet или историю очереди.

Постановка заданий и запуск worker разделены. Команда enqueue сама не начинает
сетевую обработку.

## Точечная проверка

```bash
audiobookred-source rutracker reparse \
  6889513 6809133 5887830 5848939

audiobookred-source rutracker work 1
audiobookred-source rutracker metadata-status
```

Допустимы пробелы и запятые. Повторяющиеся `topic_id` удаляются с сохранением
порядка. За один запрос принимается не более 100 уникальных идентификаторов.

По умолчанию уже обработанная текущей версией парсера запись не ставится в
очередь. Для диагностического повторного чтения:

```bash
audiobookred-source rutracker reparse --force 6889513
```

## Ограниченный backfill

```bash
audiobookred-source rutracker reparse-stale 25
audiobookred-source rutracker work 1
```

`reparse-stale` выбирает только существующие записи RuTracker с magnet и
`metadata_parser_version` ниже текущей версии. Допустимый размер партии —
1..100, значение по умолчанию — 25.

Записи со статусом `running` не прерываются. Уже стоящие в `pending` или `retry`
не выбираются повторно автоматическим batch.

## Статус

```bash
audiobookred-source rutracker metadata-status
```

Поля:

- `total` — существующие записи RuTracker с magnet;
- `current` — обработанные текущей версией detail-парсера;
- `stale` — ещё не обработанные текущей версией;
- `queued` — stale-темы в `pending` или `retry`;
- `running` — stale-темы, которые сейчас обрабатывает worker.

## Ограничения безопасности

- скрытый массовый запуск отсутствует;
- cron не меняется;
- batch не превышает 100 тем;
- worker использует существующие concurrency, delay, retry и lease;
- Docker volume, `.env`, база и существующие magnet-ссылки не пересоздаются;
- для остановки достаточно не запускать следующий worker: уже поставленные
  задания сохранятся и продолжатся штатным cron-worker.
