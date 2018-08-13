package com.abcft.pdfextract.core.office;

import com.abcft.pdfextract.core.office.OfficeDataFormat.Type;
import com.google.gson.JsonArray;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.poi.POIXMLDocument;
import org.apache.poi.POIXMLDocumentPart;
import org.apache.poi.hssf.usermodel.HSSFChart;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellAddress;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellRangeAddressBase;
import org.apache.poi.util.Units;
import org.apache.poi.xddf.usermodel.XDDFShapeProperties;
import org.apache.poi.xddf.usermodel.chart.*;
import org.apache.poi.xslf.usermodel.XSLFChart;
import org.apache.poi.xslf.usermodel.XSLFColor;
import org.apache.poi.xssf.usermodel.XSSFChart;
import org.apache.poi.xssf.usermodel.XSSFRichTextString;
import org.apache.poi.xwpf.usermodel.XWPFChart;
import org.apache.poi.xwpf.usermodel.XWPFTheme;
import org.apache.xmlbeans.XmlCursor;
import org.apache.xmlbeans.XmlObject;
import org.openxmlformats.schemas.drawingml.x2006.chart.*;
import org.openxmlformats.schemas.drawingml.x2006.main.CTGraphicalObjectData;
import org.openxmlformats.schemas.drawingml.x2006.main.CTShapeProperties;

import javax.xml.namespace.QName;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DateFormat;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.regex.Matcher;


class XDDFChartUtils {
    private static final Logger logger = LogManager.getLogger();
    /**
     * highchart数据集零界点
     */
    private static int limit = 500;
    /**
     * highchart数据集标准数
     */
    private static int standard = 500;





    /**
     * data工具类会导致线程不安全,需要从本地变量中获取
     */
    private static ThreadLocal<Map<Integer, DateFormat>> dateFormat = ThreadLocal.withInitial(() -> {
        HashMap<Integer, DateFormat> map = new HashMap<>();
        map.put(1, new SimpleDateFormat("yyyy/MM/dd"));
        map.put(2, new SimpleDateFormat("MM/dd"));
        map.put(3, new SimpleDateFormat("dd"));
        return map;
    });

    /**
     * 标题搜索(搜索范围前2行,列的前1列开始)
     *
     * @param sheet
     * @param row
     * @param col
     * @return
     */
    public static String searchTitle(Sheet sheet, int row, int col, int col1) {
        for (int i = row; i > row - 3; i--) {
            for (int j = col - 1; j <= col1; j++) {
                if (i >= 0 && j >= 0) {
                    Row rowData = sheet.getRow(i);
                    if (rowData != null && rowData.getCell(j) != null) {
                        Object value = XDDFChartUtils.getCellValue(rowData.getCell(j));
                        if (value != null) {
                            return value.toString();
                        }
                    }
                }

            }
        }
        return "";
    }

    private static JsonArray zip(JsonArray cat, JsonArray data) {
        JsonArray array = new JsonArray();
        int size = cat.size();
        for (int i = 0; i < size; i++) {
            JsonArray catAndData = new JsonArray();
            if (null == data.get(i)) {
                continue;
            }
            catAndData.add(cat.get(i));
            if (i > data.size()) {
                break;
            }
            catAndData.add(data.get(i));
            array.add(catAndData);
        }
        return array;
    }

    private static Pair<JsonArray, JsonArray> sort(JsonArray jsonArray, JsonArray data1, String df) {
        JsonArray cat = new JsonArray();
        JsonArray data = new JsonArray();
        if (jsonArray.size() > 1) {
            String formt = jsonArray.get(0).getAsString();
            Matcher matcher = OfficeRegex.DATE_FORMAT_PATTERN.matcher(formt);
            if (matcher.find()) {
                String s = formt.replaceAll("/|-", "");
                int i0 = 0;
                int i1 = 0;
                try {
                    i0 = Integer.parseInt(s);
                    i1 = Integer.parseInt(jsonArray.get(jsonArray.size() - 1).getAsString().replaceAll("/|-", ""));

                } catch (NumberFormatException e) {
                    return Pair.of(jsonArray,data1);
                }
                // 比较 索引为0,1的(一般catagory都是有顺序的)
                if (i0 > i1) {
                    // 逆序
                    int size = jsonArray.size();
                    for (int i = size - 2; i >= 0; i--) {
                        try {
                            cat.add(jsonArray.get(i));
                            data.add(data1.get(i));
                        } catch (Exception e) {
                            continue;
                        }
                    }
                } else {
                    cat = jsonArray;
                    data = data1;
                }
            } else {
                cat = jsonArray;
                data = data1;
            }
        } else {
            cat = jsonArray;
            data = data1;
        }
        JsonArray cat1 = new JsonArray();
        if (!"".equals(df)) {
            DateFormat dateFormat = new SimpleDateFormat(df);
            for (JsonElement c : cat) {
                cat1.add(dateFormat.format(Date.parse(c.getAsString())));
            }
        } else {
            cat1 = cat;
        }
        return Pair.of(cat1, data);
    }

