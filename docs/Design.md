# Low Fare Calendar — System Overview

> A single-source reference for understanding the architecture, data flow, design decisions, and assessment coverage of the Low Fare Calendar backend.

---

## Table of Contents

1. [What It Does](#1-what-it-does)
2. [Architecture](#2-architecture)
3. [Sequence Diagrams](#3-sequence-diagrams)
4. [Component Reference](#4-component-reference)
5. [Green Path vs Red Path](#5-green-path-vs-red-path)

---

## 1. What It Does

The Low Fare Calendar returns the cheapest available fare for every day in a given month, for a given route and requested currency. The data is aggregated from three mock flight providers, stored in Redis, and served with sub-millisecond cache reads at target scale of 1,000 TPS with P99 < 500 ms.

**API contract:**

```text
GET /api/v1/flights/calendar
    ?origin=KUL
    &destination=SIN
    &month=2024-07
    &currency=MYR        ← optional, defaults to USD
```

**Sample response (condensed):**

```json
{
  "origin": "KUL",
  "destination": "SIN",
  "month": "2024-07",
  "currency": "MYR",
  "days": [
    { "date": "2024-07-01", "lowestPrice": 224.05, "available": true,  "stale": false },
    { "date": "2024-07-02", "lowestPrice": null,   "available": false, "stale": false },
    { "date": "2024-07-15", "lowestPrice": 198.50, "available": true,  "stale": true  }
  ]
}
```

**What each field combination means:**

| `available` | `stale` | Meaning | When it happens |
| --- | --- | --- | --- |
| `true` | `false` | Fresh price, bookable | Normal cache hit or successful provider fetch |
| `true` | `true` | Price shown but may not be bookable | All providers failed; serving last-known-good from 24h fallback key |
| `false` | `false` | No price at all | No fallback exists yet (first-ever call) + providers failed, or providers returned no fares |

`available: false` always means `lowestPrice: null`. The UI should show "unavailable" rather than a price.  
`stale: true` means the price is real but potentially outdated — it was the cheapest price the last time providers were reachable. A user clicking through to book may find it's no longer available at that price.

---

## 2. Architecture

There are two distinct flows in this system. Understanding both is the key to understanding the whole design.

**Read flow (synchronous — happens on every API request):**
> Client → Controller → CalendarService → Redis (pipeline read) → for each miss: Singleflight + Lock → Providers → Redis (write) → currency conversion → response

**Async flow (event-driven — happens when a price class sells out):**
> Booking service → Pub/Sub → SoldOutEventListener → Redis (evict + idempotency check) → Providers → Redis (write back)

These two flows share Redis and `ProviderAggregationService` but are otherwise independent. A sold-out event never blocks an in-flight API request.

```text
┌──────────────────────────────────────────────────────────────────────┐
│                          Client / Browser                            │
└────────────────────────────┬─────────────────────────────────────────┘
                             │ GET /api/v1/flights/calendar
                             ▼
┌──────────────────────────────────────────────────────────────────────┐
│                      CalendarController                              │
│  • Validates request parameters                                      │
│  • Starts per-request trace (RequestTraceContext)                    │
│  • Writes trace file on response (RequestTraceWriter)                │
└────────────────────────────┬─────────────────────────────────────────┘
                             │
                             ▼
┌──────────────────────────────────────────────────────────────────────┐
│                       CalendarService                                │
│  • Pipeline-reads all 31 dates from Redis in one round-trip          │
│  • For each miss → InProcessSingleflight → CacheLockService          │
│  • Hot-route increment (fire-and-forget virtual thread)              │
│  • Currency conversion via CurrencyConverterRegistry                 │
└────┬───────────────────────────────────────────────────────┬─────────┘
     │ cache miss path                                        │ currency
     ▼                                                        ▼
┌──────────────────┐   ┌──────────────┐   ┌──────────────────────────┐
│ InProcessSingle- │   │ CacheLock-   │   │ CurrencyConverterRegistry│
│ flight           │──▶│ Service      │   │  UsdToMyrConverter        │
│ (ConcurrentHash- │   │ (SETNX lock, │   │  UsdToThbConverter        │
│  Map coalescing) │   │  Lua release,│   │  UsdToUsdConverter        │
└──────────────────┘   │  poll-wait)  │   └──────────────────────────┘
                       └──────┬───────┘
                              │ leader only
                              ▼
┌──────────────────────────────────────────────────────────────────────┐
│                  ProviderAggregationService                          │
│  • Fans out to all enabled providers in parallel (Virtual Threads)   │
│  • Each provider wrapped in Resilience4j CircuitBreaker              │
│  • Selects minimum price across successful quotes                    │
└──────┬─────────────────┬──────────────────┬───────────────────────┬─┘
       │                 │                  │                       │
       ▼                 ▼                  ▼                       ▼
  ProviderA          ProviderB          ProviderC            (Circuit open
  50–300ms           80–400ms           30–200ms              → skip call)
  5% error           10% error          2% error
  rate               rate               rate

┌──────────────────────────────────────────────────────────────────────┐
│                          Redis (cache tier)                          │
│                                                                      │
│  Primary key:   lfc:v1:{ORIGIN}:{DEST}:{YYYY-MM-DD}                 │
│  Fallback key:  lfc:v1:fallback:{ORIGIN}:{DEST}:{YYYY-MM-DD}        │
│  Lock key:      lock:lfc:{ORIGIN}:{DEST}:{YYYY-MM-DD}               │
│  Hot-route ZSET: lfc:hotroutes                                       │
└──────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────────┐
│               Async / Background Components                          │
│                                                                      │
│  SoldOutEventListener  ← GCP Pub/Sub "price-class-sold-out"          │
│    • Lua idempotency check on fallback key                           │
│    • Evict primary key → re-aggregate → write back                   │
│                                                                      │
│  CacheWarmingScheduler  (every 2 min)                                │
│    • Fetches top-50 hot routes from Redis ZSET                       │
│    • Re-fetches near-expiry dates proactively                        │
│                                                                      │
│  HotRouteDecayScheduler  (every 1 hr)                                │
│    • Prunes ZSET entries whose score fell below decay-min-score      │
└──────────────────────────────────────────────────────────────────────┘
```

**How to read the cache miss path in the diagram:**

The middle column (`InProcessSingleflight → CacheLockService → ProviderAggregationService`) only activates when a date is absent from Redis. The two boxes are two sequential gates:

1. **InProcessSingleflight** — same-JVM gate. Only one thread per pod does the work; others wait on a `CompletableFuture`.
2. **CacheLockService** — cross-pod gate. Only one pod across the cluster does the work; others poll Redis.

Only the thread that passes *both* gates reaches `ProviderAggregationService`. This is why the maximum provider call count is always 3, regardless of traffic volume.

**What Virtual Threads enable:** The `@Qualifier("virtualThreadExecutor")` means every `supplyAsync()` call spawns a lightweight virtual thread instead of borrowing from a fixed thread pool. Virtual threads are cheap enough (nanoseconds to create, ~1KB stack) that spawning one per provider per date per request is not a concern — the system can have thousands in flight simultaneously without exhausting OS threads.

**Where the schedulers run:** [`CacheWarmingScheduler`](../src/main/java/com/simulated/lowfarecalendar/scheduler/CacheWarmingScheduler.java) and [`HotRouteDecayScheduler`](../src/main/java/com/simulated/lowfarecalendar/scheduler/HotRouteDecayScheduler.java) are not separate services, separate JARs, or separate containers. They are Spring `@Component` beans running inside the **same JVM process** as the HTTP server. [`@EnableScheduling`](../src/main/java/com/simulated/lowfarecalendar/LowFareCalendarApplication.java#L11) on [`LowFareCalendarApplication`](../src/main/java/com/simulated/lowfarecalendar/LowFareCalendarApplication.java) activates Spring's internal scheduler thread pool at startup. The timers tick silently in the background while the same process is simultaneously handling HTTP requests and Pub/Sub events. There is exactly one process: the Spring Boot app. You do not need to deploy or restart anything separately to get scheduling to work — it starts automatically when the app starts.

---

## 3. Sequence Diagrams

### 3.1 Read Path — Calendar Request (Cache Hit)

The fast path for a warm cache. All 31 dates exist in Redis; no providers are called. This is the steady-state for any popular route after the first request (or the cache warmer) has already populated the keys.

**What to look for:** The providers column is absent entirely. The only external I/O is one Redis pipeline call that fetches all 31 dates in a single network round-trip. Everything else — ZSET scoring, currency conversion — is either fire-and-forget or pure in-memory math. No additional network calls are made after the pipeline GET returns.

```text
Client          Controller       CalendarService      Redis
  │                │                   │                │
  │── GET /cal ───▶│                   │                │
  │                │── getCalendar() ─▶│                │
  │                │                   │─ PIPELINE GET ─▶
  │                │                   │  (31 GETs, 1   │
  │                │                   │   round-trip)  │
  │                │                   │◀── 31 entries ─│
  │                │                   │                │
  │                │                   │── VT: increment ZSET (fire-and-forget)
  │                │                   │                │
  │                │                   │  convert USD→MYR (in-memory, no I/O)
  │                │                   │                │
  │◀── 200 OK ─────│◀── CalendarResp ──│                │
```

**Step-by-step walkthrough:**

1. **Client → Controller:** HTTP GET arrives. `CalendarController` validates `origin`, `destination`, `month`, and `currency` params. A `RequestTrace` object is started to record what happens for this request.
2. **Controller → CalendarService:** `getCalendar()` is called with the validated params.
3. **CalendarService → Redis (PIPELINE GET):** Rather than issuing 31 individual `GET` commands, `FareCacheService.pipelineGet()` batches all 31 date-keyed lookups into a single pipeline. Redis executes them in one round-trip and returns 31 entries.
4. **All 31 entries present:** `CalendarService` iterates the results. Every date has a cached value — no misses. The miss-resolution loop (`resolveMissForDate`) is skipped entirely.
5. **VT: increment ZSET (fire-and-forget):** `HotRouteTracker.increment()` is called on a separate virtual thread. The calling thread does not wait for it. The ZSET score update for this route is eventually-consistent — the response is not delayed for it.
6. **In-memory currency conversion:** `CurrencyConverterRegistry` applies the exchange rate for the requested currency (e.g. USD → MYR). This is a local map lookup — no Redis or HTTP call.
7. **Response:** `CalendarResponse` is built and returned. The `RequestTraceWriter` writes the JSON trace file in the controller's `finally` block.

**Typical latency:** 5–15 ms (Redis pipeline + in-memory currency math)

---

### 3.2 Read Path — Cache Miss with Thundering Herd Protection

The first request after a cold start or cache eviction. Shows the two-layer singleflight mechanism that prevents a storm of simultaneous cache misses from triggering hundreds of redundant provider calls.

**What to look for:** Client A and Client B arrive almost simultaneously. Client A's request becomes the leader through the singleflight gate (`putIfAbsent` wins the race). Client B's request is *not shown doing anything* in the middle of the diagram — it is parked, waiting on A's `CompletableFuture`. Only A touches `CacheLockService` and the providers. When A writes the result to Redis, B's second `pipelineGet` (bottom of diagram) finds all keys present — a full cache hit. B never contacted a single provider.

```text
Client A   Client B   Controller   CalendarSvc   Singleflight   CacheLock      Providers      Redis
   │           │           │             │              │              │        A  B  C          │
   │──GET──▶   │           │             │              │              │                         │
   │           │──GET──▶   │             │              │              │                         │
   │           │           │──getCalendar─▶            │              │                         │
   │           │           │             │──pipelineGet─────────────────────────────────────────▶
   │           │           │             │◀── [miss, miss, ...]──────────────────────────────────│
   │           │           │             │              │              │                         │
   │           │           │             │──getOrFetch(key)▶          │                         │
   │           │           │             │   (putIfAbsent wins)       │                         │
   │           │           │             │   leader=true              │──tryAcquire(SETNX)──────▶
   │           │           │             │                            │◀── lock token ───────────│
   │           │           │             │                            │──── aggregate() ──▶A  B  C
   │           │           │             │                            │◀─────────── quotes ──────│
   │           │           │             │                            │                         │
   │           │           │             │   A gets the future        │──set(primary+fallback)──▶
   │           │           │             │                            │──release(Lua DEL)───────▶
   │           │           │             │                            │                         │
   │           │─▶  getCalendar ──▶       │                           │                         │
   │           │             │──pipelineGet────────────────────────────────────────────────────▶
   │           │             │◀── [HIT, HIT, ...]──────────────────────────────────────────────│
   │◀── 200 ───│◀── 200 ─────│            │              │              │                         │
```

**Step-by-step walkthrough:**

1. **Both clients miss:** Client A and Client B each receive their pipeline GET result. All 31 dates return `null` — the cache is cold. Both requests independently enter `resolveMissForDate()` for each missing date.
2. **Layer 1 — in-process race (`InProcessSingleflight`):** For a given cache key, both threads call `ConcurrentHashMap.putIfAbsent(key, future)`. This is an atomic JVM operation. One thread wins (Client A becomes the leader); the other (Client B) sees a non-null return value from `putIfAbsent` and parks on the existing `CompletableFuture`.
3. **Layer 2 — cross-pod race (`CacheLockService`):** Client A's thread calls `tryAcquire(key)`, which executes Redis `SETNX lfc:lock:{key} {uuid} PX 5000`. If another pod already holds the lock, A enters a 50 ms poll loop (up to 4 s) waiting for the lock to be released and the value to appear. If A wins the SETNX, it proceeds as the single cross-pod leader.
4. **Provider fetch:** With the lock held, A calls `ProviderAggregationService.aggregate()`. Three `CompletableFuture.supplyAsync()` calls scatter to all three providers in parallel. Wall-clock time = slowest provider, not sum of all three.
5. **Write to Redis:** A calls `FareCacheService.setWithJitter()` for each of the 31 dates. Each write updates both the primary key (with TTL + jitter) and the fallback key (24h TTL, never deleted). The Lua script releases the Redis lock atomically.
6. **Client B unparks:** Client A's `CompletableFuture.complete(result)` call wakes up Client B. B gets the result without touching any provider or Redis GET — it uses the object already resolved in memory.
7. **Both respond:** Both requests return `200 OK` with the same data. Total provider calls: 3. Without thundering herd protection on a 100-pod deployment under a traffic spike, this could have been 300.

**Redis writes at step 5 — what is actually stored:**

```text
SET lfc:v1:KUL:SIN:2024-07-15
    {"lowestPrice":224.05,"available":true,"stale":false,"updatedAt":"2024-07-10T08:32:11Z"}
    PX 299520   ← primary key; 240s base + ~24.8% jitter = ~299.5s (hot route)

SET lfc:v1:fallback:KUL:SIN:2024-07-15
    {"lowestPrice":224.05,"available":true,"stale":false,"updatedAt":"2024-07-10T08:32:11Z"}
    PX 86400000  ← fallback key; 24h, never actively deleted
```

**In-process coalescing (same pod):** If 500 requests arrive within the same JVM during a miss, `InProcessSingleflight` (via `ConcurrentHashMap.putIfAbsent`) ensures only one executes the fetcher. The remaining 499 join the same `CompletableFuture` and receive the result when it resolves — zero extra provider calls.

**Cross-instance coalescing (multiple pods):** If a second pod also gets the miss, `CacheLockService.tryAcquire()` (Redis SETNX) means only one pod runs the provider fetch. The other pod polls Redis every 50 ms (up to 4 s) and reads the value once it appears.

**Why both layers are needed:** The in-process layer is invisible to other pods — each pod has its own `ConcurrentHashMap`. Without the Redis lock, 3 pods would each elect their own in-process leader, resulting in 3 × 3 = 9 provider calls. The layers work together: in-process coalesces within a pod, Redis coalesces across pods.

---

### 3.3 Async Sold-Out Event — Price Update Flow

Triggered by the booking service publishing to the `price-class-sold-out` Pub/Sub topic.

**What to look for:** This entire flow is asynchronous — the booking service publishes and immediately moves on; it does not wait for the calendar to update. The key step is the Lua idempotency check *before* any eviction happens. This is the guard that prevents duplicate events (Pub/Sub at-least-once delivery) from triggering redundant provider calls. Only after `PROCEED` is returned does the evict → re-aggregate → write sequence run.

```text
BookingSvc     Pub/Sub        SoldOutEventListener        Redis          Providers A/B/C
    │              │                   │                    │                │
    │──publish ───▶│                   │                    │                │
    │  { origin,   │──deliver msg ────▶│                    │                │
    │    dest,      │                  │                    │                │
    │    date,      │                  │──Lua idempotency ─▶│                │
    │    generatedAt}                  │  (compare          │                │
    │               │                  │   generatedAt vs   │                │
    │               │                  │   fallback.updatedAt)              │
    │               │                  │◀── PROCEED ────────│                │
    │               │                  │                    │                │
    │               │                  │──evict(primary) ──▶│                │
    │               │                  │──aggregate() ──────────────────────▶
    │               │                  │◀──────────── quotes (parallel) ─────│
    │               │                  │                    │                │
    │               │                  │──set(primary)─────▶│                │
    │               │                  │──set(fallback)────▶│                │
    │               │                  │──ack() ────────────│                │
    │               │◀── ack ──────────│                    │                │
```

**Step-by-step walkthrough:**

1. **Booking service publishes:** When a price class sells out, the booking service publishes a JSON message to the `price-class-sold-out` Pub/Sub topic. The booking service does not wait for any acknowledgement; it immediately moves on. The message payload:

```json
{
  "origin": "KUL",
  "destination": "SIN",
  "date": "2024-07-15",
  "generatedAt": "2024-07-10T08:32:11Z"
}
```

`generatedAt` is the timestamp of when the booking event actually occurred — set by the booking service, not by Pub/Sub delivery time. This is the field the Lua script compares against to determine freshness.
2. **Pub/Sub delivers to listener:** The GCP Pub/Sub infrastructure delivers the message to the `SoldOutEventListener` subscriber. Pub/Sub guarantees at-least-once delivery — the same message may arrive more than once if the previous ack was lost or delayed.
3. **Lua idempotency check (before any write):** `SoldOutEventListener` runs a Lua script on Redis *before* touching the primary key. The script atomically reads the `updatedAt` field from the fallback key and compares it to the event's `generatedAt`. If `generatedAt > updatedAt` (the event is newer than what's cached), the script returns `PROCEED`. If not, it returns `STALE`. This is a single atomic Redis operation — no race condition is possible between the read and the comparison.
4. **Evict primary key:** On `PROCEED`, the listener deletes the primary key for that date. This forces the next read request to treat it as a cache miss and re-fetch fresh data from providers.
5. **Re-aggregate from providers:** `ProviderAggregationService.aggregate()` is called. The three provider calls run in parallel, same as the read path.
6. **Write primary + fallback:** The fresh quotes are written back to Redis. The fallback key is updated with `updatedAt = generatedAt`, establishing the new idempotency baseline for future duplicate events.
7. **Ack:** The listener acks the message. Pub/Sub marks it as delivered and will not re-deliver it (unless the ack is lost, in which case the next delivery will be caught by the idempotency check).

**Idempotency guarantee:** If the same event is re-delivered (Pub/Sub at-least-once), the Lua script compares `generatedAt` against the already-written `updatedAt`. A duplicate returns `STALE` → acked without any write. A late-arriving earlier event is also discarded the same way.

**Why the fallback key is the idempotency record:** The fallback key is never actively deleted, so it always holds the `updatedAt` timestamp of the last successful write. The Lua script reads this timestamp and uses it as the baseline for the "is this event newer?" comparison. If the fallback were deleted (e.g. on eviction), the script would see no entry and return `PROCEED` for every event, including duplicates.

---

### 3.4 Sold-Out Event — Late/Out-of-Order Event (Discarded)

**What to look for:** A T1 event (generated first, delivered late) arrives after a T2 event (generated later) has already been processed and written `updatedAt=T2` to the fallback key. The Lua condition `T1 <= T2` is true — the incoming event is older than what's already in the cache — so it returns `STALE`. The listener acks it silently. No eviction, no provider call, no write. The cache retains the more up-to-date T2 result.

```text
BookingSvc     Pub/Sub        SoldOutEventListener        Redis
    │              │                   │                    │
    │  (late event, generatedAt=T1)     │                    │
    │──publish ───▶│──deliver ────────▶│                    │
    │               │                  │──Lua idempotency ─▶│
    │               │                  │  T1 <= fallback.   │
    │               │                  │  updatedAt (T2)    │
    │               │                  │◀── STALE ──────────│
    │               │                  │──ack() (no write)  │
    │               │◀── ack ──────────│                    │
```

**Step-by-step walkthrough:**

1. **Setup — two events, out-of-order delivery:** Imagine two sold-out events for the same flight date. T2 was generated later (e.g. a second price class sold out), and was delivered first. The listener processed T2: it ran the idempotency check, fetched providers, and wrote `updatedAt=T2` to the fallback key.
2. **Late T1 event arrives:** The T1 event (generated first, delivered late — perhaps Pub/Sub held it during a transient delivery delay) now arrives at the listener.
3. **Lua idempotency check:** The script reads the fallback key and finds `updatedAt=T2`. It compares: `T1 <= T2`. This is `true` — T1 is older than the data already in the cache.
4. **STALE returned:** The script returns `STALE`. The listener receives this result.
5. **Ack, no write:** The listener calls `ack()` immediately. No eviction, no provider call, no Redis write of any kind. The cache retains the T2 result, which is the more up-to-date one.

**Why this matters:** Without this check, a late-arriving stale event would evict the current (fresher) cache entry and replace it with older data. The `generatedAt` timestamp on the event message is the key — it is set by the booking service at the moment the sold-out event happened, not at delivery time, so Pub/Sub delivery order is irrelevant. The Lua check always keeps the most recently generated data.

---

### 3.5 Circuit Breaker — Provider Failure + Fallback

**What to look for:** The circuit breaker has three states. **CLOSED** (normal) — calls pass through. **OPEN** — calls are immediately rejected without touching the provider (`CallNotPermittedException` is thrown before any network call). **HALF-OPEN** — a small number of test calls are allowed through; if they succeed the circuit closes, if they fail it reopens. The diagram shows the transition from CLOSED to OPEN after repeated failures, and then the fallback path when all providers are OPEN simultaneously. Notice that `getFallback()` reads the 24h fallback key — the response is `stale: true`, not an error.

```text
CalendarSvc   ProviderAggregationService   CircuitBreaker   ProviderA   Redis
    │                   │                       │               │          │
    │── aggregate() ───▶│                       │               │          │
    │                   │── supplyAsync ────────▶── getFares() ─▶          │
    │                   │                       │◀─ Exception ──│          │
    │                   │                       │  (error #N)   │          │
    │                   │                       │               │          │
    │    [after 10 calls, ≥50% failures → CB opens]            │          │
    │                   │                       │               │          │
    │── aggregate() ───▶│── supplyAsync ────────▶               │          │
    │                   │                       │  CallNotPermitted        │
    │                   │                       │  (circuit OPEN)│         │
    │                   │◀── Optional.empty ────│               │          │
    │                   │                       │               │          │
    │  [if ALL providers fail]                                             │
    │── getFallback() ───────────────────────────────────────────────────▶│
    │◀─ stale entry ─────────────────────────────────────────────────────│
    │  (stale=true in response)                                            │
```

**Step-by-step walkthrough:**

1. **Normal operation (CLOSED):** Incoming `aggregate()` calls pass through the circuit breaker. Each `supplyAsync` launches a virtual thread that calls the provider. Exceptions (timeouts, HTTP errors) are caught and recorded against the sliding window.
2. **Threshold crossed → CB opens:** Resilience4j tracks the last N calls in a sliding window (configured as `sliding-window-size: 6`). Once the failure rate reaches `failure-rate-threshold: 50%` (i.e. 3 of 6 calls failed), the circuit transitions to **OPEN**. This happens independently per provider — ProviderA can be OPEN while ProviderB is still CLOSED.
3. **OPEN state — fast-fail:** On the next `aggregate()` call, the circuit breaker intercepts the call *before* it reaches the provider. It throws `CallNotPermittedException` immediately. No network call is made. The failed attempt does not count against the sliding window (it was never a real call). This protects the provider from being further overwhelmed.
4. **`Optional.empty` returned:** `ProviderAggregationService` catches `CallNotPermittedException` and maps it to `Optional.empty()`. Other providers (if still CLOSED) continue to contribute their quotes. Only if all three providers return `Optional.empty()` does the aggregation itself return empty.
5. **All providers failed — fallback read:** `CalendarService` detects that `aggregate()` returned no quotes. It calls `FareCacheService.getFallback()`, which reads the fallback key (`lfc:v1:fallback:{origin}:{dest}:{date}`). This key has a 24h TTL and was never deleted — it holds the last known-good snapshot of prices for this date.
6. **Stale response:** The fallback data is returned to the client with `available: true, stale: true`. The client gets a price (it can be shown in the UI) but the system signals it may be outdated. This is far better than a `503` — the user sees something rather than nothing.
7. **HALF-OPEN recovery:** After `wait-duration-in-open-state-ms: 5000` (5 seconds), the circuit transitions to **HALF-OPEN**. Up to `permitted-calls-in-half-open: 3` test calls are allowed through. If they succeed, the circuit closes and normal operation resumes. If they fail, the circuit reopens for another 5 seconds.

**Why per-provider circuit breakers:** If one circuit breaker were shared across all providers, a surge of failures from ProviderA would open the single breaker and block ProviderB and ProviderC too — even if they're healthy. Per-provider breakers ensure that one unreliable provider is isolated without degrading the others.

---

## 4. Component Reference

### [CalendarController](../src/main/java/com/simulated/lowfarecalendar/controller/CalendarController.java)

**Package:** `controller`  
**Role:** HTTP entry point. Validates and parses request params (`origin`, `dest`, `month`, `currency`). Starts the per-request `RequestTrace` and writes the JSON trace file after the response is built.  
**Key method:** [`getCalendar()`](../src/main/java/com/simulated/lowfarecalendar/controller/CalendarController.java#L51)  
**Injects:** `CalendarService`, [`RequestTraceContext`](../src/main/java/com/simulated/lowfarecalendar/trace/RequestTraceContext.java), `Optional<RequestTraceWriter>`

**Why it exists as a separate layer:** HTTP is a system boundary. All input validation (null checks, format checks on origin/dest/month/currency) must happen here before anything touches Redis or providers. It also owns the trace lifecycle — starting it before any work begins and writing the file in a `finally` block after the response is built, so the trace always captures the full picture including currency conversion results.

---

### [CalendarService](../src/main/java/com/simulated/lowfarecalendar/service/CalendarService.java)

**Package:** `service`  
**Role:** Orchestrates the full read path for one calendar request. Fires the hot-route increment asynchronously, executes the Redis pipeline read for all dates in one round trip, and calls `resolveMissForDate()` for each absent entry. Converts the final list to the requested currency.  
**Key methods:** [`getCalendar()`](../src/main/java/com/simulated/lowfarecalendar/service/CalendarService.java#L81), [`resolveMissForDate()`](../src/main/java/com/simulated/lowfarecalendar/service/CalendarService.java#L156), [`toDayPrice()`](../src/main/java/com/simulated/lowfarecalendar/service/CalendarService.java#L216)  
**Injects:** [`FareCacheService`](../src/main/java/com/simulated/lowfarecalendar/cache/FareCacheService.java), [`InProcessSingleflight`](../src/main/java/com/simulated/lowfarecalendar/cache/InProcessSingleflight.java), [`CacheLockService`](../src/main/java/com/simulated/lowfarecalendar/cache/CacheLockService.java), [`ProviderAggregationService`](../src/main/java/com/simulated/lowfarecalendar/service/ProviderAggregationService.java), [`CurrencyConverterRegistry`](../src/main/java/com/simulated/lowfarecalendar/currency/CurrencyConverterRegistry.java), [`HotRouteTracker`](../src/main/java/com/simulated/lowfarecalendar/cache/HotRouteTracker.java), [`CacheMetricsService`](../src/main/java/com/simulated/lowfarecalendar/observability/CacheMetricsService.java)

**Step-by-step for one request:**

1. Fire [`HotRouteTracker.increment()`](../src/main/java/com/simulated/lowfarecalendar/cache/HotRouteTracker.java#L28) on a virtual thread — fire-and-forget, does not block the response
2. Send one Redis pipeline GET for all 31 dates via [`FareCacheService.pipelineGet()`](../src/main/java/com/simulated/lowfarecalendar/cache/FareCacheService.java#L72) in a single network round-trip
3. For each date that came back empty, call [`resolveMissForDate()`](../src/main/java/com/simulated/lowfarecalendar/service/CalendarService.java#L156) — this is where thundering herd protection kicks in
4. `resolveMissForDate()` uses [`InProcessSingleflight`](../src/main/java/com/simulated/lowfarecalendar/cache/InProcessSingleflight.java) (same-JVM coalescing) then [`CacheLockService`](../src/main/java/com/simulated/lowfarecalendar/cache/CacheLockService.java) (cross-pod lock) before calling providers
5. Once all dates are resolved, look up the [`CurrencyConverter`](../src/main/java/com/simulated/lowfarecalendar/currency/CurrencyConverter.java) once and convert every price in memory
6. Build and return the [`CalendarResponse`](../src/main/java/com/simulated/lowfarecalendar/model/CalendarResponse.java)

**Non-obvious — why the miss loop is sequential, not parallel:** Steps 3–4 process missed dates in a `for` loop ([`CalendarService.java#L112`](../src/main/java/com/simulated/lowfarecalendar/service/CalendarService.java#L112)), one at a time. Running them in parallel would spawn 31 concurrent singleflight attempts, each of which fans out to 3 providers — 93 simultaneous outbound connections per request during a cold start. The parallelism lives *inside* `resolveMissForDate()` where the 3 providers are called concurrently. The outer loop stays sequential to bound the blast radius.
---

### [AdminController](../src/main/java/com/simulated/lowfarecalendar/controller/AdminController.java)

**Package:** `controller`  
**Role:** Admin REST controller exposing two manual trigger endpoints. `POST /admin/cache/warm` calls `CacheWarmingScheduler.warm()` directly. `POST /admin/hot-routes/decay` calls `HotRouteDecayScheduler.decay()` directly. Both bypass the scheduled countdown so they execute immediately, useful for testing without waiting for the timed intervals.  
**Key methods:** [`triggerCacheWarm()`](../src/main/java/com/simulated/lowfarecalendar/controller/AdminController.java#L27), [`triggerHotRouteDecay()`](../src/main/java/com/simulated/lowfarecalendar/controller/AdminController.java#L34)  
**Trigger scripts:** `scripts/trigger-cache-warm.sh`, `scripts/trigger-hot-route-decay.sh`

---

### [ProviderAggregationService](../src/main/java/com/simulated/lowfarecalendar/service/ProviderAggregationService.java)

**Package:** `service`  
**Role:** Scatter-gather fan-out to all enabled providers using `CompletableFuture.supplyAsync()` on virtual threads. Wraps each call in its Resilience4j `CircuitBreaker`. Returns the minimum price across all successful quotes, or `Optional.empty()` if every provider fails.  
**Key method:** [`aggregate(FlightQuery)`](../src/main/java/com/simulated/lowfarecalendar/service/ProviderAggregationService.java#L62)  
**Injects:** [`List<FlightProvider>`](../src/main/java/com/simulated/lowfarecalendar/provider/FlightProvider.java), `CircuitBreakerRegistry`

**Why parallel matters — the math:**

Calling providers sequentially: `50–300ms + 80–400ms + 30–200ms = up to 900ms` — blows the P99 < 500ms budget immediately.

Calling providers in parallel: wall-clock time = `max(A, B, C)` = up to 400ms — all three start at the same instant and you wait only for the slowest one.

```text
// All three futures start at the same moment
Future<A> = supplyAsync(() -> providerA.getFares(query))
Future<B> = supplyAsync(() -> providerB.getFares(query))
Future<C> = supplyAsync(() -> providerC.getFares(query))

allOf(A, B, C).join()   // blocks until the slowest one finishes
// Total wait: ~400ms worst-case, not ~900ms
```

**The ThreadLocal capture problem:** [`RequestTraceContext`](../src/main/java/com/simulated/lowfarecalendar/trace/RequestTraceContext.java) stores per-request trace data in a `ThreadLocal` on the HTTP request thread. When `supplyAsync()` spawns virtual threads, those threads do NOT inherit the parent's ThreadLocal. Accessing [`traceContext.current()`](../src/main/java/com/simulated/lowfarecalendar/trace/RequestTraceContext.java#L24) inside a lambda would return `null` and no provider data would be recorded. The fix: capture `RequestTrace trace = traceContext.current()` on the calling thread *before* spawning, then pass `trace` directly into each lambda as a closed-over variable.

**Non-obvious — `Optional.empty()` is not an error:** When all providers fail, [`aggregate()`](../src/main/java/com/simulated/lowfarecalendar/service/ProviderAggregationService.java#L62) returns `Optional.empty()` cleanly. The caller ([`CalendarService`](../src/main/java/com/simulated/lowfarecalendar/service/CalendarService.java)) interprets this as "try the 24h fallback key". Throwing an exception here would bypass the fallback logic entirely and return a 500 to the user instead of a `stale: true` response.

---

### [FareCacheService](../src/main/java/com/simulated/lowfarecalendar/cache/FareCacheService.java)

**Package:** `cache`  
**Role:** All Redis read/write operations. Owns the key scheme. Writes two keys on every set: the primary key (TTL = hot or standard + jitter) and the fallback key (TTL = 24 h, never evicted). Pipeline-reads all dates for a month in a single Redis round-trip.  
**Key methods:** [`pipelineGet()`](../src/main/java/com/simulated/lowfarecalendar/cache/FareCacheService.java#L72), [`set()`](../src/main/java/com/simulated/lowfarecalendar/cache/FareCacheService.java#L54), [`evict()`](../src/main/java/com/simulated/lowfarecalendar/cache/FareCacheService.java#L64), [`getFallback()`](../src/main/java/com/simulated/lowfarecalendar/cache/FareCacheService.java#L48), [`getRemainingTtlSeconds()`](../src/main/java/com/simulated/lowfarecalendar/cache/FareCacheService.java#L68), [`computeTtlMs()`](../src/main/java/com/simulated/lowfarecalendar/cache/FareCacheService.java#L95)

**Cache key design — why each segment is chosen:**

```text
lfc  :  v1  :  KUL  :  SIN  :  2024-07-15
 │       │      │       │          │
 │       │      │       │          └─ full date (not just month) — one key per day,
 │       │      │       │              so a sold-out event evicts only the affected date,
 │       │      │       │              not the whole month
 │       │      │       └─ destination IATA code — uppercase, standardised in code
 │       │      └─ origin IATA code — same normalisation
 │       └─ key version — changing to "v2" instantly busts ALL existing v1 keys
 │           on a breaking schema change, no need to flush Redis manually
 └─ "lfc" namespace — prevents collisions when multiple services share one Redis cluster
     e.g. a bookings service using "booking:v1:KUL:SIN" won't clash

lfc:v1:fallback:KUL:SIN:2024-07-15
  └─ "fallback" segment makes it visually and programmatically distinct from the
     primary key — grep/scan in Redis immediately shows which keys are safety snapshots

lock:lfc:KUL:SIN:2024-07-15
  └─ "lock:" prefix puts all lock keys in their own namespace — easy to scan separately,
     and a TTL misconfiguration on data keys can never accidentally expire a lock key
```

**What is actually stored inside a cache key (the payload):**

Each primary and fallback key stores a JSON-serialised [`CachedFareEntry`](../src/main/java/com/simulated/lowfarecalendar/model/CachedFareEntry.java) object:

```json
{
  "lowestPrice": 224.05,
  "available": true,
  "stale": false,
  "updatedAt": "2024-07-10T08:32:11Z"
}
```

- `lowestPrice` — the minimum USD price returned by providers at fetch time (always USD in Redis; currency conversion happens at response time, never stored)
- `available` — false only when providers returned nothing and no fallback existed
- `stale` — true only when this value came from the fallback key path (all providers failed)
- `updatedAt` — the timestamp of when this entry was written; the fallback key's `updatedAt` is what the Lua idempotency script reads to compare against `event.generatedAt`

**Why prices are stored in USD, not the requested currency:** The requested currency is a query parameter that can differ per caller. Storing one value per currency per date would require N writes per cache entry (one for MYR, one for THB, one for USD). Storing USD once and converting on read means one write, unlimited currency support, and exchange rate changes require zero cache invalidation.

**Why pipeline reads matter:** Without pipelining, fetching 31 dates costs 31 sequential Redis round-trips (~1ms each = ~31ms). With `executePipelined()`, all 31 GETs are batched into one TCP packet, Redis executes them all, and one response comes back — ~1–3ms total regardless of month length. At 1,000 TPS the difference is 31,000 Redis connections/second vs 1,000.

**Two-key design — why both keys exist:**

| Key | TTL | Used for | Ever actively deleted? |
| --- | --- | --- | --- |
| Primary | 4 min (hot) / 10 min (standard) + jitter | Fast read path for live requests | Yes — on sold-out event; expires naturally |
| Fallback | 24 hours | Last-known-good snapshot for outages | No — only overwritten with newer data |

The fallback exists because the primary can be evicted (sold-out event) or expire at the exact moment all three providers are down. Without a fallback, users would get `available: false` during any provider outage. The fallback guarantees there is always *something* to return, even if slightly stale.

**Why jitter on the primary TTL?** If all 31 dates were set with the same TTL (e.g. 240s for hot routes), they all expire at the same instant. At T+240s: 31 simultaneous cache misses, 31 × 3 = 93 provider calls — a self-inflicted thundering herd from TTL synchronisation. Jitter of +10% to +30% staggers expiry across a ~48-second window, so at most a few keys expire at any given moment.

---

### [InProcessSingleflight](../src/main/java/com/simulated/lowfarecalendar/cache/InProcessSingleflight.java)

**Package:** `cache`  
**Role:** First layer of thundering herd protection. Ensures only one thread per JVM executes the provider fetch for a given cache-missed date. All other callers for the same key wait for that one thread's result.

**The problem it solves:** A popular route's cache expires and 500 requests arrive on the same pod simultaneously. Without protection, all 500 see a cache miss and all 500 call all 3 providers — 1,500 outbound calls for one date. This is the thundering herd problem within a single JVM.

**How the "slot" mechanism works — think of it as a noticeboard:**

```java
ConcurrentHashMap<String, CompletableFuture<CachedFareEntry>> inFlight;

// Request A arrives first for "KUL:SIN:2024-07-15":
CompletableFuture myFuture = new CompletableFuture<>();
CompletableFuture existing = inFlight.putIfAbsent("KUL:SIN:2024-07-15", myFuture);
// existing == null → slot was empty, A just claimed it → A is the LEADER
// A calls providers, gets result, calls myFuture.complete(result)

// Requests B through 500 arrive while A is still fetching:
CompletableFuture existing = inFlight.putIfAbsent("KUL:SIN:2024-07-15", theirFuture);
// existing != null → slot already taken → FOLLOWER
// Each follower calls existing.join() and parks here, consuming no CPU
// When A completes, all 499 followers wake up and receive the same result
```

Result: 500 requests, exactly 3 provider calls (one parallel fan-out by the leader).

**Why `putIfAbsent` not `computeIfAbsent`:** `computeIfAbsent` holds the internal segment lock of `ConcurrentHashMap` for the entire duration of the compute function. Under Java 21 Virtual Threads, the thread holding this lock can be descheduled mid-execution (e.g. while waiting on a blocking I/O call inside the supplier). Other virtual threads needing that map segment then cannot proceed — a deadlock. `putIfAbsent` is atomic but non-blocking: it checks-and-writes in one CPU instruction and returns immediately, never holding any lock across a blocking operation. See [`InProcessSingleflight.getOrFetch()`](../src/main/java/com/simulated/lowfarecalendar/cache/InProcessSingleflight.java#L36) — the comment in the source explains this choice explicitly.

---

### [CacheLockService](../src/main/java/com/simulated/lowfarecalendar/cache/CacheLockService.java)

**Package:** `cache`  
**Role:** Second layer of thundering herd protection — cross-pod. Uses Redis SETNX to elect one leader across all running pods. Non-leaders poll the primary cache key until the leader writes the result.

**The problem it solves:** `InProcessSingleflight` only protects within one JVM. A deployed service runs multiple pods. If 3 pods all see a cache miss for the same date simultaneously, each pod's singleflight elects its own local leader — 3 leaders × 3 providers = 9 provider calls for one date. `CacheLockService` makes Redis the shared noticeboard so only one pod across the entire cluster does the work.

**Step-by-step — what happens across two pods:**

```text
Pod 1 calls tryAcquire("KUL:SIN:2024-07-15"):
  SET lock:lfc:KUL:SIN:2024-07-15  "550e8400-e29b-41d4-a716-446655440000"  NX PX 5000
  ↑ key                             ↑ UUID token (pod-unique)                   ↑ 5s auto-expiry
  Redis returns "OK" (key didn't exist) → Pod 1 is the LEADER

  Pod 1 fans out to 3 providers, gets quotes back:
    ProviderA → { "price": 224.05 }
    ProviderB → { "price": 249.99 }
    ProviderC → { "price": 198.50 }   ← min selected

  Pod 1 writes:
    SET lfc:v1:KUL:SIN:2024-07-15 {"lowestPrice":198.50,...}  PX 374000
    SET lfc:v1:fallback:KUL:SIN:2024-07-15 {"lowestPrice":198.50,...}  PX 86400000

  Pod 1 releases lock (Lua script — only deletes if token matches):
    GET lock:lfc:KUL:SIN:2024-07-15 → "550e8400-..." == stored token → DEL
    Redis returns 1 (deleted) → lock released

Pod 2 calls tryAcquire("KUL:SIN:2024-07-15") while Pod 1 holds the lock:
  SET lock:lfc:KUL:SIN:2024-07-15  "7f3d9a12-..."  NX PX 5000
  Redis returns nil (key already exists) → Pod 2 is a FOLLOWER
  Pod 2 enters pollForResult() — polls primary key every 50ms for up to 4s:
    GET lfc:v1:KUL:SIN:2024-07-15 → nil  (Pod 1 still fetching)
    GET lfc:v1:KUL:SIN:2024-07-15 → nil
    GET lfc:v1:KUL:SIN:2024-07-15 → {"lowestPrice":198.50,...}  ← Pod 1 wrote it
  Pod 2 returns value immediately. ZERO provider calls made by Pod 2.
```

**Why the lock stores a UUID token (not just "locked"):** The lock release uses a Lua atomic script (see [`CacheLockService.release()`](../src/main/java/com/simulated/lowfarecalendar/cache/CacheLockService.java#L52)):

```lua
if redis.call('GET', KEYS[1]) == ARGV[1] then
  return redis.call('DEL', KEYS[1])
end
```

Without the token: Pod 1 acquires the lock, then crashes. The lock auto-expires after 5s. Pod 3 acquires a new lock. Pod 1 recovers and its cleanup code issues a plain DEL — accidentally deleting Pod 3's lock. Pod 4 now also thinks it can proceed and two pods fetch simultaneously. The UUID token prevents this: Pod 1's token no longer matches what's in Redis, so the Lua script is a no-op and Pod 3's lock remains intact.

**The 5-second auto-expiry (`EX 5s`) is a dead-man switch.** If the leader pod crashes after acquiring the lock but before releasing it, the lock would be held forever, blocking all followers permanently. The expiry self-releases the lock even if the leader dies.

**Maximum provider calls regardless of how many pods: exactly 3** — one parallel fan-out by the elected leader.

---

### [HotRouteTracker](../src/main/java/com/simulated/lowfarecalendar/cache/HotRouteTracker.java)

**Package:** `cache`  
**Role:** Tracks query frequency per route+month using a Redis Sorted Set (`ZINCRBY`). Routes with a score above `hot-threshold` receive a **shorter TTL** for more frequent price refreshes. Used by `CacheWarmingScheduler` to decide which routes to pre-warm.  
**ZSET key:** `hot_routes`  
**Member format:** `ORIGIN:DEST:YYYY-MM`  
**Key methods:** [`increment()`](../src/main/java/com/simulated/lowfarecalendar/cache/HotRouteTracker.java#L28), [`isHot()`](../src/main/java/com/simulated/lowfarecalendar/cache/HotRouteTracker.java#L36), [`getTopK()`](../src/main/java/com/simulated/lowfarecalendar/cache/HotRouteTracker.java#L46), [`pruneBelow()`](../src/main/java/com/simulated/lowfarecalendar/cache/HotRouteTracker.java#L66)

**The problem it solves:** Not all routes are equal. KUL→SIN might be searched and booked thousands of times a day; an obscure regional route might be searched once a week. Hot routes need fresh prices — a user on a high-traffic route is more likely to proceed to checkout, making a stale price (one that has since sold out or changed) directly harmful. Standard routes can tolerate older cached data because the booking risk is lower. Tracking query frequency lets the system apply the right TTL and warming cadence to each route automatically.

**How scoring works:** Every calendar request fires `ZINCRBY hot_routes 1 "KUL:SIN:2024-07"` via [`HotRouteTracker.increment()`](../src/main/java/com/simulated/lowfarecalendar/cache/HotRouteTracker.java#L28) on a background virtual thread (fire-and-forget — not on the request path). Redis keeps the sorted set ordered by score automatically. Over time, high-traffic routes accumulate high scores; dormant routes score low and eventually get pruned by [`HotRouteDecayScheduler`](../src/main/java/com/simulated/lowfarecalendar/scheduler/HotRouteDecayScheduler.java).

**Critical distinction — ZSET is a scoreboard, not a rank list:** Each route's score is independent. Route X having score 143 is unaffected by how many other routes exist in the ZSET or what their scores are. Adding route Y to the ZSET does not change route X's score. The "is this route hot?" question is answered purely by comparing route X's own score against the threshold — position in the sorted set is only used by `getTopK()` in the cache warmer.

**What the Redis commands look like and what they return:**

```text
# Every request fires this (async, fire-and-forget):
ZINCRBY hot_routes 1 "KUL:SIN:2024-07"
→ "144"        ← Redis returns the new score as a string. The program ignores this return value.

# When FareCacheService is about to write a cache entry, it calls isHot():
ZSCORE hot_routes "KUL:SIN:2024-07"
→ "144"        ← Redis returns the current score as a string (or nil if the member doesn't exist)

# The Java code:
Double score = redisTemplate.opsForZSet().score("hot_routes", "KUL:SIN:2024-07");
// score == null  → member not in ZSET → return false (not hot)
// score >= 100   → return true  (hot → 240s TTL — shorter, refreshed more often)
// score < 100    → return false (normal → 600s TTL — longer, refreshed less often)

# Cache warmer fetching top 50:
ZREVRANGEBYSCORE hot_routes +inf -inf LIMIT 0 50
→ ["SIN:KUL:2024-07", "KUL:BKK:2024-07", "KUL:SIN:2024-08", ...]
   ← ordered highest score first; warmer iterates this list

# Decay scheduler pruning scores below 10:
ZREMRANGEBYSCORE hot_routes -inf 9
→ (integer) 12    ← number of members removed; program logs this count
```

**Score → TTL decision:**

```text
Route score >= hot-threshold (100)?
  YES → TTL = 240s (4 min) — hot route, shorter TTL means prices refresh more frequently
  NO  → TTL = 600s (10 min) — standard route, longer TTL acceptable given lower booking risk
```

**Why hot routes get the shorter TTL:** Hot routes are searched and booked more often, which means prices on those routes sell out faster. A user seeing a stale price on KUL→SIN is more likely to click through to book than a user on a quiet regional route — and more likely to find the price is gone. The shorter TTL ensures prices are at most 4 minutes old. The cache warmer (running every 2 minutes) proactively refreshes near-expiry hot-route entries before they expire, so in practice there are no cold misses on hot routes at steady state.

Standard routes get the longer TTL because proactively refreshing every obscure low-traffic route every 2 minutes would waste provider calls on routes nobody is booking.

**Constraint: warmer interval must be ≤ hot TTL / 2.** With hot TTL = 240s and skip threshold 50%, the warmer must fire at least once while `remainingTtl` is in the lower half (< 120s). At warmer interval = 120s, the warmer fires at T=0, T=120, T=240 … The T=120 fire sees remaining = 144–192s (above threshold → skip). The T=240 fire sees remaining = 24–72s (below threshold → refresh). The key is always refreshed before it expires.

The TTL is not stored anywhere — it is re-evaluated fresh every time [`FareCacheService.set()`](../src/main/java/com/simulated/lowfarecalendar/cache/FareCacheService.java#L54) is called via [`computeTtlMs()`](../src/main/java/com/simulated/lowfarecalendar/cache/FareCacheService.java#L95). If a previously-hot route's score decays below 100, the very next cache write for that route will automatically use the longer 600s TTL. No explicit "downgrade" step exists.

**Why this matters for warming:** `CacheWarmingScheduler` calls `getTopK(50)` — it only pre-warms the 50 most popular routes. Without this filter, the warmer would attempt to keep every route ever seen alive, which is expensive and unnecessary.

**Where `HotRouteDecayScheduler` runs:** It is a `@Scheduled` Spring bean inside the same JVM process as the HTTP server (see Section 2 for the full explanation). It fires every 3600s automatically on startup — no external trigger, no separate deployment.

---

### [HotRouteSeedRunner](../src/main/java/com/simulated/lowfarecalendar/cache/HotRouteSeedRunner.java)

**Package:** `cache`  
**Role:** `ApplicationRunner` that seeds pre-defined popular routes (`lfc.warming.seed-routes`) into the `hot_routes` ZSET at startup using `ZADD NX`, so the cache warmer has routes to work with immediately without waiting for organic traffic.

---

### [CacheWarmingScheduler](../src/main/java/com/simulated/lowfarecalendar/scheduler/CacheWarmingScheduler.java)

**Package:** `scheduler`  
**Role:** Background scheduler (every 2 min). Fetches the top-50 routes from the ZSET, iterates over the next 30 days for each route, and re-fetches from providers for dates whose remaining TTL is below 50% of the base TTL (stale-while-revalidate). This prevents cache expiry from ever being the reason for a cold miss on popular routes.  
**Key method:** [`warm()`](../src/main/java/com/simulated/lowfarecalendar/scheduler/CacheWarmingScheduler.java#L65)

**Where it runs:** Inside the same Spring Boot JVM process as the HTTP server — not a separate service. [`@EnableScheduling`](../src/main/java/com/simulated/lowfarecalendar/LowFareCalendarApplication.java#L11) on `LowFareCalendarApplication` activates Spring's scheduler at startup. The 2-minute timer ticks automatically alongside live request handling. No external cron job or deployment is involved.

**The problem it solves:** Even with jitter, a hot route's cache entries will eventually near expiry. If an entry expires before the next request arrives, that request is a cold miss — it waits 50–400ms for providers. For a route with 1,000 req/s, that cold miss causes thousands of requests to stack behind the singleflight lock. The warmer ensures the cache is refreshed *before* expiry, so a cold miss never happens on popular routes.

**Stale-while-revalidate strategy — the skip check:**

```text
For each date in the top-50 routes:
  remainingTtl > baseTtl * warmerSkipThresholdPct (0.50)?
    YES → "still fresh enough" → SKIP  (e.g. 140s of 240s base remains → skip)
    NO  → "getting stale, refresh now" → fetch from providers, write fresh entry with new full TTL
```

Users reading during the refresh window still get the current valid cache entry — they never see a miss. The new entry is written behind the scenes before the old one expires. This is stale-while-revalidate: serve existing, revalidate in background.

**Cross-pod coordination — the warming lock:** Because every pod runs its own `@Scheduled` timer, two pods can both pass the TTL skip check for the same date at the same time and both attempt to warm it. To prevent duplicate provider calls, the warmer acquires the same `CacheLockService` Redis lock used by the live read path before fetching:

```text
For each near-expiry date:
  tryAcquire(origin, dest, date) → SETNX lock:lfc:{key}
    acquired  → fetch from providers → write to Redis → release lock
    not acquired → another pod is already warming this date → skip silently
```

The lock is released in a `finally` block so it is always freed even if the provider call throws. The losing pod does not poll or wait — it skips immediately and moves on to the next date. No correctness problem exists if the lock is not used (both writes produce valid data), but this prevents redundant provider calls at scale.

**Non-obvious:** The warmer uses the same [`ProviderAggregationService.aggregate()`](../src/main/java/com/simulated/lowfarecalendar/service/ProviderAggregationService.java#L62) call as live requests. If providers are down during a warming cycle, `aggregate()` returns `Optional.empty()`, the warmer skips writing, and the existing (slightly older) entry remains until the next 2-minute cycle.

---

### [HotRouteDecayScheduler](../src/main/java/com/simulated/lowfarecalendar/scheduler/HotRouteDecayScheduler.java)

**Package:** `scheduler`  
**Role:** Background scheduler (every 1 hr). Removes routes from the ZSET whose score has fallen below `decay-min-score`. Prevents unbounded ZSET growth as routes fall out of popularity.  
**Key method:** [`decay()`](../src/main/java/com/simulated/lowfarecalendar/scheduler/HotRouteDecayScheduler.java#L29)

**Why it's needed:** The `hot_routes` ZSET grows every time a new route is queried for the first time. Without pruning, it would accumulate every route ever searched — potentially thousands of entries over months. A large ZSET makes `ZREVRANGEBYSCORE` queries slower and wastes Redis memory. Routes that haven't been queried recently fall below `decay-min-score` (10) and get removed, keeping the ZSET lean so the top-K lookup used by the warmer remains fast.

**How `decay-min-score` works — step by step:**

`decay-min-score` (configured as `10`) is a floor threshold. The scheduler calls [`pruneBelow(10)`](../src/main/java/com/simulated/lowfarecalendar/cache/HotRouteTracker.java#L66), which executes:

```text
ZREMRANGEBYSCORE hot_routes -inf 9
```

This removes every ZSET member whose score is **strictly less than 10** (the `pruneBelow` implementation subtracts 1 from `minScore`, so `pruneBelow(10)` removes scores ≤ 9). Any route with score ≥ 10 survives.

**Important: scores never decrease on their own.** `ZINCRBY` only adds to a score — there is no automatic decay over time. A route that was searched 50 times a month ago still has its accumulated score. The only way a score drops is if it is pruned out entirely (removed from the ZSET) — after which it starts from 0 on the next query.

This means `decay-min-score` is not a "rate of decay" — it is a **minimum survival threshold**. Routes that were queried fewer than 10 times total (since their last prune) are removed each hour. Routes with score ≥ 10 survive indefinitely, regardless of whether they've been searched recently.

**Practical consequence:** A route searched 11 times in January and never again will still be in the ZSET in June with score 11, because 11 ≥ 10. It will never be pruned. It will appear in `getTopK()` results if fewer than 50 routes have higher scores, and the cache warmer may attempt to warm it even though no one is actually searching for it. This is an accepted simplification — a production system would apply time-decay (e.g. halving scores nightly) to reflect recency, not just cumulative volume.

**Where it runs:** Inside the same Spring Boot JVM as the HTTP server. The 1-hour `@Scheduled` timer fires automatically alongside everything else — no separate process, no external cron. All pods run this independently; since the operation is idempotent (removing the same low-score entries produces the same result regardless of which pod runs first), duplicate executions across pods are harmless.

---

### [SoldOutEventListener](../src/main/java/com/simulated/lowfarecalendar/pubsub/SoldOutEventListener.java)

**Package:** `pubsub`  
**Role:** Consumes messages from the `price-class-sold-out` Pub/Sub subscription. Enforces idempotency using a Lua script that compares `event.generatedAt` against the fallback key's `updatedAt`. On `PROCEED`: evicts the primary cache key, re-aggregates from providers, and writes the fresh entry back. On `STALE` (duplicate or out-of-order): acks silently with no write.  
**Key method:** [`handleMessage()`](../src/main/java/com/simulated/lowfarecalendar/pubsub/SoldOutEventListener.java#L105)  
**Message model:** [`SoldOutEvent`](../src/main/java/com/simulated/lowfarecalendar/model/SoldOutEvent.java)  
**Idempotency key:** The fallback Redis key (it is never actively deleted, so it always holds the timestamp of the last valid write).

**The problem it solves:** When a flight's cheapest price class sells out during booking, the Low Fare Calendar would keep showing that now-unbookable price until the cache naturally expires (up to 10 minutes). Users would click through to book and find the price gone. The sold-out event triggers an immediate cache eviction and re-fetch so the calendar reflects the next available lowest price within seconds — rather than waiting up to 4 minutes for the hot-route TTL to expire naturally.

**Why Lua for idempotency — not Java:**

A naive Java approach has a race condition (the Lua script that replaces this is defined in [`SoldOutEventListener`](../src/main/java/com/simulated/lowfarecalendar/pubsub/SoldOutEventListener.java#L71)):

```java
String current = redis.get(fallbackKey);   // step 1: read
if (event.generatedAt > current.updatedAt) {  // step 2: check
    redis.set(fallbackKey, newValue);       // step 3: write  ← gap here
}
```

Between steps 1 and 3, another pod could write a *newer* entry. Your write at step 3 would overwrite it with an older value. The Lua script runs atomically on the Redis server — steps 1, 2, and 3 happen as a single indivisible operation with no other client able to interleave.

**How the idempotency check handles duplicates and out-of-order events:**

Pub/Sub guarantees at-least-once delivery — the same event can arrive multiple times, and a slow network path can deliver an older event after a newer one has already been processed.

```text
T2 event arrives → fallback written with updatedAt=T2

T2 event arrives again (duplicate redelivery):
  Lua: ARGV[1]=T2, cached updatedAt=T2
  Condition: T2 <= T2 → true → STALE → ack silently, no write ✓

T1 event arrives late (T1 was generated before T2 but delivered after):
  Lua: ARGV[1]=T1, cached updatedAt=T2
  Condition: T1 <= T2 → true → STALE → ack silently, no write ✓
```

The fallback key is never actively deleted precisely because it serves as the idempotency record. If it were deleted, the Lua script would see no entry and return `PROCEED` for every event including duplicates.

---

### [CurrencyConverter](../src/main/java/com/simulated/lowfarecalendar/currency/CurrencyConverter.java) / [CurrencyConverterRegistry](../src/main/java/com/simulated/lowfarecalendar/currency/CurrencyConverterRegistry.java)

**Package:** `currency`  
**Role:** Strategy pattern for currency conversion. `CurrencyConverter` is an interface; each converter ([`UsdToMyrConverter`](../src/main/java/com/simulated/lowfarecalendar/currency/UsdToMyrConverter.java), [`UsdToThbConverter`](../src/main/java/com/simulated/lowfarecalendar/currency/UsdToThbConverter.java), [`UsdToUsdConverter`](../src/main/java/com/simulated/lowfarecalendar/currency/UsdToUsdConverter.java)) is a Spring `@Component` that declares which `CurrencyPair` it handles. `CurrencyConverterRegistry` auto-discovers all implementations at startup and builds a `Map<CurrencyPair, CurrencyConverter>`.  
**Key method:** [`CurrencyConverterRegistry.get()`](../src/main/java/com/simulated/lowfarecalendar/currency/CurrencyConverterRegistry.java#L45)  
**Adding a new currency:** Create one new `@Component` class implementing `CurrencyConverter`. Zero changes to existing classes (Open/Closed Principle).

**The problem it solves:** Prices are stored in Redis in USD. Users request prices in MYR, THB, or USD. A naive implementation would use `if/else` chains — every new currency requires modifying existing code, risking bugs in already-working conversions, and requiring a redeploy just to add a currency.

**How auto-discovery works:** Spring injects *all* [`CurrencyConverter`](../src/main/java/com/simulated/lowfarecalendar/currency/CurrencyConverter.java) implementations at startup as a list (see [`CurrencyConverterRegistry`](../src/main/java/com/simulated/lowfarecalendar/currency/CurrencyConverterRegistry.java#L26)):

```java
// CurrencyConverterRegistry receives every @Component implementing CurrencyConverter
public CurrencyConverterRegistry(List<CurrencyConverter> converters) {
    for (CurrencyConverter c : converters) {
        for (CurrencyPair pair : c.supportedPairs()) {
            registry.put(pair, c);   // builds: {USD→MYR: UsdToMyrConverter, ...}
        }
    }
}
```

At request time: `registry.get(new CurrencyPair("USD", "MYR"))` returns the right converter in O(1). No `if/else`. No switch statement.

**Adding EUR — exactly one new file, zero changed files:**

```java
@Component
public class UsdToEurConverter implements CurrencyConverter {
    public Set<CurrencyPair> supportedPairs() {
        return Set.of(new CurrencyPair("USD", "EUR"));
    }
    public BigDecimal convert(BigDecimal amount, String from, String to) {
        return amount.multiply(props.getCurrency().getRates().getUsdEur())
                     .setScale(2, HALF_UP);
    }
}
```

Spring finds it, the registry picks it up, and `?currency=EUR` works immediately. This is the Open/Closed Principle in practice: open for extension, closed for modification.

---

### Observability Layer

**Package:** `observability`  
**Components:**

- [`CacheMetricsService`](../src/main/java/com/simulated/lowfarecalendar/observability/CacheMetricsService.java) — interface
- [`MeterRegistryCacheMetricsService`](../src/main/java/com/simulated/lowfarecalendar/observability/MeterRegistryCacheMetricsService.java) — Micrometer implementation (active when Prometheus is enabled)
- [`NoOpCacheMetricsService`](../src/main/java/com/simulated/lowfarecalendar/observability/NoOpCacheMetricsService.java) — no-op for tests  

**Why two implementations?** Tests that run without a full Spring context would need Prometheus/Micrometer configured to inject any component that calls `cacheMetricsService.recordCacheHit()`. The `NoOpCacheMetricsService` does nothing — safe to inject in unit tests with no infrastructure. Spring selects the real implementation automatically when Prometheus is on the classpath and enabled.

**Custom metrics exposed at `/actuator/prometheus`:**

| Metric | Type | What it tells you |
| --- | --- | --- |
| `lfc_cache_hits_total` | Counter | Cache hits, tagged by origin+dest |
| `lfc_cache_misses_total` | Counter | Cache misses, tagged by origin+dest |
| `lfc_cache_invalidations_total` | Counter | Sold-out evictions triggered |
| `lfc_provider_latency_seconds` | Timer | Per-provider latency, tagged by outcome (success/error) |
| `lfc_provider_errors_total` | Counter | Provider exception count per provider |
| `lfc_circuit_breaker_state` | Gauge | 0=closed (healthy), 1=open (bypassed) per provider |
| `lfc_pubsub_events_total` | Counter | Pub/Sub events by outcome (processed/stale_discarded/error) |

**Reading the metrics at a glance:**

- `hits / (hits + misses)` = cache hit rate. Should be >90% at steady state; low hit rate means TTLs are too short or the warmer isn't running.
- `lfc_circuit_breaker_state{provider="providerA"} 1` = ProviderA is currently OPEN — it is being bypassed entirely.
- `lfc_pubsub_events_total{outcome="stale_discarded"}` incrementing = duplicate sold-out events being caught by the idempotency check. Normal and expected in a healthy system.

---

### [RequestTraceContext](../src/main/java/com/simulated/lowfarecalendar/trace/RequestTraceContext.java) / [RequestTraceWriter](../src/main/java/com/simulated/lowfarecalendar/trace/RequestTraceWriter.java)

**Package:** `trace`  
**Role:** ThreadLocal-based per-request observability. `RequestTraceContext` holds a [`RequestTrace`](../src/main/java/com/simulated/lowfarecalendar/trace/RequestTrace.java) POJO for the duration of one request. `RequestTraceWriter` (gated by `lfc.tracing.enabled=true`) serialises it to a JSON file in `traces/` when the request completes. Files are named with an inverted epoch prefix so they sort newest-first in a file explorer.  
**Key methods:** [`RequestTraceContext.start()`](../src/main/java/com/simulated/lowfarecalendar/trace/RequestTraceContext.java#L17), [`RequestTraceContext.current()`](../src/main/java/com/simulated/lowfarecalendar/trace/RequestTraceContext.java#L24), [`RequestTraceWriter.write()`](../src/main/java/com/simulated/lowfarecalendar/trace/RequestTraceWriter.java#L36)

**The problem it solves:** Logs tell you *that* something happened. The trace file tells you *what happened for one specific request* — which of the 31 dates were cache hits, which providers responded for each missed date, what prices each quoted, whether singleflight made this request a leader or follower, and what currency conversion was applied. This is the per-request audit trail.

**Why ThreadLocal?** Each HTTP request is handled by one thread (a virtual thread). `ThreadLocal` stores data scoped to that exact thread — invisible to all other threads. 1,000 concurrent requests each accumulate their own trace data independently, with zero shared state and zero locking.

**The ThreadLocal-across-virtual-threads gotcha:** `CompletableFuture.supplyAsync()` in [`ProviderAggregationService`](../src/main/java/com/simulated/lowfarecalendar/service/ProviderAggregationService.java) runs on a *different* virtual thread. ThreadLocal values are not inherited across virtual thread boundaries. Accessing [`traceContext.current()`](../src/main/java/com/simulated/lowfarecalendar/trace/RequestTraceContext.java#L24) inside the lambda would return `null`. The fix: capture `RequestTrace trace = traceContext.current()` on the calling request thread *before* spawning the futures, then pass `trace` directly into the lambda. The reference is carried across via closure, not ThreadLocal.

**Filename format — why inverted epoch:**

```text
9223370835497275807-KUL-SIN-2024-07-a3f9c1b2.json
│                   │   │   │       │
│                   │   │   │       └─ first 8 chars of requestId
│                   │   │   └─ month
│                   │   └─ destination
│                   └─ origin
└─ Long.MAX_VALUE - currentEpochSeconds
   As time increases, this value decreases.
   Newest file = smallest number = sorts to the top lexicographically.
```

Without the inversion, the newest file would sort to the bottom of a directory listing — harder to find after several requests.

---

## 5. Green Path vs Red Path

### Green Path — Cache Hit (ideal steady state)

```text
Request arrives
    │
    ├─▶ Redis pipeline GET (all 31 dates) → all keys present
    │
    ├─▶ HotRoute ZINCRBY (virtual thread, non-blocking)
    │
    ├─▶ Currency conversion (in-memory, BigDecimal multiply)
    │
    └─▶ 200 OK  ← stale: false on all days, available: true
```

**Characteristics:**

- No provider calls
- No lock contention
- Latency dominated by Redis round-trip (~1–3 ms)
- All `DayPrice` entries have `available: true`, `stale: false`
- Metrics: `lfc_cache_hits_total` increments

---

### Green Path — Cache Miss (first request or post-eviction)

```text
Request arrives
    │
    ├─▶ Redis pipeline GET → miss on some/all dates
    │
    ├─▶ For each miss:
    │       InProcessSingleflight (leader wins putIfAbsent)
    │           CacheLockService.tryAcquire() → SETNX succeeds
    │               ProviderAggregationService.aggregate()
    │                   CompletableFuture × 3 providers (virtual threads)
    │                   all 3 respond → min price selected
    │               FareCacheService.set() → primary + fallback written
    │               CacheLockService.release() → Lua DEL
    │
    ├─▶ Currency conversion
    │
    └─▶ 200 OK  ← stale: false, available: true
```

**Characteristics:**

- Providers are called (latency 50–400 ms per date, all dates parallel)
- Singleflight prevents duplicate provider calls within one JVM
- Redis lock prevents duplicate calls across pods
- First-ever request for a route is the only truly "cold" path

---

### Red Path — Partial Provider Failure (circuit breaker open)

```text
Request arrives → cache miss for some dates
    │
    ├─▶ ProviderAggregationService.aggregate()
    │       ProviderA → CallNotPermittedException (circuit OPEN)
    │       ProviderB → quote returned
    │       ProviderC → quote returned
    │
    ├─▶ min(B.price, C.price) selected → still a valid entry
    │
    └─▶ 200 OK  ← stale: false, available: true
              (degraded but functional — 2 of 3 providers answered)
```

**Characteristics:**

- One provider's circuit is open after repeated failures
- System continues serving with remaining providers
- `lfc_circuit_breaker_state{provider="providerA"} 1`
- Circuit moves to HALF-OPEN after 5 s; test calls re-close it on success

---

### Red Path — Total Provider Failure (stale fallback)

```text
Request arrives → cache miss
    │
    ├─▶ ProviderAggregationService.aggregate()
    │       ProviderA → Exception / circuit open
    │       ProviderB → Exception / circuit open
    │       ProviderC → Exception / circuit open
    │       → Optional.empty()
    │
    ├─▶ FareCacheService.getFallback() → reads 24h fallback key
    │
    └─▶ 200 OK  ← stale: true
              lowestPrice = last known good value
              available: true (price shown, but marked stale)
```

**Characteristics:**

- User still receives a response — no 500 error
- `stale: true` signals the price may not be bookable at this exact amount
- Fallback key is never actively deleted; it persists 24 h from last write
- If the fallback key also does not exist (first-ever call + all providers down) → `available: false`

---

### Red Path — Sold-Out Event with Duplicate Delivery

```text
Pub/Sub delivers event (generatedAt=T2)
    │
    ├─▶ Lua script: fallback.updatedAt=T1 < T2 → PROCEED
    │       evict primary, re-aggregate, write back (updatedAt=T2)
    │
Pub/Sub re-delivers same event (at-least-once delivery)
    │
    ├─▶ Lua script: fallback.updatedAt=T2 >= T2 → STALE
    │       ack silently, no write, no duplicate eviction
    │
    └─▶ Cache state unchanged (idempotent)
```

---
