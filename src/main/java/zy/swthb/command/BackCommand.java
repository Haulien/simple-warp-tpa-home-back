package zy.swthb.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import zy.swthb.config.ModConfig;

/**
 * /back 命令 — 返回最后一次死亡或传送前的位置
 * <p>
 * 可在配置中通过 backEnabled 开启/关闭。
 */
public class BackCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("back")
                .executes(BackCommand::executeBack));
    }

    private static int executeBack(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;

        // 检查功能是否开启
        if (!ModConfig.getInstance().isBackEnabled()) {
            source.sendFailure(Component.translatable("swthb.back.disabled"));
            return 0;
        }

        // 检查是否有 back 点
        BackHandler.BackPoint bp = BackHandler.getBackPoint(player);
        if (bp == null) {
            source.sendFailure(Component.translatable("swthb.back.none"));
            return 0;
        }

        // 解析目标维度
        Identifier dimId = Identifier.parse(bp.world());
        ResourceKey<Level> dimKey = ResourceKey.create(Registries.DIMENSION, dimId);
        ServerLevel targetLevel = source.getServer().getLevel(dimKey);

        if (targetLevel == null) {
            source.sendFailure(Component.translatable("swthb.back.dimension_invalid"));
            return 0;
        }

        // 执行跨维度传送
        TeleportTransition transition = new TeleportTransition(
                targetLevel,
                new Vec3(bp.x(), bp.y(), bp.z()),
                player.getDeltaMovement(),
                bp.yaw(),
                bp.pitch(),
                TeleportTransition.DO_NOTHING
        );
        player.teleport(transition);

        // 显示返回来源类型（死亡地点 / 传送前位置）
        String typeKey = bp.type() == BackHandler.LocationType.DEATH
                ? "swthb.back.type_death" : "swthb.back.type_teleport";

        player.sendSystemMessage(
                Component.translatable("swthb.back.success", Component.translatable(typeKey))
                        .withStyle(ChatFormatting.GREEN)
        );

        return 1;
    }
}
