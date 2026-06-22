package com.simulated.lowfarecalendar.model;

import java.time.Instant;
import java.util.List;

/**
 * Top-level API response for GET /api/v1/flights/calendar.
 * calendar contains one DayPrice per day in the requested month (up to 31 entries).
 * generatedAt is the wall-clock time this response was assembled by CalendarService.
 * currency is the ISO-4217 code requested by the caller.
 */
public record CalendarResponse(
        String origin,
        String destination,
        String month,
        String currency,
        List<DayPrice> calendar,
        Instant generatedAt
) {
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String origin;
        private String destination;
        private String month;
        private String currency;
        private List<DayPrice> calendar;
        private Instant generatedAt;

        public Builder origin(String origin) {
            this.origin = origin;
            return this;
        }

        public Builder destination(String destination) {
            this.destination = destination;
            return this;
        }

        public Builder month(String month) {
            this.month = month;
            return this;
        }

        public Builder currency(String currency) {
            this.currency = currency;
            return this;
        }

        public Builder calendar(List<DayPrice> calendar) {
            this.calendar = calendar;
            return this;
        }

        public Builder generatedAt(Instant generatedAt) {
            this.generatedAt = generatedAt;
            return this;
        }

        public CalendarResponse build() {
            return new CalendarResponse(origin, destination, month, currency, calendar, generatedAt);
        }
    }
}
