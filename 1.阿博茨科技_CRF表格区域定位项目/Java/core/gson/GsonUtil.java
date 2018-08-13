package com.abcft.pdfextract.core.gson;

import com.abcft.pdfextract.core.ExtractContext;
import com.abcft.pdfextract.core.chart.Chart;
import com.abcft.pdfextract.core.table.ContentGroupPage;
import com.abcft.pdfextract.core.table.TableRegion;
import com.abcft.pdfextract.core.table.detectors.RulingTableRegionsDetectionAlgorithm;
import com.abcft.pdfextract.spi.ChartType;
import com.abcft.pdfextract.core.model.Rectangle;
import com.abcft.pdfextract.core.table.Table;
import com.abcft.pdfextract.core.util.BsonUtil;
import com.abcft.pdfextract.core.util.FileId;
import com.abcft.pdfextract.core.util.GraphicsUtil;
import com.abcft.pdfextract.util.JsonUtil;
import com.google.common.collect.Sets;
import com.google.gson.*;
import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.bson.Document;

import java.awt.Color;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.io.File;

import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Created by dhu on 2018/1/6.
 */
public class GsonUtil {

    private GsonUtil() {}

    // 将Json数据解析成相应的映射对象
    public static <T> T parseJsonWithGson(String jsonData, Class<T> type) {
        Gson gson = new Gson();
        return gson.fromJson(jsonData, type);
    }

    // 将Json数据解析成相应的映射对象
    public static <T> T parseJsonWithGson(JsonReader jsonData, Class<T> type) {
        Gson gson = new Gson();
        return gson.fromJson(jsonData, type);
    }

    // 将Json数组解析成相应的映射对象列表
    public static <T> ArrayList<T> parseJsonArrayWithGson(String jsonData,
                                                          Class<T> type) {
        Gson gson = new Gson();
        return gson.fromJson(jsonData, new TypeToken<ArrayList<T>>() {
        }.getType());
    }

    // 将Json数组解析成相应的映射对象列表
    public static <T> ArrayList<T> parseJsonArrayWithGson(JsonReader jsonData,
                                                          Class<T> type) {
        Gson gson = new Gson();
        return gson.fromJson(jsonData, new TypeToken<ArrayList<T>>() {
        }.getType());
    }

    public static void writeRectangle2DValue(JsonWriter writer, Rectangle2D rect) throws IOException {
        writer.beginObject();
        writeRectangle2DData(writer, rect);
        writer.endObject();
    }

    public static void writeRectangle2DData(JsonWriter writer, Rectangle2D rect) throws IOException {
        writer.name("x"); writer.value(rect.getX());
        writer.name("y"); writer.value(rect.getY());
        writer.name("w"); writer.value(rect.getWidth());
        writer.name("h"); writer.value(rect.getHeight());
    }

    public static Rectangle2D readRectangle2DValue(JsonReader reader) throws IOException {
        reader.beginObject();
        return readRectangle2DData(reader);
    }

    public static Rectangle2D readRectangle2DData(JsonReader reader) throws IOException {
        Map<String, Double> metrics = new HashMap<>(4);
        metrics.put(reader.nextName(), reader.nextDouble());
        metrics.put(reader.nextName(), reader.nextDouble());
        metrics.put(reader.nextName(), reader.nextDouble());
        metrics.put(reader.nextName(), reader.nextDouble());
        reader.endObject();
        double x = metrics.getOrDefault("x", .0);
        double y = metrics.getOrDefault("y", .0);
        double w = metrics.getOrDefault("w", .0);
        double h = metrics.getOrDefault("h", .0);
        return new Rectangle2D.Double(x, y, w, h);
    }

    public static void writeColor(JsonWriter writer, Color color) throws IOException {
        writer.value(GraphicsUtil.color2String(color));
    }

    public static Color readColor(JsonReader reader) throws IOException {
        String colorString = reader.nextString();
        return GraphicsUtil.string2Color(colorString);
    }

    private static class ColorTypeAdapter implements JsonSerializer<Color>, JsonDeserializer<Color> {

        @Override
        public JsonElement serialize(Color color, Type type, JsonSerializationContext jsonSerializationContext) {
            return new JsonPrimitive(GraphicsUtil.color2String(color));
        }

        @Override
        public Color deserialize(JsonElement jsonElement, Type type, JsonDeserializationContext jsonDeserializationContext) throws JsonParseException {
            return GraphicsUtil.string2Color(jsonElement.getAsString());
        }
    }

