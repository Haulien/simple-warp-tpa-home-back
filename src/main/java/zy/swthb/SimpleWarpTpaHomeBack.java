package zy.swthb;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import zy.swthb.command.BackCommand;
import zy.swthb.command.BackHandler;
import zy.swthb.command.HomeCommand;
import zy.swthb.command.SwthbConfigCommand;
import zy.swthb.command.TeleportHandler;
import zy.swthb.command.TpaCommand;
import zy.swthb.command.WarpCommand;
import zy.swthb.config.ModConfig;
import zy.swthb.lang.ModLang;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

public class SimpleWarpTpaHomeBack implements ModInitializer {
	public static final String MOD_ID = "simple-warp-tpa-home-back";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		// 注册所有命令
		CommandRegistrationCallback.EVENT.register((dispatcher, _, _) -> {
			TpaCommand.register(dispatcher);
			HomeCommand.register(dispatcher);
			SwthbConfigCommand.register(dispatcher);
			WarpCommand.register(dispatcher);
			BackCommand.register(dispatcher);
		});

		// 服务器启动时加载当前存档的数据
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			ModConfig.load(server);
			LOGGER.info(ModLang.server("swthb.log.world_data_loaded"));
		});

		// 注册 BackHandler 自动清理事件（玩家下线、服务器关闭时）
		BackHandler.registerEvents();

		// 每 tick 处理传送倒计时
		ServerTickEvents.START_SERVER_TICK.register(server -> TeleportHandler.tick());

		// 每 5 分钟清理超时的 TPA 请求（请求超时时长为 1 分钟）
		TpaCommand.registerCleanupEvent();

		// 服务器关闭时保存数据并清除旧状态（切换存档时确保互不影响）
		ServerLifecycleEvents.SERVER_STOPPING.register(_ -> {
			ModConfig.getInstance().save();
			ModConfig.reset();
			LOGGER.info(ModLang.server("swthb.log.world_data_saved"));
		});

		LOGGER.info(ModLang.server("swthb.log.initialized"));
	}
}
