package com.abcft.pdfextract.core.content;

import com.abcft.pdfextract.core.model.Rectangle;
import com.abcft.pdfextract.core.model.TextChunk;
import com.abcft.pdfextract.core.model.TextDirection;
import com.abcft.pdfextract.core.model.TextElement;
import com.abcft.pdfextract.util.FloatUtils;
import org.apache.commons.lang3.StringUtils;

import java.awt.geom.Area;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 文字块合并策略, 合并在一块的相邻的文字
 * Created by dhu on 2017/11/9.
 */
public class TextChunkMerger {

    static final Pattern CHART_TABLE_SPLIT_SERIAL = Pattern.compile("(^(图|表|圖|表|chart|Chart|figure|Figure|Table|table).*)");
    static final Pattern DATA_SOURCE_SERIAL = Pattern.compile("(^(数据来源|资料来源|資料來源|来源|來源|Source|source)[：:].*)");
    // 表示间距在多少个空格内的文字合并到一起
    protected final float mergeSpaceNo;

    public TextChunkMerger() {
        this(2);
    }
    public TextChunkMerger(float mergeSpaceNo) {
        this.mergeSpaceNo = mergeSpaceNo;
    }

    private static class SearchArea {
        Rectangle left;
        Rectangle right;
        Rectangle top;
        Rectangle bottom;
    }

    protected SearchArea getSearchArea(TextChunk textChunk, TextChunk textChunk2, boolean nextText) {
        boolean searchLeft = false;
        boolean searchTop = false;
        boolean searchRight = false;
        boolean searchBottom = false;
        TextDirection direction = textChunk.getDirection();
        // 如果另外一个文字的方向是确定的, 则只能往那个方向找
        if (direction == TextDirection.UNKNOWN && textChunk2.getDirection() != TextDirection.UNKNOWN) {
            direction = textChunk2.getDirection();
        }
        switch (direction) {
            case LTR:
                if (nextText) {
                    searchRight = true;
                } else {
                    searchLeft = true;
                }
                break;
            case RTL:
                if (nextText) {
                    searchLeft = true;
                } else {
                    searchRight = true;
                }
                break;
            case VERTICAL_UP:
                if (nextText) {
                    searchTop = true;
                } else {
                    searchBottom = true;
                }
                break;
            case VERTICAL_DOWN:
                if (nextText) {
                    searchBottom = true;
                } else {
                    searchTop = true;
                }
                break;
            case ROTATED:
                // 倾斜的文字通过计算倾斜角度来合并
                break;
            default:
                searchLeft = searchTop = searchRight = searchBottom = true;
                break;
        }
        // 数字一般不需要往竖直方向查找, 除非方向就是竖直方向的
        if (StringUtils.isNumeric(textChunk.getText().trim()) && StringUtils.isNumeric(textChunk2.getText().trim())) {
            TextDirection directionNumer = textChunk.getDirection();
            if (directionNumer != TextDirection.VERTICAL_UP && directionNumer != TextDirection.VERTICAL_DOWN) {
                searchTop = false;
                searchBottom = false;
            }
        }
        SearchArea area = new SearchArea();
        float delta = textChunk.getWidthOfSpace() * mergeSpaceNo / 2;
        if (TextUtils.isBulletStart(textChunk.getText()) && nextText) {
            // 项目符号后面的空格比较多, 增大查找的区域
            delta = delta + textChunk.getWidthOfSpace() * 10;
        }
        if (searchLeft) {
            area.left = new Rectangle(
                    textChunk.getMinX() - delta,
                    textChunk.getMinY(),
                    delta * 1.2,
                    textChunk.getHeight());
        }
        if (searchRight) {
            area.right = new Rectangle(
                    textChunk.getMaxX() - delta,
                    textChunk.getMinY(),
                    delta * 1.2,
                    textChunk.getHeight());
        }
        if (searchTop) {
            // 竖直方向查找范围减半
            area.top = new Rectangle(
                    textChunk.getMinX(),
                    textChunk.getMinY() - delta / 2,
                    textChunk.getWidth(),
                    delta);
        }
        if (searchBottom) {
            // 竖直方向查找范围减半
            area.bottom = new Rectangle(
                    textChunk.getMinX(),
                    textChunk.getMaxY() - delta / 2,
                    textChunk.getWidth(),
                    delta);
        }
        return area;
    }

    private static boolean intersects(Rectangle rectangle1, Rectangle rectangle2) {
        if (rectangle1 == null || rectangle2 == null) {
            return false;
        }
        return rectangle1.intersects(rectangle2);
    }

