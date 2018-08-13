package com.abcft.pdfextract.core.table;

import com.abcft.pdfextract.core.ExtractorUtil;
import com.abcft.pdfextract.core.model.TextChunk;
import com.abcft.pdfextract.core.model.TextElement;
import com.abcft.pdfextract.util.FloatUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.pdfbox.pdmodel.PDPage;

import java.awt.geom.Point2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Deprecated
public class TabularUtils {


    public static TabularPage createPage(int pageNumber, PDPage page, TableExtractParameters params) throws IOException {
        TableExtractEngine se = new TableExtractEngine(pageNumber, page, params);
        se.processPage(page);


        Point2D.Float pageSize = ExtractorUtil.determinePageSize(page);
        int pageRotation = page.getRotation();

        return new TabularPage(
                params, pageNumber, page,
                0, 0, pageSize.x, pageSize.y, pageRotation, se.paper,
                se.minCharWidth, se.minCharHeight, se.avgCharWidth, se.avgCharHeight,
                se.characters, new ArrayList<>(),
                se.rulings, se.clipAreas,
                se.spatialIndex, null,
                null, null, null, null);
    }

    public static TabularTextChunk toTabularTextChunk(TextChunk chunk) {
        TabularTextChunk textChunk = new TabularTextChunk(chunk.getElements());
        textChunk.setRect(chunk);
        return textChunk;
    }
    public static List<TabularTextChunk> toTabularTextChunks(List<TextChunk> textChunks) {
        return textChunks.stream().map(TabularUtils::toTabularTextChunk).collect(Collectors.toList());
    }

    public static TextChunk toTextChunk(TabularTextChunk chunk) {
        TextChunk textChunk = new TextChunk();
        textChunk.addTextElements(chunk.getElements());
        return textChunk;
    }

    public static List<TextChunk> toTextChunks(List<TabularTextChunk> textChunks) {
        return textChunks.stream().map(TabularUtils::toTextChunk).collect(Collectors.toList());
    }

    static final float AVERAGE_CHAR_TOLERANCE = 0.3f;

    public static List<TabularTextChunk> mergeWords(List<TextElement> textElements) {
        return mergeWords(textElements, new ArrayList<>());
    }

