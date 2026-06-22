package com.simulated.lowfarecalendar.exception;

/**
 * Thrown by CalendarService when all providers are unavailable AND no fallback
 * cache entry exists for the requested route/date.
 * Mapped to HTTP 503 Service Unavailable by the global exception handler.
 */
public class ServiceUnavailableException extends RuntimeException {

    public ServiceUnavailableException(String message) {
        super(message);
    }
}
