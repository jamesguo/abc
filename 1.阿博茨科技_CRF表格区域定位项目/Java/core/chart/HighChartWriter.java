package com.abcft.pdfextract.core.chart;

import com.abcft.pdfextract.core.chart.model.PathInfo;

import java.awt.geom.Line2D;
import java.io.File;
import java.util.*;

import com.abcft.pdfextract.core.model.Rectangle;
import com.abcft.pdfextract.core.model.TextChunk;
import com.abcft.pdfextract.core.model.TextDirection;
import com.abcft.pdfextract.core.model.TextElement;
import com.abcft.pdfextract.core.util.GraphicsUtil;
import com.abcft.pdfextract.spi.ChartType;
import com.abcft.pdfextract.util.JsonUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import static com.abcft.pdfextract.core.chart.ChartContentScaleParser.getLinePathPoints;
import static com.abcft.pdfextract.core.chart.ChartContentScaleParser.getPathColumnPoints;
import static com.abcft.pdfextract.core.chart.ChartContentScaleParser.string2Double;

/**
 * 将Chart内部元素 解析插值出来的数据　序列化为 HighCharts 支持的 Json格式
 * 目前饼图类型可以使用　其他类型开发中
 */
class HighChartWriter {
    private static Logger logger = LogManager.getLogger(HighChartWriter.class);

    JsonObject bson = new JsonObject();
    List<String> yAxisSuffix = new ArrayList<>();

    HighChartWriter() {
        bson = new JsonObject();
        yAxisSuffix.add("");
        yAxisSuffix.add("");
    }

    /**
     * 将Chart内部元素解析数据转换为HighCharts的Json格式
     * @param chart
     * @param scaleQua
     * @param detailSaveInfo
     * @return
     */
    public boolean writeJson(
            Chart chart,
            ChartScaleQuantificationInfo scaleQua,
            boolean detailSaveInfo) {
        // 写入当前算法开发提交日期   方便查询版本发布部署情况
        writeVersion(chart);

        // 是否详细保存相关信息
        if (detailSaveInfo) {
            // 写入刻度信息　方便测试自动化
            writeAxisTextInfo(chart);

            // 写入区域信息　方便测试自动化
            writeAreaInfo(chart);
        }

        // 写标题
        writeTitle(chart);

        // 写X轴信息
        writeXAxisNew(chart, scaleQua);

        // 写两侧Y轴信息 如果有两个Y轴
        writeTwoYAxis(chart, scaleQua);

        // 写图列信息
        writeLegend(chart);

        // 写plotOptions设置信息
        writePlotOptionsNew(chart, scaleQua);

        // 输出核心解析数据　以二维数组信息　方便转换成Json格式后的读写
        boolean bwrite = writeSeriesNew(chart, scaleQua);
        /*
        // 调试测试用
        if (chart.onlyMatchXScale) {
            logger.info("onlyMatchXScale ###########");
        }
        */
        return bwrite;
    }

    /**
     * 将Chart内部元素解析数据转换为HighCharts的Json格式
     * @param chart
     * @param scaleQua
     * @return
     */
    public void writeTestJson(
            Chart chart,
            ChartScaleQuantificationInfo scaleQua) {
        // 写ChartName

        // 写ChartType
        JsonObject chartType = new JsonObject();
        chartType.addProperty("text", chart.type.toString());
        bson.add("chartType", chartType);

        // 写标题
        JsonObject titleDoc = new JsonObject();
        titleDoc.addProperty("text", chart.title);
        bson.add("title", titleDoc);

        // 写图列信息
        boolean bHasLegend = false;
        if (chart.type == ChartType.PIE_CHART && !chart.legends.isEmpty()) {
            bHasLegend = true;
        }
        for (int i = 0; i < chart.pathInfos.size() ; i++) {
            ChartPathInfo pathInfo = chart.pathInfos.get(i);
            if (!StringUtils.isEmpty(pathInfo.text)) {
                bHasLegend = true;
            }
        } // end for i

        JsonArray legends = new JsonArray();
        if (bHasLegend) {
            if (chart.legends != null && !chart.legends.isEmpty()) {
                chart.legends.stream().forEach(legend -> legends.add(legend.text));
            }
        }
        bson.add("legend", legends);

        // 写入OCR信息


        // 写入刻度信息　方便测试自动化
        writeAxisTextInfo(chart);
    }

    /**
     * 将Chart内部解析出来的数据　序列化为Bson对象　并保存为其Chart内部属性 data
     * @param chart
     * @param scaleQua
     * @return
     */
    public boolean writeBson(Chart chart, ChartScaleQuantificationInfo scaleQua) {
        // 是否将详细信息写入HighCharts文件中　方便测试自动化  默认不写入详细信息
        boolean detailSaveInfo = false;
        resetPathInfoTimestamp(chart, scaleQua);
        boolean bWrite = writeJson(chart, scaleQua, detailSaveInfo);
        // 如果失败　则清空数据　返回 false
        if (!bWrite) {
            bson = new JsonObject();
            return false;
        }

        // 将序列化对象存储到 Chart 内部属性
        chart.data = bson;

        // 输出到html 方便查看和调试
        //writeHtml(chart.title);

        return true;
    }

    public void resetPathInfoTimestamp(Chart chart, ChartScaleQuantificationInfo scaleQua) {
        if (chart == null || chart.type == ChartType.BITMAP_CHART ||
                chart.type == ChartType.PIE_CHART) {
            return;
        }
        for (ChartPathInfo pathInfo : chart.pathInfos) {
            if (pathInfo.scaleTypeX == ScaleInfoType.TIME_SCALE) {
                ScaleQuantification scaleQuantification = scaleQua.infos[pathInfo.ithSideX];
                String pattern = scaleQuantification.timeFormat.formatCommon;
                for (int i = 0; i < pathInfo.valuesX.size(); i++) {
                    String x = pathInfo.valuesX.get(i);
                    try {
                        long timestamp = Long.parseLong(x);
                        long timestampNew = ChartUtils.simplifyTimestamp(timestamp, pattern);
                        String xNew = String.valueOf(timestampNew);
                        pathInfo.valuesX.set(i, xNew);
                    }
                    catch (Exception e) {
                        logger.warn("", e);
                        continue;
                    }
                }
            }
        } // end for pathInfo
    }

    /**
     * 序列化标题
     * @param chart
     */
    public void writeTitle(Chart chart) {
        JsonObject titleDoc = new JsonObject();
        titleDoc.addProperty("text", chart.title);
        bson.add("title", titleDoc);

        // 如果有副标题
        if (chart.subtitle != null) {
            JsonObject subtitleDoc = new JsonObject();
            subtitleDoc.addProperty("text", chart.subtitle);
            bson.add("subtitle", subtitleDoc);
        }
    }

    /**
     * 保存算法最新提交时间 和 RPC OCR调用信息　方便调试 快速定位Bug
     * @param chart
     */
    public void writeVersion(Chart chart) {
        JsonObject versionDoc = new JsonObject();
        versionDoc.addProperty("text", chart.algorithmCommitTime);
        bson.add("AlgorithmCommitTime", versionDoc);
        bson.add("ocrEngine", chart.ocrEngineInfo);
        JsonObject credits = new JsonObject();
        credits.addProperty("parserDetectModelArea", chart.parserDetectModelArea);
        credits.addProperty("parserHintArea", chart.parserHintArea);
        credits.addProperty("parserRecallArea", chart.parserRecallArea);
        credits.addProperty("onlyMatchXScale", chart.onlyMatchXScale);
        credits.addProperty("legendMatchPath", chart.legendMatchPath);
        bson.add("credits", credits);
    }

