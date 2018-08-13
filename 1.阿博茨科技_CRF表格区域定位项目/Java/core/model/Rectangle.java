package com.abcft.pdfextract.core.model;

import com.abcft.pdfextract.core.table.ContentGroupPage;
import com.abcft.pdfextract.util.FloatUtils;

import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.List;
import org.apache.commons.math3.util.FastMath;

@SuppressWarnings("serial")
public class Rectangle extends Rectangle2D.Float {

    public static final float VERTICAL_COMPARISON_THRESHOLD = 0.4f;

    private boolean deleted;

    private int hencSeq;

    private int vencSeq;


    /**
     * @param rectangles
     * @return minimum bounding box that contains all the rectangles
     */
    public static Rectangle boundingBoxOf(List<? extends Rectangle2D> rectangles) {
        float minx = java.lang.Float.MAX_VALUE;
        float miny = java.lang.Float.MAX_VALUE;
        float maxx = -java.lang.Float.MAX_VALUE;
        float maxy = -java.lang.Float.MAX_VALUE;

        for (Rectangle2D r : rectangles) {
            if (r.isEmpty()) {
                continue;
            }
            minx = (float) Math.min(r.getMinX(), minx);
            miny = (float) Math.min(r.getMinY(), miny);
            maxx = (float) Math.max(r.getMaxX(), maxx);
            maxy = (float) Math.max(r.getMaxY(), maxy);
        }
        return new Rectangle(minx, miny, maxx - minx, maxy - miny);
    }

    /**
     * @param textChunks
     * @return minimum bounding box that contains all the visibleBBox
     */
    public static Rectangle boundingVisibleBoxOf(List<TextChunk> textChunks) {
        float minx = java.lang.Float.MAX_VALUE;
        float miny = java.lang.Float.MAX_VALUE;
        float maxx = -java.lang.Float.MAX_VALUE;
        float maxy = -java.lang.Float.MAX_VALUE;

        for (TextChunk r : textChunks) {
            Rectangle2D visibleBBox = r.getVisibleBBox();
            if (visibleBBox.isEmpty()) {
                continue;
            }
            minx = (float) Math.min(visibleBBox.getMinX(), minx);
            miny = (float) Math.min(visibleBBox.getMinY(), miny);
            maxx = (float) Math.max(visibleBBox.getMaxX(), maxx);
            maxy = (float) Math.max(visibleBBox.getMaxY(), maxy);
        }
        return new Rectangle(minx, miny, maxx - minx, maxy - miny);
    }

    public static Rectangle textBlockBoundingVisibleBoxOf(List<TextBlock> textBlocks) {
        float minx = java.lang.Float.MAX_VALUE;
        float miny = java.lang.Float.MAX_VALUE;
        float maxx = -java.lang.Float.MAX_VALUE;
        float maxy = -java.lang.Float.MAX_VALUE;

        for (TextBlock r : textBlocks) {
            Rectangle2D visibleBBox = r.getVisibleBBox();
            if (visibleBBox.isEmpty()) {
                continue;
            }
            minx = (float) Math.min(visibleBBox.getMinX(), minx);
            miny = (float) Math.min(visibleBBox.getMinY(), miny);
            maxx = (float) Math.max(visibleBBox.getMaxX(), maxx);
            maxy = (float) Math.max(visibleBBox.getMaxY(), maxy);
        }
        return new Rectangle(minx, miny, maxx - minx, maxy - miny);
    }

    public static Rectangle fromLTRB(float left, float top, float right, float bottom) {
        return new Rectangle(left, top, right - left, bottom - top);
    }

    public Rectangle() {
    }

    public Rectangle(Rectangle2D.Float rect) {
        super(rect.x, rect.y, rect.width, rect.height);
    }

    public Rectangle(Rectangle2D rect) {
        super((float)rect.getX(), (float)rect.getY(), (float)rect.getWidth(), (float)rect.getHeight());
    }

    public Rectangle(java.awt.Rectangle rect) {
        super(rect.x, rect.y, rect.width, rect.height);
    }

    public Rectangle(double left, double top, double width, double height) {
        super((float) left, (float) top, (float) width, (float) height);
    }

    public boolean isDeleted() {
        return deleted;
    }

