package dev.lufi.infrastructure.bootstrap;

/** Ciclo de vida da sessao permanente do swarm oficial Ola Luffy. */
public enum BootstrapSwarmState {
    STOPPED,
    STARTING,
    JOINING,
    ACTIVE,
    DEGRADED,
    RECONNECTING,
    FAILED
}
