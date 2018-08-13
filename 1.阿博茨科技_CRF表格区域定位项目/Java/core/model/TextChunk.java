package com.abcft.pdfextract.core.model;

import com.abcft.pdfextract.util.FloatUtils;
import com.abcft.pdfextract.util.TextUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.math3.util.FastMath;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.util.*;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 表示单行的连续文本块
 * Created by dhu on 2017/11/7.
 */
public class TextChunk extends RectangularTextContainer<TextElement> {

    static final Logger logger = LogManager.getLogger(TextChunk.class);
    
    public static final String SPACE = " ";

    private String text;
    private TextDirection direction;
    private List<TextElement> elements;
    private PaginationType paginationType;
    private final Map<String, Object> tags = new HashMap<>();  // 辅助标记信息
    private int mcid = -1;
    private String elemId;
    private String actualText;
    private List<String> structureTypes;
    private Rectangle2D visibleBBox = new Double();

    private final TextClasses classes;

    public TextChunk() {
        this(TextDirection.UNKNOWN);
    }

    public TextChunk(TextDirection direction) {
        this.elements = new ArrayList<>();
        this.direction = direction;
        this.classes = new TextClasses();
    }

    public TextChunk(String text) {
        this.elements = new ArrayList<>();
        this.direction = TextDirection.UNKNOWN;
        this.text = text;
        this.classes = new TextClasses();
    }

    public TextChunk(TextElement textElement) {
        this(textElement, null);
    }

    public TextChunk(List<TextElement> textElements) {
        this(textElements, true);
    }

    public TextChunk(List<TextElement> textElements, boolean fillSpace) {
        this(textElements, null, fillSpace);
    }

    public TextChunk(TextChunk textChunk) {
        this(textChunk, textChunk.classes);
    }

    public TextChunk(TextBlock textBlock) {
        this(textBlock.getFirstTextChunk(), textBlock.getClasses());
        List<TextChunk> tc = textBlock.getElements();
        for (int i = 1; i < tc.size(); i++) {
            this.merge(tc.get(i));
        }
    }


    private TextChunk(TextElement textElement, TextClasses classes) {
        super(textElement.x, textElement.y, textElement.width, textElement.height);
        this.elements = new ArrayList<>();
        this.direction = textElement.getDirection();
        this.classes = classes != null ? classes : new TextClasses();
        this.addElement(textElement);
    }

    private TextChunk(List<TextElement> textElements, TextClasses classes, boolean fillSpace) {
        this(textElements.get(0), classes);
        for (int i = 1; i < textElements.size(); i++) {
            this.addElement(textElements.get(i), fillSpace);
        }
    }

    private TextChunk(TextChunk textChunk, TextClasses classes) {
        super(textChunk.x, textChunk.y, textChunk.width, textChunk.height);
        this.text = textChunk.text;
        this.classes = classes != null ? classes : textChunk.classes;
        this.direction = textChunk.direction;
        this.elements = new ArrayList<>(textChunk.elements);
        this.paginationType = textChunk.paginationType;
        this.mcid = textChunk.mcid;
        this.elemId = textChunk.elemId;
        this.actualText = textChunk.actualText;
        this.structureTypes = textChunk.structureTypes;
        this.visibleBBox.setRect(textChunk.getVisibleBBox());
        this.tags.putAll(textChunk.tags);
        if (textChunk.isDeleted()) {
            this.markDeleted();
        }
    }

    public TextClasses getClasses() {
        return classes;
    }

    public void copyAttribute(TextChunk textChunk) {
        this.direction = textChunk.direction;
        this.paginationType = textChunk.paginationType;
        this.mcid = textChunk.mcid;
        this.elemId = textChunk.elemId;
        this.structureTypes = textChunk.structureTypes;
        this.tags.putAll(textChunk.tags);
    }

