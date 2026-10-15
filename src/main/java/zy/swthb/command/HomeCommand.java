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
import net.minecraft.network.chat.MutableComponent;
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
        // /home (no name) → default home
        dispatcher.register(Commands.literal("home")
                .executes(ServerThread.run(HomeCommand::executeDefaultHome))
                .then(Commands.argument("name", StringArgumentType.greedyString())
                        .suggests(HomeCommand::suggestHomes)
                        .executes(ServerThread.run(HomeCommand::executeHome))));

        // /defaulthome → show current default; /defaulthome <name> → set it
        dispatcher.register(Commands.literal("defaulthome")
                .executes(ServerThread.run(HomeCommand::executeShowDefaultHome))
                .then(Commands.argument("name", StringArgumentType.greedyString())
                        .suggests(HomeCommand::suggestHomes)
                        .executes(ServerThread.run(HomeCommand::executeSetDefaultHome))));

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
        return teleportHome(source, player, name);
    }

    // ========== /home (default) ==========

    private static int executeDefaultHome(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayerOrException();
        ModConfig config = ModConfig.getInstance();

        String name = config.getDefaultHome(player.getUUID());
        if (name == null) {
            List<ModConfig.HomeEntry> homes = config.getHomes(player.getUUID());
            if (homes.isEmpty()) {
                source.sendFailure(Component.translatable("swthb.home.empty")
                        .withStyle(ChatFormatting.RED));
                return 0;
            }
            if (homes.size() > 1) {
                source.sendFailure(Component.translatable("swthb.home.no_default")
                        .withStyle(ChatFormatting.RED));
                return 0;
            }
            // Exactly one home: use it
            name = homes.getFirst().getName();
        }
        return teleportHome(source, player, name);
    }

    // ========== /defaulthome ==========

    private static int executeShowDefaultHome(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayerOrException();
        String name = ModConfig.getInstance().getDefaultHome(player.getUUID());
        if (name == null) {
            source.sendFailure(Component.translatable("swthb.home.default_none")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }
        player.sendSystemMessage(Component.translatable("swthb.home.default_current", name).withStyle(ChatFormatting.GREEN));
        return 1;
    }

    private static int executeSetDefaultHome(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayerOrException();
        String name = StringArgumentType.getString(ctx, "name");
        ModConfig config = ModConfig.getInstance();

        if (!config.homeExists(player.getUUID(), name)) {
            source.sendFailure(Component.translatable("swthb.home.not_found", name).withStyle(ChatFormatting.RED));
            return 0;
        }
        config.setDefaultHome(player.getUUID(), name);
        player.sendSystemMessage(Component.translatable("swthb.home.default_set", name).withStyle(ChatFormatting.GREEN));
        return 1;
    }

    // ========== shared teleport ==========

    private static int teleportHome(CommandSourceStack source, ServerPlayer player, String name) {
        ModConfig config = ModConfig.getInstance();
        ModConfig.HomeEntry home = config.getHome(player.getUUID(), name);

        if (home == null) {
            source.sendFailure(
                    Component.translatable("swthb.home.not_found", name)
                            .withStyle(ChatFormatting.RED)
            );
            return 0;
        }

        Identifier dimId = Identifier.parse(home.getWorld());
        ResourceKey<Level> dimKey = ResourceKey.create(Registries.DIMENSION, dimId);
        ServerLevel targetLevel = source.getServer().getLevel(dimKey);

        if (targetLevel == null) {
            source.sendFailure(Component.translatable("swthb.home.dimension_invalid"));
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
                        Component.translatable("swthb.home.teleported", name)
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
        Component title = Component.translatable("swthb.home.list_title", String.valueOf(homes.size()), String.valueOf(config.getMaxHomes()))
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
        player.sendSystemMessage(title);

        if (homes.isEmpty()) {
            player.sendSystemMessage(
                    Component.translatable("swthb.home.empty")
                            .withStyle(ChatFormatting.GRAY)
            );
            return 1;
        }

        String defaultName = config.getDefaultHome(player.getUUID());
        for (int i = 0; i < homes.size(); i++) {
            ModConfig.HomeEntry h = homes.get(i);
            boolean isDefault = h.getName().equals(defaultName);
            Component dimDisplay = DimensionDisplay.of(h.getWorld());

            Component nameComp = Component.literal((i + 1) + ". " + h.getName())
                    .withStyle(style -> style
                            .withColor(ChatFormatting.GREEN)
                            .withClickEvent(new ClickEvent.RunCommand("/home " + h.getName()))
                            .withHoverEvent(new HoverEvent.ShowText(
                                    Component.translatable("swthb.home.click_teleport", h.getName()))));

            Component infoComp = Component.literal("  [")
                    .withStyle(ChatFormatting.GRAY)
                    .append(dimDisplay)
                    .append(Component.literal("] ").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(String.format("%.0f, %.0f, %.0f", h.getX(), h.getY(), h.getZ()))
                            .withStyle(ChatFormatting.DARK_GRAY));

            MutableComponent line = Component.literal("").append(nameComp);
            if (isDefault) {
                line.append(Component.translatable("swthb.home.default_tag").withStyle(ChatFormatting.YELLOW));
            }
            player.sendSystemMessage(line.append(infoComp));
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
                    Component.translatable("swthb.home.max_reached",
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
                    Component.translatable("swthb.home.updated", name)
                            .withStyle(ChatFormatting.GREEN)
            );
        } else {
            player.sendSystemMessage(
                    Component.translatable("swthb.home.set_success", name,
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
                    Component.translatable("swthb.home.deleted", name)
                            .withStyle(ChatFormatting.GREEN)
            );
            return 1;
        } else {
            source.sendFailure(
                    Component.translatable("swthb.home.not_found", name)
                            .withStyle(ChatFormatting.RED)
            );
            return 0;
        }
    }

    // ========== TAB 补齐 ==========

    private static CompletableFuture<Suggestions> suggestHomes(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) return builder.buildFuture();

        List<String> names = ModConfig.getInstance().getHomes(player.getUUID()).stream()
                .map(ModConfig.HomeEntry::getName)
                .toList();
        return Suggest.matching(names, builder);
    }

}
