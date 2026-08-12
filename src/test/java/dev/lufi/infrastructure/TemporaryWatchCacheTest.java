package dev.lufi.infrastructure;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class TemporaryWatchCacheTest {
    @Test
    void removesEveryTemporaryWatchFileAndItsRoot() throws Exception {
        Path root = Files.createTempDirectory("luffy-watch-cleanup-test-");
        Files.createDirectories(root.resolve("season").resolve("subtitles"));
        Files.writeString(root.resolve("season").resolve("episode.mkv"), "temporary media");
        Files.writeString(root.resolve("season").resolve("subtitles").resolve("episode.srt"), "temporary subtitle");

        TemporaryWatchCache.CleanupResult result = TemporaryWatchCache.delete(root);

        assertTrue(result.removed());
        assertTrue(result.deletedEntries() >= 3);
        assertFalse(Files.exists(root));
    }
}
