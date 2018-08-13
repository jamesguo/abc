package com.abcft.pdfextract.core.table;

import com.abcft.pdfextract.core.ExtractorUtil;
import com.abcft.pdfextract.core.PaperParameter;
import com.abcft.pdfextract.core.model.ContentItem;
import com.abcft.pdfextract.core.model.TextElement;
import com.abcft.pdfextract.core.util.GraphicsUtil;
import com.abcft.pdfextract.core.util.NumberUtil;
import com.abcft.pdfextract.util.ClosureBoolean;
import com.abcft.pdfextract.util.ClosureWrapper;
import org.apache.fontbox.util.BoundingBox;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.contentstream.operator.markedcontent.BeginMarkedContentSequence;
import org.apache.pdfbox.contentstream.operator.markedcontent.BeginMarkedContentSequenceWithProperties;
import org.apache.pdfbox.contentstream.operator.markedcontent.EndMarkedContentSequence;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDMarkedContent;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType3Font;
import org.apache.pdfbox.pdmodel.font.PDVectorFont;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.GeneralPath;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Stack;


/**
 * Engine used to write PDF Content
 * <p>
 * Created by chzhong on 17-1-25.
 */
@Deprecated
class TableExtractEngine extends PDFGraphicsStreamEngine {

    private static final Logger LOG = LogManager.getLogger(TableExtractor.class);

    private final float pageWidth;
    private final float pageHeight;
    private final TableExtractParameters params;
    private final int pageNumber;

    protected float minCharWidth;
    protected float minCharHeight;
    protected float avgCharWidth;
    protected float avgCharHeight;
    protected List<TextElement> characters;
    protected List<Ruling> rulings;
    protected RectangleSpatialIndex<TextElement> spatialIndex;
    private int textBlockId;
    private AffineTransform pageTransform;
    private boolean debugClippingPaths;
    private boolean extractRulingLines = true;
    private int clipWindingRule = -1;
    private GeneralPath currentPath = new GeneralPath();
    private List<Shape> clippingPaths;
    private boolean isPagination = false;
    private boolean isHeader = false;
    private boolean hasHeaderLine = false;
    private boolean isFooter = false;
    private boolean hasFooterLine = false;
    private final Stack<PDMarkedContent> currentMarkedContents = new Stack<>();
    private boolean inForm;
    private boolean wordGenerated;
    private boolean verticalPage;
    PaperParameter paper;

    TableExtractEngine(int pageNumber, PDPage page, TableExtractParameters params) {
        super(page);

        this.pageNumber = pageNumber;
        addOperator(new BeginMarkedContentSequenceWithProperties());
        addOperator(new BeginMarkedContentSequence());
        addOperator(new EndMarkedContentSequence());

        this.characters = new ArrayList<>();
        this.rulings = new ArrayList<>();
        this.pageTransform = null;
        this.spatialIndex = new RectangleSpatialIndex<>();
        this.minCharWidth = Float.MAX_VALUE;
        this.minCharHeight = Float.MAX_VALUE;

        // calculate page transform
        PDRectangle cb = this.getPage().getCropBox();
        int rotation = this.getPage().getRotation();

        this.pageTransform = new AffineTransform();

        if (Math.abs(rotation) == 90 || Math.abs(rotation) == 270) {
            this.pageWidth = cb.getHeight();
            this.pageHeight = cb.getWidth();
            this.pageTransform = AffineTransform.getRotateInstance(rotation * (Math.PI / 180.0), 0, 0);
            this.pageTransform.concatenate(AffineTransform.getScaleInstance(1, -1));
            this.pageTransform.concatenate(AffineTransform.getTranslateInstance(0, cb.getHeight()));
            this.pageTransform.concatenate(AffineTransform.getScaleInstance(1, -1));
        } else {
            this.pageHeight = cb.getHeight();
            this.pageWidth = cb.getWidth();
            this.pageTransform.concatenate(AffineTransform.getTranslateInstance(0, cb.getHeight()));
            this.pageTransform.concatenate(AffineTransform.getScaleInstance(1, -1));
        }

        this.params = params;
        this.wordGenerated = ExtractorUtil.isWordGeneratedPDF(params);
        this.verticalPage = pageWidth < pageHeight;
        this.paper = PaperParameter.findPage(pageWidth, pageHeight, verticalPage);
    }

