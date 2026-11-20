package dev.rinchan.structuredfucache.client;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.rinchan.structuredfucache.StructureDfuCache;
import dev.rinchan.structuredfucache.cache.CacheManifest;
import dev.rinchan.structuredfucache.cache.CacheStats;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Gate 1 (Client Screen Lifecycle): Verifies StructureDfuCacheConfigScreen initializes,
 * populates all widgets, reads live on-disk cache statistics, and closes cleanly with zero exceptions.
 */
class StructureDfuCacheConfigScreenTest {
    @BeforeAll
    static void bootMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void configScreenInitializesWidgetsAndReadsLiveStatsWithoutException() throws Exception {
        Path cacheRoot = StructureDfuCache.cacheRoot();
        Path overlayFile = cacheRoot.resolve("overlay/data/screen_test/structure/sample.nbt");
        Files.createDirectories(overlayFile.getParent());
        Files.write(overlayFile, new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
        CacheManifest.writeAtomic(
            cacheRoot.resolve(CacheManifest.FILE_NAME),
            new CacheManifest.Header(3955, "1.21.1", "neoforge:21.1.250", "fp-screen-test"),
            Map.of(
                "screen_test:structure/sample.nbt", new CacheManifest.Entry("abc", CacheManifest.Status.C),
                "screen_test:structure/pass.nbt", new CacheManifest.Entry("def", CacheManifest.Status.P)
            )
        );

        try {
            CacheStats.resetRuntime();
            StructureDfuCacheConfigScreen screen = assertDoesNotThrow(() -> new StructureDfuCacheConfigScreen(null));
            assertNotNull(screen.getTitle());

            // Invoke protected Screen.width/height & init() in headless JVM (no OpenGL context needed).
            var widthField = Screen.class.getDeclaredField("width");
            widthField.setAccessible(true);
            widthField.setInt(screen, 854);
            var heightField = Screen.class.getDeclaredField("height");
            heightField.setAccessible(true);
            heightField.setInt(screen, 480);

            assertDoesNotThrow(() -> screen.init());

            List<? extends GuiEventListener> children = screen.children();
            assertEquals(7, children.size(), "Expected 3 CycleButtons + 4 action Buttons (Clear/Reset/Cancel/Save)");

            // Verify live snapshot read on construction reflects disk state.
            var snapField = StructureDfuCacheConfigScreen.class.getDeclaredField("snapshot");
            snapField.setAccessible(true);
            CacheStats.Snapshot snap = (CacheStats.Snapshot) snapField.get(screen);
            assertNotNull(snap);
            assertEquals(1, snap.convertedCount());
            assertEquals(1, snap.passthroughCount());
            assertTrue(snap.overlayFiles() >= 1);
            assertTrue(snap.overlayBytes() >= 8L);

            assertDoesNotThrow(screen::onClose);
            assertFalse(children.isEmpty());
        } finally {
            CacheStats.clearCache(cacheRoot);
        }
    }
}
