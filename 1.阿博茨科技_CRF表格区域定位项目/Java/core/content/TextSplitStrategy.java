package com.abcft.pdfextract.core.content;

import com.abcft.pdfextract.core.model.TextChunk;
import com.abcft.pdfextract.core.model.TextElement;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;

public enum  TextSplitStrategy {

    BY_STYLE(0) {

        private boolean shouldSplitAt(TextElement textElement) {
            // 拆分补的空格数超过2的文本块
            return textElement.isMocked() && textElement.getText().length() > 2;
        }

        @Override
        public List<TextChunk> split(TextChunk textChunk) {
            List<TextChunk> textChunks = new ArrayList<>();
            TextChunk lastTextChunk = null;
            for (TextElement textElement : textChunk.getElements()) {
                if (textElement.isDeleted()) {
                    continue;
                }
                if (lastTextChunk != null
                        && !shouldSplitAt(textElement)
                        && !shouldSplitAt(lastTextChunk.getLastElement())
                        && lastTextChunk.getLastElement().hasSameStyle(textElement)) {
                    lastTextChunk.addElement(textElement);
                } else {
                    lastTextChunk = new TextChunk(textElement);
                    textChunks.add(lastTextChunk);
                }
            }
            return textChunks;
        }
    },
    BY_SPACE(1) {
        @Override
        public List<TextChunk> split(TextChunk textChunk) {
            List<TextChunk> textChunks = new ArrayList<>();
            TextChunk lastTextChunk = null;
            for (TextElement textElement : textChunk.getElements()) {
                if (textElement.isDeleted()) {
                    continue;
                }
                if (StringUtils.isBlank(textElement.getText())) {
                    lastTextChunk = null;
                    continue;
                }
                if (lastTextChunk != null
                        && lastTextChunk.getLastElement().hasSameStyle(textElement)) {
                    lastTextChunk.addElement(textElement);
                } else {
                    lastTextChunk = new TextChunk(textElement);
                    textChunks.add(lastTextChunk);
                }
            }
            return textChunks;
        }
    },
    BY_ELEMENT(2) {
        @Override
        public List<TextChunk> split(TextChunk textChunk) {
            List<TextChunk> textChunks = new ArrayList<>();
            for (TextElement textElement : textChunk.getElements()) {
                if (textElement.isDeleted() || StringUtils.isBlank(textElement.getText())) {
                    continue;
                }
                textChunks.add(new TextChunk(textElement));
            }
            return textChunks;
        }
    };

    public static final TextSplitStrategy DEFAULT = BY_SPACE;

    private final int level;

    TextSplitStrategy(int level) {
        this.level = level;
    }

    public int getLevel() {
        return level;
    }

    public abstract List<TextChunk> split(TextChunk textChunk);
    public static TextSplitStrategy fromLevel(int level) {
        for (TextSplitStrategy textSplitStrategy : values()) {
            if (textSplitStrategy.level == level) {
                return textSplitStrategy;
            }
        }
        // default
        return DEFAULT;
    }

}
