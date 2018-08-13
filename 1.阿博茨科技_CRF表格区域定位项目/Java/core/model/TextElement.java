package com.abcft.pdfextract.core.model;

import com.abcft.pdfextract.util.FloatUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * 表示单个字符对象
 * Created by dhu on 2017/11/7.
 */
public class TextElement extends Rectangle implements TextContainer, Cloneable {

    private static final Map<Integer, String> DIACRITICS = createDiacritics();

    // Adds non-decomposing diacritics to the hash with their related combining character.
    // These are values that the unicode spec claims are equivalent but are not mapped in the form
    // NFKC normalization method. Determined by going through the Combining Diacritical Marks
    // section of the Unicode spec and identifying which characters are not  mapped to by the
    // normalization.
    @SuppressWarnings("Duplicates")
    private static Map<Integer, String> createDiacritics() {
        Map<Integer, String> map = new HashMap<>(31);
        map.put(0x0060, "\u0300");
        map.put(0x02CB, "\u0300");
        map.put(0x0027, "\u0301");
        map.put(0x02B9, "\u0301");
        map.put(0x02CA, "\u0301");
        map.put(0x005e, "\u0302");
        map.put(0x02C6, "\u0302");
        map.put(0x007E, "\u0303");
        map.put(0x02C9, "\u0304");
        map.put(0x00B0, "\u030A");
        map.put(0x02BA, "\u030B");
        map.put(0x02C7, "\u030C");
        map.put(0x02C8, "\u030D");
        map.put(0x0022, "\u030E");
        map.put(0x02BB, "\u0312");
        map.put(0x02BC, "\u0313");
        map.put(0x0486, "\u0313");
        map.put(0x055A, "\u0313");
        map.put(0x02BD, "\u0314");
        map.put(0x0485, "\u0314");
        map.put(0x0559, "\u0314");
        map.put(0x02D4, "\u031D");
        map.put(0x02D5, "\u031E");
        map.put(0x02D6, "\u031F");
        map.put(0x02D7, "\u0320");
        map.put(0x02B2, "\u0321");
        map.put(0x02CC, "\u0329");
        map.put(0x02B7, "\u032B");
        map.put(0x02CD, "\u0331");
        map.put(0x005F, "\u0332");
        map.put(0x204E, "\u0359");
        return map;
    }

    // 样式
    public static final int TEXT_STYLE_NORMAL = 0;
    public static final int TEXT_STYLE_BOLD = 1; // 粗体
    public static final int TEXT_STYLE_ITALIC = 2; // 斜体
    public static final int TEXT_STYLE_UNDERLINE = 4; // 下划线
    public static final int TEXT_STYLE_LINE_THROUGH = 8; // 删除线

    private static final Logger LOG = LogManager.getLogger();

    private final float widthOfSpace; // width of a space, in display units
    private final int[] charCodes; // internal PDF character codes
    private final String fontName;
    private final float textWidth;
    private final float textHeight;
    private int fontStyleSize;
    private final int rotate;
    private TextDirection direction;

    private final Color color;
    private int textStyle;
    private Rectangle2D visibleBBox;  // 实际可见的边界框

    // mutable
    private float[] widths;
    private String unicode;
    private boolean hidden = false;
    private boolean mocked = false;

    private int groupIndex = -1;

