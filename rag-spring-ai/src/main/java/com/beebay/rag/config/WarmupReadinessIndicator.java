package com.beebay.rag.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Manages the application's readiness state based on model warmup status.
 *
 * The application starts in REFUSING_TRAFFIC state and transitions to
 * ACCEPTING_TRAFFIC only after the embedding model warmup completes.
 *
 * This integrates with Spring Boot's health probes:
 * - /actuator/health/liveness  → Always UP if process is alive
 * - /actuator/health/readiness → DOWN until markReady() is called
 */
@Component
public class WarmupReadinessIndicator {

    private static final Logger log = LoggerFactory.getLogger(WarmupReadinessIndicator.class);

    private final ApplicationEventPublisher publisher;
    private volatile boolean ready = false;
    private volatile String failureReason = null;
    private volatile long warmupDurationMs = 0;
    private final long startTime = System.currentTimeMillis();

    public WarmupReadinessIndicator(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
        log.info("WarmupReadinessIndicator initialized - application will refuse traffic until warmup completes");
    }

    /**
     * Call this after warmup completes successfully.
     * Transitions readiness state to ACCEPTING_TRAFFIC.
     */
    public void markReady() {
        this.warmupDurationMs = System.currentTimeMillis() - startTime;
        this.ready = true;
        this.failureReason = null;

        publisher.publishEvent(new AvailabilityChangeEvent<>(
            this,
            ReadinessState.ACCEPTING_TRAFFIC
        ));

        log.info("Application readiness: ACCEPTING_TRAFFIC (warmup took {}ms)", warmupDurationMs);
    }

    /**
     * Call this if warmup fails.
     * Keeps readiness state as REFUSING_TRAFFIC.
     */
    public void markFailed(String reason) {
        this.warmupDurationMs = System.currentTimeMillis() - startTime;
        this.ready = false;
        this.failureReason = reason;

        publisher.publishEvent(new AvailabilityChangeEvent<>(
            this,
            ReadinessState.REFUSING_TRAFFIC
        ));

        log.error("Application readiness: REFUSING_TRAFFIC (warmup failed after {}ms: {})",
                  warmupDurationMs, reason);
    }

    public boolean isReady() {
        return ready;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public long getWarmupDurationMs() {
        return warmupDurationMs;
    }
}
