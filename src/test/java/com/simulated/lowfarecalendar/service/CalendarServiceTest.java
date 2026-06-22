package com.simulated.lowfarecalendar.service;

import com.simulated.lowfarecalendar.cache.CacheLockService;
import com.simulated.lowfarecalendar.cache.FareCacheService;
import com.simulated.lowfarecalendar.cache.HotRouteTracker;
import com.simulated.lowfarecalendar.cache.InProcessSingleflight;
import com.simulated.lowfarecalendar.config.LfcProperties;
import com.simulated.lowfarecalendar.currency.CurrencyConverter;
import com.simulated.lowfarecalendar.currency.CurrencyConverterRegistry;
import com.simulated.lowfarecalendar.model.CalendarResponse;
import com.simulated.lowfarecalendar.model.CachedFareEntry;
import com.simulated.lowfarecalendar.model.CurrencyPair;
import com.simulated.lowfarecalendar.model.DayPrice;
import com.simulated.lowfarecalendar.model.FlightQuery;
import com.simulated.lowfarecalendar.observability.CacheMetricsService;
import com.simulated.lowfarecalendar.trace.RequestTraceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CalendarServiceTest {

    @Mock private FareCacheService fareCacheService;
    @Mock private InProcessSingleflight singleflight;
    @Mock private CacheLockService cacheLockService;
    @Mock private ProviderAggregationService providerAggregationService;
    @Mock private CurrencyConverterRegistry currencyConverterRegistry;
    @Mock private HotRouteTracker hotRouteTracker;
    @Mock private CacheMetricsService cacheMetricsService;
    @Mock private Executor virtualThreadExecutor;
    @Mock private LfcProperties lfcProperties;
    @Mock private RequestTraceContext traceContext;
    @Mock private CurrencyConverter myrConverter;

    @InjectMocks
    private CalendarService calendarService;

    private static final String ORIGIN = "KUL";
    private static final String DEST = "SIN";
    private static final YearMonth MONTH = YearMonth.of(2024, 7); // 31 days
    private static final String CURRENCY = "MYR";
    private static final BigDecimal USD_PRICE = new BigDecimal("100.00");
    // 100 USD * 4.47 = 447.00 MYR
    private static final BigDecimal MYR_PRICE = new BigDecimal("447.00");

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(currencyConverterRegistry.get("USD", "MYR")).thenReturn(myrConverter);
        org.mockito.Mockito.lenient().when(myrConverter.supportedPairs())
                .thenReturn(Set.of(new CurrencyPair("USD", "MYR")));
        org.mockito.Mockito.lenient().when(myrConverter.convert(any(BigDecimal.class), eq("USD"), eq("MYR")))
                .thenAnswer(inv -> {
                    BigDecimal amount = inv.getArgument(0);
                    return amount.multiply(new BigDecimal("4.47"))
                            .setScale(2, RoundingMode.HALF_UP);
                });

        // virtualThreadExecutor runs tasks inline so hot-route tracking fires synchronously.
        org.mockito.Mockito.doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(virtualThreadExecutor).execute(any(Runnable.class));
    }

    private CachedFareEntry freshEntry() {
        return CachedFareEntry.builder()
                .origin(ORIGIN)
                .destination(DEST)
                .date(LocalDate.of(2024, 7, 1).toString())
                .lowestPrice(USD_PRICE)
                .currency("USD")
                .updatedAt(Instant.parse("2024-07-15T10:00:00Z"))
                .stale(false)
                .build();
    }

    /**
     * Test 1: All 31 days cached — no aggregation called, all DayPrices
     * in MYR (100 USD * 4.47 = 447.00).
     */
    @Test
    void allDaysCached_noAggregation_allDayPricesMyr() {
        List<Optional<CachedFareEntry>> allPresent =
                Collections.nCopies(31, Optional.of(freshEntry()));
        when(fareCacheService.pipelineGet(ORIGIN, DEST, MONTH)).thenReturn(allPresent);

        CalendarResponse response = calendarService.getCalendar(ORIGIN, DEST, MONTH, CURRENCY);

        verify(providerAggregationService, never()).aggregate(any());
        assertThat(response.calendar()).hasSize(31);
        assertThat(response.calendar())
                .allSatisfy(dp -> {
                    assertThat(dp.available()).isTrue();
                    assertThat(dp.lowestPrice()).isEqualByComparingTo(MYR_PRICE);
                    assertThat(dp.stale()).isFalse();
                });
    }

    /**
     * Test 2: 30 hits + 1 miss — aggregation called exactly once for the missing date.
     */
    @Test
    void thirtyHitsOneMiss_aggregationCalledOnce() {
        CachedFareEntry fresh = freshEntry();
        List<Optional<CachedFareEntry>> mixed = new java.util.ArrayList<>(
                Collections.nCopies(30, Optional.of(fresh)));
        mixed.add(Optional.empty()); // index 30 = July 31 is a miss

        when(fareCacheService.pipelineGet(ORIGIN, DEST, MONTH)).thenReturn(mixed);

        // Singleflight delegates to the fetcher inline for the missing date.
        when(singleflight.getOrFetch(anyString(), any()))
                .thenAnswer(inv -> {
                    java.util.function.Supplier<CachedFareEntry> fetcher = inv.getArgument(1);
                    return fetcher.get();
                });
        when(cacheLockService.tryAcquire(anyString(), anyString(), any(LocalDate.class)))
                .thenReturn(Optional.of("test-token"));
        when(providerAggregationService.aggregate(any())).thenReturn(Optional.of(fresh));

        CalendarResponse response = calendarService.getCalendar(ORIGIN, DEST, MONTH, CURRENCY);

        verify(providerAggregationService, times(1)).aggregate(any(FlightQuery.class));
        assertThat(response.calendar()).hasSize(31);
        assertThat(response.calendar().get(30).available()).isTrue();
    }

    /**
     * Test 3: Aggregation returns empty + fallback present → stale=true, available=true.
     */
    @Test
    void aggregationEmpty_fallbackPresent_staleTrue_availableTrue() {
        List<Optional<CachedFareEntry>> allMissing =
                Collections.nCopies(31, Optional.empty());
        when(fareCacheService.pipelineGet(ORIGIN, DEST, MONTH)).thenReturn(allMissing);

        CachedFareEntry freshEntry = freshEntry();

        when(singleflight.getOrFetch(anyString(), any()))
                .thenAnswer(inv -> {
                    java.util.function.Supplier<CachedFareEntry> fetcher = inv.getArgument(1);
                    return fetcher.get();
                });
        when(cacheLockService.tryAcquire(anyString(), anyString(), any(LocalDate.class)))
                .thenReturn(Optional.of("test-token"));
        when(providerAggregationService.aggregate(any())).thenReturn(Optional.empty());
        when(fareCacheService.getFallback(anyString(), anyString(), any(LocalDate.class)))
                .thenReturn(Optional.of(freshEntry));

        CalendarResponse response = calendarService.getCalendar(ORIGIN, DEST, MONTH, CURRENCY);

        assertThat(response.calendar())
                .allSatisfy(dp -> {
                    assertThat(dp.available()).isTrue();
                    assertThat(dp.stale()).isTrue();
                });
    }

    /**
     * Test 4: Aggregation returns empty + fallback empty → available=false for every day.
     */
    @Test
    void aggregationEmpty_fallbackEmpty_availableFalse() {
        List<Optional<CachedFareEntry>> allMissing =
                Collections.nCopies(31, Optional.empty());
        when(fareCacheService.pipelineGet(ORIGIN, DEST, MONTH)).thenReturn(allMissing);

        when(singleflight.getOrFetch(anyString(), any()))
                .thenAnswer(inv -> {
                    java.util.function.Supplier<CachedFareEntry> fetcher = inv.getArgument(1);
                    return fetcher.get();
                });
        when(cacheLockService.tryAcquire(anyString(), anyString(), any(LocalDate.class)))
                .thenReturn(Optional.of("test-token"));
        when(providerAggregationService.aggregate(any())).thenReturn(Optional.empty());
        when(fareCacheService.getFallback(anyString(), anyString(), any(LocalDate.class)))
                .thenReturn(Optional.empty());

        CalendarResponse response = calendarService.getCalendar(ORIGIN, DEST, MONTH, CURRENCY);

        assertThat(response.calendar())
                .allSatisfy(dp -> {
                    assertThat(dp.available()).isFalse();
                    assertThat(dp.lowestPrice()).isNull();
                });
    }

    /**
     * Test 5: hotRouteTracker.increment is called exactly once per getCalendar
     * invocation regardless of how many days are in the month.
     */
    @Test
    void hotRouteTrackerIncrementCalledOnce() {
        List<Optional<CachedFareEntry>> allPresent =
                Collections.nCopies(31, Optional.of(freshEntry()));
        when(fareCacheService.pipelineGet(ORIGIN, DEST, MONTH)).thenReturn(allPresent);

        calendarService.getCalendar(ORIGIN, DEST, MONTH, CURRENCY);

        verify(hotRouteTracker, times(1)).increment(anyString(), anyString(), any(YearMonth.class));
    }
}