    static JsonObject handleHSSFChart(HSSFChart chart, Sheet sheet) {
        JsonObject jsonChart = new JsonObject();
        String title = chart.getChartTitle();
        JsonObject jsonTitle = new JsonObject();
        JsonObject jsonXAxis = new JsonObject();
        JsonArray jsonYAxis = new JsonArray();
        JsonArray jsonSeries = new JsonArray();
        jsonTitle.addProperty(HighChartModel.TEXT, title == null ? "" : title);
        JsonObject time = new JsonObject();
        time.addProperty("useUTC", true);
        time.addProperty("timezone", "Asia/Shanghai");
        jsonChart.add("time", time);
        JsonObject credits = new JsonObject();
        credits.addProperty("enabled", false);
        jsonChart.add("credits", credits);
        HSSFChart.HSSFChartType type = chart.getType();
        int count = 0;
        HSSFChart.HSSFSeries[] series = chart.getSeries();
        //TODO xls 只能通过数据预估坐标的数量
        double sum = 0;
        for (HSSFChart.HSSFSeries se : series) {
            //poi获取excel图表的值区域
            JsonObject jsonSerie = new JsonObject();
            switch (type) {
                case Bar:
                    jsonSerie.addProperty(HighChartModel.TYPE, HighChartModel.HighChartType.COLUMN);
                    break;
                case Line:
                    jsonSerie.addProperty(HighChartModel.TYPE, HighChartModel.HighChartType.LINE);
                    break;
                case Area:
                    jsonSerie.addProperty(HighChartModel.TYPE, HighChartModel.HighChartType.AREA);
                    break;
                case Pie:
                    jsonSerie.addProperty(HighChartModel.TYPE, HighChartModel.HighChartType.PIE);
                    break;
                case Scatter:
                    jsonSerie.addProperty(HighChartModel.TYPE, HighChartModel.HighChartType.SCATTER);
                    break;
                default:
                    logger.error("Unknown  type: {}", type);
            }
            JsonArray jsonArray = new JsonArray();
            JsonArray cat = new JsonArray();
            JsonArray data1 = new JsonArray();
            JsonArray data = new JsonArray();
            String seriesTitle = se.getSeriesTitle();
            double serieData = 0;
            //poi获取excel图表category区域
            CellRangeAddressBase categoryRange = se.getCategoryLabelsCellRange();

            int colIndex = 0;
            int rowIndex = 0;
            for (CellAddress cellAddress : se.getValuesCellRange()) {
                colIndex = cellAddress.getColumn();
                rowIndex = cellAddress.getRow();
                break;
            }
            String text = jsonTitle.get("text").getAsString();
            if ("".equals(text)) {
                while (rowIndex >= 0) {
                    rowIndex--;
                    Map<String, String> map = isMergedRegion(sheet, rowIndex, colIndex - 1);
                    if (map.get("Result").equals("true")) {
                        Row row = sheet.getRow(rowIndex);
                        Cell cell = row.getCell(colIndex - 1);
                        int span = Integer.parseInt(map.get("ColSpan")) - series.length;
                        if (cell != null && span >= 0) {
                            jsonTitle.addProperty(HighChartModel.TEXT, cell.getStringCellValue() == null ? "" : cell.getStringCellValue());
                            break;
                        }
                    }
                }
            }
            // 当series为1的时可能是标题
            if (series.length == 1 && !type.equals(HSSFChart.HSSFChartType.Pie) && (title == null || "".equals(title))) {
                jsonTitle.addProperty(HighChartModel.TEXT, seriesTitle == null ? "" : seriesTitle);
            }
            jsonSerie.addProperty(HighChartModel.NAME, seriesTitle);
            for (CellAddress cellAddress : categoryRange) {
                Number cellValue = null;
                String format = null;
                try {
                    Row row = sheet.getRow(cellAddress.getRow());
                    Cell cell = row.getCell(cellAddress.getColumn());
                    Object o = getCellValue(cell);
                    format = (String) o;
                    Cell cell1 = row.getCell(colIndex);
                    Object value = null;
                    if (cell1 != null) {
                        value = getCellValue(cell1);
                    }
                    cellValue = (Number) value;
                } catch (Exception e) {
                    continue;
                }
                jsonArray.add(format);
                data1.add(cellValue);
                if (cellValue == null) {
                    cellValue = 0;
                }
                serieData += cellValue.doubleValue();
            }
            Pair<JsonArray, JsonArray> sort = sort(jsonArray, data1, "");
            cat = sort.getLeft();
            data = sort.getRight();
            //TODO 的逻辑,如何判断多坐标啊
            if (sum >= 10 * serieData / data.size() || sum <= serieData / data.size() / 10) {
                JsonObject jsonYa = new JsonObject();
                JsonObject label = new JsonObject();
                jsonSerie.addProperty(HighChartModel.YA, count);
                count = count + 1;
                if (count % 2 == 0) {
                    jsonYa.addProperty("opposite", true);
                }
                JsonObject titleY = new JsonObject();
                titleY.addProperty(HighChartModel.TEXT, seriesTitle);
                label.addProperty(HighChartModel.FORMAT, "{value} ");
                jsonYa.add(HighChartModel.LABELS, label);
                if (!jsonYa.isJsonNull()) {
                    jsonYAxis.add(jsonYa);
                }
            }
            sum = serieData / (data.size());
            JsonArray array = zip(cat, data);
            Pair<JsonArray, JsonArray> pair = wrapperData(cat, array, limit, standard);
            if (cat.size() > 0) {
                jsonXAxis.add(HighChartModel.CATEGORIES, pair.getLeft());
                jsonSerie.add(HighChartModel.DATA, pair.getRight());
            }
            jsonSeries.add(jsonSerie);
        }
        jsonChart.add(HighChartModel.TITLE, jsonTitle);
        jsonChart.add(HighChartModel.XA, jsonXAxis);
        if (jsonYAxis.size() != 0) {
            jsonChart.add(HighChartModel.YA, jsonYAxis);
        }
        jsonChart.add(HighChartModel.SERIES, jsonSeries);
        return jsonChart;
    }

