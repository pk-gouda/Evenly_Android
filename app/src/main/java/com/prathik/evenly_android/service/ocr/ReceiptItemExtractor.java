// File: com/prathik/evenly_android/service/ocr/ReceiptItemExtractor.java
package com.prathik.evenly_android.service.ocr;

import com.prathik.evenly_android.model.ocr.ReceiptGridRow;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ReceiptItemExtractor {

    public static class ParsedReceiptLite {
        public final List<Item> items = new ArrayList<>();
        public Double subtotal;
        public Double tax;
        public Double tip;
        public Double total;

        public double itemsSum;
        public double confidence;         // 0..1
        public final List<String> warnings = new ArrayList<>();
    }

    public static class Item {
        public final String name;
        public final double amount;
        public final double confidence;

        public Item(String name, double amount, double confidence) {
            this.name = name;
            this.amount = amount;
            this.confidence = confidence;
        }
    }

    // Money: 12.34, 1,234.56, -2.00
    private static final Pattern MONEY = Pattern.compile("[-]?\\d{1,3}(?:,\\d{3})*(?:\\.\\d{2})");
    private static final Pattern SUMMARY_KEYWORDS = Pattern.compile(
            "(?i)\\b(total|subtotal|tax|vat|gst|tip|gratuity|service|amount due|balance due|change|rounding)\\b");
    private static final Pattern PAYMENT_KEYWORDS = Pattern.compile(
            "(?i)\\b(visa|mastercard|amex|discover|card|cash|debit|credit|auth|approval|transaction)\\b");
    private static final Pattern NOISE_KEYWORDS = Pattern.compile(
            "(?i)\\b(thank you|welcome|return|policy|cashier|register|store hours|www|http|phone)\\b");

    /**
     * Main entry: takes receipt grid and returns extracted items + totals + confidence/warnings.
     */
    public static ParsedReceiptLite extract(List<ReceiptGridRow> grid) {
        ParsedReceiptLite out = new ParsedReceiptLite();
        if (grid == null || grid.isEmpty()) {
            out.warnings.add("Empty grid.");
            out.confidence = 0.0;
            return out;
        }

        int totalRowIndex = findTotalRowIndex(grid);
        int summaryStartIndex = findSummaryStartIndex(grid, totalRowIndex);

        // 1) Parse summary numbers (subtotal/tax/tip/total) from summary zone (and TOTAL row)
        parseSummary(out, grid, summaryStartIndex);

        // 2) Extract items only from item zone: rows before summaryStartIndex
        for (int i = 0; i < summaryStartIndex; i++) {
            ReceiptGridRow r = grid.get(i);
            Item item = tryParseItemRow(r);
            if (item != null) out.items.add(item);
        }

        // 3) Sum items
        double sum = 0.0;
        for (Item it : out.items) sum += it.amount;
        out.itemsSum = round2(sum);

        // 4) Reconciliation + confidence
        computeConfidenceAndWarnings(out);

        return out;
    }

    // ---------------------------
    // Zone detection
    // ---------------------------

    private static int findTotalRowIndex(List<ReceiptGridRow> grid) {
        int idx = -1;
        for (int i = grid.size() - 1; i >= 0; i--) {
            String left = safe(grid.get(i).leftText).toLowerCase(Locale.US);
            String right = safe(grid.get(i).rightText);
            if (left.contains("total") && extractMoney(right) != null) {
                idx = i;
                break;
            }
            // Also accept "amount due" / "balance due" if it contains money
            if ((left.contains("amount due") || left.contains("balance due")) && extractMoney(right) != null) {
                idx = i;
                break;
            }
        }
        return idx;
    }

    /**
     * Summary zone starts at earliest occurrence of SUBTOTAL/TAX/TIP above the TOTAL row.
     * If not found, fallback to TOTAL row index; if TOTAL not found, fallback to grid end (no summary).
     */
    private static int findSummaryStartIndex(List<ReceiptGridRow> grid, int totalRowIndex) {
        if (totalRowIndex < 0) {
            // No TOTAL found, treat entire grid as possible items zone
            return grid.size();
        }

        int start = totalRowIndex; // default
        for (int i = totalRowIndex; i >= 0; i--) {
            String left = safe(grid.get(i).leftText).toLowerCase(Locale.US);
            if (left.contains("subtotal") || left.contains("tax") || left.contains("gst") || left.contains("vat")
                    || left.contains("tip") || left.contains("gratuity") || left.contains("service")) {
                start = i;
            }
        }
        return start;
    }

    // ---------------------------
    // Summary parsing
    // ---------------------------

    private static void parseSummary(ParsedReceiptLite out, List<ReceiptGridRow> grid, int summaryStartIndex) {
        for (int i = summaryStartIndex; i < grid.size(); i++) {
            ReceiptGridRow r = grid.get(i);
            String left = safe(r.leftText).toLowerCase(Locale.US);
            String right = safe(r.rightText);

            Double amt = extractMoney(right);
            if (amt == null) continue;

            if (left.contains("subtotal")) out.subtotal = amt;
            else if (left.contains("tax") || left.contains("gst") || left.contains("vat")) out.tax = amt;
            else if (left.contains("tip") || left.contains("gratuity")) out.tip = amt;
            else if (left.contains("total") || left.contains("amount due") || left.contains("balance due")) out.total = amt;
            else {
                // payment/metadata lines that look like money
                if (PAYMENT_KEYWORDS.matcher(left).find()) {
                    // ignore as summary amount, but can be used later if total missing
                    if (out.total == null) out.total = amt; // weak fallback
                }
            }
        }
    }

    // ---------------------------
    // Item parsing
    // ---------------------------

    private static Item tryParseItemRow(ReceiptGridRow r) {
        String left = safe(r.leftText);
        String right = safe(r.rightText);

        // must have a money amount on the right
        Double amount = extractMoney(right);
        if (amount == null) return null;

        // reject zero/negative items? keep discounts as items with negative amount (optional)
        if (Math.abs(amount) < 0.009) return null;

        // reject obvious summary/payment/noise keywords
        if (SUMMARY_KEYWORDS.matcher(left).find()) return null;
        if (PAYMENT_KEYWORDS.matcher(left).find()) return null;
        if (NOISE_KEYWORDS.matcher(left).find()) return null;

        // left must look like a name (letters ratio)
        if (!looksLikeItemName(left)) return null;

        // also reject if left is empty and right is amount -> likely some artifact
        if (left.trim().isEmpty()) return null;

        double conf = baseItemConfidence(left, right);

        return new Item(cleanName(left), amount, conf);
    }

    private static boolean looksLikeItemName(String s) {
        String t = s.trim();
        if (t.length() < 2) return false;

        int letters = 0, digits = 0;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (Character.isLetter(c)) letters++;
            else if (Character.isDigit(c)) digits++;
        }

        // At least some letters, and not mostly digits
        if (letters < 2) return false;
        return letters >= digits; // simple but effective
    }

    private static double baseItemConfidence(String left, String right) {
        double c = 0.65;

        // right looks clean: only money token
        int moneyCount = countMoneyTokens(right);
        if (moneyCount == 1) c += 0.10;
        else if (moneyCount >= 2) c -= 0.10;

        // left longer => likely real item
        if (left.trim().length() >= 6) c += 0.05;

        return clamp01(c);
    }

    // ---------------------------
    // Confidence + warnings
    // ---------------------------

    private static void computeConfidenceAndWarnings(ParsedReceiptLite out) {
        double c = 0.0;

        if (out.items.size() >= 1) c += 0.35;
        if (out.items.size() >= 3) c += 0.10;

        if (out.total != null) c += 0.30;
        if (out.subtotal != null || out.tax != null || out.tip != null) c += 0.10;

        // Reconcile items sum to subtotal/total
        double bestTarget = -1.0;
        String targetName = null;

        if (out.subtotal != null) { bestTarget = out.subtotal; targetName = "subtotal"; }
        else if (out.total != null) { bestTarget = out.total; targetName = "total"; }

        if (bestTarget > 0) {
            double diff = Math.abs(out.itemsSum - bestTarget);
            if (diff <= 0.05) { // within 5 cents
                c += 0.15;
            } else if (diff <= 0.50) {
                c += 0.08;
                out.warnings.add("Small mismatch vs " + targetName + ": " + diff);
            } else {
                c -= 0.10;
                out.warnings.add("Mismatch vs " + targetName + ": " + diff + " (needs review)");
            }
        } else {
            out.warnings.add("No subtotal/total detected—items may need review.");
        }

        out.confidence = clamp01(c);
    }

    // ---------------------------
    // Helpers
    // ---------------------------

    private static String safe(String s) { return s == null ? "" : s; }

    private static String cleanName(String s) {
        String t = safe(s).trim();
        t = t.replaceAll("\\s{2,}", " ");
        // remove leading bullets or weird chars
        t = t.replaceAll("^[^A-Za-z0-9]+", "");
        return t.trim();
    }

    private static Double extractMoney(String s) {
        if (s == null) return null;
        Matcher m = MONEY.matcher(s);
        Double last = null;
        while (m.find()) {
            String token = m.group().replace(",", "");
            try {
                last = Double.parseDouble(token);
            } catch (Exception ignored) {}
        }
        return last;
    }

    private static int countMoneyTokens(String s) {
        if (s == null) return 0;
        Matcher m = MONEY.matcher(s);
        int count = 0;
        while (m.find()) count++;
        return count;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}