package com.simulated.lowfarecalendar.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.simulated.lowfarecalendar.config.LfcProperties;
import com.simulated.lowfarecalendar.model.CachedFareEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class FareCacheServiceTest {

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProps(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    private FareCacheService fareCacheService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private LfcProperties props;

    private CachedFareEntry sampleEntry;
    private final String origin = "KUL";
    private final String dest = "SIN";
    private final LocalDate date = LocalDate.of(2024, 7, 15);

    @BeforeEach
    void setUp() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
        sampleEntry = CachedFareEntry.builder()
                .origin(origin)
                .destination(dest)
                .date(date.toString())
                .lowestPrice(new BigDecimal("199.00"))
                .currency("USD")
                .updatedAt(Instant.parse("2024-07-15T10:00:00Z"))
                .providerCount(3)
                .respondingProviders(List.of("providerA", "providerB", "providerC"))
                .winningProvider("providerC")
                .build();
    }

    @Test
    void setAndGet_returnsEqualEntry() {
        fareCacheService.set(origin, dest, date, sampleEntry);
        Optional<CachedFareEntry> result = fareCacheService.get(origin, dest, date);

        assertThat(result).isPresent();
        assertThat(result.get().getLowestPrice()).isEqualByComparingTo(new BigDecimal("199.00"));
        assertThat(result.get().getCurrency()).isEqualTo("USD");
        assertThat(result.get().getWinningProvider()).isEqualTo("providerC");
    }

    @Test
    void set_writesFallbackKeyWith24hTtl() {
        fareCacheService.set(origin, dest, date, sampleEntry);

        Optional<CachedFareEntry> fallback = fareCacheService.getFallback(origin, dest, date);
        assertThat(fallback).isPresent();
        assertThat(fallback.get().getLowestPrice()).isEqualByComparingTo(new BigDecimal("199.00"));

        String fallbackKey = "lfc:v1:fallback:" + origin + ":" + dest + ":" + date;
        Long ttl = redisTemplate.getExpire(fallbackKey, TimeUnit.SECONDS);
        assertThat(ttl).isNotNull().isGreaterThan(86390L);
    }

    @Test
    void set_primaryKeyTtlIsWithinJitterRange() {
        // With test profile: jitter-min=0.0, jitter-max=0.0, standard-route-ttl=30s
        fareCacheService.set(origin, dest, date, sampleEntry);

        Long ttlSeconds = fareCacheService.getRemainingTtlSeconds(origin, dest, date);
        long baseTtl = props.getCache().getStandardRouteTtlSeconds();

        // Test profile sets jitter-min=0.0 jitter-max=0.0 so TTL ≈ baseTtl (allow 1s race between set and getExpire)
        assertThat(ttlSeconds).isNotNull().isBetween(baseTtl - 1, baseTtl);
    }

    @Test
    void evict_removesPrimaryKey_leavesFallback() {
        fareCacheService.set(origin, dest, date, sampleEntry);
        fareCacheService.evict(origin, dest, date);

        assertThat(fareCacheService.get(origin, dest, date)).isEmpty();
        assertThat(fareCacheService.getFallback(origin, dest, date)).isPresent();
    }

    @Test
    void pipelineGet_returnsListOfSize31ForJuly() {
        YearMonth month = YearMonth.of(2024, 7);
        // Only set entries for days 1, 15, 31
        fareCacheService.set(origin, dest, LocalDate.of(2024, 7, 1), sampleEntry);
        fareCacheService.set(origin, dest, LocalDate.of(2024, 7, 15), sampleEntry);
        fareCacheService.set(origin, dest, LocalDate.of(2024, 7, 31), sampleEntry);

        List<Optional<CachedFareEntry>> results = fareCacheService.pipelineGet(origin, dest, month);

        assertThat(results).hasSize(31);
        assertThat(results.get(0)).isPresent();   // July 1
        assertThat(results.get(14)).isPresent();  // July 15
        assertThat(results.get(30)).isPresent();  // July 31
        assertThat(results.get(1)).isEmpty();     // July 2 — not set
    }
}