    /**
     * 保存Chart区域信息　方便测试自动化
     * @param chart
     */
    public void writeAreaInfo(Chart chart) {
        Rectangle area = chart.getArea();
        if (area == null) {
            return;
        }
        JsonObject bndbox = new JsonObject();
        bndbox.addProperty("xmin", area.getMinX());
        bndbox.addProperty("xmax", area.getMaxX());
        bndbox.addProperty("ymin", area.getMinY());
        bndbox.addProperty("ymax", area.getMaxY());
        bson.add("bndbox", bndbox);
    }

    /**
     * 保存解析出的刻度信息　方便测试自动化
     * @param chart
     */
    public void writeAxisTextInfo(Chart chart) {
        // 保存下侧刻度信息
        JsonArray downAxisTexts = new JsonArray();
        if (chart.hAxisTextD != null && !chart.hAxisTextD.isEmpty()) {
            chart.hAxisTextD.stream().forEach(text -> downAxisTexts.add(text));
        }
        bson.add("vAxisTextDown", downAxisTexts);
        // 保存上侧刻度信息
        JsonArray upAxisTexts = new JsonArray();
        if (chart.hAxisTextU != null && !chart.hAxisTextU.isEmpty()) {
            chart.hAxisTextU.stream().forEach(text -> upAxisTexts.add(text));
        }
        bson.add("vAxisTextUp", upAxisTexts);
        // 保存左侧刻度信息
        JsonArray leftAxisTexts = new JsonArray();
        if (chart.vAxisTextL != null && !chart.vAxisTextL.isEmpty()) {
            chart.vAxisTextL.stream().forEach(text -> leftAxisTexts.add(text));
        }
        bson.add("vAxisTextLeft", leftAxisTexts);
        // 保存右侧刻度信息
        JsonArray rightAxisTexts = new JsonArray();
        if (chart.vAxisTextR != null && !chart.vAxisTextR.isEmpty()) {
            chart.vAxisTextR.stream().forEach(text -> rightAxisTexts.add(text));
        }
        bson.add("vAxisTextRight", rightAxisTexts);
    }

    // 判断Chart的X刻度的类型信息 区分　时间类　标称类　数值类
    public String getXAxisType(Chart chart) {
        if (chart.type == ChartType.PIE_CHART) {
            return "category";
        }
        boolean bChangeXY = chart.type == ChartType.COLUMN_CHART ? true : false;
        for (ChartPathInfo pathInfo : chart.pathInfos) {
            if (!bChangeXY) {
                if (pathInfo.scaleTypeX == ScaleInfoType.LABEL_SCALE) {
                    return "category";
                }
                else if (pathInfo.scaleTypeX == ScaleInfoType.TIME_SCALE) {
                    if (!pathInfo.bSubdivideX) {
                        return "category";
                    }
                    else {
                        return "datetime";
                    }
                }
            } // end if
            else {
                if (pathInfo.scaleTypeY == ScaleInfoType.LABEL_SCALE) {
                    return "category";
                }
                else if (pathInfo.scaleTypeY == ScaleInfoType.TIME_SCALE) {
                    if (!pathInfo.bSubdivideY) {
                        return "category";
                    }
                    else {
                        return "datetime";
                    }
                }
            } // end else
        } // end for
        return "";
    }

    public String dateTimeLabelFormat(ChartScaleQuantificationInfo scaleQua) {
        String formatHF = "";  // 对应的HighChart时间样式
        TimeFormat timeFormat = scaleQua.infos[3].timeFormat;
        if (timeFormat == null) {
            timeFormat = scaleQua.infos[2].timeFormat;
        }
        if (timeFormat != null) {
            formatHF = timeFormat.formatHighChart;
        }
        return formatHF;
    }

    public long tickInterval(ChartScaleQuantificationInfo scaleQua) {
        int n = scaleQua.infos[2].numsL.size();
        if (n >= 2) {
            return scaleQua.infos[2].numsL.get(1) - scaleQua.infos[2].numsL.get(0);
        }
        n = scaleQua.infos[3].numsL.size();
        if (n >= 2) {
            return scaleQua.infos[3].numsL.get(1) - scaleQua.infos[3].numsL.get(0);
        }
        return 0;
    }

    public long getFirstTimeLable(ChartScaleQuantificationInfo scaleQua) {
        if (!scaleQua.infos[2].numsL.isEmpty()) {
            return scaleQua.infos[2].numsL.get(0);
        }
        if (!scaleQua.infos[3].numsL.isEmpty()) {
            return scaleQua.infos[3].numsL.get(0);
        }
        return 0;
    }

    /**
     * 为了HighChart显示的起始时间和原图一样　添加起始时间的设置
     * @param chart
     * @param scaleQua
     */
    public void addStartTimeOption(Chart chart, ChartScaleQuantificationInfo scaleQua) {
        // 不处理非时间类型的情况
        String xAxisType = getXAxisType(chart);
        if (!xAxisType.equals("datetime")) {
            return;
        }
        // 如果没有有效时间间隔　则直接返回
        JsonObject startTime = new JsonObject();
        long tickValue = tickInterval(scaleQua);
        if (tickValue <= 0) {
            return;
        }
        startTime.addProperty("pointInterval", tickValue);
        long firstTime = getFirstTimeLable(scaleQua);
        startTime.addProperty("pointStart", firstTime);
        if (!bson.has("plotOptions")) {
            JsonObject series = new JsonObject();
            series.add("series", startTime);
            bson.add("plotOptions", series);
        }
        else {
            JsonObject plotOptions = (JsonObject) bson.get("plotOptions");
            plotOptions.add("series", startTime);
        }
    }

