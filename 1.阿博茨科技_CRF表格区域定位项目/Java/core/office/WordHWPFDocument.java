package com.abcft.pdfextract.core.office;

import com.abcft.pdfextract.spi.ChartType;
import com.abcft.pdfextract.spi.Meta;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.poi.hpsf.DocumentSummaryInformation;
import org.apache.poi.hpsf.SummaryInformation;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.model.PicturesTable;
import org.apache.poi.hwpf.model.StyleDescription;
import org.apache.poi.hwpf.model.StyleSheet;
import org.apache.poi.hwpf.usermodel.*;

import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.util.*;
import java.util.regex.Pattern;

import static com.abcft.pdfextract.core.office.OfficeRegex.HEADER_FOOTERS_PATTERNS;

public class WordHWPFDocument extends WordDocument {
    private static final Logger logger = LogManager.getLogger();

    private HWPFDocument document;
    private Set<Pair<Integer, Integer>> tableRanges = new HashSet<>();
    private PicturesTable picturesTable;
    private StyleSheet styleSheet;
    private Range headerStoryRange;

    WordHWPFDocument(HWPFDocument document) {
        this(document,null);
    }

    WordHWPFDocument(HWPFDocument document, String pdfPath) {
        this.document = document;
        this.pdfPath = pdfPath;
    }

    @Override
    public Meta getMeta() {
        DocumentSummaryInformation docProps = document.getDocumentSummaryInformation();
        SummaryInformation coreProps = document.getSummaryInformation();
        return getMeta(coreProps,docProps,false);
    }

    public void process() {
        if (processed) {
            return;
        }
        processInternal();
        processed = true;
    }

    private void processInternal() {
        this.picturesTable = this.document.getPicturesTable();
        this.styleSheet = this.document.getStyleSheet();
        this.headerStoryRange = this.document.getHeaderStoryRange();
        Range range = this.document.getOverallRange();
        final int numParagraphs = range.numParagraphs();
        int num = -1;
        Stack<Integer> tableLevels = new Stack<>();
        Stack<Integer> tableStarts = new Stack<>();
        int lastTableLevel = 0;
        while (num < numParagraphs - 1) {
            num += 1;
            org.apache.poi.hwpf.usermodel.Paragraph p = range.getParagraph(num);
            // logger.error("{} {} {}", p.getStartOffset(), p.getEndOffset(), p.text().trim());
            if (p.isInTable()) {
                lastTableLevel = p.getTableLevel();
                if (tableLevels.empty()) {
                    tableLevels.push(lastTableLevel);
                    tableStarts.push(p.getStartOffset());
                    continue;
                }

                if (lastTableLevel > tableLevels.peek()) {
                    tableLevels.push(lastTableLevel);
                    tableStarts.push(p.getStartOffset());
                    continue;
                }

                if (lastTableLevel < tableLevels.peek()) {
                    int level = tableLevels.pop();
                    int start = tableStarts.pop();
                    org.apache.poi.hwpf.usermodel.Table wptable = new org.apache.poi.hwpf.usermodel.Table(
                        start,
                        p.getStartOffset(),
                        range, level
                    );
                    try {
                        handleTable(wptable);
                    } catch (Exception e) {
                        logger.error("handleTable ERROR: {}", e);
                        e.printStackTrace();
                    }
                }
                continue;
            }

            if (lastTableLevel == 1 && !tableLevels.empty()) {
                int level = tableLevels.pop();
                int start = tableStarts.pop();
                org.apache.poi.hwpf.usermodel.Table wptable = new org.apache.poi.hwpf.usermodel.Table(
                    start,
                    p.getStartOffset(),
                    range, level
                );
                try {
                    handleTable(wptable);
                } catch (Exception e) {
                    logger.error("handleTable ERROR: {}", e);
                    e.printStackTrace();
                }
            }
            handleParagraph(p);
        }
    }

