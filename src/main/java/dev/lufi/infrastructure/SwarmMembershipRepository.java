package dev.lufi.infrastructure;

import dev.lufi.domain.MagnetLink;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.function.DoubleSupplier;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/** Persistência da lista passiva Swarm Assist; peers e conexões nunca são persistidos. */
public final class SwarmMembershipRepository {
    public enum Retention {
        ADDED, UPDATED, REPLACED,
        NOT_RETAINED_MORE_CONNECTED, NOT_RETAINED_RESIDENCE, NOT_RETAINED_THRESHOLD
    }

    public record Membership(String infoHash, String magnet, int observedPeerCount, Instant observedAt,
                             Instant zeroPeersSince, Instant lastUserInteraction, Instant lastPeerSeen,
                             Instant lastUsefulRendezvous, int holePunchRequestsRelayed,
                             int successfulHolePunches, Instant lastSuccessfulAssist, Instant joinedAt) {
        public boolean hasFreshPopulation(Duration ttl, Instant now) {
            if (observedAt == null || ttl == null || ttl.isNegative()) return false;
            return !observedAt.plus(ttl).isBefore(now == null ? Instant.now() : now);
        }

        /** Zero só é válido para política depois que uma consulta DHT/PEX terminou. */
        public boolean hasConfirmedEmptyPopulation() {
            return observedPeerCount == 0 && observedAt != null && zeroPeersSince != null;
        }

        public boolean shouldDecayEmpty(Duration decay, Instant now) {
            if (!hasConfirmedEmptyPopulation() || decay == null || decay.isNegative()) return false;
            return !zeroPeersSince.plus(decay).isAfter(now == null ? Instant.now() : now);
        }

        /** Última evidência de que este swarm teve uso humano ou utilidade de rede. */
        public Instant lastRelevantActivity() {
            return java.util.stream.Stream.of(joinedAt, lastUserInteraction, lastPeerSeen, lastUsefulRendezvous, lastSuccessfulAssist)
                    .filter(java.util.Objects::nonNull).max(Instant::compareTo).orElse(Instant.EPOCH);
        }

        /** Remove swarms antigos e mortos mesmo se uma contagem baixa ficou persistida. */
        public boolean shouldDecayInactive(Duration decay, Instant now) {
            if (decay == null || decay.isNegative()) return false;
            return !lastRelevantActivity().plus(decay).isAfter(now == null ? Instant.now() : now);
        }
    }

    public record RetentionResult(Retention retention, int observedPeerCount, String replacedInfoHash,
                                  int replacedPeerCount) {
        public boolean retained() {
            return retention == Retention.ADDED || retention == Retention.UPDATED || retention == Retention.REPLACED;
        }
    }

    private final SqliteDatabase database;
    private final IntSupplier maxAssistSwarms;
    private final Supplier<Duration> minAssistResidence;
    private final DoubleSupplier replacementThreshold;
    private final IntSupplier criticalSwarmPeerCount;
    private final Supplier<Duration> inactiveSwarmDecay;

    public SwarmMembershipRepository(SqliteDatabase database, IntSupplier maxAssistSwarms) {
        this(database, maxAssistSwarms, () -> Duration.ZERO, () -> 0d, () -> 0,
                () -> SwarmAssistSettings.DEFAULT_INACTIVE_SWARM_DECAY);
    }

    public SwarmMembershipRepository(SqliteDatabase database, IntSupplier maxAssistSwarms,
                                     Supplier<Duration> minAssistResidence, DoubleSupplier replacementThreshold) {
        this(database, maxAssistSwarms, minAssistResidence, replacementThreshold, () -> 0,
                () -> SwarmAssistSettings.DEFAULT_INACTIVE_SWARM_DECAY);
    }

    public SwarmMembershipRepository(SqliteDatabase database, IntSupplier maxAssistSwarms,
                                     Supplier<Duration> minAssistResidence, DoubleSupplier replacementThreshold,
                                     IntSupplier criticalSwarmPeerCount) {
        this(database, maxAssistSwarms, minAssistResidence, replacementThreshold, criticalSwarmPeerCount,
                () -> SwarmAssistSettings.DEFAULT_INACTIVE_SWARM_DECAY);
    }

    public SwarmMembershipRepository(SqliteDatabase database, IntSupplier maxAssistSwarms,
                                     Supplier<Duration> minAssistResidence, DoubleSupplier replacementThreshold,
                                     IntSupplier criticalSwarmPeerCount, Supplier<Duration> inactiveSwarmDecay) {
        this.database = database;
        this.maxAssistSwarms = maxAssistSwarms == null ? () -> SwarmAssistSettings.DEFAULT_MAX_ASSIST_SWARMS : maxAssistSwarms;
        this.minAssistResidence = minAssistResidence == null ? () -> Duration.ZERO : minAssistResidence;
        this.replacementThreshold = replacementThreshold == null ? () -> 0d : replacementThreshold;
        this.criticalSwarmPeerCount = criticalSwarmPeerCount == null ? () -> 0 : criticalSwarmPeerCount;
        this.inactiveSwarmDecay = inactiveSwarmDecay == null ? () -> SwarmAssistSettings.DEFAULT_INACTIVE_SWARM_DECAY : inactiveSwarmDecay;
    }

