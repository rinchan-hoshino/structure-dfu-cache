package dev.rinchan.structuredfucache.client;

import dev.rinchan.structuredfucache.StructureDfuCache;
import dev.rinchan.structuredfucache.cache.CacheStats;
import dev.rinchan.structuredfucache.config.StructureDfuCacheConfig;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Native zero-dependency configuration & live cache health dashboard screen for Minecraft 1.20.1.
 */
public final class StructureDfuCacheConfigScreen extends Screen {
    private static final List<Integer> WORKER_OPTIONS = List.of(0, 1, 2, 3, 4, 5, 6, 7, 8);

    private final Screen parent;
    private boolean enabled;
    private int maxWorkers;
    private boolean verboseLogging;
    private boolean notifyInChat;
    private CacheStats.Snapshot snapshot;
    private Component feedback;
    private int feedbackColor = 0x7CE38B;
    private int panelLeft;
    private int panelWidth;

    public StructureDfuCacheConfigScreen(Screen parent) {
        super(Component.translatable("screen.structure_dfu_cache.title"));
        this.parent = parent;
        StructureDfuCacheConfig cfg = StructureDfuCacheConfig.get();
        this.enabled = cfg.enabled();
        this.maxWorkers = cfg.maxWorkers();
        this.verboseLogging = cfg.verboseLogging();
        this.notifyInChat = cfg.notifyInChat();
        this.snapshot = CacheStats.inspect(StructureDfuCache.cacheRoot());
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        this.panelWidth = Math.min(320, this.width - 32);
        this.panelLeft = centerX - this.panelWidth / 2;
        int y = 96;

        this.addRenderableWidget(CycleButton.onOffBuilder(this.enabled)
            .create(this.panelLeft, y, this.panelWidth, 20,
                Component.translatable("screen.structure_dfu_cache.enabled"),
                (btn, value) -> this.enabled = value));
        y += 24;

        this.addRenderableWidget(CycleButton.<Integer>builder(this::formatWorkers)
            .withValues(WORKER_OPTIONS)
            .withInitialValue(this.maxWorkers)
            .create(this.panelLeft, y, this.panelWidth, 20,
                Component.translatable("screen.structure_dfu_cache.max_workers"),
                (btn, value) -> this.maxWorkers = value));
        y += 24;

        this.addRenderableWidget(CycleButton.onOffBuilder(this.verboseLogging)
            .create(this.panelLeft, y, this.panelWidth, 20,
                Component.translatable("screen.structure_dfu_cache.verbose_logging"),
                (btn, value) -> this.verboseLogging = value));
        y += 24;

        this.addRenderableWidget(CycleButton.onOffBuilder(this.notifyInChat)
            .create(this.panelLeft, y, this.panelWidth, 20,
                Component.translatable("screen.structure_dfu_cache.notify_in_chat"),
                (btn, value) -> this.notifyInChat = value));
        y += 28;

        this.addRenderableWidget(Button.builder(
                Component.translatable("screen.structure_dfu_cache.clear_cache"),
                btn -> clearCacheNow())
            .bounds(this.panelLeft, y, this.panelWidth, 20)
            .build());

        int btnY = Math.min(this.height - 28, y + 36);
        int btnW = (this.panelWidth - 12) / 3;

        this.addRenderableWidget(Button.builder(
                Component.translatable("screen.structure_dfu_cache.reset"),
                btn -> resetDefaults())
            .bounds(this.panelLeft, btnY, btnW, 20)
            .build());

        this.addRenderableWidget(Button.builder(
                Component.translatable("screen.structure_dfu_cache.cancel"),
                btn -> onClose())
            .bounds(this.panelLeft + btnW + 6, btnY, btnW, 20)
            .build());

        this.addRenderableWidget(Button.builder(
                Component.translatable("screen.structure_dfu_cache.save"),
                btn -> saveAndClose())
            .bounds(this.panelLeft + (btnW + 6) * 2, btnY, this.panelWidth - (btnW + 6) * 2, 20)
            .build());
    }

    private Component formatWorkers(int value) {
        return value == 0
            ? Component.translatable("screen.structure_dfu_cache.workers_auto")
            : Component.literal(Integer.toString(value));
    }

    private void clearCacheNow() {
        CacheStats.ClearResult result = CacheStats.clearCache(StructureDfuCache.cacheRoot());
        this.snapshot = CacheStats.inspect(StructureDfuCache.cacheRoot());
        if (result.cleared()) {
            this.feedback = Component.translatable(
                "screen.structure_dfu_cache.cleared",
                result.removedFiles(),
                CacheStats.formatBytes(result.freedBytes())
            );
            this.feedbackColor = 0x7CE38B;
        } else if (result.locked()) {
            this.feedback = Component.translatable("command.structure_dfu_cache.clear_locked");
            this.feedbackColor = 0xFF8080;
        } else {
            this.feedback = Component.translatable("command.structure_dfu_cache.clear_failed");
            this.feedbackColor = 0xFF8080;
        }
    }

    private void resetDefaults() {
        StructureDfuCacheConfig defaults = StructureDfuCacheConfig.defaults();
        this.enabled = defaults.enabled();
        this.maxWorkers = defaults.maxWorkers();
        this.verboseLogging = defaults.verboseLogging();
        this.notifyInChat = defaults.notifyInChat();
        this.rebuildWidgets();
    }

    private void saveAndClose() {
        StructureDfuCacheConfig.updateAndSave(
            new StructureDfuCacheConfig(this.enabled, this.maxWorkers, this.verboseLogging, this.notifyInChat)
        );
        onClose();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        int cardTop = 30;
        int cardBottom = 88;
        graphics.fill(this.panelLeft, cardTop, this.panelLeft + this.panelWidth, cardBottom, 0xA0101824);
        graphics.fill(this.panelLeft, cardTop, this.panelLeft + this.panelWidth, cardTop + 1, 0xFF3E6888);
        graphics.fill(this.panelLeft, cardBottom - 1, this.panelLeft + this.panelWidth, cardBottom, 0xFF284257);

        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFFFFF);

        Component modeText = Component.translatable(
            "status.structure_dfu_cache.mode." + this.snapshot.mode().name().toLowerCase(Locale.ROOT)
        );
        Component timeText = this.snapshot.lastPrepareMs() >= 0L
            ? Component.translatable("status.structure_dfu_cache.ms", this.snapshot.lastPrepareMs())
            : Component.translatable("status.structure_dfu_cache.not_run");

        int textX = this.panelLeft + 8;
        graphics.drawString(
            this.font,
            Component.translatable("screen.structure_dfu_cache.dashboard_status", modeText, timeText),
            textX,
            cardTop + 8,
            0x9FE2FF
        );
        graphics.drawString(
            this.font,
            Component.translatable(
                "screen.structure_dfu_cache.dashboard_counts",
                this.snapshot.convertedCount(),
                this.snapshot.passthroughCount()
            ),
            textX,
            cardTop + 24,
            0xE6EEF5
        );
        graphics.drawString(
            this.font,
            Component.translatable(
                "screen.structure_dfu_cache.dashboard_disk",
                CacheStats.formatBytes(this.snapshot.overlayBytes()),
                this.snapshot.overlayFiles()
            ),
            textX,
            cardTop + 40,
            0xC0D0DE
        );

        if (this.feedback != null) {
            graphics.drawCenteredString(this.font, this.feedback, this.width / 2, 220, this.feedbackColor);
        }
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        }
    }
}
