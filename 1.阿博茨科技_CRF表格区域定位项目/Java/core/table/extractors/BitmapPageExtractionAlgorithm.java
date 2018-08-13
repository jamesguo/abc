package com.abcft.pdfextract.core.table.extractors;

import com.abcft.pdfextract.core.model.*;
import com.abcft.pdfextract.core.table.*;
import com.abcft.pdfextract.core.table.detectors.RulingTableRegionsDetectionAlgorithm;
import com.abcft.pdfextract.core.table.TableRegion;
import com.abcft.pdfextract.core.util.DebugHelper;
import com.abcft.pdfextract.spi.algorithm.AlgorithmGrpcClient;
import com.abcft.pdfextract.spi.algorithm.ImageClassifyResult;
import com.abcft.pdfextract.spi.algorithm.ImageDetectResult;
import com.abcft.pdfextract.spi.algorithm.ImageType;
import com.abcft.pdfextract.util.FloatUtils;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.protobuf.ByteString;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.imageio.ImageIO;
import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;


public class BitmapPageExtractionAlgorithm implements ExtractionAlgorithm {

    public static final String  ALGORITHM_NAME = TableExtractionAlgorithmType.BITMAP_NAME;
    public enum ALGORITHM_TYPE { SCAN, CHUNK }

    private static final Logger logger = LogManager.getLogger(BitmapPageExtractionAlgorithm.class);

    private static final int TABLE_DETECT_DPI = 108;
    private static AlgorithmGrpcClient algClient = null;
    private static final RulingTableRegionsDetectionAlgorithm RULING_TABLE_DETECTOR = new RulingTableRegionsDetectionAlgorithm();
    private static final CellFillAlgorithm CELL_FILL_ALGORITHM = new CellFillAlgorithm();

    private static final Pattern UNDER_LINE_PREFIX = Pattern.compile("(\\s*)(_{3,}|\\-{5,})(\\s*)(_{3,}|\\-{5,})?(\\s*)");

    private static final Pattern TEXT_LINE_PATTERN = Pattern.compile(".*(\\.|。|;|；)");
    // 特殊文本行，对中文中常见的关键字
    private static final Pattern SPECIAL_LINE_CJK = Pattern.compile("(.*[\\s])?(单位|注|\\(续\\)|列示)[\\s：:].*");
    // 特殊文本行，对英文中常见的关键字
    private static final Pattern SPECIAL_LINE_ENG = Pattern.compile("(.*(as follows|the table below)[.:]?$)|(^Table[\\s]?\\d{1,2}.*)");
    private static final Pattern PAGE_LINE_PATTERN = Pattern.compile("([0-9]*)|目录|表\\d+[:]?.*");
    // 序号标题： 如 6.2 xxxxxxxxxx
    private static final Pattern SERIAL_NUMBER_TITLE = Pattern.compile("^([1-2]?[0-9][\\.]?|[1-2]?[0-9]\\.[1-9]|[1-2]?[0-9]\\.[1-9]\\.[1-9])[\\s]{1,}[^\\d\\.][^\\d\\.]{10,}");
    private static final Pattern TEXT_DOLLAR_UNIT = Pattern.compile("\\s*(\\$)\\s*");
    private static final Pattern CONTAIN_NUMBER_PREFIX = Pattern.compile(".*\\d+.*");

    private static final List<String> TD_TYPES = Arrays.asList("TH", "TD", "Th", "Td");

    public static void setAlgorithmGrpcClient(AlgorithmGrpcClient client) {
        algClient = client;
    }

    public static AlgorithmGrpcClient getAlgorithmGrpcClient() {
        return algClient;
    }

    @Override
    public String toString() {
        return ALGORITHM_NAME;
    }

    @Override
    public String getVersion() {
        return "0.5";
    }

    @Override
    public List<? extends Table> extract(Page page) {
        List<Table> tables = new ArrayList<>();

        if (!(page instanceof ContentGroupPage)) {
            page = ContentGroupPage.fromPage(page);
        }

        addExtractTables((ContentGroupPage)page, tables);
        return tables;
    }

    private void extractTables(ContentGroupPage page, List<Table> tables) {
        List<Table> extraTable = new ArrayList<>();

        List<TableRegion> lineTables = page.getRulingTableRegions();
        if (!lineTables.isEmpty()) {
            for (TableRegion tableBound : lineTables) {
                Table table = rulingParser(page, tableBound);
                if (table != null) {
                    table.setTableType(TableType.LineTable);
                    //extractTableTags(page, table);
                    extraTable.add(table);
                }
            }
        }

        List<TableRegion> nonLineTables = page.getNoRulingTableRegions();
        if (!nonLineTables.isEmpty()) {
            for (TableRegion tableBound : nonLineTables) {
                Table table = chunkParser(page, tableBound, null);
                if (table != null) {
                    table.setTableType(TableType.NoLineTable);
                    // extractTableTags(page, table);
                    extraTable.add(table);
                }
            }
        }

        for (Table table : extraTable) {
            fillTableCell(table);
            tables.add(table);
        }
    }

    public void addExtractTables(ContentGroupPage page, List<Table> tables) {
        if (algClient == null) {
            logger.error("AlgorithmGrpcClient is not set");
            return;
        }

        BufferedImage pageImage = null;
        try {
            pageImage = DebugHelper.getOneShotPageImage(page.getPDPage(), TABLE_DETECT_DPI);
        } catch (IOException e) {
            logger.warn("can't getOneShotPageImage");
        }
        if (pageImage == null) {
            return;
        }
        List<ImageDetectResult> imgRects = detectTables(pageImage);
        TableDebugUtils.writeImage(page,
                imgRects.stream()
                        .map(t->t.getPageRect(DebugHelper.DEFAULT_DPI / (float) TABLE_DETECT_DPI))
                        .collect(Collectors.toList()), "Detect_Tables_src" );
        imgRects = getExtraRectangles(page, imgRects, tables);
        TableDebugUtils.writeImage(page,
                imgRects.stream()
                        .map(t->t.getPageRect(DebugHelper.DEFAULT_DPI / (float) TABLE_DETECT_DPI))
                        .collect(Collectors.toList()), "Detect_Tables_extra" );

        // 根据矢量信息将位图检测结果分为正常矢量表格和扫描件位图表格
        List<ImageDetectResult> chunkRects = new ArrayList<>();
        List<ImageDetectResult> bitmapRects = new ArrayList<>();
        for (ImageDetectResult r : imgRects) {
            List<TextChunk> textChunks = page.getTextChunks(new Rectangle(r.pageBound));
            if (textChunks.isEmpty()) {
                bitmapRects.add(r);
            } else {
                chunkRects.add(r);
            }
        }

        // TODO: 暂时不开启处理扫描件
        List<Table> extraTable = new ArrayList<>();
        /*
        // 对扫描件位图表格使用RPC服务调用位图解析
        for (ImageDetectResult r: bitmapRects) {
            try {
                Table table = bitmapExtractTable(page, pageImage, r);
                if (table != null) {
                    table.setAlgorithmProperty(ALGORITHM_NAME + "_" + ALGORITHM_TYPE.SCAN, getVersion().replace(".", ""));
                    extraTable.add(table);
                }
            } catch (IOException e) {
                logger.warn("bitmap-scan : extractTable error");
            }
        }
        */

        // 对矢量表格采用矢量数据分析解析
        chunkRects = correctImageRects(page, chunkRects);
        TableDebugUtils.writeImage(page,
                chunkRects.stream()
                        .map(t->t.getPageRect(DebugHelper.DEFAULT_DPI / (float) TABLE_DETECT_DPI))
                        .collect(Collectors.toList()), "Detect_Tables_correct" );

        for (ImageDetectResult r: chunkRects) {
            try {
                Rectangle tableBound = new Rectangle(r.pageBound);
                Table table = chunkExtractTable(page, tableBound);
                if (table != null) {
                    table.setAlgorithmProperty(ALGORITHM_NAME + "_" + ALGORITHM_TYPE.CHUNK, getVersion().replace(".", ""));
                    extraTable.add(table);
                }
            } catch (IOException e) {
                logger.warn("bitmap-chunk : extractTable error");
            }
        }

        for (Table table : extraTable) {
            fillTableCell(table);
            tables.add(table);
        }
    }

    private static void fillTableCell(Table table) {
        int maxRows = table.getRowCount();
        int maxCols = table.getColumnCount();
        for (int r = 0; r < maxRows; r++) {
            for (int c = 0; c < maxCols; c++) {
                Cell cell = table.getCell(r, c);
                if (cell.isEmpty()) {
                    table.add(cell, r, c);
                } else {
                    int colSpan = cell.getColSpan();
                    int rowSpan = cell.getRowSpan();
                    int colIdx = cell.getCol();
                    int rowIdx = cell.getRow();
                    if (colSpan > 1) {
                        for (int i = 1; i < colSpan; i++) {
                            table.fillMergedCell(cell, rowIdx, colIdx+i);
                        }
                    }
                    if (rowSpan > 1) {
                        for (int i = 1; i < rowSpan; i++) {
                            for (int j = 0; j < colSpan; j++) {
                                table.fillMergedCell(cell, rowIdx+i, colIdx+j);
                            }
                        }
                    }
                }
            }
        }
    }

    public static ByteString getFormatedImageData(BufferedImage image) throws IOException {
        return getFormatedImageData(image, "png");
    }