    public void mergePaginationType(TextChunk textChunk) {
        if (this.paginationType != null && this.paginationType != PaginationType.UNSPECIFIED) {
            return;
        }
        if (textChunk.getPaginationType() != null && textChunk.paginationType != PaginationType.UNSPECIFIED) {
            this.paginationType = textChunk.paginationType;
        }
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

    public Rectangle toRectangle() {
        return new Rectangle(this.getBounds2D());
    }

    public double horizontalVisibleDistance(TextChunk other) {
        return Math.max(this.getVisibleMinX(), other.getVisibleMinX()) - Math.min(this.getVisibleMaxX(), other.getVisibleMaxX());
    }

    public void trim() {
        trimSpaces(true, true);
    }

    public void trimStart() {
        trimSpaces(true, false);
    }

    public void trimEnd() {
        trimSpaces(false, true);
    }

    private void trimSpaces(boolean trimStart, boolean trimEnd) {
        int size = elements.size();
        int start = 0;
        if (trimStart) {
            while(start < size && StringUtils.isBlank(elements.get(start).getText())) {
                ++start;
            }
        }
        int end = size;
        if (trimEnd) {
            while(end > 0 && StringUtils.isBlank(elements.get(end - 1).getText())) {
                --end;
            }
        }
        if (0 == start && end == size) {
            // Nothing to trim
            return;
        } else if (start > end) {
            elements.clear();
        } else {
            elements = elements.subList(start, end);
        }
        rebuildText();
        if (!elements.isEmpty()) {
            setRect(Rectangle.union(elements));
        }
    }

    public boolean isEmpty() {
        return elements.isEmpty();
    }

    public boolean inSameGroup(TextElement element) {
        return !elements.isEmpty() && elements.get(elements.size()-1).getGroupIndex() == element.getGroupIndex();
    }

    void rebuildText() {
        StringBuilder builder = new StringBuilder();
        for (TextElement element : elements) {
            if (element.isDeleted()) {
                continue;
            }
            builder.append(element.getUnicode());
        }
        text = builder.toString();
    }

    /**
     * 在同一个text group里合并重叠的字符
     * @param element
     */
    public void mergeShadow(TextElement element) {
        if (getGroupIndex() != element.getGroupIndex()) {
            return;
        }
        // 如果完全不相交就没必要检测每个字符了
        if (!this.intersects(element)) {
            return;
        }
        for (TextElement prevElement : elements) {
            if (prevElement.isShadowOf(element)) {
                prevElement.markDeleted();
                rebuildText();
                break;
            }
        }
    }

    /**
     * 合并重叠的字符
     * @param textChunk
     */
    public void mergeShadow(TextChunk textChunk) {
        if (textChunk == this) {
            return;
        }
        // 如果完全不相交就没必要检测每个字符了
        if (!this.intersects(textChunk)) {
            return;
        }
        // 遍历第二个TextChunk里面的每个TextElement, 去前一个TextChunk去查找有没有重叠的, 有的话删除前面的重叠的字符
        int start = 0;
        for (TextElement element : textChunk.getElements()) {
            for (int i = start; i < elements.size(); i++) {
                TextElement prevElement = elements.get(i);
                if (prevElement.isShadowOf(element)) {
                    prevElement.markDeleted();
                    start = i + 1;
                    break;
                }
            }
        }
        if (start > 0) {
            rebuildText();
        }
    }

    public void addMockSpaceToEnd() {
        addMockSpaceBetween(getLastElement(), null, 1);
    }

    public void addMockSpaceToEnd(int numberOfSpace) {
        addMockSpaceBetween(getLastElement(), null, numberOfSpace);
    }

    private void addMockSpaceBetween(TextElement prev, TextElement next, int numberOfSpace) {
        int index = elements.indexOf(prev);
        Rectangle2D bounds, visibleBBox;
        if (next != null) {
            bounds = prev.createUnion(next);
            visibleBBox = prev.getVisibleBBox().createUnion(next.getVisibleBBox());
        } else {
            bounds = prev;
            visibleBBox = prev.getVisibleBBox();
        }
        String unicode = StringUtils.repeat(SPACE, numberOfSpace);
        TextElement space = new TextElement(visibleBBox, bounds, bounds, prev.getWidthOfSpace(), unicode, new int[]{0},
                prev.getFontName(), prev.getFontSize(), 0,
                prev.getColor(), prev.getTextStyle());
        space.setMocked(true);
        elements.add(index + 1, space);
        rebuildText();
    }

    public void addElement(TextElement element) {
        addElement(element, true);
    }

    public void addElement(TextElement element, boolean fillSpace) {
        if (elements.size() < 1) {
            setRect(element);
            text = element.getUnicode();
            if (StringUtils.isNotBlank(element.getText())) {
                // 对于空白字符，visibleBBox为零，不予处理
                visibleBBox.setRect(element.getVisibleBBox());
            }
        } else {
            TextElement lastText = elements.get(elements.size()-1);
            StringBuilder builder = new StringBuilder(text);
            if (fillSpace) {
                // 计算字符间距, 自动添加空格
                int numberOfSpace = computeSpace(lastText, element);
                if (numberOfSpace > 0) {
                    builder.append(StringUtils.repeat(SPACE, numberOfSpace));
                    addMockSpaceBetween(lastText, element, numberOfSpace);
                }
            }

            builder.append(element.getUnicode());
            text = builder.toString();
            Rectangle2D.union(this, element, this);

            // 对于空白字符，visibleBBox为零，不予处理
            if (StringUtils.isNotBlank(element.getText())) {
                if (visibleBBox.isEmpty()) {
                    visibleBBox.setRect(element.getVisibleBBox());
                } else {
                    Rectangle2D.union(visibleBBox, element.getVisibleBBox(), visibleBBox);
                }
            }
        }
        elements.add(element);
        determineDirection();
    }

    public void addTextElements(List<TextElement> elements) {
        for (TextElement element : elements) {
            addElement(element);
        }
    }

    public TextChunk merge(TextChunk textChunk) {
        return merge(textChunk, true);
    }

    public TextChunk merge(TextChunk textChunk, boolean fillSpace) {
        if (textChunk.elements.isEmpty() || StringUtils.isEmpty(textChunk.getText())) {
            return this;
        }
        if (elements.size() < 1) {
            setRect(textChunk);
            text = textChunk.getText();
            elements.addAll(textChunk.elements);
            if (!textChunk.getVisibleBBox().isEmpty()) {
                visibleBBox.setRect(textChunk.getVisibleBBox());
            }
            this.classes.addClasses(textChunk.classes);
            copyAttribute(textChunk);
        } else {
            StringBuilder builder = new StringBuilder(text);
            if (fillSpace) {
                boolean insertBefore = false;
                if (inOneLine(textChunk)) {
                    // 有些情况下, 同一行的文字并不是从左往右出现的, 有可能颠倒
                    if (textChunk.getMinX() < getMinX()) {
                        // 要加入的textchunk在左边
                        insertBefore = true;
                        int numberOfSpace = TextChunk.computeSpace(textChunk.getLastElement(), getFirstElement());
                        if (numberOfSpace > 0) {
                            builder.insert(0, StringUtils.repeat(TextChunk.SPACE, numberOfSpace));
                            textChunk.addMockSpaceToEnd(numberOfSpace);
                        }
                    } else {
                        // 要加入的textchunk在右边
                        int numberOfSpace = TextChunk.computeSpace(getLastElement(), textChunk.getFirstElement());
                        if (numberOfSpace > 0) {
                            builder.append(StringUtils.repeat(TextChunk.SPACE, numberOfSpace));
                            addMockSpaceToEnd(numberOfSpace);
                        }
                    }
                } else {
                    // 如果是多行, 中文之间不需要补空格
                    if (!TextUtils.containsCJK(text) && !TextUtils.containsCJK(textChunk.getText())) {
                        // 如果已经有空格了, 就不添加
                        if (!StringUtils.endsWith(text, " ") && !StringUtils.startsWith(textChunk.getText(), " ")) {
                            builder.append(TextChunk.SPACE);
                            addMockSpaceToEnd();
                        }
                    }
                }
                if (insertBefore) {
                    builder.insert(0, textChunk.getText());
                    elements.addAll(0, textChunk.elements);
                } else {
                    builder.append(textChunk.getText());
                    elements.addAll(textChunk.elements);
                }
            } else {
                builder.append(textChunk.getText());
                elements.addAll(textChunk.elements);
            }
            text = builder.toString();
            Rectangle2D.union(this, textChunk, this);
            if (!textChunk.getVisibleBBox().isEmpty()) {
                if (visibleBBox.isEmpty()) {
                    visibleBBox.setRect(textChunk.getVisibleBBox());
                } else {
                    Rectangle2D.union(visibleBBox, textChunk.getVisibleBBox(), visibleBBox);
                }
            }
            mergePaginationType(textChunk);
        }
        determineDirection();
        return this;
    }

    /**
     * 根据两个字符之间的间距计算需要补的空格数
     * @param source
     * @param target
     * @return
     */
    public static int computeSpace(TextElement source, TextElement target) {
        float widthOfSpace = (source.getWidthOfSpace() + target.getWidthOfSpace()) / 2;
        double distanceX = Math.abs(source.getCenterX() - target.getCenterX())
                - source.getWidth() / 2 - target.getWidth() / 2;
        double distanceY = Math.abs(source.getCenterY() - target.getCenterY())
                - source.getHeight() / 2 - target.getHeight() / 2;
        double distance = Math.max(distanceX, distanceY);
        if (distance < 0) {
            return 0;
        }
        int count = (int) Math.round(distance / widthOfSpace);
        boolean sourceIsCJK = TextUtils.isStartsWithCJK(source.getUnicode());
        boolean targetIsCJK = TextUtils.isStartsWithCJK(target.getUnicode());

        // 中文之间空格过多时不进行调整
        if (sourceIsCJK && targetIsCJK && count >= 3) {
            return count;
        }

        if (count <= 3 && count >= 2) { // 2~3个空格算1个, 避免单词之间添加了多个空格
            count = 1;
        }

        // 中文之间空格数太少就不加空格了
        if (count == 1 && (sourceIsCJK || targetIsCJK)) {
            return 0;
        }

        // 如果已经是空格结尾了就不需要补空格
        if (count == 1 && SPACE.equals(source.getUnicode())) {
            return 0;
        }

        return count;
    }

    public TextElement getFirstNonBlankElement() {
        if (elements.isEmpty()) {
            return null;
        }
        for (TextElement element : elements) {
            if (element.isDeleted() || element.isHidden() || element.isMocked()) {
                continue;
            }
            if (StringUtils.isNotBlank(element.getUnicode())) {
                return element;
            }
        }
        return null;
    }

    public TextElement getSecondNonBlankElement() {
        if (elements.isEmpty()) {
            return null;
        }
        boolean findFirst = false;
        for (TextElement element : elements) {
            if (element.isDeleted() || element.isHidden() || element.isMocked()) {
                continue;
            }
            if (StringUtils.isNotBlank(element.getUnicode())) {
                if (!findFirst) {
                    findFirst = true;
                } else {
                    return element;
                }
            }
        }
        return null;
    }

    private TextElement getLastNonBlankElement() {
        if (elements.isEmpty()) {
            return null;
        }
        for (int i = elements.size() - 1; i >= 0; --i) {
            TextElement element = elements.get(i);
            if (element.isDeleted() || element.isHidden() || element.isMocked()) {
                continue;
            }
            if (StringUtils.isNotBlank(element.getUnicode())) {
                return element;
            }
        }
        return null;
    }

    private void determineDirection() {
        if (direction == TextDirection.UNKNOWN) {
            TextElement first = getFirstNonBlankElement();
            if (first != null) {
//                TextElement second = getSecondNonBlankElement();
                TextElement second = getLastNonBlankElement();
                if (first.getDirection() != TextDirection.UNKNOWN) {
                    direction = first.getDirection();
                } else if (second != null && !second.equals(first)) {
                    // 字符的高度可能不准确, top可能不对齐, 使用bottom来比较更好
                    if (Math.abs(first.getLeft() - second.getLeft()) >= Math.abs(first.getBottom() - second.getBottom())) {
                        direction = first.getLeft() <= second.getLeft() ? TextDirection.LTR : TextDirection.RTL;
                    } else {
                        direction = first.getBottom() < second.getBottom() ? TextDirection.VERTICAL_DOWN : TextDirection.VERTICAL_UP;
                    }
                }
            }
            if (direction != TextDirection.UNKNOWN) {
                setDirection(direction);
            }
        } else {
            // 强制给所有的element也设置方向
            setDirection(direction);
        }
    }

    public void setDirection(TextDirection direction) {
        this.direction = direction;
        elements.forEach(element -> element.setDirection(direction));
    }

    public String getText() {
        return text;
    }

    public String getActualTextOrText() {
        if (actualText != null) {
            return actualText;
        }
        return text;
    }

    @Override
    public String getText(boolean useLineReturns) {
        return text;
    }

    public static String getText(List<TextChunk> textChunks) {
        if (textChunks == null || textChunks.isEmpty()) {
            return "";
        }

        StringBuilder chunkBuffer = new StringBuilder("");
        for (TextChunk tc : textChunks) {
            chunkBuffer.append(tc.getText());
        }
        return chunkBuffer.toString().trim();
    }

    public TextDirection getDirection() {
        return direction;
    }

    public boolean isUnnormalDirection() {
        if (this.direction == TextDirection.VERTICAL_UP || this.direction == TextDirection.VERTICAL_DOWN
                || this.direction == TextDirection.ROTATED) {
            return true;
        }
        return false;
    }

    public boolean isVertical() {
        return this.direction == TextDirection.VERTICAL_DOWN || this.direction == TextDirection.VERTICAL_UP;
    }

    public boolean isHorizontal() {
        return this.direction == TextDirection.LTR || this.direction == TextDirection.RTL;
    }

    @Override
    public List<TextElement> getElements() {
        return elements;
    }

    public List<TextElement> getMutableElements() {
        if (this.elements == null) {
            return new ArrayList<>();
        }

        List<TextElement> newElements = new ArrayList<>(this.elements.size());
        for (TextElement element : this.elements) {
            newElements.add(new TextElement(element));
        }

        return newElements;
    }

    public TextElement getFirstElement() {
        if (elements.isEmpty()) {
            return null;
        }
        return elements.get(0);
    }

    public TextElement getLastElement() {
        if (elements.isEmpty()) {
            return null;
        }
        return elements.get(elements.size()-1);
    }

    public TextElement getElement(int index) {
        return elements.get(index);
    }

    // 上下角标不参与正文中的空格宽度计算
    public float getWidthOfSpace() {
        if (elements.isEmpty()) {
            return 0.0f;
        }
        Map<java.lang.Float, Integer> map = new HashMap<>();
        for (TextElement element : elements) {
            map.compute(element.getWidthOfSpace(), (k, v) -> v != null ? v + 1 : 1);
        }
        Map.Entry<java.lang.Float, Integer> max = null;
        for (Map.Entry<java.lang.Float, Integer> entry : map.entrySet()) {
            if (max == null || entry.getValue() > max.getValue()) {
                max = entry;
            }
        }
        return max != null ? max.getKey() : .0f;
    }

    public float getMostCommonTextWidth() {
        if (elements.isEmpty()) {
            return 0.0f;
        }
        Map<java.lang.Float, Integer> map = new HashMap<>();
        for (TextElement element : elements) {
            map.compute(element.getTextWidth(), (k, v) -> v != null ? v + 1 : 1);
        }
        Map.Entry<java.lang.Float, Integer> max = null;
        for (Map.Entry<java.lang.Float, Integer> entry : map.entrySet()) {
            if (max == null || entry.getValue() > max.getValue()) {
                max = entry;
            }
        }
        return max != null ? max.getKey() : .0f;
    }

    public float getMostCommonTextHeight() {
        if (elements.isEmpty()) {
            return 0.0f;
        }
        Map<java.lang.Float, Integer> map = new HashMap<>();
        for (TextElement element : elements) {
            map.compute(element.getTextHeight(), (k, v) -> v != null ? v + 1 : 1);
        }
        Map.Entry<java.lang.Float, Integer> max = null;
        for (Map.Entry<java.lang.Float, Integer> entry : map.entrySet()) {
            if (max == null || entry.getValue() > max.getValue()) {
                max = entry;
            }
        }
        return max != null ? max.getKey() : .0f;
    }


    public float getMaxTextWidth() {
        return (float) elements.stream().mapToDouble(Float::getWidth).max().orElse(.0);
    }

    /**
     * 取textChunk里text内容的左边界（非空字符）
     */
    public float getTextLeft(){
        for (TextElement element : this.elements) {
            if (!StringUtils.isWhitespace(element.getText())) {
                return element.getLeft();
            }
        }
        return getLeft();
    }

    /**
     * 取textChunk里text内容的右边界（非空字符）
     */
    public float getTextRight(){
        for (int i = this.elements.size() - 1; i >= 0; i--){
            TextElement element = elements.get(i);
            if(!StringUtils.isWhitespace(element.getText())){
                return element.getRight();
            }
        }
        return getRight();
    }

    public String getMostCommonFontName() {
        if (elements.isEmpty()) {
            return null;
        }
        Map<String, Integer> map = new HashMap<>();
        for (TextElement element : elements) {
            map.compute(element.getFontName(), (k, v) -> v != null ? v + 1 : 1);
        }
        Map.Entry<String, Integer> max = null;
        for (Map.Entry<String, Integer> entry : map.entrySet()) {
            if (max == null || entry.getValue() > max.getValue()) {
                max = entry;
            }
        }
        return max != null ? max.getKey() : null;
    }

    public Set<String> getFontNames() {
        return elements.stream().map(TextElement::getFontName).collect(Collectors.toSet());
    }

    public float getMostCommonElementInterval() {
        if (elements.size() <= 1) {
            return 0;
        }
        /*
        if (elements.size() == 1) {
            return elements.get(0).getWidthOfSpace();
        }
        */

        TextElement currentElement, lastElement = null;
        Map<Integer, List<java.lang.Float>> map = new HashMap<>();
        for (int i = 0; i < elements.size(); i++) {
            currentElement = elements.get(i);

            if (lastElement != null) {
                float fInterval = (float) Math.max(currentElement.getVisibleMinX() - lastElement.getVisibleMaxX(), 0);
                int iInterval = Math.round(fInterval);
                List<java.lang.Float> value = map.get(iInterval);
                if (value == null) {
                    List<java.lang.Float> newValue = new ArrayList<>();
                    newValue.add(fInterval);
                    map.put(iInterval, newValue);
                } else {
                    value.add(fInterval);
                }
            }
            lastElement = currentElement;
        }

        Map.Entry<Integer, List<java.lang.Float>> max = null;
        for (Map.Entry<Integer, List<java.lang.Float>> entry : map.entrySet()) {
            if (max == null || entry.getValue().size() > max.getValue().size()) {
                max = entry;
            }
        }

        float commonInterval = 0f;
        if (max != null) {
            for (java.lang.Float v: max.getValue()) {
                commonInterval += v;
            }
            commonInterval = commonInterval / max.getValue().size();
        }

        return commonInterval;
    }

    public int getMostCommonTextStyle() {
        if (elements.isEmpty()) {
            return 0;
        }
        Map<Integer, Integer> map = new HashMap<>();
        for (TextElement element : elements) {
            map.compute(element.getTextStyle(), (k, v) -> v != null ? v + 1 : 1);
        }
        Map.Entry<Integer, Integer> max = null;
        for (Map.Entry<Integer, Integer> entry : map.entrySet()) {
            if (max == null || entry.getValue() > max.getValue()) {
                max = entry;
            }
        }
        return max.getKey();
    }

    public float getAvgTextGap() {
        List<TextElement> newElements = new ArrayList<>();
        for (TextElement te : this.elements) {
            if (StringUtils.isNotBlank(te.getText())) {
                newElements.add(te);
            }
        }

        if (newElements.size() <= 1) {
            return 0;
        }

        float avgTextGap = 0;
        for (int i = 0; i < newElements.size() - 1; i++) {
            avgTextGap += FastMath.max(newElements.get(i + 1).getVisibleMinX() - newElements.get(i).getVisibleMaxX(), 0);
        }

        float avgTextWidth = 0;
        for (TextElement te : newElements) {
            avgTextWidth += te.getVisibleWidth();
        }
        avgTextWidth /= newElements.size();

        return avgTextGap / ((newElements.size() - 1) * avgTextWidth);
    }

    public Set<Integer> getTextStyles() {
        return elements.stream().map(TextElement::getTextStyle).collect(Collectors.toSet());
    }

    public double getMaxElementVisibleWidth() {
        double maxVisibleWidth = 0;
        for (TextElement element: elements) {
            if (StringUtils.isBlank(element.getText())) {
                continue;
            }

            Rectangle2D r = element.getVisibleBBox();
            if (r.getWidth() > maxVisibleWidth) {
                maxVisibleWidth = r.getWidth();
            }
        }
        return maxVisibleWidth;
    }

    public float getFontSize() {
        if (elements.isEmpty()) {
            return 0;
        }
        float total = 0;
        for (TextElement element : elements) {
            total += element.getFontSize();
        }
        return total / elements.size();
    }

    public float getMaxFontSize() {
        if (elements.isEmpty()) {
            return 0;
        }
        float maxFontSize = 0;
        for (TextElement element : elements) {
            if (!element.getText().equals(SPACE) && element.getFontSize() > maxFontSize) {
                maxFontSize = element.getFontSize();
            }
        }
        return maxFontSize;
    }

    public float getMostCommonFontSize() {
        if (elements.isEmpty()) {
            return 0;
        }
        Map<java.lang.Float, Integer> map = new HashMap<>();
        for (TextElement element : elements) {
            map.compute(element.getFontSize(), (k, v) -> v != null ? v + 1 : 1);
        }
        Map.Entry<java.lang.Float, Integer> max = null;
        for (Map.Entry<java.lang.Float, Integer> entry : map.entrySet()) {
            if (max == null || entry.getValue() > max.getValue()) {
                max = entry;
            }
        }
        return max == null ? 0 : max.getKey();
    }

    public Color getMostCommonFontColor() {
        if (elements.isEmpty()) {
            return null;
        }
        Map<Color, Integer> map = new HashMap<>();
        for (TextElement element : elements) {
            map.compute(element.getColor(), (k, v) -> v != null ? v + 1 : 1);
        }
        Map.Entry<Color, Integer> max = null;
        for (Map.Entry<Color, Integer> entry : map.entrySet()) {
            if (max == null || entry.getValue() > max.getValue()) {
                max = entry;
            }
        }
        return max == null ? null : max.getKey();
    }

    public int getGroupIndex() {
        if (elements.isEmpty()) {
            return -1;
        }
        return getFirstElement().getGroupIndex();
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder(super.toString());
        builder.delete(builder.length() - 1, builder.length());
        if (isBold() || isItalic() || hasUnderLine() || hasDeleteLine()) {
            builder.append(", style: ");
            if (isBold()) {
                builder.append('B');
            }
            if (isItalic()) {
                builder.append('I');
            }
            if (hasUnderLine()) {
                builder.append('U');
            }
            if (hasDeleteLine()) {
                builder.append('D');
            }
        }
        if (isDeleted() || isHidden()) {
            builder.append(", state: ");
            if (isDeleted()) {
                builder.append("Deleted");
            }
            if (isHidden()) {
                builder.append("Hidden");
            }
        }
        switch (direction) {
            case UNKNOWN:
                builder.append(", dir: ?");
                break;
            case RTL:
                builder.append(", RTL");
                break;
            case VERTICAL_UP:
                builder.append(", ↑");
                break;
            case VERTICAL_DOWN:
                builder.append(", ↓");
                break;
            case ROTATED:
                builder.append(", rotated");
                break;
            default:
                break;
        }
        if (mcid != -1) {
            builder.append("; mcid=").append(mcid);
        }
        if (actualText != null) {
            builder.append("; at=\"").append(actualText).append("\"");
        }
        if (structureTypes != null) {
            builder.append("; st=").append(structureTypes);
        }
        if (elemId != null) {
            builder.append("; elem=").append(elemId);
        }
        if (paginationType != null) {
            builder.append("; pagination=").append(paginationType);
        }
        builder.append(']');

        return builder.toString();
    }

    public void setHasDeleteLine(boolean value) {
        elements.forEach(element -> element.setLineThrough(value));
    }

    public void setHasUnderLine(boolean value) {
        elements.forEach(element -> element.setUnderline(value));
    }

    @Override
    public boolean isDeleted() {
        return super.isDeleted() || elements.stream().filter(e -> !e.isMocked()).allMatch(TextElement::isDeleted);
    }

    public boolean isHidden() {
        return elements.stream().filter(e -> !e.isMocked()).allMatch(TextElement::isHidden);
    }

    public boolean isVisible() {
        return !isDeleted() && !isHidden();
    }

    public void setHidden(boolean value) {
        elements.forEach(element -> element.setHidden(value));
    }

    public boolean isBold() {
        return elements.stream().allMatch(TextElement::isBold);
    }

    public boolean isItalic() {
        return elements.stream().allMatch(TextElement::isItalic);
    }

    public boolean hasUnderLine() {
        return elements.stream().allMatch(TextElement::isUnderline);
    }

    public boolean hasDeleteLine() {
        return elements.stream().allMatch(TextElement::isLineThrough);
    }

    public void mergeDiacritic(TextElement element) {
        if (elements.isEmpty()) {
            return;
        }
        TextElement lastElement = elements.get(elements.size()-1);
        String oldText = lastElement.getUnicode();
        lastElement.mergeDiacritic(element);
        text = text.substring(0, text.length()-oldText.length()) + lastElement.getUnicode();
        Rectangle2D.union(this, lastElement, this);
    }

    public boolean inOneLine(Rectangle2D rectangle2D) {
        double bottomDiff = Math.abs(getBottom() - rectangle2D.getMaxY());
        double minHeight = Math.min(getHeight(), rectangle2D.getHeight());
        double delta = minHeight * 2 / 5;
        return bottomDiff <= delta;
    }

    public boolean isBlank() {
        return StringUtils.isBlank(text);
    }

    public boolean isHiddenOrBlank() {
        return isHidden() || isBlank();
    }

    public boolean isPagination() {
        return paginationType != null && paginationType != PaginationType.UNSPECIFIED;
    }

    public boolean isPageFooter() {
        return PaginationType.FOOTER == paginationType;
    }

    public PaginationType getPaginationType() {
        return paginationType;
    }

    public void setPaginationType(PaginationType paginationType) {
        this.paginationType = paginationType;
    }

    public boolean isPageHeader() {
        return PaginationType.HEADER == paginationType;
    }

    public boolean isPageHeaderOrFooter() {
        return isPageHeader() || isPageFooter();
    }

    public boolean isPageLeftWing() {
        return PaginationType.LEFT_WING == paginationType;
    }

    public  boolean isPageRightWing() {
        return PaginationType.RIGHT_WING == paginationType;
    }

    public boolean isPageLeftOrRightWing() {
        return isPageLeftWing() || isPageRightWing();
    }

    public void addTag(String key, Object value) {
        tags.put(key, value);
    }

    public Object getTag(String key) {
        return tags.get(key);
    }

    public boolean hasTag(String key) {
        return tags.containsKey(key);
    }

    public boolean isMultiRowTextChunk() {
        List<TextElement> textElements = this.getElements();
        if (textElements.size() < 2) {
            return false;
        }

        float avgCharHeight = 0;
        for (TextElement te : textElements) {
            avgCharHeight += te.getTextHeight();
        }
        avgCharHeight /= textElements.size();

        for (int i = 0; i < textElements.size() - 1; i++) {
            if (!FloatUtils.feq(textElements.get(i).getCenterY(), textElements.get(i + 1).getCenterY(), 0.8 * avgCharHeight)) {
                return true;
            }
        }
        return false;
    }

    public boolean hasSameStyle(TextChunk textChunk) {
        return FloatUtils.feq(getTextHeight(), textChunk.getTextHeight(), 2)
                && getMostCommonTextStyle() == textChunk.getMostCommonTextStyle();
    }

    public double getTextHeight(){
        if (elements.isEmpty()) {
            return 0;
        }
        double textHeight = 0.0;
        for (TextElement element : elements) {
            if (!element.getText().equals(SPACE) && element.getHeight() > textHeight) {
                textHeight = element.getHeight();
            }
        }
        return textHeight;
    }

    public float getAvgCharWidth() {
        int charNum = 0;
        float avgCharWidth = 0;
        for (TextElement te : this.getElements()) {
            avgCharWidth += te.getTextWidth();
            charNum++;
        }
        avgCharWidth /= charNum;
        return avgCharWidth;
    }

    public float getAvgCharHeight() {
        int charNum = 0;
        float avgCharHeight = 0;
        for (TextElement te : this.getElements()) {
            avgCharHeight += te.getTextHeight();
            charNum++;
        }
        avgCharHeight /= charNum;
        return avgCharHeight;
    }

    public float getTextLength() {
        if (this.elements.isEmpty()) {
            return 0;
        }
        float avgCharHeight = this.getAvgCharHeight();
        List<TextChunk> splitChunks = new ArrayList<>();
        TextChunk textChunk = new TextChunk(this.elements.get(0));
        splitChunks.add(textChunk);
        for (int i = 1; i < this.elements.size(); i++) {
            TextElement te = this.elements.get(i);
            if (FloatUtils.feq(splitChunks.get(splitChunks.size() - 1).getCenterY(), te.getCenterY(), 0.7 * avgCharHeight)) {
                splitChunks.get(splitChunks.size() - 1).addElement(te);
            } else {
                textChunk = new TextChunk(te);
                splitChunks.add(textChunk);
            }
        }
        float textLength = 0;
        if (!splitChunks.isEmpty()) {
            for (TextChunk tc : splitChunks) {
                textLength += tc.getWidth();
            }
        }
        return textLength;
    }

    public void setStructElementId(String elemId) {
        this.elemId = elemId;
    }

    public String getStructElementId() {
        return elemId;
    }

    public boolean inSameNode(TextChunk textChunk) {
        if (null == textChunk || elemId == null || textChunk.elemId == null) {
            return false;
        }
        return elemId.equals(textChunk.elemId);
    }

    public void setMcid(int mcid) {
        this.mcid = mcid;
    }

    public int getMcid() {
        return mcid;
    }

    public void setActualText(String actualText) {
        this.actualText = actualText;
        if (StringUtils.isNotBlank(actualText)) {
            if (actualText.length() == elements.size()) {
                for (int i = 0; i < elements.size(); i++) {
                    String subActualText = actualText.substring(i, i+1);
                    TextElement element = elements.get(i);
                    element.setUnicode(subActualText);

                    // 对于空白字符，visibleBBox为零，不予处理
                    if (StringUtils.isNotBlank(subActualText)) {
                        if (visibleBBox.isEmpty()) {
                            visibleBBox.setRect(element.getVisibleBBox());
                        } else {
                            Rectangle2D.union(visibleBBox, element.getVisibleBBox(), visibleBBox);
                        }
                    }
                }
                text = actualText;
            } else {
                //logger.warn("ActualText's length is not equal to element's size.");
            }
        }
    }

    public String getActualText() {
        return actualText;
    }

    public void setStructureTypes(List<String> structureTypes) {
        this.structureTypes = structureTypes;
        this.classes.addClasses(TextClasses.getClassesFromStructureType(structureTypes));
    }

    public List<String> getStructureTypes() {
        return structureTypes;
    }

    public boolean hasStructureType(Pattern pattern) {
        for (String structType : structureTypes) {
            if (pattern.matcher(structType).find()) {
                return true;
            }
        }
        return false;
    }

    public boolean hasStructureType(String type) {
        for (String structType : structureTypes) {
            if (StringUtils.equalsIgnoreCase(structType, type)) {
                return true;
            }
        }
        return false;
    }

    // 获得实际的TextChunk，去除包含deleted标签的element
    public TextChunk getActualChunk() {
        boolean hasUseless = false;
        for (TextElement element: elements) {
            if (element.isDeleted()) {
                hasUseless = true;
                break;
            }
        }
        if (!hasUseless) {
            return this;
        } else {
            List<TextElement> actualElements = new ArrayList<>();
            for (TextElement element: elements) {
                if (element.isDeleted() || element.isMocked()) {
                    continue;
                }
                actualElements.add(element);
            }

            TextChunk actualChunk;
            if (actualElements.isEmpty()) {
                actualChunk = new TextChunk();
            } else {
                actualChunk = new TextChunk(actualElements);
                actualChunk.paginationType = this.paginationType;
                actualChunk.mcid = this.mcid;
                actualChunk.elemId = this.elemId;
                actualChunk.structureTypes = this.structureTypes;
                actualChunk.tags.putAll(this.tags);
            }
            return actualChunk;
        }
    }

    // 分析判断是否上标结尾，即最后一个元素为上标字符
    public boolean isEndWithSuperscript() {
        TextElement lastElement = getLastNonBlankElement();
        if (lastElement == null) {
            return false;
        }

        // 计算除去最后一个元素外，其他元素集合的一些特征
        int subElementNum = 0;
        Map<java.lang.Float, Integer> map = new HashMap<>();
        double subTop = 10000., subBottom = 0.;
        TextElement secondLastElement = elements.get(0);
        for (TextElement element : elements) {
            if (element.isDeleted() || element.isHidden() || element.isMocked()) {
                continue;
            }
            if (StringUtils.isBlank(element.getUnicode())) {
                continue;
            }

            if (element == lastElement) {
                break;
            }
            map.compute(element.getFontSize(), (k, v) -> v != null ? v + 1 : 1);
            if (element.getVisibleMinY() < subTop) {
                subTop = element.getVisibleMinY();
            }
            if (element.getVisibleMaxY() > subBottom) {
                subBottom = element.getVisibleMaxY();
            }
            subElementNum += 1;
            secondLastElement = element;
        }
        Map.Entry<java.lang.Float, Integer> max = null;
        for (Map.Entry<java.lang.Float, Integer> entry : map.entrySet()) {
            if (max == null || entry.getValue() > max.getValue()) {
                max = entry;
            }
        }

        // 非零元素太少，则不太好比较
        if (subElementNum <= 3) {
            return false;
        }

        float commonFontSize = max != null ? max.getKey() : getFirstNonBlankElement().getFontStyleSize();
        // 1.字体较小;
        boolean isSmallFontType = lastElement.getFontSize() < commonFontSize;
        // 2.位置偏上
        boolean isUpPosition = lastElement.getVisibleMinY() < subTop && lastElement.getVisibleMaxY() < subBottom;
        // 3.比前一个元素要偏上
        boolean isUpThanBefore = lastElement.getVisibleMinY() < secondLastElement.getVisibleMinY() &&
                                 lastElement.getVisibleMaxY() < secondLastElement.getVisibleMaxY();

        return isSmallFontType && isUpPosition && isUpThanBefore;
    }

}
