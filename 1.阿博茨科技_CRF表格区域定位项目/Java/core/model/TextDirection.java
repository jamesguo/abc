package com.abcft.pdfextract.core.model;

/**
 * TODO 这个类无法区别 文字正上方向（文字旋转）和书写方向，可能需要调整。
 *
 *
 * Created by dhu on 2017/11/9.
 */
public enum TextDirection {
    /**
     * 方向未知
     */
    UNKNOWN,
    /**
     * 正方像向上，由左像向右书写
     */
    LTR,
    /**
     * 水平向左（？）
     */
    RTL,
    /**
     * 正方向向左 竖直向上书写(-90°）
     */
    VERTICAL_UP,
    /**
     * 正方向向右 竖直向下书写(90°）
     */
    VERTICAL_DOWN,
    /**
     * 表示旋转的文字（顺时针旋转的角度）, 如 -45°
     */
    ROTATED,
}
