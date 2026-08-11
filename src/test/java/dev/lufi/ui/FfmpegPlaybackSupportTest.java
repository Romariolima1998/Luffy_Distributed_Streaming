package dev.lufi.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class FfmpegPlaybackSupportTest {
    @Test void usesTheIntegratedDecoderForMp4AndMkv() {
        assertTrue(FfmpegPlaybackSupport.isRequiredFor(Path.of("sample.mp4")));
        assertTrue(FfmpegPlaybackSupport.isRequiredFor(Path.of("sample.mkv")));
    }

    @Test void leavesUnknownFilesOutsideTheVideoDecoder() {
        assertFalse(FfmpegPlaybackSupport.isRequiredFor(Path.of("notes.txt")));
    }
}
