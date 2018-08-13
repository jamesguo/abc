package com.abcft.pdfextract.core.table;

import com.abcft.pdfextract.core.model.Rectangle;
import com.abcft.pdfextract.core.model.TextBlock;
import com.abcft.pdfextract.core.model.TextChunk;
import com.abcft.pdfextract.core.table.detectors.TableRegionDetectionUtils;
import com.abcft.pdfextract.core.table.extractors.BitmapPageExtractionAlgorithm;
import com.abcft.pdfextract.spi.ChartType;
import com.abcft.pdfextract.util.Confident;
import com.abcft.pdfextract.util.FloatUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.math3.util.FastMath;

import java.awt.geom.Rectangle2D;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class TableRegion extends Rectangle implements Confident {

    private double confidence = 1.0;
    private TableType tableType = TableType.NoLineTable;
    private ChartType chartType = ChartType.UNKNOWN_CHART;
    private List<TextBlock> textBlockList = new ArrayList<>();
    private boolean isMerged;
    private boolean isBoundingTable;//是否属于有线框表格

    public TableRegion() {
    }

    public TableRegion(TableRegion rect) {
        super(rect);
        this.confidence = rect.confidence;
        this.tableType = rect.tableType;
    }

    public TableRegion(Float rect) {
        super(rect);
    }

    public TableRegion(Rectangle2D rect) {
        super(rect);
    }

    public TableRegion(java.awt.Rectangle rect) {
        super(rect);
    }

    public TableRegion(Rectangle rect) {
        super(rect);
    }

    public TableRegion(double left, double top, double width, double height) {
        super(left, top, width, height);
    }

    public TableRegion(TextBlock tb) {
        super(tb.getBounds2D());
        this.textBlockList.add(tb);
    }

    public TableRegion(Rectangle2D bound, List<TextBlock> textBlocks) {
        super(bound);
        this.textBlockList.addAll(textBlocks);
    }

    public boolean getMergedFlag() {
        return this.isMerged;
    }

    public void setMergedFlag(boolean isMerged) {
        this.isMerged = isMerged;
    }

    @Override
    public double getConfidence() {
        return confidence;
    }

    @Override
    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }

    // 默认降级0.1
    public void downgradeConfidence() {
        adjustConfidence(-0.1);
    }

    public void downgradeConfidence(double grade) {
        adjustConfidence(-grade);
    }

    // 默认升级0.1
    public void upgradeConfidence() {
        adjustConfidence(0.1);
    }

    public void upgradeConfidence(double grade) {
        adjustConfidence(grade);
    }

    private void adjustConfidence(double delta) {
        double newConfidence =  this.confidence + delta;
        if (newConfidence > 1.0) {
            newConfidence = 1.0;
        } else if (newConfidence < .0) {
            newConfidence = .0;
        }
        this.confidence = newConfidence;
    }

    public void setTableType(TableType tableType) {
        this.tableType = tableType;
    }

    public TableType getTableType() {
        return this.tableType;
    }

    public ChartType getChartType() {
        return chartType;
    }

    public void setChartType(ChartType chartType) {
        this.chartType = chartType;
    }

    public void setBoundingFlag(boolean isBoundingTable) {
        this.isBoundingTable = isBoundingTable;
    }

    public boolean isBoundingTable() {
        return isBoundingTable;
    }

    public List<TextBlock> getTextBlockList() {
        return this.textBlockList;
    }

    public int getTextLineSize() {
        return this.textBlockList.size();
    }

    public void addTextBlockToList(TextBlock tb) {
        if (this.textBlockList == null) {
            this.textBlockList = new ArrayList<>();
        }
        this.textBlockList.add(tb);
        this.textBlockList.sort(Comparator.comparing(TextBlock::getTop));
        this.setRect(this.createUnion(tb.getBounds2D()));
    }

    public void addTextBlocksToList(List<TextBlock> textBlocks) {
        if (this.textBlockList == null) {
            this.textBlockList = new ArrayList<>();
        }
        for (TextBlock tb : textBlocks) {
            this.addTextBlockToList(tb);
        }
    }

    public void removeTextBlockFromList(TextBlock tb) {
        if (this.textBlockList == null || this.textBlockList.isEmpty() || this.textBlockList.size() <= 1) {
            return;
        }
        this.textBlockList.remove(tb);
        this.textBlockList.sort(Comparator.comparing(TextBlock::getTop));

        Rectangle rectTemp = null;
        for (int i = 0; i < this.textBlockList.size(); i++) {
            if (rectTemp == null) {
                rectTemp = new Rectangle(this.textBlockList.get(i).getBounds2D());
            } else {
                Rectangle2D.union(rectTemp, this.textBlockList.get(i).getBounds2D(), rectTemp);
            }
        }
        this.setRect(rectTemp);
    }

    public void merge(TableRegion detectResult) {
        this.textBlockList.addAll(detectResult.textBlockList);
        this.textBlockList.sort(Comparator.comparing(TextBlock::getTop));
        this.setRect(this.getBounds2D().createUnion(detectResult.getBounds2D()));
    }

    public List<TextBlock> getTextLineInRegion(ContentGroupPage page) {
        if (page.getTextLines() == null || page.getTextLines().isEmpty()) {
            return new ArrayList<>();
        } else {
            return page.getTextLines().stream().filter(tb -> this.isShapeIntersects(new Rectangle(tb.getBounds2D()))).collect(Collectors.toList());
        }
    }

    public static List<TextBlock> getTopOuterTextBlock(ContentGroupPage page, Rectangle rc, List<TextBlock> textRows, int chooseNum) {
        if (textRows.size() <= 2) {
            return new ArrayList<>();
        }

        List<TextBlock> textRowsCopy = new ArrayList<>(textRows);
        textRowsCopy.sort(Comparator.comparing(TextBlock::getTop));
        List<TextBlock> textChoose = new ArrayList<>();
        for (int i = textRowsCopy.size() - 1; i >= 0; i--) {
            if (textChoose.size() >= chooseNum) {
                break;
            }
            if (textRowsCopy.get(i).getTop()  < rc.getTop() - 0.1 * page.getAvgCharHeight()) {
                textChoose.add(textRowsCopy.get(i));
            }
        }

        return textChoose;
    }

    public static List<TextBlock> getBottomOuterTextBlock(ContentGroupPage page, Rectangle rc, List<TextBlock> textRows, int chooseNum) {
        if (textRows.size() <= 2) {
            return new ArrayList<>();
        }

        List<TextBlock> textRowsCopy = new ArrayList<>(textRows);
        textRowsCopy.sort(Comparator.comparing(TextBlock::getTop));
        List<TextBlock> textChoose = new ArrayList<>();
        for (int i = 0; i < textRowsCopy.size(); i++) {
            if (textChoose.size() >= chooseNum) {
                break;
            }
            if (textRowsCopy.get(i).getBottom() > rc.getBottom() + 0.1 * page.getAvgCharHeight()) {
                textChoose.add(textRowsCopy.get(i));
            }
        }

        return textChoose;
    }

    //单元格内容为空的数据所占比率
    public float calSparseRatio(ContentGroupPage page) {
        List<TextBlock> textBlocks;
        List<TextChunk> textChunks = page.getMutableTextChunks(this).stream().filter(chunk -> StringUtils.isNotBlank(chunk.getText()))
                .collect(Collectors.toList());
        if (this.getTextBlockList() == null || this.textBlockList.isEmpty()) {
            textBlocks = TextMerger.collectByRows(TextMerger.groupByBlock(textChunks, page.getHorizontalRulings(), page.getVerticalRulings()));
        } else {
            textBlocks = this.textBlockList;
        }
        if (textBlocks == null || textBlocks.isEmpty()) {
            return 1;
        }

        int rowNum = textBlocks.size();
        int colNum = BitmapPageExtractionAlgorithm.calcColumnPositions(page, textBlocks).size();

        return ((float) (rowNum * colNum - textChunks.size())) / ((float) (rowNum * colNum));
    }

    private static int mergeTableRegions(ContentGroupPage page, List<TableRegion> rulingTables, List<TableRegion> noRulingTables) {
        int mergeCount = 0;
        List<TableRegion> candidateRegions = new ArrayList<>();
        Pattern MEASURE_UNIT_RE = Pattern.compile("(.*((((单位|注|人民币)([0-9]{1,2})?(：|:))|列示).*))|(.*[(（]续[)）][：:]$)");

        List<TableRegion> allTableRegions = new ArrayList<>();
        if (rulingTables != null && !rulingTables.isEmpty()) {
            allTableRegions.addAll(rulingTables);
        }
        if (noRulingTables != null && !noRulingTables.isEmpty()) {
            allTableRegions.addAll(noRulingTables);
        }
        allTableRegions.sort(Comparator.comparing(Rectangle::getTop));
        //表格包含判断和过滤
        if (allTableRegions.size() > 1) {
            for (int i = 0; i < allTableRegions.size() - 1; i++) {
                TableRegion baseRegion = allTableRegions.get(i);
                if (baseRegion.isDeleted()) {
                    continue;
                }
                for (int j = i + 1; j < allTableRegions.size(); j++) {
                    TableRegion otherRegion = allTableRegions.get(j);
                    if (otherRegion.isDeleted()) {
                        continue;
                    }
                    if (baseRegion.nearlyContains(otherRegion, 1.0)) {
                        otherRegion.markDeleted();
                        mergeCount++;
                    } else if (otherRegion.nearlyContains(baseRegion, 1.0)) {
                        baseRegion.markDeleted();
                        mergeCount++;
                        break;
                    }
                }
            }
            noRulingTables.removeIf(Rectangle::isDeleted);
            rulingTables.removeIf(Rectangle::isDeleted);
            allTableRegions.removeIf(Rectangle::isDeleted);
        }

        //表格相邻或相交的判断和过滤
        if (allTableRegions.size() > 1) {
            TableRegion baseRegion = allTableRegions.get(0);
            for (int i = 1; i < allTableRegions.size(); i++) {
                TableRegion otherRegion = allTableRegions.get(i);
                if ((baseRegion.getTableType() == TableType.NoLineTable || otherRegion.getTableType() == TableType.NoLineTable)
                        && baseRegion.horizontallyOverlapRatio(otherRegion) > 0.6) {
                    List<TextBlock> tbList = baseRegion.getTextLineInRegion(page);
                    boolean specialLine = (baseRegion.getTableType() == TableType.NoLineTable)
                            && tbList.size() == 1 && MEASURE_UNIT_RE.matcher(tbList.get(0).getText().trim()).matches();
                    if (specialLine) {
                        baseRegion.markDeleted();
                        baseRegion = otherRegion;
                        mergeCount++;
                        continue;
                    }
                    boolean isNearlyVerticalContain = ((baseRegion.getTableType() == TableType.LineTable
                            && otherRegion.getTableType() == TableType.NoLineTable)
                            && baseRegion.nearlyHorizontalContains(otherRegion, page.getAvgCharWidth()))
                            || ((baseRegion.getTableType() == TableType.NoLineTable
                            && otherRegion.getTableType() == TableType.LineTable)
                            && otherRegion.nearlyHorizontalContains(baseRegion, page.getAvgCharWidth()));
                    if (isNearlyVerticalContain && FloatUtils.feq(baseRegion.getBottom(), otherRegion.getTop(), 5)) {
                        if (baseRegion.isBoundingTable() && otherRegion.getTextLineSize() > 0) {
                            Rectangle baseRegionRect = baseRegion;
                            List<Ruling> verRulings = page.getVerticalRulings().stream().filter(rul
                                    -> FloatUtils.feq(rul.getBottom(), baseRegionRect.getBottom(), page.getAvgCharHeight())).collect(Collectors.toList());
                            verRulings.sort(Comparator.comparing(Ruling::getLeft));
                            List<TextChunk> textChunks = otherRegion.getTextBlockList().get(0).getElements();
                            boolean intersectChunkFind = false;
                            for (TextChunk textChunk : textChunks) {
                                for (Ruling ruling : verRulings) {
                                    if (ruling.getLeft() - textChunk.getLeft() > textChunk.getAvgCharWidth()
                                            && textChunk.getRight() - ruling.getLeft() > textChunk.getAvgCharWidth()) {
                                        intersectChunkFind = true;
                                        break;
                                    }
                                }
                                if (intersectChunkFind) {
                                    break;
                                }
                            }
                            if (intersectChunkFind) {
                                baseRegion = otherRegion;
                                continue;
                            }
                        }

                        TableRegion rect = new TableRegion(baseRegion.createUnion(otherRegion));
                        rect.setTableType(TableType.NoLineTable);
                        rect.setBoundingFlag(false);
                        rect.setMergedFlag(true);
                        rect.setConfidence(FastMath.max(baseRegion.getConfidence(), otherRegion.getConfidence()));
                        candidateRegions.add(rect);
                        baseRegion.markDeleted();
                        otherRegion.markDeleted();
                        mergeCount++;
                    }
                    if (!baseRegion.isDeleted() && !otherRegion.isDeleted() && (baseRegion.overlapRatio(otherRegion) > 0)) {
                        TableRegion rect = new TableRegion(baseRegion.createUnion(otherRegion));
                        rect.setTableType(TableType.NoLineTable);
                        rect.setBoundingFlag(false);
                        rect.setMergedFlag(true);
                        rect.setConfidence(FastMath.max(baseRegion.getConfidence(), otherRegion.getConfidence()));
                        candidateRegions.add(rect);
                        baseRegion.markDeleted();
                        otherRegion.markDeleted();
                        mergeCount++;
                    }
                    float left = (baseRegion.getLeft() > otherRegion.getLeft()) ? otherRegion.getLeft() : baseRegion.getLeft();
                    float right = (baseRegion.getRight() < otherRegion.getRight()) ? otherRegion.getRight() : baseRegion.getRight();
                    Rectangle gapRegion = new Rectangle(left,baseRegion.getBottom(),right - left,
                            otherRegion.getTop() - baseRegion.getBottom()).rectReduce(1.0, 1.0, page.getWidth(), page.getHeight());
                    //将相隔较近的表头和表格主体进行合并
                    if (!baseRegion.isDeleted() && !otherRegion.isDeleted() && !TableRegionDetectionUtils.hasTextBlockWithinShape(page
                            , gapRegion, page.getTextLines()) && FloatUtils.feq(baseRegion.getBottom(), otherRegion.getTop(), page.getAvgCharHeight())
                            && !baseRegion.getTextBlockList().isEmpty() && !otherRegion.getTextBlockList().isEmpty()
                            && !TableRegionDetectionUtils.hasNumberStr(baseRegion.getTextBlockList().get(baseRegion.getTextLineSize() - 1).getElements())
                            && TableRegionDetectionUtils.hasNumberStr(otherRegion.getTextBlockList().get(0).getElements())) {
                        TableRegion rect = new TableRegion(baseRegion.createUnion(otherRegion));
                        rect.setTableType(TableType.NoLineTable);
                        rect.setBoundingFlag(false);
                        rect.setMergedFlag(true);
                        rect.setConfidence(FastMath.max(baseRegion.getConfidence(), otherRegion.getConfidence()));
                        candidateRegions.add(rect);
                        baseRegion.markDeleted();
                        otherRegion.markDeleted();
                        mergeCount++;
                    }
                    if (!baseRegion.isDeleted() && !otherRegion.isDeleted() && !TableRegionDetectionUtils.hasTextBlockWithinShape(page
                            , gapRegion, page.getTextLines()) && FloatUtils.feq(baseRegion.getBottom(), otherRegion.getTop(), page.getAvgCharHeight())
                            && (baseRegion.getTextLineInRegion(page).size() > 1 || TableRegionDetectionUtils.calTableColNum(page, baseRegion) > 3
                            || otherRegion.getTextLineInRegion(page).size() > 1 || TableRegionDetectionUtils.calTableColNum(page, otherRegion) > 3)) {
                        List<Rectangle> baseColumns = BitmapPageExtractionAlgorithm.calcColumnPositions(page, baseRegion.getTextLineInRegion(page));
                        List<Rectangle> otherColumns = BitmapPageExtractionAlgorithm.calcColumnPositions(page, otherRegion.getTextLineInRegion(page));
                        if ((baseRegion.horizontalOverlap(otherRegion) > 0.7 * FastMath.max(baseRegion.getWidth(), otherRegion.getWidth())
                                && !FloatUtils.feq(baseColumns.size(), otherColumns.size(), 2))
                                || !TableRegionDetectionUtils.isColumnAlign(baseColumns, otherColumns)) {
                            baseRegion = otherRegion;
                            continue;
                        }
                        TableRegion rect = new TableRegion(baseRegion.createUnion(otherRegion));
                        rect.setTableType(TableType.NoLineTable);
                        rect.setBoundingFlag(false);
                        rect.setMergedFlag(true);
                        rect.setConfidence(FastMath.max(baseRegion.getConfidence(), otherRegion.getConfidence()));
                        candidateRegions.add(rect);
                        baseRegion.markDeleted();
                        otherRegion.markDeleted();
                        mergeCount++;
                    }
                }
                baseRegion = otherRegion;
            }
            noRulingTables.removeIf(Rectangle::isDeleted);
            rulingTables.removeIf(Rectangle::isDeleted);
            allTableRegions.removeIf(Rectangle::isDeleted);
            noRulingTables.addAll(candidateRegions);
        }
        return mergeCount;
    }

    public static void mergeDiffTypeTables(ContentGroupPage page, List<TableRegion> rulingTables, List<TableRegion> noRulingTables) {
        int mergeCount;
        do {
            mergeCount = mergeTableRegions(page, rulingTables, noRulingTables);
        } while (mergeCount != 0);
    }
}
