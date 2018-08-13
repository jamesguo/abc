package com.abcft.pdfextract.core.model;

import java.util.List;

public interface MutableTextGroup<E extends TextContainer>
        extends TextGroup<E> {

    void addElement(E elem);

    default void addElements(List<E> elements) {
        for (E elem : elements) {
            addElement(elem);
        }
    }

    <T extends MutableTextGroup<E>> T merge(T other);
}
