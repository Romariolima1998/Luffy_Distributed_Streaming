package dev.lufi.infrastructure.identity;

import bt.protocol.extended.ExtendedMessage;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Mensagem BEP 10 {@code lf_identity}; nao altera o peer ID BitTorrent. */
public final class LuffyIdentityMessage extends ExtendedMessage {
    public static final int PROTOCOL_VERSION = 1;
    public static final int MAX_CLIENT_VERSION_BYTES = 64;

    private final int protocolVersion;
    private final LuffyNodeId nodeId;
    private final String clientVersion;
    private final boolean supportsRoute;
    private final boolean supportsRendezvous;
    private final boolean supportsUtp;
    private final boolean supportsHolePunch;

    public LuffyIdentityMessage(int protocolVersion, LuffyNodeId nodeId, String clientVersion,
                                boolean supportsRoute, boolean supportsRendezvous,
                                boolean supportsUtp, boolean supportsHolePunch) {
        if (protocolVersion != PROTOCOL_VERSION) {
            throw new IllegalArgumentException("Versao lf_identity nao suportada: " + protocolVersion);
        }
        this.protocolVersion = protocolVersion;
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.clientVersion = validateClientVersion(clientVersion);
        if (supportsHolePunch && !supportsUtp) {
            throw new IllegalArgumentException("Hole punch exige suporte uTP real");
        }
        if (supportsRendezvous && (!supportsUtp || !supportsHolePunch)) {
            throw new IllegalArgumentException("Rendezvous exige uTP e hole punch reais");
        }
        this.supportsRoute = supportsRoute;
        this.supportsRendezvous = supportsRendezvous;
        this.supportsUtp = supportsUtp;
        this.supportsHolePunch = supportsHolePunch;
    }

    public int protocolVersion() { return protocolVersion; }
    public LuffyNodeId nodeId() { return nodeId; }
    public String clientVersion() { return clientVersion; }
    public boolean supportsRoute() { return supportsRoute; }
    public boolean supportsRendezvous() { return supportsRendezvous; }
    public boolean supportsUtp() { return supportsUtp; }
    public boolean supportsHolePunch() { return supportsHolePunch; }

    public LuffyPeerCapabilities capabilities() {
        return new LuffyPeerCapabilities(protocolVersion, nodeId, clientVersion, supportsRoute,
                supportsRendezvous, supportsUtp, supportsHolePunch);
    }

    static String validateClientVersion(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("clientVersion lf_identity e obrigatoria");
        }
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        if (encoded.length > MAX_CLIENT_VERSION_BYTES) {
            throw new IllegalArgumentException("clientVersion lf_identity excede " + MAX_CLIENT_VERSION_BYTES + " bytes");
        }
        if (value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("clientVersion lf_identity contem caractere de controle");
        }
        return value;
    }
}
