package dev.lufi.infrastructure;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamingFileSelectionTest {
    @Test void matchesUiPathWithTorrentPathSeparators() {
        assertTrue(StreamingFileSelection.matches("temporada/episodio.mkv", List.of("temporada", "episodio.mkv")));
        assertTrue(StreamingFileSelection.matches("temporada\\episodio.mkv", List.of("temporada", "episodio.mkv")));
    }

    @Test void preservesTheSingleFileTorrentPath() {
        assertTrue(StreamingFileSelection.matches("", List.of()));
        assertTrue(StreamingFileSelection.matches("outro.mkv", List.of()));
    }

    @Test void doesNotUseSingleFileFallbackForFolderPaths() {
        assertFalse(StreamingFileSelection.matches("temporada/outro.mkv", List.of()));
    }
}
