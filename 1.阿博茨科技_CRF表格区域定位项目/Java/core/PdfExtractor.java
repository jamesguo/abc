package com.abcft.pdfextract.core;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;

import java.io.IOException;
import java.io.Writer;

/**
 * Interface of extractors.
 *
 * Created by chzhong on 17-3-2.
 */
public interface PdfExtractor<TParams extends ExtractParameters,
        TItem extends ExtractedItem,
        TResult extends ExtractionResult<TItem>,
        TCallback extends ExtractCallback<TItem, TResult>> extends Extractor<PDDocument, TParams, TItem, TResult, TCallback> {

    void processPage(PDDocument document, int pageIndex, PDPage page, TParams params, TResult result, TCallback callback);

    void postProcessing(PDDocument document, TParams params, TResult result, TCallback callback);

}
