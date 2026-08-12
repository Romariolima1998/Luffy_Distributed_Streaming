package dev.lufi.ui;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/**
 * Origem de midia consumida por um {@link MediaPlayerBackend}.
 *
 * <p>O player conhece somente a URI da origem. A procedencia local ou de
 * streaming e mantida aqui para que a UI e o motor P2P continuem separados.</p>
 */
interface MediaSource {
    URI uri();

    String kind();
}

/** Arquivo completo ja disponivel no sistema local. */
record LocalFileMediaSource(Path path) implements MediaSource {
    LocalFileMediaSource {
        Objects.requireNonNull(path, "path");
        path = path.toAbsolutePath().normalize();
    }

    @Override
    public URI uri() {
        return path.toUri();
    }

    @Override
    public String kind() {
        return "LOCAL_FILE";
    }

    boolean isReadableFile() {
        return Files.isRegularFile(path) && Files.isReadable(path);
    }
}

/**
 * URI HTTP local entregue futuramente pelo streaming de pieces verificadas.
 * Esta classe nao inicia servidor HTTP, nao solicita peers e nao muda a
 * prioridade de download: ela apenas descreve a origem para o player.
 */
record TorrentStreamingMediaSource(URI uri) implements MediaSource {
    TorrentStreamingMediaSource {
        Objects.requireNonNull(uri, "uri");
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("A origem de streaming torrent precisa usar HTTP local.");
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!host.equals("127.0.0.1") && !host.equals("localhost") && !host.equals("::1")) {
            throw new IllegalArgumentException("A origem de streaming torrent precisa apontar para o localhost.");
        }
        if (uri.getPath() == null || uri.getPath().isBlank()) {
            throw new IllegalArgumentException("A origem de streaming torrent precisa identificar a midia.");
        }
    }

    @Override
    public String kind() {
        return "TORRENT_STREAMING";
    }
}
