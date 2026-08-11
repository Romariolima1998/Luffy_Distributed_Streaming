package dev.lufi.ui;

import java.util.Locale;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.atomic.AtomicLong;

/** Contadores de fluxo do player, emitidos periodicamente para diagnosticar cadência e codec. */
final class FfmpegPlaybackTelemetry {
    private static final long REPORT_INTERVAL_NANOS = 2_000_000_000L;

    enum MasterClock {
        AUDIO,
        WALL
    }

    enum SyncAction {
        WAIT,
        PRESENT,
        DROP,
        RESYNC
    }

    private final LongAdder presentedFrames = new LongAdder();
    private final LongAdder presentationNanos = new LongAdder();
    private final LongAdder audioWrites = new LongAdder();
    private final LongAdder audioBytes = new LongAdder();
    private final LongAdder audioWriteNanos = new LongAdder();
    private final LongAdder queueToUiSubmitNanos = new LongAdder();
    private final LongAdder uiSubmitToPresentNanos = new LongAdder();
    private final LongAdder decodeToPresentNanos = new LongAdder();
    private final LongAdder queueToPresentNanos = new LongAdder();
    private final LongAdder presentedSurfaceFrames = new LongAdder();
    private final LongAdder lateBeforePresentFrames = new LongAdder();
    private final LongAdder lateBeforePresentNanos = new LongAdder();
    private final LongAdder lateAfterPresentFrames = new LongAdder();
    private final LongAdder lateAfterPresentNanos = new LongAdder();
    private final LongAdder runLaterDelayNanos = new LongAdder();
    private final LongAdder toFxImageNanos = new LongAdder();
    private final LongAdder listenerOnFrameNanos = new LongAdder();
    private final LongAdder totalUiPresentNanos = new LongAdder();
    private final LongAdder uiPresentationFrames = new LongAdder();
    private final LongAdder audioQueueWaitNanos = new LongAdder();
    private final LongAdder audioQueueWaitBlocks = new LongAdder();
    private final AtomicLong greatestQueueToUiSubmitNanos = new AtomicLong();
    private final AtomicLong greatestUiSubmitToPresentNanos = new AtomicLong();
    private final AtomicLong greatestDecodeToPresentNanos = new AtomicLong();
    private final AtomicLong greatestQueueToPresentNanos = new AtomicLong();
    private final AtomicLong greatestLateBeforePresentNanos = new AtomicLong();
    private final AtomicLong greatestLateAfterPresentNanos = new AtomicLong();
    private final AtomicLong greatestRunLaterDelayNanos = new AtomicLong();
    private final AtomicLong greatestToFxImageNanos = new AtomicLong();
    private final AtomicLong greatestListenerOnFrameNanos = new AtomicLong();
    private final AtomicLong greatestTotalUiPresentNanos = new AtomicLong();
    private final AtomicLong greatestAudioQueueWaitNanos = new AtomicLong();
    private long lastReportNanos;
    private long decodedFrames;
    private long submittedFrames;
    private long skippedBusyFrames;
    private long skippedSurfaceFrames;
    private long rateLimitedFrames;
    private long droppedForLatenessFrames;
    private long hardResyncEvents;
    private long hardResyncVideoAheadEvents;
    private long hardResyncVideoBehindEvents;
    private long hardResyncWaitForAudioEvents;
    private long hardResyncDropVideoEvents;
    private long hardResyncDroppedFrames;
    private long hardResyncRecoveryEvents;
    private long syncWaitFrames;
    private long syncPresentedFrames;
    private long syncDroppedFrames;
    private volatile MasterClock lastMasterClock = MasterClock.WALL;
    private volatile SyncAction lastSyncAction = SyncAction.PRESENT;
    private long discardedAudioBytes;
    private long conversionNanos;
    private long grabCalls;
    private long grabNanos;
    private long greatestGrabNanos;
    private long videoGrabCalls;
    private long videoGrabNanos;
    private long greatestVideoGrabNanos;
    private long audioGrabCalls;
    private long audioGrabNanos;
    private long greatestAudioGrabNanos;
    private long lateAtGrabReturnFrames;
    private long lateAtGrabReturnNanos;
    private long greatestLateAtGrabReturnNanos;
    private long lateBeforeGrabFrames;
    private long lateBeforeGrabNanos;
    private long greatestLateBeforeGrabNanos;
    private long lateBeforeConvertFrames;
    private long lateBeforeConvertNanos;
    private long greatestLateBeforeConvertNanos;
    private long lateAfterConvertFrames;
    private long lateAfterConvertNanos;
    private long greatestLateAfterConvertNanos;
    private long lateBeforeQueueFrames;
    private long lateBeforeQueueNanos;
    private long greatestLateBeforeQueueNanos;
    private long schedulerCalls;
    private long schedulerWaitNanos;
    private long greatestSchedulerWaitNanos;
    private long schedulerLateNanos;
    private long greatestSchedulerLateNanos;
    private long schedulerRemainingAtEntryNanos;
    private long lastSchedulerTargetNanos;
    private long lastSchedulerExitNanos;
    private long mediaFirstFrameNanos = -1L;
    private long mediaCurrentSystemNanos = -1L;
    private long mediaExpectedPtsElapsedMicros = -1L;
    private long firstVideoPtsMicros = -1L;
    private long previousVideoPtsMicros = -1L;
    private long currentVideoPtsMicros = -1L;
    private long currentVideoPtsRelativeMicros = -1L;
    private volatile long firstAudioPtsRelativeMicros = -1L;
    private int audioQueuedBytes;
    private int pendingAudioChunks;
    private long audioQueuedDurationMicros = -1L;
    private int audioBytesPerSecond;
    private int audioLineAvailableBytes = -1;
    private int audioLineBufferSizeBytes = -1;
    /** Posição bruta exposta pelo dispositivo, desde que a linha foi aberta. */
    private long audioDevicePlaybackPositionMicros = -1L;
    /** Posição já ancorada no PTS relativo da mídia; este é o relógio de áudio do player. */
    private long audioPlaybackPositionMicros = -1L;
    private long audioLongFramePosition = -1L;
    private long lastAvSyncMicros = Long.MIN_VALUE;
    private long avSyncCount;
    private long avSyncMicros;
    private long avSyncMinMicros = Long.MAX_VALUE;
    private long avSyncMaxMicros = Long.MIN_VALUE;
    private int visualQueueSize;
    private int greatestVisualQueueSize;
    private long visualQueueOldestPtsMicros = -1L;
    private long visualQueueNewestPtsMicros = -1L;
    private long pendingSurfaceAgeNanos = -1L;
    private boolean frameDeliveryQueued;
    private long lastSurfaceCreatedAtNanos = -1L;
    private long lastSurfaceQueuedAtNanos = -1L;
    private volatile long lastSurfacePresentedAtNanos = -1L;
    private long queueReplacedSurfaces;
    private long queuedSurfaceFrames;
    private long decodeToQueueNanos;
    private long greatestDecodeToQueueNanos;
    private long lateFrames;
    private long lateNanos;
    private long greatestLatenessNanos;
    private long lastVideoTimestampMicros = -1L;
    private long timestampDeltaCount;
    private long timestampDeltaMicros;
    private long greatestTimestampDeltaMicros;
    private long outOfOrderTimestamps;
    private long reportedDecodedFrames;
    private long reportedSubmittedFrames;
    private long reportedSkippedBusyFrames;
    private long reportedSkippedSurfaceFrames;
    private long reportedRateLimitedFrames;
    private long reportedDroppedForLatenessFrames;
    private long reportedHardResyncEvents;
    private long reportedHardResyncVideoAheadEvents;
    private long reportedHardResyncVideoBehindEvents;
    private long reportedHardResyncWaitForAudioEvents;
    private long reportedHardResyncDropVideoEvents;
    private long reportedHardResyncDroppedFrames;
    private long reportedHardResyncRecoveryEvents;
    private long reportedSyncWaitFrames;
    private long reportedSyncPresentedFrames;
    private long reportedSyncDroppedFrames;
    private long reportedDiscardedAudioBytes;
    private long reportedConversionNanos;
    private long reportedGrabCalls;
    private long reportedGrabNanos;
    private long reportedVideoGrabCalls;
    private long reportedVideoGrabNanos;
    private long reportedAudioGrabCalls;
    private long reportedAudioGrabNanos;
    private long reportedLateAtGrabReturnFrames;
    private long reportedLateAtGrabReturnNanos;
    private long reportedLateBeforeGrabFrames;
    private long reportedLateBeforeGrabNanos;
    private long reportedLateBeforeConvertFrames;
    private long reportedLateBeforeConvertNanos;
    private long reportedLateAfterConvertFrames;
    private long reportedLateAfterConvertNanos;
    private long reportedLateBeforeQueueFrames;
    private long reportedLateBeforeQueueNanos;
    private long reportedSchedulerCalls;
    private long reportedSchedulerWaitNanos;
    private long reportedSchedulerLateNanos;
    private long reportedSchedulerRemainingAtEntryNanos;
    private long reportedQueueReplacedSurfaces;
    private long reportedQueuedSurfaceFrames;
    private long reportedDecodeToQueueNanos;
    private long reportedLateFrames;
    private long reportedLateNanos;
    private long reportedTimestampDeltaCount;
    private long reportedTimestampDeltaMicros;
    private long reportedPresentedFrames;
    private long reportedPresentationNanos;
    private long reportedAudioWrites;
    private long reportedAudioBytes;
    private long reportedAudioWriteNanos;
    private long reportedQueueToUiSubmitNanos;
    private long reportedUiSubmitToPresentNanos;
    private long reportedDecodeToPresentNanos;
    private long reportedQueueToPresentNanos;
    private long reportedPresentedSurfaceFrames;
    private long reportedLateBeforePresentFrames;
    private long reportedLateBeforePresentNanos;
    private long reportedLateAfterPresentFrames;
    private long reportedLateAfterPresentNanos;
    private long reportedRunLaterDelayNanos;
    private long reportedToFxImageNanos;
    private long reportedListenerOnFrameNanos;
    private long reportedTotalUiPresentNanos;
    private long reportedUiPresentationFrames;
    private long reportedAudioQueueWaitNanos;
    private long reportedAudioQueueWaitBlocks;
    private long reportedAvSyncCount;
    private long reportedAvSyncMicros;

