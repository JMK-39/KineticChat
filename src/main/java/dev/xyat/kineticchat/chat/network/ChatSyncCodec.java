package dev.xyat.kineticchat.chat.network;

import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Strict codec and shared limits for the {@code kineticchat:chat_sync} payload.
 *
 * <p>The existing wire layout is intentionally unchanged. The helpers only add
 * canonical encoding, bounded allocation, and full-consumption checks.</p>
 */
public final class ChatSyncCodec {
    public static final int TYPE_CHAT_LINE = 0;
    public static final int TYPE_INPUT_LINE = 1;
    public static final int TYPE_INPUT_HISTORY = 3;
    public static final int TYPE_CHAT_HISTORY = 10;

    public static final int MIN_CHAT_LENGTH = 256;
    public static final int MAX_CHAT_LENGTH = 32767;
    public static final int MIN_HISTORY_LINES = 100;
    public static final int MAX_HISTORY_LINES = 100000;
    public static final int MAX_INPUT_HISTORY_LINES = 50;

    /** Vanilla's hard limit for a serverbound custom-payload body in 1.20.1. */
    public static final int MAX_SERVERBOUND_PACKET_BYTES = 32_767;

    /** Stays below Minecraft's custom-payload ceiling after the one-byte type. */
    public static final int MAX_COMPRESSED_HISTORY_BYTES = 1_000_000;
    public static final int MAX_UNCOMPRESSED_HISTORY_BYTES = 8 * 1024 * 1024;
    /** Persistent wire-size budget for one player's chat records. */
    public static final int MAX_STORED_HISTORY_BYTES_PER_PLAYER = MAX_UNCOMPRESSED_HISTORY_BYTES;

    private ChatSyncCodec() {
    }

    public static int clampChatLength(int value) {
        return Math.max(MIN_CHAT_LENGTH, Math.min(MAX_CHAT_LENGTH, value));
    }

    public static int clampHistoryLines(int value) {
        return Math.max(MIN_HISTORY_LINES, Math.min(MAX_HISTORY_LINES, value));
    }

    /**
     * The legacy readers allowed four serialized characters per configured
     * chat character, while vanilla writeUtf caps the result at 32767. Keep
     * that effective limit so styled component JSON remains compatible.
     */
    public static int wireStringLimit(int configuredMaxChatChars) {
        return Math.min(MAX_CHAT_LENGTH, clampChatLength(configuredMaxChatChars) * 4);
    }

    public static boolean isServerboundType(int type) {
        return type == TYPE_CHAT_LINE || type == TYPE_INPUT_LINE;
    }

    public static boolean isClientboundType(int type) {
        return type == TYPE_CHAT_HISTORY || type == TYPE_INPUT_HISTORY;
    }

    public static ChatLine decodeChatLine(byte[] payload, int configuredMaxChars) throws IOException {
        StrictInput input = StrictInput.forBytes(payload);
        long timestamp = input.readLong();
        boolean hasUuid = input.readBoolean();
        UUID senderUuid = hasUuid ? input.readUuid() : null;
        String json = input.readUtf(wireStringLimit(configuredMaxChars));
        input.requireEnd();
        return new ChatLine(json, timestamp, senderUuid);
    }

    public static String decodeInputLine(byte[] payload, int configuredMaxChars) throws IOException {
        StrictInput input = StrictInput.forBytes(payload);
        String value = input.readUtf(wireStringLimit(configuredMaxChars));
        input.requireEnd();
        return value;
    }

