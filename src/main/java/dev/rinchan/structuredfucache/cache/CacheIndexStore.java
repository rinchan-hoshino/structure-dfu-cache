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

    public enum ReadState { MISSING, VALID, FORMAT_CHANGED, DAMAGED }
    public record ReadResult(CacheIndex index, ReadState state) { }

    public static Optional<CacheIndex> read(Path indexPath) throws IOException {
        return Optional.ofNullable(inspect(indexPath).index());
    }

    public static ReadResult inspect(Path indexPath) throws IOException {
        if (!Files.isRegularFile(indexPath)) return new ReadResult(null, ReadState.MISSING);
        try (Reader reader = Files.newBufferedReader(indexPath, StandardCharsets.UTF_8)) {
            var json = com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
            int format = json.getAsJsonObject("identity").get("formatVersion").getAsInt();
            if (format > 0 && format != CacheIdentity.CURRENT_FORMAT) {
                return new ReadResult(null, ReadState.FORMAT_CHANGED);
            }
            CacheIndex index = GSON.fromJson(json, CacheIndex.class);
            return new ReadResult(index, index == null ? ReadState.DAMAGED : ReadState.VALID);
        } catch (RuntimeException exception) {
            return new ReadResult(null, ReadState.DAMAGED);
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
