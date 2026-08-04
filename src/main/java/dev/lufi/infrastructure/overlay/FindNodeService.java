package dev.lufi.infrastructure.overlay;

import bt.metainfo.TorrentId;
import bt.net.ConnectionKey;
import dev.lufi.infrastructure.P2pDiagnostics;
import dev.lufi.infrastructure.identity.ConnectedLuffyRegistry;
import dev.lufi.infrastructure.identity.LuffyNodeId;
import dev.lufi.infrastructure.identity.LuffyNodeIdentity;
import dev.lufi.infrastructure.identity.LuffyPeerCapabilities;
import dev.lufi.infrastructure.security.AbuseProtectionService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * Busca um LuffyNodeId por saltos controlados no swarm oficial. O servico so
 * usa ConnectionKeys ja vivos e validados: nao abre socket, nao toca DHT,
 * metadata, requests ou pieces.
 */
public final class FindNodeService implements AutoCloseable {
    public static final int DEFAULT_TTL = FindNodeRoutingConfig.INITIAL_DEFAULT_TTL;
    public static final int MAXIMUM_TTL = FindNodeRoutingConfig.INITIAL_MAXIMUM_TTL;
    public static final int MAXIMUM_FORWARD_PEERS = FindNodeRoutingConfig.INITIAL_MAXIMUM_FORWARD_PEERS;
    public static final Duration MAX_REQUEST_AGE = Duration.ofMinutes(2);
    public static final Duration MAX_FUTURE_SKEW = Duration.ofSeconds(30);
    public static final Duration SEARCH_TIMEOUT = FindNodeRoutingConfig.INITIAL_SEARCH_TIMEOUT;
    public static final Duration ROUTE_CACHE_TTL = FindNodeRoutingConfig.INITIAL_ROUTE_CACHE_TTL;

    private final LuffyNodeIdentity localIdentity;
    private final Supplier<LuffyPeerCapabilities> localCapabilities;
    private final TorrentId bootstrapTorrent;
    private final ConnectedLuffyRegistry connectedLuffys;
    private final RouteMessageDispatcher dispatcher;
    private final P2pDiagnostics diagnostics;
    private final RouteRequestCache requestCache;
    private final ReverseRouteRegistry reverseRoutes;
    private final RouteReplyTracker replyTracker;
    private final OverlayRoutePathRegistry routePaths;
    private final FindNodeRoutingConfig routingConfig;
    private final RouteForwardingLimiter forwardingLimiter;
    private final AbuseProtectionService abuseProtection;

    public FindNodeService(LuffyNodeIdentity localIdentity, Supplier<LuffyPeerCapabilities> localCapabilities,
                           TorrentId bootstrapTorrent, ConnectedLuffyRegistry connectedLuffys,
                           RouteMessageDispatcher dispatcher, P2pDiagnostics diagnostics) {
        this(localIdentity, localCapabilities, bootstrapTorrent, connectedLuffys, dispatcher, diagnostics,
                new RouteRequestCache(), new ReverseRouteRegistry(), new RouteReplyTracker(), new OverlayRoutePathRegistry(),
                FindNodeRoutingConfig.defaults(), new AbuseProtectionService());
    }

    FindNodeService(LuffyNodeIdentity localIdentity, Supplier<LuffyPeerCapabilities> localCapabilities,
                    TorrentId bootstrapTorrent, ConnectedLuffyRegistry connectedLuffys,
                    RouteMessageDispatcher dispatcher, P2pDiagnostics diagnostics,
                    FindNodeRoutingConfig routingConfig) {
        this(localIdentity, localCapabilities, bootstrapTorrent, connectedLuffys, dispatcher, diagnostics,
                new RouteRequestCache(), new ReverseRouteRegistry(), new RouteReplyTracker(), new OverlayRoutePathRegistry(), routingConfig);
    }

