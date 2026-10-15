package zy.swthb.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import zy.swthb.lang.ModLang;

/**
 * Translates this mod's messages into the language the player's client reports, so players
 * without the mod installed see their own language instead of a hard-coded default.
 * <p>
 * Chat, action-bar, command feedback and op broadcasts all pass through this method.
 */
@Mixin(ServerPlayer.class)
public abstract class PlayerMessageLangMixin {

    @ModifyVariable(method = "sendSystemMessage(Lnet/minecraft/network/chat/Component;Z)V",
            at = @At("HEAD"), argsOnly = true)
    private Component swthb$localiseForClient(Component message) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        return ModLang.forPlayer(message, self.clientInformation().language());
    }
}
