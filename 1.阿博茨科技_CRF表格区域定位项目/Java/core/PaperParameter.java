package com.abcft.pdfextract.core;

import com.abcft.pdfextract.util.FloatUtils;

import java.awt.geom.Rectangle2D;

/**
 * 表示页面参数。
 */
public final class PaperParameter {

    private static final int DEFAULT_VERTICAL_MARGIN = 69;
    private static final int DEFAULT_HORIZONTAL_MARGIN = 28;


    /**
     * A4 纸，标准边距。
     *
     * <p>595.35x841.99, ~90.72, ~70.88 pt
     */
    public static final PaperParameter A4 = createInMillis(210, 297, 32, 25);
    /**
     * A4 纸，窄边距。
     */
    public static final PaperParameter A4_NARROW = A4.withMarginsInMilis(13, 13);
    /**
     * A4 纸，中等边距。
     */
    public static final PaperParameter A4_MEDIUM = A4.withMarginsInMilis( 19, 25);
    /**
     * A4 纸，宽边距。
     */
    public static final PaperParameter A4_WIDE = A4.withMarginsInMilis( 51, 25);
    /**
     * A4 纸，Adobe 边距。
     *
     * <p>595.35x841.99, ~28, ~59 pt
     */
    public static final PaperParameter A4_ADOBE = A4.withMargins(DEFAULT_HORIZONTAL_MARGIN, DEFAULT_VERTICAL_MARGIN);

    /**
     * US 信值，Adobe 边距。
     *
     * <p>612x792, ~36, ~59 pt
     */
    public static final PaperParameter US_LETTER = createInMillis(216, 279, 13, 21);


    private static final PaperParameter[] PAPERS = new PaperParameter[] {
            A4_ADOBE,
            US_LETTER
    };

    /**
     * 纸宽度，单位毫米。
     */
    public final int widthInMillis;
    /**
     * 纸高度，单位毫米。
     */
    public final int heightInMillis;
    /**
     * 左边距，单位毫米。
     */
    public final int leftMarginInMillis;
    /**
     * 上边距，单位毫米。
     */
    public final int topMarginInMillis;
    /**
     * 右边距，单位毫米。
     */
    public final int rightMarginInMillis;
    /**
     * 下边距，单位毫米。
     */
    public final int bottomMarginInMillis;

    /**
     * 纸宽度，单位 pt。
     */
    public final float widthInPt;
    /**
     * 纸高度，单位 pt。
     */
    public final float heightInPt;
    /**
     * 左边距，单位 pt。
     */
    public final float leftMarginInPt;
    /**
     * 上边距，单位 pt。
     */
    public final float topMarginInPt;
    /**
     * 右边距，单位 pt。
     */
    public final float rightMarginInPt;
    /**
     * 下边距，单位 pt。
     */
    public final float bottomMarginInPt;

    /**
     * 查找一个合适的页面参数。
     *
     * @param widthPt 实际的页宽，单位 pt。
     * @param heightPt 实际的页高，单位 pt。
     * @param vertical true 表示一个纵向页面，false 表示横向页面。
     * @return 预定义的页面参数，没找到则返回 {@code null}。
     */
    public static PaperParameter findPage(float widthPt, float heightPt, boolean vertical) {
        for(PaperParameter paper: PAPERS) {
            if (vertical
                    && FloatUtils.feq(paper.widthInPt, widthPt, 0.5f)
                    && FloatUtils.feq(paper.heightInPt, heightPt, 0.5f)) {
                return paper;
            } else if (!vertical
                    && FloatUtils.feq(paper.heightInPt, widthPt, 0.5f)
                    && FloatUtils.feq(paper.widthInPt, heightPt, 0.5f)) {
                return paper.horizontal();
            }
        }
        if (vertical) {
            return createInPt(widthPt, heightPt, DEFAULT_HORIZONTAL_MARGIN, DEFAULT_VERTICAL_MARGIN);
        } else {
            return createInPt(widthPt, heightPt, DEFAULT_VERTICAL_MARGIN, DEFAULT_HORIZONTAL_MARGIN);
        }
    }

    private PaperParameter horizontal() {
        //noinspection SuspiciousNameCombination
        return createInPt(heightInPt, widthInPt,
                topMarginInPt, leftMarginInPt, bottomMarginInPt, rightMarginInPt);
    }

    private static float millisToPt(int millis) {
        // A4 Standard: 210mmx297mm --- 395.32ptx842.02pt
        // Thus pt/mm = 2.835f => pt = mm * 2.835f
        return millis * 2.835f;
    }

    private static int ptToMillis(float pt) {
        // A4 Standard: 210mmx297mm --- 395.32ptx842.02pt
        // Thus pt/mm = 2.835f => pt = mm * 2.835f
        return Math.round(pt / 2.835f);
    }

