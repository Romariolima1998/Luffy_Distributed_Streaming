package dev.lufi.infrastructure;

/** Configuração persistida e sobrescrevível por propriedade Java dos limites globais. */
public final class ConnectionLimitSettings {
    public static final String MAX_OVERLAY_CONNECTIONS_KEY = "connections.max.overlay";
    public static final String MAX_ASSIST_CONNECTIONS_KEY = "connections.max.assist";
    public static final String MAX_SEED_CONNECTIONS_KEY = "connections.max.seed";
    public static final String MAX_DOWNLOAD_CONNECTIONS_KEY = "connections.max.download";
    public static final String MAX_PENDING_CONNECTIONS_KEY = "connections.max.pending";
    public static final String MAX_TOTAL_CONNECTIONS_KEY = "connections.max.total";

    private static final String PROPERTY_PREFIX = "luffy.connections.max.";
    private final SettingsRepository settings;

    public ConnectionLimitSettings(SettingsRepository settings) { this.settings = java.util.Objects.requireNonNull(settings, "settings"); }

    public ConnectionLimits limits() {
        int overlay = value(MAX_OVERLAY_CONNECTIONS_KEY, "overlay", ConnectionLimits.DEFAULT_MAX_OVERLAY_CONNECTIONS);
        int assist = value(MAX_ASSIST_CONNECTIONS_KEY, "assist", ConnectionLimits.DEFAULT_MAX_ASSIST_CONNECTIONS);
        int seed = value(MAX_SEED_CONNECTIONS_KEY, "seed", ConnectionLimits.DEFAULT_MAX_SEED_CONNECTIONS);
        int download = value(MAX_DOWNLOAD_CONNECTIONS_KEY, "download", ConnectionLimits.DEFAULT_MAX_DOWNLOAD_CONNECTIONS);
        int pending = value(MAX_PENDING_CONNECTIONS_KEY, "pending", ConnectionLimits.DEFAULT_MAX_PENDING_CONNECTIONS);
        int total = value(MAX_TOTAL_CONNECTIONS_KEY, "total", ConnectionLimits.DEFAULT_MAX_TOTAL_CONNECTIONS);
        total = Math.max(total, download + seed);
        pending = Math.min(pending, total);
        return new ConnectionLimits(overlay, assist, seed, download, pending, total);
    }

    public int maxOverlayConnections() { return limits().maxOverlayConnections(); }
    public int maxAssistConnections() { return limits().maxAssistConnections(); }
    public int maxSeedConnections() { return limits().maxSeedConnections(); }
    public int maxDownloadConnections() { return limits().maxDownloadConnections(); }
    public int maxPendingConnections() { return limits().maxPendingConnections(); }
    public int maxTotalConnections() { return limits().maxTotalConnections(); }

    public void setMaxOverlayConnections(int value) { put(MAX_OVERLAY_CONNECTIONS_KEY, value); }
    public void setMaxAssistConnections(int value) { put(MAX_ASSIST_CONNECTIONS_KEY, value); }
    public void setMaxSeedConnections(int value) { put(MAX_SEED_CONNECTIONS_KEY, value); }
    public void setMaxDownloadConnections(int value) { put(MAX_DOWNLOAD_CONNECTIONS_KEY, value); }
    public void setMaxPendingConnections(int value) { put(MAX_PENDING_CONNECTIONS_KEY, value); }
    public void setMaxTotalConnections(int value) { put(MAX_TOTAL_CONNECTIONS_KEY, value); }

    private int value(String key, String propertySuffix, int fallback) {
        String configured = settings.get(key).orElse(System.getProperty(PROPERTY_PREFIX + propertySuffix, ""));
        try {
            int parsed = Integer.parseInt(configured.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private void put(String key, int value) {
        if (value < 1) throw new IllegalArgumentException("O limite de conexões deve ser maior que zero.");
        settings.put(key, Integer.toString(value));
    }
}
