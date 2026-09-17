package org.apve;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientChatCommand;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientChatMessage;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.logging.Logger;
import java.util.regex.Pattern;

public class NetworkChatInterceptor {

    public enum ViolationType {
        INSULT, FAMILY_INSULT, STAFF_INSULT, ADVERTISEMENT, SOCIAL_MEDIA, ADULT_CONTENT, SPAM, CAPS;
        private int priority;
        public int getPriority() { return priority; }
        public void setPriority(int priority) { this.priority = priority; }
    }

    private record StoredViolation(ViolationType type, ViolationRule rule, String reasonDetail, String badWord) {}

    public static class AhoCorasick {
        public record PatternInfo(String pattern, ViolationType type) {}
        public record Match(String pattern, ViolationType type, int startIndex, int endIndex) {}

        private static class Node {
            final Map<Character, Node> children = new HashMap<>();
            Node fail;
            final List<PatternInfo> outputs = new ArrayList<>();
        }

        private final Node root = new Node();

        public void addPattern(String pattern, ViolationType type) {
            Node current = root;
            for (char ch : pattern.toLowerCase().toCharArray()) {
                current = current.children.computeIfAbsent(ch, k -> new Node());
            }
            current.outputs.add(new PatternInfo(pattern, type));
        }

        public void build() {
            Queue<Node> queue = new LinkedList<>();
            for (Node child : root.children.values()) {
                child.fail = root;
                queue.add(child);
            }
            while (!queue.isEmpty()) {
                Node current = queue.poll();
                for (Map.Entry<Character, Node> entry : current.children.entrySet()) {
                    char ch = entry.getKey();
                    Node child = entry.getValue();
                    Node fallback = current.fail;
                    while (fallback != null && !fallback.children.containsKey(ch)) {
                        fallback = fallback.fail;
                    }
                    child.fail = (fallback != null) ? fallback.children.get(ch) : root;
                    child.outputs.addAll(child.fail.outputs);
                    queue.add(child);
                }
            }
        }

        public List<Match> search(String text) {
            List<Match> results = new ArrayList<>();
            Node current = root;
            String lowerText = text.toLowerCase();
            for (int i = 0; i < lowerText.length(); i++) {
                char ch = lowerText.charAt(i);
                while (current != null && !current.children.containsKey(ch)) {
                    current = current.fail;
                }
                current = (current != null) ? current.children.get(ch) : root;
                if (current != null) {
                    for (PatternInfo info : current.outputs) {
                        results.add(new Match(info.pattern(), info.type(), i - info.pattern().length() + 1, i + 1));
                    }
                } else {
                    current = root;
                }
            }
            return results;
        }
    }

    private static class SpamEntry {
        final String normalizedText;
        final long timestamp;
        SpamEntry(String normalizedText, long timestamp) {
            this.normalizedText = normalizedText;
            this.timestamp = timestamp;
        }
    }

    private record ViolationRule(boolean enabled, boolean punishEnabled, String type, String duration, String reason, boolean block, String blockReason, boolean censor, String censorReason) {}

    private record GlobalConfig(
        boolean consoleLog,
        boolean notifiesEnabled,
        boolean warnsIsEnabled,
        boolean warnLimitIsEnabled,
        int warnLimit,
        String warnMessage,
        String lastWarnMessage,
        boolean tempWarns,
        String warnResetTime,
        int warnResetCount,
        Map<ViolationType, ViolationRule> rules
    ) {}

    private record ChatRulesCache(
        double highThreshold, double mediumThreshold, boolean auditMode, Set<String> allowedWords,
        List<String> insultWords, Set<String> familyWords, Set<String> staffTitles, Set<String> expressiveWords, List<String> adultWords,
        List<String> socialWords, Pattern domainPattern, Set<String> interceptedCommands, boolean spamModuleEnabled,
        int spamMaxCount, long spamWindowMs, double spamSimThreshold, boolean capsModuleEnabled, int capsMinLength,
        int capsMinPct, AhoCorasick ahoCorasick) {}

    private static volatile GlobalConfig cachedConfig;
    private static volatile ChatRulesCache cachedRules;

    private static final Map<UUID, Integer> warnCounts = new ConcurrentHashMap<>();
    private static final Map<UUID, StoredViolation> highestViolations = new ConcurrentHashMap<>();
    private static final Map<UUID, BukkitTask> resetTasks = new ConcurrentHashMap<>();
    private static final Map<UUID, Deque<SpamEntry>> spamHistory = new ConcurrentHashMap<>();
    private static final Set<UUID> externallyMutedPlayers = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Set<UUID> pendingBlockMessages = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Map<UUID, String> pendingCensorMessages = new ConcurrentHashMap<>();
    private static final Set<UUID> apveCancelledMessages = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private static final Set<String> PERSONAL_PRONOUNS = Set.of("ty", "vy", "on", "ona", "oni", "tebe", "tebya", "toboy", "vas", "vam", "emu", "ey", "tvoya", "tvoyu", "tvoy", "tvoego", "tvoemu", "tvoim", "vashu", "vashe", "vash", "ego", "eyo", "ih", "you", "your", "he", "she", "they", "his", "her", "their", "u");
    private static final Pattern IP_PATTERN = Pattern.compile("\\b(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)[\\._,\\s\\-]){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\b");
    private static final Pattern NON_LETTER_PATTERN = Pattern.compile("[^a-zA-Z\u0400-\u04FF]");
    private static final Pattern NEWLINE_PATTERN = Pattern.compile("\n");

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
        ViolationType.STAFF_INSULT.setPriority(priorityMap.getOrDefault("staff-insult", 6));
        ViolationType.FAMILY_INSULT.setPriority(priorityMap.getOrDefault("family-insult", 5));
        ViolationType.ADVERTISEMENT.setPriority(priorityMap.getOrDefault("ad-dist", 8));
        ViolationType.SOCIAL_MEDIA.setPriority(priorityMap.getOrDefault("soc-media-dist", 7));
        ViolationType.ADULT_CONTENT.setPriority(priorityMap.getOrDefault("adult-content", 4));
        ViolationType.INSULT.setPriority(priorityMap.getOrDefault("insult", 3));
        ViolationType.SPAM.setPriority(priorityMap.getOrDefault("spam", 2));
        ViolationType.CAPS.setPriority(priorityMap.getOrDefault("caps", 1));