    private static void handleAxis(XDDFChart chart, JsonArray jsonYAxis, Map<Long, Map<String, Object>> maps, String dateFomart) {
        String format = "";
        String xFomart = "";
        for (XDDFChartAxis axis : chart.getAxes()) {
            Map<String, Object> map = new HashMap<>();
            if (axis instanceof XDDFDateAxis) {
                if (!dateFomart.equals("")) {
                    continue;
                }
                XDDFDateAxis a = (XDDFDateAxis) axis;
                dateFomart = a.getNumberFormat();
                map.put("position", a.getPosition());
                map.put("format", xFomart);
                maps.put(a.getId(), map);
            }
            if (axis instanceof XDDFValueAxis) {
                XDDFValueAxis a = (XDDFValueAxis) axis;
                CTValAx ctValAx = a.getCtValAx();
                format = a.getNumberFormat();
                if ("general".equalsIgnoreCase(format)) {
                    format = "";
                } else if (format.contains("%")) {
                    format = "%";
                } else {
                    format = "";
                }
                String yTitle = "";
                if (null != ctValAx.getTitle()) {
                    yTitle = ctValAx.getTitle().newCursor().getTextValue();
                }

                map.put("position", a.getPosition());
                JsonObject jsonYa = new JsonObject();
                JsonObject label = new JsonObject();
                JsonObject titleY = new JsonObject();
                switch (a.getPosition()) {
                    case BOTTOM:
                        break;
                    case LEFT:
                        titleY.addProperty(HighChartModel.TEXT, yTitle);
                        label.addProperty(HighChartModel.FORMAT, "{value}" + format);
                        jsonYa.add(HighChartModel.LABELS, label);
                        jsonYa.add(HighChartModel.TITLE, titleY);
                        break;
                    case RIGHT:
                        titleY.addProperty(HighChartModel.TEXT, yTitle);
                        jsonYa.add(HighChartModel.LABELS, label);
                        jsonYa.add(HighChartModel.TITLE, titleY);
                        label.addProperty(HighChartModel.FORMAT, "{value}" + format);
                        jsonYa.addProperty("opposite", true);

                        break;
                    case TOP:
                        break;
                }
                jsonYAxis.add(jsonYa);
                map.put("format", format);
                map.put("title", yTitle);
                maps.put(a.getId(), map);
            }
            if (axis instanceof XDDFSeriesAxis) {
                logger.info("TODO:");
            }
        }

    }

