package com.abcft.pdfextract.core.chart;

import com.abcft.pdfextract.core.PaperParameter;
import com.abcft.pdfextract.core.model.*;
import com.abcft.pdfextract.core.table.Ruling;
import com.abcft.pdfextract.util.FloatUtils;
import com.abcft.pdfextract.util.TextUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 **文字块合并策略
 * Created by xwang on 17-12-7.
 * Edited by chzhang on 18-03-22
 */
public class ChartTextMerger {
    private static final float AVERAGE_CHAR_TOLERANCE = 0.3f;
    private static final float SAME_ROW_OVERLAP = 0.5f;

    private static final Pattern TEXT_CHUNK_END = Pattern.compile("[.。:：)）]\\s*$");
    private static final Pattern TEXT_SECTION_PREFIX = Pattern.compile("^\\s*[(（]?[一二三四五六七八九十\\d]+([.、:：)）]\\s*).*");
    private static final Pattern NUM_PREFIX = Pattern.compile("((\\s*-?)[\\d\\s\\.,-]*\\d$)");
    private static final Pattern TEXT_LINE_END = Pattern.compile("。\\s*$");

    private static final List<Pair<String, String>> TEXT_MERGER_TEMPLATE = Arrays.asList(Pair.of("项", "目"));

    private static String MERGER_TYPE = "MergerType";

    enum ChunkMergerType {
        NORMAL,
        BLOCK_END,
    }

    public static List<TextChunk> mergeCellTextChunks(List<TextChunk> textChunks) {
        if (textChunks.isEmpty()) {
            return textChunks;
        }
        // 使用默认的文本块合并机制，自动填补丢失的空格
        TextBlock cellBlock = new TextBlock();
        cellBlock.setAdjustSpaces(true);
        cellBlock.addElements(textChunks);
        return cellBlock.getElements();
    }

    private static boolean isSameRow(TextElement textElement1, TextElement textElement2) {
        return textElement1.verticalOverlapRatio(textElement2) > SAME_ROW_OVERLAP;
    }

    private static boolean isSameRow(TextChunk textChunk1, TextChunk textChunk2) {
        return isSameRow(textChunk1.getLastElement(), textChunk2.getFirstElement());
    }

    private static float getTextValidWidth(List<TextChunk> textChunks) {
        float leftBound = 999.9f;
        float rightBound = 0.0f;
        for (TextChunk textChunk : textChunks) {
            if (PaperParameter.A4.topMarginContains(textChunk.getBottom()) ||
                    PaperParameter.A4.bottomMarginContains(textChunk.getTop())) {
                continue;
            }

            if (leftBound > textChunk.getLeft()) {
                leftBound = textChunk.getLeft();
            }

            if (rightBound < textChunk.getRight()) {
                rightBound = textChunk.getRight();
            }
        }

        // 正文一行不小于350，经验值
        if (rightBound - leftBound < 350.0f) {
            return 350.0f;
        }

        return rightBound - leftBound;
    }

    // TODO:目前对中文有效
    private static boolean isTextRow(TextChunk lastChunk, TextChunk nextChunk, float textVaildWidth, float searchHeight) {
        boolean overlapRatio = lastChunk.horizontallyOverlapRatio(nextChunk) >= 0.8f;
        boolean lineSpace = nextChunk.getTop() > lastChunk.getBottom() + 0.7f * searchHeight;
        boolean lastTextShort = lastChunk.getWidth() / nextChunk.getWidth() < 0.5f;

        boolean nextChunkTextFeature = TEXT_LINE_END.matcher(nextChunk.getText()).find();
        boolean lastChunkTextFeature = TEXT_LINE_END.matcher(lastChunk.getText()).find();

        boolean nextLongText = nextChunk.getTextLength() > textVaildWidth * 0.8;
        boolean lastLongText = lastChunk.getTextLength() > textVaildWidth * 0.8;
        boolean lastChunkIsText = lastLongText || lastChunkTextFeature;
        boolean nextChunkIsText = nextLongText || nextChunkTextFeature;

        return lineSpace && !lastChunkIsText && nextChunkIsText && overlapRatio && lastTextShort;
    }

