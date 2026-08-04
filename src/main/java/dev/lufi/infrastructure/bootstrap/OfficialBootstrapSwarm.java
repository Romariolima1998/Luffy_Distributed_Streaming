package dev.lufi.infrastructure.bootstrap;

import bt.metainfo.TorrentId;
import dev.lufi.domain.MagnetLink;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Artefatos imutaveis do swarm oficial "Ola Luffy". Esta classe somente carrega
 * e valida recursos versionados; ela nao cria torrent, nao abre conexao e nao
 * inicia uma sessao BitTorrent.
 */
public final class OfficialBootstrapSwarm {
    public static final String INFO_HASH = "08e3e48a8916ff0b0fdc04fa903977d5efa404c7";
    public static final String TEXT_RESOURCE = "bootstrap/ola-luffy.txt";
    public static final String TORRENT_RESOURCE = "bootstrap/ola-luffy.torrent";
    public static final String MAGNET_RESOURCE = "bootstrap/ola-luffy-magnet.txt";
    public static final String MAGNET_URI = "magnet:?xt=urn:btih:" + INFO_HASH + "&dn=Ol%C3%A1+Luffy";

    private static final byte[] EXPECTED_CONTENT = "Olá Luffy".getBytes(StandardCharsets.UTF_8);

    private final TorrentId torrentId;
    private final MagnetLink magnet;

    private OfficialBootstrapSwarm(TorrentId torrentId, MagnetLink magnet) {
        this.torrentId = Objects.requireNonNull(torrentId, "torrentId");
        this.magnet = Objects.requireNonNull(magnet, "magnet");
    }

    /** Carrega os tres recursos da distribuicao e falha antes de a rede iniciar se algum byte mudou. */
    public static OfficialBootstrapSwarm loadAndValidate() {
        return validate(readResource(TEXT_RESOURCE), readResource(TORRENT_RESOURCE), readTextResource(MAGNET_RESOURCE));
    }

    /** Visivel ao teste para validar recursos adulterados sem alterar o classpath. */
    static OfficialBootstrapSwarm validate(byte[] content, byte[] torrent, String rawMagnet) {
        if (!Arrays.equals(EXPECTED_CONTENT, Objects.requireNonNull(content, "content"))) {
            throw new IllegalStateException("O recurso oficial ola-luffy.txt foi alterado");
        }
        byte[] infoDictionary = extractInfoDictionary(Objects.requireNonNull(torrent, "torrent"));
        String actualInfoHash = sha1Hex(infoDictionary);
        if (!INFO_HASH.equals(actualInfoHash)) {
            throw new IllegalStateException("O torrent oficial Ola Luffy possui infoHash inesperado: " + actualInfoHash);
        }
        if (!MAGNET_URI.equals(Objects.requireNonNull(rawMagnet, "rawMagnet"))) {
            throw new IllegalStateException("O magnet oficial Ola Luffy foi alterado");
        }
        MagnetLink magnet;
        try {
            magnet = MagnetLink.parse(rawMagnet);
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException("O magnet oficial Ola Luffy e invalido", error);
        }
        if (!INFO_HASH.equals(magnet.infoHash()) || !"Olá Luffy".equals(magnet.displayName().orElse(null))) {
            throw new IllegalStateException("O magnet oficial Ola Luffy nao corresponde ao torrent versionado");
        }
        return new OfficialBootstrapSwarm(TorrentId.fromBytes(HexFormat.of().parseHex(INFO_HASH)), magnet);
    }

    public TorrentId torrentId() { return torrentId; }
    public MagnetLink magnet() { return magnet; }

    private static byte[] readResource(String resource) {
        try (InputStream input = OfficialBootstrapSwarm.class.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("Recurso oficial ausente: " + resource);
            return input.readAllBytes();
        } catch (IOException error) {
            throw new IllegalStateException("Nao foi possivel ler o recurso oficial " + resource, error);
        }
    }

    private static String readTextResource(String resource) {
        return new String(readResource(resource), StandardCharsets.US_ASCII);
    }

