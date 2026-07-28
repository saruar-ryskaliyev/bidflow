#!/usr/bin/env bash
# Reproducible G1 vs ZGC load comparison against a local auction server.
# Usage: from bidflow/, ./load/scripts/run-gc-compare.sh
#
# GC log paths go under /tmp so JAVA_OPTS is not broken by spaces in the
# workspace path (Gradle's unixStartScript eval-splits JAVA_OPTS).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
RESULTS="$ROOT/load/build/results"
GC_DIR="/tmp/bidflow-gc-compare"
mkdir -p "$RESULTS" "$GC_DIR"

echo "== building =="
./gradlew :serving:installDist :load:installDist --quiet

SERVING_BIN="$ROOT/serving/build/install/serving/bin/serving"
LOAD_BIN="$ROOT/load/build/install/load/bin/load"

run_one() {
  local gc="$1"
  local jvm_flags="$2"
  local out="$RESULTS/${gc}.json"
  local gclog="$GC_DIR/${gc}.gc.log"
  local server_log="$RESULTS/${gc}.server.log"
  echo "== starting server with $gc =="
  # shellcheck disable=SC2086
  JAVA_OPTS="$jvm_flags -Xlog:gc*:file=${gclog}" "$SERVING_BIN" 50051 >"$server_log" 2>&1 &
  local server_pid=$!
  trap 'kill $server_pid 2>/dev/null || true' EXIT
  sleep 2
  if ! kill -0 "$server_pid" 2>/dev/null; then
    echo "server failed to start; see $server_log" >&2
    cat "$server_log" >&2 || true
    exit 1
  fi
  echo "== load against $gc =="
  "$LOAD_BIN" --host localhost --port 50051 --rps 2000 --warmup 5 --duration 60 \
    --candidates 64 --deadline-ms 200 --out "$out"
  kill "$server_pid" 2>/dev/null || true
  wait "$server_pid" 2>/dev/null || true
  trap - EXIT
  cp "$gclog" "$RESULTS/${gc}.gc.log" 2>/dev/null || true
  echo "wrote $out and $RESULTS/${gc}.gc.log"
}

run_one g1 "-XX:+UseG1GC"
run_one zgc "-XX:+UseZGC"
echo "== done. Compare $RESULTS/g1.json and $RESULTS/zgc.json =="
