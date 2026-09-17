package org.apve;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.*;
import java.util.regex.Pattern;

public class FoolProof { 

    private final YamlConfiguration config;

    public static final String[] LATINIC_KEYS = {
            "insult-words", "family-insult-words", "family-roots",
            "bad-roots", "expressive-words", "ad-words",
            "allowed-words", "adult-words", "adult-roots", "social", "staff-tituls"
    };

    public static final String[] CATEGORIES = {
            "insult", "family-insult", "ad-dist", "soc-media-dist", "adult-content", "spam", "caps", "staff-insult"
    };

    private static final String STRICT_LATIN_PATTERN = "^[a-z]+$";
    private static final Pattern DURATION_PATTERN = Pattern.compile("^(\\d+(s|m|h|d|w|mo|y))+$", Pattern.CASE_INSENSITIVE);
    public static final Set<String> PUNISHMENT_TYPE = Set.of("mute", "ban", "banip", "none", "kick");

    public FoolProof(YamlConfiguration config) {
        this.config = config;
    }

    public record ConfigError(String path, String message, int severity) {
        public ConfigError(String message, int severity) {
            this(null, message, severity);
        }
    }

    public ValidationResult validateAll() {
        List<ConfigError> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        validateThresholds(errors);

        Map<String, Set<String>> cachedDictionaries = new HashMap<>();
        for (String key : LATINIC_KEYS) {
            validateDictionary(key, cachedDictionaries, errors);
        }

        Set<String> blockedDomains = validateGenericList("blocked-domains", warnings, errors);
        Set<String> interceptedCommands = validateGenericList("intercepted-commands", warnings, errors);

        Map<String, Integer> priorityMap = validatePriorities(errors);

        Set<String> permKeywords = validatePunishments(errors, warnings);

        boolean warnsEnabled = config.getBoolean("warns.warns-is-enabled", false);
        boolean punEnabled = config.getBoolean("punishments.punishments-is-enabled", false);
        boolean auditEnabled = config.getBoolean("audit-mode", false);


        if (warnsEnabled && !punEnabled) {
            warnings.add("Warns are enabled but punishments are disabled. Proper plugin operation is not guaranteed.");
        }
        if (auditEnabled && (warnsEnabled || punEnabled)) {
            errors.add(new ConfigError("Audit mode is incompatible with warns or punishments.", 3));
        }
        validateWarns(errors, permKeywords);

        for (String cat : CATEGORIES) {
            validateCategory(cat, errors, permKeywords);
        }

        validateCommandMessages(errors);

        return new ValidationResult(errors, warnings, priorityMap, cachedDictionaries, blockedDomains, interceptedCommands);
    }

    private void validateThresholds(List<ConfigError> errors) {
        boolean highValid = checkDoubleRange("thresholds.high", 0.0, 1.0, errors);
        boolean mediumValid = checkDoubleRange("thresholds.medium", 0.0, 1.0, errors);

        if (highValid && mediumValid) {
            double high = config.getDouble("thresholds.high");
            double medium = config.getDouble("thresholds.medium");
            if (high <= medium) {
                errors.add(new ConfigError("thresholds.high", String.format("'thresholds.high' (%f) cannot be lower or equal to 'thresholds.medium' (%f).", high, medium), 3));
            }
        }
    }

    private void validateDictionary(String key, Map<String, Set<String>> cache, List<ConfigError> errors) {
        if (!config.contains(key)) {
            errors.add(new ConfigError(key, "Missing dictionary list: '" + key + "'", 4));
            return;
        }
        
        List<String> words = config.getStringList(key);
        Set<String> validWords = new HashSet<>();

        for (int i = 0; i < words.size(); i++) {
            String word = words.get(i);
            int line = i + 1; 

            if (word == null || word.isBlank()) {
                errors.add(new ConfigError(key, String.format("In dictionary '%s' on line %d there is an illegal input: empty/null value.", key, line), 1));
                continue;
            }

            String lower = word.toLowerCase(Locale.ROOT);
            if (!lower.matches(STRICT_LATIN_PATTERN)) {
                errors.add(new ConfigError(key, String.format("In dictionary '%s' on line %d there is an illegal input: '%s'. (Only latin characters allowed)", key, line, word), 2));
            } else {
                validWords.add(lower);
            }
        }
        cache.put(key, validWords);
    }

