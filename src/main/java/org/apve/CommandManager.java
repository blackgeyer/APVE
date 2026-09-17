package org.apve;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.command.ConsoleCommandSender;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import java.util.logging.FileHandler;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;


public class CommandManager implements CommandExecutor, TabCompleter {

    private static final String PERMISSION_RELOAD = "apve.reload";
    private static final String PERMISSION_SHOW = "apve.warns.show";
    private static final String PERMISSION_REMOVE = "apve.warns.remove";
    private static final String PERMISSION_CLEAR = "apve.warns.clear";
    private static final String PERMISSION_NF_TOGGLE = "apve.notify.toggle";
    private static final String PERMISSION_CHECK = "apve.check";
    private static final String PERMISSION_HELP = "apve.help";

    private final apve plugin;

    private String permFailMSG;
    private String reloadMsg;
    private String warnShowMSG;
    private String invalidSyntaxMSG;
    private String noWarnsMSG;
    private String removeWarnMSG;
    private String clearWarnMSG;
    private String invalidNumberMSG;
    private String helpViewReqMSG;
    private String notifyEnabledMSG;
    private String notifyDisabledMSG;
    private String playerOnlyMSG;
    private List<String> helpMSG;

    private String checkHeaderMSG;
    private String checkRawMSG;
    private String checkNormalizedMSG;
    private String checkStatusMSG;
    private String checkWordMSG;
    private String checkDictMSG;
    private String checkDetailMSG;
    private String checkColorMalicious;
    private String checkColorSuspicious;
    private String checkColorNone;

    public CommandManager(apve plugin) {
        this.plugin = plugin;
        loadMessages();
    }

    public void loadMessages() {
        FileConfiguration config = plugin.getConfig();
        this.permFailMSG = config.getString("command-msg.perm-fail");
        this.reloadMsg = config.getString("command-msg.cfg-reload-msg");
        this.warnShowMSG = config.getString("command-msg.warns.show");
        this.invalidSyntaxMSG = config.getString("command-msg.invalid-syntax");
        this.noWarnsMSG = config.getString("command-msg.warns.no-warns");
        this.removeWarnMSG = config.getString("command-msg.warns.removed");
        this.clearWarnMSG = config.getString("command-msg.warns.cleared");
        this.invalidNumberMSG = config.getString("command-msg.invalid-number");
        this.helpViewReqMSG = config.getString("command-msg.help-command-view-req");
        this.notifyEnabledMSG = config.getString("command-msg.notify-activate-msg");
        this.notifyDisabledMSG = config.getString("command-msg.notify-disabled-msg");
        this.playerOnlyMSG = config.getString("command-msg.player-only");
        this.helpMSG = config.getStringList("command-msg.help-command-msg");

        this.checkHeaderMSG = config.getString("command-msg.check.header");
        this.checkRawMSG = config.getString("command-msg.check.raw");
        this.checkNormalizedMSG = config.getString("command-msg.check.normalized");
        this.checkStatusMSG = config.getString("command-msg.check.status");
        this.checkWordMSG = config.getString("command-msg.check.matched-word");
        this.checkDictMSG = config.getString("command-msg.check.dict-word");
        this.checkDetailMSG = config.getString("command-msg.check.detail");
        this.checkColorMalicious = config.getString("command-msg.check.status-colors.malicious");
        this.checkColorSuspicious = config.getString("command-msg.check.status-colors.suspicious");
        this.checkColorNone = config.getString("command-msg.check.status-colors.none");
    
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload" -> handleReload(sender);
            case "warns"  -> handleWarns(sender, args);
            case "help"   -> sendHelp(sender);
            case "notify" -> handleNotifiesToggle(sender);
            case "check"  -> handleCheck(sender, args);
            default       -> handleUnknownCommand(sender);
        }

