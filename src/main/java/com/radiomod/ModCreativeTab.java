package com.radiomod;

import com.radiomod.block.ModBlocks;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public class ModCreativeTab {

    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, RadioMod.MODID);

    public static final Supplier<CreativeModeTab> RADIO_TAB = TABS.register("radio_tab",
            () -> CreativeModeTab.builder()
                    .icon(() -> new ItemStack(ModBlocks.RADIO_BLOCK_ITEM.get()))
                    .title(Component.translatable("itemGroup.radiomod"))
                    .displayItems((params, output) -> {
                        output.accept(ModBlocks.RADIO_BLOCK_ITEM.get());
                    })
                    .build()
    );

    public static void register(IEventBus bus) {
        TABS.register(bus);
    }
}
