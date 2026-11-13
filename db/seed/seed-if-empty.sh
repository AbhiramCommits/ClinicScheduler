#!/bin/sh
# Compose one-shot seeder: inserts the 200-provider / 60,000-slot dataset only when the
# provider database is empty. Flyway migrations have already run because this service waits for
# provider-service to report healthy.
set -eu

HOST="${MYSQL_HOST:-mysql}"
USER="${DB_USER:-clinic}"
DATABASE="${DB_NAME:-provider_db}"

count="$(mysql -h "$HOST" -u "$USER" -N -B -e "SELECT COUNT(*) FROM ${DATABASE}.providers")"
echo "Existing provider rows: ${count}"

if [ "$count" -gt 0 ]; then
  echo "Seed data already present; skipping."
  exit 0
fi

echo "Seeding ${DATABASE}..."
mysql -h "$HOST" -u "$USER" --default-character-set=utf8mb4 "${DATABASE}" < /seed/generate_seed.sql
echo "Seed complete."
