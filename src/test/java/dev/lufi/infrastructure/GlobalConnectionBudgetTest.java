package dev.lufi.infrastructure;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobalConnectionBudgetTest {
    @Test void reservesCapacityForUserTransfersBeforeAdmittingSwarmAssist() {
        GlobalConnectionBudget budget = budget(new ConnectionLimits(3, 8, 2, 4, 8, 12));
        List<GlobalConnectionBudget.Slot> occupied = List.of(
                slot("seed-1", ConnectionRole.SEED), slot("seed-2", ConnectionRole.SEED),
                slot("overlay-1", ConnectionRole.OVERLAY), slot("overlay-2", ConnectionRole.OVERLAY), slot("overlay-3", ConnectionRole.OVERLAY),
                slot("assist-1", ConnectionRole.ASSIST), slot("assist-2", ConnectionRole.ASSIST),
                slot("assist-3", ConnectionRole.ASSIST), slot("assist-4", ConnectionRole.ASSIST));

        var assist = budget.admit(ConnectionRole.ASSIST, "assist-new", occupied);
        var stream = budget.admit(ConnectionRole.STREAM, "stream-new", occupied);

        assertFalse(assist.admitted());
        assertEquals(GlobalConnectionBudget.AdmissionReason.RESERVED_FOR_HIGHER_PRIORITY, assist.reason());
        assertTrue(stream.admitted());
    }

    @Test void sharesDownloadCapBetweenStreamingAndDownloads() {
        GlobalConnectionBudget budget = budget(new ConnectionLimits(3, 8, 2, 2, 8, 16));
        List<GlobalConnectionBudget.Slot> occupied = List.of(
                slot("stream-1", ConnectionRole.STREAM), slot("download-1", ConnectionRole.DOWNLOAD));

        var decision = budget.admit(ConnectionRole.DOWNLOAD, "download-2", occupied);

        assertFalse(decision.admitted());
        assertEquals(GlobalConnectionBudget.AdmissionReason.CATEGORY_LIMIT, decision.reason());
    }

    @Test void holdsOneUserTransferSlotForAStreamBeforeAdmittingBatchDownloads() {
        GlobalConnectionBudget budget = budget(new ConnectionLimits(3, 8, 2, 4, 8, 16));
        List<GlobalConnectionBudget.Slot> occupied = List.of(
                slot("download-1", ConnectionRole.DOWNLOAD), slot("download-2", ConnectionRole.DOWNLOAD),
                slot("download-3", ConnectionRole.DOWNLOAD));

        var fourthDownload = budget.admit(ConnectionRole.DOWNLOAD, "download-4", occupied);
        var stream = budget.admit(ConnectionRole.STREAM, "stream-1", occupied);

        assertFalse(fourthDownload.admitted());
        assertEquals(GlobalConnectionBudget.AdmissionReason.CATEGORY_LIMIT, fourthDownload.reason());
        assertTrue(stream.admitted());
    }

    @Test void preventsConnectionStormsWithThePendingBudget() {
        GlobalConnectionBudget budget = budget(new ConnectionLimits(3, 8, 2, 4, 2, 16));
        List<GlobalConnectionBudget.Slot> occupied = List.of(
                pending("pending-1", ConnectionRole.DOWNLOAD), pending("pending-2", ConnectionRole.DOWNLOAD));

        var decision = budget.admit(ConnectionRole.SEED, "seed-new", occupied);

        assertFalse(decision.admitted());
        assertEquals(GlobalConnectionBudget.AdmissionReason.PENDING_LIMIT, decision.reason());
    }

    @Test void doesNotChargeATcpToUtpRetryTwiceWhenItRepresentsTheSameSlot() {
        GlobalConnectionBudget budget = budget(ConnectionLimits.defaults());
        List<GlobalConnectionBudget.Slot> occupied = List.of(slot("torrent|IPV4|TCP|203.0.113.10|6891", ConnectionRole.DOWNLOAD));

        var decision = budget.admit(ConnectionRole.DOWNLOAD, "torrent|IPV4|TCP|203.0.113.10|6891", occupied);

        assertTrue(decision.admitted());
        assertEquals(GlobalConnectionBudget.AdmissionReason.ALREADY_ACCOUNTED, decision.reason());
    }

    private static GlobalConnectionBudget budget(ConnectionLimits limits) {
        GlobalConnectionBudget budget = new GlobalConnectionBudget();
        budget.setLimits(limits);
        return budget;
    }

    private static GlobalConnectionBudget.Slot slot(String key, ConnectionRole role) {
        return new GlobalConnectionBudget.Slot(key, role, false);
    }

    private static GlobalConnectionBudget.Slot pending(String key, ConnectionRole role) {
        return new GlobalConnectionBudget.Slot(key, role, true);
    }
}
