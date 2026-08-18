package dev.lufi.ui;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LuffySingleInstanceTest {
    @Test void forwardsMagnetToTheExistingLoopbackOnlyInstance() throws Exception {
        var first = LuffySingleInstance.acquireOrForward(new String[0], 0);
        assertTrue(first.isPrimary());
        try (LuffySingleInstance primary = first.primary()) {
            var received = new LinkedBlockingQueue<LuffySingleInstance.Request>();
            primary.setRequestHandler(received::add);

            String magnet = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567";
            var second = LuffySingleInstance.acquireOrForward(new String[]{magnet}, primary.port());

            assertFalse(second.isPrimary());
            assertTrue(second.forwarded());
            var request = received.poll(2, TimeUnit.SECONDS);
            assertNotNull(request);
            assertEquals(LuffySingleInstance.RequestKind.MAGNET, request.kind());
            assertEquals(magnet, request.value());

            String torrentFile = "C:\\Downloads\\movie.torrent";
            var third = LuffySingleInstance.acquireOrForward(new String[]{torrentFile}, primary.port());

            assertFalse(third.isPrimary());
            assertTrue(third.forwarded());
            var torrentRequest = received.poll(2, TimeUnit.SECONDS);
            assertNotNull(torrentRequest);
            assertEquals(LuffySingleInstance.RequestKind.TORRENT_FILE, torrentRequest.kind());
            assertEquals(torrentFile, torrentRequest.value());
        }
    }

    @Test void identifiesTorrentFileArgumentsSeparatelyFromMagnets() {
        var request = LuffySingleInstance.Request.fromArguments(new String[]{"C:\\Downloads\\movie.torrent"});

        assertEquals(LuffySingleInstance.RequestKind.TORRENT_FILE, request.kind());
        assertEquals("C:\\Downloads\\movie.torrent", request.value());
    }
}
