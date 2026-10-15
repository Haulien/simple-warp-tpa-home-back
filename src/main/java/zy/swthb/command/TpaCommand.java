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
            source.sendFailure(Component.translatableWithFallback("swthb.tpa.player_not_found", "玩家未找到"));
            return 0;
        }

        if (player.getUUID().equals(target.getUUID())) {
            source.sendFailure(Component.translatableWithFallback("swthb.tpa.self", "不能向自己发送传送请求"));
            return 0;
        }

        PENDING.put(target.getUUID(), new PendingRequest(player.getUUID(), System.currentTimeMillis()));

        // 通知发起方
        Component senderName = target.getDisplayName().copy().withStyle(ChatFormatting.AQUA);
        player.sendSystemMessage(
                Component.translatableWithFallback("swthb.tpa.sender_msg",
                        "%s 的传送请求已发送，等待回应...", senderName)
                        .withStyle(ChatFormatting.GREEN)
        );

        // 通知目标方，附带可点击的接受/拒绝按钮
        Component requesterName = player.getDisplayName().copy().withStyle(ChatFormatting.AQUA);

        Component acceptBtn = Component.translatableWithFallback("swthb.tpa.accept_btn", " [接受] ")
                .withStyle(style -> style
                        .withColor(ChatFormatting.GREEN)
                        .withBold(true)
                        .withClickEvent(new ClickEvent.RunCommand("/tpay"))
                        .withHoverEvent(new HoverEvent.ShowText(
                                Component.translatableWithFallback("swthb.tpa.accept_hover", "点击接受传送"))));

        Component denyBtn = Component.translatableWithFallback("swthb.tpa.deny_btn", " [拒绝] ")
                .withStyle(style -> style
                        .withColor(ChatFormatting.RED)
                        .withBold(true)
                        .withClickEvent(new ClickEvent.RunCommand("/tpan"))
                        .withHoverEvent(new HoverEvent.ShowText(
                                Component.translatableWithFallback("swthb.tpa.deny_hover", "点击拒绝传送"))));

        target.sendSystemMessage(
                Component.translatableWithFallback("swthb.tpa.receiver_msg",
                        "%s 请求传送到你的位置 %s %s",
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
            source.sendFailure(Component.translatableWithFallback("swthb.tpa.no_request", "没有待处理的传送请求"));
            return 0;
        }

        ServerPlayer requester = player.level().getServer().getPlayerList().getPlayer(req.requesterId());
        if (requester == null) {
            source.sendFailure(Component.translatableWithFallback("swthb.tpa.requester_offline", "请求者已离线"));
            return 0;
        }

        // 通知接受方
        Component requesterName = requester.getDisplayName().copy().withStyle(ChatFormatting.AQUA);
        player.sendSystemMessage(
                Component.translatableWithFallback("swthb.tpa.accepted_receiver",
                        "已接受 %s 的传送请求", requesterName)
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
                        Component.translatableWithFallback("swthb.tpa.accepted_sender",
                                "%s 已接受你的传送请求", playerName)
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
            source.sendFailure(Component.translatableWithFallback("swthb.tpa.no_request", "没有待处理的传送请求"));
            return 0;
        }

        ServerPlayer requester = player.level().getServer().getPlayerList().getPlayer(req.requesterId());
        if (requester != null) {
            Component playerName = player.getDisplayName().copy().withStyle(ChatFormatting.AQUA);
            requester.sendSystemMessage(
                    Component.translatableWithFallback("swthb.tpa.denied_sender",
                            "%s 已拒绝你的传送请求", playerName)
                            .withStyle(ChatFormatting.RED)
            );
        }

        Component requesterName = requester != null
                ? requester.getDisplayName().copy().withStyle(ChatFormatting.AQUA)
                : Component.literal("该玩家");
        player.sendSystemMessage(
                Component.translatableWithFallback("swthb.tpa.denied_receiver",
                        "已拒绝 %s 的传送请求", requesterName)
                        .withStyle(ChatFormatting.RED)
        );

        return 1;
    }

    private static void cleanup() {
        long now = System.currentTimeMillis();
        PENDING.entrySet().removeIf(entry -> (now - entry.getValue().timestamp()) > TIMEOUT_MS);
    }
}
