package dev.lufi.infrastructure;

import org.junit.jupiter.api.Test;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SwarmAssistStatsTest {
    private static final String INFO_HASH = "0123456789abcdef0123456789abcdef01234567";

    @Test void keepsPeerEstimateConnectionReachabilityAndFreshnessSeparate() throws Exception {
        Instant now = Instant.parse("2026-07-29T12:00:00Z");
        var direct = state("203.0.113.10", 6891, PeerConnectivityManager.Strategy.DIRECT_IPV4,
                PeerConnectivityManager.ConnectionState.CONNECTED, now.minusSeconds(3));
        var holePunch = state("203.0.113.11", 6891, PeerConnectivityManager.Strategy.HOLE_PUNCHING,
                PeerConnectivityManager.ConnectionState.CONNECTED, now.minusSeconds(1));
        var discovered = state("203.0.113.12", 6891, PeerConnectivityManager.Strategy.DIRECT_IPV4,
                PeerConnectivityManager.ConnectionState.DISCOVERED, now.minusSeconds(2));

        SwarmAssistStats stats = SwarmAssistStats.from(INFO_HASH, List.of(direct, holePunch, discovered), 1, now);

        assertEquals(3, stats.estimatedPeerCount());
        assertEquals(2, stats.connectedPeerCount());
        assertEquals(1, stats.holePunchCapablePeers());
        assertEquals(1, stats.usefulRendezvousPeerCount());
        assertEquals(1, stats.reachablePeers());
        assertTrue(stats.isFresh(Duration.ofMinutes(1), now.plusSeconds(10)));
    }

    private PeerConnectivityManager.PeerState state(String address, int port, PeerConnectivityManager.Strategy strategy,
                                                     PeerConnectivityManager.ConnectionState connection, Instant lastSeen) throws Exception {
        var endpoint = new PeerConnectivityManager.PeerEndpoint(PeerConnectivityManager.AddressFamily.IPV4,
                InetAddress.getByName(address), port, PeerConnectivityManager.Transport.TCP);
        return new PeerConnectivityManager.PeerState(INFO_HASH, endpoint, endpoint.addressFamily(),
                PeerConnectivityManager.TransportSupport.SUPPORTED, PeerConnectivityManager.TransportSupport.UNKNOWN,
                strategy, connection, 1, lastSeen, List.of(PeerConnectivityManager.DiscoveryOrigin.DHT), null, "", null);
    }
}
