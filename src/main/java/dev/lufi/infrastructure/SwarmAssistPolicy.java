package dev.lufi.infrastructure;

import java.time.Duration;
import java.util.Objects;

/**
 * Politica configuravel da participacao passiva em swarms. Ela nao se aplica
 * a downloads, streaming ou seeding voluntario do usuario.
 */
public record SwarmAssistPolicy(
        int maximumSwarms,
        Duration minimumResidence,
        double replacementThreshold,
        int criticalPeerCount,
        int maximumConnectionsPerSwarm,
        int maximumConnectionsTotal,
        Duration statsTtl,
        Duration emptySwarmDecay,
        Duration inactiveSwarmDecay) {

    public static final int DEFAULT_MAXIMUM_CONNECTIONS_PER_SWARM = 3;
    public static final int DEFAULT_MAXIMUM_CONNECTIONS_TOTAL = 75;

    public SwarmAssistPolicy {
        if (maximumSwarms < 1) throw new IllegalArgumentException("maximumSwarms deve ser maior que zero");
        minimumResidence = requireNonNegative(minimumResidence, "minimumResidence");
        if (replacementThreshold < 0d || replacementThreshold >= 1d) {
            throw new IllegalArgumentException("replacementThreshold deve estar entre zero e um");
        }
        if (criticalPeerCount < 0) throw new IllegalArgumentException("criticalPeerCount nao pode ser negativo");
        if (maximumConnectionsPerSwarm < 1) throw new IllegalArgumentException("maximumConnectionsPerSwarm deve ser maior que zero");
        if (maximumConnectionsTotal < maximumConnectionsPerSwarm) {
            throw new IllegalArgumentException("maximumConnectionsTotal deve comportar ao menos um swarm");
        }
        statsTtl = requireNonNegative(statsTtl, "statsTtl");
        emptySwarmDecay = requireNonNegative(emptySwarmDecay, "emptySwarmDecay");
        inactiveSwarmDecay = requireNonNegative(inactiveSwarmDecay, "inactiveSwarmDecay");
    }

    public static SwarmAssistPolicy defaults() {
        return new SwarmAssistPolicy(SwarmAssistSettings.DEFAULT_MAX_ASSIST_SWARMS,
                SwarmAssistSettings.DEFAULT_MIN_ASSIST_RESIDENCE,
                SwarmAssistSettings.DEFAULT_REPLACEMENT_THRESHOLD,
                SwarmAssistSettings.DEFAULT_CRITICAL_SWARM_PEER_COUNT,
                DEFAULT_MAXIMUM_CONNECTIONS_PER_SWARM, DEFAULT_MAXIMUM_CONNECTIONS_TOTAL,
                SwarmAssistSettings.SWARM_STATS_TTL, SwarmAssistSettings.DEFAULT_EMPTY_SWARM_DECAY,
                SwarmAssistSettings.DEFAULT_INACTIVE_SWARM_DECAY);
    }

    public static SwarmAssistPolicy from(SwarmAssistSettings settings) {
        if (settings == null) return defaults();
        return new SwarmAssistPolicy(settings.maxAssistSwarms(), settings.minAssistResidence(), settings.replacementThreshold(),
                settings.criticalSwarmPeerCount(), settings.maxAssistConnectionsPerSwarm(), settings.maxAssistConnectionsTotal(),
                settings.swarmStatsTtl(), settings.emptySwarmDecay(), settings.inactiveSwarmDecay());
    }

    private static Duration requireNonNegative(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative()) throw new IllegalArgumentException(name + " nao pode ser negativa");
        return value;
    }
}
