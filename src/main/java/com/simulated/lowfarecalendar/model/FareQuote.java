package com.simulated.lowfarecalendar.model;

import java.math.BigDecimal;

/**
 * Single fare quote returned by one FlightProvider for one FlightQuery.
 * price is always in USD — currency conversion is applied at read time only.
 * available=false means the provider has no bookable fare for this date
 * (e.g. flight cancelled, no inventory) — distinct from a provider error.
 */
public record FareQuote(String providerId, BigDecimal price, boolean available) {
}
