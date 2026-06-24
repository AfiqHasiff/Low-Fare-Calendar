package com.simulated.lowfarecalendar.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@Validated
@ConfigurationProperties(prefix = "lfc")
public class LfcProperties {

    @Valid
    @NotNull
    private CacheProperties cache = new CacheProperties();

    @Valid
    @NotNull
    private HotRoutesProperties hotRoutes = new HotRoutesProperties();

    @Valid
    @NotNull
    private WarmingProperties warming = new WarmingProperties();

    @Valid
    @NotNull
    private LockProperties lock = new LockProperties();

    @Valid
    @NotNull
    private CurrencyProperties currency = new CurrencyProperties();

    @Valid
    @NotNull
    private ProvidersProperties providers = new ProvidersProperties();

    @Valid
    @NotNull
    private CircuitBreakerProperties circuitBreaker = new CircuitBreakerProperties();

    @Valid
    @NotNull
    private PubSubProperties pubsub = new PubSubProperties();

    @Valid
    @NotNull
    private ObservabilityProperties observability = new ObservabilityProperties();

    // Manual getters for Lombok compatibility issues with Java 21+
    public CacheProperties getCache() {
        return cache;
    }

    public void setCache(CacheProperties cache) {
        this.cache = cache;
    }

    public HotRoutesProperties getHotRoutes() {
        return hotRoutes;
    }

    public void setHotRoutes(HotRoutesProperties hotRoutes) {
        this.hotRoutes = hotRoutes;
    }

    public WarmingProperties getWarming() {
        return warming;
    }

    public void setWarming(WarmingProperties warming) {
        this.warming = warming;
    }

    public LockProperties getLock() {
        return lock;
    }

    public void setLock(LockProperties lock) {
        this.lock = lock;
    }

    public CurrencyProperties getCurrency() {
        return currency;
    }

    public void setCurrency(CurrencyProperties currency) {
        this.currency = currency;
    }

    public ProvidersProperties getProviders() {
        return providers;
    }

    public void setProviders(ProvidersProperties providers) {
        this.providers = providers;
    }

    public CircuitBreakerProperties getCircuitBreaker() {
        return circuitBreaker;
    }

    public void setCircuitBreaker(CircuitBreakerProperties circuitBreaker) {
        this.circuitBreaker = circuitBreaker;
    }

    public PubSubProperties getPubsub() {
        return pubsub;
    }

    public void setPubsub(PubSubProperties pubsub) {
        this.pubsub = pubsub;
    }

    public ObservabilityProperties getObservability() {
        return observability;
    }

    public void setObservability(ObservabilityProperties observability) {
        this.observability = observability;
    }

    // ------------------------------------------------------------------
    // Nested config classes
    // ------------------------------------------------------------------

    @Data
    @NoArgsConstructor
    public static class CacheProperties {

        @Min(1)
        private long hotRouteTtlSeconds = 600;

        @Min(1)
        private long standardRouteTtlSeconds = 300;

        @Min(1)
        private long fallbackTtlSeconds = 86400;

        @DecimalMin("0.0")
        @DecimalMax("1.0")
        private double jitterMinPct = 0.10;

        @DecimalMin("0.0")
        @DecimalMax("1.0")
        private double jitterMaxPct = 0.30;

        @DecimalMin("0.0")
        @DecimalMax("1.0")
        private double warmerSkipThresholdPct = 0.50;

        @NotBlank
        private String keyVersion = "v1";

        public long getHotRouteTtlSeconds() { return hotRouteTtlSeconds; }
        public void setHotRouteTtlSeconds(long v) { this.hotRouteTtlSeconds = v; }

        public long getStandardRouteTtlSeconds() { return standardRouteTtlSeconds; }
        public void setStandardRouteTtlSeconds(long v) { this.standardRouteTtlSeconds = v; }

        public long getFallbackTtlSeconds() { return fallbackTtlSeconds; }
        public void setFallbackTtlSeconds(long v) { this.fallbackTtlSeconds = v; }

        public double getJitterMinPct() { return jitterMinPct; }
        public void setJitterMinPct(double v) { this.jitterMinPct = v; }

        public double getJitterMaxPct() { return jitterMaxPct; }
        public void setJitterMaxPct(double v) { this.jitterMaxPct = v; }

        public double getWarmerSkipThresholdPct() { return warmerSkipThresholdPct; }
        public void setWarmerSkipThresholdPct(double v) { this.warmerSkipThresholdPct = v; }

        public String getKeyVersion() { return keyVersion; }
        public void setKeyVersion(String v) { this.keyVersion = v; }
    }

    @Data
    @NoArgsConstructor
    public static class HotRoutesProperties {

