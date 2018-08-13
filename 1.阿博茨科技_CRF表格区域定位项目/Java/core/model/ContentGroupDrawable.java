package com.abcft.pdfextract.core.model;

import java.awt.geom.Rectangle2D;

/**
 * 表示一个可以绘制的 ContentGroup。
 */
public interface ContentGroupDrawable {

    /**
     * 表示对象的 ContentGroup。
     *
     * @return 对象的 ContentGroup。
     */
    ContentGroup getContentGroup();
    /**
     * 表示对象的显示区域。
     *
     * @return 对象的显示区域。
     */
    Rectangle2D getDrawArea();
}
