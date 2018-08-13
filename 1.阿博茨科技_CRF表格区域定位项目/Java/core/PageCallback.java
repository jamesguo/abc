package com.abcft.pdfextract.core;

import com.abcft.pdfextract.core.chart.Chart;
import com.abcft.pdfextract.core.content.Page;
import com.abcft.pdfextract.core.table.Table;
import org.apache.pdfbox.pdmodel.PDPage;

import java.util.List;

public interface PageCallback {
    void onPageFinished(PdfExtractContext.PageContext pageContext, PDPage pdPage, int pageIndex,
                        Page page, List<Chart> charts, List<Table> tables);
}
