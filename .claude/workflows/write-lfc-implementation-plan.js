
export const meta = {
  name: 'write-lfc-implementation-plan',
  description: 'Write Low Fare Calendar implementation plan in parallel sections then merge',
  phases: [
    { title: 'Write sections', detail: 'Five agents write plan sections in parallel' },
    { title: 'Merge', detail: 'Merge all sections into one final plan file' },
  ],
}

const SPEC = '/Users/hasif.ahmad/Documents/ResearchProject/simulated_assessment/docs/superpowers/specs/2026-06-20-low-fare-calendar-design.md'
const PLAN_DIR = '/Users/hasif.ahmad/Documents/ResearchProject/simulated_assessment/docs/superpowers/plans'

const CONSTRAINTS = 'Stack: Java 21 Virtual Threads, Spring Boot 3.x Jakarta EE, Spring Data Redis Lettuce, Spring Cloud GCP PubSub, Resilience4j, Micrometer+OTel, Testcontainers, JUnit5, AssertJ, Lombok. Base package: com.simulated.lowfarecalendar. Cache key: lfc:v1:{ORIGIN}:{DEST}:{YYYY-MM-DD} uppercase. Fallback key: lfc:v1:fallback:{ORIGIN}:{DEST}:{YYYY-MM-DD} 24h TTL never evicted. Lock key: lock:lfc:{ORIGIN}:{DEST}:{YYYY-MM-DD} UUID owner Lua release. All prices USD in Redis, convert at read time only. updatedAt in JSON = event.generatedAt NOT Redis write time. Singleflight uses ConcurrentHashMap.putIfAbsent + CompletableFuture NOT computeIfAbsent. All config lfc.* via @ConfigurationProperties no @Value in services. User commits manually.'

phase('Write sections')

