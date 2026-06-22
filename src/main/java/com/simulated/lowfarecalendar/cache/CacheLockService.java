package com.simulated.lowfarecalendar.cache;

import com.simulated.lowfarecalendar.config.LfcProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class CacheLockService {

    private static final Logger log = LoggerFactory.getLogger(CacheLockService.class);

    private static final String RELEASE_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
            "  return redis.call('del', KEYS[1]) " +
            "else " +
            "  return 0 " +
            "end";

    private final StringRedisTemplate redisTemplate;
    private final LfcProperties props;

    public CacheLockService(StringRedisTemplate redisTemplate, LfcProperties props) {
        this.redisTemplate = redisTemplate;
        this.props = props;
    }

    /**
     * Attempts to acquire a distributed lock for the given route and date.
     *
     * @return Optional containing the lock token if acquired, empty if lock is already held
     */
    public Optional<String> tryAcquire(String origin, String dest, LocalDate date) {
        String token = UUID.randomUUID().toString();
        String key = lockKey(origin, dest, date);
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(key, token, Duration.ofMillis(props.getLock().getTtlMs()));
        return Boolean.TRUE.equals(acquired) ? Optional.of(token) : Optional.empty();
    }

    /**
     * Releases the lock only if the provided token matches the stored token (Lua atomic check).
     */
    public void release(String origin, String dest, LocalDate date, String ownerToken) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>(RELEASE_SCRIPT, Long.class);
        redisTemplate.execute(script, List.of(lockKey(origin, dest, date)), ownerToken);
    }

    /**
     * Polls the primary cache key until either the value appears or the poll timeout elapses.
     *
     * @return true if a value appeared before the deadline, false on timeout
     */
    public boolean pollForResult(String origin, String dest, LocalDate date) {
        String primaryKey = "lfc:" + props.getCache().getKeyVersion()
                + ":" + origin.toUpperCase() + ":" + dest.toUpperCase() + ":" + date;
        long deadline = System.currentTimeMillis() + props.getLock().getPollTimeoutMs();
        while (System.currentTimeMillis() < deadline) {
            String value = redisTemplate.opsForValue().get(primaryKey);
            if (value != null) return true;
            try {
                Thread.sleep(props.getLock().getPollIntervalMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private String lockKey(String origin, String dest, LocalDate date) {
        return "lock:lfc:" + origin.toUpperCase() + ":" + dest.toUpperCase() + ":" + date;
    }
}
