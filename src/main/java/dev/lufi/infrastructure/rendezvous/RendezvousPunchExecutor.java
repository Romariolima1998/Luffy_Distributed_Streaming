package dev.lufi.infrastructure.rendezvous;

import bt.metainfo.TorrentId;
import java.util.concurrent.CompletionStage;

/** Dispara o caminho BEP55/uTP existente; sucesso exige aceite do bt-core. */
@FunctionalInterface
public interface RendezvousPunchExecutor {
    CompletionStage<Void> start(TorrentId contentTorrentId, LuffyRendezvousMessage.RendezvousEndpoint remoteEndpoint);
}
