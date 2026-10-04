package dev.rinchan.structuredfucache.cache;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public final class CacheIndexStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private CacheIndexStore() {
    }

    public static Optional<CacheIndex> read(Path indexPath) throws IOException {
        if (!Files.isRegularFile(indexPath)) {
            return Optional.empty();
        }
        // Record constructors validate both the index and its nested entries.
        try (Reader reader = Files.newBufferedReader(indexPath, StandardCharsets.UTF_8)) {
            return Optional.ofNullable(GSON.fromJson(reader, CacheIndex.class));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    public static void writeAtomic(Path indexPath, CacheIndex index) throws IOException {
        CacheFiles.write(indexPath, output -> {
            try (var writer = new OutputStreamWriter(output, StandardCharsets.UTF_8)) {
                GSON.toJson(index, writer);
            }
        });
    }
}
