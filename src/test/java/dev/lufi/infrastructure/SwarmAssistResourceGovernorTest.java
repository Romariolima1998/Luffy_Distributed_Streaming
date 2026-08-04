package dev.lufi.infrastructure;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SwarmAssistResourceGovernorTest {
    @Test void keepsAssistOpportunisticBehindPlaybackAndUserDownloads() {
        SwarmAssistResourceGovernor governor = new SwarmAssistResourceGovernor();

        assertEquals(SwarmAssistResourceGovernor.AssistPermission.PERMITTED, governor.assistPermission());

        governor.beginUserDownload("0123456789abcdef0123456789abcdef01234567");
        assertEquals(SwarmAssistResourceGovernor.AssistPermission.DEFERRED_FOR_USER_DOWNLOAD, governor.assistPermission());

        governor.setForegroundPlayback(true);
        assertEquals(SwarmAssistResourceGovernor.AssistPermission.DEFERRED_FOR_PLAYBACK, governor.assistPermission());

        governor.setForegroundPlayback(false);
        assertEquals(SwarmAssistResourceGovernor.AssistPermission.DEFERRED_FOR_USER_DOWNLOAD, governor.assistPermission());

        governor.completeUserDownload("0123456789abcdef0123456789abcdef01234567");
        assertEquals(SwarmAssistResourceGovernor.AssistPermission.PERMITTED, governor.assistPermission());
    }
}
