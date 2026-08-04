package dev.lufi.infrastructure.identity;

import bt.module.ProtocolModule;
import bt.module.ServiceModule;
import bt.net.ConnectionKey;
import bt.protocol.Message;
import bt.torrent.annotation.Consumes;
import bt.torrent.annotation.Produces;
import bt.torrent.messaging.MessageContext;
import com.google.inject.Binder;
import com.google.inject.Module;
import dev.lufi.infrastructure.P2pDiagnostics;
import dev.lufi.infrastructure.PeerCapabilities;
import dev.lufi.infrastructure.security.AbuseProtectionService;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Extensao BEP 10 {@code lf_identity}. Ela somente associa uma conexao a uma
 * identidade Luffy; peer ID BitTorrent, PEX e BEP 55 continuam inalterados.
 */
public final class LuffyIdentityExtension implements Module {
    public static final String EXTENSION_NAME = "lf_identity";

    private final LuffyNodeIdentity localIdentity;
    private final Function<MessageContext, LuffyIdentityMessage> localMessageFactory;
    private final P2pDiagnostics diagnostics;
    private final ConnectedLuffyRegistry connectedLuffys;
    private final AbuseProtectionService abuseProtection;
    private final Map<ConnectionKey, Negotiation> negotiations = new ConcurrentHashMap<>();
    private final Map<ConnectionKey, LuffyPeerCapabilities> capabilitiesByConnection = new ConcurrentHashMap<>();
    private volatile BiConsumer<ConnectionKey, LuffyPeerCapabilities> identityAcceptedListener = (key, capabilities) -> { };

    public LuffyIdentityExtension(LuffyNodeIdentity localIdentity, Supplier<LuffyIdentityMessage> localMessageSupplier,
                                  P2pDiagnostics diagnostics) {
        this(localIdentity, localMessageSupplier, diagnostics, new ConnectedLuffyRegistry());
    }

    public LuffyIdentityExtension(LuffyNodeIdentity localIdentity, Supplier<LuffyIdentityMessage> localMessageSupplier,
                                  P2pDiagnostics diagnostics, ConnectedLuffyRegistry connectedLuffys) {
        this(localIdentity, ignored -> localMessageSupplier.get(), diagnostics, connectedLuffys);
    }

    public LuffyIdentityExtension(LuffyNodeIdentity localIdentity, Function<MessageContext, LuffyIdentityMessage> localMessageFactory,
                                  P2pDiagnostics diagnostics) {
        this(localIdentity, localMessageFactory, diagnostics, new ConnectedLuffyRegistry());
    }

    public LuffyIdentityExtension(LuffyNodeIdentity localIdentity, Function<MessageContext, LuffyIdentityMessage> localMessageFactory,
                                  P2pDiagnostics diagnostics, ConnectedLuffyRegistry connectedLuffys) {
        this(localIdentity, localMessageFactory, diagnostics, connectedLuffys, new AbuseProtectionService());
    }

    public LuffyIdentityExtension(LuffyNodeIdentity localIdentity, Function<MessageContext, LuffyIdentityMessage> localMessageFactory,
                                  P2pDiagnostics diagnostics, ConnectedLuffyRegistry connectedLuffys,
                                  AbuseProtectionService abuseProtection) {
        this.localIdentity = Objects.requireNonNull(localIdentity, "localIdentity");
        this.localMessageFactory = Objects.requireNonNull(localMessageFactory, "localMessageFactory");
        this.diagnostics = diagnostics == null ? new P2pDiagnostics() : diagnostics;
        this.connectedLuffys = Objects.requireNonNull(connectedLuffys, "connectedLuffys");
        this.abuseProtection = Objects.requireNonNull(abuseProtection, "abuseProtection");
    }

    /** Integra a identidade validada a camadas locais sem expor nem alterar o peer ID BitTorrent. */
    public void setIdentityAcceptedListener(BiConsumer<ConnectionKey, LuffyPeerCapabilities> listener) {
        identityAcceptedListener = listener == null ? (key, capabilities) -> { } : listener;
    }

