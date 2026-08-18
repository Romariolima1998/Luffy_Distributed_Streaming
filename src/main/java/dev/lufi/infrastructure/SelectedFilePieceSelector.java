package dev.lufi.infrastructure;

import bt.torrent.PieceStatistics;
import bt.torrent.selector.PieceSelector;
import java.util.BitSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

/**
 * Keeps one torrent client connected while changing the file whose pieces may
 * be requested. File priorities stay enabled for the client, but this selector
 * only returns pieces that belong to the current file.
 */
final class SelectedFilePieceSelector implements PieceSelector {
    private final AtomicReference<BitSet> allowedPieces = new AtomicReference<>(new BitSet());

    void select(BitSet pieces) {
        allowedPieces.set(pieces == null ? new BitSet() : (BitSet) pieces.clone());
    }

    @Override
    public IntStream getNextPieces(BitSet availablePieces, PieceStatistics statistics) {
        BitSet selected = (BitSet) availablePieces.clone();
        selected.and(allowedPieces.get());
        return selected.stream();
    }
}
