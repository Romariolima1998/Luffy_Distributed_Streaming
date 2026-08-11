package dev.lufi.ui;

import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;
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
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * Player local baseado no FFmpeg. Ele existe somente para containers que o
 * JavaFX Media nao decodifica de forma confiavel, como MKV com HEVC.
 */
final class FfmpegMediaPlayer implements AutoCloseable {
    /**
     * Limite de pré-leitura PCM antes do SourceDataLine. O valor anterior era
     * fixo em 384 KiB: em estéreo de 44,1 kHz isso permitia mais de dois
     * segundos de mídia à frente do dispositivo. Como o vídeo usa o relógio
     * de reprodução do áudio, esse avanço fazia o scheduler dormir segundos
     * por quadro. O limite agora é temporal e acompanha o formato ativo.
     */
    private static final int MAX_QUEUED_AUDIO_MILLIS = 120;
    /**
     * A leitura do FFmpeg nunca pode esperar por uma vaga visual: ela também é
     * responsável por manter o dispositivo de áudio alimentado. Esta janela
     * curta limita a memória e preserva os quadros mais recentes se a UI atrasar.
     */
    private static final int MAX_QUEUED_VIDEO_FRAMES = 6;
    private static final int AUDIO_LINE_BUFFER_MILLIS = 60;
    /**
     * Alguns demuxers entregam um pequeno grupo inicial de pacotes de áudio
     * antes do primeiro frame visual. Essa é uma latência de inicialização do
     * pipeline, não um atraso de decode que deva descartar o vídeo.
     */
    private static final long INITIAL_AUDIO_CLOCK_ALIGNMENT_MAX_MICROS = 500_000L;
    /** Pequena pré-leitura para descobrir uma única origem PTS entre áudio e vídeo. */
    private static final int MEDIA_ORIGIN_PROBE_MAX_FRAMES = 12;
    private static final PlaybackSyncPolicy SYNC_POLICY = PlaybackSyncPolicy.load();
    private static final HardResyncPolicy HARD_RESYNC_POLICY = HardResyncPolicy.from(SYNC_POLICY);
    private static final VideoFrameDropPolicy VIDEO_FRAME_DROP_POLICY = VideoFrameDropPolicy.from(SYNC_POLICY);
    private static final PresentationRatePolicy PRESENTATION_RATE_POLICY = PresentationRatePolicy.load();
    interface Listener {
        void onReady();
        void onFrame(Image image);
        void onFinished();
        void onFailure(Throwable error);
        default void onDiagnostic(String message) { }
    }

    private final Path source;
    private final Listener listener;
    private final Object monitor = new Object();
    private final Object audioMonitor = new Object();
    private final Object videoMonitor = new Object();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final AtomicBoolean completed = new AtomicBoolean();
    private final AtomicReference<FrameSurface> pendingSurface = new AtomicReference<>();
    private final AtomicBoolean frameDeliveryQueued = new AtomicBoolean();
    private final AtomicLong frameDeliveryPostedAtNanos = new AtomicLong(-1L);
    private final FfmpegPlaybackTelemetry telemetry = new FfmpegPlaybackTelemetry();
    private final Deque<QueuedAudio> pendingAudio = new ArrayDeque<>();
    /** Frames já convertidos aguardando somente a cadência de apresentação. */
    private final Deque<DecodedVideo> pendingVideo = new ArrayDeque<>();
    private volatile boolean paused;
    private volatile double volume = .8d;
    private volatile Thread worker;
    private volatile Thread videoWorker;
    private volatile FFmpegFrameGrabber activeGrabber;
    private volatile SourceDataLine audioLine;
    private volatile Thread audioWorker;
    private volatile int audioBytesPerSecond;
    private volatile int audioSampleRate;
    private volatile boolean audioTrackPresent;
    private volatile boolean audioOutputUnavailable;
    private volatile long audioClockAnchorPtsMicros = -1L;
    private volatile long audioClockAnchorFramePosition = -1L;
    /** Deslocamento de inicialização entre o relógio do dispositivo e o primeiro PTS de vídeo. */
    private volatile long audioClockVideoAlignmentMicros = Long.MIN_VALUE;
    private int queuedAudioBytes;
    private volatile boolean inputEnded;
    private volatile boolean videoPresentationEnded;
    /** A thread de decodificação abre somente uma ressincronização por episódio de desvio grande. */
    private HardResyncAction activeHardResyncAction = HardResyncAction.NONE;
    /** Buffers reutilizados pela thread de decodificação; evitam uma nova Image grande a cada frame. */
    private FrameSurface firstPresentationSurface;
    private FrameSurface secondPresentationSurface;
    private FrameSurface thirdPresentationSurface;
    private volatile FrameSurface displayedSurface;
    private volatile FrameSurface presentingSurface;
    /** A mesma imagem JavaFX é atualizada a cada quadro para não invalidar o ImageView inteiro. */
    private WritableImage presentationImage;

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
        inputEnded = false;
        videoPresentationEnded = false;
        activeHardResyncAction = HardResyncAction.NONE;
        audioClockVideoAlignmentMicros = Long.MIN_VALUE;
        synchronized (videoMonitor) {
            pendingVideo.clear();
        }
        videoWorker = Thread.startVirtualThread(this::drainVideo);
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
        synchronized (audioMonitor) {
            audioMonitor.notifyAll();
        }
        synchronized (videoMonitor) {
            videoMonitor.notifyAll();
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
        synchronized (audioMonitor) {
            audioMonitor.notifyAll();
        }
    }

