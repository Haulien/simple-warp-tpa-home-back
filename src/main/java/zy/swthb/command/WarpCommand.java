package zy.swthb.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import zy.swthb.config.ModConfig;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Warp 公共传送点功能命令：warp、warps、setwarp、delwarp
 * <p>
 * - setwarp / delwarp 需要 OP 权限（Gamemaster 级别）
 */
public class WarpCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // /warp <名称>
        dispatcher.register(Commands.literal("warp")
                .then(Commands.argument("name", StringArgumentType.greedyString())
                        .suggests(WarpCommand::suggestWarps)
                        .executes(ServerThread.run(WarpCommand::executeWarp))));

        // /warps
        dispatcher.register(Commands.literal("warps")
                .executes(ServerThread.run(WarpCommand::executeWarps)));

        // /setwarp <名称>（OP）
        dispatcher.register(Commands.literal("setwarp")
                .requires(source -> Commands.LEVEL_GAMEMASTERS.check(source.permissions()))
                .then(Commands.argument("name", StringArgumentType.greedyString())
                        .suggests(WarpCommand::suggestWarps)
                        .executes(ServerThread.run(WarpCommand::executeSetWarp))));

        // /delwarp <名称>（OP）
        dispatcher.register(Commands.literal("delwarp")
                .requires(source -> Commands.LEVEL_GAMEMASTERS.check(source.permissions()))
                .then(Commands.argument("name", StringArgumentType.greedyString())
                        .suggests(WarpCommand::suggestWarps)
                        .executes(ServerThread.run(WarpCommand::executeDelWarp))));
    }

    // ========== /warp <name> ==========

    private static int executeWarp(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;

        String name = StringArgumentType.getString(ctx, "name");

        ModConfig config = ModConfig.getInstance();
        ModConfig.WarpEntry warp = config.getWarp(name);

        if (warp == null) {
            source.sendFailure(
                    Component.translatableWithFallback("swthb.warp.not_found",
                            "找不到名为 \"%s\" 的公共传送点", name)
                            .withStyle(ChatFormatting.RED)
            );
            return 0;
        }

        Identifier dimId = Identifier.parse(warp.getWorld());
        ResourceKey<Level> dimKey = ResourceKey.create(Registries.DIMENSION, dimId);
        ServerLevel targetLevel = source.getServer().getLevel(dimKey);

        if (targetLevel == null) {
            source.sendFailure(Component.translatableWithFallback(
                    "swthb.warp.dimension_invalid", "公共传送点的维度数据异常，无法传送"));
            return 0;
        }

        // 记录传送前的位置，供 /back 返回
        BackHandler.saveBackPoint(player, BackHandler.LocationType.TELEPORT);

        TeleportTransition transition = new TeleportTransition(
                targetLevel,
                new Vec3(warp.getX(), warp.getY(), warp.getZ()),
                player.getDeltaMovement(),
                warp.getYaw(),
                warp.getPitch(),
                TeleportTransition.DO_NOTHING
        );
        TeleportHandler.startTeleport(
                player,
                transition,
                () -> player.sendSystemMessage(
                        Component.translatableWithFallback("swthb.warp.teleported",
                                "已传送到公共传送点 \"%s\"", name)
                                .withStyle(ChatFormatting.GREEN)
                ),
                () -> {}
        );

        return 1;
    }

    // ========== /warps ==========

    private static int executeWarps(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;

        ModConfig config = ModConfig.getInstance();
        Map<String, ModConfig.WarpEntry> warps = config.getWarps();

        Component title = Component.translatableWithFallback("swthb.warp.list_title",
                "=== 公共传送点列表 (%s) ===", String.valueOf(warps.size()))
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
        player.sendSystemMessage(title);

        if (warps.isEmpty()) {
            player.sendSystemMessage(
                    Component.translatableWithFallback("swthb.warp.empty",
                            "暂无公共传送点，请联系管理员添加")
                            .withStyle(ChatFormatting.GRAY)
            );
            return 1;
        }

        int index = 1;
        for (Map.Entry<String, ModConfig.WarpEntry> entry : warps.entrySet()) {
            String warpName = entry.getKey();
            ModConfig.WarpEntry w = entry.getValue();
            Component dimDisplay = DimensionDisplay.of(w.getWorld());

            Component nameComp = Component.literal(index + ". " + warpName)
                    .withStyle(style -> style
                            .withColor(ChatFormatting.LIGHT_PURPLE)
                            .withClickEvent(new ClickEvent.RunCommand("/warp " + warpName))
                            .withHoverEvent(new HoverEvent.ShowText(
                                    Component.translatableWithFallback("swthb.warp.click_teleport",
                                            "点击传送至 \"%s\"", warpName))));

            Component infoComp = Component.literal("  [")
                    .withStyle(ChatFormatting.GRAY)
                    .append(dimDisplay)
                    .append(Component.literal("] ").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(String.format("%.0f, %.0f, %.0f", w.getX(), w.getY(), w.getZ()))
                            .withStyle(ChatFormatting.DARK_GRAY));

            player.sendSystemMessage(Component.literal("").append(nameComp).append(infoComp));
            index++;
        }

        return 1;
    }

    // ========== /setwarp <name> ==========

    private static int executeSetWarp(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;

        String name = StringArgumentType.getString(ctx, "name");

        ModConfig config = ModConfig.getInstance();

        boolean isUpdate = config.getWarp(name) != null;
        if (!isUpdate && config.getWarps().size() >= config.getMaxWarps()) {
            source.sendFailure(
                    Component.translatableWithFallback("swthb.warp.max_reached",
                            "公共传送点数量已达上限 (%s)，请先删除一个",
                            String.valueOf(config.getMaxWarps()))
                            .withStyle(ChatFormatting.RED)
            );
            return 0;
        }

        ModConfig.WarpEntry entry = new ModConfig.WarpEntry(
                player.level().dimension().identifier().toString(),
                player.getX(), player.getY(), player.getZ(),
                player.getYRot(), player.getXRot()
        );

        config.setWarp(name, entry);

        if (isUpdate) {
            source.sendSuccess(() ->
                    Component.translatableWithFallback("swthb.warp.updated",
                            "已更新公共传送点 \"%s\" 的位置", name)
                            .withStyle(ChatFormatting.GREEN),
                    true
            );
        } else {
            source.sendSuccess(() ->
                    Component.translatableWithFallback("swthb.warp.set_success",
                            "已设置公共传送点 \"%s\"", name)
                            .withStyle(ChatFormatting.GREEN),
                    true
            );
        }

        return 1;
    }

    // ========== /delwarp <name> ==========

    private static int executeDelWarp(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;

        String name = StringArgumentType.getString(ctx, "name");

        ModConfig config = ModConfig.getInstance();
        if (config.removeWarp(name)) {
            source.sendSuccess(() ->
                    Component.translatableWithFallback("swthb.warp.deleted",
                            "已删除公共传送点 \"%s\"", name)
                            .withStyle(ChatFormatting.GREEN),
                    true
            );
            return 1;
        } else {
            source.sendFailure(
                    Component.translatableWithFallback("swthb.warp.not_found",
                            "找不到名为 \"%s\" 的公共传送点", name)
                            .withStyle(ChatFormatting.RED)
            );
            return 0;
        }
    }

    // ========== TAB 补齐 ==========

    private static CompletableFuture<Suggestions> suggestWarps(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        ModConfig config = ModConfig.getInstance();
        for (String name : config.getWarps().keySet()) {
            builder.suggest(name);
        }
        return builder.buildFuture();
    }

}
