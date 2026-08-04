package dev.lufi.infrastructure;

import bt.metainfo.TorrentId;
import bt.net.ConnectionResult;
import bt.net.IPeerConnectionFactory;
import bt.net.InetPeer;
import bt.net.Peer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BtCoreConnectionFactoryAdapterTest {
    @Test void findsTheExpectedInternalSignatureOnlyOnceInTheFactoryHierarchy() {
        var method = BtCoreConnectionFactoryAdapter.locateValidatedOutgoingMethod(ValidFactory.class);

        assertEquals("createConnection", method.getName());
        assertEquals(ConnectionResult.class, method.getReturnType());
        assertEquals(ValidFactoryBase.class, method.getDeclaringClass());
        assertEquals("1.10", BtCoreConnectionFactoryAdapter.EXPECTED_BT_CORE_VERSION);
    }

    @Test void rejectsAFactoryWithoutTheInternalOutgoingMethod() {
        BtCoreIntegrationException error = assertThrows(BtCoreIntegrationException.class,
                () -> BtCoreConnectionFactoryAdapter.locateValidatedOutgoingMethod(FactoryWithoutInternalMethod.class));

        assertTrue(error.getMessage().contains("nao expoe o metodo"));
        assertTrue(error.getMessage().contains("bt-core 1.10"));
    }

    @Test void rejectsAnIncompatibleInternalSignature() {
        BtCoreIntegrationException error = assertThrows(BtCoreIntegrationException.class,
                () -> BtCoreConnectionFactoryAdapter.locateValidatedOutgoingMethod(FactoryWithWrongSignature.class));

        assertTrue(error.getMessage().contains("assinatura incompativel"));
        assertTrue(error.getMessage().contains("SocketChannel"));
    }

    @Test void convertsOutgoingInvocationFailureAndClosesTheProvidedChannel() throws Exception {
        try (SessionFixture fixture = SessionFixture.open(); SocketChannel channel = SocketChannel.open()) {
            BtCoreConnectionFactoryAdapter adapter = new BtCoreConnectionFactoryAdapter(new ThrowingFactory());

            CompletionException error = assertThrows(CompletionException.class, () -> adapter.promoteOutgoing(
                    TorrentId.fromBytes(new byte[20]), peer(), fixture.outgoingSession(), channel).toCompletableFuture().join());

            assertInstanceOf(BtCoreIntegrationException.class, error.getCause());
            assertTrue(error.getCause().getMessage().contains("saida"));
            assertFalse(channel.isOpen(), "o canal local deve ser fechado se a invocacao refletida falhar");
        }
    }

    @Test void convertsIncomingPromotionFailureAndClosesTheProvidedChannel() throws Exception {
        try (SessionFixture fixture = SessionFixture.open(); SocketChannel channel = SocketChannel.open()) {
            BtCoreConnectionFactoryAdapter adapter = new BtCoreConnectionFactoryAdapter(new ThrowingFactory());

            CompletionException error = assertThrows(CompletionException.class, () -> adapter.promoteIncoming(
                    peer(), fixture.outgoingSession(), channel).toCompletableFuture().join());

            assertInstanceOf(BtCoreIntegrationException.class, error.getCause());
            assertTrue(error.getCause().getMessage().contains("entrada"));
            assertFalse(channel.isOpen(), "o canal local deve ser fechado se a promocao de entrada falhar");
        }
    }

    private static Peer peer() throws Exception {
        return InetPeer.build(InetAddress.getLoopbackAddress(), 49_001);
    }

    private record SessionFixture(UtpTransportService left, UtpTransportService right,
                                  UtpTransportService.UtpSession outgoingSession) implements AutoCloseable {
        static SessionFixture open() throws Exception {
            InetAddress loopback = InetAddress.getLoopbackAddress();
            UtpTransportService left = new UtpTransportService(loopback, 0, new P2pDiagnostics());
            UtpTransportService right = new UtpTransportService(loopback, 0, new P2pDiagnostics());
            try {
                CompletableFuture<UtpTransportService.UtpSession> incoming = new CompletableFuture<>();
                right.setIncomingListener(incoming::complete);
                UtpTransportService.UtpSession outgoing = left.connect(new InetSocketAddress(loopback, right.localPort()))
                        .get(Duration.ofSeconds(3).toSeconds(), TimeUnit.SECONDS);
                incoming.get(Duration.ofSeconds(3).toSeconds(), TimeUnit.SECONDS);
                return new SessionFixture(left, right, outgoing);
            } catch (Exception error) {
                left.close();
                right.close();
                throw error;
            }
        }

        @Override public void close() {
            left.close();
            right.close();
        }
    }

    private abstract static class TestFactory implements IPeerConnectionFactory {
        @Override public ConnectionResult createOutgoingConnection(Peer peer, TorrentId torrentId) {
            return null;
        }

        @Override public ConnectionResult createIncomingConnection(Peer peer, SocketChannel channel) {
            return null;
        }
    }

    private static class ValidFactoryBase extends TestFactory {
        @SuppressWarnings("unused")
        private ConnectionResult createConnection(Peer peer, TorrentId torrentId, SocketChannel channel, boolean encrypted) {
            return null;
        }
    }

    private static final class ValidFactory extends ValidFactoryBase { }

    private static final class FactoryWithoutInternalMethod extends TestFactory { }

    private static final class FactoryWithWrongSignature extends TestFactory {
        @SuppressWarnings("unused")
        private String createConnection(Peer peer, TorrentId torrentId, SocketChannel channel) {
            return "wrong";
        }
    }

    private static final class ThrowingFactory extends TestFactory {
        @SuppressWarnings("unused")
        private ConnectionResult createConnection(Peer peer, TorrentId torrentId, SocketChannel channel, boolean encrypted) {
            throw new IllegalStateException("factory simulada falhou");
        }

        @Override public ConnectionResult createIncomingConnection(Peer peer, SocketChannel channel) {
            throw new IllegalStateException("factory simulada falhou");
        }
    }
}
