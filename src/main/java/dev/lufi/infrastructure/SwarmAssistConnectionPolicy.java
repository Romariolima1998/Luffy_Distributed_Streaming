package dev.lufi.infrastructure;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Limita somente a participação passiva. Downloads, streaming e seeds não são
 * contados aqui e usam a capacidade normal do motor BitTorrent. TCP e uTP do
 * mesmo peer compartilham uma vaga, pois são caminhos para o mesmo participante.
 */
final class SwarmAssistConnectionPolicy {
    static final int MAX_ASSIST_CONNECTIONS_PER_SWARM = SwarmAssistPolicy.DEFAULT_MAXIMUM_CONNECTIONS_PER_SWARM;
    static final int MAX_ASSIST_CONNECTIONS_TOTAL = SwarmAssistPolicy.DEFAULT_MAXIMUM_CONNECTIONS_TOTAL;
    private volatile SwarmAssistPolicy policy = SwarmAssistPolicy.defaults();

    enum AdmissionReason { ADMITTED, PER_SWARM_LIMIT, TOTAL_LIMIT }
    record Decision(boolean admitted, AdmissionReason reason, int occupiedInSwarm, int perSwarmLimit,
                    int occupiedTotal, int totalLimit) { }

    void setPolicy(SwarmAssistPolicy value) { policy = value == null ? SwarmAssistPolicy.defaults() : value; }

    Decision decide(String infoHash, PeerConnectivityManager.PeerEndpoint candidate,
                    Map<String, List<PeerConnectivityManager.PeerState>> assistStates) {
        SwarmAssistPolicy current = policy;
        Set<String> inSwarm = occupiedPeers(assistStates == null ? null : assistStates.get(infoHash));
        Set<String> total = new HashSet<>();
        if (assistStates != null) for (Map.Entry<String, List<PeerConnectivityManager.PeerState>> entry : assistStates.entrySet()) {
            for (String peer : occupiedPeers(entry.getValue())) total.add(entry.getKey().toLowerCase() + "|" + peer);
        }
        String candidateKey = peerKey(candidate);
        if (inSwarm.contains(candidateKey)) {
            return new Decision(true, AdmissionReason.ADMITTED, inSwarm.size(), current.maximumConnectionsPerSwarm(),
                    total.size(), current.maximumConnectionsTotal());
        }
        if (inSwarm.size() >= current.maximumConnectionsPerSwarm()) {
            return new Decision(false, AdmissionReason.PER_SWARM_LIMIT, inSwarm.size(), current.maximumConnectionsPerSwarm(),
                    total.size(), current.maximumConnectionsTotal());
        }
        if (total.size() >= current.maximumConnectionsTotal()) {
            return new Decision(false, AdmissionReason.TOTAL_LIMIT, inSwarm.size(), current.maximumConnectionsPerSwarm(),
                    total.size(), current.maximumConnectionsTotal());
        }
        return new Decision(true, AdmissionReason.ADMITTED, inSwarm.size(), current.maximumConnectionsPerSwarm(),
                total.size(), current.maximumConnectionsTotal());
    }

    int occupiedInSwarm(List<PeerConnectivityManager.PeerState> states) { return occupiedPeers(states).size(); }

    int occupiedTotal(Map<String, List<PeerConnectivityManager.PeerState>> assistStates) {
        if (assistStates == null) return 0;
        Set<String> total = new HashSet<>();
        for (Map.Entry<String, List<PeerConnectivityManager.PeerState>> entry : assistStates.entrySet()) {
            for (String peer : occupiedPeers(entry.getValue())) total.add(entry.getKey().toLowerCase() + "|" + peer);
        }
        return total.size();
    }

    private Set<String> occupiedPeers(List<PeerConnectivityManager.PeerState> states) {
        Set<String> occupied = new HashSet<>();
        if (states != null) for (PeerConnectivityManager.PeerState state : states) {
            if (occupiesLiveSlot(state)) occupied.add(peerKey(state.endpoint()));
        }
        return occupied;
    }

    private boolean occupiesLiveSlot(PeerConnectivityManager.PeerState state) {
        return switch (state.connection()) {
            case CONNECTED, DIRECT_CONNECTING, HOLE_PUNCH_PENDING, HOLE_PUNCHING -> true;
            // DIRECT_CONNECT_PENDING só ocupa vaga depois de ser despachado ao motor.
            case DIRECT_CONNECT_PENDING -> state.directAttempts() > 0;
            default -> false;
        };
    }

    private String peerKey(PeerConnectivityManager.PeerEndpoint endpoint) {
        return endpoint.addressFamily() + "|" + endpoint.address().getHostAddress() + "|" + endpoint.port();
    }
}
