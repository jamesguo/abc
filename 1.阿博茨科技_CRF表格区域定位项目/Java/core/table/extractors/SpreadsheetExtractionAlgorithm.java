package com.abcft.pdfextract.core.table.extractors;

import com.abcft.pdfextract.core.model.Rectangle;
import com.abcft.pdfextract.core.table.*;
import com.abcft.pdfextract.core.util.NumberUtil;
import com.abcft.pdfextract.util.FloatUtils;

import java.awt.geom.Point2D;
import java.util.*;

/**
 * @author manuel
 */
@Deprecated
public class SpreadsheetExtractionAlgorithm implements ExtractionAlgorithm {
    public static final String ALGORITHM_NAME = "lattice";

    public static final String ALGORITHM_DATE = "20171227";
    private static final float MAGIC_HEURISTIC_NUMBER = 0.65f;

    @Override
    public List<? extends Table> extract(Page page) {
        return extract(page, page.getRulings(), false);
    }


    List<? extends Table> extract(Page page, boolean ignoreTexts) {
        return extract(page, page.getRulings(), ignoreTexts);
    }

    /**
     * Extract a list of Table from page using rulings as separators
     */
    public List<? extends Table> extract(Page page, List<Ruling> rulings, boolean ignoreTexts) {
        // split rulings into horizontal and vertical
        List<Ruling> horizontalR = new ArrayList<>(),
                verticalR = new ArrayList<>();

        for (Ruling r : rulings) {
            if (r.horizontal()) {
                horizontalR.add(r);
            } else if (r.vertical()) {
                verticalR.add(r);
            }
        }
        horizontalR = Ruling.collapseOrientedRulings(horizontalR);
        verticalR = Ruling.collapseOrientedRulings(verticalR);

        List<Cell> cells = findCells(horizontalR, verticalR);
        List<Rectangle> spreadsheetAreas = findSpreadsheetsFromCells(cells);

        List<TableWithRulingLines> spreadsheets = new ArrayList<>();
        for (Rectangle area : spreadsheetAreas) {

            List<Cell> overlappingCells = new ArrayList<>();
            for (Cell c : cells) {
                if (c.intersects(area)) {

                    if (!ignoreTexts) {
                        // 扩展文本选区，避免误差或者某些因素导致文本外框和网格线相交的情况。
                        Rectangle textArea = c.withCenterExpand(Cell.CELL_BOUNDS_EPSILON);
                        List<TabularTextChunk> text = TabularUtils.mergeWords(page.getText(textArea));
                        c.setTextElements(TabularUtils.toTextChunks(text));
                    }
                    overlappingCells.add(c);
                }
            }

            List<Ruling> horizontalOverlappingRulings = new ArrayList<>();
            for (Ruling hr : horizontalR) {
                if (area.intersectsLine(hr)) {
                    horizontalOverlappingRulings.add(hr);
                }
            }
            List<Ruling> verticalOverlappingRulings = new ArrayList<>();
            for (Ruling vr : verticalR) {
                if (area.intersectsLine(vr)) {
                    verticalOverlappingRulings.add(vr);
                }
            }

            TableWithRulingLines t = new TableWithRulingLines(area, page, overlappingCells,
                    horizontalOverlappingRulings, verticalOverlappingRulings);

            //t.setExtractionAlgorithm(this);
            t.setAlgorithmProperty(ALGORITHM_NAME, ALGORITHM_DATE);
            spreadsheets.add(t);
        }


        NumberUtil.sortByReadingOrder(spreadsheets);

        /*
         // 添加表格标题 cx 20170420
         for (Table t : spreadsheets) {
            String title = GridlessSheetExtractionAlgorithm.getTableTitleLines(page, t);
            t.setTitle(title);
         }
        */

        return spreadsheets;
    }

