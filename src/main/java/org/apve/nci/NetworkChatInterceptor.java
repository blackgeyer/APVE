package org.apve.nci;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientChatMessage;

import org.apve.nci.*;
import org.apve.engine.*;
import org.apve.etc.*;
import org.apve.nci.StructureModule.*;

import org.apve.config.FoolProof;
import org.apve.etc.NotificationManager;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;

public class NetworkChatInterceptor {

    private static volatile GlobalConfig cachedConfig;
    private static volatile ChatRulesCache cachedRules;
    private static ViolationHandler handler;
    private static MessageAnalyzer analyzer;

    private static final Set<String> TARGET_PLAYER_COMMANDS = Set.of("msg", "tell", "w", "whisper", "pm", "message", "m");

    public static boolean hasImmunity(Player player, ViolationType type) {
        return switch (type) {
            case INSULT -> player.hasPermission("apve.insult.immune");
            case FAMILY_INSULT -> player.hasPermission("apve.fam.insult.immune");
            case STAFF_INSULT -> player.hasPermission("apve.staff.insult.immune");
            case CAPS -> player.hasPermission("apve.caps.immune");
            case SPAM -> player.hasPermission("apve.spam.immune");
            case ADULT_CONTENT -> player.hasPermission("apve.adult.content.immune");
            case SOCIAL_MEDIA -> player.hasPermission("apve.social.immune");
            case ADVERTISEMENT -> player.hasPermission("apve.advertisement.immune");
        };
    }