    protected boolean canMerge(TextChunk textChunk1, TextChunk textChunk2) {
        // 文字方向确定的文字块只能和同方向的合并, 方向不确定的可以和任意方向合并
        if (textChunk1.getDirection() != textChunk2.getDirection()
                && textChunk1.getDirection() != TextDirection.UNKNOWN
                && textChunk2.getDirection() != TextDirection.UNKNOWN) {
            return false;
        }
        // 旋转的文字只能和旋转的文字合并
        if (textChunk1.getDirection() == TextDirection.ROTATED
                && textChunk2.getDirection() != TextDirection.ROTATED) {
            return false;
        } else if (textChunk1.getDirection() != TextDirection.ROTATED
                && textChunk2.getDirection() == TextDirection.ROTATED) {
            return false;
        } else if (textChunk1.getDirection() == TextDirection.ROTATED
                && textChunk2.getDirection() == TextDirection.ROTATED) {
            // 旋转角度也要一致
            if (Math.abs(textChunk1.getFirstElement().getRotate() - textChunk2.getFirstElement().getRotate()) > 5) {
                return false;
            }
            // 计算倾斜角度
            if (textChunk1.intersects(textChunk2)) {
                double rotate = Math.toDegrees(Math.atan2(textChunk2.getCenterY()-textChunk1.getCenterY(),
                        textChunk2.getCenterX()-textChunk1.getCenterX()));
                if (Math.abs(rotate - textChunk1.getFirstElement().getRotate()) < 10) {
                    return true;
                } else {
                    return false;
                }
            }
        }
        SearchArea searchArea = getSearchArea(textChunk1, textChunk2, true);
        SearchArea searchArea2 = getSearchArea(textChunk2, textChunk1, false);
        if (intersects(searchArea.right, searchArea2.left)) {
            // LTR
            return true;
        } else if (intersects(searchArea.left, searchArea2.right)) {
            // RTL
            return true;
        } else if (intersects(searchArea.top, searchArea2.bottom)) {
            // VERTICAL_UP
            return true;
        } else if (intersects(searchArea.bottom, searchArea2.top)) {
            // VERTICAL_DOWN
            return true;
        } else {
            return false;
        }
    }

    public boolean canMergeVertical(TextChunk textChunk1, TextChunk textChunk2) {
        StringBuilder sb = new StringBuilder();
        sb.append(textChunk1.getText());
        sb.append(textChunk2.getText());
        if ((FloatUtils.feq(textChunk1.getLeft(), textChunk2.getLeft(), 1.) ||
                FloatUtils.feq(textChunk1.getRight(), textChunk2.getRight(), 1.))
                && FloatUtils.feq(textChunk1.getMostCommonFontSize(), textChunk2.getMostCommonFontSize(), 1.)
                && StringUtils.equals(textChunk1.getMostCommonFontName(), textChunk2.getMostCommonFontName())
                && (FloatUtils.flte(textChunk1.getRight(), 65.) || FloatUtils.fgte(textChunk1.getLeft(), 535.))
                && !TextUtils.isDigitalAndBlankOrAlphabet(sb.toString())
        ) {
            return true;
        }
        return false;
    }

    public List<TextChunk> merge(List<TextChunk> textChunks) {
//            textChunks.sort(Comparator.comparingInt(TextChunk::getGroupIndex));
        List<TextChunk> merged = new ArrayList<>();
        TextChunk prevText = null;
        for (TextChunk textChunk : textChunks) {
            if (textChunk.isDeleted()) {
                continue;
            }
            if (prevText != null && canMerge(prevText, textChunk)) {
                if (StringUtils.isBlank(textChunk.getText()) && textChunk.getText().length() > 0) {
                    merged.add(new TextChunk(textChunk));
                } else {
                    prevText.merge(textChunk);
                }
            } else {
                // PDF里面有很多空白的字符块, 这些字符块出现可能不是连续的
                if (StringUtils.isNotBlank(textChunk.getText())) {
                    prevText = new TextChunk(textChunk);
                    merged.add(prevText);
                } else {
                    merged.add(new TextChunk(textChunk));
                }
            }
        }

        //单独将竖直方向的chunk进行合并
        List<TextChunk> textChunks1 = merged.stream()
                .filter(textChunk -> textChunk.getDirection() == TextDirection.UNKNOWN)
                .filter(textChunk -> !textChunk.isBlank())
                .collect(Collectors.toList());
        TextChunk prevTextVertical = null;
        for (TextChunk textChunk : textChunks1) {
            if (textChunk.isDeleted()) {
                continue;
            }
            if (prevTextVertical != null && canMergeVertical(prevTextVertical, textChunk)) {
                prevTextVertical.merge(textChunk);
                merged.remove(textChunk);
            } else {
                // PDF里面有很多空白的字符块, 这些字符块出现可能不是连续的
                if (StringUtils.isNotBlank(textChunk.getText())) {
                    prevTextVertical = textChunk;
                }
            }

        }

        for (TextChunk textChunk : merged) {
            if (textChunk.getDirection() == TextDirection.UNKNOWN) {
                textChunk.setDirection(TextDirection.LTR);
            }
        }
        merged.removeIf(textChunk -> StringUtils.isBlank(textChunk.getText()));

        List<TextChunk> targetChunks = new ArrayList<>();
        for (TextChunk chunk : merged) {
            targetChunks.addAll(getSplitTextChunks(chunk));
        }
        merged = targetChunks;
        return merged;
    }

