package dev.xyat.kineticchat.util;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.HashMap;
import java.util.Map;

public final class ColorText {
    private static final Map<String, ChatFormatting[][]> ARG_STYLES = new HashMap<>();

    static {
        ARG_STYLES.put("chat.kineticchat.compact", new ChatFormatting[][]{new ChatFormatting[]{ChatFormatting.GOLD, ChatFormatting.BOLD}});
        ARG_STYLES.put("chat.kineticchat.timestamp", new ChatFormatting[][]{new ChatFormatting[]{ChatFormatting.GREEN}});
    }

    private ColorText() {
    }

    public static MutableComponent translatable(String key, Object... args) {
        ChatFormatting[][] styles = ARG_STYLES.get(key);
        if (styles == null || args.length == 0) {
            return Component.translatable(key, args);
        }
        Object[] styledArgs = args.clone();
        int count = Math.min(styles.length, styledArgs.length);
        for (int i = 0; i < count; i++) {
            ChatFormatting[] formats = styles[i];
            if (formats == null || formats.length == 0) continue;
            Object value = styledArgs[i];
            boolean preserveColor = value instanceof Component existing && existing.getStyle().getColor() != null;
            MutableComponent component = value instanceof Component existing
                    ? existing.copy()
                    : Component.literal(String.valueOf(value));
            if (preserveColor) {
                for (int j = 1; j < formats.length; j++) {
                    component.withStyle(formats[j]);
                }
            } else {
                component.withStyle(formats);
            }
            styledArgs[i] = component;
        }
        return Component.translatable(key, styledArgs);
    }
}
