package dev.lufi.infrastructure;

import dev.lufi.domain.MagnetLink;

import java.nio.file.Path;
import java.util.Objects;

/** Keeps every shared torrent in a deterministic directory of its own. */
final class SharedTorrentStorageLayout {
    private SharedTorrentStorageLayout() {
    }

    static Path resolve(Path luffyRoot, MagnetLink magnet) {
        Objects.requireNonNull(luffyRoot, "luffyRoot");
        Objects.requireNonNull(magnet, "magnet");
        Path root = luffyRoot.toAbsolutePath().normalize();
        // infoHash is validated by MagnetLink as exactly 40 hexadecimal chars.
        // Using it as the folder name keeps the location stable when the same
        // torrent is opened again, even if its display name changes.
        Path directory = root.resolve(magnet.infoHash()).normalize();
        if (!directory.startsWith(root)) throw new IllegalArgumentException("Diretorio de torrent fora da raiz do Luffy");
        return directory;
    }
}
