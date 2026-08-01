#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BASE_URL="${AUDIOBOOKRED_URL:-http://127.0.0.1:9117}"
ENV_FILE="${AUDIOBOOKRED_ENV:-$ROOT/.env}"

[[ -f "$ENV_FILE" ]] || { echo "Не найден $ENV_FILE" >&2; exit 1; }
command -v curl >/dev/null 2>&1 || { echo "curl не установлен" >&2; exit 1; }
command -v python3 >/dev/null 2>&1 || { echo "python3 не установлен" >&2; exit 1; }

API_KEY="$(sed -n 's/^API_KEY=//p' "$ENV_FILE" | tail -1 | tr -d '\r\n')"
[[ -n "$API_KEY" ]] || { echo "API_KEY отсутствует в $ENV_FILE" >&2; exit 1; }

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

curl_common=(
  --fail
  --silent
  --show-error
  --connect-timeout 5
  --max-time 30
)

curl "${curl_common[@]}" "$BASE_URL/health" >"$TMP_DIR/health.json"

python3 - "$TMP_DIR/health.json" <<'PY'
import json
import sys

payload = json.load(open(sys.argv[1], encoding="utf-8"))
assert payload.get("status") == "ok", payload
assert payload.get("service") == "audiobookred", payload
print(f"health: ok, version={payload.get('version', 'unknown')}")
PY

curl "${curl_common[@]}" -G \
  --data-urlencode 't=caps' \
  --data-urlencode "apikey=$API_KEY" \
  "$BASE_URL/torznab/api" >"$TMP_DIR/caps.xml"

python3 - "$TMP_DIR/caps.xml" <<'PY'
import sys
import xml.etree.ElementTree as ET

root = ET.parse(sys.argv[1]).getroot()
assert root.tag == "caps", root.tag
server = root.find("server")
assert server is not None and server.attrib.get("title") == "AudioBookRed"
book = root.find("./searching/book-search")
assert book is not None and book.attrib.get("available") == "yes"
assert root.find("./categories/category/subcat[@id='3030']") is not None
print("caps: ok")
PY

curl "${curl_common[@]}" -G \
  --data-urlencode 't=search' \
  --data-urlencode 'limit=1' \
  --data-urlencode "apikey=$API_KEY" \
  "$BASE_URL/torznab/api" >"$TMP_DIR/search.xml"

python3 - "$TMP_DIR/search.xml" <<'PY'
import sys
import xml.etree.ElementTree as ET

root = ET.parse(sys.argv[1]).getroot()
assert root.tag == "rss", root.tag
channel = root.find("channel")
assert channel is not None
response = channel.find("{http://www.newznab.com/DTD/2010/feeds/attributes/}response")
assert response is not None
assert "offset" in response.attrib and "total" in response.attrib
print(f"search: ok, total={response.attrib['total']}")
PY

status="$(
  curl --silent --show-error \
    --connect-timeout 5 --max-time 30 \
    -o "$TMP_DIR/error.xml" -w '%{http_code}' -G \
    --data-urlencode 't=caps' \
    --data-urlencode 'apikey=incorrect-key' \
    "$BASE_URL/torznab/api"
)"
[[ "$status" == "401" ]] || { echo "Ожидался HTTP 401, получен $status" >&2; exit 1; }

python3 - "$TMP_DIR/error.xml" <<'PY'
import sys
import xml.etree.ElementTree as ET

root = ET.parse(sys.argv[1]).getroot()
assert root.tag == "error" and root.attrib.get("code") == "100"
print("authentication: ok")
PY

echo "Torznab smoke test завершён успешно."
