package dev.lufi.ui;

import dev.lufi.application.WatchVideo;
import dev.lufi.application.port.TorrentContent;
import dev.lufi.domain.StreamingSession;
import dev.lufi.domain.WatchMode;
import dev.lufi.infrastructure.LocalVideoScanner;
import dev.lufi.infrastructure.SettingsRepository;
import dev.lufi.infrastructure.StreamingStartupSettings;
import dev.lufi.infrastructure.SqliteDatabase;
import dev.lufi.infrastructure.TorrentMetainfoGenerator;
import dev.lufi.infrastructure.BtTorrentGateway;
import dev.lufi.infrastructure.LibraryRepository;
import dev.lufi.infrastructure.LuffyManifest;
import dev.lufi.infrastructure.SwarmMembershipRepository;
import dev.lufi.infrastructure.SwarmAssistSettings;
import dev.lufi.infrastructure.SwarmAssistManager;
import dev.lufi.infrastructure.SwarmAssistPolicy;
import dev.lufi.infrastructure.SwarmNeedEvaluator;
import dev.lufi.infrastructure.ConnectivityService;
import dev.lufi.infrastructure.ConnectionLimitSettings;
import dev.lufi.infrastructure.AbuseProtectionSettings;
import dev.lufi.infrastructure.P2pDiagnostics;
import dev.lufi.infrastructure.P2pDiagnosticScenario;
import dev.lufi.infrastructure.identity.LuffyIdentityStorage;
import dev.lufi.infrastructure.identity.LuffyNodeIdentity;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.animation.PauseTransition;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.Screen;
import javafx.stage.StageStyle;
import javafx.util.Duration;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Ponto de entrada da aplicação desktop. Operações de I/O são executadas fora da thread JavaFX. */
public final class LufiApplication extends Application {
    private static final long PLAYER_POSITION_LOG_INTERVAL_NANOS = 5_000_000_000L;
    private final SqliteDatabase database = new SqliteDatabase(Path.of(System.getProperty("user.home"), ".lufi"));
    private final SettingsRepository settings = new SettingsRepository(database);
    private final LibraryRepository libraryRepository = new LibraryRepository(database);
    private final SwarmAssistSettings swarmAssistSettings = new SwarmAssistSettings(settings);
    private final ConnectionLimitSettings connectionLimitSettings = new ConnectionLimitSettings(settings);
    private final AbuseProtectionSettings abuseProtectionSettings = new AbuseProtectionSettings(settings);
    private final StreamingStartupSettings streamingStartupSettings = new StreamingStartupSettings(settings);
    private final SwarmMembershipRepository swarmMembershipRepository = new SwarmMembershipRepository(database,
            swarmAssistSettings::maxAssistSwarms, swarmAssistSettings::minAssistResidence,
            swarmAssistSettings::replacementThreshold, swarmAssistSettings::criticalSwarmPeerCount,
            swarmAssistSettings::inactiveSwarmDecay);
    private final P2pDiagnostics diagnostics = new P2pDiagnostics();
    /** Identidade local do overlay futuro; nao substitui o peer ID BitTorrent atual. */
    private final LuffyNodeIdentity nodeIdentity = new LuffyIdentityStorage(
            Path.of(System.getProperty("user.home"), ".lufi"), message -> diagnostics.log("[IDENTITY] " + message)).loadOrCreate();
    private final BtTorrentGateway torrents = new BtTorrentGateway(Path.of(System.getProperty("user.home"), ".lufi", "cache"), diagnostics, nodeIdentity);
    private final LuffyLocalMediaServer localMediaServer = new LuffyLocalMediaServer(diagnostics);
    private final SwarmAssistManager swarmAssistManager = new SwarmAssistManager(swarmMembershipRepository,
            () -> SwarmAssistPolicy.from(swarmAssistSettings), new SwarmNeedEvaluator(), new SwarmAssistManager.Runtime() {
                @Override public java.util.concurrent.CompletableFuture<Integer> inspect(dev.lufi.domain.MagnetLink magnet) {
                    return torrents.inspectSwarmPeerCount(magnet);
                }
                @Override public java.util.concurrent.CompletableFuture<Integer> restore(dev.lufi.domain.MagnetLink magnet) {
                    return torrents.restoreSwarmAssist(magnet);
                }
                @Override public void join(dev.lufi.domain.MagnetLink magnet) { torrents.rejoinSwarmAssist(magnet); }
                @Override public void leave(String infoHash) { torrents.removeFromSwarmAssist(infoHash); }
                @Override public dev.lufi.infrastructure.SwarmAssistStats stats(String infoHash) { return torrents.swarmAssistStats(infoHash); }
                @Override public void applyPolicy(SwarmAssistPolicy policy) { torrents.setSwarmAssistPolicy(policy); }
            }, diagnostics);
    private final ConnectivityService connectivity = new ConnectivityService(diagnostics);
    private final AtomicBoolean savedLibrariesLoaded = new AtomicBoolean();
    /** Garante que fechar a janela e Application.stop não deixem áudio ou rede rodando em segundo plano. */
    private final AtomicBoolean applicationShutdown = new AtomicBoolean();
    private final WatchVideo watchVideo = new WatchVideo(torrents);
    private final Label status = new Label("Pronto para receber um magnet link.");
    private TextArea connectivityPanelOutput;
    private TextArea peerPanelOutput;
    private final ListView<LibraryView> libraries = new ListView<>();
    private final TreeView<LibraryNode> videos = new TreeView<>();
    private final ListView<String> magnets = new ListView<>();
    private final Label libraryTitle = new Label("Selecione uma biblioteca");
    private final ListView<WatchEntry> watchPlaylist = new ListView<>();
    private final MediaView mediaView = new MediaView();
    /** Superficie JavaFX direta fornecida pelo backend libVLC. */
    private final StackPane backendVideoSurface = new StackPane();
    private final StackPane playerSurface = new StackPane();
    private final VBox playerPlaceholder = new VBox();
    private final Label nowPlaying = new Label("Abra um vídeo da sua biblioteca ou um magnet link.");
    private TabPane tabs;
    private MediaPlayer mediaPlayer;
    private MediaPlayerBackend backendPlayer;
    private double playbackVolume = .8d;
    private boolean playbackMuted;
    private final AtomicLong playbackGeneration = new AtomicLong();
    private final AtomicLong lastPlayerPositionLogNanos = new AtomicLong();
    private long streamingMediaPlaybackGeneration = -1L;
    /** Info-hash cuja prioridade transitória pertence à sessão HTTP atual. */
    private String streamingPriorityInfoHash;
    /** Controles espelhados entre a janela normal e a tela cheia. */
    private final List<Slider> seekControls = new ArrayList<>();
    private final List<Label> playbackTimeLabels = new ArrayList<>();
    private final List<Slider> volumeControls = new ArrayList<>();
    private final List<Button> muteControls = new ArrayList<>();
    private final List<MenuButton> audioTrackControls = new ArrayList<>();
    private final List<MenuButton> subtitleTrackControls = new ArrayList<>();
    private Duration currentPlaybackPosition = Duration.ZERO;
    private Duration currentPlaybackDuration = Duration.UNKNOWN;
    private boolean synchronizingSeekControls;
    private boolean synchronizingAudioControls;
    private WatchContext watchContext;
    private String pendingWatchPath;
    private String activeWatchInfoHash;
    private String selectedStreamingPath;
    /** Cancela a espera de buffer anterior quando o usuário escolhe outro vídeo. */
    private final AtomicLong streamingRequest = new AtomicLong();
    private final PauseTransition hideControls = new PauseTransition(Duration.seconds(4));
    private VBox playerControls;
    private Stage primaryStage;
    private Stage fullscreenPlayerStage;
    private MediaView fullscreenMediaView;
    private javafx.scene.Node fullscreenBackendView;
    private VBox fullscreenControls;

