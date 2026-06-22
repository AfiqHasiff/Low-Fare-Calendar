package com.simulated.lowfarecalendar.cache;

import com.simulated.lowfarecalendar.config.LfcProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.YearMonth;
import java.util.Collections;
import java.util.List;
import java.util.Set;

@Service
public class HotRouteTracker {

    private static final String ZSET_KEY = "hot_routes";

    private final StringRedisTemplate redisTemplate;
    private final LfcProperties props;

    public HotRouteTracker(StringRedisTemplate redisTemplate, LfcProperties props) {
        this.redisTemplate = redisTemplate;
        this.props = props;
    }

    /**
     * Increments the request score for the given route+month by 1.
     */
    public void increment(String origin, String dest, YearMonth month) {
        String member = origin.toUpperCase() + ":" + dest.toUpperCase() + ":" + month;
        redisTemplate.opsForZSet().incrementScore(ZSET_KEY, member, 1.0);
    }

    /**
     * Returns true if the route+month score meets or exceeds the configured hot threshold.
     */
    public boolean isHot(String origin, String dest, YearMonth month) {
        String member = origin.toUpperCase() + ":" + dest.toUpperCase() + ":" + month;
        Double score = redisTemplate.opsForZSet().score(ZSET_KEY, member);
        if (score == null) return false;
        return score >= props.getHotRoutes().getHotThreshold();
    }

    /**
     * Returns the top-k routes by score, highest first.
     */
    public List<String> getTopK(int k) {
        Set<String> members = redisTemplate.opsForZSet()
                .reverseRangeByScore(ZSET_KEY, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY,
                        0, k);
        if (members == null) return Collections.emptyList();
        return List.copyOf(members);
    }

    /**
     * Removes all members whose score is strictly below minScore.
     */
    public void pruneBelow(double minScore) {
        redisTemplate.opsForZSet()
                .removeRangeByScore(ZSET_KEY, Double.NEGATIVE_INFINITY, minScore - 1);
    }
}
