package dev.lufi.infrastructure.overlay;

import bt.metainfo.TorrentId;
import bt.net.ConnectionKey;
import bt.net.InetPeer;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteForwardingLimiterTest {
    private static final TorrentId TORRENT = TorrentId.fromBytes(HexFormat.of().parseHex(
            "0123456789012345678901234567890123456789"));

    @Test void excludesPeerWhenMessageWindowOrBackoffIsActive() {
        FindNodeRoutingConfig config = new FindNodeRoutingConfig(4, 6, 3, Duration.ofSeconds(10),
                Duration.ofMinutes(2), Duration.ofSeconds(5), Duration.ofMinutes(1), 1);
        RouteForwardingLimiter limiter = new RouteForwardingLimiter(config);
        ConnectionKey peer = key();
        Instant now = Instant.parse("2026-07-30T21:00:00Z");

        assertTrue(limiter.canForward(peer, now));
        limiter.recordForward(peer, now);
        assertFalse(limiter.canForward(peer, now.plusSeconds(1)));
        assertTrue(limiter.canForward(peer, now.plus(Duration.ofMinutes(1)).plusSeconds(1)));

        RouteForwardingLimiter backoffLimiter = new RouteForwardingLimiter(new FindNodeRoutingConfig(4, 6, 3,
                Duration.ofSeconds(10), Duration.ofMinutes(2), Duration.ofSeconds(5), Duration.ofMinutes(1), 10));
        backoffLimiter.recordFailure(peer, now);
        assertFalse(backoffLimiter.canForward(peer, now.plusSeconds(1)));
        assertTrue(backoffLimiter.canForward(peer, now.plusSeconds(6)));
    }

    private static ConnectionKey key() {
        try {
            InetAddress address = InetAddress.getByName("127.0.0.2");
            return new ConnectionKey(InetPeer.build(address, 7_002), 7_002, TORRENT);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }
}
