package com.simulated.lowfarecalendar.provider;

import com.simulated.lowfarecalendar.config.LfcProperties;
import com.simulated.lowfarecalendar.model.FareQuote;
import com.simulated.lowfarecalendar.model.FlightQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderATest {

    private ProviderA providerA;

    @BeforeEach
    void setUp() {
        LfcProperties props = new LfcProperties();
        LfcProperties.ProvidersProperties providers = new LfcProperties.ProvidersProperties();
        LfcProperties.ProviderSimProperties simProps = new LfcProperties.ProviderSimProperties();
        simProps.setPriceMinUsd(new BigDecimal("150.00"));
        simProps.setPriceMaxUsd(new BigDecimal("150.00"));
        simProps.setLatencyMinMs(0);
        simProps.setLatencyMaxMs(0);
        simProps.setErrorRate(0.0);
        simProps.setEnabled(true);
        providers.setProviderA(simProps);
        props.setProviders(providers);
        providerA = new ProviderA(props);
    }

    @Test
    void getFares_whenEnabled_returnsQuoteWithCorrectPrice() {
        FlightQuery query = new FlightQuery("KUL", "SIN", LocalDate.of(2024, 7, 15));

        Optional<FareQuote> result = providerA.getFares(query);

        assertThat(result).isPresent();
        assertThat(result.get().providerId()).isEqualTo("providerA");
        assertThat(result.get().price()).isEqualByComparingTo(new BigDecimal("150.00"));
        assertThat(result.get().available()).isTrue();
    }

    @Test
    void getFares_whenDisabled_returnsEmpty() {
        LfcProperties props = new LfcProperties();
        LfcProperties.ProvidersProperties providers = new LfcProperties.ProvidersProperties();
        LfcProperties.ProviderSimProperties simProps = new LfcProperties.ProviderSimProperties();
        simProps.setPriceMinUsd(new BigDecimal("150.00"));
        simProps.setPriceMaxUsd(new BigDecimal("150.00"));
        simProps.setLatencyMinMs(0);
        simProps.setLatencyMaxMs(0);
        simProps.setErrorRate(0.0);
        simProps.setEnabled(false);
        providers.setProviderA(simProps);
        props.setProviders(providers);
        ProviderA disabledProvider = new ProviderA(props);

        FlightQuery query = new FlightQuery("KUL", "SIN", LocalDate.of(2024, 7, 15));
        Optional<FareQuote> result = disabledProvider.getFares(query);

        assertThat(result).isEmpty();
    }

    @Test
    void getFares_whenErrorRate1_throwsException() {
        LfcProperties props = new LfcProperties();
        LfcProperties.ProvidersProperties providers = new LfcProperties.ProvidersProperties();
        LfcProperties.ProviderSimProperties simProps = new LfcProperties.ProviderSimProperties();
        simProps.setPriceMinUsd(new BigDecimal("150.00"));
        simProps.setPriceMaxUsd(new BigDecimal("150.00"));
        simProps.setLatencyMinMs(0);
        simProps.setLatencyMaxMs(0);
        simProps.setErrorRate(1.0);
        simProps.setEnabled(true);
        providers.setProviderA(simProps);
        props.setProviders(providers);
        ProviderA failingProvider = new ProviderA(props);

        FlightQuery query = new FlightQuery("KUL", "SIN", LocalDate.of(2024, 7, 15));

        assertThatThrownBy(() -> failingProvider.getFares(query))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("providerA");
    }
}
