package com.abcft.pdfextract.core.office;

import com.abcft.pdfextract.core.chart.ChartCallback;
import com.abcft.pdfextract.core.chart.ChartExtractParameters;
import com.abcft.pdfextract.core.chart.ChartExtractionResult;
import com.abcft.pdfextract.core.content.ContentExtractParameters;
import com.abcft.pdfextract.core.content.ContentExtractorCallback;
import com.abcft.pdfextract.core.content.Fulltext;
import com.abcft.pdfextract.core.content.Page;
import com.abcft.pdfextract.core.model.Tags;
import com.abcft.pdfextract.core.table.Cell;
import com.abcft.pdfextract.core.table.TableCallback;
import com.abcft.pdfextract.core.table.TableExtractParameters;
import com.abcft.pdfextract.core.table.TableExtractionResult;
import com.abcft.pdfextract.spi.ChartType;
import com.abcft.pdfextract.spi.Document;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;


public class OfficeExtractor {

    private static final Logger logger = LogManager.getLogger();

    private static boolean savePdf = false;

    public static <Doc extends OfficeDocument>
    ChartExtractionResult process(Document<Doc> document, ChartExtractParameters parameters, ChartCallback callback) {
        if (callback == null) {
            callback = new ChartCallback() {
                @Override
                public void onExtractionError(Throwable e) {
                }

                @Override
                public void onLoadError(Throwable e) {
                }

                @Override
                public void onFatalError(Throwable e) {
                }

                @Override
                public void onFinished(ChartExtractionResult result) {
                }
            };
        }

        Doc doc = document.getDocument();
        doc.process();
        callback.onStart(doc);
        List<Chart> charts = doc.getCharts();
        List<com.abcft.pdfextract.core.chart.Chart> chartList = new ArrayList<>();
        for (Chart officeChart : charts) {
            com.abcft.pdfextract.core.chart.Chart chart = new com.abcft.pdfextract.core.chart.Chart();
            chart.pageIndex = officeChart.getPageIndex();
            chart.title = officeChart.getTitle();
            chart.setChartIndex(officeChart.getIndex());

            if (officeChart.getType() == ChartType.BITMAP_CHART) {
                chart.type = ChartType.BITMAP_CHART;
                chart.setOfficeChart(officeChart);
            } else {
                chart.data = officeChart.getHighcharts();
                JsonObject titleJson = chart.data.getAsJsonObject("title");
                String title = titleJson.get("text").getAsString();
                if (!StringUtils.isEmpty(title)) {
                    officeChart.setTitle(title);
                    chart.title = title;
                } else {
                    titleJson.addProperty("text", officeChart.getTitle());
                }
                JsonArray series = chart.data.get("series").getAsJsonArray();
                String type = null;

                if (series.size() <= 0) {
                    return null;
                }
                type = series.get(0).getAsJsonObject().get("type").getAsString();
                ChartType chartType01 = ChartType.UNKNOWN_CHART;
                switch (type) {
                    case "area":
                        chartType01 = ChartType.AREA_CHART;
                        break;
                    case "column":
                        chartType01 = ChartType.COLUMN_CHART;
                        break;
                    case "bar":
                        chartType01 = ChartType.BAR_CHART;
                        break;
                    case "line":
                        chartType01 = ChartType.LINE_CHART;
                        break;
                    case "pie":
                        chartType01 = ChartType.PIE_CHART;
                        break;
                }
                // 大于1可能是组合图
                if (series.size() > 1) {
                    for (JsonElement sery : series) {
                        String type1 = sery.getAsJsonObject().get("type").getAsString();
                        // 只要存在type类型不同就是组合图
                        if (!type1.equals(type)) {
                            chartType01 = ChartType.COMBO_CHART;
                            break;
                        }
                    }
                }
                officeChart.setType(chartType01);
                chart.type = chartType01;
                chart.setOfficeChart(officeChart);
                chart.setConfidence(1.0);
            }
            chartList.add(chart);
            callback.onItemExtracted(chart);
        }
        ChartExtractionResult result = new ChartExtractionResult();
        result.addCharts(chartList);
        callback.onFinished(result);
        return result;
    }

