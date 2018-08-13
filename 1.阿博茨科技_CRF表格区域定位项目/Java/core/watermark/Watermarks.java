package com.abcft.pdfextract.core.watermark;

import org.apache.pdfbox.pdmodel.PDDocument;

public final class Watermarks {

    public static Watermark[] createWatermarks(PDDocument document) {
        return new Watermark[] {
                new HiborWatermark(document),
                new NxnyWatermark(document),
                new JuchaoWatermark(document)
        };
    }

    public static Watermark createHiborWatermarks(PDDocument document) {
        return new HiborWatermark(document);
    }

    public static Watermark createNxnyWatermarks(PDDocument document) {
        return new NxnyWatermark(document);
    }

    public static Watermark createJuchaoWatermarks(PDDocument document) {
        return new JuchaoWatermark(document);
    }
}
