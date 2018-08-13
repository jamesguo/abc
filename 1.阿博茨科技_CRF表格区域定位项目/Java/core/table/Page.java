package com.abcft.pdfextract.core.table;

import com.abcft.pdfextract.core.PaperParameter;
import com.abcft.pdfextract.core.model.*;
import com.abcft.pdfextract.core.model.Rectangle;
import com.abcft.pdfextract.core.table.detectors.NoRulingTableRegionsDetectionAlgorithm;
import com.abcft.pdfextract.core.table.detectors.RulingTableRegionsDetectionAlgorithm;
import com.abcft.pdfextract.core.table.detectors.TableRegionDetectionUtils;
import com.abcft.pdfextract.util.TextUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Triple;
import org.apache.pdfbox.pdmodel.PDPage;

import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public abstract class Page extends Rectangle {

    static final float PAGE_BOUNDS_EPSILON = 0.1f;
    public static final float DEFAULT_MIN_CHAR_WIDTH = 7.0f;
    public static final float DEFAULT_MIN_CHAR_HEIGHT = 7.0f * 1.2f;

    public static final float HEADER_LINE_FACTOR = 0.65f;

    private static final RulingTableRegionsDetectionAlgorithm RULING_TABLE_REGIONS_DETECTION_ALGORITHM = new RulingTableRegionsDetectionAlgorithm();
    private static final NoRulingTableRegionsDetectionAlgorithm NO_RULING_TABLE_REGIONS_DETECTION_ALGORITHM = new NoRulingTableRegionsDetectionAlgorithm();
    private int textRotate;
    private Rectangle textArea;

    private static <T> void clearArray(List<T> list) {
        if (list != null) {
            list.clear();
        }
    }

    private static <T extends TextContainer> int countValidText(List<T> characters) {
        return (int) characters.parallelStream()
                .filter(text -> !StringUtils.isEmpty(text.getText())).count();
    }

    protected static <T extends Rectangle> RectangleSpatialIndex<T> buildSpatialIndex(List<T> characters) {
        RectangleSpatialIndex<T> index = new RectangleSpatialIndex<>();
        index.addAll(characters);
        return index;
    }

    private static void guessLanguage(Page page, List<? extends TextContainer> characters, String language) {
        String lang = "en";
        String script = null;
        String region = null;
        boolean guessed = false;
        if (!StringUtils.isEmpty(language)) {
            try {
                // Use PDF built-in language code first
                Triple<String, String, String> locale = TextUtils.parseLanguageCode(language);
                lang = locale.getLeft();
                script = locale.getMiddle();
                region = locale.getRight();
                guessed = !StringUtils.isEmpty(lang);
            } catch (IllegalArgumentException e) {
                TableExtractor.logger.warn("Invalid PDF language code: {}.", language);
                // The language code in the PDF is invalid, just use it as language
                lang = language;
            }
        }
        if (!guessed) {
            // TODO Use language detection algorithm e.g: https://github.com/optimaize/language-detector/
            // 下面的部分，如果检测到了CJK字符就视为中文
            // 这对中文 or 英文的场合有效，但需要更谨慎地使用
            for (TextContainer textChunk : characters) {
                String text = textChunk.getText();
                if (TextUtils.containsCJK(text)) {
                    lang = "zh";
                    region = "CN";
                    break;
                }
            }
        }
        page.language = lang;
        page.region = region;
        page.script = script;
    }


    static List<TextChunk> getTextChunksFromIndex(RectangleSpatialIndex<TextChunk> index, Rectangle area, boolean mutable) {
        List<TextChunk> rawChunks = index.intersects(area);
        List<TextChunk> chunks;
        if (mutable) {
            chunks = new ArrayList<>(rawChunks.size());
            for (TextChunk rawChunk : rawChunks) {
                chunks.add(new TextChunk(rawChunk));
            }
        } else {
            chunks = rawChunks;
        }
        chunks.sort(Comparator.comparing(TextChunk::getGroupIndex));
        return chunks;
    }

    public final TableExtractParameters params;
    private final PDPage pdPage;
    // Path of the PDF file, maybe null.
    public String pdfPath;
    protected Integer rotation;
    protected int pageNumber;
    protected List<TextElement> texts;
    protected List<TextChunk> textObjects;
    protected List<Ruling> rulings;
    protected float minCharWidth;
    protected float minCharHeight;
    protected float avgCharWidth;
    protected float avgCharHeight;
    protected RectangleSpatialIndex<TextElement> charSpatialIndex;
    protected RectangleSpatialIndex<TextChunk> textObjectsSpatialIndex;
    protected String language;
    protected String script;
    protected String region;
    protected PaperParameter paper;
    private List<Ruling> cleanRulings;
    private List<Ruling> blockRulings;
    private List<Ruling> dotRulings;
    private List<Ruling> visibleRulings;
    private List<Ruling> verticalRulingLines;
    private List<Ruling> horizontalRulingLines;

    //所有表格的区域
    private List<TableRegion> allTableRegions = null;

    //通过ruling检测出的表格的区域
    private List<TableRegion> lineTableRegions = null;

    //无线表格的区域
    private List<TableRegion> noRulingTableRegions = null;

    // 有线表格的区域
    private List<TableRegion> rulingTableRegions = null;


    private int validTextLength;
    private List<Rectangle2D> clipAreas = null;
    private Page prevPage;
    // 页面上正式的表格区域
    final List<Rectangle> tableAreas = new ArrayList<>();


    Page(TableExtractParameters params, int pageNumber, PDPage pdPage,
         float left, float top, float width, float height, int rotation, PaperParameter paper,
         float minCharWidth, float minCharHeight,float avgCharWidth,float avgCharHeight,
         List<TextElement> characters,
         List<TextChunk> textObjects,
         List<Ruling> rulings,
         List<Rectangle2D> clipAreas,
         RectangleSpatialIndex<TextElement> charIndex,
         RectangleSpatialIndex<TextChunk> textIndex,
         String language,
         String region,
         String script,
         List<PathItem> pathItems
    ) {
        super(left, top, width, height);
        this.pageNumber = pageNumber;
        this.params = params;
        this.rotation = rotation;
        this.paper = paper;
        this.pdPage = pdPage;
        this.minCharWidth = minCharWidth;
        this.minCharHeight = minCharHeight;
        this.avgCharWidth = avgCharWidth;
        this.avgCharHeight = avgCharHeight;
        this.texts = new ArrayList<>(characters);
        this.textObjects = new ArrayList<>(textObjects);
        if (StringUtils.isEmpty(language)) {
            // 不清楚为何需要检测语言，总之先算一波
            guessLanguage(this, characters, params.context.language);
        } else {
            this.language = language;
            this.region = region;
            this.script = script;
        }
        if (null == charIndex) {
            charIndex = buildSpatialIndex(characters);
        }
        this.charSpatialIndex = charIndex;
        if (null == textIndex) {
            textIndex = buildSpatialIndex(textObjects);
        }
        this.textObjectsSpatialIndex = textIndex;
        this.rulings = new ArrayList<>(rulings);
        if (null == clipAreas) {
            clipAreas = new ArrayList<>();
        }
        this.clipAreas = clipAreas;
        this.validTextLength = countValidText(characters);
        this.pathItems = pathItems;
    }

    public abstract Page getArea(Rectangle area);

    public Page getArea(float top, float left, float bottom, float right) {
        Rectangle area = new Rectangle(left, top, right - left, bottom - top);
        return getArea(area);
    }

    public List<Rectangle2D> getClipAreas() {
        return clipAreas;
    }

    public List<TextElement> getText() {
        return texts;
    }

    public List<TextElement> getText(Rectangle area) {
        return charSpatialIndex.contains(area);
    }

    public List<TextElement> getText(float left, float top, float right, float bottom) {
        return getText(new Rectangle(left, top, right - left, bottom - top));
    }

    public Rectangle getTextBoundingBoxWithinRegion(Rectangle region) {
        return Rectangle.boundingBoxOf(this.getText(region).stream().filter(te -> StringUtils.isNotBlank(te.getText()))
                .collect(Collectors.toList()));
    }

    public List<TextChunk> getTextObjects(Rectangle area) {
        return getTextChunksFromIndex(textObjectsSpatialIndex, area, false);
    }

    public List<TextChunk> getMutableTextObjects(Rectangle area) {
        return getTextChunksFromIndex(textObjectsSpatialIndex, area, true);
    }

    public List<TextChunk> getTextChunks(Rectangle area) {
        return getTextChunksFromIndex(textObjectsSpatialIndex, area, false);
    }

    public List<TextChunk> getMutableTextChunks(Rectangle area) {
        return getTextChunksFromIndex(textObjectsSpatialIndex, area, true);
    }


    public Integer getRotation() {
        return rotation;
    }

    public int getPageNumber() {
        return pageNumber;
    }


    public String getLanguage() {
        return language;
    }

    public String getRegion() {
        return region;
    }

    public String getScript() {
        return script;
    }

    public PaperParameter getPaper() {

        return paper;
    }

    /**
     * Returns the minimum bounding box that contains all the TextElements on this Page
     */
    public Rectangle getTextBounds() {
        if (!texts.isEmpty()) {
            return Rectangle.union(texts);
        } else {
            return new Rectangle();
        }
    }

    /**
     * 返回所有页面中所有线和边缘线。
     *
     * <p>包含独立的直线、细长的填充色块、（普通）填充色块的边缘，也包含宽高都很小视觉上是一个“点”的线。
     *
     * @return 页面中所有线。
     */
    public List<Ruling> getRawRulings() {
        return this.rulings;
    }

    /**
     * 返回所有页面中所有细长的线。
     *
     * <p>包含独立的直线、细长的填充色块，不包含（普通）填充色块的边缘、高都很小视觉上是一个“点”的线。
     * <p>注意：有的填充块是有另外的边线的(边线的颜色可能和色块相同），这些线<b>会</b>被包含。
     *
     * @return 页面中所有细长的线。
     */
    public List<Ruling> getRulings() {
        if (this.cleanRulings != null) {
            return this.cleanRulings;
        }
        classifyRulings();
        return this.cleanRulings;
    }

    /**
     * 返回页面中所有填充色块的边线。
     *
     * <p>包含（普通）填充色块的边缘，不包含独立的直线、细长的填充色块、宽高都很小视觉上是一个“点”的填充块。
     * <p>注意1：请注意有的填充块是有另外的边线的(边线的颜色可能和色块不同），这些线<b>不会</b>包含。
     * <p>注意2：一个大色块可能由多个小色块组成，因此这这些线可能无法直接用来确定表格。
     * 考虑结合 {@link Ruling#getBindingBounds()} 来判定。
     *
     * @return 包围色块的线。
     */
    public List<Ruling> getBlockRulings() {
        if (this.blockRulings != null) {
            return this.blockRulings;
        }
        classifyRulings();
        return this.blockRulings;
    }

    /**
     * 返回页面中所有点状边线。
     *
     * <p>包含宽高都很小视觉上是一个“点”的填充块。
     *
     * @return 包围色块的线。
     */
    public List<Ruling> getDotRulings() {
        if (this.dotRulings != null) {
            return this.dotRulings;
        }
        classifyRulings();
        return this.dotRulings;
    }

    public List<Ruling> getVisibleRulings() {
        if (this.visibleRulings != null) {
            return this.visibleRulings;
        }
        this.getRulings();
        return this.visibleRulings;
    }

    public List<Ruling> getVerticalRulings() {
        if (this.verticalRulingLines != null) {
            return this.verticalRulingLines;
        }
        this.getRulings();
        return this.verticalRulingLines;
    }

    public List<Ruling> getHorizontalRulings() {
        if (this.horizontalRulingLines != null) {
            return this.horizontalRulingLines;
        }
        this.getRulings();
        return this.horizontalRulingLines;
    }

    private void checkRulings(List<Ruling> rawRulings, List<Ruling> normalRulings, List<Ruling> unnormalRulings) {
        if (rawRulings == null || rawRulings.isEmpty() || normalRulings == null || unnormalRulings == null) {
            return;
        }

        List<PathItem> allPathItems = this.pathItems.stream().filter(pathItem -> pathItem.isFill()
                && !pathItem.getPathInfos().isEmpty()).collect(Collectors.toList());

        //目前只考虑白线
        for (Ruling ruling : rawRulings) {
            if (ruling.nearlyEqualColor(Color.white, 6)) {
                Rectangle rulingRect = ruling.toRectangle();
                List<PathItem> pathItems = allPathItems.stream().filter(pathItem -> pathItem.getBounds()
                        .intersects(rulingRect) && TableRegionDetectionUtils.nearlyEqualColor(ruling.getColor()
                        , pathItem.getColor(), 6) && (pathItem.getBounds().getWidth() > Ruling.LINE_WIDTH_THRESHOLD
                        && pathItem.getBounds().getHeight() > Ruling.LINE_WIDTH_THRESHOLD)).collect(Collectors.toList());
                if (pathItems.isEmpty()) {
                    unnormalRulings.add(ruling);
                } else {
                    Rectangle bound = Rectangle.boundingBoxOf(pathItems.stream().map(PathItem::getBounds).collect(Collectors.toList()));
                    float horIntersectLength = Math.min(bound.getRight(), rulingRect.getRight()) - Math.max(bound.getLeft(), rulingRect.getLeft());
                    float verIntersectLength = Math.min(bound.getBottom(), rulingRect.getBottom()) - Math.max(bound.getTop(), rulingRect.getTop());
                    if ((ruling.horizontal() && horIntersectLength > 0.5 * ruling.length())
                            || (ruling.vertical() && verIntersectLength > 0.5 * ruling.length())) {
                        unnormalRulings.add(ruling);
                    } else {
                        normalRulings.add(ruling);
                    }
                }
            } else {
                normalRulings.add(ruling);
            }
        }
    }

    private void classifyRulings() {

        if (this.rulings == null || this.rulings.isEmpty()) {
            this.verticalRulingLines = new ArrayList<>();
            this.horizontalRulingLines = new ArrayList<>();
            this.visibleRulings = new ArrayList<>();
            this.cleanRulings = new ArrayList<>();
            this.blockRulings = new ArrayList<>();
            this.dotRulings = new ArrayList<>();
            return;
        }

        TableDebugUtils.writeLines(this, this.rulings, "rulings_D0-raw");

        // 区分“线”、“块”级别 ruling
        //int blackRGB = Color.BLACK.getRGB();
        List<Ruling> lineRulings = this.rulings.parallelStream()
                .filter(r -> r.getDrawType() == Ruling.DrawType.LINE || r.getDrawType() == Ruling.DrawType.IMAGE_LINE
                        /* && (r.isFill() || r.getColor().getRGB() == blackRGB)*/)
                .collect(Collectors.toList());
        List<Ruling> blockRulings = this.rulings.parallelStream()
                .filter(r -> r.getDrawType() == Ruling.DrawType.RECT
                        /* && (r.isFill() || r.getColor().getRGB() == blackRGB)*/)
                .collect(Collectors.toList());
        List<Ruling> dotRulings = this.rulings.parallelStream()
                .filter(r -> r.getDrawType() == Ruling.DrawType.DOT || r.getDrawType() == Ruling.DrawType.IMAGE_DOT
                        /* && (r.isFill() || r.getColor().getRGB() == blackRGB)*/)
                .collect(Collectors.toList());
        List<Ruling> gridRulings = this.rulings.parallelStream()
                .filter(r -> r.getDrawType() == Ruling.DrawType.IMAGE_GRID
                        /* && (r.isFill() || r.getColor().getRGB() == blackRGB)*/)
                .collect(Collectors.toList());

        TableDebugUtils.writeLines(this, lineRulings, "rulings_D1-lines");
        TableDebugUtils.writeSolidLines(this, blockRulings, "rulings_D1-blocks");
        TableDebugUtils.writeDots(this, dotRulings, "rulings_D1-dots");
        TableDebugUtils.writeLines(this, gridRulings, "rulings_D1-grids");

        this.visibleRulings = new ArrayList<>(lineRulings);
        this.visibleRulings.addAll(gridRulings);
        this.blockRulings = new ArrayList<>(blockRulings);
        this.dotRulings = new ArrayList<>(dotRulings);

        List<Ruling> rulings = this.visibleRulings;
        List<Ruling> blocks = this.blockRulings;

        //image dot merge
        List<Ruling> mergedRulings = TableUtils.detectImageDotLine(dotRulings, 1.f, 1.f);
        TableDebugUtils.writeDots(this, mergedRulings, "rulings_D1-merged_dots");
        rulings.addAll(mergedRulings);

        //merge dotRulings and visibleRulings
        TableUtils.mergeDotAndLine(rulings, dotRulings, 1.5f, 1.5f);

        //check rulings
        List<Ruling> normalRulings = new ArrayList<>();
        List<Ruling> unnormalRulings = new ArrayList<>();
        checkRulings(rulings, normalRulings, unnormalRulings);
        TableDebugUtils.writeLines(this, unnormalRulings, "unnormal_rulings");

        // 如果页面有分栏的情况，这个算法可能存在导致线段分组错误的问题
        // 现在，只考虑把非常相近的线合并起来
        // 毫无疑问，点就不用下面的策略合并了
        if (StringUtils.startsWithIgnoreCase(language, "en")) {
            if (isExistSmallVerticalGap(normalRulings)) {
                TableUtils.snapPoints(normalRulings, 1.f, 1.f);
            } else {
                TableUtils.snapPoints(normalRulings, 4.f, 4.f);
            }

            if (isExistSmallVerticalGap(blocks)) {
                TableUtils.snapPoints(blocks, 1.f, 1.f);
            } else {
                TableUtils.snapPoints(blocks, 4.f, 4.f);
            }
        } else {
            // TODO：字体的高度有问题，比实际的字体高度高出很多，导致后面线的融合出问题，具体可参看jc_1204472912
            // TODO：目前按如下方式取一半字体高度，对测试集暂无影响，建议前端考虑对文本高度不准的情况进行修复
            TableUtils.snapPoints(normalRulings, this.minCharWidth, this.minCharHeight / 2);
            TableUtils.snapPoints(blocks, this.minCharWidth, this.minCharHeight / 2);
        }
        TableDebugUtils.writeLines(this, normalRulings, "rulings_D2-snapped-lines");
        TableDebugUtils.writeSolidLines(this, blocks, "rulings_D2-snapped-blocks");

        if (null == normalRulings || normalRulings.isEmpty()) {
            this.verticalRulingLines = new ArrayList<>();
            this.horizontalRulingLines = new ArrayList<>();
            this.cleanRulings = new ArrayList<>();
            return;
        }

        List<Ruling> vrs = new ArrayList<>();
        List<Ruling> hrs = new ArrayList<>();
        for (Ruling r : normalRulings) {
            if (r.length() < Ruling.LINE_LENGTH_THRESHOLD) {
                continue;
            }
            if (r.vertical()) {
                vrs.add(r);
            } else if (r.horizontal()) {
                hrs.add(r);
            }
            // oblique line? ignored
        }

        TableDebugUtils.writeLines(this, vrs, "rulings_D3V");
        TableDebugUtils.writeLines(this, hrs, "rulings_D3H");
        this.verticalRulingLines = Ruling.collapseOrientedRulings(vrs);
        TableDebugUtils.writeLines(this, verticalRulingLines, "rulings_D3V-collapsed");
        this.horizontalRulingLines = Ruling.collapseOrientedRulings(hrs);
        TableDebugUtils.writeLines(this, horizontalRulingLines, "rulings_D3H-collapsed");

        this.cleanRulings = new ArrayList<>(this.verticalRulingLines);
        this.cleanRulings.addAll(this.horizontalRulingLines);
        TableDebugUtils.writeLines(this, cleanRulings, "rulings_D4-clean");
    }


    private boolean isExistSmallVerticalGap(List<Ruling> rulings) {
        boolean result = false;
        if (null == rulings || rulings.isEmpty()) {
            return false;
        }
        rulings.sort(Comparator.comparing(Ruling::getTop));
        Ruling baseLine = rulings.get(0);
        float minHeightThreshold = 0.f;
        for (int i = 1; i < rulings.size(); i++) {
            Ruling otherLine = rulings.get(i);
            if (otherLine.getTop() - baseLine.getTop() > 3.0) {
                if (minHeightThreshold == 0.f) {
                    minHeightThreshold = otherLine.getTop() - baseLine.getTop();
                } else {
                    if (minHeightThreshold > (otherLine.getTop() - baseLine.getTop())) {
                        minHeightThreshold = otherLine.getTop() - baseLine.getTop();
                    }
                }
            }
            baseLine = otherLine;
        }
        if (minHeightThreshold != 0.f && this.minCharHeight > minHeightThreshold && this.minCharHeight < 5.5) {
            result = true;
        }
        return result;
    }

    protected void addRuling(Ruling r) {
        if (r.oblique()) {
            throw new UnsupportedOperationException("Can't add an oblique ruling");
        }
        this.rulings.add(r);
        // clear caches
        this.verticalRulingLines = null;
        this.horizontalRulingLines = null;
        this.visibleRulings = null;
        this.cleanRulings = null;
    }

    public List<Ruling> getUnprocessedRulings() {
        return this.rulings;
    }

    public float getMinCharWidth() {
        return minCharWidth;
    }

    public float getMinCharHeight() {
        return minCharHeight;
    }

    public float getAvgCharWidth() {
        return avgCharWidth;
    }

    public float getAvgCharHeight() {
        return avgCharHeight;
    }

    public PDPage getPDPage() {
        return pdPage;
    }

    public boolean hasText() {
        return this.texts.size() > 0;
    }

    public void setTextRotate(int textRotate) {
        this.textRotate = textRotate;
    }

    public int getTextRotate() {
        return textRotate;
    }

    public Page getPrevPage() {
        return prevPage;
    }

    void setPrevPage(Page prevPage) {
        this.prevPage = prevPage;
    }

    public TableExtractParameters getParams() {
        return params;
    }

    @Override
    public String toString() {
        String s = super.toString();
        return s.substring(0, s.length() - 1) + String.format("; number=%d]", pageNumber);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        if (!super.equals(o))
            return false;

        Page page = (Page) o;

        return pdPage.equals(page.pdPage);
    }

    @Override
    public int hashCode() {
        int result = super.hashCode();
        result = 31 * result + pageNumber;
        return result;
    }

    public int getValidTextLength() {
        return validTextLength;
    }

    //get ruling table regions
    public List<TableRegion> getRulingTableRegions() {
        if (this.rulingTableRegions != null) {
            return this.rulingTableRegions;
        }

        this.detectAllTableRegions();
        return this.rulingTableRegions;
    }

    //get no ruling table regions
    public List<TableRegion> getNoRulingTableRegions() {
        if (this.noRulingTableRegions != null) {
            return this.noRulingTableRegions;
        }

        this.detectAllTableRegions();
        return this.noRulingTableRegions;
    }

    //get line table regions
    public List<TableRegion> getLineTableRegions() {
        if (this.lineTableRegions != null) {
            return this.lineTableRegions;
        }

        this.detectAllTableRegions();
        return this.lineTableRegions;
    }

    //get raw line table regions
    public List<TableRegion> getRawLineTableRegions() {
        if (this.lineTableRegions != null && this.lineTableRegions.stream().filter(TableRegion::getMergedFlag)
                .collect(Collectors.toList()).size() == 0) {
            return this.lineTableRegions;
        }

        ContentGroupPage tablePage;
        if (!(this instanceof ContentGroupPage)) {
            tablePage = ContentGroupPage.fromPage(this);
        } else {
            tablePage = (ContentGroupPage) this;
        }

        return RULING_TABLE_REGIONS_DETECTION_ALGORITHM.detect(tablePage);
    }

    //get all table region
    public List<TableRegion> getAllTableRegions() {
        if (this.allTableRegions != null) {
            return this.allTableRegions;
        }

        this.detectAllTableRegions();
        return this.allTableRegions;
    }

    //detect all table regions within page
    public void detectAllTableRegions() {
        if (this.allTableRegions != null) {
            return;
        }

        this.allTableRegions = new ArrayList<>();
        this.noRulingTableRegions = new ArrayList<>();
        this.lineTableRegions = new ArrayList<>();
        this.rulingTableRegions = new ArrayList<>();

        ContentGroupPage tablePage;
        if (!(this instanceof ContentGroupPage)) {
            tablePage = ContentGroupPage.fromPage(this);
        } else {
            tablePage = (ContentGroupPage) this;
        }

        this.lineTableRegions = RULING_TABLE_REGIONS_DETECTION_ALGORITHM.detect(tablePage);
        this.noRulingTableRegions = NO_RULING_TABLE_REGIONS_DETECTION_ALGORITHM.detect(tablePage);

        if (this.lineTableRegions != null) {
            for (TableRegion tr : this.lineTableRegions) {
                if (tr.getTableType() == TableType.LineTable) {
                    this.rulingTableRegions.add(tr);
                } else {
                    this.noRulingTableRegions.add(tr);
                }
            }
        }

        TableRegion.mergeDiffTypeTables(tablePage, this.rulingTableRegions, this.noRulingTableRegions);
        this.allTableRegions.addAll(this.rulingTableRegions);
        this.allTableRegions.addAll(this.noRulingTableRegions);
    }

    public void commitTables(List<Table> tables) {
        for (Table table : tables) {
            this.tableAreas.add(new Rectangle(table));
        }
    }

    public void clear() {
        clearNonText();
        clearText();
    }

    public List<Rectangle> getTableAreas() {
        return tableAreas;
    }

    private void clearText() {
        clearArray(texts);
        clearArray(tableAreas);

        if (charSpatialIndex != null) {
            charSpatialIndex.clear();
        }

        texts = null;
        charSpatialIndex = null;
    }

    public void clearNonText() {
        clearArray(visibleRulings);
        clearArray(cleanRulings);
        //clearArray(horizontalRulingLines);//用于后续特征提取
        //clearArray(verticalRulingLines);
        clearArray(rulings);
        clearArray(clipAreas);
        clearArray(rulingTableRegions);

        visibleRulings = null;
        cleanRulings = null;
        //horizontalRulingLines = null;
        //verticalRulingLines = null;
        rulings = null;
        rulingTableRegions = null;
        clipAreas = null;
        if (prevPage != null) {
            prevPage.clearText();
        }
        prevPage = null;
    }

    public void setTextArea(Rectangle textArea) {
        this.textArea = textArea;
    }

    public Rectangle getTextArea() {
        return textArea;
    }

    //设置页眉页脚检测信息
    private boolean pageHeaderDetected;
    private boolean pageFooterDetected;

    public void setPageHeaderDetected(boolean pageHeaderDetected) {
        this.pageHeaderDetected = pageHeaderDetected;
    }

    public boolean getPageHeaderDetected() {
        return this.pageHeaderDetected;
    }

    public void setPageFooterDetected(boolean pageFooterDetected) {
        this.pageFooterDetected = pageFooterDetected;
    }

    public boolean getPageFooterDetected() {
        return this.pageFooterDetected;
    }

    protected List<PathItem> pathItems = new ArrayList<>();
    public void setPathItems(List<PathItem> pathItems) {
        this.pathItems = pathItems;
    }

    public List<PathItem> getAllPathItems() {
        return pathItems;
    }

    public List<PathItem> getPathItems(Rectangle area) {
        return pathItems.stream().filter(pathItem -> area.intersects(pathItem.getBounds())).collect(Collectors.toList());
    }
}