    public static byte[] encodeInputLine(String value, int configuredMaxChars) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeUtf(output, value, wireStringLimit(configuredMaxChars));
        return output.toByteArray();
    }

    public static byte[] encodeChatLine(
            String json,
            long timestamp,
            @Nullable UUID senderUuid,
            int configuredMaxChars
    ) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeLong(output, timestamp);
        output.write(senderUuid == null ? 0 : 1);
        if (senderUuid != null) {
            writeLong(output, senderUuid.getMostSignificantBits());
            writeLong(output, senderUuid.getLeastSignificantBits());
        }
        writeUtf(output, json, wireStringLimit(configuredMaxChars));
        return output.toByteArray();
    }

    /**
     * Decodes a GZIP history body directly from the stream. No unbounded
     * No unbounded bulk-read allocation is used.
     */
    public static List<ChatLine> decodeCompressedHistory(
            byte[] compressed,
            int configuredMaxEntries,
            int configuredMaxChars
    ) throws IOException {
        return decodeCompressedHistory(
                compressed,
                configuredMaxEntries,
                configuredMaxChars,
                clampHistoryLines(configuredMaxEntries)
        );
    }

    /**
     * Validates every wire entry while retaining only the newest requested
     * suffix. This lets clients with a smaller local history setting accept a
     * larger server history without retaining the entire list in memory.
     */
    public static List<ChatLine> decodeCompressedHistory(
            byte[] compressed,
            int configuredMaxEntries,
            int configuredMaxChars,
            int retainNewestEntries
    ) throws IOException {
        if (compressed.length == 0 || compressed.length > MAX_COMPRESSED_HISTORY_BYTES) {
            throw new ProtocolException("compressed history length is out of bounds: " + compressed.length);
        }

        int maxEntries = clampHistoryLines(configuredMaxEntries);
        int maxChars = wireStringLimit(configuredMaxChars);
        int retainLimit = Math.max(0, Math.min(retainNewestEntries, maxEntries));
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed));
             LimitedInputStream limited = new LimitedInputStream(gzip, MAX_UNCOMPRESSED_HISTORY_BYTES)) {
            StrictInput input = new StrictInput(limited);
            int count = input.readVarInt();
            if (count < 0 || count > maxEntries) {
                throw new ProtocolException("history entry count is out of bounds: " + count);
            }

            ArrayDeque<ChatLine> retained = new ArrayDeque<>(Math.min(count, retainLimit));
            for (int i = 0; i < count; i++) {
                long timestamp = input.readLong();
                boolean hasUuid = input.readBoolean();
                UUID senderUuid = hasUuid ? input.readUuid() : null;
                String json = input.readUtf(maxChars);
                if (retainLimit > 0) {
                    if (retained.size() == retainLimit) retained.removeFirst();
                    retained.addLast(new ChatLine(json, timestamp, senderUuid));
                }
            }
            input.requireEnd();
            return new ArrayList<>(retained);
        }
    }

    /**
     * Selects a newest-first iterator into an oldest-to-newest wire payload.
     * If a payload compresses poorly, the oldest half is discarded and the
     * newest suffix is retried until it fits the compressed hard limit.
     */
    public static EncodedHistory encodeCompressedHistory(
            Iterator<? extends HistoryLine> newestFirst,
            int configuredMaxEntries,
            int configuredMaxChars
    ) throws IOException {
        int maxEntries = clampHistoryLines(configuredMaxEntries);
        int maxChars = wireStringLimit(configuredMaxChars);
        ArrayDeque<byte[]> selected = new ArrayDeque<>();
        int entriesBytes = 0;

        while (newestFirst.hasNext() && selected.size() < maxEntries) {
            HistoryLine entry = newestFirst.next();
            byte[] encoded;
            try {
                encoded = encodeHistoryEntry(entry, maxChars);
            } catch (ProtocolException ignored) {
                // Old persisted data can predate these limits. Skip an invalid
                // record instead of allowing it to prevent newer valid syncs.
                continue;
            }

            if (entriesBytes + encoded.length + 5 > MAX_UNCOMPRESSED_HISTORY_BYTES) {
                break;
            }
            selected.addFirst(encoded);
            entriesBytes += encoded.length;
        }

        while (true) {
            int uncompressedBytes = varIntSize(selected.size()) + entriesBytes;
            try {
                byte[] compressed = compress(selected);
                return new EncodedHistory(compressed, selected.size(), uncompressedBytes);
            } catch (OutputLimitException tooLarge) {
                if (selected.isEmpty()) {
                    throw tooLarge;
                }
                int toRemove = Math.max(1, selected.size() / 2);
                for (int i = 0; i < toRemove; i++) {
                    entriesBytes -= selected.removeFirst().length;
                }
            }
        }
    }

    private static byte[] encodeHistoryEntry(HistoryLine entry, int maxChars) throws IOException {
        if (entry == null || entry.json() == null) {
            throw new ProtocolException("history entry is null");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeLong(output, entry.timestamp());
        output.write(entry.senderUuid() == null ? 0 : 1);
        if (entry.senderUuid() != null) {
            writeLong(output, entry.senderUuid().getMostSignificantBits());
            writeLong(output, entry.senderUuid().getLeastSignificantBits());
        }
        writeUtf(output, entry.json(), maxChars);
        return output.toByteArray();
    }

    public static int historyEntryWireBytes(HistoryLine entry) throws IOException {
        return encodeHistoryEntry(entry, MAX_CHAT_LENGTH).length;
    }

    private static byte[] compress(ArrayDeque<byte[]> entries) throws IOException {
        LimitedByteArrayOutputStream output =
                new LimitedByteArrayOutputStream(MAX_COMPRESSED_HISTORY_BYTES);
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            writeVarInt(gzip, entries.size());
            for (byte[] entry : entries) {
                gzip.write(entry);
            }
        }
        return output.toByteArray();
    }

    private static void writeUtf(OutputStream output, String value, int maxChars) throws IOException {
        if (value == null || value.length() > maxChars) {
            throw new ProtocolException("string character length is out of bounds");
        }

        ByteBuffer encoded;
        try {
            encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value));
        } catch (CharacterCodingException exception) {
            throw new ProtocolException("string is not valid UTF-8", exception);
        }

        int byteLength = encoded.remaining();
        if (byteLength > maxChars * 4) {
            throw new ProtocolException("encoded string length is out of bounds: " + byteLength);
        }
        writeVarInt(output, byteLength);
        if (encoded.hasArray()) {
            output.write(encoded.array(), encoded.arrayOffset() + encoded.position(), byteLength);
        } else {
            byte[] bytes = new byte[byteLength];
            encoded.get(bytes);
            output.write(bytes);
        }
    }

    private static void writeLong(OutputStream output, long value) throws IOException {
        for (int shift = 56; shift >= 0; shift -= 8) {
            output.write((int) (value >>> shift) & 0xff);
        }
    }

    private static void writeVarInt(OutputStream output, int value) throws IOException {
        while ((value & ~0x7f) != 0) {
            output.write((value & 0x7f) | 0x80);
            value >>>= 7;
        }
        output.write(value);
    }

    private static int varIntSize(int value) {
        int size = 1;
        while ((value & ~0x7f) != 0) {
            value >>>= 7;
            size++;
        }
        return size;
    }

    public interface HistoryLine {
        String json();

        long timestamp();

        @Nullable UUID senderUuid();
    }

    public record ChatLine(String json, long timestamp, @Nullable UUID senderUuid) implements HistoryLine {
    }

    public record EncodedHistory(byte[] compressed, int entryCount, int uncompressedBytes) {
    }

    public static class ProtocolException extends IOException {
        public ProtocolException(String message) {
            super(message);
        }

        public ProtocolException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class StrictInput {
        private final InputStream input;

        private StrictInput(InputStream input) {
            this.input = input;
        }

        private static StrictInput forBytes(byte[] bytes) {
            return new StrictInput(new ByteArrayInputStream(bytes));
        }

        private int readUnsignedByte() throws IOException {
            int value = input.read();
            if (value < 0) {
                throw new EOFException("unexpected end of chat sync payload");
            }
            return value;
        }

        private boolean readBoolean() throws IOException {
            int value = readUnsignedByte();
            if (value != 0 && value != 1) {
                throw new ProtocolException("boolean value is not canonical: " + value);
            }
            return value == 1;
        }

        private long readLong() throws IOException {
            long value = 0;
            for (int i = 0; i < Long.BYTES; i++) {
                value = (value << 8) | readUnsignedByte();
            }
            return value;
        }

        private UUID readUuid() throws IOException {
            return new UUID(readLong(), readLong());
        }

        private int readVarInt() throws IOException {
            int result = 0;
            int bytes = 0;
            int current;
            do {
                current = readUnsignedByte();
                if (bytes == 4 && (current & 0xf0) != 0) {
                    throw new ProtocolException("VarInt exceeds the positive integer range");
                }
                result |= (current & 0x7f) << (bytes * 7);
                bytes++;
                if (bytes > 5) {
                    throw new ProtocolException("VarInt is too long");
                }
            } while ((current & 0x80) != 0);

            if (result < 0 || bytes != varIntSize(result)) {
                throw new ProtocolException("VarInt is negative or non-canonical");
            }
            return result;
        }

        private String readUtf(int maxChars) throws IOException {
            int byteLength = readVarInt();
            if (byteLength > maxChars * 4) {
                throw new ProtocolException("encoded string length is out of bounds: " + byteLength);
            }

            byte[] bytes = new byte[byteLength];
            int offset = 0;
            while (offset < byteLength) {
                int read = input.read(bytes, offset, byteLength - offset);
                if (read < 0) {
                    throw new EOFException("truncated UTF-8 string");
                }
                offset += read;
            }

            String decoded;
            try {
                decoded = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes))
                        .toString();
            } catch (CharacterCodingException exception) {
                throw new ProtocolException("string is not valid UTF-8", exception);
            }
            if (decoded.length() > maxChars) {
                throw new ProtocolException("decoded string length is out of bounds: " + decoded.length());
            }
            return decoded;
        }

        private void requireEnd() throws IOException {
            if (input.read() != -1) {
                throw new ProtocolException("chat sync payload has trailing bytes");
            }
        }
    }

    /** Counts decompressed bytes and probes EOF without allowing byte N+1 through. */
    private static final class LimitedInputStream extends InputStream {
        private final InputStream delegate;
        private final int limit;
        private int count;

        private LimitedInputStream(InputStream delegate, int limit) {
            this.delegate = delegate;
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value < 0) {
                return -1;
            }
            if (count >= limit) {
                throw new ProtocolException("decompressed history exceeds " + limit + " bytes");
            }
            count++;
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            if (count >= limit) {
                return read() < 0 ? -1 : 1;
            }
            int allowed = Math.min(length, limit - count);
            int read = delegate.read(bytes, offset, allowed);
            if (read > 0) {
                count += read;
            }
            return read;
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }

    private static final class LimitedByteArrayOutputStream extends OutputStream {
        private final ByteArrayOutputStream delegate = new ByteArrayOutputStream();
        private final int limit;
        private int count;

        private LimitedByteArrayOutputStream(int limit) {
            this.limit = limit;
        }

        @Override
        public void write(int value) throws IOException {
            ensureCapacity(1);
            delegate.write(value);
            count++;
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            ensureCapacity(length);
            delegate.write(bytes, offset, length);
            count += length;
        }

        private void ensureCapacity(int additional) throws OutputLimitException {
            if (additional < 0 || count > limit - additional) {
                throw new OutputLimitException("compressed history exceeds " + limit + " bytes");
            }
        }

        private byte[] toByteArray() {
            return delegate.toByteArray();
        }
    }

    private static final class OutputLimitException extends IOException {
        private OutputLimitException(String message) {
            super(message);
        }
    }
}
