package com.abcft.pdfextract.core.chart.model;

/**
 * Created by dhu on 2017/5/10.
 */
public class RotatedTextInfo extends PathInfo {
    public String text;
    public double angle;
    public RotatedTextInfo() {
        super(PathType.ROTATED_TEXT);
    }
}
