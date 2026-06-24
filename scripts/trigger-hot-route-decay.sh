#!/usr/bin/env bash
set -euo pipefail

HOST="${1:-localhost}"
PORT="${2:-8080}"

echo "Triggering hot-route decay on ${HOST}:${PORT}..."
curl --silent --show-error --fail \
  -X POST \
  -H "Content-Type: application/json" \
  "http://${HOST}:${PORT}/admin/hot-routes/decay"

echo ""
echo "Hot-route decay triggered."
