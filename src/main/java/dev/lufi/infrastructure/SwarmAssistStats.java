package dev.lufi.infrastructure;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Snapshot de conectividade usado pela Swarm Assist List; não toma decisões de retenção por si só. */
public record SwarmAssistStats(
        String infoHash,
        int estimatedPeerCount,
        int connectedPeerCount,
        int holePunchCapablePeers,
        int reachablePeers,
        Instant lastObservedAt) {

    public SwarmAssistStats {
        if (infoHash == null || !infoHash.matches("(?i)[a-f0-9]{40}")) throw new IllegalArgumentException("infoHash inválido");
        estimatedPeerCount = Math.max(0, estimatedPeerCount);
        connectedPeerCount = Math.max(0, connectedPeerCount);
        holePunchCapablePeers = Math.max(0, holePunchCapablePeers);
        reachablePeers = Math.max(0, reachablePeers);
        lastObservedAt = lastObservedAt == null ? Instant.EPOCH : lastObservedAt;
    }

    /**
     * Quantidade de peers conectados e úteis para BEP 55: negociação BEP 10
     * concluída, uTP e ut_holepunch anunciados. Não representa todos os peers
     * conhecidos do swarm.
     */
    public int usefulRendezvousPeerCount() { return holePunchCapablePeers; }

    /** A frescura deve ser considerada por etapas futuras ao decidir qual swarm precisa de assistência. */
    public boolean isFresh(Duration maximumAge, Instant now) {
        if (maximumAge == null || maximumAge.isNegative()) return false;
        return !lastObservedAt.plus(maximumAge).isBefore(now == null ? Instant.now() : now);
    }

    static SwarmAssistStats from(String infoHash, List<PeerConnectivityManager.PeerState> states,
                                 int usefulRendezvousPeers, Instant observedAt) {
        Map<String, PeerConnectivityManager.PeerState> peers = new LinkedHashMap<>();
        if (states != null) for (PeerConnectivityManager.PeerState state : states) {
            String key = state.endpoint().addressFamily() + "|" + state.endpoint().address().getHostAddress() + "|" + state.endpoint().port();
            peers.merge(key, state, (first, second) -> first.lastSeen().isAfter(second.lastSeen()) ? first : second);
        }
        int connected = (int) peers.values().stream()
                .filter(state -> state.connection() == PeerConnectivityManager.ConnectionState.CONNECTED).count();
        int reachable = (int) peers.values().stream()
                .filter(state -> state.connection() == PeerConnectivityManager.ConnectionState.CONNECTED)
                .filter(state -> state.strategy() != PeerConnectivityManager.Strategy.HOLE_PUNCHING).count();
        return new SwarmAssistStats(infoHash, peers.size(), connected, usefulRendezvousPeers, reachable,
                observedAt == null ? Instant.now() : observedAt);
    }
}
