package dev.lufi.ui;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FfmpegPlaybackSupportTest {
    @Test void directsMkvToTheIntegratedFfmpegDecoderRegardlessOfFilenameCase() {
        assertTrue(FfmpegPlaybackSupport.isRequiredFor(Path.of("serie", "episodio.MKV")));
    }

    @Test void keepsNativeJavaFxPathForMp4() {
        assertFalse(FfmpegPlaybackSupport.isRequiredFor(Path.of("episodio.mp4")));
    }
}
