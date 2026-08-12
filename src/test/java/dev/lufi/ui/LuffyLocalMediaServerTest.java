package dev.lufi.ui;

import dev.lufi.infrastructure.P2pDiagnostics;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LuffyLocalMediaServerTest {
    @Test
    void exposesOnlyTheVerifiedPrefixAndSupportsRanges() throws Exception {
        Path file = Files.createTempFile("luffy-local-media-", ".mp4");
        byte[] content = "0123456789abcdefghijklmnopqrstuv".getBytes();
        Files.write(file, content);
        AtomicReference<LuffyLocalMediaServer.VerifiedMediaWindow> window = new AtomicReference<>(
                new LuffyLocalMediaServer.VerifiedMediaWindow(content.length, 10, true));
        AtomicReference<String> requestedRange = new AtomicReference<>();
        P2pDiagnostics diagnostics = new P2pDiagnostics();
        LuffyLocalMediaServer server = new LuffyLocalMediaServer(diagnostics, Duration.ofMillis(100));
        try {
            TorrentStreamingMediaSource source = server.register(file, window::get,
                    (start, end) -> requestedRange.set(start + "-" + end));
            HttpClient client = HttpClient.newHttpClient();
            assertEquals("127.0.0.1", source.uri().getHost());
            assertTrue(server.boundAddress().getAddress().isLoopbackAddress());
            String[] sourcePath = source.uri().getPath().split("/");
            assertEquals(4, sourcePath.length);
            assertEquals("media", sourcePath[1]);
            assertTrue(sourcePath[2].matches("[0-9a-f]{32}"));
            assertTrue(sourcePath[3].matches("[0-9a-f]{32}"));
            TorrentStreamingMediaSource otherSource = server.register(file, window::get);
            assertNotEquals(source.uri(), otherSource.uri());

            HttpResponse<byte[]> firstRange = client.send(request(source.uri(), "bytes=2-7"), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(206, firstRange.statusCode());
            assertEquals("bytes 2-7/32", firstRange.headers().firstValue("Content-Range").orElseThrow());
            assertEquals("bytes", firstRange.headers().firstValue("Accept-Ranges").orElseThrow());
            assertEquals("6", firstRange.headers().firstValue("Content-Length").orElseThrow());
            assertEquals("video/mp4", firstRange.headers().firstValue("Content-Type").orElseThrow());
            assertArrayEquals("234567".getBytes(), firstRange.body());
            assertEquals("2-7", requestedRange.get());

            HttpResponse<Void> rangeHead = client.send(head(source.uri(), "bytes=6-11"), HttpResponse.BodyHandlers.discarding());
            assertEquals(206, rangeHead.statusCode());
            assertEquals("bytes 6-11/32", rangeHead.headers().firstValue("Content-Range").orElseThrow());
            assertEquals("6", rangeHead.headers().firstValue("Content-Length").orElseThrow());

            window.set(new LuffyLocalMediaServer.VerifiedMediaWindow(content.length, 10, false));
            HttpResponse<byte[]> unsafeRange = client.send(request(source.uri(), "bytes=10-15"), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(503, unsafeRange.statusCode());

            window.set(new LuffyLocalMediaServer.VerifiedMediaWindow(content.length, content.length, true));
            HttpResponse<byte[]> complete = client.send(HttpRequest.newBuilder(source.uri()).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, complete.statusCode());
            assertEquals("32", complete.headers().firstValue("Content-Length").orElseThrow());
            assertArrayEquals(content, complete.body());

            HttpResponse<Void> fullHead = client.send(head(source.uri(), null), HttpResponse.BodyHandlers.discarding());
            assertEquals(200, fullHead.statusCode());
            assertEquals("32", fullHead.headers().firstValue("Content-Length").orElseThrow());

            HttpResponse<Void> invalidRange = client.send(request(source.uri(), "bytes=32-33"), HttpResponse.BodyHandlers.discarding());
            assertEquals(416, invalidRange.statusCode());
            assertEquals("bytes */32", invalidRange.headers().firstValue("Content-Range").orElseThrow());

            URI missing = URI.create(source.uri().toString().replaceFirst("[0-9a-f]+$", "missing"));
            HttpResponse<Void> absent = client.send(HttpRequest.newBuilder(missing).GET().build(), HttpResponse.BodyHandlers.discarding());
            assertEquals(404, absent.statusCode());

            URI missingFileId = URI.create(source.uri().toString().replaceFirst("/[^/]+$", ""));
            HttpResponse<Void> noFile = client.send(HttpRequest.newBuilder(missingFileId).GET().build(), HttpResponse.BodyHandlers.discarding());
            assertEquals(404, noFile.statusCode());

            server.clearRegistrations();
            HttpResponse<Void> revoked = client.send(HttpRequest.newBuilder(source.uri()).GET().build(), HttpResponse.BodyHandlers.discarding());
            assertEquals(404, revoked.statusCode());
        } finally {
            server.close();
            Files.deleteIfExists(file);
        }
    }

    @Test
    void answersALongInitialRangeWithOnlyTheSafeVerifiedPrefix() throws Exception {
        Path file = Files.createTempFile("luffy-local-media-progressive-", ".mkv");
        byte[] content = "0123456789abcdefghijklmnopqrstuv".getBytes();
        Files.write(file, content);
        AtomicReference<LuffyLocalMediaServer.VerifiedMediaWindow> window = new AtomicReference<>(
                new LuffyLocalMediaServer.VerifiedMediaWindow(content.length, 10, true));
        AtomicReference<String> requestedRange = new AtomicReference<>();
        LuffyLocalMediaServer server = new LuffyLocalMediaServer(new P2pDiagnostics(), Duration.ofMillis(100));
        try {
            TorrentStreamingMediaSource source = server.register(file, window::get,
                    (start, end) -> requestedRange.set(start + "-" + end));

            HttpResponse<byte[]> response = HttpClient.newHttpClient().send(request(source.uri(), "bytes=0-31"),
                    HttpResponse.BodyHandlers.ofByteArray());

            assertEquals(206, response.statusCode());
            assertEquals("bytes 0-9/32", response.headers().firstValue("Content-Range").orElseThrow());
            assertEquals("10", response.headers().firstValue("Content-Length").orElseThrow());
            assertArrayEquals("0123456789".getBytes(), response.body());
            assertEquals("0-31", requestedRange.get());
        } finally {
            server.close();
            Files.deleteIfExists(file);
        }
    }

    @Test
    void servesASoughtRangeOnlyAfterItsOwningPiecesAreVerified() throws Exception {
        Path file = Files.createTempFile("luffy-local-media-range-", ".mkv");
        byte[] content = "0123456789abcdefghijklmnopqrstuv".getBytes();
        Files.write(file, content);
        AtomicBoolean requested = new AtomicBoolean();
        AtomicBoolean owningPiecesVerified = new AtomicBoolean();
        AtomicBoolean sessionActive = new AtomicBoolean(true);
        List<Boolean> bufferingEvents = new CopyOnWriteArrayList<>();
        P2pDiagnostics diagnostics = new P2pDiagnostics();
        LuffyLocalMediaServer server = new LuffyLocalMediaServer(diagnostics, Duration.ofMillis(100));
        try {
            TorrentStreamingMediaSource source = server.register(file,
                    () -> new LuffyLocalMediaServer.VerifiedMediaWindow(content.length, 0, sessionActive.get()),
                    (start, end) -> requested.set(true),
                    (start, end) -> owningPiecesVerified.get() && start == 12 && end == 15,
                    (buffering, start, end) -> bufferingEvents.add(buffering),
                    (start, end) -> new LuffyLocalMediaServer.RangeProgress(2,
                            owningPiecesVerified.get() ? 2 : 0));
            HttpClient client = HttpClient.newHttpClient();

            var waitingResponse = client.sendAsync(request(source.uri(), "bytes=12-15"),
                    HttpResponse.BodyHandlers.ofByteArray());
            Thread.sleep(150L);
            assertFalse(waitingResponse.isDone());
            assertTrue(requested.get());
            assertEquals(List.of(true), bufferingEvents);

            owningPiecesVerified.set(true);
            HttpResponse<byte[]> verified = waitingResponse.get(2, TimeUnit.SECONDS);
            assertEquals(206, verified.statusCode());
            assertArrayEquals("cdef".getBytes(), verified.body());
            assertEquals(List.of(true, false), bufferingEvents);
            assertTrue(diagnostics.snapshot().contains("[STREAM-HTTP] rangeStart=12; rangeEnd=15; bytesRequested=4; piecesRequired=2; piecesReady=2;"));

            owningPiecesVerified.set(false);
            sessionActive.set(false);
            HttpResponse<byte[]> adjacentUnverified = client.send(request(source.uri(), "bytes=11-15"),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(503, adjacentUnverified.statusCode());
        } finally {
            server.close();
            Files.deleteIfExists(file);
        }
    }

    @Test
    void sendsSimpleContainerHintsWithoutDependingOnPlatformMimeDetection() throws Exception {
        Path directory = Files.createTempDirectory("luffy-local-media-mime-");
        byte[] content = "video".getBytes();
        LuffyLocalMediaServer server = new LuffyLocalMediaServer(new P2pDiagnostics());
        try {
            HttpClient client = HttpClient.newHttpClient();
            for (Map.Entry<String, String> expected : Map.of(
                    "movie.mp4", "video/mp4",
                    "movie.mkv", "video/x-matroska",
                    "movie.webm", "video/webm",
                    "movie.unknown", "application/octet-stream").entrySet()) {
                Path file = directory.resolve(expected.getKey());
                Files.write(file, content);
                TorrentStreamingMediaSource source = server.register(file,
                        () -> new LuffyLocalMediaServer.VerifiedMediaWindow(content.length, content.length, true));
                HttpResponse<Void> response = client.send(head(source.uri(), null), HttpResponse.BodyHandlers.discarding());
                assertEquals(200, response.statusCode());
                assertEquals(expected.getValue(), response.headers().firstValue("Content-Type").orElseThrow());
            }
        } finally {
            server.close();
            try (var files = Files.walk(directory)) {
                files.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    private static HttpRequest request(URI uri, String range) {
        return HttpRequest.newBuilder(uri).header("Range", range).GET().build();
    }

    private static HttpRequest head(URI uri, String range) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri);
        if (range != null) request.header("Range", range);
        return request.method("HEAD", HttpRequest.BodyPublishers.noBody()).build();
    }
}
