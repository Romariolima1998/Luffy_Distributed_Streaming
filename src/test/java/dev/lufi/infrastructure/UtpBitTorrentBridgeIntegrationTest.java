package dev.lufi.infrastructure;

import bt.Bt;
import bt.data.file.FileSystemStorage;
import bt.metainfo.TorrentId;
import bt.runtime.BtClient;
import bt.runtime.BtRuntime;
import bt.runtime.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Prova de integração do caminho completo: bytes uTP -> ponte loopback -> bt-core -> piece validada.
 * Não usa TCP entre os peers: a única ligação entre as runtimes é a UtpBitTorrentBridge.
 */
class UtpBitTorrentBridgeIntegrationTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(25);

    @TempDir Path temporaryDirectory;

    @Test void transfersARealTorrentWhenOutgoingUtpAlreadyKnowsTheTorrentId() throws Exception {
        transferARealTorrentThroughUtpBridgeAndVerifyThePiece();
    }

    @Test void transfersARealTorrentWhenIncomingUtpLearnsTheTorrentIdFromHandshake() throws Exception {
        transferARealTorrentThroughUtpBridgeAndVerifyThePiece();
    }

    private void transferARealTorrentThroughUtpBridgeAndVerifyThePiece() throws Exception {
        Path seedStorage = Files.createDirectories(temporaryDirectory.resolve("seed"));
        Path downloadStorage = Files.createDirectories(temporaryDirectory.resolve("download"));
        Path source = seedStorage.resolve("teste.txt");
        Files.writeString(source, "OLA LUFFY", StandardCharsets.UTF_8);
        long sourceSize = Files.size(source);
        TorrentMetainfoGenerator.PublishedTorrent published = new TorrentMetainfoGenerator()
                .publish(source, temporaryDirectory.resolve("metainfo"));
        String infoHash = published.magnet().infoHash();
        TorrentId torrentId = TorrentId.fromBytes(HexFormat.of().parseHex(infoHash));

        assertTrue(Files.isRegularFile(published.torrentFile()), "B deve possuir o metainfo do torrent carregável");
        assertEquals(infoHash, HexFormat.of().formatHex(torrentId.getBytes()), "o TorrentId deve corresponder ao metainfo compartilhado");

        P2pDiagnostics diagnosticsA = new P2pDiagnostics();
        P2pDiagnostics diagnosticsB = new P2pDiagnostics();
        AtomicBoolean connectionRegisteredByA = new AtomicBoolean();
        AtomicBoolean connectionRegisteredByB = new AtomicBoolean();
        AtomicBoolean pieceVerifiedByA = new AtomicBoolean();
        AtomicLong downloadedByA = new AtomicLong();
        AtomicLong uploadedByB = new AtomicLong();
        CompletableFuture<Void> downloadCompleted = new CompletableFuture<>();

        BtRuntime runtimeA = null;
        BtRuntime runtimeB = null;
        PeerConnectivityManager connectivityA = null;
        PeerConnectivityManager connectivityB = null;
        UtpBitTorrentBridge bridgeA = null;
        UtpBitTorrentBridge bridgeB = null;
        BtClient downloaderA = null;
        BtClient seederB = null;
        try {
            InetAddress loopback = InetAddress.getLoopbackAddress();
            runtimeA = BtRuntime.builder(runtimeConfig(loopback)).disableAutomaticShutdown().build();
            runtimeB = BtRuntime.builder(runtimeConfig(loopback)).disableAutomaticShutdown().build();
            connectivityA = new PeerConnectivityManager(diagnosticsA, ignored -> { });
            connectivityB = new PeerConnectivityManager(diagnosticsB, ignored -> { });
            assertTrue(BtConnectionLifecycleInstrumentation.install(runtimeA, connectivityA, diagnosticsA));
            assertTrue(BtConnectionLifecycleInstrumentation.install(runtimeB, connectivityB, diagnosticsB));

            UtpTransportService utpA = new UtpTransportService(loopback, 0, diagnosticsA);
            UtpTransportService utpB = new UtpTransportService(loopback, 0, diagnosticsB);
            bridgeA = new UtpBitTorrentBridge(diagnosticsA, connectivityA);
            bridgeB = new UtpBitTorrentBridge(diagnosticsB, connectivityB);
            bridgeA.attach(runtimeA, utpA);
            bridgeB.attach(runtimeB, utpB);
            UtpBitTorrentBridge attachedBridgeA = bridgeA;
            UtpBitTorrentBridge attachedBridgeB = bridgeB;

            runtimeA.getEventSource().onPeerConnected(torrentId, event -> connectionRegisteredByA.set(true));
            runtimeB.getEventSource().onPeerConnected(torrentId, event -> connectionRegisteredByB.set(true));
            runtimeA.getEventSource().onPieceVerified(torrentId, event -> pieceVerifiedByA.set(true));

            // B recebe o arquivo local e torna-se seed; A recebe o mesmo metainfo em vez de depender de DHT/metadata.
            seederB = Bt.client(runtimeB).storage(new FileSystemStorage(seedStorage))
                    .torrent(published.torrentFile().toUri().toURL()).build();
            downloaderA = Bt.client(runtimeA).storage(new FileSystemStorage(downloadStorage))
                    .torrent(published.torrentFile().toUri().toURL()).stopWhenDownloaded()
                    .afterDownloaded(ignored -> downloadCompleted.complete(null)).build();
            BtClient activeSeederB = seederB;
            BtClient activeDownloaderA = downloaderA;
            PeerConnectivityManager activeConnectivityA = connectivityA;

            activeSeederB.startAsync(state -> uploadedByB.accumulateAndGet(state.getUploaded(), Math::max), 25);
            activeDownloaderA.startAsync(state -> downloadedByA.accumulateAndGet(state.getDownloaded(), Math::max), 25);
            await(() -> activeSeederB.isStarted() && activeDownloaderA.isStarted(), Duration.ofSeconds(5), "as duas sessões BitTorrent iniciarem");
            assertTrue(activeSeederB.isStarted(), "B deve ter o torrent carregado e semeando");
            assertTrue(activeDownloaderA.isStarted(), "A deve ter o metainfo carregado para baixar");

            // Este é o único caminho de peer do teste: A abre uTP para B e a ponte entrega o canal ao bt-core.
            bridgeA.connectDirect(infoHash, loopback, utpB.localPort()).get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);

            Supplier<String> diagnosticContext = () -> "\n--- Diagnóstico A ---\n" + diagnosticsA.snapshot()
                    + "\n--- Diagnóstico B ---\n" + diagnosticsB.snapshot();
            await(() -> diagnosticsA.snapshot().contains("OUTBOUND SYN"), Duration.ofSeconds(5), "A iniciar a sessão uTP", diagnosticContext);
            await(() -> diagnosticsB.snapshot().contains("INBOUND SYN"), Duration.ofSeconds(5), "B aceitar a sessão uTP", diagnosticContext);
            await(() -> attachedBridgeA.activeLoopbackPairCount() > 0 && attachedBridgeA.activePumpTaskCount() >= 2,
                    Duration.ofSeconds(5), "a ponte A criar os canais locais e as bombas de bytes", diagnosticContext);
            await(() -> attachedBridgeB.activeLoopbackPairCount() > 0 && attachedBridgeB.activePumpTaskCount() >= 2,
                    Duration.ofSeconds(5), "a ponte B criar os canais locais e as bombas de bytes", diagnosticContext);
            await(() -> diagnosticsA.snapshot().contains("uTP BITTORRENT HANDSHAKE START"), Duration.ofSeconds(5),
                    "A entregar o canal loopback ao bt-core", diagnosticContext);
            await(() -> diagnosticsB.snapshot().contains("uTP BITTORRENT INCOMING START"), Duration.ofSeconds(5),
                    "B entregar o canal loopback ao bt-core", diagnosticContext);
            assertTrue(diagnosticsB.snapshot().contains("uTP BITTORRENT INCOMING START: infoHash=" + infoHash),
                    "B deve descobrir o TorrentId somente apos o handshake BitTorrent de entrada");
            await(connectionRegisteredByA::get, Duration.ofSeconds(5),
                    "A registrar o canal entregue na pool BitTorrent", diagnosticContext);
            await(connectionRegisteredByB::get, Duration.ofSeconds(5),
                    "B registrar o canal entregue na pool BitTorrent", diagnosticContext);
            await(() -> diagnosticsA.snapshot().contains("BITTORRENT HANDSHAKE SUCCESS"), TIMEOUT,
                    "o handshake BitTorrent de saída sobre uTP ser aceito", diagnosticContext);
            await(() -> activeConnectivityA.peersFor(infoHash).stream().anyMatch(peer ->
                    peer.endpoint().transport() == PeerConnectivityManager.Transport.UTP
                            && peer.connection() == PeerConnectivityManager.ConnectionState.CONNECTED), TIMEOUT,
                    "o torrent correto ser associado à sessão uTP de A", diagnosticContext);
            await(downloadCompleted::isDone, TIMEOUT, "a transferência do torrent ser concluída", diagnosticContext);
            downloadCompleted.get(1, TimeUnit.SECONDS);
            await(pieceVerifiedByA::get, TIMEOUT, "a piece recebida por A ser validada pelo bt-core", diagnosticContext);
            await(() -> downloadedByA.get() >= sourceSize, TIMEOUT, "A registrar os bytes baixados após request de piece", diagnosticContext);
            await(() -> uploadedByB.get() >= sourceSize, TIMEOUT, "B registrar a resposta da piece enviada", diagnosticContext);

            Path received = downloadStorage.resolve("teste.txt");
            assertEquals("OLA LUFFY", Files.readString(received, StandardCharsets.UTF_8));
            assertEquals(sha1(source), sha1(received),
                    "o hash final do arquivo recebido deve ser o mesmo da piece validada pelo torrent");
            assertTrue(diagnosticsA.snapshot().contains("OUTBOUND SYN"), "A deve iniciar uma sessão uTP");
            assertTrue(diagnosticsB.snapshot().contains("INBOUND SYN"), "B deve aceitar a sessão uTP");
            assertTrue(diagnosticsA.snapshot().contains("uTP BITTORRENT HANDSHAKE START"), "A deve entregar o canal local ao bt-core");
            assertTrue(diagnosticsB.snapshot().contains("uTP BITTORRENT INCOMING START"), "B deve entregar o canal local ao bt-core");
        } finally {
            stop(downloaderA);
            stop(seederB);
            if (bridgeA != null) {
                UtpBitTorrentBridge activeBridgeA = bridgeA;
                activeBridgeA.close();
                await(() -> activeBridgeA.activeLoopbackPairCount() == 0 && activeBridgeA.activePumpTaskCount() == 0
                                && activeBridgeA.pendingConnectionCount() == 0,
                        Duration.ofSeconds(5), "os canais e tarefas virtuais da ponte A encerrarem");
            }
            if (bridgeB != null) {
                UtpBitTorrentBridge activeBridgeB = bridgeB;
                activeBridgeB.close();
                await(() -> activeBridgeB.activeLoopbackPairCount() == 0 && activeBridgeB.activePumpTaskCount() == 0
                                && activeBridgeB.pendingConnectionCount() == 0,
                        Duration.ofSeconds(5), "os canais e tarefas virtuais da ponte B encerrarem");
            }
            if (connectivityA != null) connectivityA.close();
            if (connectivityB != null) connectivityB.close();
            if (runtimeA != null) runtimeA.shutdown();
            if (runtimeB != null) runtimeB.shutdown();
        }
    }

    private static Config runtimeConfig(InetAddress address) {
        Config config = new Config();
        config.setAcceptorAddress(address);
        config.setAcceptorPort(0);
        config.setPeerHandshakeTimeout(Duration.ofSeconds(5));
        config.setPeerConnectionTimeout(Duration.ofSeconds(5));
        config.setPeerConnectionRetryCount(0);
        return config;
    }

    private static void await(BooleanSupplier condition, Duration timeout, String description) throws Exception {
        await(condition, timeout, description, () -> "");
    }

    private static void await(BooleanSupplier condition, Duration timeout, String description, Supplier<String> diagnosticContext) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(25);
        }
        fail("Tempo limite aguardando " + description + diagnosticContext.get());
    }

    private static void stop(BtClient client) {
        if (client != null) client.stop();
    }

    private static String sha1(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(file)));
    }
}
