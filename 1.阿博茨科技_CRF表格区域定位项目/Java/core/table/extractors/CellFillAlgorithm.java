package com.abcft.pdfextract.core.table.extractors;
import com.abcft.pdfextract.core.model.Rectangle;
import com.abcft.pdfextract.core.model.TextChunk;
import com.abcft.pdfextract.core.model.TextElement;
import com.abcft.pdfextract.core.table.Page;
import com.abcft.pdfextract.core.table.Ruling;
import com.abcft.pdfextract.core.table.TableRegion;
import com.abcft.pdfextract.util.FloatUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.math3.util.FastMath;

import java.util.*;
import java.util.stream.Collectors;

public class CellFillAlgorithm {

    private enum FindDirect {
        TOP, BOTTOM, LEFT, RIGHT, OTHER
    }

    public enum PdfType {
        WordType, NoWordType
    }

    private static class RegionLinesInfo {

        private List<Ruling> tableRegionAllHencLines = new ArrayList<>();
        private List<Ruling> tableRegionAllVencLines = new ArrayList<>();

        public RegionLinesInfo() {

        }

        public void setTableRegionHencLines(int index, Ruling hencLine) {
            this.tableRegionAllHencLines.add(index, hencLine);
        }

        public void setTableRegionVencLines(int index, Ruling vencLine) {
            this.tableRegionAllVencLines.add(index, vencLine);
        }

        public List<Ruling> getTableRegionAllHencLines() {
            return this.tableRegionAllHencLines;
        }

        public List<Ruling> getTableRegionAllVencLines() {
            return this.tableRegionAllVencLines;
        }

        public void findTableRegionHencRulings(Rectangle area, List<Ruling> horizontalRulingLines) {
            if (!horizontalRulingLines.isEmpty()) {
                this.tableRegionAllHencLines = Ruling.getRulingsFromArea(horizontalRulingLines, area);
            }
            if (!this.tableRegionAllHencLines.isEmpty()) {
                this.tableRegionAllHencLines.sort(Comparator.comparing(Ruling::getY1));
            }
        }

        public void findTableRegionVencRulings(Rectangle area, List<Ruling> verticalRulingLines) {
            if (!verticalRulingLines.isEmpty()) {
                this.tableRegionAllVencLines = Ruling.getRulingsFromArea(verticalRulingLines, area);
            }
            if (!this.tableRegionAllVencLines.isEmpty()) {
                this.tableRegionAllVencLines.sort(Comparator.comparing(Ruling::getX1));
            }
        }
    }

    private void checkBroaderLine(Rectangle area, Page page, RegionLinesInfo rulingInfo) {

        rulingInfo.findTableRegionHencRulings(area, page.getHorizontalRulings());
        List<Ruling> hencLines = rulingInfo.getTableRegionAllHencLines();
        int index = 0;
        if (hencLines == null || hencLines.isEmpty()) {
            // 表格区域无水平线时，直接补充上下两根表格边界线
            rulingInfo.setTableRegionHencLines(index, new Ruling(area.getTop(), area.getLeft(), (float)area.getWidth(), 0));
            rulingInfo.setTableRegionHencLines(index + 1, new Ruling(area.getBottom(), area.getLeft(), (float)area.getWidth(), 0));
        } else {
            Ruling targetLine = hencLines.get(index);
            if (!FloatUtils.feq(area.getTop(), targetLine.getY1(), 2.5)) {
                rulingInfo.setTableRegionHencLines(index, new Ruling(area.getTop(), area.getLeft(), (float)area.getWidth(), 0));
            } else {
                if (!FloatUtils.feq(area.getWidth(), targetLine.getWidth(), 2.5)) {
                    rulingInfo.getTableRegionAllHencLines().remove(targetLine);
                    rulingInfo.setTableRegionHencLines(index, new Ruling(area.getTop(), area.getLeft(), (float)area.getWidth(), 0));
                }
            }
            index = hencLines.size() - 1;
            targetLine = hencLines.get(index);
            if (!FloatUtils.feq(area.getBottom(), targetLine.getY1(), 2.5)) {
                rulingInfo.setTableRegionHencLines(index + 1, new Ruling(area.getBottom(), area.getLeft(), (float)area.getWidth(), 0));
            } else {
                if (!FloatUtils.feq(area.getWidth(), targetLine.getWidth(), 2.5)) {
                    rulingInfo.getTableRegionAllHencLines().remove(targetLine);
                    rulingInfo.setTableRegionHencLines(index, new Ruling(area.getBottom(), area.getLeft(), (float)area.getWidth(), 0));
                }
            }
        }

        index = 0;
        rulingInfo.findTableRegionVencRulings(area, page.getVerticalRulings());
        List<Ruling> vencLines = rulingInfo.getTableRegionAllVencLines();
        if (vencLines == null || vencLines.isEmpty()) {
            // 表格区域无垂直线时，直接补充左右两根表格边界线
            rulingInfo.setTableRegionVencLines(index, new Ruling(area.getTop(), area.getLeft(), 0, (float)area.getHeight()));
            rulingInfo.setTableRegionVencLines(index + 1, new Ruling(area.getTop(), area.getRight(), 0, (float)area.getHeight()));
        } else {
            Ruling targetLine = vencLines.get(index);
            if (!FloatUtils.feq(area.getLeft(), targetLine.getX1(), 2.5)) {
                rulingInfo.setTableRegionVencLines(index, new Ruling(area.getTop(), area.getLeft(), 0, (float)area.getHeight()));
            } else {
                if (!FloatUtils.feq(area.getHeight(), targetLine.getHeight(), 2.5)) {
                    rulingInfo.getTableRegionAllVencLines().remove(targetLine);
                    rulingInfo.setTableRegionVencLines(index, new Ruling(area.getTop(), area.getLeft(), 0, (float)area.getHeight()));
                }
            }
            index = vencLines.size() - 1;
            targetLine = vencLines.get(index);
            if (!FloatUtils.feq(area.getRight(), targetLine.getX1(), 2.5)) {
                rulingInfo.setTableRegionVencLines(index + 1, new Ruling(area.getTop(), area.getRight(), 0, (float)area.getHeight()));
            } else {
                if (!FloatUtils.feq(area.getHeight(), targetLine.getHeight(), 2.5)) {
                    rulingInfo.getTableRegionAllVencLines().remove(targetLine);
                    rulingInfo.setTableRegionVencLines(index, new Ruling(area.getTop(), area.getRight(), 0, (float)area.getHeight()));
                }
            }
        }
    }

    private void fillSubCellToList(Rectangle targetRect, List<Rectangle> clipAeras){
        if (targetRect != null) {
            clipAeras.add(targetRect);
        }
    }

    // 纠正边框有误差，没有对齐的情况
    public static void correctTableBroadLines(double widthThreshold, double heightThrehold, List<Ruling> allHorizontalRulings, List<Ruling> allVerticalRulings) {

        if (allHorizontalRulings.isEmpty() || allVerticalRulings.isEmpty()) {
            return;
        }
        // left border line
        List<Ruling> allCrossHorizontalLines = allHorizontalRulings.stream()
                .filter(r -> (FloatUtils.feq(r.getX1(), allVerticalRulings.get(0).getX1(), widthThreshold))).collect(Collectors.toList());
        if (!allCrossHorizontalLines.isEmpty()) {
            float maxX = allCrossHorizontalLines.stream().max(Comparator.comparing(Ruling::getLeft)).get().getLeft();
            allVerticalRulings.get(0).x1 = maxX;
            allVerticalRulings.get(0).x2 = maxX;
        }

        // right border line
        allCrossHorizontalLines = allHorizontalRulings.stream()
                .filter(r -> (FloatUtils.feq(r.getX2(), allVerticalRulings.get(allVerticalRulings.size() - 1).getX1(), widthThreshold))).collect(Collectors.toList());
        if (!allCrossHorizontalLines.isEmpty()) {
            float minX = allCrossHorizontalLines.stream().min(Comparator.comparing(Ruling::getRight)).get().getRight();
            allVerticalRulings.get(allVerticalRulings.size() - 1).x1 = minX;
            allVerticalRulings.get(allVerticalRulings.size() - 1).x2 = minX;
        }

        // top border line
        List<Ruling> allCrossVerticalLines = allVerticalRulings.stream()
                .filter(r -> (FloatUtils.feq(r.getY1(), allHorizontalRulings.get(0).getY1(), heightThrehold))).collect(Collectors.toList());
        if (!allCrossVerticalLines.isEmpty()) {
            float maxY = allCrossVerticalLines.stream().max(Comparator.comparing(Ruling::getY1)).get().getTop();
            allHorizontalRulings.get(0).y1 = maxY;
            allHorizontalRulings.get(0).y2 = maxY;
        }


        // bottom border line
        allCrossVerticalLines = allVerticalRulings.stream()
                .filter(r -> (FloatUtils.feq(r.getY2(), allHorizontalRulings.get(allHorizontalRulings.size() - 1).getY1(), heightThrehold))).collect(Collectors.toList());
        if (!allCrossVerticalLines.isEmpty()) {
            float minY = allCrossVerticalLines.stream().min(Comparator.comparing(Ruling::getY2)).get().getBottom();
            allHorizontalRulings.get(allHorizontalRulings.size() - 1).y1 = minY;
            allHorizontalRulings.get(allHorizontalRulings.size() - 1).y2 = minY;
        }
    }

    private void fillCellsByLines(Page page, Rectangle areaRegion, List<Rectangle> clipAeras, RegionLinesInfo rulingInfo) {
        List<Ruling> lineHenc = rulingInfo.getTableRegionAllHencLines();
        List<Ruling> candidateHencLines = new ArrayList<>();
        // 此处不能用整个page的文字最小宽高作为阈值，应选表格区域内的文字，否则会产生较大误差
        double minHeight = page.getText(areaRegion).parallelStream()
                .mapToDouble(TextElement::getTextHeight).min().orElse(Page.DEFAULT_MIN_CHAR_HEIGHT);
        double minWidth = page.getText(areaRegion).parallelStream()
                .mapToDouble(TextElement::getWidth).min().orElse(Page.DEFAULT_MIN_CHAR_WIDTH);
        double thresWidth = minWidth;
        List<TextElement> textElements = page.getText(areaRegion).stream()
                .filter(te -> (!te.isWhitespace())).collect(Collectors.toList());
        if (!textElements.isEmpty()) {
            double minLeft = textElements.stream().min(Comparator.comparing(TextElement::getLeft)).get().getWidth();
            if (thresWidth < minLeft) {
                thresWidth = minLeft;
            }
            double maxRight = textElements.stream().max(Comparator.comparing(TextElement::getRight)).get().getWidth();
            if (thresWidth < maxRight) {
                thresWidth = maxRight;
            }
        }

        double threshold;
        candidateHencLines.add(new Ruling(areaRegion.getTop(), areaRegion.getLeft(), (float)areaRegion.getWidth(),0));
        boolean isEnglish = StringUtils.startsWithIgnoreCase(page.getLanguage(), "en");
        threshold = isEnglish ? 5.5 : (0.5 * minHeight);
        for (Ruling tmpHencLine : lineHenc) {
            if (tmpHencLine.getWidth() > 5) {
                Ruling lastLine = candidateHencLines.get(candidateHencLines.size() - 1);
                if (FloatUtils.feq(lastLine.getY1(), tmpHencLine.getY1(), threshold)
                        && tmpHencLine.horizontallyOverlapRatio(lastLine) > 0) {
                    float h = (float)((lastLine.getY1() + tmpHencLine.getY1()) / 2);
                    float x1 = (float)((lastLine.getX1() > tmpHencLine.getX1()) ? tmpHencLine.getX1() : lastLine.getX1());
                    float x2 = (float)((lastLine.getX2() > tmpHencLine.getX2()) ? lastLine.getX2() : tmpHencLine.getX2());
                    candidateHencLines.remove(lastLine);
                    candidateHencLines.add(new Ruling(h, x1, x2 - x1, 0));
                } else {
                    candidateHencLines.add(tmpHencLine);
                }
            }
        }

        List<Ruling> lineVenc = rulingInfo.getTableRegionAllVencLines();
        List<Ruling> candidateVencLines = new ArrayList<>();
        candidateVencLines.add(new Ruling(areaRegion.getTop(), areaRegion.getLeft(), 0, (float)areaRegion.getHeight()));
        threshold = isEnglish ? 3.5 : (1.5 * minWidth);
        for (Ruling tmpVencLine : lineVenc) {
            if (tmpVencLine.getHeight() > 5) {
                Ruling lastLine = candidateVencLines.get(candidateVencLines.size() - 1);
                if (FloatUtils.feq(lastLine.getX1(), tmpVencLine.getX1(), threshold) &&
                        !(tmpVencLine.getY1() - lastLine.getY2() > minHeight)
                        && (tmpVencLine.verticallyOverlapRatio(lastLine) > 0.8)) {
                    float x = (float) ((lastLine.getX1() + tmpVencLine.getX1()) / 2);
                    float y1 = (float) ((lastLine.getY1() > tmpVencLine.getY1()) ? tmpVencLine.getY1() : lastLine.getY1());
                    float y2 = (float) ((lastLine.getY2() > tmpVencLine.getY2()) ? lastLine.getY2() : tmpVencLine.getY2());
                    candidateVencLines.remove(lastLine);
                    candidateVencLines.add(new Ruling(y1, x, 0, y2 - y1));

                } else {
                    candidateVencLines.add(tmpVencLine);
                }
            }
        }
        candidateHencLines.sort(Comparator.comparing(Ruling::getY1));
        correctTableBroadLines(thresWidth, minHeight, candidateHencLines, candidateVencLines);
        List<? extends Rectangle> candidateCells = Ruling.findCells(candidateHencLines, candidateVencLines);
        threshold = isEnglish ? 5.5 : minHeight;
        for (Rectangle candidateCell : candidateCells) {
            if ((candidateCell.getWidth() >= 5) && (candidateCell.getHeight() >= threshold)) {
                Rectangle rect = new Rectangle(candidateCell.getLeft(), candidateCell.getTop(),
                        (float) candidateCell.getWidth(), (float) (candidateCell.getHeight()));
                fillSubCellToList(rect, clipAeras);
            }
        }
        removeRepeatCells(page, clipAeras);
    }

