#!/usr/bin/env bash
# End-to-end smoke test through the nginx-served SPA origin:
# login -> availability search -> book -> duplicate booking must be 409.
# Exits 0 only when the whole sequence succeeds. Used by the compose-smoke CI job.
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

fail() {
  echo "SMOKE FAILED: $*" >&2
  exit 1
}

echo "Waiting for ${BASE_URL} ..."
for _ in $(seq 1 90); do
  if curl -sf "${BASE_URL}/" >/dev/null 2>&1; then
    break
  fi
  sleep 2
done
curl -sf "${BASE_URL}/" >/dev/null || fail "SPA did not become reachable"

echo "1. Login as staff"
LOGIN_STATUS="$(curl -s -o "$TMP/login.json" -w '%{http_code}' -X POST "${BASE_URL}/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"email":"staff@clinic.test","password":"staff123"}')"
[ "$LOGIN_STATUS" = "200" ] || fail "login returned HTTP ${LOGIN_STATUS}: $(cat "$TMP/login.json")"
TOKEN="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["token"])' "$TMP/login.json")"
[ -n "$TOKEN" ] || fail "login returned an empty token"
echo "   token acquired (${#TOKEN} chars)"

FROM_DATE="$(python3 -c 'import datetime; print((datetime.date.today()+datetime.timedelta(days=1)).isoformat()+"T00:00:00")')"
TO_DATE="$(python3 -c 'import datetime; print((datetime.date.today()+datetime.timedelta(days=15)).isoformat()+"T23:59:59")')"

echo "2. Search availability"
SEARCH_STATUS="$(curl -s -o "$TMP/search.json" -w '%{http_code}' \
  "${BASE_URL}/api/availability/search?from=${FROM_DATE}&to=${TO_DATE}&page=0&size=1" \
  -H "Authorization: Bearer ${TOKEN}")"
[ "$SEARCH_STATUS" = "200" ] || fail "search returned HTTP ${SEARCH_STATUS}: $(cat "$TMP/search.json")"
read -r SLOT_ID PROVIDER_ID START_TIME END_TIME < <(python3 - "$TMP/search.json" <<'PY'
import json
import sys

slot = json.load(open(sys.argv[1]))["content"][0]
print(slot["id"], slot["providerId"], slot["startTime"], slot["endTime"])
PY
)
[ -n "${SLOT_ID:-}" ] || fail "search returned no open slots (seed the database first)"
echo "   selected slot ${SLOT_ID} with provider ${PROVIDER_ID} at ${START_TIME}"

book() {
  local patient_id="$1" patient_name="$2" output="$3"
  curl -s -o "$output" -w '%{http_code}' -X POST "${BASE_URL}/api/appointments" \
    -H "Authorization: Bearer ${TOKEN}" \
    -H 'Content-Type: application/json' \
    -d "{\"patientId\":\"${patient_id}\",\"patientName\":\"${patient_name}\",\"providerId\":${PROVIDER_ID},\"slotId\":${SLOT_ID},\"startTime\":\"${START_TIME}\",\"endTime\":\"${END_TIME}\"}"
}

echo "3. Book the slot"
FIRST_STATUS="$(book "smoke-patient-$(date +%s)" "Smoke Patient" "$TMP/first.json")"
[ "$FIRST_STATUS" = "200" ] || fail "first booking returned HTTP ${FIRST_STATUS}: $(cat "$TMP/first.json")"
echo "   booking confirmed: $(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["status"])' "$TMP/first.json")"

echo "4. Book the same slot again (must conflict)"
SECOND_STATUS="$(book "smoke-rival-$(date +%s)" "Rival Patient" "$TMP/second.json")"
[ "$SECOND_STATUS" = "409" ] || fail "duplicate booking returned HTTP ${SECOND_STATUS}, expected 409: $(cat "$TMP/second.json")"
ERROR_CODE="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("error",""))' "$TMP/second.json")"
[ "$ERROR_CODE" = "DOUBLE_BOOKING_BLOCKED" ] || fail "duplicate booking error code was '${ERROR_CODE}'"
echo "   409 ${ERROR_CODE}"

echo
echo "SMOKE PASS: login -> search -> book(200) -> duplicate book(409 DOUBLE_BOOKING_BLOCKED)"
