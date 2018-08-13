package com.abcft.pdfextract.core.watermark;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;

public class NxnyWatermark extends ExtendedGraphicStateWatermark {

    NxnyWatermark(PDDocument document) {
        super(document);
    }

    @Override
    public boolean match(COSName name, PDExtendedGraphicsState item) {
        return name.getName().startsWith("Xi");
    }
}
