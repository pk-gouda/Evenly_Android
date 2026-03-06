package com.prathik.evenly_android.model.split;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The computed fair-split result for a whole receipt.
 *
 * For each participant, stores:
 *   - itemSubtotal  : sum of their share of each item's base price (post-discount)
 *   - itemTax       : sum of their EXACT tax per item (tax-code accurate)
 *   - proportionalTax: fallback tax share when item-level codes aren't available
 *   - tip           : their share of any tip (equal split across all participants)
 *   - total         : itemSubtotal + itemTax (or proportionalTax) + tip
 *
 * Unassigned items are tracked separately — they add to receiptUnassignedTotal.
 */
public class SplitResult implements Serializable {

    // ── Per-person breakdown ──────────────────────────────────────────────────

    public static class PersonShare implements Serializable {
        public final SplitParticipant participant;

        /** Raw item costs (base price × fraction, post-discount) */
        public double itemSubtotal = 0.0;

        /** Exact per-item tax (used when tax codes are known) */
        public double itemTax = 0.0;

        /** Proportional tax fallback (used when tax codes are unknown) */
        public double proportionalTax = 0.0;

        /** Their share of the tip (restaurant receipts) */
        public double tipShare = 0.0;

        /** Breakdown by item: itemIndex → amount this person owes for that item */
        public final Map<Integer, Double> itemBreakdown = new LinkedHashMap<>();

        /** Breakdown by item: itemIndex → tax this person owes for that item */
        public final Map<Integer, Double> itemTaxBreakdown = new LinkedHashMap<>();

        public PersonShare(SplitParticipant participant) {
            this.participant = participant;
        }

        /**
         * The tax this person actually owes.
         * Uses per-item tax if available, otherwise proportional.
         */
        public double effectiveTax() {
            return itemTax > 0 ? itemTax : proportionalTax;
        }

        /**
         * Grand total this person owes.
         * itemSubtotal + exact tax + tip
         */
        public double grandTotal() {
            return round2(itemSubtotal + effectiveTax() + tipShare);
        }

        private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }

        @Override
        public String toString() {
            return participant.name
                    + ": items=$" + String.format(java.util.Locale.US, "%.2f", itemSubtotal)
                    + " tax=$"    + String.format(java.util.Locale.US, "%.2f", effectiveTax())
                    + (tipShare > 0 ? " tip=$" + String.format(java.util.Locale.US, "%.2f", tipShare) : "")
                    + " TOTAL=$"  + String.format(java.util.Locale.US, "%.2f", grandTotal());
        }
    }

    // ── Receipt-level fields ──────────────────────────────────────────────────

    /** Whether item-level tax codes were used (Costco, Target, Walmart) */
    public boolean hasItemLevelTax = false;

    /** Sum of unassigned items' totals (nobody claimed these) */
    public double unassignedSubtotal = 0.0;

    /** Sum of unassigned items' taxes */
    public double unassignedTax = 0.0;

    /** Per-participant results, in insertion order */
    public final Map<String, PersonShare> shares = new LinkedHashMap<>();

    /** Ordered participant list (for UI rendering) */
    public final List<SplitParticipant> participants = new ArrayList<>();

    // ── Accessors ─────────────────────────────────────────────────────────────

    public PersonShare getShare(SplitParticipant p) {
        return shares.get(p.id);
    }

    public PersonShare getOrCreate(SplitParticipant p) {
        if (!shares.containsKey(p.id)) {
            shares.put(p.id, new PersonShare(p));
            if (!participants.contains(p)) participants.add(p);
        }
        return shares.get(p.id);
    }

    /** Sum of all participants' grand totals */
    public double totalAssigned() {
        double sum = 0;
        for (PersonShare ps : shares.values()) sum += ps.grandTotal();
        return round2(sum);
    }

    /** True if every item is assigned to at least one person */
    public boolean isFullyAssigned() { return round2(unassignedSubtotal) == 0.0; }

    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
}