package com.abcft.pdfextract.core.chart;

import com.abcft.pdfextract.core.content.TextUtils;
import com.abcft.pdfextract.core.model.Rectangle;
import com.abcft.pdfextract.core.model.TextChunk;
import com.abcft.pdfextract.core.model.TextDirection;
import com.abcft.pdfextract.spi.ChartType;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 文字块合并策略, 合并在一块的相邻的文字
 * Created by dhu on 2017/11/9.
 * Edited by chzhang on 2018/03/30
 */
public class ChartTextChunkMerger {

    // 表示间距在多少个空格内的文字合并到一起
    protected final float mergeSpaceNo;

    public ChartTextChunkMerger() {
        this(2);
    }
    public ChartTextChunkMerger(float mergeSpaceNo) {
        this.mergeSpaceNo = mergeSpaceNo;
    }

    private static class SearchArea {
        Rectangle left;
        Rectangle right;
        Rectangle top;
        Rectangle bottom;
    }

    protected ChartTextChunkMerger.SearchArea getSearchArea(TextChunk textChunk, TextChunk textChunk2, boolean nextText) {
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
        ChartTextChunkMerger.SearchArea area = new ChartTextChunkMerger.SearchArea();
        float delta = textChunk.getWidthOfSpace() * mergeSpaceNo / 2;
//        if (TextUtils.isBulletStart(textChunk.getText()) && nextText) {
//            // 项目符号后面的空格比较多, 增大查找的区域
//            delta = delta + textChunk.getWidthOfSpace() * 10;
//        }
        if (searchLeft) {
            area.left = new Rectangle(
                    textChunk.getMinX() - delta,
                    textChunk.getMinY(),
                    delta * 2,
                    textChunk.getHeight());
        }
        if (searchRight) {
            area.right = new Rectangle(
                    textChunk.getMaxX() - delta,
                    textChunk.getMinY(),
                    delta * 2,
                    textChunk.getHeight());
        }
        if (searchTop) {
            // 竖直方向查找范围减半
            area.top = new Rectangle(
                    textChunk.getMinX(),
                    textChunk.getMinY() - delta,
                    textChunk.getWidth(),
                    delta * 2);
        }
        if (searchBottom) {
            // 竖直方向查找范围减半
            area.bottom = new Rectangle(
                    textChunk.getMinX(),
                    textChunk.getMaxY() - delta,
                    textChunk.getWidth(),
                    delta * 2);
        }
        return area;
    }

    private static boolean intersects(Rectangle rectangle1, Rectangle rectangle2) {
        if (rectangle1 == null || rectangle2 == null) {
            return false;
        }
        return rectangle1.intersects(rectangle2);
    }

    protected boolean canMerge(TextChunk textChunk1, TextChunk textChunk2, ChartType chartType) {
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
                if (textChunk2.getText().endsWith("-") || textChunk1.getText().endsWith("-")) {
                    if (textChunk1.getFirstElement().getRotate() == textChunk2.getFirstElement().getRotate()) {
                        return true;
                    }
                }
                double rotate = Math.toDegrees(Math.atan2(textChunk2.getCenterY() - textChunk1.getCenterY(),
                        textChunk2.getCenterX() - textChunk1.getCenterX()));
                if (Math.abs(rotate - textChunk1.getFirstElement().getRotate()) < 10) {
                    return true;
                } else {
                    return false;
                }
            }
        }
        ChartTextChunkMerger.SearchArea searchArea = getSearchArea(textChunk1, textChunk2, true);
        ChartTextChunkMerger.SearchArea searchArea2 = getSearchArea(textChunk2, textChunk1, false);
        if(chartType != null && chartType.equals(ChartType.PIE_CHART)){
            if (intersects(searchArea.right, searchArea2.left)) {
                // LTR
                //保证在Y方向上没有错开太大距离
                if(canPIEmerge(searchArea.right.y, searchArea2.left.y, searchArea.right.height, searchArea2.left.height)){
                    return true;
                }
                return false;
            } else if (intersects(searchArea.left, searchArea2.right)) {
                // RTL
                //保证在Y方向上没有错开太大距离
                if(canPIEmerge(searchArea.left.y, searchArea2.right.y, searchArea.left.height, searchArea2.right.height)){
                    return true;
                }
                return false;
            } else if (intersects(searchArea.top, searchArea2.bottom)) {
                // VERTICAL_UP
                //保证在X方向上没有错开太大距离
                if(canPIEmerge(searchArea.top.x, searchArea2.bottom.x, searchArea.top.width, searchArea2.bottom.width)){
                    return true;
                }
                return false;
            } else if (intersects(searchArea.bottom, searchArea2.top)) {
                // VERTICAL_DOWN
                //保证在X方向上没有错开太大距离
                if(canPIEmerge(searchArea.bottom.x, searchArea2.top.x, searchArea.bottom.width, searchArea2.top.width)){
                    return true;
                }
                return false;
            } else {
                return false;
            }
        } else{
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
    }


    /**
     * 根据错开距离的比值大小返回判断结果(饼图使用)
     * @param areaValue1  非搜寻方向的值1（水平搜寻则使用y值）
     * @param area2Value1 非搜寻方向的值2（水平搜寻则使用y值）
     * @param areaValue2  非搜寻方向的宽度或高度1（水平搜寻则使用高度）
     * @param area2Value2 非搜寻方向的宽度或高度2（水平搜寻则使用高度）
     * @return 返回true融合，返回false不融合
     */
    public boolean canPIEmerge(float areaValue1, float area2Value1, float areaValue2 , float area2Value2) {
        List<Float> values = new ArrayList<>();
        float distanceValue = 0f;
        values.add(areaValue1);
        values.add(areaValue1 + areaValue2);
        values.add(area2Value1);
        values.add(area2Value1 + area2Value2);
        Collections.sort(values);
        distanceValue = values.get(2) - values.get(1);
        if(Math.min(areaValue2,area2Value2)<1E-4 || distanceValue/Math.min(areaValue2,area2Value2) > 0.8) {
            return true;
        }
        return false;
    }

    public List<TextChunk> merge(List<TextChunk> textChunks , ChartType chartType) {
//            textChunks.sort(Comparator.comparingInt(TextChunk::getGroupIndex));
        List<TextChunk> merged = new ArrayList<>();
        TextChunk prevText = null;
        for (TextChunk textChunk : textChunks) {
            if (textChunk.isDeleted()) {
                continue;
            }
            if (prevText != null && canMerge(prevText, textChunk, chartType)) {
                prevText.merge(textChunk);
            } else {
                // PDF里面有很多空白的字符块, 这些字符块出现可能不是连续的
                if (StringUtils.isNotBlank(textChunk.getText())) {
                    prevText = textChunk;
                }
                merged.add(textChunk);
            }
        }
        for (TextChunk textChunk : merged) {
            if (textChunk.getDirection() == TextDirection.UNKNOWN) {
                textChunk.setDirection(TextDirection.LTR);
            }
        }
        merged.removeIf(textChunk -> StringUtils.isBlank(textChunk.getText()));
        return merged;
    }

}
