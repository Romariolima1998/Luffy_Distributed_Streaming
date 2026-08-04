package dev.lufi.infrastructure.bootstrap;

import bt.metainfo.TorrentId;
import dev.lufi.infrastructure.P2pDiagnostics;
import dev.lufi.infrastructure.identity.ConnectedLuffyRegistry;
import dev.lufi.infrastructure.identity.LuffyNodeId;
import dev.lufi.infrastructure.identity.LuffyPeerCapabilities;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mantem uma quantidade pequena e diversa de vizinhos do swarm Ola Luffy.
 *
 * <p>A identidade usada aqui e sempre {@link LuffyNodeId}. Enderecos IP,
 * localizacao e MAC nao participam da identidade nem do score. O endereco
 * continua somente dentro da referencia de transporte ja viva, necessaria
 * para fechar uma conexao que a politica decidiu dispensar.</p>
 */
public final class BootstrapNeighborManager implements AutoCloseable {
    private final Object monitor = new Object();
    private final TorrentId bootstrapTorrent;
    private final ConnectedLuffyRegistry connectedLuffys;
    private final BootstrapPeerConnectionRegistry liveConnections;
    private final NeighborDiscovery discovery;
    private final P2pDiagnostics diagnostics;
    private final BootstrapNeighborConfiguration configuration;
    private final Map<LuffyNodeId, Instant> lastRenewedAt = new HashMap<>();
    private final Map<LuffyNodeId, Duration> latencyByNode = new ConcurrentHashMap<>();
    private final Set<LuffyNodeId> rendezvousProtected = ConcurrentHashMap.newKeySet();
    private final AtomicInteger pendingDiscoveries = new AtomicInteger();

    private volatile Instant nextDiscoveryAllowedAt = Instant.EPOCH;
    private volatile boolean started;
    private volatile boolean closed;

    public BootstrapNeighborManager(TorrentId bootstrapTorrent, ConnectedLuffyRegistry connectedLuffys,
                                    BootstrapPeerConnectionRegistry liveConnections, NeighborDiscovery discovery,
                                    P2pDiagnostics diagnostics) {
        this(bootstrapTorrent, connectedLuffys, liveConnections, discovery, diagnostics,
                BootstrapNeighborConfiguration.defaults());
    }

    public BootstrapNeighborManager(TorrentId bootstrapTorrent, ConnectedLuffyRegistry connectedLuffys,
                                    BootstrapPeerConnectionRegistry liveConnections, NeighborDiscovery discovery,
                                    P2pDiagnostics diagnostics, BootstrapNeighborConfiguration configuration) {
        this.bootstrapTorrent = Objects.requireNonNull(bootstrapTorrent, "bootstrapTorrent");
        this.connectedLuffys = Objects.requireNonNull(connectedLuffys, "connectedLuffys");
        this.liveConnections = Objects.requireNonNull(liveConnections, "liveConnections");
        this.discovery = Objects.requireNonNull(discovery, "discovery");
        this.diagnostics = diagnostics == null ? new P2pDiagnostics() : diagnostics;
        this.configuration = Objects.requireNonNull(configuration, "configuration");
    }

    public BootstrapNeighborConfiguration configuration() { return configuration; }

    public void start() {
        start(Instant.now());
    }

    /** Variante deterministica usada por testes da politica, sem abrir sockets. */
    void start(Instant now) {
        if (closed) return;
        started = true;
        maintain(now);
    }

    public void stop() { started = false; }

    @Override public void close() {
        closed = true;
        started = false;
        rendezvousProtected.clear();
        latencyByNode.clear();
        synchronized (monitor) { lastRenewedAt.clear(); }
    }

    /** Mantem um unico ciclo. O BootstrapSwarmManager o chama gradualmente em seu health-check. */
    public NeighborMaintenanceResult maintain() { return maintain(Instant.now()); }

    NeighborMaintenanceResult maintain(Instant now) {
        Objects.requireNonNull(now, "now");
        if (!started || closed) return NeighborMaintenanceResult.inactive();

        Map<LuffyNodeId, Neighbor> neighbors = activeNeighbors(now);
        Optional<LuffyNodeId> renewed = renewOne(neighbors, now);
        Optional<LuffyNodeId> evicted = Optional.empty();
        if (neighbors.size() > configuration.maximumNeighbors()) {
            evicted = evictOne(neighbors, now);
        }

        boolean discoveryRequested = false;
        if (neighbors.size() < configuration.targetNeighbors()) {
            discoveryRequested = requestDiscoveryIfAllowed(neighbors.size(), now);
        }
        return new NeighborMaintenanceResult(neighbors.size(), configuration.minimumNeighbors(), configuration.targetNeighbors(),
                configuration.maximumNeighbors(), renewed, evicted, discoveryRequested, pendingDiscoveries.get());
    }

    /** Marca uma conexao como indispensavel enquanto um rendezvous estiver em curso. */
    public void protectRendezvous(LuffyNodeId nodeId) {
        rendezvousProtected.add(Objects.requireNonNull(nodeId, "nodeId"));
    }