        Map<ViolationType, ViolationRule> rulesMap = new EnumMap<>(ViolationType.class);

        rulesMap.put(ViolationType.INSULT, new ViolationRule(config.getBoolean("insult.is-enabled"), config.getBoolean("insult.punishment-is-enabled"), config.getString("insult.type").toLowerCase(), config.getString("insult.duration"), config.getString("insult.reason"), config.getBoolean("insult.blocking"), config.getString("insult.blocking-reason"), config.getBoolean("insult.censor"), config.getString("insult.censor-reason")));
        rulesMap.put(ViolationType.FAMILY_INSULT, new ViolationRule(config.getBoolean("family-insult.is-enabled"), config.getBoolean("family-insult.punishment-is-enabled"), config.getString("family-insult.type").toLowerCase(), config.getString("family-insult.duration"), config.getString("family-insult.reason"), config.getBoolean("family-insult.blocking"), config.getString("family-insult.blocking-reason"), config.getBoolean("family-insult.censor"), config.getString("family-insult.censor-reason")));
        rulesMap.put(ViolationType.STAFF_INSULT, new ViolationRule(config.getBoolean("staff-insult.is-enabled"), config.getBoolean("staff-insult.punishment-is-enabled"), config.getString("staff-insult.type").toLowerCase(), config.getString("staff-insult.duration"), config.getString("staff-insult.reason"), config.getBoolean("staff-insult.blocking"), config.getString("staff-insult.blocking-reason"), config.getBoolean("staff-insult.censor"), config.getString("staff-insult.censor-reason")));
        rulesMap.put(ViolationType.ADVERTISEMENT, new ViolationRule(config.getBoolean("ad-dist.is-enabled"), config.getBoolean("ad-dist.punishment-is-enabled"), config.getString("ad-dist.type").toLowerCase(), config.getString("ad-dist.duration"), config.getString("ad-dist.reason"), config.getBoolean("ad-dist.blocking"), config.getString("ad-dist.blocking-reason"), config.getBoolean("ad-dist.censor"), config.getString("ad-dist.censor-reason")));
        rulesMap.put(ViolationType.SOCIAL_MEDIA, new ViolationRule(config.getBoolean("soc-media-dist.is-enabled"), config.getBoolean("soc-media-dist.punishment-is-enabled"), config.getString("soc-media-dist.type").toLowerCase(), config.getString("soc-media-dist.duration"), config.getString("soc-media-dist.reason"), config.getBoolean("soc-media-dist.blocking"), config.getString("soc-media-dist.blocking-reason"), config.getBoolean("soc-media-dist.censor"), config.getString("soc-media-dist.censor-reason")));
        rulesMap.put(ViolationType.ADULT_CONTENT, new ViolationRule(config.getBoolean("adult-content.is-enabled"), config.getBoolean("adult-content.punishment-is-enabled"), config.getString("adult-content.type").toLowerCase(), config.getString("adult-content.duration"), config.getString("adult-content.reason"), config.getBoolean("adult-content.blocking"), config.getString("adult-content.blocking-reason"), config.getBoolean("adult-content.censor"), config.getString("adult-content.censor-reason")));
        rulesMap.put(ViolationType.SPAM, new ViolationRule(config.getBoolean("spam.is-enabled"), config.getBoolean("spam.punishment-is-enabled"), config.getString("spam.type").toLowerCase(), config.getString("spam.duration"), config.getString("spam.reason"), config.getBoolean("spam.blocking"), config.getString("spam.blocking-reason"), config.getBoolean("spam.censor"), config.getString("spam.censor-reason")));
        rulesMap.put(ViolationType.CAPS, new ViolationRule(config.getBoolean("caps.is-enabled"), config.getBoolean("caps.punishment-is-enabled"), config.getString("caps.type").toLowerCase(), config.getString("caps.duration"), config.getString("caps.reason"), config.getBoolean("caps.blocking"), config.getString("caps.blocking-reason"), config.getBoolean("caps.censor"), config.getString("caps.censor-reason")));

        boolean consoleLog = config.getBoolean("console-log");
        boolean notifiesEnabled = config.getBoolean("notifies");
        boolean warnsIsEnabled = config.getBoolean("warns.warns-is-enabled");
        boolean warnLimitIsEnabled = config.getBoolean("warns.warn-limit-is-enabled");
        int warnLimit = config.getInt("warns.warn-limit");
        String warnMessage = config.getString("warns.warn-message");
        String lastWarnMessage = config.getString("warns.last-warn-message");
        boolean tempWarns = config.getBoolean("warns.temporary-warns");
        String warnResetTime = config.getString("warns.warn-reset-time");
        int warnResetCount = config.getInt("warns.warn_reset_count");

