package dev.lufi.infrastructure;

import bt.bencoding.types.BEInteger;
import bt.metainfo.TorrentId;
import bt.net.ConnectionKey;
import bt.net.InetPeer;
import bt.protocol.Message;
import bt.protocol.extended.ExtendedHandshake;
import bt.runtime.BtRuntime;
import bt.runtime.Config;
import bt.torrent.messaging.MessageContext;
import java.lang.reflect.Constructor;
import java.net.InetAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercita somente as mensagens BEP 55; o teste de torrent completo fica na integração A-C-B. */
class Bep55HolePunchAgentTest {
    private static final String INFO_HASH = "0123456789012345678901234567890123456789";
    private static final InetAddress A = address("127.0.0.1");
    private static final InetAddress B = address("127.0.0.2");
    private static final InetAddress C1 = address("127.0.0.3");
    private static final InetAddress C2 = address("127.0.0.4");

    @Test void triesTheSecondEligibleRelayAfterTheFirstReturnsNotConnected() throws Exception {
        try (Fixture fixture = Fixture.open()) {
            ConnectionKey first = key(C1, 51_001);
            ConnectionKey second = key(C2, 51_002);
            fixture.registerBep55(first);
            fixture.registerBep55(second);

            fixture.agent.requestRendezvous(INFO_HASH, B, 49_001);
            assertEquals(Bep55HolePunchMessage.Type.RENDEZVOUS, fixture.drain(first).getFirst().type());

            fixture.agent.consume(Bep55HolePunchMessage.error(B, 49_001, Bep55HolePunchMessage.ErrorCode.NOT_CONNECTED), context(first));

            List<Bep55HolePunchMessage> retry = fixture.drain(second);
            assertEquals(1, retry.size());
            assertEquals(Bep55HolePunchMessage.Type.RENDEZVOUS, retry.getFirst().type());
            assertTrue(fixture.diagnostics.snapshot().contains("RENDEZVOUS CANDIDATE FAILED"));
            assertTrue(fixture.diagnostics.snapshot().contains("candidato=2/2"));
        }
    }

    @Test void relayRejectsTargetThatDoesNotSupportUtHolePunch() throws Exception {
        try (Fixture fixture = Fixture.open()) {
            ConnectionKey requester = key(A, 51_101);
            ConnectionKey target = key(B, 51_102);
            fixture.observe(A, 49_101);
            fixture.observe(B, 49_102);
            fixture.registerBep55(requester);
            fixture.registerWithoutBep55(target);

            fixture.agent.consume(Bep55HolePunchMessage.rendezvous(B, 49_102), context(requester));

            List<Bep55HolePunchMessage> response = fixture.drain(requester);
            assertEquals(1, response.size());
            assertEquals(Bep55HolePunchMessage.Type.ERROR, response.getFirst().type());
            assertEquals(Bep55HolePunchMessage.ErrorCode.NO_SUPPORT, response.getFirst().errorCode());
            assertFalse(fixture.diagnostics.snapshot().contains("CONNECT SENT"));
        }
    }

    @Test void relayDoesNotInventUdpEndpointWhenRequesterOrTargetWasNotObserved() throws Exception {
        try (Fixture fixture = Fixture.open()) {
            ConnectionKey requester = key(A, 51_201);
            ConnectionKey target = key(B, 51_202);
            fixture.registerBep55(requester);
            fixture.registerBep55(target);

            fixture.agent.consume(Bep55HolePunchMessage.rendezvous(B, 49_202), context(requester));

            List<Bep55HolePunchMessage> response = fixture.drain(requester);
            assertEquals(1, response.size());
            assertEquals(Bep55HolePunchMessage.Type.ERROR, response.getFirst().type());
            assertEquals(Bep55HolePunchMessage.ErrorCode.NOT_CONNECTED, response.getFirst().errorCode());
            assertFalse(fixture.diagnostics.snapshot().contains("CONNECT SENT"));
        }
    }

