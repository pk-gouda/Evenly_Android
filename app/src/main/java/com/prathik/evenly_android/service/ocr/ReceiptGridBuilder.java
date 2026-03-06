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

    // Detect money in these forms:
    // 12.34, 12,34, 12 34, -3.50, 1,234.56
    private static final Pattern MONEY_ANY =
            Pattern.compile("(?<!\\d)-?(?:\\d{1,3}(?:,\\d{3})*|\\d+)(?:[\\.,]\\d{2}|\\s\\d{2})(?!\\d)");

    public static List<ReceiptGridRow> buildGrid(List<OcrLine> rawLines) {
        List<OcrLine> lines = new ArrayList<>();
        for (OcrLine l : rawLines) {
            if (l == null || l.box == null) continue;
            String t = normalize(l.text);
            if (t.isEmpty()) continue;
            lines.add(new OcrLine(t, l.box));
        }

        // Sort top-to-bottom, left-to-right
        Collections.sort(lines, (a, b) -> {
            int dy = Integer.compare(a.centerY(), b.centerY());
            if (dy != 0) return dy;
            return Integer.compare(a.left(), b.left());
        });

        List<GridRow> rows = groupIntoRows(lines);

        int priceX = estimatePriceColumnX(rows);

        List<ReceiptGridRow> grid = new ArrayList<>();
        for (GridRow r : rows) {
            grid.add(toGridRow(r, priceX));
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

            int threshold = Math.max(12, (int)(runningRowHeight * 0.7));
            int dy = Math.abs(l.centerY() - runningRowY);

            if (dy <= threshold) {
                current.add(l);
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

        for (GridRow r : rows) {
            r.lines.sort(Comparator.comparingInt(OcrLine::left));
        }
        return rows;
    }

    private static int estimatePriceColumnX(List<GridRow> rows) {
        List<Integer> candidates = new ArrayList<>();

        for (GridRow r : rows) {
            for (OcrLine l : r.lines) {
                if (containsMoney(l.text)) {
                    // Using RIGHT edge is more stable than centerX
                    candidates.add(l.right());
                }
            }
        }

        if (candidates.isEmpty()) {
            // If we can't estimate, pick a conservative split:
            // "everything right-of-mid" counts as price
            return 500; // safe-ish default; extractor will still work even if wrong now
        }

        Collections.sort(candidates);
        return candidates.get(candidates.size() / 2); // median
    }

    private static ReceiptGridRow toGridRow(GridRow row, int priceX) {
        if (row.lines.isEmpty()) return new ReceiptGridRow("", "");

        StringBuilder left = new StringBuilder();
        StringBuilder right = new StringBuilder();

        for (OcrLine l : row.lines) {
            boolean isRight = l.left() >= (priceX - 60); // more forgiving
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
        return MONEY_ANY.matcher(s).find();
    }

    private static String normalize(String s) {
        if (s == null) return "";
        String t = s.trim();
        t = t.replace("—", "-").replace("–", "-");
        t = t.replaceAll("\\s+", " ");
        return t.trim();
    }
}