    void stop() {
        stopped.set(true);
        pendingSurface.set(null);
        frameDeliveryPostedAtNanos.set(-1L);
        synchronized (monitor) {
            paused = false;
            monitor.notifyAll();
        }
        synchronized (audioMonitor) {
            audioMonitor.notifyAll();
        }
        synchronized (videoMonitor) {
            pendingVideo.clear();
            inputEnded = true;
            videoMonitor.notifyAll();
        }
        closeAudioLine();
        closeGrabber();
        Thread currentWorker = worker;
        if (currentWorker != null) {
            currentWorker.interrupt();
        }
        Thread currentVideoWorker = videoWorker;
        if (currentVideoWorker != null) {
            currentVideoWorker.interrupt();
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
        long mediaStartPtsMicros = -1L;
        long firstFrameNanos = 0L;
        long lastQueuedVideoTimestampMicros = Long.MIN_VALUE;
        Deque<GrabbedFrame> mediaOriginProbe = new ArrayDeque<>();
        try {
            grabber.setSampleMode(FrameGrabber.SampleMode.SHORT);
            activeGrabber = grabber;
            grabber.start();
            audioTrackPresent = grabber.getAudioChannels() > 0;
            audioOutputUnavailable = false;
            diagnostic(describeSource(grabber));
            diagnostic(SYNC_POLICY.describe());
            diagnostic(HARD_RESYNC_POLICY.describe());
            diagnostic(VIDEO_FRAME_DROP_POLICY.describe());
            diagnostic(PRESENTATION_RATE_POLICY.describe());
            MediaTimelineOrigin mediaTimeline = new MediaTimelineOrigin(grabber.getImageWidth() > 0,
                    grabber.getAudioChannels() > 0);
            while (!stopped.get()) {
                awaitUnpaused();
                if (stopped.get()) {
                    break;
                }
                long grabStartedAt = System.nanoTime();
                Frame frame = grabber.grab();
                long grabReturnedAt = System.nanoTime();
                long grabNanos = grabReturnedAt - grabStartedAt;
                telemetry.recordGrab(grabNanos, frame != null && frame.image != null, frame != null && frame.samples != null);
                if (frame == null) {
                    break;
                }
                long timestamp = frameTimestampMicros(frame, grabber);
                if (timestamp < 0L) {
                    continue;
                }
                if (mediaStartPtsMicros < 0L) {
                    mediaOriginProbe.addLast(new GrabbedFrame(frame.clone(), timestamp, grabReturnedAt, grabNanos));
                    mediaTimeline.observe(frame, timestamp);
                    if (!mediaTimeline.isResolved() && mediaOriginProbe.size() < MEDIA_ORIGIN_PROBE_MAX_FRAMES) {
                        continue;
                    }
                    mediaStartPtsMicros = mediaTimeline.resolve();
                    firstFrameNanos = System.nanoTime();
                    ready = true;
                    deliver(listener::onReady);
                    diagnostic(mediaTimeline.describe(mediaStartPtsMicros, mediaOriginProbe.size()));
                    while (!mediaOriginProbe.isEmpty() && !stopped.get()) {
                        GrabbedFrame probed = mediaOriginProbe.removeFirst();
                        lastQueuedVideoTimestampMicros = processFrame(converter, probed.frame(),
                                probed.timestampMicros(), mediaStartPtsMicros, firstFrameNanos,
                                probed.grabReturnedAtNanos(), probed.grabNanos(), lastQueuedVideoTimestampMicros);
                    }
                    continue;
                }
                lastQueuedVideoTimestampMicros = processFrame(converter, frame, timestamp, mediaStartPtsMicros,
                        firstFrameNanos, grabReturnedAt, grabNanos, lastQueuedVideoTimestampMicros);
            }
            if (!stopped.get()) {
                signalVideoInputEnded();
                awaitPlaybackDrain();
                completed.set(true);
                deliver(listener::onFinished);
            }
        } catch (Throwable error) {
            if (!stopped.get()) {
                diagnostic("PLAYER FAILURE: type=" + error.getClass().getSimpleName() + "; detail=" + concise(error));
                deliver(() -> listener.onFailure(error));
            }
        } finally {
            signalVideoInputEnded();
            Thread currentVideoWorker = videoWorker;
            if (currentVideoWorker != null && !videoPresentationEnded) {
                currentVideoWorker.interrupt();
            }
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
            pendingSurface.set(null);
            frameDeliveryPostedAtNanos.set(-1L);
            firstPresentationSurface = null;
            secondPresentationSurface = null;
            thirdPresentationSurface = null;
            displayedSurface = null;
            presentingSurface = null;
            presentationImage = null;
            videoWorker = null;
            running.set(false);
            diagnostic("PLAYER FINAL: " + telemetry.finalReport(System.nanoTime()));
        }
    }

    private long processFrame(Java2DFrameConverter converter, Frame frame, long timestampMicros,
            long mediaStartPtsMicros, long firstFrameNanos, long grabReturnedAtNanos, long grabNanos,
            long lastQueuedVideoTimestampMicros) throws InterruptedException {
        long relativeTimestampMicros = timestampMicros - mediaStartPtsMicros;
        long schedulerTargetNanos = firstFrameNanos + relativeTimestampMicros * 1_000L;
        long timingReferenceNanos = Math.max(firstFrameNanos, grabReturnedAtNanos);
        telemetry.recordMediaClock(firstFrameNanos, timingReferenceNanos, relativeTimestampMicros);
        if (frame.image != null) {
            telemetry.recordVideoPts(timestampMicros, relativeTimestampMicros);
            telemetry.recordLatenessAtGrabReturn(Math.max(0L, timingReferenceNanos - schedulerTargetNanos), grabNanos);
        }

        // O PCM não passa pelo scheduler visual: ele é entregue à fila do SourceDataLine imediatamente.
        if (frame.samples != null) {
            enqueueAudio(frame, relativeTimestampMicros);
        }
        if (frame.image == null || stopped.get()) {
            return lastQueuedVideoTimestampMicros;
        }

        // A seleção pelo PTS acontece antes da conversão, mas a espera pelo
        // relógio mestre pertence exclusivamente à thread de apresentação.
        // Portanto uma imagem futura nunca impede o próximo grab() de entregar PCM.
        if (PRESENTATION_RATE_POLICY.limits(timestampMicros, lastQueuedVideoTimestampMicros)) {
            telemetry.recordRateLimitedFrame();
            return lastQueuedVideoTimestampMicros;
        }
        long conversionStartedAt = System.nanoTime();
        telemetry.recordLatenessBeforeConvert(latenessAgainstTarget(schedulerTargetNanos, conversionStartedAt));
        BufferedImage buffered = converter.convert(frame);
        long conversionFinishedAt = System.nanoTime();
        telemetry.recordLatenessAfterConvert(latenessAgainstTarget(schedulerTargetNanos, conversionFinishedAt));
        if (buffered == null) {
            telemetry.recordUiSkippedFrame();
            return lastQueuedVideoTimestampMicros;
        }
        telemetry.recordLatenessBeforeQueue(latenessAgainstTarget(schedulerTargetNanos, conversionFinishedAt));
        enqueueVideo(new DecodedVideo(buffered, timestampMicros, relativeTimestampMicros, firstFrameNanos,
                grabReturnedAtNanos, conversionStartedAt, conversionFinishedAt));
        return timestampMicros;
    }

    /**
     * Entrega o quadro convertido a uma fila curta sem bloquear a leitura do
     * demuxer. Quando a apresentação ainda estiver aguardando o áudio, a
     * imagem mais antiga é descartada para manter baixa a latência visual;
     * o PCM nunca é descartado por esta fila.
     */
    private void enqueueVideo(DecodedVideo decoded) throws InterruptedException {
        synchronized (videoMonitor) {
            if (stopped.get() || inputEnded) {
                return;
            }
            while (!stopped.get() && pendingVideo.size() >= MAX_QUEUED_VIDEO_FRAMES
                    && (!audioTrackPresent || audioOutputUnavailable)) {
                // Sem um relógio de áudio, a própria fila visual faz o pacing
                // pelo wall clock, sem permitir que a origem avance até o fim.
                videoMonitor.wait(10L);
            }
            if (stopped.get() || inputEnded) {
                return;
            }
            if (pendingVideo.size() >= MAX_QUEUED_VIDEO_FRAMES) {
                pendingVideo.removeFirst();
                telemetry.recordVideoQueueOverflowDrop();
            }
            pendingVideo.addLast(decoded);
            videoMonitor.notifyAll();
        }
    }

    /**
     * Esta é a única thread que aguarda o relógio mestre para vídeo. A
     * thread decode() continua chamando grab() e alimentando o SourceDataLine
     * enquanto esta espera acontece.
     */
    private void drainVideo() {
        try {
            while (!stopped.get()) {
                DecodedVideo decoded = awaitNextVideo();
                if (decoded == null) {
                    return;
                }
                presentVideo(decoded);
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } finally {
            synchronized (videoMonitor) {
                videoPresentationEnded = true;
                videoMonitor.notifyAll();
            }
        }
    }

    private DecodedVideo awaitNextVideo() throws InterruptedException {
        synchronized (videoMonitor) {
            while (!stopped.get() && pendingVideo.isEmpty() && !inputEnded) {
                videoMonitor.wait();
            }
            DecodedVideo decoded = pendingVideo.pollFirst();
            if (decoded != null) {
                videoMonitor.notifyAll();
            }
            return decoded;
        }
    }

    private void presentVideo(DecodedVideo decoded) throws InterruptedException {
        PresentationTiming presentation = awaitVideoPresentationTime(decoded.firstFrameNanos(),
                decoded.videoPtsRelativeMicros());
        if (stopped.get()) {
            return;
        }
        long schedulerTargetNanos = presentation.targetNanos();
        telemetry.recordDecodedVideo(decoded.videoPtsMicros(), presentation.latenessNanos());
        telemetry.recordSyncDecision(presentation.masterClock(), presentation.syncAction(),
                presentation.decision().dropsVideo());
        applyHardResync(presentation.hardResyncAction(), presentation.syncDeltaMicros());
        if (presentation.decision().dropsVideo()) {
            // Nenhuma rajada para recuperar atraso: um quadro muito antigo sai
            // antes de tocar a fila JavaFX, sem afetar o áudio.
            telemetry.recordDroppedForLateness();
            if (presentation.decision().isHardResyncDrop()) {
                telemetry.recordHardResyncDrop();
            }
            reportPlaybackFlow();
            return;
        }
        if (queueFrame(decoded.image(), decoded.videoPtsMicros(), decoded.videoPtsRelativeMicros(),
                schedulerTargetNanos, decoded.grabReturnedAtNanos())) {
            telemetry.recordSubmittedFrame(decoded.convertedAtNanos() - decoded.conversionStartedAtNanos());
        } else {
            telemetry.recordSurfaceSkippedFrame();
        }
        reportPlaybackFlow();
    }

    private void signalVideoInputEnded() {
        synchronized (videoMonitor) {
            inputEnded = true;
            videoMonitor.notifyAll();
        }
    }

    /** Espera somente no encerramento natural; durante a reprodução o demux nunca espera o vídeo. */
    private void awaitPlaybackDrain() throws InterruptedException {
        while (!stopped.get()) {
            if (videoPresentationEnded && pendingSurface.get() == null && !frameDeliveryQueued.get()
                    && isAudioOutputDrained()) {
                return;
            }
            LockSupport.parkNanos(5_000_000L);
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
        }
    }

    private boolean isAudioOutputDrained() {
        SourceDataLine line;
        synchronized (audioMonitor) {
            if (!pendingAudio.isEmpty() || queuedAudioBytes > 0) {
                return false;
            }
            line = audioLine;
        }
        if (line == null || !line.isOpen()) {
            return true;
        }
        try {
            return line.available() >= line.getBufferSize();
        } catch (IllegalStateException ignored) {
            return true;
        }
    }

    private static long frameTimestampMicros(Frame frame, FFmpegFrameGrabber grabber) {
        long timestamp = frame.timestamp >= 0L ? frame.timestamp : grabber.getTimestamp();
        return timestamp >= 0L ? timestamp : -1L;
    }

    private void applyHardResync(HardResyncAction action, long videoVsMasterClockMicros) {
        if (action == activeHardResyncAction) {
            return;
        }
        if (action == HardResyncAction.NONE) {
            if (activeHardResyncAction != HardResyncAction.NONE) {
                telemetry.recordHardResyncRecovery();
            }
            activeHardResyncAction = HardResyncAction.NONE;
            return;
        }
        if (action.dropsVideo()) {
            // Do not present a stale visual queue while the decoder advances to the audio clock.
            pendingSurface.set(null);
        }
        activeHardResyncAction = action;
        telemetry.recordHardResync(videoVsMasterClockMicros, action.dropsVideo());
    }

    /**
     * Mantém uma única origem temporal. Os primeiros PTS válidos de cada
     * stream são observados antes de iniciar o playback; nunca se zera áudio
     * e vídeo de forma independente.
     */
    private static final class MediaTimelineOrigin {
        private final boolean expectsVideo;
        private final boolean expectsAudio;
        private long firstVideoPtsMicros = Long.MAX_VALUE;
        private long firstAudioPtsMicros = Long.MAX_VALUE;

        private MediaTimelineOrigin(boolean expectsVideo, boolean expectsAudio) {
            this.expectsVideo = expectsVideo;
            this.expectsAudio = expectsAudio;
        }

        private void observe(Frame frame, long timestampMicros) {
            if (frame.image != null) {
                firstVideoPtsMicros = Math.min(firstVideoPtsMicros, timestampMicros);
            }
            if (frame.samples != null) {
                firstAudioPtsMicros = Math.min(firstAudioPtsMicros, timestampMicros);
            }
        }

        private boolean isResolved() {
            return (!expectsVideo || firstVideoPtsMicros != Long.MAX_VALUE)
                    && (!expectsAudio || firstAudioPtsMicros != Long.MAX_VALUE)
                    && hasObservedTimestamp();
        }

        private long resolve() {
            if (!hasObservedTimestamp()) {
                throw new IllegalStateException("Nenhum PTS válido foi fornecido pelo FFmpeg.");
            }
            return Math.min(firstVideoPtsMicros, firstAudioPtsMicros);
        }

        private boolean hasObservedTimestamp() {
            return firstVideoPtsMicros != Long.MAX_VALUE || firstAudioPtsMicros != Long.MAX_VALUE;
        }

        private String describe(long mediaStartPtsMicros, int probeFrames) {
            return String.format(Locale.ROOT,
                    "PLAYER TIMELINE: mediaStartPtsMs=%.3f; firstVideoPtsMs=%s; firstAudioPtsMs=%s; probeFrames=%d; mode=%s",
                    mediaStartPtsMicros / 1_000d, describePts(firstVideoPtsMicros), describePts(firstAudioPtsMicros),
                    probeFrames, isResolved() ? "all-expected-streams" : "probe-limit-fallback");
        }

        private static String describePts(long ptsMicros) {
            return ptsMicros == Long.MAX_VALUE ? "absent" : String.format(Locale.ROOT, "%.3f", ptsMicros / 1_000d);
        }
    }

    private record GrabbedFrame(Frame frame, long timestampMicros, long grabReturnedAtNanos, long grabNanos) { }

    private void awaitUnpaused() throws InterruptedException {
        synchronized (monitor) {
            while (paused && !stopped.get()) {
                monitor.wait();
            }
        }
    }

    private PresentationTiming awaitVideoPresentationTime(long baseNanos, long videoPtsRelativeMicros)
            throws InterruptedException {
        long rawAudioClockMicros = audioClockRelativeMicros();
        long now = System.nanoTime();
        if (rawAudioClockMicros >= 0L) {
            long audioClockMicros = alignedAudioClockForVideo(rawAudioClockMicros, videoPtsRelativeMicros);
            long deltaMicros = videoPtsRelativeMicros - audioClockMicros;
            long target = now + deltaMicros * 1_000L;
            HardResyncAction hardResyncAction = HARD_RESYNC_POLICY.forAudioClockDelta(deltaMicros, activeHardResyncAction);
            VideoFrameDecision decision = hardResyncAction.dropsVideo()
                    ? VideoFrameDecision.DROP_FOR_HARD_RESYNC
                    : VIDEO_FRAME_DROP_POLICY.forAudioClockDelta(deltaMicros);
            if (decision.dropsVideo()) {
                telemetry.recordScheduler(baseNanos, target, now, now);
                return new PresentationTiming(target, -deltaMicros * 1_000L, decision, hardResyncAction,
                        FfmpegPlaybackTelemetry.MasterClock.AUDIO, syncAction(hardResyncAction, decision, false), deltaMicros);
            }
            if (deltaMicros <= SYNC_POLICY.toleranceMicros()) {
                telemetry.recordScheduler(baseNanos, now, now, now);
                return new PresentationTiming(now, Math.max(0L, -deltaMicros * 1_000L),
                        VideoFrameDecision.PRESENT, hardResyncAction, FfmpegPlaybackTelemetry.MasterClock.AUDIO,
                        syncAction(hardResyncAction, VideoFrameDecision.PRESENT, false), deltaMicros);
            }
            return new PresentationTiming(target, awaitPresentationTarget(baseNanos, target),
                    VideoFrameDecision.PRESENT, hardResyncAction, FfmpegPlaybackTelemetry.MasterClock.AUDIO,
                    syncAction(hardResyncAction, VideoFrameDecision.PRESENT, true), deltaMicros);
        }
        long target = baseNanos + videoPtsRelativeMicros * 1_000L;
        long latenessNanos = Math.max(0L, now - target);
        long latenessMicros = latenessNanos / 1_000L;
        HardResyncAction hardResyncAction = HARD_RESYNC_POLICY.forWallClockLateness(latenessMicros, activeHardResyncAction);
        VideoFrameDecision decision = hardResyncAction.dropsVideo()
                ? VideoFrameDecision.DROP_FOR_HARD_RESYNC
                : VIDEO_FRAME_DROP_POLICY.forWallClockLateness(latenessMicros);
        if (decision.dropsVideo()) {
            telemetry.recordScheduler(baseNanos, target, now, now);
            return new PresentationTiming(target, latenessNanos, decision, hardResyncAction,
                    FfmpegPlaybackTelemetry.MasterClock.WALL, syncAction(hardResyncAction, decision, false), -latenessMicros);
        }
        return new PresentationTiming(target, awaitPresentationTarget(baseNanos, target),
                VideoFrameDecision.PRESENT, hardResyncAction, FfmpegPlaybackTelemetry.MasterClock.WALL,
                syncAction(hardResyncAction, VideoFrameDecision.PRESENT, target > now), -latenessMicros);
    }

    /**
     * Alinha uma única vez o início da apresentação ao primeiro vídeo. Sem
     * isso, um MKV cujo demuxer entrega áudio antes da primeira imagem pode
     * manter o áudio ~250 ms à frente por toda a execução e acionar a política
     * de descarte para cada quadro, apesar de o decoder estar rápido.
     *
     * Os PTS não são reescritos: o ajuste vale apenas para comparar o relógio
     * físico do dispositivo de áudio com a linha de apresentação do vídeo.
     */
    private long alignedAudioClockForVideo(long rawAudioClockMicros, long videoPtsRelativeMicros) {
        long alignment = audioClockVideoAlignmentMicros;
        if (alignment == Long.MIN_VALUE) {
            long audioLeadMicros = rawAudioClockMicros - videoPtsRelativeMicros;
            boolean isStartupLead = videoPtsRelativeMicros <= 1_000_000L
                    && audioLeadMicros > SYNC_POLICY.toleranceMicros()
                    && audioLeadMicros <= INITIAL_AUDIO_CLOCK_ALIGNMENT_MAX_MICROS;
            alignment = isStartupLead ? audioLeadMicros : 0L;
            audioClockVideoAlignmentMicros = alignment;
            if (isStartupLead) {
                activeHardResyncAction = HardResyncAction.NONE;
                diagnostic(String.format(Locale.ROOT,
                        "PLAYER A/V CLOCK ALIGNMENT: startupAudioLeadMs=%.2f; videoPtsMs=%.2f; "
                                + "rawAudioClockMs=%.2f; schedulerAudioOffsetMs=%.2f",
                        audioLeadMicros / 1_000d, videoPtsRelativeMicros / 1_000d,
                        rawAudioClockMicros / 1_000d, alignment / 1_000d));
            }
        }
        return rawAudioClockMicros - alignment;
    }

    private static FfmpegPlaybackTelemetry.SyncAction syncAction(HardResyncAction hardResyncAction,
            VideoFrameDecision decision, boolean waitsForClock) {
        if (hardResyncAction != HardResyncAction.NONE) {
            return FfmpegPlaybackTelemetry.SyncAction.RESYNC;
        }
        if (decision.dropsVideo()) {
            return FfmpegPlaybackTelemetry.SyncAction.DROP;
        }
        return waitsForClock ? FfmpegPlaybackTelemetry.SyncAction.WAIT
                : FfmpegPlaybackTelemetry.SyncAction.PRESENT;
    }

    private long awaitPresentationTarget(long baseNanos, long target) throws InterruptedException {
        long enteredAt = System.nanoTime();
        while (!stopped.get()) {
            awaitUnpaused();
            long now = System.nanoTime();
            long remaining = target - now;
            if (remaining <= 0L) {
                telemetry.recordScheduler(baseNanos, target, enteredAt, now);
                return -remaining;
            }
            if (remaining > 2_000_000L) {
                LockSupport.parkNanos(Math.min(20_000_000L, remaining - 1_000_000L));
                if (Thread.interrupted()) throw new InterruptedException();
            } else {
                Thread.onSpinWait();
            }
        }
        telemetry.recordScheduler(baseNanos, target, enteredAt, System.nanoTime());
        return 0L;
    }

    private long audioClockRelativeMicros() {
        SourceDataLine line = audioLine;
        long anchorPts = audioClockAnchorPtsMicros;
        long anchorFramePosition = audioClockAnchorFramePosition;
        int sampleRate = audioSampleRate;
        if (line == null || !line.isOpen() || anchorPts < 0L || anchorFramePosition < 0L || sampleRate <= 0) {
            return -1L;
        }
        try {
            long framePosition = line.getLongFramePosition();
            if (framePosition < anchorFramePosition) {
                return -1L;
            }
            return anchorPts + (framePosition - anchorFramePosition) * 1_000_000L / sampleRate;
        } catch (IllegalStateException ignored) {
            return -1L;
        }
    }

    /**
     * Copia o PCM para uma fila curta. SourceDataLine.write() pode esperar por
     * dezenas de milissegundos; ele roda em uma thread própria para não reduzir
     * a taxa de vídeo.
     */
    private void enqueueAudio(Frame frame, long relativeTimestampMicros) throws InterruptedException {
        SourceDataLine line = ensureAudioLine(frame);
        if (line == null) {
            return;
        }
        byte[] pcm = pcm16LittleEndian(frame.samples, Math.max(1, frame.audioChannels));
        if (pcm.length == 0 || stopped.get()) {
            return;
        }
        synchronized (audioMonitor) {
            if (stopped.get() || audioLine != line) {
                return;
            }
            int queuedAudioLimit = maxQueuedAudioBytes();
            while (!stopped.get() && audioLine == line && queuedAudioBytes + pcm.length > queuedAudioLimit) {
                audioMonitor.wait(10L);
            }
            if (stopped.get() || audioLine != line) {
                return;
            }
            pendingAudio.addLast(new QueuedAudio(pcm, System.nanoTime(), relativeTimestampMicros));
            queuedAudioBytes += pcm.length;
            audioMonitor.notifyAll();
        }
    }

    /**
     * Converte a janela temporal de pré-leitura para o formato PCM atual.
     * Mantemos um piso de 16 KiB para caber alguns pacotes pequenos e evitar
     * wake-ups excessivos em faixas de baixa taxa.
     */
    private int maxQueuedAudioBytes() {
        int bytesPerSecond = audioBytesPerSecond;
        if (bytesPerSecond <= 0) {
            return 16 * 1024;
        }
        long requested = (long) bytesPerSecond * MAX_QUEUED_AUDIO_MILLIS / 1_000L;
        return (int) Math.min(Integer.MAX_VALUE, Math.max(16 * 1024L, requested));
    }

    private SourceDataLine ensureAudioLine(Frame frame) {
        synchronized (audioMonitor) {
            if (stopped.get()) return null;
            SourceDataLine existing = audioLine;
            if (existing != null) return existing;
            int sampleRate = frame.sampleRate > 0 ? frame.sampleRate : 48_000;
            int channels = Math.max(1, frame.audioChannels);
            AudioFormat format = new AudioFormat(sampleRate, 16, channels, true, false);
            try {
                SourceDataLine created = (SourceDataLine) AudioSystem.getLine(new DataLine.Info(SourceDataLine.class, format));
                created.open(format, Math.max(8_192, sampleRate * channels * 2 * AUDIO_LINE_BUFFER_MILLIS / 1_000));
                // stop() pode ter sido chamado enquanto a linha de áudio era aberta.
                if (stopped.get()) {
                    created.close();
                    return null;
                }
                audioLine = created;
                audioBytesPerSecond = sampleRate * channels * 2;
                audioSampleRate = sampleRate;
                applyVolume();
                if (!paused && !stopped.get()) created.start();
                startAudioWorker(created);
                return created;
            } catch (LineUnavailableException | IllegalArgumentException ignored) {
                // A ausencia de dispositivo de audio nao impede o MKV de ser exibido.
                audioOutputUnavailable = true;
                return null;
            }
        }
    }

    private void startAudioWorker(SourceDataLine line) {
        if (audioWorker != null) {
            return;
        }
        audioWorker = Thread.startVirtualThread(() -> drainAudio(line));
    }

    private void drainAudio(SourceDataLine line) {
        try {
            while (!stopped.get()) {
                QueuedAudio queued;
                synchronized (audioMonitor) {
                    while (!stopped.get() && audioLine == line && (paused || pendingAudio.isEmpty())) {
                        audioMonitor.wait();
                    }
                    if (stopped.get() || audioLine != line) {
                        return;
                    }
                    queued = pendingAudio.removeFirst();
                    queuedAudioBytes -= queued.pcm().length;
                    audioMonitor.notifyAll();
                }
                telemetry.recordAudioQueueWait(System.nanoTime() - queued.queuedAtNanos());
                establishAudioClockAnchor(line, queued.relativeTimestampMicros());
                byte[] pcm = queued.pcm();
                int offset = 0;
                while (offset < pcm.length && !stopped.get() && audioLine == line) {
                    long startedAt = System.nanoTime();
                    int written = line.write(pcm, offset, pcm.length - offset);
                    telemetry.recordAudioWrite(written, System.nanoTime() - startedAt);
                    if (written <= 0) {
                        break;
                    }
                    offset += written;
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } finally {
            synchronized (audioMonitor) {
                if (audioWorker == Thread.currentThread()) {
                    audioWorker = null;
                }
            }
        }
    }

    private void establishAudioClockAnchor(SourceDataLine line, long relativeTimestampMicros) {
        if (audioClockAnchorPtsMicros >= 0L || audioSampleRate <= 0) {
            return;
        }
        try {
            long framePosition = line.getLongFramePosition();
            audioClockAnchorFramePosition = framePosition;
            audioClockAnchorPtsMicros = relativeTimestampMicros;
            telemetry.recordAudioClockAnchor(relativeTimestampMicros);
        } catch (IllegalStateException ignored) {
            // O player usa o relÃ³gio PTS/wall clock atÃ© a linha de Ã¡udio ficar disponÃ­vel.
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

    private boolean queueFrame(BufferedImage decoded, long videoPtsMicros, long videoPtsRelativeMicros,
            long schedulerTargetNanos, long decodedAtNanos) {
        FrameSurface surface = presentationSurface(decoded);
        if (surface == null) return false;
        long createdAt = System.nanoTime();
        surface.prepare(videoPtsMicros, videoPtsRelativeMicros, schedulerTargetNanos, decodedAtNanos, createdAt);
        long queuedAt = System.nanoTime();
        surface.markQueued(queuedAt);
        FrameSurface replaced = pendingSurface.getAndSet(surface);
        telemetry.recordQueuedSurface(surface.decodedAtNanos, surface.surfaceCreatedAtNanos,
                surface.surfaceQueuedAtNanos, surface.videoPtsMicros, surface.videoPtsRelativeMicros,
                presentingSurface == null ? 1 : 2, replaced != null);
        scheduleFrameDeliveryIfNeeded();
        return true;
    }

    /** Converte para uma superfície JavaFX reutilizável e reduz a imagem para o tamanho máximo útil da interface. */
    private FrameSurface presentationSurface(BufferedImage decoded) {
        FfmpegRenderSize size = FfmpegRenderSize.fit(decoded.getWidth(), decoded.getHeight());
        ensurePresentationSurfaces(size);
        FrameSurface pending = pendingSurface.get();
        FrameSurface surface = availableSurface(firstPresentationSurface, pending);
        if (surface == null) surface = availableSurface(secondPresentationSurface, pending);
        if (surface == null) surface = availableSurface(thirdPresentationSurface, pending);
        if (surface == null) return null;

        Graphics2D graphics = surface.pixels.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(decoded, 0, 0, size.width(), size.height(), null);
        } finally {
            graphics.dispose();
        }
        return surface;
    }

    private void ensurePresentationSurfaces(FfmpegRenderSize size) {
        if (firstPresentationSurface != null && firstPresentationSurface.matches(size)) return;
        firstPresentationSurface = new FrameSurface(size);
        secondPresentationSurface = new FrameSurface(size);
        thirdPresentationSurface = new FrameSurface(size);
        displayedSurface = null;
        presentingSurface = null;
        presentationImage = null;
        pendingSurface.set(null);
    }

    private FrameSurface availableSurface(FrameSurface candidate, FrameSurface pending) {
        if (candidate == null || candidate == displayedSurface || candidate == presentingSurface || candidate == pending) return null;
        return candidate;
    }

    /**
     * A conversão BufferedImage → Image e a atualização do ImageView são as
     * partes mais caras do caminho visual. Três superfícies permitem manter
     * um quadro exibido, outro em conversão na UI e outro pendente, sem
     * descartar alternadamente uma origem de 30 FPS.
     */
    private boolean shouldConvertFrame() {
        // A primeira imagem é quem define o tamanho das superfícies reutilizáveis.
        // Antes dela, não há candidato para availableSurface(), mas a conversão
        // precisa ser permitida para inicializar a apresentação.
        if (firstPresentationSurface == null) {
            return true;
        }
        FrameSurface pending = pendingSurface.get();
        return availableSurface(firstPresentationSurface, pending) != null
                || availableSurface(secondPresentationSurface, pending) != null
                || availableSurface(thirdPresentationSurface, pending) != null;
    }

    private void deliverLatestFrame() {
        long callbackStartedAt = System.nanoTime();
        long postedAt = frameDeliveryPostedAtNanos.getAndSet(-1L);
        if (postedAt > 0L) {
            telemetry.recordRunLaterDelay(callbackStartedAt - postedAt);
        }
        FrameSurface surface = pendingSurface.getAndSet(null);
        if (surface != null && !stopped.get()) {
            long startedAt = callbackStartedAt;
            surface.markUiCallbackStarted(startedAt);
            telemetry.recordUiSubmit(surface.surfaceQueuedAtNanos, startedAt);
            telemetry.recordLatenessBeforePresent(latenessAgainstTarget(surface.schedulerTargetNanos, startedAt));
            presentingSurface = surface;
            long toFxImageStartedAt = System.nanoTime();
            presentationImage = SwingFXUtils.toFXImage(surface.pixels, presentationImage);
            long toFxImageFinishedAt = System.nanoTime();
            displayedSurface = surface;
            presentingSurface = null;
            long listenerStartedAt = System.nanoTime();
            listener.onFrame(presentationImage);
            long presentedAt = System.nanoTime();
            surface.markPresented(presentedAt);
            telemetry.recordPresentedFrame(presentedAt - startedAt);
            telemetry.recordUiPresentation(toFxImageFinishedAt - toFxImageStartedAt, presentedAt - listenerStartedAt,
                    presentedAt - startedAt);
            telemetry.recordPresentedSurface(surface.decodedAtNanos, surface.surfaceQueuedAtNanos, startedAt, presentedAt);
            telemetry.recordLatenessAfterPresent(latenessAgainstTarget(surface.schedulerTargetNanos, presentedAt));
        }
        frameDeliveryQueued.set(false);
        if (pendingSurface.get() != null) {
            scheduleFrameDeliveryIfNeeded();
        }
    }

    private void scheduleFrameDeliveryIfNeeded() {
        if (frameDeliveryQueued.compareAndSet(false, true)) {
            frameDeliveryPostedAtNanos.set(System.nanoTime());
            Platform.runLater(this::deliverLatestFrame);
        }
    }

    private static final class FrameSurface {
        private final BufferedImage pixels;
        private long videoPtsMicros;
        private long videoPtsRelativeMicros;
        private long schedulerTargetNanos;
        private long decodedAtNanos;
        private long surfaceCreatedAtNanos;
        private long surfaceQueuedAtNanos;
        private long uiCallbackStartedAtNanos;
        private long surfacePresentedAtNanos;

        private FrameSurface(FfmpegRenderSize size) {
            pixels = new BufferedImage(size.width(), size.height(), BufferedImage.TYPE_INT_ARGB);
        }

        private boolean matches(FfmpegRenderSize size) {
            return pixels.getWidth() == size.width() && pixels.getHeight() == size.height();
        }

        private void prepare(long videoPtsMicros, long videoPtsRelativeMicros, long schedulerTargetNanos,
                long decodedAtNanos, long createdAtNanos) {
            this.videoPtsMicros = videoPtsMicros;
            this.videoPtsRelativeMicros = videoPtsRelativeMicros;
            this.schedulerTargetNanos = schedulerTargetNanos;
            this.decodedAtNanos = decodedAtNanos;
            this.surfaceCreatedAtNanos = createdAtNanos;
            this.surfaceQueuedAtNanos = 0L;
            this.uiCallbackStartedAtNanos = 0L;
            this.surfacePresentedAtNanos = 0L;
        }

        private void markQueued(long queuedAtNanos) {
            surfaceQueuedAtNanos = queuedAtNanos;
        }

        private void markPresented(long presentedAtNanos) {
            surfacePresentedAtNanos = presentedAtNanos;
        }

        private void markUiCallbackStarted(long callbackStartedAtNanos) {
            uiCallbackStartedAtNanos = callbackStartedAtNanos;
        }
    }

    private void deliver(Runnable callback) {
        Platform.runLater(callback);
    }

    private void reportPlaybackFlow() {
        long now = System.nanoTime();
        recordVisualQueueState(now);
        recordAudioState();
        String report = telemetry.reportIfDue(now);
        if (report != null) diagnostic("PLAYER FLOW: " + report);
    }

    private void recordVisualQueueState(long nowNanos) {
        FrameSurface pending = pendingSurface.get();
        FrameSurface presenting = presentingSurface;
        int queueSize;
        long oldestPts = -1L;
        long newestPts = -1L;
        synchronized (videoMonitor) {
            queueSize = pendingVideo.size();
            DecodedVideo oldestDecoded = pendingVideo.peekFirst();
            DecodedVideo newestDecoded = pendingVideo.peekLast();
            if (oldestDecoded != null) {
                oldestPts = oldestDecoded.videoPtsMicros();
                newestPts = newestDecoded.videoPtsMicros();
            }
        }
        if (presenting != null) {
            queueSize++;
            oldestPts = oldestPts < 0L ? presenting.videoPtsMicros : Math.min(oldestPts, presenting.videoPtsMicros);
            newestPts = newestPts < 0L ? presenting.videoPtsMicros : Math.max(newestPts, presenting.videoPtsMicros);
        }
        if (pending != null) {
            queueSize++;
            oldestPts = oldestPts < 0L ? pending.videoPtsMicros : Math.min(oldestPts, pending.videoPtsMicros);
            newestPts = newestPts < 0L ? pending.videoPtsMicros : Math.max(newestPts, pending.videoPtsMicros);
        }
        long pendingAge = pending == null || pending.surfaceQueuedAtNanos <= 0L ? -1L : nowNanos - pending.surfaceQueuedAtNanos;
        telemetry.recordVisualQueueState(queueSize, oldestPts, newestPts, pendingAge, frameDeliveryQueued.get());
    }

    private void recordAudioState() {
        SourceDataLine line;
        int queuedBytes;
        int bytesPerSecond;
        int pendingChunks;
        synchronized (audioMonitor) {
            line = audioLine;
            queuedBytes = queuedAudioBytes;
            bytesPerSecond = audioBytesPerSecond;
            pendingChunks = pendingAudio.size();
        }
        int available = -1;
        int bufferSize = -1;
        long playbackPositionMicros = -1L;
        long longFramePosition = -1L;
        if (line != null && line.isOpen()) {
            try {
                available = line.available();
                bufferSize = line.getBufferSize();
                playbackPositionMicros = line.getMicrosecondPosition();
                longFramePosition = line.getLongFramePosition();
            } catch (IllegalStateException ignored) {
                // A linha pode fechar entre o snapshot e a leitura dos contadores.
            }
        }
        telemetry.recordAudioState(pendingChunks, queuedBytes, bytesPerSecond, available, bufferSize,
                playbackPositionMicros, longFramePosition, audioClockRelativeMicros());
    }

    private static long latenessAgainstTarget(long targetNanos, long nowNanos) {
        return Math.max(0L, nowNanos - targetNanos);
    }

    private record QueuedAudio(byte[] pcm, long queuedAtNanos, long relativeTimestampMicros) { }

    private record DecodedVideo(BufferedImage image, long videoPtsMicros, long videoPtsRelativeMicros,
            long firstFrameNanos, long grabReturnedAtNanos, long conversionStartedAtNanos,
            long convertedAtNanos) { }

    private record PresentationTiming(long targetNanos, long latenessNanos, VideoFrameDecision decision,
            HardResyncAction hardResyncAction, FfmpegPlaybackTelemetry.MasterClock masterClock,
            FfmpegPlaybackTelemetry.SyncAction syncAction, long syncDeltaMicros) { }

    /** Audio remains the master clock; video either waits or skips stale frames. */
    private enum HardResyncAction {
        NONE,
        WAIT_FOR_AUDIO_CLOCK,
        DROP_VIDEO_UNTIL_TOLERANCE;

        private boolean dropsVideo() {
            return this == DROP_VIDEO_UNTIL_TOLERANCE;
        }
    }

    /**
     * Enters only after a large divergence and leaves a behind-video resync
     * when the frame is back within the normal sync tolerance. It never changes
     * audio speed or rewrites media timestamps.
     */
    private record HardResyncPolicy(long thresholdMicros, long toleranceMicros) {
        private static HardResyncPolicy from(PlaybackSyncPolicy syncPolicy) {
            return new HardResyncPolicy(syncPolicy.hardResyncThresholdMicros(), syncPolicy.toleranceMicros());
        }

        private HardResyncAction forAudioClockDelta(long videoVsAudioMicros, HardResyncAction activeAction) {
            if (videoVsAudioMicros > thresholdMicros) {
                return HardResyncAction.WAIT_FOR_AUDIO_CLOCK;
            }
            if (videoVsAudioMicros < -thresholdMicros
                    || activeAction == HardResyncAction.DROP_VIDEO_UNTIL_TOLERANCE
                    && videoVsAudioMicros < -toleranceMicros) {
                return HardResyncAction.DROP_VIDEO_UNTIL_TOLERANCE;
            }
            return HardResyncAction.NONE;
        }

        private HardResyncAction forWallClockLateness(long latenessMicros, HardResyncAction activeAction) {
            if (latenessMicros > thresholdMicros
                    || activeAction == HardResyncAction.DROP_VIDEO_UNTIL_TOLERANCE
                    && latenessMicros > toleranceMicros) {
                return HardResyncAction.DROP_VIDEO_UNTIL_TOLERANCE;
            }
            return HardResyncAction.NONE;
        }

        private String describe() {
            return String.format(Locale.ROOT,
                    "PLAYER HARD RESYNC POLICY: thresholdMs=%.0f; master=audio-or-monotonic-wall; ahead=wait-video; behind=drop-until-tolerance; audioSpeed=unchanged",
                    thresholdMicros / 1_000d);
        }
    }

    /** Explicitly distinguishes normal presentation from a true late-frame drop. */
    private enum VideoFrameDecision {
        PRESENT,
        DROP_FOR_LATENESS,
        DROP_FOR_HARD_RESYNC;

        private boolean dropsVideo() {
            return this != PRESENT;
        }

        private boolean isHardResyncDrop() {
            return this == DROP_FOR_HARD_RESYNC;
        }
    }

    /**
     * The drop rule is independent from the presentation FPS cap and from UI
     * coalescing. Small timing variations are always presented; only a frame
     * already beyond the configured lateness threshold is discarded.
     */
    private record VideoFrameDropPolicy(long latenessThresholdMicros) {
        private static VideoFrameDropPolicy from(PlaybackSyncPolicy syncPolicy) {
            return new VideoFrameDropPolicy(syncPolicy.lateFrameDropThresholdMicros());
        }

        private VideoFrameDecision forAudioClockDelta(long videoVsAudioMicros) {
            return videoVsAudioMicros < -latenessThresholdMicros
                    ? VideoFrameDecision.DROP_FOR_LATENESS
                    : VideoFrameDecision.PRESENT;
        }

        private VideoFrameDecision forWallClockLateness(long latenessMicros) {
            return latenessMicros > latenessThresholdMicros
                    ? VideoFrameDecision.DROP_FOR_LATENESS
                    : VideoFrameDecision.PRESENT;
        }

        private String describe() {
            return String.format(Locale.ROOT,
                    "PLAYER FRAME DROP POLICY: drop=only-late-video; thresholdMs=%.0f; smallOscillations=present; telemetry=droppedForLateness",
                    latenessThresholdMicros / 1_000d);
        }
    }

    /**
     * Valores iniciais ajustáveis sem recompilar: -Dluffy.player.syncToleranceMs=...,
     * -Dluffy.player.lateFrameDropThresholdMs=... e -Dluffy.player.hardResyncThresholdMs=....
     */
    private record PlaybackSyncPolicy(long toleranceMicros, long lateFrameDropThresholdMicros,
            long hardResyncThresholdMicros) {
        private static PlaybackSyncPolicy load() {
            long toleranceMs = configuredMilliseconds("luffy.player.syncToleranceMs", 15L);
            long lateDropMs = Math.max(toleranceMs + 1L,
                    configuredMilliseconds("luffy.player.lateFrameDropThresholdMs", 80L));
            long hardResyncMs = Math.max(lateDropMs + 1L,
                    configuredMilliseconds("luffy.player.hardResyncThresholdMs", 250L));
            return new PlaybackSyncPolicy(toleranceMs * 1_000L, lateDropMs * 1_000L, hardResyncMs * 1_000L);
        }

        private static long configuredMilliseconds(String property, long defaultValue) {
            long configured = Long.getLong(property, defaultValue);
            return Math.max(1L, Math.min(5_000L, configured));
        }

        private String describe() {
            return String.format(Locale.ROOT,
                    "PLAYER SYNC POLICY: syncToleranceMs=%.0f; lateFrameDropThresholdMs=%.0f; hardResyncThresholdMs=%.0f",
                    toleranceMicros / 1_000d, lateFrameDropThresholdMicros / 1_000d, hardResyncThresholdMicros / 1_000d);
        }
    }

    /**
     * Teto temporário de apresentação para a comparação 60 FPS → 30 FPS.
     * A seleção é feita pela distância entre PTS de vídeo; portanto não altera
     * timestamps nem inventa uma cadência para conteúdo VFR. Defina 0 para
     * desativar o teto após a rodada de testes.
     */
    private record PresentationRatePolicy(long minimumIntervalMicros) {
        private static PresentationRatePolicy load() {
            long configuredFps = Long.getLong("luffy.player.presentationFpsCap", 30L);
            long fps = Math.max(0L, Math.min(240L, configuredFps));
            return new PresentationRatePolicy(fps == 0L ? 0L : 1_000_000L / fps);
        }

        private boolean limits(long timestampMicros, long lastSubmittedTimestampMicros) {
            return minimumIntervalMicros > 0L && lastSubmittedTimestampMicros != Long.MIN_VALUE
                    && timestampMicros - lastSubmittedTimestampMicros < minimumIntervalMicros;
        }

        private String describe() {
            return minimumIntervalMicros == 0L
                    ? "PLAYER PRESENTATION POLICY: presentationFpsCap=disabled; selection=PTS"
                    : String.format(Locale.ROOT,
                            "PLAYER PRESENTATION POLICY: presentationFpsCap=%.3f; selection=PTS; property=luffy.player.presentationFpsCap",
                            1_000_000d / minimumIntervalMicros);
        }
    }

    private void diagnostic(String message) {
        deliver(() -> listener.onDiagnostic(message));
    }

    private String describeSource(FFmpegFrameGrabber grabber) {
        return String.format(Locale.ROOT,
                "PLAYER SOURCE: file=\"%s\"; container=%s; videoCodec=%s; size=%dx%d; sourceFps=%.3f; "
                        + "audioCodec=%s; sampleRate=%d; channels=%d; durationMs=%d",
                source.getFileName(), value(grabber.getFormat()), value(grabber.getVideoCodecName()),
                grabber.getImageWidth(), grabber.getImageHeight(), grabber.getFrameRate(),
                value(grabber.getAudioCodecName()), grabber.getSampleRate(), grabber.getAudioChannels(),
                grabber.getLengthInTime() / 1_000L);
    }

    private static String value(String text) {
        return text == null || text.isBlank() ? "unknown" : text;
    }

    private static String concise(Throwable error) {
        String detail = error.getMessage();
        return detail == null || detail.isBlank() ? "no-message" : detail.replace('\n', ' ').replace('\r', ' ');
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
        SourceDataLine line;
        Thread outputWorker;
        synchronized (audioMonitor) {
            line = audioLine;
            audioLine = null;
            pendingAudio.clear();
            queuedAudioBytes = 0;
            audioBytesPerSecond = 0;
            audioSampleRate = 0;
            audioClockAnchorPtsMicros = -1L;
            audioClockAnchorFramePosition = -1L;
            outputWorker = audioWorker;
            audioWorker = null;
            audioMonitor.notifyAll();
        }
        if (line != null) {
            line.stop();
            line.flush();
            line.close();
        }
        if (outputWorker != null && outputWorker != Thread.currentThread()) {
            outputWorker.interrupt();
        }
    }
}