    /** Compatibilidade: uma contagem recebida significa consulta DHT/PEX concluída. */
    public RetentionResult retainIfHelpful(String rawMagnet, int observedPeerCount) {
        return retainIfHelpful(rawMagnet, SwarmNeedScore.fromCompletedObservation(observedPeerCount));
    }

    /** Retém o swarm com maior necessidade Assist e troca o menos necessário quando houver vaga lógica. */
    public RetentionResult retainIfHelpful(String rawMagnet, SwarmNeedScore candidateScore) {
        return retainIfHelpful(rawMagnet, candidateScore, membership -> scoreFor(membership, Instant.now()));
    }

    /** Permite que o gerenciador use estatisticas vivas sem dar ao repositório acesso a DHT, PEX ou sockets. */
    public RetentionResult retainIfHelpful(String rawMagnet, SwarmNeedScore candidateScore,
                                           Function<Membership, SwarmNeedScore> retainedScoreProvider) {
        if (candidateScore == null || !candidateScore.canDriveRetention()) {
            throw new IllegalArgumentException("A retenção Assist exige uma observação DHT/PEX concluída.");
        }
        Function<Membership, SwarmNeedScore> scores = retainedScoreProvider == null
                ? membership -> scoreFor(membership, Instant.now()) : retainedScoreProvider;
        MagnetLink magnet = MagnetLink.parse(rawMagnet == null ? "" : rawMagnet.trim());
        int peers = candidateScore.observedPeerCount();
        int maxSwarms = Math.max(1, maxAssistSwarms.getAsInt());
        Duration residence = safeDuration(minAssistResidence.get());
        double threshold = safeThreshold(replacementThreshold.getAsDouble());
        int criticalPopulation = Math.max(0, criticalSwarmPeerCount.getAsInt());
        Instant now = Instant.now();
        try (var connection = database.connect()) {
            connection.setAutoCommit(false);
            try {
                Membership existing = find(connection, magnet.infoHash());
                if (existing != null) {
                    update(connection, magnet, rawMagnet.trim(), peers, now);
                    connection.commit();
                    return new RetentionResult(Retention.UPDATED, peers, null, -1);
                }
                if (count(connection) < maxSwarms) {
                    insert(connection, magnet, rawMagnet.trim(), peers, now);
                    connection.commit();
                    return new RetentionResult(Retention.ADDED, peers, null, -1);
                }
                Membership leastNeeded = eligibleForReplacement(connection, now.minus(residence)).stream()
                        .min(Comparator.comparingDouble((Membership entry) -> safeScore(scores.apply(entry)).value())
                                .thenComparing(Membership::joinedAt)).orElse(null);
                if (leastNeeded == null) {
                    connection.commit();
                    return new RetentionResult(Retention.NOT_RETAINED_RESIDENCE, peers, null, -1);
                }
                SwarmNeedScore retainedScore = safeScore(scores.apply(leastNeeded));
                double improvement = relativeNeedImprovement(retainedScore.value(), candidateScore.value());
                boolean fragileCandidate = peers <= criticalPopulation;
                boolean needsMoreAssistance = candidateScore.value() > retainedScore.value();
                boolean thresholdMet = improvement >= threshold || (fragileCandidate && needsMoreAssistance);
                if (needsMoreAssistance && thresholdMet) {
                    delete(connection, leastNeeded.infoHash());
                    insert(connection, magnet, rawMagnet.trim(), peers, now);
                    connection.commit();
                    return new RetentionResult(Retention.REPLACED, peers, leastNeeded.infoHash(), leastNeeded.observedPeerCount());
                }
                connection.commit();
                return new RetentionResult(needsMoreAssistance ? Retention.NOT_RETAINED_THRESHOLD : Retention.NOT_RETAINED_MORE_CONNECTED,
                        peers, null, leastNeeded.observedPeerCount());
            } catch (Exception error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Falha ao atualizar a lista de swarms", error);
        }
    }

    public void remove(String infoHash) {
        try (var connection = database.connect()) { delete(connection, infoHash); }
        catch (SQLException error) { throw new IllegalStateException("Falha ao remover o swarm da lista", error); }
    }

    /** Reinício não é observação de rede: DHT/PEX deve confirmar a população novamente. */
    public void invalidateAllPopulationObservations() {
        execute("UPDATE swarm_assist_entries SET observed_at=NULL", statement -> { });
    }

    /** Atualiza observação concluída. Peer positivo também atualiza a última evidência de peer. */
    public void updateEstimatedPeerCount(String infoHash, int estimatedPeerCount) {
        updateEstimatedPeerCount(infoHash, estimatedPeerCount, Instant.now());
    }

    public void updateEstimatedPeerCount(String infoHash, int estimatedPeerCount, Instant observedAt) {
        int safeCount = Math.max(0, estimatedPeerCount);
        Instant when = observedAt == null ? Instant.now() : observedAt;
        try (var connection = database.connect(); var statement = connection.prepareStatement("""
                UPDATE swarm_assist_entries
                SET observed_peer_count=?, observed_at=?,
                    zero_peers_since=CASE WHEN ?=0 THEN COALESCE(zero_peers_since, ?) ELSE NULL END,
                    last_peer_seen=CASE WHEN ? > 0 THEN ? ELSE last_peer_seen END
                WHERE info_hash=?
                """)) {
            statement.setInt(1, safeCount); statement.setString(2, when.toString());
            statement.setInt(3, safeCount); statement.setString(4, when.toString());
            statement.setInt(5, safeCount); statement.setString(6, when.toString()); statement.setString(7, infoHash);
            statement.executeUpdate();
        } catch (SQLException error) { throw new IllegalStateException("Falha ao atualizar a população estimada do swarm", error); }
    }

    public void recordUserInteraction(String infoHash) { recordUserInteraction(infoHash, Instant.now()); }
    public void recordUserInteraction(String infoHash, Instant when) { touchTimestamp(infoHash, "last_user_interaction", when); }
    public void recordPeerSeen(String infoHash) { recordPeerSeen(infoHash, Instant.now()); }
    public void recordPeerSeen(String infoHash, Instant when) { touchTimestamp(infoHash, "last_peer_seen", when); }
    public void recordUsefulRendezvous(String infoHash) { recordUsefulRendezvous(infoHash, Instant.now()); }
    public void recordUsefulRendezvous(String infoHash, Instant when) { touchTimestamp(infoHash, "last_useful_rendezvous", when); }

    public void recordHolePunchRelayed(String infoHash) { recordHolePunchRelayed(infoHash, Instant.now()); }
    public void recordHolePunchRelayed(String infoHash, Instant when) {
        incrementActivity(infoHash, "hole_punch_requests_relayed", "last_useful_rendezvous", when);
    }

    public void recordSuccessfulHolePunch(String infoHash) { recordSuccessfulHolePunch(infoHash, Instant.now()); }
    public void recordSuccessfulHolePunch(String infoHash, Instant when) {
        incrementActivity(infoHash, "successful_hole_punches", "last_successful_assist", when);
    }

    public List<Membership> findAll() {
        try (var connection = database.connect(); var statement = connection.prepareStatement(selectAllSql());
             var rows = statement.executeQuery()) {
            var memberships = new java.util.ArrayList<Membership>();
            while (rows.next()) memberships.add(row(rows));
            return List.copyOf(memberships);
        } catch (SQLException error) { throw new IllegalStateException("Falha ao listar os swarms guardados", error); }
    }

    private Membership find(java.sql.Connection connection, String infoHash) throws SQLException {
        try (var statement = connection.prepareStatement(selectAllSql() + " WHERE info_hash=?")) {
            statement.setString(1, infoHash);
            try (var rows = statement.executeQuery()) { return rows.next() ? row(rows) : null; }
        }
    }

    private int count(java.sql.Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT COUNT(*) FROM swarm_assist_entries"); var rows = statement.executeQuery()) {
            return rows.next() ? rows.getInt(1) : 0;
        }
    }

