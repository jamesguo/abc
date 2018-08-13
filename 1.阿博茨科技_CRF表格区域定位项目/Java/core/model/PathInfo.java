package com.abcft.pdfextract.core.model;

import java.awt.geom.AffineTransform;
import java.awt.geom.PathIterator;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;

/**
 * Information about a (sub) path.
 */
public class PathInfo {

    private int numLineTos;
    private int numCurveTos;
    private int numMoves;
    private boolean isLinear = true;
    private Point2D startPos;
    private Point2D endPos;
    private boolean closed;
    private boolean lineToStart;
    private Rectangle2D bounds;
    private int previousAction = -1;

    PathInfo() {
    }

    public Rectangle2D getBounds2D() {
        return bounds;
    }

    public int getCurveTosCount() {
        return numCurveTos;
    }

    public int getMoveCount() {
        return numMoves;
    }

    public int getLineCount() {
        return numLineTos;
    }

    public int getTotalActionCount() {
        return numMoves + numCurveTos + numLineTos;
    }

    public boolean isEmpty() {
        return (numCurveTos + numLineTos) <= 0;
    }

    public boolean isLineToStart() {
        return lineToStart;
    }

    public boolean isLinear() {
        return isLinear;
    }

    private void adjustBounds(float x, float y) {
        if (null == bounds) {
            bounds = new Rectangle2D.Float(x, y, 0f, 0f);
        } else {
            double x1 = bounds.getMinX(), y1 = bounds.getMinY(),
                    x2 = bounds.getMaxX(), y2 = bounds.getMaxY();
            if (x < x1) x1 = x;
            if (y < y1) y1 = y;
            if (x > x2) x2 = x;
            if (y > y2) y2 = y;
            bounds.setFrameFromDiagonal(x1, y1, x2, y2);
        }
    }

    void recordMoveTo(float x, float y) {
        ++numMoves;
        startPos = new Point2D.Float(x, y);
        endPos = null;
        adjustBounds(x, y);
        previousAction = PathIterator.SEG_MOVETO;
    }

    void recordLineTo(float x, float y) {
        ++numLineTos;
        endPos = new Point2D.Float(x, y);
        adjustBounds(x, y);
        previousAction = PathIterator.SEG_LINETO;
    }

    void recordCurveTo(float x1, float y1,
                       float x2, float y2, float x3, float y3) {
        ++numCurveTos;
        adjustBounds(x1, y1);
        adjustBounds(x2, y2);
        adjustBounds(x3, y3);
        endPos = new Point2D.Float(x3, y3);
        previousAction = PathIterator.SEG_CUBICTO;
        isLinear = false;
    }

    void recordRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
        recordMoveTo((float)p0.getX(), (float)p0.getY());
        recordLineTo((float)p1.getX(), (float)p1.getY());
        recordLineTo((float)p2.getX(), (float)p2.getY());
        recordLineTo((float)p3.getX(), (float)p3.getY());
        recordClose();
    }

    void recordClose() {
        closed = true;
        if (endPos != null) {
            lineToStart = true;
        }
        startPos = endPos = null;
        previousAction = PathIterator.SEG_CLOSE;
    }

    public boolean isClosed() {
        return closed;
    }

    public int getPreviousAction() {
        return previousAction;
    }

    void transform(AffineTransform transform) {
        if (bounds != null) {
            bounds = transform.createTransformedShape(bounds).getBounds2D();
        }
    }

    @Override
    public String toString() {
        return "PathInfo{" +
                "bounds=" + bounds +
                ", numMoves=" + numMoves +
                ", numLineTos=" + numLineTos +
                ", numCurveTos=" + numCurveTos +
                ", isLinear=" + isLinear +
                ", closed=" + closed +
                '}';
    }
}
