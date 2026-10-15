package zy.swthb.command;

import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import zy.swthb.config.ModConfig;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 传送倒计时管理器
 * 为 /tpay、/home、/warp 提供可配置的延迟传送，默认 0 秒（无延迟）。
 * <p>
 * - 倒计时期间玩家移动则取消传送
 * - 倒计时显示在动作栏
 * - 可在 data.json 中通过 teleportDelay 修改秒数
 */
public class TeleportHandler {

    private static final Map<UUID, TeleportTask> PENDING = new ConcurrentHashMap<>();

    private record TeleportTask(
            ServerPlayer player,
            Vec3 startPos,
            int totalTicks,
            int ticksRemaining,
            TeleportTransition transition,
            Runnable onSuccess,
            Runnable onCancel
    ) {
        boolean hasMoved() {
            return player.position().distanceToSqr(startPos) > 0.0625; // 0.25 blocks ^2
        }
    }

    /**
     * 开始一个延迟传送
     *
     * @param player     待传送的玩家
     * @param transition 传送参数
     * @param onSuccess  传送成功后的回调（如发送提示消息）
     * @param onCancel   传送被取消时的回调
     */
    public static void startTeleport(ServerPlayer player,
                                     TeleportTransition transition,
                                     Runnable onSuccess,
                                     Runnable onCancel) {
        // 取消该玩家已有的倒计时
        cancelTeleport(player, false);

        int delay = ModConfig.getInstance().getTeleportDelay();
        if (delay <= 0) {
            // 延迟为 0，直接传送
            player.teleport(transition);
            // 在目标位置播放到达音效和粒子
            playArrivalEffects(
                    transition.newLevel(),
                    transition.position().x(),
                    transition.position().y(),
                    transition.position().z()
            );
            if (onSuccess != null) onSuccess.run();
            return;
        }

        int totalTicks = delay * 20;
        PENDING.put(player.getUUID(), new TeleportTask(
                player, player.position(), totalTicks, totalTicks,
                transition, onSuccess, onCancel
        ));

        // 发送初始倒计时提示（动作栏）
        player.sendOverlayMessage(
                Component.translatable("swthb.teleport.countdown", String.valueOf(delay))
                        .withStyle(ChatFormatting.YELLOW)
        );
    }

    /**
     * 取消玩家的传送倒计时
     *
     * @param player    目标玩家
     * @param notify    是否发送取消提示
     */
    public static void cancelTeleport(ServerPlayer player, boolean notify) {
        TeleportTask task = PENDING.remove(player.getUUID());
        if (task != null && notify) {
            if (task.onCancel() != null) task.onCancel().run();
        }
    }

    /**
     * 在指定位置播放末影珍珠传送音效和粒子效果（用于传送后）
     *
     * @param level 目标维度
     * @param x     目标 X 坐标
     * @param y     目标 Y 坐标
     * @param z     目标 Z 坐标
     */
    private static void playArrivalEffects(ServerLevel level, double x, double y, double z) {
        if (!ModConfig.getInstance().isTeleportEffectsEnabled()) return;

        // 播放末影珍珠/末影人瞬移音效
        level.playSound(
                null, x, y, z,
                SoundEvents.ENDERMAN_TELEPORT,
                SoundSource.PLAYERS,
                1.0f, 1.0f
        );

        // 播放传送粒子（末影人风格的 PORTAL 粒子）
        level.sendParticles(
                ParticleTypes.PORTAL,
                x, y + 0.5, z,
                30,       // 粒子数量
                0.5, 1.0, 0.5,  // 扩散范围
                0.1       // 速度
        );
    }

    /**
     * 每个服务器 tick 调用一次，处理倒计时逻辑
     */
    public static void tick() {
        if (PENDING.isEmpty()) return;

        Iterator<Map.Entry<UUID, TeleportTask>> it = PENDING.entrySet().iterator();
        while (it.hasNext()) {
            TeleportTask task = it.next().getValue();

            // 玩家断开连接 → 移除任务
            if (task.player().hasDisconnected()) {
                it.remove();
                continue;
            }

            // 玩家移动 → 取消传送
            if (task.hasMoved()) {
                it.remove();
                task.player().sendOverlayMessage(Component.literal("")); // 清空动作栏
                task.player().sendSystemMessage(
                        Component.translatable("swthb.teleport.cancelled_move")
                );
                if (task.onCancel() != null) task.onCancel().run();
                continue;
            }

            int remaining = task.ticksRemaining() - 1;

            if (remaining <= 0) {
                // 倒计时结束，执行传送
                it.remove();
                task.player().teleport(task.transition());
                // 在目标位置播放到达音效和粒子
                playArrivalEffects(
                        task.transition().newLevel(),
                        task.transition().position().x(),
                        task.transition().position().y(),
                        task.transition().position().z()
                );
                task.player().sendOverlayMessage(Component.literal("")); // 清空动作栏
                if (task.onSuccess() != null) task.onSuccess().run();
            } else {
                // 每秒更新一次动作栏倒计时
                if (remaining % 20 == 0) {
                    int seconds = remaining / 20;
                    task.player().sendOverlayMessage(
                            Component.translatable("swthb.teleport.countdown", String.valueOf(seconds))
                                    .withStyle(ChatFormatting.YELLOW)
                    );
                }
                // 更新剩余 tick
                PENDING.put(task.player().getUUID(), new TeleportTask(
                        task.player(), task.startPos(), task.totalTicks(),
                        remaining, task.transition(), task.onSuccess(), task.onCancel()
                ));
            }
        }
    }
}
