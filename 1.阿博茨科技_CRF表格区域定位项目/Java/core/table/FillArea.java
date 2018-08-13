package com.abcft.pdfextract.core.table;

import org.apache.commons.math3.util.FastMath;

import java.awt.*;
import java.awt.geom.Rectangle2D;

public class FillArea extends Rectangle2D.Float{

    private Color color;

    public FillArea() {
    }

    public FillArea(Rectangle2D rect, Color color) {
        super((float)rect.getX(), (float)rect.getY(), (float)rect.getWidth(), (float)rect.getHeight());
        this.color = color;
    }

    public void setColor(Color color) {
        this.color = color;
    }

    public Color getColor() {
        return this.color;
    }

    public boolean nearlyEqualColor(FillArea fillArea, float thresh) {
        return nearlyEqualColor(this.getColor(), fillArea.getColor(), thresh);
    }

    public static boolean nearlyEqualColor(Color one, Color other, float thresh) {
        if (FastMath.abs(one.getRed() - other.getRed()) <= thresh
                && FastMath.abs(one.getGreen() - other.getGreen()) <= thresh
                && FastMath.abs(one.getBlue() - other.getBlue()) <= thresh) {
            return true;
        }
        return false;
    }

}