    public void markDeleted() {
        this.deleted = true;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        Class<?> clazz = getClass();
/*      String s = super.toString();
        s = s.replace(clazz.getName(), clazz.getSimpleName());
        sb.append(s.substring(0, s.length() - 1));
        sb.append(String.format(",r=%f,b=%f]\n", this.getRight(), this.getBottom()));*/
        sb.append(String.format("%s[l=%.2f,r=%.2f,t=%.2f,b=%.2f; w=%.2f,h=%.2f]", clazz.getSimpleName(),
                this.getLeft(), this.getRight(), this.getTop(), this.getBottom(),this.getWidth(),this.getHeight()));
        return sb.toString();
    }

    public boolean nearlyEquals(Rectangle other) {
        return nearlyEquals(other, VERTICAL_COMPARISON_THRESHOLD);
    }

    public boolean nearlyEquals(Rectangle other, double epsilon) {
        return nearlyEquals(this, other, epsilon);
    }

    /**
     * 一个区域的左右边界近似包含另一个区域的边界
     */
    public boolean nearlyHorizontalContains(Rectangle other, double epsilon) {
        return this.getMinX() - other.getMinX() < epsilon && this.getMaxX() - other.getMaxX() > -epsilon;
    }

    public boolean nearlyContains(Rectangle other) {
        return nearlyContains(other, VERTICAL_COMPARISON_THRESHOLD);
    }

    public boolean nearlyContains(Rectangle other, double epsilon) {
        return nearlyContains(this, other, epsilon);
    }

    public static boolean nearlyContains(Rectangle2D one, double x, double y, double epsilon) {
        double x0 = one.getX();
        double y0 = one.getY();
        return (FloatUtils.fgte(x, x0, epsilon) &&
                FloatUtils.fgte(y, y0, epsilon) &&
                FloatUtils.flte(x, x0 + one.getWidth(), epsilon) &&
                FloatUtils.flte(y, y0 + one.getHeight(), epsilon));
    }

    public static boolean nearlyContains(Rectangle2D one, Rectangle2D other, double epsilon) {
        double x = other.getX();
        double y = other.getY();
        double w = other.getWidth();
        double h = other.getHeight();
        if (one.isEmpty() || w <= 0 || h <= 0) {
            return false;
        }
        double x0 = one.getX();
        double y0 = one.getY();
        return FloatUtils.fgte(x, x0, epsilon) &&
                FloatUtils.fgte(y, y0, epsilon) &&
                FloatUtils.flte((x + w) , x0 + one.getWidth(), epsilon) &&
                FloatUtils.flte((y + h) , y0 + one.getHeight(), epsilon);
    }

    public static boolean nearlyEquals(Rectangle2D one, Rectangle2D other, double epsilon) {
        double x = other.getX();
        double y = other.getY();
        double w = other.getWidth();
        double h = other.getHeight();
        double x0 = one.getX();
        double y0 = one.getY();
        return FloatUtils.feq(x, x0, epsilon) &&
                FloatUtils.feq(y, y0, epsilon) &&
                FloatUtils.feq((x + w) , x0 + one.getWidth(), epsilon) &&
                FloatUtils.feq((y + h) , y0 + one.getHeight(), epsilon);
    }

    public boolean nearlyAlignAndIntersects(Rectangle other, float e1, float e2) {
        // e1 Allowed variance in aligning
        // e2 Allowed variance in intersections
        if (FloatUtils.feq(getTop(), other.getTop(), e1)
                && FloatUtils.feq(getBottom(), other.getBottom(), e1)) {
            return  getLeft() <= other.getLeft() - e2 && other.getLeft() <= getRight() - e2
                    || other.getLeft() <= getLeft() - e2 && getLeft() <= other.getRight() - e2;
        } else if (FloatUtils.feq(getLeft(), other.getLeft(), e1)
                && FloatUtils.feq(getRight(), other.getRight(), e1)) {
            return (getTop() <= other.getTop() - e2 && other.getTop() <= getBottom() - e2
                    || other.getTop() <= getTop() - e2 && getTop() <= other.getBottom() - e2);
        } else {
            return false;
        }
    }

    // I'm bad at Java and need this for fancy sorting in technology.tabula.TextChunk.
    public int isLtrDominant() {
        return 0;
    }

