package com.abcft.pdfextract.core.office;

import com.abcft.pdfextract.spi.BaseTable;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.annotation.Nullable;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.awt.geom.Rectangle2D;
import java.io.StringWriter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.abcft.pdfextract.core.office.OfficeRegex.TABLE_IDS_PATTERNS;

public class Table implements BaseTable {

    private String unitTextLine = "";
    private Set<String> unitSet = null;
    private String shoes = "";
    private String caps = "";


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

    public String getCaps() {
        return caps;
    }

    public void setCaps(String caps) {
        this.caps = caps;
    }

    public String getShoes() {
        return shoes;
    }

    public void setShoes(String shoes) {
        this.shoes = shoes;
    }

    private static final Logger logger = LogManager.getLogger(Table.class);

    static class Cell {
        private int row;
        private int column;
        private int rowSpan;
        private int colSpan;
        private String text;
        private Rectangle2D rect;
        private FontInfo fontInfo;
        private Map<String, Object> info;

        public Cell(int row, int col, int rowSpan, int colSpan, String text) {
            this.row = row;
            this.column = col;
            this.rowSpan = rowSpan;
            this.colSpan = colSpan;
            this.info = new HashMap<>();
            this.text = text;
        }

        public Cell(int row, int col, int rowSpan, int colSpan) {
            this(row, col, rowSpan, colSpan, "");
        }

        public Cell(int row, int col) {
            this(row, col, 1, 1);
        }

        private Cell(Cell cell) {
            this.row = cell.row;
            this.column = cell.column;
            this.rowSpan = cell.rowSpan;
            this.colSpan = cell.colSpan;
            this.text = cell.text;
            this.rect = cell.rect;
            this.info = cell.info;
        }

        @Override
        public String toString() {
            return String.format("[Cell (%d,%d), (%d,%d), %s]", row, column, rowSpan, colSpan, text);
        }


        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Cell cell = (Cell) o;
            return row == cell.row &&
                    column == cell.column &&
                    rowSpan == cell.rowSpan &&
                    colSpan == cell.colSpan &&
                    Objects.equals(text, cell.text);
        }

        @Override
        public int hashCode() {
            return Objects.hash(row, column, rowSpan, colSpan, text);
        }

        public int getRow() {
            return row;
        }

        public void setRowSpan(int rowSpan) {
            this.rowSpan = rowSpan;
        }

        public void setColSpan(int colSpan) {
            this.colSpan = colSpan;
        }

        public int getColumn() {
            return column;
        }

        public int getRowSpan() {
            return rowSpan;
        }

        public int getColSpan() {
            return colSpan;
        }

        public String getText() {
            return text;
        }

        public Rectangle2D getRect() {
            return rect;
        }

        public void setText(String text) {
            this.text = text;
        }

        public void setRect(Rectangle2D rect) {
            this.rect = rect;
        }

        public Object getInfo(String key) {
            return info.getOrDefault(key, null);
        }

        public void addInfo(String key, Object info) {
            this.info.put(key, info);
        }

        public boolean hasInfo(String key) {
            return this.info.containsKey(key);
        }

        public FontInfo getFontInfo() {
            return fontInfo;
        }

        public void setFontInfo(FontInfo fontInfo) {
            this.fontInfo = fontInfo;
        }

