package com.simulated.lowfarecalendar;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.simulated.lowfarecalendar.config.LfcProperties;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(LfcProperties.class)
public class LowFareCalendarApplication {

    public static void main(String[] args) {
        SpringApplication.run(LowFareCalendarApplication.class, args);
    }
}
