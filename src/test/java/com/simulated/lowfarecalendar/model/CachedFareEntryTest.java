package com.simulated.lowfarecalendar.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CachedFareEntryTest {

    @Test
    @DisplayName("builder defaults: currency=USD, winningProvider=null when not set")
    void builderDefaults_currencyAndWinningProvider() {
        CachedFareEntry entry = CachedFareEntry.builder()
                .origin("KUL")
                .destination("SIN")
                .date("2024-07-15")
                .lowestPrice(new BigDecimal("199.00"))
                .updatedAt(Instant.parse("2024-07-15T10:25:00Z"))
                .providerCount(3)
                .respondingProviders(List.of("providerA", "providerC"))
                .winningProvider("providerC")
                .build();

        assertThat(entry.getCurrency())
                .as("currency should default to USD when not explicitly set")
                .isEqualTo("USD");

        assertThat(entry.getWinningProvider())
                .as("winningProvider should be the provider whose price was selected")
                .isEqualTo("providerC");
    }

    @Test
    @DisplayName("builder: winningProvider is preserved")
    void builderWinningProvider_preserved() {
        CachedFareEntry entry = CachedFareEntry.builder()
                .origin("KUL")
                .destination("SIN")
                .date("2024-07-15")
                .lowestPrice(new BigDecimal("120.00"))
                .updatedAt(Instant.parse("2024-07-15T10:25:00Z"))
                .providerCount(3)
                .respondingProviders(List.of("providerA", "providerB", "providerC"))
                .winningProvider("providerC")
                .build();

        assertThat(entry.getWinningProvider())
                .as("winningProvider should identify which provider had the lowest price")
                .isEqualTo("providerC");
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