    @Test void relayRejectsWhenRequesterUdpEndpointWasNotObserved() throws Exception {
        try (Fixture fixture = Fixture.open()) {
            ConnectionKey requester = key(A, 51_221);
            ConnectionKey target = key(B, 51_222);
            fixture.observe(B, 49_222);
            fixture.registerBep55(requester);
            fixture.registerBep55(target);

            fixture.agent.consume(Bep55HolePunchMessage.rendezvous(B, 49_222), context(requester));

            List<Bep55HolePunchMessage> response = fixture.drain(requester);
            assertEquals(Bep55HolePunchMessage.ErrorCode.NO_SUCH_PEER, response.getFirst().errorCode());
            assertFalse(fixture.diagnostics.snapshot().contains("CONNECT SENT"));
        }
    }

    @Test void relayRejectsWhenTargetUdpEndpointWasNotObserved() throws Exception {
        try (Fixture fixture = Fixture.open()) {
            ConnectionKey requester = key(A, 51_231);
            ConnectionKey target = key(B, 51_232);
            fixture.observe(A, 49_231);
            fixture.registerBep55(requester);
            fixture.registerBep55(target);

            fixture.agent.consume(Bep55HolePunchMessage.rendezvous(B, 49_232), context(requester));

            List<Bep55HolePunchMessage> response = fixture.drain(requester);
            assertEquals(Bep55HolePunchMessage.ErrorCode.NOT_CONNECTED, response.getFirst().errorCode());
            assertFalse(fixture.diagnostics.snapshot().contains("CONNECT SENT"));
        }
    }

    @Test void sendsConnectToBothPeersWhenTheRelayHasBothRealEndpointRecords() throws Exception {
        try (Fixture fixture = Fixture.open()) {
            ConnectionKey requester = key(A, 51_301);
            ConnectionKey target = key(B, 51_302);
            fixture.observe(A, 49_301);
            fixture.observe(B, 49_302);
            fixture.registerBep55(requester);
            fixture.registerBep55(target);

            fixture.agent.consume(Bep55HolePunchMessage.rendezvous(B, 49_302), context(requester));

            List<Bep55HolePunchMessage> forRequester = fixture.drain(requester);
            List<Bep55HolePunchMessage> forTarget = fixture.drain(target);
            assertEquals(Bep55HolePunchMessage.Type.CONNECT, forRequester.getFirst().type());
            assertEquals(B, forRequester.getFirst().address());
            assertEquals(49_302, forRequester.getFirst().port());
            assertEquals(Bep55HolePunchMessage.Type.CONNECT, forTarget.getFirst().type());
            assertEquals(A, forTarget.getFirst().address());
            assertEquals(49_301, forTarget.getFirst().port());
            assertTrue(fixture.diagnostics.snapshot().contains("CONNECT SENT"));
        }
    }

    @Test void concurrentRequestsForTheSameTargetProduceOnlyOneRendezvous() throws Exception {
        try (Fixture fixture = Fixture.open()) {
            ConnectionKey relay = key(C1, 51_401);
            fixture.registerBep55(relay);

            CompletableFuture.allOf(
                    CompletableFuture.runAsync(() -> fixture.agent.requestRendezvous(INFO_HASH, B, 49_401)),
                    CompletableFuture.runAsync(() -> fixture.agent.requestRendezvous(INFO_HASH, B, 49_401))
            ).join();

            List<Bep55HolePunchMessage> messages = fixture.drain(relay);
            assertEquals(1, messages.size(), "duas solicitações simultâneas não podem criar dois RENDEZVOUS");
            assertEquals(Bep55HolePunchMessage.Type.RENDEZVOUS, messages.getFirst().type());
            assertTrue(fixture.diagnostics.snapshot().contains("HOLE PUNCH RETRY SUPPRESSED"));
        }
    }

    @Test void duplicateConnectDoesNotStartASecondUtpTunnel() throws Exception {
        try (Fixture fixture = Fixture.open(); UtpTransportService remote = new UtpTransportService(A, 0, new P2pDiagnostics())) {
            ConnectionKey relay = key(C1, 51_501);
            fixture.registerBep55(relay);
            Bep55HolePunchMessage connect = Bep55HolePunchMessage.connect(A, remote.localPort());

            fixture.agent.consume(connect, context(relay));
            fixture.agent.consume(connect, context(relay));

            assertTrue(fixture.diagnostics.snapshot().contains("BEP55 CONNECT IGNORED"));
        }
    }

