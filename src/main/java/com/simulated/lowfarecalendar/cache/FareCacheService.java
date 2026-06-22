package com.simulated.lowfarecalendar.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simulated.lowfarecalendar.config.LfcProperties;
import com.simulated.lowfarecalendar.model.CachedFareEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@Service
public class FareCacheService {

    private static final Logger log = LoggerFactory.getLogger(FareCacheService.class);

    private final StringRedisTemplate redisTemplate;
    private final LfcProperties props;
    private final HotRouteTracker hotRouteTracker;
    private final ObjectMapper objectMapper;

    public FareCacheService(StringRedisTemplate redisTemplate,
                            LfcProperties props,
                            HotRouteTracker hotRouteTracker,
                            ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.props = props;
        this.hotRouteTracker = hotRouteTracker;
        this.objectMapper = objectMapper;
    }

    public Optional<CachedFareEntry> get(String origin, String dest, LocalDate date) {
        String json = redisTemplate.opsForValue().get(primaryKey(origin, dest, date));
        if (json == null) return Optional.empty();
        return deserialise(json);
    }

    public Optional<CachedFareEntry> getFallback(String origin, String dest, LocalDate date) {
        String json = redisTemplate.opsForValue().get(fallbackKey(origin, dest, date));
        if (json == null) return Optional.empty();
        return deserialise(json);
    }

    public void set(String origin, String dest, LocalDate date, CachedFareEntry entry) {
        String json = serialise(entry);
        if (json == null) return;
        long ttlMs = computeTtlMs(origin, dest, YearMonth.from(date));
        redisTemplate.opsForValue().set(primaryKey(origin, dest, date), json,
                Duration.ofMillis(ttlMs));
        redisTemplate.opsForValue().set(fallbackKey(origin, dest, date), json,
                Duration.ofSeconds(props.getCache().getFallbackTtlSeconds()));
    }

    public void evict(String origin, String dest, LocalDate date) {
        redisTemplate.delete(primaryKey(origin, dest, date));
    }

    public Long getRemainingTtlSeconds(String origin, String dest, LocalDate date) {
        return redisTemplate.getExpire(primaryKey(origin, dest, date), TimeUnit.SECONDS);
    }

    public List<Optional<CachedFareEntry>> pipelineGet(String origin, String dest, YearMonth month) {
        int days = month.lengthOfMonth();
        List<String> keys = new ArrayList<>(days);
        for (int d = 1; d <= days; d++) {
            keys.add(primaryKey(origin, dest, month.atDay(d)));
        }
        List<Object> raw = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (String key : keys) {
                connection.stringCommands().get(key.getBytes());
            }
            return null;
        });
        List<Optional<CachedFareEntry>> results = new ArrayList<>(days);
        for (Object obj : raw) {
            if (obj == null) {
                results.add(Optional.empty());
            } else {
                results.add(deserialise((String) obj));
            }
        }
        return results;
    }

    private long computeTtlMs(String origin, String dest, YearMonth month) {
        boolean hot = hotRouteTracker.isHot(origin, dest, month);
        long baseTtlSeconds = hot
                ? props.getCache().getHotRouteTtlSeconds()
                : props.getCache().getStandardRouteTtlSeconds();
        double jitterMinPct = props.getCache().getJitterMinPct();
        double jitterMaxPct = props.getCache().getJitterMaxPct();
        double jitter;
        if (jitterMaxPct <= jitterMinPct) {
            jitter = jitterMinPct;
        } else {
            jitter = ThreadLocalRandom.current().nextDouble(jitterMinPct, jitterMaxPct);
        }
        long ttlMs = (long) ((baseTtlSeconds + baseTtlSeconds * jitter) * 1000L);
        return ttlMs;
    }

    private String primaryKey(String origin, String dest, LocalDate date) {
        return "lfc:" + props.getCache().getKeyVersion()
                + ":" + origin.toUpperCase()
                + ":" + dest.toUpperCase()
                + ":" + date;
    }

    private String fallbackKey(String origin, String dest, LocalDate date) {
        return "lfc:" + props.getCache().getKeyVersion()
                + ":fallback:"
                + origin.toUpperCase()
                + ":" + dest.toUpperCase()
                + ":" + date;
    }

    private String serialise(CachedFareEntry entry) {
        try {
            return objectMapper.writeValueAsString(entry);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialise CachedFareEntry", e);
            return null;
        }
    }

    private Optional<CachedFareEntry> deserialise(String json) {
        try {
            return Optional.of(objectMapper.readValue(json, CachedFareEntry.class));
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialise CachedFareEntry", e);
            return Optional.empty();
        }
    }
}
