package org.apve.nci;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import org.apve.nci.*;
import org.apve.engine.*;
import org.apve.etc.*;
import org.apve.nci.StructureModule.*;
import org.apve.command.MessageUtil;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.logging.Logger;
import java.util.regex.Pattern;

public class ViolationHandler implements MessageAnalyzer.SpamChecker {

    private final Map<UUID, Integer> warnCounts = new ConcurrentHashMap<>();
    private final Map<UUID, StoredViolation> highestViolations = new ConcurrentHashMap<>();
    private final Map<UUID, WrappedTask> resetTasks = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<SpamEntry>> spamHistory = new ConcurrentHashMap<>();
    private final Set<UUID> pendingBlockMessages = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Map<UUID, String> pendingCensorMessages = new ConcurrentHashMap<>();
    private static final Pattern NEWLINE_PATTERN = Pattern.compile("\n");

    @Override
    public boolean checkSpam(UUID id, String normalizedText, int maxCount, long windowMs, double threshold) {
        long now = System.currentTimeMillis();
        Deque<SpamEntry> history = spamHistory.computeIfAbsent(id, k -> new ConcurrentLinkedDeque<>());

        history.removeIf(e -> (now - e.timestamp()) > windowMs);
        int similarCount = 1;

        for (SpamEntry e : history) {
            if (SimilarityChecker.getSimilarityRatio(e.normalizedText(), normalizedText, 0.0) >= threshold) {
                similarCount++;
                if (similarCount >= maxCount) {
                    return true;
                }
            }
        }

        history.addLast(new SpamEntry(normalizedText, now));
        return false;
    }

    public void handleChatViolation(Plugin plugin, Player player, PunishmentManager pm, NotificationManager nm,
                                    PacketReceiveEvent event, String rawText, AnalysisResult result,
                                    GlobalConfig cfg, boolean auditMode, Logger maliciousLogger) {
        ViolationRule rule = cfg.rules().get(result.type());
        if (!rule.enabled()) return;

        if (auditMode) {
            maliciousLogger.warning(String.format(
                    "[AUDIT-MODE | MALICIOUS] Player: %s | Violation: %s | Detail: %s | Word: '%s' | Message: '%s'",
                    player.getName(), result.type().name(), result.reasonDetail(), result.rawMatchWord(), rawText
            ));
            return;
        }

        if (cfg.consoleLog()) {
            maliciousLogger.warning(String.format(
                    "[MALICIOUS] Player: %s | Violation: %s | Detail: %s | Word: '%s' | Message: '%s'",
                    player.getName(), result.type().name(), result.reasonDetail(), result.rawMatchWord(), rawText
            ));
        }

        if (cfg.notifiesEnabled()) {
            nm.sendViolationAlert(player, result.type(), result.rawMatchWord(), rawText);
        }

        boolean isBlocked = rule.block();
        boolean isCensored = rule.censor();
        String finalMessage = rawText;

        if (isCensored && !isBlocked) {
            if (result.type() == ViolationType.CAPS) {
                finalMessage = finalMessage.toLowerCase();
            } else if (!result.rawMatchWord().isEmpty()) {
                finalMessage = finalMessage.replaceAll("(?i)" + Pattern.quote(result.rawMatchWord()), "***");
                if (finalMessage.equals(rawText)) finalMessage = "***";
            } else {
                finalMessage = "***";
            }
        }

        if (isBlocked) {
            event.setCancelled(true);
            pendingBlockMessages.add(player.getUniqueId());
        } else if (isCensored) {
            pendingCensorMessages.put(player.getUniqueId(), finalMessage);
        }

        handlePunishmentAndMessages(plugin, player, pm, rule, result.type(), result.reasonDetail(), result.rawMatchWord(), isBlocked, isCensored, cfg);
    }

    public void handleCommandViolation(Plugin plugin, Player player, PunishmentManager pm, NotificationManager nm,
                                       PlayerCommandPreprocessEvent event, String fullCommand, String rawText,
                                       AnalysisResult result, GlobalConfig cfg, boolean auditMode, Logger maliciousLogger) {
        ViolationRule rule = cfg.rules().get(result.type());
        if (!rule.enabled()) return;

        if (auditMode) {
            maliciousLogger.warning(String.format(
                    "[AUDIT-MODE | MALICIOUS] Player: %s | Violation: %s | Detail: %s | Word: '%s' | Message: '%s'",
                    player.getName(), result.type().name(), result.reasonDetail(), result.rawMatchWord(), rawText
            ));
            return;
        }

        if (cfg.consoleLog()) {
            maliciousLogger.warning(String.format(
                    "[MALICIOUS] Player: %s | Violation: %s | Detail: %s | Word: '%s' | Message: '%s'",
                    player.getName(), result.type().name(), result.reasonDetail(), result.rawMatchWord(), rawText
            ));
        }

        if (cfg.notifiesEnabled()) {
            nm.sendViolationAlert(player, result.type(), result.rawMatchWord(), rawText);
        }

        boolean isBlocked = rule.block();
        boolean isCensored = rule.censor();
        String finalMessage = rawText;

        if (isCensored && !isBlocked) {
            if (result.type() == ViolationType.CAPS) {
                finalMessage = finalMessage.toLowerCase();
            } else if (!result.rawMatchWord().isEmpty()) {
                finalMessage = finalMessage.replaceAll("(?i)" + Pattern.quote(result.rawMatchWord()), "***");
                if (finalMessage.equals(rawText)) finalMessage = "***";
            } else {
                finalMessage = "***";
            }
        }

        if (isBlocked) {
            event.setCancelled(true);
        } else if (isCensored) {
            if (!rawText.isEmpty() && fullCommand.contains(rawText)) {
                String censoredCmd = fullCommand.replace(rawText, finalMessage);
                if (!censoredCmd.startsWith("/")) {
                    censoredCmd = "/" + censoredCmd;
                }
                event.setMessage(censoredCmd);
            }
        }

        handlePunishmentAndMessages(plugin, player, pm, rule, result.type(), result.reasonDetail(), result.rawMatchWord(), isBlocked, isCensored, cfg);
    }