        public Cell copyTo(int row, int column) {
            Cell other = new Cell(this);
            other.row = row;
            other.column = column;
            return other;
        }
    }

    private Map<Pair<Integer, Integer>, Cell> cells;
    private Map<Integer, CellRange> cellRanges;
    private int rowCount;
    private int columnCount;
    private int pageIndex;
    private int itemIndex;
    private int index;
    private String title;
    private int notBlankCells;

    public Table() {
        cells = new HashMap<>();
        cellRanges = new HashMap<>();
        rowCount = 0;
        columnCount = 0;
        notBlankCells = 0;
    }

    @Override
    public String toString() {
        return String.format("[Table (%dx%d), (page:%d,index:%d) %s]",
                rowCount, columnCount, pageIndex, index, title);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Table table = (Table) o;
        return rowCount == table.rowCount &&
                columnCount == table.columnCount &&
                pageIndex == table.pageIndex &&
                index == table.index &&
                notBlankCells == table.notBlankCells &&
                Objects.equals(cells, table.cells) &&
                Objects.equals(cellRanges, table.cellRanges) &&
                Objects.equals(title, table.title);
    }

    @Override
    public int hashCode() {
        return Objects.hash(cells, cellRanges, rowCount, columnCount, pageIndex, index, notBlankCells, title);
    }

    @Nullable
    public Cell getCell(int row, int col) {
        return cells.getOrDefault(Pair.of(row, col), null);
    }

    public List<Cell> getCells() {
        return new ArrayList<>(cells.values());
    }

    public void addCell(int row, int col, Cell cell) {
        if (cell == null) {
            return;
        }
        this.cells.put(Pair.of(row, col), cell);
        if (this.rowCount < row + 1) {
            this.rowCount = row + 1;
        }
        if (this.columnCount < col + 1) {
            this.columnCount = col + 1;
        }
        if (StringUtils.isNotBlank(cell.getText())) {
            this.notBlankCells += 1;
        }
        this.addCellRange(row, col, cell.getRowSpan(), cell.getColSpan());
    }

    public void delCell(int row, int col) {
        if (this.isNormalCell(row, col)) {
            Cell cell = this.getCell(row, col);
            this.cells.remove(Pair.of(row, col));
            this.delCellRange(row, col, cell.getRowSpan(), cell.getColSpan());
            if (StringUtils.isNotBlank(cell.getText())) {
                this.notBlankCells -= 1;
            }
        } else if (this.isMergedCell(row, col)) {
            this.delCellRange(row, col, 1, 1);
        }
    }

    public void delRow(int row) {
        for (int col = 0; col < this.columnCount; col++) {
            delCell(row, col);
        }
        this.rowCount = this.rowCount - 1;
    }

    public int getPageIndex() {
        return pageIndex;
    }

    public void setPageIndex(int pageIndex) {
        this.pageIndex = pageIndex;
    }

    public int getItemIndex() {
        return itemIndex;
    }

    public void setItemIndex(int itemIndex) {
        this.itemIndex = itemIndex;
    }

    public int getIndex() {
        return index;
    }

    @Override
    public String getId() {
        return String.format("table_%d_%d", pageIndex, index);
    }

    public void setIndex(int index) {
        this.index = index;
    }

    public String getTitle() {
        return title;
    }

    @Override
    public String toHtml() {
        DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance();
        try {
            DocumentBuilder docBuilder = docFactory.newDocumentBuilder();
            Document doc = docBuilder.newDocument();
            Element tableElement = doc.createElement("table");
            tableElement.setAttribute("id", this.getId());
            tableElement.setAttribute("data-page", String.valueOf(this.getPageIndex() + 1));
            tableElement.setAttribute("class", "pdf-table");
            doc.appendChild(tableElement);
            int rowCount = this.getRowCount();
            int colCount = this.getColumnCount();
            int i = 0;
            while (i < rowCount) {
                int j = 0;
                Element tr = doc.createElement("tr");
                while (j < colCount) {
                    if (this.isNormalCell(i, j)) {
                        Cell cell = this.getCell(i, j);
                        String text = cell.getText();
                        Element td = doc.createElement("td");
                        if (cell.getRowSpan() > 1) {
                            td.setAttribute("rowspan", String.valueOf(cell.getRowSpan()));
                        }
                        if (cell.getColSpan() > 1) {
                            td.setAttribute("colspan", String.valueOf(cell.getColSpan()));
                        }
                        String[] lines = text.split("\n");
                        for (int k = 0; k < lines.length; k++) {
                            String line = lines[k];
                            if (StringUtils.isBlank(line)) {
                                continue;
                            }
                            if (k > 0) {
                                td.appendChild(doc.createElement("br"));
                            }
                            td.appendChild(doc.createTextNode(line));
                        }
                        tr.appendChild(td);
                    } else if (!this.isMergedCell(i, j)) {
                        // 补空的格子
                        Element td = doc.createElement("td");
                        td.appendChild(doc.createTextNode(""));
                        tr.appendChild(td);
                    }
                    j += 1;
                }
                tableElement.appendChild(tr);
                i += 1;
            }
            TransformerFactory transformerFactory = TransformerFactory.newInstance();
            Transformer transformer = transformerFactory.newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            DOMSource source = new DOMSource(doc);
            StringWriter writer = new StringWriter();
            StreamResult target = new StreamResult(writer);
            transformer.transform(source, target);
            return writer.toString();
        } catch (Exception e) {
            logger.error("{} toHtml Error: {}", this.toString(), e);
            e.printStackTrace();
        }
        return "";
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public int getRowCount() {
        return rowCount;
    }

    public int getColumnCount() {
        return columnCount;
    }

    public int getNotBlankCells() {
        return notBlankCells;
    }

    public boolean isAllBlank() {
        return notBlankCells == 0;
    }

    public boolean isValidCell(int row, int column) {
        return this.cellRanges.containsKey(row) && this.cellRanges.get(row).contains(column);
    }

    public boolean isNormalCell(int row, int column) {
        return this.cells.containsKey(Pair.of(row, column));
    }

    public boolean isMergedCell(int row, int column) {
        return this.isValidCell(row, column) && !this.isNormalCell(row, column);
    }

    public void setCellRowSpan(int row, int column, int rowSpan) {
        Cell cell = this.getCell(row, column);
        if (cell == null) {
            throw new RuntimeException(String.format("setCellRowSpan for empty cell (%d, %d)", row, column));
        }
        int cellRowSpan = cell.getRowSpan();
        if (cellRowSpan == rowSpan) {
            return;
        }
        if (cellRowSpan > rowSpan) {
            this.delCellRange(row, column, cell.getRowSpan(), cell.getColSpan());
        }
        cell.rowSpan = rowSpan;
        this.addCellRange(row, column, cell.getRowSpan(), cell.getColSpan());
    }

    public void setCellColSpan(int row, int column, int colSpan) {
        Cell cell = this.getCell(row, column);
        if (cell == null) {
            throw new RuntimeException(String.format("setCellRowSpan for empty cell (%d, %d)", row, column));
        }
        int cellColSpan = cell.getColSpan();
        if (cellColSpan == colSpan) {
            return;
        }
        if (cellColSpan > colSpan) {
            this.delCellRange(row, column, cell.getRowSpan(), cell.getColSpan());
        }
        cell.colSpan = colSpan;
        this.addCellRange(row, column, cell.getRowSpan(), cell.getColSpan());
    }

    /**
     * 处理单位和table之上的notes
     *
     * @param row
     */
    public void handleUnit(int row) {
        int columnCount = this.getColumnCount();
        final int limit = this.rowCount;
        while (row <= limit) {
            Pair<Boolean, Table.Cell> tuple = this.singleRow(row);
            if (tuple.getLeft()) {
                Table.Cell cell = tuple.getRight();
                Matcher matcher = OfficeRegex.UNIT_PATTERN.matcher(cell.getText());
                this.setCellColSpan(cell.getRow(), cell.getColumn(), columnCount);
                if (matcher.find()) {
                    break;
                }
            }
            row++;
        }

    }

    private void addCellRange(int row, int column, int rowSpan, int colSpan) {
        for (int r = 0; r < rowSpan; r++) {
            int rr = row + r;
            if (this.cellRanges.containsKey(rr)) {
                this.cellRanges.get(rr).addRange(column, column + colSpan - 1);
            } else {
                this.cellRanges.put(rr, new CellRange(column, column + colSpan - 1));
            }
        }
    }

    private void delCellRange(int row, int column, int rowSpan, int colSpan) {
        for (int r = 0; r < rowSpan; r++) {
            this.cellRanges.get(row + r).delRange(column, column + colSpan - 1);
        }
    }

    public void correctRowSpan() {
        for (int r = 0; r < this.rowCount; r++) {
            int minRowSpan = Integer.MAX_VALUE;
            for (int c = 0; c < this.columnCount; c++) {
                Table.Cell cell = this.getCell(r, c);
                if (cell == null) {
                    continue;
                }
                if (cell.rowSpan < minRowSpan) {
                    minRowSpan = cell.rowSpan;
                }
                if (minRowSpan == 1) {
                    break;
                }
            }
            if (minRowSpan == Integer.MAX_VALUE || minRowSpan == 1) {
                continue;
            }

            for (int c = 0; c < this.columnCount; c++) {
                Table.Cell cell = getCell(r, c);
                if (cell != null) {
                    this.setCellRowSpan(r, c, cell.getRowSpan() - minRowSpan + 1);
                }
            }
            while (minRowSpan > 1) {
                r += 1;
                this.addCell(r, 0, new Cell(r, 0, 1, this.columnCount));
                minRowSpan -= 1;
            }
        }
    }

    public boolean isEmptyRow(int row) {
        boolean isEmpty = true;
        for (int c = 0; c < this.columnCount; ++c) {
            Cell cell = this.getCell(row, c);
            if (cell != null && StringUtils.isNotBlank(cell.getText())) {
                isEmpty = false;
                break;
            }
        }
        return isEmpty;
    }

    public Pair<Boolean, Cell> singleRow(int row) {
        int count = 0;
        Cell cell = null;
        for (int c = 0; c < this.columnCount; ++c) {
            cell = this.getCell(row, c);
            if (cell != null && StringUtils.isNotBlank(cell.getText())) {
                count += 1;
                break;
            }
        }
        return Pair.of(count == 1, cell);
    }

    public boolean shouldBeChart() {
        return cells.values().stream().anyMatch(cell -> cell.info.containsKey("chart_index"));
    }

    public boolean shouldBeParagraph() {
        if (this.getRowCount() == 1) {
            return true;
        } else if (this.getNotBlankCells() == 1) {
            return true;
        } else if (this.getRowCount() == 3 && this.isEmptyRow(1)) {
            return true;
        } else if (hasLongText()) {
            if (this.getColumnCount() == 1) {
                return true;
            } else if (this.getColumnCount() <= 4) {
                long noBlank = this.cells.values().stream().filter(cell ->
                        cell.column > 0 && StringUtils.isNotBlank(cell.getText())
                ).count();
                return noBlank == 0;
            }
        }
        return false;
    }

    private boolean hasLongText() {
        boolean longText = false;
        for (Cell cell : this.cells.values()) {
            // FIXME: magic number 100
            if (cell.getText().length() > 100) {
                longText = true;
                break;
            }
        }
        return longText;
    }

    public void detectTitle() {
        String title = null;
        for (int c = 0; c < this.columnCount; c++) {
            Cell cell = this.getCell(0, c);
            if (cell != null) {
                if (title == null) {
                    title = cell.getText();
                } else {
                    // more than one cell
                    return;
                }
            }
        }
        if (StringUtils.isNotBlank(title)) {
            this.title = title;
        }
    }

    public void removeTableId() {
        Cell cell = this.getCell(0, 0);
        if (cell == null) {
            return;
        }
        if (isTableId(cell.getText())) {
            this.delCell(0, 0);
            boolean empty = true;
            int colCount = this.getColumnCount();
            // 把第一行其它格子移过来
            for (int c = cell.getColSpan(); c < colCount; c++) {
                Cell cellx = this.getCell(0, c);
                if (cellx != null && StringUtils.isNotBlank(cellx.getText())) {
                    this.delCell(0, c);
                    Cell celly = cellx.copyTo(0, 0);
                    celly.colSpan = c;
                    this.addCell(0, 0, celly);
                    empty = false;
                    break;
                }
            }
            // 其它格子上移一行
            if (empty) {
                Map<Pair<Integer, Integer>, Cell> cc = new HashMap<>();
                for (Map.Entry<Pair<Integer, Integer>, Cell> entry : this.cells.entrySet()) {
                    Pair<Integer, Integer> loc = entry.getKey();
                    cc.put(Pair.of(loc.getLeft() - 1, loc.getRight()), entry.getValue());
                }
                this.cells = cc;
                this.rowCount -= 1;
                Map<Integer, CellRange> cr = new HashMap<>();
                for (Map.Entry<Integer, CellRange> entry : this.cellRanges.entrySet()) {
                    if (entry.getKey() > 0) {
                        cr.put(entry.getKey() - 1, entry.getValue());
                    }
                }
                this.cellRanges = cr;
            }
        }
    }



    private static boolean isTableId(String text) {
        boolean isId = false;
        for (Pattern pattern : TABLE_IDS_PATTERNS) {
            if (pattern.matcher(text).matches()) {
                isId = true;
                break;
            }
        }
        return isId;
    }

}


