package com.abcft.pdfextract.core.content;

import com.abcft.pdfextract.core.ExtractedItem;
import com.abcft.pdfextract.core.chart.Chart;
import com.abcft.pdfextract.core.model.*;
import com.abcft.pdfextract.core.model.Rectangle;
import com.abcft.pdfextract.core.util.GraphicsUtil;
import com.abcft.pdfextract.spi.BaseChart;
import com.abcft.pdfextract.core.table.Table;
import com.abcft.pdfextract.spi.BaseTable;
import com.abcft.pdfextract.spi.ChartType;
import com.abcft.pdfextract.util.FloatUtils;
import com.abcft.pdfextract.util.JsonUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StringEscapeUtils;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination;

import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Represents a paragraph in a document.
 *
 * Created by chzhong on 17-2-24.
 */
public class Paragraph extends Rectangle implements ExtractedItem {

    private boolean visible = true;
    private int pageNumber;
    private String text;
    private String html;
    private Rectangle2D clipBounds;
    private String fontName;
    private float fontSize;
    private int textStyle;
    private int lines;
    private int destPageIndex = -1;
    private PDPageXYZDestination dest;
    private String anchor;
    float y2; // Bottom-Left of first line
    private Paragraph prevSibling;  // 这个是怎么用的？不了解，暂且单纯地重命名避免歧义
    private TextBlock textBlock;
    private TextSplitStrategy textSplitStrategy = TextSplitStrategy.DEFAULT;
    private BaseChart chart;
    private BaseTable table;
    private Chart coverChart = null;
    private Table coverTable = null;
    private OutlineItem outlineItem;

    private boolean isPageHeader;
    private boolean isPageFooter;
    private boolean isPageLeftWing;
    private boolean isPageRightWing;

    private Paragraph prevPageParagraph;
    private Paragraph nextPageParagraph;
    private List<Paragraph> coveredParagraphs;

    private int textGroupIndex;
    private int pid;

    public Paragraph(int pageNumber, String text, float x, float y, float width, float height) {
        super(x, y, width, height);
        this.pageNumber = pageNumber;
        this.text = text;
    }

    public Paragraph(BaseChart chart) {
        this(chart, true);
    }

    public Paragraph(BaseChart chart, boolean visible) {
        if (chart instanceof Chart) {
            this.setRect(((Chart)chart).getArea());
        }
        this.chart = chart;
        this.pageNumber = chart.getPageIndex() + 1;
        this.text = "";
        this.visible = visible;

        this.html = chart.toHtml();
        if (!visible) {
            this.html = this.html.replace("pdf-chart", "pdf-chart hidden");
        }

    }

    public Paragraph(BaseTable table) {
        if (table instanceof Table) {
            this.setRect(((Table)table).getAllCellBounds().withCenterExpand(-3));
        }
        this.pageNumber = table.getPageIndex() + 1;
        this.table = table;
        this.text = "";
        html = table.toHtml();
    }

    public void setTextBlock(TextBlock textBlock) {
        this.textBlock = textBlock;
        this.isPageHeader = textBlock.isPageHeader();
        this.isPageFooter = textBlock.isPageFooter();
        this.isPageLeftWing = textBlock.isPageLeftWing();
        this.isPageRightWing = textBlock.isPageRightWing();
    }

    public TextBlock getTextBlock() {
        return textBlock;
    }

    /**
     * 获取合并的TextBlock, 如果是跨页的段落, 所有的文本都会合并到这个TextBlock里
     * @return
     */
    public TextBlock getMergedTextBlock() {
        if (prevPageParagraph == null && nextPageParagraph == null) {
            return textBlock;
        }
        Paragraph first = this;
        while (first.prevPageParagraph != null) {
            first = first.prevPageParagraph;
        }
        Paragraph p = first;
        TextBlock merged = new TextBlock(first.textBlock.getClasses());
        while (p != null) {
            merged.addElements(p.getTextBlock().getElements());
            p = p.nextPageParagraph;
        }
        return merged;
    }

