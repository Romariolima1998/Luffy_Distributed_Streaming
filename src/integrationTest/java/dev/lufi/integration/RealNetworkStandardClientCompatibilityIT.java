package dev.lufi.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.lufi.infrastructure.BtTorrentGateway;
import dev.lufi.infrastructure.P2pDiagnostics;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Teste controlado entre redes reais. A maquina B deve usar qBittorrent,
 * uTorrent ou outro cliente BitTorrent padrao para semear teste-publico.txt.
 * Nenhuma extensao Luffy e exigida do peer remoto.
 */
@Tag("real-network")
class RealNetworkStandardClientCompatibilityIT {
    private static final String EXPECTED_CONTENT = "OLA LUFFY PUBLICO";

    @Test void downloadsFromAStandardBitTorrentClientWithoutLuffyExtensions(@TempDir Path temporaryDirectory) throws Exception {
        String magnet = System.getenv("LUFFY_STANDARD_CLIENT_MAGNET");
        Assumptions.assumeTrue(magnet != null && magnet.startsWith("magnet:?"),
                "Defina LUFFY_STANDARD_CLIENT_MAGNET com o magnet semeado pelo qBittorrent/uTorrent na maquina B.");

        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<BtTorrentGateway.DiagnosticTestResult> result = new AtomicReference<>();
        P2pDiagnostics diagnostics = new P2pDiagnostics();
        try (BtTorrentGateway gateway = new BtTorrentGateway(temporaryDirectory.resolve("cache"), diagnostics)) {
            gateway.downloadDiagnosticTest(magnet, response -> {
                result.set(response);
                completed.countDown();
            });

            assertTrue(completed.await(55, TimeUnit.SECONDS), "O Luffy nao concluiu o teste publico dentro do prazo.");
        }

        assertNotNull(result.get(), "O gateway nao retornou o resultado do download publico.");
        assertTrue(result.get().contentVerified(), () -> "Resultado P2P: " + result.get().outcome() + " — " + result.get().detail());
        assertEquals(EXPECTED_CONTENT, result.get().content().trim(), "O arquivo recebido precisa ser o teste semeado pelo cliente padrao.");

        String log = diagnostics.snapshot();
        assertTrue(log.contains("DHT] PEER DISCOVERED") || log.contains("TRACKER PEER DISCOVERED"),
                "O peer padrao deve ter sido descoberto por DHT ou tracker. Log:\n" + log);
        assertTrue(log.contains("HANDSHAKE COMPLETE"), "O handshake BitTorrent padrao precisa ser concluido. Log:\n" + log);
        assertTrue(log.contains("metadados recebidos"), "Os metadados precisam vir pelo protocolo BitTorrent. Log:\n" + log);
        assertTrue(log.contains("PIECE VERIFIED"), "Ao menos uma piece precisa ser verificada. Log:\n" + log);
        assertTrue(!log.contains("[LF_ROUTE] FIND_NODE") && !log.contains("[LF_RENDEZVOUS] STARTED"),
                "O teste com cliente padrao nao pode depender de lf_route ou lf_rendezvous. Log:\n" + log);
    }
}
