package com.prathik.evenly_android.service.ocr;

import com.prathik.evenly_android.model.ocr.GridRow;
import com.prathik.evenly_android.model.ocr.OcrLine;
import com.prathik.evenly_android.model.ocr.ReceiptGridRow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

public class ReceiptGridBuilder {

    // price patterns: 12.34, 1,234.56, -3.50
    private static final Pattern MONEY =
            Pattern.compile("[-]?\\d{1,3}(?:,\\d{3})*(?:\\.\\d{2})");

    // words to ignore as "items" (later we’ll use these for sectioning)
    private static final Pattern SUMMARY_WORDS =
            Pattern.compile("(?i)\\b(total|subtotal|tax|tip|cash|change|visa|mastercard|amex|amount|due)\\b");

    /**
     * Main entry: lines -> rows (left column + right column)
     */
    public static List<ReceiptGridRow> buildGrid(List<OcrLine> rawLines) {
        List<OcrLine> lines = new ArrayList<>();
        for (OcrLine l : rawLines) {
            if (l == null) continue;
            String t = normalize(l.text);
            if (t.isEmpty()) continue;
            if (l.box == null) continue;
            lines.add(new OcrLine(t, l.box));
        }

        // 1) Sort lines top-to-bottom, left-to-right
        Collections.sort(lines, (a, b) -> {
            int dy = Integer.compare(a.centerY(), b.centerY());
            if (dy != 0) return dy;
            return Integer.compare(a.left(), b.left());
        });

        // 2) Group into rows by Y proximity
        List<GridRow> rows = groupIntoRows(lines);

        // 3) Estimate the "price column" x-position using money-like lines
        int priceX = estimatePriceColumnX(rows);

        // 4) Convert each row into leftText/rightText using priceX split
        List<ReceiptGridRow> grid = new ArrayList<>();
        for (GridRow r : rows) {
            ReceiptGridRow rr = toGridRow(r, priceX);
            // keep even empty-right rows (useful for header), but we can filter later
            grid.add(rr);
        }
        return grid;
    }

    private static List<GridRow> groupIntoRows(List<OcrLine> lines) {
        List<GridRow> rows = new ArrayList<>();
        GridRow current = null;

        int runningRowY = -1;
        int runningRowHeight = 0;

        for (OcrLine l : lines) {
            if (current == null) {
                current = new GridRow();
                current.add(l);
                rows.add(current);
                runningRowY = l.centerY();
                runningRowHeight = Math.max(1, l.height());
                continue;
            }

            // dynamic threshold: based on typical text height
            int threshold = Math.max(12, (int)(runningRowHeight * 0.7));
            int dy = Math.abs(l.centerY() - runningRowY);

            if (dy <= threshold) {
                current.add(l);
                // update row stats
                runningRowY = (runningRowY + l.centerY()) / 2;
                runningRowHeight = (runningRowHeight + Math.max(1, l.height())) / 2;
            } else {
                current = new GridRow();
                current.add(l);
                rows.add(current);
                runningRowY = l.centerY();
                runningRowHeight = Math.max(1, l.height());
            }
        }

        // sort each row left->right
        for (GridRow r : rows) {
            r.lines.sort(Comparator.comparingInt(OcrLine::left));
        }

        return rows;
    }

    private static int estimatePriceColumnX(List<GridRow> rows) {
        List<Integer> candidates = new ArrayList<>();

        for (GridRow r : rows) {
            for (OcrLine l : r.lines) {
                // if line contains a money token, use its centerX as candidate
                if (containsMoney(l.text)) {
                    candidates.add(l.centerX());
                }
            }
        }

        if (candidates.isEmpty()) {
            // fallback: use 2/3 screen-ish heuristic; we’ll refine later
            return Integer.MAX_VALUE / 4;
        }

        Collections.sort(candidates);

        // use median as stable column estimate
        return candidates.get(candidates.size() / 2);
    }

    private static ReceiptGridRow toGridRow(GridRow row, int priceX) {
        if (row.lines.isEmpty()) return new ReceiptGridRow("", "");

        StringBuilder left = new StringBuilder();
        StringBuilder right = new StringBuilder();

        for (OcrLine l : row.lines) {
            boolean isRight = l.centerX() >= priceX - 10; // small tolerance
            if (isRight) {
                if (right.length() > 0) right.append(" ");
                right.append(l.text);
            } else {
                if (left.length() > 0) left.append(" ");
                left.append(l.text);
            }
        }

        return new ReceiptGridRow(left.toString(), right.toString());
    }

    private static boolean containsMoney(String s) {
        if (s == null) return false;
        return MONEY.matcher(s).find();
    }

    private static String normalize(String s) {
        if (s == null) return "";
        String t = s.trim();

        // common OCR cleanup (light-touch; we’ll expand later)
        t = t.replace("—", "-").replace("–", "-");
        t = t.replaceAll("\\s+", " ");

        return t.trim();
    }
}