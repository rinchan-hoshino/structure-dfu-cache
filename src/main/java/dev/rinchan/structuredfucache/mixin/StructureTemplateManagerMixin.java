package dev.rinchan.structuredfucache.mixin;

import com.mojang.datafixers.DataFixer;
import dev.rinchan.structuredfucache.cache.StructureCacheBootstrap;
import java.nio.file.Path;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
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

    @Inject(method = {"<init>", "onResourceManagerReload"}, at = @At("RETURN"))
    private void structureDfuCache$prepareResources(CallbackInfo callback) {
        // Vanilla has installed the incoming manager and, on reload, cleared its template cache.
        var prepared = StructureCacheBootstrap.prepare(this.resourceManager, this.fixerUpper);
        this.resourceManager = prepared.resourceManager();
        StructureCacheBootstrap.retireGeneration(this.structureDfuCache$retiredGenerationRoot);
        this.structureDfuCache$retiredGenerationRoot = this.structureDfuCache$generationRoot;
        this.structureDfuCache$generationRoot = prepared.generationRoot();
    }
}
