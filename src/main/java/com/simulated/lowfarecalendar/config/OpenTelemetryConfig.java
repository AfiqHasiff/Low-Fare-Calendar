package com.simulated.lowfarecalendar.config;

import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Minimal OpenTelemetry configuration.
 *
 * <p>Exposes a no-op {@link SdkTracerProvider} bean so that any component
 * that needs a tracer can inject one without pulling in a real OTLP exporter.
 * Metric export is handled by Spring Boot Actuator's Prometheus endpoint
 * ({@code /actuator/prometheus}) via the auto-configured
 * {@code PrometheusMeterRegistry}.
 *
 * <p>Real OTLP / Prometheus HTTP-server export can be wired here in a future
 * task once the {@code opentelemetry-exporter-otlp} and
 * {@code opentelemetry-exporter-prometheus} jars are added to the classpath.
 */
@Configuration
public class OpenTelemetryConfig {

    /**
     * No-op SDK tracer provider.
     * Satisfies injection points for tests and is safe to use in production
     * until a real exporter is configured.
     */
    @Bean
    public SdkTracerProvider sdkTracerProvider() {
        return SdkTracerProvider.builder().build();
    }
}
