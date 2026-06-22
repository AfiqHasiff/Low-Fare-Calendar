package com.simulated.lowfarecalendar.integration;

import com.simulated.lowfarecalendar.model.CalendarResponse;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the circuit breaker for providerA opens after repeated failures
 * (error-rate=1.0 in circuit-breaker profile) and that the API continues to respond
 * successfully using providerB and providerC.
 *
 * Profile "circuit-breaker" overrides lfc.providers.provider-a.error-rate=1.0 and
 * shrinks the sliding window to minimum-number-of-calls=5 so the breaker opens quickly.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles({"integration", "circuit-breaker"})
class CircuitBreakerIntegrationTest {

    @SuppressWarnings("resource")
    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    StringRedisTemplate stringRedisTemplate;

    @Autowired
    CircuitBreakerRegistry circuitBreakerRegistry;

    @BeforeEach
    void flushRedis() {
        stringRedisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    void afterSufficientFailures_circuitOpensForProviderA_responseStillSucceeds() {
        // Make 15 requests to the same route — providerA error-rate=1.0 in circuit-breaker profile.
        // minimumNumberOfCalls=5, failure-rate-threshold=50 → circuit opens after first 5 failures.
        for (int i = 0; i < 15; i++) {
            restTemplate.getForEntity(
                    "http://localhost:" + port + "/api/v1/flights/calendar"
                            + "?origin=KUL&destination=SIN&month=2024-07&currency=USD",
                    CalendarResponse.class);
            // Flush between calls so each call hits providers (jitter=0 means cache may persist;
            // flush forces fresh aggregation every call)
            if (i < 14) {
                stringRedisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
            }
        }

        // One more request — circuit for providerA should now be open
        ResponseEntity<CalendarResponse> response = restTemplate.getForEntity(
                "http://localhost:" + port + "/api/v1/flights/calendar"
                        + "?origin=KUL&destination=SIN&month=2024-07&currency=USD",
                CalendarResponse.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().calendar()).isNotEmpty();

        // Verify via CircuitBreakerRegistry that providerA's breaker is now OPEN
        CircuitBreaker providerACb = circuitBreakerRegistry.circuitBreaker("providerA");
        assertThat(providerACb.getState())
                .as("providerA circuit breaker must be OPEN after repeated failures")
                .isEqualTo(CircuitBreaker.State.OPEN);

        // With providerA's circuit open, only providerB (200 USD) and providerC (175 USD) respond.
        // The lowest price should be 175.00 USD (providerC), NOT 150.00 USD (providerA).
        response.getBody().calendar().forEach(day -> {
            if (day.available()) {
                assertThat(day.lowestPrice())
                        .as("providerA (150 USD) must not be the winner when its circuit is open")
                        .isGreaterThan(new java.math.BigDecimal("150.00"));
            }
        });
    }
}
