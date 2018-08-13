package com.abcft.pdfextract.core.table.detectors;

import com.abcft.pdfextract.core.model.Rectangle;
import com.abcft.pdfextract.core.model.TextBlock;
import com.abcft.pdfextract.core.table.*;
import com.abcft.pdfextract.core.model.TextChunk;
import com.abcft.pdfextract.spi.ChartType;
import com.abcft.pdfextract.util.FloatUtils;

import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.collections4.ListUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.math3.util.FastMath;

public class StructureTableRegionDetectionAlgorithm {

    //keyword pattern
    static final Pattern TABLE_BEGINNING_KEYWORD = Pattern.compile("(^表[(（]?[0-9]{1,2}.*)|(^表[零一二三四五六七八九十].*)" +
            "|(^(table)\\s+((i|ii|iii|iv|v|vi|vii|viii|ix|I|II|III|IV|V|VI|VII|VIII|IX|Ⅰ|Ⅱ|Ⅲ|Ⅳ|Ⅴ|Ⅵ|Ⅶ|Ⅷ|Ⅸ)|(\\d{1,2})).*)" +
            "|(^(the following table).*)");
    static final Pattern CHART_BEGINNING_KEYWORD = Pattern.compile("^(图|圖|图表|圖表|chart|figure)[(（]?\\s?([0-9]|[零一二三四五六七八九十]){1,2}[)）]?.*");
    static final Pattern TABLE_ENDING_KEYWORD =Pattern.compile("(^(数据来源|资料来源|資料來源|除特别注明外).*)");

    private static final RulingTableRegionsDetectionAlgorithm RULING_TABLE_REGIONS_DETECTION_ALGORITHM = new RulingTableRegionsDetectionAlgorithm();

    private List<Table> mergeSplitedTable(ContentGroupPage page, List<Table> tables) {
        if (tables == null || tables.isEmpty()) {
            return new ArrayList<>();
        }

        List<Table> mergedTables = new ArrayList<>();
        for (Table table : tables) {
            // 过滤无效空表
            if (table.getCells().isEmpty() && table.getStructureCells().isEmpty()) {
                continue;
            }

            if (mergedTables.isEmpty()) {
                mergedTables.add(table);
                continue;
            }

            Table lastMergedTable = mergedTables.get(mergedTables.size() - 1);
            if (lastMergedTable.horizontallyOverlapRatio(table) < 0.7) {
                continue;
            }
            float gapMinX = FastMath.min(lastMergedTable.getLeft(), table.getLeft());
            float gapMaxX = FastMath.max(lastMergedTable.getRight(), table.getRight());
            float gapMinY = lastMergedTable.getBottom();
            float gapMaxY = table.getTop();
            Rectangle rectGap = new Rectangle(gapMinX, gapMinY, gapMaxX - gapMinX, gapMaxY - gapMinY)
                    .rectReduce(1.0, 1.0, page.getWidth(), page.getHeight());
            if (lastMergedTable.isShapeIntersects(table)
                    || (FloatUtils.feq(lastMergedTable.getBottom(), table.getTop(), 1.5 * page.getAvgCharHeight()) && page.getText(rectGap).isEmpty())) {
                lastMergedTable.addStructureCells(table.getStructureCells());
                lastMergedTable.setRect(lastMergedTable.getBounds2D().createUnion(table.getBounds()));
                lastMergedTable.setMergedTable(true);
                lastMergedTable.setCellContainer(new Table.CellContainer());
            } else {
                mergedTables.add(table);
            }
        }
        return mergedTables;
    }