    Rectangle2D getClipBounds() {
        return clipBounds;
    }

    void setClipBounds(Rectangle2D clipBounds) {
        this.clipBounds = clipBounds;
    }

    public int getPageNumber() {
        return pageNumber;
    }

    public boolean isBlank() {
        return StringUtils.isBlank(text);
    }

    public String getText() {
        return text;
    }

    public String getFontName() {
        return fontName;
    }

    void setFontName(String fontName) {
        this.fontName = fontName;
    }

    public float getFontSize() {
        return fontSize;
    }

    public void setFontSize(float fontSize) {
        this.fontSize = fontSize;
    }

    public int getTextStyle() {
        return textStyle;
    }

    public void setTextStyle(int textStyle) {
        this.textStyle = textStyle;
    }

    public String getHTML() {
        if (html == null) {
            buildHTML();
        }
        return html;
    }

    void setHTML(String html) {
        this.html = html;
    }

    public int getLines() {
        return lines;
    }

    void setLines(int lines) {
        this.lines = lines;
    }

    boolean hasLink() {
        return destPageIndex >= 0 && dest != null;
    }

    private boolean matchLinkTarget(Paragraph paragraph) {
        return !(destPageIndex < 0 || null == dest)
                && paragraph.pageNumber - 1 == destPageIndex
                && FloatUtils.feq(dest.getTop(), paragraph.y, 3.0f);
    }

    Paragraph findLinkTarget(List<Paragraph> paragraphs) {
        for (Paragraph p : paragraphs) {
            if (matchLinkTarget(p)) {
                return p;
            }
        }
        return null;
    }


    boolean nearLinkTarget(Paragraph paragraph) {
        return !(destPageIndex < 0 || null == dest)
                && paragraph.pageNumber - 1 == destPageIndex
                && FloatUtils.fgte(dest.getTop(), paragraph.y, 3.0f)
                && (null == paragraph.prevSibling
                || paragraph.prevSibling.pageNumber == destPageIndex
                || (paragraph.prevSibling.pageNumber - 1 == destPageIndex
                && FloatUtils.fgte(paragraph.prevSibling.y, dest.getTop(), 3.0f)));

    }

    void assignAnchor(Paragraph linkParagraph) {
        String key = generateLinkKey(linkParagraph.destPageIndex, linkParagraph.dest);
        StringBuilder sb = new StringBuilder(html);
        String newAnchor = String.format("<a name='%s'>", key);
        int startTagGTIndex = sb.indexOf(">") + 1;
        int endTagLTIndex = sb.lastIndexOf("<");
        sb.insert(endTagLTIndex, "</a>");
        sb.insert(startTagGTIndex, newAnchor);
        this.html = sb.toString();
        this.anchor = key;
    }

    void assignLink(int pageIndex, PDPageXYZDestination dest) {
        this.destPageIndex = pageIndex;
        this.dest = dest;
        String key = generateLinkKey(pageIndex, dest);
        StringBuilder sb = new StringBuilder(html);
        String newAnchor = String.format("<a href='#%s'>", key);
        int startTagGTIndex = sb.indexOf(">") + 1;
        int endTagLTIndex = sb.lastIndexOf("<");
        sb.insert(endTagLTIndex, "</a>");
        sb.insert(startTagGTIndex, newAnchor);
        this.html = sb.toString().replace(" class='text'", " class='catalog-item'");
    }

    private static String generateLinkKey(int pageIndex, PDPageXYZDestination dest) {
        return String.format("link-%d@%d,%d", pageIndex, dest.getLeft(), dest.getTop());
    }

    void assignOutline(OutlineItem item) {
        this.outlineItem = item;
    }

    @Override
    public String toString() {
        String repr = String.format("%s[page=%d,x=%f,y=%f,width=%f,height=%f,text=%s]",
                getClass().getSimpleName(), pageNumber, x, y, width, height, text);

        StringBuilder builder = new StringBuilder(repr);
        builder.delete(builder.length() - 1, builder.length());
        if (isPageHeader) {
            builder.append("; <Page Header>");
        } else if (isPageFooter) {
            builder.append("; <Page Footer>");
        } else if (isPageLeftWing) {
            builder.append("; <Page LeftWing>");
        } else if (isPageRightWing) {
            builder.append("; <Page RightWing>");
        }
        builder.append(']');

        return builder.toString();
    }

