package org.apve;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;

public final class MessageUtil {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private MessageUtil() {}

    public static String formatLegacyToMiniMessage(String text) {
        if (text == null) return "";

        String normalizedText = text.replace('§', '&');

        String hexConvertedText = normalizedText.replaceAll("(?i)&#([a-f0-9]{6})", "<#$1>");

        String miniMessageText = hexConvertedText
                .replaceAll("(?i)&0", "<black>")
                .replaceAll("(?i)&1", "<dark_blue>")
                .replaceAll("(?i)&2", "<dark_green>")
                .replaceAll("(?i)&3", "<dark_aqua>")
                .replaceAll("(?i)&4", "<dark_red>")
                .replaceAll("(?i)&5", "<dark_purple>")
                .replaceAll("(?i)&6", "<gold>")
                .replaceAll("(?i)&7", "<gray>")
                .replaceAll("(?i)&8", "<dark_gray>")
                .replaceAll("(?i)&9", "<blue>")
                .replaceAll("(?i)&a", "<green>")
                .replaceAll("(?i)&b", "<aqua>")
                .replaceAll("(?i)&c", "<red>")
                .replaceAll("(?i)&d", "<light_purple>")
                .replaceAll("(?i)&e", "<yellow>")
                .replaceAll("(?i)&f", "<white>")
                .replaceAll("(?i)&l", "<bold>")
                .replaceAll("(?i)&m", "<strikethrough>")
                .replaceAll("(?i)&n", "<underlined>")
                .replaceAll("(?i)&o", "<italic>")
                .replaceAll("(?i)&k", "<obfuscated>")
                .replaceAll("(?i)&r", "<reset>");

        return miniMessageText;
    }

    public static Component parse(String text, TagResolver... resolvers) {
        String preparedText = formatLegacyToMiniMessage(text);

        Component parsedComponent = MINI_MESSAGE.deserialize(preparedText, resolvers);

        return parsedComponent;
    }

    public static void send(CommandSender sender, String text, TagResolver... resolvers) {
        Component messageToSend = parse(text, resolvers);

        sender.sendMessage(messageToSend);
    }
}