    /**
     * heuristically merge a list of TextElement into a list of TextChunk
     * ported from from PDFBox's PDFTextStripper.writePage, with modifications.
     * Here be dragons
     */
    public static List<TabularTextChunk> mergeWords(List<TextElement> textElements, List<Ruling> verticalRulings) {

        List<TabularTextChunk> textChunks = new ArrayList<>();

        if (textElements.isEmpty()) {
            return textChunks;
        }

        // it's a problem that this `remove` is side-effecty
        // other things depend on `textElements` and it can sometimes lead to the first textElement in textElement
        // not appearing in the final output because it's been removed here.
        // https://github.com/tabulapdf/tabula-java/issues/78
        List<TextElement> copyOfTextElements = new ArrayList<>(textElements);
        textChunks.add(new TabularTextChunk(copyOfTextElements.remove(0)));
        TabularTextChunk firstTC = textChunks.get(0);

        float previousAveCharWidth = (float) firstTC.getWidth();
        float endOfLastTextX = firstTC.getRight();
        float maxYForLine = firstTC.getBottom();
        float maxHeightForLine = (float) firstTC.getHeight();
        float minYTopForLine = firstTC.getTop();
        float lastWordSpacing = -1;
        float wordSpacing, deltaSpace, averageCharWidth, deltaCharWidth;
        float expectedStartOfNextWordX, dist;
        TextElement sp, prevChar;
        TabularTextChunk currentChunk;
        boolean sameLine, acrossVerticalRuling;

        for (TextElement chr : copyOfTextElements) {
            currentChunk = textChunks.get(textChunks.size() - 1);
            prevChar = currentChunk.textElements.get(currentChunk.textElements.size() - 1);

            // if same char AND overlapped, skip
            if ((chr.getText().equals(prevChar.getText())) && (prevChar.overlapRatio(chr) > 0.5)) {
                continue;
            }

            // if chr is a space that overlaps with prevChar, skip
            if (chr.getText().equals(" ") && FloatUtils.feq(prevChar.getLeft(), chr.getLeft()) && FloatUtils.feq(prevChar.getTop(), chr.getTop())) {
                continue;
            }

            // Resets the average character width when we see a change in font
            // or a change in the font size
            if (!StringUtils.equals(chr.getFontName(), prevChar.getFontName()) || !FloatUtils.feq(chr.getFontSize(), prevChar.getFontSize())) {
                previousAveCharWidth = -1;
            }

            // is there any vertical ruling that goes across chr and prevChar?
            acrossVerticalRuling = false;
            for (Ruling r : verticalRulings) {
                if (
                        (verticallyOverlapsRuling(prevChar, r) && verticallyOverlapsRuling(chr, r)) &&
                                (prevChar.x < r.getPosition() && chr.x > r.getPosition()) || (prevChar.x > r.getPosition() && chr.x < r.getPosition())
                        ) {
                    acrossVerticalRuling = true;
                    break;
                }
            }

            // Estimate the expected width of the space based on the
            // space character with some margin.
            wordSpacing = chr.getWidthOfSpace();
            deltaSpace = 0;
            if (Float.isNaN(wordSpacing) || wordSpacing == 0) {
                deltaSpace = Float.MAX_VALUE;
            } else if (lastWordSpacing < 0) {
                deltaSpace = wordSpacing * 0.5f; // 0.5 == spacing tolerance
            } else {
                deltaSpace = ((wordSpacing + lastWordSpacing) / 2.0f) * 0.5f;
            }

            // Estimate the expected width of the space based on the
            // average character width with some margin. This calculation does not
            // make a true average (average of averages) but we found that it gave the
            // best results after numerous experiments. Based on experiments we also found that
            // .3 worked well.
            if (previousAveCharWidth < 0) {
                averageCharWidth = (float) (chr.getWidth() / chr.getText().length());
            } else {
                averageCharWidth = (float) ((previousAveCharWidth + (chr.getWidth() / chr.getText().length())) / 2.0f);
            }
            deltaCharWidth = averageCharWidth * AVERAGE_CHAR_TOLERANCE;

            // Compares the values obtained by the average method and the wordSpacing method and picks
            // the smaller number.
            expectedStartOfNextWordX = -Float.MAX_VALUE;

            if (endOfLastTextX != -1) {
                expectedStartOfNextWordX = endOfLastTextX + Math.min(deltaCharWidth, deltaSpace);
            }

            // new line?
            sameLine = true;
            if (!TableUtils.overlap(chr.getBottom(), chr.height, maxYForLine, maxHeightForLine)) {
                endOfLastTextX = -1;
                expectedStartOfNextWordX = -Float.MAX_VALUE;
                maxYForLine = -Float.MAX_VALUE;
                maxHeightForLine = -1;
                minYTopForLine = Float.MAX_VALUE;
                sameLine = false;
            }

            endOfLastTextX = chr.getRight();

            // should we add a space?
            if (!acrossVerticalRuling &&
                    sameLine &&
                    expectedStartOfNextWordX < chr.getLeft() &&
                    !prevChar.getText().endsWith(" ")) {

                sp = TableUtils.makeTextElement(prevChar.getGroupIndex(),
                        prevChar.getVisibleBBox(),
                        prevChar.getLeft(), prevChar.getTop(),
                        expectedStartOfNextWordX - prevChar.getLeft(),
                        (float) prevChar.getHeight(),
                        prevChar.getFontName(),
                        prevChar.getFontSize(),
                        " ",
                        new int[] { 32 },
                        prevChar.getWidthOfSpace(),
                        prevChar.getRotate());

                currentChunk.addElement(sp);
            } else {
                sp = null;
            }

            maxYForLine = Math.max(chr.getBottom(), maxYForLine);
            maxHeightForLine = (float) Math.max(maxHeightForLine, chr.getHeight());
            minYTopForLine = Math.min(minYTopForLine, chr.getTop());

            dist = chr.getLeft() - (sp != null ? sp.getRight() : prevChar.getRight());

            if (!acrossVerticalRuling &&
                    sameLine &&
                    (dist < 0 ? currentChunk.isVerticallyOverlap(chr) : dist < wordSpacing)) {
                currentChunk.addElement(chr);
            } else { // create a new chunk
                textChunks.add(new TabularTextChunk(chr));
            }

            lastWordSpacing = wordSpacing;
            previousAveCharWidth = (float) (sp != null ? (averageCharWidth + sp.getWidth()) / 2.0f : averageCharWidth);
        }

        // System.out.println("before grouping");
        // for(TextChunk q : textChunks){
        //     System.out.println("'" + q.getText() + "'");
        // }

        List<TabularTextChunk> textChunksSeparatedByDirectionality = new ArrayList<>();
        // count up characters by directionality
        for (TabularTextChunk chunk : textChunks) {
            // choose the dominant direction
            // System.out.println("beforegrouping: '" + chunk.getText() + "'");
            boolean isLtrDominant = chunk.isLtrDominant() != -1; // treat neutral as LTR
            TabularTextChunk dirChunk = chunk.groupByDirectionality(isLtrDominant);
            textChunksSeparatedByDirectionality.add(dirChunk);
        }
        // System.out.println("after grouping");
        // for(TextChunk q : textChunksSeparatedByDirectionality){
        //     System.out.println("after grouping: '" + q.getText() + "'");
        // }
        return textChunksSeparatedByDirectionality;
    }

    private static boolean verticallyOverlapsRuling(TextElement te, Ruling r) {
        // Utils.overlap(prevChar.getTop(), prevChar.getHeight(), r.getY1(), r.getY2() - r.getY1())
        return Math.max(0, Math.min(te.getBottom(), r.getY2()) - Math.max(te.getTop(), r.getY1())) > 0;
    }

}
