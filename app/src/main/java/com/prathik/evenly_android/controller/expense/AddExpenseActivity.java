package com.prathik.evenly_android.controller.expense;

import android.content.Intent;
import android.content.IntentSender;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;

import com.google.android.gms.tasks.Task;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.documentscanner.GmsDocumentScanner;
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions;
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning;
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import com.prathik.evenly_android.R;
import com.prathik.evenly_android.controller.split.ItemAssignmentActivity;
import com.prathik.evenly_android.model.ocr.OcrLine;
import com.prathik.evenly_android.model.ocr.ReceiptGridRow;
import com.prathik.evenly_android.model.receipt.OcrCleaner;
import com.prathik.evenly_android.model.receipt.ParsedReceipt;
import com.prathik.evenly_android.model.receipt.ReceiptLineItem;
import com.prathik.evenly_android.model.split.SplitSession;
import com.prathik.evenly_android.service.ocr.ReceiptGridBuilder;
import com.prathik.evenly_android.service.receipt.ReceiptItemExtractor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class AddExpenseActivity extends AppCompatActivity {

    public static final String EXTRA_CONTEXT_TYPE = "context_type";
    public static final String CONTEXT_GROUP      = "GROUP";
    public static final String CONTEXT_FRIEND     = "FRIEND";

    // ── Logcat tags ───────────────────────────────────────────────────────────
    // Filter by "RECEIPT_DBG" in Logcat to see the full OCR + parse pipeline.
    // Each section has its own sub-tag so you can narrow further:
    //   RECEIPT_DBG   → master tag, all sections
    //   RECEIPT_RAW   → raw OCR lines straight from ML Kit
    //   RECEIPT_CLEAN → lines after OcrCleaner
    //   RECEIPT_GRID  → spatial grid rows (left | right columns)
    //   RECEIPT_ITEMS → parsed ReceiptLineItems
    //   RECEIPT_TOTALS→ subtotal / tax / total / store type
    private static final String TAG        = "RECEIPT_DBG";
    private static final String TAG_RAW    = "RECEIPT_RAW";
    private static final String TAG_CLEAN  = "RECEIPT_CLEAN";
    private static final String TAG_GRID   = "RECEIPT_GRID";
    private static final String TAG_ITEMS  = "RECEIPT_ITEMS";
    private static final String TAG_TOTALS = "RECEIPT_TOTALS";

    private TextView     tvHeader;
    private CardView     cardItemization;
    private LinearLayout llItems;
    private TextView     tvSubtotal;
    private TextView     tvTaxLabel;
    private TextView     tvTax;
    private TextView     tvTotal;
    private TextView     tvIssues;
    private Button       btnSplitReceipt;

    private ParsedReceipt lastParsedReceipt = null;

    // ── Launchers ─────────────────────────────────────────────────────────────

    private final ActivityResultLauncher<String> pickImageLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) runOcrParseAndRender(uri);
            });

    private final ActivityResultLauncher<IntentSenderRequest> scannerLauncher =
            registerForActivityResult(new ActivityResultContracts.StartIntentSenderForResult(), result -> {
                if (result.getResultCode() != RESULT_OK || result.getData() == null) {
                    Toast.makeText(this, "Scan cancelled", Toast.LENGTH_SHORT).show();
                    return;
                }
                GmsDocumentScanningResult scanResult =
                        GmsDocumentScanningResult.fromActivityResultIntent(result.getData());
                if (scanResult == null || scanResult.getPages() == null || scanResult.getPages().isEmpty()) {
                    Toast.makeText(this, "No scan pages returned", Toast.LENGTH_SHORT).show();
                    return;
                }
                runOcrParseAndRender(scanResult.getPages().get(0).getImageUri());
            });

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_add_expense);

        String contextType = getIntent().getStringExtra(EXTRA_CONTEXT_TYPE);
        if (contextType == null) contextType = CONTEXT_GROUP;

        tvHeader        = findViewById(R.id.tvHeader);
        cardItemization = findViewById(R.id.cardItemization);
        llItems         = findViewById(R.id.llItems);
        tvSubtotal      = findViewById(R.id.tvSubtotal);
        tvTaxLabel      = findViewById(R.id.tvTaxLabel);
        tvTax           = findViewById(R.id.tvTax);
        tvTotal         = findViewById(R.id.tvTotal);
        tvIssues        = findViewById(R.id.tvIssues);
        btnSplitReceipt = findViewById(R.id.btnSplitReceipt);

        tvHeader.setText(contextType.equals(CONTEXT_FRIEND)
                ? "Add expense • Friend" : "Add expense • Group");

        ImageButton btnReceipt = findViewById(R.id.btnReceiptImage);
        btnReceipt.setOnClickListener(v -> showImageSourceChooser());

        btnSplitReceipt.setVisibility(View.GONE);
        btnSplitReceipt.setOnClickListener(v -> launchSplitFlow());
    }

    // ── Image source ──────────────────────────────────────────────────────────

    private void showImageSourceChooser() {
        new AlertDialog.Builder(this)
                .setTitle("Add receipt image")
                .setItems(new CharSequence[]{"Scan (Auto crop)", "Gallery"}, (dialog, which) -> {
                    if (which == 0) startAutoScan();
                    else openGallery();
                })
                .show();
    }

    private void openGallery() { pickImageLauncher.launch("image/*"); }

    private void startAutoScan() {
        GmsDocumentScannerOptions options = new GmsDocumentScannerOptions.Builder()
                .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                .setPageLimit(1)
                .build();
        GmsDocumentScanning.getClient(options)
                .getStartScanIntent(this)
                .addOnSuccessListener(sender ->
                        scannerLauncher.launch(new IntentSenderRequest.Builder(sender).build()))
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Scanner failed", e);
                    Toast.makeText(this, "Scanner failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
    }

    // ── OCR pipeline ──────────────────────────────────────────────────────────

    private void runOcrParseAndRender(Uri imageUri) {
        try {
            InputImage image = InputImage.fromFilePath(this, imageUri);
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                    .process(image)
                    .addOnSuccessListener(text -> {

                        // ── Step 1: Raw OCR lines ─────────────────────────────
                        List<OcrLine> rawLines = extractLinesWithBoxes(text);
                        logRawLines(rawLines);

                        // ── Step 2: Clean each line ───────────────────────────
                        List<OcrLine> cleanedLines = new ArrayList<>();
                        for (OcrLine line : rawLines) {
                            String cleaned = OcrCleaner.cleanLine(line.text);
                            if (!cleaned.isEmpty())
                                cleanedLines.add(new OcrLine(cleaned, line.box));
                        }
                        logCleanedLines(rawLines, cleanedLines);

                        // ── Step 3: Build spatial grid ────────────────────────
                        List<ReceiptGridRow> grid = ReceiptGridBuilder.buildGrid(cleanedLines);
                        logGrid(grid);

                        // ── Step 4: Parse items + totals ──────────────────────
                        List<String> rawTextLines = new ArrayList<>();
                        for (OcrLine ol : rawLines) rawTextLines.add(ol.text);
                        ParsedReceipt receipt = ReceiptItemExtractor.parse(grid, rawTextLines);

                        // ── Step 5: Log parsed result ─────────────────────────
                        logParsedItems(receipt);
                        logTotals(receipt);

                        // ── Step 6: Cache + render ────────────────────────────
                        lastParsedReceipt = receipt;
                        renderItemization(receipt);

                        if (!receipt.items.isEmpty())
                            btnSplitReceipt.setVisibility(View.VISIBLE);
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "OCR failed: " + e.getMessage(), e);
                        Toast.makeText(this, "OCR failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    });
        } catch (Exception e) {
            Log.e(TAG, "Could not load image: " + e.getMessage(), e);
            Toast.makeText(this, "Could not load image: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private List<OcrLine> extractLinesWithBoxes(Text visionText) {
        List<OcrLine> lines = new ArrayList<>();
        for (Text.TextBlock block : visionText.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                Rect box = line.getBoundingBox();
                String txt = line.getText();
                if (box != null && txt != null && !txt.trim().isEmpty())
                    lines.add(new OcrLine(txt, box));
            }
        }
        return lines;
    }

    // ── Logcat helpers ────────────────────────────────────────────────────────

    /**
     * §1 RAW — every line ML Kit returned, with its bounding box Y position.
     * Use this to catch OCR misreads before any cleaning is applied.
     * Filter: RECEIPT_RAW
     */
    private void logRawLines(List<OcrLine> rawLines) {
        Log.d(TAG, "━━━ §1 RAW OCR  (" + rawLines.size() + " lines) ━━━━━━━━━━━━━━━━━━━━━━");
        for (int i = 0; i < rawLines.size(); i++) {
            OcrLine ol = rawLines.get(i);
            String boxInfo = ol.box != null
                    ? " [y=" + ol.box.top + "–" + ol.box.bottom + " x=" + ol.box.left + "–" + ol.box.right + "]"
                    : "";
            Log.d(TAG_RAW, String.format(Locale.US, "  [%02d]%s  \"%s\"", i, boxInfo, ol.text));
        }
    }

    /**
     * §2 CLEANED — shows what OcrCleaner changed.
     * Lines that changed are marked with "→" so you can spot the diff instantly.
     * Lines that were filtered out (empty after cleaning) are marked [DROPPED].
     * Filter: RECEIPT_CLEAN
     */
    private void logCleanedLines(List<OcrLine> rawLines, List<OcrLine> cleanedLines) {
        Log.d(TAG, "━━━ §2 CLEANED  (" + cleanedLines.size() + " lines) ━━━━━━━━━━━━━━━━━━━━");

        int ci = 0;
        for (OcrLine raw : rawLines) {
            String cleaned = OcrCleaner.cleanLine(raw.text);
            if (cleaned.isEmpty()) {
                Log.d(TAG_CLEAN, "  [DROPPED]  \"" + raw.text + "\"");
            } else if (!cleaned.equals(raw.text)) {
                Log.d(TAG_CLEAN, "  [CHANGED]  \"" + raw.text + "\"  →  \"" + cleaned + "\"");
            } else {
                Log.d(TAG_CLEAN, "  [OK]       \"" + cleaned + "\"");
            }
        }
    }

    /**
     * §3 GRID — the spatial 2-column layout the parser sees.
     * LEFT column is item names; RIGHT column is prices.
     * If a price ends up in the wrong column, that's a ReceiptGridBuilder issue.
     * Filter: RECEIPT_GRID
     */
    private void logGrid(List<ReceiptGridRow> grid) {
        Log.d(TAG, "━━━ §3 GRID  (" + grid.size() + " rows) ━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        for (int i = 0; i < grid.size(); i++) {
            ReceiptGridRow row = grid.get(i);
            Log.d(TAG_GRID, String.format(Locale.US,
                    "  [%02d]  LEFT=%-40s  RIGHT=%s",
                    i,
                    "\"" + row.leftText + "\"",
                    "\"" + row.rightText + "\""));
        }
    }

    /**
     * §4 ITEMS — every ReceiptLineItem that was extracted.
     * Includes name, amount, tax code, tax rate, itemTax, discount.
     * Filter: RECEIPT_ITEMS
     */
    private void logParsedItems(ParsedReceipt receipt) {
        Log.d(TAG, "━━━ §4 PARSED ITEMS  (" + receipt.items.size() + " items) ━━━━━━━━━━━━━━━━");
        if (receipt.items.isEmpty()) {
            Log.w(TAG_ITEMS, "  !! NO ITEMS EXTRACTED — check §3 GRID for mis-parsed rows");
            return;
        }
        for (int i = 0; i < receipt.items.size(); i++) {
            ReceiptLineItem item = receipt.items.get(i);
            StringBuilder sb = new StringBuilder();
            sb.append(String.format(Locale.US, "  [%02d]  %-35s  $%6.2f",
                    i, "\"" + item.name + "\"",
                    item.amount != null ? item.amount : 0.0));
            if (item.discountAmount > 0)
                sb.append(String.format(Locale.US, "  disc=-$%.2f", item.discountAmount));
            if (!item.taxCode.isEmpty())
                sb.append(String.format(Locale.US, "  code=%s(%.2f%%)",
                        item.taxCode, item.taxRate * 100));
            if (item.itemTax > 0)
                sb.append(String.format(Locale.US, "  tax=$%.2f", item.itemTax));
            sb.append(String.format(Locale.US, "  conf=%.0f%%", item.confidence * 100));
            Log.d(TAG_ITEMS, sb.toString());
        }
    }

    /**
     * §5 TOTALS — subtotal / tax / total + any parse issues and store type.
     * If subtotal/total are wrong, compare against §4 ITEMS sum.
     * Filter: RECEIPT_TOTALS
     */
    private void logTotals(ParsedReceipt receipt) {
        Log.d(TAG, "━━━ §5 TOTALS ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        Log.d(TAG_TOTALS, "  Store:       " + receipt.storeType);
        Log.d(TAG_TOTALS, "  ItemLevelTax:" + receipt.hasItemLevelTax());
        Log.d(TAG_TOTALS, "  Subtotal:    $" + String.format(Locale.US, "%.2f", receipt.computedSubtotal)
                + (receipt.summary.subtotal != null
                ? "  (receipt says $" + String.format(Locale.US, "%.2f", receipt.summary.subtotal) + ")"
                : "  (no subtotal on receipt)"));
        Log.d(TAG_TOTALS, "  Tax:         $" + String.format(Locale.US, "%.2f", receipt.computedTax)
                + (receipt.summary.taxAmount != null
                ? "  (receipt says $" + String.format(Locale.US, "%.2f", receipt.summary.taxAmount) + ")"
                : "  (no tax on receipt)"));
        if (receipt.summary.tipAmount != null && receipt.summary.tipAmount > 0)
            Log.d(TAG_TOTALS, "  Tip:         $" + String.format(Locale.US, "%.2f", receipt.summary.tipAmount));
        Log.d(TAG_TOTALS, "  Total:       $" + String.format(Locale.US, "%.2f", receipt.computedTotal)
                + (receipt.summary.total != null
                ? "  (receipt says $" + String.format(Locale.US, "%.2f", receipt.summary.total) + ")"
                : "  (no total on receipt)"));
        if (!receipt.issues.isEmpty())
            Log.w(TAG_TOTALS, "  !! ISSUES:   " + receipt.issues);
        Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    }

    // ── Split flow ────────────────────────────────────────────────────────────

    private void launchSplitFlow() {
        if (lastParsedReceipt == null || lastParsedReceipt.items.isEmpty()) {
            Toast.makeText(this, "Scan a receipt first", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(this, ItemAssignmentActivity.class);
        intent.putExtra(ItemAssignmentActivity.EXTRA_SESSION, new SplitSession(lastParsedReceipt));
        startActivity(intent);
    }

    // ── Render ────────────────────────────────────────────────────────────────

    private void renderItemization(ParsedReceipt receipt) {
        cardItemization.setVisibility(View.VISIBLE);
        llItems.removeAllViews();

        for (ReceiptLineItem item : receipt.items) {
            llItems.addView(makeItemRow(item));
        }

        if (receipt.items.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No items detected");
            empty.setTextColor(0xFF999999);
            empty.setTextSize(13);
            llItems.addView(empty);
        }

        double sub = receipt.summary.subtotal != null
                ? receipt.summary.subtotal : receipt.computedSubtotal;
        tvSubtotal.setText(formatMoney(sub));

        double tax = receipt.summary.taxAmount != null
                ? receipt.summary.taxAmount : receipt.computedTax;
        if (receipt.hasItemLevelTax()) {
            tvTaxLabel.setText("Tax (per item ✓)");
        } else if (receipt.summary.taxPercent != null && receipt.summary.taxPercent > 0) {
            tvTaxLabel.setText(String.format(Locale.US, "Tax (%.2f%%)", receipt.summary.taxPercent));
        }
        tvTax.setText(formatMoney(tax));

        double total = receipt.summary.total != null
                ? receipt.summary.total : receipt.computedTotal;
        tvTotal.setText(formatMoney(total));

        if (!receipt.issues.isEmpty()) {
            StringBuilder warn = new StringBuilder("⚠ ");
            for (int i = 0; i < receipt.issues.size(); i++) {
                if (i > 0) warn.append(", ");
                warn.append(receipt.issues.get(i).toString().replace("_", " "));
            }
            tvIssues.setText(warn.toString());
            tvIssues.setVisibility(View.VISIBLE);
        } else {
            tvIssues.setVisibility(View.GONE);
        }
    }

    private LinearLayout makeItemRow(ReceiptLineItem item) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.setMargins(0, 0, 0, dpToPx(6));
        row.setLayoutParams(rowParams);

        TextView tvName = new TextView(this);
        tvName.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        tvName.setText(item.name);
        tvName.setTextSize(13);
        tvName.setTextColor(0xFF333333);

        LinearLayout rightCol = new LinearLayout(this);
        rightCol.setOrientation(LinearLayout.VERTICAL);
        rightCol.setGravity(Gravity.END);
        rightCol.setLayoutParams(new LinearLayout.LayoutParams(
                dpToPx(90), LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView tvPrice = new TextView(this);
        tvPrice.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        tvPrice.setText(formatMoney(item.amount != null ? item.amount : 0));
        tvPrice.setTextSize(13);
        tvPrice.setTextColor(item.amount != null && item.amount < 0 ? 0xFFD32F2F : 0xFF333333);
        tvPrice.setGravity(Gravity.END);
        rightCol.addView(tvPrice);

        if (item.taxRate > 0 && item.itemTax > 0) {
            TextView tvTaxBadge = new TextView(this);
            tvTaxBadge.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            tvTaxBadge.setText(String.format(Locale.US, "+%s tax", formatMoney(item.itemTax)));
            tvTaxBadge.setTextSize(10);
            tvTaxBadge.setTextColor(0xFF888888);
            tvTaxBadge.setGravity(Gravity.END);
            rightCol.addView(tvTaxBadge);
        }

        row.addView(tvName);
        row.addView(rightCol);
        return row;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String formatMoney(double amount) {
        return String.format(Locale.US, "$%.2f", amount);
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}