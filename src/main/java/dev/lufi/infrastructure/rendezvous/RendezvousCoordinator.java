package dev.lufi.infrastructure.rendezvous;

import bt.metainfo.TorrentId;
import dev.lufi.infrastructure.P2pDiagnostics;
import dev.lufi.infrastructure.identity.ConnectedLuffyRegistry;
import dev.lufi.infrastructure.identity.LuffyNodeId;
import dev.lufi.infrastructure.identity.LuffyNodeIdentity;
import dev.lufi.infrastructure.security.AbuseProtectionService;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Conecta o controle lf_rendezvous ao executor BEP55/uTP ja existente. Z so
 * repassa comandos pequenos; o executor abre o uTP direto entre A e B.
 */
public final class RendezvousCoordinator {
    public static final Duration DEFAULT_SESSION_TIMEOUT = Duration.ofSeconds(30);

    private final LuffyNodeIdentity localIdentity;
    private final ConnectedLuffyRegistry connectedLuffys;
    private final RendezvousSessionRegistry sessions;
    private final ControlTransport routeTransport;
    private final DirectControlTransport directTransport;
    private final RendezvousEndpointProvider endpointProvider;
    private final RendezvousPunchExecutor punchExecutor;
    private final P2pDiagnostics diagnostics;
    private final Duration sessionTimeout;
    private final RendezvousFallbackPolicy fallbackPolicy;
    private final AbuseProtectionService abuseProtection;
    private final Map<UUID, LuffyRendezvousMessage.RendezvousEndpoint> targetEndpoints = new ConcurrentHashMap<>();
    private final Map<UUID, FallbackPlan> fallbackPlans = new ConcurrentHashMap<>();
    private volatile BiConsumer<RendezvousSession, RendezvousState> sessionFinishedListener = (session, state) -> { };

    public RendezvousCoordinator(LuffyNodeIdentity localIdentity, ConnectedLuffyRegistry connectedLuffys,
                                 ControlTransport routeTransport, P2pDiagnostics diagnostics) {
        this(localIdentity, connectedLuffys, new RendezvousSessionRegistry(), routeTransport, (ignored, message) -> false,
                Optional::<LuffyRendezvousMessage.RendezvousEndpoint>empty,
                (torrent, endpoint) -> CompletableFuture.failedFuture(new IllegalStateException("executor BEP55/uTP indisponivel")),
                diagnostics, DEFAULT_SESSION_TIMEOUT, RendezvousFallbackPolicy.defaults());
    }

    RendezvousCoordinator(LuffyNodeIdentity localIdentity, ConnectedLuffyRegistry connectedLuffys,
                          RendezvousSessionRegistry sessions, ControlTransport routeTransport,
                          DirectControlTransport directTransport, RendezvousEndpointProvider endpointProvider,
                          RendezvousPunchExecutor punchExecutor, P2pDiagnostics diagnostics, Duration sessionTimeout) {
        this(localIdentity, connectedLuffys, sessions, routeTransport, directTransport, endpointProvider, punchExecutor,
                diagnostics, sessionTimeout, RendezvousFallbackPolicy.defaults());
    }

    RendezvousCoordinator(LuffyNodeIdentity localIdentity, ConnectedLuffyRegistry connectedLuffys,
                          RendezvousSessionRegistry sessions, ControlTransport routeTransport,
                          DirectControlTransport directTransport, RendezvousEndpointProvider endpointProvider,
                          RendezvousPunchExecutor punchExecutor, P2pDiagnostics diagnostics, Duration sessionTimeout,
                          RendezvousFallbackPolicy fallbackPolicy) {
        this(localIdentity, connectedLuffys, sessions, routeTransport, directTransport, endpointProvider, punchExecutor,
                diagnostics, sessionTimeout, fallbackPolicy, new AbuseProtectionService());
    }