    private static PaperParameter createInMillis(int width, int height,
                   int horizontalMargin, int verticalMargin) {
        return createInMillis(width, height, horizontalMargin, verticalMargin, horizontalMargin, verticalMargin);
    }

    private static PaperParameter createInMillis(int width, int height,
                   int leftMargin, int topMargin,
                   int rightMargin, int bottomMargin) {
        return new PaperParameter(width, height,
                leftMargin, topMargin, rightMargin, bottomMargin,
                millisToPt(width), millisToPt(height),
                millisToPt(leftMargin), millisToPt(topMargin), millisToPt(rightMargin), millisToPt(bottomMargin));
    }

    private static PaperParameter createInPt(float width, float height,
                           float leftMargin, float topMargin,
                           float rightMargin, float bottomMargin) {
        return new PaperParameter(
                ptToMillis(width), ptToMillis(height),
                ptToMillis(leftMargin), ptToMillis(topMargin), ptToMillis(rightMargin), ptToMillis(bottomMargin),
                width, height,
                leftMargin, topMargin, rightMargin, bottomMargin);
    }

    private static PaperParameter createInPt(float width, float height,
                                             float horizontalMargin, float verticalMargin) {
        return createInPt(width, height, horizontalMargin, verticalMargin, horizontalMargin, verticalMargin);
    }

    private PaperParameter(
            int widthInMillis, int heightInMillis,
            int leftMarginInMillis, int topMarginInMillis,
            int rightMarginInMillis, int bottomMarginInMillis,
            float widthInPt, float heightInPt,
                           float leftMarginInPt, float topMarginInPt,
                           float rightMarginInPt, float bottomMarginInPt) {
        this.widthInMillis = widthInMillis;
        this.heightInMillis = heightInMillis;
        this.leftMarginInMillis = leftMarginInMillis;
        this.topMarginInMillis = topMarginInMillis;
        this.rightMarginInMillis = rightMarginInMillis;
        this.bottomMarginInMillis = bottomMarginInMillis;

        this.widthInPt = widthInPt;
        this.heightInPt = heightInPt;
        this.leftMarginInPt = leftMarginInPt;
        this.topMarginInPt = topMarginInPt;
        this.rightMarginInPt = rightMarginInPt;
        this.bottomMarginInPt = bottomMarginInPt;
    }

    public Rectangle2D createFrame() {
        return new Rectangle2D.Float(leftMarginInPt, topMarginInPt,
                widthInPt - leftMarginInPt - rightMarginInPt,
                heightInPt - topMarginInPt - bottomMarginInPt);
    }


    public PaperParameter withMargins(float leftMargin, float topMargin, float rightMargin, float bottomMargin) {
        return createInPt(
                widthInPt, heightInPt,
                leftMargin, topMargin, rightMargin, bottomMargin);
    }

    public PaperParameter withMargins(float horizontalMargin, float verticalMargin) {
        return createInPt(widthInPt, heightInPt, horizontalMargin, verticalMargin);
    }

    public PaperParameter withMarginsInMilis(int leftMargin, int topMargin, int rightMargin, int bottomMargin) {
        return createInMillis(widthInMillis, heightInMillis, leftMargin, topMargin, rightMargin, bottomMargin);
    }

    public PaperParameter withMarginsInMilis(int horizontalMargin, int verticalMargin) {
        return createInMillis(widthInMillis, heightInMillis, horizontalMargin, verticalMargin);
    }

    /**
     * 判断给定的屏幕坐标点是否在边距内。
     * @param x x 坐标，单位点。
     * @param y y 坐标，单位点。
     * @param epsilon 容许的误差。
     * @return 如果给定的坐标在边距内则返回 {@code true}，否则返回 {@code false}。
     */
    public boolean marginContains(double x, double y, double epsilon) {
        return leftMarginInPt > x + epsilon
                || x - epsilon > widthInPt - rightMarginInPt
                || topMarginInPt > y + epsilon
                || y - epsilon > heightInPt - bottomMarginInPt;
    }
    /**
     * 判断给定的屏幕坐标点是否在左右边距内。
     * @param x x 坐标，单位点。
     * @param epsilon 容许的误差。
     * @return 如果给定的坐标在左右边距内则返回 {@code true}，否则返回 {@code false}。
     */
    public boolean horizontalMarginContains(double x, double epsilon) {
        return leftMarginContains(x, epsilon)
                || rightMarginContains(x, epsilon);
    }

    /**
     * 判断给定的屏幕坐标点是否在上下边距内。
     * @param y y 坐标，单位点。
     * @param epsilon 容许的误差。
     * @return 如果给定的坐标在上下边距内则返回 {@code true}，否则返回 {@code false}。
     */
    public boolean verticalMarginContains(double y, double epsilon) {
        return topMarginContains(y, epsilon)
                || bottomMarginContains(y, epsilon);
    }