    private List<Table> splitTable(ContentGroupPage page, List<Table> tables) {
        if (tables == null || tables.isEmpty()) {
            return new ArrayList<>();
        }

        List<Table> splitedTables = new ArrayList<>();
        for (Table table : tables) {
            if (table.getTableMergeFlag()) {
                splitedTables.add(table);
                continue;
            }

            List<Integer> splitIdxList = new ArrayList<>();
            List<List<CandidateCell>> structureCells = table.getStructureCells();
            if (structureCells.size() < 5) {
                splitedTables.add(table);
                continue;
            }
            for (int i = 0; i < structureCells.size(); i++) {
                if (i == 0 || i == structureCells.size() - 1) {
                    continue;
                }
                if (structureCells.get(i).size() == 1 && structureCells.get(i).get(0).getElements().size() == 1) {
                    TextChunk nowText = structureCells.get(i).get(0).getElements().get(0);
                    List<String> structureType = nowText.getStructureTypes();
                    if (structureType == null || (!structureType.contains("table_head_NOTES")
                            && !structureType.contains("table_text_NOTES_4p_") && !structureType.contains("table_text_NOTES_5p_"))) {
                        continue;
                    }
                    Rectangle lastBound = Rectangle.union(structureCells.get(i - 1));
                    Rectangle nowBound = Rectangle.union(structureCells.get(i));
                    Rectangle nextBound = Rectangle.union(structureCells.get(i + 1));
                    boolean neighborGapFlag = nowBound.getTop() - lastBound.getBottom() > 1.5 * page.getAvgCharHeight()
                            && nextBound.getTop() - nowBound.getBottom() < 1.2 * page.getAvgCharHeight();
                    boolean hasTopRuling = !NoRulingTableRegionsDetectionAlgorithm.findLongRulingBetween(page.getHorizontalRulings(), (float)lastBound.getCenterY()
                            , (float)nowBound.getCenterY(), (float)(0.8 * table.getWidth())).isEmpty();
                    boolean hasBottomRuling = !NoRulingTableRegionsDetectionAlgorithm.findLongRulingBetween(page.getHorizontalRulings(), (float)nowBound.getCenterY()
                            , (float)nextBound.getCenterY(), (float)(0.8 * table.getWidth())).isEmpty();
                    boolean rightAssign = FloatUtils.feq(table.getRight(), nowBound.getRight(), 2 * page.getAvgCharWidth()) && nowBound.getLeft() > table.getCenterX();
                    if (neighborGapFlag && hasTopRuling && hasBottomRuling && rightAssign) {
                        splitIdxList.add(i);
                    }
                }
            }

            splitIdxList.add(0);
            splitIdxList.add(structureCells.size());
            Collections.sort(splitIdxList);
            if (splitIdxList.size() > 2) {
                for (int i = 0; i < splitIdxList.size() - 1; i++) {
                    Table tableTemp = new Table(page);
                    tableTemp.setStructureCells(structureCells.subList(splitIdxList.get(i), splitIdxList.get(i + 1)));
                    tableTemp.setRect(tableTemp.getStructureBound());
                    splitedTables.add(tableTemp);
                }
            } else {
                splitedTables.add(table);
            }
        }

        return splitedTables;
    }

