package dev.lufi.infrastructure;

/** Explicit lifecycle of one shared DHT runtime used only for peer lookups. */
public enum DhtLookupState {
    NEW,
    STARTING,
    READY,
    FAILED,
    STOPPING,
    STOPPED
}
