#!/usr/bin/env bash
# One-command double-booking harness runner. Ensures MySQL, provider-service and
# appointment-service are up, verifies the 60k seed, then fires 50 simultaneous bookings for
# each of 10 distinct slots and prints the invariant report. Save stdout into
# docs/bench/concurrency.txt.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

CONTAINER="${MYSQL_CONTAINER:-clinicscheduler-mysql}"
DB_USER="${DB_USER:-clinic}"
DB_PASSWORD="${DB_PASSWORD:-clinic_pass}"

PROVIDER_PIDS_BEFORE="$(lsof -ti tcp:8081 2>/dev/null || true)"
APPOINTMENT_PIDS_BEFORE="$(lsof -ti tcp:8082 2>/dev/null || true)"

cleanup() {
  local pid
  for pid in $(lsof -ti tcp:8081 2>/dev/null || true); do
    if ! grep -qw "$pid" <<<"$PROVIDER_PIDS_BEFORE"; then
      kill "$pid" 2>/dev/null || true
    fi
  done
  for pid in $(lsof -ti tcp:8082 2>/dev/null || true); do
    if ! grep -qw "$pid" <<<"$APPOINTMENT_PIDS_BEFORE"; then
      kill "$pid" 2>/dev/null || true
    fi
  done
}
trap cleanup EXIT

echo "Starting MySQL..."
docker compose up -d mysql >/dev/null
for _ in $(seq 1 60); do
  [ "$(docker inspect -f '{{.State.Health.Status}}' "$CONTAINER" 2>/dev/null || echo missing)" = "healthy" ] && break
  sleep 2
done

start_service() {
  local name="$1" port="$2" dir="$3"
  if curl -sf "http://localhost:${port}/actuator/health" >/dev/null 2>&1; then
    echo "${name} already UP on ${port}"
    return
  fi
  echo "Starting ${name} on ${port}..."
  (cd "$dir" && nohup ./mvnw -q spring-boot:run > "/tmp/clinicscheduler-${name}.log" 2>&1 &)
}

start_service "provider-service" 8081 services/provider-service
start_service "appointment-service" 8082 services/appointment-service

for _ in $(seq 1 90); do
  provider_ok="$(curl -sf http://localhost:8081/actuator/health 2>/dev/null || true)"
  appointment_ok="$(curl -sf http://localhost:8082/actuator/health 2>/dev/null || true)"
  if [ "$provider_ok" = '{"status":"UP"}' ] && [ "$appointment_ok" = '{"status":"UP"}' ]; then
    break
  fi
  sleep 2
done
curl -sf http://localhost:8081/actuator/health >/dev/null || { echo "provider-service did not start"; exit 1; }
curl -sf http://localhost:8082/actuator/health >/dev/null || { echo "appointment-service did not start"; exit 1; }

PROVIDER_COUNT="$(docker exec -i "$CONTAINER" mysql -N -B -u"$DB_USER" -p"$DB_PASSWORD" \
  -e 'SELECT COUNT(*) FROM providers' provider_db 2>/dev/null || echo 0)"
if [ "${PROVIDER_COUNT:-0}" -lt 200 ]; then
  echo "Seed data missing (providers=${PROVIDER_COUNT:-0}); running scripts/seed.sh..."
  ./scripts/seed.sh
fi

echo
cd services/appointment-service
./mvnw -q test-compile dependency:build-classpath -Dmdep.outputFile=target/test-classpath.txt
CP="target/test-classes:target/classes:$(cat target/test-classpath.txt)"
java -cp "$CP" -Dharness.concurrency="${HARNESS_CONCURRENCY:-50}" \
  -Dharness.slots="${HARNESS_SLOTS:-10}" \
  com.clinicscheduler.appointment.benchmark.DoubleBookingHarness
