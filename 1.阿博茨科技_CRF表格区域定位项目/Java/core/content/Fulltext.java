package com.abcft.pdfextract.core.content;

import com.abcft.pdfextract.core.*;
import com.abcft.pdfextract.core.chart.Chart;
import com.abcft.pdfextract.core.gson.*;
import com.abcft.pdfextract.core.model.Rectangle;
import com.abcft.pdfextract.spi.ChartType;
import com.abcft.pdfextract.core.table.Table;
import com.abcft.pdfextract.spi.Document;
import com.abcft.pdfextract.spi.FileType;
import com.abcft.pdfextract.spi.Meta;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.annotations.SerializedName;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Represents fulltext of a document.
 *
 * Created by chzhong on 17-2-24.
 */
@DataFields(value = {"pages", "outline"}, advanced = true)
@DataFields({"pageCount", "paragraphCount", "characterCount"})
@StatusFields({"state", "deleted"})
@MetaFields({"file_data", "from", "create_time", "last_updated", "text_version"})
public class Fulltext extends ExtractionResult<Page> implements ExtractedItem {

    private static final Logger logger = LogManager.getLogger();

    private final FileType fileType;
    private final List<Page> pages = new ArrayList<>();
    private String fulltext;
    private String html;
    private boolean needBuildPages = true;

    @Summary
    @DataField
    private String title;
    @Summary
    @DataField
    private String author;
    @Summary
    @DataField
    private String keywords;
    private Outline outline;
    private List<Chart> charts;
    private List<Table> tables;
    @Detail
    @DataField(inline = false)
    @SerializedName("html_file")
    public String htmlFile;
    @Detail
    @DataField(inline = false)
    @SerializedName("text_file")
    public String textFile;
    @Detail
    @DataField(inline = false)
    @SerializedName("json_file")
    public String jsonFile;
    @Detail
    @DataField(inline = false)
    @SerializedName("pdf_file")
    public String pdfFile;

    @DataField
    public Meta meta;

    public Fulltext(Document doc) {
        this.fileType = doc.getFileType();
        this.meta = doc.getMeta();
        if (this.meta != null) {
            this.title = this.meta.title;
            this.author = this.meta.author;
            this.keywords = this.meta.keywords;
        } else {
            this.title = null;
            this.author = null;
            this.keywords = null;
        }
    }

    public List<Paragraph> getParagraphs(boolean includeMocked, boolean includeCoverdParagraph) {
        return pages.stream().flatMap(page -> page.getParagraphs(includeMocked, includeCoverdParagraph).stream()).collect(Collectors.toList());
    }

    public int getParagraphCount() {
        return pages.stream().mapToInt(Page::getParagraphCount).sum();
    }

    public int getCharacterCount() {
        if (null == this.fulltext) {
            buildFulltext();
        }
        return fulltext.length();
    }

    public int getPageCount() {
        return pages.size();
    }

    public List<Page> getPages() {
        return pages;
    }

    public Page getPage(int pageIndex) {
        return pages.stream().filter(page -> page.getPageNumber() == pageIndex+1).findFirst().orElse(null);
    }

    void rebuild() {
        this.fulltext = null;
        buildFulltext();
    }