    private static void handleChartType(XDDFChartData chartData, XDDFChartData.Series ser, JsonObject jsonSerie) {
        if (chartData instanceof XDDFBarChartData) {
            jsonSerie.addProperty(HighChartModel.TYPE, HighChartModel.HighChartType.COLUMN);
        } else if (chartData instanceof XDDFLineChartData) {
            CTLineSer lineSer = (CTLineSer) ser.getSeries();
            boolean smooth = lineSer.isSetSmooth() && lineSer.getSmooth().getVal();
            jsonSerie.addProperty(HighChartModel.TYPE, smooth ? HighChartModel.HighChartType.SMOOTH_LINE : HighChartModel.HighChartType.LINE);
        } else if (chartData instanceof XDDFPieChartData) {
            jsonSerie.addProperty(HighChartModel.TYPE, HighChartModel.HighChartType.PIE);
        } else if (chartData instanceof XDDFRadarChartData) {
            jsonSerie.addProperty(HighChartModel.TYPE, HighChartModel.HighChartType.POLAR);
        } else if (chartData instanceof XDDFScatterChartData) {
            jsonSerie.addProperty(HighChartModel.TYPE, HighChartModel.HighChartType.SCATTER);
        } else {
            logger.error("Unknown XDDFChartData type: {}", chartData.getClass());
        }

    }

    private static Pair<Map<String, JsonArray>, Boolean> handleCatAndData(XDDFChartData.Series ser, String dateFomart, JsonObject jsonXAxis) {
        XDDFDataSource category = ser.getCategoryData();
        XDDFNumericalDataSource values = ser.getValuesData();
        JsonArray data = new JsonArray();
        JsonArray cat = new JsonArray();
        boolean pflag = false;
        int count = 0;
        if (category != null) {
            count = category.getPointCount();
        } else {
            count = values.getPointCount();
        }
        for (int i = 0; i < count; i++) {
            JsonArray jsonArray = new JsonArray();
            try {
                if (category != null) {
                    Object var = category.getPointAt(i);
                    if (category instanceof XDDFNumericalDataSource && ((XDDFNumericalDataSource) category).isDate()) {
                        String formatCode = ((XDDFNumericalDataSource) category).getFormatCode();
                        dateFomart = dateFomart.equals("") ? !formatCode.equals("") ? OfficeDataFormat.tansferJavaDateFormat(formatCode) : dateFomart : dateFomart;
                        var = DateUtil.getJavaDate((Double) var);
                        var = dateFormat((Date) var, formatCode);
                        jsonXAxis.addProperty("type", "datetime");
                    } else if (category instanceof XDDFNumericalDataSource) {
                        String formatCode = ((XDDFNumericalDataSource) category).getFormatCode();
                        dateFomart = dateFomart.equals("") ? !formatCode.equals("") ? OfficeDataFormat.tansferJavaDateFormat(formatCode) : dateFomart : dateFomart;
                        String s = OfficeDataFormat.tansferJavaDateFormat(formatCode);
                        if (!s.equals("")) {
                            var = DateUtil.getJavaDate((Double) var);
                            var = dateFormat((Date) var, formatCode);
                        } else {
                            var = ((Double) var).intValue();
                        }
                    } else {
                        jsonXAxis.addProperty("type", "category");
                    }
                    jsonArray.add(String.valueOf(var));
                    cat.add(String.valueOf(var));
                } else {
                    jsonArray.add("");
                }
                String formatCode = values.getFormatCode();
                Object pointAt = null;
                CTNumData values1 = values.getValues();
                List<CTNumVal> ptList = values1.getPtList();
                int pointCount = count;
                boolean f = false;
                if (pointCount == ptList.size()) {
                    pointAt = values.getPointAt(i);
                    f = true;
                } else {
                    for (CTNumVal ctNumVal : ptList) {
                        long idx = ctNumVal.getIdx();
                        String v = ctNumVal.getV();
                        if (idx == i && !(null == v)) {
                            if (!v.equals("#N/A")) {
                                pointAt = Double.parseDouble(v);
                                f = true;
                            }
                        }
                    }
                }
                if (f) {
                    Number v = null;
                    if (pointAt instanceof Number) {
                        v = (Number) pointAt;
                    }
                    if (formatCode.endsWith("0.00%") || formatCode.endsWith("0.0%")) {
                        v = new BigDecimal(v.doubleValue() * 100).doubleValue();
                        pflag = true;
                    } else if (formatCode.endsWith("0%")) {
                        v = new BigDecimal(v.doubleValue() * 100).intValue();
                        pflag = true;
                    } else if (formatCode.endsWith("%")) {
                        v = new BigDecimal(v.doubleValue() * 100).doubleValue();
                        pflag = true;
                    } else {
                        v = new BigDecimal(v.doubleValue()).doubleValue();
                        pflag = false;
                    }
                    jsonArray.add(v);
                }

            } catch (IndexOutOfBoundsException ignored) {
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                if (jsonArray.size() == 2) {
                    data.add(jsonArray);
                }

            }

        }
        Map<String, JsonArray> maps = new HashMap<>();
        maps.put("cat", cat);
        maps.put("data", data);
        return Pair.of(maps, pflag);
    }