    public boolean isTabular(Page page) {

        // if there's no text at all on the page, it's not a table 
        // (we won't be able to do anything with it though)
        if (page.getText().isEmpty()) {
            return false;
        }

        // get minimal region of page that contains every character (in effect,
        // removes white "margins")
        Page minimalRegion = page.getArea(Rectangle.union(page.getText()));

        List<? extends Table> tables = new SpreadsheetExtractionAlgorithm().extract(minimalRegion, false);
        if (tables.size() == 0) {
            return false;
        }
        Table table = null;
        if (tables.size() > 1) {
            for (Table t : tables) {
                if (t.getHeight() < 8.0f)
                    continue;
                table = t;
                break;
            }
            if (null == table) {
                table = tables.get(0);
            }
        } else {
            table = tables.get(0);
        }

        int rowsDefinedByLines = table.getRowCount();
        int colsDefinedByLines = table.getColumnCount();

        tables = new BasicExtractionAlgorithm().extract(minimalRegion);
        if (tables.size() == 0) {
            // TODO WHAT DO WE DO HERE?
        }
        table = tables.get(0);
        int rowsDefinedWithoutLines = table.getRowCount();
        int colsDefinedWithoutLines = table.getColumnCount();

        float ratio = (((float) colsDefinedByLines / colsDefinedWithoutLines) + ((float) rowsDefinedByLines / rowsDefinedWithoutLines)) / 2.0f;

        return ratio > MAGIC_HEURISTIC_NUMBER && ratio < (1 / MAGIC_HEURISTIC_NUMBER);
    }

    private static List<Ruling> findMissingHorizontalRulings(float y, List<Ruling> alignedVerticalRulings, List<Ruling> otherHorizontalRulings) {
        List<Ruling> missingHorizontalRulings = new ArrayList<>();
        if (alignedVerticalRulings.size() < 2) {
            // No more than 2 aligned rulings
            return missingHorizontalRulings;
        }
        int start = 0;
        while (start < alignedVerticalRulings.size()) {
            Ruling firstCol = alignedVerticalRulings.get(start);
            float x1 = firstCol.getLeft();
            int end;
            // Find horizontal rulings in other lines.
            for (end = alignedVerticalRulings.size() - 1; end > start; --end) {
                Ruling lastCol = alignedVerticalRulings.get(end);
                float x2 = lastCol.getRight();
                boolean exists = otherHorizontalRulings.stream()
                        .anyMatch(hr -> FloatUtils.within(y, hr.getTop(), 0.1f) && (FloatUtils.within(hr.getLeft(), x1, 0.1f) || (x1 >= hr.getLeft()))
                                && (FloatUtils.within(hr.getRight(), x2, 0.1f) || (x2 <= hr.getRight())));
                boolean hasHorizontal = otherHorizontalRulings.stream()
                        .anyMatch(hr -> FloatUtils.within(hr.getRight(), x2, 0.1f));
                if (hasHorizontal) {
                    // We have similar horizontal rulings in other lines...
                    if (!exists) {
                        // The ruling is missing...
                        missingHorizontalRulings.add(new Ruling(y, x1, x2 - x1, 0.0f));
                    }
                    break;
                }
            }
            start = end + 1;
        }
        return missingHorizontalRulings;
    }

    private static void checkRepeateLine(boolean hvFlag, List<Ruling> lines) {
        if (!lines.isEmpty()) {
            Ruling baseLine;
            if (hvFlag == true) {
                lines.sort(Comparator.comparing(Ruling::getX1));
                baseLine = lines.get(0);
                for (int i = 1; i < lines.size(); i++) {
                    Ruling otherLine =  lines.get(i);
                    if (otherLine.getX1() - baseLine.getX2() >= 3) {
                        baseLine = otherLine;
                        continue;
                    } else {
                        otherLine.markDeleted();
                        if (otherLine.getX2() > baseLine.getX2()) {
                            baseLine.x2 = (float)otherLine.getX2();
                        }
                    }
                }
            } else {
                lines.sort(Comparator.comparing(Ruling::getY1));
                baseLine = lines.get(0);
                for (int i = 1; i < lines.size(); i++) {
                    Ruling otherLine =  lines.get(i);
                    if (otherLine.getY1() - baseLine.getY2() >= 3) {
                        baseLine = otherLine;
                        continue;
                    } else {
                        otherLine.markDeleted();
                        if (otherLine.getY2() > baseLine.getY2()) {
                            baseLine.y2 = (float)otherLine.getY2();
                        }
                    }
                }
            }
        }
    }

