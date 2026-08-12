package dev.lufi.infrastructure;

/** Preferência persistente da margem contínua verificada antes do streaming começar. */
public final class StreamingStartupSettings {
    public static final String STARTUP_PIECES_KEY = "streaming.startup.verified-pieces";
    private static final String PROPERTY = "luffy.streaming.startup.verified-pieces";

    private final SettingsRepository settings;

    public StreamingStartupSettings(SettingsRepository settings) {
        this.settings = java.util.Objects.requireNonNull(settings, "settings");
    }

    public int startupPieces() {
        String configured = settings.get(STARTUP_PIECES_KEY).orElse(System.getProperty(PROPERTY, ""));
        try {
            return normalize(Integer.parseInt(configured.trim()));
        } catch (RuntimeException ignored) {
            return BtTorrentGateway.DEFAULT_STREAM_STARTUP_PIECES;
        }
    }

    public void setStartupPieces(int pieces) {
        settings.put(STARTUP_PIECES_KEY, Integer.toString(normalize(pieces)));
    }

    public static int normalize(int pieces) {
        return Math.max(BtTorrentGateway.MIN_STREAM_STARTUP_PIECES,
                Math.min(BtTorrentGateway.MAX_STREAM_STARTUP_PIECES, pieces));
    }
}
