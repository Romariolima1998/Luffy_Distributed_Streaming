package dev.lufi.infrastructure;

import dev.lufi.domain.MagnetLink;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SwarmNeedEvaluatorTest {
    private static final Instant NOW = Instant.parse("2026-08-04T18:00:00Z");
    private static final SwarmAssistPolicy POLICY = new SwarmAssistPolicy(25, Duration.ofMinutes(30), .20d,
            3, 3, 75, Duration.ofMinutes(10), Duration.ofHours(6), Duration.ofDays(7));

    @Test void favorsSmallDisconnectedSwarmsWithNoRendezvousPeers() {
        SwarmNeedEvaluator evaluator = new SwarmNeedEvaluator();
        SwarmNeedScore fragile = evaluator.evaluate(entry(1, new SwarmAssistStats(hash(1), 2, 0, 0, 0, NOW)), POLICY, NOW);
        SwarmNeedScore healthy = evaluator.evaluate(entry(2, new SwarmAssistStats(hash(2), 24, 3, 2, 2, NOW)), POLICY, NOW);

        assertTrue(fragile.value() > healthy.value());
        assertTrue(fragile.instabilityAdjustment() > healthy.instabilityAdjustment());
    }

    @Test void refusesToScoreStatisticsWhoseTtlHasExpired() {
        SwarmNeedEvaluator evaluator = new SwarmNeedEvaluator();
        SwarmNeedScore stale = evaluator.evaluate(entry(3, new SwarmAssistStats(hash(3), 1, 0, 0, 0,
                NOW.minus(Duration.ofMinutes(11)))), POLICY, NOW);

        assertTrue(!stale.canDriveRetention());
    }

    private static SwarmAssistEntry entry(int id, SwarmAssistStats stats) {
        return new SwarmAssistEntry(hash(id), MagnetLink.parse(magnet(id)), stats, NOW, NOW, null, null, 0, 0, null);
    }
    private static String magnet(int id) { return "magnet:?xt=urn:btih:" + hash(id) + "&dn=swarm-" + id; }
    private static String hash(int id) { return String.format("%040x", id); }
}
