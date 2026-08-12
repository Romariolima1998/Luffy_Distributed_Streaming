package dev.lufi.infrastructure;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamingPriorityWindowTest {
    @Test
    void replacesTheOldPriorityWindowAfterASeek() {
        StreamingReadAheadPolicy policy = new StreamingReadAheadPolicy(2, 2, 2L * 1024L * 1024L);
        StreamingPriorityWindow before = StreamingPriorityWindow.create(range(10, 11), 100, policy);
        StreamingPriorityWindow after = StreamingPriorityWindow.create(range(70, 71), 100, policy);

        assertTrue(after.isSeekFrom(before));
        assertTrue(before.pieces().get(10));
        assertFalse(after.pieces().get(10));
        assertTrue(after.pieces().get(70));
        assertTrue(after.pieces().get(73));
        assertFalse(after.pieces().get(74));
    }

    @Test
    void keepsSequentialReadsInTheSamePriorityRegion() {
        StreamingReadAheadPolicy policy = new StreamingReadAheadPolicy(4, 4, 4L * 1024L * 1024L);
        StreamingPriorityWindow current = StreamingPriorityWindow.create(range(10, 11), 100, policy);
        StreamingPriorityWindow next = StreamingPriorityWindow.create(range(14, 15), 100, policy);

        assertFalse(next.isSeekFrom(current));
    }

    @Test
    void limitsAnEndOfFileProbeToTheNextMissingPrefixPieces() {
        StreamingReadAheadPolicy policy = new StreamingReadAheadPolicy(3, 3, 3L * 1024L * 1024L);

        StreamingPriorityWindow window = StreamingPriorityWindow.forStreamingRequest(range(0, 99), 100, 24, policy);

        assertFalse(window.pieces().get(0));
        assertTrue(window.pieces().get(24));
        assertTrue(window.pieces().get(26));
        assertFalse(window.pieces().get(27));
        assertEquals(24, window.priorityStartPiece());
        assertEquals(26, window.priorityEndPiece());
    }

    private static BtTorrentGateway.StreamingPieceRange range(int startPiece, int endPiece) {
        return new BtTorrentGateway.StreamingPieceRange(0, 0, 0, 0, startPiece, endPiece,
                1024L * 1024L, 1024L * 1024L, false, false);
    }
}
