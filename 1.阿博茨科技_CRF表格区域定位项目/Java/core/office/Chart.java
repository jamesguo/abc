package com.abcft.pdfextract.core.office;

import com.abcft.pdfextract.spi.BaseChart;
import com.abcft.pdfextract.spi.ChartType;
import com.abcft.pdfextract.util.JsonUtil;
import com.google.gson.JsonObject;
import org.apache.commons.lang3.tuple.Pair;

import java.util.Objects;

public class Chart implements BaseChart {

    private Image image;
    private ChartType type;
    private int pageIndex;
    private int index;
    private int itemIndex;
    private String title;
    private JsonObject highcharts;

    Chart() {
        this(ChartType.UNKNOWN_CHART);
    }

    Chart(ChartType chartType) {
        type = chartType;
    }

    @Override
    public String toString() {
        return String.format("[Chart (type=%s) %s]", type.name(), title);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Chart chart = (Chart) o;
        return pageIndex == chart.pageIndex &&
                index == chart.index &&
                Objects.equals(image, chart.image) &&
                Objects.equals(title, chart.title) &&
                Objects.equals(highcharts, chart.highcharts);
    }

    @Override
    public int hashCode() {
        return Objects.hash(image, pageIndex, index, title, highcharts);
    }

    @Override
    public ChartType getType() {
        return type;
    }

    @Override
    public int getPageIndex() {
        return this.pageIndex;
    }

    @Override
    public int getIndex() {
        return this.index;
    }

    @Override
    public String getId() {
        return String.format("chart_%d_%d", pageIndex, index);
    }

    @Override
    public String getTitle() {
        return this.title;
    }

    @Override
    public JsonObject getHighcharts() {
        return this.highcharts;
    }

    @Override
    public String toHtml() {
        if (this.type == ChartType.BITMAP_CHART) {
            String src = this.getImage().getImageFile();
            String attr = String.format("id=\"%s\" class=\"pdf-bitmap-chart\" data-page=\"%d\" src=\"%s\"",
                    this.getId(), this.pageIndex + 1, src);
            Pair<Integer, Integer> size = this.getImage().getSize();
            if (size != null) {
                StringBuilder sb = new StringBuilder();
                int w = size.getLeft();
                int h = size.getRight();
                if (w > 0) {
                    sb.append("width:");
                    sb.append(w);
                    sb.append("px;");
                }
                if (h > 0) {
                    sb.append("height:");
                    sb.append(h);
                    sb.append("px;");
                }
                String wh = sb.toString();
                if (!wh.isEmpty()) {
                    attr += String.format(" style=\"%s\"", wh);
                }
            }
            return String.format("<img %s></img>%n", attr);
        } else {
            StringBuilder builder = new StringBuilder();
            builder.append("<div id=\"").append(this.getId()).append("\" class=\"pdf-chart\" data-page=\"")
                    .append(this.pageIndex + 1).append("\"></div>")
                    .append("<script type=\"text/javascript\">%n")
                    .append("window.").append(this.getId())
                    .append(" = ").append(JsonUtil.toString(this.getHighcharts(), false)).append(";%n")
                    .append("try { Highcharts.chart('").append(this.getId()).append("', ")
                    .append(this.getId())
                    .append("); } catch(err) {}</script>");
            builder.append("%n");
            return builder.toString();
        }
    }

    public Image getImage() {
        return image;
    }

    public int getItemIndex() {
        return itemIndex;
    }

    public void setImage(Image image) {
        this.image = image;
    }

    public void setItemIndex(int itemIndex) {
        this.itemIndex = itemIndex;
    }

    public void setType(ChartType type) {
        this.type = type;
    }

    public void setPageIndex(int pageIndex) {
        this.pageIndex = pageIndex;
    }

    public void setIndex(int index) {
        this.index = index;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public void setHighcharts(JsonObject highcharts) {
        this.highcharts = highcharts;
    }
}
