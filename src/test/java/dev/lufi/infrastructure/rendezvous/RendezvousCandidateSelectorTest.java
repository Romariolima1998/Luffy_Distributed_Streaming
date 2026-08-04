package dev.lufi.infrastructure.rendezvous;

import dev.lufi.infrastructure.ObservationSource;
import dev.lufi.infrastructure.ObservedEndpoint;
import dev.lufi.infrastructure.Transport;
import dev.lufi.infrastructure.identity.LuffyNodeId;
import dev.lufi.infrastructure.identity.LuffyPeerCapabilities;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RendezvousCandidateSelectorTest {
    private static final Instant NOW = Instant.parse("2026-08-04T12:00:00Z");
    private static final LuffyNodeId TARGET = node(9);

    @Test void selectsTheOnlyEligibleCandidate() {
        RendezvousCandidate candidate = candidate(1, false, false, 2, 20, 1, true, true, false,
                Optional.of(endpoint(false)), capabilities(true));

        RendezvousCandidateSelector.Selection selection = select(List.of(candidate));

        assertFalse(selection.directTargetConnection());
        assertEquals(candidate, selection.candidate().orElseThrow());
    }

    @Test void prioritizesContentSwarmThenRouteDistanceLoadAndStability() {
        RendezvousCandidate contentPeer = candidate(1, false, true, 5, 300, 10, true, true, false,
                Optional.of(endpoint(false)), capabilities(true));
        RendezvousCandidate closerGeneralPeer = candidate(2, false, false, 1, 10, 0, true, true, false,
                Optional.of(endpoint(false)), capabilities(true));

        RendezvousCandidateSelector.Selection selection = select(List.of(closerGeneralPeer, contentPeer));

        assertEquals(contentPeer, selection.candidate().orElseThrow());
    }

    @Test void localDirectConnectionWinsBeforeAnyRendezvousCandidate() {
        RendezvousCandidate candidate = candidate(1, false, true, 1, 10, 0, true, true, false,
                Optional.of(endpoint(false)), capabilities(true));

        RendezvousCandidateSelector.Selection selection = RendezvousCandidateSelector.select(
                new RendezvousCandidateSelector.Request(TARGET, true, NOW), List.of(candidate));

        assertTrue(selection.directTargetConnection());
        assertTrue(selection.candidate().isEmpty());
    }

    @Test void skipsInvalidFirstCandidateAndUsesTheNextEligiblePeer() {
        RendezvousCandidate blocked = candidate(1, false, true, 1, 1, 0, true, true, true,
                Optional.of(endpoint(false)), capabilities(true));
        RendezvousCandidate valid = candidate(2, false, false, 2, 30, 2, true, true, false,
                Optional.of(endpoint(false)), capabilities(true));

        RendezvousCandidateSelector.Selection selection = select(List.of(blocked, valid));

        assertEquals(valid, selection.candidate().orElseThrow());
    }

    @Test void ordinaryLuffyPeerBeatsServerAndServerRemainsFallback() {
        RendezvousCandidate server = candidate(1, true, true, 1, 10, 0, true, true, false,
                Optional.of(endpoint(false)), capabilities(true));
        RendezvousCandidate ordinary = candidate(2, false, false, 4, 200, 4, true, true, false,
                Optional.of(endpoint(false)), capabilities(true));

        assertEquals(ordinary, select(List.of(server, ordinary)).candidate().orElseThrow());
        assertEquals(server, select(List.of(server)).candidate().orElseThrow());
    }

    @Test void rejectsExpiredUdpEndpoint() {
        RendezvousCandidate expired = candidate(1, false, false, 1, 10, 0, true, true, false,
                Optional.of(endpoint(true)), capabilities(true));

        assertTrue(select(List.of(expired)).candidate().isEmpty());
    }

    @Test void rejectsInsufficientCapabilities() {
        RendezvousCandidate unsupported = candidate(1, false, false, 1, 10, 0, true, true, false,
                Optional.of(endpoint(false)), capabilities(false));

        assertTrue(select(List.of(unsupported)).candidate().isEmpty());
    }

    @Test void rejectsConnectionThatClosesDuringSelection() {
        RendezvousCandidate candidate = candidate(1, false, false, 1, 10, 0, true, true, false,
                Optional.of(endpoint(false)), capabilities(true));

        RendezvousCandidateSelector.Selection selection = RendezvousCandidateSelector.select(
                new RendezvousCandidateSelector.Request(TARGET, false, NOW), List.of(candidate), ignored -> false);

        assertTrue(selection.candidate().isEmpty());
    }

    private static RendezvousCandidateSelector.Selection select(List<RendezvousCandidate> candidates) {
        return RendezvousCandidateSelector.select(new RendezvousCandidateSelector.Request(TARGET, false, NOW), candidates);
    }

    private static RendezvousCandidate candidate(int id, boolean server, boolean inContentSwarm, int distance,
                                                  long latencyMillis, int sessions, boolean activeControl,
                                                  boolean activeTarget, boolean blocked,
                                                  Optional<ObservedEndpoint> endpoint,
                                                  LuffyPeerCapabilities capabilities) {
        return new RendezvousCandidate(node(id), TARGET, distance, capabilities, Duration.ofMillis(latencyMillis), sessions,
                server, endpoint, activeControl, activeTarget, inContentSwarm, false, false, blocked,
                NOW.minus(Duration.ofMinutes(id)));
    }

    private static ObservedEndpoint endpoint(boolean expired) {
        try {
            Instant expiresAt = expired ? NOW.minusSeconds(1) : NOW.plus(Duration.ofMinutes(5));
            return new ObservedEndpoint(InetAddress.getByName("203.0.113.10"), 43_127, Transport.UTP,
                    ObservationSource.EXTERNAL_PROBE, NOW.minus(Duration.ofMinutes(1)), expiresAt, true);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private static LuffyPeerCapabilities capabilities(boolean rendezvous) {
        return rendezvous
                ? new LuffyPeerCapabilities(1, node(20), "Luffy/0.1.0", true, true, true, true)
                : new LuffyPeerCapabilities(1, node(20), "Luffy/0.1.0", true, false, true, false);
    }

    private static LuffyNodeId node(int fill) {
        byte[] bytes = new byte[LuffyNodeId.BINARY_LENGTH];
        Arrays.fill(bytes, (byte) fill);
        return LuffyNodeId.fromBinary(bytes);
    }
}