    private List<Membership> eligibleForReplacement(java.sql.Connection connection, Instant residenceCutoff) throws SQLException {
        try (var statement = connection.prepareStatement(selectAllSql() + " WHERE joined_at<=?")) {
            statement.setString(1, residenceCutoff.toString());
            try (var rows = statement.executeQuery()) {
                var eligible = new java.util.ArrayList<Membership>();
                while (rows.next()) eligible.add(row(rows));
                return List.copyOf(eligible);
            }
        }
    }

    private String selectAllSql() {
        return "SELECT info_hash,magnet,observed_peer_count,observed_at,zero_peers_since,last_user_interaction,last_peer_seen,"
                + "last_useful_rendezvous,hole_punch_requests_relayed,successful_hole_punches,last_successful_assist,joined_at "
                + "FROM swarm_assist_entries";
    }

    private SwarmNeedScore scoreFor(Membership membership, Instant now) {
        if (membership.observedAt() == null) return SwarmNeedScore.pending();
        return SwarmNeedScore.fromCompletedObservation(membership.observedPeerCount(), membership.lastRelevantActivity(),
                membership.holePunchRequestsRelayed(), membership.successfulHolePunches(),
                safeDuration(inactiveSwarmDecay.get()), now);
    }

    private SwarmNeedScore safeScore(SwarmNeedScore score) { return score == null ? SwarmNeedScore.pending() : score; }