    private void buildFulltext(Outline outline, List<Chart> charts, List<Table> tables) {
        StringBuilder fulltext = new StringBuilder();
        StringBuilder html = new StringBuilder();
        ExtractorFactory extractorFactory = ExtractorFactory.getInstance();
        String versions = extractorFactory.getAlgorithmVersion(fileType);
        html.append("<html>\n" +
                "<head>\n" +
                "<meta http-equiv=\"content-type\" content=\"text/html; charset=utf-8\" />\n" +
                "<meta name=\"viewport\" content=\"initial-scale=1.0,maximum-scale=1.0,minimum-scale=1.0,user-scalable=no\" />\n" +
                "<meta name=\"alg-versions\" content=\"").append(versions).append("\" />\n" +
                "<link rel=\"stylesheet\" href=\"https://cdn.bootcss.com/bootstrap/3.3.7/css/bootstrap.min.css\">\n" +
                "<link href=\"https://cdn.bootcss.com/highcharts/6.0.7/css/highcharts.css\" rel=\"stylesheet\">\n" +
                "<script src=\"https://cdn.bootcss.com/jquery/3.3.1/jquery.min.js\"></script>\n" +
                "<script src=\"https://cdn.bootcss.com/bootstrap/3.3.7/js/bootstrap.min.js\"></script>\n" +
                "<script src=\"https://cdn.bootcss.com/highcharts/6.0.7/highcharts.js\"></script>\n" +
                "<script src=\"https://cdn.bootcss.com/moment.js/2.21.0/moment.min.js\"></script>\n" +
                "<script src=\"https://cdn.bootcss.com/moment-timezone/0.5.14/moment-timezone-with-data.min.js\"></script>\n" +
                "<style>\n" +
                ".pdf-chart {max-width: 500px;}\n" +
                ".pdf-header { text-align: center;\n" +
                "    text-shadow: 1px 1px 2px black; }\n" +
                ".pdf-footer { text-align: center;\n" +
                "    text-shadow: 1px 1px 2px black; }\n" +
                ".pdf-chart {max-width: 500px;}\n" +
                ".pdf-page {margin: 20px; border: 1px dashed #ececec; border-collapse: collapse;}\n" +
                ".table-row-cross-page {font-style: italic}\n" +
                ".table-title { color: #fff;\n" +
                "    background-color: #337ab7; }\n" +
                ".table-desc { background-color: #d9edf7; }\n" +
                ".table-notes { background-color: #d9f7ed; }\n" +
                ".table-caps { font-style: italic; }\n" +
                ".table-unit { text-decoration: dashed underline;}\n" +
                ".table-shoes {font-style: italic;}\n" +
                ".pdf-table {width: auto;margin-bottom: 50px; border: 1px solid #ddd; border-collapse: collapse;}\n" +
                ".pdf-table>tbody>tr:nth-of-type(odd) {background-color: #f9f9f9;}\n" +
                ".pdf-table>tbody>tr>td, .pdf-table>tbody>tr>th, .pdf-table>tfoot>tr>td, .pdf-table>tfoot>tr>th, " +
                ".pdf-table>thead>tr>td, .pdf-table>thead>tr>th {border: 1px solid #ddd; padding: 8px; line-height: 1.5;vertical-align: middle;}\n" +
                ".bold {font-weight: bold;}\n" +
                ".italic {font-style: italic;}\n" +
                ".underline {text-decoration: underline;}\n" +
                ".line-through {text-decoration: line-through;}\n" +
                "p {white-space: pre-wrap;}\n" +
                "</style>\n" +
                "</head>\n" +
                "<body>\n");

        // 如果有需要就重新buildPages
        buildPages();

        /*if (getPages().size() > 5 && outline != null) {
            // 目前的需求不需要直接在HTML输出目录, 暂时屏蔽掉
            html.append(outline.toHTML());
        }*/

        html.append("<div id='pdf-content'>\n");

        for (Page page : pages) {
            html.append("<div class=\"pdf-page\" data-page=\"").append(page.getPageNumber())
                    .append("\" data-rect=\"0,0,").append((int) page.getPageWidth())
                    .append(",").append((int) page.getPageHeight()).append("\" data-use-struct=\"")
                    .append(page.isUseStructInfo()).append("\">\n");
            fulltext.append("\n");
            for (Paragraph paragraph : page.getParagraphs(true, false)) {
                String text = paragraph.getText();
                fulltext.append(text);
                fulltext.append("\n");

                html.append(paragraph.getHTML());
                html.append("\n");
                // office text table and chart
                if (FileType.isOfficeDocument(fileType)) {
                    office2Fulltext(paragraph.getHTML(),fulltext);
                }
                if (paragraph.getCoveredParagraphs() != null) {
                    for (Paragraph coverdP : paragraph.getCoveredParagraphs()) {
                        fulltext.append(coverdP.getText());
                        fulltext.append("\n");
                        html.append(coverdP.getHTML());
                        html.append("\n");
                    }
                }
            }
            html.append("</div>\n");
        }
        html.append("</div>\n");
        html.append("</body></html>");
        this.fulltext = fulltext.toString();
        this.html = html.toString();
    }

