#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
ENDPOINT="${BASE_URL}/api/v1/flights/calendar?origin=KUL&destination=SIN&month=2024-07&currency=MYR"
TOTAL_REQUESTS=1000
BATCH_SIZE=100
SUCCESS=0
FAILED=0

echo "Load test: ${TOTAL_REQUESTS} total requests, ${BATCH_SIZE} concurrent per batch"
echo "Target: ${ENDPOINT}"
echo ""

for ((batch=1; batch<=TOTAL_REQUESTS/BATCH_SIZE; batch++)); do
  PIDS=()
  RESULTS=()
  TMPDIR_BATCH=$(mktemp -d)

  for ((i=1; i<=BATCH_SIZE; i++)); do
    OUT="${TMPDIR_BATCH}/req_${i}"
    curl --silent --output /dev/null \
         --write-out "%{http_code}" \
         --max-time 10 \
         "${ENDPOINT}" > "${OUT}" &
    PIDS+=($!)
  done

  for pid in "${PIDS[@]}"; do
    wait "${pid}"
  done

  for ((i=1; i<=BATCH_SIZE; i++)); do
    CODE=$(cat "${TMPDIR_BATCH}/req_${i}" 2>/dev/null || echo "000")
    if [[ "${CODE}" == "200" ]]; then
      ((SUCCESS++))
    else
      ((FAILED++))
    fi
  done

  rm -rf "${TMPDIR_BATCH}"
  echo "Batch ${batch}/${TOTAL_REQUESTS/BATCH_SIZE} complete — success so far: ${SUCCESS}, failed: ${FAILED}"
done

echo ""
echo "Load test complete."
echo "Total: ${TOTAL_REQUESTS} | Success: ${SUCCESS} | Failed: ${FAILED}"
