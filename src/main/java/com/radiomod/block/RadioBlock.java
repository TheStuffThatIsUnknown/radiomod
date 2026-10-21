package com.radiomod.block;

import com.mojang.serialization.MapCodec;
import com.radiomod.blockentity.RadioBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Radio block with 8-direction facing (45° increments). Plays internet radio streams
 * with positional audio. Supports placement on Sable SubLevel physics objects.
 */
public class RadioBlock extends BaseEntityBlock {

    private static final MapCodec<RadioBlock> CODEC = simpleCodec(RadioBlock::new);

    /**
     * 8-way facing: 0=N, 1=NE, 2=E, 3=SE, 4=S, 5=SW, 6=W, 7=NW
     */
    public static final IntegerProperty FACING = IntegerProperty.create("facing", 0, 7);

    private static final VoxelShape[] SHAPES = new VoxelShape[8];

    static {
        SHAPES[0] = Block.box(1, 0, 5, 15, 8, 11);
        SHAPES[2] = Block.box(5, 0, 1, 11, 8, 15);
        SHAPES[4] = Block.box(1, 0, 5, 15, 8, 11);
        SHAPES[6] = Block.box(5, 0, 1, 11, 8, 15);
        SHAPES[1] = Shapes.or(Block.box(7, 0, 0, 13, 8, 4), Block.box(2, 0, 4, 15, 8, 8),
                Block.box(1, 0, 8, 14, 8, 12), Block.box(3, 0, 12, 9, 8, 16));
        SHAPES[3] = Shapes.or(Block.box(12, 0, 7, 16, 8, 13), Block.box(8, 0, 2, 12, 8, 15),
                Block.box(4, 0, 1, 8, 8, 14), Block.box(0, 0, 3, 4, 8, 9));
        SHAPES[5] = Shapes.or(Block.box(3, 0, 12, 9, 8, 16), Block.box(1, 0, 8, 14, 8, 12),
                Block.box(2, 0, 4, 15, 8, 8), Block.box(7, 0, 0, 13, 8, 4));
        SHAPES[7] = Shapes.or(Block.box(0, 0, 3, 4, 8, 9), Block.box(4, 0, 1, 8, 8, 14),
                Block.box(8, 0, 2, 12, 8, 15), Block.box(12, 0, 7, 16, 8, 13));
    }

