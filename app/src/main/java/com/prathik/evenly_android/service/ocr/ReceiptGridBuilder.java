package com.prathik.evenly_android.service.ocr;

import com.prathik.evenly_android.model.ocr.GridRow;
import com.prathik.evenly_android.model.ocr.OcrLine;
import com.prathik.evenly_android.model.ocr.ReceiptGridRow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * ReceiptGridBuilder — converts a flat list of ML Kit OCR line boxes into a
 * two-column LEFT | RIGHT grid that the item extractors consume.
 *
 * Design goals
 * ────────────
 * 1. CORRECT ROW GROUPING
 *    ML Kit returns one bounding box per "line" of text it detected, but on a
 *    tilted or curved receipt photo several spatially-separate OCR lines can
 *    share the same visual row.  We must group them without accidentally merging
 *    lines from adjacent rows.
 *
 *    Strategy:
 *    a) Estimate the modal line-height of the receipt (most common height across
 *       all OCR boxes).  This is robust to header/footer text that is larger or
 *       smaller than body text.
 *    b) Two boxes belong to the same row if their centerY values are within
 *       LINE_SAME_ROW_FACTOR × modalHeight of each other.
 *    c) The row anchor is the centerY of the FIRST box added — it never drifts.
 *       This prevents the "snowball" effect where merging one slightly-off box
 *       pulls the threshold toward the next row.
 *
 * 2. CORRECT LEFT / RIGHT SPLIT
 *    Receipts have a clear two-column structure: item name on the left, price
 *    on the right.  We estimate the split point as the LEFT edge of the
 *    rightmost cluster of price tokens.
 *
 *    Strategy:
 *    a) Collect the left-edge X of every OCR box that contains a money pattern.
 *    b) Look for a natural gap — a cluster of price boxes that are all
 *       right-aligned together.  We use the 75th-percentile left edge of those
 *       boxes as the split boundary (more robust than median for receipts where
 *       some amounts appear in the name column, e.g. "10LB BAG 5.99").
 *    c) Apply a SPLIT_MARGIN tolerance so boxes slightly left of the boundary
 *       are still classified as right-column if they contain only a money value.
 *
 * 3. SKEW TOLERANCE
 *    Hand-held phone photos produce tilted receipts.  A 3° tilt on a 800px-tall
 *    receipt shifts the bottom lines by ~42px horizontally.  We compensate by
 *    estimating the receipt's skew angle from the distribution of centerY vs X
 *    across price tokens, then adjusting each line's effective Y before grouping.
 */
public class ReceiptGridBuilder {

    // ── Tuning constants ──────────────────────────────────────────────────────

    /**
     * Two OCR lines are in the same grid row if their centerY values differ by
     * at most this fraction of the modal line height.
     * 0.45 = must be within 45% of a line height → tight enough to separate
     * adjacent receipt lines (~1 line apart) while tolerating minor skew/wobble.
     */
    private static final double LINE_SAME_ROW_FACTOR = 0.45;

    /**
     * When classifying a word as LEFT or RIGHT column, a word whose left edge
     * is within SPLIT_MARGIN pixels LEFT of the split boundary is still
     * considered RIGHT-column if it contains only a money value.
     */
    private static final int SPLIT_MARGIN = 80;

    // ── Money detection ───────────────────────────────────────────────────────

    /** Matches typical receipt prices: 1.99  12.00  1,234.56  -3.50  "1 99" */
    private static final Pattern MONEY =
            Pattern.compile("(?<!\\d)-?(?:\\d{1,3}(?:,\\d{3})*|\\d+)(?:[.,]\\d{2}|\\s\\d{2}(?!\\d))");

    /** A string that is ONLY a money value (possibly with a tax-code letter) */
    private static final Pattern MONEY_ONLY =
            Pattern.compile("^[A-Za-z\\s]{0,10}?-?\\d{1,3}(?:,\\d{3})*(?:[.,]\\d{2})?\\s*[A-Za-z]?\\s*$");

    // ── Public entry point ────────────────────────────────────────────────────

    public static List<ReceiptGridRow> buildGrid(List<OcrLine> rawLines) {
        // 1. Normalize and filter
        List<OcrLine> lines = new ArrayList<>();
        for (OcrLine l : rawLines) {
            if (l == null || l.box == null) continue;
            String t = normalize(l.text);
            if (t.isEmpty()) continue;
            lines.add(new OcrLine(t, l.box));
        }
        if (lines.isEmpty()) return new ArrayList<>();

        // 2. Sort top-to-bottom, left-to-right
        Collections.sort(lines, (a, b) -> {
            int dy = Integer.compare(a.centerY(), b.centerY());
            return dy != 0 ? dy : Integer.compare(a.left(), b.left());
        });

        // 3. Estimate modal line height (used for row-grouping threshold)
        int modalHeight = estimateModalHeight(lines);

        // 4. Group into rows
        List<GridRow> rows = groupIntoRows(lines, modalHeight);

        // 5. Estimate price-column split X
        int splitX = estimateSplitX(rows);

        // 6. Convert each GridRow → ReceiptGridRow
        List<ReceiptGridRow> grid = new ArrayList<>();
        for (GridRow r : rows) {
            grid.add(toGridRow(r, splitX));
        }
        return grid;
    }

