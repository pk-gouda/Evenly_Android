package com.prathik.evenly_android.controller.split;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.prathik.evenly_android.model.receipt.ParsedReceipt;
import com.prathik.evenly_android.model.receipt.ReceiptLineItem;
import com.prathik.evenly_android.model.split.SplitParticipant;
import com.prathik.evenly_android.model.split.SplitResult;
import com.prathik.evenly_android.model.split.SplitSession;
import com.prathik.evenly_android.service.split.FairSplitCalculator;

import java.util.Locale;
import java.util.Map;

/**
 * SplitSummaryActivity
 *
 * Screen 2 of the split flow — shows the computed fair split.
 *
 * Layout:
 * ┌──────────────────────────────────────────────┐
 * │  ← Back          Split Summary               │
 * │  ┌─────────────────────────────────────────┐ │
 * │  │ 🟦 Alice                       $18.47   │ │
 * │  │   Organic Chapati       $9.99 + $0.12T  │ │
 * │  │   Mango Juice           $6.99 + $0.72T  │ │
 * │  │   ──────────────────────────────────    │ │
 * │  │   Items: $16.98   Tax: $0.84   →$18.47  │ │  ← EXACT tax, not averaged
 * │  └─────────────────────────────────────────┘ │
 * │  ┌─────────────────────────────────────────┐ │
 * │  │ 🟩 Bob                          $7.50   │ │
 * │  │   Kirkland Water 1/2    $4.50 + $0.46T  │ │
 * │  │   ...                                   │ │
 * │  └─────────────────────────────────────────┘ │
 * │  ┌─────────────────────────────────────────┐ │
 * │  │  RECEIPT TOTAL         $XX.XX           │ │
 * │  │  Assigned total        $XX.XX           │ │
 * │  │  ⚠ Unassigned          $XX.XX  (if any) │ │
 * │  └─────────────────────────────────────────┘ │
 * │  [← Edit Assignments]     [Confirm & Save →] │
 * └──────────────────────────────────────────────┘
 */
public class SplitSummaryActivity extends AppCompatActivity {

    public static final String EXTRA_SESSION = "split_session";

    private static final int[] PARTICIPANT_COLORS = {
            0xFF1565C0, 0xFF2E7D32, 0xFF6A1B9A,
            0xFFE65100, 0xFFC62828, 0xFF00695C,
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SplitSession session = (SplitSession) getIntent().getSerializableExtra(EXTRA_SESSION);
        if (session == null) { finish(); return; }

        // Run the fair-split calculation
        SplitResult result = FairSplitCalculator.calculate(session);

        // Log for debugging
        android.util.Log.d("FAIR_SPLIT", FairSplitCalculator.summarize(result, session.receipt));

        buildLayout(session, result);
    }

    // ── Layout ────────────────────────────────────────────────────────────────

