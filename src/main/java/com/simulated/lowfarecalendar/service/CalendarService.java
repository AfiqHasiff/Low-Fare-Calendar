package com.simulated.lowfarecalendar.service;

import com.simulated.lowfarecalendar.cache.CacheLockService;
import com.simulated.lowfarecalendar.cache.FareCacheService;
import com.simulated.lowfarecalendar.cache.HotRouteTracker;
import com.simulated.lowfarecalendar.cache.InProcessSingleflight;
import com.simulated.lowfarecalendar.currency.CurrencyConverter;
import com.simulated.lowfarecalendar.currency.CurrencyConverterRegistry;
import com.simulated.lowfarecalendar.model.CalendarResponse;
import com.simulated.lowfarecalendar.model.CachedFareEntry;
import com.simulated.lowfarecalendar.model.DayPrice;
import com.simulated.lowfarecalendar.model.FlightQuery;
import com.simulated.lowfarecalendar.observability.CacheMetricsService;
import com.simulated.lowfarecalendar.trace.RequestTrace;
import com.simulated.lowfarecalendar.trace.RequestTraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

@Service
public class CalendarService {

    // Carries a CachedFareEntry alongside whether it came from the fallback key.
    private record ResolvedEntry(CachedFareEntry entry, boolean stale) {
    }

    private static final Logger log = LoggerFactory.getLogger(CalendarService.class);

    private final FareCacheService fareCacheService;
    private final InProcessSingleflight singleflight;
    private final CacheLockService cacheLockService;
    private final ProviderAggregationService providerAggregationService;
    private final CurrencyConverterRegistry currencyConverterRegistry;
    private final HotRouteTracker hotRouteTracker;
    private final CacheMetricsService cacheMetricsService;
    private final Executor virtualThreadExecutor;
    private final RequestTraceContext traceContext;

    public CalendarService(
            FareCacheService fareCacheService,
            InProcessSingleflight singleflight,
            CacheLockService cacheLockService,
            ProviderAggregationService providerAggregationService,
            CurrencyConverterRegistry currencyConverterRegistry,
            HotRouteTracker hotRouteTracker,
            CacheMetricsService cacheMetricsService,
            @Qualifier("virtualThreadExecutor") Executor virtualThreadExecutor,
            RequestTraceContext traceContext) {
        this.fareCacheService = fareCacheService;
        this.singleflight = singleflight;
        this.cacheLockService = cacheLockService;
        this.providerAggregationService = providerAggregationService;
        this.currencyConverterRegistry = currencyConverterRegistry;
        this.hotRouteTracker = hotRouteTracker;
        this.cacheMetricsService = cacheMetricsService;
        this.virtualThreadExecutor = virtualThreadExecutor;
        this.traceContext = traceContext;
    }

    /**
     * Return the low-fare calendar for a route and month.
     *
     * Steps:
     * 1. Increment HotRouteTracker for this route+month (fire-and-forget).
     * 2. Pipeline-read all dates from Redis in one round trip via pipelineGet.
     * 3. For each date that is absent from the cache, call resolveMissForDate().
     * 4. Convert each entry to a DayPrice in the requested currency.
     * 5. Return a CalendarResponse.
     */
    public CalendarResponse getCalendar(String origin, String dest,
            YearMonth month, String currency) {
        long requestStart = System.currentTimeMillis();

        // Fire-and-forget hot-route tracking so it does not add to response latency.
        virtualThreadExecutor.execute(() -> hotRouteTracker.increment(origin, dest, month));

        // Pipeline-read all dates for the month in a single Redis round-trip.
        List<Optional<CachedFareEntry>> cached = fareCacheService.pipelineGet(origin, dest, month);

        int daysInMonth = month.lengthOfMonth();
        List<Optional<ResolvedEntry>> resolved = new ArrayList<>(daysInMonth);

        for (int i = 0; i < daysInMonth; i++) {
            LocalDate date = month.atDay(i + 1);
            Optional<CachedFareEntry> entry = cached.get(i);

            if (entry.isPresent()) {
                cacheMetricsService.recordCacheHit(origin, dest);
                RequestTrace hitTrace = traceContext.current();
                if (hitTrace != null)
                    hitTrace.getCacheHitDates().add(date.toString());
                resolved.add(Optional.of(new ResolvedEntry(entry.get(), false)));
            } else {
                cacheMetricsService.recordCacheMiss(origin, dest);
                RequestTrace missTrace = traceContext.current();
                if (missTrace != null)
                    missTrace.getCacheMissDates().add(date.toString());
                resolved.add(resolveMissForDate(origin, dest, date));
            }
        }

        CurrencyConverter converter = currencyConverterRegistry.get("USD", currency);

        List<DayPrice> dayPrices = new ArrayList<>(daysInMonth);
        for (int i = 0; i < daysInMonth; i++) {
            dayPrices.add(toDayPrice(month.atDay(i + 1), resolved.get(i), converter));
        }

        CalendarResponse response = new CalendarResponse(
                origin.toUpperCase(),
                dest.toUpperCase(),
                month.toString(),
                currency,
                dayPrices,
                Instant.now());

        recordRequestMetrics(origin, dest, currency, resolved, dayPrices, requestStart);
        updateTraceWithResponseData(origin, dest, currency, dayPrices);

        return response;
    }

