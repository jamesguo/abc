package com.abcft.pdfextract.core.table;

import com.abcft.pdfextract.core.util.NumberUtil;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.io.Closeable;
import java.io.IOException;

/**
 *
 *
 * Created by chzhong on 17-2-8.
 */
@Deprecated
public class TabularTableExtractor implements Closeable {


    private final PDDocument pdfDocument;
    private final TableExtractParameters parameters;

    public TabularTableExtractor(PDDocument pdfDocument) throws IOException {
        this.pdfDocument = pdfDocument;
        this.parameters = new TableExtractParameters.Builder().build();
    }


    protected TabularPage extractPage(Integer pageNumber) throws IOException {

        if (pageNumber > this.pdfDocument.getNumberOfPages() || pageNumber < 1) {
            throw new java.lang.IndexOutOfBoundsException(
                    "Page number does not exist");
        }

        return TabularUtils.createPage(pageNumber, this.pdfDocument.getPage(pageNumber - 1), parameters);
    }

    public PageIterator extract(Iterable<Integer> pages) {
        return new PageIterator(this, pages);
    }

    public PageIterator extract() {
        return extract(NumberUtil.range(1, this.pdfDocument.getNumberOfPages() + 1));
    }

    public TabularPage extract(int pageNumber) {
        return extract(NumberUtil.range(pageNumber, pageNumber + 1)).next();
    }

    public void close() throws IOException {
        this.pdfDocument.close();
    }
}
