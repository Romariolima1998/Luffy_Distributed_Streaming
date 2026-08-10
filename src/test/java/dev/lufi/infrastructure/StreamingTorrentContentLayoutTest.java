package dev.lufi.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class StreamingTorrentContentLayoutTest {
    @Test void mapsSingleFileTorrentDirectlyToTheStorageRoot() {
        Path root = Path.of("cache");

        var content = StreamingTorrentContentLayout.resolve(root, "episodio.mkv", List.of(List.of("episodio.mkv")));

        assertEquals(root, content.folder());
        assertEquals(List.of(root.resolve("episodio.mkv")), content.files());
    }

    @Test void retainsTheTorrentFolderForMultiFileTorrents() {
        Path root = Path.of("cache");

        var content = StreamingTorrentContentLayout.resolve(root, "temporada", List.of(
                List.of("episodio-01.mkv"), List.of("extras", "legenda.srt")));

        assertEquals(root.resolve("temporada"), content.folder());
        assertEquals(List.of(root.resolve("temporada").resolve("episodio-01.mkv"),
                root.resolve("temporada").resolve("extras").resolve("legenda.srt")), content.files());
    }
}
