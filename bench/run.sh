#!/usr/bin/env bash
# Load-tests a running placeholder with oha (brew install oha) and writes a markdown summary.
#
#   CREATES_PER_MINUTE=1000000 docker compose --profile stack up -d --build   # 3 replicas behind nginx, create limiter off
#   bench/run.sh                                                              # defaults: http://localhost:8080, 20s per scenario
#
# Everything (load generator, nginx, the app replicas, Postgres, Redis) shares one machine,
# so treat the numbers as a floor for that machine, not a capacity claim.
set -euo pipefail

BASE="${BASE:-http://localhost:8080}"
DURATION="${DURATION:-20s}"
CONCURRENCY="${CONCURRENCY:-200}"
OUT="${OUT:-$(dirname "$0")/results}"
mkdir -p "$OUT"

# Making links needs an account: sign up a throwaway benchmark user and reuse its session cookie.
SID=$(curl -sf -D - -o /dev/null -X POST "$BASE/api/account/signup" -H 'content-type: application/json' \
  -d "{\"email\":\"bench-$(date +%s)-$RANDOM@example.com\",\"password\":\"benchmark password\"}" |
  grep -i '^set-cookie: sid=' | sed -E 's/.*sid=([^;]+).*/\1/' | tr -d '\r')
[ -n "$SID" ] || { echo "Couldn't sign up a benchmark user (is AUTH_PER_MINUTE exhausted?)"; exit 1; }

create() {
  curl -sf -X POST "$BASE/api/snips" -H 'content-type: application/json' -H "Cookie: sid=$SID" -d "$1" |
    python3 -c 'import sys, json; print(json.load(sys.stdin)["slug"])'
}

echo "Seeding against $BASE"
LINK=$(create '{"content":"https://kotlinlang.org/docs/home.html"}')
CODE=$(create '{"language":"sql","content":"select u.name, count(o.id) as orders\nfrom users u\njoin orders o on o.user_id = u.id\ngroup by u.name\norder by orders desc;"}')
echo "  link: $LINK  code: $CODE"

run() { # name, then oha args
  local name="$1"; shift
  echo "-> $name"
  oha --no-tui --output-format json -z "$DURATION" "$@" > "$OUT/$name.json"
}

# Warm the JIT and every cache layer before measuring.
oha --no-tui --output-format quiet -z 5s -c 50 "$BASE/$LINK" >/dev/null
oha --no-tui --output-format quiet -z 5s -c 50 "$BASE/api/snips/$CODE" >/dev/null

run redirect     -c "$CONCURRENCY" -r 0 "$BASE/$LINK"
run read-api     -c "$CONCURRENCY" "$BASE/api/snips/$CODE"
run read-missing -c "$CONCURRENCY" "$BASE/api/snips/zzzzzzz"
run page         -c "$CONCURRENCY" -H 'Accept: text/html' -H 'Accept-Encoding: gzip' "$BASE/$CODE"
run create       -c 50 -m POST -T application/json -H "Cookie: sid=$SID" \
  -d '{"language":"kotlin","content":"fun main() {\n    println(\"hello from the benchmark\")\n}"}' "$BASE/api/snips"

python3 - "$OUT" "$DURATION" "$CONCURRENCY" <<'PY'
import json, sys, pathlib
out, duration, conc = pathlib.Path(sys.argv[1]), sys.argv[2], sys.argv[3]
labels = {
    "redirect": "GET /{slug} (link, 302)",
    "read-api": "GET /api/snips/{slug} (owned link)",
    "read-missing": "GET /api/snips/{missing} (404)",
    "page": "GET /{slug} (page, gzip)",
    "create": "POST /api/snips (c=50)",
}
rows = ["| Scenario | Requests/s | p50 | p95 | p99 | Success | Statuses |", "|---|---:|---:|---:|---:|---:|---|"]
for key, label in labels.items():
    d = json.loads((out / f"{key}.json").read_text())
    s, p = d["summary"], d["latencyPercentiles"]
    ms = lambda v: f"{v * 1000:.1f} ms"
    codes = ", ".join(f"{k}×{v}" for k, v in sorted(d["statusCodeDistribution"].items()))
    rows.append(f"| {label} | {s['requestsPerSec']:,.0f} | {ms(p['p50'])} | {ms(p['p95'])} | {ms(p['p99'])} | {s['successRate'] * 100:.2f}% | {codes} |")
table = "\n".join(rows)
(out / "summary.md").write_text(f"{duration} per scenario, {conc} connections unless noted.\n\n{table}\n")
print(table)
PY