    FindNodeService(LuffyNodeIdentity localIdentity, Supplier<LuffyPeerCapabilities> localCapabilities,
                    TorrentId bootstrapTorrent, ConnectedLuffyRegistry connectedLuffys,
                    RouteMessageDispatcher dispatcher, P2pDiagnostics diagnostics,
                    FindNodeRoutingConfig routingConfig, AbuseProtectionService abuseProtection) {
        this(localIdentity, localCapabilities, bootstrapTorrent, connectedLuffys, dispatcher, diagnostics,
                new RouteRequestCache(), new ReverseRouteRegistry(), new RouteReplyTracker(), new OverlayRoutePathRegistry(),
                routingConfig, abuseProtection);
    }

    FindNodeService(LuffyNodeIdentity localIdentity, Supplier<LuffyPeerCapabilities> localCapabilities,
                    TorrentId bootstrapTorrent, ConnectedLuffyRegistry connectedLuffys,
                    RouteMessageDispatcher dispatcher, P2pDiagnostics diagnostics, RouteRequestCache requestCache,
                    ReverseRouteRegistry reverseRoutes, RouteReplyTracker replyTracker, OverlayRoutePathRegistry routePaths,
                    FindNodeRoutingConfig routingConfig) {
        this(localIdentity, localCapabilities, bootstrapTorrent, connectedLuffys, dispatcher, diagnostics, requestCache,
                reverseRoutes, replyTracker, routePaths, routingConfig, new AbuseProtectionService());
    }

    FindNodeService(LuffyNodeIdentity localIdentity, Supplier<LuffyPeerCapabilities> localCapabilities,
                    TorrentId bootstrapTorrent, ConnectedLuffyRegistry connectedLuffys,
                    RouteMessageDispatcher dispatcher, P2pDiagnostics diagnostics, RouteRequestCache requestCache,
                    ReverseRouteRegistry reverseRoutes, RouteReplyTracker replyTracker, OverlayRoutePathRegistry routePaths,
                    FindNodeRoutingConfig routingConfig, AbuseProtectionService abuseProtection) {
        this.localIdentity = Objects.requireNonNull(localIdentity, "localIdentity");
        this.localCapabilities = Objects.requireNonNull(localCapabilities, "localCapabilities");
        this.bootstrapTorrent = Objects.requireNonNull(bootstrapTorrent, "bootstrapTorrent");
        this.connectedLuffys = Objects.requireNonNull(connectedLuffys, "connectedLuffys");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.diagnostics = diagnostics == null ? new P2pDiagnostics() : diagnostics;
        this.requestCache = Objects.requireNonNull(requestCache, "requestCache");
        this.reverseRoutes = Objects.requireNonNull(reverseRoutes, "reverseRoutes");
        this.replyTracker = Objects.requireNonNull(replyTracker, "replyTracker");
        this.routePaths = Objects.requireNonNull(routePaths, "routePaths");
        this.routingConfig = Objects.requireNonNull(routingConfig, "routingConfig");
        this.forwardingLimiter = new RouteForwardingLimiter(routingConfig);
        this.abuseProtection = Objects.requireNonNull(abuseProtection, "abuseProtection");
    }

    public CompletionStage<RouteSearchResult> findNode(LuffyNodeId targetNodeId, String contentInfoHash) {
        return startFindNode(targetNodeId, contentInfoHash).result();
    }

