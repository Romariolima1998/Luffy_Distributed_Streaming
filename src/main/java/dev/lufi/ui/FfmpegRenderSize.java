package dev.lufi.ui;

/** Dimensão de apresentação limitada à área útil do Luffy, sempre preservando a proporção do vídeo. */
record FfmpegRenderSize(int width, int height) {
    private static final int MAX_WIDTH = 960;
    private static final int MAX_HEIGHT = 540;

    static FfmpegRenderSize fit(int sourceWidth, int sourceHeight) {
        if (sourceWidth < 1 || sourceHeight < 1) throw new IllegalArgumentException("dimensão de vídeo inválida");
        double scale = Math.min(1d, Math.min((double) MAX_WIDTH / sourceWidth, (double) MAX_HEIGHT / sourceHeight));
        int width = even(Math.max(2, (int) Math.round(sourceWidth * scale)));
        int height = even(Math.max(2, (int) Math.round(sourceHeight * scale)));
        return new FfmpegRenderSize(width, height);
    }

    private static int even(int value) {
        return value % 2 == 0 ? value : value - 1;
    }
}
