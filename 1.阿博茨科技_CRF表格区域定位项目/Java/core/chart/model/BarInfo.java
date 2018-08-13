package com.abcft.pdfextract.core.chart.model;

import java.awt.geom.Rectangle2D;

public class BarInfo extends PathInfo {
    public boolean isVertical;
    public Rectangle2D bar;
    public BarInfo(boolean isVertical) {
        super(isVertical ? PathType.BAR : PathType.COLUMNAR);
        this.isVertical = isVertical;
    }
}
