package dev.lufi.infrastructure;

import java.time.Instant;

/** Eventos reais que alimentam a política Assist sem misturar persistência ao motor BitTorrent. */
public record SwarmAssistActivity(String infoHash, Type type, Instant occurredAt) {
    public enum Type {
        PEER_SEEN,
        USEFUL_RENDEZVOUS,
        HOLE_PUNCH_RELAYED,
        HOLE_PUNCH_SUCCEEDED
    }

    public SwarmAssistActivity {
        if (infoHash == null || !infoHash.matches("(?i)[a-f0-9]{40}")) throw new IllegalArgumentException("infoHash inválido");
        type = type == null ? Type.PEER_SEEN : type;
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
    }
}
