package org.apve;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

public class TextNormalizer {

    private static final String[] TRANSLIT = new String[65536];

    private static final Pattern MINECRAFT_COLOR_PATTERN = Pattern.compile("(?i)[&§]([0-9a-fk-or]|#[0-9a-f]{6}|x([&§][0-9a-f]){6})");
    private static final Pattern INVISIBLE_CHARS_PATTERN = Pattern.compile("[\\p{Cf}\\p{Cc}\\u200B-\\u200D\\uFEFF\\u00AD]");
    private static final Pattern COMBINING_MARKS_PATTERN = Pattern.compile("\\p{M}");
    private static final Pattern NON_LATIN_PATTERN = Pattern.compile("[^a-z\\s]");

    static {
        put('а', "a");  put('б', "b");  put('в', "v");  put('г', "g");  put('д', "d");
        put('е', "e");  put('ё', "e");  put('ж', "zh"); put('з', "z");  put('и', "i");
        put('й', "y");  put('к', "k");  put('л', "l");  put('м', "m");  put('н', "n");
        put('о', "o");  put('п', "p");  put('р', "r");  put('с', "s");  put('т', "t");
        put('у', "u");  put('ф', "f");  put('х', "h");  put('ц', "c");  put('ч', "ch");
        put('ш', "sh"); put('щ', "sch");put('ъ', "");   put('ы', "y");  put('ь', "b");
        put('э', "e");  put('ю', "yu"); put('я', "ya");

        put('α', "a");  put('β', "b");  put('γ', "g");  put('δ', "d");  put('ε', "e");
        put('η', "n");  put('θ', "th"); put('ι', "i");  put('κ', "k");  put('λ', "l");
        put('μ', "m");  put('ν', "v");  put('ο', "o");  put('π', "p");  put('ρ', "r");
        put('σ', "s");  put('τ', "t");  put('υ', "u");  put('χ', "x");  put('ψ', "ps");
        put('ω', "w");

        put('ø', "o");  put('ð', "d");  put('þ', "th"); put('æ', "ae"); put('œ', "oe");
        put('ŋ', "n");  put('ß', "s");  put('ł', "l");  put('đ', "d");  put('ħ', "h");
        put('ĸ', "k");  put('ı', "i");

        put('0', "o");  put('1', "i");  put('3', "z");  put('4', "ch");
        put('5', "s");  put('6', "b");  put('8', "b");

        put('@', "a");  put('$', "s");  put('!', "i");  put('|', "i");  put('+', "t");
        put('€', "e");  put('£', "l");  put('¥', "y");  put('§', "");

        put('*', "");   put('_', "");   put('-', "");   put('.', "");   put(',', "");
        put('/', "");   put('\\', "");  put('~', "");   put('`', "");   put('\'', "");
        put('"', "");   put('=', "");   put('^', "");   put('#', "");   put('&', "");
        put('(', "");   put(')', "");   put('[', "");   put(']', "");   put('{', "");   put('}', "");
    }

    private static void put(char ch, String replacement) {
        TRANSLIT[ch] = replacement;
    }

    public static String normalize(String input) {
        if (input == null || input.trim().isEmpty()) return "";

        String str = MINECRAFT_COLOR_PATTERN.matcher(input).replaceAll("");
        str = INVISIBLE_CHARS_PATTERN.matcher(str).replaceAll("");
        str = Normalizer.normalize(str, Normalizer.Form.NFKC);
        str = str.toLowerCase(Locale.ROOT);
        str = COMBINING_MARKS_PATTERN.matcher(str).replaceAll("");

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
        str = collapseDuplicates(str);

        return str.trim();
    }

    public static String normalizeForDomain(String input) {
        if (input == null || input.trim().isEmpty()) return "";

        String str = MINECRAFT_COLOR_PATTERN.matcher(input).replaceAll("");
        str = INVISIBLE_CHARS_PATTERN.matcher(str).replaceAll("");
        str = str.toLowerCase(Locale.ROOT);

        str = str.replaceAll("(\\(dot\\)|\\[dot\\]|\\{dot\\}|\\bdot\\b)", ".");
        
        str = str.replaceAll("\\s*\\.\\s*", ".");

        return str;
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
}