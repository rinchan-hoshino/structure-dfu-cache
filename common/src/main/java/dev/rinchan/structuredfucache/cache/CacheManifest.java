package dev.rinchan.structuredfucache.cache;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Streaming line-based manifest (manifest.v2) avoiding full-DOM JSON parsing.
 * Line 1: #v2\t<targetDataVersion>\t<mcVersion>\t<loaderVersion>\t<packFingerprint>
 * Lines 2+: <resourceLocation>\t<sourceSha256>\t<C|P>
 */
public final class CacheManifest {
    public static final String FILE_NAME = "manifest.v2";
    private static final String HEADER_PREFIX = "#v2";
    private static final AtomicLong TMP_SEQ = new AtomicLong();

    public enum Status {
        C,
        P
    }

    public record Header(int targetDataVersion, String mcVersion, String loaderVersion, String packFingerprint) {
        public Header {
            if (mcVersion == null || loaderVersion == null || packFingerprint == null) {
                throw new IllegalArgumentException("Header fields must not be null");
            }
        }
    }

    public record Entry(String sourceSha256, Status status) {
        public Entry {
            if (sourceSha256 == null || status == null) {
                throw new IllegalArgumentException("Entry fields must not be null");
            }
        }
    }

    public record DiskCounts(Optional<Header> header, int converted, int passthrough) {
    }

    private CacheManifest() {
    }

    /**
     * Reads only line 1 for fast-path validation without scanning or allocating entry maps.
     */
    public static Optional<Header> readHeaderOnly(Path manifestFile) throws IOException {
        if (!Files.isRegularFile(manifestFile)) {
            return Optional.empty();
        }
        try (BufferedReader reader = Files.newBufferedReader(manifestFile, StandardCharsets.UTF_8)) {
            return parseHeader(reader.readLine());
        } catch (CharacterCodingException corruptEncoding) {
            return Optional.empty();
        }
    }

    /**
     * Counts C and P entries on disk for the status dashboard and OP diagnostics without allocating a map.
     */
    public static DiskCounts inspectCounts(Path manifestFile) throws IOException {
        if (!Files.isRegularFile(manifestFile)) {
            return new DiskCounts(Optional.empty(), 0, 0);
        }
        try (BufferedReader reader = Files.newBufferedReader(manifestFile, StandardCharsets.UTF_8)) {
            Optional<Header> header = parseHeader(reader.readLine());
            if (header.isEmpty()) {
                return new DiskCounts(Optional.empty(), 0, 0);
            }
            int converted = 0;
            int passthrough = 0;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.endsWith("\tC")) {
                    converted++;
                } else if (line.endsWith("\tP")) {
                    passthrough++;
                }
            }
            return new DiskCounts(header, converted, passthrough);
        } catch (CharacterCodingException corruptEncoding) {
            return new DiskCounts(Optional.empty(), 0, 0);
        }
    }

    public static Map<String, Entry> readEntriesIfMatchingTarget(Path manifestFile, int targetDataVersion) throws IOException {
        if (!Files.isRegularFile(manifestFile)) {
            return Map.of();
        }
        try (BufferedReader reader = Files.newBufferedReader(manifestFile, StandardCharsets.UTF_8)) {
            Optional<Header> header = parseHeader(reader.readLine());
            if (header.isEmpty() || header.get().targetDataVersion() != targetDataVersion) {
                return Map.of();
            }
            Map<String, Entry> entries = new HashMap<>();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                String[] parts = line.split("\t", -1);
                if (parts.length != 3) {
                    continue;
                }
                Status status;
                if ("C".equals(parts[2])) {
                    status = Status.C;
                } else if ("P".equals(parts[2])) {
                    status = Status.P;
                } else {
                    continue;
                }
                entries.put(parts[0], new Entry(parts[1], status));
            }
            return entries;
        } catch (CharacterCodingException corruptEncoding) {
            return Map.of();
        }
    }

    public static void writeAtomic(Path manifestFile, Header header, Map<String, Entry> entries) throws IOException {
        Path parent = manifestFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = manifestFile.resolveSibling(FILE_NAME + ".tmp-" + TMP_SEQ.incrementAndGet());
        try {
            try (BufferedWriter writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                writer.write(HEADER_PREFIX);
                writer.write('\t');
                writer.write(Integer.toString(header.targetDataVersion()));
                writer.write('\t');
                writer.write(sanitizeField(header.mcVersion()));
                writer.write('\t');
                writer.write(sanitizeField(header.loaderVersion()));
                writer.write('\t');
                writer.write(sanitizeField(header.packFingerprint()));
                writer.newLine();

                Map<String, Entry> sorted = entries instanceof TreeMap<String, Entry>
                    ? entries
                    : new TreeMap<>(entries);
                for (Map.Entry<String, Entry> item : sorted.entrySet()) {
                    writer.write(sanitizeField(item.getKey()));
                    writer.write('\t');
                    writer.write(item.getValue().sourceSha256());
                    writer.write('\t');
                    writer.write(item.getValue().status().name());
                    writer.newLine();
                }
            }
            Files.move(tmp, manifestFile, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static Optional<Header> parseHeader(String line) {
        if (line == null) {
            return Optional.empty();
        }
        String[] parts = line.split("\t", -1);
        if (parts.length != 5 || !HEADER_PREFIX.equals(parts[0])) {
            return Optional.empty();
        }
        try {
            int dataVersion = Integer.parseInt(parts[1]);
            return Optional.of(new Header(dataVersion, parts[2], parts[3], parts[4]));
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }

    private static String sanitizeField(String value) {
        return value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }
}