    private void findRegionCells(Rectangle areas, List<Rectangle> clipCells, List<Rectangle> clipAeras) {
        if (clipAeras == null || clipAeras.isEmpty()) {
            return;
        }
        for (Rectangle tmpCandidateClip : clipAeras) {
            if (areas.nearlyContains(tmpCandidateClip, 3.0) || areas.nearlyEquals(tmpCandidateClip, 3.0)) {
                clipCells.add(tmpCandidateClip);
            }
        }
    }

    // 水平方向找临近cell的top/bottom直线
    private void findLeftRightHencLines(FindDirect dictx, FindDirect dicty, Rectangle baseCell, List<Ruling> candiateHencLines, Page page, RegionLinesInfo rulingInfo) {
        List<Ruling> lineHenc = rulingInfo.getTableRegionAllHencLines();
        if (lineHenc == null || lineHenc.isEmpty()) {
            return;
        }
        double x, y;
        if (dictx == FindDirect.LEFT) {
            if (dicty == FindDirect.TOP) {
                x = baseCell.getLeft();
                y = baseCell.getTop();
            } else if (dicty == FindDirect.BOTTOM) {
                x = baseCell.getLeft();
                y = baseCell.getBottom();
            } else {
                return;
            }
            for (Ruling hline : lineHenc) {
                boolean bContainLine = FloatUtils.feq(hline.getY1(), y, 3.5) &&
                        (x - hline.getX1() > 10) &&
                        (FloatUtils.feq(hline.getX2(), x, 2.0) || (hline.getX2() - x >= 2.0));
                if (bContainLine) {
                    candiateHencLines.add(hline);
                    break;
                }
            }
        } else if (dictx == FindDirect.RIGHT) {
            if (dicty == FindDirect.TOP) {
                x = baseCell.getRight();
                y = baseCell.getTop();
            } else if (dicty == FindDirect.BOTTOM) {
                x = baseCell.getRight();
                y = baseCell.getBottom();
            } else {
                return;
            }
            for (Ruling hline : lineHenc) {
                boolean bContainLine = FloatUtils.feq(hline.getY1(), y, 3.5) &&
                        (hline.getX2() - x > 10) &&
                        (FloatUtils.feq(hline.getX1(), x, 2.0) || (x - hline.getX1() >= 2.0));
                if (bContainLine) {
                    candiateHencLines.add(hline);
                    break;
                }
            }
        } else {
            return;
        }
    }

    // 水平方向找临近cell的left/right直线
    private void findLeftRightVencLines(FindDirect dictx1, FindDirect dictx2, Ruling baseLine, Page page,
                                        Rectangle baseCell, List<Ruling> candiateVencLines, RegionLinesInfo rulingInfo) {
        boolean result;
        List<Ruling> vlines = rulingInfo.getTableRegionAllVencLines();
        List<Ruling> targetLines = new ArrayList<>();
        if (dictx1 == FindDirect.LEFT) {
            targetLines.addAll(vlines);
        } else {
            targetLines.addAll(vlines);
            Collections.reverse(targetLines);
        }
        for (Ruling candidateLine : targetLines) {
            if (dictx1 == FindDirect.LEFT) {
                if (dictx2 == FindDirect.TOP) {
                    // left top: find left
                    boolean bContainLeftTop = (FloatUtils.feq(candidateLine.getY1(), baseCell.getTop(), 3.5) ||
                            ((baseCell.getTop() - candidateLine.getY1()) >= 3.5)) && (baseCell.getLeft() - candidateLine.getX1() > 10);
                    boolean bContainLeftBorder = FloatUtils.feq(candidateLine.getX1(), baseLine.getX1(), 1.0) ||
                            (candidateLine.getX1() - baseLine.getX1() >= 1.0);
                    boolean bContainTopBottom = (candidateLine.getY2() - baseCell.getTop() > 10);
                    result = bContainLeftTop && bContainLeftBorder && bContainTopBottom;
                } else if (dictx2 == FindDirect.BOTTOM) {
                    // left bottom: find left
                    boolean bContainLeftBottom = (FloatUtils.feq(candidateLine.getY2(), baseCell.getBottom(), 3.5) ||
                            ((candidateLine.getY2() - baseCell.getBottom()) >= 3.5)) && (baseCell.getLeft() - candidateLine.getX1() > 10);
                    boolean bContainLeftBorder = FloatUtils.feq(candidateLine.getX1(), baseLine.getX1(), 1.0) ||
                            (candidateLine.getX1() - baseLine.getX1() >= 1.0);
                    boolean bContainTopBottom = (baseCell.getBottom() - candidateLine.getY1() > 10);
                    result = bContainLeftBottom && bContainLeftBorder && bContainTopBottom;
                } else {
                    result = false;
                }
                if (FloatUtils.feq(candidateLine.getX1(), baseCell.getLeft(), 2.0) &&
                        ((FloatUtils.feq(candidateLine.getY1(), baseCell.getTop(), 2.0))
                                || (baseCell.getTop() - candidateLine.getY1() >= 2.0)) &&
                        ((FloatUtils.feq(candidateLine.getY2(), baseCell.getBottom(), 2.0))
                                || (candidateLine.getY2() - baseCell.getBottom() >= 2.0))) {
                    candiateVencLines.add(candidateLine);
                    break;
                }
            } else if (dictx1 == FindDirect.RIGHT) {

                if (dictx2 == FindDirect.TOP) {
                    // right top: find right
                    boolean bContainRightTop = (FloatUtils.feq(candidateLine.getY1(), baseCell.getTop(), 3.5) ||
                            ((baseCell.getTop() - candidateLine.getY1()) >= 3.5)) && (candidateLine.getX1() - baseCell.getRight() > 10);
                    boolean bContainRightBorder = FloatUtils.feq(candidateLine.getX1(), baseLine.getX2(), 2.0) ||
                            (baseLine.getX2() - candidateLine.getX1() >= 2.0);
                    boolean bContainTopBottom = (candidateLine.getY2() - baseCell.getTop() > 10);
                    result = bContainRightTop && bContainRightBorder && bContainTopBottom;
                } else if (dictx2 == FindDirect.BOTTOM) {
                    // right bottom: find right
                    boolean bContainRightBottom = (FloatUtils.feq(candidateLine.getY2(), baseCell.getBottom(), 3.5) ||
                            ((candidateLine.getY2() - baseCell.getBottom()) >= 3.5)) && (candidateLine.getX1() - baseCell.getRight() > 10);
                    boolean bContainRightBorder = FloatUtils.feq(candidateLine.getX1(), baseLine.getX2(), 2.0) ||
                            (baseLine.getX2() - candidateLine.getX1() >= 2.0);
                    boolean bContainTopBottom = (baseCell.getBottom() - candidateLine.getY1() > 10);
                    result = bContainRightBottom && bContainRightBorder && bContainTopBottom;
                } else {
                    result = false;
                }
                if (FloatUtils.feq(candidateLine.getX1(), baseCell.getRight(), 2.0) &&
                        ((FloatUtils.feq(candidateLine.getY1(), baseCell.getTop(), 2.0))
                                || (baseCell.getTop() - candidateLine.getY1() >= 2.0)) &&
                        ((FloatUtils.feq(candidateLine.getY2(), baseCell.getBottom(), 2.0))
                                || (candidateLine.getY2() - baseCell.getBottom() >= 2.0))) {
                    candiateVencLines.add(candidateLine);
                    break;
                }
            } else {
                result = false;
            }
            if (result) {
                if (candiateVencLines.size() > 0) {
                    Ruling lastLine = candiateVencLines.get(candiateVencLines.size() - 1);
                    boolean flag = (dictx1 == FindDirect.LEFT) ?
                            (candidateLine.getX1() - lastLine.getX1() > 6) : (lastLine.getX1() - candidateLine.getX1() > 6);
                    if (flag) {
                        candiateVencLines.add(candidateLine);
                    }
                } else {
                    candiateVencLines.add(candidateLine);
                }
            }
        }
    }

    // 水平方向根据cell的3根线查找最后一根线
    private boolean findLeftRightCellEndLine(FindDirect dictx1, FindDirect dictx2, Rectangle baseCell, Ruling leftLine,
                                             Ruling rightLine, Ruling targetLine) {
        boolean result;
        boolean bLeftPoint = FloatUtils.feq(targetLine.getX1(), leftLine.getX1(), 2.0) ||
                (leftLine.getX1() - targetLine.getX1() >= 2.0);
        boolean bRightPoint = FloatUtils.feq(targetLine.getX2(), rightLine.getX1(), 2.0) ||
                (targetLine.getX2() - rightLine.getX1() >= 2.0);
        if (dictx1 == FindDirect.LEFT) {
            if (dictx2 == FindDirect.TOP) {
                // left top: find bottom
                boolean bHeight = (targetLine.getY1() - baseCell.getTop() > 10);
                result = bLeftPoint && bRightPoint && bHeight;
            } else if(dictx2 == FindDirect.BOTTOM) {
                // left bottom: find top
                boolean bHeight = (baseCell.getBottom() - targetLine.getY1() > 10);
                result = bLeftPoint && bRightPoint && bHeight;
            } else {
                result = false;
            }
        } else if (dictx1 == FindDirect.RIGHT) {
            if (dictx2 == FindDirect.TOP) {
                // right top: find bottom
                boolean bHeight = (targetLine.getY1() - baseCell.getTop() > 10);
                result = bLeftPoint && bRightPoint && bHeight;
            } else if(dictx2 == FindDirect.BOTTOM) {
                // right bottom: find top
                boolean bHeight = (baseCell.getBottom() - targetLine.getY1() > 10);
                result = bLeftPoint && bRightPoint && bHeight;
            } else {
                result = false;
            }
        } else {
            result = false;
        }
        return result;
    }

    private Ruling addHencVirtualLine(FindDirect dictx1, FindDirect dictx2, Rectangle baseCell, Ruling leftLine,
                                      Ruling rightLine, Ruling targetLine) {
        Ruling newTargetLine = null;

        if (dictx1 == FindDirect.LEFT || dictx1 == FindDirect.RIGHT) {
            if (dictx2 == FindDirect.TOP) {
                if (!((FloatUtils.feq(targetLine.getY1(), leftLine.getY2(), 2.0) ||
                        (leftLine.getY2() - targetLine.getY1() >= 2)) &&
                        (FloatUtils.feq(targetLine.getY1(), rightLine.getY2(), 2.0) ||
                                (rightLine.getY2() - targetLine.getY1() >= 2)))) {
                    newTargetLine = new Ruling(baseCell.getBottom(), (float)leftLine.getX1(),
                            (float)(rightLine.getX1() - leftLine.getX1()), 0);
                }
            } else if (dictx2 == FindDirect.BOTTOM) {
                if (!(FloatUtils.feq(targetLine.getY1(), leftLine.getY1(), 2.0) ||
                        (targetLine.getY1() - leftLine.getY1() >= 2) &&
                                FloatUtils.feq(targetLine.getY1(), rightLine.getY1(), 2.0) ||
                        (targetLine.getY1() - rightLine.getY1() >= 2))) {
                    newTargetLine = new Ruling(baseCell.getBottom(), (float)leftLine.getX1(),
                            (float)(rightLine.getX1() - leftLine.getX1()), 0);
                }
            }
        }
        return newTargetLine;
    }

