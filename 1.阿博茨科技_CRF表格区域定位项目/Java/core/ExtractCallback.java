package com.abcft.pdfextract.core;

/**
 * Basic callback for PDF extraction.
 *
 * Created by chzhong on 17-3-2.
 */
public interface ExtractCallback<TItem extends ExtractedItem,
        TResult extends ExtractionResult<TItem>> {

    /**
     * Called when error occurs while extracting part of the PDF.
     *
     * After calling this method, extraction might continue.
     *
     * @param e the error occurred.
     */
    void onExtractionError(Throwable e);

    /**
     * Called when error occurs while loading the PDF.
     *
     * @param e the error occurred.
     */
    void onLoadError(Throwable e);

    /**
     * Called when fatal error while processing the PDF.
     *
     * @param e the error occurred.
     */
    void onFatalError(Throwable e);

    /**
     * Called before extraction.
     *
     * @param document the PDF document that is being processed.
     */
    default void onStart(Object document) {

    }

    /**
     * Called while a single item is extracted from a PDF page.
     *
     * @param item the extracted item.
     */
    default void onItemExtracted(TItem item) {

    }

    /**
     * Called when extraction finished.
     *
     * Note that this will also be called if any {@link #onExtractionError(Throwable)} happened.
     *
     * @param result the result of the extraction.
     */
    void onFinished(TResult result);

}
