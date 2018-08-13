package com.abcft.pdfextract.core.table;

import com.abcft.pdfextract.core.model.Rectangle;
import com.abcft.pdfextract.util.FloatUtils;

import java.awt.*;
import java.awt.geom.Line2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.*;
import java.util.List;

/**
 * Represents strokes (lines).
 */
@SuppressWarnings("serial")
public class Ruling extends Line2D.Float{

    public static final float LINE_WIDTH_THRESHOLD = 2.f;
    static final float LINE_LENGTH_THRESHOLD = 0.01f;

    static final int IMAGE_LINE_WIDTH_THRESHOLD = 9;

    private static int PERPENDICULAR_PIXEL_EXPAND_AMOUNT = 2;
    private static int COLINEAR_OR_PARALLEL_PIXEL_EXPAND_AMOUNT = 1;

    public static final Comparator<Ruling> VERTICAL_TOP_X = (java.util.Comparator<Ruling>) (arg0, arg1) -> {
        if (!arg0.vertical() || !arg1.vertical()) {
            throw new IllegalArgumentException("Rulings must be vertical.");
        }
        float arg0X = FloatUtils.round(arg0.getLeft(), 2);
        float arg0Y = FloatUtils.round(arg0.getTop(), 2);
        float arg1X = FloatUtils.round(arg1.getLeft(), 2);
        float arg1Y = FloatUtils.round(arg1.getTop(), 2);

        return TableUtils.yFirstCompare(arg0X, arg0Y, arg1X, arg1Y);
    };

    public static final Comparator<Ruling> VERTICAL_BOTTOM_X = (java.util.Comparator<Ruling>) (arg0, arg1) -> {
        if (!arg0.vertical() || !arg1.vertical()) {
            throw new IllegalArgumentException("Rulings must be vertical.");
        }
        float arg0X = FloatUtils.round(arg0.getLeft(), 2);
        float arg0Y = FloatUtils.round(arg0.getBottom(), 2);
        float arg1X = FloatUtils.round(arg1.getLeft(), 2);
        float arg1Y = FloatUtils.round(arg1.getBottom(), 2);

        return TableUtils.yFirstCompare(arg0X, arg0Y, arg1X, arg1Y);
    };

    private enum SOType {VERTICAL, HRIGHT, HLEFT}

    enum DrawType {
        /**
         * 表示这条线是普通填充色块的边缘线。
         *
         * <p>普通填充色块时指宽、高都大于 {@value #LINE_WIDTH_THRESHOLD}，视觉上是一个矩形的填充块。</p>
         *
         */
        RECT,
        /**
         * 表示这条线是细长充色块的边缘线。
         *
         * <p>普通填充色块是指宽、高至多有一个大于 {@value #LINE_WIDTH_THRESHOLD}，视觉上是一条线的填充块。</p>
         *
         */
        LINE,
        /**
         * 表示这条线是微小色块的边缘线。
         *
         * <p>普通填充色块是指宽、高都小于 {@value #LINE_WIDTH_THRESHOLD}，视觉上是一个点的填充块。</p>
         *
         */
        DOT,
        /**
         * 表示这条线是疑似图片线。
         *
         * <p>图片点是指宽、高至多有一个大于 {@value #IMAGE_LINE_WIDTH_THRESHOLD}，视觉上是一条线的图片。</p>
         *
         */
        IMAGE_LINE,
        /**
         * 表示这条线是疑似图片表示的点。
         *
         * <p>图片点是指宽、高都小于 {@value #IMAGE_LINE_WIDTH_THRESHOLD}，视觉上是一个点的图片。</p>
         *
         */
        IMAGE_DOT,
        /**
         * 表示这条线是从疑似网格图片表示提取的线。
         *
         * <p>网格图片是指宽、高都大于 {@value #IMAGE_LINE_WIDTH_THRESHOLD}，视觉上是多条网格线的图片。</p>
         *
         */
        IMAGE_GRID,
    }

    static DrawType createDrawType(boolean tinyWidth, boolean tinyHeight) {
        if (tinyWidth && tinyHeight) {
            return DrawType.DOT;
        } else if (tinyWidth ^ tinyHeight) {
            return DrawType.LINE;
        } else {
            return DrawType.RECT;
        }
    }

