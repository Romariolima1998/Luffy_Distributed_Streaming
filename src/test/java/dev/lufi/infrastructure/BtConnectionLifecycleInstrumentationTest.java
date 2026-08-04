package dev.lufi.infrastructure;

import bt.runtime.BtRuntime;
import bt.runtime.Config;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BtConnectionLifecycleInstrumentationTest {
    @Test void installsOnTheBtRuntimeBeforeClientsStart() {
        BtRuntime runtime = BtRuntime.builder(new Config()).disableAutomaticShutdown().build();
        try (PeerConnectivityManager manager = new PeerConnectivityManager(new P2pDiagnostics(), ignored -> { })) {
            assertTrue(BtConnectionLifecycleInstrumentation.install(runtime, manager, new P2pDiagnostics()));
        } finally {
            runtime.shutdown();
        }
    }
}
