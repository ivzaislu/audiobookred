# Torznab API AudioBookRed

AudioBookRed предоставляет Torznab/Newznab-совместимый слой для Prowlarr и других совместимых клиентов.

## Адрес

Основной маршрут:

```text
http://SERVER_IP:9117/torznab/api
```

Дополнительные совместимые маршруты:

```text
/api/v2.0/indexers/audiobookred/results/torznab/api
/api/v2.0/indexers/all/results/torznab/api
/api/v1/indexer/audiobookred/newznab
/api/v1/indexer/all/newznab
```

## Авторизация

API key можно передать параметром:

```text
?apikey=<API_KEY>
```

или заголовком:

```text
X-Api-Key: <API_KEY>
```

Query-параметр `apikey` принимается только Torznab-маршрутами. Собственный REST API требует заголовок `X-Api-Key`.

## Возможности

```text
t=caps
t=indexers
t=search&q=...
t=booksearch&q=...&title=...&author=...&year=...
t=musicsearch&q=...&album=...&artist=...&year=...
t=audiosearch&q=...&album=...&artist=...&year=...
```

Категория выдачи:

```text
3030 Audio/Audiobook
```

Поддерживаются `limit` и `offset`. Максимальный `limit` — 250.

В XML передаются:

- magnet-ссылка;
- info hash;
- размер;
- сидеры, личеры и общее число peers;
- источник и язык;
- автор, название, серия и год;
- формат, битрейт и чтецы, когда эти данные известны.

## Проверка caps

```bash
cd /opt/audiobookred
API_KEY="$(sed -n 's/^API_KEY=//p' .env | tail -1)"

curl -fsS -G \
  --data-urlencode "t=caps" \
  --data-urlencode "apikey=$API_KEY" \
  http://127.0.0.1:9117/torznab/api
```

## Проверка поиска

```bash
curl -fsS -G \
  --data-urlencode "t=booksearch" \
  --data-urlencode "q=Злотников" \
  --data-urlencode "limit=5" \
  --data-urlencode "apikey=$API_KEY" \
  http://127.0.0.1:9117/torznab/api
```

Полная автоматическая проверка:

```bash
bash scripts/test-torznab.sh
```

## Настройка индексатора

```text
URL:      http://SERVER_IP:9117/torznab/api
API key:  значение API_KEY из .env
Category: 3030
```
