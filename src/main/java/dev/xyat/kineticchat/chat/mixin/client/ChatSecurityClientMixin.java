package dev.xyat.kineticchat.chat.mixin.client;

import dev.xyat.kineticchat.chat.config.ChatConfig;
import net.minecraft.client.GuiMessageTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GuiMessageTag.class)
public class ChatSecurityClientMixin {
    @Inject(method = "systemSinglePlayer", at = @At("HEAD"), cancellable = true)
    private static void kineticchat$hideSystemWarning(CallbackInfoReturnable<GuiMessageTag> cir) {
        if (ChatConfig.stripChatSignatures) {
            cir.setReturnValue(null);
        }
    }

    @Inject(method = "chatNotSecure", at = @At("HEAD"), cancellable = true)
    private static void kineticchat$hideNotSecureWarning(CallbackInfoReturnable<GuiMessageTag> cir) {
        if (ChatConfig.stripChatSignatures) {
            cir.setReturnValue(null);
        }
    }
}
