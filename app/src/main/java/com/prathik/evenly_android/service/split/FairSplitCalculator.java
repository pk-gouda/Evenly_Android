package com.prathik.evenly_android.service.split;

import com.prathik.evenly_android.model.receipt.ParsedReceipt;
import com.prathik.evenly_android.model.receipt.ReceiptLineItem;
import com.prathik.evenly_android.model.split.ItemAssignment;
import com.prathik.evenly_android.model.split.SplitParticipant;
import com.prathik.evenly_android.model.split.SplitResult;
import com.prathik.evenly_android.model.split.SplitSession;

import java.util.List;

/**
 * FairSplitCalculator — the core of Evenly's value proposition.
 *
 * How it's different from Splitwise:
 *   Splitwise: total_tax / num_people  →  everyone pays the same tax
 *   Evenly:    each item carries its OWN tax rate (A=10.25%, E=1.25%, F=3.0%)
 *              → person who bought the taxable wine pays more tax than the
 *                person who only bought tax-exempt groceries. FAIR.
 *
 * Algorithm for each item:
 *   1. Get base price (amount - discountAmount)
 *   2. Get item tax (already computed by TaxCodeResolver)
 *   3. For each participant sharing the item:
 *        fraction     = their_shares / total_shares
 *        item_cost    = base_price × fraction
 *        item_tax     = item.itemTax × fraction      ← EXACT per-item tax
 *   4. If item-level tax is unavailable (GENERIC store):
 *        use proportional fallback: receipt_tax × (person_subtotal / receipt_subtotal)
 *
 * Shared items with unequal shares (e.g. 2:1):
 *   person A fraction = 2/3,  person B fraction = 1/3
 *   Both costs AND taxes scale by the same fraction — perfectly fair.
 */
public class FairSplitCalculator {

    /**
     * Compute the fair split for a complete SplitSession.
     *
     * @param session  contains the parsed receipt + all item assignments
     * @return         SplitResult with per-person breakdowns
     */
    public static SplitResult calculate(SplitSession session) {
        ParsedReceipt receipt  = session.receipt;
        List<ItemAssignment> assignments = session.assignments;

        SplitResult result = new SplitResult();
        result.hasItemLevelTax = receipt.hasItemLevelTax();

        // Pre-create a PersonShare for every participant so everyone appears
        // in the result even if they claimed no items
        for (SplitParticipant p : session.participants) {
            result.getOrCreate(p);
        }

        // ── Pass 1: item costs + per-item taxes ───────────────────────────────
        for (int i = 0; i < receipt.items.size(); i++) {
            ReceiptLineItem item = receipt.items.get(i);
            ItemAssignment  asn  = assignments.get(i);

            double basePrice = item.taxableAmount();  // price after discount
            double itemTax   = item.itemTax;          // tax already computed by TaxCodeResolver

            if (asn.isUnassigned()) {
                // Nobody claimed this item — track it as unassigned
                result.unassignedSubtotal = round2(result.unassignedSubtotal + basePrice);
                result.unassignedTax      = round2(result.unassignedTax      + itemTax);
                continue;
            }

            int totalShares = asn.totalShares();

            for (int pi = 0; pi < asn.participants.size(); pi++) {
                SplitParticipant p       = asn.participants.get(pi);
                int              pShares = asn.shares.get(pi);
                double           frac    = (double) pShares / totalShares;

                double personItemCost = round2(basePrice * frac);
                double personItemTax  = round2(itemTax   * frac);

                SplitResult.PersonShare ps = result.getOrCreate(p);
                ps.itemSubtotal += personItemCost;
                ps.itemTax      += personItemTax;

                // Per-item breakdown for the summary screen
                ps.itemBreakdown.put(i, personItemCost);
                ps.itemTaxBreakdown.put(i, personItemTax);
            }
        }

        // ── Pass 2: proportional tax fallback ────────────────────────────────
        // Used when the store doesn't have per-item tax codes (GENERIC, Trader Joe's, etc.)
        // and TaxCodeResolver couldn't assign exact rates.
        if (!result.hasItemLevelTax && receipt.summary.taxAmount != null
                && receipt.summary.taxAmount > 0) {

            double receiptTax     = receipt.summary.taxAmount;
            double assignedSubtotal = 0;
            for (SplitResult.PersonShare ps : result.shares.values()) {
                assignedSubtotal += ps.itemSubtotal;
            }

            if (assignedSubtotal > 0) {
                for (SplitResult.PersonShare ps : result.shares.values()) {
                    double fraction = ps.itemSubtotal / assignedSubtotal;
                    ps.proportionalTax = round2(receiptTax * fraction);
                }
            }
        }

        // ── Pass 3: tip split (restaurants) ──────────────────────────────────
        // Tip is split equally across ALL participants (regardless of what they ordered).
        // Rationale: tip is a social obligation shared by the table, not proportional
        // to item cost (otherwise the person who ordered the salad tips less).
        if (receipt.summary.tipAmount != null && receipt.summary.tipAmount > 0) {
            double tip = receipt.summary.tipAmount;
            int    n   = session.participants.size();
            if (n > 0) {
                double tipPerPerson = round2(tip / n);
                // Handle rounding: add leftover cents to first person
                double distributed = round2(tipPerPerson * n);
                double remainder   = round2(tip - distributed);

                boolean firstPerson = true;
                for (SplitParticipant p : session.participants) {
                    SplitResult.PersonShare ps = result.getOrCreate(p);
                    ps.tipShare = tipPerPerson;
                    if (firstPerson && remainder != 0) {
                        ps.tipShare = round2(ps.tipShare + remainder);
                        firstPerson = false;
                    }
                }
            }
        }

        // ── Pass 4: round each person's totals ───────────────────────────────
        for (SplitResult.PersonShare ps : result.shares.values()) {
            ps.itemSubtotal    = round2(ps.itemSubtotal);
            ps.itemTax         = round2(ps.itemTax);
            ps.proportionalTax = round2(ps.proportionalTax);
            ps.tipShare        = round2(ps.tipShare);
        }

        return result;
    }

