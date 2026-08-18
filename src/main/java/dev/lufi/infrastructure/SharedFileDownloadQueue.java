package dev.lufi.infrastructure;

import java.util.LinkedHashSet;
import java.util.Objects;

/** Ordered, de-duplicated queue for files selected in “Assistir e compartilhar”. */
final class SharedFileDownloadQueue {
    private final LinkedHashSet<String> waiting = new LinkedHashSet<>();
    private String active;

    /** Adds a file after the active one, preserving the first request order. */
    void enqueue(String path) {
        String requested = normalize(path);
        if (!requested.equals(active)) waiting.add(requested);
    }

    Selection select(String path, boolean keepCurrentInQueue) {
        String requested = normalize(path);
        String previous = active;
        if (requested.equals(previous)) return new Selection(previous, requested, waiting.size(), false);
        if (keepCurrentInQueue && previous != null) waiting.add(previous);
        waiting.remove(requested);
        active = requested;
        return new Selection(previous, requested, waiting.size(), true);
    }

    Advance complete(String path) {
        String completed = normalize(path);
        waiting.remove(completed);
        if (!Objects.equals(active, completed)) return new Advance(completed, active, waiting.size(), false);
        String next = waiting.stream().findFirst().orElse(null);
        if (next != null) waiting.remove(next);
        active = next;
        return new Advance(completed, next, waiting.size(), true);
    }

    String active() { return active; }

    private static String normalize(String path) {
        return StreamingFileSelection.normalize(Objects.requireNonNull(path, "path"));
    }

    record Selection(String previous, String active, int queuedFiles, boolean changed) { }
    record Advance(String completed, String next, int queuedFiles, boolean advanced) { }
}
