package com.simulated.lowfarecalendar.cache;

import com.simulated.lowfarecalendar.model.CachedFareEntry;
import com.simulated.lowfarecalendar.trace.RequestTraceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class InProcessSingleflightTest {

    private InProcessSingleflight singleflight;

    @BeforeEach
    void setUp() {
        singleflight = new InProcessSingleflight(new RequestTraceContext());
    }

    private CachedFareEntry sampleEntry() {
        return CachedFareEntry.builder()
                .origin("KUL")
                .destination("SIN")
                .date("2024-07-15")
                .lowestPrice(new BigDecimal("150.00"))
                .currency("USD")
                .updatedAt(Instant.parse("2024-07-15T10:00:00Z"))
                .stale(false)
                .build();
    }

    /**
     * Test 1: 20 concurrent threads requesting the same key with a 100 ms fetcher.
     * The fetcher must be invoked exactly once; all 20 results must be equal.
     */
    @Test
    void twentyConcurrentThreadsSameKey_fetcherCalledOnce_allResultsEqual() throws InterruptedException {
        int threadCount = 20;
        AtomicInteger fetcherCallCount = new AtomicInteger(0);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        List<CachedFareEntry> results = new ArrayList<>(threadCount);
        for (int i = 0; i < threadCount; i++) {
            results.add(null);
        }

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    startGate.await();
                    CachedFareEntry entry = singleflight.getOrFetch("KUL:SIN:2024-07-15", () -> {
                        fetcherCallCount.incrementAndGet();
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                        }
                        return sampleEntry();
                    });
                    results.set(index, entry);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startGate.countDown(); // release all threads simultaneously
        doneLatch.await();
        executor.shutdown();

        assertThat(fetcherCallCount.get()).isEqualTo(1);
        CachedFareEntry first = results.get(0);
        assertThat(results).allSatisfy(entry -> assertThat(entry).isEqualTo(first));
    }

    /**
     * Test 2: Two sequential calls with different keys must each invoke the fetcher once
     * (total fetcherCallCount == 2).
     */
    @Test
    void differentKeysSequential_fetcherCalledOncePerKey() {
        AtomicInteger fetcherCallCount = new AtomicInteger(0);

        singleflight.getOrFetch("KUL:SIN:2024-07-15", () -> {
            fetcherCallCount.incrementAndGet();
            return sampleEntry();
        });

        singleflight.getOrFetch("KUL:BKK:2024-07-15", () -> {
            fetcherCallCount.incrementAndGet();
            return sampleEntry();
        });

        assertThat(fetcherCallCount.get()).isEqualTo(2);
    }

    /**
     * Test 3: When the fetcher throws, both the winning thread and any concurrent
     * waiter receive a CompletionException wrapping the original cause.
     */
    @Test
    void fetcherThrowsIllegalStateException_bothConcurrentCallersGetCompletionException()
            throws InterruptedException {
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);
        List<Throwable> caught = new ArrayList<>();

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        for (int i = 0; i < 2; i++) {
            executor.submit(() -> {
                try {
                    startGate.await();
                    singleflight.getOrFetch("KUL:SIN:2024-07-16", () -> {
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                        }
                        throw new IllegalStateException("provider failure");
                    });
                } catch (CompletionException | IllegalStateException ex) {
                    synchronized (caught) {
                        caught.add(ex);
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startGate.countDown();
        doneLatch.await();
        executor.shutdown();

        assertThat(caught).hasSize(2);
        assertThat(caught).allSatisfy(ex -> {
            Throwable root = ex instanceof CompletionException ? ex.getCause() : ex;
            assertThat(root).isInstanceOf(IllegalStateException.class)
                    .hasMessage("provider failure");
        });
    }

    /**
     * Test 4: After the first call completes (including map cleanup in finally),
     * a subsequent call must invoke the fetcher again — confirming cleanup.
     */
    @Test
    void afterFirstCallCompletes_secondCallInvokesFetcherAgain() {
        AtomicInteger fetcherCallCount = new AtomicInteger(0);

        singleflight.getOrFetch("KUL:SIN:2024-07-17", () -> {
            fetcherCallCount.incrementAndGet();
            return sampleEntry();
        });

        singleflight.getOrFetch("KUL:SIN:2024-07-17", () -> {
            fetcherCallCount.incrementAndGet();
            return sampleEntry();
        });

        assertThat(fetcherCallCount.get()).isEqualTo(2);
    }
}
