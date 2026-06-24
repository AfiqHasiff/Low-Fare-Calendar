package com.simulated.lowfarecalendar.cache;

import com.simulated.lowfarecalendar.config.LfcProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.YearMonth;
import java.util.List;

/**
 * Seeds pre-defined popular routes into the hot-routes ZSET on startup.
 *
 * Only writes routes that have no existing score — organic traffic scores
 * are never overwritten. Seeds each route for the current month and the
 * next month so the cache warmer has coverage immediately.
 */
@Component
public class HotRouteSeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(HotRouteSeedRunner.class);

    private final HotRouteTracker hotRouteTracker;
    private final LfcProperties lfcProperties;

    public HotRouteSeedRunner(HotRouteTracker hotRouteTracker, LfcProperties lfcProperties) {
        this.hotRouteTracker = hotRouteTracker;
        this.lfcProperties = lfcProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> seedRoutes = lfcProperties.getWarming().getSeedRoutes();
        if (seedRoutes.isEmpty()) {
            return;
        }

        long seedScore = lfcProperties.getHotRoutes().getHotThreshold();
        YearMonth thisMonth = YearMonth.now();
        YearMonth nextMonth = thisMonth.plusMonths(1);

        int seeded = 0;
        for (String route : seedRoutes) {
            String[] parts = route.split(":");
            if (parts.length != 2) {
                log.warn("HotRouteSeedRunner: skipping invalid seed route '{}' — expected ORIGIN:DEST format", route);
                continue;
            }
            String origin = parts[0].toUpperCase();
            String dest = parts[1].toUpperCase();

            for (YearMonth month : List.of(thisMonth, nextMonth)) {
                if (!hotRouteTracker.isHot(origin, dest, month)) {
                    hotRouteTracker.seed(origin, dest, month, seedScore);
                    log.info("HotRouteSeedRunner: seeded {}:{} {} with score={}", origin, dest, month, seedScore);
                    seeded++;
                } else {
                    log.debug("HotRouteSeedRunner: {}:{} {} already has organic score, skipping seed", origin, dest, month);
                }
            }
        }

        log.info("HotRouteSeedRunner: seeded {} route-month entries from {} configured seed routes", seeded, seedRoutes.size());
    }
}
