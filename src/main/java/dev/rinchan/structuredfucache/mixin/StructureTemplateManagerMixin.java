package dev.rinchan.structuredfucache.mixin;

import com.mojang.datafixers.DataFixer;
import dev.rinchan.structuredfucache.cache.StructureCacheBootstrap;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Injects at RETURN of StructureTemplateManager.<init> and onResourceManagerReload.
 * By RETURN, vanilla has finished assembling the incoming ResourceManager and (on reload)
 * cleared its template cache, so swapping this.resourceManager mounts the DFU overlay
 * without touching vanilla pack discovery.
 */
@Mixin(StructureTemplateManager.class)
abstract class StructureTemplateManagerMixin {
    @Shadow
    private ResourceManager resourceManager;

    @Shadow
    @Final
    private DataFixer fixerUpper;

    @Inject(method = {"<init>", "onResourceManagerReload"}, at = @At("RETURN"))
    private void structureDfuCache$prepareResources(CallbackInfo callback) {
        this.resourceManager = StructureCacheBootstrap.prepare(this.resourceManager, this.fixerUpper);
    }
}
