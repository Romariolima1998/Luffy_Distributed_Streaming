package dev.lufi.infrastructure;

import bt.metainfo.TorrentId;
import bt.net.Peer;
import java.util.Collection;

/** Recebe peers BEP 11 sem substituir o PeerExchangePeerSource do motor Bt. */
@FunctionalInterface
public interface PexPeerObserver {
    void onPeerExchange(TorrentId torrentId, Peer viaPeer, Collection<Peer> added, Collection<Peer> dropped);
}