    public void setTextSplitStrategy(TextSplitStrategy textSplitStrategy) {
        this.textSplitStrategy = textSplitStrategy;
    }

    public JsonObject toDocument(boolean detail) {
        JsonObject document = new JsonObject();
        document.addProperty("pid", pid);
        document.addProperty("pageIndex", pageNumber - 1);

        if (outlineItem != null) {
            document.addProperty("is_catalog_item", true);
            document.addProperty("catalog_level", outlineItem.getLevel());
        }
        if (dest != null) {
            JsonObject link = new JsonObject();
            link.addProperty("dstPageIndex", destPageIndex);
            link.addProperty("dstPageX", dest.getLeft());
            link.addProperty("dstPageY", dest.getTop());
            link.addProperty("dst", generateLinkKey(destPageIndex, dest));
            document.add("link", link);
        }

        if (!StringUtils.isEmpty(anchor)) {
            document.addProperty("anchor", anchor);
        }

        if (isCovered()) {
            document.addProperty("hidden", true);

            if (coverChart != null) {
                document.addProperty("cover_chart", coverChart.chartId);
            } else if (coverTable != null) {
                document.addProperty("cover_table", coverTable.tableId);
            }
        }

        if (isMocked()) {
            document.addProperty("mocked", true);

            if (chart != null) {
                document.addProperty("binding", chart.getId());
            } else if (table != null) {
                document.addProperty("binding", table.getId());
            }
        }

        if (!isCovered() && !isMocked()) {
            setParagraphLink(document, "prevPageParagraph", prevPageParagraph);
            setParagraphLink(document, "nextPageParagraph", nextPageParagraph);
        }

        if (detail) {
            JsonObject area = new JsonObject();
            double x = JsonUtil.getAbbrDoubleValue(getX(), 2);
            double y = JsonUtil.getAbbrDoubleValue(getY(), 2);

            if (x > .0) {
                area.addProperty("x", x);
            }
            if (y > .0) {
                area.addProperty("y", y);
            }

            double w = JsonUtil.getAbbrDoubleValue(getWidth(), 2);
            double h = JsonUtil.getAbbrDoubleValue(getHeight(), 2);
            if (w > .0) {
                area.addProperty("w", w);
            }
            if (h > .0) {
                area.addProperty("h", h);
            }

            document.add("area", area);

            if (textBlock != null) {
                // 输出详细的每个文字块的信息
                document.add("texts", textBlock.toJSON(textSplitStrategy));
                if (textBlock.isPageHeader()) {
                    document.addProperty("is_header", true);
                } else if (textBlock.isPageFooter()) {
                    document.addProperty("is_footer", true);
                } else if (textBlock.isPageLeftWing()) {
                    document.addProperty("is_leftWing", true);
                } else if (textBlock.isPageRightWing()) {
                    document.addProperty("is_rightWing", true);
                }
                document.addProperty("main_text", textBlock.isMainText());
            } else {
                JsonArray texts = new JsonArray();
                JsonObject text = new JsonObject();
                text.addProperty("font_size", JsonUtil.getAbbrFloatValue(fontSize, 1));
                if ((textStyle & TextElement.TEXT_STYLE_BOLD) == TextElement.TEXT_STYLE_BOLD) {
                    text.addProperty("bold", true);
                }
                if ((textStyle & TextElement.TEXT_STYLE_ITALIC) == TextElement.TEXT_STYLE_ITALIC) {
                    text.addProperty("italic", true);
                }
                if ((textStyle & TextElement.TEXT_STYLE_UNDERLINE) == TextElement.TEXT_STYLE_UNDERLINE) {
                    text.addProperty("underline", true);
                }
                if ((textStyle & TextElement.TEXT_STYLE_LINE_THROUGH) == TextElement.TEXT_STYLE_LINE_THROUGH) {
                    text.addProperty("line-through", true);
                }
                text.addProperty("text", this.text);
                texts.add(text);
                document.add("texts", texts);
            }
        }
        return document;
    }

