package dev.lufi.infrastructure.overlay;

import bt.metainfo.TorrentId;
import bt.net.ConnectionKey;
import bt.net.InetPeer;
import bt.net.Peer;
import dev.lufi.infrastructure.P2pDiagnostics;
import dev.lufi.infrastructure.identity.ConnectedLuffyRegistry;
import dev.lufi.infrastructure.identity.LuffyNodeId;
import dev.lufi.infrastructure.identity.LuffyNodeIdentity;
import dev.lufi.infrastructure.identity.LuffyPeerCapabilities;
import java.net.InetAddress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Prova de A -> Z -> resposta, sem permitir que Z encaminhe para um quarto peer. */
class OneHopFindNodeServiceTest {
    private static final TorrentId BOOTSTRAP = torrent("08e3e48a8916ff0b0fdc04fa903977d5efa404c7");
    private static final TorrentId VIDEO_SWARM = torrent("0123456789012345678901234567890123456789");
    private static final String CONTENT_HASH = "0123456789012345678901234567890123456789";

    @Test void zFindsBConnectedInTheSameBootstrapSwarm() {
        Topology topology = new Topology();
        topology.connectB(BOOTSTRAP);

        RouteSearchResult result = topology.searchB();

        RouteSearchResult.NodeFound found = assertInstanceOf(RouteSearchResult.NodeFound.class, result);
        assertEquals(topology.z.nodeId(), found.rendezvousNodeId());
        assertEquals(1, found.distance());
        topology.close();
    }

    @Test void zFindsBConnectedThroughAnotherActiveTorrent() {
        Topology topology = new Topology();
        topology.connectB(VIDEO_SWARM);

        RouteSearchResult result = topology.searchB();

        RouteSearchResult.NodeFound found = assertInstanceOf(RouteSearchResult.NodeFound.class, result);
        assertEquals(topology.b.nodeId(), found.targetNodeId());
        assertEquals(topology.z.nodeId(), found.rendezvousNodeId());
        topology.close();
    }

    @Test void zReturnsNodeNotFoundWhenItDoesNotKnowB() {
        Topology topology = new Topology();

        RouteSearchResult result = topology.searchB();

        assertInstanceOf(RouteSearchResult.NodeNotFound.class, result);
        topology.close();
    }

    @Test void closingBConnectionBeforeZRespondsPreventsNodeFound() {
        Topology topology = new Topology();
        ConnectionKey bConnection = topology.connectB(VIDEO_SWARM);

        var pending = topology.aService.findNode(topology.b.nodeId(), CONTENT_HASH);
        topology.zRegistry.removeConnection(bConnection);
        topology.deliverAtoZ();
        topology.deliverZtoA();

        assertInstanceOf(RouteSearchResult.NodeNotFound.class, pending.toCompletableFuture().join());
        topology.close();
    }

    @Test void invalidWireMessageIsRejectedBeforeItReachesZ() {
        LuffyRouteCodec codec = new LuffyRouteCodec();
        byte[] invalid = new byte[LuffyRouteCodec.MAX_PAYLOAD_SIZE + 1];

        assertThrows(IllegalArgumentException.class, () -> codec.decode(invalid));
    }

    @Test void zAnswersImmediatelyWhenTargetIsItsOwnNodeId() {
        Topology topology = new Topology();
        LuffyRouteMessage request = LuffyRouteMessage.findNode(java.util.UUID.randomUUID(), topology.a.nodeId(), topology.z.nodeId(),
                CONTENT_HASH, 3, Instant.now());

        topology.zService.onMessage(request, topology.zToA);

        assertEquals(1, topology.zDispatcher.sent.size());
        LuffyRouteMessage response = topology.zDispatcher.sent.getFirst().message();
        assertEquals(LuffyRouteMessage.Type.NODE_FOUND, response.type());
        assertEquals(topology.z.nodeId(), response.targetNodeId());
        assertEquals(topology.z.nodeId(), response.rendezvousNodeId());
        assertEquals(0, response.distance());
        topology.close();
    }

