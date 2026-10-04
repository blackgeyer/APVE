package org.apve.nci;

import java.util.*;
import java.util.regex.Pattern;

import org.apve.engine.*;

public class StructureModule {

    public enum ViolationType {
        INSULT, FAMILY_INSULT, STAFF_INSULT, ADVERTISEMENT, SOCIAL_MEDIA, ADULT_CONTENT, SPAM, CAPS;
        private int priority;
        public int getPriority() { return priority; }
        public void setPriority(int priority) { this.priority = priority; }
    }

    public record StoredViolation(ViolationType type, ViolationRule rule, String reasonDetail, String badWord) {}

    public record SpamEntry(String normalizedText, long timestamp) {}

    public record ViolationRule(boolean enabled, boolean punishEnabled, String type, String duration, String reason, boolean block, String blockReason, boolean censor, String censorReason) {}

    public record GlobalConfig(
            boolean consoleLog, boolean notifiesEnabled, boolean warnsIsEnabled, boolean warnLimitIsEnabled,
            int warnLimit, String warnMessage, String lastWarnMessage, boolean tempWarns,
            String warnResetTime, int warnResetCount, Map<ViolationType, ViolationRule> rules
    ) {}

    public record ChatRulesCache(
            double highThreshold, double mediumThreshold, boolean auditMode, Set<String> allowedWords,
            List<String> insultWords, Set<String> familyWords, Set<String> staffTitles, Set<String> expressiveWords,
            List<String> adultWords, List<String> socialWords, Pattern domainPattern, Set<String> interceptedCommands,
            boolean spamModuleEnabled, int spamMaxCount, long spamWindowMs, double spamSimThreshold,
            boolean capsModuleEnabled, int capsMinLength, int capsMinPct, AhoCorasick ahoCorasick
    ) {}

    public record InspectionResult(
            String rawText, String normalizedText, String violationType,
            String matchedInputWord, String matchedDictWord, String detail
    ) {}

    public record AnalysisResult(ViolationType type, String matchedWord, String reasonDetail, String rawMatchWord) {}

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
}