    public float getArea() {
        return this.width * this.height;
    }

    public float verticalDistance(Rectangle other) {
        return Math.max(this.getTop(), other.getTop()) - Math.min(this.getBottom(), other.getBottom());
    }

    public float horizontalDistance(Rectangle other) {
        return Math.max(this.getLeft(), other.getLeft()) - Math.min(this.getRight(), other.getRight());
    }

    public float verticalOverlap(Rectangle other) {
        return Math.max(0, Math.min(this.getBottom(), other.getBottom()) - Math.max(this.getTop(), other.getTop()));
    }

    public static float verticalOverlap(Rectangle2D one, Rectangle2D other) {
        return (float) Math.max(0, Math.min(one.getMaxY(), other.getMaxY()) - Math.max(one.getMinY(), other.getMinY()));
    }

    public boolean isVerticallyOverlap(Rectangle other) {
        return verticalOverlap(other) > 0;
    }

    public float horizontalOverlap(Rectangle other) {
        return Math.max(0, Math.min(this.getRight(), other.getRight()) - Math.max(this.getLeft(), other.getLeft()));
    }

    public static float horizontalOverlap(Rectangle2D one, Rectangle2D other) {
        return (float) Math.max(0, Math.min(one.getMaxX(), other.getMaxX()) - Math.max(one.getMinX(), other.getMinX()));
    }

    public boolean isHorizontallyOverlap(Rectangle other) {
        return horizontalOverlap(other) > 0;
    }

    public float horizontallyOverlapRatio(Rectangle other) {
        return horizontallyOverlapRatio(other, false);
    }

    public float horizontallyOverlapRatio(Rectangle other, boolean isStrict) {
        float intersectionWidth = horizontalOverlap(other);
        float delta = isStrict ? (Math.max(this.getRight(), other.getRight()) - Math.min(this.getLeft(), other.getLeft()))
                : (Math.min((float) this.getWidth(), (float) other.getWidth()));
        return intersectionWidth / delta;
    }

    public float verticalOverlapRatio(Rectangle other) {
        return verticalOverlapRatio(other, false);
    }

    public float verticalOverlapRatio(Rectangle other, boolean isStrict) {
        float intersectionHeight = verticalOverlap(other);
        float delta = isStrict ? (Math.max(this.getBottom(), other.getBottom()) - Math.min(this.getTop(), other.getTop()))
                : (Math.min(this.getBottom() - this.getTop(), other.getBottom() - other.getTop()));
        return intersectionHeight / delta;
    }

    public float overlapRatio(Rectangle other) {
        return overlapRatio(other, true);
    }

    public float overlapRatio(Rectangle other, boolean isStrict) {
        double intersectionWidth = Math.max(0, Math.min(this.getRight(), other.getRight()) - Math.max(this.getLeft(), other.getLeft()));
        double intersectionHeight = Math.max(0, Math.min(this.getBottom(), other.getBottom()) - Math.max(this.getTop(), other.getTop()));
        if (intersectionWidth < 0 || intersectionHeight < 0) {
            return 0;
        }
        double intersectionArea = Math.max(0, intersectionWidth * intersectionHeight);
        double unionArea = isStrict ? this.getArea() + other.getArea() - intersectionArea : FastMath.min(this.getArea(), other.getArea());

        return (float) (intersectionArea / unionArea);
    }

    public float selfOverlapRatio(Rectangle other) {
        return selfOverlapRatio(other, true);
    }

    public float selfOverlapRatio(Rectangle other, boolean isStrict) {
        double intersectionWidth = Math.max(0, Math.min(this.getRight(), other.getRight()) - Math.max(this.getLeft(), other.getLeft()));
        double intersectionHeight = Math.max(0, Math.min(this.getBottom(), other.getBottom()) - Math.max(this.getTop(), other.getTop()));
        if (intersectionWidth < 0 || intersectionHeight < 0) {
            return 0;
        }
        double intersectionArea = Math.max(0, intersectionWidth * intersectionHeight);

        return (float) (intersectionArea / this.getArea());
    }

    public float selfOverlapRatio(Rectangle2D other) {
        return selfOverlapRatio(new Rectangle(other), true);
    }

