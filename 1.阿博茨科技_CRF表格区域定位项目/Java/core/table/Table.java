package com.abcft.pdfextract.core.table;

import com.abcft.pdfextract.core.ContentGroupRenderer;
import com.abcft.pdfextract.core.ExtractContext;
import com.abcft.pdfextract.core.ExtractedItem;
import com.abcft.pdfextract.core.PdfExtractContext;
import com.abcft.pdfextract.core.gson.*;
import com.abcft.pdfextract.core.model.ContentGroup;
import com.abcft.pdfextract.core.model.ContentGroupDrawable;
import com.abcft.pdfextract.core.model.Rectangle;
import com.abcft.pdfextract.core.model.TextChunk;
import com.abcft.pdfextract.core.table.detectors.RulingTableRegionsDetectionAlgorithm;
import com.abcft.pdfextract.core.table.detectors.TableRegionCrfAlgorithm;
import com.abcft.pdfextract.core.table.detectors.TableRegionDetectionUtils;
import com.abcft.pdfextract.core.table.writers.HTMLWriter;
import com.abcft.pdfextract.core.util.FileId;
import com.abcft.pdfextract.spi.BaseTable;
import com.abcft.pdfextract.util.Confident;
import com.abcft.pdfextract.util.FloatUtils;
import com.abcft.pdfextract.util.JsonUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.TypeAdapter;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.math3.util.FastMath;

import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@SuppressWarnings("serial")
@DeprecatedFields(value = { "x", "y", "w", "h", "width", "height", "area", "raw_data" })
@DataFields(value = { "page_area", "raw_data", "rowCount", "columnCount" }, advanced = true)
@StatusFields({"state", "deleted"})
@MetaFields({"file_data", "from", "create_time", "last_updated", "table_version" })
public class Table extends Rectangle implements BaseTable, TableTags, ExtractedItem, ContentGroupDrawable, Confident {

    private static final boolean WRITE_RAW_DATA = false;

    public static final double HIGH_CONFIDENCE_THRESHOLD = 0.8;
    public static final double LOW_CONFIDENCE_THRESHOLD = 0.2;


    static final class CellPosition implements Comparable<CellPosition> {
        int row, col;

        CellPosition(int row, int col) {
            this.row = row;
            this.col = col;
        }

        @Override
        public String toString() {
            return "[" + row + ", " + col + "]";
        }

        @Override
        public boolean equals(Object other) {
            if (this == other)
                return true;
            if (!(other instanceof CellPosition))
                return false;
            return this.row == ((CellPosition) other).row && this.col == ((CellPosition) other).col;
        }

        @Override
        public int hashCode() {
            return this.row * 100000 + this.col;
        }

        @Override
        public int compareTo(CellPosition other) {
            int rv = 0;
            if (this.row < other.row) {
                rv = -1;
            } else if (this.row > other.row) {
                rv = 1;
            } else if (this.col > other.col) {
                rv = 1;
            } else if (this.col < other.col) {
                rv = -1;
            }
            return rv;
        }
    }

    public static final class CellContainer extends TreeMap<CellPosition, Cell> {

        public int maxRow = 0, maxCol = 0;

        public Cell get(int row, int col) {
            return this.get(new CellPosition(row, col));
        }

        public List<Cell> getRow(int row) {
            return new ArrayList<>(this.subMap(new CellPosition(row, 0), new CellPosition(row, maxCol + 1)).values());
        }

        @Override
        public Cell put(CellPosition cp, Cell value) {
            this.maxRow = Math.max(maxRow, cp.row);
            this.maxCol = Math.max(maxCol, cp.col);
            if (this.containsKey(cp)) { // adding on an existing CellPosition, concatenate content and resize
                value.merge(this.get(cp));
            }
            super.put(cp, value);
            return value;
        }

        @Override
        public Cell get(Object key) {
            return this.containsKey(key) ? super.get(key) : new Cell();
        }

        public boolean containsKey(int row, int col) {
            return this.containsKey(new CellPosition(row, col));
        }

        public void addMergedCell(Cell cell) {
            int row = cell.getRow();
            int col = cell.getCol();
            int rowSpan = cell.getRowSpan();
            int colSpan = cell.getColSpan();
            for (int i = 0; i < rowSpan; ++i) {
                for (int j = 0; j < colSpan; ++j) {
                    this.put(new CellPosition(row + i, col + j), cell);
                }
            }
        }

    }

    private static final class CellContainerTypeAdapter extends TypeAdapter<CellContainer> {

        private static void addProperty(JsonWriter out, String name, int value) throws IOException {
            out.name(name).value(Integer.valueOf(value));
        }

        private static void addProperty(JsonWriter out, String name, String value) throws IOException {
            out.name(name).value(value);
        }

        /**
         * Writes one JSON value (an array, object, string, number, boolean or null)
         * for {@code value}.
         *
         * @param out the writer.
         * @param cells the Java object to write. May be null.
         */
        @Override
        public void write(JsonWriter out, CellContainer cells) throws IOException {
            if (!cells.isEmpty()) {
                List<String> rawData = new ArrayList<>();
                out.beginArray();
                for (int i = 0; i <= cells.maxRow; ++i) {
                    for (int j = 0; j <= cells.maxCol; ++j) {
                        Cell cell = cells.get(i, j);
                        if (cell.isDeleted()) {
                            continue;
                        }
                        String cellText = cell.getTextAt(i, j);
                        if (cell.isPivot(i, j)) {
                            out.beginObject();
                            GsonUtil.writeRectangle2DData(out, cell);
                            // 不要用 out.name(...).value(int)，Java 会强行转换为 long 类型
                            addProperty(out, "row", i);
                            addProperty(out, "column", j);
                            addProperty(out, "text", cellText);

                            if (cell.getColSpan() > 1) {
                                addProperty(out, "colSpan", cell.getColSpan());
                            }
                            if (cell.getRowSpan() > 1) {
                                addProperty(out, "rowSpan", cell.getRowSpan());
                            }

                            if (cell.getCrossPageRow() != null) {
                                if (cell.getCrossPageRow() == Cell.CrossPageRowType.FirstRow) {
                                    addProperty(out, "cross_page_row", "firstRow");
                                } else if (cell.getCrossPageRow() == Cell.CrossPageRowType.LastRow) {
                                    addProperty(out, "cross_page_row", "lastRow");
                                }
                            }
                            out.endObject();
                        }
                        rawData.add(cellText);
                    }
                }
                out.endArray();
                if (WRITE_RAW_DATA) {
                    out.name("raw_data");
                    out.beginArray();
                    for (String text : rawData) {
                        out.value(text);
                    }
                    out.endArray();
                }
                addProperty(out, "rowCount", cells.maxRow + 1);
                addProperty(out, "columnCount", cells.maxCol + 1);
            } else {
                out.nullValue();
                if (WRITE_RAW_DATA) {
                    out.name("raw_data").nullValue();
                }
                addProperty(out, "rowCount", 0);
                addProperty(out, "columnCount", 0);
            }
        }

        /**
         * Reads one JSON value (an array, object, string, number, boolean or null)
         * and converts it to a Java object. Returns the converted object.
         *
         * @param in
         * @return the converted Java object. May be null.
         */
        @Override
        public CellContainer read(JsonReader in) throws IOException {
            throw new UnsupportedOperationException();
        }
    }

    public static final Table EMPTY = new Table();

    @Detail
    @MetaField
    @SerializedName("_id")
    public String tableId;      // Temporary available while saving to DB

