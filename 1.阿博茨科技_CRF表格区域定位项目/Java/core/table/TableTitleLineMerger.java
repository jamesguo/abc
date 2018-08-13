package com.abcft.pdfextract.core.table;

import com.abcft.pdfextract.core.content.TextChunkMerger;
import com.abcft.pdfextract.core.content.TextUtils;
import com.abcft.pdfextract.core.model.TextBlock;
import com.abcft.pdfextract.core.model.TextChunk;
import com.abcft.pdfextract.core.model.TextElement;
import com.abcft.pdfextract.util.FloatUtils;

import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 表格合并策略。
 *
 * Created by chzhong on 2017/11/14.
 */
@Deprecated
public final class TableTitleLineMerger {

    private static final Pattern STRUCT_TYPE_TITLE = Pattern.compile("[Hh][1-9]");

    private static final TextChunkMerger TEXT_CHUNK_MERGER = new TextChunkMerger() {
        @Override
        protected boolean canMerge(TextChunk textChunk1, TextChunk textChunk2) {
            return super.canMerge(textChunk1, textChunk2) && textChunk1.hasSameStyle(textChunk2);
        }
    };

    @SuppressWarnings("Duplicates")
    public static List<TextBlock> merge(List<TextChunk> textChunks, Rectangle2D area) {
        List<TextBlock> merged = new ArrayList<>();
        List<TextChunk> mergedChunks = TEXT_CHUNK_MERGER.merge(textChunks);
        TextBlock prevText = null;
        for (TextChunk textChunk : mergedChunks) {
            if (prevText != null && canMerge(prevText, textChunk, area)) {
                prevText.addElement(textChunk);
            } else {
                prevText = new TextBlock(textChunk);
                merged.add(prevText);
            }
        }
        return merged;
    }

    @SuppressWarnings("Duplicates")
    private static boolean canMerge(TextBlock prevText, TextChunk nextText, Rectangle2D area) {
        TextChunk lastTextChunk = prevText.getLastTextChunk();

        boolean inSameStructElem = nextText.inSameNode(lastTextChunk);

        // 检查文字方向
        if (lastTextChunk.getDirection() != nextText.getDirection()) {
            return false;
        }
        if (lastTextChunk.isPageFooter() != nextText.isPageFooter()) {
            return false;
        }
        if (lastTextChunk.isPageHeader() != nextText.isPageHeader()) {
            return false;
        }
        // 检查字号是否相差过大
        if (Math.abs(lastTextChunk.getFontSize() - nextText.getFontSize()) > 2) {
            return false;
        }

        String text = prevText.getText();
        String text2 = nextText.getText();

        boolean inOneLine = prevText.inOneLine(nextText);
        if (inOneLine) {
            return true;
        }
        if (prevText.hasColumn() && !inSameStructElem) {
            return false;
        }
        if (TextUtils.isParagraphEnd(text) || TableTitle.hasTableSuffix(text)){
            // 检查是否在最右边, 这样的话就有可能是段落中间的符号而不是段落末尾的了
            if (lastTextChunk.getMaxX() < area.getMaxX() - lastTextChunk.getWidthOfSpace() * 3) {
                return false;
            }
        }

        // 如果是目录项的话不合并
        if (TextUtils.isCatalog(text) || TextUtils.isCatalog(text2)) {
            return false;
        }

        // 如果前面是项目编号, 检查后面一段的对齐, 如果是下面这种对齐方式的话, 可以合并
        //•  数据来源: Trustdata自建的日活跃用户超过5000万(月活跃用户超过1.4亿)
        //   的样本集
        if (TextUtils.isBulletStart(text)) {
            // 判断项目编号那一行是否过短, 如果很短的话单独为一个段落
            if (prevText.getBounds2D().getMaxX() + lastTextChunk.getWidthOfSpace() * 8 < area.getMaxX()) {
                return false;
            }
            TextElement bulletTextElement = TextUtils.getBulletTextElement(prevText.getFirstTextChunk());
            if (bulletTextElement != null
                    && FloatUtils.feq(bulletTextElement.getMinX(), nextText.getMinX(), 5)) {
                return true;
            }
            // 如果和项目编号对齐, 又不是项目编号, 则可以合并
            if (!TextUtils.isBulletStart(text2)
                    && FloatUtils.feq(nextText.getMinX(), prevText.getFirstTextChunk().getMinX(), nextText.getWidthOfSpace() * 2)) {
                return true;
            }
            // 如果下一行向左缩进了, 则可以合并
            if (nextText.getMinX() + nextText.getWidthOfSpace() * 2 <= prevText.getFirstTextChunk().getMinX()) {
                return true;
            }
        }

        // 下面一行是项目编号开始, 则上一段落结束
        if (TextUtils.isBulletStart(text2)) {
            return false;
        }

        // 计算行间距
        double lineSpacing = nextText.getMinY() - lastTextChunk.getMaxY();
        double lineHeight = nextText.getHeight();
        boolean linesIsNear = lineSpacing > 0 && lineSpacing < lineHeight * 1.2f;
        boolean sameStyle = nextText.hasSameStyle(lastTextChunk);
        float avgCharWidth = nextText.getAvgCharWidth();

        // 如果行间距很近, 而且左对齐, 样式一样
        if (linesIsNear && sameStyle
                && FloatUtils.feq(nextText.getLeft(), prevText.getLeft(), 3)
                && FloatUtils.fgte(lastTextChunk.getWidth(), nextText.getWidth(), 8)) {
            return true;
        }

        // 如果行间距很近, 而且下一行向左缩进了, 则可以合并
        if (linesIsNear
                && FloatUtils.fgte(prevText.getFirstTextChunk().getLeft() - nextText.getLeft(), avgCharWidth * 2, 1f)
                && FloatUtils.fgte(prevText.getLastTextChunk().getRight() - nextText.getRight(), -avgCharWidth * 2, 1f)) {
            return true;
        }

        // 如果都是居中对齐，对于标题来说，它们不应该合并
        if (FloatUtils.feq(lastTextChunk.getCenterX(), area.getCenterX(),30)
                && FloatUtils.feq(nextText.getCenterX(), area.getCenterX(),30)
                && linesIsNear) {
            return false;
        }

        // 如果文本离右边界太远, 认为段落结束
        if (lastTextChunk.getMaxX() < area.getMaxX() - area.getWidth() / 8) {
            return false;
        }

        // 如果两行靠得很近的话, 间距可能为负值
        if (lineSpacing < lastTextChunk.getHeight() * -1.2) {
            return false;
        } else  if (lineSpacing >= prevText.getLineSpacing() + 5) {
            return false;
        }

        // 比较样式是否一致
        if (!inOneLine) {
            // 如果文本太短, 则不合并
            double centerX = area.getCenterX();
            double contentWidth = area.getWidth();

            if (lastTextChunk.getMaxX() < centerX - contentWidth / 8) {
                return false;
            }

            if (lastTextChunk.getMinX() > area.getCenterX() + contentWidth / 8) {
                return false;
            }

            // 下一段向右缩进, 新的段落
            if (nextText.getMinX() > area.getMinX() + nextText.getWidthOfSpace() * 2) {
                return false;
            }

            // 左边界相差太大
            if (Math.abs(lastTextChunk.getMinX() - nextText.getMinX()) > lastTextChunk.getWidthOfSpace() * 9) {
                return false;
            }
        }

        return true;
    }

}