    public static <Doc extends OfficeDocument>
    TableExtractionResult process(Document<Doc> document, TableExtractParameters parameters, TableCallback callback) {
        if (callback == null) {
            callback = new TableCallback() {
                @Override
                public void onExtractionError(Throwable e) {
                }

                @Override
                public void onLoadError(Throwable e) {
                }

                @Override
                public void onFatalError(Throwable e) {
                }

                @Override
                public void onFinished(TableExtractionResult result) {
                }
            };
        }

        Doc doc = document.getDocument();
        doc.process();
        callback.onStart(doc);
        TableExtractionResult result = new TableExtractionResult();
        for (Table table : doc.getTables()) {
//            table.correctRowSpan();
            com.abcft.pdfextract.core.table.Table tt = new com.abcft.pdfextract.core.table.Table();
            tt.setPageNumber(table.getPageIndex() + 1);
            tt.setIndex(table.getIndex());
            tt.setTitle(table.getTitle());
            tt.setConfidence(1.0);
            tt.setUnit(table.getUnitTextLine(), table.getUnitSet());
            tt.setCaps(table.getCaps());
            tt.setShoes(table.getShoes());
            int rowCount = table.getRowCount();
            int columnCount = table.getColumnCount();
            for (int r = 0; r < rowCount; ++r) {
                for (int c = 0; c < columnCount; ++c) {
                    Table.Cell cell = table.getCell(r, c);
                    if (table.isMergedCell(r, c)) {
                        continue;
                    }
                    if (cell == null) {
                        cell = new Table.Cell(r, c);
                    }
                    int x = (short) Optional.ofNullable(cell.getInfo("left")).orElse((short) -1);
                    int y = (short) Optional.ofNullable(cell.getInfo("right")).orElse((short) -1);
                    int w = (short) Optional.ofNullable(cell.getInfo("top")).orElse((short) 0);
                    int h = (short) Optional.ofNullable(cell.getInfo("bottom")).orElse((short) 0);
                    Cell cc = new Cell(x, y, w, h);
                    if (cell.getRect() != null) {
                        cc.setRect(cell.getRect());
                    }
                    cc.assignPosition(r, c);
                    int rowSpan = cell.getRowSpan();
                    int colSpan = cell.getColSpan();
                    cc.setRowSpan(rowSpan);
                    cc.setColSpan(colSpan);
                    cc.setText(cell.getText());
                    tt.add(cc, r, c);
                }
            }
            callback.onItemExtracted(tt);
            result.addTable(tt);
        }
        callback.onFinished(result);
        return result;
    }

    public static <Doc extends OfficeDocument>
    Fulltext process(Document<Doc> document, ContentExtractParameters parameters, ContentExtractorCallback callback) {
        if (callback == null) {
            callback = new ContentExtractorCallback() {
                @Override
                public void onExtractionError(Throwable e) {
                }

                @Override
                public void onLoadError(Throwable e) {
                }

                @Override
                public void onFatalError(Throwable e) {
                }

                @Override
                public void onFinished(Fulltext result) {
                }
            };
        }

        Doc doc = document.getDocument();
        doc.process();
        callback.onStart(doc);
        Fulltext fulltext = new Fulltext(document);
        int pageNumber = 0;
        Page page = null;
        List<Chart> charts = doc.getCharts();
        List<Table> tables = doc.getTables();
        List<Paragraph> paragraphs = doc.getParagraphs();
        int chartIdx = 0;
        int tableIdx = 0;
        int paragIdx = 0;
        while (chartIdx >= 0 || tableIdx >= 0 || paragIdx >= 0) {
            if (chartIdx >= charts.size()) {
                chartIdx = -1;
            }
            if (tableIdx >= tables.size()) {
                tableIdx = -1;
            }
            if (paragIdx >= paragraphs.size()) {
                paragIdx = -1;
            }
            int c = chartIdx >= 0 ? charts.get(chartIdx).getItemIndex() : Integer.MAX_VALUE;
            int t = tableIdx >= 0 ? tables.get(tableIdx).getItemIndex() : Integer.MAX_VALUE;
            int p = paragIdx >= 0 ? paragraphs.get(paragIdx).getItemIndex() : Integer.MAX_VALUE;
            int min = Math.min(c, Math.min(t, p));
            if (min == Integer.MAX_VALUE) {
                break;
            }

            int px;
            com.abcft.pdfextract.core.content.Paragraph pp;
            if (min == c) {
                Chart chart = charts.get(chartIdx);
                px = chart.getPageIndex() + 1;
                pp = new com.abcft.pdfextract.core.content.Paragraph(chart);
                chartIdx++;
            } else if (min == t) {
                Table table = tables.get(tableIdx);
                px = table.getPageIndex() + 1;
                pp = new com.abcft.pdfextract.core.content.Paragraph(table);
                tableIdx++;
            } else {
                Paragraph paragraph = paragraphs.get(paragIdx);
                px = paragraph.getPageIndex() + 1;
                pp = Paragraph.toParagraph(pageNumber, paragraph);
                paragIdx++;
            }

            if (px != pageNumber) {
                if (page != null) {
                    fulltext.addPage(page);
                }
                pageNumber = px;
                page = new Page(pageNumber);
            }
            assert page != null;
            assert pp != null;
            page.addParagraph(pp);
        }
        if (page != null) {
            fulltext.addPage(page);
        }
        fulltext.addTag(Tags.OFFICE_SAVE_PDF, doc.getPdfPath());
        callback.onFinished(fulltext);
        return fulltext;
    }

    public static boolean isSavePdf() {
        return savePdf;
    }

    public static void setSavePdf(boolean savePdf) {
        OfficeExtractor.savePdf = savePdf;
    }

    static int i = 0;

    public static void write(String filePath, String str) {
        try {
            i++;
            FileOutputStream fos = new FileOutputStream(String.format("/home/kyin/桌面/tables/%s.html", i));
            fos.write(str.getBytes());
            fos.close();
        } catch (Exception e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

    }
}