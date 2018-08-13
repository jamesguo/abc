package com.abcft.pdfextract.core.content;

import com.abcft.pdfextract.core.ExtractedItem;
import com.abcft.pdfextract.core.gson.GsonUtil;
import com.abcft.pdfextract.core.gson.Summary;
import com.abcft.pdfextract.core.model.Rectangle;
import com.abcft.pdfextract.core.model.TextChunk;
import com.abcft.pdfextract.core.util.NumberUtil;
import com.abcft.pdfextract.util.JsonUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Created by dhu on 2018/1/12.
 */
public class Page implements ExtractedItem {

    public static class TextGroup extends Rectangle {

        private List<TextChunk> textChunks;
        private boolean autoResize = true;
        private boolean isPageHeader = false;
        private boolean isPageFooter = false;
        private boolean isPageLeftWing = false;
        private boolean isPageRightWing = false;

        public TextGroup(Rectangle2D rectangle) {
            super(rectangle);
            textChunks = new ArrayList<>();
        }

        public void setAutoResize(boolean autoResize) {
            this.autoResize = autoResize;
        }

        public List<TextChunk> getTextChunks() {
            return textChunks.stream().map(TextChunk::new).collect(Collectors.toList());
        }

        public int getTextChunkCount() {
            return textChunks.size();
        }

        private void sortTexts() {
            textChunks.sort(Comparator.comparing(TextChunk::getGroupIndex));
        }

        public void addText(TextChunk textChunk) {
            textChunks.add(textChunk);
            if (autoResize) {
                merge(textChunk);
            }
        }

        public void addTexts(List<TextChunk> textChunks) {
            if (textChunks == null) {
                return;
            }
            for (TextChunk textChunk : textChunks) {
                addText(textChunk);
            }
        }

        public void setTexts(List<TextChunk> textChunks) {
            if (textChunks == null) {
                this.textChunks = new ArrayList<>();
            } else {
                this.textChunks = new ArrayList<>(textChunks);
            }
        }

        public void setPageHeader(boolean isPageHeader) {
            this.isPageHeader = isPageHeader;
        }

        public boolean isPageHeader() {
            return this.isPageHeader;
        }

        public void setPageFooter(boolean isPageFooter) {
            this.isPageFooter = isPageFooter;
        }

        public boolean isPageFooter() {
            return this.isPageFooter;
        }

        public void setPageLeftWing(boolean isPageLeftWing) {
            this.isPageLeftWing = isPageLeftWing;
        }

        public boolean isPageLeftWing() {
            return this.isPageLeftWing;
        }

        public void setPageRightWing(boolean isPageRightWing) {
            this.isPageRightWing = isPageRightWing;
        }

        public boolean isPageRightWing() {
            return this.isPageRightWing;
        }

        public void merge(TextGroup textGroup) {
            if (autoResize) {
                super.merge(textGroup);
            }
            this.textChunks.addAll(textGroup.textChunks);
        }

        public boolean canAdd(Rectangle rectangle) {
            return intersects(rectangle);
        }

        public boolean canMerge(TextGroup other) {
            if (this == other) {
                return false;
            }
            if (super.contains(other)) {
                return true;
            }
            if (textChunks.size() >= 5 && getMinX() <= other.getMinX() && getMaxX() >= other.getMaxX()) {
                return true;
            }
            return false;
        }

        /**
         * 取textGroup里text内容的左边界（非空格）
         */
        public float getTextLeft(){
            float textLeft = (float) this.getMaxX();
            for(TextChunk textChunk : this.getTextChunks()){
                if (textChunk.getTextLeft()< textLeft) {
                    textLeft = textChunk.getTextLeft();
                }
            }
            return textLeft;
        }

        /**
         * 取textGroup里text内容的右边界（非空格）
         */
        public float getTextRight(){
            float textRight = (float) this.getMinX();
            for(TextChunk textChunk : this.getTextChunks()){
                if (textChunk.getTextRight() > textRight) {
                    textRight = textChunk.getTextRight();
                }
            }
            return textRight;
        }

    }

    private TextGroup header;
    private TextGroup footer;
    private TextGroup leftWing;
    private TextGroup rightWing;
    private List<TextGroup> textGroups;
    @Summary
    private final int pageNumber;
    @Summary
    private float pageWidth;
    @Summary
    private float pageHeight;
    private List<Paragraph> paragraphs;

    private boolean useStructInfo;

    public Page(int pageNumber) {
        this.pageNumber = pageNumber;
        textGroups = new ArrayList<>();
        paragraphs = new ArrayList<>();
    }

    public int getPageNumber() {
        return pageNumber;
    }

    public void setPageWidth(float pageWidth) {
        this.pageWidth = pageWidth;
    }

    public float getPageWidth() {
        return pageWidth;
    }

    public void setPageHeight(float pageHeight) {
        this.pageHeight = pageHeight;
    }

    public float getPageHeight() {
        return pageHeight;
    }

    public void setUseStructInfo(boolean useStructInfo) {
        this.useStructInfo = useStructInfo;
    }

    public boolean isUseStructInfo() {
        return useStructInfo;
    }

