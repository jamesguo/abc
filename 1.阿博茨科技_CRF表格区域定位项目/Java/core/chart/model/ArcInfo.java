package com.abcft.pdfextract.core.chart.model;

public class ArcInfo extends PathInfo {
    public double x;        // 扇形圆心 X 坐标
    public double y;        // 扇形圆心 Y 坐标
    public double r;        // 扇形半径
    public double angle;    // 扇形角度
    public ArcInfo() {
        super(PathType.ARC);
    }
}
