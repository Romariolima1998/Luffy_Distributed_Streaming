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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercita somente o overlay lf_route; nenhum socket, DHT ou dado de torrent e criado. */
class MultiHopFindNodeServiceTest {
    private static final TorrentId BOOTSTRAP = torrent("08e3e48a8916ff0b0fdc04fa903977d5efa404c7");
    private static final TorrentId VIDEO = torrent("0123456789012345678901234567890123456789");
    private static final String CONTENT_HASH = "0123456789012345678901234567890123456789";

    @Test void resolvesTargetInOneHop() {
        try (Network network = new Network()) {
            Node a = network.node(1);
            Node z = network.node(2);
            Node b = network.node(3);
            network.connectRoute(a, z);
            network.connectDirect(z, b, VIDEO);

            RouteSearchResult.NodeFound found = assertInstanceOf(RouteSearchResult.NodeFound.class,
                    network.search(a, b));

            assertEquals(z.identity.nodeId(), found.rendezvousNodeId());
            assertEquals(1, found.distance());
        }
    }

    @Test void resolvesTargetInTwoHops() {
        try (Network network = new Network()) {
            Node a = network.node(1);
            Node c = network.node(2);
            Node z = network.node(3);
            Node b = network.node(4);
            network.connectRoute(a, c);
            network.connectRoute(c, z);
            network.connectDirect(z, b, VIDEO);

            RouteSearchResult.NodeFound found = assertInstanceOf(RouteSearchResult.NodeFound.class,
                    network.search(a, b));

            assertEquals(z.identity.nodeId(), found.rendezvousNodeId());
            assertEquals(2, found.distance());
        }
    }

    @Test void resolvesTargetInFourHopsWithDefaultTtl() {
        try (Network network = new Network()) {
            Node a = network.node(1);
            Node c = network.node(2);
            Node x = network.node(3);
            Node y = network.node(4);
            Node z = network.node(5);
            Node b = network.node(6);
            network.connectRoute(a, c);
            network.connectRoute(c, x);
            network.connectRoute(x, y);
            network.connectRoute(y, z);
            network.connectDirect(z, b, VIDEO);

            RouteSearchResult.NodeFound found = assertInstanceOf(RouteSearchResult.NodeFound.class,
                    network.search(a, b));

            assertEquals(z.identity.nodeId(), found.rendezvousNodeId());
            assertEquals(4, found.distance());
        }
    }

    @Test void preservesOnlyLocalPredecessorAndSuccessorForTheWinningRoute() {
        try (Network network = new Network()) {
            Node a = network.node(1);
            Node c = network.node(2);
            Node x = network.node(3);
            Node y = network.node(4);
            Node z = network.node(5);
            Node b = network.node(6);
            RouteLink ac = network.connectRoute(a, c);
            RouteLink cx = network.connectRoute(c, x);
            RouteLink xy = network.connectRoute(x, y);
            RouteLink yz = network.connectRoute(y, z);
            network.connectDirect(z, b, VIDEO);

            CompletionStage<RouteSearchResult> pending = network.startSearch(a, b);
            UUID requestId = a.dispatcher.sent.getFirst().message().requestId();
            network.pumpUntilQuiet();
            assertInstanceOf(RouteSearchResult.NodeFound.class, pending.toCompletableFuture().join());

            assertEquals(ac.leftOutbound(), a.service.routePaths().find(requestId, Instant.now()).orElseThrow().nextHop());
            assertEquals(ac.rightInbound(), c.service.routePaths().find(requestId, Instant.now()).orElseThrow().previousHop());
            assertEquals(cx.leftOutbound(), c.service.routePaths().find(requestId, Instant.now()).orElseThrow().nextHop());
            assertEquals(cx.rightInbound(), x.service.routePaths().find(requestId, Instant.now()).orElseThrow().previousHop());
            assertEquals(xy.leftOutbound(), x.service.routePaths().find(requestId, Instant.now()).orElseThrow().nextHop());
            assertEquals(xy.rightInbound(), y.service.routePaths().find(requestId, Instant.now()).orElseThrow().previousHop());
            assertEquals(yz.leftOutbound(), y.service.routePaths().find(requestId, Instant.now()).orElseThrow().nextHop());
            assertEquals(yz.rightInbound(), z.service.routePaths().find(requestId, Instant.now()).orElseThrow().previousHop());
            assertTrue(z.service.routePaths().find(requestId, Instant.now()).orElseThrow().nextHop() == null);
        }
    }

