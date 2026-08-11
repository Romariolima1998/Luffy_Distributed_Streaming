package dev.lufi.ui;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FfmpegPlaybackTelemetryTest {
    @Test void reportsTheSignalsNeededToDiagnoseFrameCadence() {
        FfmpegPlaybackTelemetry telemetry = new FfmpegPlaybackTelemetry(0L);
        telemetry.recordDecodedVideo(0L, 0L);
        telemetry.recordGrab(3_000_000L, true, false);
        telemetry.recordSubmittedFrame(2_000_000L);
        telemetry.recordPresentedFrame(1_000_000L);
        telemetry.recordDecodedVideo(33_333L, 4_000_000L);
        telemetry.recordGrab(5_000_000L, false, true);
        telemetry.recordLatenessAtGrabReturn(4_000_000L, 1_000_000L);
        telemetry.recordMediaClock(1_000_000L, 41_000_000L, 33_333L);
        telemetry.recordVideoPts(1_000_000L, 0L);
        telemetry.recordVideoPts(1_033_333L, 33_333L);
        telemetry.recordScheduler(1_000_000L, 34_333_000L, 30_000_000L, 35_000_000L);
        telemetry.recordLatenessBeforeConvert(2_000_000L);
        telemetry.recordLatenessAfterConvert(3_000_000L);
        telemetry.recordLatenessBeforeQueue(4_000_000L);
        telemetry.recordAudioClockAnchor(0L);
        telemetry.recordAudioState(2, 4_096, 192_000, 8_192, 16_384, 33_333L, 1_600L, 33_333L);
        telemetry.recordQueuedSurface(10_000_000L, 11_000_000L, 12_000_000L, 1_033_333L, 33_333L, 1, false);
        telemetry.recordVisualQueueState(1, 1_033_333L, 1_033_333L, 1_000_000L, true);
        telemetry.recordUiSubmit(12_000_000L, 14_000_000L);
        telemetry.recordPresentedSurface(10_000_000L, 12_000_000L, 14_000_000L, 16_000_000L);
        telemetry.recordLatenessBeforePresent(5_000_000L);
        telemetry.recordLatenessAfterPresent(6_000_000L);
        telemetry.recordRunLaterDelay(7_000_000L);
        telemetry.recordUiPresentation(2_000_000L, 3_000_000L, 6_000_000L);
        telemetry.recordAudioQueueWait(8_000_000L);
        telemetry.recordUiSkippedFrame();
        telemetry.recordSurfaceSkippedFrame();
        telemetry.recordRateLimitedFrame();
        telemetry.recordDroppedForLateness();
        telemetry.recordSyncDecision(FfmpegPlaybackTelemetry.MasterClock.AUDIO,
                FfmpegPlaybackTelemetry.SyncAction.WAIT, false);
        telemetry.recordSyncDecision(FfmpegPlaybackTelemetry.MasterClock.AUDIO,
                FfmpegPlaybackTelemetry.SyncAction.PRESENT, false);
        telemetry.recordSyncDecision(FfmpegPlaybackTelemetry.MasterClock.AUDIO,
                FfmpegPlaybackTelemetry.SyncAction.DROP, true);
        telemetry.recordSyncDecision(FfmpegPlaybackTelemetry.MasterClock.AUDIO,
                FfmpegPlaybackTelemetry.SyncAction.RESYNC, true);
        telemetry.recordHardResync(-300_000L, true);
        telemetry.recordHardResyncDrop();
        telemetry.recordHardResyncRecovery();
        telemetry.recordAudioWrite(4_096, 500_000L);

        String report = telemetry.reportIfDue(2_000_000_000L);

        assertNotNull(report);
        assertTrue(report.contains("sourceFps="));
        assertTrue(report.contains("uiSkipped=1"));
        assertTrue(report.contains("surfaceSkipped=1"));
        assertTrue(report.contains("rateLimited=1"));
        assertTrue(report.contains("droppedForLateness=1"));
        assertTrue(report.contains("masterClock=AUDIO"));
        assertTrue(report.contains("syncAction=RESYNC"));
        assertTrue(report.contains("syncWaitFrames=1"));
        assertTrue(report.contains("syncPresentedFrames=1"));
        assertTrue(report.contains("syncDroppedFrames=2"));
        assertTrue(report.contains("hardResync=1"));
        assertTrue(report.contains("hardResyncCount=1"));
        assertTrue(report.contains("hardResyncDrop=1"));
        assertTrue(report.contains("hardResyncDropped=1"));
        assertTrue(report.contains("hardResyncRecovered=1"));
        assertTrue(report.contains("grabAvgMs="));
        assertTrue(report.contains("videoGrabAvgMs="));
        assertTrue(report.contains("audioGrabAvgMs="));
        assertTrue(report.contains("lateAtGrabAvgMs="));
        assertTrue(report.contains("lateBeforeGrabAvgMs="));
        assertTrue(report.contains("lateCumulativeMs="));
        assertTrue(report.contains("lateFrames=1"));
        assertTrue(report.contains("audioKiBps="));
        assertTrue(report.contains("clockDriftMs="));
        assertTrue(report.contains("videoVsMediaClockMs="));
        assertTrue(report.contains("schedulerWaitAvgMs="));
        assertTrue(report.contains("queueToPresentAvgMs="));
        assertTrue(report.contains("audioLineBufferSizeBytes="));
        assertTrue(report.contains("runLaterDelayAvgMs="));
        assertTrue(report.contains("toFXImageAvgMs="));
        assertTrue(report.contains("avSyncAvgMs="));
        assertTrue(report.contains("audioQueueWaitAvgMs="));
    }
}
