package dev.lufi.infrastructure;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.util.BitSet;
import org.junit.jupiter.api.Test;

class SelectedFilePieceSelectorTest {
    @Test
    void permitsAvailablePiecesOnlyUntilTheInitialSelectionIsKnown() {
        SelectedFilePieceSelector selector = new SelectedFilePieceSelector();
        BitSet available = new BitSet();
        available.set(1);
        available.set(3);

        assertArrayEquals(new int[] {1, 3}, selector.getNextPieces(available, null).toArray());

        selector.select(new BitSet());

        assertArrayEquals(new int[0], selector.getNextPieces(available, null).toArray());
    }

    @Test
    void changesTheAllowedPiecesWithoutCreatingAnotherSelector() {
        SelectedFilePieceSelector selector = new SelectedFilePieceSelector();
        BitSet first = new BitSet();
        first.set(2, 5);
        selector.select(first);

        BitSet available = new BitSet();
        available.set(0, 8);
        assertArrayEquals(new int[] {2, 3, 4}, selector.getNextPieces(available, null).toArray());

        BitSet next = new BitSet();
        next.set(6, 8);
        selector.select(next);
        assertArrayEquals(new int[] {6, 7}, selector.getNextPieces(available, null).toArray());
    }
}
