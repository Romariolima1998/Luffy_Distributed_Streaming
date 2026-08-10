package dev.lufi.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class DhtLookupRuntimeSettingsTest {
    @Test void defaultsToABoundedFifteenSecondStartup() {
        assertEquals(Duration.ofSeconds(15), DhtLookupRuntimeSettings.defaults().dhtStartupTimeout());
        assertEquals(Duration.ofSeconds(5), DhtLookupRuntimeSettings.defaults().dhtRetryBackoff());
    }

    @Test void rejectsNonPositiveStartupTimeouts() {
        assertThrows(IllegalArgumentException.class, () -> new DhtLookupRuntimeSettings(Duration.ZERO, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new DhtLookupRuntimeSettings(Duration.ofSeconds(-1), Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new DhtLookupRuntimeSettings(Duration.ofSeconds(1), Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new DhtLookupRuntimeSettings(Duration.ofSeconds(1), Duration.ofSeconds(-1)));
    }
}
