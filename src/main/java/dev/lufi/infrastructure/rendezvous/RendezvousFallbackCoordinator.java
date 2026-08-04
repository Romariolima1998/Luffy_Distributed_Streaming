package dev.lufi.infrastructure.rendezvous;

import dev.lufi.infrastructure.P2pDiagnostics;
import dev.lufi.infrastructure.PeerConnectivityManager;
import java.time.Duration;
import java.time.Instant;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * Último fallback de conectividade. Ele não gerencia DHT, PEX, sockets ou
 * pieces: apenas avalia a elegibilidade e inicia uma sessão lf_rendezvous já
 * roteável. A conexão real continua em BEP55/uTP/bt-core.
 */
public final class RendezvousFallbackCoordinator implements PeerConnectivityManager.OverlayRendezvousFallback, AutoCloseable {
    @FunctionalInterface
    public interface OverlayStarter {
        CompletionStage<Optional<RendezvousSession>> start(PeerConnectivityManager.PeerConnectivityContext context);
    }

    @FunctionalInterface
    public interface TerminalListener {
        void finished(PeerConnectivityManager.PeerConnectivityContext context, UUID sessionId, String terminalState, String reason);
    }

    private final P2pDiagnostics diagnostics;
    private final OverlayStarter starter;
    private final BooleanSupplier localConfirmedUtpAvailable;
    private final TerminalListener terminalListener;
    private final RendezvousFallbackConfig config;
    private final Map<String, UUID> activeByEquivalentTarget = new ConcurrentHashMap<>();
    private final Map<UUID, ActiveSession> activeSessions = new ConcurrentHashMap<>();
    private final Map<UUID, ScheduledFuture<?>> expiryTimers = new ConcurrentHashMap<>();
    /** Inclui reservas ainda iniciando para que o limite seja seguro sob concorrencia. */
    private final AtomicInteger occupiedSlots = new AtomicInteger();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());
    private volatile boolean closing;

    public RendezvousFallbackCoordinator(P2pDiagnostics diagnostics, OverlayStarter starter,
                                         BooleanSupplier localConfirmedUtpAvailable, TerminalListener terminalListener) {
        this(diagnostics, starter, localConfirmedUtpAvailable, terminalListener, RendezvousFallbackConfig.defaults());
    }

    public RendezvousFallbackCoordinator(P2pDiagnostics diagnostics, OverlayStarter starter,
                                         BooleanSupplier localConfirmedUtpAvailable, TerminalListener terminalListener,
                                         RendezvousFallbackConfig config) {
        this.diagnostics = diagnostics == null ? new P2pDiagnostics() : diagnostics;
        this.starter = Objects.requireNonNull(starter, "starter");
        this.localConfirmedUtpAvailable = Objects.requireNonNull(localConfirmedUtpAvailable, "localConfirmedUtpAvailable");
        this.terminalListener = terminalListener == null ? (context, sessionId, state, reason) -> { } : terminalListener;
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    public CompletionStage<PeerConnectivityManager.OverlayRendezvousResult> onDirectConnectivityExhausted(
            PeerConnectivityManager.PeerConnectivityContext context) {
        Objects.requireNonNull(context, "context");
        String ineligible = ineligibility(context);
        if (ineligible != null) return skipped(context, ineligible);
        String key = equivalentKey(context);
        if (activeByEquivalentTarget.containsKey(key)) return skipped(context, "tentativa equivalente ja esta ativa");
        if (!reserveSlot()) return skipped(context, "limite de sessoes de rendezvous atingido: "
                + occupiedSlots.get() + "/" + config.maximumActiveSessions());
        UUID reservation = UUID.randomUUID();
        if (activeByEquivalentTarget.putIfAbsent(key, reservation) != null) {
            releaseSlot();
            return skipped(context, "tentativa equivalente ja esta ativa");
        }
        CompletionStage<Optional<RendezvousSession>> stage;
        try {
            stage = starter.start(context);
        } catch (RuntimeException error) {
            activeByEquivalentTarget.remove(key, reservation);
            releaseSlot();
            return skipped(context, "falha ao iniciar lf_rendezvous: " + describe(error));
        }
        if (stage == null) {
            activeByEquivalentTarget.remove(key, reservation);
            releaseSlot();
            return skipped(context, "inicializador lf_rendezvous nao retornou operacao assincrona");
        }
        return stage.handle((session, error) -> {
            if (error != null) {
                activeByEquivalentTarget.remove(key, reservation);
                releaseSlot();
                return skippedResult(context, "falha ao iniciar lf_rendezvous: " + describe(error));
            }
            if (session == null || session.isEmpty()) {
                activeByEquivalentTarget.remove(key, reservation);
                releaseSlot();
                return skippedResult(context, "lf_rendezvous recusou a sessao (rota ou endpoint indisponivel)");
            }
            RendezvousSession created = session.get();
            ActiveSession active = new ActiveSession(context, key, created);
            activeSessions.put(created.sessionId(), active);
            activeByEquivalentTarget.replace(key, reservation, created.sessionId());
            armExpiry(created, active);
            diagnostics.log(P2pDiagnostics.Layer.HOLEPUNCH, "[LF_RENDEZVOUS] fallback admitido: infoHash=" + context.infoHash()
                    + "; target=" + context.targetEndpoint().display() + "; nodeId="
                    + context.targetNodeId().orElseThrow().asText().substring(0, 12) + "...; sessionId=" + created.sessionId() + ".");
            return PeerConnectivityManager.OverlayRendezvousResult.started(created.sessionId());
        });
    }

    /** Notificação terminal vinda do coordenador de protocolo, sem bloquear seus handlers. */
    public void onRendezvousSessionFinished(RendezvousSession session, RendezvousState terminal) {
        if (session == null || terminal == null || !terminal.terminal()) return;
        complete(session.sessionId(), terminal.name(), "sessao lf_rendezvous terminou em " + terminal);
    }

    public int activeSessionCount() { return activeSessions.size(); }

    @Override
    public void close() {
        closing = true;
        expiryTimers.values().forEach(timer -> timer.cancel(false));
        expiryTimers.clear();
        activeSessions.clear();
        activeByEquivalentTarget.clear();
        occupiedSlots.set(0);
        scheduler.shutdownNow();
    }

    private String ineligibility(PeerConnectivityManager.PeerConnectivityContext context) {
        if (closing || context.applicationClosing()) return "aplicacao esta encerrando";
        if (!context.torrentActive()) return "torrent encerrou ou nao possui sessao ativa";
        if (context.peerRemoved()) return "peer foi removido";
        if (context.directConnectionSucceeded()) return "conexao direta ja teve sucesso";
        if (context.backoffActive()) return "backoff de conectividade esta ativo";
        if (context.targetNodeId().isEmpty()) return "target nao possui LuffyNodeId conhecido";
        if (context.targetCapabilities().isEmpty() || !context.targetCapabilities().get().supportsDistributedRendezvous()) {
            return "capacidades do target sao insuficientes para rendezvous/uTP/hole punch";
        }
        if (!localConfirmedUtpAvailable.getAsBoolean()) return "endpoint UDP/uTP local confirmado nao esta disponivel";
        return null;
    }

    private CompletionStage<PeerConnectivityManager.OverlayRendezvousResult> skipped(
            PeerConnectivityManager.PeerConnectivityContext context, String reason) {
        diagnostics.log(P2pDiagnostics.Layer.HOLEPUNCH, "[LF_RENDEZVOUS] nao iniciado: infoHash=" + context.infoHash()
                + "; target=" + context.targetEndpoint().display() + "; motivo=" + reason + ".");
        return CompletableFuture.completedFuture(skippedResult(context, reason));
    }

    private static PeerConnectivityManager.OverlayRendezvousResult skippedResult(
            PeerConnectivityManager.PeerConnectivityContext context, String reason) {
        return PeerConnectivityManager.OverlayRendezvousResult.skipped(reason);
    }

    private void armExpiry(RendezvousSession session, ActiveSession active) {
        long delay = Math.max(1L, Duration.between(Instant.now(), session.expiresAt()).toMillis());
        ScheduledFuture<?> timer = scheduler.schedule(() -> complete(session.sessionId(), RendezvousState.EXPIRED.name(),
                "tempo limite da sessao lf_rendezvous excedido"), delay, TimeUnit.MILLISECONDS);
        expiryTimers.put(session.sessionId(), timer);
    }

    private void complete(UUID sessionId, String terminalState, String reason) {
        ActiveSession active = activeSessions.remove(sessionId);
        if (active == null) return;
        activeByEquivalentTarget.remove(active.equivalentKey(), sessionId);
        releaseSlot();
        ScheduledFuture<?> timer = expiryTimers.remove(sessionId);
        if (timer != null) timer.cancel(false);
        terminalListener.finished(active.context(), sessionId, terminalState, reason);
    }

    private static String equivalentKey(PeerConnectivityManager.PeerConnectivityContext context) {
        return context.infoHash().toLowerCase(java.util.Locale.ROOT) + "|" + context.targetNodeId().orElseThrow().asText()
                + "|" + context.targetEndpoint().display();
    }

    private boolean reserveSlot() {
        for (;;) {
            int current = occupiedSlots.get();
            if (current >= config.maximumActiveSessions()) return false;
            if (occupiedSlots.compareAndSet(current, current + 1)) return true;
        }
    }

    private void releaseSlot() {
        occupiedSlots.updateAndGet(current -> Math.max(0, current - 1));
    }

    private static String describe(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && (current.getMessage() == null || current.getMessage().isBlank())) current = current.getCause();
        String detail = current.getMessage();
        return current.getClass().getSimpleName() + (detail == null || detail.isBlank() ? "" : ": " + detail);
    }

    private record ActiveSession(PeerConnectivityManager.PeerConnectivityContext context, String equivalentKey,
                                 RendezvousSession session) { }
}