    private static boolean canMerge(TextChunk textChunk1, TextChunk textChunk2, List<Ruling> verticalRulingLines) {
        // 满足模版特例情况，不考虑后面其他条件
        for (Pair pair: TEXT_MERGER_TEMPLATE) {
            if (textChunk1.getText().equals(pair.getLeft()) && textChunk2.getText().equals(pair.getRight())) {
                return true;
            }
        }

        // 文字方向确定的文字块只能和同方向的合并, 方向不确定的可以和任意方向合并
        if (textChunk1.getDirection() != textChunk2.getDirection()
                && textChunk1.getDirection() != TextDirection.UNKNOWN
                && textChunk2.getDirection() != TextDirection.UNKNOWN) {
            return false;
        }

        // 不同的字体，则不能合并
        if (textChunk1.getMostCommonTextStyle() != textChunk2.getMostCommonTextStyle() &&
                !textChunk1.isHorizontallyOverlap(textChunk2) &&
                !FloatUtils.feq(textChunk1.getRight(), textChunk2.getLeft(),
                        Math.min(textChunk1.getWidthOfSpace(), textChunk2.getWidthOfSpace()) * 0.1f)) {
            // TODO: 优化下划线字体的判定，特别是(\/)等符号
            if (textChunk1.getMostCommonTextStyle() != TextElement.TEXT_STYLE_UNDERLINE &&
                    textChunk2.getMostCommonTextStyle() != TextElement.TEXT_STYLE_UNDERLINE) {
                return false;
            }
        }

        // 不同行的不能合并
        if (!isSameRow(textChunk1, textChunk2)) {
            return false;
        }

        float wordSpacing1 = textChunk1.getWidthOfSpace();
        float wordSpacing2 = textChunk2.getWidthOfSpace();
        float deltaSpace;
        if (Float.isNaN(wordSpacing1) || wordSpacing1 == 0) {
            deltaSpace = Float.MAX_VALUE;
        } else if (wordSpacing2 < 0) {
            deltaSpace = wordSpacing1;
        } else {
            deltaSpace = (wordSpacing1 + wordSpacing2) / 2.0f;
        }
        if (TEXT_SECTION_PREFIX.matcher(textChunk1.getText()).find()) {
            // 项目符号后面的空格比较多, 增大查找的区域
            deltaSpace = deltaSpace * 2;
        }

        float averageCharWidth = (float) ((textChunk1.getWidth() / textChunk1.getText().length() +
                (textChunk2.getWidth() / textChunk2.getText().length())) / 2.0f);
        float deltaCharWidth = averageCharWidth * AVERAGE_CHAR_TOLERANCE;
        float searchWidth = deltaCharWidth + deltaSpace;

        boolean canMerge = true;
        if (!(textChunk1.getLeft() <= textChunk2.getLeft() && textChunk2.getLeft() <= textChunk1.getRight() + searchWidth)) {
            canMerge = false;
        } else {
            // 判断是否两文本之间有ruling分隔，如果有则不能合并
            boolean acrossVerticalRuling = false;
            for (Ruling r : verticalRulingLines) {
                if ((r.isVerticallyOverlap(textChunk1) && r.isVerticallyOverlap(textChunk2)) &&
                        ((textChunk1.getCenterX() < r.getPosition() && textChunk2.getCenterX() > r.getPosition()) ||
                                (textChunk1.getCenterX() > r.getPosition() && textChunk2.getCenterX() < r.getPosition()))) {
                    acrossVerticalRuling = true;
                    break;
                }
            }
            if (acrossVerticalRuling) {
                canMerge = false;
            }
        }
        return canMerge;
    }

