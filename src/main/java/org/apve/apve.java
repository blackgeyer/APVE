package org.apve;

import com.github.retrooper.packetevents.PacketEvents;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;

import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.List;
import java.util.logging.FileHandler;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

public class apve extends JavaPlugin implements Listener {

    private NotificationManager notificationManager;
    private Logger suspiciousLogger;
    private Logger maliciousLogger;
    private PunishmentManager punishmentManager;

    @Override
    public void onLoad() {
        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
        PacketEvents.getAPI().getSettings()
                .reEncodeByDefault(true)  
                .checkForUpdates(false);
        PacketEvents.getAPI().load();
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        checkConfigForDefault();

        YamlConfiguration yamlConfig = (YamlConfiguration) getConfig();

        FoolProof foolProof = new FoolProof(yamlConfig);
        FoolProof.ValidationResult result = foolProof.validateAll();

        List<String> totalWarnings = result.warnings();

        boolean auditmode = getConfig().getBoolean("audit-mode");

        if (!totalWarnings.isEmpty()) {
            getLogger().warning("Configuration Warnings Found:");
            for (String warn : totalWarnings) {
                getLogger().warning(" [!] " + warn);
            }
        }

        if (result.hasErrors()) {
            int maxSeverity = result.maxSeverity();

            if (maxSeverity < 4) {
                YamlConfiguration defConfig = getDefaultConfig();
                getLogger().warning(String.format("CONFIGURATION ERRORS DETECTED (Severity %d/4):", maxSeverity));
                
                for (FoolProof.ConfigError err : result.errors()) {
                    if (err.severity() == 3 && err.path() != null && defConfig != null && defConfig.contains(err.path())) {
                        getConfig().set(err.path(), defConfig.get(err.path()));
                        getLogger().warning(String.format(" [Level %d] %s (Auto-fixed to default)", err.severity(), err.message()));
                    } else {
                        getLogger().warning(String.format(" [Level %d] %s", err.severity(), err.message()));
                    }
                }
                getLogger().warning("Plugin will continue working, but proper plugin operation is not guaranteed. The plugin may use default values for safety. Use at your own risk!");
            } 
            else {
                getLogger().severe(String.format("CRITICAL CONFIGURATION ERRORS DETECTED (Severity %d/4)!", maxSeverity));
                for (FoolProof.ConfigError err : result.errors()) {
                    if (err.severity() >= 4) {
                        getLogger().severe(String.format(" [CRITICAL - Level %d] %s", err.severity(), err.message()));
                    } else {
                        getLogger().warning(String.format(" [MINOR - Level %d] %s", err.severity(), err.message()));
                    }
                }
                getLogger().severe("Plugin disabled due to critical configuration errors.");

                getServer().getPluginManager().disablePlugin(this);
                return;
            }
        }

        setupSuspiciousLogger();
        setupMaliciousLogger();

        this.notificationManager = new NotificationManager(this);
        this.punishmentManager = new PunishmentManager(this);
        getServer().getPluginManager().registerEvents(this, this);

        NetworkChatInterceptor.register(this, notificationManager, punishmentManager, suspiciousLogger, maliciousLogger, result);

        PacketEvents.getAPI().init();

        CommandManager commandManager = new CommandManager(this);
        getCommand("apve").setExecutor(commandManager);
        getCommand("apve").setTabCompleter(commandManager);

        getLogger().info("Autonomous Potential Violation Eradicator [A.P.V.E] plugin has been successfully activated!");
        if (auditmode) {
            getLogger().warning("A.P.V.E Started with enabled audit-mode, actions to the violators will not apply.");
        }
    }
    
    public NotificationManager getNotificationManager() {
        return notificationManager;
    }

    @Override
    public void onDisable() {
        if (PacketEvents.getAPI() != null && PacketEvents.getAPI().isInitialized()) {
            PacketEvents.getAPI().terminate();
        }
        getLogger().info("Autonomous Potential Violation Eradicator plugin deactivated.");
    }

