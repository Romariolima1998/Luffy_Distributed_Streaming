package dev.lufi.infrastructure;

import bt.protocol.extended.ExtendedMessage;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Objects;

/** Payload binario da extensao ut_holepunch definida no BEP 55. */
public final class Bep55HolePunchMessage extends ExtendedMessage {
    public enum Type {
        RENDEZVOUS(0), CONNECT(1), ERROR(2);
        private final int id;
        Type(int id) { this.id = id; }
        int id() { return id; }
        static Type fromId(int id) {
            for (Type value : values()) if (value.id == id) return value;
            throw new IllegalArgumentException("tipo BEP 55 invalido: " + id);
        }
    }
    public enum ErrorCode {
        NONE(0), NO_SUCH_PEER(1), NOT_CONNECTED(2), NO_SUPPORT(3), NO_SELF(4);
        private final int id;
        ErrorCode(int id) { this.id = id; }
        int id() { return id; }
        static ErrorCode fromId(int id) {
            for (ErrorCode value : values()) if (value.id == id) return value;
            throw new IllegalArgumentException("codigo de erro BEP 55 invalido: " + id);
        }
    }

    private final Type type;
    private final InetAddress address;
    private final int port;
    private final ErrorCode errorCode;

    private Bep55HolePunchMessage(Type type, InetAddress address, int port, ErrorCode errorCode) {
        this.type = Objects.requireNonNull(type, "type");
        this.address = Objects.requireNonNull(address, "address");
        if (!(address instanceof Inet4Address) && !(address instanceof Inet6Address)) {
            throw new IllegalArgumentException("familia de endereco BEP 55 invalida");
        }
        // O BEP 55 define apenas a codificação de IPv4/IPv6 e porta. Endereços
        // locais são válidos em uma LAN (e permitem exercitar o protocolo em
        // testes); a política de anúncio público continua em ExternalEndpointRegistry.
        if (address.isAnyLocalAddress() || address.isMulticastAddress()) {
            throw new IllegalArgumentException("endpoint alvo BEP 55 invalido");
        }
        if (port < 1 || port > 65_535) throw new IllegalArgumentException("porta BEP 55 invalida");
        this.port = port;
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
        if (type != Type.ERROR && errorCode != ErrorCode.NONE) throw new IllegalArgumentException("somente ERROR pode carregar codigo de erro");
        if (type == Type.ERROR && errorCode == ErrorCode.NONE) throw new IllegalArgumentException("ERROR exige codigo de erro BEP 55");
    }

    public static Bep55HolePunchMessage rendezvous(InetAddress address, int port) { return new Bep55HolePunchMessage(Type.RENDEZVOUS, address, port, ErrorCode.NONE); }
    public static Bep55HolePunchMessage connect(InetAddress address, int port) { return new Bep55HolePunchMessage(Type.CONNECT, address, port, ErrorCode.NONE); }
    public static Bep55HolePunchMessage error(InetAddress address, int port, ErrorCode code) { return new Bep55HolePunchMessage(Type.ERROR, address, port, code); }

    public Type type() { return type; }
    public InetAddress address() { return address; }
    public int port() { return port; }
    public ErrorCode errorCode() { return errorCode; }
}
