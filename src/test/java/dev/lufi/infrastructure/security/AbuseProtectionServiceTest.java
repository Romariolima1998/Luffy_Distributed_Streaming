package dev.lufi.infrastructure.security;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AbuseProtectionServiceTest {
    @Test void validatesEveryConfigurableLimit() {
        assertThrows(IllegalArgumentException.class, () -> new AbuseProtectionConfig(0, 1, 1, 1, 1, 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new AbuseProtectionConfig(1, 1, 1, 1, 1, 1, 7, 1, 1));
    }

    @Test void floodIsBlockedTemporarilyAndCountersAreSeparatedByOperation() {
        AbuseProtectionService service = new AbuseProtectionService(new AbuseProtectionConfig(1, 2, 1, 1, 1, 512, 6, 4, 2));
        Instant now = Instant.parse("2026-08-04T12:00:00Z");

        assertTrue(service.allowFindNode("peer-a", now));
        assertTrue(service.allowForward("peer-a", now));
        assertFalse(service.allowFindNode("peer-a", now));
        assertFalse(service.isAllowed("peer-a", now));
        assertTrue(service.isAllowed("peer-a", now.plus(AbuseProtectionService.TEMPORARY_BLOCK).plusSeconds(1)));
    }

    @Test void enforcesAndReleasesConcurrentRouteAndRendezvousBudgets() {
        AbuseProtectionService service = new AbuseProtectionService(new AbuseProtectionConfig(10, 10, 1, 1, 2, 512, 6, 4, 2));
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        assertTrue(service.tryAcquireRouteSearch());
        assertFalse(service.tryAcquireRouteSearch());
        service.releaseRouteSearch();
        assertTrue(service.tryAcquireRouteSearch());

        assertTrue(service.tryAcquireRendezvousSession(first, Instant.now().plusSeconds(5)));
        assertFalse(service.tryAcquireRendezvousSession(second, Instant.now().plusSeconds(5)));
        service.releaseRendezvousSession(first);
        assertTrue(service.tryAcquireRendezvousSession(second, Instant.now().plusSeconds(5)));
    }

    @Test void invalidIdentityAndEndpointReceiveTemporaryPenalties() {
        AbuseProtectionService service = new AbuseProtectionService();
        Instant now = Instant.now();
        service.recordViolation("peer-b", AbuseProtectionService.Violation.IDENTITY_CHANGED, now);
        assertFalse(service.isAllowed("peer-b", now));
        service.recordViolation("peer-c", AbuseProtectionService.Violation.INVALID_ENDPOINT, now);
        service.recordViolation("peer-c", AbuseProtectionService.Violation.INVALID_ENDPOINT, now);
        service.recordViolation("peer-c", AbuseProtectionService.Violation.INVALID_ENDPOINT, now);
        assertFalse(service.isAllowed("peer-c", now));
    }
}
