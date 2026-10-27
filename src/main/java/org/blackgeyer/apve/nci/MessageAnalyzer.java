package org.blackgeyer.apve.nci;

import org.blackgeyer.apve.engine.*;
import org.blackgeyer.apve.nci.StructureModule.*;

import org.bukkit.entity.Player;
import java.util.*;
import java.util.logging.Logger;
import com.google.re2j.Pattern;

public class MessageAnalyzer {

    private static final Set<String> PERSONAL_PRONOUNS = Set.of("ty", "vy", "on", "ona", "oni", "tebe", "tebya", "toboy", "vas", "vam", "emu", "ey", "tvoya", "tvoyu", "tvoy", "tvoego", "tvoemu", "tvoim", "vashu", "vashe", "vash", "ego", "eyo", "ih", "you", "your", "he", "she", "they", "his", "her", "their", "u");
    private static final Pattern IP_PATTERN = Pattern.compile("\\b(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)[\\._,\\s\\-]){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\b");
    private static final Pattern NON_LETTER_PATTERN = Pattern.compile("[^a-zA-Z\\x{0400}-\\x{04FF}]");

    private static final int AD_DOMAIN_MAX_GAP = 12;
    private static final int AD_MAX_TLD_LEN = 6;

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

    private record BrotherMatch(ViolationType type, String matchedWord, String reasonDetail, String rawMatchWord) {}

    private record AdMatch(ViolationType type, String reason, String dictWord) {}

    private static final class Suspicion {
        double similarity = 0.0;
        ViolationType type = null;
        String inputWord = "";
        String dictWord = "";
        String brother = "";

        void offer(double sim, ViolationType type, String inputWord, String dictWord, String brother) {
            if (sim > similarity) {
                this.similarity = sim;
                this.type = type;
                this.inputWord = inputWord;
                this.dictWord = dictWord;
                this.brother = brother;
            }
        }
    }

    public AnalysisResult analyze(UUID playerId, String rawText, Player player, Logger suspiciousLogger, boolean consoleLog, ImmunityChecker immunityChecker) {
        TextNormalizer.NormalizedResult res = TextNormalizer.process(rawText);

        if (rules.spamModuleEnabled() && spamChecker.checkSpam(playerId, res.smartText(), rules.spamMaxCount(), rules.spamWindowMs(), rules.spamSimThreshold())) {
            if (!immunityChecker.hasImmunity(player, ViolationType.SPAM)) {
                return new AnalysisResult(ViolationType.SPAM, rawText, "Spam", rawText, "Smart");
            }
        }

        if (rules.capsModuleEnabled() && isCaps(rawText, rules.capsMinLength(), rules.capsMinPct())) {
            if (!immunityChecker.hasImmunity(player, ViolationType.CAPS)) {
                return new AnalysisResult(ViolationType.CAPS, rawText, "Caps", rawText, "Smart");
            }
        }

        Map<String, AdMatch> ads = detectAdvertisement(rawText, res, player, immunityChecker);
        if (!ads.isEmpty()) {
            AdMatch primary = ads.values().iterator().next();
            return new AnalysisResult(primary.type(), rawText, primary.reason(), rawText, String.join(", ", ads.keySet()));
        }

        Map<String, BrotherMatch> matchesByBrother = new LinkedHashMap<>();
        Suspicion suspicion = new Suspicion();

        BrotherMatch smartMatch = evaluateNormalized(res.smartText(), player, immunityChecker, suspicion, "Smart");
        if (smartMatch != null) matchesByBrother.put("Smart", smartMatch);

        BrotherMatch rudeMatch = evaluateNormalized(res.rudeText(), player, immunityChecker, suspicion, "Rude");
        if (rudeMatch != null) matchesByBrother.put("Rude", rudeMatch);

        BrotherMatch despondentMatch = evaluateNormalized(res.despondentText(), player, immunityChecker, suspicion, "Despondent");
        if (despondentMatch != null) matchesByBrother.put("Despondent", despondentMatch);

        if (!matchesByBrother.isEmpty()) {
            String detectedBy = String.join(", ", matchesByBrother.keySet());
            BrotherMatch primary = matchesByBrother.values().iterator().next();

            return new AnalysisResult(primary.type(), primary.matchedWord(), primary.reasonDetail(), primary.rawMatchWord(), detectedBy);
        }

        if (suspicion.type != null && (rules.auditMode() || consoleLog)) {
            String prefix = rules.auditMode() ? "[AUDIT-MODE | SUSPICIOUS]" : "[SUSPICIOUS]";
            suspiciousLogger.warning(String.format(
                    "%s Player: %s | Text: '%s' | Suspected of: %s | Word: '%s' ~ '%s' (%.0f%%) | Closest by: %s",
                    prefix, player.getName(), rawText, suspicion.type.name(),
                    suspicion.inputWord, suspicion.dictWord, suspicion.similarity * 100, suspicion.brother));
        }

        return null;
    }