    @Test void loopParticipantIsExcludedBeforeItCanReceiveTheRequestAgain() {
        try (Network network = new Network(singleForwardConfig())) {
            Node a = network.node(1);
            Node c = network.node(2);
            Node x = network.node(3);
            Node y = network.node(4);
            Node b = network.node(5);
            network.connectRoute(a, c);
            network.connectRoute(c, x);
            network.connectRoute(x, y);
            network.connectRoute(y, c);

            RouteSearchResult result = network.search(a, b);

            assertInstanceOf(RouteSearchResult.NodeNotFound.class, result, network.diagnosticsSnapshot());
            assertTrue(network.deliveredPackets <= 6, "o loop nao pode reenviar FIND_NODE a C");
        }
    }

    @Test void duplicateRequestIsForwardedOnlyOnce() {
        try (Network network = new Network()) {
            Node a = network.node(1);
            Node c = network.node(2);
            Node z = network.node(3);
            Node b = network.node(4);
            network.connectRoute(a, c);
            network.connectRoute(c, z);
            network.connectDirect(z, b, VIDEO);

            CompletionStage<RouteSearchResult> pending = network.startSearch(a, b);
            network.duplicateNextPacket();
            network.pumpUntilQuiet();

            assertInstanceOf(RouteSearchResult.NodeFound.class, pending.toCompletableFuture().join());
            assertEquals(1, c.dispatcher.countFindNodeMessages());
        }
    }

    @Test void eachLocallyStartedSearchUsesADifferentRequestId() {
        try (Network network = new Network()) {
            Node a = network.node(1);
            Node c = network.node(2);
            Node b = network.node(3);
            network.connectRoute(a, c);

            network.startSearch(a, b);
            network.startSearch(a, b);

            List<UUID> requestIds = a.dispatcher.sent.stream()
                    .filter(packet -> packet.message().type() == LuffyRouteMessage.Type.FIND_NODE)
                    .map(packet -> packet.message().requestId()).toList();
            assertEquals(2, requestIds.size());
            assertTrue(!requestIds.getFirst().equals(requestIds.getLast()));
        }
    }

    @Test void ttlZeroDoesNotForwardAgain() {
        try (Network network = new Network()) {
            Node a = network.node(1);
            Node c = network.node(2);
            Node z = network.node(3);
            Node b = network.node(4);
            RouteLink ac = network.connectRoute(a, c);
            network.connectRoute(c, z);

            LuffyRouteMessage request = LuffyRouteMessage.findNode(UUID.randomUUID(), a.identity.nodeId(), b.identity.nodeId(),
                    CONTENT_HASH, 0, Instant.now());
            c.service.onMessage(request, ac.rightInbound());

            assertEquals(1, c.dispatcher.sent.size());
            Packet response = c.dispatcher.sent.getFirst();
            assertEquals(ac.rightInbound(), response.destination());
            assertEquals(LuffyRouteMessage.RouteErrorCode.TTL_EXHAUSTED, response.message().errorCode());
            assertEquals(0, c.dispatcher.countFindNodeMessages());
        }
    }

    @Test void missingTargetReturnsNodeNotFoundAfterAllBranchesReply() {
        try (Network network = new Network()) {
            Node a = network.node(1);
            Node c = network.node(2);
            Node z = network.node(3);
            Node b = network.node(4);
            network.connectRoute(a, c);
            network.connectRoute(c, z);

            assertInstanceOf(RouteSearchResult.NodeNotFound.class, network.search(a, b));
        }
    }

    @Test void disconnectionInTheMiddleEndsWithConfiguredSearchTimeout() throws Exception {
        try (Network network = new Network(shortTimeoutConfig())) {
            Node a = network.node(1);
            Node c = network.node(2);
            Node z = network.node(3);
            Node b = network.node(4);
            network.connectRoute(a, c);
            network.connectRoute(c, z);
            network.connectDirect(z, b, VIDEO);

            CompletionStage<RouteSearchResult> pending = network.startSearch(a, b);
            network.pumpOne(); // A -> C; C encaminha para Z.
            network.disconnect(c, z);
            network.pumpUntilQuiet();

            RouteSearchResult.RouteError result = assertInstanceOf(RouteSearchResult.RouteError.class,
                    pending.toCompletableFuture().get(1, TimeUnit.SECONDS));
            assertEquals(LuffyRouteMessage.RouteErrorCode.SEARCH_TIMEOUT, result.errorCode());
        }
    }

    @Test void nodeFoundWinsWhenSeveralForwardBranchesReply() {
        try (Network network = new Network()) {
            Node a = network.node(1);
            Node c = network.node(2);
            Node x = network.node(3);
            Node y = network.node(4);
            Node b = network.node(5);
            network.connectRoute(a, c);
            network.connectRoute(c, x);
            network.connectRoute(c, y);
            network.connectDirect(y, b, VIDEO);

            RouteSearchResult.NodeFound result = assertInstanceOf(RouteSearchResult.NodeFound.class,
                    network.search(a, b));

            assertEquals(y.identity.nodeId(), result.rendezvousNodeId());
            assertEquals(2, result.distance());
            assertEquals(1, c.dispatcher.countTerminalMessagesTo(a));
        }
    }

