package com.prathik.evenly_android.model.receipt;

import java.util.ArrayList;
import java.util.List;

public class ParsedReceipt {
    public List<ReceiptLineItem> items = new ArrayList<>();
    public ReceiptSummary summary = new ReceiptSummary();

    // computed from items (and tax)
    public double computedSubtotal = 0.0;
    public double computedTax = 0.0;
    public double computedTotal = 0.0;

    public List<ParseIssue> issues = new ArrayList<>();
}