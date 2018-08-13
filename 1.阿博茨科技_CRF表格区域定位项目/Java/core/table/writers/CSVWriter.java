package com.abcft.pdfextract.core.table.writers;

import com.abcft.pdfextract.core.table.Cell;
import com.abcft.pdfextract.core.table.Table;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.QuoteMode;
import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class CSVWriter extends PlainTextWriter {

    public static CSVFormat DEFAULT_CSV_FORMAT = CSVFormat.EXCEL
            .withQuoteMode(QuoteMode.NON_NUMERIC)
            .withEscape('\\');

    private final CSVFormat format;

    public CSVWriter() {
        this(DEFAULT_CSV_FORMAT);
    }

    public CSVWriter(CSVFormat format) {
        this.format = format;
    }

    public CSVFormat getFormat() {
        return format;
    }

    @Override
    public void writeTable(Appendable out, Table table) throws IOException {
        CSVPrinter printer = new CSVPrinter(out, format);
        int rowCount = table.getRowCount();
        int colCount = table.getColumnCount();
        for (int i = 0; i < rowCount; i++) {
            List<String> cells = new ArrayList<>(colCount);
            for (int j = 0; j < colCount; j++) {
                Cell cell = table.getCell(i, j);
                if (cell.isDeleted()) {
                    continue;
                }
                String text = cell.getTextAt(i, j);
                cells.add(text);
            }
            printer.printRecord(cells);
        }
        printer.flush();
        IOUtils.closeQuietly(printer);
    }

}
