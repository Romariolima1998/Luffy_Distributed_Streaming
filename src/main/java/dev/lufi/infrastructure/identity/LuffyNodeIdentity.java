package dev.lufi.infrastructure.identity;

import java.time.Instant;
import java.util.Objects;

/** Identidade local persistida. A versao pertence ao formato do arquivo, nao ao protocolo BitTorrent. */
public record LuffyNodeIdentity(LuffyNodeId nodeId, Instant createdAt) {
    public static final int FORMAT_VERSION = 1;

    public LuffyNodeIdentity {
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
