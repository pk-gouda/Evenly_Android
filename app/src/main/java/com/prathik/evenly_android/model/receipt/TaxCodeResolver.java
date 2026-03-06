package com.prathik.evenly_android.model.receipt;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves receipt tax codes to actual tax rates.
 *
 * Different store chains and jurisdictions use different coding schemes.
 * We detect the store type from receipt text and apply the right rate table.
 *
 * This is the engine that powers Evenly's fair-split advantage over Splitwise:
 * instead of dividing tax evenly across all items, each item carries exactly
 * the tax rate indicated by its code on the receipt.
 */
public class TaxCodeResolver {

    public enum StoreType {
        COSTCO_CHICAGO,
        COSTCO_GENERIC,
        JEWEL_OSCO,
        WALMART,
        TARGET,
        TRADER_JOES,
        WHOLE_FOODS,
        RESTAURANT,
        GENERIC
    }

    // ── Costco Chicago tax code table ─────────────────────────────────────────
    // A=10.25% general merch, E=1.25% grocery, F=3.0% prepared food
    // Multi-letter codes (AF, EF) = sum of each letter's rate
    private static final Map<String, Double> COSTCO_CHICAGO_LETTER_RATES = new HashMap<>();
    static {
        COSTCO_CHICAGO_LETTER_RATES.put("A", 0.1025);
        COSTCO_CHICAGO_LETTER_RATES.put("E", 0.0125);
        COSTCO_CHICAGO_LETTER_RATES.put("F", 0.0300);
    }

    // Generic Costco (approximate US average when location unknown)
    private static final Map<String, Double> COSTCO_GENERIC_LETTER_RATES = new HashMap<>();
    static {
        COSTCO_GENERIC_LETTER_RATES.put("A", 0.0875);
        COSTCO_GENERIC_LETTER_RATES.put("E", 0.0100);
        COSTCO_GENERIC_LETTER_RATES.put("F", 0.0250);
    }

    // ── Walmart tax code table ────────────────────────────────────────────────
    // Walmart uses single-digit or letter codes; N=non-taxable, T=taxable (varies by state)
    // These are approximations; actual rates vary by state/county
    private static final Map<String, Double> WALMART_CODES = new HashMap<>();
    static {
        WALMART_CODES.put("N", 0.0);    // non-taxable (groceries, etc.)
        WALMART_CODES.put("O", 0.0);    // tax-exempt
        WALMART_CODES.put("T", 0.0875); // taxable general merch (state-dependent avg)
        WALMART_CODES.put("1", 0.0);    // non-taxable food
        WALMART_CODES.put("2", 0.0875); // taxable
        WALMART_CODES.put("X", 0.0875); // taxable
    }

    // ── Target tax code table ─────────────────────────────────────────────────
    // Target uses A/B/C/D etc. and prints tax breakdown at bottom
    // A=taxable general, B=food/grocery (lower), varies by state
    private static final Map<String, Double> TARGET_CODES = new HashMap<>();
    static {
        TARGET_CODES.put("A", 0.0875);  // general merchandise
        TARGET_CODES.put("B", 0.0125);  // grocery/food (IL reduced)
        TARGET_CODES.put("C", 0.0);     // non-taxable
        TARGET_CODES.put("D", 0.0);     // SNAP-eligible / non-taxable
        TARGET_CODES.put("F", 0.0300);  // prepared food
    }

