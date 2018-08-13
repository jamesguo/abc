package com.abcft.pdfextract.core.table.detectors;

import com.abcft.pdfextract.core.ExtractorUtil;
import com.abcft.pdfextract.core.model.Rectangle;
import com.abcft.pdfextract.core.model.TextChunk;
import com.abcft.pdfextract.core.model.TextElement;
import com.abcft.pdfextract.core.table.*;
import com.abcft.pdfextract.util.FloatUtils;

import java.awt.geom.Point2D;
import java.util.*;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class ClipAreasTableRegionDetectionAlgorithm {

    private static int megreHencSeq = 0;

    private enum MegreDirect {
        HRTL, HSC, HAC, HO
    }

    private static final double CJK_EDGE_MARGIN_TOLERANCE = 1.5;
    private static final double CJK_CELL_MARGIN_TOLERANCE = 3.5;


    private static void removeRectangleIf(List<? extends Rectangle> clipAllAreas,
                                          BiPredicate<? super Rectangle, ? super Rectangle> op) {
        if (clipAllAreas.size() < 2) {
            return;
        }
        Rectangle one, other;

        for (int i = 0; i < clipAllAreas.size() - 1; ++i) {
            if (clipAllAreas.isEmpty()) {
                break;
            }
            one = clipAllAreas.get(i);
            for (int j = i + 1; j < clipAllAreas.size(); j++) {
                other = clipAllAreas.get(j);
                if (op.test(one, other)) {
                    other.markDeleted();
                } else if (op.test(other, one)) {
                    one.markDeleted();
                }
            }
        }

        clipAllAreas.removeIf(Rectangle::isDeleted);
    }

    private static void removeRectangleIf(List<? extends Rectangle> rectangles,
                                          Predicate<? super Rectangle> op) {
        if (rectangles.size() < 1) {
            return;
        }
        Rectangle rectangle;
        for (int i = 0; i < rectangles.size(); ) {
            rectangle = rectangles.get(i);
            if (op.test(rectangle)) {
                rectangles.remove(rectangle);
            } else {
                i++;
            }
        }
    }

    private static void filterSubCells(List<TableRegion> clipAllAreas, int cellHeight) {
        removeRectangleIf(clipAllAreas, (one, other) -> one.contains(other) && one.getHeight() < cellHeight + 2);
    }

    // 为每个矩形所在的行列打标签
    private static void makeLabelxy(List<TableRegion> clipAllAreas) {
        // 根据y坐标，确定所有的行分组
        int idx = 0;
        int hencseq = 0;
        clipAllAreas.sort(Comparator.comparing(Rectangle::getTop));
        while (idx < clipAllAreas.size()) {
            Rectangle base = clipAllAreas.get(idx);

            for (int j = idx; j < clipAllAreas.size(); ++j){
                Rectangle candidate = clipAllAreas.get(j);

                if (FloatUtils.feq(base.getTop(), candidate.getTop(), CJK_EDGE_MARGIN_TOLERANCE)) {
                    clipAllAreas.get(j).setGroupHencSeq(hencseq);
                    ++idx;
                } else {
                    ++hencseq;
                    break;
                }
            }
        }

        // 根据x坐标，确定所有的列分组
        int vencseq = 0;
        idx = 0;
        clipAllAreas.sort(Comparator.comparing(Rectangle::getLeft));
        while (idx < clipAllAreas.size()) {
            Rectangle base = clipAllAreas.get(idx);

            for (int j = idx; j < clipAllAreas.size(); j++) {
                Rectangle candidate = clipAllAreas.get(j);

                if (FloatUtils.feq(base.getLeft(), candidate.getLeft(), CJK_EDGE_MARGIN_TOLERANCE)) {
                    clipAllAreas.get(j).setGroupVencSeq(vencseq);
                    ++idx;
                } else {
                    ++vencseq;
                    break;
                }
            }
        }
    }

    private static void processMegreHencRect(MegreDirect dict, Rectangle clipBase, Rectangle clipCandidate) {
        double x = (clipBase.getLeft() < clipCandidate.getLeft()) ? clipBase.getLeft() : clipCandidate.getLeft();
        double y = clipBase.getTop();
        double height = (clipBase.getBottom() < clipCandidate.getBottom()) ? (clipCandidate.getBottom() - clipBase.getTop()) : clipBase.getHeight();
        double width;
        switch (dict) {
            // henc right to left
            case HRTL:
                width = clipCandidate.getRight() - clipBase.getLeft();
                clipBase.setRect(x, y, width, height);
                break;
            // henc some contain
            case HSC:
                width = clipCandidate.getRight() - x;
                clipBase.setRect(x, y, width, height);
                break;
            // henc all contain
            case HAC:
                width = clipBase.getWidth();
                clipBase.setRect(x, y, width, height);
                break;
            // henc other
            case HO:
                width = clipCandidate.getRight() - clipBase.getLeft();
                clipBase.setRect(x, y, width, height);
                break;
            default:
                break;
        }
    }

    private static boolean twoClipAreasContainLine(Rectangle clipBase, Rectangle clipCandidate, Page page) {
        // 判断两者之间是否有线
        List<Ruling> lineHenc = page.getHorizontalRulings();
        List<Ruling> candiateHencRulings = new ArrayList<>();
        /*
        *        |------|            |-----|-----|-----|
        *   |----|      |----|       |     |-----|     |
        *   |    |      |    |       |     |     |     |
        *  --------------------    ---------     --------
        */
        for (Ruling hLines : lineHenc) {

            boolean bLeftTopPoint = FloatUtils.feq(clipBase.getTop(), hLines.getY1(), 3) &&
                    (FloatUtils.feq(clipBase.getRight(), hLines.getX1(), 2.0) || (clipBase.getRight() - hLines.getX1() >= 2));
            boolean bRightTopPoint = FloatUtils.feq(clipCandidate.getTop(), hLines.getY1(), 3) &&
                    (FloatUtils.feq(clipCandidate.getLeft(), hLines.getX2(), 2.0) || (hLines.getX2() - clipBase.getRight() >= 2));
            // top line
            if (bLeftTopPoint && bRightTopPoint) {
                candiateHencRulings.add(hLines);
            }

            boolean bLeftBottomPoint = FloatUtils.feq(clipBase.getBottom(), hLines.getY1(), 3) &&
                    (FloatUtils.feq(clipBase.getRight(), hLines.getX1(), 2.0) || (clipBase.getRight() - hLines.getX1() >= 2));
            boolean bRightBottomPoint = FloatUtils.feq(clipCandidate.getBottom(), hLines.getY1(), 3) &&
                    (FloatUtils.feq(clipCandidate.getLeft(), hLines.getX2(), 2.0) || (hLines.getX2() - clipBase.getRight() >= 2));
            // bottom line
            if (bLeftBottomPoint && bRightBottomPoint) {
                candiateHencRulings.add(hLines);
                break;
            }
        }

        boolean result = false;
        if (!candiateHencRulings.isEmpty()) {
            result = true;
        }
        return result;
    }

    private static void addAllHencCell(Rectangle referRect, Rectangle targetClip, Page page) {
        // 判断两者之间是否有线
        List<Ruling> lineHenc = page.getHorizontalRulings();
        List<Ruling> candiateHencRulings = new ArrayList<>();
        for (Ruling hLines : lineHenc) {
            if (FloatUtils.feq(referRect.getTop(), hLines.getY1(), 1.0) &&
                    (hLines.getX1() <= referRect.getLeft()) &&
                    (hLines.getX2() >= referRect.getRight())) {
                candiateHencRulings.add(hLines);
                continue;
            }
            if (FloatUtils.feq(referRect.getBottom(), hLines.getY1(), 1.0) &&
                    (hLines.getX1() <= referRect.getLeft()) &&
                    (hLines.getX2() >= referRect.getRight())) {
                candiateHencRulings.add(hLines);
                break;
            }
        }

        List<Ruling> lineVenc = page.getVerticalRulings();
        List<Ruling> candiateVencRulings = new ArrayList<>();
        for (Ruling vLines : lineVenc) {
            boolean bTopBottomContain = ((referRect.getTop() >= vLines.getY1()) || FloatUtils.feq(referRect.getTop(), vLines.getY1(), 2)) &&
                    ((referRect.getBottom() <= vLines.getY2()) || (FloatUtils.feq(referRect.getBottom(), vLines.getY2(), 2)));
            boolean bLeftRightContain = ((referRect.getLeft() - vLines.getX1() > 5) || (vLines.getX1() - referRect.getRight() > 5));
            if (bTopBottomContain && bLeftRightContain) {
                if ((vLines.getX1() > referRect.getLeft()) && (vLines.getX1() < referRect.getRight())) {
                    continue;
                } else {
                    candiateVencRulings.add(vLines);
                }
            }
        }
        // 向左 向右补充单元格
        if (candiateHencRulings.size() > 0) {
            List<Ruling> leftCandidateLines = new ArrayList<>();
            List<Ruling> rightCandidateLines = new ArrayList<>();
            for (Ruling candiateVencRuling : candiateVencRulings) {
                if (candiateVencRuling.getX1() < referRect.getLeft()) {
                    leftCandidateLines.add(candiateVencRuling);
                } else if (candiateVencRuling.getX1() > referRect.getRight()) {
                    rightCandidateLines.add(candiateVencRuling);
                }
            }
            Ruling topLine = candiateHencRulings.get(0);
            Ruling baseLeftLine = new Ruling(referRect.getTop(), referRect.getLeft(),
                    0, referRect.getBottom() - referRect.getTop());
            Collections.reverse(leftCandidateLines);
            for (Ruling candidateLine : leftCandidateLines) {
                if (FloatUtils.feq(candidateLine.getX1(), topLine.getX1(), 2)) {
                    CandidateCell cell = new CandidateCell((float) candidateLine.getX1(), baseLeftLine.getTop(),
                            (float) (baseLeftLine.getX1() - candidateLine.getX1()), baseLeftLine.getHeight());
                    targetClip.setRect(cell.getLeft(), targetClip.getTop(),
                            referRect.getLeft() - cell.getLeft(), targetClip.getHeight());
                    break;
                } else {
                    baseLeftLine = candidateLine;
                }
            }

            Ruling baseRightLine = new Ruling(referRect.getTop(), referRect.getRight(),
                    0, referRect.getBottom() - referRect.getTop());
            Collections.reverse(rightCandidateLines);
            for (Ruling candidateLine : rightCandidateLines) {
                if (FloatUtils.feq(candidateLine.getX1(), topLine.getX2(), 2)) {
                    CandidateCell cell = new CandidateCell((float) baseRightLine.getX1(), baseRightLine.getTop(),
                            (float) (candidateLine.getX1() - baseRightLine.getX1()), baseLeftLine.getHeight());
                    targetClip.setRect(targetClip.getLeft(), targetClip.getTop(),
                            cell.getRight() - targetClip.getLeft(), targetClip.getHeight());
                    break;
                } else {
                    baseRightLine = candidateLine;
                }
            }
        }
    }

    private static void mergeHencSubArea(List<TableRegion> clipCandidate, List<TableRegion> clipTargetAreas, Page page) {
        if (clipCandidate.isEmpty()) {
            return;
        }

        // 行融合
        int vencSeq = 0;
        clipCandidate.sort(Comparator.comparing(Rectangle::getGroupVencSeq));
        TableRegion megreHencBase = clipCandidate.get(0);

        for (int j = 1;j < clipCandidate.size();j++) {
            TableRegion megrehCandidate = clipCandidate.get(j);
            boolean gapRightToLeft = FloatUtils.feq(megreHencBase.getRight(), megrehCandidate.getLeft(), CJK_CELL_MARGIN_TOLERANCE);
            boolean gapSomeContain =  (megreHencBase.getRight() > megrehCandidate.getLeft()) &&
                    (megreHencBase.getRight() < megrehCandidate.getRight()) &&
                    (megreHencBase.getLeft() < megrehCandidate.getLeft());
            boolean gapFullContain = (megreHencBase.getLeft() <= megrehCandidate.getLeft()) &&
                    (megreHencBase.getRight() >= megrehCandidate.getRight());

            if (gapRightToLeft) {
                processMegreHencRect(MegreDirect.HRTL, megreHencBase, megrehCandidate);
            } else {
                if (gapSomeContain) {
                    // 两个矩形部分交叉，有交集
                    processMegreHencRect(MegreDirect.HSC, megreHencBase, megrehCandidate);
                } else if (gapFullContain) {
                    // 一个矩形完全包含另一个矩形
                    processMegreHencRect(MegreDirect.HAC, megreHencBase, megrehCandidate);
                } else {
                    // 判断两个矩形之间是否有线
                    if (!twoClipAreasContainLine(megreHencBase, megrehCandidate, page)) {
                        megreHencBase.setGroupHencSeq(megreHencSeq);
                        megreHencBase.setGroupVencSeq(vencSeq);
                        vencSeq++;
                        clipTargetAreas.add(megreHencBase);
                        megreHencBase = megrehCandidate;
                    } else {
                        processMegreHencRect(MegreDirect.HO, megreHencBase, megrehCandidate);
                    }
                }
            }
        }
        // 一行只有一个单元格
        if (clipCandidate.size() == 1) {
            addAllHencCell(clipCandidate.get(0), megreHencBase, page);
        }
        megreHencBase.setGroupHencSeq(megreHencSeq);
        if (!clipTargetAreas.isEmpty()) {
            Rectangle lastRect = clipTargetAreas.get(clipTargetAreas.size() - 1);
            if (FloatUtils.feq(megreHencBase.getLeft(), lastRect.getLeft(), 10)) {
                megreHencBase.setGroupVencSeq(lastRect.getGroupVencSeq());
            } else {
                boolean containedFlag = (megreHencBase.getLeft() >= lastRect.getLeft()) &&
                        (megreHencBase.getLeft() <= lastRect.getRight());
                if ((FloatUtils.feq(lastRect.getRight(), megreHencBase.getLeft(), CJK_EDGE_MARGIN_TOLERANCE) || containedFlag ||
                        (FloatUtils.feq(lastRect.getBottom(), megreHencBase.getTop(), CJK_EDGE_MARGIN_TOLERANCE)))) {
                    megreHencBase.setGroupVencSeq(lastRect.getGroupVencSeq());
                } else {
                    if (megreHencBase.getLeft() > lastRect.getLeft()) {
                        megreHencBase.setGroupVencSeq(lastRect.getGroupVencSeq() + 1);
                    } else {
                        megreHencBase.setGroupVencSeq(lastRect.getGroupVencSeq() - 1);
                    }
                }
            }
        } else {
            megreHencBase.setGroupVencSeq(vencSeq);
        }
        clipTargetAreas.add(megreHencBase);
        megreHencSeq++;
    }

    private static void mergeConnecteHencAreas(List<TableRegion> clipAllAreas, Page page) {

        List<TableRegion> clipCandidate = new ArrayList<>();
        List<TableRegion> clipTargetAreas = new ArrayList<>();

        if (clipAllAreas.isEmpty()) {
            return;
        }

        clipAllAreas.sort(Comparator.comparing(Rectangle::getGroupHencSeq));

        TableRegion base = clipAllAreas.get(0);
        for (TableRegion candidate : clipAllAreas) {
            if (base.getGroupHencSeq() == candidate.getGroupHencSeq()) {
                clipCandidate.add(candidate);
            } else {
                base = candidate;
                mergeHencSubArea(clipCandidate, clipTargetAreas, page);
                clipCandidate.clear();
                clipCandidate.add(candidate);
            }
        }
        mergeHencSubArea(clipCandidate, clipTargetAreas,page);
        megreHencSeq = 0;
        clipAllAreas.clear();
        clipAllAreas.addAll(clipTargetAreas);
    }

    private static void processNearlayLine(List<TableRegion> clipCandidate, TableRegion megrevBase, Page page) {
        // 判断临近是否有横线和竖线
        List<Ruling> lineHenc = page.getHorizontalRulings();
        List<Ruling> candiateHencRulings = new ArrayList<>();


        for (Ruling hLines:lineHenc) {
            if (FloatUtils.feq(megrevBase.getTop(), hLines.getY1(), CJK_CELL_MARGIN_TOLERANCE)) {
                candiateHencRulings.add(hLines);
                continue;
            }
            if (FloatUtils.feq(megrevBase.getBottom(), hLines.getY1(), CJK_CELL_MARGIN_TOLERANCE)) {
                candiateHencRulings.add(hLines);
                break;
            }
        }

        if (candiateHencRulings.size() > 1) {
            Ruling top = candiateHencRulings.get(0);
            Ruling bottom = candiateHencRulings.get(1);

            double x1;
            if(top.x1 <= bottom.x1) {
                x1 = (top.x1 <= megrevBase.getLeft()) ? top.x1 : megrevBase.getLeft();
            } else {
                x1 = (bottom.x1 <= megrevBase.getLeft())?bottom.x1 : megrevBase.getLeft();
            }

            double x2;
            if (top.x2 >= bottom.x2) {
                x2 = (top.x2 >= megrevBase.getRight()) ? top.x2 : megrevBase.getRight();
            } else {
                x2 = (bottom.x2 >= megrevBase.getRight()) ? bottom.x2 : megrevBase.getRight();
            }
            double width = x2 - x1;
            megrevBase.setRect(x1, megrevBase.getTop(), width, megrevBase.getHeight());
        }
        clipCandidate.add(megrevBase);
    }

    private static List<TableRegion> mergeSignalVencSubArea(List<TableRegion> clipHencAreas, Page page) {
        if(clipHencAreas.isEmpty()){
            return null;
        }

        List<TableRegion> clipCandidate = new ArrayList<>();
        clipHencAreas.sort(Comparator.comparing(Rectangle::getTop));
        TableRegion megreVencBase = clipHencAreas.get(0);
        float tableMarginGap = (page.getMinCharHeight() + page.getAvgCharHeight()) / 2;
        for (int i = 1;i < clipHencAreas.size();i++) {
            TableRegion megrevCandidate = clipHencAreas.get(i);
            if (megreVencBase.getLeft() == 0) {
                megreVencBase = megrevCandidate;
                continue;
            }
            boolean gapBottomToTop = FloatUtils.feq(megreVencBase.getBottom(), megrevCandidate.getTop(), tableMarginGap);

            boolean gapSomeContain = (megreVencBase.getBottom() >= megrevCandidate.getTop())
                    &&(megreVencBase.getBottom() <= megrevCandidate.getBottom());

            boolean gapFullContain = (megreVencBase.getLeft() <= megrevCandidate.getLeft()) && (megreVencBase.getTop() <= megrevCandidate.getTop())
                    && (megreVencBase.getRight() >= megrevCandidate.getRight())&&(megreVencBase.getBottom() >= megrevCandidate.getBottom());

            if (gapBottomToTop || gapSomeContain) {
                double x = (megreVencBase.getLeft() < megrevCandidate.getLeft()) ? megreVencBase.getLeft() : megrevCandidate.getLeft();
                double y = (megreVencBase.getTop() < megrevCandidate.getTop()) ? megreVencBase.getTop() : megrevCandidate.getTop();
                double width = ((megreVencBase.getRight() > megrevCandidate.getRight()) ? megreVencBase.getRight() : megrevCandidate.getRight()) - x;
                double height = ((megreVencBase.getBottom() > megrevCandidate.getBottom()) ? megreVencBase.getBottom() : megrevCandidate.getBottom()) - y;
                megreVencBase.setRect(x, y, width, height);
            } else if (gapFullContain){
                // 检测到的是一个小矩形，被上一个矩形包含
                // nothing to do
            } else {
                if ((megreVencBase.getLeft() != 0) && (clipHencAreas.size() > 1)) {
                    clipCandidate.add(megreVencBase);
                }
                megreVencBase = megrevCandidate;
            }
        }
        // 对于只有一行的融合方案
        if (clipHencAreas.size() == 1) {
            TableRegion base = clipHencAreas.get(0);
            processNearlayLine(clipCandidate, base, page);
        } else {
            clipCandidate.add(megreVencBase);
        }

        return clipCandidate;
    }

    private static List<TableRegion> mergeMultiplyleVencSubArea(List<List<TableRegion>> clipList, List<TableRegion> clipHencAreas, Page page) {
        if (clipHencAreas.isEmpty()) {
            return null;
        }

        List<TableRegion> clipTargetAreas = new ArrayList<>();
        for (List<TableRegion> clipCancidateAreas : clipList) {
            clipTargetAreas.addAll(mergeSignalVencSubArea(clipCancidateAreas, page));
        }

        return clipTargetAreas;
    }

    private static void preProcessHencInfo(List<List<TableRegion>> clipList, List<TableRegion> clipHencAreas) {
        List<TableRegion> clipAreas0 = new ArrayList<>();
        List<TableRegion> clipAreas1 = new ArrayList<>();
        clipHencAreas.sort(Comparator.comparing(Rectangle::getGroupVencSeq));

        if (!clipHencAreas.isEmpty()) {
            Rectangle baseClip = clipHencAreas.get(0);
            // TODO：目前只考虑同一水平面两个表格的情况
            for (TableRegion candidateClip : clipHencAreas) {
                if (baseClip.getGroupVencSeq() == candidateClip.getGroupVencSeq()) {
                    // 可能为同一个表格
                    clipAreas0.add(candidateClip);
                } else {
                    clipAreas1.add(candidateClip);
                }
            }

            int tableNum = 0;
            if (clipAreas0.size() > 0) {
                clipList.add(tableNum, clipAreas0);   // 水平第一个表格
            }

            if (clipAreas1.size() > 0) {
                clipList.add(++tableNum, clipAreas1); // 水平第二个表格
            }
        }
    }

    private static List<TableRegion> mergeConnecteVencAreas(List<TableRegion> clipHencAreas, Page page) {
        List<List<TableRegion>> clipList = new ArrayList<>();
        preProcessHencInfo(clipList, clipHencAreas);
        List<TableRegion> clipCandidate;

        if (clipList.size() >= 2) {
            clipCandidate = mergeMultiplyleVencSubArea(clipList, clipHencAreas, page);
        } else {
            clipCandidate = mergeSignalVencSubArea(clipHencAreas, page);
        }

        return clipCandidate;
    }

    private static <T extends Rectangle> void filterContainerAreas(List<T> rectangles) {
        List<T> toRemoves = new ArrayList<>(rectangles.size());
        for (int i = 0; i < rectangles.size() - 1; i++) {
            T rc1 = rectangles.get(i);
            for (int j = i+1; j < rectangles.size(); j++) {
                T rc2 = rectangles.get(j);
                if (rc1.contains(rc2)) {
                    toRemoves.add(rc1);
                } else if (rc2.contains(rc1)) {
                    toRemoves.add(rc2);
                }
            }
        }
        rectangles.removeAll(toRemoves);
    }

    private static void prefilterAreas(Page page, List<? extends Rectangle> clipAllAreas, int cellHeight) {

        Point2D.Float pageSize = ExtractorUtil.determinePageSize(page.getPDPage());
        int pgWidth = (int) pageSize.getX();
        int pgHeight = (int) pageSize.getY();
        int marginTop = 10;
        int marginBottom = 10;
        int marginLeft = 0;
        int marginRight = 0;

        Rectangle textArea = Rectangle.fromLTRB(marginLeft, marginTop, pgWidth - marginRight, pgHeight - marginBottom);

        removeRectangleIf(clipAllAreas, (rect) -> {
            return !textArea.contains(rect)
                    || rect.getHeight() < cellHeight * 1.5
                    || rect.getWidth() < 14;      //宽度
        });
    }

    private static List<Rectangle> cellsContainByTable(List<Rectangle> cells, Rectangle tableArea) {
        if (tableArea != null && cells.size() > 0) {
            return cells.stream().filter(cell -> (tableArea.nearlyContains(cell, 3.0) || tableArea.nearlyEquals(cell, 3.0)))
                    .collect(Collectors.toList());
        } else {
            return new ArrayList<>();
        }
    }

    private static void filterTablesByCells(List<Rectangle> cells, List<TableRegion> tableAreas, Page page) {

        if (!tableAreas.isEmpty()) {
            List<Rectangle> regionCells = new ArrayList<>();
            int lastNum = 0, nowNum = 0;
            for (Rectangle tableArea : tableAreas) {
                // tabel区域中无裁剪区，该tabel过滤掉
                regionCells.addAll(cellsContainByTable(cells, tableArea));
                nowNum = regionCells.size();
                if (nowNum - lastNum <= 1) {
                    tableArea.markDeleted();
                }
                lastNum = nowNum;

                List<TextChunk> textchunks = page.getTextObjects(tableArea);
                if (!tableArea.isDeleted() && (NoRulingTableRegionsDetectionAlgorithm.isChartHeader(page, tableArea, textchunks) ||
                        NoRulingTableRegionsDetectionAlgorithm.isChartRegion(page, tableArea, textchunks))) {
                    tableArea.markDeleted();
                }

                if (!tableArea.isDeleted()) {
                    float subCellArea = 0;
                    for (Rectangle cell : regionCells) {
                        subCellArea += cell.getArea();
                    }
                    if (subCellArea / tableArea.getArea() > 1.5 || subCellArea / tableArea.getArea() < 0.5) {
                        tableArea.markDeleted();
                    }
                }
                regionCells.clear();
            }
            tableAreas.removeIf(Rectangle::isDeleted);
        }
    }

    private static List<Integer> getClipAreasParam(List<? extends Rectangle> clipAllAreas, Function<Rectangle, Integer> param) {
        List<Integer> heights_rc = new ArrayList<>();
        for (Rectangle rect : clipAllAreas) {
            heights_rc.add(param.apply(rect));
        }
        return heights_rc;
    }

    private static Integer getKeyByValue(Map<Integer, Integer> h_cnt, Integer value) {
        Integer key = -1;
        for (Map.Entry entry : h_cnt.entrySet()) {
            if (entry.getValue().equals(value)) {
                key = (Integer) entry.getKey();
                break;
            }
        }
        return key;
    }

    //基本单元格高度一般相同 宽度可能不同 （取表格中最多的单元格为基本单元格）
    private static int getBasicCellHeight(List<? extends Rectangle> cells) {
        List<Integer> heights = getClipAreasParam(cells, (cell) -> (int) cell.getHeight());

        if (heights.isEmpty())
            return -1;
        if (heights.size() < 2)
            return heights.get(0);

        Collections.sort(heights);
        Map<Integer, Integer> counter = new HashMap<>();
        int oldHeight = heights.get(0), cnt = 0;
        for (Integer h : heights) {
            if (oldHeight == h) {
                counter.put(h, cnt++);
            } else {
                cnt = 0;
            }
            oldHeight = h;
        }
        int maxValue = Collections.max(counter.values());

        return Math.min(8, getKeyByValue(counter, maxValue));
    }


    private static List<TableRegion> processNoRulingTable(Page page, List<Rectangle> candidateCells) {
        if (candidateCells.isEmpty()) {
            return new ArrayList<>();
        }

        candidateCells.removeAll(candidateCells.stream().filter(cell -> (cell.getWidth() / page.getWidth() > 0.7 || cell.getHeight() / page.getHeight() > 0.7))
                .collect(Collectors.toList()));

        List<TableRegion> tableAreas = candidateCells.stream()
                .map(TableRegion::new)
                .collect(Collectors.toList());
        int cellHeight = getBasicCellHeight(tableAreas);
        filterSubCells(tableAreas, cellHeight);
        // 确定矩形的位置
        makeLabelxy(tableAreas);
        // 行融合
        mergeConnecteHencAreas(tableAreas, page);
        // 列融合
        List<TableRegion> clipAreas = mergeConnecteVencAreas(tableAreas, page);
        candidateCells.clear();
        if (clipAreas != null) {
            candidateCells.addAll(clipAreas);
            candidateCells.sort(Comparator.comparing(Rectangle::getTop));
        }

        filterContainerAreas(candidateCells);
        prefilterAreas(page, candidateCells, cellHeight);
        for (Rectangle area : candidateCells) {
            if (page.getText(area).isEmpty()) {
                area.markDeleted();
            } else {
                List<TextElement> textBlock = new ArrayList<>();
                int size = page.getText(area).size();
                textBlock.addAll(page.getText(area).stream().filter(t->t.isWhitespace()).collect(Collectors.toList()));
                if (size == textBlock.size()) {
                    area.markDeleted();
                }
            }
        }
        candidateCells.removeIf(Rectangle::isDeleted);

        // 过滤表格
        filterTablesByCells(candidateCells, tableAreas, page);

        return tableAreas;
    }


    // 通过裁剪区获取表格区域
    public static List<TableRegion> getTableRegions(Page page, List<Rectangle> cells) {

        List<TableRegion> tableAreas = processNoRulingTable(page,  cells);
        if (tableAreas.isEmpty()) {
            return new ArrayList<>();
        }
        List<TableRegion> tables = new ArrayList<>();
        for (Rectangle area : tableAreas) {
            TableRegion table = new TableRegion(area);
            if (new RulingTableRegionsDetectionAlgorithm().isLineTable((ContentGroupPage)page, area)) {
                table.setTableType(TableType.LineTable);
            } else {
                table.setTableType(TableType.NoLineTable);
            }
            tables.add(table);
        }
        return tables;
    }
}
