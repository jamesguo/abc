package com.abcft.pdfextract.core.model;

import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;

@SuppressWarnings("serial")
public abstract class AreaTextGroup<E extends TextContainer> extends Rectangle2D.Float implements TextGroup<E> {

    private List<Rectangle2D> areas = new ArrayList<>();
    public AreaTextGroup() {
    }

    public AreaTextGroup(AreaTextGroup group) {
        areas = new ArrayList<>(group.areas);
    }

    public AreaTextGroup(Shape shape) {
        addArea(shape.getBounds2D());
    }

    public void addArea(Rectangle2D rectangle2D) {
        if (areas.isEmpty()) {
            setRect(rectangle2D);
        } else {
            union(this, rectangle2D, this);
        }
        areas.add(rectangle2D.getBounds2D());
    }

    public List<Rectangle2D> getAreas() {
        return areas;
    }

    public String toString() {
        StringBuilder sb = new StringBuilder();
        Class<?> clazz = getClass();
        String text = getText();
        Rectangle2D bounds = getBounds2D();
        sb.append(String.format("%s[l=%.2f,r=%.2f,t=%.2f,b=%.2f; w=%.2f,h=%.2f; text=%s]", clazz.getSimpleName(),
                bounds.getMinX(), bounds.getMaxY(), bounds.getMinY(), bounds.getMaxY(), bounds.getWidth(), bounds.getHeight(),
                text == null ? "<null>" : "\"" + text + "\""));
        return sb.toString();
    }

    @Override
    public boolean intersects(Rectangle2D area) {
        for (Rectangle2D rectangle2D : areas) {
            if (rectangle2D.intersects(area)) {
                return true;
            }
        }
        return false;
    }
}
