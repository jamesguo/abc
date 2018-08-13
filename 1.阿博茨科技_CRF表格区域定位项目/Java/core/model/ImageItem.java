package com.abcft.pdfextract.core.model;

import org.apache.pdfbox.pdmodel.graphics.image.PDImage;

import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;

public class ImageItem extends ContentItem<PDImage> {

    private final Rectangle2D bounds;
    private final AffineTransform imageTransform;

    ImageItem(PDImage item, Rectangle2D bounds, AffineTransform imageTransform) {
        super(item);
        this.bounds = bounds;
        this.imageTransform = imageTransform;
    }

    public Rectangle2D getBounds() {
        return (Rectangle2D) bounds.clone();
    }

    public AffineTransform getImageTransform() {
        return imageTransform;
    }

}
