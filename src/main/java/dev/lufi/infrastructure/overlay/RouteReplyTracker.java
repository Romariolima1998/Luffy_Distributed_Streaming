package dev.lufi.infrastructure.overlay;

import dev.lufi.infrastructure.identity.LuffyNodeId;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

/** Mantem apenas buscas iniciadas localmente ate sua resposta, erro ou timeout. */
final class RouteReplyTracker {
    private final Map<UUID, PendingReply> pending = new ConcurrentHashMap<>();

    CompletionStage<RouteSearchResult> track(UUID requestId, LuffyNodeId targetNodeId, Instant expiresAt, Duration timeout) {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(targetNodeId, "targetNodeId");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(timeout, "timeout");
        CompletableFuture<RouteSearchResult> future = new CompletableFuture<>();
        PendingReply previous = pending.putIfAbsent(requestId, new PendingReply(targetNodeId, expiresAt, future));
        if (previous != null) throw new IllegalStateException("requestId lf_route local duplicado");
        future.completeOnTimeout(new RouteSearchResult.RouteError(targetNodeId,
                        LuffyRouteMessage.RouteErrorCode.SEARCH_TIMEOUT),
                timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                .whenComplete((ignored, error) -> pending.remove(requestId));
        return future;
    }

    boolean complete(UUID requestId, RouteSearchResult result) {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(result, "result");
        PendingReply reply = pending.remove(requestId);
        if (reply == null || !reply.targetNodeId().equals(result.targetNodeId())) return false;
        return reply.future().complete(result);
    }

    int expire(Instant now) {
        Objects.requireNonNull(now, "now");
        int before = pending.size();
        pending.entrySet().removeIf(entry -> {
            PendingReply value = entry.getValue();
            if (value.expiresAt().isAfter(now)) return false;
            value.future().complete(new RouteSearchResult.RouteError(value.targetNodeId(),
                    LuffyRouteMessage.RouteErrorCode.EXPIRED));
            return true;
        });
        return Math.max(0, before - pending.size());
    }

    void clear() { pending.values().forEach(reply -> reply.future().cancel(false)); pending.clear(); }
    int size() { return pending.size(); }

    private record PendingReply(LuffyNodeId targetNodeId, Instant expiresAt, CompletableFuture<RouteSearchResult> future) { }
}
