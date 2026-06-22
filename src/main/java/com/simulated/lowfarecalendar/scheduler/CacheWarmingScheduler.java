package com.simulated.lowfarecalendar.scheduler;

import com.simulated.lowfarecalendar.cache.FareCacheService;
import com.simulated.lowfarecalendar.cache.HotRouteTracker;
import com.simulated.lowfarecalendar.config.LfcProperties;
import com.simulated.lowfarecalendar.model.CachedFareEntry;
import com.simulated.lowfarecalendar.model.FlightQuery;
import com.simulated.lowfarecalendar.service.ProviderAggregationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

/**
 * Periodically warms the fare cache for the top-K hot routes.
 *
 * Stale-while-revalidate strategy: a date's cache entry is skipped if its
 * remaining TTL is still above {@code warmerSkipThresholdPct * baseTtl},
 * meaning it is still "fresh enough". Only near-expiry entries are re-fetched.
 */
@Component
public class CacheWarmingScheduler {

    private static final Logger log = LoggerFactory.getLogger(CacheWarmingScheduler.class);

    private final HotRouteTracker hotRouteTracker;
    private final FareCacheService fareCacheService;
    private final ProviderAggregationService providerAggregationService;
    private final LfcProperties lfcProperties;

    public CacheWarmingScheduler(HotRouteTracker hotRouteTracker,
                                 FareCacheService fareCacheService,
                                 ProviderAggregationService providerAggregationService,
                                 LfcProperties lfcProperties) {
        this.hotRouteTracker = hotRouteTracker;
        this.fareCacheService = fareCacheService;
        this.providerAggregationService = providerAggregationService;
        this.lfcProperties = lfcProperties;
    }

    @Scheduled(fixedDelayString = "${lfc.warming.interval-seconds}000")
    public void warm() {
        int topK = lfcProperties.getHotRoutes().getTopK();
        int lookaheadDays = lfcProperties.getWarming().getLookaheadDays();
        long baseTtlSeconds = lfcProperties.getCache().getHotRouteTtlSeconds();
        double skipThreshold = lfcProperties.getCache().getWarmerSkipThresholdPct();

        List<String> topRoutes = hotRouteTracker.getTopK(topK);

        for (String routeToken : topRoutes) {
            // routeToken format: "ORIGIN:DEST:YYYY-MM"
            String[] parts = routeToken.split(":");
            if (parts.length != 3) {
                log.warn("Unexpected hot-route token format: {}", routeToken);
                continue;
            }
            String origin = parts[0];
            String destination = parts[1];
            YearMonth yearMonth;
            try {
                yearMonth = YearMonth.parse(parts[2]);
            } catch (Exception e) {
                log.warn("Could not parse YearMonth from token={}, skipping", routeToken);
                continue;
            }

            LocalDate startDate = LocalDate.now();
            LocalDate endDate = startDate.plusDays(lookaheadDays - 1L);

            for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
                // Only warm dates that fall within the target month
                if (!YearMonth.from(date).equals(yearMonth)) {
                    continue;
                }

                Long remainingTtl = fareCacheService.getRemainingTtlSeconds(origin, destination, date);

                if (remainingTtl != null && remainingTtl > 0
                        && remainingTtl > baseTtlSeconds * skipThreshold) {
                    log.trace("Skipping warm for {}/{}/{} remainingTtl={}s", origin, destination, date, remainingTtl);
                    continue;
                }

                final LocalDate warmDate = date;
                FlightQuery query = new FlightQuery(origin, destination, warmDate);

                Optional<CachedFareEntry> result = providerAggregationService.aggregate(query);
                result.ifPresent(entry -> {
                    log.debug("Warming cache for {}/{}/{}", origin, destination, warmDate);
                    fareCacheService.set(origin, destination, warmDate, entry);
                });
            }
        }
    }
}
