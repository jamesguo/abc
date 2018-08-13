package com.abcft.pdfextract.core;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.io.IOException;
import java.io.Writer;

/**
 * Interface of extractors.
 *
 * Created by chzhong on 17-3-2.
 */
public interface HtmlExtractor<TParams extends ExtractParameters,
        TItem extends ExtractedItem,
        TResult extends ExtractionResult<TItem>,
        TCallback extends ExtractCallback<TItem, TResult>> extends Extractor<Document, TParams, TItem, TResult, TCallback> {
}