class CellRange {
    private ArrayList<Pair<Integer, Integer>> ranges;

    CellRange() {
        this.ranges = new ArrayList<>();
    }

    CellRange(int col1, int col2) {
        this();
        this.addRange(col1, col2);
    }

    @Override
    public String toString() {
        return "[CellRange " + ranges + "]";
    }

    public boolean contains(int col) {
        if (this.ranges.isEmpty()) {
            return false;
        }
        Pair<Boolean, Integer> k = this.findColumn(col);
        return k.getLeft();
    }

    public void addRange(int col1, int col2) {
        assert col1 <= col2;
        if (this.ranges.isEmpty()) {
            this.ranges.add(Pair.of(col1, col2));
            return;
        }
        Pair<Boolean, Integer> k1 = this.findColumn(col1);
        int index = k1.getRight();
        if (k1.getLeft()) {
            final Pair<Integer, Integer> range = this.ranges.get(k1.getRight());
            int left = range.getLeft();
            int right = col2;
            if (compareToRange(col2, range) == 0) {
                right = range.getRight();
            }
            this.ranges.set(index, Pair.of(left, right));
        } else {
            boolean added = false;
            index = k1.getRight() - 1;
            if (0 <= index && index < this.ranges.size()) {
                final Pair<Integer, Integer> range = this.ranges.get(index);
                if (range.getRight() + 1 == col1) {
                    this.ranges.set(index, Pair.of(range.getLeft(), col2));
                    added = true;
                }
            }
            if (!added) {
                index = k1.getRight();
                this.ranges.add(index, Pair.of(col1, col2));
            }
        }
        this.merge(index);
    }

