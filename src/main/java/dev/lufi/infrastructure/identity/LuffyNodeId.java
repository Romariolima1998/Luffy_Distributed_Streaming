package dev.lufi.infrastructure.identity;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;

/**
 * Identificador estavel de uma instalacao Luffy. Nao e um peer ID BitTorrent,
 * nem contem endereco de rede, porta, MAC ou infoHash de torrent.
 */
public final class LuffyNodeId {
    public static final int BINARY_LENGTH = 32;
    public static final int TEXT_LENGTH = 43;

    private final byte[] value;

    private LuffyNodeId(byte[] value) {
        this.value = value;
    }

    /** Gera 256 bits criptograficamente aleatorios para uma nova instalacao. */
    public static LuffyNodeId generate() {
        return generate(new SecureRandom());
    }

    static LuffyNodeId generate(SecureRandom random) {
        Objects.requireNonNull(random, "random");
        byte[] value = new byte[BINARY_LENGTH];
        random.nextBytes(value);
        return new LuffyNodeId(value);
    }

    /** Cria a representacao binaria validando exatamente 256 bits. */
    public static LuffyNodeId fromBinary(byte[] value) {
        if (value == null || value.length != BINARY_LENGTH) {
            throw new IllegalArgumentException("LuffyNodeId deve possuir exatamente " + BINARY_LENGTH + " bytes");
        }
        return new LuffyNodeId(value.clone());
    }

    /** Lê a representacao Base64 URL sem padding e valida sua forma canonica. */
    public static LuffyNodeId fromText(String text) {
        if (text == null || text.length() != TEXT_LENGTH || !text.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("Representacao textual de LuffyNodeId invalida");
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(text);
            LuffyNodeId id = fromBinary(decoded);
            if (!id.asText().equals(text)) {
                throw new IllegalArgumentException("Representacao textual de LuffyNodeId nao e canonica");
            }
            return id;
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Representacao textual de LuffyNodeId invalida", error);
        }
    }

    /** Copia defensiva da representacao binaria, adequada para protocolo futuro. */
    public byte[] asBinary() {
        return value.clone();
    }

    /** Representacao textual persistida e transportavel, sem padding. */
    public String asText() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof LuffyNodeId id && Arrays.equals(value, id.value);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(value);
    }

    @Override
    public String toString() {
        return asText();
    }
}
