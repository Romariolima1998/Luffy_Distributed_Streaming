package dev.lufi.infrastructure;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/** Cria somente o esquema local; o acesso concorrente deve ser feito em virtual threads curtas. */
public final class SqliteDatabase {
    private final String url;
    public SqliteDatabase(Path directory) {
        try { Files.createDirectories(directory); } catch (Exception e) { throw new IllegalStateException("Não foi possível criar dados locais", e); }
        url = "jdbc:sqlite:" + directory.resolve("lufi.db").toAbsolutePath(); initialize();
    }
    public Connection connect() throws SQLException { return DriverManager.getConnection(url); }
    private void initialize() {
        try (Connection c = connect(); var s = c.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS libraries (id TEXT PRIMARY KEY, name TEXT NOT NULL, path TEXT NOT NULL UNIQUE, magnet TEXT, torrent_file TEXT, created_at TEXT NOT NULL)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS videos (id TEXT PRIMARY KEY, library_id TEXT NOT NULL, title TEXT NOT NULL, path TEXT NOT NULL UNIQUE, info_hash TEXT NOT NULL, bytes INTEGER NOT NULL, last_accessed TEXT)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS swarm_assist_entries (info_hash TEXT PRIMARY KEY, magnet TEXT NOT NULL, observed_peer_count INTEGER NOT NULL DEFAULT 0, observed_at TEXT, zero_peers_since TEXT, last_user_interaction TEXT, last_peer_seen TEXT, last_useful_rendezvous TEXT, hole_punch_requests_relayed INTEGER NOT NULL DEFAULT 0, successful_hole_punches INTEGER NOT NULL DEFAULT 0, last_successful_assist TEXT, joined_at TEXT NOT NULL)");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS videos_last_accessed ON videos(last_accessed)");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS swarm_assist_entries_observed_at ON swarm_assist_entries(observed_at)");
            addColumnIfMissing(s, "ALTER TABLE libraries ADD COLUMN magnet TEXT");
            addColumnIfMissing(s, "ALTER TABLE libraries ADD COLUMN torrent_file TEXT");
            addColumnIfMissing(s, "ALTER TABLE swarm_assist_entries ADD COLUMN zero_peers_since TEXT");
            addColumnIfMissing(s, "ALTER TABLE swarm_assist_entries ADD COLUMN last_user_interaction TEXT");
            addColumnIfMissing(s, "ALTER TABLE swarm_assist_entries ADD COLUMN last_peer_seen TEXT");
            addColumnIfMissing(s, "ALTER TABLE swarm_assist_entries ADD COLUMN last_useful_rendezvous TEXT");
            addColumnIfMissing(s, "ALTER TABLE swarm_assist_entries ADD COLUMN hole_punch_requests_relayed INTEGER NOT NULL DEFAULT 0");
            addColumnIfMissing(s, "ALTER TABLE swarm_assist_entries ADD COLUMN successful_hole_punches INTEGER NOT NULL DEFAULT 0");
            addColumnIfMissing(s, "ALTER TABLE swarm_assist_entries ADD COLUMN last_successful_assist TEXT");
            migrateLegacySwarmMemberships(s);
        } catch (SQLException e) { throw new IllegalStateException("Falha ao iniciar SQLite", e); }
    }
    private void addColumnIfMissing(java.sql.Statement statement, String sql) {
        try { statement.executeUpdate(sql); } catch (SQLException ignored) { /* coluna já existe */ }
    }
    /** Migra a lista antiga sem assumir que a população observada antes do reinício ainda vale. */
    private void migrateLegacySwarmMemberships(java.sql.Statement statement) {
        try {
            statement.executeUpdate("""
                    INSERT OR IGNORE INTO swarm_assist_entries(info_hash,magnet,observed_peer_count,observed_at,zero_peers_since,last_user_interaction,last_peer_seen,last_useful_rendezvous,hole_punch_requests_relayed,successful_hole_punches,last_successful_assist,joined_at)
                    SELECT info_hash,magnet,observed_peer_count,NULL,NULL,NULL,NULL,NULL,0,0,NULL,joined_at FROM swarm_memberships
                    """);
        } catch (SQLException ignored) { /* instalação nova não possui a tabela anterior */ }
        try { statement.executeUpdate("DROP TABLE IF EXISTS swarm_memberships"); }
        catch (SQLException ignored) { /* a nova tabela já é suficiente; não impede a inicialização */ }
    }
}
