package dev.lufi.ui;

import javafx.application.Platform;
import javafx.scene.image.ImageView;
import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter;
import uk.co.caprica.vlcj.player.base.TrackDescription;
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer;
import uk.co.caprica.vlcj.support.version.Version;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Backend vlcj/libVLC para a interface {@link MediaPlayerBackend}.
 *
 * <p>As dependencias vlcj, JNA, a descoberta da biblioteca nativa e o direct
 * rendering {@code CallbackVideoSurface -> PixelBuffer -> ImageView} ficam
 * confinados a esta classe. Nem a UI JavaFX nem o motor BitTorrent conhecem
 * essas APIs.</p>
 *
 * <p>O VLC nativo nao e distribuido por este backend. Ele e localizado no
 * momento de {@link #open(URI)} e uma ausencia e reportada por {@code onError}.
 * Isso permite validar o backend tecnico sem incorporar uma decisao de
 * distribuicao/licenca do runtime VLC ao aplicativo.</p>
 */
final class LibVlcPlayerBackend implements MediaPlayerBackend {
    private static final int REQUIRED_LIBVLC_MAJOR_VERSION = 3;
    private static final double MIN_VOLUME = 0d;
    private static final double MAX_VOLUME = 1d;
    /** A UI só precisa de algumas atualizações por segundo, não de cada tick interno do VLC. */
    private static final long POSITION_UI_UPDATE_INTERVAL_NANOS = 250_000_000L;

    private final Object lifecycleLock = new Object();
    private final AtomicLong lastPositionUiUpdateNanos = new AtomicLong();
    /** Atualizado pela superfície direta PixelBuffer a partir do buffer nativo do libVLC. */
    private final ImageView pixelBufferImageView = new ImageView();

    private volatile Listener listener = new Listener() { };
    private volatile MediaPlayerFactory mediaPlayerFactory;
    private volatile EmbeddedMediaPlayer mediaPlayer;
    private volatile LibVlcJavaFxVideoSurface videoSurface;
    private volatile State state = State.IDLE;
    private volatile State stateBeforeBuffering = State.OPENING;
    private volatile double volume = .8d;
    private volatile boolean muted;
    private volatile double rate = 1d;

    boolean hasPresentedFrame() {
        LibVlcJavaFxVideoSurface currentSurface = videoSurface;
        return currentSurface != null && currentSurface.hasPresentedFrame();
    }

    @Override
    public void open(URI mediaUri) {
        URI sourceUri = Objects.requireNonNull(mediaUri, "mediaUri");
        releaseResources();
        lastPositionUiUpdateNanos.set(0L);
        transition(State.OPENING);
        listener.onDiagnostic("[PLAYER] backend=LIBVLC; event=OPEN; uri=" + sourceUri + ".");

        try {
            EmbeddedMediaPlayer created = createPlayer();
            // Registra imediatamente o player criado. Assim, uma falha na
            // configuracao posterior ainda passa pelo mesmo caminho de release.
            mediaPlayer = created;
            configureEvents(created);
            LibVlcJavaFxVideoSurface createdSurface = new LibVlcJavaFxVideoSurface(
                    pixelBufferImageView,
                    message -> listener.onDiagnostic(message));
            videoSurface = createdSurface;
            created.videoSurface().set(createdSurface);
            // VideoSurfaceApi.set(...) somente guarda a superfície. O attach
            // efetivamente conecta o CallbackVideoSurface ao libVLC; sem ele
            // não há destino para os frames de vídeo no direct rendering.
            created.videoSurface().attachVideoSurface();
            applyAudioSettings(created);
            // vlcj 4/JNA em Windows pode converter caracteres fora da página
            // de código local para '?'. A representação ASCII preserva cada
            // byte UTF-8 do caminho por percent-encoding para o libVLC.
            if (!created.media().play(sourceUri.toASCIIString())) {
                throw new IllegalStateException("libVLC recusou abrir a midia informada.");
            }
        } catch (RuntimeException error) {
            fail(error);
            releaseResources();
        }
    }

    @Override
    public void play() {
        EmbeddedMediaPlayer current = mediaPlayer;
        if (current != null) {
            current.controls().play();
        }
    }

    @Override
    public void pause() {
        EmbeddedMediaPlayer current = mediaPlayer;
        if (current != null) {
            current.controls().pause();
        }
    }

    @Override
    public void stop() {
        EmbeddedMediaPlayer current = mediaPlayer;
        if (current == null) {
            publishStopped();
            return;
        }
        try {
            current.controls().stop();
            // O runtime normalmente entrega o evento stopped. Este fallback
            // tambem mantem a UI consistente se ele for liberado antes do callback.
            publishStopped();
        } catch (RuntimeException error) {
            fail(error);
        }
    }

    @Override
    public void seek(Duration position) {
        Objects.requireNonNull(position, "position");
        EmbeddedMediaPlayer current = mediaPlayer;
        if (current != null && isSeekable()) {
            current.controls().setTime(Math.max(0L, position.toMillis()));
        }
    }

    @Override
    public void setVolume(double volume) {
        if (!Double.isFinite(volume)) {
            throw new IllegalArgumentException("O volume precisa ser finito.");
        }
        this.volume = Math.clamp(volume, MIN_VOLUME, MAX_VOLUME);
        EmbeddedMediaPlayer current = mediaPlayer;
        if (current != null) {
            applyAudioSettings(current);
        }
    }

    @Override
    public void setMute(boolean muted) {
        this.muted = muted;
        EmbeddedMediaPlayer current = mediaPlayer;
        if (current != null) {
            applyAudioSettings(current);
        }
    }

    @Override
    public void setRate(double rate) {
        if (!Double.isFinite(rate) || rate <= 0d) {
            throw new IllegalArgumentException("A velocidade precisa ser positiva.");
        }
        this.rate = rate;
        EmbeddedMediaPlayer current = mediaPlayer;
        if (current != null) {
            current.controls().setRate((float) rate);
        }
    }

    @Override
    public Optional<Duration> getPosition() {
        EmbeddedMediaPlayer current = mediaPlayer;
        return current == null ? Optional.empty() : Optional.of(Duration.ofMillis(Math.max(0L, current.status().time())));
    }

    @Override
    public Optional<Duration> getDuration() {
        EmbeddedMediaPlayer current = mediaPlayer;
        if (current == null) {
            return Optional.empty();
        }
        long length = current.status().length();
        return length > 0L ? Optional.of(Duration.ofMillis(length)) : Optional.empty();
    }

    @Override
    public State getState() {
        return state;
    }

    @Override
    public boolean isPlaying() {
        return state == State.PLAYING || state == State.BUFFERING && stateBeforeBuffering == State.PLAYING;
    }

    @Override
    public boolean isPaused() {
        return state == State.PAUSED;
    }

    @Override
    public boolean isSeekable() {
        EmbeddedMediaPlayer current = mediaPlayer;
        return current != null && current.status().isSeekable();
    }

    @Override
    public List<MediaTrack> audioTracks() {
        EmbeddedMediaPlayer current = mediaPlayer;
        if (current == null) return List.of();
        return describeTracks(current.audio().trackDescriptions(), current.audio().track());
    }

    @Override
    public List<MediaTrack> subtitleTracks() {
        EmbeddedMediaPlayer current = mediaPlayer;
        if (current == null) return List.of();
        return describeTracks(current.subpictures().trackDescriptions(), current.subpictures().track());
    }

    @Override
    public boolean selectAudioTrack(int trackId) {
        EmbeddedMediaPlayer current = mediaPlayer;
        return current != null && current.audio().setTrack(trackId) == 0;
    }

    @Override
    public boolean selectSubtitleTrack(int trackId) {
        EmbeddedMediaPlayer current = mediaPlayer;
        return current != null && current.subpictures().setTrack(trackId) == 0;
    }

    @Override
    public boolean setExternalSubtitle(URI subtitleUri) {
        EmbeddedMediaPlayer current = mediaPlayer;
        return current != null && current.subpictures().setSubTitleUri(
                Objects.requireNonNull(subtitleUri, "subtitleUri").toASCIIString());
    }

    @Override
    public LuffyVideoView createVideoView() {
        LuffyVideoView videoView = new LuffyVideoView();
        videoView.bindImage(pixelBufferImageView.imageProperty());
        return videoView;
    }

    @Override
    public void setListener(Listener listener) {
        this.listener = listener == null ? new Listener() { } : listener;
    }

    @Override
    public void release() {
        releaseResources();
        if (state != State.IDLE && state != State.ERROR) {
            publishStopped();
        }
    }

    private EmbeddedMediaPlayer createPlayer() {
        synchronized (lifecycleLock) {
            LibVlcRuntimeDiscovery.Result discovery = LibVlcRuntimeDiscovery.discover(listener::onDiagnostic);
            if (!discovery.available()) {
                throw new IllegalStateException(discovery.failureMessage());
            }
            // A superfície CallbackVideoSurface precisa que o libVLC entregue
            // frames em RV32. Em VLC 3, alguns decoders acelerados (notadamente
            // AV1 no Windows) não conseguem adaptar o formato de hardware ao
            // vmem e entram em recursão no conversor. Para esse backend de
            // renderização direta, o decoder de software do próprio libVLC é
            // o caminho estável e ainda preserva todo o pacing A/V do VLC.
            MediaPlayerFactory factory = new MediaPlayerFactory("--avcodec-hw=none", "--no-video-title-show");
            Version nativeVersion = new Version(factory.application().version());
            if (nativeVersion.major() != REQUIRED_LIBVLC_MAJOR_VERSION) {
                factory.release();
                throw new IllegalStateException("O backend libVLC requer VLC 3.x estavel; encontrado " + nativeVersion + ".");
            }
            mediaPlayerFactory = factory;
            listener.onDiagnostic("[PLAYER] backend=LIBVLC; event=RUNTIME_READY; vlcj=4.12.1; libvlc=" + nativeVersion
                    + "; directRendering=true; hardwareAcceleration=software-direct-rendering"
                    + "; discoveryPath=" + discovery.path()
                    + "; discoveryStrategy=" + discovery.strategy() + ".");
            return factory.mediaPlayers().newEmbeddedMediaPlayer();
        }
    }

    private static List<MediaTrack> describeTracks(List<TrackDescription> descriptions, int selectedTrackId) {
        if (descriptions == null || descriptions.isEmpty()) return List.of();
        return descriptions.stream()
                .filter(Objects::nonNull)
                .map(description -> new MediaTrack(description.id(), description.description(),
                        description.id() == selectedTrackId))
                .toList();
    }

    private void configureEvents(EmbeddedMediaPlayer configuredPlayer) {
        configuredPlayer.events().addMediaPlayerEventListener(new MediaPlayerEventAdapter() {
            @Override
            public void playing(MediaPlayer mediaPlayer) {
                if (!isCurrentPlayer(mediaPlayer)) return;
                boolean wasBuffering = state == State.BUFFERING;
                publishPlaying();
                if (wasBuffering) listener.onBuffering(false);
            }

            @Override
            public void paused(MediaPlayer mediaPlayer) {
                if (isCurrentPlayer(mediaPlayer)) publishPaused();
            }

            @Override
            public void stopped(MediaPlayer mediaPlayer) {
                if (isCurrentPlayer(mediaPlayer)) publishStopped();
            }

            @Override
            public void finished(MediaPlayer mediaPlayer) {
                if (isCurrentPlayer(mediaPlayer)) publishFinished();
            }

            @Override
            public void error(MediaPlayer mediaPlayer) {
                if (isCurrentPlayer(mediaPlayer)) {
                    fail(new IllegalStateException("libVLC reportou um erro durante a reproducao."));
                }
            }

            @Override
            public void buffering(MediaPlayer mediaPlayer, float newCache) {
                if (!isCurrentPlayer(mediaPlayer)) return;
                boolean buffering = newCache < 100f;
                if (buffering) {
                    State current = state;
                    if (current != State.BUFFERING) {
                        stateBeforeBuffering = current;
                        if (transition(State.BUFFERING)) listener.onBuffering(true);
                    }
                } else if (state == State.BUFFERING) {
                    transition(stateBeforeBuffering);
                    listener.onBuffering(false);
                }
            }

            @Override
            public void timeChanged(MediaPlayer mediaPlayer, long newTime) {
                if (!isCurrentPlayer(mediaPlayer)) return;
                long now = System.nanoTime();
                long previous = lastPositionUiUpdateNanos.get();
                if (previous > 0L && now - previous < POSITION_UI_UPDATE_INTERVAL_NANOS) {
                    return;
                }
                if (lastPositionUiUpdateNanos.compareAndSet(previous, now)) {
                    listener.onPositionChanged(Duration.ofMillis(Math.max(0L, newTime)));
                }
            }

            @Override
            public void lengthChanged(MediaPlayer mediaPlayer, long newLength) {
                if (isCurrentPlayer(mediaPlayer) && newLength > 0L) {
                    listener.onDurationChanged(Duration.ofMillis(newLength));
                }
            }
        });
    }

    private void applyAudioSettings(EmbeddedMediaPlayer configuredPlayer) {
        configuredPlayer.audio().setVolume((int) Math.round((muted ? 0d : volume) * 100d));
        configuredPlayer.audio().setMute(muted);
    }

    private void releaseResources() {
        synchronized (lifecycleLock) {
            LibVlcJavaFxVideoSurface currentSurface = videoSurface;
            videoSurface = null;
            if (currentSurface != null) {
                currentSurface.dispose();
            }
            EmbeddedMediaPlayer currentPlayer = mediaPlayer;
            mediaPlayer = null;
            if (currentPlayer != null) {
                stopPlayerQuietly(currentPlayer);
                try {
                    currentPlayer.release();
                } catch (RuntimeException error) {
                    listener.onDiagnostic("[PLAYER] backend=LIBVLC; event=PLAYER_RELEASE; result=failed; detail="
                            + error.getClass().getSimpleName() + ".");
                }
            }
            MediaPlayerFactory currentFactory = mediaPlayerFactory;
            mediaPlayerFactory = null;
            if (currentFactory != null) {
                try {
                    currentFactory.release();
                } catch (RuntimeException error) {
                    listener.onDiagnostic("[PLAYER] backend=LIBVLC; event=FACTORY_RELEASE; result=failed; detail="
                            + error.getClass().getSimpleName() + ".");
                }
            }
        }
        clearVideoImage();
    }

    private void fail(Throwable error) {
        if (transition(State.ERROR)) listener.onError(error);
    }

    private boolean transition(State nextState) {
        State previous = state;
        state = nextState;
        if (previous != nextState) {
            listener.onStateChanged(nextState);
            return true;
        }
        return false;
    }

    private boolean isCurrentPlayer(MediaPlayer eventPlayer) {
        return eventPlayer != null && eventPlayer == mediaPlayer;
    }

    private void publishPlaying() {
        if (transition(State.PLAYING)) listener.onPlaying();
    }

    private void publishPaused() {
        if (transition(State.PAUSED)) listener.onPaused();
    }

    private void publishStopped() {
        if (state != State.IDLE && state != State.ERROR && transition(State.STOPPED)) listener.onStopped();
    }

    private void publishFinished() {
        if (transition(State.FINISHED)) listener.onFinished();
    }

    private void stopPlayerQuietly(EmbeddedMediaPlayer currentPlayer) {
        try {
            currentPlayer.controls().stop();
        } catch (RuntimeException error) {
            listener.onDiagnostic("[PLAYER] backend=LIBVLC; event=PLAYER_STOP; result=failed; detail="
                    + error.getClass().getSimpleName() + ".");
        }
    }

    private void clearVideoImage() {
        if (Platform.isFxApplicationThread()) {
            pixelBufferImageView.setImage(null);
            return;
        }
        try {
            Platform.runLater(() -> pixelBufferImageView.setImage(null));
        } catch (IllegalStateException ignored) {
            // The toolkit is already stopping; there is no live JavaFX surface to clear.
        }
    }
}
