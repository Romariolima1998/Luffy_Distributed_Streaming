package dev.lufi.infrastructure;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SwarmAssistSettingsTest {
    @TempDir Path directory;

    @Test void usesDefaultAndThenPersistedConfigurableMaximum() {
        SwarmAssistSettings settings = new SwarmAssistSettings(new SettingsRepository(new SqliteDatabase(directory)));
        assertEquals(SwarmAssistSettings.DEFAULT_MAX_ASSIST_SWARMS, settings.maxAssistSwarms());
        settings.setMaxAssistSwarms(7);
        assertEquals(7, settings.maxAssistSwarms());
        assertEquals(SwarmAssistSettings.DEFAULT_MIN_ASSIST_RESIDENCE, settings.minAssistResidence());
        assertEquals(SwarmAssistSettings.DEFAULT_REPLACEMENT_THRESHOLD, settings.replacementThreshold());
        assertEquals(SwarmAssistSettings.DEFAULT_CRITICAL_SWARM_PEER_COUNT, settings.criticalSwarmPeerCount());
        assertEquals(SwarmAssistSettings.SWARM_STATS_TTL, settings.swarmStatsTtl());
        assertEquals(SwarmAssistSettings.DEFAULT_EMPTY_SWARM_DECAY, settings.emptySwarmDecay());
        assertEquals(SwarmAssistSettings.DEFAULT_INACTIVE_SWARM_DECAY, settings.inactiveSwarmDecay());
        assertEquals(SwarmAssistPolicy.DEFAULT_MAXIMUM_CONNECTIONS_PER_SWARM, settings.maxAssistConnectionsPerSwarm());
        assertEquals(SwarmAssistPolicy.DEFAULT_MAXIMUM_CONNECTIONS_TOTAL, settings.maxAssistConnectionsTotal());
        settings.setMinAssistResidence(Duration.ofMinutes(5));
        settings.setReplacementThreshold(.35d);
        settings.setCriticalSwarmPeerCount(2);
        settings.setSwarmStatsTtl(Duration.ofMinutes(4));
        settings.setEmptySwarmDecay(Duration.ofMinutes(45));
        settings.setInactiveSwarmDecay(Duration.ofDays(3));
        settings.setMaxAssistConnectionsPerSwarm(2);
        settings.setMaxAssistConnectionsTotal(12);
        assertEquals(Duration.ofMinutes(5), settings.minAssistResidence());
        assertEquals(.35d, settings.replacementThreshold());
        assertEquals(2, settings.criticalSwarmPeerCount());
        assertEquals(Duration.ofMinutes(4), settings.swarmStatsTtl());
        assertEquals(Duration.ofMinutes(45), settings.emptySwarmDecay());
        assertEquals(Duration.ofDays(3), settings.inactiveSwarmDecay());
        assertEquals(2, settings.maxAssistConnectionsPerSwarm());
        assertEquals(12, settings.maxAssistConnectionsTotal());
    }
}
