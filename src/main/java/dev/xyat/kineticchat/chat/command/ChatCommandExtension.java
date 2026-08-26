package dev.xyat.kineticchat.chat.command;

import dev.xyat.kineticchat.KineticChat;
import dev.xyat.kineticchat.chat.config.ChatConfig;
import dev.xyat.kineticcore.command.KTCommandApi;
import dev.xyat.kineticcore.command.KTCommandExtension;
import net.minecraft.commands.CommandSourceStack;

public final class ChatCommandExtension implements KTCommandExtension {
    private ChatCommandExtension() {
    }

    public static void install() {
        KTCommandApi.register(KineticChat.MODID, new ChatCommandExtension());
    }

    @Override
    public void reload(CommandSourceStack source) {
        ChatConfig.load();
    }
}
