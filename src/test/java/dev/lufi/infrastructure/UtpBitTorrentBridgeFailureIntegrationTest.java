package dev.lufi.infrastructure;

import bt.Bt;
import bt.data.file.FileSystemStorage;
import bt.net.IPeerConnectionFactory;
import bt.net.InetPeer;
import bt.net.Peer;
import bt.runtime.BtClient;
import bt.runtime.BtRuntime;
import bt.runtime.Config;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UtpBitTorrentBridgeFailureIntegrationTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @TempDir Path temporaryDirectory;

    @Test void rejectsAnUnknownInfoHashAndCleansBothUtpBridges() throws Exception {
        try (BridgeFixture fixture = BridgeFixture.open()) {
            String unknownInfoHash = "00".repeat(19) + "01";

            assertThrows(java.util.concurrent.ExecutionException.class, () -> fixture.bridgeA
                    .connectDirect(unknownInfoHash, InetAddress.getLoopbackAddress(), fixture.utpB.localPort())
                    .get(TIMEOUT.toSeconds(), TimeUnit.SECONDS));

            await(() -> fixture.diagnosticsA.snapshot().contains("BITTORRENT CONNECT FAILED"),
                    "a factory de saida rejeitar o infoHash inexistente");
            fixture.awaitClean();
            assertFalse(fixture.diagnosticsA.snapshot().contains("uTP BITTORRENT PEER REGISTERED: infoHash=" + unknownInfoHash));
        }
    }

    @Test void invalidBittorrentHandshakeClosesTheIncomingUtpChannelAndPumps() throws Exception {
        try (BridgeFixture fixture = BridgeFixture.open(); UtpSessionFixture session = UtpSessionFixture.open();
             ChannelPair pair = ChannelPair.open()) {
            BtCoreConnectionFactoryAdapter adapter = incomingAdapter(fixture);
            CompletableFuture<PromotionResult> promotion = promoteIncoming(adapter, session.outgoing(), pair);

            write(pair.bridgeSide(), new byte[] { 19, 'B', 'i', 't' });
            pair.bridgeSide().close();

            PromotionResult result = promotion.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            assertFalse(result.isSuccess(), "o bt-core deve rejeitar handshake BitTorrent truncado");
            assertFalse(pair.btSide().isOpen(), "o bt-core deve fechar o canal apos handshake truncado");
        }
    }

    @Test void stoppedTorrentDoesNotLeakTheIncomingHandshakeChannel() throws Exception {
        try (BridgeFixture fixture = BridgeFixture.open(); UtpSessionFixture session = UtpSessionFixture.open();
             ChannelPair pair = ChannelPair.open()) {
            SeededTorrent seeded = seed(fixture, "stopped-torrent");
            try {
                seeded.client().stop();
                await(() -> !seeded.client().isStarted(), "o torrent B encerrar durante o handshake");
                CompletableFuture<PromotionResult> promotion = promoteIncoming(incomingAdapter(fixture), session.outgoing(), pair);
                write(pair.bridgeSide(), bittorrentHandshake(seeded.infoHash()));

                PromotionResult result = promotion.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                // O bt-core pode observar a retirada do torrent antes ou depois de
                // criar o canal preliminar. Nos dois resultados a ponte nao pode
                // manter um SocketChannel aberto apos a sessao ter sido encerrada.
                if (result.isSuccess()) pair.btSide().close();
                assertFalse(pair.btSide().isOpen(), "o canal deve ser fechavel apos o torrent encerrar durante o handshake");
            } finally {
                seeded.client().stop();
                Thread.sleep(200); // O bt-core libera o stream de metainfo de forma assincrona no Windows.
            }
        }
    }

    @Test void closingThePeerSessionReleasesIncomingChannelsAndVirtualPumpTasks() throws Exception {
        try (BridgeFixture fixture = BridgeFixture.open(); UtpTransportService peer = fixture.rawPeer()) {
            UtpTransportService.UtpSession session = peer.connect(fixture.utpEndpointB()).get(1, TimeUnit.SECONDS);
            await(() -> fixture.bridgeB.activeLoopbackPairCount() == 1 && fixture.bridgeB.activePumpTaskCount() == 2,
                    "B abrir o canal local de entrada");

            session.close();

            fixture.awaitClean();
            assertEqualsZero(fixture.bridgeB.activeLoopbackPairCount(), "pares loopback B");
            assertEqualsZero(fixture.bridgeB.activePumpTaskCount(), "tarefas virtuais B");
        }
    }

    private SeededTorrent seed(BridgeFixture fixture, String directory) throws Exception {
        Path storage = Files.createDirectories(temporaryDirectory.resolve(directory));
        Path source = storage.resolve("teste.txt");
        Files.writeString(source, "OLA LUFFY", StandardCharsets.UTF_8);
        TorrentMetainfoGenerator.PublishedTorrent published = new TorrentMetainfoGenerator()
                .publish(source, temporaryDirectory.resolve(directory + "-metainfo"));
        BtClient client = Bt.client(fixture.runtimeB).storage(new FileSystemStorage(storage))
                .torrent(published.torrentFile().toUri().toURL()).build();
        client.startAsync(ignored -> { }, 25);
        await(client::isStarted, "o torrent de teste iniciar");
        return new SeededTorrent(client, published.magnet().infoHash());
    }

    private static byte[] bittorrentHandshake(String infoHash) {
        ByteBuffer bytes = ByteBuffer.allocate(68);
        bytes.put((byte) 19);
        bytes.put("BitTorrent protocol".getBytes(StandardCharsets.ISO_8859_1));
        bytes.put(new byte[8]);
        bytes.put(HexFormat.of().parseHex(infoHash));
        bytes.put("-LU0001-012345678901".getBytes(StandardCharsets.ISO_8859_1));
        return bytes.array();
    }

    private static BtCoreConnectionFactoryAdapter incomingAdapter(BridgeFixture fixture) {
        return new BtCoreConnectionFactoryAdapter(fixture.runtimeB.service(IPeerConnectionFactory.class));
    }

    private static CompletableFuture<PromotionResult> promoteIncoming(BtCoreConnectionFactoryAdapter adapter,
                                                                        UtpTransportService.UtpSession session,
                                                                        ChannelPair pair) throws Exception {
        Peer peer = InetPeer.build(InetAddress.getLoopbackAddress(), 49_001);
        return CompletableFuture.supplyAsync(() -> adapter.promoteIncoming(peer, session, pair.btSide())
                .toCompletableFuture().join());
    }

    private static void write(SocketChannel channel, byte[] bytes) throws Exception {
        ByteBuffer source = ByteBuffer.wrap(bytes);
        while (source.hasRemaining()) channel.write(source);
    }

    private static void assertEqualsZero(int actual, String resource) {
        assertTrue(actual == 0, resource + " devem estar encerrados, mas restaram " + actual);
    }

    private static void await(BooleanSupplier condition, String description) throws InterruptedException {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(20);
        }
        assertTrue(condition.getAsBoolean(), "Tempo limite aguardando " + description);
    }

    private record SeededTorrent(BtClient client, String infoHash) { }

    private record ChannelPair(SocketChannel btSide, SocketChannel bridgeSide) implements AutoCloseable {
        static ChannelPair open() throws Exception {
            try (ServerSocketChannel server = ServerSocketChannel.open()) {
                server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
                SocketChannel bt = SocketChannel.open();
                bt.connect(server.getLocalAddress());
                SocketChannel bridge = server.accept();
                bridge.configureBlocking(true);
                return new ChannelPair(bt, bridge);
            }
        }

        @Override public void close() throws Exception {
            bridgeSide.close();
            btSide.close();
        }
    }

    private record UtpSessionFixture(UtpTransportService left, UtpTransportService right,
                                     UtpTransportService.UtpSession outgoing) implements AutoCloseable {
        static UtpSessionFixture open() throws Exception {
            InetAddress loopback = InetAddress.getLoopbackAddress();
            UtpTransportService left = new UtpTransportService(loopback, 0, new P2pDiagnostics());
            UtpTransportService right = new UtpTransportService(loopback, 0, new P2pDiagnostics());
            try {
                CompletableFuture<UtpTransportService.UtpSession> incoming = new CompletableFuture<>();
                right.setIncomingListener(incoming::complete);
                UtpTransportService.UtpSession outgoing = left.connect(new InetSocketAddress(loopback, right.localPort()))
                        .get(1, TimeUnit.SECONDS);
                incoming.get(1, TimeUnit.SECONDS);
                return new UtpSessionFixture(left, right, outgoing);
            } catch (Exception error) {
                left.close();
                right.close();
                throw error;
            }
        }

        @Override public void close() {
            left.close();
            right.close();
        }
    }

    private static final class BridgeFixture implements AutoCloseable {
        private final P2pDiagnostics diagnosticsA = new P2pDiagnostics();
        private final P2pDiagnostics diagnosticsB = new P2pDiagnostics();
        private final P2pDiagnostics diagnosticsRaw = new P2pDiagnostics();
        private final BtRuntime runtimeA;
        private final BtRuntime runtimeB;
        private final PeerConnectivityManager connectivityA;
        private final PeerConnectivityManager connectivityB;
        private final UtpTransportService utpA;
        private final UtpTransportService utpB;
        private final UtpBitTorrentBridge bridgeA;
        private final UtpBitTorrentBridge bridgeB;

        private BridgeFixture() throws Exception {
            InetAddress loopback = InetAddress.getLoopbackAddress();
            runtimeA = BtRuntime.builder(runtimeConfig(loopback)).disableAutomaticShutdown().build();
            runtimeB = BtRuntime.builder(runtimeConfig(loopback)).disableAutomaticShutdown().build();
            connectivityA = new PeerConnectivityManager(diagnosticsA, ignored -> { });
            connectivityB = new PeerConnectivityManager(diagnosticsB, ignored -> { });
            assertTrue(BtConnectionLifecycleInstrumentation.install(runtimeA, connectivityA, diagnosticsA));
            assertTrue(BtConnectionLifecycleInstrumentation.install(runtimeB, connectivityB, diagnosticsB));
            utpA = new UtpTransportService(loopback, 0, diagnosticsA);
            utpB = new UtpTransportService(loopback, 0, diagnosticsB);
            bridgeA = new UtpBitTorrentBridge(diagnosticsA, connectivityA);
            bridgeB = new UtpBitTorrentBridge(diagnosticsB, connectivityB);
            bridgeA.attach(runtimeA, utpA);
            bridgeB.attach(runtimeB, utpB);
        }

        static BridgeFixture open() throws Exception {
            return new BridgeFixture();
        }

        UtpTransportService rawPeer() throws Exception {
            return new UtpTransportService(InetAddress.getLoopbackAddress(), 0, diagnosticsRaw);
        }

        InetSocketAddress utpEndpointB() {
            return new InetSocketAddress(InetAddress.getLoopbackAddress(), utpB.localPort());
        }

        void awaitClean() throws InterruptedException {
            await(() -> bridgeA.activeLoopbackPairCount() == 0 && bridgeA.activePumpTaskCount() == 0
                            && bridgeA.pendingConnectionCount() == 0 && bridgeB.activeLoopbackPairCount() == 0
                            && bridgeB.activePumpTaskCount() == 0 && bridgeB.pendingConnectionCount() == 0
                            && utpA.activeSessionCount() == 0 && utpB.activeSessionCount() == 0,
                    "as pontes, sessoes e tarefas virtuais encerrarem");
        }

        @Override public void close() throws Exception {
            bridgeA.close();
            bridgeB.close();
            awaitClean();
            connectivityA.close();
            connectivityB.close();
            runtimeA.shutdown();
            runtimeB.shutdown();
        }

        private static Config runtimeConfig(InetAddress address) {
            Config config = new Config();
            config.setAcceptorAddress(address);
            config.setAcceptorPort(0);
            config.setPeerHandshakeTimeout(Duration.ofMillis(750));
            config.setPeerConnectionTimeout(Duration.ofSeconds(2));
            config.setPeerConnectionRetryCount(0);
            return config;
        }
    }
}
