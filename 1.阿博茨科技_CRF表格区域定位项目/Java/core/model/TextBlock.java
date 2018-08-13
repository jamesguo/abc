package com.abcft.pdfextract.core.model;

import com.abcft.pdfextract.core.content.ParagraphMerger;
import com.abcft.pdfextract.core.content.TextChunkMerger;
import com.abcft.pdfextract.core.content.TextSplitStrategy;
import com.abcft.pdfextract.core.content.TextUtils;
import com.abcft.pdfextract.core.table.ContentGroupPage;
import com.abcft.pdfextract.core.util.GraphicsUtil;
import com.abcft.pdfextract.core.util.TrainDataWriter;
import com.abcft.pdfextract.util.FloatUtils;
import com.abcft.pdfextract.util.JsonUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;

import java.awt.geom.Area;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 表示文本块, 可以是多行的
 * Created by dhu on 2017/11/14.
 */
public class TextBlock extends AreaTextContainer<TextChunk> {

    private static final Pattern DIVIDED_WORD_REGEX = Pattern.compile(".*\\b[A-Za-z]+-$");
    private static final Pattern HEADING_REGEX = Pattern.compile("^H(\\d+)$", Pattern.CASE_INSENSITIVE);

    private List<TextChunk> textChunkList;
    private String text;
    private TextDirection direction;
    private int columnCount = 0;
    private boolean mergedFlag = false;
    private float avgCharWidth = 0;
    private float avgCharHeight = 0;
    private Rectangle2D textBlockBound = null;
    private Rectangle2D visibleBBox = new Rectangle2D.Double();
    private boolean adjustSpaces;   // 是否为分散对齐的文本块
    private boolean adjustText = true;     // 是否对加入的文本块进行处理

    private final TextClasses classes;

    private TrainDataWriter.LineTag lastLineTag = TrainDataWriter.LineTag.OTHER;


    public TextBlock() {
        this((TextClasses)null);
    }

    public TextBlock(TextChunk textChunk) {
        this(textChunk, null);
    }

    public TextBlock(List<TextChunk> textChunks) {
        this(textChunks, null);
    }

    public TextBlock(TextBlock textBlock) {
        this(textBlock, null);
    }

    public TextBlock(TextClasses classes) {
        this.textChunkList = new ArrayList<>();
        this.text = "";
        this.classes = classes != null ? classes : new TextClasses();
        this.direction = TextDirection.UNKNOWN;
    }

    private TextBlock(List<TextChunk> textChunks, TextClasses classes) {
        this(classes);
        adjustSpaces = true;
        for (TextChunk textChunk : textChunks) {
            addElement(textChunk);
        }
    }

    public TextBlock(TextChunk textChunk, TextClasses classes) {
        super(textChunk);
        this.textChunkList = new ArrayList<>();
        this.textChunkList.add(textChunk);
        this.text = textChunk.getText();
        this.columnCount = TextUtils.getColumnCount(textChunk.getText());
        this.textBlockBound = textChunk.getBounds2D();
        if (!textChunk.getVisibleBBox().isEmpty()) {
            visibleBBox.setRect(textChunk.getVisibleBBox());
        }
        this.classes = classes != null ? classes : new TextClasses();
        this.direction = textChunk.getDirection();
    }

    public TextClasses getClasses() {
        return classes;
    }

    private TextBlock(TextBlock textBlock, TextClasses classes) {
        super(textBlock);
        this.textChunkList = new ArrayList<>(textBlock.textChunkList);
        this.text = textBlock.text;
        this.columnCount = textBlock.columnCount;
        this.adjustSpaces = textBlock.adjustSpaces;
        this.adjustText = textBlock.adjustText;
        this.visibleBBox.setRect(textBlock.visibleBBox);
        this.avgCharHeight = textBlock.avgCharHeight;
        this.avgCharWidth = textBlock.avgCharWidth;
        this.mergedFlag = textBlock.mergedFlag;
        if (textBlock.textBlockBound != null) {
            this.textBlockBound = new Rectangle2D.Double();
            this.textBlockBound.setFrame(textBlock.textBlockBound);
        }
        this.classes = classes != null ? classes : textBlock.classes;
        this.direction = textBlock.getDirection();

    }

    public TextBlock linkTo(TextBlock textBlock) {
        textBlock.classes.addClasses(this.classes);
        return new TextBlock(this, textBlock.classes);
    }

