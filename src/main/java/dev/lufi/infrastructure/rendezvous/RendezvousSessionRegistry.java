package dev.lufi.infrastructure.rendezvous;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Registro limitado de sessoes vivas, com transicoes explicitas e limpeza terminal. */
public final class RendezvousSessionRegistry {
    private static final Map<RendezvousState, List<RendezvousState>> TRANSITIONS = transitions();
    private final Map<UUID, RendezvousSession> sessions = new ConcurrentHashMap<>();

    public Registration register(RendezvousSession session) {
        Objects.requireNonNull(session, "session");
        Holder holder = new Holder();
        sessions.compute(session.sessionId(), (ignored, existing) -> {
            if (existing == null) { holder.registration = Registration.CREATED; return session; }
            if (!sameSession(existing, session)) throw new IllegalArgumentException("sessionId rendezvous em conflito");
            holder.registration = Registration.DUPLICATE;
            return existing;
        });
        return holder.registration;
    }

    public Optional<RendezvousSession> find(UUID sessionId, Instant now) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(now, "now");
        RendezvousSession session = sessions.get(sessionId);
        if (session == null) return Optional.empty();
        if (session.expiresAt().isAfter(now)) return Optional.of(session);
        sessions.remove(sessionId, session);
        return Optional.empty();
    }

    public Transition transition(UUID sessionId, RendezvousState next, Instant now) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(next, "next");
        Objects.requireNonNull(now, "now");
        Holder holder = new Holder();
        sessions.computeIfPresent(sessionId, (ignored, current) -> {
            if (!current.expiresAt().isAfter(now)) { holder.transition = Transition.EXPIRED; return null; }
            if (current.state() == next) { holder.transition = Transition.IDEMPOTENT; return current; }
            if (!TRANSITIONS.getOrDefault(current.state(), List.of()).contains(next)) {
                holder.transition = Transition.INVALID; return current;
            }
            holder.transition = Transition.APPLIED;
            return current.transitionTo(next);
        });
        return holder.transition == null ? Transition.MISSING : holder.transition;
    }

    /** Estado final e removido imediatamente: mensagens duplicadas se tornam inofensivas. */
    public Optional<RendezvousSession> finish(UUID sessionId, RendezvousState terminal, Instant now) {
        if (!Objects.requireNonNull(terminal, "terminal").terminal()) {
            throw new IllegalArgumentException("finish exige estado terminal");
        }
        RendezvousSession session = sessions.remove(Objects.requireNonNull(sessionId, "sessionId"));
        if (session == null || !session.expiresAt().isAfter(Objects.requireNonNull(now, "now"))) return Optional.empty();
        return Optional.of(session.transitionTo(terminal));
    }

    public int expire(Instant now) {
        Objects.requireNonNull(now, "now");
        int before = sessions.size();
        sessions.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
        return Math.max(0, before - sessions.size());
    }

    public int size() { return sessions.size(); }
    public void clear() { sessions.clear(); }

    private static boolean sameSession(RendezvousSession left, RendezvousSession right) {
        return left.routeRequestId().equals(right.routeRequestId())
                && left.requesterNodeId().equals(right.requesterNodeId())
                && left.targetNodeId().equals(right.targetNodeId())
                && left.rendezvousNodeId().equals(right.rendezvousNodeId())
                && left.contentTorrentId().equals(right.contentTorrentId());
    }

    private static Map<RendezvousState, List<RendezvousState>> transitions() {
        Map<RendezvousState, List<RendezvousState>> result = new EnumMap<>(RendezvousState.class);
        result.put(RendezvousState.CREATED, List.of(RendezvousState.ROUTE_ESTABLISHED, RendezvousState.FAILED,
                RendezvousState.EXPIRED, RendezvousState.CANCELLED));
        result.put(RendezvousState.ROUTE_ESTABLISHED, List.of(RendezvousState.TARGET_CONFIRMED, RendezvousState.FAILED,
                RendezvousState.EXPIRED, RendezvousState.CANCELLED));
        result.put(RendezvousState.TARGET_CONFIRMED, List.of(RendezvousState.PREPARING, RendezvousState.FAILED,
                RendezvousState.EXPIRED, RendezvousState.CANCELLED));
        result.put(RendezvousState.PREPARING, List.of(RendezvousState.PUNCHING, RendezvousState.FAILED,
                RendezvousState.EXPIRED, RendezvousState.CANCELLED));
        result.put(RendezvousState.PUNCHING, List.of(RendezvousState.BITTORRENT_HANDSHAKING, RendezvousState.FAILED,
                RendezvousState.EXPIRED, RendezvousState.CANCELLED));
        result.put(RendezvousState.BITTORRENT_HANDSHAKING, List.of(RendezvousState.CONNECTED, RendezvousState.FAILED,
                RendezvousState.EXPIRED, RendezvousState.CANCELLED));
        return Map.copyOf(result);
    }

    private static final class Holder { private Registration registration; private Transition transition; }
    public enum Registration { CREATED, DUPLICATE }
    public enum Transition { APPLIED, IDEMPOTENT, INVALID, EXPIRED, MISSING }
}
