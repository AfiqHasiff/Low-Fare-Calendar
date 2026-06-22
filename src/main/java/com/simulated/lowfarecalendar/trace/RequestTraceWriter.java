package com.simulated.lowfarecalendar.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;

/**
 * Writes per-request trace files to the traces/ directory.
 * Only active when lfc.tracing.enabled=true.
 */
@Component
@ConditionalOnProperty(name = "lfc.tracing.enabled", havingValue = "true")
public class RequestTraceWriter {

    private static final Logger log = LoggerFactory.getLogger(RequestTraceWriter.class);

    private final ObjectMapper objectMapper;

    public RequestTraceWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Serialize the trace to a pretty-printed JSON file under traces/.
     * File name pattern: {origin}-{destination}-{month}-{requestId first 8 chars}.json
     * IOExceptions are swallowed with a WARN log to never fail the request.
     */
    public void write(RequestTrace trace) {
        try {
            Path tracesDir = Paths.get("traces");
            if (!Files.exists(tracesDir)) {
                Files.createDirectories(tracesDir);
            }

            // Inverted epoch so lexicographic sort puts the newest file first.
            long invertedEpoch = Long.MAX_VALUE - Instant.now().getEpochSecond();

            String shortId = trace.getRequestId() != null && trace.getRequestId().length() >= 8
                    ? trace.getRequestId().substring(0, 8)
                    : trace.getRequestId();

            String fileName = String.format("%019d-%s-%s-%s-%s.json",
                    invertedEpoch,
                    trace.getOrigin(),
                    trace.getDestination(),
                    trace.getMonth(),
                    shortId);

            Path filePath = tracesDir.resolve(fileName);

            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(trace);
            Files.writeString(filePath, json);

            log.info("Trace written to {}", filePath.toAbsolutePath());
        } catch (IOException e) {
            log.warn("Failed to write trace file for request {}: {}", trace.getRequestId(), e.getMessage());
        }
    }
}
