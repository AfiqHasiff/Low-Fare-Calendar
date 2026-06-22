package com.simulated.lowfarecalendar.integration;

import com.simulated.lowfarecalendar.model.CalendarResponse;
import com.simulated.lowfarecalendar.provider.ProviderA;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
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

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("integration")
class CalendarApiIntegrationTest {

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

    @SpyBean
    ProviderA providerA;

    @BeforeEach
    void flushRedis() {
        stringRedisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    void getCalendar_myrCurrency_returns200WithConvertedPrices() {
        ResponseEntity<CalendarResponse> response = restTemplate.getForEntity(
                "http://localhost:" + port + "/api/v1/flights/calendar"
                        + "?origin=KUL&destination=SIN&month=2024-07&currency=MYR",
                CalendarResponse.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().calendar()).hasSize(31);

        BigDecimal lowestPrice = response.getBody().calendar().get(0).lowestPrice();
        // provider-a fixed at 150.00 USD, usd-myr=4.47, 150.00*4.47=670.50
        assertThat(lowestPrice).isEqualByComparingTo("670.50");
        assertThat(response.getBody().calendar().get(0).stale()).isFalse();
    }

    @Test
    void getCalendar_secondCall_servedFromCache() {
        // First call populates cache
        restTemplate.getForEntity(
                "http://localhost:" + port + "/api/v1/flights/calendar"
                        + "?origin=KUL&destination=SIN&month=2024-07&currency=MYR",
                CalendarResponse.class);

        // Record invocation count after first call
        int countAfterFirstCall = org.mockito.Mockito.mockingDetails(providerA)
                .getInvocations().size();

        // Second call — should be served fully from cache
        restTemplate.getForEntity(
                "http://localhost:" + port + "/api/v1/flights/calendar"
                        + "?origin=KUL&destination=SIN&month=2024-07&currency=MYR",
                CalendarResponse.class);

        int countAfterSecondCall = org.mockito.Mockito.mockingDetails(providerA)
                .getInvocations().size();

        // ProviderA should NOT have been called again (cache hit)
        assertThat(countAfterSecondCall).isEqualTo(countAfterFirstCall);
    }

    @Test
    void getCalendar_usdCurrency_returnsPriceInUsd() {
        ResponseEntity<CalendarResponse> response = restTemplate.getForEntity(
                "http://localhost:" + port + "/api/v1/flights/calendar"
                        + "?origin=KUL&destination=SIN&month=2024-07&currency=USD",
                CalendarResponse.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        // provider-a fixed at 150.00 USD — lowest of 150, 175, 200
        assertThat(response.getBody().calendar().get(0).lowestPrice())
                .isEqualByComparingTo("150.00");
    }
}
