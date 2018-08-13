package com.abcft.pdfextract.core;

import com.abcft.pdfextract.core.gson.GsonUtil;
import com.abcft.pdfextract.util.MapUtils;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import org.apache.commons.lang3.StringUtils;

import java.awt.geom.Rectangle2D;
import java.lang.reflect.Type;
import java.util.*;

/**
 * Parameters for extractors.
 *
 * Created by chzhong on 17-3-7.
 */
public abstract class ExtractParameters implements Cloneable {


    public static final class PageAreaMap extends LinkedHashMap<Integer, List<Rectangle2D>> {

        private static List<Rectangle2D> ensureList(Map<Integer, List<Rectangle2D>> map, int key) {
            List<Rectangle2D> list = null;
            if (map.containsKey(key)) {
                list = map.get(key);
            }
            if (null == list) {
                list = new ArrayList<>();
                map.put(key, list);
            }
            return list;
        }

        public PageAreaMap(int initialCapacity, float loadFactor) {
            super(initialCapacity, loadFactor);
        }

        public PageAreaMap(int initialCapacity) {
            super(initialCapacity);
        }

        public PageAreaMap() {
        }

        public PageAreaMap(Map<? extends Integer, ? extends List<Rectangle2D>> m) {
            super(m);
        }

        public PageAreaMap(int initialCapacity, float loadFactor, boolean accessOrder) {
            super(initialCapacity, loadFactor, accessOrder);
        }

        public static PageAreaMap parse(String json) {
            if (StringUtils.isBlank(json)) {
                return new PageAreaMap();
            }
            try {
                Type type = new TypeToken<Map<Integer, List<Rectangle2D>>>(){}.getType();
                Map<Integer, List<Rectangle2D>> map = GsonUtil.DEFAULT.fromJson(json, type);
                return new PageAreaMap(map);
            } catch (Exception e) {
                return new PageAreaMap();
            }
        }

        /**
         * Adds areas to page.
         *
         * @param page the page index of the areas.
         * @param areas areas in page space.
         */
        public void addAreas(int page, Collection<Rectangle2D> areas) {
            ensureList(this, page).addAll(areas);
        }

        /**
         * Adds area to page.
         *
         * @param page the page index of the area.
         * @param area area in page space.
         */
        public void addArea(int page, Rectangle2D area) {
            ensureList(this, page).add(area);
        }

        /**
         * Remove area from page.
         *
         * @param page the page index of the area.
         * @param area area to remove in page space.
         */
        public void removeArea(int page, Rectangle2D area) {
            ensureList(this, page).remove(area);
        }

        /**
         * Remove areas from page.
         *
         * @param page the page index of the area.
         * @param areas areas to remove in page space.
         */
        public void removeAreas(int page, Collection<Rectangle2D> areas) {
            ensureList(this, page).removeAll(areas);
        }

        /**
         * Clear areas from page.
         *
         * @param page the page index of the area.
         */
        public void clearAreas(int page) {
            ensureList(this, page).clear();
        }

        /**
         * Get areas of page.
         *
         * @param page the page index of the area.
         * @return the areas of the page, or an empty list.
         */
        public List<Rectangle2D> getAreas(int page) {
            return ensureList(this, page);
        }

    }

    public abstract static class Builder<T extends ExtractParameters> {

        int startPageIndex = 0;
        int endPageIndex = Integer.MAX_VALUE;
        boolean debug;
        protected boolean savePreviewImage = true;
        private boolean layoutAnalysis = true;

        PageAreaMap ignoredAreas = new PageAreaMap();
        PageAreaMap hintAreas = new PageAreaMap();
        String path;
        String source;
        final Map<String, Object> meta = new HashMap<>();

        private ExtractContext context;


        public Builder() {
        }

        public Builder(Map<String, String> params) {
            this.startPageIndex = MapUtils.getInt(params, "startPage", 1) - 1;
            this.endPageIndex = MapUtils.getInt(params, "endPage", Integer.MAX_VALUE) - 1;
            this.source = params.get("source");
            this.path = params.get("path");
            this.layoutAnalysis = MapUtils.getBoolean(params, "layoutAnalysis", layoutAnalysis);
            // TODO Support meta
        }

        /**
         * Sets the page index to process.
         *
         * @param pageIndex the index of the page (0-based) to process.
         * @return this builder.
         */
        public Builder<T> setPageIndex(int pageIndex) {
            this.startPageIndex = pageIndex;
            this.endPageIndex = pageIndex;
            return this;
        }

