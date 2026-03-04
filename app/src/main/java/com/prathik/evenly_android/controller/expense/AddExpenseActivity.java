package com.prathik.evenly_android.controller.expense;

import android.content.IntentSender;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.gms.tasks.Task;
import com.google.mlkit.vision.documentscanner.GmsDocumentScanner;
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions;
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning;
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult;
import com.prathik.evenly_android.R;
import android.graphics.Rect;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import com.prathik.evenly_android.model.ocr.OcrLine;
import com.prathik.evenly_android.model.ocr.ReceiptGridRow;
import com.prathik.evenly_android.service.ocr.ReceiptGridBuilder;

import java.util.ArrayList;
import java.util.List;

public class AddExpenseActivity extends AppCompatActivity {

    public static final String EXTRA_CONTEXT_TYPE = "context_type";
    public static final String CONTEXT_GROUP = "GROUP";
    public static final String CONTEXT_FRIEND = "FRIEND";

    private TextView tvHeader;
    private TextView tvDebug;

    // Gallery picker
    private final ActivityResultLauncher<String> pickImageLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) showPickedImageUri(uri);
            });

    // Document scanner result
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

                Uri imageUri = scanResult.getPages().get(0).getImageUri();
                showPickedImageUri(imageUri);
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_add_expense);

        String contextType = getIntent().getStringExtra(EXTRA_CONTEXT_TYPE);
        if (contextType == null) contextType = CONTEXT_GROUP;

        tvHeader = findViewById(R.id.tvHeader);
        tvDebug = findViewById(R.id.tvDebug);

        tvHeader.setText(contextType.equals(CONTEXT_FRIEND) ? "Add expense • Friend" : "Add expense • Group");

        ImageButton btnReceipt = findViewById(R.id.btnReceiptImage);
        btnReceipt.setOnClickListener(v -> showImageSourceChooser());
    }

    private void showImageSourceChooser() {
        new AlertDialog.Builder(this)
                .setTitle("Add receipt image")
                .setItems(new CharSequence[]{"Scan (Auto crop)", "Gallery"}, (dialog, which) -> {
                    if (which == 0) startAutoScan();
                    else openGallery();
                })
                .show();
    }

    private void openGallery() {
        pickImageLauncher.launch("image/*");
    }

    private void startAutoScan() {
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
                    Log.e("DOC_SCAN", "Scanner failed", e);
                    Toast.makeText(this, "Scanner failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
    }

    private void showPickedImageUri(Uri uri) {
        tvDebug.setText("Picked image: " + uri);
        runOcrAndShowGrid(uri);
        // Next: run OCR on this URI -> feed into parser
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

    private void runOcrAndShowGrid(Uri imageUri) {
        try {
            InputImage image = InputImage.fromFilePath(this, imageUri);

            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                    .process(image)
                    .addOnSuccessListener(text -> {
                        List<OcrLine> ocrLines = extractLinesWithBoxes(text);
                        List<ReceiptGridRow> grid = ReceiptGridBuilder.buildGrid(ocrLines);

                        StringBuilder sb = new StringBuilder();
                        sb.append("RECEIPT GRID:\n\n");
                        for (ReceiptGridRow row : grid) {
                            sb.append(row.toString()).append("\n");
                        }

                        tvDebug.setText(sb.toString());   // <-- this is what you want to see
                    })
                    .addOnFailureListener(e -> tvDebug.setText("OCR failed: " + e.getMessage()));

        } catch (Exception e) {
            tvDebug.setText("Could not load image: " + e.getMessage());
        }
    }
}