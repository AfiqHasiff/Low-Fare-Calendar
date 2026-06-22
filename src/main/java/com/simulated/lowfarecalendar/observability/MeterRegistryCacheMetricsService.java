package com.simulated.lowfarecalendar.observability;

import com.simulated.lowfarecalendar.config.LfcProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Micrometer-backed implementation of {@link CacheMetricsService}.
 * Registers counters, timers, and gauges against the injected
 * {@link MeterRegistry} (Prometheus / OTel bridge).
 *
 * <p>Route tags (origin+dest) are only added when
 * {@link LfcProperties.ObservabilityProperties#isPerRouteTagsEnabled()} is true.
 */
@Service("meterRegistryCacheMetricsService")
public class MeterRegistryCacheMetricsService implements CacheMetricsService {

    private final MeterRegistry meterRegistry;
    private final LfcProperties lfcProperties;

    /**
     * Strong references for circuit-breaker gauges, keyed by providerId.
     * Micrometer gauges hold a weak reference to the observed object; we must
     * keep a strong reference here to prevent GC from zeroing the gauge.
     */
    private final ConcurrentHashMap<String, AtomicInteger> cbStateHolders =
            new ConcurrentHashMap<>();

    public MeterRegistryCacheMetricsService(MeterRegistry meterRegistry,
                                            LfcProperties lfcProperties) {
        this.meterRegistry = meterRegistry;
        this.lfcProperties = lfcProperties;
    }

    // ------------------------------------------------------------------
    // Cache counters
    // ------------------------------------------------------------------

    @Override
    public void recordCacheHit(String origin, String dest) {
        meterRegistry.counter("lfc.cache.hits", routeTags(origin, dest))
                .increment();
    }

    @Override
    public void recordCacheMiss(String origin, String dest) {
        meterRegistry.counter("lfc.cache.misses", routeTags(origin, dest))
                .increment();
    }

    @Override
    public void recordCacheInvalidation(String origin, String dest) {
        meterRegistry.counter("lfc.cache.invalidations", routeTags(origin, dest))
                .increment();
    }

    // ------------------------------------------------------------------
    // Circuit-breaker gauge
    // ------------------------------------------------------------------

    /**
     * Register (or update) a gauge for circuit-breaker state.
     * state: 0 = CLOSED, 1 = OPEN, 2 = HALF_OPEN.
     */
    @Override
    public void recordCircuitBreakerState(String providerId, int state) {
        AtomicInteger holder = cbStateHolders.computeIfAbsent(providerId, id -> {
            AtomicInteger atom = new AtomicInteger(state);
            meterRegistry.gauge("lfc.cb.state",
                    Tags.of("provider", id),
                    atom);
            return atom;
        });
        holder.set(state);
    }

    // ------------------------------------------------------------------
    // Provider counters / timer
    // ------------------------------------------------------------------

    @Override
    public void recordProviderError(String providerId) {
        meterRegistry.counter("lfc.provider.errors",
                Tags.of("provider", providerId))
                .increment();
    }

    @Override
    public void recordProviderCall(String providerId, long latencyMs, boolean success) {
        Timer timer = meterRegistry.timer("lfc.provider.latency",
                Tags.of("provider", providerId,
                        "outcome", success ? "success" : "error"));
        timer.record(latencyMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void recordPubSubEvent(String outcome) {
        meterRegistry.counter("lfc.pubsub.events", Tags.of("outcome", outcome))
                .increment();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Tags routeTags(String origin, String dest) {
        if (lfcProperties.getObservability().isPerRouteTagsEnabled()) {
            return Tags.of("origin", origin, "dest", dest);
        }
        return Tags.empty();
    }
}
