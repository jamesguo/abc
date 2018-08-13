
package com.abcft.pdfextract.core.content;

import com.abcft.pdfextract.core.model.*;
import com.abcft.pdfextract.core.table.ContentGroupPage;
import com.abcft.pdfextract.core.util.TrainDataWriter;
import com.abcft.pdfextract.util.FloatUtils;
import org.apache.commons.lang3.StringUtils;
import org.tensorflow.SavedModelBundle;
import org.tensorflow.Session;
import org.tensorflow.Tensor;
import org.tensorflow.example.Example;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Created by dhu on 2017/11/14.
 */
public class ParagraphMerger {


    private static final class ParagraphTextBlock {

        // 当前正在处理的段落，左边线位置，右边线位置
        float prevParagraphLeft = -1;
        float prevParagraphRight = -1;
        TextBlock textBlock;

        ParagraphTextBlock(TextChunk textChunk) {
            this.textBlock = new TextBlock(textChunk);
        }

        ParagraphTextBlock(TextBlock textBlock) {
            this.textBlock = textBlock;
            this.prevParagraphLeft = (float) textBlock.getLeft();
            this.prevParagraphRight = (float) textBlock.getRight();
        }

        void addElement(TextChunk textChunk) {
            this.textBlock.addElement(textChunk);
        }

        /**
         * 更新当前段落左右边界线位置
         */
        void updateParagraphBorder(TextChunk textChunk) {
            if (prevParagraphLeft != -1) {
                if (prevParagraphLeft > textChunk.getTextLeft()) {
                    prevParagraphLeft = textChunk.getTextLeft();
                }
            }else {
                prevParagraphLeft = textChunk.getTextLeft();
            }

            if (prevParagraphRight != -1){
                if (prevParagraphRight < textChunk.getTextRight()) {
                    prevParagraphRight = textChunk.getTextRight();
                }
            }else {
                prevParagraphRight = textChunk.getTextRight();
            }
        }

    }

    public static List<TextBlock> merge(Page.TextGroup textGroup) {
        return merge(null, textGroup, false, null);
    }

    public static List<TextBlock> merge(Page page, Page.TextGroup textGroup, boolean onlyMergeLine, ContentGroupPage contentGroupPage) {
        List<TextBlock> merged = new ArrayList<>();
        List<TextChunk> textChunks = textGroup.getTextChunks();
        List<TextChunk> lineChunks = mergeToLine(textChunks);
        if (lineChunks.isEmpty()) {
            return merged;
        }
        // onlyMergeLine表示只进行行合并, 不合并多行
        if (onlyMergeLine) {
            lineChunks.sort(Comparator.comparing(TextChunk::getTop));
        }
        if (!onlyMergeLine && page != null && TensorflowManager.INSTANCE.isModelAvailable(TensorflowManager.PARAGRAPH)) {
            return mergeLinesByCRF(page, textGroup, contentGroupPage);
        }
        ParagraphTextBlock prevBlock = null;

        for (int i=0; i < lineChunks.size(); i++){
            TextChunk textChunk = lineChunks.get(i);
            TextChunk thirdLineChunk = textChunk;

            if (i < lineChunks.size() - 1) {
                thirdLineChunk = lineChunks.get(i + 1);
            }

            if (prevBlock != null && !onlyMergeLine && canMerge(prevBlock, textChunk, thirdLineChunk, textGroup)) {
                prevBlock.addElement(textChunk);
            } else {
                prevBlock = new ParagraphTextBlock(textChunk);
                merged.add(prevBlock.textBlock);
            }
        }
        return merged;
    }

