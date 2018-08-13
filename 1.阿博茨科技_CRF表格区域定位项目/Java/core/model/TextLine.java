package com.abcft.pdfextract.core.model;


import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 表示文本行, 包含至少一个文本块(TextBlock)
 * Created by xwang on 2018/07/12
 */
public class TextLine extends RectangularTextContainer<TextBlock> {

    private String text;
    private List<TextBlock> textBlockList;
    private Rectangle2D visibleBBox = new Rectangle2D.Double();


    public TextLine() {
        this.textBlockList = new ArrayList<>();
        this.text = "";
    }

    public TextLine(TextBlock textBlock) {
        super((float) textBlock.getLeft(), (float) textBlock.getTop(), (float) textBlock.getWidth(), (float) textBlock.getHeight());
        this.textBlockList = new ArrayList<>();
        this.textBlockList.add(textBlock);
        this.text = textBlock.getText();
        if (!textBlock.getVisibleBBox().isEmpty()) {
            visibleBBox.setRect(textBlock.getVisibleBBox());
        }
    }

    public TextLine(List<TextBlock> textBlocks) {
        this();
        for (TextBlock textBlock: textBlocks) {
            addElement(textBlock);
        }
    }

    @Override
    public void addElement(TextBlock textBlock) {
        if (textBlock == null || textBlock.isEmpty()) {
            return;
        }

        if (textBlockList.isEmpty()) {
            text = textBlock.getText();
            setRect(textBlock.getBounds2D());
            if (!textBlock.getVisibleBBox().isEmpty()) {
                visibleBBox.setRect(textBlock.getVisibleBBox());
            }
        } else {
            text = text + textBlock.getText();
            Rectangle2D.union(this, textBlock.getBounds2D(), this);
            if (!textBlock.getVisibleBBox().isEmpty()) {
                if (visibleBBox.isEmpty()) {
                    visibleBBox.setRect(textBlock.getVisibleBBox());
                } else {
                    Rectangle2D.union(visibleBBox, textBlock.getVisibleBBox(), visibleBBox);
                }
            }
        }
        textBlockList.add(textBlock);
    }

    @Override
    public void addElements(List<TextBlock> textBlocks) {
        for (TextBlock textBlock : textBlocks) {
            addElement(textBlock);
        }
    }

    @Override
    public List<TextBlock> getElements() {
        return textBlockList;
    }

    public TextBlock getElementBlockType(int index) {
        return textBlockList.get(index);
    }

    public TextChunk getElementChunkType(int index) {
        return new TextChunk(getElementBlockType(index));
    }

    public List<TextChunk> getElementsChunkType(boolean fillSpace) {
        if (fillSpace) {
            return textBlockList.stream().map(TextChunk::new).collect(Collectors.toList());
        } else {
            return textBlockList.stream().map(TextBlock::toTextChunk).collect(Collectors.toList());
        }
    }

    public Rectangle2D getVisibleBBox() {
        return visibleBBox;
    }

    public double getVisibleMinX() {
        return visibleBBox.getMinX();
    }

    public double getVisibleMinY() {
        return visibleBBox.getMinY();
    }

    public double getVisibleMaxX() {
        return visibleBBox.getMaxX();
    }

    public double getVisibleMaxY() {
        return visibleBBox.getMaxY();
    }

    public double getVisibleWidth() {
        return visibleBBox.getWidth();
    }

    public double getVisibleHeight() {
        return visibleBBox.getHeight();
    }

    public int getSize() {
        return textBlockList.size();
    }

    @Override
    public String getText() {
        return text;
    }

    @Override
    public String getText(boolean useLineReturns) {
        return text;
    }
}