    /** Mantem o requestId da rota vencedora para protocolos de controle subsequentes. */
    public RouteSearch startFindNode(LuffyNodeId targetNodeId, String contentInfoHash) {
        Instant now = Instant.now();
        expire(now);
        if (!abuseProtection.tryAcquireRouteSearch()) {
            diagnostics.log(P2pDiagnostics.Layer.CONNECTIVITY, "[SECURITY] FIND_NODE suprimido: limite de buscas simultaneas atingido.");
            UUID requestId = UUID.randomUUID();
            return new RouteSearch(requestId, java.util.concurrent.CompletableFuture.completedFuture(
                    new RouteSearchResult.RouteError(Objects.requireNonNull(targetNodeId, "targetNodeId"),
                            LuffyRouteMessage.RouteErrorCode.NO_ROUTE)));
        }
        LuffyRouteMessage request = LuffyRouteMessage.findNode(UUID.randomUUID(), localIdentity.nodeId(),
                Objects.requireNonNull(targetNodeId, "targetNodeId"), contentInfoHash,
                Math.min(routingConfig.defaultTtl(), abuseProtection.config().maxTtl()), now);
        Instant expiresAt = expiresAt(request, now);
        requestCache.register(request, now, expiresAt);
        CompletionStage<RouteSearchResult> result = replyTracker.track(request.requestId(), targetNodeId, expiresAt,
                routingConfig.searchTimeout());
        result.whenComplete((ignored, error) -> abuseProtection.releaseRouteSearch());
        diagnostics.log(P2pDiagnostics.Layer.CONNECTIVITY, "[LF_ROUTE] FIND_NODE iniciado: requestId="
                + abbreviated(request.requestId()) + "; target=" + abbreviated(targetNodeId) + "; ttl=" + request.ttl() + ".");
        diagnostics.event(P2pDiagnostics.Category.LF_ROUTE, "FIND_NODE_START",
                "requestId", abbreviated(request.requestId()), "targetNodeId", abbreviated(targetNodeId), "ttl", request.ttl());
        resolveFind(request, null, now);
        return new RouteSearch(request.requestId(), result);
    }

    /** Chamado pelo agente BEP 10 somente depois de a extensao lf_route ser negociada. */
    public void onMessage(LuffyRouteMessage message, ConnectionKey source) {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(source, "source");
        Instant now = Instant.now();
        expire(now);
        if (connectedLuffys.findConnection(source).isEmpty()) {
            diagnostics.log(P2pDiagnostics.Layer.CONNECTIVITY, "[LF_ROUTE] mensagem ignorada sem lf_identity validada; requestId="
                    + abbreviated(message.requestId()) + ".");
            return;
        }
        switch (message.type()) {
            case FIND_NODE -> handleFindNode(message, source, now);
            case NODE_FOUND, NODE_NOT_FOUND, ROUTE_ERROR -> handleResponse(message, source, now);
        }
    }

    @Override public void close() {
        requestCache.clear();
        reverseRoutes.clear();
        replyTracker.clear();
        routePaths.clear();
    }

    /** Rota vencedora temporaria, destinada apenas a mensagens de controle do overlay. */
    public OverlayRoutePathRegistry routePaths() { return routePaths; }

    private void handleFindNode(LuffyRouteMessage request, ConnectionKey source, Instant now) {
        String sourceKey = AbuseProtectionService.peerKey(source.getPeer().getInetAddress());
        if (!abuseProtection.allowFindNode(sourceKey, now)) {
            diagnostics.log(P2pDiagnostics.Layer.CONNECTIVITY, "[SECURITY] FIND_NODE bloqueado temporariamente: peer=" + sourceKey + ".");
            return;
        }
        if (request.ttl() > abuseProtection.config().maxTtl()) {
            abuseProtection.recordViolation(sourceKey, AbuseProtectionService.Violation.TTL_ABUSE, now);
            send(source, LuffyRouteMessage.routeError(request.requestId(), request.targetNodeId(),
                    LuffyRouteMessage.RouteErrorCode.TTL_EXHAUSTED));
            return;
        }
        if (isExpiredOrFromFuture(request, now)) {
            send(source, LuffyRouteMessage.routeError(request.requestId(), request.targetNodeId(),
                    LuffyRouteMessage.RouteErrorCode.EXPIRED));
            diagnostics.log(P2pDiagnostics.Layer.CONNECTIVITY, "[LF_ROUTE] FIND_NODE rejeitado por timestamp: requestId="
                    + abbreviated(request.requestId()) + ".");
            return;
        }
        RouteRequestCache.Registration registration = requestCache.register(request, now, expiresAt(request, now));
        if (registration == RouteRequestCache.Registration.CONFLICT) {
            send(source, LuffyRouteMessage.routeError(request.requestId(), request.targetNodeId(),
                    LuffyRouteMessage.RouteErrorCode.REQUEST_CONFLICT));
            return;
        }
        if (registration == RouteRequestCache.Registration.DUPLICATE) {
            // Retransmissoes podem ocorrer sobre uma conexao BitTorrent viva.
            // Reencaminha-las criaria fan-out repetido; responder tambem poderia
            // encerrar prematuramente uma busca que ja esta em andamento.
            diagnostics.log(P2pDiagnostics.Layer.CONNECTIVITY, "[LF_ROUTE] FIND_NODE duplicado ignorado: requestId="
                    + abbreviated(request.requestId()) + ".");
            return;
        }
        diagnostics.log(P2pDiagnostics.Layer.CONNECTIVITY, "[LF_ROUTE] FIND_NODE recebido: requestId="
                + abbreviated(request.requestId()) + "; target=" + abbreviated(request.targetNodeId())
                + "; ttl=" + request.ttl() + ".");
        resolveFind(request, source, now);
    }

