package org.apve.etc;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.apve.nci.StructureModule;

import org.apve.command.MessageUtil;
import org.apve.engine.ScheduleManager;
import org.apve.apve;

public class NotificationManager {

    private final apve plugin;
    private final Set<UUID> disabledNotifies = new HashSet<>();
    private String template;

    public NotificationManager(apve plugin) {
        this.plugin = plugin;
        loadMessages();
    }

    public void loadMessages() {
        FileConfiguration config = plugin.getConfig();
        String configTemplate = config.getString("command-msg.violation-notify-msg");
        this.template = configTemplate;
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

    public void sendViolationAlert(Player violator, StructureModule.ViolationType type, String badWord, String rawMessage) {
        String formattedAlert = template
                .replace("{player}", violator.getName())
                .replace("{type}", type.name())
                .replace("{word}", badWord.isEmpty() ? "—" : badWord)
                .replace("{message}", rawMessage);

        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission("apve.violation.notify") && isEnabled(staff.getUniqueId())) {
                ScheduleManager.get().runAtEntity(staff, () -> MessageUtil.send(staff, formattedAlert));
            }
        }
    }

    public Set<UUID> getDisabledNotifies() {
        return disabledNotifies;
    }
}