        /**
         * Sets the start page index to process.
         *
         * Default value is 0.
         *
         * @param startPageIndex the index of the first page (0-based) to process.
         * @return this builder.
         */
        public Builder<T> setStartPageIndex(int startPageIndex) {
            this.startPageIndex = startPageIndex;
            return this;
        }

        /**
         * Sets the last page index to process.
         *
         * Default value is {@value Integer#MAX_VALUE}.
         *
         * @param endPageIndex the index of the last page (0-based) to process.
         * @return this builder.
         */
        public Builder<T> setEndPageIndex(int endPageIndex) {
            this.endPageIndex = endPageIndex;
            return this;
        }

        /**
         * Adds areas to ignore for write targets.
         *
         * @param areaMap a area map contains ignored areas.
         * @return this builder.
         */
        public Builder<T> addIgnoreAreas(PageAreaMap areaMap) {
            if  (null == areaMap || areaMap.isEmpty()) {
                return this;
            }
            areaMap.forEach((page, areas) -> {
                if (page < 0) {
                    page = -1;
                }
                this.ignoredAreas.addAreas(page, areas);
            });
            return this;
        }

        /**
         * Adds areas to ignore for write targets.
         *
         * @param page the page index of the areas.
         * @param ignoredAreas areas to ignore of the write targets in page space.
         * @return this builder.
         */
        public Builder<T> addIgnoreAreas(int page, List<Rectangle2D> ignoredAreas) {
            if  (null == ignoredAreas || ignoredAreas.isEmpty()) {
                return this;
            }
            if (page < 0) {
                page = -1;
            }
            this.ignoredAreas.addAreas(page, ignoredAreas);
            return this;
        }

        /**
         * Adds areas to ignore for write targets.
         *
         * @param page the page index of the area.
         * @param ignoredArea area to ignore of the write targets in page space.
         * @return this builder.
         */
        public Builder<T> addIgnoreArea(int page, Rectangle2D ignoredArea) {
            this.ignoredAreas.addArea(page, ignoredArea);
            return this;
        }

        public Builder<T> setIgnoreAreas(String json) {
            this.ignoredAreas = PageAreaMap.parse(json);
            return this;
        }

        /**
         * Adds areas to hint for write targets.
         *
         * @param areaMap a area map contains hint areas.
         * @return this builder.
         */
        public Builder<T> addHintAreas(PageAreaMap areaMap) {
            if  (null == areaMap || areaMap.isEmpty()) {
                return this;
            }
            areaMap.forEach((page, areas) -> {
                if (page < 0) {
                    page = -1;
                }
                this.hintAreas.addAreas(page, areas);
            });
            return this;
        }


        /**
         * Adds hint areas for write targets.
         * @param page the page index of the write target.
         * @param hintAreas areas of the write targets in page space.
         * @return this builder.
         */
        public Builder<T> addHintAreas(int page, List<Rectangle2D> hintAreas) {
            if  (null == hintAreas || hintAreas.isEmpty()) {
                return this;
            }
            this.hintAreas.addAreas(page, hintAreas);
            return this;
        }

        /**
         * Adds a hint area for a write target.
         * @param page the page index of the write target.
         * @param hintArea the area of the write target in page space.
         * @return this builder.
         */
        public Builder<T> addHintAreas(int page, Rectangle2D hintArea) {
            this.hintAreas.addArea(page, hintArea);
            return this;
        }

        public Builder<T> setHintAreas(String json) {
            this.hintAreas = PageAreaMap.parse(json);
            return this;
        }

        /**
         * sets whether we should run in debug mode.
         *
         * @param debug true if we wants to run in debug mode.
         * @return this builder.
         */
        public Builder<T> setDebug(boolean debug) {
            this.debug = debug;
            return this;
        }

        /**
         * sets the path of the PDF file, this is used as hint.
         *
         * @param path path of the PDF file.
         * @return this builder.
         */
        public Builder<T> setPath(String path) {
            this.path = path;
            return this;
        }

        /**
         * Set the document source.
         * @param source the name of the document source.
         * @return this builder.
         */
        public Builder<T> setSource(String source) {
            this.source = source;
            return this;
        }

