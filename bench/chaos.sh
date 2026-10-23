#!/usr/bin/env bash
# Failure experiments: break one dependency at a time under steady load, and measure what users and the click
# counts see. Needs the stack with Toxiproxy in front of Postgres, Redis and Kafka:
#
#   docker compose -f compose.yaml -f docker/compose.chaos.yaml --profile stack up -d --build
#   bench/chaos.sh
#
# Each experiment sends RATE redirects a second to one short link for LOAD seconds. FAULT_AT seconds in, the
# fault starts; FAULT_FOR seconds later it's undone. Afterwards the script waits for the click pipeline to catch
# up and checks that every redirect served was counted as exactly one visit.
set -euo pipefail

BASE="${BASE:-http://localhost:8080}"
TOXIPROXY="${TOXIPROXY:-http://localhost:8474}"
RATE="${RATE:-2000}"
LOAD="${LOAD:-30}"
FAULT_AT="${FAULT_AT:-10}"
FAULT_FOR="${FAULT_FOR:-10}"
OUT="${OUT:-$(dirname "$0")/results/chaos}"
COMPOSE=(docker compose -f compose.yaml -f docker/compose.chaos.yaml --profile stack)
cd "$(dirname "$0")/.."
mkdir -p "$OUT"

curl -sf "$TOXIPROXY/version" >/dev/null || { echo "Toxiproxy isn't reachable at $TOXIPROXY; start the stack with docker/compose.chaos.yaml"; exit 1; }

SID=$(curl -sf -D - -o /dev/null -X POST "$BASE/api/account/signup" -H 'content-type: application/json' \
  -d "{\"email\":\"chaos-$(date +%s)-$RANDOM@example.com\",\"password\":\"benchmark password\"}" |
  grep -i '^set-cookie: sid=' | sed -E 's/.*sid=([^;]+).*/\1/' | tr -d '\r')
[ -n "$SID" ] || { echo "Couldn't sign up a user (is AUTH_PER_MINUTE exhausted?)"; exit 1; }

new_link() {
  curl -sf -X POST "$BASE/api/snips" -H 'content-type: application/json' -H "Cookie: sid=$SID" \
    -d "{\"content\":\"https://example.com/chaos/$1\"}" | python3 -c 'import sys, json; print(json.load(sys.stdin)["slug"])'
}

visits() {
  curl -sf "$BASE/api/snips/$1/stats" -H "Cookie: sid=$SID" | python3 -c 'import sys, json; print(json.load(sys.stdin)["visits"])' || echo 0
}

# Toxiproxy: cut a dependency off (connections refused) or slow every packet down.
proxy()   { curl -sf -o /dev/null -X POST "$TOXIPROXY/proxies/$1" -d "{\"enabled\":$2}"; }
slow()    { curl -sf -o /dev/null -X POST "$TOXIPROXY/proxies/$1/toxics" -d "{\"name\":\"slow\",\"type\":\"latency\",\"attributes\":{\"latency\":$2}}"; }
unslow()  { curl -sf -o /dev/null -X DELETE "$TOXIPROXY/proxies/$1/toxics/slow"; }
reset()   { curl -sf -o /dev/null -X POST "$TOXIPROXY/reset"; }

# One app container, killed outright (no graceful shutdown) and started again.
REPLICA=$("${COMPOSE[@]}" ps -q app | head -1)

experiment() { # name, then the commands that start and end the fault
  local name="$1" start="$2" end="$3"
  reset
  local slug; slug=$(new_link "$name")
  # Warm every instance's cache with the link, as a live link would be.
  oha --no-tui --output-format quiet -n 300 -c 30 -r 0 "$BASE/$slug" >/dev/null
  local warm; warm=$(visits_settled "$slug" 300)

  echo "-> $name"
  # A fixed number of requests, not a fixed time: oha abandons requests still in flight when a timer runs out, and
  # the server would count visits whose response the load generator never recorded.
  oha --no-tui --output-format json -n "$((RATE * LOAD))" -q "$RATE" --latency-correction -c 100 -r 0 "$BASE/$slug" > "$OUT/$name.json" &
  local load=$!
  sleep "$FAULT_AT"; eval "$start"
  sleep "$FAULT_FOR"; eval "$end"
  wait "$load"

  local served; served=$(python3 -c 'import sys, json; print(json.load(open(sys.argv[1]))["statusCodeDistribution"].get("302", 0))' "$OUT/$name.json")
  local expected=$((warm + served)) began=$SECONDS counted
  counted=$(visits_settled "$slug" "$expected")
  echo "{\"served\": $served, \"counted\": $((counted - warm)), \"settled_seconds\": $((SECONDS - began))}" > "$OUT/$name.clicks.json"
}

# Polls until the link's visits reach the expected count or stop moving for a minute; prints the last count.
# A minute, because a killed server's partitions move to the others only once its Kafka session times out.
visits_settled() {
  local slug="$1" expected="$2" last=-1 still=0 now
  while true; do
    now=$(visits "$slug")
    [ "$now" -ge "$expected" ] && { echo "$now"; return; }
    if [ "$now" -eq "$last" ]; then still=$((still + 1)); else still=0; fi
    [ "$still" -ge 60 ] && { echo "$now"; return; }
    last=$now; sleep 1
  done
}

experiment baseline          ":"                          ":"
experiment cache-redis-down  "proxy redis false"          "proxy redis true"
experiment kafka-down        "proxy kafka false"          "proxy kafka true"
experiment postgres-down     "proxy postgres false"       "proxy postgres true"
experiment postgres-slow     "slow postgres 250"          "unslow postgres"
experiment replica-killed    "docker kill $REPLICA >/dev/null" "docker start $REPLICA >/dev/null"
reset

python3 - "$OUT" "$RATE" "$LOAD" "$FAULT_AT" "$FAULT_FOR" <<'PY'
import json, sys, pathlib
out, rate, load, at, length = pathlib.Path(sys.argv[1]), *sys.argv[2:]
experiments = {
    "baseline": "Nothing broken",
    "cache-redis-down": "Cache Redis unreachable",
    "kafka-down": "Kafka unreachable",
    "postgres-down": "Postgres unreachable",
    "postgres-slow": "Postgres +250 ms per packet",
    "replica-killed": "One of 3 app servers killed",
}
rows = ["| Fault | Redirects served | Errors | p50 | p99 | Visits counted | Counts settled in |",
        "|---|---:|---:|---:|---:|---:|---:|"]
for key, label in experiments.items():
    d = json.loads((out / f"{key}.json").read_text())
    c = json.loads((out / f"{key}.clicks.json").read_text())
    codes = d["statusCodeDistribution"]
    errors = sum(v for k, v in codes.items() if k != "302") + sum(d.get("errorDistribution", {}).values())
    p = d["latencyPercentiles"]
    ms = lambda v: f"{v * 1000:.1f} ms"
    exact = "all" if c["counted"] == c["served"] else f"{c['counted']:,}"
    rows.append(f"| {label} | {c['served']:,} | {errors:,} | {ms(p['p50'])} | {ms(p['p99'])} | {exact} | {c['settled_seconds']} s |")
table = "\n".join(rows)
note = (f"{rate} redirects/s to one link for {load} s; the fault starts {at} s in and lasts {length} s. "
        "\"Visits counted: all\" means every redirect served was counted exactly once.")
(out / "summary.md").write_text(f"{note}\n\n{table}\n")
print(note)
print()
print(table)
PY
