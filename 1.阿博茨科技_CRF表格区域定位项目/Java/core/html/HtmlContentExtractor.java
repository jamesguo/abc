package com.abcft.pdfextract.core.html;

import com.abcft.annotations.proguard.KeepName;
import com.abcft.pdfextract.core.HtmlExtractor;
import com.abcft.pdfextract.core.chart.ChartExtractor;
import com.abcft.pdfextract.core.content.*;
import com.abcft.pdfextract.core.model.TextElement;
import com.abcft.pdfextract.spi.FileType;
import com.abcft.pdfextract.util.ClosureInt;
import com.google.common.collect.Sets;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;

import java.io.IOException;
import java.io.Writer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Created by dhu on 2017/12/8.
 */
public class HtmlContentExtractor implements HtmlExtractor<ContentExtractParameters, Page, Fulltext, ContentExtractorCallback> {

    @Override
    public int getVersion() {
        return 4;
    }

    private static Logger logger = LogManager.getLogger(ChartExtractor.class);


    private static Set<String> BLOCK_TAGS = Sets.newHashSet("title", "font", "p", "tr", "h1", "h2", "h3", "h4", "h5", "h6");

    @Override
    public Fulltext process(com.abcft.pdfextract.spi.Document<Document> document, ContentExtractParameters parameters, ContentExtractorCallback callback) {
        return processDocument(document, parameters, null, callback);
    }

    private Paragraph parseParagraph(int pageNumber, Element element, ContentExtractParameters parameters) {
        Map<String, String> style = parseStyle(element.attr("style"));
        String text =  (parameters != null && parameters.htmlWholeText) ? element.wholeText() : element.text();
        Element font = element.getElementsByTag("font").first();
        Map<String, String> fontStyle = parseStyle(font != null ? font.attr("style") : "");
        if (StringUtils.isNoneBlank(text)) {
            Paragraph paragraph = new Paragraph(pageNumber, text, 0, 0, 0, 0);
            int textStyle = 0;
            if ("bold".equals(style.get("font-weight")) || "bold".equals(fontStyle.get("font-weight"))) {
                textStyle |= TextElement.TEXT_STYLE_BOLD;
            }
            if ("italic".equals(style.get("font-style")) || "italic".equals(fontStyle.get("font-style"))) {
                textStyle |= TextElement.TEXT_STYLE_ITALIC;
            }
            if ("underline".equals(style.get("text-decoration")) || "underline".equals(fontStyle.get("text-decoration"))) {
                textStyle |= TextElement.TEXT_STYLE_UNDERLINE;
            }
            if ("line-through".equals(style.get("text-decoration")) || "line-through".equals(fontStyle.get("text-decoration"))) {
                textStyle |= TextElement.TEXT_STYLE_LINE_THROUGH;
            }
            paragraph.setTextStyle(textStyle);
            String fontSize = style.get("font-size");
            if (StringUtils.isNoneBlank(fontSize)) {
                if ("xx-small".equals(fontSize) || "x-small".equals(fontSize)) {
                    paragraph.setFontSize(8);
                } else if ("small".equals(fontSize) || "smaller".equals(fontSize)) {
                    paragraph.setFontSize(9);
                } else if ("medium".equals(fontSize) || "larger".equals(fontSize)) {
                    paragraph.setFontSize(11);
                } else if ("large".equals(fontSize)) {
                    paragraph.setFontSize(12);
                } else if ("x-large".equals(fontSize)) {
                    paragraph.setFontSize(16);
                } else if ("xx-large".equals(fontSize)) {
                    paragraph.setFontSize(21);
                } else if (fontSize.endsWith("pt")) {
                    paragraph.setFontSize(Float.parseFloat(fontSize.substring(0, fontSize.length()-2)));
                } else if (fontSize.endsWith("px")) {
                    paragraph.setFontSize(Float.parseFloat(fontSize.substring(0, fontSize.length()-2)) / 1.5f);
                } else {
                    paragraph.setFontSize(11);
                }
            }
            return paragraph;
        } else {
            return null;
        }
    }

