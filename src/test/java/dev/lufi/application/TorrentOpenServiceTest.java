package dev.lufi.application;

import dev.lufi.infrastructure.TorrentMetainfoGenerator;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TorrentOpenServiceTest {
    @TempDir Path temporaryDirectory;

    @Test void acceptsAMagnetThroughTheSameNormalizedRequest() {
        var request = new TorrentOpenService().openMagnet("magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=movie");

        assertFalse(request.hasEmbeddedMetadata());
        assertEquals("0123456789abcdef0123456789abcdef01234567", request.magnet().infoHash());
    }

    @Test void decodesATorrentFileAndKeepsItsOriginalMetadataSource() throws Exception {
        Path video = temporaryDirectory.resolve("sample.mp4");
        Files.writeString(video, "sample media");
        var published = new TorrentMetainfoGenerator().publish(video, temporaryDirectory.resolve("metainfo"));

        var request = new TorrentOpenService().openTorrentFile(published.torrentFile());

        assertTrue(request.hasEmbeddedMetadata());
        assertEquals(published.magnet().infoHash(), request.magnet().infoHash());
        assertEquals("sample.mp4", request.magnet().displayName().orElseThrow());
        assertEquals(published.torrentFile().toAbsolutePath().normalize(), request.torrentFile().orElseThrow());
    }

    @Test void rejectsAFileThatIsNotATorrent() throws Exception {
        Path text = temporaryDirectory.resolve("not-a-torrent.txt");
        Files.writeString(text, "not bencode");

        assertThrows(IllegalArgumentException.class, () -> new TorrentOpenService().openTorrentFile(text));
    }
}
