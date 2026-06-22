package com.simulated.lowfarecalendar.observability;

/**
 * Interface for cache and provider metrics recording.
 * Real implementation: MeterRegistryCacheMetricsService (Task 8).
 * No-op fallback: NoOpCacheMetricsService.
 */
public interface CacheMetricsService {

    /**
     * Increment lfc.cache.hits when a primary cache key is found.
     *
     * @param origin the origin IATA code (e.g. "KUL")
     * @param dest   the destination IATA code (e.g. "SIN")
     */
    void recordCacheHit(String origin, String dest);

    /**
     * Increment lfc.cache.misses when a primary cache key is absent.
     *
     * @param origin the origin IATA code
     * @param dest   the destination IATA code
     */
    void recordCacheMiss(String origin, String dest);

    /**
     * Increment lfc.cache.invalidations when a cache entry is evicted.
     *
     * @param origin the origin IATA code
     * @param dest   the destination IATA code
     */
    void recordCacheInvalidation(String origin, String dest);

    /**
     * Record the current circuit breaker state for a provider.
     *
     * @param providerId the provider identifier
     * @param state      0=CLOSED, 1=OPEN, 2=HALF_OPEN
     */
    void recordCircuitBreakerState(String providerId, int state);

    /**
     * Record that a provider returned an error response.
     *
     * @param providerId the provider identifier
     */
    void recordProviderError(String providerId);

    /**
     * Record a completed provider call with its latency and outcome.
     *
     * @param providerId  the provider identifier
     * @param latencyMs   call duration in milliseconds
     * @param success     true if the call returned a usable fare quote
     */
    void recordProviderCall(String providerId, long latencyMs, boolean success);

    /**
     * Record a Pub/Sub event processing outcome.
     *
     * @param outcome one of "processed", "stale_discarded", "error"
     */
    void recordPubSubEvent(String outcome);
}
