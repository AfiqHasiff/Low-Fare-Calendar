package com.simulated.lowfarecalendar.cache;

import com.simulated.lowfarecalendar.model.CachedFareEntry;
import com.simulated.lowfarecalendar.trace.RequestTrace;
import com.simulated.lowfarecalendar.trace.RequestTraceContext;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

@Component
public class InProcessSingleflight {

    private final ConcurrentHashMap<String, CompletableFuture<CachedFareEntry>> inFlight =
            new ConcurrentHashMap<>();

    private final RequestTraceContext traceContext;

    public InProcessSingleflight(RequestTraceContext traceContext) {
        this.traceContext = traceContext;
    }

    /**
     * For the given key, ensures only one thread executes the fetcher at a time.
     * Concurrent callers for the same key block on the in-flight CompletableFuture
     * and receive the winner's result. After the future is resolved (success or
     * exception) it is removed from the map so the next independent call re-executes
     * the fetcher.
     *
     * Uses putIfAbsent (not computeIfAbsent) to guarantee atomicity of the
     * "am I the winner?" check without risking re-entrant compute under contention.
     * computeIfAbsent holds the map segment lock during supplier execution, which
     * would deadlock under Virtual Threads — putIfAbsent does not.
     */
    public CachedFareEntry getOrFetch(String key, Supplier<CachedFareEntry> fetcher) {
        CompletableFuture<CachedFareEntry> myFuture = new CompletableFuture<>();
        CompletableFuture<CachedFareEntry> existing = inFlight.putIfAbsent(key, myFuture);

        if (existing != null) {
            // Another thread is already fetching this key — wait for its result.
            RequestTrace followerTrace = traceContext.current();
            if (followerTrace != null) {
                followerTrace.getSummary().singleflightFollowerDates++;
            }
            return existing.join();
        }

        // This thread won the slot — execute the fetcher and settle the future.
        RequestTrace leaderTrace = traceContext.current();
        if (leaderTrace != null) {
            leaderTrace.getSummary().singleflightLeaderDates++;
        }
        try {
            CachedFareEntry result = fetcher.get();
            myFuture.complete(result);
            return result;
        } catch (Exception e) {
            myFuture.completeExceptionally(e);
            throw e;
        } finally {
            // Clean up so a subsequent independent call re-runs the fetcher.
            inFlight.remove(key, myFuture);
        }
    }
}
