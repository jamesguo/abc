package com.abcft.pdfextract.core.table.extractors;

import java.util.List;

import com.abcft.pdfextract.core.table.Page;
import com.abcft.pdfextract.core.table.Table;

public interface ExtractionAlgorithm {

    List<? extends Table> extract(Page page);
    String toString();
    String getVersion();
    
}