    /** Libera a protecao ao concluir, falhar ou cancelar o rendezvous. */
    public void releaseRendezvous(LuffyNodeId nodeId) {
        if (nodeId != null) rendezvousProtected.remove(nodeId);
    }

    /** Medida opcional; a ausencia de latencia e neutra e nunca vira identificador. */
    public void reportLatency(LuffyNodeId nodeId, Duration latency) {
        Objects.requireNonNull(nodeId, "nodeId");
        if (latency == null || latency.isNegative()) {
            latencyByNode.remove(nodeId);
            return;
        }
        latencyByNode.put(nodeId, latency);
    }

    /** Snapshot sem Peer/IP: apropriado para logs e para futuras decisoes de overlay. */
    public List<NeighborSnapshot> neighbors() {
        return activeNeighbors(Instant.now()).values().stream()
                .sorted(Comparator.comparing(neighbor -> neighbor.nodeId().asText()))
                .map(Neighbor::snapshot).toList();
    }

    private Map<LuffyNodeId, Neighbor> activeNeighbors(Instant now) {
        Map<LuffyNodeId, Neighbor> result = new HashMap<>();
        for (LuffyNodeId nodeId : connectedLuffys.listConnectedNodeIds()) {
            List<ConnectedLuffyRegistry.ConnectedLuffy> bootstrapConnections = connectedLuffys.findConnections(nodeId).stream()
                    .filter(connection -> connection.sourceTorrent().equals(bootstrapTorrent))
                    .filter(connection -> liveConnections.contains(connection.connectionKey()))
                    .toList();
            if (bootstrapConnections.isEmpty()) continue;
            ConnectedLuffyRegistry.ConnectedLuffy best = bootstrapConnections.stream()
                    .max(Comparator.comparingInt(this::connectionUsefulness)
                            .thenComparing(ConnectedLuffyRegistry.ConnectedLuffy::connectedAt))
                    .orElseThrow();
            int sessions = connectedLuffys.findConnections(nodeId).size();
            result.put(nodeId, new Neighbor(nodeId, best, sessions, lastRenewed(nodeId, now),
                    rendezvousProtected.contains(nodeId), latencyByNode.get(nodeId)));
        }
        return result;
    }

    private Instant lastRenewed(LuffyNodeId nodeId, Instant fallback) {
        synchronized (monitor) { return lastRenewedAt.computeIfAbsent(nodeId, ignored -> fallback); }
    }

    private Optional<LuffyNodeId> renewOne(Map<LuffyNodeId, Neighbor> neighbors, Instant now) {
        if (neighbors.isEmpty()) return Optional.empty();
        Optional<Neighbor> candidate = neighbors.values().stream()
                .filter(neighbor -> !neighbor.rendezvousProtected())
                .filter(neighbor -> !now.isBefore(neighbor.lastRenewedAt().plus(configuration.renewalInterval())))
                .min(Comparator.comparing(Neighbor::lastRenewedAt)
                        .thenComparing(neighbor -> neighbor.nodeId().asText()));
        if (candidate.isEmpty()) return Optional.empty();
        LuffyNodeId nodeId = candidate.get().nodeId();
        synchronized (monitor) { lastRenewedAt.put(nodeId, now); }
        diagnostics.log(P2pDiagnostics.Layer.CONNECTIVITY, "[BOOTSTRAP] renovacao gradual de vizinho: nodeId="
                + abbreviated(nodeId) + "; conexoes reais mantidas sem churn.");
        return Optional.of(nodeId);
    }

    private Optional<LuffyNodeId> evictOne(Map<LuffyNodeId, Neighbor> neighbors, Instant now) {
        Optional<Neighbor> candidate = neighbors.values().stream()
                .filter(neighbor -> !neighbor.rendezvousProtected())
                .filter(neighbor -> liveConnections.contains(neighbor.connection().connectionKey()))
                .min(Comparator.<Neighbor>comparingInt(neighbor -> selectionScore(neighbor, now))
                        .thenComparing(neighbor -> neighbor.nodeId().asText()));
        if (candidate.isEmpty()) {
            diagnostics.log(P2pDiagnostics.Layer.CONNECTIVITY, "[BOOTSTRAP] limite de vizinhos excedido, mas todas as conexoes estao protegidas por rendezvous ativo.");
            return Optional.empty();
        }
        Neighbor selected = candidate.get();
        if (!liveConnections.close(selected.connection().connectionKey())) return Optional.empty();
        connectedLuffys.removeConnection(selected.connection().connectionKey());
        rendezvousProtected.remove(selected.nodeId());
        latencyByNode.remove(selected.nodeId());
        synchronized (monitor) { lastRenewedAt.remove(selected.nodeId()); }
        diagnostics.log(P2pDiagnostics.Layer.CONNECTIVITY, "[BOOTSTRAP] vizinho dispensado acima do limite: nodeId="
                + abbreviated(selected.nodeId()) + "; ativos=" + neighbors.size() + "/" + configuration.maximumNeighbors()
                + "; remocao gradual de uma conexao menos util.");
        return Optional.of(selected.nodeId());
    }

