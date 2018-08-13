package com.abcft.pdfextract.core.office;

/**
 * @author chlin.abcft
 * highchart的数据模型字段
 */
public class HighChartModel {
    public static final String NAME = "name";
    public static final String TYPE = "type";
    public static final String TEXT = "text";
    public static final String TITLE = "title";
    public static final String SERIES = "series";
    public static final String DATA = "data";
    public static final String CHART = "chart";
    public static final String XA = "xAxis";
    public static final String LABELS = "labels";
    public static final String FORMAT = "format";
    public static final String YA = "yAxis";
    public static final String TOOLTIP = "tooltip";
    public static final String CATEGORIES = "categories";
    public static final String COLOR = "color";
    public static final String PLOT_OPTIONS = "plotOptions";
    public static final String DATETIME_LABEL_FORMATS = "dateTimeLabelFormats";

    /**
     * highchart的图表类型
     */
    public static class HighChartType {
        /**
         * 折线图
         */
        public static final String LINE = "line";

        /**
         * 曲线图
         */
        public static final String SMOOTH_LINE = "spline";

        /**
         * 区域图
         */
        public static final String AREA = "area";
        /**
         * 条形图
         */
        public static final String BAR = "bar";
        /**
         * 柱状图
         */
        public static final String COLUMN = "column";
        /**
         * 饼状图
         */
        public static final String PIE = "pie";
        /**
         * 散点图
         */
        public static final String SCATTER = "scatter";
        /**
         * 雷达图
         */
        public static final String POLAR = "polar";
    }
}
