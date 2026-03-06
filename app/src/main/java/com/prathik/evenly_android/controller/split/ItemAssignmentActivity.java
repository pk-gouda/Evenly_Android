package com.prathik.evenly_android.controller.split;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.prathik.evenly_android.R;
import com.prathik.evenly_android.model.receipt.ParsedReceipt;
import com.prathik.evenly_android.model.receipt.ReceiptLineItem;
import com.prathik.evenly_android.model.split.ItemAssignment;
import com.prathik.evenly_android.model.split.SplitParticipant;
import com.prathik.evenly_android.model.split.SplitSession;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ItemAssignmentActivity
 *
 * Screen 1 of the split flow.
 *
 * Layout (built entirely in code):
 * ┌──────────────────────────────────────────────┐
 * │  ← Back        Assign Items                  │
 * │  Participants: [Alice ×] [Bob ×] [+ Add]     │  ← chip row
 * ├──────────────────────────────────────────────┤
 * │  ITEM NAME               $PRICE              │
 * │  [ Alice ]  [ Bob ]  (filled = assigned)     │  ← per-item participant chips
 * │  ─────────────────────────────────────────   │
 * │  ...                                         │
 * ├──────────────────────────────────────────────┤
 * │  [Split All Evenly]         [See Summary →]  │
 * └──────────────────────────────────────────────┘
 *
 * Tapping a participant chip on an item toggles their claim.
 * The chip turns colored (filled) when claimed, grey when not.
 * "Split All Evenly" assigns every unassigned item to all participants.
 * "See Summary" advances to SplitSummaryActivity.
 */
public class ItemAssignmentActivity extends AppCompatActivity {

    public static final String EXTRA_SESSION = "split_session";

    // Chip colors
    private static final int[] PARTICIPANT_COLORS = {
            0xFF1565C0, // blue
            0xFF2E7D32, // green
            0xFF6A1B9A, // purple
            0xFFE65100, // orange
            0xFFC62828, // red
            0xFF00695C, // teal
    };

    private SplitSession session;
    private LinearLayout llParticipantChips;
    private LinearLayout llItems;
    private TextView tvUnassignedBadge;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Retrieve session from intent
        session = (SplitSession) getIntent().getSerializableExtra(EXTRA_SESSION);
        if (session == null) { finish(); return; }

