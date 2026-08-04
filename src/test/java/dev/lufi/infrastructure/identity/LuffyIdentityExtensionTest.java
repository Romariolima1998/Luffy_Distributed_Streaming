package dev.lufi.infrastructure.identity;

import bt.bencoding.types.BEInteger;
import bt.metainfo.TorrentId;
import bt.net.ConnectionKey;
import bt.net.InetPeer;
import bt.net.buffer.DelegatingByteBufferView;
import bt.protocol.DecodingContext;
import bt.protocol.EncodingContext;
import bt.protocol.IExtendedHandshakeFactory;
import bt.protocol.Message;
import bt.protocol.extended.ExtendedHandshake;
import bt.runtime.BtRuntime;
import bt.runtime.Config;
import bt.torrent.messaging.MessageContext;
import dev.lufi.infrastructure.P2pDiagnostics;
import dev.lufi.infrastructure.security.AbuseProtectionService;
import dev.lufi.infrastructure.PeerCapabilities;
import java.lang.reflect.Constructor;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LuffyIdentityExtensionTest {
    private static final String INFO_HASH = "0123456789012345678901234567890123456789";
    private static final InetAddress PEER = address("127.0.0.2");

    @Test void codecAndMessageHandlerRoundTripTheExactIdentityPayload() {
        LuffyIdentityMessage original = message(nodeId(1), true, true, true, true);
        LuffyIdentityCodec codec = new LuffyIdentityCodec();

        byte[] payload = codec.encode(original);
        LuffyIdentityMessage decoded = codec.decode(payload);
        assertEquals(payload.length, codec.expectedPayloadSize(ByteBuffer.wrap(payload)));

        assertEquals(original.protocolVersion(), decoded.protocolVersion());
        assertEquals(original.nodeId(), decoded.nodeId());
        assertEquals(original.clientVersion(), decoded.clientVersion());
        assertTrue(decoded.supportsRoute());
        assertTrue(decoded.capabilities().supportsDistributedRendezvous());

        LuffyIdentityMessageHandler handler = new LuffyIdentityMessageHandler(codec);
        ByteBuffer buffer = ByteBuffer.allocate(payload.length);
        assertTrue(handler.encode(new EncodingContext(InetPeer.build(PEER, 6891)), original, buffer));
        buffer.flip();
        DecodingContext context = new DecodingContext(InetPeer.build(PEER, 6891));
        assertEquals(payload.length, handler.decode(context, new DelegatingByteBufferView(buffer)));
        assertEquals(original.nodeId(), ((LuffyIdentityMessage) context.getMessage()).nodeId());
    }

    @Test void handshakeWithLfIdentityQueuesAndAcceptsThePeerIdentity() throws Exception {
        P2pDiagnostics diagnostics = new P2pDiagnostics();
        LuffyIdentityExtension extension = extension(nodeId(1), diagnostics);
        ConnectionKey key = key(6891);

        extension.onExtendedHandshake(key, PeerCapabilities.fromExtensionHandshake(handshake(true).getSupportedMessageTypes()));
        List<Message> produced = new ArrayList<>();
        extension.produce(produced::add, context(key));
        extension.consume(message(nodeId(2), false, true, true, true), context(key));

        assertEquals(1, produced.size());
        assertEquals(nodeId(1), ((LuffyIdentityMessage) produced.getFirst()).nodeId());
        LuffyPeerCapabilities capabilities = extension.peerCapabilities(key).orElseThrow();
        assertEquals(nodeId(2), capabilities.nodeId());
        assertFalse(capabilities.supportsRoute());
        assertTrue(capabilities.supportsDistributedRendezvous());
        assertTrue(diagnostics.snapshot().contains("LF_IDENTITY ACCEPTED"));
    }

    @Test void moduleAdvertisesLfIdentityInTheRealBep10Handshake() {
        Config config = new Config();
        config.setAcceptorAddress(address("127.0.0.1"));
        config.setAcceptorPort(0);
        LuffyIdentityExtension extension = extension(nodeId(1), new P2pDiagnostics());
        BtRuntime runtime = BtRuntime.builder(config).disableAutomaticShutdown()
                .module(extension).module(extension.handshakeObserverModule()).build();
        try {
            ExtendedHandshake handshake = runtime.service(IExtendedHandshakeFactory.class)
                    .getHandshake(TorrentId.fromBytes(HexFormat.of().parseHex(INFO_HASH)));
            assertTrue(handshake.getSupportedMessageTypes().contains(LuffyIdentityExtension.EXTENSION_NAME));
        } finally {
            runtime.shutdown();
        }
    }

    @Test void peerWithoutTheExtensionContinuesWithoutAnIdentityMessage() throws Exception {
        LuffyIdentityExtension extension = extension(nodeId(1), new P2pDiagnostics());
        ConnectionKey key = key(6892);

        extension.onExtendedHandshake(key, PeerCapabilities.fromExtensionHandshake(handshake(false).getSupportedMessageTypes()));
        List<Message> produced = new ArrayList<>();
        extension.produce(produced::add, context(key));

        assertTrue(produced.isEmpty());
        assertTrue(extension.peerCapabilities(key).isEmpty());
        PeerCapabilities capabilities = PeerCapabilities.fromExtensionHandshake(handshake(false).getSupportedMessageTypes());
        assertFalse(capabilities.supportsLuffyIdentity());
    }

    @Test void rejectsInvalidNodePayloadAndIncompatibleProtocolVersion() {
        LuffyIdentityCodec codec = new LuffyIdentityCodec();
        assertThrows(IllegalArgumentException.class, () -> LuffyNodeId.fromBinary(new byte[LuffyNodeId.BINARY_LENGTH - 1]));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(new byte[LuffyIdentityCodec.MIN_PAYLOAD_SIZE - 1]));

        byte[] incompatibleVersion = codec.encode(message(nodeId(1), false, false, false, false));
        incompatibleVersion[0] = 2;
        assertThrows(IllegalArgumentException.class, () -> codec.decode(incompatibleVersion));
    }

    @Test void rejectsPayloadsThatExceedTheLimitOrContainUnknownFields() {
        LuffyIdentityCodec codec = new LuffyIdentityCodec();
        byte[] valid = codec.encode(message(nodeId(1), false, false, false, false));
        byte[] excessive = Arrays.copyOf(valid, LuffyIdentityCodec.MAX_PAYLOAD_SIZE + 1);
        assertThrows(IllegalArgumentException.class, () -> codec.decode(excessive));

        byte[] unknownFlag = valid.clone();
        unknownFlag[unknownFlag.length - 1] = (byte) 0x80;
        assertThrows(IllegalArgumentException.class, () -> codec.decode(unknownFlag));
    }

    @Test void changingTheNodeIdOnTheSameConnectionIsAConflict() throws Exception {
        P2pDiagnostics diagnostics = new P2pDiagnostics();
        LuffyIdentityExtension extension = extension(nodeId(1), diagnostics);
        ConnectionKey key = key(6893);
        extension.onExtendedHandshake(key, PeerCapabilities.fromExtensionHandshake(handshake(true).getSupportedMessageTypes()));
        extension.consume(message(nodeId(2), false, false, false, false), context(key));

        assertThrows(IllegalStateException.class,
                () -> extension.consume(message(nodeId(3), false, false, false, false), context(key)));

        assertTrue(extension.peerCapabilities(key).isEmpty());
        assertTrue(diagnostics.snapshot().contains("LF_IDENTITY CONFLICT"));
    }

    @Test void changingIdentityTemporarilyBlocksThePeerOrigin() throws Exception {
        P2pDiagnostics diagnostics = new P2pDiagnostics();
        AbuseProtectionService protection = new AbuseProtectionService();
        LuffyNodeIdentity local = new LuffyNodeIdentity(nodeId(1), Instant.parse("2026-07-30T14:00:00Z"));
        LuffyIdentityExtension extension = new LuffyIdentityExtension(local,
                ignored -> message(local.nodeId(), false, true, true, true), diagnostics, new ConnectedLuffyRegistry(), protection);
        ConnectionKey key = key(6898);
        extension.onExtendedHandshake(key, PeerCapabilities.fromExtensionHandshake(handshake(true).getSupportedMessageTypes()));
        extension.consume(message(nodeId(2), false, false, false, false), context(key));

        assertThrows(IllegalStateException.class, () -> extension.consume(message(nodeId(3), false, false, false, false), context(key)));
        assertFalse(protection.isAllowed(AbuseProtectionService.peerKey(key.getPeer().getInetAddress()), Instant.now()));
    }

    @Test void capabilitiesRequireRealUtpForHolePunchAndRendezvous() {
        assertThrows(IllegalArgumentException.class,
                () -> message(nodeId(1), false, true, false, true));
        assertThrows(IllegalArgumentException.class,
                () -> message(nodeId(1), false, false, false, true));

        LuffyPeerCapabilities usable = message(nodeId(4), false, true, true, true).capabilities();
        assertTrue(usable.supportsUtp());
        assertTrue(usable.supportsHolePunch());
        assertTrue(usable.supportsDistributedRendezvous());
    }

    @Test void acceptedIdentityEntersTheGlobalRegistryAndDisconnectRemovesIt() throws Exception {
        P2pDiagnostics diagnostics = new P2pDiagnostics();
        ConnectedLuffyRegistry registry = new ConnectedLuffyRegistry();
        LuffyNodeId remoteNodeId = nodeId(8);
        LuffyNodeIdentity local = new LuffyNodeIdentity(nodeId(1), Instant.parse("2026-07-30T14:00:00Z"));
        LuffyIdentityExtension extension = new LuffyIdentityExtension(local,
                () -> message(local.nodeId(), false, true, true, true), diagnostics, registry);
        ConnectionKey key = key(6894);
        extension.onExtendedHandshake(key, PeerCapabilities.fromExtensionHandshake(handshake(true).getSupportedMessageTypes()));
        extension.consume(message(remoteNodeId, false, true, true, true), context(key));

        assertTrue(registry.hasDirectConnection(remoteNodeId));
        assertEquals(key.getTorrentId(), registry.findConnections(remoteNodeId).getFirst().sourceTorrent());

        extension.onPeerDisconnected(INFO_HASH, key.getPeer(), key.getRemotePort());
        assertFalse(registry.hasDirectConnection(remoteNodeId));
    }

    private static LuffyIdentityExtension extension(LuffyNodeId localNodeId, P2pDiagnostics diagnostics) {
        LuffyNodeIdentity local = new LuffyNodeIdentity(localNodeId, Instant.parse("2026-07-30T14:00:00Z"));
        return new LuffyIdentityExtension(local, () -> message(localNodeId, false, true, true, true), diagnostics);
    }

    private static LuffyIdentityMessage message(LuffyNodeId nodeId, boolean route, boolean rendezvous, boolean utp, boolean holePunch) {
        return new LuffyIdentityMessage(LuffyIdentityMessage.PROTOCOL_VERSION, nodeId, "Luffy/0.1.0",
                route, rendezvous, utp, holePunch);
    }

    private static LuffyNodeId nodeId(int fill) {
        byte[] value = new byte[LuffyNodeId.BINARY_LENGTH];
        Arrays.fill(value, (byte) fill);
        return LuffyNodeId.fromBinary(value);
    }

    private static ExtendedHandshake handshake(boolean supportsIdentity) {
        var builder = ExtendedHandshake.builder()
                .property(ExtendedHandshake.TCPPORT_PROPERTY, new BEInteger(6891));
        if (supportsIdentity) builder.addMessageType(LuffyIdentityExtension.EXTENSION_NAME, 7);
        return builder.build();
    }

    private static ConnectionKey key(int port) {
        return new ConnectionKey(InetPeer.build(PEER, port), port, TorrentId.fromBytes(HexFormat.of().parseHex(INFO_HASH)));
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
