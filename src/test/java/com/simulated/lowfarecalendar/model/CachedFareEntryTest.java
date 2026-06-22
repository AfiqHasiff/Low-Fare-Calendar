package com.simulated.lowfarecalendar.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CachedFareEntryTest {

    @Test
    @DisplayName("builder defaults: stale=false, currency=USD")
    void builderDefaults_staleAndCurrency() {
        CachedFareEntry entry = CachedFareEntry.builder()
                .origin("KUL")
                .destination("SIN")
                .date("2024-07-15")
                .lowestPrice(new BigDecimal("199.00"))
                .updatedAt(Instant.parse("2024-07-15T10:25:00Z"))
                .providerCount(3)
                .respondingProviders(List.of("PROVIDER_A", "PROVIDER_C"))
                .build();

        assertThat(entry.isStale())
                .as("stale should default to false when not explicitly set")
                .isFalse();

        assertThat(entry.getCurrency())
                .as("currency should default to USD when not explicitly set")
                .isEqualTo("USD");
    }

    @Test
    @DisplayName("builder: explicit stale=true is preserved")
    void builderExplicitStale_preserved() {
        CachedFareEntry entry = CachedFareEntry.builder()
                .origin("KUL")
                .destination("SIN")
                .date("2024-07-15")
                .lowestPrice(new BigDecimal("199.00"))
                .updatedAt(Instant.parse("2024-07-15T10:25:00Z"))
                .providerCount(0)
                .respondingProviders(List.of())
                .stale(true)
                .build();

        assertThat(entry.isStale())
                .as("stale=true explicitly set should be preserved")
                .isTrue();
    }

    @Test
    @DisplayName("builder: explicit currency override is preserved")
    void builderExplicitCurrency_preserved() {
        CachedFareEntry entry = CachedFareEntry.builder()
                .origin("KUL")
                .destination("SIN")
                .date("2024-07-15")
                .lowestPrice(new BigDecimal("890.09"))
                .updatedAt(Instant.now())
                .providerCount(2)
                .respondingProviders(List.of("PROVIDER_B"))
                .currency("MYR")
                .build();

        assertThat(entry.getCurrency())
                .as("explicit currency override should not be replaced by default")
                .isEqualTo("MYR");
    }
}
