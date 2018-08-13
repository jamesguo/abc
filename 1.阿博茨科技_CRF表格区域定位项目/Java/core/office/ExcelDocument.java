package com.abcft.pdfextract.core.office;

import com.abcft.pdfextract.spi.ChartType;
import com.abcft.pdfextract.spi.Document;
import com.abcft.pdfextract.spi.FileType;
import com.abcft.pdfextract.spi.Meta;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import com.google.common.collect.TreeBasedTable;
import com.google.gson.JsonObject;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.poi.POIXMLDocument;
import org.apache.poi.POIXMLDocumentPart;
import org.apache.poi.POIXMLProperties;
import org.apache.poi.hpsf.DocumentSummaryInformation;
import org.apache.poi.hpsf.SummaryInformation;
import org.apache.poi.hssf.converter.ExcelToFoConverter;
import org.apache.poi.hssf.extractor.ExcelExtractor;
import org.apache.poi.hssf.usermodel.*;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.*;
import org.apache.poi.xwpf.usermodel.XWPFTheme;
import org.apache.xmlbeans.XmlObject;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.Text;


import javax.annotation.Nullable;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.awt.geom.Rectangle2D;
import java.io.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

public class ExcelDocument extends OfficeDocument implements Document<ExcelDocument> {
    private static final Logger logger = LogManager.getLogger();
    private static final String[] catelog = {"目录", "目錄", "封面", "鋒面", "日历", "封面目录"};
    private static final String[] titleFilters = {"日期", "单位", "附件", "数据来源："};
    private final Workbook workbook;
    private boolean processed;
    private XWPFTheme theme;
    private int counter;
    // 是否开启excel解析
    private static boolean parseExcel = true;

    static boolean isParseExcel() {
        return parseExcel;
    }

    public static void setParseExcel(boolean parseExcel) {
        ExcelDocument.parseExcel = parseExcel;
    }

    public ExcelDocument(InputStream stream) throws InvalidFormatException, IOException {
        this(WorkbookFactory.create(stream));
    }

