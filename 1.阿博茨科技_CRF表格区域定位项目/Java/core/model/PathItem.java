package com.abcft.pdfextract.core.model;

import com.abcft.pdfextract.core.util.GraphicsUtil;
import org.apache.pdfbox.pdmodel.graphics.PDLineDashPattern;
import org.apache.pdfbox.pdmodel.graphics.state.PDGraphicsState;

import java.awt.*;
import java.awt.geom.GeneralPath;
import java.awt.geom.Rectangle2D;
import java.util.List;

public class PathItem extends ContentItem<GeneralPath> {

    private final boolean isFill;
    private final boolean dashed;
    private Color color;
    private float lineWidth;
    private PDLineDashPattern lineDashPattern;
    private List<PathInfo> pathInfos;
    private String shadingName;
    private Rectangle2D bounds;

    PathItem(GeneralPath item, Rectangle2D bounds, PDGraphicsState graphicsState, boolean isFill, Color color) {
        this(item, bounds, isFill, color, GraphicsUtil.getTransformedLineWidth(graphicsState), graphicsState.getLineDashPattern());
    }

    public PathItem(GeneralPath item, Rectangle2D bounds, boolean isFill, Color color, float lineWidth,
             PDLineDashPattern lineDashPattern) {
        super((GeneralPath) item.clone());
        this.bounds = bounds;
        this.color = color;
        this.isFill = isFill;
        this.lineWidth = lineWidth;
        this.lineDashPattern = lineDashPattern;
        this.dashed = (0 == lineDashPattern.getPhase() && 0 == lineDashPattern.getDashArray().length);
    }

    public Color getColor() {
        return color;
    }

    public Color getRGBColor() {
        return color;
    }

    public void setRGBColor(Color c) {
        color = c;
    }

    public boolean isFill() {
        return isFill;
    }

    public boolean isDashed() {
        return dashed;
    }

    public float getLineWidth() {
        return lineWidth;
    }

    public Rectangle2D getBounds() {
        return bounds;
    }

    public PDLineDashPattern getLineDashPattern() {
        return lineDashPattern;
    }

    @Override
    public String toString() {
        Rectangle2D bounds = getItem().getBounds2D();
        return String.format("fill: %s, x: %.2f, y: %.2f, w: %.2f, h: %.2f", isFill,
                bounds.getX(), bounds.getY(), bounds.getWidth(), bounds.getHeight());
    }

    public void setPathInfos(List<PathInfo> pathInfos) {
        this.pathInfos = pathInfos;
    }

    public List<PathInfo> getPathInfos() {
        if (null == pathInfos) {
            pathInfos = PathInfoRecorder.replay(getItem(), null);
        }
        return pathInfos;
    }

    public void setShadingName(String shadingName) {
        this.shadingName = shadingName;
    }

    public String getShadingName() {
        return shadingName;
    }
}