    public void buildPages() {
        // 检查是否需要重新buildPages
        if (!needBuildPages) {
            return;
        }

        if (!FileType.isOfficeDocument(fileType)) {
            buildPages(charts, tables, outline);
        }
    }

    private void buildPages(List<Chart> charts, List<Table> tables, Outline outline) {
        pages.sort(Comparator.comparing(Page::getPageNumber));
        // 根据chart的区域删除误识别的table
        Set<Chart> untrustableCharts = new HashSet<>();
        if (tables != null && charts != null) {
            tables.removeIf(table -> {
                for (Chart chart : charts) {
                    if (chart.pageIndex == table.getPageNumber() - 1
                            && chart.getArea().intersects(table)) {
                        if (chart.getConfidence() > 0.5f) {
                            if (table.getConfidence() > 0.5) {
                                return false;
                            }
                            return true;
                        } else {
                            untrustableCharts.add(chart);
                            return false;
                        }
                    }
                }
                return false;
            });
        }
        for (Page page : pages) {
            buildPage(page, charts, tables, untrustableCharts);
        }
        // 设置pid
        int pid = 1;
        for (Page page : pages) {
            for (Paragraph paragraph : page.getParagraphs(true, true)) {
                paragraph.setPid(pid++);
            }
        }

        if (outline != null) {
            if (outline.isBuiltin()) {
                outline.assignOutline(getParagraphs(false, false));
            } else {
                outline.extractOutline(getParagraphs(false, false), getPages(), charts, tables);
            }
        }

        needBuildPages = false;
    }