    private void filterFalseStructureTableRegion(ContentGroupPage page, List<Table> structureTables) {
        if (structureTables.isEmpty()) {
            return;
        }

        //filter chart
        List<TableRegion> chartRegions = page.getChartRegions("StructureTableRegionDetectionAlgorithm");
        for (Table structureTable : structureTables) {
            for (TableRegion chart : chartRegions) {
                if (chart.nearlyContains(structureTable, page.getAvgCharWidth()) || structureTable.nearlyContains(chart, page.getAvgCharWidth())) {
                    structureTable.markDeleted();
                    break;
                }
                float overlap = structureTable.overlapRatio(chart);
                if ((chart.getChartType() == ChartType.COLUMN_CHART || chart.getChartType() == ChartType.BAR_CHART)
                        && overlap > 0.5) {
                    structureTable.markDeleted();
                    break;
                } else if (overlap > 0.3) {
                    structureTable.markDeleted();
                    break;
                }
            }
        }
        structureTables.removeIf(Table::isDeleted);

        //filter content
        for (Table structureTable : structureTables) {
            if (structureTable.isDeleted()) {
                continue;
            }
            List<TextBlock> tbList = structureTable.getStructureCells().stream().map(row -> new TextBlock(row.stream()
                    .map(CandidateCell::getMergedTextChunk).collect(Collectors.toList()))).collect(Collectors.toList());
            List<TableRegion> lineTableRegions = page.getLineTableRegions();
            boolean hasCrossLineTable = false;
            for (TableRegion tr : lineTableRegions) {
                Rectangle2D intersectBounds = tr.createIntersection(structureTable);
                float a0 = (float) (intersectBounds.getWidth() * intersectBounds.getHeight());
                float a1 = structureTable.getArea();
                if (a0 / a1 > 0.2) {
                    hasCrossLineTable = true;
                }
            }

            if (!hasCrossLineTable && tbList.size() == 1 && tbList.get(0).getSize() == 1) {
                structureTable.markDeleted();
                continue;
            }

            int multiColRowNum = 0;
            for (TextBlock tb : tbList) {
                if (TableRegionDetectionUtils.isLargeGap(tb, 2 * tb.getAvgCharWidth(), 1)) {
                    multiColRowNum++;
                }
            }
            if (!hasCrossLineTable && multiColRowNum < 2 && multiColRowNum < 0.4 * tbList.size()) {
                for (List<CandidateCell> rowCells : structureTable.getStructureCells()) {
                    if (rowCells.size() == 1 && StringUtils.isNotBlank(rowCells.get(0).getText())
                            && TableRegionDetectionUtils.splitChunkByRow(page.getMutableTextChunks(rowCells.get(0))).size() > 7) {
                        structureTable.markDeleted();
                        break;
                    }
                }
            }
        }
        structureTables.removeIf(Table::isDeleted);

        //关键词，行列信息
        for (Table structureTable : structureTables) {
            if (structureTable.isDeleted()) {
                continue;
            }

            List<TextBlock> tbList = structureTable.getStructureCells().stream().map(row -> new TextBlock(row.stream()
                    .map(CandidateCell::getMergedTextChunk).collect(Collectors.toList()))).collect(Collectors.toList());
            if (tbList.isEmpty()) {
                continue;
            }

            if (tbList.size() <= 2) {
                for (TextBlock tb : tbList) {
                    if (TableRegionDetectionUtils.matchAnyText(tb.getElements(), NoRulingTableRegionsDetectionAlgorithm.CHART_BEGINNING_KEYWORD)) {
                        structureTable.markDeleted();
                        break;
                    }
                }
                if (structureTable.isDeleted()) {
                    continue;
                }
            }

            if ((structureTable.getStructureCells() != null && structureTable.getStructureCells().stream()
                    .mapToInt(row -> row.size()).max().orElse(0) <= 1)
                    || TableRegionDetectionUtils.calTableColNum(page, structureTable, tbList) == 1) {
                Rectangle searchRect = new Rectangle(structureTable.getLeft(), structureTable.getTop() - 2 * page.getAvgCharHeight()
                        , structureTable.getWidth(), 2 * page.getAvgCharHeight());
                List<TextBlock> topTextLines = TextMerger.collectByRows(TextMerger.groupByBlock(page.getMutableTextChunks(searchRect)
                        , page.getHorizontalRulings(), page.getVerticalRulings()));
                if (TableRegionDetectionUtils.matchAnyText(tbList.get(0).getElements(), NoRulingTableRegionsDetectionAlgorithm.TABLE_BEGINNING_KEYWORD)
                        || TableRegionDetectionUtils.matchAnyText(topTextLines, NoRulingTableRegionsDetectionAlgorithm.TABLE_BEGINNING_KEYWORD)) {
                    continue;
                }
                structureTable.markDeleted();
            }
        }
        structureTables.removeIf(Table::isDeleted);

        //目录
        Pattern APOSTROPHE_RE = Pattern.compile("((\\.\\s){3})|(\\.{3})");
        for (Table table : structureTables) {
            if (table.getCellCount() == 0) {
                continue;
            }

            List<Cell> rowCells = table.getRow(0);
            for (Cell cell : rowCells) {
                List<TextChunk> textChunksWithinRC = page.getMutableTextChunks(cell);
                if (textChunksWithinRC.isEmpty()) {
                    continue;
                }

                List<TextBlock> textLines = TextMerger.collectByRows(TextMerger.groupByBlock(textChunksWithinRC
                        , page.getHorizontalRulings(), page.getVerticalRulings()));
                if (NoRulingTableRegionsDetectionAlgorithm.SPECIAL_STR_CONTENT_RE.matcher(textLines.get(0).getText().trim()
                        .toLowerCase()).matches()) {
                    int apostropheNum = 0;
                    for (TextBlock tb : textLines) {
                        if (APOSTROPHE_RE.matcher(tb.getText()).find()) {
                            apostropheNum++;
                        }
                    }
                    if (apostropheNum >= 0.5 * textLines.size()) {
                        table.markDeleted();
                        break;
                    }
                }
            }
        }
        structureTables.removeIf(Table::isDeleted);
    }