    RendezvousCoordinator(LuffyNodeIdentity localIdentity, ConnectedLuffyRegistry connectedLuffys,
                          RendezvousSessionRegistry sessions, ControlTransport routeTransport,
                          DirectControlTransport directTransport, RendezvousEndpointProvider endpointProvider,
                          RendezvousPunchExecutor punchExecutor, P2pDiagnostics diagnostics, Duration sessionTimeout,
                          RendezvousFallbackPolicy fallbackPolicy, AbuseProtectionService abuseProtection) {
        this.localIdentity = Objects.requireNonNull(localIdentity, "localIdentity");
        this.connectedLuffys = Objects.requireNonNull(connectedLuffys, "connectedLuffys");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.routeTransport = Objects.requireNonNull(routeTransport, "routeTransport");
        this.directTransport = Objects.requireNonNull(directTransport, "directTransport");
        this.endpointProvider = Objects.requireNonNull(endpointProvider, "endpointProvider");
        this.punchExecutor = Objects.requireNonNull(punchExecutor, "punchExecutor");
        this.diagnostics = diagnostics == null ? new P2pDiagnostics() : diagnostics;
        this.sessionTimeout = requirePositive(sessionTimeout);
        this.fallbackPolicy = Objects.requireNonNull(fallbackPolicy, "fallbackPolicy");
        this.abuseProtection = Objects.requireNonNull(abuseProtection, "abuseProtection");
    }

    /** A inicia apenas se possui endpoint uTP externo confirmado. */
    public Optional<RendezvousSession> request(UUID routeRequestId, LuffyNodeId targetNodeId,
                                                LuffyNodeId rendezvousNodeId, TorrentId contentTorrentId) {
        Instant now = Instant.now();
        expire(now);
        if (localIdentity.nodeId().equals(targetNodeId) || localIdentity.nodeId().equals(rendezvousNodeId)) {
            throw new IllegalArgumentException("rendezvous requer requisitante, alvo e coordenador distintos");
        }
        Optional<LuffyRendezvousMessage.RendezvousEndpoint> endpoint = endpointProvider.localConfirmedUtpEndpoint();
        if (endpoint.isEmpty()) {
            diagnostics.log(P2pDiagnostics.Layer.HOLEPUNCH, "[LF_RENDEZVOUS] REQUEST suprimido: endpoint uTP externo confirmado do requisitante ausente.");
            return Optional.empty();
        }
        RendezvousSession session = new RendezvousSession(UUID.randomUUID(), routeRequestId, localIdentity.nodeId(),
                targetNodeId, rendezvousNodeId, contentTorrentId, now, now.plus(sessionTimeout), RendezvousState.CREATED);
        if (!acquire(session, "REQUEST suprimido: limite de sessoes simultaneas atingido")) return Optional.empty();
        sessions.register(session);
        sessions.transition(session.sessionId(), RendezvousState.ROUTE_ESTABLISHED, now);
        RendezvousSession active = sessions.find(session.sessionId(), now).orElseThrow();
        if (routeTransport.send(LuffyRendezvousMessage.request(active, endpoint.get()))) {
            log("REQUEST enviado", active);
            diagnostics.event(P2pDiagnostics.Category.LF_RENDEZVOUS, "RENDEZVOUS_START",
                    "sessionId", abbreviated(active.sessionId()), "requester", abbreviated(active.requesterNodeId()),
                    "target", abbreviated(active.targetNodeId()), "via", abbreviated(active.rendezvousNodeId()));
            return Optional.of(active);
        }
        finish(active, RendezvousState.FAILED, now);
        diagnostics.log(P2pDiagnostics.Layer.CONNECTIVITY, "[LF_RENDEZVOUS] REQUEST falhou: rota indisponivel; sessionId="
                + abbreviated(session.sessionId()) + ".");
        return Optional.empty();
    }

    /**
     * Aceita candidatos ja retornados por uma mesma busca. A troca ocorre
     * somente se o coordenador falhar antes do estado PUNCHING.
     */
    public Optional<RendezvousSession> requestWithFallback(LuffyNodeId targetNodeId, TorrentId contentTorrentId,
                                                            java.util.List<RouteCandidate> candidates) {
        Objects.requireNonNull(candidates, "candidates");
        FallbackPlan plan = new FallbackPlan(targetNodeId, contentTorrentId, candidates, fallbackPolicy.maximumCoordinatorAttempts());
        return startNextCandidate(plan);
    }