    private Set<String> validateGenericList(String path, List<String> warnings, List<ConfigError> errors) {
        if (!config.contains(path)) {
            warnings.add("List '" + path + "' is missing.");
            return Collections.emptySet();
        }
        List<String> list = config.getStringList(path);
        if (list.isEmpty()) {
            warnings.add("List '" + path + "' is empty. Proper plugin operation is not guaranteed.");
            return Collections.emptySet();
        }
        Set<String> set = new HashSet<>();
        for (int i = 0; i < list.size(); i++) {
            String item = list.get(i);
            if (item == null || item.isBlank()) {
                errors.add(new ConfigError(path, String.format("In list '%s' at position %d there is an illegal blank/null entry.", path, i + 1), 1));
            } else {
                set.add(item.toLowerCase(Locale.ROOT));
            }
        }
        return set;
    }

    private Map<String, Integer> validatePriorities(List<ConfigError> errors) {
        Map<String, Integer> map = new HashMap<>();
        ConfigurationSection sec = config.getConfigurationSection("priority");
        if (sec == null) {
            errors.add(new ConfigError("priority", "Section 'priority' is missing.", 4));
            return map;
        }
        Set<Integer> unique = new HashSet<>();
        for (String cat : CATEGORIES) {
            String path = "priority." + cat;
            if (!sec.isInt(cat)) {
                errors.add(new ConfigError(path, "Priority '" + cat + "' is missing or not an integer.", 3));
                continue;
            }
            int val = sec.getInt(cat);
            if (val < 0) {
                errors.add(new ConfigError(path, "Priority '" + cat + "' must be a non-negative integer.", 3));
            }
            if (!unique.add(val)) {
                errors.add(new ConfigError(path, "Duplicate priority value '" + val + "' found for '" + cat + "'.", 3));
            }
            map.put(cat, val);
        }
        return map;
    }

    private Set<String> validatePunishments(List<ConfigError> errors, List<String> warnings) {
        checkBool("punishments.punishments-is-enabled", errors, 3);
        
        String[] cmds = {"mute-command", "ban-command", "banip-command", "kick-command"};
        for (String cmd : cmds) {
            String path = "punishments." + cmd;
            String val = config.getString(path, "");
            if (val.isBlank()) {
                errors.add(new ConfigError(path, "Command '" + path + "' cannot be empty.", 4));
            } else if (!val.contains("%player%") && !val.contains("%ip%")) {
                errors.add(new ConfigError(path, "Command '" + path + "' must contain %player% (or %ip%).", 4));
            }
        }

        List<String> rawPerm = config.getStringList("punishments.perm-keywords");
        if (rawPerm.isEmpty()) {
            warnings.add("List 'punishments.perm-keywords' is empty.");
        }
        
        Set<String> perms = new HashSet<>();
        for (int i = 0; i < rawPerm.size(); i++) {
            String k = rawPerm.get(i);
            if (k == null || k.isBlank()) {
                errors.add(new ConfigError("punishments.perm-keywords", String.format("In 'punishments.perm-keywords' at position %d there is a blank value.", i + 1), 1));
            } else {
                perms.add(k.toLowerCase(Locale.ROOT));
            }
        }
        return perms;
    }

