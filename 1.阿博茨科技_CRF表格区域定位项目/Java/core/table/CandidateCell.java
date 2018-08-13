package com.abcft.pdfextract.core.table;

import com.abcft.pdfextract.core.model.Rectangle;
import com.abcft.pdfextract.core.model.RectangularTextGroup;
import com.abcft.pdfextract.core.model.TextChunk;
import com.abcft.pdfextract.core.model.TextElement;
import com.abcft.pdfextract.core.model.TextBlock;
import com.google.common.collect.Lists;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.math3.util.FastMath;

import java.awt.geom.Rectangle2D;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class CandidateCell extends RectangularTextGroup<TextChunk> {
    public enum CellStatus {
        /**
         * 正常状态
         * <p>指生成的候选单元格一切正常
         */
        NORMAL,
        /**
         * 不确定状态
         * <p>指生成的候选单元格在结构或内容上有非正常状态，
         * 可能内部有错误
         */
        CONFUSED,
        /**
         * 不正常状态
         * <p>指生成的候选单元格内部有错误，不可用
         */
        ABNORMAL //不正常，
    }

    private List<TextChunk> textChunks = new ArrayList<>();
    private String cachedText;
    private Rectangle textBounds;

    private List<List<TextChunk>> collectedTextChunks = null;
    private float avgTextWidth;
    private float avgTextHeight;

    private boolean isCanMergeUpside;
    private boolean isCanMergeBottom;
    private boolean isCanMergeLeft;
    private boolean isCanMergeRight;
    private int rowIdx;
    private int colIdx;
    private boolean collected;
    private String structType;
    private CellStatus cellStatus = CellStatus.NORMAL;


    public CandidateCell(Rectangle area) {
        super(area.getLeft(), area.getTop(), (float) area.getWidth(), (float) area.getHeight());
    }

    public CandidateCell(float x, float y, float w, float h){
        super(x, y, w, h);
    }

    public CandidateCell(Rectangle2D area) {
        super ((float) area.getX(), (float) area.getY(), (float) area.getWidth(), (float) area.getHeight());
    }

    public static CandidateCell fromTextChunk(TextChunk textChunk) {
        CandidateCell cell = new CandidateCell(textChunk);
        cell.textChunks = Lists.newArrayList(textChunk);
        return cell;
    }

    public CandidateCell(List<TextChunk> textChunks) {
        this.textChunks = textChunks;
        this.setRect(Rectangle.union(textChunks));
    }

    // TODO:暂时不考虑有线表格单元格内分栏的情况
    private List<TextChunk> sortTextChunksOfCell(List<TextChunk> allChunks) {
        if (allChunks.isEmpty()) {
            return allChunks;
        }
        boolean isNormalOrder = true;
        TextChunk chunk0 = allChunks.get(0);
        for (int i = 1; i < allChunks.size(); i++) {
            TextChunk chunk1 = allChunks.get(i);
            if ((chunk0.horizontallyOverlapRatio(chunk1) > 0.5 && chunk0.getMaxY() <= chunk1.getMinY())
                    || (chunk0.verticalOverlapRatio(chunk1) > 0.5 && chunk0.getMaxX() <= chunk1.getMinX())
                    || (!(chunk0.verticalOverlapRatio(chunk1) > 0.5)
                    && !(chunk0.horizontallyOverlapRatio(chunk1) > 0.5)
                    && chunk1.getMinX() < chunk0.getMinX() && chunk1.getMinY() > chunk0.getMaxY())) {
                chunk0 = chunk1;
            } else {
                isNormalOrder = false;
                break;
            }
        }

        if (!isNormalOrder) {
            List<TextChunk> sortChunks = new ArrayList<>(allChunks);
            sortChunks.sort(Comparator.comparing(TextChunk::getTop));
            TextChunk baseChunk = sortChunks.get(0);
            List<TextChunk> groupChunks = new ArrayList<>();
            List<TextChunk> targetChunks = new ArrayList<>();
            groupChunks.add(baseChunk);
            for (int i = 1; i < sortChunks.size(); i++) {
                TextChunk otherChunk = sortChunks.get(i);
                if (baseChunk.verticalOverlapRatio(otherChunk) > 0.8) {
                    groupChunks.add(otherChunk);
                } else {
                    groupChunks.sort(Comparator.comparing(TextChunk::getLeft));
                    targetChunks.addAll(new ArrayList<>(groupChunks));
                    groupChunks.clear();
                    baseChunk = otherChunk;
                    groupChunks.add(baseChunk);
                }
            }
            groupChunks.sort(Comparator.comparing(TextChunk::getLeft));
            targetChunks.addAll(new ArrayList<>(groupChunks));
            return targetChunks;
        }
        return allChunks;
    }

    public void collectText(Page page) {
        textChunks.clear();
        textBounds = null;
        cachedText = null;

        List<TextChunk> chunks = page.getMutableTextChunks(this);
        chunks = sortTextChunksOfCell(chunks);
        List<TextChunk> textChunks = new ArrayList<>();

        for (TextChunk te : chunks) {
            Rectangle2D intersectBounds = this.createIntersection(te.getBounds2D());
            float area1 = (float) (intersectBounds.getWidth() * intersectBounds.getHeight());
            float area2 = te.getArea();
            if (area1 / area2 > 0.5) {
                textChunks.add(te);
            }
        }
        this.textChunks.addAll(TextMerger.mergeCellTextChunks(textChunks));
        collected = true;
    }

    public Cell toCell() {
        Cell cell = new Cell(getLeft(), getTop(), (float) getWidth(), (float) getHeight());
        cell.setTextElements(this.textChunks);
        return cell;
    }

    @Override
    public Rectangle merge(Rectangle2D other) {
        Rectangle rect = super.merge(other);
        cachedText = null;
        textBounds = null;
        return rect;
    }

    public String getOrCollectText(Page page) {
        if (!collected) {
            collectText(page);
        }
        return getText();
    }

    @Override
    public String getText(boolean useLineReturns) {
        if (null == cachedText) {
            // TODO Handle useLineReturns
            if (this.textChunks.size() == 0) {
                cachedText = "";
                return cachedText;
            }

            StringBuilder sb = new StringBuilder();
            for (TextChunk te: this.textChunks) {
                sb.append(te.getText());
            }
            cachedText = Normalizer.normalize(sb.toString(), Normalizer.Form.NFKC).trim();
        }
        return cachedText;
    }

    @Override
    public List<TextChunk> getElements() {
        return textChunks;
    }

    public Rectangle getTextBounds() {
        if (null == textBounds) {
            calculateTextBounds();
        }
        return textBounds;
    }

    private void calculateTextBounds() {
        textBounds = null;
        if (!textChunks.isEmpty()) {
            return;
        }
        for(TextChunk textChunk : textChunks) {
            if (null == textBounds) {
                textBounds = new Rectangle(textChunk);
            } else {
                textBounds.merge(textChunk);
            }
        }
    }

    public Rectangle getValidTextBounds() {
        List<TextElement> teList = this.textChunks.stream().map(tc -> tc.getElements()).flatMap(te -> te.stream())
                .filter(te -> StringUtils.isNotBlank(te.getText())).collect(Collectors.toList());
        if (teList == null || teList.isEmpty()) {
            return null;
        }
        return Rectangle.boundingBoxOf(teList);
    }

    public TextChunk getMergedTextChunk() {
        if (!textChunks.isEmpty()) {
            TextChunk merged = new TextChunk(textChunks.get(0));
            for (int i = 1; i < textChunks.size(); i++) {
                merged.merge(textChunks.get(i), false);
            }
            return merged;
        } else {
            return null;
        }
    }

    // 当前潜在单元格是否正常标识
    public CellStatus getCellStatus() {return this.cellStatus;}

    public void setCellStatus(CellStatus cellStatus) {this.cellStatus = cellStatus;}

    public void setCanMergeUpside(boolean mergeLabel) {this.isCanMergeUpside = mergeLabel;}

    public void setCanMergeBottom(boolean mergeLabel) {this.isCanMergeBottom = mergeLabel;}

    public void setCanMergeLeft(boolean mergeLabel) {this.isCanMergeLeft = mergeLabel;}

    public void setCanMergeRight(boolean mergeLabel) {this.isCanMergeRight = mergeLabel;}

    public boolean getCanMergeUpside() {return this.isCanMergeUpside;}

    public boolean getCanMergeBottom() {return this.isCanMergeBottom;}

    public boolean getCanMergeLeft() {return this.isCanMergeLeft;}

    public boolean getCanMergeRight() {return this.isCanMergeRight;}

    public void setRowIdx(int rowIdx) {this.rowIdx = rowIdx;}

    public void setColIdx(int colIdx) {this.colIdx = colIdx;}

    public int getRowIdx() {return this.rowIdx;}

    public int getColIdx() {return this.colIdx;}

    public String getStructType() {return this.structType;}

    public void setStructType(String type) {this.structType = type;}


    public void setTextAveSize(float aveTextWidth, float aveTextHeight) {
        this.avgTextWidth = aveTextWidth;
        this.avgTextHeight = aveTextHeight;
    }

    public float getAveTextWidth() {
        return this.avgTextWidth;
    }

    public float getAveTextHeight() {
        return this.avgTextHeight;
    }

    public List<List<TextChunk>> getCollectedTextChunk(ContentGroupPage page) {
        if (this.collectedTextChunks == null || this.collectedTextChunks.isEmpty()) {
            return collectTextChunk(page);
        } else {
            return this.collectedTextChunks;
        }
    }

    public List<List<TextChunk>> collectTextChunk(ContentGroupPage page) {
        if (this.collectedTextChunks == null || this.collectedTextChunks.isEmpty()) {
            this.collectedTextChunks = new ArrayList<>();
        } else {
            this.collectedTextChunks.clear();
        }

        //trim string(blank,tab,etc.)
        List<TextElement> cellTextElements = page.getText(this.withCenterExpand(1.0));
        List<TextElement> newCellTextElements = new ArrayList<>();

        if (!cellTextElements.isEmpty()) {
            int startIdx = -1;
            int endIdx = -1;
            for (int i = 0; i < cellTextElements.size(); i++) {
                if (!StringUtils.isBlank(cellTextElements.get(i).getText())) {
                    startIdx = i;
                    break;
                }
            }
            for (int j = cellTextElements.size() - 1; j >= 0; j--) {
                if (!StringUtils.isBlank(cellTextElements.get(j).getText())) {
                    endIdx = j;
                    break;
                }
            }
            if (startIdx != -1 && endIdx != -1) {
                newCellTextElements.addAll(cellTextElements.subList(startIdx,endIdx + 1));
            }
        }

        //calculate text average width and height of a cell
        float aveTextWidth = 0;
        float aveTextHeight = 0;
        if (newCellTextElements.isEmpty()) {
            aveTextWidth = 6.0f;
            aveTextHeight = 8.0f;
        } else {
            for (TextElement te : newCellTextElements) {
                aveTextWidth += te.getTextWidth();
                aveTextHeight += te.getTextHeight();
            }
            aveTextWidth /= newCellTextElements.size();
            aveTextHeight /= newCellTextElements.size();
        }
        this.setTextAveSize(aveTextWidth, aveTextHeight);
        newCellTextElements.sort(Comparator.comparing(TextElement::getGroupIndex));
        List<TextBlock> textBlockTemp = TextMerger.collectByLines(TextMerger.groupByChunks(newCellTextElements));

        //collect to row-col style
        if (textBlockTemp.size() == 0) {
            return new ArrayList<>();
        }

        for (TextBlock tb: textBlockTemp) {
            this.collectedTextChunks.add(tb.getElements());
        }

        return this.collectedTextChunks;
    }

    public CandidateCell cellMerge(CandidateCell cell) {
        float mergeLeft = FastMath.min(this.getLeft(), cell.getLeft());
        float mergeRight = FastMath.max(this.getRight(), cell.getRight());
        float mergeTop = FastMath.min(this.getTop(), cell.getTop());
        float mergeBotoom = FastMath.max(this.getBottom(), cell.getBottom());
        List<List<TextChunk>> mergeTextChunk = new ArrayList<>(this.collectedTextChunks);
        mergeTextChunk.addAll(cell.collectedTextChunks);

        CandidateCell mergedCell = new CandidateCell(mergeLeft, mergeTop, mergeRight - mergeLeft, mergeBotoom - mergeTop);
        mergedCell.collectedTextChunks = mergeTextChunk;
        mergedCell.avgTextWidth = (this.avgTextWidth + cell.avgTextWidth)/2;
        mergedCell.avgTextHeight = (this.avgTextHeight + cell.avgTextHeight)/2;
        if (this.getStructType() != null && cell.getStructType() != null &&
                this.getStructType().equals(cell.getStructType())) {
            mergedCell.setStructType(cell.getStructType());
        }
        return mergedCell;
    }

}