    private static boolean canMerge(TextBlock prevBlock, TextChunk nextText, List<Ruling> horizontalRulingLines,
                                    List<Ruling> verticalRulingLines, float textVaildWidth) {
        TextChunk lastText = prevBlock.getLastTextChunk();
        if (lastText.hasTag(MERGER_TYPE) && lastText.getTag(MERGER_TYPE) == com.abcft.pdfextract.core.chart.ChartTextMerger.ChunkMergerType.BLOCK_END) {
            return false;
        }

        if (!isSameRow(lastText, nextText)) {
            // 如果不在同一行，则分析是否满足换行的合并单元格
            float searchHeight = (float) (lastText.getHeight() + nextText.getHeight()) / 2.0f;
            if (lastText.horizontallyOverlapRatio(nextText) < 0.5 ||
                    !(lastText.getTop() <= nextText.getTop() && nextText.getTop() <= lastText.getBottom() + searchHeight)) {
                return false;
            }

            // 字体不同，不能合并
            if (lastText.getMostCommonTextStyle()!= nextText.getMostCommonTextStyle()) {
                return false;
            }

            // 如果字样式相同，字号差距大，不能合并
            if (StringUtils.equals(lastText.getMostCommonFontName(), nextText.getMostCommonFontName()) &&
                    !FloatUtils.feq(lastText.getMostCommonFontSize(), nextText.getMostCommonFontSize(), 0.5)) {
                return false;
            }

            // 正则要求
            if (TEXT_CHUNK_END.matcher(lastText.getText()).find() ||
                    TEXT_SECTION_PREFIX.matcher(nextText.getText()).find()) {
                // 补充条件 - 对英文单词缩写以.结尾的文本例外
                if (TextUtils.containsCJK(lastText.getText()) || lastText.getElements().size() > 6) {
                    return false;
                }
            }

            // 判断是否两文本之间有ruling分隔，如果有则不能合并
            boolean canMerge = true;
            for (Ruling r : horizontalRulingLines) {
                if ((r.isHorizontallyOverlap(lastText) && r.isHorizontallyOverlap(nextText)) &&
                        ((lastText.getCenterY() < r.getPosition() && nextText.getCenterY() > r.getPosition()) ||
                                (lastText.getCenterY() > r.getPosition() && nextText.getCenterY() < r.getPosition()))) {
                    canMerge = false;
                    break;
                }
            }

            // 字体颜色的不同,来过滤掉一部分英文标题
            if (lastText.getLastElement().getColor().getRGB() != nextText.getFirstElement().getColor().getRGB()
                    && prevBlock.getElements().size() == 1 && lastText.getFontSize() != nextText.getFontSize()) {
                return false;
            }

            // 判断正文
            if (isTextRow(lastText, nextText, textVaildWidth, searchHeight)) {
                return false;
            }

            return canMerge;
        } else {
            // 如果在同一行，则分析是否满足搜索合并区域
            boolean canMerge = true;
            canMerge = canMerge(lastText, nextText, verticalRulingLines);
            return canMerge;
        }
    }

    /**
     * 根据原始PDF的TJ
     * 合并在一块的相邻的文字
     */
    public static List<TextChunk> groupNeighbour(List<TextChunk> textObjects) {
        return groupNeighbour(textObjects, new ArrayList<>());
    }

    static List<TextChunk> groupNeighbour(List<TextChunk> textObjects, List<Ruling> verticalRulingLines) {
        List<TextChunk> merged = new ArrayList<>();
        TextChunk prevText = null;
        //对每个TJ做合并分析
        for (TextChunk text : textObjects) {
            if (text.isDeleted()) {
                continue;
            }
            if (StringUtils.isBlank(text.getText())) {
                if (prevText != null && isSameRow(text, prevText) &&
                        (text.getLeft() - prevText.getRight() > text.getWidth() * 2)) {
                    prevText.addTag(MERGER_TYPE, com.abcft.pdfextract.core.chart.ChartTextMerger.ChunkMergerType.BLOCK_END);
                }
                continue;
            }

            //对每个待合并分析的TJ基于空字符间隔压缩拆分
            List<TextChunk> squeezeChunks = squeeze(text, ' ', 3, verticalRulingLines);
            for (TextChunk squeezeChunk : squeezeChunks) {
                if (StringUtils.isBlank(squeezeChunk.getText())) {
                    continue;
                }
                //清除每个chunk的前后空白符
                squeezeChunk.trim();
                //对每个压缩拆分后的TextChunk针对纯数字的空字符间隔压缩拆分
                List<TextChunk> textChunks = squeezeNumberText(squeezeChunk);
                for (TextChunk textChunk : textChunks) {
                    if (prevText != null && canMerge(prevText, textChunk, verticalRulingLines)) {
                        prevText.merge(textChunk);
                    } else {
                        if (StringUtils.isNotBlank(textChunk.getText())) {
                            prevText = textChunk;
                            merged.add(textChunk);
                        }
                    }
                }
            }
        }
        for (TextChunk textChunk : merged) {
            if (textChunk.getDirection() == TextDirection.UNKNOWN) {
                textChunk.setDirection(TextDirection.LTR);
            }
        }
        return merged;
    }

    /**
     * 将连续相邻的文本字符合并成一个文本块
     */
    public static List<TextChunk> groupByChunks(List<TextElement> textElements) {
        return groupByChunks(textElements, new ArrayList<>());
    }

    public static List<TextChunk> groupByChunks(List<TextElement> textElements, List<Ruling> verticalRulingLines) {
        List<TextChunk> t = textElements.stream().map(TextChunk::new).collect(Collectors.toList());
        return groupNeighbour(t, verticalRulingLines);
    }

