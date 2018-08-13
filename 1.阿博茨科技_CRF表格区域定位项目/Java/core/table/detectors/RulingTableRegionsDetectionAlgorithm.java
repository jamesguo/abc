package com.abcft.pdfextract.core.table.detectors;

import com.abcft.pdfextract.core.model.*;
import com.abcft.pdfextract.core.model.Rectangle;
import com.abcft.pdfextract.core.table.*;
import com.abcft.pdfextract.core.table.extractors.CellFillAlgorithm;
import com.abcft.pdfextract.spi.ChartType;
import com.abcft.pdfextract.util.FloatUtils;
import com.google.common.collect.Lists;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.math3.util.FastMath;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.awt.*;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.*;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class RulingTableRegionsDetectionAlgorithm {

    static final float CELL_BORDER_EPSILON = 2.f;

    private static final Logger logger = LogManager.getLogger(RulingTableRegionsDetectionAlgorithm.class);

    private boolean isOverlapMultiRow(TextBlock checkLine) {
        if (checkLine.getSize() < 2) {
            return false;
        }

        List<TextChunk> textChunkList = checkLine.getElements();
        float avgCharHeight = checkLine.getAvgCharHeight();
        for (int i = 0; i < textChunkList.size() - 1; i++) {
            TextChunk one = textChunkList.get(i);
            TextChunk other = textChunkList.get(i + 1);
            if ((one.isMultiRowTextChunk() ^ other.isMultiRowTextChunk()) && FloatUtils.feq(one.getCenterY(), other.getCenterY(), 0.5 * avgCharHeight)) {
                return true;
            }
        }

        return false;
    }

    public static List<TableRegion> findLineTableRegion(ContentGroupPage page, List<TableRegion> candidateTableRects) {
        int mergeCount;
        do {
            mergeCount = mergeRectangle(page, candidateTableRects);
        } while (mergeCount != 0);

        //合并由于水平直线全部从某个x坐标断裂引起的表格切分问题
        mergeCrackRectangle(page, candidateTableRects);

        //filter too small rectangle
        candidateTableRects.removeIf(rectTemp -> (rectTemp.getWidth() < 5.0 || rectTemp.getHeight() < 5.0));
        return candidateTableRects;
    }

    public static List<TableRegion> findRulingRectangles(ContentGroupPage page) {
        if (page.getRulings().isEmpty()) {
            return new ArrayList<>();
        }

        //find ruling rectangle
        List<TableRegion> rulingRectangles = new ArrayList<>();

        for (Ruling rulingTemp : page.getRulings()) {
            if ((rulingTemp.vertical() && (rulingTemp.getLeft() < 1.0 || rulingTemp.getRight() > page.getWidth() - 1.0))
                    || (rulingTemp.horizontal() && (rulingTemp.getTop() < 1.0 || rulingTemp.getBottom() > page.getHeight() - 1.0))) {
                continue;
            }
            rulingRectangles.add(new TableRegion(rulingTemp.getLeft(), rulingTemp.getTop(), rulingTemp.getWidth(), rulingTemp.getHeight()));
        }

        //merge region
        findLineTableRegion(page, rulingRectangles);

        //filter too small rectangle
        Rectangle pageTextBound = page.getTextBoundingBoxWithinRegion(page);
        rulingRectangles.removeIf(rectTemp -> rectTemp.getWidth() < 5.0 || rectTemp.getHeight() < 5.0 || isRulingDislocationTable(page, rectTemp, pageTextBound));
        return rulingRectangles;
    }

    /**
     * 过滤“十”、“工”、“T”字型有线框表格
     * @param page
     * @param candidateTableRects
     */
    private void filterCrossLineTableRegion(ContentGroupPage page, List<TableRegion> candidateTableRects) {
        if (candidateTableRects == null || candidateTableRects.isEmpty()) {
            return;
        }

        if (candidateTableRects.size() == 1) {
            TableRegion tableRegion = candidateTableRects.get(0);
            if (tableRegion.overlapRatio(page) > 0.9 && (tableRegion.getWidth() > 0.9 * page.getWidth()
                    || tableRegion.getHeight() > 0.9 * page.getHeight())) {
                List<Ruling> longestHorRulings = new ArrayList<>();
                List<Ruling> longestVerRulings = new ArrayList<>();
                List<Ruling> rulings = Ruling.getRulingsFromArea(page.getRulings(), tableRegion.withCenterExpand(0.5));
                for (Ruling ruling : rulings) {
                    if (ruling.horizontal() && FloatUtils.feq(ruling.getWidth(), tableRegion.getWidth(), page.getAvgCharWidth())) {
                        longestHorRulings.add(ruling);
                    }
                    if (ruling.vertical() && FloatUtils.feq(ruling.getHeight(), tableRegion.getHeight(), page.getAvgCharWidth())) {
                        longestVerRulings.add(ruling);
                    }
                }

                boolean tableRemoveFind = false;
                if (longestHorRulings.size() == 1 && longestVerRulings.size() == 1) {
                    Ruling longestHorRuling = longestHorRulings.get(0);
                    Ruling longestVerRuling = longestVerRulings.get(0);
                    List<Ruling> borderHorRulings = new ArrayList<>(Arrays.asList(longestHorRuling, new Ruling(tableRegion.getTop(), tableRegion.getLeft()
                            ,  (float) tableRegion.getWidth(), 0), new Ruling(tableRegion.getBottom(), tableRegion.getLeft()
                            , (float) tableRegion.getWidth(), 0)));
                    List<Ruling> borderVerRulings = new ArrayList<>(Arrays.asList(longestVerRuling, new Ruling(tableRegion.getTop(),
                            tableRegion.getLeft(), 0, (float) tableRegion.getHeight()), new Ruling(tableRegion.getTop()
                            , tableRegion.getRight(),  0, (float) tableRegion.getHeight())));
                    List<Cell> cellsByLine = Ruling.findCells(borderHorRulings, borderVerRulings);

                    int littleTextCellNum = 0;
                    for (Cell cell : cellsByLine) {
                        if (page.getTextChunks(cell.rectReduce(0.5 * page.getAvgCharWidth(), 0.5 * page.getAvgCharHeight()
                                , page.getWidth(), page.getHeight())).size() <= 5) {
                            littleTextCellNum++;
                        }
                    }
                    float horDiffRatio = (longestHorRuling.getTop() - tableRegion.getTop()) / (tableRegion.getBottom() - longestHorRuling.getTop());
                    float verDiffRatio = (longestVerRuling.getLeft() - tableRegion.getLeft()) / (tableRegion.getRight() - longestHorRuling.getLeft());
                    if (cellsByLine.size() <= 4 && littleTextCellNum >= 2 && ((horDiffRatio > 8 || horDiffRatio < 1.0 / 8.0)
                            || (verDiffRatio > 8 || verDiffRatio < 1.0 / 8.0))) {
                        tableRemoveFind = true;
                    }
                } else if (longestHorRulings.isEmpty() && longestVerRulings.size() == 1){
                    List<Ruling> otherRulings = rulings.stream().filter(rul -> rul.getWidth() > 0.9 * tableRegion.getWidth()).collect(Collectors.toList());
                    if (otherRulings.size() <= 1) {
                        tableRemoveFind = true;
                    }
                } else if (longestHorRulings.size() <= 1 && longestVerRulings.isEmpty()) {
                    List<Ruling> otherRulings = rulings.stream().filter(rul -> rul.getHeight() > 0.9 * tableRegion.getHeight()).collect(Collectors.toList());
                    if (otherRulings.size() <= 1) {
                        tableRemoveFind = true;
                    }
                }

                if (tableRemoveFind) {
                    List<Ruling> innerRulings = Ruling.getRulingsFromArea(page.getRulings(), tableRegion.withCenterExpand(0.5)).stream()
                            .filter(rul -> rul.getWidth() < tableRegion.getWidth() - page.getAvgCharWidth()
                                    && rul.getHeight() < tableRegion.getHeight() - page.getAvgCharWidth())
                            .collect(Collectors.toList());
                    candidateTableRects.clear();
                    if (innerRulings.isEmpty()) {
                        return;
                    }
                    List<TableRegion> rulingRect = innerRulings.stream().map(rul -> new TableRegion(rul.getLeft(), rul.getTop()
                            , rul.getWidth(), rul.getHeight())).collect(Collectors.toList());
                    candidateTableRects.addAll(findLineTableRegion(page, rulingRect));
                    return;
                }
            }
        }

        //"工"型表格过滤
        List<NoRulingTableRegionsDetectionAlgorithm.SegmentPageInfo> segmentPageInfos = new NoRulingTableRegionsDetectionAlgorithm()
                .segmentPageByTj(page, new Rectangle(page), new ArrayList<>(), false, true);
        float pageValidWidth = TableRegionDetectionUtils.getValidTextWidthInRegion(page, page);
        boolean isEnglish = StringUtils.startsWithIgnoreCase(page.getLanguage(), "en");
        for (TableRegion tableRegion : candidateTableRects) {
            if (tableRegion.getWidth() < 0.8 * pageValidWidth || !isEnglish) {
                continue;
            }

            List<Ruling> longestHorRulings = new ArrayList<>();
            List<Ruling> longestVerRulings = new ArrayList<>();
            List<Ruling> rulings = Ruling.getRulingsFromArea(page.getRulings(), tableRegion.withCenterExpand(0.5));
            for (Ruling ruling : rulings) {
                if (ruling.horizontal() && FloatUtils.feq(ruling.getWidth(), tableRegion.getWidth(), page.getAvgCharWidth())) {
                    longestHorRulings.add(ruling);
                }
                if (ruling.vertical() && FloatUtils.feq(ruling.getHeight(), tableRegion.getHeight(), page.getAvgCharWidth())) {
                    longestVerRulings.add(ruling);
                }
            }

            boolean horRulingMatch = false;
            if (longestHorRulings.size() == 2) {
                longestHorRulings.sort(Comparator.comparing(Ruling::getTop));
                horRulingMatch = FloatUtils.feq(tableRegion.getTop(), longestHorRulings.get(0).getTop(), 1.0)
                        && FloatUtils.feq(tableRegion.getBottom(), longestHorRulings.get(1).getBottom(), 1.0);
            } else if (longestHorRulings.size() == 1) {
                horRulingMatch = FloatUtils.feq(tableRegion.getTop(), longestHorRulings.get(0).getTop(), 1.0)
                        || FloatUtils.feq(tableRegion.getBottom(), longestHorRulings.get(0).getBottom(), 1.0);
            }

            boolean verRulingMatch = false;
            if (longestVerRulings.size() == 1) {
                Ruling temp = longestVerRulings.get(0);
                verRulingMatch = temp.getLeft() > (tableRegion.getLeft() + tableRegion.getWidth() / 3.0)
                        && temp.getLeft() < (tableRegion.getRight() - tableRegion.getWidth() / 3.0);
            }

            if (horRulingMatch && verRulingMatch) {
                Ruling verticalRuling = longestVerRulings.get(0);
                Rectangle innnerRect = tableRegion.rectReduce(page.getAvgCharWidth(), page.getAvgCharHeight(), page.getWidth(), page.getHeight());
                List<Ruling> innerHorRulings = rulings.stream().filter(ruling -> ruling.horizontal() && ruling.length() > tableRegion.getWidth() / 3.0
                        && verticalRuling.intersectsLine(ruling) && ruling.getTop() > innnerRect.getTop() && ruling.getTop() < innnerRect.getBottom())
                        .collect(Collectors.toList());
                if (innerHorRulings.isEmpty()) {
                    List<NoRulingTableRegionsDetectionAlgorithm.SegmentPageInfo> innerSegmentPageInfos = segmentPageInfos
                            .stream().filter(seg -> seg.verticalOverlapRatio(tableRegion) > 0.5
                                    && (seg.getRight() < verticalRuling.getLeft() + page.getAvgCharWidth()
                                    || seg.getLeft() > verticalRuling.getLeft() - page.getAvgCharWidth())).collect(Collectors.toList());
                    if (!innerSegmentPageInfos.isEmpty()) {
                        List<Ruling> innerRulings = rulings.stream().filter(rul -> (rul.getWidth() < tableRegion.getWidth() - page.getAvgCharWidth()
                                        && rul.getHeight() < tableRegion.getHeight() - page.getAvgCharWidth()))
                                .collect(Collectors.toList());
                        candidateTableRects.clear();
                        if (innerRulings.isEmpty()) {
                            return;
                        }
                        List<TableRegion> rulingRect = innerRulings.stream().map(rul -> new TableRegion(rul.getLeft(), rul.getTop()
                                , rul.getWidth(), rul.getHeight())).collect(Collectors.toList());
                        candidateTableRects.addAll(findLineTableRegion(page, rulingRect));
                        return;
                    }

                }
            }
        }
    }

    public static TableRegion getValidTableRegions(ContentGroupPage page, Rectangle2D region) {
        TableRegion oldTableRegion = new TableRegion(region);
        List<TextChunk> textChunks = new ArrayList<>();
        for (TextChunk textChunk : page.getTextChunks(oldTableRegion)) {
            if (StringUtils.isBlank(textChunk.getText())) {
                continue;
            }
            Rectangle chunkBound = textChunk.toRectangle();
            if (oldTableRegion.horizontallyOverlapRatio(chunkBound) > 0.2 && oldTableRegion.verticalOverlapRatio(chunkBound) > 0.2) {
                textChunks.add(textChunk);
            }
        }

        if (textChunks.isEmpty()) {
            logger.info("hintArea has no chunk");
            return oldTableRegion;
        }
        Rectangle oldChunkBound = Rectangle.boundingBoxOf(textChunks);
        List<Ruling> intersectRulings = Ruling.getRulingsFromArea(page.getRulings(), oldChunkBound.withCenterExpand(0.5));
        if (intersectRulings.isEmpty()) {
            return oldTableRegion;
        }
        TableRegion newTableRegion = new TableRegion(oldChunkBound.createUnion(Ruling.getBounds(intersectRulings)));
        Rectangle newChunkBound = Rectangle.boundingBoxOf(page.getTextChunks(newTableRegion).stream()
                .filter(chunk -> StringUtils.isNotBlank(chunk.getText())).collect(Collectors.toList()));
        if (newChunkBound.nearlyEquals(oldChunkBound, 0.5)) {
            return newTableRegion;
        } else {
            return oldTableRegion;
        }
    }
    
    public static int mergeRectangle(ContentGroupPage page, List<TableRegion> rulingTableRegions) {
        /**find the max bounding box by merging all ruling and clipareas
         *      +——————+                       +————————————————+
         *      |      |                       |                |
         *      |      |                       |                |
         * —————+      +————+                  |                |
         * |                |      ===》       |                |
         * _____+      +————|                  |                |
         *             |                       |                |
         *             |                       |                |
         *             +————+                  +————————————————+
         * principle:finding the adjacentent or intersection rect and merge to the bounding
         */

        /**
         * 如下断裂情况将被合并
         * ———|—————| —————|———
         * ———|—————| —————|———
         * ———|—————| —————|———
         */
        int mergeCount = 0;
        double thresLow = FastMath.max(1.5, 0.8 * page.getMinCharWidth());
        double thresHigh = page.getAvgCharWidth();
        if (!rulingTableRegions.isEmpty()) {
            for (int i = 0; i < rulingTableRegions.size() - 1; ) {
                Rectangle rc1 = rulingTableRegions.get(i);
                boolean mergeFlag = false;
                for (int j = i + 1; j < rulingTableRegions.size(); j++) {
                    Rectangle rc2 = rulingTableRegions.get(j);
                    Rectangle unionRect = Rectangle.boundingBoxOf(Arrays.asList(rc1, rc2));
                    double dis = rc1.calDistance(rc2);
                    if (rc1.isShapeIntersects(rc2) || dis < thresLow) {
                        Rectangle2D.union(rc1, rc2, rc1);
                        rc2.markDeleted();
                        mergeFlag = true;
                        mergeCount++;
                    } else if (dis < thresHigh && rc1.isVerticallyOverlap(rc2)) {
                        if (rc2.getLeft() > rc1.getRight()) {
                            List<Ruling> betweenRulings = TableRegionDetectionUtils.getRulingBetween(page
                                    , TableRegionDetectionUtils.DirectionType.VERTICAL, 3 * page.getAvgCharWidth()
                                    , rc1.getRight() - 0.1, rc2.getLeft() + 0.1);
                            betweenRulings = betweenRulings.stream().filter(ruling -> unionRect.isVerticallyOverlap(ruling.toRectangle()))
                                    .collect(Collectors.toList());
                            if (betweenRulings.size() == 1) {
                                Ruling betweenRuling = betweenRulings.get(0);
                                double thres;
                                if (Math.abs(betweenRuling.getCenterX() - rc1.getRight()) < Math.abs(betweenRuling.getCenterX() - rc2.getLeft())) {
                                    thres = rc2.getLeft();
                                } else {
                                    thres = rc1.getRight();
                                }
                                Ruling leftRuling = TableRegionDetectionUtils.getNeighborRuling(page, TableRegionDetectionUtils.PositionType.LEFT,
                                        TableRegionDetectionUtils.DirectionType.VERTICAL, 3 * page.getAvgCharWidth(), thres, unionRect);
                                Ruling rightRuling = TableRegionDetectionUtils.getNeighborRuling(page, TableRegionDetectionUtils.PositionType.RIGHT,
                                        TableRegionDetectionUtils.DirectionType.VERTICAL, 3 * page.getAvgCharWidth(), thres, unionRect);
                                if (leftRuling != null && rightRuling != null && dis < 0.15 * (rightRuling.getCenterX() - leftRuling.getCenterX())) {
                                    Rectangle2D.union(rc1, rc2, rc1);
                                    rc2.markDeleted();
                                    mergeFlag = true;
                                    mergeCount++;
                                }
                            }
                        } else if (rc1.getLeft() > rc2.getRight()) {
                            List<Ruling> betweenRulings = TableRegionDetectionUtils.getRulingBetween(page
                                    , TableRegionDetectionUtils.DirectionType.VERTICAL, 3 * page.getAvgCharWidth()
                                    , rc2.getRight() - 0.1, rc1.getLeft() + 0.1);
                            betweenRulings = betweenRulings.stream().filter(ruling -> unionRect.isVerticallyOverlap(ruling.toRectangle()))
                                    .collect(Collectors.toList());
                            if (betweenRulings.size() == 1) {
                                Ruling betweenRuling = betweenRulings.get(0);
                                double thres;
                                if (Math.abs(betweenRuling.getCenterX() - rc2.getRight()) < Math.abs(betweenRuling.getCenterX() - rc1.getLeft())) {
                                    thres = rc2.getRight();
                                } else {
                                    thres = rc1.getLeft();
                                }
                                Ruling leftRuling = TableRegionDetectionUtils.getNeighborRuling(page, TableRegionDetectionUtils.PositionType.LEFT,
                                        TableRegionDetectionUtils.DirectionType.VERTICAL, 3 * page.getAvgCharWidth(), thres, unionRect);
                                Ruling rightRuling = TableRegionDetectionUtils.getNeighborRuling(page, TableRegionDetectionUtils.PositionType.RIGHT,
                                        TableRegionDetectionUtils.DirectionType.VERTICAL, 3 * page.getAvgCharWidth(), thres, unionRect);
                                if (leftRuling != null && rightRuling != null && dis < 0.15 * (rightRuling.getCenterX() - leftRuling.getCenterX())) {
                                    Rectangle2D.union(rc1, rc2, rc1);
                                    rc2.markDeleted();
                                    mergeFlag = true;
                                    mergeCount++;
                                }
                            }
                        }
                    } else if (dis < thresHigh && rc1.isHorizontallyOverlap(rc2)) {
                        if (rc2.getTop() > rc1.getBottom()) {
                            List<Ruling> betweenRulings = TableRegionDetectionUtils.getRulingBetween(page
                                    , TableRegionDetectionUtils.DirectionType.HORIZONTAL, 3 * page.getAvgCharWidth()
                                    , rc1.getBottom() - 0.1, rc2.getTop() + 0.1);
                            betweenRulings = betweenRulings.stream().filter(ruling -> unionRect.isHorizontallyOverlap(ruling.toRectangle()))
                                    .collect(Collectors.toList());
                            if (betweenRulings.size() == 1) {
                                Ruling betweenRuling = betweenRulings.get(0);
                                double thres;
                                if (Math.abs(betweenRuling.getCenterY() - rc1.getBottom()) < Math.abs(betweenRuling.getCenterY() - rc2.getTop())) {
                                    thres = rc2.getTop();
                                } else {
                                    thres = rc1.getBottom();
                                }
                                Ruling topRuling = TableRegionDetectionUtils.getNeighborRuling(page, TableRegionDetectionUtils.PositionType.TOP,
                                        TableRegionDetectionUtils.DirectionType.HORIZONTAL, 3 * page.getAvgCharWidth(), thres, unionRect);
                                Ruling bottomRuling = TableRegionDetectionUtils.getNeighborRuling(page, TableRegionDetectionUtils.PositionType.BOTTOM,
                                        TableRegionDetectionUtils.DirectionType.HORIZONTAL, 3 * page.getAvgCharWidth(), thres, unionRect);
                                if (topRuling != null && bottomRuling != null && dis < 0.15 * (bottomRuling.getCenterY() - topRuling.getCenterY())) {
                                    Rectangle2D.union(rc1, rc2, rc1);
                                    rc2.markDeleted();
                                    mergeFlag = true;
                                    mergeCount++;
                                }
                            }
                        } else if (rc1.getTop() > rc2.getBottom()) {
                            List<Ruling> betweenRulings = TableRegionDetectionUtils.getRulingBetween(page
                                    , TableRegionDetectionUtils.DirectionType.HORIZONTAL, 3 * page.getAvgCharWidth()
                                    , rc2.getBottom() - 0.1, rc1.getTop() + 0.1);
                            betweenRulings = betweenRulings.stream().filter(ruling -> unionRect.isHorizontallyOverlap(ruling.toRectangle()))
                                    .collect(Collectors.toList());
                            if (betweenRulings.size() == 1) {
                                Ruling betweenRuling = betweenRulings.get(0);
                                double thres;
                                if (Math.abs(betweenRuling.getCenterY() - rc2.getBottom()) < Math.abs(betweenRuling.getCenterY() - rc1.getTop())) {
                                    thres = rc2.getBottom();
                                } else {
                                    thres = rc1.getTop();
                                }
                                Ruling topRuling = TableRegionDetectionUtils.getNeighborRuling(page, TableRegionDetectionUtils.PositionType.TOP,
                                        TableRegionDetectionUtils.DirectionType.HORIZONTAL, 3 * page.getAvgCharWidth(), thres, unionRect);
                                Ruling bottomRuling = TableRegionDetectionUtils.getNeighborRuling(page, TableRegionDetectionUtils.PositionType.BOTTOM,
                                        TableRegionDetectionUtils.DirectionType.HORIZONTAL, 3 * page.getAvgCharWidth(), thres, unionRect);
                                if (topRuling != null && bottomRuling != null && dis < 0.15 * (bottomRuling.getCenterY() - topRuling.getCenterY())) {
                                    Rectangle2D.union(rc1, rc2, rc1);
                                    rc2.markDeleted();
                                    mergeFlag = true;
                                    mergeCount++;
                                }
                            }
                        }
                    }
                }
                rulingTableRegions.removeIf(Rectangle::isDeleted);
                if (!mergeFlag) {
                    i++;
                }
            }
        }
        return mergeCount;
    }

    private static List<TableRegion> mergeCrackRectangle(ContentGroupPage page, List<TableRegion> candidateTableRects) {
        if (candidateTableRects.size() < 2) {
            return candidateTableRects;
        }

        for (TableRegion tr : candidateTableRects) {
            tr.setDeleted(false);
        }
        candidateTableRects.sort(Comparator.comparing(TableRegion::getTop));
        List<List<TableRegion>> groups = new ArrayList<>();
        for (TableRegion tr : candidateTableRects) {
            if (groups.isEmpty()) {
                groups.add(Lists.newArrayList(tr));
                continue;
            }

            List<TableRegion> lastGroup = groups.get(groups.size() - 1);
            Rectangle unionRect = Rectangle.boundingBoxOf(lastGroup);
            if (FloatUtils.feq(unionRect.getTop(), tr.getTop(), 0.5) && FloatUtils.feq(unionRect.getBottom(), tr.getBottom(), 0.5)) {
                lastGroup.add(tr);
            } else {
                groups.add(Lists.newArrayList(tr));
            }
        }

        double thres = 0.8 * page.getAvgCharWidth();
        for (List<TableRegion> group : groups) {
            if (group.size() < 2) {
                continue;
            }

            group.sort(Comparator.comparing(TableRegion::getLeft));
            for (int i = 0; i < group.size() - 1; ) {
                boolean mergeFlag = false;
                TableRegion tr1 = group.get(i);
                TableRegion tr2 = group.get(i + 1);
                if (tr2.getLeft() - tr1.getRight() < thres) {
                    List<Ruling> rulings1 = TableRegionDetectionUtils.getOneRulingEveryGroup(
                            Ruling.getRulingsFromArea(page.getHorizontalRulings(), tr1.withCenterExpand(0.5))
                            , TableRegionDetectionUtils.PositionType.RIGHT);
                    List<Ruling> rulings2 = TableRegionDetectionUtils.getOneRulingEveryGroup(
                            Ruling.getRulingsFromArea(page.getHorizontalRulings(), tr2.withCenterExpand(0.5))
                            , TableRegionDetectionUtils.PositionType.RIGHT);
                    if (rulings1.isEmpty() || rulings2.isEmpty()) {
                        i++;
                        continue;
                    }
                    boolean allMatch = true;
                    for (Ruling rul1 : rulings1) {
                        boolean oneMatch = false;
                        for (Ruling rul2 : rulings2) {
                            if (FloatUtils.feq(rul1.getTop(), rul2.getTop(), 0.1) && FloatUtils.feq(rul1.getRight(), rul2.getLeft(), thres)) {
                                oneMatch = true;
                                break;
                            }
                        }
                        if (!oneMatch) {
                            allMatch = false;
                            break;
                        }
                    }

                    if (allMatch) {
                        tr1.setRect(tr1.createUnion(tr2));
                        tr2.markDeleted();
                        mergeFlag = true;
                    }
                }
                if (mergeFlag) {
                    group.removeIf(TableRegion::isDeleted);
                } else {
                    i++;
                }
            }
        }

        return groups.stream().flatMap(group -> group.stream()).collect(Collectors.toList());
    }

    /**
     * 仅有外界框，内部没有符合有线表格特征的直线存在
     */
    public static boolean isBoundingTable(ContentGroupPage page, Rectangle rc) {
        Rectangle rectTemp = rc.rectReduce(3.0, 3.0, page.width, page.height);
        List<Ruling> hRulings = page.getHorizontalRulings().stream().filter(
                rul -> rectTemp.isShapeIntersects(rul.toRectangle())).collect(Collectors.toList());
        List<Ruling> vRulings = page.getVerticalRulings().stream().filter(
                rul -> rectTemp.isShapeIntersects(rul.toRectangle())).collect(Collectors.toList());

        if (hRulings.isEmpty() && vRulings.isEmpty()) {
            return true;
        } else if (hRulings.size() >= 1 && vRulings.size() >= 1) {
            if (hasIntersectionLines(hRulings, vRulings) || hasNearIntersectionLines(hRulings, vRulings, 3.0f)) {
                return false;
            }
            return true;
        } else if (hRulings.size() >= 1) {
            for (Ruling hRul : hRulings) {
                if (FloatUtils.feq(rc.getLeft(), hRul.getLeft(), 3.0f) && FloatUtils.feq(rc.getRight(), hRul.getRight(), 3.0f)) {
                    return false;
                }
            }
            return true;
        } else {
            for (Ruling vRul : vRulings) {
                if (FloatUtils.feq(rc.getTop(), vRul.getTop(), 3.0f) && FloatUtils.feq(rc.getBottom(), vRul.getBottom(), 3.0f)) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * 区域中有比较明显的垂直线错位情况通常属于分栏线干扰，需加以去除
     */
    public static boolean isRulingDislocationTable(ContentGroupPage page, Rectangle rc, Rectangle pageTextBound) {
        if (rc.getArea() < 0.8 * pageTextBound.getArea()) {
            return false;
        }
        Rectangle rectTmp = rc.withCenterExpand(2.0f,2.0f);
        List<Ruling> hRulings = page.getHorizontalRulings().stream().filter(
                rul -> rectTmp.isShapeIntersects(rul.toRectangle())).collect(Collectors.toList());
        List<Ruling> vRulings = page.getVerticalRulings().stream().filter(
                rul -> rectTmp.isShapeIntersects(rul.toRectangle())).collect(Collectors.toList());

        float lineRatio = 0.8f;

        float topLineLength = 0.0f;
        float bottomLineLength = 0.0f;
        float leftLineLength = 0.0f;
        float rightLineLength = 0.0f;

        for (Ruling rul : hRulings) {
            if (FloatUtils.feq(rc.getTop(), rul.getTop(), 5.0)) {
                topLineLength += rul.getWidth();
            }
            if (FloatUtils.feq(rc.getBottom(), rul.getBottom(), 5.0)) {
                bottomLineLength += rul.getWidth();
            }
        }

        for (Ruling rul : vRulings) {
            if (FloatUtils.feq(rc.getLeft(), rul.getLeft(), 5.0)) {
                leftLineLength += rul.getHeight();
            }
            if (FloatUtils.feq(rc.getRight(), rul.getRight(), 5.0)) {
                rightLineLength += rul.getHeight();
            }
        }

        boolean topFind = topLineLength / rc.getWidth() > lineRatio;
        boolean bottomFind = bottomLineLength / rc.getWidth() > lineRatio;
        boolean leftFind = leftLineLength / rc.getHeight() > lineRatio;
        boolean rightFind = rightLineLength / rc.getHeight() > lineRatio;

        //只对没有外接框的表格进行判断
        float maxSpan = 20;
        if (topFind && bottomFind && leftFind && rightFind) {
            return false;
        } else {
            List<Ruling> veiticalLongRulings = vRulings.stream().filter(rul -> rul.length() > 0.25 * page.getHeight())
                    .collect(Collectors.toList());
            for (Ruling rul : veiticalLongRulings) {
                if (FloatUtils.feq(rc.getTop(), rul.getTop(), 5.0) && rul.getBottom() < rc.getBottom() - maxSpan) {
                    boolean findFlag = false;
                    for (Ruling horizontalRul : hRulings) {
                        if (FloatUtils.feq(horizontalRul.getTop(), rul.getBottom(), maxSpan)
                                && (horizontalRul.getRight() > rul.getLeft() - maxSpan && horizontalRul.getLeft() < rul.getRight() + maxSpan)) {
                            findFlag = true;
                            break;
                        }
                    }
                    if (!findFlag) {
                        return true;
                    }
                } else if (FloatUtils.feq(rc.getBottom(), rul.getBottom(), 5.0) && rul.getTop() > rc.getTop() + maxSpan) {
                    boolean findFlag = false;
                    for (Ruling horizontalRul : hRulings) {
                        if (FloatUtils.feq(horizontalRul.getTop(), rul.getTop(), maxSpan)
                                && (horizontalRul.getRight() > rul.getLeft() - maxSpan && horizontalRul.getLeft() < rul.getRight() + maxSpan)) {
                            findFlag = true;
                            break;
                        }
                    }
                    if (!findFlag) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    private boolean isSubTable(ContentGroupPage page, List<TableRegion> rulingTableRegions, Rectangle rc) {
        //judge left
        Rectangle rectLeft = new Rectangle(0, rc.getTop(), rc.getLeft(), (float)(rc.getHeight()))
                .rectReduce(1.0f, 0, page.getWidth(), page.getHeight());
        List<TextChunk> chunkTemp = page.getMergeChunks(rectLeft);
        List<TextChunk> chunkLeft = new ArrayList<>();
        if (!chunkTemp.isEmpty()) {
            for (TextChunk tc : chunkTemp) {
                if (StringUtils.isBlank(tc.getText().trim())) {
                    continue;
                }
                boolean intersectFlag = false;
                for (Rectangle rcTemp : rulingTableRegions) {
                    if (rcTemp.nearlyEquals(rc, 1.0f)) {
                        continue;
                    }
                    if (rcTemp.isShapeIntersects(tc)) {
                        intersectFlag = true;
                        break;
                    }
                }
                if (!intersectFlag) {
                    chunkLeft.add(tc);
                }
            }
        }

        //judge right
        Rectangle rectRight = new Rectangle(rc.getRight(), rc.getTop(), (float)(page.getWidth()) - rc.getRight(), (float)(rc.getHeight()))
                .rectReduce(1.0f, 0, page.getWidth(), page.getHeight());
        chunkTemp = page.getMergeChunks(rectRight);
        List<TextChunk> chunkRight = new ArrayList<>();
        if (!chunkTemp.isEmpty()) {
            for (TextChunk tc : chunkTemp) {
                if (StringUtils.isBlank(tc.getText().trim())) {
                    continue;
                }
                boolean intersectFlag = false;
                for (Rectangle rcTemp : rulingTableRegions) {
                    if (rcTemp.nearlyEquals(rc, 1.0f)) {
                        continue;
                    }
                    if (rcTemp.isShapeIntersects(tc)) {
                        intersectFlag = true;
                        break;
                    }
                }
                if (!intersectFlag) {
                    chunkRight.add(tc);
                }
            }
        }

        if (chunkLeft.isEmpty() && chunkRight.isEmpty()) {
            return false;
        }
        if (!chunkLeft.isEmpty()) {
            double validTextWidth = Rectangle.boundingBoxOf(chunkLeft).getWidth();
            for (TextChunk tc : chunkLeft) {
                if (tc.getWidth() > 1.5 * validTextWidth) {
                    return false;
                }
            }
        }
        if (!chunkRight.isEmpty()) {
            double validTextWidth = Rectangle.boundingBoxOf(chunkRight).getWidth();
            for (TextChunk tc : chunkRight) {
                if (tc.getWidth() > 1.5 * validTextWidth) {
                    return false;
                }
            }
        }
        List<TextBlock> textBlocks = page.getTextLines().stream().filter(line -> rc.intersects(line.getBounds2D())).collect(Collectors.toList());
        if (chunkLeft.size() < 0.5 * textBlocks.size() && chunkRight.size() < 0.5 * textBlocks.size()) {
            return false;
        }

        return true;
    }

    //考虑到ruling提取可能存在错误，对于距离较近的Ruling进行判断
    private static boolean hasNearIntersectionLines(List<Ruling> hRulings, List<Ruling> vRulings, float thres) {
        if (hRulings.isEmpty() || vRulings.isEmpty()) {
            return false;
        }
        for (Ruling hRul : hRulings) {
            Rectangle hRulRect = hRul.toRectangle();
            for (Ruling vRul : vRulings) {
                Rectangle vRulRect = vRul.toRectangle();
                if (hRulRect.isShapeAdjacent(vRulRect, thres)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean hasIntersectionLines(List<Ruling> innerRulings) {
        List<Ruling> hRulings = innerRulings.stream().filter(rul -> rul.horizontal()).collect(Collectors.toList());
        List<Ruling> vRulings = innerRulings.stream().filter(rul -> rul.vertical()).collect(Collectors.toList());
        return hasIntersectionLines(hRulings, vRulings);
    }

    public static boolean hasIntersectionLines(List<Ruling> hRulings, List<Ruling> vRulings) {
        if (hRulings.isEmpty() || vRulings.isEmpty()) {
            return false;
        } else if (Ruling.findIntersections(hRulings, vRulings).isEmpty()) {
            return false;
        }
        return true;
    }

    private void addBorderRuling(Rectangle rc, List<Ruling> hRulings, List<Ruling> vRulings) {
        float lineRatio = 0.8f;

        float rcTop = rc.getTop();
        float rcBottom = rc.getBottom();
        float rcLeft = rc.getLeft();
        float rcRight = rc.getRight();

        float topLineLength = 0.0f;
        float bottomLineLength = 0.0f;
        float leftLineLength = 0.0f;
        float rightLineLength = 0.0f;

        for (Ruling rul : hRulings) {
            if (FloatUtils.feq(rcTop, rul.getTop(), 5.0)) {
                topLineLength += rul.getWidth();
            }
            if (FloatUtils.feq(rcBottom, rul.getBottom(), 5.0)) {
                bottomLineLength += rul.getWidth();
            }
        }

        for (Ruling rul : vRulings) {
            if (FloatUtils.feq(rcLeft, rul.getLeft(), 5.0)) {
                leftLineLength += rul.getHeight();
            }
            if (FloatUtils.feq(rcRight, rul.getRight(), 5.0)) {
                rightLineLength += rul.getHeight();
            }
        }

        if (topLineLength / rc.getWidth() < lineRatio) {
            hRulings.add(new Ruling(rc.getTop(), rc.getLeft(), (float)rc.getWidth(), 0.0f));
        }
        if (bottomLineLength / rc.getWidth() < lineRatio) {
            hRulings.add(new Ruling(rc.getBottom(), rc.getLeft(), (float)rc.getWidth(), 0.0f));
        }
        if (leftLineLength / rc.getHeight() < lineRatio) {
            vRulings.add(new Ruling(rc.getTop(), rc.getLeft(), 0.0f, (float)rc.getHeight()));
        }
        if (rightLineLength / rc.getHeight() < lineRatio) {
            vRulings.add(new Ruling(rc.getTop(), rc.getRight(), 0.0f, (float)rc.getHeight()));
        }
    }

    public static HashMap<String, Integer> calRowColNum(List<CandidateCell> cells) {
        HashMap<String, Integer> rcMap = new HashMap<>();

        if (cells.isEmpty()) {
            return rcMap;
        }

        List<List<CandidateCell>> rowsOfCells = new ArrayList<>();
        List<CandidateCell> lastRow = new ArrayList<>();
        cells.sort(Comparator.comparingDouble(Rectangle::getTop));
        Iterator<CandidateCell> iter = cells.iterator();
        CandidateCell c = iter.next();
        float lastTop = c.getTop();
        lastRow.add(c);
        rowsOfCells.add(lastRow);
        while (iter.hasNext()) {
            c = iter.next();
            if (!FloatUtils.feq(c.getTop(), lastTop)) {
                lastRow = new ArrayList<>();
                rowsOfCells.add(lastRow);
            }
            lastRow.add(c);
            lastTop = c.getTop();
        }

        int colNum = 0;
        for (List<CandidateCell> row : rowsOfCells) {
            colNum = Math.max(colNum, row.size());
        }
        int rowNum = rowsOfCells.size();

        rcMap.put("rowNum", rowNum);
        rcMap.put("colNum", colNum);

        return rcMap;
    }

    private List<List<CandidateCell>> collectHorizontalAlignCells(List<CandidateCell> cells) {
        List<List<CandidateCell>> rowsOfCells = new ArrayList<>();

        if (cells.isEmpty()) {
            return rowsOfCells;
        }

        cells.sort(Comparator.comparingDouble(Rectangle::getTop));
        List<CandidateCell> cellListTemp = new ArrayList<>();
        float lastTop = cells.get(0).getTop();
        cellListTemp.add(cells.get(0));
        for (int i = 1; i < cells.size(); i++) {
            CandidateCell ce = cells.get(i);
            if (FloatUtils.feq(ce.getTop(), lastTop, CELL_BORDER_EPSILON)) {
                cellListTemp.add(ce);
            }
            if (!(FloatUtils.feq(ce.getTop(), lastTop, CELL_BORDER_EPSILON)) || (i == cells.size()-1)) {
                cellListTemp.sort(Comparator.comparingDouble(Rectangle::getLeft));
                List<CandidateCell> horizontalAlignCells = new ArrayList<>();
                CandidateCell base = cellListTemp.get(0);
                float lastBottom = base.getBottom();
                float lastRight = base.getRight();
                horizontalAlignCells.add(base);
                rowsOfCells.add(horizontalAlignCells);
                for (int j = 1; j < cellListTemp.size(); j++) {
                    CandidateCell other = cellListTemp.get(j);
                    if (!(FloatUtils.feq(other.getBottom(), lastBottom, CELL_BORDER_EPSILON)
                            && FloatUtils.feq(other.getLeft(), lastRight, CELL_BORDER_EPSILON))) {
                        horizontalAlignCells = new ArrayList<>();
                        rowsOfCells.add(horizontalAlignCells);
                    }
                    horizontalAlignCells.add(other);
                    lastRight = other.getRight();
                    lastBottom = other.getBottom();
                }
                cellListTemp.clear();
                cellListTemp.add(ce);
            }
            lastTop = ce.getTop();
        }

        if (cells.size() >= 2 && !(FloatUtils.feq(cells.get(cells.size() - 1).getTop(), cells.get(cells.size() - 2).getTop()
                , CELL_BORDER_EPSILON))) {
            rowsOfCells.add(Lists.newArrayList(cells.get(cells.size() - 1)));
        }

        return rowsOfCells;
    }

    private List<List<CandidateCell>> collectVerticalAlignCells(List<CandidateCell> cells) {
        List<List<CandidateCell>> colsOfCells = new ArrayList<>();

        if (cells.isEmpty()) {
            return colsOfCells;
        }

        cells.sort(Comparator.comparingDouble(Rectangle::getLeft));
        List<CandidateCell> cellListTemp = new ArrayList<>();
        float lastLeft = cells.get(0).getLeft();
        cellListTemp.add(cells.get(0));
        for (int i = 1; i < cells.size(); i++) {
            CandidateCell ce = cells.get(i);
            if (FloatUtils.feq(ce.getLeft(), lastLeft, CELL_BORDER_EPSILON)) {
                cellListTemp.add(ce);
            }
            if (!(FloatUtils.feq(ce.getLeft(), lastLeft, CELL_BORDER_EPSILON)) || (i == cells.size()-1)) {
                cellListTemp.sort(Comparator.comparingDouble(Rectangle::getTop));
                List<CandidateCell> verticalAlignCells = new ArrayList<>();
                CandidateCell base = cellListTemp.get(0);
                float lastRight = base.getRight();
                float lastBottom = base.getBottom();
                verticalAlignCells.add(base);
                colsOfCells.add(verticalAlignCells);
                for (int j = 1; j < cellListTemp.size(); j++) {
                    CandidateCell other = cellListTemp.get(j);
                    if (!(FloatUtils.feq(other.getRight(), lastRight, CELL_BORDER_EPSILON)
                            && FloatUtils.feq(other.getTop(), lastBottom, CELL_BORDER_EPSILON))) {
                        verticalAlignCells = new ArrayList<>();
                        colsOfCells.add(verticalAlignCells);
                    }
                    verticalAlignCells.add(other);
                    lastRight = other.getRight();
                    lastBottom = other.getBottom();
                }
                cellListTemp.clear();
                cellListTemp.add(ce);
            }
            lastLeft = ce.getLeft();
        }

        if (cells.size() >= 2 && !(FloatUtils.feq(cells.get(cells.size() - 1).getLeft(), cells.get(cells.size() - 2).getLeft()
                , CELL_BORDER_EPSILON))) {
            colsOfCells.add(Lists.newArrayList(cells.get(cells.size() - 1)));
        }

        return colsOfCells;
    }

    private HashMap<Point2D, java.lang.Float> calVerticalAlignGap(ContentGroupPage page, CandidateCell cell) {
        HashMap<Point2D, java.lang.Float> gapMap = new HashMap<>();
        List<List<TextChunk>> collectedTextChunks = cell.getCollectedTextChunk(page);
        if (collectedTextChunks.size() < 2) {
            return new HashMap<>();
        }

        //find line of max col;
        int maxColumn = 0;
        for (List<TextChunk> te : collectedTextChunks) {
            if (te.size() > maxColumn) {
                maxColumn = te.size();
            }
        }
        if (maxColumn <= 1) {
            return new HashMap<>();
        }

        List<List<TextChunk>> maxColLines= new ArrayList<>();
        for (List<TextChunk> te : collectedTextChunks) {
            if (te.size() == maxColumn) {
                maxColLines.add(te);
            }
        }

        List<TextChunk> baseTextChunkList = new ArrayList<>();
        TextChunk baseTextChunk = new TextChunk();
        for (int i = 0; i < maxColumn; i++) {
            double baseWidth = 0.0;
            for (List<TextChunk> te : maxColLines) {
                if (baseWidth < te.get(i).getWidth()) {
                    baseTextChunk = te.get(i);
                    baseWidth = te.get(i).getWidth();
                }
            }
            baseTextChunkList.add(baseTextChunk);
        }

        //find aligned textChunk
        List<List<TextChunk>> allAlignedCol = new ArrayList<>();
        for (TextChunk baseChunk : baseTextChunkList) {
            List<TextChunk> alignedColTextChunks = new ArrayList<>();
            for (List<TextChunk> te : collectedTextChunks) {
                List<TextChunk> chunkListTemp = te.stream().filter(x -> x.isHorizontallyOverlap(baseChunk))
                        .collect(Collectors.toList());
                if (chunkListTemp.size() > 1) {
                    return new HashMap<>();
                } else if (!chunkListTemp.isEmpty()) {
                    alignedColTextChunks.add(chunkListTemp.get(0));
                }
            }
            allAlignedCol.add(alignedColTextChunks);
        }

        //calculate the gap between two adjacent col
        double lastRight = allAlignedCol.get(0).stream().map(Rectangle2D.Float::getMaxX).max(Comparator.comparing(u -> u)).get();
        for (int i = 1; i < allAlignedCol.size(); i++) {
            double nowLeft = allAlignedCol.get(i).stream().map(Rectangle2D.Float::getMinX).min(Comparator.comparing(u -> u)).get();
            double nowRight = allAlignedCol.get(i).stream().map(Rectangle2D.Float::getMaxX).max(Comparator.comparing(u -> u)).get();
            if (nowLeft < lastRight) {
                return new HashMap<>();
            } else {
                gapMap.put(new Point2D.Float((float)lastRight, (float)nowLeft), (float)(nowLeft - lastRight));
            }
            lastRight = nowRight;
        }

        return gapMap;
    }

    private boolean isHorizontalHardAlign(List<TextChunk> textChunks) {
        if (textChunks == null || textChunks.size() < 2) {
            return false;
        }

        textChunks.sort(Comparator.comparing(TextChunk::getMinY));
        for (TextChunk tc1 : textChunks) {
            Rectangle bound1 = new Rectangle(tc1.getVisibleBBox());
            for (TextChunk tc2 : textChunks) {
                Rectangle bound2 = new Rectangle(tc2.getVisibleBBox());
                if (Objects.equals(tc1, tc2)) {
                    continue;
                }
                if (bound1.isVerticallyOverlap(bound2) && !bound1.isHorizontallyOverlap(bound2) &&
                        FastMath.abs(tc1.getCenterY() - tc2.getCenterY()) > 0.15 * FastMath.min(tc1.getHeight(), tc2.getHeight())) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * if table is not all comprised of line seg, return true
     */
    private boolean judgeTextBlock(ContentGroupPage page, List<TextBlock> textBlockList, List<Ruling> vRulings, Rectangle rc) {
        if (judgeInnerTextBlock(page,textBlockList,vRulings) || judgeOuterTextBlock(page,textBlockList,rc)) {
            return true;
        }
        return false;
    }

    private boolean judgeInnerTextBlock(ContentGroupPage page, List<TextBlock> textBlockList, List<Ruling> vRulings) {
        if (textBlockList.isEmpty()) {
            return false;
        }

        vRulings.sort(Comparator.comparing(Ruling::getLeft));
        for (TextBlock tb : textBlockList) {
            int largeGapNum = 0;
            List<TextChunk> tc = new ArrayList<>(tb.getElements());
            tc.sort(Comparator.comparing(TextChunk::getLeft));
            if (tc.size() >= 2) {
                for (int i = 0; i < tc.size() - 1; i++) {
                    boolean hasRulingBetween = false;
                    for (Ruling rul : vRulings) {
                        if (rul.getLeft() > tc.get(i).getRight() && rul.getLeft() < tc.get(i + 1).getLeft()) {
                            hasRulingBetween = true;
                            break;
                        }
                    }
                    if (!hasRulingBetween && tc.get(i + 1).getLeft() - tc.get(i).getRight() > 5 * page.getAvgCharWidth()) {
                        largeGapNum++;
                    }
                }
            }
            if (largeGapNum >= 2) {
                return true;
            }
        }
        return false;
    }

    private boolean judgeOuterTextBlock(ContentGroupPage page, List<TextBlock> textBlockList, Rectangle rc) {
        if (textBlockList.isEmpty()) {
            return false;
        }
        List<TextBlock> textRows = page.getTextLines();
        TextBlock topOuterTextBlock = new TextBlock();
        TextBlock bottomOuterTextBlock = new TextBlock();
        List<TextBlock> textChoose = TableRegion.getTopOuterTextBlock(page, rc, textRows, 1);
        if (!textChoose.isEmpty()) {
            topOuterTextBlock = textChoose.get(0);
        }
        textChoose = TableRegion.getBottomOuterTextBlock(page, rc, textRows, 1);
        if (!textChoose.isEmpty()) {
            bottomOuterTextBlock = textChoose.get(0);
        }

        if (topOuterTextBlock.getSize() == 0 && bottomOuterTextBlock.getSize() == 0) {
            return false;
        }

        int largeGapNum = 0;
        if (topOuterTextBlock.getSize() >= 2 && textBlockList.get(0).getTop() - topOuterTextBlock.getBottom() < 2 * page.getAvgCharHeight()) {
            List<TextChunk> tc = topOuterTextBlock.getElements();
            for (int i = 0; i < tc.size() - 1; i++) {
                if (tc.get(i + 1).getLeft() - tc.get(i).getRight() > 3 * page.getAvgCharWidth()) {
                    largeGapNum++;
                }
                if (tc.get(i + 1).getLeft() - tc.get(i).getRight() > 8 * page.getAvgCharWidth()) {
                    rc.markDeleted();
                    return true;
                }
            }
        }
        if (largeGapNum >= 2) {
            rc.markDeleted();
            return true;
        }

        largeGapNum = 0;
        if (bottomOuterTextBlock.getSize() >= 2 && bottomOuterTextBlock.getTop() - textBlockList.get(textBlockList.size() - 1).getBottom() < 2 * page.getAvgCharHeight()) {
            List<TextChunk> tc = bottomOuterTextBlock.getElements();
            for (int i = 0; i < tc.size() - 1; i++) {
                if (tc.get(i + 1).getLeft() - tc.get(i).getRight() > 3 * page.getAvgCharWidth()) {
                    largeGapNum++;
                }
                if (tc.get(i + 1).getLeft() - tc.get(i).getRight() > 8 * page.getAvgCharWidth()) {
                    rc.markDeleted();
                    return true;
                }
            }
        }
        if (largeGapNum >= 2) {
            rc.markDeleted();
            return true;
        }

        return false;
    }

    private List<TextChunk> findChunkBetweenVerticalSegPoint(float minX, float maxX, TextBlock tb) {
        List<TextChunk> segTextChunks = new ArrayList<>();
        for (TextChunk tc : tb.getElements()) {
            if (tc.getLeft() > minX && tc.getRight() < maxX) {
                segTextChunks.add(tc);
            }
        }
        return segTextChunks;
    }

    private boolean judgeTextSparse(ContentGroupPage page, List<TextBlock> continuousTextBlocks) {
        if (continuousTextBlocks.size() < 2) {
            return false;
        }

        float minx = java.lang.Float.MAX_VALUE;
        float miny = java.lang.Float.MAX_VALUE;
        float maxx = java.lang.Float.MIN_VALUE;
        float maxy = java.lang.Float.MIN_VALUE;
        for (TextBlock r : continuousTextBlocks) {
            minx = (float) Math.min(r.getLeft(), minx);
            miny = (float) Math.min(r.getTop(), miny);
            maxx = (float) Math.max(r.getRight(), maxx);
            maxy = (float) Math.max(r.getBottom(), maxy);
        }
        Rectangle rectTemp = new Rectangle(minx, miny, maxx - minx, maxy - miny);
        List<Ruling> vRulings = page.getVerticalRulings().stream().filter(
                rul -> rectTemp.isShapeIntersects(new Rectangle(rul.getLeft(), rul.getTop(), rul.getWidth()
                        , rul.getHeight()))).collect(Collectors.toList());
        if (vRulings.isEmpty()) {
            return false;
        }
        List<Float> segX = new ArrayList<>();
        for (Ruling rul : vRulings) {
            segX.add(rul.getLeft());
        }
        segX.add(rectTemp.getLeft() - 1.0f);
        segX.add(rectTemp.getRight() + 1.0f);
        Collections.sort(segX);
        float highDutyRatio = 0.65f;
        float lowDutyRatio = 0.5f;
        int highDutyRatioNum = 0;
        int lowDutyRatioNum = 0;
        int filledCellsNum = 0;
        for (int i = 0; i < segX.size() - 1; i++) {
            List<TextChunk> textChunks = new ArrayList<>();
            for (TextBlock r : continuousTextBlocks) {
                List<TextChunk> segTextChunks = findChunkBetweenVerticalSegPoint(segX.get(i), segX.get(i + 1), r);
                if (!segTextChunks.isEmpty()) {
                    filledCellsNum++;
                }
                if (segTextChunks.size() == 1) {
                    textChunks.addAll(segTextChunks);
                } else {
                    break;
                }
            }
            if (textChunks.size() == continuousTextBlocks.size()) {
                //字符内容判断
                for (int m = 0; m < textChunks.size() - 1; m++) {
                    TextChunk tc1 = textChunks.get(m);
                    String str1 = tc1.getText().trim();
                    for (int n = m + 1; n < textChunks.size(); n++) {
                        TextChunk tc2 = textChunks.get(n);
                        String str2 = tc2.getText().trim();
                        if (StringUtils.isNotBlank(str1) && StringUtils.isNotBlank(str2) && StringUtils.equalsIgnoreCase(str1, str2)
                                && FloatUtils.feq(tc1.getCenterX(), tc2.getCenterX(), page.getAvgCharWidth())
                                && tc1.getWidth() < 0.65 * (segX.get(i + 1) - segX.get(i))) {
                            return true;
                        }
                    }
                }

                //数字字符串判断
                int numberStringNum = 0;
                for (TextChunk tc : textChunks) {
                    String str = tc.getText().trim();
                    if (TableRegionDetectionUtils.isNumberStr(str) && tc.getBounds2D().getWidth() < highDutyRatio * (segX.get(i + 1) - segX.get(i))) {
                        numberStringNum++;
                    }
                }
                if (numberStringNum == continuousTextBlocks.size()) {
                    return true;
                }
                //字符串占空比判断
                if (Rectangle.boundingBoxOf(textChunks).getWidth() < highDutyRatio * (segX.get(i + 1) - segX.get(i))) {
                    highDutyRatioNum++;
                }
                if (Rectangle.boundingBoxOf(textChunks).getWidth() < lowDutyRatio * (segX.get(i + 1) - segX.get(i))) {
                    lowDutyRatioNum++;
                }
            }
        }
        boolean allCenterYMatch = continuousTextBlocks.stream().anyMatch(tb -> TableRegionDetectionUtils.isHorizontalCenterNear(tb.getElements()));
        boolean allCellFilled = (filledCellsNum == (segX.size() - 1) * continuousTextBlocks.size());
        if (allCenterYMatch && ((segX.size() - 1 >= 3 && highDutyRatioNum > 0.65 * (segX.size() - 1))
                || (allCellFilled && segX.size() - 1 >= 2 && lowDutyRatioNum > 0.8 * (segX.size() - 1)))) {
            return true;
        }

        return false;
    }

    private boolean judgeVerticalAlign(ContentGroupPage page, List<List<CandidateCell>> rowAlignedCells, List<List<CandidateCell>> colAlignedCells, Rectangle rc) {
        if (rowAlignedCells.isEmpty() || colAlignedCells.isEmpty()) {
            return false;
        }

        //judge the single cell
        int largeGapNum = 0;
        for (List<CandidateCell> cellListTemp : rowAlignedCells) {
            for (CandidateCell cellTemp : cellListTemp) {
                if (cellTemp.getCollectedTextChunk(page).isEmpty()) {
                    continue;
                }

                //垂直对齐投影
                HashMap<Point2D, java.lang.Float> gapMap = calVerticalAlignGap(page, cellTemp);
                int gapNum = TableRegionDetectionUtils.countLargeGap(gapMap,  2 * cellTemp.getAveTextWidth());
                if (!gapMap.isEmpty() && gapNum >= 2) {
                    largeGapNum++;
                }
                if (largeGapNum >= 2 || gapNum >= 3) {
                    return true;
                }

                //nearly center collect
                int multiGroup = 0;
                List<List<TextChunk>> groups = TableRegionDetectionUtils.nearlyYCenterCollect(page, cellTemp);
                for (List<TextChunk> group : groups) {
                    if (group.size() >= 2 && TableRegionDetectionUtils.isLargeGap(group, 4 * cellTemp.getAveTextWidth(), 1)) {
                        multiGroup++;
                    }
                }
                if (multiGroup >= 2) {
                    return true;
                }

                int largeHorizontalProjectNum = TableRegionDetectionUtils.countLargeGap(TableRegionDetectionUtils.calVerticalProjectionGap(page, cellTemp, true)
                        , 5 * cellTemp.getAveTextWidth());
                int largeVerticalProjectNum = TableRegionDetectionUtils.countLargeGap(TableRegionDetectionUtils.calHorizontalProjectionGap(page, cellTemp, true)
                        , 3 * cellTemp.getAveTextHeight());
                if (largeHorizontalProjectNum >= 1 && largeVerticalProjectNum >= 1) {
                    return true;
                }
                List<List<TextChunk>> collectedTextChunks = cellTemp.getCollectedTextChunk(page);
                //基于单个cell中多行文本判断
                if (largeVerticalProjectNum >= 1 && collectedTextChunks.size() >= 2) {
                    List<TextChunk> sameRowExtraTextChunks = page.getTextChunks(Rectangle.boundingBoxOf(cellListTemp)
                            .rectReduce(1.0, 1.0, page.getWidth(), page.getHeight())).stream()
                            .filter(chunk -> !cellTemp.isShapeIntersects(chunk)).collect(Collectors.toList());
                    if (sameRowExtraTextChunks.size() >= 2) {
                        for (int i = 0; i < collectedTextChunks.size() - 1; i++) {
                            List<TextChunk> upChunks = collectedTextChunks.get(i);
                            List<TextChunk> downChunks = collectedTextChunks.get(i + 1);
                            Rectangle upChunkBound = Rectangle.boundingBoxOf(upChunks);
                            Rectangle downChunkBound = Rectangle.boundingBoxOf(downChunks);
                            if (downChunkBound.getTop() - upChunkBound.getBottom() > 2 * page.getAvgCharHeight()
                                    && TableRegionDetectionUtils.isAllNumberStr(upChunks)
                                    && TableRegionDetectionUtils.isAllNumberStr(downChunks)) {
                                List<TextChunk> betweenTextChunks = new ArrayList<>();
                                for (TextChunk tc : sameRowExtraTextChunks) {
                                    if (tc.getVisibleMinY() > upChunkBound.getBottom() && tc.getVisibleMaxY() < downChunkBound.getTop()) {
                                        betweenTextChunks.add(tc);
                                    }
                                }
                                if (betweenTextChunks.size() >= 2 && TableRegionDetectionUtils.splitChunkByRow(betweenTextChunks).size() >= 3) {
                                    return true;
                                }
                            }
                        }
                    }
                }

                int littleGapRowNum = 0;
                int largeGapRowNum = 0;
                int hugeGapRowNum = 0;
                int smallDutyCycleNum = 0;
                int multiNumberRowNum = 0;
                for (List<TextChunk> rowChunks : collectedTextChunks) {
                    if (TableRegionDetectionUtils.isLargeGap(rowChunks, 3 * cellTemp.getAveTextWidth(), 1)
                            && TableRegionDetectionUtils.isHorizontalCenterNear(rowChunks)) {
                        for (int i = 0; i < rowChunks.size() - 1; i++) {
                            if (TableRegionDetectionUtils.isNumberStr(rowChunks.get(i).getText().trim())
                                    || TableRegionDetectionUtils.isNumberStr(rowChunks.get(i + 1).getText().trim())) {
                                return true;
                            }
                        }
                    }
                    if (TableRegionDetectionUtils.isLargeGap(rowChunks, 2 * cellTemp.getAveTextWidth(), 2)) {
                        littleGapRowNum++;
                    }
                    if (TableRegionDetectionUtils.isLargeGap(rowChunks, 3 * cellTemp.getAveTextWidth(), 2)) {
                        largeGapRowNum++;
                    }
                    if (TableRegionDetectionUtils.isLargeGap(rowChunks, 5 * cellTemp.getAveTextWidth(), 1)
                            && TableRegionDetectionUtils.isHorizontalCenterNear(rowChunks)) {
                        hugeGapRowNum++;
                    }

                    boolean isNumber = TableRegionDetectionUtils.isNumberStr(TextChunk.getText(rowChunks));
                    if (isNumber && TableRegionDetectionUtils.isLargeGap(rowChunks, 2 * cellTemp.getAveTextWidth(), 1)) {
                        multiNumberRowNum++;
                    }
                    //占空比较小且空白宽度不能太小
                    if (isNumber && TableRegionDetectionUtils.calDutyRatio(rowChunks, (float)cellTemp.getWidth()) < 0.55
                            && cellTemp.getWidth() - Rectangle.boundingVisibleBoxOf(rowChunks).getWidth() > 4 * cellTemp.getAveTextWidth()) {
                        smallDutyCycleNum++;
                    }
                }
                if (smallDutyCycleNum >= 3 || multiNumberRowNum >= 2 || (collectedTextChunks.size() >= 2 && gapMap.size() >= 1
                        && (littleGapRowNum >= 2 || largeGapRowNum >= 1 || hugeGapRowNum >= 1))) {
                    return true;
                }
            }
        }

        //根据英文数字规则的判定方法
        char[] chars1 = {',','-'};
        char[] chars2 = {'(',')','（','）'};
        for (List<CandidateCell> cellListTemp : rowAlignedCells) {
            if (cellListTemp.size() < 2) {
                continue;
            }

            Rectangle rowRect = Rectangle.boundingBoxOf(cellListTemp);
            Rectangle reduceRect = rowRect.rectReduce(1.0, 1.0 , page.getWidth(), page.getHeight());
            List<TextChunk> innerChunks = page.getMutableTextChunks(reduceRect);
            boolean isHorizontalAligned = isHorizontalHardAlign(innerChunks);
            boolean hasMultiRow = TextMerger.collectByRows(TextMerger.groupByBlock(innerChunks, page.getHorizontalRulings(), page.getVerticalRulings())).size() >= 2;
            boolean isSpanWidth = (rowRect.getWidth() > 0.9 * rc.getWidth());
            boolean hasVerticalLine = TableRegionDetectionUtils.getLongRulings(Ruling.getRulingsFromArea(page.getVerticalRulings(), reduceRect)
                    , (float) (0.5 * reduceRect.getHeight())).size() > 0;
            if ((!isHorizontalAligned && !hasVerticalLine) || !hasMultiRow) {
                continue;
            }

            boolean allLittleDuty = true;
            for (CandidateCell cellTemp : cellListTemp) {
                List<List<TextChunk>> collectedTextChunks = cellTemp.getCollectedTextChunk(page);
                if (allLittleDuty && (!isSpanWidth || collectedTextChunks.size() < 2 || Rectangle.boundingBoxOf(page.getTextChunks(cellTemp)).getWidth() > 0.6 * cellTemp.getWidth())) {
                    allLittleDuty = false;
                }

                //cell中存在上下两行均为数字串且占空比小，则根据英文数字逗号分割规则确定
                if (collectedTextChunks.size() >= 2) {
                    int numberNum = 0;
                    for (int i = 0; i < collectedTextChunks.size() - 1; i++) {
                        List<TextChunk> one = collectedTextChunks.get(i);
                        List<TextChunk> other = collectedTextChunks.get(i + 1);
                        boolean oneIsNumber = TableRegionDetectionUtils.isNumberStr(TextChunk.getText(one));
                        boolean otherIsNumber = TableRegionDetectionUtils.isNumberStr(TextChunk.getText(other));
                        if (oneIsNumber) {
                            numberNum++;
                        }
                        if (i == collectedTextChunks.size() - 2 && otherIsNumber) {
                            numberNum++;
                        }
                        if (one.size() == 1 && other.size() == 1 && oneIsNumber && otherIsNumber
                                && TableRegionDetectionUtils.calDutyRatio(one, (float)cellTemp.getWidth()) < 0.65
                                && TableRegionDetectionUtils.calDutyRatio(other, (float)cellTemp.getWidth()) < 0.65) {
                            String oneStr = one.get(0).getText().trim();
                            String otherStr = other.get(0).getText().trim();
                            if (StringUtils.isBlank(oneStr) || StringUtils.isBlank(otherStr)) {
                                continue;
                            } else if (TableRegionDetectionUtils.equalsAnyChar(oneStr.charAt(oneStr.length() - 1), chars1)) {
                                continue;
                            }
                            String[] arrayOne = oneStr.split(",");
                            String[] arrayOther = otherStr.split(",");
                            if (arrayOne.length >=2 && arrayOther.length >= 2 && !TableRegionDetectionUtils.equalsAnyChar(oneStr.charAt(oneStr.length() - 1), chars2)
                                    && !TableRegionDetectionUtils.equalsAnyChar(otherStr.charAt(0), chars2)
                                    && arrayOne[arrayOne.length - 1].length() + arrayOther[0].length() > 3) {
                                return true;
                            }
                        }
                    }
                    if (numberNum >= 5) {
                        return true;
                    }
                }
            }
            if (allLittleDuty) {
                return true;
            }
        }

        //judge the adjcent cell
        for (List<CandidateCell> cellListTemp : colAlignedCells) {
            if (cellListTemp.size() > 1) {
                CandidateCell cellNew = (CandidateCell)(cellListTemp.get(0).clone());
                for (int i = 1; i < cellListTemp.size(); i++) {
                    cellNew = cellNew.cellMerge(cellListTemp.get(i));
                }
                HashMap<Point2D, java.lang.Float> gapMap = calVerticalAlignGap(page, cellNew);
                float textWidth = cellNew.getAveTextWidth();
                long gapNum = gapMap.entrySet().stream().filter(s -> s.getValue() > 2 * textWidth).count();
                if (!gapMap.isEmpty() && gapNum >= 3) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean judgeHorizontalAlign(ContentGroupPage page, List<List<CandidateCell>> rowAlignedCells, List<TextBlock> textBlockList, List<TextChunk> textChunks, Rectangle rc) {
        if (rowAlignedCells.isEmpty()) {
            return false;
        }

        for (List<CandidateCell> rowCandidateCells : rowAlignedCells) {
            List<TextChunk> allOneRowTextChunks = new ArrayList<>();
            for (CandidateCell cell : rowCandidateCells) {
                for (List<TextChunk> tc : cell.getCollectedTextChunk(page)) {
                    allOneRowTextChunks.addAll(tc);
                }
            }
            allOneRowTextChunks.sort(Comparator.comparing(TextChunk::getGroupIndex));
            List<TextBlock> allOneRowTextBlocks = TextMerger.collectByRows(TextMerger.groupByBlock(allOneRowTextChunks, page.getHorizontalRulings(), page.getVerticalRulings()));

            //基于文本块属性和占空比的判定规则
            if (rowCandidateCells.size() >= 3) {
                boolean mutiRowFlag = true;
                for (CandidateCell cell : rowCandidateCells) {
                    if (cell.getCollectedTextChunk(page).size() < 3) {
                        mutiRowFlag = false;
                        break;
                    }
                }
                if (mutiRowFlag) {
                    //连续多行满足水平对齐关系且大部分为数字串
                    int minNum = 2;
                    int continuousNum = 0;
                    List<TextBlock> continuousTextBlocks = new ArrayList<>();
                    for (TextBlock tb : allOneRowTextBlocks) {
                        if (FastMath.abs(rowCandidateCells.size() - tb.getSize()) <= 1
                                && TableRegionDetectionUtils.isHorizontalCenterNear(tb.getElements())) {
                            continuousNum++;
                            continuousTextBlocks.add(tb);
                        } else {
                            continuousNum = 0;
                            continuousTextBlocks.clear();
                        }
                        if (continuousNum >= minNum) {
                            break;
                        }
                    }

                    if (continuousTextBlocks.size() >= minNum) {
                        int numberStringNum = 0;
                        int allStringNum = 0;
                        for (TextBlock tb : continuousTextBlocks) {
                            for (TextChunk tc : tb.getElements()) {
                                if (TableRegionDetectionUtils.isNumberStr(tc.getText().trim())) {
                                    numberStringNum++;
                                }
                                allStringNum++;
                            }
                        }
                        if ((float)numberStringNum / (float)allStringNum > 0.7) {
                            return true;
                        }

                        //judge sparse
                        if (judgeTextSparse(page, continuousTextBlocks)) {
                            return true;
                        }
                    }
                }
            } else if (rowCandidateCells.size() == 2) {
                int minNum = 2;
                int continuousNum = 0;
                List<TextBlock> continuousTextBlocks = new ArrayList<>();
                for (TextBlock tb : allOneRowTextBlocks) {
                    if (rowCandidateCells.size() == tb.getSize() && TableRegionDetectionUtils.isHorizontalCenterNear(tb.getElements())) {
                        continuousNum++;
                        continuousTextBlocks.add(tb);
                    } else {
                        continuousNum = 0;
                        continuousTextBlocks.clear();
                    }
                    if (continuousNum >= minNum) {
                        break;
                    }
                }

                if (continuousTextBlocks.size() >= minNum) {
                    int numberStringNum = 0;
                    int allStringNum = 0;
                    for (TextBlock tb : continuousTextBlocks) {
                        for (TextChunk tc : tb.getElements()) {
                            if (TableRegionDetectionUtils.isNumberStr(tc.getText().trim())) {
                                numberStringNum++;
                            }
                            allStringNum++;
                        }
                    }
                    if ((float)numberStringNum / (float)allStringNum > 0.7) {
                        return true;
                    }

                    //judge sparse
                    if (judgeTextSparse(page, continuousTextBlocks)) {
                        return true;
                    }
                }
            } else if (rowCandidateCells.size() == 1 && rowCandidateCells.get(0).getCollectedTextChunk(page).size() >= 3) {
                List<List<TextChunk>> collectedTextChunks = rowCandidateCells.get(0).getCollectedTextChunk(page);
                CandidateCell cellTemp = rowCandidateCells.get(0);
                int littleDutyNum = 0;
                int validNum = 0;
                for (List<TextChunk> chunks : collectedTextChunks) {
                    if (TableRegionDetectionUtils.isBlankChunks(chunks)) {
                        continue;
                    }
                    validNum++;
                    if (TableRegionDetectionUtils.calDutyRatio(chunks, (float)rowCandidateCells.get(0).getWidth()) < 0.5) {
                        littleDutyNum++;
                    }
                }
                if (validNum > 8 && littleDutyNum > validNum * 0.7f) {
                    return true;
                }
                for (int i = 0; i < collectedTextChunks.size() - 1; i++) {
                    List<TextChunk> chunks1 = collectedTextChunks.get(i);
                    List<TextChunk> chunks2 = collectedTextChunks.get(i + 1);
                    Rectangle chunkRect1 = Rectangle.boundingBoxOf(chunks1);
                    Rectangle chunkRect2 = Rectangle.boundingBoxOf(chunks2);
                    if (!TableRegionDetectionUtils.isBlankChunks(chunks1) && !TableRegionDetectionUtils.isBlankChunks(chunks2)
                            && TableRegionDetectionUtils.calDutyRatio(chunks1, (float)cellTemp.getWidth()) < 0.5
                            && TableRegionDetectionUtils.calDutyRatio(chunks2, (float)cellTemp.getWidth()) < 0.5) {
                        List<TextChunk> sameRowChunks1 = textChunks.stream().filter(tc -> TableRegionDetectionUtils.isHorizontalCenterNear(chunkRect1, tc)
                                && (tc.getLeft() > chunkRect1.getRight() || tc.getRight() < chunkRect1.getLeft())).collect(Collectors.toList());
                        List<TextChunk> sameRowChunks2 = textChunks.stream().filter(tc -> TableRegionDetectionUtils.isHorizontalCenterNear(chunkRect2, tc)
                                && (tc.getLeft() > chunkRect2.getRight() || tc.getRight() < chunkRect2.getLeft())).collect(Collectors.toList());
                        if (sameRowChunks1.size() >= 2 && TableRegionDetectionUtils.countNumberStr(sameRowChunks1) >= 2
                                && sameRowChunks2.size() >= 2 && TableRegionDetectionUtils.countNumberStr(sameRowChunks2) >= 2) {
                            return true;
                        }
                    }

                }
            }
        }

        return false;


        /*if (textBlockList.isEmpty() || textBlockList.size() < 2) {
            return false;
        }

        Rectangle rectTmp = rc.withCenterExpand(2.0f,2.0f);
        List<Ruling> hRulings = page.getHorizontalRulings().stream().filter(
                rul -> rectTmp.isShapeIntersects(new Rectangle(rul.getLeft(), rul.getTop(), rul.getWidth()
                        , rul.getHeight()))).collect(Collectors.toList());

        //judge partial row
        for (int i = 0; i < textBlockList.size() - 1; i++) {
            if ((isOverlapMultiRow(textBlockList.get(i)) || isOverlapMultiRow(textBlockList.get(i + 1))) &&
                    findHorizontalRulingBetweenTextBlocks(textBlockList.get(i), textBlockList.get(i + 1), hRulings).isEmpty()) {
                return true;
            }
        }

        List<Ruling> vRulings = page.getVerticalRulings().stream().filter(
                rul -> rectTmp.isShapeIntersects(new Rectangle(rul.getLeft(), rul.getTop(), rul.getWidth()
                        , rul.getHeight()))).collect(Collectors.toList());
        List<Ruling> longRulings = getLongHorizontalRulings(hRulings, (float)(0.5 * rc.getWidth()));
        List<Ruling> innerRulings = new ArrayList<>();
        for (Ruling rul : longRulings) {
            if (rul.getTop() > rc.getTop() + 2.0f && rul.getTop() < rc.getBottom() - 2.0f) {
                innerRulings.add(rul);
            }
        }

        HashMap<Point2D, java.lang.Float> gapMap = new NoRulingTableRegionsDetectionAlgorithm().calVerticalHardAlignGap(page, textBlockList);
        boolean isLargeGap = true;
        for (Float gap : gapMap.values()) {
            if (gap < page.getAvgCharWidth()) {
                isLargeGap = false;
                break;
            }
        }

        if (isLargeGap) {
            if (innerRulings.size() == textBlockList.size() - 1) {
                return false;
            } else if (textBlockList.size() - innerRulings.size() >= 3){
                return true;
            }
        }

        //find textBlock between two horizontal line
        longRulings.sort(Comparator.comparing(Ruling::getTop));
        List<Float> segPoint = new ArrayList<>();
        segPoint.add(rc.getTop());
        for (Ruling rul : longRulings) {
            if (rul.getTop() > textBlockList.get(0).getBottom() && rul.getTop() < textBlockList.get(textBlockList.size() - 1).getTop()) {
                segPoint.add(rul.getTop());
            }
        }
        segPoint.add(rc.getBottom());

        List<List<TextBlock>> tbTempListList = new ArrayList<>();
        for (int i = 0; i < segPoint.size() - 1; i++) {
            List<TextBlock> tbTempList = new ArrayList<>();
            for (int j = 0; j < textBlockList.size(); j++) {
                if (textBlockList.get(j).getTop() > segPoint.get(i) && textBlockList.get(j).getBottom() < segPoint.get(i + 1)) {
                    tbTempList.add(textBlockList.get(j));
                }
            }
            tbTempListList.add(tbTempList);
        }

        for (List<TextBlock> tbTempList : tbTempListList) {
            if (tbTempList.size() < 2) {
               continue;
            }

            gapMap = new NoRulingTableRegionsDetectionAlgorithm().calVerticalHardAlignGap(page, tbTempList);
            isLargeGap = true;
            for (Float gap : gapMap.values()) {
                if (gap < page.getAvgCharWidth()) {
                    isLargeGap = false;
                    break;
                }
            }

            if (isLargeGap) {
                return true;
            }
        }
        return false;*/
    }

    /**
     * if table is not all comprised of line seg, return true
     */
    private boolean judgeTextAlign(ContentGroupPage page, List<List<CandidateCell>> rowAlignedCells, List<List<CandidateCell>> colAlignedCells, List<TextBlock> textBlockList, List<TextChunk> textChunks, Rectangle rc) {
        if (judgeVerticalAlign(page, rowAlignedCells, colAlignedCells, rc) || judgeHorizontalAlign(page, rowAlignedCells, textBlockList, textChunks, rc)) {
            return true;
        }

        return false;
    }

    public boolean isLineTable(ContentGroupPage page, Rectangle rc) {
        List<TextElement> textElements = page.getText(rc);
        if (textElements.isEmpty()) {
            return false;
        }

        //judge the border line
        Rectangle rectTmp = rc.withCenterExpand(2.0f,2.0f);
        List<Ruling> hRulings = page.getHorizontalRulings().stream().filter(
                rul -> rectTmp.isShapeIntersects(rul.toRectangle())).collect(Collectors.toList());
        List<Ruling> vRulings = page.getVerticalRulings().stream().filter(
                rul -> rectTmp.isShapeIntersects(rul.toRectangle())).collect(Collectors.toList());
        if (hRulings.size() < 1 || vRulings.size() < 1) {
            return false;
        }
        addBorderRuling(rc, hRulings, vRulings);
        if (hRulings.size() < 2 || vRulings.size() < 2) {
            return false;
        }

        //judge the innner line
        List<Ruling> allRulings = page.getRulings();
        Rectangle rectTemp = rc.rectReduce(3.0, 3.0, page.width, page.height);
        List<Ruling> innerRulings = allRulings.stream().filter(rulingTemp -> rectTemp.isShapeIntersects(rulingTemp.toRectangle()))
                .collect(Collectors.toList());
        //根据水平和垂直直线划分表格区域
        double minHeight = textElements.parallelStream()
                .mapToDouble(TextElement::getTextHeight).min().orElse(Page.DEFAULT_MIN_CHAR_HEIGHT);
        double minWidth = 1.5 * textElements.parallelStream()
                .mapToDouble(TextElement::getTextWidth).min().orElse(Page.DEFAULT_MIN_CHAR_WIDTH);
        //边界表格线修正：部分表格线由于误差对findCells造成影响
        hRulings.removeIf(rul -> rul.length() < 5);
        vRulings.removeIf(rul -> rul.length() < 5);
        hRulings.sort(Comparator.comparing(Ruling::getTop));
        vRulings.sort(Comparator.comparing(Ruling::getLeft));
        CellFillAlgorithm.correctTableBroadLines(minWidth, minHeight, hRulings, vRulings);
        List<Cell> cellsByLine = Ruling.findCells(hRulings, vRulings);
        //有些表格划分cell可能存在问题，如“方正证券-170412.pdf”，划分错误直接作为无线表格进行解析
        List<TextChunk> allTextChunks = page.getTextChunks(rc);
        int wrongDivideNum = 0;
        for (TextChunk chunk : allTextChunks) {
            boolean chunkFind = false;
            if (chunk.isHiddenOrBlank()) {
                continue;
            }
            for (Cell cell : cellsByLine) {
                if (cell.intersects(chunk)) {
                    chunkFind = true;
                    break;
                }
            }
            if (!chunkFind) {
                wrongDivideNum++;
            }
        }
        if (wrongDivideNum > 0.1 * allTextChunks.size()) {
            return false;
        }

        //如果划分的cell中存在包含关系的比率大于一定值，估计直线提取存在问题，此时分为无线表格进行处理
        for (int i = 0; i < cellsByLine.size(); i++) {
            Cell one = cellsByLine.get(i);
            int num = 0;
            if (one.nearlyContains(rc, 2.0f) && cellsByLine.size() > 5) {
                return false;
            }
            for (int j = 0; j < cellsByLine.size(); j++) {
                if (i == j) {
                    continue;
                }
                Cell other = cellsByLine.get(j);
                if (one.overlapRatio(other) > 0.5) {
                    num++;
                }
            }
            if (cellsByLine.size() > 5 && num > 0.6 * cellsByLine.size()) {
                return false;
            }
        }

        //过滤无线cell
        List<CandidateCell> cellCandidateByLine = cellsByLine.stream()
                .filter(cell -> (cell.getWidth() > 5.0 && cell.getHeight() > 5.0))
                .map(cell -> new CandidateCell(cell.x, cell.y, cell.width, cell.height))
                .collect(Collectors.toList());

        HashMap<String, Integer> rcMap = calRowColNum(cellCandidateByLine);
        if ((rcMap.isEmpty()) || (cellCandidateByLine.size() <= 1) || (rcMap.get("rowNum") >= 2 && rcMap.get("colNum") >= 2
                && !hasIntersectionLines(innerRulings))) {
            return false;
        }

        //judge the aligned text
        for (CandidateCell cell : cellCandidateByLine) {
            cell.collectTextChunk(page);
        }
        List<List<CandidateCell>> rowAlignedCells = collectHorizontalAlignCells(cellCandidateByLine);
        List<List<CandidateCell>> colAlignedCells = collectVerticalAlignCells(cellCandidateByLine);
        List<TextChunk> textChunks = page.getTextChunks(rc.rectReduce(1.0, 1.0, page.getWidth(), page.getHeight()));
        List<TextBlock> tbTmep = TextMerger.collectByRows(TextMerger.groupByBlock(textChunks, hRulings, vRulings));//multi row merge to single cell
        if (judgeTextAlign(page, rowAlignedCells, colAlignedCells, tbTmep, textChunks, rc) /*|| judgeTextBlock(page, tbTmep, vRulings, rc)*/) {
            return false;
        }

        //judge the magic line
        int numAllValidChunk = 0;
        int numAllSingleChunk = 0;
        int numMultiRowChunk = 0;
        int numContinusMultiRowChunk = 0;
        for(CandidateCell cell : cellCandidateByLine) {
            List<TextChunk> thunkTemp = page.getTextChunks(cell.rectReduce(1.0, 1.0, page.getWidth(), page.getHeight()));
            int numValidChunk = 0;
            for (TextChunk text : thunkTemp) {
                if (StringUtils.isNotBlank(text.getText().trim())) {
                    numValidChunk ++;
                }
            }

            if (numValidChunk == 1) {
                numAllSingleChunk++;
            }
            if (numValidChunk >= 1) {
                numAllValidChunk++;
            }

            if (numValidChunk >=2) {
                List<TextChunk> newTextChunks = TableRegionDetectionUtils.splitChunkByRow(thunkTemp);
                if (newTextChunks.size() >= 2) {
                    numMultiRowChunk++;
                    for (TextChunk tc : newTextChunks) {
                        if (TableRegionDetectionUtils.calDutyRatio(Lists.newArrayList(tc), (float)cell.getWidth()) > 0.7) {
                            numContinusMultiRowChunk++;
                            break;
                        }
                    }
                }
            }
        }

        int rowsDefinedByLines = rcMap.get("rowNum");
        int colsDefinedByLines = rcMap.get("colNum");
        int rowsDefinedWithoutLines = tbTmep.size();
        int colsDefinedWithoutLines = 0;
        for (TextBlock tb : tbTmep) {
            int colNum = 1;
            List<TextChunk> tc = new ArrayList<>(tb.getElements());
            tc.sort(Comparator.comparing(TextChunk::getLeft));
            if (tc.size() > 2) {
                for (int i = 0; i < tc.size() - 1; i++) {
                    if (tc.get(i + 1).getLeft() - tc.get(i).getRight() > 2 * page.getAvgCharWidth()) {
                        colNum++;
                    } else {
                        boolean hasRulingBetween = false;
                        for (Ruling rul : vRulings) {
                            if (rul.getLeft() > tc.get(i).getRight() && rul.getLeft() < tc.get(i + 1).getLeft()) {
                                hasRulingBetween = true;
                                break;
                            }
                        }
                        if (hasRulingBetween) {
                            colNum++;
                        }
                    }
                }
                if (colNum > colsDefinedWithoutLines) {
                    colsDefinedWithoutLines = colNum;
                }
            } else {
                if (tc.size() > colsDefinedWithoutLines) {
                    colsDefinedWithoutLines = tc.size();
                }
            }
        }

        float magicRatioValue = 0.5f;
        float validRatioValue = 0.1f;
        float validRatio = (float)(numAllSingleChunk + 1)/ (float)(numAllValidChunk + 1);
        float magicRatio = (((float) colsDefinedByLines / colsDefinedWithoutLines) + ((float) rowsDefinedByLines / rowsDefinedWithoutLines)) / 2.0f;

        if (magicRatio > 0.7) {
            return true;
        } else  if ((validRatio > validRatioValue) && (magicRatio > magicRatioValue)) {
            return true;
        } else if ((validRatio <= validRatioValue)) {
            if (colsDefinedByLines >= 5 && colsDefinedByLines > colsDefinedWithoutLines * 0.6f) {
                return true;
            } else if (numContinusMultiRowChunk > numMultiRowChunk * 0.8f) {
                return true;
            }
            return false;
        } else {
            return false;
        }
    }

    private List<TableRegion> mergeSubTable(ContentGroupPage page, List<TableRegion> rulingTableRegions) {
        if (rulingTableRegions.isEmpty() || rulingTableRegions.size() < 2) {
            return rulingTableRegions;
        }
        List<TableRegion> mergedTables = new ArrayList<>();
        rulingTableRegions.sort(Comparator.comparing(TableRegion::getTop));
        Pattern SERIAL_NUMBER_ROW_RE = Pattern.compile("^[一二三四五六七八九十]{1,2}[.、].+");
        for (TableRegion rc : rulingTableRegions) {
            if (mergedTables.isEmpty()) {
                mergedTables.add(new TableRegion(rc));
                continue;
            }
            TableRegion lastTable = mergedTables.get(mergedTables.size() - 1);
            if (lastTable.getBottom() > rc.getTop()) {
                mergedTables.add(new TableRegion(rc));
                continue;
            }
            Rectangle rectGap = new Rectangle(lastTable.getLeft(), lastTable.getBottom(), lastTable.getWidth(), rc.getTop() - lastTable.getBottom())
                    .rectReduce(0, 1.0, page.getWidth(), page.getHeight());
            List<TextBlock> textBlocks = page.getTextLines().stream().filter(tb -> rectGap.isShapeIntersects(new Rectangle(tb.getBounds2D())))
                    .collect(Collectors.toList());
            if (textBlocks.size() != 1 || textBlocks.get(0).getSize() != 1
                    || NoRulingTableRegionsDetectionAlgorithm.TABLE_BEGINNING_KEYWORD.matcher(textBlocks.get(0).getText().trim().toLowerCase()).matches()) {
                mergedTables.add(new TableRegion(rc));
                continue;
            }
            TextBlock currentTextBlock = textBlocks.get(0);
            Rectangle LastTableTextBound = Rectangle.union(page.getTextChunks(lastTable));
            if (LastTableTextBound == null) {
                mergedTables.add(new TableRegion(rc));
                continue;
            }
            boolean tableAlignFlag = FloatUtils.feq(rc.getLeft(), lastTable.getLeft(), 2.0) && FloatUtils.feq(rc.getRight(), lastTable.getRight(), 2.0);
            boolean textAlignFlag = FloatUtils.feq(LastTableTextBound.getLeft(), currentTextBlock.getLeft(), currentTextBlock.getAvgCharWidth());
            boolean serialNumberFlag = SERIAL_NUMBER_ROW_RE.matcher(currentTextBlock.getText().trim()).matches();
            List<Ruling> topRulings = NoRulingTableRegionsDetectionAlgorithm.findLongRulingBetween(page.getHorizontalRulings()
                    , (float)(lastTable.getBottom() - 0.5 * page.getAvgCharHeight()), (float)currentTextBlock.getCenterY(), (float)(0.9 * lastTable.getWidth()));
            List<Ruling> bottomRulings = NoRulingTableRegionsDetectionAlgorithm.findLongRulingBetween(page.getHorizontalRulings()
                    , (float)currentTextBlock.getCenterY(), (float)(currentTextBlock.getBottom() + 0.7 * page.getAvgCharHeight())
                    , (float)(0.9 * lastTable.getWidth()));
            boolean hasRulingTopBottom = (topRulings.size() == 1) && (bottomRulings.size() == 1);
            double tableAvgTextHeight = page.getTextLines().stream().filter(tb -> LastTableTextBound.isShapeIntersects(new Rectangle(tb.getBounds2D())))
                    .mapToDouble(TextBlock::getAvgCharHeight).average().orElse(currentTextBlock.getAvgCharHeight());
            double heightRatio1 = currentTextBlock.getAvgCharHeight() / tableAvgTextHeight;
            double heightRatio2 = 0;
            if (hasRulingTopBottom) {
                double minRulingGap = Float.MAX_VALUE;
                List<Ruling> tableRulings = new ArrayList<>();
                Rectangle tableExpandRect = lastTable.withCenterExpand(0, 1.0);
                for (Ruling ruling : page.getHorizontalRulings()) {
                    if (tableExpandRect.isShapeIntersects(new Rectangle(ruling.getLeft(), ruling.getTop(), ruling.getWidth(), ruling.getHeight()))
                            && ruling.getWidth() > 0.9 * lastTable.getWidth()) {
                        tableRulings.add(ruling);
                    }
                }
                if (tableRulings.size() >= 2) {
                    for (int i = 0; i < tableRulings.size() - 1; i++) {
                        if (tableRulings.get(i + 1).getTop() - tableRulings.get(i).getTop() < minRulingGap) {
                            minRulingGap = tableRulings.get(i + 1).getTop() - tableRulings.get(i).getTop();
                        }
                    }
                    heightRatio2 = (bottomRulings.get(0).getTop() - topRulings.get(0).getTop()) / minRulingGap;
                }
            }
            boolean littleTextGap = heightRatio1 > 0.9 && heightRatio1 < 1.0 / 0.9 && heightRatio2 > 0.5 && heightRatio2 < 1.5;
            if (tableAlignFlag && textAlignFlag && serialNumberFlag && hasRulingTopBottom && littleTextGap) {
                lastTable.setRect(lastTable.createUnion(rc));
            } else {
                mergedTables.add(new TableRegion(rc));
            }
        }
        return mergedTables;
    }

    private boolean isWellTableRegion(ContentGroupPage page, TableRegion rulingTableRegion, List<Ruling> innerRulings, float gapThres) {
        if (innerRulings.isEmpty()) {
            return true;
        }

        for (Ruling rul : innerRulings) {
            if (rul.vertical() && (FloatUtils.feq(rul.getTop(), rulingTableRegion.getTop(), gapThres)
                    || FloatUtils.feq(rul.getBottom(), rulingTableRegion.getBottom(), gapThres))) {
                return false;
            } else if (rul.horizontal() && (FloatUtils.feq(rul.getLeft(), rulingTableRegion.getLeft(), gapThres)
                    || FloatUtils.feq(rul.getRight(), rulingTableRegion.getRight(), gapThres))) {
                return false;
            }
        }

        return true;
    }

    private void correctWellTypeTableRegion(ContentGroupPage page, List<TableRegion> rulingTableRegions) {
        for (TableRegion rc : rulingTableRegions) {
            Rectangle rectTemp = rc.rectReduce(2.0, 2.0, page.width, page.height);
            List<Ruling> innerRulings = page.getRulings().stream().filter(rul -> rectTemp.isShapeIntersects(new Rectangle(rul.getLeft()
                    , rul.getTop(), rul.getWidth(), rul.getHeight()))).collect(Collectors.toList());

            if (isWellTableRegion(page, rc, innerRulings, 5.0f)) {
                float minX = Float.MAX_VALUE;
                float minY = Float.MAX_VALUE;
                float maxX = 0;
                float maxY = 0;
                for (Ruling rul : innerRulings) {
                    minX = FastMath.min(minX, rul.getLeft());
                    minY = FastMath.min(minY, rul.getTop());
                    maxX = FastMath.max(maxX, rul.getRight());
                    maxY = FastMath.max(maxY, rul.getBottom());
                }
                rc.setRect(minX, minY, maxX - minX, maxY- minY);
            }
        }
    }
    private void correctTableRegion(ContentGroupPage page, List<TableRegion> tableRegions) {
        if (null == tableRegions || tableRegions.isEmpty()) {
            return;
        }

        for (TableRegion table : tableRegions) {
            if (table.getTableType() != TableType.LineTable) {
                continue;
            }
            // TODO: 只对上下边框进行矫正
            List<Ruling> allHencRulings = page.getHorizontalRulings().stream().
                    filter(r -> (r.getY1() >= table.getTop() || FloatUtils.feq(r.getY1(), table.getTop(), 2.0)) &&
                            (r.getY1() <= table.getBottom() || FloatUtils.feq(r.getY1(), table.getBottom(), 2.0)))
                    .collect(Collectors.toList());
            if (allHencRulings != null && allHencRulings.size() >= 3) {
                allHencRulings.sort(Comparator.comparing(Ruling::getTop));
                double minHeight = page.getText(table).parallelStream()
                        .mapToDouble(TextElement::getTextHeight).min().orElse(Page.DEFAULT_MIN_CHAR_HEIGHT);
                Ruling otherLine;
                Ruling topLine = allHencRulings.get(0);
                if (FloatUtils.feq(topLine.getY1(), table.getTop(), 1.5)) {
                    otherLine = allHencRulings.get(1);
                    if ((otherLine.getY1() - topLine.getY1() < minHeight) &&
                            FloatUtils.feq(topLine.getWidth(), otherLine.getWidth(), 5)) {
                        Rectangle topRect = new Rectangle(table.getLeft(), table.getTop(), (float) table.getWidth(),
                                (float) (otherLine.getY1() - topLine.getY1()));
                        if (page.getTextObjects(topRect).isEmpty()) {
                            table.setRect(table.getLeft(), otherLine.getY1(),
                                    (float) table.getWidth(), (table.getBottom() - otherLine.getY1()));
                        }
                    }
                }
                Ruling bottomLine = allHencRulings.get(allHencRulings.size() - 1);
                if (FloatUtils.feq(bottomLine.getY1(), table.getBottom(), 1.5)) {
                    otherLine = allHencRulings.get(allHencRulings.size() - 2);
                    if ((bottomLine.getY1() - otherLine.getY1() < minHeight) &&
                            FloatUtils.feq(bottomLine.getWidth(), otherLine.getWidth(), 5)) {
                        Rectangle bottomRect = new Rectangle(table.getLeft(), otherLine.getY1(), (float)table.getWidth(),
                                (float)(bottomLine.getY1() - otherLine.getY1()));
                        if (page.getTextObjects(bottomRect).isEmpty()) {
                            table.setRect(table.getLeft(), table.getTop(),
                                    (float)table.getWidth(), (otherLine.getY1() - table.getTop()));
                        }
                    }
                }
            }
        }

        //修正表格和chart复合图表，如表格左边/右边为chart，chart在表格的一个单元格中
        List<TableRegion> chartRegions = page.getChartRegions("RulingTableRegionsDetectionAlgorithm");
        if (!chartRegions.isEmpty() && !tableRegions.isEmpty()) {
            for (TableRegion table : tableRegions) {
                List<TableRegion> chartsInRegion = chartRegions.stream().filter(chart -> table.nearlyContains(chart))
                        .collect(Collectors.toList());
                boolean hasTopRuling = page.getHorizontalRulings().stream().filter(rul -> table.isShapeIntersects(rul.toRectangle())
                        && FloatUtils.feq(rul.getTop(), table.getTop(), 1.5 * page.getAvgCharHeight())
                        && rul.length() > 0.9 * table.getWidth()).collect(Collectors.toList()).size() > 0;
                boolean hasBottomRuling = page.getHorizontalRulings().stream().filter(rul -> table.isShapeIntersects(rul.toRectangle())
                        && FloatUtils.feq(rul.getTop(), table.getBottom(), 1.5 * page.getAvgCharHeight())
                        && rul.length() > 0.9 * table.getWidth()).collect(Collectors.toList()).size() > 0;
                if (!hasTopRuling || !hasBottomRuling) {
                    continue;
                }
                if (chartsInRegion.size() != 1) {
                    continue;
                } else {
                    TableRegion chart = chartsInRegion.get(0);
                    if (chart.getChartType() == ChartType.COLUMN_CHART || chart.getChartType() == ChartType.BAR_CHART) {
                        continue;
                    }
                }

                TableRegion chart = chartsInRegion.get(0);

                Rectangle shrinkRect = null;
                if (Math.abs(chart.getLeft() - table.getLeft()) < Math.abs(chart.getRight() - table.getRight())) {
                    boolean hasSideChunk = page.getTextChunks(new Rectangle(table.getLeft(), table.getTop()
                            , chart.getRight() - table.getLeft(), table.getHeight()).rectReduce(page.getMinCharWidth()
                            , page.getMinCharHeight(), page.getWidth(), page.getHeight())).stream()
                            .filter(chunk -> !chart.intersects(chunk)).collect(Collectors.toList()).size() > 1;
                    if (!hasSideChunk) {
                        shrinkRect = new Rectangle(chart.getRight(), table.getTop(), table.getRight() - chart.getRight(), table.getHeight());
                    }
                } else {
                    boolean hasSideChunk = page.getTextChunks(new Rectangle(chart.getLeft(), table.getTop()
                            , table.getRight() - chart.getLeft(), table.getHeight()).rectReduce(page.getMinCharWidth()
                            , page.getMinCharHeight(), page.getWidth(), page.getHeight())).stream()
                            .filter(chunk -> !chart.intersects(chunk)).collect(Collectors.toList()).size() > 1;
                    if (!hasSideChunk) {
                        shrinkRect = new Rectangle(table.getLeft(), table.getTop(), chart.getLeft() - table.getLeft(), table.getHeight());
                    }
                }
                if (shrinkRect != null) {
                    List<TextBlock> textBlocks = TableRegionDetectionUtils.getTextBlockWithinShape(page, shrinkRect, null);
                    if (textBlocks.size() >= 4 && TableRegionDetectionUtils.calTableColNum(page, shrinkRect, textBlocks) >= 4
                            && page.getTextChunks(shrinkRect).size() >= 16) {
                        logger.info("table and chart is mixed");
                        table.setRect(shrinkRect);
                    }
                }
            }
        }
    }

    private void filterInvalidTables(ContentGroupPage page, List<TableRegion> tablesRegions) {
        if (tablesRegions == null || tablesRegions.isEmpty()) {
            return;
        }

        //split textLines to normal direction and unnormal direction
        List<TextBlock> normalTextBlocks = new ArrayList<>();
        List<TextBlock> unnormalTextBlocks = new ArrayList<>();
        TableRegionDetectionUtils.splitTextLine(page.getTextLines(), normalTextBlocks, unnormalTextBlocks);
        boolean normalDirection = normalTextBlocks.size() > unnormalTextBlocks.size();
        for (TableRegion tableArea : tablesRegions) {
            List<TextElement> texts = page.getText(tableArea);
            List<Ruling> hencLines = Ruling.getRulingsFromArea(page.getHorizontalRulings(), tableArea.withCenterExpand(0.5));
            List<Ruling> vencLines = Ruling.getRulingsFromArea(page.getVerticalRulings(), tableArea.withCenterExpand(0.5));
            if (tableArea.getTableType() == TableType.LineTable) {
                if (hencLines.isEmpty() || vencLines.isEmpty()) {
                    tableArea.markDeleted();
                }
            }

            if (!tableArea.isDeleted()) {
                texts = texts.stream().filter(text -> !text.getText().trim().isEmpty()).collect(Collectors.toList());
                if (texts.isEmpty()) {
                    tableArea.markDeleted();
                } else {
                    // 检查边框有文字从线中穿过，可能为图表，删除该类表格
                    if (vencLines == null || vencLines.isEmpty()) {
                        continue;
                    }
                    vencLines.sort(Comparator.comparing(Ruling::getLeft));
                    if (tableArea.getTableType() == TableType.LineTable) {
                        texts = texts.stream().filter(t -> (((vencLines.get(0).getX1() - t.getLeft() > page.getAvgCharWidth())
                                && (t.getRight() - vencLines.get(0).getX1() > page.getAvgCharWidth())) &&
                                (t.getCenterY() > vencLines.get(0).getY1() && t.getCenterY() < vencLines.get(0).getY2())))
                                .collect(Collectors.toList());
                        if (texts.size() >= 5) {
                            tableArea.markDeleted();
                        }
                    }
                }
            }

            //过滤掉由于隐藏线错误提取导致的单行表格问题
            if (!tableArea.isDeleted() && page.getTextChunks(tableArea.rectReduce(1.0,1.0
                    , page.getWidth(), page.getHeight())).size() <= 2 && Ruling.getRulingsFromArea(page.getRulings()
                    , tableArea.withCenterExpand(0.5)).size() <= 2) {
                tableArea.markDeleted();
                continue;
            }

            //侧边页眉页脚过检
            if (normalDirection && tableArea.getWidth() < 4 * page.getAvgCharWidth()) {
                tableArea.markDeleted();
                continue;
            }

            //T字型误检
            List<Ruling> horLongRulings = hencLines.stream().filter(rul -> rul.getWidth() > 0.9 * tableArea.getWidth())
                    .collect(Collectors.toList());
            List<Ruling> verLongRulings = vencLines.stream().filter(rul -> rul.getHeight() > 0.9 * tableArea.getHeight())
                    .collect(Collectors.toList());
            if (horLongRulings.size() == 1 && verLongRulings.size() == 1 && (FloatUtils.feq(tableArea.getHeight(), page.getHeight(), 1.0)
                    || FloatUtils.feq(tableArea.getWidth(), page.getWidth(), 1.0))) {
                Ruling longestHorRuling = horLongRulings.get(0);
                Ruling longestVerRuling = verLongRulings.get(0);
                float horDiffRatio = (longestHorRuling.getTop() - tableArea.getTop()) / (tableArea.getBottom() - longestHorRuling.getTop());
                float verDiffRatio = (longestVerRuling.getLeft() - tableArea.getLeft()) / (tableArea.getRight() - longestHorRuling.getLeft());
                if ((horDiffRatio > 8 || horDiffRatio < 1.0 / 8.0) || (verDiffRatio > 8 || verDiffRatio < 1.0 / 8.0)) {
                    tableArea.markDeleted();
                }
            }

            //去除chart类误检
            float sparseRatio = tableArea.calSparseRatio(page);
            List<FillAreaGroup> fillAreaGroups = page.getPageFillAreaGroup().stream().filter(group
                    -> (group.getLength() > 5 * page.getAvgCharWidth() && group.getGroupRectArea().overlapRatio(tableArea) > 0.8))
                    .collect(Collectors.toList());
            if (sparseRatio > 0.75 && fillAreaGroups.size() >= 10 && page.getTextChunks(tableArea).size() < 10) {
                tableArea.markDeleted();
            }

            //隐藏线类型的表格误检
            List<Ruling> innerHorRulings = Ruling.getRulingsFromArea(page.getHorizontalRulings(), tableArea.withCenterExpand(0
                    , 0.5));
            List<Ruling> innerVerRulings = Ruling.getRulingsFromArea(page.getVerticalRulings(), tableArea.withCenterExpand(0.5
                    , 0));
            List<Ruling> outerHorRulings = page.getHorizontalRulings().stream().filter(rul -> !tableArea.isShapeIntersects(rul.toRectangle())
                    && tableArea.isVerticallyOverlap(rul.toRectangle()) && (FloatUtils.feq(tableArea.getRight(), rul.getLeft(), page.getAvgCharWidth())
                    || FloatUtils.feq(tableArea.getLeft(), rul.getRight(), page.getAvgCharWidth()))).collect(Collectors.toList());
            if (!innerHorRulings.isEmpty() && !innerVerRulings.isEmpty() && !outerHorRulings.isEmpty()) {
                if (Ruling.allNearlyEqualColor(innerHorRulings, 2) && Ruling.allNearlyEqualColor(innerVerRulings, 2)
                        && Math.abs(innerHorRulings.get(0).getColor().getRed() - innerVerRulings.get(0).getColor().getRed()) > 250) {
                    tableArea.markDeleted();
                }
            }
        }

        tablesRegions.removeIf(Rectangle::isDeleted);
    }

    private void evaluateLineTableConfidence(ContentGroupPage page, List<TableRegion> lineTableRegions) {
        if (lineTableRegions.isEmpty()) {
            return;
        }
        //有线表格周围有对齐文本，降级0.1
        for (TableRegion rc : lineTableRegions) {
            if (isSubTable(page, lineTableRegions, rc)) {
                rc.downgradeConfidence();
            }
        }
    }

    private void collectTextBlockWithinTable(ContentGroupPage page, List<TableRegion> rulingTableRegions) {
        for (TableRegion table : rulingTableRegions) {
            List<TextChunk> textChunksWithinRC = new ArrayList<>();
            for (TextChunk te : page.getTextChunks(table)) {
                Rectangle2D intersectBounds = table.createIntersection(te);
                float area1 = (float) (intersectBounds.getWidth() * intersectBounds.getHeight());
                float area2 = te.getArea();
                if (area1 / (area2 + Float.MIN_VALUE) > 0.5) {
                    textChunksWithinRC.add(te);
                }
            }
            if (textChunksWithinRC.isEmpty()) {
                continue;
            }
            table.addTextBlocksToList(TextMerger.collectByRows(TextMerger.groupByBlock(textChunksWithinRC
                    , page.getHorizontalRulings(), page.getVerticalRulings())));
        }
    }

    private static void filterWrongRuling(ContentGroupPage page, List<Ruling> rawRulings, List<Ruling> normalRulings, List<Ruling> unnormalRulings) {
        if (rawRulings == null || rawRulings.isEmpty() || normalRulings == null || unnormalRulings == null) {
            return;
        }

        List<PathItem> allPathItems = page.getAllPathItems().stream().filter(pathItem -> pathItem.isFill()
                && !pathItem.getPathInfos().isEmpty()).collect(Collectors.toList());
        //目前只考虑白线
        for (Ruling ruling : rawRulings) {
            if (!ruling.isFill()) {
                normalRulings.add(ruling);
                continue;
            }
            if (ruling.nearlyEqualColor(Color.white, 6)) {
                List<PathItem> pathItems = new ArrayList<>();
                for (PathItem pathItem : allPathItems) {
                    Rectangle pathItemBound = new Rectangle(pathItem.getBounds());
                    if (pathItemBound.isShapeIntersects(ruling.toRectangle()) && !TableRegionDetectionUtils.nearlyEqualColor(ruling.getColor()
                            , pathItem.getColor(), 6) && (pathItemBound.getWidth() > 2 || pathItemBound.getHeight() > 2)) {
                        pathItems.add(pathItem);
                    }
                }
                if (pathItems.isEmpty()) {
                    unnormalRulings.add(ruling);
                } else {
                    normalRulings.add(ruling);
                }
            } else {
                normalRulings.add(ruling);
            }
        }
    }

    //find the possible table area
    public List<TableRegion> detect(ContentGroupPage page) {
        List<TableRegion> rulingTableRegions = new ArrayList<>();

        List<TableRegion> rulingRect = new ArrayList<>();
        if (page.getRulings().isEmpty()) {
            return new ArrayList<>();
        }
        for (Ruling rulingTemp : page.getRulings()) {
            if ((rulingTemp.vertical() && (rulingTemp.getLeft() < 1.0 || rulingTemp.getRight() > page.getWidth() - 1.0))
                    || (rulingTemp.horizontal() && (rulingTemp.getTop() < 1.0 || rulingTemp.getBottom() > page.getHeight() - 1.0))) {
                continue;
            }
            rulingRect.add(new TableRegion(rulingTemp.getLeft(), rulingTemp.getTop(), rulingTemp.getWidth(), rulingTemp.getHeight()));
        }

        //merge region
        List<TableRegion> candidateTableRects = findLineTableRegion(page, rulingRect);

        //页面十字交叉表格
        filterCrossLineTableRegion(page, candidateTableRects);

        //对井字型表格被不相交外框包围的情况下的检查和修正
        for (TableRegion rc : candidateTableRects) {
            Rectangle rectTemp = rc.rectReduce(2.0, 2.0, page.width, page.height);
            List<Ruling> innerRulings = page.getRulings().stream().filter(rul -> rectTemp.isShapeIntersects(rul.toRectangle())).collect(Collectors.toList());
            if (innerRulings.isEmpty()) {
                continue;
            }
            if (isWellTableRegion(page, rc, innerRulings, 5.0f)) {
                rulingRect = innerRulings.stream().map(rul -> new TableRegion(rul.getLeft(), rul.getTop(), rul.getWidth(), rul.getHeight())).collect(Collectors.toList());
                rulingTableRegions.addAll(findLineTableRegion(page, rulingRect));
            } else {
                rulingTableRegions.add(rc);
            }
        }

        //filter region by ruling
        Rectangle pageTextBound = page.getTextBoundingBoxWithinRegion(page);
        for (TableRegion rc : rulingTableRegions) {
            if (isBoundingTable(page, rc) || isRulingDislocationTable(page, rc, pageTextBound)) {
                rc.markDeleted();
            }
        }
        rulingTableRegions.removeIf(TableRegion::isDeleted);

        /*/filter sub-region
        for (TableRegion rc : rulingTableRegions) {
            if (isSubTable(page, rulingTableRegions, rc)) {
                rc.markDeleted();
            }
        }
        rulingTableRegions.removeIf(TableRegion :: isDeleted);*/

        //修正井字型表格被不相交外框包围的情况
        correctWellTypeTableRegion(page, rulingTableRegions);

        //merge sub-tables beyond to same line table
        rulingTableRegions = mergeSubTable(page, rulingTableRegions);

        //table classification
        for (TableRegion rc : rulingTableRegions) {
            if (isLineTable(page, rc)) {
                rc.setTableType(TableType.LineTable);
            } else {
                rc.setTableType(TableType.NoLineTable);
            }
            rc.setBoundingFlag(true);
        }
        rulingTableRegions.removeIf(TableRegion::isDeleted);

        filterInvalidTables(page, rulingTableRegions);

        //collect table region by text-bound
        correctTableRegion(page, rulingTableRegions);

        //collect textblock within table
        collectTextBlockWithinTable(page, rulingTableRegions);

        //evaluate table confidence
        evaluateLineTableConfidence(page, rulingTableRegions);

        return rulingTableRegions;
    }
}
