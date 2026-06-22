package com.simulated.lowfarecalendar.exception;

/**
 * Thrown by CurrencyConverterRegistry when the requested conversion pair
 * has no registered CurrencyConverter implementation.
 * Mapped to HTTP 400 Bad Request by the global exception handler.
 */
public class UnsupportedCurrencyException extends RuntimeException {

    public UnsupportedCurrencyException(String currency) {
        super("currency '" + currency + "' is not supported. Supported: MYR, USD, THB");
    }

    public UnsupportedCurrencyException(String from, String to) {
        super(String.format("Currency conversion from '%s' to '%s' is not supported.", from, to));
    }
}
