package dev.lufi.ui;

import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.image.Image;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.Java2DFrameConverter;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import java.awt.image.BufferedImage;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Player local baseado no FFmpeg. Ele existe somente para containers que o
 * JavaFX Media nao decodifica de forma confiavel, como MKV com HEVC.
 */
final class FfmpegMediaPlayer implements AutoCloseable {
    interface Listener {
        void onReady();
        void onFrame(Image image);
        void onFinished();
        void onFailure(Throwable error);
    }

    private final Path source;
    private final Listener listener;
    private final Object monitor = new Object();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final AtomicBoolean completed = new AtomicBoolean();
    private final AtomicReference<Image> pendingImage = new AtomicReference<>();
    private final AtomicBoolean frameDeliveryQueued = new AtomicBoolean();
    /** Evita converter/renderizar todos os frames quando a UI ou a CPU não acompanham a origem. */
    private final FfmpegFrameDeliveryGate frameDeliveryGate = new FfmpegFrameDeliveryGate(30);
    private volatile boolean paused;
    private volatile double volume = .8d;
    private volatile Thread worker;
    private volatile FFmpegFrameGrabber activeGrabber;
    private volatile SourceDataLine audioLine;

    FfmpegMediaPlayer(Path source, Listener listener) {
        this.source = Objects.requireNonNull(source, "source");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        stopped.set(false);
        completed.set(false);
        paused = false;
        worker = Thread.startVirtualThread(this::decode);
    }

    void play() {
        if (completed.get() && !running.get()) {
            start();
            return;
        }
        synchronized (monitor) {
            paused = false;
            monitor.notifyAll();
        }
        SourceDataLine line = audioLine;
        if (line != null && line.isOpen()) {
            line.start();
        }
    }

    void pause() {
        synchronized (monitor) {
            paused = true;
        }
        SourceDataLine line = audioLine;
        if (line != null && line.isOpen()) {
            line.stop();
        }
    }

    void stop() {
        stopped.set(true);
        pendingImage.set(null);
        synchronized (monitor) {
            paused = false;
            monitor.notifyAll();
        }
        closeAudioLine();
        closeGrabber();
        Thread currentWorker = worker;
        if (currentWorker != null) {
            currentWorker.interrupt();
        }
    }

    void setVolume(double volume) {
        this.volume = Math.max(0d, Math.min(1d, volume));
        applyVolume();
    }

    @Override public void close() {
        stop();
    }

    private void decode() {
        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(source.toFile());
        Java2DFrameConverter converter = new Java2DFrameConverter();
        boolean ready = false;
        long firstTimestampMicros = -1L;
        long firstFrameNanos = 0L;
        try {
            grabber.setSampleMode(FrameGrabber.SampleMode.SHORT);
            activeGrabber = grabber;
            grabber.start();
            while (!stopped.get()) {
                awaitUnpaused();
                if (stopped.get()) {
                    break;
                }
                Frame frame = grabber.grab();
                if (frame == null) {
                    break;
                }
                long timestamp = Math.max(0L, grabber.getTimestamp());
                if (firstTimestampMicros < 0L) {
                    firstTimestampMicros = timestamp;
                    firstFrameNanos = System.nanoTime();
                    ready = true;
                    deliver(listener::onReady);
                }
                awaitPresentationTime(firstFrameNanos, timestamp - firstTimestampMicros);
                if (stopped.get()) {
                    break;
                }
                if (frame.samples != null) {
                    writeAudio(frame);
                }
                if (frame.image != null && shouldConvertFrame()) {
                    BufferedImage buffered = converter.convert(frame);
                    if (buffered != null) {
                        queueFrame(SwingFXUtils.toFXImage(buffered, null));
                    }
                }
            }
            if (!stopped.get()) {
                completed.set(true);
                deliver(listener::onFinished);
            }
        } catch (Throwable error) {
            if (!stopped.get()) {
                deliver(() -> listener.onFailure(error));
            }
        } finally {
            activeGrabber = null;
            closeAudioLine();
            try {
                converter.close();
            } catch (RuntimeException ignored) {
                // A conversao de quadros ja terminou; nenhum recurso do player fica em uso.
            }
            try {
                grabber.stop();
            } catch (Exception ignored) {
                // A origem pode ter sido fechada simultaneamente pelo usuario.
            }
            try {
                grabber.release();
            } catch (Exception ignored) {
                // O recurso nativo pode ja ter sido liberado em stop().
            }
            running.set(false);
        }
    }

    private void awaitUnpaused() throws InterruptedException {
        synchronized (monitor) {
            while (paused && !stopped.get()) {
                monitor.wait();
            }
        }
    }

    private void awaitPresentationTime(long baseNanos, long elapsedMicros) throws InterruptedException {
        long target = baseNanos + elapsedMicros * 1_000L;
        while (!stopped.get()) {
            awaitUnpaused();
            long remaining = target - System.nanoTime();
            if (remaining <= 0L) {
                return;
            }
            Thread.sleep(Math.min(remaining / 1_000_000L, 20L));
        }
    }

