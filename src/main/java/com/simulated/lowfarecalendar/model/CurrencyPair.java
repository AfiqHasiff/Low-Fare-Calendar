package com.simulated.lowfarecalendar.model;

/**
 * Immutable value object representing a directed currency conversion pair.
 * Used as a Map key in CurrencyConverterRegistry — equals/hashCode are
 * record-generated and correct for Map lookup.
 *
 * Example: new CurrencyPair("USD", "MYR")
 */
public record CurrencyPair(String from, String to) {
}
