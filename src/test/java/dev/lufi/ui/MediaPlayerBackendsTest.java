package dev.lufi.ui;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaPlayerBackendsTest {
    @Test
    void selectsLibVlcForEveryMediaRoute() {
        assertTrue(MediaPlayerBackends.requiresMediaBackend(new LocalFileMediaSource(Path.of("movie.mkv"))));
        assertTrue(MediaPlayerBackends.requiresMediaBackend(
                new TorrentStreamingMediaSource(URI.create("http://127.0.0.1:18991/media/session/file"))));

        MediaPlayerBackend backend = MediaPlayerBackends.createDefaultBackend();
        try {
            assertInstanceOf(LibVlcPlayerBackend.class, backend);
        } finally {
            backend.release();
        }
    }
}