    // 根据线填充目标cell
    private void fillLeftRightTargetCell(FindDirect dictx1, FindDirect dictx2, Rectangle baseCell, Ruling leftLine,
                                         Ruling rightLine, Ruling targetLine, List<Rectangle> clipAeras) {

        float left = (float)leftLine.getX1();
        float width = (float)(rightLine.getX1() - leftLine.getX1());
        float top = 0, height = 0;
        Ruling virtualLine = addHencVirtualLine(dictx1, dictx2, baseCell, leftLine, rightLine, targetLine);
        if (virtualLine != null) {
            targetLine = virtualLine;
        }
        if (dictx1 == FindDirect.LEFT) {
            if (dictx2 == FindDirect.TOP) {
                // left top: find left cell
                top = baseCell.getTop();
                if (FloatUtils.feq(baseCell.getBottom(), targetLine.getY1(), 3.5)) {
                    height = baseCell.getBottom() - top;
                } else {
                    if ((FloatUtils.feq(targetLine.getY1(), leftLine.getY2(), 2.0) ||
                            (leftLine.getY2() - targetLine.getY1() >= 2.0)) &&
                            (FloatUtils.feq(targetLine.getY1(), rightLine.getY2(), 2.0)
                                    || (rightLine.getY2() - targetLine.getY1() >= 2.0))) {
                        height = (float)(targetLine.getY1() - top);
                    }

                }
            } else if(dictx2 == FindDirect.BOTTOM) {
                // left bottom: find left cell
                if (FloatUtils.feq(baseCell.getTop(), targetLine.getY1(), 3.5)) {
                    top = baseCell.getTop();
                } else {
                    if ((FloatUtils.feq(targetLine.getY1(), leftLine.getY1(), 2.0) ||
                            (targetLine.getY1() - leftLine.getY1() >= 2.0)) &&
                            (FloatUtils.feq(targetLine.getY1(), rightLine.getY1(), 2.0)
                                    || (targetLine.getY1() - rightLine.getY1() >= 2.0))) {
                        top = (float)targetLine.getY1();
                    }
                }
                if (top > 0) {
                    height = baseCell.getBottom() - top;
                }
            } else {
                width = 0;
            }
        } else if (dictx1 == FindDirect.RIGHT) {
            if (dictx2 == FindDirect.TOP) {
                // right top: right cell
                top = baseCell.getTop();
                if (FloatUtils.feq(baseCell.getBottom(), targetLine.getY1(), 3.5)) {
                    height = baseCell.getBottom() - top;
                } else {
                    if ((FloatUtils.feq(targetLine.getY1(), leftLine.getY2(), 2.0) ||
                            (leftLine.getY2() - targetLine.getY1() >= 2.0)) &&
                            (FloatUtils.feq(targetLine.getY1(), rightLine.getY2(), 2.0)
                                    || (rightLine.getY2() - targetLine.getY1() >= 2.0))) {
                        height = (float)(targetLine.getY1() - top);
                    }

                }
            } else if(dictx2 == FindDirect.BOTTOM) {
                // right bottom: right cell
                if (FloatUtils.feq(baseCell.getTop(), targetLine.getY1(), 3.5)) {
                    top = baseCell.getTop();
                } else {
                    if ((FloatUtils.feq(targetLine.getY1(), leftLine.getY1(), 2.0) ||
                            (targetLine.getY1() - leftLine.getY1() >= 2.0)) &&
                            (FloatUtils.feq(targetLine.getY1(), rightLine.getY1(), 2.0)
                                    || (targetLine.getY1() - rightLine.getY1() >= 2.0))) {
                        top = (float)targetLine.getY1();
                    }
                }
                if (top > 0) {
                    height = baseCell.getBottom() - top;
                }
            } else {
                width = 0;
            }
        } else {
            width = 0;
        }
        if (!((width == 0) || (height == 0))) {
            Rectangle rect = new Rectangle(left, top, width, height);
            Rectangle lastRect = clipAeras.get(clipAeras.size() - 1);
            if (!rect.nearlyContains(lastRect, 1)) {
                clipAeras.add(rect);
            }
        }
    }

    private void findLeftSubCell(FindDirect dict1, FindDirect dict2, Ruling baseLine, Rectangle baseCell, List<Rectangle> clipAeras, Page page, RegionLinesInfo rulingInfo) {

        List<Ruling> lineHenc = rulingInfo.getTableRegionAllHencLines();
        List<Ruling> candidateVlines = new ArrayList<>();
        List<Ruling> candidateHencLines = new ArrayList<>();
        if (dict2 == FindDirect.TOP) {
            candidateHencLines.addAll(lineHenc);
        } else if (dict2 == FindDirect.BOTTOM) {
            candidateHencLines.addAll(lineHenc);
            Collections.reverse(candidateHencLines);
        }
        // find left line
        findLeftRightVencLines(dict1, dict2, baseLine, page, baseCell, candidateVlines, rulingInfo);
        if (candidateVlines.size() > 0) {
            Collections.reverse(candidateVlines);
            Ruling rightLine = candidateVlines.get(0);
            candidateVlines.remove(rightLine);
            for (Ruling leftLine : candidateVlines) {
                // find bottom line
                for (Ruling targetLine : candidateHencLines) {
                    if (findLeftRightCellEndLine(dict1, dict2, baseCell, leftLine, rightLine, targetLine)) {

                        fillLeftRightTargetCell(dict1, dict2, baseCell, leftLine,
                                rightLine, targetLine, clipAeras);
                        break;
                    }
                }
                rightLine = leftLine;
            }
        }

    }

    // table水平方向被向左的cell分开，获取该cell
    private void checkLeftSeparateCell(Rectangle baseCell,  Rectangle regionArea, List<Rectangle> separateCells, Page page, RegionLinesInfo rulingInfo) {

        List<Ruling> lineVenc = rulingInfo.getTableRegionAllVencLines();
        List<Ruling> candiateVencRulings = new ArrayList<>();
        for (Ruling vline : lineVenc) {

            boolean bRightContain = FloatUtils.feq(vline.getX1(), baseCell.getLeft(), 2.0) ||
                    (baseCell.getLeft() - vline.getX1() > 10);
            boolean bLeftContain = FloatUtils.feq(vline.getX1(), regionArea.getLeft(), 2.0) ||
                    (vline.getX1() - regionArea.getLeft() > 10);
            boolean bTopBottomContain = (baseCell.getTop() - vline.getY1() > 10) &&
                    (vline.getY2() - vline.getY1() > 10) && (vline.getY2() - baseCell.getTop() > 10);
            if (bLeftContain && bRightContain && bTopBottomContain) {
                if (candiateVencRulings.size() > 0) {
                    Ruling lastLine = candiateVencRulings.get(candiateVencRulings.size() - 1);
                    if (vline.getX1() - lastLine.getX1() > 6) {
                        candiateVencRulings.add(vline);
                    }
                } else {
                    candiateVencRulings.add(vline);
                }
            }
            if (vline.getX1() - baseCell.getLeft() > 5) {
                break;
            }
        }

        List<Ruling> lineHenc = rulingInfo.getTableRegionAllHencLines();
        if (!candiateVencRulings.isEmpty()) {
            Collections.reverse(candiateVencRulings);
            Ruling baseVencLine = candiateVencRulings.get(0);
            List<Ruling> validLines = new ArrayList<>();
            for (int i = 1; i < candiateVencRulings.size(); i++) {
                Ruling candidateVencLine = candiateVencRulings.get(i);
                for (Ruling hline : lineHenc) {
                    boolean bLeftCross = FloatUtils.feq(candidateVencLine.getX1(), hline.getX1(), 2.0) ||
                            (candidateVencLine.getX1() - hline.getX1() >= 2.0);
                    boolean bRightCross = FloatUtils.feq(hline.getX2(), baseVencLine.getX1(), 2.0) ||
                            (hline.getX2() - baseVencLine.getX1() >= 2.0) ;

                    if (bLeftCross && bRightCross) {
                        if (hline.getY1() - baseCell.getTop() > 5) {
                            if (!validLines.isEmpty()) {
                                Ruling lastLine = validLines.get(validLines.size() - 1);
                                separateCells.add(new Rectangle((float)candidateVencLine.getX1(), (float)lastLine.getY1(),
                                        (float)(baseVencLine.getX1() - candidateVencLine.getX1()), (float)(hline.getY1() - lastLine.getY1())));
                                break;
                            }

                        } else {
                            if (FloatUtils.feq(hline.getY1(), baseCell.getTop(), 2.0)) {
                                return;
                            }
                            validLines.add(hline);
                        }
                    }
                }
                validLines.clear();
                baseVencLine = candidateVencLine;
            }
        }
    }

    private void findLeftCells(Rectangle baseCell, Rectangle regionArea, List<Rectangle> clipAeras, Page page, RegionLinesInfo rulingInfo) {

        List<Ruling> candiateHencRulings = new ArrayList<>();
        // find top line
        findLeftRightHencLines(FindDirect.LEFT, FindDirect.TOP, baseCell, candiateHencRulings, page, rulingInfo);

        if (candiateHencRulings.size() > 0) {
            Ruling topLine = candiateHencRulings.get(0);
            findLeftSubCell(FindDirect.LEFT, FindDirect.TOP, topLine, baseCell, clipAeras, page, rulingInfo);
        }

        Rectangle leftBaseCell;
        if (candiateHencRulings.size() == 0) {
            leftBaseCell = baseCell;
        } else {
            leftBaseCell = clipAeras.get(clipAeras.size() - 1);
        }
        if (!FloatUtils.feq(regionArea.getLeft(), leftBaseCell.getLeft(), 2.0)) {
            candiateHencRulings.clear();
            // find bottom line
            findLeftRightHencLines(FindDirect.LEFT, FindDirect.BOTTOM, leftBaseCell, candiateHencRulings, page, rulingInfo);
            if (candiateHencRulings.size() > 0) {
                Ruling bottomLine = candiateHencRulings.get(0);
                findLeftSubCell(FindDirect.LEFT, FindDirect.BOTTOM, bottomLine, leftBaseCell, clipAeras, page, rulingInfo);
            } else {
                List<Rectangle> spreateCells = new ArrayList<>();
                checkLeftSeparateCell(leftBaseCell, regionArea, spreateCells, page, rulingInfo);
                if (!spreateCells.isEmpty()) {
                    Rectangle leftRect = spreateCells.get(spreateCells.size() - 1);
                    if (!FloatUtils.feq(leftRect.getLeft(), regionArea.getLeft(), 2)) {
                        Rectangle newBaseCell = new Rectangle(leftRect.getLeft(), leftBaseCell.getTop(),
                                leftBaseCell.getRight() - leftRect.getLeft(), (float)leftBaseCell.getHeight());

                        candiateHencRulings.clear();
                        // find top line
                        findLeftRightHencLines(FindDirect.LEFT, FindDirect.TOP, newBaseCell, candiateHencRulings, page, rulingInfo);

                        if (candiateHencRulings.size() > 0) {
                            Ruling topLine = candiateHencRulings.get(0);
                            findLeftSubCell(FindDirect.LEFT, FindDirect.TOP, topLine, newBaseCell, clipAeras, page, rulingInfo);
                        }
                    }
                } else {
                    // 上下边线都没有，根据上一个单元格补充虚拟单元格
                    if (leftBaseCell != null || !leftBaseCell.isEmpty()) {
                        List<Ruling> allVlines = rulingInfo.getTableRegionAllVencLines().stream()
                                .filter(r -> (leftBaseCell.getLeft() - r.getX1() > 5 &&
                                        FloatUtils.feq(r.getX1(), regionArea.getLeft(), 3.5)))
                                .collect(Collectors.toList());
                        if ((allVlines != null || !allVlines.isEmpty()) && allVlines.size() == 1) {
                            clipAeras.add(new Rectangle(allVlines.get(0).getX1(), leftBaseCell.getTop(),
                                    leftBaseCell.getLeft() - allVlines.get(0).getX1(), leftBaseCell.getHeight()));
                        }
                    }
                }
            }
        }
    }

    private void findRightSubCell(FindDirect dict1, FindDirect dict2, Ruling baseLine, Rectangle baseCell, List<Rectangle> clipAeras, Page page, RegionLinesInfo rulingInfo) {

        List<Ruling> lineHenc = rulingInfo.getTableRegionAllHencLines();

        List<Ruling> candidateVlines = new ArrayList<>();
        List<Ruling> candidateHencLines = new ArrayList<>();
        if (dict2 == FindDirect.TOP) {
            candidateHencLines.addAll(lineHenc);
        } else if (dict2 == FindDirect.BOTTOM) {
            candidateHencLines.addAll(lineHenc);
            Collections.reverse(candidateHencLines);
        }
        // find right line
        findLeftRightVencLines(dict1, dict2, baseLine, page, baseCell, candidateVlines, rulingInfo);
        if (candidateVlines.size() > 0) {
            Collections.reverse(candidateVlines);
            Ruling leftLine = candidateVlines.get(0);
            candidateVlines.remove(leftLine);
            for (Ruling rightLine : candidateVlines) {
                // find bottom line
                for (Ruling targetLine : candidateHencLines) {
                    if (findLeftRightCellEndLine(dict1, dict2, baseCell, leftLine, rightLine, targetLine)) {

                        fillLeftRightTargetCell(dict1, dict2, baseCell, leftLine,
                                rightLine, targetLine, clipAeras);
                        break;
                    }
                }
                leftLine = rightLine;
            }
        }

    }

