package dev.lufi.infrastructure;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SwarmMembershipRepositoryTest {
    @TempDir Path directory;

    @Test void retainsAtMostTwentyFiveSwarmsAndReplacesTheMostConnectedOne() {
        SwarmMembershipRepository repository = repository();
        for (int index = 0; index < SwarmAssistSettings.DEFAULT_MAX_ASSIST_SWARMS; index++) {
            assertTrue(repository.retainIfHelpful(magnet(index), index + 1).retained());
        }

        var replacement = repository.retainIfHelpful(magnet(30), 0);

        assertEquals(SwarmMembershipRepository.Retention.REPLACED, replacement.retention());
        assertEquals(25, replacement.replacedPeerCount());
        assertEquals(SwarmAssistSettings.DEFAULT_MAX_ASSIST_SWARMS, repository.findAll().size());
        assertTrue(repository.findAll().stream().anyMatch(entry -> entry.infoHash().equals(infoHash(30))));
        assertFalse(repository.findAll().stream().anyMatch(entry -> entry.infoHash().equals(infoHash(24))));
    }

    @Test void rejectsAFullListWhenCandidateHasAtLeastAsManyObservedPeers() {
        SwarmMembershipRepository repository = repository();
        for (int index = 0; index < SwarmAssistSettings.DEFAULT_MAX_ASSIST_SWARMS; index++) repository.retainIfHelpful(magnet(index), 2);

        var rejected = repository.retainIfHelpful(magnet(30), 2);

        assertEquals(SwarmMembershipRepository.Retention.NOT_RETAINED_MORE_CONNECTED, rejected.retention());
        assertEquals(SwarmAssistSettings.DEFAULT_MAX_ASSIST_SWARMS, repository.findAll().size());
        assertFalse(repository.findAll().stream().anyMatch(entry -> entry.infoHash().equals(infoHash(30))));
    }

    @Test void updatesTheExistingEntryWithoutConsumingAnotherSlot() {
        SwarmMembershipRepository repository = repository();
        repository.retainIfHelpful(magnet(1), 8);

        var update = repository.retainIfHelpful(magnet(1).replace("Swarm1", "Atualizado"), 3);

        assertEquals(SwarmMembershipRepository.Retention.UPDATED, update.retention());
        assertEquals(1, repository.findAll().size());
        assertEquals(3, repository.findAll().getFirst().observedPeerCount());
        assertTrue(repository.findAll().getFirst().magnet().contains("dn=Atualizado"));
    }

    @Test void appliesTheConfiguredMaximumInsteadOfADeeplyHardcodedLimit() {
        SwarmMembershipRepository repository = new SwarmMembershipRepository(new SqliteDatabase(directory), () -> 2);
        var first = repository.retainIfHelpful(magnet(1), 1);
        // Mesmo com muito mais peers, o segundo entra porque ainda há vaga.
        var second = repository.retainIfHelpful(magnet(2), 999);

        var rejected = repository.retainIfHelpful(magnet(3), 1_000);

        assertEquals(SwarmMembershipRepository.Retention.ADDED, first.retention());
        assertEquals(SwarmMembershipRepository.Retention.ADDED, second.retention());
        assertEquals(SwarmMembershipRepository.Retention.NOT_RETAINED_MORE_CONNECTED, rejected.retention());
        assertEquals(2, repository.findAll().size());
    }

    @Test void fullListReplacesTheSwarmWithTheLargestCurrentEstimatedPopulation() {
        SwarmMembershipRepository repository = new SwarmMembershipRepository(new SqliteDatabase(directory), () -> 2);
        repository.retainIfHelpful(magnet(1), 2);
        repository.retainIfHelpful(magnet(2), 7);
        // A observação mais recente diz que o primeiro swarm se tornou o mais saudável.
        repository.updateEstimatedPeerCount(infoHash(1), 31);

        var result = repository.retainIfHelpful(magnet(3), 3);

        assertEquals(SwarmMembershipRepository.Retention.REPLACED, result.retention());
        assertEquals(infoHash(1), result.replacedInfoHash());
        assertTrue(repository.findAll().stream().anyMatch(entry -> entry.infoHash().equals(infoHash(3))));
        assertFalse(repository.findAll().stream().anyMatch(entry -> entry.infoHash().equals(infoHash(1))));
    }

    @Test void protectsRecentlyAddedAssistsFromReplacement() {
        SwarmMembershipRepository repository = new SwarmMembershipRepository(new SqliteDatabase(directory), () -> 2,
                () -> Duration.ofMinutes(30), () -> 0d);
        repository.retainIfHelpful(magnet(1), 10);
        repository.retainIfHelpful(magnet(2), 20);

        var result = repository.retainIfHelpful(magnet(3), 1);

        assertEquals(SwarmMembershipRepository.Retention.NOT_RETAINED_RESIDENCE, result.retention());
        assertEquals(2, repository.findAll().size());
    }

    @Test void ignoresSmallPopulationDifferencesButReplacesWhenThresholdIsMet() {
        SwarmMembershipRepository repository = new SwarmMembershipRepository(new SqliteDatabase(directory), () -> 2,
                () -> Duration.ZERO, () -> .20d);
        repository.retainIfHelpful(magnet(1), 2);
        repository.retainIfHelpful(magnet(2), 10);

        var smallDifference = repository.retainIfHelpful(magnet(3), 9);
        var significantDifference = repository.retainIfHelpful(magnet(4), 7);

        assertEquals(SwarmMembershipRepository.Retention.NOT_RETAINED_THRESHOLD, smallDifference.retention());
        assertEquals(SwarmMembershipRepository.Retention.REPLACED, significantDifference.retention());
        assertEquals(infoHash(2), significantDifference.replacedInfoHash());
    }

    @Test void givesStrongPriorityToFragileSwarmsWithoutBypassingResidenceProtection() {
        SwarmMembershipRepository repository = new SwarmMembershipRepository(new SqliteDatabase(directory), () -> 2,
                () -> Duration.ZERO, () -> .80d, () -> 3);
        repository.retainIfHelpful(magnet(1), 2);
        repository.retainIfHelpful(magnet(2), 4);

        // A diferença 4 -> 3 não alcança 80%, mas 3 peers é um swarm frágil.
        var result = repository.retainIfHelpful(magnet(3), 3);

        assertEquals(SwarmMembershipRepository.Retention.REPLACED, result.retention());
        assertEquals(infoHash(2), result.replacedInfoHash());
        assertTrue(result.retained());
    }

    @Test void marksEveryNonRetainedDecisionAsNotRetained() {
        SwarmMembershipRepository repository = new SwarmMembershipRepository(new SqliteDatabase(directory), () -> 1,
                () -> Duration.ofMinutes(30), () -> 0d);
        repository.retainIfHelpful(magnet(1), 10);

        assertFalse(repository.retainIfHelpful(magnet(2), 1).retained());
    }

    @Test void expiresPopulationAtRestartUntilItIsObservedAgain() {
        SwarmMembershipRepository repository = repository();
        repository.retainIfHelpful(magnet(1), 4);
        var beforeRestart = repository.findAll().getFirst();
        assertTrue(beforeRestart.hasFreshPopulation(Duration.ofMinutes(1), java.time.Instant.now()));

        repository.invalidateAllPopulationObservations();
        var afterRestart = repository.findAll().getFirst();

        assertFalse(afterRestart.hasFreshPopulation(Duration.ofMinutes(1), java.time.Instant.now()));
        repository.updateEstimatedPeerCount(afterRestart.infoHash(), 2);
        assertEquals(2, repository.findAll().getFirst().observedPeerCount());
        assertTrue(repository.findAll().getFirst().hasFreshPopulation(Duration.ofMinutes(1), java.time.Instant.now()));
    }

    @Test void marksZeroOnlyAfterACompletedObservationAndDecaysItAfterTheConfiguredWindow() {
        SwarmMembershipRepository repository = repository();
        Instant firstZero = Instant.parse("2026-07-29T12:00:00Z");
        repository.retainIfHelpful(magnet(1), 1);
        // The repository receives the exact completed-observation time from the network layer.
        repository.updateEstimatedPeerCount(infoHash(1), 0, firstZero);

        var empty = repository.findAll().getFirst();
        assertTrue(empty.hasConfirmedEmptyPopulation());
        assertEquals(firstZero, empty.zeroPeersSince());
        assertFalse(empty.shouldDecayEmpty(Duration.ofMinutes(30), firstZero.plus(Duration.ofMinutes(29))));
        assertTrue(empty.shouldDecayEmpty(Duration.ofMinutes(30), firstZero.plus(Duration.ofMinutes(30))));

        repository.updateEstimatedPeerCount(infoHash(1), 2, firstZero.plusSeconds(1));
        var repopulated = repository.findAll().getFirst();
        assertFalse(repopulated.hasConfirmedEmptyPopulation());
        assertEquals(null, repopulated.zeroPeersSince());
    }

    @Test void persistsAgingAndRealRendezvousUtility() {
        SwarmMembershipRepository repository = repository();
        repository.retainIfHelpful(magnet(1), 1);
        Instant base = repository.findAll().getFirst().joinedAt();
        Instant usefulAt = base.plus(Duration.ofMinutes(1));

        repository.recordUserInteraction(infoHash(1), usefulAt);
        repository.recordPeerSeen(infoHash(1), usefulAt.plusSeconds(1));
        repository.recordUsefulRendezvous(infoHash(1), usefulAt.plusSeconds(2));
        repository.recordHolePunchRelayed(infoHash(1), usefulAt.plusSeconds(3));
        repository.recordSuccessfulHolePunch(infoHash(1), usefulAt.plusSeconds(4));

        var entry = repository.findAll().getFirst();
        assertEquals(usefulAt, entry.lastUserInteraction());
        assertEquals(usefulAt.plusSeconds(1), entry.lastPeerSeen());
        assertEquals(usefulAt.plusSeconds(3), entry.lastUsefulRendezvous());
        assertEquals(1, entry.holePunchRequestsRelayed());
        assertEquals(1, entry.successfulHolePunches());
        assertEquals(usefulAt.plusSeconds(4), entry.lastSuccessfulAssist());
        assertFalse(entry.shouldDecayInactive(Duration.ofDays(7), usefulAt.plus(Duration.ofDays(6))));
        assertTrue(entry.shouldDecayInactive(Duration.ofDays(7), usefulAt.plusSeconds(4).plus(Duration.ofDays(7))));
    }

    private String magnet(int number) { return "magnet:?xt=urn:btih:" + infoHash(number) + "&dn=Swarm" + number; }
    private String infoHash(int number) { return String.format("%040x", number + 1); }
    private SwarmMembershipRepository repository() {
        return new SwarmMembershipRepository(new SqliteDatabase(directory), () -> SwarmAssistSettings.DEFAULT_MAX_ASSIST_SWARMS);
    }
}