    public static void loadConfig(FileConfiguration config, FoolProof.ValidationResult validation) {
        Map<String, Integer> priorityMap = validation.priorityMap();
        int staffPriority = priorityMap.getOrDefault("staff-insult", 6);
        int familyPriority = priorityMap.getOrDefault("family-insult", 5);
        int adPriority = priorityMap.getOrDefault("ad-dist", 8);
        int socPriority = priorityMap.getOrDefault("soc-media-dist", 7);
        int adultPriority = priorityMap.getOrDefault("adult-content", 4);
        int insultPriority = priorityMap.getOrDefault("insult", 3);
        int spamPriority = priorityMap.getOrDefault("spam", 2);
        int capsPriority = priorityMap.getOrDefault("caps", 1);

        ViolationType.STAFF_INSULT.setPriority(staffPriority);
        ViolationType.FAMILY_INSULT.setPriority(familyPriority);
        ViolationType.ADVERTISEMENT.setPriority(adPriority);
        ViolationType.SOCIAL_MEDIA.setPriority(socPriority);
        ViolationType.ADULT_CONTENT.setPriority(adultPriority);
        ViolationType.INSULT.setPriority(insultPriority);
        ViolationType.SPAM.setPriority(spamPriority);
        ViolationType.CAPS.setPriority(capsPriority);

        Map<ViolationType, ViolationRule> rulesMap = new EnumMap<>(ViolationType.class);

        rulesMap.put(ViolationType.INSULT, new ViolationRule(config.getBoolean("insult.is-enabled"), config.getBoolean("insult.punishment-is-enabled"), config.getString("insult.type").toLowerCase(), config.getString("insult.duration"), config.getString("insult.reason"), config.getBoolean("insult.blocking"), config.getString("insult.blocking-reason"), config.getBoolean("insult.censor"), config.getString("insult.censor-reason")));
        rulesMap.put(ViolationType.FAMILY_INSULT, new ViolationRule(config.getBoolean("family-insult.is-enabled"), config.getBoolean("family-insult.punishment-is-enabled"), config.getString("family-insult.type").toLowerCase(), config.getString("family-insult.duration"), config.getString("family-insult.reason"), config.getBoolean("family-insult.blocking"), config.getString("family-insult.blocking-reason"), config.getBoolean("family-insult.censor"), config.getString("family-insult.censor-reason")));
        rulesMap.put(ViolationType.STAFF_INSULT, new ViolationRule(config.getBoolean("staff-insult.is-enabled"), config.getBoolean("staff-insult.punishment-is-enabled"), config.getString("staff-insult.type").toLowerCase(), config.getString("staff-insult.duration"), config.getString("staff-insult.reason"), config.getBoolean("staff-insult.blocking"), config.getString("staff-insult.blocking-reason"), config.getBoolean("staff-insult.censor"), config.getString("staff-insult.censor-reason")));
        rulesMap.put(ViolationType.ADVERTISEMENT, new ViolationRule(config.getBoolean("ad-dist.is-enabled"), config.getBoolean("ad-dist.punishment-is-enabled"), config.getString("ad-dist.type").toLowerCase(), config.getString("ad-dist.duration"), config.getString("ad-dist.reason"), config.getBoolean("ad-dist.blocking"), config.getString("ad-dist.blocking-reason"), config.getBoolean("ad-dist.censor"), config.getString("ad-dist.censor-reason")));
        rulesMap.put(ViolationType.SOCIAL_MEDIA, new ViolationRule(config.getBoolean("soc-media-dist.is-enabled"), config.getBoolean("soc-media-dist.punishment-is-enabled"), config.getString("soc-media-dist.type").toLowerCase(), config.getString("soc-media-dist.duration"), config.getString("soc-media-dist.reason"), config.getBoolean("soc-media-dist.blocking"), config.getString("soc-media-dist.blocking-reason"), config.getBoolean("soc-media-dist.censor"), config.getString("soc-media-dist.censor-reason")));
        rulesMap.put(ViolationType.ADULT_CONTENT, new ViolationRule(config.getBoolean("adult-content.is-enabled"), config.getBoolean("adult-content.punishment-is-enabled"), config.getString("adult-content.type").toLowerCase(), config.getString("adult-content.duration"), config.getString("adult-content.reason"), config.getBoolean("adult-content.blocking"), config.getString("adult-content.blocking-reason"), config.getBoolean("adult-content.censor"), config.getString("adult-content.censor-reason")));
        rulesMap.put(ViolationType.SPAM, new ViolationRule(config.getBoolean("spam.is-enabled"), config.getBoolean("spam.punishment-is-enabled"), config.getString("spam.type").toLowerCase(), config.getString("spam.duration"), config.getString("spam.reason"), config.getBoolean("spam.blocking"), config.getString("spam.blocking-reason"), config.getBoolean("spam.censor"), config.getString("spam.censor-reason")));
        rulesMap.put(ViolationType.CAPS, new ViolationRule(config.getBoolean("caps.is-enabled"), config.getBoolean("caps.punishment-is-enabled"), config.getString("caps.type").toLowerCase(), config.getString("caps.duration"), config.getString("caps.reason"), config.getBoolean("caps.blocking"), config.getString("caps.blocking-reason"), config.getBoolean("caps.censor"), config.getString("caps.censor-reason")));

        cachedConfig = new GlobalConfig(
                config.getBoolean("console-log"), config.getBoolean("notifies"),
                config.getBoolean("warns.warns-is-enabled"), config.getBoolean("warns.warn-limit-is-enabled"),
                config.getInt("warns.warn-limit"), config.getString("warns.warn-message"),
                config.getString("warns.last-warn-message"), config.getBoolean("warns.temporary-warns"),
                config.getString("warns.warn-reset-time"), config.getInt("warns.warn_reset_count"), rulesMap
        );

        AhoCorasick ac = new AhoCorasick();
        for (String root : config.getStringList("bad-roots")) ac.addPattern(root, ViolationType.INSULT);
        for (String word : config.getStringList("insult-words")) ac.addPattern(word, ViolationType.INSULT);
        for (String root : config.getStringList("adult-roots")) ac.addPattern(root, ViolationType.ADULT_CONTENT);
        for (String word : config.getStringList("adult-words")) ac.addPattern(word, ViolationType.ADULT_CONTENT);
        for (String word : config.getStringList("ad-words")) ac.addPattern(word, ViolationType.ADVERTISEMENT);
        for (String word : config.getStringList("social")) ac.addPattern(word, ViolationType.SOCIAL_MEDIA);
        ac.build();

        Set<String> familyContextWords = new HashSet<>(config.getStringList("family-insult-words"));
        familyContextWords.addAll(config.getStringList("family-roots"));

        List<String> blockedDomainsList = new ArrayList<>(validation.blockedDomains());
        String domainRegex = "(?i)\\b[a-z0-9\\-_]+\\.(?:" + String.join("|", blockedDomainsList) + ")\\b";

        cachedRules = new ChatRulesCache(
                config.getDouble("thresholds.high"), config.getDouble("thresholds.medium"),
                config.getBoolean("audit-mode"), new HashSet<>(config.getStringList("allowed-words")),
                config.getStringList("insult-words"), familyContextWords,
                new HashSet<>(config.getStringList("staff-tituls")), new HashSet<>(config.getStringList("expressive-words")),
                config.getStringList("adult-words"), config.getStringList("social"),
                Pattern.compile(domainRegex), validation.interceptedCommands(),
                config.getBoolean("spam.is-enabled"), config.getInt("spam.max-similar-messages"),
                config.getLong("spam.time-window-seconds") * 1000L, config.getDouble("spam.similarity-threshold"),
                config.getBoolean("caps.is-enabled"), config.getInt("caps.min-message-length"),
                config.getInt("caps.min-caps-percentage"), ac
        );
    }

