package com.abcft.pdfextract.core.table.extractors;

import com.abcft.pdfextract.core.ExtractParameters;
import java.io.File;
import java.io.IOException;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.commons.io.FilenameUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import com.abcft.pdfextract.core.ExtractorUtil;
import com.abcft.pdfextract.core.PaperParameter;
import com.abcft.pdfextract.core.gson.GsonUtil;
import com.abcft.pdfextract.core.model.*;
import com.abcft.pdfextract.core.table.*;
import com.abcft.pdfextract.core.table.detectors.ClipAreasTableRegionDetectionAlgorithm;
import com.abcft.pdfextract.core.table.detectors.RulingTableRegionsDetectionAlgorithm;
import com.abcft.pdfextract.core.table.detectors.StructureTableRegionDetectionAlgorithm;
import com.abcft.pdfextract.core.table.detectors.TableRegionCrfAlgorithm;
import com.abcft.pdfextract.core.util.TrainDataWriter;
import com.abcft.pdfextract.util.FloatUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.math3.util.FastMath;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.tensorflow.SavedModelBundle;
import org.tensorflow.Session;
import org.tensorflow.Tensor;
import org.tensorflow.example.Example;

import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.*;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.stream.Collectors;


/**
 * 矢量表格解析算法集合。
 * <p>
 * 这里包括所有矢量算法的解析。这个类不是线程安全的，每次使用时必须创建新的实例。
 */
public class VectorTableExtractionAlgorithm implements ExtractionAlgorithm {

    private static final Logger logger = LogManager.getLogger(VectorTableExtractionAlgorithm.class);

    public static final String ALGORITHM_NAME = "Vector";
    public static float crfTime = 0;
    public static float vectorTime = 0;
    // 算法发布日期。
    private static final String ALGORITHM_DATE = "20180716";

    // 版本号不能带小数点(.),否则保存时会出错
    public static final String ALGORITHM_VERSION = ALGORITHM_NAME + "-v" + ALGORITHM_DATE;

    private static final CellFillAlgorithm CELL_FILL_ALGORITHM = new CellFillAlgorithm();
    private final StructureTableRegionDetectionAlgorithm STRUCTURE_TABLE_REGION_DETECTION_ALGORITHM = new StructureTableRegionDetectionAlgorithm();
    private final RulingTableRegionsDetectionAlgorithm RULING_TABLE_REGIONS_DETECTION_ALGORITHM = new RulingTableRegionsDetectionAlgorithm();

    // TODO 列宽参数的对齐/黏着参数（20pt）是否会由于我们变更了线框提取算法变得没必要了？

    private static final int MIN_COLUMN_WIDTH = 18;
    private static final int MIN_ROW_HEIGHT = 4;

    private static final class PageParam {
        final TableExtractParameters params;
        final Page page;
        final PaperParameter paper;
        int pgIndex;
        int pgWidth;
        int pgHeight;
        int marginTop;
        int marginBottom;
        int marginLeft;
        int marginRight;
        int cellHeight;
        Rectangle textArea;

        boolean isChinesePage = true;
        final boolean pptGenerated;
        final boolean wordGenerated;
        final boolean verticalPage;
        String pdfPath;
        String language;

        PageParam(Page page, TableExtractParameters params) {
            this.params = params;
            this.page = page;
            this.paper = page.getPaper();
            this.pptGenerated = ExtractorUtil.isPPTGeneratedPDF(params);
            this.wordGenerated = ExtractorUtil.isWordGeneratedPDF(params);
            this.language = page.getLanguage();
            this.isChinesePage = StringUtils.equalsIgnoreCase(this.language, "zh");
            this.pgIndex = 0;
            Point2D.Float pageSize = ExtractorUtil.determinePageSize(page.getPDPage());
            this.pgWidth = (int) pageSize.getX();
            this.pgHeight = (int) pageSize.getY();
            this.verticalPage = pageSize.x < pageSize.y;
            this.marginTop = 10;
            this.marginBottom = 10;
            this.marginLeft = 0;
            this.marginRight = 0;
            this.cellHeight = -1;
            this.textArea = Rectangle.fromLTRB(marginLeft, marginTop, pgWidth - marginRight, pgHeight - marginBottom);
        }

        void setCellHeight(int cellHeight) {
            this.cellHeight = Math.min(8, cellHeight);
        }
    }

    private List<CandidateCell> pageCells = null;
    private List<Table> lineTables = new ArrayList<>();
    private List<Table> nonLinesTables = new ArrayList<>();
    private List<Table> crfLineTables = new ArrayList<>();
    private List<Table> crfNonLinesTables = new ArrayList<>();
    private PageParam pagePara;

    @Override
    public String toString() {
        return ALGORITHM_VERSION;
    }

    @Override
    public String getVersion() {
        return ALGORITHM_VERSION;
    }

    @Override
    public List<? extends Table> extract(Page page) {
        pagePara = new PageParam(page, page.params);
        List<Table> tables = new ArrayList<>();

        extractTables(page);

        if (lineTables != null && !lineTables.isEmpty()) {
            tables.addAll(lineTables);
            TableDebugUtils.writeTables(page, lineTables, "tables_LineTables");
        }

        if (nonLinesTables != null && !nonLinesTables.isEmpty()) {
            tables.addAll(nonLinesTables);
            TableDebugUtils.writeTables(page, nonLinesTables, "tables_NonLinesTables");
        }

        tables.sort(Comparator.comparing(Rectangle::getTop));

        TableTitle.searchTableTitles(page, tables);

        return tables;
    }

