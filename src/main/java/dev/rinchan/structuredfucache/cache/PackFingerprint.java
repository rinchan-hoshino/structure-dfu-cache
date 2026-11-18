package dev.rinchan.structuredfucache.cache;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.ResourceManager;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModFileInfo;

/**
 * Computes a deterministic SHA-256 fingerprint of the active pack stack, NeoForge mod files,
 * and external datapack/mod file metadata (size + mtime) without reading any NBT payloads.
 */
public final class PackFingerprint {
    private PackFingerprint() {
    }

    public static String compute(ResourceManager original, Path gameDir) {
        ResourceManager base = original instanceof StructureOverlayResourceManager overlay
            ? overlay.delegate()
            : original;
        MessageDigest digest = sha256Digest();

        update(digest, "packs");
        try (Stream<PackResources> packs = base.listPacks()) {
            packs.forEach(pack -> update(digest, pack.packId()));
        }

        update(digest, "modlist");
        try {
            ModList modList = ModList.get();
            if (modList != null) {
                List<Path> modPaths = new ArrayList<>();
                for (IModFileInfo info : modList.getModFiles()) {
                    modPaths.add(info.getFile().getFilePath().toAbsolutePath().normalize());
                }
                modPaths.sort(Comparator.comparing(Path::toString));
                for (Path path : modPaths) {
                    feedFileStat(digest, path.toString(), path);
                }
            }
        } catch (Throwable ignored) {
            // Unit tests without full FML ModList initialization continue with pack + gameDir stats.
        }

        if (gameDir != null) {
            Path normalizedGameDir = gameDir.toAbsolutePath().normalize();
            feedDirectoryStats(digest, "mods", normalizedGameDir.resolve("mods"), ".jar");
            feedDirectoryStats(digest, "datapacks", normalizedGameDir.resolve("datapacks"), null);
            feedDirectoryStats(digest, "world_datapacks", normalizedGameDir.resolve("world/datapacks"), null);
        }

        return HexFormat.of().formatHex(digest.digest());
    }

    private static void feedDirectoryStats(MessageDigest digest, String label, Path root, String suffixFilter) {
        update(digest, label);
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> files = walk
                .filter(Files::isRegularFile)
                .filter(path -> suffixFilter == null || path.getFileName().toString().endsWith(suffixFilter))
                .sorted(Comparator.comparing(path -> root.relativize(path).toString()))
                .toList();
            for (Path file : files) {
                feedFileStat(digest, root.relativize(file).toString(), file);
            }
        } catch (IOException ignored) {
            update(digest, "unreadable:" + root);
        }
    }

    private static void feedFileStat(MessageDigest digest, String key, Path file) {
        update(digest, key);
        try {
            if (Files.isRegularFile(file)) {
                update(digest, Long.toString(Files.size(file)));
                update(digest, Long.toString(Files.getLastModifiedTime(file).toMillis()));
            }
        } catch (IOException ignored) {
            update(digest, "stat-error");
        }
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) '\0');
    }

    static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
