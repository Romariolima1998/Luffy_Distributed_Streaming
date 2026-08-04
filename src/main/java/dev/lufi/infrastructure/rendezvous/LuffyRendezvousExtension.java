package dev.lufi.infrastructure.rendezvous;

import bt.metainfo.TorrentId;
import bt.module.ProtocolModule;
import bt.module.ServiceModule;
import bt.net.ConnectionKey;
import bt.net.Peer;
import bt.protocol.Message;
import bt.protocol.extended.ExtendedHandshake;
import bt.torrent.annotation.Consumes;
import bt.torrent.annotation.Produces;
import bt.torrent.messaging.MessageContext;
import com.google.inject.Binder;
import com.google.inject.Module;
import dev.lufi.infrastructure.P2pDiagnostics;
import dev.lufi.infrastructure.PeerCapabilities;
import dev.lufi.infrastructure.identity.ConnectedLuffyRegistry;
import dev.lufi.infrastructure.identity.LuffyNodeId;
import dev.lufi.infrastructure.identity.LuffyNodeIdentity;
import dev.lufi.infrastructure.overlay.OverlayRoutePathRegistry;
import dev.lufi.infrastructure.security.AbuseProtectionService;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Extensao BEP 10 {@code lf_rendezvous}. Usa estritamente as conexoes
 * BitTorrent existentes e a rota local preservada por {@code lf_route}.
 */
public final class LuffyRendezvousExtension implements Module, AutoCloseable {
    public static final String EXTENSION_NAME = "lf_rendezvous";

    private final LuffyNodeIdentity localIdentity;
    private final OverlayRoutePathRegistry routePaths;
    private final ConnectedLuffyRegistry connectedLuffys;
    private final P2pDiagnostics diagnostics;
    private final Set<ConnectionKey> negotiatedConnections = ConcurrentHashMap.newKeySet();
    private final Map<ConnectionKey, ConcurrentLinkedQueue<Message>> outbound = new ConcurrentHashMap<>();
    private final RendezvousCoordinator coordinator;
    private final AbuseProtectionService abuseProtection;
    private final OverlayPrivacyPolicy privacyPolicy;

    public LuffyRendezvousExtension(LuffyNodeIdentity localIdentity, OverlayRoutePathRegistry routePaths,
                                    ConnectedLuffyRegistry connectedLuffys, P2pDiagnostics diagnostics) {
        this(localIdentity, routePaths, connectedLuffys, Optional::<LuffyRendezvousMessage.RendezvousEndpoint>empty,
                (torrent, endpoint) -> CompletableFuture.failedFuture(new IllegalStateException("executor BEP55/uTP indisponivel")), diagnostics,
                new AbuseProtectionService());
    }

    public LuffyRendezvousExtension(LuffyNodeIdentity localIdentity, OverlayRoutePathRegistry routePaths,
                                    ConnectedLuffyRegistry connectedLuffys, RendezvousEndpointProvider endpointProvider,
                                    RendezvousPunchExecutor punchExecutor, P2pDiagnostics diagnostics) {
        this(localIdentity, routePaths, connectedLuffys, endpointProvider, punchExecutor, diagnostics, new AbuseProtectionService());
    }

    public LuffyRendezvousExtension(LuffyNodeIdentity localIdentity, OverlayRoutePathRegistry routePaths,
                                    ConnectedLuffyRegistry connectedLuffys, RendezvousEndpointProvider endpointProvider,
                                    RendezvousPunchExecutor punchExecutor, P2pDiagnostics diagnostics,
                                    AbuseProtectionService abuseProtection) {
        this(localIdentity, routePaths, connectedLuffys, endpointProvider, punchExecutor, diagnostics, abuseProtection,
                OverlayPrivacyPolicy.strict());
    }

    /** A variante com politica permissiva e reservada ao teste loopback do transporte. */
    public LuffyRendezvousExtension(LuffyNodeIdentity localIdentity, OverlayRoutePathRegistry routePaths,
                                    ConnectedLuffyRegistry connectedLuffys, RendezvousEndpointProvider endpointProvider,
                                    RendezvousPunchExecutor punchExecutor, P2pDiagnostics diagnostics,
                                    AbuseProtectionService abuseProtection, OverlayPrivacyPolicy privacyPolicy) {
        this.localIdentity = Objects.requireNonNull(localIdentity, "localIdentity");
        this.routePaths = Objects.requireNonNull(routePaths, "routePaths");
        this.connectedLuffys = Objects.requireNonNull(connectedLuffys, "connectedLuffys");
        this.diagnostics = diagnostics == null ? new P2pDiagnostics() : diagnostics;
        this.abuseProtection = Objects.requireNonNull(abuseProtection, "abuseProtection");
        this.privacyPolicy = Objects.requireNonNull(privacyPolicy, "privacyPolicy");
        this.coordinator = new RendezvousCoordinator(localIdentity, connectedLuffys, new RendezvousSessionRegistry(),
                this::sendByRoute, this::sendDirectToNode, endpointProvider, punchExecutor, this.diagnostics,
                RendezvousCoordinator.DEFAULT_SESSION_TIMEOUT, RendezvousFallbackPolicy.defaults(), this.abuseProtection);
    }