    private void handleTable(org.apache.poi.hwpf.usermodel.Table wptable) {
        int rows = wptable.numRows();
        if (rows == 0) {
            return;
        }
        int endOffset = wptable.getEndOffset();

        boolean isOverlay = false;
        int start = wptable.getStartOffset();
        int end = wptable.getEndOffset();
        if (this.isOverlayTable(start, end)) {
            isOverlay = true;
        } else {
            tableRanges.add(Pair.of(start, end));
        }

        Table table = new Table();
        table.setTitle(this.getPossibleTitle());
        table.setPageIndex(this.currentPageIndex);
        table.setItemIndex(endOffset);
        table.setIndex(this.getAndIncTableIndex());
        Set<Integer> tableCellEdges = buildTableCellEdgesArray(wptable);
        for (int rowIdx = 0; rowIdx < rows; rowIdx++) {
            TableRow tableRow = wptable.getRow(rowIdx);
            int columns = tableRow.numCells();
            int colIdx = -1;
            int lastColSpan = 1;
            for (int col = 0; col < columns; col++) {
                TableCell tableCell = tableRow.getCell(col);
                if (isOverlay) {
                    int s1 = tableCell.getStartOffset();
                    int e1 = tableCell.getEndOffset();
                    if (this.isOverlayTable(s1, e1)) {
                        continue;
                    }
                }

                boolean isVerticallyMerged = tableCell.isVerticallyMerged();
                boolean isFirstVerticallyMerged = tableCell.isFirstVerticallyMerged();
                colIdx += lastColSpan;
                if (isVerticallyMerged && !isFirstVerticallyMerged) {
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
                int colSpan = getColSpan(tableCell, tableCellEdges);
                lastColSpan = colSpan;
                Table.Cell cell = new Table.Cell(rowIdx, colIdx, 1, colSpan);
                cell.addInfo("originCell", tableCell);
                setCellText(cell, tableCell);
                table.addCell(rowIdx, colIdx, cell);
            }
        }
        postProcess(table);
    }

    protected void postProcess(Table table) {
        table.removeTableId();
        if (isLayoutTable(table)) {
            tableToParagraphs(table);
            return;
        }
        if (table.isAllBlank()) {
            return;
        }
        if (table.shouldBeChart()) {
            tableToCharts(table);
            return;
        }
        if (table.shouldBeParagraph()) {
            tableToParagraphs(table);
            return;
        }
        table.detectTitle();
        this.tables.add(table);
    }

    private boolean isLayoutTable(Table table) {
        if (!this.paragraphs.isEmpty()) {
            return false;
        }
        Table.Cell cell00 = table.getCell(0, 0);
        if (cell00 == null) {
            logger.warn("Empty cell at (0, 0)");
            return false;
        }
        if (cell00.hasInfo("chart_index")) {
            return true;
        } else if (maybeTitle(cell00.getText())) {
            return  true;
        }
        return false;
    }

    private static Set<Integer> buildTableCellEdgesArray(org.apache.poi.hwpf.usermodel.Table table) {
        Set<Integer> edges = new TreeSet<>();
        for (int r = 0; r < table.numRows(); r++) {
            TableRow tableRow = table.getRow(r);
            for (int c = 0; c < tableRow.numCells(); c++) {
                TableCell tableCell = tableRow.getCell(c);
                edges.add(tableCell.getLeftEdge());
                edges.add(tableCell.getLeftEdge() + tableCell.getWidth());
            }
        }
        return edges;
    }

    private static int getColSpan(TableCell tableCell, Set<Integer> tableCellEdges) {
        int colSpan = 0;
        int leftEdge = tableCell.getLeftEdge();
        int rightEdge = tableCell.getWidth() + leftEdge;
        for (int edge : tableCellEdges) {
            if (leftEdge <= edge && rightEdge > edge) {
                colSpan++;
            }
        }
        return colSpan;
    }

    private void setCellText(Table.Cell cell, TableCell tableCell) {
        StringBuilder sb = new StringBuilder();
        int numCharacterRuns = tableCell.numCharacterRuns();
        for (int i = 0; i < numCharacterRuns; i++) {
            CharacterRun r = tableCell.getCharacterRun(i);
            if (picturesTable.hasPicture(r)) {
                Picture picture = picturesTable.extractPicture(r, true);
                int index = handleChart(picture, r.getEndOffset());
                if (index != -1) {
                    cell.addInfo("chart_index", index);
                }
            } else {
                sb.append(getNormalText(r.text()));
            }
        }
        cell.setText(sb.toString());
    }

    protected void tableToParagraphs(Table table) {
        int rowCount = table.getRowCount();
        int columnCount = table.getColumnCount();
        for (int r = 0; r < rowCount; ++r) {
            for (int c = 0; c < columnCount; ++c) {
                Table.Cell cell = table.getCell(r, c);
                if (cell != null && StringUtils.isNotBlank(cell.getText())) {
                    TableCell tableCell = (TableCell) cell.getInfo("originCell");
                    int np = tableCell.numParagraphs();
                    for (int i = 0; i < np; ++i) {
                        handleParagraph(tableCell.getParagraph(i));
                    }
                }
            }
        }
    }



    private int handleChart(Picture picture, int num, String title) {
        // doc charts are pictures
        byte[] data = picture.getContent();
        if (data == null || data.length == 0) {
            logger.warn("Empty Image {}", title);
            return -1;
        }
        String ext = picture.suggestFileExtension().toLowerCase();
        Image image = Image.toImage(data, ext);
        if (image == null) {
            logger.warn("Empty Image {} {}", ext, title);
            return -1;
        }
        if (image.isTooSmall(100,100,2000)) {
            logger.warn("Image isTooSmall {} {}", ext, title);
            return -1;
        }
        Chart chart = new Chart(ChartType.BITMAP_CHART);
        chart.setTitle(StringUtils.isBlank(title) ? this.getPossibleTitle() : title);
        chart.setPageIndex(this.currentPageIndex);
        chart.setItemIndex(num);
        chart.setIndex(this.getAndIncChartIndex());
        chart.setImage(image);
        charts.add(chart);
        return charts.size() - 1;
    }

    private Integer handleChart(Picture picture, int num) {
        return handleChart(picture, num, this.getPossibleTitle());
    }

    private void handleParagraph(org.apache.poi.hwpf.usermodel.Paragraph paragraph) {
        if (isInHeadStory(paragraph)) {
            return;
        }
        StyleDescription description = this.styleSheet.getStyleDescription(paragraph.getStyleIndex());
        if (isHeaderStyle(description.getName())) {
            return;
        }
        // TODO: pageIndex
        Paragraph p = new Paragraph(this.currentPageIndex, new Rectangle2D.Float(0, 0, 0, 0));
        p.setItemIndex(paragraph.getEndOffset());
        int numCharacterRuns = paragraph.numCharacterRuns();
        StringBuilder sb = new StringBuilder();
        for (int j = 0; j < numCharacterRuns; j++) {
            CharacterRun characterRun = paragraph.getCharacterRun(j);
            if (picturesTable.hasPicture(characterRun)) {
                Picture picture = picturesTable.extractPicture(characterRun, true);
                handleChart(picture, characterRun.getEndOffset());
                continue;
            }
            String t = getNormalText(characterRun.text());
            if (!StringUtils.isEmpty(t)) {
                Paragraph.Text text = new Paragraph.Text(t, getCharacterRunFontInfo(characterRun));
                p.addText(text);
                sb.append(t);
            }
        }
        this.addTitleCandidate(sb.toString());
        this.paragraphs.add(p);
    }

    private boolean isOverlayTable(int start, int end) {
        for (Pair<Integer, Integer> range : tableRanges) {
            int s0 = range.getLeft();
            int e0 = range.getRight();
            if (!(start >= e0 || end <= s0)) {
                return true;
            }
        }
        return false;
    }

    private boolean isInHeadStory(org.apache.poi.hwpf.usermodel.Paragraph p) {
        final int start = this.headerStoryRange.getStartOffset();
        final int end = this.headerStoryRange.getEndOffset();
        int s = p.getStartOffset();
        return start <= s && s < end;
    }

    private static FontInfo getCharacterRunFontInfo(CharacterRun characterRun) {
        String fontFamily = characterRun.getFontName();
        float fontSize = characterRun.getFontSize();
        FontInfo fontInfo = new FontInfo(fontFamily, fontSize);
        // TODO: color
        fontInfo.setFontColor(Color.BLACK);
        if (characterRun.isBold()) {
            fontInfo.addFontStyle(FontInfo.Style.BOLD);
        }
        if (characterRun.isItalic()) {
            fontInfo.addFontStyle(FontInfo.Style.ITALIC);
        }
        if (characterRun.getUnderlineCode() > 0) {
            fontInfo.addFontStyle(FontInfo.Style.UNDERLINED);
        }
        if (characterRun.isStrikeThrough() || characterRun.isDoubleStrikeThrough()) {
            fontInfo.addFontStyle(FontInfo.Style.STRIKE_THROUGH);
        }
        return fontInfo;
    }

    private static String getNormalText(String text) {
        if (text.contains("HYPERLINK")) {
            return removeHyperLink(text);
        }
        text = text.replaceAll("\\s*EMBED Equation.*", "");
        text = text.replaceAll("\\s*TOC \\\\h \\\\z \\\\t .*", "");
        text = text.replaceAll("\\s*TIME\\s+\\\\@ \".*\".*", "");
        // TODO: replace with real date ?
        text = text.replaceAll(".*SEQ\\s+(表|图|表格).*\\\\\\* ARABIC.*", "");
        text = text.replaceAll(".*DATE.*MERGEFORMAT.*", "");
        text = text.replaceAll(".*INCLUDEPICTURE.*MERGEFORMAT.*", "");
        text = text.replaceAll(".*DOCPROPERTY.*MERGEFORMAT.*", "");
        text = text.replaceAll(".*SHAPE.*MERGEFORMAT.*", "");
        text = text.replaceAll(".*EMBED MSGraph.Chart.*", "");
        text = text.replaceAll(".*LINK Excel.SheetMacroEnabled.*", "");
        text = text.replaceAll(".*(LINK \\\\l|REF _Toc|TOC \\\\o|PAGE   \\\\*).*", "");
        text = text.replaceAll("[\\u0001|\\u0007|\\u0013|\\u0014|\\u0015|\f|\b]", "");
        text = text.replace('\r', '\n');
        return text;
    }

    private static String removeHyperLink(String text) {
        // TODO set link
        text = text.replaceAll("\\s*HYPERLINK \"mailto:.*\"\\s*", "");
        if (!text.contains("HYPERLINK")) {
            return text;
        }
        return "";
    }



    // header or footer style
    private static boolean isHeaderStyle(String styleName) {
        boolean found = false;
        for (Pattern pattern : HEADER_FOOTERS_PATTERNS) {
            if (pattern.matcher(styleName).matches()) {
                found = true;
                break;
            }
        }
        return found;
    }
}
