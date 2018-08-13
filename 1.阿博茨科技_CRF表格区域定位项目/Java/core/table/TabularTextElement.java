package com.abcft.pdfextract.core.table;

import com.abcft.pdfextract.core.model.FontUtils;
import com.abcft.pdfextract.core.model.Rectangle;
import com.abcft.pdfextract.core.model.TextContainer;
import com.abcft.pdfextract.core.model.TextElement;
import com.abcft.pdfextract.util.ClosureInt;
import org.apache.pdfbox.pdmodel.font.PDFont;

import static com.abcft.pdfextract.core.table.TableUtils.UNUSED;

@SuppressWarnings("serial")
@Deprecated
public class TabularTextElement extends Rectangle implements TextContainer {

    final String text;
    final String fontName;
    final int textBlockId;
    float fontSize;
    float widthOfSpace, dir;

    public TabularTextElement(int textBlockId, float x, float y, float width, float height,
                              PDFont font, float fontSize, String c, float widthOfSpace) {
        this(textBlockId, x, y, width, height, FontUtils.getFontName(font, UNUSED), fontSize, c, widthOfSpace, 0f);
    }

    public TabularTextElement(int textBlockId, float x, float y, float width, float height,
                              PDFont font, float fontSize, String c, float widthOfSpace, float dir) {
        this(textBlockId, x, y, width, height, FontUtils.getFontName(font, UNUSED), fontSize, c, widthOfSpace, 0f);
    }
    public TabularTextElement(int textBlockId, float x, float y, float width, float height,
                              String fontName, float fontSize, String c, float widthOfSpace) {
        this(textBlockId, x, y, width, height, fontName, fontSize, c, widthOfSpace, 0f);
    }

    public TabularTextElement(int textBlockId, float x, float y, float width, float height,
                              String fontName, float fontSize, String c, float widthOfSpace, float dir) {
        this.textBlockId = textBlockId;
        this.setRect(x, y, width, height);
        this.text = c;
        this.widthOfSpace = widthOfSpace;
        this.fontSize = fontSize;
        this.fontName = fontName;
        this.dir = dir;
    }

    public int getTextBlockId() {
        return textBlockId;
    }

    public String getText() {
        return text;
    }

    public float getDirection() {
        return dir;
    }

    public float getWidthOfSpace() {
        return widthOfSpace;
    }

    public String getFontName() {
        return fontName;
    }

    public float getFontSize() {
        return fontSize;
    }

    public String toString() {
        StringBuilder sb = new StringBuilder();
        String s = super.toString();
        Class<?> clazz = getClass();
        s = s.replace(clazz.getName(), clazz.getSimpleName());
        sb.append(s.substring(0, s.length() - 1));
        sb.append(String.format(",text=\"%s\"]\n", this.getText()));
 //       sb.append(String.format("text=\"%s\", font = %s]\n", this.getText(), this.getFont().getName()));
        return sb.toString();
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = super.hashCode();
        result = prime * result + java.lang.Float.floatToIntBits(dir);
        result = prime * result + fontName.hashCode();
        result = prime * result + java.lang.Float.floatToIntBits(fontSize);
        result = prime * result + ((text == null) ? 0 : text.hashCode());
        result = prime * result + java.lang.Float.floatToIntBits(widthOfSpace);
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (!super.equals(obj))
            return false;
        if (getClass() != obj.getClass())
            return false;
        TabularTextElement other = (TabularTextElement) obj;
        if (java.lang.Float.floatToIntBits(dir) != java.lang.Float
                .floatToIntBits(other.dir))
            return false;
        if (fontName == null) {
            if (other.fontName != null)
                return false;
        } else if (!fontName.equals(other.fontName))
            return false;
        if (java.lang.Float.floatToIntBits(fontSize) != java.lang.Float
                .floatToIntBits(other.fontSize))
            return false;
        if (text == null) {
            if (other.text != null)
                return false;
        } else if (!text.equals(other.text))
            return false;
        return java.lang.Float.floatToIntBits(widthOfSpace) == java.lang.Float
                .floatToIntBits(other.widthOfSpace);
    }

}
