package dev.lufi.infrastructure.identity;

import java.util.Objects;

/** Capacidades que um peer Luffy anunciou depois de negociar {@code lf_identity}. */
public record LuffyPeerCapabilities(
        int protocolVersion,
        LuffyNodeId nodeId,
        String clientVersion,
        boolean supportsRoute,
        boolean supportsRendezvous,
        boolean supportsUtp,
        boolean supportsHolePunch
) {
    public LuffyPeerCapabilities {
        if (protocolVersion != LuffyIdentityMessage.PROTOCOL_VERSION) {
            throw new IllegalArgumentException("Versao de protocolo lf_identity nao suportada: " + protocolVersion);
        }
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(clientVersion, "clientVersion");
        if (supportsHolePunch && !supportsUtp) {
            throw new IllegalArgumentException("Hole punch exige suporte uTP real");
        }
        if (supportsRendezvous && (!supportsUtp || !supportsHolePunch)) {
            throw new IllegalArgumentException("Rendezvous exige suporte uTP e hole punch reais");
        }
    }

    /** Um candidato so e elegivel quando anunciou todas as capacidades necessarias. */
    public boolean supportsDistributedRendezvous() {
        return supportsRendezvous && supportsUtp && supportsHolePunch;
    }
}