        @Min(1)
        private long hotThreshold = 100;

        @Min(1)
        private int topK = 50;

        @Min(0)
        private long decayMinScore = 10;

        @Min(1)
        private long decayIntervalSeconds = 3600;

        public long getHotThreshold() { return hotThreshold; }
        public void setHotThreshold(long v) { this.hotThreshold = v; }

        public int getTopK() { return topK; }
        public void setTopK(int v) { this.topK = v; }

        public long getDecayMinScore() { return decayMinScore; }
        public void setDecayMinScore(long v) { this.decayMinScore = v; }

        public long getDecayIntervalSeconds() { return decayIntervalSeconds; }
        public void setDecayIntervalSeconds(long v) { this.decayIntervalSeconds = v; }
    }

    @Data
    @NoArgsConstructor
    public static class WarmingProperties {

        @Min(1)
        private long intervalSeconds = 300;

        @Min(1)
        private int lookaheadDays = 30;

        // Pre-defined routes to seed into the hot-routes ZSET on startup.
        // Format: "ORIGIN:DEST" e.g. "KUL:SIN". Seeded with hot-threshold score
        // so the warmer picks them up immediately on a fresh start.
        // Only applied if the route has no existing score (won't overwrite organic traffic).
        private java.util.List<String> seedRoutes = new java.util.ArrayList<>();

        public long getIntervalSeconds() { return intervalSeconds; }
        public void setIntervalSeconds(long v) { this.intervalSeconds = v; }

        public int getLookaheadDays() { return lookaheadDays; }
        public void setLookaheadDays(int v) { this.lookaheadDays = v; }

        public java.util.List<String> getSeedRoutes() { return seedRoutes; }
        public void setSeedRoutes(java.util.List<String> v) { this.seedRoutes = v; }
    }

    @Data
    @NoArgsConstructor
    public static class LockProperties {

        @Min(1)
        private long ttlMs = 5000;

        @Min(1)
        private long pollIntervalMs = 50;

        @Min(1)
        private long pollTimeoutMs = 4000;

        public long getTtlMs() { return ttlMs; }
        public void setTtlMs(long v) { this.ttlMs = v; }

        public long getPollIntervalMs() { return pollIntervalMs; }
        public void setPollIntervalMs(long v) { this.pollIntervalMs = v; }

        public long getPollTimeoutMs() { return pollTimeoutMs; }
        public void setPollTimeoutMs(long v) { this.pollTimeoutMs = v; }
    }

    @Data
    @NoArgsConstructor
    public static class CurrencyProperties {

        @Valid
        @NotNull
        private RatesProperties rates = new RatesProperties();

        public RatesProperties getRates() {
            return rates;
        }

        public void setRates(RatesProperties rates) {
            this.rates = rates;
        }

        @Data
        @NoArgsConstructor
        public static class RatesProperties {

            @NotNull
            private BigDecimal usdMyr = new BigDecimal("4.47");

            @NotNull
            private BigDecimal usdThb = new BigDecimal("33.50");

            @NotNull
            private BigDecimal usdUsd = BigDecimal.ONE;

            public BigDecimal getUsdMyr() {
                return usdMyr;
            }

            public void setUsdMyr(BigDecimal usdMyr) {
                this.usdMyr = usdMyr;
            }

            public BigDecimal getUsdThb() {
                return usdThb;
            }

            public void setUsdThb(BigDecimal usdThb) {
                this.usdThb = usdThb;
            }

            public BigDecimal getUsdUsd() {
                return usdUsd;
            }

            public void setUsdUsd(BigDecimal usdUsd) {
                this.usdUsd = usdUsd;
            }
        }
    }

    @Data
    @NoArgsConstructor
    public static class ProviderSimProperties {

        @NotNull
        private BigDecimal priceMinUsd = new BigDecimal("100.00");

        @NotNull
        private BigDecimal priceMaxUsd = new BigDecimal("500.00");

        @Min(0)
        private long latencyMinMs = 50;

        @Min(0)
        private long latencyMaxMs = 300;

        @DecimalMin("0.0")
        @DecimalMax("1.0")
        private double errorRate = 0.05;

        private boolean enabled = true;

        public BigDecimal getPriceMinUsd() { return priceMinUsd; }
        public void setPriceMinUsd(BigDecimal priceMinUsd) { this.priceMinUsd = priceMinUsd; }

        public BigDecimal getPriceMaxUsd() { return priceMaxUsd; }
        public void setPriceMaxUsd(BigDecimal priceMaxUsd) { this.priceMaxUsd = priceMaxUsd; }

        public long getLatencyMinMs() { return latencyMinMs; }
        public void setLatencyMinMs(long latencyMinMs) { this.latencyMinMs = latencyMinMs; }

