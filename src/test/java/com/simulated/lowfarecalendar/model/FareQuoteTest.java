package com.simulated.lowfarecalendar.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class FareQuoteTest {

    @Test
    @DisplayName("FareQuote is immutable record with providerId, price, available")
    void fareQuoteRecordStructure() {
        BigDecimal price = new BigDecimal("299.50");
        FareQuote quote = new FareQuote("PROVIDER_A", price, true);

        assertThat(quote.providerId()).isEqualTo("PROVIDER_A");
        assertThat(quote.price()).isEqualTo(price);
        assertThat(quote.available()).isTrue();
    }

    @Test
    @DisplayName("FareQuote with available=false represents no bookable inventory")
    void fareQuoteUnavailable() {
        BigDecimal price = BigDecimal.ZERO;
        FareQuote quote = new FareQuote("PROVIDER_B", price, false);

        assertThat(quote.available()).isFalse();
    }

    @Test
    @DisplayName("FareQuote records are equal when fields match")
    void fareQuoteEquality() {
        BigDecimal price = new BigDecimal("299.50");
        FareQuote quote1 = new FareQuote("PROVIDER_A", price, true);
        FareQuote quote2 = new FareQuote("PROVIDER_A", price, true);

        assertThat(quote1).isEqualTo(quote2);
    }

    @Test
    @DisplayName("FareQuote records are not equal when fields differ")
    void fareQuoteInequality() {
        BigDecimal price = new BigDecimal("299.50");
        FareQuote quote1 = new FareQuote("PROVIDER_A", price, true);
        FareQuote quote2 = new FareQuote("PROVIDER_A", price, false);

        assertThat(quote1).isNotEqualTo(quote2);
    }
}