    public Rectangle merge(Rectangle2D other) {
        this.setRect(this.createUnion(other));
        return this;
    }

    public float getTop() {
        return (float) this.getMinY();
    }

    public void setTop(float top) {
        float deltaHeight = top - this.y;
        this.setRect(this.x, top, this.width, this.height - deltaHeight);
    }

    public float getRight() {
        return (float) this.getMaxX();
    }

    public void setRight(float right) {
        this.setRect(this.x, this.y, right - this.x, this.height);
    }

    public float getLeft() {
        return (float) this.getMinX();
    }

    public void setLeft(float left) {
        float deltaWidth = left - this.x;
        this.setRect(left, this.y, this.width - deltaWidth, this.height);
    }

    public float getBottom() {
        return (float) this.getMaxY();
    }

    public void setBottom(float bottom) {
        this.setRect(this.x, this.y, this.width, bottom - this.y);
    }

    public Point2D[] getPoints() {
        return new Point2D[]{
                new Point2D.Float(this.getLeft(), this.getTop()),
                new Point2D.Float(this.getRight(), this.getTop()),
                new Point2D.Float(this.getRight(), this.getBottom()),
                new Point2D.Float(this.getLeft(), this.getBottom())
        };
    }

    public Rectangle moveTo(float left, float top) {
        this.setRect(left, top, width, height);
        return this;
    }

    public Rectangle moveBy(float leftDelta, float topDelta) {
        this.setRect(x + leftDelta, y + topDelta, width, height);
        return this;
    }

    public Rectangle resize(float newWidth, float newHeight) {
        this.setRect(x, y, newWidth, newHeight);
        return this;
    }

    public Point2D getCenter() {
        return new Point2D.Float((float) getCenterX(), (float) getCenterY());
    }

    public void round() {
        round(2);
    }

    public void round(int decimals) {
        setLeft(FloatUtils.round(getLeft(), decimals));
        setTop(FloatUtils.round(getTop(), decimals));
        setRight(FloatUtils.round(getRight(), decimals));
        setBottom(FloatUtils.round(getBottom(), decimals));
    }

    public void centerExpand(double ds) {
        expand(ds, ds, ds, ds);
    }

    public void centerExpand(double dw, double dh) {
        expand(dw, dh, dw, dh);
    }

    public void expand(double deltaLeft, double deltaTop, double deltaRight, double deltaBottom) {
        setLeft((float) (getLeft() - deltaLeft));
        setRight((float) (getRight() + deltaRight));
        setTop((float) (getTop() - deltaTop));
        setBottom((float) (getBottom() + deltaBottom));
    }

    public void centerReduce(double dw, double dh) {
        setLeft((float) (getLeft() + dw));
        setRight((float) (getRight() - dw));
        setTop((float) (getTop() + dh));
        setBottom((float) (getBottom() - dh));
    }


    public Rectangle withExpand(double deltaLeft, double deltaTop, double deltaRight, double deltaBottom) {
        float left = (float) (getLeft() - deltaLeft);
        float right = (float) (getRight() + deltaRight);
        float top = (float) (getTop() - deltaTop);
        float bottom = (float) (getBottom() + deltaBottom);
        return new Rectangle(left, top, right - left, bottom - top);
    }

    public Rectangle rectReduce(double deltaLeftRight, double deltaTopBottom, double pageWidth, double pageHeight) {
        float left = (float) FastMath.min((getLeft() + deltaLeftRight), pageWidth);
        float right = (float) FastMath.max(FastMath.min(getRight() - deltaLeftRight, pageWidth), 0.0);
        float top = (float) FastMath.min(getTop() + deltaTopBottom, pageHeight);
        float bottom = (float) FastMath.max(FastMath.min(getBottom() - deltaTopBottom, pageHeight), 0.0);
        return new Rectangle(left, top, right - left, bottom - top);
    }

    public Rectangle shrinkToPage(ContentGroupPage page) {
        float left = FastMath.min(FastMath.max(page.getLeft(), this.getLeft()), page.getRight());
        float right = FastMath.max(FastMath.min(page.getRight(), this.getRight()), page.getLeft());
        float top = FastMath.min(FastMath.max(page.getTop(), this.getTop()), page.getBottom());
        float bottom = FastMath.max(FastMath.min(page.getBottom(), this.getBottom()), page.getTop());
        return new Rectangle(left, top, right - left, bottom - top);
    }

