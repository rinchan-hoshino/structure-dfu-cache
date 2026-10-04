package dev.rinchan.structuredfucache.cache;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestOutputStream;
import java.util.HexFormat;

/** Commit complete files without modifying hard links held by active resource generations. */
final class CacheFiles {
    private CacheFiles() {
    }

    static String write(Path target, Writer writer) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".structure-dfu-", ".tmp");
        var digest = CacheKey.newDigest();
        try {
            try (var output = new DigestOutputStream(Files.newOutputStream(temporary), digest)) {
                writer.write(output);
            }
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return HexFormat.of().formatHex(digest.digest());
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @FunctionalInterface
    interface Writer {
        void write(OutputStream output) throws IOException;
    }
}
