package com.abcft.pdfextract.core;

import com.abcft.pdfextract.core.chart.*;
import com.abcft.pdfextract.core.chart.Chart;
import com.abcft.pdfextract.core.content.*;
import com.abcft.pdfextract.core.content.Page;
import com.abcft.pdfextract.core.content.Paragraph;
import com.abcft.pdfextract.core.html.HtmlChartExtractor;
import com.abcft.pdfextract.core.html.HtmlContentExtractor;
import com.abcft.pdfextract.core.html.HtmlTableExtractor;
import com.abcft.pdfextract.core.office.*;
import com.abcft.pdfextract.core.table.*;
import com.abcft.pdfextract.core.table.Table;
import com.abcft.pdfextract.spi.AlgorithmVersion;
import com.abcft.pdfextract.spi.Document;
import com.abcft.pdfextract.spi.ExtractType;
import com.abcft.pdfextract.spi.FileType;
import com.google.common.base.Stopwatch;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.math3.util.FastMath;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageTree;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Factory used to create PDF extractors.
 *
 * Created by chzhong on 17-3-21.
 */
public class ExtractorFactory implements AlgorithmVersion {

    private static final Object LOCK = new Object();
    private static ExtractorFactory instance;

    /**
     * Retrieve the only instance of {@link ExtractorFactory}.
     *
     * @return the singleton of {@link ExtractorFactory}.
     */
    public static ExtractorFactory getInstance() {
        if (null == instance) {
            synchronized (LOCK) {
                if (null == instance) {
                    instance = new ExtractorFactory();
                }
            }
        }
        return instance;
    }

    protected ExtractorFactory() {
        registerExtractorInternal(FileType.PDF, ExtractType.CHART, new ChartExtractor());
        registerExtractorInternal(FileType.PDF, ExtractType.TABLE, new TableExtractor());
        registerExtractorInternal(FileType.PDF, ExtractType.TEXT, new ContentExtractor());
        registerExtractorInternal(FileType.HTML, ExtractType.CHART, new HtmlChartExtractor());
        registerExtractorInternal(FileType.HTML, ExtractType.TABLE, new HtmlTableExtractor());
        registerExtractorInternal(FileType.HTML, ExtractType.TEXT, new HtmlContentExtractor());
        registerExtractorInternal(FileType.WORD, ExtractType.CHART, new WordExtractor.ChartExtractor());
        registerExtractorInternal(FileType.WORD, ExtractType.TABLE, new WordExtractor.TableExtractor());
        registerExtractorInternal(FileType.WORD, ExtractType.TEXT, new WordExtractor.TextExtractor());
        registerExtractorInternal(FileType.EXCEL, ExtractType.CHART, new ExcelExtractor.ChartExtractor());
        registerExtractorInternal(FileType.EXCEL, ExtractType.TABLE, new ExcelExtractor.TableExtractor());
        registerExtractorInternal(FileType.EXCEL, ExtractType.TEXT, new ExcelExtractor.TextExtractor());
        registerExtractorInternal(FileType.POWERPOINT, ExtractType.CHART, new PowerPointExtractor.ChartExtractor());
        registerExtractorInternal(FileType.POWERPOINT, ExtractType.TABLE, new PowerPointExtractor.TableExtractor());
        registerExtractorInternal(FileType.POWERPOINT, ExtractType.TEXT, new PowerPointExtractor.TextExtractor());
    }

    private final Object lock = new Object();
    private Map<Pair<FileType, ExtractType>, Extractor> extractorInstances = new HashMap<>();

    /**
     * Determine the version of algorithm.

     * @return the version code of {@link ChartExtractor}.
     */
    @Override
    public final int getAlgorithmVersion(FileType fileType, ExtractType extractType) {
        return getExtractor(fileType, extractType).getVersion();
    }

    /**
     * Process in a document.
     * @param document the document to process.
     * @param extractType the extract type
     * @param params the parameters for the extraction.
     * @param callback callback to receive extraction events.
     */
    public <TItem extends ExtractedItem,
            TResult extends ExtractionResult<TItem>,
            TCallback extends ExtractCallback<TItem, TResult>>
    TResult process(Document document, ExtractType extractType, ExtractParameters params, TCallback callback) {
        return (TResult) getExtractor(document.getFileType(), extractType).process(document, params, callback);
    }