    /** Chamado depois que a extensao validou o sentido e o destino da mensagem. */
    public void onMessage(LuffyRendezvousMessage message) {
        Objects.requireNonNull(message, "message");
        Instant now = Instant.now();
        expire(now);
        switch (message.direction()) {
            case TO_RENDEZVOUS -> { if (message.rendezvousNodeId().equals(localIdentity.nodeId())) handleAtRendezvous(message, now); }
            case TO_REQUESTER -> { if (message.requesterNodeId().equals(localIdentity.nodeId())) handleAtRequester(message, now); }
            case TO_TARGET -> { if (message.targetNodeId().equals(localIdentity.nodeId())) handleAtTarget(message, now); }
        }
    }

    public int expire(Instant now) {
        int expired = sessions.expire(Objects.requireNonNull(now, "now"));
        abuseProtection.expireRendezvousSessions(now);
        targetEndpoints.entrySet().removeIf(entry -> sessions.find(entry.getKey(), now).isEmpty());
        fallbackPlans.entrySet().removeIf(entry -> sessions.find(entry.getKey(), now).isEmpty());
        if (expired > 0) diagnostics.log(P2pDiagnostics.Layer.CONNECTIVITY,
                "[LF_RENDEZVOUS] sessoes expiradas removidas=" + expired + ".");
        return expired;
    }
    public int activeSessionCount() { return sessions.size(); }
    public RendezvousSessionRegistry sessionRegistry() { return sessions; }
    /** Observador de término para a camada de conectividade; não participa do protocolo. */
    public void setSessionFinishedListener(BiConsumer<RendezvousSession, RendezvousState> listener) {
        sessionFinishedListener = listener == null ? (session, state) -> { } : listener;
    }

    private void handleAtRendezvous(LuffyRendezvousMessage message, Instant now) {
        switch (message.type()) {
            case RENDEZVOUS_REQUEST -> handleRequestAtRendezvous(message, now);
            case RENDEZVOUS_ACCEPTED -> handleAcceptedAtRendezvous(message, now);
            case RENDEZVOUS_RESULT -> finishIfPresent(message, message.code() == LuffyRendezvousMessage.Code.PUNCH_SUCCEEDED
                    ? RendezvousState.CONNECTED : RendezvousState.FAILED, now);
            case RENDEZVOUS_ERROR -> relayFailureToRequester(message, now);
            default -> rejectProtocol(message, now);
        }
    }

    private void handleRequestAtRendezvous(LuffyRendezvousMessage message, Instant now) {
        RendezvousSession session = sessionFrom(message, now);
        if (!abuseProtection.allowRendezvousRequest(message.requesterNodeId().asText(), now)
                || !acquire(session, "REQUEST rejeitado: limite de rendezvous atingido")) return;
        sessions.register(session);
        sessions.transition(session.sessionId(), RendezvousState.ROUTE_ESTABLISHED, now);
        if (message.endpoint().isEmpty()) { rejectTarget(session, LuffyRendezvousMessage.Code.TARGET_REJECTED, now); return; }
        Optional<ConnectedLuffyRegistry.ConnectedLuffy> target = connectedLuffys.findBestControlConnection(message.targetNodeId())
                .filter(connection -> connection.capabilities().supportsDistributedRendezvous());
        if (target.isEmpty()) { rejectTarget(session, LuffyRendezvousMessage.Code.TARGET_UNAVAILABLE, now); return; }
        sessions.transition(session.sessionId(), RendezvousState.TARGET_CONFIRMED, now);
        RendezvousSession active = sessions.find(session.sessionId(), now).orElse(session);
        if (!directTransport.send(message.targetNodeId(), LuffyRendezvousMessage.prepare(active,
                LuffyRendezvousMessage.Direction.TO_TARGET, message.endpoint().orElseThrow()))) {
            rejectTarget(active, LuffyRendezvousMessage.Code.ROUTE_UNAVAILABLE, now);
            return;
        }
        log("TARGET PREPARE enviado", active);
    }

