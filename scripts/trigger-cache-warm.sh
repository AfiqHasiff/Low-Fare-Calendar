#!/usr/bin/env bash
set -euo pipefail

HOST="${1:-localhost}"
PORT="${2:-8080}"

echo "Triggering cache warm cycle on ${HOST}:${PORT}..."
curl --silent --show-error --fail \
  -X POST \
  -H "Content-Type: application/json" \
  "http://${HOST}:${PORT}/admin/cache/warm"

echo ""
echo "Cache warm triggered."