    private void extractTables(Page page) {
        logger.info("当前处理的文件:{},页数:{}",page.getParams().path,page.getPageNumber());
        long startTime = System.currentTimeMillis();
        if (pageCells == null) {
            pageCells = getPageCells(page, pagePara);  //调用之前调用isHit(Page page)
        }

        //根据页面画的表格区域进行解析
        ExtractParameters.PageAreaMap hintAreas = page.params.hintAreas;
        List<Rectangle2D> allTableRegions = hintAreas.get(page.getPageNumber());
        if (allTableRegions != null && !allTableRegions.isEmpty()) {
            logger.info("Extracting tables from {} hint areas...", allTableRegions.size());
            extractHintAreaTables((ContentGroupPage) page, allTableRegions);
            return;
        }

        TableDebugUtils.writeCells(page, pageCells, "cells_clips");
        TableDebugUtils.writeLines(page, page.getRawRulings(), "rulings_00_all");
        TableDebugUtils.writeLines(page, page.getVisibleRulings(), "rulings_01_visible");
        TableDebugUtils.writeLines(page, page.getRulings(), "rulings_02_clean");

        //获取对应的表格区域并修改json文件，新增json表格类型
        if (page.params.jsonDataFilesPath != null) {
            //extractJsonTables(page);
            extractXMLTables(page);
            return;
        }

        //CRF模型表格解析
        if (page.params.useCRFModel) {
            long startTime2 = System.currentTimeMillis();
            extractCRFTables((ContentGroupPage) page);
            crfTime+=System.currentTimeMillis() - startTime2;
            //return;
        }

        //结构化信息表格解析
        if (page.params.useStructureFeatures && page instanceof ContentGroupPage) {
            ContentGroupPage contentGroupPage = (ContentGroupPage) page;
            extractStructureTables(contentGroupPage);
        }

        //矢量表格解析
        extractVectorTables(page);

        //表格过滤
        if (nonLinesTables != null) {
            for (Table nonTable : nonLinesTables) {
                if (nonTable.isOneRowMultiValidTable(page) || nonTable.isSparseTable()) {
                    nonTable.markDeleted();
                }
            }
            nonLinesTables.removeIf(Rectangle::isDeleted);
        }
        if (page.params.useStructureFeatures) {
            Table.filterTableByStructureFeature(page, this.nonLinesTables, this.lineTables);
        }
        if(page.params.useCRFModel){
            //对CRF模型结果进行融合
            //Table.combinaCrfAndVectorResults((ContentGroupPage)page,this.crfLineTables,this.crfNonLinesTables,this.lineTables,this.nonLinesTables);
        }
        vectorTime+=System.currentTimeMillis() - startTime;
        logger.info("CRF单次页面耗时: {} ms", crfTime);
        logger.info("矢量单次页面耗时: {} ms", vectorTime);
    }

    /**
     * 无表表格在没有用矢量算法的情况下，才用到裁剪区定位的区域;另外有线框表格不受位图影响
     */
    private void extractVectorTables(Page page) {
        List<TableRegion> rulingTables = new ArrayList<>(page.getRulingTableRegions());
        List<TableRegion> noRulingTables = new ArrayList<>(page.getNoRulingTableRegions());

        //有线表格解析
        CellFillAlgorithm.PdfType pdfType = pagePara.wordGenerated ? CellFillAlgorithm.PdfType.WordType : CellFillAlgorithm.PdfType.NoWordType;
        List<Rectangle> cells = new ArrayList<>(CELL_FILL_ALGORITHM.getAllCells(page, rulingTables, pageCells, pdfType));
        upDatePageCells(rulingTables, cells, pageCells);
        String algorithmName = TableExtractionAlgorithmType.VECTOR_NAME + "_" + TableType.LineTable.name();
        setStructOfRulingTables(page, pageCells, rulingTables, algorithmName);
        debugDrawLineTableCells(rulingTables);

        //无线表格解析
        if (page.params.useNoRulingVectorFeatures) {
            algorithmName = TableExtractionAlgorithmType.VECTOR_NAME + "_" + TableType.NoLineTable.name();
            if (page.params.useBitmapPageFallback) {
                List<TableRegion> boundingTableRegions = noRulingTables.stream().filter(TableRegion::isBoundingTable)
                        .collect(Collectors.toList());
                setStructOfNonRulingTables(page, boundingTableRegions, algorithmName);
            } else {
                setStructOfNonRulingTables(page, noRulingTables, algorithmName);
            }
        } else {
            // 对于无线表格位图，矢量算法二选一，有线表格不受位图影响
            List<TableRegion> clipAreaTables = new ArrayList<>(getClipAreaTableAreas(page));
            if (!clipAreaTables.isEmpty() && !page.params.useBitmapPageFallback) {
                algorithmName = TableExtractionAlgorithmType.CLIPAREAS_NAME + "_" + TableType.LineTable.name();
                setStructOfRulingTables(page, pageCells, clipAreaTables, algorithmName);
            }
        }
    }

    /**
     * 基于结构化信息的表格解析
     */
    private void extractStructureTables(ContentGroupPage page) {
        //int pageNumber = page.getPageNumber();
        ContentGroup contentGroup = page.getContentGroup();
        List<Table> structureTables = null;
        if (contentGroup.getTextTree() != null && contentGroup.getTextTree().canUseStructInfoForTableExtraction()) {
            structureTables = StructTableExtractionAlgorithm.findStructureTables(contentGroup);
            // logger.info("Page {}: Found {} structure tables", pageNumber, structureTables.size());
        }
        if (structureTables == null || structureTables.isEmpty()) {
            return;
        }

        List<Table> structureLineTables = new ArrayList<>();
        List<Table> structureNoLineTables = new ArrayList<>();
        // 区分结构化表格为有线或者无线
        structureTables = STRUCTURE_TABLE_REGION_DETECTION_ALGORITHM.detect(page, structureTables,
                structureNoLineTables, structureLineTables);

        List<TextBlock> allTableTextBlocks = new ArrayList<>();
        for (Table table : structureTables) {
            List<TextBlock> tbList = table.getStructureCells().stream().map(row -> new TextBlock(row.stream()
                    .map(CandidateCell::getMergedTextChunk).collect(Collectors.toList()))).collect(Collectors.toList());
            allTableTextBlocks.addAll(tbList);
        }

        //无线结构化表格解析
        if (!structureNoLineTables.isEmpty()) {
            if (this.nonLinesTables == null) {
                this.nonLinesTables = new ArrayList<>();
            }
            for (Table table : structureNoLineTables) {
                String algorithmName = TableExtractionAlgorithmType.STRUCTURE_NAME + "_" + TableType.NoLineTable.name();
                //单元格信息已经确定, 直接进行结构化输出
                if (table.getCellCount() > 0 && !table.getTableMergeFlag()) {
                    table.setPage(page);
                    table.setTableType(TableType.NoLineTable);
                    table.setAlgorithmProperty(algorithmName, getVersion());
                    // 最后统一搜索标题，这样可以避免到前一张表格的区域内搜索标题
                    /*
                    if (table.getTitle() == null) {
                        BitmapPageExtractionAlgorithm.extractTableTags(page, table);
                    }
                    */
                    this.nonLinesTables.add(table);
                } else {
                    //收集struct TextBlock
                    List<TextBlock> textLines = null;
                    // 如果结构化表格正常，则根据结构化文本行进行无线表格解析
                    // 否则，如果存在单元格结构异常，则根据区域采用无线表格解析算法
                    if (table.getNormalStatus()) {
                        textLines = allTableTextBlocks.stream().filter(tb -> table.isShapeIntersects(
                                new Rectangle(tb.getBounds2D()))).collect(Collectors.toList());
                    }
                    Table nonLineTable = BitmapPageExtractionAlgorithm.chunkExtractNonLineTables(page, table, textLines);
                    if (nonLineTable == null) {
                        continue;
                    }
                    /*
                    if (table.getTitle() != null) {
                        nonLineTables.setTitle(table.getTitle());
                    }
                    if (table.getTag() != null) {
                        nonLineTables.setTag(table.getTag());
                    }
                    */
                    nonLineTable.setTableType(TableType.NoLineTable);
                    nonLineTable.setAlgorithmProperty(algorithmName, getVersion());
                    this.nonLinesTables.add(nonLineTable);
                }
            }

            TableDebugUtils.writeTables(page, this.nonLinesTables, "structure_no_line_tables");
        }

        //有线结构化表格解析
        if (!structureLineTables.isEmpty()) {
            if (this.lineTables == null) {
                this.lineTables = new ArrayList<>();
            }
            List<TableRegion> rulingTable = structureLineTables.stream().map(TableRegion::new).collect(Collectors.toList());
            List<Rectangle> cells = new ArrayList<>();
            if (pagePara.wordGenerated) {
                cells.addAll(CELL_FILL_ALGORITHM.getAllCells(page, rulingTable, pageCells, CellFillAlgorithm.PdfType.WordType));
                upDatePageCells(rulingTable, cells, pageCells);
            } else {
                cells.addAll(CELL_FILL_ALGORITHM.getAllCells(page, rulingTable, null, CellFillAlgorithm.PdfType.NoWordType));
                upDatePageCells(rulingTable, cells, pageCells);
            }
            String algorithmName = TableExtractionAlgorithmType.STRUCTURE_NAME + "_" + TableType.LineTable.name();
            setStructOfRulingTables(page, pageCells, rulingTable, algorithmName);

            //debug for writing image
            if (TableDebugUtils.isDebug()) {
                List<CandidateCell> regionCells = new ArrayList<>();
                for (Rectangle tmpTableRegion : rulingTable) {
                    regionCells.addAll(cellsContainByTable(pageCells, tmpTableRegion));
                }
                TableDebugUtils.writeCells(pagePara.page, regionCells, "structure_line_tables_cells");
            }
        }
    }