    private static void setTextBlockTag(TextBlock prev, TrainDataWriter.LineTag tag) {

        if (!tag.equals(TrainDataWriter.LineTag.PARAGRAPH_START)
                && !tag.equals(TrainDataWriter.LineTag.PARAGRAPH_MIDDLE)
                && !tag.equals(TrainDataWriter.LineTag.PARAGRAPH_END)
                && !tag.equals(TrainDataWriter.LineTag.PARAGRAPH_FOOTER)
                && !tag.equals(TrainDataWriter.LineTag.PARAGRAPH_HEADER)) {
            prev.setLastLineTag(TrainDataWriter.LineTag.SINGLE_LINE_PARAGRAPH);
        } else if (tag.equals(TrainDataWriter.LineTag.PARAGRAPH_START)) {
            prev.setLastLineTag(TrainDataWriter.LineTag.PARAGRAPH_START);
        } else if (tag.equals(TrainDataWriter.LineTag.PARAGRAPH_MIDDLE)) {
            prev.setLastLineTag(TrainDataWriter.LineTag.PARAGRAPH_MIDDLE);
        } else if (tag.equals(TrainDataWriter.LineTag.PARAGRAPH_END)) {
            prev.setLastLineTag(TrainDataWriter.LineTag.PARAGRAPH_END);
        } else if (tag.equals(TrainDataWriter.LineTag.PARAGRAPH_HEADER)) {
            prev.setLastLineTag(TrainDataWriter.LineTag.PARAGRAPH_HEADER);
        } else if (tag.equals(TrainDataWriter.LineTag.PARAGRAPH_FOOTER)) {
            prev.setLastLineTag(TrainDataWriter.LineTag.PARAGRAPH_FOOTER);
        }
    }
    private static List<TextBlock> mergeLinesByCRF(Page page, Page.TextGroup textGroup, ContentGroupPage contentGroupPage) {
//        Example example = TrainDataWriter.buildPageExample(
//                page, lineChunks);
        Example example = TrainDataWriter.buildPageExample(contentGroupPage,
                page, textGroup);
        Tensor exampleTensor = Tensor.create(example.toByteArray());

        SavedModelBundle savedModelBundle = TensorflowManager.INSTANCE.getSavedModelBundle(TensorflowManager.PARAGRAPH);
        Session session = savedModelBundle.session();
        Tensor<?> crfTags = session.runner()
                .feed("serialized_example", exampleTensor)
                .fetch("crf_tags")
                .run()
                .get(0);
        int length = (int) crfTags.shape()[1];
        int[] tags = crfTags.copyTo(new int[1][length])[0];
        List<TrainDataWriter.LineTag> lineTags = Arrays.stream(tags).mapToObj(tag -> TrainDataWriter.LineTag.values()[tag]).collect(Collectors.toList());
        List<TextBlock> merged = new ArrayList<>();
        TextBlock prev = null;
        List<TextChunk> lineChunks = textGroup.getTextChunks();
        TrainDataWriter.LineTag lastTag = lineTags.get(0);
        for (int i = 0; i < lineChunks.size(); i++) {
            TextChunk line = lineChunks.get(i);
            TrainDataWriter.LineTag tag = lineTags.get(i);
            if (prev == null) {
                prev = new TextBlock(line);
                merged.add(prev);
                setTextBlockTag(prev, tag);

            } else {
                if (tag == TrainDataWriter.LineTag.PARAGRAPH_MIDDLE || tag == TrainDataWriter.LineTag.PARAGRAPH_END
                        || lastTag == TrainDataWriter.LineTag.PARAGRAPH_MIDDLE && tag == TrainDataWriter.LineTag.SINGLE_LINE_PARAGRAPH) {
                    prev.addElement(line);
                    if (tag.equals(TrainDataWriter.LineTag.PARAGRAPH_MIDDLE)) {
                        prev.setLastLineTag(TrainDataWriter.LineTag.PARAGRAPH_MIDDLE);
                    } else {
                        prev.setLastLineTag(TrainDataWriter.LineTag.PARAGRAPH_END);
                    }
                } else {
                    prev = new TextBlock(line);
                    merged.add(prev);
                    setTextBlockTag(prev,tag);
                }
            }
            lastTag = tag;
        }
        return merged;
    }

    public static boolean canMerge(Paragraph lastParagraph, Page page1, Paragraph firstParagraph, Page page2) {
        if (null == firstParagraph || null == lastParagraph) {
            return false;
        }
        TextBlock prevBlock = lastParagraph.getTextBlock();
        TextBlock nextBlock = firstParagraph.getTextBlock();

        TextChunk prevTextChunk = prevBlock.getLastTextChunk();
        TextChunk nextTextChunk = new TextChunk(nextBlock.getFirstTextChunk());
        // 尝试校准下一页文本块的 y 坐标
        nextTextChunk.moveTo(nextTextChunk.getLeft(), (float) (prevTextChunk.getBottom() + prevTextChunk.getTextHeight()));

        Page.TextGroup textGroup = page1.findOrCreateGroup(prevTextChunk);

        return canMerge(new ParagraphTextBlock(prevBlock), nextTextChunk, nextTextChunk, textGroup);
    }

    /**
     * 将同一行的TextChunk合并为一个lineChunk
     */
    public static List<TextChunk> mergeToLine(List<TextChunk> textChunks) {
        List<TextChunk> lineChunks = new ArrayList<>();
        TextChunk prevChunk = null;
        for (TextChunk textChunk : textChunks) {
            if (textChunk.isDeleted()) {
                continue;
            }
            if (prevChunk != null
                    && textChunk.getDirection() == prevChunk.getDirection()
                    && textChunk.isHorizontal()
                    && textChunk.inOneLine(prevChunk)) {
                prevChunk.merge(textChunk);
            } else {
//                 PDF里面有很多空白的字符块, 这些字符块出现可能不是连续的
//                if (StringUtils.isNotBlank(textChunk.getText())) {
//                    prevChunk = textChunk;
//                }
//                lineChunks.add(textChunk);
                if (prevChunk != null) {
                    lineChunks.add(prevChunk);
                }
                prevChunk = new TextChunk(textChunk);
            }
        }
        if (prevChunk != null) {
            lineChunks.add(prevChunk);
        }

        return lineChunks;
    }