    /**
     * 根据原始PDF的TJ，或者初步合并后的单独文本块，合并成满足连续文本的文本块
     * 如：实际连续的多行独立单元格文本块
     */
    public static List<TextBlock> groupByBlock(List<TextChunk> textChunks) {
        return groupByBlock(textChunks, new ArrayList<>(), new ArrayList<>());
    }

    public static List<TextBlock> groupByBlock(List<TextChunk> textChunks, List<Ruling> horizontalRulingLines, List<Ruling> verticalRulingLines) {
        List<TextBlock> merged = new ArrayList<>();
        TextBlock prevBlock = null;
        float textValidWidth = getTextValidWidth(textChunks);
        for (TextChunk text : textChunks) {
            if (text.isDeleted()) {
                continue;
            }
            if (prevBlock != null && canMerge(prevBlock, text, horizontalRulingLines, verticalRulingLines, textValidWidth)) {
                prevBlock.addElement(text);
            } else {
                if (StringUtils.isNotBlank(text.getText())) {
                    prevBlock = new TextBlock(text);
                    merged.add(prevBlock);
                }
            }
        }
        return merged;
    }

    /** Splits a TextChunk in two, at the position of the i-th TextElement
     */
    private static TextChunk[] splitAt(TextChunk textChunk, int i) {
        if (i < 1 || i >= textChunk.getElements().size()) {
            throw new IllegalArgumentException();
        }

        return new TextChunk[] {
                new TextChunk(textChunk.getElements().subList(0, i)),
                new TextChunk(textChunk.getElements().subList(i, textChunk.getElements().size()))
        };
    }

    public static float computeSpace(TextElement lastElement, TextElement currElement) {
        float widthOfSpace = (lastElement.getWidthOfSpace() + currElement.getWidthOfSpace()) / 2;
        double distanceX = Math.abs(lastElement.getCenterX() - currElement.getCenterX())
                - lastElement.getWidth() / 2 - currElement.getWidth() / 2;
        double distanceY = Math.abs(lastElement.getCenterY() - currElement.getCenterY())
                - lastElement.getHeight() / 2 - currElement.getHeight() / 2;
        double distance = Math.max(distanceX, distanceY);
        if (distance < 0) {
            return 0;
        }
        return (float) distance / widthOfSpace;
    }

    /**
     * Removes runs of identical TextElements in this TextChunk
     * For example, if the TextChunk contains this string of characters: "1234xxxxx56xx"
     * and c == 'x' and minRunLength == 4, this method will return a list of TextChunk
     * such that: ["1234", "56xx"]
     */
    public static List<TextChunk> squeeze(TextChunk textChunk, Character c, int minRunLength) {
        return squeeze(textChunk, c, minRunLength, new ArrayList<>());
    }

