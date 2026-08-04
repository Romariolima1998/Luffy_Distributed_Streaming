package dev.lufi.infrastructure;

import java.util.HashSet;
import java.util.Set;

/**
 * O Assist é sempre oportunista. A ordem efetiva de recursos é: reprodução,
 * download do usuário, seed, NAT traversal da sessão ativa e, por último,
 * Swarm Assist. Como Assist não transfere peças, ele só é pausado para as duas
 * atividades de primeiro plano que podem exigir banda/CPU imediatamente.
 */
final class SwarmAssistResourceGovernor {
    enum AssistPermission { PERMITTED, DEFERRED_FOR_PLAYBACK, DEFERRED_FOR_USER_DOWNLOAD }

    private boolean foregroundPlayback;
    private final Set<String> activeUserDownloads = new HashSet<>();

    synchronized void setForegroundPlayback(boolean active) { foregroundPlayback = active; }
    synchronized void beginUserDownload(String infoHash) { if (infoHash != null && !infoHash.isBlank()) activeUserDownloads.add(infoHash); }
    synchronized void completeUserDownload(String infoHash) { if (infoHash != null) activeUserDownloads.remove(infoHash); }

    synchronized AssistPermission assistPermission() {
        if (foregroundPlayback) return AssistPermission.DEFERRED_FOR_PLAYBACK;
        if (!activeUserDownloads.isEmpty()) return AssistPermission.DEFERRED_FOR_USER_DOWNLOAD;
        return AssistPermission.PERMITTED;
    }

    synchronized boolean hasForegroundUserWork() { return foregroundPlayback || !activeUserDownloads.isEmpty(); }
}
