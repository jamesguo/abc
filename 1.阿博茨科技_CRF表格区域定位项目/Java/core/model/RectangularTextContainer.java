package com.abcft.pdfextract.core.model;


import com.abcft.pdfextract.core.util.NumberUtil;

import java.util.List;

@SuppressWarnings("serial")
public abstract class RectangularTextContainer<E extends TextContainer>
        extends RectangularTextGroup<E> implements MutableTextGroup<E> {

    protected static final float TEXT_SEARCH_EPSILON = 2.f;

    public RectangularTextContainer(float left, float top, float width, float height) {
        super(left, top, width, height);
    }

    public RectangularTextContainer() {
    }

    public RectangularTextContainer<E> merge(RectangularTextContainer<E> other) {
        List<E> otherElements = other.getElements();
        if (null == otherElements || otherElements.isEmpty()) {
            return this;
        }
        this.getElements().addAll(otherElements);
        super.merge(other);
        return this;
    }

    @Override
    public <T extends MutableTextGroup<E>> T merge(T other) {
        return null;
    }
}
