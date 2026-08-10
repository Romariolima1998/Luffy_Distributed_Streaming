package dev.lufi.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StreamingPiecePrefixTrackerTest {
    @Test void doesNotTreatScatteredPiecesAsAnInitialPlayableBuffer() {
        StreamingPiecePrefixTracker tracker = new StreamingPiecePrefixTracker();

        tracker.record("ABC", 6);
        tracker.record("ABC", 7);
        tracker.record("ABC", 12);

        assertEquals(0, tracker.contiguousPrefix("abc", 100));
    }

    @Test void countsOnlyTheContinuousPrefixFromPieceZero() {
        StreamingPiecePrefixTracker tracker = new StreamingPiecePrefixTracker();

        tracker.record("abc", 0);
        tracker.record("abc", 2);
        assertEquals(1, tracker.contiguousPrefix("abc", 100));
        tracker.record("abc", 1);

        assertEquals(3, tracker.contiguousPrefix("abc", 100));
    }

    @Test void clearsTheOldTorrentStateBeforeAnotherStreamingAttempt() {
        StreamingPiecePrefixTracker tracker = new StreamingPiecePrefixTracker();
        tracker.record("abc", 0);

        tracker.clear("abc");

        assertEquals(0, tracker.contiguousPrefix("abc", 100));
    }

    @Test void refusesToOpenPreallocatedFilesWithoutTheBeginningOfTheStream() {
        var scattered = new BtTorrentGateway.StreamingBufferStatus(15_000_000, 12, 0, 1_385, 12, true);
        var contiguous = new BtTorrentGateway.StreamingBufferStatus(15_000_000, 12, 4, 1_385, 12, true);

        assertFalse(scattered.playable());
        assertTrue(contiguous.playable());
    }
}
