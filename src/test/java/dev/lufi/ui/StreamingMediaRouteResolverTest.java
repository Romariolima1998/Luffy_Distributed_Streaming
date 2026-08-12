package dev.lufi.ui;

import dev.lufi.infrastructure.BtTorrentGateway;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StreamingMediaRouteResolverTest {
    @Test
    void usesFileUriOnlyAfterEveryPieceIsVerified() throws Exception {
        Path file = Files.createTempFile("luffy-stream-route-complete-", ".mkv");
        try {
            Files.writeString(file, "complete");
            var complete = new BtTorrentGateway.StreamingBufferStatus(8, 4, 4, 4, 2, true);

            assertEquals(StreamingMediaRouteResolver.Route.LOCAL_FILE_DIRECT,
                    StreamingMediaRouteResolver.resolve(complete, file));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void usesLoopbackHttpForAPlayableButIncompleteFile() throws Exception {
        Path file = Files.createTempFile("luffy-stream-route-partial-", ".mkv");
        try {
            Files.writeString(file, "preallocated-content");
            var partial = new BtTorrentGateway.StreamingBufferStatus(4, 2, 2, 4, 2, true);

            assertEquals(StreamingMediaRouteResolver.Route.LOCAL_HTTP,
                    StreamingMediaRouteResolver.resolve(partial, file));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void waitsUntilTheInitialVerifiedPrefixIsPlayable() throws Exception {
        Path file = Files.createTempFile("luffy-stream-route-wait-", ".mkv");
        try {
            Files.writeString(file, "preallocated-content");
            var waiting = new BtTorrentGateway.StreamingBufferStatus(4, 2, 1, 4, 2, true);

            assertEquals(StreamingMediaRouteResolver.Route.WAIT_FOR_BUFFER,
                    StreamingMediaRouteResolver.resolve(waiting, file));
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