    public void performReload(CommandSender sender) {
        reloadConfig();
        checkConfigForDefault();

        YamlConfiguration yamlConfig = (YamlConfiguration) getConfig();

        FoolProof foolProof = new FoolProof(yamlConfig);
        FoolProof.ValidationResult result = foolProof.validateAll();

        List<String> totalWarnings = result.warnings();

        if (!totalWarnings.isEmpty()) {
            getLogger().warning("Configuration Warnings Found:");
            for (String warn : totalWarnings) {
                getLogger().warning(" [!] " + warn);
            }
        }

        if (result.hasErrors()) {
            int maxSeverity = result.maxSeverity();

            if (maxSeverity >= 4) {
                getLogger().severe(String.format("CRITICAL CONFIGURATION ERRORS ON RELOAD (Severity %d/4)!", maxSeverity));
                for (FoolProof.ConfigError err : result.errors()) {
                    if (err.severity() >= 4) {
                        getLogger().severe(String.format(" [CRITICAL - Level %d] %s", err.severity(), err.message()));
                    }
                }
                sender.sendMessage("[APVE] Reload aborted due to critical errors (Severity " + maxSeverity + "). Check console.");
                return;
            } else {
                YamlConfiguration defConfig = getDefaultConfig();
                getLogger().warning(String.format("Configuration errors found on reload (Severity %d/4). The plugin may use default values for safety. Use at your own risk!", maxSeverity));
                
                for (FoolProof.ConfigError err : result.errors()) {
                    if (err.severity() == 3 && err.path() != null && defConfig != null && defConfig.contains(err.path())) {
                        getConfig().set(err.path(), defConfig.get(err.path()));
                        getLogger().warning(String.format(" [Level %d] %s (May auto-fixed to default)", err.severity(), err.message()));
                    } else {
                        getLogger().warning(String.format(" [Level %d] %s", err.severity(), err.message()));
                    }
                }
            }
        }

        NetworkChatInterceptor.loadConfig(getConfig(), result);

        if (notificationManager != null) {
            notificationManager.loadMessages();
        }

        sender.sendMessage("[APVE] Config reloaded successfully.");
        getLogger().info("Config reloaded successfully.");
    }
    
    private YamlConfiguration getDefaultConfig() {
        try (InputStream defaultStream = getResource("config.yml")) {
            if (defaultStream != null) {
                return YamlConfiguration.loadConfiguration(new InputStreamReader(defaultStream, StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            getLogger().warning("Could not load internal config.yml for fallback.");
        }
        return null;
    }

    private void checkConfigForDefault() {
        File configFile = new File(getDataFolder(), "config.yml");
        if (!configFile.exists()) return;

        try (InputStream originalStream = getResource("config.yml")) {
            if (originalStream == null) return;

            String currentContent = Files.readString(configFile.toPath(), StandardCharsets.UTF_8);
            String originalContent = new String(originalStream.readAllBytes(), StandardCharsets.UTF_8);

            String currentHash = calculateHash(currentContent);
            String originalHash = calculateHash(originalContent);

            if (currentHash.equals(originalHash)) {
                getLogger().warning("WARNING: You are using the default unconfigured config.yml!");
                getLogger().warning("Please configure your config.yml before using A.P.V.E.");
                getLogger().warning("Without configuring the config.yml, proper plugin operation not guaranteed.");
            }
        } catch (Exception e) {
            getLogger().severe("Could not verify config.yml integrity: " + e.getMessage());
            getLogger().severe("Proper plugin operation is not guaranteed.");
        }
    }

    private String calculateHash(String input) throws Exception {
        String normalizedInput = input.replace("\r\n", "\n");

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] encodedhash = digest.digest(normalizedInput.getBytes(StandardCharsets.UTF_8));

        StringBuilder hexString = new StringBuilder();
        for (byte b : encodedhash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString();
    }

    private void setupSuspiciousLogger() {
        suspiciousLogger = Logger.getLogger("SuspiciousChat");
        try {
            File logDir = new File(getDataFolder(), "logs");
            if (!logDir.exists()) logDir.mkdirs();
            FileHandler fh = new FileHandler(getDataFolder() + "/logs/suspicious.log", true);
            fh.setFormatter(new SimpleFormatter());
            suspiciousLogger.addHandler(fh);
            suspiciousLogger.setUseParentHandlers(false);
        } catch (IOException e) {
            getLogger().severe("Failed to create suspicious.log file: " + e.getMessage());
        }
    }

    public Logger getSuspiciousLogger() {
        return suspiciousLogger;
    }

    private void setupMaliciousLogger() {
        maliciousLogger = Logger.getLogger("MaliciousChat");
        try {
            File logDir = new File(getDataFolder(), "logs");
            if (!logDir.exists()) logDir.mkdirs();
            FileHandler fh = new FileHandler(getDataFolder() + "/logs/malicious.log", true);
            fh.setFormatter(new SimpleFormatter());
            maliciousLogger.addHandler(fh);
            maliciousLogger.setUseParentHandlers(false);
        } catch (IOException e) {
            getLogger().severe("Failed to create malicious.log file: " + e.getMessage());
        }
    }

    public Logger getMaliciousLogger() {
        return maliciousLogger;
    }
}