    @Override
    public void processPage(PDPage page) throws IOException {
        super.processPage(page);
        ClosureWrapper<PaperParameter> paperWrapper = new ClosureWrapper<>(paper);
        ClosureBoolean hasHeaderLineWrapper = new ClosureBoolean(hasHeaderLine);
        TableUtils.findAdobeHeaderLine(rulings, pageWidth, hasHeaderLineWrapper, paperWrapper);
        hasHeaderLine = hasHeaderLineWrapper.get();
        paper = paperWrapper.get();
        onPostProcessPage();
    }

    @Override
    public void beginMarkedContentSequence(COSName tag, COSDictionary properties) {
        if (null == properties) {
            // Ensure properties would make our life easier.
            properties = new COSDictionary();
        }
        PDMarkedContent group = PDMarkedContent.create(tag, properties);
        currentMarkedContents.add(group);
        ContentItem.checkPagination(group);
        isPagination = properties.getBoolean("Pagination", false);
        isHeader = properties.getBoolean("Pagination.Header", false);
        isFooter = properties.getBoolean("Pagination.Footer", false);
    }

    @Override
    public void endMarkedContentSequence() {
        currentMarkedContents.pop();
        if (!currentMarkedContents.isEmpty()) {
            PDMarkedContent group = currentMarkedContents.peek();
            COSDictionary properties = group.getProperties();
            if (properties != null) {
                isPagination = properties.getBoolean("Pagination", false);
                isHeader = properties.getBoolean("Pagination.Header", false);
                isFooter = properties.getBoolean("Pagination.Footer", false);
            }
        } else {
            isPagination = false;
            isHeader = false;
            isFooter = false;
        }
    }

    @Override
    public void beginText() throws IOException {
        super.beginText();
        ++text;
    }

    @Override
    public void showTextStrings(COSArray array) throws IOException {
        super.showTextStrings(array);
        ++textBlockId;
    }

    @Override
    public void showTextString(byte[] string) throws IOException {
        super.showTextString(string);
        ++textBlockId;
    }

    @Override
    protected void showGlyph(Matrix textRenderingMatrix, PDFont font, int code, String unicode, Vector displacement)
            throws IOException {
        if (null == unicode) {
            return;
        }
        if ((!wordGenerated && isPagination) || isFooter) {
            // 某些 Word 生成的 PDF 页眉内可能包含有效文字（银行类三大表表格）
            // 因此这里仅对非Word的页眉以及所有的页脚直接跳过
            return;
        }

        Rectangle2D clipBounds = currentClippingPath().getBounds2D();
        // Only handle rect big enough to hold characters
        // If the rect is too small to hold a text, ignore it
        if (clipBounds.getWidth() < 4 || clipBounds.getHeight() < 4) {
            return;
        }

        BoundingBox bbox = font.getBoundingBox();
        if (bbox.getLowerLeftY() < Short.MIN_VALUE) {
            // PDFBOX-2158 and PDFBOX-3130
            // files by Salmat eSolutions / ClibPDF Library
            bbox.setLowerLeftY(-(bbox.getLowerLeftY() + 65536));
        }
        // advance width, bbox height (glyph space)
        float xadvance = font.getWidth(code);
        Rectangle2D.Float rect = new Rectangle2D.Float(0, bbox.getLowerLeftY(), xadvance, bbox.getHeight());

        // glyph space -> display space
        AffineTransform at = (AffineTransform) getPageTransform().clone();
        at.concatenate(textRenderingMatrix.createAffineTransform());

        // for visible space
        Rectangle2D visibleBBox = null;
        AffineTransform visibleAt = (AffineTransform) at.clone();
        visibleAt.concatenate(font.getFontMatrix().createAffineTransform());

        if (font instanceof PDType3Font) {
            // bbox and font matrix are unscaled
            at.concatenate(font.getFontMatrix().createAffineTransform());
        } else {
            // bbox and font matrix are already scaled to 1000
            at.scale(1 / 1000f, 1 / 1000f);
        }
        Rectangle2D bounds = at.createTransformedShape(rect).getBounds2D();

        // get the path
        PDVectorFont vectorFont = (PDVectorFont) font;
        GeneralPath path = null;
        try {
            path = vectorFont.getNormalizedPath(code);
        } catch (Exception e) {
            LOG.warn("get visible path failed!");
        }
        if (path != null) {
            // stretch non-embedded glyph if it does not match the width contained in the PDF
            if (!font.isEmbedded())
            {
                float fontWidth = font.getWidthFromFont(code);
                if (fontWidth > 0 && Math.abs(fontWidth - displacement.getX() * 1000) > 0.0001)
                {
                    float sx = (displacement.getX() * 1000) / fontWidth;
                    visibleAt.scale(sx, 1);
                }
            }

            Shape glyph = visibleAt.createTransformedShape(path);
            visibleBBox = glyph.getBounds2D();
        }
        if (visibleBBox == null) {
            visibleBBox = bounds;
        }

        Color color = GraphicsUtil.getColor(getGraphicsState().getNonStrokingColor(), getInitialMatrix());

        int rotate = GraphicsUtil.getAngleInDegrees(at);

        TextElement te = TableUtils.makeTextElement(textBlockId, visibleBBox, bounds,
                font, getGraphicsState().getTextState().getFontSize(),  unicode, new int[]{ code },
                color, this.widthOfSpace(font, textRenderingMatrix), rotate);

        if (this.currentClippingPath().intersects(te)) {
            this.minCharWidth = (float) Math.min(this.minCharWidth, te.getWidth());
            this.minCharHeight = (float) Math.min(this.minCharHeight, te.getHeight());

            // 数据清理后再建立索引
            // this.spatialIndex.add(te);
            this.characters.add(te);
        }

        if (this.isDebugClippingPaths() && !this.clippingPaths.contains(this.currentClippingPath())) {
            this.clippingPaths.add(this.currentClippingPath());
        }
    }