    static JsonObject handleXDDFChart(XDDFChart chart, XWPFTheme theme) {
        JsonObject jsonChart = null;
        try {
            jsonChart = new JsonObject();
            JsonArray categories = new JsonArray();
            JsonObject jsonXAxis = new JsonObject();
            JsonArray jsonYAxis = new JsonArray();
            JsonArray jsonSeries = new JsonArray();
            JsonObject tooltip = null;
            JsonObject time = new JsonObject();
            time.addProperty("useUTC", true);
            time.addProperty("timezone", "Asia/Shanghai");
            jsonChart.add("time", time);
            JsonObject credits = new JsonObject();
            credits.addProperty("enabled", false);
            jsonChart.add("credits", credits);
            List<XDDFChartData> chartSeries = chart.getChartSeries();
            //判断是否是双y轴 单y轴是 bottom和left,双外轴是bottom,left,right
            String dateFomart = "";
            Map<Long, Map<String, Object>> maps = new HashMap<>();
            String format = "";
            handleAxis(chart, jsonYAxis, maps, dateFomart);
            dateFomart = OfficeDataFormat.tansferJavaDateFormat(dateFomart);
            for (XDDFChartData chartData : chartSeries) {
                JsonObject jsonSerie = null;
                List<XDDFChartData.Series> series = chartData.getSeries();
                for (XDDFChartData.Series ser : series) {
                    jsonSerie = new JsonObject();
                    handleChartType(chartData, ser, jsonSerie);
                    if (chartData instanceof XDDFPieChartData) {
                        tooltip = new JsonObject();
                        tooltip.addProperty("pointFormat", "{series.name}:<b>{point.percentage:.1f}%</b>");
                        JsonObject plotOptions = new JsonObject();
                        JsonObject pie = new JsonObject();
                        JsonObject dataLabels = new JsonObject();
                        dataLabels.addProperty("enabled", true);
                        dataLabels.addProperty("format", "<b>{point.name}</b>:{point.percentage:.1f}%");
                        pie.add("dataLabels", dataLabels);
                        plotOptions.add("pie", pie);
                        jsonChart.add(HighChartModel.PLOT_OPTIONS, plotOptions);
                    }
                    Pair<Map<String, JsonArray>, Boolean> m = handleCatAndData(ser, dateFomart, jsonXAxis);
                    Pair<JsonArray, JsonArray> sort = sort(m.getLeft().get("cat"), m.getLeft().get("data"), "");
                    categories = sort.getLeft().size() > categories.size() ? sort.getLeft() : categories;
                    JsonArray data = sort.getRight();
                    // 数据处理(因为数据量太大导致highchart渲染失败)
                    Pair<JsonArray, JsonArray> pair = wrapperData(categories, data, limit, standard);
                    String title1 = "  ";
                    try {
                        //可能会抛出空指针异常(内部会导致空指针异常)
                        title1 = ser.getTitle();
                    } catch (NullPointerException e) {
                        //TODO
                        title1 = " ";
                    }
                    List<XDDFValueAxis> valueAxes = chartData.getValueAxes();
                    if (valueAxes != null && valueAxes.size() > 0) {
                        // 坐標保存
                        for (XDDFValueAxis axis : valueAxes) {
                            long id = axis.getId();
                            Map<String, Object> map = maps.get(id);
                            AxisPosition position = (AxisPosition) map.get("position");
                            switch (position) {
                                case TOP:
                                    break;
                                case LEFT:
                                    jsonSerie.addProperty(HighChartModel.YA, 0);
                                    break;
                                case RIGHT:
                                    jsonSerie.addProperty(HighChartModel.YA, 1);
                                    break;
                                case BOTTOM:
                                    break;
                            }
                        }
                    }
                    if (m.getRight()) {
                        JsonObject tooltip1 = new JsonObject();
                        tooltip1.addProperty("valueSuffix", "%");
                        jsonSerie.add(HighChartModel.TOOLTIP, tooltip1);
                    }
//                    JsonObject labels = new JsonObject();
//                    labels.addProperty("formatter","function(){Highcharts.dateFormat("+dateFomart+")}");

                    jsonXAxis.addProperty("type", "category");
                    jsonXAxis.add(HighChartModel.CATEGORIES, pair.getLeft());
                    jsonSerie.addProperty(HighChartModel.NAME, title1);
                    jsonSerie.add(HighChartModel.DATA, pair.getRight());
                    jsonSeries.add(jsonSerie);
                }
            }
            if (null != tooltip) {
                jsonChart.add("tooltip", tooltip);
            }
            String title = getTitleText(chart);
            JsonObject jsonTitle = new JsonObject();
            jsonChart.add(HighChartModel.XA, jsonXAxis);
            if (jsonYAxis.size() != 0) {
                jsonChart.add(HighChartModel.YA, jsonYAxis);
            }
            if (jsonSeries.size() == 0) {
                return null;
            }
            if (jsonSeries.size() == 1) {
                String name = jsonSeries.get(0).getAsJsonObject().get("name").getAsString();
                if ("".equals(name.trim())) {
                    JsonObject legend = new JsonObject();
                    legend.addProperty("enabled", false);
                    jsonChart.add("legend", legend);
                }
            }
            jsonTitle.addProperty(HighChartModel.TEXT, title);
            jsonChart.add(HighChartModel.TITLE, jsonTitle);
            jsonChart.add(HighChartModel.SERIES, jsonSeries);
        } catch (Exception e) {
            logger.info("internal error {}", e);
        }
        return jsonChart;
    }