    public static void preProcessLines(List<Ruling> horizontalRulingLines, List<Ruling> verticalRulingLines) {

        List<Ruling> targetLines = new ArrayList<>();
        // process henc
        if (!horizontalRulingLines.isEmpty()) {
            Ruling baseLine = horizontalRulingLines.get(0);
            targetLines.add(baseLine);
            for (int i = 1; i < horizontalRulingLines.size(); i++) {
                Ruling candidateLine = horizontalRulingLines.get(i);
                if (FloatUtils.feq(baseLine.getY1(), candidateLine.getY1(), 2.0)) {
                    targetLines.add(candidateLine);
                } else {
                    checkRepeateLine(true, targetLines);
                    baseLine = candidateLine;
                    targetLines.clear();
                    targetLines.add(baseLine);
                }
            }
            checkRepeateLine(true, targetLines);
            horizontalRulingLines.removeIf(Ruling::isDeleted);
            targetLines.clear();

            // process venc
            if (!verticalRulingLines.isEmpty()) {
                baseLine = verticalRulingLines.get(0);
                targetLines.add(baseLine);
                for (int i = 1; i < verticalRulingLines.size(); i++) {
                    Ruling candidateLine = verticalRulingLines.get(i);
                    if (FloatUtils.feq(baseLine.getX1(), candidateLine.getX1(), 2.0)) {
                        targetLines.add(candidateLine);
                    } else {
                        checkRepeateLine(false, targetLines);
                        baseLine = candidateLine;
                        targetLines.clear();
                        targetLines.add(baseLine);
                    }
                }
                checkRepeateLine(false, targetLines);
                verticalRulingLines.removeIf(Ruling::isDeleted);
                targetLines.clear();
            }
        }
    }