    private void correctStructureTableRegion(ContentGroupPage page, List<Table> structureNoLineTables) {
        List<TableRegion> rulingRectangles = RulingTableRegionsDetectionAlgorithm.findRulingRectangles(page);//由线组成的外接框区域

        for (Table nonLineTable : structureNoLineTables) {
            ////修正表格中包含标题的情况
            List<TextBlock> tbList = nonLineTable.getStructureCells().stream().map(row -> new TextBlock(row.stream()
                    .map(CandidateCell::getMergedTextChunk).collect(Collectors.toList()))).collect(Collectors.toList());
            tbList.sort(Comparator.comparing(TextBlock::getTop));
            if (tbList.isEmpty()) {
                nonLineTable.markDeleted();
                continue;
            }

            //structureCell有可能乱序,或者多行错误放在一起
            TextBlock topTextBlock = tbList.get(0);
            String topStr = topTextBlock.getText().trim().toLowerCase();
            boolean topMatchFind = (TABLE_BEGINNING_KEYWORD.matcher(topStr).matches() || CHART_BEGINNING_KEYWORD.matcher(topStr).matches()
                    || TABLE_ENDING_KEYWORD.matcher(topStr).matches())
                    && TableRegionDetectionUtils.splitChunkByRow(page.getMutableTextChunks(topTextBlock.toRectangle())).size() <= 2;
            TextBlock bottomTextBlock = tbList.get(tbList.size() - 1);
            String bottomStr = bottomTextBlock.getText().trim().toLowerCase();
            boolean bottomMatchFind = (TABLE_BEGINNING_KEYWORD.matcher(bottomStr).matches() || CHART_BEGINNING_KEYWORD.matcher(bottomStr).matches()
                    || TABLE_ENDING_KEYWORD.matcher(bottomStr).matches())
                    && TableRegionDetectionUtils.splitChunkByRow(page.getMutableTextChunks(bottomTextBlock.toRectangle())).size() <= 2;
            if (topMatchFind || bottomMatchFind) {
                List<TableRegion> intersectRectangles = rulingRectangles.stream().filter(tb
                        -> tb.overlapRatio(nonLineTable, true) > 0.6).collect(Collectors.toList());
                if (intersectRectangles.isEmpty()) {
                    if (topMatchFind) {
                        nonLineTable.setTop((float) topTextBlock.getBottom() + 0.1f);
                    }
                    if (bottomMatchFind) {
                        nonLineTable.setBottom((float) bottomTextBlock.getTop() - 0.1f);
                    }
                } else if (intersectRectangles.size() == 1) {
                    if (topMatchFind && topTextBlock.getCenterY() < intersectRectangles.get(0).getTop()) {
                        nonLineTable.setTop((float) topTextBlock.getBottom() + 0.1f);
                    }
                    if (bottomMatchFind && bottomTextBlock.getCenterY() > intersectRectangles.get(0).getBottom()) {
                        nonLineTable.setBottom((float) bottomTextBlock.getTop() - 0.1f);
                    }
                }

                if (nonLineTable.getBottom() < nonLineTable.getTop()) {
                    nonLineTable.markDeleted();
                } else {
                    List<List<CandidateCell>> innnerCells = nonLineTable.getStructureCells().stream().filter(rowCells
                            -> Rectangle.boundingBoxOf(rowCells).intersects(nonLineTable)).collect(Collectors.toList());
                    if (innnerCells.isEmpty()) {
                        nonLineTable.markDeleted();
                    } else {
                        nonLineTable.setStructureCells(innnerCells);
                    }
                }
            }

            ////修正表格上下区域有大量正文的情况
            if (!nonLineTable.isDeleted()) {
                List<List<CandidateCell>> innnerCells = nonLineTable.getStructureCells();
                int multiColNum = innnerCells.stream().filter(rowCells -> rowCells.size() > 1).collect(Collectors.toList()).size();
                if (multiColNum < 0.5 * innnerCells.size() && innnerCells.size() > 10
                        && page.getTextChunks().size() - page.getTextChunks(nonLineTable).size() <= 2) {
                    List<TextBlock> textBlocks = innnerCells.stream().map(row -> new TextBlock(row.stream()
                            .map(CandidateCell::getMergedTextChunk).collect(Collectors.toList()))).collect(Collectors.toList());
                    float pageValidWidth = TableRegionDetectionUtils.getValidTextWidthInRegion(page, nonLineTable);

                    textBlocks.sort(Comparator.comparing(TextBlock::getTop));
                    for (TextBlock textBlock : textBlocks) {
                        if (textBlock.getSize() > 1 || textBlock.getTop() > nonLineTable.getCenterY()) {
                            break;
                        }
                        if (textBlock.getSize() == 1 && FloatUtils.feq(textBlock.getWidth(), pageValidWidth, page.getAvgCharWidth())
                                && textBlock.getHeight() > 1.5 * textBlock.getAvgCharHeight() && Ruling.getRulingsFromArea(page.getRulings()
                                , textBlock.toRectangle().withCenterExpand(textBlock.getAvgCharWidth(), 2 * textBlock.getAvgCharHeight())).isEmpty()) {
                            nonLineTable.setTop((float) textBlock.getBottom() + 0.1f);
                            break;
                        }
                    }

                    textBlocks.sort(Comparator.comparing(TextBlock::getBottom).reversed());
                    for (TextBlock textBlock : textBlocks) {
                        if (textBlock.getSize() > 1 || textBlock.getBottom() < nonLineTable.getCenterY()) {
                            break;
                        }
                        if (textBlock.getSize() == 1 && FloatUtils.feq(textBlock.getWidth(), pageValidWidth, page.getAvgCharWidth())
                                && textBlock.getHeight() > 1.5 * textBlock.getAvgCharHeight() && Ruling.getRulingsFromArea(page.getRulings()
                                , textBlock.toRectangle().withCenterExpand(textBlock.getAvgCharWidth(), 2 * textBlock.getAvgCharHeight())).isEmpty()) {
                            nonLineTable.setBottom((float) textBlock.getTop() - 0.1f);
                            break;
                        }
                    }
                }
            }
        }
        structureNoLineTables.removeIf(Table::isDeleted);
    }

