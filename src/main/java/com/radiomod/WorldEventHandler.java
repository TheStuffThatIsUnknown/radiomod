package com.radiomod;

import com.radiomod.blockentity.RadioBlockEntity;
import com.radiomod.network.ModPayloads;
import com.radiomod.radio.RadioPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.network.PacketDistributor;

public class WorldEventHandler {

    /**
     * Syncs already-playing radios (and their linked speakers) to a player
     * who just joined, so they hear ongoing stations without anyone reselecting.
     * Only loaded block entities are covered — radios in unloaded chunks start
     * playing for the player once their chunk loads and someone interacts.
     */
    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        try {
            for (RadioBlockEntity radio : RadioBlockEntity.streamAll()) {
                if (radio.isRemoved()) continue;
                String station = radio.getCurrentStation();
                if (station == null || station.isBlank() || "off".equals(station)) continue;
                String url = radio.getLastStreamUrl();
                if (url == null || url.isEmpty()) {
                    url = com.radiomod.radio.RadioStation.getById(station).getStreamUrl();
                }
                net.minecraft.world.phys.Vec3 worldPos =
                        com.radiomod.network.ModPayloads.resolveWorldPosition(radio);
                if (worldPos == null) continue;
                PacketDistributor.sendToPlayer(sp, new ModPayloads.StationBroadcastPayload(
                        BlockPos.containing(worldPos), station, radio.getRadioKey(),
                        url != null ? url : ""));
            }
        } catch (Exception ignored) {
        }
    }

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        BlockPos pos = event.getPos();
        BlockEntity be = event.getLevel().getBlockEntity(pos);
        if (be instanceof RadioBlockEntity radio) {
            ModPayloads.StationBroadcastPayload pkt =
                    new ModPayloads.StationBroadcastPayload(pos, "off", radio.getRadioKey(), "");
            for (ServerPlayer sp : ((ServerLevel) event.getLevel()).getServer().getPlayerList().getPlayers()) {
                PacketDistributor.sendToPlayer(sp, pkt);
            }
            ServerTickHandler.untrackRadio(radio.getRadioKey());
        }
    }

    // Registered separately in RadioMod when on CLIENT only
    public static class ClientEvents {
        @SubscribeEvent
        public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
            RadioPlayer.stopAll();
        }

        /**
         * Fires on the client when disconnecting from a server or leaving a singleplayer world.
         * Guarantees all active radio streams are closed immediately.
         */
        @SubscribeEvent
        public static void onClientDisconnect(ClientPlayerNetworkEvent.LoggingOut event) {
            RadioPlayer.stopAll();
        }
    }
}
