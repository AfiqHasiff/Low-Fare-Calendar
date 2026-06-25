package com.simulated.lowfarecalendar.service;

import com.simulated.lowfarecalendar.model.CachedFareEntry;
import com.simulated.lowfarecalendar.model.FareQuote;
import com.simulated.lowfarecalendar.model.FlightQuery;
import com.simulated.lowfarecalendar.observability.CacheMetricsService;
import com.simulated.lowfarecalendar.provider.FlightProvider;
import com.simulated.lowfarecalendar.trace.RequestTrace;
import com.simulated.lowfarecalendar.trace.RequestTraceContext;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.vavr.control.Try;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Scatter-gather aggregation across all enabled FlightProviders.
 *
 * Each provider is called concurrently via virtual threads. Circuit breakers
 * protect against slow/failing providers. The lowest price across all
 * successful quotes is selected and returned as a CachedFareEntry.
 *
 * Returns Optional.empty() when every provider fails or the open circuit
 * breaker rejects all calls.
 */
@Service
public class ProviderAggregationService {

    private final List<FlightProvider> providers;
    private final CircuitBreakerRegistry cbRegistry;
    private final Executor executor;
    private final CacheMetricsService metrics;
    private final RequestTraceContext traceContext;

    public ProviderAggregationService(
            List<FlightProvider> providers,
            CircuitBreakerRegistry cbRegistry,
            @Qualifier("virtualThreadExecutor") Executor executor,
            CacheMetricsService metrics,
            RequestTraceContext traceContext) {
        this.providers = providers;
        this.cbRegistry = cbRegistry;
        this.executor = executor;
        this.metrics = metrics;
        this.traceContext = traceContext;
    }

    /**
     * Fan out to all enabled providers in parallel, collect quotes, and return
     * a CachedFareEntry carrying the minimum price. Returns Optional.empty()
     * if no provider returns a usable quote.
     */
    public Optional<CachedFareEntry> aggregate(FlightQuery query) {
        // Capture trace reference on the calling thread (ThreadLocal is request-scoped)
        RequestTrace trace = traceContext.current();

        List<FlightProvider> enabled = providers.stream()
                .filter(FlightProvider::isEnabled)
                .toList();

        List<CompletableFuture<Optional<FareQuote>>> futures = enabled.stream()
                .map(provider -> CompletableFuture.supplyAsync(
                        () -> fetchWithCircuitBreaker(provider, query, trace),
                        executor))
                .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        List<FareQuote> quotes = futures.stream()
                .map(f -> f.exceptionally(ex -> Optional.empty()).join())
                .filter(Optional::isPresent)
                .map(Optional::get)
                .toList();

        if (quotes.isEmpty()) {
            return Optional.empty();
        }

        FareQuote winner = quotes.stream()
                .min((a, b) -> a.price().compareTo(b.price()))
                .orElseThrow();

        List<String> respondingProviders = quotes.stream()
                .map(FareQuote::providerId)
                .toList();

        return Optional.of(CachedFareEntry.builder()
                .origin(query.origin())
                .destination(query.destination())
                .date(query.date().toString())
                .lowestPrice(winner.price())
                .currency("USD")
                .updatedAt(Instant.now())
                .respondingProviders(respondingProviders)
                .providerCount(respondingProviders.size())
                .winningProvider(winner.providerId())
                .build());
    }

    /**
     * Call a single provider wrapped in its circuit breaker.
     * Records metrics and trace data for every outcome.
     * trace may be null if tracing is not active for this request.
     */
    private Optional<FareQuote> fetchWithCircuitBreaker(FlightProvider provider,
                                                         FlightQuery query,
                                                         RequestTrace trace) {
        long start = System.currentTimeMillis();
        CircuitBreaker cb = cbRegistry.circuitBreaker(provider.getId());
        return Try.ofSupplier(CircuitBreaker.decorateSupplier(cb, () -> provider.getFares(query)))
                .recover(CallNotPermittedException.class, ex -> {
                    long elapsed = System.currentTimeMillis() - start;
                    metrics.recordCircuitBreakerState(provider.getId(), 1);
                    if (trace != null) {
                        trace.getProviderFailures().add(
                                new RequestTrace.ProviderResult(provider.getId(), null, elapsed, false, "CIRCUIT_OPEN"));
                    }
                    return Optional.empty();
                })
                .recover(Exception.class, ex -> {
                    long elapsed = System.currentTimeMillis() - start;
                    metrics.recordProviderError(provider.getId());
                    if (trace != null) {
                        trace.getProviderFailures().add(
                                new RequestTrace.ProviderResult(provider.getId(), null, elapsed, false, "EXCEPTION"));
                    }
                    return Optional.empty();
                })
                .andThen(result -> {
                    long elapsed = System.currentTimeMillis() - start;
                    metrics.recordProviderCall(provider.getId(), elapsed, result.isPresent());
                    if (trace != null && result.isPresent()) {
                        FareQuote quote = result.get();
                        trace.getProviderResults()
                                .computeIfAbsent(query.date().toString(), k -> new ArrayList<>())
                                .add(new RequestTrace.ProviderResult(
                                        provider.getId(), quote.price(), elapsed, true, null));
                    }
                })
                .get();
    }
}
