package com.simulated.lowfarecalendar.model;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Payload stored in Redis as a UTF-8 JSON string.
 * Key: lfc:v1:{ORIGIN}:{DEST}:{YYYY-MM-DD}
 *
 * IMPORTANT — updatedAt semantics:
 *   For Pub/Sub-driven updates: stores event.generatedAt (NOT Redis write time).
 *   For warmer-driven updates: stores Instant.now() at fetch time.
 *   This field is the authoritative ordering signal for the Lua idempotency script.
 *
 * stale=true is set only when this entry was retrieved from the fallback key
 * because all providers failed. It is never stored as true in the primary key.
 *
 * currency is always "USD" in Redis. Conversion is applied at read time.
 */
public class CachedFareEntry {

    private String origin;
    private String destination;
    private String date;
    private BigDecimal lowestPrice;
    private String currency = "USD";

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Instant updatedAt;

    private int providerCount;
    private List<String> respondingProviders;
    private String winningProvider;

    public CachedFareEntry() {
    }

    public CachedFareEntry(String origin, String destination, String date, BigDecimal lowestPrice,
                           String currency, Instant updatedAt, int providerCount,
                           List<String> respondingProviders, String winningProvider) {
        this.origin = origin;
        this.destination = destination;
        this.date = date;
        this.lowestPrice = lowestPrice;
        this.currency = currency;
        this.updatedAt = updatedAt;
        this.providerCount = providerCount;
        this.respondingProviders = respondingProviders;
        this.winningProvider = winningProvider;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder()
                .origin(this.origin)
                .destination(this.destination)
                .date(this.date)
                .lowestPrice(this.lowestPrice)
                .currency(this.currency)
                .updatedAt(this.updatedAt)
                .providerCount(this.providerCount)
                .respondingProviders(this.respondingProviders)
                .winningProvider(this.winningProvider);
    }

    public static class Builder {
        private String origin;
        private String destination;
        private String date;
        private BigDecimal lowestPrice;
        private String currency = "USD";
        private Instant updatedAt;
        private int providerCount;
        private List<String> respondingProviders;
        private String winningProvider;

        public Builder origin(String origin) {
            this.origin = origin;
            return this;
        }

        public Builder destination(String destination) {
            this.destination = destination;
            return this;
        }

        public Builder date(String date) {
            this.date = date;
            return this;
        }

        public Builder lowestPrice(BigDecimal lowestPrice) {
            this.lowestPrice = lowestPrice;
            return this;
        }

        public Builder currency(String currency) {
            this.currency = currency;
            return this;
        }

        public Builder updatedAt(Instant updatedAt) {
            this.updatedAt = updatedAt;
            return this;
        }

        public Builder providerCount(int providerCount) {
            this.providerCount = providerCount;
            return this;
        }

        public Builder respondingProviders(List<String> respondingProviders) {
            this.respondingProviders = respondingProviders;
            return this;
        }

        public Builder winningProvider(String winningProvider) {
            this.winningProvider = winningProvider;
            return this;
        }

        public CachedFareEntry build() {
            return new CachedFareEntry(origin, destination, date, lowestPrice, currency,
                    updatedAt, providerCount, respondingProviders, winningProvider);
        }
    }

    public String getOrigin() {
        return origin;
    }

    public void setOrigin(String origin) {
        this.origin = origin;
    }

    public String getDestination() {
        return destination;
    }

    public void setDestination(String destination) {
        this.destination = destination;
    }

    public String getDate() {
        return date;
    }

    public void setDate(String date) {
        this.date = date;
    }

    public BigDecimal getLowestPrice() {
        return lowestPrice;
    }

    public void setLowestPrice(BigDecimal lowestPrice) {
        this.lowestPrice = lowestPrice;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public int getProviderCount() {
        return providerCount;
    }

    public void setProviderCount(int providerCount) {
        this.providerCount = providerCount;
    }

    public List<String> getRespondingProviders() {
        return respondingProviders;
    }

    public void setRespondingProviders(List<String> respondingProviders) {
        this.respondingProviders = respondingProviders;
    }

    public String getWinningProvider() {
        return winningProvider;
    }

    public void setWinningProvider(String winningProvider) {
        this.winningProvider = winningProvider;
    }
}
