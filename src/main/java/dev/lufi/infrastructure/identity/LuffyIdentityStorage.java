package dev.lufi.infrastructure.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Persistencia atomica da identidade da instalacao. Esta classe nao toca em
 * peer ID BitTorrent, DHT, endereco IP, porta ou identificadores de torrent.
 */
public final class LuffyIdentityStorage {
    public static final String FILE_NAME = "luffy-node-identity.json";

    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path directory;
    private final Path identityFile;
    private final Consumer<String> log;
    private final SecureRandom random;
    private final Clock clock;
    private final AtomicIdentityFileWriter writer;

    public LuffyIdentityStorage(Path directory) {
        this(directory, ignored -> { });
    }

    public LuffyIdentityStorage(Path directory, Consumer<String> log) {
        this(directory, log, new SecureRandom(), Clock.systemUTC(), AtomicIdentityFileWriter.system());
    }

    LuffyIdentityStorage(Path directory, Consumer<String> log, SecureRandom random, Clock clock,
                         AtomicIdentityFileWriter writer) {
        this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        this.identityFile = this.directory.resolve(FILE_NAME);
        this.log = log == null ? ignored -> { } : log;
        this.random = Objects.requireNonNull(random, "random");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.writer = Objects.requireNonNull(writer, "writer");
    }

    public Path identityFile() {
        return identityFile;
    }

    /** Carrega a mesma identidade apos reinicializacoes ou cria uma unica identidade nova. */
    public synchronized LuffyNodeIdentity loadOrCreate() {
        try {
            Files.createDirectories(directory);
            if (!Files.exists(identityFile)) return createAndPersist("IDENTITY CREATED: nova identidade local foi criada.");
            try {
                return readIdentity();
            } catch (InvalidIdentityFileException error) {
                Path backup = backupInvalidIdentity();
                log.accept("IDENTITY CORRUPTED: identidade anterior nao pode ser recuperada; backup=" + backup
                        + "; uma nova identidade sera gerada. Motivo=" + error.getMessage());
                return createAndPersist("IDENTITY RECREATED: nova identidade criada apos arquivo corrompido.");
            }
        } catch (IOException error) {
            throw new IllegalStateException("Falha ao acessar a identidade persistente do Luffy", error);
        }
    }

    private LuffyNodeIdentity createAndPersist(String message) throws IOException {
        LuffyNodeIdentity created = new LuffyNodeIdentity(LuffyNodeId.generate(random), Instant.now(clock));
        writer.write(identityFile, serialize(created));
        log.accept(message + " Arquivo=" + identityFile + ".");
        return created;
    }

    private LuffyNodeIdentity readIdentity() throws IOException, InvalidIdentityFileException {
        try {
            JsonNode root = JSON.readTree(Files.readAllBytes(identityFile));
            if (root == null || !root.isObject()) throw new InvalidIdentityFileException("JSON raiz nao e um objeto");
            int version = requiredInteger(root, "version");
            if (version != LuffyNodeIdentity.FORMAT_VERSION) {
                throw new InvalidIdentityFileException("versao de identidade nao suportada: " + version);
            }
            String textId = requiredText(root, "nodeId");
            String createdAt = requiredText(root, "createdAt");
            return new LuffyNodeIdentity(LuffyNodeId.fromText(textId), Instant.parse(createdAt));
        } catch (InvalidIdentityFileException error) {
            throw error;
        } catch (Exception error) {
            throw new InvalidIdentityFileException("conteudo invalido: " + describe(error), error);
        }
    }

    private byte[] serialize(LuffyNodeIdentity identity) throws IOException {
        ObjectNode root = JSON.createObjectNode();
        root.put("version", LuffyNodeIdentity.FORMAT_VERSION);
        root.put("nodeId", identity.nodeId().asText());
        root.put("createdAt", identity.createdAt().toString());
        return JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
    }

    private Path backupInvalidIdentity() throws IOException {
        for (int attempt = 0; attempt < 100; attempt++) {
            String suffix = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS").withZone(clock.getZone()).format(Instant.now(clock));
            Path backup = identityFile.resolveSibling(FILE_NAME + ".corrupt-" + suffix + (attempt == 0 ? "" : "-" + attempt));
            try {
                return Files.move(identityFile, backup, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                return Files.move(identityFile, backup);
            } catch (java.nio.file.FileAlreadyExistsException ignored) {
                // Usa o proximo sufixo sem substituir um backup existente.
            }
        }
        throw new IOException("Nao foi possivel reservar nome para backup da identidade corrompida");
    }

    private static int requiredInteger(JsonNode root, String field) throws InvalidIdentityFileException {
        JsonNode value = root.get(field);
        if (value == null || !value.canConvertToInt()) throw new InvalidIdentityFileException("campo obrigatorio invalido: " + field);
        return value.intValue();
    }

    private static String requiredText(JsonNode root, String field) throws InvalidIdentityFileException {
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new InvalidIdentityFileException("campo obrigatorio invalido: " + field);
        }
        return value.asText();
    }

    private static String describe(Exception error) {
        return error.getClass().getSimpleName() + (error.getMessage() == null ? "" : ": " + error.getMessage());
    }

    @FunctionalInterface
    interface AtomicIdentityFileWriter {
        void write(Path target, byte[] content) throws IOException;

        static AtomicIdentityFileWriter system() {
            return (target, content) -> {
                Path parent = target.getParent();
                Files.createDirectories(parent);
                Path temporary = parent.resolve("." + target.getFileName() + "." + UUID.randomUUID() + ".tmp");
                try {
                    try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                        ByteBuffer source = ByteBuffer.wrap(content);
                        while (source.hasRemaining()) channel.write(source);
                        channel.force(true);
                    }
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } finally {
                    Files.deleteIfExists(temporary);
                }
            };
        }
    }

    private static final class InvalidIdentityFileException extends Exception {
        private InvalidIdentityFileException(String message) { super(message); }
        private InvalidIdentityFileException(String message, Throwable cause) { super(message, cause); }
    }
}