    public void writeXAxisNew(Chart chart, ChartScaleQuantificationInfo scaleQua) {
        String xAxisType = getXAxisType(chart);
        JsonObject xAxisDoc = new JsonObject();
        xAxisDoc.addProperty("type", xAxisType);

        if (xAxisType == "datetime") {
            long tickValue = tickInterval(scaleQua);
            if (tickValue > 0) {
                xAxisDoc.addProperty("tickInterval", tickValue);
            }
        }

        // 非水平平行排列的类型Chart 水平坐标刻度有可能存在　水平　垂直向上　垂直向下　方向
        if (chart.type != ChartType.COLUMN_CHART) {
            TextDirection dir = TextDirection.LTR;
            if (chart.hAxisChunksD != null && !chart.hAxisChunksD.isEmpty()) {
                dir = chart.hAxisChunksD.get(0).getDirection();
            }
            else if (chart.hAxisChunksU != null && !chart.hAxisChunksU.isEmpty()){
                dir = chart.hAxisChunksU.get(0).getDirection();
            }
            JsonObject labelDoc = new JsonObject();
            if (dir == TextDirection.VERTICAL_UP) {
                labelDoc.addProperty("rotation", 270);
            }
            else if (dir == TextDirection.VERTICAL_DOWN) {
                labelDoc.addProperty("rotation", 90);
            }
            else if (dir == TextDirection.ROTATED) {
                TextChunk chunk = chart.hAxisChunksD.get(0);
                double angle = Math.atan2(chunk.getHeight(), chunk.getWidth());
                angle = 180.0 * angle / Math.PI;
                List<TextElement> elements = chunk.getElements();
                int n = elements.size();
                if (n <= 1) {
                    labelDoc.addProperty("rotation", -45);
                }
                else {
                    if (elements.get(0).getCenterY() >= elements.get(n - 1).getCenterY()) {
                        labelDoc.addProperty("rotation", -angle);
                    }
                    else {
                        labelDoc.addProperty("rotation", angle);
                    }
                }
            }
            // 设置斜着刻度的方向
            else if (!chart.ocrs.isEmpty()) {
                // 取出第一个OCR对象　计算实际角度
                OCRPathInfo ocr = chart.ocrs.get(0);
                double angle = Math.atan2(ocr.path.getBounds2D().getHeight(), ocr.path.getBounds2D().getWidth());
                angle = 180.0 * angle / Math.PI;
                if (chart.ocrs.get(0).ccw) {
                    labelDoc.addProperty("rotation", -angle);
                }
                else {
                    labelDoc.addProperty("rotation", angle);
                }
            }
            labelDoc.addProperty("textalign", "center");

            // 如果是时间刻度 在labels标签内修改format
            if (xAxisType == "datetime") {
                String xAxisTimeFormat = dateTimeLabelFormat(scaleQua);
                if (xAxisTimeFormat.equals("")){
                    labelDoc.addProperty("format", "{value}");
                } else {
                    labelDoc.addProperty("format", String.format("{value: %s}", xAxisTimeFormat));
                }
            }
            xAxisDoc.add("labels", labelDoc);
        }

        // 如果水平坐标轴刻度为标称类型或水平柱状图的垂直坐标轴刻度为标称类型　则直接声明标称集
        List<String> labels = new ArrayList<>();
        List<Integer> postions = new ArrayList<>();
        List<Double> xs = new ArrayList<>();
        if (chart.type == ChartType.PIE_CHART) {
        }
        else if (chart.type == ChartType.COLUMN_CHART) {
            if (ChartContentScaleParser.isChartXYAxisLabel(chart, false)) {
                if (!scaleQua.infos[0].infos.isEmpty()) {
                    labels = (ArrayList<String>)((ArrayList)scaleQua.infos[0].infos).clone();
                    Collections.reverse(labels);
                }
                else if (!scaleQua.infos[1].infos.isEmpty()) {
                    labels = (ArrayList<String>)((ArrayList)scaleQua.infos[1].infos).clone();
                    Collections.reverse(labels);
                }
            }
        }
        else {
            if (ChartContentScaleParser.isChartXYAxisLabel(chart, true)) {
                if (!scaleQua.infos[2].infos.isEmpty()) {
                    labels = scaleQua.infos[2].infos;
                }
                else if (!scaleQua.infos[3].infos.isEmpty()) {
                    labels = scaleQua.infos[3].infos;
                }
                // 如果没有有效X刻度 即图形元素X轴标都是坐标值的情况  控制显示范围
                else  if (scaleQua.infos[2].infos.isEmpty() && scaleQua.infos[3].infos.isEmpty()) {
                    if (chart.hAxis != null) {
                        double range = chart.hAxis.getBounds().getMaxX() - chart.hAxis.getBounds().getMinX() + 10;
                        xAxisDoc.addProperty("minRange", range);
                    }
                }
            }

            // 如果是经过扩展的X刻度对象 则设置扩充后的刻度和其位置控制数组　为显示和原图尽可能保持一致
            // 新的显示控制方法　需要经过一定的测试
            if (chart.onlyMatchXScale) {
                if (!scaleQua.infos[2].infos.isEmpty()) {
                    labels = scaleQua.infos[2].infosExt;
                    xs = scaleQua.infos[2].xsExt;
                }
                else if (!scaleQua.infos[3].infos.isEmpty()) {
                    labels = scaleQua.infos[3].infosExt;
                    xs = scaleQua.infos[3].xsExt;
                }
                // 计算有效标签刻度的序号，　方便控制显示位置
                for (int i = 0; i < labels.size(); i++) {
                    String label = labels.get(i);
                    if (StringUtils.isNotEmpty(label)) {
                        postions.add(i);
                    }
                }
            }
        }
        if (!labels.isEmpty()) {
            JsonUtil.putCollection(xAxisDoc, "categories", labels);
            // 如果刻度位置不为空　　则加入位置控制数组
            if (!postions.isEmpty() && !xs.isEmpty()) {
                JsonUtil.putCollection(xAxisDoc, "tickPositions", postions);
                JsonUtil.putCollection(xAxisDoc, "categoriesXValue", xs);
            }
        }

        bson.add("xAxis", xAxisDoc);
    }

    /**
     * 得到给定PathInfo对应的序列化Y刻度信息
     * 如果对应与标称类的X坐标　没有Y值　则用　null 代替
     * @param pathInfo
     * @param scaleQua
     * @return
     */
    public List<Double> getSeriesYAxis(
            ChartPathInfo pathInfo,
            ChartScaleQuantificationInfo scaleQua,
            boolean bYAxis) {
        List<String> values = pathInfo.valuesY;
        if (!bYAxis) {
            values = pathInfo.valuesX;
        }
        List<Double> nums = ChartContentScaleParser.transforListString2Double(values);
        List<Double> numsNew = new ArrayList<>();
        if (nums.isEmpty()) {
            return numsNew;
        }

        int []ithSides = {2, 3};
        if (!bYAxis) {
            ithSides[0] = 0;
            ithSides[1] = 1;
        }
        // 根据下侧刻度对应的Y刻度属性
        numsNew = getPathInfoSerieYAxis(pathInfo, bYAxis, nums, scaleQua.infos[ithSides[0]].infos);
        if (!numsNew.isEmpty()) {
            return numsNew;
        }
        // 根据上侧刻度对应的Y刻度属性
        numsNew = getPathInfoSerieYAxis(pathInfo, bYAxis, nums, scaleQua.infos[ithSides[1]].infos);
        if (!numsNew.isEmpty()) {
            return numsNew;
        }
        return nums;
    }

    /**
     * 获取给定PathInfo对应的Y刻度属性序列
     * @param pathInfo
     * @param bYAxis,
     * @param nums
     * @param infos
     * @return
     */
    public List<Double> getPathInfoSerieYAxis(
            ChartPathInfo pathInfo,
            boolean bYAxis,
            List<Double> nums,
            List<String> infos) {
        List<String> values = pathInfo.valuesX;
        if (!bYAxis) {
            values = pathInfo.valuesY;
        }
        List<Double> numsNew = new ArrayList<>();
        for (int j = 0; j < infos.size(); j++) {
            String info = infos.get(j);
            int ith = values.indexOf(info);
            if (ith == -1) {
                numsNew.add(null);
            }
            else {
                numsNew.add(nums.get(ith));
            }
        } // end for j
        return numsNew;
    }

    public void writeLegend(Chart chart) {
        boolean bHasLegend = false;
        if (chart.type == ChartType.PIE_CHART && !chart.legends.isEmpty()) {
            bHasLegend = true;
        }
        for (int i = 0; i < chart.pathInfos.size() ; i++) {
            ChartPathInfo pathInfo = chart.pathInfos.get(i);
            if (!StringUtils.isEmpty(pathInfo.text)) {
                bHasLegend = true;
            }
        } // end for i

        JsonObject legendDoc = new JsonObject();
        legendDoc.addProperty("enabled", bHasLegend);
        bson.add("legend", legendDoc);
    }

    private void setNotDisplayAuxiliaryLine(JsonObject obj) {
        obj.addProperty("gridLineWidth", 0);
    }

    public JsonObject writeOneYAxis(String suffix, List<String> axisInfos, TextChunk unit) {
        JsonObject labelDoc = new JsonObject();
        JsonObject styleDoc = new JsonObject();
        JsonObject titleDoc = new JsonObject();
        // 如果前缀或后缀不为空且存在刻度信息　则判断前后缀位置 　使highchart显示刻度尽量与原图一致
        if (StringUtils.isNotEmpty(suffix) && axisInfos != null && !axisInfos.isEmpty()) {
            String firstAxisInfo = axisInfos.get(0);
            if (firstAxisInfo.startsWith(suffix)) {
                labelDoc.addProperty("format", suffix + "{value}");
            }
            else {
                labelDoc.addProperty("format", "{value}" + suffix);
            }
        }
        else {
            labelDoc.addProperty("format", "{value}" + suffix);
        }
        labelDoc.add("style", styleDoc);

        // 控制刻度单位显示
        if (unit != null) {
            titleDoc.addProperty("text", unit.getText());
            TextDirection direction = unit.getDirection();
            if (direction == TextDirection.LTR) {
                titleDoc.addProperty("align", "high");
                titleDoc.addProperty("rotation", 0);
            }
            else {
                titleDoc.addProperty("align", "middle");
                if (direction == TextDirection.VERTICAL_UP) {
                    titleDoc.addProperty("rotation", 270);
                }
                else {
                    titleDoc.addProperty("rotation", 90);
                }
            }
        }
        else {
            titleDoc.addProperty("text", "");
        }

        styleDoc = new JsonObject();
        titleDoc.add("style", styleDoc);

        JsonObject yAxisLDoc = new JsonObject();
        yAxisLDoc.add("labels", labelDoc);
        yAxisLDoc.add("title", titleDoc);
        // 添加坐标轴的刻度值　可视化上更接近原始PDF文件中的Chart
        if (axisInfos != null) {
            double [] num = {0.0};
            String [] suffix2 = {""};
            JsonArray data = new JsonArray();
            for (String axis : axisInfos) {
                if (axis != null) {
                    if (ChartContentScaleParser.string2Double(axis, num, suffix2)) {
                        data.add(num[0]);
                    }
                }
            }
            if (data.size() >= 1) {
                yAxisLDoc.add("tickPositions", data);
            }
        }
        return yAxisLDoc;
    }