    /**
     * 基于CRF模型的表格解析
     */
    private void extractCRFTables(ContentGroupPage tablePage) {
        //logger.info("当前处理的文件:{},页数:{}",tablePage.getParams().path,tablePage.getPageNumber());
        long startTime = System.currentTimeMillis();
        SavedModelBundle savedModelBundle = TensorflowManager.INSTANCE.getSavedModelBundle(TensorflowManager.LINE_CRF_TABLE);
        Session session = savedModelBundle.session();
        System.out.println(System.currentTimeMillis()-startTime);
        List<Rectangle> layoutAnalysisRectList = tablePage.getTableLayoutAnalysis();

        List<TableRegion> crfTableRegions = new ArrayList<>();
        List<TrainDataWriter.LineInfo> lineInfos = new ArrayList<>();
        for (Rectangle rc : layoutAnalysisRectList) {
            com.abcft.pdfextract.core.content.Page.TextGroup textGroup = new com.abcft.pdfextract.core.content.Page.TextGroup(rc);
            textGroup.addTexts(tablePage.getMutableTextChunks(rc));
            lineInfos.addAll(TrainDataWriter.buildLineInfosForLayout(tablePage, null, textGroup));
        }
        if (lineInfos.isEmpty()) {
            return;
        }
        Example example = TrainDataWriter.buildExample(lineInfos);
        Tensor exampleTensor = Tensor.create(example.toByteArray());

        Tensor<?> crfTags = session.runner()
                .feed("serialized_example", exampleTensor)
                .fetch("crf_tags")
                .run()
                .get(0);
        int length = (int) crfTags.shape()[1];
        int[] tags = crfTags.copyTo(new int[1][length])[0];
        if (length == 0 || length != lineInfos.size()) {
            return;
        }
        //TODO:计算table区域
        TableRegionCrfAlgorithm.layoutAnalysisRectList = layoutAnalysisRectList;
        List<TableRegion> layoutTableRegions = TableRegionCrfAlgorithm.tableCrfAccuracy(tablePage,lineInfos,tags);
        if (layoutTableRegions.isEmpty()) {
            return;
        } else {
            crfTableRegions.addAll(layoutTableRegions);
        }

        //table 解析
        List<TableRegion> crfLineTables = new ArrayList<>();
        List<TableRegion> crfNoLineTables = new ArrayList<>();
        for (TableRegion region : crfTableRegions) {
            if (RULING_TABLE_REGIONS_DETECTION_ALGORITHM.isLineTable(tablePage, region)) {
                crfLineTables.add(region);
            } else {
                crfNoLineTables.add(region);
            }
        }

        //CRF模型无线表格解析
        if (!crfNoLineTables.isEmpty()) {
            if (this.nonLinesTables == null) {
                this.nonLinesTables = new ArrayList<>();
            }
            for (TableRegion tableBound : crfNoLineTables) {
                String algorithmName = TableExtractionAlgorithmType.CRF_MODEL_NAME + "_" + TableType.NoLineTable.name();
                Table nonLineTables = BitmapPageExtractionAlgorithm.chunkExtractNonLineTables(tablePage, tableBound, null);
                if (nonLineTables == null) {
                    continue;
                }
                nonLineTables.setTableType(TableType.NoLineTable);
                nonLineTables.setAlgorithmProperty(algorithmName, getVersion());
                this.nonLinesTables.add(nonLineTables);
            }
        }

        //CRF模型有线表格解析
        if (!crfLineTables.isEmpty()) {
            if (this.lineTables == null) {
                this.lineTables = new ArrayList<>();
            }
            List<Rectangle> cells = new ArrayList<>();
            if (pagePara.wordGenerated) {
                cells.addAll(CELL_FILL_ALGORITHM.getAllCells(tablePage, crfLineTables, pageCells, CellFillAlgorithm.PdfType.WordType));
                upDatePageCells(crfLineTables, cells, pageCells);
            } else {
                cells.addAll(CELL_FILL_ALGORITHM.getAllCells(tablePage, crfLineTables, null, CellFillAlgorithm.PdfType.NoWordType));
                upDatePageCells(crfLineTables, cells, pageCells);
            }
            String algorithmName = TableExtractionAlgorithmType.CRF_MODEL_NAME + "_" + TableType.LineTable.name();
            setStructOfRulingTables(tablePage, pageCells, crfLineTables, algorithmName);
        }
        this.crfLineTables.addAll(this.lineTables);
        this.crfNonLinesTables.addAll(this.nonLinesTables);
        //为避免影响后续结构化流程,此处予以清空
        this.lineTables.clear();
        this.nonLinesTables.clear();
        //logger.info("单次页面耗时: {} ms", crfTime);
    }

