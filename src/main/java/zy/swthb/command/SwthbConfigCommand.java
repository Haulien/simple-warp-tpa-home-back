package zy.swthb.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import zy.swthb.config.ModConfig;

import java.util.function.Supplier;

/**
 * /swthbconfig 命令 — 修改模组运行时配置
 * <p>
 * - 管理员权限项：最大家数量、最大 Warp 数量、传送倒计时、/back 开关、重载配置、保存配置
 * - 所有人可用：teleportEffects（传送粒子效果开关）
 */
public class SwthbConfigCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("swthbconfig")
                .then(Commands.literal("maxHomes")
                        .requires(source -> Commands.LEVEL_GAMEMASTERS.check(source.permissions()))
                        .then(Commands.argument("amount", IntegerArgumentType.integer(1, 100))
                                .executes(SwthbConfigCommand::executeSetMaxHomes)))
                .then(Commands.literal("maxWarps")
                        .requires(source -> Commands.LEVEL_GAMEMASTERS.check(source.permissions()))
                        .then(Commands.argument("amount", IntegerArgumentType.integer(1, 100))
                                .executes(SwthbConfigCommand::executeSetMaxWarps)))
                .then(Commands.literal("teleportDelay")
                        .requires(source -> Commands.LEVEL_GAMEMASTERS.check(source.permissions()))
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(0, 120))
                                .executes(SwthbConfigCommand::executeSetTeleportDelay)))
                .then(Commands.literal("backEnabled")
                        .requires(source -> Commands.LEVEL_GAMEMASTERS.check(source.permissions()))
                        .then(Commands.literal("true")
                                .executes(ctx -> executeSetBackEnabled(ctx, true)))
                        .then(Commands.literal("false")
                                .executes(ctx -> executeSetBackEnabled(ctx, false))))
                .then(Commands.literal("teleportEffects")
                        .then(Commands.literal("true")
                                .executes(ctx -> executeSetTeleportEffectsEnabled(ctx, true)))
                        .then(Commands.literal("false")
                                .executes(ctx -> executeSetTeleportEffectsEnabled(ctx, false))))
                .then(Commands.literal("reload")
                        .requires(source -> Commands.LEVEL_GAMEMASTERS.check(source.permissions()))
                        .executes(SwthbConfigCommand::executeReload))
                .then(Commands.literal("save")
                        .requires(source -> Commands.LEVEL_GAMEMASTERS.check(source.permissions()))
                        .executes(SwthbConfigCommand::executeSave))
        );
    }

    private static int executeSetMaxHomes(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        int amount = IntegerArgumentType.getInteger(ctx, "amount");

        ModConfig config = ModConfig.getInstance();
        int oldValue = config.getMaxHomes();
        config.setMaxHomes(amount);

        source.sendSuccess(() ->
                Component.translatable("swthb.config.max_homes_set",
                                String.valueOf(oldValue), String.valueOf(amount))
                        .withStyle(ChatFormatting.GREEN),
                true
        );

        return 1;
    }

    private static int executeSetMaxWarps(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        int amount = IntegerArgumentType.getInteger(ctx, "amount");

        ModConfig config = ModConfig.getInstance();
        int oldValue = config.getMaxWarps();
        config.setMaxWarps(amount);

        source.sendSuccess(() ->
                Component.translatable("swthb.config.max_warps_set",
                                String.valueOf(oldValue), String.valueOf(amount))
                        .withStyle(ChatFormatting.GREEN),
                true
        );

        return 1;
    }

    private static int executeSetTeleportDelay(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        int seconds = IntegerArgumentType.getInteger(ctx, "seconds");

        ModConfig config = ModConfig.getInstance();
        int oldValue = config.getTeleportDelay();
        config.setTeleportDelay(seconds);

        source.sendSuccess(() ->
                        Component.translatable("swthb.config.teleport_delay_set",
                                        String.valueOf(oldValue), String.valueOf(seconds))
                                .withStyle(ChatFormatting.GREEN),
                true
        );

        return 1;
    }

    private static int executeSetBackEnabled(CommandContext<CommandSourceStack> ctx, boolean enabled) {
        CommandSourceStack source = ctx.getSource();

        ModConfig config = ModConfig.getInstance();
        boolean oldValue = config.isBackEnabled();
        config.setBackEnabled(enabled);

        Supplier<Component> msg;
        if (enabled) {
            msg = () -> Component.translatable("swthb.config.back_enabled").withStyle(ChatFormatting.GREEN);
        } else {
            msg = () -> Component.translatable("swthb.config.back_disabled").withStyle(ChatFormatting.RED);
        }
        source.sendSuccess(msg, true);

        return 1;
    }

    private static int executeSetTeleportEffectsEnabled(CommandContext<CommandSourceStack> ctx, boolean enabled) {
        CommandSourceStack source = ctx.getSource();

        ModConfig config = ModConfig.getInstance();
        config.setTeleportEffectsEnabled(enabled);

        Supplier<Component> msg;
        if (enabled) {
            msg = () -> Component.translatable("swthb.config.teleport_effects_enabled").withStyle(ChatFormatting.GREEN);
        } else {
            msg = () -> Component.translatable("swthb.config.teleport_effects_disabled").withStyle(ChatFormatting.RED);
        }
        source.sendSuccess(msg, true);

        return 1;
    }

    private static int executeReload(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();

        ModConfig.load(source.getServer());

        source.sendSuccess(() ->
                Component.translatable("swthb.config.reloaded").withStyle(ChatFormatting.GREEN),
                true
        );

        return 1;
    }

    private static int executeSave(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();

        ModConfig.getInstance().save();

        source.sendSuccess(() ->
                Component.translatable("swthb.config.saved").withStyle(ChatFormatting.GREEN),
                true
        );

        return 1;
    }
}
