package zy.swthb.command;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

/**
 * 维度显示名工具类 — 将维度标识符（如 minecraft:overworld）转为本地化的显示名称。
 * <p>
 * 供 HomeCommand 和 WarpCommand 共用，避免重复代码。
 */
public final class DimensionDisplay {

    private DimensionDisplay() {}

    public static Component of(String worldId) {
        return switch (worldId) {
            case "minecraft:overworld" ->
                    Component.translatable("swthb.dim.overworld")
                            .withStyle(ChatFormatting.YELLOW);
            case "minecraft:the_nether" ->
                    Component.translatable("swthb.dim.nether")
                            .withStyle(ChatFormatting.YELLOW);
            case "minecraft:the_end" ->
                    Component.translatable("swthb.dim.end")
                            .withStyle(ChatFormatting.YELLOW);
            default -> {
                String[] parts = worldId.split(":");
                yield Component.literal(parts.length > 1 ? parts[1] : worldId)
                        .withStyle(ChatFormatting.YELLOW);
            }
        };
    }
}
