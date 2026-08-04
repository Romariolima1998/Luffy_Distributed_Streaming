package dev.lufi.infrastructure;

import bt.module.ServiceModule;
import bt.peerexchange.LuffyPexObserver;
import com.google.inject.Binder;
import com.google.inject.Module;

/** Complementa o PEX nativo do bt-core com telemetria e origem dos peers recebidos. */
final class PexObservationModule implements Module {
    private final PexPeerObserver observer;
    PexObservationModule(PexPeerObserver observer) { this.observer = observer; }
    @Override public void configure(Binder binder) {
        ServiceModule.extend(binder).addMessagingAgent(new LuffyPexObserver(observer));
    }
}
