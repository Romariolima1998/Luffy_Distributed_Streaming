package dev.lufi.infrastructure;

import java.time.Duration;
import java.util.Objects;

/** Configures the bounded startup of the shared DHT lookup runtimes. */
public record DhtLookupRuntimeSettings(Duration dhtStartupTimeout, Duration dhtRetryBackoff) {
    public static final Duration DEFAULT_DHT_STARTUP_TIMEOUT = Duration.ofSeconds(15);
    public static final Duration DEFAULT_DHT_RETRY_BACKOFF = Duration.ofSeconds(5);

    public DhtLookupRuntimeSettings {
        Objects.requireNonNull(dhtStartupTimeout, "dhtStartupTimeout");
        Objects.requireNonNull(dhtRetryBackoff, "dhtRetryBackoff");
        if (dhtStartupTimeout.isZero() || dhtStartupTimeout.isNegative()) {
            throw new IllegalArgumentException("dhtStartupTimeout must be positive");
        }
        if (dhtRetryBackoff.isZero() || dhtRetryBackoff.isNegative()) {
            throw new IllegalArgumentException("dhtRetryBackoff must be positive");
        }
    }

    public static DhtLookupRuntimeSettings defaults() {
        return new DhtLookupRuntimeSettings(DEFAULT_DHT_STARTUP_TIMEOUT, DEFAULT_DHT_RETRY_BACKOFF);
    }
}
