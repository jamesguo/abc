package com.abcft.pdfextract.core.table;

import com.abcft.pdfextract.core.model.Rectangle;
import com.abcft.pdfextract.core.table.detectors.RulingTableRegionsDetectionAlgorithm;
import com.abcft.pdfextract.util.FloatUtils;
import org.apache.commons.math3.util.FastMath;

import java.awt.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class FillAreaGroup {

    private List<FillArea> fillAreas;
    private Rectangle area;
    private AreaType areaType;

    private boolean marked;

    public boolean isMarked() {
        return marked;
    }

    public void setMark(boolean marked) {
        this.marked = marked;
    }

    /**
     * BAR_AREA包含柱状图和条状图（水平）
     */
    public enum AreaType {
        BAR_AREA, PIE_AREA, OTHER
    }

    public FillAreaGroup () {
        fillAreas = new ArrayList<>();
        area = null;
        areaType = AreaType.OTHER;
    }

    public void setGroupFillAreas(List<FillArea> fillAreas) {
        this.fillAreas = fillAreas;
    }

    public List<FillArea> getGroupFillAreas() {
        return this.fillAreas;
    }

    public void setGroupRectArea(Rectangle area) {
        this.area = area;
    }

    public Rectangle getGroupRectArea() {
        return this.area;
    }

    public void setGroupAreaType(AreaType type) {
        this.areaType = type;
    }

    public AreaType getGroupAreaType() {
        return this.areaType;
    }

    public float getWidth() {
        return this.area.width;
    }

    public float getHeight() {
        return this.area.height;
    }

    public float getLeft() {
        return this.area.getLeft();
    }

    public float getRight() {
        return this.area.getRight();
    }

    public float getTop() {
        return this.area.getTop();
    }

    public float getBottom() {
        return this.area.getBottom();
    }

    public int getColorNum() {
        return getColorSet().size();
    }

    public List<Color> getColorSet() {
        List<Color> colorList = new ArrayList<>();
        for (FillArea fillArea : this.fillAreas) {
            if (colorList.isEmpty()) {
                colorList.add(fillArea.getColor());
                continue;
            }
            boolean hasSameColor = false;
            for (Color temp : colorList) {
                if (FillArea.nearlyEqualColor(temp, fillArea.getColor(), 1.5f)) {
                    hasSameColor = true;
                    break;
                }
            }
            if (!hasSameColor) {
                colorList.add(fillArea.getColor());
            }
        }
        return colorList;
    }

    /**
     * 包含的颜色数目及颜色集合相同
     */
    public boolean nearlyEqualColor(FillAreaGroup group, float thresh) {
        List<Color> ColorList1 = this.getColorSet();
        List<Color> ColorList2 = group.getColorSet();
        if (ColorList1.size() != ColorList2.size()) {
            return false;
        }
        for (Color one : ColorList1) {
            boolean hasSameColor = false;
            for (Color other : ColorList2) {
                if (FillArea.nearlyEqualColor(one, other, thresh)) {
                    hasSameColor = true;
                    break;
                }
            }
            if (!hasSameColor) {
                return false;
            }
        }

        return true;
    }

    public static boolean isAllNearlyEqualColor(List<FillAreaGroup> groups) {
        for (int i = 0; i < groups.size() - 1; i++) {
            for (int j = i + 1; j < groups.size(); j++) {
                if (!(groups.get(i).nearlyEqualColor(groups.get(j), 1.5f))) {
                    return false;
                }
            }
        }
        return true;
    }

    public static boolean isPossibleTableRegion(ContentGroupPage page, List<FillAreaGroup> groups) {
        if (groups.isEmpty()) {
            return false;
        }
        //以长度最长的group的方向为基准进行计算
        boolean isVertical = false;
        float maxLength = 0;
        Rectangle maxLengthRect = groups.get(0).getGroupRectArea();
        for (FillAreaGroup group : groups) {
            if (group.getLength() > maxLength) {
                maxLengthRect = group.getGroupRectArea();
                if (group.isVertical()) {
                    maxLength = group.getHeight();
                    isVertical = true;
                } else {
                    maxLength = group.getWidth();
                    isVertical = false;
                }
            }
        }

        List<Float> lengthList = new ArrayList<>();
        for (FillAreaGroup group : groups) {
            if (isVertical) {
                lengthList.add(group.getHeight());
            } else {
                lengthList.add(group.getWidth());
            }
        }
        float sum = 0;
        for (Float length : lengthList) {
            sum += length;
        }
        sum /= lengthList.size();
        float var = 0;
        for (Float length : lengthList) {
            var += FastMath.pow(length - sum, 2);
        }
        if (FastMath.sqrt(var / lengthList.size()) < 0.2 * sum) {
            return true;
        }

        int horizontalOverlapNum = 0;
        int verticalOverlapNum = 0;
        for (FillAreaGroup group : groups) {
            if (group.getGroupRectArea().isHorizontallyOverlap(maxLengthRect)) {
                horizontalOverlapNum++;
            }
            if (group.getGroupRectArea().isVerticallyOverlap(maxLengthRect)) {
                verticalOverlapNum++;
            }
        }
        if ((isVertical && verticalOverlapNum < horizontalOverlapNum) || (!isVertical && horizontalOverlapNum < verticalOverlapNum)) {
            return true;
        }

        //多颜色块，且有交叉直线
        Rectangle bound = boundingBoxOf(groups);
        if (FillAreaGroup.getMostCommonColorNum(groups) > 1 && RulingTableRegionsDetectionAlgorithm
                .hasIntersectionLines(Ruling.getRulingsFromArea(page.getRulings(), bound))
                && page.getTextChunks(bound).size() >= 2 * groups.size()) {
            return true;
        }

        return false;
    }

    public static int getMostCommonColorNum(List<FillAreaGroup> groups) {
        if (groups.isEmpty()) {
            return 0;
        }

        Map<Integer, Integer> map = new HashMap<>();
        for (FillAreaGroup fillAreaGroup : groups) {
            map.compute(fillAreaGroup.getColorNum(), (k, v) -> v != null ? v + 1 : 1);
        }

        Map.Entry<Integer, Integer> max = null;
        for (Map.Entry<Integer, Integer> entry : map.entrySet()) {
            if (max == null || entry.getValue() > max.getValue()) {
                max = entry;
            }
        }
        return max.getKey();
    }

    /**
     *判断填充区之间是否紧密
     */
    public static boolean isFillAreaGroupDense(List<FillAreaGroup> groups) {
        if (groups.size() < 2) {
            return false;
        }

        float maxSpan = 0;
        float avgSpan = 0;
        for (FillAreaGroup group : groups) {
            float span = FastMath.min(group.getWidth(), group.getHeight());
            if (span > maxSpan) {
                maxSpan = span;
            }
            avgSpan += span;
        }
        float thres = 2.5f * (avgSpan / groups.size() + maxSpan) / 2;

        for (FillAreaGroup one : groups) {
            boolean denseFindFlag = false;
            for (FillAreaGroup other : groups) {
                if (one.equals(other)) {
                    continue;
                }
                if (one.getGroupRectArea().isShapeIntersects(other.getGroupRectArea())
                        || one.getGroupRectArea().isShapeAdjacent(other.getGroupRectArea(), thres)) {
                    denseFindFlag = true;
                    break;
                }
            }
            if (!denseFindFlag) {
                return false;
            }
        }
        return true;
    }

    public boolean isLegend() {
       if (FloatUtils.feq(this.getWidth(), this.getHeight(), 1.0) && this.getWidth() < 15 && this.getHeight() < 15) {
           return true;
       }
       return false;
    }

    public float getLength() {
        return FastMath.max(this.getWidth(), this.getHeight());
    }

    public boolean isVertical() {
        return this.getHeight() > this.getWidth();
    }

    public static TableRegion boundingBoxOf(List<FillAreaGroup> groups) {
        List<Rectangle> rectangles = new ArrayList<>();
        for (FillAreaGroup group : groups) {
            rectangles.add(group.getGroupRectArea());
        }
        return new TableRegion(Rectangle.boundingBoxOf(rectangles));
    }
}
