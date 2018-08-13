package com.abcft.pdfextract.core.table;

import com.abcft.pdfextract.util.FloatUtils;

import java.awt.geom.Point2D;
import java.util.Comparator;

/**
 * Comparator for {@link Point2D}.
 *
 * Created by chzhong on 17-2-8.
 */
public abstract class PointComparator implements Comparator<Point2D> {

    public static final PointComparator X_FIRST = new PointComparator() {
        @Override
        protected int pointCompare(float arg0X, float arg0Y, float arg1X, float arg1Y) {
            return TableUtils.xFirstCompare(arg0X, arg0Y, arg1X, arg1Y);
        }
    };

    public static final PointComparator Y_FIRST = new PointComparator() {
        @Override
        protected int pointCompare(float arg0X, float arg0Y, float arg1X, float arg1Y) {
            return TableUtils.yFirstCompare(arg0X, arg0Y, arg1X, arg1Y);
        }
    };

    protected abstract int pointCompare(float arg0X, float arg0Y, float arg1X, float arg1Y);

    @Override
    public int compare(Point2D arg0, Point2D arg1) {
        float arg0X = FloatUtils.round((float)arg0.getX(), 2);
        float arg0Y = FloatUtils.round((float)arg0.getY(), 2);
        float arg1X = FloatUtils.round((float)arg1.getX(), 2);
        float arg1Y = FloatUtils.round((float)arg1.getY(), 2);

        return pointCompare(arg0X, arg0Y, arg1X, arg1Y);
    }
}