    // table水平方向被向右的cell分开，获取该cell
    private void checkRightSeparateCell(Rectangle baseCell,  Rectangle regionArea, List<Rectangle> separateCells, Page page, RegionLinesInfo rulingInfo) {
        List<Ruling> lineHenc = rulingInfo.getTableRegionAllHencLines();
        List<Ruling> lineVenc = rulingInfo.getTableRegionAllVencLines();
        List<Ruling> candiateVencRulings = new ArrayList<>();

        for (Ruling vline : lineVenc) {
            if (baseCell.getRight() - vline.getX1() > 5) {
                continue;
            }
            boolean bRightContain = FloatUtils.feq(vline.getX1(), regionArea.getRight(), 2.0) ||
                    (regionArea.getRight() - vline.getX1() > 10);
            boolean bLeftContain = FloatUtils.feq(vline.getX1(), baseCell.getRight(), 2.0) ||
                    (vline.getX1() - baseCell.getRight() > 10);
            boolean bTopBottomContain = (baseCell.getTop() - vline.getY1() > 10) &&
                    (vline.getY2() - vline.getY1() > 10) && (vline.getY2() - baseCell.getTop() > 10);
            if (bLeftContain && bRightContain && bTopBottomContain) {
                if (candiateVencRulings.size() > 0) {
                    Ruling lastLine = candiateVencRulings.get(candiateVencRulings.size() - 1);
                    if (vline.getX1() - lastLine.getX1() > 6) {
                        candiateVencRulings.add(vline);
                    }
                } else {
                    candiateVencRulings.add(vline);
                }
            }
        }
        if (!candiateVencRulings.isEmpty()) {
            Ruling baseVencLine = candiateVencRulings.get(0);
            List<Ruling> validLines = new ArrayList<>();
            for (int i = 1; i < candiateVencRulings.size(); i++) {
                Ruling candidateVencLine = candiateVencRulings.get(i);
                for (Ruling hline : lineHenc) {
                    boolean bLeftCross = FloatUtils.feq(baseVencLine.getX1(), hline.getX1(), 2.0) ||
                            (baseVencLine.getX1() - hline.getX1() >= 2.0);
                    boolean bRightCross = FloatUtils.feq(hline.getX2(), candidateVencLine.getX1(), 2.0) ||
                            (hline.getX2() - candidateVencLine.getX1() >= 2.0) ;

                    if (bLeftCross && bRightCross) {
                        if (hline.getY1() - baseCell.getTop() > 5) {
                            if (!validLines.isEmpty()) {
                                Ruling lastLine = validLines.get(validLines.size() - 1);
                                separateCells.add(new Rectangle((float)baseVencLine.getX1(), (float)lastLine.getY1(),
                                        (float)(candidateVencLine.getX1() - baseVencLine.getX1()), (float)(hline.getY1() - lastLine.getY1())));
                                break;
                            }

                        } else {
                            if (FloatUtils.feq(hline.getY1(), baseCell.getTop(), 2.0)) {
                                return;
                            }
                            validLines.add(hline);
                        }
                    }
                }
                validLines.clear();
                baseVencLine = candidateVencLine;
            }
        }
    }

    private void findRightCells(Rectangle baseCell, Rectangle regionArea, List<Rectangle> clipAeras, Page page, RegionLinesInfo rulingInfo) {

        List<Ruling> candiateHencRulings = new ArrayList<>();
        // find top line
        findLeftRightHencLines(FindDirect.RIGHT, FindDirect.TOP, baseCell, candiateHencRulings, page, rulingInfo);
        if (candiateHencRulings.size() > 0) {
            Ruling topLine = candiateHencRulings.get(0);
            findRightSubCell(FindDirect.RIGHT, FindDirect.TOP, topLine, baseCell, clipAeras, page, rulingInfo);
        }

        Rectangle rightBaseCell;
        if (candiateHencRulings.size() == 0) {
            rightBaseCell = baseCell;
        } else {
            rightBaseCell = clipAeras.get(clipAeras.size() - 1);
        }
        if (!FloatUtils.feq(regionArea.getRight(), rightBaseCell.getRight(), 2.0)) {
            candiateHencRulings.clear();
            // find bottom line
            findLeftRightHencLines(FindDirect.RIGHT, FindDirect.BOTTOM, rightBaseCell, candiateHencRulings, page, rulingInfo);
            if (candiateHencRulings.size() > 0) {
                Ruling bottomLine = candiateHencRulings.get(0);
                findRightSubCell(FindDirect.RIGHT, FindDirect.BOTTOM, bottomLine, rightBaseCell, clipAeras, page, rulingInfo);
            } else {
                List<Rectangle> spreateCells = new ArrayList<>();
                checkRightSeparateCell(rightBaseCell, regionArea, spreateCells, page, rulingInfo);
                if (!spreateCells.isEmpty()) {
                    Rectangle rightRect = spreateCells.get(spreateCells.size() - 1);
                    if (!FloatUtils.feq(rightRect.getRight(), regionArea.getRight(), 2.0)) {
                        Rectangle newBaseCell = new Rectangle(rightBaseCell.getLeft(), rightBaseCell.getTop(),
                                rightRect.getRight() - rightBaseCell.getLeft(), (float)rightBaseCell.getHeight());
                        candiateHencRulings.clear();
                        findLeftRightHencLines(FindDirect.RIGHT, FindDirect.TOP, newBaseCell, candiateHencRulings, page, rulingInfo);
                        if (candiateHencRulings.size() > 0) {
                            Ruling topLine = candiateHencRulings.get(0);
                            findRightSubCell(FindDirect.RIGHT, FindDirect.TOP, topLine, newBaseCell, clipAeras, page, rulingInfo);
                        }
                    }
                } else {
                    // 上下边线都没有，根据上一个单元格补充虚拟单元格
                    if (rightBaseCell != null || !rightBaseCell.isEmpty()) {
                        List<Ruling> allVlines = rulingInfo.getTableRegionAllVencLines().stream()
                                .filter(r -> (r.getX1() - rightBaseCell.getRight() > 5 &&
                                        FloatUtils.feq(r.getX1(), regionArea.getRight(), 3.5)))
                                .collect(Collectors.toList());
                        if ((allVlines != null || !allVlines.isEmpty()) && allVlines.size() == 1) {
                            clipAeras.add(new Rectangle(rightBaseCell.getRight(), rightBaseCell.getTop(),
                                    allVlines.get(0).getX1() - rightBaseCell.getRight(), rightBaseCell.getHeight()));
                        }
                    }
                }
            }
        }
    }

    // true:水平 false:垂直
    private void checkValidityOfCells(List<? extends Rectangle> cells, boolean direction) {

        if (!cells.isEmpty() && cells.size() > 1) {
            Rectangle baseCell;
            if (direction == true) {
                baseCell = cells.get(0);
                for (int i = 1; i < cells.size(); i++) {
                    Rectangle otherCell = cells.get(i);
                    if (!(FloatUtils.feq(baseCell.getRight(), otherCell.getLeft(), 3.0) ||
                            (otherCell.getLeft() - baseCell.getRight() >= 3.0))) {
                        if (baseCell.getArea() > otherCell.getArea()) {
                            baseCell.markDeleted();
                        } else {
                            otherCell.markDeleted();
                        }
                    }
                    baseCell = otherCell;
                }
            } else {
                baseCell = cells.get(0);
                for (int i = 1; i < cells.size(); i++) {
                    Rectangle otherCell = cells.get(i);
                    if (!(FloatUtils.feq(baseCell.getBottom(), otherCell.getTop(), 3.0) ||
                            (otherCell.getTop() - baseCell.getBottom() >= 3.0))) {
                        if (baseCell.getArea() > otherCell.getArea()) {
                            baseCell.markDeleted();
                        } else {
                            otherCell.markDeleted();
                        }
                    }
                    baseCell = otherCell;
                }
            }

            cells.removeIf(Rectangle::isDeleted);
        }
    }

    private void addHencMutiCells(Ruling leftLine, Ruling rightLine, Rectangle clipBase, Page page, List<Rectangle> clipAeras) {
        List<Ruling> lineHenc = page.getHorizontalRulings();

        for (Ruling bottomLine : lineHenc) {
            boolean bLeftBottomPoint = FloatUtils.feq(bottomLine.getX1(), leftLine.getX1(), 2.0) ||
                    (leftLine.getX1() - bottomLine.getX1() >= 2.0);
            boolean bRightBottomPoint = FloatUtils.feq(bottomLine.getX2(), rightLine.getX1(), 2.0) ||
                    (bottomLine.getX2() - rightLine.getX1() >= 2.0);
            boolean bHeight = (bottomLine.getY1() - clipBase.getTop() > 10);

            if (bLeftBottomPoint && bRightBottomPoint && bHeight) {
                float y;
                if (FloatUtils.feq(clipBase.getBottom(), bottomLine.getY1(), 3.5)) {
                    y = clipBase.getBottom();
                } else {
                    y = (float) bottomLine.getY1();
                }
                Rectangle rect = new Rectangle((float) leftLine.getX1(), clipBase.getTop(),
                        (float) (rightLine.getX1() - leftLine.getX1()), y - clipBase.getTop());
                fillSubCellToList(rect, clipAeras);
                break;
            }
        }
    }

    private void addHencSignalCell(Rectangle clipBase, Rectangle clipCandidate, Page page, List<Rectangle > clipAeras) {

        List<Ruling> lineHenc = page.getHorizontalRulings();

        for (Ruling bottomLine : lineHenc) {
            boolean bLeftBottomPoint = FloatUtils.feq(bottomLine.getX1(), clipBase.getRight(), 2.0) ||
                    (clipBase.getRight() - bottomLine.getX1() >= 2.0);
            boolean bRightBottomPoint = FloatUtils.feq(bottomLine.getX2(), clipCandidate.getLeft(), 2.0) ||
                    (bottomLine.getX2() - clipCandidate.getLeft() >= 2.0);
            boolean bHeight = (bottomLine.getY1() - clipBase.getTop() > 10);

            if (bLeftBottomPoint && bRightBottomPoint && bHeight) {
                float y;
                if (FloatUtils.feq(clipBase.getBottom(), bottomLine.getY1(), 3.0)) {
                    y = clipBase.getBottom();
                } else {
                    y = (float) bottomLine.getY1();
                }
                Rectangle rect = new Rectangle(clipBase.getRight(), clipBase.getTop(),
                        clipCandidate.getLeft() - clipBase.getRight(), y - clipBase.getTop());
                fillSubCellToList(rect, clipAeras);
                break;
            }
        }
    }

    //水平两个cell之间进行cell填充
    private void generateHencCellByLine(Rectangle clipBase, Rectangle areaRegion, Rectangle clipCandidate, List<Rectangle> clipAeras, Page page, RegionLinesInfo rulingInfo) {
        List<Ruling> lineHenc = rulingInfo.getTableRegionAllHencLines();
        List<Ruling> CandidateHencLines = new ArrayList<>();

        for (Ruling hLine : lineHenc) {
            // find henc line
            boolean bLeftPoint = FloatUtils.feq(hLine.getX1(), clipBase.getRight(), 2.0) ||
                    (clipBase.getRight() - hLine.getX1() >= 2.0);
            boolean bRightPoint = FloatUtils.feq(hLine.getX2(), clipCandidate.getLeft(), 2.0) ||
                    (hLine.getX2() - clipCandidate.getLeft() >= 2.0);
            boolean bHeight = FloatUtils.feq(hLine.getY1(), clipBase.getTop(), 3.5) &&
                    FloatUtils.feq(hLine.getY1(), clipCandidate.getTop(), 3.5);

            if (bLeftPoint && bRightPoint && bHeight) {
                CandidateHencLines.add(hLine);
                break;
            }
        }
        if (CandidateHencLines.size() == 0) {
            // 可能存在separate cell
            List<Rectangle> separateCells = new ArrayList<>();
            checkRightSeparateCell(clipBase, areaRegion, separateCells, page, rulingInfo);
            if (separateCells.isEmpty()) {
                Rectangle tmpTableRegion = new Rectangle(clipBase.getRight(), areaRegion.getTop(),
                        (clipCandidate.getLeft() - clipBase.getRight()), (float)areaRegion.getHeight());
                fillCellsByLines(page, tmpTableRegion, clipAeras, rulingInfo);
            }
            return;
        }

        List<Ruling> CandadateVencLines = new ArrayList<>();
        List<Ruling> lineVenc = rulingInfo.getTableRegionAllVencLines();
        // find venc line
        for (Ruling vLine : lineVenc) {
            if (vLine.getX1() < clipBase.getRight()) {
                continue;
            }
            boolean bLrContarin = (vLine.getX1() - clipBase.getRight() > 10) && (clipCandidate.getLeft() - vLine.getX1() > 10);
            boolean bTbContrain = (FloatUtils.feq(vLine.getY1(), clipBase.getTop(), 3.5) ||
                    (clipBase.getTop() - vLine.getY1() >= 3.5)) && (vLine.getY2() - clipBase.getTop() > 10);

            if (bLrContarin && bTbContrain) {
                CandadateVencLines.add(vLine);
            }
            if (FloatUtils.feq(vLine.getX1(), clipCandidate.getLeft(), 2.0)
                    || (vLine.getX1() - clipCandidate.getLeft() >= 2.0)) {
                break;
            }
        }
        //add cell
        if (CandadateVencLines.size() > 0) {
            Ruling leftLine = new Ruling(clipBase.getTop(), clipBase.getRight(), 0, (float)clipBase.getHeight());
            // find bottom line
            for (Ruling rightLine : CandadateVencLines) {
                addHencMutiCells(leftLine, rightLine, clipBase, page, clipAeras);
                leftLine = rightLine;
            }
            addHencMutiCells(leftLine, new Ruling(clipCandidate.getTop(), clipCandidate.getLeft(),0,
                    (float)clipCandidate.getHeight()), clipBase, page, clipAeras);
        } else {
            if (clipCandidate.getLeft() - clipBase.getRight() < 10) {
                // 一个单元格中间有多余的线，将单元格一份为二，重置单元格
                clipBase.setRect(clipBase.getLeft(), clipBase.getTop(), clipCandidate.getRight() - clipBase.getLeft(),
                        clipBase.getHeight());
            } else {
                addHencSignalCell(clipBase, clipCandidate, page, clipAeras);
            }
        }
    }