        cachedConfig = new GlobalConfig(
            consoleLog,
            notifiesEnabled,
            warnsIsEnabled,
            warnLimitIsEnabled,
            warnLimit,
            warnMessage,
            lastWarnMessage,
            tempWarns,
            warnResetTime,
            warnResetCount,
            rulesMap
        );

        AhoCorasick ac = new AhoCorasick();

        List<String> badRoots = config.getStringList("bad-roots");
        for (String root : badRoots) ac.addPattern(root, ViolationType.INSULT);

        List<String> insultWords = config.getStringList("insult-words");
        for (String word : insultWords) ac.addPattern(word, ViolationType.INSULT);

        List<String> adultRoots = config.getStringList("adult-roots");
        for (String root : adultRoots) ac.addPattern(root, ViolationType.ADULT_CONTENT);

        List<String> adultWords = config.getStringList("adult-words");
        for (String word : adultWords) ac.addPattern(word, ViolationType.ADULT_CONTENT);

        List<String> adWords = config.getStringList("ad-words");
        for (String word : adWords) ac.addPattern(word, ViolationType.ADVERTISEMENT);

        List<String> socialWords = config.getStringList("social");
        for (String word : socialWords) ac.addPattern(word, ViolationType.SOCIAL_MEDIA);

        ac.build();

        List<String> familyInsultList = config.getStringList("family-insult-words");
        List<String> familyRootsList = config.getStringList("family-roots");
        Set<String> familyContextWords = new HashSet<>(familyInsultList);
        familyContextWords.addAll(familyRootsList);

        List<String> staffTitulsList = config.getStringList("staff-tituls");
        Set<String> staffTitles = new HashSet<>(staffTitulsList);

        List<String> allowedWordsList = config.getStringList("allowed-words");
        List<String> expressiveWordsList = config.getStringList("expressive-words");

        double highThreshold = config.getDouble("thresholds.high");
        double mediumThreshold = config.getDouble("thresholds.medium");
        boolean auditMode = config.getBoolean("audit-mode");

        String domainRegex = "(?i)\\b[a-z0-9\\-_]+\\.(?:" + String.join("|", validation.blockedDomains()) + ")\\b";
        Pattern domainPattern = Pattern.compile(domainRegex);

        boolean spamIsEnabled = config.getBoolean("spam.is-enabled");
        int spamMaxSimilar = config.getInt("spam.max-similar-messages");
        long spamWindowMs = config.getLong("spam.time-window-seconds") * 1000L;
        double spamSimThreshold = config.getDouble("spam.similarity-threshold");

        boolean capsIsEnabled = config.getBoolean("caps.is-enabled");
        int capsMinLength = config.getInt("caps.min-message-length");
        int capsMinPct = config.getInt("caps.min-caps-percentage");

