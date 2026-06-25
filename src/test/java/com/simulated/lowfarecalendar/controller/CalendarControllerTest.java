package com.simulated.lowfarecalendar.controller;

import com.simulated.lowfarecalendar.config.LfcProperties;
import com.simulated.lowfarecalendar.exception.ServiceUnavailableException;
import com.simulated.lowfarecalendar.model.CalendarResponse;
import com.simulated.lowfarecalendar.model.DayPrice;
import com.simulated.lowfarecalendar.service.CalendarService;
import com.simulated.lowfarecalendar.trace.RequestTraceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CalendarController.class)
class CalendarControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CalendarService calendarService;

    @MockBean
    private RequestTraceContext requestTraceContext;

    @MockBean
    private LfcProperties lfcProperties;

    @BeforeEach
    void setUp() {
        // Stub LfcProperties for the currency sample rate calculation
        LfcProperties.CurrencyProperties currencyProperties = new LfcProperties.CurrencyProperties();
        lenient().when(lfcProperties.getCurrency()).thenReturn(currencyProperties);
    }

    // Test 1: valid params -> 200, origin in response is KUL
    @Test
    void getCalendar_validParams_returns200WithOriginKUL() throws Exception {
        CalendarResponse mockResponse = CalendarResponse.builder()
                .origin("KUL")
                .destination("SIN")
                .month("2024-07")
                .currency("MYR")
                .calendar(List.of(
                        DayPrice.builder()
                                .date("2024-07-01")
                                .lowestPrice(new BigDecimal("199.00"))
                                .available(true)
                                .stale(false)  // stale is on DayPrice (response model), not CachedFareEntry
                                .build()))
                .generatedAt(Instant.parse("2024-07-15T10:30:00Z"))
                .build();

        when(calendarService.getCalendar(eq("KUL"), eq("SIN"), eq(YearMonth.of(2024, 7)), eq("MYR")))
                .thenReturn(mockResponse);

        mockMvc.perform(get("/api/v1/flights/calendar")
                        .param("origin", "KUL")
                        .param("destination", "SIN")
                        .param("month", "2024-07")
                        .param("currency", "MYR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.origin").value("KUL"))
                .andExpect(jsonPath("$.destination").value("SIN"))
                .andExpect(jsonPath("$.currency").value("MYR"))
                .andExpect(jsonPath("$.calendar[0].date").value("2024-07-01"))
                .andExpect(jsonPath("$.calendar[0].lowestPrice").value(199.00));
    }

    // Test 2: missing origin -> 400
    @Test
    void getCalendar_missingOrigin_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/flights/calendar")
                        .param("destination", "SIN")
                        .param("month", "2024-07")
                        .param("currency", "MYR"))
                .andExpect(status().isBadRequest());
    }

    // Test 3: origin length 2 "KU" -> 400
    @Test
    void getCalendar_originTwoChars_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/flights/calendar")
                        .param("origin", "KU")
                        .param("destination", "SIN")
                        .param("month", "2024-07")
                        .param("currency", "MYR"))
                .andExpect(status().isBadRequest());
    }

    // Test 4: month="2024/07" -> 400
    @Test
    void getCalendar_invalidMonthFormat_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/flights/calendar")
                        .param("origin", "KUL")
                        .param("destination", "SIN")
                        .param("month", "2024/07")
                        .param("currency", "MYR"))
                .andExpect(status().isBadRequest());
        }

    // Test 5: currency="XYZ" -> 400 body contains INVALID_PARAMETER
    @Test
    void getCalendar_unsupportedCurrency_returns400WithInvalidParameter() throws Exception {
        mockMvc.perform(get("/api/v1/flights/calendar")
                        .param("origin", "KUL")
                        .param("destination", "SIN")
                        .param("month", "2024-07")
                        .param("currency", "XYZ"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PARAMETER"));
    }

    // Test 6: lowercase origin "kul" -> CalendarService called with "KUL"
    @Test
    void getCalendar_lowercaseOrigin_normalisesToUpperCaseBeforeCallingService() throws Exception {
        CalendarResponse mockResponse = CalendarResponse.builder()
                .origin("KUL")
                .destination("SIN")
                .month("2024-07")
                .currency("MYR")
                .calendar(List.of())
                .generatedAt(Instant.now())
                .build();

        when(calendarService.getCalendar(eq("KUL"), eq("SIN"), eq(YearMonth.of(2024, 7)), eq("MYR")))
                .thenReturn(mockResponse);

        mockMvc.perform(get("/api/v1/flights/calendar")
                        .param("origin", "kul")
                        .param("destination", "sin")
                        .param("month", "2024-07")
                        .param("currency", "MYR"))
                .andExpect(status().isOk());

        verify(calendarService).getCalendar(eq("KUL"), eq("SIN"), eq(YearMonth.of(2024, 7)), eq("MYR"));
    }

    // Test 7: ServiceUnavailableException -> 503 body SERVICE_UNAVAILABLE
    @Test
    void getCalendar_serviceUnavailable_returns503WithServiceUnavailableBody() throws Exception {
        when(calendarService.getCalendar(any(), any(), any(), any()))
                .thenThrow(new ServiceUnavailableException(
                        "Unable to retrieve fare data at this time. Please try again."));

        mockMvc.perform(get("/api/v1/flights/calendar")
                        .param("origin", "KUL")
                        .param("destination", "SIN")
                        .param("month", "2024-07")
                        .param("currency", "MYR"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value(
                        "Unable to retrieve fare data at this time. Please try again."))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }
}