    @Override
    public MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    public RadioBlock(Properties props) {
        super(props);
        registerDefaultState(stateDefinition.any().setValue(FACING, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    /**
     * Determines the facing direction when placed. The block is oriented with its
     * front facing away from the player (standard Minecraft directional-block
     * behavior), correcting for SubLevel rotation so placement is consistent.
     *
     * @param ctx BlockPlaceContext with player yaw and target position
     * @return BlockState with correct FACING value (0-7)
     */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        float yaw = ctx.getPlayer() != null ? ctx.getPlayer().getYRot() : 0f;

        BlockPos pos = ctx.getClickedPos();
        if (Math.abs(pos.getX()) > 1_000_000 || Math.abs(pos.getZ()) > 1_000_000) {
            float subLevelYaw = getSubLevelYaw(ctx.getLevel(), pos);
            yaw = yaw - subLevelYaw;
        }

        float normalized = ((yaw % 360) + 360) % 360;
        int sector = (int) Math.floor((normalized + 22.5f) / 45f) % 8;
        // +4 turns the block so its front faces away from the player, keeping the
        // outline (SHAPES) and blockstate model rotation aligned.
        return defaultBlockState().setValue(FACING, (sector + 4) % 8);
    }

    /**
     * Extracts the yaw of a Sable SubLevel using reflection.
     * Uses Sable.HELPER.getContaining(level, blockPos) → logicalPose().orientation().
     *
     * @param level    The level where the block is placed
     * @param blockPos Block's position (SubLevel-local, >1M offset)
     * @return SubLevel yaw in degrees (Minecraft convention: CW-positive), 0 on error
     */
    private static float getSubLevelYaw(Level level, BlockPos blockPos) {
        try {
            Class<?> sableClass = Class.forName("dev.ryanhcode.sable.Sable");
            Object helper = sableClass.getField("HELPER").get(null);

            Object subLevel = null;
            for (java.lang.reflect.Method m : helper.getClass().getMethods()) {
                if (!"getContaining".equals(m.getName()) || subLevel != null) continue;
                Class<?>[] params = m.getParameterTypes();
                try {
                    if (params.length == 2 && params[0].isAssignableFrom(level.getClass())
                            && params[1].isAssignableFrom(BlockPos.class)) {
                        subLevel = m.invoke(helper, level, blockPos);
                    } else if (params.length == 1 && params[0].isAssignableFrom(level.getClass())) {
                        subLevel = m.invoke(helper, level);
                    }
                } catch (Exception ignored) {
                }
            }

            if (subLevel == null) {
                try {
                    Class<?> subLevelClass = Class.forName("dev.ryanhcode.sable.sublevel.SubLevel");
                    if (subLevelClass.isInstance(level)) subLevel = level;
                } catch (ClassNotFoundException ignored) {
                }
            }

            if (subLevel == null) return 0f;

            Object pose = subLevel.getClass().getMethod("logicalPose").invoke(subLevel);
            if (pose == null) return 0f;

            Object orientation = null;
            try {
                orientation = pose.getClass().getMethod("orientation").invoke(pose);
            } catch (Exception ignored) {
            }
            if (orientation == null) {
                try {
                    orientation = pose.getClass().getMethod("rotation").invoke(pose);
                } catch (Exception ignored) {
                }
            }

            if (orientation != null) return extractYawFromRotation(orientation);
        } catch (Exception ignored) {
        }
        return 0f;
    }

    /**
     * Extracts the yaw angle from a JOML Quaterniond/Quaternionf.
     *
     * @param rotation Quaternion object with x/y/z/w methods or fields
     * @return Yaw in degrees (CW-positive, Minecraft convention), 0 on error
     */
    private static float extractYawFromRotation(Object rotation) {
        try {
            double qx, qy, qz, qw;
            try {
                qx = ((Number) rotation.getClass().getMethod("x").invoke(rotation)).doubleValue();
                qy = ((Number) rotation.getClass().getMethod("y").invoke(rotation)).doubleValue();
                qz = ((Number) rotation.getClass().getMethod("z").invoke(rotation)).doubleValue();
                qw = ((Number) rotation.getClass().getMethod("w").invoke(rotation)).doubleValue();
            } catch (Exception e) {
                qx = ((Number) rotation.getClass().getField("x").get(rotation)).doubleValue();
                qy = ((Number) rotation.getClass().getField("y").get(rotation)).doubleValue();
                qz = ((Number) rotation.getClass().getField("z").get(rotation)).doubleValue();
                qw = ((Number) rotation.getClass().getField("w").get(rotation)).doubleValue();
            }

            double siny_cosp = 2.0 * (qw * qy + qx * qz);
            double cosy_cosp = 1.0 - 2.0 * (qy * qy + qz * qz);
            double yawRadians = Math.atan2(siny_cosp, cosy_cosp);
            return -(float) Math.toDegrees(yawRadians);
        } catch (Exception e) {
            return 0f;
        }
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPES[state.getValue(FACING)];
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new RadioBlockEntity(pos, state);
    }

    /**
     * Opens the radio GUI on right-click. Server-side only.
     * The radio key is sent along so the menu also works when the block entity
     * is not in the client level (Sable SubLevel, Create contraption/train).
     *
     * @return SUCCESS on both sides
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (!level.isClientSide()) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof RadioBlockEntity radio) {
                String key = radio.getRadioKey();
                ((ServerPlayer) player).openMenu(radio, buf -> {
                    buf.writeBlockPos(pos);
                    buf.writeUtf(key);
                });
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("block.radiomod.radio_block.tooltip"));
    }
}