    public static void register(JavaPlugin plugin, NotificationManager notificationManager, PunishmentManager punishmentManager, Logger suspiciousLogger, Logger maliciousLogger, FoolProof.ValidationResult validation) {
        loadConfig(plugin.getConfig(), validation);

        handler = new ViolationHandler();
        analyzer = new MessageAnalyzer(cachedRules, handler);

        PacketEvents.getAPI().getEventManager().registerListener(
                new PacketListenerAbstract(PacketListenerPriority.HIGH) {
                    @Override
                    public void onPacketReceive(PacketReceiveEvent event) {
                        if (event.getPacketType() != PacketType.Play.Client.CHAT_MESSAGE) return;

                        Player player = Bukkit.getPlayer(event.getUser().getUUID());
                        if (player == null || punishmentManager.isMuted(player.getUniqueId())) return;

                        String rawText = new WrapperPlayClientChatMessage(event).getMessage();
                        if (rawText.startsWith("/")) return;

                        AnalysisResult result = analyzer.analyze(player.getUniqueId(), rawText, player, suspiciousLogger, cachedConfig.consoleLog(), NetworkChatInterceptor::hasImmunity);
                        if (result != null) {
                            handler.handleChatViolation(plugin, player, punishmentManager, notificationManager, event, rawText, result, cachedConfig, cachedRules.auditMode(), maliciousLogger);
                        }
                    }
                }
        );

        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler(priority = EventPriority.HIGHEST)
            public void onAsyncChatApve(AsyncPlayerChatEvent event) {
                UUID uuid = event.getPlayer().getUniqueId();
                if (handler.checkAndRemoveBlock(uuid)) {
                    if (!event.isCancelled()) event.setCancelled(true);
                    return;
                }
                String censoredMsg = handler.getAndRemoveCensor(uuid);
                if (censoredMsg != null && !event.isCancelled()) {
                    event.setMessage(censoredMsg);
                }
            }

            @EventHandler(priority = EventPriority.HIGHEST)
            public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent event) {
                if (event.isCancelled()) return;

                Player player = event.getPlayer();
                if (punishmentManager.isMuted(player.getUniqueId())) return;

                String fullCommand = event.getMessage();
                String rawText = extractRawTextFromCommand(fullCommand, cachedRules.interceptedCommands());
                if (rawText.isEmpty()) return;

                AnalysisResult result = analyzer.analyze(player.getUniqueId(), rawText, player, suspiciousLogger, cachedConfig.consoleLog(), NetworkChatInterceptor::hasImmunity);
                if (result != null) {
                    handler.handleCommandViolation(plugin, player, punishmentManager, notificationManager, event, fullCommand, rawText, result, cachedConfig, cachedRules.auditMode(), maliciousLogger);
                }
            }

            @EventHandler
            public void onQuit(PlayerQuitEvent event) {
                handler.cleanupPlayer(event.getPlayer().getUniqueId());
            }
        }, plugin);
    }

    private static String extractRawTextFromCommand(String fullCommand, Set<String> interceptedCommands) {
        if (fullCommand == null || fullCommand.isBlank()) return "";
        String commandStr = fullCommand.trim();
        if (commandStr.startsWith("/")) commandStr = commandStr.substring(1).trim();

        String[] parts = commandStr.split("\\s+", 2);
        String cmd = parts[0].toLowerCase(Locale.ROOT);
        String args = parts.length > 1 ? parts[1] : "";

        boolean isIntercepted = false;
        for (String ic : interceptedCommands) {
            String cleanIc = ic.startsWith("/") ? ic.substring(1).toLowerCase(Locale.ROOT) : ic.toLowerCase(Locale.ROOT);
            if (cleanIc.equals(cmd)) {
                isIntercepted = true;
                break;
            }
        }

        if (!isIntercepted) return "";
        return extractMessageFromArgs(cmd, args);
    }

    private static String extractMessageFromArgs(String command, String args) {
        if (args.isBlank()) return "";
        String cleanCmd = command.startsWith("/") ? command.substring(1).toLowerCase(Locale.ROOT) : command.toLowerCase(Locale.ROOT);
        if (TARGET_PLAYER_COMMANDS.contains(cleanCmd)) {
            String[] parts = args.trim().split("\\s+", 2);
            return parts.length > 1 ? parts[1] : "";
        }
        return args.trim();
    }

    public static String colorize(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    public static int getWarns(UUID uuid) { return handler != null ? handler.getWarns(uuid) : 0; }
    public static int removeWarns(UUID uuid, int amount) { return handler != null ? handler.removeWarns(uuid, amount) : 0; }
    public static void clearWarns(UUID uuid) { if (handler != null) handler.clearWarns(uuid); }
    public static String getHighestViolationType(UUID uuid) { return handler != null ? handler.getHighestViolationType(uuid) : "NONE"; }
    public static InspectionResult inspect(String rawText) { return analyzer != null ? analyzer.inspect(rawText) : null; }
}