    public void delRange(int col1, int col2) {
        assert col1 <= col2;
        Pair<Boolean, Integer> k1 = this.findColumn(col1);
        Pair<Boolean, Integer> k2 = this.findColumn(col2);
        assert k1.getLeft() && k2.getLeft() && Integer.compare(k1.getRight(), k2.getRight()) == 0;
        int index = k1.getRight();
        int left = this.ranges.get(index).getLeft();
        int right = this.ranges.get(index).getRight();
        Pair<Integer, Integer> r1 = Pair.of(left, col1 - 1);
        Pair<Integer, Integer> r2 = Pair.of(col2 + 1, right);
        if (r1.getLeft() <= r1.getRight()) {
            this.ranges.set(index, r1);
            if (r2.getLeft() <= r2.getRight()) {
                if (this.ranges.size() > index + 1) {
                    this.ranges.set(index + 1, r2);
                } else {
                    this.ranges.add(r2);
                }
            }
        } else {
            if (r2.getLeft() <= r2.getRight()) {
                this.ranges.set(index, r2);
            } else {
                this.ranges.remove(index);
            }
        }
    }

    private Pair<Boolean, Integer> findColumn(int col) {
        int i = 0;
        int j = this.ranges.size();
        boolean found = false;
        while (i < j) {
            int k = (i + j) / 2;
            int cmp = compareToRange(col, this.ranges.get(k));
            if (cmp < 0) {
                j = k;
            } else if (cmp > 0) {
                i = k + 1;
            } else {
                found = true;
                i = k;
                break;
            }
        }
        return Pair.of(found, i);
    }

    private void merge(int index) {
        final int left = this.ranges.get(index).getLeft();
        final int right = this.ranges.get(index).getRight();
        int end = index + 1;
        int size = this.ranges.size();
        while (end < size) {
            final Pair<Integer, Integer> range = this.ranges.get(end);
            int cmp = compareToRange(right, range);
            if (cmp > 0) {
                end += 1;
            } else if (cmp == 0 || right + 1 == range.getLeft()) {
                this.ranges.set(index, Pair.of(left, range.getRight()));
                end += 1;
                break;
            } else {
                break;
            }
        }
        this.ranges.subList(index + 1, end).clear();
    }

    private static int compareToRange(int col, Pair<Integer, Integer> range) {
        if (col < range.getLeft()) {
            return -1;
        } else if (col > range.getRight()) {
            return 1;
        } else {
            return 0;
        }
    }
}