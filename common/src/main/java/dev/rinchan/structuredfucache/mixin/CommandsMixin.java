package dev.rinchan.structuredfucache.mixin;

import com.mojang.brigadier.CommandDispatcher;
import dev.rinchan.structuredfucache.command.StructureDfuCacheCommand;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Registers /structuredfucache on all loaders without requiring Fabric API or loader event glue.
 */
@Mixin(Commands.class)
abstract class CommandsMixin {
    @Shadow
    @Final
    private CommandDispatcher<CommandSourceStack> dispatcher;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void structureDfuCache$registerCommands(CallbackInfo callback) {
        StructureDfuCacheCommand.register(this.dispatcher);
    }
}
