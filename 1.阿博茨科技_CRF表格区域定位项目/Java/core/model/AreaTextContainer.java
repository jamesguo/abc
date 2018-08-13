package com.abcft.pdfextract.core.model;

import java.awt.*;

@SuppressWarnings("serial")
public abstract class AreaTextContainer<E extends TextContainer>
        extends AreaTextGroup<E> implements MutableTextGroup<E> {

    public AreaTextContainer() {
    }

    public AreaTextContainer(AreaTextContainer container) {
        super(container);
    }

    public AreaTextContainer(Shape shape) {
        super(shape);
    }

}
