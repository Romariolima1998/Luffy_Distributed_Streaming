package dev.lufi.application.port;

import dev.lufi.domain.MagnetLink;
import dev.lufi.domain.StreamingSession;
import dev.lufi.domain.WatchMode;
import java.util.function.Consumer;

/** Porta para o motor P2P. Implementações devem verificar cada peça antes de expô-la ao player. */
public interface TorrentGateway {
    StreamingSession open(MagnetLink magnet, WatchMode mode);
    default StreamingSession open(MagnetLink magnet, WatchMode mode, Consumer<TorrentContent> onMetadata) { return open(magnet, mode); }
    default StreamingSession open(MagnetLink magnet, WatchMode mode, String selectedRelativePath, Consumer<TorrentContent> onMetadata) { return open(magnet, mode, onMetadata); }
}
