#!/usr/bin/env bash
# Applies the provider-service Flyway migrations to the Dockerized MySQL and loads the
# 200-provider / 60,000-slot seed. Prints the real final row counts.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if [ -f "$ROOT/.env" ]; then
  set -a
  # shellcheck disable=SC1091
  . "$ROOT/.env"
  set +a
fi

CONTAINER="${MYSQL_CONTAINER:-clinicscheduler-mysql}"
DB_USER="${DB_USER:-clinic}"
DB_PASSWORD="${DB_PASSWORD:-clinic_pass}"
DB_NAME="${DB_NAME:-provider_db}"

echo "Starting MySQL..."
docker compose up -d mysql >/dev/null

echo -n "Waiting for MySQL health"
for _ in $(seq 1 60); do
  status="$(docker inspect -f '{{.State.Health.Status}}' "$CONTAINER" 2>/dev/null || echo missing)"
  if [ "$status" = "healthy" ]; then
    echo " -> healthy"
    break
  fi
  echo -n "."
  sleep 2
done
if [ "$(docker inspect -f '{{.State.Health.Status}}' "$CONTAINER")" != "healthy" ]; then
  echo
  echo "MySQL did not become healthy" >&2
  exit 1
fi

echo "Applying Flyway migrations (provider_db)..."
(
  cd services/provider-service
  ./mvnw -q flyway:migrate \
    -Ddb.host=localhost -Ddb.port=3306 -Ddb.name="$DB_NAME" \
    -Ddb.user="$DB_USER" -Ddb.password="$DB_PASSWORD"
)

echo "Loading db/seed/generate_seed.sql..."
docker exec -i "$CONTAINER" mysql --default-character-set=utf8mb4 \
  -u"$DB_USER" -p"$DB_PASSWORD" "$DB_NAME" < db/seed/generate_seed.sql 2>/dev/null

echo
echo "Final row counts in $DB_NAME:"
docker exec -i "$CONTAINER" mysql -N --default-character-set=utf8mb4 \
  -u"$DB_USER" -p"$DB_PASSWORD" "$DB_NAME" -e "
    SELECT 'providers', COUNT(*) FROM providers
    UNION ALL
    SELECT 'availability_slots', COUNT(*) FROM availability_slots
    UNION ALL
    SELECT 'open_slots', COUNT(*) FROM availability_slots WHERE status='OPEN'
    UNION ALL
    SELECT 'distinct_specialties', COUNT(DISTINCT specialty) FROM providers
    UNION ALL
    SELECT 'distinct_locations', COUNT(DISTINCT clinic_location) FROM providers
    UNION ALL
    SELECT 'slot_date_span_days', DATEDIFF(MAX(start_time), MIN(start_time)) FROM availability_slots;" \
  2>/dev/null | awk -F'\t' '{printf "  %-24s %s\n", $1, $2}'
