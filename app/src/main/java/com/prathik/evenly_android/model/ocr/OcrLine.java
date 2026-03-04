package com.prathik.evenly_android.model.ocr;

import android.graphics.Rect;

public class OcrLine {
    public final String text;
    public final Rect box;

    public OcrLine(String text, Rect box) {
        this.text = text == null ? "" : text;
        this.box = box;
    }

    public int centerY() {
        return box == null ? 0 : (box.top + box.bottom) / 2;
    }

    public int centerX() {
        return box == null ? 0 : (box.left + box.right) / 2;
    }

    public int left() { return box == null ? 0 : box.left; }
    public int right() { return box == null ? 0 : box.right; }
    public int top() { return box == null ? 0 : box.top; }
    public int bottom() { return box == null ? 0 : box.bottom; }
    public int height() { return box == null ? 0 : (box.bottom - box.top); }
}