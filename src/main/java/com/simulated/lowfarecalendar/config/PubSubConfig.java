package com.simulated.lowfarecalendar.config;

import com.simulated.lowfarecalendar.config.LfcProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/**
 * GCP Pub/Sub configuration.
 *
 * Conditional on lfc.pubsub.enabled=true to prevent Spring Cloud GCP
 * auto-configuration from attempting to connect to a real GCP project
 * when running tests or local dev with pubsub disabled.
 *
 * The emulator host is passed via the PUBSUB_EMULATOR_HOST environment
 * variable or the spring.cloud.gcp.pubsub.emulator-host property.
 * Spring Cloud GCP auto-detects PUBSUB_EMULATOR_HOST and routes the
 * PubSubTemplate to the emulator automatically — no bean override is needed.
 *
 * For integration tests, set:
 *   System.setProperty("spring.cloud.gcp.pubsub.emulator-host",
 *       lfcProperties.getPubsub().getEmulatorHost());
 * in a @BeforeAll. This class serves as the documented binding point.
 */
@Configuration
@ConditionalOnProperty(name = "lfc.pubsub.enabled", havingValue = "true")
public class PubSubConfig {

    private final LfcProperties lfcProperties;

    public PubSubConfig(LfcProperties lfcProperties) {
        this.lfcProperties = lfcProperties;
    }
}
