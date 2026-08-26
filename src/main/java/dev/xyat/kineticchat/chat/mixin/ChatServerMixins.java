package dev.xyat.kineticchat.chat.mixin;

import dev.xyat.kineticchat.KineticChat;
import dev.xyat.kineticchat.chat.config.ChatConfig;
import dev.xyat.kineticchat.chat.data.ChatHistoryServerManager;
import dev.xyat.kineticchat.chat.network.ChatSyncCodec;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.util.StringUtil;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

public class ChatServerMixins {

    @Mixin(ServerGamePacketListenerImpl.class)
    public static abstract class ServerPacketTweaks {
        @Shadow public ServerPlayer player;
        @Shadow @Final private MinecraftServer server;

        @Unique private static final int kineticchat$MAX_SYNC_PACKETS_PER_SECOND = 64;
        @Unique private static final int kineticchat$MAX_SYNC_BYTES_PER_SECOND = 512 * 1024;
        @Unique private long kineticchat$syncWindowStart;
        @Unique private int kineticchat$syncPacketsInWindow;
        @Unique private int kineticchat$syncBytesInWindow;
        @Unique private long kineticchat$lastRejectWarning;

        @ModifyConstant(method = "handleChat", constant = @Constant(intValue = 256))
        private int kineticchat$modifyChatLimit(int original) {
            return ChatConfig.maxChatLength;
        }

        @Inject(method = "handleCustomPayload", at = @At("HEAD"), cancellable = true)
        private void kineticchat$onServerCustomPayload(ServerboundCustomPayloadPacket packet, CallbackInfo ci) {
            ResourceLocation id = packet.getIdentifier();
            if (!id.getNamespace().equals("kineticchat") || !id.getPath().equals("chat_sync")) {
                return;
            }

            ci.cancel();
            FriendlyByteBuf buf = packet.getData();
            int packetBytes = buf.readableBytes();
            if (packetBytes < 1 || packetBytes > ChatSyncCodec.MAX_SERVERBOUND_PACKET_BYTES) {
                kineticchat$warnRejected("invalid payload length " + packetBytes);
                return;
            }

            int type = buf.readUnsignedByte();
            // The client only sends individual chat/input records. Type 10 is
            // server-to-client only and must never be decompressed here.
            if (!ChatSyncCodec.isServerboundType(type)) {
                kineticchat$warnRejected("invalid serverbound type " + type);
                return;
            }
            if (!kineticchat$acquireSyncBudget(packetBytes)) {
                kineticchat$warnRejected("rate limit exceeded");
                return;
            }

            // Copy only after both the type and hard packet bound are known.
            byte[] payloadData = new byte[buf.readableBytes()];
            buf.readBytes(payloadData);
            this.server.execute(() -> {
                try {
                    if (type == ChatSyncCodec.TYPE_CHAT_LINE) {
                        ChatSyncCodec.ChatLine line =
                                ChatSyncCodec.decodeChatLine(payloadData, ChatConfig.maxChatLength);
                        ChatHistoryServerManager.addChatLine(
                                server,
                                this.player.getUUID(),
                                line.json(),
                                line.timestamp(),
                                line.senderUuid()
                        );
                    } else {
                        String input = ChatSyncCodec.decodeInputLine(payloadData, ChatConfig.maxChatLength);
                        ChatHistoryServerManager.addInputLine(server, this.player.getUUID(), input);
                    }
                } catch (IOException | RuntimeException exception) {
                    kineticchat$warnRejected("malformed type " + type + " payload");
                }
            });
        }

        @Unique
        private boolean kineticchat$acquireSyncBudget(int packetBytes) {
            long now = System.nanoTime();
            if (kineticchat$syncWindowStart == 0L
                    || now - kineticchat$syncWindowStart >= TimeUnit.SECONDS.toNanos(1)) {
                kineticchat$syncWindowStart = now;
                kineticchat$syncPacketsInWindow = 0;
                kineticchat$syncBytesInWindow = 0;
            }
            if (kineticchat$syncPacketsInWindow >= kineticchat$MAX_SYNC_PACKETS_PER_SECOND
                    || kineticchat$syncBytesInWindow > kineticchat$MAX_SYNC_BYTES_PER_SECOND - packetBytes) {
                return false;
            }
            kineticchat$syncPacketsInWindow++;
            kineticchat$syncBytesInWindow += packetBytes;
            return true;
        }

        @Unique
        private void kineticchat$warnRejected(String reason) {
            long now = System.nanoTime();
            if (kineticchat$lastRejectWarning == 0L
                    || now - kineticchat$lastRejectWarning >= TimeUnit.SECONDS.toNanos(10)) {
                kineticchat$lastRejectWarning = now;
                KineticChat.LOGGER.warn("Rejected chat sync payload from {}: {}",
                        this.player.getGameProfile().getName(), reason);
            }
        }
    }

    @Mixin(PlayerList.class)
    public static class PlayerListTweaks {
        @Inject(method = "placeNewPlayer", at = @At("RETURN"))
        private void kineticchat$onPlayerJoin(net.minecraft.network.Connection connection, ServerPlayer player, CallbackInfo ci) {
            ChatHistoryServerManager.syncToPlayer(player);
        }
    }

    @Mixin({ServerboundChatPacket.class, ServerboundChatCommandPacket.class})
    public static class PacketLengthTweaks {
        @ModifyConstant(method = "<init>(Lnet/minecraft/network/FriendlyByteBuf;)V", constant = @Constant(intValue = 256), remap = false)
        private static int kineticchat$increasePacketReadLimit(int original) {
            return ChatConfig.maxChatLength;
        }
    }

    @Mixin(StringUtil.class)
    public static class StringUtilTweaks {
        @ModifyConstant(method = "trimChatMessage", constant = @Constant(intValue = 256))
        private static int kineticchat$increaseTrimLimit(int original) {
            return ChatConfig.maxChatLength;
        }
    }
}
