package com.simulated.lowfarecalendar.observability;

/**
 * Interface for cache and provider metrics recording.
 * Real implementation: MeterRegistryCacheMetricsService.
 * No-op fallback: NoOpCacheMetricsService.
 */
public interface CacheMetricsService {

    void recordCacheHit(String origin, String dest);

    void recordCacheMiss(String origin, String dest);

    void recordCacheInvalidation(String origin, String dest);

    void recordCircuitBreakerState(String providerId, int state);

    void recordProviderError(String providerId);

    void recordProviderCall(String providerId, long latencyMs, boolean success);

    void recordPubSubEvent(String outcome);

    /**
     * Record the total duration of a calendar request from service entry to response.
     *
     * @param origin     origin IATA code
     * @param dest       destination IATA code
     * @param currency   requested currency
     * @param latencyMs  total wall-clock time in milliseconds
     */
    void recordRequestDuration(String origin, String dest, String currency, long latencyMs);

    /**
     * Increment the counter of dates served from the 24h fallback key (stale data).
     * Each date within a single request that came from the fallback key counts as one.
     */
    void recordStaleServed(String origin, String dest);

    /**
     * Increment the counter of dates that were unavailable (no cache, no providers).
     * Each unavailable date within a single request counts as one.
     */
    void recordUnavailableDate(String origin, String dest);

    /**
     * Increment singleflight leader counter — this pod won the fetch slot for a date.
     */
    void recordSingleflightLeader(String origin, String dest);

    /**
     * Increment singleflight follower counter — this pod parked on another thread's future.
     */
    void recordSingleflightFollower(String origin, String dest);

    /**
     * Record the per-request cache hit ratio as a distribution summary (0.0–1.0).
     * A value of 1.0 means every date in the month was served from cache.
     */
    void recordRequestCacheHitRatio(String origin, String dest, double ratio);
}
