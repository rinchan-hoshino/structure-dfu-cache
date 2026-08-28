package dev.rinchan.structuredfucache.mixin;

import com.mojang.datafixers.DataFixer;
import dev.rinchan.structuredfucache.cache.StructureCacheBootstrap;
import java.nio.file.Path;
import net.minecraft.core.HolderGetter;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(StructureTemplateManager.class)
abstract class StructureTemplateManagerMixin {
    @Shadow
    private ResourceManager resourceManager;

    @Shadow
    @Final
    private DataFixer fixerUpper;

    @Unique
    private Path structureDfuCache$generationRoot;

    @Unique
    private Path structureDfuCache$retiredGenerationRoot;

    @Unique
    private StructureCacheBootstrap.PreparedStructureResources structureDfuCache$pendingReload;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void structureDfuCache$prepareInitialResources(
        ResourceManager original,
        LevelStorageSource.LevelStorageAccess levelStorage,
        DataFixer dataFixer,
        HolderGetter<Block> blockLookup,
        CallbackInfo callback
    ) {
        StructureCacheBootstrap.PreparedStructureResources prepared = StructureCacheBootstrap.prepare(original, this.fixerUpper);
        this.resourceManager = prepared.resourceManager();
        this.structureDfuCache$generationRoot = prepared.generationRoot();
    }

    @Inject(method = "onResourceManagerReload", at = @At("HEAD"))
    private void structureDfuCache$prepareReload(ResourceManager original, CallbackInfo callback) {
        // Minecraft closes the previous resource manager before this callback. Publish the new vanilla
        // manager immediately so concurrent structure requests never observe closed pack resources.
        this.resourceManager = original;
        this.structureDfuCache$pendingReload = StructureCacheBootstrap.prepare(original, this.fixerUpper);
    }

    @Inject(method = "onResourceManagerReload", at = @At("RETURN"))
    private void structureDfuCache$activateReload(ResourceManager original, CallbackInfo callback) {
        StructureCacheBootstrap.PreparedStructureResources prepared = this.structureDfuCache$pendingReload;
        if (prepared == null) {
            return;
        }
        this.resourceManager = prepared.resourceManager();
        StructureCacheBootstrap.retireGeneration(this.structureDfuCache$retiredGenerationRoot);
        this.structureDfuCache$retiredGenerationRoot = this.structureDfuCache$generationRoot;
        this.structureDfuCache$generationRoot = prepared.generationRoot();
        this.structureDfuCache$pendingReload = null;
    }
}
