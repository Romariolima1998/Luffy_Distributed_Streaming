package dev.lufi.infrastructure;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StreamingReadAheadPolicyTest {
    @Test
    void adaptsReadAheadToTheTorrentPieceSizeWithinTheConfiguredBounds() {
        StreamingReadAheadPolicy policy = StreamingReadAheadPolicy.defaults();

        assertEquals(20, policy.piecesFor(256 * 1024L));
        assertEquals(8, policy.piecesFor(1024 * 1024L));
        assertEquals(5, policy.piecesFor(2 * 1024 * 1024L));
    }

    @Test
    void acceptsAnExplicitReadAheadConfiguration() {
        StreamingReadAheadPolicy policy = new StreamingReadAheadPolicy(3, 12, 6L * 1024L * 1024L);

        assertEquals(6, policy.piecesFor(1024 * 1024L));
        assertEquals(3, policy.piecesFor(8L * 1024L * 1024L));
        assertEquals(12, policy.piecesFor(128 * 1024L));
    }
}