        return true;
    }

    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission(PERMISSION_RELOAD)) {
            send(sender, permFailMSG);
            return;
        }

        plugin.performReload(sender);
        loadMessages(); 
    }

    private void handleNotifiesToggle(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            send(sender, playerOnlyMSG);
            return;
        }

        if (!player.hasPermission(PERMISSION_NF_TOGGLE)) {
            send(sender, permFailMSG);
            return;
        }

        boolean isNowEnabled = plugin.getNotificationManager().toggleNotifications(player.getUniqueId());

        if (isNowEnabled) {
            send(sender, notifyEnabledMSG);
        } else {
            send(sender, notifyDisabledMSG);
        }
    }

    private void handleCheck(CommandSender sender, String[] args) {
    if (!sender.hasPermission(PERMISSION_CHECK)) {
        send(sender, permFailMSG);
        return;
    }

    if (args.length < 2) {
        send(sender, invalidSyntaxMSG, "{usage}", "/apve check {String}");
        return;
    }

    String rawText = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
    NetworkChatInterceptor.InspectionResult result = NetworkChatInterceptor.inspect(rawText);
    String status = result.violationType();

    if (sender instanceof ConsoleCommandSender) {
        String normDisplay = result.normalizedText().isEmpty() ? "[empty]" : result.normalizedText();

        plugin.getLogger().info("=== A.P.V.E. Inspection ===");
        plugin.getLogger().info("Input text: " + result.rawText());
        plugin.getLogger().info("Normalized text: " + normDisplay);
        plugin.getLogger().info("Check status: " + status);

        if (!"NONE".equals(status)) {
            plugin.getLogger().info("Matched word: " + result.matchedInputWord());
            plugin.getLogger().info("Dictionary sample: " + result.matchedDictWord());
            plugin.getLogger().info("Details: " + result.detail());
        }
        return;
    }

    String statusColor = switch (status) {
        case "MALICIOUS" -> checkColorMalicious;
        case "SUSPICIOUS" -> checkColorSuspicious;
        default -> checkColorNone;
    };

    String normDisplay = result.normalizedText().isEmpty() ? "&c[empty]" : "&a" + result.normalizedText();

    send(sender, checkHeaderMSG);
    send(sender, checkRawMSG, "{raw}", result.rawText());
    send(sender, checkNormalizedMSG, "{normalized}", normDisplay);
    send(sender, checkStatusMSG, "{status_color}", statusColor, "{status}", status);

    if (!"NONE".equals(status)) {
        send(sender, checkWordMSG, "{word}", result.matchedInputWord());
        send(sender, checkDictMSG, "{dict}", result.matchedDictWord());
        send(sender, checkDetailMSG, "{detail}", result.detail());
    }
}
   

    private void handleWarns(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sendHelp(sender);
            return;
        }

        String action = args[1].toLowerCase();
        String targetName = args[2];
        
        @SuppressWarnings("deprecation")
        OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
        UUID targetId = target.getUniqueId();

        switch (action) {
            case "show" -> {
                if (!sender.hasPermission(PERMISSION_SHOW)) {
                    send(sender, permFailMSG);
                    return;
                }
                
                int count = NetworkChatInterceptor.getWarns(targetId);
                String maxViolation = NetworkChatInterceptor.getHighestViolationType(targetId);
                
                send(sender, warnShowMSG, 
                        "{player}", targetName, 
                        "{amount}", String.valueOf(count), 
                        "{violation}", maxViolation);
            }
            case "remove" -> {
                if (!sender.hasPermission(PERMISSION_REMOVE)) {
                    send(sender, permFailMSG);
                    return;
                }
                if (args.length < 4) {
                    send(sender, invalidSyntaxMSG, "{usage}", "/apve warns remove <nickname> <amount>");
                    return;
                }
                try {
                    int amountToRemove = Integer.parseInt(args[3]);
                    int currentWarns = NetworkChatInterceptor.getWarns(targetId);
                    
                    if (currentWarns == 0) {
                        send(sender, noWarnsMSG, "{player}", targetName);
                        return;
                    }
                    
                    int leftover = NetworkChatInterceptor.removeWarns(targetId, amountToRemove);
                    
                    send(sender, removeWarnMSG, 
                            "{player}", targetName, 
                            "{amount}", String.valueOf(amountToRemove), 
                            "{left}", String.valueOf(leftover));
                            
                } catch (NumberFormatException e) {
                    send(sender, invalidNumberMSG, "{arg}", args[3]);
                }
            }
            case "clear" -> {
                if (!sender.hasPermission(PERMISSION_CLEAR)) {
                    send(sender, permFailMSG);
                    return;
                }
                NetworkChatInterceptor.clearWarns(targetId);
                send(sender, clearWarnMSG, "{player}", targetName);
            }
            default -> sendHelp(sender);
        }
    }

    private void handleUnknownCommand(CommandSender sender) {
        send(sender, helpViewReqMSG);
    }

    private void sendHelp(CommandSender sender) {
        if (!sender.hasPermission(PERMISSION_HELP)) {
            send(sender, permFailMSG);
            return;
        }

        for (String line : helpMSG) {
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', line));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            if (sender.hasPermission(PERMISSION_RELOAD)) completions.add("reload");
            if (sender.hasPermission(PERMISSION_SHOW) || 
                sender.hasPermission(PERMISSION_REMOVE) || 
                sender.hasPermission(PERMISSION_CLEAR)) {
                completions.add("warns");
            }
            if (sender.hasPermission(PERMISSION_NF_TOGGLE)) completions.add("notify");
            if (sender.hasPermission(PERMISSION_CHECK)) completions.add("check");
            completions.add("help");
            return filterPrefix(completions, args[0]);
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("warns")) {
            if (sender.hasPermission(PERMISSION_SHOW)) completions.add("show");
            if (sender.hasPermission(PERMISSION_REMOVE)) completions.add("remove");
            if (sender.hasPermission(PERMISSION_CLEAR)) completions.add("clear");
            return filterPrefix(completions, args[1]);
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("notify")) {
            if (sender.hasPermission(PERMISSION_NF_TOGGLE)) completions.add("toggle");
            return filterPrefix(completions, args[1]);
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("warns")) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                completions.add(player.getName());
            }
            return filterPrefix(completions, args[2]);
        }

        return List.of();
    }

    private void send(CommandSender sender, String template, String... replacements) {
        if (template == null || template.isEmpty()) return;

        String formatted = template;
        for (int i = 0; i < replacements.length; i += 2) {
            if (i + 1 < replacements.length) {
                formatted = formatted.replace(replacements[i], replacements[i + 1]);
            }
        }
        
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', formatted));
    }

    private List<String> filterPrefix(List<String> list, String prefix) {
        return list.stream()
                .filter(s -> s.toLowerCase().startsWith(prefix.toLowerCase()))
                .toList();
    }
}