    private void handlePunishmentAndMessages(Plugin plugin, Player player, PunishmentManager pm,
                                             ViolationRule rule, ViolationType type, String reasonDetail,
                                             String badWord, boolean isBlocked, boolean isCensored,
                                             GlobalConfig cfg) {
        ScheduleManager.get().runAtEntity(player, () -> {
            boolean executePunishment = true;
            String warnMsgToSend = null;

            boolean punishEnabled = rule.punishEnabled();
            boolean warnsIsEnabled = cfg.warnsIsEnabled();
            boolean warnLimitIsEnabled = cfg.warnLimitIsEnabled();

            if (punishEnabled && warnsIsEnabled && warnLimitIsEnabled) {
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
                    WrappedTask old = resetTasks.remove(uuid);
                    if (old != null) old.cancel();

                    WrappedTask task = ScheduleManager.get().runGlobalLater(() -> {
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

                int warnLimit = cfg.warnLimit();
                if (warns <= warnLimit) {
                    executePunishment = false;
                    warnMsgToSend = warns < warnLimit ? cfg.warnMessage() : cfg.lastWarnMessage();
                    if (cfg.consoleLog()) {
                        plugin.getLogger().info(logLine("WARN", warns + "/" + warnLimit, player.getName(), reasonDetail, badWord));
                    }
                } else {
                    warnCounts.put(uuid, 0);
                }
            }

            if (isBlocked) {
                sendMultilineMessage(player, rule.blockReason());
            } else if (isCensored) {
                sendMultilineMessage(player, rule.censorReason());
            }

            if (warnMsgToSend != null) {
                sendMultilineMessage(player, warnMsgToSend);
            }

            if (executePunishment && punishEnabled) {
                StoredViolation heaviest = (warnsIsEnabled && warnLimitIsEnabled) ? highestViolations.remove(player.getUniqueId()) : null;
                if (heaviest != null) {
                    applyPunishment(plugin, player, pm, heaviest.rule(), heaviest.reasonDetail(), heaviest.badWord(), cfg.consoleLog());
                } else {
                    applyPunishment(plugin, player, pm, rule, reasonDetail, badWord, cfg.consoleLog());
                }
            }
        });
    }

    private void applyPunishment(Plugin plugin, Player player, PunishmentManager pm, ViolationRule rule, String detail, String word, boolean consoleLog) {
        String type = rule.type().toLowerCase(Locale.ROOT).trim();
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
            plugin.getLogger().info(logLine(type.toUpperCase(Locale.ROOT), rule.duration(), player.getName(), detail, word));
        }
    }

    private void sendMultilineMessage(Player player, String message) {
        if (message == null || message.isBlank()) return;
        String[] lines = NEWLINE_PATTERN.split(message.trim());
        for (String line : lines) {
            String trimmedLine = line.trim();
            if (!trimmedLine.isEmpty()) {
                MessageUtil.send(player, trimmedLine);
            }
        }
    }

    private String logLine(String action, String duration, String name, String detail, String word) {
        return "[A.P.V.E.] " + action + " " + duration + " → " + name + " [" + detail + (word.isEmpty() ? "" : " | '" + word + "'") + "]";
    }

    private long parseTimeToTicks(String timeStr) {
        if (timeStr.isEmpty()) return 30 * 60 * 20L;
        String time = timeStr.toLowerCase();
        try {
            if (time.endsWith("h")) return Long.parseLong(time, 0, time.length() - 1, 10) * 60 * 60 * 20L;
            if (time.endsWith("m")) return Long.parseLong(time, 0, time.length() - 1, 10) * 60 * 20L;
            if (time.endsWith("s")) return Long.parseLong(time, 0, time.length() - 1, 10) * 20L;
        } catch (NumberFormatException ignored) {}
        return 30 * 60 * 20L;
    }

    public int getWarns(UUID uuid) { return warnCounts.getOrDefault(uuid, 0); }

    public int removeWarns(UUID uuid, int amount) {
        if (!warnCounts.containsKey(uuid)) return 0;
        int current = warnCounts.get(uuid);
        int newCount = Math.max(0, current - amount);
        if (newCount == 0) clearWarns(uuid);
        else warnCounts.put(uuid, newCount);
        return newCount;
    }

    public void clearWarns(UUID uuid) {
        warnCounts.remove(uuid);
        highestViolations.remove(uuid);
        WrappedTask task = resetTasks.remove(uuid);
        if (task != null) task.cancel();
    }

    public String getHighestViolationType(UUID uuid) {
        StoredViolation v = highestViolations.get(uuid);
        return v != null ? v.type().name() : "NONE";
    }

    public void cleanupPlayer(UUID uuid) {
        pendingBlockMessages.remove(uuid);
        pendingCensorMessages.remove(uuid);
    }

    public boolean checkAndRemoveBlock(UUID uuid) {
        return pendingBlockMessages.remove(uuid);
    }

    public String getAndRemoveCensor(UUID uuid) {
        return pendingCensorMessages.remove(uuid);
    }
}