    /**
     * Constructor.
     * @param bounds text bounds in display units
     * @param spaceWidth The width of the space character. (in display units)
     * @param unicode The string of Unicode characters to be displayed.
     * @param charCodes An array of the internal PDF character codes for the glyphs in this text.
     * @param fontName
     * @param rotate 文字旋转的角度
     * @param color
     */
    public TextElement(Rectangle2D visibleBBox, Rectangle2D bounds, Shape shape, float spaceWidth, String unicode, int[] charCodes,
                       String fontName, float fontSize, int rotate, Color color, int textStyle) {
        super((float) bounds.getX(), (float) bounds.getY(), (float) bounds.getWidth(), (float) bounds.getHeight());
        this.visibleBBox = visibleBBox;
        this.unicode = unicode;
        this.charCodes = charCodes;
        this.fontName = fontName;
        this.rotate = rotate;
        this.widths = new float[] {(float) bounds.getWidth()};

        if (rotate == 0 || rotate == 180) {
            textWidth = (float) bounds.getWidth();
            textHeight = (float) bounds.getHeight();
        } else if (rotate == 90 || rotate == -90 || rotate == 270) {
            textHeight = (float) bounds.getWidth();
            textWidth = (float) bounds.getHeight();
            this.visibleBBox.setFrame(visibleBBox.getX(), visibleBBox.getY(), visibleBBox.getHeight(), visibleBBox.getWidth());
        } else {
            Rectangle2D textBounds = AffineTransform.getRotateInstance(Math.toRadians(-rotate))
                    .createTransformedShape(shape).getBounds2D();
            textWidth = (float) textBounds.getWidth();
            textHeight = (float) textBounds.getHeight();
            this.visibleBBox = AffineTransform.getRotateInstance(Math.toRadians(-rotate))
                    .createTransformedShape(visibleBBox).getBounds2D();
        }

        if (fontSize < 2.0f) {
            // 设置的号太小，推测是通过缩放手段变大的，因此采用字符高度计算
            // 偏大一些的字号（考虑到高度的误差）
            float h = FloatUtils.round(textHeight, 1);
            this.fontStyleSize = (int) Math.round(Math.ceil(h) / 1.2);
        } else {
            this.fontStyleSize = Math.round(fontSize);
        }

        this.widthOfSpace = spaceWidth;

        // 样式
        this.color = color;
        this.textStyle = textStyle;
    }

    public TextElement(TextElement element) {
        super((float) element.getX(), (float) element.getY(), (float) element.getWidth(), (float) element.getHeight());
        this.visibleBBox = element.getVisibleBBox();
        this.unicode = element.getUnicode();
        this.charCodes = element.getCharacterCodes();
        this.fontName = element.getFontName();
        this.rotate = element.getRotate();
        this.widthOfSpace = element.getWidthOfSpace();
        this.textWidth = element.getTextWidth();
        this.textHeight = element.getTextHeight();
        this.fontStyleSize = element.getFontStyleSize();
        this.direction = element.getDirection();
        this.color = element.getColor();
        this.textStyle = element.getTextStyle();
    }

    /**
     * Return the string of characters stored in this object. The length can be different than the
     * CharacterCodes length e.g. if ligatures are used ("fi", "fl", "ffl") where one glyph
     * represents several unicode characters.
     *
     * @return The string on the screen.
     */
    public String getUnicode() {
        return unicode;
    }

    void setUnicode(String unicode) {
        this.unicode = unicode;
    }

    /**
     * Return the internal PDF character codes of the glyphs in this text.
     *
     * @return an array of internal PDF character codes
     */
    public int[] getCharacterCodes() {
        return charCodes;
    }

    /**
     * Return the direction/orientation of the string in this object based on its text matrix.
     *
     * @return The direction of the text (0, 90, 180, or 270)
     */
    public TextDirection getDirection() {
        if (direction == null) {
            switch (rotate) {
                case 90:
                    direction = TextDirection.VERTICAL_DOWN;
                    break;
                case 270:
                case -90:
                    direction = TextDirection.VERTICAL_UP;
                    break;
                case 0:
                    direction = TextDirection.UNKNOWN;
                    break;
                default:
                    direction = TextDirection.ROTATED;
                    break;
            }
        }
        return direction;
    }

    public void setDirection(TextDirection direction) {
        this.direction = direction;
    }

    public int getRotate() {
        return rotate;
    }

    public int getFontStyleSize() {
        return fontStyleSize;
    }

    public Rectangle2D getVisibleBBox() {
        return visibleBBox;
    }