    public static List<Cell> findCells(List<Ruling> horizontalRulingLines, List<Ruling> verticalRulingLines) {
        List<Cell> cellsFound = new ArrayList<>();

        horizontalRulingLines.sort(Comparator.comparing(Ruling::getY1));
        verticalRulingLines.sort(Comparator.comparing(Ruling::getX1));

        preProcessLines(horizontalRulingLines, verticalRulingLines);

        List<Ruling> hrs = new ArrayList<>(horizontalRulingLines);
        List<Ruling> vrs = new ArrayList<>(verticalRulingLines);

        // TODO Find vertical ruling lines with aligned endpoints at the top/bottom of a grid
        // that aren't connected with an horizontal ruler?
        // see: https://github.com/jazzido/tabula-extractor/issues/78#issuecomment-41481207
        if (!verticalRulingLines.isEmpty()) {
            // Align top endpoints
            List<Ruling> alignedRulings = new ArrayList<>();
            Ruling relativeRule;

            verticalRulingLines.sort(Ruling.VERTICAL_TOP_X);
            relativeRule = verticalRulingLines.get(0);
            alignedRulings.add(relativeRule);

            for (int i = 1; i < verticalRulingLines.size(); ++i) {
                Ruling r = verticalRulingLines.get(i);
                if (FloatUtils.within(r.getTop(), relativeRule.getTop(), 0.2f)) {
                    // Same aligned vertical rulings
                    alignedRulings.add(r);
                } else {
                    hrs.addAll(findMissingHorizontalRulings(relativeRule.getTop(), alignedRulings, horizontalRulingLines));
                    alignedRulings.clear();
                    relativeRule = r;
                    alignedRulings.add(r);
                }
            }
            hrs.addAll(findMissingHorizontalRulings(relativeRule.getTop(), alignedRulings, horizontalRulingLines));
            alignedRulings.clear();

            verticalRulingLines.sort(Ruling.VERTICAL_BOTTOM_X);
            relativeRule = verticalRulingLines.get(0);
            alignedRulings.add(relativeRule);

            for (int i = 1; i < verticalRulingLines.size(); ++i) {
                Ruling r = verticalRulingLines.get(i);
                if (FloatUtils.within(r.getBottom(), relativeRule.getBottom(), 0.2f)) {
                    // Same aligned vertical rulings
                    alignedRulings.add(r);
                } else {
                    hrs.addAll(findMissingHorizontalRulings(relativeRule.getBottom(), alignedRulings, horizontalRulingLines));
                    alignedRulings.clear();
                    relativeRule = r;
                    alignedRulings.add(r);
                }
            }
            hrs.addAll(findMissingHorizontalRulings(relativeRule.getBottom(), alignedRulings, horizontalRulingLines));
            alignedRulings.clear();
        }

        // TODO This should not happen, find out why
        hrs.removeIf(r -> !r.horizontal());
        vrs.removeIf(r -> !r.vertical());

        Map<Point2D, Ruling[]> intersectionPoints = Ruling.findIntersections(hrs, vrs);
        List<Point2D> intersectionPointsList = new ArrayList<>(intersectionPoints.keySet());
        intersectionPointsList.sort(PointComparator.Y_FIRST);
        boolean doBreak;

        for (int i = 0; i < intersectionPointsList.size(); i++) {
            Point2D topLeft = intersectionPointsList.get(i);
            Ruling[] hv = intersectionPoints.get(topLeft);
            doBreak = false;

            // CrossingPointsDirectlyBelow( topLeft );
            List<Point2D> xPoints = new ArrayList<>();
            // CrossingPointsDirectlyToTheRight( topLeft );
            List<Point2D> yPoints = new ArrayList<>();

            for (Point2D p : intersectionPointsList.subList(i, intersectionPointsList.size())) {
                if (p.getX() == topLeft.getX() && p.getY() > topLeft.getY()) {
                    xPoints.add(p);
                }
                if (p.getY() == topLeft.getY() && p.getX() > topLeft.getX()) {
                    yPoints.add(p);
                }
            }
            outer:
            for (Point2D xPoint : xPoints) {
                if (doBreak) {
                    break;
                }

                // is there a vertical edge b/w topLeft and xPoint?
                if (!hv[1].equals(intersectionPoints.get(xPoint)[1])) {
                    continue;
                }
                for (Point2D yPoint : yPoints) {
                    // is there an horizontal edge b/w topLeft and yPoint ?
                    if (!hv[0].equals(intersectionPoints.get(yPoint)[0])) {
                        continue;
                    }
                    Point2D btmRight = new Point2D.Float((float) yPoint.getX(), (float) xPoint.getY());
                    if (intersectionPoints.containsKey(btmRight)
                            && intersectionPoints.get(btmRight)[0].equals(intersectionPoints.get(xPoint)[0])
                            && intersectionPoints.get(btmRight)[1].equals(intersectionPoints.get(yPoint)[1])) {
                        cellsFound.add(new Cell(topLeft, btmRight));
                        doBreak = true;
                        break outer;
                    }
                }
            }
        }


        return cellsFound;
    }

