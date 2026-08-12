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

    @Test void considersARangeAvailableOnlyWhenEveryOwningPieceWasVerified() {
        StreamingPiecePrefixTracker tracker = new StreamingPiecePrefixTracker();
        tracker.record("abc", 4);
        tracker.record("abc", 6);

        assertFalse(tracker.containsAll("abc", 4, 6));
        assertFalse(tracker.containsAll("abc", 3, 4));

        tracker.record("abc", 5);
        assertTrue(tracker.containsAll("abc", 4, 6));
    }

    @Test void waitsForTheEntireConfiguredContinuousStartupBuffer() {
        var scattered = new BtTorrentGateway.StreamingBufferStatus(30_000_000, 24, 4, 1_385, 24, true);
        var contiguous = new BtTorrentGateway.StreamingBufferStatus(30_000_000, 24, 24, 1_385, 24, true);

        assertFalse(scattered.playable());
        assertTrue(contiguous.playable());
    }
}