    /** B devolve seu endpoint uTP confirmado a Z; Z so entao libera A. */
    private void handleAcceptedAtRendezvous(LuffyRendezvousMessage message, Instant now) {
        Optional<RendezvousSession> current = sessions.find(message.sessionId(), now);
        if (current.isEmpty() || !matches(current.get(), message)) return;
        RendezvousSession session = current.get();
        if (message.endpoint().isPresent()) {
            targetEndpoints.put(session.sessionId(), message.endpoint().orElseThrow());
            sessions.transition(session.sessionId(), RendezvousState.PREPARING, now);
            RendezvousSession active = sessions.find(session.sessionId(), now).orElse(session);
            if (!routeTransport.send(LuffyRendezvousMessage.prepare(active, LuffyRendezvousMessage.Direction.TO_REQUESTER,
                    message.endpoint().orElseThrow()))) {
                finish(active, RendezvousState.FAILED, now);
            } else log("TARGET endpoint confirmado", active);
            return;
        }
        sessions.transition(session.sessionId(), RendezvousState.PUNCHING, now);
        sessions.find(session.sessionId(), now).ifPresent(active -> log("PUNCHING iniciado", active));
    }

    private void handleAtRequester(LuffyRendezvousMessage message, Instant now) {
        Optional<RendezvousSession> current = sessions.find(message.sessionId(), now);
        if (current.isEmpty() || !matches(current.get(), message)) return;
        RendezvousSession session = current.get();
        switch (message.type()) {
            case RENDEZVOUS_PREPARE -> {
                LuffyRendezvousMessage.RendezvousEndpoint targetEndpoint = message.endpoint().orElse(null);
                if (targetEndpoint == null) { failAndReport(session, LuffyRendezvousMessage.Code.TARGET_REJECTED, now, false); return; }
                sessions.transition(session.sessionId(), RendezvousState.TARGET_CONFIRMED, now);
                sessions.transition(session.sessionId(), RendezvousState.PREPARING, now);
                sessions.transition(session.sessionId(), RendezvousState.PUNCHING, now);
                RendezvousSession active = sessions.find(session.sessionId(), now).orElse(session);
                routeTransport.send(LuffyRendezvousMessage.accepted(active, null));
                startPunch(active, targetEndpoint, false);
            }
            case RENDEZVOUS_REJECTED, RENDEZVOUS_ERROR -> {
                if (!tryFallback(session, now)) finish(session, RendezvousState.FAILED, now);
            }
            default -> rejectProtocol(message, now);
        }
    }

    /** B inicia uTP para A imediatamente apos confirmar seu proprio endpoint. */
    private void handleAtTarget(LuffyRendezvousMessage message, Instant now) {
        if (message.type() != LuffyRendezvousMessage.Type.RENDEZVOUS_PREPARE || message.endpoint().isEmpty()) return;
        RendezvousSession session = sessionFrom(message, now);
        if (!acquire(session, "PREPARE rejeitado: limite de sessoes simultaneas atingido")) return;
        sessions.register(session);
        sessions.transition(session.sessionId(), RendezvousState.ROUTE_ESTABLISHED, now);
        Optional<LuffyRendezvousMessage.RendezvousEndpoint> localEndpoint = endpointProvider.localConfirmedUtpEndpoint();
        if (localEndpoint.isEmpty()) { failAndReport(session, LuffyRendezvousMessage.Code.TARGET_UNAVAILABLE, now, true); return; }
        sessions.transition(session.sessionId(), RendezvousState.TARGET_CONFIRMED, now);
        sessions.transition(session.sessionId(), RendezvousState.PREPARING, now);
        sessions.transition(session.sessionId(), RendezvousState.PUNCHING, now);
        RendezvousSession active = sessions.find(session.sessionId(), now).orElse(session);
        if (!directTransport.send(active.rendezvousNodeId(), LuffyRendezvousMessage.accepted(active, localEndpoint.get()))) {
            finish(active, RendezvousState.FAILED, now);
            return;
        }
        startPunch(active, message.endpoint().orElseThrow(), true);
    }