    FfmpegPlaybackTelemetry() {
        this(System.nanoTime());
    }

    FfmpegPlaybackTelemetry(long startedAtNanos) {
        lastReportNanos = startedAtNanos;
    }

    void recordDecodedVideo(long timestampMicros, long latenessNanos) {
        decodedFrames++;
        if (lastVideoTimestampMicros >= 0L) {
            long delta = timestampMicros - lastVideoTimestampMicros;
            if (delta > 0L) {
                timestampDeltaCount++;
                timestampDeltaMicros += delta;
                greatestTimestampDeltaMicros = Math.max(greatestTimestampDeltaMicros, delta);
            } else {
                outOfOrderTimestamps++;
            }
        }
        lastVideoTimestampMicros = timestampMicros;
        if (latenessNanos > 0L) {
            lateFrames++;
            lateNanos += latenessNanos;
            greatestLatenessNanos = Math.max(greatestLatenessNanos, latenessNanos);
        }
    }

    void recordSubmittedFrame(long workNanos) {
        submittedFrames++;
        conversionNanos += Math.max(0L, workNanos);
    }

    /**
     * Mede somente a chamada bloqueante ao FFmpeg. Um mesmo Frame pode conter
     * imagem e amostras; nesse caso ele entra nas duas categorias para tornar
     * explícito onde o custo foi observado.
     */
    void recordGrab(long workNanos, boolean video, boolean audio) {
        long elapsed = Math.max(0L, workNanos);
        grabCalls++;
        grabNanos += elapsed;
        greatestGrabNanos = Math.max(greatestGrabNanos, elapsed);
        if (video) {
            videoGrabCalls++;
            videoGrabNanos += elapsed;
            greatestVideoGrabNanos = Math.max(greatestVideoGrabNanos, elapsed);
        }
        if (audio) {
            audioGrabCalls++;
            audioGrabNanos += elapsed;
            greatestAudioGrabNanos = Math.max(greatestAudioGrabNanos, elapsed);
        }
    }

    /** Registra o atraso já existente assim que grab() devolve o frame, antes da conversão e da UI. */
    void recordLatenessAtGrabReturn(long latenessNanos, long grabNanos) {
        if (latenessNanos <= 0L) return;
        lateAtGrabReturnFrames++;
        lateAtGrabReturnNanos += latenessNanos;
        greatestLateAtGrabReturnNanos = Math.max(greatestLateAtGrabReturnNanos, latenessNanos);
        long existingBeforeGrab = Math.max(0L, latenessNanos - Math.max(0L, grabNanos));
        if (existingBeforeGrab > 0L) {
            lateBeforeGrabFrames++;
            lateBeforeGrabNanos += existingBeforeGrab;
            greatestLateBeforeGrabNanos = Math.max(greatestLateBeforeGrabNanos, existingBeforeGrab);
        }
    }

    void recordMediaClock(long firstFrameNanos, long currentSystemNanos, long expectedPtsElapsedMicros) {
        mediaFirstFrameNanos = firstFrameNanos;
        mediaCurrentSystemNanos = currentSystemNanos;
        mediaExpectedPtsElapsedMicros = expectedPtsElapsedMicros;
    }

    void recordVideoPts(long videoPtsMicros, long videoPtsRelativeMicros) {
        if (firstVideoPtsMicros < 0L) {
            firstVideoPtsMicros = videoPtsMicros;
        }
        previousVideoPtsMicros = currentVideoPtsMicros;
        currentVideoPtsMicros = videoPtsMicros;
        currentVideoPtsRelativeMicros = videoPtsRelativeMicros;
    }

