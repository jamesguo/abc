package com.abcft.pdfextract.core.watermark;

import org.apache.commons.lang3.StringUtils;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;

public class JuchaoWatermark extends XObjectWatermark {

    JuchaoWatermark(PDDocument document) {
        super(document);
    }

    @Override
    public boolean match(COSName name, PDXObject object) {
        if (!(object instanceof PDFormXObject)) {
            return false;
        }
        try {
            PDFormXObject form = (PDFormXObject)object;
            COSDictionary ocDict = (COSDictionary) form.getCOSObject().getDictionaryObject(COSName.OC);
            COSDictionary ocgsDict = (COSDictionary) ocDict.getDictionaryObject(COSName.OCGS);
            String ocgsName = ocgsDict.getString(COSName.NAME);
            return StringUtils.equalsIgnoreCase(ocgsName, "Watermark");
        } catch (ClassCastException e) {
            return false;
        }
    }
}
