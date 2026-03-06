package com.prathik.evenly_android.model.split;

import com.prathik.evenly_android.model.receipt.ParsedReceipt;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Holds the complete state of one receipt-splitting session:
 *   - the parsed receipt (items + summary)
 *   - the list of participants
 *   - per-item assignments
 *
 * This object flows from the item-assignment screen into the
 * split calculator and finally to the summary screen.
 */
public class SplitSession implements Serializable {

    public final ParsedReceipt receipt;
    public final List<SplitParticipant> participants = new ArrayList<>();

    /**
     * One ItemAssignment per receipt item.
     * assignments.get(i) corresponds to receipt.items.get(i).
     */
    public final List<ItemAssignment> assignments = new ArrayList<>();

    public SplitSession(ParsedReceipt receipt) {
        this.receipt = receipt;
        // Pre-populate one empty assignment per item
        for (int i = 0; i < receipt.items.size(); i++) {
            assignments.add(new ItemAssignment(i));
        }
    }

    // ── Participant management ────────────────────────────────────────────────

    public void addParticipant(SplitParticipant p) {
        if (!participants.contains(p)) participants.add(p);
    }

    public void removeParticipant(SplitParticipant p) {
        participants.remove(p);
        // Also remove them from all item assignments
        for (ItemAssignment a : assignments) a.removeParticipant(p);
    }

    // ── Convenience assignment helpers ────────────────────────────────────────

    /** Assign item i equally to all current participants */
    public void assignToAll(int itemIndex) {
        ItemAssignment a = assignments.get(itemIndex);
        a.participants.clear();
        a.shares.clear();
        for (SplitParticipant p : participants) a.addParticipant(p, 1);
    }

    /** Assign item i to a single participant only */
    public void assignToOne(int itemIndex, SplitParticipant p) {
        ItemAssignment a = assignments.get(itemIndex);
        a.participants.clear();
        a.shares.clear();
        a.addParticipant(p, 1);
    }

    /** Toggle a participant's claim on an item (equal share) */
    public void toggleParticipant(int itemIndex, SplitParticipant p) {
        ItemAssignment a = assignments.get(itemIndex);
        if (a.participants.contains(p)) {
            a.removeParticipant(p);
        } else {
            a.addParticipant(p, 1);
        }
    }

    // ── Query helpers ─────────────────────────────────────────────────────────

    public int unassignedCount() {
        int n = 0;
        for (ItemAssignment a : assignments) if (a.isUnassigned()) n++;
        return n;
    }

    public boolean isFullyAssigned() { return unassignedCount() == 0; }
}