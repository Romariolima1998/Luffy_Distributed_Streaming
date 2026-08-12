package dev.lufi.infrastructure;

import java.util.Optional;

/** Pure mapping between a byte range of one torrent file and torrent pieces. */
final class StreamingPieceRangeMapper {
    private StreamingPieceRangeMapper() { }

    static Optional<BtTorrentGateway.StreamingPieceRange> map(long torrentLengthBytes, long fileOffsetBytes,
                                                                long fileLengthBytes, long pieceLengthBytes,
                                                                long fileStartByte, long fileEndByte) {
        if (torrentLengthBytes <= 0 || fileOffsetBytes < 0 || fileLengthBytes <= 0 || pieceLengthBytes <= 0
                || fileStartByte < 0 || fileEndByte < fileStartByte || fileEndByte >= fileLengthBytes) {
            return Optional.empty();
        }
        if (fileOffsetBytes > torrentLengthBytes || fileLengthBytes > torrentLengthBytes - fileOffsetBytes) {
            return Optional.empty();
        }
        long torrentStart = fileOffsetBytes + fileStartByte;
        long torrentEnd = fileOffsetBytes + fileEndByte;
        if (torrentEnd < torrentStart || torrentEnd >= torrentLengthBytes) return Optional.empty();

        long firstPiece = torrentStart / pieceLengthBytes;
        long lastPiece = torrentEnd / pieceLengthBytes;
        if (firstPiece > Integer.MAX_VALUE || lastPiece > Integer.MAX_VALUE) return Optional.empty();
        long firstPieceStart = firstPiece * pieceLengthBytes;
        long lastPieceStart = lastPiece * pieceLengthBytes;
        long lastPieceLength = Math.min(pieceLengthBytes, torrentLengthBytes - lastPieceStart);
        return Optional.of(new BtTorrentGateway.StreamingPieceRange(
                fileStartByte, fileEndByte, torrentStart, torrentEnd,
                (int) firstPiece, (int) lastPiece, pieceLengthBytes, lastPieceLength,
                torrentStart > firstPieceStart,
                torrentEnd < lastPieceStart + lastPieceLength - 1));
    }
}
