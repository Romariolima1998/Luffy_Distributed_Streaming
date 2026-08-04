package dev.lufi.infrastructure.overlay;

import bt.Bt;
import bt.bencoding.types.BEInteger;
import bt.metainfo.TorrentId;
import bt.net.ConnectionKey;
import bt.net.InetPeer;
import bt.protocol.IExtendedHandshakeFactory;
import bt.protocol.extended.ExtendedHandshake;
import bt.runtime.BtRuntime;
import bt.runtime.Config;
import bt.torrent.messaging.MessageContext;
import dev.lufi.infrastructure.P2pDiagnostics;
import dev.lufi.infrastructure.identity.ConnectedLuffyRegistry;
import dev.lufi.infrastructure.identity.LuffyNodeId;
import dev.lufi.infrastructure.identity.LuffyNodeIdentity;
import dev.lufi.infrastructure.identity.LuffyPeerCapabilities;
import java.lang.reflect.Constructor;
import java.net.InetAddress;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LuffyRouteExtensionTest {
    private static final TorrentId TORRENT = TorrentId.fromBytes(HexFormat.of().parseHex(
            "08e3e48a8916ff0b0fdc04fa903977d5efa404c7"));

    @Test void moduleAdvertisesLfRouteInTheBep10Handshake() {
        LuffyRouteExtension extension = extension();
        Config config = new Config();
        config.setAcceptorAddress(address("127.0.0.1"));
        config.setAcceptorPort(0);
        BtRuntime runtime = BtRuntime.builder(config).disableAutomaticShutdown().module(extension).build();
        try {
            ExtendedHandshake handshake = runtime.service(IExtendedHandshakeFactory.class).getHandshake(TORRENT);
            assertTrue(handshake.getSupportedMessageTypes().contains(LuffyRouteExtension.EXTENSION_NAME));
        } finally {
            runtime.shutdown();
            extension.close();
        }
    }

    @Test void onlyMarksRouteAvailableAfterThePeerAdvertisesLfRoute() throws Exception {
        LuffyRouteExtension extension = extension();
        ConnectionKey key = key();
        try {
            extension.consume(ExtendedHandshake.builder().property(ExtendedHandshake.TCPPORT_PROPERTY, new BEInteger(6891)).build(), context(key));
            assertFalse(extension.isNegotiated(key));

            extension.consume(ExtendedHandshake.builder().addMessageType(LuffyRouteExtension.EXTENSION_NAME, 11)
                    .property(ExtendedHandshake.TCPPORT_PROPERTY, new BEInteger(6891)).build(), context(key));
            assertTrue(extension.isNegotiated(key));
        } finally {
            extension.close();
        }
    }

    private static LuffyRouteExtension extension() {
        LuffyNodeId nodeId = node(1);
        LuffyNodeIdentity identity = new LuffyNodeIdentity(nodeId, Instant.parse("2026-07-30T19:00:00Z"));
        return new LuffyRouteExtension(identity,
                () -> new LuffyPeerCapabilities(1, nodeId, "Luffy/0.1.0", true, false, false, false),
                TORRENT, new ConnectedLuffyRegistry(), new P2pDiagnostics());
    }

    private static ConnectionKey key() {
        return new ConnectionKey(InetPeer.build(address("127.0.0.2"), 6891), 6891, TORRENT);
    }

    private static MessageContext context(ConnectionKey key) throws Exception {
        Constructor<MessageContext> constructor = MessageContext.class.getDeclaredConstructor(ConnectionKey.class,
                Class.forName("bt.torrent.messaging.ConnectionState"));
        constructor.setAccessible(true);
        return constructor.newInstance(key, null);
    }

    private static LuffyNodeId node(int fill) {
        byte[] bytes = new byte[LuffyNodeId.BINARY_LENGTH];
        Arrays.fill(bytes, (byte) fill);
        return LuffyNodeId.fromBinary(bytes);
    }

    private static InetAddress address(String value) {
        try { return InetAddress.getByName(value); }
        catch (Exception error) { throw new AssertionError(error); }
    }
}
