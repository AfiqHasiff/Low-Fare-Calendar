package com.simulated.lowfarecalendar.scheduler;

import com.simulated.lowfarecalendar.cache.HotRouteTracker;
import com.simulated.lowfarecalendar.config.LfcProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically prunes the hot-routes ZSET by removing members whose score
 * has dropped below the configured minimum. Prevents unbounded ZSET growth.
 */
@Component
public class HotRouteDecayScheduler {

    private static final Logger log = LoggerFactory.getLogger(HotRouteDecayScheduler.class);

    private final HotRouteTracker hotRouteTracker;
    private final LfcProperties lfcProperties;

    public HotRouteDecayScheduler(HotRouteTracker hotRouteTracker,
                                  LfcProperties lfcProperties) {
        this.hotRouteTracker = hotRouteTracker;
        this.lfcProperties = lfcProperties;
    }

    @Scheduled(fixedDelayString = "${lfc.hot-routes.decay-interval-seconds}000")
    public void decay() {
        double decayMinScore = lfcProperties.getHotRoutes().getDecayMinScore();
        log.debug("Running hot-route decay, pruning below score={}", decayMinScore);
        hotRouteTracker.pruneBelow(decayMinScore);
    }
}
