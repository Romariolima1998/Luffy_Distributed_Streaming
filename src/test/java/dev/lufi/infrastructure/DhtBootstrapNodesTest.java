package dev.lufi.infrastructure;

import bt.dht.DHTConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DhtBootstrapNodesTest {
    @Test void keepsTheLibraryRoutersAndAddsTheIpv4Fallback() {
        DHTConfig config = new DHTConfig();

        DhtBootstrapNodes.configure(config, false);

        assertTrue(config.shouldUseRouterBootstrap());
        assertEquals(1, config.getBootstrapNodes().size());
        var fallback = config.getBootstrapNodes().iterator().next();
        assertEquals("dht.libtorrent.org", fallback.getHostname());
        assertEquals(25_401, fallback.getPort());
    }

    @Test void doesNotReuseAnIpv4FallbackForIpv6() {
        DHTConfig config = new DHTConfig();

        DhtBootstrapNodes.configure(config, true);

        assertTrue(config.shouldUseRouterBootstrap());
        assertFalse(config.getBootstrapNodes().iterator().hasNext());
    }
}