    private void fillHencCell(List<Rectangle> clipCandidate, Rectangle areaRegion, List<Rectangle> clipAeras, Page page, RegionLinesInfo rulingInfo) {
        if (clipCandidate.isEmpty()) {
            return;
        }

        if (clipCandidate.size() == 1) {
            // 一行只有一个单元格
            if (clipCandidate.get(0).getLeft() - areaRegion.getLeft() > 10) {
                findLeftCells(clipCandidate.get(0), areaRegion, clipAeras, page, rulingInfo);
            }
            if (areaRegion.getRight() - clipCandidate.get(0).getRight() > 10) {
                findRightCells(clipCandidate.get(0), areaRegion, clipAeras, page, rulingInfo);
            }
            return;
        }
        clipCandidate.sort(Comparator.comparing(Rectangle::getLeft));
        checkValidityOfCells(clipCandidate, true);
        if (clipCandidate.isEmpty()) {
            return;
        }
        Rectangle baseCell = clipCandidate.get(0);

        if (baseCell.getLeft() - areaRegion.getLeft() > 10) {
            findLeftCells(baseCell, areaRegion, clipAeras, page, rulingInfo);
        }
        // right fill cell
        for(int i = 1; i < clipCandidate.size(); i++) {
            Rectangle candidateCell = clipCandidate.get(i);
            boolean bBaseRighttoLeft = FloatUtils.feq(baseCell.getRight(),candidateCell.getLeft(),3.5);
            if (bBaseRighttoLeft) {
                baseCell = candidateCell;
                continue;
            } else {
                // 根据两个单元格之间的线补充cell
                generateHencCellByLine(baseCell, areaRegion, candidateCell, clipAeras, page, rulingInfo);
            }
            baseCell = candidateCell;
        }
        if (areaRegion.getRight() - baseCell.getRight() > 10) {
            findRightCells(baseCell, areaRegion, clipAeras, page, rulingInfo);
        }
    }

    private void processHencSubGroupCells(List<Rectangle> clipAeras, List<Rectangle> candidateClipAeras, Rectangle areaRegion, Page page, RegionLinesInfo rulingInfo) {
        if (!candidateClipAeras.isEmpty()) {
            candidateClipAeras.sort(Comparator.comparing(Rectangle::getTop));
            List<Rectangle> clipCandidate = new ArrayList<>();
            Rectangle base = candidateClipAeras.get(0);
            clipCandidate.add(base);
            for (int i = 1; i < candidateClipAeras.size(); i++) {
                Rectangle candidate = candidateClipAeras.get(i);
                if (FloatUtils.feq(base.getTop(), candidate.getTop(), 1.5)) {
                    clipCandidate.add(candidate);
                } else {
                    base = candidate;
                    fillHencCell(clipCandidate, areaRegion, clipAeras, page, rulingInfo);
                    clipCandidate.clear();
                    clipCandidate.add(candidate);
                }
            }
            fillHencCell(clipCandidate, areaRegion, clipAeras, page, rulingInfo);
        }
    }

    private List<Rectangle> processRepeatCells(boolean bHencDict, List<Rectangle> cells, List<Rectangle> newCells) {
        if (cells.size() <= 1) {
            return new ArrayList<>();
        }
        if (bHencDict) {
            cells.sort(Comparator.comparing(Rectangle::getLeft));
        } else {
            cells.sort(Comparator.comparing(Rectangle::getTop));
        }
        List<Rectangle> removeCells = new ArrayList<>();
        List<Rectangle> tmpCells = new ArrayList<>();
        Rectangle baseCell = cells.get(0);
        tmpCells.add(baseCell);
        for (int i = 1; i < cells.size(); i++) {
            Rectangle otherCell = cells.get(i);
            if (otherCell.overlapRatio(baseCell) > 0.9) {
                tmpCells.add(otherCell);
            } else {
                if (tmpCells.size() == 1) {
                    if (baseCell.contains(otherCell)) {
                        removeCells.add(baseCell);
                        baseCell = otherCell;
                    } else if (otherCell.contains(baseCell)) {
                        removeCells.add(otherCell);
                    } else {
                        baseCell = otherCell;
                    }
                    tmpCells.clear();
                    tmpCells.add(baseCell);
                }

                if (tmpCells.size() > 1) {
                    if (bHencDict) {
                        double left = (tmpCells.stream().min(Comparator.comparing(Rectangle::getLeft)).get().getLeft()
                                + tmpCells.stream().max(Comparator.comparing(Rectangle::getLeft)).get().getLeft()) / 2;
                        double right = (tmpCells.stream().min(Comparator.comparing(Rectangle::getRight)).get().getRight()
                                + tmpCells.stream().max(Comparator.comparing(Rectangle::getRight)).get().getRight()) / 2;

                        Rectangle newBaseCell = new Rectangle(left, baseCell.getTop(), right - left, baseCell.getHeight());
                        removeCells.addAll(new ArrayList<>(tmpCells));
                        tmpCells.clear();
                        baseCell = otherCell;
                        tmpCells.add(baseCell);
                        newCells.add(newBaseCell);
                    } else {
                        double top = (tmpCells.stream().min(Comparator.comparing(Rectangle::getTop)).get().getTop()
                                + tmpCells.stream().max(Comparator.comparing(Rectangle::getTop)).get().getTop()) / 2;
                        double bottom = (tmpCells.stream().min(Comparator.comparing(Rectangle::getBottom)).get().getBottom()
                                + tmpCells.stream().max(Comparator.comparing(Rectangle::getBottom)).get().getBottom()) / 2;

                        Rectangle newBaseCell = new Rectangle(baseCell.getLeft(), top, baseCell.getWidth(), bottom - top);
                        removeCells.addAll(new ArrayList<>(tmpCells));
                        tmpCells.clear();
                        baseCell = otherCell;
                        tmpCells.add(baseCell);
                        newCells.add(newBaseCell);
                    }
                }
            }
        }

        getNewGenerateCells(baseCell, tmpCells, removeCells, newCells,  bHencDict);

        return removeCells;
    }

    private void removeRepeatCells(Page page, List<Rectangle> cells) {
        if (cells.isEmpty()) {
            return;
        }
        List<Rectangle> sameCells = new ArrayList<>(cells.size());
        List<Rectangle> repeateCells = new ArrayList<>();
        List<Rectangle> newCells = new ArrayList<>();
        cells.sort(Comparator.comparing(Rectangle::getTop));
        Rectangle cellBase = cells.get(0);
        sameCells.add(cellBase);
        for (int i = 1; i < cells.size(); i++) {
            Rectangle otherCell = cells.get(i);
            if (FloatUtils.feq(cellBase.getTop(), otherCell.getTop(), 2.5)) {
                sameCells.add(otherCell);
            } else {
                cellBase = otherCell;
                repeateCells.addAll(processRepeatCells(true, sameCells, newCells));
                sameCells.clear();
                sameCells.add(otherCell);
            }
        }
        repeateCells.addAll(processRepeatCells(true, sameCells, newCells));
        cells.removeAll(repeateCells);
        cells.addAll(newCells);

        if (cells.isEmpty()) {
            return;
        }
        repeateCells.clear();
        newCells.clear();
        //cells.removeIf(Rectangle::isDeleted);
        cells.sort(Comparator.comparing(Rectangle::getLeft));
        sameCells.clear();
        cellBase = cells.get(0);
        sameCells.add(cellBase);
        for (int i = 1; i < cells.size(); i++) {
            Rectangle otherCell = cells.get(i);
            if (FloatUtils.feq(cellBase.getLeft(), otherCell.getLeft(), 2.5)) {
                sameCells.add(otherCell);
            } else {
                cellBase = otherCell;
                repeateCells.addAll(processRepeatCells(false, sameCells, newCells));
                sameCells.clear();
                sameCells.add(otherCell);
            }
        }
        repeateCells.addAll(processRepeatCells(false, sameCells, newCells));
        cells.removeAll(repeateCells);
        cells.addAll(newCells);
        //cells.removeIf(Rectangle::isDeleted);
    }

    // 垂直方向找临近cell的left直线
    private void findTopBottomVencLines(FindDirect dictx, Rectangle baseCell, Rectangle area, List<Ruling> candiateVencLines, Page page, RegionLinesInfo rulingInfo) {
        List<Ruling> lineVenc = rulingInfo.getTableRegionAllVencLines();
        if (lineVenc.isEmpty()) {
            return;
        }
        boolean bContainLeftLine;
        for (Ruling vline : lineVenc) {
            if (dictx == FindDirect.TOP) {
                bContainLeftLine = FloatUtils.feq(vline.getX1(), baseCell.getLeft(), 2.0) &&
                        (baseCell.getTop() - vline.getY1() > 10) &&
                        (FloatUtils.feq(vline.getY2(), baseCell.getTop(), 3.5) || (vline.getY2() - baseCell.getTop() >= 3.5));

            } else if (dictx == FindDirect.BOTTOM) {

                bContainLeftLine = FloatUtils.feq(vline.getX1(), baseCell.getLeft(), 2.0) &&
                        (vline.getY2() - baseCell.getBottom() > 10) &&
                        (FloatUtils.feq(vline.getY1(), baseCell.getBottom(), 3.5) || (baseCell.getBottom() -vline.getY1() >= 3.5));
            } else {
                bContainLeftLine = false;
            }
            if (bContainLeftLine) {
                if (FloatUtils.feq(vline.getX1(), area.getLeft(), 2.0)) {
                    candiateVencLines.add(new Ruling(area.getTop(), area.getLeft(), 0, (float)area.getHeight()));
                } else {
                    candiateVencLines.add(vline);
                }
                break;
            }
        }
    }

    // 垂直方向找临近cell的top/bottom直线
    private void findTopBottomHencLines(FindDirect dicty, Rectangle baseCell, Ruling baseLine, List<Ruling> candiateHencLines, Page page, RegionLinesInfo rulingInfo) {

        List<Ruling> lineHenc = rulingInfo.getTableRegionAllHencLines();
        if (lineHenc == null || lineHenc.isEmpty()) {
            return;
        }

        boolean result;
        for (Ruling hline : lineHenc) {
            boolean bWidthContain = (hline.getX2() - baseCell.getLeft() > 10);
            if (dicty == FindDirect.TOP) {
                boolean bContainLeftTop = (FloatUtils.feq(hline.getX1(), baseCell.getLeft(), 2.0) ||
                        ((baseCell.getLeft() - hline.getX1()) >= 2.0)) && (baseCell.getTop() - hline.getY1() > 10);
                boolean bContainTopBorder = FloatUtils.feq(hline.getY1(), baseLine.getY1(), 2.0) ||
                        (hline.getY1() - baseLine.getY1() >= 2.0);
                result = bContainLeftTop && bContainTopBorder && bWidthContain;

            } else if (dicty == FindDirect.BOTTOM) {

                if (hline.getY1() < baseCell.getBottom()) {
                    continue;
                }
                boolean bContainLeftBottom = (FloatUtils.feq(hline.getX1(), baseCell.getLeft(), 2.0) ||
                        ((baseCell.getLeft() - hline.getX1()) >= 2.0)) && (hline.getY1() - baseCell.getBottom() > 10);
                boolean bContainBottomBorder = FloatUtils.feq(hline.getY1(), baseLine.getY2(), 2.0) ||
                        (baseLine.getY2() - hline.getY1()>= 2.0);
                result = bContainLeftBottom && bContainBottomBorder && bWidthContain;

            } else {
                result = false;
            }
            if (result) {
                if (candiateHencLines.size() > 0) {
                    Ruling lastLine = candiateHencLines.get(candiateHencLines.size() - 1);
                    if (hline.getY1() - lastLine.getY1() > 6) {
                        candiateHencLines.add(hline);
                    }
                } else {
                    candiateHencLines.add(hline);
                }
            }
            if (dicty == FindDirect.TOP) {
                if (FloatUtils.feq(hline.getY1(), baseCell.getTop(), 2.0)
                        || (hline.getY1() - baseCell.getTop() >= 2.0)) {
                    break;
                }
            }
        }

    }

