package dev.lufi.infrastructure.rendezvous;

import bt.metainfo.TorrentId;
import dev.lufi.infrastructure.identity.LuffyNodeId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Estado efemero de uma tentativa de rendezvous para um unico torrent de conteudo. */
public record RendezvousSession(
        UUID sessionId,
        UUID routeRequestId,
        LuffyNodeId requesterNodeId,
        LuffyNodeId targetNodeId,
        LuffyNodeId rendezvousNodeId,
        TorrentId contentTorrentId,
        Instant createdAt,
        Instant expiresAt,
        RendezvousState state) {

    public RendezvousSession {
        requireUuid(sessionId, "sessionId");
        requireUuid(routeRequestId, "routeRequestId");
        Objects.requireNonNull(requesterNodeId, "requesterNodeId");
        Objects.requireNonNull(targetNodeId, "targetNodeId");
        Objects.requireNonNull(rendezvousNodeId, "rendezvousNodeId");
        Objects.requireNonNull(contentTorrentId, "contentTorrentId");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(state, "state");
        if (!expiresAt.isAfter(createdAt)) throw new IllegalArgumentException("sessao rendezvous ja expirada");
        if (requesterNodeId.equals(targetNodeId)) throw new IllegalArgumentException("requisitante e alvo nao podem coincidir");
    }

    public RendezvousSession transitionTo(RendezvousState next) {
        return new RendezvousSession(sessionId, routeRequestId, requesterNodeId, targetNodeId, rendezvousNodeId,
                contentTorrentId, createdAt, expiresAt, Objects.requireNonNull(next, "next"));
    }

    private static void requireUuid(UUID value, String name) {
        Objects.requireNonNull(value, name);
        if (value.getMostSignificantBits() == 0L && value.getLeastSignificantBits() == 0L) {
            throw new IllegalArgumentException(name + " nulo nao e permitido");
        }
    }
}
