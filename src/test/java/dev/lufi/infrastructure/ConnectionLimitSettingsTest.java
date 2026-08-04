package dev.lufi.infrastructure;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConnectionLimitSettingsTest {
    @TempDir Path directory;

    @Test void persistsAllSixIndependentConnectionLimits() {
        ConnectionLimitSettings settings = new ConnectionLimitSettings(new SettingsRepository(new SqliteDatabase(directory)));
        assertEquals(ConnectionLimits.defaults(), settings.limits());

        settings.setMaxOverlayConnections(9);
        settings.setMaxAssistConnections(18);
        settings.setMaxSeedConnections(11);
        settings.setMaxDownloadConnections(15);
        settings.setMaxPendingConnections(13);
        settings.setMaxTotalConnections(50);

        assertEquals(new ConnectionLimits(9, 18, 11, 15, 13, 50), settings.limits());
    }

    @Test void protectsTheDownloadAndSeedReservationWhenPersistedTotalIsTooSmall() {
        ConnectionLimitSettings settings = new ConnectionLimitSettings(new SettingsRepository(new SqliteDatabase(directory)));
        settings.setMaxSeedConnections(12);
        settings.setMaxDownloadConnections(20);
        settings.setMaxTotalConnections(8);

        assertEquals(32, settings.limits().maxTotalConnections());
    }
}
