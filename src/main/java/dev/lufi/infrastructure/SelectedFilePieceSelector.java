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
    /**
     * A ausência de seleção inicial é diferente de uma seleção vazia.  Em um
     * .torrent local o bt-core pode consultar o seletor antes de executar
     * afterTorrentFetched, que é quando a faixa do arquivo escolhido é
     * calculada. Se devolvêssemos um BitSet vazio nesse intervalo, o cliente
     * concluiria que não há nenhuma peça para baixar e encerraria a sessão.
     *
     * <p>{@code null} representa somente esse curto estado de bootstrap e
     * permite as peças disponíveis até que {@link #select(BitSet)} instale a
     * seleção real. Um BitSet vazio explícito continua significando que não há
     * peças desejadas.</p>
     */
    private final AtomicReference<BitSet> allowedPieces = new AtomicReference<>();

    void select(BitSet pieces) {
        allowedPieces.set(pieces == null ? new BitSet() : (BitSet) pieces.clone());
    }

    @Override
    public IntStream getNextPieces(BitSet availablePieces, PieceStatistics statistics) {
        BitSet allowed = allowedPieces.get();
        if (allowed == null) return availablePieces.stream();
        BitSet selected = (BitSet) availablePieces.clone();
        selected.and(allowed);
        return selected.stream();
    }
}
