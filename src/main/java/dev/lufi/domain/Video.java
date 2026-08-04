package dev.lufi.domain;

import java.nio.file.Path;
import java.time.Instant;

/** Metadados de um arquivo publicado localmente. */
public record Video(String id, String libraryId, String title, Path path, long bytes, String infoHash, Instant addedAt) { }

