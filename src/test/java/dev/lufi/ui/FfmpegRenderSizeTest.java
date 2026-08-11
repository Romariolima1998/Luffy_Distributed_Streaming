package dev.lufi.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class FfmpegRenderSizeTest {
    @Test void limitsFullHdToTheUsefulPresentationSize() {
        assertEquals(new FfmpegRenderSize(960, 540), FfmpegRenderSize.fit(1_920, 1_080));
    }

    @Test void preservesSmallVideosWithoutUpscaling() {
        assertEquals(new FfmpegRenderSize(640, 480), FfmpegRenderSize.fit(640, 480));
    }

    @Test void preservesTheAspectRatioOfPortraitVideo() {
        assertEquals(new FfmpegRenderSize(304, 540), FfmpegRenderSize.fit(1_080, 1_920));
    }

    @Test void rejectsInvalidDimensions() {
        assertThrows(IllegalArgumentException.class, () -> FfmpegRenderSize.fit(0, 720));
    }
}
