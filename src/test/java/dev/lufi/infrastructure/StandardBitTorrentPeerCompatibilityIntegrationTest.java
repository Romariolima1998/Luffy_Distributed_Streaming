package dev.lufi.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import bt.Bt;
import bt.data.file.FileSystemStorage;
import bt.metainfo.TorrentId;
import bt.net.InetPeer;
import bt.net.IConnectionSource;
import bt.runtime.BtClient;
import bt.runtime.BtRuntime;
import bt.runtime.Config;
import dev.lufi.infrastructure.identity.LuffyIdentityExtension;
import dev.lufi.infrastructure.identity.LuffyIdentityMessage;
import dev.lufi.infrastructure.identity.LuffyNodeId;
import dev.lufi.infrastructure.identity.LuffyNodeIdentity;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A simulates Luffy, while B deliberately has no lf_identity, lf_route or
 * lf_rendezvous module. The transfer must remain ordinary BitTorrent/TCP.
 */
class StandardBitTorrentPeerCompatibilityIntegrationTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    @TempDir Path temporaryDirectory;

    @Test void downloadsFromAStandardPeerThatDoesNotSupportAnyLuffyExtension() throws Exception {
        Path seedStorage = Files.createDirectories(temporaryDirectory.resolve("standard-seed"));
        Path downloadStorage = Files.createDirectories(temporaryDirectory.resolve("luffy-download"));
        Path source = seedStorage.resolve("teste.txt");
        Files.writeString(source, "OLA LUFFY", StandardCharsets.UTF_8);
        TorrentMetainfoGenerator.PublishedTorrent published = new TorrentMetainfoGenerator()
                .publish(source, temporaryDirectory.resolve("metainfo"));
        TorrentId torrentId = TorrentId.fromBytes(HexFormat.of().parseHex(published.magnet().infoHash()));

        InetAddress loopback = InetAddress.getLoopbackAddress();
        int standardPeerPort = freeTcpPort(loopback);
        LuffyIdentityExtension identity = luffyIdentityExtension();
        BtRuntime luffyRuntime = null;
        BtRuntime standardRuntime = null;
        BtClient downloader = null;
        BtClient seeder = null;
        CompletableFuture<Void> completed = new CompletableFuture<>();
        try {
            luffyRuntime = BtRuntime.builder(runtimeConfig(loopback, freeTcpPort(loopback))).disableAutomaticShutdown()
                    .module(identity).module(identity.handshakeObserverModule()).build();
            // B is intentionally a plain bt-core runtime: no Luffy extension is installed.
            standardRuntime = BtRuntime.builder(runtimeConfig(loopback, standardPeerPort)).disableAutomaticShutdown().build();

            seeder = Bt.client(standardRuntime).storage(new FileSystemStorage(seedStorage))
                    .torrent(published.torrentFile().toUri().toURL()).build();
            downloader = Bt.client(luffyRuntime).storage(new FileSystemStorage(downloadStorage))
                    .torrent(published.torrentFile().toUri().toURL()).stopWhenDownloaded()
                    .afterDownloaded(ignored -> completed.complete(null)).build();
            BtClient activeSeeder = seeder;
            BtClient activeDownloader = downloader;
            BtRuntime activeLuffyRuntime = luffyRuntime;
            BtRuntime activeStandardRuntime = standardRuntime;
            activeSeeder.startAsync(ignored -> { }, 25);
            activeDownloader.startAsync(ignored -> { }, 25);
            await(() -> activeSeeder.isStarted() && activeDownloader.isStarted(),
                    "as duas sessoes BitTorrent iniciarem");
            await(() -> activeLuffyRuntime.service(bt.torrent.TorrentRegistry.class).getTorrent(torrentId).isPresent()
                            && activeStandardRuntime.service(bt.torrent.TorrentRegistry.class).getTorrent(torrentId).isPresent(),
                    "as duas runtimes registrarem o TorrentId antes da conexao TCP");

            var connection = activeLuffyRuntime.service(IConnectionSource.class)
                    .getConnectionAsync(InetPeer.build(loopback, standardPeerPort), torrentId)
                    .get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            assertTrue(connection.isSuccess(), connection.getMessage().orElse("bt-core recusou a conexao TCP padrao"));

            completed.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            Path received = downloadStorage.resolve("teste.txt");
            assertEquals("OLA LUFFY", Files.readString(received, StandardCharsets.UTF_8));
            assertEquals(0, identity.identifiedPeerCount(),
                    "um peer sem lf_identity nao pode ser rejeitado nem registrado como Luffy");
        } finally {
            if (downloader != null) downloader.stop();
            if (seeder != null) seeder.stop();
            if (luffyRuntime != null) luffyRuntime.shutdown();
            if (standardRuntime != null) standardRuntime.shutdown();
        }
    }

    private static LuffyIdentityExtension luffyIdentityExtension() {
        byte[] nodeIdBytes = new byte[LuffyNodeId.BINARY_LENGTH];
        Arrays.fill(nodeIdBytes, (byte) 1);
        LuffyNodeId nodeId = LuffyNodeId.fromBinary(nodeIdBytes);
        LuffyNodeIdentity local = new LuffyNodeIdentity(nodeId, Instant.parse("2026-08-10T13:00:00Z"));
        return new LuffyIdentityExtension(local,
                () -> new LuffyIdentityMessage(LuffyIdentityMessage.PROTOCOL_VERSION, nodeId, "Luffy/0.1.0",
                        false, false, false, false), new P2pDiagnostics());
    }

    private static Config runtimeConfig(InetAddress address, int port) {
        Config config = new Config();
        config.setAcceptorAddress(address);
        config.setAcceptorPort(port);
        config.setPeerHandshakeTimeout(Duration.ofSeconds(5));
        config.setPeerConnectionTimeout(Duration.ofSeconds(5));
        config.setPeerConnectionRetryCount(0);
        return config;
    }

    private static int freeTcpPort(InetAddress address) throws Exception {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress(address, 0));
            return socket.getLocalPort();
        }
    }

    private static void await(BooleanSupplier condition, String description) throws Exception {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(25);
        }
        fail("Tempo limite aguardando " + description);
    }
}
