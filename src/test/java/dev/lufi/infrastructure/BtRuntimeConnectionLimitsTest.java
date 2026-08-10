package dev.lufi.infrastructure;

import bt.runtime.Config;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BtRuntimeConnectionLimitsTest {
    @Test void alignsNativeBtCoreLimitsWithTheLuffyConnectionBudget() {
        ConnectionLimits limits = new ConnectionLimits(12, 60, 24, 32, 24, 128);
        Config config = new Config();

        BtTorrentGateway.applyConnectionLimits(config, limits);

        assertEquals(128, config.getMaxPeerConnections());
        assertEquals(32, config.getMaxPeerConnectionsPerTorrent());
        assertEquals(24, config.getMaxPendingConnectionRequests());
        assertEquals(32, config.getNumberOfPeersToRequestFromTracker());
    }
}
