package com.simulated.lowfarecalendar.cache;

import com.simulated.lowfarecalendar.config.LfcProperties;
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

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class CacheLockServiceTest {

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProps(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    private CacheLockService cacheLockService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private final String origin = "KUL";
    private final String dest = "SIN";
    private final LocalDate date = LocalDate.of(2024, 7, 15);
    private final String lockKey = "lock:lfc:KUL:SIN:2024-07-15";

    @BeforeEach
    void setUp() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    void tryAcquire_firstCall_returnsNonEmptyToken() {
        Optional<String> token = cacheLockService.tryAcquire(origin, dest, date);
        assertThat(token).isPresent();
        assertThat(token.get()).isNotBlank();
    }

    @Test
    void tryAcquire_whileLockHeld_returnsEmpty() {
        cacheLockService.tryAcquire(origin, dest, date);
        Optional<String> second = cacheLockService.tryAcquire(origin, dest, date);
        assertThat(second).isEmpty();
    }

    @Test
    void release_withCorrectToken_deletesKey() {
        Optional<String> token = cacheLockService.tryAcquire(origin, dest, date);
        assertThat(token).isPresent();

        cacheLockService.release(origin, dest, date, token.get());

        String value = redisTemplate.opsForValue().get(lockKey);
        assertThat(value).isNull();
    }

    @Test
    void release_withWrongToken_doesNotDeleteKey() {
        cacheLockService.tryAcquire(origin, dest, date);
        cacheLockService.release(origin, dest, date, "wrong-token-uuid");

        String value = redisTemplate.opsForValue().get(lockKey);
        assertThat(value).isNotNull();
    }
}
