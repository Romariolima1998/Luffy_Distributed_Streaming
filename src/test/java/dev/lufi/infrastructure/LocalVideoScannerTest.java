package dev.lufi.infrastructure;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertEquals;

class LocalVideoScannerTest {
    @TempDir Path folder;
    @Test void findsSupportedFormatsInNestedFolders() throws IOException {
        Files.writeString(folder.resolve("movie.MP4"), "video");
        Files.createDirectory(folder.resolve("season"));
        Files.writeString(folder.resolve("season").resolve("episode.m2ts"), "video");
        Files.writeString(folder.resolve("notes.txt"), "not a video");
        assertEquals(2, new LocalVideoScanner().scan(folder).size());
    }
}
