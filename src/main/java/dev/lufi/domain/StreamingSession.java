package dev.lufi.domain;

import java.time.Instant;

/** Estado observável da reprodução progressiva; transportes atualizam este agregado. */
public record StreamingSession(String infoHash, String title, WatchMode mode, SessionStatus status, int bufferedPieces, int requiredBufferPieces, Instant startedAt) {
    public enum SessionStatus { DISCOVERING_PEERS, BUFFERING, PLAYABLE, STALLED, FAILED }
    public boolean playable() { return bufferedPieces >= requiredBufferPieces; }
}