    /**
     * Process charts, tables and fulltext in a PDF document, using page-first strategy.
     *
     * @param document the document to process.
     * @param chartExtractPair a pair to chart parameter and callback.
     * @param tableExtractPair a pair to table parameter and callback.
     * @param contentExtractPair a pair to content parameter and callback.
     * @return error occurred during general processing, or {@code null} if no error happens.
     */
    public Throwable processPDF(Document<PDDocument> document,
                           Pair<ChartExtractParameters, ? extends ChartCallback> chartExtractPair,
                           Pair<TableExtractParameters, ? extends TableCallback> tableExtractPair,
                           Pair<ContentExtractParameters, ? extends ContentExtractorCallback> contentExtractPair
    ) {
        try {
            PageByPageExtractor extractor = new PageByPageExtractor(this, document,
                    chartExtractPair, tableExtractPair, contentExtractPair, null);
            extractor.process();
            return null;
        } catch (Throwable e) {
            return e;
        }
    }

    /**
     * Process charts, tables and fulltext in a PDF document, using page-first strategy.
     *
     * @param document the document to process.
     * @param chartExtractPair a pair to chart parameter and callback.
     * @param tableExtractPair a pair to table parameter and callback.
     * @param contentExtractPair a pair to content parameter and callback.
     * @return error occurred during general processing, or {@code null} if no error happens.
     */
    public Throwable processPDF(Document<PDDocument> document,
                                Pair<ChartExtractParameters, ? extends ChartCallback> chartExtractPair,
                                Pair<TableExtractParameters, ? extends TableCallback> tableExtractPair,
                                Pair<ContentExtractParameters, ? extends ContentExtractorCallback> contentExtractPair,
                                PageCallback pageCallback
    ) {
        try {
            PageByPageExtractor extractor = new PageByPageExtractor(this, document,
                    chartExtractPair, tableExtractPair, contentExtractPair, pageCallback);
            extractor.process();
            return null;
        } catch (Throwable e) {
            return e;
        }
    }


    /**
     * Retrieve the instance of specified extractor type.
     *
     * @param <T> the type of the extractor.
     *
     * @return the instance of the specified extractor.
     */
    public <T extends Extractor> T getExtractor(FileType fileType, ExtractType extractType) {
        synchronized (lock) {
            //noinspection unchecked
            Pair key = Pair.of(fileType, extractType);
            T instance = (T) extractorInstances.get(key);
            if (null == instance) {
                throw new IllegalArgumentException("Can't get extractor, fileType: " + fileType + ", extractType: " + extractType);
            }
            return instance;
        }
    }

    private <T extends Extractor> void registerExtractorInternal(FileType fileType, ExtractType extractType, T instance) {
        extractorInstances.put(Pair.of(fileType, extractType), instance);
    }

    public boolean supportFileType(FileType fileType) {
        synchronized (lock) {
            for (Pair<FileType, ExtractType> pair : extractorInstances.keySet()) {
                if (pair.getLeft() == fileType) {
                    return true;
                }
            }
            return false;
        }
    }

    private static abstract class ExtractCallbackWrapper
            <TItem extends ExtractedItem,
            TResult extends ExtractionResult<TItem>,
            TCallback extends ExtractCallback<TItem, TResult>
            > implements ExtractCallback<TItem, TResult> {

        final TCallback callback;
        final TResult result;

        ExtractCallbackWrapper(TCallback callback, TResult result) {
            this.callback = callback;
            this.result = result;
        }

        @Override
        public void onExtractionError(Throwable e) {
            this.callback.onExtractionError(e);
        }

        @Override
        public void onLoadError(Throwable e) {
            this.callback.onLoadError(e);
        }

        @Override
        public void onFatalError(Throwable e) {
            this.callback.onFatalError(e);
        }

        void notifyOnStart(PDDocument document) {
            this.callback.onStart(document);
        }

        @Override
        public void onItemExtracted(TItem item) {
            this.callback.onItemExtracted(item);
        }

        @Override
        public void onStart(Object document) {
            // Skipped
        }

        void notifyOnFinished() {
            this.callback.onFinished(result);
        }
    }

    private static final class PageByPageChartCallbackWrapper
            extends ExtractCallbackWrapper<Chart, ChartExtractionResult, ChartCallback> implements ChartCallback {

        PageByPageChartCallbackWrapper(ChartCallback callback) {
            super(callback, new ChartExtractionResult());
        }

        @Override
        public void onFinished(ChartExtractionResult result) {
            this.result.addCharts(result.getItems());
            this.result.addErrors(result.getErrors(), result.getLastError());
        }

    }