    /**
     * 根据页面画的表格区域进行表格解析
     */
    private void extractHintAreaTables(ContentGroupPage page, List<Rectangle2D> allTableRegions) {
        if (allTableRegions == null || allTableRegions.isEmpty()) {
            return;
        }

        List<TableRegion> rulingTables = new ArrayList<>();
        List<TableRegion> noRulingTables = new ArrayList<>();
        for (Rectangle2D region : allTableRegions) {
            TableRegion tableRegion = RulingTableRegionsDetectionAlgorithm.getValidTableRegions(page, region);
            logger.info("Processing hint area {}...", tableRegion);
            if (RULING_TABLE_REGIONS_DETECTION_ALGORITHM.isLineTable(page, tableRegion)) {
                rulingTables.add(tableRegion);
            } else {
                noRulingTables.add(tableRegion);
            }
        }

        //有线表格解析
        CellFillAlgorithm.PdfType pdfType = pagePara.wordGenerated ? CellFillAlgorithm.PdfType.WordType : CellFillAlgorithm.PdfType.NoWordType;
        List<Rectangle> cells = new ArrayList<>(CELL_FILL_ALGORITHM.getAllCells(page, rulingTables, pageCells, pdfType));
        upDatePageCells(rulingTables, cells, pageCells);
        String algorithmName = TableExtractionAlgorithmType.VECTOR_NAME + "_" + TableType.LineTable.name();
        setStructOfRulingTables(page, pageCells, rulingTables, algorithmName);
        debugDrawLineTableCells(rulingTables);

        //无线表格解析
        if (page.params.useNoRulingVectorFeatures) {
            algorithmName = TableExtractionAlgorithmType.VECTOR_NAME + "_" + TableType.NoLineTable.name();
            setStructOfNonRulingTables(page, noRulingTables, algorithmName);
        } else {
            List<TableRegion> clipAreaTables = new ArrayList<>(getClipAreaTableAreas(page));
            if (!clipAreaTables.isEmpty()) {
                algorithmName = TableExtractionAlgorithmType.CLIPAREAS_NAME + "_" + TableType.LineTable.name();
                setStructOfRulingTables(page, pageCells, clipAreaTables, algorithmName);
            }
        }
    }

    private void debugDrawLineTableCells(List<TableRegion> tables) {
        if (TableDebugUtils.isDebug()) {
            List<CandidateCell> regionCells = new ArrayList<>();
            for (Rectangle tmpTableRegion : tables) {
                regionCells.addAll(cellsContainByTable(pageCells, tmpTableRegion));
            }
            TableDebugUtils.writeCells(pagePara.page, regionCells, "cells_line_table");
        }
    }

    /**
     *从XML中读取GIC数据
     *
     */

