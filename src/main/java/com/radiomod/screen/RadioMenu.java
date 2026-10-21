package com.radiomod.screen;

import com.radiomod.blockentity.RadioBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

public class RadioMenu extends AbstractContainerMenu {

    public final RadioBlockEntity blockEntity;
    /** Radio key "radiomod:&lt;UUID&gt;" — always present, even when the BE is not in the client level
     * (Sable SubLevel, Create contraption/train, unloaded chunk). Issue #4. */
    public final String radioKey;
    public final BlockPos radioPos;

    // Called server-side from RadioBlockEntity#createMenu
    public RadioMenu(int id, Inventory inv, RadioBlockEntity be) {
        super(ModMenuTypes.RADIO_MENU.get(), id);
        this.blockEntity = be;
        this.radioKey = be != null ? be.getRadioKey() : "";
        this.radioPos = be != null ? be.getBlockPos() : BlockPos.ZERO;
    }

    public RadioMenu(int id, Inventory inv, RadioBlockEntity be, String radioKey, BlockPos pos) {
        super(ModMenuTypes.RADIO_MENU.get(), id);
        this.blockEntity = be;
        this.radioKey = radioKey != null ? radioKey : (be != null ? be.getRadioKey() : "");
        this.radioPos = pos != null ? pos : (be != null ? be.getBlockPos() : BlockPos.ZERO);
    }

    /** Effective key for station-selection packets (never null). */
    public String getRadioKey() {
        if (blockEntity != null) return blockEntity.getRadioKey();
        return radioKey != null ? radioKey : "";
    }

    /** Effective pos for station-selection packets. */
    public BlockPos getRadioPos() {
        if (blockEntity != null) return blockEntity.getBlockPos();
        return radioPos != null ? radioPos : BlockPos.ZERO;
    }

    // Called client-side via the registered factory
    public static RadioMenu fromNetwork(int id, Inventory inv, RegistryFriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        String key = "";
        try {
            key = buf.readUtf();
        } catch (Exception ignored) {
            // Older server / truncated packet — fall back to pos-only lookup.
        }
        // Primary lookup: block exists in the current client level (normal / main-world case).
        net.minecraft.world.level.block.entity.BlockEntity raw = inv.player.level().getBlockEntity(pos);
        RadioBlockEntity be = raw instanceof RadioBlockEntity r ? r : null;

        // Fallback: resolve via the global registry (Sable SubLevel, Create
        // contraption/train in issue #4, or chunk not yet loaded on the client).
        if (be == null && key != null && !key.isEmpty()) {
            try {
                be = RadioBlockEntity.findByKey(key);
            } catch (Exception ignored) {
            }
        }

        // We still proceed with a null blockEntity – the menu will render, and
        // station-selection packets include the radioKey so the server can locate
        // the entity via its global registry. Null is handled defensively in
        // the rest of the menu / screen.
        return new RadioMenu(id, inv, be, key, pos);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        // Issue #4: when the BE is not in the client level (contraption/train,
        // SubLevel, unloaded chunk) we still keep the menu open as long as we
        // know which radio it belongs to — the server validates on select.
        if (blockEntity == null) {
            return radioKey != null && !radioKey.isEmpty();
        }

        // Normal proximity check for plain-world radios.
        double distSq = player.distanceToSqr(
                blockEntity.getBlockPos().getX() + .5,
                blockEntity.getBlockPos().getY() + .5,
                blockEntity.getBlockPos().getZ() + .5);

        if (distSq <= 64) return true;

        // If the blockPos is a local SubLevel position the distance to the player's world
        // position may be arbitrarily large.  Detect this by checking whether the radio's
        // level is one of the server's registered levels.
        // On the client side we cannot do that check, so we allow the menu to stay open
        // whenever the block entity appears to be in a non-standard (SubLevel) context.
        // The server will enforce station-selection validity via its own registry lookup.
        try {
            if (blockEntity.getLevel() instanceof net.minecraft.server.level.ServerLevel sl) {
                // Server context: the level IS registered → it's a normal world → enforce distance
                sl.getServer(); // just confirm it works without exception
                return false;   // distSq > 64 and we're on the server → close menu
            }
        } catch (Exception ignored) { /* not a ServerLevel or no server ref */ }

        // Client side, SubLevel (physics object) or contraption (Create train) — keep open
        return true;
    }
}
