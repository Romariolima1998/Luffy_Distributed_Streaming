package dev.lufi.infrastructure.rendezvous;

import bt.bencoding.types.BEInteger;
import bt.metainfo.TorrentId;
import bt.net.ConnectionKey;
import bt.net.InetPeer;
import bt.net.Peer;
import bt.protocol.Message;
import bt.protocol.extended.ExtendedHandshake;
import bt.torrent.messaging.MessageContext;
import dev.lufi.infrastructure.P2pDiagnostics;
import dev.lufi.infrastructure.identity.ConnectedLuffyRegistry;
import dev.lufi.infrastructure.identity.LuffyNodeId;
import dev.lufi.infrastructure.identity.LuffyNodeIdentity;
import dev.lufi.infrastructure.identity.LuffyPeerCapabilities;
import dev.lufi.infrastructure.overlay.OverlayRoutePathRegistry;
import dev.lufi.infrastructure.security.AbuseProtectionConfig;
import dev.lufi.infrastructure.security.AbuseProtectionService;
import java.lang.reflect.Constructor;
import java.net.InetAddress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LuffyRendezvousExtensionTest {
    private static final TorrentId BOOTSTRAP = torrent("08e3e48a8916ff0b0fdc04fa903977d5efa404c7");
    private static final TorrentId CONTENT = torrent("0123456789012345678901234567890123456789");

    @Test void preparesBAndCompletesBep55UtpControlAcrossACXZWithoutTorrentData() throws Exception {
        try (Node a = new Node(1); Node c = new Node(2); Node x = new Node(3); Node z = new Node(4); Node b = new Node(5)) {
            Link ac = link(a, c); Link cx = link(c, x); Link xz = link(x, z); Link zb = link(z, b);
            UUID route = UUID.randomUUID();
            a.paths.recordOrigin(route, ac.left(), Instant.now().plusSeconds(30));
            c.paths.recordRelay(route, ac.right(), cx.left(), Instant.now().plusSeconds(30));
            x.paths.recordRelay(route, cx.right(), xz.left(), Instant.now().plusSeconds(30));
            z.paths.recordTerminal(route, xz.right(), Instant.now().plusSeconds(30));

            RendezvousSession created = a.extension.request(route, b.identity.nodeId(), z.identity.nodeId(), CONTENT).orElseThrow();
            LuffyRendezvousMessage request = take(a, ac.left());
            assertEquals(LuffyRendezvousMessage.Type.RENDEZVOUS_REQUEST, request.type());
            c.extension.consume(request, context(ac.right()));
            x.extension.consume(take(c, cx.left()), context(cx.right()));
            z.extension.consume(take(x, xz.left()), context(xz.right()));

            LuffyRendezvousMessage prepareB = take(z, zb.left());
            assertEquals(LuffyRendezvousMessage.Direction.TO_TARGET, prepareB.direction());
            b.extension.consume(prepareB, context(zb.right()));
            LuffyRendezvousMessage bAccepted = take(b, zb.right());
            assertEquals(LuffyRendezvousMessage.Type.RENDEZVOUS_ACCEPTED, bAccepted.type());
            assertTrue(bAccepted.endpoint().isPresent());
            z.extension.consume(bAccepted, context(zb.left()));

            LuffyRendezvousMessage prepareA = take(z, xz.right());
            assertEquals(LuffyRendezvousMessage.Direction.TO_REQUESTER, prepareA.direction());
            x.extension.consume(prepareA, context(xz.left()));
            c.extension.consume(take(x, cx.right()), context(cx.left()));
            a.extension.consume(take(c, ac.right()), context(ac.left()));

            LuffyRendezvousMessage accepted = take(a, ac.left());
            assertEquals(LuffyRendezvousMessage.Type.RENDEZVOUS_ACCEPTED, accepted.type());
            c.extension.consume(accepted, context(ac.right()));
            x.extension.consume(take(c, cx.left()), context(cx.right()));
            z.extension.consume(take(x, xz.left()), context(xz.right()));

            a.completePunches();
            LuffyRendezvousMessage result = take(a, ac.left());
            assertEquals(LuffyRendezvousMessage.Type.RENDEZVOUS_RESULT, result.type());
            assertEquals(LuffyRendezvousMessage.Code.PUNCH_SUCCEEDED, result.code());
            c.extension.consume(result, context(ac.right()));
            x.extension.consume(take(c, cx.left()), context(cx.right()));
            z.extension.consume(take(x, xz.left()), context(xz.right()));
            b.completePunches();

            assertEquals(0, a.extension.activeSessionCount());
            assertEquals(0, b.extension.activeSessionCount());
            assertEquals(0, z.extension.activeSessionCount());
            assertTrue(a.diagnostics.snapshot().contains("[LF-RENDEZVOUS] event=RENDEZVOUS_START"));
            assertTrue(a.diagnostics.snapshot().contains("[LF-UTP] event=PUNCH_START"));
            assertTrue(a.diagnostics.snapshot().contains("[LF-BT-BRIDGE] event=BITTORRENT_CONNECTED"));
        }
    }

    @Test void rejectsUnnegotiatedPeerWithoutOpeningAParallelChannel() throws Exception {
        try (Node a = new Node(1); Node c = new Node(2)) {
            ConnectionKey ac = connection(a, c);
            a.paths.recordOrigin(UUID.randomUUID(), ac, Instant.now().plusSeconds(30));
            LuffyRendezvousMessage request = LuffyRendezvousMessage.request(session(a, c, c), endpoint(a));
            a.extension.consume(request, context(ac));
            assertEquals(0, a.extension.activeSessionCount());
            assertTrue(a.outbound(ac).isEmpty());
        }
    }

    @Test void rejectsPrivateEndpointBeforeRelayingAnyRendezvousControl() throws Exception {
        try (Node a = new Node(1); Node c = new Node(2)) {
            Link ca = link(c, a);
            Instant now = Instant.now();
            RendezvousSession session = new RendezvousSession(UUID.randomUUID(), UUID.randomUUID(), node(7), node(8),
                    a.identity.nodeId(), CONTENT, now, now.plusSeconds(30), RendezvousState.CREATED);
            LuffyRendezvousMessage privateRequest = LuffyRendezvousMessage.request(session,
                    new LuffyRendezvousMessage.RendezvousEndpoint(address("192.168.1.7"), 6_891));

            a.extension.consume(privateRequest, context(ca.right()));

            assertTrue(a.outbound(ca.right()).isEmpty());
            assertEquals(0, a.extension.activeSessionCount());
        }
    }

    @Test void limitsConcurrentRendezvousSessionsWithoutOpeningAnotherChannel() throws Exception {
        AbuseProtectionService protection = new AbuseProtectionService(new AbuseProtectionConfig(20, 20, 2, 1, 2, 512, 6, 8, 2));
        try (Node a = new Node(1, protection); Node c = new Node(2)) {
            Link ac = link(a, c);
            UUID firstRoute = UUID.randomUUID();
            UUID secondRoute = UUID.randomUUID();
            a.paths.recordOrigin(firstRoute, ac.left(), Instant.now().plusSeconds(30));
            a.paths.recordOrigin(secondRoute, ac.left(), Instant.now().plusSeconds(30));

            // O primeiro pedido valido permanece pendente; o segundo e negado pelo orcamento local.
            assertTrue(a.extension.request(firstRoute, node(3), c.identity.nodeId(), CONTENT).isPresent());
            assertTrue(a.extension.request(secondRoute, node(4), c.identity.nodeId(), CONTENT).isEmpty());
        }
    }

    private static Link link(Node left, Node right) throws Exception {
        ConnectionKey outbound = connection(left, right);
        ConnectionKey inbound = connection(right, left);
        register(left, right, outbound); register(right, left, inbound);
        negotiate(left, outbound); negotiate(right, inbound);
        return new Link(outbound, inbound);
    }
    private static void register(Node owner, Node remote, ConnectionKey key) {
        owner.registry.registerConnection(new ConnectedLuffyRegistry.ConnectedLuffy(remote.identity.nodeId(), key.getTorrentId(), key.getPeer(), key,
                new LuffyPeerCapabilities(1, remote.identity.nodeId(), "Luffy/0.1.0", true, true, true, true), Optional.empty(), Optional.empty(),
                ConnectedLuffyRegistry.ConnectionDirection.UNKNOWN, Instant.now(), Instant.now()));
    }
    private static void negotiate(Node node, ConnectionKey key) throws Exception {
        node.extension.consume(ExtendedHandshake.builder().addMessageType(LuffyRendezvousExtension.EXTENSION_NAME, 13)
                .property(ExtendedHandshake.TCPPORT_PROPERTY, new BEInteger(6891)).build(), context(key));
        assertTrue(node.extension.isNegotiated(key));
    }
    private static LuffyRendezvousMessage take(Node node, ConnectionKey key) throws Exception {
        List<Message> messages = node.outbound(key);
        assertEquals(1, messages.size());
        return assertInstanceOf(LuffyRendezvousMessage.class, messages.getFirst());
    }
    private static LuffyRendezvousMessage.RendezvousEndpoint endpoint(Node node) {
        return new LuffyRendezvousMessage.RendezvousEndpoint(address("203.0.113." + node.id), 43_000 + node.id);
    }
    private static RendezvousSession session(Node requester, Node target, Node rendezvous) {
        Instant now = Instant.now(); return new RendezvousSession(UUID.randomUUID(), UUID.randomUUID(), requester.identity.nodeId(), target.identity.nodeId(), rendezvous.identity.nodeId(), CONTENT, now, now.plusSeconds(30), RendezvousState.CREATED);
    }
    private static ConnectionKey connection(Node local, Node remote) {
        Peer peer = InetPeer.build(address("127.0.0." + remote.id), 6_800 + remote.id);
        return new ConnectionKey(peer, 6_800 + remote.id, BOOTSTRAP);
    }
    private static MessageContext context(ConnectionKey key) throws Exception {
        Constructor<MessageContext> constructor = MessageContext.class.getDeclaredConstructor(ConnectionKey.class, Class.forName("bt.torrent.messaging.ConnectionState"));
        constructor.setAccessible(true); return constructor.newInstance(key, null);
    }
    private static TorrentId torrent(String value) { return TorrentId.fromBytes(HexFormat.of().parseHex(value)); }
    private static InetAddress address(String value) { try { return InetAddress.getByName(value); } catch (Exception error) { throw new AssertionError(error); } }

    private static final class Node implements AutoCloseable {
        private final int id;
        private final LuffyNodeIdentity identity;
        private final P2pDiagnostics diagnostics = new P2pDiagnostics();
        private final ConnectedLuffyRegistry registry = new ConnectedLuffyRegistry();
        private final OverlayRoutePathRegistry paths = new OverlayRoutePathRegistry();
        private final List<CompletableFuture<Void>> punches = new CopyOnWriteArrayList<>();
        private final LuffyRendezvousExtension extension;
        private Node(int id) { this(id, new AbuseProtectionService()); }
        private Node(int id, AbuseProtectionService protection) {
            this.id = id;
            this.identity = new LuffyNodeIdentity(node(id), Instant.parse("2026-08-04T18:00:00Z"));
            this.extension = new LuffyRendezvousExtension(identity, paths, registry, () -> Optional.of(endpoint(this)),
                    (torrent, remote) -> { CompletableFuture<Void> result = new CompletableFuture<>(); punches.add(result); return result; },
                    diagnostics, protection);
        }
        private void completePunches() { punches.forEach(future -> future.complete(null)); }
        private List<Message> outbound(ConnectionKey key) throws Exception { List<Message> result = new ArrayList<>(); extension.produce(result::add, context(key)); return result; }
        @Override public void close() { extension.close(); }
    }
    private record Link(ConnectionKey left, ConnectionKey right) { }
    private static LuffyNodeId node(int fill) { byte[] value = new byte[LuffyNodeId.BINARY_LENGTH]; Arrays.fill(value, (byte) fill); return LuffyNodeId.fromBinary(value); }
}