    public String [] getAxisRangeInfo(ScaleQuantification axis) {
        String [] minmax = {null, null};
        if (!axis.numsD.isEmpty()) {
            minmax[0] = axis.infos.get(0);
            int n = axis.infos.size();
            minmax[1] = axis.infos.get(n - 1);
        }
        return minmax;
    }

    /**
     * 序列化左右两侧的刻度信息
     * @param chart
     * @param scaleQua
     * @return
     */
    public boolean writeTwoYAxis(Chart chart, ChartScaleQuantificationInfo scaleQua) {
        if (chart.type == ChartType.PIE_CHART) {
            return true;
        }

        JsonArray yAxiss = new JsonArray();
        int [] ithSides = {0, 1};
        if (chart.type == ChartType.COLUMN_CHART) {
            ithSides[0] = 2;
            ithSides[1] = 3;
        }
        if (!scaleQua.infos[ithSides[0]].infos.isEmpty()) {
            //检查轴坐标的值域是否包含图内数据 如果需要额外增加tick
            expandAxisRange(chart, scaleQua, ithSides[0]);
            String suffix = scaleQua.infos[ithSides[0]].suffix[0];
            TextChunk unit = null;
            if (chart.type != ChartType.COLUMN_CHART && chart.vAxisTextUnitL != null) {
                unit = chart.vAxisChunkUnitL;
            }
            JsonObject yAxisLDoc = writeOneYAxis(suffix, scaleQua.infos[ithSides[0]].infos, unit);
            yAxisLDoc.addProperty("tickAmount", scaleQua.infos[ithSides[0]].infos.size());
            yAxiss.add(yAxisLDoc);
            yAxisSuffix.set(0, suffix);
        } // end if
        if (!scaleQua.infos[ithSides[1]].infos.isEmpty()) {
            //检查轴坐标的值域是否包含图内数据 如果需要额外增加tick
            expandAxisRange(chart, scaleQua, ithSides[1]);
            String suffix = scaleQua.infos[ithSides[1]].suffix[0];
            TextChunk unit = null;
            if (chart.type != ChartType.COLUMN_CHART && chart.vAxisTextUnitR != null) {
                unit = chart.vAxisChunkUnitR;
            }
            JsonObject yAxisRDoc = writeOneYAxis(suffix, scaleQua.infos[ithSides[1]].infos, unit);
            yAxisRDoc.addProperty("tickAmount", scaleQua.infos[ithSides[1]].infos.size());
            yAxisRDoc.addProperty("opposite", true);
            yAxiss.add(yAxisRDoc);
            yAxisSuffix.set(1, suffix);
        } // end if
        if (yAxiss.size() == 0) {
            List<String> suffixs = getChartYAxisSuffix(chart);
            TextChunk unitChunk = null;
            if (chart.vAxisChunkUnitL != null) {
                unitChunk = chart.vAxisChunkUnitL;
            }
            JsonObject yAxisLDoc = writeOneYAxis(suffixs.get(0), null, unitChunk);
            yAxiss.add(yAxisLDoc);
            yAxisSuffix.set(0, suffixs.get(0));
            if (suffixs.size() >= 2) {
                JsonObject yAxisRDoc = writeOneYAxis(suffixs.get(1), null, null);
                yAxisRDoc.addProperty("opposite", true);
                yAxiss.add(yAxisRDoc);
                yAxisSuffix.set(1, suffixs.get(1));
            }
        }
        // 如果有两个Y轴 则隐藏辅助水平线
        if (yAxiss.size() == 2) {
            setNotDisplayAuxiliaryLine((JsonObject)yAxiss.get(0));
            setNotDisplayAuxiliaryLine((JsonObject)yAxiss.get(1));
        }

        bson.add("yAxis", yAxiss);
        return true;
    }

    /**
     * 检查轴坐标的值域是否能包含图内数据 如果需要额外增加tick (上/下) 各仅增加一次
     * @param chart
     * @return
     */
    private void expandAxisRange(Chart chart, ChartScaleQuantificationInfo scaleQua, int ithSide) {
        List<Double> axisValues = scaleQua.infos[ithSide].numsD;
        if (axisValues.size() < 2) {
            // && scaleQua.numsL.isEmpty()
            return;
        }

        // 暂时只对浮点数numsD处理
        double maxAxis = Collections.max(axisValues);
        double minAxis = Collections.min(axisValues);
        double interval = Math.abs(axisValues.get(1) - axisValues.get(0));
        double [] num = {0.0};
        String [] suffix = {""};

        // 判断刻度从下往上是否递增
        boolean axisIncrease = axisValues.get(1) > axisValues.get(0);

//        chart.pathInfos.get(0).ithSideY
        for (ChartPathInfo pathInfo : chart.pathInfos) {
            if (pathInfo.ithSideY == ithSide) {
                //如果Path对应此坐标轴
                List<Double> graphValues = new ArrayList<>();
                pathInfo.valuesY.forEach(str -> {if (string2Double(str, num, suffix)) {
                    graphValues.add(num[0]);
                }});

                if (graphValues.isEmpty()) {
                    break;
                }

                double maxGraph = Collections.max(graphValues);
                double minGraph = Collections.min(graphValues);

                if (maxGraph > maxAxis + 0.05 * interval) {
                    // Y轴最大值不够用 仅扩充一次
                    double toAdd = maxAxis + interval;
                    toAdd = roundOff(toAdd, 2);
                    if (axisIncrease) {
                        scaleQua.infos[ithSide].numsD.add(toAdd);
                        scaleQua.infos[ithSide].infos.add(Double.toString(toAdd));
                    }
                    else {
                        scaleQua.infos[ithSide].numsD.add(0, toAdd);
                        scaleQua.infos[ithSide].infos.add(0, Integer.toString((int) toAdd));
                    }
                    break;
                }

                if (minGraph < minAxis - 0.05 * interval) {
                    // Y轴最小值不够用 仅扩充一次
                    double toAdd = minAxis - interval;
                    toAdd = roundOff(toAdd, 2);
                    if (axisIncrease) {
                        scaleQua.infos[ithSide].numsD.add(0, toAdd);
                        scaleQua.infos[ithSide].infos.add(0, Integer.toString((int) toAdd));
                    }
                    else {
                        scaleQua.infos[ithSide].numsD.add(toAdd);
                        scaleQua.infos[ithSide].infos.add(Double.toString(toAdd));
                    }
                    break;
                }
            }
        }
    }

