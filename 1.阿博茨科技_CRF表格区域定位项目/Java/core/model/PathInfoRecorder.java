package com.abcft.pdfextract.core.model;

import java.awt.geom.AffineTransform;
import java.awt.geom.GeneralPath;
import java.awt.geom.PathIterator;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;

public class PathInfoRecorder {

    public static List<PathInfo> replay(GeneralPath path, AffineTransform at) {
        PathInfoRecorder recorder = new PathInfoRecorder();
        PathIterator iter = path.getPathIterator(at);
        float[] coords = new float[6];
        int currentSegment;
        while (!iter.isDone()) {
            currentSegment = iter.currentSegment(coords);
            switch (currentSegment) {
                case PathIterator.SEG_MOVETO:
                    recorder.moveTo(coords[0], coords[1]);
                    break;
                case PathIterator.SEG_LINETO:
                    recorder.lineTo(coords[0], coords[1]);
                    break;
                case PathIterator.SEG_CUBICTO:
                    recorder.curveTo(coords[0], coords[1],
                            coords[2], coords[3],
                            coords[4], coords[5]);
                    break;
                case PathIterator.SEG_CLOSE:
                    recorder.closePath();
                    break;
            }
            iter.next();
        }
        return recorder.endPath(null);
    }

    private List<PathInfo> subPaths = new ArrayList<>();
    private List<PathInfo> cached;
    private PathInfo currentSubPath = new PathInfo();
    private AffineTransform transform;

    public void setTransform(AffineTransform transform) {
        this.transform = transform;
    }

    public void moveTo(float x, float y) {
        if (!currentSubPath.isClosed() && currentSubPath.getPreviousAction() != PathIterator.SEG_MOVETO) {
            // 如果上一次操作没有关闭画笔，则强制关闭
            closePath();
        }
        if (transform != null) {
            Point2D.Float pt = new Point2D.Float(x, y);
            transform.transform(pt, pt);
            x = pt.x;
            y = pt.y;
        }
        currentSubPath.recordMoveTo(x, y);
    }

    public void lineTo(float x, float y) {
        if (transform != null) {
            Point2D.Float pt = new Point2D.Float(x, y);
            transform.transform(pt, pt);
            x = pt.x;
            y = pt.y;
        }
        currentSubPath.recordLineTo(x, y);
    }

    public void curveTo(float x1, float y1,
                        float x2, float y2,
                        float x3, float y3) {
        if (transform != null) {
            float[] coords = new float[] { x1, y1, x2, y2, x3, y3 };
            transform.transform(coords, 0, coords, 0, 3);
        }
        currentSubPath.recordCurveTo(x1, y1, x2, y2, x3, y3);
    }


    public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
        if (transform != null) {
            transform.transform(p0, p0);
            transform.transform(p1, p1);
            transform.transform(p2, p2);
            transform.transform(p3, p3);
        }

        currentSubPath.recordRectangle(p0, p1, p2, p3);
        closePath();
    }

    public List<PathInfo> fillPath(AffineTransform transform) {
        return endPath(transform);
    }

    public List<PathInfo> strokePath(AffineTransform transform) {
        return endPath(transform);
    }

    public List<PathInfo> fillFirst(AffineTransform transform) {
        cached = endPath(transform);
        return cached;
    }

    @SuppressWarnings("unused")
    public List<PathInfo> thenStroke(AffineTransform transform) {
        List<PathInfo> subPaths = cached;
        cached = null;
        return subPaths;
    }

    public void closePath() {
        PathInfo subPathInfo = currentSubPath;
        subPathInfo.recordClose();
        if (!subPathInfo.isEmpty()) {
            subPaths.add(subPathInfo);
        }
        currentSubPath = new PathInfo();
    }

    public List<PathInfo> endPath(AffineTransform transform) {
        List<PathInfo> subPaths = this.subPaths;
        closePath();
        if (transform != null) {
            for (PathInfo pathInfo : subPaths) {
                pathInfo.transform(transform);
            }
        }
        this.subPaths = new ArrayList<>();
        return subPaths;
    }

}