    private boolean requestDiscoveryIfAllowed(int currentNeighbors, Instant now) {
        synchronized (monitor) {
            if (pendingDiscoveries.get() >= configuration.maximumPendingAttempts()) return false;
            if (now.isBefore(nextDiscoveryAllowedAt)) return false;
            pendingDiscoveries.incrementAndGet();
            nextDiscoveryAllowedAt = now.plus(configuration.discoveryBackoff());
        }
        Instant retryAllowedAt = now.plus(configuration.discoveryBackoff());
        diagnostics.log(P2pDiagnostics.Layer.DISCOVERY, "[BOOTSTRAP] vizinhos=" + currentNeighbors + "/"
                + configuration.targetNeighbors() + "; DHT/PEX solicitado de forma limitada para completar a malha.");
        try {
            CompletionStage<Integer> request = Objects.requireNonNull(discovery.requestPeers(), "discovery retornou null");
            request.whenComplete((ignored, error) -> {
                pendingDiscoveries.updateAndGet(value -> Math.max(0, value - 1));
                if (error != null) {
                    nextDiscoveryAllowedAt = retryAllowedAt;
                    diagnostics.log(P2pDiagnostics.Layer.DISCOVERY, "[BOOTSTRAP] descoberta de vizinhos falhou; backoff="
                            + configuration.discoveryBackoff().toSeconds() + "s; erro=" + message(error) + ".");
                }
            });
            return true;
        } catch (RuntimeException error) {
            pendingDiscoveries.updateAndGet(value -> Math.max(0, value - 1));
            nextDiscoveryAllowedAt = now.plus(configuration.discoveryBackoff());
            diagnostics.log(P2pDiagnostics.Layer.DISCOVERY, "[BOOTSTRAP] descoberta de vizinhos nao iniciou; backoff="
                    + configuration.discoveryBackoff().toSeconds() + "s; erro=" + message(error) + ".");
            return false;
        }
    }

    private int connectionUsefulness(ConnectedLuffyRegistry.ConnectedLuffy connection) {
        LuffyPeerCapabilities capabilities = connection.capabilities();
        int score = 0;
        if (capabilities.supportsRoute()) score += 4;
        if (capabilities.supportsDistributedRendezvous()) score += 8;
        if (capabilities.supportsUtp()) score += 2;
        return score;
    }

    private int selectionScore(Neighbor neighbor, Instant now) {
        LuffyPeerCapabilities capabilities = neighbor.connection().capabilities();
        int score = 1_000; // conexao ativa: requisito minimo de todo candidato
        if (capabilities.supportsRoute()) score += 120;
        if (capabilities.supportsDistributedRendezvous()) score += 180;
        if (capabilities.supportsUtp()) score += 30;
        long stableMinutes = Math.max(0, Duration.between(neighbor.connection().connectedAt(), now).toMinutes());
        score += (int) Math.min(120, stableMinutes);
        score += Math.min(30, neighbor.sessionCount() * 3);
        if (neighbor.latency() != null) score += latencyScore(neighbor.latency());
        return score;
    }

    private static int latencyScore(Duration latency) {
        long millis = Math.max(0, latency.toMillis());
        if (millis <= 50) return 60;
        if (millis <= 150) return 40;
        if (millis <= 400) return 20;
        return 0;
    }

    private static String abbreviated(LuffyNodeId nodeId) {
        String value = nodeId.asText();
        return value.substring(0, Math.min(12, value.length())) + "...";
    }

    private static String message(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    @FunctionalInterface
    public interface NeighborDiscovery {
        CompletionStage<Integer> requestPeers();
    }

    public record NeighborSnapshot(LuffyNodeId nodeId, boolean active, int sessionCount,
                                   boolean supportsRoute, boolean supportsRendezvous,
                                   boolean rendezvousProtected, Instant connectedAt, Instant lastRenewedAt) { }

    public record NeighborMaintenanceResult(int activeNeighbors, int minimumNeighbors, int targetNeighbors,
                                            int maximumNeighbors, Optional<LuffyNodeId> renewed,
                                            Optional<LuffyNodeId> evicted, boolean discoveryRequested,
                                            int pendingDiscoveryAttempts) {
        private static NeighborMaintenanceResult inactive() {
            return new NeighborMaintenanceResult(0, 0, 0, 0, Optional.empty(), Optional.empty(), false, 0);
        }
    }

    private record Neighbor(LuffyNodeId nodeId, ConnectedLuffyRegistry.ConnectedLuffy connection,
                            int sessionCount, Instant lastRenewedAt, boolean rendezvousProtected,
                            Duration latency) {
        private NeighborSnapshot snapshot() {
            LuffyPeerCapabilities capabilities = connection.capabilities();
            return new NeighborSnapshot(nodeId, true, sessionCount, capabilities.supportsRoute(),
                    capabilities.supportsDistributedRendezvous(), rendezvousProtected,
                    connection.connectedAt(), lastRenewedAt);
        }
    }
}
