package com.simulated.lowfarecalendar.model;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Pub/Sub event payload for the "price-class-sold-out" topic.
 *
 * generatedAt is the wall-clock timestamp at which the booking service detected
 * the sold-out condition. This is the authoritative ordering signal used by the
 * Lua idempotency script to discard stale / duplicate events.
 *
 * It is set by the producer at event creation time — NOT the Pub/Sub publish
 * timestamp, which can be delayed by network or queue pressure.
 *
 * currency on the event is informational only; soldOutPrice is treated as USD
 * because all provider prices are denominated in USD.
 */
public class SoldOutEvent {

    private String eventId;
    private String origin;
    private String destination;
    private String date;
    private String priceClass;
    private BigDecimal soldOutPrice;
    private String currency;

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Instant generatedAt;

    public SoldOutEvent() {
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String eventId;
        private String origin;
        private String destination;
        private String date;
        private String priceClass;
        private BigDecimal soldOutPrice;
        private String currency;
        private Instant generatedAt;

        public Builder eventId(String eventId) { this.eventId = eventId; return this; }
        public Builder origin(String origin) { this.origin = origin; return this; }
        public Builder destination(String destination) { this.destination = destination; return this; }
        public Builder date(String date) { this.date = date; return this; }
        public Builder date(java.time.LocalDate date) { this.date = date == null ? null : date.toString(); return this; }
        public Builder priceClass(String priceClass) { this.priceClass = priceClass; return this; }
        public Builder soldOutPrice(BigDecimal soldOutPrice) { this.soldOutPrice = soldOutPrice; return this; }
        public Builder currency(String currency) { this.currency = currency; return this; }
        public Builder generatedAt(Instant generatedAt) { this.generatedAt = generatedAt; return this; }

        public SoldOutEvent build() {
            SoldOutEvent e = new SoldOutEvent();
            e.eventId = this.eventId;
            e.origin = this.origin;
            e.destination = this.destination;
            e.date = this.date;
            e.priceClass = this.priceClass;
            e.soldOutPrice = this.soldOutPrice;
            e.currency = this.currency;
            e.generatedAt = this.generatedAt;
            return e;
        }
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
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

    public String getPriceClass() {
        return priceClass;
    }

    public void setPriceClass(String priceClass) {
        this.priceClass = priceClass;
    }

    public BigDecimal getSoldOutPrice() {
        return soldOutPrice;
    }

    public void setSoldOutPrice(BigDecimal soldOutPrice) {
        this.soldOutPrice = soldOutPrice;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public Instant getGeneratedAt() {
        return generatedAt;
    }

    public void setGeneratedAt(Instant generatedAt) {
        this.generatedAt = generatedAt;
    }
}