    private static List<TextChunk> squeeze(TextChunk textChunk, Character c, int minRunLength, List<Ruling> verticalRulingLines) {
        Character currentChar, lastChar = null;
        TextElement currentElement, lastElement = null;
        int subSequenceLength = 0, subSequenceStart = 0, countLength = 0;
        TextChunk[] t;
        List<TextChunk> rv = new ArrayList<>();
        boolean hasContainsCJK = TextUtils.containsCJK(textChunk.getText());

        for (int i = 0; i < textChunk.getElements().size(); i++) {
            currentElement = textChunk.getElements().get(i);
            currentChar = currentElement.getText().charAt(0);

            // 使用ruling辅助拆分
            boolean splitVerticalRuling = false;
            boolean acrossVerticalRuling = false;
            for (Ruling r : verticalRulingLines) {
                if (lastElement != null && (r.isVerticallyOverlap(currentElement) && r.isVerticallyOverlap(lastElement)) &&
                        ((currentElement.getCenterX() < r.getPosition() && lastElement.getCenterX() > r.getPosition()) ||
                                (currentElement.getCenterX() > r.getPosition() && lastElement.getCenterX() < r.getPosition()))) {
                    splitVerticalRuling = true;
                    break;
                } else if (r.intersects(currentElement) && StringUtils.isBlank(currentElement.getText())) {
                    acrossVerticalRuling = true;
                    break;
                }
            }

            // 根据字符间距辅助拆分 -- 目前仅对中文处理
            boolean hasLongDistance = false;
            if (hasContainsCJK && lastElement != null && isSameRow(lastElement, currentElement)) {
                float distanceOfSpace = computeSpace(lastElement, currentElement);
                if (distanceOfSpace > 1.5f) {
                    hasLongDistance = true;
                }
            }

            if (lastChar != null && currentChar.equals(c) && lastChar.equals(currentChar) &&
                    !splitVerticalRuling && !acrossVerticalRuling) {
                subSequenceLength++;
                countLength += currentElement.getUnicode().length();
            }
            else {
                if ((((lastChar != null && !lastChar.equals(currentChar)) || i + 1 == textChunk.getElements().size()) &&
                        countLength >= minRunLength) || splitVerticalRuling || acrossVerticalRuling || hasLongDistance) {

                    if (splitVerticalRuling || hasLongDistance) {
                        t = splitAt(textChunk, i);
                        rv.add(t[0]);
                    } else if (acrossVerticalRuling) {
                        if (i == 0) {
                            t = splitAt(textChunk, i+1);
                        } else {
                            t = splitAt(textChunk, i);
                            rv.add(t[0]);
                        }
                    } else {
                        if (subSequenceStart == 0 && subSequenceLength <= textChunk.getElements().size() - 1) {
                            t = splitAt(textChunk, subSequenceLength);
                        }
                        else {
                            t = splitAt(textChunk, subSequenceStart);
                            rv.add(t[0]);
                        }
                    }
                    if (StringUtils.isNotBlank(t[1].getText())) {
                        rv.addAll(squeeze(t[1], c, minRunLength, verticalRulingLines)); // Lo and behold, recursion.
                    }
                    break;

                }
                subSequenceLength = 1;
                subSequenceStart = i;
                if (currentChar.equals(c)) {
                    countLength = currentElement.getUnicode().length();
                }
            }

            lastChar = currentChar;
            lastElement = currentElement;
        }

        if (rv.isEmpty()) { // no splits occurred, hence this.squeeze() == [this]
            if (countLength >= minRunLength && subSequenceLength < textChunk.getElements().size()) {
                TextChunk[] chunks = splitAt(textChunk, subSequenceStart);
                rv.add(chunks[0]);
            }
            else {
                rv.add(new TextChunk(textChunk));
            }
        }

        return rv;
    }

    private static List<TextChunk> squeezeNumberText(TextChunk textChunk) {
        List<TextChunk> rv = new ArrayList<>();

        String text = textChunk.getText();
        int spaceIdx = text.indexOf(" ");
        if (NUM_PREFIX.matcher(textChunk.getText()).matches() &&
                spaceIdx > 0 && spaceIdx < text.length() - 1) {
            TextChunk[] t = splitAt(textChunk, spaceIdx);
            rv.add(t[0]);
            t[1].trim();
            rv.addAll(squeezeNumberText(t[1]));
        } else {
            rv.add(textChunk);
        }
        return rv;
    }

    public static boolean isSameChar(TextChunk textChunk, Character c) {
        return isSameChar(textChunk, new Character[] { c });
    }

    public static boolean isSameChar(TextChunk textChunk, Character[] c) {
        String s = textChunk.getText();
        List<Character> chars = Arrays.asList(c);
        for (int i = 0; i < s.length(); i++) {
            if (!chars.contains(s.charAt(i))) { return false; }
        }
        return true;
    }

    public static boolean allSameChar(List<TextChunk> textChunks) {
        /* the previous, far more elegant version of this method failed when there was an empty TextChunk in textChunks.
         * so I rewrote it in an ugly way. but it works!
         * it would be good for this to get rewritten eventually
         * the purpose is basically just to return true iff there are 2+ TextChunks and they're identical.
         * -Jeremy 5/13/2016
         */

        if(textChunks.size() == 1) return false;
        boolean hasHadAtLeastOneNonEmptyTextChunk = false;
        char first = '\u0000';
        for (TextChunk tc: textChunks) {
            if (tc.getText().length() == 0) {
                continue;
            }
            if (first == '\u0000'){
                first = tc.getText().charAt(0);
            }else{
                hasHadAtLeastOneNonEmptyTextChunk = true;
                if (!isSameChar(tc, first)) return false;
            }
        }
        return hasHadAtLeastOneNonEmptyTextChunk;
    }

    private static TextBlock removeRepeatedCharacters(TextBlock line, Character c, int minRunLength) {

        TextBlock rv = new TextBlock();

        for(TextChunk t: line.getElements()) {
            TextChunk temp = new TextChunk();
            for (TextChunk r: squeeze(t, c, minRunLength)) {
                temp.merge(r, false);
            }
            rv.addElement(temp);
        }

        return rv;
    }