    private void buildLayout(SplitSession session, SplitResult result) {
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
        topBar.setPadding(0, 0, 0, dp(16));

        android.widget.ImageButton btnBack = new android.widget.ImageButton(this);
        btnBack.setImageResource(android.R.drawable.ic_menu_revert);
        btnBack.setBackgroundColor(Color.TRANSPARENT);
        btnBack.setOnClickListener(v -> finish());

        TextView tvTitle = new TextView(this);
        tvTitle.setText("Split Summary");
        tvTitle.setTextSize(20);
        tvTitle.setTypeface(null, Typeface.BOLD);
        tvTitle.setTextColor(0xFF212121);
        tvTitle.setPadding(dp(8), 0, 0, 0);
        topBar.addView(btnBack);
        topBar.addView(tvTitle);
        container.addView(topBar);

        // Tax method badge
        TextView taxBadge = new TextView(this);
        taxBadge.setText(result.hasItemLevelTax
                ? "✓ Using exact per-item tax rates"
                : "Using proportional tax distribution");
        taxBadge.setTextSize(12);
        taxBadge.setTextColor(result.hasItemLevelTax ? 0xFF2E7D32 : 0xFF757575);
        taxBadge.setPadding(0, 0, 0, dp(12));
        container.addView(taxBadge);

        // ── Per-person cards ──────────────────────────────────────────────────
        int colorIdx = 0;
        for (SplitParticipant p : result.participants) {
            SplitResult.PersonShare ps = result.getShare(p);
            if (ps == null) continue;
            int color = PARTICIPANT_COLORS[colorIdx++ % PARTICIPANT_COLORS.length];
            container.addView(makePersonCard(ps, session.receipt, color));
        }

        // ── Receipt totals card ───────────────────────────────────────────────
        container.addView(makeTotalsCard(session.receipt, result));

        // ── Bottom buttons ────────────────────────────────────────────────────
        LinearLayout bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setPadding(0, dp(16), 0, 0);

        Button btnEdit = new Button(this);
        btnEdit.setText("← Edit");
        btnEdit.setTextColor(0xFF1565C0);
        btnEdit.setBackgroundColor(Color.WHITE);
        btnEdit.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        ep.setMarginEnd(dp(8));
        bottomBar.addView(btnEdit, ep);

        Button btnConfirm = new Button(this);
        btnConfirm.setText("Confirm & Save →");
        btnConfirm.setTextColor(Color.WHITE);
        btnConfirm.setBackgroundColor(0xFF1565C0);
        btnConfirm.setOnClickListener(v -> onConfirm(result));
        bottomBar.addView(btnConfirm, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        container.addView(bottomBar);
        setContentView(root);
    }

    // ── Person card ───────────────────────────────────────────────────────────

    private View makePersonCard(SplitResult.PersonShare ps,
                                ParsedReceipt receipt, int color) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(Color.WHITE);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(0, 0, 0, dp(10));
        card.setLayoutParams(cardLp);

        // Header: colored dot + name + grand total
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, dp(8));