        cachedRules = new ChatRulesCache(
            highThreshold, mediumThreshold, auditMode,
            new HashSet<>(allowedWordsList), insultWords,
            familyContextWords, staffTitles, new HashSet<>(expressiveWordsList), adultWords,
            socialWords, domainPattern, validation.interceptedCommands(), spamIsEnabled,
            spamMaxSimilar, spamWindowMs, spamSimThreshold, capsIsEnabled,
            capsMinLength, capsMinPct, ac
        );
    }

    public static void register(JavaPlugin plugin, NotificationManager notificationManager, PunishmentManager punishmentManager, Logger suspiciousLogger, Logger maliciousLogger, FoolProof.ValidationResult validation) {
        loadConfig(plugin.getConfig(), validation);

        PacketEvents.getAPI().getEventManager().registerListener(
            new PacketListenerAbstract(PacketListenerPriority.HIGH) {
                @Override
                public void onPacketReceive(PacketReceiveEvent event) {

                    boolean isChatMsg = event.getPacketType() == PacketType.Play.Client.CHAT_MESSAGE;
                    boolean isChatCmd = event.getPacketType() == PacketType.Play.Client.CHAT_COMMAND;
                    if (!isChatMsg && !isChatCmd) return;

                    Player player = Bukkit.getPlayer(event.getUser().getUUID());

                    final ChatRulesCache rules = cachedRules;
                    final GlobalConfig cfg = cachedConfig;

                    String rawText = extractRawText(event, isChatMsg, isChatCmd, rules.interceptedCommands());
                    if (rawText.isEmpty()) return;

                    String normalized = TextNormalizer.normalize(rawText);
                    String fullyCompressed = TextNormalizer.removeSpaces(normalized);
                    int[] spaceMap = TextNormalizer.createSpaceMapping(normalized);

                    ViolationType spamCandidate = null;
                    if (rules.spamModuleEnabled() && checkAndRecordSpam(player.getUniqueId(), normalized, rules.spamMaxCount(), rules.spamWindowMs(), rules.spamSimThreshold())) {
                        spamCandidate = ViolationType.SPAM;
                    }

                    ViolationType capsCandidate = null;
                    if (rules.capsModuleEnabled() && isCaps(rawText, rules.capsMinLength(), rules.capsMinPct())) {
                        capsCandidate = ViolationType.CAPS;
                    }

                    String domainNormalized = TextNormalizer.normalizeForDomain(rawText);
                    boolean hasLinkBypass = domainNormalized.contains("http") || domainNormalized.contains("www");

                    if (containsIP(rawText) || rules.domainPattern().matcher(domainNormalized).find() || hasLinkBypass) {
                        ViolationType detected = ViolationType.ADVERTISEMENT;
                        String reason = "IP/Link/Domain";

                        for (String social : rules.socialWords()) {
                            if (domainNormalized.contains(social.toLowerCase())) {
                                detected = ViolationType.SOCIAL_MEDIA;
                                reason = "Social Media Link: " + social;
                                break;
                            }
                        }

                        if (!hasImmunity(player, detected)) {
                            dispatch(plugin, player, punishmentManager, notificationManager, event, rawText, rawText, detected, reason, cfg, isChatMsg, rules.auditMode(), suspiciousLogger, maliciousLogger);
                            return;
                        }
                    }

                    List<AhoCorasick.Match> acMatches = rules.ahoCorasick().search(fullyCompressed);
                    boolean acPunished = false;

                    for (AhoCorasick.Match match : acMatches) {
                        String pattern = match.pattern();
                        int c_start = match.startIndex();
                        int c_end = match.endIndex();

                        int n_start = spaceMap[c_start];
                        int n_end = spaceMap[c_end - 1] + 1;
                        String span = normalized.substring(n_start, n_end);

                        boolean isValidViolation = true;

                        if (span.contains(" ")) {
                            boolean cutsWord = false;

                            if (n_start > 0 && Character.isLetter(normalized.charAt(n_start - 1))) cutsWord = true;
                            if (n_end < normalized.length() && Character.isLetter(normalized.charAt(n_end))) cutsWord = true;

                            if (cutsWord) {
                                isValidViolation = false;
                            } else {
                                String[] parts = span.split("\\s+");
                                boolean allAllowed = true;
                                for (String part : parts) {
                                    if (part.isEmpty()) continue;
                                    if (!rules.allowedWords().contains(part)) {
                                        allAllowed = false;
                                        break;
                                    }
                                }
                                if (allAllowed) isValidViolation = false;
                            }
                        }

                        if (isValidViolation) {
                            ViolationType finalType = match.type();

                            if (finalType == ViolationType.INSULT) {
                                String[] normWords = normalized.split("\\s+");
                                int tokenStartIdx = 0;
                                int charCount = 0;
                                for (int j = 0; j < normWords.length; j++) {
                                    charCount += normWords[j].length();
                                    if (charCount > n_start) {
                                        tokenStartIdx = j;
                                        break;
                                    }
                                    charCount++;
                                }

                                if (hasStaffContext(normWords, rules.staffTitles(), tokenStartIdx)) {
                                    finalType = ViolationType.STAFF_INSULT;
                                } else if (hasFamilyContext(normWords, rules.familyWords(), tokenStartIdx)) {
                                    finalType = ViolationType.FAMILY_INSULT;
                                }
                            }

                            if (hasImmunity(player, finalType)) {
                                continue;
                            }

                            dispatch(plugin, player, punishmentManager, notificationManager, event, rawText, pattern, finalType, "Found via AC Anti-Bypass: " + pattern, cfg, isChatMsg, rules.auditMode(), suspiciousLogger, maliciousLogger);
                            acPunished = true;
                            break;
                        }
                    }

                    if (acPunished) return;

                    String[] normWords = normalized.split("\\s+");
                    String[] rawWords = rawText.toLowerCase().split("\\s+");

                    ViolationType detectedType = null;
                    String matchedWord = "";
                    String reasonDetail = "";
                    String rawMatchWord = "";
                    double maxSimilarity = 0.0;
                    String suspectedInsult = "";

                    outer:
                    for (int i = 0; i < normWords.length; i++) {
                        final String word = normWords[i];
                        final String rawWord = (i < rawWords.length) ? rawWords[i] : word;

                        if (word.isEmpty() || rules.allowedWords().contains(word)) continue;

                        ViolationRule adultRule = cfg.rules().get(ViolationType.ADULT_CONTENT);
                        if (adultRule.enabled() && !hasImmunity(player, ViolationType.ADULT_CONTENT)) {
                            for (String adult : rules.adultWords()) {
                                if (SimilarityChecker.getSimilarityRatio(word, adult, 0.0) >= rules.highThreshold()) {
                                    detectedType = ViolationType.ADULT_CONTENT;
                                    matchedWord = adult;
                                    reasonDetail = "Adult content: " + adult;
                                    rawMatchWord = rawWord;
                                    break outer;
                                }
                            }
                        }

                        for (String insult : rules.insultWords()) {
                            double sim = SimilarityChecker.getSimilarityRatio(word, insult, 0.0);
                            if (sim > maxSimilarity) {
                                maxSimilarity = sim;
                                suspectedInsult = insult;
                                rawMatchWord = rawWord;
                                if (maxSimilarity >= rules.highThreshold()) break;
                            }
                        }

                        if (maxSimilarity >= rules.highThreshold()) {
                            ViolationType candType = ViolationType.INSULT;
                            if (hasStaffContext(normWords, rules.staffTitles(), i)) {
                                candType = ViolationType.STAFF_INSULT;
                            } else if (hasFamilyContext(normWords, rules.familyWords(), i)) {
                                candType = ViolationType.FAMILY_INSULT;
                            }

                            if (!hasImmunity(player, candType)) {
                                matchedWord = suspectedInsult;
                                reasonDetail = "Insult (fuzzy): " + matchedWord;
                                detectedType = candType;
                                break outer;
                            }
                        }

                        if (rules.expressiveWords().contains(word)) {
                            boolean targetedAtPronoun =
                                    (i > 0 && PERSONAL_PRONOUNS.contains(normWords[i - 1])) ||
                                    (i < normWords.length - 1 && PERSONAL_PRONOUNS.contains(normWords[i + 1]));
                            boolean targetedAtStaff = hasStaffContext(normWords, rules.staffTitles(), i);
                            boolean targetedAtFamily = hasFamilyContext(normWords, rules.familyWords(), i);

                            if (targetedAtPronoun || targetedAtStaff || targetedAtFamily) {
                                ViolationType candType = ViolationType.INSULT;
                                if (targetedAtStaff) {
                                    candType = ViolationType.STAFF_INSULT;
                                } else if (targetedAtFamily) {
                                    candType = ViolationType.FAMILY_INSULT;
                                }

                                if (!hasImmunity(player, candType)) {
                                    matchedWord = word;
                                    rawMatchWord = rawWord;
                                    reasonDetail = "Targeted profanity: " + word;
                                    detectedType = candType;
                                    break outer;
                                }
                            }
                        }
                    }

                    if (detectedType != null) {
                        dispatch(plugin, player, punishmentManager, notificationManager, event, rawText, rawMatchWord, detectedType, reasonDetail, cfg, isChatMsg, rules.auditMode(), suspiciousLogger, maliciousLogger);
                    } else if (spamCandidate != null && !hasImmunity(player, ViolationType.SPAM)) {
                        dispatch(plugin, player, punishmentManager, notificationManager, event, rawText, rawText, ViolationType.SPAM, "Spam", cfg, isChatMsg, rules.auditMode(), suspiciousLogger, maliciousLogger);
                    } else if (capsCandidate != null && !hasImmunity(player, ViolationType.CAPS)) {
                        dispatch(plugin, player, punishmentManager, notificationManager, event, rawText, rawText, ViolationType.CAPS, "Caps", cfg, isChatMsg, rules.auditMode(), suspiciousLogger, maliciousLogger);
                    } else if (maxSimilarity >= rules.mediumThreshold() && maxSimilarity < rules.highThreshold()) {
                        if (rules.auditMode() || cfg.consoleLog()) {
                            String prefix = rules.auditMode() ? "[AUDIT-MODE | SUSPICIOUS]" : "[SUSPICIOUS]";
                            suspiciousLogger.warning(String.format(
                                    "%s Player: %s | Text: '%s' | Suspicion: '%s' (%.0f%%)",
                                    prefix, player.getName(), rawText, suspectedInsult, maxSimilarity * 100));
                        }
                    }
                }
            }
        );

        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler(priority = EventPriority.HIGHEST)
            public void onAsyncChatApve(AsyncPlayerChatEvent event) {
                UUID uuid = event.getPlayer().getUniqueId();
                if (pendingBlockMessages.remove(uuid)) {
                    if (!event.isCancelled()) {
                        event.setCancelled(true);
                        apveCancelledMessages.add(uuid);
                    }
                    return;
                }
                String censoredMsg = pendingCensorMessages.remove(uuid);
                if (censoredMsg != null && !event.isCancelled()) {
                    event.setMessage(censoredMsg);
                }
            }

            @EventHandler(priority = EventPriority.MONITOR)
            public void onAsyncChatMonitor(AsyncPlayerChatEvent event) {
                UUID uuid = event.getPlayer().getUniqueId();
                if (event.isCancelled()) {
                    if (!apveCancelledMessages.remove(uuid)) {
                        externallyMutedPlayers.add(uuid);
                    }
                } else {
                    apveCancelledMessages.remove(uuid);
                    externallyMutedPlayers.remove(uuid);
                }
            }

            @EventHandler
            public void onQuit(PlayerQuitEvent event) {
                UUID uuid = event.getPlayer().getUniqueId();
                externallyMutedPlayers.remove(uuid);
                pendingBlockMessages.remove(uuid);
                pendingCensorMessages.remove(uuid);
                apveCancelledMessages.remove(uuid);
            }
        }, plugin);
    }

    public static void dispatch(Plugin plugin, Player player, PunishmentManager pm, NotificationManager nm,
                                PacketReceiveEvent event, String rawText, String badWord,
                                ViolationType type, String reasonDetail, GlobalConfig cfg,
                                boolean isChatMsg, boolean auditMode,
                                Logger suspiciousLogger, Logger maliciousLogger) {

        if (hasImmunity(player, type)) return;

        ViolationRule rule = cfg.rules().get(type);
        if (!rule.enabled()) return;

        if (auditMode) {
            maliciousLogger.warning(String.format(
                "[AUDIT-MODE | MALICIOUS] Player: %s | Violation: %s | Detail: %s | Word: '%s' | Message: '%s'",
                player.getName(), type.name(), reasonDetail, badWord, rawText
            ));
            return;
        }

        if (cfg.consoleLog()) {
            maliciousLogger.warning(String.format(
                "[MALICIOUS] Player: %s | Violation: %s | Detail: %s | Word: '%s' | Message: '%s'",
                player.getName(), type.name(), reasonDetail, badWord, rawText
            ));
        }

        if (cfg.notifiesEnabled()) {
            nm.sendViolationAlert(player, type, badWord, rawText);
        }

        String finalMessage = rawText;
        if (rule.censor() && !rule.block()) {
            if (type == ViolationType.CAPS) {
                finalMessage = finalMessage.toLowerCase();
            } else if (!badWord.isEmpty()) {
                finalMessage = finalMessage.replaceAll("(?i)" + Pattern.quote(badWord), "***");
                if (finalMessage.equals(rawText)) finalMessage = "***";
            } else {
                finalMessage = "***";
            }
        }
        final String fMsg = finalMessage;

        if (isChatMsg) {
            if (rule.block()) pendingBlockMessages.add(player.getUniqueId());
            else if (rule.censor()) pendingCensorMessages.put(player.getUniqueId(), fMsg);
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (externallyMutedPlayers.contains(player.getUniqueId())) return;
            boolean executePunishment = true;
            String warnMsgToSend = null;

            if (cfg.warnsIsEnabled() && cfg.warnLimitIsEnabled()) {
                UUID uuid = player.getUniqueId();
                StoredViolation currentViolation = new StoredViolation(type, rule, reasonDetail, badWord);

                highestViolations.compute(uuid, (k, old) -> {
                    if (old == null || type.getPriority() > old.type().getPriority()) return currentViolation;
                    return old;
                });

                int warns = warnCounts.getOrDefault(uuid, 0) + 1;
                warnCounts.put(uuid, warns);

                if (cfg.tempWarns()) {
                    long resetTicks = parseTimeToTicks(cfg.warnResetTime());
                    BukkitTask old = resetTasks.remove(uuid);
                    if (old != null) old.cancel();

                    BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        int current = warnCounts.getOrDefault(uuid, 0);
                        int newCount = Math.max(0, current - cfg.warnResetCount());
                        if (newCount == 0) {
                            warnCounts.remove(uuid);
                            highestViolations.remove(uuid);
                        } else {
                            warnCounts.put(uuid, newCount);
                        }
                        highestViolations.remove(uuid);
                        spamHistory.remove(uuid);
                        resetTasks.remove(uuid);
                    }, resetTicks);
                    resetTasks.put(uuid, task);
                }

                if (warns <= cfg.warnLimit()) {
                    executePunishment = false;
                    warnMsgToSend = warns < cfg.warnLimit() ? cfg.warnMessage() : cfg.lastWarnMessage();
                    if (cfg.consoleLog()) {
                        plugin.getLogger().info(logLine("WARN", warns + "/" + cfg.warnLimit(), player.getName(), reasonDetail, badWord));
                    }
                } else {
                    warnCounts.put(uuid, 0);
                }
            }

            if (rule.block()) {
                if (!externallyMutedPlayers.contains(player.getUniqueId())) sendMultilineMessage(player, rule.blockReason());
            } else if (rule.censor() && isChatMsg) {
                if (!externallyMutedPlayers.contains(player.getUniqueId())) sendMultilineMessage(player, rule.censorReason());
            }

            if (warnMsgToSend != null && !externallyMutedPlayers.contains(player.getUniqueId())) {
                sendMultilineMessage(player, warnMsgToSend);
            }

            if (executePunishment && rule.punishEnabled()) {
                StoredViolation heaviest = (cfg.warnsIsEnabled() && cfg.warnLimitIsEnabled()) ? highestViolations.remove(player.getUniqueId()) : null;
                if (heaviest != null) {
                    applyPunishment(plugin, player, pm, heaviest.rule(), heaviest.reasonDetail(), heaviest.badWord(), cfg.consoleLog());
                } else {
                    applyPunishment(plugin, player, pm, rule, reasonDetail, badWord, cfg.consoleLog());
                }
            }
        });
    }

    public static String colorize(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    private static void applyPunishment(Plugin plugin, Player player, PunishmentManager pm, ViolationRule rule, String detail, String word, boolean consoleLog) {
        String type = rule.type().toLowerCase(java.util.Locale.ROOT).trim();
        switch (type) {
            case "mute" -> pm.mutePlayer(player.getUniqueId(), rule.reason(), rule.duration());
            case "ban" -> pm.banPlayer(player.getUniqueId(), rule.reason(), rule.duration());
            case "banip" -> {
                String ip = player.getAddress().getAddress().getHostAddress();
                pm.banipPlayer(ip, rule.reason(), rule.duration());
            }
            case "kick" -> pm.kickPlayer(player.getUniqueId(), rule.reason());
            case "none" -> { return; }
        }
        if (consoleLog) {
            plugin.getLogger().info(logLine(type.toUpperCase(java.util.Locale.ROOT), rule.duration(), player.getName(), detail, word));
        }
    }

    private static boolean hasStaffContext(String[] normWords, Set<String> staffTitles, int insultIndex) {
        for (int i = 0; i < normWords.length; i++) {
            if (i == insultIndex) continue;
            if (Math.abs(i - insultIndex) > 5) continue;
            String word = normWords[i];
            for (String st : staffTitles) {
                if (word.equals(st) || word.startsWith(st)) return true;
            }
        }
        return false;
    }

    private static boolean hasFamilyContext(String[] normWords, Set<String> familyWords, int insultIndex) {
        for (int i = 0; i < normWords.length; i++) {
            if (i == insultIndex) continue;
            if (Math.abs(i - insultIndex) > 4) continue;
            String word = normWords[i];
            for (String fw : familyWords) {
                if (word.equals(fw) || word.startsWith(fw)) return true;
            }
        }
        return false;
    }

    private static boolean checkAndRecordSpam(UUID id, String normalizedText, int maxCount, long windowMs, double threshold) {
        long now = System.currentTimeMillis();
        Deque<SpamEntry> history = spamHistory.computeIfAbsent(id, k -> new ConcurrentLinkedDeque<>());

        history.removeIf(e -> (now - e.timestamp) > windowMs);

        int similarCount = 1;

        for (SpamEntry e : history) {
            if (SimilarityChecker.getSimilarityRatio(e.normalizedText, normalizedText, 0.0) >= threshold) {
                similarCount++;
                if (similarCount >= maxCount) {
                    return true;
                }
            }
        }

        history.addLast(new SpamEntry(normalizedText, now));
        return false;
    }

    private static long parseTimeToTicks(String timeStr) {
        if (timeStr.isEmpty()) return 30 * 60 * 20L;
        String time = timeStr.toLowerCase();
        try {
            if (time.endsWith("h")) return Long.parseLong(time, 0, time.length() - 1, 10) * 60 * 60 * 20L;
            if (time.endsWith("m")) return Long.parseLong(time, 0, time.length() - 1, 10) * 60 * 20L;
            if (time.endsWith("s")) return Long.parseLong(time, 0, time.length() - 1, 10) * 20L;
        } catch (NumberFormatException ignored) {}
        return 30 * 60 * 20L;
    }

    private static boolean isCaps(String rawText, int minLength, int minPct) {
        String letters = NON_LETTER_PATTERN.matcher(rawText).replaceAll("");
        if (letters.length() < minLength) return false;
        long upper = letters.chars().filter(Character::isUpperCase).count();
        return (double) upper / letters.length() * 100.0 >= minPct;
    }

    private static void sendMultilineMessage(Player player, String message) {
        String colored = colorize(message.trim());
        for (String line : NEWLINE_PATTERN.split(colored)) {
            String t = line.trim();
            if (!t.isEmpty()) player.sendMessage(t);
        }
    }

    private static String logLine(String action, String duration, String name, String detail, String word) {
        return "[A.P.V.E.] " + action + " " + duration + " → " + name + " [" + detail + (word.isEmpty() ? "" : " | '" + word + "'") + "]";
    }

    private static String extractRawText(PacketReceiveEvent event, boolean isChatMsg, boolean isChatCmd, Set<String> interceptedCommands) {
        if (isChatMsg) return new WrapperPlayClientChatMessage(event).getMessage();
        if (isChatCmd) {
            WrapperPlayClientChatCommand wrapper = new WrapperPlayClientChatCommand(event);
            String command = wrapper.getCommand();
            if (command.isEmpty()) return "";
            String[] parts = command.split(" ", 2);
            String cmd = parts[0].toLowerCase();
            String args = parts.length > 1 ? parts[1] : "";
            if (interceptedCommands.contains(cmd)) return extractMessageFromArgs(cmd, args);
        }
        return "";
    }

    private static boolean containsIP(String text) {
        return !text.isEmpty() && IP_PATTERN.matcher(text).find();
    }

    private static String extractMessageFromArgs(String command, String args) {
        if (args.isEmpty()) return "";
        if (command.equalsIgnoreCase("r") || command.equalsIgnoreCase("reply")) return args;
        String[] parts = args.split(" ", 2);
        return parts.length > 1 ? parts[1] : "";
    }

    public static int getWarns(UUID uuid) { return warnCounts.getOrDefault(uuid, 0); }

    public static int removeWarns(UUID uuid, int amount) {
        if (!warnCounts.containsKey(uuid)) return 0;
        int current = warnCounts.get(uuid);
        int newCount = Math.max(0, current - amount);
        if (newCount == 0) clearWarns(uuid);
        else warnCounts.put(uuid, newCount);
        return newCount;
    }

    public static void clearWarns(UUID uuid) {
        warnCounts.remove(uuid);
        highestViolations.remove(uuid);
        BukkitTask task = resetTasks.remove(uuid);
        if (task != null) task.cancel();
    }

    public static String getHighestViolationType(UUID uuid) {
        StoredViolation v = highestViolations.get(uuid);
        return v != null ? v.type().name() : "NONE";
    }
    public record InspectionResult(
    String rawText,
    String normalizedText,
    String violationType,
    String matchedInputWord,
    String matchedDictWord,
    String detail
) {}

public static InspectionResult inspect(String rawText) {
    String normalized = TextNormalizer.normalize(rawText);
    String fullyCompressed = TextNormalizer.removeSpaces(normalized);
    int[] spaceMap = TextNormalizer.createSpaceMapping(normalized);

    ChatRulesCache rules = cachedRules;

    String domainNormalized = TextNormalizer.normalizeForDomain(rawText);
    if (containsIP(rawText) || rules.domainPattern().matcher(domainNormalized).find() || domainNormalized.contains("http") || domainNormalized.contains("www")) {
        String detected = "ADVERTISEMENT";
        String dictWord = "Domain/IP Pattern";
        for (String social : rules.socialWords()) {
            if (domainNormalized.contains(social.toLowerCase())) {
                detected = "SOCIAL_MEDIA";
                dictWord = social;
                break;
            }
        }
        return new InspectionResult(rawText, normalized, "MALICIOUS", rawText, dictWord, "Type: " + detected + " | Link or IP pattern");
    }

    List<AhoCorasick.Match> acMatches = rules.ahoCorasick().search(fullyCompressed);
    for (AhoCorasick.Match match : acMatches) {
        String pattern = match.pattern();
        int c_start = match.startIndex();
        int c_end = match.endIndex();

        int n_start = spaceMap[c_start];
        int n_end = spaceMap[c_end - 1] + 1;
        String span = normalized.substring(n_start, n_end);

        boolean isValid = true;
        if (span.contains(" ")) {
            boolean cutsWord = (n_start > 0 && Character.isLetter(normalized.charAt(n_start - 1))) ||
                               (n_end < normalized.length() && Character.isLetter(normalized.charAt(n_end)));
            if (cutsWord) {
                isValid = false;
            } else {
                String[] parts = span.split("\\s+");
                boolean allAllowed = true;
                for (String part : parts) {
                    if (!part.isEmpty() && !rules.allowedWords().contains(part)) {
                        allAllowed = false;
                        break;
                    }
                }
                if (allAllowed) isValid = false;
            }
        }

        if (isValid) {
            ViolationType type = match.type();
            if (type == ViolationType.INSULT) {
                String[] normWords = normalized.split("\\s+");
                int tokenStartIdx = 0;
                int charCount = 0;
                for (int j = 0; j < normWords.length; j++) {
                    charCount += normWords[j].length();
                    if (charCount > n_start) {
                        tokenStartIdx = j;
                        break;
                    }
                    charCount++;
                }
                if (hasStaffContext(normWords, rules.staffTitles(), tokenStartIdx)) type = ViolationType.STAFF_INSULT;
                else if (hasFamilyContext(normWords, rules.familyWords(), tokenStartIdx)) type = ViolationType.FAMILY_INSULT;
            }
            return new InspectionResult(rawText, normalized, "MALICIOUS", span, pattern, "Type: " + type.name() + " | AC Match");
        }
    }

    String[] normWords = normalized.split("\\s+");
    String[] rawWords = rawText.toLowerCase().split("\\s+");

    double maxSuspiciousSim = 0.0;
    String suspiciousInputWord = "";
    String suspiciousDictWord = "";

    for (int i = 0; i < normWords.length; i++) {
        String word = normWords[i];
        String rawWord = (i < rawWords.length) ? rawWords[i] : word;

        if (word.isEmpty() || rules.allowedWords().contains(word)) continue;

        for (String adult : rules.adultWords()) {
            double sim = SimilarityChecker.getSimilarityRatio(word, adult, 0.0);
            if (sim >= rules.highThreshold()) {
                return new InspectionResult(rawText, normalized, "MALICIOUS", rawWord, adult, "Type: ADULT_CONTENT | Fuzzy Match Adult");
            }
        }

        double maxSim = 0.0;
        String bestInsult = "";
        for (String insult : rules.insultWords()) {
            double sim = SimilarityChecker.getSimilarityRatio(word, insult, 0.0);
            if (sim > maxSim) {
                maxSim = sim;
                bestInsult = insult;
            }
        }

        if (maxSim >= rules.highThreshold()) {
            ViolationType cand = ViolationType.INSULT;
            if (hasStaffContext(normWords, rules.staffTitles(), i)) cand = ViolationType.STAFF_INSULT;
            else if (hasFamilyContext(normWords, rules.familyWords(), i)) cand = ViolationType.FAMILY_INSULT;

            return new InspectionResult(rawText, normalized, "MALICIOUS", rawWord, bestInsult, String.format("Type: %s | Fuzzy Insult (%.0f%%)", cand.name(), maxSim * 100));
        }

        if (maxSim >= rules.mediumThreshold()) {
            if (maxSim > maxSuspiciousSim) {
                maxSuspiciousSim = maxSim;
                suspiciousInputWord = rawWord;
                suspiciousDictWord = bestInsult;
            }
        }

        if (rules.expressiveWords().contains(word)) {
            boolean targetedAtPronoun = (i > 0 && PERSONAL_PRONOUNS.contains(normWords[i - 1])) ||
                                        (i < normWords.length - 1 && PERSONAL_PRONOUNS.contains(normWords[i + 1]));
            boolean targetedAtStaff = hasStaffContext(normWords, rules.staffTitles(), i);
            boolean targetedAtFamily = hasFamilyContext(normWords, rules.familyWords(), i);

            if (targetedAtPronoun || targetedAtStaff || targetedAtFamily) {
                ViolationType cand = ViolationType.INSULT;
                if (targetedAtStaff) cand = ViolationType.STAFF_INSULT;
                else if (targetedAtFamily) cand = ViolationType.FAMILY_INSULT;

                return new InspectionResult(rawText, normalized, "MALICIOUS", rawWord, word, "Type: " + cand.name() + " | Targeted profanity");
            }
        }
    }

    if (rules.capsModuleEnabled() && isCaps(rawText, rules.capsMinLength(), rules.capsMinPct())) {
        return new InspectionResult(rawText, normalized, "MALICIOUS", rawText, "-", "Type: CAPS | Caps Threshold");
    }

    if (maxSuspiciousSim > 0.0) {
        return new InspectionResult(rawText, normalized, "SUSPICIOUS", suspiciousInputWord, suspiciousDictWord, String.format("Similarity above medium threshold (%.0f%%)", maxSuspiciousSim * 100));
    }

    return new InspectionResult(rawText, normalized, "NONE", "-", "-", "Similarity below medium threshold");
    }
}