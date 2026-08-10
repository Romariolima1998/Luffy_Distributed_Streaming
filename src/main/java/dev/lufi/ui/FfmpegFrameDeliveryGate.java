package dev.lufi.ui;

/** Limita o trabalho visual do player sem afetar a decodificação ou o áudio. */
final class FfmpegFrameDeliveryGate {
    private final long intervalNanos;
    private long nextAllowedNanos;

    FfmpegFrameDeliveryGate(int maximumFramesPerSecond) {
        if (maximumFramesPerSecond < 1 || maximumFramesPerSecond > 120) {
            throw new IllegalArgumentException("maximumFramesPerSecond inválido");
        }
        intervalNanos = 1_000_000_000L / maximumFramesPerSecond;
    }

    /** Deve ser chamado apenas pela thread de decodificação. */
    boolean tryAcquire(long nowNanos) {
        if (nowNanos < nextAllowedNanos) {
            return false;
        }
        nextAllowedNanos = nowNanos + intervalNanos;
        return true;
    }
}
