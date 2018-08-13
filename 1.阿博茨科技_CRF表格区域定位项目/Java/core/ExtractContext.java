package com.abcft.pdfextract.core;

import com.abcft.pdfextract.core.chart.Chart;
import com.abcft.pdfextract.core.table.Table;
import com.abcft.pdfextract.spi.Document;
import com.abcft.pdfextract.util.Taggable;
import com.google.gson.JsonObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by dhu on 2017/11/13.
 */
public class ExtractContext<T> implements Taggable {

    protected static final Logger logger = LogManager.getLogger(ExtractContext.class);

    public static <T> ExtractContext<T> create(Document<T> document) {
        if (document instanceof DocumentFactory.PDFDocument) {
            //noinspection unchecked
            return (ExtractContext<T>) new PdfExtractContext((DocumentFactory.PDFDocument) document);
        }
        // TODO 实现不同的 ExtractContext
        return new ExtractContext<>(document);
    }

    public final Document<T> document;

    /**
     * The language code of the document, might be {@code null}.
     */
    public String language;

    private final List<Chart> chartList = new ArrayList<>();
    private final List<Table> tableList = new ArrayList<>();

    private final Map<String, Object> tags = new HashMap<>();  // 辅助标记信息

    public ExtractContext(Document<T> document) {
        this.document = document;
    }

    public Document<T> getDocument() {
        return document;
    }

    public T getNativeDocument() {
        return document.getDocument();
    }

    public void addCharts(List<Chart> charts) {
        if (charts == null || charts.isEmpty()) {
            return;
        }
        this.chartList.addAll(charts);
    }

    public void setCharts(List<Chart> charts) {
        this.chartList.clear();
        if (charts == null || charts.isEmpty()) {
            return;
        }
        this.chartList.addAll(charts);
    }

    public List<Chart> getCharts() {
        return chartList;
    }

    public void addTables(List<Table> tables) {
        if (tables == null || tables.isEmpty()) {
            return;
        }
        this.tableList.addAll(tables);
    }

    public void setTables(List<Table> tables) {
        this.tableList.clear();
        if (tables == null || tables.isEmpty()) {
            return;
        }
        this.tableList.addAll(tables);
    }

    public List<Table> getTables() {
        return tableList;
    }

    /**
     * Clear cache, tags and all other temp objects.
     */
    public void clearCache() {
        clearTags();
    }


    /**
     * 收集一些有价值的解析内部反馈，方便后续改善。
     *
     * @param feedback 用于存储反馈结果的对象。此对象会被反复使用。
     */
    public void collectFeedback(JsonObject feedback) {

    }


    public void addTag(String key, Object value) {
        tags.put(key, value);
    }

    public Object getTag(String key) {
        return tags.get(key);
    }

    public boolean hasTag(String key) {
        return tags.containsKey(key);
    }

    public void clearTags() {
        tags.clear();
    }

    public void removeTag(String key) {
        tags.remove(key);
    }


}
