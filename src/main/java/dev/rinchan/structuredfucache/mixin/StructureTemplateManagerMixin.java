package dev.rinchan.structuredfucache.mixin;

import dev.rinchan.structuredfucache.cache.StructureCacheService;
import java.io.IOException;
import java.io.InputStream;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(StructureTemplateManager.class)
abstract class StructureTemplateManagerMixin {
    @Redirect(
        method = "lambda$loadFromResource$1",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/packs/resources/ResourceManager;open(Lnet/minecraft/resources/ResourceLocation;)Ljava/io/InputStream;"
        )
    )
    private InputStream structureDfuCache$openCachedResource(ResourceManager resourceManager, ResourceLocation fileLocation) throws IOException {
        return StructureCacheService.open(resourceManager, fileLocation);
    }
}
