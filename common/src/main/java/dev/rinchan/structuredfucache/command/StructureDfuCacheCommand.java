package dev.rinchan.structuredfucache.command;

import com.mojang.brigadier.CommandDispatcher;
import dev.rinchan.structuredfucache.StructureDfuCache;
import dev.rinchan.structuredfucache.cache.CacheStats;
import dev.rinchan.structuredfucache.compat.McCompat;
import dev.rinchan.structuredfucache.config.StructureDfuCacheConfig;
import java.util.Locale;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * OP diagnostic command (/structuredfucache status | clear) registered directly via Brigadier.
 */
public final class StructureDfuCacheCommand {
    private StructureDfuCacheCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("structuredfucache")
                .requires(McCompat::hasPermissionLevel2)
                .then(Commands.literal("status").executes(ctx -> status(ctx.getSource())))
                .then(Commands.literal("clear").executes(ctx -> clear(ctx.getSource())))
        );
    }

    private static int status(CommandSourceStack source) {
        StructureDfuCacheConfig cfg = StructureDfuCacheConfig.get();
        CacheStats.Snapshot snap = CacheStats.inspect(StructureDfuCache.cacheRoot());
        Component modeText = Component.translatable(
            "status.structure_dfu_cache.mode." + snap.mode().name().toLowerCase(Locale.ROOT)
        );
        Component timeText = snap.lastPrepareMs() >= 0L
            ? Component.translatable("status.structure_dfu_cache.ms", snap.lastPrepareMs())
            : Component.translatable("status.structure_dfu_cache.not_run");
        Component workersText = cfg.maxWorkers() == 0
            ? Component.translatable("screen.structure_dfu_cache.workers_auto")
            : Component.literal(Integer.toString(cfg.maxWorkers()));

        source.sendSuccess(
            () -> Component.translatable(
                "command.structure_dfu_cache.status",
                modeText,
                timeText,
                snap.convertedCount(),
                snap.passthroughCount(),
                CacheStats.formatBytes(snap.overlayBytes()),
                snap.overlayFiles(),
                workersText
            ),
            false
        );
        return 1;
    }

    private static int clear(CommandSourceStack source) {
        CacheStats.ClearResult result = CacheStats.clearCache(StructureDfuCache.cacheRoot());
        if (result.cleared()) {
            source.sendSuccess(
                () -> Component.translatable(
                    "command.structure_dfu_cache.cleared",
                    result.removedFiles(),
                    CacheStats.formatBytes(result.freedBytes())
                ),
                true
            );
            return 1;
        }
        if (result.locked()) {
            source.sendFailure(Component.translatable("command.structure_dfu_cache.clear_locked"));
        } else {
            source.sendFailure(Component.translatable("command.structure_dfu_cache.clear_failed"));
        }
        return 0;
    }
}
