package com.abcft.pdfextract.core.table;

import com.abcft.pdfextract.core.PaperParameter;
import com.abcft.pdfextract.core.chart.Chart;
import com.abcft.pdfextract.core.model.*;
import com.abcft.pdfextract.core.model.Rectangle;
import com.abcft.pdfextract.core.table.detectors.NoRulingTableRegionsDetectionAlgorithm;
import com.abcft.pdfextract.core.table.extractors.LayoutAnalysisAlgorithm;
import com.google.common.collect.Lists;
import org.apache.pdfbox.pdmodel.PDPage;

import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

// TODO: this class should probably be called "PageArea" or something like that

@SuppressWarnings("serial")
public class ContentGroupPage extends Page {

    public static final Character[] WHITE_SPACE_CHARS = { ' ', '\t', '\r', '\n', '\f' };

    public static ContentGroupPage fromPage(Page page) {
        return new ContentGroupPage(
                page.params, page.pageNumber, page.getPDPage(),
                page.getLeft(), page.getTop(), (float) page.getWidth(), (float) page.getHeight(), page.rotation, page.paper,
                page.minCharWidth, page.minCharHeight, page.avgCharWidth, page.avgCharHeight, page.texts, page.textObjects,
                page.rulings, page.getClipAreas(), page.charSpatialIndex, page.textObjectsSpatialIndex,
                page.language, page.region, page.script, page.pathItems

        );
    }

    private List<TextBlock> textRows;
    private List<TextChunk> textChunks;
    private RectangleSpatialIndex<TextChunk> chunkSpatialIndex;
    private RectangleSpatialIndex<TextChunk> blockSpatialIndex;
    private ContentGroup root;
    private List<FillAreaGroup> fillAreaGroups = new ArrayList<>();
    private List<TableRegion> chartRegions = null;
    private List<Chart> charts = new ArrayList<>();
    private List<TextChunk> marginChunks = new ArrayList<>();

    ContentGroupPage(TableExtractParameters params, int pageNumber, PDPage pdPage, float left, float top, float width, float height,
                     int rotation, PaperParameter paper, float minCharWidth, float minCharHeight,float avgCharWidth, float avgCharHeight,
                     List<TextElement> characters, List<TextChunk> textObjects, List<Ruling> rulings, List<Rectangle2D> clipAreas,
                     RectangleSpatialIndex<TextElement> charIndex, RectangleSpatialIndex<TextChunk> textIndex, String language, String region, String script, List<PathItem> pathItems) {
        super(params, pageNumber, pdPage, left, top, width, height, rotation, paper, minCharWidth, minCharHeight, avgCharWidth, avgCharHeight, characters, textObjects, rulings, clipAreas, charIndex, textIndex, language, region, script, pathItems);


        List<TextChunk> textChunks = TextMerger.groupNeighbour(textObjects, this.getHorizontalRulings(), this.getVerticalRulings(), true);
        List<TextBlock> tb = TextMerger.groupByBlock(textChunks, this.getHorizontalRulings(), this.getVerticalRulings());
        List<TextChunk> tcFromTb = tb.stream().map(TextChunk::new).collect(Collectors.toList());
        this.textRows = TextMerger.collectByLines(tcFromTb);
        this.chunkSpatialIndex = buildSpatialIndex(textChunks);
        this.blockSpatialIndex = buildSpatialIndex(tcFromTb);
        this.textChunks = textChunks;
    }

    void setRoot(ContentGroup root) {
        this.root = root;
    }

    @Override
    public ContentGroupPage getArea(Rectangle area) {
        // FIXME Expend the area a little bit to include edges
        Rectangle safeArea = (Rectangle) area.clone();
        safeArea.centerExpand(PAGE_BOUNDS_EPSILON);
        List<TextElement> t = getText(safeArea);
        List<TextChunk> tj = getTextObjects(safeArea);

        float minCharWidth = (float)t.parallelStream().mapToDouble(TextElement::getTextWidth)
                .min().orElse(DEFAULT_MIN_CHAR_WIDTH);
        float minCharHeight = (float)t.parallelStream().mapToDouble(TextElement::getTextHeight)
                .min().orElse(Page.DEFAULT_MIN_CHAR_HEIGHT);
        float avgCharWidth = (float)t.parallelStream().mapToDouble(TextElement::getTextWidth)
                .average().orElse(DEFAULT_MIN_CHAR_WIDTH);
        float avgCharHeight = (float)t.parallelStream().mapToDouble(TextElement::getTextHeight)
                .average().orElse(Page.DEFAULT_MIN_CHAR_HEIGHT);

        ContentGroupPage rv = new ContentGroupPage(
                params,  pageNumber, getPDPage(),
                area.getLeft(), area.getTop(), (float) area.getWidth(), (float) area.getHeight(), rotation, paper,
                minCharWidth, minCharHeight, avgCharWidth, avgCharHeight, t, tj,
                Ruling.cropRulingsToArea(getRulings(), safeArea),
                getClipAreas(), charSpatialIndex, textObjectsSpatialIndex,
                language, region, script, pathItems);
        rv.root = this.root;

        rv.addRuling(new Ruling(
                new Point2D.Double(area.getLeft(),
                        area.getTop()),
                new Point2D.Double(area.getRight(),
                        area.getTop())));
        rv.addRuling(new Ruling(
                new Point2D.Double(area.getRight(),
                        area.getTop()),
                new Point2D.Double(area.getRight(),
                        area.getBottom())));
        rv.addRuling(new Ruling(
                new Point2D.Double(area.getRight(),
                        area.getBottom()),
                new Point2D.Double(area.getLeft(),
                        area.getBottom())));
        rv.addRuling(new Ruling(
                new Point2D.Double(area.getLeft(),
                        area.getBottom()),
                new Point2D.Double(area.getLeft(),
                        area.getTop())));
        return rv;
    }