    @Test void expiredReverseRouteDoesNotForwardAResponse() {
        ReverseRouteRegistry routes = new ReverseRouteRegistry();
        UUID requestId = UUID.randomUUID();
        Instant now = Instant.parse("2026-07-30T21:00:00Z");
        ConnectionKey previous = key(1, 2, BOOTSTRAP);
        ConnectionKey child = key(2, 3, BOOTSTRAP);
        routes.register(requestId, previous, nodeId(4), now.plusSeconds(1), Set.of(child));

        Optional<ReverseRouteRegistry.ForwardedResponse> result = routes.acceptResponse(requestId, child,
                LuffyRouteMessage.nodeNotFound(requestId, nodeId(4)), now.plusSeconds(2));

        assertTrue(result.isEmpty());
        assertEquals(0, routes.expire(now.plusSeconds(2)));
        assertEquals(0, routes.size());
    }

    @Test void fanOutIsLimitedToThreePeers() {
        try (Network network = new Network()) {
            Node a = network.node(1);
            Node b = network.node(2);
            network.connectRoute(a, network.node(3));
            network.connectRoute(a, network.node(4));
            network.connectRoute(a, network.node(5));
            network.connectRoute(a, network.node(6));
            network.connectRoute(a, network.node(7));

            network.startSearch(a, b);

            assertEquals(FindNodeService.MAXIMUM_FORWARD_PEERS, a.dispatcher.countFindNodeMessages());
        }
    }

    private static FindNodeRoutingConfig shortTimeoutConfig() {
        return new FindNodeRoutingConfig(4, 6, 3, Duration.ofMillis(50), Duration.ofMinutes(2),
                Duration.ofSeconds(5), Duration.ofMinutes(1), 24);
    }

    private static FindNodeRoutingConfig singleForwardConfig() {
        return new FindNodeRoutingConfig(4, 6, 1, Duration.ofSeconds(10), Duration.ofMinutes(2),
                Duration.ofSeconds(5), Duration.ofMinutes(1), 24);
    }

    private static final class Network implements AutoCloseable {
        private final FindNodeRoutingConfig config;
        private final Map<Integer, Node> nodes = new LinkedHashMap<>();
        private final Map<LinkKey, LinkTarget> links = new LinkedHashMap<>();
        private final Deque<Packet> packets = new ArrayDeque<>();
        private int deliveredPackets;

        private Network() { this(FindNodeRoutingConfig.defaults()); }
        private Network(FindNodeRoutingConfig config) { this.config = config; }

        private Node node(int id) {
            return nodes.computeIfAbsent(id, value -> new Node(value, config, this));
        }

        private RouteLink connectRoute(Node left, Node right) {
            ConnectionKey leftOutbound = key(left.id, right.id, BOOTSTRAP);
            ConnectionKey rightOutbound = key(right.id, left.id, BOOTSTRAP);
            register(left, right.identity.nodeId(), leftOutbound, true);
            register(right, left.identity.nodeId(), rightOutbound, true);
            links.put(new LinkKey(left, leftOutbound), new LinkTarget(right, rightOutbound));
            links.put(new LinkKey(right, rightOutbound), new LinkTarget(left, leftOutbound));
            return new RouteLink(leftOutbound, rightOutbound);
        }

        private void connectDirect(Node owner, Node target, TorrentId torrent) {
            register(owner, target.identity.nodeId(), key(owner.id, target.id, torrent), false);
        }

        private void disconnect(Node left, Node right) {
            links.entrySet().removeIf(entry -> (entry.getKey().source().equals(left) && entry.getValue().target().equals(right))
                    || (entry.getKey().source().equals(right) && entry.getValue().target().equals(left)));
            left.registry.removeConnection(key(left.id, right.id, BOOTSTRAP));
            right.registry.removeConnection(key(right.id, left.id, BOOTSTRAP));
        }

        private CompletionStage<RouteSearchResult> startSearch(Node source, Node target) {
            return source.service.findNode(target.identity.nodeId(), CONTENT_HASH);
        }

        private RouteSearchResult search(Node source, Node target) {
            CompletionStage<RouteSearchResult> pending = startSearch(source, target);
            pumpUntilQuiet();
            return pending.toCompletableFuture().join();
        }

        private void duplicateNextPacket() {
            Packet packet = packets.peekFirst();
            if (packet == null) throw new AssertionError("nenhum pacote para duplicar");
            packets.addLast(packet);
        }

