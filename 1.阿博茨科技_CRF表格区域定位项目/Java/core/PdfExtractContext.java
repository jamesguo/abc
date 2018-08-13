package com.abcft.pdfextract.core;

import com.abcft.pdfextract.core.chart.Chart;
import com.abcft.pdfextract.core.model.ContentGroup;
import com.abcft.pdfextract.core.model.Tags;
import com.abcft.pdfextract.core.table.Table;
import com.google.common.cache.AbstractLoadingCache;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.EvictingQueue;
import com.google.gson.JsonObject;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

public class PdfExtractContext extends ExtractContext<PDDocument> {

    public static final String CHART_PARAMS = "params.chart";
    public static final String TABLE_PARAMS = "params.table";
    public static final String TEXT_PARAMS = "params.text";

    private static final int MAX_CACHED_PAGES = 3;

    /**
     * Creator of the document, might be {@code null}.
     */
    public final String creator;
    /**
     * Producer of the document, might be {@code null}.
     */
    public final String producer;

    /**
     * the document information, might be {@code null}.
     */
    public final PDDocumentInformation documentInformation;
    /**
     * the document catalog, might be {@code null}.
     */
    public final PDDocumentCatalog documentCatalog;


    private final ContentGroupRenderer renderer;


    private final LoadingCache<PDPage, PageContext> pageContextCache;

    private long contentGroupDuration;

    public PdfExtractContext(DocumentFactory.PDFDocument document) {
        super(document);
        PDDocument pdf = getNativeDocument();
        if (pdf != null) {
            this.documentCatalog = pdf.getDocumentCatalog();
            this.documentInformation = ExtractorUtil.getPdfDocumentInformation(pdf);
        } else {
            this.documentCatalog = null;
            this.documentInformation = null;
        }
        if (documentInformation != null) {
            this.creator = documentInformation.getCreator();
            this.producer = documentInformation.getProducer();
        } else {
            this.creator = null;
            this.producer = null;
        }
        if (documentCatalog != null) {
            this.language = documentCatalog.getLanguage();
        } else {
            this.language = null;
        }
        this.renderer = new ContentGroupRenderer(pdf);
        /*
        this.pageContextCache = CacheBuilder.newBuilder()
                .maximumSize(MAX_CACHED_PAGES)
                .build(new CacheLoader<PDPage, PageContext>() {
                    @Override
                    public PageContext load(PDPage page) throws Exception {
                        return new PageContext();
                    }
                });
        */
        this.pageContextCache = new FifoLoadingCache(MAX_CACHED_PAGES);
    }

    @Override
    public void collectFeedback(JsonObject feedback) {
        if (!feedback.has("content_group_duration") && contentGroupDuration > 0) {
            feedback.addProperty("content_group_duration", contentGroupDuration);
        }
        COSDictionary trailer = getNativeDocument().getDocument().getTrailer();
        int fontFlags = trailer.getInt(ExtractorUtil.FONT_FLAGS, 0);
        if (fontFlags != 0) {
            feedback.addProperty("font_no_mapping",
                    (fontFlags & ExtractorUtil.FLAG_FONT_CMAP_MISSSING) == ExtractorUtil.FLAG_FONT_CMAP_MISSSING);
            feedback.addProperty("font_predefined_incomplete",
                    (fontFlags & ExtractorUtil.FLAG_PREDEFINED_INCOMPLETE) == ExtractorUtil.FLAG_PREDEFINED_INCOMPLETE);
            feedback.addProperty("font_text_failure",
                    (fontFlags & ExtractorUtil.FLAG_FONT_TEXT_FAILURE) == ExtractorUtil.FLAG_FONT_TEXT_FAILURE);
        }
    }

    public long getContentGroupDuration() {
        return contentGroupDuration;
    }

    void setContentGroupDuration(long contentGroupDuration) {
        this.contentGroupDuration = contentGroupDuration;
        logger.info("Building content group: {} ms used.", contentGroupDuration);
    }

    /**
     * 获取或者解析一个页面对应的 {@link ContentGroup} 结构。
     * <p>
     * 如果页面上次解析超时，则会直接抛出 {@link TimeoutException}.
     *
     * @param page 要解析的页面。
     * @return 页面对应的 {@link ContentGroup} 结构。
     * @throws TimeoutException 本次或上次解析超时。
     */
    public ContentGroup getPageContentGroup(PDPage page) throws TimeoutException {
        PageContext pageContext = pageContextCache.getUnchecked(page);
        Map<String, String> extractParams = getTag(Tags.EXTRACT_PARAMS, Map.class);
        return pageContext.loadContentGroup(renderer, page, extractParams);
    }

    /**
     * 获取或者解析一个页面对应的 {@link ContentGroup} 结构。
     * <p>
     * 如果页面上次解析超时，则会直接抛出 {@link TimeoutException}.
     *
     * @param pageIndex 要解析的页面索引。
     * @return 页面对应的 {@link ContentGroup} 结构。
     * @throws TimeoutException 本次或上次解析超时。
     */
    public ContentGroup getPageContentGroup(int pageIndex) throws TimeoutException {
        return getPageContentGroup(getNativeDocument().getPage(pageIndex));
    }

