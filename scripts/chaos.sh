#!/usr/bin/env bash
# Publishes trades and sends transfers while killing (SIGKILL) settlement-service,
# ledger-service, Kafka and the ledger's Postgres. Afterwards every trade must have reached a
# final state exactly once and the books must still balance.
# Usage: scripts/chaos.sh [trades]
set -euo pipefail
cd "$(dirname "$0")/.."

trades=${1:-6000}

token() {
  curl -sf -d grant_type=client_credentials -d client_id=ops-cli -d client_secret=ops-secret \
    http://localhost:8180/realms/payments/protocol/openid-connect/token |
    python3 -c 'import sys, json; print(json.load(sys.stdin)["access_token"])'
}
sdb() { docker compose exec -T settlement-db psql -U settlement -d settlement_db -tAc "$1"; }
ldb() { docker compose exec -T ledger-db psql -U ledger -d ledger_db -tAc "$1"; }
log() { echo "$(date +%H:%M:%S) $*"; }

kill_for() {
  log "kill -9 $1 for ${2}s"
  docker compose kill -s SIGKILL "$1" >/dev/null 2>&1
  sleep "$2"
  docker compose up -d --no-deps "$1" >/dev/null 2>&1
  log "$1 restarted"
}

before=$(sdb "select count(*) from settlements")
log "publishing $trades trades at 100/s and running k6 transfers"
docker compose run --quiet-pull --rm -e SIMULATOR_TRADES="$trades" -e SIMULATOR_RATE_PER_SECOND=100 \
  trade-simulator >/tmp/chaos-sim.log 2>&1 &
sim=$!
k6 run --quiet -e VUS=10 -e DURATION=70s scripts/transfers.k6.js >/tmp/chaos-k6.log 2>&1 &
k6pid=$!

sleep 15; kill_for settlement-service 5
sleep 8;  kill_for ledger-service 5
sleep 8;  kill_for kafka 8
sleep 8;  kill_for ledger-db 5
sleep 5;  kill_for settlement-service 3

wait $sim || true
wait $k6pid || true
log "load finished: $(grep published /tmp/chaos-sim.log | sed 's/.*: //')"
log "k6: $(grep -E 'checks_succeeded' /tmp/chaos-k6.log | sed 's/^ *//')"

expected=$((before + trades))
log "waiting for every trade to settle or fail (the sweeper re-drives anything left pending)"
for _ in $(seq 1 90); do
  read -r done pending < <(sdb "select count(*) filter (where status <> 'PENDING'), count(*) filter (where status = 'PENDING') from settlements" | tr '|' ' ')
  [[ "$done" -ge "$expected" && "$pending" == "0" ]] && break
  sleep 3
done
log "settlements: $done final, $pending pending (expected $expected)"
[[ "$done" -ge "$expected" && "$pending" == "0" ]] || { echo "FAIL  trades left unfinished"; exit 1; }

settled=$(sdb "select count(*) from settlements where status = 'SETTLED'")
posted=$(ldb "select count(*) from transactions where type = 'TRADE_SETTLEMENT' and status = 'POSTED'")
if [[ "$settled" == "$posted" ]]; then
  echo "ok    $settled SETTLED trades = $posted posted ledger transactions"
else
  echo "FAIL  $settled SETTLED trades but $posted posted ledger transactions"; exit 1
fi
dlt=$(docker compose exec -T kafka /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server localhost:9092 --topic trades.executed.DLT 2>/dev/null | awk -F: '{s+=$3} END {print s+0}')
echo "info  $dlt messages went to the DLT and were finished by the sweeper"

for _ in $(seq 1 20); do
  [[ "$(ldb "select count(*) from outbox where published_at is null")" == "0" && \
     "$(sdb "select count(*) from outbox where published_at is null")" == "0" ]] && break
  sleep 2
done
echo "ok    outboxes drained (ledger $(ldb "select count(*) from outbox where published_at is null"), settlement $(sdb "select count(*) from outbox where published_at is null") pending)"

scripts/check-ledger.sh
t=$(token)
echo "recon ledger:     $(curl -sf -XPOST -H "Authorization: Bearer $t" http://localhost:8081/reconciliation/runs | python3 -c 'import sys, json; r = json.load(sys.stdin); print(r["status"], r["mismatchCount"], "mismatches")')"
echo "recon settlement: $(curl -sf -XPOST -H "Authorization: Bearer $t" http://localhost:8082/reconciliation/runs | python3 -c 'import sys, json; r = json.load(sys.stdin); print(r["status"], r["checked"], "checked,", r["mismatchCount"], "mismatches")')"