    @Test void peerDisconnectRemovesBep55SessionState() throws Exception {
        try (Fixture fixture = Fixture.open()) {
            ConnectionKey relay = key(C1, 51_601);
            fixture.registerBep55(relay);
            assertEquals(1, fixture.agent.usefulRendezvousPeerCount(INFO_HASH));

            fixture.agent.onPeerDisconnected(INFO_HASH, InetPeer.build(C1, 51_601), 51_601);

            assertEquals(0, fixture.agent.usefulRendezvousPeerCount(INFO_HASH));
        }
    }

    private static ConnectionKey key(InetAddress address, int port) {
        return new ConnectionKey(InetPeer.build(address, port), port, TorrentId.fromBytes(HexFormat.of().parseHex(INFO_HASH)));
    }

    private static ExtendedHandshake bep55Handshake(int tcpPort) {
        return ExtendedHandshake.builder().addMessageType("ut_holepunch", 1)
                .property(ExtendedHandshake.TCPPORT_PROPERTY, new BEInteger(tcpPort)).build();
    }

    private static ExtendedHandshake noBep55Handshake(int tcpPort) {
        return ExtendedHandshake.builder().property(ExtendedHandshake.TCPPORT_PROPERTY, new BEInteger(tcpPort)).build();
    }

    private static MessageContext context(ConnectionKey key) throws Exception {
        Constructor<MessageContext> constructor = MessageContext.class.getDeclaredConstructor(ConnectionKey.class,
                Class.forName("bt.torrent.messaging.ConnectionState"));
        constructor.setAccessible(true); // construtor do bt-core é package-private; o teste não faz parte da aplicação.
        return constructor.newInstance(key, null);
    }

    private static InetAddress address(String value) {
        try { return InetAddress.getByName(value); }
        catch (Exception error) { throw new AssertionError(error); }
    }

    private static final class Fixture implements AutoCloseable {
        private final P2pDiagnostics diagnostics = new P2pDiagnostics();
        private final PeerConnectivityManager connectivity = new PeerConnectivityManager(diagnostics, ignored -> { });
        private final BtRuntime runtime;
        private final UtpTransportService utp;
        private final UtpBitTorrentBridge bridge;
        private final Bep55HolePunchAgent agent;

        private Fixture() throws Exception {
            Config config = new Config();
            config.setAcceptorAddress(A);
            config.setAcceptorPort(0);
            config.setPeerHandshakeTimeout(Duration.ofSeconds(2));
            runtime = BtRuntime.builder(config).disableAutomaticShutdown().build();
            utp = new UtpTransportService(A, 0, diagnostics);
            bridge = new UtpBitTorrentBridge(diagnostics, connectivity);
            bridge.attach(runtime, utp);
            agent = new Bep55HolePunchAgent(diagnostics, bridge);
        }

        static Fixture open() throws Exception { return new Fixture(); }

        void observe(InetAddress address, int port) {
            agent.observePeerUtpEndpoint(INFO_HASH,
                    new PeerConnectivityManager.PeerEndpoint(address, port, PeerConnectivityManager.Transport.UTP));
        }

        void registerBep55(ConnectionKey key) throws Exception { agent.consume(bep55Handshake(key.getRemotePort()), context(key)); }
        void registerWithoutBep55(ConnectionKey key) throws Exception { agent.consume(noBep55Handshake(key.getRemotePort()), context(key)); }

        List<Bep55HolePunchMessage> drain(ConnectionKey key) throws Exception {
            List<Message> messages = new ArrayList<>();
            agent.produce(messages::add, context(key));
            return messages.stream().map(Bep55HolePunchMessage.class::cast).toList();
        }

        @Override public void close() {
            bridge.close();
            connectivity.close();
            runtime.shutdown();
        }
    }
}