    /**
     * 对highchart的数据集进行处理(压缩)
     *
     * @param categories x轴
     * @param data       数据集
     * @param limit      限制点
     * @param standard   标准点
     */
    private static Pair<JsonArray, JsonArray> wrapperData(JsonArray categories, JsonArray data, int limit, int standard) {
        //判断点数是否超过limit
        int size1 = categories.size();
        int size = data.size();
        if (size1 > size) {
            size1 = size;
        }
        JsonArray cat = new JsonArray();
        JsonArray dat = new JsonArray();
        if (size1 >= limit) {
            int n = size1 / standard;
            for (int i = 0; i < size1 - 1; i = i + n) {
                if (i >= size1 - 1) break;
                cat.add(categories.get(i));
                dat.add(data.get(i));
            }
            categories = cat;
            data = dat;
        }
        return Pair.of(categories, data);

    }


    public static XWPFTheme getTheme(POIXMLDocument document) {
        if (document == null) {
            return null;
        }
        for (POIXMLDocumentPart relation : document.getRelations()) {
            if (relation.getPackagePart().getContentType().equals("application/vnd.openxmlformats-officedocument.theme+xml")) {
                try {
                    return new XWPFTheme(relation.getPackagePart());
                } catch (Exception e) {
                    logger.debug("Create XWPFTheme failed", e);
                }
            }
        }
        return null;
    }

    public static Object getCellValue(Cell cell) {
        CellType cellType = cell.getCellType();
        switch (cellType) {
            case STRING:
                return cell.getStringCellValue();
            case NUMERIC:
                return getNumericCellValue(cell);
            case BOOLEAN:
                return cell.getBooleanCellValue();
            case BLANK:
                return "";
            case FORMULA:
                return getFormulaCellValue(cell, cell.getCachedFormulaResultType());
            case ERROR:
                return cell.getErrorCellValue();
        }
        return null;
    }

    public static String format(double value, int num) {
        NumberFormat nf = NumberFormat.getNumberInstance();
        nf.setMaximumFractionDigits(num);
        nf.setRoundingMode(RoundingMode.HALF_UP);
        nf.setGroupingUsed(false);
        return nf.format(value);
    }


    private static Object getFormulaCellValue(Cell cell, CellType cellType) {
        switch (cellType) {
            case STRING:
                return cell.getStringCellValue();
            case NUMERIC:
                return getNumericCellValue(cell);
            case BOOLEAN:
                return cell.getBooleanCellValue();
            case BLANK:
                return "";
            case ERROR:
                return cell.getErrorCellValue();
        }
        return null;
    }