    void recordScheduler(long mediaBaseNanos, long targetNanos, long entryNanos, long exitNanos) {
        long waited = Math.max(0L, exitNanos - entryNanos);
        long late = Math.max(0L, exitNanos - targetNanos);
        schedulerCalls++;
        schedulerWaitNanos += waited;
        greatestSchedulerWaitNanos = Math.max(greatestSchedulerWaitNanos, waited);
        schedulerLateNanos += late;
        greatestSchedulerLateNanos = Math.max(greatestSchedulerLateNanos, late);
        schedulerRemainingAtEntryNanos += targetNanos - entryNanos;
        lastSchedulerTargetNanos = targetNanos - mediaBaseNanos;
        lastSchedulerExitNanos = exitNanos - mediaBaseNanos;
    }

    void recordLatenessBeforeConvert(long latenessNanos) {
        lateBeforeConvertFrames++;
        lateBeforeConvertNanos += Math.max(0L, latenessNanos);
        greatestLateBeforeConvertNanos = Math.max(greatestLateBeforeConvertNanos, Math.max(0L, latenessNanos));
    }

    void recordLatenessAfterConvert(long latenessNanos) {
        lateAfterConvertFrames++;
        lateAfterConvertNanos += Math.max(0L, latenessNanos);
        greatestLateAfterConvertNanos = Math.max(greatestLateAfterConvertNanos, Math.max(0L, latenessNanos));
    }

    void recordLatenessBeforeQueue(long latenessNanos) {
        lateBeforeQueueFrames++;
        lateBeforeQueueNanos += Math.max(0L, latenessNanos);
        greatestLateBeforeQueueNanos = Math.max(greatestLateBeforeQueueNanos, Math.max(0L, latenessNanos));
    }

    void recordLatenessBeforePresent(long latenessNanos) {
        if (latenessNanos <= 0L) return;
        lateBeforePresentFrames.increment();
        lateBeforePresentNanos.add(latenessNanos);
        updateMaximum(greatestLateBeforePresentNanos, latenessNanos);
    }

    void recordLatenessAfterPresent(long latenessNanos) {
        if (latenessNanos <= 0L) return;
        lateAfterPresentFrames.increment();
        lateAfterPresentNanos.add(latenessNanos);
        updateMaximum(greatestLateAfterPresentNanos, latenessNanos);
    }

    void recordAudioClockAnchor(long audioPtsRelativeMicros) {
        if (firstAudioPtsRelativeMicros < 0L) {
            firstAudioPtsRelativeMicros = audioPtsRelativeMicros;
        }
    }

    void recordAudioState(int pendingChunks, int queuedBytes, int bytesPerSecond, int lineAvailableBytes,
            int lineBufferSizeBytes, long devicePlaybackPositionMicros, long longFramePosition,
            long mediaPlaybackPositionMicros) {
        this.pendingAudioChunks = Math.max(0, pendingChunks);
        audioQueuedBytes = Math.max(0, queuedBytes);
        audioBytesPerSecond = Math.max(0, bytesPerSecond);
        audioQueuedDurationMicros = bytesPerSecond <= 0 ? -1L : audioQueuedBytes * 1_000_000L / bytesPerSecond;
        audioLineAvailableBytes = lineAvailableBytes;
        audioLineBufferSizeBytes = lineBufferSizeBytes;
        audioDevicePlaybackPositionMicros = devicePlaybackPositionMicros;
        audioPlaybackPositionMicros = mediaPlaybackPositionMicros;
        audioLongFramePosition = longFramePosition;
        if (mediaPlaybackPositionMicros >= 0L && currentVideoPtsRelativeMicros >= 0L) {
            lastAvSyncMicros = currentVideoPtsRelativeMicros - mediaPlaybackPositionMicros;
            avSyncCount++;
            avSyncMicros += lastAvSyncMicros;
            avSyncMinMicros = Math.min(avSyncMinMicros, lastAvSyncMicros);
            avSyncMaxMicros = Math.max(avSyncMaxMicros, lastAvSyncMicros);
        }
    }

    void recordRunLaterDelay(long delayNanos) {
        long elapsed = Math.max(0L, delayNanos);
        runLaterDelayNanos.add(elapsed);
        updateMaximum(greatestRunLaterDelayNanos, elapsed);
    }

    void recordUiPresentation(long toFxImageDurationNanos, long listenerDurationNanos, long totalDurationNanos) {
        long toFx = Math.max(0L, toFxImageDurationNanos);
        long listener = Math.max(0L, listenerDurationNanos);
        long total = Math.max(0L, totalDurationNanos);
        uiPresentationFrames.increment();
        toFxImageNanos.add(toFx);
        listenerOnFrameNanos.add(listener);
        totalUiPresentNanos.add(total);
        updateMaximum(greatestToFxImageNanos, toFx);
        updateMaximum(greatestListenerOnFrameNanos, listener);
        updateMaximum(greatestTotalUiPresentNanos, total);
    }

    void recordAudioQueueWait(long waitNanos) {
        long elapsed = Math.max(0L, waitNanos);
        audioQueueWaitBlocks.increment();
        audioQueueWaitNanos.add(elapsed);
        updateMaximum(greatestAudioQueueWaitNanos, elapsed);
    }

    void recordQueuedSurface(long decodedAtNanos, long createdAtNanos, long queuedAtNanos, long videoPtsMicros,
            long videoPtsRelativeMicros, int queueSize, boolean replacedPendingSurface) {
        lastSurfaceCreatedAtNanos = createdAtNanos;
        lastSurfaceQueuedAtNanos = queuedAtNanos;
        visualQueueSize = Math.max(0, queueSize);
        greatestVisualQueueSize = Math.max(greatestVisualQueueSize, visualQueueSize);
        visualQueueOldestPtsMicros = videoPtsMicros;
        visualQueueNewestPtsMicros = videoPtsMicros;
        if (replacedPendingSurface) {
            queueReplacedSurfaces++;
        }
        queuedSurfaceFrames++;
        long decodeToQueue = Math.max(0L, queuedAtNanos - decodedAtNanos);
        decodeToQueueNanos += decodeToQueue;
        greatestDecodeToQueueNanos = Math.max(greatestDecodeToQueueNanos, decodeToQueue);
    }

    void recordVisualQueueState(int queueSize, long oldestPtsMicros, long newestPtsMicros, long pendingAgeNanos,
            boolean deliveryQueued) {
        visualQueueSize = Math.max(0, queueSize);
        greatestVisualQueueSize = Math.max(greatestVisualQueueSize, visualQueueSize);
        visualQueueOldestPtsMicros = oldestPtsMicros;
        visualQueueNewestPtsMicros = newestPtsMicros;
        pendingSurfaceAgeNanos = pendingAgeNanos;
        frameDeliveryQueued = deliveryQueued;
    }