    @Override public void configure(Binder binder) {
        ProtocolModule.extend(binder).addExtendedMessageHandler(EXTENSION_NAME,
                new LuffyRendezvousMessageHandler(() -> abuseProtection.config().maxPayloadBytes()));
        ServiceModule.extend(binder).addMessagingAgent(this);
    }

    @Consumes public void consume(ExtendedHandshake handshake, MessageContext context) {
        ConnectionKey key = context.getConnectionKey();
        if (!PeerCapabilities.fromExtensionHandshake(handshake.getSupportedMessageTypes()).supportsLuffyRendezvous()) {
            negotiatedConnections.remove(key);
            outbound.remove(key);
            return;
        }
        negotiatedConnections.add(key);
        diagnostics.log(P2pDiagnostics.Layer.CONNECTIVITY, "[LF_RENDEZVOUS] negociado: peer=" + display(key) + ".");
    }

    @Consumes public void consume(LuffyRendezvousMessage message, MessageContext context) {
        ConnectionKey source = context.getConnectionKey();
        if (!negotiatedConnections.contains(source) || connectedLuffys.findConnection(source).isEmpty()) {
            diagnostics.log(P2pDiagnostics.Layer.CONNECTIVITY, "[LF_RENDEZVOUS] mensagem rejeitada sem extensao/identidade validada.");
            return;
        }
        String peerKey = AbuseProtectionService.peerKey(source.getPeer().getInetAddress());
        if (!abuseProtection.isAllowed(peerKey, Instant.now())) {
            diagnostics.log(P2pDiagnostics.Layer.CONNECTIVITY, "[SECURITY] LF_RENDEZVOUS bloqueado temporariamente: peer=" + peerKey + ".");
            return;
        }
        if (message.type() == LuffyRendezvousMessage.Type.RENDEZVOUS_REQUEST
                && !abuseProtection.allowRendezvousRequest(peerKey, Instant.now())) {
            diagnostics.log(P2pDiagnostics.Layer.CONNECTIVITY, "[SECURITY] LF_RENDEZVOUS REQUEST excedeu limite por peer.");
            return;
        }
        if (message.endpoint().isPresent() && !privacyPolicy.allows(message.endpoint().orElseThrow())) {
            diagnostics.log(P2pDiagnostics.Layer.CONNECTIVITY,
                    "[PRIVACY] LF_RENDEZVOUS rejeitado: endpoint privado/local nao e encaminhado pelo overlay.");
            return;
        }
        forwardOrConsume(message, source);
    }

    @Produces public void produce(Consumer<Message> consumer, MessageContext context) {
        ConcurrentLinkedQueue<Message> queue = outbound.get(context.getConnectionKey());
        if (queue == null) return;
        Message message = queue.poll();
        if (message != null) consumer.accept(message);
    }

    public Optional<RendezvousSession> request(UUID routeRequestId, LuffyNodeId targetNodeId,
                                               LuffyNodeId rendezvousNodeId, TorrentId contentTorrentId) {
        return coordinator.request(routeRequestId, targetNodeId, rendezvousNodeId, contentTorrentId);
    }
    public Optional<RendezvousSession> requestWithFallback(LuffyNodeId targetNodeId, TorrentId contentTorrentId,
                                                            java.util.List<RendezvousCoordinator.RouteCandidate> candidates) {
        return coordinator.requestWithFallback(targetNodeId, contentTorrentId, candidates);
    }

    public int activeSessionCount() { return coordinator.activeSessionCount(); }
    public RendezvousSessionRegistry sessions() { return coordinator.sessionRegistry(); }
    public boolean isNegotiated(ConnectionKey key) { return negotiatedConnections.contains(key); }
    /** Entrega estados terminais ao gerenciador de conectividade sem criar outro canal de rede. */
    public void setSessionFinishedListener(java.util.function.BiConsumer<RendezvousSession, RendezvousState> listener) {
        coordinator.setSessionFinishedListener(listener);
    }

