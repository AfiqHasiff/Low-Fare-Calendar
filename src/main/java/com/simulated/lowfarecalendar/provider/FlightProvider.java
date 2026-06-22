package com.simulated.lowfarecalendar.provider;

import com.simulated.lowfarecalendar.model.FareQuote;
import com.simulated.lowfarecalendar.model.FlightQuery;

import java.util.Optional;

public interface FlightProvider {
    Optional<FareQuote> getFares(FlightQuery query);
    String getId();
    boolean isEnabled();
}