    public TextBlock unlink() {
        TextClasses newClasses = new TextClasses();
        newClasses.addClasses(this.classes);
        return new TextBlock(this, newClasses);
    }

    @Override
    public <T extends MutableTextGroup<TextChunk>> T merge(T other) {
        throw new UnsupportedOperationException();
    }

    public void addElement(TextChunk textChunk) {
        if (textChunk == null || textChunk.isEmpty()) {
            return;
        }

        // 增加第一个子元素
        if (textChunkList != null && textChunkList.isEmpty()) {
            textChunkList.add(textChunk);
            text = textChunk.getText();
            columnCount = TextUtils.getColumnCount(textChunk.getText());
            addArea(textChunk);
            this.textBlockBound = textChunk.getBounds2D();
            if (!textChunk.getVisibleBBox().isEmpty()) {
                visibleBBox.setRect(textChunk.getVisibleBBox());
            }
            this.direction = textChunk.getDirection();
            return;
        }

        // 增加非第一个元素的公共操作
        if (textChunk.getDirection() != TextDirection.UNKNOWN && textChunk.getDirection() != this.direction) {
            this.direction = TextDirection.UNKNOWN;
        }

        if (!adjustText) {
            textChunkList.add(textChunk);
            text = text + textChunk.getText();
            addArea(textChunk);
            this.textBlockBound = this.textBlockBound.createUnion(textChunk.getBounds2D());
            if (!textChunk.getVisibleBBox().isEmpty()) {
                if (visibleBBox.isEmpty()) {
                    visibleBBox.setRect(textChunk.getVisibleBBox());
                } else {
                    Rectangle2D.union(visibleBBox, textChunk.getVisibleBBox(), visibleBBox);
                }
            }
            return;
        }

        // 合并文字
        // 中文的话不需要补空格
        String text2 = textChunk.getText();
        boolean insertBefore = false;
        if (inOneLine(textChunk)) {
            // 有些情况下, 同一行的文字并不是从左往右出现的, 有可能颠倒
            if (textChunk.getMinX() < getLastTextChunk().getMinX()) {
                insertBefore = true;
                int numberOfSpace = TextChunk.computeSpace(textChunk.getLastElement(), getLastTextChunk().getFirstElement());
                if (numberOfSpace > 0) {
                    if (adjustSpaces) {
                        // 分散对齐情况下，无论距离多远都只保留一个空格
                        numberOfSpace = 1;
                    }
                    text = StringUtils.repeat(TextChunk.SPACE, numberOfSpace) + text;
                    textChunk.addMockSpaceToEnd(numberOfSpace);
                    if (numberOfSpace >= 3) {
                        columnCount++;
                    }
                }
            } else {
                int numberOfSpace = TextChunk.computeSpace(getLastTextChunk().getLastElement(), textChunk.getFirstElement());
                if (numberOfSpace > 0) {
                    if (adjustSpaces) {
                        // 分散对齐情况下，无论距离多远都只保留一个空格
                        numberOfSpace = 1;
                    }
                    text = text + StringUtils.repeat(TextChunk.SPACE, numberOfSpace);
                    getLastTextChunk().addMockSpaceToEnd(numberOfSpace);
                    if (numberOfSpace >= 3) {
                        columnCount++;
                    }
                }
            }
        } else {
            if (DIVIDED_WORD_REGEX.matcher(text).matches()) {
                // 同一个词被分成两部分的话需要合并起来
                // 删除最后的连字符-
                getLastTextChunk().getLastElement().markDeleted();
                getLastTextChunk().rebuildText();
                text = text.substring(0, text.length()-1);
            } else if (!com.abcft.pdfextract.util.TextUtils.containsCJK(text)
                    && !com.abcft.pdfextract.util.TextUtils.containsCJK(text2)) {
                // 如果是多行, 中文之间不需要补空格
                // 如果已经有空格了, 就不添加
                if (!StringUtils.endsWith(text, " ") && !StringUtils.startsWith(textChunk.getText(), " ")) {
                    text = text + TextChunk.SPACE;
                    getLastTextChunk().addMockSpaceToEnd();
                }
            }
        }
        if (insertBefore) {
            text = text2 + text;
            textChunkList.add(0, textChunk);
        } else {
            text = text + text2;
            textChunkList.add(textChunk);
        }

        addArea(textChunk);
        this.textBlockBound = this.textBlockBound.createUnion(textChunk.getBounds2D());
        if (!textChunk.getVisibleBBox().isEmpty()) {
            if (visibleBBox.isEmpty()) {
                visibleBBox.setRect(textChunk.getVisibleBBox());
            } else {
                Rectangle2D.union(visibleBBox, textChunk.getVisibleBBox(), visibleBBox);
            }
        }
    }