    private void setParagraphLink(JsonObject doc, String name, Paragraph node) {
        if (null == node) {
            return;
        }
        if (0 == node.getPid()) {
            return;
        }
        if (node.isCovered() || node.isMocked()) {
            return;
        }
        doc.addProperty(name, node.getPid());
    }

    public BaseChart getChart() {
        return chart;
    }

    public void setChart(BaseChart chart) {
        this.chart = chart;
    }

    public BaseTable getTable() {
        return table;
    }

    public void setTable(BaseTable table) {
        this.table = table;
    }

    public void setCoverChart(Chart coverChart) {
        this.coverChart = coverChart;
    }

    public Chart getCoverChart() {
        return coverChart;
    }

    public void setCoverTable(Table coverTable) {
        this.coverTable = coverTable;
    }

    public Table getCoverTable() {
        return coverTable;
    }

    public void setCoveredParagraphs(List<Paragraph> coveredParagraphs) {
        this.coveredParagraphs = coveredParagraphs;
    }

    public List<Paragraph> getCoveredParagraphs() {
        return coveredParagraphs;
    }

    public boolean isCovered() {
        return coverChart != null || coverTable != null;
    }

    public boolean isMocked() {
        return chart != null || table != null;
    }

    public boolean isPageHeader() {
        return isPageHeader;
    }

    public boolean isPageLeftWing() {
        return isPageLeftWing;
    }

    public boolean isPageRightWing() {
        return isPageRightWing;
    }

    public boolean isPageFooter() {
        return isPageFooter;
    }

    public boolean isPagination() {
        return isPageHeader || isPageFooter
                || isPageLeftWing || isPageRightWing;
    }

    public void setTextGroupIndex(int textGroupIndex) {
        this.textGroupIndex = textGroupIndex;
    }

    public int getTextGroupIndex() {
        return textGroupIndex;
    }

    public int getPid() {
        return pid;
    }

    public void setPid(int pid) {
        this.pid = pid;
    }

    public void setCrossPageNextParagraph(Paragraph nextParagraph) {
        this.nextPageParagraph = nextParagraph;
    }

    public Paragraph getNextPageParagraph() {
        return nextPageParagraph;
    }

    public void setCrossPagePrevParagraph(Paragraph prevParagraph) {
        this.prevPageParagraph = prevParagraph;
        if (prevParagraph != null) {
            this.textBlock = this.textBlock.linkTo(prevParagraph.textBlock);
        } else {
            this.textBlock = this.textBlock.unlink();
        }
    }

    public Paragraph getPrevPageParagraph() {
        return prevPageParagraph;
    }

