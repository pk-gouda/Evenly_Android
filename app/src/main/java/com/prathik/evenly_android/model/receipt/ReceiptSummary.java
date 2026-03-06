package com.prathik.evenly_android.model.receipt;

public class ReceiptSummary {
    public Double subtotal;     // from receipt lines, if detected
    public Double taxAmount;    // from receipt lines, if detected
    public Double total;        // from receipt lines, if detected
    public Double taxPercent;   // derived if possible
    public Double tipAmount;    // restaurants only

    @Override
    public String toString() {
        return "subtotal=" + subtotal
                + ", tax=" + taxAmount
                + ", tip=" + tipAmount
                + ", total=" + total
                + ", tax%=" + taxPercent;
    }
}