    public List<TextBlock> getTextLines() {
        return textRows;
    }

    /**
     * 获取拿整个页面的所有TextChunk
     */
    public List<TextChunk> getTextChunks() {
        return this.textChunks;
    }

    /**
     * 获取整个页面的所有TextChunk，每个对象新建了一个拷贝
     */
    public List<TextChunk> getMutableTextChunks() {
        if (this.textChunks == null) {
            return new ArrayList<>();
        }

        List<TextChunk> chunks = new ArrayList<>(this.textChunks.size());
        //原始TextChunk已排过序，这里不再排序
        for (TextChunk rawChunk : this.textChunks) {
            chunks.add(new TextChunk(rawChunk));
        }

        return chunks;
    }

    public List<TextChunk> getTextChunks(Rectangle area) {
        return getTextChunksFromIndex(chunkSpatialIndex, area, false);
    }

    public List<TextChunk> getMutableTextChunks(Rectangle area) {
        return getTextChunksFromIndex(chunkSpatialIndex, area, true);
    }

    public List<TextChunk> getMergeChunks(Rectangle area) {
        return getTextChunksFromIndex(blockSpatialIndex, area, false);
    }

    public List<TextChunk> getMutableMergeChunks(Rectangle area) {
        return getTextChunksFromIndex(blockSpatialIndex, area, true);
    }

    public ContentGroup getContentGroup() {
        return root;
    }

    public List<Rectangle> getTableLayoutAnalysis() {
        if (params.useLayoutAnalysis) {
            if (this.getContentGroup().hasTag(Tags.TABLE_LAYOUT_RESULT)) {
                return root.getTag(Tags.TABLE_LAYOUT_RESULT, LayoutResult.class).getTableLayoutAreas();
            } else {
                List<Rectangle> contentRectangles = new ArrayList<>();
                List<Rectangle> tableRectangles = new ArrayList<>();
                getChartRegions("ContentGroupPage");
                LayoutAnalysisAlgorithm.detect(this, tableRectangles, contentRectangles);
                LayoutResult layout = new LayoutResult(tableRectangles, contentRectangles);
                root.addTag(Tags.TABLE_LAYOUT_RESULT, layout);
                return tableRectangles;
            }
        } else {
            return Lists.newArrayList(this);
        }
    }

    public List<TextChunk> getPageMarginChunks() {
        return marginChunks;
    }

    public void setPageMarginChunks(List<TextChunk> marginChunkList) {
        this.marginChunks = marginChunkList;
    }

    public List<FillAreaGroup> getPageFillAreaGroup() {
        return fillAreaGroups;
    }

    public void setPageFillAreaGroup(List<FillAreaGroup> groups) {
        this.fillAreaGroups = groups;
    }

    public List<TableRegion> getChartRegions(String className) {
        if (className.equals("LayoutAnalysisAlgorithm")) {
            if (this.chartRegions == null) {
                this.chartRegions = new NoRulingTableRegionsDetectionAlgorithm().findMostPossibleChartRegion(this);
                return this.chartRegions;
            } else {
                return this.chartRegions;
            }
        } else {
            if (this.getContentGroup().hasTag(Tags.IS_CHART_DETECT_IN_TABLE)) {
                return this.chartRegions;
            } else {
                this.chartRegions = new NoRulingTableRegionsDetectionAlgorithm().findMostPossibleChartRegion(this);
                root.addTag(Tags.IS_CHART_DETECT_IN_TABLE, true);
                return this.chartRegions;
            }
        }
    }

    public void setCharts(List<Chart> charts) {
        this.charts = charts;
    }

    public List<Chart> getCharts() {
        return charts;
    }

    /*
     TODO Enable content group on table
    @Override
    public void commitTables(List<Table> tables) {
        for (Table table : tables) {
            if (root != null) {
                table.setContentGroup(root.getSubgroup(table));
            }
            this.tableAreas.add(new Rectangle(table));
        }
    }
    */
}
