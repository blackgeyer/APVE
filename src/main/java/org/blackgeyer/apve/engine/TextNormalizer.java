package org.blackgeyer.apve.engine;

import java.text.Normalizer;
import java.util.Locale;
import com.google.re2j.Pattern;

public class TextNormalizer {

    private static final String[] TRANSLIT = new String[65536];

    private static final Pattern MINECRAFT_COLOR_PATTERN = Pattern.compile("(?i)[&§]([0-9a-fk-or]|#[0-9a-f]{6}|x([&§][0-9a-f]){6})");
    private static final Pattern INVISIBLE_CHARS_PATTERN = Pattern.compile("[\\p{Cf}\\p{Cc}\\x{200B}-\\x{200D}\\x{FEFF}\\x{00AD}]");
    private static final Pattern COMBINING_MARKS_PATTERN = Pattern.compile("\\p{M}");
    private static final Pattern NON_LATIN_PATTERN = Pattern.compile("[^a-z0-9\\s]");

    static {
        put('а', "a");  put('б', "b");  put('в', "v");  put('г', "g");  put('д', "d");
        put('е', "e");  put('ё', "e");  put('ж', "zh"); put('з', "z");  put('и', "i");
        put('й', "y");  put('к', "k");  put('л', "l");  put('м', "m");  put('н', "n");
        put('о', "o");  put('п', "p");  put('р', "r");  put('с', "s");  put('т', "t");
        put('у', "u");  put('ф', "f");  put('ц', "c");  put('ч', "ch");
        put('ш', "sh"); put('щ', "sch");put('ъ', "");   put('ы', "y");  put('ь', "b");
        put('э', "e");  put('ю', "yu"); put('я', "ya"); put('х', "h");

        put('α', "a");  put('β', "b");  put('γ', "y");  put('δ', "d");  put('ε', "e");
        put('η', "n");  put('θ', "th"); put('ι', "i");  put('κ', "k");  put('λ', "l");
        put('μ', "u");  put('ν', "v");  put('ο', "o");  put('π', "p");  put('ρ', "r");
        put('σ', "o");  put('τ', "t");  put('υ', "u");  put('χ', "x"); 
        put('ω', "w");

        put('ø', "o");  put('ð', "o");  put('þ', "b"); put('æ', "ae"); put('œ', "oe");
        put('ŋ', "n");  put('ß', "b");  put('ł', "l");  put('đ', "d");  put('ħ', "h");
        put('ĸ', "k");  put('ı', "i");
    }

    private static void put(char ch, String replacement) {
        TRANSLIT[ch] = replacement;
    }

    private static String baseClean(String input) {
        if (input == null || input.trim().isEmpty()) return "";
        String str = MINECRAFT_COLOR_PATTERN.matcher(input).replaceAll("");
        str = INVISIBLE_CHARS_PATTERN.matcher(str).replaceAll("");
        str = Normalizer.normalize(str, Normalizer.Form.NFKC);
        str = str.toLowerCase(Locale.ROOT);
        return COMBINING_MARKS_PATTERN.matcher(str).replaceAll("");
    }

    public record NormalizedResult(
            String smartText,   
            String rudeText,     
            String despondentText,
            String domainText    
    ) {}

    public static NormalizedResult process(String rawText) {
        return new NormalizedResult(
                SmartBrother.normalize(rawText),
                RudeBrother.normalize(rawText),
                DespondentBrother.normalize(rawText),
                normalizeForDomain(rawText)
        );
    }

    public static class SmartBrother {
        public static String normalize(String input) {
            String str = baseClean(input);
            StringBuilder builder = new StringBuilder(str.length());
            for (int i = 0; i < str.length(); ) {
                int codePoint = str.codePointAt(i);
                char ch = (char) codePoint;

                String replacement = switch (ch) {
                    case '0' -> "o";
                    case '1', '!', '|' -> "i";
                    case '3', '€' -> "e";
                    case '4', '+' -> "ch";
                    case '5' -> "s";
                    case '6', '8' -> "b";
                    case '@', '$' -> "a";
                    case '£' -> "l";
                    case '¥' -> "y";
                    default -> (codePoint < 65536 && TRANSLIT[codePoint] != null) ? TRANSLIT[codePoint] : null;
                };

                if (replacement != null) {
                    builder.append(replacement);
                } else {
                    builder.appendCodePoint(codePoint);
                }
                i += Character.charCount(codePoint);
            }
            str = builder.toString();
            str = NON_LATIN_PATTERN.matcher(str).replaceAll("");
            return collapseDuplicates(str).trim();
        }
    }

    public static class RudeBrother {
        public static String normalize(String input) {
            String str = baseClean(input);
            str = str.replaceAll("[\\d\\p{Punct}]", "");

            StringBuilder builder = new StringBuilder(str.length());
            for (int i = 0; i < str.length(); ) {
                int codePoint = str.codePointAt(i);
                if (codePoint < 65536 && TRANSLIT[codePoint] != null) {
                    builder.append(TRANSLIT[codePoint]);
                } else {
                    builder.appendCodePoint(codePoint);
                }
                i += Character.charCount(codePoint);
            }
            str = builder.toString();
            str = NON_LATIN_PATTERN.matcher(str).replaceAll("");
            return collapseDuplicates(str).trim();
        }
    }

    public static class DespondentBrother {
        public static String normalize(String input) {
            String str = baseClean(input);
            StringBuilder builder = new StringBuilder(str.length());
            for (int i = 0; i < str.length(); ) {
                int codePoint = str.codePointAt(i);
                if (codePoint < 65536 && TRANSLIT[codePoint] != null) {
                    builder.append(TRANSLIT[codePoint]);
                } else {
                    builder.appendCodePoint(codePoint);
                }
                i += Character.charCount(codePoint);
            }
            str = builder.toString();

            str = str.replaceAll("[^a-z0-9\\s\\.\\-_]", "");
            return collapseDuplicates(str).trim();
        }
    }

    public static String normalizeForDomain(String input) {
        if (input == null || input.trim().isEmpty()) return "";
        String str = baseClean(input);
        str = str.replaceAll("(\\(dot\\)|\\[dot\\]|\\{dot\\}|\\bdot\\b)", ".");
        return str.replaceAll("\\s*\\.\\s*", ".");
    }

    public static String removeSpaces(String normalized) {
        return normalized == null ? "" : normalized.replace(" ", "");
    }

    public static int[] createSpaceMapping(String normalized) {
        if (normalized == null) return new int[0];
        int[] map = new int[normalized.replace(" ", "").length()];
        int compIdx = 0;
        for (int i = 0; i < normalized.length(); i++) {
            if (normalized.charAt(i) != ' ') {
                map[compIdx++] = i;
            }
        }
        return map;
    }

    private static String collapseDuplicates(String text) {
        if (text == null || text.isEmpty()) return "";

        StringBuilder result = new StringBuilder(text.length());
        int lastCodePoint = -1;
        int count = 0;

        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (cp == lastCodePoint) {
                count++;
                if (count <= 1) { 
                    result.appendCodePoint(cp);
                }
            } else {
                lastCodePoint = cp;
                count = 1;
                result.appendCodePoint(cp);
            }
            i += Character.charCount(cp);
        }
        return result.toString();
    }

    public static String normalize(String input) {
        return SmartBrother.normalize(input);
    }
}