    private static Map<String, String> parseStyle(String style) {
        Map<String, String> attrs = new HashMap<>();
        if (StringUtils.isBlank(style)) {
            return attrs;
        }
        String[] array = style.split(";");
        for (String s : array) {
            if (StringUtils.isBlank(s)) {
                continue;
            }
            String[] keyValue = s.split(":");
            if (keyValue.length == 2) {
                attrs.put(keyValue[0].trim(), keyValue[1].trim());
            }
        }
        return attrs;
    }

    private Fulltext extractFulltext(com.abcft.pdfextract.spi.Document<Document> htmlDocument, ContentExtractParameters parameters, Writer outputStream, ContentExtractorCallback callback) {
        Fulltext fulltext = new Fulltext(htmlDocument);
        Document document = htmlDocument.getDocument();
        final ClosureInt pageNumber = new ClosureInt(1);
        Page page = new Page(pageNumber.get());
        Element body = document.select("body").first();
        StringBuilder builder = new StringBuilder();
        Node node = body;
        int depth = 0;
        boolean goToChild = true;
        while(node != null) {
            if (depth > 0 && node instanceof Element) {
                Element element = (Element)node;
                Map<String, String> style = parseStyle(element.attr("style"));
                String nodeName = element.nodeName();
                if (BLOCK_TAGS.contains(nodeName)) {
                    Paragraph paragraph = parseParagraph(pageNumber.get(), element, parameters);
                    if (paragraph != null) {
                        builder.append(paragraph.getText()).append("\n");
                        page.addParagraph(paragraph);
                    }
                    goToChild = false;
                } else {
                    goToChild = true;
                }
                if (StringUtils.equals(style.get("page-break-after"), "always")) {
                    pageNumber.increment();
                    fulltext.addPage(page);
                    page = new Page(pageNumber.get());
                    builder.append("\n\n");
                }
                if (element.hasAttr("data-page")) {
                    int dataPage = Integer.parseInt(element.attr("data-page"));
                    if (dataPage != pageNumber.get()) {
                        fulltext.addPage(page);
                        pageNumber.set(dataPage);
                        page = new Page(pageNumber.get());
                        builder.append("\n\n");
                    }
                }
            }
            if(goToChild && node.childNodeSize() > 0) {
                node = node.childNode(0);
                ++depth;
            } else {
                while(node.nextSibling() == null && depth > 0) {
                    node = node.parentNode();
                    --depth;
                }
                if(node == body) {
                    break;
                }
                node = node.nextSibling();
            }
        }
        fulltext.addPage(page);
        if (callback != null) {
            callback.onItemExtracted(page);
        }
        for (Element meta : document.select("meta, META")) {
            // 移除默认的编码, 后面保存HTML的时候会统一按照utf-8保存
            if ((meta.hasAttr("http-equiv") || meta.hasAttr("HTTP-EQUIV"))
                    && (meta.hasAttr("content") || meta.hasAttr("CONTENT"))) {
                meta.remove();
            }
            if (meta.hasAttr("charset") || meta.hasAttr("CHARSET")) {
                meta.remove();
            }
        }
        // 添加默认的编码utf-8
        Element head = document.head();
        if (head == null) {
            head = document.createElement("head");
            Element html = document.selectFirst("html");
            if (html == null) {
                html = document.createElement("html");
                document.insertChildren(0, html);
            }
            html.insertChildren(0, head);
        }
        head.append("<meta http-equiv=\"Content-Type\" content=\"text/html; charset=utf-8\">");
        fulltext.setHtml(document.outerHtml());
        fulltext.setFulltext(builder.toString());
        if (outputStream != null) {
            try {
                outputStream.write(fulltext.getText());
            } catch (IOException e) {
                logger.warn("write fulltext failed", e);
            }
        }
        return fulltext;
    }

    private Fulltext processDocument(com.abcft.pdfextract.spi.Document<Document> document, ContentExtractParameters parameters, Writer outputStream, ContentExtractorCallback callback) {
        try {
            if (callback != null) {
                callback.onStart(document);
            }
            Fulltext fulltext = extractFulltext(document, parameters, outputStream, callback);
            if (callback != null) {
                callback.onFinished(fulltext);
            }
            return fulltext;
        } catch (Exception e) {
            if (callback != null) {
                callback.onFatalError(e);
            }
        }
        return null;
    }

}
