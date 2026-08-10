package dev.lufi.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bt.Bt;
import bt.data.file.FileSystemStorage;
import bt.metainfo.TorrentId;
import bt.net.IConnectionSource;
import bt.net.InetPeer;
import bt.runtime.BtClient;
import bt.runtime.BtRuntime;
import bt.runtime.Config;
import bt.torrent.fileselector.FilePriority;
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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Regressão: um torrent de arquivo único não pode ser marcado como SKIP no streaming. */
class StreamingSingleFilePriorityIntegrationTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    @TempDir Path temporaryDirectory;

    @Test void downloadsTheSelectedSingleFileInsteadOfTreatingItAsSkipped() throws Exception {
        Path seedStorage = Files.createDirectories(temporaryDirectory.resolve("seed"));
        Path downloadStorage = Files.createDirectories(temporaryDirectory.resolve("download"));
        Path source = seedStorage.resolve("teste.mkv");
        Files.writeString(source, "OLA LUFFY", StandardCharsets.UTF_8);
        TorrentMetainfoGenerator.PublishedTorrent published = new TorrentMetainfoGenerator()
                .publish(source, temporaryDirectory.resolve("metainfo"));
        TorrentId torrentId = TorrentId.fromBytes(HexFormat.of().parseHex(published.magnet().infoHash()));

        InetAddress loopback = InetAddress.getLoopbackAddress();
        int seederPort = freeTcpPort(loopback);
        BtRuntime seederRuntime = null;
        BtRuntime downloaderRuntime = null;
        BtClient seeder = null;
        BtClient downloader = null;
        try {
            seederRuntime = BtRuntime.builder(config(loopback, seederPort)).disableAutomaticShutdown().build();
            downloaderRuntime = BtRuntime.builder(config(loopback, freeTcpPort(loopback))).disableAutomaticShutdown().build();
            CompletableFuture<Void> completed = new CompletableFuture<>();
            AtomicReference<FilePriority> selectedPriority = new AtomicReference<>();

            seeder = Bt.client(seederRuntime).storage(new FileSystemStorage(seedStorage))
                    .torrent(published.torrentFile().toUri().toURL()).build();
            downloader = Bt.client(downloaderRuntime).storage(new FileSystemStorage(downloadStorage))
                    .torrent(published.torrentFile().toUri().toURL()).sequentialSelector()
                    .fileSelector(file -> {
                        FilePriority priority = StreamingFileSelection.matches("teste.mkv", file.getPathElements())
                                ? FilePriority.HIGH_PRIORITY : FilePriority.SKIP;
                        selectedPriority.set(priority);
                        return priority;
                    }).stopWhenDownloaded().afterDownloaded(ignored -> completed.complete(null)).build();

            BtClient activeSeeder = seeder;
            BtClient activeDownloader = downloader;
            activeSeeder.startAsync();
            activeDownloader.startAsync();
            waitUntil(() -> activeSeeder.isStarted() && activeDownloader.isStarted(), "as sessões iniciarem");
            assertTrue(downloaderRuntime.service(IConnectionSource.class)
                    .getConnectionAsync(InetPeer.build(loopback, seederPort), torrentId)
                    .get(TIMEOUT.toSeconds(), TimeUnit.SECONDS).isSuccess());
            completed.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);

            assertEquals(FilePriority.HIGH_PRIORITY, selectedPriority.get(), "o arquivo único escolhido não pode virar SKIP");
            assertEquals("OLA LUFFY", Files.readString(downloadStorage.resolve("teste.mkv"), StandardCharsets.UTF_8));
        } finally {
            if (downloader != null) downloader.stop();
            if (seeder != null) seeder.stop();
            if (downloaderRuntime != null) downloaderRuntime.shutdown();
            if (seederRuntime != null) seederRuntime.shutdown();
        }
    }

    @Test void waitsForTheMetadataOnlyClientToStopBeforeStartingTheSelectedStream() throws Exception {
        Path seedStorage = Files.createDirectories(temporaryDirectory.resolve("seed-restart"));
        Path downloadStorage = Files.createDirectories(temporaryDirectory.resolve("download-restart"));
        Path source = seedStorage.resolve("teste.mkv");
        Files.writeString(source, "OLA LUFFY", StandardCharsets.UTF_8);
        TorrentMetainfoGenerator.PublishedTorrent published = new TorrentMetainfoGenerator()
                .publish(source, temporaryDirectory.resolve("metainfo-restart"));
        TorrentId torrentId = TorrentId.fromBytes(HexFormat.of().parseHex(published.magnet().infoHash()));

        InetAddress loopback = InetAddress.getLoopbackAddress();
        BtRuntime seederRuntime = null;
        BtRuntime previewRuntime = null;
        BtRuntime downloaderRuntime = null;
        BtClient seeder = null;
        BtClient metadataOnly = null;
        BtClient selectedStream = null;
        try {
            int seederPort = freeTcpPort(loopback);
            seederRuntime = BtRuntime.builder(config(loopback, seederPort)).disableAutomaticShutdown().build();
            previewRuntime = BtRuntime.builder(config(loopback, freeTcpPort(loopback))).disableAutomaticShutdown().build();
            downloaderRuntime = BtRuntime.builder(config(loopback, freeTcpPort(loopback))).disableAutomaticShutdown().build();
            CompletableFuture<Void> metadataReceived = new CompletableFuture<>();
            CompletableFuture<Void> downloaded = new CompletableFuture<>();

            seeder = Bt.client(seederRuntime).storage(new FileSystemStorage(seedStorage))
                    .torrent(published.torrentFile().toUri().toURL()).build();
            metadataOnly = Bt.client(previewRuntime).storage(new FileSystemStorage(downloadStorage))
                    .torrent(published.torrentFile().toUri().toURL())
                    .fileSelector(file -> FilePriority.SKIP)
                    .afterTorrentFetched(ignored -> metadataReceived.complete(null)).build();

            BtClient activeSeeder = seeder;
            BtClient activeMetadataOnly = metadataOnly;
            activeSeeder.startAsync();
            CompletableFuture<?> metadataProcess = activeMetadataOnly.startAsync();
            waitUntil(() -> activeSeeder.isStarted() && activeMetadataOnly.isStarted(), "a sessão de metadados iniciar");
            assertTrue(previewRuntime.service(IConnectionSource.class)
                    .getConnectionAsync(InetPeer.build(loopback, seederPort), torrentId)
                    .get(TIMEOUT.toSeconds(), TimeUnit.SECONDS).isSuccess());
            metadataReceived.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);

            // A troca só é segura depois da conclusão do processador anterior.
            activeMetadataOnly.stop();
            metadataProcess.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);

            selectedStream = Bt.client(downloaderRuntime).storage(new FileSystemStorage(downloadStorage))
                    .torrent(published.torrentFile().toUri().toURL()).sequentialSelector()
                    .fileSelector(file -> StreamingFileSelection.matches("teste.mkv", file.getPathElements())
                            ? FilePriority.HIGH_PRIORITY : FilePriority.SKIP)
                    .stopWhenDownloaded().afterDownloaded(ignored -> downloaded.complete(null)).build();
            BtClient activeSelectedStream = selectedStream;
            activeSelectedStream.startAsync();
            waitUntil(activeSelectedStream::isStarted, "a sessão selecionada iniciar");
            assertTrue(downloaderRuntime.service(IConnectionSource.class)
                    .getConnectionAsync(InetPeer.build(loopback, seederPort), torrentId)
                    .get(TIMEOUT.toSeconds(), TimeUnit.SECONDS).isSuccess());
            downloaded.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);

            assertEquals("OLA LUFFY", Files.readString(downloadStorage.resolve("teste.mkv"), StandardCharsets.UTF_8));
        } finally {
            if (selectedStream != null) selectedStream.stop();
            if (metadataOnly != null) metadataOnly.stop();
            if (seeder != null) seeder.stop();
            if (downloaderRuntime != null) downloaderRuntime.shutdown();
            if (previewRuntime != null) previewRuntime.shutdown();
            if (seederRuntime != null) seederRuntime.shutdown();
        }
    }

    private static Config config(InetAddress address, int port) {
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

    private static void waitUntil(java.util.function.BooleanSupplier condition, String description) throws Exception {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(25);
        }
        throw new AssertionError("Tempo limite aguardando " + description);
    }
}