    private void buildPage(Page page, List<Chart> charts, List<Table> tables, Set<Chart> untrustableCharts) {
        // FIXME 把 POST-EXTRACT 之间的内容放置到合适的位置。
        // POST-EXTRACT START
        List<Paragraph> paragraphsWithChartAndTable = page.getParagraphs(false, true);


        List<Paragraph> newParagraphs = new ArrayList<>();
        if (charts != null && !charts.isEmpty()) {
            charts.stream()
                    .filter(chart -> chart.getPageNumber() == page.getPageNumber())
                    .forEach(chart -> {
                        chart.chartId = String.format("chart_%d_%s", chart.getPageIndex(), chart.getName());
                        Rectangle chartArea = chart.getArea();
                        Rectangle bounds = chartArea.withCenterExpand(5);
                        List<Paragraph> coveredParagraphs = new ArrayList<>();
                        for (Paragraph p : paragraphsWithChartAndTable) {
                            if (p.isPagination()) {
                                continue;
                            }
                            if (p.selfOverlapRatio(chartArea)>0.8) {
                                p.setCoverChart(chart);
                                coveredParagraphs.add(p);
                            }
                        }
                        Paragraph chartParagraph = new Paragraph(chart, !untrustableCharts.contains(chart));
                        chartParagraph.setTextGroupIndex(page.indexOfTextGroup(chartParagraph));
                        if (chart.type != ChartType.BITMAP_CHART) {
                            // 不去除被位图覆盖的段落
                            chartParagraph.setCoveredParagraphs(coveredParagraphs);
                            paragraphsWithChartAndTable.removeAll(coveredParagraphs);
                        }
                        newParagraphs.add(chartParagraph);
                    });
        }
        if (tables != null) {
            tables.stream()
                    .filter(table -> table.getPageNumber() == page.getPageNumber())
                    .forEach(table -> {
                        table.tableId = String.format("table_%d_%d", table.getPageNumber()-1, table.getIndex());
                        List<Paragraph> coveredParagraphs = new ArrayList<>();
                        Rectangle bounds = table.withCenterExpand(5);
                        for (Paragraph p : paragraphsWithChartAndTable) {
                            if (p.isPagination()) {
                                continue;
                            }
                            if (p.selfOverlapRatio(table.getAllCellBounds())>0.6) {
                                p.setCoverTable(table.getHeadTable());
                                coveredParagraphs.add(p);
                            }
                        }
                        // TODO 考虑跨页表格合并的情形
                        Paragraph tableParagraph = new Paragraph(table);
                        tableParagraph.setTextGroupIndex(page.indexOfTextGroup(tableParagraph));
                        tableParagraph.setCoveredParagraphs(coveredParagraphs);
                        paragraphsWithChartAndTable.removeAll(coveredParagraphs);
                        newParagraphs.add(tableParagraph);
                    });
        }

        // 把chart, table插入到合适的位置
        Collections.reverse(newParagraphs); // 倒序依次插入, 这样在插入同一个位置的时候, 顺序可以还原
        for (Paragraph paragraph : newParagraphs) {
            // 考虑分组的信息, 只能在同组内查找插入的位置
            Paragraph best = paragraphsWithChartAndTable.stream()
                    .filter(p-> p.getTextGroupIndex() == paragraph.getTextGroupIndex()
                            && p.getTop() <= paragraph.getTop()
                            && p.horizontallyOverlapRatio(paragraph) > 0.5)
                    .max(Comparator.comparingDouble(Rectangle::getBottom))
                    .orElse(null);
            if (best == null) {
                // 插入到同组内最开始的位置
                int insertIndex = -1;
                for (int i = 0; i < paragraphsWithChartAndTable.size(); i++) {
                    if (paragraphsWithChartAndTable.get(i).getTextGroupIndex() >= paragraph.getTextGroupIndex()) {
                        insertIndex = i;
                        break;
                    }
                }
                if (insertIndex == -1) {
                    insertIndex = paragraphsWithChartAndTable.size();
                }
                paragraphsWithChartAndTable.add(insertIndex, paragraph);
            } else {
                paragraphsWithChartAndTable.add(paragraphsWithChartAndTable.indexOf(best) + 1, paragraph);
            }
        }
        for (int i = 0; i < paragraphsWithChartAndTable.size(); i++) {
            paragraphsWithChartAndTable.get(i).setPid(i + 1);
        }
        page.setParagraphs(paragraphsWithChartAndTable);
        // POST-EXTRACT END
    }

    @Override
    public List<Page> getItems() {
        return pages;
    }

    @Override
    public boolean hasResult() {
        return !pages.isEmpty();
    }

    public JsonObject toDocument() {
        return toDocument(false);
    }

    public String getText() {
        if (null == fulltext) {
            buildFulltext();
        }
        return fulltext;
    }

    public void setFulltext(String fulltext) {
        this.fulltext = fulltext;
    }

    public String getHTML() {
        if (null == html) {
            buildFulltext();
        }
        return html;
    }

    public void setHtml(String html) {
        this.html = html;
    }

    public JsonArray getPageJson() {
        JsonArray pagesJson = new JsonArray();
        for (Page page : pages) {
            pagesJson.add(page.toDocument(true));
        }
        return pagesJson;
    }

    public void merge(Fulltext fulltext) {
        pages.addAll(fulltext.getPages());
    }

    public void addPage(Page page) {
        pages.add(page);
    }

    private void buildFulltext() {
        buildFulltext(outline, charts, tables);
    }

    void setOutline(Outline outline) {
        this.outline = outline;
        needBuildPages = true;
    }

    public Outline getOutline() {
        return outline;
    }


