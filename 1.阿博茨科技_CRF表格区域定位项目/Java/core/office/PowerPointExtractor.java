package com.abcft.pdfextract.core.office;

import com.abcft.pdfextract.core.Extractor;
import com.abcft.pdfextract.core.chart.Chart;
import com.abcft.pdfextract.core.chart.ChartCallback;
import com.abcft.pdfextract.core.chart.ChartExtractParameters;
import com.abcft.pdfextract.core.chart.ChartExtractionResult;
import com.abcft.pdfextract.core.content.ContentExtractParameters;
import com.abcft.pdfextract.core.content.ContentExtractorCallback;
import com.abcft.pdfextract.core.content.Fulltext;
import com.abcft.pdfextract.core.content.Page;
import com.abcft.pdfextract.core.table.TableCallback;
import com.abcft.pdfextract.core.table.TableExtractParameters;
import com.abcft.pdfextract.core.table.TableExtractionResult;
import com.abcft.pdfextract.spi.Document;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


public class PowerPointExtractor {
    private static final Logger logger = LogManager.getLogger();
    private static final int VERSION = 6;

    public static class ChartExtractor implements Extractor<PowerPointDocument, ChartExtractParameters, Chart, ChartExtractionResult, ChartCallback> {

        @Override
        public int getVersion() {
            return VERSION;
        }

        @Override
        public ChartExtractionResult process(Document<PowerPointDocument> document, ChartExtractParameters parameters, ChartCallback callback) {
            return OfficeExtractor.process(document, parameters, callback);
        }
    }

    public static class TableExtractor implements Extractor<PowerPointDocument, TableExtractParameters, com.abcft.pdfextract.core.table.Table, TableExtractionResult, TableCallback> {

        @Override
        public int getVersion() {
            return VERSION;
        }

        @Override
        public TableExtractionResult process(Document<PowerPointDocument> document, TableExtractParameters parameters, TableCallback callback) {
            return OfficeExtractor.process(document, parameters, callback);
        }
    }

    public static class TextExtractor implements Extractor<PowerPointDocument, ContentExtractParameters, Page, Fulltext, ContentExtractorCallback> {

        @Override
        public int getVersion() {
            return VERSION;
        }

        @Override
        public Fulltext process(Document<PowerPointDocument> document, ContentExtractParameters parameters, ContentExtractorCallback callback) {
            return OfficeExtractor.process(document, parameters, callback);
        }
    }
}
