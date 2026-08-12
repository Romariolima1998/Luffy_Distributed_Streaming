package dev.lufi.ui;

import javafx.scene.Node;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Contrato de reprodução usado pela interface do Luffy.
 *
 * <p>A UI conhece somente esta abstração. Implementações podem usar o player
 * JavaFX, libVLC, um binding JNA ou outro motor, sem expor APIs desses motores
 * ao restante da aplicação nem ao motor BitTorrent.</p>
 */
interface MediaPlayerBackend extends AutoCloseable {
    enum State {
        IDLE,
        OPENING,
        BUFFERING,
        PLAYING,
        PAUSED,
        STOPPED,
        FINISHED,
        ERROR
    }

    interface Listener {
        /** Estado público e independente da implementação concreta do player. */
        default void onStateChanged(State state) { }
        default void onPlaying() { }
        default void onPaused() { }
        default void onStopped() { }
        default void onFinished() { }
        default void onError(Throwable error) { }
        default void onBuffering(boolean buffering) { }
        default void onPositionChanged(Duration position) { }
        default void onDurationChanged(Duration duration) { }
        default void onDiagnostic(String message) { }
    }

    /** Abre a origem. Implementações podem iniciar a preparação de forma assíncrona. */
    /** Abre somente a URI da mídia; o backend não recebe identidade nem estado de torrent. */
    void open(URI mediaUri);

    void play();

    void pause();

    void stop();

    void seek(Duration position);

    void setVolume(double volume);

    void setMute(boolean muted);

    void setRate(double rate);

    Optional<Duration> getPosition();

    Optional<Duration> getDuration();

    State getState();

    boolean isPlaying();

    boolean isPaused();

    boolean isSeekable();

    /** Faixas de audio conhecidas, sem expor tipos internos do backend. */
    default List<MediaTrack> audioTracks() {
        return List.of();
    }

    /** Faixas de legenda embutidas, sem alterar a selecao atual. */
    default List<MediaTrack> subtitleTracks() {
        return List.of();
    }

    /** Seleciona uma faixa de audio quando o backend oferecer esse recurso. */
    default boolean selectAudioTrack(int trackId) {
        return false;
    }

    /** Seleciona uma faixa de legenda embutida quando o backend oferecer esse recurso. */
    default boolean selectSubtitleTrack(int trackId) {
        return false;
    }

    /** Associa uma legenda externa quando o backend oferecer esse recurso. */
    default boolean setExternalSubtitle(URI subtitleUri) {
        return false;
    }

    /** Cria uma superfície JavaFX ligada à mesma reprodução. Também é usada na tela cheia. */
    Node createVideoView();

    void setListener(Listener listener);

    void release();

    @Override
    default void close() {
        release();
    }
}
