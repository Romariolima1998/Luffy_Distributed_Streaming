package dev.lufi.infrastructure;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AbuseProtectionSettingsTest {
    @TempDir Path directory;

    @Test void persistsAllSecurityLimits() throws Exception {
        AbuseProtectionSettings settings = new AbuseProtectionSettings(new SettingsRepository(new SqliteDatabase(Files.createDirectory(directory.resolve("db")))));
        settings.setMaxFindNodeRequestsPerMinute(31);
        settings.setMaxForwardedRequestsPerMinute(32);
        settings.setMaxConcurrentRouteSearches(3);
        settings.setMaxConcurrentRendezvousSessions(4);
        settings.setMaxRendezvousRequestsPerPeer(5);
        settings.setMaxPayloadBytes(256);
        settings.setMaxTtl(2);
        settings.setMaxPendingUtpSessions(7);
        settings.setMaxPendingUtpSessionsPerAddress(8);

        var config = settings.config();
        assertEquals(31, config.maxFindNodeRequestsPerMinute());
        assertEquals(32, config.maxForwardedRequestsPerMinute());
        assertEquals(3, config.maxConcurrentRouteSearches());
        assertEquals(4, config.maxConcurrentRendezvousSessions());
        assertEquals(5, config.maxRendezvousRequestsPerPeer());
        assertEquals(256, config.maxPayloadBytes());
        assertEquals(2, config.maxTtl());
        assertEquals(7, config.maxPendingUtpSessions());
        assertEquals(8, config.maxPendingUtpSessionsPerAddress());
    }
}