    private static final class Topology implements AutoCloseable {
        private final LuffyNodeIdentity a = identity(1);
        private final LuffyNodeIdentity z = identity(2);
        private final LuffyNodeIdentity b = identity(3);
        private final ConnectedLuffyRegistry aRegistry = new ConnectedLuffyRegistry();
        private final ConnectedLuffyRegistry zRegistry = new ConnectedLuffyRegistry();
        private final RecordingDispatcher aDispatcher = new RecordingDispatcher();
        private final RecordingDispatcher zDispatcher = new RecordingDispatcher();
        private final FindNodeService aService = service(a, aRegistry, aDispatcher);
        private final FindNodeService zService = service(z, zRegistry, zDispatcher);
        private final ConnectionKey aToZ = key("127.0.0.2", 7_002, BOOTSTRAP);
        private final ConnectionKey zToA = key("127.0.0.1", 7_001, BOOTSTRAP);

        private Topology() {
            register(aRegistry, z.nodeId(), aToZ, true);
            register(zRegistry, a.nodeId(), zToA, true);
            aDispatcher.allow(aToZ);
            zDispatcher.allow(zToA);
        }

        private ConnectionKey connectB(TorrentId torrent) {
            ConnectionKey zToB = key("127.0.0.3", 7_003, torrent);
            register(zRegistry, b.nodeId(), zToB, false);
            return zToB;
        }

        private RouteSearchResult searchB() {
            var pending = aService.findNode(b.nodeId(), CONTENT_HASH);
            deliverAtoZ();
            deliverZtoA();
            return pending.toCompletableFuture().join();
        }

        private void deliverAtoZ() {
            assertEquals(1, aDispatcher.sent.size());
            Sent message = aDispatcher.sent.removeFirst();
            assertEquals(aToZ, message.destination());
            zService.onMessage(message.message(), zToA);
        }

        private void deliverZtoA() {
            assertEquals(1, zDispatcher.sent.size());
            Sent message = zDispatcher.sent.removeFirst();
            assertEquals(zToA, message.destination());
            aService.onMessage(message.message(), aToZ);
        }

        @Override public void close() {
            aService.close();
            zService.close();
        }
    }

    private static FindNodeService service(LuffyNodeIdentity local, ConnectedLuffyRegistry registry,
                                           FindNodeService.RouteMessageDispatcher dispatcher) {
        return new FindNodeService(local,
                () -> new LuffyPeerCapabilities(1, local.nodeId(), "Luffy/0.1.0", true, true, true, true),
                BOOTSTRAP, registry, dispatcher, new P2pDiagnostics());
    }

    private static void register(ConnectedLuffyRegistry registry, LuffyNodeId nodeId, ConnectionKey key, boolean route) {
        LuffyPeerCapabilities capabilities = new LuffyPeerCapabilities(1, nodeId, "Luffy/0.1.0", route, false, false, false);
        registry.registerConnection(new ConnectedLuffyRegistry.ConnectedLuffy(nodeId, key.getTorrentId(), key.getPeer(), key,
                capabilities, Optional.empty(), Optional.empty(), ConnectedLuffyRegistry.ConnectionDirection.UNKNOWN,
                Instant.now(), Instant.now()));
    }

    private static final class RecordingDispatcher implements FindNodeService.RouteMessageDispatcher {
        private final List<ConnectionKey> allowed = new ArrayList<>();
        private final List<Sent> sent = new ArrayList<>();
        @Override public boolean send(ConnectionKey destination, LuffyRouteMessage message) {
            if (!canSend(destination)) return false;
            sent.add(new Sent(destination, message));
            return true;
        }
        @Override public boolean canSend(ConnectionKey destination) { return allowed.contains(destination); }
        private void allow(ConnectionKey key) { allowed.add(key); }
    }

    private record Sent(ConnectionKey destination, LuffyRouteMessage message) { }

    private static LuffyNodeIdentity identity(int fill) {
        return new LuffyNodeIdentity(node(fill), Instant.parse("2026-07-30T20:00:00Z"));
    }

    private static LuffyNodeId node(int fill) {
        byte[] bytes = new byte[LuffyNodeId.BINARY_LENGTH];
        Arrays.fill(bytes, (byte) fill);
        return LuffyNodeId.fromBinary(bytes);
    }

    private static TorrentId torrent(String value) { return TorrentId.fromBytes(HexFormat.of().parseHex(value)); }

    private static ConnectionKey key(String address, int port, TorrentId torrentId) {
        Peer peer = InetPeer.build(address(address), port);
        return new ConnectionKey(peer, port, torrentId);
    }

    private static InetAddress address(String value) {
        try { return InetAddress.getByName(value); }
        catch (Exception error) { throw new AssertionError(error); }
    }
}
