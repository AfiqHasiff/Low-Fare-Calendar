package com.simulated.lowfarecalendar.service;

import com.simulated.lowfarecalendar.model.CachedFareEntry;
import com.simulated.lowfarecalendar.model.FareQuote;
import com.simulated.lowfarecalendar.model.FlightQuery;
import com.simulated.lowfarecalendar.observability.CacheMetricsService;
import com.simulated.lowfarecalendar.provider.FlightProvider;
import com.simulated.lowfarecalendar.trace.RequestTraceContext;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProviderAggregationServiceTest {

    @Mock private FlightProvider providerA;
    @Mock private FlightProvider providerB;
    @Mock private FlightProvider providerC;
    @Mock private CacheMetricsService metrics;

    private ProviderAggregationService service;
    private final FlightQuery query = new FlightQuery("KUL", "SIN", LocalDate.of(2024, 7, 15));

    @BeforeEach
    void setUp() {
        lenient().when(providerA.getId()).thenReturn("providerA");
        lenient().when(providerB.getId()).thenReturn("providerB");
        lenient().when(providerC.getId()).thenReturn("providerC");
        lenient().when(providerA.isEnabled()).thenReturn(true);
        lenient().when(providerB.isEnabled()).thenReturn(true);
        lenient().when(providerC.isEnabled()).thenReturn(true);

        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        registry.circuitBreaker("providerA");
        registry.circuitBreaker("providerB");
        registry.circuitBreaker("providerC");

        service = new ProviderAggregationService(
                List.of(providerA, providerB, providerC),
                registry,
                Executors.newVirtualThreadPerTaskExecutor(),
                metrics,
                new RequestTraceContext());
    }

    @Test
    void aggregate_allProvidersRespond_returnsLowestPrice() {
        when(providerA.getFares(any())).thenReturn(Optional.of(new FareQuote("providerA", new BigDecimal("150.00"), true)));
        when(providerB.getFares(any())).thenReturn(Optional.of(new FareQuote("providerB", new BigDecimal("200.00"), true)));
        when(providerC.getFares(any())).thenReturn(Optional.of(new FareQuote("providerC", new BigDecimal("120.00"), true)));

        Optional<CachedFareEntry> result = service.aggregate(query);

        assertThat(result).isPresent();
        assertThat(result.get().getLowestPrice()).isEqualByComparingTo(new BigDecimal("120.00"));
        assertThat(result.get().getRespondingProviders()).containsExactlyInAnyOrder("providerA", "providerB", "providerC");
        assertThat(result.get().getProviderCount()).isEqualTo(3);
        assertThat(result.get().getCurrency()).isEqualTo("USD");
        assertThat(result.get().isStale()).isFalse();
    }

    @Test
    void aggregate_oneProviderThrows_returnsMinOfRemaining() {
        when(providerA.getFares(any())).thenThrow(new RuntimeException("providerA failure"));
        when(providerB.getFares(any())).thenReturn(Optional.of(new FareQuote("providerB", new BigDecimal("200.00"), true)));
        when(providerC.getFares(any())).thenReturn(Optional.of(new FareQuote("providerC", new BigDecimal("120.00"), true)));

        Optional<CachedFareEntry> result = service.aggregate(query);

        assertThat(result).isPresent();
        assertThat(result.get().getLowestPrice()).isEqualByComparingTo(new BigDecimal("120.00"));
        assertThat(result.get().getRespondingProviders()).containsExactlyInAnyOrder("providerB", "providerC");
        assertThat(result.get().getProviderCount()).isEqualTo(2);
    }

    @Test
    void aggregate_allProvidersFail_returnsEmpty() {
        when(providerA.getFares(any())).thenThrow(new RuntimeException("A failure"));
        when(providerB.getFares(any())).thenThrow(new RuntimeException("B failure"));
        when(providerC.getFares(any())).thenThrow(new RuntimeException("C failure"));

        Optional<CachedFareEntry> result = service.aggregate(query);

        assertThat(result).isEmpty();
    }

    @Test
    void aggregate_singleProvider_returnsThatPrice() {
        when(providerB.isEnabled()).thenReturn(false);
        when(providerC.isEnabled()).thenReturn(false);
        when(providerA.getFares(any())).thenReturn(Optional.of(new FareQuote("providerA", new BigDecimal("175.00"), true)));

        Optional<CachedFareEntry> result = service.aggregate(query);

        assertThat(result).isPresent();
        assertThat(result.get().getLowestPrice()).isEqualByComparingTo(new BigDecimal("175.00"));
        assertThat(result.get().getRespondingProviders()).containsExactly("providerA");
    }
}