    private void writeAudio(Frame frame) {
        SourceDataLine line = ensureAudioLine(frame);
        if (line == null) {
            return;
        }
        byte[] pcm = pcm16LittleEndian(frame.samples, Math.max(1, frame.audioChannels));
        if (pcm.length > 0 && !stopped.get()) {
            line.write(pcm, 0, pcm.length);
        }
    }

    private SourceDataLine ensureAudioLine(Frame frame) {
        SourceDataLine existing = audioLine;
        if (existing != null) {
            return existing;
        }
        int sampleRate = frame.sampleRate > 0 ? frame.sampleRate : 48_000;
        int channels = Math.max(1, frame.audioChannels);
        AudioFormat format = new AudioFormat(sampleRate, 16, channels, true, false);
        try {
            SourceDataLine created = (SourceDataLine) AudioSystem.getLine(new DataLine.Info(SourceDataLine.class, format));
            created.open(format, Math.max(8_192, sampleRate * channels * 2 / 4));
            audioLine = created;
            applyVolume();
            if (!paused) {
                created.start();
            }
            return created;
        } catch (LineUnavailableException | IllegalArgumentException ignored) {
            // A ausencia de dispositivo de audio nao impede o MKV de ser exibido.
            return null;
        }
    }

    private static byte[] pcm16LittleEndian(Buffer[] samples, int channels) {
        if (samples.length == 0) {
            return new byte[0];
        }
        if (samples[0] instanceof ByteBuffer bytes) {
            ByteBuffer copy = bytes.duplicate();
            byte[] result = new byte[copy.remaining()];
            copy.get(result);
            return result;
        }
        if (!(samples[0] instanceof ShortBuffer)) {
            return new byte[0];
        }
        ShortBuffer[] planes = new ShortBuffer[samples.length];
        for (int index = 0; index < samples.length; index++) {
            if (!(samples[index] instanceof ShortBuffer plane)) {
                return new byte[0];
            }
            planes[index] = plane.duplicate();
        }
        if (planes.length == 1) {
            return shortsToBytes(planes[0]);
        }
        int frames = Integer.MAX_VALUE;
        for (int channel = 0; channel < Math.min(channels, planes.length); channel++) {
            frames = Math.min(frames, planes[channel].remaining());
        }
        if (frames == Integer.MAX_VALUE || frames <= 0) {
            return new byte[0];
        }
        int activeChannels = Math.min(channels, planes.length);
        byte[] result = new byte[frames * activeChannels * 2];
        int output = 0;
        for (int frame = 0; frame < frames; frame++) {
            for (int channel = 0; channel < activeChannels; channel++) {
                short value = planes[channel].get();
                result[output++] = (byte) value;
                result[output++] = (byte) (value >>> 8);
            }
        }
        return result;
    }

    private static byte[] shortsToBytes(ShortBuffer samples) {
        byte[] result = new byte[samples.remaining() * 2];
        int output = 0;
        while (samples.hasRemaining()) {
            short value = samples.get();
            result[output++] = (byte) value;
            result[output++] = (byte) (value >>> 8);
        }
        return result;
    }

    private void applyVolume() {
        SourceDataLine line = audioLine;
        if (line == null || !line.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
            return;
        }
        FloatControl gain = (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN);
        float decibels = volume <= .0001d ? gain.getMinimum() : (float) (20d * Math.log10(volume));
        gain.setValue(Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), decibels)));
    }

    private void queueFrame(Image image) {
        pendingImage.set(image);
        if (frameDeliveryQueued.compareAndSet(false, true)) {
            Platform.runLater(this::deliverLatestFrame);
        }
    }

    /**
     * A conversão BufferedImage → Image e a atualização do ImageView são as
     * partes mais caras do caminho visual. Elas nunca devem ocupar toda a fila
     * do JavaFX: controles e mouse precisam continuar tendo prioridade.
     */
    private boolean shouldConvertFrame() {
        if (frameDeliveryQueued.get() || pendingImage.get() != null) {
            return false;
        }
        return frameDeliveryGate.tryAcquire(System.nanoTime());
    }

    private void deliverLatestFrame() {
        Image image = pendingImage.getAndSet(null);
        if (image != null && !stopped.get()) {
            listener.onFrame(image);
        }
        frameDeliveryQueued.set(false);
    }

    private void deliver(Runnable callback) {
        Platform.runLater(callback);
    }

    private void closeGrabber() {
        FFmpegFrameGrabber grabber = activeGrabber;
        if (grabber == null) {
            return;
        }
        try {
            grabber.close();
        } catch (Exception ignored) {
            // A thread de decodificacao faz a liberacao final.
        }
    }

    private void closeAudioLine() {
        SourceDataLine line = audioLine;
        audioLine = null;
        if (line != null) {
            line.stop();
            line.flush();
            line.close();
        }
    }
}
