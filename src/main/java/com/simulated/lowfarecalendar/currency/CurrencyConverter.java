package com.simulated.lowfarecalendar.currency;

import com.simulated.lowfarecalendar.model.CurrencyPair;

import java.math.BigDecimal;
import java.util.Set;

/**
 * Strategy interface for currency conversion.
 *
 * Open/Closed Principle: adding a new currency requires only a new
 * @Component implementation of this interface. No existing classes change.
 *
 * supportedPairs() declares the directed pairs this converter handles.
 * CurrencyConverterRegistry auto-discovers all implementations at startup
 * via Spring's List<CurrencyConverter> injection and builds a lookup map.
 *
 * All implementations must:
 *   - Use BigDecimal arithmetic with HALF_UP rounding, scale 2
 *   - Obtain their exchange rate from LfcProperties (NOT @Value)
 *   - Be annotated @Component so Spring registers them
 */
public interface CurrencyConverter {

    /**
     * Converts amount from fromCurrency to toCurrency.
     *
     * @param amount       source amount (non-null, non-negative)
     * @param fromCurrency ISO-4217 source currency code (e.g. "USD")
     * @param toCurrency   ISO-4217 target currency code (e.g. "MYR")
     * @return converted amount, scale=2, HALF_UP rounding
     */
    BigDecimal convert(BigDecimal amount, String fromCurrency, String toCurrency);

    /**
     * Returns the set of CurrencyPairs this converter supports.
     * Used by CurrencyConverterRegistry to build the lookup map.
     */
    Set<CurrencyPair> supportedPairs();
}