    // 垂直方向找临近cell的right直线
    private void findRightLineFillCell(Rectangle baseCell, Ruling topLine, Ruling bottomLine, List<Rectangle> clipAeras, Page page, RegionLinesInfo rulingInfo) {

        List<Ruling> lineVenc = rulingInfo.getTableRegionAllVencLines();
        if (lineVenc == null || lineVenc.isEmpty()) {
            return;
        }
        for (Ruling rightLine : lineVenc) {
            // right line
            if (rightLine.getX1() <= baseCell.getLeft()) {
                continue;
            }
            boolean bRightBottomPoint = FloatUtils.feq(bottomLine.getY1(), rightLine.getY2(), 2.0) ||
                    (rightLine.getY2() - bottomLine.getY1() >= 2.0);
            boolean bRightTopPoint = FloatUtils.feq(topLine.getY1(), rightLine.getY1(), 2.0) ||
                    (topLine.getY1() - rightLine.getY1() >= 2.0);
            boolean bWidth = (rightLine.getX1() - baseCell.getLeft() > 10);

            if (bRightTopPoint && bRightBottomPoint && bWidth) {
                float x;
                if (FloatUtils.feq(rightLine.getX1(), baseCell.getRight(), 3)) {
                    x = baseCell.getRight();
                } else {
                    x = (float) rightLine.getX1();
                }
                Rectangle rect = new Rectangle(baseCell.getLeft(), (float) topLine.getY1(),
                        x - baseCell.getLeft(), (float) (bottomLine.getY1() - topLine.getY1()));
                fillSubCellToList(rect, clipAeras);
                break;
            }
        }
    }

    // table垂直方向被向上的cell分开，获取该cell
    private void checkUpSpreateCell(Rectangle baseCell,  Rectangle regionArea, List<Rectangle> spreateCells, Page page, RegionLinesInfo rulingInfo) {
        List<Ruling> lineHenc = rulingInfo.getTableRegionAllHencLines();
        List<Ruling> lineVenc = rulingInfo.getTableRegionAllVencLines();
        List<Ruling> candiateHencRulings = new ArrayList<>();

        for (Ruling hline : lineHenc) {
            boolean bBottomContain = FloatUtils.feq(hline.getY1(), baseCell.getTop(), 2.0) ||
                    (baseCell.getTop() - hline.getY1() > 10);
            boolean bTopContain = FloatUtils.feq(hline.getY1(), regionArea.getTop(), 2.0) ||
                    (hline.getY1() - regionArea.getTop() > 10);
            boolean bLeftRightContain = (baseCell.getLeft() - hline.getX1() > 10) &&
                    (hline.getX2() - hline.getX1() > 10) && (hline.getX2() - baseCell.getLeft() > 10);
            if (bBottomContain && bTopContain && bLeftRightContain) {
                if (candiateHencRulings.size() > 0) {
                    Ruling lastLine = candiateHencRulings.get(candiateHencRulings.size() - 1);
                    if (hline.getY1() - lastLine.getY1() > 6) {
                        candiateHencRulings.add(hline);
                    }
                } else {
                    candiateHencRulings.add(hline);
                }
            }
            if (hline.getY1() - baseCell.getTop() > 5) {
                break;
            }
        }
        if (!candiateHencRulings.isEmpty()) {
            Collections.reverse(candiateHencRulings);
            Ruling baseHencLine = candiateHencRulings.get(0);
            List<Ruling> validLines = new ArrayList<>();
            for (int i = 1; i < candiateHencRulings.size(); i++) {
                Ruling candidateHencLine = candiateHencRulings.get(i);
                for (Ruling vline : lineVenc) {
                    boolean bTopCross = FloatUtils.feq(candidateHencLine.getY1(), vline.getY1(), 2.0) ||
                            (candidateHencLine.getY1() - vline.getY1() >= 2.0);
                    boolean bBottomCross = FloatUtils.feq(vline.getY2(), baseHencLine.getY1(), 2.0) ||
                            (vline.getY2() - baseHencLine.getY1() >= 2.0) ;
                    if (bTopCross && bBottomCross) {
                        if (vline.getX1() - baseCell.getLeft() > 5) {
                            if (!validLines.isEmpty()) {
                                Ruling lastLine = validLines.get(validLines.size() - 1);
                                spreateCells.add(new Rectangle((float)lastLine.getX1(), (float)candidateHencLine.getY1(),
                                        (float)(vline.getX1() - lastLine.getX1()), (float)(baseHencLine.getY1() - candidateHencLine.getY1())));
                                break;
                            }
                        } else {
                            if (FloatUtils.feq(vline.getX1(), baseCell.getLeft(), 2.0)) {
                                return;
                            }
                            validLines.add(vline);
                        }
                    }
                }
                validLines.clear();
                baseHencLine = candidateHencLine;
            }

        }
    }

    private void getNewGenerateCells(Rectangle baseCell, List<Rectangle> candidateCells,
                                     List<Rectangle> removeCells, List<Rectangle> newCells, boolean bHencDict) {

        if (candidateCells.size() > 1) {
            if (bHencDict) {
                double left = (candidateCells.stream().min(Comparator.comparing(Rectangle::getLeft)).get().getLeft()
                        + candidateCells.stream().max(Comparator.comparing(Rectangle::getLeft)).get().getLeft()) / 2;
                double right = (candidateCells.stream().min(Comparator.comparing(Rectangle::getRight)).get().getRight()
                        + candidateCells.stream().max(Comparator.comparing(Rectangle::getRight)).get().getRight()) / 2;

                Rectangle newBaseCell = new Rectangle(left, baseCell.getTop(), right - left, baseCell.getHeight());
                removeCells.addAll(new ArrayList<>(candidateCells));
                newCells.add(newBaseCell);
            } else {
                double top = (candidateCells.stream().min(Comparator.comparing(Rectangle::getTop)).get().getTop()
                        + candidateCells.stream().max(Comparator.comparing(Rectangle::getTop)).get().getTop()) / 2;
                double bottom = (candidateCells.stream().min(Comparator.comparing(Rectangle::getBottom)).get().getBottom()
                        + candidateCells.stream().max(Comparator.comparing(Rectangle::getBottom)).get().getBottom()) / 2;

                Rectangle newBaseCell = new Rectangle(baseCell.getLeft(), top, baseCell.getWidth(), bottom - top);
                removeCells.addAll(new ArrayList<>(candidateCells));
                newCells.add(newBaseCell);
            }
        }
    }

    
    private void findUpCells(Rectangle baseCell, Rectangle area, List<Rectangle> clipAeras, Page page, RegionLinesInfo rulingInfo) {

        List<Ruling> candiateVencRulings = new ArrayList<>();
        // find left line
        findTopBottomVencLines(FindDirect.TOP, baseCell, area, candiateVencRulings, page, rulingInfo);

        if (candiateVencRulings.size() > 0) {
            // left line
            Ruling leftLine = candiateVencRulings.get(0);
            List<Ruling> candidateHlines = new ArrayList<>();
            // find top line
            findTopBottomHencLines(FindDirect.TOP, baseCell, leftLine, candidateHlines, page, rulingInfo);

            if (candidateHlines.size() > 0) {
                Collections.reverse(candidateHlines);
                // bottom line
                Ruling bottomLine = new Ruling(baseCell.getTop(), baseCell.getLeft(), (float)baseCell.getWidth(), 0);
                for (Ruling topLine : candidateHlines) {
                    // find right line and add cell
                    findRightLineFillCell(baseCell, topLine, bottomLine, clipAeras, page, rulingInfo);
                    bottomLine = topLine;
                }
            }
        } else {
            List<Rectangle> spreateCells = new ArrayList<>();
            checkUpSpreateCell(baseCell, area, spreateCells, page, rulingInfo);
            if (!spreateCells.isEmpty()) {
                Rectangle topRect = spreateCells.get(spreateCells.size() - 1);
                Rectangle newBaseCell = new Rectangle(baseCell.getLeft(), topRect.getTop(),
                        (float)baseCell.getWidth(), baseCell.getBottom() - topRect.getTop());
                List<Ruling> candidateVencLines = new ArrayList<>();
                // find left line
                findTopBottomVencLines(FindDirect.TOP, newBaseCell, area, candidateVencLines, page, rulingInfo);

                if (!candidateVencLines.isEmpty()) {
                    Ruling leftLine = candidateVencLines.get(0);
                    List<Ruling> candidateHlines = new ArrayList<>();
                    // find top line
                    findTopBottomHencLines(FindDirect.TOP, newBaseCell, leftLine, candidateHlines, page, rulingInfo);
                    if (candidateHlines.size() > 0) {
                        Collections.reverse(candidateHlines);
                        // bottom line
                        Ruling bottomLine = new Ruling(newBaseCell.getTop(), area.getLeft(), (float)area.getWidth(), 0);
                        for (Ruling topLine : candidateHlines) {
                            // find right line and add cell
                            findRightLineFillCell(newBaseCell, topLine, bottomLine, clipAeras, page, rulingInfo);
                            bottomLine = topLine;
                        }
                    }
                }
            }
        }
    }

    // table垂直方向被向下的cell分开，获取该cell
    private void checkDownSpreateCell(Rectangle baseCell,  Rectangle regionArea, List<Rectangle> spreateCells, Page page, RegionLinesInfo rulingInfo) {
        List<Ruling> lineHenc = rulingInfo.getTableRegionAllHencLines();
        List<Ruling> lineVenc = rulingInfo.getTableRegionAllVencLines();
        List<Ruling> candiateHencRulings = new ArrayList<>();

        for (Ruling hline : lineHenc) {
            if (baseCell.getBottom() - hline.getY1() > 5) {
                continue;
            }
            boolean bBottomContain = FloatUtils.feq(hline.getY1(), regionArea.getBottom(), 2.0) ||
                    (regionArea.getBottom() - hline.getY1() > 10);
            boolean bTopContain = FloatUtils.feq(hline.getY1(), baseCell.getBottom(), 2.0) ||
                    (hline.getY1() - baseCell.getBottom() > 10);
            boolean bLeftRightContain = (baseCell.getLeft() - hline.getX1() > 10) &&
                    (hline.getX2() - hline.getX1() > 10) && (hline.getX2() - baseCell.getLeft() > 10);
            if (bBottomContain && bTopContain && bLeftRightContain) {
                if (candiateHencRulings.size() > 0) {
                    Ruling lastLine = candiateHencRulings.get(candiateHencRulings.size() - 1);
                    if (hline.getY1() - lastLine.getY1() > 6) {
                        candiateHencRulings.add(hline);
                    }
                } else {
                    candiateHencRulings.add(hline);
                }
            }
            if (FloatUtils.feq(hline.getY1(), regionArea.getBottom(), 2)) {
                break;
            }
        }
        if (!candiateHencRulings.isEmpty()) {
            Ruling baseHencLine = candiateHencRulings.get(0);
            List<Ruling> validLines = new ArrayList<>();
            for (int i = 1; i < candiateHencRulings.size(); i++) {
                Ruling candidateHencLine = candiateHencRulings.get(i);
                for (Ruling vline : lineVenc) {
                    boolean bTopCross = FloatUtils.feq(baseHencLine.getY1(), vline.getY1(), 2.0) ||
                            (baseHencLine.getY1() - vline.getY1() >= 2.0);
                    boolean bBottomCross = FloatUtils.feq(vline.getY2(), candidateHencLine.getY1(), 2.0) ||
                            (vline.getY2() - candidateHencLine.getY1() >= 2.0) ;
                    if (bTopCross && bBottomCross) {
                        if (vline.getX1() - baseCell.getLeft() > 5) {
                            if (!validLines.isEmpty()) {
                                Ruling lastLine = validLines.get(validLines.size() - 1);
                                spreateCells.add(new Rectangle((float)lastLine.getX1(), (float)baseHencLine.getY1(),
                                        (float)(vline.getX1() - lastLine.getX1()), (float)(candidateHencLine.getY1() - baseHencLine.getY1())));
                                break;
                            }
                        } else {
                            if (FloatUtils.feq(vline.getX1(), baseCell.getLeft(), 2.0)) {
                                return;
                            }
                            validLines.add(vline);
                        }
                    }
                }
                validLines.clear();
                baseHencLine = candidateHencLine;
            }
        }
    }

    private void findDownCells(Rectangle baseCell, Rectangle area, List<Rectangle> clipAeras, Page page, RegionLinesInfo rulingInfo) {

        List<Ruling> candidateVencRulings = new ArrayList<>();
        // find left line
        findTopBottomVencLines(FindDirect.BOTTOM, baseCell, area, candidateVencRulings, page, rulingInfo);
        if (candidateVencRulings.size() > 0) {
            // left line
            Ruling leftLine = candidateVencRulings.get(0);
            List<Ruling> candidateHlines = new ArrayList<>();
            // find bottom line
            findTopBottomHencLines(FindDirect.BOTTOM, baseCell, leftLine, candidateHlines, page, rulingInfo);
            if (candidateHlines.size() > 0) {
                // top line
                Ruling topLine = new Ruling(baseCell.getBottom(), baseCell.getLeft(), (float)baseCell.getWidth(), 0);
                for (Ruling bottomLine : candidateHlines) {
                    // find right line and add cell
                    findRightLineFillCell(baseCell, topLine, bottomLine, clipAeras, page, rulingInfo);
                    topLine = bottomLine;
                }
            }
        } else {
            List<Rectangle> spreateCells = new ArrayList<>();
            checkDownSpreateCell(baseCell, area, spreateCells, page, rulingInfo);
            if (!spreateCells.isEmpty()) {
                Rectangle topRect = spreateCells.get(0);
                Rectangle newBaseCell = new Rectangle(baseCell.getLeft(), baseCell.getTop(),
                        (float)baseCell.getWidth(), (float)(baseCell.getHeight() + topRect.getHeight()));
                candidateVencRulings.clear();
                // find left line
                findTopBottomVencLines(FindDirect.BOTTOM, newBaseCell, area, candidateVencRulings, page, rulingInfo);
                if (!candidateVencRulings.isEmpty()) {
                    Ruling leftLine = candidateVencRulings.get(0);
                    List<Ruling> candidateHlines = new ArrayList<>();
                    // find bottom line
                    findTopBottomHencLines(FindDirect.BOTTOM, newBaseCell, leftLine, candidateHlines, page, rulingInfo);
                    if (candidateHlines.size() > 0) {
                        // top line
                        Ruling topLine = new Ruling(newBaseCell.getBottom(), area.getLeft(), (float)area.getWidth(), 0);
                        for (Ruling bottomLine : candidateHlines) {
                            // find right line and add cell
                            findRightLineFillCell(newBaseCell, topLine, bottomLine, clipAeras, page, rulingInfo);
                            topLine = bottomLine;
                        }
                    }
                }
            }
        }
    }