    @Override
    public JsonObject toDocument(boolean detail) {
        JsonObject bson = GsonUtil.toDocument(this, detail);
        bson.addProperty("pageCount", getPageCount());
        bson.addProperty("paragraphCount", getParagraphCount());
        bson.addProperty("characterCount", getCharacterCount());

        if (detail) {
            bson.add("meta", GsonUtil.toDocument(meta));
            bson.add("pages", getPageJson());
            if (outline != null) {
                bson.add("outline", outline.toDocument());
            }
        }

        return bson;
    }

    public List<Chart> getCharts() {
        return this.charts;
    }

    private void setCharts(List<Chart> charts) {
        this.charts = charts != null ? new ArrayList<>(charts) : new ArrayList<>();
        this.charts.removeIf(chart -> chart.isPPT);
    }

    public List<Table> getTables() {
        return tables;
    }

    private void setTables(List<Table> tables) {
        this.tables = tables != null ? new ArrayList<>(tables) : new ArrayList<>();
    }

    public void setExtractContext(ExtractContext extractContext) {
        if (null == extractContext) {
            return;
        }
        setTables(extractContext.getTables());
        setCharts(extractContext.getCharts());
        needBuildPages = true;
    }


    private void office2Fulltext(String htmlString,StringBuilder fulltext){
        StringBuilder sb = new StringBuilder();
        org.jsoup.nodes.Document doc = Jsoup.parse(htmlString);
        // 根据id获取table
        Elements elementTables = doc.getElementsByAttributeValue("class", "pdf-table");
        if(elementTables != null && elementTables.size() > 0){
            for (Element table : elementTables) {
                Elements trs = table.select("tr");
                //遍历该表格内的所有的<tr> <tr/>
                for (int i = 0; i < trs.size(); ++i) {
                    // 获取一个tr
                    Element tr = trs.get(i);
                    // 获取该行的所有td节点
                    Elements tds = tr.select("td");
                    // 选择某一个td节点
                    for (int j = 0; j < tds.size(); ++j) {
                        Element td = tds.get(j);
                        sb.append(td.text()).append("\t\t");
                    }
                    sb.append("\n");
                }
            }
            fulltext.append(sb.toString());
        }else {
            Elements elementCharts = doc.getElementsByAttributeValue("class", "pdf-chart");
            if(elementCharts != null && elementCharts.size() > 0){
                Pattern compile = Pattern.compile("window.chart_\\d_\\d\\s+=\\s+[\\s\\S]+?;");
                Matcher matcher = compile.matcher(htmlString);
                if(matcher.find()){
                    String[] split = matcher.group().split("=");
                    JsonParser parser = new JsonParser();
                    JsonObject jsonObject = parser.parse(split[1].substring(0,split[1].length()-1)).getAsJsonObject();
                    JsonObject jsonTitle = jsonObject.get("title").getAsJsonObject();
                    if(jsonTitle != null && jsonTitle.size() > 0){
                        String title = jsonTitle.get("text").getAsString();
                        fulltext.append(title).append("\n");
                    }
                    JsonArray series = jsonObject.get("series").getAsJsonArray();
                    StringBuilder keys = new StringBuilder();
                    for (JsonElement jsonElement : series) {
                        JsonObject jsonObject1 = jsonElement.getAsJsonObject();
                        if(jsonObject1 != null){
                            JsonElement names = jsonObject1.get("name");
                            JsonElement datas = jsonObject1.get("data");
                            if( null != names && null != datas){
                                String name = names.getAsString();
                                JsonArray data = datas.getAsJsonArray();
                                if(keys.length()==0){
                                    for (JsonElement datum : data) {
                                        String key = datum.getAsJsonArray().get(0).getAsString();
                                        keys.append(key).append("\t\t");
                                    }
                                }
                                fulltext.append(name).append("\n");
                            }
                        }
                    }
                    fulltext.append(keys.toString()).append("\n");
                }
            }
        }
    }

}
