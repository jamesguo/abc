package com.abcft.pdfextract.core.watermark;

import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;

import java.io.IOException;

public class WatermarkHelper {

    private MemoryUsageSetting memoryUsageSetting = null;

    /**
     * @return the current memory setting.
     */
    public MemoryUsageSetting getMemoryUsageSetting()
    {
        return memoryUsageSetting;
    }

    /**
     * Set the memory setting.
     *
     * @param memoryUsageSetting
     */
    public void setMemoryUsageSetting(MemoryUsageSetting memoryUsageSetting)
    {
        this.memoryUsageSetting = memoryUsageSetting;
    }

    /**
     * Remove watermarks from specified document.
     *
     * @param document The document to split.
     * @param callback optional callback.
     * @return The document with watermark removed.
     */
    public PDDocument removeWatermark(PDDocument document, WatermarkHelperCallback callback)
    {
        if (callback != null) {
            callback.onStart(document);
        }
        Watermark[] watermarks = Watermarks.createWatermarks(document);
        for (PDPage page : document.getPages()) {
            for (Watermark watermark : watermarks) {
                try {
                    watermark.remove(page);
                } catch (IOException e) {
                    if (callback != null) {
                        callback.onRemovalError(e);
                    }
                }
            }
        }
        if (callback != null) {
            callback.onFinished(document);
        }
        return document;
    }
}
