package com.abcft.pdfextract.core.table.writers;

import com.abcft.pdfextract.core.table.Table;
import com.abcft.pdfextract.util.JsonUtil;
import com.google.gson.JsonArray;

import java.io.IOException;
import java.util.List;

public class JSONWriter implements Writer {

    private boolean firstTable = false;
    public JSONWriter() {
    }

    @Override
    public void writeFileStart(Appendable out, String path) throws IOException {
        out.append("[\n");
        firstTable = true;
    }

    @Override
    public void writePageStart(Appendable out, int page) throws IOException {

    }

    @Override
    public void writeTableStart(Appendable out, Table table) throws IOException {
        if (firstTable) {
            firstTable = false;
        } else {
            out.append(",\n");
        }
    }

    @Override
    public void writeTableTitle(Appendable out, String title) throws IOException {

    }

    @Override
    public void writeTableCaps(Appendable out, String caps) throws IOException {

    }

    @Override
    public void writeTableUnitText(Appendable out, String unitText) throws IOException {

    }

    @Override
    public void writeTableShoes(Appendable out, String shoes) throws IOException {

    }

    @Override
    public void writeTable(Appendable out, Table table) throws IOException {
    	out.append(JsonUtil.toString(table.toDocument(true), true));
    }

    @Override
    public void writeTableEnd(Appendable out, Table table) throws IOException {

    }

    @Override
    public void writePageEnd(Appendable out, int page) throws IOException {

    }

    @Override
    public void writeFileEnd(Appendable out, String path) throws IOException {
        out.append("]\n");
    }

}
