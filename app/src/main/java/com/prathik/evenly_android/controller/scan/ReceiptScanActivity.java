package com.prathik.evenly_android.controller.scan;

import android.content.IntentSender;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
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
import com.prathik.evenly_android.model.ocr.OcrLine;
import com.prathik.evenly_android.model.ocr.ReceiptGridRow;
import com.prathik.evenly_android.model.receipt.OcrCleaner;
import com.prathik.evenly_android.model.receipt.ParsedReceipt;
import com.prathik.evenly_android.model.receipt.ReceiptLineItem;
import com.prathik.evenly_android.service.ocr.ReceiptGridBuilder;
import com.prathik.evenly_android.service.receipt.ReceiptItemExtractor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ReceiptScanActivity extends AppCompatActivity {

    private TextView tvStatus;
    private CardView cardItemization;
    private LinearLayout llItems;
    private TextView tvSubtotal;
    private TextView tvTaxLabel;
    private TextView tvTax;
    private TextView tvTotal;
    private TextView tvIssues;

    private final ActivityResultLauncher<IntentSenderRequest> scannerLauncher =
            registerForActivityResult(new ActivityResultContracts.StartIntentSenderForResult(), result -> {
                if (result.getResultCode() != RESULT_OK || result.getData() == null) {
                    Toast.makeText(this, "Scan cancelled", Toast.LENGTH_SHORT).show();
                    finish();
                    return;
                }

                GmsDocumentScanningResult scanResult =
                        GmsDocumentScanningResult.fromActivityResultIntent(result.getData());

                if (scanResult == null || scanResult.getPages() == null || scanResult.getPages().isEmpty()) {
                    Toast.makeText(this, "No pages returned", Toast.LENGTH_SHORT).show();
                    finish();
                    return;
                }

                Uri imageUri = scanResult.getPages().get(0).getImageUri();
                tvStatus.setText("Processing receipt...");
                runOcrOnImageUri(imageUri);
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_receipt_scan);

        tvStatus       = findViewById(R.id.tvStatus);
        cardItemization = findViewById(R.id.cardItemization);
        llItems        = findViewById(R.id.llItems);
        tvSubtotal     = findViewById(R.id.tvSubtotal);
        tvTaxLabel     = findViewById(R.id.tvTaxLabel);
        tvTax          = findViewById(R.id.tvTax);
        tvTotal        = findViewById(R.id.tvTotal);
        tvIssues       = findViewById(R.id.tvIssues);

        startDocumentScanner();
    }

    // ── Scanner ───────────────────────────────────────────────────────────────

    private void startDocumentScanner() {
        GmsDocumentScannerOptions options = new GmsDocumentScannerOptions.Builder()
                .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                .setPageLimit(1)
                .build();

        GmsDocumentScanner scanner = GmsDocumentScanning.getClient(options);

        Task<IntentSender> task = scanner.getStartScanIntent(this);
        task.addOnSuccessListener(intentSender -> {
                    IntentSenderRequest request = new IntentSenderRequest.Builder(intentSender).build();
                    scannerLauncher.launch(request);
                })
                .addOnFailureListener(e -> {
                    tvStatus.setText("Scanner failed: " + e.getMessage());
                    Log.e("SCAN", "Scanner failed", e);
                    Toast.makeText(this, "Scanner failed", Toast.LENGTH_LONG).show();
                    finish();
                });
    }

    // ── OCR ───────────────────────────────────────────────────────────────────

    private void runOcrOnImageUri(Uri imageUri) {
        try {
            InputImage image = InputImage.fromFilePath(this, imageUri);
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                    .process(image)
                    .addOnSuccessListener(this::onOcrSuccess)
                    .addOnFailureListener(e -> {
                        tvStatus.setText("OCR failed: " + e.getMessage());
                        Log.e("OCR", "OCR failed", e);
                    });
        } catch (Exception e) {
            tvStatus.setText("Could not load image: " + e.getMessage());
            Log.e("OCR", "Could not load image", e);
        }
    }

    private void onOcrSuccess(Text text) {
        // 1. Extract raw OCR lines with bounding boxes
        List<OcrLine> rawLines = extractLinesWithBoxes(text);

        // 2. Clean each line (fix pipes, price confusions, word errors)
        List<OcrLine> cleanedLines = new ArrayList<>();
        for (OcrLine line : rawLines) {
            String cleaned = OcrCleaner.cleanLine(line.text);
            if (!cleaned.isEmpty()) {
                cleanedLines.add(new OcrLine(cleaned, line.box));
            }
        }

        // 3. Build spatial grid (groups tokens into rows by Y position)
        List<ReceiptGridRow> grid = ReceiptGridBuilder.buildGrid(cleanedLines);

        // 4. Parse items and summary totals
        ParsedReceipt receipt = ReceiptItemExtractor.parse(grid);

        // 5. Render the ITEMIZATION section
        renderItemization(receipt);

        Log.d("RECEIPT", buildDebugLog(receipt));
    }

    private List<OcrLine> extractLinesWithBoxes(Text visionText) {
        List<OcrLine> lines = new ArrayList<>();
        for (Text.TextBlock block : visionText.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                Rect box = line.getBoundingBox();
                String txt = line.getText();
                if (box != null && txt != null && !txt.trim().isEmpty()) {
                    lines.add(new OcrLine(txt, box));
                }
            }
        }
        return lines;
    }

    // ── Render ────────────────────────────────────────────────────────────────

    private void renderItemization(ParsedReceipt receipt) {
        // Show the card
        cardItemization.setVisibility(View.VISIBLE);
        tvStatus.setText("");

        // Clear any previously added rows
        llItems.removeAllViews();

        // Add one row per item
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

        // Subtotal
        double sub = receipt.summary.subtotal != null
                ? receipt.summary.subtotal
                : receipt.computedSubtotal;
        tvSubtotal.setText(formatMoney(sub));

        // Tax — show percentage in label if available
        double tax = receipt.summary.taxAmount != null
                ? receipt.summary.taxAmount
                : receipt.computedTax;
        if (receipt.summary.taxPercent != null && receipt.summary.taxPercent > 0) {
            tvTaxLabel.setText(String.format(Locale.US, "Tax (%.2f%%)", receipt.summary.taxPercent));
        }
        tvTax.setText(formatMoney(tax));

        // Total
        double total = receipt.summary.total != null
                ? receipt.summary.total
                : receipt.computedTotal;
        tvTotal.setText(formatMoney(total));

        // Issues warning
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

    /**
     * Build a single horizontal row: item name (weighted) + price (fixed width).
     */
    private LinearLayout makeItemRow(ReceiptLineItem item) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.setMargins(0, 0, 0, dpToPx(6));
        row.setLayoutParams(rowParams);

        // Item name
        TextView tvName = new TextView(this);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tvName.setLayoutParams(nameParams);
        tvName.setText(item.name);
        tvName.setTextSize(13);
        tvName.setTextColor(0xFF333333);

        // Price
        TextView tvPrice = new TextView(this);
        LinearLayout.LayoutParams priceParams = new LinearLayout.LayoutParams(
                dpToPx(80), LinearLayout.LayoutParams.WRAP_CONTENT);
        tvPrice.setLayoutParams(priceParams);
        tvPrice.setText(formatMoney(item.amount != null ? item.amount : 0));
        tvPrice.setTextSize(13);
        tvPrice.setTextColor(item.amount != null && item.amount < 0 ? 0xFFD32F2F : 0xFF333333);
        tvPrice.setGravity(android.view.Gravity.END);

        row.addView(tvName);
        row.addView(tvPrice);
        return row;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String formatMoney(double amount) {
        return String.format(Locale.US, "$%.2f", amount);
    }

    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }

    private String buildDebugLog(ParsedReceipt receipt) {
        StringBuilder sb = new StringBuilder("── ITEMS ──\n");
        for (ReceiptLineItem item : receipt.items) {
            sb.append(item.toString()).append("\n");
        }
        sb.append("\n── SUMMARY ──\n");
        sb.append("Subtotal: ").append(formatMoney(receipt.computedSubtotal)).append("\n");
        sb.append("Tax:      ").append(formatMoney(receipt.computedTax)).append("\n");
        sb.append("Total:    ").append(formatMoney(receipt.computedTotal)).append("\n");
        if (!receipt.issues.isEmpty()) {
            sb.append("Issues: ").append(receipt.issues).append("\n");
        }
        return sb.toString();
    }
}