    /** O future so completa depois de a ponte obter aceite do bt-core. */
    private void startPunch(RendezvousSession session, LuffyRendezvousMessage.RendezvousEndpoint endpoint, boolean fromTarget) {
        String localEndpoint = endpointProvider.localConfirmedUtpEndpoint().map(RendezvousCoordinator::display).orElse("unavailable");
        diagnostics.event(P2pDiagnostics.Category.LF_UTP, "PUNCH_START",
                "sessionId", abbreviated(session.sessionId()), "localUtpEndpoint", localEndpoint,
                "remoteUtpEndpoint", display(endpoint));
        punchExecutor.start(session.contentTorrentId(), endpoint).whenComplete((ignored, error) -> {
            Instant now = Instant.now();
            if (error == null) {
                sessions.transition(session.sessionId(), RendezvousState.BITTORRENT_HANDSHAKING, now);
                finish(session, RendezvousState.CONNECTED, now);
                if (!fromTarget) routeTransport.send(LuffyRendezvousMessage.result(session,
                        LuffyRendezvousMessage.Direction.TO_RENDEZVOUS, LuffyRendezvousMessage.Code.PUNCH_SUCCEEDED));
                log("BITTORRENT HANDSHAKE aceito", session);
                diagnostics.event(P2pDiagnostics.Category.LF_BT_BRIDGE, "BITTORRENT_CONNECTED",
                        "sessionId", abbreviated(session.sessionId()), "torrentId", abbreviated(session.contentTorrentId()),
                        "transport", "UTP");
            } else {
                failAndReport(session, LuffyRendezvousMessage.Code.PUNCH_FAILED, now, fromTarget);
            }
        });
    }

    private void failAndReport(RendezvousSession session, LuffyRendezvousMessage.Code code, Instant now, boolean directToRendezvous) {
        finish(session, RendezvousState.FAILED, now);
        LuffyRendezvousMessage error = LuffyRendezvousMessage.error(session, LuffyRendezvousMessage.Direction.TO_RENDEZVOUS, code);
        if (directToRendezvous) directTransport.send(session.rendezvousNodeId(), error); else routeTransport.send(error);
    }
    private void relayFailureToRequester(LuffyRendezvousMessage message, Instant now) {
        sessions.find(message.sessionId(), now).ifPresent(session -> {
            finish(session, RendezvousState.FAILED, now);
            routeTransport.send(LuffyRendezvousMessage.error(session, LuffyRendezvousMessage.Direction.TO_REQUESTER, message.code()));
        });
    }
    private Optional<RendezvousSession> startNextCandidate(FallbackPlan plan) {
        while (plan.hasNext()) {
            RouteCandidate candidate = plan.next();
            Optional<RendezvousSession> session = request(candidate.routeRequestId(), plan.targetNodeId(),
                    candidate.rendezvousNodeId(), plan.contentTorrentId());
            if (session.isPresent()) {
                fallbackPlans.put(session.get().sessionId(), plan);
                return session;
            }
        }
        diagnostics.log(P2pDiagnostics.Layer.HOLEPUNCH, "[LF_RENDEZVOUS] FALLBACK esgotado: nenhum candidato restante antes do inicio do punch.");
        return Optional.empty();
    }
    private boolean tryFallback(RendezvousSession failed, Instant now) {
        FallbackPlan plan = fallbackPlans.remove(failed.sessionId());
        if (plan == null || failed.state() == RendezvousState.PUNCHING || failed.state() == RendezvousState.BITTORRENT_HANDSHAKING) return false;
        finish(failed, RendezvousState.FAILED, now);
        Optional<RendezvousSession> replacement = startNextCandidate(plan);
        if (replacement.isPresent()) diagnostics.log(P2pDiagnostics.Layer.HOLEPUNCH, "[LF_RENDEZVOUS] FALLBACK: coordenador anterior falhou; novo sessionId="
                + abbreviated(replacement.get().sessionId()) + ".");
        return replacement.isPresent();
    }
    private void rejectTarget(RendezvousSession session, LuffyRendezvousMessage.Code code, Instant now) {
        finish(session, RendezvousState.FAILED, now);
        routeTransport.send(LuffyRendezvousMessage.rejected(session, code));
    }
    private void finishIfPresent(LuffyRendezvousMessage message, RendezvousState terminal, Instant now) {
        sessions.find(message.sessionId(), now).ifPresent(session -> finish(session, terminal, now));
    }
    private void finish(RendezvousSession session, RendezvousState terminal, Instant now) {
        sessions.finish(session.sessionId(), terminal, now).ifPresent(finished -> {
            try {
                sessionFinishedListener.accept(finished, terminal);
            } catch (RuntimeException ignored) {
                // Observabilidade/fallback nunca pode derrubar o processamento da mensagem BEP 10.
            }
        });
        targetEndpoints.remove(session.sessionId());
        abuseProtection.releaseRendezvousSession(session.sessionId());
        if (terminal.terminal()) fallbackPlans.remove(session.sessionId());
    }
    private boolean acquire(RendezvousSession session, String reason) {
        if (abuseProtection.tryAcquireRendezvousSession(session.sessionId(), session.expiresAt())) return true;
        diagnostics.log(P2pDiagnostics.Layer.CONNECTIVITY, "[SECURITY] LF_RENDEZVOUS " + reason + ".");
        return false;
    }
    private void rejectProtocol(LuffyRendezvousMessage message, Instant now) {
        sessions.find(message.sessionId(), now).ifPresent(session -> failAndReport(session, LuffyRendezvousMessage.Code.PROTOCOL_ERROR, now, false));
    }
    private RendezvousSession sessionFrom(LuffyRendezvousMessage message, Instant now) {
        return new RendezvousSession(message.sessionId(), message.routeRequestId(), message.requesterNodeId(),
                message.targetNodeId(), message.rendezvousNodeId(), message.contentTorrentId(), now,
                now.plus(sessionTimeout), RendezvousState.CREATED);
    }
    private static boolean matches(RendezvousSession session, LuffyRendezvousMessage message) {
        return session.routeRequestId().equals(message.routeRequestId()) && session.requesterNodeId().equals(message.requesterNodeId())
                && session.targetNodeId().equals(message.targetNodeId()) && session.rendezvousNodeId().equals(message.rendezvousNodeId())
                && session.contentTorrentId().equals(message.contentTorrentId());
    }
    private void log(String event, RendezvousSession session) {
        diagnostics.log(P2pDiagnostics.Layer.HOLEPUNCH, "[LF_RENDEZVOUS] " + event + ": sessionId="
                + abbreviated(session.sessionId()) + "; route=" + abbreviated(session.routeRequestId()) + ".");
    }
    private static Duration requirePositive(Duration value) { Objects.requireNonNull(value, "sessionTimeout"); if (value.isZero() || value.isNegative()) throw new IllegalArgumentException("timeout de rendezvous invalido"); return value; }
    private static String abbreviated(UUID value) { return value.toString().substring(0, 8); }
    private static String abbreviated(LuffyNodeId value) { return value.asText().substring(0, 12) + "..."; }
    private static String abbreviated(TorrentId value) {
        return java.util.HexFormat.of().formatHex(value.getBytes()).substring(0, 12) + "...";
    }
    private static String display(LuffyRendezvousMessage.RendezvousEndpoint endpoint) {
        String host = endpoint.address() instanceof java.net.Inet6Address ? "[" + endpoint.address().getHostAddress() + "]"
                : endpoint.address().getHostAddress();
        return host + ":" + endpoint.port();
    }