    public void extractXMLTables(Page tablePage) {
        String fileName = FilenameUtils.getBaseName(tablePage.pdfPath);
        String jsonPath = tablePage.params.jsonDataFilesPath;
        int pageNum = tablePage.getPageNumber() - 1;
        String jsonFile = null;
        List<TableRegion> crfLineTables = new ArrayList<>();
        List<TableRegion> crfNoLineTables = new ArrayList<>();
        jsonFile = fileName + "_page_" + pageNum + ".xml";
        DocumentBuilderFactory bdf = DocumentBuilderFactory.newInstance();
        DocumentBuilder db = null;
        float xMin,xMax,yMin,yMax;
        try {
            db = bdf.newDocumentBuilder();
            Document document = db.parse(new File(        jsonPath + "/" + jsonFile));
            NodeList list = document.getElementsByTagName("object");
            for (int i = 0; i < list.getLength(); i++) {
                Element element = (Element)list.item(i);
                String name = element.getElementsByTagName("name").item(0).getFirstChild().getNodeValue();
                if(name.equals("TABLE")){
                    NodeList list2 =element.getElementsByTagName("bndbox");
                    xMin = Float.valueOf(list2.item(0).getNodeValue());
                    yMin = Float.valueOf(list2.item(1).getNodeValue());
                    xMax = Float.valueOf(list2.item(2).getNodeValue());
                    yMax = Float.valueOf(list2.item(3).getNodeValue());
                    TableRegion region = new TableRegion(xMin, yMin, xMax - xMin, yMax - yMin);
                    //利用鲁方波的算法新增类别，是否是有线表格还是无线表格
                    if (new RulingTableRegionsDetectionAlgorithm().isLineTable((ContentGroupPage)tablePage, region)) {
                        crfLineTables.add(region);
                    } else {
                        crfNoLineTables.add(region);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        if(crfLineTables.size() == 0 && crfNoLineTables.size() == 0){
            return;
        }

        if (null == pagePara) {
            pagePara = new PageParam(tablePage, tablePage.getParams());
        }

        //无线表格解析
        if (!crfNoLineTables.isEmpty()) {
            if (this.nonLinesTables == null) {
                this.nonLinesTables = new ArrayList<>();
            }
            for (TableRegion tableBound : crfNoLineTables) {
                String algorithmName = TableExtractionAlgorithmType.VECTOR_NAME + "_" + TableType.NoLineTable.name();
                Table nonLineTables = BitmapPageExtractionAlgorithm.chunkExtractNonLineTables((ContentGroupPage)tablePage, tableBound, null);
                if (nonLineTables == null) {
                    continue;
                }
                nonLineTables.setTableType(TableType.NoLineTable);
                nonLineTables.setAlgorithmProperty(algorithmName, getVersion());
                this.nonLinesTables.add(nonLineTables);
            }
        }

        //有线表格解析
        if (!crfLineTables.isEmpty()) {
            if (this.lineTables == null) {
                this.lineTables = new ArrayList<>();
            }
            pageCells = new ArrayList<>();
            List<Rectangle> cells = new ArrayList<>(CELL_FILL_ALGORITHM.getAllCells(tablePage, crfLineTables, null, CellFillAlgorithm.PdfType.NoWordType));
            upDatePageCells(crfLineTables, cells, pageCells);
            String algorithmName = TableExtractionAlgorithmType.VECTOR_NAME + "_" + TableType.LineTable.name();
            setStructOfRulingTables(tablePage, pageCells, crfLineTables, algorithmName);
        }

        List<Table> tables = new ArrayList<>();
        if (this.lineTables != null && !this.lineTables.isEmpty()) {
            tables.addAll(this.lineTables);
        }

        if (this.nonLinesTables != null && !this.nonLinesTables.isEmpty()) {
            tables.addAll(this.nonLinesTables);
        }

        tables.sort(Comparator.comparing(Rectangle::getTop));
        TableDebugUtils.writeTables(tablePage, tables, "xml_tables");
    }

    /**
     * 读取json文件中的表格并进行解析
     */
    public List<Table> extractJsonTables(Page page) {
        List<TableRegion> crfLineTables = new ArrayList<>();
        List<TableRegion> crfNoLineTables = new ArrayList<>();

        ContentGroupPage tablePage;
        if (!(page instanceof ContentGroupPage)) {
            tablePage = ContentGroupPage.fromPage(page);
        } else {
            tablePage = (ContentGroupPage) page;
        }

        GsonUtil.readTabelsFromJson(tablePage, crfLineTables, crfNoLineTables);

        if (null == pagePara) {
            pagePara = new PageParam(page, tablePage.getParams());
        }

        //无线表格解析
        if (!crfNoLineTables.isEmpty()) {
            if (this.nonLinesTables == null) {
                this.nonLinesTables = new ArrayList<>();
            }
            for (TableRegion tableBound : crfNoLineTables) {
                String algorithmName = TableExtractionAlgorithmType.VECTOR_NAME + "_" + TableType.NoLineTable.name();
                Table nonLineTables = BitmapPageExtractionAlgorithm.chunkExtractNonLineTables(tablePage, tableBound, null);
                if (nonLineTables == null) {
                    continue;
                }
                nonLineTables.setTableType(TableType.NoLineTable);
                nonLineTables.setAlgorithmProperty(algorithmName, getVersion());
                this.nonLinesTables.add(nonLineTables);
            }
        }

        //有线表格解析
        if (!crfLineTables.isEmpty()) {
            if (this.lineTables == null) {
                this.lineTables = new ArrayList<>();
            }
            pageCells = new ArrayList<>();
            List<Rectangle> cells = new ArrayList<>(CELL_FILL_ALGORITHM.getAllCells(tablePage, crfLineTables, null, CellFillAlgorithm.PdfType.NoWordType));
            upDatePageCells(crfLineTables, cells, pageCells);
            String algorithmName = TableExtractionAlgorithmType.VECTOR_NAME + "_" + TableType.LineTable.name();
            setStructOfRulingTables(tablePage, pageCells, crfLineTables, algorithmName);
        }

        List<Table> tables = new ArrayList<>();
        if (this.lineTables != null && !this.lineTables.isEmpty()) {
            tables.addAll(this.lineTables);
        }

        if (this.nonLinesTables != null && !this.nonLinesTables.isEmpty()) {
            tables.addAll(this.nonLinesTables);
        }

        tables.sort(Comparator.comparing(Rectangle::getTop));
        TableDebugUtils.writeTables(page, tables, "json_tables");
        return tables;
    }

    private void setStructOfRulingTables(Page page, List<CandidateCell> cells, List<? extends TableRegion> tableAreas, String algorithmName) {
        List<TableMatrix> tableMatrixs = filterAreasByContext(cells, page, tableAreas, algorithmName, getVersion());
        if (!tableMatrixs.isEmpty()) {
            for (TableMatrix tmpMatrix : tableMatrixs) {
                Table table = new Table(page);
                tmpMatrix.fillTable(table);
                if (!table.isSparseTable()) {
                    table.setConfidence(tmpMatrix.getConfidence());
                    table.setBoundingFlag(tmpMatrix.isBoundingTable());
                    this.lineTables.add(table);
                }
            }
        }
    }

    private void setStructOfNonRulingTables(Page page, List<? extends TableRegion> tables, String algorithmName) {
        if (!tables.isEmpty()) {
            List<Table> tableList = new ArrayList<>();
            for (TableRegion tableArea : tables) {
                Table table = BitmapPageExtractionAlgorithm.chunkExtractNonLineTables((ContentGroupPage) page, tableArea, null);
                if (table != null && !table.isEmpty()) {
                    table.setAlgorithmProperty(algorithmName, getVersion());
                    table.setConfidence(tableArea.getConfidence());
                    table.setBoundingFlag(tableArea.isBoundingTable());
                    tableList.add(table);
                }
            }
            nonLinesTables.addAll(tableList);
        }
    }

    private List<CandidateCell> cellsContainByTable(List<CandidateCell> cells, Rectangle tableArea) {
        if (tableArea != null && cells.size() > 0) {
            return cells.stream().filter(cell -> (tableArea.nearlyContains(cell, 3.0) || tableArea.nearlyEquals(cell, 3.0)))
                    .collect(Collectors.toList());
        } else {
            return new ArrayList<>();
        }
    }

    private List<TableRegion> getClipAreaTableAreas(Page page) {
        List<TableRegion> tables = new ArrayList<>();
        List<Rectangle> cells = pageCells.stream().map(Rectangle::new).collect(Collectors.toList());
        tables.addAll(ClipAreasTableRegionDetectionAlgorithm.getTableRegions(page, cells));
        return tables;
    }

    private void upDatePageCells(List<TableRegion> tableRegions, List<Rectangle> cells, List<CandidateCell> allPageCells) {
        if (cells == null || cells.isEmpty() || tableRegions == null || tableRegions.isEmpty()) {
            return;
        }
        List<CandidateCell> regionCells = new ArrayList<>();
        for (Rectangle tmpTableRegion : tableRegions) {
            regionCells.addAll(cellsContainByTable(allPageCells, tmpTableRegion));
        }
        allPageCells.removeAll(regionCells);
        for (Rectangle cell : cells) {
            allPageCells.add(new CandidateCell(cell));
        }
    }

    private static List<CandidateCell> getPageCells(Page page, PageParam pageParam) {
        List<CandidateCell> cells = toCandidateCell(page.getClipAreas());
        //pageParam.debugDrawClips(cells);
        // TODO 下面所有子过程，考虑排序后再进行操作
        removeSameAreas(cells); //去掉相同
        filterIntersectAreas(page, cells, 1);// //去掉交叉
        filterContainerAreas(cells);//去掉包含      海通证券-170414.pdf P2T2(合并后有重叠的大单元格，已经解决)
        int basicCellHeight = getBasicCellHeight(cells);
        pageParam.setCellHeight(basicCellHeight);
        return cells;
    }

    /*
    private static void searchTablesTags(List<TableMatrix> tables, Page page) {
        if (tables.isEmpty()) {
            return;
        }
        TableMatrix prevTable = null;
        for (TableMatrix table : tables) {
            TableTitle title = searchTableTitle(table, prevTable, page);
            if (!table.isTitleSearched() || title.isBold()) {
                table.setTitle(title.getTitle());
            }
            table.setCaps(title.getCaps());
            table.setUnit(title.getUnitTextLine(), title.getUnitSet());

            prevTable = table;
        }
    }

    private static TableTitle searchTableTitle(TableMatrix table, TableMatrix prevTable, Page page) {
        int basicCellHeight = table.getBasicCellHeight();
        Rectangle area = table.getTableArea();
        Rectangle prevArea = prevTable != null ? prevTable.getTableArea() : null;

        return TableTitle.searchTableTitle(area, basicCellHeight, prevArea, page);
    }
    */


    private List<TableMatrix> filterAreasByContext(List<CandidateCell> pageCells, Page page, List<? extends TableRegion> areas, String name, String version) {
        List<TableMatrix> tables = new ArrayList<>(areas.size());
        List<TableRegion> removed = new ArrayList<>(areas.size());
        for (TableRegion area : areas) {
            TableMatrix table = TableMatrix.fillTableMatrix(page, pageCells, area, pagePara);
            if (null == table) {
                removed.add(area);
            } else {
                table.algorithmName = name;
                table.algorithmVersion = version;
                table.setConfidence(area.getConfidence());
                tables.add(table);
            }
        }
        areas.removeAll(removed);
        // 最后统一搜索标题，这样可以避免到前一张表格的区域内搜索标题
        // searchTablesTags(tables, page);
        return tables;
    }

    /*
    public boolean isHit(Page page) {
        boolean result = false;
        pagePara.pdfPath = page.pdfPath;
        pageCells = getPageCells(page, pagePara);
        if (!page.getRulingTableRegions().isEmpty() || !pageCells.isEmpty() || !page.getNoRulingTableRegions().isEmpty()
                || !page.getStructureTables().isEmpty()) {
            result = true;
        }
        return result;
    }
    */

    private static final double CJK_CELL_MARGIN_TOLERANCE = 3.5;


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

    private static List<Integer> getClipAreasParam(List<? extends Rectangle> clipAllAreas, Function<Rectangle, Integer> param) {
        List<Integer> heights_rc = new ArrayList<>();
        for (Rectangle rect : clipAllAreas) {
            heights_rc.add(param.apply(rect));
        }
        return heights_rc;
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
        return getKeyByValue(counter, maxValue);
    }

    private static List<CandidateCell> toCandidateCell(List<Rectangle2D> rcs) {
        List<CandidateCell> rectangles = new ArrayList<>(rcs.size());
        for (Rectangle2D rc : rcs) {
            rectangles.add(new CandidateCell(rc));
        }
        return rectangles;
    }


    static class TableMatrix extends TableRegion implements TableTags {

        private final CandidateCell[][] cells;
        private final int rows;
        private final int cols;
        private String title = "";
        private String caps = "";
        private String algorithmName = ALGORITHM_NAME;
        private String algorithmVersion = ALGORITHM_DATE;
        private String shoes = "";
        private String unitTextLine = "";
        private Set<String> unitSet = null;
        private int basicCellHeight;
        private Rectangle area;
        private final List<Integer> rowEdges;
        private final List<Integer> columnEdges;
        private boolean titleSearched;
        private String tag;

        TableMatrix(Rectangle area, List<Integer> tops, List<Integer> lefts) {
            super(area);
            this.rows = tops.size();
            this.cols = lefts.size();
            this.rowEdges = new ArrayList<>(tops);
            this.rowEdges.add((int) Math.ceil(area.getBottom()));
            this.columnEdges = new ArrayList<>(lefts);
            this.columnEdges.add((int) Math.ceil(area.getRight()));
            this.cells = new CandidateCell[rows][cols];
        }

        int getColumnEdge(int col) {
            return this.columnEdges.get(col);
        }

        int getRowEdge(int row) {
            return this.rowEdges.get(row);
        }


        int getRows() {
            return this.rows;
        }

        int getCols() {
            return this.cols;
        }

        void setCellArea(CandidateCell cell, int row, int col) {
            cells[row][col] = cell;
        }

        int getBasicCellHeight() {
            return basicCellHeight;
        }

        void setBasicCellHeight(int basicCellHeight) {
            this.basicCellHeight = basicCellHeight;
        }

        private Cell createEmptyCell(int row, int col) {
            int x = columnEdges.get(col);
            int y = rowEdges.get(row);
            int w = columnEdges.get(col + 1) - x;
            int h = rowEdges.get(row + 1) - y;
            return new Cell(x, y, w, h);
        }

        public String getAlgorithmName() {
            return this.algorithmName;
        }

        void fillTable(Table table) {
            Rectangle rc = this.getTableArea();
            table.setRect(rc.getBounds2D());
            table.setTitle(title, titleSearched);//有时候标题在外部设置
            table.setCaps(caps);
            table.setShoes(shoes);
            table.setUnit(unitTextLine, unitSet);
            table.setAlgorithmProperty(this.algorithmName, this.algorithmVersion);
            table.setTableType(TableType.LineTable);
            Cell cell;
            for (int row = 0; row < rows; row++) {
                for (int col = 0; col < cols; col++) {
                    final CandidateCell cellArea = cells[row][col];

                    if (null == cellArea) {
                        cell = createEmptyCell(row, col);
                        table.add(cell, row, col);
                        continue;
                    }

                    // Check merged cells
                    if (col > 0 && cellArea == cells[row][col - 1]) {
                        // Might be merged with the left cell?
                        Cell pivotCell = table.getCell(row, col - 1);
                        if (!pivotCell.containsPosition(row, col)) {
                            table.addRightMergedCell(pivotCell, row, col);
                        } else {
                            table.fillMergedCell(pivotCell, row, col);
                        }
                    } else if (row > 0 && cellArea == cells[row - 1][col]) {
                        // Might be merged with the upper cell?
                        Cell pivotCell = table.getCell(row - 1, col);
                        if (!pivotCell.containsPosition(row, col)) {
                            table.addDownMergedCell(pivotCell, row, col);
                        } else {
                            table.fillMergedCell(pivotCell, row, col);
                        }
                    } else {
                        CandidateCell candidate = cells[row][col];
                        if (candidate != null) {
                            cell = candidate.toCell();
                            table.add(cell, row, col);
                        }
                    }
                }
            }
        }


        void setTableArea(Rectangle area) {
            this.area = area;
        }

        boolean isTitleSearched() {
            return titleSearched;
        }

        Rectangle getTableArea() {
            return this.area;
        }

        @Override
        public void setTitle(String title, boolean inline) {
            this.title = title;
            this.titleSearched = inline;
        }

        @Override
        public boolean isInlineTitle() {
            return titleSearched;
        }

        public String getTitle() {
            return this.title;
        }

        public void setCaps(String caps) {
            this.caps = caps;
        }

        public String getCaps() {
            return caps;
        }

        public void setShoes(String shoes) {
            this.shoes = shoes;
        }

        public String getShoes() {
            return shoes;
        }

        public String getUnitTextLine() {
            return this.unitTextLine;
        }

        public Set<String> getUnitSet() {
            return this.unitSet;
        }

        public void setUnit(String unitTextLine, Set<String> unitSet) {
            this.unitTextLine = unitTextLine;
            this.unitSet = unitSet;
        }

        @Override
        public String getTag() {
            return tag;
        }

        @Override
        public void setTag(String tag) {
            this.tag = tag;
        }

        static boolean isTitleInArea(String title, Rectangle2D tableArea, List<CandidateCell> titleCells, Page page) {
            boolean titleIsInArea;
            float tableWidth = (float) tableArea.getWidth();
            if (titleCells.size() == 1) {
                titleIsInArea = !StringUtils.isEmpty(title)
                        && !StringUtils.containsAny(title, '：', ':')
                        && FloatUtils.feq(titleCells.get(0).getWidth(), tableWidth, CJK_CELL_MARGIN_TOLERANCE);
            } else {
                double nonEmptyCellWidths = 0.0;
                double totalWidth = .0;
                int nonEmptyCells = 0;
                for (CandidateCell cell : titleCells) {
                    double cellWidth = cell.getWidth();
                    totalWidth += cellWidth;
                    if (!StringUtils.isBlank(cell.getText())) {
                        nonEmptyCells++;
                        nonEmptyCellWidths += cellWidth;

                        if (nonEmptyCells > 1) {
                            // 有多个非空单元格，不视为标题行
                            return false;
                        }

                        Rectangle textBounds = cell.getTextBounds();
                        if (null == textBounds) {
                            // This would not happen
                            return false;
                        }

                        if (textBounds.getCenterX() - tableArea.getCenterX() > 30.) {
                            // 标题行必须左对齐或者居中对齐
                            return false;
                        }
                    }
                }
                // 文本单元格占比过小就不算标题
                titleIsInArea = nonEmptyCellWidths / totalWidth > .75;
            }
            return titleIsInArea;
        }

        static void snapEdges(List<Integer> edges, int threshold, Map<Integer, Integer> marked) {
            if (edges == null || edges.isEmpty()) {
                return;
            }
            final int orginThreshold = threshold;
            List<Integer> removed = new ArrayList<>(edges.size());
            for (int i = 1; i < edges.size(); i++) {
                int diff = edges.get(i) - edges.get(i - 1);
                if (marked != null && !marked.isEmpty()) {
                    if (marked.containsKey(edges.get(i - 1))) {
                        threshold = marked.get(edges.get(i - 1));
                    }
                }
                if (diff <= threshold) {
                    removed.add(edges.get(i - 1));
                    threshold = orginThreshold;
                }
            }
            edges.removeAll(removed);
        }

        static void confirmColumThreshold(Page page, Rectangle baseCell,
                                          List<TextElement> listElements, Integer edge, Map<Integer, Integer> linesMarked) {

            if (!listElements.isEmpty()) {
                List<TextElement> textElements = new ArrayList<>();
                List<TextElement> blankTextElements = new ArrayList<>();
                for (TextElement element : listElements) {
                    if (!element.isWhitespace()) {
                        textElements.add(element);
                    } else {
                        blankTextElements.add(element);
                    }
                }
                if (!textElements.isEmpty()) {
                    int size = textElements.size() - 1;
                    TextElement base = textElements.get(0);
                    for (int i = 1; i < textElements.size(); i++) {
                        TextElement other = textElements.get(i);
                        if (FloatUtils.feq(base.getLeft(), other.getLeft(), 1.0) &&
                                FloatUtils.feq(base.getRight(), other.getRight(), 1.0)) {
                            size--;
                        }
                    }
                    if ((0 == size) && (textElements.size() >= 1)) {
                        int widthThreshold = (int) textElements.parallelStream().mapToDouble(TextElement::getTextWidth).average()
                                .orElse(MIN_COLUMN_WIDTH);
                        linesMarked.put(edge, widthThreshold);
                    }
                } else {
                    blankTextElements.sort(Comparator.comparing(Rectangle::getLeft));
                    double left = blankTextElements.get(0).getLeft();
                    blankTextElements.sort(Comparator.comparing(Rectangle::getRight).reversed());
                    double right = blankTextElements.get(0).getRight();
                    if ((right - left) < page.getAvgCharWidth() && baseCell.getWidth() > page.getAvgCharWidth()) {
                        linesMarked.put(edge, (int) page.getAvgCharWidth());
                    } else {
                        linesMarked.put(edge, (int) (right - left));
                    }
                }
            } else {
                if (page.getAvgCharWidth() < baseCell.getWidth()) {
                    linesMarked.put(edge, (int) page.getAvgCharWidth());
                }
            }
        }

        static TableMatrix fillTableMatrix(Page page, List<CandidateCell> pageCells,
                                           Rectangle area, PageParam pagePara) {
            if (pageCells.isEmpty()) {
                return null;
            }
            List<TextChunk> textChunks = page.getTextChunks(area);
            if (textChunks.isEmpty()) {
                return null;
            }

            List<CandidateCell> tableCells = new ArrayList<>();
            Rectangle tableArea = area.withCenterExpand(5);//表格区域稍微扩大一些
            Set<Integer> leftEdges = new TreeSet<>();
            Set<Integer> topEdges = new TreeSet<>();
            Map<Integer, Integer> linesMarked = new HashMap<>();
            Map<Integer, Integer> heights = new HashMap<>();
            Integer lastEdge = 0;
            Rectangle laseCell = pageCells.get(0);
            for (Rectangle candidate : pageCells) {
                if (tableArea.contains(candidate) && candidate.getHeight() >= pagePara.cellHeight * 0.5) {
                    CandidateCell cell = new CandidateCell(candidate);
                    cell.collectText(page);
                    tableCells.add(cell);
                    boolean smallGap = false;
                    if (lastEdge != 0) {
                        if (!FloatUtils.feq(lastEdge, FastMath.round(candidate.getLeft()), 3.5)) {
                            lastEdge = FastMath.round(candidate.getLeft());
                            laseCell = candidate;
                        } else {
                            float threshold = 6.f, tmpHeight;
                            List<TextChunk> lastChunks = page.getTextChunks(laseCell);
                            List<TextChunk> currentChunks = page.getTextChunks(candidate);
                            if (!lastChunks.isEmpty()) {
                                tmpHeight = (float) lastChunks.get(0).getHeight();
                                threshold = (threshold > tmpHeight) ? tmpHeight : threshold;
                            }
                            if (!currentChunks.isEmpty()) {
                                tmpHeight = (float) currentChunks.get(0).getHeight();
                                threshold = (threshold > tmpHeight) ? tmpHeight : threshold;
                            }

                            if (laseCell.verticalDistance(candidate) > threshold) {
                                smallGap = true;
                            }
                        }
                    } else {
                        lastEdge = FastMath.round(candidate.getLeft());
                        laseCell = candidate;
                        leftEdges.add(lastEdge);
                    }
                    if (smallGap) {
                        int tmpLeftEdge = FastMath.round(candidate.getLeft());
                        linesMarked.put(lastEdge, FastMath.abs(tmpLeftEdge - lastEdge));
                        leftEdges.add(lastEdge);
                    } else {
                        leftEdges.add(lastEdge);
                        List<TextElement> textElements = page.getText(candidate);
                        confirmColumThreshold(page, candidate, textElements, lastEdge, linesMarked);
                    }
                    topEdges.add(FastMath.round(candidate.getTop()));
                    heights.merge(FastMath.round((float) candidate.getHeight()), 1, (x, y) -> x + y);
                }
            }
            int basicCellHeight;
            if (heights.size() > 0) {
                basicCellHeight = Collections.max(heights.entrySet(), Map.Entry.comparingByValue()).getKey();
            } else {
                basicCellHeight = (int) (pageCells.parallelStream().filter(tableArea::intersects)
                        .mapToDouble(Float::getHeight).average().orElse(10.0));
            }

            List<Integer> lefts = new ArrayList<>(leftEdges);

            List<Integer> tops = new ArrayList<>(topEdges);

            // 列间距不小于 MIN_COLUMN_WIDTH，避免 hb 2063808 平安证券-170414.pdf P1T1问题
            snapEdges(lefts, MIN_COLUMN_WIDTH, linesMarked);
            // 行间距不小于 MIN_ROW_HEIGHT
            snapEdges(tops, MIN_ROW_HEIGHT, null);

            // Warn:基本单元格中可能还包含子单元格（少数）
            // 如果表格只有1行，并且第一行位置笔记靠页面上半部分，这部分内容可能时续表
            //int cols = lefts.size(), rows = tops.size();
            //if (cols < 2 || (rows < 2 && (100 < tops.get(0) && tops.get(0) < 680)))
            //   return null;

            //判断首行单元格是否是标题
            String tableTitle = null;
            String tableShoes = "";
            boolean titleIsInArea = false;
            if (tops.size() > 1) {
                //第一行
                List<CandidateCell> firstRow = new ArrayList<>();
                StringBuilder sb = new StringBuilder();
                int firstRowTop = tops.get(0);

                for (CandidateCell cell : tableCells) {
                    if ((int) cell.getTop() == firstRowTop) {
                        firstRow.add(cell);
                        sb.append(cell.getText());
                    }
                }

                String title = sb.toString();

                titleIsInArea = isTitleInArea(title, area, firstRow, page);
                if (titleIsInArea) {
                    tableTitle = title;
                }
            }

            //最后一行也有可能为说明性文字
            if (tops.size() > 1) {
                List<CandidateCell> lastRow = new ArrayList<>();
                for (CandidateCell cell : tableCells) {
                    if (cell.getTop() == tops.get(tops.size() - 1)) {
                        lastRow.add(cell);
                    }
                }
                if (lastRow.size() == 1) {
                    CandidateCell cell = lastRow.get(0);
                    String footerText = cell.getText();
                    if (TableUtils.isTableFooter(footerText)) {
                        tableShoes = footerText;
                    }
                }
            }

            int cols = lefts.size();
            int rows = tops.size();


            TableMatrix table = new TableMatrix(area, tops, lefts);

            // TODO 应该用类似空间索引的方式查找矩形？这个 n^3 的算法太糟糕
            for (int row = 0; row < rows; row++) {
                // TableMatrix 已经补了表格的下边线和右边线，所以这里不用担心越界
                double dy = (table.getRowEdge(row + 1) - table.getRowEdge(row)) * 0.5;
                for (int col = 0; col < cols; col++) {
                    double dx = (table.getColumnEdge(col + 1) - table.getColumnEdge(col)) / 2;
                    for (CandidateCell cell : tableCells) {
                        if (FastMath.ceil(cell.getRight()) < lefts.get(col)
                                || FastMath.ceil(cell.getBottom()) < tops.get(row)) {
                            continue;
                        }
                        int left = lefts.get(col) + (int) dx;
                        int top = tops.get(row) + (int) dy;
                        if (cell.contains(left, top)) {  //+1使点位于矩形内
                            table.setCellArea(cell, row, col);
                            // 不应该存在多个单元格区域属于包含同一行列编号的情况。
                            break;
                        }
                    }
                }
            }

            //table.setTableCells(rc_cells);//表格区域单元格（非矩阵结构），为mongodb做准备避免重复计算
            table.setTableArea(area);
            table.setRect(area.getLeft(), area.getTop(), area.getWidth(), area.getHeight());
            table.setBasicCellHeight(basicCellHeight);

            table.setShoes(tableShoes);
            if (titleIsInArea) {
                table.setInlineTitle(tableTitle);
            }

            return table;
        }

    }

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

    private static void removeSameAreas(List<? extends Rectangle> clipAllAreas) {
        int oldSize, loops = 16;
        do {
            oldSize = clipAllAreas.size();
            removeRectangleIf(clipAllAreas, (one, other) -> one.nearlyEquals(other, 1.0f));
        } while (clipAllAreas.size() != oldSize && loops-- > 0);
    }


    private static <T extends Rectangle> void filterContainerAreas(List<T> rectangles) {
        List<T> toRemoves = new ArrayList<>(rectangles.size());
        for (int i = 0; i < rectangles.size() - 1; i++) {
            T rc1 = rectangles.get(i);
            for (int j = i + 1; j < rectangles.size(); j++) {
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


    //解决中银国际-170412.pdf文档P6异常（存在Cell交叉）
    private static void filterIntersectAreas(Page page, List<CandidateCell> clipAllAreas, int epsilon) {
        ArrayList<CandidateCell> removed = new ArrayList<>(clipAllAreas.size());
        for (int i = 0; i < clipAllAreas.size() - 1; i++) {
            CandidateCell cell1 = clipAllAreas.get(i);
            int cell1TextLength = cell1.getOrCollectText(page).trim().length();
            for (int j = i + 1; j < clipAllAreas.size(); j++) {
                CandidateCell cell2 = clipAllAreas.get(j);
                //过滤交叉Cell 内容短的删除
                if (cell1.nearlyAlignAndIntersects(cell2, epsilon, 6)) {
                    int cell2TextLength = cell1.getOrCollectText(page).trim().length();
                    if (cell1TextLength < cell2TextLength) {
                        removed.add(cell1);
                    } else if (cell1TextLength > cell2TextLength) {
                        removed.add(cell2);
                    } else {
                        if (cell1.getWidth() * cell1.getHeight() < cell2.getWidth() * cell2.getHeight()) {
                            removed.add(cell1);
                        } else {
                            removed.add(cell2);
                        }
                    }
                }
            }
        }

        clipAllAreas.removeAll(removed);
    }

}