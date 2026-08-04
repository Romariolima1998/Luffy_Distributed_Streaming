package dev.lufi.infrastructure;

import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Decide a admissão de novas conexões de saída sem abrir sockets. O controle
 * de menor prioridade deixa capacidade reservada para conteúdo do usuário.
 */
public final class GlobalConnectionBudget {
    public enum AdmissionReason {
        ADMITTED,
        ALREADY_ACCOUNTED,
        CATEGORY_LIMIT,
        PENDING_LIMIT,
        RESERVED_FOR_HIGHER_PRIORITY,
        TOTAL_LIMIT
    }

    /** Uma fotografia de conexão útil; a chave deve identificar o peer no torrent. */
    public record Slot(String key, ConnectionRole role, boolean pending) {
        public Slot {
            if (key == null || key.isBlank()) throw new IllegalArgumentException("chave de conexão ausente");
            Objects.requireNonNull(role, "role");
        }
    }

    public record Snapshot(int total, int pending, Map<ConnectionRole, Integer> byRole) {
        public Snapshot {
            byRole = byRole == null ? Map.of() : Map.copyOf(byRole);
        }
        public int count(ConnectionRole role) { return byRole.getOrDefault(role, 0); }
    }

    public record Decision(boolean admitted, AdmissionReason reason, ConnectionRole role,
                           Snapshot snapshot, int categoryLimit, int reservedForHigherPriority) { }

    private volatile ConnectionLimits limits = ConnectionLimits.defaults();

    public void setLimits(ConnectionLimits value) { limits = value == null ? ConnectionLimits.defaults() : value; }
    public ConnectionLimits limits() { return limits; }

    public Decision admit(ConnectionRole requested, String candidateKey, Collection<Slot> existing) {
        Objects.requireNonNull(requested, "requested");
        if (candidateKey == null || candidateKey.isBlank()) throw new IllegalArgumentException("chave de candidato ausente");
        Snapshot snapshot = snapshot(existing);
        ConnectionLimits current = limits;
        boolean alreadyAccounted = existing != null && existing.stream().anyMatch(slot -> candidateKey.equals(slot.key()));
        if (alreadyAccounted) return new Decision(true, AdmissionReason.ALREADY_ACCOUNTED, requested, snapshot,
                current.categoryLimit(requested), reserveForHigherPriority(requested, current));

        int categoryLimit = categoryLimit(snapshot, requested, current);
        if (countCategory(snapshot, requested) >= categoryLimit) {
            return new Decision(false, AdmissionReason.CATEGORY_LIMIT, requested, snapshot, categoryLimit,
                    reserveForHigherPriority(requested, current));
        }
        if (snapshot.pending() >= current.maxPendingConnections()) {
            return new Decision(false, AdmissionReason.PENDING_LIMIT, requested, snapshot, categoryLimit,
                    reserveForHigherPriority(requested, current));
        }
        int reserved = reserveForHigherPriority(requested, current);
        if (snapshot.total() >= current.maxTotalConnections()) {
            return new Decision(false, AdmissionReason.TOTAL_LIMIT, requested, snapshot, categoryLimit, reserved);
        }
        if (snapshot.total() >= current.maxTotalConnections() - reserved) {
            return new Decision(false, AdmissionReason.RESERVED_FOR_HIGHER_PRIORITY, requested, snapshot, categoryLimit, reserved);
        }
        return new Decision(true, AdmissionReason.ADMITTED, requested, snapshot, categoryLimit, reserved);
    }

    public Snapshot snapshot(Collection<Slot> slots) {
        Map<String, Slot> unique = new HashMap<>();
        if (slots != null) for (Slot slot : slots) {
            if (slot == null) continue;
            Slot prior = unique.get(slot.key());
            if (prior == null || (!slot.pending() && prior.pending())) unique.put(slot.key(), slot);
        }
        EnumMap<ConnectionRole, Integer> byRole = new EnumMap<>(ConnectionRole.class);
        int pending = 0;
        for (Slot slot : unique.values()) {
            byRole.merge(slot.role(), 1, Integer::sum);
            if (slot.pending()) pending++;
        }
        return new Snapshot(unique.size(), pending, byRole);
    }

    private int countCategory(Snapshot snapshot, ConnectionRole requested) {
        if (requested.isUserTransfer()) return snapshot.count(ConnectionRole.STREAM) + snapshot.count(ConnectionRole.DOWNLOAD);
        if (requested.isOverlayControl()) return snapshot.count(ConnectionRole.RENDEZVOUS) + snapshot.count(ConnectionRole.OVERLAY);
        return snapshot.count(requested);
    }

    private int categoryLimit(Snapshot snapshot, ConnectionRole requested, ConnectionLimits current) {
        if (requested == ConnectionRole.DOWNLOAD && snapshot.count(ConnectionRole.STREAM) == 0) {
            return Math.max(1, current.maxDownloadConnections() - current.streamReserveConnections());
        }
        return current.categoryLimit(requested);
    }

    private int reserveForHigherPriority(ConnectionRole requested, ConnectionLimits current) {
        return switch (requested) {
            case STREAM, DOWNLOAD -> 0;
            case SEED, RENDEZVOUS -> current.maxDownloadConnections();
            case OVERLAY -> current.maxDownloadConnections() + current.maxSeedConnections();
            case ASSIST -> current.maxDownloadConnections() + current.maxSeedConnections() + current.maxOverlayConnections();
        };
    }
}
