package dev.lufi.infrastructure.rendezvous;

import bt.metainfo.TorrentId;
import dev.lufi.infrastructure.P2pDiagnostics;
import dev.lufi.infrastructure.PeerConnectivityManager;
import dev.lufi.infrastructure.identity.LuffyNodeId;
import dev.lufi.infrastructure.identity.LuffyPeerCapabilities;
import java.net.InetAddress;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RendezvousFallbackCoordinatorTest {
    private static final String INFO_HASH = "0123456789012345678901234567890123456789";

    @Test void startsOnlyOneEquivalentSessionAndReleasesTheSlotWhenItTerminates() throws Exception {
        AtomicInteger starts = new AtomicInteger();
        AtomicReference<String> terminal = new AtomicReference<>();
        RendezvousSession session = session(1);
        try (RendezvousFallbackCoordinator coordinator = new RendezvousFallbackCoordinator(new P2pDiagnostics(), context -> {
            starts.incrementAndGet();
            return CompletableFuture.completedFuture(Optional.of(session));
        }, () -> true, (context, id, state, reason) -> terminal.set(id + ":" + state))) {
            var first = coordinator.onDirectConnectivityExhausted(context(1)).toCompletableFuture().join();
            var duplicate = coordinator.onDirectConnectivityExhausted(context(1)).toCompletableFuture().join();

            assertTrue(first.started());
            assertEquals(session.sessionId(), first.sessionId().orElseThrow());
            assertFalse(duplicate.started());
            assertTrue(duplicate.reason().contains("equivalente"));
            assertEquals(1, starts.get());
            assertEquals(1, coordinator.activeSessionCount());

            coordinator.onRendezvousSessionFinished(session, RendezvousState.FAILED);

            assertEquals(session.sessionId() + ":FAILED", terminal.get());
            assertEquals(0, coordinator.activeSessionCount());
        }
    }

    @Test void rejectsEveryIneligibleContextBeforeStartingTheOverlay() throws Exception {
        AtomicInteger starts = new AtomicInteger();
        try (RendezvousFallbackCoordinator coordinator = new RendezvousFallbackCoordinator(new P2pDiagnostics(), context -> {
            starts.incrementAndGet();
            return CompletableFuture.completedFuture(Optional.of(session(1)));
        }, () -> true, (context, id, state, reason) -> { })) {
            assertFalse(coordinator.onDirectConnectivityExhausted(contextWithoutNode()).toCompletableFuture().join().started());
            assertFalse(coordinator.onDirectConnectivityExhausted(context(1, false, false, false, false, false)).toCompletableFuture().join().started());
            assertFalse(coordinator.onDirectConnectivityExhausted(context(1, true, true, false, false, false)).toCompletableFuture().join().started());
            assertFalse(coordinator.onDirectConnectivityExhausted(context(1, true, false, true, false, false)).toCompletableFuture().join().started());
            assertFalse(coordinator.onDirectConnectivityExhausted(context(1, true, false, false, true, false)).toCompletableFuture().join().started());
            assertFalse(coordinator.onDirectConnectivityExhausted(context(1, true, false, false, false, true)).toCompletableFuture().join().started());
            assertFalse(coordinator.onDirectConnectivityExhausted(contextWithInsufficientCapabilities()).toCompletableFuture().join().started());
            assertEquals(0, starts.get());
        }
        try (RendezvousFallbackCoordinator noLocalEndpoint = new RendezvousFallbackCoordinator(new P2pDiagnostics(), context -> {
            starts.incrementAndGet();
            return CompletableFuture.completedFuture(Optional.of(session(1)));
        }, () -> false, (context, id, state, reason) -> { })) {
            assertFalse(noLocalEndpoint.onDirectConnectivityExhausted(context(1)).toCompletableFuture().join().started());
            assertEquals(0, starts.get());
        }
    }

    @Test void appliesTheConfiguredGlobalSessionLimit() throws Exception {
        AtomicInteger starts = new AtomicInteger();
        RendezvousSession session = session(1);
        try (RendezvousFallbackCoordinator coordinator = new RendezvousFallbackCoordinator(new P2pDiagnostics(), context -> {
            starts.incrementAndGet();
            return CompletableFuture.completedFuture(Optional.of(session));
        }, () -> true, (context, id, state, reason) -> { }, new RendezvousFallbackConfig(1))) {
            assertTrue(coordinator.onDirectConnectivityExhausted(context(1)).toCompletableFuture().join().started());
            var limited = coordinator.onDirectConnectivityExhausted(context(2)).toCompletableFuture().join();
            assertFalse(limited.started());
            assertTrue(limited.reason().contains("limite"));
            assertEquals(1, starts.get());
        }
    }

    private static PeerConnectivityManager.PeerConnectivityContext contextWithoutNode() throws Exception {
        return new PeerConnectivityManager.PeerConnectivityContext(INFO_HASH, endpoint(), Optional.empty(), Optional.empty(),
                true, false, false, false, false, Instant.now());
    }

    private static PeerConnectivityManager.PeerConnectivityContext context(int id) throws Exception {
        return context(id, true, false, false, false, false);
    }

    private static PeerConnectivityManager.PeerConnectivityContext context(int id, boolean torrentActive, boolean peerRemoved,
                                                                            boolean directConnected, boolean backoff, boolean closing) throws Exception {
        LuffyNodeId nodeId = node(id);
        LuffyPeerCapabilities capabilities = new LuffyPeerCapabilities(1, nodeId, "test", true, true, true, true);
        return new PeerConnectivityManager.PeerConnectivityContext(INFO_HASH, endpoint(), Optional.of(nodeId), Optional.of(capabilities),
                torrentActive, peerRemoved, directConnected, backoff, closing, Instant.now());
    }

    private static PeerConnectivityManager.PeerConnectivityContext contextWithInsufficientCapabilities() throws Exception {
        LuffyNodeId nodeId = node(77);
        LuffyPeerCapabilities capabilities = new LuffyPeerCapabilities(1, nodeId, "test", false, false, false, false);
        return new PeerConnectivityManager.PeerConnectivityContext(INFO_HASH, endpoint(), Optional.of(nodeId), Optional.of(capabilities),
                true, false, false, false, false, Instant.now());
    }

    private static PeerConnectivityManager.PeerEndpoint endpoint() throws Exception {
        return new PeerConnectivityManager.PeerEndpoint(InetAddress.getByName("203.0.113.45"), 48_001,
                PeerConnectivityManager.Transport.UTP);
    }

    private static RendezvousSession session(int id) {
        Instant now = Instant.now();
        return new RendezvousSession(UUID.randomUUID(), UUID.randomUUID(), node(id), node(id + 10), node(id + 20),
                TorrentId.fromBytes(HexFormat.of().parseHex(INFO_HASH)), now, now.plusSeconds(30), RendezvousState.CREATED);
    }

    private static LuffyNodeId node(int value) {
        byte[] bytes = new byte[LuffyNodeId.BINARY_LENGTH];
        Arrays.fill(bytes, (byte) value);
        return LuffyNodeId.fromBinary(bytes);
    }
}
