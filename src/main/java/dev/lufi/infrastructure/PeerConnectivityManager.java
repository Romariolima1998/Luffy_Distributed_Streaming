package dev.lufi.infrastructure;

import bt.net.Peer;
import dev.lufi.infrastructure.identity.LuffyNodeId;
import dev.lufi.infrastructure.identity.LuffyPeerCapabilities;
import java.io.IOException;
import java.net.ConnectException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Camada única entre descoberta DHT e o motor BitTorrent, com diagnóstico de socket por tentativa. */
public final class PeerConnectivityManager implements AutoCloseable {
    private static final int MAX_DIRECT_PROMOTIONS = 5;
    private static final List<Duration> RETRY_BACKOFFS = List.of(Duration.ofSeconds(5), Duration.ofSeconds(15),
            Duration.ofSeconds(30), Duration.ofMinutes(2));
    private static final Duration CONNECT_WINDOW = Duration.ofSeconds(12);
    private static final Duration HAPPY_EYEBALLS_COALESCE = Duration.ofMillis(75);
    private static final Duration HAPPY_EYEBALLS_STAGGER = Duration.ofMillis(300);
    private static final int MAX_HAPPY_EYEBALLS_PATHS = 4;

    public enum AddressFamily { IPV4, IPV6 }
    public enum Transport { TCP, UTP }
    /** A origem permanece associada ao endpoint mesmo quando ele chega por mais de uma fonte. */
    public enum DiscoveryOrigin { DHT, PEX, TRACKER, PEER_CACHE, MAGNET_METADATA, UNKNOWN }
    public enum TransportSupport { UNKNOWN, SUPPORTED, NOT_SUPPORTED }
    public enum Strategy { DIRECT_IPV4, DIRECT_IPV6, DIRECT_UTP, NAT_MAPPING, HOLE_PUNCHING, NONE }
    public enum ConnectionState {
        DISCOVERED, DIRECT_CONNECT_PENDING, DIRECT_CONNECTING, DIRECT_CONNECT_FAILED,
        PORT_MAPPING_PENDING, HOLE_PUNCH_PENDING, HOLE_PUNCHING, CONNECTED, UNREACHABLE
    }
    public enum SocketFailure {
        NONE, TIMEOUT, CONNECTION_REFUSED, NO_ROUTE, CONNECTION_RESET, IO_EXCEPTION, SOCKET_EXCEPTION, HANDSHAKE_REJECTED, UNKNOWN
    }

    /** Caminho individual de um peer. Um mesmo peer pode manter quatro caminhos independentes. */
    public record PeerEndpoint(AddressFamily addressFamily, InetAddress address, int port, Transport transport) {
        public PeerEndpoint {
            Objects.requireNonNull(address, "address");
            Objects.requireNonNull(addressFamily, "addressFamily");
            Objects.requireNonNull(transport, "transport");
            if (port < 1 || port > 65_535) throw new IllegalArgumentException("Porta de peer inválida");
            if ((addressFamily == AddressFamily.IPV4) != (address instanceof Inet4Address)) {
                throw new IllegalArgumentException("A família do endpoint não corresponde ao endereço IP");
            }
        }
        public PeerEndpoint(InetAddress address, int port) { this(familyOf(address), address, port, Transport.TCP); }
        public PeerEndpoint(InetAddress address, int port, Transport transport) { this(familyOf(address), address, port, transport); }
        public AddressFamily family() { return addressFamily; }
        public String display() { return (addressFamily == AddressFamily.IPV6 ? "[" + address.getHostAddress() + "]" : address.getHostAddress()) + ":" + port; }
        private static AddressFamily familyOf(InetAddress address) {
            if (address instanceof Inet4Address) return AddressFamily.IPV4;
            if (address instanceof Inet6Address) return AddressFamily.IPV6;
            throw new IllegalArgumentException("Endereço IP sem família reconhecida");
        }
    }

    /** Endereços que o próprio SocketChannel observou; não são inferidos a partir da DHT. */
    public record SocketAddresses(String local, String remote) {
        public SocketAddresses {
            local = blankToUnknown(local); remote = blankToUnknown(remote);
        }
        public static SocketAddresses pending(PeerEndpoint peer) { return new SocketAddresses("pendente", peer.display()); }
        private static String blankToUnknown(String value) { return value == null || value.isBlank() ? "desconhecido" : value; }
    }

    /** Última tentativa direta do peer. O log contém uma linha completa para cada tentativa. */
    public record SocketAttempt(
            String protocol,
            SocketAddresses addresses,
            Instant connectStartedAt,
            Instant finishedAt,
            SocketFailure failure,
            String detail) {
        public long durationMillis() {
            return connectStartedAt == null || finishedAt == null ? -1 : Duration.between(connectStartedAt, finishedAt).toMillis();
        }
    }

    public record PeerState(
            String infoHash, PeerEndpoint endpoint, AddressFamily family, TransportSupport tcp, TransportSupport utp,
            Strategy strategy, ConnectionState connection, int directAttempts, Instant lastSeen,
            List<DiscoveryOrigin> origins, Instant nextRetryAt, String failureReason, SocketAttempt lastSocketAttempt) { }
    public record Promotion(String infoHash, PeerEndpoint endpoint, Strategy strategy) { }

    @FunctionalInterface public interface BitTorrentPromoter { void promote(Promotion promotion); }
    /** Permite que uma política de sessão adie uma promoção antes de qualquer socket ser aberto. */
    @FunctionalInterface public interface ConnectionAdmission { boolean admit(Promotion promotion); }
    @FunctionalInterface public interface HolePunchRequester { void request(String infoHash, PeerEndpoint target); }
    /** Fallback opcional: o manager continua dono da ordem de estrategias, nao do overlay. */
    @FunctionalInterface public interface OverlayRendezvousFallback {
        CompletionStage<OverlayRendezvousResult> onDirectConnectivityExhausted(PeerConnectivityContext context);
    }
    /** Consulta leve fornecida pela camada de sessao; evita iniciar overlay para torrent encerrado. */
    @FunctionalInterface public interface TorrentActivity { boolean isActive(String infoHash); }
    /**
     * Observa endpoints que chegaram por uma fonte de descoberta. A DHT continua
     * responsável somente pela descoberta; consumidores não devem abrir sockets
     * a partir deste callback.
     */
    @FunctionalInterface public interface PeerEndpointObserver { void observed(String infoHash, PeerEndpoint endpoint); }

    /** Fotografia imutavel entregue ao fallback depois de TCP, uTP e BEP55 local. */
    public record PeerConnectivityContext(
            String infoHash,
            PeerEndpoint targetEndpoint,
            Optional<LuffyNodeId> targetNodeId,
            Optional<LuffyPeerCapabilities> targetCapabilities,
            boolean torrentActive,
            boolean peerRemoved,
            boolean directConnectionSucceeded,
            boolean backoffActive,
            boolean applicationClosing,
            Instant observedAt) {
        public PeerConnectivityContext {
            validateInfoHashStatic(infoHash);
            Objects.requireNonNull(targetEndpoint, "targetEndpoint");
            if (targetEndpoint.transport() != Transport.UTP) {
                throw new IllegalArgumentException("O fallback de rendezvous exige endpoint uTP/UDP");
            }
            targetNodeId = targetNodeId == null ? Optional.empty() : targetNodeId;
            targetCapabilities = targetCapabilities == null ? Optional.empty() : targetCapabilities;
            Objects.requireNonNull(observedAt, "observedAt");
            if (targetNodeId.isPresent() && targetCapabilities.isPresent()
                    && !targetNodeId.get().equals(targetCapabilities.get().nodeId())) {
                throw new IllegalArgumentException("NodeId e capacidades do target nao correspondem");
            }
        }
    }

    /** Resultado de admissao do overlay; STARTED nao significa transferencia concluida. */
    public record OverlayRendezvousResult(boolean started, String reason, Optional<UUID> sessionId) {
        public OverlayRendezvousResult {
            reason = reason == null || reason.isBlank() ? "motivo nao informado" : reason;
            sessionId = sessionId == null ? Optional.empty() : sessionId;
            if (started != sessionId.isPresent()) {
                throw new IllegalArgumentException("Resultado iniciado exige exatamente um sessionId");
            }
        }
        public static OverlayRendezvousResult started(UUID sessionId) {
            return new OverlayRendezvousResult(true, "sessao lf_rendezvous iniciada", Optional.of(Objects.requireNonNull(sessionId, "sessionId")));
        }
        public static OverlayRendezvousResult skipped(String reason) {
            return new OverlayRendezvousResult(false, reason, Optional.empty());
        }
    }

