package com.simulated.lowfarecalendar.model;

import java.time.LocalDate;

/**
 * Immutable query descriptor passed to every FlightProvider.
 * Carries the minimal coordinates needed to look up fares for a single day.
 * IATA codes should be stored uppercase by the caller before construction.
 */
public record FlightQuery(String origin, String destination, LocalDate date) {
}
