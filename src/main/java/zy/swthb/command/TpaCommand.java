package zy.swthb.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.portal.TeleportTransition;
import zy.swthb.command.BackHandler;
import zy.swthb.command.TeleportHandler;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TPA 传送请求功能
 * 玩家可向其他玩家发送传送请求，对方可在聊天栏点击 [接受] 或 [拒绝]。
 */
public class TpaCommand {

    // 所有待处理的传送请求：key = 目标玩家 UUID, value = 请求信息
    private static final Map<UUID, PendingRequest> PENDING = new ConcurrentHashMap<>();

    // 请求超时时间：60 秒
    private static final long TIMEOUT_MS = 60_000L;

    /**
     * 一条待处理的传送请求记录
     *
     * @param requesterId 发起请求的玩家 UUID
     * @param timestamp   请求发起时间（毫秒时间戳）
     */
    private record PendingRequest(UUID requesterId, long timestamp) {}

    /**
     * 注册 TPA 过期清理事件（每 5 分钟执行一次）
     */
    public static void registerCleanupEvent() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % 6000 == 0) {
                cleanup();
            }
        });
    }

    /**
     * 注册 /tpa、/tpay、/tpan 三个命令
     */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // /tpa <玩家> — 向目标玩家发送传送请求
        dispatcher.register(Commands.literal("tpa")
                .then(Commands.argument("target", EntityArgument.player())
                        .executes(ServerThread.run(TpaCommand::executeTpa))));

        // /tpay — 接受最近一条传送请求
        dispatcher.register(Commands.literal("tpay")
                .executes(ServerThread.run(TpaCommand::executeTpay)));

        // /tpan — 拒绝传送请求
        dispatcher.register(Commands.literal("tpan")
                .executes(ServerThread.run(TpaCommand::executeTpan)));
    }

    /**
     * 执行 /tpa <target>
     */
    private static int executeTpa(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;

        ServerPlayer target;
        try {
            target = EntityArgument.getPlayer(ctx, "target");
        } catch (Exception e) {
            source.sendFailure(Component.translatable("swthb.tpa.player_not_found"));
            return 0;
        }

        if (player.getUUID().equals(target.getUUID())) {
            source.sendFailure(Component.translatable("swthb.tpa.self"));
            return 0;
        }

        PENDING.put(target.getUUID(), new PendingRequest(player.getUUID(), System.currentTimeMillis()));

        // 通知发起方
        Component senderName = target.getDisplayName().copy().withStyle(ChatFormatting.AQUA);
        player.sendSystemMessage(
                Component.translatable("swthb.tpa.sender_msg", senderName)
                        .withStyle(ChatFormatting.GREEN)
        );

        // 通知目标方，附带可点击的接受/拒绝按钮
        Component requesterName = player.getDisplayName().copy().withStyle(ChatFormatting.AQUA);

        Component acceptBtn = Component.translatable("swthb.tpa.accept_btn")
                .withStyle(style -> style
                        .withColor(ChatFormatting.GREEN)
                        .withBold(true)
                        .withClickEvent(new ClickEvent.RunCommand("/tpay"))
                        .withHoverEvent(new HoverEvent.ShowText(
                                Component.translatable("swthb.tpa.accept_hover"))));

        Component denyBtn = Component.translatable("swthb.tpa.deny_btn")
                .withStyle(style -> style
                        .withColor(ChatFormatting.RED)
                        .withBold(true)
                        .withClickEvent(new ClickEvent.RunCommand("/tpan"))
                        .withHoverEvent(new HoverEvent.ShowText(
                                Component.translatable("swthb.tpa.deny_hover"))));

        target.sendSystemMessage(
                Component.translatable("swthb.tpa.receiver_msg",
                        requesterName, acceptBtn, denyBtn)
        );

        return 1;
    }

    /**
     * 执行 /tpay
     */
    private static int executeTpay(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;

        PendingRequest req = PENDING.remove(player.getUUID());
        if (req == null) {
            source.sendFailure(Component.translatable("swthb.tpa.no_request"));
            return 0;
        }

        ServerPlayer requester = player.level().getServer().getPlayerList().getPlayer(req.requesterId());
        if (requester == null) {
            source.sendFailure(Component.translatable("swthb.tpa.requester_offline"));
            return 0;
        }

        // 通知接受方
        Component requesterName = requester.getDisplayName().copy().withStyle(ChatFormatting.AQUA);
        player.sendSystemMessage(
                Component.translatable("swthb.tpa.accepted_receiver", requesterName)
                        .withStyle(ChatFormatting.GREEN)
        );

        // 记录传送前位置供 /back 使用
        BackHandler.saveBackPoint(requester, BackHandler.LocationType.TELEPORT);

        // 延迟传送发起方
        TeleportTransition transition = new TeleportTransition(
                player.level(),
                player.position(),
                requester.getDeltaMovement(),
                player.getYRot(),
                player.getXRot(),
                TeleportTransition.DO_NOTHING
        );
        Component playerName = player.getDisplayName().copy().withStyle(ChatFormatting.AQUA);
        TeleportHandler.startTeleport(
                requester,
                transition,
                () -> requester.sendSystemMessage(
                        Component.translatable("swthb.tpa.accepted_sender", playerName)
                                .withStyle(ChatFormatting.GREEN)
                ),
                () -> {}
        );

        return 1;
    }

    /**
     * 执行 /tpan
     */
    private static int executeTpan(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;

        PendingRequest req = PENDING.remove(player.getUUID());
        if (req == null) {
            source.sendFailure(Component.translatable("swthb.tpa.no_request"));
            return 0;
        }

        ServerPlayer requester = player.level().getServer().getPlayerList().getPlayer(req.requesterId());
        if (requester != null) {
            Component playerName = player.getDisplayName().copy().withStyle(ChatFormatting.AQUA);
            requester.sendSystemMessage(
                    Component.translatable("swthb.tpa.denied_sender", playerName)
                            .withStyle(ChatFormatting.RED)
            );
        }

        Component requesterName = requester != null
                ? requester.getDisplayName().copy().withStyle(ChatFormatting.AQUA)
                : Component.translatable("swthb.tpa.unknown_player");
        player.sendSystemMessage(
                Component.translatable("swthb.tpa.denied_receiver", requesterName)
                        .withStyle(ChatFormatting.RED)
        );

        return 1;
    }

    private static void cleanup() {
        long now = System.currentTimeMillis();
        PENDING.entrySet().removeIf(entry -> (now - entry.getValue().timestamp()) > TIMEOUT_MS);
    }
}