    @Detail
    @MetaField
    @SerializedName("fileId")
    public FileId fileId;       // Temporary available while saving to DB

    @Detail
    @MetaField
    @SerializedName("file_source")
    public String fileSource;   // Temporary available while saving to DB

    @Detail
    @DataField
    @SerializedName("pngFile")
    public String pngFile;

    @DataField(inline = false)
    @SerializedName("html_file")
    public String htmlFile;
    @DataField(inline = false)
    @SerializedName("data_file")
    public String dataFile;

    private Point2D.Double offset;
    private ContentGroup contentGroup;

    @Summary
    @DataField(advanced = true)
    @SerializedName("data")
    @JsonAdapter(CellContainerTypeAdapter.class)
    private CellContainer cellContainer = new CellContainer();
    private List<List<CandidateCell>> structureCells = new ArrayList<>();

    Page page;

    float classifyScore = 1.0f;

    private Rectangle2D pageArea;

    @Summary
    @MetaField
    @SerializedName("pageIndex")
    @JsonAdapter(GsonUtil.PageNumberTypeAdapter.class)
    private int pageNumber;


    /***************************************************************
     表格类别 标识表格分类类别
     -1  disable classify
     0   Financial
     1   Report
     2   RateAdvise
     3   AnalystInfo
     4   Fragment
     ***************************************************************/
    @Detail
    @DataField
    @SerializedName("table_type")
    private TableType tableType;    // FIXME 这个字段是否和上面的重复？

    private boolean hasText;

    @Summary
    @DataField
    @SerializedName("algorithm")
    private String algorithmName;
    @Detail
    @DataField
    @SerializedName("algorithm_version")
    private String algorithmVersion;

    @Summary
    @DataField
    private double confidence = 1.0;

    @Summary
    @DataField
    private String title;
    @Summary
    @DataField
    private String caps = "";
    @Summary
    @DataField(advanced = true)
    private String shoes = "";
    @Summary
    @DataField(advanced = true)
    @SerializedName("inline_title")
    private boolean inlineTitle;

    @Summary
    @DataField
    private String tag = null;

    private boolean isMergedTable = false;
    private boolean isStructureTable;
    private boolean isCrfTable = false;
    private boolean isNormalTable = true;

    @Summary
    @DataField(advanced = true)
    @SerializedName("unit_line")
    private String unitTextLine = "";
    private Set<String> unitSet = null;

    /*
     *   设置跨页表格合并属性
     */
    public boolean needCombine = false;

    private Table prevTable = null;

    private Table nextTable = null;

    private Rectangle2D originalTableBounds = null;

    @Summary
    @MetaField
    @SerializedName("endPageIndex")
    @JsonAdapter(GsonUtil.PageNumberTypeAdapter.class)
    private int endPageNumber;

    private int index;

    @Override
    public int getPageIndex() {
        return this.pageNumber -1;
    }

    @Override
    public String getId() {
        return this.tableId;
    }

    @Override
    public String toHtml() {
        StringBuilder builder = new StringBuilder();
        HTMLWriter htmlWriter = new HTMLWriter();
        htmlWriter.writeTable(builder, this);
        builder.append("\n");
        return builder.toString();
    }

    public Table() {
        this.pageArea = this;
    }

    Table(Table other) {
        this(other.page);
        this.pageArea = this;
        this.tableType = other.tableType;
        this.algorithmName = other.algorithmName;
        this.algorithmVersion = other.algorithmVersion;
        this.title = other.title;
        this.caps = other.caps;
        this.shoes = other.shoes;
        this.contentGroup = other.contentGroup;
        this.offset = other.offset;
        this.endPageNumber = other.endPageNumber;
    }

    public Table(Page page) {
        this();
        this.pageArea = this;
        this.page = page;
        this.pageNumber = page.getPageNumber();
        this.endPageNumber = page.getPageNumber();
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder(super.toString());
        builder.delete(builder.length() - 1, builder.length());
        builder.append(", title=\"").append(title).append("\"");
        builder.append(", page=").append(pageNumber);
        builder.append(", c=").append(confidence);
        builder.append(']');
        return builder.toString();
    }

    public void setPage(Page page) {
        this.page = page;
        this.pageNumber = page.getPageNumber();
        this.endPageNumber = page.getPageNumber();
    }


    /*********************************************
     * 基础属性的 get/set 方法
     *********************************************/

    public boolean isInlineTitle() {
        return inlineTitle;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title, boolean inlineTitle) {
        this.title = title;
        this.inlineTitle = inlineTitle;
    }

    public String getTag() {
        return tag;
    }

    public void setTag(String tag) {
        this.tag = tag;
    }

    public String getAlgorithmName() {
        return this.algorithmName;
    }

    public String getAlgorithmVersion() {
        return this.algorithmVersion;
    }

    public void setAlgorithmProperty(String name, String version) {
        this.algorithmName = name + ":v" + version;
        this.algorithmVersion = version;
        this.isStructureTable = StringUtils.containsIgnoreCase(name, "Structure");
        this.isCrfTable = StringUtils.containsIgnoreCase(name, "CRF");
    }

    @Override
    public double getConfidence() {
        return confidence;
    }