    private void buildHTML() {
        StringBuilder builder = new StringBuilder();
        TextClasses classes = textBlock.getClasses();
        Map<String, Object> dataAttrs = new HashMap<>();
        if (textBlock.isPageHeader()) {
            classes.addClass(TextClasses.CLASS_PAGE_HEADER);
        } else if (textBlock.isPageFooter()) {
            classes.addClass(TextClasses.CLASS_PAGE_FOOTER);
        } else if (textBlock.isPageLeftWing()) {
            classes.addClass(TextClasses.CLASS_PAGE_LEFT_WING);
        } else if (textBlock.isPageRightWing()) {
            classes.addClass(TextClasses.CLASS_PAGE_RIGHT_WING);
        } else {
            if (textBlock.isMainText()) {
                classes.addClass(TextClasses.CLASS_MAIN_TEXT);
            } else {
                classes.addClass(TextClasses.CLASS_OTHER_TEXT);
            }
        }
        if (prevPageParagraph != null) {
            classes.addClass(TextClasses.CLASS_HIDDEN);
            dataAttrs.put("data-prev-pid", prevPageParagraph.getPid());
        }
        // 隐藏被 Chart、Table 覆盖的文字，但不隐藏位图覆盖的文字
        if (coverChart != null && coverChart.type != ChartType.BITMAP_CHART) {
            classes.addClass(TextClasses.CLASS_HIDDEN);
            dataAttrs.put("data-chart", coverChart.chartId);
        }
        if (coverTable != null) {
            classes.addClass(TextClasses.CLASS_HIDDEN);
            dataAttrs.put("data-table", coverTable.tableId);
        }
        if (outlineItem != null) {
            classes.addClass("catalog-item");
            dataAttrs.put("data-level", outlineItem.getLevel());
        }
        dataAttrs.put("data-page", pageNumber);
        dataAttrs.put("data-pid", pid);
        dataAttrs.put("data-rect", String.format("%d,%d,%d,%d", (int) getMinX(), (int) getMinY(), (int) getWidth(), (int) getHeight()));

        String pClass = classes.getClasses();
        String pData = StringUtils.join(dataAttrs.entrySet().stream()
                .map(entry -> String.format("%s=\"%s\"", entry.getKey(), entry.getValue())).collect(Collectors.toList()), " ");
        builder.append(String.format("<p class=\"%s\" %s>", pClass, pData));
        buildParagraphBodyHTML(textBlock, builder, null);
        Paragraph next = nextPageParagraph;
        while (next != null) {
            buildParagraphBodyHTML(next.getTextBlock(), builder, next);
            next = next.getNextPageParagraph();
        }
        builder.append("</p>");
        // 不显示隐藏的段落
        if (classes.hasClass(TextClasses.CLASS_HIDDEN)) {
            html = "";
        } else {
            html = builder.toString();
        }
    }

    private static void buildParagraphBodyHTML(TextBlock textBlock, StringBuilder builder, Paragraph crossPageParagraph) {
        for (TextChunk textChunk : textBlock.getElements()) {
            List<TextChunk> spans = TextSplitStrategy.BY_STYLE.split(textChunk);
            for (TextChunk span : spans) {
                // 空白也需要输出, 否则间距就没了
                String text = span.getText();
                String curCSS = buildTextCSS(span.getFirstElement(), crossPageParagraph);
                builder.append(String.format("<span %s>", curCSS));
                builder.append(StringEscapeUtils.escapeHtml4(text));
                builder.append("</span>");
            }
        }
    }

    private static String buildTextCSS(TextElement textElement, Paragraph crossPageParagraph) {
        StringBuilder builder = new StringBuilder();
        StringBuilder classes = new StringBuilder();
        if (textElement.isBold() || textElement.isItalic() || textElement.isUnderline() || textElement.isLineThrough()) {
            if (textElement.isBold()) {
                classes.append("bold ");
            }
            if (textElement.isItalic()) {
                classes.append("italic ");
            }
            if (textElement.isUnderline()) {
                classes.append("underline ");
            } else if (textElement.isLineThrough()) {
                classes.append("line-through ");
            }
        }
        if (classes.length() > 0) {
            classes.deleteCharAt(classes.length() - 1); // 删除最后一个空格
            builder.append(" class=\"");
            builder.append(classes);
            builder.append("\"");
        }
        // 这里考虑尽量使用 PDF 中指定的字体大小，除非字体大小太小
        builder.append(" data-fsize=\"").append(textElement.getFontSize()).append("\"");
        Color color = textElement.getColor();
        // 避免白色的字看不到，同时把几乎是黑色（＜#101010）的视为黑色
        if ((color.getRed() > 250 && color.getGreen() > 250 && color.getBlue() > 250)
                || (color.getRed() < 16 && color.getGreen() < 16 && color.getBlue() < 16)) {
            color = Color.BLACK;
        }
        if (!color.equals(Color.BLACK)) {
            builder.append(" data-color=\"").append(GraphicsUtil.color2String(color)).append("\"");
        }
        if (crossPageParagraph != null) {
            builder.append(" data-pid=\"").append(crossPageParagraph.getPid()).append("\"");
        }
        return builder.toString();
    }

}
