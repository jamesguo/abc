package com.abcft.pdfextract.core.table;

import com.abcft.pdfextract.core.model.Rectangle;

import java.util.ArrayList;
import java.util.List;

// TODO this class seems superfluous - get rid of it

/**
 * Represents a text line.
 */
@SuppressWarnings("serial")
@Deprecated
public class TabularLine extends Rectangle {

    List<TabularTextChunk> textChunks = new ArrayList<TabularTextChunk>();
    public static final Character[] WHITE_SPACE_CHARS = { ' ', '\t', '\r', '\n', '\f' };
    

    public List<TabularTextChunk> getTextElements() {
        return textChunks;
    }

    public void setTextElements(List<TabularTextChunk> textChunks) {
        this.textChunks = textChunks;
    }

    public void addTextChunk(int i, TabularTextChunk textChunk) {
        if (i < 0) {
            throw new IllegalArgumentException("i can't be less than 0");
        }

        int s = this.textChunks.size(); 
        if (s < i + 1) {
            for (; s <= i; s++) {
                this.textChunks.add(null);
            }
            this.textChunks.set(i, textChunk);
        }
        else {
            this.textChunks.set(i, this.textChunks.get(i).merge(textChunk));
        }
        this.merge(textChunk);
    }

    public void addTextChunk(TabularTextChunk textChunk) {
        if (this.textChunks.isEmpty()) {
            this.setRect(textChunk);
        }
        else {
            this.merge(textChunk);
        }
        this.textChunks.add(textChunk);
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        String s = super.toString();
        Class<?> clazz = getClass();
        s = s.replace(clazz.getName(), clazz.getSimpleName());
        sb.append(s.substring(0, s.length() - 1));
        sb.append(",chunks=");
        for (TabularTextChunk te: this.textChunks) {
            sb.append("'" + te.getText() + "', ");
        }
        sb.append(']');
        return sb.toString();
    }

    static TabularLine removeRepeatedCharacters(TabularLine line, Character c, int minRunLength) {

        TabularLine rv = new TabularLine();
        
        for(TabularTextChunk t: line.getTextElements()) {
            for (TabularTextChunk r: t.squeeze(c, minRunLength)) {
                rv.addTextChunk(r);
            }
        }
        
        return rv;
    }
}
