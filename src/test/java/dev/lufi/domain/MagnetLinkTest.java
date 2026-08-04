package dev.lufi.domain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MagnetLinkTest {
    @Test void acceptsAndNormalizesAValidBtih() {
        var magnet = MagnetLink.parse("magnet:?xt=urn:btih:0123456789ABCDEF0123456789ABCDEF01234567&dn=Film");
        assertEquals("0123456789abcdef0123456789abcdef01234567", magnet.infoHash());
        assertEquals("Film", magnet.displayName().orElseThrow());
    }
    @Test void rejectsMissingOrInvalidHash() {
        assertThrows(IllegalArgumentException.class, () -> MagnetLink.parse("magnet:?dn=Film"));
        assertThrows(IllegalArgumentException.class, () -> MagnetLink.parse("magnet:?xt=urn:btih:abc"));
    }
}

