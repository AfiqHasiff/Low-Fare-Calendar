package com.simulated.lowfarecalendar.trace;

import org.springframework.stereotype.Component;

/**
 * ThreadLocal holder for the per-request RequestTrace.
 * All operations are null-safe: callers can safely call current() and check for null.
 */
@Component
public class RequestTraceContext {

    private static final ThreadLocal<RequestTrace> CONTEXT = new ThreadLocal<>();

    /**
     * Start a new trace for the given request parameters and bind it to the current thread.
     */
    public void start(String origin, String destination, String month, String currency) {
        CONTEXT.set(RequestTrace.start(origin, destination, month, currency));
    }

    /**
     * Return the current trace, or null if no trace is active on this thread.
     */
    public RequestTrace current() {
        return CONTEXT.get();
    }

    /**
     * Remove the trace from the current thread. Always call this in a finally block.
     */
    public void clear() {
        CONTEXT.remove();
    }
}
