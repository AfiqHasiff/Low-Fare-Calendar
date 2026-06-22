package com.simulated.lowfarecalendar.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class FlightQueryTest {

    @Test
    @DisplayName("FlightQuery is immutable record with origin, destination, date")
    void flightQueryRecordStructure() {
        LocalDate date = LocalDate.of(2024, 7, 15);
        FlightQuery query = new FlightQuery("KUL", "SIN", date);

        assertThat(query.origin()).isEqualTo("KUL");
        assertThat(query.destination()).isEqualTo("SIN");
        assertThat(query.date()).isEqualTo(date);
    }

    @Test
    @DisplayName("FlightQuery records are equal when fields match")
    void flightQueryEquality() {
        LocalDate date = LocalDate.of(2024, 7, 15);
        FlightQuery query1 = new FlightQuery("KUL", "SIN", date);
        FlightQuery query2 = new FlightQuery("KUL", "SIN", date);

        assertThat(query1).isEqualTo(query2);
    }

    @Test
    @DisplayName("FlightQuery records are not equal when fields differ")
    void flightQueryInequality() {
        LocalDate date1 = LocalDate.of(2024, 7, 15);
        LocalDate date2 = LocalDate.of(2024, 7, 16);
        FlightQuery query1 = new FlightQuery("KUL", "SIN", date1);
        FlightQuery query2 = new FlightQuery("KUL", "SIN", date2);

        assertThat(query1).isNotEqualTo(query2);
    }
}
