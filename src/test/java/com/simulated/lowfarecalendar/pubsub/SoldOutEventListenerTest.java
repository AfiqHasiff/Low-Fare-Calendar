package com.simulated.lowfarecalendar.pubsub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simulated.lowfarecalendar.cache.FareCacheService;
import com.simulated.lowfarecalendar.config.LfcProperties;
import com.simulated.lowfarecalendar.config.LfcProperties.CacheProperties;
import com.simulated.lowfarecalendar.model.CachedFareEntry;
import com.simulated.lowfarecalendar.model.FlightQuery;
import com.simulated.lowfarecalendar.model.SoldOutEvent;
import com.simulated.lowfarecalendar.observability.CacheMetricsService;
import com.simulated.lowfarecalendar.service.ProviderAggregationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SoldOutEventListenerTest {

    @Mock private RedisTemplate<String, String> redisTemplate;
    @Mock private FareCacheService fareCacheService;
    @Mock private ProviderAggregationService providerAggregationService;
    @Mock private CacheMetricsService cacheMetricsService;
    @Mock private LfcProperties lfcProperties;

    @InjectMocks
    private SoldOutEventListener listener;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules(); // registers JavaTimeModule

    private static final String ORIGIN = "KUL";
    private static final String DESTINATION = "SIN";
    private static final LocalDate DATE = LocalDate.of(2024, 7, 15);
    private static final Instant GENERATED_AT = Instant.parse("2024-07-15T10:25:00Z");

    @BeforeEach
    void setUp() throws Exception {
        CacheProperties cacheProperties = new CacheProperties();
        cacheProperties.setKeyVersion("v1");
        when(lfcProperties.getCache()).thenReturn(cacheProperties);

        // Wire objectMapper into listener — InjectMocks does not inject the inline
        // ObjectMapper instance; use reflection to set the field directly.
        var field = SoldOutEventListener.class.getDeclaredField("objectMapper");
        field.setAccessible(true);
        field.set(listener, objectMapper);
    }

    private byte[] buildMessagePayload(SoldOutEvent event) throws Exception {
        return objectMapper.writeValueAsBytes(event);
    }

    private SoldOutEvent buildEvent(Instant generatedAt) {
        return SoldOutEvent.builder()
                .eventId("test-uuid")
                .origin(ORIGIN)
                .destination(DESTINATION)
                .date(DATE)
                .priceClass("ECONOMY_LITE")
                .soldOutPrice(new BigDecimal("199.00"))
                .currency("USD")
                .generatedAt(generatedAt)
                .build();
    }

    /**
     * Test 1: Lua returns PROCEED, aggregation returns an entry.
     * Expects: evict called, set called with updatedAt == event.generatedAt,
     * metric = "processed", ack invoked, nack never invoked.
     */
    @Test
    void handleMessage_proceedAndAggregationReturnsEntry_evictsAndSetsWithEventGeneratedAt()
            throws Exception {
        SoldOutEvent event = buildEvent(GENERATED_AT);
        byte[] payload = buildMessagePayload(event);

        when(redisTemplate.execute(any(RedisScript.class), anyList(), any()))
                .thenReturn("PROCEED");

        CachedFareEntry aggregatedEntry = CachedFareEntry.builder()
                .origin(ORIGIN)
                .destination(DESTINATION)
                .date(DATE.toString())
                .lowestPrice(new BigDecimal("215.00"))
                .currency("USD")
                .updatedAt(Instant.now())
                .stale(false)
                .build();

        when(providerAggregationService.aggregate(any(FlightQuery.class)))
                .thenReturn(Optional.of(aggregatedEntry));

        AtomicBoolean acked = new AtomicBoolean(false);
        AtomicBoolean nacked = new AtomicBoolean(false);

        listener.handleMessage(payload, () -> acked.set(true), () -> nacked.set(true));

        verify(fareCacheService).evict(ORIGIN, DESTINATION, DATE);
        verify(cacheMetricsService).recordCacheInvalidation(ORIGIN, DESTINATION);
        verify(providerAggregationService).aggregate(any(FlightQuery.class));
        verify(fareCacheService).set(
                eq(ORIGIN),
                eq(DESTINATION),
                eq(DATE),
                argThat(entry -> GENERATED_AT.equals(entry.getUpdatedAt())));
        verify(cacheMetricsService).recordPubSubEvent("processed");
        assertThat(acked.get()).isTrue();
        assertThat(nacked.get()).isFalse();
    }

    /**
     * Test 2: Lua returns STALE.
     * Expects: evict NOT called, aggregate NOT called, metric = "stale_discarded", ack invoked.
     */
    @Test
    void handleMessage_stale_doesNotEvictOrAggregate() throws Exception {
        SoldOutEvent event = buildEvent(GENERATED_AT);
        byte[] payload = buildMessagePayload(event);

        when(redisTemplate.execute(any(RedisScript.class), anyList(), any()))
                .thenReturn("STALE");

        AtomicBoolean acked = new AtomicBoolean(false);
        AtomicBoolean nacked = new AtomicBoolean(false);

        listener.handleMessage(payload, () -> acked.set(true), () -> nacked.set(true));

        verify(fareCacheService, never()).evict(any(), any(), any());
        verify(providerAggregationService, never()).aggregate(any());
        verify(fareCacheService, never()).set(any(), any(), any(), any());
        verify(cacheMetricsService).recordPubSubEvent("stale_discarded");
        assertThat(acked.get()).isTrue();
        assertThat(nacked.get()).isFalse();
    }

    /**
     * Test 3: Duplicate event (same generatedAt as cached) treated as STALE.
     * Same as Test 2 but documents the duplicate scenario explicitly.
     */
    @Test
    void handleMessage_duplicateEvent_treatedAsStale() throws Exception {
        SoldOutEvent event = buildEvent(GENERATED_AT);
        byte[] payload = buildMessagePayload(event);

        when(redisTemplate.execute(any(RedisScript.class), anyList(), any()))
                .thenReturn("STALE");

        AtomicBoolean acked = new AtomicBoolean(false);

        listener.handleMessage(payload, () -> acked.set(true), () -> {});

        verify(fareCacheService, never()).evict(any(), any(), any());
        verify(providerAggregationService, never()).aggregate(any());
        verify(cacheMetricsService).recordPubSubEvent("stale_discarded");
        assertThat(acked.get()).isTrue();
    }

    /**
     * Test 4: Lua returns PROCEED but aggregation returns empty.
     * Expects: evict called, aggregate called, set NOT called, metric = "processed", ack invoked.
     */
    @Test
    void handleMessage_proceedButAggregationEmpty_evictsButDoesNotSet() throws Exception {
        SoldOutEvent event = buildEvent(GENERATED_AT);
        byte[] payload = buildMessagePayload(event);

        when(redisTemplate.execute(any(RedisScript.class), anyList(), any()))
                .thenReturn("PROCEED");
        when(providerAggregationService.aggregate(any(FlightQuery.class)))
                .thenReturn(Optional.empty());

        AtomicBoolean acked = new AtomicBoolean(false);
        AtomicBoolean nacked = new AtomicBoolean(false);

        listener.handleMessage(payload, () -> acked.set(true), () -> nacked.set(true));

        verify(fareCacheService).evict(ORIGIN, DESTINATION, DATE);
        verify(cacheMetricsService).recordCacheInvalidation(ORIGIN, DESTINATION);
        verify(providerAggregationService).aggregate(any(FlightQuery.class));
        verify(fareCacheService, never()).set(any(), any(), any(), any());
        verify(cacheMetricsService).recordPubSubEvent("processed");
        assertThat(acked.get()).isTrue();
        assertThat(nacked.get()).isFalse();
    }
}
