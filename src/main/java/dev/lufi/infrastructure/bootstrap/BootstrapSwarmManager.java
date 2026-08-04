package dev.lufi.infrastructure.bootstrap;

import bt.metainfo.TorrentId;
import dev.lufi.domain.MagnetLink;
import dev.lufi.infrastructure.P2pDiagnostics;
import dev.lufi.infrastructure.identity.ConnectedLuffyRegistry;
import dev.lufi.infrastructure.identity.LuffyNodeId;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Mantem a presenca do Luffy no swarm oficial sem criar uma runtime propria.
 * A criacao concreta da sessao e fornecida pelo {@code BtTorrentGateway}, que
 * reutiliza sua BtRuntime, BtClient, DHT, PEX, uTP e extensoes ja instaladas.
 */
public final class BootstrapSwarmManager implements AutoCloseable {
    private static final Duration DEFAULT_RECONNECT_DELAY = Duration.ofSeconds(5);
    private static final Duration DEFAULT_HEALTH_CHECK_INTERVAL = Duration.ofSeconds(20);
    private static final int DEFAULT_MAX_RECONNECT_ATTEMPTS = 3;

    private final Object monitor = new Object();
    private final String rawMagnet;
    private final String expectedInfoHash;
    private final TorrentId expectedTorrentId;
    private final ConnectedLuffyRegistry connectedLuffys;
    private final BootstrapSessionFactory sessionFactory;
    private final P2pDiagnostics diagnostics;
    private final ScheduledExecutorService scheduler;
    private final Duration reconnectDelay;
    private final Duration healthCheckInterval;
    private final int maxReconnectAttempts;
    private final boolean ownsScheduler;

    private volatile BootstrapSwarmState state = BootstrapSwarmState.STOPPED;
    private BootstrapSession session;
    private ScheduledFuture<?> reconnectTask;
    private ScheduledFuture<?> healthCheckTask;
    /** Politica de vizinhos opcional: continua separada da sessao BitTorrent. */
    private volatile BootstrapNeighborManager neighborManager;
    private boolean closed;
    private int reconnectAttempts;
    private int lastReportedNeighborCount = -1;

