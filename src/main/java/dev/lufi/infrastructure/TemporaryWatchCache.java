package dev.lufi.infrastructure;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicInteger;

/** Removes the private cache created for an “Assistir apenas” session. */
final class TemporaryWatchCache {
    private TemporaryWatchCache() { }

    static CleanupResult delete(Path root) {
        if (root == null || Files.notExists(root)) return new CleanupResult(0, true);
        AtomicInteger deletedEntries = new AtomicInteger();
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    if (Files.deleteIfExists(path)) deletedEntries.incrementAndGet();
                } catch (IOException ignored) {
                    // A result below reports whether a locked file prevented cleanup.
                }
            });
        } catch (IOException ignored) {
            // A result below reports whether the cache root could be removed.
        }
        return new CleanupResult(deletedEntries.get(), Files.notExists(root));
    }

    record CleanupResult(int deletedEntries, boolean removed) { }
}