    /**
     * 合并在一行的文字块
     */
    public static List<TextBlock> groupByRows(List<TextBlock> textBlocks) {
        List<TextChunk> tc = textBlocks.stream().map(TextChunk::new).collect(Collectors.toList());
        return groupByLines(tc);
    }

    public static List<TextBlock> groupByLines(List<TextChunk> textChunks) {
        if (textChunks.size() == 0) {
            return new ArrayList<>();
        }

        List<TextBlock> lines;
        List<Rectangle> lineBounds;
        TextBlock line;
        Rectangle bound;
        boolean isHorizontal;

        List<TextChunk> horizontalTextChunks = new ArrayList<>();
        List<TextBlock> verticalLines = new ArrayList<>();
        List<Rectangle> verticalLineBounds = new ArrayList<>();
        List<TextBlock> horizontalLines = new ArrayList<>();
        List<Rectangle> horizontalLineBounds = new ArrayList<>();

        for (TextChunk te : textChunks) {
            float maxOverlap = 0;
            int maxIdx = -1;
            if (te.getDirection() == TextDirection.VERTICAL_UP || te.getDirection() == TextDirection.VERTICAL_DOWN) {
                lines = verticalLines;
                lineBounds = verticalLineBounds;
                isHorizontal = false;
            } else {
                lines = horizontalLines;
                lineBounds = horizontalLineBounds;
                isHorizontal = true;
                horizontalTextChunks.add(te);
            }

            for (int j = 0; j < lineBounds.size(); j++) {
                bound = lineBounds.get(j);
                float ratio = isHorizontal ? bound.verticalOverlapRatio(te) : bound.horizontallyOverlapRatio(te);
                if (ratio > maxOverlap) {
                    maxOverlap = ratio;
                    maxIdx = j;
                }
            }
            if (maxIdx == -1 || maxOverlap < SAME_ROW_OVERLAP) {
                line = new TextBlock(te);
                lines.add(line);
                lineBounds.add(new Rectangle(line.getBounds2D()));
            } else {
                line = lines.get(maxIdx);
                line.addElement(te);
                lineBounds.get(maxIdx).setRect(line.getBounds2D());
            }
        }

        float bbwidth = Rectangle.boundingBoxOf(horizontalTextChunks).width;
        List<TextBlock> rv = new ArrayList<>(horizontalLines.size());
        for (int i = 0; i < horizontalLines.size();) {
            line = horizontalLines.get(i);
            bound = horizontalLineBounds.get(i);
            if (bound.width / bbwidth > 0.9 && allSameChar(line.getElements())) {
                horizontalLines.remove(i);
                horizontalLineBounds.remove(i);
            } else {
                rv.add(removeRepeatedCharacters(line, ' ', 3));
                i++;
            }
        }
        rv.addAll(verticalLines);

        rv.sort(Comparator.comparing(textBlock -> textBlock.getBounds2D().getMinY()));
        return rv;
    }


    public static List<TextBlock> groupByLinesWithStream(List<TextChunk> textChunks) {
        List<TextBlock> lines = new ArrayList<>();

        if (textChunks.size() == 0) {
            return lines;
        }

        float bbwidth = Rectangle.boundingBoxOf(textChunks).width;

        TextBlock l = new TextBlock(textChunks.get(0));
        lines.add(l);

        TextBlock lastLine = lines.get(lines.size() - 1);
        Rectangle lastBound = new Rectangle(lastLine.getBounds2D());
        for (int i = 1; i < textChunks.size(); i++) {
            TextChunk te = textChunks.get(i);
            if (lastBound.verticalOverlapRatio(te) < SAME_ROW_OVERLAP) {
                if (lastBound.width / bbwidth > 0.9 && allSameChar(lastLine.getElements())) {
                    lines.remove(lines.size() - 1);
                }
                lastLine = new TextBlock(te);
                lines.add(lastLine);
            } else {
                lastLine.addElement(te);
            }
            lastBound.setRect(lastLine.getBounds2D());
        }

        if (lastBound.width / bbwidth > 0.9 && allSameChar(lastLine.getElements())) {
            lines.remove(lines.size() - 1);
        }

        List<TextBlock> rv = new ArrayList<>(lines.size());

        for (TextBlock line: lines) {
            rv.add(removeRepeatedCharacters(line, ' ', 3));
        }

        return rv;
    }
}
