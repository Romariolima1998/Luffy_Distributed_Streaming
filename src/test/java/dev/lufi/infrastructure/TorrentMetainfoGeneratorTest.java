package dev.lufi.infrastructure;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TorrentMetainfoGeneratorTest {
    @TempDir Path folder;
    @Test void producesTorrentAndStandardMagnet() throws Exception {
        Path video = folder.resolve("sample.mp4"); Files.writeString(video, "video content");
        var published = new TorrentMetainfoGenerator().publish(video, folder.resolve("torrents"));
        assertTrue(Files.isRegularFile(published.torrentFile()));
        assertEquals(40, published.magnet().infoHash().length());
        byte[] content = Files.readAllBytes(published.torrentFile());
        assertEquals("d4:info", new String(content, 0, 7, StandardCharsets.US_ASCII));
    }
    @Test void producesOneTorrentForAnEntireLibrary() throws Exception {
        Files.writeString(folder.resolve("first.mp4"), "first");
        Files.createDirectories(folder.resolve("season")); Files.writeString(folder.resolve("season").resolve("second.mp4"), "second");
        var published = new TorrentMetainfoGenerator().publishDirectory(folder, folder.resolve("out"));
        assertTrue(Files.isRegularFile(published.torrentFile()));
        assertEquals(folder.getFileName().toString(), published.magnet().displayName().orElseThrow());
        assertEquals(List.of("first.mp4", "season/second.mp4"), LuffyManifest.decode(published.magnet()));
    }
}