    private static Object getNumericCellValue(Cell cell) {
        double value = cell.getNumericCellValue();
        CellStyle cellStyle = cell.getCellStyle();
        short format = cellStyle.getDataFormat();
        Pair<java.lang.String, Enum> formt = OfficeDataFormat.dataFormt(format);
        if (DateUtil.isCellDateFormatted(cell)) {
            String dataFormat = cellStyle.getDataFormatString();
            return dateFormat(cell.getDateCellValue(), dataFormat);
        } else if (formt.getRight() == Type.Date) {
            DateFormat df = new SimpleDateFormat(formt.getLeft());
            return df.format(DateUtil.getJavaDate(value));
        } else {
            String dataFormat = cellStyle.getDataFormatString();
            Matcher matcher = OfficeRegex.DOUBLE_PATTERN.matcher(dataFormat);
            String effectiveDataFormat = "";
            int decimalPlaceNum = 2;
            String s = String.valueOf(value);
            if(StringUtils.equalsIgnoreCase(dataFormat, "general")){
                DecimalFormat df = new DecimalFormat("#");
                return Long.parseLong(df.format(value));
            }
            boolean scale = false;
            if (matcher.find()) {
                effectiveDataFormat = matcher.group(0);
                scale = StringUtils.contains(effectiveDataFormat, ".");
                if (scale) {
                    if(StringUtils.endsWithAny(dataFormat.trim(),"_")){
                        return new DecimalFormat(effectiveDataFormat).format(value);
                    }
                    String[] ss = effectiveDataFormat.split("\\.");

                    decimalPlaceNum = ss.length == 2 ? ss[1].length() : decimalPlaceNum;
                }
            }
            if (StringUtils.contains(dataFormat, "%")) {
                return Double.parseDouble(format(value * 100, decimalPlaceNum - 2));
            }

            if (!scale) {
                DecimalFormat df = new DecimalFormat("#");
                return Long.parseLong(df.format(value));
            }
            return Double.parseDouble(format(value, decimalPlaceNum));
        }
    }

    private static String getTitleText(XDDFChart chart) {
        XSSFRichTextString titleText = null;
        if (chart instanceof XSLFChart) {
            return ((XSLFChart) chart).getTitle().getText();
        } else if (chart instanceof XSSFChart) {
            titleText = ((XSSFChart) chart).getTitleText();
        } else if (chart instanceof XWPFChart) {
            titleText = ((XWPFChart) chart).getTitleText();
        }
        if (titleText != null) {
            return titleText.getString();
        }
        return "";
    }

    private static Object dateFormat(Date date, String format) {
        if (format.toLowerCase().contains("y")) {
            return dateFormat.get().get(1).format(date);
        } else if (format.toLowerCase().contains("m")) {
            return dateFormat.get().get(2).format(date);

        }
        return dateFormat.get().get(3).format(date);

    }

