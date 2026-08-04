package dev.lufi.infrastructure.rendezvous;

import dev.lufi.infrastructure.ObservedEndpoint;
import dev.lufi.infrastructure.identity.LuffyNodeId;
import dev.lufi.infrastructure.identity.LuffyPeerCapabilities;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Fotografia local de um peer que poderia coordenar um rendezvous. Ela nunca
 * representa um relay de metadata, pieces ou arquivos.
 */
public record RendezvousCandidate(
        LuffyNodeId rendezvousNodeId,
        LuffyNodeId targetNodeId,
        int routeDistance,
        LuffyPeerCapabilities capabilities,
        Duration estimatedLatency,
        int activeSessions,
        boolean server,
        Optional<ObservedEndpoint> udpEndpoint,
        boolean controlConnectionActive,
        boolean targetConnectionActive,
        boolean targetConnectionInContentSwarm,
        boolean inBackoff,
        boolean overloaded,
        boolean blocked,
        Instant stableSince) {

    public RendezvousCandidate {
        Objects.requireNonNull(rendezvousNodeId, "rendezvousNodeId");
        Objects.requireNonNull(targetNodeId, "targetNodeId");
        if (rendezvousNodeId.equals(targetNodeId)) {
            throw new IllegalArgumentException("O alvo nao pode ser seu proprio rendezvous");
        }
        if (routeDistance < 1 || routeDistance > 255) throw new IllegalArgumentException("distancia de rota invalida");
        Objects.requireNonNull(capabilities, "capabilities");
        Objects.requireNonNull(estimatedLatency, "estimatedLatency");
        if (estimatedLatency.isNegative()) throw new IllegalArgumentException("latencia estimada invalida");
        if (activeSessions < 0) throw new IllegalArgumentException("sessoes ativas invalidas");
        udpEndpoint = udpEndpoint == null ? Optional.empty() : udpEndpoint;
        Objects.requireNonNull(stableSince, "stableSince");
    }
}
