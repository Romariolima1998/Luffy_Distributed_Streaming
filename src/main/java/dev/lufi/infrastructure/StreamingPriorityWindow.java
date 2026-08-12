package dev.lufi.infrastructure;

import java.util.BitSet;

/** Current HTTP demand plus bounded read-ahead, represented as torrent pieces. */
final class StreamingPriorityWindow {
    private final int requestedStartPiece;
    private final int requestedEndPiece;
    private final int priorityStartPiece;
    private final int priorityEndPiece;

    private StreamingPriorityWindow(int requestedStartPiece, int requestedEndPiece,
                                    int priorityStartPiece, int priorityEndPiece) {
        this.requestedStartPiece = requestedStartPiece;
        this.requestedEndPiece = requestedEndPiece;
        this.priorityStartPiece = priorityStartPiece;
        this.priorityEndPiece = priorityEndPiece;
    }

    static StreamingPriorityWindow create(BtTorrentGateway.StreamingPieceRange range, int totalPieces,
                                          StreamingReadAheadPolicy policy) {
        if (range == null || totalPieces <= 0 || policy == null) throw new IllegalArgumentException("janela de streaming invalida");
        int readAhead = policy.piecesFor(range.pieceLengthBytes());
        int endInclusive = (int) Math.min(totalPieces - 1L, (long) range.endPiece() + readAhead);
        return new StreamingPriorityWindow(range.startPiece(), range.endPiece(), range.startPiece(), endInclusive);
    }

    /**
     * Limits a broad HTTP probe to the next missing continuous piece. libVLC
     * often asks for {@code start-EOF}, which describes what it may need, not
     * what it will consume next. Prioritizing that full range makes every
     * torrent piece equally urgent and defeats sequential streaming.
     */
    static StreamingPriorityWindow forStreamingRequest(BtTorrentGateway.StreamingPieceRange range, int totalPieces,
                                                        int verifiedPrefixPieces, StreamingReadAheadPolicy policy) {
        if (range == null || totalPieces <= 0 || policy == null) throw new IllegalArgumentException("janela de streaming invalida");
        int requestedStart = range.startPiece();
        int requestedEnd = range.endPiece();
        int nextMissingPrefixPiece = Math.max(0, Math.min(totalPieces - 1, verifiedPrefixPieces));
        boolean requestCoversPrefixFrontier = requestedStart <= nextMissingPrefixPiece
                && nextMissingPrefixPiece <= requestedEnd;
        int priorityStart = requestCoversPrefixFrontier ? nextMissingPrefixPiece : requestedStart;
        int requestedPieces = requestedEnd - requestedStart + 1;
        int readAhead = policy.piecesFor(range.pieceLengthBytes());
        boolean broadProbe = requestedPieces > readAhead;
        int priorityEnd;
        if (requestCoversPrefixFrontier || broadProbe) {
            priorityEnd = (int) Math.min(totalPieces - 1L, (long) priorityStart + readAhead - 1L);
        } else {
            priorityEnd = (int) Math.min(totalPieces - 1L, (long) requestedEnd + readAhead);
        }
        return new StreamingPriorityWindow(requestedStart, requestedEnd, priorityStart, priorityEnd);
    }

    BitSet pieces() {
        BitSet pieces = new BitSet(priorityEndPiece + 1);
        pieces.set(priorityStartPiece, priorityEndPiece + 1);
        return pieces;
    }

    /** A non-overlapping request means libVLC has moved to a different playback region. */
    boolean isSeekFrom(StreamingPriorityWindow previous) {
        return previous != null && (requestedStartPiece > previous.priorityEndPiece
                || requestedEndPiece < previous.priorityStartPiece);
    }

    int requestedStartPiece() { return requestedStartPiece; }
    int requestedEndPiece() { return requestedEndPiece; }
    int priorityStartPiece() { return priorityStartPiece; }
    int priorityEndPiece() { return priorityEndPiece; }
    int prioritizedPieces() { return priorityEndPiece - priorityStartPiece + 1; }
}