    /**
     * 获取或者解析一个页面对应的 {@link ContentGroup} 结构。
     * <p>
     * 如果页面上次解析超时，会重新尝试解析.
     *
     * @param page 要解析的页面。
     * @return 页面对应的 {@link ContentGroup} 结构。
     * @throws TimeoutException 本次解析超时。
     */
    public ContentGroup getOrReloadPageContentGroup(PDPage page) throws TimeoutException {
        PageContext pageContext = pageContextCache.getUnchecked(page);
        pageContext.removeArg(PAGE_ARG_PROCESS_TIMEOUT);
        Map<String, String> extractParams = getTag(Tags.EXTRACT_PARAMS, Map.class);
        return pageContext.loadContentGroup(renderer, page, extractParams);
    }

    public PageContext getPageContext(PDPage page) {
        return getPageContext(page, false);
    }

    PageContext getPageContext(PDPage page, boolean loadContentGroup) {
        PageContext pageContext  = pageContextCache.getUnchecked(page);
        if (loadContentGroup) {
            try {
                Map<String, String> extractParams = getTag(Tags.EXTRACT_PARAMS, Map.class);
                pageContext.loadContentGroup(renderer, page, extractParams);
            } catch (TimeoutException e) {
                // Already time-out
                logger.warn("Timeout while trying to build content group.", e);
            }
        }
        return pageContext;
    }

    public PageContext getPageContext(int pageIndex) {
        return getPageContext(getNativeDocument().getPage(pageIndex), false);
    }

    PageContext getPageContext(int pageIndex, boolean loadContentGroup) {
        return getPageContext(getNativeDocument().getPage(pageIndex), loadContentGroup);
    }

    @Override
    public void clearCache() {
        pageContextCache.cleanUp();
        super.clearCache();
    }

    static final String PAGE_ARG_PROCESS_TIMEOUT = "page.processTimeout";

    public static final class PageContext {
        ContentGroup root;
        final Map<String, Object> args;

        PageContext() {
            this.args = new HashMap<>();
        }

        ContentGroup loadContentGroup(ContentGroupRenderer renderer, PDPage page, Map<String, String> pageParams) throws TimeoutException {
            if (args.containsKey(PAGE_ARG_PROCESS_TIMEOUT)) {
                throw (TimeoutException)args.get(PAGE_ARG_PROCESS_TIMEOUT);
            }
            if (null == this.root) {
                try {
                    this.root = renderer.processPage(page, pageParams);
                    args.remove(PAGE_ARG_PROCESS_TIMEOUT);
                } catch (TimeoutException e) {
                    args.put(PAGE_ARG_PROCESS_TIMEOUT, e);
                    throw e;
                }
            }
            return root;
        }

        public boolean timeout() {
            return args.containsKey(PAGE_ARG_PROCESS_TIMEOUT);
        }

        public boolean hasContentGroup() {
            return root != null;
        }

        public ContentGroup getCachedContentGroup() {
            return root;
        }

        public void putArg(String key, Object obj) {
            args.put(key, obj);
        }

        public <T> T getArg(String key) {
            return (T) args.get(key);
        }

        public Map<String, Object> getArgMap() {
            return args;
        }

        public void clearArgs() {
            args.clear();
        }

        public Object removeArg(String key) {
            return args.remove(key);
        }


    }

    private static final class FifoLoadingCache extends AbstractLoadingCache<PDPage, PageContext> {

        private final EvictingQueue<PDPage> pageQueue;
        private final Map<PDPage, PageContext> map;

        FifoLoadingCache(int capacity) {
            this.pageQueue = EvictingQueue.create(capacity);
            this.map = new ConcurrentHashMap<>(capacity);
        }

        /**
         * Returns the value associated with {@code key} in this cache, first loading that value if
         * necessary. No observable state associated with this cache is modified until loading completes.
         *
         * <p>If another call to {@link #get} or {@link #getUnchecked} is currently loading the value for
         * {@code key}, simply waits for that thread to finish and returns its loaded value. Note that
         * multiple threads can concurrently load values for distinct keys.
         *
         * <p>Caches loaded by a {@link CacheLoader} will call {@link CacheLoader#load} to load new values
         * into the cache. Newly loaded values are added to the cache using
         * {@code Cache.asMap().putIfAbsent} after loading has completed; if another value was associated
         * with {@code key} while the new value was loading then a removal notification will be sent for
         * the new value.
         *
         * <p>If the cache loader associated with this cache is known not to throw checked exceptions,
         * then prefer {@link #getUnchecked} over this method.
         *
         * @param key
         * @throws ExecutionException          if a checked exception was thrown while loading the value. ({@code
         *                                     ExecutionException} is thrown
         *                                     <a href="https://github.com/google/guava/wiki/CachesExplained#interruption">even if
         *                                     computation was interrupted by an {@code InterruptedException}</a>.)
         */
        @Override
        public PageContext get(PDPage key) {
            if (map.containsKey(key)) {
                return map.get(key);
            } else {
                if (pageQueue.remainingCapacity() <= 0) {
                    PDPage oldKey = pageQueue.poll();
                    map.remove(oldKey);
                }
                PageContext newContext = new PageContext();
                map.put(key, newContext);
                pageQueue.offer(key);
                return newContext;
            }
        }

        /**
         * Returns the value associated with {@code key} in this cache, or {@code null} if there is no
         * cached value for {@code key}.
         *
         * @param key
         * @since 11.0
         */
        @Nullable
        @Override
        public PageContext getIfPresent(Object key) {
            return map.get(key);
        }

        @Override
        public void cleanUp() {
            map.clear();
            pageQueue.clear();
            super.cleanUp();
        }
    }
}