    public List<Paragraph> getParagraphs(boolean includeMocked, boolean includeCoverdParagraph) {
        List<Paragraph> allParagraphs = new ArrayList<>();
        for (Paragraph paragraph : paragraphs) {
            if (includeMocked || !paragraph.isMocked()) {
                allParagraphs.add(paragraph);
            }
            if (includeCoverdParagraph && paragraph.getCoveredParagraphs() != null) {
                allParagraphs.addAll(paragraph.getCoveredParagraphs());
            }
        }
        return allParagraphs;
    }

    public JsonArray getParagraphJson() {
        JsonArray paragraphs = new JsonArray();
        for (Paragraph paragraph : getParagraphs(true, true)) {
            if (!paragraph.isBlank()) {
                JsonObject paragraphObj = paragraph.toDocument(true);
                paragraphs.add(paragraphObj);
            }
        }
        return paragraphs;
    }

    public void setParagraphs(List<Paragraph> paragraphs) {
        this.paragraphs = paragraphs;
    }

    public void addParagraph(Paragraph p) {
        paragraphs.add(p);
    }

    @Override
    public JsonObject toDocument(boolean detail) {
        JsonObject json = new JsonObject();
        json.addProperty("pageIndex", getPageNumber()-1);
        if (isUseStructInfo()) {
            json.addProperty("useStruct", true);
        }
        JsonUtil.putDoubleAbbr(json, "w", getPageWidth());
        JsonUtil.putDoubleAbbr(json, "h", getPageHeight());
        if (detail) {
            json.add("paragraphs", getParagraphJson());
        }
        return json;
    }

    public Paragraph getFirstParagraph() {
        return getFirstParagraph(false);
    }

    public Paragraph getFirstParagraph(boolean allowPagination) {
        for (Paragraph p : paragraphs) {
            if (!p.isPagination() || allowPagination) {
                return p;
            }
        }
        return null;
    }

    public Paragraph getLastParagraph() {
        return getLastParagraph(false);
    }

    public Paragraph getLastParagraph(boolean allowPagination) {
        for (int i = paragraphs.size() - 1; i >= 0; --i) {
            Paragraph p = paragraphs.get(i);
            if (!p.isPagination() || allowPagination) {
                return p;
            }
        }
        return null;
    }

    public void setHeader(TextGroup header) {
        this.header = header;
    }

    public void setFooter(TextGroup footer) {
        this.footer = footer;
    }

    public void setLeftWing(TextGroup leftWing) {
        this.leftWing = leftWing;
    }

    public void setRightWing (TextGroup rightWing) {
        this.rightWing = rightWing;
    }

    public TextGroup getHeader() {
        return header;
    }

    public TextGroup getFooter() {
        return footer;
    }

    public TextGroup getLeftWing() {
        return leftWing;
    }

    public TextGroup getRightWing() {
        return rightWing;
    }

    public TextGroup findOrCreateGroup(TextChunk textChunk) {
        TextGroup best = textGroups.stream()
                .filter(textGroup -> textGroup.canAdd(textChunk))
                .min(Comparator.comparingDouble(o -> o.getCenter().distance(textChunk.getCenter()))).orElse(null);
        if (best != null) {
            return best;
        }
        TextGroup textGroup = new TextGroup(textChunk);
        textGroups.add(textGroup);
        return textGroup;
    }

    public void mergeGroups() {
        for (TextGroup textGroup : textGroups) {
            textGroup.sortTexts();
        }
        NumberUtil.sortByReadingOrder(textGroups);
    }

    public List<TextGroup> getTextGroups() {
        return textGroups;
    }

    public List<TextGroup> getAllGroups() {
        List<TextGroup> allTextGroups = new ArrayList<>();
        if (header != null) {
            allTextGroups.add(header);
        }

        if (leftWing != null) {
            allTextGroups.add(leftWing);
        }

        allTextGroups.addAll(this.textGroups);

        if (rightWing != null) {
            allTextGroups.add(rightWing);
        }

        if (footer != null) {
            allTextGroups.add(footer);
        }
        return allTextGroups;
    }

    public int getParagraphCount() {
        return paragraphs.size();
    }

    public void addGroup(TextGroup group) {
        textGroups.add(group);
    }

    public int indexOfTextGroup(Rectangle p) {
//        int start = header != null ? 1 : 0;
        int start = header != null ? (leftWing != null ? 2 : 1) : (leftWing != null ? 1 : 0);
        for (int i = 0; i < textGroups.size(); i++) {
            TextGroup group = textGroups.get(i);
            if (group.canAdd(p)) {
                return start + i;
            } else if (i < textGroups.size() - 1
                    && (group.getBottom() < p.getTop() && textGroups.get(i + 1).getTop() > p.getBottom()
                    || group.getBottom() == p.getBottom()
                        && group.getRight() < p.getLeft()
                        && textGroups.get(i + 1).getTop() > p.getBottom()))

            {
                return start + i + 1;
            } else if (i < textGroups.size() - 1
                    && group.getBottom() == p.getBottom()
                    && group.getLeft() < p.getRight()
                    && textGroups.get(i + 1).getTop() > p.getBottom()) {
                return start + i;
            } else if (i == textGroups.size() - 1
                    && (group.getBottom() < p.getTop() || group.getBottom() == p.getBottom() && group.getRight() < p.getLeft())) {
                return start + i + 1;
            } else if (i == textGroups.size() - 1 && group.getBottom() == p.getBottom() && group.getLeft() < p.getRight()) {
                return start + i;
            }
        }
        return start;
    }
}
