package com.abcft.pdfextract.core.table.detectors;

import java.awt.Color;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.abcft.pdfextract.core.model.*;
import com.abcft.pdfextract.core.table.*;
import com.abcft.pdfextract.core.table.extractors.BitmapPageExtractionAlgorithm;
import com.abcft.pdfextract.util.FloatUtils;
import com.abcft.pdfextract.util.TextUtils;
import com.google.common.collect.Lists;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.math3.util.FastMath;


public class TableRegionDetectionUtils {
    private static final Pattern NUMBER_PREFIX_RE = Pattern.compile("(((\\s*-?)[\\d\\s\\.,-]*\\d$)|((\\s*-?)[\\d\\s\\.]+%$))");

    public static boolean hasLargeGapTextBlock(ContentGroupPage page, Rectangle rectangle, float gap, int minLargeGapNum) {
        List<TextChunk> textChunks = page.getTextChunks(rectangle);
        if (textChunks.isEmpty()) {
            return false;
        }
        List<TextBlock> textLines = TextMerger.collectByRows(TextMerger.groupByBlock(textChunks, page.getHorizontalRulings()
                , page.getVerticalRulings()));
        return hasLargeGapTextBlock(textLines, gap, minLargeGapNum);
     }

     public static boolean hasLargeGapTextBlock(List<TextBlock> textLines, float gap, int minLargeGapNum) {
        if (textLines == null || textLines.isEmpty()) {
            return false;
        }
         for (TextBlock checkLine : textLines) {
             if (isLargeGap(checkLine, gap, minLargeGapNum)) {
                 return true;
             }
         }
         return false;
     }

     public static boolean hasNoGapLongTextBlock(List<TextBlock> textLines, float minLength, float gap, int minLargeGapNum) {
         if (textLines == null || textLines.isEmpty()) {
             return false;
         }
         for (TextBlock checkLine : textLines) {
             if (!isLargeGap(checkLine, gap, minLargeGapNum) && checkLine.getWidth() > minLength) {
                 return true;
             }
         }
         return false;
     }

    public static boolean isLargeGap(TextBlock checkLine, float gap, int minLargeGapNum) {
        if (checkLine.getSize() < 2) {
            return false;
        }
        return isLargeGap(checkLine.getElements(), gap, minLargeGapNum);
    }

    public static boolean isLargeGap(List<? extends Rectangle2D> rectangles, float gap, int minLargeGapNum) {
        if (rectangles.size() < 2) {
            return false;
        }

        //优先对文本行进行垂直投影判断
        List<Rectangle> projectRectangles = calVerticalProjection(rectangles);
        if (projectRectangles.size() < 2) {
            return false;
        }
        projectRectangles.sort(Comparator.comparing(Rectangle2D::getMinX));
        int largeCount = 0;
        for (int i = 0; i < projectRectangles.size() - 1; i++) {
            if (projectRectangles.get(i + 1).getMinX() - projectRectangles.get(i).getMaxX() >= gap) {
                largeCount++;
            }
        }
        return largeCount >= minLargeGapNum;
    }

    public static boolean hasLongStr(TextBlock checkLine, float len, int minLongStrNum) {
        if (checkLine == null) {
            return false;
        }
        int longCount = 0;
        for (TextChunk tc : checkLine.getElements()) {
            if (tc.getWidth() > len) {
                longCount++;
            }
        }
        return longCount >= minLongStrNum;
    }