    /**
     * 对没有左右侧坐标轴的Chart的PathInfos进行分析　获得其有效后缀
     * @param chart
     * @return
     */
    public List<String> getChartYAxisSuffix(Chart chart) {
        double [] num = {0.0};
        String [] suffix = {""};
        String info = "";
        List<String> suffixs = new ArrayList<>();
        for (int i = 0; i < chart.pathInfos.size(); i++) {
            ChartPathInfo pinfo = chart.pathInfos.get(i);
            if (chart.type != ChartType.COLUMN_CHART) {
                if (pinfo.valuesY.isEmpty()) {
                    continue;
                }
                info = pinfo.valuesY.get(0);
            }
            else {
                if (pinfo.valuesX.isEmpty()) {
                    continue;
                }
                info = pinfo.valuesX.get(0);
            }
            if(ChartContentScaleParser.string2Double(info, num, suffix)) {
                int ith = suffixs.indexOf(suffix[0]);
                if (ith == -1) {
                    ith = suffixs.size();
                    suffixs.add(suffix[0]);
                }
                // 通常最多只有两个坐标轴　如果提取数据出错　出现两种以上的后缀, 则取前两个
                pinfo.ithSideY = ith % 2;
            }
        } // end for i
        if (suffixs.isEmpty()) {
            suffixs.add("");
        }
        return suffixs;
    }

    public String getPathInfoDataType(
        ChartPathInfo pathInfo,
        ChartScaleQuantificationInfo scaleQua) {

        if (pathInfo.type == PathInfo.PathType.LINE) {
            return "line";
        }
        else if (pathInfo.type == PathInfo.PathType.CURVE) {
            return "spline";
        }
        else if (pathInfo.type == PathInfo.PathType.BAR) {
            return "column";
        }
        else if (pathInfo.type == PathInfo.PathType.COLUMNAR) {
            return "bar";
        }
        else if (pathInfo.type == PathInfo.PathType.AREA) {
            return "area";
        }
        else {
            return "";
        }
    }

    public JsonObject writePieInfo(
            Chart.PieInfo pieInfo, int id) {
        JsonObject serieDoc = new JsonObject();
        // 设置名称
        serieDoc.addProperty("name", "");

        // 设置环形图样式 默认50%大小
        if (!pieInfo.parts.get(0).isPie) {
            serieDoc.addProperty("innerSize", "50%");
        }

        // 设置类型
        serieDoc.addProperty("type", "pie");

        java.text.DecimalFormat df = new java.text.DecimalFormat("##.##%");

        // 遍历路径对应的X坐标刻度属性集 找出元组　[x, y] 放入 data 对象
        JsonArray data = new JsonArray();
        JsonArray colors = new JsonArray();
        for (int i = 0; i < pieInfo.parts.size(); i++) {
            JsonArray values = new JsonArray();
            Chart.PieInfo.PiePartInfo part = pieInfo.parts.get(i);
            if (part.id != id) {
                continue;
            }
            if (StringUtils.isEmpty(part.text)) {
                if (part.weight < 0.0001) {
                    continue;
                }
                String name = df.format(part.weight);
                values.add(name);
            }
            else {
                values.add(part.text);
            }
            values.add(part.weight);
            data.add(values);
            colors.add(GraphicsUtil.color2String(part.color));
        } // end for i
        if (data.size() == 0) {
            return null;
        }

        serieDoc.add("data", data);
        // 设置饼图各扇形块颜色　显示和原图相同颜色
        serieDoc.add("colors", colors);

        return serieDoc;
    }

    public JsonObject writePathInfo(
            ChartPathInfo pathInfo,
            ChartScaleQuantificationInfo scaleQua) {
        JsonObject serieDoc = new JsonObject();
        // 设置名称
        serieDoc.addProperty("name", pathInfo.text);

        // 如果图例为空　则设置不显示该图例 避免出现 Series1 Series2 ......
        if (StringUtils.isEmpty(pathInfo.text)) {
            serieDoc.addProperty("showInLegend", false);
        }

        // 设置类型
        String typeStr = getPathInfoDataType(pathInfo, scaleQua);
        serieDoc.addProperty("type", typeStr);

        // 设置颜色
        serieDoc.addProperty("color", GraphicsUtil.color2String(pathInfo.color));

        // 判断是否对应右侧的坐标刻度信息
        //String axisSuffix = scaleQua.infos[pathInfo.ithSideY].suffix[0];
        String axisSuffix = yAxisSuffix.get(pathInfo.ithSideY);
        if (pathInfo.type == PathInfo.PathType.COLUMNAR) {
            int ithSideY = pathInfo.ithSideX == 2 ? 0 : 1;
            axisSuffix = yAxisSuffix.get(ithSideY);
        }
        if (pathInfo.ithSideY == 1) {
            // 判断是否有两个Y轴坐标轴
            boolean hasTwoAxis = false;
            if (typeStr.equals("bar")) {
                hasTwoAxis = !scaleQua.infos[2].infos.isEmpty() && !scaleQua.infos[3].infos.isEmpty();
            }
            else {
                hasTwoAxis = !scaleQua.infos[0].infos.isEmpty() && !scaleQua.infos[1].infos.isEmpty();
            }
            // 如果有两个　则需要设置　否则不需要
            if (hasTwoAxis) {
                serieDoc.addProperty("yAxis", 1);
            }
        }

        // 判断PathInfo是否为水平的柱状矩形　如果是则需要对调　X和Y坐标刻度
        // 后续可能添加其他需要对调情况
        List<String> valuesX = pathInfo.valuesX;
        List<String> valuesY = pathInfo.valuesY;
        ScaleInfoType typeX = pathInfo.scaleTypeX;
        ScaleInfoType typeY = pathInfo.scaleTypeY;
        if (pathInfo.type == PathInfo.PathType.COLUMNAR) {
            valuesX = pathInfo.valuesY;
            valuesY = pathInfo.valuesX;
            typeX = pathInfo.scaleTypeY;
            typeY = pathInfo.scaleTypeX;
        }

        // 遍历路径对应的X坐标刻度属性集 找出元组　[x, y] 放入 data 对象
        JsonArray data = new JsonArray();
        double [] num = {0.0};
        String [] suffix = {""};
        for (int j = 0; j < valuesX.size(); j++) {
            JsonArray values = new JsonArray();
            String str = valuesX.get(j);
            try {
                // 设置 X 刻度
                if (typeX == ScaleInfoType.TIME_SCALE) {
                    long timeLong = Long.parseLong(str);
                    values.add(timeLong);
                } else if (typeX == ScaleInfoType.LABEL_SCALE) {
                    values.add(str);
                } else {
                    if (ChartContentScaleParser.string2Double(str, num, suffix)) {
                        // 取小数点后４位有效数字
                        double value = num[0];
                        value = roundOff(value, 4);
                        values.add(value);
                    }
                    else {
                        return null;
                    }
                }

                // 设置 Y 刻度
                if (typeY == ScaleInfoType.NUMBER_SCALE) {
                    if (ChartContentScaleParser.string2Double(valuesY.get(j), num, suffix)) {
                        double value = num[0];
                        if (!suffix[0].equals(axisSuffix)) {
                            if (suffix[0].equals("%")) {
                                value /= 100.0;
                            }
                        }
                        value = roundOff(value, 4);
                        values.add(value);
                    }
                    else {
                        return null;
                    }
                }
                else {
                    values.add(JsonNull.INSTANCE);
                }
            } catch (Exception e) {
                return null;
            }
            data.add(values);
        } // end for j

        // 如果只对刻度正上方对象匹配刻度值
        if (pathInfo.onlyMatchXScale) {
            ScaleQuantification xscale = scaleQua.infos[pathInfo.ithSideX];
            // 对pathInfo对象的采样点　投射到扩展后的刻度上
            List<JsonArray> xy = mapPointsToXScale(data, xscale);
            if (xy == null) {
                return null;
            }
            else {
                serieDoc.add("data", xy.get(1));
            }
        }
        else {
            serieDoc.add("data", data);
        }
        return serieDoc;
    }

