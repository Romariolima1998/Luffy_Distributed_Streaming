package dev.lufi.infrastructure;

import bt.net.InetPeer;
import dev.lufi.infrastructure.identity.LuffyNodeId;
import dev.lufi.infrastructure.identity.LuffyPeerCapabilities;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PeerConnectivityManagerTest {
    private static final String INFO_HASH = "0123456789012345678901234567890123456789";

    @Test void promotesPublicIpv4OnlyOnceDuringCooldown() throws Exception {
        List<PeerConnectivityManager.Promotion> promotions = new ArrayList<>();
        try (PeerConnectivityManager manager = new PeerConnectivityManager(new P2pDiagnostics(), promotions::add)) {
            var endpoint = new PeerConnectivityManager.PeerEndpoint(InetAddress.getByName("203.0.113.9"), 6891);

            manager.onDhtPeerDiscovered(INFO_HASH, endpoint);
            manager.onDhtPeerDiscovered(INFO_HASH, endpoint);
            Thread.sleep(150);

            assertEquals(1, promotions.size());
            var state = manager.peersFor(INFO_HASH).getFirst();
            assertEquals(PeerConnectivityManager.Strategy.DIRECT_IPV4, state.strategy());
            assertEquals(PeerConnectivityManager.ConnectionState.DIRECT_CONNECT_PENDING, state.connection());
            assertEquals(PeerConnectivityManager.TransportSupport.UNKNOWN, state.utp());
        }
    }

    @Test void acceptsPrivateLanEndpointOnlyWhenItCameFromExplicitMagnetPeerHint() throws Exception {
        List<PeerConnectivityManager.Promotion> promotions = new ArrayList<>();
        try (PeerConnectivityManager manager = new PeerConnectivityManager(new P2pDiagnostics(), promotions::add)) {
            var peer = InetPeer.build(InetAddress.getByName("192.168.11.194"), 6891);

            manager.onMagnetMetadataPeerDiscovered(INFO_HASH, peer);
            Thread.sleep(150);

            assertEquals(1, promotions.size());
            assertEquals(PeerConnectivityManager.Strategy.DIRECT_IPV4, manager.peersFor(INFO_HASH).getFirst().strategy());
        }
    }

    @Test void blocksIpv6PeerWhenLocalPeerHasNoGlobalIpv6() throws Exception {
        List<PeerConnectivityManager.Promotion> promotions = new ArrayList<>();
        try (PeerConnectivityManager manager = new PeerConnectivityManager(new P2pDiagnostics(), promotions::add)) {
            var endpoint = new PeerConnectivityManager.PeerEndpoint(InetAddress.getByName("2001:db8::42"), 6891);

            manager.onDhtPeerDiscovered(INFO_HASH, endpoint);

            assertTrue(promotions.isEmpty());
            var state = manager.peersFor(INFO_HASH).getFirst();
            assertEquals(PeerConnectivityManager.AddressFamily.IPV6, state.family());
            assertEquals(PeerConnectivityManager.ConnectionState.UNREACHABLE, state.connection());
        }
    }

    @Test void separatesDiscoveryTcpAndHandshakeStates() throws Exception {
        P2pDiagnostics diagnostics = new P2pDiagnostics();
        try (PeerConnectivityManager manager = new PeerConnectivityManager(diagnostics, ignored -> { })) {
            var address = InetAddress.getByName("203.0.113.20");
            var endpoint = new PeerConnectivityManager.PeerEndpoint(address, 6891);
            var peer = InetPeer.build(address, 6891);

            manager.onDhtPeerDiscovered(INFO_HASH, endpoint);
            assertEquals(PeerConnectivityManager.ConnectionState.DIRECT_CONNECT_PENDING, manager.peersFor(INFO_HASH).getFirst().connection());

            manager.onTcpConnectStart(INFO_HASH, peer);
            assertEquals(PeerConnectivityManager.ConnectionState.DIRECT_CONNECTING, manager.peersFor(INFO_HASH).getFirst().connection());

            manager.onTcpConnectSuccess(INFO_HASH, peer, 6891);
            manager.onBittorrentHandshakeStart(INFO_HASH, peer, 6891);
            manager.onBittorrentHandshakeSuccess(INFO_HASH, peer, 6891);

            assertEquals(PeerConnectivityManager.ConnectionState.CONNECTED, manager.peersFor(INFO_HASH).getFirst().connection());
            assertTrue(diagnostics.snapshot().contains("PEER DISCOVERED"));
            assertTrue(diagnostics.snapshot().contains("TCP CONNECT START"));
            assertTrue(diagnostics.snapshot().contains("TCP CONNECT SUCCESS"));
            assertTrue(diagnostics.snapshot().contains("BITTORRENT HANDSHAKE START"));
            assertTrue(diagnostics.snapshot().contains("BITTORRENT HANDSHAKE SUCCESS"));
        }
    }

    @Test void recordsSocketFailureKindAndAttemptDuration() throws Exception {
        P2pDiagnostics diagnostics = new P2pDiagnostics();
        try (PeerConnectivityManager manager = new PeerConnectivityManager(diagnostics, ignored -> { })) {
            var address = InetAddress.getByName("203.0.113.30");
            var endpoint = new PeerConnectivityManager.PeerEndpoint(address, 6891);
            var peer = InetPeer.build(address, 6891);

            manager.onDhtPeerDiscovered(INFO_HASH, endpoint);
            manager.onTcpConnectStart(INFO_HASH, peer);
            manager.onTcpConnectFailure(INFO_HASH, peer, new ConnectException("Connection refused"));

            var attempt = manager.peersFor(INFO_HASH).getFirst().lastSocketAttempt();
            assertEquals(PeerConnectivityManager.ConnectionState.DIRECT_CONNECT_FAILED, manager.peersFor(INFO_HASH).getFirst().connection());
            assertEquals(PeerConnectivityManager.SocketFailure.CONNECTION_REFUSED, attempt.failure());
            assertTrue(attempt.durationMillis() >= 0);
            assertTrue(diagnostics.snapshot().contains("infoHash=" + INFO_HASH));
            assertTrue(diagnostics.snapshot().contains("TCP CONNECT FAILED"));
        }
    }

    @Test void recordsTimeoutWithoutWaitingForTheRealSocketDeadline() throws Exception {
        try (PeerConnectivityManager manager = new PeerConnectivityManager(new P2pDiagnostics(), ignored -> { })) {
            var address = InetAddress.getByName("203.0.113.32");
            var peer = InetPeer.build(address, 6891);

            manager.onDhtPeerDiscovered(INFO_HASH, new PeerConnectivityManager.PeerEndpoint(address, 6891));
            manager.onTcpConnectStart(INFO_HASH, peer);
            manager.onTcpConnectFailure(INFO_HASH, peer, new SocketTimeoutException("connect timed out"));

            var state = manager.peersFor(INFO_HASH).getFirst();
            assertEquals(PeerConnectivityManager.ConnectionState.DIRECT_CONNECT_FAILED, state.connection());
            assertEquals(PeerConnectivityManager.SocketFailure.TIMEOUT, state.lastSocketAttempt().failure());
            assertTrue(state.nextRetryAt().isAfter(java.time.Instant.now()));
        }
    }

    @Test void exposesNatAndHolePunchStateTransitionsWithoutMixingThemWithDiscovery() throws Exception {
        try (PeerConnectivityManager manager = new PeerConnectivityManager(new P2pDiagnostics(), ignored -> { })) {
            var endpoint = new PeerConnectivityManager.PeerEndpoint(InetAddress.getByName("203.0.113.33"), 43127,
                    PeerConnectivityManager.Transport.UTP);

            manager.markPortMappingPending(INFO_HASH, endpoint);
            assertEquals(PeerConnectivityManager.ConnectionState.PORT_MAPPING_PENDING, manager.peersFor(INFO_HASH).getFirst().connection());
            assertEquals(PeerConnectivityManager.Strategy.NAT_MAPPING, manager.peersFor(INFO_HASH).getFirst().strategy());

            manager.markHolePunchPending(INFO_HASH, endpoint);
            assertEquals(PeerConnectivityManager.ConnectionState.HOLE_PUNCH_PENDING, manager.peersFor(INFO_HASH).getFirst().connection());
            manager.markHolePunching(INFO_HASH, endpoint);
            assertEquals(PeerConnectivityManager.ConnectionState.HOLE_PUNCHING, manager.peersFor(INFO_HASH).getFirst().connection());
        }
    }

    @Test void appliesEndpointBackoffAndSuppressesRepeatedDhtDiscovery() throws Exception {
        List<PeerConnectivityManager.Promotion> promotions = new ArrayList<>();
        P2pDiagnostics diagnostics = new P2pDiagnostics();
        try (PeerConnectivityManager manager = new PeerConnectivityManager(diagnostics, promotions::add)) {
            var address = InetAddress.getByName("203.0.113.35");
            var endpoint = new PeerConnectivityManager.PeerEndpoint(address, 6891);
            var peer = InetPeer.build(address, 6891);

            manager.onDhtPeerDiscovered(INFO_HASH, endpoint);
            Thread.sleep(150);
            assertEquals(1, promotions.size());

            manager.onTcpConnectStart(INFO_HASH, peer);
            manager.onTcpConnectFailure(INFO_HASH, peer, new ConnectException("Connection refused"));

            var afterFailure = manager.peersFor(INFO_HASH).getFirst();
            assertEquals(1, afterFailure.directAttempts());
            assertTrue(afterFailure.nextRetryAt().isAfter(java.time.Instant.now().plusSeconds(3)));

            manager.onDhtPeerDiscovered(INFO_HASH, endpoint);
            manager.onDhtPeerDiscovered(INFO_HASH, endpoint);
            Thread.sleep(150);

            assertEquals(1, promotions.size());
            assertTrue(diagnostics.snapshot().contains("PEER RETRY BACKOFF: infoHash=" + INFO_HASH));
            assertTrue(diagnostics.snapshot().contains("PEER RETRY SUPPRESSED: infoHash=" + INFO_HASH));
        }
    }

    @Test void closesHolePunchAttemptWhenNoRendezvousPeerExists() throws Exception {
        try (PeerConnectivityManager manager = new PeerConnectivityManager(new P2pDiagnostics(), ignored -> { })) {
            var endpoint = new PeerConnectivityManager.PeerEndpoint(InetAddress.getByName("203.0.113.31"), 43817,
                    PeerConnectivityManager.Transport.UTP);

            manager.onHolePunchUnavailable(INFO_HASH, endpoint, "nenhum peer rendezvous conectado ao target");

            var state = manager.peersFor(INFO_HASH).getFirst();
            assertEquals(PeerConnectivityManager.ConnectionState.UNREACHABLE, state.connection());
            assertEquals(PeerConnectivityManager.Strategy.HOLE_PUNCHING, state.strategy());
            assertEquals("nenhum peer rendezvous conectado ao target", state.failureReason());
        }
    }

    @Test void triesDirectUtpBeforeRequestingBep55AfterTcpFailure() throws Exception {
        List<PeerConnectivityManager.Promotion> promotions = new ArrayList<>();
        AtomicReference<PeerConnectivityManager.PeerEndpoint> holePunchTarget = new AtomicReference<>();
        P2pDiagnostics diagnostics = new P2pDiagnostics();
        try (PeerConnectivityManager manager = new PeerConnectivityManager(diagnostics, promotions::add)) {
            var address = InetAddress.getByName("203.0.113.36");
            var tcp = new PeerConnectivityManager.PeerEndpoint(address, 43127);
            var explicitUtp = new PeerConnectivityManager.PeerEndpoint(address, 51234, PeerConnectivityManager.Transport.UTP);
            var peer = InetPeer.build(address, 43127);
            manager.setHolePunchRequester((ignored, endpoint) -> holePunchTarget.set(endpoint));

            manager.onPeerEndpointDiscovered(INFO_HASH, explicitUtp, PeerConnectivityManager.DiscoveryOrigin.PEX);
            manager.setPathAvailable(PeerConnectivityManager.AddressFamily.IPV4, PeerConnectivityManager.Transport.UTP, true);

            manager.onDhtPeerDiscovered(INFO_HASH, tcp);
            Thread.sleep(150);
            manager.onTcpConnectStart(INFO_HASH, peer);
            manager.onTcpConnectFailure(INFO_HASH, peer, new SocketTimeoutException("connect timed out"));

            assertEquals(2, promotions.size());
            assertEquals(PeerConnectivityManager.Transport.UTP, promotions.get(1).endpoint().transport());
            assertEquals(51234, promotions.get(1).endpoint().port());
            assertEquals(PeerConnectivityManager.Strategy.DIRECT_UTP, promotions.get(1).strategy());
            assertEquals(null, holePunchTarget.get());

            var utp = promotions.get(1).endpoint();
            manager.onUtpFailure(INFO_HASH, utp, new SocketTimeoutException("uTP timed out"));

            assertEquals(utp, holePunchTarget.get());
            assertTrue(diagnostics.snapshot().contains("[CONNECTIVITY] FALLBACK SELECTED"));
            assertTrue(diagnostics.snapshot().contains("[HOLEPUNCH] RENDEZVOUS LOOKUP"));
        }
    }

    @Test void neverConvertsATcpDiscoveryPortIntoAnUtpEndpoint() throws Exception {
        List<PeerConnectivityManager.Promotion> promotions = new ArrayList<>();
        AtomicReference<PeerConnectivityManager.PeerEndpoint> holePunchTarget = new AtomicReference<>();
        P2pDiagnostics diagnostics = new P2pDiagnostics();
        try (PeerConnectivityManager manager = new PeerConnectivityManager(diagnostics, promotions::add)) {
            var address = InetAddress.getByName("203.0.113.37");
            var tcp = new PeerConnectivityManager.PeerEndpoint(address, 43127);
            var peer = InetPeer.build(address, 43127);
            manager.setPathAvailable(PeerConnectivityManager.AddressFamily.IPV4, PeerConnectivityManager.Transport.UTP, true);
            manager.setHolePunchRequester((ignored, endpoint) -> holePunchTarget.set(endpoint));

            manager.onDhtPeerDiscovered(INFO_HASH, tcp);
            Thread.sleep(150);
            manager.onTcpConnectStart(INFO_HASH, peer);
            manager.onTcpConnectFailure(INFO_HASH, peer, new SocketTimeoutException("connect timed out"));

            assertEquals(1, promotions.size());
            assertEquals(null, holePunchTarget.get());
            assertTrue(diagnostics.snapshot().contains("nenhum endpoint UDP/uTP foi anunciado independentemente da porta TCP"));
        }
    }

    @Test void preservesEveryDiscoveryOriginForTheSamePeer() throws Exception {
        try (PeerConnectivityManager manager = new PeerConnectivityManager(new P2pDiagnostics(), ignored -> { })) {
            var peer = InetPeer.build(InetAddress.getByName("203.0.113.50"), 6891);

            manager.onPexPeerDiscovered(INFO_HASH, peer);
            manager.onDhtPeerDiscovered(INFO_HASH, peer);

            assertEquals(1, manager.peersFor(INFO_HASH).size());
            var origins = manager.peersFor(INFO_HASH).getFirst().origins();
            assertTrue(origins.contains(PeerConnectivityManager.DiscoveryOrigin.PEX));
            assertTrue(origins.contains(PeerConnectivityManager.DiscoveryOrigin.DHT));
        }
    }

    @Test void staggersPathsAndCancelsScheduledFallbackAfterHandshakeWinner() throws Exception {
        List<PeerConnectivityManager.Promotion> promotions = new ArrayList<>();
        try (PeerConnectivityManager manager = new PeerConnectivityManager(new P2pDiagnostics(), promotions::add)) {
            var address = InetAddress.getByName("203.0.113.60");
            var tcp = new PeerConnectivityManager.PeerEndpoint(address, 6891, PeerConnectivityManager.Transport.TCP);
            var utp = new PeerConnectivityManager.PeerEndpoint(address, 6891, PeerConnectivityManager.Transport.UTP);
            manager.setPathAvailable(PeerConnectivityManager.AddressFamily.IPV4, PeerConnectivityManager.Transport.UTP, true);

            manager.onPeerEndpointDiscovered(INFO_HASH, tcp, PeerConnectivityManager.DiscoveryOrigin.DHT);
            manager.onPeerEndpointDiscovered(INFO_HASH, utp, PeerConnectivityManager.DiscoveryOrigin.PEX);
            Thread.sleep(180); // após a janela de 75 ms, antes do fallback de 300 ms

            assertEquals(1, promotions.size());
            assertEquals(PeerConnectivityManager.Transport.TCP, promotions.getFirst().endpoint().transport());
            manager.onBittorrentHandshakeSuccess(INFO_HASH, InetPeer.build(address, 6891), 6891);
            Thread.sleep(400);
            assertEquals(1, promotions.size());
        }
    }

    @Test void preservesIndependentIpv4Ipv6TcpAndUtpEndpoints() throws Exception {
        try (PeerConnectivityManager manager = new PeerConnectivityManager(new P2pDiagnostics(), ignored -> { })) {
            var ipv4 = InetAddress.getByName("203.0.113.44");
            var ipv6 = InetAddress.getByName("2001:db8::44");

            manager.onPeerEndpointDiscovered(INFO_HASH, new PeerConnectivityManager.PeerEndpoint(ipv4, 6891, PeerConnectivityManager.Transport.TCP));
            manager.onPeerEndpointDiscovered(INFO_HASH, new PeerConnectivityManager.PeerEndpoint(ipv4, 6891, PeerConnectivityManager.Transport.UTP));
            manager.onPeerEndpointDiscovered(INFO_HASH, new PeerConnectivityManager.PeerEndpoint(ipv6, 6891, PeerConnectivityManager.Transport.TCP));
            manager.onPeerEndpointDiscovered(INFO_HASH, new PeerConnectivityManager.PeerEndpoint(ipv6, 6891, PeerConnectivityManager.Transport.UTP));

            var endpoints = manager.endpointsFor(INFO_HASH);
            assertEquals(4, endpoints.size());
            assertTrue(endpoints.stream().anyMatch(endpoint -> endpoint.addressFamily() == PeerConnectivityManager.AddressFamily.IPV4
                    && endpoint.transport() == PeerConnectivityManager.Transport.TCP));
            assertTrue(endpoints.stream().anyMatch(endpoint -> endpoint.addressFamily() == PeerConnectivityManager.AddressFamily.IPV4
                    && endpoint.transport() == PeerConnectivityManager.Transport.UTP));
            assertTrue(endpoints.stream().anyMatch(endpoint -> endpoint.addressFamily() == PeerConnectivityManager.AddressFamily.IPV6
                    && endpoint.transport() == PeerConnectivityManager.Transport.TCP));
            assertTrue(endpoints.stream().anyMatch(endpoint -> endpoint.addressFamily() == PeerConnectivityManager.AddressFamily.IPV6
                    && endpoint.transport() == PeerConnectivityManager.Transport.UTP));
        }
    }

    @Test void startsOverlayOnlyAfterTheLocalBep55PathHasBeenExhausted() throws Exception {
        List<PeerConnectivityManager.Promotion> promotions = new ArrayList<>();
        AtomicReference<PeerConnectivityManager.PeerConnectivityContext> context = new AtomicReference<>();
        UUID sessionId = UUID.randomUUID();
        try (PeerConnectivityManager manager = new PeerConnectivityManager(new P2pDiagnostics(), promotions::add)) {
            var address = InetAddress.getByName("203.0.113.81");
            var tcp = new PeerConnectivityManager.PeerEndpoint(address, 43_127);
            var utp = new PeerConnectivityManager.PeerEndpoint(address, 51_234, PeerConnectivityManager.Transport.UTP);
            var peer = InetPeer.build(address, 43_127);
            var nodeId = nodeId(9);
            var capabilities = new LuffyPeerCapabilities(1, nodeId, "test", true, true, true, true);
            manager.setTorrentActivity(ignored -> true);
            manager.setHolePunchRequester((ignored, endpoint) -> { });
            manager.setOverlayRendezvousFallback(candidate -> {
                context.set(candidate);
                return java.util.concurrent.CompletableFuture.completedFuture(
                        PeerConnectivityManager.OverlayRendezvousResult.started(sessionId));
            });

            manager.onPeerEndpointDiscovered(INFO_HASH, utp, PeerConnectivityManager.DiscoveryOrigin.PEX);
            manager.setPathAvailable(PeerConnectivityManager.AddressFamily.IPV4, PeerConnectivityManager.Transport.UTP, true);
            manager.onDhtPeerDiscovered(INFO_HASH, tcp);
            Thread.sleep(150);
            manager.onLuffyIdentity(INFO_HASH, peer, 43_127, capabilities);
            manager.onTcpConnectStart(INFO_HASH, peer);
            manager.onTcpConnectFailure(INFO_HASH, peer, new SocketTimeoutException("tcp timed out"));
            manager.onUtpFailure(INFO_HASH, utp, new SocketTimeoutException("utp timed out"));

            assertEquals(2, promotions.size(), "uTP direto precisa anteceder BEP55 local/overlay");
            assertEquals(PeerConnectivityManager.Strategy.DIRECT_UTP, promotions.get(1).strategy());
            manager.onHolePunchUnavailable(INFO_HASH, utp, "nenhum relay BEP55 no swarm de conteudo");

            assertEquals(nodeId, context.get().targetNodeId().orElseThrow());
            assertEquals(utp, context.get().targetEndpoint());
            assertTrue(context.get().targetCapabilities().orElseThrow().supportsDistributedRendezvous());
            var overlayState = manager.peersFor(INFO_HASH).stream().filter(state -> state.endpoint().equals(utp)).findFirst().orElseThrow();
            assertEquals(PeerConnectivityManager.ConnectionState.HOLE_PUNCH_PENDING, overlayState.connection());

            manager.onOverlayRendezvousFinished(context.get(), sessionId, "FAILED", "timeout de rendezvous");
            var failed = manager.peersFor(INFO_HASH).stream().filter(state -> state.endpoint().equals(utp)).findFirst().orElseThrow();
            assertEquals(PeerConnectivityManager.ConnectionState.UNREACHABLE, failed.connection());
            assertTrue(failed.nextRetryAt().isAfter(Instant.now()));
        }
    }

    private static LuffyNodeId nodeId(int value) {
        byte[] bytes = new byte[LuffyNodeId.BINARY_LENGTH];
        Arrays.fill(bytes, (byte) value);
        return LuffyNodeId.fromBinary(bytes);
    }
}
