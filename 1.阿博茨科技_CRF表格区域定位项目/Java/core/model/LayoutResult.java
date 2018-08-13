package com.abcft.pdfextract.core.model;
import java.util.List;

public class LayoutResult {

    private List<Rectangle> textLayoutAreas;   // 提供给正文的版面区域,中间结果
    private List<Rectangle> tableLayoutAreas;  // 提供给table的版面区域，最终结果

    public LayoutResult(List<Rectangle> tableLayoutAreas, List<Rectangle> textLayoutAreas) {
        this.textLayoutAreas = textLayoutAreas;
        this.tableLayoutAreas = tableLayoutAreas;
    }

    public LayoutResult() {

    }

    public List<Rectangle> getTextLayoutAreas() {
        return this.textLayoutAreas;
    }

    public List<Rectangle> getTableLayoutAreas() {
        return this.tableLayoutAreas;
    }

    public void setTableLayoutAreas(List<Rectangle> tableLayoutAreas) {
        this.tableLayoutAreas = tableLayoutAreas;
    }

    public void setTextLayoutAreas(List<Rectangle> textLayoutAreas) {
        this.textLayoutAreas = textLayoutAreas;
    }
}
