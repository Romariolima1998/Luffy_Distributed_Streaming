package dev.lufi.ui;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.lufi.infrastructure.P2pDiagnostics;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Servidor de midia estritamente local para arquivos de torrents ainda incompletos.
 *
 * <p>Ele nunca le alem do prefixo indicado como verificado pelo fornecedor de
 * disponibilidade. Dessa forma, arquivos prealocados pelo armazenamento do
 * torrent nao expõem lacunas como se fossem dados de video.</p>
 */
final class LuffyLocalMediaServer implements AutoCloseable {
    private static final String MEDIA_PREFIX = "/media/";
    /**
     * Limite de memória por resposta HTTP: um bloco é lido e escrito antes do
     * seguinte. OutputStream.write aplica backpressure quando o libVLC deixa de
     * consumir, portanto vídeos grandes nunca são carregados integralmente na RAM.
     */
    private static final int COPY_BUFFER_BYTES = 64 * 1024;
    private static final Duration DEFAULT_AVAILABILITY_POLL_INTERVAL = Duration.ofMillis(50);

    private final P2pDiagnostics diagnostics;
    private final Duration availabilityPollInterval;
    private final Map<String, Registration> registrations = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private HttpServer server;

    LuffyLocalMediaServer(P2pDiagnostics diagnostics) {
        this(diagnostics, DEFAULT_AVAILABILITY_POLL_INTERVAL);
    }

    LuffyLocalMediaServer(P2pDiagnostics diagnostics, Duration availabilityPollInterval) {
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.availabilityPollInterval = Objects.requireNonNull(availabilityPollInterval, "availabilityPollInterval");
        if (availabilityPollInterval.isNegative() || availabilityPollInterval.isZero()) {
            throw new IllegalArgumentException("availabilityPollInterval deve ser positivo");
        }
    }

    synchronized TorrentStreamingMediaSource register(Path file, Supplier<VerifiedMediaWindow> availability) {
        return register(file, availability, (start, end) -> { },
                (start, end) -> availability.get().hasVerifiedRange(start, end));
    }

    synchronized TorrentStreamingMediaSource register(Path file, Supplier<VerifiedMediaWindow> availability,
                                                       RangeListener rangeListener) {
        return register(file, availability, rangeListener,
                (start, end) -> availability.get().hasVerifiedRange(start, end));
    }

    synchronized TorrentStreamingMediaSource register(Path file, Supplier<VerifiedMediaWindow> availability,
                                                       RangeListener rangeListener,
                                                       RangeAvailability rangeAvailability) {
        return register(file, availability, rangeListener, rangeAvailability, (buffering, start, end) -> { });
    }

    synchronized TorrentStreamingMediaSource register(Path file, Supplier<VerifiedMediaWindow> availability,
                                                       RangeListener rangeListener,
                                                       RangeAvailability rangeAvailability,
                                                       BufferingListener bufferingListener) {
        return register(file, availability, rangeListener, rangeAvailability, bufferingListener,
                (start, end) -> RangeProgress.unavailable());
    }

