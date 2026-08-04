package dev.lufi.infrastructure;

import bt.dht.DHTConfig;
import bt.dht.DHTHandshakeHandler;
import bt.dht.DHTService;
import bt.dht.MldhtService;
import bt.module.ProtocolModule;
import bt.protocol.handler.PortMessageHandler;
import com.google.inject.Binder;
import com.google.inject.Module;

/**
 * Mantém os protocolos DHT ativos, mas não instala o peer source automático
 * da biblioteca. Assim, a DHT apenas descobre endpoints; a promoção para o
 * BitTorrent é decidida pelo {@link PeerConnectivityManager}.
 */
public final class LuffyDhtDiscoveryModule implements Module {
    private final DHTConfig config;

    public LuffyDhtDiscoveryModule(DHTConfig config) { this.config = config; }

    @Override public void configure(Binder binder) {
        binder.bind(DHTConfig.class).toInstance(config);
        ProtocolModule.extend(binder).addHandshakeHandler(DHTHandshakeHandler.class);
        ProtocolModule.extend(binder).addMessageHandler(PortMessageHandler.PORT_ID, PortMessageHandler.class);
        binder.bind(DHTService.class).to(MldhtService.class).asEagerSingleton();
    }
}