    @Override public void configure(Binder binder) {
        ProtocolModule.extend(binder).addExtendedMessageHandler(EXTENSION_NAME, new LuffyIdentityMessageHandler());
        ServiceModule.extend(binder).addMessagingAgent(this);
    }

    /** Usado apenas no runtime sem uTP, onde o agente BEP 55 nao esta instalado para observar o handshake. */
    public Module handshakeObserverModule() {
        return binder -> ServiceModule.extend(binder).addMessagingAgent(new HandshakeObserver(this));
    }

    /** Chamado pelo consumidor existente do extension handshake, sem criar um segundo observador. */
    public void onExtendedHandshake(ConnectionKey key, PeerCapabilities peerCapabilities) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(peerCapabilities, "peerCapabilities");
        if (!peerCapabilities.supportsLuffyIdentity()) {
            negotiations.remove(key);
            capabilitiesByConnection.remove(key);
            connectedLuffys.removeConnection(key);
            return;
        }
        negotiations.computeIfAbsent(key, ignored -> new Negotiation());
        diagnostics.log(P2pDiagnostics.Layer.CONNECTIVITY, "LF_IDENTITY NEGOTIATED: infoHash=" + infoHash(key)
                + "; peer=" + display(key) + "; aguardando identidade do peer.");
    }

    @Consumes public void consume(LuffyIdentityMessage message, MessageContext context) {
        ConnectionKey key = context.getConnectionKey();
        if (!negotiations.containsKey(key)) {
            throw new IllegalStateException("lf_identity recebida sem anuncio no extension handshake");
        }
        LuffyPeerCapabilities received = message.capabilities();
        LuffyPeerCapabilities previous = capabilitiesByConnection.putIfAbsent(key, received);
        if (previous != null && !previous.nodeId().equals(received.nodeId())) {
            abuseProtection.recordViolation(AbuseProtectionService.peerKey(key.getPeer().getInetAddress()),
                    AbuseProtectionService.Violation.IDENTITY_CHANGED, java.time.Instant.now());
            capabilitiesByConnection.remove(key, previous);
            negotiations.remove(key);
            connectedLuffys.removeConnection(key);
            diagnostics.log(P2pDiagnostics.Layer.CONNECTIVITY, "LF_IDENTITY CONFLICT: infoHash=" + infoHash(key)
                    + "; peer=" + display(key) + "; nodeId mudou na mesma conexao; conexao sera rejeitada.");
            throw new IllegalStateException("nodeId lf_identity mudou na mesma conexao");
        }
        if (previous != null) capabilitiesByConnection.replace(key, previous, received);
        connectedLuffys.registerConnection(ConnectedLuffyRegistry.ConnectedLuffy.identified(key, received, java.time.Instant.now()));
        try {
            identityAcceptedListener.accept(key, received);
        } catch (RuntimeException error) {
            diagnostics.log(P2pDiagnostics.Layer.CONNECTIVITY, "LF_IDENTITY OBSERVER FAILED: infoHash=" + infoHash(key)
                    + "; peer=" + display(key) + "; motivo=" + error.getClass().getSimpleName() + ".");
        }
        diagnostics.log(P2pDiagnostics.Layer.CONNECTIVITY, "LF_IDENTITY ACCEPTED: infoHash=" + infoHash(key)
                + "; peer=" + display(key) + "; nodeId=" + received.nodeId().asText().substring(0, 12)
                + "...; route=" + received.supportsRoute() + "; rendezvous=" + received.supportsRendezvous()
                + "; utp=" + received.supportsUtp() + "; holePunch=" + received.supportsHolePunch() + ".");
        diagnostics.event(P2pDiagnostics.Category.LF_IDENTITY, "IDENTITY_ACCEPTED",
                "nodeId", abbreviated(received.nodeId()), "route", received.supportsRoute(),
                "rendezvous", received.supportsRendezvous(), "utp", received.supportsUtp(),
                "holePunch", received.supportsHolePunch());
    }

    @Produces public void produce(Consumer<Message> consumer, MessageContext context) {
        Negotiation negotiation = negotiations.get(context.getConnectionKey());
        if (negotiation == null || !negotiation.pendingLocalIdentity.compareAndSet(true, false)) return;
        LuffyIdentityMessage local = localMessageFactory.apply(context);
        if (!local.nodeId().equals(localIdentity.nodeId())) {
            negotiation.pendingLocalIdentity.set(true);
            throw new IllegalStateException("fornecedor lf_identity retornou nodeId diferente da identidade persistente");
        }
        try {
            consumer.accept(local);
            diagnostics.log(P2pDiagnostics.Layer.CONNECTIVITY, "LF_IDENTITY SENT: infoHash=" + infoHash(context.getConnectionKey())
                    + "; peer=" + display(context.getConnectionKey()) + "; protocolo=" + local.protocolVersion() + ".");
        } catch (RuntimeException error) {
            negotiation.pendingLocalIdentity.set(true);
            throw error;
        }
    }

    /** O lifecycle BitTorrent ja existente chama esta limpeza ao encerrar o peer. */
    public void onPeerDisconnected(String infoHash, bt.net.Peer peer, int remotePort) {
        if (peer == null) return;
        bt.metainfo.TorrentId torrentId = bt.metainfo.TorrentId.fromBytes(hexBytes(infoHash));
        connectedLuffys.removeConnection(torrentId, peer, remotePort);
        negotiations.keySet().removeIf(key -> sameConnection(key, torrentId, peer, remotePort));
        capabilitiesByConnection.keySet().removeIf(key -> sameConnection(key, torrentId, peer, remotePort));
    }

    public Optional<LuffyPeerCapabilities> peerCapabilities(ConnectionKey key) {
        return Optional.ofNullable(capabilitiesByConnection.get(key));
    }

    public int identifiedPeerCount() { return capabilitiesByConnection.size(); }

    private static String infoHash(ConnectionKey key) {
        StringBuilder result = new StringBuilder(40);
        for (byte value : key.getTorrentId().getBytes()) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }

    private static String display(ConnectionKey key) {
        return key.getPeer().getInetAddress().getHostAddress() + ":" + key.getRemotePort();
    }
    private static String abbreviated(LuffyNodeId nodeId) { return nodeId.asText().substring(0, 12) + "..."; }

    private static byte[] hexBytes(String infoHash) {
        if (infoHash == null || !infoHash.matches("(?i)[a-f0-9]{40}")) {
            throw new IllegalArgumentException("infoHash invalido para limpeza de lf_identity");
        }
        byte[] result = new byte[20];
        for (int index = 0; index < result.length; index++) {
            result[index] = (byte) Integer.parseInt(infoHash.substring(index * 2, index * 2 + 2), 16);
        }
        return result;
    }

    private static boolean sameConnection(ConnectionKey key, bt.metainfo.TorrentId torrentId, bt.net.Peer peer, int remotePort) {
        return key.getTorrentId().equals(torrentId) && key.getPeer().getInetAddress().equals(peer.getInetAddress())
                && key.getRemotePort() == remotePort;
    }

    private static final class Negotiation {
        private final AtomicBoolean pendingLocalIdentity = new AtomicBoolean(true);
    }

    /** Agente publico porque o compilador do bt-core usa {@code publicLookup()}. */
    public static final class HandshakeObserver {
        private final LuffyIdentityExtension extension;

        public HandshakeObserver(LuffyIdentityExtension extension) {
            this.extension = Objects.requireNonNull(extension, "extension");
        }

        @Consumes public void consume(bt.protocol.extended.ExtendedHandshake handshake, MessageContext context) {
            extension.onExtendedHandshake(context.getConnectionKey(),
                    PeerCapabilities.fromExtensionHandshake(handshake.getSupportedMessageTypes()));
        }
    }
}