    private void resolveFind(LuffyRouteMessage request, ConnectionKey previousHop, Instant now) {
        if (request.targetNodeId().equals(localIdentity.nodeId())) {
            terminal(previousHop, LuffyRouteMessage.nodeFound(request.requestId(), request.targetNodeId(), localIdentity.nodeId(), 0,
                    LuffyRouteMessage.TargetCapabilities.from(localCapabilities.get())));
            return;
        }
        Optional<ConnectedLuffyRegistry.ConnectedLuffy> target = connectedLuffys.findBestControlConnection(request.targetNodeId());
        if (target.isPresent() && connectedLuffys.findConnection(target.get().connectionKey()).isPresent()) {
            terminal(previousHop, LuffyRouteMessage.nodeFound(request.requestId(), request.targetNodeId(), localIdentity.nodeId(), 1,
                    LuffyRouteMessage.TargetCapabilities.from(target.get().capabilities())));
            return;
        }
        if (request.ttl() <= 0) {
            terminal(previousHop, LuffyRouteMessage.routeError(request.requestId(), request.targetNodeId(),
                    LuffyRouteMessage.RouteErrorCode.TTL_EXHAUSTED));
            return;
        }
        List<ConnectionKey> candidates = selectForwardPeers(request, previousHop, now);
        if (candidates.isEmpty()) {
            terminal(previousHop, LuffyRouteMessage.nodeNotFound(request.requestId(), request.targetNodeId()));
            return;
        }
        LuffyRouteMessage forwarded = request.forwardedBy(localIdentity.nodeId());
        if (previousHop != null && !abuseProtection.allowForward(
                AbuseProtectionService.peerKey(previousHop.getPeer().getInetAddress()), now)) {
            diagnostics.log(P2pDiagnostics.Layer.CONNECTIVITY, "[SECURITY] encaminhamento lf_route bloqueado por limite da origem.");
            terminal(previousHop, LuffyRouteMessage.routeError(request.requestId(), request.targetNodeId(),
                    LuffyRouteMessage.RouteErrorCode.NO_ROUTE));
            return;
        }
        Set<ConnectionKey> forwardedHops = new HashSet<>();
        for (ConnectionKey candidate : candidates) {
            if (send(candidate, forwarded)) {
                forwardingLimiter.recordForward(candidate, now);
                forwardedHops.add(candidate);
            } else {
                forwardingLimiter.recordFailure(candidate, now);
            }
        }
        if (forwardedHops.isEmpty()) {
            terminal(previousHop, LuffyRouteMessage.routeError(request.requestId(), request.targetNodeId(),
                    LuffyRouteMessage.RouteErrorCode.NO_ROUTE));
            return;
        }
        if (previousHop != null) {
            reverseRoutes.register(request.requestId(), previousHop, request.targetNodeId(),
                    expiresAt(request, now), forwardedHops);
        }
        diagnostics.log(P2pDiagnostics.Layer.CONNECTIVITY, "[LF_ROUTE] FIND_NODE encaminhado: requestId="
                + abbreviated(request.requestId()) + "; ttl=" + forwarded.ttl() + "; fan-out=" + forwardedHops.size() + ".");
    }

