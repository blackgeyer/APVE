package org.apve;

import org.bukkit.Bukkit;
import org.bukkit.plugin.PluginManager;

import java.util.Optional;

public enum PunishmentPlugin {

    LITEBANS("LiteBans",
            "mute %player% %duration% %reason%",
            "ban %player% %duration% %reason%",
            "ipban %ip% %duration% %reason%",
            "kick %player% %reason%"),

    ADVANCED_BAN("AdvancedBan",
            "tempmute %player% %duration% %reason%",
            "tempban %player% %duration% %reason%",
            "tempipban %ip% %duration% %reason%",
            "kick %player% %reason%"),

    LIBERTY_BANS("LibertyBans",
            "mute %player% %duration% %reason%",
            "ban %player% %duration% %reason%",
            "banip %ip% %duration% %reason%",
            "kick %player% %reason%");


    private final String pluginName;
    private final String muteCommand;
    private final String banCommand;
    private final String banipCommand;
    private final String kickCommand;

    PunishmentPlugin(String pluginName, String muteCommand, String banCommand, String banipCommand, String kickCommand) {
        this.pluginName = pluginName;
        this.muteCommand = muteCommand;
        this.banCommand = banCommand;
        this.banipCommand = banipCommand;
        this.kickCommand = kickCommand;
    }

    public static Optional<PunishmentPlugin> detectInstalledPlugin() {
        PluginManager pm = Bukkit.getPluginManager();
        if (pm == null) return Optional.empty();

        for (PunishmentPlugin plugin : values()) {
            if (pm.isPluginEnabled(plugin.pluginName)) {
                return Optional.of(plugin);
            }
        }

        return Optional.empty();
    }

    public String getPluginName()   { return pluginName;   }
    public String getMuteCommand()  { return muteCommand;  }
    public String getBanCommand()   { return banCommand;   }
    public String getBanipCommand() { return banipCommand; }
    public String getKickCommand()  { return kickCommand;  }
}