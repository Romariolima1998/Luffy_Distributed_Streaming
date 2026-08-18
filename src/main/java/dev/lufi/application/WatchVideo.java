package dev.lufi.application;

import dev.lufi.application.port.TorrentGateway;
import dev.lufi.application.port.TorrentOpenRequest;
import dev.lufi.domain.MagnetLink;
import dev.lufi.domain.StreamingSession;
import dev.lufi.domain.WatchMode;
import dev.lufi.application.port.TorrentContent;
import java.util.function.Consumer;

public final class WatchVideo {
    private final TorrentGateway torrents;
    public WatchVideo(TorrentGateway torrents) { this.torrents = torrents; }
    public StreamingSession execute(String rawMagnet, WatchMode mode) { return torrents.open(MagnetLink.parse(rawMagnet.trim()), mode); }
    public StreamingSession execute(TorrentOpenRequest request, WatchMode mode) { return torrents.open(request, mode); }
    public StreamingSession execute(String rawMagnet, WatchMode mode, Consumer<TorrentContent> onMetadata) { return torrents.open(MagnetLink.parse(rawMagnet.trim()), mode, onMetadata); }
    public StreamingSession execute(TorrentOpenRequest request, WatchMode mode, Consumer<TorrentContent> onMetadata) { return torrents.open(request, mode, onMetadata); }
    public StreamingSession execute(String rawMagnet, WatchMode mode, String selectedRelativePath, Consumer<TorrentContent> onMetadata) { return torrents.open(MagnetLink.parse(rawMagnet.trim()), mode, selectedRelativePath, onMetadata); }
    public StreamingSession execute(TorrentOpenRequest request, WatchMode mode, String selectedRelativePath, Consumer<TorrentContent> onMetadata) { return torrents.open(request, mode, selectedRelativePath, onMetadata); }
}
