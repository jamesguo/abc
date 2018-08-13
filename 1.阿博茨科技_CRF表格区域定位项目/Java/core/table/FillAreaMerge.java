package com.abcft.pdfextract.core.table;

import com.abcft.pdfextract.core.model.ContentGroup;
import com.abcft.pdfextract.core.model.PathInfo;
import com.abcft.pdfextract.core.model.PathItem;
import com.abcft.pdfextract.core.model.Rectangle;
import com.abcft.pdfextract.util.FloatUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public final class FillAreaMerge {

    public static void collectFillAreasGroup(ContentGroupPage page, List<FillAreaGroup> fillAreaGroups){

        List<FillArea> rectFillAreas = new ArrayList<>();
        List<FillArea> sectorFillAreas = new ArrayList<>();

        ContentGroup groups = page.getContentGroup();
        List<PathItem> allPaths = groups.getAllPathItems().stream().filter(it -> (it.isFill() && it.getPathInfos().size() == 1)
                && (it.getBounds().getMinX() > 1.0 && it.getBounds().getMaxX() <= page.getRight()
                && it.getBounds().getMinY() > 1.0 && it.getBounds().getMaxY() <= page.getBottom())).collect(Collectors.toList());

        float whiteRGB = -1, blackRGB = -16777216;
        for (PathItem it : allPaths) {
            PathInfo itInfo = it.getPathInfos().get(0);
            if (itInfo.isLinear() && it.getLineWidth() < 50
                    && it.getBounds().getWidth() > 3 && it.getBounds().getHeight() > 3
                    && itInfo.getLineCount() < 5) {
                if (!FloatUtils.feq(whiteRGB, it.getColor().getRGB(), 3) && !FloatUtils.feq(blackRGB, it.getColor().getRGB(), 3)) {
                    FillArea fillArea = new FillArea(it.getBounds(), it.getColor());
                    rectFillAreas.add(fillArea);
                }
            } else {
                if (!itInfo.isLinear()
                        && (((itInfo.getLineCount() > 0 || itInfo.getLineCount() == 0) && itInfo.getLineCount() < 4)
                        && (itInfo.getCurveTosCount() >= 1 && itInfo.getCurveTosCount() <= 6))
                        && (it.getBounds().getWidth() > page.getAvgCharWidth()
                        && it.getBounds().getHeight() > page.getAvgCharHeight())) {
                    FillArea fillArea = new FillArea(it.getBounds(), it.getColor());
                    sectorFillAreas.add(fillArea);
                }
            }
        }

        List<PathItem> allPaths1 = groups.getAllPathItems().stream().filter(it -> (it.isFill() && it.getPathInfos().size() > 1)
                && (it.getBounds().getMinX() > 1.0 && it.getBounds().getMaxX() <= page.getRight()
                && it.getBounds().getMinY() > 1.0 && it.getBounds().getMaxY() <= page.getBottom())
                && it.getPathInfos().get(0).isLinear()).collect(Collectors.toList());
        for (PathItem it : allPaths1) {
            if (FloatUtils.feq(whiteRGB, it.getColor().getRGB(), 3)
                    || FloatUtils.feq(blackRGB, it.getColor().getRGB(), 3)) {
                continue;
            }

            List<PathInfo> rectInfos = it.getPathInfos().stream().filter(pt -> (pt.getBounds2D().getWidth() > 3
                    && pt.getBounds2D().getHeight() > 3 && pt.getLineCount() < 5)).collect(Collectors.toList());
            if (it.getLineWidth() < 50 && !rectInfos.isEmpty()) {
                for (PathInfo pth : rectInfos) {
                    FillArea fillArea = new FillArea(pth.getBounds2D(), it.getColor());
                    rectFillAreas.add(fillArea);
                }
            }
        }

        List<FillArea> removeFillAreas = new ArrayList<>();
        if (!sectorFillAreas.isEmpty()) {
            for (FillArea rectArea : rectFillAreas) {
                if (sectorFillAreas.stream().anyMatch(it -> (it.intersects(rectArea)))) {
                    removeFillAreas.add(rectArea);
                }
            }
            if (!removeFillAreas.isEmpty()) {
                rectFillAreas.removeAll(removeFillAreas);
            }
        }

        // 对矩形填充区进行组合
        if (!rectFillAreas.isEmpty() /*&& receFillAreas.size() < 100*/) {
            fillAreaGroups.addAll(processRectFillArea(page, rectFillAreas));
        }

        // 对扇形填充区进行组合
        if (!sectorFillAreas.isEmpty()) {
            fillAreaGroups.addAll(processSectorFillArea(page, sectorFillAreas));
        }
    }

    private static List<Rectangle> processGroupRects(ContentGroupPage page, List<Rectangle> groupRects, boolean isHorizontalMerge) {
        if (groupRects.isEmpty()) {
            return new ArrayList<>();
        }
        List<Rectangle> targetRects = new ArrayList<>();
        // 水平投影，垂直方向拼接， 垂直投影，水平方向拼接
        if (!isHorizontalMerge) {
            groupRects.sort(Comparator.comparing(Rectangle::getTop));
            Rectangle baseRect = groupRects.get(0);
            for (int i = 1; i < groupRects.size(); i++) {
                Rectangle otherRect = groupRects.get(i);
                if (FloatUtils.feq(baseRect.getBottom(), otherRect.getTop(), 4) || baseRect.intersects(otherRect)) {
                    baseRect.merge(otherRect);
                    continue;
                }
                targetRects.add(new Rectangle(baseRect));
                baseRect = otherRect;
            }
            targetRects.add(new Rectangle(baseRect));
        } else {
            groupRects.sort(Comparator.comparing(Rectangle::getLeft));
            Rectangle baseRect = groupRects.get(0);
            for (int i = 1; i < groupRects.size(); i++) {
                Rectangle otherRect = groupRects.get(i);
                if ((FloatUtils.feq(baseRect.getRight(), otherRect.getLeft(), 3)
                        || baseRect.intersects(otherRect) || baseRect.contains(otherRect) || otherRect.contains(baseRect))
                        && FloatUtils.feq(baseRect.getHeight(), otherRect.getHeight(), 3)) {
                    baseRect.merge(otherRect);
                    continue;
                }
                targetRects.add(new Rectangle(baseRect));
                baseRect = otherRect;
            }
            targetRects.add(new Rectangle(baseRect));
        }

        return targetRects;
    }

    private static List<FillAreaGroup> processRectFillArea(ContentGroupPage page, List<FillArea> rectFillAreas) {
        if (rectFillAreas.isEmpty()) {
            return new ArrayList<>();
        }
        List<Rectangle> fillRects = rectFillAreas.stream().filter(r -> (r.getWidth() > 0 && r.getHeight() > 0))
                .map(Rectangle::new).collect(Collectors.toList());
        if (fillRects.isEmpty()) {
            return new ArrayList<>();
        }

        fillRects.sort(Comparator.comparing(Rectangle::getArea).reversed());
        Rectangle maxFillArea = fillRects.get(0);
        if (fillRects.stream().allMatch(r -> (maxFillArea.contains(r))) && fillRects.size() > 1) {
            fillRects.remove(maxFillArea);
        }
        /*List<TextElement> texts = page.getText();
        for (Rectangle tmpFill : fillRects) {
            //List<Rectangle> insetRects = texts.stream().filter(t -> (tmpFill.intersects(t.getVisibleBBox()))).collect(Collectors.toList());
            //TableDebugUtils.writeCells(page, insetRects, "insetRects");
            //TableDebugUtils.writeCells(page, Arrays.asList(tmpFill), "tmpFill");
            if (texts.stream().anyMatch(t -> (tmpFill.intersects(t.getVisibleBBox()) && !tmpFill.contains(t)))) {
                tmpFill.markDeleted();
            }
        }
        fillRects.removeIf(Rectangle::isDeleted);*/

        if (fillRects.isEmpty()) {
            return new ArrayList<>();
        }

        List<FillAreaGroup> rectGroups = new ArrayList<>();
        fillRects.sort(Comparator.comparing(Rectangle::getTop));
        List<Rectangle> groupRects = new ArrayList<>();
        Rectangle baseRect = fillRects.get(0);
        groupRects.add(baseRect);
        List<Rectangle> barCharts = new ArrayList<>();
        for (int i = 1; i < fillRects.size(); i++) {
            Rectangle otherRect = fillRects.get(i);
            if (baseRect.isVerticallyOverlap(otherRect) && FloatUtils.feq(baseRect.getTop(), otherRect.getTop(), 4)) {
                groupRects.add(otherRect);
            } else {
                barCharts.addAll(processGroupRects(page, groupRects, true));
                groupRects.clear();
                baseRect = otherRect;
                groupRects.add(baseRect);
            }
        }
        barCharts.addAll(processGroupRects(page, groupRects, true));

        List<Rectangle> barCharts1 = new ArrayList<>();
        if (!barCharts.isEmpty()) {
            barCharts.sort(Comparator.comparing(Rectangle::getLeft));
            groupRects.clear();
            baseRect = barCharts.get(0);
            groupRects.add(baseRect);
            for (int i = 1; i < barCharts.size(); i++) {
                Rectangle otherRect = barCharts.get(i);
                if (baseRect.isHorizontallyOverlap(otherRect) && FloatUtils.feq(baseRect.getLeft(), otherRect.getLeft(), 4)) {
                    groupRects.add(otherRect);
                } else {
                    barCharts1.addAll(processGroupRects(page, groupRects, false));
                    groupRects.clear();
                    baseRect = otherRect;
                    groupRects.add(baseRect);
                }
            }
            barCharts1.addAll(processGroupRects(page, groupRects, false));
        }

        List<Rectangle> targetRects = new ArrayList<>();
        if (!barCharts1.isEmpty()) {
            barCharts1.sort(Comparator.comparing(Rectangle::getArea).reversed());
            for (Rectangle re : barCharts1) {
                Rectangle mergeRect = new Rectangle(re);
                if (!targetRects.isEmpty() && targetRects.stream().anyMatch(r ->
                        (r.overlapRatio(re) > 0.1 || r.nearlyContains(re, 1) || re.nearlyContains(r, 1)))) {
                    continue;
                }

                List<Rectangle> allAreas = barCharts1.stream()
                        .filter(r -> (r.overlapRatio(mergeRect) > 0.1)).collect(Collectors.toList());
                int count = allAreas.size();
                if (!allAreas.isEmpty()) {
                    do {
                        for (Rectangle tmp : allAreas) {
                            mergeRect.merge(tmp);
                        }
                        allAreas = barCharts1.stream().filter(r -> (r.overlapRatio(mergeRect) > 0.1
                                && !mergeRect.contains(r))).collect(Collectors.toList());
                    } while (!allAreas.isEmpty() && (count-- > 0));
                }
                targetRects.add(mergeRect);
            }
        }

        if (!targetRects.isEmpty()) {
            for (Rectangle bar : targetRects) {
                FillAreaGroup tmpGroup = new FillAreaGroup();
                tmpGroup.setGroupAreaType(FillAreaGroup.AreaType.BAR_AREA);
                tmpGroup.setGroupRectArea(bar);
                List<FillArea> items = rectFillAreas.stream()
                        .filter(pe -> (bar.contains(pe))).collect(Collectors.toList());
                if (!items.isEmpty()) {
                    List<FillArea> fillAreas = new ArrayList<>();
                    for (FillArea it : items) {
                        FillArea tmpFillArea = new FillArea(it.getBounds2D(), it.getColor());
                        fillAreas.add(tmpFillArea);
                    }
                    if (!fillAreas.isEmpty()) {
                        tmpGroup.setGroupFillAreas(fillAreas);
                    }
                }
                rectGroups.add(tmpGroup);
            }
        }
        TableDebugUtils.writeCells(page, targetRects, "RectFillArea");

        return rectGroups;
    }

    private static List<FillAreaGroup> processSectorFillArea(ContentGroupPage page, List<FillArea> sectorFillAreas) {
        if (sectorFillAreas.isEmpty()) {
            return new ArrayList<>();
        }

        List<FillAreaGroup> sectorGroups = new ArrayList<>();
        List<Rectangle> sectorFillRects = sectorFillAreas.stream()
                .map(Rectangle::new).collect(Collectors.toList());
        sectorFillRects.sort(Comparator.comparing(Rectangle::getLeft));
        List<Rectangle> pieCharts = new ArrayList<>();
        Rectangle baseRect = sectorFillRects.get(0);
        for (int i = 1; i < sectorFillRects.size(); i++ ) {
            Rectangle otherRect = sectorFillRects.get(i);
            if (baseRect.intersects(otherRect) || baseRect.contains(otherRect) || otherRect.contains(baseRect)) {
                baseRect.merge(otherRect);
                continue;
            }

            if ((baseRect.isVerticallyOverlap(otherRect) &&
                    ((baseRect.overlapRatio(otherRect) > 0) || baseRect.horizontalDistance(otherRect) < 4))) {
                baseRect.merge(otherRect);
                continue;
            }

            if ((baseRect.isHorizontallyOverlap(otherRect) &&
                    ((baseRect.overlapRatio(otherRect) > 0) || baseRect.verticalDistance(otherRect) < 4))) {
                baseRect.merge(otherRect);
                continue;
            }
            pieCharts.add(new Rectangle(baseRect));
            baseRect = otherRect;
        }
        pieCharts.add(new Rectangle(baseRect));
        List<Rectangle> targetCharts = new ArrayList<>();
        if (!pieCharts.isEmpty()) {
            pieCharts.sort(Comparator.comparing(Rectangle::getArea).reversed());
            for (Rectangle pie : pieCharts) {
                Rectangle mergeRect = new Rectangle(pie);
                if (!targetCharts.isEmpty() && targetCharts.stream().anyMatch(r ->
                        (r.intersects(pie) || r.nearlyContains(pie, 1)))) {
                    continue;
                }

                List<Rectangle> allAreas = pieCharts.stream().filter(r -> (r.overlapRatio(mergeRect) > 0))
                        .collect(Collectors.toList());
                int count = allAreas.size();
                if (!allAreas.isEmpty()) {
                    do {
                        for (Rectangle tmp : allAreas) {
                            mergeRect.merge(tmp);
                        }
                        allAreas = pieCharts.stream().filter(r -> (r.overlapRatio(mergeRect) > 0
                                && !mergeRect.contains(r))).collect(Collectors.toList());
                    } while (!allAreas.isEmpty() && (count-- > 0));
                }
                targetCharts.add(mergeRect);
            }
        }

        if (!targetCharts.isEmpty()) {
            for (Rectangle pie : targetCharts) {
                FillAreaGroup tmpGroup = new FillAreaGroup();
                tmpGroup.setGroupAreaType(FillAreaGroup.AreaType.PIE_AREA);
                tmpGroup.setGroupRectArea(pie);
                List<FillArea> items = sectorFillAreas.stream()
                        .filter(pe -> (pie.contains(pe))).collect(Collectors.toList());
                if (!items.isEmpty()) {
                    List<FillArea> fillAreas = new ArrayList<>();
                    for (FillArea it : items) {
                        FillArea tmpFillArea = new FillArea(it.getBounds2D(), it.getColor());
                        fillAreas.add(tmpFillArea);
                    }
                    if (!fillAreas.isEmpty()) {
                        tmpGroup.setGroupFillAreas(fillAreas);
                    }
                }
                sectorGroups.add(tmpGroup);
            }
        }
        TableDebugUtils.writeCells(page, targetCharts, "SectorFillArea");

        return sectorGroups;
    }
}
