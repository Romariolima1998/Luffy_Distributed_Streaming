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
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FindNodeServiceTest {
    private static final TorrentId BOOTSTRAP = TorrentId.fromBytes(HexFormat.of().parseHex(
            "08e3e48a8916ff0b0fdc04fa903977d5efa404c7"));
    private static final TorrentId CONTENT = TorrentId.fromBytes(HexFormat.of().parseHex(
            "0123456789012345678901234567890123456789"));
    private static final String CONTENT_HASH = "0123456789012345678901234567890123456789";

    @Test void findsTargetAlreadyConnectedWithoutSendingRouteMessages() {
        Fixture fixture = new Fixture();
        fixture.add(node(2), CONTENT, true);

        RouteSearchResult result = fixture.service.findNode(node(2), CONTENT_HASH).toCompletableFuture().join();

        RouteSearchResult.NodeFound found = assertInstanceOf(RouteSearchResult.NodeFound.class, result);
        assertEquals(fixture.local.nodeId(), found.rendezvousNodeId());
        assertEquals(1, found.distance());
        assertTrue(fixture.dispatcher.sent.isEmpty());
        assertTrue(fixture.diagnostics.snapshot().contains("[LF-ROUTE] event=FIND_NODE_START"));
        assertTrue(fixture.diagnostics.snapshot().contains("[LF-ROUTE] event=NODE_FOUND"));
        fixture.close();
    }

    @Test void forwardsThroughEligibleBootstrapNeighbor() {
        Fixture fixture = new Fixture();
        ConnectionKey nextHop = fixture.add(node(3), BOOTSTRAP, true);

        CompletionStage<RouteSearchResult> pending = fixture.service.findNode(node(9), CONTENT_HASH);

        assertFalse(pending.toCompletableFuture().isDone());
        assertEquals(1, fixture.dispatcher.sent.size());
        SentRoute sent = fixture.dispatcher.sent.getFirst();
        assertEquals(nextHop, sent.destination());
        assertEquals(LuffyRouteMessage.Type.FIND_NODE, sent.message().type());
        assertEquals(FindNodeService.DEFAULT_TTL - 1, sent.message().ttl());
        fixture.close();
    }

    @Test void incomingSearchForwardsToAnEligibleSecondHop() {
        Fixture fixture = new Fixture();
        ConnectionKey previousHop = fixture.add(node(2), BOOTSTRAP, true);
        ConnectionKey nextHop = fixture.add(node(3), BOOTSTRAP, true);
        LuffyRouteMessage inbound = LuffyRouteMessage.findNode(UUID.randomUUID(), node(2), node(9), CONTENT_HASH, 4, Instant.now());

        fixture.service.onMessage(inbound, previousHop);
        assertEquals(1, fixture.dispatcher.sent.size());
        SentRoute forwarded = fixture.dispatcher.sent.getFirst();
        assertEquals(nextHop, forwarded.destination());
        assertEquals(LuffyRouteMessage.Type.FIND_NODE, forwarded.message().type());
        assertEquals(3, forwarded.message().ttl());
        fixture.close();
    }

    @Test void rejectsStaleTimestampAndNeverForwardsIt() {
        Fixture fixture = new Fixture();
        ConnectionKey source = fixture.add(node(2), BOOTSTRAP, true);
        fixture.add(node(3), BOOTSTRAP, true);
        LuffyRouteMessage stale = LuffyRouteMessage.findNode(UUID.randomUUID(), node(2), node(9), CONTENT_HASH, 4,
                Instant.now().minus(FindNodeService.MAX_REQUEST_AGE).minusSeconds(1));

        fixture.service.onMessage(stale, source);

        assertEquals(1, fixture.dispatcher.sent.size());
        assertEquals(source, fixture.dispatcher.sent.getFirst().destination());
        assertEquals(LuffyRouteMessage.RouteErrorCode.EXPIRED, fixture.dispatcher.sent.getFirst().message().errorCode());
        fixture.close();
    }

    @Test void duplicateRequestsAreIgnoredAndConflictsAreRejectedWithoutLooping() {
        Fixture fixture = new Fixture();
        ConnectionKey source = fixture.add(node(2), BOOTSTRAP, true);
        UUID requestId = UUID.randomUUID();
        LuffyRouteMessage request = LuffyRouteMessage.findNode(requestId, node(2), node(9), CONTENT_HASH, 3, Instant.now());

        fixture.service.onMessage(request, source);
        fixture.dispatcher.sent.clear();
        fixture.service.onMessage(request, source);
        fixture.service.onMessage(LuffyRouteMessage.findNode(requestId, node(2), node(8), CONTENT_HASH, 3, Instant.now()), source);

        assertEquals(1, fixture.dispatcher.sent.size());
        assertEquals(LuffyRouteMessage.RouteErrorCode.REQUEST_CONFLICT, fixture.dispatcher.sent.getFirst().message().errorCode());
        fixture.close();
    }

    @Test void ignoresRouteMessageFromConnectionWithoutValidatedIdentity() {
        Fixture fixture = new Fixture();
        ConnectionKey unknown = key(8_888, BOOTSTRAP);
        fixture.service.onMessage(LuffyRouteMessage.findNode(UUID.randomUUID(), node(2), node(9), CONTENT_HASH, 3, Instant.now()), unknown);

        assertTrue(fixture.dispatcher.sent.isEmpty());
        fixture.close();
    }

    private static final class Fixture implements AutoCloseable {
        private final LuffyNodeIdentity local = new LuffyNodeIdentity(node(1), Instant.parse("2026-07-30T19:00:00Z"));
        private final ConnectedLuffyRegistry registry = new ConnectedLuffyRegistry();
        private final RecordingDispatcher dispatcher = new RecordingDispatcher();
        private final P2pDiagnostics diagnostics = new P2pDiagnostics();
        private final FindNodeService service = new FindNodeService(local,
                () -> new LuffyPeerCapabilities(1, local.nodeId(), "Luffy/0.1.0", true, true, true, true),
                BOOTSTRAP, registry, dispatcher, diagnostics);
        private int port = 7_000;

        private ConnectionKey add(LuffyNodeId nodeId, TorrentId torrentId, boolean route) {
            ConnectionKey key = key(port++, torrentId);
            LuffyPeerCapabilities capabilities = new LuffyPeerCapabilities(1, nodeId, "Luffy/0.1.0", route, false, false, false);
            registry.registerConnection(new ConnectedLuffyRegistry.ConnectedLuffy(nodeId, torrentId, key.getPeer(), key,
                    capabilities, Optional.empty(), Optional.empty(), ConnectedLuffyRegistry.ConnectionDirection.UNKNOWN,
                    Instant.now(), Instant.now()));
            dispatcher.allowed.add(key);
            return key;
        }

        @Override public void close() { service.close(); }
    }

    private static final class RecordingDispatcher implements FindNodeService.RouteMessageDispatcher {
        private final List<ConnectionKey> allowed = new ArrayList<>();
        private final List<SentRoute> sent = new ArrayList<>();
        @Override public boolean send(ConnectionKey destination, LuffyRouteMessage message) {
            if (!canSend(destination)) return false;
            sent.add(new SentRoute(destination, message));
            return true;
        }
        @Override public boolean canSend(ConnectionKey destination) { return allowed.contains(destination); }
    }

    private record SentRoute(ConnectionKey destination, LuffyRouteMessage message) { }

    private static ConnectionKey key(int port, TorrentId torrentId) {
        Peer peer = InetPeer.build(address("127.0.0." + ((port % 200) + 1)), port);
        return new ConnectionKey(peer, port, torrentId);
    }

    private static LuffyNodeId node(int fill) {
        byte[] bytes = new byte[LuffyNodeId.BINARY_LENGTH];
        Arrays.fill(bytes, (byte) fill);
        return LuffyNodeId.fromBinary(bytes);
    }

    private static InetAddress address(String value) {
        try { return InetAddress.getByName(value); }
        catch (Exception error) { throw new AssertionError(error); }
    }
}
