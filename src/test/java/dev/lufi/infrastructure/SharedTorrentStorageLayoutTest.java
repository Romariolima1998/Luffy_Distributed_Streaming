package dev.lufi.infrastructure;

import dev.lufi.domain.MagnetLink;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SharedTorrentStorageLayoutTest {
    @Test
    void usesOneStableChildDirectoryForEachMagnetInfoHash() {
        Path root = Path.of("shared-downloads").toAbsolutePath();
        MagnetLink first = MagnetLink.parse("magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=First");
        MagnetLink sameTorrentWithAnotherName = MagnetLink.parse("magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=Renamed");
        MagnetLink second = MagnetLink.parse("magnet:?xt=urn:btih:89abcdef0123456789abcdef0123456789abcdef&dn=Second");

        Path firstDirectory = SharedTorrentStorageLayout.resolve(root, first);
        assertEquals(firstDirectory, SharedTorrentStorageLayout.resolve(root, sameTorrentWithAnotherName));
        assertNotEquals(firstDirectory, SharedTorrentStorageLayout.resolve(root, second));
        assertEquals(first.infoHash(), firstDirectory.getFileName().toString());
        assertTrue(firstDirectory.startsWith(root));
    }
}