    private final P2pDiagnostics diagnostics;
    private final BitTorrentPromoter promoter;
    private final Map<String, MutablePeerState> peers = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> connectTimers = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> retryTimers = new ConcurrentHashMap<>();
    private final Map<EndpointPath, Boolean> availablePaths = new ConcurrentHashMap<>();
    private final Map<String, ConnectionRace> connectionRaces = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());
    private volatile ConnectivityProfile localConnectivity = ConnectivityProfile.unavailable();
    private volatile ConnectionAdmission connectionAdmission = promotion -> true;
    private volatile HolePunchRequester holePunchRequester = (infoHash, target) -> { };
    private volatile PeerEndpointObserver peerEndpointObserver = (infoHash, endpoint) -> { };
    private volatile OverlayRendezvousFallback overlayRendezvousFallback = context ->
            CompletableFuture.completedFuture(OverlayRendezvousResult.skipped("overlay lf_rendezvous nao configurado"));
    private volatile TorrentActivity torrentActivity = ignored -> true;
    private volatile boolean closing;

    public PeerConnectivityManager(P2pDiagnostics diagnostics, BitTorrentPromoter promoter) {
        this.diagnostics = diagnostics == null ? new P2pDiagnostics() : diagnostics;
        this.promoter = Objects.requireNonNull(promoter, "promoter");
        availablePaths.put(new EndpointPath(AddressFamily.IPV4, Transport.TCP), true);
    }

    public void setLocalConnectivity(ConnectivityProfile profile) { localConnectivity = profile == null ? ConnectivityProfile.unavailable() : profile; }
    public void setConnectionAdmission(ConnectionAdmission admission) { connectionAdmission = admission == null ? promotion -> true : admission; }
    public void setHolePunchRequester(HolePunchRequester requester) { holePunchRequester = requester == null ? (infoHash, target) -> { } : requester; }
    public void setOverlayRendezvousFallback(OverlayRendezvousFallback fallback) {
        overlayRendezvousFallback = fallback == null ? context -> CompletableFuture.completedFuture(
                OverlayRendezvousResult.skipped("overlay lf_rendezvous nao configurado")) : fallback;
    }
    public void setTorrentActivity(TorrentActivity activity) { torrentActivity = activity == null ? ignored -> true : activity; }
    public void setPeerEndpointObserver(PeerEndpointObserver observer) {
        peerEndpointObserver = observer == null ? (infoHash, endpoint) -> { } : observer;
    }

    /** Registra o motor disponível para uma família/transport, sem apagar endpoints já descobertos. */
    public void setPathAvailable(AddressFamily family, Transport transport, boolean available) {
        availablePaths.put(new EndpointPath(family, transport), available);
        diagnostics.log("CONNECTIVITY PATH: " + family + "/" + transport + "=" + (available ? "ativo" : "registrado, aguardando motor") + ".");
    }

    /** Entrada exclusiva da DHT. Descoberta não é tentativa de conexão. */
    public void onDhtPeerDiscovered(String infoHash, Peer peer) {
        if (peer == null || peer.isPortUnknown()) {
            diagnostics.log("PEER DISCOVERED: infoHash=" + infoHash + "; ignorado porque a DHT não informou porta.");
            return;
        }
        onPeerEndpointDiscovered(infoHash, new PeerEndpoint(peer.getInetAddress(), peer.getPort(), Transport.TCP), DiscoveryOrigin.DHT);
    }

    /** Armazena qualquer caminho de um peer. A DHT usa TCP; uTP e IPv6 podem chegar por fontes futuras. */
    public void onDhtPeerDiscovered(String infoHash, PeerEndpoint endpoint) {
        onPeerEndpointDiscovered(infoHash, endpoint, DiscoveryOrigin.DHT);
    }

    /** Entrada do BEP 11: peer recebido de outro participante já conectado ao swarm. */
    public void onPexPeerDiscovered(String infoHash, Peer peer) {
        if (peer == null || peer.isPortUnknown()) {
            diagnostics.log("PEX PEER DISCOVERED: infoHash=" + infoHash + "; ignorado porque PEX não informou porta.");
            return;
        }
        onPeerEndpointDiscovered(infoHash, new PeerEndpoint(peer.getInetAddress(), peer.getPort(), Transport.TCP), DiscoveryOrigin.PEX);
    }

    /**
     * O bt-core ja registrou este peer no seu {@code IPeerRegistry} ao receber
     * a resposta do tracker. Aqui apenas unificamos a descoberta e a origem no
     * Connectivity Manager: promover novamente o mesmo endpoint criaria uma
     * segunda tentativa TCP desnecessaria.
     */
    public void onTrackerPeerDiscovered(String infoHash, Peer peer) {
        onPeerDiscoveredFrom(infoHash, peer, DiscoveryOrigin.TRACKER, false);
    }
    public void onPeerCachePeerDiscovered(String infoHash, Peer peer) { onPeerDiscoveredFrom(infoHash, peer, DiscoveryOrigin.PEER_CACHE); }
    public void onMagnetMetadataPeerDiscovered(String infoHash, Peer peer) { onPeerDiscoveredFrom(infoHash, peer, DiscoveryOrigin.MAGNET_METADATA); }
    private void onPeerDiscoveredFrom(String infoHash, Peer peer, DiscoveryOrigin origin) {
        if (peer == null || peer.isPortUnknown()) return;
        onPeerEndpointDiscovered(infoHash, new PeerEndpoint(peer.getInetAddress(), peer.getPort(), Transport.TCP), origin);
    }
    private void onPeerDiscoveredFrom(String infoHash, Peer peer, DiscoveryOrigin origin, boolean scheduleConnectivityAttempt) {
        if (peer == null || peer.isPortUnknown()) return;
        recordPeerEndpoint(infoHash, new PeerEndpoint(peer.getInetAddress(), peer.getPort(), Transport.TCP), origin,
                scheduleConnectivityAttempt);
    }

    /** Armazena qualquer caminho de um peer sem deduplicar outra família ou transporte. */
    public void onPeerEndpointDiscovered(String infoHash, PeerEndpoint endpoint) {
        onPeerEndpointDiscovered(infoHash, endpoint, DiscoveryOrigin.UNKNOWN);
    }

    /** Armazena o endpoint e a fonte que o apresentou sem misturar descoberta com conexão. */
    public void onPeerEndpointDiscovered(String infoHash, PeerEndpoint endpoint, DiscoveryOrigin origin) {
        recordPeerEndpoint(infoHash, endpoint, origin, true);
    }

    /**
     * Registra uma descoberta de qualquer fonte. Somente fontes externas ao
     * bt-core (DHT, PEX e hints do magnet) pedem uma promocao explicita; o
     * tracker ja foi entregue ao registro interno do motor antes deste ponto.
     */
    private void recordPeerEndpoint(String infoHash, PeerEndpoint endpoint, DiscoveryOrigin origin, boolean scheduleConnectivityAttempt) {
        validateInfoHash(infoHash);
        origin = origin == null ? DiscoveryOrigin.UNKNOWN : origin;
        peerEndpointObserver.observed(infoHash, endpoint);
        String key = key(infoHash, endpoint);
        DiscoveryOrigin discoveryOrigin = origin;
        boolean explicitLanPeerHint = discoveryOrigin == DiscoveryOrigin.MAGNET_METADATA && isPrivateLanIpv4(endpoint.address());
        MutablePeerState state = peers.computeIfAbsent(key, ignored -> {
            MutablePeerState created = new MutablePeerState(infoHash, endpoint);
            created.origins.add(discoveryOrigin);
            diagnostics.log(P2pDiagnostics.Layer.DISCOVERY, "PEER DISCOVERED: infoHash=" + infoHash + "; peer=" + endpoint.display()
                    + "; origem=" + discoveryOrigin + "; família=" + endpoint.family() + "; transporte=" + endpoint.transport() + "; TCP=UNKNOWN; uTP=UNKNOWN.");
            return created;
        });
        synchronized (state) {
            state.lastSeen = Instant.now();
            if (state.origins.add(discoveryOrigin)) {
                diagnostics.log("PEER ORIGIN RECORDED: infoHash=" + infoHash + "; peer=" + endpoint.display() + "; origem=" + discoveryOrigin + ".");
            }
            if (state.connection == ConnectionState.CONNECTED) return;
            if (!scheduleConnectivityAttempt) {
                // O PeerDiscoveredEvent do bt-core e disparado antes de sua
                // propria conexao TCP. Conservamos qualquer tentativa DHT/PEX
                // que ja esteja pendente e, para um peer novo, apenas deixamos
                // o lifecycle observar a conexao nativa do tracker.
                if (state.connection == ConnectionState.DISCOVERED) {
                    state.strategy = explicitLanPeerHint ? Strategy.DIRECT_IPV4 : chooseStrategy(endpoint);
                    if (state.strategy == Strategy.NONE) {
                        state.connection = ConnectionState.UNREACHABLE;
                        state.failureReason = unreachableReason(endpoint);
                    } else {
                        state.failureReason = "peer informado por tracker; aguardando conexao TCP nativa do bt-core";
                    }
                }
                return;
            }
            state.strategy = explicitLanPeerHint ? Strategy.DIRECT_IPV4 : chooseStrategy(endpoint);
            if (state.strategy == Strategy.NONE) {
                state.connection = ConnectionState.UNREACHABLE;
                state.failureReason = unreachableReason(endpoint);
                diagnostics.log("PEER UNREACHABLE: infoHash=" + infoHash + "; peer=" + endpoint.display() + "; motivo=" + state.failureReason + ".");
                return;
            }
            if (explicitLanPeerHint) {
                diagnostics.log("PEER LAN HINT: infoHash=" + infoHash + "; peer=" + endpoint.display()
                        + "; origem=MAGNET_METADATA; conexão direta local autorizada pelo magnet.");
            }
            if (!isPathAvailable(endpoint)) {
                state.connection = ConnectionState.DISCOVERED;
                state.failureReason = "caminho " + endpoint.family() + "/" + endpoint.transport() + " registrado; aguardando motor compatível";
                diagnostics.log("PEER ENDPOINT STORED: infoHash=" + infoHash + "; peer=" + endpoint.display() + "; transporte=" + endpoint.transport()
                        + "; motivo=" + state.failureReason + ".");
                return;
            }
            if (state.directAttempts >= MAX_DIRECT_PROMOTIONS) {
                state.connection = ConnectionState.UNREACHABLE;
                state.failureReason = "limite de " + MAX_DIRECT_PROMOTIONS + " conexões diretas atingido";
                diagnostics.log("PEER UNREACHABLE: infoHash=" + infoHash + "; peer=" + endpoint.display() + "; motivo=" + state.failureReason + ".");
                return;
            }
            if (state.connection == ConnectionState.DIRECT_CONNECT_PENDING || state.connection == ConnectionState.DIRECT_CONNECTING) return;
            if (state.nextRetryAt != null && Instant.now().isBefore(state.nextRetryAt)) {
                if (state.lastSuppressedAt == null || Duration.between(state.lastSuppressedAt, Instant.now()).compareTo(Duration.ofSeconds(1)) >= 0) {
                    state.lastSuppressedAt = Instant.now();
                    diagnostics.log("PEER RETRY SUPPRESSED: infoHash=" + infoHash + "; key=" + key
                            + "; próximo retry=" + state.nextRetryAt + "; origem repetida=" + discoveryOrigin + ".");
                }
                return;
            }

            state.connection = ConnectionState.DIRECT_CONNECT_PENDING;
            state.failureReason = "";
            state.handshakeStarted = false;
            state.attempt = new SocketAttempt(endpoint.transport().name(), SocketAddresses.pending(endpoint), null, null, SocketFailure.NONE,
                    "aguardando escalonamento Happy Eyeballs");
        }
        queueHappyEyeballs(infoHash, endpoint);
    }

    /**
     * Associa uma identidade recebida por {@code lf_identity} aos caminhos ja
     * conhecidos do mesmo peer. A identidade nao abre conexao nem converte
     * endereco TCP em UDP; ela so habilita um fallback futuro se a conexao
     * direta cair depois de ter sido identificada.
     */
    public void onLuffyIdentity(String infoHash, Peer peer, int remotePort, LuffyPeerCapabilities capabilities) {
        validateInfoHash(infoHash);
        Objects.requireNonNull(peer, "peer");
        Objects.requireNonNull(capabilities, "capabilities");
        if (remotePort < 1 || remotePort > 65_535) throw new IllegalArgumentException("porta remota invalida");
        List<MutablePeerState> tcpMatches = peers.values().stream()
                .filter(state -> state.infoHash.equalsIgnoreCase(infoHash))
                .filter(state -> state.endpoint.address().equals(peer.getInetAddress()))
                .filter(state -> state.endpoint.transport() == Transport.TCP)
                .filter(state -> state.endpoint.port() == remotePort)
                .toList();
        if (tcpMatches.isEmpty()) {
            diagnostics.log(P2pDiagnostics.Layer.CONNECTIVITY, "LF_IDENTITY IGNORADA: infoHash=" + infoHash
                    + "; peer=" + peer.getInetAddress().getHostAddress() + ":" + remotePort + "; endpoint nao esta no gerenciador.");
            return;
        }
        for (MutablePeerState state : tcpMatches) {
            synchronized (state) {
                if (state.luffyNodeId.isPresent() && !state.luffyNodeId.get().equals(capabilities.nodeId())) {
                    diagnostics.log(P2pDiagnostics.Layer.CONNECTIVITY, "LF_IDENTITY CONFLICT: infoHash=" + infoHash
                            + "; peer=" + state.endpoint.display() + "; nodeId diferente foi rejeitado.");
                    continue;
                }
                state.luffyNodeId = Optional.of(capabilities.nodeId());
                state.luffyCapabilities = Optional.of(capabilities);
            }
        }
        List<MutablePeerState> sameAddressUtp = peers.values().stream()
                .filter(state -> state.infoHash.equalsIgnoreCase(infoHash))
                .filter(state -> state.endpoint.address().equals(peer.getInetAddress()))
                .filter(state -> state.endpoint.transport() == Transport.UTP)
                .toList();
        if (tcpMatches.size() == 1 && sameAddressUtp.size() == 1) {
            MutablePeerState utpState = sameAddressUtp.getFirst();
            synchronized (utpState) {
                utpState.luffyNodeId = Optional.of(capabilities.nodeId());
                utpState.luffyCapabilities = Optional.of(capabilities);
            }
        } else if (!sameAddressUtp.isEmpty()) {
            diagnostics.log(P2pDiagnostics.Layer.CONNECTIVITY, "LF_IDENTITY UTP NAO ASSOCIADA: infoHash=" + infoHash
                    + "; TCP no mesmo endereco=" + tcpMatches.size() + "; endpoints uTP=" + sameAddressUtp.size()
                    + "; motivo=associacao ambigua entre endpoints.");
        }
        diagnostics.log(P2pDiagnostics.Layer.CONNECTIVITY, "LF_IDENTITY ASSOCIADA A CONNECTIVITY: infoHash=" + infoHash
                + "; peer=" + peer.getInetAddress().getHostAddress() + ":" + remotePort
                + "; nodeId=" + capabilities.nodeId().asText().substring(0, 12) + "...; rendezvous="
                + capabilities.supportsDistributedRendezvous() + ".");
    }

    /** Ponto imediatamente anterior à chamada SocketChannel.connect() dentro do motor BitTorrent. */
    public void onTcpConnectStart(String infoHash, Peer peer) {
        MutablePeerState state = find(infoHash, peer, peer.getPort());
        if (state == null) return;
        Instant started = Instant.now();
        synchronized (state) {
            if (state.connection == ConnectionState.CONNECTED) return;
            state.connection = ConnectionState.DIRECT_CONNECTING;
            state.lastSeen = started;
            state.attempt = new SocketAttempt("TCP", SocketAddresses.pending(state.endpoint), started, null, SocketFailure.NONE, "SocketChannel.connect iniciado");
        }
        diagnostics.log(P2pDiagnostics.Layer.CONNECTIVITY, "TCP CONNECT START (DIRECT): " + details(state) + ".");
    }

    /** Socket TCP já conectado; o endereço local vem do SocketChannel real. */
    public void onTcpConnectSuccess(String infoHash, Peer peer, int remotePort) {
        onTcpConnectSuccess(infoHash, peer, remotePort, SocketAddresses.pending(endpoint(peer, remotePort)));
    }

    /** Socket TCP já conectado; o endereço local/remoto foram extraídos do SocketChannel real. */
    public void onTcpConnectSuccess(String infoHash, Peer peer, int remotePort, SocketAddresses addresses) {
        MutablePeerState state = find(infoHash, peer, remotePort);
        if (state == null) return;
        synchronized (state) {
            state.tcp = TransportSupport.SUPPORTED;
            state.connection = ConnectionState.DIRECT_CONNECTING;
            state.lastSeen = Instant.now();
            SocketAttempt current = state.attempt == null ? new SocketAttempt("TCP", addresses, Instant.now(), null, SocketFailure.NONE, "") : state.attempt;
            state.attempt = new SocketAttempt(current.protocol(), addresses, current.connectStartedAt(), null, SocketFailure.NONE, "conexão TCP concluída");
        }
        diagnostics.log(P2pDiagnostics.Layer.CONNECTIVITY, "TCP CONNECT SUCCESS (DIRECT): " + details(state) + "; próximo=fazer handshake BitTorrent.");
    }

    /** O handler BitTorrent só começa depois de TCP CONNECT SUCCESS. */
    public void onBittorrentHandshakeStart(String infoHash, Peer peer, int remotePort) {
        MutablePeerState state = find(infoHash, peer, remotePort);
        if (state == null) return;
        synchronized (state) {
            state.lastSeen = Instant.now();
            state.handshakeStarted = true;
            if (state.attempt != null) state.attempt = new SocketAttempt("BITTORRENT/TCP", state.attempt.addresses(), state.attempt.connectStartedAt(), null, SocketFailure.NONE, "handshake iniciado");
        }
        diagnostics.log("BITTORRENT HANDSHAKE START: " + details(state) + ".");
    }

    /** Único ponto que muda um peer direto para CONNECTED. */
    public void onBittorrentHandshakeSuccess(String infoHash, Peer peer, int remotePort) {
        MutablePeerState state = find(infoHash, peer, remotePort);
        if (state == null) return;
        synchronized (state) {
            state.tcp = TransportSupport.SUPPORTED;
            state.connection = ConnectionState.CONNECTED;
            state.failureReason = "";
            state.lastSeen = Instant.now();
            state.attempt = finish(state.attempt, SocketFailure.NONE, "handshake aceito");
        }
        cancelConnectionTimer(infoHash, state.endpoint);
        diagnostics.log("BITTORRENT HANDSHAKE SUCCESS: " + details(state) + ".");
        completeHappyEyeballs(infoHash, state.endpoint);
    }

    /** Falha antes do handler BitTorrent: registra a exceção original e sua classificação. */
    public void onTcpConnectFailure(String infoHash, Peer peer, Throwable error) {
        MutablePeerState state = find(infoHash, peer, peer.getPort());
        if (state == null) return;
        SocketFailure kind = classify(error);
        String reason = describe(error);
        failSocket(state, kind, reason);
        cancelConnectionTimer(infoHash, state.endpoint);
        diagnostics.log(P2pDiagnostics.Layer.CONNECTIVITY, "TCP CONNECT FAILED (DIRECT): " + details(state)
                + "; próximo=fallback automático uTP direto, depois BEP55 se necessário.");
        if (finishHappyEyeballs(infoHash, state.endpoint)) {
            if (startDirectUtpFallback(state)) return;
            if (requestHolePunchIfAvailable(state)) return;
        }
        scheduleBackoffRetry(state);
    }

    /** Resultado do factory: não reporta TCP failed quando o handler já registrou falha do handshake. */
    public void onOutgoingConnectionFactoryFailure(String infoHash, Peer peer, Throwable error, String fallbackReason) {
        MutablePeerState state = find(infoHash, peer, peer.getPort());
        if (state == null) return;
        synchronized (state) { if (state.handshakeStarted) return; }
        if (error != null) onTcpConnectFailure(infoHash, peer, error);
        else onTcpConnectFailure(infoHash, peer, new IOException(fallbackReason));
    }

    /** Falha após TCP conectado; não é apresentada como erro de socket. */
    public void onBittorrentHandshakeFailure(String infoHash, Peer peer, int remotePort, String reason) {
        MutablePeerState state = find(infoHash, peer, remotePort);
        if (state == null) return;
        failSocket(state, SocketFailure.HANDSHAKE_REJECTED, reason);
        cancelConnectionTimer(infoHash, state.endpoint);
        diagnostics.log(P2pDiagnostics.Layer.BITTORRENT, "HANDSHAKE TCP FAILED: " + details(state)
                + "; próximo=fallback automático uTP direto, depois BEP55 se necessário.");
        if (finishHappyEyeballs(infoHash, state.endpoint)) {
            if (startDirectUtpFallback(state)) return;
            if (requestHolePunchIfAvailable(state)) return;
        }
        scheduleBackoffRetry(state);
    }

    /** Abertura real do fluxo UDP uTP, disparada somente depois do CONNECT BEP 55. */
    public void onUtpConnectStart(String infoHash, PeerEndpoint endpoint, SocketAddresses addresses, Strategy strategy) {
        MutablePeerState state = peers.computeIfAbsent(key(infoHash, endpoint), ignored -> new MutablePeerState(infoHash, endpoint));
        Instant started = Instant.now();
        synchronized (state) {
            state.connection = strategy == Strategy.HOLE_PUNCHING ? ConnectionState.HOLE_PUNCHING : ConnectionState.DIRECT_CONNECTING;
            state.strategy = strategy == Strategy.HOLE_PUNCHING ? Strategy.HOLE_PUNCHING : Strategy.DIRECT_UTP;
            state.lastSeen = started;
            state.attempt = new SocketAttempt("uTP/UDP", addresses, started, null, SocketFailure.NONE, "datagrama SYN uTP enviado");
        }
        diagnostics.log(P2pDiagnostics.Layer.UTP, "DIRECT " + (strategy == Strategy.HOLE_PUNCHING ? "HOLE PUNCH" : "uTP")
                + " START: " + details(state) + "; pacote outbound SYN enviado.");
    }

    /** A sessao uTP foi estabelecida; o handshake BitTorrent ainda e uma etapa separada. */
    public void onUtpConnectSuccess(String infoHash, PeerEndpoint endpoint, SocketAddresses addresses) {
        MutablePeerState state = peers.get(key(infoHash, endpoint));
        if (state == null) return;
        synchronized (state) {
            state.utp = TransportSupport.SUPPORTED;
            state.connection = state.strategy == Strategy.HOLE_PUNCHING ? ConnectionState.HOLE_PUNCHING : ConnectionState.DIRECT_CONNECTING;
            state.lastSeen = Instant.now();
            SocketAttempt current = state.attempt == null
                    ? new SocketAttempt("uTP/UDP", addresses, Instant.now(), null, SocketFailure.NONE, "") : state.attempt;
            state.attempt = new SocketAttempt("uTP/UDP", addresses, current.connectStartedAt(), null, SocketFailure.NONE, "conexao uTP concluida");
        }
        diagnostics.log(P2pDiagnostics.Layer.UTP, "CONNECT SUCCESS: " + details(state) + "; resposta UDP recebida; próximo=handshake BitTorrent.");
    }

    public void onUtpBittorrentHandshakeStart(String infoHash, Peer peer, int remotePort) {
        MutablePeerState state = find(infoHash, peer, remotePort, Transport.UTP);
        if (state == null) return;
        synchronized (state) {
            state.connection = state.strategy == Strategy.HOLE_PUNCHING ? ConnectionState.HOLE_PUNCHING : ConnectionState.DIRECT_CONNECTING;
            state.lastSeen = Instant.now();
            SocketAttempt current = state.attempt == null
                    ? new SocketAttempt("BITTORRENT/uTP", SocketAddresses.pending(state.endpoint), Instant.now(), null, SocketFailure.NONE, "") : state.attempt;
            state.attempt = new SocketAttempt("BITTORRENT/uTP", current.addresses(), current.connectStartedAt(), null, SocketFailure.NONE, "handshake iniciado");
        }
        diagnostics.log(P2pDiagnostics.Layer.BITTORRENT, "HANDSHAKE uTP START: " + details(state) + ".");
    }

    public void onUtpBittorrentHandshakeSuccess(String infoHash, Peer peer, int remotePort) {
        MutablePeerState state = find(infoHash, peer, remotePort, Transport.UTP);
        if (state == null) return;
        synchronized (state) {
            state.utp = TransportSupport.SUPPORTED;
            state.connection = ConnectionState.CONNECTED;
            state.failureReason = "";
            state.lastSeen = Instant.now();
            state.attempt = finish(state.attempt, SocketFailure.NONE, "handshake aceito sobre uTP");
        }
        cancelConnectionTimer(infoHash, state.endpoint);
        diagnostics.log("BITTORRENT HANDSHAKE SUCCESS: " + details(state) + ".");
        completeHappyEyeballs(infoHash, state.endpoint);
    }

    public void onUtpBittorrentHandshakeFailure(String infoHash, Peer peer, int remotePort, String reason) {
        MutablePeerState state = find(infoHash, peer, remotePort, Transport.UTP);
        if (state == null) return;
        failSocket(state, SocketFailure.HANDSHAKE_REJECTED, reason);
        cancelConnectionTimer(infoHash, state.endpoint);
        diagnostics.log(P2pDiagnostics.Layer.BITTORRENT, "HANDSHAKE uTP FAILED: " + details(state)
                + "; próximo=" + (state.strategy == Strategy.DIRECT_UTP ? "BEP55" : "nenhum fallback restante") + ".");
        if (state.strategy == Strategy.DIRECT_UTP && requestHolePunchIfAvailable(state)) return;
        if (state.strategy == Strategy.HOLE_PUNCHING) {
            onHolePunchUnavailable(infoHash, state.endpoint, "handshake BitTorrent/uTP falhou apos BEP55 local: " + reason);
            return;
        }
        scheduleBackoffRetry(state);
    }

    public void onUtpFailure(String infoHash, PeerEndpoint endpoint, Throwable error) {
        MutablePeerState state = peers.get(key(infoHash, endpoint));
        if (state == null) return;
        failSocket(state, classify(error), describe(error));
        diagnostics.log(P2pDiagnostics.Layer.UTP, "DIRECT uTP FAILED: " + details(state)
                + "; próximo=" + (state.strategy == Strategy.DIRECT_UTP ? "BEP55" : "resultado terminal") + ".");
        if (state.strategy == Strategy.DIRECT_UTP && requestHolePunchIfAvailable(state)) return;
        if (state.strategy == Strategy.HOLE_PUNCHING) {
            onHolePunchUnavailable(infoHash, endpoint, "uTP falhou apos BEP55 local: " + describe(error));
            return;
        }
        finishHappyEyeballs(infoHash, endpoint);
        scheduleBackoffRetry(state);
    }

    /**
     * O BEP55 local foi esgotado. O overlay e consultado somente agora, de modo
     * assincrono e com um contexto imutavel; se ele nao for elegivel, o manager
     * registra a causa e volta ao backoff normal.
     */
    public void onHolePunchUnavailable(String infoHash, PeerEndpoint endpoint, String reason) {
        MutablePeerState state = peers.computeIfAbsent(key(infoHash, endpoint), ignored -> new MutablePeerState(infoHash, endpoint));
        PeerConnectivityContext context;
        synchronized (state) {
            if (state.connection == ConnectionState.CONNECTED) return;
            state.connection = ConnectionState.HOLE_PUNCH_PENDING;
            state.strategy = Strategy.HOLE_PUNCHING;
            state.failureReason = reason;
            state.lastSeen = Instant.now();
            state.attempt = finish(state.attempt, SocketFailure.NONE, reason);
            context = overlayContext(state);
        }
        diagnostics.log("HOLE PUNCH LOCAL FAILED: " + details(state)
                + "; proximo=lf_rendezvous pelo swarm Ola Luffy, se elegivel.");
        startOverlayRendezvousFallback(state, context);
    }

    /** Recebe o resultado terminal da sessao de overlay sem bloquear a runtime BitTorrent. */
    public void onOverlayRendezvousFinished(PeerConnectivityContext context, UUID sessionId, String terminalState, String reason) {
        if (context == null || sessionId == null) return;
        MutablePeerState state = peers.get(key(context.infoHash(), context.targetEndpoint()));
        if (state == null) return;
        boolean connected = "CONNECTED".equals(terminalState);
        synchronized (state) {
            if (state.connection == ConnectionState.CONNECTED) return;
            state.lastFinishedOverlaySession = sessionId;
            if (connected) {
                state.connection = ConnectionState.CONNECTED;
                state.strategy = Strategy.HOLE_PUNCHING;
                state.failureReason = "";
                state.lastSeen = Instant.now();
                state.attempt = finish(state.attempt, SocketFailure.NONE, "handshake aceito apos lf_rendezvous");
            } else {
                state.connection = ConnectionState.UNREACHABLE;
                state.strategy = Strategy.HOLE_PUNCHING;
                state.failureReason = reason == null || reason.isBlank() ? "sessao lf_rendezvous terminou em " + terminalState : reason;
                state.lastSeen = Instant.now();
                state.attempt = finish(state.attempt, SocketFailure.UNKNOWN, state.failureReason);
            }
        }
        if (connected) {
            cancelConnectionTimer(context.infoHash(), state.endpoint);
            completeHappyEyeballs(context.infoHash(), state.endpoint);
            diagnostics.log(P2pDiagnostics.Layer.RESULT, "[LF_RENDEZVOUS] CONNECTED: infoHash=" + context.infoHash()
                    + "; target=" + state.endpoint.display() + "; sessionId=" + sessionId + "; bt-core aceitou o peer.");
        } else {
            diagnostics.log(P2pDiagnostics.Layer.RESULT, "[LF_RENDEZVOUS] FAILED: infoHash=" + context.infoHash()
                    + "; target=" + state.endpoint.display() + "; sessionId=" + sessionId + "; estado=" + terminalState
                    + "; motivo=" + state.failureReason + "; proximo=backoff.");
            scheduleBackoffRetry(state);
        }
    }

    /** Fallback para uma versão do motor sem instrumentação; não inventa CONNECT START. */
    public void onBitTorrentConnectedEvent(String infoHash, Peer peer, int remotePort) {
        MutablePeerState state = find(infoHash, peer, remotePort);
        if (state == null || state.connection == ConnectionState.CONNECTED) return;
        synchronized (state) {
            state.tcp = TransportSupport.SUPPORTED;
            state.connection = ConnectionState.CONNECTED;
            state.failureReason = "";
            state.attempt = finish(state.attempt, SocketFailure.NONE, "handshake aceito (evento do motor)");
        }
        cancelConnectionTimer(infoHash, state.endpoint);
        diagnostics.log("BITTORRENT HANDSHAKE SUCCESS: " + details(state) + "; fonte=evento final do motor.");
        completeHappyEyeballs(infoHash, state.endpoint);
    }

    public void onBitTorrentDisconnected(String infoHash, Peer peer, int remotePort) {
        MutablePeerState state = find(infoHash, peer, remotePort);
        if (state == null) return;
        synchronized (state) {
            if (state.connection == ConnectionState.CONNECTED) {
                state.connection = ConnectionState.DIRECT_CONNECT_FAILED;
                state.failureReason = "conexão BitTorrent encerrada após o handshake";
            }
            state.lastSeen = Instant.now();
        }
    }

    /** Pontos de extensão das próximas etapas, centralizados fora da UI, DHT e downloader. */
    public void markPortMappingPending(String infoHash, PeerEndpoint endpoint) { mark(infoHash, endpoint, ConnectionState.PORT_MAPPING_PENDING, Strategy.NAT_MAPPING); }
    public void markHolePunchPending(String infoHash, PeerEndpoint endpoint) { mark(infoHash, endpoint, ConnectionState.HOLE_PUNCH_PENDING, Strategy.HOLE_PUNCHING); }
    public void markHolePunching(String infoHash, PeerEndpoint endpoint) { mark(infoHash, endpoint, ConnectionState.HOLE_PUNCHING, Strategy.HOLE_PUNCHING); }

    /** A DHT não repete indefinidamente; uma ação explícita do usuário libera nova rodada. */
    public void allowExplicitRetry(String infoHash) {
        peers.forEach((key, state) -> {
            if (!state.infoHash.equalsIgnoreCase(infoHash)) return;
            synchronized (state) {
                if (state.connection != ConnectionState.CONNECTED) {
                    state.directAttempts = 0; state.connection = ConnectionState.DISCOVERED;
                    state.failureReason = ""; state.lastPromotion = null; state.handshakeStarted = false; state.nextRetryAt = null;
                }
            }
        });
        ConnectionRace race = connectionRaces.remove(infoHash.toLowerCase(Locale.ROOT));
        if (race != null) race.cancelAll().forEach(future -> future.cancel(false));
        retryTimers.entrySet().removeIf(entry -> {
            if (!entry.getKey().startsWith(infoHash.toLowerCase(Locale.ROOT) + "|")) return false;
            entry.getValue().cancel(false); return true;
        });
        diagnostics.log("CONNECTIVITY: nova tentativa explícita liberada para infoHash=" + infoHash + ".");
    }

    /**
     * Descarta peers e tentativas pendentes de um torrent que deixou de ser
     * assistido. Conexões vivas são fechadas pelo dono da sessão BitTorrent.
     */
    public void forgetTorrent(String infoHash) {
        validateInfoHash(infoHash);
        String prefix = infoHash.toLowerCase(Locale.ROOT) + "|";
        ConnectionRace race = connectionRaces.remove(infoHash.toLowerCase(Locale.ROOT));
        if (race != null) race.cancelAll().forEach(future -> future.cancel(false));
        cancelTimersFor(prefix, connectTimers);
        cancelTimersFor(prefix, retryTimers);
        peers.entrySet().removeIf(entry -> entry.getKey().startsWith(prefix));
    }

    private static void cancelTimersFor(String keyPrefix, Map<String, ScheduledFuture<?>> timers) {
        timers.entrySet().removeIf(entry -> {
            if (!entry.getKey().startsWith(keyPrefix)) return false;
            entry.getValue().cancel(false);
            return true;
        });
    }

    public List<PeerState> peersFor(String infoHash) {
        List<PeerState> result = new ArrayList<>();
        peers.values().forEach(state -> { if (state.infoHash.equalsIgnoreCase(infoHash)) result.add(state.snapshot()); });
        result.sort(Comparator.comparing((PeerState state) -> state.endpoint().addressFamily()).thenComparing(state -> state.endpoint().transport()).thenComparing(state -> state.endpoint().display()));
        return List.copyOf(result);
    }

    /** Snapshot somente de leitura para o painel de diagnóstico por peer. */
    public List<PeerState> allPeers() {
        List<PeerState> result = peers.values().stream().map(MutablePeerState::snapshot).toList();
        return result.stream().sorted(Comparator.comparing(PeerState::infoHash)
                .thenComparing(state -> state.endpoint().display()).thenComparing(state -> state.endpoint().transport())).toList();
    }

    /** Retorna todos os caminhos preservados, inclusive IPv6/uTP ainda sem motor ativo. */
    public List<PeerEndpoint> endpointsFor(String infoHash) {
        return peersFor(infoHash).stream().map(PeerState::endpoint).toList();
    }

    /** Usado pelo observador de tracker para nao reclassificar uma descoberta DHT/PEX ja registrada. */
    public boolean isKnownTcpEndpoint(String infoHash, Peer peer) {
        if (infoHash == null || peer == null || peer.isPortUnknown()) return false;
        return peers.containsKey(key(infoHash, new PeerEndpoint(peer.getInetAddress(), peer.getPort(), Transport.TCP)));
    }

    private void armConnectionTimer(Promotion promotion) {
        String key = key(promotion.infoHash(), promotion.endpoint());
        ScheduledFuture<?> previous = connectTimers.remove(key);
        if (previous != null) previous.cancel(false);
        connectTimers.put(key, scheduler.schedule(() -> {
            MutablePeerState state = peers.get(key);
            if (state == null) return;
            synchronized (state) {
                if ((state.connection != ConnectionState.DIRECT_CONNECT_PENDING && state.connection != ConnectionState.DIRECT_CONNECTING)
                        || state.lastPromotion == null || Duration.between(state.lastPromotion, Instant.now()).compareTo(CONNECT_WINDOW) < 0) return;
            }
            failSocket(state, SocketFailure.TIMEOUT, "tempo limite de " + CONNECT_WINDOW.toSeconds() + " s");
            diagnostics.log("TCP CONNECT FAILED: " + details(state) + ".");
            if (finishHappyEyeballs(promotion.infoHash(), state.endpoint)) requestHolePunchIfAvailable(state);
            scheduleBackoffRetry(state);
        }, CONNECT_WINDOW.toMillis(), TimeUnit.MILLISECONDS));
    }

    private void queueHappyEyeballs(String infoHash, PeerEndpoint endpoint) {
        String raceKey = infoHash.toLowerCase(Locale.ROOT);
        ConnectionRace race = connectionRaces.computeIfAbsent(raceKey, ignored -> new ConnectionRace());
        if (race.enqueue(endpoint) && race.requestWave()) {
            diagnostics.log("HAPPY EYEBALLS QUEUED: infoHash=" + infoHash + "; peer=" + endpoint.display()
                    + "; caminho=" + endpoint.family() + "/" + endpoint.transport() + "; janela=" + HAPPY_EYEBALLS_COALESCE.toMillis() + "ms.");
            scheduler.schedule(() -> beginHappyEyeballsWave(raceKey, race), HAPPY_EYEBALLS_COALESCE.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private void beginHappyEyeballsWave(String raceKey, ConnectionRace race) {
        List<PeerEndpoint> endpoints = race.reserveWave();
        for (int index = 0; index < endpoints.size(); index++) {
            PeerEndpoint endpoint = endpoints.get(index);
            long delay = HAPPY_EYEBALLS_STAGGER.toMillis() * index;
            ScheduledFuture<?> future = scheduler.schedule(() -> dispatchHappyEyeballs(raceKey, race, endpoint), delay, TimeUnit.MILLISECONDS);
            race.register(endpoint, future);
            diagnostics.log("HAPPY EYEBALLS SCHEDULED: infoHash=" + raceKey + "; peer=" + endpoint.display()
                    + "; caminho=" + endpoint.family() + "/" + endpoint.transport() + "; atraso=" + delay + "ms.");
        }
    }

    private void dispatchHappyEyeballs(String raceKey, ConnectionRace race, PeerEndpoint endpoint) {
        if (!race.dispatch(endpoint)) return;
        MutablePeerState state = peers.get(key(raceKey, endpoint));
        if (state == null) { finishHappyEyeballs(raceKey, endpoint); return; }
        Promotion promotion;
        synchronized (state) {
            if (state.connection == ConnectionState.CONNECTED || state.directAttempts >= MAX_DIRECT_PROMOTIONS) {
                finishHappyEyeballs(raceKey, endpoint); return;
            }
            promotion = new Promotion(state.infoHash, endpoint, state.strategy);
        }
        if (!admit(promotion)) {
            synchronized (state) {
                state.connection = ConnectionState.DISCOVERED;
                state.failureReason = "promoção adiada pelo limite de conexões da sessão";
                state.attempt = null;
            }
            finishHappyEyeballs(raceKey, endpoint);
            return;
        }
        synchronized (state) {
            if (state.connection == ConnectionState.CONNECTED || state.directAttempts >= MAX_DIRECT_PROMOTIONS) {
                finishHappyEyeballs(raceKey, endpoint); return;
            }
            state.directAttempts++;
            state.lastPromotion = Instant.now();
            state.connection = ConnectionState.DIRECT_CONNECT_PENDING;
            state.attempt = new SocketAttempt(endpoint.transport().name(), SocketAddresses.pending(endpoint), null, null, SocketFailure.NONE,
                    "tentativa escalonada Happy Eyeballs iniciada");
        }
        diagnostics.log("HAPPY EYEBALLS START: infoHash=" + promotion.infoHash() + "; peer=" + endpoint.display()
                + "; caminho=" + endpoint.family() + "/" + endpoint.transport() + "; tentativa=" + state.directAttempts + ".");
        try {
            promoter.promote(promotion);
            armConnectionTimer(promotion);
        } catch (RuntimeException error) {
            failBeforeSocket(promotion.infoHash(), endpoint, "o motor BitTorrent recusou o peer: " + describe(error));
            finishHappyEyeballs(promotion.infoHash(), endpoint);
            scheduleBackoffRetry(state);
        }
    }

    private void scheduleBackoffRetry(MutablePeerState state) {
        Duration delay;
        String timerKey = key(state.infoHash, state.endpoint);
        boolean terminalFailure = false;
        synchronized (state) {
            if (state.connection == ConnectionState.CONNECTED) return;
            if (state.directAttempts >= MAX_DIRECT_PROMOTIONS) {
                state.connection = ConnectionState.UNREACHABLE;
                state.nextRetryAt = null;
                state.failureReason = "limite de " + MAX_DIRECT_PROMOTIONS + " conexões diretas atingido";
                terminalFailure = true;
                delay = Duration.ZERO;
            } else {
                delay = retryBackoff(state.directAttempts);
                state.nextRetryAt = Instant.now().plus(delay);
            }
        }
        ScheduledFuture<?> previous = retryTimers.remove(timerKey);
        if (previous != null) previous.cancel(false);
        if (terminalFailure) {
            diagnostics.log("PEER UNREACHABLE: infoHash=" + state.infoHash + "; key=" + timerKey
                    + "; motivo=" + state.failureReason + ".");
            return;
        }
        ScheduledFuture<?> retry = scheduler.schedule(() -> {
            MutablePeerState current = peers.get(timerKey);
            if (current == null) return;
            synchronized (current) {
                if (current.connection == ConnectionState.CONNECTED || current.nextRetryAt == null || Instant.now().isBefore(current.nextRetryAt)) return;
                current.nextRetryAt = null;
            }
            diagnostics.log("PEER RETRY BACKOFF EXPIRED: infoHash=" + current.infoHash + "; key=" + timerKey + "; nova tentativa controlada.");
            queueHappyEyeballs(current.infoHash, current.endpoint);
        }, delay.toMillis(), TimeUnit.MILLISECONDS);
        retryTimers.put(timerKey, retry);
        diagnostics.log("PEER RETRY BACKOFF: infoHash=" + state.infoHash + "; key=" + timerKey + "; falha=" + state.directAttempts
                + "; aguardando=" + delay.toSeconds() + "s; próximo retry=" + state.nextRetryAt + ".");
    }

    private void startOverlayRendezvousFallback(MutablePeerState state, PeerConnectivityContext context) {
        CompletionStage<OverlayRendezvousResult> stage;
        try {
            stage = overlayRendezvousFallback.onDirectConnectivityExhausted(context);
        } catch (RuntimeException error) {
            completeOverlayFallbackWithoutSession(state, "coordenador lf_rendezvous falhou: " + describe(error));
            return;
        }
        if (stage == null) {
            completeOverlayFallbackWithoutSession(state, "coordenador lf_rendezvous nao retornou operacao assincrona");
            return;
        }
        stage.whenComplete((result, error) -> {
            if (error != null) {
                completeOverlayFallbackWithoutSession(state, "coordenador lf_rendezvous falhou: " + describe(error));
                return;
            }
            if (result == null || !result.started()) {
                completeOverlayFallbackWithoutSession(state, result == null ? "coordenador lf_rendezvous nao retornou resultado" : result.reason());
                return;
            }
            synchronized (state) {
                if (state.connection == ConnectionState.CONNECTED || closing
                        || result.sessionId().orElseThrow().equals(state.lastFinishedOverlaySession)) return;
                state.connection = ConnectionState.HOLE_PUNCH_PENDING;
                state.strategy = Strategy.HOLE_PUNCHING;
                state.failureReason = "sessao lf_rendezvous " + result.sessionId().orElseThrow() + " aguardando BEP55/uTP";
                state.lastSeen = Instant.now();
            }
            diagnostics.log(P2pDiagnostics.Layer.HOLEPUNCH, "[LF_RENDEZVOUS] STARTED: infoHash=" + context.infoHash()
                    + "; target=" + context.targetEndpoint().display() + "; sessionId=" + result.sessionId().orElseThrow()
                    + "; proximo=BEP55 distribuido e uTP direto.");
        });
    }

    private void completeOverlayFallbackWithoutSession(MutablePeerState state, String reason) {
        synchronized (state) {
            if (state.connection == ConnectionState.CONNECTED || closing) return;
            state.connection = ConnectionState.UNREACHABLE;
            state.strategy = Strategy.HOLE_PUNCHING;
            // Sem um coordenador configurado, preserva exatamente o motivo do
            // BEP55 local para manter o diagnóstico e o comportamento legado.
            state.failureReason = "overlay lf_rendezvous nao configurado".equals(reason)
                    ? state.failureReason : reason;
            state.lastSeen = Instant.now();
            state.attempt = finish(state.attempt, SocketFailure.NONE, reason);
        }
        diagnostics.log(P2pDiagnostics.Layer.RESULT, "[LF_RENDEZVOUS] SKIPPED: " + details(state) + "; proximo=backoff.");
        scheduleBackoffRetry(state);
    }

    private PeerConnectivityContext overlayContext(MutablePeerState state) {
        boolean active;
        try {
            active = torrentActivity.isActive(state.infoHash);
        } catch (RuntimeException error) {
            active = false;
            diagnostics.log(P2pDiagnostics.Layer.CONNECTIVITY, "[LF_RENDEZVOUS] torrent nao pode ser verificado: " + describe(error) + ".");
        }
        Instant now = Instant.now();
        return new PeerConnectivityContext(state.infoHash, state.endpoint, state.luffyNodeId, state.luffyCapabilities,
                active, state.removed, state.connection == ConnectionState.CONNECTED,
                state.nextRetryAt != null && now.isBefore(state.nextRetryAt), closing, now);
    }

    private static Duration retryBackoff(int failedAttempt) {
        return RETRY_BACKOFFS.get(Math.min(Math.max(0, failedAttempt - 1), RETRY_BACKOFFS.size() - 1));
    }

    /** @return true somente quando todos os caminhos desta rodada terminaram sem vencedor. */
    private boolean finishHappyEyeballs(String infoHash, PeerEndpoint endpoint) {
        ConnectionRace race = connectionRaces.get(infoHash.toLowerCase(Locale.ROOT));
        if (race == null) return true;
        boolean scheduleNext = race.finish(endpoint);
        if (scheduleNext && race.requestWave()) {
            scheduler.schedule(() -> beginHappyEyeballsWave(infoHash.toLowerCase(Locale.ROOT), race), HAPPY_EYEBALLS_COALESCE.toMillis(), TimeUnit.MILLISECONDS);
        }
        return race.isExhausted();
    }

    private void completeHappyEyeballs(String infoHash, PeerEndpoint winner) {
        ConnectionRace race = connectionRaces.get(infoHash.toLowerCase(Locale.ROOT));
        if (race == null) return;
        List<ScheduledFuture<?>> cancelled = race.complete(winner);
        cancelled.forEach(future -> future.cancel(false));
        ScheduledFuture<?> retry = retryTimers.remove(key(infoHash, winner));
        if (retry != null) retry.cancel(false);
        diagnostics.log("HAPPY EYEBALLS WINNER: infoHash=" + infoHash + "; peer=" + winner.display()
                + "; caminhos agendados restantes cancelados=" + cancelled.size() + ".");
    }

    private void failBeforeSocket(String infoHash, PeerEndpoint endpoint, String reason) {
        MutablePeerState state = peers.get(key(infoHash, endpoint));
        if (state == null) return;
        synchronized (state) {
            state.connection = ConnectionState.DIRECT_CONNECT_FAILED;
            state.failureReason = reason;
            state.attempt = finish(state.attempt, SocketFailure.UNKNOWN, reason);
        }
        diagnostics.log("DIRECT CONNECT FAILED: " + details(state) + ".");
    }

    private void failSocket(MutablePeerState state, SocketFailure failure, String reason) {
        synchronized (state) {
            if (state.connection == ConnectionState.CONNECTED) return;
            state.connection = ConnectionState.DIRECT_CONNECT_FAILED;
            state.failureReason = reason;
            state.lastSeen = Instant.now();
            state.attempt = finish(state.attempt, failure, reason);
        }
    }

    /**
     * Após TCP, tenta uTP somente quando esse endpoint foi descoberto de forma
     * independente. A porta TCP anunciada nunca é convertida automaticamente em
     * porta UDP/uTP.
     */
    private boolean startDirectUtpFallback(MutablePeerState source) {
        PeerEndpoint utpEndpoint = explicitUtpEndpoint(source);
        if (utpEndpoint == null) {
            diagnostics.log(P2pDiagnostics.Layer.CONNECTIVITY, "uTP DIRECT SKIPPED: infoHash=" + source.infoHash + "; target="
                    + source.endpoint.display() + "; motivo=nenhum endpoint UDP/uTP foi anunciado independentemente da porta TCP.");
            return false;
        }
        if (!isPathAvailable(utpEndpoint)) {
            diagnostics.log(P2pDiagnostics.Layer.CONNECTIVITY, "uTP DIRECT SKIPPED: infoHash=" + source.infoHash + "; target="
                    + utpEndpoint.display() + "; motivo=listener uTP local indisponível; próximo=BEP55 indisponível sem uTP.");
            return false;
        }
        MutablePeerState state = peers.computeIfAbsent(key(source.infoHash, utpEndpoint), ignored -> new MutablePeerState(source.infoHash, utpEndpoint));
        Promotion promotion;
        synchronized (state) {
            if (state.connection == ConnectionState.CONNECTED || state.connection == ConnectionState.DIRECT_CONNECT_PENDING
                    || state.connection == ConnectionState.DIRECT_CONNECTING || state.connection == ConnectionState.HOLE_PUNCHING) return true;
            if (state.directAttempts >= MAX_DIRECT_PROMOTIONS) return false;
            promotion = new Promotion(source.infoHash, utpEndpoint, Strategy.DIRECT_UTP);
        }
        if (!admit(promotion)) {
            synchronized (state) {
                state.connection = ConnectionState.DISCOVERED;
                state.failureReason = "fallback uTP adiado pelo limite de conexões da sessão";
                state.attempt = null;
            }
            return false;
        }
        synchronized (state) {
            if (state.connection == ConnectionState.CONNECTED || state.connection == ConnectionState.DIRECT_CONNECT_PENDING
                    || state.connection == ConnectionState.DIRECT_CONNECTING || state.connection == ConnectionState.HOLE_PUNCHING
                    || state.directAttempts >= MAX_DIRECT_PROMOTIONS) return true;
            state.directAttempts++;
            state.strategy = Strategy.DIRECT_UTP;
            state.connection = ConnectionState.DIRECT_CONNECT_PENDING;
            state.lastPromotion = Instant.now();
            state.attempt = new SocketAttempt("uTP/UDP", SocketAddresses.pending(utpEndpoint), null, null, SocketFailure.NONE,
                    "fallback automático após falha TCP");
        }
        diagnostics.log(P2pDiagnostics.Layer.CONNECTIVITY, "FALLBACK SELECTED: infoHash=" + source.infoHash + "; estratégia=uTP DIRECT; target="
                + utpEndpoint.display() + "; tentativa=" + state.directAttempts + "; próximo=BEP55 somente se uTP falhar.");
        try {
            promoter.promote(promotion);
            return true;
        } catch (RuntimeException error) {
            failBeforeSocket(source.infoHash, utpEndpoint, "o motor uTP recusou o peer: " + describe(error));
            return false;
        }
    }

    /** BEP55 só é solicitado depois de TCP e uTP direto falharem, nunca como primeira estratégia. */
    private boolean requestHolePunchIfAvailable(MutablePeerState source) {
        PeerEndpoint utpEndpoint = explicitUtpEndpoint(source);
        if (utpEndpoint == null) {
            diagnostics.log(P2pDiagnostics.Layer.HOLEPUNCH, "BEP55 SKIPPED: infoHash=" + source.infoHash + "; target=" + source.endpoint.display()
                    + "; motivo=nenhum endpoint UDP/uTP foi anunciado independentemente da porta TCP.");
            return false;
        }
        if (!isPathAvailable(utpEndpoint)) {
            diagnostics.log(P2pDiagnostics.Layer.HOLEPUNCH, "BEP55 SKIPPED: infoHash=" + source.infoHash + "; target=" + utpEndpoint.display()
                    + "; motivo=uTP local indisponível; próximo=RESULT peer unreachable.");
            return false;
        }
        if (!admit(new Promotion(source.infoHash, utpEndpoint, Strategy.HOLE_PUNCHING))) return false;
        markHolePunchPending(source.infoHash, utpEndpoint);
        diagnostics.log(P2pDiagnostics.Layer.HOLEPUNCH, "RENDEZVOUS LOOKUP: infoHash=" + source.infoHash + "; target=" + utpEndpoint.display()
                + "; motivo=TCP e uTP direto falharam; próximo=CONNECT se existir peer C.");
        holePunchRequester.request(source.infoHash, utpEndpoint);
        return true;
    }

    private PeerEndpoint explicitUtpEndpoint(MutablePeerState source) {
        if (source.endpoint.transport() == Transport.UTP) return source.endpoint;
        return peers.values().stream()
                .filter(candidate -> candidate.infoHash.equalsIgnoreCase(source.infoHash))
                .filter(candidate -> candidate.endpoint.transport() == Transport.UTP)
                .filter(candidate -> candidate.endpoint.addressFamily() == source.endpoint.addressFamily())
                .filter(candidate -> candidate.endpoint.address().equals(source.endpoint.address()))
                .map(candidate -> candidate.endpoint)
                .findFirst().orElse(null);
    }

    private boolean admit(Promotion promotion) {
        try {
            if (connectionAdmission.admit(promotion)) return true;
            diagnostics.log(P2pDiagnostics.Layer.CONNECTIVITY, "CONNECTION PROMOTION DEFERRED: infoHash=" + promotion.infoHash()
                    + "; peer=" + promotion.endpoint().display() + "; estratégia=" + promotion.strategy()
                    + "; motivo=limite de conexões úteis da sessão.");
            return false;
        } catch (RuntimeException error) {
            diagnostics.log(P2pDiagnostics.Layer.CONNECTIVITY, "CONNECTION PROMOTION DEFERRED: infoHash=" + promotion.infoHash()
                    + "; peer=" + promotion.endpoint().display() + "; motivo=política de sessão indisponível: " + describe(error) + ".");
            return false;
        }
    }

    private SocketAttempt finish(SocketAttempt attempt, SocketFailure failure, String detail) {
        SocketAttempt base = attempt == null
                ? new SocketAttempt("TCP", new SocketAddresses("desconhecido", "desconhecido"), Instant.now(), null, SocketFailure.NONE, "")
                : attempt;
        return new SocketAttempt(base.protocol(), base.addresses(), base.connectStartedAt(), Instant.now(), failure, detail);
    }

    private SocketFailure classify(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            String text = String.valueOf(current.getMessage()).toLowerCase(Locale.ROOT);
            if (current instanceof SocketTimeoutException) return SocketFailure.TIMEOUT;
            if (current instanceof NoRouteToHostException || text.contains("no route")) return SocketFailure.NO_ROUTE;
            if ((current instanceof ConnectException || current instanceof IOException) && (text.contains("refused") || text.contains("recus"))) return SocketFailure.CONNECTION_REFUSED;
            if (text.contains("reset") || text.contains("forcibly closed")) return SocketFailure.CONNECTION_RESET;
            if (current instanceof SocketException) return SocketFailure.SOCKET_EXCEPTION;
            if (current instanceof IOException) return SocketFailure.IO_EXCEPTION;
        }
        return SocketFailure.UNKNOWN;
    }

    private String details(MutablePeerState state) {
        SocketAttempt attempt;
        synchronized (state) { attempt = state.attempt; }
        String local = attempt == null ? "desconhecido" : attempt.addresses().local();
        String remote = attempt == null ? state.endpoint.display() : attempt.addresses().remote();
        String started = attempt == null || attempt.connectStartedAt() == null ? "não iniciado" : attempt.connectStartedAt().toString();
        long duration = attempt == null ? -1 : attempt.durationMillis();
        String failure = attempt == null ? SocketFailure.NONE.name() : attempt.failure().name();
        String detail = attempt == null ? "" : attempt.detail();
        return "infoHash=" + state.infoHash + "; peer=" + state.endpoint.display() + "; protocolo=" + (attempt == null ? "TCP" : attempt.protocol())
                + "; local=" + local + "; remoto=" + remote + "; início=" + started + "; duraçãoMs=" + duration
                + "; falha=" + failure + (detail == null || detail.isBlank() ? "" : "; detalhe=" + detail);
    }

    private void mark(String infoHash, PeerEndpoint endpoint, ConnectionState connection, Strategy strategy) {
        MutablePeerState state = peers.computeIfAbsent(key(infoHash, endpoint), ignored -> new MutablePeerState(infoHash, endpoint));
        synchronized (state) { state.connection = connection; state.strategy = strategy; state.lastSeen = Instant.now(); }
    }
    private void cancelConnectionTimer(String infoHash, PeerEndpoint endpoint) {
        ScheduledFuture<?> timer = connectTimers.remove(key(infoHash, endpoint)); if (timer != null) timer.cancel(false);
    }
    private MutablePeerState find(String infoHash, Peer peer, int remotePort) {
        return find(infoHash, peer, remotePort, Transport.TCP);
    }
    private MutablePeerState find(String infoHash, Peer peer, int remotePort, Transport transport) {
        if (peer == null) return null;
        int declaredPort = peer.isPortUnknown() ? remotePort : peer.getPort();
        MutablePeerState exact = peers.get(key(infoHash, endpoint(peer, declaredPort, transport)));
        if (exact != null) return exact;
        exact = peers.get(key(infoHash, endpoint(peer, remotePort, transport)));
        if (exact != null) return exact;
        return peers.values().stream().filter(state -> state.infoHash.equalsIgnoreCase(infoHash)
                && state.endpoint.transport() == transport && state.endpoint.address().equals(peer.getInetAddress())).findFirst().orElse(null);
    }
    private Strategy chooseStrategy(PeerEndpoint endpoint) {
        if (!isRoutable(endpoint.address())) return Strategy.NONE;
        if (endpoint.transport() == Transport.UTP) return Strategy.DIRECT_UTP;
        if (endpoint.family() == AddressFamily.IPV6) return localConnectivity.hasGlobalIpv6() ? Strategy.DIRECT_IPV6 : Strategy.NONE;
        return Strategy.DIRECT_IPV4;
    }
    private boolean isPathAvailable(PeerEndpoint endpoint) {
        return availablePaths.getOrDefault(new EndpointPath(endpoint.addressFamily(), endpoint.transport()), false);
    }
    private String unreachableReason(PeerEndpoint endpoint) {
        if (!isRoutable(endpoint.address())) return "a DHT retornou endereço privado, local ou reservado";
        if (endpoint.family() == AddressFamily.IPV6) return "peer IPv6 sem IPv6 global compatível nesta máquina";
        return "sem rota direta disponível";
    }
    private boolean isRoutable(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isMulticastAddress()) return false;
        if (address instanceof Inet4Address ipv4) {
            byte[] b = ipv4.getAddress(); int first = b[0] & 0xff; int second = b[1] & 0xff;
            return first != 0 && first != 10 && first != 127 && !(first == 169 && second == 254)
                    && !(first == 172 && second >= 16 && second <= 31) && !(first == 192 && second == 168)
                    && !(first == 100 && second >= 64 && second <= 127);
        }
        return IpAddressClassifier.isGlobalUnicastIpv6(address);
    }
    private boolean isPrivateLanIpv4(InetAddress address) {
        if (!(address instanceof Inet4Address ipv4)) return false;
        byte[] bytes = ipv4.getAddress(); int first = bytes[0] & 0xff, second = bytes[1] & 0xff;
        return first == 10 || first == 192 && second == 168 || first == 172 && second >= 16 && second <= 31;
    }
    private PeerEndpoint endpoint(Peer peer, int port) { return new PeerEndpoint(peer.getInetAddress(), port); }
    private PeerEndpoint endpoint(Peer peer, int port, Transport transport) { return new PeerEndpoint(peer.getInetAddress(), port, transport); }
    private String key(String infoHash, PeerEndpoint endpoint) {
        return infoHash.toLowerCase(Locale.ROOT) + "|" + endpoint.addressFamily() + "|" + endpoint.transport() + "|" + endpoint.display();
    }
    private void validateInfoHash(String infoHash) { validateInfoHashStatic(infoHash); }
    private static void validateInfoHashStatic(String infoHash) {
        if (infoHash == null || !infoHash.matches("[0-9a-fA-F]{40}")) throw new IllegalArgumentException("infoHash invalido");
    }
    private String describe(Throwable error) {
        String detail = error.getMessage(); return error.getClass().getSimpleName() + (detail == null || detail.isBlank() ? "" : ": " + detail);
    }

    @Override public void close() {
        closing = true;
        connectTimers.values().forEach(timer -> timer.cancel(false)); connectTimers.clear();
        retryTimers.values().forEach(timer -> timer.cancel(false)); retryTimers.clear(); scheduler.shutdownNow(); peers.clear();
    }

    private static final class MutablePeerState {
        private final String infoHash; private final PeerEndpoint endpoint; private final AddressFamily family;
        private final EnumSet<DiscoveryOrigin> origins = EnumSet.noneOf(DiscoveryOrigin.class);
        private TransportSupport tcp = TransportSupport.UNKNOWN; private TransportSupport utp = TransportSupport.UNKNOWN;
        private Strategy strategy = Strategy.NONE; private ConnectionState connection = ConnectionState.DISCOVERED;
        private int directAttempts; private Instant lastSeen = Instant.now(); private Instant lastPromotion;
        private boolean handshakeStarted; private String failureReason = ""; private SocketAttempt attempt;
        private Instant nextRetryAt; private Instant lastSuppressedAt;
        private Optional<LuffyNodeId> luffyNodeId = Optional.empty();
        private Optional<LuffyPeerCapabilities> luffyCapabilities = Optional.empty();
        private boolean removed;
        private UUID lastFinishedOverlaySession;
        private MutablePeerState(String infoHash, PeerEndpoint endpoint) { this.infoHash = infoHash; this.endpoint = endpoint; this.family = endpoint.family(); }
        private synchronized PeerState snapshot() { return new PeerState(infoHash, endpoint, family, tcp, utp, strategy, connection, directAttempts, lastSeen,
                List.copyOf(origins), nextRetryAt, failureReason, attempt); }
    }

    /** Uma rodada pequena e ordenada: evita disparar todos os endpoints ao mesmo tempo. */
    private static final class ConnectionRace {
        private final List<PeerEndpoint> pending = new ArrayList<>();
        private final java.util.Set<PeerEndpoint> scheduled = new java.util.HashSet<>();
        private final java.util.Set<PeerEndpoint> inFlight = new java.util.HashSet<>();
        private final Map<PeerEndpoint, ScheduledFuture<?>> futures = new java.util.HashMap<>();
        private boolean waveRequested;
        private boolean completed;

        private synchronized boolean enqueue(PeerEndpoint endpoint) {
            if (completed || pending.contains(endpoint) || scheduled.contains(endpoint) || inFlight.contains(endpoint)) return false;
            pending.add(endpoint);
            return true;
        }
        private synchronized boolean requestWave() {
            if (completed || waveRequested || !scheduled.isEmpty() || !inFlight.isEmpty() || pending.isEmpty()) return false;
            waveRequested = true;
            return true;
        }
        private synchronized List<PeerEndpoint> reserveWave() {
            waveRequested = false;
            if (completed || !scheduled.isEmpty() || !inFlight.isEmpty() || pending.isEmpty()) return List.of();
            pending.sort(Comparator.comparingInt(ConnectionRace::priority));
            int count = Math.min(MAX_HAPPY_EYEBALLS_PATHS, pending.size());
            List<PeerEndpoint> wave = new ArrayList<>(pending.subList(0, count));
            pending.subList(0, count).clear();
            scheduled.addAll(wave);
            return wave;
        }
        private synchronized void register(PeerEndpoint endpoint, ScheduledFuture<?> future) {
            if (completed || !scheduled.contains(endpoint)) future.cancel(false);
            else futures.put(endpoint, future);
        }
        private synchronized boolean dispatch(PeerEndpoint endpoint) {
            if (completed || !scheduled.remove(endpoint)) return false;
            futures.remove(endpoint);
            inFlight.add(endpoint);
            return true;
        }
        private synchronized boolean finish(PeerEndpoint endpoint) {
            scheduled.remove(endpoint);
            inFlight.remove(endpoint);
            futures.remove(endpoint);
            return !completed && scheduled.isEmpty() && inFlight.isEmpty() && !pending.isEmpty();
        }
        private synchronized boolean isExhausted() {
            return !completed && pending.isEmpty() && scheduled.isEmpty() && inFlight.isEmpty();
        }
        private synchronized List<ScheduledFuture<?>> complete(PeerEndpoint winner) {
            if (completed) return List.of();
            completed = true;
            List<ScheduledFuture<?>> result = new ArrayList<>();
            futures.forEach((endpoint, future) -> { if (!endpoint.equals(winner)) result.add(future); });
            futures.clear(); pending.clear(); scheduled.clear(); inFlight.clear();
            return result;
        }
        private synchronized List<ScheduledFuture<?>> cancelAll() {
            completed = true;
            List<ScheduledFuture<?>> result = new ArrayList<>(futures.values());
            futures.clear(); pending.clear(); scheduled.clear(); inFlight.clear();
            return result;
        }
        private static int priority(PeerEndpoint endpoint) {
            if (endpoint.family() == AddressFamily.IPV6 && endpoint.transport() == Transport.TCP) return 0;
            if (endpoint.family() == AddressFamily.IPV6) return 1;
            if (endpoint.transport() == Transport.TCP) return 2;
            return 3;
        }
    }

    private record EndpointPath(AddressFamily family, Transport transport) { }
}
