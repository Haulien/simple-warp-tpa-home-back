package zy.swthb.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import zy.swthb.lang.ModLang;

/**
 * Translates this mod's messages shown in the server console (command feedback, op broadcasts)
 * into the server's language.
 */
@Mixin(MinecraftServer.class)
public abstract class ConsoleMessageLangMixin {

    @ModifyVariable(method = "sendSystemMessage(Lnet/minecraft/network/chat/Component;)V",
            at = @At("HEAD"), argsOnly = true)
    private Component swthb$localiseForConsole(Component message) {
        return ModLang.forConsole(message);
    }
}