    /** Extrai os bytes exatos do dicionario info, que e a unica entrada hasheada pelo BitTorrent v1. */
    private static byte[] extractInfoDictionary(byte[] torrent) {
        if (torrent.length < 3 || torrent[0] != 'd') throw new IllegalStateException("Metainfo oficial invalido: dicionario raiz ausente");
        int index = 1;
        int infoStart = -1;
        int infoEnd = -1;
        while (true) {
            if (index >= torrent.length) throw new IllegalStateException("Metainfo oficial invalido: dicionario raiz incompleto");
            if (torrent[index] == 'e') {
                index++;
                break;
            }
            ByteString key = readByteString(torrent, index);
            index = key.end();
            int valueStart = index;
            int valueEnd = parseValueEnd(torrent, valueStart, 0);
            if (key.matchesAscii("info")) {
                if (infoStart >= 0 || torrent[valueStart] != 'd') {
                    throw new IllegalStateException("Metainfo oficial invalido: entrada info ausente ou duplicada");
                }
                infoStart = valueStart;
                infoEnd = valueEnd;
            }
            index = valueEnd;
        }
        if (index != torrent.length || infoStart < 0) {
            throw new IllegalStateException("Metainfo oficial invalido: bytes adicionais ou info ausente");
        }
        return Arrays.copyOfRange(torrent, infoStart, infoEnd);
    }

    private static int parseValueEnd(byte[] data, int index, int depth) {
        if (depth > 64 || index >= data.length) throw new IllegalStateException("Bencode oficial invalido");
        byte marker = data[index];
        if (marker == 'i') {
            int cursor = index + 1;
            if (cursor < data.length && data[cursor] == '-') cursor++;
            int digitStart = cursor;
            while (cursor < data.length && data[cursor] != 'e') {
                if (data[cursor] < '0' || data[cursor] > '9') throw new IllegalStateException("Inteiro bencode invalido");
                cursor++;
            }
            if (cursor == digitStart || cursor >= data.length) throw new IllegalStateException("Inteiro bencode incompleto");
            return cursor + 1;
        }
        if (marker == 'l') {
            int cursor = index + 1;
            while (cursor < data.length && data[cursor] != 'e') cursor = parseValueEnd(data, cursor, depth + 1);
            if (cursor >= data.length) throw new IllegalStateException("Lista bencode incompleta");
            return cursor + 1;
        }
        if (marker == 'd') {
            int cursor = index + 1;
            while (cursor < data.length && data[cursor] != 'e') {
                ByteString key = readByteString(data, cursor);
                cursor = parseValueEnd(data, key.end(), depth + 1);
            }
            if (cursor >= data.length) throw new IllegalStateException("Dicionario bencode incompleto");
            return cursor + 1;
        }
        if (marker < '0' || marker > '9') throw new IllegalStateException("Valor bencode invalido");
        return readByteString(data, index).end();
    }

    private static ByteString readByteString(byte[] data, int index) {
        if (index >= data.length || data[index] < '0' || data[index] > '9') {
            throw new IllegalStateException("String bencode invalida");
        }
        long length = 0;
        int cursor = index;
        while (cursor < data.length && data[cursor] != ':') {
            if (data[cursor] < '0' || data[cursor] > '9') throw new IllegalStateException("Tamanho bencode invalido");
            length = length * 10 + (data[cursor] - '0');
            if (length > Integer.MAX_VALUE) throw new IllegalStateException("String bencode excessiva");
            cursor++;
        }
        if (cursor == index || cursor >= data.length) throw new IllegalStateException("String bencode sem separador");
        int start = cursor + 1;
        int end = start + (int) length;
        if (end < start || end > data.length) throw new IllegalStateException("String bencode truncada");
        return new ByteString(data, start, end);
    }

    private static String sha1Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record ByteString(byte[] source, int start, int end) {
        private boolean matchesAscii(String expected) {
            byte[] expectedBytes = expected.getBytes(StandardCharsets.US_ASCII);
            return end - start == expectedBytes.length
                    && Arrays.equals(source, start, end, expectedBytes, 0, expectedBytes.length);
        }
    }
}