    /**
     * Resolve a cache miss for a single date.
     *
     * Uses InProcessSingleflight so that concurrent requests within the same JVM
     * for the same key coalesce into one fetch attempt. Inside the singleflight
     * boundary we attempt the distributed SETNX lock:
     * - Lock acquired: aggregate from providers, write to cache, release lock.
     * - Lock not acquired: another instance is fetching; poll Redis until result
     * appears,
     * then re-read from cache.
     * On total provider failure the fallback key is read and flagged stale=true in
     * ResolvedEntry.
     * stale is never stored in Redis — it is a read-time signal only.
     */
    private Optional<ResolvedEntry> resolveMissForDate(String origin, String dest,
            LocalDate date) {
        String singleflightKey = origin.toUpperCase() + ":" + dest.toUpperCase() + ":" + date;
        try {
            CachedFareEntry entry = singleflight.getOrFetch(singleflightKey, () -> {
                Optional<String> lockToken = cacheLockService.tryAcquire(origin, dest, date);

                if (lockToken.isPresent()) {
                    try {
                        FlightQuery query = new FlightQuery(
                                origin.toUpperCase(),
                                dest.toUpperCase(),
                                date);
                        Optional<CachedFareEntry> aggregated = providerAggregationService.aggregate(query);

                        if (aggregated.isPresent()) {
                            fareCacheService.set(origin, dest, date, aggregated.get());
                            return aggregated.get();
                        } else {
                            // All providers failed — null signals the fallback path below.
                            return null;
                        }
                    } finally {
                        cacheLockService.release(origin, dest, date, lockToken.get());
                    }
                } else {
                    // Another instance holds the lock — poll until result is written.
                    boolean appeared = cacheLockService.pollForResult(origin, dest, date);
                    if (appeared) {
                        return fareCacheService.get(origin, dest, date).orElse(null);
                    }
                    return null;
                }
            });

            if (entry != null) {
                return Optional.of(new ResolvedEntry(entry, false));
            }
            // null from singleflight means providers failed — try the 24h fallback key.
            return fareCacheService.getFallback(origin, dest, date)
                    .map(fb -> new ResolvedEntry(fb, true));
        } catch (Exception e) {
            log.warn("resolveMissForDate failed for {}-{}:{}: {}",
                    origin, dest, date, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Convert a ResolvedEntry (USD) to a DayPrice in the requested currency.
     * Empty entry -> available=false, lowestPrice=null, stale=false.
     * Present entry -> lowestPrice converted via the supplied CurrencyConverter.
     * stale=true when the entry came from the 24h fallback key.
     */
    private DayPrice toDayPrice(LocalDate date,
            Optional<ResolvedEntry> resolved,
            CurrencyConverter converter) {
        if (resolved.isEmpty()) {
            return new DayPrice(date.toString(), null, false, false);
        }

        ResolvedEntry r = resolved.get();
        BigDecimal converted = converter.convert(r.entry().getLowestPrice(), "USD", converter
                .supportedPairs()
                .iterator()
                .next()
                .to());

        return new DayPrice(date.toString(), converted, true, r.stale());
    }

    private void recordRequestMetrics(String origin, String dest, String currency,
            List<Optional<ResolvedEntry>> resolved,
            List<DayPrice> dayPrices, long requestStart) {
        int staleCount = 0;
        int unavailableCount = 0;
        for (DayPrice dp : dayPrices) {
            if (!dp.available())
                unavailableCount++;
            else if (dp.stale())
                staleCount++;
        }
        for (int i = 0; i < staleCount; i++)
            cacheMetricsService.recordStaleServed(origin, dest);
        for (int i = 0; i < unavailableCount; i++)
            cacheMetricsService.recordUnavailableDate(origin, dest);

        RequestTrace trace = traceContext.current();
        if (trace != null) {
            int leaders = trace.getSummary().getSingleflightLeaderDates();
            int followers = trace.getSummary().getSingleflightFollowerDates();
            for (int i = 0; i < leaders; i++)
                cacheMetricsService.recordSingleflightLeader(origin, dest);
            for (int i = 0; i < followers; i++)
                cacheMetricsService.recordSingleflightFollower(origin, dest);
        }

        int daysInMonth = resolved.size();
        int hits = daysInMonth - (int) resolved.stream()
                .filter(r -> r.isEmpty() || r.map(ResolvedEntry::stale).orElse(false)).count();
        double hitRatio = daysInMonth > 0 ? (double) hits / daysInMonth : 0.0;
        cacheMetricsService.recordRequestCacheHitRatio(origin, dest, hitRatio);

        cacheMetricsService.recordRequestDuration(origin, dest, currency,
                System.currentTimeMillis() - requestStart);
    }

    private void updateTraceWithResponseData(String origin, String dest, String currency,
            List<DayPrice> dayPrices) {
        RequestTrace trace = traceContext.current();
        if (trace == null)
            return;
        RequestTrace.CurrencyInfo currencyInfo = trace.getCurrencyInfo();
        currencyInfo.setRequestedCurrency(currency);
        currencyInfo.setSourceCurrency("USD");
        currencyInfo.setConversionActive(!currency.equals("USD"));
    }
}