    // 该条 Ruling 是否为填充区（false 表示这是独立的线）
    private boolean isFill;
    // 该条 Ruling 是否为虚线
    private boolean isDashed;
    // 该条 Ruling 及关联的矩形区域的颜色
    private Color color;
    // 和该条 Ruling 关联的矩形区域的性质（点、线、矩形）
    private DrawType drawType = DrawType.RECT;
    // 和该条 Ruling 关联的矩形区域
    private Rectangle2D bindingBounds;

    private boolean isBlack;            // 是否为黑色
    private boolean deleted = false;    // 是否已删除

    public Ruling(Rectangle2D rect) {
        if (rect.getWidth() > rect.getHeight()) {
            setLine(rect.getMinX(), rect.getCenterY(), rect.getMaxX(), rect.getCenterY());
        } else {
            setLine(rect.getCenterX(), rect.getMinY(), rect.getCenterX(), rect.getMaxY());
        }
    }

    public Ruling(float top, float left, float width, float height) {
        this(new Point2D.Float(left, top), new Point2D.Float(left + width, top + height));
    }

    public Ruling(Point2D p1, Point2D p2) {
        super(p1, p2);
    }

    void setColor(Color color) {
        this.color = color;
        this.isBlack = color.getRGB() == Color.BLACK.getRGB();
    }

    void setFill(boolean fill) {
        isFill = fill;
    }

    void setDashed(boolean dashed) {
        this.isDashed = dashed;
    }