    public static String xgetRelationId(CTGraphicalObjectData graphicalObjectData) {
        String uri = graphicalObjectData.getUri();
        if (uri.equals("http://schemas.openxmlformats.org/drawingml/2006/chart")) {
            String xpath = "declare namespace c='http://schemas.openxmlformats.org/drawingml/2006/chart' c:chart";
            XmlObject[] obj = graphicalObjectData.selectPath(xpath);
            if (obj == null || obj.length != 1) {
                return null;
            }
            XmlCursor c = obj[0].newCursor();
            QName idQualifiedName = new QName("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id");
            return c.getAttributeText(idQualifiedName);
        } else if (uri.equals("http://schemas.openxmlformats.org/drawingml/2006/picture")) {
            String xpath = "declare namespace pic='http://schemas.openxmlformats.org/drawingml/2006/picture' pic:pic";
            XmlObject[] obj = graphicalObjectData.selectPath(xpath);
            if (obj == null || obj.length != 1) {
                return null;
            }
            XmlCursor c = obj[0].newCursor();
            QName blipFillQName = new QName("http://schemas.openxmlformats.org/drawingml/2006/picture", "blipFill");
            if (!c.toChild(blipFillQName)) {
                return null;
            }
            QName blipQName = new QName("http://schemas.openxmlformats.org/drawingml/2006/main", "blip");
            if (!c.toChild(blipQName)) {
                return null;
            }
            QName idQualifiedName = new QName("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "embed");
            return c.getAttributeText(idQualifiedName);
        } else if (uri.equals("http://schemas.openxmlformats.org/presentationml/2006/ole")) {
            String xpath = "declare namespace p='http://schemas.openxmlformats.org/presentationml/2006/main' p:oleObj";
            XmlObject[] obj = graphicalObjectData.selectPath(xpath);
            if (obj == null || obj.length != 1) {
                return null;
            }
            XmlCursor c = obj[0].newCursor();
            QName idQualifiedName = new QName("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id");
            return c.getAttributeText(idQualifiedName);
        } else if (uri.equals("http://schemas.openxmlformats.org/drawingml/2006/diagram")) {
            // TODO: ignore diagram for now
            logger.warn("Ignore diagram");
            return null;
        } else {
            // TODO  support other types of objects
            logger.warn("Do NOT support scheme {}", uri);
            return null;
        }
    }

    private static Pair<Double, Double> xgetPictureSize(CTGraphicalObjectData graphicalObjectData) {
        String uri = graphicalObjectData.getUri();
        if (uri.equals("http://schemas.openxmlformats.org/drawingml/2006/picture")) {
            String xpath = "declare namespace pic='http://schemas.openxmlformats.org/drawingml/2006/picture' pic:pic";
            XmlObject[] obj = graphicalObjectData.selectPath(xpath);
            if (obj != null && obj.length == 1) {
                XmlCursor c = obj[0].newCursor();
                QName spPrQName = new QName("http://schemas.openxmlformats.org/drawingml/2006/picture", "spPr");
                if (!c.toChild(spPrQName)) {
                    return null;
                }
                QName xfrmQName = new QName("http://schemas.openxmlformats.org/drawingml/2006/main", "xfrm");
                if (!c.toChild(xfrmQName)) {
                    return null;
                }
                QName extQName = new QName("http://schemas.openxmlformats.org/drawingml/2006/main", "ext");
                if (!c.toChild(extQName)) {
                    return null;
                }
                String cx = c.getAttributeText(new QName("cx"));
                String cy = c.getAttributeText(new QName("cy"));
                return Pair.of(Units.toPoints(Long.parseLong(cx)), Units.toPoints(Long.parseLong(cy)));
            }
        } else if (uri.equals("http://schemas.openxmlformats.org/drawingml/2006/diagram")) {
            // TODO: ignore diagram for now
            logger.debug("grepPictureSize: Ignore diagram");
        } else {
            // TODO  support other types of objects
            logger.debug("grepPictureSize: Do NOT support scheme {}", uri);
        }
        return null;
    }

    public static Pair<Double, Double> getPictureSize(CTGraphicalObjectData graphicalObjectData) {
        try {
            return xgetPictureSize(graphicalObjectData);
        } catch (Exception e) {
            logger.error("getPictureSize ERROR");
            return null;
        }
    }

    public static POIXMLDocumentPart handleCTGraphicObjectData(CTGraphicalObjectData graphic, POIXMLDocumentPart documentPart) {
        String relationId = xgetRelationId(graphic);
        if (StringUtils.isNotEmpty(relationId)) {
            return documentPart.getRelationById(relationId);
        }
        return null;
    }

    private static java.awt.Color getColor(CTShapeProperties properties, XWPFTheme theme) {
        return getColor(new XDDFShapeProperties(properties), theme);
    }

    private static java.awt.Color getColor(XDDFShapeProperties properties, XWPFTheme theme) {
        if (properties == null) {
            return null;
        }
        try {
            if (properties.getFillProperties() != null) {
                XSLFColor color = new XSLFColor(properties.getFillProperties().getXmlObject(), theme, null);
                return color.getColor();
            } else if (properties.getLineProperties() != null) {
                XSLFColor color = new XSLFColor(properties.getLineProperties().getFillProperties().getXmlObject(), theme, null);
                return color.getColor();
            }
        } catch (Exception ignore) {
            logger.debug("failed to get color from: {}", properties);
        }
        return null;
    }

    /**
     * 判断指定的单元格是否是合并单元格
     *
     * @param sheet
     * @param row    行下标
     * @param column 列下标
     * @return
     */
    public static Map<String, String> isMergedRegion(Sheet sheet, int row, int column) {

        Map<String, String> map = new HashMap<String, String>();
        //判断结果
        String flag = "false";
        //当前行
        String r = "-1";
        //当前列
        String c = "-1";
        //最大row
        String mr = row + "";
        map.put("Row", r);
        map.put("Col", c);
        map.put("Result", flag);
        map.put("MaxRow", mr);
        // 得到一个sheet中有多少个合并单元格
        int sheetMergeCount = sheet.getNumMergedRegions();
        for (int i = 0; i < sheetMergeCount; i++) {
            // 获取合并后的单元格
            CellRangeAddress range = sheet.getMergedRegion(i);
            int firstColumn = range.getFirstColumn();
            int lastColumn = range.getLastColumn();
            int firstRow = range.getFirstRow();
            int lastRow = range.getLastRow();
            if (row >= firstRow && row <= lastRow) {
                if (column >= firstColumn && column <= lastColumn) {
                    flag = "true";
                    map.put("Result", flag);
                    map.put("ColSpan", (lastColumn - firstColumn + 1) + "");
                    return map;
                }
            }
        }
        return map;
    }


    public static void main(String[] args) {
        String s = "General####0.0000#";
        Matcher matcher = OfficeRegex.DOUBLE_PATTERN.matcher(s);
        if (matcher.find()) {
            System.out.println(matcher.group(0));
        }
    }
}