    public List<Table> detect(ContentGroupPage page, List<Table> structureTables,
                       List<Table> structureNoLineTables, List<Table> structureLineTables) {
        if (structureTables.isEmpty()) {
            return structureTables;
        }

        //将分裂表格进行合并
        structureTables.sort(Comparator.comparing(Table::getTop));
        List<Table> mergedTables = mergeSplitedTable(page, structureTables);
        mergedTables = splitTable(page, mergedTables);

        //根据chart检测结果进行结构化过检表格过滤，如chart、框图、正文都有可能过检
        filterFalseStructureTableRegion(page, mergedTables);

        //分类
        List<Rectangle> oldTableBound = new ArrayList<>();
        for (Table table : mergedTables) {
            boolean sameTableFindFlag = false;
            TableRegion tableBound = new TableRegion(table.calculateTableBound(page));
            if (oldTableBound.isEmpty()) {
                oldTableBound.add(tableBound);
            } else {
                for (Rectangle rect : oldTableBound) {
                    if (rect.overlapRatio(tableBound) > 0.9) {
                        sameTableFindFlag = true;
                        break;
                    }
                }
                oldTableBound.add(tableBound);
            }
            if (sameTableFindFlag) {
                continue;
            }

            table.setRect(tableBound);
            if (RULING_TABLE_REGIONS_DETECTION_ALGORITHM.isLineTable(page, tableBound)) {
                structureLineTables.add(table);
            } else {
                structureNoLineTables.add(table);
            }
        }

        //修正无线表格区域，有线表格不受影响; 同时过滤掉无效结构化表格
        correctStructureTableRegion(page, structureNoLineTables);

        // 因为上面的处理可能会拆分或合并表格, 这里需要更新结果
        return ListUtils.union(structureLineTables, structureNoLineTables);
    }

}
