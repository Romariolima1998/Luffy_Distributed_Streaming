package dev.lufi.infrastructure;

import bt.Bt;
import bt.data.file.FileSystemStorage;
import bt.runtime.BtClient;
import bt.runtime.BtRuntime;
import bt.runtime.Config;
import bt.dht.DHTConfig;
import bt.dht.DHTService;
import bt.metainfo.TorrentId;
import bt.net.InetPeer;
import bt.net.Peer;
import bt.peer.IPeerRegistry;
import bt.peerexchange.PeerExchangeModule;
import bt.torrent.selector.PrioritizedPieceSelector;
import bt.torrent.selector.SequentialSelector;
import dev.lufi.application.port.TorrentGateway;
import dev.lufi.application.port.TorrentContent;
import dev.lufi.domain.MagnetLink;
import dev.lufi.domain.StreamingSession;
import dev.lufi.domain.WatchMode;
import dev.lufi.infrastructure.identity.LuffyIdentityExtension;
import dev.lufi.infrastructure.identity.LuffyIdentityMessage;
import dev.lufi.infrastructure.identity.LuffyIdentityStorage;
import dev.lufi.infrastructure.identity.LuffyNodeIdentity;
import dev.lufi.infrastructure.identity.ConnectedLuffyRegistry;
import dev.lufi.infrastructure.bootstrap.OfficialBootstrapSwarm;
import dev.lufi.infrastructure.bootstrap.BootstrapSwarmManager;
import dev.lufi.infrastructure.bootstrap.BootstrapSwarmState;
import dev.lufi.infrastructure.bootstrap.BootstrapNeighborManager;
import dev.lufi.infrastructure.bootstrap.BootstrapPeerConnectionRegistry;
import dev.lufi.infrastructure.overlay.LuffyRouteExtension;
import dev.lufi.infrastructure.rendezvous.LuffyRendezvousExtension;
import dev.lufi.infrastructure.rendezvous.RendezvousFallbackCoordinator;
import dev.lufi.infrastructure.overlay.RouteSearchResult;
import dev.lufi.infrastructure.overlay.FindNodeService;
import dev.lufi.infrastructure.rendezvous.RendezvousSession;
import dev.lufi.infrastructure.identity.LuffyNodeId;
import dev.lufi.infrastructure.identity.LuffyPeerCapabilities;
import dev.lufi.infrastructure.security.AbuseProtectionConfig;
import dev.lufi.infrastructure.security.AbuseProtectionService;
import java.net.Inet4Address;
import java.net.MalformedURLException;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Enumeration;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Motor BitTorrent distribuído: DHT e descoberta local são carregadas dos módulos presentes no classpath. */
public final class BtTorrentGateway implements TorrentGateway, AutoCloseable {
    private static final String LUFFY_CLIENT_VERSION = "Luffy/0.1.0";
    private static final Duration DIAGNOSTIC_TEST_DEADLINE = Duration.ofSeconds(45);
    private static final Duration SWARM_ASSIST_MAINTENANCE_INTERVAL = Duration.ofMinutes(5);
    private static final Duration SWARM_ASSIST_REPLENISH_DELAY = Duration.ofSeconds(5);
    private static final Duration SWARM_ASSIST_PEX_FRESHNESS = Duration.ofMinutes(2);
    /** Margem inicial contínua antes de abrir um arquivo temporário no player. */
    public static final int DEFAULT_STREAM_STARTUP_PIECES = 24;
    public static final int MIN_STREAM_STARTUP_PIECES = 1;
    public static final int MAX_STREAM_STARTUP_PIECES = 500;
    private final Path cacheDirectory;
    private final P2pDiagnostics diagnostics;
    private final Map<String, BtClient> sessions = new ConcurrentHashMap<>();
    /** Permite aguardar o encerramento efetivo de um cliente antes de reutilizar o mesmo torrent na runtime. */
    private final Map<BtClient, CompletableFuture<?>> clientProcessCompletions = new ConcurrentHashMap<>();
    /** Prévia isolada: impede que prioridades SKIP contaminem a sessão principal do torrent. */
    private final Map<String, BtRuntime> metadataPreviewRuntimes = new ConcurrentHashMap<>();
    /** Swarms cujo conteúdo existe localmente: esta é a lista de SEEDING SWARMS. */
    private final Map<String, BtClient> seedingSessions = new ConcurrentHashMap<>();
    /** Sessões que participam do swarm, mas não podem solicitar nem semear conteúdo. */
    private final Map<String, BtClient> swarmAssistSessions = new ConcurrentHashMap<>();
    /** Papel do conteúdo solicitado pelo usuário; não é persistido nem confunde seed com Assist. */
    private final Map<String, ConnectionRole> userTransferRoles = new ConcurrentHashMap<>();
    /** Apenas os magnets aprovados pela política configurável de Swarm Assist. */
    private final Map<String, MagnetLink> swarmAssistMagnets = new ConcurrentHashMap<>();
    /** Evita consulta DHT duplicada quando a restauração gradual já está revalidando a população. */
    private final java.util.Set<String> swarmAssistInitialLookupSuppressed = ConcurrentHashMap.newKeySet();
    private final Map<String, SwarmAssistStats> swarmAssistStats = new ConcurrentHashMap<>();
    private final Map<String, Instant> swarmAssistLastPexAt = new ConcurrentHashMap<>();
    /** Evita contar duas vezes o mesmo evento final do handshake uTP/BEP55. */
    private final Set<String> recordedHolePunchSuccesses = ConcurrentHashMap.newKeySet();
    /** Tarefas de manutenção separadas das sessões de seeding e download. */
    private final Map<String, ScheduledFuture<?>> swarmAssistMaintenanceTasks = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> swarmAssistReplenishTasks = new ConcurrentHashMap<>();
    private final ScheduledExecutorService swarmAssistMaintenance = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());
    private final SwarmAssistDhtScheduler swarmAssistDhtScheduler = new SwarmAssistDhtScheduler();
    private final Map<String, Path> publishedRoots = new ConcurrentHashMap<>();
    private final java.util.Set<Path> temporaryRoots = ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> observedTorrents = ConcurrentHashMap.newKeySet();
    /** O papel pode mudar de participação passiva para download sem duplicar os listeners do torrent. */
    private final Map<String, String> torrentDiagnosticRoles = new ConcurrentHashMap<>();
    private final Map<String, TransferSnapshot> transferSnapshots = new ConcurrentHashMap<>();
    private final Map<String, StreamingMediaFile> streamingMediaFiles = new ConcurrentHashMap<>();
    /** Seletores da sessao de streaming: faixa HTTP atual e seu read-ahead ganham precedencia. */
    private final Map<String, PrioritizedPieceSelector> streamingPieceSelectors = new ConcurrentHashMap<>();
    /** Janela ativa por torrent; uma nova janela substitui a prioridade anterior. */
    private final Map<String, StreamingPriorityWindow> streamingPriorityWindows = new ConcurrentHashMap<>();
    private volatile StreamingReadAheadPolicy streamingReadAheadPolicy = StreamingReadAheadPolicy.defaults();
    /** Preferência da UI, aplicada somente ao ponto de início do streaming. */
    private volatile int streamStartupPieces = DEFAULT_STREAM_STARTUP_PIECES;
    /** Peças iniciais confirmadas por torrent; necessárias antes de entregar um arquivo pré-alocado ao player. */
    private final StreamingPiecePrefixTracker streamingPiecePrefixes = new StreamingPiecePrefixTracker();
    private final PeerConnectivityManager peerConnectivity;
    private final SwarmAssistConnectionPolicy swarmAssistConnectionPolicy = new SwarmAssistConnectionPolicy();
    private volatile SwarmAssistPolicy activeSwarmAssistPolicy = SwarmAssistPolicy.defaults();
    /** Orçamento único para promoções de saída, aplicado antes do bt-core abrir o socket. */
    private final GlobalConnectionBudget globalConnectionBudget = new GlobalConnectionBudget();
    private final SwarmAssistResourceGovernor swarmAssistResourceGovernor = new SwarmAssistResourceGovernor();
    /** Protecao temporaria da camada de controle; nao participa de DHT nem de pieces. */
    private final AbuseProtectionService abuseProtection = new AbuseProtectionService();
    private final UtpBitTorrentBridge utpBridge;
    private final Bep55HolePunchAgent holePunchAgent;
    private final LuffyNodeIdentity nodeIdentity;
    /** Artefato imutavel validado antes de a runtime P2P ser criada. A adesao ao swarm vira etapa propria. */
    private final OfficialBootstrapSwarm officialBootstrapSwarm;
    /** Registro unico por gateway: agrega identidades validas de todos os torrents ativos. */
    private final ConnectedLuffyRegistry connectedLuffyRegistry;
    private final LuffyIdentityExtension identityExtension;
    /** Roteamento BEP 10 entre vizinhos ja existentes; nao cria runtime nem socket adicional. */
    private final LuffyRouteExtension routeExtension;
    /** Controle de rendezvous sobre a rota existente; nunca transporta dados do torrent. */
    private final LuffyRendezvousExtension rendezvousExtension;
    /** Ultimo fallback, acionado pelo PeerConnectivityManager somente apos BEP55 local falhar. */
    private final RendezvousFallbackCoordinator rendezvousFallbackCoordinator;
    /** Sessao separada da biblioteca e do Swarm Assist, mas sempre na mesma BtRuntime principal. */
    private final BootstrapSwarmManager bootstrapSwarmManager;
    /** Referencias vivas usadas exclusivamente pela politica de vizinhos do swarm oficial. */
    private final BootstrapPeerConnectionRegistry bootstrapPeerConnections;
    private final BootstrapNeighborManager bootstrapNeighborManager;
    private volatile Consumer<String> statusListener = ignored -> { };
    /** A UI decide se um streaming temporário concluído vira candidato à Swarm Assist List. */
    private volatile Consumer<MagnetLink> temporaryWatchCompletedListener = ignored -> { };
    /** Adaptador para a política persistida; o motor não acessa SQLite nem a UI. */
    private volatile Consumer<SwarmAssistActivity> swarmAssistActivityListener = ignored -> { };
    private volatile ConnectivityProfile connectivity = ConnectivityProfile.unavailable();
    private volatile DhtLookupRuntimeSettings dhtLookupRuntimeSettings = DhtLookupRuntimeSettings.defaults();
    private volatile BtRuntime runtime;
    private final AtomicBoolean shuttingDown = new AtomicBoolean();
    private final Object dhtLookupShutdownMonitor = new Object();
    private final Map<Thread, CompletableFuture<Integer>> pendingDhtLookups = new ConcurrentHashMap<>();
    /** Runtime DHT sem torrents: no modo outbound-only ela só consulta peers e nunca anuncia esta máquina. */
    private final DhtLookupRuntimeLifecycle ipv4DhtLookupLifecycle;
    private final DhtLookupRuntimeLifecycle ipv6DhtLookupLifecycle;
    private volatile boolean transferRuntimeDhtAnnounceEnabled;
    private volatile BtRuntime ipv6Runtime;
    private volatile UtpTransportService utpTransport;
    private volatile boolean swarmAssistSuspendedForPriority;

    public BtTorrentGateway(Path cacheDirectory) { this(cacheDirectory, new P2pDiagnostics()); }
    public BtTorrentGateway(Path cacheDirectory, P2pDiagnostics diagnostics) {
        this(cacheDirectory, diagnostics, loadIdentity(cacheDirectory, diagnostics));
    }
    public BtTorrentGateway(Path cacheDirectory, P2pDiagnostics diagnostics, LuffyNodeIdentity nodeIdentity) {
        this.cacheDirectory = cacheDirectory;
        this.diagnostics = diagnostics == null ? new P2pDiagnostics() : diagnostics;
        this.ipv4DhtLookupLifecycle = dhtLookupLifecycle(false);
        this.ipv6DhtLookupLifecycle = dhtLookupLifecycle(true);
        this.nodeIdentity = java.util.Objects.requireNonNull(nodeIdentity, "nodeIdentity");
        this.officialBootstrapSwarm = OfficialBootstrapSwarm.loadAndValidate();
        this.diagnostics.log("[BOOTSTRAP] Ola Luffy validado: infoHash=" + OfficialBootstrapSwarm.INFO_HASH
                + "; artefato oficial carregado; sessao magnet sera iniciada ao configurar a conectividade.");
        this.peerConnectivity = new PeerConnectivityManager(this.diagnostics, this::promotePeerToBitTorrent);
        this.peerConnectivity.setConnectionAdmission(this::admitPeerPromotion);
        this.utpBridge = new UtpBitTorrentBridge(this.diagnostics, this.peerConnectivity);
        this.holePunchAgent = new Bep55HolePunchAgent(this.diagnostics, this.utpBridge);
        this.connectedLuffyRegistry = new ConnectedLuffyRegistry();
        this.identityExtension = new LuffyIdentityExtension(this.nodeIdentity, this::localIdentityMessage, this.diagnostics,
                this.connectedLuffyRegistry, this.abuseProtection);
        this.routeExtension = new LuffyRouteExtension(this.nodeIdentity, this::localRouteCapabilities,
                this.officialBootstrapSwarm.torrentId(), this.connectedLuffyRegistry, this.diagnostics, this.abuseProtection);
        this.rendezvousExtension = new LuffyRendezvousExtension(this.nodeIdentity, this.routeExtension.routePaths(),
                this.connectedLuffyRegistry, this::localConfirmedUtpEndpoint,
                (torrentId, endpoint) -> holePunchAgent.startDistributedHolePunch(torrentId, endpoint.address(), endpoint.port()),
                this.diagnostics, this.abuseProtection);
        this.rendezvousFallbackCoordinator = new RendezvousFallbackCoordinator(this.diagnostics,
                context -> requestOverlayRendezvous(context.targetNodeId().orElseThrow(), context.infoHash()),
                () -> localConfirmedUtpEndpoint().isPresent(), peerConnectivity::onOverlayRendezvousFinished);
        this.rendezvousExtension.setSessionFinishedListener(rendezvousFallbackCoordinator::onRendezvousSessionFinished);
        this.peerConnectivity.setOverlayRendezvousFallback(rendezvousFallbackCoordinator);
        this.peerConnectivity.setTorrentActivity(this::isTorrentSessionActive);
        this.identityExtension.setIdentityAcceptedListener((key, capabilities) -> peerConnectivity.onLuffyIdentity(
                hex(key.getTorrentId()), key.getPeer(), key.getRemotePort(), capabilities));
        this.bootstrapPeerConnections = new BootstrapPeerConnectionRegistry();
        this.bootstrapSwarmManager = new BootstrapSwarmManager(OfficialBootstrapSwarm.MAGNET_URI,
                OfficialBootstrapSwarm.INFO_HASH, this.connectedLuffyRegistry, this::createBootstrapSession, this.diagnostics);
        this.bootstrapNeighborManager = new BootstrapNeighborManager(this.officialBootstrapSwarm.torrentId(),
                this.connectedLuffyRegistry, this.bootstrapPeerConnections, this::requestBootstrapNeighborDiscovery, this.diagnostics);
        this.bootstrapSwarmManager.setNeighborManager(this.bootstrapNeighborManager);
        this.holePunchAgent.setExtensionHandshakeListener(identityExtension::onExtendedHandshake);
        this.holePunchAgent.setCapabilityListener(this::refreshSwarmAssistStats);
        this.holePunchAgent.setUsefulRendezvousListener(infoHash -> reportSwarmAssistActivity(infoHash,
                SwarmAssistActivity.Type.USEFUL_RENDEZVOUS));
        this.holePunchAgent.setRendezvousRelayedListener(infoHash -> reportSwarmAssistActivity(infoHash,
                SwarmAssistActivity.Type.HOLE_PUNCH_RELAYED));
        this.peerConnectivity.setHolePunchRequester((infoHash, endpoint) -> holePunchAgent.requestRendezvous(infoHash, endpoint.address(), endpoint.port()));
        this.peerConnectivity.setPeerEndpointObserver(holePunchAgent::observePeerUtpEndpoint);
    }

    private static LuffyNodeIdentity loadIdentity(Path cacheDirectory, P2pDiagnostics diagnostics) {
        Path normalized = cacheDirectory.toAbsolutePath().normalize();
        Path identityDirectory = normalized.getParent() == null ? normalized : normalized.getParent();
        P2pDiagnostics targetDiagnostics = diagnostics == null ? new P2pDiagnostics() : diagnostics;
        return new LuffyIdentityStorage(identityDirectory,
                message -> targetDiagnostics.log("[IDENTITY] " + message)).loadOrCreate();
    }

    /** Anuncia somente recursos ja operacionais nesta runtime, e nao configuracoes desejadas. */
    private LuffyIdentityMessage localIdentityMessage(bt.torrent.messaging.MessageContext context) {
        boolean ipv4Peer = context.getConnectionKey().getPeer().getInetAddress() instanceof Inet4Address;
        boolean utpOperational = ipv4Peer && utpTransport != null && utpTransport.isListening() && utpBridge.isReady();
        return new LuffyIdentityMessage(LuffyIdentityMessage.PROTOCOL_VERSION, nodeIdentity.nodeId(), LUFFY_CLIENT_VERSION,
                true, utpOperational, utpOperational, utpOperational);
    }

    /** Capacidades reais locais usadas quando esta instalacao e o alvo de uma rota. */
    private LuffyPeerCapabilities localRouteCapabilities() {
        boolean utpOperational = utpTransport != null && utpTransport.isListening() && utpBridge.isReady();
        return new LuffyPeerCapabilities(LuffyIdentityMessage.PROTOCOL_VERSION, nodeIdentity.nodeId(), LUFFY_CLIENT_VERSION,
                true, utpOperational, utpOperational, utpOperational);
    }

    /** O overlay so informa o endpoint UDP que esta confirmado, publico e ainda valido. */
    private Optional<dev.lufi.infrastructure.rendezvous.LuffyRendezvousMessage.RendezvousEndpoint> localConfirmedUtpEndpoint() {
        return dev.lufi.infrastructure.rendezvous.RendezvousEndpointSelector.selectConfirmedUtp(
                connectivity.observedEndpoints(), Instant.now());
    }

    /** O fallback de overlay não deve ressuscitar um torrent que a aplicação já encerrou. */
    private boolean isTorrentSessionActive(String infoHash) {
        BtClient session = sessions.get(infoHash);
        return session != null && session.isStarted();
    }
    public P2pDiagnostics diagnostics() { return diagnostics; }

    /** Contadores locais dos eventos estruturados; nunca são enviados à rede. */
    public Map<String, Long> diagnosticMetrics() { return diagnostics.metricsSnapshot(); }
    /** Estado local para a aba de diagnóstico; não cria sockets nem muda a conectividade. */
    public String connectivityVisualReport(List<Inet4Address> localIpv4) {
        UtpTransportService utp = utpTransport;
        boolean dhtListening = ipv4DhtLookupLifecycle.isReady() || ipv6DhtLookupLifecycle.isReady()
                || transferRuntimeDhtAnnounceEnabled && runtime != null;
        return ConnectivityVisualReport.render(connectivity, localIpv4, runtime != null,
                utp != null && utp.isListening(), dhtListening);
    }
    /** Estado individual de endpoints, capacidades e BEP 55 para depuração. */
    public String peerVisualReport() {
        return PeerVisualReport.render(peerConnectivity.allPeers(), holePunchAgent::peerDebugStatus);
    }
    /** Snapshot consolidado de um swarm; a política de seleção será definida nas próximas etapas. */
    public SwarmAssistStats swarmAssistStats(String infoHash) { return refreshSwarmAssistStats(infoHash); }
    /** Atualiza somente orçamentos Assist; downloads, streaming e seeding continuam fora deles. */
    public void setSwarmAssistPolicy(SwarmAssistPolicy policy) {
        activeSwarmAssistPolicy = policy == null ? SwarmAssistPolicy.defaults() : policy;
        swarmAssistConnectionPolicy.setPolicy(activeSwarmAssistPolicy);
    }
    /** Atualiza os limites globais e os limites de conexão da runtime, sem alterar DHT ou torrents ativos. */
    public void setConnectionLimits(ConnectionLimits limits) {
        globalConnectionBudget.setLimits(limits);
        ConnectionLimits current = globalConnectionBudget.limits();
        if (runtime != null) applyConnectionLimits(runtime.getConfig(), current);
        if (ipv6Runtime != null) applyConnectionLimits(ipv6Runtime.getConfig(), current);
        diagnostics.log(P2pDiagnostics.Layer.CONNECTIVITY, "CONNECTION BUDGET: stream/download="
                + current.maxDownloadConnections() + "; seed=" + current.maxSeedConnections()
                + "; rendezvous/overlay=" + current.maxOverlayConnections() + "; assist="
                + current.maxAssistConnections() + "; pending=" + current.maxPendingConnections()
                + "; total=" + current.maxTotalConnections() + ".");
    }
    public ConnectionLimits connectionLimits() { return globalConnectionBudget.limits(); }
    /** Atualiza quantas pieces iniciais, verificadas e contínuas, o player espera antes de abrir o HTTP local. */
    public void setStreamingStartupPieces(int pieces) {
        streamStartupPieces = Math.max(MIN_STREAM_STARTUP_PIECES, Math.min(MAX_STREAM_STARTUP_PIECES, pieces));
        diagnostics.log(P2pDiagnostics.Layer.DOWNLOAD, "STREAM STARTUP BUFFER: requiredPieces=" + streamStartupPieces + ".");
    }
    public int streamingStartupPieces() { return streamStartupPieces; }
    /** Estado real do buffer, atualizado pelo callback do bt-core a cada segundo. */
    public StreamingBufferStatus streamingBufferStatus(String infoHash) {
        if (infoHash == null || infoHash.isBlank()) return StreamingBufferStatus.unavailable();
        TransferSnapshot snapshot = transferSnapshots.get(infoHash.toLowerCase());
        boolean active = isTorrentSessionActive(infoHash);
        int configuredStartupPieces = streamStartupPieces;
        if (snapshot == null) return new StreamingBufferStatus(0, 0, 0, 0, configuredStartupPieces, active);
        int required = snapshot.piecesTotal() > 0 ? Math.min(configuredStartupPieces, snapshot.piecesTotal()) : configuredStartupPieces;
        int contiguousPrefix = streamingPiecePrefixes.contiguousPrefix(infoHash, snapshot.piecesTotal());
        return new StreamingBufferStatus(snapshot.downloaded(), snapshot.piecesComplete(), contiguousPrefix,
                snapshot.piecesTotal(), required, active);
    }

    /**
     * Read-only availability of a streamed file. This only reports bytes backed by
     * verified pieces; it never changes peer selection or piece priority.
     */
    public StreamingMediaWindow streamingMediaWindow(String infoHash, Path file) {
        if (infoHash == null || infoHash.isBlank() || file == null) return StreamingMediaWindow.unavailable();
        StreamingMediaFile mediaFile = streamingMediaFiles.get(streamingMediaKey(infoHash, file));
        boolean active = isTorrentSessionActive(infoHash);
        if (mediaFile == null) return new StreamingMediaWindow(0, 0, active);
        TransferSnapshot snapshot = transferSnapshots.get(infoHash.toLowerCase());
        if (snapshot == null || snapshot.piecesTotal() <= 0) {
            return new StreamingMediaWindow(mediaFile.lengthBytes(), 0, active);
        }
        int prefixPieces = streamingPiecePrefixes.contiguousPrefix(infoHash, snapshot.piecesTotal());
        long verifiedTorrentBytes = Math.min(mediaFile.torrentLengthBytes(),
                saturatedMultiply(prefixPieces, mediaFile.pieceLengthBytes()));
        long verifiedFileBytes = Math.max(0,
                Math.min(mediaFile.lengthBytes(), verifiedTorrentBytes - mediaFile.offsetBytes()));
        return new StreamingMediaWindow(mediaFile.lengthBytes(), verifiedFileBytes, active);
    }

    /** Maps one HTTP byte range of a selected file to its owning torrent pieces. */
    public Optional<StreamingPieceRange> streamingPiecesForRange(String infoHash, Path file,
                                                                  long fileStartByte, long fileEndByte) {
        if (infoHash == null || infoHash.isBlank() || file == null) return Optional.empty();
        StreamingMediaFile mediaFile = streamingMediaFiles.get(streamingMediaKey(infoHash, file));
        if (mediaFile == null) return Optional.empty();
        return StreamingPieceRangeMapper.map(mediaFile.torrentLengthBytes(), mediaFile.offsetBytes(),
                mediaFile.lengthBytes(), mediaFile.pieceLengthBytes(), fileStartByte, fileEndByte);
    }

    /** Progress of the pieces that own one HTTP range; used only by the local media bridge. */
    public Optional<StreamingRangeProgress> streamingRangeProgress(String infoHash, Path file,
                                                                    long fileStartByte, long fileEndByte) {
        Optional<StreamingPieceRange> mapped = streamingPiecesForRange(infoHash, file, fileStartByte, fileEndByte);
        if (mapped.isEmpty()) return Optional.empty();
        StreamingPieceRange range = mapped.get();
        int required = range.endPiece() - range.startPiece() + 1;
        int ready = streamingPiecePrefixes.countVerified(infoHash, range.startPiece(), range.endPiece());
        return Optional.of(new StreamingRangeProgress(required, ready));
    }

    /**
     * Gives the active HTTP range and its bounded read-ahead precedence over
     * the sequential fallback. It does not alter discovery, peers, transports
     * or piece verification; bt-core still validates every piece hash.
     */
    public void prioritizeStreamingRange(String infoHash, Path file, long fileStartByte, long fileEndByte) {
        Optional<StreamingPieceRange> mapped = streamingPiecesForRange(infoHash, file, fileStartByte, fileEndByte);
        if (mapped.isEmpty()) return;
        StreamingPieceRange range = mapped.get();
        PrioritizedPieceSelector selector = streamingPieceSelectors.get(normalizeInfoHash(infoHash));
        if (selector == null) return;
        StreamingMediaFile mediaFile = streamingMediaFiles.get(streamingMediaKey(infoHash, file));
        if (mediaFile == null) return;
        int totalPieces = totalPieces(mediaFile);
        int verifiedPrefixPieces = streamingPiecePrefixes.contiguousPrefix(infoHash, totalPieces);
        StreamingPriorityWindow current = StreamingPriorityWindow.forStreamingRequest(range, totalPieces,
                verifiedPrefixPieces, streamingReadAheadPolicy);
        StreamingPriorityWindow previous = streamingPriorityWindows.put(normalizeInfoHash(infoHash), current);
        // setHighPriorityPieces troca o BitSet inteiro de forma atômica no bt-core:
        // pieces da posição anterior voltam imediatamente ao seletor sequencial.
        selector.setHighPriorityPieces(current.pieces());
        int piecesReady = streamingPiecePrefixes.countVerified(infoHash, range.startPiece(), range.endPiece());
        String requestKind = current.isSeekFrom(previous) ? "SEEK" : "READ";
        String priorityMode = current.priorityStartPiece() == range.startPiece() ? "REQUEST" : "PREFIX_FRONTIER";
        diagnostics.log(P2pDiagnostics.Layer.DOWNLOAD, "[STREAM-PRIORITY] requestedPiece=" + range.startPiece()
                + "; requestedEndPiece=" + range.endPiece()
                + "; priorityStartPiece=" + current.priorityStartPiece()
                + "; priorityEndPiece=" + current.priorityEndPiece()
                + "; priorityMode=" + priorityMode
                + "; request=" + requestKind
                + "; prefixPieces=" + verifiedPrefixPieces
                + "; piecesReady=" + piecesReady + ".");
        if (requestKind.equals("SEEK") && previous != null) {
            diagnostics.log(P2pDiagnostics.Layer.DOWNLOAD, "STREAM HTTP SEEK PRIORITY SHIFT: infoHash=" + infoHash
                    + "; previousPieces=" + previous.priorityStartPiece() + "-" + previous.priorityEndPiece()
                    + "; currentPieces=" + current.priorityStartPiece() + "-" + current.priorityEndPiece()
                    + "; oldPriority=cleared.");
        }
    }

    /**
     * Remove somente a prioridade temporária de leitura do player.
     *
     * <p>O torrent, seus peers, verificações e download normal permanecem
     * ativos. Sem essa chamada, uma janela antiga de reprodução poderia
     * continuar recebendo precedência mesmo depois de o usuário parar o vídeo.</p>
     */
    public void clearStreamingPriority(String infoHash) {
        if (infoHash == null || infoHash.isBlank()) return;
        String normalized = normalizeInfoHash(infoHash);
        StreamingPriorityWindow previous = streamingPriorityWindows.remove(normalized);
        PrioritizedPieceSelector selector = streamingPieceSelectors.get(normalized);
        if (selector != null) {
            selector.setHighPriorityPieces(new java.util.BitSet());
        }
        if (previous != null) {
            diagnostics.log(P2pDiagnostics.Layer.DOWNLOAD, "[STREAM-PRIORITY] action=CLEARED; infoHash=" + infoHash
                    + "; previousPieces=" + previous.priorityStartPiece() + "-" + previous.priorityEndPiece() + ".");
        }
    }

    /** Ajusta o read-ahead das próximas solicitações HTTP de streaming. */
    public void setStreamingReadAheadPolicy(StreamingReadAheadPolicy policy) {
        streamingReadAheadPolicy = Objects.requireNonNull(policy, "policy");
    }

    public StreamingReadAheadPolicy streamingReadAheadPolicy() {
        return streamingReadAheadPolicy;
    }

    /**
     * True only when every owning piece of this file byte range emitted a
     * bt-core piece-verified event. File allocation and downloaded byte counts
     * are intentionally not considered proof of availability here.
     */
    public boolean isStreamingFileRangeVerified(String infoHash, Path file, long fileStartByte, long fileEndByte) {
        Optional<StreamingPieceRange> mapped = streamingPiecesForRange(infoHash, file, fileStartByte, fileEndByte);
        return mapped.isPresent() && streamingPiecePrefixes.containsAll(infoHash,
                mapped.get().startPiece(), mapped.get().endPiece());
    }
    /** Atualiza somente os guardrails de abuso; conexoes BitTorrent estabelecidas nao sao tocadas. */
    public void setAbuseProtectionConfig(AbuseProtectionConfig config) {
        abuseProtection.setConfig(config);
        diagnostics.log(P2pDiagnostics.Layer.CONNECTIVITY, "SECURITY LIMITS: find-node="
                + config.maxFindNodeRequestsPerMinute() + "/min; forwarded=" + config.maxForwardedRequestsPerMinute()
                + "/min; route=" + config.maxConcurrentRouteSearches() + "; rendezvous="
                + config.maxConcurrentRendezvousSessions() + "; payload=" + config.maxPayloadBytes()
                + "; pending-uTP=" + config.maxPendingUtpSessions() + ".");
    }
    public AbuseProtectionConfig abuseProtectionConfig() { return abuseProtection.config(); }
    public void setStatusListener(Consumer<String> listener) {
        statusListener = listener == null ? ignored -> { } : listener;
        holePunchAgent.setStatusListener(statusListener);
    }
    public void setTemporaryWatchCompletedListener(Consumer<MagnetLink> listener) {
        temporaryWatchCompletedListener = listener == null ? ignored -> { } : listener;
    }
    public void setSwarmAssistActivityListener(Consumer<SwarmAssistActivity> listener) {
        swarmAssistActivityListener = listener == null ? ignored -> { } : listener;
    }
    public DhtLookupRuntimeSettings dhtLookupRuntimeSettings() { return dhtLookupRuntimeSettings; }
    /** Applies to the next lookup runtime created after a failed start or application restart. */
    public void setDhtLookupRuntimeSettings(DhtLookupRuntimeSettings settings) {
        dhtLookupRuntimeSettings = settings == null ? DhtLookupRuntimeSettings.defaults() : settings;
    }
    /** A reprodução no player é atividade de primeiro plano e suspende Swarm Assist. */
    public void setForegroundPlaybackActive(boolean active) {
        swarmAssistResourceGovernor.setForegroundPlayback(active);
        reconcileSwarmAssistResourcePriority();
    }
    public void setConnectivityProfile(ConnectivityProfile profile) {
        connectivity = profile == null ? ConnectivityProfile.unavailable() : profile;
        peerConnectivity.setLocalConnectivity(connectivity);
        ensureUtpTransport();
        bootstrapSwarmManager.start();
        BtRuntime active = runtime;
        if (active != null && connectivity.dhtAnnouncement().shouldAnnounce() && transferRuntimeDhtAnnounceEnabled) {
            configureIpv4DhtAnnouncementPort(active);
        } else if (active != null && connectivity.dhtAnnouncement().shouldAnnounce()) {
            diagnostics.log("DHT ANNOUNCE DEFERRED: a runtime de transferência foi criada em modo outbound-only; "
                    + "o anúncio seguro será aplicado na próxima abertura do Luffy.");
        }
        diagnostics.log("REDE configurada: P2P TCP local " + connectivity.torrentListeningPort() + ", DHT UDP local " + connectivity.dhtListeningPort()
                + connectivity.ipv4PublicPeerEndpoint().map(endpoint -> "; endpoint TCP externo observado "
                + endpoint.address().getHostAddress() + ":" + endpoint.port() + " (" + endpoint.mechanism() + ", ainda nao confirmado)")
                .orElse("; sem endpoint TCP externo observado")
                + "; anúncio DHT=" + (connectivity.dhtAnnouncement().shouldAnnounce() ? "permitido" : "suprimido (outbound-only/firewalled)") + ".");
        diagnostics.log(connectivity.hasGlobalIpv6()
                ? "DHT DUAL STACK: IPv4 e IPv6 serão consultados em paralelo; endpoints IPv6 serão preservados separadamente."
                : "DHT IPv6 desativado: nenhum endereço IPv6 unicast global confirmado nesta máquina.");
    }

    /** Estado interno do swarm oficial, mantido fora das abas de biblioteca e Swarm Assist. */
    public BootstrapSwarmState bootstrapSwarmState() { return bootstrapSwarmManager.state(); }

    /** Busca um NodeId no overlay usando apenas conexoes BitTorrent ja estabelecidas. */
    public CompletionStage<RouteSearchResult> findLuffyNode(LuffyNodeId targetNodeId, String contentInfoHash) {
        return routeExtension.findNode(targetNodeId, contentInfoHash);
    }

    /**
     * Fallback chamado somente depois de esgotadas as tentativas diretas para o
     * peer identificado. A busca lf_route escolhe Z e o controle lf_rendezvous
     * usa a rota vencedora; dados BitTorrent nao passam pela rota.
     */
    public CompletionStage<Optional<RendezvousSession>> requestOverlayRendezvous(
            LuffyNodeId targetNodeId, String contentInfoHash) {
        Objects.requireNonNull(targetNodeId, "targetNodeId");
        Objects.requireNonNull(contentInfoHash, "contentInfoHash");
        TorrentId contentTorrentId = TorrentId.fromBytes(hexBytes(contentInfoHash));
        FindNodeService.RouteSearch search = routeExtension.startFindNode(targetNodeId, contentInfoHash);
        return search.result().thenApply(result -> {
            if (!(result instanceof RouteSearchResult.NodeFound found)) {
                diagnostics.log(P2pDiagnostics.Layer.HOLEPUNCH, "[LF_RENDEZVOUS] nao iniciado: lf_route nao encontrou coordenador para "
                        + targetNodeId.asText() + "; resultado=" + result.getClass().getSimpleName() + ".");
                return Optional.empty();
            }
            if (found.rendezvousNodeId().equals(nodeIdentity.nodeId())) {
                diagnostics.log(P2pDiagnostics.Layer.HOLEPUNCH, "[LF_RENDEZVOUS] dispensado: o destino ja esta conectado localmente.");
                return Optional.empty();
            }
            Optional<RendezvousSession> session = rendezvousExtension.request(search.requestId(), targetNodeId,
                    found.rendezvousNodeId(), contentTorrentId);
            if (session.isEmpty()) {
                diagnostics.log(P2pDiagnostics.Layer.HOLEPUNCH, "[LF_RENDEZVOUS] nao iniciado: endpoint uTP confirmado ou rota vencedora indisponivel.");
            }
            return session;
        });
    }

    /**
     * Reentra em um magnet já conhecido sem baixar arquivos. A sessão busca peers por
     * DHT/PEX e pode manter handshakes, porém todos os arquivos ficam em SKIP: este
     * Luffy nunca anuncia peças que não possui.
     */
    public void rejoinSwarmAssist(MagnetLink magnet) {
        if (magnet == null) return;
        String infoHash = magnet.infoHash();
        if (seedingSessions.containsKey(infoHash)) {
            diagnostics.log(P2pDiagnostics.Layer.DISCOVERY, "SWARM ASSIST IGNORADO: infoHash=" + infoHash
                    + "; o conteúdo está disponível localmente e pertence somente a SEEDING SWARMS.");
            return;
        }
        swarmAssistMagnets.put(infoHash, magnet);
        refreshSwarmAssistStats(infoHash);
        registerMagnetPeerHint(magnet);
        SwarmAssistResourceGovernor.AssistPermission permission = swarmAssistResourceGovernor.assistPermission();
        if (permission != SwarmAssistResourceGovernor.AssistPermission.PERMITTED) {
            diagnostics.log(P2pDiagnostics.Layer.CONNECTIVITY, "SWARM ASSIST DEFERRED: infoHash=" + infoHash
                    + "; motivo=" + permission + "; a atividade de primeiro plano do usuário tem prioridade.");
            return;
        }
        if (sessions.containsKey(infoHash)) {
            diagnostics.log(P2pDiagnostics.Layer.DISCOVERY, "SWARM ASSIST: infoHash=" + infoHash
                    + "; já possui sessão ativa (seed/download); renovando descoberta sem criar outra sessão.");
            requestDhtLookup(infoHash, "reentrada automática de swarm ativo");
            return;
        }
        BtClient existing = swarmAssistSessions.get(infoHash);
        if (existing != null && existing.isStarted()) {
            ensureSwarmAssistMaintenance(infoHash);
            diagnostics.log(P2pDiagnostics.Layer.DISCOVERY, "SWARM ASSIST: infoHash=" + infoHash
                    + "; participação passiva já está ativa.");
            return;
        }
        if (existing != null) swarmAssistSessions.remove(infoHash, existing);
        swarmAssistSessions.computeIfAbsent(infoHash, ignored -> startSwarmAssist(magnet));
        ensureSwarmAssistMaintenance(infoHash);
    }

    /** Restauração do disco: a consulta abaixo é a única fonte nova da população persistida. */
    public CompletableFuture<Integer> restoreSwarmAssist(MagnetLink magnet) {
        if (magnet == null) return CompletableFuture.completedFuture(0);
        swarmAssistInitialLookupSuppressed.add(magnet.infoHash());
        rejoinSwarmAssist(magnet);
        return scheduleSwarmAssistDhtLookup(magnet.infoHash(), "restauração gradual de Swarm Assist");
    }

    /** Remove apenas a presença passiva; uma sessão de seed/download em curso continua intocada. */
    public void removeFromSwarmAssist(String infoHash) {
        if (infoHash == null || infoHash.isBlank()) return;
        stopSwarmAssistMaintenance(infoHash);
        swarmAssistMagnets.remove(infoHash);
        swarmAssistLastPexAt.remove(infoHash);
        swarmAssistDhtScheduler.cancel(infoHash);
        swarmAssistStats.remove(infoHash);
        BtClient passive = swarmAssistSessions.remove(infoHash);
        if (passive != null) {
            passive.stop();
            diagnostics.log(P2pDiagnostics.Layer.DISCOVERY, "SWARM ASSIST REMOVIDO: infoHash=" + infoHash
                    + "; vaga passiva liberada sem interromper seeds ou downloads.");
        }
    }

    /** Faz uma consulta DHT completa antes de a política decidir se o swarm merece uma das 25 vagas. */
    public CompletableFuture<Integer> inspectSwarmPeerCount(MagnetLink magnet) {
        if (magnet == null) return CompletableFuture.completedFuture(0);
        registerMagnetPeerHint(magnet);
        return inspectSwarmPeerCountNow(magnet.infoHash(), "avaliação da lista de swarms");
    }

    /** Consulta imediata usada somente por ação do usuário ou decisão de substituição com TTL vencido. */
    private CompletableFuture<Integer> inspectSwarmPeerCountNow(String infoHash, String purpose) {
        var families = activeDhtFamilies();
        if (families.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "nenhuma DHT ativa para concluir a observação de peers"));
        }
        CompletableFuture<?>[] lookups = families.stream()
                .map(ipv6 -> requestDhtLookup(infoHash, purpose, ipv6))
                .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(lookups).thenApply(ignored -> {
            int observed = refreshSwarmAssistStats(infoHash).estimatedPeerCount();
            diagnostics.log(P2pDiagnostics.Layer.DISCOVERY, "SWARM ASSIST AVALIADO: infoHash=" + infoHash
                    + "; finalidade=" + purpose + "; peers DHT/PEX observados=" + observed + ".");
            return observed;
        });
    }

    /** Cria um enxame BitTorrent exclusivo; não usa servidor, tracker ou protocolo auxiliar. */
    public DiagnosticTestSource createAndSeedDiagnosticTest() {
        return createAndSeedDiagnosticTest(P2pDiagnosticScenario.DIRECT_IPV4);
    }

    /** Máquina A: semeia o mesmo teste.txt usado em todos os cenários da matriz. */
    public DiagnosticTestSource createAndSeedDiagnosticTest(P2pDiagnosticScenario scenario) {
        P2pDiagnosticScenario selected = scenario == null ? P2pDiagnosticScenario.DIRECT_IPV4 : scenario;
        try {
            String testId = UUID.randomUUID().toString();
            Path sourceDirectory = cacheDirectory.getParent().resolve("diagnostic-p2p").resolve("source").resolve(testId);
            Files.createDirectories(sourceDirectory);
            Path testFile = sourceDirectory.resolve("teste.txt");
            Files.writeString(testFile, "OLA LUFFY\n", StandardCharsets.UTF_8);
            var published = new TorrentMetainfoGenerator().publish(testFile, sourceDirectory.resolve("torrent"));
            MagnetLink diagnosticMagnet = diagnosticMagnet(published.magnet(), selected);
            String magnetText = toUri(diagnosticMagnet);
            diagnostics.log("TESTE BITTORRENT A: arquivo criado em " + testFile + "; conteúdo=OLA LUFFY.");
            diagnostics.log("MATRIZ DE TESTE: cenário=" + selected.label() + "; esperado=" + selected.expected()
                    + "; instrução=" + selected.guidance());
            diagnostics.log("TESTE BITTORRENT A: infoHash=" + published.magnet().infoHash() + "; magnet=" + magnetText + ".");
            attachTorrentDiagnostics(published.magnet().infoHash(), "TESTE BITTORRENT A");
            seed(published.torrentFile(), sourceDirectory, testFile, published.magnet().infoHash());
            checkDhtReachability();
            return new DiagnosticTestSource(magnetText, published.magnet().infoHash(), testFile, selected);
        } catch (Exception error) {
            throw new IllegalStateException("Não foi possível criar o teste BitTorrent: " + message(error), error);
        }
    }

    /** B baixa teste.txt usando o magnet de A e a DHT do mesmo motor BitTorrent. */
    public void downloadDiagnosticTest(String rawMagnet, Consumer<DiagnosticTestResult> onCompleted) {
        downloadDiagnosticTest(rawMagnet, P2pDiagnosticScenario.DIRECT_IPV4, onCompleted);
    }

    /** Máquina B: termina em sucesso ou em diagnóstico explícito, nunca em busca infinita. */
    public void downloadDiagnosticTest(String rawMagnet, P2pDiagnosticScenario scenario, Consumer<DiagnosticTestResult> onCompleted) {
        P2pDiagnosticScenario selected = scenario == null ? P2pDiagnosticScenario.DIRECT_IPV4 : scenario;
        MagnetLink magnet = MagnetLink.parse(rawMagnet == null ? "" : rawMagnet.trim());
        beginUserDownload(magnet.infoHash(), ConnectionRole.DOWNLOAD);
        Path target = cacheDirectory.getParent().resolve("diagnostic-p2p").resolve("received").resolve(magnet.infoHash());
        pauseSwarmAssist(magnet.infoHash());
        BtClient previous = sessions.get(magnet.infoHash());
        if (previous != null && sessions.remove(magnet.infoHash(), previous)) previous.stop();
        diagnostics.log("TESTE BITTORRENT B: lookup iniciado; cenário=" + selected.label() + "; esperado=" + selected.expected()
                + "; infoHash=" + magnet.infoHash() + "; destino=" + target + ".");
        attachTorrentDiagnostics(magnet.infoHash(), "TESTE BITTORRENT B");
        AtomicBoolean finished = new AtomicBoolean();
        AtomicReference<BtClient> clientReference = new AtomicReference<>();
        var builder = Bt.client(runtime()).storage(new FileSystemStorage(target)).magnet(toUri(magnet)).sequentialSelector()
                .afterTorrentFetched(torrent -> diagnostics.log("TESTE BITTORRENT B: metadados recebidos; nome=" + torrent.getName()
                        + "; arquivos=" + torrent.getFiles().size() + "; peças=" + Math.ceilDiv(torrent.getSize(), torrent.getChunkSize()) + "."))
                .afterDownloaded(torrent -> {
                    if (!finished.compareAndSet(false, true)) return;
                    Path received = target.resolve(torrent.getName());
                    try {
                        String content = Files.readString(received, StandardCharsets.UTF_8);
                        boolean valid = content.startsWith("OLA LUFFY");
                        diagnostics.log(P2pDiagnostics.Layer.DOWNLOAD, "teste.txt concluído: arquivo=" + received + "; bytes="
                                + Files.size(received) + "; conteúdo=" + (valid ? "OLA LUFFY" : "inválido") + ".");
                        diagnostics.log("TESTE BITTORRENT B: transferência concluída; arquivo=" + received + "; bytes=" + Files.size(received)
                                + "; conteúdo OLA LUFFY=" + valid + ".");
                        String outcome = valid ? diagnosticSuccessOutcome(magnet.infoHash(), selected) : "DOWNLOAD INVALID";
                        if (onCompleted != null) onCompleted.accept(new DiagnosticTestResult(received, valid, content, outcome,
                                valid ? "teste.txt recebido e validado" : "o conteúdo recebido não começa com OLA LUFFY"));
                    } catch (Exception error) {
                        diagnostics.log("TESTE BITTORRENT B: download concluiu, mas a verificação do arquivo falhou: " + message(error));
                        if (onCompleted != null) onCompleted.accept(new DiagnosticTestResult(received, false, "", "DOWNLOAD INVALID", message(error)));
                    }
                    completeUserDownload(magnet.infoHash());
                }).stopWhenDownloaded();
        BtClient client = builder.build();
        clientReference.set(client);
        sessions.put(magnet.infoHash(), client);
        startClient("Download Teste", "teste.txt", magnet.infoHash(), client, () -> { });
        registerMagnetPeerHint(magnet);
        scheduleDiagnosticDeadline(magnet.infoHash(), target, clientReference, finished, onCompleted);
        checkDhtReachability();
    }

    /** No cenário LAN, x.pe é uma pista explícita dada pelo usuário no magnet; a DHT continua rejeitando IP privado. */
    private MagnetLink diagnosticMagnet(MagnetLink original, P2pDiagnosticScenario scenario) {
        if (!scenario.lanPeerHint()) return original;
        Inet4Address local = preferredIpv4Address();
        if (local == null || !isPrivateLanIpv4(local)) {
            diagnostics.log("MATRIZ LAN DIRECT: não foi encontrado IPv4 privado local para incluir como x.pe no magnet.");
            return original;
        }
        Map<String, String> parameters = new LinkedHashMap<>(original.parameters());
        parameters.put("x.pe", local.getHostAddress() + ":" + connectivity.torrentListeningPort());
        diagnostics.log("MATRIZ LAN DIRECT: magnet inclui x.pe explícito " + parameters.get("x.pe") + ".");
        return new MagnetLink(original.infoHash(), original.displayName(), parameters, original.trackers());
    }

    private void scheduleDiagnosticDeadline(String infoHash, Path target, AtomicReference<BtClient> clientReference,
                                            AtomicBoolean finished, Consumer<DiagnosticTestResult> onCompleted) {
        Thread.startVirtualThread(() -> {
            try { Thread.sleep(DIAGNOSTIC_TEST_DEADLINE.toMillis()); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); return; }
            if (!finished.compareAndSet(false, true)) return;
            BtClient client = clientReference.get();
            if (client != null && sessions.remove(infoHash, client)) client.stop();
            completeUserDownload(infoHash);
            String reason = diagnosticFailureReason(infoHash);
            diagnostics.log(P2pDiagnostics.Layer.RESULT, "PEER UNREACHABLE: infoHash=" + infoHash + "; prazo="
                    + DIAGNOSTIC_TEST_DEADLINE.toSeconds() + "s; motivo=" + reason + ".");
            statusListener.accept("Teste teste.txt encerrou: UNREACHABLE — " + reason);
            if (onCompleted != null) onCompleted.accept(new DiagnosticTestResult(target.resolve("teste.txt"), false, "", "UNREACHABLE", reason));
        });
    }

    private String diagnosticFailureReason(String infoHash) {
        List<PeerConnectivityManager.PeerState> peers = peerConnectivity.peersFor(infoHash);
        if (peers.isEmpty()) return "nenhum peer foi retornado pela DHT e não há x.pe alcançável no magnet";
        String unavailable = peers.stream().filter(peer -> peer.connection() == PeerConnectivityManager.ConnectionState.UNREACHABLE)
                .map(PeerConnectivityManager.PeerState::failureReason).filter(reason -> !reason.isBlank()).findFirst().orElse("");
        if (!unavailable.isBlank()) return unavailable;
        String failure = peers.stream().map(PeerConnectivityManager.PeerState::lastSocketAttempt).filter(java.util.Objects::nonNull)
                .filter(attempt -> attempt.failure() != PeerConnectivityManager.SocketFailure.NONE)
                .map(attempt -> attempt.failure().name()).findFirst().orElse("");
        return failure.isBlank() ? "peers foram descobertos, mas não houve handshake BitTorrent dentro do prazo" : "falha de conexão " + failure;
    }

    private String diagnosticSuccessOutcome(String infoHash, P2pDiagnosticScenario scenario) {
        List<PeerConnectivityManager.PeerState> peers = peerConnectivity.peersFor(infoHash);
        boolean utpHolePunch = peers.stream().anyMatch(peer -> peer.endpoint().transport() == PeerConnectivityManager.Transport.UTP
                && peer.connection() == PeerConnectivityManager.ConnectionState.CONNECTED
                && peer.strategy() == PeerConnectivityManager.Strategy.HOLE_PUNCHING);
        if (utpHolePunch) return "DIRECT P2P VIA UTP HOLE PUNCH";
        if (scenario == P2pDiagnosticScenario.LAN_DIRECT) return "LAN DIRECT";
        return "DIRECT IPV4";
    }

    private boolean isPrivateLanIpv4(Inet4Address address) {
        byte[] bytes = address.getAddress(); int first = bytes[0] & 0xff, second = bytes[1] & 0xff;
        return first == 10 || first == 192 && second == 168 || first == 172 && second >= 16 && second <= 31;
    }

    @Override public StreamingSession open(MagnetLink magnet, WatchMode mode) {
        return open(magnet, mode, ignored -> { });
    }
    @Override public StreamingSession open(MagnetLink magnet, WatchMode mode, Consumer<TorrentContent> onMetadata) {
        return open(magnet, mode, null, onMetadata);
    }
    @Override public StreamingSession open(MagnetLink magnet, WatchMode mode, String selectedRelativePath, Consumer<TorrentContent> onMetadata) {
        if (mode == WatchMode.SHARE) documentsLuffyDirectory();
        Path localSource = publishedRoots.get(magnet.infoHash());
        if (localSource != null) onMetadata.accept(new TorrentContent(localSource, listFiles(localSource)));
        else restartDownload(magnet, mode, selectedRelativePath, onMetadata);
        return new StreamingSession(magnet.infoHash(), magnet.displayName().orElse("Vídeo sem título"), mode,
                StreamingSession.SessionStatus.BUFFERING, 0, streamStartupPieces, Instant.now());
    }

    /** A second "Abrir magnet" is an explicit retry, not a no-op against a stalled lookup. */
    private void restartDownload(MagnetLink magnet, WatchMode mode, String selectedRelativePath, Consumer<TorrentContent> onMetadata) {
        beginUserDownload(magnet.infoHash(), mode == WatchMode.TEMPORARY ? ConnectionRole.STREAM : ConnectionRole.DOWNLOAD);
        if (mode == WatchMode.SHARE) removeFromSwarmAssist(magnet.infoHash());
        else pauseSwarmAssist(magnet.infoHash());
        BtClient previous = sessions.get(magnet.infoHash());
        if (mode == WatchMode.TEMPORARY) {
            diagnostics.log(P2pDiagnostics.Layer.DOWNLOAD, "STREAM DIAGNOSTIC RESTART: infoHash=" + magnet.infoHash()
                    + "; selected=\"" + StreamingFileSelection.normalize(selectedRelativePath) + "\"; previousSession="
                    + (previous == null ? "absent" : "present") + "; previousStarted="
                    + (previous != null && previous.isStarted()) + ".");
        }
        // A sessão inicial de streaming mantém todos os arquivos em SKIP para obter
        // somente os metadados. O bt-core não permite promover um arquivo que foi
        // inicialmente SKIP. É essencial aguardar o cliente antigo terminar antes
        // de criar a sessão selecionada, ou a runtime reutiliza o estado de SKIP e
        // conclui o novo cliente sem solicitar nenhuma peça.
        if (mode == WatchMode.TEMPORARY && selectedRelativePath != null && previous != null
                && sessions.remove(magnet.infoHash(), previous)) {
            scheduleSelectedStreamingRestart(magnet, selectedRelativePath, onMetadata, previous);
            return;
        }
        if (previous != null && sessions.remove(magnet.infoHash(), previous)) {
            previous.stop();
            shutdownMetadataPreviewAfterClient(magnet.infoHash(), previous);
            statusListener.accept("Reiniciando a busca de peers para “" + magnet.displayName().orElse("vídeo") + "”…");
        }
        peerConnectivity.allowExplicitRetry(magnet.infoHash());
        diagnostics.log("DHT LOOKUP solicitado: infoHash=" + magnet.infoHash() + ", nome=\"" + magnet.displayName().orElse("sem nome") + "\".");
        holePunchAgent.allowExplicitRetry(magnet.infoHash());
        sessions.computeIfAbsent(magnet.infoHash(), ignored -> startDownload(magnet, mode, selectedRelativePath, onMetadata));
        registerMagnetPeerHint(magnet);
    }

    /**
     * A troca de metadados para o arquivo escolhido precisa respeitar o lifecycle
     * da mesma BtRuntime. Criar o segundo BtClient antes da conclusão do primeiro
     * preserva o mapa de peças SKIP do cliente anterior dentro do bt-core.
     */
    private void scheduleSelectedStreamingRestart(MagnetLink magnet, String selectedRelativePath,
                                                   Consumer<TorrentContent> onMetadata, BtClient previous) {
        CompletableFuture<?> completion = clientProcessCompletions.get(previous);
        BtRuntime metadataPreviewRuntime = metadataPreviewRuntimes.remove(magnet.infoHash());
        diagnostics.log(P2pDiagnostics.Layer.DOWNLOAD, "STREAM SESSION REPLACEMENT WAIT: infoHash=" + magnet.infoHash()
                + "; selected=\"" + StreamingFileSelection.normalize(selectedRelativePath) + "\"; completionKnown="
                + (completion != null) + ".");
        statusListener.accept("Preparando o arquivo escolhido para streaming…");
        peerConnectivity.allowExplicitRetry(magnet.infoHash());
        holePunchAgent.allowExplicitRetry(magnet.infoHash());
        previous.stop();

        Runnable startReplacement = () -> {
            if (shuttingDown.get()) return;
            shutdownRuntime(metadataPreviewRuntime);
            BtClient replacement = startDownload(magnet, WatchMode.TEMPORARY, selectedRelativePath, onMetadata);
            BtClient competing = sessions.putIfAbsent(magnet.infoHash(), replacement);
            if (competing != null) {
                replacement.stop();
                diagnostics.log(P2pDiagnostics.Layer.DOWNLOAD, "STREAM SESSION REPLACEMENT CANCELLED: infoHash="
                        + magnet.infoHash() + "; uma sessão mais recente já está ativa.");
                return;
            }
            diagnostics.log(P2pDiagnostics.Layer.DOWNLOAD, "STREAM SESSION REPLACEMENT STARTED: infoHash="
                    + magnet.infoHash() + "; selected=\"" + StreamingFileSelection.normalize(selectedRelativePath) + "\".");
            registerMagnetPeerHint(magnet);
        };
        if (completion == null) {
            Thread.startVirtualThread(startReplacement);
        } else {
            completion.handle((ignored, error) -> null)
                    .thenRun(() -> Thread.startVirtualThread(startReplacement));
        }
    }

    /** Encerra a runtime de prévia somente depois de o cliente que a usa finalizar. */
    private void shutdownMetadataPreviewAfterClient(String infoHash, BtClient client) {
        BtRuntime preview = metadataPreviewRuntimes.remove(infoHash);
        if (preview == null) return;
        CompletableFuture<?> completion = clientProcessCompletions.get(client);
        if (completion == null) shutdownRuntime(preview);
        else completion.handle((ignored, error) -> null).thenRun(() -> shutdownRuntime(preview));
    }

    private void shutdownRuntime(BtRuntime active) {
        if (active == null) return;
        try { active.shutdown(); }
        catch (RuntimeException error) {
            diagnostics.log(P2pDiagnostics.Layer.DOWNLOAD, "STREAM METADATA RUNTIME SHUTDOWN FAILED: " + message(error));
        }
    }

    /** Mantém o arquivo original como seed enquanto a aplicação estiver aberta. */
    public void seed(Path torrentFile, Path storageDirectory, Path contentRoot, String infoHash) {
        removeFromSwarmAssist(infoHash);
        ConnectivityProfile.DhtAnnouncement announcement = connectivity.dhtAnnouncement();
        if (announcement.shouldAnnounce()) {
            ConnectivityProfile.PublicPeerEndpoint endpoint = announcement.endpoint().orElseThrow();
            diagnostics.log("DHT ANNOUNCE solicitado: infoHash=" + infoHash + ", pasta=\"" + contentRoot.getFileName()
                    + "\"; endpoint público=" + endpoint.address().getHostAddress() + ":" + endpoint.port()
                    + "; porta TCP local=" + connectivity.torrentListeningPort() + ".");
        } else {
            diagnostics.log("DHT ANNOUNCE SUPPRESSED: infoHash=" + infoHash + ", pasta=\"" + contentRoot.getFileName()
                    + "\"; estado=OUTBOUND_ONLY_FIREWALLED; motivo=" + announcement.reason() + ".");
        }
        BtClient seedClient = sessions.computeIfAbsent(infoHash, ignored -> {
            try {
                BtClient client = Bt.client(runtime()).storage(new FileSystemStorage(storageDirectory))
                        .torrent(torrentFile.toUri().toURL()).build();
                startClient("Seed", contentRoot.getFileName().toString(), infoHash, client, () -> publishedRoots.put(infoHash, contentRoot));
                return client;
            } catch (MalformedURLException e) { throw new IllegalArgumentException("Arquivo torrent inválido", e); }
        });
        seedingSessions.put(infoHash, seedClient);
        userTransferRoles.remove(infoHash);
        diagnostics.log(P2pDiagnostics.Layer.BITTORRENT, "SEEDING SWARM REGISTRADO: infoHash=" + infoHash
                + "; este swarm não ocupa uma vaga de Swarm Assist.");
        seedOnIpv6WhenAvailable(torrentFile, storageDirectory, contentRoot, infoHash);
    }

    private BtClient startDownload(MagnetLink magnet, WatchMode mode, String selectedRelativePath, Consumer<TorrentContent> onMetadata) {
        Path target = mode == WatchMode.SHARE ? documentsLuffyDirectory() : temporaryDirectory();
        boolean metadataOnly = mode == WatchMode.TEMPORARY && selectedRelativePath == null;
        BtRuntime activeRuntime = metadataOnly ? metadataPreviewRuntime(magnet.infoHash()) : runtime();
        attachTorrentDiagnostics(activeRuntime, magnet.infoHash(), "DOWNLOAD");
        AtomicInteger selectorCalls = new AtomicInteger();
        AtomicInteger selectedFileCalls = new AtomicInteger();
        if (mode == WatchMode.TEMPORARY) {
            diagnostics.log(P2pDiagnostics.Layer.DOWNLOAD, "STREAM DIAGNOSTIC CREATE: infoHash=" + magnet.infoHash()
                    + "; metadataOnly=" + metadataOnly + "; selected=\""
                    + StreamingFileSelection.normalize(selectedRelativePath) + "\"; target=" + target + ".");
        }
        if (!metadataOnly) {
            transferSnapshots.remove(magnet.infoHash().toLowerCase());
            streamingPiecePrefixes.clear(magnet.infoHash());
            clearStreamingMediaFiles(magnet.infoHash());
            streamingPieceSelectors.remove(normalizeInfoHash(magnet.infoHash()));
            streamingPriorityWindows.remove(normalizeInfoHash(magnet.infoHash()));
        }
        AtomicReference<BtClient> clientReference = new AtomicReference<>();
        PrioritizedPieceSelector streamingSelector = mode == WatchMode.TEMPORARY && selectedRelativePath != null
                ? new PrioritizedPieceSelector(SequentialSelector.sequential()) : null;
        if (streamingSelector != null) streamingPieceSelectors.put(normalizeInfoHash(magnet.infoHash()), streamingSelector);
        var builder = Bt.client(activeRuntime).storage(new FileSystemStorage(target)).magnet(toUri(magnet));
        if (streamingSelector == null) builder.sequentialSelector();
        else builder.selector(streamingSelector);
        builder.afterTorrentFetched(torrent -> {
                    TorrentContent content = StreamingTorrentContentLayout.resolve(target, torrent.getName(),
                            torrent.getFiles().stream().map(file -> file.getPathElements()).toList());
                    Path folder = content.folder();
                    List<Path> files = content.files();
                    if (mode == WatchMode.TEMPORARY && selectedRelativePath != null) {
                        boolean found = torrent.getFiles().stream().anyMatch(file -> StreamingFileSelection.matches(selectedRelativePath, file.getPathElements()));
                        String torrentPaths = torrent.getFiles().stream().limit(8)
                                .map(file -> StreamingFileSelection.normalize(String.join("/", file.getPathElements())))
                                .reduce((left, right) -> left + " | " + right).orElse("<nenhum>");
                        diagnostics.log(P2pDiagnostics.Layer.DOWNLOAD, "STREAM FILE SELECTION: requested=\""
                                + StreamingFileSelection.normalize(selectedRelativePath) + "\"; matched=" + found
                                + "; arquivos=" + torrent.getFiles().size() + "; paths=" + torrentPaths + ".");
                        if (!found) statusListener.accept("O vídeo escolhido não foi encontrado nos metadados deste torrent.");
                    }
                    if (mode == WatchMode.TEMPORARY && selectedRelativePath != null) {
                        registerStreamingMediaFile(magnet.infoHash(), torrent, content, selectedRelativePath);
                    }
                    onMetadata.accept(content);
                });
        if (mode == WatchMode.TEMPORARY) {
            // Primeiro buscamos apenas os metadados e mostramos a lista. Depois do clique
            // do usuário uma nova sessão prioriza exclusivamente o arquivo escolhido.
            builder.fileSelector(file -> {
                boolean selected = StreamingFileSelection.matches(selectedRelativePath, file.getPathElements());
                bt.torrent.fileselector.FilePriority priority = selected
                        ? bt.torrent.fileselector.FilePriority.HIGH_PRIORITY
                        : bt.torrent.fileselector.FilePriority.SKIP;
                int invocation = selectorCalls.incrementAndGet();
                if (selected) selectedFileCalls.incrementAndGet();
                if (invocation <= 32) {
                    diagnostics.log(P2pDiagnostics.Layer.DOWNLOAD, "STREAM SELECTOR DECISION: infoHash=" + magnet.infoHash()
                            + "; invocation=" + invocation + "; requested=\""
                            + StreamingFileSelection.normalize(selectedRelativePath) + "\"; candidate=\""
                            + StreamingFileSelection.normalize(String.join("/", file.getPathElements())) + "\"; pathElements="
                            + file.getPathElements().size() + "; selected=" + selected + "; priority=" + priority + ".");
                } else if (invocation == 33) {
                    diagnostics.log(P2pDiagnostics.Layer.DOWNLOAD, "STREAM SELECTOR DECISION: demais decisões foram suprimidas após 32 arquivos.");
                }
                return priority;
            });
            if (selectedRelativePath != null) {
                builder.afterFilesChosen(() -> diagnostics.log(P2pDiagnostics.Layer.DOWNLOAD,
                        "STREAM FILES CHOSEN CALLBACK: infoHash=" + magnet.infoHash() + "; selected=\""
                                + StreamingFileSelection.normalize(selectedRelativePath) + "\"; selectorCalls="
                                + selectorCalls.get() + "; selectedCalls=" + selectedFileCalls.get() + "."));
            }
        }
        if (mode == WatchMode.TEMPORARY && selectedRelativePath != null) {
            // O player pode ainda estar lendo o buffer quando o BitTorrent marca o
            // arquivo como concluído. A sessão só deve ser liberada quando o usuário
            // encerrar a reprodução, nunca automaticamente neste callback.
            builder.afterDownloaded(ignored -> diagnostics.log(P2pDiagnostics.Layer.DOWNLOAD,
                    "STREAM DOWNLOAD COMPLETE: infoHash=" + magnet.infoHash() + "; sessão mantida para reprodução."));
        } else if (mode == WatchMode.SHARE) {
            builder.afterDownloaded(ignored -> markDownloadedSwarmAsSeed(magnet, clientReference.get()));
        }
        BtClient client = builder.build();
        clientReference.set(client);
        startClient(metadataOnly ? "Metadata" : "Download", magnet.displayName().orElse("vídeo"), magnet.infoHash(), client, () -> { });
        if (metadataOnly) connectKnownTcpPeersToMetadataPreview(magnet.infoHash(), activeRuntime);
        reportMissingMetadataAfterDelay(magnet, client);
        return client;
    }

    private void registerStreamingMediaFile(String infoHash, bt.metainfo.Torrent torrent,
                                            TorrentContent content, String selectedRelativePath) {
        long offset = 0;
        List<bt.metainfo.TorrentFile> files = torrent.getFiles();
        List<Path> resolvedFiles = content.files();
        for (int index = 0; index < files.size(); index++) {
            bt.metainfo.TorrentFile torrentFile = files.get(index);
            long length = torrentFile.getSize();
            if (StreamingFileSelection.matches(selectedRelativePath, torrentFile.getPathElements())
                    && index < resolvedFiles.size() && length > 0 && torrent.getChunkSize() > 0) {
                Path resolved = resolvedFiles.get(index);
                streamingMediaFiles.put(streamingMediaKey(infoHash, resolved), new StreamingMediaFile(
                        torrent.getSize(), offset, length, torrent.getChunkSize()));
                return;
            }
            offset += length;
        }
    }

    private void clearStreamingMediaFiles(String infoHash) {
        if (infoHash == null) return;
        String prefix = normalizeInfoHash(infoHash) + "|";
        streamingMediaFiles.keySet().removeIf(key -> key.startsWith(prefix));
    }

    private static String streamingMediaKey(String infoHash, Path file) {
        return normalizeInfoHash(infoHash) + "|" + file.toAbsolutePath().normalize();
    }

    private static String normalizeInfoHash(String infoHash) {
        return infoHash.toLowerCase(java.util.Locale.ROOT);
    }

    private static long saturatedMultiply(long left, long right) {
        if (left <= 0 || right <= 0) return 0;
        return left > Long.MAX_VALUE / right ? Long.MAX_VALUE : left * right;
    }

    private static int totalPieces(StreamingMediaFile mediaFile) {
        long total = Math.ceilDiv(mediaFile.torrentLengthBytes(), mediaFile.pieceLengthBytes());
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    /**
     * O bt-core mantém o estado de arquivos SKIP por TorrentId dentro de uma
     * BtRuntime. A prévia vive separadamente para que a sessão principal possa
     * selecionar o vídeo depois de os metadados chegarem.
     */
    private BtRuntime metadataPreviewRuntime(String infoHash) {
        BtRuntime current = metadataPreviewRuntimes.get(infoHash);
        if (current != null) return current;
        BtRuntime created = BtRuntime.builder(metadataPreviewNetworkConfig()).disableAutomaticShutdown().build();
        BtRuntime existing = metadataPreviewRuntimes.putIfAbsent(infoHash, created);
        if (existing != null) {
            shutdownRuntime(created);
            return existing;
        }
        diagnostics.log(P2pDiagnostics.Layer.DOWNLOAD, "STREAM METADATA RUNTIME STARTED: infoHash=" + infoHash
                + "; finalidade=lista-de-arquivos; transferência de peças=SKIP.");
        return created;
    }

    /** Repassa apenas endpoints TCP já descobertos à prévia, que não participa da DHT nem anuncia conteúdo. */
    private void connectKnownTcpPeersToMetadataPreview(String infoHash, BtRuntime previewRuntime) {
        TorrentId torrentId = TorrentId.fromBytes(hexBytes(infoHash));
        int forwarded = 0;
        for (PeerConnectivityManager.PeerState state : peerConnectivity.peersFor(infoHash)) {
            if (state.endpoint().transport() != PeerConnectivityManager.Transport.TCP) continue;
            try {
                previewRuntime.service(IPeerRegistry.class).addPeer(torrentId,
                        InetPeer.build(state.endpoint().address(), state.endpoint().port()));
                forwarded++;
            } catch (RuntimeException ignored) {
                // Uma nova observação DHT/PEX poderá reenviar este endpoint.
            }
        }
        if (forwarded > 0) diagnostics.log(P2pDiagnostics.Layer.DOWNLOAD,
                "STREAM METADATA PEERS FORWARDED: infoHash=" + infoHash + "; tcpPeers=" + forwarded + ".");
    }

    /** O download compartilhado termina como SEEDING SWARM e fica fora de Swarm Assist. */
    private void markDownloadedSwarmAsSeed(MagnetLink magnet, BtClient client) {
        if (client == null) return;
        completeUserDownload(magnet.infoHash());
        removeFromSwarmAssist(magnet.infoHash());
        seedingSessions.put(magnet.infoHash(), client);
        userTransferRoles.put(magnet.infoHash(), ConnectionRole.SEED);
        diagnostics.log(P2pDiagnostics.Layer.BITTORRENT, "SEEDING SWARM REGISTRADO: infoHash=" + magnet.infoHash()
                + "; download concluído e conteúdo local continuará sendo semeado fora de Swarm Assist.");
    }

    /** Uma sessão temporária concluída pode, a partir deste ponto, ser candidata a Swarm Assist. */
    private void completeTemporaryWatchSession(MagnetLink magnet, BtClient client) {
        if (client == null || !sessions.remove(magnet.infoHash(), client)) return;
        completeUserDownload(magnet.infoHash());
        diagnostics.log(P2pDiagnostics.Layer.DISCOVERY, "SWARM ASSIST CANDIDATO: infoHash=" + magnet.infoHash()
                + "; a sessão temporária foi encerrada; nenhuma vaga foi ocupada antes deste momento.");
        temporaryWatchCompletedListener.accept(magnet);
    }

    /** Cria a sessão persistente de presença no swarm, deliberadamente sem selecionar arquivos. */
    private BtClient startSwarmAssist(MagnetLink magnet) {
        Path storage = cacheDirectory.resolve("swarm-assist").resolve(magnet.infoHash());
        attachTorrentDiagnostics(magnet.infoHash(), "SWARM ASSIST");
        BtClient client = Bt.client(runtime()).storage(new FileSystemStorage(storage)).magnet(toUri(magnet))
                .fileSelector(file -> bt.torrent.fileselector.FilePriority.SKIP)
                .afterTorrentFetched(torrent -> diagnostics.log(P2pDiagnostics.Layer.BITTORRENT,
                        "SWARM ASSIST METADATA: infoHash=" + magnet.infoHash() + "; torrent=\"" + torrent.getName()
                                + "\"; arquivos=" + torrent.getFiles().size() + "; todas as prioridades permanecem SKIP; "
                                + "este cliente fica NOT INTERESTED e não anuncia I HAVE PIECE."))
                .build();
        startClient("Swarm Assist", magnet.displayName().orElse("swarm sem título"), magnet.infoHash(), client, () -> { });
        diagnostics.log(P2pDiagnostics.Layer.DISCOVERY, "SWARM ASSIST: infoHash=" + magnet.infoHash()
                + "; magnet restaurado; DHT/PEX e conexões BitTorrent serão mantidos sem download de conteúdo.");
        diagnostics.log(P2pDiagnostics.Layer.CONNECTIVITY, "SWARM ASSIST BUDGET: por swarm="
                + activeSwarmAssistPolicy.maximumConnectionsPerSwarm() + "; política Assist="
                + activeSwarmAssistPolicy.maximumConnectionsTotal() + "; orçamento global Assist="
                + connectionLimits().maxAssistConnections()
                + "; somente tráfego de controle. Download, streaming e seed do usuário não passam por este orçamento.");
        return client;
    }

    /**
     * A sessao oficial reutiliza a BtRuntime principal e todas as suas extensoes.
     * Todos os arquivos ficam SKIP: ela so mantem descoberta, conexoes, PEX e
     * lf_identity; nunca aparece como video, download ou seed da biblioteca.
     */
    private BootstrapSwarmManager.BootstrapSession createBootstrapSession(MagnetLink magnet) {
        if (!OfficialBootstrapSwarm.INFO_HASH.equals(magnet.infoHash())) {
            throw new IllegalArgumentException("magnet do bootstrap nao corresponde ao artefato oficial");
        }
        String infoHash = magnet.infoHash();
        attachTorrentDiagnostics(infoHash, "BOOTSTRAP OLA LUFFY");
        Path storage = cacheDirectory.resolve("bootstrap-swarm").resolve(infoHash);
        BtClient client = Bt.client(runtime()).storage(new FileSystemStorage(storage)).magnet(OfficialBootstrapSwarm.MAGNET_URI)
                .fileSelector(file -> bt.torrent.fileselector.FilePriority.SKIP)
                .afterTorrentFetched(torrent -> diagnostics.log(P2pDiagnostics.Layer.BITTORRENT,
                        "[BOOTSTRAP] metadados confirmados: torrent=\"" + torrent.getName() + "\"; arquivos="
                                + torrent.getFiles().size() + "; nenhuma peca sera solicitada."))
                .build();
        return new BtBootstrapSession(client, infoHash);
    }

    /** Uma consulta por vez e compartilhada pelos vizinhos IPv4/IPv6 ativos; nao cria protocolo paralelo. */
    private CompletableFuture<Integer> requestBootstrapNeighborDiscovery() {
        List<Boolean> families = activeDhtFamilies();
        if (families.isEmpty()) return CompletableFuture.failedFuture(
                new IllegalStateException("nenhuma DHT ativa para procurar vizinhos do bootstrap"));
        List<CompletableFuture<Integer>> lookups = families.stream()
                .map(ipv6 -> requestDhtLookup(OfficialBootstrapSwarm.INFO_HASH,
                        "manutencao limitada de vizinhos Ola Luffy", ipv6))
                .toList();
        return CompletableFuture.allOf(lookups.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> lookups.stream().mapToInt(CompletableFuture::join).sum());
    }

    /** Adaptador BtClient para o manager; ele nao cria runtime, listener ou protocolo paralelo. */
    private final class BtBootstrapSession implements BootstrapSwarmManager.BootstrapSession {
        private final BtClient client;
        private final String infoHash;
        private final AtomicBoolean startRequested = new AtomicBoolean();
        private final CompletableFuture<Void> ready = new CompletableFuture<>();

        private BtBootstrapSession(BtClient client, String infoHash) {
            this.client = java.util.Objects.requireNonNull(client, "client");
            this.infoHash = java.util.Objects.requireNonNull(infoHash, "infoHash");
        }

        @Override public java.util.concurrent.CompletionStage<Void> start() {
            if (!startRequested.compareAndSet(false, true)) return ready;
            try {
                client.startAsync(state -> { }, 1_000).whenComplete((ignored, error) -> {
                    if (error != null) ready.completeExceptionally(error);
                });
                Thread.startVirtualThread(() -> {
                    try {
                        for (int attempt = 0; attempt < 20; attempt++) {
                            if (client.isStarted()) {
                                diagnostics.log("[BOOTSTRAP] sessao BtClient iniciada: infoHash=" + infoHash
                                        + "; PEX e lf_identity serao usados na runtime existente; a politica de vizinhos solicitara DHT de forma limitada.");
                                ready.complete(null);
                                return;
                            }
                            Thread.sleep(100);
                        }
                        ready.completeExceptionally(new IllegalStateException("BtClient bootstrap nao iniciou dentro de 2 s"));
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        ready.completeExceptionally(error);
                    } catch (RuntimeException error) {
                        ready.completeExceptionally(error);
                    }
                });
            } catch (RuntimeException error) {
                ready.completeExceptionally(error);
            }
            return ready;
        }

        @Override public boolean isActive() { return client.isStarted(); }
        @Override public void close() { client.stop(); }
    }

    /**
     * Swarm Assist precisa de conexões reais para receber PEX e participar de
     * BEP 55. Esta manutenção só consulta a DHT novamente quando há vaga no
     * pequeno orçamento de peers; uma sessão saudável continua viva sem polling.
     */
    private void ensureSwarmAssistMaintenance(String infoHash) {
        swarmAssistMaintenanceTasks.compute(infoHash, (ignored, current) -> {
            if (current != null && !current.isCancelled() && !current.isDone()) return current;
            return swarmAssistMaintenance.scheduleWithFixedDelay(() -> maintainSwarmAssist(infoHash),
                    SWARM_ASSIST_MAINTENANCE_INTERVAL.toMillis(), SWARM_ASSIST_MAINTENANCE_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
        });
    }

    private void beginUserDownload(String infoHash, ConnectionRole role) {
        userTransferRoles.put(infoHash, role == ConnectionRole.STREAM ? ConnectionRole.STREAM : ConnectionRole.DOWNLOAD);
        swarmAssistResourceGovernor.beginUserDownload(infoHash);
        diagnostics.log(P2pDiagnostics.Layer.CONNECTIVITY, "RESOURCE PRIORITY: download do usuário ativo; Swarm Assist passa a ser oportunista.");
        reconcileSwarmAssistResourcePriority();
    }

    private void completeUserDownload(String infoHash) {
        userTransferRoles.remove(infoHash);
        swarmAssistResourceGovernor.completeUserDownload(infoHash);
        diagnostics.log(P2pDiagnostics.Layer.CONNECTIVITY, "RESOURCE PRIORITY: download do usuário concluído; reavaliando Swarm Assist.");
        reconcileSwarmAssistResourcePriority();
    }

    /**
     * Pausar não remove magnets da lista persistente. Ao voltar a haver recursos,
     * as mesmas sessões Assist são recriadas e retomam DHT/PEX/BEP55 sem peças.
     */
    private synchronized void reconcileSwarmAssistResourcePriority() {
        SwarmAssistResourceGovernor.AssistPermission permission = swarmAssistResourceGovernor.assistPermission();
        if (permission != SwarmAssistResourceGovernor.AssistPermission.PERMITTED) {
            boolean stoppedAny = false;
            for (Map.Entry<String, BtClient> entry : swarmAssistSessions.entrySet()) {
                if (swarmAssistSessions.remove(entry.getKey(), entry.getValue())) {
                    stopSwarmAssistMaintenance(entry.getKey());
                    entry.getValue().stop();
                    stoppedAny = true;
                }
            }
            swarmAssistSuspendedForPriority = true;
            if (stoppedAny) diagnostics.log(P2pDiagnostics.Layer.CONNECTIVITY, "SWARM ASSIST SUSPENDED: motivo=" + permission
                    + "; nenhuma transferência de conteúdo Assist permanecerá competindo com a atividade do usuário.");
            return;
        }
        if (!swarmAssistSuspendedForPriority) return;
        swarmAssistSuspendedForPriority = false;
        List<MagnetLink> toResume = List.copyOf(swarmAssistMagnets.values());
        diagnostics.log(P2pDiagnostics.Layer.CONNECTIVITY, "SWARM ASSIST RESUMED: atividade de primeiro plano terminou; "
                + toResume.size() + " swarms poderão retomar somente tráfego de controle.");
        toResume.forEach(this::rejoinSwarmAssist);
    }

    private void requestSwarmAssistReplenishment(String infoHash, String trigger) {
        if (!isActiveSwarmAssist(infoHash)) return;
        swarmAssistReplenishTasks.compute(infoHash, (ignored, current) -> {
            if (current != null && !current.isDone() && !current.isCancelled()) return current;
            return swarmAssistMaintenance.schedule(() -> {
                swarmAssistReplenishTasks.remove(infoHash);
                maintainSwarmAssist(infoHash, trigger);
            }, SWARM_ASSIST_REPLENISH_DELAY.toMillis(), TimeUnit.MILLISECONDS);
        });
    }

    private void maintainSwarmAssist(String infoHash) { maintainSwarmAssist(infoHash, "verificação periódica"); }
    private void maintainSwarmAssist(String infoHash, String trigger) {
        SwarmAssistResourceGovernor.AssistPermission permission = swarmAssistResourceGovernor.assistPermission();
        if (permission != SwarmAssistResourceGovernor.AssistPermission.PERMITTED) {
            diagnostics.log(P2pDiagnostics.Layer.CONNECTIVITY, "SWARM ASSIST MAINTENANCE DEFERRED: infoHash=" + infoHash
                    + "; motivo=" + permission + ".");
            return;
        }
        BtClient session = swarmAssistSessions.get(infoHash);
        if (session == null || !session.isStarted()) return;
        Map<String, List<PeerConnectivityManager.PeerState>> assistStates = activeAssistPeerStates();
        int occupied = swarmAssistConnectionPolicy.occupiedInSwarm(assistStates.get(infoHash));
        int total = swarmAssistConnectionPolicy.occupiedTotal(assistStates);
        if (total >= activeSwarmAssistPolicy.maximumConnectionsTotal()) {
            diagnostics.log(P2pDiagnostics.Layer.CONNECTIVITY, "SWARM ASSIST GLOBAL LIMIT: infoHash=" + infoHash
                    + "; conexões assistenciais=" + total + "/" + activeSwarmAssistPolicy.maximumConnectionsTotal()
                    + "; trigger=" + trigger + "; download, streaming e seed permanecem sem este limite.");
            return;
        }
        if (occupied >= activeSwarmAssistPolicy.maximumConnectionsPerSwarm()) {
            SwarmAssistStats stats = refreshSwarmAssistStats(infoHash);
            diagnostics.log(P2pDiagnostics.Layer.CONNECTIVITY, "SWARM ASSIST CONNECTIONS MAINTAINED: infoHash=" + infoHash
                    + "; conexões úteis=" + occupied + "/" + activeSwarmAssistPolicy.maximumConnectionsPerSwarm()
                    + "; total assistencial=" + total + "/" + activeSwarmAssistPolicy.maximumConnectionsTotal()
                    + "; rendezvous BEP55=" + stats.usefulRendezvousPeerCount() + "; trigger=" + trigger + ".");
            logSwarmAssistConnectionState(infoHash, occupied, stats);
            return;
        }
        Instant pexObservedAt = swarmAssistLastPexAt.get(infoHash);
        if (occupied > 0 && pexObservedAt != null && !pexObservedAt.plus(SWARM_ASSIST_PEX_FRESHNESS).isBefore(Instant.now())) {
            diagnostics.log(P2pDiagnostics.Layer.DISCOVERY, "SWARM ASSIST DHT DEFERRED: infoHash=" + infoHash
                    + "; motivo=PEX recente em " + pexObservedAt + "; conexões úteis=" + occupied
                    + "; PEX continua sendo promovido pelo Connectivity Manager com deduplicação de endpoint.");
            return;
        }
        diagnostics.log(P2pDiagnostics.Layer.DISCOVERY, "SWARM ASSIST REPLENISH: infoHash=" + infoHash
                + "; conexões úteis=" + occupied + "/" + activeSwarmAssistPolicy.maximumConnectionsPerSwarm()
                + "; total assistencial=" + total + "/" + activeSwarmAssistPolicy.maximumConnectionsTotal()
                + "; trigger=" + trigger + "; próximo=DHT lookup e promoção controlada de peers.");
        scheduleSwarmAssistDhtLookup(infoHash, "manutenção de conexões Swarm Assist");
    }

    private void stopSwarmAssistMaintenance(String infoHash) {
        swarmAssistDhtScheduler.cancel(infoHash);
        ScheduledFuture<?> periodic = swarmAssistMaintenanceTasks.remove(infoHash);
        if (periodic != null) periodic.cancel(false);
        ScheduledFuture<?> replenishment = swarmAssistReplenishTasks.remove(infoHash);
        if (replenishment != null) replenishment.cancel(false);
    }

    private boolean isActiveSwarmAssist(String infoHash) {
        return swarmAssistSessions.containsKey(infoHash) && !sessions.containsKey(infoHash) && !seedingSessions.containsKey(infoHash);
    }

    private Map<String, List<PeerConnectivityManager.PeerState>> activeAssistPeerStates() {
        Map<String, List<PeerConnectivityManager.PeerState>> states = new LinkedHashMap<>();
        for (String infoHash : swarmAssistSessions.keySet()) {
            if (isActiveSwarmAssist(infoHash)) states.put(infoHash, peerConnectivity.peersFor(infoHash));
        }
        return states;
    }

    private boolean admitPeerPromotion(PeerConnectivityManager.Promotion promotion) {
        ConnectionRole role = connectionRoleFor(promotion.infoHash(), promotion.strategy());
        if (role == ConnectionRole.OVERLAY && swarmAssistResourceGovernor.hasForegroundUserWork()) {
            diagnostics.log(P2pDiagnostics.Layer.CONNECTIVITY, "OVERLAY CONNECTION DEFERRED: infoHash=" + promotion.infoHash()
                    + "; peer=" + promotion.endpoint().display() + "; motivo=stream/download do usuário em primeiro plano.");
            return false;
        }
        if (role == ConnectionRole.ASSIST) {
            SwarmAssistResourceGovernor.AssistPermission permission = swarmAssistResourceGovernor.assistPermission();
            if (permission != SwarmAssistResourceGovernor.AssistPermission.PERMITTED) {
                diagnostics.log(P2pDiagnostics.Layer.CONNECTIVITY, "SWARM ASSIST CONNECTION DEFERRED: infoHash=" + promotion.infoHash()
                        + "; peer=" + promotion.endpoint().display() + "; motivo=" + permission + ".");
                return false;
            }
            SwarmAssistConnectionPolicy.Decision decision = swarmAssistConnectionPolicy.decide(promotion.infoHash(), promotion.endpoint(),
                    activeAssistPeerStates());
            if (!decision.admitted()) {
                diagnostics.log(P2pDiagnostics.Layer.CONNECTIVITY, "SWARM ASSIST CONNECTION LIMIT: infoHash=" + promotion.infoHash()
                        + "; peer=" + promotion.endpoint().display() + "; motivo=" + decision.reason()
                        + "; conexões no swarm=" + decision.occupiedInSwarm() + "/" + decision.perSwarmLimit()
                        + "; total assistencial=" + decision.occupiedTotal() + "/" + decision.totalLimit()
                        + "; descoberta preservada, promoção adiada.");
                return false;
            }
        }
        GlobalConnectionBudget.Decision global = globalConnectionBudget.admit(role, connectionKey(promotion.infoHash(), promotion.endpoint()),
                globalConnectionSlots());
        if (!global.admitted()) {
            diagnostics.log(P2pDiagnostics.Layer.CONNECTIVITY, "CONNECTION BUDGET DEFERRED: role=" + role
                    + "; infoHash=" + promotion.infoHash() + "; peer=" + promotion.endpoint().display()
                    + "; motivo=" + global.reason() + "; categoria=" + global.snapshot().count(role)
                    + "/" + global.categoryLimit() + "; pending=" + global.snapshot().pending()
                    + "/" + connectionLimits().maxPendingConnections() + "; total=" + global.snapshot().total()
                    + "/" + connectionLimits().maxTotalConnections() + "; reservaSuperior="
                    + global.reservedForHigherPriority() + ".");
        }
        return global.admitted();
    }

    private ConnectionRole connectionRoleFor(String infoHash, PeerConnectivityManager.Strategy strategy) {
        if (strategy == PeerConnectivityManager.Strategy.HOLE_PUNCHING) return ConnectionRole.RENDEZVOUS;
        if (OfficialBootstrapSwarm.INFO_HASH.equalsIgnoreCase(infoHash)) return ConnectionRole.OVERLAY;
        if (isActiveSwarmAssist(infoHash)) return ConnectionRole.ASSIST;
        if (seedingSessions.containsKey(infoHash)) return ConnectionRole.SEED;
        return userTransferRoles.getOrDefault(infoHash, ConnectionRole.DOWNLOAD);
    }

    private List<GlobalConnectionBudget.Slot> globalConnectionSlots() {
        Map<String, GlobalConnectionBudget.Slot> slots = new LinkedHashMap<>();
        for (bt.net.ConnectionKey accepted : bootstrapPeerConnections.connectionKeys()) {
            String infoHash = hex(accepted.getTorrentId());
            PeerConnectivityManager.Strategy strategy = strategyForAcceptedConnection(infoHash, accepted.getPeer(), accepted.getRemotePort());
            String key = connectionKey(infoHash, accepted.getPeer().getInetAddress(), accepted.getRemotePort());
            slots.put(key, new GlobalConnectionBudget.Slot(key, connectionRoleFor(infoHash, strategy), false));
        }
        for (PeerConnectivityManager.PeerState state : peerConnectivity.allPeers()) {
            if (!isPendingGlobalConnection(state.connection())) continue;
            String key = connectionKey(state.infoHash(), state.endpoint().address(), state.endpoint().port());
            slots.putIfAbsent(key, new GlobalConnectionBudget.Slot(key,
                    connectionRoleFor(state.infoHash(), state.strategy()), true));
        }
        return List.copyOf(slots.values());
    }

    private boolean isPendingGlobalConnection(PeerConnectivityManager.ConnectionState state) {
        return switch (state) {
            case DIRECT_CONNECT_PENDING, DIRECT_CONNECTING, PORT_MAPPING_PENDING, HOLE_PUNCH_PENDING, HOLE_PUNCHING -> true;
            default -> false;
        };
    }

    private String connectionKey(String infoHash, PeerConnectivityManager.PeerEndpoint endpoint) {
        return connectionKey(infoHash, endpoint.address(), endpoint.port());
    }

    private String connectionKey(String infoHash, java.net.InetAddress address, int port) {
        return infoHash.toLowerCase() + "|" + address.getHostAddress() + "|" + port;
    }

    private PeerConnectivityManager.Strategy strategyForAcceptedConnection(String infoHash, Peer peer, int remotePort) {
        return peerConnectivity.peersFor(infoHash).stream()
                .filter(state -> state.endpoint().address().equals(peer.getInetAddress()))
                .filter(state -> state.endpoint().port() == remotePort)
                .filter(state -> state.connection() == PeerConnectivityManager.ConnectionState.CONNECTED)
                .map(PeerConnectivityManager.PeerState::strategy).findFirst()
                .orElse(PeerConnectivityManager.Strategy.NONE);
    }

    /** A conexão recebida só é classificada após o handshake, quando o torrent é conhecido. */
    private void enforceAcceptedConnectionBudget(String infoHash, Peer peer, int remotePort) {
        if (peer == null || remotePort < 1 || remotePort > 65_535) return;
        // A admissão já considera tentativas pendentes. No instante posterior
        // ao handshake, porém, elas não devem fechar a conexão recém-aceita e
        // impedir metadata/bitfield de chegar ao torrent.
        GlobalConnectionBudget.Snapshot snapshot = globalConnectionBudget.acceptedSnapshot(globalConnectionSlots());
        ConnectionLimits limits = connectionLimits();
        ConnectionRole incomingRole = connectionRoleFor(infoHash, strategyForAcceptedConnection(infoHash, peer, remotePort));
        boolean overTotal = snapshot.total() > limits.maxTotalConnections();
        boolean overCategory = countConnectionRole(snapshot, incomingRole) > limits.categoryLimit(incomingRole);
        if (!overTotal && !overCategory) return;

        String incomingKey = connectionKey(infoHash, peer.getInetAddress(), remotePort);
        bt.net.ConnectionKey victim = selectBudgetVictim(incomingKey, incomingRole, overCategory);
        if (victim == null || !bootstrapPeerConnections.close(victim)) return;
        String victimHash = hex(victim.getTorrentId());
        ConnectionRole victimRole = connectionRoleFor(victimHash,
                strategyForAcceptedConnection(victimHash, victim.getPeer(), victim.getRemotePort()));
        diagnostics.log(P2pDiagnostics.Layer.CONNECTIVITY, "CONNECTION BUDGET CLOSED: infoHash=" + victimHash
                + "; peer=" + victim.getPeer().getInetAddress().getHostAddress() + ":" + victim.getRemotePort()
                + "; role=" + victimRole + "; motivo=" + (overCategory ? "CATEGORY_LIMIT" : "TOTAL_LIMIT")
                + "; prioridade do stream/download foi preservada.");
    }

    private int countConnectionRole(GlobalConnectionBudget.Snapshot snapshot, ConnectionRole role) {
        if (role.isUserTransfer()) return snapshot.count(ConnectionRole.STREAM) + snapshot.count(ConnectionRole.DOWNLOAD);
        if (role.isOverlayControl()) return snapshot.count(ConnectionRole.RENDEZVOUS) + snapshot.count(ConnectionRole.OVERLAY);
        return snapshot.count(role);
    }

    private bt.net.ConnectionKey selectBudgetVictim(String incomingKey, ConnectionRole incomingRole, boolean categoryViolation) {
        List<bt.net.ConnectionKey> candidates = bootstrapPeerConnections.connectionKeys().stream()
                .filter(key -> {
                    String infoHash = hex(key.getTorrentId());
                    ConnectionRole role = connectionRoleFor(infoHash,
                            strategyForAcceptedConnection(infoHash, key.getPeer(), key.getRemotePort()));
                    return categoryViolation ? sameBudgetCategory(role, incomingRole) : role.priority() >= incomingRole.priority();
                })
                .toList();
        // O peer que acabou de concluir o handshake ainda não teve oportunidade de
        // enviar seu bitfield. Fechá-lo sempre aqui descartava justamente o peer
        // que poderia entregar metadados ou as primeiras peças do streaming.
        List<bt.net.ConnectionKey> alternatives = candidates.stream().filter(key -> !connectionKey(hex(key.getTorrentId()),
                key.getPeer().getInetAddress(), key.getRemotePort()).equals(incomingKey)).toList();
        return (alternatives.isEmpty() ? candidates : alternatives).stream()
                .max(java.util.Comparator.comparingInt((bt.net.ConnectionKey key) -> {
                    String infoHash = hex(key.getTorrentId());
                    return connectionRoleFor(infoHash,
                            strategyForAcceptedConnection(infoHash, key.getPeer(), key.getRemotePort())).priority();
                }).thenComparingInt(key -> bootstrapPeerConnections.hasAdvertisedPieces(key) ? 0 : 1)
                        .thenComparing(key -> bootstrapPeerConnections.acceptedAt(key).orElse(Instant.MAX),
                                java.util.Comparator.reverseOrder()))
                .orElse(null);
    }

    private boolean sameBudgetCategory(ConnectionRole left, ConnectionRole right) {
        return left == right || left.isUserTransfer() && right.isUserTransfer()
                || left.isOverlayControl() && right.isOverlayControl();
    }

    /** Uma ação explícita de seed ou download promove o swarm e encerra somente a sessão sem dados. */
    private void pauseSwarmAssist(String infoHash) {
        BtClient passive = swarmAssistSessions.remove(infoHash);
        if (passive != null) {
            stopSwarmAssistMaintenance(infoHash);
            passive.stop();
            diagnostics.log(P2pDiagnostics.Layer.BITTORRENT, "SWARM ASSIST PAUSADO: infoHash=" + infoHash
                    + "; sessão passiva encerrada para iniciar a sessão solicitada pelo usuário.");
        }
    }

    private void reportMissingMetadataAfterDelay(MagnetLink magnet, BtClient client) {
        Thread.startVirtualThread(() -> {
            try { Thread.sleep(15_000); } catch (InterruptedException error) { Thread.currentThread().interrupt(); return; }
            if (sessions.get(magnet.infoHash()) == client && client.isStarted()) {
                holePunchAgent.terminalStatus(magnet.infoHash()).ifPresentOrElse(statusListener::accept,
                        () -> statusListener.accept("Ainda não chegaram metadados para “" + magnet.displayName().orElse("vídeo")
                                + "”. Verifique se o magnet foi copiado do computador que semeia e se ele possui rota pública ou IPv6 compatível."));
            }
        });
    }
    /** uTP usa UDP na mesma porta logica do peer BitTorrent; DHT permanece em 49001. */
    private synchronized UtpTransportService ensureUtpTransport() {
        if (utpTransport != null) return utpTransport;
        try {
            utpTransport = new UtpTransportService(java.net.InetAddress.getByName("0.0.0.0"), connectivity.torrentListeningPort(),
                    diagnostics, new UtpTransportService.SessionLimits(1_024, 128, 8, Duration.ofSeconds(15), Duration.ofMinutes(5)),
                    abuseProtection);
            return utpTransport;
        } catch (Exception error) {
            diagnostics.log("uTP UDP nao iniciou na porta " + connectivity.torrentListeningPort() + ": " + message(error)
                    + ". TCP continua ativo e o hole punching nao sera anunciado.");
            return null;
        }
    }
    private synchronized BtRuntime runtime() {
        if (runtime == null) {
            UtpTransportService utp = ensureUtpTransport();
            ConnectivityProfile.DhtAnnouncement announcement = connectivity.dhtAnnouncement();
            var builder = BtRuntime.builder(networkConfig(false)).disableAutomaticShutdown().autoLoadModules()
                    // BEP 11 é ativado explicitamente, mesmo já sendo extensão padrão do bt-core.
                    .module(new PeerExchangeModule())
                    .module(new PexObservationModule(this::onPexPeerExchange))
                    .module(identityExtension).module(routeExtension).module(rendezvousExtension);
            if (announcement.shouldAnnounce()) builder.module(dhtDiscoveryModule(false));
            else diagnostics.log("DHT ANNOUNCE MODE: OUTBOUND_ONLY_FIREWALLED. A runtime de transferência não recebeu o módulo DHT; "
                    + "a runtime separada de descoberta continuará buscando peers sem anunciar esta máquina.");
            if (utp != null) builder.module(new Bep55HolePunchModule(holePunchAgent));
            else builder.module(identityExtension.handshakeObserverModule());
            runtime = builder.build();
            transferRuntimeDhtAnnounceEnabled = announcement.shouldAnnounce();
            BtConnectionLifecycleInstrumentation.install(runtime, peerConnectivity, diagnostics, bootstrapPeerConnections);
            if (utp != null) {
                try {
                    utpBridge.attach(runtime, utp);
                    peerConnectivity.setPathAvailable(PeerConnectivityManager.AddressFamily.IPV4, PeerConnectivityManager.Transport.UTP, true);
                } catch (RuntimeException error) {
                    diagnostics.log("uTP BRIDGE indisponivel: " + message(error) + ". TCP continua ativo.");
                }
            }
            if (announcement.shouldAnnounce()) configureIpv4DhtAnnouncementPort(runtime);
            diagnostics.log("PEX BEP 11 ativo: peers recebidos via ut_pex serão registrados com origem PEX.");
            diagnostics.log("DHT IPv4 criado: UDP local " + connectivity.dhtListeningPort() + ", TCP P2P local " + connectivity.torrentListeningPort()
                    + (utp == null ? "; uTP indisponivel." : "; uTP UDP local " + utp.localPort() + " ativo.") );
        }
        return runtime;
    }

    /**
     * Mantém o DHT disponível para lookup sem associar clientes/torrents a esta runtime.
     * MldhtService anuncia automaticamente quando recebe um TorrentStartedEvent; como esta
     * runtime nunca cria BtClient, ela é segura para peers outbound-only/firewalled.
     */
    private BtRuntime awaitDhtReady(boolean ipv6) {
        if (ipv6 && !connectivity.hasGlobalIpv6()) return null;
        return (ipv6 ? ipv6DhtLookupLifecycle : ipv4DhtLookupLifecycle).awaitDhtReady();
    }

    private DhtLookupRuntimeLifecycle dhtLookupLifecycle(boolean ipv6) {
        String family = ipv6 ? "IPv6" : "IPv4";
        return new DhtLookupRuntimeLifecycle(
                () -> BtRuntime.builder(dhtLookupNetworkConfig(ipv6)).disableAutomaticShutdown().autoLoadModules()
                        .module(dhtDiscoveryModule(ipv6)).build(),
                created -> {
                    diagnostics.log("[DHT] LOOKUP RUNTIME STARTING: family=" + family + "; udpLocalPort="
                            + connectivity.dhtListeningPort() + "; mode=DISCOVERY_ONLY.");
                    DhtLookupRuntimeInitializer.ReadyDhtState state;
                    try {
                        state = DhtLookupRuntimeInitializer.startupAndAwait(created);
                    } catch (RuntimeException error) {
                        diagnostics.log(P2pDiagnostics.Layer.DISCOVERY, "[DHT] startup failed reason=RPC server did not become ready"
                                + "; timeout=" + created.getConfig().getShutdownHookTimeout().toSeconds() + "s"
                                + "; retryBackoff=" + dhtLookupRuntimeSettings.dhtRetryBackoff().toSeconds() + "s"
                                + "; detail=" + message(error));
                        throw error;
                    }
                    diagnostics.log("[DHT] RPC SERVER STARTED: family=" + family + "; runningServers="
                            + state.runningRpcServers() + ".");
                    diagnostics.log("[DHT] LOOKUP RUNTIME READY: family=" + family + "; mode=DISCOVERY_ONLY.");
                    diagnostics.log("[DHT] bootstrap started: family=" + family + "; source=bt-dht.");
                    diagnostics.log("[DHT] nodes known=" + state.knownNodes() + "; family=" + family + ".");
                }, () -> dhtLookupRuntimeSettings.dhtRetryBackoff());
    }

    private List<Boolean> activeDhtFamilies() {
        return connectivity.hasGlobalIpv6() ? List.of(false, true) : List.of(false);
    }
    private synchronized BtRuntime ipv6Runtime() {
        if (connectivity.publicIpv6().isEmpty()) return null;
        if (ipv6Runtime == null) {
            ipv6Runtime = BtRuntime.builder(networkConfig(true)).disableAutomaticShutdown().autoLoadModules()
                    .module(dhtDiscoveryModule(true)).module(new PeerExchangeModule())
                    .module(new PexObservationModule(this::onPexPeerExchange)).module(identityExtension).module(routeExtension).module(rendezvousExtension)
                    .module(identityExtension.handshakeObserverModule()).build();
            BtConnectionLifecycleInstrumentation.install(ipv6Runtime, peerConnectivity, diagnostics, bootstrapPeerConnections);
            diagnostics.log("DHT IPv6 criado para semeadura: UDP local " + connectivity.dhtListeningPort() + ".");
        }
        return ipv6Runtime;
    }
    /** IPv4 and IPv6 have separate public DHT overlays. A seed with public IPv6 joins both. */
    private void seedOnIpv6WhenAvailable(Path torrentFile, Path storageDirectory, Path contentRoot, String infoHash) {
        if (connectivity.hasGlobalIpv6()) {
            diagnostics.log("DHT IPv6 ANNOUNCE SUPPRESSED: infoHash=" + infoHash
                    + "; estado=OUTBOUND_ONLY_FIREWALLED; conectividade de entrada IPv6 ainda não confirmada.");
        }
        return;
        /* Etapa futura: reativar somente após confirmação externa IPv6.
        BtRuntime v6 = ipv6Runtime();
        if (v6 == null) return;
        ipv6SeedSessions.computeIfAbsent(infoHash, ignored -> {
            try {
                BtClient client = Bt.client(v6).storage(new FileSystemStorage(storageDirectory))
                        .torrent(torrentFile.toUri().toURL()).build();
                startClient("Seed IPv6", contentRoot.getFileName().toString(), infoHash, client, () -> { });
                return client;
            } catch (MalformedURLException e) { throw new IllegalArgumentException("Arquivo torrent inválido", e); }
            catch (RuntimeException error) {
                statusListener.accept("A semeadura IPv6 não iniciou para “" + contentRoot.getFileName() + "”; a semeadura IPv4 continua ativa.");
                return null;
            }
        });
        */
    }
    private CompletableFuture<?> startClient(String operation, String title, String infoHash, BtClient client, Runnable onStarted) {
        diagnostics.log(P2pDiagnostics.Layer.DOWNLOAD, "CLIENT START REQUESTED: operation=" + operation + "; infoHash=" + infoHash
                + "; startedBeforeRequest=" + client.isStarted() + ".");
        CompletableFuture<?> clientFuture = client.startAsync(state -> reportTransferState(operation, infoHash, state), 1_000);
        clientProcessCompletions.put(client, clientFuture);
        clientFuture.whenComplete((ignored, error) -> {
            clientProcessCompletions.remove(client, clientFuture);
            diagnostics.log(P2pDiagnostics.Layer.DOWNLOAD, "CLIENT PROCESS COMPLETED: operation=" + operation + "; infoHash=" + infoHash
                    + "; error=" + (error == null ? "none" : error.getClass().getSimpleName() + ": " + error.getMessage())
                    + "; startedAtCompletion=" + client.isStarted() + ".");
            if (error != null) reportFailure(operation, error);
        });
        Thread.startVirtualThread(() -> {
            try { Thread.sleep(1_000); } catch (InterruptedException error) { Thread.currentThread().interrupt(); return; }
            if (client.isStarted()) {
                onStarted.run();
                boolean swarmAssist = operation.equals("Swarm Assist");
                diagnostics.log(operation.toUpperCase() + " iniciado: infoHash=" + infoHash + ", título=\"" + title + "\". "
                        + (swarmAssist
                        ? "Swarm Assist: peers podem ser descobertos e conectados, mas peças não serão solicitadas."
                        : operation.startsWith("Seed")
                        ? (connectivity.dhtAnnouncement().shouldAnnounce()
                        ? "O announce foi encaminhado ao motor DHT."
                        : "A máquina permanece outbound-only; o announce local foi suprimido.")
                        : "A busca de peers foi encaminhada ao motor DHT."));
                if (!swarmAssist) statusListener.accept(operation.equals("Seed")
                        ? seedStatus(title)
                        : "P2P ativo para “" + title + "”. Buscando peers no DHT…");
                if (operation.equals("Seed")) requestDhtLookup(infoHash, "verificação do announce");
                else if (operation.startsWith("Download")) requestDhtLookup(infoHash, "busca do magnet");
                else if (swarmAssist && swarmAssistInitialLookupSuppressed.remove(infoHash)) {
                    diagnostics.log(P2pDiagnostics.Layer.DISCOVERY, "SWARM ASSIST START: infoHash=" + infoHash
                            + "; lookup inicial delegado à restauração gradual.");
                } else if (swarmAssist) scheduleSwarmAssistDhtLookup(infoHash, "reentrada automática de Swarm Assist");
            }
            else if (!operation.equals("Swarm Assist")) statusListener.accept(operation + " P2P não iniciou para “" + title + "”.");
        });
        return clientFuture;
    }
    /** Consulta observável da DHT. Ela apenas registra endpoints; o motor BitTorrent realiza conexões reais. */
    private void requestDhtLookup(String infoHash, String purpose) {
        if (shuttingDown.get()) return;
        for (boolean ipv6 : activeDhtFamilies()) requestDhtLookup(infoHash, purpose, ipv6);
    }

    /**
     * Manutenção automática nunca dispara 25 lookups ao mesmo tempo. A fila
     * deduplica o infoHash e reserva uma janela entre consultas Assist.
     */
    private CompletableFuture<Integer> scheduleSwarmAssistDhtLookup(String infoHash, String purpose) {
        SwarmAssistDhtScheduler.ScheduledLookup scheduled = swarmAssistDhtScheduler.schedule(infoHash,
                () -> inspectSwarmPeerCountNow(infoHash, purpose));
        diagnostics.log(P2pDiagnostics.Layer.DISCOVERY, "SWARM ASSIST DHT " + (scheduled.coalesced() ? "COALESCED" : "SCHEDULED")
                + ": infoHash=" + infoHash + "; finalidade=" + purpose + "; atrasoMs=" + scheduled.delay().toMillis()
                + "; espaçamentoMs=" + SwarmAssistDhtScheduler.LOOKUP_SPACING.toMillis() + ".");
        return scheduled.completion();
    }

    /** O mesmo infoHash é consultado em cada sobreposição DHT ativa, sem misturar IPv4 e IPv6. */
    private CompletableFuture<Integer> requestDhtLookup(String infoHash, String purpose, boolean ipv6) {
        if (shuttingDown.get()) return CompletableFuture.failedFuture(dhtLookupShutdownError());
        CompletableFuture<Integer> completion = new CompletableFuture<>();
        Thread worker = Thread.ofVirtual().unstarted(() -> {
            try {
                if (shuttingDown.get()) throw dhtLookupShutdownError();
                BtRuntime active = awaitDhtReady(ipv6);
                if (active == null) {
                    completion.completeExceptionally(new IllegalStateException("DHT " + (ipv6 ? "IPv6" : "IPv4") + " não está ativa"));
                    return;
                }
                if (shuttingDown.get()) throw dhtLookupShutdownError();
                String overlay = ipv6 ? "IPv6" : "IPv4";
                diagnostics.log("[DHT] LOOKUP START: infoHash=" + infoHash + "; family=" + overlay + "; purpose=" + purpose + ".");
                diagnostics.log("[DHT] nodes known=" + dhtKnownNodes(active) + "; family=" + overlay + "; phase=before-lookup.");
                AtomicInteger rawPeers = new AtomicInteger();
                java.util.Set<String> uniquePeers = ConcurrentHashMap.newKeySet();
                try (var found = active.service(DHTService.class).getPeers(TorrentId.fromBytes(hexBytes(infoHash)))) {
                    found.limit(32).forEach(peer -> {
                        if (shuttingDown.get()) throw dhtLookupShutdownError();
                        rawPeers.incrementAndGet();
                        String endpoint = peer.getInetAddress().getHostAddress() + ":" + peer.getPort();
                        if (uniquePeers.add(endpoint)) {
                            diagnostics.log("[DHT] PEER DISCOVERED: infoHash=" + infoHash + "; endpoint=" + endpoint
                                    + "; source=" + overlay + "; index=" + uniquePeers.size() + ".");
                            peerConnectivity.onDhtPeerDiscovered(infoHash, peer);
                            if (uniquePeers.size() == 1) reportSwarmAssistActivity(infoHash, SwarmAssistActivity.Type.PEER_SEEN);
                        }
                    });
                }
                diagnostics.log("[DHT] LOOKUP COMPLETE: infoHash=" + infoHash + "; purpose=" + purpose + "; events=" + rawPeers.get()
                        + "; uniquePeers=" + uniquePeers.size() + "; nodes known=" + dhtKnownNodes(active) + ".");
                refreshSwarmAssistStats(infoHash);
                completion.complete(uniquePeers.size());
            } catch (Exception error) {
                diagnostics.log(P2pDiagnostics.Layer.DISCOVERY, "[DHT] " + (shuttingDown.get() ? "LOOKUP CANCELLED" : "LOOKUP FAILED")
                        + ": finalidade=" + purpose + "; erro=" + error.getClass().getSimpleName() + " — " + String.valueOf(error.getMessage()));
                // Falha não é observação de swarm vazio. O chamador mantém a
                // estatística anterior e pode tentar novamente no scheduler.
                completion.completeExceptionally(error);
            } finally {
                pendingDhtLookups.remove(Thread.currentThread());
            }
        });
        synchronized (dhtLookupShutdownMonitor) {
            if (shuttingDown.get()) return CompletableFuture.failedFuture(dhtLookupShutdownError());
            pendingDhtLookups.put(worker, completion);
            worker.start();
        }
        return completion;
    }
    private void attachTorrentDiagnostics(String infoHash, String role) {
        attachTorrentDiagnostics(runtime(), infoHash, role);
    }

    private void attachTorrentDiagnostics(BtRuntime activeRuntime, String infoHash, String role) {
        torrentDiagnosticRoles.put(infoHash, role);
        String runtimeTorrentKey = System.identityHashCode(activeRuntime) + ":" + infoHash;
        if (!observedTorrents.add(runtimeTorrentKey)) return;
        TorrentId id = TorrentId.fromBytes(hexBytes(infoHash));
        var events = activeRuntime.getEventSource();
        events.onPeerDiscovered(id, event -> {
            Peer discovered = event.getPeer();
            if (discovered == null || discovered.isPortUnknown()) return;
            // DHT e PEX ja passaram explicitamente pelo Connectivity Manager.
            // Um endpoint novo neste evento vem da fonte nativa do bt-core
            // (trackers configurados no magnet), que ja o registrou uma vez
            // no IPeerRegistry. Apenas registramos a origem, sem uma segunda
            // promocao/conexao e sem criar outra sessao de download.
            if (peerConnectivity.isKnownTcpEndpoint(infoHash, discovered)) return;
            peerConnectivity.onTrackerPeerDiscovered(infoHash, discovered);
            diagnostics.log(P2pDiagnostics.Layer.DISCOVERY, "TRACKER PEER DISCOVERED: infoHash=" + infoHash
                    + "; peer=" + peer(discovered) + "; origem=TRACKER; encaminhado ao PeerConnectivityManager.");
        });
        events.onPeerConnected(id, event -> {
            peerConnectivity.onBitTorrentConnectedEvent(infoHash, event.getPeer(), event.getRemotePort());
            enforceAcceptedConnectionBudget(infoHash, event.getPeer(), event.getRemotePort());
            reportSwarmAssistActivity(infoHash, SwarmAssistActivity.Type.PEER_SEEN);
            reportHolePunchSuccessIfConfirmed(infoHash, event.getPeer(), event.getRemotePort());
            SwarmAssistStats stats = refreshSwarmAssistStats(infoHash);
            diagnostics.log(P2pDiagnostics.Layer.BITTORRENT, "HANDSHAKE COMPLETE: role=" + diagnosticRole(infoHash, role) + "; peer=" + peer(event.getPeer())
                    + "; porta remota efetiva=" + event.getRemotePort() + ".");
            if (isActiveSwarmAssist(infoHash)) {
                Map<String, List<PeerConnectivityManager.PeerState>> assistStates = activeAssistPeerStates();
                int occupied = swarmAssistConnectionPolicy.occupiedInSwarm(assistStates.get(infoHash));
                diagnostics.log(P2pDiagnostics.Layer.CONNECTIVITY, "SWARM ASSIST CONNECTION LIVE: infoHash=" + infoHash
                        + "; conexões úteis=" + occupied
                        + "/" + activeSwarmAssistPolicy.maximumConnectionsPerSwarm()
                        + "; total assistencial=" + swarmAssistConnectionPolicy.occupiedTotal(assistStates)
                        + "/" + activeSwarmAssistPolicy.maximumConnectionsTotal()
                        + "; rendezvous BEP55=" + stats.usefulRendezvousPeerCount() + ".");
                logSwarmAssistConnectionState(infoHash, occupied, stats);
            }
        });
        events.onPeerBitfieldUpdated(id, event -> {
            bootstrapPeerConnections.markPeerHasPieces(id, event.getPeer(), event.getConnectionKey().getRemotePort(),
                    event.getBitfield().getPiecesComplete() > 0);
            diagnostics.log(diagnosticRole(infoHash, role) + ": bitfield recebido de " + peer(event.getPeer()) + "; peças disponíveis="
                    + event.getBitfield().getPiecesComplete() + "/" + event.getBitfield().getPiecesTotal() + ".");
        });
        events.onMetadataAvailable(id, event -> diagnostics.log(diagnosticRole(infoHash, role) + ": evento de metadados recebido; torrent=" + event.getTorrent().getName() + "."));
        events.onPieceVerified(id, event -> {
            int contiguousPrefix = streamingPiecePrefixes.record(infoHash, event.getPieceIndex());
            diagnostics.log(P2pDiagnostics.Layer.BITTORRENT, "PIECE VERIFIED: role=" + diagnosticRole(infoHash, role)
                    + "; índice=" + event.getPieceIndex() + ".");
            if (contiguousPrefix > 0) {
                diagnostics.log(P2pDiagnostics.Layer.DOWNLOAD, "STREAM PREFIX VERIFIED: infoHash=" + infoHash
                        + "; contiguousPieces=" + contiguousPrefix + "; lastPiece=" + event.getPieceIndex() + ".");
            }
        });
        events.onPeerDisconnected(id, event -> {
            peerConnectivity.onBitTorrentDisconnected(infoHash, event.getPeer(), event.getRemotePort());
            holePunchAgent.onPeerDisconnected(infoHash, event.getPeer(), event.getRemotePort());
            bootstrapPeerConnections.remove(id, event.getPeer(), event.getRemotePort());
            routeExtension.onPeerDisconnected(infoHash, event.getPeer(), event.getRemotePort());
            rendezvousExtension.onPeerDisconnected(infoHash, event.getPeer(), event.getRemotePort());
            identityExtension.onPeerDisconnected(infoHash, event.getPeer(), event.getRemotePort());
            refreshSwarmAssistStats(infoHash);
            diagnostics.log(diagnosticRole(infoHash, role) + ": conexão BitTorrent encerrada com " + peer(event.getPeer()) + ".");
            requestSwarmAssistReplenishment(infoHash, "conexão BitTorrent encerrada");
        });
    }

    private String diagnosticRole(String infoHash, String fallback) {
        return torrentDiagnosticRoles.getOrDefault(infoHash, fallback);
    }

    private SwarmAssistStats refreshSwarmAssistStats(String infoHash) {
        SwarmAssistStats stats = SwarmAssistStats.from(infoHash, peerConnectivity.peersFor(infoHash),
                holePunchAgent.usefulRendezvousPeerCount(infoHash), Instant.now());
        swarmAssistStats.put(infoHash, stats);
        return stats;
    }

    private void reportHolePunchSuccessIfConfirmed(String infoHash, Peer peer, int remotePort) {
        if (peer == null) return;
        boolean confirmed = peerConnectivity.peersFor(infoHash).stream().anyMatch(state ->
                state.endpoint().transport() == PeerConnectivityManager.Transport.UTP
                        && state.endpoint().address().equals(peer.getInetAddress())
                        && state.endpoint().port() == remotePort
                        && state.strategy() == PeerConnectivityManager.Strategy.HOLE_PUNCHING
                        && state.connection() == PeerConnectivityManager.ConnectionState.CONNECTED);
        String key = infoHash + "|" + peer.getInetAddress().getHostAddress() + ":" + remotePort;
        if (confirmed && recordedHolePunchSuccesses.add(key)) {
            reportSwarmAssistActivity(infoHash, SwarmAssistActivity.Type.HOLE_PUNCH_SUCCEEDED);
            diagnostics.log(P2pDiagnostics.Layer.HOLEPUNCH, "SWARM ASSIST HOLE PUNCH SUCCESS: infoHash=" + infoHash
                    + "; peer=" + peer(peer) + "; handshake BitTorrent/uTP confirmado.");
            diagnostics.log(P2pDiagnostics.Layer.HOLEPUNCH, "[SWARM-ASSIST] hole punch success: infoHash=" + infoHash
                    + "; peer=" + peer(peer) + "; BitTorrent/uTP confirmado.");
        }
    }

    private void logSwarmAssistConnectionState(String infoHash, int occupied, SwarmAssistStats stats) {
        diagnostics.log(P2pDiagnostics.Layer.CONNECTIVITY, "[SWARM-ASSIST] conexões: " + occupied + "/"
                + activeSwarmAssistPolicy.maximumConnectionsPerSwarm() + "; infoHash=" + infoHash + ".");
        diagnostics.log(P2pDiagnostics.Layer.HOLEPUNCH, "[SWARM-ASSIST] BEP55 capable peers: "
                + stats.usefulRendezvousPeerCount() + "; infoHash=" + infoHash + ".");
    }

    private void reportSwarmAssistActivity(String infoHash, SwarmAssistActivity.Type type) {
        if (infoHash == null || !infoHash.matches("(?i)[a-f0-9]{40}")) return;
        try {
            swarmAssistActivityListener.accept(new SwarmAssistActivity(infoHash.toLowerCase(), type, Instant.now()));
        } catch (RuntimeException error) {
            diagnostics.log("SWARM ASSIST ACTIVITY não persistida: infoHash=" + infoHash + "; tipo=" + type
                    + "; motivo=" + message(error) + ".");
        }
    }
    /** Observa o PEX nativo do bt-core; a descoberta e promovida pelo mesmo Connectivity Manager usado pela DHT. */
    private void onPexPeerExchange(TorrentId torrentId, Peer viaPeer, Collection<Peer> added, Collection<Peer> dropped) {
        String infoHash = hex(torrentId);
        int received = 0;
        int duplicateEndpoints = 0;
        Set<String> endpointsInMessage = new HashSet<>();
        for (Peer peer : added) {
            if (peer == null || peer.isPortUnknown()) continue;
            String endpointKey = peer.getInetAddress().getHostAddress() + ":" + peer.getPort();
            if (!endpointsInMessage.add(endpointKey)) {
                duplicateEndpoints++;
                continue;
            }
            received++;
            diagnostics.log("PEX PEER " + received + " recebido: infoHash=" + infoHash + "; origem=PEX; via=" + peer(viaPeer)
                    + "; IP/porta=" + peer(peer) + ".");
            peerConnectivity.onPexPeerDiscovered(infoHash, peer);
        }
        if (!dropped.isEmpty()) {
            diagnostics.log("PEX PEERS REMOVIDOS: infoHash=" + infoHash + "; via=" + peer(viaPeer) + "; quantidade=" + dropped.size() + ".");
        }
        if (received > 0) {
            swarmAssistLastPexAt.put(infoHash, Instant.now());
            refreshSwarmAssistStats(infoHash);
            reportSwarmAssistActivity(infoHash, SwarmAssistActivity.Type.PEER_SEEN);
            diagnostics.log("PEX RECEBIDO: infoHash=" + infoHash + "; novos peers=" + received + "; fonte=peer " + peer(viaPeer) + ".");
        }
        if (duplicateEndpoints > 0) diagnostics.log("PEX DEDUPLICADO: infoHash=" + infoHash + "; endpoints repetidos no anúncio="
                + duplicateEndpoints + "; a mesma identidade permanece registrada uma única vez no Connectivity Manager.");
    }
    /** Um x.pe de magnet é tratado como origem distinta da DHT e do PEX. */
    private void registerMagnetPeerHint(MagnetLink magnet) {
        String value = magnet.parameters().get("x.pe");
        if (value == null || value.isBlank()) return;
        try {
            java.net.InetSocketAddress endpoint = PeerEndpointParser.parse(value);
            if (endpoint.isUnresolved()) throw new IllegalArgumentException("host não pôde ser resolvido");
            Peer peer = InetPeer.build(endpoint.getAddress(), endpoint.getPort());
            diagnostics.log("MAGNET PEER: infoHash=" + magnet.infoHash() + "; origem=MAGNET_METADATA; IP/porta=" + peer(peer) + ".");
            peerConnectivity.onMagnetMetadataPeerDiscovered(magnet.infoHash(), peer);
        } catch (Exception error) {
            diagnostics.log("MAGNET PEER ignorado: infoHash=" + magnet.infoHash() + "; valor=" + value + "; motivo=" + message(error) + ".");
        }
    }
    private java.net.InetSocketAddress parseMagnetPeerHint(String value) throws java.net.UnknownHostException {
        String host;
        String port;
        if (value.startsWith("[")) {
            int closing = value.indexOf(']');
            if (closing < 1 || closing + 2 > value.length() || value.charAt(closing + 1) != ':') throw new IllegalArgumentException("x.pe IPv6 inválido");
            host = value.substring(1, closing); port = value.substring(closing + 2);
        } else {
            int separator = value.lastIndexOf(':');
            if (separator < 1) throw new IllegalArgumentException("x.pe deve usar IP:porta");
            host = value.substring(0, separator); port = value.substring(separator + 1);
        }
        int number = Integer.parseInt(port);
        if (number < 1 || number > 65_535) throw new IllegalArgumentException("porta x.pe inválida");
        return new java.net.InetSocketAddress(java.net.InetAddress.getByName(host), number);
    }
    /** Único adaptador entre a decisão do Connectivity Manager e a fila do motor BitTorrent. */
    private void promotePeerToBitTorrent(PeerConnectivityManager.Promotion promotion) {
        if (promotion.endpoint().family() == PeerConnectivityManager.AddressFamily.IPV6) {
            // A runtime IPv6 de download será ativada na etapa dedicada; não misturamos famílias aqui.
            throw new IllegalStateException("runtime IPv6 de download ainda não foi ativada");
        }
        if (promotion.endpoint().transport() == PeerConnectivityManager.Transport.UTP) {
            if (promotion.strategy() == PeerConnectivityManager.Strategy.HOLE_PUNCHING) {
                utpBridge.connectViaHolePunch(promotion.infoHash(), promotion.endpoint().address(), promotion.endpoint().port());
            } else {
                utpBridge.connectDirect(promotion.infoHash(), promotion.endpoint().address(), promotion.endpoint().port());
            }
            return;
        }
        TorrentId torrentId = TorrentId.fromBytes(hexBytes(promotion.infoHash()));
        InetPeer peer = InetPeer.build(promotion.endpoint().address(), promotion.endpoint().port());
        BtRuntime preview = metadataPreviewRuntimes.get(promotion.infoHash());
        if (preview != null) {
            try {
                preview.service(IPeerRegistry.class).addPeer(torrentId, peer);
                diagnostics.log(P2pDiagnostics.Layer.DOWNLOAD, "STREAM METADATA PEER FORWARDED: infoHash="
                        + promotion.infoHash() + "; endpoint=" + promotion.endpoint().address().getHostAddress()
                        + ":" + promotion.endpoint().port() + ".");
            } catch (RuntimeException error) {
                diagnostics.log(P2pDiagnostics.Layer.DOWNLOAD, "STREAM METADATA PEER FORWARD FAILED: infoHash="
                        + promotion.infoHash() + "; reason=" + message(error) + ".");
            }
        }
        runtime().service(IPeerRegistry.class).addPeer(torrentId, peer);
    }
    private void reportTransferState(String operation, String infoHash, bt.torrent.TorrentSessionState state) {
        if (operation.startsWith("Seed") || operation.equals("Swarm Assist") || operation.equals("Metadata")) return;
        TransferSnapshot previous = transferSnapshots.put(infoHash.toLowerCase(), new TransferSnapshot(state.getDownloaded(),
                state.getPiecesRemaining(), state.getPiecesComplete(), state.getPiecesTotal()));
        if (previous == null || state.getDownloaded() != previous.downloaded()) {
            long delta = previous == null ? state.getDownloaded() : state.getDownloaded() - previous.downloaded();
            diagnostics.log(P2pDiagnostics.Layer.DOWNLOAD, operation.toUpperCase() + ": dados recebidos=" + state.getDownloaded() + " bytes (novo=" + Math.max(0, delta)
                    + "); peças=" + state.getPiecesComplete() + "/" + state.getPiecesTotal() + ".");
        }
        if (state.getPiecesRemaining() > 0 && (previous == null || state.getPiecesRemaining() != previous.piecesRemaining())) {
            diagnostics.log(P2pDiagnostics.Layer.BITTORRENT, "PIECE/BLOCK REQUEST SCHEDULED: operação=" + operation + "; peças restantes="
                    + state.getPiecesRemaining() + "; peers conectados=" + state.getConnectedPeers().size() + ".");
        }
        // Um seletor com todos os arquivos em SKIP também informa zero peças restantes.
        // Isso não é uma transferência concluída: só registrar conclusão com todas as
        // peças verificadas e os dados realmente recebidos.
        if (state.getPiecesTotal() > 0 && state.getPiecesComplete() >= state.getPiecesTotal()
                && state.getDownloaded() > 0 && (previous == null || previous.piecesComplete() < state.getPiecesTotal())) {
            diagnostics.event(P2pDiagnostics.Category.LF_BT_BRIDGE, "PIECE_TRANSFER_CONFIRMED",
                    "torrentId", abbreviatedInfoHash(infoHash), "bytes", state.getDownloaded());
            diagnostics.log(P2pDiagnostics.Layer.DOWNLOAD, operation.toUpperCase() + ": todas as peças foram verificadas; aguardando callback de conclusão.");
        }
    }
    private static String abbreviatedInfoHash(String value) {
        return value == null ? "unknown" : value.substring(0, Math.min(12, value.length())) + (value.length() > 12 ? "..." : "");
    }
    private String peer(bt.net.Peer peer) {
        return peer.getInetAddress().getHostAddress() + (peer.isPortUnknown() ? ":porta desconhecida" : ":" + peer.getPort());
    }
    private int dhtKnownNodes(BtRuntime active) {
        return DhtLookupRuntimeInitializer.readyState(active).knownNodes();
    }
    private byte[] hexBytes(String infoHash) {
        if (infoHash == null || !infoHash.matches("[0-9a-fA-F]{40}")) throw new IllegalArgumentException("infoHash inválido");
        byte[] bytes = new byte[20];
        for (int index = 0; index < bytes.length; index++) bytes[index] = (byte) Integer.parseInt(infoHash.substring(index * 2, index * 2 + 2), 16);
        return bytes;
    }
    private String hex(TorrentId torrentId) {
        StringBuilder value = new StringBuilder(40);
        for (byte current : torrentId.getBytes()) value.append(String.format("%02x", current & 0xff));
        return value.toString();
    }
    private String seedStatus(String title) {
        return connectivity.dhtAnnouncement().endpoint()
                .map(endpoint -> "Biblioteca semeando: “" + title + "”. Rota direta pública: "
                        + endpoint.address().getHostAddress() + ":" + endpoint.port() + " (" + endpoint.mechanism() + ").")
                .orElse("Biblioteca semeando: “" + title + "”. O IP privado da rede local não é anunciado; "
                        + "o Luffy continua buscando e atendendo peers por rotas P2P disponíveis.");
    }
    private Path documentsLuffyDirectory() {
        Path directory = Path.of(System.getProperty("user.home"), "Documents", "Luffy");
        try { java.nio.file.Files.createDirectories(directory); return directory; }
        catch (java.io.IOException e) { throw new IllegalStateException("Não foi possível criar Documentos\\Luffy", e); }
    }
    private Path temporaryDirectory() {
        try { Path directory = java.nio.file.Files.createTempDirectory("luffy-watch-"); temporaryRoots.add(directory); return directory; }
        catch (java.io.IOException e) { throw new IllegalStateException("Não foi possível preparar o cache temporário", e); }
    }
    private List<Path> listFiles(Path root) {
        try (var stream = java.nio.file.Files.walk(root)) { return stream.filter(java.nio.file.Files::isRegularFile).toList(); }
        catch (java.io.IOException e) { return List.of(); }
    }
    private Config networkConfig(boolean ipv6) {
        Config config = new Config();
        config.setAcceptorPort(connectivity.torrentListeningPort());
        // A runtime principal permanece no IPv4 para que todos os Luffys compartilhem um DHT
        // comum. Quando houver IPv6 público, uma segunda runtime de seed entra no DHT IPv6.
        if (ipv6) connectivity.publicIpv6().ifPresent(config::setAcceptorAddress);
        else java.util.Optional.ofNullable(preferredIpv4Address()).ifPresent(config::setAcceptorAddress);
        config.setNumOfHashingThreads(Math.max(2, Runtime.getRuntime().availableProcessors()));
        config.setMaxConcurrentlyActivePeerConnectionsPerTorrent(16);
        applyConnectionLimits(config, connectionLimits());
        // Limite real de retentativas: evita martelar um peer inacessível enquanto preserva a descoberta P2P.
        config.setPeerConnectionRetryInterval(Duration.ofSeconds(30));
        config.setPeerConnectionRetryCount(2);
        return config;
    }

    /** A prévia de metadados usa porta efêmera e não carrega módulos DHT/announce. */
    private Config metadataPreviewNetworkConfig() {
        Config config = networkConfig(false);
        config.setAcceptorPort(0);
        return config;
    }

    /** Mantém o limite interno do bt-core alinhado ao orçamento aplicado pelo Luffy. */
    static void applyConnectionLimits(Config config, ConnectionLimits limits) {
        if (config == null || limits == null) return;
        config.setMaxPeerConnections(limits.maxTotalConnections());
        config.setMaxPeerConnectionsPerTorrent(limits.maxDownloadConnections());
        config.setMaxPendingConnectionRequests(limits.maxPendingConnections());
        config.setNumberOfPeersToRequestFromTracker(limits.maxDownloadConnections());
    }

    /** DHT de consulta não recebe torrents e usa porta TCP efêmera para nunca anunciar a porta P2P local. */
    private Config dhtLookupNetworkConfig(boolean ipv6) {
        Config config = networkConfig(ipv6);
        config.setAcceptorPort(0);
        config.setShutdownHookTimeout(dhtLookupRuntimeSettings.dhtStartupTimeout());
        return config;
    }
    /**
     * O acceptor TCP já foi criado com a porta local durante a construção da runtime.
     * MldhtService lê Config#getAcceptorPort ao anunciar, portanto após a construção
     * substituímos apenas a porta anunciada pela porta externa que o roteador concedeu.
     */
    private void configureIpv4DhtAnnouncementPort(BtRuntime active) {
        int localPort = connectivity.torrentListeningPort();
        ConnectivityProfile.PublicPeerEndpoint endpoint = connectivity.dhtAnnouncement().endpoint().orElseThrow(
                () -> new IllegalStateException("tentativa de anunciar DHT sem endpoint público confirmado"));
        int externalPort = endpoint.port();
        active.getConfig().setAcceptorPort(externalPort);
        diagnostics.log("DHT ANNOUNCE READY: listener TCP local=" + localPort + "; porta pública anunciada=" + externalPort
                + "; endpoint=" + endpoint.address().getHostAddress() + "; evidência=" + endpoint.mechanism() + ".");
    }
    private Inet4Address preferredIpv4Address() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback() || network.isVirtual()) continue;
                Enumeration<java.net.InetAddress> addresses = network.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    java.net.InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address ipv4 && !ipv4.isLoopbackAddress() && !ipv4.isLinkLocalAddress()) return ipv4;
                }
            }
        } catch (Exception ignored) { }
        return null;
    }
    private LuffyDhtDiscoveryModule dhtDiscoveryModule(boolean ipv6) {
        DHTConfig config = new DHTConfig();
        DhtBootstrapNodes.configure(config, ipv6);
        config.setListeningPort(connectivity.dhtListeningPort());
        config.setShouldUseIPv6(ipv6);
        return new LuffyDhtDiscoveryModule(config);
    }
    /** Diagnóstico leve: confirma se a rede permite consultas UDP ao bootstrap público do DHT. */
    public void checkDhtReachability() {
        if (shuttingDown.get()) return;
        for (boolean ipv6 : activeDhtFamilies()) checkDhtReachability(ipv6);
    }

    private void checkDhtReachability(boolean ipv6) {
        Thread.startVirtualThread(() -> {
            try {
                if (shuttingDown.get()) return;
                java.net.InetAddress destination = bootstrapAddress(ipv6);
                try (var socket = ipv6
                        ? new java.net.DatagramSocket(new java.net.InetSocketAddress(connectivity.publicIpv6().orElseThrow(), 0))
                        : new java.net.DatagramSocket()) {
                socket.setSoTimeout(5_000);
                byte[] query = "d1:ad2:id20:01234567890123456789e1:q4:ping1:t2:aa1:y1:qe".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
                socket.send(new java.net.DatagramPacket(query, query.length, new java.net.InetSocketAddress(destination, 6881)));
                byte[] response = new byte[2_048]; socket.receive(new java.net.DatagramPacket(response, response.length));
                diagnostics.log("DHT " + (ipv6 ? "IPv6" : "IPv4") + " bootstrap respondeu por UDP; nós conhecidos=" + dhtKnownNodes(awaitDhtReady(ipv6)) + ".");
                statusListener.accept("DHT " + (ipv6 ? "IPv6" : "IPv4") + " respondeu ao bootstrap. "
                        + (connectivity.publicPeerEndpoint().isPresent()
                        ? "A descoberta de peers está ativa com rota direta configurada."
                        : "Isso confirma apenas saída UDP; nenhum IP privado será tratado como peer público."));
                }
            } catch (Exception error) {
                diagnostics.log("DHT " + (ipv6 ? "IPv6" : "IPv4") + " bootstrap sem resposta UDP: " + error.getClass().getSimpleName() + " — " + String.valueOf(error.getMessage()));
                statusListener.accept("DHT " + (ipv6 ? "IPv6" : "IPv4") + " sem resposta UDP. Verifique firewall, roteador ou rede; sem UDP não é possível encontrar peers na internet.");
            }
        });
    }
    private java.net.InetAddress bootstrapAddress(boolean ipv6) throws java.net.UnknownHostException {
        for (String host : List.of("dht.transmissionbt.com", "router.bittorrent.com", "router.utorrent.com")) {
            for (java.net.InetAddress address : java.net.InetAddress.getAllByName(host)) {
                if (ipv6 == (address instanceof java.net.Inet6Address)) return address;
            }
        }
        throw new java.net.UnknownHostException("Nenhum bootstrap DHT " + (ipv6 ? "IPv6" : "IPv4") + " encontrado");
    }
    private void reportFailure(String operation, Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        statusListener.accept(operation + " P2P falhou: " + cause.getClass().getSimpleName() + " — " + String.valueOf(cause.getMessage()));
    }
    private String message(Exception error) { return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(); }
    private String toUri(MagnetLink magnet) {
        return magnet.toUri();
    }
    @Override public void close() {
        if (!shuttingDown.compareAndSet(false, true)) return;
        cancelPendingDhtLookups();
        ipv4DhtLookupLifecycle.stop();
        ipv6DhtLookupLifecycle.stop();
        rendezvousFallbackCoordinator.close();
        rendezvousExtension.close();
        routeExtension.close();
        bootstrapNeighborManager.close();
        bootstrapSwarmManager.close();
        sessions.values().forEach(BtClient::stop); sessions.clear();
        metadataPreviewRuntimes.values().forEach(this::shutdownRuntime); metadataPreviewRuntimes.clear();
        clientProcessCompletions.clear();
        swarmAssistSessions.values().forEach(BtClient::stop); swarmAssistSessions.clear();
        swarmAssistMaintenanceTasks.values().forEach(task -> task.cancel(false)); swarmAssistMaintenanceTasks.clear();
        swarmAssistReplenishTasks.values().forEach(task -> task.cancel(false)); swarmAssistReplenishTasks.clear();
        swarmAssistMaintenance.shutdownNow(); swarmAssistDhtScheduler.close();
        seedingSessions.clear(); swarmAssistMagnets.clear(); swarmAssistInitialLookupSuppressed.clear(); swarmAssistStats.clear(); swarmAssistLastPexAt.clear(); recordedHolePunchSuccesses.clear(); publishedRoots.clear(); observedTorrents.clear(); torrentDiagnosticRoles.clear(); streamingPiecePrefixes.clear(); streamingMediaFiles.clear(); streamingPieceSelectors.clear(); streamingPriorityWindows.clear();
        BtRuntime active = runtime; runtime = null; transferRuntimeDhtAnnounceEnabled = false; if (active != null) active.shutdown();
        BtRuntime activeIpv6 = ipv6Runtime; ipv6Runtime = null; if (activeIpv6 != null) activeIpv6.shutdown();
        utpBridge.close(); utpTransport = null;
        bootstrapPeerConnections.clear();
        peerConnectivity.close();
        temporaryRoots.forEach(this::deleteTemporaryRoot); temporaryRoots.clear();
    }

    private void cancelPendingDhtLookups() {
        List<Map.Entry<Thread, CompletableFuture<Integer>>> pending;
        synchronized (dhtLookupShutdownMonitor) {
            pending = pendingDhtLookups.entrySet().stream().toList();
        }
        if (!pending.isEmpty()) {
            diagnostics.log(P2pDiagnostics.Layer.DISCOVERY, "DHT shutdown: cancelando " + pending.size() + " consulta(s) pendente(s).");
        }
        for (Map.Entry<Thread, CompletableFuture<Integer>> lookup : pending) {
            lookup.getValue().completeExceptionally(dhtLookupShutdownError());
            lookup.getKey().interrupt();
        }
    }

    private IllegalStateException dhtLookupShutdownError() {
        return new IllegalStateException("DHT lookup cancelled because Luffy is shutting down");
    }
    private void deleteTemporaryRoot(Path root) {
        try (var paths = java.nio.file.Files.walk(root)) { paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> { try { java.nio.file.Files.deleteIfExists(path); } catch (java.io.IOException ignored) { } }); }
        catch (java.io.IOException ignored) { }
    }
    public record DiagnosticTestSource(String magnet, String infoHash, Path sourceFile, P2pDiagnosticScenario scenario) { }
    public record DiagnosticTestResult(Path receivedFile, boolean contentVerified, String content, String outcome, String detail) { }
    public record StreamingBufferStatus(long downloadedBytes, int verifiedPieces, int contiguousPrefixPieces,
                                        int totalPieces, int requiredPieces, boolean sessionActive) {
        public boolean playable() {
            return totalPieces > 0 && contiguousPrefixPieces >= requiredPrefixPieces();
        }
        public int requiredPrefixPieces() {
            return Math.min(Math.max(1, requiredPieces), Math.max(1, totalPieces));
        }
        static StreamingBufferStatus unavailable() {
            return new StreamingBufferStatus(0, 0, 0, 0, DEFAULT_STREAM_STARTUP_PIECES, false);
        }
    }
    /** Byte window that the localhost server may read without exposing unverified gaps. */
    public record StreamingMediaWindow(long contentLengthBytes, long verifiedPrefixBytes, boolean sessionActive) {
        public StreamingMediaWindow {
            contentLengthBytes = Math.max(0, contentLengthBytes);
            verifiedPrefixBytes = Math.max(0, Math.min(contentLengthBytes, verifiedPrefixBytes));
        }
        public boolean hasVerifiedBytesAt(long offset) {
            return offset >= 0 && offset < verifiedPrefixBytes;
        }
        static StreamingMediaWindow unavailable() {
            return new StreamingMediaWindow(0, 0, false);
        }
    }
    /** Immutable description of all torrent pieces required by one file byte range. */
    public record StreamingPieceRange(long fileStartByte, long fileEndByte,
                                      long torrentStartByte, long torrentEndByte,
                                      int startPiece, int endPiece,
                                      long pieceLengthBytes, long endPieceLengthBytes,
                                       boolean firstPiecePartial, boolean lastPiecePartial) { }
    public record StreamingRangeProgress(int piecesRequired, int piecesReady) {
        public StreamingRangeProgress {
            piecesRequired = Math.max(0, piecesRequired);
            piecesReady = Math.max(0, Math.min(piecesRequired, piecesReady));
        }
    }
    private record TransferSnapshot(long downloaded, int piecesRemaining, int piecesComplete, int piecesTotal) { }
    private record StreamingMediaFile(long torrentLengthBytes, long offsetBytes, long lengthBytes, long pieceLengthBytes) { }
}
