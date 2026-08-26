package dev.xyat.kineticchat.chat.data;

import dev.xyat.kineticchat.KineticChat;
import dev.xyat.kineticchat.chat.config.ChatConfig;
import dev.xyat.kineticchat.chat.network.ChatSyncCodec;
import io.netty.buffer.Unpooled;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;

public class ChatHistoryServerManager extends SavedData {
    private static final String DATA_NAME = "kineticchat_chat_history";
    private static final ResourceLocation SYNC_CHANNEL = new ResourceLocation("kineticchat", "chat_sync");
    private final Map<UUID, PlayerChatData> playerData = new HashMap<>();

    public static ChatHistoryServerManager get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                ChatHistoryServerManager::load,
                ChatHistoryServerManager::new,
                DATA_NAME
        );
    }

    public static void addChatLine(
            MinecraftServer server,
            UUID uuid,
            String json,
            long timestamp,
            @Nullable UUID senderUuid
    ) {
        int maxChars = ChatSyncCodec.wireStringLimit(ChatConfig.maxChatLength);
        if (json == null || json.length() > maxChars) return;

        ChatHistoryServerManager manager = get(server);
        PlayerChatData data = manager.playerData.computeIfAbsent(uuid, ignored -> new PlayerChatData());
        ChatEntry entry = new ChatEntry(json, timestamp, senderUuid);
        int wireBytes = measureWireBytes(entry);
        if (wireBytes < 0) return;
        data.chatLines.add(entry);
        data.chatLineCount++;
        data.chatWireBytes += wireBytes;
        data.lastSeen = System.currentTimeMillis();
        trimChatLines(data, ChatSyncCodec.clampHistoryLines(ChatConfig.maxChatHistoryLines));
        manager.setDirty();
    }

    public static void addInputLine(MinecraftServer server, UUID uuid, String input) {
        int maxChars = ChatSyncCodec.wireStringLimit(ChatConfig.maxChatLength);
        if (input == null || input.length() > maxChars) return;

        ChatHistoryServerManager manager = get(server);
        PlayerChatData data = manager.playerData.computeIfAbsent(uuid, ignored -> new PlayerChatData());
        data.inputLines.add(input);
        data.lastSeen = System.currentTimeMillis();
        trimOldest(data.inputLines, ChatSyncCodec.MAX_INPUT_HISTORY_LINES);
        manager.setDirty();
    }

    public static void syncToPlayer(ServerPlayer player) {
        ChatHistoryServerManager manager = get(Objects.requireNonNull(player.getServer()));
        PlayerChatData data = manager.playerData.get(player.getUUID());
        if (data == null) return;

        int configuredMaxChars = ChatSyncCodec.clampChatLength(ChatConfig.maxChatLength);
        int maxEntries = ChatSyncCodec.clampHistoryLines(ChatConfig.maxChatHistoryLines);
        if (trimChatLines(data, maxEntries)) manager.setDirty();
        if (!data.chatLines.isEmpty()) {
            try {
                Iterator<ChatEntry> newestFirst = data.chatLines.descendingIterator();
                ChatSyncCodec.EncodedHistory encoded =
                        ChatSyncCodec.encodeCompressedHistory(newestFirst, maxEntries, configuredMaxChars);
                if (encoded.entryCount() > 0) {
                    FriendlyByteBuf payload = new FriendlyByteBuf(Unpooled.buffer());
                    payload.writeByte(ChatSyncCodec.TYPE_CHAT_HISTORY);
                    payload.writeBytes(encoded.compressed());
                    player.connection.send(new ClientboundCustomPayloadPacket(SYNC_CHANNEL, payload));

                    int retained = Math.min(data.chatLines.size(), maxEntries);
                    if (encoded.entryCount() < retained) {
                        KineticChat.LOGGER.debug(
                                "Chat history sync for {} retained newest {}/{} entries ({} raw bytes, {} compressed bytes)",
                                player.getGameProfile().getName(), encoded.entryCount(), retained,
                                encoded.uncompressedBytes(), encoded.compressed().length);
                    }
                }
            } catch (IOException | RuntimeException exception) {
                KineticChat.LOGGER.error("Failed to build bounded chat history sync", exception);
            }
        }

        for (String input : data.inputLines) {
            try {
                byte[] encodedInput = ChatSyncCodec.encodeInputLine(input, configuredMaxChars);
                FriendlyByteBuf payload = new FriendlyByteBuf(Unpooled.buffer());
                payload.writeByte(ChatSyncCodec.TYPE_INPUT_HISTORY);
                payload.writeBytes(encodedInput);
                player.connection.send(new ClientboundCustomPayloadPacket(SYNC_CHANNEL, payload));
            } catch (IOException | RuntimeException exception) {
                KineticChat.LOGGER.debug("Skipping invalid persisted chat input during sync", exception);
            }
        }
    }

    private static <T> void trimOldest(ConcurrentLinkedDeque<T> values, int maximum) {
        int excess = values.size() - maximum;
        while (excess-- > 0) {
            values.pollFirst();
        }
    }

    private static boolean trimChatLines(PlayerChatData data, int maximum) {
        boolean changed = false;
        while (data.chatLineCount > maximum
                || data.chatWireBytes > ChatSyncCodec.MAX_STORED_HISTORY_BYTES_PER_PLAYER) {
            ChatEntry removed = data.chatLines.pollFirst();
            if (removed == null) break;
            data.chatLineCount = Math.max(0, data.chatLineCount - 1);
            int removedBytes = measureWireBytes(removed);
            data.chatWireBytes = removedBytes < 0
                    ? recalculateWireBytes(data.chatLines)
                    : Math.max(0, data.chatWireBytes - removedBytes);
            changed = true;
        }
        return changed;
    }

    private static int measureWireBytes(ChatEntry entry) {
        try {
            return ChatSyncCodec.historyEntryWireBytes(entry);
        } catch (IOException | RuntimeException ignored) {
            return -1;
        }
    }

    private static int recalculateWireBytes(ConcurrentLinkedDeque<ChatEntry> entries) {
        long total = 0L;
        for (ChatEntry entry : entries) {
            int size = measureWireBytes(entry);
            if (size < 0) continue;
            total += size;
            if (total >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
        }
        return (int) total;
    }

    public static ChatHistoryServerManager load(CompoundTag tag) {
        ChatHistoryServerManager manager = new ChatHistoryServerManager();
        CompoundTag playersTag = tag.getCompound("Players");
        long now = System.currentTimeMillis();
        long expireTime = 30L * 24 * 60 * 60 * 1000;
        int maxChars = ChatSyncCodec.wireStringLimit(ChatConfig.maxChatLength);
        int maxEntries = ChatSyncCodec.clampHistoryLines(ChatConfig.maxChatHistoryLines);

        for (String key : playersTag.getAllKeys()) {
            CompoundTag playerTag = playersTag.getCompound(key);
            long lastSeen = playerTag.getLong("LastSeen");
            if (now - lastSeen > expireTime && lastSeen != 0) continue;

            PlayerChatData data = new PlayerChatData();
            data.lastSeen = lastSeen;
            ListTag chatList = playerTag.getList("ChatLines", Tag.TAG_COMPOUND);
            int firstChat = Math.max(0, chatList.size() - maxEntries);
            for (int i = firstChat; i < chatList.size(); i++) {
                CompoundTag entry = chatList.getCompound(i);
                String json = entry.getString("js");
                if (json.length() > maxChars) continue;
                UUID senderUuid = entry.hasUUID("sender") ? entry.getUUID("sender") : null;
                ChatEntry chatEntry = new ChatEntry(json, entry.getLong("ts"), senderUuid);
                int wireBytes = measureWireBytes(chatEntry);
                if (wireBytes < 0) continue;
                data.chatLines.add(chatEntry);
                data.chatLineCount++;
                data.chatWireBytes += wireBytes;
                trimChatLines(data, maxEntries);
            }

            ListTag inputList = playerTag.getList("InputLines", Tag.TAG_STRING);
            int firstInput = Math.max(0, inputList.size() - ChatSyncCodec.MAX_INPUT_HISTORY_LINES);
            for (int i = firstInput; i < inputList.size(); i++) {
                String input = inputList.getString(i);
                if (input.length() <= maxChars) data.inputLines.add(input);
            }
            manager.playerData.put(UUID.fromString(key), data);
        }
        return manager;
    }

    @Override
    public @NotNull CompoundTag save(@NotNull CompoundTag tag) {
        CompoundTag playersTag = new CompoundTag();
        int maxEntries = ChatSyncCodec.clampHistoryLines(ChatConfig.maxChatHistoryLines);
        for (Map.Entry<UUID, PlayerChatData> entry : playerData.entrySet()) {
            trimChatLines(entry.getValue(), maxEntries);
            CompoundTag playerTag = new CompoundTag();
            playerTag.putLong("LastSeen", entry.getValue().lastSeen);

            ListTag chatList = new ListTag();
            for (ChatEntry chatEntry : entry.getValue().chatLines) {
                CompoundTag chatTag = new CompoundTag();
                chatTag.putString("js", chatEntry.json());
                chatTag.putLong("ts", chatEntry.timestamp());
                if (chatEntry.senderUuid() != null) chatTag.putUUID("sender", chatEntry.senderUuid());
                chatList.add(chatTag);
            }
            playerTag.put("ChatLines", chatList);

            ListTag inputList = new ListTag();
            for (String input : entry.getValue().inputLines) {
                inputList.add(net.minecraft.nbt.StringTag.valueOf(input));
            }
            playerTag.put("InputLines", inputList);
            playersTag.put(entry.getKey().toString(), playerTag);
        }
        tag.put("Players", playersTag);
        return tag;
    }

    public static class PlayerChatData {
        public long lastSeen = System.currentTimeMillis();
        public int chatLineCount;
        public int chatWireBytes;
        public final ConcurrentLinkedDeque<ChatEntry> chatLines = new ConcurrentLinkedDeque<>();
        public final ConcurrentLinkedDeque<String> inputLines = new ConcurrentLinkedDeque<>();
    }

    public record ChatEntry(
            String json,
            long timestamp,
            @Nullable UUID senderUuid
    ) implements ChatSyncCodec.HistoryLine {
    }
}
