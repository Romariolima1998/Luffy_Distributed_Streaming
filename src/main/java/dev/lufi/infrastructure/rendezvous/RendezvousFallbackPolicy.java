package dev.lufi.infrastructure.rendezvous;

/** Limita a troca de coordenadores antes de uma tentativa uTP ser iniciada. */
public record RendezvousFallbackPolicy(int maximumCoordinatorAttempts) {
    public static final int INITIAL_MAXIMUM_COORDINATOR_ATTEMPTS = 3;
    public RendezvousFallbackPolicy {
        if (maximumCoordinatorAttempts < 1 || maximumCoordinatorAttempts > 8) {
            throw new IllegalArgumentException("limite de candidatos rendezvous invalido");
        }
    }
    public static RendezvousFallbackPolicy defaults() { return new RendezvousFallbackPolicy(INITIAL_MAXIMUM_COORDINATOR_ATTEMPTS); }
}