    public Rectangle withCenterExpand(double dw, double dh) {
        return withExpand(dw, dh, dw, dh);
    }

    public Rectangle withCenterExpand(double ds) {
        return withExpand(ds, ds, ds, ds);
    }

    public void setGroupHencSeq(int seq){
        this.hencSeq = seq;
    }

    public void setGroupVencSeq(int seq){
        this.vencSeq = seq;
    }

    public int getGroupHencSeq(){
        return this.hencSeq;
    }

    public int getGroupVencSeq(){
        return this.vencSeq;
    }

    public boolean isShapeIntersects(Rectangle other) {
        /**
         * this function may be a little difference with java.awt.Rectangle.intersects(),
         * because the import contain rectangle and line(transform to Rectangle)
         */
        double x = other.getLeft();
        double y = other.getTop();
        double w = other.getWidth();
        double h = other.getHeight();
        if (isEmpty() || w < 0 || h < 0) {
            return false;
        }

        double x0 = this.getX();
        double y0 = this.getY();
        return (x + w >= x0 &&
                y + h >= y0 &&
                x <= x0 + this.getWidth() &&
                y <= y0 + this.getHeight());
    }

    public double calDistance(Rectangle other) {
        double dis;
        boolean isHorizontalCrosss = this.getTop() <= other.getBottom() && this.getBottom() >= other.getTop();
        boolean isVerticalCrosss = this.getLeft() <= other.getRight() && this.getRight() >= other.getLeft();
        if (isHorizontalCrosss && !isVerticalCrosss) {
            dis = FastMath.min( FastMath.min( FastMath.abs(this.getLeft() - other.getLeft() ),
                    FastMath.abs(this.getLeft() - other.getRight()) ),
                    FastMath.min( FastMath.abs(this.getRight() - other.getLeft() ),
                            FastMath.abs(this.getRight() - other.getRight())) );
        } else if (!isHorizontalCrosss && isVerticalCrosss) {
            dis = FastMath.min( FastMath.min( FastMath.abs(this.getTop() - other.getTop() ),
                    FastMath.abs(this.getTop() - other.getBottom()) ),
                    FastMath.min( FastMath.abs(this.getBottom() - other.getTop() ),
                            FastMath.abs(this.getBottom() - other.getBottom())) );
        } else if (!isHorizontalCrosss && !isVerticalCrosss){
            if (this.getTop() > other.getBottom() && this.getLeft() > other.getRight()) {
                double px = this.getLeft() - other.getRight();
                double py = this.getTop() - other.getBottom();
                dis = FastMath.sqrt(px * px + py * py);
            } else if (this.getTop() > other.getBottom() && this.getRight() < other.getLeft()) {
                double px = this.getRight() - other.getLeft();
                double py = this.getTop() - other.getBottom();
                dis = FastMath.sqrt(px * px + py * py);
            } else if (this.getBottom() < other.getTop() && this.getLeft() > other.getRight()) {
                double px = this.getLeft() - other.getRight();
                double py = this.getBottom() - other.getTop();
                dis = FastMath.sqrt(px * px + py * py);
            } else {
                double px = this.getRight() - other.getLeft();
                double py = this.getBottom() - other.getTop();
                dis = FastMath.sqrt(px * px + py * py);
            }
        } else {
            dis = 0.0;
        }
        return dis;
    }

    /**
     * calculate the minimum distance between two shape in all directions,
     * if distance is lower than thres,then return true
     */
    public boolean isShapeAdjacent(Rectangle other, double thres) {
        return this.calDistance(other) < thres;
    }

    /**
     * 合并多个rect
     */
    public static Rectangle union(List<? extends Rectangle2D> rects) {
        if (rects == null || rects.isEmpty()) {
            return null;
        }
        Rectangle bounds = null;
        for (Rectangle2D rect : rects) {
            if (rect.isEmpty()) {
                continue;
            }
            if (bounds == null) {
                bounds = new Rectangle(rect);
            } else {
                bounds.merge(rect);
            }
        }
        return bounds;
    }

}
