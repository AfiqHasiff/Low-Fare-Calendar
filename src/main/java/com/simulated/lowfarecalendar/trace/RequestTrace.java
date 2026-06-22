package com.simulated.lowfarecalendar.trace;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Per-request trace capturing the full lifecycle of a calendar API call.
 * Accumulated by RequestTraceContext via ThreadLocal and written to disk
 * by RequestTraceWriter at the end of each request.
 */
public class RequestTrace {

    private String requestId;
    private Instant requestedAt;
    private String origin;
    private String destination;
    private String month;
    private String currency;
    private Summary summary;
    private List<String> cacheHitDates;
    private List<String> cacheMissDates;
    private Map<String, List<ProviderResult>> providerResults;
    private List<ProviderResult> providerFailures;
    private List<String> staleDates;
    private List<String> unavailableDates;
    private CurrencyInfo currencyInfo;
    private boolean thunderingHerdObserved;
    private String observabilityNote;

    /**
     * Factory method that initialises all collections and sets requestId + requestedAt.
     */
    public static RequestTrace start(String origin, String destination, String month, String currency) {
        RequestTrace trace = new RequestTrace();
        trace.requestId = UUID.randomUUID().toString();
        trace.requestedAt = Instant.now();
        trace.origin = origin;
        trace.destination = destination;
        trace.month = month;
        trace.currency = currency;
        trace.summary = new Summary();
        trace.cacheHitDates = new ArrayList<>();
        trace.cacheMissDates = new ArrayList<>();
        trace.providerResults = new HashMap<>();
        trace.providerFailures = new ArrayList<>();
        trace.staleDates = new ArrayList<>();
        trace.unavailableDates = new ArrayList<>();
        trace.currencyInfo = new CurrencyInfo();
        trace.thunderingHerdObserved = false;
        trace.observabilityNote = "Metrics available at GET http://localhost:8080/actuator/prometheus — filter by lfc_* prefix";
        return trace;
    }

    // ------------------------------------------------------------------
    // Getters and setters
    // ------------------------------------------------------------------

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public Instant getRequestedAt() { return requestedAt; }
    public void setRequestedAt(Instant requestedAt) { this.requestedAt = requestedAt; }

    public String getOrigin() { return origin; }
    public void setOrigin(String origin) { this.origin = origin; }

    public String getDestination() { return destination; }
    public void setDestination(String destination) { this.destination = destination; }

    public String getMonth() { return month; }
    public void setMonth(String month) { this.month = month; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public Summary getSummary() { return summary; }
    public void setSummary(Summary summary) { this.summary = summary; }

    public List<String> getCacheHitDates() { return cacheHitDates; }
    public void setCacheHitDates(List<String> cacheHitDates) { this.cacheHitDates = cacheHitDates; }

    public List<String> getCacheMissDates() { return cacheMissDates; }
    public void setCacheMissDates(List<String> cacheMissDates) { this.cacheMissDates = cacheMissDates; }

    public Map<String, List<ProviderResult>> getProviderResults() { return providerResults; }
    public void setProviderResults(Map<String, List<ProviderResult>> providerResults) { this.providerResults = providerResults; }

    public List<ProviderResult> getProviderFailures() { return providerFailures; }
    public void setProviderFailures(List<ProviderResult> providerFailures) { this.providerFailures = providerFailures; }

    public List<String> getStaleDates() { return staleDates; }
    public void setStaleDates(List<String> staleDates) { this.staleDates = staleDates; }

    public List<String> getUnavailableDates() { return unavailableDates; }
    public void setUnavailableDates(List<String> unavailableDates) { this.unavailableDates = unavailableDates; }

    public CurrencyInfo getCurrencyInfo() { return currencyInfo; }
    public void setCurrencyInfo(CurrencyInfo currencyInfo) { this.currencyInfo = currencyInfo; }

    public boolean isThunderingHerdObserved() { return thunderingHerdObserved; }
    public void setThunderingHerdObserved(boolean thunderingHerdObserved) { this.thunderingHerdObserved = thunderingHerdObserved; }

    public String getObservabilityNote() { return observabilityNote; }
    public void setObservabilityNote(String observabilityNote) { this.observabilityNote = observabilityNote; }

    // ------------------------------------------------------------------
    // Inner classes
    // ------------------------------------------------------------------

    public static class Summary {
        private int totalDays;
        private int cacheHits;
        private int cacheMisses;
        private int staleFromFallback;
        private int unavailableDays;
        public int singleflightLeaderDates;
        public int singleflightFollowerDates;

        public int getTotalDays() { return totalDays; }
        public void setTotalDays(int totalDays) { this.totalDays = totalDays; }

        public int getCacheHits() { return cacheHits; }
        public void setCacheHits(int cacheHits) { this.cacheHits = cacheHits; }

        public int getCacheMisses() { return cacheMisses; }
        public void setCacheMisses(int cacheMisses) { this.cacheMisses = cacheMisses; }

        public int getStaleFromFallback() { return staleFromFallback; }
        public void setStaleFromFallback(int staleFromFallback) { this.staleFromFallback = staleFromFallback; }

        public int getUnavailableDays() { return unavailableDays; }
        public void setUnavailableDays(int unavailableDays) { this.unavailableDays = unavailableDays; }

        public int getSingleflightLeaderDates() { return singleflightLeaderDates; }
        public void setSingleflightLeaderDates(int singleflightLeaderDates) { this.singleflightLeaderDates = singleflightLeaderDates; }

        public int getSingleflightFollowerDates() { return singleflightFollowerDates; }
        public void setSingleflightFollowerDates(int singleflightFollowerDates) { this.singleflightFollowerDates = singleflightFollowerDates; }
    }

    public static class ProviderResult {
        private String providerId;
        private BigDecimal priceUsd;
        private long latencyMs;
        private boolean success;
        private String failureReason;

        public ProviderResult() {}

        public ProviderResult(String providerId, BigDecimal priceUsd, long latencyMs,
                              boolean success, String failureReason) {
            this.providerId = providerId;
            this.priceUsd = priceUsd;
            this.latencyMs = latencyMs;
            this.success = success;
            this.failureReason = failureReason;
        }

        public String getProviderId() { return providerId; }
        public void setProviderId(String providerId) { this.providerId = providerId; }

        public BigDecimal getPriceUsd() { return priceUsd; }
        public void setPriceUsd(BigDecimal priceUsd) { this.priceUsd = priceUsd; }

        public long getLatencyMs() { return latencyMs; }
        public void setLatencyMs(long latencyMs) { this.latencyMs = latencyMs; }

        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }

        public String getFailureReason() { return failureReason; }
        public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
    }

    public static class CurrencyInfo {
        private String requestedCurrency;
        private String sourceCurrency = "USD";
        private boolean conversionActive;
        private String sampleRate;

        public String getRequestedCurrency() { return requestedCurrency; }
        public void setRequestedCurrency(String requestedCurrency) { this.requestedCurrency = requestedCurrency; }

        public String getSourceCurrency() { return sourceCurrency; }
        public void setSourceCurrency(String sourceCurrency) { this.sourceCurrency = sourceCurrency; }

        public boolean isConversionActive() { return conversionActive; }
        public void setConversionActive(boolean conversionActive) { this.conversionActive = conversionActive; }

        public String getSampleRate() { return sampleRate; }
        public void setSampleRate(String sampleRate) { this.sampleRate = sampleRate; }
    }
}