const sections = await parallel([
  () => agent(
    'Read the full design spec at ' + SPEC + ' then write the HEADER + TASKS 1-3 of an implementation plan to ' + PLAN_DIR + '/section-1.md\n\n' +
    'Global constraints: ' + CONSTRAINTS + '\n\n' +
    'The file must start with exactly this header block:\n\n' +
    '# Low Fare Calendar — Implementation Plan\n\n' +
    '> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.\n\n' +
    '**Goal:** Build the Low Fare Calendar backend — a Spring Boot 3 service that aggregates flight prices from 3 mock providers, caches per-day results in Redis, updates asynchronously via GCP Pub/Sub sold-out events, and exposes a REST calendar API supporting multiple currencies.\n\n' +
    '**Architecture:** Write-Through + Proactive Warming + two-layer Singleflight (in-process ConcurrentHashMap.putIfAbsent + distributed Redis SETNX). Hot read path is Redis-only. Thundering herd prevented structurally. Cache invalidation driven by Pub/Sub with Lua-script idempotency.\n\n' +
    '**Tech Stack:** Java 21 (Virtual Threads), Spring Boot 3.x, Spring Data Redis (Lettuce), Spring Cloud GCP Pub/Sub, Resilience4j, Micrometer + OpenTelemetry SDK, Testcontainers, JUnit 5, AssertJ, Lombok\n\n' +
    '## Global Constraints\n\n' +
    '- Java 21 — Virtual Threads mandatory, use Executors.newVirtualThreadPerTaskExecutor()\n' +
    '- Spring Boot 3.x — Jakarta EE namespaces (jakarta.*, not javax.*)\n' +
    '- All prices stored in USD internally; currency conversion at read time only\n' +
    '- Cache key: lfc:v1:{ORIGIN}:{DEST}:{YYYY-MM-DD} (uppercase IATA codes)\n' +
    '- Fallback key: lfc:v1:fallback:{ORIGIN}:{DEST}:{YYYY-MM-DD} (24h TTL, never evicted by events)\n' +
    '- Lock key: lock:lfc:{ORIGIN}:{DEST}:{YYYY-MM-DD} with UUID owner token + Lua release\n' +
    '- updatedAt in cached JSON = event.generatedAt (NOT Redis write time)\n' +
    '- In-process singleflight: ConcurrentHashMap.putIfAbsent + CompletableFuture (NOT computeIfAbsent)\n' +
    '- All config under lfc.* via @ConfigurationProperties — no @Value in service classes\n' +
    '- Base package: com.simulated.lowfarecalendar\n' +
    '- User commits manually — every task ends with a suggested commit message only\n\n' +
    '---\n\n' +
    'Then write TASK 1, TASK 2, TASK 3 in full TDD style. Each task must have:\n' +
    '- ### Task N: Title\n' +
    '- **Files:** exact paths to create/modify\n' +
    '- **Interfaces:** Consumes/Produces with exact method signatures\n' +
    '- Checkbox steps with 100% complete code (no ellipsis, no placeholders)\n' +
    '- Exact mvn test command and expected output snippet\n' +
    '- Suggested commit message\n\n' +
    'TASK 1: Project Scaffolding. Create pom.xml (Spring Boot 3.x, Java 21, all dependencies listed in spec). LowFareCalendarApplication.java. LfcProperties.java with ALL nested static inner classes for every config group (CacheProperties, HotRoutesProperties, WarmingProperties, LockProperties, CurrencyProperties, ProviderSimProperties, ProvidersProperties, CircuitBreakerProperties, PubSubProperties, ObservabilityProperties) each with @Data @NoArgsConstructor and JSR-303 validation. AsyncConfig.java with virtualThreadExecutor bean using Executors.newVirtualThreadPerTaskExecutor(). RedisConfig.java with RedisTemplate<String,String> using StringRedisSerializer. application.yml with all lfc.* values from spec section 8. docker-compose.yml with redis:7-alpine port 6379 and pubsub emulator port 8085. No unit test — verify: mvn spring-boot:run starts on port 8080.\n\n' +
    'TASK 2: Domain Models. Show complete code for: CurrencyPair (record), FlightQuery (record), FareQuote (record), CachedFareEntry (@Data @Builder with stale=false default and currency="USD" default), DayPrice (record), CalendarResponse (record), SoldOutEvent (@Data with all fields from spec). UnsupportedCurrencyException and ServiceUnavailableException. Write CachedFareEntryTest verifying builder defaults.\n\n' +
    'TASK 3: Currency Conversion Strategy Pattern. CurrencyConverter interface with convert() and supportedPairs(). UsdToMyrConverter, UsdToThbConverter, UsdToUsdConverter — each injects LfcProperties via constructor NOT @Value, uses BigDecimal with HALF_UP rounding. CurrencyConverterRegistry builds Map<CurrencyPair,CurrencyConverter> at construction. Write CurrencyConverterRegistryTest: USD->MYR 100->447.00, USD->THB 100->3350.00, USD->USD 100->100.00, USD->SGD throws UnsupportedCurrencyException.',
    { label: 'section-1: header+tasks1-3', phase: 'Write sections' }
  ),

  () => agent(
    'Read the full design spec at ' + SPEC + ' then write TASKS 4-6 of the implementation plan to ' + PLAN_DIR + '/section-2.md\n\n' +
    'Global constraints: ' + CONSTRAINTS + '\n\n' +
    'Do NOT include a plan header — start directly with ### Task 4.\n\n' +
    'Each task must have: exact file paths, Interfaces (Consumes/Produces), checkbox steps with 100% complete code, mvn test command with expected output, suggested commit message. Zero placeholders or ellipsis.\n\n' +
    'TASK 4: Mock Flight Providers + Circuit Breaker Config.\n' +
    'FlightProvider interface: Optional<FareQuote> getFares(FlightQuery), String getId(), boolean isEnabled().\n' +
    'ProviderA/B/C: each reads its LfcProperties.ProviderSimProperties via constructor. getFares() logic: if !enabled return empty; Thread.sleep(ThreadLocalRandom latency); if random < errorRate throw RuntimeException; return FareQuote with ThreadLocalRandom price between min/max rounded to 2dp.\n' +
    'ProviderCircuitBreakerConfig: reads LfcProperties.CircuitBreakerProperties, programmatically creates CircuitBreakerRegistry with instances providerA, providerB, providerC.\n' +
    'Test with application-test.yml: error-rate=0.0, latency=0, price fixed at 150.00. Tests: enabled=true returns FareQuote price=150.00; enabled=false returns empty; error-rate=1.0 throws.\n\n' +
    'TASK 5: Provider Aggregation Service.\n' +
    'ProviderAggregationService.aggregate(FlightQuery): filters enabled providers, CompletableFuture.supplyAsync each wrapped in circuit breaker, allOf().join(), collect non-empty, return Optional.empty() if all fail else CachedFareEntry with min price.\n' +
    'fetchWithCircuitBreaker: Try.ofSupplier with CircuitBreaker.decorateSupplier, recover CallNotPermittedException -> metrics.recordCircuitBreakerState(id,1) return empty, recover Exception -> metrics.recordProviderError(id) return empty.\n' +
    'ProviderAggregationServiceTest (plain Mockito unit test): 3 providers return [150,200,120] -> min=120 respondingProviders size 3; A throws -> min(B=200,C=120)=120 respondingProviders size 2; all throw -> Optional.empty(); 1 provider -> its price.\n\n' +
    'TASK 6: Cache Layer (FareCacheService + CacheLockService + HotRouteTracker).\n' +
    'FareCacheService methods: get() Redis GET primary key deserialise; getFallback() GET fallback key; set() serialise JSON SET primaryKey with computeTtlMs() jitter AND SET fallbackKey with 24h TTL; evict() DEL primary only; getRemainingTtlSeconds() PTTL; pipelineGet() executePipelined 31 GETs for month; computeTtlMs() uses hot-route vs standard TTL + ThreadLocalRandom jitter between jitterMinPct and jitterMaxPct.\n' +
    'CacheLockService: tryAcquire() SET key token NX Duration.ofMillis(lockTtlMs) returns Optional<token>; release() Lua script "if redis.call(GET,KEYS[1])==ARGV[1] then return redis.call(DEL,KEYS[1]) else return 0 end"; pollForResult() polls GET every pollIntervalMs up to pollTimeoutMs.\n' +
    'HotRouteTracker: increment() ZINCRBY hot_routes 1; isHot() ZSCORE >= threshold; getTopK() ZREVRANGEBYSCORE limit k; pruneBelow() ZREMRANGEBYSCORE -inf (minScore-1).\n' +
    'FareCacheServiceTest (Testcontainers Redis @Container GenericContainer redis:7-alpine @DynamicPropertySource): set+get equals; set writes fallback with 24h TTL; primary TTL within [baseTtl*1.09*1000, baseTtl*1.31*1000]ms; evict->get empty, getFallback still present; pipelineGet for YearMonth.of(2024,7) returns size 31.\n' +
    'CacheLockServiceTest (Testcontainers Redis): tryAcquire returns non-empty; tryAcquire same key while held returns empty; release correct token -> key gone; release wrong token -> key still exists.',
    { label: 'section-2: tasks4-6', phase: 'Write sections' }
  ),

  () => agent(
    'Read the full design spec at ' + SPEC + ' then write TASKS 7-9 of the implementation plan to ' + PLAN_DIR + '/section-3.md\n\n' +
    'Global constraints: ' + CONSTRAINTS + '\n\n' +
    'Do NOT include a plan header — start directly with ### Task 7.\n\n' +
    'Each task must have: exact file paths, Interfaces (Consumes/Produces), checkbox steps with 100% complete code, mvn test command with expected output, suggested commit message. Zero placeholders.\n\n' +
    'TASK 7: In-Process Singleflight.\n' +
    'InProcessSingleflight @Component with ConcurrentHashMap<String,CompletableFuture<CachedFareEntry>> inFlight. getOrFetch(String key, Supplier<CachedFareEntry> fetcher): create new CompletableFuture, call putIfAbsent, if existing!=null return existing.join(), else run fetcher in try/finally completing/exceptionally the future and removing from map.\n' +
    'InProcessSingleflightTest: (1) 20 threads same key fetcher sleeps 100ms AtomicInteger counter -> fetcherCallCount==1 all 20 results equal; use CountDownLatch(1) startGate CountDownLatch(20) doneLatch. (2) Different keys sequential -> fetcherCallCount==2. (3) Fetcher throws IllegalStateException -> both concurrent callers get CompletionException. (4) After first completes, second call invokes fetcher again (cleanup verified).\n\n' +
    'TASK 8: Observability.\n' +
    'CacheMetricsService @Component injects MeterRegistry. All 9 methods: recordCacheHit(route,date), recordCacheMiss(route,date), recordCacheInvalidation(route,date,reason), recordStaleServed(route,date), recordProviderCall(providerId,durationMs,success) using meterRegistry.timer(), recordProviderError(providerId), recordCircuitBreakerState(providerId,state) using meterRegistry.gauge(), recordSingleflightCoalesced(route), recordPubSubEvent(outcome). Show complete implementation of each method.\n' +
    'OpenTelemetryConfig @Configuration: OTel SDK bean with MicrometerMetricExporter bridge, Prometheus exporter.\n' +
    'CacheMetricsServiceTest using SimpleMeterRegistry: recordCacheHit -> counter lfc.cache.hits tags route+date count==1.0; recordCacheMiss -> lfc.cache.misses count==1.0; recordProviderCall("providerA",150L,true) -> timer lfc.provider.call.duration tag provider=providerA,outcome=success count==1; recordPubSubEvent("stale_discarded") -> counter lfc.pubsub.events_processed tag outcome=stale_discarded count==1.0.\n\n' +
    'TASK 9: CalendarService (Core Orchestrator).\n' +
    'CalendarService @Service constructor injects: FareCacheService, InProcessSingleflight, CacheLockService, ProviderAggregationService, CurrencyConverterRegistry, HotRouteTracker, CacheMetricsService, @Qualifier("virtualThreadExecutor") Executor, LfcProperties.\n' +
    'getCalendar(String origin, String dest, YearMonth month, String currency): 1) get all dates for month via IntStream; 2) pipelineGet all 31; 3) for each missing date call resolveMissForDate(); 4) fire-and-forget hotRouteTracker.increment() via executor; 5) convert each to DayPrice via toDayPrice(); 6) return CalendarResponse.\n' +
    'resolveMissForDate(origin,dest,date): key=origin+":"+dest+":"+date, singleflight.getOrFetch(key, () -> { tryAcquire: if won aggregate+set+release fallback to getFallback stale; if lost pollForResult then get from cache }).\n' +
    'toDayPrice(date, Optional<CachedFareEntry> entry, currency): if empty return DayPrice(date,null,false,false); else convert lowestPrice USD->currency, return DayPrice(date,converted,true,entry.isStale()).\n' +
    'CalendarServiceTest (Mockito @Mock all deps): 1) all 31 cached->no aggregation->31 DayPrices MYR (100 USD * 4.47 = 447.00); 2) 30 hits+1 miss->aggregation called once; 3) aggregation empty+fallback present->stale=true available=true; 4) aggregation empty+fallback empty->available=false; 5) hotRouteTracker.increment called once; show exact Mockito setup with @ExtendWith(MockitoExtension.class) @Mock @InjectMocks.',
    { label: 'section-3: tasks7-9', phase: 'Write sections' }
  ),

  () => agent(
    'Read the full design spec at ' + SPEC + ' then write TASKS 10-12 of the implementation plan to ' + PLAN_DIR + '/section-4.md\n\n' +
    'Global constraints: ' + CONSTRAINTS + '\n\n' +
    'Do NOT include a plan header — start directly with ### Task 10.\n\n' +
    'Each task must have: exact file paths, Interfaces (Consumes/Produces), checkbox steps with 100% complete code, mvn test command with expected output, suggested commit message. Zero placeholders.\n\n' +
    'TASK 10: REST Controller + Input Validation + Exception Handling.\n' +
    'CalendarController @RestController @RequestMapping("/api/v1/flights") @Validated. GET /calendar with @RequestParam @NotBlank @Size(min=3,max=3) origin, destination, @NotBlank month, @NotBlank currency. Validate month via YearMonth.parse() catch DateTimeParseException throw ResponseStatusException 400. Validate currency in Set.of("MYR","USD","THB") else throw UnsupportedCurrencyException. Normalise origin/dest to toUpperCase() before calling service.\n' +
    'GlobalExceptionHandler @RestControllerAdvice: UnsupportedCurrencyException->400 body {error:INVALID_PARAMETER,message,timestamp}; ServiceUnavailableException->503 body {error:SERVICE_UNAVAILABLE,message,timestamp}; ConstraintViolationException->400. Define inner record ErrorResponse(String error, String message, Instant timestamp).\n' +
    'CalendarControllerTest @WebMvcTest(CalendarController.class) @MockBean CalendarService: 1) valid params->200 JSON origin=KUL; 2) missing origin->400; 3) origin length 2 "KU"->400; 4) month="2024/07"->400; 5) currency="XYZ"->400 body contains INVALID_PARAMETER; 6) lowercase origin "kul"->CalendarService called with "KUL" verify via Mockito; 7) ServiceUnavailableException->503 body SERVICE_UNAVAILABLE. Show exact MockMvc andExpect jsonPath assertions.\n\n' +
    'TASK 11: Pub/Sub Event Listener with Idempotency.\n' +
    'PubSubConfig @Configuration: configure PubSubTemplate to use emulator host from LfcProperties.getPubsub().getEmulatorHost(), set PUBSUB_EMULATOR_HOST env or GcpProjectIdProvider.\n' +
    'SoldOutEventListener @Component. Lua idempotency script constant: "local current = redis.call(\'GET\', KEYS[1])\\nif current == false then\\n  return \'PROCEED\'\\nend\\nlocal payload = cjson.decode(current)\\nlocal cachedUpdatedAt = payload[\'updatedAt\']\\nif ARGV[1] <= cachedUpdatedAt then\\n  return \'STALE\'\\nend\\nreturn \'PROCEED\'" — store as static final String.\n' +
    '@PubSubListener(subscriptionName = "#{@lfcProperties.pubsub.subscription}") void receiveMessage(BasicAcknowledgeablePubsubMessage): parse JSON->SoldOutEvent; execute Lua KEYS=[primaryKey] ARGV=[event.getGeneratedAt().toString()]; if STALE: recordPubSubEvent("stale_discarded") ack return; evict primary; aggregate; if present set entry with updatedAt=event.getGeneratedAt(); recordPubSubEvent("processed") ack; on exception recordPubSubEvent("error") nack.\n' +
    'SoldOutEventListenerTest (Mockito): mock RedisTemplate.execute() to return "PROCEED" or "STALE". Test 1: PROCEED+aggregation returns entry -> evict called, set called with updatedAt==event.generatedAt, metric=processed. Test 2: STALE -> evict NOT called, aggregate NOT called, metric=stale_discarded. Test 3: duplicate same generatedAt -> STALE. Test 4: empty cache PROCEED+aggregation empty -> evict called, aggregate called, set NOT called, metric=processed. Show all 4 with exact Mockito.verify().\n\n' +
    'TASK 12: Background Schedulers.\n' +
    'CacheWarmingScheduler @Component @Scheduled(fixedDelayString="#{@lfcProperties.warming.intervalSeconds * 1000}"): getTopK routes, for each route parse origin:dest:month, for each day in next lookaheadDays: getRemainingTtlSeconds, if remaining > baseTtl*skipThreshold skip; else aggregate and set.\n' +
    'HotRouteDecayScheduler @Component @Scheduled(fixedDelayString="#{@lfcProperties.hotRoutes.decayIntervalSeconds * 1000}"): call hotRouteTracker.pruneBelow(decayMinScore).\n' +
    'CacheWarmingSchedulerTest (Mockito @ExtendWith MockitoExtension): mock getTopK returns ["KUL:SIN:2024-07"]; test 1: getRemainingTtlSeconds returns 400 (>600*0.5=300) -> aggregate NOT called; test 2: returns 100 (<300) -> aggregate called set called; test 3: getTopK returns empty -> aggregate never called. Show exact verify assertions.',
    { label: 'section-4: tasks10-12', phase: 'Write sections' }
  ),

  () => agent(
    'Read the full design spec at ' + SPEC + ' then write TASKS 13-15 of the implementation plan to ' + PLAN_DIR + '/section-5.md\n\n' +
    'Global constraints: ' + CONSTRAINTS + '\n\n' +
    'Do NOT include a plan header — start directly with ### Task 13.\n\n' +
    'Each task must have: exact file paths, Interfaces (Consumes/Produces), checkbox steps with 100% complete code, mvn test command with expected output, suggested commit message. Zero placeholders.\n\n' +
    'TASK 13: Integration Tests.\n' +
    'src/test/resources/application-integration.yml: all providers fixed price 150.00/200.00/175.00 USD, latency 0, error-rate 0, pubsub.enabled=false, cache ttl 60/30s.\n' +
    'src/test/resources/application-circuit-breaker.yml: provider-a error-rate=1.0, latency 0.\n' +
    'CalendarApiIntegrationTest @SpringBootTest(webEnvironment=RANDOM_PORT) @Testcontainers @ActiveProfiles("integration"): @Container GenericContainer redis:7-alpine, @DynamicPropertySource for spring.data.redis.host/port. Test 1: GET /api/v1/flights/calendar?origin=KUL&destination=SIN&month=2024-07&currency=MYR -> 200 calendar.size==31 calendar[0].lowestPrice==670.50 (150.00*4.47) stale==false. Test 2: @SpyBean ProviderA, first call populates cache, second call -> ProviderA.getFares count unchanged (cache hit). Test 3: currency=USD -> lowestPrice==150.00.\n' +
    'ThunderingHerdIntegrationTest @Testcontainers @SpringBootTest @ActiveProfiles("integration"): 50 threads concurrent same cold route, @SpyBean ProviderAggregationService with AtomicInteger counter, CountDownLatch start+done, assert aggregateCallCount <= 31 (singleflight coalescing, max 1 per day per 31 days).\n' +
    'IdempotencyIntegrationTest @Testcontainers @SpringBootTest: seed Redis with CachedFareEntry lowestPrice=250.00 updatedAt=2024-07-15T10:00:30Z; inject SoldOutEventListener directly; call receiveMessage with event generatedAt=2024-07-15T10:00:00Z (OLDER); assert Redis still contains lowestPrice=250.00.\n' +
    'CircuitBreakerIntegrationTest @Testcontainers @SpringBootTest @ActiveProfiles("circuit-breaker"): make 15 calendar requests for same route (opens providerA circuit); make one more request; assert response 200 and respondingProviders does not contain "providerA".\n\n' +
    'TASK 14: docker-compose + Scripts + README.\n' +
    'docker-compose.yml: redis:7-alpine port 6379 with healthcheck; gcr.io/google.com/cloudsdktool/google-cloud-cli:emulators pubsub on port 8085 with healthcheck.\n' +
    'scripts/init-pubsub.sh: curl PUT to create topic price-class-sold-out and subscription price-class-sold-out-sub on localhost:8085 project local-project. chmod +x.\n' +
    'scripts/publish-sold-out.sh: accepts $1=origin $2=dest $3=date, builds JSON payload base64 encoded, curl POST to emulator. chmod +x.\n' +
    'scripts/load-test.sh: fires 100 concurrent curl requests in batches of 100, total 1000, to /api/v1/flights/calendar. chmod +x.\n' +
    'README.md: Prerequisites (Java 21, Maven 3.9+, Docker 20+). Quick start: docker-compose up -d; bash scripts/init-pubsub.sh; mvn spring-boot:run. Test: curl http://localhost:8080/api/v1/flights/calendar?origin=KUL&destination=SIN&month=2024-07&currency=MYR. Publish sold-out: bash scripts/publish-sold-out.sh KUL SIN 2024-07-15. Load test: bash scripts/load-test.sh. Run tests: mvn test. View metrics: curl http://localhost:8080/actuator/prometheus | grep lfc.\n\n' +
    'TASK 15: AI_USAGE.md (Part C).\n' +
    'Create AI_USAGE.md at project root covering: 1) Tools: Claude Code (Anthropic) for design+review+codebase exploration. 2) Design session phases: brainstorming architecture, exploring Grab Go production codebase for validated patterns, independent mediator cross-check. 3) Two critical issues caught by mediator before any code was written: (a) ConcurrentHashMap.computeIfAbsent does not coalesce concurrent Java callers - fixed to putIfAbsent pattern; (b) month-level cache key causes 93 provider calls per sold-out event - fixed to per-day keys. 4) Production patterns adopted from Grab codebase: singleflight (gcredis/shard_cache.go), TTL jitter 10-30% (cache/kvcache.go), tiered TTL (store/hot_data_entity_strategy), full-snapshot stale fallback not per-provider mix (essearch.go). 5) 15 total design amendments logged before implementation. 6) AI acceleration: design phase compressed from days to hours; human judgment applied at every gate; AI suggested month-level keys initially which was caught and corrected by the mediator review.',
    { label: 'section-5: tasks13-15', phase: 'Write sections' }
  ),
])

