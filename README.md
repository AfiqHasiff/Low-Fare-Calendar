# Low Fare Calendar

A Spring Boot 3.x service that aggregates the cheapest available flight price per day across 3 providers, with Redis caching, distributed singleflight protection, Pub/Sub-driven sold-out invalidation, and OpenTelemetry metrics.

## Prerequisites

- Java 21 (Virtual Threads required)
- Maven 3.9+
- Docker 20+ (with Docker Compose v2)

> **Colima users:** ensure Colima is running before starting Docker services:
>
> ```bash
> colima start
> ```

## Quick Start

### 1. Start infrastructure

```bash
docker-compose up -d
```

Starts Redis 7 on port 6379 and the GCP Pub/Sub emulator on port 8085.
Wait for both healthchecks to pass before proceeding.

### 2. Initialise Pub/Sub topic and subscription

```bash
bash scripts/init-pubsub.sh
```

Creates the `price-class-sold-out` topic and `price-class-sold-out-sub` subscription on the local emulator.

### 3. Start the application

```bash
./mvnw spring-boot:run
```

The API will be available at `http://localhost:8080`.

---

## Usage

### Get low fare calendar

```bash
curl "http://localhost:8080/api/v1/flights/calendar?origin=KUL&destination=SIN&month=2024-07&currency=MYR"
```

Returns a 31-day calendar with the lowest available price per day, converted to MYR.

### Publish a sold-out event

```bash
bash scripts/publish-sold-out.sh KUL SIN 2024-07-15
```

Triggers async cache invalidation and re-fetch for KUL→SIN on 2024-07-15.

### Trigger cache warm manually

```bash
bash scripts/trigger-cache-warm.sh
```

Forces the cache warmer to run immediately (calls `POST /admin/cache/warm`). Useful for testing warm-cycle behaviour without waiting for the 2-minute scheduled interval. The app must be running.

### Run the load test

```bash
bash scripts/load-test.sh
```

Fires 1000 requests in batches of 100 concurrent requests and reports success/failure counts.

### Run tests

```bash
./mvnw test
```

Runs all unit and integration tests. Integration tests use Testcontainers (Docker must be running).

### View metrics

```bash
curl http://localhost:8080/actuator/prometheus | grep lfc
```

Shows all `lfc.*` metrics including cache hits/misses, provider call durations, and circuit breaker states.

---

## Architecture

```text
HTTP Request
    │
    ▼
CalendarController  (GET /api/v1/flights/calendar)
    │
    ▼
CalendarService
    ├── RedisCache (hot-route TTL: 600s / standard TTL: 300s)
    │       └── on miss ──► SingleflightCoordinator (distributed Redis lock)
    │                               │
    │                               ▼
    │                       Provider Fan-out (parallel, virtual threads)
    │                         ├── ProviderA  (simulated latency + error-rate)
    │                         ├── ProviderB
    │                         └── ProviderC
    │                               │
    │                               ▼
    │                       Price Aggregation (min per day)
    │                               │
    │                               ▼
    │                       CurrencyConversion (USD → target)
    │                               │
    │                               ▼
    │                       Write back to cache
    │
    ▼
CalendarResponse (31-day map of date → price)

Async path:
PubSubSubscriber ──► SoldOutEvent ──► Cache Invalidation + Re-fetch
```

### Key design decisions

- **Providers**: Three simulated airline pricing providers (`provider-a`, `provider-b`, `provider-c`) called in parallel. The cheapest price per day wins.
- **Cache**: Redis with separate TTLs for hot routes vs standard routes. A jitter (±10–30%) is applied to prevent thundering herd on TTL expiry.
- **Singleflight**: A distributed Redis lock prevents duplicate fan-out calls when the cache is cold and multiple requests arrive simultaneously.
- **Pub/Sub**: Google Cloud Pub/Sub (or local emulator) delivers `price-class-sold-out` events. The subscriber invalidates the affected cache key and schedules a background re-fetch.
- **Circuit breaker**: Resilience4j circuit breakers wrap each provider call; open state falls back to cached/stale data.
- **Observability**: Micrometer + Prometheus exposition via `/actuator/prometheus`. All `lfc.*` metrics are tagged by route and provider.

---

## Configuration

All tuneable parameters live in `src/main/resources/application.yml` under the `lfc.*` prefix.
See the design spec at `docs/superpowers/specs/2026-06-20-low-fare-calendar-design.md` section 8 for a full simulation guide.

Key parameters:

| Property | Default | Effect |
| --- | --- | --- |
| `lfc.cache.hot-route-ttl-seconds` | 600 | TTL for frequently queried routes |
| `lfc.cache.standard-route-ttl-seconds` | 300 | TTL for standard routes |
| `lfc.providers.provider-a.error-rate` | 0.05 | Probability of provider-a returning an error |
| `lfc.pubsub.enabled` | true | Enable/disable async sold-out event processing |

To run without Pub/Sub (Redis only), set in `application.yml`:

```yaml
lfc:
  pubsub:
    enabled: false
```

Then skip steps 2 and the Pub/Sub emulator service is unused (though it will still start with `docker-compose up -d`).