    private void handleResponse(LuffyRouteMessage message, ConnectionKey source, Instant now) {
        Optional<ReverseRouteRegistry.ReverseRoute> reverse = reverseRoutes.find(message.requestId(), now);
        if (reverse.isPresent()) {
            ReverseRouteRegistry.ReverseRoute route = reverse.get();
            if (!route.targetNodeId().equals(message.targetNodeId())) {
                send(source, LuffyRouteMessage.routeError(message.requestId(), message.targetNodeId(),
                        LuffyRouteMessage.RouteErrorCode.INVALID_REQUEST));
                return;
            }
            Optional<ReverseRouteRegistry.ForwardedResponse> accepted = reverseRoutes.acceptResponse(
                    message.requestId(), source, message, now);
            if (accepted.isEmpty()) return;
            LuffyRouteMessage response = accepted.get().response().type() == LuffyRouteMessage.Type.NODE_FOUND
                    ? accepted.get().response().withIncreasedDistance() : accepted.get().response();
            if (response.type() == LuffyRouteMessage.Type.NODE_FOUND) {
                routePaths.recordRelay(message.requestId(), accepted.get().previousHop(), accepted.get().source(),
                        now.plus(routingConfig.routeCacheTtl()));
            }
            send(accepted.get().previousHop(), response);
            return;
        }
        RouteSearchResult result = toResult(message);
        if (result == null || !replyTracker.complete(message.requestId(), result)) {
            diagnostics.log(P2pDiagnostics.Layer.CONNECTIVITY, "[LF_ROUTE] resposta sem busca local/reversa ativa ignorada: requestId="
                    + abbreviated(message.requestId()) + ".");
            return;
        }
        if (message.type() == LuffyRouteMessage.Type.NODE_FOUND) {
            routePaths.recordOrigin(message.requestId(), source, now.plus(routingConfig.routeCacheTtl()));
        }
        emitRouteResult(message);
        diagnostics.log(P2pDiagnostics.Layer.CONNECTIVITY, "[LF_ROUTE] resultado recebido: requestId="
                + abbreviated(message.requestId()) + "; tipo=" + message.type() + ".");
    }

    private List<ConnectionKey> selectForwardPeers(LuffyRouteMessage request, ConnectionKey previousHop, Instant now) {
        List<ConnectedLuffyRegistry.ConnectedLuffy> ordered = connectedLuffys.listConnectedNodeIds().stream()
                .filter(nodeId -> !nodeId.equals(request.requesterNodeId()))
                .filter(nodeId -> !nodeId.equals(request.targetNodeId()))
                .filter(nodeId -> !nodeId.equals(localIdentity.nodeId()))
                .filter(nodeId -> !request.routeParticipants().contains(nodeId))
                .flatMap(nodeId -> connectedLuffys.findConnections(nodeId).stream())
                .filter(connection -> connection.sourceTorrent().equals(bootstrapTorrent))
                .filter(connection -> connection.capabilities().supportsRoute())
                .filter(connection -> previousHop == null || !connection.connectionKey().equals(previousHop))
                .filter(connection -> connectedLuffys.findConnection(connection.connectionKey()).isPresent())
                .filter(connection -> dispatcher.canSend(connection.connectionKey()))
                .filter(connection -> forwardingLimiter.canForward(connection.connectionKey(), now))
                .sorted(Comparator.comparingInt(this::neighborScore).reversed()
                        .thenComparing(connection -> connection.capabilities().nodeId().asText()))
                .toList();
        List<ConnectionKey> selected = new ArrayList<>();
        Set<LuffyNodeId> selectedNodes = new HashSet<>();
        for (ConnectedLuffyRegistry.ConnectedLuffy connection : ordered) {
            if (!selectedNodes.add(connection.nodeId())) continue;
            selected.add(connection.connectionKey());
            if (selected.size() == routingConfig.maximumForwardPeers()) break;
        }
        return List.copyOf(selected);
    }

