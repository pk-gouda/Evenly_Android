package com.prathik.evenly_android.model.receipt;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Complete structured output of the parsing pipeline.
 *
 * All existing fields (items, summary, computedSubtotal, computedTax,
 * computedTotal, storeType, issues) are unchanged — nothing else breaks.
 *
 * New fields added:
 *   adjustments  — discounts, credits, fees, tips
 *   payment      — cash/card payment details
 *   splitTotal   — use this in split UI, not computedTotal
 *   computedTotalBeforeCredits
 *   amountChargedNow
 *   confidence
 *   requiresUserChoice  — true if unresolved credits exist
 *   requiresUserReview  — true if low confidence or mismatch
 */
public class ParsedReceipt implements Serializable {

    // ── Existing fields (unchanged) ───────────────────────────────────────────
    public List<ReceiptLineItem>  items       = new ArrayList<>();
    public ReceiptSummary         summary     = new ReceiptSummary();
    public double computedSubtotal = 0.0;
    public double computedTax      = 0.0;
    public double computedTotal    = 0.0;   // = computedTotalBeforeCredits (backward compat)
    public List<ParseIssue>       issues      = new ArrayList<>();
    public TaxCodeResolver.StoreType storeType = TaxCodeResolver.StoreType.GENERIC;

    // ── New fields ────────────────────────────────────────────────────────────
    public List<ReceiptAdjustment> adjustments = new ArrayList<>();
    public ReceiptPayment          payment     = new ReceiptPayment();

    /** Bill total before any credits are applied: subtotal + tax + fees + tip - discounts */
    public double computedTotalBeforeCredits = 0.0;

    /**
     * The amount that should actually be split.
     * Starts equal to computedTotalBeforeCredits.
     * Updated by ReceiptReconciler.applyCreditTreatment() after user decides.
     */
    public double splitTotal = 0.0;

    /** What the payer actually paid right now (card charge or cash paid) */
    public Double amountChargedNow = null;

    /** Computed fees total (delivery fee, service charge, etc.) */
    public double computedFees = 0.0;

    /** 0.0–1.0 parse quality score */
    public double confidence = 0.0;

    /** True if unresolved credits exist — UI should ask user how to treat them */
    public boolean requiresUserChoice = false;

    /** True if low confidence or totals mismatch — UI should show a review prompt */
    public boolean requiresUserReview = false;

    // ── Existing convenience methods (unchanged) ──────────────────────────────
    public boolean hasItemLevelTax() {
        return storeType == TaxCodeResolver.StoreType.COSTCO_CHICAGO
                || storeType == TaxCodeResolver.StoreType.COSTCO_GENERIC
                || storeType == TaxCodeResolver.StoreType.WALMART
                || storeType == TaxCodeResolver.StoreType.TARGET;
    }

    public boolean isRestaurant() {
        return storeType == TaxCodeResolver.StoreType.RESTAURANT;
    }

    // ── New convenience methods ───────────────────────────────────────────────
    public double totalCredits() {
        double sum = 0;
        for (ReceiptAdjustment a : adjustments)
            if (a.type == ReceiptAdjustment.Type.CREDIT) sum += a.amount;
        return round2(sum);
    }

    public double totalDiscounts() {
        double sum = 0;
        for (ReceiptAdjustment a : adjustments)
            if (a.type == ReceiptAdjustment.Type.DISCOUNT) sum += a.amount;
        return round2(sum);
    }

    public double totalFees() {
        double sum = 0;
        for (ReceiptAdjustment a : adjustments)
            if (a.type == ReceiptAdjustment.Type.FEE) sum += a.amount;
        return round2(sum);
    }

    public double tipAmount() {
        for (ReceiptAdjustment a : adjustments)
            if (a.type == ReceiptAdjustment.Type.TIP) return a.amount;
        return summary.tipAmount != null ? summary.tipAmount : 0.0;
    }

    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
}