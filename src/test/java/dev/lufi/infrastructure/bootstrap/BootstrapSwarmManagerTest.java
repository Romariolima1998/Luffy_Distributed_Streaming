package dev.lufi.infrastructure.bootstrap;

import bt.metainfo.TorrentId;
import bt.net.ConnectionKey;
import bt.net.InetPeer;
import dev.lufi.infrastructure.P2pDiagnostics;
import dev.lufi.infrastructure.identity.ConnectedLuffyRegistry;
import dev.lufi.infrastructure.identity.LuffyNodeId;
import dev.lufi.infrastructure.identity.LuffyPeerCapabilities;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BootstrapSwarmManagerTest {
    private final List<Fixture> fixtures = new CopyOnWriteArrayList<>();

    @AfterEach void cleanUp() {
        fixtures.forEach(Fixture::close);
    }

    @Test void initializesTheOfficialMagnetSessionOnTheExistingSessionFactory() {
        FakeSession session = FakeSession.active();
        List<String> receivedMagnets = new CopyOnWriteArrayList<>();
        Fixture fixture = fixture(3, () -> session, magnet -> receivedMagnets.add(magnet.infoHash()));

        fixture.manager.start();

        assertEquals(BootstrapSwarmState.ACTIVE, fixture.manager.state());
        assertEquals(List.of(OfficialBootstrapSwarm.INFO_HASH), receivedMagnets);
        assertFalse(session.closed.get());
    }

    @Test void invalidOfficialMagnetFailsBeforeCreatingAnySession() {
        AtomicInteger factoryCalls = new AtomicInteger();
        Fixture fixture = fixture("magnet:?dn=ola-luffy", 3, FakeSession::active, ignored -> factoryCalls.incrementAndGet());

        fixture.manager.start();

        assertEquals(BootstrapSwarmState.FAILED, fixture.manager.state());
        assertEquals(0, factoryCalls.get());
    }

    @Test void aRecoverableSessionFailureReconnectsWithANewBtClientAdapter() {
        FakeSession first = FakeSession.active();
        FakeSession second = FakeSession.active();
        AtomicInteger calls = new AtomicInteger();
        Fixture fixture = fixture(3, () -> calls.getAndIncrement() == 0 ? first : second, ignored -> { });
        fixture.manager.start();

        fixture.manager.reportRecoverableFailure(new IllegalStateException("runtime interrompida"));
        await(() -> fixture.manager.state() == BootstrapSwarmState.ACTIVE && calls.get() == 2);

        assertTrue(first.closed.get());
        assertFalse(second.closed.get());
    }

    @Test void persistentRuntimeFailureStopsAfterTheConfiguredRetryBudget() {
        Fixture fixture = fixture(0, () -> { throw new IllegalStateException("runtime indisponivel"); }, ignored -> { });

        fixture.manager.start();

        assertEquals(BootstrapSwarmState.FAILED, fixture.manager.state());
    }

    @Test void stopClosesTheBootstrapSessionAndCancelsItsLifecycle() {
        FakeSession session = FakeSession.active();
        Fixture fixture = fixture(3, () -> session, ignored -> { });
        fixture.manager.start();

        fixture.manager.stop();

        assertEquals(BootstrapSwarmState.STOPPED, fixture.manager.state());
        assertTrue(session.closed.get());
    }

    @Test void exposesOnlyValidatedLfIdentityNeighborsFromTheOfficialTorrent() {
        ConnectedLuffyRegistry registry = new ConnectedLuffyRegistry();
        LuffyNodeId bootstrapNeighbor = nodeId(4);
        LuffyNodeId otherTorrentNeighbor = nodeId(5);
        registry.registerConnection(connection(bootstrapNeighbor, OfficialBootstrapSwarm.INFO_HASH, 6891));
        registry.registerConnection(connection(otherTorrentNeighbor, "0123456789012345678901234567890123456789", 6892));
        Fixture fixture = fixture(3, registry, FakeSession::active, ignored -> { });

        assertEquals(java.util.Set.of(bootstrapNeighbor), fixture.manager.connectedNeighbors());
    }

    private Fixture fixture(int maxRetries, Supplier<FakeSession> supplier,
                            java.util.function.Consumer<dev.lufi.domain.MagnetLink> onCreated) {
        return fixture(OfficialBootstrapSwarm.MAGNET_URI, maxRetries, new ConnectedLuffyRegistry(), supplier, onCreated);
    }

    private Fixture fixture(String magnet, int maxRetries, Supplier<FakeSession> supplier,
                            java.util.function.Consumer<dev.lufi.domain.MagnetLink> onCreated) {
        return fixture(magnet, maxRetries, new ConnectedLuffyRegistry(), supplier, onCreated);
    }

    private Fixture fixture(int maxRetries, ConnectedLuffyRegistry registry, Supplier<FakeSession> supplier,
                            java.util.function.Consumer<dev.lufi.domain.MagnetLink> onCreated) {
        return fixture(OfficialBootstrapSwarm.MAGNET_URI, maxRetries, registry, supplier, onCreated);
    }

    private Fixture fixture(String magnet, int maxRetries, ConnectedLuffyRegistry registry, Supplier<FakeSession> supplier,
                            java.util.function.Consumer<dev.lufi.domain.MagnetLink> onCreated) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());
        BootstrapSwarmManager manager = new BootstrapSwarmManager(magnet, OfficialBootstrapSwarm.INFO_HASH, registry,
                receivedMagnet -> {
                    onCreated.accept(receivedMagnet);
                    return supplier.get();
                }, new P2pDiagnostics(), scheduler, Duration.ZERO, Duration.ofHours(1), maxRetries, false);
        Fixture fixture = new Fixture(manager, scheduler);
        fixtures.add(fixture);
        return fixture;
    }

    private static ConnectedLuffyRegistry.ConnectedLuffy connection(LuffyNodeId nodeId, String infoHash, int port) {
        TorrentId torrentId = TorrentId.fromBytes(java.util.HexFormat.of().parseHex(infoHash));
        ConnectionKey key = new ConnectionKey(InetPeer.build(address("127.0.0." + (port % 200 + 1)), port), port, torrentId);
        LuffyPeerCapabilities capabilities = new LuffyPeerCapabilities(1, nodeId, "Luffy/0.1.0", false, true, true, true);
        Instant now = Instant.parse("2026-07-30T16:00:00Z");
        return new ConnectedLuffyRegistry.ConnectedLuffy(nodeId, torrentId, key.getPeer(), key, capabilities,
                Optional.empty(), Optional.empty(), ConnectedLuffyRegistry.ConnectionDirection.UNKNOWN, now, now);
    }

    private static LuffyNodeId nodeId(int fill) {
        byte[] value = new byte[LuffyNodeId.BINARY_LENGTH];
        Arrays.fill(value, (byte) fill);
        return LuffyNodeId.fromBinary(value);
    }

    private static InetAddress address(String value) {
        try { return InetAddress.getByName(value); }
        catch (Exception error) { throw new AssertionError(error); }
    }

    private static void await(java.util.function.BooleanSupplier condition) {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (condition.getAsBoolean()) return;
            try { Thread.sleep(10); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
        }
        throw new AssertionError("A condicao nao foi atingida no prazo");
    }

    private record Fixture(BootstrapSwarmManager manager, ScheduledExecutorService scheduler) implements AutoCloseable {
        @Override public void close() {
            manager.close();
            scheduler.shutdownNow();
        }
    }

    private static final class FakeSession implements BootstrapSwarmManager.BootstrapSession {
        private final CompletableFuture<Void> start = new CompletableFuture<>();
        private final AtomicBoolean active = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();

        static FakeSession active() {
            FakeSession session = new FakeSession();
            session.active.set(true);
            session.start.complete(null);
            return session;
        }

        @Override public CompletionStage<Void> start() { return start; }
        @Override public boolean isActive() { return active.get() && !closed.get(); }
        @Override public void close() { closed.set(true); active.set(false); }
    }
}
