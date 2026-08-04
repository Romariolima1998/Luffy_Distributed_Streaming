package dev.lufi.infrastructure;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SwarmNeedScoreTest {
    @Test void startsWithScarcityAndProtectsTheZeroPeerCase() {
        var onePeer = SwarmNeedScore.fromCompletedObservation(1);
        var fourPeers = SwarmNeedScore.fromCompletedObservation(4);
        var zeroPeers = SwarmNeedScore.fromCompletedObservation(0);

        assertEquals(1d, onePeer.value());
        assertEquals(.25d, fourPeers.value());
        assertEquals(SwarmNeedScore.PopulationState.CONFIRMED_EMPTY, zeroPeers.populationState());
        assertEquals(1d, zeroPeers.value());
        assertTrue(zeroPeers.canDriveRetention());
    }

    @Test void doesNotTreatAnUnfinishedLookupAsAZeroPeerObservation() {
        var pending = SwarmNeedScore.pending();

        assertEquals(SwarmNeedScore.PopulationState.PENDING, pending.populationState());
        assertFalse(pending.canDriveRetention());
    }

    @Test void rewardsRealAssistButAgesAnInactiveSwarm() {
        Instant now = Instant.parse("2026-07-29T12:00:00Z");
        var fresh = SwarmNeedScore.fromCompletedObservation(4, now, 0, 0, Duration.ofDays(7), now);
        var useful = SwarmNeedScore.fromCompletedObservation(4, now, 3, 2, Duration.ofDays(7), now);
        var stale = SwarmNeedScore.fromCompletedObservation(4, now.minus(Duration.ofDays(8)), 0, 0, Duration.ofDays(7), now);

        assertTrue(useful.value() > fresh.value());
        assertTrue(stale.value() < fresh.value());
    }

    @Test void keepsPeerScarcityAsTheMainRuleFromTheAssistListExample() {
        Instant now = Instant.parse("2026-07-29T12:00:00Z");
        var healthyY = SwarmNeedScore.fromCompletedObservation(27, now, 100, 100, Duration.ofDays(7), now);
        var fragileZ = SwarmNeedScore.fromCompletedObservation(3);

        assertTrue(fragileZ.value() > healthyY.value(), "Z com 3 peers deve substituir Y com 27 peers");
    }
}