        private void pumpOne() {
            Packet packet = packets.pollFirst();
            if (packet == null) return;
            LinkTarget target = links.get(new LinkKey(packet.source(), packet.destination()));
            if (target == null) return;
            deliveredPackets++;
            target.target().service.onMessage(packet.message(), target.inboundKey());
        }

        private void pumpUntilQuiet() {
            int guard = 100;
            while (!packets.isEmpty() && guard-- > 0) pumpOne();
            if (guard <= 0) throw new AssertionError("encaminhamento lf_route nao estabilizou");
        }

        private String diagnosticsSnapshot() {
            return nodes.values().stream().map(node -> "Node " + node.id + System.lineSeparator() + node.diagnostics.snapshot())
                    .collect(java.util.stream.Collectors.joining(System.lineSeparator()));
        }

        @Override public void close() {
            nodes.values().forEach(node -> node.service.close());
            packets.clear();
        }
    }

    private static final class Node {
        private final int id;
        private final LuffyNodeIdentity identity;
        private final ConnectedLuffyRegistry registry = new ConnectedLuffyRegistry();
        private final P2pDiagnostics diagnostics = new P2pDiagnostics();
        private final Dispatcher dispatcher;
        private final FindNodeService service;

        private Node(int id, FindNodeRoutingConfig config, Network network) {
            this.id = id;
            this.identity = new LuffyNodeIdentity(nodeId(id), Instant.parse("2026-07-30T20:00:00Z"));
            this.dispatcher = new Dispatcher(this, network);
            this.service = new FindNodeService(identity,
                    () -> new LuffyPeerCapabilities(1, identity.nodeId(), "Luffy/0.1.0", true, true, true, true),
                    BOOTSTRAP, registry, dispatcher, diagnostics, config);
        }
    }

    private static final class Dispatcher implements FindNodeService.RouteMessageDispatcher {
        private final Node owner;
        private final Network network;
        private final List<Packet> sent = new java.util.ArrayList<>();

        private Dispatcher(Node owner, Network network) {
            this.owner = owner;
            this.network = network;
        }

        @Override public boolean send(ConnectionKey destination, LuffyRouteMessage message) {
            if (!canSend(destination)) return false;
            Packet packet = new Packet(owner, destination, message);
            sent.add(packet);
            network.packets.addLast(packet);
            return true;
        }

        @Override public boolean canSend(ConnectionKey destination) {
            return network.links.containsKey(new LinkKey(owner, destination))
                    && owner.registry.findConnection(destination).isPresent();
        }

        private int countFindNodeMessages() {
            return (int) sent.stream().filter(packet -> packet.message().type() == LuffyRouteMessage.Type.FIND_NODE).count();
        }

        private int countTerminalMessagesTo(Node target) {
            return (int) sent.stream().filter(packet -> packet.destination().getPeer().getInetAddress()
                            .equals(address(target.id)) && packet.message().type() != LuffyRouteMessage.Type.FIND_NODE)
                    .count();
        }
    }

    private static void register(Node owner, LuffyNodeId remoteNodeId, ConnectionKey key, boolean supportsRoute) {
        LuffyPeerCapabilities capabilities = new LuffyPeerCapabilities(1, remoteNodeId, "Luffy/0.1.0", supportsRoute,
                false, false, false);
        owner.registry.registerConnection(new ConnectedLuffyRegistry.ConnectedLuffy(remoteNodeId, key.getTorrentId(), key.getPeer(),
                key, capabilities, Optional.empty(), Optional.empty(), ConnectedLuffyRegistry.ConnectionDirection.UNKNOWN,
                Instant.now(), Instant.now()));
    }

    private record LinkKey(Node source, ConnectionKey outboundKey) { }
    private record LinkTarget(Node target, ConnectionKey inboundKey) { }
    private record RouteLink(ConnectionKey leftOutbound, ConnectionKey rightInbound) { }
    private record Packet(Node source, ConnectionKey destination, LuffyRouteMessage message) { }

    private static LuffyNodeId nodeId(int fill) {
        byte[] bytes = new byte[LuffyNodeId.BINARY_LENGTH];
        Arrays.fill(bytes, (byte) fill);
        return LuffyNodeId.fromBinary(bytes);
    }

    private static TorrentId torrent(String value) { return TorrentId.fromBytes(HexFormat.of().parseHex(value)); }

    private static ConnectionKey key(int sourceId, int destinationId, TorrentId torrentId) {
        int port = 7_000 + destinationId;
        Peer peer = InetPeer.build(address(destinationId), port);
        return new ConnectionKey(peer, port, torrentId);
    }

    private static InetAddress address(int id) {
        try { return InetAddress.getByName("127.0.0." + id); }
        catch (Exception error) { throw new AssertionError(error); }
    }
}
