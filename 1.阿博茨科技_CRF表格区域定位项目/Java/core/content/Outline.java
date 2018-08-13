package com.abcft.pdfextract.core.content;

import com.abcft.pdfextract.core.ExtractedItem;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination;

/**
 * Represents the outline of the document.
 *
 * Created by chzhong on 17-6-30.
 */
public class Outline extends OutlineNode implements ExtractedItem {


    private final PDDocument document;
    private final boolean builtin;

    Outline(PDDocument document, boolean builtin) {
        this.document = document;
        this.builtin = builtin;
    }

    OutlineItem findOutlineItem(Paragraph paragraph) {
        int pageNumber = paragraph.getPageNumber();
        PDPage page = document.getPage(pageNumber - 1);
        double y = paragraph.y2;
        PDPageXYZDestination dest = new PDPageXYZDestination();
        dest.setPage(page);
        dest.setLeft(0);
        dest.setTop((int) y);
        dest.setZoom(-1f);
        return find(dest);
    }

    public boolean isBuiltin() {
        return builtin;
    }

    String toHTML() {
        StringBuilder html = new StringBuilder();
        html.append("<div id='outline' style='background-color: white; overflow-y: auto; float: right; position: fixed; min-width: 200px; max-width: 400px; max-height: 600px; top:  20px; right: 20px;  border: black 1px solid; padding: 8px;'>");
        html.append("<p>Outline<span style='float: right; margin-left: 40px;'><a href='#' onclick=\"(function(){var  e=document.getElementById('outline-btn'),n=document.getElementById('outline-list');\n" +
                "return (n.style.display!=='none')?(n.style.display='none',e.innerText='Show'):(n.style.display='block',e.innerText='Hide') && false;})()\" id=\"outline-btn\">Hide</a></span></p>");
        html.append("<div id=\"outline-list\">");
        html = super.toHTML(html);
        html.append("</div></div><div style='clear: both;'></div>");
        return html.toString();
    }

    @Override
    public JsonObject toDocument(boolean detail) {
        JsonObject bson = new JsonObject();
        JsonArray children = getChildrenDocument();
        bson.add("items", children);
        bson.addProperty("built-in", builtin);
        return bson;
    }
}
