package com.abcft.pdfextract.core.chart;

import com.abcft.annotations.proguard.KeepName;
import com.abcft.pdfextract.core.ExtractorUtil;
import com.abcft.pdfextract.core.PdfExtractor;
import com.abcft.pdfextract.spi.Document;
import com.abcft.pdfextract.util.JsonUtil;
import com.google.gson.JsonObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;

import java.io.IOException;
import java.io.Writer;

/**
 * Extractor for charts.
 *
 * Created by chzhong on 17-3-7.
 */
public class ChartExtractor implements PdfExtractor<ChartExtractParameters, Chart, ChartExtractionResult, ChartCallback> {

    @Override
    public int getVersion() {
        return 48;
    }

    private static Logger logger = LogManager.getLogger(ChartExtractor.class);


    public static class ChartWriterCallback implements ChartCallback {
        private Writer writer;

        public ChartWriterCallback(Writer writer) {
            this.writer = writer;
        }

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
        public void onItemExtracted(Chart chart) {
            JsonObject doc = chart.toDocument(true);
            try {
                writer.write(JsonUtil.toString(doc, false));
            } catch (IOException e) {
                logger.warn("serialize chart failed", e);
            }
        }

        @Override
        public void onFinished(ChartExtractionResult result) {

        }

    }

    @Override
    public ChartExtractionResult process(Document<PDDocument> document, ChartExtractParameters params, ChartCallback callback) {
        return extractCharts(document.getDocument(), params, callback);
    }

    @Override
    public void processPage(PDDocument document, int pageIndex, PDPage page,
                            ChartExtractParameters params, ChartExtractionResult result, ChartCallback callback) {
        extractChartsFromPage(pageIndex, page, params, result, callback);
    }

    @Override
    public void postProcessing(PDDocument document, ChartExtractParameters parameters, ChartExtractionResult result, ChartCallback callback) {
        // 在指定的所有页面都解析结束后　过滤掉内容重复的位图Chart对象
        result.filterSameBitmapChart();
        parameters.context.setCharts(result.getItems());
    }

    private ChartExtractionResult extractCharts(PDDocument document, ChartExtractParameters parameters, ChartCallback callback) {
        ChartExtractionResult result = new ChartExtractionResult();
        if (callback != null) {
            callback.onStart(document);
        }

        ExtractorUtil.forEachEffectivePages(document, parameters, ((pageIndex, page) -> {
            extractChartsFromPage(pageIndex, page, parameters, result, callback);
        }));
        postProcessing(document, parameters, result, callback);
        if (callback != null) {
            callback.onFinished(result);
        }
        return result;
    }

    private void extractChartsFromPage(int pageIndex, PDPage page, ChartExtractParameters parameters, ChartExtractionResult result, ChartCallback callback) {
        new ChartDetector(parameters).processPage(parameters.getExtractContext(), pageIndex, page, result, callback);
    }
}
