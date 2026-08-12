package dev.lufi.ui;

import javafx.application.Platform;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelBuffer;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.embedded.videosurface.CallbackVideoSurface;
import uk.co.caprica.vlcj.player.embedded.videosurface.VideoSurfaceAdapters;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Superfície de direct rendering compatível com vlcj 4.12.x e JavaFX 21.
 *
 * <p>O artefato {@code vlcj-javafx:1.2.0} disponível publicamente foi
 * compilado contra uma assinatura anterior de {@link RenderCallback}. Em
 * execução com vlcj 4.12.x, o callback de exibição não é chamado de forma
 * compatível e o libVLC aborta a criação do vídeo. Esta implementação mantém
 * a mesma arquitetura suportada: o buffer RV32 nativo é exibido por um
 * {@link PixelBuffer} no {@link ImageView}, sem conversão de cor, decoder ou
 * scheduler de vídeo próprio no Luffy.</p>
 */
final class LibVlcJavaFxVideoSurface extends CallbackVideoSurface {
    private static final long FX_ALLOCATION_TIMEOUT_SECONDS = 3L;

    private final SurfaceState state;

    LibVlcJavaFxVideoSurface(ImageView imageView, Consumer<String> diagnostics) {
        this(new SurfaceState(imageView, diagnostics));
    }

    private LibVlcJavaFxVideoSurface(SurfaceState state) {
        super(state, state, false, VideoSurfaceAdapters.getVideoSurfaceAdapter());
        this.state = state;
    }

    void dispose() {
        state.dispose();
    }

    boolean hasPresentedFrame() {
        return state.presentedFrames.get() > 0L;
    }

    private static final class SurfaceState implements BufferFormatCallback, RenderCallback {
        private final ImageView imageView;
        private final Consumer<String> diagnostics;
        private final AtomicBoolean disposed = new AtomicBoolean();
        private final AtomicBoolean uiUpdateQueued = new AtomicBoolean();
        private final AtomicLong presentedFrames = new AtomicLong();

        private volatile int width;
        private volatile int height;
        private volatile PixelBuffer<ByteBuffer> pixelBuffer;

        private SurfaceState(ImageView imageView, Consumer<String> diagnostics) {
            this.imageView = Objects.requireNonNull(imageView, "imageView");
            this.diagnostics = diagnostics == null ? ignored -> { } : diagnostics;
        }

        @Override
        public BufferFormat getBufferFormat(int sourceWidth, int sourceHeight) {
            width = sourceWidth;
            height = sourceHeight;
            return new RV32BufferFormat(sourceWidth, sourceHeight);
        }

        @Override
        public void newFormatSize(int bufferWidth, int bufferHeight, int displayWidth, int displayHeight) {
            width = bufferWidth;
            height = bufferHeight;
        }

        @Override
        public void allocatedBuffers(ByteBuffer[] buffers) {
            if (disposed.get()) {
                return;
            }
            if (buffers == null || buffers.length == 0 || buffers[0] == null || width <= 0 || height <= 0) {
                diagnostics.accept("PLAYER BACKEND: libVLC forneceu um buffer de vídeo inválido.");
                return;
            }

            ByteBuffer nativeBuffer = buffers[0];
            int bufferWidth = width;
            int bufferHeight = height;
            try {
                runOnFxThreadAndWait(() -> {
                    if (disposed.get()) {
                        return;
                    }
                    PixelBuffer<ByteBuffer> created = new PixelBuffer<>(
                            bufferWidth,
                            bufferHeight,
                            nativeBuffer,
                            PixelFormat.getByteBgraPreInstance());
                    pixelBuffer = created;
                    imageView.setImage(new WritableImage(created));
                    diagnostics.accept("PLAYER BACKEND: superfície JavaFX direta pronta; "
                            + "format=RV32; width=" + bufferWidth + "; height=" + bufferHeight + ".");
                });
            } catch (RuntimeException error) {
                diagnostics.accept("PLAYER BACKEND: falha ao preparar PixelBuffer JavaFX: "
                        + error.getClass().getSimpleName() + ": " + safeMessage(error) + ".");
                throw error;
            }
        }

        @Override
        public void lock(MediaPlayer mediaPlayer) {
            // O libVLC escreve no ByteBuffer direto disponibilizado pelo vlcj.
        }

        @Override
        public void display(MediaPlayer mediaPlayer, ByteBuffer[] nativeBuffers, BufferFormat bufferFormat,
                            int displayWidth, int displayHeight) {
            PixelBuffer<ByteBuffer> current = pixelBuffer;
            if (disposed.get() || current == null || !uiUpdateQueued.compareAndSet(false, true)) {
                return;
            }
            try {
                Platform.runLater(() -> {
                    try {
                        if (!disposed.get() && pixelBuffer == current) {
                            current.updateBuffer(ignored -> null);
                            presentedFrames.incrementAndGet();
                        }
                    } finally {
                        uiUpdateQueued.set(false);
                    }
                });
            } catch (IllegalStateException toolkitStopping) {
                uiUpdateQueued.set(false);
            }
        }

        @Override
        public void unlock(MediaPlayer mediaPlayer) {
            // A atualização visual é coalescida na JavaFX Application Thread.
        }

        private void dispose() {
            if (!disposed.compareAndSet(false, true)) {
                return;
            }
            pixelBuffer = null;
            uiUpdateQueued.set(false);
            try {
                if (Platform.isFxApplicationThread()) {
                    imageView.setImage(null);
                } else {
                    Platform.runLater(() -> imageView.setImage(null));
                }
            } catch (IllegalStateException ignored) {
                // A aplicação JavaFX já está sendo encerrada.
            }
        }

        private static void runOnFxThreadAndWait(Runnable action) {
            if (Platform.isFxApplicationThread()) {
                action.run();
                return;
            }
            CountDownLatch completed = new CountDownLatch(1);
            RuntimeException[] failure = new RuntimeException[1];
            try {
                Platform.runLater(() -> {
                    try {
                        action.run();
                    } catch (RuntimeException error) {
                        failure[0] = error;
                    } finally {
                        completed.countDown();
                    }
                });
                if (!completed.await(FX_ALLOCATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("JavaFX não respondeu ao criar a superfície de vídeo.");
                }
                if (failure[0] != null) {
                    throw failure[0];
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Criação da superfície JavaFX foi interrompida.", interrupted);
            } catch (IllegalStateException toolkitStopping) {
                throw new IllegalStateException("JavaFX não está disponível para receber o vídeo.", toolkitStopping);
            }
        }

        private static String safeMessage(Throwable error) {
            String message = error.getMessage();
            return message == null || message.isBlank() ? "sem detalhe" : message;
        }
    }
}
