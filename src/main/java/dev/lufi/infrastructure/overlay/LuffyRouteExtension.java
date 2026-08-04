package dev.lufi.infrastructure.overlay;

import bt.module.ProtocolModule;
import bt.module.ServiceModule;
import bt.net.ConnectionKey;
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
import dev.lufi.infrastructure.identity.LuffyPeerCapabilities;
import dev.lufi.infrastructure.security.AbuseProtectionService;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Extensao BEP 10 opcional {@code lf_route}. Ela so acrescenta pequenas
 * mensagens de controle a uma conexao BitTorrent ja existente.
 */
public final class LuffyRouteExtension implements Module, AutoCloseable {
    public static final String EXTENSION_NAME = "lf_route";

    private final Set<ConnectionKey> negotiatedConnections = ConcurrentHashMap.newKeySet();
    private final Map<ConnectionKey, ConcurrentLinkedQueue<Message>> outbound = new ConcurrentHashMap<>();
    private final ConnectedLuffyRegistry connectedLuffys;
    private final P2pDiagnostics diagnostics;
    private final FindNodeService findNodeService;
    private final AbuseProtectionService abuseProtection;

    public LuffyRouteExtension(LuffyNodeIdentity localIdentity, Supplier<LuffyPeerCapabilities> localCapabilities,
                               bt.metainfo.TorrentId bootstrapTorrent, ConnectedLuffyRegistry connectedLuffys,
                               P2pDiagnostics diagnostics) {
        this(localIdentity, localCapabilities, bootstrapTorrent, connectedLuffys, diagnostics, new AbuseProtectionService());
    }

    public LuffyRouteExtension(LuffyNodeIdentity localIdentity, Supplier<LuffyPeerCapabilities> localCapabilities,
                               bt.metainfo.TorrentId bootstrapTorrent, ConnectedLuffyRegistry connectedLuffys,
                               P2pDiagnostics diagnostics, AbuseProtectionService abuseProtection) {
        this.connectedLuffys = Objects.requireNonNull(connectedLuffys, "connectedLuffys");
        this.diagnostics = diagnostics == null ? new P2pDiagnostics() : diagnostics;
        this.abuseProtection = Objects.requireNonNull(abuseProtection, "abuseProtection");
        this.findNodeService = new FindNodeService(localIdentity, localCapabilities, bootstrapTorrent, connectedLuffys,
                new ExtensionDispatcher(), this.diagnostics, new FindNodeRoutingConfig(
                FindNodeRoutingConfig.INITIAL_DEFAULT_TTL, FindNodeRoutingConfig.INITIAL_MAXIMUM_TTL,
                FindNodeRoutingConfig.INITIAL_MAXIMUM_FORWARD_PEERS, FindNodeRoutingConfig.INITIAL_SEARCH_TIMEOUT,
                FindNodeRoutingConfig.INITIAL_ROUTE_CACHE_TTL, Duration.ofSeconds(5), Duration.ofMinutes(1), 24), abuseProtection);
    }

    @Override public void configure(Binder binder) {
        ProtocolModule.extend(binder).addExtendedMessageHandler(EXTENSION_NAME,
                new LuffyRouteMessageHandler(() -> abuseProtection.config().maxPayloadBytes()));
        ServiceModule.extend(binder).addMessagingAgent(this);
    }

    /** O extension handshake e a unica negociacao: nao ha socket ou handshake paralelo. */
    @Consumes public void consume(ExtendedHandshake handshake, MessageContext context) {
        ConnectionKey key = context.getConnectionKey();
        PeerCapabilities capabilities = PeerCapabilities.fromExtensionHandshake(handshake.getSupportedMessageTypes());
        if (!capabilities.supportsLuffyRoute()) {
            negotiatedConnections.remove(key);
            outbound.remove(key);
            return;
        }
        negotiatedConnections.add(key);
        diagnostics.log(P2pDiagnostics.Layer.CONNECTIVITY, "[LF_ROUTE] negociado: infoHash=" + infoHash(key)
                + "; peer=" + key.getPeer().getInetAddress().getHostAddress() + ":" + key.getRemotePort() + ".");
    }

    @Consumes public void consume(LuffyRouteMessage message, MessageContext context) {
        ConnectionKey key = context.getConnectionKey();
        if (!negotiatedConnections.contains(key)) {
            diagnostics.log(P2pDiagnostics.Layer.CONNECTIVITY, "[LF_ROUTE] mensagem rejeitada sem extensao negociada.");
            return;
        }
        findNodeService.onMessage(message, key);
    }

    @Produces public void produce(Consumer<Message> consumer, MessageContext context) {
        ConcurrentLinkedQueue<Message> queue = outbound.get(context.getConnectionKey());
        if (queue == null) return;
        Message message = queue.poll();
        if (message != null) consumer.accept(message);
    }

    public CompletionStage<RouteSearchResult> findNode(LuffyNodeId targetNodeId, String contentInfoHash) {
        return findNodeService.findNode(targetNodeId, contentInfoHash);
    }

    /** Busca que preserva o requestId necessario para mensagens de controle na rota vencedora. */
    public FindNodeService.RouteSearch startFindNode(LuffyNodeId targetNodeId, String contentInfoHash) {
        return findNodeService.startFindNode(targetNodeId, contentInfoHash);
    }

    public OverlayRoutePathRegistry routePaths() { return findNodeService.routePaths(); }

    /** Limpeza chamada pelo lifecycle BitTorrent ja existente. */
    public void onPeerDisconnected(String infoHash, bt.net.Peer peer, int remotePort) {
        if (peer == null) return;
        bt.metainfo.TorrentId torrentId = bt.metainfo.TorrentId.fromBytes(java.util.HexFormat.of().parseHex(infoHash));
        negotiatedConnections.removeIf(key -> key.getTorrentId().equals(torrentId)
                && key.getPeer().getInetAddress().equals(peer.getInetAddress())
                && key.getRemotePort() == remotePort);
        outbound.keySet().removeIf(key -> key.getTorrentId().equals(torrentId)
                && key.getPeer().getInetAddress().equals(peer.getInetAddress())
                && key.getRemotePort() == remotePort);
    }

    public boolean isNegotiated(ConnectionKey key) { return negotiatedConnections.contains(key); }
    public int negotiatedConnectionCount() { return negotiatedConnections.size(); }

    @Override public void close() {
        negotiatedConnections.clear();
        outbound.clear();
        findNodeService.close();
    }

    private static String infoHash(ConnectionKey key) {
        return java.util.HexFormat.of().formatHex(key.getTorrentId().getBytes());
    }

    private final class ExtensionDispatcher implements FindNodeService.RouteMessageDispatcher {
        @Override public boolean send(ConnectionKey destination, LuffyRouteMessage message) {
            if (!canSend(destination)) return false;
            outbound.computeIfAbsent(destination, ignored -> new ConcurrentLinkedQueue<>()).add(message);
            return true;
        }

        @Override public boolean canSend(ConnectionKey destination) {
            return destination != null && negotiatedConnections.contains(destination)
                    && connectedLuffys.findConnection(destination).isPresent();
        }
    }
}
