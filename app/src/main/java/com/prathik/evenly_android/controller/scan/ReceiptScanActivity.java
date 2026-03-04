package com.prathik.evenly_android.controller.scan;

import android.content.IntentSender;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

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
import com.prathik.evenly_android.service.ocr.ReceiptGridBuilder;

import java.util.ArrayList;
import java.util.List;

public class ReceiptScanActivity extends AppCompatActivity {

    private TextView tvStatus;

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
                tvStatus.setText("Scanned image: " + imageUri);

                runOcrOnImageUri(imageUri);
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_receipt_scan);

        tvStatus = findViewById(R.id.tvStatus);
        startDocumentScanner();
    }

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
        List<OcrLine> ocrLines = extractLinesWithBoxes(text);

        List<ReceiptGridRow> grid = ReceiptGridBuilder.buildGrid(ocrLines);

        StringBuilder sb = new StringBuilder();
        sb.append("RECEIPT GRID:\n\n");
        for (ReceiptGridRow row : grid) {
            sb.append(row.toString()).append("\n");
        }

        tvStatus.setText(sb.toString());
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
}