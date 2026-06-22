#!/usr/bin/env bash
set -euo pipefail

EMULATOR_HOST="localhost:8085"
PROJECT="local-project"
TOPIC="price-class-sold-out"
SUBSCRIPTION="price-class-sold-out-sub"

echo "Creating topic: ${TOPIC}"
curl --silent --show-error --fail \
  -X PUT \
  "http://${EMULATOR_HOST}/v1/projects/${PROJECT}/topics/${TOPIC}"

echo ""
echo "Creating subscription: ${SUBSCRIPTION}"
curl --silent --show-error --fail \
  -X PUT \
  -H "Content-Type: application/json" \
  -d "{\"topic\": \"projects/${PROJECT}/topics/${TOPIC}\"}" \
  "http://${EMULATOR_HOST}/v1/projects/${PROJECT}/subscriptions/${SUBSCRIPTION}"

echo ""
echo "Pub/Sub initialisation complete."
