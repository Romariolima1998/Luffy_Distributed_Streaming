package dev.lufi.infrastructure.rendezvous;

/** Limites locais do fallback de overlay; separados dos limites de download e Swarm Assist. */
public record RendezvousFallbackConfig(int maximumActiveSessions) {
    public RendezvousFallbackConfig {
        if (maximumActiveSessions < 1 || maximumActiveSessions > 32) {
            throw new IllegalArgumentException("maximumActiveSessions deve estar entre 1 e 32");
        }
    }

    public static RendezvousFallbackConfig defaults() {
        return new RendezvousFallbackConfig(4);
    }
}
