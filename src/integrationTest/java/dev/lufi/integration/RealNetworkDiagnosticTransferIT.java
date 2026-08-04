package dev.lufi.integration;

import dev.lufi.infrastructure.BtTorrentGateway;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Teste de rede real, deliberadamente separado de src/test.
 * Requer uma máquina A já semeando teste.txt e LUFFY_REAL_MAGNET com o magnet dela.
 */
@Tag("real-network")
class RealNetworkDiagnosticTransferIT {
    @Test void downloadsOlaLuffyThroughTheConfiguredExternalSwarm(@TempDir Path temporaryDirectory) throws Exception {
        String magnet = System.getenv("LUFFY_REAL_MAGNET");
        assertTrue(magnet != null && magnet.startsWith("magnet:?"),
                "Defina LUFFY_REAL_MAGNET com o magnet de teste.txt fornecido pela máquina A.");
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<BtTorrentGateway.DiagnosticTestResult> result = new AtomicReference<>();

        try (BtTorrentGateway gateway = new BtTorrentGateway(temporaryDirectory.resolve("cache"))) {
            gateway.downloadDiagnosticTest(magnet, response -> {
                result.set(response);
                completed.countDown();
            });

            assertTrue(completed.await(55, TimeUnit.SECONDS), "A transferência não terminou dentro do prazo diagnóstico.");
        }

        assertNotNull(result.get(), "O gateway não retornou resultado da transferência.");
        assertTrue(result.get().contentVerified(), () -> "Resultado P2P: " + result.get().outcome() + " — " + result.get().detail());
        assertTrue(result.get().content().startsWith("OLA LUFFY"));
    }
}
