package com.abcft.pdfextract.core.watermark;

import org.apache.pdfbox.pdmodel.PDDocument;

/**
 * Basic callback for PDF split.
 *
 * Created by chzhong on 17-3-2.
 */
public interface WatermarkHelperCallback {

    /**
     * Called when fatal error while processing the PDF.
     *
     * @param e the error occurred.
     */
    void onRemovalError(Throwable e);

    /**
     * Called when fatal error while processing the PDF.
     *
     * @param e the error occurred.
     */
    void onFatalError(Throwable e);

    /**
     * Called before split.
     *
     * @param document the PDF document that is being processed.
     */
    default void onStart(PDDocument document) {

    }

    /**
     * Called when removing finished.
     *
     * @param result the document.
     */
    default void onFinished(PDDocument result) {

    }
}
