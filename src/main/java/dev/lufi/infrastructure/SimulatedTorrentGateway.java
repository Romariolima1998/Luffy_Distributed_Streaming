package dev.lufi.infrastructure;

import dev.lufi.application.port.TorrentGateway;
import dev.lufi.domain.MagnetLink;
import dev.lufi.domain.StreamingSession;
import dev.lufi.domain.WatchMode;
import java.time.Instant;

/** Adaptador explícito de desenvolvimento. Nunca anuncia seed ou transferência inexistente. */
public final class SimulatedTorrentGateway implements TorrentGateway {
    @Override public StreamingSession open(MagnetLink magnet, WatchMode mode) {
        return new StreamingSession(magnet.infoHash(), magnet.displayName().orElse("Vídeo sem título"), mode,
                StreamingSession.SessionStatus.DISCOVERING_PEERS, 0, 12, Instant.now());
    }
}