    /**
     * 判断给定的屏幕坐标点是否在左边距内。
     * @param x x 坐标，单位点。
     * @param epsilon 容许的误差。
     * @return 如果给定的坐标在左边距内则返回 {@code true}，否则返回 {@code false}。
     */
    public boolean leftMarginContains(double x, double epsilon) {
        return leftMarginInPt > x + epsilon;
    }

    /**
     * 判断给定的屏幕坐标点是否在右边距内。
     * @param x x 坐标，单位点。
     * @param epsilon 容许的误差。
     * @return 如果给定的坐标在右边距内则返回 {@code true}，否则返回 {@code false}。
     */
    public boolean rightMarginContains(double x, double epsilon) {
        return x - epsilon > widthInPt - rightMarginInPt;
    }

    /**
     * 判断给定的屏幕坐标点是否在上边距内。
     * @param y y 坐标，单位点。
     * @param epsilon 容许的误差。
     * @return 如果给定的坐标在上边距内则返回 {@code true}，否则返回 {@code false}。
     */
    public boolean topMarginContains(double y, double epsilon) {
        return topMarginInPt > y + epsilon;
    }

    /**
     * 判断给定的屏幕坐标点是否在下边距内。
     * @param y y 坐标，单位点。
     * @param epsilon 容许的误差。
     * @return 如果给定的坐标在下边距内则返回 {@code true}，否则返回 {@code false}。
     */
    public boolean bottomMarginContains(double y, double epsilon) {
        return y - epsilon > heightInPt - bottomMarginInPt;
    }

    /**
     * 判断给定的屏幕坐标点是否在边距内，容许误差为 0.5 pt。
     * @param x x 坐标，单位 pt。
     * @param y y 坐标，单位 pt。
     * @return 如果给定的坐标在边距内则返回 {@code true}，否则返回 {@code false}。
     */
    public boolean marginContains(double x, double y) {
        return marginContains(x, y, 0.5f);
    }
    /**
     * 判断给定的屏幕坐标点是否在左右边距内，容许误差为 0.5 pt。
     * @param x x 坐标，单位 pt。
     * @return 如果给定的坐标在左右边距内则返回 {@code true}，否则返回 {@code false}。
     */
    public boolean horizontalMarginContains(double x) {
        return horizontalMarginContains(x, 0.5f);
    }

    /**
     * 判断给定的屏幕坐标点是否在上下边距内，容许误差为 0.5 pt。
     * @param y y 坐标，单位 pt。
     * @return 如果给定的坐标在上下边距内则返回 {@code true}，否则返回 {@code false}。
     */
    public boolean verticalMarginContains(double y) {
        return verticalMarginContains(y, 0.5f);
    }


    /**
     * 判断给定的屏幕坐标点是否在左边距内，容许误差为 0.5 pt。
     * @param x x 坐标，单位 pt。
     * @return 如果给定的坐标在左边距内则返回 {@code true}，否则返回 {@code false}。
     */
    public boolean leftMarginContains(double x) {
        return leftMarginContains(x, 0.5f);
    }

    /**
     * 判断给定的屏幕坐标点是否在右边距内，容许误差为 0.5 pt。
     * @param x x 坐标，单位 pt。
     * @return 如果给定的坐标在右边距内则返回 {@code true}，否则返回 {@code false}。
     */
    public boolean rightMarginContains(double x) {
        return rightMarginContains(x, 0.5f);
    }

    /**
     * 判断给定的屏幕坐标点是否在上边距内，容许误差为 0.5 pt。
     * @param y y 坐标，单位 pt。
     * @return 如果给定的坐标在上边距内则返回 {@code true}，否则返回 {@code false}。
     */
    public boolean topMarginContains(double y) {
        return topMarginContains(y, 0.5f);
    }

    /**
     * 判断给定的屏幕坐标点是否在下边距内，容许误差为 0.5 pt。
     * @param y y 坐标，单位 pt。
     * @return 如果给定的坐标在下边距内则返回 {@code true}，否则返回 {@code false}。
     */
    public boolean bottomMarginContains(double y) {
        return bottomMarginContains(y, 0.5f);
    }

    @Override
    public String toString() {
        return "PaperParameter{" +
                "Size(pt): (" + widthInPt + "x" + heightInPt + "), " +
                "Margin(pt): (" + leftMarginInPt +
                ", " + topMarginInPt +
                ", " + rightMarginInPt +
                ", " + bottomMarginInPt + "); " +
                "Size(mm): (" + widthInMillis + "x" + heightInMillis + "), " +
                "Margin(mm): (" + leftMarginInMillis +
                ", " + topMarginInMillis +
                ", " + rightMarginInMillis +
                ", " + bottomMarginInMillis + ")" +
                '}';
    }
}