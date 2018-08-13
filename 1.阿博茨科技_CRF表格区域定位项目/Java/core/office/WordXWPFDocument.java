package com.abcft.pdfextract.core.office;

import com.abcft.pdfextract.core.util.GraphicsUtil;
import com.abcft.pdfextract.spi.ChartType;
import com.abcft.pdfextract.spi.Meta;
import com.google.gson.JsonObject;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.poi.POIXMLDocumentPart;
import org.apache.poi.POIXMLProperties;
import org.apache.poi.xddf.usermodel.chart.XDDFChart;
import org.apache.poi.xwpf.usermodel.*;
import org.apache.xmlbeans.XmlCursor;
import org.apache.xmlbeans.XmlException;
import org.openxmlformats.schemas.drawingml.x2006.main.CTGraphicalObjectData;
import org.openxmlformats.schemas.drawingml.x2006.wordprocessingDrawing.CTAnchor;
import org.apache.xmlbeans.XmlObject;
import org.openxmlformats.schemas.drawingml.x2006.wordprocessingDrawing.CTInline;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;
import javax.xml.namespace.QName;
import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.util.*;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class WordXWPFDocument extends WordDocument {
    private static final Logger logger = LogManager.getLogger();

    private XWPFDocument document;
    private XWPFTheme theme;

    WordXWPFDocument(XWPFDocument document) {
        this(document,null);
    }

    WordXWPFDocument(XWPFDocument document, String pdfPath) {
        this.document = document;
        this.processed = false;
        this.theme = XDDFChartUtils.getTheme(document);
        this.pdfPath = pdfPath;
    }

    @Override
    public Meta getMeta() {
        POIXMLProperties properties = document.getProperties();
        POIXMLProperties.CoreProperties coreProps = properties.getCoreProperties();
        POIXMLProperties.ExtendedProperties extProps = properties.getExtendedProperties();
        return getMeta(coreProps,extProps,false);
    }

    @Override
    public void process() {
        if (processed) {
            return;
        }
        processInternal();
        processed = true;
    }

    private void processInternal() {
        List<IBodyElement> elements = this.document.getBodyElements();
        for (IBodyElement bodyElement : elements) {
            handleBodyElement(bodyElement, true);
        }
    }

    private void handleBodyElement(IBodyElement bodyElement, boolean keepParagraph) {
        switch (bodyElement.getElementType()) {
            case TABLE:
                XWPFTable table = (XWPFTable) bodyElement;
                handleTable(table);
                break;
            case PARAGRAPH:
                XWPFParagraph paragraph = (XWPFParagraph) bodyElement;
                if (keepParagraph) {
                    handleParagraph(paragraph);
                } else {
                    paragraph.getRuns().forEach(this::handleXWPFRun);
                }
                break;
            case CONTENTCONTROL:
                handleSTD((AbstractXWPFSDT) bodyElement);
                break;
            default:
                logger.warn("TODO: ElementType {}", bodyElement.getElementType().name());
        }
    }

    private void handleChart(XDDFChart xddfChart) {
        JsonObject chartData = XDDFChartUtils.handleXDDFChart(xddfChart, theme);
        if (chartData != null) {
            Chart chartx = new Chart();
            chartx.setTitle(this.getPossibleTitle());
            chartx.setPageIndex(this.currentPageIndex);
            chartx.setItemIndex(this.getItemIndex());
            chartx.setIndex(this.getAndIncChartIndex());
            chartx.setHighcharts(chartData);
            charts.add(chartx);
        }
    }

    private void handlePictureData(XWPFPictureData xwpfPictureData, Pair<Double, Double> size) {
        byte[] data = xwpfPictureData.getData();
        if (data == null || data.length == 0) {
            logger.warn("Empty Image");
            return;
        }
        String ext = xwpfPictureData.suggestFileExtension().toLowerCase();
        Image image = Image.toImage(data, ext);
        if (image == null) {
            logger.warn("Empty Image {}", ext);
            return;
        }
        if (image.isTooSmall(100, 100, 2000)) {
            logger.warn("Ignore too small image");
            return;
        }
        image.setPageIndex(currentPageIndex);
        if (size != null) {
            image.setSize(size.getLeft(), size.getRight());
        }
        String shapeName = xwpfPictureData.getFileName();
        if (shapeName != null) {
            image.setTitle(shapeName);
        }
        Chart chart = new Chart(ChartType.BITMAP_CHART);
        chart.setTitle(this.getPossibleTitle());
        chart.setPageIndex(this.currentPageIndex);
        chart.setItemIndex(this.getItemIndex());
        chart.setIndex(this.getAndIncChartIndex());
        chart.setImage(image);
        this.charts.add(chart);
    }

    private void handleSTD(AbstractXWPFSDT abstractXWPFSDT) {
        ISDTContent sdtContent = abstractXWPFSDT.getContent();
        if (sdtContent instanceof XWPFSDTContent) {
            XWPFSDTContent xwpfsdtContent = (XWPFSDTContent) sdtContent;
            List<ISDTContents> contents = xwpfsdtContent.getSDTContents();
            for (ISDTContents c : contents) {
                if (c instanceof XWPFParagraph) {
                    handleParagraph((XWPFParagraph) c);
                } else if (c instanceof XWPFTable) {
                    handleTable((XWPFTable) c);
                } else if (c instanceof XWPFSDT) {
                    handleSTD((XWPFSDT) c);
                } else {
                    logger.warn("TODO: ISDTContents {}", c.getClass());
                }
            }
        } else {
            logger.warn("TODO: handleContentControl {}", sdtContent.getClass());
        }
    }

    private void handleTable(XWPFTable xwpfTable) {
        Table table = new Table();
        table.setTitle(this.getPossibleTitle());
        table.setPageIndex(this.currentPageIndex);
        table.setItemIndex(this.getItemIndex());
        table.setIndex(this.getAndIncTableIndex());
        int rows = xwpfTable.getNumberOfRows();
        for (int rowIdx = 0; rowIdx < rows; rowIdx++) {
            XWPFTableRow tableRow = xwpfTable.getRow(rowIdx);
            int columns = tableRow.getTableCells().size();
            int colIdx = -1;
            int lastColSpan = 1;
            for (int col = 0; col < columns; col++) {
                colIdx += lastColSpan;

                XWPFTableCell tableCell = tableRow.getCell(col);
                CTTcPr tcpr = tableCell.getCTTc().getTcPr();
                if (tcpr.getVMerge() != null && !STMerge.RESTART.equals(tcpr.getVMerge().getVal())) {
                    // 行被合并的单元格, 通过这个设置上面Cell的行缩进
                    int lastRowIdx = rowIdx - 1;
                    while (lastRowIdx >= 0) {
                        Table.Cell lastRowCell = table.getCell(lastRowIdx, colIdx);
                        if (lastRowCell == null) {
                            lastRowIdx -= 1;
                            continue;
                        }
                        table.setCellRowSpan(lastRowIdx, colIdx, rowIdx - lastRowIdx + 1);
                        lastColSpan = lastRowCell.getColSpan();
                        break;
                    }
                    continue;
                }
                List<IBodyElement> elements = tableCell.getBodyElements();
                int index = this.getItemIndex();
                for (IBodyElement bodyElement : elements) {
                    // keep index for contents in cell
                    this.getItemIndex();
                    handleBodyElement(bodyElement, false);
                }
                int colSpan = tcpr.getGridSpan() == null ? 1 : tcpr.getGridSpan().getVal().intValue();
                lastColSpan = colSpan;
                Table.Cell cell = new Table.Cell(rowIdx, colIdx, 1, colSpan);
                if (hasImageTag(tableCell.getCTTc())) {
                    if(charts.size() > 0){
                        cell.addInfo("chart_index", charts.size() - 1);
                    }
                }
                String text = tableCell.getTextRecursively();
                text = text.replace('\t', '\n');
                Matcher matcher = OfficeRegex.FOOT_PATTERN.matcher(text);
                if(matcher.find()) {
                    text = text.replaceAll("\\[footnoteRef:\\d]", "")
                            .replaceAll("\\[\\d","[脚注");
                }
                cell.setText(text);
                cell.addInfo("index", index);
                cell.addInfo("originCell", tableCell);
                table.addCell(rowIdx, colIdx, cell);
            }
        }
        postProcess(table);
    }

    @Override
    protected void tableToParagraphs(Table table) {
        int rowCount = table.getRowCount();
        int columnCount = table.getColumnCount();
        for (int r = 0; r < rowCount; ++r) {
            for (int c = 0; c < columnCount; ++c) {
                Table.Cell cell = table.getCell(r, c);
                if (cell != null) {
                    Integer index = (Integer) cell.getInfo("index");
                    XWPFTableCell tableCell = (XWPFTableCell) cell.getInfo("originCell");
                    List<IBodyElement> elements = tableCell.getBodyElements();
                    for (IBodyElement bodyElement : elements) {
                        if (bodyElement.getElementType() == BodyElementType.PARAGRAPH) {
                            ++index;
                            Paragraph p = new Paragraph(this.currentPageIndex, new Rectangle2D.Float(0, 0, 0, 0));
                            p.setItemIndex(index);
                            handleParagraph((XWPFParagraph) bodyElement, p);
                        }
                    }
                }
            }
        }
    }

    private void handleXWPFRun(XWPFRun xwpfRun) {
        List<CTDrawing> drawings = xwpfRun.getCTR().getDrawingList();
        for (CTDrawing drawing : drawings) {
            List<CTAnchor> ctAnchors = drawing.getAnchorList();
            for (CTAnchor anchor : ctAnchors) {
                CTGraphicalObjectData ctGraphicalObjectData = anchor.getGraphic().getGraphicData();
                Pair<Double, Double> size = XDDFChartUtils.getPictureSize(ctGraphicalObjectData);
                POIXMLDocumentPart part = XDDFChartUtils.handleCTGraphicObjectData(ctGraphicalObjectData, this.document);
                handlePOIXMLDocumentPart(part, size);
            }
            List<CTInline> ctInlines = drawing.getInlineList();
            for (CTInline inline : ctInlines) {
                CTGraphicalObjectData ctGraphicalObjectData = inline.getGraphic().getGraphicData();
                Pair<Double, Double> size = XDDFChartUtils.getPictureSize(ctGraphicalObjectData);
                POIXMLDocumentPart part = XDDFChartUtils.handleCTGraphicObjectData(ctGraphicalObjectData, this.document);
                handlePOIXMLDocumentPart(part, size);
            }
        }
        List<XmlObject> xmlObjects = new ArrayList<>();
        xmlObjects.addAll(xwpfRun.getCTR().getObjectList());
        xmlObjects.addAll(xwpfRun.getCTR().getPictList());
        XmlObject[] mc = xwpfRun.getCTR().selectPath("declare namespace mc='http://schemas.openxmlformats.org/markup-compatibility/2006' .//mc:Fallback");
        for (XmlObject xmlObject1:mc){
            XmlObject[] vtextbox = xmlObject1.selectPath("declare namespace mc='http://schemas.openxmlformats.org/markup-compatibility/2006'  declare namespace v=\"urn:schemas-microsoft-com:vml\" .//v:textbox");
            handleVTextboxes(vtextbox);
        }
        for (XmlObject xmlObject : xmlObjects) {
            XmlObject[] vshapes = xmlObject.selectChildren("urn:schemas-microsoft-com:vml", "shape");
            handleVShapes(vshapes);
            XmlObject[] oleObjects = xmlObject.selectChildren("urn:schemas-microsoft-com:office:office", "OLEObject");
            handleOLEObjects(oleObjects);
        }
    }

    private void handleOLEObjects(XmlObject[] oleObjects) {
        if (oleObjects == null) {
            return;
        }
        for (XmlObject ole : oleObjects) {
            logger.warn("TODO <o:OLEObject> getSizeByStyle");
            XmlCursor c = ole.newCursor();
            String rid = c.getAttributeText(new QName("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id"));
            POIXMLDocumentPart part = this.document.getRelationById(rid);
            handlePOIXMLDocumentPart(part, null);
        }
    }

    private void handleVShapes(XmlObject[] vshapes) {
        if (vshapes == null) {
            return;
        }
        // <v:shape style="width:479.04pt;height:341.25pt">
        //   <v:imagedata r:id="..." />
        // </v:shape>
        for (XmlObject vshape : vshapes) {
            XmlCursor c = vshape.newCursor();
            String style = c.getAttributeText(new QName("style"));
            Pair<Double, Double> size = getSizeByStyle(style);
            XmlObject[] vimagedatas = vshape.selectChildren("urn:schemas-microsoft-com:vml", "imagedata");
            handleVImagedatas(vimagedatas, size);
            XmlObject[] vtextboxes = vshape.selectChildren("urn:schemas-microsoft-com:vml", "textbox");
            handleVTextboxes(vtextboxes);
        }
    }

    private void handleVImagedatas(XmlObject[] vimagedatas, Pair<Double, Double> size) {
        if (vimagedatas == null) {
            return;
        }
        for (XmlObject vimagedata : vimagedatas) {
            XmlCursor c = vimagedata.newCursor();
            String rid = c.getAttributeText(new QName("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id"));
            POIXMLDocumentPart part = this.document.getRelationById(rid);
            handlePOIXMLDocumentPart(part, size);
        }
    }

    private void handleVTextboxes(XmlObject[] vtextboxes) {
        if (vtextboxes == null) {
            return;
        }
        for (XmlObject vtextboxe : vtextboxes) {
            XmlObject[] txbxContents = vtextboxe.selectChildren("http://schemas.openxmlformats.org/wordprocessingml/2006/main", "txbxContent");
            if (txbxContents == null) {
                continue;
            }
            for (XmlObject txbxContent : txbxContents) {
                XmlObject[] children = txbxContent.selectPath("child::*");
                for (XmlObject obj : children) {
                    if (obj.schemaType().equals(CTP.type) || obj.schemaType().toString().equals("N=")) {
                        try {
                            CTP ctp = CTP.Factory.parse(obj.xmlText());
                            XWPFParagraph xwpfParagraph = new XWPFParagraph(ctp, this.document);
                            handleParagraph(xwpfParagraph);
                        } catch (XmlException e) {
                            logger.error("handleVTextboxes CTP ERROR", e);
                        }
                    } else if (obj.schemaType().equals(CTTbl.type)) {
                        try {
                            CTTbl ctTbl = CTTbl.Factory.parse(obj.xmlText());
                            XWPFTable xwpfTable = new XWPFTable(ctTbl, this.document);
                            handleTable(xwpfTable);
                        } catch (XmlException e) {
                            logger.error("handleVTextboxes CTTbl ERROR", e);
                        }
                    } else {
                        logger.error("TODO: {}", obj.schemaType().getClass());
                    }
                }
            }
        }
    }

    private void handlePOIXMLDocumentPart(POIXMLDocumentPart part, Pair<Double, Double> size) {
        if (part == null) {
            return;
        }
        if (part instanceof XDDFChart) {
            handleChart((XDDFChart) part);
        } else if (part instanceof XWPFPictureData) {
            handlePictureData((XWPFPictureData) part, size);
        } else {
            logger.warn("TODO: {}", part);
        }
    }

    private void handleParagraph(XWPFParagraph xwpfParagraph) {
        Paragraph p = new Paragraph(currentPageIndex, new Rectangle2D.Float(0, 0, 0, 0));
        p.setItemIndex(this.getItemIndex());
        handleParagraph(xwpfParagraph, p);
    }

    private void handleParagraph(XWPFParagraph xwpfParagraph, Paragraph p) {
        StringBuilder sb = new StringBuilder();
        int defaultFontSize = this.document.getStyles().getDefaultRunStyle().getFontSize();
        String pictureText = xwpfParagraph.getPictureText();
        Paragraph.Text text1 = new Paragraph.Text(pictureText,new FontInfo("",12,Color.BLACK,FontInfo.Style.NORMAL.getStyle()) );
        p.addText(text1);
        for (XWPFRun xwpfRun : xwpfParagraph.getRuns()) {
            handleXWPFRun(xwpfRun);
            String tt = xwpfRun.text();
            Paragraph.Text text = new Paragraph.Text(tt, getXWPFRunFontInfo(xwpfRun, defaultFontSize));
            p.addText(text);
            sb.append(tt);
        }
        this.addTitleCandidate(sb.toString());
        if (!p.getTexts().isEmpty()) {
            this.paragraphs.add(p);
        }
    }

    private static FontInfo getXWPFRunFontInfo(XWPFRun xwpfRun, int defaultFontSize) {
        String fontFamily = xwpfRun.getFontFamily();
        float fontSize = xwpfRun.getFontSize();
        if (fontSize <= 0) {
            fontSize = defaultFontSize;
        }
        if (fontSize <= 0) {
            fontSize = 10;
        }
        FontInfo fontInfo = new FontInfo(fontFamily, fontSize);
        String clr = xwpfRun.getColor();
        if (clr == null) {
            fontInfo.setFontColor(Color.BLACK);
        } else {
            fontInfo.setFontColor(GraphicsUtil.string2Color(clr));
        }
        if (xwpfRun.isBold()) {
            fontInfo.addFontStyle(FontInfo.Style.BOLD);
        }
        if (xwpfRun.isItalic()) {
            fontInfo.addFontStyle(FontInfo.Style.ITALIC);
        }
        if (xwpfRun.getUnderline() != UnderlinePatterns.NONE) {
            fontInfo.addFontStyle(FontInfo.Style.UNDERLINED);
        }
        if (xwpfRun.isStrikeThrough() || xwpfRun.isDoubleStrikeThrough()) {
            fontInfo.addFontStyle(FontInfo.Style.STRIKE_THROUGH);
        }
        return fontInfo;
    }

    private static Pair<Double, Double> getSizeByStyle(String style) {
        final Pattern pattern = Pattern.compile(".*width:([0-9.]*)(pt|in);height:([0-9.]*)(pt|in).*");
        Matcher matcher = pattern.matcher(style);
        if (!matcher.matches()) {
            logger.warn("getSizeByStyle ERROR for style: {}", style);
            return null;
        }
        double w = Double.parseDouble(matcher.group(1));
        double h = Double.parseDouble(matcher.group(3));
        if (StringUtils.equals(matcher.group(2), "in")) {
            w *= 72;
        }
        if (StringUtils.equals(matcher.group(4), "in")) {
            h *= 72;
        }
        return Pair.of(w, h);
    }

    private static boolean hasImageTag(XmlObject xmlObject) {
        XmlObject[] path = xmlObject.selectPath("declare namespace a='http://schemas.openxmlformats.org/drawingml/2006/main' .//a:graphicData");
        if (path != null && path.length > 0) {
            return true;
        }
        path = xmlObject.selectPath("declare namespace w='http://schemas.openxmlformats.org/wordprocessingml/2006/main' .//w:pict");
        if (path != null && path.length > 0) {
            for (XmlObject p : path) {
                {
                    XmlCursor c = p.newCursor();
                    c.toParent();
                    c.toParent();
                    if (c.getObject().schemaType().getName().getLocalPart().equals("CT_Hyperlink")) {
                        continue;
                    }
                }
                XmlObject[] qq = p.selectPath("declare namespace v='urn:schemas-microsoft-com:vml' .//v:imagedata");
                if (qq != null && qq.length > 0) {
                    return true;
                }
            }
        }
        return false;
    }

}
