#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"
[[ -f .env ]] || { echo ".env не найден" >&2; exit 1; }
mkdir -p backups
out="backups/audiobookred-$(date +%Y%m%d-%H%M%S).dump"
docker compose exec -T db sh -lc 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > "$out"
sha256sum "$out" > "$out.sha256"
echo "$out"