    @Override
    public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
        GraphicsUtil.appendRectangleToPath(currentPath, p0, p1, p2, p3);
        ++rectangeles;
    }

    @Override
    public void clip(int windingRule) {
        // the clipping path will not be updated until the succeeding painting operator is called
        clipWindingRule = windingRule;
    }

    @Override
    public void closePath() {
        currentPath.closePath();
    }

    @Override
    public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {
        currentPath.curveTo(x1, y1, x2, y2, x3, y3);
        ++linesAndCurves;
    }

    @Override
    public void drawImage(PDImage arg0) {

    }

    /*
    @Override
    public void showForm(PDFormXObject form) throws IOException {
        inForm = true;
        rectangeles = 0;
        linesAndCurves = 0;
        text = 0;
        super.showForm(form);
        inForm = false;
        if (rectangeles > 0 && linesAndCurves / rectangeles < 2) {
            // Possibly a table form
            Table table = new Table();
            table.setFrame(currentClippingPath());
            table.form = form;
            pendingForms.add(table);
        }
    }
    */


    // Draw Counters
    private int rectangeles;
    private int linesAndCurves;
    private int text;
    private List<Table> pendingForms = new ArrayList<>();

    List<Rectangle2D> clipAreas = new ArrayList<>();

    @Override
    public void  endPath() {
        if (clipWindingRule != -1) {
            currentPath.setWindingRule(clipWindingRule);
            getGraphicsState().intersectClippingPath(currentPath);
            clipWindingRule = -1;
        }

        TableUtils.PageContext pageContext = TableUtils.PageContext.poolPageContext()
                .setParams(params)
                .setPageNumber(pageNumber)
                .setPageSize(pageWidth, pageHeight)
                .setPagination(isPagination)
                .setHeaderState(isHeader, hasHeaderLine)
                .setFooterState(isFooter, hasFooterLine);

        Rectangle2D clipBounds = currentClippingPath();

        if (TableUtils.acceptsClipBounds(clipBounds, pageContext)) {
            // Only handle rect big enough to hold characters
            // If the rect is too small to hold a text, ignore it

            // TODO 排除“大致上”重合的矩形
            clipAreas.add(clipBounds.getBounds2D());
        }
        TableUtils.PageContext.releasePageContext(pageContext);

        currentPath.reset();
    }

    @Override
    public void fillAndStrokePath(int arg0) {
        strokeOrFillPath(true);
    }

    @Override
    public void fillPath(int arg0) {
        strokeOrFillPath(true);
    }

    @Override
    public Point2D getCurrentPoint() {
        return currentPath.getCurrentPoint();
    }

    @Override
    public void lineTo(float x, float y) {
        currentPath.lineTo(x, y);
        ++linesAndCurves;
    }

    @Override
    public void moveTo(float x, float y) {
        currentPath.moveTo(x, y);
    }

    @Override
    public void shadingFill(COSName arg0) {
        // TODO Auto-generated method stub

    }

    @Override
    public void strokePath() {
        strokeOrFillPath(false);
    }

    private void strokeOrFillPath(boolean isFill) {
        GeneralPath path = this.currentPath;
        path.transform(getPageTransform());
        TableUtils.DrawPathContext drawPathContext = TableUtils.DrawPathContext.poolDrawPathContext()
                .setPath(path)
                .setPathInfos(null)
                .setFill(isFill)
                .setDashed(false)
                .setGraphicState(getGraphicsState(), getInitialMatrix())
                .setClipPath(currentClippingPath())
                .setParams(params)
                .setPageNumber(pageNumber)
                .setPageSize(pageWidth, pageHeight)
                .setPagination(isPagination)
                .setHeaderState(isHeader, hasHeaderLine)
                .setFooterState(isFooter, hasFooterLine);
        List<Ruling> newRulings = TableUtils.pathToRulings(drawPathContext);
        if (paper != drawPathContext.paper) {
            paper = drawPathContext.paper;
        }
        if (drawPathContext.hasHeaderLine != hasHeaderLine) {
            hasHeaderLine = drawPathContext.hasHeaderLine;
        }
        if (drawPathContext.hasFooterLine != hasFooterLine) {
            hasFooterLine = drawPathContext.hasFooterLine;
        }
        TableUtils.DrawPathContext.releaseDrawPathContext(drawPathContext);
        if (newRulings != null && !newRulings.isEmpty()) {
            this.rulings.addAll(newRulings);
        }
        path.reset();
    }

    /*
    private void strokeOrFillPath(boolean isFill) throws IOException {
        GeneralPath path = this.currentPath;

        java.awt.Rectangle bounds = getPageTransform().createTransformedShape(path).getBounds();
        if (isPagination && paper != null) {

            // Probably a pagination separator line
            if (bounds.getWidth() >= pageWidth * 0.7 && bounds.getHeight() <= 2) {
                // Attempts to adjust top/bottom margin
                // TODO 如果页眉页脚通过填充区块背景来区分页面主体内容或者有多条横线，如何确定页眉边线？
                if (isHeader && !hasHeaderLine && bounds.getY() < PaperParameter.A4.topMarginInPt * 1.5) {
                    paper = paper.withMargins(
                            0,
                            (float) bounds.getY(),
                            0,
                            paper.bottomMarginInPt
                    );
                    hasHeaderLine = true;
                } else if (isFooter && !hasFooterLine && (pageHeight - bounds.getY()) < PaperParameter.A4.bottomMarginInPt * 1.5) {
                    paper = paper.withMargins(
                            0,
                            paper.topMarginInPt,
                            0,
                            pageHeight - (float)bounds.getY()
                    );
                    hasFooterLine = true;
                }
            }
        }


        if (isPagination || !this.extractRulingLines) {
            path.reset();
            return;
        }

        boolean tinyWidth = bounds.width <= Ruling.LINE_WIDTH_THRESHOLD;
        boolean tinyHeight = bounds.height <= Ruling.LINE_WIDTH_THRESHOLD;

        Color color = GraphicsUtil.getColor(getGraphicsState().getNonStrokingColor(), this);


        PathIterator pi = path.getPathIterator(this.getPageTransform());
        float[] c = new float[6];
        int currentSegment;

        // skip paths whose first operation is not a MOVETO
        // or contains operations other than LINETO, MOVETO or CLOSE
        if ((pi.currentSegment(c) != PathIterator.SEG_MOVETO)) {
            path.reset();
            return;
        }
        pi.next();
        while (!pi.isDone()) {
            currentSegment = pi.currentSegment(c);
            if (currentSegment != PathIterator.SEG_LINETO
                    && currentSegment != PathIterator.SEG_CLOSE
                    && currentSegment != PathIterator.SEG_MOVETO) {
                path.reset();
                return;
            }
            pi.next();
        }

        // TODO: how to implement color filter?

        // skip the first path operation and save it as the starting position
        float[] first = new float[6];
        pi = path.getPathIterator(this.getPageTransform());
        pi.currentSegment(first);
        // last move
        Point2D.Float start_pos = new Point2D.Float(FloatUtils.round(first[0], 2), FloatUtils.round(first[1], 2));
        Point2D.Float last_move = start_pos;
        Point2D.Float end_pos = null;
        Line2D.Float line;
        PointComparator pc = PointComparator.Y_FIRST;

        pi.next();
        while (!pi.isDone()) {
            currentSegment = pi.currentSegment(c);
            switch (currentSegment) {
                case PathIterator.SEG_LINETO:
                    end_pos = new Point2D.Float(c[0], c[1]);

                    line = pc.compare(start_pos, end_pos) < 0 ? new Line2D.Float(
                            start_pos, end_pos) : new Line2D.Float(end_pos,
                            start_pos);

                    if (line.intersects(this.currentClippingPath())) {
                        Ruling r = new Ruling(line.getP1(), line.getP2())
                                .intersect(this.currentClippingPath());
                        r.setFill(isFill);
                        r.setColor(color);
                        r.setDrawType(tinyWidth, tinyHeight);

                        if (r.length() > com.abcft.pdfextract.core.model.Rectangle.VERTICAL_COMPARISON_THRESHOLD) {
                            this.rulings.add(r);
                        }
                    }
                    break;
                case PathIterator.SEG_MOVETO:
                    last_move = new Point2D.Float(c[0], c[1]);
                    end_pos = last_move;
                    break;
                case PathIterator.SEG_CLOSE:
                    // according to PathIterator docs:
                    // "the preceding subpath should be closed by appending a line
                    // segment
                    // back to the point corresponding to the most recent
                    // SEG_MOVETO."
                    line = pc.compare(end_pos, last_move) < 0 ? new Line2D.Float(
                            end_pos, last_move) : new Line2D.Float(last_move,
                            end_pos);

                    if (line.intersects(this.currentClippingPath())) {
                        Ruling r = new Ruling(line.getP1(), line.getP2())
                                .intersect(this.currentClippingPath());
                        r.setFill(isFill);
                        r.setColor(color);
                        r.setDrawType(tinyWidth, tinyHeight);

                        if (r.length() > Ruling.LINE_LENGTH_THRESHOLD) {
                            this.rulings.add(r);
                        }
                    }
                    break;
            }
            start_pos = end_pos;
            pi.next();
        }
        path.reset();
    }
    */


    private AffineTransform getPageTransform() {
        return this.pageTransform;
    }

    private Rectangle2D currentClippingPath() {
        Shape clippingPath = this.getGraphicsState().getCurrentClippingPath();
        Shape transformedClippingPath = this.getPageTransform().createTransformedShape(clippingPath);

        return transformedClippingPath.getBounds2D();
    }

    private static boolean isPrintable(String s) {
        Character c;
        Character.UnicodeBlock block;
        boolean printable = false;
        for (int i = 0; i < s.length(); i++) {
            c = s.charAt(i);
            block = Character.UnicodeBlock.of(c);
            printable |= !Character.isISOControl(c) && block != null && block != Character.UnicodeBlock.SPECIALS;
        }
        return printable;
    }

    private float widthOfSpace(PDFont font, Matrix textRenderingMatrix) {
        float glyphSpaceToTextSpaceFactor = 1 / 1000f;
        if (font instanceof PDType3Font) {
            glyphSpaceToTextSpaceFactor = font.getFontMatrix().getScaleX();
        }

        float spaceWidthText = 0;
        try {
            // to avoid crash as described in PDFBOX-614, see what the space displacement should be
            spaceWidthText = font.getSpaceWidth() * glyphSpaceToTextSpaceFactor;
        } catch (Throwable exception) {
            LOG.warn(exception.toString());
        }

        if (spaceWidthText == 0) {
            spaceWidthText = font.getAverageFontWidth() * glyphSpaceToTextSpaceFactor;
            // the average space width appears to be higher than necessary so make it smaller
            spaceWidthText *= .80f;
        }
        if (spaceWidthText == 0) {
            spaceWidthText = 1.0f; // if could not find font, use a generic value
        }

        // the space width has to be transformed into display units
        float spaceWidthDisplay = spaceWidthText * textRenderingMatrix.getScalingFactorX();

        return spaceWidthDisplay;
    }

    private boolean isDebugClippingPaths() {
        return debugClippingPaths;
    }

    public void setDebugClippingPaths(boolean debugClippingPaths) {
        this.debugClippingPaths = debugClippingPaths;
    }

    private void onPostProcessPage() {
        if (hasHeaderLine) {
            characters.removeIf(ch -> paper.topMarginContains(ch.y));
        }
        if (hasFooterLine) {
            characters.removeIf(ch -> paper.bottomMarginContains(ch.y));
        }
        NumberUtil.sortByReadingOrder(characters);
        spatialIndex.addAll(characters);
    }
}