    private static List<TextChunk> getSplitTextChunks(TextChunk candidatechunk) {

        List<TextElement> allElements = new ArrayList<>(candidatechunk.getElements());
        List<TextChunk> targetChunks = new ArrayList<>();
        if (allElements.size() < 3) {
            targetChunks.add(candidatechunk);
            return targetChunks;
        }

        double height = 1.5 * allElements.stream().max(Comparator.comparing(TextElement::getHeight)).get().getHeight();
        if (candidatechunk.getHeight() > height) {
            targetChunks.add(candidatechunk);
            return targetChunks;
        }

        List<TextElement> clusElements = allElements.stream()
                .filter(te -> (te.verticalOverlapRatio(allElements.get(0)) > 0.5)).collect(Collectors.toList());
        clusElements.sort(Comparator.comparing(TextElement::getLeft));

        if (candidatechunk.getDirection() == TextDirection.LTR
                && clusElements.size() >= 3 && clusElements.size() == allElements.size()) {

            if (DATA_SOURCE_SERIAL.matcher(candidatechunk.getText().trim().toLowerCase()).matches()) {

                List<TextElement> firstTextElement = new ArrayList<>();
                for (TextElement cellElement : clusElements) {
                    if (cellElement.getText().equals("：") || cellElement.getText().equals(":")) {
                        firstTextElement.add(cellElement);
                        break;
                    } else {
                        firstTextElement.add(cellElement);
                    }
                }

                List<TextElement> tmpTextElement = new ArrayList<>(clusElements);
                tmpTextElement.removeAll(firstTextElement);
                List<TextElement> secondTextElements = new ArrayList<>();
                int gapCnt = 0;
                boolean isFindSecondElementFlag = false;
                for (TextElement cellElement : tmpTextElement) {
                    if (cellElement.getText().equals("：") || cellElement.getText().equals(":")) {
                        secondTextElements.add(cellElement);
                        isFindSecondElementFlag = true;
                        break;
                    } else {
                        secondTextElements.add(cellElement);
                        gapCnt++;
                    }
                }

                boolean isExistOtherSource = false;
                TextElement headerText = null;
                if (isFindSecondElementFlag && secondTextElements.size() > 3 && secondTextElements.size() > gapCnt
                        && (secondTextElements.get(gapCnt).getText().equals("：")
                        || secondTextElements.get(gapCnt).getText().equals(":")) ) {
                    List<TextElement> candidateElements = new ArrayList<>();
                    candidateElements.add(secondTextElements.get(gapCnt));
                    for (int i = 1; i < gapCnt; i++) {
                        if (i > 6) {
                            break;
                        }
                        headerText = secondTextElements.get(gapCnt - i);
                        candidateElements.add(0, headerText);
                        if (DATA_SOURCE_SERIAL.matcher((new TextChunk(candidateElements)).getText().trim().toLowerCase()).matches()) {
                            isExistOtherSource = true;
                        }
                    }
                }

                if (isExistOtherSource && headerText != null) {
                    // textChunk1
                    TextElement finalHeaderText = headerText;
                    List<TextElement> elementList1 = clusElements.stream()
                            .filter(te -> (te.getLeft() < finalHeaderText.getLeft())).collect(Collectors.toList());
                    TextChunk textChunk1 = new TextChunk();
                    textChunk1.addTextElements(elementList1);
                    // textChunk2
                    clusElements.removeAll(elementList1);
                    TextChunk textChunk2 = new TextChunk();
                    textChunk2.addTextElements(clusElements);
                    targetChunks.add(textChunk1);
                    targetChunks.add(textChunk2);
                } else {
                    targetChunks.add(candidatechunk);
                }
            } else {
                TextElement firstElement = clusElements.get(0);
                boolean isHeader = CHART_TABLE_SPLIT_SERIAL.matcher(firstElement.getText().toLowerCase()).matches();
                if (isHeader) {
                    TextElement secondElement = null;
                    int gapCnt = 0;
                    for (TextElement tmpElement : clusElements) {
                        if (tmpElement.equals(firstElement)) {
                            continue;
                        }
                        if ((CHART_TABLE_SPLIT_SERIAL.matcher(tmpElement.getText().toLowerCase()).matches()) && gapCnt > 1) {
                            secondElement = tmpElement;
                            break;
                        }
                        gapCnt++;
                    }

                    if (secondElement != null) {
                        // textChunk1
                        TextElement finalSecondElement = secondElement;
                        List<TextElement> clusLeftElements = clusElements.stream()
                                .filter(te -> (te.equals(firstElement) || (te.getLeft() > firstElement.getLeft()
                                        && te.getRight() < finalSecondElement.getRight()))).collect(Collectors.toList());
                        TextChunk textChunk1 = new TextChunk();
                        textChunk1.addTextElements(clusLeftElements);
                        // textChunk2
                        clusElements.removeAll(clusLeftElements);
                        TextChunk textChunk2 = new TextChunk();
                        textChunk2.addTextElements(clusElements);
                        targetChunks.add(textChunk1);
                        targetChunks.add(textChunk2);
                    } else {
                        targetChunks.add(candidatechunk);
                    }
                } else {
                    targetChunks.add(candidatechunk);
                }
            }
        } else {
            targetChunks.add(candidatechunk);
        }

        return targetChunks;
    }

}