    /**
     * 基于给定刻度　匹配正对即正上方的点, 其余点的刻度值赋空值
     * @param pts
     * @param xscale
     * @return
     */
    public static List<JsonArray> mapPointsToXScale(
            JsonArray pts, ScaleQuantification xscale) {
        // 判断数据有效性
        if (pts.isJsonNull() || pts.size() == 0 || xscale.xsExt.isEmpty()) {
            return null;
        }

        List<Double> xscaleXs = xscale.xsExt;
        int n = xscaleXs.size();
        int npts = pts.size();
        JsonArray xs = new JsonArray();
        JsonArray ys = new JsonArray();
        double xStart = xscaleXs.get(0);
        double dx = 0.5 * (xscaleXs.get(1)  - xStart);
        double x = 0, ptX = 0, ptY = 0;
        // 遍历投射点
        for (int i = 0; i < n; i++) {
            x = xscaleXs.get(i);
            x = roundOff(x, 4);
            xs.add(x);
            boolean find = false;
            for (int j = 0; j < npts; j++) {
                JsonArray pt = pts.get(j).getAsJsonArray();
                if (pt.isJsonNull() || pt.size() != 2) {
                    continue;
                }
                ptX = pt.get(0).getAsDouble() + xStart;
                ptY = pt.get(1).getAsDouble();
                // 如果点里投射点太远　直接返回　加快效率
                if (ptX > x + 4 * dx) {
                    break;
                }
                // 如果存在匹配点即正上方点　则保存Y值并返回
                if (Math.abs(x - ptX) < dx) {
                    ys.add(ptY);
                    find = true;
                    break;
                }
            } // end for j
            // 如果没有匹配点　则赋空值
            if (!find) {
                ys.add("");
            }
        } // end for i
        /*
        // 遍历所有点　将相邻同Y值之间的空白点赋值
        for (int i = 1; i < ys.size() - 1; i++) {
            String yiB = ys.get(i - 1).getAsString();
            String yi = ys.get(i).getAsString();
            String yiA = ys.get(i + 1).getAsString();
            if (StringUtils.isEmpty(yi) && StringUtils.isNotEmpty(yiB) && yiB.equals(yiA)) {
                ys.set(i, ys.get(i - 1));
            }
        }
        */

        //　保存结果
        List<JsonArray> xy = new ArrayList<>();
        xy.add(xs);
        xy.add(ys);
        return xy;
    }

    /**
     * 控制的浮点数小数点位数　产品提出尽量压缩位数的需求
     * @param x
     * @param position
     * @return
     */
    public static double roundOff(double x, int position)
    {
        double a = x;
        double temp = Math.pow(10.0, position);
        a *= temp;
        a = Math.round(a);
        return (a / (float)temp);
    }

    /**
     * 从JsonObject中获取pathInfos对应的属性
     * @param obj
     * @return
     */
    public static List<List<Double>> getPathInfosJsonValues(JsonObject obj) {
        JsonArray series = obj.getAsJsonArray("series");
        if (series.isJsonNull() || series.size() == 0) {
            return null;
        }
        List<Double> barValidValueRates = new ArrayList<>();
        List<Double> lineValidValueRates = new ArrayList<>();
        List<List<Double>> pathsValues = new ArrayList<>();
        for (int idata = 0; idata < series.size(); idata++) {
            JsonElement pathInfo = series.get(idata);
            if (pathInfo.isJsonNull()) {
                return null;
            }
            String type = pathInfo.getAsJsonObject().get("type").getAsString();
            JsonArray data = pathInfo.getAsJsonObject().getAsJsonArray("data");
            if (data.isJsonNull() || data.size() == 0) {
                return null;
            }

            double validValueRate = 0.0;
            List<Double> vaules = new ArrayList<>();
            for (int i = 0; i < data.size(); i++) {
                JsonElement element = data.get(i);
                if (!element.isJsonArray()) {
                    return null;
                }
                JsonArray array = element.getAsJsonArray();
                if (array.isJsonNull()) {
                    continue;
                }
                Double value = new Double(0);
                JsonElement ele = null;
                if (array.size() == 1) {
                    ele = array.get(0);
                }
                else if (array.size() == 2) {
                    ele = array.get(1);
                }
                else {
                    continue;
                }
                if (ele.isJsonNull()) {
                    continue;
                }
                value = ele.getAsDouble();
                vaules.add(value);
            } // end for i
            if (!vaules.isEmpty()) {
                if (vaules.size() == 1 && (type.equals("line") || type.equals("curve"))) {
                    return null;
                }
                // 统计水平柱状对象的有效值占比
                validValueRate = 1.0 * vaules.size() / data.size();
                if (type.equals("bar") && vaules.size() == 1) {
                    barValidValueRates.add(validValueRate);
                }
                if ((type.equals("line") || type.equals("curve"))) {
                    lineValidValueRates.add(validValueRate);
                }
                pathsValues.add(vaules);
            }
        } // end for idata
        // 某些表格通常误解析为水平柱状图　而且有效值数目较少　　判断有效值占比
        if (barValidValueRates.size() == pathsValues.size() && pathsValues.size() == 1) {
            if (barValidValueRates.get(0) < 0.51) {
                return null;
            }
        }
        // 某些折线对象解析出的有效值数目较少 视觉效果是　大部分空着　　判断有效值占比
        if (lineValidValueRates.size() == pathsValues.size() && pathsValues.size() == 1) {
            if (lineValidValueRates.get(0) < 0.3) {
                return null;
            }
        }
        return pathsValues;
    }

