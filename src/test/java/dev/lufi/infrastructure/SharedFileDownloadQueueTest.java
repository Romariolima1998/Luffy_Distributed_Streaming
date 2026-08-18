package dev.lufi.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SharedFileDownloadQueueTest {
    @Test
    void keepsTheInterruptedFileAndAdvancesTheQueueOneAtATime() {
        SharedFileDownloadQueue queue = new SharedFileDownloadQueue();
        queue.select("one.mkv", false);
        SharedFileDownloadQueue.Selection switched = queue.select("two.mkv", true);

        assertEquals("one.mkv", switched.previous());
        assertEquals("two.mkv", switched.active());
        assertEquals(1, switched.queuedFiles());

        SharedFileDownloadQueue.Advance next = queue.complete("two.mkv");
        assertTrue(next.advanced());
        assertEquals("one.mkv", next.next());
        assertEquals("one.mkv", queue.active());
    }

    @Test
    void doesNotDuplicateTheCurrentFileInTheQueue() {
        SharedFileDownloadQueue queue = new SharedFileDownloadQueue();
        queue.select("one.mp3", false);

        SharedFileDownloadQueue.Selection repeated = queue.select("one.mp3", true);

        assertFalse(repeated.changed());
        assertEquals(0, repeated.queuedFiles());
    }

    @Test
    void downloadsExplicitlyQueuedFilesInTheirOriginalOrder() {
        SharedFileDownloadQueue queue = new SharedFileDownloadQueue();
        queue.select("one.mkv", false);
        queue.enqueue("two.mkv");
        queue.enqueue("three.mkv");

        assertEquals("two.mkv", queue.complete("one.mkv").next());
        assertEquals("three.mkv", queue.complete("two.mkv").next());
        assertEquals(null, queue.complete("three.mkv").next());
    }
}