    public static boolean containAnyText(List<? extends TextContainer> texts, Pattern pattern) {
        for (TextContainer textChunk : texts) {
            String text = textChunk.getText().trim();
            if (!StringUtils.isEmpty(text) && pattern.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    public static boolean matchAnyText(List<? extends TextContainer> texts, Pattern pattern) {
        if (texts == null || texts.isEmpty()) {
            return false;
        }

        for (TextContainer textChunk : texts) {
            String text = textChunk.getText().trim().toLowerCase();
            if (!StringUtils.isEmpty(text) && pattern.matcher(text).matches()) {
                return true;
            }
        }
        return false;
    }

    public static boolean matchAllText(List<? extends TextContainer> texts, Pattern pattern) {
        if (texts == null || texts.isEmpty()) {
            return false;
        }

        for (TextContainer textChunk : texts) {
            String text = textChunk.getText().trim().toLowerCase();
            if (StringUtils.isEmpty(text)) {
                continue;
            }
            if (!pattern.matcher(text).matches()) {
                return false;
            }
        }
        return true;
    }

    public static boolean isAllNumberStr(List<? extends TextContainer> texts) {
        return matchAllText(texts, NUMBER_PREFIX_RE);
    }

    public static boolean hasCJKEStr(String s) {
        return TextUtils.containsCJK(s) || TextUtils.containsEN(s);
    }

    public static boolean hasCJKEStr(List<TextChunk> textChunkList) {
        for (TextChunk tc : textChunkList) {
            if (hasCJKEStr(tc.getText().trim())) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasNumberStr(List<TextChunk> textChunkList) {
        for (TextChunk tc : textChunkList) {
            if (isNumberStr(tc.getText().trim())) {
                return true;
            }
        }
        return false;
    }

    public static boolean isNumberStr(String s) {
        if (NUMBER_PREFIX_RE.matcher(s.trim()).matches()) {
            return true;
        }
        return false;
    }

    public static int countNumberStr(List<TextChunk> textChunkList) {
        int num = 0;
        for (TextChunk tc : textChunkList) {
            if (isNumberStr(tc.getText())) {
                num++;
            }
        }
        return num;
    }

    public static int countNumberStr(String [] strings) {
        int num = 0;
        for (String str : strings) {
            if (isNumberStr(str)) {
                num++;
            }
        }
        return num;
    }

    public static float numberStrRatio(List<TextChunk> textChunkList) {
        if (textChunkList.isEmpty()) {
            return 0;
        }
        return countNumberStr(textChunkList) * 1.f / textChunkList.size();
    }

    public static boolean isBlankChunks(List<TextChunk> chunks) {
        for (TextChunk tc : chunks) {
            if (StringUtils.isNotBlank(tc.getText().trim())) {
                return false;
            }
        }
        return true;
    }

    public static boolean stringEqualsIgnoreWhitespace(String str1, String str2) {
        String stra = str1 != null ? str1.trim().replaceAll("\\s", "").replaceAll("　", "") : null;
        String strb = str2 != null ? str2.trim().replaceAll("\\s", "").replaceAll("　", "") : null;
        return StringUtils.equals(stra, strb);
    }

    public static boolean hasTextBlockWithinShape(ContentGroupPage page, Rectangle rc, List<TextBlock> textBlockList) {
        if (rc.getWidth() <= 0 || rc.getHeight() <= 0 || textBlockList.isEmpty()) {
            return false;
        }

        return getTextBlockWithinShape(page, rc, textBlockList).size() >= 1;
    }

    public static List<TextBlock> getTextBlockWithinShape(ContentGroupPage page, Rectangle rc, List<TextBlock> textBlockList) {
        if (rc.getWidth() <= 0 || rc.getHeight() <= 0) {
            return new ArrayList<>();
        }

        List<TextBlock> resultTextBlockLists = new ArrayList<>();
        if (textBlockList == null || textBlockList.isEmpty()) {
            List<TextChunk> textChunksWithinRC = page.getMutableTextChunks(rc);
            if (textChunksWithinRC.isEmpty()) {
                return new ArrayList<>();
            }
            resultTextBlockLists = TextMerger.collectByRows(TextMerger.groupByBlock(textChunksWithinRC, page.getHorizontalRulings(), page.getVerticalRulings()));
        } else {
            for (TextBlock tb : textBlockList) {
                if ((tb.getTop() >= rc.getTop() || FloatUtils.feq(rc.getTop(), tb.getTop(), 0.5 * page.getAvgCharHeight()))
                        && (tb.getBottom() <= rc.getBottom() || FloatUtils.feq(rc.getBottom(), tb.getBottom(), 0.5 * page.getAvgCharHeight()))
                        && rc.isHorizontallyOverlap(new Rectangle(tb.getBounds2D()))) {
                    double minY = FastMath.max(rc.getTop(), tb.getTop());
                    double maxY = FastMath.min(rc.getBottom(), tb.getBottom());
                    if ((maxY - minY) / (tb.getBounds2D().getHeight()) > 0.6) {
                        resultTextBlockLists.add(tb);
                    }
                }
            }
        }

        return resultTextBlockLists;
    }

    public static int calTableColNum(ContentGroupPage page, Rectangle rc, List<TextBlock> textBlocks) {
        if (textBlocks == null || textBlocks.isEmpty()) {
            return calTableColNum(page, rc);
        } else {
            return BitmapPageExtractionAlgorithm.calcColumnPositions(page, textBlocks).size();
        }
    }

    public static int calTableColNum(ContentGroupPage page, Rectangle rc) {
        return BitmapPageExtractionAlgorithm.calcColumnPositions(page, getTextBlockWithinShape(page, rc, page.getTextLines())).size();
    }

    public static int calStrMatchNum(Pattern pattern, String totalStr) {
        if (StringUtils.isBlank(totalStr)) {
            return 0;
        }
        Matcher matcher = pattern.matcher(totalStr);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    public static int calStrMatchNum(Pattern pattern, List<TextChunk> textChunks) {
        int count = 0;
        for (TextChunk tc : textChunks) {
            count += calStrMatchNum(pattern, tc.getText().trim().toLowerCase());
        }
        return count;
    }

    public static boolean isColumnAlign(List<Rectangle> baseColumns, List<Rectangle> otherColumns) {
        if (baseColumns.isEmpty() || otherColumns.isEmpty()) {
            return false;
        }
        int alignNum = 0;
        for (Rectangle base : baseColumns) {
            for (Rectangle other : otherColumns) {
                if (base.horizontallyOverlapRatio(other) > 0.5) {
                    alignNum++;
                    break;
                }
            }
        }
        if (alignNum > FastMath.min(baseColumns.size(), otherColumns.size()) * 0.6f) {
            return true;
        }
        return false;
    }

    //将文本块按行组合输出
    public static List<TextChunk> splitChunkByRow(List<TextChunk> textChunks) {
        if (textChunks == null || textChunks.isEmpty()) {
            return new ArrayList<>();
        }
        if (textChunks.size() < 2) {
            return textChunks;
        }
        List<TextChunk> newTextChunks = null;
        for (TextChunk tc : textChunks) {
            if (newTextChunks == null) {
                newTextChunks = new ArrayList<>();
                newTextChunks.add(new TextChunk(tc));
                continue;
            }
            TextChunk last = newTextChunks.get(newTextChunks.size() - 1);
            if (FloatUtils.feq(last.getCenterY(), tc.getCenterY(), 0.8 * FastMath.min(last.getAvgCharHeight(), tc.getAvgCharHeight()))) {
                last = last.merge(tc);
            } else {
                newTextChunks.add(new TextChunk(tc));
            }
        }
        return newTextChunks;
    }

    public static float calFontDiffRatio(List<TextChunk> textChunks) {
        if (textChunks == null || textChunks.size() < 2) {
            return 1;
        }

        double maxChunkHeight = 0;
        double minChunkHeight = Float.MAX_VALUE;
        for (TextChunk tc : textChunks) {
            if (tc.getElements().size() <= 2) {
                continue;
            }
            double tcHeight = tc.getElements().stream().mapToDouble(TextElement::getHeight).average().orElse(0);
            if (tcHeight > maxChunkHeight) {
                maxChunkHeight = tcHeight;
            }
            if (tcHeight < minChunkHeight) {
                minChunkHeight = tcHeight;
            }
        }

        if (minChunkHeight < 3 || maxChunkHeight > 50) {
            return 1;
        } else {
            return (float) (maxChunkHeight / minChunkHeight);
        }
    }

    public static boolean equalsAnyChar(char a, char[] chars) {
        if (ArrayUtils.isNotEmpty(chars)) {
            for(char b : chars) {
                if (a == b) {
                    return true;
                }
            }
        }
        return false;
    }

    public static int countLargeGap(HashMap<Point2D, java.lang.Float> gapMap, float gap) {
        if (gapMap == null || gapMap.isEmpty()) {
            return 0;
        }

        int largeCount = 0;
        for (Float gapValue : gapMap.values()) {
            if (gapValue > gap) {
                largeCount++;
            }
        }
        return largeCount;
    }

    public static HashMap<Point2D, java.lang.Float> calVerticalProjectionGap(ContentGroupPage page, Rectangle cell, boolean considerRuling) {
        Rectangle initRect = cell.rectReduce(2.0, 2.0, page.getWidth(), page.getHeight());
        List<TextChunk> textChunkTemps = page.getTextObjects(initRect);
        filterBlankChunk(textChunkTemps);
        if (textChunkTemps.isEmpty()) {
            return new HashMap<>();
        }

        List<Rectangle> rectTemps = textChunkTemps.stream().map(Rectangle::new).collect(Collectors.toList());
        if (considerRuling) {
            for (Ruling rul : page.getHorizontalRulings()) {
                if (rul.intersects(initRect)) {
                    rectTemps.add(new Rectangle(rul.getLeft(), rul.getTop(), rul.getWidth(), rul.getHeight()));
                }
            }
        }

        return calVerticalProjectionGap(rectTemps);
    }

    public static HashMap<Point2D, java.lang.Float> calVerticalProjectionGap(List<? extends Rectangle2D> rectangles) {
        List<Rectangle> projectRectangles = calVerticalProjection(rectangles);
        if (projectRectangles.size() < 2) {
            return new HashMap<>();
        }

        projectRectangles.sort(Comparator.comparing(Rectangle2D::getMinX));
        HashMap<Point2D, java.lang.Float> gapMap = new HashMap<>();
        for (int i = 0; i < projectRectangles.size() - 1; i++) {
            if (projectRectangles.get(i + 1).getMinX() < projectRectangles.get(i).getMaxX()) {
                continue;
            }
            gapMap.put(new Point2D.Float((float)projectRectangles.get(i).getMaxX(), (float)projectRectangles.get(i + 1).getMinX())
                    , (float)(projectRectangles.get(i + 1).getMinX() - projectRectangles.get(i).getMaxX()));
        }

        return gapMap;
    }

    public static List<Rectangle> calVerticalProjection(List<? extends Rectangle2D> rectangles) {
        if (rectangles.isEmpty()) {
            return new ArrayList<>();
        }

        List<Rectangle2D> newRectangles = new ArrayList<>(rectangles.size());
        for (Rectangle2D rect : rectangles) {
            newRectangles.add(new Rectangle(rect));
        }
        newRectangles.sort(Comparator.comparing(Rectangle2D::getMinX));
        HashSet<Integer> removeIdxSet = new HashSet<>();
        for (int i = 0; i < newRectangles.size() - 1; ) {
            if (removeIdxSet.contains(i)) {
                i++;
                continue;
            }
            Rectangle2D rc1 = newRectangles.get(i);
            boolean mergeFlag = false;
            for (int j = i + 1; j < newRectangles.size(); j++) {
                if (removeIdxSet.contains(j)) {
                    continue;
                }
                Rectangle2D rc2 = newRectangles.get(j);
                if (Math.min(rc1.getMaxX(), rc2.getMaxX()) - Math.max(rc1.getMinX(), rc2.getMinX()) > 0
                        || FloatUtils.feq(rc1.getMaxX(), rc2.getMinX(), 1.0)
                        || FloatUtils.feq(rc2.getMaxX(), rc1.getMinX(), 1.0)) {
                    rc1.setRect(rc1.createUnion(rc2));
                    removeIdxSet.add(j);
                    mergeFlag = true;
                }
            }
            if (!mergeFlag) {
                i++;
            }
        }

        List<Rectangle> projectRectangles = new ArrayList<>();
        for (int i = 0; i < newRectangles.size(); i++) {
            if (!removeIdxSet.contains(i)) {
                projectRectangles.add(new Rectangle(newRectangles.get(i)));
            }
        }

        projectRectangles.sort(Comparator.comparing(Rectangle2D::getMinX));
        return projectRectangles;


        /*if (rectangles.isEmpty()) {
            return rectangles;
        }

        Rectangle bound = Rectangle.boundingBoxOf(rectangles);
        rectangles.sort(Comparator.comparing(Rectangle::getLeft));
        for (int i = 0; i < rectangles.size() - 1; ) {
            Rectangle rc1 = rectangles.get(i);
            boolean mergeFlag = false;
            for (int j = i + 1; j < rectangles.size(); j++) {
                Rectangle rc2 = rectangles.get(j);
                if (rc1.isHorizontallyOverlap(rc2) || FloatUtils.feq(rc1.getRight(), rc2.getLeft(), 1.0)
                        || FloatUtils.feq(rc2.getRight(), rc1.getLeft(), 1.0)) {
                    rc1.setLeft(FastMath.min(rc1.getLeft(), rc2.getLeft()));
                    rc1.setRight(FastMath.max(rc1.getRight(), rc2.getRight()));
                    rc2.markDeleted();
                    mergeFlag = true;
                }
            }
            rectangles.removeIf(Rectangle::isDeleted);
            if (!mergeFlag) {
                i++;
            }
        }

        for (Rectangle rect : rectangles) {
            rect.setRect(rect.getLeft(), bound.getTop(), rect.getWidth(), bound.getHeight());
        }
        return rectangles;*/
    }

    /**
     * 计算rect的水平坐标范围内字符投影宽度
     * @param projects：投影分段值
     * @return
     */
    public static float getNoRepoProjectWidth(List<Rectangle> projects, Rectangle rect) {
        if (projects.isEmpty()) {
            return 0;
        }

        float noRepoWidth = 0;
        for (Rectangle project : projects) {
            double gap = FastMath.min(project.getMaxX(), rect.getMaxX()) - FastMath.max(project.getMinX(), rect.getMinX());
            noRepoWidth += gap > 0 ? gap : 0;
        }
        return noRepoWidth;
    }

    public static HashMap<Point2D, java.lang.Float> calHorizontalProjectionGap(ContentGroupPage page, Rectangle cell, boolean considerRuling) {
        Rectangle initRect = cell.rectReduce(2.0, 2.0, page.getWidth(), page.getHeight());
        List<TextChunk> textChunkTemps = page.getMutableTextObjects(initRect);
        filterBlankChunk(textChunkTemps);
        if (textChunkTemps.isEmpty()) {
            return new HashMap<>();
        }

        List<Rectangle> rectTemps = textChunkTemps.stream().map(Rectangle::new).collect(Collectors.toList());
        if (considerRuling) {
            for (Ruling rul : page.getVerticalRulings()) {
                if (rul.intersects(initRect)) {
                    rectTemps.add(new Rectangle(rul.getLeft(), rul.getTop(), rul.getWidth(), rul.getHeight()));
                }
            }
        }

        return calHorizontalProjectionGap(rectTemps);
    }

    public static HashMap<Point2D, java.lang.Float> calHorizontalProjectionGap(List<? extends Rectangle2D> rectangles) {
        if (rectangles.isEmpty()) {
            return new HashMap<>();
        }

        List<Rectangle2D> newRectangles = new ArrayList<>(rectangles.size());
        for (Rectangle2D rect : rectangles) {
            newRectangles.add(new Rectangle(rect));
        }

        newRectangles.sort(Comparator.comparing(Rectangle2D::getMinY));
        HashSet<Integer> removeIdxSet = new HashSet<>();
        for (int i = 0; i < newRectangles.size() - 1; ) {
            if (removeIdxSet.contains(i)) {
                i++;
                continue;
            }
            Rectangle2D rc1 = newRectangles.get(i);
            boolean mergeFlag = false;
            for (int j = i + 1; j < newRectangles.size(); j++) {
                if (removeIdxSet.contains(j)) {
                    continue;
                }
                Rectangle2D rc2 = newRectangles.get(j);
                if (Math.min(rc1.getMaxY(), rc2.getMaxY()) - Math.max(rc1.getMinY(), rc2.getMinY()) > 0
                        || FloatUtils.feq(rc1.getMaxY(), rc2.getMinY(), 1.0)
                        || FloatUtils.feq(rc2.getMaxY(), rc1.getMinY(), 1.0)) {
                    rc1.setRect(rc1.createUnion(rc2));
                    removeIdxSet.add(j);
                    mergeFlag = true;
                }
            }
            if (!mergeFlag) {
                i++;
            }
        }

        List<Rectangle2D> projectRectangles = new ArrayList<>();
        for (int i = 0; i < newRectangles.size(); i++) {
            if (!removeIdxSet.contains(i)) {
                projectRectangles.add(newRectangles.get(i));
            }
        }
        if (projectRectangles.size() < 2) {
            return new HashMap<>();
        }

        projectRectangles.sort(Comparator.comparing(Rectangle2D::getMinY));
        HashMap<Point2D, java.lang.Float> gapMap = new HashMap<>();
        for (int i = 0; i < projectRectangles.size() - 1; i++) {
            if (projectRectangles.get(i + 1).getMinY() < projectRectangles.get(i).getMaxY()) {
                continue;
            }
            gapMap.put(new Point2D.Float((float)projectRectangles.get(i).getMaxY(), (float)projectRectangles.get(i + 1).getMinY())
                    , (float)(projectRectangles.get(i + 1).getMinY() - projectRectangles.get(i).getMaxY()));
        }

        return gapMap;
    }

    public static List<List<TextChunk>> analysisTextBlock(TextBlock textBlock) {
        if (textBlock == null || textBlock.getSize() == 0) {
            return new ArrayList<>();
        }

        List<List<TextChunk>> rowTextChunks = new ArrayList<>();
        textBlock.getElements().sort(Comparator.comparing(TextChunk::getTop));
        for (TextChunk tc : textBlock.getElements()) {
            if (StringUtils.isBlank(tc.getText().trim())) {
                continue;
            }
            if (rowTextChunks.isEmpty()) {
                rowTextChunks.add(Lists.newArrayList(tc));
                continue;
            }

            List<TextChunk> lastRow = rowTextChunks.get(rowTextChunks.size() - 1);
            if (Rectangle.boundingBoxOf(lastRow).verticalOverlapRatio(tc) > 0.5) {
                lastRow.add(tc);
            } else {
                rowTextChunks.add(Lists.newArrayList(tc));
            }
        }

        for (List<TextChunk> rows : rowTextChunks) {
            rows.sort(Comparator.comparing(TextChunk::getLeft));
        }
        return rowTextChunks;
    }

    public boolean isBigIdxDifference(List<TextChunk> searchedTextChunks, int diff) {
        searchedTextChunks.sort(Comparator.comparing(TextChunk::getCenterY));
        if (searchedTextChunks.isEmpty() || searchedTextChunks.size() < 2) {
            return false;
        }
        for (int i = 0; i < searchedTextChunks.size() - 1; i++) {
            if (FastMath.abs(searchedTextChunks.get(i + 1).getGroupIndex() - searchedTextChunks.get(i).getGroupIndex()) >= diff) {
                return true;
            }
        }
        return false;
    }

    public static void filterBlankChunk(List<TextChunk> textChunks) {
        if (textChunks == null || textChunks.isEmpty()) {
            return;
        }

        for (int i = 0; i < textChunks.size();) {
            TextChunk textChunk = textChunks.get(i);
            if (Objects.equals(textChunk.getText(), "") ||
                    Objects.equals(textChunk.getText(), " ")) {
                textChunks.remove(i);
            } else {
                //trim string(blank,tab,etc.)
                List<TextElement> cellTextElements = textChunk.getElements();
                List<TextElement> newCellTextElements = new ArrayList<>();
                if (!cellTextElements.isEmpty()) {
                    int startIdx = -1;
                    int endIdx = -1;
                    for (int j = 0; j < cellTextElements.size(); j++) {
                        if (!StringUtils.isBlank(cellTextElements.get(j).getText().replace('\u00a0', ' '))) {
                            startIdx = j;
                            break;
                        }
                    }
                    for (int k = cellTextElements.size() - 1; k >= 0; k--) {
                        if (!StringUtils.isBlank(cellTextElements.get(k).getText().replace('\u00a0', ' '))) {
                            endIdx = k;
                            break;
                        }
                    }
                    if (startIdx != -1 && endIdx != -1) {
                        newCellTextElements.addAll(cellTextElements.subList(startIdx,endIdx + 1));
                    }
                }

                if (!newCellTextElements.isEmpty()) {
                    textChunks.set(i, new TextChunk(newCellTextElements));
                    i++;
                } else {
                    textChunks.remove(i);
                }
            }
        }
    }

    public static void filterBlankElement(List<TextElement> elements) {
        if (elements == null || elements.isEmpty()) {
            return;
        }

        elements.removeIf(element -> StringUtils.isBlank(element.getText()));
    }

    public static List<Ruling> getOneRulingEveryGroup(List<Ruling> rulings, PositionType positionType) {
        if (rulings.size() < 2) {
            return rulings;
        }

        List<Ruling> resultRulings = new ArrayList<>();
        if (positionType == PositionType.LEFT) {
            rulings.sort(Comparator.comparing(Ruling::getTop));
            List<List<Ruling>> groups = new ArrayList<>();
            for (Ruling ruling : rulings) {
                if (groups.isEmpty()) {
                    groups.add(Lists.newArrayList(ruling));
                    continue;
                }

                List<Ruling> lastGroup = groups.get(groups.size() - 1);
                Ruling last = lastGroup.get(lastGroup.size() - 1);
                if (FloatUtils.feq(last.getTop(), ruling.getTop(), 0.5)) {
                    lastGroup.add(ruling);
                } else {
                    groups.add(Lists.newArrayList(ruling));
                }
            }

            for (List<Ruling> group : groups) {
                if (group.isEmpty()) {
                    continue;
                } else if (group.size() == 1) {
                    resultRulings.add(group.get(0));
                } else {
                    group.sort(Comparator.comparing(Ruling::getLeft));
                    resultRulings.add(group.get(0));
                }
            }
            return resultRulings;
        } else if (positionType == PositionType.RIGHT) {
            rulings.sort(Comparator.comparing(Ruling::getTop));
            List<List<Ruling>> groups = new ArrayList<>();
            for (Ruling ruling : rulings) {
                if (groups.isEmpty()) {
                    groups.add(Lists.newArrayList(ruling));
                    continue;
                }

                List<Ruling> lastGroup = groups.get(groups.size() - 1);
                Ruling last = lastGroup.get(lastGroup.size() - 1);
                if (FloatUtils.feq(last.getTop(), ruling.getTop(), 0.5)) {
                    lastGroup.add(ruling);
                } else {
                    groups.add(Lists.newArrayList(ruling));
                }
            }

            for (List<Ruling> group : groups) {
                if (group.isEmpty()) {
                    continue;
                } else if (group.size() == 1) {
                    resultRulings.add(group.get(0));
                } else {
                    group.sort(Comparator.comparing(Ruling::getRight));
                    resultRulings.add(group.get(group.size() - 1));
                }
            }
            return resultRulings;
        } else {
            return rulings;
        }
    }

    public static List<Ruling> getLongRulings(List<Ruling> rulings, float minLength) {
        List<Ruling> longRulings = new ArrayList<>();
        for (Ruling rul : rulings) {
            if (rul.length() >= minLength) {
                longRulings.add(rul);
            }
        }
        return longRulings;
    }

    public enum PositionType {
        LEFT, RIGHT, TOP, BOTTOM
    }

    public enum DirectionType {
        HORIZONTAL, VERTICAL
    }

    public static Ruling getNeighborRuling(ContentGroupPage page, PositionType positionType, DirectionType directionType, double minRulingLength, double thres, Rectangle rect) {
        Ruling ruling = getNeighborRuling(page, positionType, directionType, minRulingLength, thres);
        if (rect != null && ruling != null) {
            if ((directionType == DirectionType.VERTICAL && rect.isVerticallyOverlap(ruling.toRectangle()))
                    || (directionType == DirectionType.HORIZONTAL && rect.isHorizontallyOverlap(ruling.toRectangle()))) {
                return ruling;
            }
        }
        return null;
    }

    public static Ruling getNeighborRuling(ContentGroupPage page, PositionType positionType, DirectionType directionType, double minRulingLength, double thres) {
        if (directionType == DirectionType.VERTICAL) {
            List<Ruling> rulings = new ArrayList<>(page.getVerticalRulings());
            if (positionType == PositionType.LEFT) {
                rulings.sort(Comparator.comparing(Ruling::getLeft));
                Collections.reverse(rulings);
                for (Ruling rul : rulings) {
                    if (rul.getCenterX() < thres && rul.length() > minRulingLength) {
                        return rul;
                    }
                }
            } else if (positionType == PositionType.RIGHT) {
                rulings.sort(Comparator.comparing(Ruling::getLeft));
                for (Ruling rul : rulings) {
                    if (rul.getCenterX() > thres && rul.length() > minRulingLength) {
                        return rul;
                    }
                }
            }
        } else {
            List<Ruling> rulings = new ArrayList<>(page.getHorizontalRulings());
            if (positionType == PositionType.TOP) {
                rulings.sort(Comparator.comparing(Ruling::getTop));
                Collections.reverse(rulings);
                for (Ruling rul : rulings) {
                    if (rul.getCenterY() < thres && rul.length() > minRulingLength) {
                        return rul;
                    }
                }
            } else if (positionType == PositionType.BOTTOM) {
                rulings.sort(Comparator.comparing(Ruling::getTop));
                for (Ruling rul : rulings) {
                    if (rul.getCenterY() > thres && rul.length() > minRulingLength) {
                        return rul;
                    }
                }
            }
        }
        return null;
    }

    public static List<Ruling> getRulingBetween(ContentGroupPage page, DirectionType directionType, double minRulingLength, double thresLow, double thresHigh) {
        List<Ruling> betweenRulings = new ArrayList<>();
        if (directionType == DirectionType.VERTICAL) {
            List<Ruling> rulings = new ArrayList<>(page.getVerticalRulings());
            rulings.sort(Comparator.comparing(Ruling::getLeft));
            for (Ruling rul : rulings) {
                if (rul.getCenterX() > thresLow && rul.getCenterX() < thresHigh && rul.length() > minRulingLength) {
                    betweenRulings.add(rul);
                }
            }
        } else {
            List<Ruling> rulings = new ArrayList<>(page.getHorizontalRulings());
            rulings.sort(Comparator.comparing(Ruling::getTop));
            for (Ruling rul : rulings) {
                if (rul.getCenterY() > thresLow && rul.getCenterY() < thresHigh && rul.length() > minRulingLength) {
                    betweenRulings.add(rul);
                }
            }
        }
        return betweenRulings;
    }

    public static float getValidTextWidthInRegion(ContentGroupPage page, Rectangle processRect) {
        List<TextElement> textElements = page.getText(processRect);
        if (textElements.isEmpty()) {
            return 0;
        } else {
            return (float)(Rectangle.boundingBoxOf(textElements).getWidth());
        }
    }

    public static float calDutyRatio(List<TextChunk> rowChunks, float cellWidth) {
        if (rowChunks.isEmpty()) {
            return 0;
        }
        float textLength = 0;
        for (TextChunk tc : rowChunks) {
            textLength += tc.getWidth();
        }
        return textLength / cellWidth;
    }

    public static boolean isVerticalNeighborText(TextBlock one, TextBlock other, float heightThres) {
        if (one.intersects(other.getBounds2D())) {
            return true;
        }
        if (one.getTop() <= other.getTop()) {
            return FloatUtils.feq(one.getBottom(), other.getTop(), heightThres);
        } else {
            return FloatUtils.feq(other.getBottom(), one.getTop(), heightThres);
        }
    }

    //获取baseY上下一定范围的文本行
    public static List<TextBlock> getNeighborTextBlocks(List<TextBlock> textBlocks, float baseY, float heightThres) {
        List<TextBlock> tmpTextBlocks = new ArrayList<>();
        for (TextBlock tb : textBlocks) {
            if (tb.getCenterY() <= baseY && FloatUtils.feq(tb.getBottom(), baseY, heightThres)) {
                tmpTextBlocks.add(tb);
            } else if (tb.getCenterY() > baseY && FloatUtils.feq(tb.getTop(), baseY, heightThres)) {
                tmpTextBlocks.add(tb);
            }
        }
        return tmpTextBlocks;
    }

    public static TextBlock getBottomTextBlock(Rectangle regionBase, List<TextBlock> textBlocks, float baseY) {
        textBlocks.sort(Comparator.comparing(TextBlock::getTop));
        for (TextBlock tb : textBlocks) {
            if (tb.getCenterY() > baseY && regionBase.horizontallyOverlapRatio(tb.toRectangle()) > 0.5) {
                return tb;
            }
        }

        return null;
    }

    public static List<Rectangle> getColumnPositions(ContentGroupPage page, Rectangle rc, List<TextBlock> textBlocks) {
        if (textBlocks == null || textBlocks.isEmpty()) {
            List<TextChunk> textChunks = page.getMutableTextChunks(rc);
            if (textChunks.isEmpty()) {
                return new ArrayList<>();
            }
            return BitmapPageExtractionAlgorithm.calcColumnPositions(page
                    , TextMerger.collectByRows(TextMerger.groupByBlock(textChunks
                            , page.getHorizontalRulings(), page.getVerticalRulings())));
        } else {
            return BitmapPageExtractionAlgorithm.calcColumnPositions(page, textBlocks);
        }
    }

    public static boolean isHorizontalCenterNear(Rectangle2D one, Rectangle2D other) {
        float diff = (float)(one.getHeight() + other.getHeight()) / 8;
        if (FloatUtils.feq(one.getCenterY(), other.getCenterY(), diff)) {
            return true;
        }
        return false;
    }

    public static boolean isHorizontalCenterNear(List<TextChunk> tcLists) {
        if (tcLists.isEmpty()) {
            return false;
        }

        float diff = 0;
        for (TextChunk tc : tcLists) {
            diff += tc.getHeight();
        }
        diff /= (4 * tcLists.size());

        float avgCenterY = 0;
        for (TextChunk tc : tcLists) {
            avgCenterY += tc.getCenterY();
        }
        avgCenterY /= tcLists.size();
        for (TextChunk tc : tcLists) {
            if (!FloatUtils.feq(tc.getCenterY(), avgCenterY, diff)) {
                return false;
            }
        }
        return true;
    }

    public static List<List<TextChunk>> nearlyYCenterCollect(ContentGroupPage page, CandidateCell cellTemp) {
        List<TextChunk> textChunks = page.getMutableMergeChunks(cellTemp);
        textChunks.sort(Comparator.comparing(TextChunk::getCenterY));
        List<List<TextChunk>> groups = new ArrayList<>();
        for (TextChunk chunk : textChunks) {
            if (StringUtils.isBlank(chunk.getText().trim())) {
                continue;
            }
            if (groups.isEmpty()) {
                groups.add(Lists.newArrayList(chunk));
                continue;
            }

            List<TextChunk> lastRow = groups.get(groups.size() - 1);
            Rectangle lastRect = Rectangle.boundingBoxOf(lastRow);
            double delta = 0.25 * Math.min(lastRect.getHeight(), chunk.getAvgCharHeight());
            if (FloatUtils.feq(lastRect.getCenterY(), chunk.getCenterY(), delta)) {
                lastRow.add(chunk);
            } else {
                groups.add(Lists.newArrayList(chunk));
            }
        }

        for (List<TextChunk> rows : groups) {
            rows.sort(Comparator.comparing(TextChunk::getLeft));
        }
        return groups;
    }

    public static boolean nearlyEqualColor(Color one, Color other, int thresh) {
        if (one == null || other == null) {
            return false;
        }
        if (FloatUtils.feq(one.getRed(), other.getRed(), thresh)
                && FloatUtils.feq(one.getGreen(), other.getGreen(), thresh)
                && FloatUtils.feq(one.getBlue(), other.getBlue(), thresh)) {
            return true;
        }
        return false;
    }

    /**
     * 将文本行分为正常方向和非正常方向
     * @param textLines：原始文本行
     * @param normalTextBlocks：页面中占多数方向的文本行
     * @param unnormalTextBlocks：页面中占少数方向的文本行
     */
    public static void splitTextLine(List<TextBlock> textLines, List<TextBlock> normalTextBlocks, List<TextBlock> unnormalTextBlocks) {
        if (textLines.isEmpty()) {
            return;
        }

        int unnormalNum = 0;
        int validNum = 0;
        for (TextBlock tb : textLines) {
            if (tb.getElements().isEmpty()) {
                continue;
            }
            if (tb.getElements().get(0).isUnnormalDirection()) {
                unnormalNum++;
            }
            validNum++;
        }

        if (validNum - unnormalNum >= unnormalNum) {
            for (TextBlock tb : textLines) {
                if (tb.getElements().isEmpty()) {
                    continue;
                }
                if (tb.getElements().get(0).isUnnormalDirection()) {
                    unnormalTextBlocks.add(tb);
                } else {
                    normalTextBlocks.add(tb);
                }
            }
        } else {
            for (TextBlock tb : textLines) {
                if (tb.getElements().isEmpty()) {
                    continue;
                }
                if (tb.getElements().get(0).isUnnormalDirection()) {
                    normalTextBlocks.add(tb);
                } else {
                    unnormalTextBlocks.add(tb);
                }
            }
        }
    }

    public static List<TextChunk> getExcludeTextChunks(ContentGroupPage page, Rectangle fullRegion, List<? extends Rectangle2D> excludeRegions) {
        List<TextChunk> textChunks = new ArrayList<>();
        if (excludeRegions == null || excludeRegions.isEmpty()) {
            return page.getTextChunks(fullRegion);
        }

        for (TextChunk textChunk : page.getTextChunks(fullRegion)) {
            boolean intersectFlag = false;
            for (Rectangle2D rect : excludeRegions) {
                if (rect.intersects(textChunk)) {
                    intersectFlag = true;
                    break;
                }
            }
            if (!intersectFlag) {
                textChunks.add(textChunk);
            }
        }
        return textChunks;
    }


    /*PageColumnar pageColumnar = null;

    public static class PageColumnar {
        private boolean isPageColumnar = false;
        private float regionMinY, regionMaxY;
        private float columnarSegX;

        public void setPageColumnarStatus(boolean isPageColumnar) {
            this.isPageColumnar = isPageColumnar;
        }

        public boolean getPageColumnarStatus() {
            return this.isPageColumnar;
        }

        public void setRegionMinY(float regionMinY) {
            this.regionMinY = regionMinY;
        }

        public float getRegionMinY() {
            return this.regionMinY;
        }

        public void setRegionMaxY(float regionMaxY) {
            this.regionMaxY = regionMaxY;
        }

        public float getRegionMaxY() {
            return this.regionMaxY;
        }

        public void setColumnarSegX(float columnarSegX) {
            this.columnarSegX = columnarSegX;
        }

        public float getColumnarSegX() {
            return this.columnarSegX;
        }
    }

    public PageColumnar getPageColumnar(ContentGroupPage page) {
        if (this.pageColumnar != null) {
            return this.pageColumnar;
        }
        this.pageColumnar = new PageColumnar();
        return checkPageColumnar(page);
    }

    //是否分栏的判断
    private PageColumnar checkPageColumnar(ContentGroupPage page) {
        //暂时只对研报第一页处理
        if (this.pageColumnar == null) {
            this.pageColumnar = new PageColumnar();
        }
        if (page.getPageNumber() != 1 || page.getTextChunks().isEmpty() || page.getTextChunks().size() < 20) {
            return new PageColumnar();
        }

        Rectangle initialRect = new Rectangle(0, 0.25f * page.height, page.width, 0.5f * page.height);
        List<TextChunk> textChunkTemps = page.getTextObjects(initialRect);
        if (textChunkTemps.isEmpty()) {
            return new PageColumnar();
        }

        //投影方法判定
        List<Rectangle> rectTemps = textChunkTemps.stream().map(Rectangle::new).collect(Collectors.toList());
        for (Ruling rul : page.getHorizontalRulings()) {
            if (rul.intersects(initialRect)) {
                rectTemps.add(new Rectangle(rul.getLeft(), rul.getTop(), rul.getWidth(), rul.getHeight()));
            }
        }

        rectTemps.sort(Comparator.comparing(Rectangle::getLeft));
        for (int i = 0; i < rectTemps.size() - 1; ) {
            Rectangle rc1 = rectTemps.get(i);
            boolean mergeFlag = false;
            for (int j = i + 1; j < rectTemps.size(); j++) {
                Rectangle rc2 = rectTemps.get(j);
                if (rc1.isHorizontallyOverlap(rc2) || FloatUtils.feq(rc1.getRight(), rc2.getLeft(), 1.0)
                        || FloatUtils.feq(rc2.getRight(), rc1.getLeft(), 0)) {
                    rc1.setLeft(FastMath.min(rc1.getLeft(), rc2.getLeft()));
                    rc1.setRight(FastMath.max(rc1.getRight(), rc2.getRight()));
                    rc2.markDeleted();
                    mergeFlag = true;
                }
            }
            rectTemps.removeIf(Rectangle::isDeleted);
            if (!mergeFlag) {
                i++;
            }
        }

        rectTemps.sort(Comparator.comparing(Rectangle::getLeft));
        double minX = (page.getWidth() - page.getPaper().leftMarginInPt - page.getPaper().rightMarginInPt) / 4;
        double maxX = page.getWidth() - minX;
        for (Rectangle rc : rectTemps) {
            if (rc.getRight() < minX || rc.getLeft() > maxX) {
                rc.markDeleted();
            }
        }
        rectTemps.removeIf(Rectangle::isDeleted);

        List<Point2D> columnarSegGap = new ArrayList<>();
        if (rectTemps.size() == 1) {
            return new PageColumnar();
        } else {
            for (int i = 0; i < rectTemps.size() - 1; i++) {
                if (rectTemps.get(i + 1).getLeft() - rectTemps.get(i).getRight() >= FastMath.max(page.getAvgCharWidth() / 2, 3.0)) {
                    columnarSegGap.add(new Point2D.Float(rectTemps.get(i).getRight(), rectTemps.get(i + 1).getLeft()));
                }
            }
        }
        if (columnarSegGap.isEmpty()) {
            return new PageColumnar();
        }

        //根据分栏位置寻找区域
        for (Point2D gap : columnarSegGap) {
            float minY, maxY;
            Rectangle gapRect = new Rectangle((float)gap.getX() + 1.0f, 0, (float)(gap.getY() - gap.getX() - 2.0f), 0);
            List<Rectangle> invalidRegionFind = new ArrayList<>();
            invalidRegionFind.addAll(page.getTextChunks().stream().filter(tc -> tc.isHorizontallyOverlap(gapRect))
                    .map(tc -> new Rectangle(tc.x, tc.y, tc.width, tc.height)).collect(Collectors.toList()));
            invalidRegionFind.addAll(page.getHorizontalRulings().stream().filter(rul -> rul.isHorizontallyOverlap(gapRect))
                    .map(rul -> new Rectangle(rul.getLeft(), rul.getTop(), rul.getWidth(), rul.getHeight())).collect(Collectors.toList()));
            List<Rectangle> rectUp = new ArrayList<>();
            List<Rectangle> rectDown = new ArrayList<>();

            for (Rectangle rect : invalidRegionFind) {
                if (rect.getBottom() < 0.25f * page.height) {
                    rectUp.add(rect);
                } else if (rect.getTop() > 0.75f * page.height) {
                    rectDown.add(rect);
                }
            }
            rectUp.sort(Comparator.comparing(Rectangle::getTop));
            rectDown.sort(Comparator.comparing(Rectangle::getTop));
            if (rectUp.isEmpty()) {
                minY = 0;
            } else {
                minY = rectUp.get(rectUp.size() - 1).getBottom();
            }
            if (rectDown.isEmpty()) {
                maxY = page.height;
            } else {
                maxY = rectDown.get(0).getTop();
            }

            if (!this.pageColumnar.getPageColumnarStatus()) {
                this.pageColumnar.setRegionMinY(minY);
                this.pageColumnar.setRegionMaxY(maxY);
                this.pageColumnar.setPageColumnarStatus(true);
                this.pageColumnar.setColumnarSegX((float)((gap.getX() + gap.getY()) / 2));
            } else if ((maxY - minY) > (this.pageColumnar.getRegionMaxY() - this.pageColumnar.getRegionMinY())) {
                this.pageColumnar.setRegionMinY(minY);
                this.pageColumnar.setRegionMaxY(maxY);
                this.pageColumnar.setPageColumnarStatus(true);
                this.pageColumnar.setColumnarSegX((float)((gap.getX() + gap.getY()) / 2));
            }
        }
        if (!this.pageColumnar.getPageColumnarStatus()) {
            return new PageColumnar();
        }

        //如果有直线分界线则以该直线为分栏依据
        float basicPageHeight = page.height - page.getPaper().topMarginInPt - page.getPaper().bottomMarginInPt;
        List<Ruling> longRulings = new ArrayList<>();
        for (Ruling rul : page.getVerticalRulings()) {
            if (rul.getHeight() / basicPageHeight > 0.65) {
                longRulings.add(rul);
            }
        }
        for (Ruling rul : longRulings) {
            if (this.pageColumnar.getPageColumnarStatus() && FloatUtils.feq(rul.getLeft(), this.pageColumnar.getColumnarSegX(), 5.0)) {
                this.pageColumnar.setRegionMinY(rul.getTop());
                this.pageColumnar.setRegionMaxY(rul.getBottom());
                this.pageColumnar.setPageColumnarStatus(true);
                this.pageColumnar.setColumnarSegX(rul.getLeft());
                return this.pageColumnar;
            }
        }

        //如果没有找到线，则根据TJ顺序进一步判定
        textChunkTemps = page.getTextObjects(new Rectangle(0, this.pageColumnar.getRegionMinY(), page.width
                , this.pageColumnar.getRegionMaxY() - this.pageColumnar.getRegionMinY()));
        textChunkTemps = textChunkTemps.stream().filter(tc -> !tc.getText().trim().isEmpty()).collect(Collectors.toList());
        textChunkTemps.sort(Comparator.comparing(TextChunk::getCenterY));
        if (textChunkTemps.size() < 2) {
            return new PageColumnar();
        }
        boolean bigIdxDiffFindFlag = false;
        for (int i= 0; i < textChunkTemps.size() - 1; i++) {
            if (((textChunkTemps.get(i).getRight() < this.pageColumnar.getColumnarSegX() && textChunkTemps.get(i + 1).getLeft() > this.pageColumnar.getColumnarSegX())
                    || (textChunkTemps.get(i).getLeft() > this.pageColumnar.getColumnarSegX() && textChunkTemps.get(i + 1).getRight() < this.pageColumnar.getColumnarSegX()))
                    && this.isBigIdxDifference(Arrays.asList(textChunkTemps.get(i), textChunkTemps.get(i + 1)), 20)) {
                bigIdxDiffFindFlag = true;
                break;
            }
        }
        if (!bigIdxDiffFindFlag) {
            return new PageColumnar();
        }

        //边界修正前的区域边界
        float originalMinY = pageColumnar.getRegionMinY();
        float originalMaxY = pageColumnar.getRegionMaxY();

        //分栏上方或下方有未分栏的情况的修正:（１）边界收缩（２）边界扩大
        //收缩上边界
        boolean upFindFlag = false;
        for (int i = 0; i < page.getTextLines().size(); i++) {
            TextBlock tb = page.getTextLines().get(i);
            if (tb.getBottom() < this.pageColumnar.getRegionMinY() || tb.getTop() > this.pageColumnar.getRegionMaxY() || tb.getSize() < 2) {
                continue;
            } else if (tb.getBottom() < 0.5 * page.height){
                List<TextChunk> textChunksTemp = tb.getElements();
                for (int j = 0; j < tb.getSize() - 1; j++) {
                    if ((textChunksTemp.get(j).getRight() < this.pageColumnar.getColumnarSegX() && textChunksTemp.get(j + 1).getLeft() > this.pageColumnar.getColumnarSegX())
                            || (textChunksTemp.get(j + 1).getRight() < this.pageColumnar.getColumnarSegX() && textChunksTemp.get(j).getLeft() > this.pageColumnar.getColumnarSegX())) {
                        if (FastMath.abs(textChunksTemp.get(j).getGroupIndex() - textChunksTemp.get(j + 1).getGroupIndex()) < 5) {
                            if ((FastMath.min(j + 1, tb.getSize() - (j + 1)) <= 2 && FastMath.max(j + 1, tb.getSize() - (j + 1)) >= 4)
                                    || tb.getSize() < 6) {
                                this.pageColumnar.setRegionMinY((float)tb.getBounds2D().getMinY());
                                upFindFlag = true;
                                break;
                            } else {
                                continue;
                            }
                        } else {
                            this.pageColumnar.setRegionMinY((float)tb.getBounds2D().getMinY());
                            upFindFlag = true;
                            break;
                        }
                    }
                }
                if (upFindFlag) {
                    break;
                }
            }
        }
        //扩大上边界
        List<TextBlock> upTextBlocks = new ArrayList<>();
        for (TextBlock tb : page.getTextLines()) {
            if (tb.getBottom() < 0.5 * page.height && tb.getBottom() > originalMinY && tb.getTop() < pageColumnar.getRegionMinY()) {
                upTextBlocks.add(tb);
            }
        }
        if (!upTextBlocks.isEmpty()) {
            upTextBlocks.sort(Comparator.comparing(TextBlock::getTop));
            for (int i = upTextBlocks.size() - 1; i >= 0; i--) {
                Rectangle2D blockBound = upTextBlocks.get(i).getBounds2D();
                if (blockBound.getMaxX() < pageColumnar.getColumnarSegX() || blockBound.getMinX() > pageColumnar.getColumnarSegX()) {
                    pageColumnar.setRegionMinY((float)blockBound.getMinY());
                }
            }
        }

        //收缩下边界
        boolean downFindFlag = false;
        for (int i = page.getTextLines().size() - 1; i >= 0; i--) {
            TextBlock tb = page.getTextLines().get(i);
            if (tb.getBottom() < this.pageColumnar.getRegionMinY() || tb.getTop() > this.pageColumnar.getRegionMaxY() || tb.getSize() < 2) {
                continue;
            } else if (tb.getTop() > 0.5 * page.height){
                List<TextChunk> textChunksTemp = tb.getElements();
                for (int j = 0; j < tb.getSize() - 1; j++) {
                    if (textChunksTemp.get(j).getRight() < this.pageColumnar.getColumnarSegX() && textChunksTemp.get(j + 1).getLeft() > this.pageColumnar.getColumnarSegX()
                            || textChunksTemp.get(j + 1).getRight() < this.pageColumnar.getColumnarSegX() && textChunksTemp.get(j).getLeft() > this.pageColumnar.getColumnarSegX()) {
                        if (FastMath.abs(textChunksTemp.get(j).getGroupIndex() - textChunksTemp.get(j + 1).getGroupIndex()) < 5) {
                            if ((FastMath.min(j + 1, tb.getSize() - (j + 1)) <= 2 && FastMath.max(j + 1, tb.getSize() - (j + 1)) >= 4)
                                    || tb.getSize() < 6) {
                                this.pageColumnar.setRegionMaxY((float)tb.getBounds2D().getMaxY());
                                downFindFlag = true;
                                break;
                            } else {
                                continue;
                            }
                        } else {
                            this.pageColumnar.setRegionMaxY((float)tb.getBounds2D().getMaxY());
                            downFindFlag = true;
                            break;
                        }
                    }
                }
                if (downFindFlag) {
                    break;
                }
            }
        }
        //扩大下边界
        List<TextBlock> downTextBlocks = new ArrayList<>();
        for (TextBlock tb : page.getTextLines()) {
            if (tb.getTop() > 0.5 * page.height && tb.getBottom() > pageColumnar.getRegionMaxY() && tb.getTop() < originalMaxY) {
                downTextBlocks.add(tb);
            }
        }
        if (!downTextBlocks.isEmpty()) {
            downTextBlocks.sort(Comparator.comparing(TextBlock::getTop));
            for (int i = 0; i < downTextBlocks.size(); i++) {
                Rectangle2D blockBound = downTextBlocks.get(i).getBounds2D();
                if (blockBound.getMaxX() < pageColumnar.getColumnarSegX() || blockBound.getMinX() > pageColumnar.getColumnarSegX()) {
                    pageColumnar.setRegionMaxY((float)blockBound.getMaxY());
                }
            }
        }

        return this.pageColumnar;
    }*/



}
