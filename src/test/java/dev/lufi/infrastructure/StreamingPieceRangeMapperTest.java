package dev.lufi.infrastructure;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamingPieceRangeMapperTest {
    @Test
    void mapsAFileRangeUsingTheOffsetInsideAMultiFileTorrent() {
        BtTorrentGateway.StreamingPieceRange range = StreamingPieceRangeMapper.map(
                64, 10, 30, 8, 1, 17).orElseThrow();

        assertEquals(1, range.fileStartByte());
        assertEquals(17, range.fileEndByte());
        assertEquals(11, range.torrentStartByte());
        assertEquals(27, range.torrentEndByte());
        assertEquals(1, range.startPiece());
        assertEquals(3, range.endPiece());
        assertTrue(range.firstPiecePartial());
        assertTrue(range.lastPiecePartial());
    }

    @Test
    void handlesTheShortLastPieceOfATorrent() {
        BtTorrentGateway.StreamingPieceRange range = StreamingPieceRangeMapper.map(
                34, 20, 14, 8, 0, 13).orElseThrow();

        assertEquals(20, range.torrentStartByte());
        assertEquals(33, range.torrentEndByte());
        assertEquals(2, range.startPiece());
        assertEquals(4, range.endPiece());
        assertEquals(2, range.endPieceLengthBytes());
        assertTrue(range.firstPiecePartial());
        assertFalse(range.lastPiecePartial());
    }

    @Test
    void rejectsRangesOutsideTheLogicalFile() {
        assertTrue(StreamingPieceRangeMapper.map(64, 10, 30, 8, -1, 3).isEmpty());
        assertTrue(StreamingPieceRangeMapper.map(64, 10, 30, 8, 0, 30).isEmpty());
        assertTrue(StreamingPieceRangeMapper.map(64, 40, 30, 8, 0, 1).isEmpty());
    }
}
