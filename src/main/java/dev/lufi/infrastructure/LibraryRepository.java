package dev.lufi.infrastructure;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Persistência das bibliotecas publicadas pelo usuário. */
public final class LibraryRepository {
    public record Library(String id, String name, Path path, String magnet, Path torrentFile) { }
    private final SqliteDatabase database;
    public LibraryRepository(SqliteDatabase database) { this.database = database; }
    public List<Library> findAll() {
        try (var connection = database.connect(); var statement = connection.prepareStatement("SELECT id,name,path,magnet,torrent_file FROM libraries ORDER BY name"); var rows = statement.executeQuery()) {
            var result = new java.util.ArrayList<Library>();
            while (rows.next()) result.add(new Library(rows.getString(1), rows.getString(2), Path.of(rows.getString(3)), rows.getString(4), rows.getString(5) == null ? null : Path.of(rows.getString(5))));
            return result;
        } catch (SQLException e) { throw new IllegalStateException("Falha ao listar bibliotecas", e); }
    }
    public Library save(Path path, String magnet, Path torrentFile) {
        Library library = new Library(UUID.randomUUID().toString(), path.getFileName().toString(), path, magnet, torrentFile);
        try (var connection = database.connect(); var statement = connection.prepareStatement("INSERT INTO libraries(id,name,path,magnet,torrent_file,created_at) VALUES(?,?,?,?,?,?) ON CONFLICT(path) DO UPDATE SET name=excluded.name,magnet=excluded.magnet,torrent_file=excluded.torrent_file")) {
            statement.setString(1, library.id()); statement.setString(2, library.name()); statement.setString(3, library.path().toString()); statement.setString(4, magnet);
            if (torrentFile == null) statement.setNull(5, java.sql.Types.VARCHAR); else statement.setString(5, torrentFile.toString());
            statement.setString(6, Instant.now().toString()); statement.executeUpdate(); return library;
        } catch (SQLException e) { throw new IllegalStateException("Falha ao salvar biblioteca", e); }
    }
}
