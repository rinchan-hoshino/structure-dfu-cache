package dev.rinchan.structuredfucache.mixin;

import dev.rinchan.structuredfucache.cache.StructureCacheBootstrap;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Optionally notifies joining ops / singleplayer host once per cache prepare when notifyInChat is enabled.
 */
@Mixin(PlayerList.class)
abstract class PlayerListMixin {
    @Inject(method = "placeNewPlayer", at = @At("TAIL"))
    private void structureDfuCache$onPlayerJoin(Connection connection, ServerPlayer player, CallbackInfo callback) {
        StructureCacheBootstrap.onPlayerJoin(player);
    }
}