    public void onPeerDisconnected(String infoHash, Peer peer, int remotePort) {
        if (peer == null || infoHash == null || !infoHash.matches("(?i)[a-f0-9]{40}")) return;
        TorrentId torrentId = TorrentId.fromBytes(java.util.HexFormat.of().parseHex(infoHash));
        negotiatedConnections.removeIf(key -> sameConnection(key, torrentId, peer, remotePort));
        outbound.keySet().removeIf(key -> sameConnection(key, torrentId, peer, remotePort));
        routePaths.removeConnection(new ConnectionKey(peer, remotePort, torrentId));
    }

    @Override public void close() {
        negotiatedConnections.clear();
        outbound.clear();
        coordinator.sessionRegistry().clear();
    }

    private void forwardOrConsume(LuffyRendezvousMessage message, ConnectionKey source) {
        boolean atDestination = message.direction() == LuffyRendezvousMessage.Direction.TO_RENDEZVOUS
                ? message.rendezvousNodeId().equals(localIdentity.nodeId())
                : message.direction() == LuffyRendezvousMessage.Direction.TO_REQUESTER
                ? message.requesterNodeId().equals(localIdentity.nodeId())
                : message.targetNodeId().equals(localIdentity.nodeId());
        if (atDestination) {
            coordinator.onMessage(message);
            return;
        }
        if (message.direction() == LuffyRendezvousMessage.Direction.TO_TARGET) {
            diagnostics.log(P2pDiagnostics.Layer.CONNECTIVITY, "[LF_RENDEZVOUS] PREPARE para target sem conexao direta foi rejeitado.");
            return;
        }
        Optional<ConnectionKey> expectedSource = message.direction() == LuffyRendezvousMessage.Direction.TO_RENDEZVOUS
                ? routePaths.previousHop(message.routeRequestId(), Instant.now())
                : routePaths.nextHop(message.routeRequestId(), Instant.now());
        Optional<ConnectionKey> destination = message.direction() == LuffyRendezvousMessage.Direction.TO_RENDEZVOUS
                ? routePaths.nextHop(message.routeRequestId(), Instant.now())
                : routePaths.previousHop(message.routeRequestId(), Instant.now());
        if (expectedSource.isEmpty() || !expectedSource.get().equals(source) || destination.isEmpty() || !send(destination.get(), message)) {
            diagnostics.log(P2pDiagnostics.Layer.CONNECTIVITY, "[LF_RENDEZVOUS] rota indisponivel: sessionId="
                    + abbreviated(message.sessionId()) + "; requestId=" + abbreviated(message.routeRequestId()) + ".");
        }
    }

    private boolean sendByRoute(LuffyRendezvousMessage message) {
        Optional<ConnectionKey> destination = message.direction() == LuffyRendezvousMessage.Direction.TO_RENDEZVOUS
                ? routePaths.nextHop(message.routeRequestId(), Instant.now())
                : routePaths.previousHop(message.routeRequestId(), Instant.now());
        if (destination.isEmpty()) return false;
        return send(destination.get(), message);
    }

    private boolean sendDirectToNode(LuffyNodeId destinationNodeId, LuffyRendezvousMessage message) {
        return connectedLuffys.findBestControlConnection(destinationNodeId)
                .map(ConnectedLuffyRegistry.ConnectedLuffy::connectionKey)
                .map(destination -> send(destination, message)).orElse(false);
    }

    private boolean send(ConnectionKey destination, LuffyRendezvousMessage message) {
        if (!negotiatedConnections.contains(destination) || connectedLuffys.findConnection(destination).isEmpty()) return false;
        if (message.endpoint().isPresent() && !privacyPolicy.allows(message.endpoint().orElseThrow())) {
            diagnostics.log(P2pDiagnostics.Layer.CONNECTIVITY,
                    "[PRIVACY] LF_RENDEZVOUS suprimido: endpoint privado/local nao sera compartilhado.");
            return false;
        }
        outbound.computeIfAbsent(destination, ignored -> new ConcurrentLinkedQueue<>()).add(message);
        return true;
    }

    private static boolean sameConnection(ConnectionKey key, TorrentId torrentId, Peer peer, int remotePort) {
        return key.getTorrentId().equals(torrentId) && key.getPeer().getInetAddress().equals(peer.getInetAddress())
                && key.getRemotePort() == remotePort;
    }
    private static String display(ConnectionKey key) { return key.getPeer().getInetAddress().getHostAddress() + ":" + key.getRemotePort(); }
    private static String abbreviated(UUID value) { return value.toString().substring(0, 8); }
}
