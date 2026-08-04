package dev.lufi.infrastructure;

import dev.lufi.infrastructure.security.AbuseProtectionConfig;

/** Configuracao persistida e sobrescrevivel pelos parametros -Dluffy.security.*. */
public final class AbuseProtectionSettings {
    private static final String PREFIX = "security.";
    private static final String PROPERTY_PREFIX = "luffy.security.";
    private final SettingsRepository settings;

    public AbuseProtectionSettings(SettingsRepository settings) { this.settings = java.util.Objects.requireNonNull(settings, "settings"); }

    public AbuseProtectionConfig config() {
        return new AbuseProtectionConfig(value("max.find-node-requests-per-minute", AbuseProtectionConfig.DEFAULT_MAX_FIND_NODE_REQUESTS_PER_MINUTE),
                value("max.forwarded-requests-per-minute", AbuseProtectionConfig.DEFAULT_MAX_FORWARDED_REQUESTS_PER_MINUTE),
                value("max.concurrent-route-searches", AbuseProtectionConfig.DEFAULT_MAX_CONCURRENT_ROUTE_SEARCHES),
                value("max.concurrent-rendezvous-sessions", AbuseProtectionConfig.DEFAULT_MAX_CONCURRENT_RENDEZVOUS_SESSIONS),
                value("max.rendezvous-requests-per-peer", AbuseProtectionConfig.DEFAULT_MAX_RENDEZVOUS_REQUESTS_PER_PEER),
                value("max.payload-bytes", AbuseProtectionConfig.DEFAULT_MAX_PAYLOAD_BYTES),
                value("max.ttl", AbuseProtectionConfig.defaults().maxTtl()),
                value("max.pending-utp-sessions", AbuseProtectionConfig.DEFAULT_MAX_PENDING_UTP_SESSIONS),
                value("max.pending-utp-sessions-per-address", AbuseProtectionConfig.DEFAULT_MAX_PENDING_UTP_SESSIONS_PER_ADDRESS));
    }

    public void setMaxFindNodeRequestsPerMinute(int value) { put("max.find-node-requests-per-minute", value); }
    public void setMaxForwardedRequestsPerMinute(int value) { put("max.forwarded-requests-per-minute", value); }
    public void setMaxConcurrentRouteSearches(int value) { put("max.concurrent-route-searches", value); }
    public void setMaxConcurrentRendezvousSessions(int value) { put("max.concurrent-rendezvous-sessions", value); }
    public void setMaxRendezvousRequestsPerPeer(int value) { put("max.rendezvous-requests-per-peer", value); }
    public void setMaxPayloadBytes(int value) { put("max.payload-bytes", value); }
    public void setMaxTtl(int value) { put("max.ttl", value); }
    public void setMaxPendingUtpSessions(int value) { put("max.pending-utp-sessions", value); }
    public void setMaxPendingUtpSessionsPerAddress(int value) { put("max.pending-utp-sessions-per-address", value); }

    private int value(String suffix, int fallback) {
        String configured = settings.get(PREFIX + suffix).orElse(System.getProperty(PROPERTY_PREFIX + suffix, ""));
        try {
            int parsed = Integer.parseInt(configured.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (RuntimeException ignored) { return fallback; }
    }
    private void put(String suffix, int value) {
        if (value < 1) throw new IllegalArgumentException("O limite de seguranca deve ser maior que zero.");
        settings.put(PREFIX + suffix, Integer.toString(value));
    }
}
