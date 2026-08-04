package dev.lufi.infrastructure;

import bt.Bt;
import bt.data.file.FileSystemStorage;
import bt.net.ConnectionResult;
import bt.net.IConnectionSource;
import bt.metainfo.TorrentId;
import bt.net.InetPeer;
import bt.runtime.BtClient;
import bt.runtime.BtRuntime;
import bt.runtime.Config;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Prova da topologia A-C-B: C possui conexões BitTorrent reais com ambos e
 * encaminha apenas RENDEZVOUS/CONNECT. A transferência final é A<->B/uTP.
 */
class Bep55HolePunchIntegrationTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @TempDir Path temporaryDirectory;

    @Test void downloadsTesteTxtAfterBep55HolePunchThroughConnectedRelay() throws Exception {
        Path seedDirectory = Files.createDirectories(temporaryDirectory.resolve("seed-b"));
        Path assistDirectory = Files.createDirectories(temporaryDirectory.resolve("assist-c"));
        Path downloadDirectory = Files.createDirectories(temporaryDirectory.resolve("download-a"));
        Path source = seedDirectory.resolve("teste.txt");
        Files.writeString(source, "OLA LUFFY", StandardCharsets.UTF_8);
        TorrentMetainfoGenerator.PublishedTorrent published = new TorrentMetainfoGenerator()
                .publish(source, temporaryDirectory.resolve("metainfo"));
        String infoHash = published.magnet().infoHash();
        TorrentId torrentId = TorrentId.fromBytes(HexFormat.of().parseHex(infoHash));
        CompletableFuture<Void> completed = new CompletableFuture<>();

        try (Node a = Node.open("A"); Node b = Node.open("B"); Node c = Node.open("C")) {
            BtClient seederB = null;
            BtClient assistC = null;
            BtClient downloaderA = null;
            try {
                seederB = Bt.client(b.runtime).storage(new FileSystemStorage(seedDirectory))
                        .torrent(published.torrentFile().toUri().toURL()).build();
                // C mantém uma sessão BitTorrent real no swarm. O teste verifica que
                // ele apenas coordena CONNECT; a ponte A-B continua sendo criada por uTP.
                assistC = Bt.client(c.runtime).storage(new FileSystemStorage(assistDirectory))
                        .torrent(published.torrentFile().toUri().toURL()).build();
                downloaderA = Bt.client(a.runtime).storage(new FileSystemStorage(downloadDirectory))
                        .torrent(published.torrentFile().toUri().toURL())
                        .afterDownloaded(ignored -> completed.complete(null)).build();
                BtClient activeSeederB = seederB;
                BtClient activeAssistC = assistC;
                BtClient activeDownloaderA = downloaderA;
                activeSeederB.startAsync(ignored -> { }, 25).whenComplete((ignored, error) -> b.diagnostics.log("TEST CLIENT B FINISHED: " + error));
                activeAssistC.startAsync(ignored -> { }, 25).whenComplete((ignored, error) -> c.diagnostics.log("TEST CLIENT C FINISHED: " + error));
                await(() -> activeSeederB.isStarted() && activeAssistC.isStarted(),
                        Duration.ofSeconds(5), "B e C iniciarem as sessões BitTorrent", diagnostics(a, b, c));
                await(() -> b.hasTorrent(torrentId) && c.hasTorrent(torrentId),
                        Duration.ofSeconds(5), "B e C registrarem o TorrentId antes da conexão TCP", diagnostics(a, b, c));

                // C primeiro estabelece uma conexão BitTorrent real com B.
                c.connectTcpTo(torrentId, b).get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                await(() -> c.agent.usefulRendezvousPeerCount(infoHash) == 1 && b.agent.usefulRendezvousPeerCount(infoHash) == 1,
                        TIMEOUT, "B e C negociarem BEP 10/uTP/ut_holepunch", diagnostics(a, b, c));

                // A entra quando C já está no swarm: A ainda terá uma conexão TCP apenas com C,
                // nunca diretamente com B.
                activeDownloaderA.startAsync(ignored -> { }, 25).whenComplete((ignored, error) -> a.diagnostics.log("TEST CLIENT A FINISHED: " + error));
                await(() -> activeDownloaderA.isStarted() && a.hasTorrent(torrentId),
                        Duration.ofSeconds(5), "A registrar o TorrentId antes da conexão TCP com C", diagnostics(a, b, c));
                a.connectTcpTo(torrentId, c).get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                await(() -> c.agent.usefulRendezvousPeerCount(infoHash) == 2,
                        TIMEOUT, "C negociar BEP 10/uTP/ut_holepunch com A e B", diagnostics(a, b, c));
                await(() -> a.agent.usefulRendezvousPeerCount(infoHash) == 1,
                        TIMEOUT, "A reconhecer C como peer BEP55", diagnostics(a, b, c));

                // Estes registros representam endpoints UDP descobertos de forma independente; nenhuma porta TCP é reutilizada.
                c.associateUtp(infoHash, a);
                c.associateUtp(infoHash, b);

                assertEquals(1, a.agent.usefulRendezvousPeerCount(infoHash),
                        "A deve estar conectado apenas ao relay C, não diretamente a B");
                a.agent.requestRendezvous(infoHash, b.address, b.utp.localPort());

                await(() -> c.diagnostics.snapshot().contains("CONNECT SENT: infoHash=" + infoHash),
                        TIMEOUT, "C enviar CONNECT para A e B", diagnostics(a, b, c));
                await(() -> a.diagnostics.snapshot().contains("CONNECT RECEIVED: infoHash=" + infoHash)
                                && b.diagnostics.snapshot().contains("CONNECT RECEIVED: infoHash=" + infoHash),
                        TIMEOUT, "A e B iniciarem uTP simultaneamente", diagnostics(a, b, c));
                await(() -> a.diagnostics.snapshot().contains("uTP BITTORRENT HANDSHAKE START: infoHash=" + infoHash),
                        TIMEOUT, "A entregar a sessão uTP ao bt-core", diagnostics(a, b, c));
                await(completed::isDone, TIMEOUT, "A baixar teste.txt diretamente de B", diagnostics(a, b, c));
                completed.get(1, TimeUnit.SECONDS);
                assertEquals("OLA LUFFY", Files.readString(downloadDirectory.resolve("teste.txt"), StandardCharsets.UTF_8));
                assertTrue(a.diagnostics.snapshot().contains("BITTORRENT HANDSHAKE SUCCESS"),
                        "o handshake BitTorrent sobre uTP precisa terminar antes das pieces");
            } finally {
                if (downloaderA != null) downloaderA.stop();
                if (assistC != null) assistC.stop();
                if (seederB != null) seederB.stop();
            }
        }
    }

    private static Supplier<String> diagnostics(Node a, Node b, Node c) {
        return () -> "\nStarted: A=" + a.runtime.service(bt.torrent.TorrentRegistry.class).getTorrentIds().size()
                + ", B=" + b.runtime.service(bt.torrent.TorrentRegistry.class).getTorrentIds().size()
                + ", C=" + c.runtime.service(bt.torrent.TorrentRegistry.class).getTorrentIds().size()
                + "\n--- A ---\n" + a.diagnostics.snapshot() + "\n--- C ---\n" + c.diagnostics.snapshot()
                + "\n--- B ---\n" + b.diagnostics.snapshot();
    }

    private static void await(BooleanSupplier condition, Duration timeout, String description, Supplier<String> context) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(25);
        }
        fail("Tempo limite aguardando " + description + context.get());
    }

    private static final class Node implements AutoCloseable {
        private final InetAddress address;
        private final P2pDiagnostics diagnostics = new P2pDiagnostics();
        private final PeerConnectivityManager connectivity = new PeerConnectivityManager(diagnostics, ignored -> { });
        private final UtpBitTorrentBridge bridge = new UtpBitTorrentBridge(diagnostics, connectivity);
        private final Bep55HolePunchAgent agent = new Bep55HolePunchAgent(diagnostics, bridge);
        private final BtRuntime runtime;
        private final UtpTransportService utp;
        private final int tcpPort;

        private Node(String ignoredNodeName) throws Exception {
            this.address = InetAddress.getLoopbackAddress();
            Config config = new Config();
            config.setAcceptorAddress(this.address);
            tcpPort = freeTcpPort(this.address);
            config.setAcceptorPort(tcpPort);
            config.setPeerHandshakeTimeout(Duration.ofSeconds(5));
            config.setPeerConnectionTimeout(Duration.ofSeconds(5));
            config.setPeerConnectionRetryCount(0);
            runtime = BtRuntime.builder(config).disableAutomaticShutdown().module(new Bep55HolePunchModule(agent)).build();
            assertTrue(BtConnectionLifecycleInstrumentation.install(runtime, connectivity, diagnostics));
            utp = new UtpTransportService(this.address, 0, diagnostics);
            bridge.attach(runtime, utp);
        }

        static Node open(String nodeName) throws Exception { return new Node(nodeName); }
        int tcpPort() { return tcpPort; }
        boolean hasTorrent(TorrentId torrentId) { return runtime.service(bt.torrent.TorrentRegistry.class).getTorrent(torrentId).isPresent(); }
        CompletableFuture<Void> connectTcpTo(TorrentId torrentId, Node peer) {
            return runtime.service(IConnectionSource.class)
                    .getConnectionAsync(InetPeer.build(peer.address, peer.tcpPort()), torrentId)
                    .thenAccept(result -> {
                        if (!result.isSuccess()) {
                            throw new IllegalStateException(result.getMessage().orElse("bt-core recusou conexão TCP"),
                                    result.getError().orElse(null));
                        }
                    });
        }
        void associateUtp(String infoHash, Node peer) {
            agent.associatePeerUtpEndpoint(infoHash, peer.address, peer.tcpPort(),
                    new PeerConnectivityManager.PeerEndpoint(peer.address, peer.utp.localPort(), PeerConnectivityManager.Transport.UTP));
        }

        private static int freeTcpPort(InetAddress address) throws Exception {
            try (ServerSocket socket = new ServerSocket()) {
                socket.bind(new InetSocketAddress(address, 0));
                return socket.getLocalPort();
            }
        }

        @Override public void close() throws Exception {
            bridge.close();
            await(() -> bridge.activeLoopbackPairCount() == 0 && bridge.activePumpTaskCount() == 0
                            && bridge.pendingConnectionCount() == 0,
                    Duration.ofSeconds(5), "os recursos uTP da ponte encerrarem", () -> diagnostics.snapshot());
            connectivity.close();
            runtime.shutdown();
        }
    }
}