    @Override
    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }

    public void updateConfidence(double lowThresh, double highThresh) {
        double minConfidence = (lowThresh < 0.0 ? 0.0 : lowThresh) > 1.0 ? 1.0 : lowThresh;
        double maxConfidence = (highThresh < 0.0 ? 0.0 : highThresh) > 1.0 ? 1.0 : highThresh;
        if (minConfidence > maxConfidence) {
            double temp = minConfidence;
            minConfidence = maxConfidence;
            maxConfidence = temp;
        }

        this.confidence = (maxConfidence - minConfidence) * this.confidence + minConfidence;
    }

    public void setClassifyScore(float classifyScore) {
        this.classifyScore = classifyScore;
    }

    public float getClassifyScore() {
        return classifyScore;
    }

    public boolean hasCaps() {
        return StringUtils.isNotBlank(caps);
    }

    public String getCaps() {
        return caps;
    }

    public void setCaps(String caps) {
        this.caps = caps;
    }

    public boolean hasShoes() {
        return StringUtils.isNotBlank(shoes);
    }

    public String getShoes() {
        return shoes;
    }

    public void setShoes(String shoes) {
        this.shoes = shoes;
    }

    public boolean hasUnit() {
        return StringUtils.isNotBlank(unitTextLine);
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

    public List<Cell> getRow(int row) {
        return this.cellContainer.getRow(row);
    }

    public int getRowCount() {
        return this.cellContainer.maxRow + 1;
    }

    public int getColumnCount() {
        return this.cellContainer.maxCol + 1;
    }

    public int getCellCount() {
        return this.cellContainer.size();
    }

    public Cell getCell(int row, int col) {
        return this.cellContainer.get(row, col);
    }

    public void setCellContainer(CellContainer cellContainer) {
        this.cellContainer = cellContainer;
    }

    public boolean hasText() {
        return hasText;
    }

    public boolean hasCell(int row, int col) {
        return this.cellContainer.containsKey(row, col);
    }

    public boolean hasSingleCell() {
        if (this.cellContainer.maxCol * this.cellContainer.maxRow == 1) {
            return true;
        }

        int validCellCount = 0;
        for (int i = 0; i < this.getRowCount(); i++) {
            for (int j = 0; j < this.getColumnCount(); j++) {
                if (StringUtils.isNotBlank(this.getCell(i, j).getText().trim())) {
                    validCellCount++;
                }
                if (validCellCount >= 2) {
                    return false;
                }
            }
        }
        return true;
    }

    public boolean hasSingleColumn() {
        return cellContainer.maxCol + 1 == 1;
    }

    public boolean hasSingleRow() {
        return cellContainer.maxRow + 1 == 1;
    }

    public boolean isMergedTable() {
        return isMergedTable;
    }

    public boolean isStructureTable() {
        return isStructureTable;
    }
    public boolean isCrfTable() {
        return isCrfTable;
    }
    public boolean getNormalStatus() {
        return isNormalTable;
    }

    public void setNormalStatus(boolean isNormalTable) {
        this.isNormalTable = isNormalTable;
    }

    public boolean isSparse() {
        return cellContainer.size() * 2 < (cellContainer.maxCol + 1) * (cellContainer.maxRow + 1);
    }

    public boolean hasHole() {
        return cellContainer.size() < (cellContainer.maxCol + 1) * (cellContainer.maxRow + 1);
    }

    public boolean isSparseTable() {
        if (this.getTableType() == TableType.LineTable) {
            // 多行一列
            int blankCell = 0;
            if (this.hasSingleColumn() && !this.hasSingleRow()) {
                for (int i = 0; i < this.getCellCount(); i++) {
                    if (this.getCell(i,0).getElements().isEmpty()) {
                        blankCell++;
                    }
                }
            }
            return (blankCell >= 2);
        } else {
            List<Cell> cells = this.getCells();
            if (cells.size() > 25) {
                long validCellNum = cells.stream().filter(cell -> StringUtils.isBlank(cell.getText())).count();
                return (float)validCellNum / (float)cells.size() > 0.7;
            }
            return false;
        }
    }

    private float calTableAlignRatio() {
        if (page == null) {
            return 0;
        }

        ContentGroupPage tablePage;
        if (!(page instanceof ContentGroupPage)) {
            tablePage = ContentGroupPage.fromPage(page);
        } else {
            tablePage = (ContentGroupPage) page;
        }

        List<Rectangle> textChunkBounds = new ArrayList<>();
        for (TextChunk textChunk : tablePage.getMergeChunks(this)) {
            if (StringUtils.isBlank(textChunk.getText())) {
                continue;
            }
            textChunkBounds.add(new Rectangle(textChunk.getVisibleBBox()));
        }
        int rowAlignNum = 0;
        int rowChunkNum = 0;
        int colAlignNum = 0;
        int colChunkNum = 0;
        for (Rectangle one : textChunkBounds) {
            for (Rectangle other : textChunkBounds) {
                if (one.equals(other)) {
                    continue;
                }

                float verticalOverlapRatio = one.verticalOverlapRatio(other);
                if (verticalOverlapRatio > 0) {
                    rowChunkNum++;
                    if (verticalOverlapRatio > 0.8) {
                        rowAlignNum++;
                    }
                }

                float horizontalOverlapRatio = one.horizontallyOverlapRatio(other);
                if (horizontalOverlapRatio > 0) {
                    colChunkNum++;
                    if (horizontalOverlapRatio > 0.8) {
                        colAlignNum++;
                    }
                }
            }
        }

        float rowAlignRatio = rowAlignNum / (float) rowChunkNum;
        float colAlignRatio = colAlignNum / (float) colChunkNum;
        //F1 score
        return 2 * (rowAlignRatio * colAlignRatio) / (rowAlignRatio + colAlignRatio);
    }

    private float calTableDenseRatio() {
        long validCellNum = this.getCells().stream().filter(cell -> StringUtils.isNotBlank(cell.getText())).count();
        return validCellNum / ((float)((cellContainer.maxCol + 1) * (cellContainer.maxRow + 1)));
    }

    private float calTableProjectRatio() {
        List<Rectangle> cellList = new ArrayList<>();
        for (Cell cell : this.getCells()) {
            if (cell.getRowSpan() <= 1 && cell.getColSpan() <= 1 && StringUtils.isNotBlank(cell.getText())
                    && cell.getWidth() > 1.0 && cell.getHeight() > 1.0) {
                List<TextChunk> chunks = cell.getElements();
                if (chunks == null || chunks.isEmpty()) {
                    continue;
                }
                cellList.add(Rectangle.boundingVisibleBoxOf(chunks));
            }
        }

        float rowProjectRatio = (TableRegionDetectionUtils.calHorizontalProjectionGap(cellList).size() + 1) / (float)(cellContainer.maxRow + 1);
        float colProjectRatio = (TableRegionDetectionUtils.calVerticalProjectionGap(cellList).size() + 1) / (float)(cellContainer.maxCol + 1);
        //F1 score
        return 2 * (rowProjectRatio * colProjectRatio) / (rowProjectRatio + colProjectRatio);
    }

    public boolean isMostPossibleTable() {
        if (page == null || this.getCellCount() < 5) {
            return false;
        }

        if (Math.max(this.getRowCount(), this.getColumnCount()) >= 4 && Math.min(this.getRowCount(), this.getColumnCount()) >= 2) {
            float alignRatio = this.calTableAlignRatio();
            float denseRatio = this.calTableDenseRatio();
            float projectRatio = this.calTableProjectRatio();
            if ((this.isStructureTable() && alignRatio >= 0.7 && denseRatio >= 0.7 && projectRatio >= 0.7)
                    || (alignRatio >= 0.9 && denseRatio >= 0.85 && projectRatio >= 0.85)) {
                return true;
            } else {
                return false;
            }
        } else {
            return false;
        }
    }

    public boolean isOneRowMultiValidTable(Page page) {
        boolean result = false;
        if (this.hasSingleRow() && !this.hasSingleColumn()) {
            if (this.getCellCount() >= 3) {
                return false;
            }
            // 排除续表的可能
            Rectangle upRect = new Rectangle(page.getLeft(), page.getTop(),
                    page.getWidth(),this.getTop() - page.getTop());
            List<TextChunk> texts = page.getMutableTextChunks(upRect.rectReduce(1.0, 1.0 , page.getWidth(), page.getHeight()));
            boolean isNotContinueTable = false;
            TextChunk topText = null;
            if (texts != null && !texts.isEmpty()) {
                texts.sort(Comparator.comparing(TextChunk::getTop));
                if (!texts.get(texts.size() - 1).isEmpty() && (this.getTop() - page.getTop() > 80 && texts.size() > 1)) {
                    topText = texts.get(texts.size() - 1);
                    isNotContinueTable = true;
                }
            }
            if (isNotContinueTable) {
                Rectangle downRect = new Rectangle(page.getLeft(), this.getBottom(),
                        page.getWidth(),page.getBottom() - this.getBottom());
                texts = page.getMutableTextChunks(downRect.rectReduce(1.0, 1.0 , page.getWidth(), page.getHeight()));
                if (texts != null && !texts.isEmpty()) {
                    texts.sort(Comparator.comparing(TextChunk::getTop));
                    if (!texts.get(0).isEmpty() && (page.getBottom() - this.getBottom() > 80 && texts.size() > 1)) {
                        TextChunk bottomText = texts.get(0);
                        if (topText != null) {
                            Rectangle expandTableArea = new Rectangle(this.getLeft(), topText.getBottom(),
                                    this.getWidth(), bottomText.getTop() - topText.getBottom());
                            List<Ruling> horizontalRulingsOfRegion = new ArrayList<>(Ruling.getRulingsFromArea(page.getHorizontalRulings(), expandTableArea));
                            horizontalRulingsOfRegion.sort(Comparator.comparing(Ruling::getTop));
                            if (!(horizontalRulingsOfRegion.size() == 2 &&
                                    (horizontalRulingsOfRegion.get(1).getY1() - horizontalRulingsOfRegion.get(0).getY1() > page.getMinCharHeight()))) {
                                result = true;
                            }
                        } else {
                            result = true;
                        }
                    }
                }
            }
        }
        return result;
    }

    public boolean isTableOverlap(Table table) {
        return this.overlapRatio(table, true) >= 0.8;
    }

    public Rectangle getAllCellBounds() {
        Rectangle bounds = null;
        for (Cell cell : cellContainer.values()) {
            if (cell.isEmpty()) {
                continue;
            }
            if (bounds == null) {
                bounds = new Rectangle(cell);
            } else {
                Rectangle.union(bounds, cell, bounds);
            }
        }
        return bounds;
    }

    public ContentGroup getContentGroup() {
        return contentGroup;
    }

    public void setContentGroup(ContentGroup contentGroup) {
        this.contentGroup = contentGroup;
    }

    @Override
    public Rectangle2D getDrawArea() {
        return this;
    }

    public int getPageNumber() {
        return pageNumber;
    }

    public void setPageNumber(int pageNumber) {
        this.pageNumber = pageNumber;
        this.endPageNumber = pageNumber;
    }

    public TableType getTableType() {
        return this.tableType;
    }

    public void setTableType(TableType tableType) {
        this.tableType = tableType;
    }

    public int getIndex() {
        return index;
    }

    public String getName() {
        // TODO: 使用更加合理的数据来表示table的id, 比如在页面内的位置, 在PDF流里面的位置等等,
        // TODO: 这样不会因为多识别或者少识别导致index被打乱
        return String.valueOf(index);
    }

    public void setIndex(int index) {
        this.index = index;
    }

    public List<Cell> getCells() {
        return new ArrayList<>(this.cellContainer.values());
    }


    public Float getTableAreaInPageSpace() {
        float pageHeight = page.getPDPage().getCropBox().getHeight();
        double x = getX();
        double y = getY();
        if (offset != null) {
            x += offset.x;
            y += offset.y;
        }
        return new Rectangle2D.Float((float) x, (float) (pageHeight - y), (float) getWidth(), (float) getHeight());
    }

    public Float getTableAreaInPageSpaceLB() {
        float pageHeight = page.getPDPage().getCropBox().getHeight();
        double x = getX();
        double y = getY();
        if (offset != null) {
            x += offset.x;
            y += offset.y;
        }
        float height = (float) getHeight();

        return new Rectangle2D.Float((float) x, (float) (pageHeight - y - height), (float) getWidth(), height);
    }

    public boolean getTableMergeFlag() {
        return this.isMergedTable;
    }

    public void setMergedTable(boolean mergedTable) {
        this.isMergedTable = mergedTable;
    }

    public void setNeedCombine() {
        this.needCombine = true;
    }

    public void setNextTable(Table nextTable) {
        this.nextTable = nextTable;
    }

    public void setPrevTable(Table prevTable) {
        this.prevTable = prevTable;
    }

    public void setEndPageNumber(int endPageNumber) {
        this.endPageNumber = endPageNumber;
    }

    public void setOriginalTableBounds(Rectangle2D tableBounds) {
        this.originalTableBounds = tableBounds;
    }

    public Table getNextTable() {
        return this.nextTable;
    }

    public Table getTailTable() {
        Table tail = this;
        while (tail.nextTable != null) {
            tail = tail.nextTable;
        }
        return tail;
    }

    public Table getPrevTable() {
        return this.prevTable;
    }

    public Table getHeadTable() {
        Table head = this;
        while (head.prevTable != null) {
            head = head.prevTable;
        }
        return head;
    }

    public int getEndPageNumber() {
        return this.endPageNumber;
    }

    public Rectangle2D getOriginalTableBounds() {
        if (this.originalTableBounds == null) {
            return this.getBounds2D();
        }

        return this.originalTableBounds;
    }

    public Page getPage() {
        return page;
    }

    public void setStructureCells(List<List<CandidateCell>> structureCells) {
        this.structureCells = structureCells;
    }

    public List<List<CandidateCell>> getStructureCells() {
        return this.structureCells;
    }

    public Rectangle getStructureBound() {
        List<CandidateCell> cellList = new ArrayList<>();
        for (List<CandidateCell> cell : this.structureCells) {
            cellList.addAll(cell);
        }
        return Rectangle.union(cellList.stream().map(CandidateCell::getBounds2D).collect(Collectors.toList()));
    }

    public void addStructureCells(List<List<CandidateCell>> cells) {
        this.structureCells.addAll(cells);
    }


    public void fromDocument(JsonObject document, ExtractContext context) {
        pageNumber = JsonUtil.getInt(document, "pageIndex", 0) + 1;
        Rectangle2D pageArea = JsonUtil.getRectangle(document, "page_area");
        if (pageArea != null) {
            setRect(pageArea);
        } else {
            Rectangle2D area = JsonUtil.getRectangle(document, null);
            area.setRect(area.getX(), area.getY() - area.getHeight(), area.getWidth(), area.getHeight());
            AffineTransform pageTransform = ContentGroupRenderer.buildPageTransform(
                    ((PdfExtractContext)context).getNativeDocument().getPage(pageNumber-1), 1);
            pageArea = pageTransform.createTransformedShape(area).getBounds2D();
            setRect(pageArea);
        }

        JsonArray data = document.getAsJsonArray("data");
        for (int i = 0; i < data.size(); i++) {
            JsonObject cellDoc = data.get(i).getAsJsonObject();
            int row = JsonUtil.getInt(cellDoc, "row", 0);
            int col = JsonUtil.getInt(cellDoc, "column", 0);
            int rowSpan = JsonUtil.getInt(cellDoc, "rowSpan", 1);
            int colSpan = JsonUtil.getInt(cellDoc, "colSpan", 1);
            Rectangle2D area = JsonUtil.getRectangle(cellDoc, null);
            Cell cell = new Cell((float) area.getX(), (float) area.getY(), (float) area.getWidth(), (float) area.getHeight());
            cell.setText(JsonUtil.getString(cellDoc, "text"));
            add(cell, row, col);
            if (rowSpan > 1 || colSpan > 1) {
                cell.setRowSpan(rowSpan);
                cell.setColSpan(colSpan);
                addMergedCell(cell, row, col, rowSpan, colSpan);
            }
        }
    }

    public JsonObject toDocument(boolean detail) {
        JsonObject bson = GsonUtil.toDocument(this, detail);
        //bson.addProperty("pageIndex", pageNumber - 1);
        bson.add("page_area", JsonUtil.toDocumentAbbr(this));

        /*
        Float pageAreaLB;
        Float pageArea;
        if (page != null) {
            pageAreaLB = getTableAreaInPageSpaceLB();
            pageArea = getTableAreaInPageSpace();
        } else {
            pageArea = pageAreaLB = new Float();
        }
        bson.add("area", JsonUtil.toDocumentAbbr(pageAreaLB));
        JsonUtil.putRectangleAbbr(bson, pageArea);
        */

        /*
        if (prevTable != null) {
            bson.addProperty("prev_table", prevTable.tableId);
        }
        if (nextTable != null) {
            bson.addProperty("next_table", nextTable.tableId);
        }
        */

        /*
        CellContainer cells = cellContainer;
        JsonArray data = new JsonArray();
        JsonArray rawData = new JsonArray();
        if (!cells.isEmpty()) {
            bson.addProperty("rowCount", cells.maxRow + 1);
            bson.addProperty("columnCount", cells.maxCol + 1);
            for (int i = 0; i <= cells.maxRow; ++i) {
                for (int j = 0; j <= cells.maxCol; ++j) {
                    Cell cell = cells.get(i, j);
                    if (cell.isDeleted()) {
                        continue;
                    }
                    String cellText = cell.getTextAt(i, j);
                    if (cell.isPivot(i, j)) {
                        JsonObject cellDoc = new JsonObject();
                        JsonUtil.putRectangleAbbr(cellDoc, cell);
                        cellDoc.addProperty("row", i);
                        cellDoc.addProperty("column", j);
                        cellDoc.addProperty("text", cell.getText());

                        if (cell.getColSpan() > 1) {
                            cellDoc.addProperty("colSpan", cell.getColSpan());
                        }
                        if (cell.getRowSpan() > 1) {
                            cellDoc.addProperty("rowSpan", cell.getRowSpan());
                        }
                        if (cell.getCrossPageRow() != null) {
                            cellDoc.addProperty("cross_page_row", cell.getCrossPageRow());
                        }

                        data.add(cellDoc);
                    }
                    rawData.add(cellText);
                }
            }
        } else {
            bson.addProperty("rowCount", 0);
            bson.addProperty("columnCount", 0);
        }
        bson.add("data", data);
        bson.add("raw_data", rawData);
        */
        return bson;
    }


    /******************************************************
     * 下面为用于修改、构造表格的方法
     *****************************************************/

    public boolean canSaveImage() {
        return page != null;
    }

    public void add(Cell cell, int i, int j) {
        if (!cell.isEmpty()) {
            this.merge(cell);
        }
        cell.assignPosition(i, j);
        this.cellContainer.put(new CellPosition(i, j), cell);
        this.hasText |= cell.getText().trim().length() > 0;
        cell.setClean();
    }

    // startRow为从otherTable从哪一行开始add进thisTable中
    // 如果已经判断出不用单元格合并则不对第一列空单元格对下合并
    public void addTable(Table otherTable, int startRow) {
        this.setOriginalTableBounds(this.getBounds2D());
        int r = getRowCount();
        float bottom = getBottom();
        float increaseHeight = 0f;
        int idx = 0;
        int prevRowCellIdx = 0;
        int mergeCol = 0;
        boolean upMerge = true;
        for (int i = startRow; i < otherTable.getRowCount(); i++) {
            for (int j = 0; j < otherTable.getColumnCount(); j++) {
                Cell cell = new Cell(otherTable.getCell(i, j));
                if (!cell.isPivot(i, j)) {
                    // 单元格有合并的情况
                    if (cell.getText().equals("") && i == startRow && upMerge) {
                        this.getCell(r + idx - 1, j).setRowSpan(this.getCell(r + idx - 1, j).getRowSpan() + cell.getRowSpan() - 1);
                        addMergedCell(this.getCell(r + idx - 1, j));
                        mergeCol += this.getCell(r + idx - 1, j).getColSpan();
                        this.getCell(r + idx - 1, j).setCrossPageCell();
                    }
                    upMerge = false;
                    continue;
                }

                // 单元格没有合并的情况
                if (upMerge && cell.getText().equals("") && i == startRow) {
                    Cell prevRowCell = this.getCell(r + idx - 1, prevRowCellIdx);
                    // 前后两行单元格要对应起来
                    if (prevRowCell.getColSpan() > 1) {
                        prevRowCellIdx = prevRowCellIdx + this.getCell(r + idx - 1, j).getColSpan();
                        mergeCol += prevRowCell.getColSpan();
                    } else {
                        prevRowCellIdx++;
                    }
                    prevRowCell.setRowSpan(prevRowCell.getRowSpan() + cell.getRowSpan());
                    addMergedCell(prevRowCell);
                    prevRowCell.setCrossPageCell();
                    continue;
                }

                upMerge = false;
                cell.setRow(r + idx);
                cell.setCol(j);
                double cellHeight = cell.getHeight();
                increaseHeight = cellHeight > increaseHeight ? increaseHeight : (float) cellHeight;
                cell.setTop(bottom);
                cell.setBottom((float) (cell.getTop() + cellHeight));
                if (i == startRow && mergeCol != 0) {
                    add(cell, r + idx, j + mergeCol);
                } else {
                    add(cell, r + idx, j);
                }
            }
            bottom += increaseHeight;
            idx++;
        }
        this.getRow(r-1).stream().forEach(cell -> cell.setCrossPageRow(Cell.CrossPageRowType.LastRow));
        this.getRow(r).stream().forEach(cell -> cell.setCrossPageRow(Cell.CrossPageRowType.FirstRow));
    }


    public void fillMergedCell(Cell cell, int row, int col) {
        cellContainer.put(new CellPosition(row, col), cell);
    }

    public void addRightMergedCell(Cell cell, int row, int col) {
        cell.increaseColSpan();
        fillMergedCell(cell, row, col);
        cell.setClean();
    }

    public void addDownMergedCell(Cell cell, int row, int col) {
        cell.increaseRowSpan();
        fillMergedCell(cell, row, col);
        cell.setClean();
    }

    public void updateCells() {
        List<Cell> cells = cellContainer.values().stream().map(Cell::new).collect(Collectors.toList());
        cellContainer.clear();
        for (Cell cell : cells) {
            cellContainer.addMergedCell(cell);
            cell.setClean();
        }
    }

    private void addMergedCell(Cell cell) {
        cellContainer.addMergedCell(cell);
        cell.setClean();
    }

    public void addMergedCell(Cell cell, int row, int col, int rowSpan, int colSpan) {
        /*
         * cell为含有合并信息的单元格，所以不需要对cell修改rowSpan和colSpan
         * 如果cell为初始化的新单元格，需要更新合并信息，则在外面调用setColSpan和setRowSpan设置
         */
        this.merge(cell);
        if (colSpan > 1) {
            for (int i = 1; i < colSpan; i++) {
                this.fillMergedCell(cell, row, col+i);
            }
        }
        if (rowSpan > 1) {
            for (int i = 1; i < rowSpan; i++) {
                for (int j = 0; j < colSpan; j++) {
                    this.fillMergedCell(cell, row+i, col+j);
                }
            }
        }
    }

    private int mergeRectangle(Rectangle rect, List<Rectangle> rulingTableRegions) {
        int mergeCount = 0;
        if (!rulingTableRegions.isEmpty()) {
            for (Rectangle rc1 : rulingTableRegions) {
                if (rect.isShapeIntersects(rc1) || rect.isShapeAdjacent(rc1, 1.5)) {
                    Rectangle2D.union(rect, rc1, rect);
                    rc1.markDeleted();
                    mergeCount++;
                }
            }
            rulingTableRegions.removeIf(Rectangle::isDeleted);
        }
        return mergeCount;
    }

    private Rectangle mergeNeighborRuling(Page page, Rectangle tableBound) {
        //寻找包围该table文本区域的ruling
        List<Ruling> neighborRulings = new ArrayList<>();

        List<Ruling> hRulings = new ArrayList<>(page.getHorizontalRulings());
        hRulings.sort(Comparator.comparing(Ruling::getTop));
        for (Ruling ruling : hRulings) {
            if ((ruling.getTop() >= tableBound.getBottom() || FloatUtils.feq(ruling.getTop(), tableBound.getBottom(), 0.25 * page.getAvgCharHeight()))
                    && tableBound.isHorizontallyOverlap(ruling.toRectangle())) {
                neighborRulings.add(ruling);
                break;
            }
        }
        for (int i = hRulings.size() - 1; i >= 0; i--) {
            Ruling ruling = hRulings.get(i);
            if ((ruling.getTop() <= tableBound.getTop() || FloatUtils.feq(ruling.getTop(), tableBound.getTop(), 0.25 * page.getAvgCharHeight()))
                    && tableBound.isHorizontallyOverlap(ruling.toRectangle())) {
                neighborRulings.add(hRulings.get(i));
                break;
            }
        }

        List<Ruling> vRulings = new ArrayList<>(page.getVerticalRulings());
        vRulings.sort(Comparator.comparing(Ruling::getLeft));
        for (Ruling ruling : vRulings) {
            if ((ruling.getLeft() >= tableBound.getRight() || FloatUtils.feq(ruling.getLeft(), tableBound.getRight(), 0.25 * page.getAvgCharWidth()))
                    && tableBound.isVerticallyOverlap(ruling.toRectangle())) {
                neighborRulings.add(ruling);
                break;
            }
        }
        for (int i = vRulings.size() - 1; i >= 0; i--) {
            Ruling ruling = vRulings.get(i);
            if ((ruling.getLeft() <= tableBound.getLeft() || FloatUtils.feq(ruling.getLeft(), tableBound.getLeft(), 0.25 * page.getAvgCharWidth()))
                    && tableBound.isVerticallyOverlap(ruling.toRectangle())) {
                neighborRulings.add(vRulings.get(i));
                break;
            }
        }

        //neighbor valid ruling
        for (Ruling neighborRul : neighborRulings) {
            Rectangle neighborRect = new Rectangle(neighborRul.getLeft(), neighborRul.getTop(), neighborRul.getWidth(), neighborRul.getHeight());
            boolean hasIntersect = false;
            for (Ruling rul : page.getRulings()) {
                Rectangle rulRect = new Rectangle(rul.getLeft(), rul.getTop(), rul.getWidth(), rul.getHeight());
                boolean isSameRuling = FloatUtils.feq(neighborRul.getLeft(), rul.getLeft(), 0.5)
                        && FloatUtils.feq(neighborRul.getRight(), rul.getRight(), 0.5)
                        && FloatUtils.feq(neighborRul.getTop(), rul.getTop(), 0.5)
                        && FloatUtils.feq(neighborRul.getBottom(), rul.getBottom(), 0.5);
                if (!isSameRuling && (neighborRect.isShapeIntersects(rulRect) || neighborRect.isShapeAdjacent(rulRect, 1.5))) {
                    hasIntersect = true;
                    break;
                }
            }
            if (!hasIntersect) {
                neighborRul.markDeleted();
            }
        }
        neighborRulings.removeIf(Ruling::isDeleted);

        if (neighborRulings.size() <= 1) {
            return tableBound;
        } else {
            Rectangle rectTemp = new Rectangle(tableBound);
            List<Rectangle> rulingRectList = neighborRulings.stream().map(rul -> new Rectangle(rul.getLeft(), rul.getTop(), rul.getWidth(), rul.getHeight())).collect(Collectors.toList());
            for (Rectangle rect : rulingRectList) {
                Rectangle2D.union(rectTemp, rect, rectTemp);
            }

            Rectangle neighborBound = page.getTextBoundingBoxWithinRegion(rectTemp);
            if (FloatUtils.feq(tableBound.getMinX(), neighborBound.getMinX(), 1.0) && FloatUtils.feq(tableBound.getMaxX(), neighborBound.getMaxX(), 1.0)
                    && FloatUtils.feq(tableBound.getMinY(), neighborBound.getMinY(), 1.0) && FloatUtils.feq(tableBound.getMaxY(), neighborBound.getMaxY(), 1.0)) {
                return rectTemp;
            }
        }
        return tableBound;
    }

    public Rectangle calculateTableBound(Page page) {
        float minX = java.lang.Float.MAX_VALUE;
        float minY = java.lang.Float.MAX_VALUE;
        float maxX = 0;
        float maxY = 0;
        for (List<CandidateCell> candidateCellList : this.getStructureCells()) {
            for (CandidateCell cell : candidateCellList) {
                Rectangle cellRect = cell.getValidTextBounds();
                if (cellRect == null) {
                    continue;
                }
                minX = FastMath.min(minX, (float) cellRect.getMinX());
                minY = FastMath.min(minY, (float) cellRect.getMinY());
                maxX = FastMath.max(maxX, (float) cellRect.getMaxX());
                maxY = FastMath.max(maxY, (float) cellRect.getMaxY());
            }
        }
        Rectangle tableBound = new Rectangle(minX, minY, maxX - minX, maxY - minY);

        tableBound = mergeNeighborRuling(page, tableBound);
        Rectangle rectTemp = new Rectangle(tableBound);
        List<Rectangle> rulingRectList = page.getRulings().stream().map(rul -> new Rectangle(rul.getLeft(), rul.getTop(), rul.getWidth(), rul.getHeight())).collect(Collectors.toList());
        int mergeCount;
        do {
            mergeCount = mergeRectangle(rectTemp, rulingRectList);
        } while (mergeCount != 0);


        Rectangle one = page.getTextBoundingBoxWithinRegion(tableBound);
        Rectangle other = page.getTextBoundingBoxWithinRegion(rectTemp);
        if (FloatUtils.feq(one.getMinX(), other.getMinX(), 1.0) && FloatUtils.feq(one.getMaxX(), other.getMaxX(), 1.0)
                && FloatUtils.feq(one.getMinY(), other.getMinY(), 1.0) && FloatUtils.feq(one.getMaxY(), other.getMaxY(), 1.0)) {
            return rectTemp.shrinkToPage((ContentGroupPage) page);
        } else {
            return tableBound.shrinkToPage((ContentGroupPage) page);
        }
    }

    private static void nearlyContainRemove(Page page, List<Table> tables) {
        if (tables == null || tables.size() < 2) {
            return;
        }

        for (int i = 0; i < tables.size() - 1; i++) {
            Table one = tables.get(i);
            if (one.isDeleted()) {
                continue;
            }
            Rectangle oneTextBound = Rectangle.boundingBoxOf(page.getTextChunks(one));
            for (int j = i + 1; j < tables.size(); j++) {
                Table other = tables.get(j);
                if (other.isDeleted()) {
                    continue;
                }
                Rectangle otherTextBound = Rectangle.boundingBoxOf(page.getTextChunks(other));
                if (otherTextBound.nearlyContains(oneTextBound)) {
                    one.markDeleted();
                    break;
                } else if (oneTextBound.nearlyContains(otherTextBound)) {
                    other.markDeleted();
                }
            }
        }
        tables.removeIf(Table::isDeleted);
    }

    //对CRF模型结果进行融合
    public static void combinaCrfAndVectorResults(ContentGroupPage page,List<Table> crfLineTables,List<Table> crfNonLinesTables,List<Table> lineTables,List<Table> nonLinesTables){
        if(!page.params.useCRFModel){
            return;
        }
        float avgChartHeight = page.getAvgCharHeight();
        List<Table> tempRemoveNolineTables = new ArrayList<>();
        List<Table> tempRemovelineTables = new ArrayList<>();
        //TableDebugUtils.writeTables(page,crfLineTables,"crf有线表格");
        //TableDebugUtils.writeTables(page,crfNonLinesTables,"crf无线表格");
        //TableDebugUtils.writeTables(page,lineTables,"矢量有线表格");
        //TableDebugUtils.writeTables(page,nonLinesTables,"矢量无线表格");
        List<Table> tempNonLinesTables =  new ArrayList<>();
        List<Table> tempLinesTables =  new ArrayList<>();
        Boolean bFind = false;
        float overlapRatio = 0;

        //矢量无线表格如果比CRF多出2张以上,矢量的全部丢弃
        if(nonLinesTables.size() - crfNonLinesTables.size()>2){
            TableDebugUtils.writeTables(page,nonLinesTables,"矢量无线表格比CRF多出2张以上");
            nonLinesTables.clear();
        }

        //先对有线表格进行融合：以矢量为准
        for(Table crf:crfLineTables){
            bFind=false;
            for(int i = 0; i < lineTables.size();i++){
                overlapRatio = lineTables.get(i).overlapRatio(crf);
                if(overlapRatio > 0) { //有线表格,以矢量为准
                    bFind = true;
                    break;
                }
            }
            if(!bFind){
                tempLinesTables.add(crf);
            }
        }

        //对矢量结果进行排查
        for(int i=0;i<lineTables.size();i++){
            removeSpecialTable(page,lineTables.get(i),tempRemovelineTables);
        }

        TableDebugUtils.writeTables(page,tempRemovelineTables,"融合过程中被丢弃的表格");
        lineTables.removeAll(tempRemovelineTables);
        tempLinesTables.addAll(lineTables);

        //对无线表格进行融合：以CRF为准
        for(Table crf : crfNonLinesTables){
            bFind=false;
            for(int i = 0; i < nonLinesTables.size(); i++){
                overlapRatio = nonLinesTables.get(i).overlapRatio(crf);
                if(overlapRatio > 0){//相交,以CRF为准
                    bFind=true;
                    //先对矢量表格进行修正
                    TableRegionCrfAlgorithm.repairVectorRegion(nonLinesTables.get(i));
                    //CRF包含矢量并且与下一张矢量表格相交
                    if(overlapRatio < 0.9 && crf.nearlyContains(nonLinesTables.get(i),avgChartHeight)
                            && i+1 < nonLinesTables.size()-1 && nonLinesTables.get(i+1).intersects(crf) && nonLinesTables.size() <= 2){
                        TableDebugUtils.writeTables(page,Arrays.asList(crf),"CRF包含矢量,选择矢量");
                        //此处进行设置是因为后续流程中CRF结果会可信度比较高,就不用再次处理了
                        crf.setRect(nonLinesTables.get(i));//矢量被包含,可能是CRF包含了两个表格或者区域扩大,此时以矢量为主
                    }
                    if(overlapRatio < 0.9 && (nonLinesTables.get(i).nearlyContains(crf,avgChartHeight) || crf.nearlyContains(nonLinesTables.get(i),3.0f))){
                     //此时利用CRF的特征进行选择
                        if(TableRegionCrfAlgorithm.chooseNolineTableByCRF(nonLinesTables.get(i),crf)){
                            TableDebugUtils.writeTables(page,Arrays.asList(crf),"矢量与CRF存在包含关系,选择矢量");
                            crf.setRect(nonLinesTables.get(i));//矢量被包含,可能是CRF包含了两个表格或者区域扩大,此时以矢量为主
                        }
                    }
                    //如果矢量包含CRF,此时可能是CRF区域不完整，也有可能是矢量多包含，目前观测到的是矢量包含的情况较多，因此以CRF为准
                    if(!nonLinesTables.get(i).isCrfTable()) {
                        nonLinesTables.set(i, crf);//如果不存在CRF包含矢量的问题,此时以CRF结果对矢量进行修正
                    }
                    break;
                }
            }
            if(!bFind){
                tempNonLinesTables.add(crf);
            }
        }
        //对矢量结果进行排查
        for(int i = 0; i < nonLinesTables.size(); i++){
            removeSpecialTable(page,nonLinesTables.get(i),tempRemoveNolineTables);
        }

        TableDebugUtils.writeTables(page,tempRemoveNolineTables,"融合过程中被丢弃的表格");
        nonLinesTables.removeAll(tempRemoveNolineTables);
        tempNonLinesTables.addAll(nonLinesTables);
        //去重
        removeRepeatTable(tempLinesTables,tempNonLinesTables);
        if(tempLinesTables.size() != 0 ){
            lineTables.clear();
            lineTables.addAll(tempLinesTables);
        }
        if(tempNonLinesTables.size() != 0 ){
            nonLinesTables.clear();
            nonLinesTables.addAll(tempNonLinesTables);
            //TableDebugUtils.writeTables(page,nonLinesTables,"result_无线表格");
        }
        //再利用CRF里的特征对表格进行再次过滤
        TableRegionCrfAlgorithm.removeTableByCrfFeatures(page,lineTables,nonLinesTables);
    }

    public static void removeSpecialTable(ContentGroupPage page,Table table,List<Table> tempRemoveTables){
        float avgChartHeight = page.getAvgCharHeight();
        List<TableRegion> chartRegions = page.getChartRegions("");
        //如果与chart区域相交的丢弃
        for(TableRegion area:chartRegions){
            if (area.intersects(table) && !tempRemoveTables.contains(table)){
                tempRemoveTables.add(table);
                return;
            }
            //对矢量表格可以尝试扩展区域
            /*
            if(!table.isCrfTable() && !tempRemoveTables.contains(table) &&
                    new Rectangle(table.getLeft(),table.getTop()-avgChartHeight*0.8,table.getWidth(),table.getHeight()+avgChartHeight*1.6).intersects(area)){
                tempRemoveTables.add(table);
                return;
            }
            */
        }
    }
    //防止一个表格与多个表格相交后的去重问题
    public static void removeRepeatTable(List<Table> lineTables,List<Table> noLineTables){
        List<Table> removeLineTables = new ArrayList<>();
        List<Table> removeNoLineTables = new ArrayList<>();
        if(lineTables.size() != 0) {
            //去除与自己重复的
            for (int i = 0; i < lineTables.size(); i++) {
                for (int j = 0; j < lineTables.size(); j++) {
                    if (i != j && lineTables.get(i).intersects(lineTables.get(j))) {
                        //CRF结果已经去除了相交的情况,此时一律以CRF结果为准
                        if (lineTables.get(i).isCrfTable() && !lineTables.get(j).isCrfTable() && !removeLineTables.contains(lineTables.get(j))) {
                            removeLineTables.add(lineTables.get(j));
                        } else if (lineTables.get(j).isCrfTable() && !lineTables.get(i).isCrfTable() && !removeLineTables.contains(lineTables.get(i))) {
                            removeLineTables.add(lineTables.get(i));
                        }
                    }
                }
            }
            lineTables.removeAll(removeLineTables);
            removeLineTables.clear();
        }
        if(noLineTables.size() != 0) {
            //去除与自己重复的
            for (int i = 0; i < noLineTables.size(); i++) {
                for (int j = 0; j < noLineTables.size(); j++) {
                    if (i != j && noLineTables.get(i).intersects(noLineTables.get(j))) {
                        //CRF结果已经去除了相交的情况,此时一律以CRF结果为准
                        if (noLineTables.get(i).isCrfTable() && !noLineTables.get(j).isCrfTable() && !removeNoLineTables.contains(noLineTables.get(j))) {
                            removeNoLineTables.add(noLineTables.get(j));
                        } else if (noLineTables.get(j).isCrfTable() && !noLineTables.get(i).isCrfTable() && !removeNoLineTables.contains(noLineTables.get(i))) {
                            removeNoLineTables.add(noLineTables.get(i));
                        }
                    }
                }
            }
            noLineTables.removeAll(removeNoLineTables);
            removeNoLineTables.clear();
        }

        //去除与其他的重复的
        for(int i=0;i < lineTables.size();i++){
            for(int j=0;j < noLineTables.size();j++){
                if(lineTables.get(i).intersects(noLineTables.get(j))){
                   //大多是因为区域不完整,导致有线无线判断错误,此时以面积大的为准
                    if(lineTables.get(i).getArea()>=noLineTables.get(j).getArea()){
                        removeNoLineTables.add(noLineTables.get(j));
                    }else {
                        removeLineTables.add(lineTables.get(i));
                    }
                }
            }
        }
        noLineTables.removeAll(removeNoLineTables);
        lineTables.removeAll(removeLineTables);
    }
    public static void filterTableByStructureFeature(Page page, List<Table> nonLinesTables, List<Table> lineTables) {
        List<Table> structureTables = new ArrayList<>();
        if (null == nonLinesTables) {
            nonLinesTables = new ArrayList<>();
        }
        if (null == lineTables) {
            lineTables = new ArrayList<>();
        }

        for (Table table : nonLinesTables) {
            if (!table.isStructureTable()) {
                continue;
            }
            if (table.hasSingleCell()) {
                table.markDeleted();
                continue;
            }
            structureTables.add(table);
        }
        nonLinesTables.removeIf(Rectangle::isDeleted);
        for (Table table : lineTables) {
            if (table.isStructureTable()) {
                structureTables.add(table);
            }
        }

        if (structureTables.isEmpty()) {
            return;
        }

        if (!nonLinesTables.isEmpty()) {
            for (Table structureTable : structureTables) {
                if (structureTable.isDeleted()) {
                    continue;
                }
                List<Ruling> structureRulings = structureTable.getPage().getRulings().stream().filter(rul -> structureTable
                        .isShapeIntersects(rul.toRectangle())).collect(Collectors.toList());
                if (!structureRulings.isEmpty()) {
                    Rectangle structureBound = new Rectangle(structureTable.createUnion(Ruling.getBounds(structureRulings)));
                    if (structureBound.getArea() > 1.5 * structureTable.getArea()) {
                        structureTable.markDeleted();
                        continue;
                    }
                }
                for (Table table : nonLinesTables) {
                    if (table.isDeleted() || table.isStructureTable()) {
                        continue;
                    }
                    float overlapRatio = table.overlapRatio(structureTable);
                    if (overlapRatio > 0) {
                        Rectangle reduceTableBound = table.rectReduce(2.0, 2.0
                                , table.getPage().getWidth(), table.getPage().getHeight());
                        List<TableRegion> rulingRectangles = table.getPage().getRulings().stream().filter(rul -> reduceTableBound
                                .isShapeIntersects(rul.toRectangle())).map(Ruling::toTableRegion).collect(Collectors.toList());
                        if (!rulingRectangles.isEmpty()) {
                            RulingTableRegionsDetectionAlgorithm.findLineTableRegion((ContentGroupPage) table.getPage(), rulingRectangles);
                            if (table.isBoundingTable() && rulingRectangles.size() == 1 && rulingRectangles.get(0).nearlyContains(table, 2.0)
                                    && (overlapRatio > 0.8 || table.getRowCount() > 5)) {
                                structureTable.markDeleted();
                                break;
                            }
                        }
                        Rectangle2D intersectBounds = structureTable.createIntersection(table);
                        float a0 = (float) (intersectBounds.getWidth() * intersectBounds.getHeight());
                        float a1 = (float) (structureTable.getWidth() * structureTable.getHeight());
                        float a2 = (float) (table.getWidth() * table.getHeight());
                        float u = a1 + a2 - a0;
                        if (a0 / u > 0.2 || a0 / a1 > 0.2) {
                            table.markDeleted();
                        }
                    }
                }
            }
            nonLinesTables.removeIf(Rectangle::isDeleted);
        }

        if (!lineTables.isEmpty()) {
            for (Table structureTable : structureTables) {
                if (structureTable.isDeleted()) {
                    continue;
                }
                Rectangle structureTextBound = Rectangle.boundingBoxOf(structureTable.getPage().getTextChunks(structureTable));
                for (Table table : lineTables) {
                    if (table.isDeleted() || table.isStructureTable()) {
                        continue;
                    }
                    float overlapRatio = structureTable.overlapRatio(table);
                    if (overlapRatio > 0.1) {
                        //如果结构化表格包含有线表格且结构化表格有TH等信息则去掉有线表格
                        Rectangle vectorTextBound = Rectangle.boundingBoxOf(table.getPage().getTextChunks(table));
                        if (structureTextBound.nearlyContains(vectorTextBound, 2.0) && overlapRatio < 0.3
                                && (table.getHeight() < 0.5 * structureTable.getHeight() || table.getWidth() < 0.5 * structureTable.getWidth())
                                && structureTable.hasReliableTableStructure()) {
                            table.markDeleted();
                            continue;
                        }
                        structureTable.markDeleted();
                        break;
                    }
                }
            }
        }
        nonLinesTables.removeIf(Rectangle::isDeleted);
        lineTables.removeIf(Rectangle::isDeleted);

        //对融合结果进行最终过滤，将包含内型表格过滤掉
        nearlyContainRemove(page, nonLinesTables);
        nearlyContainRemove(page, lineTables);
    }

    private boolean hasReliableTableStructure() {
        List<TextChunk> textChunks = this.page.getTextChunks(this).stream().filter(chunk
                -> chunk.getStructureTypes().contains("TH")).collect(Collectors.toList());
        Rectangle bound = Rectangle.boundingBoxOf(textChunks);
        if (bound.getBottom() < 0.5 * this.getBottom() || FloatUtils.feq(bound.getTop(), this.getTop(), this.page.getAvgCharHeight())) {
            return true;
        }
        return false;
    }

    static final class CellMatrix {

        private final boolean[][] matrix;
        private final int maxRow;
        private final int maxCol;

        private int row = -1;
        private int col = -1;

        CellMatrix(int maxRow, int maxCol) {
            this.matrix = new boolean[maxRow][maxCol];
            for (int i = 0; i < maxRow; ++i) {
                this.matrix[i] = new boolean[maxCol];
            }
            this.maxRow = maxRow;
            this.maxCol = maxCol;
        }

        int getRow() {
            return row;
        }

        int getCol() {
            return col;
        }

        void nextRow() {
            do {
                ++row;
                col = 0;
                while (row < maxRow && col < maxCol && matrix[row][col]) {
                    ++col;
                }
            } while (col >= maxCol && row < maxRow);
        }

        void nextCol() {
            if (col <= maxCol) {
                ++col;
            }
        }

        void putCell(int rowSpan, int colSpan) {
            for (int i = 0; i < rowSpan; ++i) {
                for (int j = 0; j < colSpan; ++j) {
                    // 处理可能的超行、超列的情形
                    if (row + i >= maxRow || col + j >= maxCol)
                        continue;
                    matrix[row + i][col + j] = true;
                }
            }
            col += colSpan;
            while (row < maxRow && col < maxCol && matrix[row][col]) {
                ++col;
            }
        }
    }

    private boolean isBoundingTable;//是否属于有线框表格

    public void setBoundingFlag(boolean isBoundingTable) {
        this.isBoundingTable = isBoundingTable;
    }

    public boolean isBoundingTable() {
        return isBoundingTable;
    }
}