    private static class Line2DTypeAdapter implements JsonSerializer<Line2D>, JsonDeserializer<Line2D> {

        @Override
        public JsonElement serialize(Line2D line2D, Type type, JsonSerializationContext jsonSerializationContext) {
            return JsonUtil.toDocumentAbbr(line2D);
        }

        @Override
        public Line2D deserialize(JsonElement jsonElement, Type type, JsonDeserializationContext jsonDeserializationContext) throws JsonParseException {
            return JsonUtil.getLine(jsonElement.getAsJsonObject(), null);
        }

    }

    private static class Rect2DTypeAdapter implements JsonSerializer<Rectangle2D>, JsonDeserializer<Rectangle2D> {

        @Override
        public JsonElement serialize(Rectangle2D rectangle2D, Type type, JsonSerializationContext jsonSerializationContext) {
            return JsonUtil.toDocumentAbbr(rectangle2D);
        }

        @Override
        public Rectangle2D deserialize(JsonElement jsonElement, Type type, JsonDeserializationContext jsonDeserializationContext) throws JsonParseException {
            return JsonUtil.getRectangle(jsonElement.getAsJsonObject(), null);
        }

    }

    private static class FileIdTypeAdapter implements JsonSerializer<FileId>, JsonDeserializer<FileId> {

        @Override
        public JsonElement serialize(FileId fileId, Type type, JsonSerializationContext jsonSerializationContext) {
            return BsonUtil.serializeFileId(fileId);
        }

        @Override
        public FileId deserialize(JsonElement jsonElement, Type type, JsonDeserializationContext jsonDeserializationContext) throws JsonParseException {
            return BsonUtil.deserializeFileId(jsonElement);
        }

    }

    private static class DateTypeAdapter implements JsonSerializer<Date>, JsonDeserializer<Date> {

        @Override
        public JsonElement serialize(Date date, Type type, JsonSerializationContext jsonSerializationContext) {
            return BsonUtil.serializeDate(date);
        }

        @Override
        public Date deserialize(JsonElement jsonElement, Type type, JsonDeserializationContext jsonDeserializationContext) throws JsonParseException {
            return BsonUtil.deserializeDate(jsonElement);
        }
    }

    public static class PageNumberTypeAdapter implements JsonSerializer<Integer>, JsonDeserializer<Integer> {

        @Override
        public JsonElement serialize(Integer pageNumber, Type type, JsonSerializationContext jsonSerializationContext) {
            return new JsonPrimitive(pageNumber - 1);
        }

        @Override
        public Integer deserialize(JsonElement jsonElement, Type type, JsonDeserializationContext jsonDeserializationContext) throws JsonParseException {
            return jsonElement.getAsInt() + 1;
        }
    }

    private static final Gson SUMMARY = new GsonBuilder()
            .setExclusionStrategies(DocumentExclusionStrategy.SUMMARY)
            .registerTypeAdapter(Color.class, new ColorTypeAdapter())
            .registerTypeAdapter(Line2D.class, new Line2DTypeAdapter())
            .registerTypeAdapter(Rectangle2D.class, new Rect2DTypeAdapter())
            .registerTypeAdapter(Rectangle.class, new Rect2DTypeAdapter())
            .registerTypeAdapter(FileId.class, new FileIdTypeAdapter())
            .registerTypeAdapter(Date.class, new DateTypeAdapter())
            .create();

    private static final Gson DETAIL = new GsonBuilder()
            .setExclusionStrategies(DocumentExclusionStrategy.DETAIL)
            .registerTypeAdapter(Color.class, new ColorTypeAdapter())
            .registerTypeAdapter(Line2D.class, new Line2DTypeAdapter())
            .registerTypeAdapter(Rectangle2D.class, new Rect2DTypeAdapter())
            .registerTypeAdapter(Rectangle.class, new Rect2DTypeAdapter())
            .registerTypeAdapter(FileId.class, new FileIdTypeAdapter())
            .registerTypeAdapter(Date.class, new DateTypeAdapter())
            .create();

    public static final Gson DEFAULT = new GsonBuilder()
            .registerTypeAdapter(Color.class, new ColorTypeAdapter())
            .registerTypeAdapter(Line2D.class, new Line2DTypeAdapter())
            .registerTypeAdapter(Rectangle2D.class, new Rect2DTypeAdapter())
            .registerTypeAdapter(Rectangle.class, new Rect2DTypeAdapter())
            .registerTypeAdapter(FileId.class, new FileIdTypeAdapter())
            .registerTypeAdapter(Date.class, new DateTypeAdapter())
            .create();