    @Override public void start(Stage stage) {
        primaryStage = stage;
        stage.setOnCloseRequest(event -> {
            shutdownApplication();
            // Garante que uma eventual janela secundária (por exemplo, tela
            // cheia) não mantenha o toolkit JavaFX ativo após a principal fechar.
            Platform.exit();
        });
        if (settings.get("cache.max.gb").isEmpty()) showOnboarding(stage); else showMain(stage);
    }
    private void showOnboarding(Stage stage) {
        Label title = new Label("Bem-vindo ao Luffy"); title.getStyleClass().add("hero");
        Label intro = new Label("Quanto espaço deseja reservar para o cache e compartilhamento?");
        ComboBox<String> amount = new ComboBox<>(); amount.getItems().addAll("5 GB", "20 GB", "50 GB", "100 GB", "Personalizado"); amount.getSelectionModel().select("20 GB");
        Button continueButton = new Button("Continuar"); continueButton.setDefaultButton(true);
        continueButton.setOnAction(e -> { settings.put("cache.max.gb", amount.getValue()); showMain(stage); });
        VBox root = new VBox(18, title, intro, amount, continueButton); root.setAlignment(Pos.CENTER); root.setPadding(new Insets(48));
        stage.setScene(scene(root)); stage.setTitle("Luffy"); stage.show();
    }
    private void showMain(Stage stage) {
        tabs = new TabPane(watchTab(), libraryTab(), settingsTab(), diagnosticsTab(), logsTab()); tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.setTabMinWidth(130);
        BorderPane root = new BorderPane(tabs); root.setTop(header()); root.setPadding(new Insets(0, 24, 24, 24));
        var bounds = Screen.getPrimary().getVisualBounds();
        stage.setScene(scene(root));
        stage.setWidth(Math.min(980, Math.max(640, bounds.getWidth() - 48)));
        stage.setHeight(Math.min(680, Math.max(440, bounds.getHeight() - 48)));
        stage.setMinWidth(640); stage.setMinHeight(440);
        stage.setTitle("Luffy — streaming P2P"); stage.show();
        torrents.setConnectionLimits(connectionLimitSettings.limits());
        torrents.setAbuseProtectionConfig(abuseProtectionSettings.config());
        torrents.setStreamingStartupPieces(streamingStartupSettings.startupPieces());
        torrents.setStatusListener(message -> Platform.runLater(() -> status.setText(message)));
        torrents.setTemporaryWatchCompletedListener(magnet -> swarmAssistManager.considerTemporaryWatch(magnet)
                .exceptionally(error -> {
                    diagnostics.log("SWARM ASSIST nao pode ser avaliado: " + error.getMessage());
                    return null;
                }));
        torrents.setSwarmAssistActivityListener(swarmAssistManager::recordActivity);
        swarmAssistManager.startMaintenance();
        Platform.runLater(this::configureAutomaticConnectivity);
    }
    private HBox header() {
        Label logo = new Label("luffy"); logo.getStyleClass().add("logo");
        Label subtitle = new Label("Streaming P2P, do seu jeito");
        HBox box = new HBox(14, logo, subtitle); box.setAlignment(Pos.CENTER_LEFT); box.setPadding(new Insets(24, 0, 18, 0)); return box;
    }
    private void configureAutomaticConnectivity() {
        boolean needsFirewallPermission = settings.get("network.firewall.configured").isEmpty();
        connectivity.configure(luffyExecutable(), needsFirewallPermission, profile -> Platform.runLater(() -> {
            torrents.setConnectivityProfile(profile);
            refreshConnectivityPanel();
            if (profile.firewallConfigured()) settings.put("network.firewall.configured", "true");
            if (savedLibrariesLoaded.compareAndSet(false, true)) loadSavedLibraries();
        }), message -> Platform.runLater(() -> status.setText(message)));
    }
    private Path luffyExecutable() {
        String packaged = System.getProperty("jpackage.app-path");
        if (packaged != null && !packaged.isBlank()) return Path.of(packaged);
        return ProcessHandle.current().info().command().map(Path::of).orElse(Path.of(System.getProperty("java.home"), "bin", "java.exe"));
    }
    private Tab watchTab() {
        TextField magnet = new TextField(); magnet.setPromptText("Cole um magnet:?xt=urn:btih:…"); magnet.setPrefWidth(620);
        Button open = new Button("Abrir magnet");
        open.setOnAction(e -> askWatchMode(magnet.getText()));
        Label heading = new Label("Assistir"); heading.getStyleClass().add("section-title");
        Label hint = new Label("A reprodução começa quando o buffer de segurança estiver disponível."); hint.getStyleClass().add("muted");
        Label playlistTitle = new Label("Arquivos do torrent"); playlistTitle.getStyleClass().add("panel-title");
        watchPlaylist.setPlaceholder(new Label("Abra um magnet para carregar os arquivos."));
        // Escolher um item é a autorização explícita para começar o download dele.
        watchPlaylist.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 1
                    && watchPlaylist.getSelectionModel().getSelectedItem() != null) {
                openWatchEntry(watchPlaylist.getSelectionModel().getSelectedItem());
            }
        });
        VBox playlist = new VBox(10, playlistTitle, watchPlaylist); playlist.getStyleClass().add("library-panel"); playlist.setMinWidth(300); VBox.setVgrow(watchPlaylist, Priority.ALWAYS);
        SplitPane playback = new SplitPane(playerSurface(), playlist); playback.setDividerPositions(.60);
        VBox box = new VBox(16, heading, hint, new HBox(10, magnet, open), status, playback); box.setPadding(new Insets(24));
        VBox.setVgrow(playback, Priority.ALWAYS);
        return new Tab("Assistir", box);
    }
    private Tab settingsTab() {
        Label heading = new Label("Configurações"); heading.getStyleClass().add("section-title");
        Label title = new Label("Buffer inicial para streaming"); title.getStyleClass().add("panel-title");
        Label recommendation = new Label("Recomendado: 15 chunks verificados."); recommendation.getStyleClass().add("settings-recommendation");
        Label explanation = new Label("O Luffy só inicia a reprodução de um torrent incompleto após receber esta quantidade de chunks consecutivos e verificados. Um valor maior reduz travamentos, mas aumenta a espera inicial.");
        explanation.setWrapText(true); explanation.getStyleClass().add("muted");
        Label warning = new Label("⚠ Atenção: quanto menor a quantidade, mais rápido o vídeo inicia, mas maior é a chance de travar ou voltar para buffering.");
        warning.setWrapText(true); warning.getStyleClass().add("settings-warning");
        Spinner<Integer> chunks = new Spinner<>(BtTorrentGateway.MIN_STREAM_STARTUP_PIECES,
                BtTorrentGateway.MAX_STREAM_STARTUP_PIECES, streamingStartupSettings.startupPieces());
        chunks.setEditable(true); chunks.setPrefWidth(150);
        Button save = new Button("Salvar");
        Label saved = new Label(); saved.getStyleClass().add("muted");
        save.setOnAction(event -> {
            try {
                int value = StreamingStartupSettings.normalize(Integer.parseInt(chunks.getEditor().getText().trim()));
                chunks.getValueFactory().setValue(value);
                streamingStartupSettings.setStartupPieces(value);
                torrents.setStreamingStartupPieces(value);
                saved.setText("Salvo. Novas reproduções vão aguardar " + value + " chunks verificados.");
            } catch (RuntimeException error) {
                saved.setText("Informe uma quantidade entre " + BtTorrentGateway.MIN_STREAM_STARTUP_PIECES
                        + " e " + BtTorrentGateway.MAX_STREAM_STARTUP_PIECES + ".");
            }
        });
        HBox editor = new HBox(10, new Label("Chunks verificados:"), chunks, save); editor.setAlignment(Pos.CENTER_LEFT);
        VBox panel = new VBox(12, title, recommendation, explanation, warning, editor, saved);
        panel.getStyleClass().add("content-panel"); panel.setMaxWidth(680);
        VBox box = new VBox(18, heading, panel); box.setPadding(new Insets(24));
        return new Tab("Configurações", box);
    }
    private StackPane playerSurface() {
        nowPlaying.getStyleClass().add("muted");
        playerControls = createPlayerControls(false);
        playerControls.getStyleClass().add("player-controls");
        playerPlaceholder.getChildren().setAll(nowPlaying); playerPlaceholder.setAlignment(Pos.CENTER);
        mediaView.setPreserveRatio(true); mediaView.fitWidthProperty().bind(playerSurface.widthProperty()); mediaView.fitHeightProperty().bind(playerSurface.heightProperty());
        backendVideoSurface.setVisible(false);
        StackPane.setAlignment(playerControls, Pos.BOTTOM_CENTER); StackPane.setMargin(playerControls, new Insets(12));
        hideControls.setOnFinished(e -> {
            VBox active = fullscreenPlayerStage == null ? playerControls : fullscreenControls;
            if (active != null) active.setVisible(false);
        });
        playerSurface.setOnMouseMoved(e -> revealControls());
        playerSurface.setOnMouseEntered(e -> revealControls());
        playerSurface.getChildren().setAll(mediaView, backendVideoSurface, playerPlaceholder, playerControls); playerSurface.getStyleClass().add("player"); playerSurface.setMinHeight(220); return playerSurface;
    }
    private void revealControls() {
        VBox active = fullscreenPlayerStage == null ? playerControls : fullscreenControls;
        if (active == null) return;
        active.setVisible(true); hideControls.playFromStart();
    }
    private VBox createPlayerControls(boolean fullscreen) {
        Button play = commandButton("▶", "Reproduzir"); play.setOnAction(e -> resumePlayback());
        Button pause = commandButton("⏸", "Pausar"); pause.setOnAction(e -> pausePlayback());
        Button stop = commandButton("⏹", "Parar"); stop.setOnAction(e -> stopPlayback());
        Slider seek = new Slider(0, 1, 0); seek.setMinWidth(120); seek.setDisable(true); HBox.setHgrow(seek, Priority.ALWAYS);
        Label time = new Label("00:00 / --:--"); time.getStyleClass().add("muted");
        seek.setOnMouseReleased(e -> seekToFraction(seek.getValue()));
        seek.valueChangingProperty().addListener((observable, wasChanging, isChanging) -> {
            if (wasChanging && !isChanging) seekToFraction(seek.getValue());
        });
        seekControls.add(seek); playbackTimeLabels.add(time); refreshSeekControls();
        Slider volume = new Slider(0, 1, playbackVolume); volume.setPrefWidth(96);
        volume.valueProperty().addListener((observable, oldValue, value) -> {
            if (!synchronizingAudioControls) setPlaybackVolume(value.doubleValue());
        });
        Button mute = commandButton("", "Ativar ou silenciar som");
        mute.setOnAction(e -> setPlaybackMuted(!playbackMuted));
        volumeControls.add(volume); muteControls.add(mute); refreshAudioControls();
        MenuButton audioTracks = trackButton("🔊", "Faixa de áudio");
        MenuButton subtitles = trackButton("💬", "Legendas");
        audioTrackControls.add(audioTracks); subtitleTrackControls.add(subtitles); refreshTrackControls();
        Button display = commandButton(fullscreen ? "↙" : "⛶", fullscreen ? "Sair da tela cheia" : "Tela cheia");
        display.setOnAction(e -> {
            if (fullscreen) minimizePlayer(); else togglePlayerFullscreen();
        });
        HBox timeline = new HBox(8, time, seek, mute, volume); timeline.setAlignment(Pos.CENTER_LEFT);
        timeline.getStyleClass().add("player-controls-top");
        HBox commands = new HBox(8, play, pause, stop, audioTracks, subtitles, display); commands.setAlignment(Pos.CENTER);
        commands.getStyleClass().add("player-controls-bottom");
        VBox controls = new VBox(8, timeline, commands); controls.setAlignment(Pos.CENTER); controls.getStyleClass().add("player-controls");
        return controls;
    }

    private Button commandButton(String icon, String tooltip) {
        Button button = new Button(icon);
        button.getStyleClass().add("player-command");
        button.setTooltip(new Tooltip(tooltip));
        return button;
    }

    private MenuButton trackButton(String icon, String tooltip) {
        MenuButton button = new MenuButton(icon);
        button.getStyleClass().addAll("player-command", "player-track");
        button.setTooltip(new Tooltip(tooltip));
        button.setDisable(true);
        return button;
    }

    /** Busca local sem depender do transporte que estiver ativo no aplicativo. */
    private void seekToFraction(double fraction) {
        if (synchronizingSeekControls || !currentPlaybackDuration.greaterThan(Duration.ZERO)) return;
        long targetMillis = Math.round(currentPlaybackDuration.toMillis() * Math.max(0d, Math.min(1d, fraction)));
        if (backendPlayer != null && backendPlayer.isSeekable()) {
            backendPlayer.seek(java.time.Duration.ofMillis(targetMillis));
            updatePlaybackPosition(Duration.millis(targetMillis));
        } else if (mediaPlayer != null) {
            mediaPlayer.seek(Duration.millis(targetMillis));
            updatePlaybackPosition(Duration.millis(targetMillis));
        }
    }

    private void updatePlaybackPosition(Duration position) {
        currentPlaybackPosition = position == null || position.lessThan(Duration.ZERO) ? Duration.ZERO : position;
        refreshSeekControls();
    }

    private void updatePlaybackDuration(Duration duration) {
        currentPlaybackDuration = duration == null || duration.lessThanOrEqualTo(Duration.ZERO) ? Duration.UNKNOWN : duration;
        refreshSeekControls();
    }

    private void resetSeekControls() {
        currentPlaybackPosition = Duration.ZERO;
        currentPlaybackDuration = Duration.UNKNOWN;
        refreshSeekControls();
    }

    private void refreshSeekControls() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(this::refreshSeekControls);
            return;
        }
        double fraction = currentPlaybackDuration.greaterThan(Duration.ZERO)
                ? Math.max(0d, Math.min(1d, currentPlaybackPosition.toMillis() / currentPlaybackDuration.toMillis()))
                : 0d;
        String time = formatPlaybackDuration(currentPlaybackPosition) + " / " + formatPlaybackDuration(currentPlaybackDuration);
        synchronizingSeekControls = true;
        try {
            for (Slider seek : seekControls) {
                seek.setDisable(!currentPlaybackDuration.greaterThan(Duration.ZERO));
                seek.setValue(fraction);
            }
            playbackTimeLabels.forEach(label -> label.setText(time));
        } finally {
            synchronizingSeekControls = false;
        }
    }

    private String formatPlaybackDuration(Duration duration) {
        if (duration == null || duration.isUnknown() || duration.isIndefinite() || duration.lessThan(Duration.ZERO)) return "--:--";
        long totalSeconds = (long) Math.floor(duration.toSeconds());
        return String.format("%02d:%02d", totalSeconds / 60, totalSeconds % 60);
    }

    private void refreshAudioControls() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(this::refreshAudioControls);
            return;
        }
        synchronizingAudioControls = true;
        try {
            for (Slider volume : volumeControls) {
                volume.setValue(playbackVolume);
            }
            String icon = playbackMuted ? "🔇" : "🔊";
            String tooltip = playbackMuted ? "Ativar som" : "Silenciar";
            muteControls.forEach(button -> {
                button.setText(icon);
                button.setTooltip(new Tooltip(tooltip));
            });
        } finally {
            synchronizingAudioControls = false;
        }
    }

    private void refreshTrackControls() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(this::refreshTrackControls);
            return;
        }
        List<MediaTrack> audioTracks = backendPlayer == null ? List.of() : backendPlayer.audioTracks();
        List<MediaTrack> subtitleTracks = backendPlayer == null ? List.of() : backendPlayer.subtitleTracks();
        for (MenuButton menu : audioTrackControls) {
            populateTrackMenu(menu, audioTracks, true);
        }
        for (MenuButton menu : subtitleTrackControls) {
            populateTrackMenu(menu, subtitleTracks, false);
        }
    }

    private void populateTrackMenu(MenuButton menu, List<MediaTrack> tracks, boolean audio) {
        menu.getItems().clear();
        if (backendPlayer == null) {
            menu.setDisable(true);
            return;
        }
        if (tracks.isEmpty()) {
            MenuItem unavailable = new MenuItem(audio ? "Nenhuma faixa de áudio" : "Nenhuma legenda embutida");
            unavailable.setDisable(true);
            menu.getItems().add(unavailable);
        } else {
            for (MediaTrack track : tracks) {
                MenuItem item = new MenuItem((track.selected() ? "✓ " : "") + track.label());
                item.setOnAction(event -> selectTrack(track, audio));
                menu.getItems().add(item);
            }
        }
        if (!audio) {
            menu.getItems().add(new SeparatorMenuItem());
            MenuItem external = new MenuItem("Adicionar arquivo de legenda…");
            external.setOnAction(event -> chooseExternalSubtitle());
            menu.getItems().add(external);
        }
        menu.setDisable(audio && tracks.isEmpty());
    }

    private void selectTrack(MediaTrack track, boolean audio) {
        MediaPlayerBackend player = backendPlayer;
        if (player == null) return;
        boolean selected = audio ? player.selectAudioTrack(track.id()) : player.selectSubtitleTrack(track.id());
        if (!selected) {
            status.setText("Não foi possível trocar a faixa selecionada.");
            return;
        }
        refreshTrackControls();
    }

    private void chooseExternalSubtitle() {
        MediaPlayerBackend player = backendPlayer;
        if (player == null) return;
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Selecionar legenda externa");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Legendas", "*.srt", "*.ass", "*.ssa", "*.vtt"));
        java.io.File subtitle = chooser.showOpenDialog(fullscreenPlayerStage == null ? primaryStage : fullscreenPlayerStage);
        if (subtitle == null) return;
        if (player.setExternalSubtitle(subtitle.toPath().toUri())) {
            status.setText("Legenda externa adicionada.");
            refreshTrackControls();
        } else {
            status.setText("Não foi possível adicionar esta legenda.");
        }
    }
    private void togglePlayerFullscreen() {
        if (fullscreenPlayerStage != null) { fullscreenPlayerStage.close(); return; }
        fullscreenPlayerStage = new Stage(StageStyle.UNDECORATED); fullscreenPlayerStage.initOwner(primaryStage);
        boolean backendActive = backendPlayer != null;
        javafx.scene.Node activeVideo;
        if (backendActive) {
            fullscreenBackendView = backendPlayer.createVideoView();
            activeVideo = fullscreenBackendView;
        } else {
            fullscreenMediaView = new MediaView(mediaPlayer); fullscreenMediaView.setPreserveRatio(true); fullscreenMediaView.setMouseTransparent(true);
            activeVideo = fullscreenMediaView;
        }
        StackPane video = new StackPane(activeVideo); video.setStyle("-fx-background-color: black;");
        if (fullscreenMediaView != null) { fullscreenMediaView.fitWidthProperty().bind(video.widthProperty()); fullscreenMediaView.fitHeightProperty().bind(video.heightProperty()); }
        fullscreenControls = createPlayerControls(true); StackPane.setAlignment(fullscreenControls, Pos.BOTTOM_CENTER); StackPane.setMargin(fullscreenControls, new Insets(18)); video.getChildren().add(fullscreenControls);
        Scene scene = new Scene(video, 960, 540);
        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE) { event.consume(); minimizePlayer(); }
        });
        fullscreenPlayerStage.setScene(scene); fullscreenPlayerStage.setOnHidden(e -> restoreEmbeddedPlayer());
        video.addEventFilter(MouseEvent.MOUSE_MOVED, e -> revealControls()); video.addEventFilter(MouseEvent.MOUSE_ENTERED, e -> revealControls());
        mediaView.setVisible(false); backendVideoSurface.setVisible(false); fullscreenPlayerStage.show(); fullscreenPlayerStage.setFullScreen(true); revealControls();
    }
    private void minimizePlayer() {
        if (fullscreenPlayerStage != null) fullscreenPlayerStage.close();
    }
    private void restoreEmbeddedPlayer() {
        fullscreenPlayerStage = null; fullscreenMediaView = null; fullscreenBackendView = null; fullscreenControls = null;
        seekControls.removeIf(control -> control.getScene() == null);
        playbackTimeLabels.removeIf(label -> label.getScene() == null);
        volumeControls.removeIf(control -> control.getScene() == null);
        muteControls.removeIf(control -> control.getScene() == null);
        audioTrackControls.removeIf(control -> control.getScene() == null);
        subtitleTrackControls.removeIf(control -> control.getScene() == null);
        mediaView.setVisible(backendPlayer == null); backendVideoSurface.setVisible(backendPlayer != null); revealControls();
    }
    private Tab libraryTab() {
        Button add = new Button("Adicionar biblioteca"); add.setOnAction(e -> selectLibrary());
        Button watchSelected = new Button("Assistir selecionado"); watchSelected.setOnAction(e -> watchSelectedVideo());
        Button copyMagnet = new Button("Copiar link selecionado"); copyMagnet.setOnAction(e -> copyMagnet());
        Label heading = new Label("Meus vídeos"); heading.getStyleClass().add("section-title");
        Label explanation = new Label("Adicione quantas pastas quiser. Cada biblioteca possui um magnet e compartilhamento próprios."); explanation.getStyleClass().add("muted");
        Label libraryLabel = new Label("Bibliotecas"); libraryLabel.getStyleClass().add("panel-title");
        Label contentLabel = new Label("Vídeos encontrados"); contentLabel.getStyleClass().add("panel-title");
        Label linkLabel = new Label("Link magnet da biblioteca"); linkLabel.getStyleClass().add("panel-title");
        libraryTitle.getStyleClass().add("library-name");
        libraries.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, selected) -> { if (selected != null) openLibrary(selected); });
        videos.setOnMouseClicked(event -> {
            TreeItem<LibraryNode> selected = videos.getSelectionModel().getSelectedItem();
            if (selected != null && selected.getValue().video() && event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) playLocal(new FoundVideo(selected.getValue().name(), selected.getValue().path()));
        });
        Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox toolbar = new HBox(12, heading, spacer, add); toolbar.setAlignment(Pos.CENTER_LEFT);
        VBox left = new VBox(10, libraryLabel, libraries); left.getStyleClass().add("library-panel"); left.setMinWidth(220); VBox.setVgrow(libraries, Priority.ALWAYS);
        HBox videoActions = new HBox(watchSelected); videoActions.setAlignment(Pos.CENTER_RIGHT);
        VBox content = new VBox(10, libraryTitle, contentLabel, videos, videoActions); content.getStyleClass().add("content-panel"); VBox.setVgrow(videos, Priority.ALWAYS);
        magnets.setPrefHeight(62); magnets.setMaxHeight(82);
        VBox linkPanel = new VBox(8, linkLabel, magnets, copyMagnet); linkPanel.getStyleClass().add("link-panel");
        BorderPane right = new BorderPane(content); right.setBottom(linkPanel);
        SplitPane split = new SplitPane(left, right); split.setDividerPositions(.30); VBox.setVgrow(split, Priority.ALWAYS);
        VBox box = new VBox(10, toolbar, explanation, split); box.setPadding(new Insets(24)); return new Tab("Meus vídeos", box);
    }
    /** Aba Logs: teste BitTorrent/DHT isolado, sem alterar as abas Assistir e Meus vídeos. */
    private Tab diagnosticsTab() {
        Label heading = new Label("Diagnóstico P2P"); heading.getStyleClass().add("section-title");
        Label explanation = new Label("Teste real no novo motor BitTorrent: A cria teste.txt com OLA LUFFY; B o baixa pelo magnet, DHT e fallbacks automáticos."); explanation.getStyleClass().add("muted");
        Label matrixHeading = new Label("Matriz do teste teste.txt"); matrixHeading.getStyleClass().add("panel-title");
        ComboBox<P2pDiagnosticScenario> scenario = new ComboBox<>(); scenario.getItems().setAll(P2pDiagnosticScenario.values()); scenario.getSelectionModel().select(P2pDiagnosticScenario.DIRECT_IPV4);
        Label scenarioExpected = new Label(); scenarioExpected.getStyleClass().add("muted");
        Runnable updateScenario = () -> { P2pDiagnosticScenario selected = scenario.getValue(); scenarioExpected.setText("Esperado: " + selected.expected() + ". " + selected.guidance()); };
        scenario.valueProperty().addListener((observable, previous, selected) -> updateScenario.run()); updateScenario.run();
        VBox matrixPanel = new VBox(8, matrixHeading, scenario, scenarioExpected); matrixPanel.getStyleClass().add("library-panel");

        Label networkHeading = new Label("Rede local"); networkHeading.getStyleClass().add("panel-title");
        Label networkHint = new Label("Estado observado da máquina. Endpoints apenas observados não são tratados como porta pública confirmada."); networkHint.getStyleClass().add("muted");
        TextArea networkOutput = new TextArea(); networkOutput.setEditable(false); networkOutput.setWrapText(false); networkOutput.setPrefRowCount(15);
        networkOutput.setStyle("-fx-font-family: 'Consolas'; -fx-font-size: 12px;");
        connectivityPanelOutput = networkOutput;
        refreshConnectivityPanel();
        Button refreshNetwork = new Button("Atualizar rede"); refreshNetwork.setOnAction(e -> refreshConnectivityPanel());
        Button copyNetwork = new Button("Copiar rede"); copyNetwork.setOnAction(e -> { ClipboardContent content = new ClipboardContent(); content.putString(networkOutput.getText()); Clipboard.getSystemClipboard().setContent(content); });
        HBox networkActions = new HBox(10, refreshNetwork, copyNetwork); networkActions.setAlignment(Pos.CENTER_LEFT);
        VBox networkPanel = new VBox(8, networkHeading, networkHint, networkOutput, networkActions); networkPanel.getStyleClass().add("library-panel");

        Label peersHeading = new Label("Peers do swarm"); peersHeading.getStyleClass().add("panel-title");
        Label peersHint = new Label("Estado por peer: origem, TCP, uTP, BEP 55, rendezvous e resultado da conexão."); peersHint.getStyleClass().add("muted");
        TextArea peerOutput = new TextArea(); peerOutput.setEditable(false); peerOutput.setWrapText(false); peerOutput.setPrefRowCount(14);
        peerOutput.setStyle("-fx-font-family: 'Consolas'; -fx-font-size: 12px;");
        peerPanelOutput = peerOutput;
        refreshPeerPanel();
        Button refreshPeers = new Button("Atualizar peers"); refreshPeers.setOnAction(e -> refreshPeerPanel());
        Button copyPeers = new Button("Copiar peers"); copyPeers.setOnAction(e -> { ClipboardContent content = new ClipboardContent(); content.putString(peerOutput.getText()); Clipboard.getSystemClipboard().setContent(content); });
        HBox peerActions = new HBox(10, refreshPeers, copyPeers); peerActions.setAlignment(Pos.CENTER_LEFT);
        VBox peersPanel = new VBox(8, peersHeading, peersHint, peerOutput, peerActions); peersPanel.getStyleClass().add("library-panel");

        TextArea testMagnet = new TextArea();
        testMagnet.setPromptText("Na máquina A, crie o teste e copie o magnet. Na máquina B, cole o magnet aqui.");
        testMagnet.setPrefRowCount(3); testMagnet.setWrapText(true);
        Button createSeed = new Button("A — Criar e semear teste.txt");
        createSeed.setOnAction(e -> {
            try {
                var source = torrents.createAndSeedDiagnosticTest(scenario.getValue());
                testMagnet.setText(source.magnet());
                refreshConnectivityPanel();
                refreshPeerPanel();
                status.setText("Teste " + source.scenario().label() + " criado e semeando. Copie o magnet para a máquina B.");
            } catch (IllegalStateException error) { status.setText(error.getMessage()); }
        });
        Button copyTestMagnet = new Button("Copiar magnet do teste");
        copyTestMagnet.setOnAction(e -> { ClipboardContent content = new ClipboardContent(); content.putString(testMagnet.getText()); Clipboard.getSystemClipboard().setContent(content); });
        Button downloadTest = new Button("B — Baixar teste.txt");
        downloadTest.setOnAction(e -> {
            try {
                torrents.downloadDiagnosticTest(testMagnet.getText(), scenario.getValue(), result -> Platform.runLater(() -> status.setText(result.contentVerified()
                        ? "Teste P2P concluído: " + result.outcome() + ". teste.txt contém OLA LUFFY."
                        : "Teste P2P encerrado: " + result.outcome() + " — " + result.detail())));
                status.setText("Teste BitTorrent iniciado: procurando o infoHash na DHT.");
                refreshConnectivityPanel();
                refreshPeerPanel();
            } catch (IllegalArgumentException error) { status.setText(error.getMessage()); }
        });
        TextArea output = new TextArea(diagnostics.snapshot()); output.setEditable(false); output.setWrapText(false); output.setStyle("-fx-font-family: 'Consolas'; -fx-font-size: 11px;");
        bindDiagnosticOutput(output);
        Button copy = new Button("Copiar log"); copy.setOnAction(e -> { ClipboardContent content = new ClipboardContent(); content.putString(output.getText()); Clipboard.getSystemClipboard().setContent(content); });
        Button clear = new Button("Limpar"); clear.setOnAction(e -> { diagnostics.clear(); output.clear(); });
        HBox actions = new HBox(10, createSeed, copyTestMagnet, downloadTest, copy, clear); actions.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(12, heading, matrixPanel, networkPanel, peersPanel, explanation, testMagnet, actions, output); box.setPadding(new Insets(24)); VBox.setVgrow(output, Priority.ALWAYS);
        return new Tab("Diagnóstico P2P", box);
    }

    /** Aba simples e copiável para o teste teste.txt, sempre conectada ao mesmo motor BitTorrent do Luffy. */
    private Tab logsTab() {
        Label heading = new Label("Logs"); heading.getStyleClass().add("section-title");
        Label explanation = new Label("Máquina A cria e semeia teste.txt com OLA LUFFY. Máquina B cola o magnet e recebe o arquivo pelo motor BitTorrent, DHT e fallbacks automáticos.");
        explanation.getStyleClass().add("muted"); explanation.setWrapText(true);
        Label testStatus = new Label("Pronto para criar ou baixar o teste."); testStatus.getStyleClass().add("muted");

        TextArea testMagnet = new TextArea();
        testMagnet.setPromptText("Na máquina A, crie o teste e copie o magnet. Na máquina B, cole o magnet aqui.");
        testMagnet.setPrefRowCount(3); testMagnet.setWrapText(true);
        Button createSeed = new Button("A — Criar e semear teste.txt");
        createSeed.setOnAction(e -> {
            try {
                var source = torrents.createAndSeedDiagnosticTest(P2pDiagnosticScenario.DIRECT_IPV4);
                testMagnet.setText(source.magnet());
                testStatus.setText("teste.txt criado com OLA LUFFY e semeando. Compartilhe o magnet com a máquina B.");
                diagnostics.log(P2pDiagnostics.Layer.RESULT, "TESTE A PRONTO: magnet copiado pela aba Logs; conteúdo=OLA LUFFY.");
            } catch (IllegalStateException error) { testStatus.setText(error.getMessage()); }
        });
        Button copyMagnet = new Button("Copiar magnet");
        copyMagnet.setOnAction(e -> {
            ClipboardContent content = new ClipboardContent(); content.putString(testMagnet.getText());
            Clipboard.getSystemClipboard().setContent(content);
        });
        Button downloadTest = new Button("B — Baixar teste.txt");
        downloadTest.setOnAction(e -> {
            try {
                torrents.downloadDiagnosticTest(testMagnet.getText(), P2pDiagnosticScenario.DIRECT_IPV4, result -> Platform.runLater(() ->
                        testStatus.setText(result.contentVerified()
                                ? "Teste concluído: " + result.outcome() + ". Conteúdo recebido: OLA LUFFY."
                                : "Teste encerrado: " + result.outcome() + " — " + result.detail())));
                testStatus.setText("Motor BitTorrent iniciado: procurando peers e testando caminhos automaticamente.");
            } catch (IllegalArgumentException error) { testStatus.setText(error.getMessage()); }
        });

        Label terminalHeading = new Label("Terminal do Luffy — DHT, conectividade e motor BitTorrent"); terminalHeading.getStyleClass().add("panel-title");
        TextArea terminal = new TextArea(diagnostics.snapshot());
        terminal.setEditable(false); terminal.setWrapText(false);
        terminal.setStyle("-fx-font-family: 'Consolas'; -fx-font-size: 11px;");
        bindDiagnosticOutput(terminal);
        Button copyLog = new Button("Copiar todos os logs");
        copyLog.setOnAction(e -> { ClipboardContent content = new ClipboardContent(); content.putString(terminal.getText()); Clipboard.getSystemClipboard().setContent(content); });
        Button clearLog = new Button("Limpar terminal");
        clearLog.setOnAction(e -> { diagnostics.clear(); terminal.clear(); });
        HBox testActions = new HBox(10, createSeed, copyMagnet, downloadTest); testActions.setAlignment(Pos.CENTER_LEFT);
        HBox logActions = new HBox(10, copyLog, clearLog); logActions.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(12, heading, explanation, testMagnet, testActions, testStatus, terminalHeading, terminal, logActions);
        box.setPadding(new Insets(24)); VBox.setVgrow(terminal, Priority.ALWAYS);
        return new Tab("Logs", box);
    }
    private void refreshConnectivityPanel() {
        if (connectivityPanelOutput == null) return;
        connectivityPanelOutput.setText(torrents.connectivityVisualReport(connectivity.localIpv4Addresses()));
    }

    private void refreshPeerPanel() {
        if (peerPanelOutput == null) return;
        peerPanelOutput.setText(torrents.peerVisualReport());
    }

    private Tab legacyHelloDiagnosticsTab() {
        /* Sistema de olá removido: teste substituído pelo fluxo BitTorrent/DHT acima.
        Label heading = new Label("Diagnóstico P2P"); heading.getStyleClass().add("section-title");
        Label explanation = new Label("Log temporário e copiável: DHT, announce, peers, TCP e o teste de \"olá\"."); explanation.getStyleClass().add("muted");
        Label helloHint = new Label("Teste entre máquinas: ative manualmente o receptor no Windows. Ele escuta em TCP " + P2pHelloService.PORT
                + ". Endereço LAN: " + hello.localIpv4Addresses() + ". No Ubuntu, informe este IP e clique em Enviar olá.");
        helloHint.setWrapText(true); helloHint.getStyleClass().add("muted");
        TextField destination = new TextField(); destination.setPromptText("IP:porta do outro Luffy, ex.: 192.168.1.5:" + P2pHelloService.PORT); destination.setPrefWidth(420);
        Button activateHello = new Button("Ativar teste de olá"); activateHello.setOnAction(e -> activateHelloTest());
        Button sendHello = new Button("Enviar olá"); sendHello.setOnAction(e -> hello.send(destination.getText()));
        TextArea output = new TextArea(diagnostics.snapshot()); output.setEditable(false); output.setWrapText(false); output.setStyle("-fx-font-family: 'Consolas'; -fx-font-size: 11px;");
        bindDiagnosticOutput(output);
        Button copy = new Button("Copiar log"); copy.setOnAction(e -> { ClipboardContent content = new ClipboardContent(); content.putString(output.getText()); Clipboard.getSystemClipboard().setContent(content); });
        Button clear = new Button("Limpar"); clear.setOnAction(e -> { diagnostics.clear(); output.clear(); });
        HBox actions = new HBox(10, activateHello, destination, sendHello, copy, clear); actions.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(12, heading, explanation, helloHint, actions, output); box.setPadding(new Insets(24)); VBox.setVgrow(output, Priority.ALWAYS);
        return new Tab("Diagnóstico P2P", box);
    }
    private void activateHelloTest() {
        Thread.startVirtualThread(() -> {
            try {
                if (System.getProperty("os.name").toLowerCase().contains("win")) {
                    WindowsFirewallManager firewall = new WindowsFirewallManager();
                    if (!firewall.isDiagnosticHelloAllowed(luffyExecutable())) {
                        diagnostics.log("TESTE OLÁ solicitando autorização do Windows apenas para TCP " + P2pHelloService.PORT + ".");
                        firewall.allowDiagnosticHello(luffyExecutable());
                    }
                }
                hello.start();
            } catch (Exception error) {
                diagnostics.log("TESTE OLÁ não foi ativado: " + error.getClass().getSimpleName() + " — " + String.valueOf(error.getMessage()));
            }
        });
    }
        */
        return new Tab();
    }
    private void askWatchMode(String raw) {
        Alert choice = new Alert(Alert.AlertType.CONFIRMATION); choice.setTitle("Como deseja assistir?"); choice.setHeaderText("Escolha o comportamento do cache");
        ButtonType temporary = new ButtonType("Assistir apenas"); ButtonType share = new ButtonType("Assistir e compartilhar"); choice.getButtonTypes().setAll(temporary, share, ButtonType.CANCEL);
        choice.showAndWait().ifPresent(result -> { if (result != ButtonType.CANCEL) openSession(raw, result == share ? WatchMode.SHARE : WatchMode.TEMPORARY); });
    }
    private void openSession(String raw, WatchMode mode) {
        try {
            var magnet = dev.lufi.domain.MagnetLink.parse(raw.trim());
            transitionPreviousWatchIfNeeded(magnet.infoHash());
            if (mode == WatchMode.TEMPORARY) swarmAssistManager.recordUserInteraction(magnet.infoHash());
            if (mode == WatchMode.SHARE) removeWatchOnlySwarm(magnet.infoHash());
            watchContext = new WatchContext(raw.trim(), mode);
            activeWatchInfoHash = magnet.infoHash();
            pendingWatchPath = null;
            selectedStreamingPath = null;
            long metadataRequest = streamingRequest.incrementAndGet();
            List<String> manifest = LuffyManifest.decode(magnet);
            if (mode == WatchMode.TEMPORARY && !manifest.isEmpty()) {
                watchPlaylist.getItems().setAll(manifest.stream()
                        .map(path -> watchEntry(Path.of(path).getFileName().toString(), path, null)).toList());
                status.setText("Arquivos recebidos. Clique em uma música ou vídeo para iniciar somente esse arquivo; nada foi baixado ainda.");
                return;
            }
            StreamingSession session = watchVideo.execute(raw, mode, content -> Platform.runLater(() -> {
                if (isCurrentWatchMetadata(metadataRequest, magnet.infoHash())) onTorrentMetadata(content, raw, mode);
            }));
            status.setText("Buscando os metadados de “" + session.title() + "”. Nenhum arquivo será baixado antes da sua escolha.");
            torrents.checkDhtReachability();
        } catch (IllegalArgumentException ex) { status.setText(ex.getMessage()); }
    }

    /** Trocar de magnet nunca deixa a reprodução e as conexões do anterior disputando o primeiro plano. */
    private void transitionPreviousWatchIfNeeded(String nextInfoHash) {
        if (watchContext == null || activeWatchInfoHash == null || activeWatchInfoHash.equalsIgnoreCase(nextInfoHash)) return;
        String previousInfoHash = activeWatchInfoHash;
        WatchMode previousMode = watchContext.mode();
        streamingRequest.incrementAndGet();
        resetPlayback();
        watchPlaylist.getItems().clear();
        BtTorrentGateway.ForegroundWatchTransition transition = torrents.transitionForegroundWatch(previousInfoHash,
                previousMode, nextInfoHash);
        diagnostics.log(P2pDiagnostics.Layer.RESULT, "[WATCH] NEW MAGNET: previousInfoHash=" + previousInfoHash
                + "; nextInfoHash=" + nextInfoHash + "; previousMode=" + previousMode + "; transition="
                + transition + ".");
    }
    private void onTorrentMetadata(TorrentContent content, String magnet, WatchMode mode) {
        Path folder = content.folder();
        // A prévia de metadados de “Assistir e compartilhar” usa cache temporário.
        // A pasta permanente só é criada após a escolha de um arquivo.
        if (mode == WatchMode.SHARE && selectedStreamingPath != null) {
            LibraryRepository.Library saved = libraryRepository.save(folder, magnet, null);
            LibraryView library = new LibraryView(saved.name(), saved.path(), saved.magnet(), null, true);
            addOrReplaceLibrary(library);
            libraries.getSelectionModel().select(library);
        }
        showWatchPlaylist(content);
    }
    /** Descarta metadados de uma sessão substituída antes que eles alterem a lista ou a pasta persistente. */
    private boolean isCurrentWatchMetadata(long requestId, String infoHash) {
        return streamingRequest.get() == requestId && activeWatchInfoHash != null
                && activeWatchInfoHash.equalsIgnoreCase(infoHash);
    }
    private void showWatchPlaylist(TorrentContent content) {
        List<WatchEntry> entries = content.files().stream()
                .map(path -> watchEntry(path.getFileName().toString(),
                        content.folder().relativize(path).toString().replace('\\', '/'), path))
                .toList();
        watchPlaylist.getItems().setAll(entries);
        if (entries.isEmpty()) loadWatchPlaylist(content.folder());
        else {
            status.setText(selectedStreamingPath == null
                    ? "Lista de arquivos recebida. Clique em uma música, vídeo ou arquivo para baixar somente ele."
                    : selectedFileStatus());
            if (pendingWatchPath != null) {
                String requestedPath = pendingWatchPath;
                pendingWatchPath = null;
                entries.stream().filter(entry -> entry.relativePath().equals(requestedPath)).findFirst()
                        .filter(WatchEntry::playableMedia)
                        .ifPresent(entry -> playStreaming(new FoundVideo(entry.name(), entry.path())));
            }
        }
    }
    private void loadWatchPlaylist(Path folder) {
        Thread.startVirtualThread(() -> {
            try {
                Files.createDirectories(folder);
                for (int attempt = 0; attempt < 30; attempt++) {
                    List<Path> found = listAllFiles(folder);
                    if (!found.isEmpty()) {
                        Platform.runLater(() -> {
                            watchPlaylist.getItems().setAll(found.stream().map(path -> watchEntry(path.getFileName().toString(),
                                    folder.relativize(path).toString().replace('\\', '/'), path)).toList());
                            status.setText(selectedStreamingPath == null
                                    ? "Lista de arquivos recebida. Clique em um arquivo para baixar somente ele."
                                    : selectedFileStatus());
                        });
                        return;
                    }
                    Thread.sleep(2_000);
                }
                Platform.runLater(() -> status.setText("Metadados recebidos; aguardando os primeiros chunks de vídeo."));
            } catch (Exception error) { Platform.runLater(() -> status.setText("Não foi possível preparar a lista de vídeos recebidos.")); }
        });
    }
    private List<Path> listAllFiles(Path folder) throws java.io.IOException {
        try (var paths = Files.walk(folder)) {
            return paths.filter(Files::isRegularFile).toList();
        }
    }
    private WatchEntry watchEntry(String name, String relativePath, Path path) {
        return new WatchEntry(name, relativePath, path, isPlayableMedia(name));
    }
    private boolean isPlayableMedia(String name) {
        String normalized = name.toLowerCase(java.util.Locale.ROOT);
        return List.of(".mp4", ".mkv", ".webm", ".mov", ".avi", ".m4v", ".mpeg", ".mpg", ".ts", ".m2ts", ".mts", ".wmv", ".flv", ".3gp", ".ogv", ".vob", ".asf",
                ".mp3", ".flac", ".wav", ".m4a", ".aac", ".ogg", ".oga", ".opus", ".wma", ".aiff", ".aif", ".alac", ".ac3", ".eac3", ".dts")
                .stream().anyMatch(normalized::endsWith);
    }
    private boolean isVideoFile(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        return List.of(".mp4", ".mkv", ".webm", ".mov", ".avi", ".m4v", ".mpeg", ".mpg", ".ts", ".m2ts", ".mts", ".wmv", ".flv", ".3gp", ".ogv", ".vob", ".asf").stream().anyMatch(name::endsWith);
    }
    private String selectedFileStatus() {
        return watchContext != null && watchContext.mode() == WatchMode.SHARE
                ? "Baixando e compartilhando somente o arquivo selecionado em Documentos\\Luffy."
                : "Baixando temporariamente somente o arquivo selecionado para reprodução.";
    }
    private void openWatchEntry(WatchEntry entry) {
        if (watchContext == null) { status.setText("Cole um magnet antes de escolher um arquivo."); return; }
        if (watchContext.mode() == WatchMode.TEMPORARY && !entry.playableMedia()) {
            status.setText("“Assistir apenas” baixa somente músicas e vídeos. Para baixar este arquivo, escolha “Assistir e compartilhar”.");
            return;
        }
        if (entry.relativePath().equals(selectedStreamingPath) && entry.path() != null) {
            if (entry.playableMedia()) playStreaming(new FoundVideo(entry.name(), entry.path()));
            else status.setText("Este arquivo já é o download selecionado.");
            return;
        }
        selectedStreamingPath = entry.relativePath();
        pendingWatchPath = entry.playableMedia() ? entry.relativePath() : null;
        long metadataRequest = streamingRequest.incrementAndGet();
        WatchContext context = watchContext;
        String contextInfoHash = activeWatchInfoHash;
        status.setText(entry.playableMedia()
                ? "Buscando peers para a mídia selecionada. Apenas este arquivo será baixado."
                : "Buscando peers para o arquivo selecionado. Apenas este arquivo será baixado.");
        try { watchVideo.execute(context.magnet(), context.mode(), entry.relativePath(), content -> Platform.runLater(() -> {
            if (isCurrentWatchMetadata(metadataRequest, contextInfoHash)) {
                onTorrentMetadata(content, context.magnet(), context.mode());
            }
        })); }
        catch (IllegalArgumentException error) { status.setText(error.getMessage()); }
    }
    private void selectLibrary() {
        DirectoryChooser chooser = new DirectoryChooser(); chooser.setTitle("Escolha uma biblioteca de vídeos");
        var selected = chooser.showDialog(libraries.getScene().getWindow()); if (selected == null) return;
        status.setText("Escaneando “" + selected.getName() + "”…");
        Task<List<Path>> scan = new Task<>() { @Override protected List<Path> call() throws Exception { return new LocalVideoScanner().scan(selected.toPath()); } };
        scan.setOnSucceeded(e -> {
            List<Path> found = scan.getValue();
            populateVideoTree(selected.toPath(), found);
            if (found.isEmpty()) status.setText("Nenhum vídeo compatível foi encontrado em “" + selected.getName() + "”. Selecione a pasta correta, não um arquivo.");
            else publishLibrary(selected.toPath());
        });
        scan.setOnFailed(e -> { Throwable cause = scan.getException(); status.setText(cause == null ? "Não foi possível escanear a pasta." : cause.getMessage()); });
        Thread.startVirtualThread(scan);
    }
    private void publishLibrary(Path folder) {
        status.setText("Publicando a biblioteca “" + folder.getFileName() + "” como um único torrent…");
        Task<LibraryView> publish = new Task<>() {
            @Override protected LibraryView call() throws Exception {
                var generator = new TorrentMetainfoGenerator();
                Path output = Path.of(System.getProperty("user.home"), ".lufi", "torrents");
                var torrent = generator.publishDirectory(folder, output);
                swarmAssistManager.promoteToUserOwned(torrent.magnet().infoHash());
                torrents.seed(torrent.torrentFile(), folder.getParent(), folder, torrent.magnet().infoHash());
                String magnet = asMagnet(torrent);
                var saved = libraryRepository.save(folder, magnet, torrent.torrentFile());
                return new LibraryView(saved.name(), saved.path(), saved.magnet(), saved.torrentFile(), true);
            }
        };
        publish.setOnSucceeded(e -> { addOrReplaceLibrary(publish.getValue()); libraries.getSelectionModel().select(publish.getValue()); status.setText("Biblioteca semeando. Copie o link magnet para compartilhar a pasta inteira."); });
        publish.setOnFailed(e -> status.setText(publish.getException().getMessage()));
        Thread.startVirtualThread(publish);
    }
    private String asMagnet(TorrentMetainfoGenerator.PublishedTorrent torrent) {
        String name = torrent.magnet().displayName().orElse(torrent.video().getFileName().toString());
        String magnet = "magnet:?xt=urn:btih:" + torrent.magnet().infoHash() + "&dn=" + URLEncoder.encode(name, StandardCharsets.UTF_8);
        return connectivity.profile().publicPeerEndpoint()
                .map(endpoint -> magnet + "&x.pe=" + URLEncoder.encode(peerAddress(endpoint), StandardCharsets.UTF_8))
                .orElse(magnet);
    }
    private String peerAddress(dev.lufi.infrastructure.ConnectivityProfile.PublicPeerEndpoint endpoint) {
        String host = endpoint.address() instanceof java.net.Inet6Address ? "[" + endpoint.address().getHostAddress() + "]" : endpoint.address().getHostAddress();
        return host + ":" + endpoint.port();
    }
    private void copyMagnet() {
        String magnet = magnets.getSelectionModel().getSelectedItem();
        if (magnet == null) { status.setText("Selecione um link magnet para copiá-lo."); return; }
        ClipboardContent content = new ClipboardContent(); content.putString(magnet);
        Clipboard.getSystemClipboard().setContent(content); status.setText("Link magnet copiado.");
    }
    private void loadSavedLibraries() {
        Thread.startVirtualThread(() -> {
            var saved = libraryRepository.findAll();
            // Bibliotecas locais pertencem a SEEDING SWARMS, nunca à Swarm Assist List.
            for (var library : saved) {
                try { swarmAssistManager.promoteToUserOwned(dev.lufi.domain.MagnetLink.parse(library.magnet()).infoHash()); }
                catch (RuntimeException ignored) { /* registro local inválido não deve impedir a restauração */ }
            }
            Platform.runLater(() -> {
                for (var library : saved) {
                    var item = new LibraryView(library.name(), library.path(), library.magnet(), library.torrentFile(), true);
                    addOrReplaceLibrary(item);
                    if (library.torrentFile() != null && Files.isRegularFile(library.torrentFile()) && Files.isDirectory(library.path())) {
                        try { torrents.seed(library.torrentFile(), library.path().getParent(), library.path(), dev.lufi.domain.MagnetLink.parse(library.magnet()).infoHash()); }
                        catch (RuntimeException ignored) { /* biblioteca continua disponível para abrir localmente */ }
                    } else if (library.magnet() != null && Files.isDirectory(library.path())) {
                        try { torrents.open(dev.lufi.domain.MagnetLink.parse(library.magnet()), WatchMode.SHARE); }
                        catch (RuntimeException ignored) { /* o conteúdo local continua navegável */ }
                    }
                }
                swarmAssistManager.restorePersisted().exceptionally(error -> {
                    diagnostics.log("SWARM ASSIST RESTORE falhou: " + error.getMessage());
                    return null;
                });
                Path cache = Path.of(System.getProperty("user.home"), ".lufi", "cache");
                if (Files.isDirectory(cache)) libraries.getItems().add(new LibraryView("Vídeos recebidos (cache P2P)", cache, null, null, false));
            });
        });
    }

    /** Um download permanente/seed deixa de ocupar vaga da lista passiva. */
    private void removeWatchOnlySwarm(String infoHash) {
        swarmAssistManager.promoteToUserOwned(infoHash);
    }
    private void addOrReplaceLibrary(LibraryView library) {
        for (int index = 0; index < libraries.getItems().size(); index++) {
            if (libraries.getItems().get(index).path().equals(library.path())) { libraries.getItems().set(index, library); return; }
        }
        libraries.getItems().add(library);
    }
    private void openLibrary(LibraryView library) {
        libraryTitle.setText(library.name() + (library.seeding() ? "  •  semeando" : "  •  cache P2P"));
        if (library.magnet() != null) magnets.getItems().setAll(library.magnet()); else magnets.getItems().clear();
        status.setText("Abrindo “" + library.name() + "”…");
        Task<List<Path>> scan = new Task<>() { @Override protected List<Path> call() throws Exception { return new LocalVideoScanner().scan(library.path()); } };
        scan.setOnSucceeded(e -> { populateVideoTree(library.path(), scan.getValue()); status.setText(library.name() + ": " + scan.getValue().size() + " vídeo(s) disponíveis" + (library.seeding() ? " e semeando." : ".")); });
        scan.setOnFailed(e -> status.setText("Não foi possível abrir a biblioteca: " + scan.getException().getMessage()));
        Thread.startVirtualThread(scan);
    }
    private void populateVideoTree(Path rootPath, List<Path> foundVideos) {
        TreeItem<LibraryNode> root = new TreeItem<>(new LibraryNode(rootPath.getFileName().toString(), rootPath, false));
        root.setExpanded(true);
        java.util.Map<Path, TreeItem<LibraryNode>> nodes = new java.util.HashMap<>(); nodes.put(rootPath, root);
        for (Path video : foundVideos) {
            Path relative = rootPath.relativize(video); Path current = rootPath;
            for (int index = 0; index < relative.getNameCount(); index++) {
                current = current.resolve(relative.getName(index)); boolean isVideo = index == relative.getNameCount() - 1;
                TreeItem<LibraryNode> parent = nodes.get(current.getParent());
                TreeItem<LibraryNode> item = nodes.get(current);
                if (item == null) {
                    item = new TreeItem<>(new LibraryNode(relative.getName(index).toString(), current, isVideo));
                    if (!isVideo) item.setExpanded(true);
                    parent.getChildren().add(item); nodes.put(current, item);
                }
            }
        }
        sortTree(root); videos.setRoot(root); videos.setShowRoot(true);
    }
    private void sortTree(TreeItem<LibraryNode> item) {
        item.getChildren().sort(java.util.Comparator.comparing((TreeItem<LibraryNode> child) -> child.getValue().video()).thenComparing(child -> child.getValue().name(), String.CASE_INSENSITIVE_ORDER));
        item.getChildren().forEach(this::sortTree);
    }
    private void watchSelectedVideo() {
        TreeItem<LibraryNode> selected = videos.getSelectionModel().getSelectedItem();
        if (selected == null || !selected.getValue().video()) { status.setText("Selecione um vídeo da árvore da biblioteca primeiro."); return; }
        playLocal(new FoundVideo(selected.getValue().name(), selected.getValue().path()));
    }
    private void bindDiagnosticOutput(TextArea output) {
        diagnostics.subscribe(new DiagnosticTextAreaAppender(output));
    }
    private Scene scene(javafx.scene.Parent root) { Scene scene = new Scene(root); scene.getStylesheets().add(getClass().getResource("/lufi.css").toExternalForm()); return scene; }
    private void playLocal(FoundVideo video) {
        long generation = resetPlayback();
        LocalFileMediaSource source = new LocalFileMediaSource(video.path());
        if (!source.isReadableFile()) {
            showPlaybackFailure("LOCAL_FILE", new PlayerPlaybackException(PlayerErrorCode.FILE_NOT_FOUND,
                    "arquivo ausente ou sem permissão de leitura: " + source.path()));
            return;
        }
        if (MediaPlayerBackends.requiresMediaBackend(source)) {
            playWithMediaBackend(video, source, generation);
            return;
        }
        try {
            mediaView.setVisible(true); backendVideoSurface.setVisible(false);
            Media media = new Media(source.uri().toString());
            media.setOnError(() -> { torrents.setForegroundPlaybackActive(false); status.setText("Formato ou codec não suportado: " + video.name() + ". Tente um MP4 com H.264/AAC."); });
            mediaPlayer = new MediaPlayer(media);
            mediaPlayer.setVolume(playbackVolume);
            mediaPlayer.setMute(playbackMuted);
            mediaPlayer.setOnReady(() -> { playerPlaceholder.setVisible(false); nowPlaying.setText("Reproduzindo: " + video.name()); torrents.setForegroundPlaybackActive(true); mediaPlayer.play(); });
            mediaPlayer.setOnEndOfMedia(() -> torrents.setForegroundPlaybackActive(false));
            mediaPlayer.setOnError(() -> { torrents.setForegroundPlaybackActive(false); status.setText("Não foi possível reproduzir este formato neste computador. Tente MP4 com H.264/AAC."); });
            mediaView.setMediaPlayer(mediaPlayer);
            if (fullscreenMediaView != null) fullscreenMediaView.setMediaPlayer(mediaPlayer);
            tabs.getSelectionModel().select(0);
            status.setText("Carregando “" + video.name() + "”…");
        } catch (RuntimeException error) { torrents.setForegroundPlaybackActive(false); playerPlaceholder.setVisible(true); status.setText("Não foi possível abrir este vídeo: " + error.getMessage()); }
    }
    /** Opens an incomplete torrent only through the loopback server. */
    private void playTorrentStreaming(FoundVideo video, String infoHash) {
        long generation = resetPlayback();
        try {
            TorrentStreamingMediaSource source = localMediaServer.register(video.path(), () -> {
                BtTorrentGateway.StreamingMediaWindow window = torrents.streamingMediaWindow(infoHash, video.path());
                return new LuffyLocalMediaServer.VerifiedMediaWindow(window.contentLengthBytes(),
                        window.verifiedPrefixBytes(), window.sessionActive());
            }, (startByte, endByte) -> torrents.prioritizeStreamingRange(infoHash, video.path(), startByte, endByte),
                    (startByte, endByte) -> torrents.isStreamingFileRangeVerified(infoHash, video.path(), startByte, endByte),
                    (buffering, startByte, endByte) -> onStreamingBuffering(generation, buffering, startByte, endByte),
                    (startByte, endByte) -> torrents.streamingRangeProgress(infoHash, video.path(), startByte, endByte)
                            .map(progress -> new LuffyLocalMediaServer.RangeProgress(progress.piecesRequired(), progress.piecesReady()))
                            .orElse(LuffyLocalMediaServer.RangeProgress.unavailable()),
                    (code, detail) -> onStreamingFailure(generation, code, detail));
            streamingMediaPlaybackGeneration = generation;
            streamingPriorityInfoHash = infoHash;
            playWithMediaBackend(video, source, generation);
        } catch (RuntimeException error) {
            terminateStreamingSession(generation);
            torrents.setForegroundPlaybackActive(false);
            showPlaybackFailure("TORRENT_HTTP", new PlayerPlaybackException(PlayerErrorCode.HTTP_STREAM_FAILED,
                    "não foi possível iniciar o streaming HTTP local: " + PlayerPlaybackException.detail(error), error));
        }
    }

    private void playWithMediaBackend(FoundVideo video, MediaSource source, long generation) {
        resetSeekControls();
        playerPlaceholder.setVisible(true);
        mediaView.setVisible(false); backendVideoSurface.setVisible(true); backendVideoSurface.getChildren().clear();
        MediaPlayerBackend created = MediaPlayerBackends.createDefaultBackend();
        String playerSource = playerSource(source);
        diagnostics.log(P2pDiagnostics.Layer.RESULT, "[PLAYER] backend=LIBVLC; source=" + playerSource + ".");
        created.setListener(new MediaPlayerBackend.Listener() {
            @Override public void onStateChanged(MediaPlayerBackend.State state) {
                Platform.runLater(() -> applyBackendPlaybackState(generation, video.name(), playerSource, state));
            }

            @Override public void onError(Throwable error) {
                Platform.runLater(() -> {
                    if (!isCurrentBackendPlayback(generation)) return;
                    reportPlaybackFailure(generation, playerSource, error);
                });
            }

            @Override public void onPositionChanged(java.time.Duration position) {
                Platform.runLater(() -> {
                    if (isCurrentBackendPlayback(generation)) {
                        updatePlaybackPosition(Duration.millis(position.toMillis()));
                        logPlayerPosition(generation, playerSource, position.toMillis());
                    }
                });
            }

            @Override public void onDurationChanged(java.time.Duration duration) {
                Platform.runLater(() -> {
                    if (isCurrentBackendPlayback(generation)) updatePlaybackDuration(Duration.millis(duration.toMillis()));
                });
            }

            @Override public void onDiagnostic(String message) {
                diagnostics.log(P2pDiagnostics.Layer.RESULT, message);
            }
        });
        backendPlayer = created;
        backendVideoSurface.getChildren().setAll(created.createVideoView());
        created.setVolume(playbackVolume);
        created.setMute(playbackMuted);
        tabs.getSelectionModel().select(0);
        status.setText("Carregando vídeo local com libVLC…");
        if (source instanceof TorrentStreamingMediaSource) {
            status.setText("Carregando video pelo streaming local...");
        }
        created.open(source.uri());
    }

    private long resetPlayback() {
        long generation = playbackGeneration.incrementAndGet();
        lastPlayerPositionLogNanos.set(0L);
        resetSeekControls();
        torrents.setForegroundPlaybackActive(false);
        if (mediaPlayer != null) {
            try {
                mediaPlayer.stop();
            } finally {
                mediaPlayer.dispose();
            }
            mediaPlayer = null;
        }
        if (backendPlayer != null) {
            backendPlayer.release();
            backendPlayer = null;
        }
        mediaView.setMediaPlayer(null);
        clearStreamingSessionResources();
        streamingMediaPlaybackGeneration = -1L;
        backendVideoSurface.getChildren().clear();
        backendVideoSurface.setVisible(false);
        refreshTrackControls();
        return generation;
    }

    private boolean isCurrentBackendPlayback(long generation) {
        return playbackGeneration.get() == generation && backendPlayer != null;
    }

    private void terminateStreamingSession(long generation) {
        if (streamingMediaPlaybackGeneration != generation) return;
        clearStreamingSessionResources();
        streamingMediaPlaybackGeneration = -1L;
        diagnostics.log(P2pDiagnostics.Layer.DOWNLOAD,
                "LOCAL MEDIA SERVER SESSION ENDED: route=TORRENT_STREAMING; token=revoked.");
    }

    /** Fecha apenas a ponte de reprodução e devolve o seletor ao download normal. */
    private void clearStreamingSessionResources() {
        localMediaServer.clearRegistrations();
        String infoHash = streamingPriorityInfoHash;
        streamingPriorityInfoHash = null;
        if (infoHash != null && !infoHash.isBlank()) {
            torrents.clearStreamingPriority(infoHash);
        }
    }

    /** A falta temporária de uma piece mantém o player aberto e visível como buffering, não como falha. */
    private void onStreamingBuffering(long generation, boolean buffering, long startByte, long endByte) {
        Platform.runLater(() -> {
            if (!isCurrentBackendPlayback(generation) || streamingMediaPlaybackGeneration != generation) return;
            if (buffering) {
                status.setText("Buffering... aguardando partes do vídeo.");
            } else if (backendPlayer.isPlaying()) {
                status.setText("Reprodução retomada.");
            }
        });
    }

    private void onStreamingFailure(long generation, PlayerErrorCode code, String detail) {
        Platform.runLater(() -> {
            if (!isCurrentBackendPlayback(generation) || streamingMediaPlaybackGeneration != generation) return;
            reportPlaybackFailure(generation, "TORRENT_HTTP", new PlayerPlaybackException(code, detail));
        });
    }

    /** Atualiza somente a UI a partir do estado público do backend, nunca de APIs do vlcj. */
    private void applyBackendPlaybackState(long generation, String videoName, String playerSource, MediaPlayerBackend.State state) {
        if (!isCurrentBackendPlayback(generation)) return;
        logPlayerState(playerSource, state);
        switch (state) {
            case OPENING -> status.setText("Carregando vídeo local com libVLC…");
            case BUFFERING -> status.setText("Aguardando o buffer de reprodução…");
            case PLAYING -> {
                playerPlaceholder.setVisible(false);
                nowPlaying.setText("Reproduzindo: " + videoName);
                status.setText("Reproduzindo “" + videoName + "”.");
                torrents.setForegroundPlaybackActive(true);
                refreshTrackControls();
            }
            case PAUSED -> status.setText("Reprodução pausada.");
            case STOPPED -> {
                terminateStreamingSession(generation);
                torrents.setForegroundPlaybackActive(false);
                updatePlaybackPosition(Duration.ZERO);
                clearPlayerDisplay();
                status.setText("Reprodução parada.");
            }
            case FINISHED -> {
                terminateStreamingSession(generation);
                torrents.setForegroundPlaybackActive(false);
                status.setText("Reprodução concluída.");
            }
            case ERROR -> {
                terminateStreamingSession(generation);
                torrents.setForegroundPlaybackActive(false);
                clearPlayerDisplay();
            }
            case IDLE -> { }
        }
    }

    private static String playerSource(MediaSource source) {
        return source instanceof TorrentStreamingMediaSource ? "TORRENT_HTTP" : "LOCAL_FILE";
    }

    private void logPlayerPosition(long generation, String playerSource, long positionMs) {
        if (!isCurrentBackendPlayback(generation)) return;
        long now = System.nanoTime();
        long previous = lastPlayerPositionLogNanos.get();
        if (previous != 0L && now - previous < PLAYER_POSITION_LOG_INTERVAL_NANOS) return;
        if (lastPlayerPositionLogNanos.compareAndSet(previous, now)) {
            logPlayerSnapshot(playerSource, backendPlayer.getState(), Math.max(0L, positionMs));
        }
    }

    private void logPlayerState(String playerSource, MediaPlayerBackend.State state) {
        logPlayerSnapshot(playerSource, state, Math.max(0L, Math.round(currentPlaybackPosition.toMillis())));
    }

    private void logPlayerSnapshot(String playerSource, MediaPlayerBackend.State state, long positionMs) {
        long durationMs = currentPlaybackDuration.isUnknown() || currentPlaybackDuration.isIndefinite()
                ? -1L : Math.max(0L, Math.round(currentPlaybackDuration.toMillis()));
        boolean buffering = state == MediaPlayerBackend.State.BUFFERING;
        diagnostics.log(P2pDiagnostics.Layer.RESULT, String.format(Locale.ROOT,
                "[PLAYER] backend=LIBVLC; state=%s; source=%s; positionMs=%d; durationMs=%d; buffering=%s; volume=%.2f; muted=%s.",
                state, playerSource, positionMs, durationMs, buffering, playbackVolume, playbackMuted));
    }

    private void resumePlayback() {
        if (backendPlayer != null) {
            mediaView.setVisible(false);
            backendVideoSurface.setVisible(true);
            backendPlayer.play();
            torrents.setForegroundPlaybackActive(true);
        } else if (mediaPlayer != null) {
            mediaPlayer.play();
            torrents.setForegroundPlaybackActive(true);
        }
    }

    private void pausePlayback() {
        if (backendPlayer != null) backendPlayer.pause();
        else if (mediaPlayer != null) mediaPlayer.pause();
    }

    private void stopPlayback() {
        if (backendPlayer != null) backendPlayer.stop();
        else if (mediaPlayer != null) mediaPlayer.stop();
        terminateStreamingSession(playbackGeneration.get());
        torrents.setForegroundPlaybackActive(false);
        updatePlaybackPosition(Duration.ZERO);
        clearPlayerDisplay();
        status.setText("Reprodução parada.");
    }

    private void clearPlayerDisplay() {
        playerPlaceholder.setVisible(true);
        backendVideoSurface.setVisible(false);
        mediaView.setVisible(false);
        nowPlaying.setText("Abra um vídeo da sua biblioteca ou um magnet link.");
        resetSeekControls();
        refreshTrackControls();
    }

    private void setPlaybackVolume(double volume) {
        playbackVolume = Math.max(0d, Math.min(1d, volume));
        if (backendPlayer != null) backendPlayer.setVolume(playbackVolume);
        if (mediaPlayer != null) mediaPlayer.setVolume(playbackVolume);
        refreshAudioControls();
    }

    private void setPlaybackMuted(boolean muted) {
        playbackMuted = muted;
        if (backendPlayer != null) backendPlayer.setMute(muted);
        if (mediaPlayer != null) mediaPlayer.setMute(muted);
        refreshAudioControls();
    }

    private void reportPlaybackFailure(long generation, String playerSource, Throwable error) {
        if (!isCurrentBackendPlayback(generation)) return;
        terminateStreamingSession(generation);
        torrents.setForegroundPlaybackActive(false);
        showPlaybackFailure(playerSource, PlayerPlaybackException.from(error, playerSource));
    }

    private void showPlaybackFailure(String playerSource, PlayerPlaybackException failure) {
        clearPlayerDisplay();
        String detail = PlayerPlaybackException.detail(failure).replace(';', ',');
        diagnostics.log(P2pDiagnostics.Layer.RESULT, "[PLAYER] errorCode=" + failure.code()
                + "; source=" + playerSource + "; cause=" + detail + ".");
        status.setText("[" + failure.code() + "] " + failure.code().userMessage());
    }

    private void playStreaming(FoundVideo video) {
        String infoHash = activeWatchInfoHash;
        if (infoHash == null || infoHash.isBlank()) { playLocal(video); return; }
        long requestId = streamingRequest.incrementAndGet();
        diagnostics.log(P2pDiagnostics.Layer.DOWNLOAD, "STREAM BUFFER WAIT START: requestId=" + requestId + "; infoHash="
                + infoHash + "; video=\"" + video.name() + "\"; path=" + video.path() + ".");
        status.setText("Preparando o buffer de “" + video.name() + "”…");
        Thread.startVirtualThread(() -> {
            try {
                int lastVerifiedPieces = -1;
                boolean waitingForMediaDescription = false;
                while (streamingRequest.get() == requestId) {
                    BtTorrentGateway.StreamingBufferStatus buffer = torrents.streamingBufferStatus(infoHash);
                    StreamingMediaRouteResolver.Route route = StreamingMediaRouteResolver.resolve(buffer, video.path());
                    if (route == StreamingMediaRouteResolver.Route.LOCAL_FILE_DIRECT) {
                        diagnostics.log(P2pDiagnostics.Layer.DOWNLOAD, "STREAM LOCAL FILE READY: requestId=" + requestId
                                + "; infoHash=" + infoHash + "; route=LOCAL_FILE_DIRECT; http=not-used; pieces="
                                + buffer.verifiedPieces() + "/" + buffer.totalPieces() + "; path=" + video.path() + ".");
                        Platform.runLater(() -> {
                            if (streamingRequest.get() == requestId) playLocal(video);
                        });
                        return;
                    }
                    if (route == StreamingMediaRouteResolver.Route.LOCAL_HTTP) {
                        BtTorrentGateway.StreamingMediaWindow mediaWindow = torrents.streamingMediaWindow(infoHash, video.path());
                        if (mediaWindow.contentLengthBytes() <= 0) {
                            if (!waitingForMediaDescription) {
                                waitingForMediaDescription = true;
                                diagnostics.log(P2pDiagnostics.Layer.DOWNLOAD, "STREAM MEDIA DESCRIPTION WAIT: requestId=" + requestId
                                        + "; infoHash=" + infoHash + "; path=" + video.path()
                                        + "; reason=file-to-piece-mapping-unavailable.");
                                Platform.runLater(() -> {
                                    if (streamingRequest.get() == requestId) {
                                        status.setText("Preparando a descrição do arquivo para streaming…");
                                    }
                                });
                            }
                            Thread.sleep(100);
                            continue;
                        }
                        diagnostics.log(P2pDiagnostics.Layer.DOWNLOAD, "STREAM BUFFER READY: requestId=" + requestId + "; infoHash="
                                + infoHash + "; route=LOCAL_HTTP; downloadedBytes=" + buffer.downloadedBytes() + "; pieces="
                                + buffer.verifiedPieces() + "/" + buffer.totalPieces() + "; prefixPieces="
                                + buffer.contiguousPrefixPieces() + "; required=" + buffer.requiredPrefixPieces()
                                + "; fileBytes=" + Files.size(video.path()) + "; mediaLength="
                                + mediaWindow.contentLengthBytes() + ".");
                        Platform.runLater(() -> {
                            if (streamingRequest.get() == requestId) playTorrentStreaming(video, infoHash);
                        });
                        return;
                    }
                    if (!buffer.sessionActive()) {
                        diagnostics.log(P2pDiagnostics.Layer.DOWNLOAD, "STREAM BUFFER ABORT: requestId=" + requestId + "; infoHash="
                                + infoHash + "; reason=session-inactive; downloadedBytes=" + buffer.downloadedBytes()
                                + "; pieces=" + buffer.verifiedPieces() + "/" + buffer.totalPieces() + "; prefixPieces="
                                + buffer.contiguousPrefixPieces() + "; fileExists="
                                + Files.exists(video.path()) + ".");
                        Platform.runLater(() -> {
                            if (streamingRequest.get() == requestId) {
                                showPlaybackFailure("TORRENT_HTTP", new PlayerPlaybackException(PlayerErrorCode.TORRENT_STOPPED,
                                        "a sessão foi encerrada antes de receber o buffer inicial"));
                            }
                        });
                        return;
                    }
                    if (buffer.verifiedPieces() != lastVerifiedPieces) {
                        lastVerifiedPieces = buffer.verifiedPieces();
                        int verified = buffer.verifiedPieces();
                        int required = buffer.requiredPieces();
                        long fileBytes = Files.isRegularFile(video.path()) ? Files.size(video.path()) : 0;
                        diagnostics.log(P2pDiagnostics.Layer.DOWNLOAD, "STREAM BUFFER PROGRESS: requestId=" + requestId + "; infoHash="
                                + infoHash + "; downloadedBytes=" + buffer.downloadedBytes() + "; fileBytes=" + fileBytes
                                + "; pieces=" + verified + "/" + buffer.totalPieces() + "; prefixPieces="
                                + buffer.contiguousPrefixPieces() + "; required=" + required
                                + "; sessionActive=" + buffer.sessionActive() + ".");
                        Platform.runLater(() -> {
                            if (streamingRequest.get() == requestId) status.setText("Buffering de “" + video.name() + "”: "
                                    + verified + "/" + required + " chunks verificados; início contínuo="
                                    + buffer.contiguousPrefixPieces() + "/" + buffer.requiredPrefixPieces()
                                    + "; aguardando peers com dados.");
                        });
                    }
                    Thread.sleep(1_000);
                }
            } catch (Exception error) {
                PlayerErrorCode code = Files.isRegularFile(video.path())
                        ? PlayerErrorCode.HTTP_STREAM_FAILED : PlayerErrorCode.FILE_NOT_FOUND;
                Platform.runLater(() -> showPlaybackFailure("TORRENT_HTTP", new PlayerPlaybackException(code,
                        "não foi possível preparar o streaming: " + PlayerPlaybackException.detail(error), error)));
            }
        });
    }
    @Override public void stop() {
        shutdownApplication();
    }

    /** Fecha os recursos em ordem, de modo idempotente, inclusive quando a janela é fechada pelo sistema. */
    private void shutdownApplication() {
        if (!applicationShutdown.compareAndSet(false, true)) return;
        streamingRequest.incrementAndGet();
        hideControls.stop();
        shutdownStep("player", this::resetPlayback);
        shutdownStep("http-local", localMediaServer::close);
        shutdownStep("swarm-assist", swarmAssistManager::close);
        shutdownStep("connectivity", connectivity::close);
        shutdownStep("bittorrent", torrents::close);
        diagnostics.log(P2pDiagnostics.Layer.RESULT, "[APPLICATION] shutdown=COMPLETED; processExit=launcher.");
    }

    /** Um recurso que falha ao fechar não pode impedir os demais de encerrar. */
    private void shutdownStep(String component, Runnable action) {
        try {
            action.run();
            diagnostics.log(P2pDiagnostics.Layer.RESULT, "[APPLICATION] shutdownComponent=" + component + "; result=closed.");
        } catch (RuntimeException error) {
            diagnostics.log(P2pDiagnostics.Layer.RESULT, "[APPLICATION] shutdownComponent=" + component
                    + "; result=failed; detail=" + PlayerPlaybackException.detail(error).replace(';', ',') + ".");
        }
    }
    private record FoundVideo(String name, Path path) { @Override public String toString() { return name; } }
    private record WatchEntry(String name, String relativePath, Path path, boolean playableMedia) {
        @Override public String toString() { return (playableMedia ? "▶ " : "📄 ") + name; }
    }
    private record WatchContext(String magnet, WatchMode mode) { }
    private record LibraryNode(String name, Path path, boolean video) {
        @Override public String toString() { return (video ? "▶ " : "📁 ") + name; }
    }
    private record LibraryView(String name, Path path, String magnet, Path torrentFile, boolean seeding) {
        @Override public String toString() { return name + (seeding ? " — semeando" : " — cache P2P"); }
    }
    public static void main(String[] args) { launch(args); }
}
