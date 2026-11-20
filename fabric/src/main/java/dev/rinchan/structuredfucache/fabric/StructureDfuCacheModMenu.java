package dev.rinchan.structuredfucache.fabric;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import dev.rinchan.structuredfucache.client.StructureDfuCacheConfigScreen;

public final class StructureDfuCacheModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return StructureDfuCacheConfigScreen::new;
    }
}