    private Map<String, AdMatch> detectAdvertisement(String rawText, TextNormalizer.NormalizedResult res, Player player, ImmunityChecker immunityChecker) {
        boolean rawHasIp = containsIP(rawText);

        Map<String, String> variants = new LinkedHashMap<>();
        variants.put("Smart", res.smartText());
        variants.put("Rude", res.rudeText());
        variants.put("Despondent", res.despondentText());
        variants.put("Domain", res.domainText());

        Map<String, AdMatch> found = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : variants.entrySet()) {
            AdMatch m = detectAdInText(e.getValue(), rawHasIp, player, immunityChecker);
            if (m != null) found.put(e.getKey(), m);
        }
        return found;
    }

    private AdMatch detectAdInText(String text, boolean rawHasIp, Player player, ImmunityChecker immunityChecker) {
        if (text == null || text.isBlank()) return null;

        String v = text.replaceAll("\\s*\\.\\s*", ".");
        String compressed = TextNormalizer.removeSpaces(v);
        int[] spaceMap = TextNormalizer.createSpaceMapping(v);

        List<int[]> domains = rangesOf(rules.domainPattern(), v);
        List<int[]> ips = rangesOf(IP_PATTERN, v);
        int linkIdx = firstLinkIndex(v);
        boolean hasIp = rawHasIp || !ips.isEmpty();

        if (linkIdx >= 0 || hasIp || !domains.isEmpty()) {
            for (String social : rules.socialWords()) {
                String s = social.toLowerCase();
                if (v.contains(s) || compressed.contains(s)) {
                    if (immunityChecker.hasImmunity(player, ViolationType.SOCIAL_MEDIA)) return null;
                    return new AdMatch(ViolationType.SOCIAL_MEDIA, "Social Media Link: " + social, social);
                }
            }
        }

        for (AhoCorasick.Match m : rules.ahoCorasick().search(compressed)) {
            if (m.type() != ViolationType.ADVERTISEMENT) continue;

            int start = spaceMap[m.startIndex()];
            int end = spaceMap[m.endIndex() - 1] + 1;

            String context = adContext(start, end, m.endIndex(), domains, ips, linkIdx, rawHasIp, v, compressed, spaceMap);
            if (context == null) continue;

            if (immunityChecker.hasImmunity(player, ViolationType.ADVERTISEMENT)) return null;
            return new AdMatch(ViolationType.ADVERTISEMENT,
                    "Ad word + " + context + ": " + m.pattern(), m.pattern());
        }

        return null;
    }

    private String adContext(int start, int end, int compressedEnd,
                             List<int[]> domains, List<int[]> ips, int linkIdx, boolean rawHasIp,
                             String v, String compressed, int[] spaceMap) {
        for (int[] d : domains) {
            if (gap(start, end, d[0], d[1]) <= AD_DOMAIN_MAX_GAP) return "domain";
        }
        for (int[] ip : ips) {
            if (gap(start, end, ip[0], ip[1]) <= AD_DOMAIN_MAX_GAP) return "IP";
        }
        if (ips.isEmpty() && rawHasIp) return "IP";
        if (linkIdx >= 0 && gap(start, end, linkIdx, linkIdx + 4) <= AD_DOMAIN_MAX_GAP) return "link";
        if (tldFollows(v, compressed, spaceMap, compressedEnd)) return "TLD";
        return null;
    }

    private boolean tldFollows(String v, String compressed, int[] spaceMap, int compressedEnd) {
        for (int len = 2; len <= AD_MAX_TLD_LEN && compressedEnd + len <= compressed.length(); len++) {
            int lastV = spaceMap[compressedEnd + len - 1];
            boolean boundary = lastV + 1 >= v.length() || !Character.isLetterOrDigit(v.charAt(lastV + 1));
            if (!boundary) continue;

            String candidate = "x." + compressed.substring(compressedEnd, compressedEnd + len);
            if (rules.domainPattern().matcher(candidate).find()) return true;
        }
        return false;
    }

    private static List<int[]> rangesOf(Pattern pattern, String text) {
        List<int[]> ranges = new ArrayList<>();
        if (text.isEmpty()) return ranges;
        var m = pattern.matcher(text);
        while (m.find()) {
            ranges.add(new int[]{m.start(), m.end()});
        }
        return ranges;
    }

    private static int firstLinkIndex(String text) {
        int http = text.indexOf("http");
        int www = text.indexOf("www");
        if (http < 0) return www;
        if (www < 0) return http;
        return Math.min(http, www);
    }

    private static int gap(int aStart, int aEnd, int bStart, int bEnd) {
        if (aEnd <= bStart) return bStart - aEnd;
        if (bEnd <= aStart) return aStart - bEnd;
        return 0;
    }

    private BrotherMatch evaluateNormalized(String normalized, Player player, ImmunityChecker immunityChecker, Suspicion suspicion, String brother) {
        String fullyCompressed = TextNormalizer.removeSpaces(normalized);
        int[] spaceMap = TextNormalizer.createSpaceMapping(normalized);

        String[] normWords = normalized.split("\\s+");
        List<AhoCorasick.Match> acMatches = rules.ahoCorasick().search(fullyCompressed);

        for (AhoCorasick.Match match : acMatches) {
            if (match.type() == ViolationType.ADVERTISEMENT) continue;

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
                    return new BrotherMatch(finalType, match.pattern(), "Found via AC: " + match.pattern(), match.pattern());
                }
            }
        }

        String[] rawWords = normalized.toLowerCase().split("\\s+");
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
                        return new BrotherMatch(candType, word, "Targeted profanity: " + word, rawWord);
                    }
                }
                continue;
            }

            if (!rules.adultWords().isEmpty() && !immunityChecker.hasImmunity(player, ViolationType.ADULT_CONTENT)) {
                for (String adult : rules.adultWords()) {
                    double adultSim = SimilarityChecker.getSimilarityRatio(word, adult, 0.0);
                    if (adultSim >= rules.highThreshold()) {
                        return new BrotherMatch(ViolationType.ADULT_CONTENT, adult, "Adult content: " + adult, rawWord);
                    }
                    if (adultSim >= rules.mediumThreshold()) {
                        suspicion.offer(adultSim, ViolationType.ADULT_CONTENT, rawWord, adult, brother);
                    }
                }
            }

            double maxSim = 0.0;
            String suspectedInsult = "";
            for (String insult : rules.insultWords()) {
                double sim = SimilarityChecker.getSimilarityRatio(word, insult, 0.0);
                if (sim > maxSim) {
                    maxSim = sim;
                    suspectedInsult = insult;
                }
            }

            if (maxSim >= rules.highThreshold()) {
                ViolationType candType = ViolationType.INSULT;
                if (hasStaffContext(normWords, rules.staffTitles(), i)) candType = ViolationType.STAFF_INSULT;
                else if (hasFamilyContext(normWords, rules.familyWords(), i)) candType = ViolationType.FAMILY_INSULT;

                if (!immunityChecker.hasImmunity(player, candType)) {
                    return new BrotherMatch(candType, suspectedInsult, "Insult (fuzzy): " + suspectedInsult, rawWord);
                }
            } else if (maxSim >= rules.mediumThreshold()) {
                ViolationType candType = ViolationType.INSULT;
                if (hasStaffContext(normWords, rules.staffTitles(), i)) candType = ViolationType.STAFF_INSULT;
                else if (hasFamilyContext(normWords, rules.familyWords(), i)) candType = ViolationType.FAMILY_INSULT;

                if (!immunityChecker.hasImmunity(player, candType)) {
                    suspicion.offer(maxSim, candType, rawWord, suspectedInsult, brother);
                }
            }
        }

        if (rules.customPatterns() != null && !rules.customPatterns().isEmpty()) {
            for (Map.Entry<ViolationType, List<Pattern>> entry : rules.customPatterns().entrySet()) {
                ViolationType vType = entry.getKey();
                if (immunityChecker.hasImmunity(player, vType)) continue;

                for (Pattern pattern : entry.getValue()) {
                    if (pattern.matcher(normalized).find()) {
                        return new BrotherMatch(vType, pattern.pattern(), "RE2J Pattern Match (" + brother + "): " + pattern.pattern(), normalized);
                    }
                }
            }
        }

        return null;
    }
    public InspectionResult inspect(String rawText) {
        TextNormalizer.NormalizedResult res = TextNormalizer.process(rawText);
        ImmunityChecker noImmunity = (p, t) -> false;

        Map<String, AdMatch> ads = detectAdvertisement(rawText, res, null, noImmunity);
        if (!ads.isEmpty()) {
            AdMatch primary = ads.values().iterator().next();
            return new InspectionResult(
                    rawText,
                    res.smartText(),
                    primary.type().name(),
                    rawText,
                    primary.dictWord(),
                    primary.reason() + " | Detected by: " + String.join(", ", ads.keySet()) + " | " + variants(res)
            );
        }

        Map<String, BrotherMatch> successfulBrothers = new LinkedHashMap<>();
        Suspicion suspicion = new Suspicion();

        BrotherMatch smartMatch = evaluateNormalized(res.smartText(), null, noImmunity, suspicion, "Smart");
        if (smartMatch != null) successfulBrothers.put("Smart", smartMatch);

        BrotherMatch rudeMatch = evaluateNormalized(res.rudeText(), null, noImmunity, suspicion, "Rude");
        if (rudeMatch != null) successfulBrothers.put("Rude", rudeMatch);

        BrotherMatch despondentMatch = evaluateNormalized(res.despondentText(), null, noImmunity, suspicion, "Despondent");
        if (despondentMatch != null) successfulBrothers.put("Despondent", despondentMatch);

        if (!successfulBrothers.isEmpty()) {
            String detectedBy = String.join(", ", successfulBrothers.keySet());
            BrotherMatch primary = successfulBrothers.values().iterator().next();

            return new InspectionResult(
                    rawText,
                    res.smartText(),
                    primary.type().name(),
                    primary.rawMatchWord(),
                    primary.matchedWord(),
                    primary.reasonDetail() + " | Detected by: " + detectedBy + " | " + variants(res)
            );
        }

        if (suspicion.type != null) {
            return new InspectionResult(
                    rawText,
                    res.smartText(),
                    "SUSPECTED OF " + suspicion.type.name(),
                    suspicion.inputWord,
                    suspicion.dictWord,
                    String.format("Similarity to '%s': %.0f%% (thresholds: medium %.0f%%, high %.0f%%) | Closest by: %s | %s",
                            suspicion.dictWord, suspicion.similarity * 100,
                            rules.mediumThreshold() * 100, rules.highThreshold() * 100,
                            suspicion.brother, variants(res))
            );
        }

        return new InspectionResult(
                rawText,
                res.smartText(),
                "CLEAN",
                "-",
                "-",
                "No violations detected | " + variants(res)
        );
    }

    private static String variants(TextNormalizer.NormalizedResult res) {
        return "Smart: [" + res.smartText() + "] | Rude: [" + res.rudeText()
                + "] | Despondent: [" + res.despondentText() + "]";
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