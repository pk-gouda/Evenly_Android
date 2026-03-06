package com.prathik.evenly_android.service.receipt;

import com.prathik.evenly_android.model.ocr.ReceiptGridRow;
import com.prathik.evenly_android.model.receipt.ParseIssue;
import com.prathik.evenly_android.model.receipt.ParsedReceipt;
import com.prathik.evenly_android.model.receipt.TaxCodeResolver;
import com.prathik.evenly_android.model.receipt.ReceiptLineItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ReceiptItemExtractor — universal receipt line-item parser.
 *
 * Store routing:
 *   COSTCO          → extractItemsCostco / extractItemsFromRawLines (corrupted grid)
 *   JEWEL_OSCO      → extractItemsJewelOsco
 *   WALMART         → extractItemsWalmart
 *   TARGET          → extractItemsTarget
 *   TRADER_JOES     → extractItemsTraderJoes
 *   WHOLE_FOODS     → extractItemsWholeFoods
 *   RESTAURANT      → extractItemsRestaurant
 *   GENERIC         → extractItemsGeneric
 *
 * Common problems addressed:
 *   - Wrong price matched to item   → per-store column logic, two-pass name+price linking
 *   - Tax / total miscalculated     → smart derivation, per-item tax codes, tip isolation
 *   - Noise leaking through         → hard-noise pattern library, store-specific skip rules
 */
public class ReceiptItemExtractor {

    // ── Money patterns ────────────────────────────────────────────────────────

    /** Matches prices: 10.00  1,234.56  -4.49  10,00 (EU comma) */
    private static final Pattern MONEY =
            Pattern.compile("[-]?\\d{1,3}(?:,\\d{3})*(?:[\\.,]\\d{2})");

    /** Fixes "11 00" → 11.00 (OCR space instead of decimal point) */
    private static final Pattern MONEY_WITH_SPACE =
            Pattern.compile("[-]?(\\d{1,3}(?:,\\d{3})*|\\d+)\\s(\\d{2})\\b");

    /** Costco trailing tax code: " E", " A", " AF" at end of line */
    private static final Pattern TAX_CODE_SUFFIX =
            Pattern.compile("\\s+([A-Z]{1,2})$");

    /** Walmart/Target trailing tax code: single letter or digit at end */
    private static final Pattern RETAIL_TAX_CODE =
            Pattern.compile("\\s+([A-Z0-9])\\s*$");

    /** Costco item-level discount: "SKU / SKU AMOUNT-TAXCODE" */
    private static final Pattern COSTCO_ITEM_DISCOUNT =
            Pattern.compile(".*[a-z0-9]{5,}\\s*/\\s*\\d{5,}\\s+(\\d+\\.\\d{2})-([A-Z]{1,2}).*",
                    Pattern.CASE_INSENSITIVE);

    // ── Noise patterns ────────────────────────────────────────────────────────

