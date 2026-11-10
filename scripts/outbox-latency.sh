#!/usr/bin/env bash
# Measures commit-to-Kafka latency for transfer.completed: from the transaction's timestamp in
# Postgres to the time the broker appended the event. Works for either publisher (polling relay
# or Debezium), whichever is running. Usage: scripts/outbox-latency.sh [label]
set -euo pipefail
cd "$(dirname "$0")/.."
label=${1:-run}
out=$(mktemp)

# Broker-side timestamps, so both publishers are measured against the same clock.
docker compose exec -T kafka /opt/kafka/bin/kafka-configs.sh --bootstrap-server localhost:9092 --alter \
  --entity-type topics --entity-name transfer.completed --add-config message.timestamp.type=LogAppendTime >/dev/null

docker compose exec -T kafka timeout 45 /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic transfer.completed --property print.timestamp=true >"$out" 2>/dev/null &
consumer=$!
sleep 4
k6 run --quiet -e VUS=5 -e DURATION=30s scripts/transfers.k6.js >/dev/null 2>&1 || true
wait $consumer || true

python3 - "$out" "$label" <<'PY'
import json, sys, datetime, statistics
lat = []
for line in open(sys.argv[1]):
    if not line.startswith("LogAppendTime:"):
        continue
    ts, payload = line.split("\t", 1)
    appended = int(ts.split(":")[1])
    posted = datetime.datetime.fromisoformat(json.loads(payload)["postedAt"].replace("Z", "+00:00"))
    lat.append(appended - posted.timestamp() * 1000)
lat.sort()
q = lambda p: lat[min(len(lat) - 1, int(p * len(lat)))]
print(f"{sys.argv[2]}: {len(lat)} events, p50={q(.5):.0f}ms p95={q(.95):.0f}ms p99={q(.99):.0f}ms max={lat[-1]:.0f}ms")
PY
rm -f "$out"
