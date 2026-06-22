package com.simulated.lowfarecalendar.provider;

import com.simulated.lowfarecalendar.config.LfcProperties;
import com.simulated.lowfarecalendar.model.FareQuote;
import com.simulated.lowfarecalendar.model.FlightQuery;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

@Component
public class ProviderA implements FlightProvider {

    private final LfcProperties.ProviderSimProperties config;

    public ProviderA(LfcProperties props) {
        this.config = props.getProviders().getProviderA();
    }

    @Override
    public String getId() {
        return "providerA";
    }

    @Override
    public boolean isEnabled() {
        return config.isEnabled();
    }

    @Override
    public Optional<FareQuote> getFares(FlightQuery query) {
        if (!config.isEnabled()) {
            return Optional.empty();
        }
        long latency = ThreadLocalRandom.current()
                .nextLong(config.getLatencyMinMs(), config.getLatencyMaxMs() + 1);
        if (latency > 0) {
            try {
                Thread.sleep(latency);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("ProviderA interrupted", e);
            }
        }
        if (ThreadLocalRandom.current().nextDouble() < config.getErrorRate()) {
            throw new RuntimeException("providerA simulated failure");
        }
        double rawPrice = ThreadLocalRandom.current().nextDouble(
                config.getPriceMinUsd().doubleValue(),
                config.getPriceMaxUsd().doubleValue() + 0.001);
        BigDecimal price = BigDecimal.valueOf(rawPrice).setScale(2, RoundingMode.HALF_UP);
        return Optional.of(new FareQuote(getId(), price, true));
    }
}
