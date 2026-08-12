package dev.lufi.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LibVlcRuntimeDiscoveryTest {
    @Test
    void acceptsTheWindowsX64Java21Mvp() {
        assertTrue(LibVlcRuntimeDiscovery.platformInfo("Windows 11", "amd64", "64", "21").supported());
    }

    @Test
    void acceptsTheLinuxX64Java21Mvp() {
        assertTrue(LibVlcRuntimeDiscovery.platformInfo("Linux", "x86_64", "64", "21").supported());
    }

    @Test
    void rejectsUnsupportedArchitectureOrRuntime() {
        assertFalse(LibVlcRuntimeDiscovery.platformInfo("Windows 11", "x86", "32", "21").supported());
        assertFalse(LibVlcRuntimeDiscovery.platformInfo("Linux", "amd64", "64", "22").supported());
        assertFalse(LibVlcRuntimeDiscovery.platformInfo("Mac OS X", "aarch64", "64", "21").supported());
    }
}
