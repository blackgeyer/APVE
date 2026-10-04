package org.apve.nci;

import org.apve.nci.*;
import org.apve.engine.*;
import org.apve.nci.StructureModule.*;


import org.bukkit.entity.Player;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;

public class MessageAnalyzer {

    private static final Set<String> PERSONAL_PRONOUNS = Set.of("ty", "vy", "on", "ona", "oni", "tebe", "tebya", "toboy", "vas", "vam", "emu", "ey", "tvoya", "tvoyu", "tvoy", "tvoego", "tvoemu", "tvoim", "vashu", "vashe", "vash", "ego", "eyo", "ih", "you", "your", "he", "she", "they", "his", "her", "their", "u");
    private static final Pattern IP_PATTERN = Pattern.compile("\\b(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)[\\._,\\s\\-]){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\b");
    private static final Pattern NON_LETTER_PATTERN = Pattern.compile("[^a-zA-Z\u0400-\u04FF]");

    private final ChatRulesCache rules;
    private final SpamChecker spamChecker;

    public interface SpamChecker {
        boolean checkSpam(UUID playerId, String normalizedText, int maxCount, long windowMs, double threshold);
    }

    public interface ImmunityChecker {
        boolean hasImmunity(Player player, ViolationType type);
    }

    public MessageAnalyzer(ChatRulesCache rules, SpamChecker spamChecker) {
        this.rules = rules;
        this.spamChecker = spamChecker;
    }

    public AnalysisResult analyze(UUID playerId, String rawText, Player player, Logger suspiciousLogger, boolean consoleLog, ImmunityChecker immunityChecker) {
        String normalized = TextNormalizer.normalize(rawText);
        String fullyCompressed = TextNormalizer.removeSpaces(normalized);
        int[] spaceMap = TextNormalizer.createSpaceMapping(normalized);

        ViolationType spamCandidate = null;
        if (rules.spamModuleEnabled() && spamChecker.checkSpam(playerId, normalized, rules.spamMaxCount(), rules.spamWindowMs(), rules.spamSimThreshold())) {
            spamCandidate = ViolationType.SPAM;
        }

        ViolationType capsCandidate = null;
        if (rules.capsModuleEnabled() && isCaps(rawText, rules.capsMinLength(), rules.capsMinPct())) {
            capsCandidate = ViolationType.CAPS;
        }

        String domainNormalized = TextNormalizer.normalizeForDomain(rawText);
        boolean hasLinkBypass = domainNormalized.contains("http") || domainNormalized.contains("www");
        boolean hasIpMatch = containsIP(rawText);
        boolean hasDomainMatch = rules.domainPattern().matcher(domainNormalized).find();

        if (hasIpMatch || hasDomainMatch || hasLinkBypass) {
            ViolationType detected = ViolationType.ADVERTISEMENT;
            String reason = "IP/Link/Domain";
            for (String social : rules.socialWords()) {
                if (domainNormalized.contains(social.toLowerCase())) {
                    detected = ViolationType.SOCIAL_MEDIA;
                    reason = "Social Media Link: " + social;
                    break;
                }
            }
            if (!immunityChecker.hasImmunity(player, detected)) {
                return new AnalysisResult(detected, rawText, reason, rawText);
            }
        }

        String[] normWords = normalized.split("\\s+");
        List<AhoCorasick.Match> acMatches = rules.ahoCorasick().search(fullyCompressed);

        for (AhoCorasick.Match match : acMatches) {
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
                String fullToken = extractFullToken(normalized, n_start, n_end);
                if (rules.expressiveWords().contains(fullToken)) {
                    isValidViolation = false;
                }
            }

            if (isValidViolation) {
                ViolationType finalType = match.type();
                if (finalType == ViolationType.INSULT) {
                    int tokenStartIdx = findTokenIndex(normWords, n_start);
                    if (hasStaffContext(normWords, rules.staffTitles(), tokenStartIdx)) {
                        finalType = ViolationType.STAFF_INSULT;
                    } else if (hasFamilyContext(normWords, rules.familyWords(), tokenStartIdx)) {
                        finalType = ViolationType.FAMILY_INSULT;
                    }
                }
                if (!immunityChecker.hasImmunity(player, finalType)) {
                    return new AnalysisResult(finalType, match.pattern(), "Found via AC: " + match.pattern(), match.pattern());
                }
            }
        }

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

            if (rules.expressiveWords().contains(word)) {
                boolean targetedAtPronoun =
                        (i > 0 && PERSONAL_PRONOUNS.contains(normWords[i - 1])) ||
                                (i < normWords.length - 1 && PERSONAL_PRONOUNS.contains(normWords[i + 1]));
                boolean targetedAtStaff = hasStaffContext(normWords, rules.staffTitles(), i);
                boolean targetedAtFamily = hasFamilyContext(normWords, rules.familyWords(), i);

                if (targetedAtPronoun || targetedAtStaff || targetedAtFamily) {
                    ViolationType candType = ViolationType.INSULT;
                    if (targetedAtStaff) candType = ViolationType.STAFF_INSULT;
                    else if (targetedAtFamily) candType = ViolationType.FAMILY_INSULT;

                    if (!immunityChecker.hasImmunity(player, candType)) {
                        matchedWord = word;
                        rawMatchWord = rawWord;
                        reasonDetail = "Targeted profanity: " + word;
                        detectedType = candType;
                        break outer;
                    }
                }
                continue;
            }

