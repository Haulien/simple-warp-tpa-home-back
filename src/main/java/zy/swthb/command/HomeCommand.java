package zy.swthb.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
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

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 家功能命令：home、homes、sethome、delhome
 */
public class HomeCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // /home <名称>
        dispatcher.register(Commands.literal("home")
                .then(Commands.argument("name", StringArgumentType.greedyString())
                        .suggests(HomeCommand::suggestHomes)
                        .executes(ServerThread.run(HomeCommand::executeHome))));

        // /homes
        dispatcher.register(Commands.literal("homes")
                .executes(ServerThread.run(HomeCommand::executeHomes)));

        // /sethome <名称>
        dispatcher.register(Commands.literal("sethome")
                .then(Commands.argument("name", StringArgumentType.greedyString())
                        .suggests(HomeCommand::suggestHomes)
                        .executes(ServerThread.run(HomeCommand::executeSetHome))));

        // /delhome <名称>
        dispatcher.register(Commands.literal("delhome")
                .then(Commands.argument("name", StringArgumentType.greedyString())
                        .suggests(HomeCommand::suggestHomes)
                        .executes(ServerThread.run(HomeCommand::executeDelHome))));
    }

    // ========== /home <name> ==========

    private static int executeHome(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayerOrException();
        String name = StringArgumentType.getString(ctx, "name");

        ModConfig config = ModConfig.getInstance();
        ModConfig.HomeEntry home = config.getHome(player.getUUID(), name);

        if (home == null) {
            source.sendFailure(
                    Component.translatableWithFallback("swthb.home.not_found",
                            "找不到名为 \"%s\" 的家", name)
                            .withStyle(ChatFormatting.RED)
            );
            return 0;
        }

        Identifier dimId = Identifier.parse(home.getWorld());
        ResourceKey<Level> dimKey = ResourceKey.create(Registries.DIMENSION, dimId);
        ServerLevel targetLevel = source.getServer().getLevel(dimKey);

        if (targetLevel == null) {
            source.sendFailure(Component.translatableWithFallback(
                    "swthb.home.dimension_invalid", "家的维度数据异常，无法传送"));
            return 0;
        }

        // 记录传送前的位置，供 /back 返回
        BackHandler.saveBackPoint(player, BackHandler.LocationType.TELEPORT);

        TeleportTransition transition = new TeleportTransition(
                targetLevel,
                new Vec3(home.getX(), home.getY(), home.getZ()),
                player.getDeltaMovement(),
                home.getYaw(),
                home.getPitch(),
                TeleportTransition.DO_NOTHING
        );
        TeleportHandler.startTeleport(
                player,
                transition,
                () -> player.sendSystemMessage(
                        Component.translatableWithFallback("swthb.home.teleported",
                                "已传送到家 \"%s\"", name)
                                .withStyle(ChatFormatting.GREEN)
                ),
                () -> {}
        );

        return 1;
    }

    // ========== /homes ==========

    private static int executeHomes(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;

        ModConfig config = ModConfig.getInstance();
        List<ModConfig.HomeEntry> homes = config.getHomes(player.getUUID());

        // 列表标题
        Component title = Component.translatableWithFallback("swthb.home.list_title",
                "=== 你的家 (%s/%s) ===", String.valueOf(homes.size()), String.valueOf(config.getMaxHomes()))
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
        player.sendSystemMessage(title);

        if (homes.isEmpty()) {
            player.sendSystemMessage(
                    Component.translatableWithFallback("swthb.home.empty",
                            "暂无已设置的家，使用 /sethome <名称> 来设置")
                            .withStyle(ChatFormatting.GRAY)
            );
            return 1;
        }

        for (int i = 0; i < homes.size(); i++) {
            ModConfig.HomeEntry h = homes.get(i);
            Component dimDisplay = DimensionDisplay.of(h.getWorld());

            Component nameComp = Component.literal((i + 1) + ". " + h.getName())
                    .withStyle(style -> style
                            .withColor(ChatFormatting.GREEN)
                            .withClickEvent(new ClickEvent.RunCommand("/home " + h.getName()))
                            .withHoverEvent(new HoverEvent.ShowText(
                                    Component.translatableWithFallback("swthb.home.click_teleport",
                                            "点击传送至 \"%s\"", h.getName()))));

            Component infoComp = Component.literal("  [")
                    .withStyle(ChatFormatting.GRAY)
                    .append(dimDisplay)
                    .append(Component.literal("] ").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(String.format("%.0f, %.0f, %.0f", h.getX(), h.getY(), h.getZ()))
                            .withStyle(ChatFormatting.DARK_GRAY));

            player.sendSystemMessage(Component.literal("").append(nameComp).append(infoComp));
        }

        return 1;
    }

    // ========== /sethome <name> ==========

    private static int executeSetHome(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;

        String name = StringArgumentType.getString(ctx, "name");

        ModConfig config = ModConfig.getInstance();

        boolean isUpdate = config.homeExists(player.getUUID(), name);

        if (!isUpdate && config.isAtMaxHomes(player.getUUID())) {
            source.sendFailure(
                    Component.translatableWithFallback("swthb.home.max_reached",
                            "家的数量已达上限 (%s)，请先使用 /delhome 删除一个家",
                            String.valueOf(config.getMaxHomes()))
                            .withStyle(ChatFormatting.RED)
            );
            return 0;
        }

        ModConfig.HomeEntry entry = new ModConfig.HomeEntry(
                name,
                player.level().dimension().identifier().toString(),
                player.getX(), player.getY(), player.getZ(),
                player.getYRot(), player.getXRot()
        );

        config.setHome(player.getUUID(), entry);

        if (isUpdate) {
            player.sendSystemMessage(
                    Component.translatableWithFallback("swthb.home.updated",
                            "已更新家 \"%s\" 的位置", name)
                            .withStyle(ChatFormatting.GREEN)
            );
        } else {
            player.sendSystemMessage(
                    Component.translatableWithFallback("swthb.home.set_success",
                            "已设置家 \"%s\" (%s/%s)", name,
                            String.valueOf(config.getHomeCount(player.getUUID())),
                            String.valueOf(config.getMaxHomes()))
                            .withStyle(ChatFormatting.GREEN)
            );
        }

        return 1;
    }

    // ========== /delhome <name> ==========

    private static int executeDelHome(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;

        String name = StringArgumentType.getString(ctx, "name");

        ModConfig config = ModConfig.getInstance();
        if (config.removeHome(player.getUUID(), name)) {
            player.sendSystemMessage(
                    Component.translatableWithFallback("swthb.home.deleted",
                            "已删除家 \"%s\"", name)
                            .withStyle(ChatFormatting.GREEN)
            );
            return 1;
        } else {
            source.sendFailure(
                    Component.translatableWithFallback("swthb.home.not_found",
                            "找不到名为 \"%s\" 的家", name)
                            .withStyle(ChatFormatting.RED)
            );
            return 0;
        }
    }

    // ========== TAB 补齐 ==========

    private static CompletableFuture<Suggestions> suggestHomes(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) return builder.buildFuture();

        ModConfig config = ModConfig.getInstance();
        for (ModConfig.HomeEntry home : config.getHomes(player.getUUID())) {
            builder.suggest(home.getName());
        }
        return builder.buildFuture();
    }

}