    private void validateWarns(List<ConfigError> errors, Set<String> permKeywords) {
        checkBool("warns.warns-is-enabled", errors, 3);
        checkBool("warns.warn-limit-is-enabled", errors, 3);
        checkBool("warns.warn_reset_when_server_restarts", errors, 3);
        checkBool("warns.temporary-warns", errors, 3);
        
        checkIntMin("warns.warn-limit", 1, errors, 3);
        checkIntMin("warns.warn_reset_count", 1, errors, 2);
        
        checkString("warns.warn-message", errors, 2);
        checkString("warns.last-warn-message", errors, 2);
        checkDuration("warns.warn-reset-time", errors, permKeywords, 3);
    }
    private void validateOther(List<ConfigError> errors, Set<String> permKeyWords) {
        checkBool("audit-mode", errors, 3);
        checkBool("console-log", errors, 3);
        checkBool("notifies", errors, 3);
    }

    private void validateCategory(String cat, List<ConfigError> errors, Set<String> permKeywords) {
        if (!config.contains(cat)) {
            errors.add(new ConfigError(cat, "Missing module section: '" + cat + "'", 4));
            return;
        }

        checkBool(cat + ".is-enabled", errors, 3);
        checkBool(cat + ".punishment-is-enabled", errors, 3);

        boolean isBlock = config.getBoolean(cat + ".blocking", false);
        boolean isCensor = config.getBoolean(cat + ".censor", false);
        if (isBlock && isCensor) {
            errors.add(new ConfigError(cat + ".censor", "Category '" + cat + "': 'blocking' and 'censor' cannot both be true simultaneously.", 3));
        }

        String typePath = cat + ".type";
        String type = config.getString(typePath, "none").toLowerCase(Locale.ROOT);
        if (!PUNISHMENT_TYPE.contains(type)) {
            errors.add(new ConfigError(typePath, "Category '" + cat + "': invalid punishment type '" + type + "'. Allowed: " + PUNISHMENT_TYPE, 4));
        }

        if (!type.equals("none")) {
            checkDuration(cat + ".duration", errors, permKeywords, 4);
        }

        if (config.getBoolean(cat + ".punishment-is-enabled")) checkString(cat + ".reason", errors, 2);
        if (isBlock) checkString(cat + ".blocking-reason", errors, 2);
        if (isCensor) checkString(cat + ".censor-reason", errors, 2);

        if (cat.equals("spam")) {
            checkIntMin("spam.max-similar-messages", 1, errors, 3);
            checkIntMin("spam.time-window-seconds", 1, errors, 3);
            checkDoubleRange("spam.similarity-threshold", 0.0, 1.0, errors);
        }
        if (cat.equals("caps")) {
            checkIntMin("caps.min-message-length", 1, errors, 2);
            checkIntRange("caps.min-caps-percentage", 1, 100, errors, 3);
        }
    }

    private void validateCommandMessages(List<ConfigError> errors) {
        String[] msgPaths = {
            "command-msg.perm-fail", "command-msg.help-command-view-req", "command-msg.cfg-reload-msg",
            "command-msg.invalid-syntax", "command-msg.invalid-number", "command-msg.warns.no-warns",
            "command-msg.warns.show", "command-msg.warns.removed", "command-msg.warns.cleared",
            "command-msg.violation-notify-msg", "command-msg.notify-activate-msg", "command-msg.notify-disabled-msg", "command-msg.player-only"
        };
        for (String path : msgPaths) checkString(path, errors, 1);
        
        List<String> helpMsgs = config.getStringList("command-msg.help-command-msg");
        if (helpMsgs.isEmpty()) {
            errors.add(new ConfigError("command-msg.help-command-msg", "List 'command-msg.help-command-msg' is missing or empty.", 2));
        } else {
            for (int i = 0; i < helpMsgs.size(); i++) {
                String msg = helpMsgs.get(i);
                if (msg == null || msg.isBlank()) {
                    errors.add(new ConfigError("command-msg.help-command-msg", String.format("In list 'command-msg.help-command-msg' at line %d there is a blank message.", i + 1), 1));
                }
            }
        }
    }

    private void checkBool(String path, List<ConfigError> errors, int severity) {
        if (!config.contains(path)) {
            errors.add(new ConfigError(path, "Missing boolean parameter: '" + path + "'", severity));
        } else if (!config.isBoolean(path)) {
            errors.add(new ConfigError(path, "Parameter '" + path + "' must be boolean (true/false).", severity));
        }
    }

