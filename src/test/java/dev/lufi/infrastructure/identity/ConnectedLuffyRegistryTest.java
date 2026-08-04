package dev.lufi.infrastructure.identity;

import bt.metainfo.TorrentId;
import bt.net.ConnectionKey;
import bt.net.InetPeer;
import java.net.InetAddress;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectedLuffyRegistryTest {
    private static final Instant CONNECTED_AT = Instant.parse("2026-07-30T15:00:00Z");

    @Test void keepsTheSameNodeIdConnectedThroughTwoDifferentTorrents() {
        ConnectedLuffyRegistry registry = new ConnectedLuffyRegistry();
        LuffyNodeId nodeId = nodeId(1);
        registry.registerConnection(connection(nodeId, 1, 6891, basicCapabilities(nodeId), CONNECTED_AT));
        registry.registerConnection(connection(nodeId, 2, 6892, basicCapabilities(nodeId), CONNECTED_AT.plusSeconds(1)));

        assertEquals(2, registry.findConnections(nodeId).size());
        assertTrue(registry.hasDirectConnection(nodeId));
        assertEquals(java.util.Set.of(nodeId), registry.listConnectedNodeIds());
    }

    @Test void removingOneConnectionKeepsTheOtherConnectionForTheSameNode() {
        ConnectedLuffyRegistry registry = new ConnectedLuffyRegistry();
        LuffyNodeId nodeId = nodeId(2);
        ConnectedLuffyRegistry.ConnectedLuffy first = connection(nodeId, 3, 6893, basicCapabilities(nodeId), CONNECTED_AT);
        ConnectedLuffyRegistry.ConnectedLuffy second = connection(nodeId, 4, 6894, basicCapabilities(nodeId), CONNECTED_AT.plusSeconds(1));
        registry.registerConnection(first);
        registry.registerConnection(second);

        assertTrue(registry.removeConnection(first.connectionKey()));
        assertEquals(List.of(second), registry.findConnections(nodeId));
        assertTrue(registry.hasDirectConnection(nodeId));
    }

    @Test void removingTheLastConnectionRemovesTheNodeFromTheGlobalView() {
        ConnectedLuffyRegistry registry = new ConnectedLuffyRegistry();
        LuffyNodeId nodeId = nodeId(3);
        ConnectedLuffyRegistry.ConnectedLuffy active = connection(nodeId, 5, 6895, basicCapabilities(nodeId), CONNECTED_AT);
        registry.registerConnection(active);

        assertTrue(registry.removeConnection(active.connectionKey()));
        assertFalse(registry.hasDirectConnection(nodeId));
        assertTrue(registry.findConnections(nodeId).isEmpty());
        assertTrue(registry.listConnectedNodeIds().isEmpty());
    }

    @Test void prefersAConnectionWithUsefulControlCapabilities() {
        ConnectedLuffyRegistry registry = new ConnectedLuffyRegistry();
        LuffyNodeId nodeId = nodeId(4);
        LuffyPeerCapabilities limited = new LuffyPeerCapabilities(1, nodeId, "Luffy/0.1.0", false, false, false, false);
        LuffyPeerCapabilities rendezvous = new LuffyPeerCapabilities(1, nodeId, "Luffy/0.1.0", true, true, true, true);
        ConnectedLuffyRegistry.ConnectedLuffy weak = connection(nodeId, 6, 6896, limited, CONNECTED_AT.plusSeconds(5));
        ConnectedLuffyRegistry.ConnectedLuffy useful = connection(nodeId, 7, 6897, rendezvous, CONNECTED_AT);
        registry.registerConnection(weak);
        registry.registerConnection(useful);

        assertEquals(useful, registry.findBestControlConnection(nodeId).orElseThrow());
    }

    @Test void removesAClosedConnectionUsingTheBtCoreLifecycleCoordinates() {
        ConnectedLuffyRegistry registry = new ConnectedLuffyRegistry();
        LuffyNodeId nodeId = nodeId(5);
        ConnectedLuffyRegistry.ConnectedLuffy active = connection(nodeId, 8, 6898, basicCapabilities(nodeId), CONNECTED_AT);
        registry.registerConnection(active);

        assertEquals(1, registry.removeConnection(active.sourceTorrent(), active.peer(), active.connectionKey().getRemotePort()));
        assertFalse(registry.hasDirectConnection(nodeId));
    }

    @Test void preservesTheCapabilitiesOfEachConnectionIndependently() {
        ConnectedLuffyRegistry registry = new ConnectedLuffyRegistry();
        LuffyNodeId nodeId = nodeId(6);
        LuffyPeerCapabilities tcpOnly = new LuffyPeerCapabilities(1, nodeId, "Luffy/0.1.0", false, false, false, false);
        LuffyPeerCapabilities utpCapable = new LuffyPeerCapabilities(1, nodeId, "Luffy/0.1.0", false, true, true, true);
        registry.registerConnection(connection(nodeId, 9, 6899, tcpOnly, CONNECTED_AT));
        registry.registerConnection(connection(nodeId, 10, 6900, utpCapable, CONNECTED_AT.plusSeconds(1)));

        assertEquals(2, registry.findConnections(nodeId).size());
        assertTrue(registry.findConnections(nodeId).stream().anyMatch(connection -> !connection.capabilities().supportsUtp()));
        assertTrue(registry.findConnections(nodeId).stream().anyMatch(connection -> connection.capabilities().supportsDistributedRendezvous()));
    }

    @Test void concurrentRegisterAndRemoveOperationsDoNotLeaveStaleEntries() {
        ConnectedLuffyRegistry registry = new ConnectedLuffyRegistry();
        LuffyNodeId nodeId = nodeId(7);
        List<ConnectedLuffyRegistry.ConnectedLuffy> connections = java.util.stream.IntStream.range(0, 64)
                .mapToObj(index -> connection(nodeId, 100 + index, 7_000 + index, basicCapabilities(nodeId),
                        CONNECTED_AT.plusSeconds(index))).toList();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture.allOf(connections.stream()
                    .map(connection -> CompletableFuture.runAsync(() -> registry.registerConnection(connection), executor))
                    .toArray(CompletableFuture[]::new)).join();
            assertEquals(64, registry.findConnections(nodeId).size());

            CompletableFuture.allOf(connections.stream()
                    .map(connection -> CompletableFuture.runAsync(() -> registry.removeConnection(connection.connectionKey()), executor))
                    .toArray(CompletableFuture[]::new)).join();
        }
        assertFalse(registry.hasDirectConnection(nodeId));
        assertTrue(registry.listConnectedNodeIds().isEmpty());
    }

    private static ConnectedLuffyRegistry.ConnectedLuffy connection(LuffyNodeId nodeId, int torrentSuffix, int port,
                                                                      LuffyPeerCapabilities capabilities, Instant connectedAt) {
        ConnectionKey key = new ConnectionKey(InetPeer.build(address("127.0.0." + ((port % 200) + 1)), port), port,
                torrent(torrentSuffix));
        return new ConnectedLuffyRegistry.ConnectedLuffy(nodeId, key.getTorrentId(), key.getPeer(), key, capabilities,
                Optional.empty(), Optional.empty(), ConnectedLuffyRegistry.ConnectionDirection.UNKNOWN,
                connectedAt, connectedAt);
    }

    private static LuffyPeerCapabilities basicCapabilities(LuffyNodeId nodeId) {
        return new LuffyPeerCapabilities(1, nodeId, "Luffy/0.1.0", false, false, false, false);
    }

    private static TorrentId torrent(int suffix) {
        return TorrentId.fromBytes(HexFormat.of().parseHex(String.format("%040x", suffix)));
    }

    private static LuffyNodeId nodeId(int fill) {
        byte[] value = new byte[LuffyNodeId.BINARY_LENGTH];
        Arrays.fill(value, (byte) fill);
        return LuffyNodeId.fromBinary(value);
    }

    private static InetAddress address(String value) {
        try { return InetAddress.getByName(value); }
        catch (Exception error) { throw new AssertionError(error); }
    }
}
