package dev.lufi.infrastructure.rendezvous;

import bt.metainfo.TorrentId;
import dev.lufi.infrastructure.identity.LuffyNodeId;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RendezvousSessionRegistryTest {
    private static final Instant NOW = Instant.parse("2026-08-04T18:00:00Z");

    @Test void validatesTransitionsIsIdempotentAndCleansTerminalSession() {
        RendezvousSessionRegistry registry = new RendezvousSessionRegistry();
        RendezvousSession session = session();
        assertEquals(RendezvousSessionRegistry.Registration.CREATED, registry.register(session));
        assertEquals(RendezvousSessionRegistry.Registration.DUPLICATE, registry.register(session));
        assertEquals(RendezvousSessionRegistry.Transition.APPLIED,
                registry.transition(session.sessionId(), RendezvousState.ROUTE_ESTABLISHED, NOW));
        assertEquals(RendezvousSessionRegistry.Transition.IDEMPOTENT,
                registry.transition(session.sessionId(), RendezvousState.ROUTE_ESTABLISHED, NOW));
        assertEquals(RendezvousSessionRegistry.Transition.INVALID,
                registry.transition(session.sessionId(), RendezvousState.PUNCHING, NOW));
        assertTrue(registry.finish(session.sessionId(), RendezvousState.FAILED, NOW).isPresent());
        assertFalse(registry.find(session.sessionId(), NOW).isPresent());
        assertEquals(0, registry.size());

        RendezvousSession successful = session();
        registry.register(successful);
        assertTrue(registry.finish(successful.sessionId(), RendezvousState.CONNECTED, NOW).isPresent());
        assertFalse(registry.find(successful.sessionId(), NOW).isPresent());
    }

    @Test void expiresAndRejectsConflictingSessionId() {
        RendezvousSessionRegistry registry = new RendezvousSessionRegistry();
        RendezvousSession session = session();
        registry.register(session);
        assertFalse(registry.find(session.sessionId(), NOW.plusSeconds(31)).isPresent());
        assertEquals(0, registry.size());

        registry.register(session());
        RendezvousSession conflict = new RendezvousSession(session().sessionId(), UUID.randomUUID(), node(1), node(2), node(3),
                torrent(), NOW, NOW.plusSeconds(30), RendezvousState.CREATED);
        registry.register(conflict);
        assertThrows(IllegalArgumentException.class, () -> registry.register(new RendezvousSession(conflict.sessionId(), UUID.randomUUID(),
                node(1), node(4), node(3), torrent(), NOW, NOW.plusSeconds(30), RendezvousState.CREATED)));
    }

    private static RendezvousSession session() { return new RendezvousSession(UUID.randomUUID(), UUID.randomUUID(), node(1), node(2), node(3), torrent(), NOW, NOW.plusSeconds(30), RendezvousState.CREATED); }
    private static TorrentId torrent() { return TorrentId.fromBytes(HexFormat.of().parseHex("0123456789012345678901234567890123456789")); }
    private static LuffyNodeId node(int fill) { byte[] value = new byte[LuffyNodeId.BINARY_LENGTH]; Arrays.fill(value, (byte) fill); return LuffyNodeId.fromBinary(value); }
}