    private int neighborScore(ConnectedLuffyRegistry.ConnectedLuffy connection) {
        LuffyPeerCapabilities capabilities = connection.capabilities();
        int score = capabilities.supportsRoute() ? 100 : 0;
        if (capabilities.supportsDistributedRendezvous()) score += 20;
        if (capabilities.supportsUtp()) score += 5;
        return score;
    }

    private void terminal(ConnectionKey previousHop, LuffyRouteMessage response) {
        if (previousHop != null) {
            if (response.type() == LuffyRouteMessage.Type.NODE_FOUND) {
                routePaths.recordTerminal(response.requestId(), previousHop, Instant.now().plus(routingConfig.routeCacheTtl()));
            }
            send(previousHop, response);
            return;
        }
        RouteSearchResult result = toResult(response);
        if (result != null && replyTracker.complete(response.requestId(), result)) emitRouteResult(response);
    }

    private boolean send(ConnectionKey destination, LuffyRouteMessage message) {
        boolean sent = dispatcher.send(destination, message);
        if (!sent) diagnostics.log(P2pDiagnostics.Layer.CONNECTIVITY, "[LF_ROUTE] envio suprimido: extensao nao negociada ou conexao encerrada.");
        return sent;
    }

    private static RouteSearchResult toResult(LuffyRouteMessage message) {
        return switch (message.type()) {
            case NODE_FOUND -> new RouteSearchResult.NodeFound(message.targetNodeId(), message.rendezvousNodeId(),
                    message.distance(), message.targetCapabilities());
            case NODE_NOT_FOUND -> new RouteSearchResult.NodeNotFound(message.targetNodeId());
            case ROUTE_ERROR -> new RouteSearchResult.RouteError(message.targetNodeId(), message.errorCode());
            case FIND_NODE -> null;
        };
    }

    private void emitRouteResult(LuffyRouteMessage message) {
        switch (message.type()) {
            case NODE_FOUND -> diagnostics.event(P2pDiagnostics.Category.LF_ROUTE, "NODE_FOUND",
                    "requestId", abbreviated(message.requestId()), "targetNodeId", abbreviated(message.targetNodeId()),
                    "rendezvousNodeId", abbreviated(message.rendezvousNodeId()), "distance", message.distance());
            case NODE_NOT_FOUND -> diagnostics.event(P2pDiagnostics.Category.LF_ROUTE, "NODE_NOT_FOUND",
                    "requestId", abbreviated(message.requestId()), "targetNodeId", abbreviated(message.targetNodeId()));
            case ROUTE_ERROR -> diagnostics.event(P2pDiagnostics.Category.LF_ROUTE, "ROUTE_ERROR",
                    "requestId", abbreviated(message.requestId()), "targetNodeId", abbreviated(message.targetNodeId()),
                    "code", message.errorCode());
            case FIND_NODE -> { }
        }
    }

    private boolean isExpiredOrFromFuture(LuffyRouteMessage request, Instant now) {
        return request.createdAt().isBefore(now.minus(MAX_REQUEST_AGE))
                || request.createdAt().isAfter(now.plus(MAX_FUTURE_SKEW));
    }

    private Instant expiresAt(LuffyRouteMessage request, Instant now) {
        Instant fromRequest = request.createdAt().plus(MAX_REQUEST_AGE);
        Instant fromNow = now.plus(routingConfig.routeCacheTtl());
        return fromRequest.isBefore(fromNow) ? fromRequest : fromNow;
    }

    private void expire(Instant now) {
        requestCache.expire(now);
        reverseRoutes.expire(now);
        replyTracker.expire(now);
        routePaths.expire(now);
        forwardingLimiter.expire(now);
    }

    private static String abbreviated(UUID requestId) { return requestId.toString().substring(0, 8); }
    private static String abbreviated(LuffyNodeId nodeId) { return nodeId.asText().substring(0, 12) + "..."; }

    public interface RouteMessageDispatcher {
        boolean send(ConnectionKey destination, LuffyRouteMessage message);
        boolean canSend(ConnectionKey destination);
    }

    public record RouteSearch(UUID requestId, CompletionStage<RouteSearchResult> result) {
        public RouteSearch {
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(result, "result");
        }
    }
}
