package dev.lufi.domain;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CachePolicyTest {
    @Test void evictsOldestCacheEntriesButNeverLibraryContent() {
        Instant now = Instant.now();
        var removed = new CachePolicy().evictions(300, 150, List.of(
                new CachePolicy.CachedItem("protected", 200, now.minusSeconds(100), true),
                new CachePolicy.CachedItem("old", 100, now.minusSeconds(50), false),
                new CachePolicy.CachedItem("new", 100, now, false)));
        assertEquals(List.of("old", "new"), removed.stream().map(CachePolicy.CachedItem::infoHash).toList());
    }
}
