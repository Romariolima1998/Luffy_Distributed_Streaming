package dev.lufi.ui;

import dev.lufi.infrastructure.BtTorrentGateway;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Selects the media path without ever treating a preallocated partial file as complete. */
final class StreamingMediaRouteResolver {
    enum Route {
        LOCAL_FILE_DIRECT,
        LOCAL_HTTP,
        WAIT_FOR_BUFFER
    }

    private StreamingMediaRouteResolver() { }

    static Route resolve(BtTorrentGateway.StreamingBufferStatus buffer, Path file) {
        Objects.requireNonNull(buffer, "buffer");
        Objects.requireNonNull(file, "file");
        LocalFileMediaSource localFile = new LocalFileMediaSource(file);
        if (isComplete(buffer) && localFile.isReadableFile()) return Route.LOCAL_FILE_DIRECT;
        if (buffer.playable() && Files.isRegularFile(file) && readableSize(file) > 0L) return Route.LOCAL_HTTP;
        return Route.WAIT_FOR_BUFFER;
    }

    private static boolean isComplete(BtTorrentGateway.StreamingBufferStatus buffer) {
        return buffer.totalPieces() > 0 && buffer.verifiedPieces() >= buffer.totalPieces();
    }

    private static long readableSize(Path file) {
        try {
            return Files.size(file);
        } catch (Exception ignored) {
            return 0L;
        }
    }
}
