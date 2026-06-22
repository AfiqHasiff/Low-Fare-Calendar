package com.simulated.lowfarecalendar.model;

import java.math.BigDecimal;

/**
 * One entry in CalendarResponse.calendar.
 * lowestPrice is in the currency requested by the API caller — NOT USD.
 * Currency conversion is applied by CalendarService before constructing this record.
 * stale=true means this price was sourced from the fallback key and may be outdated.
 */
public record DayPrice(String date, BigDecimal lowestPrice, boolean available, boolean stale) {

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String date;
        private BigDecimal lowestPrice;
        private boolean available;
        private boolean stale;

        public Builder date(String date) {
            this.date = date;
            return this;
        }

        public Builder lowestPrice(BigDecimal lowestPrice) {
            this.lowestPrice = lowestPrice;
            return this;
        }

        public Builder available(boolean available) {
            this.available = available;
            return this;
        }

        public Builder stale(boolean stale) {
            this.stale = stale;
            return this;
        }

        public DayPrice build() {
            return new DayPrice(date, lowestPrice, available, stale);
        }
    }
}
