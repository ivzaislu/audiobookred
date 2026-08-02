# Контракт модулей источников

Версия архитектурной границы: 0.22.0.

## Назначение

Модуль источника отделяет общую очередь, runtime-настройки и HTTP-маршруты
AudioBookRed от конкретного парсера. RuTracker остаётся единственным активным
источником в 0.22.0, но регистрируется через тот же контракт, который должен
использовать второй источник.

## Состав модуля

`ISourceModule` описывает статические свойства:

- стабильный ключ источника;
- отображаемое имя;
- категории;
- runtime-настройки по умолчанию;
- возможности адаптера.

`ISourceCrawler` описывает управляющие операции:

- bootstrap discovery;
- incremental enqueue;
- queue worker;
- reconcile;
- pause, resume и reset;
- retry;
- status, events, settings, completeness и maintenance.

`SourceRegistry` проверяет при старте:

- корректность ключей;
- отсутствие дубликатов;
- наличие ровно одного crawler для каждого module;
- отсутствие crawler без module.

## HTTP

Общие операции регистрируются один раз через маршруты:

```text
/api/v1/sources/{source}/...
```

Существующие URL RuTracker не меняются, потому что `{source}` принимает
`rutracker`. Специфичные transport, Atom и legacy-маршруты RuTracker пока
остаются отдельными.

Добавлен защищённый endpoint:

```text
GET /api/v1/sources
```

Он возвращает зарегистрированные модули, их категории, defaults и capabilities.

Неизвестный источник получает `404 unknown_source` со списком доступных ключей.

## Runtime-настройки

`SourceSettingsRepository` больше не использует значения RuTracker для любого
переданного ключа. Defaults выбираются из зарегистрированного `ISourceModule`.
При старте строки настроек создаются для всех зарегистрированных модулей через
`ON CONFLICT DO NOTHING`, поэтому пользовательские значения не перезаписываются.

## Что намеренно не меняется

- таблицы и существующие данные PostgreSQL;
- ключ `rutracker`;
- cron и CLI;
- RuTracker topic, Atom и magnet pipeline;
- `.env`;
- алгоритм bootstrap, incremental и reconcile;
- формат существующих ответов RuTracker.

## Следующий этап

Перед добавлением второго источника нужно реализовать его `ISourceModule` и
`ISourceCrawler`, затем добавить contract-тесты на изоляцию двух source-ключей.
Для источника без модели `category + page` потребуется отдельно обобщить payload
page jobs.
