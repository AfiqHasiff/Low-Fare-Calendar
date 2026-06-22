#!/usr/bin/env bash
set -euo pipefail

ORIGIN="${1:?Usage: $0 <ORIGIN> <DESTINATION> <DATE> e.g. KUL SIN 2024-07-15}"
DEST="${2:?Missing DESTINATION argument}"
DATE="${3:?Missing DATE argument}"

EMULATOR_HOST="localhost:8085"
PROJECT="local-project"
TOPIC="price-class-sold-out"

GENERATED_AT="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
EVENT_ID="$(uuidgen | tr '[:upper:]' '[:lower:]')"

PAYLOAD=$(cat <<EOF
{
  "eventId": "${EVENT_ID}",
  "origin": "${ORIGIN}",
  "destination": "${DEST}",
  "date": "${DATE}",
  "priceClass": "ECONOMY_LITE",
  "soldOutPrice": 199.00,
  "currency": "USD",
  "generatedAt": "${GENERATED_AT}"
}
EOF
)

ENCODED=$(echo -n "${PAYLOAD}" | base64 | tr -d '\n')

BODY=$(cat <<EOF
{
  "messages": [
    {
      "data": "${ENCODED}"
    }
  ]
}
EOF
)

echo "Publishing sold-out event for ${ORIGIN}->${DEST} on ${DATE}"
curl --silent --show-error --fail \
  -X POST \
  -H "Content-Type: application/json" \
  -d "${BODY}" \
  "http://${EMULATOR_HOST}/v1/projects/${PROJECT}/topics/${TOPIC}:publish"

echo ""
echo "Event published."