    /**
     * Convenience: build a SplitSession where all items are split equally
     * among all participants, then calculate.
     * Useful for "split everything evenly" quick-split mode.
     */
    public static SplitResult calculateEvenSplit(ParsedReceipt receipt,
                                                 List<SplitParticipant> participants) {
        SplitSession session = new SplitSession(receipt);
        for (SplitParticipant p : participants) session.addParticipant(p);
        for (int i = 0; i < receipt.items.size(); i++) session.assignToAll(i);
        return calculate(session);
    }

    // ── Debug ─────────────────────────────────────────────────────────────────

    /**
     * Human-readable summary of the split result.
     * Use this in your debug logs / unit tests.
     */
    public static String summarize(SplitResult result, ParsedReceipt receipt) {
        StringBuilder sb = new StringBuilder();
        sb.append("── FAIR SPLIT (").append(result.hasItemLevelTax ? "per-item tax" : "proportional tax").append(") ──\n");
        for (SplitResult.PersonShare ps : result.shares.values()) {
            sb.append(ps.toString()).append("\n");
            // Per-item detail
            for (java.util.Map.Entry<Integer, Double> e : ps.itemBreakdown.entrySet()) {
                ReceiptLineItem item = receipt.items.get(e.getKey());
                double itax = ps.itemTaxBreakdown.getOrDefault(e.getKey(), 0.0);
                sb.append("   ").append(item.name)
                        .append(": $").append(String.format(java.util.Locale.US, "%.2f", e.getValue()))
                        .append(" + tax $").append(String.format(java.util.Locale.US, "%.2f", itax))
                        .append("\n");
            }
        }
        if (result.unassignedSubtotal > 0) {
            sb.append("UNASSIGNED: $")
                    .append(String.format(java.util.Locale.US, "%.2f", result.unassignedSubtotal))
                    .append(" + tax $")
                    .append(String.format(java.util.Locale.US, "%.2f", result.unassignedTax))
                    .append("\n");
        }
        return sb.toString();
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}