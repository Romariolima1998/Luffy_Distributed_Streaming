package dev.lufi.infrastructure;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SwarmAssistConnectionPolicyTest {
    private static final String INFO_HASH = "0123456789abcdef0123456789abcdef01234567";

    @Test void keepsOnlyThreeLivePeerSlotsPerPassiveSwarm() throws Exception {
        SwarmAssistConnectionPolicy policy = new SwarmAssistConnectionPolicy();
        List<PeerConnectivityManager.PeerState> active = List.of(
                state("203.0.113.1", 6891, PeerConnectivityManager.Transport.TCP, PeerConnectivityManager.ConnectionState.CONNECTED, 1),
                state("203.0.113.2", 6891, PeerConnectivityManager.Transport.TCP, PeerConnectivityManager.ConnectionState.CONNECTED, 1),
                state("203.0.113.3", 6891, PeerConnectivityManager.Transport.TCP, PeerConnectivityManager.ConnectionState.HOLE_PUNCHING, 1));

        var candidate = endpoint("203.0.113.5", 6891, PeerConnectivityManager.Transport.TCP);
        var decision = policy.decide("swarm-a", candidate, Map.of("swarm-a", active));

        assertEquals(3, policy.occupiedInSwarm(active));
        assertFalse(decision.admitted());
        assertEquals(SwarmAssistConnectionPolicy.AdmissionReason.PER_SWARM_LIMIT, decision.reason());
        assertEquals(SwarmAssistConnectionPolicy.MAX_ASSIST_CONNECTIONS_PER_SWARM, decision.perSwarmLimit());
    }

    @Test void allowsTcpToUtpFallbackForAnAlreadyAdmittedPeerWithoutOpeningAnotherSlot() throws Exception {
        SwarmAssistConnectionPolicy policy = new SwarmAssistConnectionPolicy();
        List<PeerConnectivityManager.PeerState> active = List.of(
                state("203.0.113.1", 6891, PeerConnectivityManager.Transport.TCP, PeerConnectivityManager.ConnectionState.CONNECTED, 1),
                state("203.0.113.2", 6891, PeerConnectivityManager.Transport.TCP, PeerConnectivityManager.ConnectionState.CONNECTED, 1),
                state("203.0.113.3", 6891, PeerConnectivityManager.Transport.TCP, PeerConnectivityManager.ConnectionState.CONNECTED, 1));

        assertTrue(policy.decide("swarm-a", endpoint("203.0.113.1", 6891, PeerConnectivityManager.Transport.UTP),
                Map.of("swarm-a", active)).admitted());
    }

    @Test void doesNotConsumeASlotForPeersOnlyQueuedByDiscovery() throws Exception {
        SwarmAssistConnectionPolicy policy = new SwarmAssistConnectionPolicy();
        List<PeerConnectivityManager.PeerState> discovered = List.of(
                state("203.0.113.1", 6891, PeerConnectivityManager.Transport.TCP,
                        PeerConnectivityManager.ConnectionState.DIRECT_CONNECT_PENDING, 0));

        assertEquals(0, policy.occupiedInSwarm(discovered));
        assertTrue(policy.decide("swarm-a", endpoint("203.0.113.2", 6891, PeerConnectivityManager.Transport.TCP),
                Map.of("swarm-a", discovered)).admitted());
    }

    @Test void enforcesTheGlobalAssistBudgetSeparatelyFromPerSwarmBudget() throws Exception {
        SwarmAssistConnectionPolicy policy = new SwarmAssistConnectionPolicy();
        Map<String, List<PeerConnectivityManager.PeerState>> allAssists = new LinkedHashMap<>();
        for (int swarm = 0; swarm < 25; swarm++) {
            allAssists.put("swarm-" + swarm, List.of(
                    state("198.18." + swarm + ".1", 6891, PeerConnectivityManager.Transport.TCP, PeerConnectivityManager.ConnectionState.CONNECTED, 1),
                    state("198.18." + swarm + ".2", 6891, PeerConnectivityManager.Transport.TCP, PeerConnectivityManager.ConnectionState.CONNECTED, 1),
                    state("198.18." + swarm + ".3", 6891, PeerConnectivityManager.Transport.TCP, PeerConnectivityManager.ConnectionState.CONNECTED, 1)));
        }

        var decision = policy.decide("new-swarm", endpoint("198.19.1.1", 6891, PeerConnectivityManager.Transport.TCP), allAssists);

        assertEquals(SwarmAssistConnectionPolicy.MAX_ASSIST_CONNECTIONS_TOTAL, policy.occupiedTotal(allAssists));
        assertFalse(decision.admitted());
        assertEquals(SwarmAssistConnectionPolicy.AdmissionReason.TOTAL_LIMIT, decision.reason());
    }

    @Test void appliesTheConnectionBudgetFromTheSwarmAssistPolicy() throws Exception {
        SwarmAssistConnectionPolicy policy = new SwarmAssistConnectionPolicy();
        policy.setPolicy(new SwarmAssistPolicy(25, java.time.Duration.ofMinutes(30), .20d, 3,
                2, 4, java.time.Duration.ofMinutes(10), java.time.Duration.ofHours(6), java.time.Duration.ofDays(7)));
        List<PeerConnectivityManager.PeerState> active = List.of(
                state("203.0.113.1", 6891, PeerConnectivityManager.Transport.TCP, PeerConnectivityManager.ConnectionState.CONNECTED, 1),
                state("203.0.113.2", 6891, PeerConnectivityManager.Transport.TCP, PeerConnectivityManager.ConnectionState.CONNECTED, 1));

        var decision = policy.decide("swarm-a", endpoint("203.0.113.3", 6891, PeerConnectivityManager.Transport.TCP),
                Map.of("swarm-a", active));

        assertFalse(decision.admitted());
        assertEquals(SwarmAssistConnectionPolicy.AdmissionReason.PER_SWARM_LIMIT, decision.reason());
        assertEquals(2, decision.perSwarmLimit());
        assertEquals(4, decision.totalLimit());
    }

    private PeerConnectivityManager.PeerState state(String address, int port, PeerConnectivityManager.Transport transport,
                                                     PeerConnectivityManager.ConnectionState connection, int attempts) throws Exception {
        var endpoint = endpoint(address, port, transport);
        return new PeerConnectivityManager.PeerState(INFO_HASH, endpoint, endpoint.addressFamily(),
                PeerConnectivityManager.TransportSupport.UNKNOWN, PeerConnectivityManager.TransportSupport.UNKNOWN,
                PeerConnectivityManager.Strategy.NONE, connection, attempts, Instant.now(), List.of(), null, "", null);
    }

    private PeerConnectivityManager.PeerEndpoint endpoint(String address, int port, PeerConnectivityManager.Transport transport) throws Exception {
        return new PeerConnectivityManager.PeerEndpoint(PeerConnectivityManager.AddressFamily.IPV4,
                InetAddress.getByName(address), port, transport);
    }
}