    public static List<Rectangle> findSpreadsheetsFromCells(List<? extends Rectangle> cells) {
        // via: http://stackoverflow.com/questions/13746284/merging-multiple-adjacent-rectangles-into-one-polygon
        List<Rectangle> rectangles = new ArrayList<>();
        Set<Point2D> pointSet = new HashSet<>();
        Map<Point2D, Point2D> edgesH = new HashMap<>();
        Map<Point2D, Point2D> edgesV = new HashMap<>();
        int i = 0;

        cells = new ArrayList<>(new HashSet<Rectangle>(cells));

        NumberUtil.sortByReadingOrder(cells);

        for (Rectangle cell : cells) {
            cell.round();
            for (Point2D pt : cell.getPoints()) {
                if (pointSet.contains(pt)) { // shared vertex, remove it
                    pointSet.remove(pt);
                } else {
                    pointSet.add(pt);
                }
            }
        }

        // X first sort
        List<Point2D> pointsSortX = new ArrayList<>(pointSet);
        pointsSortX.sort(PointComparator.X_FIRST);
        // Y first sort
        List<Point2D> pointsSortY = new ArrayList<>(pointSet);
        pointsSortY.sort(PointComparator.Y_FIRST);

        while (i < pointSet.size()) {
            float currY = (float) pointsSortY.get(i).getY();
            while (i < pointSet.size() && FloatUtils.feq(pointsSortY.get(i).getY(), currY)) {
                edgesH.put(pointsSortY.get(i), pointsSortY.get(i + 1));
                edgesH.put(pointsSortY.get(i + 1), pointsSortY.get(i));
                i += 2;
            }
        }

        i = 0;
        while (i < pointSet.size()) {
            float currX = (float) pointsSortX.get(i).getX();
            while (i < pointSet.size() && FloatUtils.feq(pointsSortX.get(i).getX(), currX)) {
                edgesV.put(pointsSortX.get(i), pointsSortX.get(i + 1));
                edgesV.put(pointsSortX.get(i + 1), pointsSortX.get(i));
                i += 2;
            }
        }

        // Get all the polygons
        List<List<PolygonVertex>> polygons = new ArrayList<>();
        Point2D nextVertex;
        while (!edgesH.isEmpty()) {
            ArrayList<PolygonVertex> polygon = new ArrayList<>();
            Point2D first = edgesH.keySet().iterator().next();
            polygon.add(new PolygonVertex(first, Direction.HORIZONTAL));
            edgesH.remove(first);

            while (true) {
                PolygonVertex curr = polygon.get(polygon.size() - 1);
                PolygonVertex lastAddedVertex;
                if (curr.direction == Direction.HORIZONTAL) {
                    nextVertex = edgesV.get(curr.point);
                    edgesV.remove(curr.point);
                    lastAddedVertex = new PolygonVertex(nextVertex, Direction.VERTICAL);
                    polygon.add(lastAddedVertex);
                } else {
                    nextVertex = edgesH.get(curr.point);
                    edgesH.remove(curr.point);
                    lastAddedVertex = new PolygonVertex(nextVertex, Direction.HORIZONTAL);
                    polygon.add(lastAddedVertex);
                }

                if (lastAddedVertex.equals(polygon.get(0))) {
                    // closed polygon
                    polygon.remove(polygon.size() - 1);
                    break;
                }
            }

            for (PolygonVertex vertex : polygon) {
                edgesH.remove(vertex.point);
                edgesV.remove(vertex.point);
            }
            polygons.add(polygon);
        }

        // calculate grid-aligned minimum area rectangles for each found polygon
        for (List<PolygonVertex> poly : polygons) {
            float top = Float.MAX_VALUE;
            float left = Float.MAX_VALUE;
            float bottom = Float.MIN_VALUE;
            float right = Float.MIN_VALUE;
            for (PolygonVertex pt : poly) {
                top = (float) Math.min(top, pt.point.getY());
                left = (float) Math.min(left, pt.point.getX());
                bottom = (float) Math.max(bottom, pt.point.getY());
                right = (float) Math.max(right, pt.point.getX());
            }
            rectangles.add(new Rectangle(left, top, right - left, bottom - top));
        }

        return rectangles;
    }

    @Override
    public String toString() {
        return "lattice";
    }

    @Override
    public String getVersion() {
        return "0.9";
    }

    private enum Direction {
        HORIZONTAL,
        VERTICAL
    }

    static class PolygonVertex {
        Point2D point;
        Direction direction;

        public PolygonVertex(Point2D point, Direction direction) {
            this.direction = direction;
            this.point = point;
        }

        public boolean equals(Object other) {
            if (this == other)
                return true;
            if (!(other instanceof PolygonVertex))
                return false;
            return this.point.equals(((PolygonVertex) other).point);
        }

        public int hashCode() {
            return this.point.hashCode();
        }

        public String toString() {
            return String.format("%s[point=%s,direction=%s]", this.getClass().getName(), this.point.toString(), this.direction.toString());
        }
    }
}
