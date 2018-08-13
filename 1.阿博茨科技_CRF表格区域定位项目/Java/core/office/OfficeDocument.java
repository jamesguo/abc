package com.abcft.pdfextract.core.office;

import com.abcft.pdfextract.spi.Meta;
import org.apache.commons.lang3.StringUtils;
import org.apache.poi.POIXMLProperties;
import org.apache.poi.hpsf.DocumentSummaryInformation;
import org.apache.poi.hpsf.SummaryInformation;

import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;


public abstract class OfficeDocument {

    protected final List<Table> tables = new ArrayList<>();
    protected final List<Image> images = new ArrayList<>();
    protected final List<Chart> charts = new ArrayList<>();
    protected final List<Paragraph> paragraphs = new ArrayList<>();
    private final TitleCandidates titleCandidates = new TitleCandidates();
    private int itemIndex = 0;

    protected String pdfPath;

    protected int currentPageIndex;
    protected int currentChartIndex;
    protected int currentTableIndex;

    public List<Table> getTables() {
        return this.tables;
    }

    public List<Image> getImages() {
        return this.images;
    }

    public List<Chart> getCharts() {
        return this.charts;
    }

    public List<Paragraph> getParagraphs() {
        return this.paragraphs;
    }

    protected int getItemIndex() {
        int id = itemIndex;
        itemIndex += 1;
        return id;
    }

    public String getPdfPath() {
        return pdfPath;
    }

    abstract void process();

    protected abstract void tableToParagraphs(Table table);

    protected void addTitleCandidate(String title) {
        this.titleCandidates.addTitle(title);
    }


    protected String getPossibleTitle() {
        return this.titleCandidates.getPossibleTitle();
    }

    protected boolean isPossibleTitle() {
        return titleCandidates.getSize() > 0;
    }

    protected void tableToCharts(Table table) {
        table.getCells()
                .stream()
                .filter(cell -> cell.hasInfo("chart_index"))
                .forEach(cell -> {
                    int row = cell.getRow() - 1;
                    int column = cell.getColumn();
                    if (table.isNormalCell(row, column)) {
                        Table.Cell cell1 = table.getCell(row, column);
                        if (charts.size() > 0) {
                            Chart chart = charts.get((Integer) cell.getInfo("chart_index"));
                            int row1 = cell.getRow() + 1;
                            if (null != cell1) {
                                String title = cell1.getText();
                                Paragraph p = new Paragraph(currentPageIndex, new Rectangle2D.Float(0, 0, 0, 0));
                                p.setItemIndex(chart.getItemIndex() - 1);
                                Paragraph.Text text = new Paragraph.Text(title, new FontInfo("", 12, Color.BLACK, FontInfo.Style.NORMAL.getStyle()));
                                p.addText(text);
                                paragraphs.add(p);
                                if (StringUtils.isNotBlank(title)) {
                                    chart.setTitle(title);
                                }
                            }
                            if (table.isNormalCell(row1, column)) {
                                Table.Cell cell2 = table.getCell(row1, column);
                                if (null != cell2) {
                                    String title = cell2.getText();
                                    Paragraph p = new Paragraph(currentPageIndex, new Rectangle2D.Float(0, 0, 0, 0));
                                    p.setItemIndex(chart.getItemIndex() + 1);
                                    Paragraph.Text text = new Paragraph.Text(title, new FontInfo("", 12, Color.BLACK, FontInfo.Style.NORMAL.getStyle()));
                                    p.addText(text);
                                    paragraphs.add(p);
                                }
                            }

                        }
                    }
                });
    }

    protected void cleanTitleCandidates() {
        titleCandidates.clear();
    }

    protected static final String OFFICE_FILE = "office_pdf_";
    protected static final String SUFFIX_FILE = ".pdf";

    protected static File createOfficePDF() throws IOException {
        return File.createTempFile(OFFICE_FILE, SUFFIX_FILE);
    }

    protected Meta getMeta(POIXMLProperties.CoreProperties coreProps, POIXMLProperties.ExtendedProperties extProps, boolean isPPT) {
        Meta meta = new Meta();
        try {
            meta.author = coreProps.getLastModifiedByUser();
            meta.title = coreProps.getTitle();
            meta.isPPT = isPPT;
            meta.creator = coreProps.getCreator();
            meta.version = Float.parseFloat(extProps.getAppVersion() == null ? "1.0" : extProps.getAppVersion());
            meta.isScanCopy = false;
            meta.pageCount = extProps.getSlides();
            meta.keywords = coreProps.getKeywords();
            meta.creationDate = coreProps.getCreated();
            meta.modificationDate = coreProps.getModified();
            meta.subject = coreProps.getSubject();
        } catch (NumberFormatException e) {
            e.printStackTrace();
        }
        return meta;
    }

    protected Meta getMeta(SummaryInformation coreProps, DocumentSummaryInformation docProps, boolean isPPT) {
        Meta meta = new Meta();
        try {
            meta.author = coreProps.getLastAuthor();
            meta.title = coreProps.getTitle();
            meta.isPPT = isPPT;
            meta.creator = coreProps.getAuthor();
            meta.version = docProps.getApplicationVersion();
            meta.isScanCopy = false;
            meta.pageCount = docProps.getSlideCount();
            meta.keywords = coreProps.getKeywords();
            meta.creationDate = coreProps.getCreateDateTime();
            meta.modificationDate = coreProps.getLastSaveDateTime();
            meta.subject = coreProps.getSubject();
            meta.language = docProps.getLanguage();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return meta;
    }

}
