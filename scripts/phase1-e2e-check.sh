#!/usr/bin/env bash
set -euo pipefail

API_URL="${API_URL:-http://localhost:8080/api/telemetry}"
MYSQL_ROOT_PASSWORD="${MYSQL_ROOT_PASSWORD:-admin_password}"
TEST_ID="phase1-check-drone"

cleanup() {
  docker start fleet-replica >/dev/null 2>&1 || true
  docker exec fleet-replica mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "START REPLICA SQL_THREAD;" >/dev/null 2>&1 || true
  docker exec fleet-primary mysql -uroot -p"$MYSQL_ROOT_PASSWORD" fleet_db \
    -e "DELETE FROM fleet_state WHERE vehicle_id='${TEST_ID}';" >/dev/null 2>&1 || true
}
trap cleanup EXIT

require_json_sequence() {
  local expected="$1"
  python3 -c 'import json,sys; d=json.load(sys.stdin); e=int(sys.argv[1]); assert d["sequence"]==e, (d,e)' "$expected"
}

echo "[1/6] Checking GTID replication threads..."
STATUS="$(docker exec fleet-replica mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "SHOW REPLICA STATUS\\G" 2>/dev/null)"
grep -q "Replica_IO_Running: Yes" <<<"$STATUS"
grep -q "Replica_SQL_Running: Yes" <<<"$STATUS"

echo "[2/6] Checking ordered write..."
curl -fsS -X POST "$API_URL/$TEST_ID" -H 'Content-Type: application/json' \
  -d '{"sequence":10,"lat":10.0,"lng":20.0,"bat":90}' >/dev/null
curl -fsS "$API_URL/$TEST_ID" | require_json_sequence 10

echo "[3/6] Checking out-of-order update cannot overwrite newer state..."
curl -fsS -X POST "$API_URL/$TEST_ID" -H 'Content-Type: application/json' \
  -d '{"sequence":9,"lat":999.0,"lng":999.0,"bat":1}' >/dev/null
curl -fsS "$API_URL/$TEST_ID" | require_json_sequence 10

echo "[4/6] Checking stale replica falls back to primary..."
docker exec fleet-replica mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "STOP REPLICA SQL_THREAD;" >/dev/null
curl -fsS -X POST "$API_URL/$TEST_ID" -H 'Content-Type: application/json' \
  -d '{"sequence":11,"lat":11.0,"lng":21.0,"bat":89}' >/dev/null
curl -fsS "$API_URL/$TEST_ID" | require_json_sequence 11
docker exec fleet-replica mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "START REPLICA SQL_THREAD;" >/dev/null

echo "[5/6] Checking unavailable replica falls back to primary..."
docker stop fleet-replica >/dev/null
curl -fsS "$API_URL/$TEST_ID" | require_json_sequence 11
docker start fleet-replica >/dev/null

echo "[6/6] Checking missing vehicle returns HTTP 404..."
CODE="$(curl -sS -o /dev/null -w '%{http_code}' "$API_URL/definitely-missing-phase1-check")"
[[ "$CODE" == "404" ]]

echo "PASS: all Phase-1 end-to-end checks succeeded."