            ViolationRule adultRule = rules.adultWords().isEmpty() ? null : new ViolationRule(true, false, "", "", "", false, "", false, "");
            if (adultRule != null && !immunityChecker.hasImmunity(player, ViolationType.ADULT_CONTENT)) {
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
                if (hasStaffContext(normWords, rules.staffTitles(), i)) candType = ViolationType.STAFF_INSULT;
                else if (hasFamilyContext(normWords, rules.familyWords(), i)) candType = ViolationType.FAMILY_INSULT;

                if (!immunityChecker.hasImmunity(player, candType)) {
                    matchedWord = suspectedInsult;
                    reasonDetail = "Insult (fuzzy): " + matchedWord;
                    detectedType = candType;
                    break outer;
                }
            }
        }

        if (detectedType != null) {
            return new AnalysisResult(detectedType, matchedWord, reasonDetail, rawMatchWord);
        } else if (spamCandidate != null && !immunityChecker.hasImmunity(player, ViolationType.SPAM)) {
            return new AnalysisResult(ViolationType.SPAM, rawText, "Spam", rawText);
        } else if (capsCandidate != null && !immunityChecker.hasImmunity(player, ViolationType.CAPS)) {
            return new AnalysisResult(ViolationType.CAPS, rawText, "Caps", rawText);
        } else if (maxSimilarity >= rules.mediumThreshold() && maxSimilarity < rules.highThreshold()) {
            if (rules.auditMode() || consoleLog) {
                String prefix = rules.auditMode() ? "[AUDIT-MODE | SUSPICIOUS]" : "[SUSPICIOUS]";
                suspiciousLogger.warning(String.format(
                        "%s Player: %s | Text: '%s' | Suspicion: '%s' (%.0f%%)",
                        prefix, player.getName(), rawText, suspectedInsult, maxSimilarity * 100));
            }
        }

        return null;
    }

    public InspectionResult inspect(String rawText) {
        String normalized = TextNormalizer.normalize(rawText);
        String fullyCompressed = TextNormalizer.removeSpaces(normalized);
        int[] spaceMap = TextNormalizer.createSpaceMapping(normalized);

        String domainNormalized = TextNormalizer.normalizeForDomain(rawText);
        boolean hasLinkBypass = domainNormalized.contains("http") || domainNormalized.contains("www");
        boolean hasIpMatch = containsIP(rawText);
        boolean hasDomainMatch = rules.domainPattern().matcher(domainNormalized).find();

        if (hasIpMatch || hasDomainMatch || hasLinkBypass) {
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

        String[] normWords = normalized.split("\\s+");
        List<AhoCorasick.Match> acMatches = rules.ahoCorasick().search(fullyCompressed);
        for (AhoCorasick.Match match : acMatches) {
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
                String fullToken = extractFullToken(normalized, n_start, n_end);
                if (rules.expressiveWords().contains(fullToken)) isValid = false;
            }
            if (isValid) {
                ViolationType type = match.type();
                if (type == ViolationType.INSULT) {
                    int tokenStartIdx = findTokenIndex(normWords, n_start);
                    if (hasStaffContext(normWords, rules.staffTitles(), tokenStartIdx)) type = ViolationType.STAFF_INSULT;
                    else if (hasFamilyContext(normWords, rules.familyWords(), tokenStartIdx)) type = ViolationType.FAMILY_INSULT;
                }
                return new InspectionResult(rawText, normalized, "MALICIOUS", span, match.pattern(), "Type: " + type.name() + " | AC Match");
            }
        }

        String[] rawWords = rawText.toLowerCase().split("\\s+");
        double maxSuspiciousSim = 0.0;
        String suspiciousInputWord = "";
        String suspiciousDictWord = "";

        for (int i = 0; i < normWords.length; i++) {
            String word = normWords[i];
            String rawWord = (i < rawWords.length) ? rawWords[i] : word;

            if (word.isEmpty() || rules.allowedWords().contains(word)) continue;

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
                continue;
            }

            for (String adult : rules.adultWords()) {
                if (SimilarityChecker.getSimilarityRatio(word, adult, 0.0) >= rules.highThreshold()) {
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
        }

        if (rules.capsModuleEnabled() && isCaps(rawText, rules.capsMinLength(), rules.capsMinPct())) {
            return new InspectionResult(rawText, normalized, "MALICIOUS", rawText, "-", "Type: CAPS | Caps Threshold");
        }

        if (maxSuspiciousSim > 0.0) {
            return new InspectionResult(rawText, normalized, "SUSPICIOUS", suspiciousInputWord, suspiciousDictWord, String.format("Similarity above medium threshold (%.0f%%)", maxSuspiciousSim * 100));
        }

        return new InspectionResult(rawText, normalized, "NONE", "-", "-", "Similarity below medium threshold");
    }

    private static String extractFullToken(String text, int start, int end) {
        int left = start;
        while (left > 0 && !Character.isWhitespace(text.charAt(left - 1))) left--;
        int right = end;
        while (right < text.length() && !Character.isWhitespace(text.charAt(right))) right++;
        return text.substring(left, right);
    }

    private static int findTokenIndex(String[] normWords, int charOffset) {
        int charCount = 0;
        for (int j = 0; j < normWords.length; j++) {
            charCount += normWords[j].length();
            if (charCount > charOffset) return j;
            charCount++;
        }
        return 0;
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

    private static boolean isCaps(String rawText, int minLength, int minPct) {
        String letters = NON_LETTER_PATTERN.matcher(rawText).replaceAll("");
        if (letters.length() < minLength) return false;
        long upper = letters.chars().filter(Character::isUpperCase).count();
        return (double) upper / letters.length() * 100.0 >= minPct;
    }

    private static boolean containsIP(String text) {
        return !text.isEmpty() && IP_PATTERN.matcher(text).find();
    }
}
