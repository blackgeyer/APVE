package org.apve;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

public class PunishmentManager {

    private final JavaPlugin plugin;
    private final List<String> permanentKeywords;

    public PunishmentManager(JavaPlugin plugin) {
        this.plugin = plugin;
        
        List<String> configList = plugin.getConfig().getStringList("punishments.perm-keywords");
        this.permanentKeywords = configList.stream()
                .map(s -> s.toLowerCase(Locale.ROOT).trim())
                .collect(Collectors.toList());
    }

    public void mutePlayer(UUID playerUuid, String reason, String durationStr) {
        String commandTemplate = plugin.getConfig().getString("punishments.mute-command", "mute %player% %duration% %reason%");
        executeCommand(commandTemplate, playerUuid, null, reason, durationStr);
    }

    public void banPlayer(UUID playerUuid, String reason, String durationStr) {
        String commandTemplate = plugin.getConfig().getString("punishments.ban-command", "ban %player% %duration% %reason%");
        executeCommand(commandTemplate, playerUuid, null, reason, durationStr);
    }

    public void banipPlayer(String ipAddress, String reason, String durationStr) {
        String commandTemplate = plugin.getConfig().getString("punishments.banip-command", "banip %ip% %duration% %reason%");
        executeCommand(commandTemplate, null, ipAddress, reason, durationStr);
    }

    public void kickPlayer(UUID playerUuid, String reason) {
        String commandTemplate = plugin.getConfig().getString("punishments.kick-command", "kick %player% %reason%");
        executeCommand(commandTemplate, playerUuid, null, reason, null);
    }

    private void executeCommand(String template, UUID playerUuid, String ipAddress, String reason, String durationStr) {
        if (!plugin.getConfig().getBoolean("punishments.punishments-is-enabled", true)) {
            return;
        }

        if (template == null || template.trim().isEmpty()) {
            return;
        }

        String command = template;

        if (playerUuid != null) {
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerUuid);
            String playerName = offlinePlayer.getName();
            
            if (playerName == null) {
                playerName = playerUuid.toString();
            }

            command = command.replace("%player%", playerName);
            command = command.replace("%uuid%", playerUuid.toString());
        }

        if (ipAddress != null) {
            command = command.replace("%ip%", ipAddress);
        }

        if (durationStr != null) {
            boolean isPermanent = isPermanentKeyword(durationStr);
            String formattedDuration = isPermanent ? "perm" : durationStr.trim();
            command = command.replace("%duration%", formattedDuration);
        }

        String formattedReason = (reason != null && !reason.trim().isEmpty()) ? reason : "Rule-violating";
        formattedReason = ChatColor.translateAlternateColorCodes('&', formattedReason);
        command = command.replace("%reason%", formattedReason);

        final String finalCommand = command.trim().replaceAll("\\s+", " ");

        if (finalCommand.isEmpty()) {
            return;
        }

        Runnable dispatchTask = () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCommand);

        if (Bukkit.isPrimaryThread()) {
            dispatchTask.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, dispatchTask);
        }
    }

    private boolean isPermanentKeyword(String durationStr) {
        if (durationStr == null || durationStr.isBlank()) {
            return false;
        }
        return permanentKeywords.contains(durationStr.toLowerCase(Locale.ROOT).trim());
    }
}