    public ExcelDocument(Workbook workbook) {
        this.workbook = workbook;
        this.workbook.setMissingCellPolicy(Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        this.workbook.setForceFormulaRecalculation(true);
        this.processed = false;
        if (workbook instanceof POIXMLDocument) {
            theme = XDDFChartUtils.getTheme((POIXMLDocument) workbook);
        } else {
            theme = null;
        }
    }

    @Override
    public FileType getFileType() {
        return FileType.EXCEL;
    }

    @Override
    public Meta getMeta() {
        Meta meta = new Meta();
        if (workbook instanceof XSSFWorkbook) {
            POIXMLProperties properties = ((XSSFWorkbook) workbook).getProperties();
            POIXMLProperties.CoreProperties coreProperties = properties.getCoreProperties();
            POIXMLProperties.ExtendedProperties extProps = properties.getExtendedProperties();
            meta = getMeta(coreProperties, extProps, false);
        } else if (workbook instanceof HSSFWorkbook) {
            SummaryInformation coreProps = ((HSSFWorkbook) workbook).getSummaryInformation();
            DocumentSummaryInformation docProps = ((HSSFWorkbook) workbook).getDocumentSummaryInformation();
            meta = getMeta(coreProps, docProps, false);
        }
        return meta;
    }

    @Override
    public ExcelDocument getDocument() {
        return this;
    }

    private void resetIndex(int pageIndex) {
        currentPageIndex = pageIndex;
        currentTableIndex = 0;
        currentChartIndex = 0;
    }

    private int getAndIncChartIndex() {
        int x = currentChartIndex;
        currentChartIndex += 1;
        return x;
    }

    private int getAndIncTableIndex() {
        int x = currentTableIndex;
        currentTableIndex += 1;
        return x;
    }

    public void process() {
        if (this.processed) {
            return;
        }
        int num = this.workbook.getNumberOfSheets();
        for (int i = 0; i < num; i++) {
            resetIndex(i);
            try {
                extractSheet(this.workbook.getSheetAt(i));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        this.processed = true;
    }

    @Override
    protected void tableToParagraphs(Table table) {

    }


    private void extractSheet(Sheet sheet) {
        if (isParseExcel()) {
            extractTablesFromSheet(sheet);
            extractChartsFromSheet(sheet);
            extractTextFromSheet(sheet);
        } else {
            extractSheetName(sheet);
        }
    }

    private void extractSheetName(Sheet sheet) {
        String sheetName = sheet.getSheetName();
        toParagraph(sheetName);
    }

    private void extractChartsFromSheet(Sheet sheet) {
        if (!(sheet instanceof XSSFSheet)) {
            extractHSSFChart((HSSFSheet) sheet);
            return;
        }
        extractXSSFChart((XSSFSheet) sheet);
    }

    private void extractTextFromSheet(Sheet sheet) {
        if (sheet instanceof HSSFSheet) {
            extractHSSFText((HSSFSheet) sheet);
        } else if (sheet instanceof XSSFSheet) {
            extractXSSFText((XSSFSheet) sheet);
        }
    }

    private void extractTablesFromSheet(Sheet sheet) {
        TreeBasedTable<Integer, Integer, Integer> t = TreeBasedTable.create();
        String sheetName = sheet.getSheetName();
        boolean cateFlag = false;
        for (String cat : catelog) {
            if (sheetName.contains(cat)) {
                cateFlag = true;
            }
        }
        TreeBasedTable<Integer, Integer, Table.Cell> gTable = TreeBasedTable.create();
        Map<Pair<Integer, Integer>, CellRangeAddress> merged = new HashMap<>();
        List<CellRangeAddress> mergedRegions = sheet.getMergedRegions();
        for (CellRangeAddress address : mergedRegions) {
            int row1 = address.getFirstRow();
            int col1 = address.getFirstColumn();
            Pair<Integer, Integer> key = Pair.of(row1, col1);
            merged.put(key, address);
        }
        int minColx = 99999; // big enough
        int rowIdx = 0;
        Iterator<Row> rows = sheet.rowIterator();
        while (rows.hasNext()) {
            rowIdx++;
            Row row = rows.next();
            int first = row.getFirstCellNum();
            int last = row.getLastCellNum();
            if (first < minColx) {
                minColx = first;
            }
            for (int colIdx = first; colIdx < last; colIdx++) {
                Cell cell = row.getCell(colIdx);
                Font font = null;

                Cell _cell = row.getCell(colIdx, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);

                if (1 <= _cell.getCellStyle().getBorderTop().getCode() || 1 <= _cell.getCellStyle().getBorderRight().getCode() || 1 <= _cell.getCellStyle().getBorderLeft().getCode() || 1 <= _cell.getCellStyle().getBorderBottom().getCode()) {
                    t.put(row.getRowNum(), _cell.getColumnIndex(), 1);
                } else {
                    t.put(row.getRowNum(), _cell.getColumnIndex(), 0);
                }
                if (null != cell) {
                    if (sheet.isColumnHidden(cell.getColumnIndex()) || row.getZeroHeight()) {
                        continue;
                    }
                    if (cell instanceof XSSFCell) {
                        font = ((XSSFCell) cell).getCellStyle().getFont();
                    } else {
                        font = ((HSSFCell) cell).getCellStyle().getFont(sheet.getWorkbook());
                    }
                    CellStyle cellStyle = cell.getCellStyle();
                    Pair<Integer, Integer> key = Pair.of(row.getRowNum(), colIdx);
                    CellRangeAddress m = merged.getOrDefault(key, null);
                    int rowSpan = 1;
                    int colSpan = 1;

                    if (null != m) {
                        int r = m.getLastRow();
                        int c = m.getLastColumn();
                        rowSpan = r - row.getRowNum() + 1;
                        colSpan = c - cell.getColumnIndex() + 1;
                    }
                    Table.Cell cell0 = new Table.Cell(row.getRowNum(), cell.getColumnIndex(), rowSpan, colSpan);
                    Object value = XDDFChartUtils.getCellValue(cell);
                    String dataFormat = cellStyle.getDataFormatString();
                    if (dataFormat != null && (dataFormat.contains("%") || dataFormat.contains("0.00%") || dataFormat.contains("0.0%"))) {
                        value += "%";
                    }
                    if (StringUtils.isNotBlank(value.toString())) {
                        cell0.setText(value.toString());
                        cell0.addInfo("left", cellStyle.getBorderLeft().getCode());
                        cell0.addInfo("right", sheet.getRow(row.getRowNum()).getCell(colIdx + colSpan - 1, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).getCellStyle().getBorderRight().getCode());
                        cell0.addInfo("top", cellStyle.getBorderTop().getCode());
                        cell0.addInfo("bottom", sheet.getRow(row.getRowNum() + rowSpan - 1).getCell(colIdx, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).getCellStyle().getBorderBottom().getCode());
                        cell0.setFontInfo(new FontInfo("", font.getFontHeightInPoints()));
                        gTable.put(row.getRowNum(), cell.getColumnIndex(), cell0);
                    }
                }
            }

        }
        if (gTable.isEmpty()) {
            return;
        }
        // 目录页不做表格拆分
        if (cateFlag) {
            Table table1 = new Table();
            for (Table.Cell cell : gTable.values()) {
                table1.addCell(cell.getRow(), cell.getColumn(), cell);
            }
            int notBlankCells = table1.getNotBlankCells();
            if (notBlankCells == 0) {
                return;
            }
            Pair<Integer, Integer> point = searchTableTitle(table1, gTable, null);
            Table table = correctTable(table1, point);
            saveSheetName(sheet, table);
            tables.add(table);
            return;
        }
        // 去除隐藏的超链接
        List<Table.Cell> filterCell = gTable.values()
                .stream().filter(cell -> {
                    Matcher matcher = OfficeRegex.HYPLINK_PATTERN.matcher(cell.getText());
                    if (matcher.find()) {
                        /**
                         * 判断超链接占文本大小,如果超链接比较小,说明不需要剔除
                         * 标准是文本长度应该是超链接长度2倍或者以上
                         */
                        int httpSize = matcher.group(0).trim().length();
                        int textSize = cell.getText().length();
                        int ratio = textSize / httpSize;
                        if (ratio == 1) {
                            // 横向
                            if (!gTable.contains(cell.getRow(), cell.getColumn() - 1) && !gTable.contains(cell.getRow(), cell.getColumn() + 1)) {
                                return true;
                            }
                            // 纵向
                            return !gTable.contains(cell.getRow() - 1, cell.getColumn()) && !gTable.contains(cell.getRow() + 1, cell.getColumn());
                        }
                    }
                    return false;
                }).collect(Collectors.toList());
        filterCell.forEach(cell -> gTable.remove(cell.getRow(), cell.getColumn()));
        int res = 1;
        try {
            res = splits(gTable, t, sheet);
        } catch (Throwable e) {
            res = 0;
        } finally {
            /**
             * 0 表示根据线拆分表格出现异常,采用单表模式解析
             * 1 表示没有线的信息,通过空行拆分表格
             */
            if (res == 0) {
                Table table1 = new Table();
                for (Table.Cell cell : gTable.values()) {
                    table1.addCell(cell.getRow(), cell.getColumn(), cell);
                }
                int notBlankCells = table1.getNotBlankCells();
                if (notBlankCells == 0) return;
                Pair<Integer, Integer> point = searchTableTitle(table1, gTable, null);
                Table table = correctTable(table1, point);
                tables.add(table);
            } else if (res == 1) {
                MutablePair<Integer, Integer> pair1 = null;
                MutablePair<Integer, Integer> pair2 = null;
                SortedSet<Integer> rowList = gTable.rowKeySet();
                out1:
                for (Integer row : rowList) {
                    Map<Integer, Table.Cell> column = gTable.row(row);
                    List<Integer> colList = column.keySet().stream().sorted(Comparator.naturalOrder()).collect(Collectors.toList());
                    for (Integer col : colList) {
                        if (gTable.contains(row, col) && gTable.get(row, col) != null && gTable.get(row, col).getText() != null) {
                            pair1 = MutablePair.of(row, col);
                            break out1;
                        }
                    }
                }
                List<Integer> colList = gTable.columnKeySet().stream().sorted(Comparator.naturalOrder()).collect(Collectors.toList());
                out2:
                for (Integer col : colList) {
                    Map<Integer, Table.Cell> rowSet = gTable.column(col);
                    List<Integer> rowLists = rowSet.keySet().stream().sorted(Comparator.naturalOrder()).collect(Collectors.toList());
                    for (Integer row : rowLists) {
                        if (gTable.contains(row, col) && gTable.get(row, col) != null && gTable.get(row, col).getText() != null) {
                            pair2 = MutablePair.of(row, col);
                            break out2;
                        }
                    }
                }
                if (pair1 == null) {
                    partitonTable(MutablePair.of(0, 0), gTable, sheet);
                } else if (pair1.equals(pair2)) {
                    partitonTable(pair1, gTable, sheet);
                } else {
                    assert pair2 != null;
                    partitonTable(MutablePair.of(pair1.getLeft(), pair2.getRight()), gTable, sheet);
                }
            } else {
            }
        }

    }


    /**
     * 标记连通域
     *
     * @param i
     * @param j
     */
    private void dealBwlabe(int i, int j, TreeBasedTable<Integer, Integer, Integer> t) {
        // TODO Auto-generated method stub
        //上
        if (null != t.get(i - 1, j) && t.get(i - 1, j) == 1) {
            t.put(i - 1, j, counter);
            dealBwlabe(i - 1, j, t);
        }
        //左
        if (null != t.get(i, j - 1) && t.get(i, j - 1) == 1) {
            t.put(i, j - 1, counter);
            dealBwlabe(i, j - 1, t);
        }
        //下
        if (null != t.get(i + 1, j) && t.get(i + 1, j) == 1) {
            t.put(i + 1, j, counter);
            dealBwlabe(i + 1, j, t);
        }
        //右
        if (null != t.get(i, j + 1) && t.get(i, j + 1) == 1) {
            t.put(i, j + 1, counter);
            dealBwlabe(i, j + 1, t);
        }
    }

    /**
     * 根据表格边框框(线)信息表格拆分
     *
     * @param gTable
     */
    private int splits(TreeBasedTable<Integer, Integer, Table.Cell> gTable, TreeBasedTable<Integer, Integer, Integer> t, Sheet sheet) {
        SortedSet<Integer> rowKey = t.rowKeySet();
        Integer firstRow = rowKey.first();
        Integer lastRow = rowKey.last();
        SortedSet<Integer> columnKey = t.columnKeySet().parallelStream().sorted().collect(Collectors.toCollection(TreeSet::new));
        Integer firstCol = columnKey.first();
        Integer lastCol = columnKey.last();
        counter = 1;
        Table table = null;
        for (int row = firstRow; row <= lastRow; row++) {
            for (int col = firstCol; col <= lastCol; col++) {
                if (gTable.contains(row, col)) {
                    Integer a = t.get(row, col);
                    if (a == 1) {
                        counter++;
                        t.put(row, col, counter);
                        dealBwlabe(row, col, t);
                    }

                }
            }
        }

        /**
         * 连通域的最大矩的坐标点
         */
        int o = 2;
        Map<Integer, List<Pair<Integer, Integer>>> region = new HashMap<>();
        while (o <= counter) {
            List<Pair<Integer, Integer>> tuples = new ArrayList<>();
            for (int row = firstRow; row <= lastRow; row++) {
                for (int col = firstCol; col <= lastCol; col++) {
                    if (t.contains(row, col) && o == t.get(row, col)) {
                        tuples.add(Pair.of(row, col));
                    }
                }
            }
            region.put(o, tuples);
            o++;
        }
        for (Map.Entry<Integer, List<Pair<Integer, Integer>>> entry : region.entrySet()) {
            List<Pair<Integer, Integer>> value = entry.getValue();
            if (1 >= value.size()) {
                continue;
            }
            TreeSet<Integer> rows = value.stream().map(Pair::getLeft).sorted(Comparator.naturalOrder()).collect(Collectors.toCollection(TreeSet::new));
            TreeSet<Integer> cols = value.stream().map(Pair::getRight).sorted(Comparator.naturalOrder()).collect(Collectors.toCollection(TreeSet::new));
            Table _table = new Table();
            for (int i = rows.first(); i <= rows.last(); i++) {
                for (int j = cols.first(); j <= cols.last(); j++) {
                    if (gTable.contains(i, j)) {
                        Table.Cell cell = gTable.get(i, j).copyTo(i - rows.first(), j - cols.first());
                        cell.setFontInfo(gTable.get(i, j).getFontInfo());
                        _table.addCell(i - rows.first(), j - cols.first(), cell);
                    }
                }
            }
            searchTableTitle(_table, gTable, Pair.of(rows.first(), cols.first()));
            _table.setPageIndex(currentPageIndex);
            _table.setItemIndex(this.getItemIndex());
            _table.setIndex(getAndIncTableIndex());
            Pair<Integer, Integer> pair = selectFirstPoint(_table);
            _table = correctTable(_table, pair);
            saveSheetName(sheet, _table);
            tables.add(_table);
        }
        return counter;
    }

    private void saveSheetName(Sheet sheet, Table table) {
        String sheetName = sheet.getSheetName();
        if (!StringUtils.contains(sheetName, "Sheet")) {
            String shoes = table.getShoes();
            shoes = String.format("%s\t\t\t\t%s", sheetName, shoes);
            table.setShoes(shoes);
        }
    }

    /**
     * 根据空行空列拆分表格
     *
     * @param pair0
     * @param gTable
     */
    private void partitonTable(MutablePair<Integer, Integer> pair0, TreeBasedTable<Integer, Integer, Table.Cell> gTable, Sheet sheet) {
        int x = pair0.getLeft();
        int y = pair0.getRight();
        List<Integer> rowList = new ArrayList<>();
        List<Integer> colList = new ArrayList<>();
        List<Integer> columnKeys = gTable.columnKeySet().stream().sorted(Comparator.naturalOrder()).collect(Collectors.toList());
        List<Integer> rowKeys = gTable.rowKeySet().stream().sorted(Comparator.naturalOrder()).collect(Collectors.toList());
        Integer col = columnKeys.get(0);
        Integer row = rowKeys.get(0);
        int count = 0;
        // 剔除单元格合并导致的空行
        for (int i = x; i < row; i++) {
            if (!rowKeys.contains(i)) {
                if (count > 0) {
                    Integer _row = rowKeys.get(count - 1);
                    Map<Integer, Table.Cell> cellMap = gTable.row(_row);
                    Object[] array = cellMap.keySet().toArray();
                    Table.Cell cell = cellMap.get(array[0]);
                    int rowSpan = cell.getRowSpan();
                    int flag = rowSpan - (i - _row);
                    if (flag != 1) {
                        rowList.add(i);
                    }
                } else {
                    rowList.add(i);
                }
            } else {
                count += 1;
            }
        }
        rowList.add(row + 1);
        count = 0;
        for (int i = y; i < col; i++) {
            if (!columnKeys.contains(i)) {
                if (count > 0) {
                    Integer _col = columnKeys.get(count - 1);
                    Map<Integer, Table.Cell> cellMap = gTable.column(_col);
                    Object[] array = cellMap.keySet().stream().sorted(Comparator.naturalOrder()).toArray();
                    Table.Cell cell = cellMap.get(array[0]);
                    int colSpan = cell.getColSpan();
                    int flag = colSpan - (i - _col);
                    if (flag != 1) {
                        colList.add(i);
                    }
                } else {
                    colList.add(i);
                }
            } else {
                count += 1;
            }

        }
        colList.add(col + 1);
        for (Integer row1 : rowList) {
            for (Integer col1 : colList) {
                part(x, y, row1, col1, gTable);
                y = col1 + 1;
                if (y >= colList.get(colList.size() - 1)) {
                    y = pair0.getRight();
                }
            }
            x = row1 + 1;
        }
    }

    /**
     * 表格拆分
     *
     * @param x1
     * @param y1
     * @param m
     * @param n
     * @param gTable
     */
    private void part(int x1, int y1, int m, int n, TreeBasedTable<Integer, Integer, Table.Cell> gTable) {
        int x2 = x1, x3 = x1;
        int y2 = y1, y3 = y1;
        Table table1 = new Table();
        while (y2 < n) {
            while (x2 < m) {
                if (gTable.contains(x2, y2)) {
                    Table.Cell cell1 = gTable.get(x2, y2);
                    if (cell1.getText() != null && ("".equals(cell1.getText().trim()) || cell1.getText().contains("返回首页") || cell1.getText().contains("返回目录") || cell1.getText().contains("返回封面"))) {
                        x2 += 1;
                        continue;
                    }
                    Table.Cell cell2 = cell1.copyTo(x2 - x3, y2 - y3);
                    table1.addCell(x2 - x3, y2 - y3, cell2);
                }
                x2 += 1;
            }
            x2 = x1;
            y2 += 1;
        }
        if (table1.getRowCount() > 1 && table1.getColumnCount() > 1) {
            Pair<Integer, Integer> point = searchTableTitle(table1, gTable, null);
            Table table = correctTable(table1, point);
            tables.add(table);
        } else {
            StringBuilder text1 = new StringBuilder();
            int rowCount = table1.getRowCount();
            int columnCount = table1.getColumnCount();
            for (int r = 0; r < rowCount; ++r) {
                for (int c = 0; c < columnCount; ++c) {
                    if (table1.getCell(r, c) != null) {
                        text1.append(Objects.requireNonNull(table1.getCell(r, c)).getText()).append(" ");
                    }
                }
            }
            String s = text1.toString().trim();
            if (!"".equals(s) && (!"返回首页".equals(s) || !"返回目录".equals(s))) {
                toParagraph(text1.toString());
            }
        }
    }

    /**
     * 定位table的左端点
     *
     * @param table
     * @return
     */
    private Pair<Integer, Integer> selectFirstPoint(Table table) {
        List<Table.Cell> cells = table.getCells();
        Ordering<Table.Cell> orderingRow = new Ordering<Table.Cell>() {
            @Override
            public int compare(@Nullable Table.Cell c1, @Nullable Table.Cell c2) {
                if (c1 == null) {
                    return 1;
                } else if (c1.getRow() < c2.getRow()) {
                    return -1;
                } else {
                    return 1;
                }
            }
        };
        Ordering<Table.Cell> orderingCol = new Ordering<Table.Cell>() {
            @Override
            public int compare(@Nullable Table.Cell c1, @Nullable Table.Cell c2) {
                if (c1 == null) {
                    return 1;
                } else if (c1.getColumn() < c2.getColumn()) {
                    return -1;
                } else {
                    return 1;
                }
            }
        };
        Table.Cell firstRow = orderingRow.min(cells);
        Table.Cell firstCol = orderingCol.min(cells);
        return Pair.of(firstRow.getRow(), firstCol.getColumn());
    }

    /**
     * 矫正table
     *
     * @param table
     * @param firstPoint
     * @return
     */
    private Table correctTable(Table table, Pair<Integer, Integer> firstPoint) {
        List<Table.Cell> cells = table.getCells();
        int rowCount = table.getRowCount();
        int column = firstPoint.getRight();
        int row = firstPoint.getLeft();
        while (row <= rowCount) {
            if (table.isEmptyRow(row)) {
                row += 1;
            } else {
                break;
            }
        }
        Table table1 = new Table();
        table1.setPageIndex(currentPageIndex);
        table1.setItemIndex(this.getItemIndex());
        table1.setIndex(getAndIncTableIndex());
        table1.setUnit(table.getUnitTextLine(), table.getUnitSet());
        table1.setCaps(table.getCaps());
        table1.setShoes(table.getShoes());
        int row1 = 0;
        for (Table.Cell cell :
                cells) {
            String text = cell.getText();
            Matcher matcher = OfficeRegex.NOTES_PATTERN.matcher(text);
            if (matcher.find()) {
                row1 = cell.getRow();
            }
            Table.Cell cell2 = cell.copyTo(cell.getRow() - row, cell.getColumn() - column);
            table1.addCell(cell.getRow() - row, cell.getColumn() - column, cell2);
        }
        if (row1 != 0) {
            handleNotes(table1, row1 - row);
        }
        table1.setTitle(table.getTitle());
        return table1;

    }

    /**
     * 处理单位和table之上的notes
     *
     * @param row
     */
    public void handleNotes(Table table, int row) {
        int rowCount = table.getRowCount();
        boolean flag = true;
        int row1 = row;
        while (row < rowCount) {
            Table.Cell cell = table.getCell(row, 0);
            if (cell != null && StringUtils.isNotBlank(cell.getText())) {
                toParagraph(cell.getText());
                table.delRow(row);
            }
            row++;
        }
        while (row1 > 0) {
            row1--;
            flag = table.isEmptyRow(row1);
            if (flag) {
                table.delRow(row1);
            } else {
                break;
            }
        }
    }

    private void toParagraph(String text) {
        Paragraph p = new Paragraph(this.currentPageIndex, new Rectangle2D.Float(0, 0, 0, 0));
        p.setItemIndex(this.getItemIndex());
        Paragraph.Text text1 = new Paragraph.Text(text);
        p.addText(text1);
        paragraphs.add(p);
    }

    private void toTitle(String text) {
        Paragraph p = new Paragraph(this.currentPageIndex, new Rectangle2D.Float(0, 0, 0, 0));
        p.setItemIndex(this.getItemIndex());
        Paragraph.Text text1 = new Paragraph.Text(text, new FontInfo("", 15, java.awt.Color.BLACK, FontInfo.Style.BOLD.getStyle()));
        p.addText(text1);
        paragraphs.add(p);
    }


    private void extractHSSFText(HSSFSheet sheet) {
        HSSFPatriarch drawingPatriarch = sheet.getDrawingPatriarch();
        if (drawingPatriarch != null) {
            for (HSSFShape hssfShape : drawingPatriarch.getChildren()) {
                if (hssfShape instanceof HSSFSimpleShape) {
                    Paragraph p = new Paragraph(this.currentPageIndex, new Rectangle2D.Float(0, 0, 0, 0));
                    p.setItemIndex(this.getItemIndex());
                    HSSFRichTextString richTextString = null;
                    try {
                        richTextString = ((HSSFSimpleShape) hssfShape).getString();
                    } catch (NullPointerException e) {
                        // TODO poi内部可能抛出控制针
                    }
                    if (richTextString != null) {
                        String text1 = richTextString.getString();
                        if (!"".equals(text1)) {
                            Paragraph.Text text = new Paragraph.Text(text1);
                            p.addText(text);
                            paragraphs.add(p);
                        }

                    }
                }
            }
        }

    }

    private void extractXSSFText(XSSFSheet sheet) {
        XSSFDrawing drawing = sheet.getDrawingPatriarch();
        if (drawing != null) {
            StringBuilder text1 = new StringBuilder();
            XmlObject[] t = drawing.getCTDrawing().selectPath("declare namespace a='http://schemas.openxmlformats.org/drawingml/2006/main' declare namespace xdr='http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing' .//a:t");
            for (XmlObject element : t) {
                NodeList kids = element.getDomNode().getChildNodes();
                final int count = kids.getLength();
                for (int n = 0; n < count; n++) {
                    Node kid = kids.item(n);
                    if (kid instanceof Text) {
                        text1.append(kid.getNodeValue());
                    }
                }
            }
            Paragraph p = new Paragraph(this.currentPageIndex, new Rectangle2D.Float(0, 0, 0, 0));
            p.setItemIndex(this.getItemIndex());
            Paragraph.Text text = new Paragraph.Text(text1.toString());
            p.addText(text);
            paragraphs.add(p);
        }

    }

    private void extractHSSFChart(HSSFSheet sheet) {
        HSSFChart[] charts01 = HSSFChart.getSheetCharts(sheet);
        for (HSSFChart chart : charts01) {
            JsonObject jsonChart = XDDFChartUtils.handleHSSFChart(chart, sheet);
            if (jsonChart != null) {
                wrapperAddChart(jsonChart);
            }
        }
        HSSFPatriarch drawingPatriarch = sheet.getDrawingPatriarch();
        if (drawingPatriarch == null) {
            return;
        }
        for (HSSFShape hssfShape : drawingPatriarch.getChildren()) {
            if (hssfShape instanceof HSSFPicture) {
                saveImage((HSSFPicture) hssfShape, sheet);
            }
        }
    }


    private void extractXSSFChart(XSSFSheet sheet) {
        for (POIXMLDocumentPart part : sheet.getRelations()) {
            if (part instanceof XSSFDrawing) {
                XSSFDrawing drawing = (XSSFDrawing) part;
                List<XSSFChart> charts01 = drawing.getCharts();
                for (XSSFChart chart : charts01) {
                    JsonObject jsonChart = XDDFChartUtils.handleXDDFChart(chart, theme);
                    if (jsonChart != null) {
                        wrapperAddChart(jsonChart);
                    }

                }
                List<XSSFShape> shapes = drawing.getShapes();
                for (XSSFShape shape : shapes) {
                    if (shape instanceof XSSFPicture) {
                        saveImage((XSSFPicture) shape, sheet);
                    }
                }
            } else {
                // TODO
                logger.warn("TODO: {}", part.getClass());
            }
        }
    }

    /**
     * @param picture
     * @param sheet
     */
    private void saveImage(Picture picture, Sheet sheet) {
        PictureData pictureData = picture.getPictureData();
        String ext = pictureData.suggestFileExtension();
        byte[] data = pictureData.getData();
        if (data == null || data.length <= 2000) {
            logger.warn("Empty Image");
            return;
        }
        Image image = Image.toImage(data, ext);
        if (image != null) {
            image.setPageIndex(currentPageIndex);
            String shapeName = picture.getShapeName();
            if (shapeName != null) {
                image.setTitle(shapeName);
            }
            Chart chart = new Chart(ChartType.BITMAP_CHART);
            // TODO 标题定位
            short col1 = picture.getPreferredSize().getCol1();
            int row1 = picture.getPreferredSize().getRow1();
            int col2 = picture.getPreferredSize().getCol2();
            // 标题范围三行以内
            chart.setTitle(XDDFChartUtils.searchTitle(sheet, row1, col1, col2));
            chart.setPageIndex(this.currentPageIndex);
            chart.setItemIndex(this.getItemIndex());
            chart.setIndex(this.getAndIncChartIndex());
            chart.setImage(image);
            this.charts.add(chart);
        }

    }


    /**
     * 将jsonChart组装好
     *
     * @param jsonChart
     */
    private void wrapperAddChart(JsonObject jsonChart) {
        Chart chart = new Chart();
        chart.setPageIndex(this.currentPageIndex);
        chart.setItemIndex(this.getItemIndex());
        chart.setIndex(this.getAndIncChartIndex());
        chart.setHighcharts(jsonChart);
        String title = jsonChart.get("title").getAsJsonObject().get("text").getAsString();
        if (title.length() > 15) {
            title = title.substring(0, 5);
        }
        chart.setTitle(title);
        charts.add(chart);
    }

    /**
     * 定位table的标题
     *
     * @param table
     * @return
     */
    private Pair<Integer, Integer> searchTableTitle(Table table, TreeBasedTable<Integer, Integer, Table.Cell> gTable, Pair<Integer, Integer> point) {
        int columnCount = table.getColumnCount();
        if (null == point) {
            point = selectFirstPoint(table);
        }
        float fontSize = 10;
        Integer firstRow = point.getLeft();
        Integer firstCol = point.getRight();
        boolean flag = true;
        for (int i = firstCol; i <= columnCount; i++) {
            Table.Cell cell = table.getCell(firstRow, i);
            if (null != cell) {
                fontSize = cell.getFontInfo().getFontSize();
                int column = cell.getColSpan();
                if (column >= columnCount && column > 1 || (fontSize >= 14)) {
                    if (StringUtils.isNotBlank(cell.getText()) && !filterTitle(cell.getText())) {
                        String text = cell.getText();
                        table.setTitle(text);
                        table.addCell(firstRow, i, null);
                        point = Pair.of(firstRow + 1, firstCol);
                        flag = false;
                        break;
                    }
                }
            }
        }
        String date = "";
        TreeSet<String> titles = Sets.newTreeSet();
        // flag == true 说明标题在表格外部
        if (flag) {
            Map<String, Object> map = searchTableOuter(gTable, point.getLeft(), point.getRight(), columnCount + point.getRight(), fontSize);
            String unit = (String) map.get("unit");
            date = (String) map.get("date");
            titles = (TreeSet<String>) map.get("titles");
            table.setUnit(unit, Sets.newHashSet(unit));
            if (titles.size() > 0) {
                String title = titles.first();
                titles.remove(title);
                table.setCaps(date);
                String collect = titles.stream().collect(Collectors.joining(","));
                table.setShoes(collect == null ? "" : collect);
                table.setTitle(title == null ? "" : title);
            }

        }
        titles.stream().sorted(Comparator.reverseOrder()).forEach(this::toTitle);
        toTitle(table.getTitle() == null ? "" : table.getTitle());
        toParagraph(date == null ? "" : date);
        toParagraph(table.getUnitTextLine() == null ? "" : table.getUnitTextLine());
        return point;
    }

    /**
     * 表格外部搜索标题
     */
    private Map<String, Object> searchTableOuter(TreeBasedTable<Integer, Integer, Table.Cell> gTable, int row, int col, int col1, float fontSize) {
        TreeSet<String> treeSet = Sets.newTreeSet();
        String u = "";
        int j0 = col > 1 ? col - 1 : 0;
        StringBuilder date = new StringBuilder();
        for (int i = row - 1; i > row - 10; i--) {
            for (int j = j0; j <= col1; j++) {
                if (i >= 0 && j >= 0) {
                    if (gTable.contains(i, j)) {
                        Table.Cell cell = gTable.get(i, j);
                        int colSpan = cell.getColSpan();
                        String text = cell.getText();
                        FontInfo fontInfo = cell.getFontInfo();
                        Matcher matcher = OfficeRegex.UNIT_PATTERN.matcher(text);
                        if (matcher.find()) {
                            u = text;
                            continue;
                        }
                        if ((colSpan >= col1 - col - 3 && colSpan > 1) || (fontInfo.getFontSize() > fontSize)) {
                            if (!filterTitle(text)) {
                                treeSet.add(text);
                            } else {
                                date.append(",").append(text);
                            }
                        }
                    }
                }

            }
        }
        HashMap<String, Object> map = Maps.newHashMap();
        map.put("unit", u);
        map.put("date", date.length() > 0 ? date.substring(1).toString() : "");
        map.put("titles", treeSet);
        return map;
    }

    private boolean filterTitle(String title) {
        for (String s :
                titleFilters) {
            Matcher matcher = OfficeRegex.DATA_PATTERN.matcher(title);
            if (title.startsWith(s) || matcher.find()) {
                return true;
            }
        }
        return false;
    }


    public static void main(String[] args) throws Exception {
//        ExcelDocument excelDocument = new ExcelDocument(new FileInputStream(args[0]));
//        excelDocument.process();
//        List<Chart> charts = excelDocument.getCharts();
//        List<Table> tables = excelDocument.getTables();
//        logger.info("{}", charts);
        org.w3c.dom.Document doc = null;
        try {
            doc = ExcelToFoConverter.process(new File(args[0]));
        } catch (IOException e) {
        } catch (ParserConfigurationException e) {
        }

        DOMSource domSource = new DOMSource(doc);
        StreamResult streamResult = new StreamResult(new File(args[1]));

        TransformerFactory tf = TransformerFactory.newInstance();
        Transformer serializer = tf.newTransformer();
        // TODO set encoding from a command argument
        serializer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        serializer.setOutputProperty(OutputKeys.INDENT, "no");
        serializer.setOutputProperty(OutputKeys.METHOD, "html");
        serializer.transform(domSource, streamResult);
        FileInputStream is = new FileInputStream(args[0]);
        HSSFWorkbook wb = new HSSFWorkbook();
        is.close();
        org.apache.poi.hssf.extractor.ExcelExtractor extractor = new ExcelExtractor(wb);
        extractor.setIncludeSheetNames(true);
        extractor.setFormulasNotResults(false);
        extractor.setIncludeCellComments(true);
        extractor.setIncludeBlankCells(true);
        extractor.setIncludeHeadersFooters(true);
        System.out.println(extractor.getText());
        extractor.close();
        wb.close();
    }


}