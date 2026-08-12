package dev.lufi.ui;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Teste opt-in para um arquivo local real. Ele nunca roda na suite normal:
 * requer LUFFY_LOCAL_VIDEO_SMOKE com o caminho do arquivo a ser validado.
 */
@EnabledIfEnvironmentVariable(named = "LUFFY_LOCAL_VIDEO_SMOKE", matches = ".+")
class LibVlcPlayerBackendLocalSmokeTest {
    private static final long TIMEOUT_SECONDS = 15L;

    @Test
    void playsPausesResumesSeeksAndDeliversAnImageForALocalFile() throws Exception {
        Path source = Path.of(System.getenv("LUFFY_LOCAL_VIDEO_SMOKE"));
        assertTrue(Files.isRegularFile(source) && Files.isReadable(source), "Arquivo local não pode ser lido: " + source);

        AtomicReference<LibVlcPlayerBackend> backendReference = new AtomicReference<>();
        AtomicReference<LuffyVideoView> viewReference = new AtomicReference<>();
        AtomicReference<Stage> stageReference = new AtomicReference<>();
        AtomicReference<Throwable> playerError = new AtomicReference<>();
        ConcurrentLinkedQueue<MediaPlayerBackend.State> states = new ConcurrentLinkedQueue<>();
        CountDownLatch playing = new CountDownLatch(1);
        CountDownLatch paused = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);

        try {
            onFxThread(() -> {
                LibVlcPlayerBackend backend = new LibVlcPlayerBackend();
                backendReference.set(backend);
                LuffyVideoView view = (LuffyVideoView) backend.createVideoView();
                viewReference.set(view);
                Stage stage = new Stage();
                stage.setScene(new Scene(new StackPane(view), 640, 360));
                stageReference.set(stage);
                stage.show();
                backend.setMute(true);
                backend.setListener(new MediaPlayerBackend.Listener() {
                    @Override public void onStateChanged(MediaPlayerBackend.State state) {
                        states.add(state);
                        switch (state) {
                            case PLAYING -> playing.countDown();
                            case PAUSED -> paused.countDown();
                            case STOPPED -> stopped.countDown();
                            default -> { }
                        }
                    }
                    @Override public void onError(Throwable error) {
                        playerError.compareAndSet(null, error);
                        playing.countDown();
                        paused.countDown();
                    }
                });
                backend.open(new LocalFileMediaSource(source).uri());
            });

            assertTrue(playing.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), failureMessage("libVLC não iniciou a reprodução", playerError));
            assertNoPlayerError(playerError);
            assertTrue(awaitPresentedFrame(backendReference.get(), viewReference.get(), playerError),
                    failureMessage("libVLC iniciou sem apresentar um frame JavaFX", playerError));

