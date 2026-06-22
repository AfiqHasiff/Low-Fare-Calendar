package com.simulated.lowfarecalendar.observability;

import com.simulated.lowfarecalendar.config.LfcProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link MeterRegistryCacheMetricsService}.
 * Uses {@link SimpleMeterRegistry} — no Spring context required.
 */
class CacheMetricsServiceTest {

    private SimpleMeterRegistry registry;
    private MeterRegistryCacheMetricsService metricsService;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();

        LfcProperties props = new LfcProperties();
        // perRouteTagsEnabled defaults to true in LfcProperties.ObservabilityProperties
        metricsService = new MeterRegistryCacheMetricsService(registry, props);
    }

    // ------------------------------------------------------------------
    // lfc.cache.hits
    // ------------------------------------------------------------------

    @Test
    void recordCacheHit_incrementsHitsCounter() {
        metricsService.recordCacheHit("KUL", "SIN");

        Counter counter = registry.find("lfc.cache.hits")
                .tag("origin", "KUL")
                .tag("dest", "SIN")
                .counter();

        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void recordCacheHit_incrementsOnEachCall() {
        metricsService.recordCacheHit("KUL", "SIN");
        metricsService.recordCacheHit("KUL", "SIN");
        metricsService.recordCacheHit("KUL", "SIN");

        Counter counter = registry.find("lfc.cache.hits")
                .tag("origin", "KUL")
                .tag("dest", "SIN")
                .counter();

        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(3.0);
    }

    // ------------------------------------------------------------------
    // lfc.cache.misses
    // ------------------------------------------------------------------

    @Test
    void recordCacheMiss_incrementsMissesCounter() {
        metricsService.recordCacheMiss("KUL", "SIN");

        Counter counter = registry.find("lfc.cache.misses")
                .tag("origin", "KUL")
                .tag("dest", "SIN")
                .counter();

        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    // ------------------------------------------------------------------
    // lfc.cache.invalidations
    // ------------------------------------------------------------------

    @Test
    void recordCacheInvalidation_incrementsInvalidationsCounter() {
        metricsService.recordCacheInvalidation("KUL", "SIN");

        Counter counter = registry.find("lfc.cache.invalidations")
                .tag("origin", "KUL")
                .tag("dest", "SIN")
                .counter();

        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    // ------------------------------------------------------------------
    // lfc.cb.state (gauge)
    // ------------------------------------------------------------------

    @Test
    void recordCircuitBreakerState_registersGaugeWithCorrectValue() {
        metricsService.recordCircuitBreakerState("providerA", 0);

        Gauge gauge = registry.find("lfc.cb.state")
                .tag("provider", "providerA")
                .gauge();

        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).isEqualTo(0.0);
    }

    @Test
    void recordCircuitBreakerState_updatesExistingGauge() {
        metricsService.recordCircuitBreakerState("providerA", 0);
        metricsService.recordCircuitBreakerState("providerA", 1); // OPEN

        Gauge gauge = registry.find("lfc.cb.state")
                .tag("provider", "providerA")
                .gauge();

        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).isEqualTo(1.0);
    }

    // ------------------------------------------------------------------
    // lfc.provider.errors
    // ------------------------------------------------------------------

    @Test
    void recordProviderError_incrementsErrorsCounter() {
        metricsService.recordProviderError("providerA");

        Counter counter = registry.find("lfc.provider.errors")
                .tag("provider", "providerA")
                .counter();

        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    // ------------------------------------------------------------------
    // lfc.provider.latency (timer)
    // ------------------------------------------------------------------

    @Test
    void recordProviderCall_success_recordsTimerWithCorrectTags() {
        metricsService.recordProviderCall("providerA", 150L, true);

        Timer timer = registry.find("lfc.provider.latency")
                .tag("provider", "providerA")
                .tag("outcome", "success")
                .timer();

        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(150.0);
    }

    @Test
    void recordProviderCall_failure_recordsTimerWithErrorOutcome() {
        metricsService.recordProviderCall("providerB", 500L, false);

        Timer timer = registry.find("lfc.provider.latency")
                .tag("provider", "providerB")
                .tag("outcome", "error")
                .timer();

        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(500.0);
    }

    // ------------------------------------------------------------------
    // perRouteTagsEnabled = false suppresses route tags
    // ------------------------------------------------------------------

    @Test
    void recordCacheHit_withRouteTagsDisabled_noRouteTags() {
        LfcProperties props = new LfcProperties();
        props.getObservability().setPerRouteTagsEnabled(false);
        MeterRegistryCacheMetricsService svc =
                new MeterRegistryCacheMetricsService(registry, props);

        svc.recordCacheHit("KUL", "SIN");

        // Counter exists but has no origin/dest tags
        Counter counter = registry.find("lfc.cache.hits").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }
}
