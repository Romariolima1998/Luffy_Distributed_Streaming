package dev.lufi.infrastructure;

import dev.lufi.domain.MagnetLink;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SwarmAssistManagerTest {
    @TempDir Path directory;

    @Test void replacesTheHealthiestEligibleSwarmAndKeepsOnlyPassiveEntries() {
        SwarmAssistPolicy policy = policy(Duration.ZERO);
        SwarmMembershipRepository repository = repository(policy);
        FakeRuntime runtime = new FakeRuntime();
        SwarmAssistManager manager = manager(repository, policy, runtime);
        repository.retainIfHelpful(rawMagnet(1), 2);
        repository.retainIfHelpful(rawMagnet(2), 27);
        runtime.observe(magnet(1), 2, 1, 1, 1);
        runtime.observe(magnet(2), 27, 3, 2, 2);
        runtime.observe(magnet(3), 3, 0, 0, 0);

        SwarmAssistManager.Decision decision = manager.considerTemporaryWatch(magnet(3)).toCompletableFuture().join();

        assertEquals(SwarmMembershipRepository.Retention.REPLACED, decision.retention());
        assertEquals(hash(2), decision.replacedInfoHash());
        assertTrue(runtime.left.contains(hash(2)));
        assertTrue(runtime.joined.contains(hash(3)));
        assertEquals(List.of(hash(1), hash(3)), repository.findAll().stream().map(SwarmMembershipRepository.Membership::infoHash).sorted().toList());
    }

    @Test void doesNotReplaceWithAHealthierCandidateWhenTheListIsFull() {
        SwarmAssistPolicy policy = policy(Duration.ZERO);
        SwarmMembershipRepository repository = repository(policy);
        FakeRuntime runtime = new FakeRuntime();
        SwarmAssistManager manager = manager(repository, policy, runtime);
        repository.retainIfHelpful(rawMagnet(1), 2);
        repository.retainIfHelpful(rawMagnet(2), 4);
        runtime.observe(magnet(1), 2, 1, 1, 1);
        runtime.observe(magnet(2), 4, 2, 1, 1);
        runtime.observe(magnet(3), 30, 3, 2, 2);

        SwarmAssistManager.Decision decision = manager.considerTemporaryWatch(magnet(3)).toCompletableFuture().join();

        assertFalse(decision.retained());
        assertTrue(runtime.joined.isEmpty());
        assertEquals(2, repository.findAll().size());
    }

    @Test void userOwnedSwarmLeavesTheAssistListWithoutTouchingOtherEntries() {
        SwarmAssistPolicy policy = policy(Duration.ZERO);
        SwarmMembershipRepository repository = repository(policy);
        FakeRuntime runtime = new FakeRuntime();
        SwarmAssistManager manager = manager(repository, policy, runtime);
        repository.retainIfHelpful(rawMagnet(1), 2);
        repository.retainIfHelpful(rawMagnet(2), 3);

        manager.promoteToUserOwned(hash(1));

        assertEquals(List.of(hash(2)), repository.findAll().stream().map(SwarmMembershipRepository.Membership::infoHash).toList());
        assertTrue(runtime.left.contains(hash(1)));
    }

    @Test void restoresPersistedEntriesByInvalidatingOldPopulationThenReobservingIt() {
        SwarmAssistPolicy policy = policy(Duration.ZERO);
        SwarmMembershipRepository repository = repository(policy);
        FakeRuntime runtime = new FakeRuntime();
        SwarmAssistManager manager = manager(repository, policy, runtime);
        repository.retainIfHelpful(rawMagnet(1), 8);
        runtime.observe(magnet(1), 3, 1, 0, 1);

        manager.restorePersisted().join();

        assertEquals(List.of(hash(1)), runtime.restored);
        assertEquals(3, repository.findAll().getFirst().observedPeerCount());
        assertTrue(repository.findAll().getFirst().hasFreshPopulation(policy.statsTtl(), Instant.now()));
    }

    private SwarmAssistManager manager(SwarmMembershipRepository repository, SwarmAssistPolicy policy, FakeRuntime runtime) {
        return new SwarmAssistManager(repository, () -> policy, new SwarmNeedEvaluator(), runtime, new P2pDiagnostics());
    }

    private SwarmMembershipRepository repository(SwarmAssistPolicy policy) {
        return new SwarmMembershipRepository(new SqliteDatabase(directory), () -> policy.maximumSwarms(),
                () -> policy.minimumResidence(), policy::replacementThreshold, policy::criticalPeerCount,
                policy::inactiveSwarmDecay);
    }

    private static SwarmAssistPolicy policy(Duration residence) {
        return new SwarmAssistPolicy(2, residence, .20d, 3, 3, 6,
                Duration.ofMinutes(10), Duration.ofHours(6), Duration.ofDays(7));
    }
    private static MagnetLink magnet(int id) { return MagnetLink.parse("magnet:?xt=urn:btih:" + hash(id) + "&dn=swarm-" + id); }
    private static String rawMagnet(int id) { return "magnet:?xt=urn:btih:" + hash(id) + "&dn=swarm-" + id; }
    private static String hash(int id) { return String.format("%040x", id); }

    private static final class FakeRuntime implements SwarmAssistManager.Runtime {
        private final Map<String, Integer> peers = new HashMap<>();
        private final Map<String, SwarmAssistStats> stats = new HashMap<>();
        private final List<String> joined = new ArrayList<>();
        private final List<String> left = new ArrayList<>();
        private final List<String> restored = new ArrayList<>();

        void observe(MagnetLink magnet, int peerCount, int connected, int holePunch, int reachable) {
            peers.put(magnet.infoHash(), peerCount);
            stats.put(magnet.infoHash(), new SwarmAssistStats(magnet.infoHash(), peerCount, connected, holePunch, reachable, Instant.now()));
        }
        @Override public CompletableFuture<Integer> inspect(MagnetLink magnet) {
            return CompletableFuture.completedFuture(peers.getOrDefault(magnet.infoHash(), 0));
        }
        @Override public CompletableFuture<Integer> restore(MagnetLink magnet) {
            restored.add(magnet.infoHash());
            return inspect(magnet);
        }
        @Override public void join(MagnetLink magnet) { joined.add(magnet.infoHash()); }
        @Override public void leave(String infoHash) { left.add(infoHash); }
        @Override public SwarmAssistStats stats(String infoHash) { return stats.get(infoHash); }
        @Override public void applyPolicy(SwarmAssistPolicy policy) { }
    }
}