    @FunctionalInterface public interface ControlTransport { boolean send(LuffyRendezvousMessage message); }
    @FunctionalInterface public interface DirectControlTransport { boolean send(LuffyNodeId destination, LuffyRendezvousMessage message); }
    public record RouteCandidate(UUID routeRequestId, LuffyNodeId rendezvousNodeId) {
        public RouteCandidate { Objects.requireNonNull(routeRequestId, "routeRequestId"); Objects.requireNonNull(rendezvousNodeId, "rendezvousNodeId"); }
    }
    private static final class FallbackPlan {
        private final LuffyNodeId targetNodeId;
        private final TorrentId contentTorrentId;
        private final java.util.List<RouteCandidate> candidates;
        private int index;
        private FallbackPlan(LuffyNodeId targetNodeId, TorrentId contentTorrentId, java.util.List<RouteCandidate> candidates, int maximum) {
            this.targetNodeId = Objects.requireNonNull(targetNodeId, "targetNodeId");
            this.contentTorrentId = Objects.requireNonNull(contentTorrentId, "contentTorrentId");
            this.candidates = java.util.List.copyOf(candidates.stream().limit(maximum).toList());
        }
        private LuffyNodeId targetNodeId() { return targetNodeId; }
        private TorrentId contentTorrentId() { return contentTorrentId; }
        private boolean hasNext() { return index < candidates.size(); }
        private RouteCandidate next() { return candidates.get(index++); }
    }
}