    public static ByteString getFormatedImageData(BufferedImage image, String fmt) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean r = ImageIO.write(image, fmt, out);
        if (!r) {
            logger.warn("ImageIO.write(image, \"" + fmt + "\", out); error");
            return null;
        }
        return ByteString.copyFrom(out.toByteArray());
    }

    /**
     * 位图算法抽取表格
     **/
    private Table bitmapExtractTable(ContentGroupPage page, BufferedImage pageImage, ImageDetectResult rectangle) throws IOException {
        BufferedImage tableImage = pageImage.getSubimage(
                (int) rectangle.imgBound.getMinX(), (int) rectangle.imgBound.getMinY(),
                (int) rectangle.imgBound.getWidth(), (int) rectangle.imgBound.getHeight()
        );
        ByteString imageData = getFormatedImageData(tableImage);
        int tableType = bitmapClassify(imageData);

        JsonArray parsed = algClient.bitmapTableParse(imageData, tableType, true);
        if (parsed == null) {
            logger.warn("bitmapTableParse get no result with tableType=0");
            return null;
        }
        if (parsed.size() == 0 || parsed.get(0).getAsJsonArray().size() == 0) {
            logger.warn("Empty result or invalid tableType(=0) in bitmapTableParse.");
            return null;
        }

        AffineTransform pageToImageTransform = new AffineTransform();
        float scale = (float) TABLE_DETECT_DPI / DebugHelper.DEFAULT_DPI;
        pageToImageTransform.scale(scale, scale);
        pageToImageTransform.translate(-rectangle.pageBound.getMinX(), -rectangle.pageBound.getMinY());
        AffineTransform imageToPageTransform;
        try {
            imageToPageTransform = pageToImageTransform.createInverse();
        } catch (NoninvertibleTransformException e) {
            throw new RuntimeException(e);
        }

        Table table = new Table(page);
        table.x = (float) rectangle.pageBound.getMinX();
        table.y = (float) rectangle.pageBound.getMinY();
        table.width = (float) rectangle.pageBound.getWidth();
        table.height = (float) rectangle.pageBound.getHeight();
        for (JsonElement rowsArray: parsed) {
            JsonArray cellArray = rowsArray.getAsJsonArray().getAsJsonArray();
            for (JsonElement cellElement: cellArray) {
                JsonObject cellObject = cellElement.getAsJsonObject();
                int x = cellObject.get("x").getAsInt();
                int y = cellObject.get("y").getAsInt();
                int w = cellObject.get("w").getAsInt();
                int h = cellObject.get("h").getAsInt();
                int rowIdx = cellObject.get("row_idx").getAsInt();
                int colIdx = cellObject.get("col_idx").getAsInt();
                // maybe empty!
                int mergeRight = cellObject.get("merge_right").getAsInt();
                int mergeDown = cellObject.get("merge_down").getAsInt();
                Rectangle cellBounds = new Rectangle(imageToPageTransform.createTransformedShape(new Rectangle2D.Float(x, y, w, h)).getBounds2D());
                Cell tableCell = new Cell((float) cellBounds.getMinX(), (float) cellBounds.getMinY(),
                        (float) cellBounds.getWidth(), (float) cellBounds.getHeight());
                float delta = (float) Math.min(3, Math.min(cellBounds.getWidth(), cellBounds.getHeight()));
                cellBounds.centerExpand(-delta); // 向内缩小
                tableCell.setTextElements(page.getTextChunks(cellBounds));
                if (mergeRight > 0) {
                    tableCell.setColSpan(mergeRight + 1);
                }
                if (mergeDown > 0) {
                    tableCell.setRowSpan(mergeDown + 1);
                }
                table.add(tableCell, rowIdx, colIdx);
            }
        }
        return table;
    }

    /**
     * 矢量算法抽取表格
     **/
    private Table chunkExtractTable(ContentGroupPage page, Rectangle tableBound) throws IOException {
        boolean isRulingTable = RULING_TABLE_DETECTOR.isLineTable(page, tableBound);

        Table table;
        if (isRulingTable) {
            // negateWordFillCell(Page page, List<Rectangle> clipAeras)
            // TableMatrix fillTableMatrix(Page page, List<CandidateCell> pageCells, Rectangle area, PageParam pagePara)
            // TODO: 根据之前的处理流程，遗漏到此处的有线表格概率很小，暂时使用无线解析算法，后续优化有线表格算法或者开发新的有线表格解析算法
            logger.warn("bitmapTableParse get a ruling table, parser with textChunk!");
            table = chunkParser(page, tableBound, null);
            if (table == null) {
                return null;
            }
            table.setTableType(TableType.LineTable);
        } else {
            table = chunkParser(page, tableBound, null);
            if (table == null) {
                return null;
            }
            table.setTableType(TableType.NoLineTable);
        }

        // 最后统一搜索标题，防止搜索到前一张表的标题
        // extractTableTags(page, table);
        return table;
    }

    public static void extractTableTags(ContentGroupPage page, Table table) {
        // 从表格顶部区域的上方和下发来查找标题
        int basicCellHeight = (int) (table.getHeight() / table.getRowCount());
        // TODO 考虑排除上一张表格的区域
        TableTitle title = TableTitle.searchTableTitle(table, basicCellHeight, null,  page);
        String tableTitle = title.getTitle();
        String tableCaps = title.getCaps();
        String tableShoes = "";

        // 如果已经有标题了就不需要设置了
        if (table.getTitle() == null) {
            table.setTitle(tableTitle);
        }
        table.setCaps(tableCaps);
        table.setShoes(tableShoes);
        table.setUnit(title.getUnitTextLine(), title.getUnitSet());
    }

    public static Table chunkExtractNonLineTables(ContentGroupPage page, Rectangle rectangle, List<TextBlock> structLines) {

        Table table;
        table = chunkParser(page, rectangle, structLines);
        if (table == null) {
            return null;
        }
        table.setTableType(TableType.NoLineTable);

        // 查找标题和脚注
        // extractTableTags(page, table);

        fillTableCell(table);

        return table;
    }

    public static boolean isTableRegion(ContentGroupPage page, Rectangle rectangle, List<TextBlock> textLines) {
        if (textLines == null || textLines.isEmpty()) {
            return false;
        }
        
        // 单元格多行分布统计
        int totalCellNum = 0;
        int comboCellNum = 0;
        int comboCellACC = 0;

        // 行单元格对齐状态
        int alignLine = 0;

        TextBlock lastLine = null;
        for (TextBlock textLine: textLines) {
            List<TextChunk> lineCells = textLine.getElements();

            for (TextChunk cell: lineCells) {
                totalCellNum += 1;

                Rectangle cellRect = new Rectangle(cell.getBounds());
                TextElement firstElement = cell.getFirstElement();
                List<TextChunk> cellChunks = page.getTextChunks(cellRect.rectReduce(firstElement.getTextWidth()*0.5,
                        firstElement.getTextHeight()*0.5, page.getWidth(), page.getHeight()));

                if (!cellChunks.isEmpty()) {
                    comboCellACC += Math.pow(2.0f, (double) (cellChunks.size() - 1)); //对多行单元格根据个数增加权重
                }
                comboCellNum += cellChunks.size() > 1 ? 1 : 0;

            }

            lastLine = textLine;
        }

        // 多行单元格占比单元格总数大于阈值，且文本行数与单元格数比例大于阈值，则认为是正文区域
        // 逻辑的假设前提是：表格中大多数单元格为多行，且总行数与单元格比例偏差较大时，很可能是正文，无线表格不会出现这种布局
        List<TableRegion> lineTableRegions = page.getLineTableRegions();
        boolean hasCrossLineTable = false;
        for (TableRegion tr : lineTableRegions) {
            Rectangle2D intersectBounds = tr.createIntersection(rectangle);
            float a0 = (float) (intersectBounds.getWidth() * intersectBounds.getHeight());
            float a1 = (float) (rectangle.getWidth() * rectangle.getHeight());
            if (a0 / a1 > 0.2) {
                hasCrossLineTable = true;
            }
        }

        if ((float)comboCellACC / (float)totalCellNum > 7 && (float)comboCellNum / (float)totalCellNum > 0.5 && !hasCrossLineTable) {
            return false;
        }

        return true;
    }


    // 对位图检测结果进行过滤无效区域、修正表格边界
    private List<ImageDetectResult> correctImageRects(ContentGroupPage page, List<ImageDetectResult> detectRects) {
        // 准备一份副本，在对某个结果修正时，其他的结果作为参考
        List<ImageDetectResult> backupRects = new ArrayList<>(detectRects);
        List<ImageDetectResult> newRects = new ArrayList<>();

        for (ImageDetectResult rect: detectRects) {
            backupRects.remove(rect); //将当前待分析区域排除，只结合其他结果做参考
            ImageDetectResult correctRect = correctImageRect(page, rect, backupRects);
            if (correctRect != null) {
                newRects.add(correctRect);
                backupRects.add(correctRect);
            }
        }
        newRects = mergeRectangles(page, newRects);
        if (!detectRects.isEmpty() && detectRects.size() != newRects.size()) {
            logger.info(String.format("Page %d: bitmapTableParse has detect %d table and keep %d table finally!", page.getPageNumber(), detectRects.size(), newRects.size()));
        }
        return newRects;
    }

    private ImageDetectResult correctImageRect(ContentGroupPage page, ImageDetectResult detectRect, List<ImageDetectResult> otherDetects) {
        float imgToPageScale = DebugHelper.DEFAULT_DPI / (float) TABLE_DETECT_DPI;
        Rectangle pageRect = new Rectangle(detectRect.getPageRect(imgToPageScale));
        Rectangle newPageRect = correctRectByChunks(page, pageRect);
        if (newPageRect == null) {
            return null;
        }

        List<Rectangle> otherRects = otherDetects.stream()
                .map(t->new Rectangle(t.getPageRect(imgToPageScale)))
                .collect(Collectors.toList());

        List<Ruling> horizontalRulings = page.getHorizontalRulings();
        List<Ruling> noRepRulings = extractSingleRuling(horizontalRulings);

        List<TextBlock> regionLines = correctImageRectBottom(page, newPageRect, noRepRulings, otherRects);
        Rectangle correctBounds = correctImageRectTop(page, regionLines, noRepRulings, otherRects);

        if (correctBounds == null) {
            return null;
        }

        ImageDetectResult newResult = new ImageDetectResult(detectRect);
        newResult.setNewPageRect(correctBounds);
        return newResult;
    }

    // 对位图检测结果，初步使用区域内的文本块进行修正
    private static Rectangle correctRectByChunks(ContentGroupPage page, Rectangle pageRect) {
        List<TextChunk> textChunks = page.getMutableTextChunks(pageRect);
        if (textChunks.isEmpty()) {
            return null;
        }
        // 将所有的空白字符去除
        filterUselessChunk(textChunks);

        // 通过overlap分析每个TextChunk是否应该保留在rect中，做初步筛除
        // 避免位图检查结果IOU偏差，拉入了相邻分栏区域的文本
        List<TextChunk> removeChunks = new ArrayList<>();
        double ratioThresh = 3./4.;
        for (TextChunk textChunk: textChunks) {
            // 文本与区域的重叠小于自身的0.2,或者文本纳入后使区域宽度增加了30%，则认为是可疑的，暂时不纳入
            if (textChunk.selfOverlapRatio(pageRect) < 0.2 ||
                    pageRect.getWidth() / textChunk.createUnion(pageRect).getWidth() < ratioThresh ||
                    Objects.equals(textChunk.getText().trim(), "•")) {
                removeChunks.add(textChunk);
            }
        }
        textChunks.removeAll(removeChunks);

        // 如果全部文本都可疑，则降低条件再过滤一边
        if (textChunks.isEmpty()) {
            ratioThresh = 2./3.;
            for (TextChunk removeChunk: removeChunks) {
                // 文本与区域的重叠小于自身的0.1,或者文本纳入后使区域宽度增加了30%，则认为是可疑的，暂时不纳入
                if (removeChunk.selfOverlapRatio(pageRect) >= 0.1 &&
                        pageRect.getWidth() / removeChunk.createUnion(pageRect).getWidth() >= ratioThresh) {
                    textChunks.add(removeChunk);
                }
            }

        }

        if (textChunks.isEmpty()) {
            return null;
        } else {
            return Rectangle.boundingBoxOf(textChunks);
        }
    }

    //标题行跨多列判断
    private boolean isOverlapMultiColumn(List<Rectangle>columns, TextBlock line) {
        Rectangle lineBound = new Rectangle(line.getBounds2D());
        int overlapCount = 0;
        // 如果一列的内容太少不算一列
        for (Rectangle column : columns) {
            if (lineBound.isHorizontallyOverlap(column) && column.getWidth() >= lineBound.getHeight() * 2) {
                overlapCount++;
            }
        }
        return overlapCount > 1;
    }

    private enum direct {
        TOP, BOTTOM, LEFT, RIGHT, CENTER
    }

    private boolean isNearByBound(Rectangle rect, TextBlock line, direct side) {
        Rectangle lineBound = new Rectangle(line.getBounds2D());
        double textGap = line.getLastTextChunk().getHeight();

        switch (side) {
            case LEFT:
                return Math.abs(lineBound.getLeft() - rect.getLeft()) < textGap * 3;
            case RIGHT:
                return Math.abs(lineBound.getRight() - rect.getRight()) < textGap * 3;
            case CENTER:
                return Math.abs(lineBound.getCenterX() - rect.getCenterX()) < textGap * 3;
        }
        return false;
    }

    // 判断行是否是正文，考虑textBlock结构
    private boolean isTextLine(Rectangle rect, TextBlock line) {
        int lineSize = line.getElements().size();
        TextChunk lastChunk = line.getLastTextChunk();
        String lineText = line.getText().trim();
        double fontSize = lastChunk.getMostCommonTextWidth();

        boolean lineStartLeft = isNearByBound(rect, line, direct.LEFT);
        boolean lineCenter = isNearByBound(rect, line, direct.CENTER);

        boolean textsLine = lineStartLeft && lineSize == 1 &&
                (lastChunk.getWidth() > rect.getWidth() * 4/5 || TEXT_LINE_PATTERN.matcher(lineText).matches());
        boolean specialLine = SPECIAL_LINE_CJK.matcher(lineText).matches() || SPECIAL_LINE_ENG.matcher(lineText).matches();
        boolean pageLine = PAGE_LINE_PATTERN.matcher(lineText).matches();
        boolean serialNumberTitle = (lineSize <= 2 && SERIAL_NUMBER_TITLE.matcher(lineText).matches()) ||
                (lineSize <= 3 && SERIAL_NUMBER_TITLE.matcher(lineText).matches() && Objects.equals(lastChunk.getText().trim(), "continued"));

        // 正文有可能是单位行，有可能是正文，有可能是注释
        // 正文以标点结尾的特征是左边顶头，chunk最后可能是一个空格, 单位行也许居中
        if (textsLine || specialLine || serialNumberTitle) {
            return true;
        } else if (lineSize == 1) {
            return pageLine;
        } else if (lineSize > 1) {
            // 多列判断列间隙
            List<TextChunk> textChunks = line.getElements();
            TextChunk oneChunk = textChunks.get(0);
            if (lineSize == 2) {
                // 常见于脚注或上下文存在排序文本，表现形式为排序符后加一长串文本
                TextChunk otherChunk = textChunks.get(1);
                if ((TextMerger.TEXT_SUPER_SCRIPT.matcher(oneChunk.getText().trim()).matches() || oneChunk.getText().trim().length()==1) &&
                        otherChunk.getWidth() > rect.getWidth() * 4/5) {
                    return true;
                }
            }

            double textWide = oneChunk.getWidth();
            boolean textAdjacent  = false;
            for (int i = 1; i < textChunks.size(); i++) {
                TextChunk nextChunk = textChunks.get(i);
                // 以两个字符为判断列间隙是否较大
                boolean isSameLine = oneChunk.verticalOverlap(nextChunk) > Math.min(oneChunk.getTextHeight(), nextChunk.getTextHeight()) * 0.5;
                if (isSameLine && nextChunk.getLeft() - oneChunk.getRight() < fontSize * 1.5f) {
                    textAdjacent = true;
                }

                if (oneChunk.horizontalOverlap(nextChunk) > fontSize) {
                    // 存在垂直重合，则去除前一个文本宽，加合并宽
                    textWide += oneChunk.createUnion(nextChunk).getWidth() - oneChunk.getWidth();
                } else {
                    textWide += nextChunk.getWidth();
                }
                oneChunk = nextChunk;
            }
            boolean textsLineWide = textWide > rect.getWidth() * 4/5;
            return textAdjacent && textsLineWide;
        }
        return false;
    }

    private boolean isTitleLine(List<Rectangle>columns, Rectangle rect, List<Ruling> noRepRulings,
                                TextBlock checkLine, TextBlock currLine, boolean onTop) {
        Rectangle checkLineBound = new Rectangle(checkLine.getBounds2D());
        Rectangle currLineBound = new Rectangle(currLine.getBounds2D());
        List<TextChunk> checkChunks = checkLine.getElements();
        List<TextChunk> currChunks = currLine.getElements();
        TextChunk lastChunk = checkLine.getLastTextChunk();
        Ruling splitRuling = getNearBySingleRuling(noRepRulings, checkLineBound.getBottom(), direct.BOTTOM);

        boolean lineStartLeft = isNearByBound(rect, checkLine, direct.LEFT);
        boolean lineEndRight = isNearByBound(rect, checkLine, direct.RIGHT);
        boolean lineCenter = isNearByBound(rect, checkLine, direct.CENTER);
        boolean overlapColumns = isOverlapMultiColumn(columns, checkLine);
        boolean existHeaderRuling = splitRuling != null && splitRuling.getTop() < currLineBound.getTop();

        // 标题行 靠左，行高较大，但也有可能是两行叠起来的结果，扩多列
        // 页眉行1列靠右居中，2列左右对齐
        if (lineStartLeft) {
            if (checkChunks.size() == 1) {
                boolean fontLarge = checkLineBound.getHeight() > currLineBound.getHeight() * 1.5
                        && checkLineBound.getHeight() < currLineBound.getHeight() * 2;
                boolean validColumn = lastChunk.getWidth() > lastChunk.getHeight() * 3;
                return fontLarge || overlapColumns || (existHeaderRuling && validColumn);
            } else if (checkChunks.size() == 2) {
                boolean blankInCenter = checkLine.getLastTextChunk().getLeft()
                        - checkLine.getFirstTextChunk().getRight() >= rect.getWidth() * 1/3;

                return lineEndRight && blankInCenter && existHeaderRuling;
            }
            return false;
        }  else if (lineEndRight || lineCenter) {
            if (checkChunks.size() >= 3 || checkChunks.size() > currChunks.size() ||
                    (currChunks.size() > 1 && checkChunks.size() == currChunks.size())) {
                return false;
            }

            // TODO: 需要限定范围
            boolean fontSmall = checkLineBound.getHeight() < currLineBound.getHeight() * 0.8f;
            return (overlapColumns && checkChunks.size() == 1)  || fontSmall || existHeaderRuling;
        }
        return false;
    }

    // 提取出一行内只有一条的ruling
    //TODO：未考虑分栏的情况
    private List<Ruling> extractSingleRuling(List<Ruling> horizontalRulings) {
        float repY = 0;
        List<Ruling> singleRulings = new ArrayList<>();
        for (int i = 0; i < horizontalRulings.size(); i++ ) {
            Ruling oneRuling = horizontalRulings.get(i);
            if (i != horizontalRulings.size() - 1){
                Ruling nextRuling = horizontalRulings.get(i + 1);
                if (oneRuling.getTop() == nextRuling.getTop()) {
                    repY = oneRuling.getTop();
                    continue;
                }
            }
            if (oneRuling.getTop() != repY) {
                singleRulings.add(oneRuling);
            }
        }
        return singleRulings;
    }

    // 获取边界最近的单条ruling（区别向上或向下）
    private Ruling getNearBySingleRuling(List<Ruling> singleRulings, float bound, direct side) {
        if (singleRulings.isEmpty()) {
            return null;
        }
        Ruling firstRuling = singleRulings.get(0);
        Ruling lastRuling = singleRulings.get(singleRulings.size() - 1);
        Ruling nearByRuling = null;
        // bond和ruling区域相交
        if (firstRuling.getTop() < bound && lastRuling.getTop() > bound) {
            for (int i = 0; i < singleRulings.size() - 1; i++) {
                Ruling oneRuling = singleRulings.get(i);
                Ruling nextRuling = singleRulings.get(i + 1);
                if (oneRuling.getTop() <= bound && nextRuling.getTop() >= bound) {
                    nearByRuling = side.equals(direct.TOP) ? oneRuling : nextRuling;
                }
            }
        } else {
            switch (side) {
                case TOP:
                    nearByRuling = firstRuling.getTop() > bound ? null : lastRuling;
                    break;
                case BOTTOM:
                    nearByRuling = firstRuling.getTop() > bound ? firstRuling : null;
                    break;
            }
        }
        return nearByRuling;
    }

    // 处理矩形区域内的行
    private List<TextBlock> rectToLines(ContentGroupPage page, Rectangle rect, String region) {
        List<TextChunk> textChunks = page.getMutableTextChunks(rect);
        if (textChunks.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        // 将所有的空白字符去除
        filterUselessChunk(textChunks);

        List<TextBlock> textBlocks = TextMerger.groupByBlock(textChunks, page.getHorizontalRulings(), page.getVerticalRulings());
        List<TextBlock> lines = TextMerger.collectByRows(textBlocks);
        lines.sort(Comparator.comparing(TextBlock::getCenterY));
        if (region.equals("searchArea")) {
            return lines;
        }
        // 判断lines是不是表格，如果不是不用进行修正
        Rectangle textBound = Rectangle.boundingVisibleBoxOf(textChunks);
        float widthThresh = (float) textBound.getWidth() * 0.7f;
        int count = 0;
        for (TextBlock line : lines) {
            if (1 == line.getElements().size() && line.getVisibleWidth() > widthThresh) {
                count++;
            }
        }

        if (count > lines.size() * 0.5) {
            logger.info("no column in table, skip, ");
            return java.util.Collections.emptyList();
        }

        return lines;
    }
    private int getIndex(List<TextBlock> lines, int i, direct side) {
        return side.equals(direct.TOP) ? i : lines.size() - 1 - i;
    }

    private boolean canRemove(TextBlock currLine, TextBlock nextLine, Rectangle rect, List<Rectangle> columns, direct side) {
        if (isTextLine(rect, currLine)) {
            return true;
        }

        // 行高较大,行不顶头则直接跳出，不进行缩减
        if (currLine.getHeight() > currLine.getLastTextChunk().getHeight() * 1.5) {
            return false;
        }
        // 单列继续缩减，多列跳出
        //if (currLine.getElements().size() < 2 ) {
        //    return true;
        //}

        // 判断行的列数差，并判断当前行是否从第一列就开始
        boolean isRemove = false;
        boolean largeColumnsDiffer = Math.abs(nextLine.getElements().size() - currLine.getElements().size()) > 1;
        boolean lineStartOnLeft = columns.get(0).isHorizontallyOverlap(currLine.getElements().get(0));
        double rowGap = side.equals(direct.TOP) ?
                nextLine.getVisibleMinY() - currLine.getVisibleMaxY() : currLine.getVisibleMinY() - nextLine.getVisibleMaxY();
        double rowHeiht = Math.min(currLine.getVisibleHeight(), nextLine.getVisibleHeight());
        if (largeColumnsDiffer && lineStartOnLeft && nextLine.getElements().size() != 1 && rowGap > rowHeiht*3) {
            isRemove =  true;
        }
        return isRemove;
    }

    // 缩小矩形区域
    private Rectangle shinkRect(List<TextBlock> lines, Rectangle rect, List<Rectangle> columns, direct side) {
        // 首先对范围过大的矩形进行缩小，从下往上判断行
        // 如果连续移去3行，则疑似算法问题，不进行缩减，直接对矩形扩大进行搜索
        boolean shinkValid = true;
        List<TextBlock> removeLines = new ArrayList<>();

        // 最多检测表格的一半
        int loopMax = (int) Math.rint(lines.size() * 0.5);
        for (int i = 0; i < loopMax; i++) {
            if (removeLines.size() == 3) {
                shinkValid = false;
                break;
            }

            TextBlock currLine = lines.get(getIndex(lines, i, side));
            TextBlock nextLine = lines.get(getIndex(lines, i + 1, side));
            if (!canRemove(currLine, nextLine, rect, columns, side)) {
                break;
            }
            removeLines.add(currLine);
        }

        if (shinkValid && !removeLines.isEmpty()) {
            lines.removeAll(removeLines);
        }
        return Rectangle.union(lines);
    }

    // 集合ruling信息建立搜索区域
    private Rectangle createSearchRect(List<TextBlock> lines, List<Ruling> noRepRulings, Rectangle tableBounds, direct side) {
        boolean onTop = side.equals(direct.TOP);
        TextBlock oneLine = onTop ? lines.get(0) : lines.get(lines.size() - 1);
        Rectangle oneLineBound = new Rectangle(oneLine.getBounds2D());
        float bound = onTop ? tableBounds.getTop() : tableBounds.getBottom();
        double lineHeight = oneLineBound.getHeight();

        // 将搜索区域缩减2是因为不将原来的行加入到搜索区域中
        float searchHeight = Math.min((float) tableBounds.getHeight()*0.5f, (float) lineHeight * 3.f);
        float searchAreaTop;
        if (onTop) {
            searchAreaTop = tableBounds.getTop() - searchHeight -2;
        } else {
            searchAreaTop = tableBounds.getBottom() + 2;
        }
        Ruling nearRuling = getNearBySingleRuling(noRepRulings, bound, side);
        if (!noRepRulings.isEmpty() && nearRuling != null) {
            float boundRulingDist = Math.abs(bound - nearRuling.getTop());
            // 搜索范围如果太大也不可靠, ruling和bound离的太近就直接取bound
            if (boundRulingDist < lineHeight * 15 && boundRulingDist > lineHeight * 1.5){
                searchAreaTop = onTop
                        ? nearRuling.getTop()
                        : tableBounds.getBottom() + 2;
                searchHeight = onTop
                        ? boundRulingDist - 2
                        : nearRuling.getTop() - tableBounds.getBottom() + (float)lineHeight;
            }
        }
        return new Rectangle(tableBounds.getLeft(), searchAreaTop, (float) tableBounds.getWidth(), searchHeight);
    }

    private int countValidChunkNum(TextBlock textBlock) {
        int validNum = 0;
        List<TextChunk> textChunks = textBlock.getElements();
        for (TextChunk textChunk: textChunks) {
            String text = textChunk.getText().trim();
            // 排除掉美元符号($)
            if (text.length() == 1) {
                if (TEXT_DOLLAR_UNIT.matcher(text).matches()) {
                    continue;
                }
            }
            validNum += 1;
        }
        return validNum;
    }

    private int alignColsNum(List<Rectangle> currLine, List<Rectangle> referLine) {
        int hitColsNum = 0;
        for (Rectangle currRect: currLine) {
            int alignNum = 0;
            for (Rectangle referRect : referLine) {
                if (currRect.horizontallyOverlapRatio(referRect) > 0.5) {
                    alignNum += 1;
                }
            }
            if (alignNum == 1) {
                hitColsNum += 1;
            }
        }
        return hitColsNum;
    }

    private boolean hasAllAlignCols(List<Rectangle> currLine, List<Rectangle> referLine) {
        return currLine.size() == alignColsNum(currLine, referLine);
    }


    // 判断搜索区域的内容需不需要加入原区域
    private List<TextBlock> expandRect(Rectangle tableBounds,  List<TextBlock> lines, List<Rectangle> columns,
                                       List<TextBlock> searchLines, List<Ruling> noRepRulings, List<Rectangle> otherRects,
                                       direct side) {
        boolean onTop = side.equals(direct.TOP);
        TextBlock oneLine = onTop ? lines.get(0) : lines.get(lines.size() - 1);
        // 顶部修正进入到这里，lines.size()一定大于1
        TextBlock nextLine;
        int singleChunkLineCount = 0;
        if (lines.size() == 1) {
            nextLine = oneLine;
            singleChunkLineCount = oneLine.getSize() == 1 ? 1 : 0;
        } else {
            nextLine = onTop ? lines.get(1) : lines.get(lines.size() - 2);
            singleChunkLineCount = (oneLine.getSize() == 1 ? 1 : 0) + (nextLine.getSize() == 1 ? 1 : 0);
        }

        List<Rectangle> oneRects = oneLine.getElements().stream().map(t->new Rectangle(t.getBounds())).collect(Collectors.toList());
        List<Rectangle> nextRects = nextLine.getElements().stream().map(t->new Rectangle(t.getBounds())).collect(Collectors.toList());
        List<TextBlock> beMergedLines = new ArrayList<>(); // 被合并融合的行
        for (int i = 0; i < searchLines.size(); i++) {
            TextBlock checkLine = onTop ? searchLines.get(searchLines.size() - i - 1) : searchLines.get(i);
            Rectangle checkBound = new Rectangle(checkLine.getBounds2D());
            List<TextChunk> checkChunks = checkLine.getElements();
            if (!otherRects.isEmpty() && otherRects.stream().anyMatch(t->t.overlapRatio(checkBound) > 0)) {
                // 如果当前行与其他结果区域有重合，则终止搜索
                break;
            }
            List<Rectangle> checkRects = checkChunks.stream().map(t->new Rectangle(t.getBounds())).collect(Collectors.toList());

            boolean doubleLine = checkBound.getHeight() > checkLine.getLastTextChunk().getHeight() * 1.5;
            boolean textLine = isTextLine(tableBounds, checkLine);
            boolean titleLine = isTitleLine(columns, tableBounds, noRepRulings, checkLine, oneLine, onTop);

            if (textLine || doubleLine) {
                break;
            }

            // 判断待合并行与参考行的距离
            float baseRowHeight = (float) oneLine.getLastTextChunk().getHeight();
            float gapBetweenRow = 0.f, gapWithCheck = 0.f;
            if (onTop) {
                gapBetweenRow = (float) (nextLine.getTop() - oneLine.getBottom());
                gapWithCheck = (float) (oneLine.getTop() - checkLine.getBottom());
            } else {
                gapBetweenRow = (float) (oneLine.getTop() - nextLine.getBottom());
                gapWithCheck = (float) (checkLine.getTop() - oneLine.getBottom());
            }

            // 如果行间距大于行高，则表格行是间隔比较远的类型，则通过行间距的一定倍数范围限制过滤
            if (gapBetweenRow > baseRowHeight) {
                if (gapWithCheck > gapBetweenRow * 1.5) {
                    break;
                }
            } else {
                // 行高大于行间距，则表格行是比较紧邻的类型，则通过行高的一定倍数范围分析
                if (gapWithCheck > baseRowHeight * 2.5) {
                    break;
                }
                if (gapWithCheck > baseRowHeight * 1.5) {
                    int alignCount = Math.max(alignColsNum(checkRects, oneRects), alignColsNum(checkRects, nextRects));
                    if (alignCount < checkRects.size()*0.75 || checkRects.size() <= 2) {
                        break;
                    }
                }

            }

            // 判断checkLine中的文本是否存在异常超出表格区域
            boolean isOutRegion = false;
            for (TextChunk textChunk: checkChunks) {
                if (textChunk.horizontalOverlap(tableBounds) < textChunk.getWidth()*0.5) {
                    isOutRegion = true;
                    break;
                }
            }
            if (isOutRegion) {
                break;
            }

            boolean canMerge = false;
            if (checkChunks.size() == 1) {
                // 标题行判断，对行高进行判断，如果在中间的可能不是标题
                // TODO:根据靠近第一行行高判断需不需要加入有问题
                boolean lineStartLeft = isNearByBound(tableBounds, checkLine, direct.LEFT);
                if (!titleLine && (!lineStartLeft || onTop)) {
                    canMerge = true;
                }
                // 修正上方的时候，限制单chunk行最多两行
                if (onTop && singleChunkLineCount >= 2) {
                    canMerge = false;
                }
            } else {
                // 针对英文中$符号经常占用一列，先排除掉单独的$文本块，再来对比行之间的列数差异
                int checkValidCount = countValidChunkNum(checkLine);
                int oneValidCount = countValidChunkNum(oneLine);
                int nextValidCount = countValidChunkNum(nextLine);
                boolean smallColumnsDiffer = Math.abs(checkValidCount - oneValidCount) <= 1
                        || Math.abs(checkValidCount - nextValidCount) <= 1;

                // 不在第一列, 且当前行的列数小于表格区域列数
                boolean lineStartNotOnLeft = checkChunks.size() <= columns.size() &&
                        !columns.isEmpty() && !columns.get(0).isHorizontallyOverlap(checkChunks.get(0));

                // 存在列对齐
                boolean hasAllAlign = false;
                if (checkValidCount >= 3 && (oneValidCount >= checkValidCount || nextValidCount >= checkValidCount)) {
                    hasAllAlign = hasAllAlignCols(checkRects, oneRects) || hasAllAlignCols(checkRects, nextRects);
                }

                if (!titleLine && (smallColumnsDiffer || lineStartNotOnLeft || hasAllAlign)) {
                    canMerge = true;
                }

                if (canMerge) {
                    // 对列数相等的情况，验证当前行的文本块是否存在和表格列一一对应
                    if (checkChunks.size() == columns.size()) {
                        for (TextChunk textChunk: checkChunks) {
                            boolean hasAlign =  false;
                            for (Rectangle col: columns) {
                                if (textChunk.horizontalOverlap(col) > 0) {
                                    hasAlign = true;
                                    break;
                                }
                            }
                            if (!hasAlign) {
                                canMerge = false;
                                break;
                            }
                        }
                    }
                }
            }
            if (canMerge) {
                lines.add(checkLine);
                tableBounds.merge(checkBound);
                beMergedLines.add(checkLine);

                // 更新参考行索引
                nextLine = oneLine;
                oneLine = checkLine;
                if (checkLine.getSize() == 1) {
                    singleChunkLineCount += 1;
                }
            } else {
                break;
            }
        }

        // 基于全局信息判断被融合的行是否有效，如果存在无效行，则在结果中去除
        List<TextBlock> inValidLines = checkInvalidMerge(lines, beMergedLines, side);
        if (!inValidLines.isEmpty()) {
            lines.removeAll(inValidLines);
        }
        return lines;
    }

    private List<TextBlock> checkInvalidMerge(List<TextBlock> allLines, List<TextBlock> beMergedLines, direct side) {
        if (beMergedLines.isEmpty()) {
            return Collections.emptyList();
        }
        boolean onTop = side.equals(direct.TOP);
        List<TextBlock> inValidLines = new ArrayList<>();

        // 对下边界融合的行分析
        if (!onTop) {
            int beginIdx = allLines.size() - 1;
            int endinIdx = allLines.size() - beMergedLines.size();
            for (int i = beginIdx; i <= endinIdx && i>=1; i--) {
                TextBlock currLine = allLines.get(i);
                TextBlock nextLine = allLines.get(i-1);
                if (nextLine.getSize() > 8 && currLine.getSize() == 1 &&
                        !CONTAIN_NUMBER_PREFIX.matcher(currLine.getText()).matches()) {
                    // 当前行为单文本块，且不包含数值。上一行为较多列，则当前行很可能为错误合并行
                    inValidLines.add(currLine);
                }
            }
        }
        //TODO: 对上边界校验的时候，需注意beMergedLines里的顺序针对表上方来说是反的

        return inValidLines;
    }

    // 修正矩形底部的范围
    private List<TextBlock> correctImageRectBottom(ContentGroupPage page, Rectangle pageRect,
                                                   List<Ruling> noRepRulings, List<Rectangle> otherRects) {
        List<TextBlock> lines = rectToLines(page, pageRect, "correctArea");
        if (lines.isEmpty()) {
            return java.util.Collections.emptyList();
        }

        List<Rectangle> columns = calcColumnPositions(page, lines);
        // 列区间切分结果为空，则不做后续分析修正处理
        // 考虑文本存在旋转(page.getTextRotate() != 0)
        if (columns.isEmpty()) {
            return lines;
        }

        pageRect = Rectangle.union(lines);
        Rectangle tableBounds = shinkRect(lines, pageRect, columns, direct.BOTTOM);
        Rectangle searchArea = createSearchRect(lines, noRepRulings, tableBounds, direct.BOTTOM);
        List<TextBlock> searchLines = rectToLines(page, searchArea, "searchArea");
        if (searchLines.isEmpty()) {
            // 搜索区域为空返回缩减后的区域
            return lines;
        }

        return expandRect(tableBounds, lines, columns, searchLines, noRepRulings, otherRects, direct.BOTTOM);
    }

    // 修正顶部区域
    private Rectangle correctImageRectTop(ContentGroupPage page, List<TextBlock> lines,
                                          List<Ruling> noRepRulings, List<Rectangle> otherRects) {
        if (lines.isEmpty()) {
            logger.info("no column in table, skip.");
            return null;
        }

        List<Rectangle> columns = calcColumnPositions(page, lines);
        Rectangle bottomCorrectBounds = Rectangle.union(lines);
        // 列区间切分结果为空，则不做后续分析修正处理
        if (columns.isEmpty()) {
            return bottomCorrectBounds;
        }

        Rectangle tableBounds = shinkRect(lines, bottomCorrectBounds, columns, direct.TOP);

        if (lines.size() == 1) {
            return isTextLine(bottomCorrectBounds ,lines.get(0)) ? null : Rectangle.union(lines);
        }

        Rectangle searchArea = createSearchRect(lines, noRepRulings, tableBounds, direct.TOP);
        List<TextBlock> searchLines = rectToLines(page, searchArea, "searchArea");
        if (searchLines.isEmpty()) {
            return tableBounds;
        }
        List<TextBlock> correctLines = expandRect(tableBounds, lines, columns, searchLines, noRepRulings, otherRects, direct.TOP);
        return Rectangle.union(correctLines);
    }


    /*
    * 清除无效文本块
    * */
    private static void filterUselessChunk(List<TextChunk> textChunks) {
        // 去除空白字符文本块和下划线文本块
        if (textChunks != null && !textChunks.isEmpty()) {
            for (int i = 0; i < textChunks.size();) {
                TextChunk textChunk = textChunks.get(i);
                if (StringUtils.isBlank(textChunk.getText().replace('\u00a0', ' ')) ||
                        UNDER_LINE_PREFIX.matcher(textChunk.getText()).matches()) {
                    textChunks.remove(i);
                } else {
                    // 清除textChunk前后的空白无效字符
                    textChunk.trim();
                    i++;
                }
            }
        }
    }

    /*
     * 清除左右侧边栏文本块
     * */
    private static void filterSideBarRow(List<TextBlock> textRows) {
        // 过滤旋转方向的可疑文本
        List<TextBlock> rotateRows = textRows.stream()
                .filter(t->t.getElements().get(0).getDirection() == TextDirection.VERTICAL_DOWN ||
                        t.getElements().get(0).getDirection() == TextDirection.VERTICAL_UP ||
                        t.getElements().get(0).getDirection() == TextDirection.ROTATED)
                .collect(Collectors.toList());
        if (rotateRows.isEmpty()) {
            return;
        }

        if (rotateRows.size() <= 3 && rotateRows.size() < textRows.size()) {
            List<TextChunk> allChunks = textRows.stream().map(TextBlock::getElements).flatMap(Collection::stream).collect(Collectors.toList());
            List<Rectangle> rowBounds = textRows.stream().map(t->new Rectangle(t.getBounds2D())).collect(Collectors.toList());
            rowBounds.sort(Comparator.comparing(Rectangle::getLeft));
            Rectangle leftRect = rowBounds.get(0);
            rowBounds.sort(Comparator.comparing(Rectangle::getRight).reversed());
            Rectangle rightRect = rowBounds.get(0);

            Rectangle wholeBound = Rectangle.boundingBoxOf(rowBounds);
            List<TextBlock> removeRows = new ArrayList<>();
            for (TextBlock rotateRow: rotateRows) {
                Rectangle rotateBound = new Rectangle(rotateRow.getBounds2D());
                if (rotateBound.overlapRatio(leftRect) > 0.95 || rotateBound.overlapRatio(rightRect) > 0.95) {
                    Rectangle tempRegion = Rectangle.fromLTRB(rotateBound.getLeft(), wholeBound.getTop(), rotateBound.getRight(), wholeBound.getBottom());
                    List<TextChunk> noRotateChunks = allChunks.stream()
                            .filter(t->t.overlapRatio(tempRegion) > 0 &&
                                    (t.getDirection() != TextDirection.VERTICAL_DOWN &&
                                            t.getDirection() != TextDirection.VERTICAL_UP &&
                                            t.getDirection() != TextDirection.ROTATED))
                            .collect(Collectors.toList());
                    if (noRotateChunks.isEmpty()) {
                        removeRows.add(rotateRow);
                    }

                }
            }
            if (!removeRows.isEmpty()) {
                textRows.removeAll(removeRows);
            }
        }
    }


    private static List<Float> calcSplitFromRulings(List<Ruling> rulings) {
        List<Float> splits = new ArrayList<>();
        for (Ruling line : rulings) {
            boolean isMerger = false;
            for (int i = 0; i < splits.size(); i++) {
                float idx = splits.get(i);
                if (FloatUtils.within(line.getPosition(), idx, 5f)) {
                    splits.set(i, (idx + line.getPosition()) / 2);
                    isMerger = true;
                    break;
                }
            }
            if (!isMerger) {
                splits.add(line.getPosition());
            }
        }
        return splits;
    }


    private static List<Ruling> getMatchRuling(Ruling baseRuling, List<Ruling> allRulings) {
        List<Ruling> matchLines = new ArrayList<>();
        float iNearThresh = (baseRuling.getEnd() - baseRuling.getStart()) * 0.5f;
        float iMaxNear = -1000000;
        Ruling maxNearLine = new Ruling(-1, -1, 0, 0);
        for (Ruling line : allRulings) {
            if (Objects.equals(baseRuling.getDirection(), line.getDirection()) &&
                    FloatUtils.within(baseRuling.getPosition(), line.getPosition(), 5f)) {
                float iNear;
                if (baseRuling.horizontal()) {
                    iNear = Math.min(baseRuling.getRight(), line.getRight()) - Math.max(baseRuling.getLeft(), line.getLeft());

                } else {
                    iNear = Math.min(baseRuling.getBottom(), line.getBottom()) - Math.max(baseRuling.getTop(), line.getTop());
                }

                if (iMaxNear < iNear) {
                    iMaxNear = iNear;
                    maxNearLine = line;
                }
                if (iNear > iNearThresh) {
                    matchLines.add(line);
                }
            }
        }

        if (matchLines.isEmpty()) {
            matchLines.add(maxNearLine);
        }
        return matchLines;
    }

    // 根据水平直线分析基准行高间距
    private static void analyseRowHeight(List<Ruling> horizontalRulings, Rectangle tableBound) {

    }


    private static Table rulingParser(ContentGroupPage page, Rectangle tableBound) {
        List<Ruling> h_lines = Ruling.getRulingsFromArea(page.getHorizontalRulings(), tableBound);
        List<Ruling> v_lines = Ruling.getRulingsFromArea(page.getVerticalRulings(), tableBound);

        List<Float> splitRows = calcSplitFromRulings(h_lines);
        List<Float> splitCols = calcSplitFromRulings(v_lines);

        // 根据行列分割信息切分单元格
        List<List<CandidateCell>> allCells = new ArrayList<>();
        for (int h0 = 0, h1 = 1; h1 < splitRows.size(); h0++, h1++) {
            float upsideY = splitRows.get(h0);
            float bottomY = splitRows.get(h1);

            List<CandidateCell> rowCells = new ArrayList<>();
            for (int v0 = 0, v1 = 1; v1 < splitCols.size(); v0++, v1++) {
                float leftX = splitCols.get(v0);
                float rightX= splitCols.get(v1);

                Ruling fitLineUpside = new Ruling(upsideY, leftX, rightX - leftX, 0);
                Ruling fitLineBottom = new Ruling(bottomY, leftX, rightX - leftX, 0);
                Ruling fitLineLeft = new Ruling(upsideY, leftX, 0, bottomY - upsideY);
                Ruling fitLineRight = new Ruling(upsideY, rightX, 0, bottomY - upsideY);
                List<Ruling> matchLinesUpside = getMatchRuling(fitLineUpside, h_lines);
                List<Ruling> matchLinesBottom = getMatchRuling(fitLineBottom, h_lines);
                List<Ruling> matchLinesLeft = getMatchRuling(fitLineLeft, v_lines);
                List<Ruling> matchLinesRight = getMatchRuling(fitLineRight, v_lines);
                Ruling upsideLine = matchLinesUpside.size() == 1 ? matchLinesUpside.get(0) : fitLineUpside;
                Ruling bottomLine = matchLinesBottom.size() == 1 ? matchLinesBottom.get(0) : fitLineBottom;
                Ruling leftLine = matchLinesLeft.size() == 1 ? matchLinesLeft.get(0) : fitLineLeft;
                Ruling rightLine = matchLinesRight.size() == 1 ? matchLinesRight.get(0) : fitLineRight;

                CandidateCell cell = new CandidateCell(leftX, upsideY, rightX - leftX, bottomY - upsideY);
                cell.setCanMergeUpside(upsideLine.horizontallyOverlapRatio(fitLineUpside) < 0.5);
                cell.setCanMergeBottom(bottomLine.horizontallyOverlapRatio(fitLineBottom) < 0.5);
                cell.setCanMergeLeft(leftLine.verticallyOverlapRatio(fitLineLeft) < 0.5);
                cell.setCanMergeRight(rightLine.verticallyOverlapRatio(fitLineRight) < 0.5);
                cell.setRowIdx(h0);
                cell.setColIdx(v0);

                rowCells.add(cell);
            }
            allCells.add(rowCells);
        }

        // 根据candidata cell进行合并
        Table table = new Table(page);
        int iRows = allCells.size();
        for (int row = 0; row < iRows; row++) {
            List<CandidateCell> rowCells = allCells.get(row);
            int iCols = rowCells.size();

            for (int col = 0; col < iCols; col++) {
                CandidateCell candidateCell = rowCells.get(col);
                int shiftRow = 0, shiftCol = 0;
                while (candidateCell.getCanMergeRight()) {
                    shiftCol += 1;
                    if (col + shiftCol >= iCols) {
                        candidateCell.setCanMergeRight(false);
                        shiftCol -= 1;
                    } else {
                        candidateCell.setCanMergeRight(allCells.get(row).get(col + shiftCol).getCanMergeRight());
                    }
                }
                while (candidateCell.getCanMergeBottom()) {
                    shiftRow += 1;
                    if (row + shiftRow >= iRows) {
                        candidateCell.setCanMergeBottom(false);
                        shiftRow -= 1;
                    } else {
                        candidateCell.setCanMergeBottom(allCells.get(row + shiftRow).get(col).getCanMergeBottom());
                    }
                }

                // 根据单元格边界信息进行合并处理
                if (shiftRow > 0 || shiftCol > 0) {
                    for (int i = 0; i <= shiftCol; i++) {
                        candidateCell.merge(allCells.get(row).get(col + i));
                        allCells.get(row).set(col + i, candidateCell);
                    }
                    for (int j = 1; j <= shiftRow; j++) {
                        for (int i = 0; i <= shiftCol; i++) {
                            candidateCell.merge(allCells.get(row + j).get(col + i));
                            allCells.get(row + j).set(col + i, candidateCell);
                        }
                    }
                }

                // 填充表格（行列信息与原始不对应，则表示被合并，不填充输出）
                if (candidateCell.getRowIdx() == row && candidateCell.getColIdx() == col) {
                    Cell cell = candidateCell.toCell();

                    Rectangle cellBounds = new Rectangle(candidateCell.getBounds());
                    cell.setTextElements(page.getTextChunks(cellBounds.rectReduce(5,5,page.getWidth(),page.getHeight())));
                    if (shiftCol > 0) {
                        cell.setColSpan(shiftCol + 1);
                    }
                    if (shiftRow > 0) {
                        cell.setRowSpan(shiftRow + 1);
                    }
                    table.add(cell, row, col);
                }
            }
        }
        return table;
    }

    private static List<TextChunk> removeOverlapTextChunks(List<TextChunk> chunks, Rectangle area) {
        List<TextChunk> cleanChunks = new ArrayList<>(chunks.size());
        for (TextChunk chunk : chunks) {
            Rectangle2D bound = area.createIntersection(chunk.getBounds2D());
            if (bound.getHeight() > 0.3 * chunk.getAvgCharHeight() && bound.getWidth() > 0.3 * chunk.getAvgCharWidth()) {
                cleanChunks.add(chunk);
            }
        }
        return cleanChunks;
    }

    // 对textLines预处理
    private static List<TextBlock> preProcessTextLines(ContentGroupPage page, List<TextBlock> srcTextLines) {
        if (srcTextLines == null || srcTextLines.isEmpty()) {
            return srcTextLines;
        }
        float minCharWidth = page.getMinCharWidth();
        float minCharHeight = page.getMinCharHeight();
        float deltaCharHeight = minCharHeight * 0.8f;

        // 合并存在重合的文本行
        List<List<TextChunk>> mergeTextLine = new ArrayList<>();
        List<Rectangle> mergeLineBounds = new ArrayList<>();
        for (TextBlock textBlock: srcTextLines) {
            Rectangle currLineBound = new Rectangle(textBlock.getBounds2D());
            List<TextChunk> currLineChunks = textBlock.getElements();

            List<Integer> mergeIdx = new ArrayList<>();
            for (int i = 0; i < mergeLineBounds.size(); i++) {
                Rectangle baseLineBound = mergeLineBounds.get(i);
                if (Rectangle.verticalOverlap(currLineBound, baseLineBound) > Math.max(deltaCharHeight, 2.f)) {
                    mergeIdx.add(i);
                }
            }
            if (mergeIdx.isEmpty()) {
                mergeLineBounds.add(currLineBound);
                mergeTextLine.add(currLineChunks);
            } else {
                Rectangle firstMergeBound = mergeLineBounds.get(mergeIdx.get(0));
                List<TextChunk> firstMergeLine = mergeTextLine.get(mergeIdx.get(0));

                // 将当前行融合进重合的合并基准行中
                Rectangle.union(firstMergeBound, currLineBound, firstMergeBound);
                firstMergeLine.addAll(currLineChunks);

                if (mergeIdx.size() >= 2) {
                    // 将其他也存在重合的基准行都合并到第一个合并基准行中，然后去除
                    List<Rectangle> otherMergeBounds = new ArrayList<>();
                    List<List<TextChunk>> otherMergeLines = new ArrayList<>();
                    for (int j = 1; j < mergeIdx.size(); j++) {
                        Rectangle otherMergeBound = mergeLineBounds.get(mergeIdx.get(j));
                        List<TextChunk> otherMergeLine = mergeTextLine.get(mergeIdx.get(j));
                        Rectangle.union(firstMergeBound, otherMergeBound, firstMergeBound);
                        firstMergeLine.addAll(otherMergeLine);

                        otherMergeBounds.add(otherMergeBound);
                        otherMergeLines.add(otherMergeLine);
                    }
                    mergeLineBounds.removeAll(otherMergeBounds);
                    mergeTextLine.removeAll(otherMergeLines);
                }
            }
        }

        List<TextBlock> dstTextLines = new ArrayList<>();
        for (List<TextChunk> textChunks: mergeTextLine) {
            textChunks.sort(Comparator.comparing(TextChunk::getVisibleMinX));
            if (textChunks.size() <= 1) {
                dstTextLines.add(new TextBlock(textChunks));
                continue;
            }

            // 同一行的TextChunk，合并存在重叠的Chunk
            List<TextChunk> mergeChunks = new ArrayList<>();
            TextChunk lastChunk = null;
            for (TextChunk currChunk : textChunks) {
                boolean isNewChunk = true;
                if (lastChunk != null) {
                    float minDeltaVisibleHeight = (float) Math.min(lastChunk.getVisibleHeight(), currChunk.getVisibleHeight()) * 0.8f;

                    if (Rectangle.verticalOverlap(lastChunk.getVisibleBBox(), currChunk.getVisibleBBox()) > Math.min(deltaCharHeight, minDeltaVisibleHeight) &&
                            Rectangle.horizontalOverlap(lastChunk.getVisibleBBox(), currChunk.getVisibleBBox()) > minCharWidth) {
                        // 处理视觉上同一个TextChunk重复出现的情况（bound相同，内容相同）
                        if (lastChunk.nearlyEquals(currChunk, 0.1f) &&
                                Objects.equals(lastChunk.getText().trim(), currChunk.getText().trim())) {
                            continue;
                        }
                        lastChunk.merge(currChunk);
                        isNewChunk = false;
                    }
                }

                if (isNewChunk) {
                    lastChunk = new TextChunk(currChunk);
                    mergeChunks.add(lastChunk);
                }
            }

            mergeChunks.sort(Comparator.comparing(TextChunk::getGroupIndex));
            dstTextLines.add(new TextBlock(mergeChunks));
        }

        return dstTextLines;
    }

    private static List<TextChunk> collectMissStructChunk(List<TextChunk> regionChunks, List<TextBlock> structLines) {
        List<TextChunk> structChunks = structLines.stream().map(TextBlock::getElements)
                .flatMap(Collection::stream)
                .collect(Collectors.toList());
        List<TextChunk> missChunks = new ArrayList<>();
        if (structChunks.size() != regionChunks.size()) {
            // 可能存在结构化表格遗失文本块的情况
            // 注意：由于部分chunks可能被错误判定为页眉页脚，导致regionChunks有可能比structChunks少
            missChunks = regionChunks.stream()
                    .filter(r-> structChunks.stream().noneMatch(s->s.getMcid() == r.getMcid() || r.selfOverlapRatio(s) > 0.9))
                    .collect(Collectors.toList());

            //去除脚注等有结构化标签，但不是TD的文本块
            List<TextChunk> noUseChunks = new ArrayList<>();
            for (TextChunk t : missChunks) {
                List<String> structTypes = t.getStructureTypes();
                if (structTypes == null || structTypes.isEmpty()) {
                    continue;
                }
                if (CollectionUtils.intersection(structTypes, TD_TYPES).isEmpty()) {
                    noUseChunks.add(t);
                }
            }
            if (!noUseChunks.isEmpty()) {
                missChunks.removeAll(noUseChunks);
            }
        }
        return missChunks;
    }

    private static void fillMissStructChunk(TextBlock currRow, List<TextChunk> missChunks,
                                            List<Ruling> horizontalRulings, List<Ruling> verticalRulings) {
        if (!missChunks.isEmpty()) {
            List<TextChunk> lineChunk = currRow.getElements();
            List<TextChunk> sameRowChunks = missChunks.stream()
                    .filter(t->t.verticalOverlapRatio(new Rectangle(currRow.getBounds2D())) > 0.55)
                    .collect(Collectors.toList());
            if (!sameRowChunks.isEmpty()) {
                sameRowChunks.sort(Comparator.comparing(TextChunk::getGroupIndex));
                List<TextBlock> mergeChunks = TextMerger.groupByBlock(sameRowChunks, horizontalRulings, verticalRulings);

                for (TextBlock b: mergeChunks) {
                    List<TextChunk> bChunks = b.getElements();
                    TextChunk insertChunk = new TextChunk(bChunks.get(0));
                    if (bChunks.size() > 1) {
                        for (int c = 1; c < bChunks.size(); c++) {
                            insertChunk.merge(bChunks.get(c));
                        }
                    }

                    // 判断是否应该和已有chunk合并，如GIC样本13-1-HK MONETARY AUTHORITY-AR2016E.pdf中page73,18和8被拆成两个chunk
                    float minDistance = 1000.0f;
                    TextChunk nearestChunk = lineChunk.get(0);
                    for (TextChunk t: lineChunk) {
                        float dis = (float) (t.getVisibleMinX() < insertChunk.getVisibleMinX() ?
                                insertChunk.getVisibleMinX() - t.getVisibleMaxX() : t.getVisibleMinX() - insertChunk.getVisibleMaxX());
                        if (dis < minDistance) {
                            minDistance = dis;
                            nearestChunk = t;
                        }
                    }
                    // 不同行的不能合并
                    float gapThresh = (float) (nearestChunk.getMaxElementVisibleWidth() + insertChunk.getMaxElementVisibleWidth()) * 0.5f;
                    if (nearestChunk.verticalOverlapRatio(insertChunk) > 0.5 && minDistance < gapThresh) {
                        nearestChunk.merge(insertChunk, false);
                    } else {
                        lineChunk.add(insertChunk);
                    }
                }
                missChunks.removeAll(sameRowChunks);
            }
        }
    }

    private static boolean checkStructAvailable(ContentGroupPage page, Rectangle tableBound, List<TextBlock> structLines) {
        List<Ruling> horizontalRulings = Ruling.getRulingsFromArea(page.getHorizontalRulings(), tableBound);
        List<Ruling> verticalRulings = Ruling.getRulingsFromArea(page.getVerticalRulings(), tableBound);
        List<TextChunk> allCells = structLines.stream()
                .map(TextBlock::getElements)
                .flatMap(Collection::stream)
                .collect(Collectors.toList());

        // 1.是否存在TD被rulings贯穿，可能有错误被合并的结构化单元格
        for (TextChunk cell: allCells) {
            List<TextElement> elements = cell.getElements();
            Rectangle centralRegion = new Rectangle(cell.getVisibleBBox());
            centralRegion.centerReduce(Math.min(cell.getVisibleWidth()*0.1f, 2.0f), Math.min(cell.getVisibleHeight()*0.1f, 2.0f));
            for (Ruling hRuling: horizontalRulings) {
                if (hRuling.isHorizontallyOverlap(centralRegion) &&
                        hRuling.getCenterY() > centralRegion.getMinY() && hRuling.getCenterY() < centralRegion.getMaxY()) {
                    List<TextElement> aboveElements = new ArrayList<>();
                    List<TextElement> belowElements = new ArrayList<>();
                    for (TextElement element: elements) {
                        if (element.getCenterY() < hRuling.getCenterY()) {
                            aboveElements.add(element);
                        } else {
                            belowElements.add(element);
                        }
                    }
                    if (!aboveElements.isEmpty() && !belowElements.isEmpty()) {
                        return false;
                    }
                }
            }

            for (Ruling vRuling: verticalRulings) {
                if (vRuling.isVerticallyOverlap(centralRegion) &&
                        vRuling.getCenterX() > centralRegion.getMinX() && vRuling.getCenterX() < centralRegion.getMaxX()) {
                    List<TextElement> leftElements = new ArrayList<>();
                    List<TextElement> rightElements = new ArrayList<>();
                    for (TextElement element: elements) {
                        if (element.getCenterX() < vRuling.getCenterX()) {
                            leftElements.add(element);
                        } else {
                            rightElements.add(element);
                        }
                    }
                    if (!leftElements.isEmpty() && !rightElements.isEmpty()) {
                        return false;
                    }
                }
            }
        }

        // 2.是否存在多行的列被错误合并在一个TD中
        // 表现为结构化的一行中，每个TD都能切分成多行，且几乎行数都相同

        return true;
    }

    // 矢量解析ROI区域table
    private static Table chunkParser(ContentGroupPage page, Rectangle tableBound, List<TextBlock> structLines) {
        List<Ruling> horizontalRulings = new ArrayList<>(page.getHorizontalRulings());
        List<Ruling> verticalRulings = new ArrayList<>(page.getVerticalRulings());

        List<TextBlock> rawTextLines = null;
        boolean useStructLines = false;
        if (structLines != null && !structLines.isEmpty()) {
            List<TextChunk> missChunks = collectMissStructChunk(page.getTextChunks(tableBound), structLines);
            rawTextLines = structLines;
            for(int i = 0; i < rawTextLines.size();) {
                TextBlock currRow = rawTextLines.get(i);
                List<TextChunk> lineChunk = currRow.getElements();
                filterUselessChunk(lineChunk);
                if (lineChunk.isEmpty()) {
                    rawTextLines.remove(i);
                } else {
                    fillMissStructChunk(currRow, missChunks, horizontalRulings, verticalRulings);
                    i++;
                }
            }
            rawTextLines.sort(Comparator.comparing(TextBlock::getCenterY));
            for (TextBlock line: rawTextLines) {
                line.getElements().sort(Comparator.comparing(TextChunk::getLeft));
            }
            useStructLines = checkStructAvailable(page, tableBound, rawTextLines);
        }

        if (!useStructLines) {
            List<TextChunk> textChunks = page.getTextChunks(tableBound);
            textChunks = removeOverlapTextChunks(textChunks, tableBound);
            filterUselessChunk(textChunks);
            if (textChunks.size() == 0) {
                return null;
            }
            List<TextBlock> textBlocks = TextMerger.groupByBlock(textChunks, page.getHorizontalRulings(), page.getVerticalRulings());
            rawTextLines = TextMerger.collectByRows(textBlocks);
            filterSideBarRow(rawTextLines);
            rawTextLines.sort(Comparator.comparing(TextBlock::getCenterY));
            for (TextBlock line: rawTextLines) {
                line.getElements().sort(Comparator.comparing(TextChunk::getLeft));
            }
        }

        // 对textLines预处理
        List<TextBlock> textLines = preProcessTextLines(page, rawTextLines);

        // 分析判断是否为表格区域
        if (!isTableRegion(page, tableBound, textLines)) {
            return null;
        }

        // 根据表格内ruling切分区域
        List<Rectangle> rulingCells = CELL_FILL_ALGORITHM.getCellsFromTableRulings(page, new TableRegion(tableBound));

        // 分析表格整体结构的列区间
        List<Rectangle> columns = calcColumnPositions(page, textLines);

        // 分析表格整体结构的行区间
        List<List<Rectangle>> lineRows = new ArrayList<>();
        List<List<Ruling>> lineRulings = new ArrayList<>();
        for (TextBlock textLine : textLines) {
            // 本初始行中的rulings
            List<Ruling> rulingsInLine = new ArrayList<>();
            Rectangle2D visibleRowBBox = textLine.getVisibleBBox();
            List<TextChunk> textChunks = textLine.getElements();
            if (!visibleRowBBox.isEmpty() && !horizontalRulings.isEmpty()) {
                rulingsInLine = Ruling.getRulingsFromArea(horizontalRulings, visibleRowBBox);
            }

            // 去除无效ruling
            // 1.文本框附近的边界ruling
            // 2.贯穿文本的错误ruling
            if (!rulingsInLine.isEmpty()) {
                double minChunkHeight = textLine.getElements().stream().map(TextChunk::getVisibleHeight).min(Double::compareTo).get();
                double gapThresh = Math.max(1.0, minChunkHeight * 0.3);
                for (int i = 0; i < rulingsInLine.size();) {
                    Ruling ruling = rulingsInLine.get(i);
                    boolean isBorderLine = FloatUtils.feq(ruling.getCenterY(), visibleRowBBox.getMinY(), gapThresh) ||
                            FloatUtils.feq(ruling.getCenterY(), visibleRowBBox.getMaxY(), gapThresh);

                    boolean isIntersectLine = false;
                    if (!isBorderLine) {
                        for (TextChunk chunk: textChunks) {
                            Rectangle centralRegion = new Rectangle(chunk.getVisibleBBox());
                            centralRegion.centerReduce(Math.min(chunk.getVisibleWidth()*0.1f, 2.0f),
                                    Math.min(chunk.getVisibleHeight()*0.1f, 2.0f));
                            if (ruling.isHorizontallyOverlap(centralRegion) &&
                                    ruling.getCenterY() > centralRegion.getMinY() && ruling.getCenterY() < centralRegion.getMaxY()) {
                                isIntersectLine = true;
                                break;
                            }
                        }
                    }

                    if (isBorderLine || isIntersectLine) {
                        rulingsInLine.remove(i);
                    } else {
                        i++;
                    }
                }
            }
            lineRulings.add(rulingsInLine);

            // 分析当前行的细粒度行切分
            List<Rectangle> rows = splitBaseRows(textLine, rulingsInLine);
            lineRows.add(rows);
        }

        Table table = new Table(page);
        table.x = (float) tableBound.getMinX();
        table.y = (float) tableBound.getMinY();
        table.width = (float) tableBound.getWidth();
        table.height = (float) tableBound.getHeight();
        // 表格完整的单元格填充，包括合并单元格的填充
        Table.CellContainer fulllyCellContainer = new Table.CellContainer();

        int rowOffset = 0;  // 行累计偏移量
        TextBlock lastLine = null;
        for (int lineIdx = 0; lineIdx < textLines.size(); lineIdx++) {
            TextBlock textLine = textLines.get(lineIdx);
            List<Rectangle> rows = lineRows.get(lineIdx);
            List<Ruling> subLineRulings =  lineRulings.get(lineIdx);
            List<TextChunk> elements = textLine.getElements();

            for (TextChunk tc : elements) {
                if (TextMerger.isSameChar(tc, ContentGroupPage.WHITE_SPACE_CHARS)) {
                    continue;
                }
                float avgCharWidth = tc.getAvgCharWidth();
                float avgCharHeight = tc.getAvgCharHeight();
                double deltaCharWidth = avgCharWidth * 0.5;
                double deltaCharHeight = avgCharHeight * 0.5;

                Cell tableCell = Cell.fromTextChunk(tc);
                int rowIdx = -1, colIdx = -1;

                // ----- 查找行结构 -----
                // 查找是否存在有线单元格归属
                boolean useRulingCell = false;
                List<Rectangle> hitCells = rulingCells.stream().filter(x->x.nearlyContains(tc, 1.5)).collect(Collectors.toList());
                if (hitCells != null && hitCells.size() == 1) {
                    Rectangle hitCell = hitCells.get(0);
                    Rectangle reduceCell = new Rectangle(hitCell).rectReduce(deltaCharWidth, deltaCharHeight,
                            page.getWidth(), page.getHeight());
                    List<TextChunk> hitTexts = page.getMergeChunks(reduceCell);
                    boolean isSameTextBlock = false;
                    if (hitTexts != null && hitTexts.size() == 1) {
                        isSameTextBlock = hitTexts.get(0).nearlyEquals(tc, 1);
                    }

                    boolean isHaveLeftBorder = false;
                    for (Ruling vLine : verticalRulings) {
                        if (vLine.verticalOverlap(hitCell) > hitCell.getHeight() * 0.8 &&
                                Math.abs(vLine.getLeft() - hitCell.getLeft()) < deltaCharWidth) {
                            isHaveLeftBorder = true;
                            break;
                        }
                    }
                    boolean isHaveRightBorder = false;
                    for (Ruling vLine : verticalRulings) {
                        if (vLine.verticalOverlap(hitCell) > hitCell.getHeight() * 0.8 &&
                                Math.abs(vLine.getRight() - hitCell.getRight()) < deltaCharWidth) {
                            isHaveRightBorder = true;
                            break;
                        }
                    }
                    useRulingCell = isSameTextBlock && (isHaveLeftBorder || isHaveRightBorder);
                }

                if (useRulingCell) {
                    // 采用归属的有线单元格分析行列索引
                    Rectangle hitCell = hitCells.get(0).rectReduce(deltaCharWidth, deltaCharHeight,
                            page.getWidth(), page.getHeight());

                    // 寻找行信息
                    List<Integer> rowFoundIdx = new ArrayList<>();
                    int rowAcc = 0;
                    for (List<Rectangle> lineRow: lineRows) {
                        for (Rectangle aLineRow : lineRow) {
                            if (hitCell.isVerticallyOverlap(aLineRow)) {
                                rowFoundIdx.add(rowAcc);
                            }
                            rowAcc += 1;
                        }
                    }

                    if (!rowFoundIdx.isEmpty()) {
                        rowIdx = rowFoundIdx.get(0);
                        tableCell.setRowSpan(rowFoundIdx.size());
                    } else {
                        rowIdx = rowOffset;
                    }

                    // 寻找列信息
                    List<Integer> colFoundIdx = new ArrayList<>();
                    for (int j = 0; j < columns.size(); j++) {
                        if (hitCell.isHorizontallyOverlap(columns.get(j))) {
                            colFoundIdx.add(j);
                        }
                    }
                    if (!colFoundIdx.isEmpty()) {
                        colIdx = colFoundIdx.get(0);
                        tableCell.setColSpan(colFoundIdx.size());
                    }
                } else {
                    // 使用文本块本身分析行列索引
                    if (rows.size() >= 2 && !subLineRulings.isEmpty()) {
                        boolean hasChunkOL = false;
                        boolean hasRulingOL = false;
                        boolean hasSplitRuling = false;
                        for (TextChunk subChunk: elements) {
                            if (tc != subChunk && Rectangle.horizontalOverlap(tc.getVisibleBBox(), subChunk.getVisibleBBox()) > 0) {
                                hasChunkOL = true;
                                break;
                            }
                        }
                        for (Ruling subRuling: subLineRulings) {
                            // 判断是否为分割上下单元格的切分线
                            List<TextChunk> aboveChunks = new ArrayList<>();
                            List<TextChunk> belowChunks = new ArrayList<>();
                            for (TextChunk subChunk: elements) {
                                if (subRuling.horizontalOverlap(subChunk.getVisibleBBox()) > deltaCharWidth) {
                                    if (subChunk.getCenterY() < subRuling.getCenterY()) {
                                        aboveChunks.add(subChunk);
                                    } else {
                                        belowChunks.add(subChunk);
                                    }
                                }
                            }
                            // 上下存在文本块，才认为是多行切分ruling，避免非切分线对此单元格上下合并行的误判
                            if (aboveChunks.isEmpty() || belowChunks.isEmpty()) {
                                continue;
                            }

                            hasSplitRuling = true;
                            if (subRuling.horizontalOverlap(tc.getVisibleBBox()) > deltaCharWidth) {
                                hasRulingOL = true;
                                break;
                            }
                        }
                        // 在当前行中，当前文本块为独立列的单元格，此时存在由区域内ruling切分的细颗粒子行，则当前独立单元格为多行合并
                        if (!hasChunkOL && hasSplitRuling && !hasRulingOL) {
                            rowIdx = rowOffset;
                            tableCell.setRowSpan(rows.size());
                        }
                    }
                    if (rowIdx == -1){
                        List<Integer> rowFoundIdx = new ArrayList<>();
                        for (int j = 0; j < rows.size(); j++) {
                            if (rows.get(j).isVerticallyOverlap(new Rectangle(tc.getVisibleBBox()))) {
                                rowFoundIdx.add(j);
                            }
                        }
                        if (!rowFoundIdx.isEmpty()) {
                            rowIdx = rowOffset + rowFoundIdx.get(0);
                            tableCell.setRowSpan(rowFoundIdx.size());
                        } else {
                            rowIdx = rowOffset;
                        }
                    }

                    // ----- 查找列结构 -----
                    // 查找文本块下是否有ruling划分单元格区域
                    float extraHeight = (float) tc.getHeight() * 0.5f; // 限定搜索下划线范围
                    List<Ruling> chunkRuling = Ruling.getRulingsFromArea(horizontalRulings,
                            new Rectangle2D.Float(tc.getLeft(), tc.getTop() + extraHeight, (float) tc.getWidth(),
                                    (float) tc.getHeight()));
                    if (chunkRuling.size() == 1) {
                        Ruling re = chunkRuling.get(0);
                        extraHeight = (float) tc.getHeight() * 0.8f; // 扩大方向搜索文本的范围
                        Rectangle extraRe = new Rectangle(re.getLeft(), re.getTop() - extraHeight,
                                re.getWidth(), re.getHeight() + extraHeight);
                        List<TextChunk> reTextChunks = page.getTextChunks(extraRe);
                        filterUselessChunk(reTextChunks);

                        float centerReduce = re.getWidth()*0.3f;
                        Rectangle centerRe = new Rectangle(extraRe);
                        centerRe.setLeft(extraRe.getLeft() + centerReduce);
                        centerRe.setRight(extraRe.getRight() - centerReduce);
                        if (reTextChunks != null && reTextChunks.size() == 1 && centerRe.intersects(tc)) {
                            boolean isHeader = true;
                            Rectangle upsideRect = new Rectangle(tc).moveBy(0f, (float)-tc.getHeight());
                            List<TextChunk> upsideChunks = page.getTextChunks(upsideRect);
                            List<Ruling> upsideRulings = Ruling.getRulingsFromArea(horizontalRulings, upsideRect);
                            upsideChunks.remove(tc); //去除当前textChunk
                            if (lastLine != null && lastLine.getElements().size() > 1 && upsideChunks.size() == 1 && upsideRulings.isEmpty()) {
                                isHeader = false;
                            }

                            boolean isSubThunk = false;
                            for (TextChunk tt : elements) {
                                float overlapRatio = tt.overlapRatio(reTextChunks.get(0));
                                if (0 < overlapRatio && overlapRatio < 0.8) {
                                    isSubThunk = true;
                                    break;
                                }
                            }

                            if (isHeader && !isSubThunk) {
                                // 如果ruling上只有当前一个文本块，则认为此ruling的跨列信息作为单元格的合并
                                List<Integer> colFoundIdx = new ArrayList<>();
                                for (int j = 0; j < columns.size(); j++) {
                                    if (extraRe.horizontalOverlap(columns.get(j)) > 0.2f) {
                                        colFoundIdx.add(j);
                                    }
                                }
                                if (!colFoundIdx.isEmpty()) {
                                    colIdx = colFoundIdx.get(0);
                                    tableCell.setColSpan(colFoundIdx.size());
                                }
                            }
                        }
                    }

                    if (colIdx == -1) {
                        List<Pair<Integer, Float>> colFoundIdx = new ArrayList<>();
                        int minDisIdx = columns.size();
                        float minDistance = 10000;
                        for (int j = 0; j < columns.size(); j++) {
                            Rectangle referCol = columns.get(j);
                            float distan = tc.horizontalDistance(referCol);
                            if (minDistance > distan) {
                                minDistance = distan;
                                minDisIdx = j;
                            }
                            // 距离小于零，代表两者之间有重叠交集
                            if (distan < 0) {
                                Cell referCell = fulllyCellContainer.get(rowIdx, j);
                                if (referCell.isEmpty()) {
                                    colFoundIdx.add(Pair.of(j, tc.horizontallyOverlapRatio(referCol)));
                                } else {
                                    if (tc.selfOverlapRatio(referCol) > referCell.selfOverlapRatio(referCol)) {
                                        // 当前单元格与此列区域重合度，比上一列的单元格重合度更大，可认为更适合此列，同时调整上一个单元格的列合并信息
                                        int lastColSpan = referCell.getColSpan();
                                        referCell.setColSpan(lastColSpan - 1);
                                        colFoundIdx.add(Pair.of(j, tc.horizontallyOverlapRatio(referCol)));
                                    }
                                }
                            }
                        }

                        if (!colFoundIdx.isEmpty()) {
                            if (colFoundIdx.size() == 2) {
                                // 当覆盖列区域为两个时，如果存在某一个几乎完全覆盖，另一个覆盖率很低，则保守起见，认为是错误列合并，做修正处理
                                if (colFoundIdx.get(0).getValue() > 0.99f && colFoundIdx.get(1).getValue() < 0.1f) {
                                    colFoundIdx.remove(1);
                                } else if (colFoundIdx.get(0).getValue() < 0.1f && colFoundIdx.get(1).getValue() > 0.99f) {
                                    colFoundIdx.remove(0);
                                }
                            }
                            colIdx = colFoundIdx.get(0).getKey();
                            tableCell.setColSpan(colFoundIdx.size());
                        } else {
                            //沒有找到归属列的时候，根据空间关系寻找最近临的
                            if (fulllyCellContainer.get(rowIdx, minDisIdx).isEmpty()) {
                                colIdx = minDisIdx;
                            } else {
                                colIdx = minDisIdx + 1;
                            }
                        }
                    }
                }

                table.add(tableCell, rowIdx, colIdx);
                fulllyCellContainer.addMergedCell(tableCell);
            }
            rowOffset += rows.size();
            lastLine = textLine;
        }
        return table;
    }


    private static void splitRow(TextBlock textRow, List<TextChunk> singleCells, List<TextChunk> combineCells) {
        if (singleCells == null || combineCells == null) {
            return;
        }

        singleCells.clear();
        combineCells.clear();
        List<TextChunk> textCells = new ArrayList<>(textRow.getElements());
        for (int i=0; i<textCells.size();) {
            TextChunk cell = textCells.get(i);

            // 对旋转文本不参与水平行切分统计
            // 对TextDirection.VERTICAL_UP和TextDirection.VERTICAL_DOWN暂时保留，需注意可能对边栏的问题
            if (cell.getDirection() == TextDirection.ROTATED) {
                textCells.remove(i);
                continue;
            }

            boolean isCombine = false;
            for (int j=0; j<textCells.size(); j++) {
                if (j != i && Rectangle.verticalOverlap(cell.getVisibleBBox(), textCells.get(j).getVisibleBBox()) == 0) {
                    isCombine = true;
                    break;
                }
            }

            if (isCombine) {
                combineCells.add(cell);
                i++;
            } else {
                singleCells.add(cell);
                textCells.remove(i);
            }
        }
    }

    private static List<TextChunk> analyzeColOfLine(TextBlock textRow) {
        List<TextChunk> singleCells = new ArrayList<>();
        List<TextChunk> combineCells = new ArrayList<>();
        List<TextChunk> subCols = new ArrayList<>();
        splitRow(textRow, singleCells, combineCells);
        int maxLevel = 4;

        subCols.addAll(singleCells);
        while (!combineCells.isEmpty() && maxLevel-- > 0) {
            combineCells.sort(Comparator.comparing(TextChunk::getGroupIndex));
            List<TextBlock> Rows = TextMerger.collectByLines(combineCells);
            Rows.sort(Comparator.comparing(TextBlock::getSize).reversed());
            if (Rows == null || Rows.isEmpty()) {
                break;
            }
            TextBlock maxRow = Rows.get(0);
            splitRow(maxRow, singleCells, combineCells);
            subCols.addAll(singleCells);
        }
        subCols.sort(Comparator.comparing(Rectangle::getLeft));
        return subCols;
    }

    private static List<Rectangle> analyzeRowOfLine(TextBlock textRow) {
        List<TextChunk> singleCells = new ArrayList<>();
        List<TextChunk> combineCells = new ArrayList<>();
        List<Rectangle> subRows = new ArrayList<>();

        splitRow(textRow, singleCells, combineCells);
        if (combineCells.isEmpty()) {
            subRows.add(Rectangle.boundingVisibleBoxOf(singleCells));
        } else {
            combineCells.sort(Comparator.comparing(TextChunk::getGroupIndex));
            List<TextBlock> Rows = TextMerger.collectByLines(combineCells);
            // 融合存在重合的行
            List<TextBlock> mergeLines = new ArrayList<>();
            List<Rectangle> mergeLineBounds = new ArrayList<>();
            Rows.sort(Comparator.comparing(textBlock -> textBlock.getBounds2D().getMinY()));
            for (int i=0; i<Rows.size(); i++) {
                TextBlock textLine = Rows.get(i);
                Rectangle rectLine = new Rectangle(textLine.getBounds2D());
                float avgCharHeight = textLine.getAvgCharHeight();

                boolean isMerge = false;
                for (int j=0; j<mergeLineBounds.size(); j++) {
                    TextBlock baseTextLine = mergeLines.get(j);
                    Rectangle baseRectLine = mergeLineBounds.get(j);

                    if (rectLine.verticalOverlap(baseRectLine) > avgCharHeight*0.8) {
                        baseRectLine.merge(rectLine);
                        baseTextLine.addElements(textLine.getElements());
                        isMerge = true;
                    }
                }
                if (!isMerge) {
                    mergeLineBounds.add(rectLine);
                    mergeLines.add(new TextBlock(textLine));
                }
            }

            if (mergeLines.size() == 1 && singleCells.isEmpty()) {
                subRows.add(new Rectangle(mergeLines.get(0).getVisibleBBox()));
            } else {
                for (TextBlock textBlock : mergeLines) {
                    subRows.addAll(analyzeRowOfLine(textBlock));
                }
            }
        }

        subRows.sort(Comparator.comparing(Rectangle::getTop));
        return subRows;
    }

    private static List<Rectangle> splitBaseRows(TextBlock textRow, List<Ruling> horizontalRulings) {
        List<Rectangle> subRows = new ArrayList<>();
        Rectangle2D visibleRowBBox = textRow.getVisibleBBox();
        List<Ruling> rulingsInLine = null;
        List<Float> rulingCoordY = new ArrayList<>();

        if (!visibleRowBBox.isEmpty() && horizontalRulings != null) {
            rulingsInLine = Ruling.getRulingsFromArea(horizontalRulings, visibleRowBBox);
        }

        // 对区域内ruling:
        // 1.去除文本框附近的边界ruling;
        // 2.获取有效的水平Y坐标集合-去除重复值，即同一水平线的只取一次
        if (rulingsInLine != null && !rulingsInLine.isEmpty()) {
            for (Ruling ruling: rulingsInLine) {
                if (FloatUtils.feq(ruling.getCenterY(), visibleRowBBox.getMinY(), 1.0f) ||
                        FloatUtils.feq(ruling.getCenterY(), visibleRowBBox.getMaxY(), 1.0f)) {
                    continue;
                }

                boolean isExist = false;
                for (Float coordY: rulingCoordY) {
                    if (FloatUtils.feq(ruling.getCenterY(), coordY, 2)) {
                        isExist = true;
                        break;
                    }
                }
                if (!isExist) {
                    rulingCoordY.add(ruling.getCenterY());
                }
            }
        }

        if (rulingCoordY.isEmpty()) {
            List<Rectangle> tempRows = analyzeRowOfLine(textRow);
            List<TextChunk> textCells = new ArrayList<>(textRow.getElements());
            for (Rectangle row: tempRows) {
                List<TextChunk> textInRow = new ArrayList<>();
                float maxHeight = 0;
                TextChunk maxHeightChunk = null;
                for (TextChunk textChunk: textCells) {
                    if (textChunk.verticalOverlap(row) > textChunk.getAvgCharHeight()*0.5 ||
                            textChunk.verticalOverlapRatio(row) > 0.3) {
                        textInRow.add(textChunk);
                        if (maxHeight < textChunk.getHeight()) {
                            maxHeight = (float) textChunk.getHeight();
                            maxHeightChunk = textChunk;
                        }
                    }
                }

                // 分析当前切分子行中的文本块是否存在纵向重叠，如果有则当前子行还不是最细粒度的切分
                boolean isNormal = true;
                for (int i=0; i<textInRow.size(); i++) {
                    TextChunk chunkA = textInRow.get(i);
                    for (int j=i+1; j<textInRow.size(); j++) {
                        TextChunk chunkB = textInRow.get(j);
                        if (chunkA.horizontalOverlap(chunkB) > Math.min(chunkA.getAvgCharWidth(), chunkB.getAvgCharWidth()) ||
                                chunkA.horizontallyOverlapRatio(chunkB) > 0.5) {
                            isNormal = false;
                            break;
                        }
                    }
                    if (!isNormal) {
                        break;
                    }
                }

                // 正常终止递归
                if (isNormal) {
                    subRows.add(row);
                } else {
                    // 去除当前子行文本块最高的
                    textInRow.remove(maxHeightChunk);

                    // 继续递归分析
                    List<Rectangle> partBaseCell = splitBaseRows(new TextBlock(textInRow), null);
                    subRows.addAll(partBaseCell);
                }
                textCells.removeAll(textInRow);
            }
        } else {
            rulingCoordY.sort(Comparator.comparing(Float::floatValue));
            List<TextChunk> textCells = new ArrayList<>(textRow.getElements());
            float minTextHeight = Float.MAX_VALUE;
            // 去除与区域内水平线有水平相交的chunk，只保留由ruling划分开的chunk
            for (int i = 0; i < textCells.size();) {
                TextChunk chunk = textCells.get(i);
                if (minTextHeight > chunk.getVisibleHeight()) {
                    minTextHeight =(float) chunk.getVisibleHeight();
                }
                boolean hasIntersect = false;
                for (Float coordY: rulingCoordY) {
                    if (chunk.getVisibleMinY() <= coordY && chunk.getVisibleMaxY() >= coordY) {
                        hasIntersect = true;
                        break;
                    }
                }
                if (hasIntersect) {
                    textCells.remove(chunk);
                } else {
                    i++;
                }
            }

            List<TextBlock> Rows = new ArrayList<>();
            List<Rectangle2D> Rects = new ArrayList<>();
            float minX = (float) visibleRowBBox.getMinX();
            float minY = (float) visibleRowBBox.getMinY();
            float width = (float) visibleRowBBox.getMaxX();
            rulingCoordY.add((float) visibleRowBBox.getMaxY());
            // 对每条区域内的ruling形成子区域，根据相关条件判断是否为ruling划分的子行
            for (Float coordY : rulingCoordY) {
                Rectangle2D subRect = new Rectangle2D.Float(minX, minY, width, coordY - minY);
                minY = coordY;

                if (subRect.getHeight() < minTextHeight) {
                    continue;
                }

                // 对子区域获取在内部的TextChunk
                TextBlock subRow = new TextBlock();
                for (int i = 0; i < textCells.size();) {
                    TextChunk chunk = textCells.get(i);
                    if (FloatUtils.flte(subRect.getMinY(), chunk.getVisibleMinY(), 1.f) &&
                            FloatUtils.fgte(subRect.getMaxY(), chunk.getVisibleMaxY(), 1.f)) {
                        subRow.addElement(textCells.remove(i));
                    } else {
                        i++;
                    }
                }
                if (subRow.getElements().isEmpty()) {
                    continue;
                }
                Rows.add(subRow);
                Rects.add(subRect);
            }

            // 对每个由ruling划分的子行，根据textChunk分析更细粒度的子行
            for (int i = 0; i < Rows.size(); i++) {
                TextBlock textBlock = Rows.get(i);
                Rectangle2D textRect = Rects.get(i);
                List<Rectangle> tempRows = splitBaseRows(textBlock, null);
                if (tempRows.get(0).getTop() < textRect.getMinY()) {
                    tempRows.get(0).setTop((float) textRect.getMinY() + 0.1f);
                }
                if (tempRows.get(tempRows.size() - 1).getBottom() > textRect.getMaxY()) {
                    tempRows.get(tempRows.size() - 1).setBottom((float) textRect.getMaxY() - 0.1f);
                }
                subRows.addAll(tempRows);
            }
        }

        subRows.sort(Comparator.comparing(Rectangle::getTop));
        return subRows;
    }

    public static List<Rectangle> calColumnFromLines(List<List<TextChunk>> lines) {
        // 根据投影获得初步列区间
        List<Rectangle> projectColCells = new ArrayList<>();
        float minWidthOfSpace = java.lang.Float.MAX_VALUE; // 最小空白宽度
        for (List<TextChunk> line: lines) {
            for (TextChunk rect: line) {
                float widthOfSpace = rect.getWidthOfSpace();
                if (minWidthOfSpace > widthOfSpace) {
                    minWidthOfSpace = widthOfSpace;
                }

                boolean isNullCol = true;
                for (Rectangle baseCell: projectColCells) {
                    if (rect.horizontalOverlap(baseCell) > widthOfSpace ||
                            rect.horizontallyOverlapRatio(baseCell) > 0.3) {
                        baseCell.merge(rect);
                        isNullCol = false;
                    }
                }
                if (isNullCol) {
                    projectColCells.add(new Rectangle(rect));
                }
            }
        }

        // 对投影列区间的重合区间合并
        List<Rectangle> mergeCells = new ArrayList<>();
        projectColCells.sort(Comparator.comparing(Rectangle::getLeft));
        for (Rectangle cell: projectColCells) {
            boolean isMerge = false;
            for (Rectangle baseCell: mergeCells) {
                if (cell.horizontalOverlap(baseCell) > minWidthOfSpace) {
                    baseCell.merge(cell);
                    isMerge = true;
                }
            }
            if (!isMerge) {
                mergeCells.add(new Rectangle(cell));
            }
        }
        mergeCells.sort(Comparator.comparing(Rectangle::getLeft));
        return mergeCells;
    }

    private static List<Rectangle> splitBaseColumns(Page page, List<List<TextChunk>> lines, int MaxRecursionNum) {
        if (lines.isEmpty()) {
            return new ArrayList<>();
        }

        List<Rectangle> baseCells = new ArrayList<>();
        List<Rectangle> colCells = calColumnFromLines(lines);
        for (Rectangle cell: colCells) {

            List<List<TextChunk>> linesForSingleCol = new ArrayList<>();
            int minColumn = 10000, maxColumn = 0;
            int hasFilterNum = 0;
            for (List<TextChunk> line: lines) {
                List<TextChunk> chunksInCol = new ArrayList<>();
                for (TextChunk textChunk: line) {
                    if (textChunk.horizontalOverlap(cell) > textChunk.getWidthOfSpace() ||
                            textChunk.horizontallyOverlapRatio(cell) > 0.3) {
                        chunksInCol.add(textChunk);
                    } else {
                        hasFilterNum += 1;
                    }
                }
                if (chunksInCol.size() > 1) {
                    linesForSingleCol.add(chunksInCol);
                    if (chunksInCol.size() < minColumn) {
                        minColumn = chunksInCol.size();
                    }
                    if (chunksInCol.size() > maxColumn) {
                        maxColumn = chunksInCol.size();
                    }
                }
            }

            // 正常终止递归
            if (linesForSingleCol.isEmpty()) {
                baseCells.add(cell);
            } else {
                if (1 == colCells.size() && 0 == hasFilterNum) {
                    // (当前待分析的所有行只分裂成一列，且根据分裂结果包含所有文本块，但存在多列文本的行，即分裂不开)
                    if (minColumn < maxColumn) {
                        // 尝试去除局部最小列的文本行后，再切分
                        for (int i = 0; i < linesForSingleCol.size();) {
                            if (linesForSingleCol.get(i).size() == minColumn) {
                                linesForSingleCol.remove(i);
                            } else {
                                i++;
                            }
                        }
                    } else if (minColumn == maxColumn) {
                        List<List<TextChunk>> tempLines = new ArrayList<>();
                        // 去除每行中造成拆分干扰的文本块，再切分
                        for (int i = 0; i < linesForSingleCol.size(); i++) {
                            List<TextChunk> currLineChunks = linesForSingleCol.get(i);
                            List<TextChunk> chunksInCol = new ArrayList<>();

                            for (TextChunk currChunk: currLineChunks) {
                                boolean isSingle = true;
                                for (int j = 0; j < linesForSingleCol.size(); j++) {
                                    List<TextChunk> referLineChunks = linesForSingleCol.get(j);
                                    if (i == j) {
                                        continue;
                                    }
                                    int numForOverlap = 0;
                                    for (TextChunk referChunk: referLineChunks) {
                                        if (currChunk.horizontalOverlap(referChunk) > currChunk.getWidthOfSpace() ||
                                                currChunk.horizontallyOverlapRatio(referChunk) > 0.3) {
                                            numForOverlap += 1;
                                        }
                                        if (numForOverlap  > 1) {
                                            break;
                                        }
                                    }
                                    if (numForOverlap > 1) {
                                        isSingle = false;
                                        break;
                                    }
                                }
                                // 当前文本块与其他行的文本都只存在单一区域重叠，则加入到后续待分割的ChunkList中
                                if (isSingle) {
                                    chunksInCol.add(currChunk);
                                }
                            }
                            if (!chunksInCol.isEmpty()) {
                                tempLines.add(chunksInCol);
                            }
                        }
                        linesForSingleCol = tempLines;
                    }

                }

                // 继续递归分析
                List<Rectangle> partBaseCell = splitBaseColumns(page, linesForSingleCol, MaxRecursionNum-1);
                if (partBaseCell.isEmpty() || MaxRecursionNum == 1) {
                    // 递归出现异常，则清空结果
                    baseCells.clear();
                    break;
                } else {
                    baseCells.addAll(partBaseCell);
                }
            }
        }
        baseCells.sort(Comparator.comparing(Rectangle::getLeft));
        return baseCells;
    }

    // 类似于二叉树结构，根据由粗到细迭代分裂获取最细颗粒的列区间分布
    public static List<Rectangle> calcColumnPositions(Page page, List<TextBlock> lines) {
        int maxColumn = 0;
        List<List<TextChunk>> columnOfLines = new ArrayList<>();
        for (TextBlock textBlock : lines) {
            List<TextChunk> cols = analyzeColOfLine(textBlock);
            columnOfLines.add(cols);
            if (cols.size() > maxColumn) {
                maxColumn = cols.size();
            }
        }

        // 最大列的行集合
        List<List<TextChunk>> maxColLines = new ArrayList<>();
        for (List<TextChunk> line: columnOfLines) {
            if (line.size() == maxColumn) {
                maxColLines.add(line);
            }
        }

        List<Rectangle> baseCells;
        List<List<TextChunk>> objectLines;


        // 先采用基于全局的递归分裂算法，如果分析有异常，则返回empty
        List<Rectangle> baseCellsWithSplit = null;
        try {
            baseCellsWithSplit = splitBaseColumns(page, columnOfLines, 10);
        } catch (Throwable e) {
            logger.debug("Fail in splitBaseColumns() - PDF:{} -pageNum:{}", page.pdfPath, page.getPageNumber());
        }

        // 同时计算基于最大列分析算法
        List<Rectangle> baseCellsWithMaxCol = calColumnFromLines(maxColLines);

        // 如果递归分类算法分析异常，则只采用基于最大列算法
        if (baseCellsWithSplit == null || baseCellsWithSplit.isEmpty()) {
            baseCells = baseCellsWithMaxCol;
            objectLines = maxColLines;
        } else {
            // 融合两种算法结果
            List<Rectangle> mergeBaseCell = new ArrayList<>();
            for (Rectangle splitCell: baseCellsWithSplit) {
                Rectangle mergeCell = new Rectangle(splitCell);
                float fMaxOverlap = 0;
                int iMaxIdx = -1;
                for (int i=0; i<baseCellsWithMaxCol.size(); i++) {
                    float overlapRatio = mergeCell.horizontallyOverlapRatio(baseCellsWithMaxCol.get(i));
                    if (overlapRatio > fMaxOverlap) {
                        fMaxOverlap = overlapRatio;
                        iMaxIdx = i;
                    }
                }
                if (fMaxOverlap > 0.8) {
                    mergeCell.setLeft(Math.max(mergeCell.getLeft(), baseCellsWithMaxCol.get(iMaxIdx).getLeft()));
                    mergeCell.setRight(Math.min(mergeCell.getRight(), baseCellsWithMaxCol.get(iMaxIdx).getRight()));
                }
                mergeBaseCell.add(mergeCell);
            }
            baseCells = mergeBaseCell;
            objectLines = new ArrayList<>(maxColLines);
            if (maxColLines.size() == 1 || maxColumn < baseCells.size()) {
                int totalNumOfLines = columnOfLines.size();
                objectLines.addAll(columnOfLines.subList(totalNumOfLines/2, totalNumOfLines));
            }
        }

        // 最小空白宽度
        float minWidthOfSpace = Float.MAX_VALUE;
        float minHeightOfChunk = Float.MAX_VALUE;
        for (List<TextChunk> line: objectLines) {
            for (TextChunk rect : line) {
                float widthOfSpace = rect.getWidthOfSpace();
                if (minWidthOfSpace > widthOfSpace) {
                    minWidthOfSpace = widthOfSpace;
                }
                float heightOfChunk = (float) rect.getHeight();
                if (minHeightOfChunk > heightOfChunk) {
                    minHeightOfChunk = heightOfChunk;
                }
            }
        }

        List<TextChunk> objectChunks = objectLines.stream().flatMap(Collection::stream).collect(Collectors.toList());
        List<Rectangle> columns = new ArrayList<>();
        for (int cellIdx = 0; cellIdx < baseCells.size(); cellIdx++) {
            Rectangle cell = baseCells.get(cellIdx);

            // 筛选出当前基准列区间的基准文本块做动态微缩
            // 关键: 去除存在多个基准列区间的合并文本块，只保留仅在当前列区间的基准文本块，避免列区间被合并信息污染
            float finalMinWidthOfSpace = minWidthOfSpace;
            // step1: 选出当前列区间包含的文本块
            List<TextChunk> overlapColumn = objectChunks.stream()
                    .filter(x -> x.horizontallyOverlapRatio(cell) > 0.5 || x.horizontalOverlap(cell) >= finalMinWidthOfSpace)
                    .collect(Collectors.toList());

            // 如果当前列只有一个文本块，且表格包含多行（大于5,暂时经验值），且位于表前几行，满足以下条件，则认为独立表头，不作为有效独立列
            // 1.当前潜在列不在首尾
            // 2.其唯一的单元格文本块左右没有其他文本块
            boolean inHeader = false;
            if (overlapColumn.size() == 1 && lines.size() > 5 && cellIdx > 0 && cellIdx < baseCells.size()-1 ) {
                TextChunk singleChunk = overlapColumn.get(0);
                int forepart = Math.min(3, Math.max(1, (int)(lines.size() * 0.3f)));
                for (int i = 0; i < forepart; i++) {
                    if (singleChunk.verticalOverlapRatio(new Rectangle(lines.get(i).getBounds2D())) > 0.8) {
                        List<TextChunk> hitLineChunks = lines.get(i).getElements();
                        Rectangle leftCol = baseCells.get(cellIdx - 1);
                        Rectangle rightCol = baseCells.get(cellIdx + 1);
                        List<TextChunk> leftChunks = hitLineChunks.stream()
                                .filter(x->x.horizontallyOverlapRatio(leftCol) > 0.5 && x.verticalOverlapRatio(singleChunk) > 0.8)
                                .collect(Collectors.toList());
                        List<TextChunk> rightChunks = hitLineChunks.stream()
                                .filter(x->x.horizontallyOverlapRatio(rightCol) > 0.5 && x.verticalOverlapRatio(singleChunk) > 0.8)
                                .collect(Collectors.toList());
                        if (leftChunks.isEmpty() && rightChunks.isEmpty()) {
                            inHeader = true;
                            break;
                        }

                        // 对于唯一文本块就是基础列区间时，如果存在左边或右边为空，且当前列区间与邻区间存在相交重叠，则很可能为偏移的独立表头
                        if (singleChunk.overlapRatio(cell, true) > 0.95) {
                            if ((leftChunks.isEmpty() && cellIdx > 0 && cell.isHorizontallyOverlap(baseCells.get(cellIdx-1))) ||
                                    (rightChunks.isEmpty() && cellIdx < baseCells.size()-1 && cell.isHorizontallyOverlap(baseCells.get(cellIdx+1)))) {
                                inHeader = true;
                                break;
                            }
                        }
                    }
                }
            }
            if (inHeader) {
                continue;
            }

            // step2：去除干扰文本块
            // (a).去除落在多个列区间的文本块
            // (b).去除偏移的表头文本块
            List<TextChunk> multiCellChunk = new ArrayList<>();
            for (TextChunk textChunk: overlapColumn) {
                int inNum = 0;
                // baseCells有可能是基于baseCellsWithSplit的结果做过收缩修正，导致目前区域偏小
                // 所以以baseCellsWithSplit来去除干扰文本块
                List<Rectangle> referBaseCells = baseCellsWithSplit;
                if (baseCellsWithSplit == null || baseCellsWithSplit.isEmpty()) {
                    referBaseCells = baseCells;
                }

                for (Rectangle bCell: referBaseCells) {
                    if (textChunk.horizontalOverlap(bCell) > finalMinWidthOfSpace) {
                        inNum += 1;
                    }
                    if (inNum > 1) {
                        multiCellChunk.add(textChunk);
                        break;
                    }
                }
            }
            overlapColumn.removeAll(multiCellChunk);
            // 已分析过的基准文本块不参与其他基本列区间的分析
            objectChunks.removeAll(overlapColumn);

            // 当前列文本过滤后为空，则采用另一种策略分析是否为独立表头
            if (overlapColumn.isEmpty() && cell.getHeight() < minHeightOfChunk*2) {
                if ((cellIdx > 0 && cell.isHorizontallyOverlap(baseCells.get(cellIdx-1))) ||
                        (cellIdx < baseCells.size()-1 && cell.isHorizontallyOverlap(baseCells.get(cellIdx+1)))) {
                    continue;
                } else if (multiCellChunk.isEmpty()) {
                    int forepart = Math.min(3, Math.max(1, (int)(lines.size() * 0.3f)));
                    for (int i = 0; i < forepart; i++) {
                        if (cell.verticalOverlapRatio(new Rectangle(lines.get(i).getBounds2D())) > 0.8) {
                            inHeader = true;
                            break;
                        }
                    }
                    if (inHeader) {
                        continue;
                    }
                }
            }

            // step3： 根据收集的当前列区间的基准文本块做动态微缩
            Rectangle union = new Rectangle(cell);
            if (overlapColumn != null && !overlapColumn.isEmpty()) {
                int count = overlapColumn.size();
                overlapColumn.sort(Comparator.comparing(Rectangle::getTop));
                float top = overlapColumn.get(0).getTop();
                float bottom = overlapColumn.get(count - 1).getBottom();

                // 查找第二缩进的左侧
                overlapColumn.sort(Comparator.comparing(Rectangle::getLeft));
                float minLeft = overlapColumn.get(0).getLeft();
                float maxLeft = overlapColumn.get(count - 1).getLeft();
                float threshLeft = minLeft + ((float) cell.getWidth() * 0.1f);
                float secondMinLeft = minLeft;
                for (Rectangle col: overlapColumn) {
                    if (secondMinLeft < col.getLeft()) {
                        secondMinLeft = col.getLeft();
                        break;
                    }
                }

                overlapColumn.sort(Comparator.comparing(Rectangle::getRight).reversed());
                float maxRight = overlapColumn.get(0).getRight();
                float minRight = overlapColumn.get(overlapColumn.size() - 1).getRight();
                float threshRight = maxRight - ((float) cell.getWidth() * 0.1f);
                float secondMaxRight = maxRight;
                for (Rectangle col: overlapColumn) {
                    if (secondMaxRight > col.getRight()) {
                        secondMaxRight = col.getRight();
                        break;
                    }
                }

                float left = Math.min(threshLeft, secondMinLeft);
                if (left >= minRight) {
                    left = minLeft;
                }

                float right = Math.max(threshRight, secondMaxRight);
                if (right <= maxLeft) {
                    right = maxRight;
                }

                union.setRect(left, top, right - left, bottom - top);
            }
            columns.add(union);
        }
        columns.sort(Comparator.comparing(Rectangle::getLeft));

        // 后处理验证，对列区间调整后存在重合的区域，进行拆分调整
        for (int i = 0; i < columns.size() - 1; i++) {
            Rectangle currCol = columns.get(i);
            Rectangle nextCol = columns.get(i+1);
            if (currCol.isHorizontallyOverlap(nextCol)) {
                // 对重合区域进行边界调整，
                float overlapLeft = Math.max(currCol.getLeft(), nextCol.getLeft());
                float overlapRight= Math.min(currCol.getRight(), nextCol.getRight());
                currCol.setRight(overlapLeft - 0.5f);
                nextCol.setLeft(overlapRight + 0.5f);
            }
        }
        columns.sort(Comparator.comparing(Rectangle::getLeft));
        return columns;
    }

    private int bitmapClassify(ByteString imageData) {
        ImageClassifyResult classifyResult = algClient.imageClassify(imageData).get(0);
        int tableType;
        if (classifyResult == null ||
                (classifyResult.type != ImageType.IMAGE_GRID_TABLE && classifyResult.type != ImageType.IMAGE_LINE_TABLE)) {
            logger.warn("imageClassify get no result");
            // force using "line table" to parse
            tableType = 1;
        } else {
            // convert to bitmap table type
            tableType = classifyResult.type - 7;
        }
        return tableType;
    }

    public static boolean classifyTableByBitmap(Table table) {
        if (algClient == null) {
            logger.error("AlgorithmGrpcClient is not set");
            return true;
        }

        BufferedImage tableImage = TableUtils.getTableImage(table, DebugHelper.DEFAULT_DPI, true);
        if (tableImage == null) {
            logger.warn("BufferedImage is null");
            return true;
        }

        ByteString imageData;
        try {
            imageData = getFormatedImageData(tableImage);
        } catch (IOException e) {
            logger.warn("Failed to get image data for page", e);
            return true;
        }
        if (imageData == null) {
            logger.warn("Failed to get image data for page");
            return true;
        }

        ImageClassifyResult classifyResult = null;
        try {
            classifyResult = algClient.imageClassify(imageData).get(0);
        } catch (Exception e) {
            logger.warn("ImageClassify failed", e);
        }
        if (classifyResult == null) {
            return true;
        }

        logger.info("Table image classified as type: {}, the classify score is: {}", ImageType.desc(classifyResult.type), classifyResult.score);
        if (classifyResult.type == ImageType.IMAGE_GRID_TABLE
                || classifyResult.type == ImageType.IMAGE_LINE_TABLE) {
            table.updateConfidence(Table.HIGH_CONFIDENCE_THRESHOLD, 1.0);
            return true;
        } else {
            if (classifyResult.score > 0.5) {
                table.updateConfidence(0.0, Table.LOW_CONFIDENCE_THRESHOLD);
                return false;
            } else {
                table.updateConfidence(Table.LOW_CONFIDENCE_THRESHOLD, Table.HIGH_CONFIDENCE_THRESHOLD);
                return true;
            }
        }
    }

    private List<ImageDetectResult> detectTables(BufferedImage pageImage) {
        List<ImageDetectResult> rectangles = new ArrayList<>();
        ByteString byteString;
        try {
            byteString = getFormatedImageData(pageImage);
        } catch (IOException e) {
            logger.warn("Failed to get image data for page", e);
            return rectangles;
        }
        if (byteString == null) {
            logger.warn("Failed to get image data for page");
            return rectangles;
        }

        List<ImageDetectResult> results = algClient.imageDetect(byteString);
        for (ImageDetectResult r: results) {
            if (ImageType.imageTypeIsTable(r.type)) {
                rectangles.add(r);
            }
        }
        return rectangles;
    }

    private static boolean canExtra(ContentGroupPage page, Rectangle detectTable, List<Table> tables) {
        List<Table> intersectTable = tables.stream().filter(t->t.intersects(detectTable)).collect(Collectors.toList());
        if (intersectTable.isEmpty()) {
            return true;
        }

        Table maxIntersectTable = intersectTable.get(0);
        float maxIntersectArea = 0.f;
        for (Table t : intersectTable) {
            Rectangle2D intersectBounds = detectTable.createIntersection(t);
            float intersectArea = (float) (intersectBounds.getWidth() * intersectBounds.getHeight());
            if (intersectArea > maxIntersectArea) {
                maxIntersectArea = intersectArea;
                maxIntersectTable = t;
            }
        }

        // 与相交最大的table进行分析
        Rectangle2D intersectBounds = detectTable.createIntersection(maxIntersectTable);
        float a0 = (float) (intersectBounds.getWidth() * intersectBounds.getHeight());
        float a1 = (float) (detectTable.getWidth() * detectTable.getHeight());
        float a2 = (float) (maxIntersectTable.getWidth() * maxIntersectTable.getHeight());
        //如果位图结果几乎包含矢量结果，且面积比矢量结果大很多，可认为是更完整的表格结果
        if (a0 / a2 > 0.9 && a1 / a2 > 2) {
            return true;
        }
        // 相交区域只占各自比较小比例，可认为是两个不同的结果
        if ((a0 / a1 < 0.2 && a0 / a2 < 0.2) || a0 / a1 < 0.1) {
            return true;
        }
        // 相交区域有较多重叠，分析其非重叠区域内部结构是否与重叠区域匹配
        // 基于目前开启位图模型后，矢量只输出有线框表格(有线表格与有线框的无线表格)，目前仅对无线矢量表格做分析
        if (a0 / a2 > 0.8 && detectTable.nearlyContains(maxIntersectTable, page.getAvgCharHeight())
                && maxIntersectTable.getTableType() == TableType.NoLineTable) {
            List<TextChunk> regionChunks = page.getMergeChunks(detectTable);
            List<TextChunk> tableChunks = page.getMergeChunks(maxIntersectTable);
            regionChunks.removeAll(tableChunks);
            if (regionChunks.isEmpty()) {
                return false;
            }

            Rectangle2D unionBound = detectTable.createUnion(maxIntersectTable);
            // 上方
            Rectangle aboveBound = Rectangle.fromLTRB(maxIntersectTable.getLeft(), (float) unionBound.getMinY(),
                    maxIntersectTable.getRight(), maxIntersectTable.getTop() - 2);
            List<TextChunk> aboveChunks = regionChunks.stream().filter(t->t.overlapRatio(aboveBound) > 0).collect(Collectors.toList());
            boolean isAboveMatch = true;
            if (!aboveChunks.isEmpty()) {
                List<TextBlock> aboveRows = TextMerger.collectByLines(new ArrayList<>(aboveChunks));
                List<Rectangle> aboveCols = calcColumnPositions(page, aboveRows);
                Rectangle chunksRegion = Rectangle.boundingBoxOf(aboveChunks);
                // 少于矢量表格列数的0.5,或者距离矢量表格大于3个平均字符高度，则认为不可靠
                if (aboveCols.size() < maxIntersectTable.getColumnCount()*0.5 ||
                        maxIntersectTable.getTop() - chunksRegion.getBottom() > page.getAvgCharHeight()*3) {
                    isAboveMatch = false;
                }
            }

            // 下方
            Rectangle belowBound = Rectangle.fromLTRB(maxIntersectTable.getLeft(), maxIntersectTable.getBottom() + 2,
                    maxIntersectTable.getRight(), (float) unionBound.getMaxY());
            List<TextChunk> belowChunks = regionChunks.stream().filter(t->t.overlapRatio(belowBound) > 0).collect(Collectors.toList());
            boolean isBelowMatch = true;
            if (!belowChunks.isEmpty()) {
                List<TextBlock> belowRows = TextMerger.collectByLines(new ArrayList<>(belowChunks));
                List<Rectangle> belowCols = calcColumnPositions(page, belowRows);
                Rectangle chunksRegion = Rectangle.boundingBoxOf(belowChunks);
                // 少于矢量表格列数的0.5,或者距离矢量表格大于3个平均字符高度，则认为不可靠
                if (belowCols.size() < maxIntersectTable.getColumnCount()*0.5 ||
                        chunksRegion.getTop() - maxIntersectTable.getBottom() > page.getAvgCharHeight()*3) {
                    isBelowMatch = false;
                }
            }
            // 左方
            Rectangle leftBound = Rectangle.fromLTRB((float) unionBound.getMinX(), maxIntersectTable.getTop(),
                    maxIntersectTable.getLeft() - 2, maxIntersectTable.getBottom());
            List<TextChunk> leftChunks = regionChunks.stream().filter(t->t.overlapRatio(leftBound) > 0).collect(Collectors.toList());
            boolean isLeftMatch = true;
            if (!leftChunks.isEmpty()) {
                List<TextBlock> leftRows = TextMerger.collectByLines(new ArrayList<>(leftChunks));
                // 少于矢量表格行数的0.5, 则认为不可靠
                if (leftRows.size() < maxIntersectTable.getRowCount()*0.8) {
                    isLeftMatch = false;
                }
            }
            // 右方
            Rectangle rightBound = Rectangle.fromLTRB(maxIntersectTable.getRight() + 2, maxIntersectTable.getTop(),
                    (float) unionBound.getMaxX(), maxIntersectTable.getBottom());
            List<TextChunk> rightChunks = regionChunks.stream().filter(t->t.overlapRatio(rightBound) > 0).collect(Collectors.toList());
            boolean isRightMatch = true;
            if (!rightChunks.isEmpty()) {
                List<TextBlock> rightRows = TextMerger.collectByLines(new ArrayList<>(rightChunks));
                // 少于矢量表格行数的0.5, 则认为不可靠
                if (rightRows.size() < maxIntersectTable.getRowCount()*0.8) {
                    isRightMatch = false;
                }
            }
            if (!regionChunks.isEmpty() && isAboveMatch && isBelowMatch && isLeftMatch && isRightMatch) {
                tables.remove(maxIntersectTable);
                return true;
            }
        }

        return false;
    }

    // 结合已有table对位图检测结果做过滤
    private List<ImageDetectResult> getExtraRectangles(ContentGroupPage page, List<ImageDetectResult> rectangles, List<Table> tables) {
        List<ImageDetectResult> extras = new ArrayList<>();
        float imageToPageScale = DebugHelper.DEFAULT_DPI / (float) TABLE_DETECT_DPI;
        List<Rectangle> pageRects = rectangles.stream().map(r->new Rectangle(r.getPageRect(imageToPageScale))).collect(Collectors.toList());

        // 首先对已解析的矢量表格结果做分析，结合位图检测结果确认有效性
        // 1. tables个数为1的时候
        if (tables.size() == 1) {
            Table table = tables.get(0);
            // 只有一个矢量无线表格，且占据整个页面80%以上，则存在可疑
            if (!table.isStructureTable() && table.getTableType() == TableType.NoLineTable &&
                    page.getTextBounds().selfOverlapRatio(table) > 0.8) {
                // 只有当位图结果存在多个时，才能做区域分析
                if (pageRects.size() > 1) {
                    List<Rectangle> rectContainByTable = pageRects.stream()
                            .filter(r->r.selfOverlapRatio(table) > 0.9)
                            .collect(Collectors.toList());

                    boolean isNoOverlap = true;
                    boolean isNoHorizontalOverlap = true;
                    boolean isNoVerticalOverlap = true;
                    for (int i = 0; i < rectContainByTable.size(); i++) {
                        Rectangle currRect = rectContainByTable.get(i);
                        for (int j = i+1; j < rectContainByTable.size(); j++) {
                            Rectangle nextRect = rectContainByTable.get(j);
                            if (currRect.overlapRatio(nextRect) > 0) {
                                isNoOverlap = false;
                            }

                            float horizontalOverlap = currRect.horizontalOverlap(nextRect);
                            float verticalOverlap = currRect.verticalOverlap(nextRect);
                            if (horizontalOverlap > currRect.getWidth()*0.5 || horizontalOverlap > nextRect.getWidth()*0.5) {
                                isNoHorizontalOverlap = false;
                            }
                            if (verticalOverlap > currRect.getHeight()*0.5 || verticalOverlap > nextRect.getHeight()*0.5) {
                                isNoVerticalOverlap = false;
                            }
                        }
                    }

                    // 几乎占据整个页面的矢量表格中，包含多个位图结果，且多个位图结果不存在重叠，不存在垂直或水平相交
                    // 则可认为内部是存在多个子表，当前矢量表格为区域误检
                    if (isNoOverlap) {
                        if (isNoHorizontalOverlap && isNoVerticalOverlap) {
                            tables.remove(table);
                        } else {
                            float totalArea = 0;
                            for (Rectangle rect: rectContainByTable) {
                                Rectangle2D intersectRect = rect.createIntersection(table);
                                totalArea += intersectRect.getWidth() * intersectRect.getHeight();
                            }
                            if (totalArea / table.getArea() < 0.7) {
                                tables.remove(table);
                            }
                        }
                    }
                }
            }
        }
        // 2. tables个数为2的时候

        // 根据矢量结果，对位图结果做确认
        for (ImageDetectResult r : rectangles) {
            Rectangle2D pageRect = r.getPageRect(imageToPageScale);
            List<TextChunk> textChunks = page.getTextChunks(new Rectangle(pageRect));
            if (textChunks.isEmpty()) {
                continue;
            }
            boolean extra = true;
            boolean valid = false;

            // -------- method 1 --------
            Rectangle textBound = Rectangle.boundingVisibleBoxOf(textChunks);
            // 根据结构化表格来决定是否保留位图结果
            List<Table> tableContainByDetect = tables.stream()
                    .filter(Table::isStructureTable)
                    .filter(t->t.selfOverlapRatio(pageRect.createIntersection(t)) > 0.8f)
                    .collect(Collectors.toList());
            if (tableContainByDetect.size() > 1) {
                int weekStructTableNum = tableContainByDetect.stream()
                        .filter(t->t.getRowCount() == 1 || t.getColumnCount() == 1)
                        .collect(Collectors.toList()).size();
                Rectangle unionBound = Rectangle.boundingBoxOf(tableContainByDetect);
                Rectangle2D intersectBounds = pageRect.createIntersection(unionBound);
                float a0 = (float) (intersectBounds.getWidth() * intersectBounds.getHeight());
                float a1 = (float) (pageRect.getWidth() * pageRect.getHeight());
                float a2 = (float) (unionBound.getWidth() * unionBound.getHeight());
                if (weekStructTableNum > 1 && a0/a1 > 0.9 && a0/a2 > 0.9) {
                    // 与位图检测结果高重叠率的结构化表格，总区域与位图结果几乎相同，且存在多个单行或单列的弱性结构化表格
                    // 此时认为位图结果是完整性比较高的表格区域，而高重合度包含的结构化表格是被拆分的零散错误区域，删除处理
                    valid = true;
                    extra = true;
                    tables.removeAll(tableContainByDetect);
                }
            }

            // -------- method 2 --------
            if (!valid) {
                extra = canExtra(page, new Rectangle(pageRect), tables);
            }

            if (extra) {
                extras.add(r);
            }
        }
        return extras;
    }

    private List<ImageDetectResult> mergeRectangles(ContentGroupPage page, List<ImageDetectResult> rectangles) {
        List<ImageDetectResult> merger = new ArrayList<>();
        rectangles.sort((r1, r2) -> r1.getArea() - r2.getArea() >= 0 ? -1 : 1);

        for (ImageDetectResult r : rectangles) {
            boolean valid = true;
            for (ImageDetectResult base : merger) {
                if (base.contains(r, 0.5f) > 0) {
                    base.combine(r);
                    valid = false;
                    break;
                } else {
                    // 判断区域是否有交集
                    float imgToPageScale = DebugHelper.DEFAULT_DPI / (float) TABLE_DETECT_DPI;
                    Rectangle2D bound = r.getPageRect(imgToPageScale);
                    Rectangle2D baseBound = base.getPageRect(imgToPageScale);
                    Rectangle2D intersectBounds = baseBound.createIntersection(bound);
                    if (intersectBounds.isEmpty()) {
                        continue;
                    }
                    // 根据文本块判断交集区域是否有效
                    Rectangle pageRect =  new Rectangle(intersectBounds);
                    List<TextChunk> textChunks = page.getMutableTextChunks(pageRect);
                    if (textChunks.isEmpty()) {
                        continue;
                    }
                    // 过滤空文本块
                    for (int i = 0; i < textChunks.size();) {
                        if (Objects.equals(textChunks.get(i).getText(), "")) {
                            textChunks.remove(i);
                        } else {
                            i++;
                        }
                    }
                    // 取最小列数对应的行为分割点
                    textChunks.sort(Comparator.comparing(TextChunk::getTop));
                    List<TextBlock> lines = TextMerger.collectByLines(textChunks);
                    int minColNum = 1000;
                    Rectangle2D minColRect = intersectBounds;
                    for (TextBlock line: lines) {
                        int currColNum = line.getElements().size();
                        if (currColNum < minColNum) {
                            minColNum = currColNum;
                            minColRect = line.getBounds2D();
                        }
                    }

                    // 根据分割点重新设置上下区域
                    if (bound.getY() < baseBound.getY()) {
                        bound.setRect(bound.getMinX(), bound.getMinY(),
                                bound.getWidth(), minColRect.getMinY() - bound.getMinY() - 1);
                        baseBound.setRect(baseBound.getMinX(), minColRect.getMinY(),
                                baseBound.getWidth(), baseBound.getMaxY() - minColRect.getMinY());
                    } else {
                        bound.setRect(bound.getMinX(), minColRect.getMinY(),
                                bound.getWidth(), bound.getMaxY() - minColRect.getMinY());
                        baseBound.setRect(baseBound.getMinX(), baseBound.getMinY(),
                                baseBound.getWidth(), minColRect.getMinY() - baseBound.getMinY() - 1);
                    }

                    r.setNewPageRect(bound);
                    base.setNewPageRect(baseBound);
                }
            }
            if (valid) {
                merger.add(r);
            }
        }
        return merger;
    }
}
