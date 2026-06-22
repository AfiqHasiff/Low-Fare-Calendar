package com.simulated.lowfarecalendar.pubsub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simulated.lowfarecalendar.cache.FareCacheService;
import com.simulated.lowfarecalendar.config.LfcProperties;
import com.simulated.lowfarecalendar.model.CachedFareEntry;
import com.simulated.lowfarecalendar.model.FlightQuery;
import com.simulated.lowfarecalendar.model.SoldOutEvent;
import com.simulated.lowfarecalendar.observability.CacheMetricsService;
import com.simulated.lowfarecalendar.service.ProviderAggregationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Processes "price-class-sold-out" Pub/Sub events.
 *
 * Idempotency is enforced via a Lua atomic compare-and-update on the
 * Redis fallback key. The script compares the incoming event's
 * generatedAt against the cached updatedAt; only events newer than
 * the current cache entry are processed (PROCEED). Duplicate or
 * out-of-order events return STALE and are acked without side effects.
 *
 * On PROCEED:
 *   1. Evict the primary cache key.
 *   2. Re-fetch from all providers via ProviderAggregationService.
 *   3. If re-fetch succeeds, write fresh entry back with updatedAt = event.generatedAt.
 *   4. If re-fetch fails, the fallback entry remains (stale served at read time).
 *   5. Record cache invalidation and pubsub event metrics.
 *
 * Exceptions nack the message for redelivery.
 */
@Service
public class SoldOutEventListener {

    private static final Logger log = LoggerFactory.getLogger(SoldOutEventListener.class);

    /**
     * Lua idempotency script.
     *
     * KEYS[1] = fallback cache key (lfc:{version}:fallback:{ORIGIN}:{DEST}:{DATE})
     * ARGV[1] = event.generatedAt ISO-8601 string
     *
     * Returns "PROCEED" when the incoming event is newer than the cached entry
     * or when the fallback key does not exist yet.
     * Returns "STALE" when the cached updatedAt is >= the incoming generatedAt.
     */
    private static final String IDEMPOTENCY_SCRIPT =
            "local current = redis.call('GET', KEYS[1])\n" +
            "if current == false then\n" +
            "  return 'PROCEED'\n" +
            "end\n" +
            "local payload = cjson.decode(current)\n" +
            "local cachedUpdatedAt = payload['updatedAt']\n" +
            "if cachedUpdatedAt == nil then\n" +
            "  return 'PROCEED'\n" +
            "end\n" +
            "if ARGV[1] <= cachedUpdatedAt then\n" +
            "  return 'STALE'\n" +
            "end\n" +
            "return 'PROCEED'";

    private static final RedisScript<String> IDEMPOTENCY_REDIS_SCRIPT =
            new DefaultRedisScript<>(IDEMPOTENCY_SCRIPT, String.class);

    private final RedisTemplate<String, String> redisTemplate;
    private final FareCacheService fareCacheService;
    private final ProviderAggregationService providerAggregationService;
    private final CacheMetricsService cacheMetricsService;
    private final LfcProperties lfcProperties;
    private final ObjectMapper objectMapper;

    public SoldOutEventListener(RedisTemplate<String, String> redisTemplate,
                                FareCacheService fareCacheService,
                                ProviderAggregationService providerAggregationService,
                                CacheMetricsService cacheMetricsService,
                                LfcProperties lfcProperties,
                                ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.fareCacheService = fareCacheService;
        this.providerAggregationService = providerAggregationService;
        this.cacheMetricsService = cacheMetricsService;
        this.lfcProperties = lfcProperties;
        this.objectMapper = objectMapper;
    }

    /**
     * Handle an incoming Pub/Sub message containing a JSON-serialised SoldOutEvent.
     *
     * This method is called by the Spring Cloud GCP Pub/Sub message handler
     * registered in PubSubConfig. The message is acked on success and nacked
     * on any unhandled exception to trigger redelivery.
     *
     * @param messagePayload raw JSON bytes from the Pub/Sub message data field
     * @param ackCallback    runnable to ack the message on success
     * @param nackCallback   runnable to nack the message on failure
     */
    public void handleMessage(byte[] messagePayload, Runnable ackCallback, Runnable nackCallback) {
        try {
            String json = new String(messagePayload, java.nio.charset.StandardCharsets.UTF_8);
            SoldOutEvent event = objectMapper.readValue(json, SoldOutEvent.class);

            String origin = event.getOrigin();
            String destination = event.getDestination();
            LocalDate date = LocalDate.parse(event.getDate());

            String fallbackKey = buildFallbackKey(origin, destination, date);

            String luaResult = redisTemplate.execute(
                    IDEMPOTENCY_REDIS_SCRIPT,
                    List.of(fallbackKey),
                    event.getGeneratedAt().toString());

            if ("STALE".equals(luaResult)) {
                log.debug("Discarding stale pubsub event for fallbackKey={} generatedAt={}",
                        fallbackKey, event.getGeneratedAt());
                cacheMetricsService.recordPubSubEvent("stale_discarded");
                ackCallback.run();
                return;
            }

            // PROCEED: evict primary key, re-aggregate, conditionally write back
            fareCacheService.evict(origin, destination, date);
            cacheMetricsService.recordCacheInvalidation(origin, destination);

            FlightQuery query = new FlightQuery(origin, destination, date);
            Optional<CachedFareEntry> aggregated = providerAggregationService.aggregate(query);

            if (aggregated.isPresent()) {
                CachedFareEntry entry = aggregated.get().toBuilder()
                        .updatedAt(event.getGeneratedAt())
                        .build();
                fareCacheService.set(origin, destination, date, entry);
            }

            cacheMetricsService.recordPubSubEvent("processed");
            ackCallback.run();

        } catch (Exception e) {
            log.error("Error processing sold-out pubsub event", e);
            cacheMetricsService.recordPubSubEvent("error");
            nackCallback.run();
        }
    }

    private String buildFallbackKey(String origin, String destination, LocalDate date) {
        String version = lfcProperties.getCache().getKeyVersion();
        return "lfc:" + version + ":fallback:" + origin + ":" + destination + ":" + date;
    }
}
