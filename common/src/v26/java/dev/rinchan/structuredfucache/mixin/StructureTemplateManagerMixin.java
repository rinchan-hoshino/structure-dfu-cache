package dev.rinchan.structuredfucache.mixin;

import com.mojang.datafixers.DataFixer;
import dev.rinchan.structuredfucache.cache.StructureCacheBootstrap;
import net.minecraft.core.HolderGetter;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.levelgen.structure.templatesystem.loader.ResourceManagerTemplateSource;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Minecraft 26.x: Injects at RETURN of StructureTemplateManager.<init> and onResourceManagerReload,
 * updating ResourceManagerTemplateSource with the prepared StructureOverlayResourceManager.
 */
@Mixin(StructureTemplateManager.class)
abstract class StructureTemplateManagerMixin {
    @Shadow
    @Final
    private ResourceManagerTemplateSource resourceManagerSource;

    @Unique
    private DataFixer structureDfuCache$fixerUpper;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void structureDfuCache$onInit(
        ResourceManager resourceManager,
        LevelStorageSource.LevelStorageAccess levelStorage,
        DataFixer fixerUpper,
        HolderGetter<Block> blockLookup,
        CallbackInfo callback
    ) {
        this.structureDfuCache$fixerUpper = fixerUpper;
        this.resourceManagerSource.setResourceManager(
            StructureCacheBootstrap.prepare(resourceManager, fixerUpper)
        );
    }

    @Inject(method = "onResourceManagerReload", at = @At("RETURN"))
    private void structureDfuCache$onReload(ResourceManager resourceManager, CallbackInfo callback) {
        if (this.structureDfuCache$fixerUpper != null) {
            this.resourceManagerSource.setResourceManager(
                StructureCacheBootstrap.prepare(resourceManager, this.structureDfuCache$fixerUpper)
            );
        }
    }
}
