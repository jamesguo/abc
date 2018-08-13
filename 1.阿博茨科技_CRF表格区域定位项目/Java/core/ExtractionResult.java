package com.abcft.pdfextract.core;

import com.abcft.pdfextract.util.Taggable;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Represents result of an extraction.
 * Created by chzhong on 17-3-2.
 */
public abstract class ExtractionResult<T extends ExtractedItem> implements Taggable {

    public static final String TAG_EXTRACT_DURATION = "extract.duration";

    private Throwable lastError;
    private final ArrayList<Throwable> errors = new ArrayList<>();
    private final Map<String, Object> tags = new HashMap<>();  // 辅助标记信息

    @Override
    public void addTag(String key, Object value) {
        tags.put(key, value);
    }

    @Override
    public Object getTag(String key) {
        return tags.get(key);
    }

    @Override
    public boolean hasTag(String key) {
        return tags.containsKey(key);
    }

    @Override
    public void clearTags() {
        tags.clear();
    }

    @Override
    public void removeTag(String key) {
        tags.remove(key);
    }

    public long getExtractDuration() {
        if (tags.containsKey(TAG_EXTRACT_DURATION)) {
            return getTag(TAG_EXTRACT_DURATION, Long.class);
        } else {
            return -1;
        }
    }

    public void setExtractDuration(long duration) {
        tags.put(TAG_EXTRACT_DURATION, duration);
    }

    /**
     * Clear all errors.
     */
    public void clearErrors() {
        this.errors.clear();
        this.lastError = null;
    }

    /**
     * Determine whether there is any error.
     *
     * @return {@code true} if there is any error, {@code false} otherwise.
     */
    public boolean hasError() {
        return null != lastError;
    }

    /**
     * Get all errors.
     *
     * @return a list of errors, or an empty list if there is no error.
     */
    public List<Throwable> getErrors() {
        return Collections.unmodifiableList(this.errors);
    }

    /**
     * Get all errors.
     *
     * @return a list of errors, or an empty list if there is no error.
     */
    public List<String> getErrorStrings() {
        return this.errors.stream().map(e -> {
            try(StringWriter sw = new StringWriter()) {
                e.printStackTrace(new PrintWriter(sw,  true));
                return sw.toString();
            } catch (IOException ex) {
                // Impossible
            }
            return e.toString();
        }).collect(Collectors.toList());
    }

    /**
     * Retrieve the number of errors.
     *
     * @return the number of errors.
     */
    public int getErrorCount() {
        return this.errors.size();
    }

    /**
     * Record an error and mark it as the last error.
     * @param e the error to record.
     */
    public void recordError(Throwable e) {
        this.lastError = e;
        this.errors.add(e);
    }

    /**
     * Merge errors.
     *
     * @param errors errors to merge.
     * @param lastError the last error to merge.
     */
    public void addErrors(List<Throwable> errors, Throwable lastError) {
        this.errors.addAll(errors);
        this.lastError = lastError;
    }

    /**
     * Get the last error.
     *
     * @return the last error, or {@code null} if there is no error.
     */
    public Throwable getLastError() {
        return this.lastError;
    }

    /**
     * Get the extracted items.
     *
     * @return a list of extracted items.
     */
    public abstract List<T> getItems();

    /**
     * Determine if there is any extracted items.
     *
     * @return {@code true} if there is any extracted item, {@code false} otherwise.
     */
    public abstract boolean hasResult();


}
