package com.abcft.pdfextract.core.content;

import com.abcft.pdfextract.core.ContentGroupRenderer;
import com.abcft.pdfextract.core.ExtractContext;
import com.abcft.pdfextract.core.model.ContentGroup;
import com.abcft.pdfextract.util.FloatUtils;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.apache.commons.lang3.StringUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.action.PDAction;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;

import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.io.IOException;
import java.util.Stack;

/**
 * Represents an outline item
 * Created by chzhong on 17-6-30.
 */
public class OutlineItem extends OutlineNode {


    private static PDDestination getDestination(PDOutlineItem item) {
        PDDestination dest = null;
        try {
            dest = item.getDestination();
            if (null == dest) {
                PDAction action = item.getAction();
                if (action instanceof PDActionGoTo) {
                    dest = ((PDActionGoTo)action).getDestination();
                }
            }
        } catch (IOException e) {
            // Ignore
        }
        return dest;
    }

    private static boolean isDestinationEqual(PDPageXYZDestination d1, PDPageXYZDestination d2) {
        return d1 == d2
                || d1.getPage().equals(d2.getPage())
                && FloatUtils.feq(d1.getTop(), d2.getTop(), 10.0f);
    }

    private int color;
    private String text;
    private String key;
    private Paragraph paragraph;
    private PDPageXYZDestination dest;
    private int destPageIndex = -1;
    private Point2D.Float destPoint = null;


    OutlineItem(String text) {
    }

    OutlineItem(Paragraph paragraph) {
        this.paragraph = paragraph;
    }

    private static int getDestinationPageIndex(PDPageDestination destination, PDDocument document) {
        int pageIndex = destination.getPageNumber() - 1;
        if (pageIndex >= 0) {
            return pageIndex;
        }
        PDPage page = destination.getPage();
        if (page != null) {
            return document.getPages().indexOf(page);
        } else {
            return -1;
        }
    }

    public OutlineItem(PDOutlineItem item, PDDocument document, ExtractContext context) {
        PDDestination d = getDestination(item);
        if (d instanceof PDPageDestination) {
            destPageIndex = getDestinationPageIndex((PDPageDestination) d, document);
            if (destPageIndex >= 0 && d instanceof PDPageXYZDestination) {
                PDPage page = document.getPage(destPageIndex);
                dest = (PDPageXYZDestination) d;
                // getZoom中会取Index为4的obj
                float scale = .0f;
                if (dest.getCOSObject().size() > 4) {
                    scale = ((PDPageXYZDestination) d).getZoom();
                }
                if (scale <= .0f) {
                    scale = 1.0f;
                }
                AffineTransform transform = ContentGroupRenderer.buildPageTransform(page ,scale);
                destPoint = new Point2D.Float(dest.getLeft(), dest.getTop());
                // 转换成display坐标系
                transform.transform(destPoint, destPoint);
            } else {
                dest = new PDPageXYZDestination();
                dest.setPageNumber(destPageIndex);
                dest.setLeft(0);
                if (d instanceof PDPageXYZDestination) {
                    dest.setTop(((PDPageXYZDestination) d).getTop());
                } else {
                    dest.setTop(0);
                }
                dest.setZoom(0);
                destPoint = new Point2D.Float(0, 0);
            }
        } else {
            this.dest = null;
            this.destPageIndex = -1;
            destPoint = new Point2D.Float(0, 0);
        }
        this.text = item.getTitle();
        try {
            this.color = 0xFF000000 | item.getTextColor().toRGB();
            // TODO Define where it links to.
        } catch (IOException e) {
            // Ignore.
        }
    }

    boolean match(String title) {
        return StringUtils.equals(text, title);
    }


    boolean matchDestination(PDPageXYZDestination d) {
        return this.dest != null
                && isDestinationEqual(this.dest, d);
    }

    private void buildKey() {
        StringBuilder outlineKey = new StringBuilder("outline-");
        Stack<String> levelKeys = new Stack<>();
        OutlineNode item = this;
        while (item != null) {
            levelKeys.push(String.valueOf(item.number));
            levelKeys.push(".");
            item = item.parent;
            if (item instanceof Outline) {
                break;
            }
        }
        levelKeys.pop();
        while(!levelKeys.empty()) {
            outlineKey.append(levelKeys.pop());
        }
        this.key = outlineKey.toString();
    }

    int getDestPageIndex() {
        return destPageIndex;
    }

    PDPageXYZDestination getDest() {
        return dest;
    }

    public Point2D.Float getDestPoint() {
        return destPoint;
    }

    public String getKey() {
        if (null == key) {
            buildKey();
        }
        return key;
    }

    @Override
    String toHTML() {
        StringBuilder html = new StringBuilder();
        String anchor;
        if (destPageIndex >= 0) {
            anchor = String.format("<a href='#%1$s' title='%2$s' data-dstPageIndex='%3$d' data-dstX='%4$d' data-dstY='%5$d'>%2$s</a>",
                    getKey(), text, destPageIndex,
                    (int) destPoint.getX(),
                    (int) destPoint.getY());
        } else {
            anchor = String.format("<a href='#%1$s' title='%2$s'>%2$s</a>",
                    getKey(), text);
        }
        html.append(anchor);
        if (firstChild != null) {
            html = super.toHTML(html);
        }
        return html.toString();
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public int getColor() {
        return color;
    }

    void addChild(String text) {
        addChild(new OutlineItem(text));
    }

    public Paragraph getParagraph() {
        return this.paragraph;
    }

    public void setParagraph(Paragraph paragraph) {
        this.paragraph = paragraph;
    }

    @Override
    public String toString() {
        return text;
    }

    @Override
    public JsonObject toDocument(boolean detail) {
        JsonObject bson = new JsonObject();
        JsonArray children = getChildrenDocument();
        bson.add("items", children);
        bson.addProperty("title", text);
        if (paragraph != null) {
            bson.addProperty("pageIndex", paragraph.getPageNumber());
            bson.addProperty("pid", paragraph.getPid());
        }
        if (dest != null) {
            bson.addProperty("dstPageIndex", destPageIndex);
            bson.addProperty("dstPageX", dest.getLeft());
            bson.addProperty("dstPageY", dest.getTop());
        }
        return bson;
    }
}
