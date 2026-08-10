package dev.lufi.infrastructure;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bt.dht.DHTConfig;
import bt.dht.DHTService;
import bt.metainfo.TorrentId;
import bt.runtime.BtRuntime;
import bt.runtime.Config;
import bt.torrent.TorrentRegistry;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class DhtLookupRuntimeInitializerTest {
    @Test void regression_lookupNeverCallsMldhtWithANullServerManager() throws Exception {
        Optional<Inet4Address> localIpv4 = localIpv4();
        Assumptions.assumeTrue(localIpv4.isPresent(), "o host de teste precisa possuir IPv4 local utilizavel");
        InetAddress address = localIpv4.orElseThrow();
        Config network = new Config();
        network.setAcceptorAddress(address);
        network.setAcceptorPort(0);

        DHTConfig dht = new DHTConfig();
        dht.setShouldUseRouterBootstrap(false);
        dht.setShouldUseIPv6(false);
        dht.setListeningPort(freeUdpPort(address));

        BtRuntime runtime = BtRuntime.builder(network).disableAutomaticShutdown().autoLoadModules()
                .module(new LuffyDhtDiscoveryModule(dht)).build();
        try {
            TorrentId probeTorrent = TorrentId.fromBytes(new byte[20]);
            TorrentRegistry torrents = runtime.service(TorrentRegistry.class);
            assertTrue(torrents.getTorrentIds().isEmpty(), "a runtime de lookup nasce sem torrents locais");

            DhtLookupRuntimeInitializer.ReadyDhtState state = DhtLookupRuntimeInitializer.startupAndAwait(runtime);

            assertTrue(runtime.isRunning(), "a runtime precisa ter executado o ciclo de startup");
            assertTrue(state.runningRpcServers() >= 1, "READY exige ao menos um RPC server em execucao");
            assertTrue(state.knownNodes() >= 0, "READY nunca pode reportar nodes known=-1");
            assertLookupStartsOnlyWithAnInitializedServerManager(runtime, probeTorrent);
            assertTrue(torrents.getTorrentIds().isEmpty(),
                    "getPeers nao pode registrar torrent nem habilitar announce na runtime de lookup");
        } finally {
            runtime.shutdown();
        }
    }

    private static int freeUdpPort(InetAddress address) throws Exception {
        try (DatagramSocket socket = new DatagramSocket(new InetSocketAddress(address, 0))) {
            return socket.getLocalPort();
        }
    }

    private static Optional<Inet4Address> localIpv4() throws Exception {
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces.hasMoreElements()) {
            NetworkInterface network = interfaces.nextElement();
            if (!network.isUp() || network.isLoopback() || network.isVirtual()) continue;
            Enumeration<InetAddress> addresses = network.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress address = addresses.nextElement();
                if (address instanceof Inet4Address ipv4 && !ipv4.isLoopbackAddress() && !ipv4.isLinkLocalAddress()) {
                    return Optional.of(ipv4);
                }
            }
        }
        return Optional.empty();
    }

    private static void assertLookupStartsOnlyWithAnInitializedServerManager(BtRuntime runtime, TorrentId torrentId) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> lookup = executor.submit(() -> {
            DhtLookupRuntimeInitializer.ReadyDhtState stateAtGetPeers = DhtLookupRuntimeInitializer.readyState(runtime);
            assertTrue(stateAtGetPeers.runningRpcServers() >= 1,
                    "getPeers so pode iniciar depois de o RPCServerManager possuir servidor RUNNING");
            try (var ignored = runtime.service(DHTService.class).getPeers(torrentId)) {
                // Without an external bootstrap this may wait for reachability,
                // but it must no longer reach mldht with a null server manager.
            }
        });
        try {
            lookup.get(250, TimeUnit.MILLISECONDS);
        } catch (TimeoutException expectedWhileAwaitingReachability) {
            // Normal: bt-dht waits for external reachability inside getPeers().
        } catch (ExecutionException error) {
            String details = String.valueOf(error.getCause());
            assertFalse(details.contains("getServerManager") && details.contains("null"),
                    "regressao: getPeers chegou ao mldht sem RPCServerManager: " + details);
            throw error;
        } finally {
            lookup.cancel(true);
            executor.shutdownNow();
        }
    }
}