phase('Merge')

const merged = await agent(
  'Read these 5 files in order and merge them into one final plan file.\n\n' +
  'Files to read:\n' +
  '1. ' + PLAN_DIR + '/section-1.md\n' +
  '2. ' + PLAN_DIR + '/section-2.md\n' +
  '3. ' + PLAN_DIR + '/section-3.md\n' +
  '4. ' + PLAN_DIR + '/section-4.md\n' +
  '5. ' + PLAN_DIR + '/section-5.md\n\n' +
  'Write merged content to: ' + PLAN_DIR + '/2026-06-21-low-fare-calendar-implementation.md\n\n' +
  'Rules:\n' +
  '- section-1 has the full header (title, goal, architecture, global constraints) plus tasks 1-3. Include it in full.\n' +
  '- section-2 starts at Task 4. Do NOT duplicate the header.\n' +
  '- section-3 starts at Task 7.\n' +
  '- section-4 starts at Task 10.\n' +
  '- section-5 starts at Task 13.\n' +
  '- Separate sections with a single blank line.\n' +
  '- Do not add or remove any content.\n' +
  'After writing the merged file, delete section-1.md through section-5.md.\n' +
  'Report: "Plan written: " + line count of final file.',
  { label: 'merge all sections', phase: 'Merge' }
)

return { planPath: PLAN_DIR + '/2026-06-21-low-fare-calendar-implementation.md', result: merged }
