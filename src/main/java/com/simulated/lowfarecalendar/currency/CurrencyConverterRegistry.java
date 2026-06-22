package com.simulated.lowfarecalendar.currency;

import com.simulated.lowfarecalendar.exception.UnsupportedCurrencyException;
import com.simulated.lowfarecalendar.model.CurrencyPair;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Builds and holds an immutable Map<CurrencyPair, CurrencyConverter> at application startup.
 * Spring injects all @Component CurrencyConverter implementations as a List.
 *
 * Open/Closed Principle: adding a new converter requires only annotating the new class
 * with @Component. This registry class never needs modification.
 *
 * The registry is closed for modification after construction — the map is unmodifiable.
 */
@Component
public class CurrencyConverterRegistry {

    private final Map<CurrencyPair, CurrencyConverter> registry;

    public CurrencyConverterRegistry(List<CurrencyConverter> converters) {
        this.registry = converters.stream()
                .flatMap(converter -> converter.supportedPairs()
                        .stream()
                        .map(pair -> Map.entry(pair, converter)))
                .collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue
                ));
    }

    /**
     * Resolves the CurrencyConverter for the given pair.
     *
     * @param from ISO-4217 source currency (e.g. "USD")
     * @param to   ISO-4217 target currency (e.g. "MYR")
     * @return the registered converter for this pair
     * @throws UnsupportedCurrencyException if no converter is registered for this pair
     */
    public CurrencyConverter get(String from, String to) {
        return Optional.ofNullable(registry.get(new CurrencyPair(from, to)))
                .orElseThrow(() -> new UnsupportedCurrencyException(from, to));
    }
}
