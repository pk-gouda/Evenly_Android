package com.prathik.evenly_android.model.receipt;

import java.util.ArrayList;
import java.util.List;

public class ParsedReceipt {
    public List<ReceiptLineItem> items = new ArrayList<>();
    public ReceiptSummary summary = new ReceiptSummary();

    // Computed from items (and tax)
    public double computedSubtotal = 0.0;
    public double computedTax      = 0.0;
    public double computedTotal    = 0.0;

    public List<ParseIssue> issues = new ArrayList<>();

    // Store type detected from receipt header
    public TaxCodeResolver.StoreType storeType = TaxCodeResolver.StoreType.GENERIC;

    /**
     * True if this receipt has item-level tax codes (Costco, Target, Walmart).
     * When true, use per-item tax for fair splitting.
     * When false, fall back to proportional tax distribution.
     */
    public boolean hasItemLevelTax() {
        return storeType == TaxCodeResolver.StoreType.COSTCO_CHICAGO
                || storeType == TaxCodeResolver.StoreType.COSTCO_GENERIC
                || storeType == TaxCodeResolver.StoreType.WALMART
                || storeType == TaxCodeResolver.StoreType.TARGET;
    }

    /**
     * True if this receipt is from a restaurant (tip handling differs).
     */
    public boolean isRestaurant() {
        return storeType == TaxCodeResolver.StoreType.RESTAURANT;
    }
}