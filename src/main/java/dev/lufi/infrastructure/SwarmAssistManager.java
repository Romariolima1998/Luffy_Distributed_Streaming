package dev.lufi.infrastructure;

import dev.lufi.domain.MagnetLink;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Orquestra a lista passiva de Swarm Assist sem misturar decisao de retencao
 * com a UI, DHT ou o motor BitTorrent. O runtime continua dono de DHT/PEX,
 * sessoes e de {@link dev.lufi.infrastructure.identity.ConnectedLuffyRegistry}.
 */
public final class SwarmAssistManager implements AutoCloseable {
    public interface Runtime {
        CompletableFuture<Integer> inspect(MagnetLink magnet);
        CompletableFuture<Integer> restore(MagnetLink magnet);
        void join(MagnetLink magnet);
        void leave(String infoHash);
        SwarmAssistStats stats(String infoHash);
        void applyPolicy(SwarmAssistPolicy policy);
    }

    public record Decision(SwarmMembershipRepository.Retention retention, String infoHash, int observedPeerCount,
                           String replacedInfoHash, int replacedPeerCount, SwarmNeedScore needScore) {
        public boolean retained() {
            return retention == SwarmMembershipRepository.Retention.ADDED
                    || retention == SwarmMembershipRepository.Retention.UPDATED
                    || retention == SwarmMembershipRepository.Retention.REPLACED;
        }
    }

