package com.abcft.pdfextract.core.table.writers;

import com.abcft.pdfextract.core.ExtractorUtil;
import com.abcft.pdfextract.core.table.Table;

import java.io.IOException;

/**
 * Created by dhu on 2017/11/27.
 */
abstract class PlainTextWriter implements Writer {

    @Override
    public void writeFileStart(Appendable out, String path) throws IOException {

    }

    @Override
    public void writePageStart(Appendable out, int page) throws IOException {
        out.append("Page ")
                .append(String.valueOf(page))
                .append(ExtractorUtil.LINE_SEPARATOR);
    }

    @Override
    public void writeTableTitle(Appendable out, String title) throws IOException {
        out.append(title).append(ExtractorUtil.LINE_SEPARATOR);
    }

    @Override
    public void writeTableCaps(Appendable out, String caps) {

    }

    @Override
    public void writeTableShoes(Appendable out, String shoes) {

    }

    @Override
    public void writeTableUnitText(Appendable out, String unitText) throws IOException {
        out.append(unitText).append(ExtractorUtil.LINE_SEPARATOR);
    }

    @Override
    public void writeTableStart(Appendable out, Table table) throws IOException {

    }

    @Override
    public void writeTableEnd(Appendable out, Table table) throws IOException {
        out.append(ExtractorUtil.LINE_SEPARATOR);
    }

    @Override
    public void writePageEnd(Appendable out, int page) throws IOException {
        out.append(ExtractorUtil.LINE_SEPARATOR);
    }

    @Override
    public void writeFileEnd(Appendable out, String path) throws IOException {

    }
}
