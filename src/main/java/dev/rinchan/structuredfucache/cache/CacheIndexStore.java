package dev.rinchan.structuredfucache.cache;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

public final class CacheIndexStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private CacheIndexStore() {
    }

    public static Optional<CacheIndex> read(Path indexPath) throws IOException {
        if (!Files.isRegularFile(indexPath)) {
            return Optional.empty();
        }
        try (Reader reader = Files.newBufferedReader(indexPath, StandardCharsets.UTF_8)) {
            CacheIndex index = GSON.fromJson(reader, CacheIndex.class);
            if (index == null || index.formatVersion() != CacheIndex.CURRENT_FORMAT) {
                return Optional.empty();
            }
            return Optional.of(index);
        }
    }

    public static void writeAtomic(Path indexPath, CacheIndex index) throws IOException {
        Path parent = indexPath.toAbsolutePath().getParent();
        if (parent == null) {
            throw new IOException("cache index has no parent directory: " + indexPath);
        }
        Files.createDirectories(parent);
        Path temporaryPath = indexPath.resolveSibling(indexPath.getFileName() + ".tmp");
        Files.deleteIfExists(temporaryPath);
        try {
            try (Writer writer = Files.newBufferedWriter(
                temporaryPath,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
            )) {
                GSON.toJson(index, writer);
            }
            try (FileChannel channel = FileChannel.open(temporaryPath, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(temporaryPath, indexPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporaryPath, indexPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporaryPath);
        }
    }
}
