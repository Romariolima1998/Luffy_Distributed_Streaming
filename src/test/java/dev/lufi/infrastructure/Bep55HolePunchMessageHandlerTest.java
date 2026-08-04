package dev.lufi.infrastructure;

import bt.net.InetPeer;
import bt.net.buffer.DelegatingByteBufferView;
import bt.protocol.DecodingContext;
import bt.protocol.EncodingContext;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Bep55HolePunchMessageHandlerTest {
    @Test void encodesAndDecodesRendezvousPayloadExactlyAsBep55() throws Exception {
        InetAddress target = InetAddress.getByName("203.0.113.12");
        Bep55HolePunchMessage original = Bep55HolePunchMessage.rendezvous(target, 43127);
        Bep55HolePunchMessageHandler handler = new Bep55HolePunchMessageHandler();
        ByteBuffer bytes = ByteBuffer.allocate(32);
        handler.encode(new EncodingContext(InetPeer.build(target, 43127)), original, bytes);
        bytes.flip();

        DecodingContext context = new DecodingContext(InetPeer.build(target, 43127));
        assertEquals(12, handler.decode(context, new DelegatingByteBufferView(bytes)));
        Bep55HolePunchMessage decoded = (Bep55HolePunchMessage) context.getMessage();
        assertEquals(Bep55HolePunchMessage.Type.RENDEZVOUS, decoded.type());
        assertEquals(target, decoded.address());
        assertEquals(43127, decoded.port());
        assertEquals(Bep55HolePunchMessage.ErrorCode.NONE, decoded.errorCode());
    }

    @Test void encodesAndDecodesIpv6ConnectWithTheExactBep55Length() throws Exception {
        InetAddress target = InetAddress.getByName("2001:db8::7");
        Bep55HolePunchMessageHandler handler = new Bep55HolePunchMessageHandler();
        ByteBuffer bytes = ByteBuffer.allocate(32);
        handler.encode(new EncodingContext(InetPeer.build(target, 43817)), Bep55HolePunchMessage.connect(target, 43817), bytes);
        bytes.flip();

        DecodingContext context = new DecodingContext(InetPeer.build(target, 43817));
        assertEquals(24, handler.decode(context, new DelegatingByteBufferView(bytes)));
        Bep55HolePunchMessage decoded = (Bep55HolePunchMessage) context.getMessage();
        assertEquals(Bep55HolePunchMessage.Type.CONNECT, decoded.type());
        assertEquals(target, decoded.address());
        assertEquals(43817, decoded.port());
    }

    @Test void encodesAndDecodesErrorWithTheBep55ErrorCode() throws Exception {
        InetAddress target = InetAddress.getByName("203.0.113.18");
        Bep55HolePunchMessageHandler handler = new Bep55HolePunchMessageHandler();
        ByteBuffer bytes = ByteBuffer.allocate(32);
        handler.encode(new EncodingContext(InetPeer.build(target, 43127)),
                Bep55HolePunchMessage.error(target, 43127, Bep55HolePunchMessage.ErrorCode.NOT_CONNECTED), bytes);
        bytes.flip();

        DecodingContext context = new DecodingContext(InetPeer.build(target, 43127));
        assertEquals(12, handler.decode(context, new DelegatingByteBufferView(bytes)));
        Bep55HolePunchMessage decoded = (Bep55HolePunchMessage) context.getMessage();
        assertEquals(Bep55HolePunchMessage.Type.ERROR, decoded.type());
        assertEquals(Bep55HolePunchMessage.ErrorCode.NOT_CONNECTED, decoded.errorCode());
    }

    @Test void rejectsInvalidFamilyPortAndErrorCode() throws Exception {
        InetAddress peer = InetAddress.getByName("203.0.113.12");
        Bep55HolePunchMessageHandler handler = new Bep55HolePunchMessageHandler();

        ByteBuffer invalidFamily = ByteBuffer.wrap(new byte[] { 0, 2, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0 });
        assertThrows(IllegalArgumentException.class, () -> handler.decode(new DecodingContext(InetPeer.build(peer, 1)), new DelegatingByteBufferView(invalidFamily)));

        ByteBuffer invalidPort = payload(0, 0, peer.getAddress(), 0, 0);
        assertThrows(IllegalArgumentException.class, () -> handler.decode(new DecodingContext(InetPeer.build(peer, 1)), new DelegatingByteBufferView(invalidPort)));

        ByteBuffer invalidError = payload(2, 0, peer.getAddress(), 43127, 99);
        assertThrows(IllegalArgumentException.class, () -> handler.decode(new DecodingContext(InetPeer.build(peer, 1)), new DelegatingByteBufferView(invalidError)));
    }

    @Test void allowsLanEndpointsButRejectsInvalidErrorCode() throws Exception {
        assertEquals(Bep55HolePunchMessage.Type.CONNECT,
                Bep55HolePunchMessage.connect(InetAddress.getLoopbackAddress(), 6891).type());
        InetAddress target = InetAddress.getByName("203.0.113.12");
        assertThrows(IllegalArgumentException.class, () -> Bep55HolePunchMessage.error(target, 6891, Bep55HolePunchMessage.ErrorCode.NONE));
    }

    private static ByteBuffer payload(int type, int family, byte[] address, int port, int error) {
        ByteBuffer bytes = ByteBuffer.allocate(1 + 1 + address.length + 2 + 4);
        bytes.put((byte) type).put((byte) family).put(address).putShort((short) port).putInt(error);
        bytes.flip();
        return bytes;
    }
}