    private static final class PageByPageTableCallbackWrapper
            extends ExtractCallbackWrapper<Table, TableExtractionResult, TableCallback> implements TableCallback {

        PageByPageTableCallbackWrapper(TableCallback callback) {
            super(callback, new TableExtractionResult());
        }


        @Override
        public void onFinished(TableExtractionResult result) {
            this.result.addTables(result.getItems());
            this.result.addErrors(result.getErrors(), result.getLastError());
        }

    }

    private static final class PageByPageContentCallbackWrapper
            extends ExtractCallbackWrapper<Page, Fulltext, ContentExtractorCallback> implements ContentExtractorCallback {

        PageByPageContentCallbackWrapper(Document<PDDocument> document, ContentExtractorCallback callback) {
            super(callback, new Fulltext(document));
        }

        @Override
        public void onFinished(Fulltext result) {
            this.result.merge(result);
            this.result.addErrors(result.getErrors(), result.getLastError());
        }

    }

    private static final class PageByPageExtractor {

        private final ChartExtractor chartExtractor;
        private final ChartExtractParameters chartParams;
        private final PageByPageChartCallbackWrapper chartCallback;
        private final TableExtractor tableExtractor;
        private final TableExtractParameters tableParams;
        private final PageByPageTableCallbackWrapper tableCallback;
        private final ContentExtractor contentExtractor;
        private final ContentExtractParameters contentParams;
        private final PageByPageContentCallbackWrapper contentCallback;
        private final PageCallback pageCallback;
        private final PDDocument document;
        private final int startPageIndex;
        private final int endPageIndex;
        private final PdfExtractContext extractContext;

        PageByPageExtractor(ExtractorFactory factory, Document<PDDocument> document,
                            Pair<ChartExtractParameters, ? extends ChartCallback> chartExtractPair,
                            Pair<TableExtractParameters, ? extends TableCallback> tableExtractPair,
                            Pair<ContentExtractParameters, ? extends ContentExtractorCallback> contentExtractPair,
                            PageCallback pageCallback
        ) {
            this.document = document.getDocument();
            this.pageCallback = pageCallback;
            int startPageIndex = -1;
            int endPageIndex = -1;
            ExtractContext extractContext = null;

            if (chartExtractPair != null) {
                chartExtractor = factory.getExtractor(FileType.PDF, ExtractType.CHART);
                chartParams = chartExtractPair.getLeft();
                extractContext = chartParams.context;
                startPageIndex = chartParams.startPageIndex;
                endPageIndex = chartParams.endPageIndex;
                chartCallback = new PageByPageChartCallbackWrapper(chartExtractPair.getRight());
            } else {
                chartExtractor = null;
                chartParams = null;
                chartCallback = null;
            }

            if (tableExtractPair != null) {
                tableExtractor = factory.getExtractor(FileType.PDF, ExtractType.TABLE);
                tableParams = tableExtractPair.getLeft();
                if (null == extractContext) {
                    extractContext = tableParams.context;
                } else {
                    tableParams.context = extractContext;
                }
                if (startPageIndex < 0) {
                    startPageIndex = tableParams.startPageIndex;
                } else {
                    startPageIndex = FastMath.min(startPageIndex, tableParams.startPageIndex);
                }
                if (endPageIndex < 0) {
                    endPageIndex = tableParams.endPageIndex;
                } else {
                    endPageIndex = FastMath.min(endPageIndex, tableParams.endPageIndex);
                }

                tableCallback = new PageByPageTableCallbackWrapper(tableExtractPair.getRight());
            } else {
                tableExtractor = null;
                tableParams = null;
                tableCallback = null;
            }

            if (contentExtractPair != null) {
                contentExtractor = factory.getExtractor(FileType.PDF, ExtractType.TEXT);
                contentParams = contentExtractPair.getLeft();
                if (null == extractContext) {
                    extractContext = contentParams.context;
                } else {
                    contentParams.context = extractContext;
                }
                if (startPageIndex < 0) {
                    startPageIndex = contentParams.startPageIndex;
                } else {
                    startPageIndex = FastMath.min(startPageIndex, contentParams.startPageIndex);
                }
                if (endPageIndex < 0) {
                    endPageIndex = contentParams.endPageIndex;
                } else {
                    endPageIndex = FastMath.min(endPageIndex, contentParams.endPageIndex);
                }
                contentCallback = new PageByPageContentCallbackWrapper(document, contentExtractPair.getRight());
            } else {
                contentExtractor = null;
                contentParams = null;
                contentCallback = null;
            }

            if (startPageIndex < 0) {
                startPageIndex = 0;
            }
            if (endPageIndex >= this.document.getNumberOfPages() || endPageIndex < 0) {
                endPageIndex = this.document.getNumberOfPages() - 1;
            }
            if (null == extractContext) {
                throw new IllegalArgumentException("extractContext is required");
            }
            this.extractContext = (PdfExtractContext)extractContext;
            extractContext.addTag(PdfExtractContext.CHART_PARAMS, chartParams);
            extractContext.addTag(PdfExtractContext.TABLE_PARAMS, tableParams);
            extractContext.addTag(PdfExtractContext.TEXT_PARAMS, contentParams);
            this.startPageIndex = startPageIndex;
            this.endPageIndex = endPageIndex;
        }

