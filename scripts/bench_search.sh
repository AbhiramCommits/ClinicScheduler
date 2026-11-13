#!/usr/bin/env bash
# Runs the real availability-search benchmark against the Dockerized, fully seeded MySQL.
# Prints EXPLAIN plans, actual rows examined, chosen access paths, and 200 randomized search
# latencies (min/p50/p95/max) for both supported query shapes. Run once before V3 indexes and
# once after; redirect stdout into docs/bench/before.txt / after.txt.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

CONTAINER="${MYSQL_CONTAINER:-clinicscheduler-mysql}"
DB_USER="${DB_USER:-clinic}"
DB_PASSWORD="${DB_PASSWORD:-clinic_pass}"
DB_NAME="${DB_NAME:-provider_db}"

mysql_exec() {
  docker exec -i "$CONTAINER" mysql --default-character-set=utf8mb4 \
    -u"$DB_USER" -p"$DB_PASSWORD" -r "$@" "$DB_NAME" 2>/dev/null
}

print_actual_rows() {
  python3 - "$1" <<'PY'
import re
import sys

text = sys.argv[1]
access = re.compile(
    r"(Table scan|Index range scan|Index lookup|Single-row index lookup|Single-row covering index lookup|"
    r"Covering index lookup|Covering index range scan|Covering index scan|"
    r"Index scan|Index merge|Full-text index search)"
)
actual = re.compile(r"actual time=[\d.eE+-]+\.[\d.eE+-]+ rows=([\d.]+) loops=(\d+)")

total = 0.0
found = 0
for line in text.splitlines():
    if access.search(line):
        match = actual.search(line)
        rows = float(match.group(1)) * int(match.group(2)) if match else 0.0
        found += 1
        total += rows
        print(f"  access[{found}]: rows_examined={rows:.0f}")
        print(f"           {line.strip()}")
print(f"actual_rows_examined_total={total:.0f}")
print(f"table_access_nodes={found}")
PY
}

print_plan_summary() {
  python3 - "$1" <<'PY'
import json
import sys

plan = json.loads(sys.argv[1])
rows = []


def walk(node):
    if isinstance(node, dict):
        if "table_name" in node or "access_type" in node:
            rows.append({
                "table": node.get("table_name", "(derived)"),
                "access_type": node.get("access_type", "?"),
                "key": node.get("key"),
                "estimated_rows_per_scan": node.get("rows_examined_per_scan"),
                "rows_produced_per_join": node.get("rows_produced_per_join"),
                "using_filesort": node.get("using_filesort", False),
                "using_temporary_table": node.get("using_temporary_table", False),
            })
        for value in node.values():
            walk(value)
    elif isinstance(node, list):
        for value in node:
            walk(value)


walk(plan.get("query_block", plan))
for entry in rows:
    print(f"  table={entry['table']}")
    print(f"    access_type={entry['access_type']} key={entry['key']} "
          f"rows_examined_per_scan={entry['estimated_rows_per_scan']} "
          f"rows_produced_per_join={entry['rows_produced_per_join']}")
    if entry["using_filesort"]:
        print("    using_filesort=true")
    if entry["using_temporary_table"]:
        print("    using_temporary_table=true")
PY
}

QUERY_FILTERED="SELECT s.id, s.provider_id, s.start_time
FROM availability_slots s
JOIN providers p ON p.id = s.provider_id
WHERE p.specialty = 'Cardiology'
  AND p.clinic_location = 'Downtown Clinic'
  AND s.status = 'OPEN'
  AND s.start_time >= DATE_ADD(CURDATE(), INTERVAL 1 DAY)
  AND s.start_time < DATE_ADD(CURDATE(), INTERVAL 31 DAY)
ORDER BY s.start_time, s.provider_id
LIMIT 20"

QUERY_BROWSE="SELECT s.id, s.provider_id, s.start_time
FROM availability_slots s
JOIN providers p ON p.id = s.provider_id
WHERE s.status = 'OPEN'
  AND s.start_time >= DATE_ADD(CURDATE(), INTERVAL 1 DAY)
  AND s.start_time < DATE_ADD(CURDATE(), INTERVAL 31 DAY)
ORDER BY s.start_time, s.provider_id
LIMIT 20"

echo "=== ClinicScheduler availability-search benchmark ==="
echo "timestamp_utc: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo "database: $DB_NAME (MySQL $(mysql_exec -N -B -e 'SELECT VERSION()'))"
echo
echo "--- data volume ---"
mysql_exec -t -e "
  SELECT 'providers' AS table_name, COUNT(*) AS row_count FROM providers
  UNION ALL SELECT 'availability_slots', COUNT(*) FROM availability_slots
  UNION ALL SELECT 'open_slots', COUNT(*) FROM availability_slots WHERE status='OPEN';"
echo
echo "--- secondary indexes on availability_slots / providers ---"
mysql_exec -t -e "
  SELECT TABLE_NAME, INDEX_NAME, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS indexed_columns, NON_UNIQUE
  FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = '$DB_NAME' AND TABLE_NAME IN ('availability_slots','providers')
  GROUP BY TABLE_NAME, INDEX_NAME, NON_UNIQUE
  ORDER BY TABLE_NAME, INDEX_NAME;"
echo

echo "################################################################"
echo "# SHAPE A: filtered by specialty + clinic location            #"
echo "################################################################"
echo "--- query ---"
echo "$QUERY_FILTERED"
echo
ANALYZE_FILTERED="$(mysql_exec -N -B -e "EXPLAIN ANALYZE $QUERY_FILTERED")"
echo "--- EXPLAIN ANALYZE ---"
echo "$ANALYZE_FILTERED"
echo
echo "--- actual rows examined ---"
print_actual_rows "$ANALYZE_FILTERED"
echo
PLAN_FILTERED="$(mysql_exec -N -B -e "EXPLAIN FORMAT=JSON $QUERY_FILTERED")"
echo "--- EXPLAIN FORMAT=JSON summary ---"
print_plan_summary "$PLAN_FILTERED"
echo

echo "################################################################"
echo "# SHAPE B: browse all open slots (no filter)                   #"
echo "################################################################"
echo "--- query ---"
echo "$QUERY_BROWSE"
echo
ANALYZE_BROWSE="$(mysql_exec -N -B -e "EXPLAIN ANALYZE $QUERY_BROWSE")"
echo "--- EXPLAIN ANALYZE ---"
echo "$ANALYZE_BROWSE"
echo
echo "--- actual rows examined ---"
print_actual_rows "$ANALYZE_BROWSE"
echo
PLAN_BROWSE="$(mysql_exec -N -B -e "EXPLAIN FORMAT=JSON $QUERY_BROWSE")"
echo "--- EXPLAIN FORMAT=JSON summary ---"
print_plan_summary "$PLAN_BROWSE"
echo

echo "--- EXPLAIN FORMAT=JSON (shape A, full plan) ---"
echo "$PLAN_FILTERED"
echo
echo "--- EXPLAIN FORMAT=JSON (shape B, full plan) ---"
echo "$PLAN_BROWSE"
echo

echo "--- randomized 200-query latency loops (engine-side) ---"
mysql_exec -t < db/benchmark/explain_before.sql
echo
echo "=== benchmark complete ==="
