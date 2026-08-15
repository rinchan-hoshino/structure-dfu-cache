package dev.rinchan.structuredfucache.mixin;

import dev.rinchan.structuredfucache.cache.StructureCacheBootstrap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.commands.Commands;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.ReloadableServerResources;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.flag.FeatureFlagSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ReloadableServerResources.class)
abstract class ReloadableServerResourcesMixin {
    @Inject(method = "loadResources", at = @At("HEAD"))
    private static void structureDfuCache$buildBeforeOtherReloadListeners(
        ResourceManager resourceManager,
        LayeredRegistryAccess<RegistryLayer> registries,
        FeatureFlagSet featureFlags,
        Commands.CommandSelection commandSelection,
        int functionCompilationLevel,
        Executor backgroundExecutor,
        Executor gameExecutor,
        CallbackInfoReturnable<CompletableFuture<ReloadableServerResources>> callback
    ) {
        StructureCacheBootstrap.ensureInitialCache(resourceManager);
    }
}
