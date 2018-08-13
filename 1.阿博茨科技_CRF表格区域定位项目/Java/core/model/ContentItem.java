package com.abcft.pdfextract.core.model;

import org.apache.commons.lang3.StringUtils;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureNode;
import org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDMarkedContent;
import org.apache.pdfbox.pdmodel.documentinterchange.taggedpdf.PDArtifactMarkedContent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by dhu on 2017/5/9.
 */
public class ContentItem<T> {
    private final T item;
    private final Map<String, Object> tags = new HashMap<>();
    protected PDMarkedContent markedContent;

    private boolean isPagination;
    private boolean isPageFooter;
    private boolean isPageHeader;
    private boolean isFigure;
    private int mcid = -1;

    ContentItem(T item) {
        this.item = item;
    }

    public T getItem() {
        return item;
    }

    public void addTag(String key, Object value) {
        tags.put(key, value);
    }

    public Object getTag(String key) {
        return tags.get(key);
    }

    public boolean hasTag(String key) {
        return tags.containsKey(key);
    }

    public PDMarkedContent getMarkedContent() {
        return markedContent;
    }

    public boolean isPagination() {
        return isPagination;
    }

    public boolean isPageHeader() {
        return isPageHeader;
    }

    public void setPageHeader(boolean pageHeader) {
        isPageHeader = pageHeader;
    }

    public boolean isPageFooter() {
        return isPageFooter;
    }

    public void setPageFooter(boolean pageFooter) {
        isPageFooter = pageFooter;
    }

    public boolean isFigure() {
        return isFigure;
    }

    public int getMcid() {
        return mcid;
    }

    public void setMarkedContent(PDMarkedContent markedContent) {
        this.markedContent = markedContent;
        if (markedContent != null) {
            mcid = markedContent.getMCID();
            checkPagination(markedContent);
            COSDictionary properties = markedContent.getProperties();
            if (properties != null) {
                isPagination = properties.getBoolean("Pagination", false);
                isPageHeader = properties.getBoolean("Pagination.Header", false);
                isPageFooter = properties.getBoolean("Pagination.Footer", false);
            } else {
                isPagination = false;
                isPageHeader = false;
                isPageFooter = false;
            }
            isFigure = "Figure".equalsIgnoreCase(markedContent.getTag());
        } else {
            isPagination = false;
            isPageHeader = false;
            isPageFooter = false;
        }
    }

    public PDStructureElement getStructureElement() {
        if (markedContent == null) {
            return null;
        }
        COSBase k = markedContent.getProperties().getItem(COSName.K);
        if (k instanceof COSDictionary) {
            return new PDStructureElement((COSDictionary) k);
        }
        return null;
    }

    public List<String> getStructureTypeList() {
        PDStructureElement element = getStructureElement();
        if (element != null) {
            return getStructureTypeList(element);
        }
        return null;
    }

    static List<String> getStructureTypeList(PDStructureElement element) {
        List<String> list = new ArrayList<>();
        list.add(TextNode.getStandardStructureType(element));
        PDStructureNode parent = element.getParent();
        while (parent instanceof PDStructureElement) {
            list.add(0, TextNode.getStandardStructureType((PDStructureElement) parent));
            parent = ((PDStructureElement) parent).getParent();
        }
        return list;
    }

    @Override
    public String toString() {
        return item.toString();
    }


    public static void checkPagination(PDMarkedContent markedContent) {
        COSDictionary properties = markedContent.getProperties();
        if (markedContent instanceof PDArtifactMarkedContent) {
            PDArtifactMarkedContent artifact = (PDArtifactMarkedContent) markedContent;
            boolean topAttached = artifact.isTopAttached();
            boolean bottomAttached = artifact.isBottomAttached();
            boolean pagination = topAttached || bottomAttached;
            properties.setBoolean("Pagination", pagination);
            if (pagination) {
                properties.setBoolean("Pagination.Header", topAttached);
                properties.setBoolean("Pagination.Footer", bottomAttached);
            }
        }
    }
}

