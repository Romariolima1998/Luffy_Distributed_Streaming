package dev.lufi.application.port;

import dev.lufi.domain.MagnetLink;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Entrada normalizada para abrir um torrent. Um arquivo {@code .torrent}
 * conserva sua metadata local; um magnet dependerá da troca de metadata normal.
 */
public record TorrentOpenRequest(MagnetLink magnet, Optional<Path> torrentFile, Optional<TorrentMetadata> metadata) {
    public TorrentOpenRequest {
        magnet = Objects.requireNonNull(magnet, "magnet");
        torrentFile = torrentFile == null ? Optional.empty() : torrentFile.map(path -> path.toAbsolutePath().normalize());
        metadata = metadata == null ? Optional.empty() : metadata;
    }

    public static TorrentOpenRequest magnet(MagnetLink magnet) {
        return new TorrentOpenRequest(magnet, Optional.empty(), Optional.empty());
    }

    public static TorrentOpenRequest torrentFile(MagnetLink magnet, Path torrentFile) {
        return new TorrentOpenRequest(magnet, Optional.of(Objects.requireNonNull(torrentFile, "torrentFile")), Optional.empty());
    }

    public static TorrentOpenRequest torrentFile(MagnetLink magnet, Path torrentFile, TorrentMetadata metadata) {
        return new TorrentOpenRequest(magnet, Optional.of(Objects.requireNonNull(torrentFile, "torrentFile")),
                Optional.of(Objects.requireNonNull(metadata, "metadata")));
    }

    public boolean hasEmbeddedMetadata() {
        return torrentFile.isPresent();
    }
}