    public void addPathNullLabel(
            Chart chart,
            ChartPathInfo pathInfo,
            ChartScaleQuantificationInfo scaleQua,
            JsonObject data) {
        // 不处理非category类型即非标签类型的
        String xAxisType = getXAxisType(chart);
        if (!xAxisType.equals("category")) {
            return;
        }
        JsonArray oldData = data.getAsJsonArray("data");
        List<String> scale = null;
        if (pathInfo.type == PathInfo.PathType.COLUMNAR) {
            if (!scaleQua.infos[0].infos.isEmpty()) {
                scale = scaleQua.infos[0].infos;
            }
            else if (!scaleQua.infos[1].infos.isEmpty()) {
                scale = scaleQua.infos[1].infos;
            }
            else {
                return;
            }
        }
        else {
            if (!scaleQua.infos[2].infos.isEmpty()) {
                scale = scaleQua.infos[2].infos;
            }
            else if (!scaleQua.infos[3].infos.isEmpty()) {
                scale = scaleQua.infos[3].infos;
            }
            else {
                return;
            }
        }
        // 如果代表点数目和已取得的数据数目不匹配
        int n = scale.size();
        List<Double> fullScale = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            fullScale.add(null);
        }
        for (int i = 0; i < oldData.size(); i++) {
            String item = oldData.get(i).getAsJsonArray().get(0).getAsString();
            Double value = oldData.get(i).getAsJsonArray().get(1).getAsDouble();
            if (!scale.contains(item)) {
                return;
            }

            for (int j = 0; j < n; j++) {
                if (!scale.get(j).equals(item)) {
                    continue;
                }
                if (fullScale.get(j) != null) {
                    continue;
                }
                else {
                    fullScale.set(j, value);
                    break;
                }
            } // end for j
        }
        JsonArray newData = new JsonArray();
        for (int i = 0; i < n; i++) {
            JsonArray arr = new JsonArray();
            arr.add(scale.get(i));
            arr.add(fullScale.get(i));
            newData.add(arr);
        }
        data.remove("data");
        data.add("data", newData);
    }
    /**
     * 对应X轴为标签类型的并且存在重复标签时，以前的输出格式显示效果不好，
     * 现在采取遍历所有标签的方法，如果对某个PathInfo对象某个标签没有对应值，则设为[]
     * 先对含有重复标签的情况使用，待使用一段时间稳定后，推广到所有标签类型的数据
     * @param chart
     * @param pathInfo
     * @param scaleQua
     * @param data
     */
    public void resetPathLabel(
            Chart chart,
            ChartPathInfo pathInfo,
            ChartScaleQuantificationInfo scaleQua,
            JsonObject data) {
        // 不处理非category类型即非标签类型的
        String xAxisType = getXAxisType(chart);
        if (!xAxisType.equals("category")) {
            return;
        }

        // 判断是否有重复label
        List<Double> xs = new ArrayList<>();
        Set<String> set = new HashSet<>();
        if (pathInfo.type == PathInfo.PathType.COLUMNAR) {
            if (!scaleQua.infos[0].xs.isEmpty()) {
                xs = scaleQua.infos[0].xs;
                set = new HashSet<String>(scaleQua.infos[0].infos);
            }
            else if (!scaleQua.infos[1].xs.isEmpty()) {
                xs = scaleQua.infos[1].xs;
                set = new HashSet<String>(scaleQua.infos[1].infos);
            }
            else {
                return;
            }
            Collections.reverse(xs);
        }
        else {
            if (!scaleQua.infos[2].xs.isEmpty()) {
                xs = scaleQua.infos[2].xs;
                set = new HashSet<String>(scaleQua.infos[2].infos);
            }
            else if (!scaleQua.infos[3].xs.isEmpty()) {
                xs = scaleQua.infos[3].xs;
                set = new HashSet<String>(scaleQua.infos[3].infos);
            }
            else {
                return;
            }
        }
        // 如果没有重复的label 则直接返回    等算法稳定后推广到所有标签类型的情况
        if (set.size() == xs.size()) {
            return;
        }

        // 找出给定pathInfo点的有效X坐标
        List<Double> cxs = new ArrayList<>();    // Path X坐标
        List<Double> cys = new ArrayList<>();    // Path Y坐标
        if (pathInfo.type == PathInfo.PathType.CURVE || pathInfo.type == PathInfo.PathType.LINE) {
            if (!getLinePathPoints(pathInfo.path, chart.hAxis, cxs, cys)) {
                return;
            }
        }
        else if (pathInfo.type == PathInfo.PathType.BAR || pathInfo.type == PathInfo.PathType.COLUMNAR) {
            boolean bVertical = pathInfo.type == PathInfo.PathType.BAR;
            Line2D axis = bVertical ? chart.hAxis : (chart.lvAxis != null ? chart.lvAxis : chart.rvAxis);
            if (!getPathColumnPoints(pathInfo.path, bVertical, chart.getArea(), axis, cxs, cys)) {
                return;
            }
            if (!bVertical) {
                cxs = cys;
            }
        }
        else {
            return;
        }

        int nc = cxs.size();
        JsonArray oldData = data.getAsJsonArray("data");
        JsonArray filterData = new JsonArray();
        for (int i = 0; i < oldData.size(); i++) {
            JsonArray item = oldData.get(i).getAsJsonArray();
            if (item.size() == 2 && item.get(1).isJsonNull()) {
                continue;
            }
            filterData.add(item);
        }
        // 如果代表点数目和已取得的数据数目不匹配
        if (nc != filterData.size()) {
            return;
        }

        double xLeftOrUp = 0.0;
        double xRightOrDown = 0.0;
        double xmin = 0.0;
        double xmax = 0.0;
        double x = 0.0;
        JsonArray newData = new JsonArray();
        List<Boolean> ptStatus = new ArrayList<>();
        cxs.stream().forEach(cx -> ptStatus.add(false));
        // 遍历找出每个标签的有效范围
        int n = xs.size();
        for (int i = 0; i < n; i++) {
            // 找出当前标签对应的有效范围区间
            if (i == 0) {
                xLeftOrUp = xs.get(i) - 2.0 * (xs.get(i + 1) - xs.get(i));
                xRightOrDown = 0.5 * (xs.get(i) + xs.get(i + 1));
            }
            else if (i == n - 1) {
                xLeftOrUp = xRightOrDown;
                xRightOrDown = xs.get(i) + 2.0 * (xs.get(i) - xs.get(i - 1));
            }
            else {
                xLeftOrUp = xRightOrDown;
                xRightOrDown = 0.5 * (xs.get(i) + xs.get(i + 1));
            }
            // 计算范围的最小最大值
            xmin = Math.min(xLeftOrUp, xRightOrDown);
            xmax = Math.max(xLeftOrUp, xRightOrDown);
            int id = -1;
            JsonArray arr = new JsonArray();
            // 遍历已取得的数据　找出属于当前标签有效范围内的数据
            for (int j = 0; j < cxs.size(); j++) {
                x = cxs.get(j);
                if (ptStatus.get(j)) {
                    continue;
                }
                // 如果当前数据刚好在区间范围内　则标记找到过并记录序号
                if (xmin <= x && x < xmax) {
                    ptStatus.set(j, true);
                    id = j;
                    break;
                }
            } // end for j
            if (id != -1) {
                //arr.add(oldData.get(id).getAsJsonArray().get(1));
                arr.add(filterData.get(id).getAsJsonArray().get(1));
            }
            newData.add(arr);
        } // end for i
        // 用新数据代替老数据
        data.remove("data");
        data.add("data", newData);
    }

    /**
     * 重置折线对象的顺序, 将其放到柱状, 面积等其他图形后面
     * @param pathInfos
     * @return
     */
    public List<ChartPathInfo> resetLinePathInfoOrder(List<ChartPathInfo> pathInfos) {
        List<ChartPathInfo> linePathInfos = new ArrayList<>();
        List<ChartPathInfo> otherPathInfos = new ArrayList<>();
        for (int i = 0; i < pathInfos.size(); i++) {
            ChartPathInfo pathInfo = pathInfos.get(i);
            if (pathInfo.type == PathInfo.PathType.LINE ||
                    pathInfo.type == PathInfo.PathType.CURVE ||
                    pathInfo.type == PathInfo.PathType.DASH_LINE) {
                linePathInfos.add(pathInfo);
            }
            else {
                otherPathInfos.add(pathInfo);
            }
        } // end for i
        otherPathInfos.addAll(linePathInfos);
        return otherPathInfos;
    }

    public List<ChartPathInfo> reverseBarPathInfo(List<ChartPathInfo> pathInfos) {
        int istart = -1;
        int iend = 0;
        for (int i = 0; i < pathInfos.size(); i++) {
            ChartPathInfo pathInfo = pathInfos.get(i);
            if (pathInfo.type == PathInfo.PathType.COLUMNAR ||
                    pathInfo.type == PathInfo.PathType.BAR) {
                if (istart == -1) {
                    istart = i;
                }
                iend = i;
            }
        } // end for i
        List<ChartPathInfo> pathInfosNew = new ArrayList<>();
        for (int i = 0; i < pathInfos.size(); i++) {
            if (istart <= i && i <= iend) {
                pathInfosNew.add(pathInfos.get(iend - (i - istart)));
            }
            else {
                pathInfosNew.add(pathInfos.get(i));
            }
        }
        return pathInfosNew;
    }

    public boolean writeSeriesNew(
            Chart chart, ChartScaleQuantificationInfo scaleQua) {
        JsonArray series = new JsonArray();
        // 处理饼图内部的扇形元素
        if (chart.type == ChartType.PIE_CHART) {
           Set<Integer> ids = new HashSet<>();
           chart.pieInfo.parts.stream().forEach(part -> ids.add(part.id));
           int ith = 1;
           boolean hasMorePie = ids.size() >= 2;
           for (Integer id : ids) {
               JsonObject serie = writePieInfo(chart.pieInfo, id);
               if (serie == null) {
                   return false;
               }
               JsonObject dataLabel = writePiePlotOptions(chart);
               serie.add("plotOptions", dataLabel);
               JsonArray pos = new JsonArray();
               // 如果有多个饼图或是环形图　则依次显示 后续得优化排列方式
               if (hasMorePie) {
                   pos.add((ith - 1) * 300 + 250);
                   pos.add(150);
                   serie.add("center", pos);
                   serie.addProperty("size", 150);
                   if (ith >= 2) {
                       serie.addProperty("showInLegend", false);
                   }
               }
               series.add(serie);
               ith++;
           } // end for id
        }
        // 处理其他内部PathInfo元素
        else {
            // 判断是否为堆叠面积图 如果是则将pathinfos逆序
            if (chart.type == ChartType.AREA_OVERLAP_CHART) {
                Collections.reverse(chart.pathInfos);
            }
            // 判断是否为堆叠柱状图 如果是则将pathinfos逆序
            boolean bStackedBar = ChartPathInfosParser.isChartStackedBar(chart);
            if (bStackedBar) {
                chart.pathInfos = reverseBarPathInfo(chart.pathInfos);
                //Collections.reverse(chart.pathInfos);
            }
            chart.pathInfos = resetLinePathInfoOrder(chart.pathInfos);
            boolean hasData = false;
            for (int i = 0; i < chart.pathInfos.size(); i++) {
                ChartPathInfo pathInfo = chart.pathInfos.get(i);
                if (pathInfo.type == PathInfo.PathType.UNKNOWN) {
                    continue;
                }
                JsonObject serie = writePathInfo(pathInfo, scaleQua);
                if (serie == null) {
                    return false;
                }

                // 如果不需要只匹配刻度上方元素
                if (!chart.onlyMatchXScale) {
                    // 尝试将标签类型的每个X轴刻度都赋值
                    addPathNullLabel(chart, pathInfo, scaleQua, serie);

                    // 尝试重置X轴刻度具有重复label的标签的数据
                    resetPathLabel(chart, pathInfo, scaleQua, serie);
                }

                if (!(serie.getAsJsonArray("data").size() == 0)) {
                    hasData = true;
                }
                // 设置显示属性的细节
                String suffix = ChartContentScaleParser.getChartPathInfoSuffix(chart, scaleQua, i);
                JsonObject tooltip = new JsonObject();
                tooltip.addProperty("pointFormat", "{series.name}: {point.y:,.2f}" + suffix);
                serie.add("tooltip", tooltip);
                JsonObject dataLabel = getChartPathInfoDataLabel(chart, scaleQua, i);
                serie.add("dataLabels", dataLabel);
                // 设置堆叠柱状图状态
                if (bStackedBar) {
                    if (pathInfo.type == PathInfo.PathType.BAR ||
                            pathInfo.type == PathInfo.PathType.COLUMNAR) {
                        serie.addProperty("stacking", "normal");
                    }
                }
                // 折线对象统一不显示顶点图标
                if (pathInfo.type == PathInfo.PathType.LINE ||
                        pathInfo.type == PathInfo.PathType.CURVE) {
                    JsonObject markerDoc = new JsonObject();
                    markerDoc.addProperty("enabled", false);
                    serie.add("marker", markerDoc);
                }
                series.add(serie);
            }
            if (!hasData) {
                return false;
            }
        }
        bson.add("series", series);
        return true;
    }

    public JsonObject getAreaThreshold(Chart chart) {
        for (ChartPathInfo pathInfo : chart.pathInfos) {
            if (pathInfo.type == PathInfo.PathType.AREA &&
                    !StringUtils.isEmpty(pathInfo.areaThreshold)) {
                JsonObject thresholdDoc = new JsonObject();
                thresholdDoc.addProperty("threshold", pathInfo.areaThreshold);
                JsonObject areaDoc = new JsonObject();
                areaDoc.add("area", thresholdDoc);
                return areaDoc;
            }
        }
        return null;
    }

    public void writePlotOptionsNew(Chart chart, ChartScaleQuantificationInfo scaleQua) {
        // 常规 plotOptions控制
        writePlotOptions(chart, scaleQua);

        // 添加起始时间控制
        addStartTimeOption(chart, scaleQua);
    }

    public void writePlotOptions(Chart chart, ChartScaleQuantificationInfo scaleQua) {
        // 设置面积图的 threshold
        if (chart.type != ChartType.PIE_CHART) {
            JsonObject areaThresholdDoc = getAreaThreshold(chart);
            if (areaThresholdDoc != null) {
                bson.add("plotOptions", areaThresholdDoc);
            }
            return;
        }

        // 设置饼图的 plotOptions
        JsonObject plotOptionsDoc = new JsonObject();
        JsonObject elementDoc = writePiePlotOptions(chart);
        String name = "pie";
        plotOptionsDoc.add(name, elementDoc);
        bson.add("plotOptions", plotOptionsDoc);
    }

    public JsonObject writePiePlotOptions(Chart chart) {
        JsonObject pieDoc = new JsonObject();
        pieDoc.addProperty("allowPointSelect", true);
        pieDoc.addProperty("cursor", "pointer");

        JsonObject dataLabelsDoc = new JsonObject();
        dataLabelsDoc.addProperty("enabled", true);
        // 如果有显式图例
        if (!chart.legends.isEmpty()) {
            pieDoc.addProperty("showInLegend", true);
            dataLabelsDoc.addProperty("format", "{point.percentage:.2f} %");
        }
        pieDoc.add("dataLabels", dataLabelsDoc);
        JsonObject tooltip = new JsonObject();
        tooltip.addProperty("pointFormat", "{series.name}: {point.percentage:,.2f}%");
        pieDoc.add("tooltip", tooltip);
        return pieDoc;
    }

    /**
     * 计算Chart内指定PathInfo的 dataLabel 属性
     * @param chart
     * @param scaleQua
     * @param ithPath
     * @return
     */
    public JsonObject getChartPathInfoDataLabel(
            Chart chart,
            ChartScaleQuantificationInfo scaleQua,
            int ithPath) {
        JsonObject dataLabelsDoc = new JsonObject();

        // 如果Chart类型为面积图或叠加面积图或曲线图 或 X轴为时间类型且需要细分插值  设置不显示单位
        ChartPathInfo pathInfo = chart.pathInfos.get(ithPath);
        dataLabelsDoc.addProperty("enabled", pathInfo.valueFromNearbyTextInfo);

        // 获得相应的属性后缀
        String suffix = ChartContentScaleParser.getChartPathInfoSuffix(chart, scaleQua, ithPath);
        dataLabelsDoc.addProperty("format", "{point.y:,.f}" + suffix);
        return dataLabelsDoc;
    }

    public JsonObject writeLinePlotOptions(Chart chart, ChartScaleQuantificationInfo scaleQua) {
        // 如果为空
        if (chart.pathInfos.isEmpty()) {
            return null;
        }
        JsonObject lineDoc = new JsonObject();
        lineDoc.addProperty("enableMouseTracking", true);
        JsonObject dataLabelsDoc = getChartPathInfoDataLabel(chart, scaleQua, 0);
        lineDoc.add("dataLabels", dataLabelsDoc);
        return lineDoc;
    }

    /**
     * 输出到html文件中　可以直接用浏览器查看　方便查看结果
     * @param title
     */
    public void writeHtml(String title) {
        // 如果目录不存在　则试图创建
        String dir = "/tmp/charthtmls/";
        File myFolderPath = new File(dir);
        try {
            if (!myFolderPath.exists()) {
               myFolderPath.mkdir();
            }
        }
        catch (Exception e) {
            return;
            //logger.debug("新建目录操作出错");
            //e.printStackTrace();
        }

        // 以当前时间作为 ID 附加标题命名保存的文件名
        Date date = new Date();
        long id = date.getTime();
        String filename = "/tmp/charthtmls/chart." + id;
        if (title != null) {
            if (title.length() >= 8) {
                filename = filename + "." + title.substring(0, 8);
            }
            else {
                filename = filename + "." + title;
            }
        }
        // 保存为 html 模板的内容
        filename = filename + ".html";
        // 尝试输出为　html 文件
        File myFilePath = new File(filename);
        ChartUtils.saveChartToHTML(bson, myFilePath);
    }
}