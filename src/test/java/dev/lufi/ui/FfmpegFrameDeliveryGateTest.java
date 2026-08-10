package dev.lufi.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FfmpegFrameDeliveryGateTest {
    @Test void limitsVisualUpdatesToTheConfiguredFrameRate() {
        FfmpegFrameDeliveryGate gate = new FfmpegFrameDeliveryGate(30);

        assertTrue(gate.tryAcquire(0));
        assertFalse(gate.tryAcquire(33_333_332));
        assertTrue(gate.tryAcquire(33_333_333));
    }

    @Test void rejectsAnInvalidFrameRate() {
        assertThrows(IllegalArgumentException.class, () -> new FfmpegFrameDeliveryGate(0));
        assertThrows(IllegalArgumentException.class, () -> new FfmpegFrameDeliveryGate(121));
    }
}
