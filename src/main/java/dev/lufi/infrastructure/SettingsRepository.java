package dev.lufi.infrastructure;

import java.sql.SQLException;
import java.util.Optional;

public final class SettingsRepository {
    private final SqliteDatabase database;
    public SettingsRepository(SqliteDatabase database) { this.database = database; }
    public Optional<String> get(String key) {
        try (var c = database.connect(); var s = c.prepareStatement("SELECT value FROM settings WHERE key=?")) {
            s.setString(1, key); var result = s.executeQuery(); return result.next() ? Optional.of(result.getString(1)) : Optional.empty();
        } catch (SQLException e) { throw new IllegalStateException("Falha ao ler configuração", e); }
    }
    public void put(String key, String value) {
        try (var c = database.connect(); var s = c.prepareStatement("INSERT INTO settings(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
            s.setString(1, key); s.setString(2, value); s.executeUpdate();
        } catch (SQLException e) { throw new IllegalStateException("Falha ao salvar configuração", e); }
    }
}

