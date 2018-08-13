package com.abcft.pdfextract.core;


import com.abcft.pdfextract.spi.Document;

/**
 * Created by dhu on 2017/12/8.
 */
public interface Extractor<T, TParams extends ExtractParameters,
        TItem extends ExtractedItem,
        TResult extends ExtractionResult<TItem>,
        TCallback extends ExtractCallback<TItem, TResult>> {

    TResult process(Document<T> document, TParams params, TCallback callback);

    /**
     * 算法的版本。
     *
     * 如果有主要更改，需要重跑旧数据，请让百位加1，个位和十位清零或者保留。
     * 如果只是一些小改动，不需要重跑旧数据，只增加个位和十位。
     */
    int getVersion();

}