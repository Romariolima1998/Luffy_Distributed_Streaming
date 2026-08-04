package dev.lufi.infrastructure;

import bt.metainfo.TorrentId;
import bt.net.Peer;
import java.nio.channels.SocketChannel;
import java.util.concurrent.CompletionStage;

/**
 * Fronteira tipada entre uma sessao uTP ja estabelecida e a factory interna do
 * bt-core. A ponte continua dona das bombas de bytes; o bt-core recebe somente
 * o {@link SocketChannel} local que representa essa sessao.
 */
public interface EstablishedPeerConnectionPromoter {
    CompletionStage<PromotionResult> promoteOutgoing(
            TorrentId torrentId,
            Peer remotePeer,
            UtpTransportService.UtpSession session,
            SocketChannel btCoreChannel
    );

    CompletionStage<PromotionResult> promoteIncoming(
            Peer remotePeer,
            UtpTransportService.UtpSession session,
            SocketChannel btCoreChannel
    );
}
