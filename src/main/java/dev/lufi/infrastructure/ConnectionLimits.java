package dev.lufi.infrastructure;

/**
 * Limites globais de conexões de saída. Eles são distintos do limite por
 * torrent do bt-core e não mudam DHT, peças, seeding ou a semântica do
 * protocolo BitTorrent.
 */
public record ConnectionLimits(
        int maxOverlayConnections,
        int maxAssistConnections,
        int maxSeedConnections,
        int maxDownloadConnections,
        int maxPendingConnections,
        int maxTotalConnections) {

    public static final int DEFAULT_MAX_OVERLAY_CONNECTIONS = 12;
    public static final int DEFAULT_MAX_ASSIST_CONNECTIONS = 60;
    public static final int DEFAULT_MAX_SEED_CONNECTIONS = 24;
    public static final int DEFAULT_MAX_DOWNLOAD_CONNECTIONS = 32;
    public static final int DEFAULT_MAX_PENDING_CONNECTIONS = 24;
    public static final int DEFAULT_MAX_TOTAL_CONNECTIONS = 128;

    public ConnectionLimits {
        requirePositive(maxOverlayConnections, "maxOverlayConnections");
        requirePositive(maxAssistConnections, "maxAssistConnections");
        requirePositive(maxSeedConnections, "maxSeedConnections");
        requirePositive(maxDownloadConnections, "maxDownloadConnections");
        requirePositive(maxPendingConnections, "maxPendingConnections");
        requirePositive(maxTotalConnections, "maxTotalConnections");
        if (maxTotalConnections < maxDownloadConnections + maxSeedConnections) {
            throw new IllegalArgumentException("maxTotalConnections precisa reservar download e seed");
        }
        if (maxPendingConnections > maxTotalConnections) {
            throw new IllegalArgumentException("maxPendingConnections não pode exceder maxTotalConnections");
        }
    }

    public static ConnectionLimits defaults() {
        return new ConnectionLimits(DEFAULT_MAX_OVERLAY_CONNECTIONS, DEFAULT_MAX_ASSIST_CONNECTIONS,
                DEFAULT_MAX_SEED_CONNECTIONS, DEFAULT_MAX_DOWNLOAD_CONNECTIONS,
                DEFAULT_MAX_PENDING_CONNECTIONS, DEFAULT_MAX_TOTAL_CONNECTIONS);
    }

    public int categoryLimit(ConnectionRole role) {
        return switch (role) {
            case STREAM, DOWNLOAD, BACKGROUND_DOWNLOAD -> maxDownloadConnections;
            case SEED -> maxSeedConnections;
            case RENDEZVOUS, OVERLAY -> maxOverlayConnections;
            case ASSIST -> maxAssistConnections;
        };
    }

    /** Reserva uma vaga de aquisição para que um stream iniciado pelo usuário não fique atrás de downloads em lote. */
    public int streamReserveConnections() { return maxDownloadConnections > 1 ? 1 : 0; }

    /**
     * Download mantido em segundo plano não deve ocupar toda a janela que o
     * magnet em primeiro plano precisa para montar seu buffer inicial.
     */
    public int backgroundDownloadConnections() { return Math.min(8, maxDownloadConnections); }

    private static void requirePositive(int value, String name) {
        if (value < 1) throw new IllegalArgumentException(name + " deve ser maior que zero");
    }
}