        void process() {
            final boolean canProcessChart = chartExtractor != null && chartCallback != null && chartParams != null;
            final boolean canProcessTable = tableExtractor != null && tableCallback != null && tableParams != null;
            final boolean canProcessContent = contentExtractor != null && contentCallback != null && contentParams != null;

            if (canProcessChart) {
                chartCallback.notifyOnStart(document);
            }
            if (canProcessTable) {
                tableCallback.notifyOnStart(document);
            }
            if (canProcessContent) {
                contentCallback.notifyOnStart(document);
            }

            PDPageTree pages = document.getPages();


            Stopwatch contentGroupStopwatch = Stopwatch.createUnstarted();
            Stopwatch chartStopwatch = Stopwatch.createUnstarted();
            Stopwatch tableStopwatch = Stopwatch.createUnstarted();
            Stopwatch contentStopwatch = Stopwatch.createUnstarted();
            for(int i = startPageIndex; i <= endPageIndex; ++i) {
                PDPage page = ExtractorUtil.getPdfPage(pages, i);
                if (null == page) {
                    continue;
                }
                contentGroupStopwatch.start();
                PdfExtractContext.PageContext pageContext = extractContext.getPageContext(page, true);
                contentGroupStopwatch.stop();
                // 先解析正文和段落，方便后续段落搜索
                if (canProcessContent && !pageContext.timeout()) {
                    contentStopwatch.start();
                    contentExtractor.processPage(document, i, page, contentParams, contentCallback.result, contentCallback);
                    contentStopwatch.stop();
                }
                if (canProcessChart && !pageContext.timeout()) {
                    chartStopwatch.start();
                    chartExtractor.processPage(document, i, page, chartParams, chartCallback.result, chartCallback);
                    chartStopwatch.stop();
                }
                if (canProcessTable && !pageContext.timeout()) {
                    tableStopwatch.start();
                    tableExtractor.processPage(document, i, page, tableParams, tableCallback.result, tableCallback);
                    tableStopwatch.stop();
                }
                if (pageCallback != null) {
                    pageCallback.onPageFinished(pageContext, page, i,
                            contentCallback.result.getPage(i),
                            chartCallback.result.getItemsByPage(i),
                            tableCallback.result.getItemsByPage(i)
                    );
                }
            }
            extractContext.setContentGroupDuration(contentGroupStopwatch.elapsed(TimeUnit.MILLISECONDS));
            if (canProcessChart) {
                finish(chartExtractor, document, chartParams, chartCallback, chartStopwatch);
            }
            if (canProcessTable) {
                finish(tableExtractor, document, tableParams, tableCallback, tableStopwatch);
            }
            if (canProcessContent) {
                finish(contentExtractor, document, contentParams, contentCallback, contentStopwatch);
            }
        }

        private <TParams extends ExtractParameters,
                TItem extends ExtractedItem,
                TResult extends ExtractionResult<TItem>,
                TCallback extends ExtractCallback<TItem, TResult>> void finish(
                PdfExtractor<TParams, TItem, TResult, TCallback> extractor,
                PDDocument document,
                TParams params,
                ExtractCallbackWrapper<TItem, TResult, TCallback> wrapper,
                Stopwatch stopwatch
        ) {
            TResult result = wrapper.result;
            stopwatch.start();
            extractor.postProcessing(document, params, result, wrapper.callback);
            stopwatch.stop();
            long duration = stopwatch.elapsed(TimeUnit.MILLISECONDS);
            result.setExtractDuration(duration);
            wrapper.notifyOnFinished();
        }
    }

}
