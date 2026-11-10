#!/usr/bin/env bash
# Runs the simulator against the compose stack and checks every trade ends SETTLED or FAILED
# with the books still balanced. Usage: scripts/e2e.sh [trades]
set -euo pipefail
cd "$(dirname "$0")/.."

trades=${1:-500}
rate=${2:-100}   # trades per second published; 0 = as fast as possible

token() {
  curl -sf -d grant_type=client_credentials -d client_id=ops-cli -d client_secret=ops-secret \
    http://localhost:8180/realms/payments/protocol/openid-connect/token |
    python3 -c 'import sys, json; print(json.load(sys.stdin)["access_token"])'
}

stats() {
  curl -sf -H "Authorization: Bearer $(token)" http://localhost:8082/settlements/stats
}

total() {
  stats | python3 -c 'import sys, json; s = json.load(sys.stdin); print(s["SETTLED"] + s["FAILED"], s["PENDING"])'
}

sdb() { docker compose exec -T settlement-db psql -U settlement -d settlement_db -tAc "$1"; }

read -r before _ < <(total)
docker compose run --quiet-pull --rm -e SIMULATOR_TRADES="$trades" -e SIMULATOR_RATE_PER_SECOND="$rate" \
  -e SIMULATOR_SEED="${SEED:-true}" -e SIMULATOR_TRADERS="${TRADERS:-20}" trade-simulator 2>&1 | grep -E "seeded|published"

expected=$((before + trades))
for _ in $(seq 1 60); do
  read -r done pending < <(total)
  if [[ "$done" -ge "$expected" && "$pending" == "0" ]]; then
    break
  fi
  sleep 2
done
echo "settlements: $(stats)"
if [[ "$done" -lt "$expected" || "$pending" != "0" ]]; then
  echo "FAIL  expected $expected finished settlements, got $done ($pending pending)"
  exit 1
fi
echo "ok    all $trades trades reached a final state"
sdb "select '      ' || count(*) || ' settlements in ' || round(extract(epoch from max(updated_at) - min(created_at))::numeric, 1)
     || ' s, ' || round(count(*) / extract(epoch from max(updated_at) - min(created_at))::numeric) || ' per second'
     from (select * from settlements order by created_at desc limit $trades) recent"
scripts/check-ledger.sh