    private void insert(java.sql.Connection connection, MagnetLink magnet, String rawMagnet, int peers, Instant now) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO swarm_assist_entries(info_hash,magnet,observed_peer_count,observed_at,zero_peers_since,last_user_interaction,last_peer_seen,last_useful_rendezvous,hole_punch_requests_relayed,successful_hole_punches,last_successful_assist,joined_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                """)) {
            statement.setString(1, magnet.infoHash()); statement.setString(2, rawMagnet); statement.setInt(3, peers);
            statement.setString(4, now.toString()); statement.setString(5, peers == 0 ? now.toString() : null);
            statement.setString(6, now.toString()); statement.setString(7, peers > 0 ? now.toString() : null);
            statement.setString(8, null); statement.setInt(9, 0); statement.setInt(10, 0); statement.setString(11, null);
            statement.setString(12, now.toString()); statement.executeUpdate();
        }
    }

    private void update(java.sql.Connection connection, MagnetLink magnet, String rawMagnet, int peers, Instant now) throws SQLException {
        try (var statement = connection.prepareStatement("""
                UPDATE swarm_assist_entries
                SET magnet=?, observed_peer_count=?, observed_at=?,
                    zero_peers_since=CASE WHEN ?=0 THEN COALESCE(zero_peers_since, ?) ELSE NULL END,
                    last_user_interaction=?, last_peer_seen=CASE WHEN ? > 0 THEN ? ELSE last_peer_seen END
                WHERE info_hash=?
                """)) {
            statement.setString(1, rawMagnet); statement.setInt(2, peers); statement.setString(3, now.toString());
            statement.setInt(4, peers); statement.setString(5, now.toString()); statement.setString(6, now.toString());
            statement.setInt(7, peers); statement.setString(8, now.toString()); statement.setString(9, magnet.infoHash());
            statement.executeUpdate();
        }
    }

    private void touchTimestamp(String infoHash, String column, Instant when) {
        String sql = "UPDATE swarm_assist_entries SET " + column + "=? WHERE info_hash=?";
        execute(sql, statement -> { statement.setString(1, (when == null ? Instant.now() : when).toString()); statement.setString(2, infoHash); });
    }

    private void incrementActivity(String infoHash, String counterColumn, String timestampColumn, Instant when) {
        String sql = "UPDATE swarm_assist_entries SET " + counterColumn + "=" + counterColumn + "+1, " + timestampColumn + "=? WHERE info_hash=?";
        execute(sql, statement -> { statement.setString(1, (when == null ? Instant.now() : when).toString()); statement.setString(2, infoHash); });
    }

    private void execute(String sql, SqlBinder binder) {
        try (var connection = database.connect(); var statement = connection.prepareStatement(sql)) {
            binder.bind(statement); statement.executeUpdate();
        } catch (SQLException error) { throw new IllegalStateException("Falha ao registrar atividade do swarm Assist", error); }
    }

    private void delete(java.sql.Connection connection, String infoHash) throws SQLException {
        try (var statement = connection.prepareStatement("DELETE FROM swarm_assist_entries WHERE info_hash=?")) {
            statement.setString(1, infoHash); statement.executeUpdate();
        }
    }

    private Membership row(java.sql.ResultSet rows) throws SQLException {
        return new Membership(rows.getString(1), rows.getString(2), rows.getInt(3), parseInstant(rows.getString(4)),
                parseInstant(rows.getString(5)), parseInstant(rows.getString(6)), parseInstant(rows.getString(7)),
                parseInstant(rows.getString(8)), rows.getInt(9), rows.getInt(10), parseInstant(rows.getString(11)),
                parseInstant(rows.getString(12)));
    }

    private Instant parseInstant(String value) { return value == null || value.isBlank() ? null : Instant.parse(value); }
    private Duration safeDuration(Duration value) { return value == null || value.isNegative() ? Duration.ZERO : value; }
    private double safeThreshold(double value) { return value >= 0d && value < 1d ? value : 0d; }
    private double relativeNeedImprovement(double currentScore, double candidateScore) {
        return candidateScore <= currentScore ? 0d : (candidateScore - currentScore) / Math.max(0.000_001d, currentScore);
    }

    @FunctionalInterface private interface SqlBinder { void bind(java.sql.PreparedStatement statement) throws SQLException; }
}