    public void markDeleted() {
        this.deleted = true;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public boolean isBlack() {
        return isBlack;
    }

    void setDrawType(DrawType drawType) {
        this.drawType = drawType;
    }

    void setBindingBounds(Rectangle2D bindingBounds) {
        this.bindingBounds = bindingBounds;
    }

    public Rectangle2D getBindingBounds() {
        return bindingBounds;
    }

    public boolean isFill() {
        return isFill;
    }

    public boolean isDashed() {
        return isDashed;
    }

    public Color getColor() {
        return color;
    }

    public DrawType getDrawType() {
        return drawType;
    }

    /**
     * Normalize almost horizontal or almost vertical lines
     */
    public void normalize() {
        double angle = this.getAngle();
        if (FloatUtils.within(angle, 0, 1) || FloatUtils.within(angle, 180, 1)) { // almost horizontal
            float x1 = Math.min(this.x1, this.x2);
            float x2 = Math.max(this.x1, this.x2);
            super.setLine(x1, this.y1, x2, this.y1);
        } else if (FloatUtils.within(angle, 90, 1) || FloatUtils.within(angle, 270, 1)) { // almost vertical
            float y1 = Math.min(this.y1, this.y2);
            float y2 = Math.max(this.y1, this.y2);
            super.setLine(this.x1, y1, this.x1, y2);
        } /* else {
            System.out.println("oblique: " + this + " ("+ this.getAngle() + ")");
        } */
    }

    @Override
    public void setLine(float x1, float y1, float x2, float y2) {
        super.setLine(x1, y1, x2, y2);
        this.normalize();
    }

    @Override
    public void setLine(double x1, double y1, double x2, double y2) {
        super.setLine(x1, y1, x2, y2);
        this.normalize();
    }

    public boolean vertical() {
        return this.length() > 0 && FloatUtils.feq(this.x1, this.x2); //diff < ORIENTATION_CHECK_THRESHOLD;
    }

    public float verticallyOverlapRatio(Ruling l) {
        float overlapLen = Math.max(0, Math.min(this.getBottom(), l.getBottom()) - Math.max(this.getTop(), l.getTop()));
        return Math.max(overlapLen / (this.getEnd() - this.getStart()), overlapLen / (l.getEnd() - l.getStart()));
    }

    public float horizontallyOverlapRatio(Ruling l) {
        float overlapLen = Math.max(0, Math.min(this.getRight(), l.getRight()) - Math.max(this.getLeft(), l.getLeft()));
        return Math.max(overlapLen / (this.getEnd() - this.getStart()), overlapLen / (l.getEnd() - l.getStart()));
    }

    public boolean isVerticallyOverlap(Rectangle r) {
        return verticalOverlap(r) > 0;
    }

    public boolean isHorizontallyOverlap(Rectangle r) {
        return horizontalOverlap(r) > 0;
    }

    public float horizontalOverlap(Rectangle other) {
        return Math.max(0, Math.min(this.getRight(), other.getRight()) - Math.max(this.getLeft(), other.getLeft()));
    }

    public float verticalOverlap(Rectangle other) {
        return Math.max(0, Math.min(this.getBottom(), other.getBottom()) - Math.max(this.getTop(), other.getTop()));
    }

    public float horizontalOverlap(Rectangle2D other) {
        return (float) Math.max(0, Math.min(this.getRight(), other.getMaxX()) - Math.max(this.getLeft(), other.getMinX()));
    }

    public float verticalOverlap(Rectangle2D other) {
        return (float) Math.max(0, Math.min(this.getBottom(), other.getMaxY()) - Math.max(this.getTop(), other.getMinY()));
    }

    public boolean horizontal() {
        return this.length() > 0 && FloatUtils.feq(this.y1, this.y2); //diff < ORIENTATION_CHECK_THRESHOLD;
    }

    public boolean oblique() {
        return !(this.vertical() || this.horizontal());
    }

    // attributes that make sense only for non-oblique lines
    // these are used to have a single collapse method (in page, currently)
    public float getPosition() {
        if (this.oblique()) {
            throw new UnsupportedOperationException();
        }
        return this.vertical() ? this.getLeft() : this.getTop();
    }

    public void setPosition(float v) {
        if (this.oblique()) {
            throw new UnsupportedOperationException();
        }
        if (this.vertical()) {
            this.setLine(v, getTop(), v, getBottom());
        } else {
            this.setLine(getLeft(), v, getRight(), v);
        }
    }

    public float getStart() {
        if (this.oblique()) {
            throw new UnsupportedOperationException();
        }
        return this.vertical() ? this.getTop() : this.getLeft();
    }

    public void setStart(float v) {
        if (this.oblique()) {
            throw new UnsupportedOperationException();
        }
        if (this.vertical()) {
            this.setTop(v);
        } else {
            this.setLeft(v);
        }
    }

    public float getEnd() {
        if (this.oblique()) {
            throw new UnsupportedOperationException();
        }
        return this.vertical() ? this.getBottom() : this.getRight();
    }

    public void setEnd(float v) {
        if (this.oblique()) {
            throw new UnsupportedOperationException();
        }
        if (this.vertical()) {
            this.setBottom(v);
        } else {
            this.setRight(v);
        }
    }

    private void setStartEnd(float start, float end) {
        if (this.oblique()) {
            throw new UnsupportedOperationException();
        }
        if (this.vertical()) {
            this.setLine(getLeft(), start, getRight(), end);
        } else {
            this.setLine(start, getTop(), end, getBottom());
        }
    }

    /**
     * ruling方向可能为从右向左/从下到上
     */
    public Rectangle toRectangle() {
        float minX, maxX, minY, maxY;
        if (this.getLeft() > this.getRight()) {
            minX = this.getRight();
            maxX = this.getLeft();
        } else {
            minX = this.getLeft();
            maxX = this.getRight();
        }

        if (this.getTop() > this.getBottom()) {
            minY = this.getBottom();
            maxY = this.getTop();
        } else {
            minY = this.getTop();
            maxY = this.getBottom();
        }

        return new Rectangle(minX, minY, maxX - minX, maxY - minY);
    }

    public TableRegion toTableRegion() {
        return new TableRegion(getLeft(), getTop(), getWidth(), getHeight());
    }

    public boolean perpendicularTo(Ruling other) {
        return this.vertical() == other.horizontal();
    }

    public boolean colinear(Point2D point) {
        return point.getX() >= this.x1
                && point.getX() <= this.x2
                && point.getY() >= this.y1
                && point.getY() <= this.y2;
    }

    // if the lines we're comparing are colinear or parallel, we centerExpand them by a only 1 pixel,
    // because the expansions are additive
    // (e.g. two vertical lines, at x = 100, with one having y2 of 98 and the other having y1 of 102 would
    // erroneously be said to nearlyIntersect if they were each expanded by 2 (since they'd both terminate at 100).
    // By default the COLINEAR_OR_PARALLEL_PIXEL_EXPAND_AMOUNT is only 1 so the total expansion is 2.
    // A total expansion amount of 2 is empirically verified to work sometimes. It's not a magic number from any
    // source other than a little bit of experience.)
    public boolean nearlyIntersects(Ruling another) {
        return this.nearlyIntersects(another, COLINEAR_OR_PARALLEL_PIXEL_EXPAND_AMOUNT);
    }

    public boolean nearlyIntersects(Ruling another, int colinearOrParallelExpandAmount) {
        if (this.intersectsLine(another)) {
            return true;
        }

        boolean rv = false;

        if (this.perpendicularTo(another)) {
            rv = this.expand(PERPENDICULAR_PIXEL_EXPAND_AMOUNT).intersectsLine(another);
        } else {
            rv = this.expand(colinearOrParallelExpandAmount)
                    .intersectsLine(another.expand(colinearOrParallelExpandAmount));
        }

        return rv;
    }

    public double length() {
        return Math.sqrt(Math.pow(this.x1 - this.x2, 2) + Math.pow(this.y1 - this.y2, 2));
    }

    public Ruling intersect(Rectangle2D clip) {
        Float clipee = (Float) this.clone();
        boolean clipped = new CohenSutherlandClipping(clip).clip(clipee);

        if (clipped) {
            return new Ruling(clipee.getP1(), clipee.getP2());
        } else {
            return this;
        }
    }

    public Ruling expand(float amount) {
        if (this.oblique()) {
            throw new UnsupportedOperationException();
        }
        Ruling r = (Ruling) this.clone();
        // FIX lines must be orthogonal, vertical and horizontal
        if (this.vertical()) {
            r.setTop(this.getTop() - amount);
            r.setBottom(this.getBottom() + amount);
        } else {
            r.setLeft(this.getLeft() - amount);
            r.setRight(this.getRight() + amount);
        }
        return r;
    }

    public Point2D intersectionPoint(Ruling other) {
        Ruling this_l = this.expand(PERPENDICULAR_PIXEL_EXPAND_AMOUNT);
        Ruling other_l = other.expand(PERPENDICULAR_PIXEL_EXPAND_AMOUNT);
        Ruling horizontal, vertical;

        if (!this_l.intersectsLine(other_l)) {
            return null;
        }

        if (this_l.horizontal() && other_l.vertical()) {
            horizontal = this_l;
            vertical = other_l;
        } else if (this_l.vertical() && other_l.horizontal()) {
            vertical = this_l;
            horizontal = other_l;
        } else {
            throw new IllegalArgumentException("lines must be orthogonal, vertical and horizontal");
        }
        return new Point2D.Float(vertical.getLeft(), horizontal.getTop());
    }

    @Override
    public boolean equals(Object other) {
        if (this == other)
            return true;

        if (!(other instanceof Ruling))
            return false;

        Ruling o = (Ruling) other;
        return this.getP1().equals(o.getP1()) && this.getP2().equals(o.getP2());
    }

    @Override
    public int hashCode() {
        return super.hashCode();
    }

    public float getTop() {
        return this.y1;
    }

    public void setTop(float v) {
        setLine(this.getLeft(), v, this.getRight(), this.getBottom());
    }

    public float getLeft() {
        return this.x1;
    }

    public void setLeft(float v) {
        setLine(v, this.getTop(), this.getRight(), this.getBottom());
    }

    public float getBottom() {
        return this.y2;
    }

    public void setBottom(float v) {
        setLine(this.getLeft(), this.getTop(), this.getRight(), v);
    }

    public float getRight() {
        return this.x2;
    }

    public void setRight(float v) {
        setLine(this.getLeft(), this.getTop(), v, this.getBottom());
    }

    public float getWidth() {
        return this.getRight() - this.getLeft();
    }

    public float getHeight() {
        return this.getBottom() - this.getTop();
    }

    public float getCenterX() {
        return this.x1 + this.getWidth() / 2;
    }

    public float getCenterY() {
        return this.y1 + this.getHeight() / 2;
    }

    public static double calAvgCenterX(List<Ruling> rulings) {
        if (rulings == null || rulings.isEmpty()) {
            return -java.lang.Float.MAX_VALUE;
        }
        double avgX = 0;
        for (Ruling rul : rulings) {
            avgX += rul.getX1() + rul.getWidth() / 2;
        }
        return avgX / rulings.size();
    }

    public static double calAvgCenterY(List<Ruling> rulings) {
        if (rulings == null || rulings.isEmpty()) {
            return -java.lang.Float.MAX_VALUE;
        }
        double avgY = 0;
        for (Ruling rul : rulings) {
            avgY += rul.getY1() + rul.getHeight() / 2;
        }
        return avgY / rulings.size();
    }

    public static Rectangle getBounds(List<Ruling> rulings) {
        if (rulings == null || rulings.isEmpty()) {
            return null;
        }

        float minx = java.lang.Float.MAX_VALUE;
        float miny = java.lang.Float.MAX_VALUE;
        float maxx = java.lang.Float.MIN_VALUE;
        float maxy = java.lang.Float.MIN_VALUE;

        for (Ruling r : rulings) {
            minx = (float) Math.min(r.getX1(), minx);
            miny = (float) Math.min(r.getY1(), miny);
            maxx = (float) Math.max(r.getX2(), maxx);
            maxy = (float) Math.max(r.getY2(), maxy);
        }
        return new Rectangle(minx, miny, maxx - minx, maxy - miny);
    }

    public double getAngle() {
        double angle = Math.toDegrees(Math.atan2(this.getP2().getY() - this.getP1().getY(),
                this.getP2().getX() - this.getP1().getX()));

        if (angle < 0) {
            angle += 360;
        }
        return angle;
    }

    public String getDirection() {
        if (vertical()) {
            return "V";
        } else if (horizontal()) {
            return "H";
        } else {
            return "X";
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        Formatter formatter = new Formatter(sb);
        String rv = formatter.format("%s[x1=%.0f y1=%.0f x2=%.0f y2=%.0f l=%.0f d=%s t=%s]\n", this.getClass().getSimpleName(),
                this.x1, this.y1, this.x2, this.y2,
                length(), getDirection(), drawType).toString();
        formatter.close();
        return rv;
    }

    public static List<Ruling> cropRulingsToArea(List<Ruling> rulings, Rectangle2D area) {
        ArrayList<Ruling> rv = new ArrayList<>();
        for (Ruling r : rulings) {
            if (r.intersects(area)) {
                rv.add(r.intersect(area));
            }
        }
        return rv;
    }

    public static List<Ruling> getRulingsFromArea(List<Ruling> rulings, Rectangle2D area) {
        ArrayList<Ruling> rv = new ArrayList<>();
        for (Ruling r : rulings) {
            if (r.intersects(area)) {
                rv.add(r);
            }
        }
        return rv;
    }

    // log(n) implementation of find_intersections
    // based on http://people.csail.mit.edu/indyk/6.838-old/handouts/lec2.pdf
    public static Map<Point2D, Ruling[]> findIntersections(List<Ruling> horizontals, List<Ruling> verticals) {

        class SortObject {
            protected SOType type;
            protected float position;
            protected Ruling ruling;

            public SortObject(SOType type, float position, Ruling ruling) {
                this.type = type;
                this.position = position;
                this.ruling = ruling;
            }
        }

        List<SortObject> sos = new ArrayList<>();

        TreeMap<Ruling, Boolean> tree = new TreeMap<>(Comparator.comparingDouble(Ruling::getTop));

        TreeMap<Point2D, Ruling[]> rv = new TreeMap<>((o1, o2) -> {
            if (o1.getY() > o2.getY()) return 1;
            if (o1.getY() < o2.getY()) return -1;
            if (o1.getX() > o2.getX()) return 1;
            if (o1.getX() < o2.getX()) return -1;
            return 0;
        });

        for (Ruling h : horizontals) {
            sos.add(new SortObject(SOType.HLEFT, h.getLeft() - PERPENDICULAR_PIXEL_EXPAND_AMOUNT, h));
            sos.add(new SortObject(SOType.HRIGHT, h.getRight() + PERPENDICULAR_PIXEL_EXPAND_AMOUNT, h));
        }

        for (Ruling v : verticals) {
            sos.add(new SortObject(SOType.VERTICAL, v.getLeft(), v));
        }

        sos.sort((a, b) -> {
            int rv1;
            if (FloatUtils.feq(a.position, b.position)) {
                if (a.type == SOType.VERTICAL && b.type == SOType.HLEFT) {
                    rv1 = 1;
                } else if (a.type == SOType.VERTICAL && b.type == SOType.HRIGHT) {
                    rv1 = -1;
                } else if (a.type == SOType.HLEFT && b.type == SOType.VERTICAL) {
                    rv1 = -1;
                } else if (a.type == SOType.HRIGHT && b.type == SOType.VERTICAL) {
                    rv1 = 1;
                } else {
                    rv1 = java.lang.Double.compare(a.position, b.position);
                }
            } else {
                return java.lang.Double.compare(a.position, b.position);
            }
            return rv1;
        });

        for (SortObject so : sos) {
            switch (so.type) {
                case VERTICAL:
                    for (Map.Entry<Ruling, Boolean> h : tree.entrySet()) {
                        Point2D i = h.getKey().intersectionPoint(so.ruling);
                        if (i == null) {
                            continue;
                        }
                        rv.put(i,
                                new Ruling[]{h.getKey().expand(PERPENDICULAR_PIXEL_EXPAND_AMOUNT),
                                        so.ruling.expand(PERPENDICULAR_PIXEL_EXPAND_AMOUNT)});
                    }
                    break;
                case HRIGHT:
                    tree.remove(so.ruling);
                    break;
                case HLEFT:
                    tree.put(so.ruling, true);
                    break;
            }
        }

        return rv;

    }

    public static List<Ruling> collapseOrientedRulings(List<Ruling> lines) {
        return collapseOrientedRulings(lines, COLINEAR_OR_PARALLEL_PIXEL_EXPAND_AMOUNT);
    }

    public static List<Ruling> collapseOrientedRulings(List<Ruling> lines, int expandAmount) {
        ArrayList<Ruling> rv = new ArrayList<>();
        lines.sort((a, b) -> {
            final float diff = a.getPosition() - b.getPosition();
            return java.lang.Float.compare(diff == 0 ? a.getStart() - b.getStart() : diff, 0f);
        });

        for (Ruling next_line : lines) {
            Ruling last = rv.isEmpty() ? null : rv.get(rv.size() - 1);
            // if current line colinear with next, and are "close enough": centerExpand current line
            if (last != null && FloatUtils.feq(next_line.getPosition(), last.getPosition()) && last.nearlyIntersects(next_line, expandAmount)) {
                final float lastStart = last.getStart();
                final float lastEnd = last.getEnd();

                final boolean lastFlipped = lastStart > lastEnd;
                final boolean nextFlipped = next_line.getStart() > next_line.getEnd();

                boolean differentDirections = nextFlipped != lastFlipped;
                float nextS = differentDirections ? next_line.getEnd() : next_line.getStart();
                float nextE = differentDirections ? next_line.getStart() : next_line.getEnd();

                final float newStart = lastFlipped ? Math.max(nextS, lastStart) : Math.min(nextS, lastStart);
                final float newEnd = lastFlipped ? Math.min(nextE, lastEnd) : Math.max(nextE, lastEnd);

                last.setStartEnd(newStart, newEnd);
                assert !last.oblique();
            } else if (next_line.length() == 0) {
                continue;
            } else {
                rv.add(next_line);
            }
        }
        return rv;
    }

    private static List<Ruling> findMissingHorizontalRulings(float y, List<Ruling> alignedVerticalRulings, List<Ruling> otherHorizontalRulings) {
        List<Ruling> missingHorizontalRulings = new ArrayList<>();
        if (alignedVerticalRulings.size() < 2) {
            // No more than 2 aligned rulings
            return missingHorizontalRulings;
        }
        int start = 0;
        while (start < alignedVerticalRulings.size()) {
            Ruling firstCol = alignedVerticalRulings.get(start);
            float x1 = firstCol.getLeft();
            int end;
            // Find horizontal rulings in other lines.
            for (end = alignedVerticalRulings.size() - 1; end > start; --end) {
                Ruling lastCol = alignedVerticalRulings.get(end);
                float x2 = lastCol.getRight();
                boolean exists = otherHorizontalRulings.stream()
                        .anyMatch(hr -> FloatUtils.within(y, hr.getTop(), 0.1f) && (FloatUtils.within(hr.getLeft(), x1, 0.1f) || (x1 >= hr.getLeft()))
                                && (FloatUtils.within(hr.getRight(), x2, 0.1f) || (x2 <= hr.getRight())));
                boolean hasHorizontal = otherHorizontalRulings.stream()
                        .anyMatch(hr -> FloatUtils.within(hr.getRight(), x2, 0.1f));
                if (hasHorizontal) {
                    // We have similar horizontal rulings in other lines...
                    if (!exists) {
                        // The ruling is missing...
                        missingHorizontalRulings.add(new Ruling(y, x1, x2 - x1, 0.0f));
                    }
                    break;
                }
            }
            start = end + 1;
        }
        return missingHorizontalRulings;
    }

    private static void checkRepeateLine(boolean hvFlag, List<Ruling> lines) {
        if (!lines.isEmpty()) {
            Ruling baseLine;
            if (hvFlag == true) {
                lines.sort(Comparator.comparing(Ruling::getX1));
                baseLine = lines.get(0);
                for (int i = 1; i < lines.size(); i++) {
                    Ruling otherLine =  lines.get(i);
                    if (otherLine.getX1() - baseLine.getX2() >= 3) {
                        baseLine = otherLine;
                        continue;
                    } else {
                        otherLine.markDeleted();
                        if (otherLine.getX2() > baseLine.getX2()) {
                            baseLine.x2 = (float)otherLine.getX2();
                        }
                    }
                }
            } else {
                lines.sort(Comparator.comparing(Ruling::getY1));
                baseLine = lines.get(0);
                for (int i = 1; i < lines.size(); i++) {
                    Ruling otherLine =  lines.get(i);
                    if (otherLine.getY1() - baseLine.getY2() >= 3) {
                        baseLine = otherLine;
                        continue;
                    } else {
                        otherLine.markDeleted();
                        if (otherLine.getY2() > baseLine.getY2()) {
                            baseLine.y2 = (float)otherLine.getY2();
                        }
                    }
                }
            }
        }
    }

    private static void preProcessLines(List<Ruling> horizontalRulingLines, List<Ruling> verticalRulingLines) {

        List<Ruling> targetLines = new ArrayList<>();
        // process henc
        if (!horizontalRulingLines.isEmpty()) {
            Ruling baseLine = horizontalRulingLines.get(0);
            targetLines.add(baseLine);
            for (int i = 1; i < horizontalRulingLines.size(); i++) {
                Ruling candidateLine = horizontalRulingLines.get(i);
                if (FloatUtils.feq(baseLine.getY1(), candidateLine.getY1(), 2.0)) {
                    targetLines.add(candidateLine);
                } else {
                    checkRepeateLine(true, targetLines);
                    baseLine = candidateLine;
                    targetLines.clear();
                    targetLines.add(baseLine);
                }
            }
            checkRepeateLine(true, targetLines);
            horizontalRulingLines.removeIf(Ruling::isDeleted);
            targetLines.clear();

            // process venc
            if (!verticalRulingLines.isEmpty()) {
                baseLine = verticalRulingLines.get(0);
                targetLines.add(baseLine);
                for (int i = 1; i < verticalRulingLines.size(); i++) {
                    Ruling candidateLine = verticalRulingLines.get(i);
                    if (FloatUtils.feq(baseLine.getX1(), candidateLine.getX1(), 2.0)) {
                        targetLines.add(candidateLine);
                    } else {
                        checkRepeateLine(false, targetLines);
                        baseLine = candidateLine;
                        targetLines.clear();
                        targetLines.add(baseLine);
                    }
                }
                checkRepeateLine(false, targetLines);
                verticalRulingLines.removeIf(Ruling::isDeleted);
                targetLines.clear();
            }
        }
    }

    public static List<Cell> findCells(List<Ruling> horizontalRulingLines, List<Ruling> verticalRulingLines) {
        List<Cell> cellsFound = new ArrayList<>();

        horizontalRulingLines.sort(Comparator.comparing(Ruling::getY1));
        verticalRulingLines.sort(Comparator.comparing(Ruling::getX1));

        preProcessLines(horizontalRulingLines, verticalRulingLines);

        List<Ruling> hrs = new ArrayList<>(horizontalRulingLines);
        List<Ruling> vrs = new ArrayList<>(verticalRulingLines);

        // TODO Find vertical ruling lines with aligned endpoints at the top/bottom of a grid
        // that aren't connected with an horizontal ruler?
        // see: https://github.com/jazzido/tabula-extractor/issues/78#issuecomment-41481207
        if (!verticalRulingLines.isEmpty()) {
            // Align top endpoints
            List<Ruling> alignedRulings = new ArrayList<>();
            Ruling relativeRule;

            verticalRulingLines.sort(Ruling.VERTICAL_TOP_X);
            relativeRule = verticalRulingLines.get(0);
            alignedRulings.add(relativeRule);

            for (int i = 1; i < verticalRulingLines.size(); ++i) {
                Ruling r = verticalRulingLines.get(i);
                if (FloatUtils.within(r.getTop(), relativeRule.getTop(), 0.2f)) {
                    // Same aligned vertical rulings
                    alignedRulings.add(r);
                } else {
                    hrs.addAll(findMissingHorizontalRulings(relativeRule.getTop(), alignedRulings, horizontalRulingLines));
                    alignedRulings.clear();
                    relativeRule = r;
                    alignedRulings.add(r);
                }
            }
            hrs.addAll(findMissingHorizontalRulings(relativeRule.getTop(), alignedRulings, horizontalRulingLines));
            alignedRulings.clear();

            verticalRulingLines.sort(Ruling.VERTICAL_BOTTOM_X);
            relativeRule = verticalRulingLines.get(0);
            alignedRulings.add(relativeRule);

            for (int i = 1; i < verticalRulingLines.size(); ++i) {
                Ruling r = verticalRulingLines.get(i);
                if (FloatUtils.within(r.getBottom(), relativeRule.getBottom(), 0.2f)) {
                    // Same aligned vertical rulings
                    alignedRulings.add(r);
                } else {
                    hrs.addAll(findMissingHorizontalRulings(relativeRule.getBottom(), alignedRulings, horizontalRulingLines));
                    alignedRulings.clear();
                    relativeRule = r;
                    alignedRulings.add(r);
                }
            }
            hrs.addAll(findMissingHorizontalRulings(relativeRule.getBottom(), alignedRulings, horizontalRulingLines));
            alignedRulings.clear();
        }

        // TODO This should not happen, find out why
        hrs.removeIf(r -> !r.horizontal());
        vrs.removeIf(r -> !r.vertical());

        Map<Point2D, Ruling[]> intersectionPoints = Ruling.findIntersections(hrs, vrs);
        List<Point2D> intersectionPointsList = new ArrayList<>(intersectionPoints.keySet());
        intersectionPointsList.sort(PointComparator.Y_FIRST);
        boolean doBreak;

        for (int i = 0; i < intersectionPointsList.size(); i++) {
            Point2D topLeft = intersectionPointsList.get(i);
            Ruling[] hv = intersectionPoints.get(topLeft);
            doBreak = false;

            // CrossingPointsDirectlyBelow( topLeft );
            List<Point2D> xPoints = new ArrayList<>();
            // CrossingPointsDirectlyToTheRight( topLeft );
            List<Point2D> yPoints = new ArrayList<>();

            for (Point2D p : intersectionPointsList.subList(i, intersectionPointsList.size())) {
                if (p.getX() == topLeft.getX() && p.getY() > topLeft.getY()) {
                    xPoints.add(p);
                }
                if (p.getY() == topLeft.getY() && p.getX() > topLeft.getX()) {
                    yPoints.add(p);
                }
            }
            outer:
            for (Point2D xPoint : xPoints) {
                if (doBreak) {
                    break;
                }

                // is there a vertical edge b/w topLeft and xPoint?
                if (!hv[1].equals(intersectionPoints.get(xPoint)[1])) {
                    continue;
                }
                for (Point2D yPoint : yPoints) {
                    // is there an horizontal edge b/w topLeft and yPoint ?
                    if (!hv[0].equals(intersectionPoints.get(yPoint)[0])) {
                        continue;
                    }
                    Point2D btmRight = new Point2D.Float((float) yPoint.getX(), (float) xPoint.getY());
                    if (intersectionPoints.containsKey(btmRight)
                            && intersectionPoints.get(btmRight)[0].equals(intersectionPoints.get(xPoint)[0])
                            && intersectionPoints.get(btmRight)[1].equals(intersectionPoints.get(yPoint)[1])) {
                        cellsFound.add(new Cell(topLeft, btmRight));
                        doBreak = true;
                        break outer;
                    }
                }
            }
        }

        return cellsFound;
    }

    public boolean nearlyEqualColor(Color color, int thresh) {
        if (this.getColor() == null) {
            return false;
        }
        if (FloatUtils.feq(this.getColor().getRed(), color.getRed(), thresh)
                && FloatUtils.feq(this.getColor().getGreen(), color.getGreen(), thresh)
                && FloatUtils.feq(this.getColor().getBlue(), color.getBlue(), thresh)) {
            return true;
        }
        return false;
    }

    public boolean nearlyEqualColor(Ruling other, int thresh) {
        if (this.getColor() == null || other.getColor() == null) {
            return false;
        }
        if (FloatUtils.feq(this.getColor().getRed(), other.getColor().getRed(), thresh)
                && FloatUtils.feq(this.getColor().getGreen(), other.getColor().getGreen(), thresh)
                && FloatUtils.feq(this.getColor().getBlue(), other.getColor().getBlue(), thresh)) {
            return true;
        }
        return false;
    }

    public static boolean allNearlyEqualColor(List<Ruling> rulings, int thresh) {
        if (rulings == null || rulings.isEmpty()) {
            return false;
        }

        Color color = rulings.get(0).getColor();
        if (color == null) {
            return false;
        }

        for (int i = 1; i < rulings.size(); i++) {
            Color color1 = rulings.get(i).getColor();
            if (color1 == null || !rulings.get(i).nearlyEqualColor(color, thresh)) {
                return false;
            }
        }
        return true;
    }
}
