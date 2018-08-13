package com.abcft.pdfextract.core.watermark;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

public class HiborWatermark extends XObjectWatermark {

    HiborWatermark(PDDocument document) {
        super(document);
    }

    @Override
    public boolean match(COSName name, PDXObject object) {
        if (!(object instanceof PDImageXObject)) {
            return false;
        }
        return name.getName().startsWith("QQAPIm");
    }

}
