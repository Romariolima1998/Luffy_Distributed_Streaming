package bt.peerexchange;

import bt.torrent.annotation.Consumes;
import bt.torrent.messaging.MessageContext;
import dev.lufi.infrastructure.PexPeerObserver;
import java.util.Objects;

/** Observador BEP 11 no mesmo pacote para consumir a mensagem interna PeerExchange do bt-core. */
public final class LuffyPexObserver {
    private final PexPeerObserver observer;

    public LuffyPexObserver(PexPeerObserver observer) { this.observer = Objects.requireNonNull(observer, "observer"); }

    @Consumes public void consume(PeerExchange message, MessageContext context) {
        observer.onPeerExchange(context.getTorrentId(), context.getConnectionKey().getPeer(), message.getAdded(), message.getDropped());
    }
}
