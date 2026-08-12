package dev.lufi.infrastructure;

import java.util.BitSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mantém a parte inicial contínua de cada torrent já validada pelo bt-core.
 * O tamanho físico de um arquivo em download não é uma prova de que seu começo
 * existe: FileSystemStorage pode pré-alocar o arquivo inteiro com lacunas.
 */
final class StreamingPiecePrefixTracker {
    private final Map<String, BitSet> verifiedByInfoHash = new ConcurrentHashMap<>();

    int record(String infoHash, int pieceIndex) {
        if (infoHash == null || infoHash.isBlank() || pieceIndex < 0) return 0;
        BitSet verified = verifiedByInfoHash.computeIfAbsent(normalize(infoHash), ignored -> new BitSet());
        synchronized (verified) {
            verified.set(pieceIndex);
            return verified.nextClearBit(0);
        }
    }

    int contiguousPrefix(String infoHash, int totalPieces) {
        if (infoHash == null || infoHash.isBlank() || totalPieces <= 0) return 0;
        BitSet verified = verifiedByInfoHash.get(normalize(infoHash));
        if (verified == null) return 0;
        synchronized (verified) {
            return Math.min(totalPieces, verified.nextClearBit(0));
        }
    }

    /** Returns true only when every piece in the inclusive interval was hash-verified. */
    boolean containsAll(String infoHash, int firstPiece, int lastPiece) {
        if (infoHash == null || infoHash.isBlank() || firstPiece < 0 || lastPiece < firstPiece) return false;
        BitSet verified = verifiedByInfoHash.get(normalize(infoHash));
        if (verified == null) return false;
        synchronized (verified) {
            return verified.nextClearBit(firstPiece) > lastPiece;
        }
    }

    /** Counts hash-verified pieces in the inclusive interval for streaming telemetry. */
    int countVerified(String infoHash, int firstPiece, int lastPiece) {
        if (infoHash == null || infoHash.isBlank() || firstPiece < 0 || lastPiece < firstPiece) return 0;
        BitSet verified = verifiedByInfoHash.get(normalize(infoHash));
        if (verified == null) return 0;
        synchronized (verified) {
            return verified.get(firstPiece, lastPiece + 1).cardinality();
        }
    }

    void clear(String infoHash) {
        if (infoHash != null) verifiedByInfoHash.remove(normalize(infoHash));
    }

    void clear() {
        verifiedByInfoHash.clear();
    }

    private static String normalize(String infoHash) {
        return infoHash.toLowerCase(java.util.Locale.ROOT);
    }
}
