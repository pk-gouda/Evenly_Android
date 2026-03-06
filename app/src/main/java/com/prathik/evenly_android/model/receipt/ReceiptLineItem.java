package com.prathik.evenly_android.model.receipt;

public class ReceiptLineItem {
    public String name;
    public Double amount;       // line total (pre-discount price)
    public boolean taxable;
    public double confidence;
    public String taxCode;      // e.g. "E", "A", "AF"
    public double taxRate;      // resolved rate, e.g. 0.0125
    public double itemTax;      // computed tax: taxableAmount * taxRate
    public double discountAmount; // item-level discount (e.g. Costco instant savings for this item)

    public ReceiptLineItem(String name, Double amount, boolean taxable, double confidence) {
        this.name = name;
        this.amount = amount;
        this.taxable = taxable;
        this.confidence = confidence;
        this.taxCode = "";
        this.taxRate = 0.0;
        this.itemTax = 0.0;
        this.discountAmount = 0.0;
    }

    public ReceiptLineItem(String name, Double amount, boolean taxable,
                           double confidence, String taxCode, double taxRate) {
        this(name, amount, taxable, confidence);
        this.taxCode = taxCode != null ? taxCode : "";
        this.taxRate = taxRate;
        this.discountAmount = 0.0;
        // itemTax computed later by TaxCodeResolver after discounts are applied
    }

    /** The amount actually subject to tax (after any item-level discount) */
    public double taxableAmount() {
        double base = (amount != null ? amount : 0.0);
        return Math.max(0, base - discountAmount);
    }

    /** True cost to the buyer = (price - discount) + tax */
    public double totalWithTax() {
        return taxableAmount() + itemTax;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    @Override
    public String toString() {
        String s = name + " | $" + String.format(java.util.Locale.US, "%.2f", amount);
        if (discountAmount > 0)
            s += " disc=-$" + String.format(java.util.Locale.US, "%.2f", discountAmount);
        if (!taxCode.isEmpty())
            s += " [" + taxCode + " " + String.format(java.util.Locale.US, "%.2f%%", taxRate * 100) + "]";
        if (itemTax > 0)
            s += " tax=$" + String.format(java.util.Locale.US, "%.2f", itemTax);
        return s;
    }
}