    private void checkIntMin(String path, int min, List<ConfigError> errors, int severity) {
        if (!config.contains(path)) {
            errors.add(new ConfigError(path, "Missing integer parameter: '" + path + "'", severity));
        } else if (!config.isInt(path)) {
            errors.add(new ConfigError(path, "Parameter '" + path + "' must be an integer number.", severity));
        } else {
            int val = config.getInt(path);
            if (val < min) {
                errors.add(new ConfigError(path, String.format("Parameter '%s' must be at least %d (got %d).", path, min, val), severity));
            }
        }
    }

    private void checkIntRange(String path, int min, int max, List<ConfigError> errors, int severity) {
        if (!config.contains(path)) {
            errors.add(new ConfigError(path, "Missing integer parameter: '" + path + "'", severity));
        } else if (!config.isInt(path)) {
            errors.add(new ConfigError(path, "Parameter '" + path + "' must be an integer number.", severity));
        } else {
            int val = config.getInt(path);
            if (val < min || val > max) {
                errors.add(new ConfigError(path, String.format("Parameter '%s' must be between %d and %d (got %d).", path, min, max, val), severity));
            }
        }
    }

    private boolean checkDoubleRange(String path, double min, double max, List<ConfigError> errors) {
        if (!config.contains(path)) {
            errors.add(new ConfigError(path, "Missing decimal parameter: '" + path + "'", 3));
            return false;
        }
        if (!config.isDouble(path) && !config.isInt(path)) {
            errors.add(new ConfigError(path, "Parameter '" + path + "' must be a valid decimal number.", 3));
            return false;
        }
        double val = config.getDouble(path);
        if (val <= min || val > max) {
            errors.add(new ConfigError(path, String.format("Parameter '%s' must be between %f and %f (got %f).", path, min, max, val), 3));
            return false;
        }
        return true;
    }

    private void checkString(String path, List<ConfigError> errors, int severity) {
        if (!config.contains(path)) {
            errors.add(new ConfigError(path, "Missing text parameter: '" + path + "'", severity));
            return;
        }
        String val = config.getString(path);
        if (val == null || val.trim().isEmpty()) {
            errors.add(new ConfigError(path, "Text parameter '" + path + "' is missing or completely empty.", severity));
        }
    }

    private void checkDuration(String path, List<ConfigError> errors, Set<String> permKeywords, int severity) {
        if (!config.contains(path)) {
            errors.add(new ConfigError(path, "Duration parameter '" + path + "' is missing.", severity));
            return;
        }
        String val = config.getString(path);
        if (val == null || val.isBlank()) {
            errors.add(new ConfigError(path, "Duration '" + path + "' is missing or empty.", severity));
            return;
        }
        String clean = val.trim().replaceAll("^[\"']+|[\"']+$", "").toLowerCase(Locale.ROOT);
        if (!permKeywords.contains(clean) && !DURATION_PATTERN.matcher(clean).matches()) {
            errors.add(new ConfigError(path, "Invalid duration format in '" + path + "': '" + val + "'. Use valid time formats (e.g., 30m, 8h) or perm-keywords.", severity));
        }
    }

    public record ValidationResult(
            List<ConfigError> errors,
            List<String> warnings,
            Map<String, Integer> priorityMap,
            Map<String, Set<String>> dictionaries,
            Set<String> blockedDomains,
            Set<String> interceptedCommands
    ) {
        public boolean hasErrors() { return !errors.isEmpty(); }
        public boolean hasWarnings() { return !warnings.isEmpty(); }
        public boolean containsWord(String dictionaryKey, String word) {
            Set<String> set = dictionaries.get(dictionaryKey);
            return set != null && set.contains(word.toLowerCase(Locale.ROOT));
        }
        public int maxSeverity() {
            return errors.stream().mapToInt(ConfigError::severity).max().orElse(0);
        }
    }
} 