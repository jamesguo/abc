package com.abcft.pdfextract.core.table.extractors.gridless;

import com.abcft.pdfextract.core.table.Page;
import com.abcft.pdfextract.core.table.TableExtractParameters;

@Deprecated
abstract class AbstractGridlessFeature implements GridlessFeature {

    PageParam pagePara;

    void ensurePageParam(Page page, TableExtractParameters params) {
        if (null == pagePara) {
            pagePara = new PageParam(page, params);
        }
    }

    void checkPageParam() {
        if (null == pagePara) {
            throw new IllegalStateException("Need to call prepare(Page) first.");
        }
    }


    @Override
    public void prepare(Page page, TableExtractParameters params) {
        ensurePageParam(page, params);
    }

    @Override
    public void reset() {
        pagePara = null;
    }
}
