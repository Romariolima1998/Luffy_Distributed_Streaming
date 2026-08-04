package dev.lufi.infrastructure.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LuffyIdentityStorageTest {
    private static final Instant CREATED_AT = Instant.parse("2026-07-30T14:00:00Z");
    private static final Clock CLOCK = Clock.fixed(CREATED_AT, ZoneOffset.UTC);

    @TempDir Path temporaryDirectory;

    @Test void createsAnIdentityOnlyOnTheFirstRun() throws Exception {
        List<String> logs = new ArrayList<>();
        LuffyIdentityStorage storage = storage(temporaryDirectory.resolve("first"), logs);

        LuffyNodeIdentity identity = storage.loadOrCreate();

        assertTrue(Files.isRegularFile(storage.identityFile()));
        assertEquals(CREATED_AT, identity.createdAt());
        assertEquals(LuffyNodeId.BINARY_LENGTH, identity.nodeId().asBinary().length);
        assertEquals(LuffyNodeId.TEXT_LENGTH, identity.nodeId().asText().length());
        assertTrue(logs.stream().anyMatch(line -> line.contains("IDENTITY CREATED")));
    }

    @Test void loadsTheSameIdentityAfterRestart() {
        Path installation = temporaryDirectory.resolve("restart");
        LuffyNodeIdentity first = storage(installation, new ArrayList<>()).loadOrCreate();

        LuffyNodeIdentity second = storage(installation, new ArrayList<>()).loadOrCreate();

        assertEquals(first, second);
        assertEquals(first.nodeId().asText(), second.nodeId().asText());
    }

    @Test void generatesDifferentIdentitiesForDifferentInstallations() {
        LuffyNodeIdentity first = storage(temporaryDirectory.resolve("installation-a"), new ArrayList<>()).loadOrCreate();
        LuffyNodeIdentity second = storage(temporaryDirectory.resolve("installation-b"), new ArrayList<>()).loadOrCreate();

        assertNotEquals(first.nodeId(), second.nodeId());
    }

    @Test void movesAnInvalidIdentityToBackupAndLogsTheRecovery() throws Exception {
        Path installation = temporaryDirectory.resolve("corrupt");
        Files.createDirectories(installation);
        Path identityFile = installation.resolve(LuffyIdentityStorage.FILE_NAME);
        String invalid = "{ not-json";
        Files.writeString(identityFile, invalid);
        List<String> logs = new ArrayList<>();

        LuffyNodeIdentity recovered = storage(installation, logs).loadOrCreate();

        assertTrue(Files.isRegularFile(identityFile));
        assertEquals(LuffyNodeId.BINARY_LENGTH, recovered.nodeId().asBinary().length);
        try (var files = Files.list(installation)) {
            Path backup = files.filter(path -> path.getFileName().toString().startsWith(LuffyIdentityStorage.FILE_NAME + ".corrupt-"))
                    .findFirst().orElseThrow();
            assertEquals(invalid, Files.readString(backup));
        }
        assertTrue(logs.stream().anyMatch(line -> line.contains("IDENTITY CORRUPTED")));
        assertTrue(logs.stream().anyMatch(line -> line.contains("IDENTITY RECREATED")));
    }

    @Test void interruptedWriteDoesNotLeaveANewIdentityFile() {
        Path installation = temporaryDirectory.resolve("interrupted");
        LuffyIdentityStorage.AtomicIdentityFileWriter interrupted = (target, content) -> {
            throw new IOException("falha de escrita injetada");
        };
        LuffyIdentityStorage storage = new LuffyIdentityStorage(installation, ignored -> { }, new SecureRandom(), CLOCK, interrupted);

        IllegalStateException failure = assertThrows(IllegalStateException.class, storage::loadOrCreate);

        assertTrue(failure.getMessage().contains("identidade persistente"));
        assertFalse(Files.exists(storage.identityFile()));
    }

    @Test void serializesTheVersionNodeIdAndCreationTime() throws Exception {
        LuffyIdentityStorage storage = storage(temporaryDirectory.resolve("serialization"), new ArrayList<>());
        LuffyNodeIdentity identity = storage.loadOrCreate();

        JsonNode document = new ObjectMapper().readTree(Files.readAllBytes(storage.identityFile()));

        assertEquals(LuffyNodeIdentity.FORMAT_VERSION, document.get("version").intValue());
        assertEquals(identity.nodeId().asText(), document.get("nodeId").asText());
        assertEquals(CREATED_AT.toString(), document.get("createdAt").asText());
    }

    @Test void nodeIdUsesValueEqualityHashCodeAndDefensiveBinaryCopies() {
        byte[] value = new byte[LuffyNodeId.BINARY_LENGTH];
        for (int index = 0; index < value.length; index++) value[index] = (byte) index;
        LuffyNodeId first = LuffyNodeId.fromBinary(value);
        LuffyNodeId second = LuffyNodeId.fromText(first.asText());
        value[0] = 100;

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertArrayEquals(second.asBinary(), first.asBinary());
        assertNotEquals(100, first.asBinary()[0]);
        assertEquals(new LuffyNodeIdentity(first, CREATED_AT), new LuffyNodeIdentity(second, CREATED_AT));
    }

    @Test void rejectsInvalidBinaryAndTextLengthsBeforeUse() {
        assertThrows(IllegalArgumentException.class, () -> LuffyNodeId.fromBinary(new byte[LuffyNodeId.BINARY_LENGTH - 1]));
        String shortText = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[LuffyNodeId.BINARY_LENGTH - 1]);
        assertThrows(IllegalArgumentException.class, () -> LuffyNodeId.fromText(shortText));
        assertThrows(IllegalArgumentException.class, () -> LuffyNodeId.fromText("!".repeat(LuffyNodeId.TEXT_LENGTH)));
    }

    private LuffyIdentityStorage storage(Path directory, List<String> logs) {
        return new LuffyIdentityStorage(directory, logs::add, new SecureRandom(), CLOCK,
                LuffyIdentityStorage.AtomicIdentityFileWriter.system());
    }
}
