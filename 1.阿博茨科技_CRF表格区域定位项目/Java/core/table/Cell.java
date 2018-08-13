package com.abcft.pdfextract.core.table;

import com.abcft.pdfextract.core.model.Rectangle;
import com.abcft.pdfextract.core.model.RectangularTextGroup;
import com.abcft.pdfextract.core.model.TextChunk;
import com.google.common.collect.Lists;

import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;

@SuppressWarnings("serial")
public class Cell extends RectangularTextGroup<TextChunk> {

    /**
     * 表明此单元格的已与左侧单元格合并。
     */
    public static final char LEFT_MERGED = '←';
    /**
     * 表明此单元格的已与上方单元格合并。
     */
    public static final char UP_MERGED = '↑';
    /**
     * 表明此单元格的已与左上方单元格合并。
     */
    public static final char UP_LEFT_MERGED = '↖';

    /**
     * 表明此单元格的已与左侧单元格合并。
     */
    public static final String LEFT_MERGED_TEXT = String.valueOf(LEFT_MERGED);
    /**
     * 表明此单元格的已与上方单元格合并。
     */
    public static final String UP_MERGED_TEXT = String.valueOf(UP_MERGED);
    /**
     * 表明此单元格的已与左上方单元格合并。
     */
    public static final String UP_LEFT_MERGED_TEXT = String.valueOf(UP_LEFT_MERGED);

    public static final double CELL_BOUNDS_EPSILON = 1.0f;

    public enum CrossPageRowType {
        // 跨页表格前一张表的最后一行。
        LastRow,
        // 跨页表格后一张表的第一行。
        FirstRow
    }


    private boolean spanning;
    private boolean placeholder;
    private List<TextChunk> textElements;
    private int rowSpan = 1;
    private int colSpan = 1;
    private int row;
    private int col;
    private boolean dirty;
    private CrossPageRowType crossPageRow;
    private boolean crossPageCell = false;

    public static Cell fromTextChunk(TextChunk chunk) {
        Cell cell = new Cell(chunk.getLeft(), chunk.getTop(), (float) chunk.getWidth(), (float) chunk.getHeight());
        cell.setTextElements(Lists.newArrayList(chunk));
        return cell;
    }

    public Cell() {
        super(-1, -1, 0,0);
        this.setTextElements(new ArrayList<>());
    }

    Cell(Cell other) {
        super(other.getLeft(), other.getTop(), (float) other.getWidth(), (float)other.getHeight());
        this.row = other.row;
        this.col = other.col;
        this.rowSpan = other.rowSpan;
        this.colSpan = other.colSpan;
        this.spanning = other.spanning;
        this.placeholder = other.placeholder;
        this.crossPageRow = other.crossPageRow;
        this.crossPageCell = other.crossPageCell;
        this.dirty = other.dirty;
        this.textElements = new ArrayList<>(other.textElements);
    }

    public Cell(float left, float top, float width, float height) {
        super(left, top, width, height);
        this.setPlaceholder(false);
        this.setSpanning(false);
        this.setTextElements(new ArrayList<>());
    }

    public Cell(Point2D topLeft, Point2D bottomRight) {
        super((float) topLeft.getX(), (float) topLeft.getY(), (float) (bottomRight.getX() - topLeft.getX()), (float) (bottomRight.getY() - topLeft.getY()));
        this.setPlaceholder(false);
        this.setSpanning(false);
        this.setTextElements(new ArrayList<>());
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        Class<?> clazz = getClass();
        sb.append(String.format("%s[%d, %d]", clazz.getSimpleName(), row, col));
        sb.append(" = ");
        String text =  getText();
        text = (text == null) ? "<null>" : "\"" + text + "\"";
        sb.append(text);
        if (rowSpan != 1 || colSpan != 1) {
            sb.append(", ");
            sb.append(rowSpan);
            sb.append("x");
            sb.append(colSpan);
        }
        return sb.toString();
    }

    public void assignPosition(int row, int col) {
        this.row = row;
        this.col = col;
        this.dirty = true;
    }

    public int getRowSpan() {
        return rowSpan;
    }

    public void setRowSpan(int rowSpan) {
        this.rowSpan = rowSpan;
        this.dirty = true;
    }

    public int getColSpan() {
        return colSpan;
    }

    public void setColSpan(int colSpan) {
        this.colSpan = colSpan;
        this.dirty = true;
    }

    public int getRow() {
        return row;
    }

    public int getCol() {
        return col;
    }

    public void setRow(int r) {
        this.row = r;
        this.dirty = true;
    }

    public void setCol(int c) {
        this.col = c;
    }

    public void setCrossPageRow(CrossPageRowType type) {
        this.crossPageRow = type;
    }

    public CrossPageRowType getCrossPageRow() {
        return this.crossPageRow;
    }

    public void setCrossPageCell() {
        this.crossPageCell = true;
    }

    public boolean isCrossPageCell() {
        return this.crossPageCell;
    }

    void increaseColSpan() {
        ++colSpan;
        this.dirty = true;
    }

    void increaseRowSpan() {
        ++rowSpan;
        this.dirty = true;
    }

    public boolean isDirty() {
        return dirty;
    }

    void setClean() {
        this.dirty = false;
    }

    public boolean isPivot(int row, int col) {
        return this.row == row && this.col == col;
    }

