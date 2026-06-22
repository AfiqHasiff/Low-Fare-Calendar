package com.simulated.lowfarecalendar.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class ProviderCircuitBreakerConfig {

    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry(LfcProperties props) {
        LfcProperties.CircuitBreakerProperties cb = props.getCircuitBreaker();
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(cb.getSlidingWindowSize())
                .minimumNumberOfCalls(cb.getMinimumNumberOfCalls())
                .failureRateThreshold(cb.getFailureRateThreshold())
                .slowCallDurationThreshold(Duration.ofMillis(cb.getSlowCallDurationThresholdMs()))
                .slowCallRateThreshold(cb.getSlowCallRateThreshold())
                .waitDurationInOpenState(Duration.ofMillis(cb.getWaitDurationInOpenStateMs()))
                .permittedNumberOfCallsInHalfOpenState(cb.getPermittedCallsInHalfOpen())
                .build();

        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(config);
        registry.circuitBreaker("providerA");
        registry.circuitBreaker("providerB");
        registry.circuitBreaker("providerC");
        return registry;
    }
}
