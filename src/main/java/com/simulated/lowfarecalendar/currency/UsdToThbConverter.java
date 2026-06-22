package com.simulated.lowfarecalendar.currency;

import com.simulated.lowfarecalendar.config.LfcProperties;
import com.simulated.lowfarecalendar.model.CurrencyPair;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;

/**
 * Converts USD amounts to THB.
 * Exchange rate sourced from lfc.currency.rates.usd-thb (LfcProperties).
 * No @Value usage — rate is obtained via constructor-injected LfcProperties.
 */
@Component
public class UsdToThbConverter implements CurrencyConverter {

    private final BigDecimal rate;

    public UsdToThbConverter(LfcProperties properties) {
        this.rate = properties.getCurrency().getRates().getUsdThb();
    }

    @Override
    public BigDecimal convert(BigDecimal amount, String fromCurrency, String toCurrency) {
        return amount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }

    @Override
    public Set<CurrencyPair> supportedPairs() {
        return Set.of(new CurrencyPair("USD", "THB"));
    }
}
