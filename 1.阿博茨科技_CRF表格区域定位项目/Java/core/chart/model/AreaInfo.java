package com.abcft.pdfextract.core.chart.model;

import java.awt.geom.Point2D;
import java.util.List;

public class AreaInfo extends PathInfo {
    public List<Point2D> points;
    public AreaInfo() {
        super(PathType.AREA);
    }
}
