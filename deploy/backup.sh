#!/usr/bin/env bash
# Backs up Cronbell's SQLite database while the server keeps running.
#
# sqlite3's .backup copies a consistent snapshot even while Cronbell writes (WAL mode), unlike
# copying the file. It runs in a throwaway container that mounts the Cronbell data volume, as
# the same user as the server so it never leaves root-owned files behind. The copy is checked
# with PRAGMA integrity_check, gzipped into BACKUP_DIR, and copies older than KEEP_DAYS days
# are deleted.
#
# Usage: ./backup.sh                 (from anywhere; uses the docker-compose.yml next to it)
# Settings, as environment variables:
#   BACKUP_DIR    where backups go             (default: /var/backups/cronbell)
#   KEEP_DAYS     days of backups to keep      (default: 14)
#   SQLITE_IMAGE  image with sqlite3 and gzip  (default: keinos/sqlite3:3.53.4)
#
# With CRONBELL_DATABASE_URL (PostgreSQL) there is no SQLite file: use pg_dump instead.
# Restoring: docs/self-hosting.md, "Restore a backup".

set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
backup_dir="${BACKUP_DIR:-/var/backups/cronbell}"
keep_days="${KEEP_DAYS:-14}"
sqlite_image="${SQLITE_IMAGE:-keinos/sqlite3:3.53.4}"

container="$(docker compose -f "$here/docker-compose.yml" ps --all --quiet cronbell)"
if [[ -z "$container" ]]; then
  echo "backup: no cronbell container; start the stack first (docker compose up -d)" >&2
  exit 1
fi

mkdir -p "$backup_dir"
target="$backup_dir/cronbell-$(date -u +%Y%m%dT%H%M%SZ).db.gz"
partial="$target.partial"
trap 'rm -f "$partial"' EXIT

docker run --rm --volumes-from "$container" --user 10001:10001 --entrypoint sh "$sqlite_image" -c '
  set -e
  test -f /data/cronbell.db || { echo "backup: /data/cronbell.db not found" >&2; exit 1; }
  sqlite3 /data/cronbell.db ".backup /tmp/cronbell.db"
  check="$(sqlite3 /tmp/cronbell.db "PRAGMA integrity_check")"
  test "$check" = ok || { echo "backup: integrity check failed: $check" >&2; exit 1; }
  gzip -c /tmp/cronbell.db
' >"$partial"

mv "$partial" "$target"
chmod 600 "$target"
echo "backup: wrote $target ($(du -h "$target" | cut -f1))"

find "$backup_dir" -maxdepth 1 -name 'cronbell-*.db.gz' -mtime "+$keep_days" -print -delete |
  sed 's/^/backup: removed /'

# Off-site copy (optional): a backup on the same disk does not survive losing the server.
# For example, with rclone configured for an S3 bucket or similar:
#   rclone copy "$target" remote:cronbell-backups/
