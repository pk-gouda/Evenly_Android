package com.prathik.evenly_android.model.receipt;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OcrCleaner — cleans raw OCR text before grid building / parsing.
 *
 * Pipeline (applied in order per line):
 *  1. Strip trailing pipe characters
 *  2. fi/fl ligature → "$"
 *  3. Fix letter-digits in price tokens
 *  4. Digit-1 misread as letter at word start
 *  5. Digit-2 misread as R at word start
 *  6. Truncated AM/PM
 *  7. Known word-level OCR corrections (table-driven)
 *  8. Normalize degree/special symbols
 *  9. Collapse excess whitespace
 *
 * NOTE: All Matcher loops use StringBuffer (not StringBuilder)
 * to stay compatible with API < 34.
 */
public class OcrCleaner {

    private static final Pattern TRAILING_PIPE =
            Pattern.compile("\\s*\\|\\s*$");

    private static final Pattern FI_LIGATURE_PRICE =
            Pattern.compile("\\b(?:fi|fl)(\\d+[.,]\\d{2})\\b");

    private static final Pattern PRICE_TOKEN =
            Pattern.compile("\\$( ?)([0-9lLIiOoRr]{1,6}[.,][0-9lLIiOoRr]{2})");

    private static final Pattern ONE_AS_LETTER =
            Pattern.compile("(?<!\\d)1([a-z]{2,})");

    private static final Pattern TWO_AS_R =
            Pattern.compile("(?<!\\d)2([a-z]{2,})");

    private static final Pattern TRUNCATED_PM =
            Pattern.compile("(\\d{1,2}:\\d{2}(?::\\d{2})?)([PA])(?=\\s|$|\\|)");

    // Word-level fixes: { regex-string, replacement }
    private static final String[][] WORD_FIXES = {
            // Generic OCR word errors
            {"validatio\\b",    "validation"},
            {"validatic\\b",    "validation"},
            {"transactio\\b",   "transaction"},
            {"appreciat\\b",    "appreciate"},
            {"\\btota[!]",      "Total"},
            {"\\btota1\\b",     "Total"},
            {"\\bsubtota1\\b",  "Subtotal"},
            {"\\bautti\\b",     "Auth"},
            {"\\bpiivacy\\b",   "Privacy"},
            {"\\bQUr\\b",       "Our"},
            {"\\boUr\\b",       "Our"},
            {"\\byoi\\b",       "you"},
            // Walmart-specific
            {"\\bwal-mai't\\b", "WAL-MART"},
            {"\\bwalmai't\\b",  "WALMART"},
            {"\\brol1back\\b",  "ROLLBACK"},
            // Target-specific
            {"\\btai'get\\b",   "TARGET"},
            {"\\brai'get\\b",   "TARGET"},
            {"\\bredcai'd\\b",  "REDCARD"},
            {"\\bcai'twheel\\b","CARTWHEEL"},
            // Whole Foods specific
            {"\\bwhole f[o0]ods\\b", "WHOLE FOODS"},
            {"\\bwh[o0]le f[o0]ods\\b", "WHOLE FOODS"},
            // Trader Joe's specific
            {"\\btrader j[o0]e\\b",   "TRADER JOE"},
            {"\\btrader j[o0]es\\b",  "TRADER JOES"},
            // Restaurant
            {"\\bgratuiti\\b",  "gratuity"},
            {"\\bgratuty\\b",   "gratuity"},
            {"\\bservei'\\b",   "server"},
    };

    private static final Pattern[] WORD_PATTERNS;
    private static final String[]  WORD_REPLACEMENTS;

    static {
        WORD_PATTERNS     = new Pattern[WORD_FIXES.length];
        WORD_REPLACEMENTS = new String[WORD_FIXES.length];
        for (int i = 0; i < WORD_FIXES.length; i++) {
            WORD_PATTERNS[i]     = Pattern.compile(WORD_FIXES[i][0], Pattern.CASE_INSENSITIVE);
            WORD_REPLACEMENTS[i] = WORD_FIXES[i][1];
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Clean a single raw OCR line. */
    public static String cleanLine(String raw) {
        if (raw == null || raw.isEmpty()) return "";

        String t = raw;

        // 1. Strip trailing pipes
        t = TRAILING_PIPE.matcher(t).replaceAll("");

        // 2. fi/fl ligature → "$"
        t = replaceFiLigature(t);

        // 3. Fix letter confusions inside price tokens
        t = fixPriceToken(t);

        // 4. Digit-1 → letter at word start
        t = fixLeadingOneAsLetter(t);

        // 5. Digit-2 → R at word start
        t = fixLeadingTwoAsR(t);

        // 6. Truncated AM/PM
        t = TRUNCATED_PM.matcher(t).replaceAll("$1$2M");

        // 7. Word-level corrections
        for (int i = 0; i < WORD_PATTERNS.length; i++) {
            t = WORD_PATTERNS[i].matcher(t).replaceAll(WORD_REPLACEMENTS[i]);
        }

        // 8. Normalize special symbols
        t = t.replace("—", "-").replace("–", "-")
                .replace("\u00b0", " ")   // degree sign
                .replace("\u00ae", "")    // registered trademark
                .replace("\u2122", "")    // TM symbol
                .replace("\u00a9", "");   // copyright symbol

        // 9. Collapse excess whitespace
        t = t.replaceAll("\\s{2,}", " ").trim();

        return t;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static String replaceFiLigature(String s) {
        Matcher m = FI_LIGATURE_PRICE.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement("$" + m.group(1)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String fixPriceToken(String s) {
        Matcher m = PRICE_TOKEN.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String space  = m.group(1);
            String digits = m.group(2);
            if (digits == null) { m.appendReplacement(sb, Matcher.quoteReplacement(m.group())); continue; }
            String fixed = digits
                    .replace('l', '1').replace('L', '1')
                    .replace('I', '1').replace('i', '1')
                    .replace('O', '0').replace('o', '0')
                    .replace('R', '8').replace('r', '8');
            m.appendReplacement(sb, Matcher.quoteReplacement("$" + space + fixed));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String fixLeadingOneAsLetter(String s) {
        Matcher m = ONE_AS_LETTER.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String suffix = m.group(1);
            if (suffix == null || suffix.isEmpty()) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group())); continue;
            }
            String rep = Character.toUpperCase(suffix.charAt(0)) + suffix.substring(1);
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String fixLeadingTwoAsR(String s) {
        Matcher m = TWO_AS_R.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String suffix = m.group(1);
            if (suffix == null || suffix.isEmpty()) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group())); continue;
            }
            char first = suffix.charAt(0);
            if (first == 'e' || first == 'a' || first == 'o' || first == 'u' || first == 'i') {
                m.appendReplacement(sb, Matcher.quoteReplacement("R" + suffix));
            } else {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }
}