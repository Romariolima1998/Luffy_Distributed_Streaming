package dev.lufi.infrastructure;

import bt.bencoding.types.BEInteger;
import bt.metainfo.TorrentId;
import bt.net.ConnectionKey;
import bt.net.InetPeer;
import bt.protocol.Message;
import bt.protocol.extended.ExtendedHandshake;
import bt.torrent.messaging.MessageContext;
import dev.lufi.infrastructure.identity.LuffyIdentityExtension;
import dev.lufi.infrastructure.identity.LuffyIdentityMessage;
import dev.lufi.infrastructure.identity.LuffyNodeId;
import dev.lufi.infrastructure.identity.LuffyNodeIdentity;
import java.lang.reflect.Constructor;
import java.net.InetAddress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Confirma que a extensao reutiliza a leitura de handshake que ja existia para BEP 55. */
class LuffyIdentityBepHandshakeBridgeTest {
    private static final String INFO_HASH = "0123456789012345678901234567890123456789";

    @Test void existingBep55HandshakeObserverForwardsLfIdentityNegotiation() throws Exception {
        P2pDiagnostics diagnostics = new P2pDiagnostics();
        PeerConnectivityManager connectivity = new PeerConnectivityManager(diagnostics, ignored -> { });
        UtpBitTorrentBridge bridge = new UtpBitTorrentBridge(diagnostics, connectivity);
        Bep55HolePunchAgent bep55 = new Bep55HolePunchAgent(diagnostics, bridge);
        LuffyNodeId localId = nodeId(1);
        LuffyIdentityExtension identity = new LuffyIdentityExtension(
                new LuffyNodeIdentity(localId, Instant.parse("2026-07-30T14:00:00Z")),
                () -> new LuffyIdentityMessage(1, localId, "Luffy/0.1.0", false, false, false, false), diagnostics);
        bep55.setExtensionHandshakeListener(identity::onExtendedHandshake);
        ConnectionKey key = new ConnectionKey(InetPeer.build(address("127.0.0.2"), 6891), 6891,
                TorrentId.fromBytes(HexFormat.of().parseHex(INFO_HASH)));
        ExtendedHandshake handshake = ExtendedHandshake.builder().addMessageType(LuffyIdentityExtension.EXTENSION_NAME, 7)
                .property(ExtendedHandshake.TCPPORT_PROPERTY, new BEInteger(6891)).build();
        try {
            bep55.consume(handshake, context(key));
            List<Message> outbound = new ArrayList<>();
            identity.produce(outbound::add, context(key));

            assertEquals(1, outbound.size());
            assertEquals(localId, ((LuffyIdentityMessage) outbound.getFirst()).nodeId());
        } finally {
            bridge.close();
            connectivity.close();
        }
    }

    private static LuffyNodeId nodeId(int fill) {
        byte[] value = new byte[LuffyNodeId.BINARY_LENGTH];
        Arrays.fill(value, (byte) fill);
        return LuffyNodeId.fromBinary(value);
    }

    private static MessageContext context(ConnectionKey key) throws Exception {
        Constructor<MessageContext> constructor = MessageContext.class.getDeclaredConstructor(ConnectionKey.class,
                Class.forName("bt.torrent.messaging.ConnectionState"));
        constructor.setAccessible(true);
        return constructor.newInstance(key, null);
    }

    private static InetAddress address(String value) {
        try { return InetAddress.getByName(value); }
        catch (Exception error) { throw new AssertionError(error); }
    }
}
