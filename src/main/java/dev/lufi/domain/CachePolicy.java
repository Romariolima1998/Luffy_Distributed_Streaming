package dev.lufi.domain;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/** Calcula remoções LRU sem tocar no conteúdo que pertence a uma biblioteca do usuário. */
public final class CachePolicy {
    public record CachedItem(String infoHash, long bytes, Instant lastAccessed, boolean userLibraryFile) { }
    public List<CachedItem> evictions(long usedBytes, long limitBytes, List<CachedItem> items) {
        if (usedBytes <= limitBytes) return List.of();
        long excess = usedBytes - limitBytes;
        long[] reclaimed = {0};
        return items.stream().filter(item -> !item.userLibraryFile()).sorted(Comparator.comparing(CachedItem::lastAccessed))
                .takeWhile(item -> { if (reclaimed[0] >= excess) return false; reclaimed[0] += item.bytes(); return true; }).toList();
    }
}