        buildLayout();
    }

    // ── Layout builder ────────────────────────────────────────────────────────

    private void buildLayout() {
        // Root scroll
        ScrollView root = new ScrollView(this);
        root.setBackgroundColor(0xFFF5F5F5);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(16), dp(12), dp(16), dp(80));
        root.addView(container, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // ── Top bar ───────────────────────────────────────────────────────────
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(0, 0, 0, dp(12));

        ImageButton btnBack = new ImageButton(this);
        btnBack.setImageResource(android.R.drawable.ic_menu_revert);
        btnBack.setBackgroundColor(Color.TRANSPARENT);
        btnBack.setOnClickListener(v -> finish());

        TextView tvTitle = new TextView(this);
        tvTitle.setText("Assign Items");
        tvTitle.setTextSize(20);
        tvTitle.setTypeface(null, Typeface.BOLD);
        tvTitle.setTextColor(0xFF212121);
        tvTitle.setPadding(dp(8), 0, 0, 0);

        // Unassigned badge
        tvUnassignedBadge = new TextView(this);
        tvUnassignedBadge.setTextSize(12);
        tvUnassignedBadge.setTextColor(0xFFFFFFFF);
        tvUnassignedBadge.setBackgroundColor(0xFFE53935);
        tvUnassignedBadge.setPadding(dp(8), dp(3), dp(8), dp(3));
        tvUnassignedBadge.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        badgeParams.setMarginStart(dp(8));
        topBar.addView(btnBack);
        topBar.addView(tvTitle);
        topBar.addView(tvUnassignedBadge, badgeParams);

        container.addView(topBar);

        // ── Participant chips ──────────────────────────────────────────────────
        TextView tvParticipantsLabel = makeLabel("Participants");
        container.addView(tvParticipantsLabel);

        HorizontalScrollView chipScroll = new HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(false);
        llParticipantChips = new LinearLayout(this);
        llParticipantChips.setOrientation(LinearLayout.HORIZONTAL);
        llParticipantChips.setPadding(0, dp(4), 0, dp(12));
        chipScroll.addView(llParticipantChips);
        container.addView(chipScroll);

        renderParticipantChips();

        // ── Divider ───────────────────────────────────────────────────────────
        container.addView(makeDivider());

        // ── Item list ─────────────────────────────────────────────────────────
        llItems = new LinearLayout(this);
        llItems.setOrientation(LinearLayout.VERTICAL);
        container.addView(llItems);
        renderItemList();

        // ── Bottom action bar ─────────────────────────────────────────────────
        LinearLayout bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setPadding(0, dp(16), 0, 0);
        bottomBar.setGravity(Gravity.CENTER_VERTICAL);

        Button btnEvenSplit = new Button(this);
        btnEvenSplit.setText("Split All Evenly");
        btnEvenSplit.setTextColor(0xFF1565C0);
        btnEvenSplit.setBackgroundColor(Color.WHITE);
        btnEvenSplit.setOnClickListener(v -> splitAllEvenly());
        LinearLayout.LayoutParams evenParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        evenParams.setMarginEnd(dp(8));
        bottomBar.addView(btnEvenSplit, evenParams);

        Button btnSummary = new Button(this);
        btnSummary.setText("See Summary →");
        btnSummary.setTextColor(Color.WHITE);
        btnSummary.setBackgroundColor(0xFF1565C0);
        btnSummary.setOnClickListener(v -> goToSummary());
        LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        bottomBar.addView(btnSummary, summaryParams);

        container.addView(bottomBar);
        setContentView(root);

        updateUnassignedBadge();
    }

    // ── Participant chips ─────────────────────────────────────────────────────

    private void renderParticipantChips() {
        llParticipantChips.removeAllViews();
        for (int i = 0; i < session.participants.size(); i++) {
            SplitParticipant p   = session.participants.get(i);
            int              col = PARTICIPANT_COLORS[i % PARTICIPANT_COLORS.length];
            llParticipantChips.addView(makeParticipantChip(p, col));
        }
        // "+ Add" chip
        TextView addChip = new TextView(this);
        addChip.setText("+ Add");
        addChip.setTextColor(0xFF1565C0);
        addChip.setBackgroundColor(0xFFE3F2FD);
        addChip.setPadding(dp(14), dp(8), dp(14), dp(8));
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        ap.setMarginEnd(dp(8));
        addChip.setLayoutParams(ap);
        addChip.setOnClickListener(v -> showAddParticipantDialog());
        llParticipantChips.addView(addChip);
    }

    private View makeParticipantChip(SplitParticipant p, int color) {
        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setBackgroundColor(0xFFE8EAF6);
        chip.setPadding(dp(12), dp(8), dp(8), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(8));
        chip.setLayoutParams(lp);

        // Color dot
        View dot = new View(this);
        dot.setBackgroundColor(color);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(10), dp(10));
        dotLp.setMarginEnd(dp(6));
        dot.setLayoutParams(dotLp);
        chip.addView(dot);

        TextView tv = new TextView(this);
        tv.setText(p.name);
        tv.setTextColor(0xFF212121);
        tv.setTextSize(13);
        chip.addView(tv);

        // × remove button
        TextView rm = new TextView(this);
        rm.setText(" ×");
        rm.setTextColor(0xFF888888);
        rm.setTextSize(14);
        rm.setPadding(dp(4), 0, 0, 0);
        rm.setOnClickListener(v -> {
            session.removeParticipant(p);
            renderParticipantChips();
            renderItemList();
            updateUnassignedBadge();
        });
        chip.addView(rm);
        return chip;
    }

    private void showAddParticipantDialog() {
        // Simple text input dialog
        android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("Name");
        input.setPadding(dp(16), dp(8), dp(16), dp(8));
        new AlertDialog.Builder(this)
                .setTitle("Add Participant")
                .setView(input)
                .setPositiveButton("Add", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (!name.isEmpty()) {
                        String id = "p_" + System.currentTimeMillis();
                        session.addParticipant(new SplitParticipant(id, name));
                        renderParticipantChips();
                        renderItemList();
                        updateUnassignedBadge();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ── Item list ─────────────────────────────────────────────────────────────

    private void renderItemList() {
        llItems.removeAllViews();
        ParsedReceipt receipt = session.receipt;

        for (int i = 0; i < receipt.items.size(); i++) {
            ReceiptLineItem item = receipt.items.get(i);
            ItemAssignment  asn  = session.assignments.get(i);
            final int       idx  = i;

            // Item card
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackgroundColor(Color.WHITE);
            card.setPadding(dp(12), dp(10), dp(12), dp(10));
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            cardLp.setMargins(0, 0, 0, dp(8));
            card.setLayoutParams(cardLp);

            // Row 1: item name + price
            LinearLayout nameRow = new LinearLayout(this);
            nameRow.setOrientation(LinearLayout.HORIZONTAL);
            nameRow.setGravity(Gravity.CENTER_VERTICAL);

            TextView tvName = new TextView(this);
            tvName.setText(item.name);
            tvName.setTextSize(14);
            tvName.setTextColor(0xFF212121);
            LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            tvName.setLayoutParams(nameLp);

            LinearLayout priceCol = new LinearLayout(this);
            priceCol.setOrientation(LinearLayout.VERTICAL);
            priceCol.setGravity(Gravity.END);

            TextView tvPrice = new TextView(this);
            tvPrice.setText(formatMoney(item.amount != null ? item.amount : 0));
            tvPrice.setTextSize(14);
            tvPrice.setTextColor(item.amount != null && item.amount < 0 ? 0xFFD32F2F : 0xFF212121);
            tvPrice.setTypeface(null, Typeface.BOLD);
            tvPrice.setGravity(Gravity.END);
            priceCol.addView(tvPrice);

            // Tax badge
            if (item.itemTax > 0) {
                TextView tvTax = new TextView(this);
                tvTax.setText(String.format(Locale.US, "+%s tax", formatMoney(item.itemTax)));
                tvTax.setTextSize(10);
                tvTax.setTextColor(0xFF888888);
                tvTax.setGravity(Gravity.END);
                priceCol.addView(tvTax);
            }

            nameRow.addView(tvName);
            nameRow.addView(priceCol);
            card.addView(nameRow);

            // Row 2: participant claim chips (only if there are participants)
            if (!session.participants.isEmpty()) {
                HorizontalScrollView chipScroll = new HorizontalScrollView(this);
                chipScroll.setHorizontalScrollBarEnabled(false);
                LinearLayout chipRow = new LinearLayout(this);
                chipRow.setOrientation(LinearLayout.HORIZONTAL);
                chipRow.setPadding(0, dp(8), 0, 0);

                for (int pi = 0; pi < session.participants.size(); pi++) {
                    SplitParticipant p        = session.participants.get(pi);
                    boolean          claimed  = asn.participants.contains(p);
                    int              baseColor = PARTICIPANT_COLORS[pi % PARTICIPANT_COLORS.length];
                    chipRow.addView(makeItemClaimChip(p, idx, claimed, baseColor));
                }
                chipScroll.addView(chipRow);
                card.addView(chipScroll);
            }

            llItems.addView(card);
        }
    }

    /**
     * A chip on an item row representing one participant's claim.
     * Filled (colored) = claimed. Grey = not claimed. Tap to toggle.
     */
    private View makeItemClaimChip(SplitParticipant p, int itemIdx,
                                   boolean claimed, int color) {
        TextView chip = new TextView(this);
        chip.setText(p.name);
        chip.setTextSize(12);
        chip.setPadding(dp(10), dp(5), dp(10), dp(5));

        applyChipStyle(chip, claimed, color);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(6));
        chip.setLayoutParams(lp);

        chip.setOnClickListener(v -> {
            session.toggleParticipant(itemIdx, p);
            // Re-render just the item list (cheap enough)
            renderItemList();
            updateUnassignedBadge();
        });
        return chip;
    }

    private void applyChipStyle(TextView chip, boolean claimed, int color) {
        if (claimed) {
            chip.setBackgroundColor(color);
            chip.setTextColor(Color.WHITE);
        } else {
            chip.setBackgroundColor(0xFFEEEEEE);
            chip.setTextColor(0xFF757575);
        }
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    private void splitAllEvenly() {
        if (session.participants.isEmpty()) {
            Toast.makeText(this, "Add participants first", Toast.LENGTH_SHORT).show();
            return;
        }
        for (int i = 0; i < session.receipt.items.size(); i++) {
            session.assignToAll(i);
        }
        renderItemList();
        updateUnassignedBadge();
        Toast.makeText(this, "All items split evenly ✓", Toast.LENGTH_SHORT).show();
    }

    private void goToSummary() {
        if (session.participants.isEmpty()) {
            Toast.makeText(this, "Add at least one participant", Toast.LENGTH_SHORT).show();
            return;
        }
        if (session.unassignedCount() > 0) {
            new AlertDialog.Builder(this)
                    .setTitle("Unassigned Items")
                    .setMessage(session.unassignedCount() + " item(s) are not assigned to anyone.\n\nContinue anyway?")
                    .setPositiveButton("Continue", (d, w) -> launchSummary())
                    .setNegativeButton("Go Back", null)
                    .show();
        } else {
            launchSummary();
        }
    }

    private void launchSummary() {
        Intent intent = new Intent(this, SplitSummaryActivity.class);
        intent.putExtra(SplitSummaryActivity.EXTRA_SESSION, session);
        startActivity(intent);
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private void updateUnassignedBadge() {
        int n = session.unassignedCount();
        if (n > 0) {
            tvUnassignedBadge.setText(n + " unassigned");
            tvUnassignedBadge.setVisibility(View.VISIBLE);
        } else {
            tvUnassignedBadge.setVisibility(View.GONE);
        }
    }

    private TextView makeLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(11);
        tv.setTextColor(0xFF757575);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setAllCaps(true);
        tv.setPadding(0, dp(4), 0, dp(2));
        return tv;
    }

    private View makeDivider() {
        View v = new View(this);
        v.setBackgroundColor(0xFFE0E0E0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        lp.setMargins(0, 0, 0, dp(12));
        v.setLayoutParams(lp);
        return v;
    }

    private String formatMoney(double amount) {
        return String.format(Locale.US, "$%.2f", amount);
    }

    private int dp(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}