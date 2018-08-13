package com.abcft.pdfextract.core.model;


import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDMarkedContent;
import org.apache.pdfbox.pdmodel.documentinterchange.taggedpdf.PDArtifactMarkedContent;

public class TextChunkItem extends ContentItem<TextChunk> {

    TextChunkItem(TextChunk item) {
        super(item);
    }

    @Override
    public void setMarkedContent(PDMarkedContent markedContent) {
        super.setMarkedContent(markedContent);
        TextChunk textChunk = getItem();
        if (isPageFooter()) {
            textChunk.setPaginationType(PaginationType.FOOTER);
        } else if (isPageHeader()) {
            textChunk.setPaginationType(PaginationType.HEADER);
        } else if (isPagination()) {
            textChunk.setPaginationType(PaginationType.UNSPECIFIED);
        } else if (markedContent instanceof PDArtifactMarkedContent) {
            textChunk.setPaginationType(PaginationType.UNSPECIFIED);
        }
        if (markedContent != null) {
            textChunk.setMcid(getMcid());
            if (markedContent.getActualText() != null) {
                String actualText = markedContent.getActualText();
                textChunk.setActualText(actualText);
            }
            PDStructureElement element = getStructureElement();
            if (element != null) {
                textChunk.setStructureTypes(getStructureTypeList(element));
                textChunk.setStructElementId(element.getElementIdentifier());
            }
        }
    }

    public boolean canMerge(TextChunkItem other) {
        return markedContent == other.markedContent
                && markedContent != null && markedContent.getActualText() != null;
    }

    public void merge(TextChunkItem other) {
        getItem().merge(other.getItem());
    }

    @Override
    public void setPageHeader(boolean pageHeader) {
        super.setPageHeader(pageHeader);
        getItem().setPaginationType(PaginationType.HEADER);
    }

    @Override
    public void setPageFooter(boolean pageFooter) {
        super.setPageFooter(pageFooter);
        getItem().setPaginationType(PaginationType.FOOTER);
    }

}