    private static final Pattern TIME_LIKE =
            Pattern.compile("\\b\\d{1,2}:\\d{2}(:\\d{2})?\\b");
    private static final Pattern DATE_LIKE =
            Pattern.compile("\\b\\d{1,2}[-/]\\w{3}[-/]\\d{2,4}\\b|\\b\\d{1,2}[-/]\\d{1,2}[-/]\\d{2,4}\\b",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern QTY_PREFIX =
            Pattern.compile("^\\s*(\\d+)\\s+");

    // Payment section noise
    private static final Pattern NOISE_AMOUNT =
            Pattern.compile("^[a-z][a-z0-9i]{2,6}[nt]\\s*:\\s*[\\$\\d]", Pattern.CASE_INSENSITIVE);
    private static final Pattern NOISE_VISA =
            Pattern.compile("^v[il1!][a-z0-9\\s]{0,3}s[a-z0-9!]{0,2}a?\\s*[\\$\\d]", Pattern.CASE_INSENSITIVE);
    private static final Pattern NOISE_REF =
            Pattern.compile("^([a-z]{1,5}[#i!]\\s*:?\\s*\\d{3,}|seq[i#!\\s]+\\d{3,})", Pattern.CASE_INSENSITIVE);
    private static final Pattern NOISE_CARD =
            Pattern.compile("x{4,}", Pattern.CASE_INSENSITIVE);
    private static final Pattern NOISE_TAX_LINE =
            Pattern.compile("^[aef]\\s+[\\d.]*%?\\s*(tax|tix|txx)", Pattern.CASE_INSENSITIVE);

    // Costco corrupted-grid detection
    private static final Pattern PRICE_WITH_CODE =
            Pattern.compile("\\d+\\.\\d{2}\\s+[A-Z]{1,2}(?:\\s|$)");

    // Raw-line fallback patterns (Costco corrupted grid)
    private static final Pattern RAW_SKU_NAME =
            Pattern.compile("^\\d?\\s*\\d{4,7}\\s+[A-Z]{2,}");
    private static final Pattern RAW_PRICE_ONLY =
            Pattern.compile("^(\\d+\\.\\d{2})\\s+([A-Z]{1,2})$");
    private static final Pattern RAW_DISCOUNT_ONLY =
            Pattern.compile("^(\\d+\\.\\d{2})-([A-Z]{1,2})$");
    private static final Pattern RAW_SKU_DISCOUNT =
            Pattern.compile("\\d+\\s*/\\s*\\d+\\s+(\\d+\\.\\d{2})-([A-Z]{1,2})");

    // Walmart/Target: "DPCI" or "UPC" barcode lines
    private static final Pattern BARCODE_LINE =
            Pattern.compile("^\\d{10,14}$");

    // Target DPCI (dept-class-item): "012-08-1234"
    private static final Pattern TARGET_DPCI =
            Pattern.compile("^\\d{3}-\\d{2}-\\d{4}$");

    // Walmart item code suffix: " N", " T", " O" at end
    private static final Pattern WALMART_TAX_CODE =
            Pattern.compile("\\s+([NTO12X])\\s*$");

    // Restaurant: detect tip/gratuity lines
    private static final Pattern TIP_LINE =
            Pattern.compile("(?i)(tip|gratuity|suggested|service\\s+charge)");

    // Whole Foods: weight-based item "0.75 lb @ $5.99 /lb"
    private static final Pattern WF_WEIGHT_ITEM =
            Pattern.compile("(?i)(\\d+\\.\\d+)\\s+(lb|oz|kg)\\s+@\\s+\\$?([\\d.]+)\\s*/?(lb|oz|kg)?");

    // Trader Joe's / generic: quantity × price  e.g. "3 @ 1.99"
    private static final Pattern QTY_TIMES_PRICE =
            Pattern.compile("(\\d+)\\s*[@x×]\\s*(\\d+\\.\\d{2})");

    // ── Public entry point ────────────────────────────────────────────────────

    public static ParsedReceipt parse(List<ReceiptGridRow> grid) {
        return parse(grid, null);
    }

    public static ParsedReceipt parse(List<ReceiptGridRow> grid, List<String> rawLines) {
        ParsedReceipt out = new ParsedReceipt();

        // 0) Detect store type early
        TaxCodeResolver.StoreType storeType = rawLines != null
                ? TaxCodeResolver.detectStore(rawLines)
                : TaxCodeResolver.StoreType.GENERIC;
        out.storeType = storeType;

        // 1) Pull summary numbers (subtotal, tax, total)
        parseSummary(out, grid, storeType);

        // 2) Route to store-specific item extractor
        switch (storeType) {
            case JEWEL_OSCO:
                extractItemsJewelOsco(out, grid);
                break;
            case WALMART:
                extractItemsWalmart(out, grid);
                break;
            case TARGET:
                extractItemsTarget(out, grid);
                break;
            case TRADER_JOES:
                extractItemsTraderJoes(out, grid);
                break;
            case WHOLE_FOODS:
                extractItemsWholeFoods(out, grid);
                break;
            case RESTAURANT:
                extractItemsRestaurant(out, grid);
                break;
            case COSTCO_CHICAGO:
            case COSTCO_GENERIC:
                if (rawLines != null && isGridCorrupted(grid)) {
                    extractItemsFromRawLines(out, rawLines);
                } else {
                    extractItemsCostco(out, grid);
                }
                break;
            default:
                extractItemsGeneric(out, grid);
                break;
        }

        // 3) Compute subtotal from items
        out.computedSubtotal = round2(sumItems(out.items));

        // 4) Apply per-item tax rates from tax codes
        TaxCodeResolver.applyTaxCodes(out, storeType);

        // 5) Smart compute/derive missing values
        computeTaxAndTotalSmart(out);

        // 6) Cross-verify and flag issues
        crossVerifySmart(out);

        return out;
    }

    // ── Summary parsing ───────────────────────────────────────────────────────

    private static void parseSummary(ParsedReceipt out, List<ReceiptGridRow> grid,
                                     TaxCodeResolver.StoreType storeType) {
        Double subtotal = null;
        Double tax      = null;
        Double tip      = null;
        List<Double> totalCandidates = new ArrayList<>();

        for (ReceiptGridRow row : grid) {
            String full  = normalizeRow(row);
            if (full.isEmpty()) continue;
            String lower = full.toLowerCase(Locale.US);

            if (looksLikeDateOrTime(full)) continue;

            Double amt = lastMoney(full);
            if (amt == null) continue;

            // SUBTOTAL
            if (containsAny(lower, "subtotal", "sub total", "items subtotal",
                    "item total", "merchandise total")) {
                subtotal = amt;
                continue;
            }

            // TAX — be precise; skip "tax id", "tax exempt" lines
            if (containsAny(lower, "sales tax", "grocery tax", "taxes and fees",
                    "state tax", "local tax", "city tax", "county tax",
                    "tax", "gst", "hst", "pst", "vat")) {
                if (!containsAny(lower, "tax id", "tax exempt", "tax #", "taxi", "tax rate")) {
                    tax = (tax == null) ? amt : tax + amt; // accumulate multiple tax lines
                    continue;
                }
            }

            // TIP (restaurants)
            if (storeType == TaxCodeResolver.StoreType.RESTAURANT
                    && containsAny(lower, "tip", "gratuity", "service charge")) {
                if (amt > 0 && amt < 100) { // sanity: tip shouldn't be > $100
                    tip = amt;
                }
                continue;
            }

            // TOTAL — high-priority keywords insert at front; plain "total" appends
            if (containsAny(lower, "total charged", "amount paid", "grand total",
                    "total amount", "amount due", "balance due", "balance")) {
                totalCandidates.add(0, amt); // high-priority: insert at front
                continue;
            }
            if (containsAny(lower, "total", "tota!", "tota1")) {
                if (!containsAny(lower, "original charge", "credit", "credits",
                        "temporary", "authorized", "hold", "discount", "savings",
                        "items total", "item total", "for u", "foru", "your")) {
                    totalCandidates.add(amt); // low-priority: append
                }
            }
        }

        // Pick first candidate — high-priority ones are inserted at front
        if (!totalCandidates.isEmpty()) {
            out.summary.total = totalCandidates.get(0);
        }

        out.summary.subtotal  = subtotal;
        out.summary.taxAmount = tax;
        out.summary.tipAmount = tip;

        // Costco Instant Savings scan
        scanForCostcoInstantSavings(out, grid);
    }

    private static void scanForCostcoInstantSavings(ParsedReceipt out, List<ReceiptGridRow> grid) {
        Pattern TOTAL_ITEMS_PAT = Pattern.compile(
                "total\\s+num[\\w:]{0,6}\\s+of\\s+items", Pattern.CASE_INSENSITIVE);

        Double savingsAmt = null;
        boolean foundSavingsText = false;

        for (int si = 0; si < grid.size(); si++) {
            String savFull = normalizeRow(grid.get(si));
            String savLower = savFull.toLowerCase(Locale.US);

            if (containsAny(savLower, "instant savings", "instani savings",
                    "instant saving", "instint savings", "inst savings")) {
                foundSavingsText = true;
                Double c = lastMoney(savFull);
                if (c != null && c > 0 && c < 50) savingsAmt = c;

                if (savingsAmt == null) {
                    for (int off = -3; off <= 3 && savingsAmt == null; off++) {
                        int ni = si + off;
                        if (ni < 0 || ni >= grid.size() || ni == si) continue;
                        String nFull = normalizeRow(grid.get(ni));
                        if (TOTAL_ITEMS_PAT.matcher(nFull).find()) {
                            Double candidate = lastMoney(nFull);
                            if (candidate != null && candidate > 0 && candidate < 50)
                                savingsAmt = candidate;
                        }
                    }
                }
                break;
            }
        }

        if (!foundSavingsText || savingsAmt == null) {
            for (ReceiptGridRow row : grid) {
                String full = normalizeRow(row);
                if (TOTAL_ITEMS_PAT.matcher(full).find()) {
                    Double c = lastMoney(full);
                    if (c != null && c > 0 && c < 50) { savingsAmt = c; break; }
                }
            }
        }
        if (savingsAmt != null && savingsAmt > 0) {
            out.items.add(new ReceiptLineItem("Instant Savings", -savingsAmt, false, 0.9));
        }
    }

    // ── Costco extractor ──────────────────────────────────────────────────────

    private static void extractItemsCostco(ParsedReceipt out, List<ReceiptGridRow> grid) {
        boolean inItemArea = true;
        String pendingName = null;
        ReceiptLineItem lastAddedItem = null;

        for (ReceiptGridRow row : grid) {
            String left  = safe(row.leftText);
            String right = safe(row.rightText);
            String full  = (left + " " + right).trim();
            if (full.isEmpty()) continue;

            String lower = full.toLowerCase(Locale.US);

            if (containsAny(lower, "credit card sale", "credit card", "card sale")) {
                inItemArea = false;
                continue;
            }
            if (containsAny(lower, "order totals", "purchase summary", "total number of items")
                    || lower.matches(".*total\\s+num[\\w:]{0,6}\\s+of\\s+items.*")) {
                inItemArea = false;
            }
            if (!inItemArea) continue;

            // Costco item-level discount
            Matcher discMatcher = COSTCO_ITEM_DISCOUNT.matcher(full);
            if (discMatcher.matches()) {
                try {
                    double discAmt = Double.parseDouble(discMatcher.group(1));
                    if (lastAddedItem != null && discAmt > 0)
                        lastAddedItem.discountAmount = round2(discAmt);
                } catch (NumberFormatException ignored) {}
                continue;
            }

            if (isHardNoiseRow(lower)) continue;
            if (looksLikeDateOrTime(full)) continue;

            Double amt = lastMoney(full);
            if (amt == null) {
                if (looksLikeItemName(full) && !looksLikeSummary(lower) && !isHardNoiseRow(lower))
                    pendingName = full;
                continue;
            }

            if (Math.abs(amt) < 0.05) continue;
            if (looksLikeSummary(lower)) continue;

            boolean isDiscount = containsAny(lower, "saving", "savings", "discount", "promo", "coupon");
            boolean taxable    = containsAny(lower, "tx", "tax");
            String nameCandidate = extractNameFromRow(full, amt);

            if (!looksLikeItemName(nameCandidate)) {
                if (pendingName != null) {
                    lastAddedItem = addItem(out, pendingName, amt, taxable, isDiscount);
                    pendingName = null;
                }
                continue;
            }

            lastAddedItem = addItem(out, nameCandidate, amt, taxable, isDiscount);
            pendingName = null;
        }
    }

    // ── Walmart extractor ─────────────────────────────────────────────────────
    // Walmart receipts: left=item name [tax code], right=price
    // Tax codes: N=non-taxable, T=taxable, O=tax-exempt, X=taxable
    // Also: weight items "X lb @ $Y/lb = $Z", multi-buy "2/$5.00"

    private static void extractItemsWalmart(ParsedReceipt out, List<ReceiptGridRow> grid) {
        boolean inItemArea = true;
        String pendingName = null;

        for (ReceiptGridRow row : grid) {
            String left  = safe(row.leftText).trim();
            String right = safe(row.rightText).trim();
            String full  = (left + " " + right).trim();
            if (full.isEmpty()) continue;

            String lower = full.toLowerCase(Locale.US);

            // Hard stop at payment section
            if (containsAny(lower, "payment", "credit card", "debit", "ebt",
                    "cash", "change", "tend", "total tender")) {
                inItemArea = false;
            }
            if (!inItemArea) continue;

            if (isHardNoiseRow(lower)) continue;
            if (looksLikeDateOrTime(full)) continue;
            if (looksLikeSummary(lower)) continue;

            // Skip barcode-only lines
            if (BARCODE_LINE.matcher(left.trim()).matches()) continue;

            // Skip TC# (transaction), ST# (store), OP# (operator) lines
            if (left.trim().matches("(?i)(TC|ST|OP|TR|TM|CSH|CS|EC)#.*")) continue;

            // Extract Walmart tax code from right column or end of left
            String walmartCode = "";
            Matcher wm = WALMART_TAX_CODE.matcher(right);
            if (wm.find()) {
                walmartCode = wm.group(1);
                right = right.substring(0, wm.start()).trim();
            } else {
                // Sometimes code appears at end of left text
                Matcher wml = WALMART_TAX_CODE.matcher(left);
                if (wml.find()) {
                    walmartCode = wml.group(1);
                    left = left.substring(0, wml.start()).trim();
                }
            }

            Double amt = lastMoney(right.isEmpty() ? full : right);
            if (amt == null) {
                // Could be a name-only row
                if (looksLikeItemName(left)) pendingName = left;
                continue;
            }
            if (Math.abs(amt) < 0.01) continue;

            // Multi-buy price: "2/$5.00" — keep as-is (total paid)
            // Weight item: "0.75 lb @ $1.98/lb"
            Matcher weightM = WF_WEIGHT_ITEM.matcher(full);
            if (weightM.find()) {
                // price is already in right column, name in left
            }

            boolean isDiscount = containsAny(lower, "rollback", "savings", "save", "discount",
                    "clearance", "price cut", "reduced");
            String name = pendingName != null ? pendingName : cleanNameWalmart(left.isEmpty() ? extractNameFromRow(full, amt) : left);
            pendingName = null;

            if (name.isEmpty() || !looksLikeItemName(name)) continue;

            boolean taxable = containsAny(walmartCode, "T", "X", "2");
            ReceiptLineItem item = addItemWithCode(out, name, amt, taxable, isDiscount, walmartCode);
        }
    }

    // ── Target extractor ──────────────────────────────────────────────────────
    // Target receipts: left=item desc [DPCI optional] [tax code], right=price
    // Tax codes: A=taxable, B=food/grocery, C/D=non-taxable, F=prepared food
    // Discounts appear as negative prices or "REG PRICE / SALE PRICE" lines
    // REDcard 5% discount shows as a savings line

    private static void extractItemsTarget(ParsedReceipt out, List<ReceiptGridRow> grid) {
        boolean inItemArea = true;
        String pendingName = null;
        ReceiptLineItem lastItem = null;

        for (ReceiptGridRow row : grid) {
            String left  = safe(row.leftText).trim();
            String right = safe(row.rightText).trim();
            String full  = (left + " " + right).trim();
            if (full.isEmpty()) continue;

            String lower = full.toLowerCase(Locale.US);

            // Stop at payment section
            if (containsAny(lower, "payment", "credit", "debit", "redcard",
                    "visa", "mastercard", "change due", "cash tendered")) {
                // But only stop if it also has a money amount — avoid stopping on
                // "REDCARD SAVINGS" (which is a discount item line)
                if (lastMoney(full) != null && !containsAny(lower, "savings", "save")) {
                    inItemArea = false;
                }
            }
            if (!inItemArea) continue;

            if (isHardNoiseRow(lower)) continue;
            if (looksLikeDateOrTime(full)) continue;
            if (looksLikeSummary(lower)) continue;

            // Skip DPCI lines
            if (TARGET_DPCI.matcher(left.trim()).matches()) continue;
            // Skip barcode lines
            if (BARCODE_LINE.matcher(left.trim()).matches()) continue;

            // Extract Target tax code: single letter at end of right col
            String targetCode = "";
            Matcher tcm = RETAIL_TAX_CODE.matcher(right);
            if (tcm.find()) {
                String cand = tcm.group(1);
                if (cand.matches("[A-DF]")) { // only known Target codes
                    targetCode = cand;
                    right = right.substring(0, tcm.start()).trim();
                }
            }

            Double amt = lastMoney(right.isEmpty() ? full : right);
            if (amt == null) {
                if (looksLikeItemName(left)) pendingName = left;
                continue;
            }
            if (Math.abs(amt) < 0.01) continue;

            // REDcard savings line: treat as discount
            boolean isDiscount = containsAny(lower, "redcard", "savings", "cartwheel",
                    "circle offer", "sale", "promo", "coupon", "discount");

            // "REG PRICE" line = not an item, skip
            if (containsAny(lower, "reg price", "regular price", "orig price")) continue;

            String name = pendingName != null ? pendingName
                    : cleanNameTarget(left.isEmpty() ? extractNameFromRow(full, amt) : left);
            pendingName = null;

            if (name.isEmpty() || !looksLikeItemName(name)) continue;

            boolean taxable = containsAny(targetCode, "A", "F") || (targetCode.isEmpty() && amt > 0);
            lastItem = addItemWithCode(out, name, amt, taxable, isDiscount, targetCode);
        }
    }

    // ── Trader Joe's extractor ────────────────────────────────────────────────
    // TJ receipts are relatively clean: item name on left, price on right.
    // No SKU codes. Occasional "Tax" suffix on right. "TOTAL ITEMS: N" at bottom.
    // Discounts appear as negative-price lines.

    private static void extractItemsTraderJoes(ParsedReceipt out, List<ReceiptGridRow> grid) {
        boolean inItemArea = true;
        String pendingName = null;

        for (ReceiptGridRow row : grid) {
            String left  = safe(row.leftText).trim();
            String right = safe(row.rightText).trim();
            String full  = (left + " " + right).trim();
            if (full.isEmpty()) continue;

            String lower = full.toLowerCase(Locale.US);

            // Stop at totals / payment
            if (containsAny(lower, "total items", "payment", "credit", "debit", "cash")) {
                if (lastMoney(full) != null && !containsAny(lower, "tax")) inItemArea = false;
            }
            if (!inItemArea) continue;

            if (isHardNoiseRow(lower)) continue;
            if (looksLikeDateOrTime(full)) continue;
            if (looksLikeSummary(lower)) continue;

            // TJ sometimes shows qty×price: "3 @ 1.99"
            Matcher qpm = QTY_TIMES_PRICE.matcher(full);
            boolean hasQtyPrice = qpm.find();

            Double amt = lastMoney(right.isEmpty() ? full : right);
            if (amt == null) {
                if (looksLikeItemName(left)) pendingName = left;
                continue;
            }
            if (Math.abs(amt) < 0.01) continue;

            boolean isDiscount = amt < 0 || containsAny(lower, "discount", "savings", "coupon");
            String name = pendingName != null ? pendingName : cleanName(left.isEmpty() ? extractNameFromRow(full, amt) : left);
            pendingName = null;

            if (name.isEmpty() || !looksLikeItemName(name)) continue;

            // TJ marks taxable items with "T" in right col occasionally
            boolean taxable = right.trim().endsWith("T") || right.trim().endsWith(" T");
            addItem(out, name, amt, taxable, isDiscount);
        }
    }

    // ── Whole Foods extractor ─────────────────────────────────────────────────
    // WF receipts: left=item name, right=price
    // Weight items: "X lb @ $Y/lb" — price is in right column
    // PLU codes sometimes appear before item name
    // Discounts: "365 SAVINGS", "PRIME DISCOUNT", negative prices

    private static void extractItemsWholeFoods(ParsedReceipt out, List<ReceiptGridRow> grid) {
        boolean inItemArea = true;
        String pendingName = null;

        for (ReceiptGridRow row : grid) {
            String left  = safe(row.leftText).trim();
            String right = safe(row.rightText).trim();
            String full  = (left + " " + right).trim();
            if (full.isEmpty()) continue;

            String lower = full.toLowerCase(Locale.US);

            if (containsAny(lower, "payment", "credit", "debit", "cash",
                    "total items", "ebt")) {
                if (lastMoney(full) != null) inItemArea = false;
            }
            if (!inItemArea) continue;

            if (isHardNoiseRow(lower)) continue;
            if (looksLikeDateOrTime(full)) continue;
            if (looksLikeSummary(lower)) continue;

            // Strip leading PLU code (4-5 digit number at start of item name)
            String cleanLeft = left.replaceAll("^\\d{4,5}\\s+", "");

            // Weight item: parse "0.75 lb @ $5.99 /lb" — price is product
            Matcher wm = WF_WEIGHT_ITEM.matcher(full);
            if (wm.find()) {
                // Price is already captured in right column; just clean name
            }

            Double amt = lastMoney(right.isEmpty() ? full : right);
            if (amt == null) {
                if (looksLikeItemName(cleanLeft)) pendingName = cleanLeft;
                continue;
            }
            if (Math.abs(amt) < 0.01) continue;

            boolean isDiscount = amt < 0 || containsAny(lower,
                    "365 savings", "prime discount", "prime member", "savings",
                    "discount", "coupon", "sale");

            String name = pendingName != null ? pendingName
                    : cleanName(cleanLeft.isEmpty() ? extractNameFromRow(full, amt) : cleanLeft);
            pendingName = null;

            if (name.isEmpty() || !looksLikeItemName(name)) continue;

            addItem(out, name, amt, false, isDiscount);
        }
    }

    // ── Restaurant extractor ──────────────────────────────────────────────────
    // Restaurant receipts: left=item [modifier?], right=price
    // Key differences vs retail:
    //   - Tip appears AFTER tax; isolate it (don't sum with items)
    //   - "modifier" lines (e.g. "NO ONION", "ADD CHEESE $1.00") may follow an item
    //   - Combo modifiers with price should be added as sub-items
    //   - Quantity prefix "2x Burger" is common
    //   - Header/footer noise: table#, server, check#, re-order info

    private static void extractItemsRestaurant(ParsedReceipt out, List<ReceiptGridRow> grid) {
        boolean inItemArea = true;
        boolean pastSubtotal = false;
        String pendingName = null;

        for (ReceiptGridRow row : grid) {
            String left  = safe(row.leftText).trim();
            String right = safe(row.rightText).trim();
            String full  = (left + " " + right).trim();
            if (full.isEmpty()) continue;

            String lower = full.toLowerCase(Locale.US);

            // Track when we've passed subtotal (tip/totals come after)
            if (containsAny(lower, "subtotal", "sub total")) pastSubtotal = true;

            // Stop extracting items once we hit tip/gratuity/total lines
            if (pastSubtotal && TIP_LINE.matcher(lower).find()) {
                inItemArea = false;
            }
            if (pastSubtotal && containsAny(lower, "total", "amount due", "balance")) {
                inItemArea = false;
            }
            if (!inItemArea) continue;

            // Skip header noise: table, server, check, guests
            if (containsAny(lower, "table", "server", "check #", "check:", "guest",
                    "order #", "cashier", "bartender", "your server", "seat",
                    "dine in", "take out", "to go", "order type")) continue;

            if (isHardNoiseRow(lower)) continue;
            if (looksLikeDateOrTime(full)) continue;
            if (looksLikeSummary(lower)) continue;

            Double amt = lastMoney(right.isEmpty() ? full : right);

            if (amt == null) {
                // Name-only line (modifier without price, or pending item name)
                // Only queue as pending if it looks like a real item name
                if (looksLikeItemName(left) && !containsAny(lower, "no ", "add ", "sub ", "extra ", "lite ")) {
                    pendingName = left;
                }
                continue;
            }
            if (Math.abs(amt) < 0.01) continue;

            // Modifier with price (e.g. "ADD CHEESE  1.00") — attach to last item or standalone
            boolean isModifier = containsAny(lower, "add ", "extra ", "sub ", "no ", "sauce",
                    "dressing", "side", "modifier");

            boolean isDiscount = amt < 0 || containsAny(lower, "discount", "coupon", "comp",
                    "void", "promo", "happy hour", "military");

            // Extract quantity from prefix: "2 Burger", "2x Burger"
            String nameRaw;
            if (pendingName != null) {
                nameRaw = pendingName;
                pendingName = null;
            } else {
                nameRaw = left.isEmpty() ? extractNameFromRow(full, amt) : left;
            }

            // Strip "2x" or "2 " quantity prefix for clean name, but keep multiplied price
            Matcher qxm = Pattern.compile("^(\\d+)[xX]?\\s+").matcher(nameRaw);
            if (qxm.find()) {
                try {
                    int qty = Integer.parseInt(qxm.group(1));
                    nameRaw = nameRaw.substring(qxm.end());
                    // price already reflects total; quantity just for display
                } catch (NumberFormatException ignored) {}
            }

            String name = cleanName(nameRaw);
            if (name.isEmpty() || !looksLikeItemName(name)) continue;

            addItem(out, name, amt, false, isDiscount);
        }
    }

    // ── Generic extractor ─────────────────────────────────────────────────────

    private static void extractItemsGeneric(ParsedReceipt out, List<ReceiptGridRow> grid) {
        boolean inItemArea = true;
        String pendingName = null;
        ReceiptLineItem lastAddedItem = null;

        for (ReceiptGridRow row : grid) {
            String left  = safe(row.leftText);
            String right = safe(row.rightText);
            String full  = (left + " " + right).trim();
            if (full.isEmpty()) continue;

            String lower = full.toLowerCase(Locale.US);

            if (containsAny(lower, "credit card sale", "credit card", "card sale")) {
                inItemArea = false;
                continue;
            }
            if (containsAny(lower, "order totals", "purchase summary", "total number of items")
                    || lower.matches(".*total\\s+num[\\w:]{0,6}\\s+of\\s+items.*")) {
                inItemArea = false;
            }
            if (!inItemArea) continue;

            if (isHardNoiseRow(lower)) continue;
            if (looksLikeDateOrTime(full)) continue;

            Double amt = lastMoney(full);
            if (amt == null) {
                if (looksLikeItemName(full) && !looksLikeSummary(lower) && !isHardNoiseRow(lower))
                    pendingName = full;
                continue;
            }

            if (Math.abs(amt) < 0.05) continue;
            if (looksLikeSummary(lower)) continue;

            boolean isDiscount = containsAny(lower, "saving", "savings", "discount", "promo", "coupon");
            boolean taxable    = containsAny(lower, "tx", "tax");
            String nameCandidate = extractNameFromRow(full, amt);

            if (!looksLikeItemName(nameCandidate)) {
                if (pendingName != null) {
                    lastAddedItem = addItem(out, pendingName, amt, taxable, isDiscount);
                    pendingName = null;
                }
                continue;
            }

            lastAddedItem = addItem(out, nameCandidate, amt, taxable, isDiscount);
            pendingName = null;
        }
    }

    // ── Jewel-Osco extractor ──────────────────────────────────────────────────

    private static void extractItemsJewelOsco(ParsedReceipt out, List<ReceiptGridRow> grid) {
        Pattern YOU_PAY = Pattern.compile("(?i)(?:you\\s+pay\\s+|price\\s+)?(\\d+\\.\\d{2})\\s*[Bb]?\\s*$");
        Pattern JEWEL_COUPON = Pattern.compile(
                "(?i)^(f[io0]{0,2}r\\s*[u!]?\\s+store\\s+coup|sale\\s+sav|store\\s+sav|for\\s+[u!]\\s+sav|your\\s+sav|f[io0]{0,2}r[u!]\\s+store)");
        Pattern WEIGHT_LINE = Pattern.compile("(?i)\\d+\\.\\d+\\s+(?:lb|oz)\\s+@");
        Pattern JEWEL_NOISE = Pattern.compile(
                "(?i)^(produce|your\\s+cashier|credit\\s+purchase|card\\s+#|ref:|payment\\s+amount"
                        + "|al\\s+debit|aid\\s+a|tvr\\s+|mastercard|change|points|total\\s+savings"
                        + "|your\\s+points|price|you\\s+pay|thank\\s+you|for\\s+jewel|\\*{3,}"
                        + "|total\\s+umber|total\\s+number|\\d{2}/\\d{2}/\\d{2})");

        String pendingName = null;

        for (ReceiptGridRow row : grid) {
            String left      = safe(row.leftText).trim();
            String right     = safe(row.rightText).trim();
            String leftLower = left.toLowerCase(Locale.US);

            if (left.isEmpty() && right.isEmpty()) continue;

            // Hard stop — once we see the balance/tax line, no more items below
            if (containsAny(leftLower, "balance", "subtotal", "tax")) {
                break;
            }

            if (JEWEL_COUPON.matcher(left).find()) {
                // Even if this row contains coupon text, it may also contain an item
                // e.g. "4062 forU Store Coupon -3.99 20 CUCUMBERS 1.98" RIGHT="1.00 B"
                // Try to salvage: strip the coupon segment and check if a valid item remains
                String stripped = left
                        .replaceAll("(?i)f[io0]{0,2}r\\s*[u!]?\\s+store\\s+coup[^\\d]*-?\\d+\\.\\d+\\s*", " ")
                        .replaceAll("(?i)sale\\s+sav[^\\d]*-?\\d+\\.\\d+\\s*", " ")
                        .replaceAll("\\s{2,}", " ").trim();
                String salvaged = extractJewelName(stripped);
                if (salvaged != null && !salvaged.isEmpty()) {
                    pendingName = salvaged;
                }
                continue;
            }
            if (JEWEL_NOISE.matcher(left).find()) continue;
            if (containsAny(leftLower, "total", "savings", "payment", "miscellaneous")) continue;

            Matcher youPayM = YOU_PAY.matcher(right);
            if (!youPayM.matches()) {
                if (!WEIGHT_LINE.matcher(left).find() && !left.isEmpty()) {
                    String extracted = extractJewelName(left);
                    if (extracted != null) pendingName = extracted;
                }
                continue;
            }

            double youPay = Double.parseDouble(youPayM.group(1));
            if (youPay <= 0) continue;

            String name = extractJewelName(left);
            if (name == null && pendingName != null) name = pendingName;
            pendingName = null;

            if (name == null || name.isEmpty()) continue;
            if (Math.abs(youPay) < 0.01) continue;

            // All Jewel items are non-taxable at item level (tax shown as single line)
            // Bag fees are also non-taxable
            out.items.add(new ReceiptLineItem(name, youPay, false, 0.9));
        }
    }

    private static String extractJewelName(String leftText) {
        String t = leftText.trim();
        if (t.isEmpty()) return null;

        t = t.replaceAll("^\\d{4,13}\\s+", "");
        t = t.replaceAll("^\\d{4,13}\\s+", "");
        t = t.replaceAll("(?i)for[u!]?\\s+store\\s+coup.*", "").trim();
        t = t.replaceAll("(?i)sale\\s+savings.*", "").trim();
        t = t.replaceAll("(?i)store\\s+savings.*", "").trim();
        t = t.replaceAll("\\s+\\d+\\.\\d+\\s+(?:lb|oz)\\s+@.*", "").trim();
        t = t.replaceAll("\\s+-?\\d+\\.\\d{2}\\s*$", "").trim();
        t = t.replaceAll("\\s+[Bb]$", "").trim();
        t = t.replaceAll("\\s+", " ").trim();

        if (t.length() < 2) return null;
        if (t.matches("\\d+\\.\\d+")) return null;
        String lower = t.toLowerCase(Locale.US);
        if (lower.startsWith("miscell") || lower.startsWith("produce")
                || lower.startsWith("your cashier") || lower.startsWith("osco")) return null;

        return t.isEmpty() ? null : t;
    }

    // ── Item addition helpers ─────────────────────────────────────────────────

    private static ReceiptLineItem addItem(ParsedReceipt out, String nameRaw, double amt,
                                           boolean taxable, boolean isDiscount) {
        return addItemWithCode(out, nameRaw, amt, taxable, isDiscount, "");
    }

    private static ReceiptLineItem addItemWithCode(ParsedReceipt out, String nameRaw, double amt,
                                                   boolean taxable, boolean isDiscount,
                                                   String explicitCode) {
        // Extract trailing tax code if not already provided
        String taxCode = explicitCode;
        if (taxCode.isEmpty()) {
            Matcher tcm = TAX_CODE_SUFFIX.matcher(nameRaw.trim());
            if (tcm.find()) taxCode = tcm.group(1);
        }

        String name = cleanName(nameRaw);

        if (isDiscount && amt > 0) amt = -amt;

        double conf = 0.7;
        if (name.length() >= 8)  conf += 0.1;
        if (name.length() >= 14) conf += 0.1;
        if (containsLetter(name)) conf += 0.1;
        if (conf > 1.0) conf = 1.0;

        ReceiptLineItem item = new ReceiptLineItem(name, round2(amt), taxable, conf, taxCode, 0.0);
        out.items.add(item);
        return item;
    }

    // ── Name extraction / cleaning ────────────────────────────────────────────

    private static String extractNameFromRow(String full, double amt) {
        String t = full;
        t = t.replaceAll("[-]?\\d{1,3}(?:,\\d{3})*(?:[\\.,]\\d{2})", " ");
        t = t.replace("|", " ").replaceAll("\\s+", " ").trim();
        t = QTY_PREFIX.matcher(t).replaceFirst("");
        return t.trim();
    }

    private static String cleanName(String s) {
        String t = safe(s);
        t = t.replace("|", " ").replaceAll("\\s+", " ").trim();
        // Strip leading SKU (Costco format)
        t = t.replaceAll("^\\d[A-Za-z0-9:]{4,8}\\s+|^[A-Za-z]\\d{4,8}\\s+", "");
        // Strip trailing Costco/Target/Walmart tax code: " E", " A", " AF", " N", " T"
        t = t.replaceAll("\\s+[A-Z]{1,2}$", "");
        // Strip trailing leaked price
        t = t.replaceAll("\\s+\\d+\\.\\d{2}$", "").trim();
        // Remove "Tx1" patterns
        t = t.replaceAll("(?i)\\btx\\d+\\b", "").trim();
        // Remove leading bullets
        t = t.replaceAll("^[•\\-*]+\\s*", "");
        // Strip barcode-looking prefixes
        t = t.replaceAll("^\\d{10,}\\s+", "");
        return t.isEmpty() ? "Item" : t;
    }

    private static String cleanNameWalmart(String s) {
        String t = cleanName(s);
        // Walmart sometimes has "WM " prefix
        t = t.replaceAll("(?i)^wm\\s+", "");
        // Strip trailing Walmart tax code letter
        t = t.replaceAll("\\s+[NTO12X]$", "").trim();
        return t.isEmpty() ? "Item" : t;
    }

    private static String cleanNameTarget(String s) {
        String t = cleanName(s);
        // Strip "EACH" suffix common in Target
        t = t.replaceAll("(?i)\\s+each$", "").trim();
        // Strip Target DPCI embedded in name
        t = t.replaceAll("\\d{3}-\\d{2}-\\d{4}", "").trim();
        return t.isEmpty() ? "Item" : t;
    }

    // ── Summary math ──────────────────────────────────────────────────────────

    private static void computeTaxAndTotalSmart(ParsedReceipt out) {
        if (out.summary.taxAmount != null) out.computedTax = round2(out.summary.taxAmount);

        if (out.summary.total != null) {
            if (out.computedSubtotal > 0 && out.computedTax == 0.0) {
                double derivedTax = out.summary.total - out.computedSubtotal;
                // Also subtract tip if restaurant
                if (out.summary.tipAmount != null) derivedTax -= out.summary.tipAmount;
                if (derivedTax >= 0 && derivedTax <= Math.max(5.0, out.computedSubtotal * 0.30)) {
                    out.computedTax = round2(derivedTax);
                }
            }
        }

        if (out.summary.subtotal == null && out.summary.total != null
                && out.computedTax > 0.0 && out.computedSubtotal == 0.0) {
            double tip = out.summary.tipAmount != null ? out.summary.tipAmount : 0.0;
            double derivedSub = out.summary.total - out.computedTax - tip;
            if (derivedSub > 0) out.computedSubtotal = round2(derivedSub);
        }

        if (out.summary.total != null) {
            double tip = out.summary.tipAmount != null ? out.summary.tipAmount : 0.0;
            out.computedTotal = round2(out.computedSubtotal + out.computedTax + tip);
            out.summary.total = repairIfTooLarge(out.summary.total, out.computedTotal);
        } else {
            double tip = out.summary.tipAmount != null ? out.summary.tipAmount : 0.0;
            out.computedTotal = round2(out.computedSubtotal + out.computedTax + tip);
        }

        if (out.computedSubtotal > 0.0 && out.computedTax > 0.0) {
            out.summary.taxPercent = round2((out.computedTax / out.computedSubtotal) * 100.0);
        }
    }

    private static void crossVerifySmart(ParsedReceipt out) {
        if (out.summary.subtotal != null) {
            if (!closeEnough(out.computedSubtotal, out.summary.subtotal))
                out.issues.add(ParseIssue.SUBTOTAL_MISMATCH);
        } else {
            if (out.computedSubtotal == 0.0) out.issues.add(ParseIssue.SUBTOTAL_MISSING);
        }

        if (out.summary.total != null) {
            if (!closeEnough(out.computedTotal, out.summary.total))
                out.issues.add(ParseIssue.TOTAL_MISMATCH);
        } else {
            if (out.computedTotal == 0.0) out.issues.add(ParseIssue.TOTAL_MISSING);
        }

        if (out.summary.taxAmount == null && out.computedTax == 0.0) {
            out.issues.add(ParseIssue.TAX_MISSING);
        }
    }

    // ── Noise detection ───────────────────────────────────────────────────────

    private static boolean looksLikeSummary(String lower) {
        return containsAny(lower,
                "subtotal", "sub total", "items subtotal", "item total",
                "sales tax", "grocery tax", "taxes and fees", "state tax",
                "local tax", "city tax", " tax", "% tax", "% tix", "% txx",
                "gst", "hst", "pst", "vat",
                "total", "amount due", "balance due", "order totals",
                "purchase summary", "tip", "gratuity",
                "service charge", "delivery fee", "bag fee"
        );
    }

    private static boolean isHardNoiseRow(String lower) {
        if (NOISE_CARD.matcher(lower).find()) return true;
        if (NOISE_AMOUNT.matcher(lower).find()) return true;
        if (lower.matches(".*\\bamount\\s*:.*")) return true;
        if (NOISE_VISA.matcher(lower).find()) return true;
        if (NOISE_REF.matcher(lower).find()) return true;
        if (NOISE_TAX_LINE.matcher(lower).find()) return true;
        if (lower.matches(".*[a-z0-9]{6,}\\s*/\\s*\\d{6,}.*")) return true;
        if (lower.matches(".*\\b(ave|blvd|ave\\.|st\\.|rd\\.|dr\\.|ln\\.|loop)\\b.*")) return true;

        if (containsAny(lower,
                "number of items", "items sold", "item count",
                "thank you", "please come", "come again",
                "subtotal", "sub total", "total tax", "grand total",
                "approved", "resp:", "resp :", "approval",
                "amount:", "amount :", "change due", "change 0.00", "change 0", "change",
                "aid:", " aid ", "a000",
                "weklesale", "ewikesale", "wholesale",
                "member", "membership",
                "wifi", "wi-fi", "survey", "receipt #",
                "store #", "store#", "register", "cashier",
                "phone:", "www.", "http", ".com",
                "transaction id", "terminal id"
        )) return true;

        return false;
    }

    private static boolean looksLikeDateOrTime(String s) {
        String t = safe(s);
        return TIME_LIKE.matcher(t).find() || DATE_LIKE.matcher(t).find();
    }

    private static boolean looksLikeItemName(String s) {
        String t = safe(s).trim();
        if (t.length() < 3) return false;
        if (!containsLetter(t)) return false;
        if (t.startsWith("(") && t.endsWith(")")) return false;
        if (t.matches("^\\d+\\s*(pcs|pc|oz|lb|kg|g)\\.?$")) return false;

        String lower = t.toLowerCase(Locale.US);
        if (containsAny(lower, "order", "transaction", "subtotal", "total", "tax",
                "payment", "purchase summary", "order totals")) return false;
        // Reject Costco tax-code-only names
        if (t.matches("(?i)^[A-Z]\\s+TX.*") || t.matches("(?i)^TX$")) return false;

        return true;
    }

    // ── Corrupted grid detection (Costco) ─────────────────────────────────────

    private static boolean isGridCorrupted(List<ReceiptGridRow> grid) {
        for (ReceiptGridRow row : grid) {
            String combined = safe(row.leftText) + " " + safe(row.rightText);
            Matcher m = PRICE_WITH_CODE.matcher(combined);
            int count = 0;
            while (m.find()) count++;
            if (count >= 2) return true;
        }
        return false;
    }

    private static void extractItemsFromRawLines(ParsedReceipt out, List<String> rawLines) {
        List<String> nameLines    = new ArrayList<>();
        List<double[]> priceEntries = new ArrayList<>();
        List<String> priceCodes   = new ArrayList<>();
        java.util.Map<Integer, Double> discountByNameIdx = new java.util.HashMap<>();
        Double pendingDiscount = null;

        for (String raw : rawLines) {
            String line  = raw.trim();
            if (line.isEmpty()) continue;
            String lower = line.toLowerCase(Locale.US);

            if (isHardNoiseRow(lower)) continue;
            if (looksLikeSummary(lower)) continue;
            if (containsAny(lower, "subtotal", "sub total", "total tax",
                    "total number", "instant savings", "thank", "please",
                    "come again", "items sold", "sr 0")) continue;

            Matcher sdm = RAW_SKU_DISCOUNT.matcher(line);
            if (sdm.find()) { pendingDiscount = Double.parseDouble(sdm.group(1)); continue; }

            Matcher dom = RAW_DISCOUNT_ONLY.matcher(line);
            if (dom.matches()) { pendingDiscount = Double.parseDouble(dom.group(1)); continue; }

            Matcher pom = RAW_PRICE_ONLY.matcher(line);
            if (pom.matches()) {
                priceEntries.add(new double[]{Double.parseDouble(pom.group(1))});
                priceCodes.add(pom.group(2));
                continue;
            }

            if (RAW_SKU_NAME.matcher(line).find() && !MONEY.matcher(line).find()) {
                nameLines.add(line);
                if (pendingDiscount != null) {
                    discountByNameIdx.put(nameLines.size() - 1, pendingDiscount);
                    pendingDiscount = null;
                }
            }
        }

        int count = Math.min(nameLines.size(), priceEntries.size());
        for (int i = 0; i < count; i++) {
            String nameRaw = nameLines.get(i) + " " + priceEntries.get(i)[0] + " " + priceCodes.get(i);
            ReceiptLineItem item = addItem(out, nameRaw, priceEntries.get(i)[0], false, false);
            if (item != null) {
                item.taxCode = priceCodes.get(i);
                Double disc = discountByNameIdx.get(i);
                if (disc != null) item.discountAmount = round2(disc);
            }
        }
    }

    // ── Math helpers ──────────────────────────────────────────────────────────

    private static double sumItems(List<ReceiptLineItem> items) {
        double sum = 0.0;
        for (ReceiptLineItem it : items) if (it.amount != null) sum += it.amount;
        return sum;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static boolean closeEnough(double a, double b) {
        double tol = Math.max(0.10, Math.max(Math.abs(a), Math.abs(b)) * 0.01);
        return Math.abs(a - b) <= tol;
    }

    private static Double lastMoney(String s) {
        if (s == null) return null;
        String cleaned = normalizeForMoney(s);

        Matcher ms = MONEY_WITH_SPACE.matcher(cleaned);
        if (ms.find()) {
            try {
                return Double.parseDouble((ms.group(1) + "." + ms.group(2)).replace(",", ""));
            } catch (Exception ignored) {}
        }

        Matcher m = MONEY.matcher(cleaned);
        Double last = null;
        while (m.find()) {
            String token = m.group().replace(",", "").replace(',', '.');
            try { last = Double.parseDouble(token.replace(',', '.')); } catch (Exception ignored) {}
        }
        return last;
    }

    private static String normalizeForMoney(String s) {
        String t = safe(s);
        t = t.replace("$", "").replace("|", " ");
        t = t.replaceAll("(?<=\\d)[lLIi](?=[\\d.,])", "1");
        t = t.replaceAll("(?<=[.,])[lLIi](?=\\d)", "1");
        t = t.replaceAll("(?<=\\d)[Oo](?=[\\d.,])", "0");
        t = t.replaceAll("(?<=[.,])[Oo](?=\\d)", "0");
        t = t.replaceAll("(\\d),(\\d{2})\\b", "$1.$2");
        return t;
    }

    private static Double repairIfTooLarge(Double candidate, double expected) {
        if (candidate == null) return null;
        if (expected <= 0 || candidate <= expected * 2.0) return candidate;
        String str = String.format(Locale.US, "%.2f", candidate);
        int dot = str.indexOf('.');
        if (dot <= 0) return candidate;
        String cents = str.substring(dot);
        String intPart = str.substring(0, dot);
        for (int i = 1; i < intPart.length(); i++) {
            try {
                double v = Double.parseDouble(intPart.substring(i) + cents);
                if (Math.abs(v - expected) <= 0.50) return v;
            } catch (Exception ignored) {}
        }
        return candidate;
    }

    private static String normalizeRow(ReceiptGridRow row) {
        return (safe(row.leftText) + " " + safe(row.rightText))
                .replace("|", " ").replaceAll("\\s+", " ").trim();
    }

    private static boolean containsLetter(String s) {
        return s != null && s.matches(".*[A-Za-z].*");
    }

    private static boolean containsAny(String s, String... keys) {
        for (String k : keys) if (s.contains(k)) return true;
        return false;
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }
}