        public long getLatencyMaxMs() { return latencyMaxMs; }
        public void setLatencyMaxMs(long latencyMaxMs) { this.latencyMaxMs = latencyMaxMs; }

        public double getErrorRate() { return errorRate; }
        public void setErrorRate(double errorRate) { this.errorRate = errorRate; }

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    @Data
    @NoArgsConstructor
    public static class ProvidersProperties {

        @Valid
        @NotNull
        private ProviderSimProperties providerA = new ProviderSimProperties();

        @Valid
        @NotNull
        private ProviderSimProperties providerB = new ProviderSimProperties();

        @Valid
        @NotNull
        private ProviderSimProperties providerC = new ProviderSimProperties();

        public ProviderSimProperties getProviderA() { return providerA; }
        public void setProviderA(ProviderSimProperties providerA) { this.providerA = providerA; }

        public ProviderSimProperties getProviderB() { return providerB; }
        public void setProviderB(ProviderSimProperties providerB) { this.providerB = providerB; }

        public ProviderSimProperties getProviderC() { return providerC; }
        public void setProviderC(ProviderSimProperties providerC) { this.providerC = providerC; }
    }

    @Data
    @NoArgsConstructor
    public static class CircuitBreakerProperties {

        @Min(1)
        private int slidingWindowSize = 20;

        @Min(1)
        private int minimumNumberOfCalls = 10;

        @DecimalMin("0.0")
        @DecimalMax("100.0")
        private float failureRateThreshold = 50.0f;

        @Min(1)
        private long slowCallDurationThresholdMs = 1000;

        @DecimalMin("0.0")
        @DecimalMax("100.0")
        private float slowCallRateThreshold = 50.0f;

        @Min(1)
        private long waitDurationInOpenStateMs = 5000;

        @Min(1)
        private int permittedCallsInHalfOpen = 3;

        public int getSlidingWindowSize() { return slidingWindowSize; }
        public void setSlidingWindowSize(int slidingWindowSize) { this.slidingWindowSize = slidingWindowSize; }

        public int getMinimumNumberOfCalls() { return minimumNumberOfCalls; }
        public void setMinimumNumberOfCalls(int minimumNumberOfCalls) { this.minimumNumberOfCalls = minimumNumberOfCalls; }

        public float getFailureRateThreshold() { return failureRateThreshold; }
        public void setFailureRateThreshold(float failureRateThreshold) { this.failureRateThreshold = failureRateThreshold; }

        public long getSlowCallDurationThresholdMs() { return slowCallDurationThresholdMs; }
        public void setSlowCallDurationThresholdMs(long slowCallDurationThresholdMs) { this.slowCallDurationThresholdMs = slowCallDurationThresholdMs; }

        public float getSlowCallRateThreshold() { return slowCallRateThreshold; }
        public void setSlowCallRateThreshold(float slowCallRateThreshold) { this.slowCallRateThreshold = slowCallRateThreshold; }

        public long getWaitDurationInOpenStateMs() { return waitDurationInOpenStateMs; }
        public void setWaitDurationInOpenStateMs(long waitDurationInOpenStateMs) { this.waitDurationInOpenStateMs = waitDurationInOpenStateMs; }

        public int getPermittedCallsInHalfOpen() { return permittedCallsInHalfOpen; }
        public void setPermittedCallsInHalfOpen(int permittedCallsInHalfOpen) { this.permittedCallsInHalfOpen = permittedCallsInHalfOpen; }
    }

    @Data
    @NoArgsConstructor
    public static class PubSubProperties {

        @NotBlank
        private String topic = "price-class-sold-out";

        @NotBlank
        private String subscription = "price-class-sold-out-sub";

        @NotBlank
        private String emulatorHost = "localhost:8085";

        private boolean enabled = true;

        public String getTopic() { return topic; }
        public void setTopic(String v) { this.topic = v; }

        public String getSubscription() { return subscription; }
        public void setSubscription(String v) { this.subscription = v; }

        public String getEmulatorHost() { return emulatorHost; }
        public void setEmulatorHost(String v) { this.emulatorHost = v; }

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
    }

    @Data
    @NoArgsConstructor
    public static class ObservabilityProperties {

        private boolean perRouteTagsEnabled = true;

        private boolean perDateTagsEnabled = false;

        public boolean isPerRouteTagsEnabled() { return perRouteTagsEnabled; }
        public void setPerRouteTagsEnabled(boolean v) { this.perRouteTagsEnabled = v; }

        public boolean isPerDateTagsEnabled() { return perDateTagsEnabled; }
        public void setPerDateTagsEnabled(boolean v) { this.perDateTagsEnabled = v; }
    }
}
