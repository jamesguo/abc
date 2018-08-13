package com.abcft.pdfextract.core.watermark;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;

public abstract class ExtendedGraphicStateWatermark extends AbstractWatermark<PDExtendedGraphicsState> {

    protected ExtendedGraphicStateWatermark(PDDocument document) {
        super(document);
    }

    @Override
    public Iterable<COSName> getCandidatesNames(PDResources resources) {
        return resources.getExtGStateNames();
    }

    @Override
    public PDExtendedGraphicsState getCandidateItem(PDResources resources, COSName name) {
        return resources.getExtGState(name);
    }

    @Override
    public void removeCandidateItem(PDResources resources, COSName name) {
        resources.put(name, (PDExtendedGraphicsState)null);
    }

}
