package com.abcft.pdfextract.core;

import com.google.gson.JsonObject;

/**
 * Extracted result.
 *
 * Created by chzhong on 17-3-2.
 */
public interface ExtractedItem {

    /**
     * Dump an extracted item as a JSON {@link JsonObject}.
     *
     * @return a JSON {@link JsonObject} with minimal information.
     */
    default JsonObject toDocument() {
        return toDocument(false);
    }

    /**
     * Dump an extracted item as a JSON {@link JsonObject}.
     *
     * @param detail whether detailed data should be included.
     *
     * @return a JSON {@link JsonObject} with/without detailed information.
     */
    JsonObject toDocument(boolean detail);

}
