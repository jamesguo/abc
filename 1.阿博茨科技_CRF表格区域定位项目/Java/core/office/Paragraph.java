package com.abcft.pdfextract.core.office;

import com.abcft.pdfextract.core.model.TextBlock;
import com.abcft.pdfextract.core.model.TextChunk;
import com.abcft.pdfextract.core.model.TextElement;

import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class Paragraph {

    static class Text {
        private final String text;
        private final FontInfo fontInfo;

        public Text(String text, FontInfo fontInfo) {
            this.text = text;
            this.fontInfo = fontInfo;
        }

        public Text(String text, String fontFamily, float fontSize) {
            this(text, new FontInfo(fontFamily, fontSize));
        }

        public Text(String text) {
            this(text, new FontInfo("", 10));
        }

        @Override
        public String toString() {
            return String.format("[Text %s]", text);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Text text1 = (Text) o;
            return Objects.equals(text, text1.text) &&
                Objects.equals(fontInfo, text1.fontInfo);
        }

        @Override
        public int hashCode() {

            return Objects.hash(text, fontInfo);
        }

        public String getText() {
            return text;
        }

        public FontInfo getFontInfo() {
            return fontInfo;
        }
    }

    private final int pageIndex;
    private final Rectangle2D area;
    private final List<Text> texts;
    private int itemIndex;

    public Paragraph(int pageIndex, Rectangle2D area) {
        this.pageIndex = pageIndex;
        this.area = area;
        this.texts = new ArrayList<>();
    }

    public void addText(Text text) {
        this.texts.add(text);
    }

    public void addText(List<Text> text) {
        this.texts.addAll(text);
    }

    public String getParagraphText() {
        StringBuilder sb = new StringBuilder();
        for (Text text : this.texts) {
            sb.append(text.getText());
        }
        return sb.toString();
    }

    public static com.abcft.pdfextract.core.content.Paragraph toParagraph(int pageNumber, Paragraph paragraph) {
        float x = (float) paragraph.getArea().getX();
        float y = (float) paragraph.getArea().getY();
        float w = (float) paragraph.getArea().getWidth();
        float h = (float) paragraph.getArea().getHeight();
        com.abcft.pdfextract.core.content.Paragraph p =
                new com.abcft.pdfextract.core.content.Paragraph(pageNumber, paragraph.getParagraphText(), x, y, w, h);
        List<TextChunk> textChunks = new ArrayList<>();
        paragraph.getTexts().forEach(t -> {
            Rectangle2D.Float bounds = new Rectangle2D.Float(x, y, w, h);
            FontInfo info = t.getFontInfo();
            TextElement textElement = new TextElement(bounds, bounds,
                    bounds,
                    1,
                    t.getText(),
                    new int[]{32},
                    info.getFontFamily(),
                    info.getFontSize(),
                    0,
                    info.getFontColor(),
                    info.getFontStyle()
            );
            textElement.setGroupIndex(1);
            TextChunk textChunk = new TextChunk(textElement);
            textChunks.add(textChunk);
        });
        TextBlock textBlock = new TextBlock(textChunks);
        p.setTextBlock(textBlock);
        if (!paragraph.getTexts().isEmpty()) {
            FontInfo fontInfo = paragraph.getTexts().get(0).getFontInfo();
            p.setTextStyle(fontInfo.getFontStyle());
        }
        return p;
    }

    @Override
    public String toString() {
        return String.format("[Paragraph pageIndex=%d, with %d texts]", pageIndex, texts.size());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Paragraph paragraph = (Paragraph) o;
        return pageIndex == paragraph.pageIndex &&
                Objects.equals(area, paragraph.area) &&
                Objects.equals(texts, paragraph.texts);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pageIndex, area, texts);
    }

    public int getPageIndex() {
        return pageIndex;
    }

    public Rectangle2D getArea() {
        return area;
    }

    public List<Text> getTexts() {
        return texts;
    }

    public int getItemIndex() {
        return itemIndex;
    }

    public void setItemIndex(int itemIndex) {
        this.itemIndex = itemIndex;
    }
}
