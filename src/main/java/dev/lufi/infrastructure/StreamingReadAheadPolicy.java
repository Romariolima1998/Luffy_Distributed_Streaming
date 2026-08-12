package dev.lufi.infrastructure;

/**
 * Centraliza a antecipação de leitura para o streaming HTTP.
 *
 * <p>O alvo é expresso em bytes para se adaptar ao tamanho escolhido pelo
 * torrent para cada piece, mas sempre fica dentro de uma faixa pequena e
 * previsível de pieces. A política não altera verificação de hash nem
 * permite a entrega antecipada de bytes.</p>
 */
public record StreamingReadAheadPolicy(int minimumPieces, int maximumPieces, long targetBytes) {
    private static final int DEFAULT_MINIMUM_PIECES = 5;
    private static final int DEFAULT_MAXIMUM_PIECES = 20;
    private static final long DEFAULT_TARGET_BYTES = 8L * 1024L * 1024L;

    public StreamingReadAheadPolicy {
        if (minimumPieces < 0) throw new IllegalArgumentException("minimumPieces deve ser positivo");
        if (maximumPieces < minimumPieces) throw new IllegalArgumentException("maximumPieces deve ser maior ou igual ao minimo");
        if (targetBytes <= 0) throw new IllegalArgumentException("targetBytes deve ser positivo");
    }

    public static StreamingReadAheadPolicy defaults() {
        return new StreamingReadAheadPolicy(DEFAULT_MINIMUM_PIECES, DEFAULT_MAXIMUM_PIECES, DEFAULT_TARGET_BYTES);
    }

    /** Number of pieces to request after the range that libVLC actually asked for. */
    public int piecesFor(long pieceLengthBytes) {
        if (pieceLengthBytes <= 0) return minimumPieces;
        long targetPieces = Math.max(1, Math.ceilDiv(targetBytes, pieceLengthBytes));
        return (int) Math.min(maximumPieces, Math.max(minimumPieces, targetPieces));
    }
}
