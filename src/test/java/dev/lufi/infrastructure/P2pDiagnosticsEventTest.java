package dev.lufi.infrastructure;

import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class P2pDiagnosticsEventTest {
    @Test void rendersStructuredEventsAndAggregatesOnlyLocalMetrics() {
        P2pDiagnostics diagnostics = new P2pDiagnostics();

        diagnostics.event(P2pDiagnostics.Category.LF_ROUTE, "FIND_NODE_START",
                "requestId", "a1b2c3d4", "targetNodeId", "node-123", "ttl", 4);
        diagnostics.event(P2pDiagnostics.Category.LF_ROUTE, "FIND_NODE_START",
                "requestId", "e5f6g7h8", "targetNodeId", "node-456", "ttl", 4);

        assertTrue(diagnostics.snapshot().contains("[LF-ROUTE] event=FIND_NODE_START requestId=a1b2c3d4 targetNodeId=node-123 ttl=4"));
        assertEquals(2L, diagnostics.metricsSnapshot().get("LF-ROUTE.FIND_NODE_START"));
        diagnostics.clear();
        assertTrue(diagnostics.metricsSnapshot().isEmpty());
    }

    @Test void rejectsMalformedFieldsAndSanitizesControlCharacters() {
        P2pDiagnostics diagnostics = new P2pDiagnostics();
        diagnostics.event(P2pDiagnostics.Category.LF_IDENTITY, "IDENTITY_ACCEPTED", "nodeId", "safe\nvalue");

        assertTrue(diagnostics.snapshot().contains("nodeId=safe_value"));
        assertThrows(IllegalArgumentException.class,
                () -> diagnostics.event(P2pDiagnostics.Category.LF_ROUTE, "not-valid", "key", "value"));
        assertThrows(IllegalArgumentException.class,
                () -> diagnostics.event(P2pDiagnostics.Category.LF_ROUTE, "FIND_NODE_START", "key"));
    }
}
