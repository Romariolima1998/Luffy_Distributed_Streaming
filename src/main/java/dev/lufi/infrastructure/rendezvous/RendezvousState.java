package dev.lufi.infrastructure.rendezvous;

/** Estados locais de uma coordenacao distribuida; nenhum deles representa dados do torrent. */
public enum RendezvousState {
    CREATED,
    ROUTE_ESTABLISHED,
    TARGET_CONFIRMED,
    PREPARING,
    PUNCHING,
    BITTORRENT_HANDSHAKING,
    CONNECTED,
    FAILED,
    EXPIRED,
    CANCELLED;

    public boolean terminal() {
        return this == CONNECTED || this == FAILED || this == EXPIRED || this == CANCELLED;
    }
}