        /**
         * Add a single meta value.
         *
         * @param key the key
         * @param value the value
         * @return this builder.
         */
        public Builder<T> addMeta(String key, Object value) {
            this.meta.put(key, value);
            return this;
        }

        /**
         * Remove a meta value.
         *
         * @param key the key
         * @return this builder.
         */
        public Builder<T> removeMeta(String key) {
            this.meta.remove(key);
            return this;
        }

        /**
         * Clear meta values.
         *
         * @return this builder.
         */
        public Builder<T> clearMeta() {
            this.meta.clear();
            return this;
        }


        /**
         * Add meta of the document.
         * @param meta the meta of the document.
         * @return this builder.
         */
        public Builder<T> addMetas(Map<String, Object> meta) {
            this.meta.putAll(meta);
            return this;
        }

        /**
         * Set whether to save preview image of extracted item.
         *
         * @param savePreviewImage the name of the document source.
         * @return this builder.
         */
        public Builder<T> setPreviewImage(boolean savePreviewImage) {
            this.savePreviewImage = savePreviewImage;
            return this;
        }

        /**
         * Set whether to enable layout analysis algorithm.
         *
         * @param useLayoutAnalysis true to enable layout analysis, false otherwise.
         * @return this builder.
         */
        public Builder<T> setLayoutAnalysis(boolean useLayoutAnalysis) {
            this.layoutAnalysis = useLayoutAnalysis;
            return this;
        }

        /**
         * Creates an new instance of the parameters.
         *
         * @return the new instance of parameters.
         */
        public abstract T build();

        public Builder<T> setContext(ExtractContext context) {
            this.context = context;
            return this;
        }
    }


    public ExtractParameters(Builder<?> builder) {
        this.startPageIndex = builder.startPageIndex;
        this.endPageIndex = builder.endPageIndex;
        this.ignoredAreas = new PageAreaMap(builder.ignoredAreas);
        this.hintAreas = new PageAreaMap(builder.hintAreas);
        this.debug = builder.debug;
        this.path = builder.path;
        this.savePreviewImage = builder.savePreviewImage;
        this.useLayoutAnalysis = builder.layoutAnalysis;
        this.source = builder.source;
        this.meta = builder.meta;
        this.publisher = (String) builder.meta.getOrDefault("publish", null);
        this.context = builder.context;
    }

    /**
     * Populates the builder with current parameters.
     * @param builder the builder to populate with.
     */
    protected <T extends Builder> T buildUpon(T builder) {
        builder.setPath(path)
                .setDebug(debug)
                .setStartPageIndex(startPageIndex)
                .setEndPageIndex(endPageIndex)
                .addIgnoreAreas(ignoredAreas)
                .addHintAreas(hintAreas)
                .setSource(source)
                .addMetas(meta)
                .setPreviewImage(savePreviewImage)
                .setLayoutAnalysis(useLayoutAnalysis)
                .setContext(context);
        return builder;
    }

    public boolean containsPage(int pageIndex) {
        return this.startPageIndex <= pageIndex && pageIndex <= this.endPageIndex;
    }

    /**
     * Create a the builder with current parameters.
     *
     * @return a new builder with current parameters.
     */
    public abstract Builder buildUpon();

    /**
     * Path of the PDF file.
     * <b>Might be {@code null}, might be meaningless names.</b>
     */
    public final String path;
    /**
     * Debug mode.
     */
    public final boolean debug;
    /**
     * Save preview image of extracted item.
     */
    public final boolean savePreviewImage;
    /**
     * Enable layaout analysis algorithm.
     */
    public final boolean useLayoutAnalysis;
    /**
     * Index (zero-based) of the first page to process.
     */
    public final int startPageIndex;
    /**
     * Index (zero-based) of the last page to process.
     */
    public final int endPageIndex;
    /**
     * Ignored areas for extraction.
     */
    public final PageAreaMap ignoredAreas;
    /**
     * Hint areas for extraction.
     */
    public final PageAreaMap hintAreas;
    /**
     * Source name of the document, might be {@code null}.
     */
    public final String source;
    /**
     * Publisher of the document, might be {@code null}.
     */
    public final String publisher;
    /**
     * the meta of the document.
     */
    public final Map<String, Object> meta;

    public ExtractContext context;

    public <D, T extends ExtractContext<D>> T getExtractContext() {
        //noinspection unchecked
        return (T)context;
    }

}