    public double getVisibleMinX() {
        return visibleBBox.getMinX();
    }

    public double getVisibleMinY() {
        return visibleBBox.getMinY();
    }

    public double getVisibleMaxX() {
        return visibleBBox.getMaxX();
    }

    public double getVisibleMaxY() {
        return visibleBBox.getMaxY();
    }

    public double getVisibleWidth() {
        return visibleBBox.getWidth();
    }

    public double getVisibleHeight() {
        return visibleBBox.getHeight();
    }

    /**
     * This will get the font size.
     *
     * @return The font size.
     */
    public float getFontSize() {
        return fontStyleSize;
    }

    public String getFontName() {
        return fontName;
    }

    public int getTextStyle() {
        return textStyle;
    }

    public Color getColor() {
        return color;
    }

    /**
     * This will get the width of a space character. This is useful for some algorithms such as the
     * text stripper, that need to know the width of a space character.
     *
     * @return The width of a space character.
     */
    public float getWidthOfSpace() {
        return widthOfSpace;
    }

    /**
     * Get the widths of each individual character.
     *
     * @return An array that has the same length as the CharacterCodes array.
     */
    public float[] getIndividualWidths() {
        return widths;
    }

    /**
     * Merge a single character TextElement into the current object. This is to be used only for
     * cases where we have a diacritic that overlaps an existing TextElement. In a graphical
     * display, we could overlay them, but for text extraction we need to merge them. Use the
     * contains() method to test if two objects overlap.
     *
     * @param diacritic TextElement to merge into the current TextElement.
     */
    public void mergeDiacritic(TextElement diacritic) {
        if (diacritic.getUnicode().length() > 1) {
            return;
        }

        float diacXStart = (float) diacritic.getBounds2D().getMinX();
        float diacXEnd = diacXStart + diacritic.widths[0];

        float currCharXStart = (float) getBounds2D().getMinX();

        int strLen = unicode.length();
        boolean wasAdded = false;

        for (int i = 0; i < strLen && !wasAdded; i++) {
            if (i >= widths.length) {
                LOG.info("diacritic " + diacritic.getUnicode() + " on ligature " + unicode +
                        " is not supported yet and is ignored (PDFBOX-2831)");
                break;
            }
            float currCharXEnd = currCharXStart + widths[i];

            // this is the case where there is an overlap of the diacritic character with the
            // current character and the previous character. If no previous character, just append
            // the diacritic after the current one
            if (diacXStart < currCharXStart && diacXEnd <= currCharXEnd) {
                if (i == 0) {
                    insertDiacritic(i, diacritic);
                } else {
                    float distanceOverlapping1 = diacXEnd - currCharXStart;
                    float percentage1 = distanceOverlapping1 / widths[i];

                    float distanceOverlapping2 = currCharXStart - diacXStart;
                    float percentage2 = distanceOverlapping2 / widths[i - 1];

                    if (percentage1 >= percentage2) {
                        insertDiacritic(i, diacritic);
                    } else {
                        insertDiacritic(i - 1, diacritic);
                    }
                }
                wasAdded = true;
            }
            // diacritic completely covers this character and therefore we assume that this is the
            // character the diacritic belongs to
            else if (diacXStart < currCharXStart && diacXEnd > currCharXEnd) {
                insertDiacritic(i, diacritic);
                wasAdded = true;
            }
            // otherwise, The diacritic modifies this character because its completely
            // contained by the character width
            else if (diacXStart >= currCharXStart && diacXEnd <= currCharXEnd) {
                insertDiacritic(i, diacritic);
                wasAdded = true;
            }
            // last character in the TextElement so we add diacritic to the end
            else if (diacXStart >= currCharXStart && diacXEnd > currCharXEnd && i == strLen - 1) {
                insertDiacritic(i, diacritic);
                wasAdded = true;
            }

            // couldn't find anything useful so we go to the next character in the TextElement
            currCharXStart += widths[i];
        }
    }