    private static Set<String> BITMAP_CHART_KEYS = Sets.newHashSet("_id", "is_ppt", "fileId", "pngFile",
            "area", "page_area", "pageIndex", "title", "fileUrl", "chart_version", "state", "create_time", "last_updated");

    public static Chart parseChart(JsonObject document, ExtractContext context) {
        try {
            JsonObject chartJson = document;
            // 位图解析之后很多字段的数据结构和Chart的结构不一致, 会导致解析失败, 这里只还原位图最核心的几个字段
            if (document.has("bitmap_parsed")) {
                chartJson = new JsonObject();
                for (Map.Entry<String, JsonElement> entry : document.entrySet()) {
                    if (BITMAP_CHART_KEYS.contains(entry.getKey())) {
                        chartJson.add(entry.getKey(), entry.getValue());
                    }
                }
                // 强制把类型还原成BITMAP_CHART
                chartJson.addProperty("chartType", ChartType.BITMAP_CHART.name());
            }
            Chart chart = DETAIL.fromJson(chartJson, Chart.class);
            chart.fromDocument(document, context);
            return chart;
        } catch (Exception e) {
            // incompatible with old data format
            return null;
        }
    }

    public static List<Chart> parseChartList(List<Document> documents, ExtractContext context) {
        if (documents == null) {
            return null;
        }
        return documents.stream()
                .map(document -> parseChart(BsonUtil.toJson(document), context))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /*
        Chart 和 Table 轻量化处理：

        目前 MongoDB 中 Chart 和 Table 所占用的空间较大，威胁到了 MongoDB 的稳定性。
        所以可能需要和正文一页，把 Chart 和 Table 的结构化数据以及详细数据上传到 OSS 等存储空间，只保留基本字段。

        基本字段包含：
         _id、pageIndex、fileId、file_source、from 等元数据字段；
         create_time、last_updated、state、xx_version 等状态字段；
         title、xx_type、algorithm、confidence 等和搜索相关的常用数据字段。

         高级字段：
         data 等包含大量数据的字段，这是本次需要处理的主要内容。
         page_area、legend 等可能需要被深度加工和利用的详细数据。
     */


    // 构造 Chart JSON 数据时需要移除的字段
    // TODO 请 Chart 相关的同事 Review
    private static Set<String> CHART_META_KEYS = Sets.newHashSet("pngFile",
            "fileUrl", "state", "create_time", "last_updated", "deleted",
            "data_file",
            "area");

    // 构造 Table JSON 数据时需要移除的字段
    private static Set<String> TABLE_META_KEYS = Sets.newHashSet("pngFile",
            "fileUrl", "state", "create_time", "last_updated", "deleted",
            "html_file", "data_file", "csv_file",
            "x", "y", "w", "h", "raw_data", "area");


    // 轻量 Chart 的字段（只保留元数据字段、状态字段和标题等少量
    // TODO 请 Chart 相关的同事 Review
    private static Set<String> CHART_LITE_REMOVAL_KEYS = Sets.newHashSet(
            "pngFile", "fileUrl", "page_rea", "data", "area",
            "hAxis", "vAxis", "vAxisTextL", "vAxisTextR", "hAxisTextU", "hAxisTextD", "legends", "rvAxis", "lvAxis", "rawTexts");

    // 轻量 Table 数据需要移除的字段，只保留方便查询的字段
    private static Set<String> TABLE_LITE_REMOVAL_KEYS = Sets.newHashSet(
            "pngFile", "fileUrl", "page_rea", "data", "raw_data", "area", "caps", "shoes",
            "x", "y", "w", "h", "raw_data", "area");

    public static Document toChartDataDocument(Document chartDoc) {
        return copyAndTrimDocument(chartDoc, CHART_META_KEYS);
    }

    public static Document toTableDataDocument(Document tableDoc) {
        return copyAndTrimDocument(tableDoc, TABLE_META_KEYS);
    }

    public static void trimChartDocument(Document tableDoc) {
        trimDocument(tableDoc, CHART_LITE_REMOVAL_KEYS);
    }

    public static void trimTableDocument(Document tableDoc) {
        trimDocument(tableDoc, TABLE_LITE_REMOVAL_KEYS);
    }

    private static final Object SYNC_ROOT = new Object();
    private static final Map<Class<?>, Iterable<String>> DATA_JSON_EXCLUSION_FIELDS_MAP = new HashMap<>();
    private static final Map<Class<?>, Iterable<String>> LITE_JSON_EXCLUSION_FIELDS_MAP = new HashMap<>();
    private static final Map<Class<?>, Iterable<String>> DEPRECATED_FIELDS_MAP = new HashMap<>();

    /**
     * 创建要上传的 BSON 文档。
     *
     * @param document 原始的 BSON 文档。
     * @param type 对象的类型。
     * @param preserveData 是否保留高级数据字段。
     * @return 适合用于上传的 BSON 文档。
     */
    public static Document createDataDocumentForUpload(Document document, Class<?> type, boolean preserveData) {
        Iterable<String> dataExcludedFields;
        synchronized (SYNC_ROOT) {
            dataExcludedFields = DATA_JSON_EXCLUSION_FIELDS_MAP.computeIfAbsent(type, k -> computeDataExclusionFields(type));
        }
        Document dataDocument = copyAndTrimDocument(document, dataExcludedFields);
        if (!preserveData) {
            Iterable<String> liteExcludedFields;
            synchronized (SYNC_ROOT) {
                liteExcludedFields = LITE_JSON_EXCLUSION_FIELDS_MAP.computeIfAbsent(type, k -> computeLiteExclusionFields(type));
            }
            trimDocument(document, liteExcludedFields);
        }
        return dataDocument;
    }

    public static void tidyDocument(Document storageDocument, Class<?> type) {
        Iterable<String> deprecatedFields;
        synchronized (SYNC_ROOT) {
            deprecatedFields = DEPRECATED_FIELDS_MAP.computeIfAbsent(type, k -> computeDeprecatedFields(type));
        }
        trimDocument(storageDocument, deprecatedFields);
    }

    /**
     * 计算不应包含在上传数据的字段。
     * <p>
     * 下列字段不应该包含在上传的数据中：
     * <ul>
     *     <li>没有序列化的字段。但这些字段本身不会在 JSON 中，所以无需记录。</li>
     *     <li>非内联数据，比如各种路径。因为上传数据都应该时内联的。</li>
     *     <li>状态字段。因为这些数据应该直接读数据库获取。</li>
     * </ul>
     * </p>
     */
    private static Iterable<String> computeDataExclusionFields(Class<?> type) {
        Field[] fields = type.getDeclaredFields();
        List<String> excludedFields = new ArrayList<>();
        for (Field field : fields) {
            FieldAttributes fieldAttributes = new FieldAttributes(field);
            DataField dataFieldAnnotation = fieldAttributes.getAnnotation(DataField.class);
            if (null == dataFieldAnnotation)
                continue;
            if (dataFieldAnnotation.inline()) {
                continue;
            }
            SerializedName serializedNameAnnotation = fieldAttributes.getAnnotation(SerializedName.class);
            if (serializedNameAnnotation != null) {
                excludedFields.add(serializedNameAnnotation.value());
            } else {
                excludedFields.add(fieldAttributes.getName());
            }
        }
        DataFields[] extraDataFields = type.getAnnotationsByType(DataFields.class);
        for (DataFields extraDataField : extraDataFields) {
            if (extraDataField.inline())
                continue;
            excludedFields.addAll(Arrays.asList(extraDataField.value()));
        }
        StatusFields statusFields = type.getAnnotation(StatusFields.class);
        excludedFields.addAll(Arrays.asList(statusFields.value()));
        return excludedFields;
    }

    /**
     * 计算不应包含在轻量数据的字段。
     * <p>
     * 下列字段不应该包含在上传的数据中：
     * <ul>
     *     <li>没有序列化的字段。但这些字段本身不会在 JSON 中，所以无需记录。</li>
     *     <li>比较大的数据，或者没有 MongoDB 直接查询意义的数据，统称为高级数据，比如各种 data、位置信息。</li>
     * </ul>
     * </p>
     */
    private static Iterable<String> computeLiteExclusionFields(Class<?> type) {
        Field[] fields = type.getDeclaredFields();
        List<String> excludedFields = new ArrayList<>();
        for (Field field : fields) {
            FieldAttributes fieldAttributes = new FieldAttributes(field);
            DataField dataFieldAnnotation = fieldAttributes.getAnnotation(DataField.class);
            if (null == dataFieldAnnotation)
                continue;
            if (!dataFieldAnnotation.advanced()) {
                continue;
            }
            SerializedName serializedNameAnnotation = fieldAttributes.getAnnotation(SerializedName.class);
            if (serializedNameAnnotation != null) {
                excludedFields.add(serializedNameAnnotation.value());
            } else {
                excludedFields.add(fieldAttributes.getName());
            }
        }
        DataFields[] extraDataFields = type.getAnnotationsByType(DataFields.class);
        for (DataFields extraDataField : extraDataFields) {
            if (!extraDataField.advanced())
                continue;
            excludedFields.addAll(Arrays.asList(extraDataField.value()));
        }

        return excludedFields;
    }


    /**
     * 计算之前废弃的字段。
     */
    private static Iterable<String> computeDeprecatedFields(Class<?> type) {
        DeprecatedFields deprecatedFields = type.getAnnotation(DeprecatedFields.class);
        return Arrays.asList(deprecatedFields.value());
    }


    private static Document copyAndTrimDocument(Document doc, Iterable<String> keysToRemove) {
        Document trimmed = new Document(doc);
        for (String key : keysToRemove) {
            trimmed.remove(key);
        }
        trimDocument(trimmed, keysToRemove);
        return trimmed;
    }


    private static void trimDocument(Document doc, Iterable<String> keysToRemove) {
        for (String key : keysToRemove) {
            doc.remove(key);
        }
    }


    public static Table parseTable(JsonObject document, ExtractContext context) {
        try {
            Table table = DETAIL.fromJson(document, Table.class);
            table.fromDocument(document, context);
            return table;
        } catch (Exception e) {
            // incompatible with old data format
            return null;
        }
    }

    public static List<Table> parseTableList(List<Document> documents, ExtractContext context) {
        if (documents == null) {
            return null;
        }
        return documents.stream()
                .map(document -> parseTable(BsonUtil.toJson(document), context))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    public static JsonObject toDocument(Object item, boolean detail) {
        if (item == null) {
            return null;
        }
        Gson gson = detail ? DETAIL : SUMMARY;
        return gson.toJsonTree(item).getAsJsonObject();
    }

    public static JsonObject toDocument(Object item) {
        if (item == null) {
            return null;
        }
        return DEFAULT.toJsonTree(item).getAsJsonObject();
    }

    public static void readTabelsFromJson(ContentGroupPage tablePage, List<TableRegion> crfLineTables, List<TableRegion> crfNoLineTables) {
        if (crfLineTables == null) {
            crfLineTables = new ArrayList<>();
        }
        if (crfNoLineTables == null) {
            crfNoLineTables = new ArrayList<>();
        }

        //TODO:计算table区域
        String fileName = FilenameUtils.getBaseName(tablePage.pdfPath);
        String jsonPath = tablePage.params.jsonDataFilesPath;
        int pageNum = tablePage.getPageNumber() - 1;
        String jsonFile = null;
        jsonFile = fileName + "__page_" + pageNum + ".json";
        File file = new File(jsonPath + "/" + jsonFile);
        String content = null;
        try {
            content = FileUtils.readFileToString(file, "UTF-8");
        } catch (IOException e) {
            //e.printStackTrace();
            throw new RuntimeException("Json 文件不存在" + file);
        }
        Gson gson = new Gson();
        JsonObject jsonObject = gson.fromJson(content, JsonObject.class);
        JsonArray objects = jsonObject.get("object").getAsJsonArray();
        for (int i = 0; i < objects.size(); i++) {
            JsonObject info = objects.get(i).getAsJsonObject();
            if (info.get("type").getAsString().equals("Table") || info.get("type").getAsString().equals("WirelessTable") || info.get("type").getAsString().equals("CableTable")) {
                JsonObject rec = info.get("box").getAsJsonObject();
                double xmin = Double.valueOf(rec.get("xmin").toString()) / 1.5;
                double xmax = Double.valueOf(rec.get("xmax").toString()) / 1.5;
                double ymin = Double.valueOf(rec.get("ymin").toString()) / 1.5;
                double ymax = Double.valueOf(rec.get("ymax").toString()) / 1.5;
                TableRegion region = new TableRegion(xmin, ymin, xmax - xmin, ymax - ymin);
                //利用鲁方波的算法新增类别，是否是有线表格还是无线表格
                if (new RulingTableRegionsDetectionAlgorithm().isLineTable(tablePage, region)) {
                    crfLineTables.add(region);
                } else {
                    crfNoLineTables.add(region);
                }
            }

            try {
                FileUtils.writeStringToFile(file, jsonObject.toString(), "UTF-8");
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
