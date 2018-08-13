package com.abcft.pdfextract.core.chart.model;

import java.awt.*;

/**
 * Created by dhu on 2017/5/10.
 */
public class PathInfo {

    /**
     * Chart内部单个几何元素类型定义
     */
    public enum PathType {
        /**
         * 折线
         */
        LINE(1),
        /**
         * 曲线
         */
        CURVE(2),
        /**
         * 虚线
         */
        DASH_LINE(3),
        /**
         * 长水平线
         */
        HORIZON_LONG_LINE(4),
        /**
         * 竖直的柱状
         */
        BAR(30),
        /**
         * 水平的柱状
         */
        COLUMNAR(31),
        /**
         * 面积
         */
        AREA(50),
        /**
         * 扇形
         */
        ARC(60),
        /**
         * 简单的直线, 只有两个点
         */
        SINGLE_LINE(70),
        /**
         * 倾斜的文字
         */
        ROTATED_TEXT(80),
        /**
         * 折线顶点绘制图形对象 (矩形, 三角形, 菱形等各种小面积图形元素)
         */
        LINE_NODE_GRAPHIC_OBJ(100),
        /**
         * 未知
         */
        UNKNOWN(-1);

        private final int value;

        private PathType(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }
    } // end enum

    /**
     * Chart内部图形对象组类型定义
     */
    public enum PathGroupType {
        /**
         * 坐标轴或轴标
         */
        AXIS(1),
        /**
         * 图例
         */
        LEGEND(2),
        /**
         * 折线
         */
        LINE(3),
        /**
         * 竖直的柱状
         */
        COLUMNAR_VERTICAL(4),
        /**
         * 水平的柱状
         */
        COLUMNAR_HORIZON(5),
        /**
         * OCR文字
         */
        TEXT(6),
        /**
         * 扇形
         */
        ARC(7),
        /**
         * 面积
         */
        AREA(8),
        /**
         * 其他 (现阶段不支持图形和辅助图形　后续需要细分)
         */
        OTHER(9),
        /**
         * 未知
         */
        UNKNOWN(0);

        private final int value;

        private PathGroupType(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }
    } // end enum

    private PathType type;
    private Color color;
    public PathInfo(PathType type) {
        this.type = type;
    }

    public PathType getType() {
        return type;
    }

    public void setColor(Color color) {
        this.color = color;
    }

    public Color getColor() {
        return color;
    }
}



