package com.simulated.lowfarecalendar.scheduler;

import com.simulated.lowfarecalendar.cache.FareCacheService;
import com.simulated.lowfarecalendar.cache.HotRouteTracker;
import com.simulated.lowfarecalendar.config.LfcProperties;
import com.simulated.lowfarecalendar.config.LfcProperties.CacheProperties;
import com.simulated.lowfarecalendar.config.LfcProperties.HotRoutesProperties;
import com.simulated.lowfarecalendar.config.LfcProperties.WarmingProperties;
import com.simulated.lowfarecalendar.model.CachedFareEntry;
import com.simulated.lowfarecalendar.model.FlightQuery;
import com.simulated.lowfarecalendar.service.ProviderAggregationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CacheWarmingSchedulerTest {

    @Mock private HotRouteTracker hotRouteTracker;
    @Mock private FareCacheService fareCacheService;
    @Mock private ProviderAggregationService providerAggregationService;
    @Mock private LfcProperties lfcProperties;

    @InjectMocks
    private CacheWarmingScheduler scheduler;

    // getTopK returns ["KUL:SIN:YYYY-MM"] where YYYY-MM is the current month,
    // so the date being warmed falls within the lookahead window.
    private static final String CURRENT_MONTH_TOKEN =
            "KUL:SIN:" + LocalDate.now().getYear() + "-"
                    + String.format("%02d", LocalDate.now().getMonthValue());

    @BeforeEach
    void setUp() {
        // base TTL = 600s, skipThreshold = 0.5  =>  skip if remaining > 600*0.5 = 300s
        CacheProperties cacheProps = new CacheProperties();
        cacheProps.setHotRouteTtlSeconds(600L);
        cacheProps.setWarmerSkipThresholdPct(0.5);
        cacheProps.setKeyVersion("v1");

        HotRoutesProperties hotRoutesProps = new HotRoutesProperties();
        hotRoutesProps.setTopK(50);

        WarmingProperties warmingProps = new WarmingProperties();
        warmingProps.setLookaheadDays(30);

        when(lfcProperties.getCache()).thenReturn(cacheProps);
        when(lfcProperties.getHotRoutes()).thenReturn(hotRoutesProps);
        when(lfcProperties.getWarming()).thenReturn(warmingProps);
    }

    // Test 1: getRemainingTtlSeconds returns 400 (> 600*0.5=300) -> aggregate NOT called
    @Test
    void warm_remainingTtlAboveThreshold_skipsAggregation() {
        when(hotRouteTracker.getTopK(50)).thenReturn(List.of(CURRENT_MONTH_TOKEN));
        when(fareCacheService.getRemainingTtlSeconds(anyString(), anyString(), any(LocalDate.class)))
                .thenReturn(400L);

        scheduler.warm();

        verify(providerAggregationService, never()).aggregate(any());
        verify(fareCacheService, never()).set(anyString(), anyString(), any(LocalDate.class), any());
    }

    // Test 2: getRemainingTtlSeconds returns 100 (< 300) -> aggregate called, set called
    @Test
    void warm_remainingTtlBelowThreshold_aggregatesAndSets() {
        when(hotRouteTracker.getTopK(50)).thenReturn(List.of(CURRENT_MONTH_TOKEN));
        when(fareCacheService.getRemainingTtlSeconds(anyString(), anyString(), any(LocalDate.class)))
                .thenReturn(100L);

        CachedFareEntry entry = CachedFareEntry.builder()
                .origin("KUL")
                .destination("SIN")
                .date(LocalDate.now().toString())
                .lowestPrice(new BigDecimal("199.00"))
                .currency("USD")
                .updatedAt(Instant.now())
                .stale(false)
                .build();

        when(providerAggregationService.aggregate(any(FlightQuery.class)))
                .thenReturn(Optional.of(entry));

        scheduler.warm();

        verify(providerAggregationService, atLeast(1)).aggregate(any(FlightQuery.class));
        verify(fareCacheService, atLeast(1)).set(anyString(), anyString(), any(LocalDate.class),
                any(CachedFareEntry.class));
    }

    // Test 3: getTopK returns empty -> aggregate never called
    @Test
    void warm_noHotRoutes_aggregateNeverCalled() {
        when(hotRouteTracker.getTopK(50)).thenReturn(List.of());

        scheduler.warm();

        verify(providerAggregationService, never()).aggregate(any());
        verify(fareCacheService, never()).set(anyString(), anyString(), any(LocalDate.class), any());
    }
}
