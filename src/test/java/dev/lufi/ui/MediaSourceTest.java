package dev.lufi.ui;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MediaSourceTest {
    @Test
    void localFileSourceExposesAFileUri() {
        LocalFileMediaSource source = new LocalFileMediaSource(Path.of("videos", "arquivo de teste.mkv"));

        assertEquals("file", source.uri().getScheme());
        assertEquals("LOCAL_FILE", source.kind());
    }

    @Test
    void torrentStreamingSourceAcceptsLocalHttpUri() {
        TorrentStreamingMediaSource source = new TorrentStreamingMediaSource(
                URI.create("http://127.0.0.1:18991/media/token-123"));

        assertEquals("TORRENT_STREAMING", source.kind());
        assertEquals("127.0.0.1", source.uri().getHost());
    }

    @Test
    void torrentStreamingSourceRejectsRemoteOrNonHttpUris() {
        assertThrows(IllegalArgumentException.class,
                () -> new TorrentStreamingMediaSource(URI.create("https://example.com/media/token")));
        assertThrows(IllegalArgumentException.class,
                () -> new TorrentStreamingMediaSource(URI.create("file:///tmp/video.mkv")));
    }
}
