package dev.lufi.infrastructure.bootstrap;

import bt.metainfo.TorrentId;
import bt.net.ConnectionKey;
import bt.net.InetPeer;
import bt.net.Peer;
import bt.net.PeerConnection;
import bt.protocol.Message;
import dev.lufi.infrastructure.P2pDiagnostics;
import dev.lufi.infrastructure.identity.ConnectedLuffyRegistry;
import dev.lufi.infrastructure.identity.LuffyNodeId;
import dev.lufi.infrastructure.identity.LuffyPeerCapabilities;
import java.io.IOException;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BootstrapNeighborManagerTest {
    private static final TorrentId BOOTSTRAP_TORRENT = TorrentId.fromBytes(
            java.util.HexFormat.of().parseHex(OfficialBootstrapSwarm.INFO_HASH));
    private static final Instant T0 = Instant.parse("2026-07-30T18:00:00Z");
    private final List<Fixture> fixtures = new CopyOnWriteArrayList<>();

    @AfterEach void closeFixtures() {
        fixtures.forEach(Fixture::close);
    }

    @Test void belowMinimumRequestsMorePeersThroughTheExistingDiscoveryPath() {
        Fixture fixture = fixture(BootstrapNeighborConfiguration.defaults(), () -> new CompletableFuture<>());

        fixture.manager.start(T0);

        assertEquals(1, fixture.discoveryRequests.get());
        assertEquals(1, fixture.manager.maintain(T0.plusSeconds(1)).pendingDiscoveryAttempts());
    }

    @Test void targetNeighborCountStabilizesWithoutNewDiscovery() {
        Fixture fixture = fixture(BootstrapNeighborConfiguration.defaults(), () -> CompletableFuture.completedFuture(0));
        for (int index = 1; index <= 12; index++) fixture.add(index, false);

        fixture.manager.start(T0);

        assertEquals(0, fixture.discoveryRequests.get());
        assertEquals(12, fixture.manager.neighbors().size());
    }

    @Test void aboveMaximumClosesOnlyOneLeastUsefulConnectionPerMaintenanceCycle() {
        Fixture fixture = fixture(BootstrapNeighborConfiguration.defaults(), () -> CompletableFuture.completedFuture(0));
        for (int index = 1; index <= 21; index++) fixture.add(index, false);

        fixture.manager.start(T0);

        assertEquals(20, fixture.manager.neighbors().size());
        assertEquals(20, fixture.liveConnections.size());
        assertEquals(1, fixture.closedConnections());
        assertEquals(0, fixture.discoveryRequests.get());
    }

    @Test void oneNodeCannotDominateSlotsEvenWhenItHasSeveralBootstrapConnections() {
        Fixture fixture = fixture(BootstrapNeighborConfiguration.defaults(), () -> CompletableFuture.completedFuture(0));
        fixture.add(1, false);
        fixture.add(1, false);
        fixture.add(1, false);
        for (int index = 2; index <= 12; index++) fixture.add(index, false);

        fixture.manager.start(T0);

        assertEquals(12, fixture.manager.neighbors().size());
        assertEquals(1, fixture.manager.neighbors().stream().filter(neighbor -> neighbor.nodeId().equals(node(1))).count());
        assertEquals(0, fixture.discoveryRequests.get());
    }

    @Test void duplicateNodeIdIsRepresentedByOneNeighborOnly() {
        Fixture fixture = fixture(BootstrapNeighborConfiguration.defaults(), () -> CompletableFuture.completedFuture(0));
        fixture.add(7, false);
        fixture.add(7, true);

        fixture.manager.start(T0);

        assertEquals(1, fixture.manager.neighbors().size());
        assertEquals(node(7), fixture.manager.neighbors().getFirst().nodeId());
    }

    @Test void rendezvousProtectedConnectionIsNeverEvicted() {
        Fixture fixture = fixture(BootstrapNeighborConfiguration.defaults(), () -> CompletableFuture.completedFuture(0));
        for (int index = 1; index <= 21; index++) fixture.add(index, false);
        fixture.manager.protectRendezvous(node(1));

        fixture.manager.start(T0);

        assertTrue(fixture.manager.neighbors().stream().anyMatch(neighbor -> neighbor.nodeId().equals(node(1))));
        assertFalse(fixture.connectionFor(node(1)).isClosed());
        assertEquals(20, fixture.manager.neighbors().size());
    }

    @Test void renewalIsGradualAndTouchesOnlyOneNeighborAtATime() {
        BootstrapNeighborConfiguration configuration = new BootstrapNeighborConfiguration(0, 0, 20, 1,
                Duration.ofSeconds(30), Duration.ofSeconds(10));
        Fixture fixture = fixture(configuration, () -> CompletableFuture.completedFuture(0));
        fixture.add(1, false);
        fixture.add(2, false);
        fixture.add(3, false);

        fixture.manager.start(T0);
        BootstrapNeighborManager.NeighborMaintenanceResult result = fixture.manager.maintain(T0.plusSeconds(11));

        assertTrue(result.renewed().isPresent());
        assertEquals(3, fixture.manager.neighbors().size());
        long renewed = fixture.manager.neighbors().stream()
                .filter(neighbor -> neighbor.lastRenewedAt().equals(T0.plusSeconds(11))).count();
        assertEquals(1, renewed);
    }

    @Test void discoveryFailureAppliesBackoffInsteadOfRetryingOnEveryCycle() {
        CompletableFuture<Integer> failedLookup = new CompletableFuture<>();
        BootstrapNeighborConfiguration configuration = new BootstrapNeighborConfiguration(6, 12, 20, 1,
                Duration.ofSeconds(30), Duration.ofMinutes(10));
        Fixture fixture = fixture(configuration, () -> failedLookup);

        fixture.manager.start(T0);
        failedLookup.completeExceptionally(new IllegalStateException("DHT indisponivel"));
        fixture.manager.maintain(T0.plusSeconds(10));
        fixture.manager.maintain(T0.plusSeconds(31));

        assertEquals(2, fixture.discoveryRequests.get());
    }

    @Test void latencyAndCapabilitiesPreferUsefulNeighborWhenLimitRequiresAnEviction() {
        Fixture fixture = fixture(BootstrapNeighborConfiguration.defaults(), () -> CompletableFuture.completedFuture(0));
        for (int index = 1; index <= 21; index++) fixture.add(index, index == 21);
        fixture.manager.reportLatency(node(21), Duration.ofMillis(20));

        fixture.manager.start(T0);

        assertTrue(fixture.manager.neighbors().stream().anyMatch(neighbor -> neighbor.nodeId().equals(node(21))));
        assertNotEquals(node(21), fixture.manager.maintain(T0.plusSeconds(1)).evicted().orElse(null));
    }

    private Fixture fixture(BootstrapNeighborConfiguration configuration, DiscoverySupplier discovery) {
        Fixture fixture = new Fixture(configuration, discovery);
        fixtures.add(fixture);
        return fixture;
    }

    @FunctionalInterface private interface DiscoverySupplier {
        CompletableFuture<Integer> request();
    }

    private static final class Fixture implements AutoCloseable {
        private final ConnectedLuffyRegistry connected = new ConnectedLuffyRegistry();
        private final BootstrapPeerConnectionRegistry liveConnections = new BootstrapPeerConnectionRegistry();
        private final AtomicInteger discoveryRequests = new AtomicInteger();
        private final List<FakePeerConnection> connections = new CopyOnWriteArrayList<>();
        private final BootstrapNeighborManager manager;
        private final DiscoverySupplier discovery;
        private int port = 7_000;

        private Fixture(BootstrapNeighborConfiguration configuration, DiscoverySupplier discovery) {
            this.discovery = discovery;
            this.manager = new BootstrapNeighborManager(BOOTSTRAP_TORRENT, connected, liveConnections, () -> {
                discoveryRequests.incrementAndGet();
                return discovery.request();
            }, new P2pDiagnostics(), configuration);
        }

        private void add(int nodeFill, boolean useful) {
            int currentPort = port++;
            Peer peer = InetPeer.build(address("127.0.0." + ((currentPort % 200) + 1)), currentPort);
            ConnectionKey key = new ConnectionKey(peer, currentPort, BOOTSTRAP_TORRENT);
            LuffyNodeId node = node(nodeFill);
            LuffyPeerCapabilities capabilities = useful
                    ? new LuffyPeerCapabilities(1, node, "Luffy/0.1.0", true, true, true, true)
                    : new LuffyPeerCapabilities(1, node, "Luffy/0.1.0", false, false, false, false);
            connected.registerConnection(new ConnectedLuffyRegistry.ConnectedLuffy(node, BOOTSTRAP_TORRENT, peer, key,
                    capabilities, Optional.empty(), Optional.empty(), ConnectedLuffyRegistry.ConnectionDirection.UNKNOWN,
                    T0, T0));
            FakePeerConnection connection = new FakePeerConnection(peer, currentPort, BOOTSTRAP_TORRENT);
            connections.add(connection);
            liveConnections.register(connection);
        }

        private FakePeerConnection connectionFor(LuffyNodeId nodeId) {
            return connections.stream().filter(connection -> connected.findConnections(nodeId).stream()
                    .anyMatch(identified -> identified.connectionKey().getPeer().equals(connection.getRemotePeer())
                            && identified.connectionKey().getRemotePort() == connection.getRemotePort()))
                    .findFirst().orElseThrow();
        }

        private long closedConnections() { return connections.stream().filter(FakePeerConnection::isClosed).count(); }

        @Override public void close() { manager.close(); }
    }

    private static final class FakePeerConnection implements PeerConnection {
        private final Peer peer;
        private final int remotePort;
        private TorrentId torrentId;
        private volatile boolean closed;

        private FakePeerConnection(Peer peer, int remotePort, TorrentId torrentId) {
            this.peer = peer;
            this.remotePort = remotePort;
            this.torrentId = torrentId;
        }

        @Override public Peer getRemotePeer() { return peer; }
        @Override public int getRemotePort() { return remotePort; }
        @Override public TorrentId setTorrentId(TorrentId torrentId) { this.torrentId = torrentId; return torrentId; }
        @Override public TorrentId getTorrentId() { return torrentId; }
        @Override public Message readMessageNow() throws IOException { throw new UnsupportedOperationException(); }
        @Override public Message readMessage(long timeout) throws IOException { throw new UnsupportedOperationException(); }
        @Override public void postMessage(Message message) throws IOException { throw new UnsupportedOperationException(); }
        @Override public long getLastActive() { return 0; }
        @Override public void closeQuietly() { closed = true; }
        @Override public boolean isClosed() { return closed; }
        @Override public void close() { closeQuietly(); }
    }

    private static LuffyNodeId node(int fill) {
        byte[] value = new byte[LuffyNodeId.BINARY_LENGTH];
        Arrays.fill(value, (byte) fill);
        return LuffyNodeId.fromBinary(value);
    }

    private static InetAddress address(String value) {
        try { return InetAddress.getByName(value); }
        catch (Exception error) { throw new AssertionError(error); }
    }
}