    void recordUiSubmit(long queuedAtNanos, long uiSubmitAtNanos) {
        long elapsed = Math.max(0L, uiSubmitAtNanos - queuedAtNanos);
        queueToUiSubmitNanos.add(elapsed);
        updateMaximum(greatestQueueToUiSubmitNanos, elapsed);
    }

    void recordPresentedSurface(long decodedAtNanos, long queuedAtNanos, long uiSubmitAtNanos, long presentedAtNanos) {
        long uiToPresent = Math.max(0L, presentedAtNanos - uiSubmitAtNanos);
        long decodeToPresent = Math.max(0L, presentedAtNanos - decodedAtNanos);
        long queueToPresent = Math.max(0L, presentedAtNanos - queuedAtNanos);
        presentedSurfaceFrames.increment();
        uiSubmitToPresentNanos.add(uiToPresent);
        decodeToPresentNanos.add(decodeToPresent);
        queueToPresentNanos.add(queueToPresent);
        updateMaximum(greatestUiSubmitToPresentNanos, uiToPresent);
        updateMaximum(greatestDecodeToPresentNanos, decodeToPresent);
        updateMaximum(greatestQueueToPresentNanos, queueToPresent);
        lastSurfacePresentedAtNanos = presentedAtNanos;
    }

    void recordUiSkippedFrame() {
        skippedBusyFrames++;
    }

    void recordSurfaceSkippedFrame() {
        skippedSurfaceFrames++;
    }

    /** Quadro substituído antes do scheduler visual; PCM continua intacto. */
    void recordVideoQueueOverflowDrop() {
        skippedSurfaceFrames++;
    }

    void recordRateLimitedFrame() {
        rateLimitedFrames++;
    }

    void recordDroppedForLateness() {
        droppedForLatenessFrames++;
    }

    void recordSyncDecision(MasterClock masterClock, SyncAction action, boolean dropsVideo) {
        lastMasterClock = masterClock;
        lastSyncAction = action;
        switch (action) {
            case WAIT -> syncWaitFrames++;
            case PRESENT -> syncPresentedFrames++;
            case DROP -> syncDroppedFrames++;
            case RESYNC -> {
                if (dropsVideo) {
                    syncDroppedFrames++;
                }
            }
        }
    }

    void recordHardResync(long videoVsMasterClockMicros, boolean dropsVideo) {
        hardResyncEvents++;
        if (videoVsMasterClockMicros >= 0L) {
            hardResyncVideoAheadEvents++;
        } else {
            hardResyncVideoBehindEvents++;
        }
        if (dropsVideo) {
            hardResyncDropVideoEvents++;
        } else {
            hardResyncWaitForAudioEvents++;
        }
    }

    void recordHardResyncDrop() {
        hardResyncDroppedFrames++;
    }

    void recordHardResyncRecovery() {
        hardResyncRecoveryEvents++;
    }

    void recordDiscardedAudio(int bytes) {
        discardedAudioBytes += Math.max(0, bytes);
    }

    void recordPresentedFrame(long workNanos) {
        presentedFrames.increment();
        presentationNanos.add(Math.max(0L, workNanos));
    }

    void recordAudioWrite(int bytes, long workNanos) {
        if (bytes <= 0) return;
        audioWrites.increment();
        audioBytes.add(bytes);
        audioWriteNanos.add(Math.max(0L, workNanos));
    }

    String reportIfDue(long nowNanos) {
        return report(nowNanos, false);
    }

    String finalReport(long nowNanos) {
        return report(nowNanos, true);
    }

