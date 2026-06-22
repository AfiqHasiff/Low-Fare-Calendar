package com.simulated.lowfarecalendar.controller;

import com.simulated.lowfarecalendar.config.LfcProperties;
import com.simulated.lowfarecalendar.exception.UnsupportedCurrencyException;
import com.simulated.lowfarecalendar.model.CalendarResponse;
import com.simulated.lowfarecalendar.model.DayPrice;
import com.simulated.lowfarecalendar.service.CalendarService;
import com.simulated.lowfarecalendar.trace.RequestTrace;
import com.simulated.lowfarecalendar.trace.RequestTraceContext;
import com.simulated.lowfarecalendar.trace.RequestTraceWriter;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/flights")
@Validated
public class CalendarController {

    private static final Set<String> SUPPORTED_CURRENCIES = Set.of("MYR", "USD", "THB");

    private final CalendarService calendarService;
    private final RequestTraceContext traceContext;
    private final Optional<RequestTraceWriter> traceWriter;
    private final LfcProperties lfcProperties;

    public CalendarController(CalendarService calendarService,
                              RequestTraceContext traceContext,
                              Optional<RequestTraceWriter> traceWriter,
                              LfcProperties lfcProperties) {
        this.calendarService = calendarService;
        this.traceContext = traceContext;
        this.traceWriter = traceWriter;
        this.lfcProperties = lfcProperties;
    }

    @GetMapping("/calendar")
    public ResponseEntity<CalendarResponse> getCalendar(
            @RequestParam
            @NotBlank
            @Pattern(regexp = "[A-Za-z]{3}", message = "must be a 3-letter IATA code")
            String origin,

            @RequestParam
            @NotBlank
            @Pattern(regexp = "[A-Za-z]{3}", message = "must be a 3-letter IATA code")
            String destination,

            @RequestParam @NotBlank String month,

            @RequestParam @NotBlank String currency) {

        YearMonth yearMonth;
        try {
            yearMonth = YearMonth.parse(month);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "month '" + month + "' is not a valid YYYY-MM value");
        }

        String normalisedCurrency = currency.toUpperCase();
        if (!SUPPORTED_CURRENCIES.contains(normalisedCurrency)) {
            throw new UnsupportedCurrencyException(currency);
        }

        String normalisedOrigin = origin.toUpperCase();
        String normalisedDestination = destination.toUpperCase();

        // Start trace before service call
        traceContext.start(normalisedOrigin, normalisedDestination,
                yearMonth.toString(), normalisedCurrency);

        try {
            CalendarResponse response = calendarService.getCalendar(
                    normalisedOrigin, normalisedDestination, yearMonth, normalisedCurrency);

            // Populate trace with response-level data
            RequestTrace trace = traceContext.current();
            if (trace != null) {
                // Populate stale and unavailable dates from response
                for (DayPrice day : response.calendar()) {
                    if (!day.available()) {
                        trace.getUnavailableDates().add(day.date());
                    } else if (day.stale()) {
                        trace.getStaleDates().add(day.date());
                    }
                }

                // Populate summary counts
                RequestTrace.Summary summary = trace.getSummary();
                summary.setTotalDays(response.calendar().size());
                summary.setCacheHits(trace.getCacheHitDates().size());
                summary.setCacheMisses(trace.getCacheMissDates().size());
                summary.setStaleFromFallback(trace.getStaleDates().size());
                summary.setUnavailableDays(trace.getUnavailableDates().size());

                // Thundering herd observed if any date had a singleflight follower
                trace.setThunderingHerdObserved(summary.getSingleflightFollowerDates() > 0);

                // Currency info — sample rate from LfcProperties
                RequestTrace.CurrencyInfo currencyInfo = trace.getCurrencyInfo();
                String sampleRate = buildSampleRate(normalisedCurrency);
                currencyInfo.setSampleRate(sampleRate);

                // Write the trace
                traceWriter.ifPresent(w -> w.write(trace));
            }

            return ResponseEntity.ok(response);
        } finally {
            traceContext.clear();
        }
    }

    private String buildSampleRate(String currency) {
        LfcProperties.CurrencyProperties.RatesProperties rates =
                lfcProperties.getCurrency().getRates();
        BigDecimal rate;
        switch (currency) {
            case "MYR" -> rate = rates.getUsdMyr();
            case "THB" -> rate = rates.getUsdThb();
            default -> rate = rates.getUsdUsd();
        }
        return "1 USD = " + rate.toPlainString() + " " + currency;
    }
}
