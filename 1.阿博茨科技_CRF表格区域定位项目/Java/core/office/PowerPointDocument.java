package com.abcft.pdfextract.core.office;

import com.abcft.pdfextract.core.util.NumberUtil;
import com.abcft.pdfextract.spi.ChartType;
import com.abcft.pdfextract.spi.Document;
import com.abcft.pdfextract.spi.FileType;
import com.abcft.pdfextract.spi.Meta;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.poi.POIXMLDocument;
import org.apache.poi.POIXMLDocumentPart;
import org.apache.poi.POIXMLProperties;
import org.apache.poi.hpsf.DocumentSummaryInformation;
import org.apache.poi.hpsf.SummaryInformation;
import org.apache.poi.hpsf.extractor.HPSFPropertiesExtractor;
import org.apache.poi.hslf.usermodel.*;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.sl.draw.DrawFactory;
import org.apache.poi.sl.usermodel.*;
import org.apache.poi.sl.usermodel.Shape;
import org.apache.poi.xddf.usermodel.chart.XDDFChart;
import org.apache.poi.xslf.usermodel.*;
import org.apache.poi.xwpf.usermodel.*;
import org.apache.xmlbeans.XmlObject;
import org.openxmlformats.schemas.drawingml.x2006.main.CTGraphicalObjectData;
import org.openxmlformats.schemas.presentationml.x2006.main.CTGraphicalObjectFrame;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import java.util.List;

public class PowerPointDocument extends OfficeDocument implements Document<PowerPointDocument> {
    private static final Logger logger = LogManager.getLogger();
    private final SlideShow slideShow;
    private final XWPFTheme theme;
    private final Dimension pageSize;
    private boolean processed;

    private List<String> lastTexts = new ArrayList<>();

    public PowerPointDocument(SlideShow slideShow, String pdfPath) {
        this.slideShow = slideShow;
        this.pageSize = this.slideShow.getPageSize();
        this.processed = false;
        this.pdfPath = pdfPath;
        if (slideShow instanceof POIXMLDocument) {
            theme = XDDFChartUtils.getTheme((POIXMLDocument) slideShow);
        } else {
            theme = null;
        }
    }

    public PowerPointDocument(SlideShow slideShow) {
        this(slideShow, null);
    }

    public static PowerPointDocument getInstance(InputStream stream) {
        String fileName = null;
        File file = null;
        try {
            ByteArrayOutputStream baos1 = new ByteArrayOutputStream();
            org.apache.commons.io.IOUtils.copy(stream, baos1);
            byte[] bytes = baos1.toByteArray();
            if (OfficeExtractor.isSavePdf()) {
                file = createOfficePDF();
                fileName = file.getAbsolutePath();
                OfficeConverter.convert(new ByteArrayInputStream(bytes), file, OfficeConverter::convertPPT2PDF);
            }
            SlideShow slideShow = SlideShowFactory.create(new ByteArrayInputStream(bytes));
            return new PowerPointDocument(slideShow, fileName);
        } catch (IOException e) {
            logger.error("ERROR when load PowerPoint Document: {}", e);
            FileUtils.deleteQuietly(file);
            return null;
        }
    }

    @Override
    public FileType getFileType() {
        return FileType.POWERPOINT;
    }

    @Override
    public PowerPointDocument getDocument() {
        return this;
    }

