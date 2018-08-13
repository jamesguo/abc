package com.abcft.pdfextract.core.table.writers;

import java.io.IOException;

import com.abcft.pdfextract.core.table.Table;

public interface Writer {

    void writeFileStart(Appendable out, String path) throws IOException;

    void writePageStart(Appendable out, int page) throws IOException;

    void writeTableStart(Appendable out, Table table) throws IOException;

    void writeTableTitle(Appendable out, String title) throws IOException;

    void writeTableCaps(Appendable out, String caps) throws IOException;

    void writeTableUnitText(Appendable out, String unitText) throws IOException;

    void writeTableShoes(Appendable out, String shoes) throws IOException;

    void writeTable(Appendable out, Table table) throws IOException;

    void writeTableEnd(Appendable out, Table table) throws IOException;

    void writePageEnd(Appendable out, int page) throws IOException;

    void writeFileEnd(Appendable out, String path) throws IOException;

}
