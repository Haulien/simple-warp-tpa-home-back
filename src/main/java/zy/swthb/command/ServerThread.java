package zy.swthb.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 确保命令在服务器主线程上执行。
 * <p>
 * 原版会在主线程上处理聊天栏输入和点击事件中的命令，但部分模组（如 Carpet FGA 的点击事件处理）
 * 会直接在网络（Netty）线程上执行命令。此时传送会在网络线程上修改实体，触发 C2ME 的
 * "Async entity unload" 检测，导致跨维度传送卡在"加载地形中"（issue #1）；
 * 同时 /sethome、/setwarp 等命令会在网络线程上修改并保存 data.json，可能与主线程同时写入。
 * <p>
 * 用 {@link #run} 包装命令：已在主线程上时直接执行（行为与原来完全一致）；
 * 不在主线程上时，把整个命令转交给主线程执行。
 *
 * Makes sure commands run on the server thread. Some mods run click-event commands on the
 * network thread; teleporting or saving data from there is unsafe (see issue #1).
 */
public final class ServerThread {

    private static final Logger LOGGER = LoggerFactory.getLogger("simple-warp-tpa-home-back");

    private ServerThread() {}

    public static Command<CommandSourceStack> run(Command<CommandSourceStack> command) {
        return ctx -> {
            CommandSourceStack source = ctx.getSource();
            MinecraftServer server = source.getServer();

            // 已在主线程：直接执行，返回值与异常处理均与原版相同
            if (server.isSameThread()) {
                return command.run(ctx);
            }

            // 不在主线程：交给主线程执行。返回值此时无法得知，按成功处理
            // （/execute store 和函数只会在主线程上运行，不会走到这里）
            server.execute(() -> {
                // 排队期间玩家可能已经下线
                if (source.getEntity() instanceof ServerPlayer player && player.hasDisconnected()) {
                    return;
                }
                try {
                    command.run(ctx);
                } catch (CommandSyntaxException e) {
                    source.sendFailure(ComponentUtils.fromMessage(e.getRawMessage()));
                } catch (Exception e) {
                    LOGGER.error("Error running command '{}' moved to the server thread", ctx.getInput(), e);
                    source.sendFailure(Component.translatable("command.failed").withStyle(ChatFormatting.RED));
                }
            });
            return Command.SINGLE_SUCCESS;
        };
    }
}