    @Override
    public Meta getMeta() {
        Meta meta = new Meta();
        if (slideShow instanceof XMLSlideShow) {
            XMLSlideShow slideShow = (XMLSlideShow) this.slideShow;
            POIXMLProperties properties = slideShow.getProperties();
            POIXMLProperties.CoreProperties coreProps = properties.getCoreProperties();
            POIXMLProperties.ExtendedProperties extProps = properties.getExtendedProperties();
            meta = getMeta(coreProps, extProps, true);
        } else if (slideShow instanceof HSLFSlideShow) {
            HPSFPropertiesExtractor textExtractor = ((HSLFSlideShow) slideShow).getMetadataTextExtractor();
            SummaryInformation coreProps = textExtractor.getSummaryInformation();
            DocumentSummaryInformation docProps = textExtractor.getDocSummaryInformation();
            meta = getMeta(coreProps, docProps, true);
        }
        return meta;
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

    @Override
    public void process() {
        if (this.slideShow == null || this.processed) {
            return;
        }
        List<Slide> slides = this.slideShow.getSlides();
        for (Slide slide : slides) {
            resetIndex(slide.getSlideNumber() - 1);
            process(slide);
            this.cleanTitleCandidates();
        }
        this.processed = true;
    }

    private void process(Slide slide) {
        List<Shape> shapes = slide.getShapes();
        if (shapes == null) {
            return;
        }
        NumberUtil.sort(shapes, (s1, s2) -> {
            Rectangle2D r1 = s1.getAnchor();
            Rectangle2D r2 = s2.getAnchor();
            if (isOverlay(Pair.of(r1.getMinX(), r1.getMaxX()), Pair.of(r2.getMinX(), r2.getMaxX()))) {
                if (r1.getMaxY() < r2.getMinY()) {
                    return -1;
                } else if (r1.getMinY() > r2.getMaxY()) {
                    return 1;
                }
            }
            if (isOverlay(Pair.of(r1.getMinY(), r1.getMaxY()), Pair.of(r2.getMinY(), r2.getMaxY()))) {
                if (r1.getMaxX() < r2.getMinX()) {
                    return -1;
                } else if (r1.getMinX() > r2.getMaxX()) {
                    return 1;
                }
            }
            // TODO: magic 0.1
            int r = 0;
            if (r1.getWidth() + r2.getWidth() < pageSize.width &&
                    Math.abs(r1.getMinY() - r2.getMinY()) < pageSize.width * 0.1) {
                r = Double.compare(r1.getX(), r2.getX());
            }
            if (r == 0) {
                r = Double.compare(r1.getY(), r2.getY());
            }
            if (r == 0) {
                r = Double.compare(r1.getMaxY(), r2.getMaxY());
            }
            return r;
        });
        for (Shape shape : shapes) {
            if (shape instanceof TextBox) {
                handleShape(slide, (TextBox) shape);
            } else if (shape instanceof Line) {
                handleShape(slide, (HSLFLine) shape);
            } else if (shape instanceof TableShape) {
                handleShape(slide, (TableShape) shape);
            } else if (shape instanceof AutoShape) {
                handleShape(slide, (AutoShape) shape);
            } else if (shape instanceof GroupShape) {
                handleShape(slide, (GroupShape) shape);
            } else if (shape instanceof PictureShape) {
                handleShape(slide, (PictureShape) shape);
            } else if (shape instanceof XSLFGraphicFrame) {
                handleShape(slide, (XSLFGraphicFrame) shape);
            } else if (shape instanceof XSLFConnectorShape) {
                handleShape(slide, (XSLFConnectorShape) shape);
            } else {
                logger.warn("TODO: handle shape {}", shape.getClass());
            }
        }
    }

    private static boolean isOverlay(Pair<Double, Double> p1, Pair<Double, Double> p2) {
        return Math.max(p1.getLeft(), p2.getLeft()) < Math.min(p1.getRight(), p2.getRight());
    }

    private static void handleShape(Slide slide, Line line) {
        // TODO: to decide the title of slide
        logger.info("Line");
    }

    private void handleShape(Slide slide, TextBox textBox) {
        List<TextParagraph> textParagraphs = textBox.getTextParagraphs();
        List<Paragraph> paragraphs = handleTextParagraphs(textParagraphs, textBox.getAnchor());
        this.paragraphs.addAll(paragraphs);
    }

    private void handleShape(Slide slide, AutoShape autoShape) {
        if (autoShape instanceof TextBox) {
            handleShape(slide, (TextBox) autoShape);
        } else if (autoShape instanceof FreeformShape) {
            handleShape(slide, (FreeformShape) autoShape);
        } else {
            List<TextParagraph> textParagraphs = autoShape.getTextParagraphs();
            if (autoShape.getAnchor() == null) return;
            List<Paragraph> paragraphs = handleTextParagraphs(textParagraphs, autoShape.getAnchor());
            this.paragraphs.addAll(paragraphs);
        }
    }

    private void handleShape(Slide slide, FreeformShape freeformShape) {
        addBiChart(freeformShape);
    }

    private void handleShape(Slide slide, GroupShape groupShape) {
        // Render to image
        if (groupShape instanceof XSLFGroupShape) {
            handleShape(slide, (XSLFGroupShape) groupShape);
        } else if (groupShape instanceof HSLFGroupShape) {
            handleShape(slide, (HSLFGroupShape) groupShape);
        }
        addBiChart(groupShape);

    }

    private void handleShape(Slide slide, XSLFGroupShape groupShape) {
        List<XSLFShape> shapes = groupShape.getShapes();
        List<XSLFTextBox> textBoxes = new ArrayList<>();
        for (XSLFShape c : shapes) {
            if (c instanceof XSLFTextBox) {
                textBoxes.add((XSLFTextBox) c);
            }
        }
        if (1 <= textBoxes.size() * 2 / shapes.size()) {
            for (XSLFTextBox textBox : textBoxes) {
                handleShape(slide, textBox);
            }
        }
        XmlObject[] xmlObjects = groupShape.getXmlObject().selectPath("declare namespace a='http://schemas.openxmlformats.org/drawingml/2006/main' declare namespace p='http://schemas.openxmlformats.org/presentationml/2006/main' .//a:graphicData");
        for (XmlObject xmlObject : xmlObjects) {
            CTGraphicalObjectData graphicData = (CTGraphicalObjectData) xmlObject;
            POIXMLDocumentPart part = XDDFChartUtils.handleCTGraphicObjectData(graphicData, ((XSLFGroupShape) groupShape).getSheet());
            if (part == null) {
                logger.warn("Empty part {}", graphicData.getUri());
                return;
            }
            if (part instanceof XDDFChart) {
                JsonObject chartData = XDDFChartUtils.handleXDDFChart((XDDFChart) part, theme);
                if (chartData != null) {
                    Chart chart = new Chart();
                    chart.setPageIndex(this.currentPageIndex);
                    chart.setItemIndex(this.getItemIndex());
                    chart.setIndex(this.getAndIncChartIndex());
                    chart.setTitle(this.getTitle(groupShape));
                    chart.setHighcharts(chartData);
                    charts.add(chart);
                }
            }
        }
    }

    private void handleShape(Slide slide, HSLFGroupShape groupShape) {
        List<HSLFShape> shapes = groupShape.getShapes();
        List<HSLFTextBox> textBoxes = new ArrayList<>();
        for (HSLFShape c : shapes) {
            if (c instanceof HSLFTextBox) {
                textBoxes.add((HSLFTextBox) c);
            }
        }
        if (1 <= textBoxes.size() * 2 / shapes.size()) {
            for (HSLFTextBox textBox : textBoxes) {
                handleShape(slide, textBox);
            }
        }
    }

    private void addBiChart(Shape groupShape) {
        BufferedImage img = renderShapeToImage(groupShape);
        if (img == null) {
            return;
        }
        Rectangle2D anchor = groupShape.getAnchor();
        Image image = new Image(img);
        image.setSize(anchor.getWidth(), anchor.getHeight());
        Chart chart = new Chart(ChartType.BITMAP_CHART);
        chart.setPageIndex(this.currentPageIndex);
        chart.setItemIndex(this.getItemIndex());
        chart.setIndex(this.getAndIncChartIndex());
        chart.setTitle(this.getTitle(groupShape));
        chart.setImage(image);
        this.charts.add(chart);
    }

    private void handleShape(Slide slide, PictureShape pictureShape) {
        PictureData pictureData = pictureShape.getPictureData();
        handlePictureData(pictureData, pictureShape);
    }

    private void handlePictureData(PictureData pictureData, Shape shape) {
        if (pictureData == null) {
            return;
        }
        byte[] data = pictureData.getData();
        if (data == null) {
            logger.warn("Empty Image");
            return;
        }
        Image image = Image.toImage(data, Optional.ofNullable(pictureData.getType()).orElse(PictureData.PictureType.PNG).extension);
        if (image == null) {
            return;
        }
        if (image.isTooSmall(200, 200, 2500)) {
            logger.warn("Ignore too small image");
            return;
        }

        Rectangle2D anchor = shape.getAnchor();
        image.setSize(anchor.getWidth(), anchor.getHeight());
        image.setTitle(this.getTitle(shape));

        Chart chart = new Chart(ChartType.BITMAP_CHART);
        chart.setPageIndex(this.currentPageIndex);
        chart.setItemIndex(this.getItemIndex());
        chart.setIndex(this.getAndIncChartIndex());
        chart.setImage(image);
        chart.setTitle(image.getTitle());
        this.charts.add(chart);
    }

    private void handleShape(Slide slide, XSLFGraphicFrame graphicFrame) {
        CTGraphicalObjectData graphicData = ((CTGraphicalObjectFrame) graphicFrame.getXmlObject()).getGraphic().getGraphicData();
        POIXMLDocumentPart part = XDDFChartUtils.handleCTGraphicObjectData(graphicData, graphicFrame.getSheet());
        if (part == null) {
            if (graphicFrame instanceof XSLFObjectShape) {
                String id = ((XSLFObjectShape) graphicFrame).getCTOleObject().getId();
                if (StringUtils.isNotEmpty(id)) {
                    String rId = id.replace("rId", "");
                    try {
                        int i = Integer.parseInt(rId);
                        rId = "rId" + (i + 1);
                        POIXMLDocumentPart part1 = ((XSLFSlide) slide).getRelationById(rId);
                        if (part1 != null) {
                            PackagePart packagePart = part1.getPackagePart();
                            XSLFPictureData pictureData = new XSLFPictureData(packagePart);
                            handlePictureData(pictureData, graphicFrame);
                        }
                    } catch (NumberFormatException e) {
                        e.printStackTrace();
                    }
                } else {
                    logger.warn("Empty part {}", graphicData.getUri());
                }
            } else {
                logger.warn("Empty part {}", graphicData.getUri());
            }
            return;
        }
        if (part instanceof XDDFChart) {
            JsonObject chartData = XDDFChartUtils.handleXDDFChart((XDDFChart) part, theme);
            List<POIXMLDocumentPart.RelationPart> relationParts = part.getRelationParts();
            if (chartData != null) {
                Chart chart = new Chart();
                chart.setPageIndex(this.currentPageIndex);
                chart.setItemIndex(this.getItemIndex());
                chart.setIndex(this.getAndIncChartIndex());
                JsonElement jsonElement = chartData.get("title");
                String title = null;
                if (jsonElement != null) {
                    title = jsonElement.getAsJsonObject().get("text").getAsString();
                }
                if (StringUtils.isEmpty(title)) {
                    title = this.getTitle(graphicFrame);
                }
                chart.setTitle(title);
                chart.setHighcharts(chartData);
                charts.add(chart);
            }
        } else if (part instanceof XSLFObjectData) {
            try {
                PictureData pictureData = ((XSLFObjectShape) graphicFrame).getPictureData();
                handlePictureData(pictureData, graphicFrame);
            } catch (Exception e) {
                logger.warn("TODO XSLFObjectData has no picture");
                XSLFPictureData xslfPictureData = new XSLFPictureData(part.getPackagePart());
                handlePictureData(xslfPictureData, graphicFrame);
            }
        } else {
            logger.warn("TODO: {}", part.getClass());
        }
    }

    private void handleShape(Slide slide, XSLFConnectorShape connectorShape) {
        // TODO: what is this?
        logger.info("XSLFConnectorShape: {}", connectorShape.getShapeId());
    }

    private void handleShape(Slide slide, TableShape tableShape) {
        Table table = new Table();
        table.setPageIndex(this.currentPageIndex);
        table.setItemIndex(this.getItemIndex());
        table.setIndex(this.getAndIncTableIndex());
        table.setTitle(this.getTitle(tableShape));

        int columns = tableShape.getNumberOfColumns();
        int rows = tableShape.getNumberOfRows();
        for (int rowIdx = 0; rowIdx < rows; rowIdx++) {
            for (int colIdx = 0; colIdx < columns; colIdx++) {
                TableCell tableCell = tableShape.getCell(rowIdx, colIdx);
                // 跳过被合并的单元格
                if (tableCell == null || tableCell.isMerged()) {
                    continue;
                }
                int rowSpan = tableCell.getRowSpan();
                int colSpan = tableCell.getGridSpan();
                Table.Cell cell = new Table.Cell(rowIdx, colIdx, rowSpan, colSpan);
                cell.setRect(tableCell.getAnchor());
                cell.setText(tableCell.getText());
                cell.addInfo("originCell", tableCell);
                table.addCell(rowIdx, colIdx, cell);
            }
        }
        postProcess(table);
    }

    protected void postProcess(Table table) {
        List<Table.Cell> cells = table.getCells();
        Optional<Table.Cell> signCell = cells.stream().filter(cell -> StringUtils.contains(cell.getText(), "资料来源")).findAny();
        /**
         * 判断这个table包不包含chart
         */
        int rowCount = table.getRowCount();
        int colCount = table.getColumnCount();
        int notBlankCells = table.getNotBlankCells();
        Optional<Integer> reduce = cells.stream().map(cell -> {
            int rowSpan = cell.getRowSpan();
            int colSpan = cell.getColSpan();
            return rowSpan * colSpan - 1;
        }).reduce((x, y) -> x + y);
        int count = 0;
        if (reduce.isPresent()) {
            count = reduce.get();
        }
        boolean flag = notBlankCells - 2 * (rowCount * colCount - count) / 3 <= 0;
        if (flag && signCell.isPresent()) {
            double height = signCell.get().getRect().getHeight();
            height = height > 60 ? 30 : height;
            int col = table.getColumnCount();
            int row = table.getRowCount();
            for (int i = 0; i <= row; i++) {
                for (int j = 0; j < col; j++) {
                    Table.Cell cell = table.getCell(i, j);
                    if (cell == null) continue;
                    Rectangle2D rect = cell.getRect();
                    if (null == rect) continue;
                    double height1 = rect.getHeight();
                    if (height1 >= height * 6) {
                        cell.addInfo("chart_index", charts.size() - 1);
                    }

                }
            }
        }
        Table.Cell cell1 = table.getCell(0, 0);
        if (StringUtils.isNotEmpty(cell1.getText())) {
            String[] split = cell1.getText().split("\\s{20,}");
            int columnCount = table.getColumnCount() - 1;
            Table.Cell cell = table.getCell(0, columnCount);
            if (null == cell && split.length == 2 && columnCount > 0) {
                Table.Cell c = new Table.Cell(0, columnCount);
                c.setText(split[1]);
                table.addCell(0, columnCount, c);
                cell1.setText(split[0]);
                cell1.setColSpan(1);
                table.addCell(0, 0, cell1);
            }
        }
        table.removeTableId();
        if (table.isAllBlank()) {
            return;
        }
        if (table.shouldBeChart()) {
            table.getCells().stream()
                    .filter(cell -> 0 == cell.getRow())
                    .filter(cell -> StringUtils.isNotBlank(cell.getText()))
                    .sorted(Comparator.comparingInt(Table.Cell::getColumn))
                    .map(Table.Cell::getText)
                    .forEach(lastTexts::add);
            return;
        }
        if (table.shouldBeParagraph()) {
            tableToParagraphs(table);
            return;
        }
        table.detectTitle();
        tables.add(table);
    }

    @Override
    protected void tableToParagraphs(Table table) {
        int rowCount = table.getRowCount();
        int columnCount = table.getColumnCount();
        for (int r = 0; r < rowCount; ++r) {
            for (int c = 0; c < columnCount; ++c) {
                Table.Cell cell = table.getCell(r, c);
                if (cell != null) {
                    TableCell tableCell = (TableCell) cell.getInfo("originCell");
                    List<TextParagraph> textParagraphs = tableCell.getTextParagraphs();
                    List<Paragraph> paragraphs = handleTextParagraphs(textParagraphs, tableCell.getAnchor());
                    this.paragraphs.addAll(paragraphs);
                }
            }
        }
    }

    private List<Paragraph> handleTextParagraphs(List<TextParagraph> textParagraphs, Rectangle2D area) {
        List<Paragraph> paragraphs = new ArrayList<>();
        List<Paragraph.Text> texts = new ArrayList<>();
        float shapeWidth = (float) area.getWidth();
        boolean needMerge = true;
        FontInfo lastFontInfo = null;
        for (TextParagraph textParagraph : textParagraphs) {
            List<TextRun> textRuns = textParagraph.getTextRuns();
            StringBuilder sb = new StringBuilder();
            FontInfo runFontInfo = null;
            float textWidth = 0;
            for (TextRun textRun : textRuns) {
                if (textRun.getFieldType() == TextRun.FieldType.SLIDE_NUMBER) {
                    continue;
                }
                if (runFontInfo == null) {
                    runFontInfo = getTextRunFontInfo(textRun);
                }

                String text = textRun.getRawText();
                sb.append(text);
                if (StringUtils.isNotBlank(text)) {
                    double x = Optional.ofNullable(textRun.getFontSize()).orElse(20d) * text.length();
                    if (StringUtils.isAsciiPrintable(text)) {
                        x *= 0.6;
                    }
                    textWidth += x;
                }
            }
            if (runFontInfo == null) {
                lastFontInfo = null;
                continue;
            }
            if (lastFontInfo != null && !lastFontInfo.equals(runFontInfo)) {
                needMerge = false;
            }
            lastFontInfo = runFontInfo;

            if (Math.abs(shapeWidth - textWidth) > runFontInfo.getFontSize() * 1.5) {
                needMerge = false;
            }

            String t = sb.toString().replaceAll("\\*", "");
            if (t.length() == 0 || StringUtils.isBlank(t.substring(t.length() - 1))) {
                needMerge = false;
            }
            String[] split = t.trim().replace("。", "").split("\\s{6,}");
            List<String> list = Arrays.asList(split);
            Collections.reverse(list);
            for (String str : list) {
                this.addTitleCandidate(str);
            }
            Paragraph.Text text = new Paragraph.Text(t, runFontInfo);
            texts.add(text);
            if (!needMerge) {
                if (!texts.isEmpty()) {
                    Paragraph paragraph = new Paragraph(this.currentPageIndex, area);
                    paragraph.setItemIndex(this.getItemIndex());
                    paragraph.addText(texts);
                    paragraphs.add(paragraph);
                }
                texts = new ArrayList<>();
            }
        }
        if (!texts.isEmpty()) {
            Paragraph paragraph = new Paragraph(this.currentPageIndex, area);
            paragraph.setItemIndex(this.getItemIndex());
            paragraph.addText(texts);
            paragraphs.add(paragraph);
        }
        return paragraphs;
    }

    @Nonnull
    private String getTitle(Shape shape) {
        if (this.lastTexts.size() > 0 && this.lastTexts.get(0).length() < 40) {
            String title = lastTexts.get(0);
            Paragraph p = new Paragraph(currentPageIndex, new Rectangle2D.Float(0, 0, 0, 0));
            p.setItemIndex(this.getItemIndex());
            Paragraph.Text text = new Paragraph.Text(title, new FontInfo("", 12, Color.BLUE, FontInfo.Style.BOLD.getStyle()));
            p.addText(text);
            paragraphs.add(p);
            lastTexts.remove(0);
            return title;
        } else if (this.isPossibleTitle()) {
            return this.getPossibleTitle();
        } else {
            String shapeName = getShapeName(shape);
            if (shapeName == null) {
                shapeName = "";
            }
            return shapeName;
        }
    }

    private static FontInfo getTextRunFontInfo(TextRun textRun) {
        String fontFamily = textRun.getFontFamily();
        float fontSize = Optional.ofNullable(textRun.getFontSize()).orElse(20d).floatValue();
        FontInfo fontInfo = new FontInfo(fontFamily, fontSize);
        Color fontColor = Color.BLACK;
        PaintStyle paintStyle = textRun.getFontColor();
        if (paintStyle instanceof PaintStyle.SolidPaint) {
            fontColor = ((PaintStyle.SolidPaint) paintStyle).getSolidColor().getColor();
        } else if (paintStyle instanceof PaintStyle.GradientPaint) {
            fontColor = ((PaintStyle.GradientPaint) paintStyle).getGradientColors()[0].getColor();
        }
        fontInfo.setFontColor(fontColor);
        if (textRun.isBold()) {
            fontInfo.addFontStyle(FontInfo.Style.BOLD);
        }
        if (textRun.isItalic()) {
            fontInfo.addFontStyle(FontInfo.Style.ITALIC);
        }
        if (textRun.isUnderlined()) {
            fontInfo.addFontStyle(FontInfo.Style.UNDERLINED);
        }
        if (textRun.isStrikethrough()) {
            fontInfo.addFontStyle(FontInfo.Style.STRIKE_THROUGH);
        }
        return fontInfo;
    }

    private static BufferedImage renderShapeToImage(Shape shape) {
        BufferedImage image = null;
        try {
            image = _renderShapeToImage(shape);
        } catch (Exception e) {
            logger.error("renderShapeToImage ERROR", e);
        }
        return image;
    }

    private static BufferedImage _renderShapeToImage(Shape shape) {
        // TODO: there maybe some render issues
        Rectangle2D rect = shape.getAnchor().getBounds2D();
        // ignore if too small
        if (rect.getWidth() < 100 || rect.getHeight() < 100) {
            return null;
        }
        BufferedImage img = new BufferedImage((int) rect.getWidth(), (int) rect.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = img.createGraphics();
        DrawFactory.getInstance(graphics).fixFonts(graphics);
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        graphics.setPaint(Color.white);
        graphics.fill(new Rectangle2D.Double(0, 0, rect.getWidth(), rect.getHeight()));
        try {
            shape.draw(graphics, new Rectangle2D.Double(0, 0, rect.getWidth(), rect.getHeight()));
        } catch (Exception e) {
            e.printStackTrace();
        }
        return img;
    }

    private static int getShapeId(Shape shape) {
        if (shape instanceof XSLFShape) {
            return ((XSLFShape) shape).getShapeId();
        } else if (shape instanceof HSLFShape) {
            return ((HSLFShape) shape).getShapeId();
        } else {
            return -1;
        }
    }

    @Nullable
    private static String getShapeName(Shape shape) {
        if (shape instanceof XSLFShape) {
            return ((XSLFShape) shape).getShapeName();
        } else if (shape instanceof HSLFShape) {
            return ((HSLFShape) shape).getShapeName();
        } else {
            return null;
        }
    }

    public static void main(String[] args) throws FileNotFoundException {
        PowerPointDocument powerPointDocument = PowerPointDocument.getInstance(new FileInputStream(args[0]));
        powerPointDocument.process();
    }

}
