package com.simulated.lowfarecalendar.currency;

import com.simulated.lowfarecalendar.config.LfcProperties;
import com.simulated.lowfarecalendar.model.CurrencyPair;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;

/**
 * Identity converter: USD to USD (no-op conversion).
 * Returns the input amount with scale=2 HALF_UP rounding for consistency.
 * Rate sourced from lfc.currency.rates.usd-usd (always 1.00 by convention).
 * No @Value usage — rate is obtained via constructor-injected LfcProperties.
 *
 * Rationale for existence: callers always go through CurrencyConverterRegistry.get()
 * regardless of whether a conversion is needed. Having a registered USD->USD converter
 * means no special-casing in the CalendarService for the "no conversion" path.
 */
@Component
public class UsdToUsdConverter implements CurrencyConverter {

    private final BigDecimal rate;

    public UsdToUsdConverter(LfcProperties properties) {
        this.rate = properties.getCurrency().getRates().getUsdUsd();
    }

    @Override
    public BigDecimal convert(BigDecimal amount, String fromCurrency, String toCurrency) {
        return amount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }

    @Override
    public Set<CurrencyPair> supportedPairs() {
        return Set.of(new CurrencyPair("USD", "USD"));
    }
}
