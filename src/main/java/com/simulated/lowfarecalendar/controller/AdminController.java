package com.simulated.lowfarecalendar.controller;

import com.simulated.lowfarecalendar.scheduler.CacheWarmingScheduler;
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

    public AdminController(CacheWarmingScheduler cacheWarmingScheduler) {
        this.cacheWarmingScheduler = cacheWarmingScheduler;
    }

    @PostMapping("/cache/warm")
    public ResponseEntity<String> triggerCacheWarm() {
        log.info("AdminController: manual cache warm triggered via POST /admin/cache/warm");
        cacheWarmingScheduler.warm();
        return ResponseEntity.ok("Cache warm cycle triggered.");
    }
}
