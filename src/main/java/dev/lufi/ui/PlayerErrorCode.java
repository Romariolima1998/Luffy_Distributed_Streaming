package dev.lufi.ui;

/**
 * Códigos estáveis para falhas visíveis na reprodução.
 *
 * <p>Eles pertencem à camada de player/ponte HTTP. Não representam uma falha
 * do motor BitTorrent global, que pode continuar baixando ou semeando depois
 * que uma reprodução é parada.</p>
 */
enum PlayerErrorCode {
    LIBVLC_NOT_FOUND("O runtime integrado do libVLC não foi encontrado. Reinstale o Luffy ou confira a instalação."),
    MEDIA_OPEN_FAILED("Não foi possível abrir esta mídia no libVLC."),
    MEDIA_DECODE_FAILED("O libVLC não conseguiu decodificar esta mídia."),
    HTTP_STREAM_FAILED("A ponte HTTP local da reprodução falhou."),
    PIECE_TIMEOUT("As partes necessárias do vídeo não chegaram a tempo."),
    TORRENT_STOPPED("A sessão de streaming foi encerrada."),
    FILE_NOT_FOUND("O arquivo de mídia não foi encontrado."),
    UNKNOWN("Ocorreu um erro inesperado na reprodução.");

    private final String userMessage;

    PlayerErrorCode(String userMessage) {
        this.userMessage = userMessage;
    }

    String userMessage() {
        return userMessage;
    }
}
