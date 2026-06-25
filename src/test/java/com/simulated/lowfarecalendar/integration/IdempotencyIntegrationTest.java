package com.simulated.lowfarecalendar.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simulated.lowfarecalendar.model.CachedFareEntry;
import com.simulated.lowfarecalendar.model.SoldOutEvent;
import com.simulated.lowfarecalendar.pubsub.SoldOutEventListener;
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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("integration")
class IdempotencyIntegrationTest {

    @SuppressWarnings("resource")
    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    StringRedisTemplate stringRedisTemplate;

    @Autowired
    SoldOutEventListener soldOutEventListener;

    @Autowired
    ObjectMapper objectMapper;

    // Primary cache key for KUL-SIN 2024-07-15
    private static final String PRIMARY_KEY = "lfc:v1:KUL:SIN:2024-07-15";
    // Fallback key — this is what the Lua idempotency script checks
    private static final String FALLBACK_KEY = "lfc:v1:fallback:KUL:SIN:2024-07-15";

    @BeforeEach
    void seedRedis() throws Exception {
        stringRedisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();

        // Seed both primary and fallback keys with updatedAt=2024-07-15T10:00:30Z
        // The Lua script checks the fallback key for idempotency ordering.
        CachedFareEntry seeded = CachedFareEntry.builder()
                .origin("KUL")
                .destination("SIN")
                .date("2024-07-15")
                .lowestPrice(new BigDecimal("250.00"))
                .currency("USD")
                .updatedAt(Instant.parse("2024-07-15T10:00:30Z"))
                .respondingProviders(List.of("providerA", "providerB", "providerC"))
                .winningProvider("providerC")
                .providerCount(3)
                .build();

        String json = objectMapper.writeValueAsString(seeded);
        stringRedisTemplate.opsForValue().set(PRIMARY_KEY, json);
        stringRedisTemplate.opsForValue().set(FALLBACK_KEY, json);
    }

    @Test
    void staleEvent_olderGeneratedAt_doesNotOverwriteCache() throws Exception {
        // Build an event with generatedAt OLDER than the seeded updatedAt (10:00:00 < 10:00:30)
        SoldOutEvent staleEvent = SoldOutEvent.builder()
                .eventId("test-event-id-001")
                .origin("KUL")
                .destination("SIN")
                .date("2024-07-15")
                .priceClass("ECONOMY_LITE")
                .soldOutPrice(new BigDecimal("199.00"))
                .currency("USD")
                .generatedAt(Instant.parse("2024-07-15T10:00:00Z"))
                .build();

        String eventJson = objectMapper.writeValueAsString(staleEvent);
        byte[] payload = eventJson.getBytes(StandardCharsets.UTF_8);

        AtomicBoolean acked = new AtomicBoolean(false);
        AtomicBoolean nacked = new AtomicBoolean(false);

        // Inject the event listener directly — bypasses Pub/Sub transport
        soldOutEventListener.handleMessage(payload, () -> acked.set(true), () -> nacked.set(true));

        // Stale event must be acked without modifying anything
        assertThat(acked.get()).isTrue();
        assertThat(nacked.get()).isFalse();

        // Assert Redis still contains the original seeded entry (250.00) on the primary key
        String raw = stringRedisTemplate.opsForValue().get(PRIMARY_KEY);
        assertThat(raw).isNotNull();

        CachedFareEntry current = objectMapper.readValue(raw, CachedFareEntry.class);
        assertThat(current.getLowestPrice()).isEqualByComparingTo("250.00");
    }

    @Test
    void duplicateEvent_sameGeneratedAt_doesNotOverwriteCache() throws Exception {
        // Build an event with generatedAt EQUAL to the seeded updatedAt (10:00:30 == 10:00:30)
        SoldOutEvent duplicateEvent = SoldOutEvent.builder()
                .eventId("test-event-id-002")
                .origin("KUL")
                .destination("SIN")
                .date("2024-07-15")
                .priceClass("ECONOMY_LITE")
                .soldOutPrice(new BigDecimal("199.00"))
                .currency("USD")
                .generatedAt(Instant.parse("2024-07-15T10:00:30Z"))
                .build();

        String eventJson = objectMapper.writeValueAsString(duplicateEvent);
        byte[] payload = eventJson.getBytes(StandardCharsets.UTF_8);

        AtomicBoolean acked = new AtomicBoolean(false);

        // Duplicate event (same generatedAt) must be treated as stale
        soldOutEventListener.handleMessage(payload, () -> acked.set(true), () -> {});

        assertThat(acked.get()).isTrue();

        // Primary key remains unchanged
        String raw = stringRedisTemplate.opsForValue().get(PRIMARY_KEY);
        assertThat(raw).isNotNull();

        CachedFareEntry current = objectMapper.readValue(raw, CachedFareEntry.class);
        assertThat(current.getLowestPrice()).isEqualByComparingTo("250.00");
    }
}
