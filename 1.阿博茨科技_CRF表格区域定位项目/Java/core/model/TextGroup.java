package com.abcft.pdfextract.core.model;

import java.util.List;

public interface TextGroup<E extends TextContainer> extends TextContainer {

    List<E> getElements();

    default String getText() {
        return getText(true);
    }

    String getText(boolean useLineReturns);
}
