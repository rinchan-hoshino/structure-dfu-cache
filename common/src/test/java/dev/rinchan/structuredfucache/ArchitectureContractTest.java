package dev.rinchan.structuredfucache;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Gate 3: Automated bytecode & packaged-artifact architecture guardrails.
 */
class ArchitectureContractTest {
    private static final List<String> CLIENT_ONLY_PREFIXES = List.of(
        "net/minecraft/client/",
        "net/neoforged/neoforge/client/",
        "net/minecraftforge/client/",
        "com/terraformersmc/modmenu/"
    );

    private static final List<String> FORBIDDEN_ARTIFACT_PATTERNS = List.of(
        "rinlib",
        "com/terraformersmc/modmenu/",
        "testresourceadapter"
    );

    private static Path packagedJar;

    @BeforeAll
    static void locatePackagedJar() {
        String configured = System.getProperty("structure_dfu_cache.packagedJar");
        assertNotNull(configured, "System property 'structure_dfu_cache.packagedJar' must be set by Gradle test task");
        packagedJar = Path.of(configured);
        assertTrue(Files.isRegularFile(packagedJar), "Packaged JAR must exist before ArchitectureContractTest runs: " + packagedJar);
    }

    @Test
    void dedicatedServerClassesNeverReferenceClientOnlyBytecode() throws IOException {
        List<String> violations = new ArrayList<>();
        try (JarFile jar = new JarFile(packagedJar.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.endsWith(".class") || isClientOnlyClass(name)) {
                    continue;
                }
                byte[] bytecode = readEntry(jar, entry);
                for (String utf8 : extractConstantPoolUtf8s(bytecode)) {
                    for (String forbidden : CLIENT_ONLY_PREFIXES) {
                        if (utf8.contains(forbidden)) {
                            violations.add(name + " -> " + utf8);
                        }
                    }
                }
            }
        }
        assertTrue(
            violations.isEmpty(),
            "Dedicated-server isolation violated! Non-client classes reference client bytecode:\n" + String.join("\n", violations)
        );
    }

    @Test
    void mixinConfigAndPackagedClassesFormBidirectionalClosure() throws IOException {
        try (JarFile jar = new JarFile(packagedJar.toFile())) {
            JarEntry configEntry = jar.getJarEntry("structure_dfu_cache.mixins.json");
            assertNotNull(configEntry, "structure_dfu_cache.mixins.json must exist in packaged JAR");

            String jsonText = new String(readEntry(jar, configEntry), StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(jsonText).getAsJsonObject();
            String mixinPkg = root.get("package").getAsString().replace('.', '/');

            Set<String> declaredClassEntries = new HashSet<>();
            for (String section : List.of("mixins", "client", "server")) {
                if (root.has(section) && root.get(section).isJsonArray()) {
                    JsonArray arr = root.getAsJsonArray(section);
                    for (JsonElement el : arr) {
                        String rel = el.getAsString().replace('.', '/');
                        declaredClassEntries.add(mixinPkg + "/" + rel + ".class");
                    }
                }
            }
            assertFalse(declaredClassEntries.isEmpty(), "Mixin config must declare at least one Mixin");

            // Forward check: every declared Mixin exists in packaged JAR.
            for (String expected : declaredClassEntries) {
                assertNotNull(
                    jar.getJarEntry(expected),
                    "Declared Mixin class missing from packaged JAR: " + expected
                );
            }

            // Reverse check: every @Mixin-annotated class in packaged JAR is declared in mixins.json.
            Set<String> discoveredMixins = new HashSet<>();
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.endsWith(".class")) {
                    continue;
                }
                List<String> pool = extractConstantPoolUtf8s(readEntry(jar, entry));
                if (pool.contains("Lorg/spongepowered/asm/mixin/Mixin;")) {
                    discoveredMixins.add(name);
                }
            }

            assertFalse(discoveredMixins.isEmpty(), "Expected at least one @Mixin class in packaged JAR");
            for (String discovered : discoveredMixins) {
                assertTrue(
                    declaredClassEntries.contains(discovered),
                    "Unregistered @Mixin dead code found in packaged JAR: " + discovered
                );
            }
        }
    }

    @Test
    void packagedJarIsCleanAndContainsRequiredMetadata() throws IOException {
        long size = Files.size(packagedJar);
        assertTrue(size > 0 && size < 100 * 1024, "Packaged JAR size out of budget (<100KB): " + size);

        Set<String> entryNames = new HashSet<>();
        List<String> forbiddenHits = new ArrayList<>();
        try (JarFile jar = new JarFile(packagedJar.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                entryNames.add(name);
                String lower = name.toLowerCase();
                for (String forbidden : FORBIDDEN_ARTIFACT_PATTERNS) {
                    if (lower.contains(forbidden)) {
                        forbiddenHits.add(name);
                    }
                }
            }
        }

        assertTrue(forbiddenHits.isEmpty(), "Forbidden entries leaked into packaged JAR: " + forbiddenHits);
        for (String required : List.of(
            "icon.png",
            "structure_dfu_cache.mixins.json",
            "assets/structure_dfu_cache/lang/en_us.json",
            "assets/structure_dfu_cache/lang/zh_cn.json",
            "assets/structure_dfu_cache/lang/zh_tw.json",
            "assets/structure_dfu_cache/lang/ja_jp.json"
        )) {
            assertTrue(entryNames.contains(required), "Missing required resource in packaged JAR: " + required);
        }
    }

    private static boolean isClientOnlyClass(String entryName) {
        if (entryName.contains("/client/")) {
            return true;
        }
        String simple = entryName.substring(entryName.lastIndexOf('/') + 1);
        return simple.endsWith("Client.class")
            || simple.contains("Client$")
            || simple.endsWith("ModMenu.class")
            || simple.contains("ModMenu$");
    }

    private static byte[] readEntry(JarFile jar, JarEntry entry) throws IOException {
        try (InputStream in = jar.getInputStream(entry)) {
            return in.readAllBytes();
        }
    }

    /**
     * Parses JVM ClassFile constant pool (JVMS §4.4) and returns all CONSTANT_Utf8 strings.
     */
    private static List<String> extractConstantPoolUtf8s(byte[] classBytes) throws IOException {
        List<String> utf8s = new ArrayList<>();
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(classBytes))) {
            int magic = in.readInt();
            if (magic != 0xCAFEBABE) {
                throw new IOException("Invalid class file magic");
            }
            in.readUnsignedShort(); // minor
            in.readUnsignedShort(); // major
            int cpCount = in.readUnsignedShort();
            for (int i = 1; i < cpCount; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1 -> utf8s.add(in.readUTF()); // CONSTANT_Utf8
                    case 3, 4 -> in.skipBytes(4); // Integer, Float
                    case 5, 6 -> { // Long, Double occupy 2 slots
                        in.skipBytes(8);
                        i++;
                    }
                    case 7, 8, 16, 19, 20 -> in.skipBytes(2); // Class, String, MethodType, Module, Package
                    case 9, 10, 11, 12, 17, 18 -> in.skipBytes(4); // Fieldref, Methodref, InterfaceMethodref, NameAndType, Dynamic, InvokeDynamic
                    case 15 -> in.skipBytes(3); // MethodHandle
                    default -> throw new IOException("Unknown constant pool tag: " + tag);
                }
            }
        }
        return utf8s;
    }
}
