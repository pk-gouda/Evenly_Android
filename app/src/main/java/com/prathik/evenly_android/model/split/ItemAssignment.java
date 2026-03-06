package com.prathik.evenly_android.model.split;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Tracks which participants share a single receipt line item,
 * and how many "shares" each person holds.
 *
 * Examples:
 *   1 person claims whole item   → participants=[A],    shares=[1]
 *   3 people split equally       → participants=[A,B,C], shares=[1,1,1]
 *   2 people split 2/3 + 1/3    → participants=[A,B],  shares=[2,1]
 *
 * Person fraction = person_shares / total_shares
 */
public class ItemAssignment implements Serializable {

    /** Index into ParsedReceipt.items — links back to the source item */
    public final int itemIndex;

    /** Ordered list of participants sharing this item */
    public final List<SplitParticipant> participants = new ArrayList<>();

    /** Parallel list of integer share counts (same size as participants) */
    public final List<Integer> shares = new ArrayList<>();

    public ItemAssignment(int itemIndex) {
        this.itemIndex = itemIndex;
    }

    // ── Mutation ──────────────────────────────────────────────────────────────

    /** Add participant with 1 equal share (or update if already present) */
    public void addParticipant(SplitParticipant p) {
        addParticipant(p, 1);
    }

    /** Add participant with a custom share count (or update if already present) */
    public void addParticipant(SplitParticipant p, int shareCount) {
        int idx = participants.indexOf(p);
        if (idx >= 0) {
            shares.set(idx, Math.max(1, shareCount));
        } else {
            participants.add(p);
            shares.add(Math.max(1, shareCount));
        }
    }

    /** Remove a participant entirely */
    public void removeParticipant(SplitParticipant p) {
        int idx = participants.indexOf(p);
        if (idx >= 0) {
            participants.remove(idx);
            shares.remove(idx);
        }
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    /** True if nobody has claimed this item yet */
    public boolean isUnassigned() { return participants.isEmpty(); }

    /** Total share units across all participants */
    public int totalShares() {
        int sum = 0;
        for (int s : shares) sum += s;
        return sum;
    }

    /**
     * Fraction of this item belonging to participant p (0.0 if not in list).
     * e.g. shares=[2,1] → participant[0] owns 2/3
     */
    public double fractionFor(SplitParticipant p) {
        int idx = participants.indexOf(p);
        if (idx < 0) return 0.0;
        int total = totalShares();
        return total == 0 ? 0.0 : (double) shares.get(idx) / total;
    }

    public int participantCount() { return participants.size(); }
}