    /**
     * Inserts the diacritic TextElement to the str of this TextElement and updates the widths
     * array to include the extra character width.
     *
     * @param i         current character
     * @param diacritic The diacritic TextElement
     */
    private void insertDiacritic(int i, TextElement diacritic) {
        StringBuilder sb = new StringBuilder();
        sb.append(unicode.substring(0, i));

        float[] widths2 = new float[widths.length + 1];
        System.arraycopy(widths, 0, widths2, 0, i);

        // Unicode combining diacritics always go after the base character, regardless of whether
        // the string is in presentation order or logical order
        sb.append(unicode.charAt(i));
        widths2[i] = widths[i];
        sb.append(combineDiacritic(diacritic.getUnicode()));
        widths2[i + 1] = 0;

        // get the rest of the string
        sb.append(unicode.substring(i + 1, unicode.length()));
        System.arraycopy(widths, i + 1, widths2, i + 2, widths.length - i - 1);

        unicode = sb.toString();
        widths = widths2;
        Rectangle2D.union(this, diacritic, this);
    }

    /**
     * Combine the diacritic, for example, convert non-combining diacritic characters to their
     * combining counterparts.
     *
     * @param str String to normalize
     * @return Normalized string
     */
    private String combineDiacritic(String str) {
        // Unicode contains special combining forms of the diacritic characters which we want to use
        int codePoint = str.codePointAt(0);

        // convert the characters not defined in the Unicode spec
        if (DIACRITICS.containsKey(codePoint)) {
            return DIACRITICS.get(codePoint);
        } else {
            return Normalizer.normalize(str, Normalizer.Form.NFKC).trim();
        }
    }

    /**
     * @return True if the current character is a diacritic char. example: Ã
     */
    public boolean isDiacritic() {
        String text = this.getUnicode();
        if (text.length() != 1) {
            return false;
        }
        if ("ー".equals(text)) {
            // PDFBOX-3833: ー is not a real diacritic like ¨ or ˆ, it just changes the
            // pronunciation of the previous sound, and is printed after the previous glyph
            // http://www.japanesewithanime.com/2017/04/prolonged-sound-mark.html
            // Ignoring it as diacritic avoids trouble if it slightly overlaps with the next glyph.
            return false;
        }
        int type = Character.getType(text.charAt(0));
        return type == Character.NON_SPACING_MARK ||
                type == Character.MODIFIER_SYMBOL ||
                type == Character.MODIFIER_LETTER;

    }


    /**
     * Determine whether current character is a whitespace character.
     *
     * @return true if current character is a whitespace character.
     */
    public boolean isWhitespace() {
        String text = this.getUnicode();
        return text.length() == 1
                && Character.isWhitespace(text.charAt(0));
    }