    synchronized TorrentStreamingMediaSource register(Path file, Supplier<VerifiedMediaWindow> availability,
                                                       RangeListener rangeListener,
                                                       RangeAvailability rangeAvailability,
                                                       BufferingListener bufferingListener,
                                                       RangeMetrics rangeMetrics) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(availability, "availability");
        Objects.requireNonNull(rangeListener, "rangeListener");
        Objects.requireNonNull(rangeAvailability, "rangeAvailability");
        Objects.requireNonNull(bufferingListener, "bufferingListener");
        Objects.requireNonNull(rangeMetrics, "rangeMetrics");
        startIfNeeded();
        String sessionId = newOpaqueId();
        String fileId = newOpaqueId();
        registrations.put(sessionId + "/" + fileId, new Registration(file.toAbsolutePath().normalize(), availability, rangeListener,
                rangeAvailability, bufferingListener, rangeMetrics, new BufferingTracker()));
        URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + MEDIA_PREFIX + sessionId + "/" + fileId);
        diagnostics.log(P2pDiagnostics.Layer.DOWNLOAD, "LOCAL MEDIA SERVER REGISTERED: route=TORRENT_STREAMING; "
                + "address=127.0.0.1:" + server.getAddress().getPort() + "; session=redacted; fileId=redacted; file="
                + file.toAbsolutePath().normalize() + ".");
        return new TorrentStreamingMediaSource(uri);
    }

    synchronized void clearRegistrations() {
        registrations.clear();
    }

    synchronized InetSocketAddress boundAddress() {
        return server == null ? null : server.getAddress();
    }

    private void startIfNeeded() {
        if (server != null) return;
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
            server.createContext(MEDIA_PREFIX, this::handle);
            server.setExecutor(executor);
            server.start();
            diagnostics.log(P2pDiagnostics.Layer.DOWNLOAD, "LOCAL MEDIA SERVER STARTED: address=127.0.0.1:"
                    + server.getAddress().getPort() + "; transport=HTTP; scope=loopback-only.");
        } catch (IOException error) {
            throw new IllegalStateException("Nao foi possivel iniciar o servidor local de midia.", error);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String method = exchange.getRequestMethod();
            if (!method.equals("GET") && !method.equals("HEAD")) {
                exchange.getResponseHeaders().set("Allow", "GET, HEAD");
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            Registration registration = registrationFor(exchange.getRequestURI());
            if (registration == null || !Files.isRegularFile(registration.file())) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            VerifiedMediaWindow initial = registration.availability().get();
            long contentLength = initial.contentLengthBytes();
            if (contentLength <= 0) {
                exchange.getResponseHeaders().set("Retry-After", "1");
                exchange.sendResponseHeaders(503, -1);
                return;
            }
            Headers headers = exchange.getResponseHeaders();
            headers.set("Accept-Ranges", "bytes");
            headers.set("Content-Type", mediaContentType(registration.file()));
            Range range = parseRange(exchange.getRequestHeaders().getFirst("Range"), contentLength);
            if (range == Range.INVALID) {
                headers.set("Content-Range", "bytes */" + contentLength);
                exchange.sendResponseHeaders(416, -1);
                return;
            }
            if (range != null) registration.rangeListener().onRangeRequested(range.start(), range.end());
            if (method.equals("HEAD")) {
                if (range != null) {
                    logRangeTelemetry(registration, range, range, 0L);
                    headers.set("Content-Range", "bytes " + range.start() + "-" + range.end() + "/" + contentLength);
                    headers.set("Content-Length", Long.toString(range.length()));
                    exchange.sendResponseHeaders(206, -1);
                } else {
                    headers.set("Content-Length", Long.toString(contentLength));
                    exchange.sendResponseHeaders(200, -1);
                }
                return;
            }
            if (range != null) {
                long waitStartedNanos = System.nanoTime();
                AvailableRange available = awaitAvailableRange(registration, range);
                if (available == null) {
                    logRangeTelemetry(registration, range, range, elapsedMillis(waitStartedNanos));
                    headers.set("Retry-After", "1");
                    exchange.sendResponseHeaders(503, -1);
                    return;
                }
                Range served = available.range();
                logRangeTelemetry(registration, range, served, elapsedMillis(waitStartedNanos));
                headers.set("Content-Range", "bytes " + served.start() + "-" + served.end() + "/" + contentLength);
                exchange.sendResponseHeaders(206, served.length());
                writeVerifiedRange(exchange.getResponseBody(), registration.file(), served.start(), served.end());
                return;
            }
            // A resposta sem Range permanece aberta e avanca apenas quando novas
            // pieces contiguas forem verificadas. Nenhum byte de lacuna e enviado.
            exchange.sendResponseHeaders(200, contentLength);
            writeVerifiedBytes(exchange.getResponseBody(), registration, 0, contentLength - 1);
        } catch (IOException ignored) {
            // O libVLC pode cancelar uma request ao trocar de faixa ou fechar o player.
        }
    }

    private Registration registrationFor(URI requestUri) {
        String path = requestUri.getPath();
        if (path == null || !path.startsWith(MEDIA_PREFIX)) return null;
        String[] segments = path.substring(MEDIA_PREFIX.length()).split("/", -1);
        if (segments.length != 2 || !isOpaqueId(segments[0]) || !isOpaqueId(segments[1])) return null;
        return registrations.get(segments[0] + "/" + segments[1]);
    }

    private static String newOpaqueId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static boolean isOpaqueId(String value) {
        return value != null && value.matches("[0-9a-f]{32}");
    }

    private boolean awaitVerified(Registration registration, long requiredExclusive) {
        boolean buffering = false;
        try {
            while (true) {
                VerifiedMediaWindow window = registration.availability().get();
                if (window.verifiedPrefixBytes() >= requiredExclusive) return true;
                if (!buffering) {
                    buffering = true;
                    signalBuffering(registration, true, requiredExclusive - 1, requiredExclusive - 1);
                }
                if (!window.sessionActive()) return false;
                if (!waitForAvailability()) return false;
            }
        } finally {
            if (buffering) signalBuffering(registration, false, requiredExclusive - 1, requiredExclusive - 1);
        }
    }

    /**
     * Selects a response range that is safe at the instant its HTTP headers are
     * sent. A libVLC probe commonly asks from byte zero to EOF. Waiting for the
     * complete probe would turn an ordinary streaming request into a full
     * download. When the request is already inside the verified prefix, return
     * that prefix only; libVLC can request the next range as playback advances.
     *
     * <p>A seek outside the prefix still waits until the requested owning pieces
     * are verified, so no unverified gap can ever reach the player.</p>
     */
    private AvailableRange awaitAvailableRange(Registration registration, Range requested) {
        boolean buffering = false;
        try {
            while (true) {
                VerifiedMediaWindow window = registration.availability().get();
                if (window.hasVerifiedRange(requested.start(), requested.start())) {
                    long safeEnd = Math.min(requested.end(), window.verifiedPrefixBytes() - 1);
                    return new AvailableRange(new Range(requested.start(), safeEnd));
                }
                if (registration.rangeAvailability().isVerified(requested.start(), requested.end())) {
                    return new AvailableRange(requested);
                }
                if (!buffering) {
                    buffering = true;
                    signalBuffering(registration, true, requested.start(), requested.end());
                }
                if (!window.sessionActive()) return null;
                if (!waitForAvailability()) return null;
            }
        } finally {
            if (buffering) signalBuffering(registration, false, requested.start(), requested.end());
        }
    }

    /** A missing piece is normal while a torrent stream is active: keep this HTTP request open. */
    private boolean waitForAvailability() {
        try {
            Thread.sleep(availabilityPollInterval);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void signalBuffering(Registration registration, boolean buffering, long startByte, long endByte) {
        boolean stateChanged = buffering ? registration.bufferingTracker().begin() : registration.bufferingTracker().end();
        if (!stateChanged) return;
        registration.bufferingListener().onBuffering(buffering, startByte, endByte);
        diagnostics.log(P2pDiagnostics.Layer.DOWNLOAD, "STREAM HTTP BUFFERING " + (buffering ? "START" : "END")
                + ": range=" + startByte + "-" + endByte + "; waitPolicy=session-active.");
    }

    private void logRangeTelemetry(Registration registration, Range requested, Range served, long waitMs) {
        RangeProgress progress = registration.rangeMetrics().progress(requested.start(), requested.end());
        String delivery = served.length() < requested.length() ? "PROGRESSIVE_PREFIX" : "FULL_RANGE";
        diagnostics.log(P2pDiagnostics.Layer.DOWNLOAD, "[STREAM-HTTP] rangeStart=" + requested.start()
                + "; rangeEnd=" + requested.end()
                + "; bytesRequested=" + requested.length()
                + "; piecesRequired=" + progress.piecesRequired()
                + "; piecesReady=" + progress.piecesReady()
                + "; responseStart=" + served.start()
                + "; responseEnd=" + served.end()
                + "; bytesServed=" + served.length()
                + "; delivery=" + delivery
                + "; waitMs=" + Math.max(0L, waitMs) + ".");
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private void writeVerifiedBytes(OutputStream output, Registration registration, long start, long end) throws IOException {
        long position = start;
        try (FileChannel channel = FileChannel.open(registration.file(), StandardOpenOption.READ)) {
            byte[] buffer = new byte[COPY_BUFFER_BYTES];
            while (position <= end) {
                if (!awaitVerified(registration, position + 1)) return;
                long safeExclusive = registration.availability().get().verifiedPrefixBytes();
                int count = (int) Math.min(Math.min(buffer.length, end - position + 1), safeExclusive - position);
                if (count <= 0) continue;
                channel.position(position);
                int read = channel.read(ByteBuffer.wrap(buffer, 0, count));
                if (read <= 0) return;
                output.write(buffer, 0, read);
                position += read;
            }
        }
    }

    /** The caller has already proven the complete requested range from verified torrent pieces. */
    private static void writeVerifiedRange(OutputStream output, Path file, long start, long end) throws IOException {
        long position = start;
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            byte[] buffer = new byte[COPY_BUFFER_BYTES];
            while (position <= end) {
                int count = (int) Math.min(buffer.length, end - position + 1);
                channel.position(position);
                int read = channel.read(ByteBuffer.wrap(buffer, 0, count));
                if (read <= 0) return;
                output.write(buffer, 0, read);
                position += read;
            }
        }
    }

    private static Range parseRange(String header, long contentLength) {
        if (header == null || header.isBlank()) return null;
        if (!header.startsWith("bytes=") || header.indexOf(',') >= 0) return Range.INVALID;
        String value = header.substring("bytes=".length()).trim();
        int separator = value.indexOf('-');
        if (separator < 0) return Range.INVALID;
        try {
            String startText = value.substring(0, separator).trim();
            String endText = value.substring(separator + 1).trim();
            if (startText.isEmpty()) {
                long suffix = Long.parseLong(endText);
                if (suffix <= 0) return Range.INVALID;
                long start = Math.max(0, contentLength - suffix);
                return new Range(start, contentLength - 1);
            }
            long start = Long.parseLong(startText);
            long end = endText.isEmpty() ? contentLength - 1 : Long.parseLong(endText);
            if (start < 0 || start >= contentLength || end < start) return Range.INVALID;
            return new Range(start, Math.min(end, contentLength - 1));
        } catch (NumberFormatException invalid) {
            return Range.INVALID;
        }
    }

    private static String mediaContentType(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        // Hint simples para HTTP; o libVLC continua fazendo a detecção real pelo container.
        if (name.endsWith(".mp4") || name.endsWith(".m4v")) return "video/mp4";
        if (name.endsWith(".mkv")) return "video/x-matroska";
        if (name.endsWith(".webm")) return "video/webm";
        if (name.endsWith(".avi")) return "video/x-msvideo";
        return "application/octet-stream";
    }

    @Override
    public synchronized void close() {
        registrations.clear();
        if (server != null) {
            server.stop(0);
            server = null;
        }
        executor.close();
    }

    record VerifiedMediaWindow(long contentLengthBytes, long verifiedPrefixBytes, boolean sessionActive) {
        VerifiedMediaWindow {
            contentLengthBytes = Math.max(0, contentLengthBytes);
            verifiedPrefixBytes = Math.max(0, Math.min(contentLengthBytes, verifiedPrefixBytes));
        }

        boolean hasVerifiedRange(long startByte, long endByte) {
            return startByte >= 0 && endByte >= startByte && endByte < verifiedPrefixBytes;
        }
    }

    @FunctionalInterface
    interface RangeListener {
        void onRangeRequested(long startByte, long endByte);
    }

    @FunctionalInterface
    interface RangeAvailability {
        boolean isVerified(long startByte, long endByte);
    }

    @FunctionalInterface
    interface BufferingListener {
        void onBuffering(boolean buffering, long startByte, long endByte);
    }

    @FunctionalInterface
    interface RangeMetrics {
        RangeProgress progress(long startByte, long endByte);
    }

    private record Registration(Path file, Supplier<VerifiedMediaWindow> availability, RangeListener rangeListener,
                                RangeAvailability rangeAvailability, BufferingListener bufferingListener, RangeMetrics rangeMetrics,
                                BufferingTracker bufferingTracker) { }

    private record AvailableRange(Range range) { }

    record RangeProgress(int piecesRequired, int piecesReady) {
        RangeProgress {
            piecesRequired = Math.max(0, piecesRequired);
            piecesReady = Math.max(0, Math.min(piecesRequired, piecesReady));
        }

        static RangeProgress unavailable() {
            return new RangeProgress(0, 0);
        }
    }

    private static final class BufferingTracker {
        private int waiters;

        synchronized boolean begin() {
            waiters++;
            return waiters == 1;
        }

        synchronized boolean end() {
            if (waiters == 0) return false;
            waiters--;
            return waiters == 0;
        }
    }
    private record Range(long start, long end) {
        static final Range INVALID = new Range(-1, -1);
        long length() { return end - start + 1; }
    }
}