    private void addVencMutiCells(Ruling leftLine, Ruling topLine, Ruling bottomLine, Rectangle clipBase, Page page, List<Rectangle> clipAeras, RegionLinesInfo rulingInfo) {

        List<Ruling> lineVenc = rulingInfo.getTableRegionAllVencLines();

        for (Ruling rightLine : lineVenc) {
            boolean bRightTopPoint = FloatUtils.feq(rightLine.getY1(), topLine.getY1(), 3.5) ||
                    (topLine.getY1() - rightLine.getY1() >= 3.5);
            boolean bRightBottomPoint = FloatUtils.feq(bottomLine.getY1(), rightLine.getY2(), 3.5) ||
                    (rightLine.getY2() - bottomLine.getY1() >= 3.5);
            boolean bWidth = (rightLine.getX1() - leftLine.getX1() > 10);
            if (bRightTopPoint && bRightBottomPoint && bWidth) {
                float x;
                if (FloatUtils.feq(clipBase.getRight(), rightLine.getX1(), 3)) {
                    x = clipBase.getRight();
                } else {
                    x = (float)rightLine.getX1();
                }
                Rectangle rect = new Rectangle((float) leftLine.getX1(), (float) topLine.getY1(),
                        (float) (x - leftLine.getX1()), (float) (bottomLine.getY1() - topLine.getY1()));
                fillSubCellToList(rect, clipAeras);
                break;
            }
        }
    }

    private void addVencSignalCell(Ruling leftLine, Rectangle clipBase, Rectangle clipCandidate, Page page, List<Rectangle > clipAeras, RegionLinesInfo rulingInfo) {

        List<Ruling> lineVenc = rulingInfo.getTableRegionAllVencLines();
        for (Ruling rightLine : lineVenc) {
            boolean bRightTopPoint = FloatUtils.feq(rightLine.getY1(), clipBase.getBottom(), 3.5) ||
                    (clipBase.getBottom() - rightLine.getY1() >= 3.5);
            boolean bRightBottomPoint = FloatUtils.feq(clipCandidate.getTop(), rightLine.getY2(), 3.5) ||
                    (rightLine.getY2() - clipCandidate.getTop() >= 3.5);
            boolean bWidth = (rightLine.getX1() - leftLine.getX1() > 10);
            if (bRightTopPoint && bRightBottomPoint && bWidth) {
                float rightX;
                if (FloatUtils.feq(clipBase.getRight(), rightLine.getX1(), 3)) {
                    rightX = clipBase.getRight();
                } else {
                    rightX = rightLine.x1;
                }
                Rectangle rect = new Rectangle((float) leftLine.getX1(), clipBase.getBottom(),
                        (float) (rightX - leftLine.getX1()), clipCandidate.getTop() - clipBase.getBottom());
                fillSubCellToList(rect, clipAeras);
                break;
            }
        }
    }

    // 垂直两个cell之间进行cell填充
    private void generateVencCellByLine(Rectangle clipBase, Rectangle area, Rectangle clipCandidate, List<Rectangle> clipAeras, Page page, RegionLinesInfo rulingInfo) {
        List<Ruling> lineVenc = rulingInfo.getTableRegionAllVencLines();
        List<Ruling> lineHenc = rulingInfo.getTableRegionAllHencLines();

        if (lineHenc != null) {
            double avgWidth = page.getText(area).parallelStream().mapToDouble(TextElement::getTextWidth).average()
                    .orElse(Page.DEFAULT_MIN_CHAR_WIDTH);

            List<Ruling> centerLines = lineHenc.stream().filter(r -> (r.getY1() >= clipBase.getBottom() && r.getY1() <= clipCandidate.getTop() &&
                    (r.getX1() < clipBase.getLeft() || FloatUtils.feq(r.getX1(), clipBase.getLeft(), 2.0)) && r.getWidth() >= avgWidth))
                    .collect(Collectors.toList());
            if (centerLines.size() == 1) {
                clipBase.setRect(clipBase.getLeft(), clipBase.getTop(), clipBase.getWidth(),
                        (centerLines.get(0).getY1() - clipBase.getTop()));
                clipCandidate.setRect(clipBase.getLeft(), centerLines.get(0).getY1(),
                        clipCandidate.getWidth(), (clipCandidate.getBottom() - centerLines.get(0).getY1()));
                return;
            }
        }

        Ruling leftLine = null;
        for (Ruling vLine : lineVenc) {
            // find venc line
            boolean bTopPoint = FloatUtils.feq(vLine.getY1(), clipBase.getBottom(), 2.5) ||
                    (clipBase.getBottom() - vLine.getY1() >= 2.5);
            boolean bBottomPoint = FloatUtils.feq(vLine.getY2(), clipCandidate.getTop(), 2.5) ||
                    (vLine.getY2() - clipCandidate.getTop() >= 2.5);
            boolean bWidth = FloatUtils.feq(vLine.getX1(), clipBase.getLeft(), 2.0) &&
                    FloatUtils.feq(vLine.getX1(), clipCandidate.getLeft(), 2.0);

            if (bTopPoint && bBottomPoint && bWidth) {
                leftLine = vLine;
                break;
            }
        }
        if (leftLine == null) {
            // 可能存在separate cell
            List<Rectangle> spreateCells = new ArrayList<>();
            checkDownSpreateCell(clipBase, area, spreateCells, page, rulingInfo);
            if (!spreateCells.isEmpty()) {

                List<Ruling> candidateVencRulings = new ArrayList<>();
                Rectangle topRect = spreateCells.get(spreateCells.size() - 1);
                Rectangle newBaseCell = new Rectangle(clipBase.getLeft(), clipBase.getTop(),
                        (float)clipBase.getWidth(), topRect.getBottom() - clipBase.getTop());

                // find left line
                findTopBottomVencLines(FindDirect.BOTTOM, newBaseCell, area, candidateVencRulings, page, rulingInfo);
                if (!candidateVencRulings.isEmpty()) {
                    leftLine = candidateVencRulings.get(0);
                    List<Ruling> candidateHlines = new ArrayList<>();
                    // find bottom line
                    findTopBottomHencLines(FindDirect.BOTTOM, newBaseCell, leftLine, candidateHlines, page, rulingInfo);
                    if (candidateHlines.size() > 0) {
                        // top line
                        Ruling topLine = new Ruling(newBaseCell.getBottom(), area.getLeft(), (float)area.getWidth(), 0);
                        for (Ruling bottomLine : candidateHlines) {
                            // find right line and add cell
                            findRightLineFillCell(newBaseCell, topLine, bottomLine, clipAeras, page, rulingInfo);
                            topLine = bottomLine;
                        }
                    }
                }
            }
            return;
        }
        List<Ruling> CandidateHencLines = new ArrayList<>();
        // find henc line
        for (Ruling hLine : lineHenc) {
            if (hLine.getY1() < clipBase.getBottom()) {
                continue;
            }
            boolean bTbContain = (hLine.getY1() - clipBase.getBottom() > 10) && (clipCandidate.getTop() - hLine.getY1() > 10);
            boolean bLrContain = (FloatUtils.feq(hLine.getX1(), leftLine.getX1(), 2.0) ||
                    (leftLine.getX1() - hLine.getX1() >= 2.0)) && (hLine.getX2() - leftLine.getX1() > 10);
            if (bTbContain && bLrContain) {
                CandidateHencLines.add(hLine);
            }
            if (FloatUtils.feq(hLine.getY1(), clipCandidate.getTop(), 3.5)
                    || (hLine.getY1() > clipCandidate.getTop())) {
                break;
            }
        }
        // add cell
        if (CandidateHencLines.size() > 0) {
            Ruling topLine = new Ruling(clipBase.getBottom(), clipBase.getLeft(), (float)clipBase.getWidth(),0);
            // find right line
            for (Ruling bottomLine : CandidateHencLines) {
                addVencMutiCells(leftLine, topLine, bottomLine, clipBase, page, clipAeras, rulingInfo);
                topLine = bottomLine;
            }
            addVencMutiCells(leftLine, topLine, new Ruling(clipCandidate.getTop(), clipCandidate.getLeft(),
                    (float)clipCandidate.getWidth(), 0), clipBase, page, clipAeras, rulingInfo);
        } else {
            if (clipCandidate.getTop() - clipBase.getBottom() < 10) {
                // 一个单元格中间有多余的线，将单元格一份为二，重置单元格
                clipBase.setRect(clipBase.getLeft(), clipBase.getTop(), clipBase.getWidth(),
                        clipCandidate.getTop() - clipBase.getTop());
            } else {
                addVencSignalCell(leftLine, clipBase, clipCandidate, page, clipAeras, rulingInfo);
            }
        }
    }

    private void fillVencCell(List<Rectangle> clipCandidate, Rectangle areaRegion, List<Rectangle> clipAeras, Page page, RegionLinesInfo rulingInfo) {
        if (clipCandidate.isEmpty()) {
            return;
        }
        if (clipCandidate.size() == 1) {
            // 一行只有一个单元格
            if (clipCandidate.get(0).getTop() - areaRegion.getTop() > 10) {
                findUpCells(clipCandidate.get(0), areaRegion, clipAeras, page, rulingInfo);
            }
            if (areaRegion.getBottom() - clipCandidate.get(0).getBottom() > 10) {
                findDownCells(clipCandidate.get(0), areaRegion, clipAeras, page, rulingInfo);
            }
            return;
        }

        clipCandidate.sort(Comparator.comparing(Rectangle::getTop));
        checkValidityOfCells(clipCandidate, false);
        if (clipCandidate.isEmpty()) {
            return;
        }
        Rectangle baseCell = clipCandidate.get(0);
        // top fill cell
        if (baseCell.getTop() - areaRegion.getTop() > 10) {
            findUpCells(baseCell, areaRegion, clipAeras, page, rulingInfo);
        }
        for(int i = 1; i < clipCandidate.size(); i++) {
            Rectangle candidateCell = clipCandidate.get(i);
            boolean bBaseBottomtoTop = FloatUtils.feq(baseCell.getBottom(),candidateCell.getTop(),3.5);
            if (bBaseBottomtoTop) {
                baseCell = candidateCell;
                continue;
            } else {
                generateVencCellByLine(baseCell, areaRegion, candidateCell, clipAeras, page, rulingInfo);
            }
            baseCell = candidateCell;
        }
        if (areaRegion.getBottom() - baseCell.getBottom() > 10) {
            findDownCells(baseCell, areaRegion, clipAeras, page, rulingInfo);
        }
    }

    private void processVencSubGroupCells(List<Rectangle> clipAeras, List<Rectangle> candidateClipAeras, Rectangle areaRegion, Page page, RegionLinesInfo rulingInfo) {

        findRegionCells(areaRegion, candidateClipAeras, clipAeras);
        List<Rectangle> clipCandidate = new ArrayList<>();
        if (!candidateClipAeras.isEmpty()) {
            candidateClipAeras.sort(Comparator.comparing(Rectangle::getLeft));
            Rectangle base = candidateClipAeras.get(0);
            clipCandidate.add(base);
            for (int i = 1; i < candidateClipAeras.size(); i++) {
                Rectangle candidate = candidateClipAeras.get(i);
                if (FloatUtils.feq(base.getLeft(), candidate.getLeft(), 2.0)) {
                    clipCandidate.add(candidate);
                } else {
                    base = candidate;
                    fillVencCell(clipCandidate, areaRegion, clipAeras, page, rulingInfo);
                    clipCandidate.clear();
                    clipCandidate.add(candidate);
                }
            }
            fillVencCell(clipCandidate, areaRegion, clipAeras, page, rulingInfo);
        }
    }

    private void processGroupCells(Page page, Rectangle areaRegion, List<Rectangle> candidateClipAeras, List<Rectangle> clipAeras, RegionLinesInfo rulingInfo) {
        if (candidateClipAeras.isEmpty()) {
            return;
        }
        processHencSubGroupCells(clipAeras, candidateClipAeras, areaRegion, page, rulingInfo);

        candidateClipAeras.clear();
        removeRepeatCells(page, clipAeras);
        processVencSubGroupCells(clipAeras, candidateClipAeras, areaRegion, page, rulingInfo);
    }

