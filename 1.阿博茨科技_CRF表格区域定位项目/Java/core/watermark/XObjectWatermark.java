package com.abcft.pdfextract.core.watermark;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;

import java.io.IOException;

public abstract class XObjectWatermark extends AbstractWatermark<PDXObject> {

    protected XObjectWatermark(PDDocument document) {
        super(document);
    }

    @Override
    public Iterable<COSName> getCandidatesNames(PDResources resources) {
        return resources.getXObjectNames();
    }

    @Override
    public PDXObject getCandidateItem(PDResources resources, COSName name) throws IOException {
        return resources.getXObject(name);
    }

    @Override
    public void removeCandidateItem(PDResources resources, COSName name) {
        resources.put(name, (PDXObject)null);
    }

}
