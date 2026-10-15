package zy.swthb.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import zy.swthb.lang.ModLang;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 存档配置管理器
 * 统一管理模组设置和所有传送点数据（家、Warp），使用 JSON 文件持久化。
 * <p>
 * 每个存档独立存储：<存档目录>/data/simple-warp-tpa-home-back/data.json
 * 专用服务器：world/data/simple-warp-tpa-home-back/data.json
 * 单人游戏：saves/<世界名>/data/simple-warp-tpa-home-back/data.json
 */
public class ModConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Logger LOGGER = LoggerFactory.getLogger("simple-warp-tpa-home-back-config");

    // ---------- 单例 ----------

    private static ModConfig instance;

    public static ModConfig getInstance() {
        if (instance == null) {
            instance = new ModConfig();
        }
        return instance;
    }

    // 当前存档的服务器引用（用于获取存档路径）
    private MinecraftServer server;

    // 当前数据文件路径
    private Path dataFile;

    // ---------- 设置 ----------

    private int maxHomes = 5;
    private int maxWarps = 5;

    /** 传送倒计时（秒），默认 0（无延迟） */
    private int teleportDelay = 0;

    /** 传送时播放末影人风格的音效和粒子效果，默认关闭 */
    private boolean teleportEffectsEnabled = false;

    /** /back 功能开关，默认关闭 */
    private boolean backEnabled = false;

    // ---------- 数据 ----------

    private Map<String, List<HomeEntry>> homes = new HashMap<>();
    private Map<String, WarpEntry> warps = new HashMap<>();

    /** Player UUID → name of their default home (used by /home with no name) */
    private Map<String, String> defaultHomes = new HashMap<>();

    public MinecraftServer getServer() {
        return server;
    }

    // ---------- 内部数据类 ----------

    public static class HomeEntry {
        private final String name;
        private final String world;
        private final double x, y, z;
        private final float yaw, pitch;

        public HomeEntry(String name, String world, double x, double y, double z, float yaw, float pitch) {
            this.name = name;
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
        }

        public String getName() { return name; }
        public String getWorld() { return world; }
        public double getX() { return x; }
        public double getY() { return y; }
        public double getZ() { return z; }
        public float getYaw() { return yaw; }
        public float getPitch() { return pitch; }
    }

    public static class WarpEntry {
        private final String world;
        private final double x, y, z;
        private final float yaw, pitch;

        public WarpEntry(String world, double x, double y, double z, float yaw, float pitch) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
        }

        public String getWorld() { return world; }
        public double getX() { return x; }
        public double getY() { return y; }
        public double getZ() { return z; }
        public float getYaw() { return yaw; }
        public float getPitch() { return pitch; }
    }

    // ---------- 生命周期 ----------

    private ModConfig() {}

    /**
     * 从当前存档的数据目录加载配置文件。
     *
     * @param server 当前 Minecraft 服务器实例
     */
    public static void load(MinecraftServer server) {
        ModConfig config = getInstance();
        config.server = server;
        config.dataFile = server.getWorldPath(LevelResource.DATA)
                .resolve("simple-warp-tpa-home-back")
                .resolve("data.json");

        if (!Files.exists(config.dataFile)) {
            LOGGER.info(ModLang.server("swthb.log.data_missing"));
            config.save();
            return;
        }

        try (BufferedReader reader = Files.newBufferedReader(config.dataFile)) {
            JsonData data = GSON.fromJson(reader, JsonData.class);
            if (data != null) {
                config.maxHomes = data.maxHomes;
                config.maxWarps = data.maxWarps;
                config.teleportDelay = data.teleportDelay;
                config.teleportEffectsEnabled = data.teleportEffectsEnabled;
                config.backEnabled = data.backEnabled;
                config.homes = data.homes != null ? data.homes : new HashMap<>();
                config.warps = data.warps != null ? data.warps : new HashMap<>();
                config.defaultHomes = data.defaultHomes != null ? data.defaultHomes : new HashMap<>();
                LOGGER.info(ModLang.server("swthb.log.data_loaded_from", config.dataFile));
            }
        } catch (Exception e) {
            LOGGER.error(ModLang.server("swthb.log.load_failed", e.getMessage()));
        }
    }

    /**
     * 将当前配置保存到当前存档的数据目录。
     */
    public void save() {
        if (dataFile == null) {
            LOGGER.warn(ModLang.server("swthb.log.no_data_file"));
            return;
        }
        try {
            Files.createDirectories(dataFile.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(dataFile)) {
                JsonData data = new JsonData();
                data.maxHomes = this.maxHomes;
                data.maxWarps = this.maxWarps;
                data.teleportDelay = this.teleportDelay;
                data.teleportEffectsEnabled = this.teleportEffectsEnabled;
                data.backEnabled = this.backEnabled;
                data.homes = this.homes;
                data.warps = this.warps;
                data.defaultHomes = this.defaultHomes;
                GSON.toJson(data, writer);
                writer.flush();
            }
        } catch (Exception e) {
            LOGGER.error(ModLang.server("swthb.log.save_failed", e.getMessage()));
        }
    }

    /**
     * 重置单例，用于切换存档时清除旧状态。
     */
    public static void reset() {
        instance = null;
    }

    private static class JsonData {
        int maxHomes = 5;
        int maxWarps = 5;
        int teleportDelay = 0;
        boolean teleportEffectsEnabled = false;
        boolean backEnabled = false;
        Map<String, List<HomeEntry>> homes = new HashMap<>();
        Map<String, WarpEntry> warps = new HashMap<>();
        Map<String, String> defaultHomes = new HashMap<>();
    }

    // ---------- Homes 方法 ----------

    public int getMaxHomes() {
        return maxHomes;
    }

    public void setMaxHomes(int maxHomes) {
        this.maxHomes = maxHomes;
        save();
    }

    public List<HomeEntry> getHomes(UUID playerUuid) {
        List<HomeEntry> list = homes.get(playerUuid.toString());
        return list != null ? Collections.unmodifiableList(list) : List.of();
    }

    public HomeEntry getHome(UUID playerUuid, String name) {
        List<HomeEntry> list = homes.get(playerUuid.toString());
        if (list == null) return null;
        for (HomeEntry h : list) {
            if (h.getName().equals(name)) return h;
        }
        return null;
    }

    public boolean isAtMaxHomes(UUID playerUuid) {
        List<HomeEntry> list = homes.get(playerUuid.toString());
        return list != null && list.size() >= maxHomes;
    }

    public boolean homeExists(UUID playerUuid, String name) {
        List<HomeEntry> list = homes.get(playerUuid.toString());
        if (list == null) return false;
        return list.stream().anyMatch(h -> h.getName().equals(name));
    }

    public int getHomeCount(UUID playerUuid) {
        List<HomeEntry> list = homes.get(playerUuid.toString());
        return list != null ? list.size() : 0;
    }

    public void setHome(UUID playerUuid, HomeEntry entry) {
        String key = playerUuid.toString();
        List<HomeEntry> list = homes.computeIfAbsent(key, _ -> new ArrayList<>());

        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).getName().equals(entry.getName())) {
                list.set(i, entry);
                save();
                return;
            }
        }

        list.add(entry);
        // A player's first home becomes their default automatically
        defaultHomes.putIfAbsent(key, entry.getName());
        save();
    }

    public boolean removeHome(UUID playerUuid, String name) {
        String key = playerUuid.toString();
        List<HomeEntry> list = homes.get(key);
        if (list == null) return false;

        boolean removed = list.removeIf(h -> h.getName().equals(name));
        if (removed) {
            if (name.equals(defaultHomes.get(key))) {
                defaultHomes.remove(key);
            }
            if (list.isEmpty()) {
                homes.remove(key);
            }
            save();
        }
        return removed;
    }

    // ---------- Default home ----------

    /** Name of the player's default home, or null if none is set (or it no longer exists). */
    public String getDefaultHome(UUID playerUuid) {
        String name = defaultHomes.get(playerUuid.toString());
        return name != null && homeExists(playerUuid, name) ? name : null;
    }

    public void setDefaultHome(UUID playerUuid, String name) {
        defaultHomes.put(playerUuid.toString(), name);
        save();
    }

    // ---------- 传送倒计时 ----------

    public int getTeleportDelay() {
        return teleportDelay;
    }

    public void setTeleportDelay(int teleportDelay) {
        this.teleportDelay = Math.clamp(teleportDelay, 0, 120);
        save();
    }

    // ---------- 传送粒子效果 ----------

    public boolean isTeleportEffectsEnabled() {
        return teleportEffectsEnabled;
    }

    public void setTeleportEffectsEnabled(boolean teleportEffectsEnabled) {
        this.teleportEffectsEnabled = teleportEffectsEnabled;
        save();
    }

    // ---------- /back 功能开关 ----------

    public boolean isBackEnabled() {
        return backEnabled;
    }

    public void setBackEnabled(boolean backEnabled) {
        this.backEnabled = backEnabled;
        save();
    }

    // ---------- Warps（公共传送点） ----------

    public int getMaxWarps() {
        return maxWarps;
    }

    public void setMaxWarps(int maxWarps) {
        this.maxWarps = maxWarps;
        save();
    }

    public Map<String, WarpEntry> getWarps() {
        return Collections.unmodifiableMap(warps);
    }

    public WarpEntry getWarp(String name) {
        return warps.get(name);
    }

    public void setWarp(String name, WarpEntry entry) {
        warps.put(name, entry);
        save();
    }

    public boolean removeWarp(String name) {
        if (warps.containsKey(name)) {
            warps.remove(name);
            save();
            return true;
        }
        return false;
    }
}
