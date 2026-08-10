package dev.lufi.infrastructure;

import bt.dht.DHTService;
import bt.dht.MldhtService;
import bt.runtime.BtRuntime;
import java.lang.reflect.Field;
import java.util.Objects;
import lbms.plugins.mldht.kad.DHT;
import lbms.plugins.mldht.kad.RPCServer;
import lbms.plugins.mldht.kad.RPCServerManager;

/**
 * Starts the bt-dht lifecycle for the runtime used only for lookups and waits
 * for its UDP listener to be running before any DHTService#getPeers call.
 */
final class DhtLookupRuntimeInitializer {
    record ReadyDhtState(int knownNodes, int runningRpcServers) {
        ReadyDhtState {
            if (knownNodes < 0) throw new IllegalArgumentException("knownNodes must not be negative");
            if (runningRpcServers < 1) throw new IllegalArgumentException("at least one RPC server must be running");
        }
    }

    private DhtLookupRuntimeInitializer() { }

    static ReadyDhtState startupAndAwait(BtRuntime runtime) {
        Objects.requireNonNull(runtime, "runtime");
        runtime.startup();
        return readyState(runtime);
    }

    /** Estado real da runtime pronta; falha em vez de mascarar diagnosticos como -1. */
    static ReadyDhtState readyState(BtRuntime runtime) {
        Objects.requireNonNull(runtime, "runtime");
        MldhtService service = requireMldhtService(runtime.service(DHTService.class));
        DHT dht = extractDht(service);
        int runningServers = requireRunningRpcServerCount(dht);
        int knownNodes = dht.getNode().getNumEntriesInRoutingTable();
        return new ReadyDhtState(knownNodes, runningServers);
    }

    private static MldhtService requireMldhtService(DHTService service) {
        if (service instanceof MldhtService mldht) return mldht;
        throw new IllegalStateException("Expected bt-dht 1.10 MldhtService, got " + service.getClass().getName());
    }

    private static DHT extractDht(MldhtService service) {
        try {
            Field field = MldhtService.class.getDeclaredField("dht");
            field.setAccessible(true);
            return (DHT) field.get(service);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Incompatible bt-dht 1.10 MldhtService: internal DHT is unavailable", error);
        }
    }

    private static int requireRunningRpcServerCount(DHT dht) {
        RPCServerManager serverManager = dht.getServerManager();
        if (serverManager == null) {
            throw new IllegalStateException("mldht startup completed without an RPCServerManager");
        }
        int running = (int) serverManager.getAllServers().stream()
                .filter(server -> server.getState() == RPCServer.State.RUNNING)
                .count();
        if (running < 1) throw new IllegalStateException(
                "mldht startup completed without a usable RPC server");
        return running;
    }
}
