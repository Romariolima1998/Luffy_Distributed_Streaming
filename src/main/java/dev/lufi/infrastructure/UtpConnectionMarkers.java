package dev.lufi.infrastructure;

import java.nio.channels.SocketChannel;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Identifica o SocketChannel local que representa uma sessao BitTorrent sobre uTP. */
final class UtpConnectionMarkers {
    private static final Set<SocketChannel> CHANNELS = ConcurrentHashMap.newKeySet();

    private UtpConnectionMarkers() { }

    static void mark(SocketChannel channel) { CHANNELS.add(channel); }
    static boolean isMarked(SocketChannel channel) { return CHANNELS.contains(channel); }
    static void unmark(SocketChannel channel) { CHANNELS.remove(channel); }
}
