package com.abcft.pdfextract.core.office;

import java.awt.*;
import java.util.Objects;

public class FontInfo {
    enum Style {
        NORMAL(0x0),
        BOLD(0x1),
        ITALIC(0x2),
        UNDERLINED(0x4),
        STRIKE_THROUGH(0x8);

        private final int style;

        Style(int style) {
            this.style = style;
        }

        public int getStyle() {
            return style;
        }
    }

    private final String fontFamily;
    private final float fontSize;
    private Color fontColor;
    private int fontStyle;

    FontInfo(String fontFamily, float fontSize, Color fontColor, int fontStyle) {
        this.fontFamily = fontFamily;
        this.fontSize = fontSize;
        this.fontColor = fontColor;
        this.fontStyle = fontStyle;
    }

    FontInfo(String fontFamily, float fontSize) {
        this(fontFamily, fontSize, Color.BLACK, Style.NORMAL.getStyle());
    }

    @Override
    public String toString() {
        return String.format("[FontInfo (%s,%f,%s,%d)]",
            this.fontFamily, this.fontSize, this.fontColor.toString(), this.fontStyle);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FontInfo fontInfo = (FontInfo) o;
        return Float.compare(fontInfo.fontSize, fontSize) == 0 &&
            fontStyle == fontInfo.fontStyle &&
            Objects.equals(fontFamily, fontInfo.fontFamily) &&
            Objects.equals(fontColor, fontInfo.fontColor);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fontFamily, fontSize, fontColor, fontStyle);
    }

    public Color getFontColor() {
        return fontColor;
    }

    public String getFontFamily() {
        return fontFamily;
    }

    public float getFontSize() {
        return fontSize;
    }

    public int getFontStyle() {
        return fontStyle;
    }

    public void setFontColor(Color fontColor) {
        this.fontColor = fontColor;
    }

    public void addFontStyle(Style style) {
        this.fontStyle |= style.getStyle();
    }

    public void removeFontStyle(Style style) {
        this.fontStyle &= ~style.getStyle();
    }

    public void resetFontStyle() {
        this.fontStyle = Style.NORMAL.getStyle();
    }
}
