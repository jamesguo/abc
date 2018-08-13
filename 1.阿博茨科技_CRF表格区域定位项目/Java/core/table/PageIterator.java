package com.abcft.pdfextract.core.table;

import java.io.IOException;
import java.util.Iterator;

@Deprecated
public class PageIterator implements Iterator<TabularPage> {

    private TabularTableExtractor oe;
    private Iterator<Integer> pageIndexIterator;

    public PageIterator(TabularTableExtractor oe, Iterable<Integer> pages) {
        this.oe = oe;
        this.pageIndexIterator = pages.iterator();
    }

    @Override
    public boolean hasNext() {
        return this.pageIndexIterator.hasNext();
    }

    @Override
    public TabularPage next() {
        TabularPage page = null;
        if (!this.hasNext()) {
            throw new IllegalStateException();
        }
        try {
            page = oe.extractPage(this.pageIndexIterator.next());
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        return page;
    }

    @Override
    public void remove() {
        throw new UnsupportedOperationException();

    }

}