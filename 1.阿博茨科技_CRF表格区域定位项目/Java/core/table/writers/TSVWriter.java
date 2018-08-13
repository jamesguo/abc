package com.abcft.pdfextract.core.table.writers;

import org.apache.commons.csv.CSVFormat;

public class TSVWriter extends CSVWriter {

    public TSVWriter() {
        super(CSVFormat.TDF);
    }

}
