package com.abcft.pdfextract.core.model;

@SuppressWarnings("serial")
public abstract class RectangularTextGroup<E extends TextContainer>
        extends Rectangle implements TextGroup<E> {

    protected static final float TEXT_SEARCH_EPSILON = 2.f;

    public RectangularTextGroup(float left, float top, float width, float height) {
        super(left, top, width, height);
    }

    public RectangularTextGroup() {
    }

    public String toString() {
        StringBuilder sb = new StringBuilder();
        String s = super.toString();
        String text = getText();
        sb.append(s.substring(0, s.length() - 1));
        sb.append(String.format("; text=%s]", text == null ? "<null>" : "\"" + text + "\""));
        return sb.toString();
    }

}
