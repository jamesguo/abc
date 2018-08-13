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

public class WordExtractor {
    private static final Logger logger = LogManager.getLogger();
    private static final int VERSION = 6;

    public static class ChartExtractor implements Extractor<WordDocument, ChartExtractParameters, Chart, ChartExtractionResult, ChartCallback> {

        @Override
        public int getVersion() {
            return VERSION;
        }

        @Override
        public ChartExtractionResult process(Document<WordDocument> document, ChartExtractParameters parameters, ChartCallback callback) {
            return OfficeExtractor.process(document, parameters, callback);
        }
    }

    public static class TableExtractor implements Extractor<WordDocument, TableExtractParameters, com.abcft.pdfextract.core.table.Table, TableExtractionResult, TableCallback> {

        @Override
        public int getVersion() {
            return VERSION;
        }

        @Override
        public TableExtractionResult process(Document<WordDocument> document, TableExtractParameters parameters, TableCallback callback) {
            return OfficeExtractor.process(document, parameters, callback);
        }
    }

    public static class TextExtractor implements Extractor<WordDocument, ContentExtractParameters, Page, Fulltext, ContentExtractorCallback> {

        @Override
        public int getVersion() {
            return VERSION;
        }

        @Override
        public Fulltext process(Document<WordDocument> document, ContentExtractParameters parameters, ContentExtractorCallback callback) {
            return OfficeExtractor.process(document, parameters, callback);
        }
    }


}
