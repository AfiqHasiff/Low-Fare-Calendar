package com.simulated.lowfarecalendar.currency;

import com.simulated.lowfarecalendar.config.LfcProperties;
import com.simulated.lowfarecalendar.exception.UnsupportedCurrencyException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CurrencyConverterRegistryTest {

    private CurrencyConverterRegistry registry;

    @BeforeEach
    void setUp() {
        // Build LfcProperties with explicit rates matching spec defaults
        LfcProperties properties = new LfcProperties();
        LfcProperties.CurrencyProperties currency = new LfcProperties.CurrencyProperties();
        LfcProperties.CurrencyProperties.RatesProperties rates =
                new LfcProperties.CurrencyProperties.RatesProperties();
        rates.setUsdMyr(new BigDecimal("4.47"));
        rates.setUsdThb(new BigDecimal("33.50"));
        rates.setUsdUsd(BigDecimal.ONE);
        currency.setRates(rates);
        properties.setCurrency(currency);

        List<CurrencyConverter> converters = List.of(
                new UsdToMyrConverter(properties),
                new UsdToThbConverter(properties),
                new UsdToUsdConverter(properties)
        );
        registry = new CurrencyConverterRegistry(converters);
    }

    @Test
    @DisplayName("USD -> MYR: 100 USD converts to 447.00 MYR at rate 4.47")
    void usdToMyr_100usd_returns447() {
        CurrencyConverter converter = registry.get("USD", "MYR");
        BigDecimal result = converter.convert(new BigDecimal("100"), "USD", "MYR");

        assertThat(result)
                .as("100 USD at rate 4.47 should equal 447.00 MYR")
                .isEqualByComparingTo(new BigDecimal("447.00"));
    }

    @Test
    @DisplayName("USD -> THB: 100 USD converts to 3350.00 THB at rate 33.50")
    void usdToThb_100usd_returns3350() {
        CurrencyConverter converter = registry.get("USD", "THB");
        BigDecimal result = converter.convert(new BigDecimal("100"), "USD", "THB");

        assertThat(result)
                .as("100 USD at rate 33.50 should equal 3350.00 THB")
                .isEqualByComparingTo(new BigDecimal("3350.00"));
    }

    @Test
    @DisplayName("USD -> USD: 100 USD identity converts to 100.00 USD")
    void usdToUsd_100usd_returns100() {
        CurrencyConverter converter = registry.get("USD", "USD");
        BigDecimal result = converter.convert(new BigDecimal("100"), "USD", "USD");

        assertThat(result)
                .as("100 USD identity conversion should return 100.00 USD")
                .isEqualByComparingTo(new BigDecimal("100.00"));
    }

    @Test
    @DisplayName("USD -> SGD: throws UnsupportedCurrencyException (not registered)")
    void usdToSgd_throwsUnsupportedCurrencyException() {
        assertThatThrownBy(() -> registry.get("USD", "SGD"))
                .isInstanceOf(UnsupportedCurrencyException.class)
                .hasMessageContaining("USD")
                .hasMessageContaining("SGD");
    }
}
