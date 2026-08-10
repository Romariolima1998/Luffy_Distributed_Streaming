package dev.lufi.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bt.Bt;
import bt.data.file.FileSystemStorage;
import bt.metainfo.TorrentId;
import bt.net.Peer;
import bt.runtime.BtClient;
import bt.runtime.BtRuntime;
import bt.runtime.Config;
import dev.lufi.domain.MagnetLink;
import dev.lufi.infrastructure.P2pDiagnostics;
import dev.lufi.infrastructure.PeerConnectivityManager;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Sondagem real de magnet publico: valida tracker e descoberta sem solicitar
 * pieces. Todos os arquivos recebem prioridade SKIP antes de o cliente iniciar.
 */
@Tag("real-network")
class RealNetworkPublicMagnetDiscoveryIT {
    private static final Duration TIMEOUT = Duration.ofSeconds(45);

    @TempDir Path temporaryDirectory;

    @Test void discoversACompactPeerFromTheConfiguredPublicUdpTrackersWithoutDownloadingContent() throws Exception {
        String rawMagnet = System.getenv("LUFFY_PUBLIC_MAGNET");
        Assumptions.assumeTrue(rawMagnet != null && rawMagnet.startsWith("magnet:?"),
                "LUFFY_PUBLIC_MAGNET nao foi definido para a sondagem publica.");
        MagnetLink magnet = MagnetLink.parse(rawMagnet);

        assertEquals(40, magnet.infoHash().length());
        assertTrue(!magnet.trackers().isEmpty(), "O magnet publico precisa possuir ao menos um tracker.");
        assertTrue(magnet.trackers().stream().allMatch(tracker -> tracker.startsWith("udp://")),
                "Esta sondagem controla somente trackers UDP.");
        assertEquals(magnet.trackers(), MagnetLink.parse(magnet.toUri()).trackers(),
                "A reconstrucao do magnet nao pode perder trackers repetidos.");

        Config config = new Config();
        config.setAcceptorPort(0);
        config.setPeerConnectionTimeout(Duration.ofSeconds(5));
        config.setPeerConnectionRetryCount(0);
        CompletableFuture<Peer> peerFromTracker = new CompletableFuture<>();
        P2pDiagnostics diagnostics = new P2pDiagnostics();
        try (PeerConnectivityManager connectivity = new PeerConnectivityManager(diagnostics, ignored -> { })) {
            BtRuntime runtime = null;
            BtClient client = null;
            try {
                runtime = BtRuntime.builder(config).disableAutomaticShutdown().autoLoadModules().build();
                TorrentId torrentId = TorrentId.fromBytes(HexFormat.of().parseHex(magnet.infoHash()));
                runtime.getEventSource().onPeerDiscovered(torrentId, event -> {
                    Peer peer = event.getPeer();
                    connectivity.onTrackerPeerDiscovered(magnet.infoHash(), peer);
                    peerFromTracker.complete(peer);
                });
                client = Bt.client(runtime).storage(new FileSystemStorage(temporaryDirectory.resolve("storage")))
                        .magnet(magnet.toUri())
                        .fileSelector(file -> bt.torrent.fileselector.FilePriority.SKIP)
                        .build();
                client.startAsync(ignored -> { }, 25);

                Peer peer = peerFromTracker.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                assertTrue(!peer.isPortUnknown(), "A resposta compacta do tracker deve trazer IP e porta.");
                assertTrue(connectivity.peersFor(magnet.infoHash()).stream().anyMatch(state ->
                        state.endpoint().address().equals(peer.getInetAddress())
                                && state.endpoint().port() == peer.getPort()
                                && state.origins().contains(PeerConnectivityManager.DiscoveryOrigin.TRACKER)));
            } finally {
                if (client != null) client.stop();
                if (runtime != null) runtime.shutdown();
            }
        }
    }
}