    private static boolean canMerge(ParagraphTextBlock block, TextChunk nextChunk, TextChunk thirdChunk, Page.TextGroup textGroup) {
        TextBlock prevBlock = block.textBlock;
        TextChunk lastChunk = prevBlock.getLastTextChunk();
        block.updateParagraphBorder(lastChunk);

        String text = prevBlock.getText();    // 当前处理中段落文本
        String text1 = lastChunk.getText();   // 上一行文本
        String text2 = nextChunk.getText();   // 下一行文本

        // 上下行字号差
        double gapFontSize = Math.abs(lastChunk.getMaxFontSize() - nextChunk.getMaxFontSize());

        // 上下行文字左边界差
        float gapLineTextLeft =  Math.abs(lastChunk.getTextLeft() - nextChunk.getTextLeft());

        // 上下行文字右边界差
        float gapLineTextRight = Math.abs(lastChunk.getTextRight() - nextChunk.getTextRight());


        // 检查文字方向
        if (lastChunk.getDirection() != nextChunk.getDirection()) {
            return false;
        }
        if (lastChunk.isPageFooter() != nextChunk.isPageFooter()) {
            return false;
        }
        if (lastChunk.isPageHeader() != nextChunk.isPageHeader()) {
            return false;
        }

        // 检查是否为同一行
        boolean inOneLine = prevBlock.inOneLine(nextChunk);
        if (inOneLine) {
            return true;
        }

        // 检查当前段落是否含有分栏
        if (prevBlock.getColumnCount() >= 2 || TextUtils.getColumnCount(nextChunk.getText()) >= 2) {
            return false;
        }

        // 上下两行字号差过大
        if (gapFontSize > 2.2) {
            // 通常情况下不可合并，特例存在的情况下合并
            if (TextUtils.isCompanyTitle(text1.trim())
                    && FloatUtils.feq(lastChunk.getCenterX(), textGroup.getCenterX(), 30)
                    && FloatUtils.feq(nextChunk.getCenterX(), textGroup.getCenterX(), 30)
                    && nextChunk.getBottom() < 500 ){
                if (TextUtils.isENWord(text2)){
                    return false;
                }
                return true;
            }

            if (lastChunk.getMostCommonFontSize() == nextChunk.getMostCommonFontSize()
                    && (gapLineTextLeft < 6 || lastChunk.getTextLeft() - nextChunk.getTextLeft() > nextChunk.getWidthOfSpace() * 2)
                    && lastChunk.getTextRight() > 500
                    && TextUtils.getCHNNumber(lastChunk) > 25) {
                return true;
            }
            return false;
        }

        // 上下两行字号差为2
        if (Math.abs(gapFontSize-2) < 0.2) {
            // 通常情况下不可合并，特例存在的情况下合并
            if (TextUtils.isCompanyTitle(text1.trim())
                    && FloatUtils.feq(lastChunk.getCenterX(), textGroup.getCenterX(), 30)
                    && FloatUtils.feq(nextChunk.getCenterX(), textGroup.getCenterX(), 30)
                    && nextChunk.getBottom() < 360 ){
                if (text1.length() + text2.length() > 48){
                    return false;
                }
                return true;
            }

            if (lastChunk.getMostCommonFontSize() == nextChunk.getMostCommonFontSize()
                    && (gapLineTextLeft < 6 || lastChunk.getTextLeft() - nextChunk.getTextLeft() > nextChunk.getWidthOfSpace() * 2)
                    && lastChunk.getTextRight() > 500
                    && TextUtils.getCHNNumber(lastChunk) > 35) {
                return true;
            }

            if (TextUtils.isCompanyTitle(text1.trim())
                    && TextUtils.isNoticeTitle(text2.trim())){
                return true;
            }
            return false;
        }

        // 上下两行字号差为1
        if (Math.abs(gapFontSize-1) < 0.2) {
            if ((TextUtils.isCatalogPart(text1) || TextUtils.isBulletStart(text1))
                    && TextUtils.isCatalog(text2)
                    && gapLineTextLeft < 6
                    && gapLineTextRight < 6){

                if (nextChunk.getTextLeft() > 80 && nextChunk.getTextLeft() < 110
                        && text1.length() > 30){
                    return true;
                }

            }

            if (TextUtils.isLongCatalog(text2)
                    && TextUtils.getCHNNumber(lastChunk) > 35){
                if (gapLineTextLeft < 6
                        && gapLineTextRight < 6) {
                    return true;
                }
            }

            if (TextUtils.isCompanyTitle(text1.trim())
                    && FloatUtils.feq(lastChunk.getCenterX(), 300, 30)
                    && FloatUtils.feq(nextChunk.getCenterX(), 300, 30)
                    && nextChunk.getBottom() < 350
                    && !TextUtils.hasComma(text2)){
                return true;
            }

            if (!(lastChunk.isBold()
                    && lastChunk.hasSameStyle(nextChunk)
                    && !TextUtils.hasComma(text1)
                    && !TextUtils.hasComma(text2))){
                if (TextUtils.isENWord(text2)
                        && text2.length() < 30
                        && text1.length() > 35
                        && TextUtils.isENWord(text1.substring(text1.length()-10, text1.length()-1))
                        && TextUtils.getENFont(lastChunk).equals(TextUtils.getENFont(nextChunk))
                        && lastChunk.getTextRight() > 505 && nextChunk.getTextLeft() < 95){
                    return true;
                }
                if (TextUtils.isENWord(text1) && TextUtils.isENWord(text2)
                        && gapLineTextLeft < 6
                        && lastChunk.getTextRight() > 500
                        && nextChunk.getTextLeft() < 95
                        && text1.length() + text2.length() > 150
                        && !TextUtils.isParagraphEnd(text1)){
                    return true;
                }
                return false;
            }

            if (lastChunk.getMaxFontSize() - nextChunk.getMaxFontSize() < 0
                    && text1.length() > 20){
                return false;
            }

            if (FloatUtils.feq(lastChunk.getCenterX(), textGroup.getCenterX(), 30)
                    && FloatUtils.feq(nextChunk.getCenterX(), textGroup.getCenterX(), 30)){
                if (prevBlock.getText().length() + text2.length() > 48){
                    return false;
                }

                return true;
            }
            return false;
        }

        /**
         *  以下处理上下两行字号差为0的情况
         */

        // 上下两行字号差为0
        // if (Math.abs(gapFontSize-0) < 0.2) {    }

        /**
         *上下行文字的字号相同，样式不同
         */

        if (!lastChunk.hasSameStyle(nextChunk)) {
            // 上下行文字的字号相同，样式不同
            if (TextUtils.hasURL(text1) && !TextUtils.isParagraphEnd(text1)){
                if (TextUtils.getCHNFont(lastChunk).equals(TextUtils.getCHNFont(nextChunk))
                        && !lastChunk.isBold()
                        && !nextChunk.isBold()
                        && lastChunk.getTextRight()> 500
                        && nextChunk.getTextLeft() < 100
                        && lastChunk.getTextLeft() - nextChunk.getTextLeft() > nextChunk.getWidthOfSpace() * 3){
                    return true;
                }
            }

            if (TextUtils.isBulletStart(text) && TextUtils.isCatalog(text2)
                    && gapLineTextLeft < 6
                    && gapLineTextRight < 6){
                if (lastChunk.getTextLeft() > 110){
                    return true;
                }

                if (lastChunk.getTextLeft() >100
                        && lastChunk.isBold()){
                    return true;
                }

            }

            if (lastChunk.getMaxFontSize() == nextChunk.getMaxFontSize()
                    && gapLineTextLeft < 6
                    && gapLineTextRight < 6
                    && lastChunk.getTextLeft() < 95 && lastChunk.getTextRight() > 500){
                return true;
            }
            return false;
        }


        /**
         *上下行文字的字号相同，样式相同
         */

        else if ((Math.abs(gapFontSize-0) < 0.2)
                && lastChunk.hasSameStyle(nextChunk)) {

            if (nextChunk.isBold() && !StringUtils.equals(lastChunk.getMostCommonFontName(), nextChunk.getMostCommonFontName())){
                if (TextUtils.isBulletStart(text) && TextUtils.isParagraphKeep(text1)){
                    return true;
                }
                if (gapLineTextLeft < 6
                        && gapLineTextRight < 6
                        && TextUtils.getCHNNumber(lastChunk) > 36){
                    return true;
                }

//                if (text2.length() > 45
//                        && nextChunk.getTextRight() > 500
//                        && !TextUtils.isCatalog(text)){
//                    return true;
//                }
//                return false;
            }

            if (TextUtils.getSpaceGapCount(text) >=3 && TextUtils.getSpaceGapCount(text2) >=3 ){
                if (!TextUtils.hasComma(text)
                        && !TextUtils.hasComma(text2)
                        && lastChunk.getTop() < 150) {
                    return false;
                }
                if (TextUtils.getCHNNumber(lastChunk) > 25 && TextUtils.getCHNNumber(nextChunk) > 25
                        && gapLineTextLeft > nextChunk.getWidthOfSpace() * 3
                        && lastChunk.getTop() > 200){
                    return true;
                }
            }

            if (TextUtils.isParagraphEnd(text)) {
                if (TextUtils.isBulletStart(text2)
                        && nextChunk.getTextRight() < 480
                        && !TextUtils.hasComma(text2)){
                    return false;
                }

                // 检查是否在最右边, 这样的话就有可能是段落中间的符号而不是段落末尾的了
                if (!TextUtils.isBulletStart(text)
                        && ( gapLineTextLeft < 6 || lastChunk.getTextLeft() - nextChunk.getTextLeft() > nextChunk.getWidthOfSpace() * 2)
                        && lastChunk.getTextRight() > 500
                        && nextChunk.getTextLeft() < 95
                        && nextChunk.getTop() - lastChunk.getBottom() < lastChunk.getTextHeight()){
                    if (nextChunk.getTextRight() - lastChunk.getTextRight() > nextChunk.getWidthOfSpace() * 3){
                        if (TextUtils.isSerialNum(text2) && nextChunk.getTextLeft() < 95){
                            return true;
                        }
                        return false;
                    }

                    if (nextChunk.getTextRight() - lastChunk.getTextRight() > nextChunk.getWidthOfSpace()
                            && nextChunk.getTextLeft() - thirdChunk.getTextLeft() > nextChunk.getWidthOfSpace() * 3){
                        return false;
                    }

                    if (TextUtils.isListStart(text1)
                            && TextUtils.isListStart(text2)
                            && gapLineTextLeft < 6
                            && lastChunk.getLastElement().getText().equals(" ")){
                        return false;
                    }

                    if (lastChunk.getTextRight() > 500
                            && lastChunk.getTextLeft() - nextChunk.getTextLeft() > nextChunk.getWidthOfSpace() * 2
                            && TextUtils.isLongENWord(text2)){
                        return true;
                    }

                    return true;
                }

                if (!TextUtils.isBulletStart(text1)
                        && TextUtils.isParagraphEnd(text1)
                        && gapLineTextLeft < 6
                        && lastChunk.getTextRight() > 500
                        && nextChunk.getTextLeft() < 95
                        && nextChunk.getTop() - lastChunk.getBottom() < lastChunk.getTextHeight() * 1.5){
                    if (TextUtils.isBulletStart(text2)
                            && ((nextChunk.getBottom() - lastChunk.getBottom() > thirdChunk.getBottom() - nextChunk.getBottom() + 3)
                                || nextChunk.getTextRight() < 480)
                            && thirdChunk.getBottom() - nextChunk.getBottom() > 10){
                        return false;
                    }
                    return true;
                }


                if (lastChunk.getMaxX() < textGroup.getMaxX() - lastChunk.getWidthOfSpace() * 3
                        && (lastChunk.getTextLeft() - block.prevParagraphLeft > lastChunk.getWidthOfSpace() * 2
                        || !TextUtils.isParagraphEnd(text2))) {
                    return false;
                }

                if (nextChunk.getTextLeft() - lastChunk.getTextLeft() > nextChunk.getWidthOfSpace() * 2
                        && TextUtils.isParagraphEnd(text2) || text2.length() < 10
                        && nextChunk.getTop() - lastChunk.getBottom() > nextChunk.getTextHeight() * 1.5){
                    return false;
                }
            }

            // 如果是目录项的话不合并
            if (TextUtils.isCatalog(text) || TextUtils.isCatalog(text2)) {
                if ((TextUtils.isBulletStart(text) && TextUtils.isCatalog(text))
                        || (TextUtils.isBulletStart(text2) && TextUtils.isCatalog(text2))){
                    return false;
                }

                if (TextUtils.isCatalogPart(text2)
                        && lastChunk.getTextLeft() - nextChunk.getTextLeft() > nextChunk.getWidthOfSpace() * 2
                        && !StringUtils.equals(lastChunk.getMostCommonFontName(), nextChunk.getMostCommonFontName())) {
                    return false;
                }

                if (TextUtils.isBulletStart(text) && TextUtils.isCatalog(text2)
                        && ( gapLineTextLeft < 6 || lastChunk.getTextLeft() - nextChunk.getTextLeft() > nextChunk.getWidthOfSpace() * 2)
                        && gapLineTextRight < 6){
                    if (lastChunk.getTextLeft() > 110){
                        return true;
                    }

                    if (lastChunk.getTextLeft() >100
                            && lastChunk.isBold() && nextChunk.isBold()){
                        return true;
                    }

                }

                if (TextUtils.getCHNNumber(lastChunk) > 32){
                    if (TextUtils.isLongCatalog(text2)){
                        return true;
                    }

                    if (TextUtils.isCatalog(text2)
                            && TextUtils.getCHNNumber(nextChunk) * 4 + TextUtils.getCatalogNumber(nextChunk) > 110){
                        return true;
                    }
                }

                return false;
            }

            // 下面一行是项目编号开始, 则上一段落结束
            if (TextUtils.isBulletStart(text2)
                    && !TextUtils.isMultipleNum(text2)) {
                if (TextUtils.isParagraphKeep(text)
                        && Math.abs(lastChunk.getTextRight() - block.prevParagraphRight) < 6) {
                    return true;
                }
                if (lastChunk.getTextLeft() - nextChunk.getTextLeft() > nextChunk.getWidthOfSpace() * 3
                        && lastChunk.getTextRight() > 500
                        && nextChunk.getTextRight() > 500){
                    if (lastChunk.getTextRight() < block.prevParagraphRight - 6
                            && nextChunk.getTextRight() > block.prevParagraphRight - 6){
                        return false;
                    }
                    if ((TextUtils.hasMultipleSpace(lastChunk) && lastChunk.getTextLeft() > 240)
                            || TextUtils.hasTableText(lastChunk)){
                        return false;
                    }
                    return true;
                }
                return false;
            }

            // 如果下一行含有选择判断符号 "□ 是 √ 否"
            if (TextUtils.hasChoiceSymbol(text2.trim())) {
                return false;
            }

            // 如果上一行含有选择判断符号 "□ 是 √ 否"
            if (TextUtils.hasChoiceSymbol(text1.trim())) {
                return false;
            }

            // 如果上下两行都为 ① ② ③的列表开始，且无段落结束符号，则不合并
            if (TextUtils.isListStart(text)
                    && TextUtils.isListStart(text2)
                    && !TextUtils.isParagraphEnd(text)) {
                return false;
            }

            if (TextUtils.isAddressLine(text) && TextUtils.isAddressLine(text2)
                    && gapLineTextLeft < 6.5){
                return false;
            }

            // 如果上一行，或者下一行为表格文字，则不合并
            if (TextUtils.hasTableText(lastChunk) && TextUtils.hasMultipleSpace(lastChunk)
                    || TextUtils.hasTableText(nextChunk) && TextUtils.hasMultipleSpace(nextChunk)){
                return false;
            }

            // 如果前面是项目编号, 检查后面一段的对齐, 如果是下面这种对齐方式的话, 可以合并
            //?  数据来源: Trustdata自建的日活跃用户超过5000万(月活跃用户超过1.4亿)
            //   的样本集git
            if (TextUtils.isBulletStart(text)) {
                // 判断项目编号那一行是否过短, 如果很短的话单独为一个段落
                if (prevBlock.getBounds2D().getMaxX() + lastChunk.getMaxTextWidth() * 4 < textGroup.getTextRight()) {
                    if (lastChunk.getTextRight() == block.prevParagraphRight
                            && nextChunk.getTextLeft() == block.prevParagraphLeft
                            && !TextUtils.isParagraphEnd(text)
                            && TextUtils.isParagraphEnd(text2)) {
                        if (lastChunk.getTextRight() < 500){
                            return false;
                        }
                        return true;
                    }

                    if (!TextUtils.isParagraphEnd(text1)
                            && lastChunk.getTextRight() > 500
                            && lastChunk.getTextLeft() - nextChunk.getTextLeft() > 6){
                        return true;
                    }

                    if (!TextUtils.isParagraphEnd(text1)
                            && lastChunk.getRight() > 420
                            && lastChunk.getTextLeft() - nextChunk.getTextLeft() > nextChunk.getWidthOfSpace() * 2
                            && TextUtils.isBigNum(text2)){
                        return true;
                    }

                    if (!TextUtils.isBulletStart(text1)
                            && !TextUtils.isParagraphEnd(text1)
                            && lastChunk.getTextRight() > 500
                            && gapLineTextLeft < 6){
                        return true;
                    }

                    return false;
                }
                TextElement bulletTextElement = TextUtils.getBulletTextElement(prevBlock.getFirstTextChunk());

                if (bulletTextElement != null
                        && FloatUtils.feq(bulletTextElement.getMinX(), nextChunk.getMinX(), 5)) {
                    return true;
                }

                if (!TextUtils.isParagraphEnd(text1)
                        && nextChunk.getTextLeft() - lastChunk.getTextLeft() > nextChunk.getWidthOfSpace() * 2
                        && gapLineTextLeft < 30
                        && !TextUtils.isParagraphEnd(text2)
                        && text2.length() < 16){
                    return true;
                }

                // 如果和项目编号对齐, 又不是项目编号, 则可以合并
                if (!TextUtils.isBulletStart(text2)
                        && FloatUtils.feq(nextChunk.getMinX(), prevBlock.getFirstTextChunk().getMinX(), nextChunk.getWidthOfSpace() * 2)) {
                    if (nextChunk.getTextLeft() - textGroup.getTextLeft() > nextChunk.getWidthOfSpace() * 2) {
                        if (gapLineTextLeft < 6
                                && gapLineTextRight < 6
                                && !TextUtils.isParagraphEnd(text)){
                            return true;
                        }

                        if (gapLineTextLeft < 6
                                && !TextUtils.isParagraphEnd(text)
                                && TextUtils.isParagraphEnd(text2) || (nextChunk.getTextLeft() < 90 && nextChunk.getTextRight() < 300) ){
                            return true;
                        }
                        return false;
                    }

                    if (nextChunk.getBottom() - lastChunk.getBottom() > lastChunk.getHeight() * 8){
                        return false;
                    }
                    return true;
                }

                // 如果和上一行对齐，和首行向右缩进，则可以合并
                if (!TextUtils.isParagraphEnd(text1)
                        && gapLineTextLeft < 6
                        && Math.abs(lastChunk.getTextRight() - block.prevParagraphRight) < 6
                        && nextChunk.getTextLeft() > block.prevParagraphLeft + nextChunk.getWidthOfSpace() * 2){
                    return true;
                }


                // 如果下一行向左缩进了, 则可以合并
                if (nextChunk.getMinX() + nextChunk.getWidthOfSpace() * 2 <= prevBlock.getFirstTextChunk().getMinX()) {
                    return true;
                }

                if (TextUtils.isParagraphKeep(text1)){
                    return true;
                }
            }

            //
            if (!TextUtils.isParagraphEnd(text1)
                    && gapLineTextLeft < 6.5
                    && lastChunk.getTextLeft() < 90
                    && lastChunk.getTextRight() > 500
                    && text1.length() + text2.length() > 160
                    && TextUtils.isENWord(text1) && TextUtils.isENWord(text2)){
                return true;
            }

            //
            if (TextUtils.isYearMonthDay(text2) && text2.trim().length() <= 11
                    && !TextUtils.hasComma(text1)
                    && !TextUtils.hasComma(text2)) {
                return false;
            }

            // 如果是逗号结尾, 则认为段落未结束
            if (StringUtils.endsWithAny(text.trim(), "，", ",", "、")) {
                return true;
            }

            // 判断上面一行从页面左边首起，并且过短, 如果很短的话单独为一个段落
            if ((Math.abs(prevBlock.getBounds2D().getMinX() - textGroup.getMinX()) < 2)
                    && (prevBlock.getBounds2D().getMaxX() + lastChunk.getMaxTextWidth() * 8 < textGroup.getMaxX())) {
                return false;
            }

            // 判断上一行未到段尾即结束，下一行也为单独行，则不合并；
            if (TextUtils.isParagraphEnd(text) && lastChunk.getTextRight() < 500
                    && TextUtils.isParagraphEnd(text2)
                    && nextChunk.getTextLeft() <= lastChunk.getTextLeft() + 2){
                return false;
            }

            // 判断上一行未到段尾即结束，下一行也为单独行，则不合并
            if (TextUtils.isParagraphEnd(text)
                    && lastChunk.getTextRight() < 500
                    && TextUtils.isParagraphEnd(text2)
                    && nextChunk.getTextLeft() > lastChunk.getTextLeft() + lastChunk.getWidthOfSpace() * 2){
                return false;
            }

            // 上一行从中间位置开始，且不含，。，上一行含有多个空格的间隔，下一行向右缩进两个字符，不合并
            if (!TextUtils.hasComma(text1)
                    && TextUtils.hasMultipleSpace(lastChunk)
                    && TextUtils.hasTableText(lastChunk)
                    && lastChunk.getTextLeft() > 90 + lastChunk.getMaxTextWidth() * 5
                    && Math.abs(nextChunk.getTextLeft() - 90 - nextChunk.getMaxTextWidth() * 2) < 10){
                return false;
            }

            // 上一行居中，无段落符号
            if (!TextUtils.isParagraphEnd(text1)
                    && FloatUtils.feq(lastChunk.getCenterX(), textGroup.getCenterX(), 30)
                    && lastChunk.getCenterX() - nextChunk.getCenterX() > nextChunk.getWidthOfSpace() * 8
                    && !TextUtils.hasComma(text1)
                    && lastChunk.getTextRight() < 480
                    && gapLineTextLeft > nextChunk.getWidthOfSpace() * 10){
                return false;
            }

            if (TextUtils.isCompanyTitle(text1.trim())
                    && FloatUtils.feq(lastChunk.getCenterX(), textGroup.getCenterX(), 30)
                    && FloatUtils.feq(nextChunk.getCenterX(), textGroup.getCenterX(), 30)
                    && nextChunk.getBottom() < 400){
                return true;
            }

            // 计算行间距
            double lineSpacing = nextChunk.getMinY() - lastChunk.getMaxY();
            // 如果行间距很近, 而且左对齐, 样式一样
            if (lineSpacing < nextChunk.getHeight() && lineSpacing > 0
                    && FloatUtils.feq(nextChunk.getLeft(), lastChunk.getLeft(), 3)
                    && FloatUtils.fgte(lastChunk.getWidth(), nextChunk.getWidth(), 8)) {
                if ((lastChunk.getMaxX() < textGroup.getTextRight() - textGroup.getWidth() / 8)
                        && (!nextChunk.isBold())) {
                    if (!(nextChunk.getTextLeft() == block.prevParagraphLeft
                            && TextUtils.isParagraphEnd(text2))) {
                        if (!TextUtils.hasComma(text) && !TextUtils.hasComma(text2)
                                && FloatUtils.feq(lastChunk.getCenterX(), (block.prevParagraphLeft + block.prevParagraphRight)/2,30)
                                && FloatUtils.feq(nextChunk.getCenterX(), (block.prevParagraphLeft + block.prevParagraphRight)/2,30)
                                && nextChunk.getTop() < 200){
                            return true;
                        }

                        return false;
                    }
                }

                if (nextChunk.getTextLeft() > textGroup.getTextLeft() + nextChunk.getWidthOfSpace() * 3
                        && nextChunk.getTop() < 150){
                    if (lastChunk.getTextLeft() < 95 && lastChunk.getTextRight() > 500
                            && gapLineTextLeft < 6.5
                            && gapLineTextRight < 6.5
                            && text.length() > 32 && text2.length() > 32){
                        return true;
                    }
                    if (TextUtils.isNoticeTitle(text2)){
                        return true;
                    }

                    if (!TextUtils.isParagraphEnd(text1)
                            && lastChunk.getTextRight() > 500
                            && lastChunk.getTextLeft() < 115
                            && gapLineTextLeft < 6
                            && TextUtils.hasComma(text2)){
                        return true;
                    }
                    return false;
                }

                if (TextUtils.isParagraphKeep(text)
                        && lastChunk.getTextRight() > 500){
                    return true;
                }

                if (!TextUtils.isParagraphEnd(text1) && TextUtils.isParagraphEnd(text2)
                        && text2.length() < 15
                        && gapLineTextLeft < 3
                        && lineSpacing < 5){
                    return true;
                }

                if (lastChunk.getTextRight() > textGroup.getTextRight() - lastChunk.getWidthOfSpace() * 2
                        && lastChunk.getTextLeft() > textGroup.getTextLeft() + lastChunk.getWidthOfSpace() * 3
                        && gapLineTextLeft < 6){
                    if (lastChunk.getTextLeft() == block.prevParagraphLeft
                            && !TextUtils.isParagraphEnd(text1)
                            && TextUtils.isParagraphEnd(text2)){
                        return true;
                    }

                    if (Math.abs(lastChunk.getTextLeft() - block.prevParagraphLeft) < 1
                            && !TextUtils.isParagraphEnd(text1)
                            && gapLineTextLeft < 2
                            && (gapLineTextRight < 2 || TextUtils.isParagraphEnd(text2))
                            && lastChunk.getTextLeft() < 60 && lastChunk.getTextRight() > 520
                            && text1.length()> 50){
                        return true;
                    }

                    return false;
                }

                // 判断上一行到页面中间即结束，则为单独一行的段落
                if (textGroup.getTextRight() - lastChunk.getTextRight() > lastChunk.getWidthOfSpace() * 6
                        && nextChunk.getTextLeft() - textGroup.getTextLeft() > nextChunk.getWidthOfSpace() * 3
                        && textGroup.getCenterX() - lastChunk.getCenterX() > lastChunk.getWidthOfSpace() * 6
                        && lastChunk.getTextRight() < 450
                        && TextUtils.isParagraphEnd(text2)){
                    return false;
                }

                return true;
            }
            // 如果行间距很近, 而且下一行向左缩进了, 则可以合并
            if (lineSpacing < nextChunk.getHeight() && lineSpacing > 0
                    && prevBlock.getFirstTextChunk().getLeft() - nextChunk.getLeft() >= nextChunk.getWidthOfSpace() * 2) {

                // 如果下一行的text以多个空格开始字符串，则不合并
                if (TextUtils.getStartSpaceN(nextChunk) >= 2) {
                    return false;
                }

                // 如果是月、日、年落款，则不合并
                if (TextUtils.isYearMonthDay(text2) && text2.trim().length() <= 11) {
                    return false;
                }

                // 如果上一行字数太少，且靠右边，则不合并
                if (text.length() < 18 && lastChunk.getTextLeft() > 300) {
                    return false;
                }

                // 如果上一行字数太少，且靠左边，则不合并
                if (text.length() < 18 && lastChunk.getTextRight() < 300){
                    return false;
                }

                return true;
            }

            // 如果都是居中对齐, 而且样式一样, 则合并
            if (FloatUtils.feq(lastChunk.getCenterX(), textGroup.getCenterX(), 30)
                    && FloatUtils.feq(nextChunk.getCenterX(), textGroup.getCenterX(), 30)
                    && lastChunk.hasSameStyle(nextChunk)) {

                if (nextChunk.getFontSize() < 16.0f || lastChunk.getFontSize() < 16.0f) {
                    return false;
                }


                if (TextUtils.isParagraphKeep(text1) && text1.trim().length() == 1
                        || TextUtils.isParagraphKeep(text2) && text2.trim().length() == 1){
                    return true;
                }

                if (nextChunk.getTextLeft() - lastChunk.getTextLeft() > nextChunk.getWidthOfSpace() * 3
                        && TextUtils.isParagraphEnd(text)
                        && (nextChunk.getTextRight() > block.prevParagraphRight - nextChunk.getWidthOfSpace() * 3)){
                    return false;
                }

                if (nextChunk.getTextLeft() - lastChunk.getTextLeft() > nextChunk.getWidthOfSpace() * 3
                        && TextUtils.isParagraphEnd(text)
                        && text1.length() + text2.length() > 60) {
                    return false;
                }

                if (!TextUtils.hasComma(text) && !TextUtils.hasComma(text2)
                        && lastChunk.getMaxFontSize() <= 9
                        && lastChunk.getTop() > 500
                        && text1.length() + text2.length() > 50){
                    return false;
                }

                if (StringUtils.endsWith(text.trim(), "）")
                        && lastChunk.getTextRight() < block.prevParagraphRight - 6
                        && nextChunk.getTextLeft() > block.prevParagraphLeft + 6){
                    return false;
                }

                if (lineSpacing > 0 && lineSpacing < nextChunk.getHeight()) {
                    if (lastChunk.getTextRight() < textGroup.getTextRight() - lastChunk.getWidthOfSpace() * 3
                            && lastChunk.getTextLeft() - textGroup.getTextLeft() > 6
                            && gapLineTextLeft < 6.5){
                        return false;
                    }
                    if (!(lastChunk.getTextLeft() == block.prevParagraphLeft
                            && lastChunk.getTextRight() == block.prevParagraphRight
                            && nextChunk.getTextLeft() - block.prevParagraphLeft > nextChunk.getWidthOfSpace() * 2)){
                        return true;
                    }
                    else if(!TextUtils.hasComma(text)
                            && !TextUtils.hasComma(text2)){
                        return true;

                    }
                }

                if (lineSpacing > 0 && (TextUtils.isBoldText(nextChunk))) {
                    if (FloatUtils.feq((block.prevParagraphLeft + block.prevParagraphRight)/2, lastChunk.getCenterX(), 30)
                            && FloatUtils.feq((block.prevParagraphLeft + block.prevParagraphRight)/2, nextChunk.getCenterX(), 30)
                            && nextChunk.getTop() < 400){
                        if (FloatUtils.feq(lastChunk.getCenterX(), textGroup.getCenterX(), 30)
                                && text.length() < 11 && text2.length() > 30
                                && nextChunk.getTextRight() + nextChunk.getWidthOfSpace()*3 > 500){
                            return false;
                        }

                        return true;
                    }
                }

                if (!TextUtils.hasComma(text) && !TextUtils.hasComma(text2)
                        && nextChunk.getBottom() < 300
                        && nextChunk.getMaxFontSize() > 10
                        && TextUtils.isBoldText(nextChunk)){
                    return true;
                }
            }

            // 如果文本离右边界太远, 认为段落结束
            if ((lastChunk.getMaxX() < textGroup.getMaxX() - textGroup.getWidth() / 8)
                    && !TextUtils.isBoldText(nextChunk)
                    && !TextUtils.isParagraphEnd(text2)) {
                if (lastChunk.getTextRight() > 500) {
                    if (text2.length() < 12 && nextChunk.getTextLeft() > 200){
                        return false;
                    }
                    return true;
                }
                return false;
            }

            // 如果两行靠得很近的话, 间距可能为负值
            if (lineSpacing < lastChunk.getHeight() * -1.2) {
                return false;
            } else if (lineSpacing >= prevBlock.getLineSpacing() + 5) {
                if (Math.abs(lastChunk.getTextLeft()-nextChunk.getTextLeft()) < 6
                        && lastChunk.getTextLeft() < 100
                        && lastChunk.getTextRight() > 500
                        && !TextUtils.isParagraphEnd(text)
                        && lastChunk.hasSameStyle(nextChunk)){
                    return true;
                }
                return false;
            }

            // 如果是月、日、年落款，则不合并
            if (TextUtils.isYearMonthDay(text2) && text2.trim().length() <= 11) {
                return false;
            }

            // 比较样式是否一致
            if (!inOneLine) {
                // 如果文本太短, 则不合并
                double centerX = textGroup.getCenterX();
                double contentWidth = textGroup.getWidth();

                if (lastChunk.getMaxX() < centerX - contentWidth / 8) {
                    return false;
                }

                if (lastChunk.getMinX() > textGroup.getCenterX() + contentWidth / 8) {
                    return false;
                }

                // 下一段向右缩进, 新的段落
                if (nextChunk.getMinX() > textGroup.getMinX() + nextChunk.getWidthOfSpace() * 2) {
                    // 字体较大，且不含标点符号，为文章首页大标题
                    if ((nextChunk.getFontSize() > 9.5) && !TextUtils.hasComma(text2) && nextChunk.isBold()) {
                        if ((Math.abs(lastChunk.getFontSize() - nextChunk.getFontSize()) > 0.75)
                                || (prevBlock.getBounds2D().getMaxX() + lastChunk.getMaxTextWidth() * 2 > textGroup.getMaxX())) {
                            return false;
                        }

                        if (!StringUtils.equals(lastChunk.getMostCommonFontName(), nextChunk.getMostCommonFontName())) {
                            return false;
                        }
                        return true;
                    }

                    if (prevBlock.getFirstTextChunk().getLeft() - nextChunk.getLeft() >= nextChunk.getWidthOfSpace() * 2) {
                        return true;
                    }

                    if (!TextUtils.isParagraphEnd(text1)
                            && lastChunk.getRight() > 500
                            && nextChunk.getTextLeft() > lastChunk.getTextLeft() + nextChunk.getWidthOfSpace() * 2
                            && TextUtils.isParagraphEnd(text2)){
                        return true;
                    }

                    return false;
                }

                // 左边界相差太大
                if (Math.abs(lastChunk.getMinX() - nextChunk.getMinX()) > lastChunk.getWidthOfSpace() * 9) {
                    return false;
                }

                // 上一行尾为段落结束符，下一行向右缩进两个字符
                if (TextUtils.isParagraphEnd(text)
                        && nextChunk.getTextLeft() >= prevBlock.getLeft() - nextChunk.getWidthOfSpace() * 2){
                    // return false;
                }
            }
            return true;
        }
        return true;
    }

}
