package com.aicivilization.util;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

/** Consistent chat palette for mod feedback. */
public final class ChatMessages {
    private ChatMessages() {}

    public static Component info(String text) {
        return tagged("AI Civ", ChatFormatting.AQUA, text);
    }

    public static Component success(String text) {
        return tagged("OK", ChatFormatting.GREEN, text);
    }

    public static Component error(String text) {
        return tagged("!", ChatFormatting.RED, text);
    }

    public static Component title(String text) {
        return Component.literal(text).withStyle(ChatFormatting.GOLD).withStyle(ChatFormatting.BOLD);
    }

    private static Component tagged(String tag, ChatFormatting color, String text) {
        return Component.literal("[").withStyle(ChatFormatting.DARK_GRAY)
            .append(Component.literal(tag).withStyle(color).withStyle(ChatFormatting.BOLD))
            .append(Component.literal("] ").withStyle(ChatFormatting.DARK_GRAY))
            .append(Component.literal(text).withStyle(ChatFormatting.WHITE));
    }
}
