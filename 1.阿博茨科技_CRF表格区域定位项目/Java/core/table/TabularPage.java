package com.abcft.pdfextract.core.table;

import com.abcft.pdfextract.core.PaperParameter;
import com.abcft.pdfextract.core.model.PathItem;
import com.abcft.pdfextract.core.model.Rectangle;
import com.abcft.pdfextract.core.model.TextChunk;
import com.abcft.pdfextract.core.model.TextElement;
import org.apache.pdfbox.pdmodel.PDPage;

import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.List;

// TODO: this class should probably be called "PageArea" or something like that

@SuppressWarnings("serial")
@Deprecated
public class TabularPage extends Page {

    public static TabularPage fromPage(Page page) {
        return new TabularPage(
                page.params, page.pageNumber, page.getPDPage(),
                page.getLeft(), page.getTop(), (float) page.getWidth(), (float) page.getHeight(), page.rotation, page.paper,
                page.minCharWidth, page.minCharHeight, page.avgCharWidth, page.avgCharHeight, page.texts, page.textObjects,
                page.rulings, page.getClipAreas(), page.charSpatialIndex, page.textObjectsSpatialIndex,
                page.language, page.region, page.script, page.pathItems

        );
    }

    private RectangleSpatialIndex<TabularTextChunk> chunkSpatialIndex;
    private List<TabularLine> textLines;

    TabularPage(TableExtractParameters params, int pageNumber, PDPage pdPage,
                float left, float top, float width, float height, int rotation, PaperParameter paper,
                float minCharWidth, float minCharHeight, float avgCharWidth, float avgCharHeight,
                List<TextElement> characters, List<TextChunk> textObjects,
                List<Ruling> rulings, List<Rectangle2D> clipAreas,
                RectangleSpatialIndex<TextElement> index,
                RectangleSpatialIndex<TextChunk> textIndex,
                String language, String region, String script, List<PathItem> pathItems) {
        super(params, pageNumber, pdPage,
                left, top, width, height, rotation, paper,
                minCharWidth, minCharHeight, avgCharWidth, avgCharHeight, characters, textObjects,
                rulings, clipAreas, index, textIndex,
                language, region, script, pathItems);
        buildTextChunkSpatialIndex();
    }

    private void buildTextChunkSpatialIndex() {
        List<TabularTextChunk> textChunks = TabularUtils.mergeWords(texts);
        textLines = TabularTextChunk.groupByLines(textChunks);
        chunkSpatialIndex = new RectangleSpatialIndex<>();
        textLines.forEach(line -> line.getTextElements().forEach(chunkSpatialIndex::add));
    }

    public List<TabularLine> getTextLines() {
        return textLines;
    }

    @Override
    public TabularPage getArea(Rectangle area) {
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

        TabularPage rv = new TabularPage(
                params,  pageNumber, getPDPage(),
                area.getLeft(), area.getTop(), (float) area.getWidth(), (float) area.getHeight(), rotation, paper,
                minCharWidth, minCharHeight, avgCharWidth, avgCharHeight, t, tj,
                Ruling.cropRulingsToArea(getRulings(), safeArea),
                getClipAreas(), charSpatialIndex, textObjectsSpatialIndex,
                language, region, script, pathItems);

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


}
