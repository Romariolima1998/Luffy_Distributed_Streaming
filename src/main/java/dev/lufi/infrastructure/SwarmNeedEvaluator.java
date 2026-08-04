package dev.lufi.infrastructure;

import java.time.Instant;
import java.util.Objects;

/**
 * Traduz a saude observada de um swarm em uma prioridade de assistencia.
 * Quanto maior o score, maior a necessidade de manter uma vaga passiva.
 */
public final class SwarmNeedEvaluator {
    public SwarmNeedScore evaluate(SwarmAssistEntry entry, SwarmAssistPolicy policy, Instant now) {
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(policy, "policy");
        Instant reference = now == null ? Instant.now() : now;
        SwarmAssistStats stats = entry.stats();
        if (!stats.isFresh(policy.statsTtl(), reference)) return SwarmNeedScore.pending();

        SwarmNeedScore base = SwarmNeedScore.fromCompletedObservation(stats.estimatedPeerCount(), entry.lastRelevantActivity(),
                entry.holePunchRequestsRelayed(), entry.successfulHolePunches(), policy.inactiveSwarmDecay(), reference);
        double connectionGap = stats.estimatedPeerCount() > 0 && stats.connectedPeerCount() == 0 ? 0.08d
                : stats.connectedPeerCount() < Math.min(stats.estimatedPeerCount(), policy.maximumConnectionsPerSwarm()) ? 0.03d : 0d;
        double rendezvousGap = stats.estimatedPeerCount() > 1 && stats.holePunchCapablePeers() == 0 ? 0.05d : 0d;
        double reachabilityGap = stats.estimatedPeerCount() > 0 && stats.reachablePeers() == 0 ? 0.03d : 0d;
        double criticalBonus = stats.estimatedPeerCount() <= policy.criticalPeerCount() ? 0.02d : 0d;
        double instability = connectionGap + rendezvousGap + reachabilityGap + criticalBonus;
        return new SwarmNeedScore(base.value() + instability, base.observedPeerCount(), base.populationState(),
                base.scarcity(), base.rendezvousAdjustment() + rendezvousGap,
                instability, base.recencyAdjustment());
    }
}