            onFxThread(() -> backendReference.get().pause());
            assertTrue(paused.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), failureMessage("libVLC não pausou", playerError));
            assertNoPlayerError(playerError);

            onFxThread(() -> {
                LibVlcPlayerBackend backend = backendReference.get();
                backend.play();
                if (backend.isSeekable()) {
                    backend.seek(java.time.Duration.of(1, ChronoUnit.SECONDS));
                }
            });
            onFxThread(() -> backendReference.get().stop());
            assertTrue(stopped.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), failureMessage("libVLC não parou", playerError));
            assertTrue(states.contains(MediaPlayerBackend.State.OPENING), "Evento OPENING não foi publicado.");
            assertTrue(states.contains(MediaPlayerBackend.State.PLAYING), "Evento PLAYING não foi publicado.");
            assertTrue(states.contains(MediaPlayerBackend.State.PAUSED), "Evento PAUSED não foi publicado.");
            assertTrue(states.contains(MediaPlayerBackend.State.STOPPED), "Evento STOPPED não foi publicado.");
            assertNoPlayerError(playerError);
            onFxThread(() -> backendReference.get().release());
            assertEquals(MediaPlayerBackend.State.STOPPED, backendReference.get().getState(),
                    "release() must leave the backend in STOPPED.");
            assertTrue(backendReference.get().getPosition().isEmpty(),
                    "release() must clear the native playback position.");
            assertTrue(backendReference.get().getDuration().isEmpty(),
                    "release() must clear the native playback duration.");
        } finally {
            LibVlcPlayerBackend backend = backendReference.get();
            if (backend != null) onFxThread(backend::release);
            Stage stage = stageReference.get();
            if (stage != null) onFxThread(stage::close);
        }
    }

    @Test
    void playsTheSameLocalFileThroughTheLoopbackStreamingSource() throws Exception {
        Path source = Path.of(System.getenv("LUFFY_LOCAL_VIDEO_SMOKE"));
        assertTrue(Files.isRegularFile(source) && Files.isReadable(source), "Arquivo local nao pode ser lido: " + source);
        long sourceSize = Files.size(source);
        LuffyLocalMediaServer server = new LuffyLocalMediaServer(new dev.lufi.infrastructure.P2pDiagnostics());
        AtomicReference<LibVlcPlayerBackend> backendReference = new AtomicReference<>();
        AtomicReference<LuffyVideoView> viewReference = new AtomicReference<>();
        AtomicReference<Stage> stageReference = new AtomicReference<>();
        AtomicReference<Throwable> playerError = new AtomicReference<>();
        CountDownLatch playing = new CountDownLatch(1);
        try {
            TorrentStreamingMediaSource streamingSource = server.register(source,
                    () -> new LuffyLocalMediaServer.VerifiedMediaWindow(sourceSize, sourceSize, true));
            onFxThread(() -> {
                LibVlcPlayerBackend backend = new LibVlcPlayerBackend();
                backendReference.set(backend);
                LuffyVideoView view = (LuffyVideoView) backend.createVideoView();
                viewReference.set(view);
                Stage stage = new Stage();
                stage.setScene(new Scene(new StackPane(view), 640, 360));
                stageReference.set(stage);
                stage.show();
                backend.setMute(true);
                backend.setListener(new MediaPlayerBackend.Listener() {
                    @Override public void onStateChanged(MediaPlayerBackend.State state) {
                        if (state == MediaPlayerBackend.State.PLAYING) playing.countDown();
                    }
                    @Override public void onError(Throwable error) {
                        playerError.compareAndSet(null, error);
                        playing.countDown();
                    }
                });
                backend.open(streamingSource.uri());
            });
            assertTrue(playing.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    failureMessage("libVLC nao iniciou a reproducao HTTP local", playerError));
            assertNoPlayerError(playerError);
            assertTrue(awaitPresentedFrame(backendReference.get(), viewReference.get(), playerError),
                    failureMessage("libVLC nao apresentou imagem a partir do HTTP local", playerError));
        } finally {
            LibVlcPlayerBackend backend = backendReference.get();
            if (backend != null) onFxThread(backend::release);
            Stage stage = stageReference.get();
            if (stage != null) onFxThread(stage::close);
            server.close();
        }
    }

    private static boolean awaitPresentedFrame(LibVlcPlayerBackend backend, LuffyVideoView view,
                                               AtomicReference<Throwable> playerError) throws Exception {
        for (int attempt = 0; attempt < TIMEOUT_SECONDS * 10; attempt++) {
            AtomicReference<Boolean> imageReady = new AtomicReference<>(false);
            onFxThread(() -> imageReady.set(((ImageView) view.getChildren().getFirst()).getImage() != null));
            if (imageReady.get() && backend.hasPresentedFrame()) return true;
            if (playerError.get() != null) return false;
            Thread.sleep(100L);
        }
        return false;
    }

    private static void assertNoPlayerError(AtomicReference<Throwable> playerError) {
        if (playerError.get() != null) fail(failureMessage("libVLC reportou erro", playerError));
    }

    private static String failureMessage(String prefix, AtomicReference<Throwable> playerError) {
        Throwable error = playerError.get();
        return error == null ? prefix : prefix + ": " + error.getMessage();
    }

    private static void onFxThread(ThrowingRunnable action) throws Exception {
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Runnable invocation = () -> {
            try {
                Platform.setImplicitExit(false);
                action.run();
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                completed.countDown();
            }
        };
        try {
            Platform.startup(invocation);
        } catch (IllegalStateException alreadyStarted) {
            Platform.runLater(invocation);
        }
        assertTrue(completed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "JavaFX não respondeu ao teste do libVLC.");
        if (failure.get() != null) throw new AssertionError("Falha na JavaFX Application Thread", failure.get());
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