    private final SwarmMembershipRepository repository;
    private final Supplier<SwarmAssistPolicy> policySupplier;
    private final SwarmNeedEvaluator evaluator;
    private final Runtime runtime;
    private final P2pDiagnostics diagnostics;
    private final Set<String> candidateRequests = ConcurrentHashMap.newKeySet();
    private final Object selectionLock = new Object();
    private final ScheduledExecutorService maintenance = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("luffy-swarm-assist-policy-", 0).factory());
    private final AtomicBoolean maintenanceStarted = new AtomicBoolean();

    public SwarmAssistManager(SwarmMembershipRepository repository, Supplier<SwarmAssistPolicy> policySupplier,
                              SwarmNeedEvaluator evaluator, Runtime runtime, P2pDiagnostics diagnostics) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.policySupplier = Objects.requireNonNull(policySupplier, "policySupplier");
        this.evaluator = evaluator == null ? new SwarmNeedEvaluator() : evaluator;
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.diagnostics = diagnostics == null ? new P2pDiagnostics() : diagnostics;
    }

    /** Um assistido temporariamente vira candidato somente depois de a sessao do usuario encerrar. */
    public CompletionStage<Decision> considerTemporaryWatch(MagnetLink magnet) {
        Objects.requireNonNull(magnet, "magnet");
        candidateRequests.add(magnet.infoHash());
        applyCurrentPolicy();
        return runtime.inspect(magnet).thenCompose(observed -> refreshStalePopulationWhenFull()
                .thenApply(ignored -> decideCandidate(magnet, observed == null ? 0 : observed)));
    }

    /** Reinicio invalida numeros antigos e reativa a lista gradualmente no runtime. */
    public CompletableFuture<Void> restorePersisted() {
        repository.invalidateAllPopulationObservations();
        applyCurrentPolicy();
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (SwarmMembershipRepository.Membership membership : repository.findAll()) {
            chain = chain.thenCompose(ignored -> {
                MagnetLink magnet = MagnetLink.parse(membership.magnet());
                return runtime.restore(magnet).thenAccept(observed -> repository.updateEstimatedPeerCount(
                        membership.infoHash(), observed == null ? 0 : observed));
            });
        }
        return chain;
    }

    /** Um seed ou download permanente remove somente a vaga passiva equivalente. */
    public void promoteToUserOwned(String infoHash) {
        if (infoHash == null || !infoHash.matches("(?i)[a-f0-9]{40}")) return;
        candidateRequests.remove(infoHash);
        repository.remove(infoHash);
        runtime.leave(infoHash);
    }

    public void recordUserInteraction(String infoHash) {
        if (isInfoHash(infoHash)) repository.recordUserInteraction(infoHash);
    }

    /** Eventos do gateway sao persistidos fora de DHT, PEX e listeners BitTorrent. */
    public void recordActivity(SwarmAssistActivity activity) {
        if (activity == null) return;
        Thread.startVirtualThread(() -> {
            try {
                switch (activity.type()) {
                    case PEER_SEEN -> repository.recordPeerSeen(activity.infoHash(), activity.occurredAt());
                    case USEFUL_RENDEZVOUS -> repository.recordUsefulRendezvous(activity.infoHash(), activity.occurredAt());
                    case HOLE_PUNCH_RELAYED -> repository.recordHolePunchRelayed(activity.infoHash(), activity.occurredAt());
                    case HOLE_PUNCH_SUCCEEDED -> repository.recordSuccessfulHolePunch(activity.infoHash(), activity.occurredAt());
                }
            } catch (RuntimeException error) {
                diagnostics.log("SWARM ASSIST ACTIVITY nao pode ser persistida: infoHash=" + activity.infoHash()
                        + "; tipo=" + activity.type() + "; motivo=" + error.getMessage() + ".");
            }
        });
    }

    /** Libera vagas de swarms comprovadamente vazios ou inativos; nunca toca em seeding. */
    public List<String> pruneExpiredEntries() {
        SwarmAssistPolicy policy = currentPolicy();
        Instant now = Instant.now();
        List<String> removed = new ArrayList<>();
        for (SwarmMembershipRepository.Membership membership : repository.findAll()) {
            boolean emptyExpired = membership.shouldDecayEmpty(policy.emptySwarmDecay(), now);
            boolean inactiveExpired = membership.shouldDecayInactive(policy.inactiveSwarmDecay(), now);
            if (!emptyExpired && !inactiveExpired) continue;
            repository.remove(membership.infoHash());
            candidateRequests.remove(membership.infoHash());
            runtime.leave(membership.infoHash());
            removed.add(membership.infoHash());
            diagnostics.log(P2pDiagnostics.Layer.DISCOVERY, "SWARM ASSIST DECAY: infoHash=" + membership.infoHash()
                    + "; motivo=" + (emptyExpired ? "vazio confirmado" : "inativo") + "; vaga liberada.");
        }
        return List.copyOf(removed);
    }

    public List<SwarmAssistEntry> entries() {
        return repository.findAll().stream().map(membership -> SwarmAssistEntry.from(membership,
                statsFor(membership.infoHash(), membership.observedPeerCount(), membership.observedAt()))).toList();
    }

    /** A limpeza ocorre fora da UI e nunca em listeners de rede. */
    public void startMaintenance() {
        if (!maintenanceStarted.compareAndSet(false, true)) return;
        maintenance.scheduleWithFixedDelay(this::pruneExpiredEntries, 5, 15, TimeUnit.MINUTES);
    }

    @Override public void close() {
        candidateRequests.clear();
        maintenance.shutdownNow();
    }

    private Decision decideCandidate(MagnetLink magnet, int observed) {
        synchronized (selectionLock) {
            if (!candidateRequests.remove(magnet.infoHash())) {
                return new Decision(SwarmMembershipRepository.Retention.NOT_RETAINED_MORE_CONNECTED, magnet.infoHash(), observed,
                        null, -1, SwarmNeedScore.pending());
            }
            pruneExpiredEntries();
            SwarmAssistPolicy policy = currentPolicy();
            SwarmAssistEntry candidate = new SwarmAssistEntry(magnet.infoHash(), magnet,
                    statsFor(magnet.infoHash(), observed, Instant.now()), Instant.now(), Instant.now(), null, null, 0, 0, null);
            SwarmNeedScore score = evaluator.evaluate(candidate, policy, Instant.now());
            SwarmMembershipRepository.RetentionResult result = repository.retainIfHelpful(toMagnetUri(magnet), score,
                    membership -> evaluator.evaluate(SwarmAssistEntry.from(membership,
                            statsFor(membership.infoHash(), membership.observedPeerCount(), membership.observedAt())), policy, Instant.now()));
            if (result.retained()) {
                if (result.replacedInfoHash() != null) runtime.leave(result.replacedInfoHash());
                runtime.join(magnet);
            }
            diagnostics.log(P2pDiagnostics.Layer.DISCOVERY, "[SWARM-ASSIST] candidato: infoHash=" + magnet.infoHash()
                    + "; peers=" + observed + "; needScore=" + score.value() + "; resultado=" + result.retention() + ".");
            diagnostics.event(P2pDiagnostics.Category.LF_SWARM_ASSIST, "ASSIST_DECISION",
                    "torrentId", abbreviated(magnet.infoHash()), "peers", observed, "result", result.retention());
            return new Decision(result.retention(), magnet.infoHash(), observed, result.replacedInfoHash(),
                    result.replacedPeerCount(), score);
        }
    }

    private CompletionStage<Void> refreshStalePopulationWhenFull() {
        SwarmAssistPolicy policy = currentPolicy();
        List<SwarmMembershipRepository.Membership> current = repository.findAll();
        if (current.size() < policy.maximumSwarms()) return CompletableFuture.completedFuture(null);
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (SwarmMembershipRepository.Membership membership : current) {
            if (membership.hasFreshPopulation(policy.statsTtl(), Instant.now())) continue;
            chain = chain.thenCompose(ignored -> runtime.inspect(MagnetLink.parse(membership.magnet()))
                    .thenAccept(observed -> repository.updateEstimatedPeerCount(membership.infoHash(), observed == null ? 0 : observed)));
        }
        return chain;
    }

    private SwarmAssistStats statsFor(String infoHash, int fallbackPeerCount, Instant observedAt) {
        SwarmAssistStats current = runtime.stats(infoHash);
        if (current == null || current.lastObservedAt().equals(Instant.EPOCH)) {
            return new SwarmAssistStats(infoHash, Math.max(0, fallbackPeerCount), 0, 0, 0,
                    observedAt == null ? Instant.now() : observedAt);
        }
        return current;
    }

    private SwarmAssistPolicy currentPolicy() {
        SwarmAssistPolicy policy = Objects.requireNonNullElseGet(policySupplier.get(), SwarmAssistPolicy::defaults);
        runtime.applyPolicy(policy);
        return policy;
    }

    private void applyCurrentPolicy() { currentPolicy(); }

    private static boolean isInfoHash(String value) { return value != null && value.matches("(?i)[a-f0-9]{40}"); }
    private static String abbreviated(String value) {
        return value == null ? "unknown" : value.substring(0, Math.min(12, value.length())) + (value.length() > 12 ? "..." : "");
    }

    private static String toMagnetUri(MagnetLink magnet) {
        StringBuilder value = new StringBuilder("magnet:?xt=urn:btih:").append(magnet.infoHash());
        magnet.displayName().ifPresent(name -> value.append("&dn=").append(java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8)));
        magnet.parameters().forEach((key, parameter) -> {
            if (!key.equalsIgnoreCase("xt") && !key.equalsIgnoreCase("dn") && parameter != null && !parameter.isBlank()) {
                value.append('&').append(java.net.URLEncoder.encode(key, java.nio.charset.StandardCharsets.UTF_8))
                        .append('=').append(java.net.URLEncoder.encode(parameter, java.nio.charset.StandardCharsets.UTF_8));
            }
        });
        return value.toString();
    }
}