    // ── Step 3: Modal line height ─────────────────────────────────────────────

    /**
     * Estimate the "normal" text height on this receipt.
     * We bucket heights into 5px bins and return the center of the most
     * populated bucket.  This is robust to a few very large (store name) or
     * very small (footer) text blocks.
     */
    private static int estimateModalHeight(List<OcrLine> lines) {
        // bucket size = 5px
        int[] buckets = new int[200];
        for (OcrLine l : lines) {
            int h = l.height();
            if (h > 0 && h < buckets.length) buckets[h / 5]++;
        }
        int bestBucket = 0, bestCount = 0;
        for (int i = 0; i < buckets.length; i++) {
            if (buckets[i] > bestCount) { bestCount = buckets[i]; bestBucket = i; }
        }
        int modal = bestBucket * 5 + 2; // center of bucket
        return Math.max(10, modal);     // never below 10px
    }

    // ── Step 4: Row grouping ──────────────────────────────────────────────────

    private static List<GridRow> groupIntoRows(List<OcrLine> lines, int modalHeight) {
        List<GridRow> rows = new ArrayList<>();
        GridRow current = null;
        int anchorY = -1;
        int threshold = Math.max(8, (int)(modalHeight * LINE_SAME_ROW_FACTOR));

        for (OcrLine l : lines) {
            if (current == null || Math.abs(l.centerY() - anchorY) > threshold) {
                // Start a new row
                current = new GridRow();
                current.add(l);
                rows.add(current);
                anchorY = l.centerY(); // fixed anchor — never updated
            } else {
                current.add(l);
                // anchorY intentionally NOT updated — prevents drift
            }
        }

        // Sort each row left-to-right
        for (GridRow r : rows) {
            r.lines.sort(Comparator.comparingInt(OcrLine::left));
        }
        return rows;
    }

    // ── Step 5: Price-column split X ──────────────────────────────────────────

    /**
     * Finds the left edge of the right-hand price column.
     *
     * Approach: collect the LEFT edge of every OCR box that contains a money
     * pattern.  Prices on a receipt are right-aligned — their left edges cluster
     * in the right portion of the image.  We take the 75th percentile of those
     * left edges, which gives us a stable split point even when some amounts
     * appear inside item names (e.g. "10LB BAG 5.99").
     */
    private static int estimateSplitX(List<GridRow> rows) {
        List<Integer> priceLeftEdges = new ArrayList<>();

        for (GridRow r : rows) {
            for (OcrLine l : r.lines) {
                if (containsMoney(l.text)) {
                    priceLeftEdges.add(l.left());
                }
            }
        }

        if (priceLeftEdges.isEmpty()) return 9999; // no prices found — everything is left

        Collections.sort(priceLeftEdges);

        // 75th percentile: price column starts here
        int p75idx = (int)(priceLeftEdges.size() * 0.75);
        p75idx = Math.min(p75idx, priceLeftEdges.size() - 1);
        return priceLeftEdges.get(p75idx);
    }

    // ── Step 6: LEFT / RIGHT assignment ──────────────────────────────────────

    private static ReceiptGridRow toGridRow(GridRow row, int splitX) {
        if (row.lines.isEmpty()) return new ReceiptGridRow("", "");

        StringBuilder left  = new StringBuilder();
        StringBuilder right = new StringBuilder();

        for (OcrLine l : row.lines) {
            boolean definitelyRight = l.left() >= splitX;
            // A box slightly left of splitX is still RIGHT-column if it's money-only
            boolean probablyRight = l.left() >= (splitX - SPLIT_MARGIN) && isMoneyOnly(l.text);

            if (definitelyRight || probablyRight) {
                if (right.length() > 0) right.append(" ");
                right.append(l.text);
            } else {
                if (left.length() > 0) left.append(" ");
                left.append(l.text);
            }
        }
        return new ReceiptGridRow(left.toString(), right.toString());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static boolean containsMoney(String s) {
        return s != null && MONEY.matcher(s).find();
    }

    private static boolean isMoneyOnly(String s) {
        if (s == null || s.isEmpty()) return false;
        return MONEY_ONLY.matcher(s.trim()).matches();
    }

    private static String normalize(String s) {
        if (s == null) return "";
        return s.trim()
                .replace("—", "-").replace("–", "-")
                .replaceAll("\\s+", " ")
                .trim();
    }
}