        View dot = new View(this);
        dot.setBackgroundColor(color);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(12), dp(12));
        dotLp.setMarginEnd(dp(8));
        dot.setLayoutParams(dotLp);

        TextView tvName = new TextView(this);
        tvName.setText(ps.participant.name);
        tvName.setTextSize(16);
        tvName.setTypeface(null, Typeface.BOLD);
        tvName.setTextColor(0xFF212121);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tvName.setLayoutParams(nameLp);

        TextView tvTotal = new TextView(this);
        tvTotal.setText(formatMoney(ps.grandTotal()));
        tvTotal.setTextSize(18);
        tvTotal.setTypeface(null, Typeface.BOLD);
        tvTotal.setTextColor(color);

        header.addView(dot);
        header.addView(tvName);
        header.addView(tvTotal);
        card.addView(header);

        // Per-item breakdown
        for (Map.Entry<Integer, Double> e : ps.itemBreakdown.entrySet()) {
            int    idx      = e.getKey();
            double itemCost = e.getValue();
            double itemTax  = ps.itemTaxBreakdown.getOrDefault(idx, 0.0);
            ReceiptLineItem item = receipt.items.get(idx);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            rowLp.setMargins(0, 0, 0, dp(3));
            row.setLayoutParams(rowLp);

            TextView tvItemName = new TextView(this);
            tvItemName.setText(item.name);
            tvItemName.setTextSize(12);
            tvItemName.setTextColor(0xFF616161);
            LinearLayout.LayoutParams itemNameLp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            tvItemName.setLayoutParams(itemNameLp);

            // Price + tax inline: "$9.99 + $0.12T"
            String priceStr = formatMoney(itemCost);
            if (itemTax > 0) {
                priceStr += " +" + formatMoney(itemTax) + "T";
            }
            TextView tvItemPrice = new TextView(this);
            tvItemPrice.setText(priceStr);
            tvItemPrice.setTextSize(12);
            tvItemPrice.setTextColor(0xFF424242);
            tvItemPrice.setGravity(Gravity.END);

            row.addView(tvItemName);
            row.addView(tvItemPrice);
            card.addView(row);
        }

        // Subtotal / tax / total footer
        card.addView(makeThinDivider());

        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setPadding(0, dp(6), 0, 0);

        footer.addView(makeSummaryFooterChip("Items: " + formatMoney(ps.itemSubtotal), 0xFF616161));
        footer.addView(makeSummaryFooterChip(
                "Tax: " + formatMoney(ps.effectiveTax()), 0xFF757575));
        if (ps.tipShare > 0)
            footer.addView(makeSummaryFooterChip("Tip: " + formatMoney(ps.tipShare), 0xFF757575));
        footer.addView(makeSummaryFooterChip("→ " + formatMoney(ps.grandTotal()), color));

        card.addView(footer);
        return card;
    }

    // ── Receipt totals card ───────────────────────────────────────────────────

    private View makeTotalsCard(ParsedReceipt receipt, SplitResult result) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(Color.WHITE);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(0, 0, 0, dp(10));
        card.setLayoutParams(cardLp);

        TextView tvLabel = new TextView(this);
        tvLabel.setText("RECEIPT TOTALS");
        tvLabel.setTextSize(11);
        tvLabel.setTextColor(0xFF9E9E9E);
        tvLabel.setTypeface(null, Typeface.BOLD);
        tvLabel.setAllCaps(true);
        tvLabel.setPadding(0, 0, 0, dp(8));
        card.addView(tvLabel);

        double receiptTotal = receipt.summary.total != null
                ? receipt.summary.total : receipt.computedTotal;
        double receiptTax   = receipt.summary.taxAmount != null
                ? receipt.summary.taxAmount : receipt.computedTax;

        card.addView(makeTotalsRow("Receipt subtotal", receipt.computedSubtotal, false));
        card.addView(makeTotalsRow("Receipt tax", receiptTax, false));
        if (receipt.summary.tipAmount != null && receipt.summary.tipAmount > 0)
            card.addView(makeTotalsRow("Tip", receipt.summary.tipAmount, false));
        card.addView(makeTotalsRow("Receipt total", receiptTotal, true));

        card.addView(makeThinDivider());

        card.addView(makeTotalsRow("Assigned total", result.totalAssigned(), false));
        if (!result.isFullyAssigned()) {
            card.addView(makeWarningRow(
                    "⚠ Unassigned: " + formatMoney(result.unassignedSubtotal)
                            + " + " + formatMoney(result.unassignedTax) + " tax"));
        }

        return card;
    }

    // ── View helpers ──────────────────────────────────────────────────────────

    private View makeTotalsRow(String label, double amount, boolean bold) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(4));
        row.setLayoutParams(lp);

        TextView tvL = new TextView(this);
        tvL.setText(label);
        tvL.setTextSize(13);
        tvL.setTextColor(bold ? 0xFF212121 : 0xFF616161);
        if (bold) tvL.setTypeface(null, Typeface.BOLD);
        tvL.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvR = new TextView(this);
        tvR.setText(formatMoney(amount));
        tvR.setTextSize(13);
        tvR.setTextColor(bold ? 0xFF212121 : 0xFF616161);
        if (bold) tvR.setTypeface(null, Typeface.BOLD);
        tvR.setGravity(Gravity.END);

        row.addView(tvL);
        row.addView(tvR);
        return row;
    }

    private View makeWarningRow(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTextColor(0xFFE53935);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(4), 0, 0);
        tv.setLayoutParams(lp);
        return tv;
    }

    private TextView makeSummaryFooterChip(String text, int textColor) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(11);
        tv.setTextColor(textColor);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(10));
        tv.setLayoutParams(lp);
        return tv;
    }

    private View makeThinDivider() {
        View v = new View(this);
        v.setBackgroundColor(0xFFEEEEEE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
        lp.setMargins(0, dp(8), 0, dp(4));
        v.setLayoutParams(lp);
        return v;
    }

    private void onConfirm(SplitResult result) {
        // TODO: persist the split result to the expense/group model
        // For now, just return to MainActivity with a success signal
        android.util.Log.d("SPLIT_CONFIRM", FairSplitCalculator.summarize(result,
                ((SplitSession) getIntent().getSerializableExtra(EXTRA_SESSION)).receipt));
        android.widget.Toast.makeText(this, "Split saved!", android.widget.Toast.LENGTH_SHORT).show();
        // Walk back to main
        Intent intent = new Intent(this,
                com.prathik.evenly_android.controller.MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
    }

    private String formatMoney(double amount) {
        return String.format(Locale.US, "$%.2f", amount);
    }

    private int dp(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}