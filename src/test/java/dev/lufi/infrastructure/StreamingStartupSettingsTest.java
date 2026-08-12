package dev.lufi.infrastructure;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StreamingStartupSettingsTest {
    @Test void keepsTheRecommendedDefaultAndBoundsUserValues() {
        assertEquals(24, BtTorrentGateway.DEFAULT_STREAM_STARTUP_PIECES);
        assertEquals(BtTorrentGateway.MIN_STREAM_STARTUP_PIECES, StreamingStartupSettings.normalize(0));
        assertEquals(24, StreamingStartupSettings.normalize(24));
        assertEquals(BtTorrentGateway.MAX_STREAM_STARTUP_PIECES,
                StreamingStartupSettings.normalize(BtTorrentGateway.MAX_STREAM_STARTUP_PIECES + 1));
    }
}
