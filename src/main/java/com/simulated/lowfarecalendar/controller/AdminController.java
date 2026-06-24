package com.simulated.lowfarecalendar.controller;

import com.simulated.lowfarecalendar.scheduler.CacheWarmingScheduler;
import com.simulated.lowfarecalendar.scheduler.HotRouteDecayScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final CacheWarmingScheduler cacheWarmingScheduler;
    private final HotRouteDecayScheduler hotRouteDecayScheduler;

    public AdminController(CacheWarmingScheduler cacheWarmingScheduler,
                           HotRouteDecayScheduler hotRouteDecayScheduler) {
        this.cacheWarmingScheduler = cacheWarmingScheduler;
        this.hotRouteDecayScheduler = hotRouteDecayScheduler;
    }

    @PostMapping("/cache/warm")
    public ResponseEntity<String> triggerCacheWarm() {
        log.info("AdminController: manual cache warm triggered via POST /admin/cache/warm");
        cacheWarmingScheduler.warm();
        return ResponseEntity.ok("Cache warm cycle triggered.");
    }

    @PostMapping("/hot-routes/decay")
    public ResponseEntity<String> triggerHotRouteDecay() {
        log.info("AdminController: manual hot-route decay triggered via POST /admin/hot-routes/decay");
        hotRouteDecayScheduler.decay();
        return ResponseEntity.ok("Hot-route decay triggered.");
    }
}
