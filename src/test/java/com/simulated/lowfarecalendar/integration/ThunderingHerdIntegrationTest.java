package com.simulated.lowfarecalendar.integration;

import com.simulated.lowfarecalendar.model.FlightQuery;
import com.simulated.lowfarecalendar.service.ProviderAggregationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("integration")
class ThunderingHerdIntegrationTest {

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
    ProviderAggregationService providerAggregationService;

    @BeforeEach
    void flushRedis() {
        stringRedisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    void concurrentRequestsOnColdRoute_singleflightCoalesces_aggregateCallsAtMost31() throws Exception {
        AtomicInteger aggregateCallCount = new AtomicInteger(0);

        doAnswer(invocation -> {
            aggregateCallCount.incrementAndGet();
            return invocation.callRealMethod();
        }).when(providerAggregationService).aggregate(any(FlightQuery.class));

        int threadCount = 50;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                try {
                    start.await();
                    restTemplate.getForEntity(
                            "http://localhost:" + port + "/api/v1/flights/calendar"
                                    + "?origin=KUL&destination=SIN&month=2024-07&currency=USD",
                            String.class);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }));
        }

        start.countDown();
        done.await();
        executor.shutdown();

        // 31 days in July 2024, singleflight must coalesce to at most 1 aggregate call per day
        assertThat(aggregateCallCount.get())
                .as("Singleflight must coalesce concurrent requests — max 31 aggregate calls for 31 days")
                .isLessThanOrEqualTo(31);
    }
}
