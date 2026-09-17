package org.apve;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class NotificationManager {

    private final apve plugin;
    private final Set<UUID> disabledNotifies = new HashSet<>();
    private String template;

    public NotificationManager(apve plugin) {
        this.plugin = plugin;
        loadMessages();
    }

    public void loadMessages() {
        this.template = plugin.getConfig().getString("command-msg.violation-notify-msg");
    }

    public boolean toggleNotifications(UUID uuid) {
        if (disabledNotifies.contains(uuid)) {
            disabledNotifies.remove(uuid);
            return true;
        } else {
            disabledNotifies.add(uuid);
            return false;
        }
    }

    public boolean isEnabled(UUID uuid) {
        return !disabledNotifies.contains(uuid);
    }

    public void sendViolationAlert(Player violator, NetworkChatInterceptor.ViolationType type, String badWord, String rawMessage) {
        String formattedAlert = ChatColor.translateAlternateColorCodes('&', template
                .replace("{player}", violator.getName())
                .replace("{type}", type.name())
                .replace("{word}", badWord.isEmpty() ? "—" : badWord)
                .replace("{message}", rawMessage));

        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission("apve.violation.notify") && isEnabled(staff.getUniqueId())) {
                staff.sendMessage(formattedAlert);
            }
        }
    }

    public Set<UUID> getDisabledNotifies() {
        return disabledNotifies;
    }
}