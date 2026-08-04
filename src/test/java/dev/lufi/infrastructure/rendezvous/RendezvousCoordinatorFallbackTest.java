package dev.lufi.infrastructure.rendezvous;

import bt.metainfo.TorrentId;
import dev.lufi.infrastructure.P2pDiagnostics;
import dev.lufi.infrastructure.identity.ConnectedLuffyRegistry;
import dev.lufi.infrastructure.identity.LuffyNodeId;
import dev.lufi.infrastructure.identity.LuffyNodeIdentity;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RendezvousCoordinatorFallbackTest {
    @Test void usesSecondCandidateWhenFirstRouteFailsBeforePunching() throws Exception {
        List<LuffyRendezvousMessage> sent = new ArrayList<>();
        RendezvousCoordinator coordinator = coordinator(message -> { sent.add(message); return sent.size() > 1; }, 3);
        List<RendezvousCoordinator.RouteCandidate> candidates = List.of(
                new RendezvousCoordinator.RouteCandidate(UUID.randomUUID(), node(3)),
                new RendezvousCoordinator.RouteCandidate(UUID.randomUUID(), node(4)));

        Optional<RendezvousSession> session = coordinator.requestWithFallback(node(2), torrent(), candidates);

        assertTrue(session.isPresent());
        assertEquals(2, sent.size());
        assertEquals(candidates.get(1).routeRequestId(), session.orElseThrow().routeRequestId());
        assertEquals(LuffyRendezvousMessage.Type.RENDEZVOUS_REQUEST, sent.getLast().type());
    }

    @Test void doesNotExceedConfiguredFallbackLimit() throws Exception {
        List<LuffyRendezvousMessage> sent = new ArrayList<>();
        RendezvousCoordinator coordinator = coordinator(message -> { sent.add(message); return false; }, 1);
        Optional<RendezvousSession> session = coordinator.requestWithFallback(node(2), torrent(), List.of(
                new RendezvousCoordinator.RouteCandidate(UUID.randomUUID(), node(3)),
                new RendezvousCoordinator.RouteCandidate(UUID.randomUUID(), node(4))));

        assertTrue(session.isEmpty());
        assertEquals(1, sent.size());
    }

    @Test void notifiesTheConnectivityLayerWhenASessionEnds() throws Exception {
        RendezvousCoordinator coordinator = coordinator(message -> false, 1);
        AtomicReference<RendezvousState> terminal = new AtomicReference<>();
        coordinator.setSessionFinishedListener((session, state) -> terminal.set(state));

        coordinator.request(UUID.randomUUID(), node(2), node(3), torrent());

        assertEquals(RendezvousState.FAILED, terminal.get());
    }

    private static RendezvousCoordinator coordinator(RendezvousCoordinator.ControlTransport transport, int maximum) throws Exception {
        LuffyNodeIdentity identity = new LuffyNodeIdentity(node(1), Instant.parse("2026-08-04T19:00:00Z"));
        return new RendezvousCoordinator(identity, new ConnectedLuffyRegistry(), new RendezvousSessionRegistry(), transport,
                (node, message) -> false, () -> Optional.of(endpoint()),
                (torrent, endpoint) -> CompletableFuture.completedFuture(null), new P2pDiagnostics(), Duration.ofSeconds(30),
                new RendezvousFallbackPolicy(maximum));
    }
    private static TorrentId torrent() { return TorrentId.fromBytes(HexFormat.of().parseHex("0123456789012345678901234567890123456789")); }
    private static LuffyRendezvousMessage.RendezvousEndpoint endpoint() {
        try { return new LuffyRendezvousMessage.RendezvousEndpoint(InetAddress.getByName("203.0.113.1"), 43_001); }
        catch (Exception error) { throw new AssertionError(error); }
    }
    private static LuffyNodeId node(int fill) { byte[] value = new byte[LuffyNodeId.BINARY_LENGTH]; Arrays.fill(value, (byte) fill); return LuffyNodeId.fromBinary(value); }
}