    public BootstrapSwarmManager(String rawMagnet, String expectedInfoHash, ConnectedLuffyRegistry connectedLuffys,
                                 BootstrapSessionFactory sessionFactory, P2pDiagnostics diagnostics) {
        this(rawMagnet, expectedInfoHash, connectedLuffys, sessionFactory, diagnostics,
                Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("luffy-bootstrap-swarm-", 0).factory()),
                DEFAULT_RECONNECT_DELAY, DEFAULT_HEALTH_CHECK_INTERVAL, DEFAULT_MAX_RECONNECT_ATTEMPTS, true);
    }

    BootstrapSwarmManager(String rawMagnet, String expectedInfoHash, ConnectedLuffyRegistry connectedLuffys,
                          BootstrapSessionFactory sessionFactory, P2pDiagnostics diagnostics,
                          ScheduledExecutorService scheduler, Duration reconnectDelay,
                          Duration healthCheckInterval, int maxReconnectAttempts, boolean ownsScheduler) {
        this.rawMagnet = Objects.requireNonNull(rawMagnet, "rawMagnet");
        this.expectedInfoHash = normalizeInfoHash(expectedInfoHash);
        this.expectedTorrentId = TorrentId.fromBytes(hexBytes(this.expectedInfoHash));
        this.connectedLuffys = Objects.requireNonNull(connectedLuffys, "connectedLuffys");
        this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory");
        this.diagnostics = diagnostics == null ? new P2pDiagnostics() : diagnostics;
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.reconnectDelay = requirePositive(reconnectDelay, "reconnectDelay");
        this.healthCheckInterval = requirePositive(healthCheckInterval, "healthCheckInterval");
        if (maxReconnectAttempts < 0) throw new IllegalArgumentException("maxReconnectAttempts invalido");
        this.maxReconnectAttempts = maxReconnectAttempts;
        this.ownsScheduler = ownsScheduler;
    }

    /** Inicia a sessao uma unica vez; chamadas repetidas enquanto ativa sao inofensivas. */
    public void start() {
        synchronized (monitor) {
            if (closed || state == BootstrapSwarmState.STARTING || state == BootstrapSwarmState.JOINING
                    || state == BootstrapSwarmState.ACTIVE) return;
            cancel(reconnectTask);
            reconnectTask = null;
            reconnectAttempts = 0;
            beginJoinLocked();
        }
    }

    public BootstrapSwarmState state() { return state; }

    /** Liga a politica depois que o gateway cria seus adaptadores de DHT e de conexao viva. */
    public void setNeighborManager(BootstrapNeighborManager neighborManager) {
        this.neighborManager = Objects.requireNonNull(neighborManager, "neighborManager");
    }

    /** Vizinhos sao somente identidades lf_identity validas cuja conexao nasceu no torrent oficial. */
    public Set<LuffyNodeId> connectedNeighbors() {
        return connectedLuffys.listConnectedNodeIds().stream().filter(nodeId -> connectedLuffys.findConnections(nodeId).stream()
                .anyMatch(connection -> connection.sourceTorrent().equals(expectedTorrentId)))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** Acionado pela verificacao de saude ou por um adaptador quando a sessao subjacente falhar. */
    public void reportRecoverableFailure(Throwable error) {
        synchronized (monitor) {
            if (closed || session == null) return;
            recoverLocked(error == null ? new IllegalStateException("sessao bootstrap encerrada") : error);
        }
    }

    /** Encerra a sessao e as tarefas; nao deixa uma tentativa de reconexao sobrevivente. */
    public void stop() {
        synchronized (monitor) {
            cancel(reconnectTask);
            reconnectTask = null;
            cancel(healthCheckTask);
            healthCheckTask = null;
            BootstrapSession current = session;
            session = null;
            BootstrapNeighborManager neighbors = neighborManager;
            if (neighbors != null) neighbors.stop();
            if (current != null) current.close();
            transitionLocked(BootstrapSwarmState.STOPPED, "encerrado");
        }
    }

    @Override public void close() {
        synchronized (monitor) {
            if (closed) return;
            closed = true;
        }
        stop();
        if (ownsScheduler) scheduler.shutdownNow();
    }

    private void beginJoinLocked() {
        transitionLocked(BootstrapSwarmState.STARTING, "validando magnet oficial");
        final MagnetLink magnet;
        try {
            magnet = MagnetLink.parse(rawMagnet);
            if (!expectedInfoHash.equals(magnet.infoHash())) {
                throw new IllegalArgumentException("infoHash do magnet oficial nao corresponde ao esperado");
            }
        } catch (RuntimeException error) {
            transitionLocked(BootstrapSwarmState.FAILED, "magnet oficial invalido: " + message(error));
            return;
        }
        final BootstrapSession created;
        try {
            created = Objects.requireNonNull(sessionFactory.create(magnet), "sessionFactory retornou null");
        } catch (RuntimeException error) {
            recoverLocked(error);
            return;
        }
        session = created;
        transitionLocked(BootstrapSwarmState.JOINING, "sessao criada na runtime BitTorrent existente");
        try {
            created.start().whenComplete((ignored, error) -> onStartCompleted(created, error));
        } catch (RuntimeException error) {
            recoverLocked(error);
        }
    }

    private void onStartCompleted(BootstrapSession candidate, Throwable error) {
        synchronized (monitor) {
            if (closed || candidate != session) return;
            if (error != null) {
                recoverLocked(error);
                return;
            }
            if (!candidate.isActive()) {
                recoverLocked(new IllegalStateException("BtClient bootstrap nao permaneceu iniciado"));
                return;
            }
            reconnectAttempts = 0;
            transitionLocked(BootstrapSwarmState.ACTIVE, "sessao magnet ativa; lf_identity e PEX disponiveis");
            ensureHealthCheckLocked();
            BootstrapNeighborManager neighbors = neighborManager;
            if (neighbors != null) neighbors.start();
            reportNeighborsLocked();
        }
    }

    private void recoverLocked(Throwable error) {
        BootstrapSession failed = session;
        session = null;
        if (failed != null) failed.close();
        transitionLocked(BootstrapSwarmState.DEGRADED, "falha recuperavel: " + message(error));
        reconnectAttempts++;
        if (reconnectAttempts > maxReconnectAttempts) {
            transitionLocked(BootstrapSwarmState.FAILED, "limite de reconexoes atingido");
            return;
        }
        transitionLocked(BootstrapSwarmState.RECONNECTING, "nova tentativa em " + reconnectDelay.toMillis() + " ms");
        cancel(reconnectTask);
        reconnectTask = scheduler.schedule(() -> {
            synchronized (monitor) {
                if (closed || state != BootstrapSwarmState.RECONNECTING) return;
                beginJoinLocked();
            }
        }, reconnectDelay.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void ensureHealthCheckLocked() {
        if (healthCheckTask != null && !healthCheckTask.isCancelled() && !healthCheckTask.isDone()) return;
        healthCheckTask = scheduler.scheduleWithFixedDelay(() -> {
            synchronized (monitor) {
                if (closed || state != BootstrapSwarmState.ACTIVE || session == null) return;
                if (!session.isActive()) recoverLocked(new IllegalStateException("BtClient bootstrap deixou de estar ativo"));
                else {
                    reportNeighborsLocked();
                    BootstrapNeighborManager neighbors = neighborManager;
                    if (neighbors != null) neighbors.maintain();
                }
            }
        }, healthCheckInterval.toMillis(), healthCheckInterval.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void reportNeighborsLocked() {
        int current = connectedNeighbors().size();
        if (current == lastReportedNeighborCount) return;
        lastReportedNeighborCount = current;
        diagnostics.log("[BOOTSTRAP] vizinhos lf_identity ativos=" + current + "; infoHash=" + expectedInfoHash + ".");
    }

    private void transitionLocked(BootstrapSwarmState next, String reason) {
        if (state == next) return;
        state = next;
        diagnostics.log("[BOOTSTRAP] estado=" + next + "; infoHash=" + expectedInfoHash + "; " + reason + ".");
        diagnostics.event(P2pDiagnostics.Category.LF_OVERLAY, "BOOTSTRAP_STATE",
                "state", next, "neighbors", connectedNeighbors().size());
    }

    private static void cancel(ScheduledFuture<?> task) {
        if (task != null) task.cancel(false);
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative()) throw new IllegalArgumentException(name + " nao pode ser negativo");
        return value;
    }

    private static String normalizeInfoHash(String value) {
        if (value == null || !value.matches("(?i)[a-f0-9]{40}")) throw new IllegalArgumentException("infoHash esperado invalido");
        return value.toLowerCase();
    }

    private static byte[] hexBytes(String infoHash) {
        return java.util.HexFormat.of().parseHex(infoHash);
    }

    private static String message(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    @FunctionalInterface
    public interface BootstrapSessionFactory {
        BootstrapSession create(MagnetLink magnet);
    }

    /** Adaptador minimo de BtClient. Ele permite testar o manager sem uma segunda runtime BitTorrent. */
    public interface BootstrapSession extends AutoCloseable {
        CompletionStage<Void> start();
        boolean isActive();
        @Override void close();
    }
}
