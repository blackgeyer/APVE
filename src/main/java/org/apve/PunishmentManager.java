package org.apve;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class PunishmentManager {

    private final JavaPlugin plugin;
    private final Set<UUID> mutedPlayers = ConcurrentHashMap.newKeySet();

    private List<String> permanentKeywords;
    private boolean punishmentsEnabled;
    private String muteCommandTemplate;
    private String banCommandTemplate;
    private String banipCommandTemplate;
    private String kickCommandTemplate;

    public PunishmentManager(JavaPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    public void reload() {
        load();
    }

    private void load() {
        FileConfiguration config = plugin.getConfig();

        boolean enabled = config.getBoolean("punishments.punishments-is-enabled", true);
        List<String> rawKeywords = config.getStringList("punishments.perm-keywords");
        String defaultMute = config.getString("punishments.mute-command", "");
        String defaultBan = config.getString("punishments.ban-command", "");
        String defaultBanip = config.getString("punishments.banip-command", "");
        String defaultKick = config.getString("punishments.kick-command", "");
        boolean autoDetect = config.getBoolean("punishments.auto-detect-plugin", true);

        List<String> keywords = rawKeywords.stream()
                .map(s -> s.toLowerCase(Locale.ROOT).trim())
                .collect(Collectors.toList());

        this.punishmentsEnabled = enabled;
        this.permanentKeywords = keywords;

        if (autoDetect) {
            Optional<PunishmentPlugin> detected = PunishmentPlugin.detectInstalledPlugin();

            if (detected.isPresent()) {
                PunishmentPlugin pp = detected.get();
                String pluginName = pp.getPluginName();
                plugin.getLogger().info("Auto-detected punishment plugin: "
                        + pluginName + ". Applying command syntax.");

                String muteCmd = pp.getMuteCommand();
                String banCmd = pp.getBanCommand();
                String banipCmd = pp.getBanipCommand();
                String kickCmd = pp.getKickCommand();

                this.muteCommandTemplate = muteCmd;
                this.banCommandTemplate = banCmd;
                this.banipCommandTemplate = banipCmd;
                this.kickCommandTemplate = kickCmd;
                return;
            }

            plugin.getLogger().warning(
                    "Auto-detect is enabled but no supported punishment plugin was found. "
                    + "Falling back to manual commands from config.");
        }

        this.muteCommandTemplate = defaultMute;
        this.banCommandTemplate = defaultBan;
        this.banipCommandTemplate = defaultBanip;
        this.kickCommandTemplate = defaultKick;
    }

    public boolean isMuted(UUID playerUuid) {
        if (playerUuid == null) {
            return false;
        }
        return mutedPlayers.contains(playerUuid);
    }

    public void mutePlayer(UUID playerUuid, String reason, String durationStr) {
        if (playerUuid != null) {
            mutedPlayers.add(playerUuid);
        }
        executeCommand(muteCommandTemplate, playerUuid, null, reason, durationStr);
    }

    public void unmutePlayer(UUID playerUuid) {
        if (playerUuid != null) {
            mutedPlayers.remove(playerUuid);
        }
    }

    public void banPlayer(UUID playerUuid, String reason, String durationStr) {
        executeCommand(banCommandTemplate, playerUuid, null, reason, durationStr);
    }

    public void banipPlayer(String ipAddress, String reason, String durationStr) {
        executeCommand(banipCommandTemplate, null, ipAddress, reason, durationStr);
    }

    public void kickPlayer(UUID playerUuid, String reason) {
        executeCommand(kickCommandTemplate, playerUuid, null, reason, null);
    }

    private void executeCommand(String template, UUID playerUuid, String ipAddress, String reason, String durationStr) {
        if (!punishmentsEnabled) return;

        if (template == null || template.isBlank()) {
            plugin.getLogger().warning("Attempted to execute an empty punishment command template.");
            return;
        }

        String command = template;

        if (playerUuid != null) {
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerUuid);
            String playerName = offlinePlayer.getName();
            String nameOrUuid = (playerName != null) ? playerName : playerUuid.toString();
            command = command.replace("%player%", nameOrUuid);
            command = command.replace("%uuid%", playerUuid.toString());
        }

        if (ipAddress != null) {
            command = command.replace("%ip%", ipAddress);
        }

        if (durationStr != null) {
            String formattedDuration = isPermanentKeyword(durationStr) ? "perm" : durationStr.trim();
            command = command.replace("%duration%", formattedDuration);
        }

        String rawReason = (reason != null && !reason.isBlank()) ? reason : "Rule-violating";
        Component parsedReason = MiniMessage.miniMessage().deserialize(rawReason);
        String formattedReason = LegacyComponentSerializer.legacySection().serialize(parsedReason);

        command = command.replace("%reason%", formattedReason);

        final String finalCommand = command.trim().replaceAll("\\s+", " ");
        if (finalCommand.isEmpty()) return;

        Runnable dispatchTask = () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCommand);

        if (Bukkit.isPrimaryThread()) {
            dispatchTask.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, dispatchTask);
        }
    }

    private boolean isPermanentKeyword(String durationStr) {
        if (durationStr == null || durationStr.isBlank()) return false;
        return permanentKeywords.contains(durationStr.toLowerCase(Locale.ROOT).trim());
    }
}