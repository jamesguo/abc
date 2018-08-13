package com.abcft.pdfextract.core;

import com.abcft.pdfextract.util.IntegerInterval;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.util.List;

/**
 * Basic callback for PDF split.
 *
 * Created by chzhong on 17-3-2.
 */
public interface SplitCallback {


    /**
     * Called when error occurs while splitting of the PDF.
     *
     * After calling this method, splitting might continue.
     *
     * @param segment the segment of the pages.
     * @param e the error occurred.
     */
    void onSplitError(IntegerInterval segment, Throwable e);

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
     * Called while a single part is split from a PDF.
     *
     * @param index the index of the segment.
     * @param segment the segment of the pages.
     * @param part the part of split document.
     * @return {@code true} means we should continue splitting, {@code false} otherwise.
     */
    boolean onSplit(int index, IntegerInterval segment, PDDocument part);

    /**
     * Called when splitting finished.
     *
     * Note that this will also be called if any {@link #onSplitError(IntegerInterval, Throwable)} (Throwable)} happened.
     *
     * @param result all split documents.
     */
    default void onFinished(List<PDDocument> result) {

    }
}
