#!/usr/bin/env bash
# Load-tests a running placeholder with oha (brew install oha) and writes a markdown summary.
#
#   CREATES_PER_MINUTE=1000000 docker compose --profile stack up -d --build   # 3 replicas behind nginx, create limiter off
#   bench/run.sh                                                              # defaults: http://localhost:8080, 20s per scenario
#
# Besides one-link scenarios, it measures realistic skew (10,000 links, visited by a Zipf distribution), a flood of
# random missing slugs, and a cold viral link: a burst on a link no cache holds, counting how many lookups reach
# Postgres (read from Prometheus, so the stack's Prometheus must be up).
#
# Everything (load generator, nginx, the app replicas, Postgres, Redis) shares one machine,
# so treat the numbers as a floor for that machine, not a capacity claim.
set -euo pipefail

BASE="${BASE:-http://localhost:8080}"
PROMETHEUS="${PROMETHEUS:-http://localhost:9090}"
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

# Real traffic is skewed: a few links get most visits, and a long tail gets a few each. 10,000 links, 200,000
# visits drawn from a Zipf distribution (s = 1.1), so the tail misses the in-memory caches and reaches Redis.
echo "Seeding 10,000 links for the skewed run"
python3 - "$BASE" "$SID" "$OUT/zipf-urls.txt" <<'PY'
import json, random, sys, urllib.request
from concurrent.futures import ThreadPoolExecutor
base, sid, path = sys.argv[1:]
def create(i):
    body = json.dumps({"content": f"https://example.com/zipf/{i}"}).encode()
    req = urllib.request.Request(f"{base}/api/snips", body, {"content-type": "application/json", "Cookie": f"sid={sid}"})
    return json.load(urllib.request.urlopen(req))["slug"]
with ThreadPoolExecutor(32) as pool:
    slugs = list(pool.map(create, range(10_000)))
weights = [1 / (rank ** 1.1) for rank in range(1, len(slugs) + 1)]
random.seed(7)
with open(path, "w") as f:
    for slug in random.choices(slugs, weights, k=200_000):
        f.write(f"{base}/{slug}\n")
PY
run popular      -c "$CONCURRENCY" -r 0 --urls-from-file "$OUT/zipf-urls.txt"

# Someone scanning for links: every request a different random slug, so no negative cache can help.
ALPHABET="abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789" # Rules.SLUG_ALPHABET
run missing-flood -c "$CONCURRENCY" --rand-regex-url "$BASE/api/snips/[$ALPHABET]{7}"
run page         -c "$CONCURRENCY" -H 'Accept: text/html' -H 'Accept-Encoding: gzip' "$BASE/$CODE"
run create       -c 50 -m POST -T application/json -H "Cookie: sid=$SID" \
  -d '{"language":"kotlin","content":"fun main() {\n    println(\"hello from the benchmark\")\n}"}' "$BASE/api/snips"

# A link goes viral before any cache holds it. Editing it evicts it from every instance and from Redis, then 500
# connections hit it at once. Without coalescing each would look it up in Postgres; with it, about one per instance.
db_lookups() {
  curl -sf "$PROMETHEUS/api/v1/query" --data-urlencode 'query=sum(snip_lookups_total{layer="database"})' |
    python3 -c 'import sys, json; r = json.load(sys.stdin)["data"]["result"]; print(int(float(r[0]["value"][1])) if r else 0)'
}
VIRAL=$(create '{"content":"https://example.com/viral"}')
sleep 6 # let Prometheus scrape a baseline
BEFORE=$(db_lookups)
curl -sf -o /dev/null -X PUT "$BASE/api/snips/$VIRAL" -H 'content-type: application/json' -H "Cookie: sid=$SID" \
  -d '{"content":"https://example.com/viral?edited"}'
oha --no-tui --output-format json -n 5000 -c 500 -r 0 "$BASE/$VIRAL" > "$OUT/stampede.json"
sleep 6
AFTER=$(db_lookups)
echo "$((AFTER - BEFORE))" > "$OUT/stampede-db-lookups.txt"

python3 - "$OUT" "$DURATION" "$CONCURRENCY" <<'PY'
import json, sys, pathlib
out, duration, conc = pathlib.Path(sys.argv[1]), sys.argv[2], sys.argv[3]
labels = {
    "redirect": "GET /{slug} (link, 302)",
    "read-api": "GET /api/snips/{slug} (owned link)",
    "read-missing": "GET /api/snips/{missing} (404)",
    "page": "GET /{slug} (page, gzip)",
    "create": "POST /api/snips (c=50)",
    "popular": "GET /{slug}, 10,000 links, Zipf-skewed",
    "missing-flood": "GET /api/snips/{random} (scan)",
}
rows = ["| Scenario | Requests/s | p50 | p95 | p99 | Success | Statuses |", "|---|---:|---:|---:|---:|---:|---|"]
for key, label in labels.items():
    d = json.loads((out / f"{key}.json").read_text())
    s, p = d["summary"], d["latencyPercentiles"]
    ms = lambda v: f"{v * 1000:.1f} ms"
    codes = ", ".join(f"{k}×{v}" for k, v in sorted(d["statusCodeDistribution"].items()))
    rows.append(f"| {label} | {s['requestsPerSec']:,.0f} | {ms(p['p50'])} | {ms(p['p95'])} | {ms(p['p99'])} | {s['successRate'] * 100:.2f}% | {codes} |")
table = "\n".join(rows)
burst = json.loads((out / "stampede.json").read_text())
lookups = (out / "stampede-db-lookups.txt").read_text().strip()
codes = ", ".join(f"{k}×{v}" for k, v in sorted(burst["statusCodeDistribution"].items()))
stampede = (f"Cold viral link: 5,000 requests from 500 connections at once ({codes}), "
            f"p99 {burst['latencyPercentiles']['p99'] * 1000:.1f} ms, {lookups} Postgres lookups (the rest were coalesced or cached).")
(out / "summary.md").write_text(f"{duration} per scenario, {conc} connections unless noted.\n\n{table}\n\n{stampede}\n")
print(table)
print()
print(stampede)
PY
