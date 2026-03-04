package com.prathik.evenly_android.model.ocr;

public class ReceiptGridRow {
    public final String leftText;
    public final String rightText;

    public ReceiptGridRow(String leftText, String rightText) {
        this.leftText = leftText == null ? "" : leftText.trim();
        this.rightText = rightText == null ? "" : rightText.trim();
    }

    @Override
    public String toString() {
        return leftText + "  |  " + rightText;
    }
}