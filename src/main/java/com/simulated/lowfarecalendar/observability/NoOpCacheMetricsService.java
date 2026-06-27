package com.simulated.lowfarecalendar.observability;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnMissingBean(name = "meterRegistryCacheMetricsService")
public class NoOpCacheMetricsService implements CacheMetricsService {

    @Override
    public void recordCacheHit(String origin, String dest) {
    }

    @Override
    public void recordCacheMiss(String origin, String dest) {
    }

    @Override
    public void recordCacheInvalidation(String origin, String dest) {
    }

    @Override
    public void recordCircuitBreakerState(String providerId, int state) {
    }

    @Override
    public void recordProviderError(String providerId) {
    }

    @Override
    public void recordProviderCall(String providerId, long latencyMs, boolean success) {
    }

    @Override
    public void recordPubSubEvent(String outcome) {
    }

    @Override
    public void recordRequestDuration(String origin, String dest, String currency, long latencyMs) {
    }

    @Override
    public void recordStaleServed(String origin, String dest) {
    }

    @Override
    public void recordUnavailableDate(String origin, String dest) {
    }

    @Override
    public void recordSingleflightLeader(String origin, String dest) {
    }

    @Override
    public void recordSingleflightFollower(String origin, String dest) {
    }

    @Override
    public void recordRequestCacheHitRatio(String origin, String dest, double ratio) {
    }
}
