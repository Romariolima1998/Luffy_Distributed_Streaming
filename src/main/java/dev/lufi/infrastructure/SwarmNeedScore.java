package dev.lufi.infrastructure;

import java.time.Duration;
import java.time.Instant;

/**
 * Quanto maior o score, maior a necessidade de manter presença Assist. Ele
 * começa pela escassez de peers e mantém os ajustes de utilidade e idade
 * explícitos, para que a decisão seja auditável.
 */
public record SwarmNeedScore(double value, int observedPeerCount, PopulationState populationState,
                             double scarcity, double rendezvousAdjustment,
                             double instabilityAdjustment, double recencyAdjustment) {
    public enum PopulationState { PENDING, OBSERVED, CONFIRMED_EMPTY }

    public SwarmNeedScore {
        observedPeerCount = Math.max(0, observedPeerCount);
        populationState = populationState == null ? PopulationState.PENDING : populationState;
        value = Math.max(0d, value);
    }

    public static SwarmNeedScore pending() {
        return new SwarmNeedScore(0d, 0, PopulationState.PENDING, 0d, 0d, 0d, 0d);
    }

    /** Só deve ser chamado quando o lookup DHT/PEX já concluiu. */
    public static SwarmNeedScore fromCompletedObservation(int observedPeerCount) {
        int peers = Math.max(0, observedPeerCount);
        PopulationState state = peers == 0 ? PopulationState.CONFIRMED_EMPTY : PopulationState.OBSERVED;
        // Zero não é divisão por zero: recebe escassez máxima temporária e
        // depois é removido pelo decay se permanecer vazio.
        double scarcity = 1d / Math.max(1, peers);
        return new SwarmNeedScore(scarcity, peers, state, scarcity, 0d, 0d, 0d);
    }

    /**
     * Score de uma entrada já Assistida. Utilidade real ganha um bônus pequeno
     * e limitado; atividade antiga perde prioridade antes do decay terminal
     * remover um swarm morto.
     */
    public static SwarmNeedScore fromCompletedObservation(int observedPeerCount, Instant lastRelevantActivity,
                                                           int holePunchRequestsRelayed, int successfulHolePunches,
                                                           Duration inactiveDecay, Instant now) {
        SwarmNeedScore base = fromCompletedObservation(observedPeerCount);
        Instant reference = now == null ? Instant.now() : now;
        Duration decay = inactiveDecay == null || inactiveDecay.isNegative() ? Duration.ofDays(7) : inactiveDecay;
        double utility = utilityAdjustment(holePunchRequestsRelayed, successfulHolePunches);
        double recency = recencyAdjustment(lastRelevantActivity, decay, reference);
        return new SwarmNeedScore(base.scarcity() + utility + recency, base.observedPeerCount(),
                base.populationState(), base.scarcity(), utility, 0d, recency);
    }

    private static double utilityAdjustment(int relayed, int succeeded) {
        // Utilidade só desempata situações próximas. Mesmo no máximo, ela não
        // supera a escassez de 3 peers contra um swarm saudável com 27 peers.
        double relayValue = Math.min(0.05d, Math.max(0, relayed) * 0.01d);
        double successValue = Math.min(0.10d, Math.max(0, succeeded) * 0.03d);
        return relayValue + successValue;
    }

    private static double recencyAdjustment(Instant lastActivity, Duration decay, Instant now) {
        if (lastActivity == null) return -0.15d;
        if (decay.isZero() || !lastActivity.isBefore(now)) return 0d;
        double ageFraction = (double) Duration.between(lastActivity, now).toMillis() / Math.max(1d, decay.toMillis());
        // Não cria churn por milissegundos entre duas observações equivalentes.
        if (ageFraction < 0.05d) return 0d;
        return -Math.min(0.75d, Math.max(0d, ageFraction) * 0.75d);
    }

    public boolean canDriveRetention() { return populationState != PopulationState.PENDING; }
}
