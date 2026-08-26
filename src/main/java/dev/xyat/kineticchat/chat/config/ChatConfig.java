package dev.xyat.kineticchat.chat.config;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.core.io.WritingMode;
import dev.xyat.kineticchat.KineticChat;
import dev.xyat.kineticchat.chat.network.ChatSyncCodec;
import net.minecraftforge.fml.loading.FMLPaths;
import java.nio.file.Path;

public class ChatConfig {
    private static final Path CONFIG_PATH = FMLPaths.CONFIGDIR.get().resolve("kineticcore/chat.toml");
    private static CommentedFileConfig configData;

    public static int maxChatLength = 16384;
    public static int maxChatHistoryLines = 10000;
    public static boolean enableDraggableScrollbar = true;
    public static boolean enableTimestamp = true;
    public static boolean enableCompactChat = true;
    public static boolean stripChatSignatures = true;
    public static boolean enableChatHeads = true;

    public static void load() {
        try {
            configData = CommentedFileConfig.builder(CONFIG_PATH).sync().preserveInsertionOrder().writingMode(WritingMode.REPLACE).build();
            configData.load();
            setupConfig();
            readValues();
            save();
        } catch (Exception e) { KineticChat.LOGGER.error("ChatConfig Load Failed", e); }
    }

    private static void setupConfig() {
        // --- 聊天长度限制 ---
        if (!configData.contains("max_chat_length")) configData.set("max_chat_length", 16384);
        configData.setComment("max_chat_length", """
                 聊天输入框与指令的最大字符限制。
                 Maximum character limit for chat messages and commands.
                 提示：原版硬编码限制为 256。增大此值可发送超长文本或复杂指令。
                 Note: Vanilla is hardcoded to 256. Increasing this allows for long texts or complex commands.""");

        // --- 历史记录行数 ---
        if (!configData.contains("max_chat_history_lines")) configData.set("max_chat_history_lines", 10000);
        configData.setComment("max_chat_history_lines", """
                 客户端聊天历史记录的最大保留行数。
                 Maximum number of lines to keep in client chat history.
                 本机服务端还会为每位玩家限制 8 MiB 的线格式存储量，超出时优先删除最旧记录。
                 Local-server persistence also has an 8 MiB wire-size budget per player; oldest records are removed first.
                 提示：原版限制为 100。增加此值有助于在不翻看日志文件的情况下查阅长篇对话。
                 Note: Vanilla limit is 100. Increasing this helps with reviewing long conversations without checking logs.""");

        // --- 拖拽滚动条 ---
        if (!configData.contains("enable_draggable_scrollbar")) configData.set("enable_draggable_scrollbar", true);
        configData.setComment("enable_draggable_scrollbar", """
                 是否在聊天界面右侧显示可鼠标拖拽的视觉滚动条。
                 Whether to display a draggable visual scrollbar on the right side of the chat screen.
                 提示：方便在使用鼠标时快速翻阅长达数千行的历史记录。
                 Note: Useful for quickly scrolling through thousands of history lines using a mouse.""");

        // --- 时间戳 ---
        if (!configData.contains("enable_timestamp")) configData.set("enable_timestamp", true);
        configData.setComment("enable_timestamp", """
                 是否在每条消息前显示高精度时间戳 [HH:mm:ss.SSS]。
                 Whether to display a high-precision timestamp [HH:mm:ss.SSS] before each message.
                 提示：有助于在回溯历史记录时精确追踪事件发生的时间。
                 Note: Helps accurately track event timing when reviewing chat history.""");

        // --- 消息合并 ---
        if (!configData.contains("enable_compact_chat")) configData.set("enable_compact_chat", true);
        configData.setComment("enable_compact_chat", """
                 是否开启消息合并（叠楼）。连续相同的消息将合并显示并增加计数 [x5]。
                 Whether to enable Message Compaction. Identical consecutive messages will be merged with a counter.
                 提示：大幅减少系统消息、模组报错或玩家复读导致的刷屏。
                 Note: Significantly reduces spam from system messages, mod errors, or player repetition.""");

        // --- 末端签名剥离  ---
        if (!configData.contains("strip_chat_signatures")) configData.set("strip_chat_signatures", true);
        configData.setComment("strip_chat_signatures", """
                 是否启用“末端签名剥离”技术（反举报功能）。
                 Whether to enable "Late Signature Stripping" (No Chat Reports functionality).
                 提示：在消息广播给其他玩家的前一刻抹除签名，既能废除微软举报系统，又能保全服务端聊天事件流的完整性。
                 Note: Strips signatures before broadcasting, disabling the reporting system while preserving server-side chat events.""");

        // --- 聊天头像 ---
        if (!configData.contains("enable_chat_heads")) configData.set("enable_chat_heads", true);
        configData.setComment("enable_chat_heads", """
                 是否在玩家消息前显示 8x8 像素的皮肤头像。
                 Whether to display 8x8 pixel skin avatars before player messages.
                 提示：支持正版皮肤实时获取与持久化恢复。即便重新进入游戏，历史记录中的头像依然会根据 UUID 显示。
                 Note: Supports real-time premium skin retrieval and persistence. Avatars in history will still show correctly via UUID after reconnection.""");
    }

    private static void readValues() {
        maxChatLength = ChatSyncCodec.clampChatLength(configData.getIntOrElse("max_chat_length", 16384));
        maxChatHistoryLines = ChatSyncCodec.clampHistoryLines(
                configData.getIntOrElse("max_chat_history_lines", 10000));
        enableDraggableScrollbar = configData.getOrElse("enable_draggable_scrollbar", true);
        enableTimestamp = configData.getOrElse("enable_timestamp", true);
        enableCompactChat = configData.getOrElse("enable_compact_chat", true);
        stripChatSignatures = configData.getOrElse("strip_chat_signatures", true);
        enableChatHeads = configData.getOrElse("enable_chat_heads", true);
    }

    public static void save() {
        if (configData == null) return;
        saveServerSettings();
        saveClientSettings();
    }

    public static void saveServerSettings() {
        if (configData == null) return;
        maxChatLength = ChatSyncCodec.clampChatLength(maxChatLength);
        maxChatHistoryLines = ChatSyncCodec.clampHistoryLines(maxChatHistoryLines);
        configData.set("max_chat_length", maxChatLength);
        configData.set("max_chat_history_lines", maxChatHistoryLines);
        configData.set("strip_chat_signatures", stripChatSignatures);
        configData.save();
    }

    public static void saveClientSettings() {
        if (configData == null) return;
        configData.set("enable_draggable_scrollbar", enableDraggableScrollbar);
        configData.set("enable_timestamp", enableTimestamp);
        configData.set("enable_compact_chat", enableCompactChat);
        configData.set("enable_chat_heads", enableChatHeads);
        configData.save();
    }
}