    /**
     * Show the string data for this text position.
     *
     * @return A human readable form of this object.
     */
    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder(super.toString());
        builder.delete(builder.length() - 1, builder.length());
        builder.append("; ch=\"")
                .append(getUnicode())
                .append('"');
        if (isBold() || isItalic() || isUnderline() || isLineThrough()) {
            builder.append(", style: ");
            if (isBold()) {
                builder.append('B');
            }
            if (isItalic()) {
                builder.append('I');
            }
            if (isUnderline()) {
                builder.append('U');
            }
            if (isLineThrough()) {
                builder.append('D');
            }
        }
        if (isDeleted() || isHidden()) {
            builder.append(", state: ");
            if (isDeleted()) {
                builder.append("Deleted");
            }
            if (isHidden()) {
                builder.append(" Hidden");
            }
        }
        if (isMocked()) {
            builder.append(", mocked");
        }
        builder.append(']');
        return builder.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TextElement)) {
            return false;
        }

        TextElement that = (TextElement) o;

        if (java.lang.Float.compare(that.widthOfSpace, widthOfSpace) != 0) {
            return false;
        }
        if (fontStyleSize != that.fontStyleSize) {
            return false;
        }
        if (Integer.compare(that.rotate, rotate) != 0) {
            return false;
        }
        if (!super.equals(that)) {
            return false;
        }
        if (!Arrays.equals(charCodes, that.charCodes)) {
            return false;
        }
        if (!StringUtils.equals(fontName, that.fontName)) {
            return false;
        }
        if (!Arrays.equals(widths, that.widths)) {
            return false;
        }
        if (textStyle != that.textStyle) {
            return false;
        }
        return unicode != null ? unicode.equals(that.unicode) : that.unicode == null;

    }

    @Override
    public int hashCode() {
        int result = super.hashCode();
        result = 31 * result + java.lang.Float.floatToIntBits(widthOfSpace);
        result = 31 * result + Arrays.hashCode(charCodes);
        result = 31 * result + (fontName != null ? fontName.hashCode() : 0);
        result = 31 * result + fontStyleSize;
        result = 31 * result + rotate;
        result = 31 * result + textStyle;
        return result;
    }

    /**
     * 获取字符的宽度, 与旋转角度无关
     * @return 宽度
     */
    public float getTextWidth() {
        return textWidth;
    }

    /**
     * 获取字符的高度, 与旋转角度无关
     * @return 高度
     */
    public float getTextHeight() {
        return textHeight;
    }

    public void setHidden(boolean hidden) {
        this.hidden = hidden;
    }

    public boolean isHidden() {
        return hidden;
    }

    public void setMocked(boolean mocked) {
        this.mocked = mocked;
    }

    public boolean isMocked() {
        return mocked;
    }

    public boolean isBold() {
        return (textStyle & TEXT_STYLE_BOLD) == TEXT_STYLE_BOLD;
    }

    public boolean isItalic() {
        return (textStyle & TEXT_STYLE_ITALIC) == TEXT_STYLE_ITALIC;
    }

    public boolean isUnderline() {
        return (textStyle & TEXT_STYLE_UNDERLINE) == TEXT_STYLE_UNDERLINE;
    }

    public boolean isLineThrough() {
        return (textStyle & TEXT_STYLE_LINE_THROUGH) == TEXT_STYLE_LINE_THROUGH;
    }

    public void setUnderline(boolean hasUnderLine) {
        if (hasUnderLine) {
            this.textStyle |= TEXT_STYLE_UNDERLINE;
        } else {
            this.textStyle ^= TEXT_STYLE_UNDERLINE;
        }
    }


    public void setLineThrough(boolean hasDeleteLine) {
        if (hasDeleteLine) {
            this.textStyle |= TEXT_STYLE_LINE_THROUGH;
        } else {
            this.textStyle ^= TEXT_STYLE_LINE_THROUGH;
        }
    }

    public void setGroupIndex(int groupIndex) {
        this.groupIndex = groupIndex;
    }

    public int getGroupIndex() {
        return groupIndex;
    }

    /**
     * 判断是否是阴影文字, 有些文字的阴影效果是通过把字画两次, 位置稍有偏差, 第一次画阴影, 第二次画正常的字
     * @param element
     * @return
     */
    public boolean isShadowOf(TextElement element) {
        if (element == this || isDeleted() || !StringUtils.equals(getUnicode(), element.getUnicode())) {
            return false;
        }
        Rectangle2D intersection = this.createIntersection(element);
        if (intersection.isEmpty()) {
            return false;
        }
        double overlapPercent = intersection.getWidth() * intersection.getHeight() /
                (getWidth() * getHeight());
        return overlapPercent > 0.8;
    }

    public boolean hasSameStyle(TextElement textElement) {
        return StringUtils.equals(fontName, textElement.fontName)
                && fontStyleSize == textElement.fontStyleSize
                && textStyle == textElement.textStyle
                && color.equals(textElement.color);
    }

    @Override
    public String getText() {
        return getUnicode();
    }
}
