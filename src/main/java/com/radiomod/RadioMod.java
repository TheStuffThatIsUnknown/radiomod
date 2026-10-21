package com.radiomod;

import com.mojang.logging.LogUtils;
import com.radiomod.block.ModBlocks;
import com.radiomod.blockentity.ModBlockEntities;
import com.radiomod.network.ModPayloads;
import com.radiomod.screen.ModMenuTypes;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

@Mod(RadioMod.MODID)
public class RadioMod {
    public static final String MODID = "radiomod";
    private static final Logger LOGGER = LogUtils.getLogger();

    public RadioMod(IEventBus modEventBus) {
        // Load JSON configs (general + custom stations, issues #2/#6)
        try {
            RadioConfig.load();
        } catch (Throwable t) {
            LOGGER.warn("RadioMod: config load failed: {}", t.getMessage());
        }

        // DeferredRegister setup
        ModBlocks.register(modEventBus);
        ModBlockEntities.register(modEventBus);
        ModMenuTypes.register(modEventBus);
        ModCreativeTab.register(modEventBus);

        // Network payloads registration (fired on MOD bus)
        modEventBus.register(ModPayloads.class);

        // Game-bus events
        NeoForge.EVENT_BUS.register(WorldEventHandler.class);
        NeoForge.EVENT_BUS.register(ServerTickHandler.class);

        // Client-only classes — guarded to avoid loading client classes on server
        if (FMLEnvironment.dist.isClient()) {
            ClientSetup.registerFetcherHook(); // bind LocalStationFetcher → RadioStation.Country
            modEventBus.register(ClientSetup.class);
            NeoForge.EVENT_BUS.register(ClientTickHandler.class);
            NeoForge.EVENT_BUS.register(ClientUuidTooltip.class);
            NeoForge.EVENT_BUS.register(WorldEventHandler.ClientEvents.class);
        }

        LOGGER.info("RadioMod loaded");
    }
}