    private void addRegionLineCell(Page page, List<Rectangle> clipAeras, List<Rectangle> clipBaseAeras, Rectangle region, RegionLinesInfo rulingInfo) {
        // only have line
        fillCellsByLines(page, region, clipAeras, rulingInfo);
        // 纯线的算法补充不完整，需要再次补充
        findRegionCells(region, clipBaseAeras, clipAeras);
        processGroupCells(page, region, clipBaseAeras, clipAeras, rulingInfo);
    }

    private boolean removeInvalidCells(Page page, List<Rectangle> cells, Rectangle region, RegionLinesInfo rulingInfo) {

        boolean result = false;
        double minHeight = page.getText(region).parallelStream()
                .mapToDouble(TextElement::getTextHeight).min().orElse(Page.DEFAULT_MIN_CHAR_HEIGHT);
        List<Ruling> verticalLines = rulingInfo.getTableRegionAllVencLines();
        List<Ruling> horizontalLines = rulingInfo.getTableRegionAllHencLines();
        // 检测cell是否存在四条边
        for (Rectangle cell : cells) {
            if (page.getText(cell).isEmpty() && ((cell.getWidth() < 10) || (cell.getHeight() < minHeight + 0.5))) {
                cell.markDeleted();
                result = true;
            }
            if (!cell.isDeleted() && !verticalLines.isEmpty()) {
                List<Ruling> vEdges = new ArrayList<>();
                vEdges.addAll(verticalLines.stream()
                        .filter(r ->((FloatUtils.feq(r.getY1(), cell.getTop(), 3.5) || (cell.getTop() - r.getY1() >= 3.5)) &&
                                (FloatUtils.feq(r.getY2(), cell.getBottom(), 3.5) || (r.getY2() - cell.getBottom() >= 3.5)) &&
                                (FloatUtils.feq(r.getX1(), cell.getLeft(), 3.5) || FloatUtils.feq(r.getX1(), cell.getRight(), 3.5))))
                        .collect(Collectors.toList()));
                if (vEdges.size() >= 2) {
                    vEdges.sort(Comparator.comparing(Ruling::getLeft));
                    if (vEdges.size() > 2) {
                        Ruling leftLine = vEdges.get(0);
                        double threshold1 = FastMath.abs(leftLine.getX1() - cell.getLeft());
                        for (int i = 1; i < vEdges.size(); i++) {
                            Ruling otherLine = vEdges.get(i);
                            double threshold2 = FastMath.abs(otherLine.getX1() - cell.getLeft());
                            if (threshold1 > threshold2) {
                                leftLine = otherLine;
                            }
                        }
                        Ruling rightLine = vEdges.get(0);
                        threshold1 = FastMath.abs(rightLine.getX1() - cell.getRight());
                        for (int i = 1; i < vEdges.size(); i++) {
                            Ruling otherLine = vEdges.get(i);
                            double threshold2 = FastMath.abs(otherLine.getX1() - cell.getRight());
                            if (threshold1 > threshold2) {
                                rightLine = otherLine;
                            }
                        }
                        vEdges.clear();
                        vEdges.add(leftLine);
                        vEdges.add(rightLine);
                    }
                } else {
                    cell.markDeleted();
                    result = true;
                }

                if (!cell.isDeleted() && !horizontalLines.isEmpty()) {
                    List<Ruling> hEdges = horizontalLines.stream()
                            .filter(r ->((FloatUtils.feq(r.getY1(), cell.getTop(), 3.5) || FloatUtils.feq(r.getY1(), cell.getBottom(), 3.5)) &&
                                    (FloatUtils.feq(r.getX1(), vEdges.get(0).getX1(), 3.5) || (vEdges.get(0).getX1() - r.getX1() >= 3.5)) &&
                                    (FloatUtils.feq(r.getX2(), vEdges.get(1).getX1(), 3.5) || (r.getX2() - vEdges.get(0).getX1() >= 3.5))))
                            .collect(Collectors.toList());
                    if (hEdges.size() < 2) {
                        cell.markDeleted();
                        result = true;
                    }
                }

                // 检查cell是否有线穿过
                if (!cell.isDeleted()) {
                    List<Ruling> insertLines = verticalLines.stream().filter(r -> (r.intersects(cell))).collect(Collectors.toList());
                    if (insertLines.size() > 2) {
                        List<Ruling> removeRulings = new ArrayList<>();
                        removeRulings.addAll(insertLines.stream().filter(r -> (FloatUtils.feq(cell.getLeft(), r.getX1(), 3.5)
                                || FloatUtils.feq(cell.getRight(), r.getX1(), 3.5))).collect(Collectors.toList()));
                        insertLines.removeAll(removeRulings);
                        if (!insertLines.isEmpty()) {
                            cell.markDeleted();
                            result = true;
                        }
                    }
                }
            }
        }

        return result;
    }

    private List<Rectangle> negateWordFillCell(Page page, List<TableRegion> tableRegions) {

        if (tableRegions == null || tableRegions.isEmpty()) {
            return new ArrayList<>();
        }
        List<Rectangle> clipAeras = new ArrayList<>();
        tableRegions.sort(Comparator.comparing(Rectangle::getTop));
        for (Rectangle tmpTableRegion : tableRegions) {
            List<Rectangle> tmpClipAreas = new ArrayList<>();
            RegionLinesInfo lineInfo = new RegionLinesInfo();
            checkBroaderLine(tmpTableRegion, page, lineInfo);
            addRegionLineCell(page, clipAeras, tmpClipAreas, tmpTableRegion, lineInfo);
            tmpClipAreas.clear();
        }
        // TODO：针对GIC的A260的page12添加单元格拆分策略，对单个单元格内含有两个thunk，且距离远进行拆分
        List<Rectangle> cells = splitCells(page, clipAeras);
        return cells;
    }

    private List<Rectangle> splitCells(Page page, List<Rectangle> clipCells) {
        if (clipCells.isEmpty()) {
            return new ArrayList<>();
        }
        List<Rectangle> cells = new ArrayList<>();
        for (Rectangle cell : clipCells) {
            List<TextChunk> texts = new ArrayList<>(page.getTextChunks(cell));
            texts.sort(Comparator.comparing(TextChunk::getLeft));
            if (texts.size() == 1 || texts.isEmpty()) {
                cells.add(cell);
                continue;
            }
            if (texts.size() == 2 && !texts.get(0).isBlank() && !texts.get(1).isBlank() &&
                    FloatUtils.feq(texts.get(0).getTop(), texts.get(1).getTop(), 4.f)) {
                double avgWidth = page.getText(cell).parallelStream().mapToDouble(TextElement::getTextWidth).average()
                        .orElse(Page.DEFAULT_MIN_CHAR_WIDTH);
                if (texts.get(1).getLeft() - texts.get(0).getRight() > 100 * avgWidth) {
                    double tmpWidth = texts.get(1).getLeft() - texts.get(1).getRight();
                    cells.add(new Rectangle(cell.getLeft(), cell.getTop(),
                            (texts.get(0).getRight() + tmpWidth / 2) - cell.getLeft(), cell.getHeight()));
                    cells.add(new Rectangle((texts.get(0).getRight() + tmpWidth / 2), cell.getTop(),
                            cell.getRight() - (texts.get(0).getRight() + tmpWidth / 2),cell.getHeight()));
                    continue;
                }
            }
            cells.add(cell);
        }
        return cells;
    }

    private void wordFillCell(Page page, List<TableRegion> tableRegions, List<Rectangle> clipAeras) {
        if (tableRegions == null || tableRegions.isEmpty()) {
            return;
        }
        tableRegions.sort(Comparator.comparing(Rectangle::getTop));
        for (Rectangle tmpTableRegion : tableRegions) {
            List<Rectangle> tmpClipAreas = new ArrayList<>();
            RegionLinesInfo lineInfo = new RegionLinesInfo();
            checkBroaderLine(tmpTableRegion, page, lineInfo);
            findRegionCells(tmpTableRegion, tmpClipAreas, clipAeras);
            if (!tmpClipAreas.isEmpty()) {
                if (removeInvalidCells(page, tmpClipAreas, tmpTableRegion, lineInfo)) {
                    clipAeras.removeIf(Rectangle::isDeleted);
                    tmpClipAreas.clear();
                    addRegionLineCell(page, clipAeras, tmpClipAreas, tmpTableRegion, lineInfo);
                } else {
                    processGroupCells(page, tmpTableRegion, tmpClipAreas, clipAeras, lineInfo);
                    removeRepeatCells(page, clipAeras);
                    tmpClipAreas.clear();
                    findRegionCells(tmpTableRegion, tmpClipAreas, clipAeras);
                    processHencSubGroupCells(clipAeras, tmpClipAreas, tmpTableRegion, page, lineInfo);
                }
            } else {
                addRegionLineCell(page, clipAeras, tmpClipAreas, tmpTableRegion, lineInfo);
            }
            tmpClipAreas.clear();
            removeRepeatCells(page, clipAeras);
        }
    }

    private void cleanInvalidCells(Page page, List<Rectangle> cells, List<TableRegion> tableRegions) {

        if (cells.isEmpty()) {
            return;
        }
        /*ContentGroupPage contentPage = (ContentGroupPage)page;
        List<Rectangle> contentLayoutAreas = new ArrayList<>();
        List<Rectangle> tableLayoutAreas = new ArrayList<>();
        if (contentPage.getContentGroup().hasTag(Tags.CONTENT_LAYOUT_RESULT)) {
            LayoutResult layout = contentPage.getContentGroup().getTag(Tags.CONTENT_LAYOUT_RESULT, LayoutResult.class);
            contentLayoutAreas = layout.getTableLayoutAreas();
        } else {
            LayoutAnalysisAlgorithm.detect(contentPage, tableLayoutAreas, contentLayoutAreas);
        }*/
        //List<TextBlock> contentTextBlocks = TableTitle.findTextBlocks(page, seg);
        // 过滤两侧的单个单元格的空列
        List<Rectangle> removeCells = new ArrayList<>();
        for (Rectangle tableArea : tableRegions) {

            //double avgWidth = page.getText(tableArea).parallelStream()
            //        .mapToDouble(TextElement::getWidth).average().orElse(Page.DEFAULT_MIN_CHAR_WIDTH);

            List<Rectangle> tableCells = cells.stream().filter(ce -> (tableArea.nearlyContains(ce) /*&& ce.getWidth() < 5 * avgWidth*/
                    && page.getTextChunks(ce).isEmpty() && FloatUtils.feq(ce.getHeight(), tableArea.getHeight(), 10.f)
                    && (FloatUtils.feq(ce.getLeft(), tableArea.getLeft(), 5.f)
                    || FloatUtils.feq(ce.getRight(), tableArea.getRight(), 5.f)))).collect(Collectors.toList());
            List<Ruling> verticallyRulings = page.getVerticalRulings();
            if (!tableCells.isEmpty() && tableCells.size() <= 2 && !verticallyRulings.isEmpty()) {

                tableCells.sort(Comparator.comparing(Rectangle::getLeft));
                for (Rectangle tableCell : tableCells) {
                    if (FloatUtils.feq(tableCell.getLeft(), tableArea.getLeft(), 5.f)
                            && !tableCells.stream().anyMatch(re -> (re.horizontallyOverlapRatio(tableCell) > 0.5 && !re.equals(tableCell)))
                            && !verticallyRulings.stream().anyMatch(line -> (FloatUtils.feq(line.getX1(), tableCell.getLeft(), 2.f)
                            && FloatUtils.feq(line.getY1(), tableArea.getTop(), 5.f)
                            && FloatUtils.feq(line.getY2(), tableArea.getBottom(), 5.f)))) {
                        removeCells.add(tableCell);
                    }

                    if (FloatUtils.feq(tableCell.getRight(), tableArea.getRight(), 5.f)
                            && !tableCells.stream().anyMatch(re -> (re.horizontallyOverlapRatio(tableCell) > 0.5 && !re.equals(tableCell)))
                            && !verticallyRulings.stream().anyMatch(line -> (FloatUtils.feq(line.getX1(), tableCell.getRight(), 2.f)
                            && FloatUtils.feq(line.getY1(), tableArea.getTop(), 5.f)
                            && FloatUtils.feq(line.getY2(), tableArea.getBottom(), 5.f)))) {
                        removeCells.add(tableCell);
                    }
                }
            }
        }

        if (!removeCells.isEmpty()) {
            cells.removeAll(removeCells);
        }
    }

    public List<Rectangle> getCellsFromTableRulings(Page page, TableRegion tableRegion) {
        return getAllCells(page, Arrays.asList(tableRegion), null, PdfType.NoWordType);
    }

    public List<Rectangle> getAllCells(Page page, List<TableRegion> tableRegion, List<? extends Rectangle> clipAreas, PdfType type) {

        if (tableRegion == null || tableRegion.isEmpty()) {
            return new ArrayList<>();
        }

        List<Rectangle> cells = new ArrayList<>();
        if (type == PdfType.WordType) {
            List<Rectangle> wordClipAreas = clipAreas.stream().map(Rectangle::new).collect(Collectors.toList());
            wordFillCell(page, tableRegion, wordClipAreas);
            cells.addAll(wordClipAreas);
        } else {
            cells.addAll(negateWordFillCell(page, tableRegion));
        }
        cells = cells.stream().distinct().collect(Collectors.toList());
        cleanInvalidCells(page, cells, tableRegion);
        return cells;
    }
}