    public boolean isUpMerged(int row, int col) {
        return this.row < row && row < this.row + rowSpan
                && this.col == col;
    }

    public boolean isLeftMerged(int row, int col) {
        return this.row == row
                && this.col < col && col < this.col + colSpan;
    }

    public boolean isUpLeftMerged(int row, int col) {
        return this.row < row && row < this.row + rowSpan
                && this.col < col && col < this.col + colSpan;
    }

    public boolean containsPosition(int row, int col) {
        return this.row <= row && row < this.row + rowSpan
            && this.col <= col && col < this.col + colSpan;
    }

    @Override
    public String getText(boolean useLineReturns) {
        if (this.textElements.size() == 0) {
            return "";
        }
        // TODO 换行空格的处理？后续需要修改为 TextBlock
        StringBuilder sb = new StringBuilder();
        // 排序等操作已经在外面执行完毕，这里不再需要排序计算
        // NumberUtil.sort(this.textElements);
        for (TextChunk tc : this.textElements) {
            sb.append(tc.getText());
        }
        return sb.toString().trim();
    }

    /**
     * 获取该单元格在给定行/列时的适当文本。
     *
     * <p>对于合并单元格，将会输出 {@value #LEFT_MERGED}、{@value #UP_MERGED}、{@value #UP_LEFT_MERGED}等符号。</p>
     * <p>对于单元格外的内热，将会输出 {@code null}。</p>
     *
     * 对于下面这样的表格：
     * <p>
     * <table style="border-collapse: collapse;">
     *     <tr>
     *         <td colspan="3" style="border: solid 1px;"><center>A</center></td>
     *         <td style="border: solid 1px;">B</td>
     *     </tr>
     *     <tr>
     *         <td rowspan="3" style="border: solid 1px;">C</td>
     *         <td style="border: solid 1px;">D</td>
     *         <td style="border: solid 1px;">E</td>
     *         <td style="border: solid 1px;">F</td>
     *     </tr>
     *     <tr>
     *         <td rowspan="2" colspan="2" style="border: solid 1px;"><center>G</center</td>
     *         <td style="border: solid 1px;">H</td>
     *     </tr>
     *     <tr>
     *         <td style="border: solid 1px;">I</td>
     *     </tr>
     * </table>
     * </p>
     * 每个单元格的输出如下：
     * <p>
     * <table style="border-collapse: collapse;">
     *     <tr>
     *         <td>A</td>
     *         <td>{@value #LEFT_MERGED}</td>
     *         <td>{@value #LEFT_MERGED}</td>
     *         <td>B</td>
     *     </tr>
     *     <tr>
     *         <td>C</td>
     *         <td>D</td>
     *         <td>E</td>
     *         <td>F</td>
     *     </tr>
     *     <tr>
     *         <td>{@value #UP_MERGED}</td>
     *         <td>G</td>
     *         <td>{@value #LEFT_MERGED}</td>
     *         <td>H</td>
     *     </tr>
     *     <tr>
     *         <td>{@value #UP_MERGED}</td>
     *         <td>{@value #UP_MERGED}</td>
     *         <td>{@value #UP_LEFT_MERGED}</td>
     *         <td>I</td>
     *     </tr>
     * </table>
     * </p>
     *
     *
     * @param viewRow 表格中实际单元格的行索引。
     * @param viewCol 表格中实际单元格的列索引。
     * @return 该单元格的文本或者合适文本。
     */
    public String getTextAt(int viewRow, int viewCol) {
        String text;
        if (isPivot(viewRow, viewCol)) {
            text = getText();
        } else if (isLeftMerged(viewRow, viewCol)) {
            text = LEFT_MERGED_TEXT;
        } else if (isUpMerged(viewRow, viewCol)) {
            text = UP_MERGED_TEXT;
        } else if (isUpLeftMerged(viewRow, viewCol)) {
            text = UP_LEFT_MERGED_TEXT;
        } else {
            text = null;
        }
        return text;
    }

    @Override
    public boolean nearlyContains(Rectangle other) {
        return super.nearlyContains(other, CELL_BOUNDS_EPSILON);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Cell)) {
            return false;
        }
        Cell other = (Cell)o;
        if (isEmpty() || other.isEmpty()) {
            return getCol() == other.getCol() && getRow() == other.getRow();
        }
        return this.getLeft() == other.getLeft()
                && this.getRight() == other.getRight()
                && this.getTop() == other.getTop()
                && this.getBottom() == other.getBottom();
    }

    @Override
    public int hashCode() {
        int result = super.hashCode();
        result = 31 * result + (spanning ? 1 : 0);
        result = 31 * result + (placeholder ? 1 : 0);
        result = 31 * result + (textElements != null ? textElements.hashCode() : 0);
        return result;
    }


    public boolean isSpanning() {
        return spanning;
    }

    public void setSpanning(boolean spanning) {
        this.spanning = spanning;
    }

    public boolean isPlaceholder() {
        return placeholder;
    }

    public void setPlaceholder(boolean placeholder) {
        this.placeholder = placeholder;
    }


    public List<TextChunk> getElements() {
        return textElements;
    }

    public void setTextElements(List<TextChunk> textElements) {
        this.textElements = textElements;
    }

    public void setText(String text) {
        List<TextChunk> textChunks = new ArrayList<>(1);
        textChunks.add(new TextChunk(text));
        setTextElements(textChunks);
    }

}