    public TextDirection getDirection() {
        return direction;
    }

    public void setDirection(TextDirection direction) {
        this.direction = direction;
        textChunkList.forEach(textChunk -> textChunk.setDirection(direction));
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

    public int getSize() {
        return textChunkList.size();
    }

    public List<TextChunk> getElements() {
        return textChunkList;
    }

    public List<TextElement> getValidTextElements() {
        return this.textChunkList.stream().flatMap(tc -> tc.getElements().stream().filter(te -> StringUtils
                .isNotBlank(te.getText()))).collect(Collectors.toList());
    }

    public TextChunk getFirstTextChunk() {
        if (textChunkList.isEmpty()) {
            return null;
        }
        return textChunkList.get(0);
    }

    public TextChunk getLastTextChunk() {
        if (textChunkList.isEmpty()) {
            return null;
        }
        return textChunkList.get(textChunkList.size()-1);
    }

    public boolean isAdjustSpaces() {
        return adjustSpaces;
    }

    public void setAdjustSpaces(boolean adjustSpaces) {
        this.adjustSpaces = adjustSpaces;
    }

    public void setAdjustText(boolean adjustText) {
        this.adjustText = adjustText;
    }

    public boolean isAdjustText() {
        return adjustText;
    }

    public String getText() {
        return text;
    }

    public boolean isBlankText() {
        return StringUtils.isBlank(text);
    }

    public boolean hasNonBlankText() {
        return StringUtils.isNotBlank(text);
    }

    @Override
    public String getText(boolean useLineReturns) {
        return text;
    }

    public float getTextNoRedoLength() {
        //部分文本块下方有虚线或者直线
        float textLength = 0.0f;
        if (this.isEmpty()) {
            return 0;
        } else if (this.getSize() == 1) {
            return (float) this.textChunkList.get(0).getWidth();
        } else {
            List<Rectangle> textChunkRectList = new ArrayList<>();
            for (TextChunk te : this.textChunkList) {
                textChunkRectList.add(new Rectangle(te));
            }
            textChunkRectList.sort(Comparator.comparing(Rectangle::getLeft));
            for (int i = 0; i < textChunkRectList.size() - 1; i++) {
                Rectangle one  = textChunkRectList.get(i);
                for (int j = i + 1; j < textChunkRectList.size(); j++) {
                    Rectangle other = textChunkRectList.get(j);
                    if (FloatUtils.feq(one.getTop(), other.getTop(), 1)) {
                        if (FloatUtils.feq(one.getRight(), other.getLeft(), 0.5 * this.getAvgCharWidth())) {
                            textLength += (one.getWidth() + (other.getRight() - one.getRight()));
                        } else if (FloatUtils.feq(one.getLeft(), other.getLeft(), 0.5 * this.getAvgCharWidth())) {
                            textLength += (one.getWidth() > other.getWidth()) ? one.getWidth() : other.getWidth();
                        }
                    } else {
                        if (one.getRight() <= other.getLeft() || other.getRight() <= one.getLeft()) {
                            textLength += one.getWidth();
                            i = j;
                            if (j == textChunkRectList.size() - 1) {
                                textLength += other.getWidth();
                            }
                            break;
                        } else {
                            one.merge(other);
                        }
                    }
                }
            }
            return textLength;
        }
    }

    public Rectangle2D getTextBound() {
        this.textBlockBound = this.getBounds2D();
        return this.textBlockBound;
    }

    public double getWidth() {
        if (this.textBlockBound == null) {
            this.textBlockBound = this.getBounds2D();
        }
        return this.textBlockBound.getWidth();
    }

    public double getHeight() {
        if (this.textBlockBound == null) {
            this.textBlockBound = this.getBounds2D();
        }
        return this.textBlockBound.getHeight();
    }

    public double getTop() {
        if (this.textBlockBound == null) {
            this.textBlockBound = this.getBounds2D();
        }
        return this.textBlockBound.getMinY();
    }

    public double getBottom() {
        if (this.textBlockBound == null) {
            this.textBlockBound = this.getBounds2D();
        }
        return this.textBlockBound.getMaxY();
    }

    public double getLeft() {
        if (this.textBlockBound == null) {
            this.textBlockBound = this.getBounds2D();
        }
        return this.textBlockBound.getMinX();
    }

    public double getRight() {
        if (this.textBlockBound == null) {
            this.textBlockBound = this.getBounds2D();
        }
        return this.textBlockBound.getMaxX();
    }

    public double getCenterX() {
        if (this.textBlockBound == null) {
            this.textBlockBound = this.getBounds2D();
        }
        return (this.textBlockBound.getMinX() + this.textBlockBound.getMaxX()) / 2;
    }

    public double getCenterY() {
        if (this.textBlockBound == null) {
            this.textBlockBound = this.getBounds2D();
        }
        return (this.textBlockBound.getMinY() + this.textBlockBound.getMaxY()) / 2;
    }

    public Rectangle toRectangle() {
        if (this.textBlockBound == null) {
            this.textBlockBound = this.getBounds2D();
        }
        return new Rectangle(this.textBlockBound);
    }

    public boolean hasMultiChunk(ContentGroupPage page) {
        return page.getTextChunks(this.toRectangle()).size() > 1;
    }

    public int getLineCount() {
        return textChunkList.size();
    }

    public double getLineSpacing() {
        if (textChunkList.size() == 1 || hasColumn()) {
            return getFirstTextChunk().getHeight() * 1.2;
        }
        double lineSpacingTotal = 0;
        for (int i = 0; i < textChunkList.size()-1; i++) {
            double space = textChunkList.get(i+1).getMinY() - textChunkList.get(i).getMaxY();
            lineSpacingTotal += space;
        }
        return lineSpacingTotal / (textChunkList.size() - 1);
    }

    public boolean inOneLine(Rectangle2D rectangle2D) {
        // 检测是否是居中对齐
        double centerYDiff = Math.abs(getBounds2D().getCenterY() - rectangle2D.getCenterY());
        double delta = rectangle2D.getHeight() / 2;
        if (centerYDiff <= delta) {
            return true;
        }
        // 检测是否是上对齐和下对齐
        return getFirstTextChunk().inOneLine(rectangle2D) || getLastTextChunk().inOneLine(rectangle2D);
    }

    public boolean hasColumn() {
        return columnCount >= 3;
    }

    public int getColumnCount() {
        return columnCount;
    }

    public boolean isMainText() {
        if (getColumnCount() >= 2) {
            return false;
        }
        // 检查是否是纯数字
        String text = getText().trim();
        text = StringUtils.removeAll(text, "[.,/%]");
        if (NumberUtils.isParsable(text)) {
            return false;
        }
        if (text.length() <= 3) {
            return false;
        }
        return true;
    }

    public int getCatalogLevel() {
        if (getColumnCount() >= 2) {
            return 10;
        }
        TextChunk textChunk = getFirstTextChunk();
        List<String> structTypes = textChunk.getStructureTypes();
        if (structTypes != null && !structTypes.isEmpty()) {
            String leafStructType = structTypes.get(structTypes.size() - 1);
            Matcher m = HEADING_REGEX.matcher(leafStructType);
            if (m.matches()) {
                return Integer.parseInt(m.group(1));
            }
        }
        return textChunk.isBold() ? 9 : 10;
    }

    private JsonObject buildTextStyle(TextElement textElement) {
        JsonObject style = new JsonObject();
        style.addProperty("font_size", textElement.getFontSize());
        if (textElement.isBold()) {
            style.addProperty("bold", true);
        }
        if (textElement.isItalic()) {
            style.addProperty("italic", true);
        }
        if (textElement.isUnderline()) {
            style.addProperty("underline", true);
        }
        if (textElement.isLineThrough()) {
            style.addProperty("line-through", true);
        }
        style.addProperty("color", GraphicsUtil.color2String(textElement.getColor()));
        if (textElement.getDirection() != TextDirection.LTR && textElement.getDirection() != TextDirection.UNKNOWN) {
            style.addProperty("direction", textElement.getDirection().name());
            if (textElement.getDirection() == TextDirection.ROTATED) {
                style.addProperty("rotate", textElement.getRotate());
            }
        }
        return style;
    }

    public JsonArray toJSON(TextSplitStrategy splitStrategy) {
        JsonArray array = new JsonArray();
        for (TextChunk textChunk : textChunkList) {
            // 不同行的TextChunk不合并到一起输出
            List<TextChunk> spans = splitStrategy.split(textChunk);
            for (TextChunk span : spans) {
                JsonObject style = buildTextStyle(span.getFirstElement());
                String text = span.getText().trim();
                if (StringUtils.isNoneEmpty(text)) {
                    style.addProperty("text", text);
                    array.add(style);
                    JsonArray elementArrayLeft = new JsonArray();
                    JsonArray elementArrayRight = new JsonArray();
                    span.getElements()
                            .forEach(textElement -> JsonUtil.putFloatAbbr(elementArrayLeft, textElement.getLeft()));
                    span.getElements()
                            .forEach(textElement -> JsonUtil.putFloatAbbr(elementArrayRight, textElement.getRight()));
                    style.add("char_left", elementArrayLeft);
                    style.add("char_right", elementArrayRight);
                    JsonUtil.putRectangleAbbr(style, span);
                }
            }
        }
        return array;
    }

    public TextChunk toTextChunk() {
        TextChunk textChunk = new TextChunk(textChunkList.get(0));
        for (int i = 1; i < textChunkList.size(); i++) {
            textChunk.merge(textChunkList.get(i), false);
        }
        return textChunk;
    }

    public boolean isPageFooter() {
        return textChunkList.stream().allMatch(TextChunk::isPageFooter);
    }

    public boolean isPageHeader() {
        return textChunkList.stream().allMatch(TextChunk::isPageHeader);
    }

    public boolean isPageLeftWing() {
        return textChunkList.stream().allMatch(TextChunk::isPageLeftWing);
    }

    public boolean isPageRightWing() {
        return textChunkList.stream().allMatch(TextChunk::isPageRightWing);
    }

    public boolean isPagination() {
        return textChunkList.stream().allMatch(textChunk -> textChunk.getPaginationType() != null);
    }

    public void setLastLineTag (TrainDataWriter.LineTag lineTag) {
        this.lastLineTag = lineTag;
    }

    public TrainDataWriter.LineTag getLastLineTag() {
        return this.lastLineTag;
    }

    public void setMergeFlag() {
        this.mergedFlag = true;
    }

    public boolean getMergeFlag() {
        return this.mergedFlag;
    }

    public float getAvgCharWidth() {
        if (this.avgCharWidth != 0) {
            return this.avgCharWidth;
        }

        int charNum = 0;
        for (TextChunk tc : this.getElements()) {
            for (TextElement te : tc.getElements()) {
                this.avgCharWidth += te.getTextWidth();
                charNum++;
            }
        }
        this.avgCharWidth /= charNum;
        return this.avgCharWidth;
    }

    public float getAvgCharHeight() {
        if (this.avgCharHeight != 0) {
            return this.avgCharHeight;
        }

        int charNum = 0;
        for (TextChunk tc : this.getElements()) {
            for (TextElement te : tc.getElements()) {
                this.avgCharHeight += te.getTextHeight();
                charNum++;
            }
        }
        this.avgCharHeight /= charNum;
        return this.avgCharHeight;
    }

    public float getMaxCharWidth() {
        return (float) textChunkList.stream().mapToDouble(TextChunk::getMaxTextWidth).max().orElse(.0);
    }

    public float getMaxCharHeight() {
        return (float) textChunkList.stream().mapToDouble(TextChunk::getTextHeight).max().orElse(.0);
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder(super.toString());
        builder.delete(builder.length() - 1, builder.length());
        if (isPageHeader()) {
            builder.append("; <Page Header>");
        } else if (isPageFooter()) {
            builder.append("; <Page Footer>");
        } else if (isPageLeftWing()) {
            builder.append("; <Page LeftWing>");
        } else if (isPageRightWing()) {
            builder.append("; <Page RightWing>");
        }
        builder.append(']');

        return builder.toString();
    }

    public TextBlock getActualBlock() {
        List<TextChunk> actualChunks = new ArrayList<>();
        for (TextChunk textChunk: textChunkList) {
            actualChunks.add(textChunk.getActualChunk());
        }
        return new TextBlock(actualChunks, classes);
    }
}