    private String report(long nowNanos, boolean force) {
        if (!force && nowNanos - lastReportNanos < REPORT_INTERVAL_NANOS) return null;
        long elapsedNanos = Math.max(1L, nowNanos - lastReportNanos);
        double elapsedSeconds = elapsedNanos / 1_000_000_000d;
        long currentPresented = presentedFrames.sum();
        long currentPresentationNanos = presentationNanos.sum();
        long currentAudioWrites = audioWrites.sum();
        long currentAudioBytes = audioBytes.sum();
        long currentAudioWriteNanos = audioWriteNanos.sum();
        long currentQueueToUiSubmitNanos = queueToUiSubmitNanos.sum();
        long currentUiSubmitToPresentNanos = uiSubmitToPresentNanos.sum();
        long currentDecodeToPresentNanos = decodeToPresentNanos.sum();
        long currentQueueToPresentNanos = queueToPresentNanos.sum();
        long currentPresentedSurfaceFrames = presentedSurfaceFrames.sum();
        long currentLateBeforePresentFrames = lateBeforePresentFrames.sum();
        long currentLateBeforePresentNanos = lateBeforePresentNanos.sum();
        long currentLateAfterPresentFrames = lateAfterPresentFrames.sum();
        long currentLateAfterPresentNanos = lateAfterPresentNanos.sum();
        long currentRunLaterDelayNanos = runLaterDelayNanos.sum();
        long currentToFxImageNanos = toFxImageNanos.sum();
        long currentListenerOnFrameNanos = listenerOnFrameNanos.sum();
        long currentTotalUiPresentNanos = totalUiPresentNanos.sum();
        long currentUiPresentationFrames = uiPresentationFrames.sum();
        long currentAudioQueueWaitNanos = audioQueueWaitNanos.sum();
        long currentAudioQueueWaitBlocks = audioQueueWaitBlocks.sum();
        long decodedDelta = decodedFrames - reportedDecodedFrames;
        long submittedDelta = submittedFrames - reportedSubmittedFrames;
        long busyDelta = skippedBusyFrames - reportedSkippedBusyFrames;
        long surfaceDelta = skippedSurfaceFrames - reportedSkippedSurfaceFrames;
        long rateLimitedDelta = rateLimitedFrames - reportedRateLimitedFrames;
        long droppedForLatenessDelta = droppedForLatenessFrames - reportedDroppedForLatenessFrames;
        long hardResyncDelta = hardResyncEvents - reportedHardResyncEvents;
        long hardResyncAheadDelta = hardResyncVideoAheadEvents - reportedHardResyncVideoAheadEvents;
        long hardResyncBehindDelta = hardResyncVideoBehindEvents - reportedHardResyncVideoBehindEvents;
        long hardResyncWaitDelta = hardResyncWaitForAudioEvents - reportedHardResyncWaitForAudioEvents;
        long hardResyncDropDelta = hardResyncDropVideoEvents - reportedHardResyncDropVideoEvents;
        long hardResyncDroppedFramesDelta = hardResyncDroppedFrames - reportedHardResyncDroppedFrames;
        long hardResyncRecoveryDelta = hardResyncRecoveryEvents - reportedHardResyncRecoveryEvents;
        long syncWaitDelta = syncWaitFrames - reportedSyncWaitFrames;
        long syncPresentedDelta = syncPresentedFrames - reportedSyncPresentedFrames;
        long syncDroppedDelta = syncDroppedFrames - reportedSyncDroppedFrames;
        long discardedAudioBytesDelta = discardedAudioBytes - reportedDiscardedAudioBytes;
        long conversionDelta = conversionNanos - reportedConversionNanos;
        long grabCallsDelta = grabCalls - reportedGrabCalls;
        long grabNanosDelta = grabNanos - reportedGrabNanos;
        long videoGrabCallsDelta = videoGrabCalls - reportedVideoGrabCalls;
        long videoGrabNanosDelta = videoGrabNanos - reportedVideoGrabNanos;
        long audioGrabCallsDelta = audioGrabCalls - reportedAudioGrabCalls;
        long audioGrabNanosDelta = audioGrabNanos - reportedAudioGrabNanos;
        long lateAtGrabReturnFramesDelta = lateAtGrabReturnFrames - reportedLateAtGrabReturnFrames;
        long lateAtGrabReturnNanosDelta = lateAtGrabReturnNanos - reportedLateAtGrabReturnNanos;
        long lateBeforeGrabFramesDelta = lateBeforeGrabFrames - reportedLateBeforeGrabFrames;
        long lateBeforeGrabNanosDelta = lateBeforeGrabNanos - reportedLateBeforeGrabNanos;
        long lateBeforeConvertFramesDelta = lateBeforeConvertFrames - reportedLateBeforeConvertFrames;
        long lateBeforeConvertNanosDelta = lateBeforeConvertNanos - reportedLateBeforeConvertNanos;
        long lateAfterConvertFramesDelta = lateAfterConvertFrames - reportedLateAfterConvertFrames;
        long lateAfterConvertNanosDelta = lateAfterConvertNanos - reportedLateAfterConvertNanos;
        long lateBeforeQueueFramesDelta = lateBeforeQueueFrames - reportedLateBeforeQueueFrames;
        long lateBeforeQueueNanosDelta = lateBeforeQueueNanos - reportedLateBeforeQueueNanos;
        long schedulerCallsDelta = schedulerCalls - reportedSchedulerCalls;
        long schedulerWaitNanosDelta = schedulerWaitNanos - reportedSchedulerWaitNanos;
        long schedulerLateNanosDelta = schedulerLateNanos - reportedSchedulerLateNanos;
        long schedulerRemainingAtEntryNanosDelta = schedulerRemainingAtEntryNanos - reportedSchedulerRemainingAtEntryNanos;
        long queueReplacedDelta = queueReplacedSurfaces - reportedQueueReplacedSurfaces;
        long queuedSurfaceFramesDelta = queuedSurfaceFrames - reportedQueuedSurfaceFrames;
        long decodeToQueueNanosDelta = decodeToQueueNanos - reportedDecodeToQueueNanos;
        long lateFramesDelta = lateFrames - reportedLateFrames;
        long lateNanosDelta = lateNanos - reportedLateNanos;
        long timestampCountDelta = timestampDeltaCount - reportedTimestampDeltaCount;
        long timestampMicrosDelta = timestampDeltaMicros - reportedTimestampDeltaMicros;
        long presentedDelta = currentPresented - reportedPresentedFrames;
        long presentationNanosDelta = currentPresentationNanos - reportedPresentationNanos;
        long audioWritesDelta = currentAudioWrites - reportedAudioWrites;
        long audioBytesDelta = currentAudioBytes - reportedAudioBytes;
        long audioWriteNanosDelta = currentAudioWriteNanos - reportedAudioWriteNanos;
        long queueToUiSubmitNanosDelta = currentQueueToUiSubmitNanos - reportedQueueToUiSubmitNanos;
        long uiSubmitToPresentNanosDelta = currentUiSubmitToPresentNanos - reportedUiSubmitToPresentNanos;
        long decodeToPresentNanosDelta = currentDecodeToPresentNanos - reportedDecodeToPresentNanos;
        long queueToPresentNanosDelta = currentQueueToPresentNanos - reportedQueueToPresentNanos;
        long presentedSurfaceFramesDelta = currentPresentedSurfaceFrames - reportedPresentedSurfaceFrames;
        long lateBeforePresentFramesDelta = currentLateBeforePresentFrames - reportedLateBeforePresentFrames;
        long lateBeforePresentNanosDelta = currentLateBeforePresentNanos - reportedLateBeforePresentNanos;
        long lateAfterPresentFramesDelta = currentLateAfterPresentFrames - reportedLateAfterPresentFrames;
        long lateAfterPresentNanosDelta = currentLateAfterPresentNanos - reportedLateAfterPresentNanos;
        long runLaterDelayNanosDelta = currentRunLaterDelayNanos - reportedRunLaterDelayNanos;
        long toFxImageNanosDelta = currentToFxImageNanos - reportedToFxImageNanos;
        long listenerOnFrameNanosDelta = currentListenerOnFrameNanos - reportedListenerOnFrameNanos;
        long totalUiPresentNanosDelta = currentTotalUiPresentNanos - reportedTotalUiPresentNanos;
        long uiPresentationFramesDelta = currentUiPresentationFrames - reportedUiPresentationFrames;
        long audioQueueWaitNanosDelta = currentAudioQueueWaitNanos - reportedAudioQueueWaitNanos;
        long audioQueueWaitBlocksDelta = currentAudioQueueWaitBlocks - reportedAudioQueueWaitBlocks;
        long avSyncCountDelta = avSyncCount - reportedAvSyncCount;
        long avSyncMicrosDelta = avSyncMicros - reportedAvSyncMicros;

        lastReportNanos = nowNanos;
        reportedDecodedFrames = decodedFrames;
        reportedSubmittedFrames = submittedFrames;
        reportedSkippedBusyFrames = skippedBusyFrames;
        reportedSkippedSurfaceFrames = skippedSurfaceFrames;
        reportedRateLimitedFrames = rateLimitedFrames;
        reportedDroppedForLatenessFrames = droppedForLatenessFrames;
        reportedHardResyncEvents = hardResyncEvents;
        reportedHardResyncVideoAheadEvents = hardResyncVideoAheadEvents;
        reportedHardResyncVideoBehindEvents = hardResyncVideoBehindEvents;
        reportedHardResyncWaitForAudioEvents = hardResyncWaitForAudioEvents;
        reportedHardResyncDropVideoEvents = hardResyncDropVideoEvents;
        reportedHardResyncDroppedFrames = hardResyncDroppedFrames;
        reportedHardResyncRecoveryEvents = hardResyncRecoveryEvents;
        reportedSyncWaitFrames = syncWaitFrames;
        reportedSyncPresentedFrames = syncPresentedFrames;
        reportedSyncDroppedFrames = syncDroppedFrames;
        reportedDiscardedAudioBytes = discardedAudioBytes;
        reportedConversionNanos = conversionNanos;
        reportedGrabCalls = grabCalls;
        reportedGrabNanos = grabNanos;
        reportedVideoGrabCalls = videoGrabCalls;
        reportedVideoGrabNanos = videoGrabNanos;
        reportedAudioGrabCalls = audioGrabCalls;
        reportedAudioGrabNanos = audioGrabNanos;
        reportedLateAtGrabReturnFrames = lateAtGrabReturnFrames;
        reportedLateAtGrabReturnNanos = lateAtGrabReturnNanos;
        reportedLateBeforeGrabFrames = lateBeforeGrabFrames;
        reportedLateBeforeGrabNanos = lateBeforeGrabNanos;
        reportedLateBeforeConvertFrames = lateBeforeConvertFrames;
        reportedLateBeforeConvertNanos = lateBeforeConvertNanos;
        reportedLateAfterConvertFrames = lateAfterConvertFrames;
        reportedLateAfterConvertNanos = lateAfterConvertNanos;
        reportedLateBeforeQueueFrames = lateBeforeQueueFrames;
        reportedLateBeforeQueueNanos = lateBeforeQueueNanos;
        reportedSchedulerCalls = schedulerCalls;
        reportedSchedulerWaitNanos = schedulerWaitNanos;
        reportedSchedulerLateNanos = schedulerLateNanos;
        reportedSchedulerRemainingAtEntryNanos = schedulerRemainingAtEntryNanos;
        reportedQueueReplacedSurfaces = queueReplacedSurfaces;
        reportedQueuedSurfaceFrames = queuedSurfaceFrames;
        reportedDecodeToQueueNanos = decodeToQueueNanos;
        reportedLateFrames = lateFrames;
        reportedLateNanos = lateNanos;
        reportedTimestampDeltaCount = timestampDeltaCount;
        reportedTimestampDeltaMicros = timestampDeltaMicros;
        reportedPresentedFrames = currentPresented;
        reportedPresentationNanos = currentPresentationNanos;
        reportedAudioWrites = currentAudioWrites;
        reportedAudioBytes = currentAudioBytes;
        reportedAudioWriteNanos = currentAudioWriteNanos;
        reportedQueueToUiSubmitNanos = currentQueueToUiSubmitNanos;
        reportedUiSubmitToPresentNanos = currentUiSubmitToPresentNanos;
        reportedDecodeToPresentNanos = currentDecodeToPresentNanos;
        reportedQueueToPresentNanos = currentQueueToPresentNanos;
        reportedPresentedSurfaceFrames = currentPresentedSurfaceFrames;
        reportedLateBeforePresentFrames = currentLateBeforePresentFrames;
        reportedLateBeforePresentNanos = currentLateBeforePresentNanos;
        reportedLateAfterPresentFrames = currentLateAfterPresentFrames;
        reportedLateAfterPresentNanos = currentLateAfterPresentNanos;
        reportedRunLaterDelayNanos = currentRunLaterDelayNanos;
        reportedToFxImageNanos = currentToFxImageNanos;
        reportedListenerOnFrameNanos = currentListenerOnFrameNanos;
        reportedTotalUiPresentNanos = currentTotalUiPresentNanos;
        reportedUiPresentationFrames = currentUiPresentationFrames;
        reportedAudioQueueWaitNanos = currentAudioQueueWaitNanos;
        reportedAudioQueueWaitBlocks = currentAudioQueueWaitBlocks;
        reportedAvSyncCount = avSyncCount;
        reportedAvSyncMicros = avSyncMicros;

        double sourceFps = decodedDelta / elapsedSeconds;
        double submittedFps = submittedDelta / elapsedSeconds;
        double presentedFps = presentedDelta / elapsedSeconds;
        double sourceGapMs = timestampCountDelta == 0 ? 0d : timestampMicrosDelta / (timestampCountDelta * 1_000d);
        double lateMs = lateFramesDelta == 0 ? 0d : lateNanosDelta / (lateFramesDelta * 1_000_000d);
        double grabMs = grabCallsDelta == 0 ? 0d : grabNanosDelta / (grabCallsDelta * 1_000_000d);
        double videoGrabMs = videoGrabCallsDelta == 0 ? 0d : videoGrabNanosDelta / (videoGrabCallsDelta * 1_000_000d);
        double audioGrabMs = audioGrabCallsDelta == 0 ? 0d : audioGrabNanosDelta / (audioGrabCallsDelta * 1_000_000d);
        double lateAtGrabReturnMs = lateAtGrabReturnFramesDelta == 0 ? 0d
                : lateAtGrabReturnNanosDelta / (lateAtGrabReturnFramesDelta * 1_000_000d);
        double lateBeforeGrabMs = lateBeforeGrabFramesDelta == 0 ? 0d
                : lateBeforeGrabNanosDelta / (lateBeforeGrabFramesDelta * 1_000_000d);
        double lateBeforeConvertMs = averageMillis(lateBeforeConvertNanosDelta, lateBeforeConvertFramesDelta);
        double lateAfterConvertMs = averageMillis(lateAfterConvertNanosDelta, lateAfterConvertFramesDelta);
        double lateBeforeQueueMs = averageMillis(lateBeforeQueueNanosDelta, lateBeforeQueueFramesDelta);
        double schedulerWaitMs = averageMillis(schedulerWaitNanosDelta, schedulerCallsDelta);
        double schedulerLateMs = averageMillis(schedulerLateNanosDelta, schedulerCallsDelta);
        double schedulerRemainingAtEntryMs = averageMillis(schedulerRemainingAtEntryNanosDelta, schedulerCallsDelta);
        double decodeToQueueMs = averageMillis(decodeToQueueNanosDelta, queuedSurfaceFramesDelta);
        double queueToUiSubmitMs = averageMillis(queueToUiSubmitNanosDelta, presentedSurfaceFramesDelta);
        double uiSubmitToPresentMs = averageMillis(uiSubmitToPresentNanosDelta, presentedSurfaceFramesDelta);
        double decodeToPresentMs = averageMillis(decodeToPresentNanosDelta, presentedSurfaceFramesDelta);
        double queueToPresentMs = averageMillis(queueToPresentNanosDelta, presentedSurfaceFramesDelta);
        double lateBeforePresentMs = averageMillis(lateBeforePresentNanosDelta, lateBeforePresentFramesDelta);
        double lateAfterPresentMs = averageMillis(lateAfterPresentNanosDelta, lateAfterPresentFramesDelta);
        double runLaterDelayMs = averageMillis(runLaterDelayNanosDelta, uiPresentationFramesDelta);
        double toFxImageMs = averageMillis(toFxImageNanosDelta, uiPresentationFramesDelta);
        double listenerOnFrameMs = averageMillis(listenerOnFrameNanosDelta, uiPresentationFramesDelta);
        double totalUiPresentMs = averageMillis(totalUiPresentNanosDelta, uiPresentationFramesDelta);
        double audioQueueWaitMs = averageMillis(audioQueueWaitNanosDelta, audioQueueWaitBlocksDelta);
        double avSyncAvgMs = avSyncCountDelta == 0L ? -1d : avSyncMicrosDelta / (avSyncCountDelta * 1_000d);
        double convertMs = submittedDelta == 0 ? 0d : conversionDelta / (submittedDelta * 1_000_000d);
        double uploadMs = presentedDelta == 0 ? 0d : presentationNanosDelta / (presentedDelta * 1_000_000d);
        double audioKiBps = audioBytesDelta / elapsedSeconds / 1_024d;
        double audioWriteMs = audioWritesDelta == 0 ? 0d : audioWriteNanosDelta / (audioWritesDelta * 1_000_000d);
        double wallClockElapsedMs = mediaFirstFrameNanos < 0L ? -1d : (nowNanos - mediaFirstFrameNanos) / 1_000_000d;
        double expectedPtsElapsedMs = mediaExpectedPtsElapsedMicros < 0L ? -1d : mediaExpectedPtsElapsedMicros / 1_000d;
        double mediaClockMs = wallClockElapsedMs;
        double clockDriftMs = wallClockElapsedMs < 0d || expectedPtsElapsedMs < 0d ? -1d : wallClockElapsedMs - expectedPtsElapsedMs;
        double videoPtsMs = microsToMillis(currentVideoPtsMicros);
        double firstVideoPtsMs = microsToMillis(firstVideoPtsMicros);
        double previousVideoPtsMs = microsToMillis(previousVideoPtsMicros);
        double videoPtsRelativeMs = microsToMillis(currentVideoPtsRelativeMicros);
        double videoPtsGapMs = currentVideoPtsMicros < 0L || previousVideoPtsMicros < 0L ? -1d
                : (currentVideoPtsMicros - previousVideoPtsMicros) / 1_000d;
        double audioDevicePlaybackPositionMs = microsToMillis(audioDevicePlaybackPositionMicros);
        double audioClockMs = microsToMillis(audioPlaybackPositionMicros);
        double audioPlaybackPositionMs = audioClockMs;
        double videoVsMediaClockMs = videoPtsRelativeMs < 0d || mediaClockMs < 0d ? -1d : videoPtsRelativeMs - mediaClockMs;
        double videoVsAudioClockMs = videoPtsRelativeMs < 0d || audioClockMs < 0d ? -1d : videoPtsRelativeMs - audioClockMs;
        double audioVsMediaClockMs = audioClockMs < 0d || mediaClockMs < 0d ? -1d : audioClockMs - mediaClockMs;
        double queueOldestPtsMs = microsToMillis(visualQueueOldestPtsMicros);
        double queueNewestPtsMs = microsToMillis(visualQueueNewestPtsMicros);
        double queueDurationMs = visualQueueOldestPtsMicros < 0L || visualQueueNewestPtsMicros < 0L ? -1d
                : (visualQueueNewestPtsMicros - visualQueueOldestPtsMicros) / 1_000d;
        double pendingSurfaceAgeMs = pendingSurfaceAgeNanos < 0L ? -1d : pendingSurfaceAgeNanos / 1_000_000d;
        double surfaceCreatedAtMs = relativeMillis(lastSurfaceCreatedAtNanos, mediaFirstFrameNanos);
        double surfaceQueuedAtMs = relativeMillis(lastSurfaceQueuedAtNanos, mediaFirstFrameNanos);
        double surfacePresentedAtMs = relativeMillis(lastSurfacePresentedAtNanos, mediaFirstFrameNanos);
        double audioBufferedMs = audioBytesPerSecond <= 0 || audioLineAvailableBytes < 0 || audioLineBufferSizeBytes < 0 ? -1d
                : Math.max(0, audioLineBufferSizeBytes - audioLineAvailableBytes) * 1_000d / audioBytesPerSecond;
        double avSyncMs = lastAvSyncMicros == Long.MIN_VALUE ? -1d : lastAvSyncMicros / 1_000d;
        double avSyncMinMs = avSyncMinMicros == Long.MAX_VALUE ? -1d : avSyncMinMicros / 1_000d;
        double avSyncMaxMs = avSyncMaxMicros == Long.MIN_VALUE ? -1d : avSyncMaxMicros / 1_000d;
        String base = String.format(Locale.ROOT,
                "janelaMs=%d; sourceFps=%.1f; queuedFps=%.1f; presentedFps=%.1f; masterClock=%s; syncAction=%s; syncWaitFrames=%d; syncPresentedFrames=%d; syncDroppedFrames=%d; uiSkipped=%d; surfaceSkipped=%d; rateLimited=%d; droppedForLateness=%d; hardResync=%d; hardResyncCount=%d; hardResyncVideoAhead=%d; hardResyncVideoBehind=%d; hardResyncWait=%d; hardResyncDrop=%d; hardResyncDropped=%d; hardResyncRecovered=%d; "
                        + "grabAvgMs=%.2f; grabMaxMs=%.2f; videoGrabAvgMs=%.2f; videoGrabMaxMs=%.2f; audioGrabAvgMs=%.2f; audioGrabMaxMs=%.2f; "
                        + "ptsGapMs=%.2f; ptsOutOfOrder=%d; lateAtGrabFrames=%d; lateAtGrabAvgMs=%.2f; lateAtGrabMaxMs=%.2f; "
                        + "lateBeforeGrabFrames=%d; lateBeforeGrabAvgMs=%.2f; lateBeforeGrabMaxMs=%.2f; "
                        + "lateFrames=%d; lateAvgMs=%.2f; lateMaxMs=%.2f; lateWindowTotalMs=%.2f; lateCumulativeMs=%.2f; "
                        + "convertAvgMs=%.2f; uploadAvgMs=%.2f; audioKiBps=%.1f; audioWriteAvgMs=%.2f; audioDroppedKiB=%.1f",
                elapsedNanos / 1_000_000L, sourceFps, submittedFps, presentedFps, lastMasterClock, lastSyncAction,
                syncWaitDelta, syncPresentedDelta, syncDroppedDelta, busyDelta, surfaceDelta, rateLimitedDelta,
                droppedForLatenessDelta, hardResyncDelta, hardResyncDelta, hardResyncAheadDelta, hardResyncBehindDelta,
                hardResyncWaitDelta, hardResyncDropDelta, hardResyncDroppedFramesDelta, hardResyncRecoveryDelta,
                grabMs, greatestGrabNanos / 1_000_000d, videoGrabMs, greatestVideoGrabNanos / 1_000_000d,
                audioGrabMs, greatestAudioGrabNanos / 1_000_000d, sourceGapMs, outOfOrderTimestamps,
                lateAtGrabReturnFramesDelta, lateAtGrabReturnMs, greatestLateAtGrabReturnNanos / 1_000_000d,
                lateBeforeGrabFramesDelta, lateBeforeGrabMs, greatestLateBeforeGrabNanos / 1_000_000d,
                lateFramesDelta, lateMs, greatestLatenessNanos / 1_000_000d,
                lateNanosDelta / 1_000_000d, lateNanos / 1_000_000d,
                convertMs, uploadMs, audioKiBps, audioWriteMs, discardedAudioBytesDelta / 1_024d);
        return base + String.format(Locale.ROOT,
                "; firstFrameNanos=%d; currentSystemNanos=%d; mediaClockMs=%.2f; wallClockElapsedMs=%.2f; expectedPtsElapsedMs=%.2f; clockDriftMs=%.2f"
                        + "; firstVideoPtsMs=%.2f; currentVideoPtsMs=%.2f; previousVideoPtsMs=%.2f; videoPtsRelativeMs=%.2f; videoPtsGapMs=%.2f"
                        + "; videoVsMediaClockMs=%.2f; audioClockMs=%.2f; audioPlaybackPositionMs=%.2f; videoVsAudioClockMs=%.2f; audioVsMediaClockMs=%.2f"
                        + "; schedulerTargetMs=%.2f; schedulerNowMs=%.2f; schedulerRemainingAtEntryAvgMs=%.2f; schedulerWaitAvgMs=%.2f; schedulerWaitMaxMs=%.2f; schedulerLateAvgMs=%.2f; schedulerLateMaxMs=%.2f"
                        + "; latenessBeforeGrabAvgMs=%.2f; latenessAfterGrabAvgMs=%.2f; latenessBeforeConvertAvgMs=%.2f; latenessAfterConvertAvgMs=%.2f; latenessBeforeQueueAvgMs=%.2f; latenessBeforePresentAvgMs=%.2f; latenessAfterPresentAvgMs=%.2f"
                        + "; queueSize=%d; queueMaxSize=%d; queueOldestPtsMs=%.2f; queueNewestPtsMs=%.2f; queueDurationMs=%.2f; pendingSurfaceAgeMs=%.2f; frameDeliveryQueued=%s; queueReplaced=%d"
                        + "; surfaceCreatedAtMs=%.2f; surfaceQueuedAtMs=%.2f; surfacePresentedAtMs=%.2f; decodeToQueueAvgMs=%.2f; decodeToQueueMaxMs=%.2f; queueToUiSubmitAvgMs=%.2f; queueToUiSubmitMaxMs=%.2f; uiSubmitToPresentAvgMs=%.2f; uiSubmitToPresentMaxMs=%.2f; decodeToPresentAvgMs=%.2f; decodeToPresentMaxMs=%.2f; queueToPresentAvgMs=%.2f; queueToPresentMaxMs=%.2f"
                        + "; runLaterDelayAvgMs=%.2f; runLaterDelayMaxMs=%.2f; toFXImageAvgMs=%.2f; toFXImageMaxMs=%.2f; listenerOnFrameAvgMs=%.2f; listenerOnFrameMaxMs=%.2f; totalUiPresentAvgMs=%.2f; totalUiPresentMaxMs=%.2f"
                        + "; avSyncMs=%.2f; avSyncAvgMs=%.2f; avSyncMinMs=%.2f; avSyncMaxMs=%.2f"
                        + "; pendingAudioChunks=%d; audioQueuedBytes=%d; audioQueuedDurationMs=%.2f; audioPendingQueueMs=%.2f; audioBufferedMs=%.2f; audioDevicePlaybackPositionMs=%.2f; audioPlaybackPositionMs=%.2f; audioLongFramePosition=%d; audioQueueWaitAvgMs=%.2f; audioQueueWaitMaxMs=%.2f; audioWriteDurationAvgMs=%.2f; audioLineAvailableBytes=%d; audioLineBufferSizeBytes=%d",
                mediaFirstFrameNanos, mediaCurrentSystemNanos, mediaClockMs, wallClockElapsedMs, expectedPtsElapsedMs, clockDriftMs,
                firstVideoPtsMs, videoPtsMs, previousVideoPtsMs, videoPtsRelativeMs, videoPtsGapMs,
                videoVsMediaClockMs, audioClockMs, audioPlaybackPositionMs, videoVsAudioClockMs, audioVsMediaClockMs,
                lastSchedulerTargetNanos / 1_000_000d, lastSchedulerExitNanos / 1_000_000d, schedulerRemainingAtEntryMs,
                schedulerWaitMs, greatestSchedulerWaitNanos / 1_000_000d, schedulerLateMs, greatestSchedulerLateNanos / 1_000_000d,
                lateBeforeGrabMs, lateAtGrabReturnMs, lateBeforeConvertMs, lateAfterConvertMs, lateBeforeQueueMs,
                lateBeforePresentMs, lateAfterPresentMs,
                visualQueueSize, greatestVisualQueueSize, queueOldestPtsMs, queueNewestPtsMs, queueDurationMs,
                pendingSurfaceAgeMs, frameDeliveryQueued, queueReplacedDelta,
                surfaceCreatedAtMs, surfaceQueuedAtMs, surfacePresentedAtMs, decodeToQueueMs, greatestDecodeToQueueNanos / 1_000_000d,
                queueToUiSubmitMs, greatestQueueToUiSubmitNanos.get() / 1_000_000d,
                uiSubmitToPresentMs, greatestUiSubmitToPresentNanos.get() / 1_000_000d,
                decodeToPresentMs, greatestDecodeToPresentNanos.get() / 1_000_000d,
                queueToPresentMs, greatestQueueToPresentNanos.get() / 1_000_000d,
                runLaterDelayMs, greatestRunLaterDelayNanos.get() / 1_000_000d,
                toFxImageMs, greatestToFxImageNanos.get() / 1_000_000d,
                listenerOnFrameMs, greatestListenerOnFrameNanos.get() / 1_000_000d,
                totalUiPresentMs, greatestTotalUiPresentNanos.get() / 1_000_000d,
                avSyncMs, avSyncAvgMs, avSyncMinMs, avSyncMaxMs,
                pendingAudioChunks, audioQueuedBytes,
                audioQueuedDurationMicros < 0L ? -1d : audioQueuedDurationMicros / 1_000d,
                audioQueuedDurationMicros < 0L ? -1d : audioQueuedDurationMicros / 1_000d,
                audioBufferedMs, audioDevicePlaybackPositionMs, audioPlaybackPositionMs, audioLongFramePosition,
                audioQueueWaitMs, greatestAudioQueueWaitNanos.get() / 1_000_000d, audioWriteMs,
                audioLineAvailableBytes, audioLineBufferSizeBytes);
    }

    private static double averageMillis(long totalNanos, long count) {
        return count <= 0L ? 0d : totalNanos / (count * 1_000_000d);
    }

    private static double microsToMillis(long micros) {
        return micros < 0L ? -1d : micros / 1_000d;
    }

    private static double relativeMillis(long nanos, long baseNanos) {
        return nanos < 0L || baseNanos < 0L ? -1d : (nanos - baseNanos) / 1_000_000d;
    }

    private static void updateMaximum(AtomicLong maximum, long value) {
        long observed;
        while (value > (observed = maximum.get()) && !maximum.compareAndSet(observed, value)) {
            // A concorrencia aqui existe apenas entre a UI, o audio e a telemetria.
        }
    }
}
