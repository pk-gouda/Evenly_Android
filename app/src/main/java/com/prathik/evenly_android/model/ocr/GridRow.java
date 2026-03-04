package com.prathik.evenly_android.model.ocr;

import java.util.ArrayList;
import java.util.List;

public class GridRow {
    public final List<OcrLine> lines = new ArrayList<>();

    public void add(OcrLine l) { lines.add(l); }

    // Convenience: row "y" to sort rows
    public int avgCenterY() {
        if (lines.isEmpty()) return 0;
        int sum = 0;
        for (OcrLine l : lines) sum += l.centerY();
        return sum / lines.size();
    }
}