    // ── Detect store type from receipt header text ────────────────────────────
    public static StoreType detectStore(List<String> rawLines) {
        boolean hasCostcoSignal = false;
        boolean hasChicagoSignal = false;
        boolean hasMultiTaxCodes = false;
        int taxCodeLineCount = 0;

        for (String line : rawLines) {
            String lower = line.toLowerCase(java.util.Locale.US);

            // Costco — robust to OCR garbling
            if (lower.contains("costco") || lower.contains("ostco")
                    || lower.contains("wholesale") || lower.contains("weklesale")
                    || lower.contains("mwikesale") || lower.contains("whol esale")) {
                hasCostcoSignal = true;
            }

            // Chicago/IL location
            if (lower.contains("chicago") || lower.contains(" il ")
                    || lower.contains("60608") || lower.contains("60616")
                    || lower.contains("60601") || lower.contains("60654")
                    || lower.contains("ashland") || lower.contains("il 606")) {
                hasChicagoSignal = true;
            }

            // Costco tax breakdown: "A 10.25% Tax", "E 1.25% TAX"
            if (lower.matches("^[aef]\\s+\\d+\\.\\d+%\\s+(tax|tix|txx).*")) {
                taxCodeLineCount++;
            }
        }

        if (taxCodeLineCount >= 2) hasMultiTaxCodes = true;

        if (hasCostcoSignal || hasMultiTaxCodes) {
            return hasChicagoSignal ? StoreType.COSTCO_CHICAGO : StoreType.COSTCO_GENERIC;
        }

        // Check each store in priority order
        for (String line : rawLines) {
            String lower = line.toLowerCase(java.util.Locale.US);
            if (lower.contains("jewel") || lower.contains("osco") || lower.contains("jewel-osco"))
                return StoreType.JEWEL_OSCO;
        }
        for (String line : rawLines) {
            String lower = line.toLowerCase(java.util.Locale.US);
            if (lower.contains("walmart") || lower.contains("wal-mart") || lower.contains("wal mart"))
                return StoreType.WALMART;
        }
        for (String line : rawLines) {
            String lower = line.toLowerCase(java.util.Locale.US);
            if (lower.contains("target") || lower.contains("target.com") || lower.contains("redcard"))
                return StoreType.TARGET;
        }
        for (String line : rawLines) {
            String lower = line.toLowerCase(java.util.Locale.US);
            if (lower.contains("trader joe") || lower.contains("trader joes") || lower.contains("trader joe's"))
                return StoreType.TRADER_JOES;
        }
        for (String line : rawLines) {
            String lower = line.toLowerCase(java.util.Locale.US);
            if (lower.contains("whole foods") || lower.contains("wholefoods") || lower.contains("whole food market"))
                return StoreType.WHOLE_FOODS;
        }

        // Restaurant detection: tip/gratuity/server/table signals
        int restaurantSignals = 0;
        for (String line : rawLines) {
            String lower = line.toLowerCase(java.util.Locale.US);
            if (lower.contains("tip") || lower.contains("gratuity")
                    || lower.contains("server") || lower.contains("table #")
                    || lower.contains("table no") || lower.contains("guest")
                    || lower.contains("dine in") || lower.contains("take out")
                    || lower.contains("check #") || lower.contains("seat")
                    || lower.contains("bartender") || lower.contains("your server")) {
                restaurantSignals++;
            }
        }
        if (restaurantSignals >= 2) return StoreType.RESTAURANT;

        return StoreType.GENERIC;
    }

    // ── Resolve a tax code to a rate ──────────────────────────────────────────
    public static double resolveRate(String taxCode, StoreType storeType) {
        if (taxCode == null || taxCode.trim().isEmpty()) return 0.0;
        String code = taxCode.trim().toUpperCase(java.util.Locale.US);

        switch (storeType) {
            case COSTCO_CHICAGO:
            case COSTCO_GENERIC: {
                Map<String, Double> letterRates = (storeType == StoreType.COSTCO_CHICAGO)
                        ? COSTCO_CHICAGO_LETTER_RATES : COSTCO_GENERIC_LETTER_RATES;
                double total = 0.0;
                boolean anyResolved = false;
                for (char ch : code.toCharArray()) {
                    String letter = String.valueOf(ch);
                    if (letterRates.containsKey(letter)) {
                        total += letterRates.get(letter);
                        anyResolved = true;
                    }
                }
                return anyResolved ? total : -1.0;
            }
            case WALMART:
                return WALMART_CODES.containsKey(code) ? WALMART_CODES.get(code) : -1.0;
            case TARGET:
                return TARGET_CODES.containsKey(code) ? TARGET_CODES.get(code) : -1.0;
            default:
                return -1.0; // unknown: use proportional fallback
        }
    }

    /**
     * Apply tax codes to all items in a ParsedReceipt.
     * After this call, each item has taxRate and itemTax set.
     * Also recomputes computedTax as sum of per-item taxes.
     */
    public static void applyTaxCodes(ParsedReceipt receipt, StoreType storeType) {
        boolean allResolved = true;
        for (ReceiptLineItem item : receipt.items) {
            if (item.amount == null || item.amount <= 0) continue;
            double rate = resolveRate(item.taxCode, storeType);
            if (rate >= 0) {
                item.taxRate = rate;
                item.itemTax = round2(item.taxableAmount() * rate);
            } else if (storeType == StoreType.COSTCO_CHICAGO || storeType == StoreType.COSTCO_GENERIC) {
                // Unknown code on a Costco receipt → treat as exempt
                item.taxRate = 0.0;
                item.itemTax = 0.0;
            } else {
                allResolved = false;
            }
        }

        // Proportional fallback when codes couldn't be resolved
        if (!allResolved && receipt.summary.taxAmount != null && receipt.summary.taxAmount > 0) {
            double taxableSum = 0;
            for (ReceiptLineItem item : receipt.items) {
                if (item.amount != null && item.amount > 0 && item.taxRate < 0) {
                    taxableSum += item.taxableAmount();
                }
            }
            if (taxableSum > 0) {
                for (ReceiptLineItem item : receipt.items) {
                    if (item.amount != null && item.amount > 0 && item.taxRate < 0) {
                        double share = item.taxableAmount() / taxableSum;
                        item.taxRate = share;
                        item.itemTax = round2(receipt.summary.taxAmount * share);
                    }
                }
            }
        }

        // Recompute total tax from per-item taxes
        double sumTax = 0;
        for (ReceiptLineItem item : receipt.items) sumTax += item.itemTax;
        receipt.computedTax = round2(sumTax);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}