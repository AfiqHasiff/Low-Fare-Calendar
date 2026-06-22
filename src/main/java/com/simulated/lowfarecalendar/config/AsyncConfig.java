package com.simulated.lowfarecalendar.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@Configuration
public class AsyncConfig {

    /**
     * Virtual Thread executor (Java 21 Project Loom).
     * Each submitted task runs on its own virtual thread.
     * Carrier threads are unmounted during blocking I/O, making this
     * near-zero-cost for scatter-gather provider calls.
     */
    @Bean(name = "virtualThreadExecutor")
    public Executor virtualThreadExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
