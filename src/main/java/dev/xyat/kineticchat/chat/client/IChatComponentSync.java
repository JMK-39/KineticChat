package dev.xyat.kineticchat.chat.client;

import java.util.UUID;

public interface IChatComponentSync {
    void kineticchat$setSyncing(boolean syncing);
    void kineticchat$setProvidedTimestamp(long timestamp);
    /** 捕获当前正在处理的消息发送者 */
    void kineticchat$setCapturedSender(UUID uuid);
}
