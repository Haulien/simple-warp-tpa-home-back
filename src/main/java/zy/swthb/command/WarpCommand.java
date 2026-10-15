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
                        .executes(WarpCommand::executeWarp)));

        // /warps
        dispatcher.register(Commands.literal("warps")
                .executes(WarpCommand::executeWarps));

        // /setwarp <名称>（OP）
        dispatcher.register(Commands.literal("setwarp")
                .requires(source -> Commands.LEVEL_GAMEMASTERS.check(source.permissions()))
                .then(Commands.argument("name", StringArgumentType.greedyString())
                        .suggests(WarpCommand::suggestWarps)
                        .executes(WarpCommand::executeSetWarp)));

        // /delwarp <名称>（OP）
        dispatcher.register(Commands.literal("delwarp")
                .requires(source -> Commands.LEVEL_GAMEMASTERS.check(source.permissions()))
                .then(Commands.argument("name", StringArgumentType.greedyString())
                        .suggests(WarpCommand::suggestWarps)
                        .executes(WarpCommand::executeDelWarp)));
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
                    Component.translatable("swthb.warp.not_found", name)
                            .withStyle(ChatFormatting.RED)
            );
            return 0;
        }

        Identifier dimId = Identifier.parse(warp.getWorld());
        ResourceKey<Level> dimKey = ResourceKey.create(Registries.DIMENSION, dimId);
        ServerLevel targetLevel = source.getServer().getLevel(dimKey);

        if (targetLevel == null) {
            source.sendFailure(Component.translatable("swthb.warp.dimension_invalid"));
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
                        Component.translatable("swthb.warp.teleported", name)
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

        Component title = Component.translatable("swthb.warp.list_title", String.valueOf(warps.size()))
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
        player.sendSystemMessage(title);

        if (warps.isEmpty()) {
            player.sendSystemMessage(
                    Component.translatable("swthb.warp.empty")
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
                                    Component.translatable("swthb.warp.click_teleport", warpName))));

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
                    Component.translatable("swthb.warp.max_reached",
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
                    Component.translatable("swthb.warp.updated", name)
                            .withStyle(ChatFormatting.GREEN),
                    true
            );
        } else {
            source.sendSuccess(() ->
                    Component.translatable("swthb.warp.set_success", name)
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
                    Component.translatable("swthb.warp.deleted", name)
                            .withStyle(ChatFormatting.GREEN),
                    true
            );
            return 1;
        } else {
            source.sendFailure(
                    Component.translatable("swthb.warp.not_found", name)
                            .withStyle(ChatFormatting.RED)
            );
            return 0;
        }
    }

    // ========== TAB 补齐 ==========

    private static CompletableFuture<Suggestions> suggestWarps(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        return Suggest.matching(ModConfig.getInstance().getWarps().keySet(), builder);
    }

}
