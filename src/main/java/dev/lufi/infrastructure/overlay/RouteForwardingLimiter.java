package dev.lufi.infrastructure.overlay;

import bt.net.ConnectionKey;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Limita encaminhamentos por conexao e aplica backoff apos falha de envio. */
final class RouteForwardingLimiter {
    private final FindNodeRoutingConfig config;
    private final Map<ConnectionKey, State> states = new ConcurrentHashMap<>();

    RouteForwardingLimiter(FindNodeRoutingConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    boolean canForward(ConnectionKey connectionKey, Instant now) {
        Objects.requireNonNull(connectionKey, "connectionKey");
        Objects.requireNonNull(now, "now");
        State state = states.get(connectionKey);
        if (state == null) return true;
        return !state.backoffUntil().isAfter(now)
                && (state.windowStarted().plus(config.peerMessageWindow()).isBefore(now)
                || state.messagesInWindow() < config.maximumMessagesPerPeerWindow());
    }

    void recordForward(ConnectionKey connectionKey, Instant now) {
        update(connectionKey, now, false);
    }

    void recordFailure(ConnectionKey connectionKey, Instant now) {
        update(connectionKey, now, true);
    }

    void expire(Instant now) {
        states.entrySet().removeIf(entry -> !entry.getValue().backoffUntil().isAfter(now)
                && entry.getValue().windowStarted().plus(config.peerMessageWindow()).isBefore(now));
    }

    private void update(ConnectionKey key, Instant now, boolean failed) {
        states.compute(key, (ignored, existing) -> {
            Instant windowStarted = existing == null || existing.windowStarted().plus(config.peerMessageWindow()).isBefore(now)
                    ? now : existing.windowStarted();
            int messages = existing == null || !windowStarted.equals(existing.windowStarted())
                    ? 1 : existing.messagesInWindow() + 1;
            Instant backoffUntil = failed ? now.plus(config.peerBackoff())
                    : existing == null ? Instant.EPOCH : existing.backoffUntil();
            return new State(windowStarted, messages, backoffUntil);
        });
    }

    private record State(Instant windowStarted, int messagesInWindow, Instant backoffUntil) { }
}
