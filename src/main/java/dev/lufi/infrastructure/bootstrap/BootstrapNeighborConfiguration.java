package dev.lufi.infrastructure.bootstrap;

import java.time.Duration;
import java.util.Objects;

/**
 * Limites e cadencia da malha de vizinhos do swarm oficial. A configuracao
 * pertence somente ao overlay: ela nao altera os limites de downloads, seeds
 * ou Swarm Assist.
 */
public record BootstrapNeighborConfiguration(
        int minimumNeighbors,
        int targetNeighbors,
        int maximumNeighbors,
        int maximumPendingAttempts,
        Duration discoveryBackoff,
        Duration renewalInterval) {

    public static final int DEFAULT_MINIMUM_NEIGHBORS = 6;
    public static final int DEFAULT_TARGET_NEIGHBORS = 12;
    public static final int DEFAULT_MAXIMUM_NEIGHBORS = 20;
    public static final int DEFAULT_MAXIMUM_PENDING_ATTEMPTS = 2;
    public static final Duration DEFAULT_DISCOVERY_BACKOFF = Duration.ofSeconds(30);
    public static final Duration DEFAULT_RENEWAL_INTERVAL = Duration.ofMinutes(10);

    public BootstrapNeighborConfiguration {
        if (minimumNeighbors < 0) throw new IllegalArgumentException("minimumNeighbors nao pode ser negativo");
        if (targetNeighbors < minimumNeighbors) throw new IllegalArgumentException("targetNeighbors deve ser maior ou igual ao minimo");
        if (maximumNeighbors < targetNeighbors) throw new IllegalArgumentException("maximumNeighbors deve ser maior ou igual ao alvo");
        if (maximumPendingAttempts < 1) throw new IllegalArgumentException("maximumPendingAttempts deve ser positivo");
        discoveryBackoff = positive(discoveryBackoff, "discoveryBackoff");
        renewalInterval = positive(renewalInterval, "renewalInterval");
    }

    public static BootstrapNeighborConfiguration defaults() {
        return new BootstrapNeighborConfiguration(DEFAULT_MINIMUM_NEIGHBORS, DEFAULT_TARGET_NEIGHBORS,
                DEFAULT_MAXIMUM_NEIGHBORS, DEFAULT_MAXIMUM_PENDING_ATTEMPTS,
                DEFAULT_DISCOVERY_BACKOFF, DEFAULT_RENEWAL_INTERVAL);
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) throw new IllegalArgumentException(name + " deve ser positivo");
        return value;
    }
}
