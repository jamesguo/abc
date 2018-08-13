package com.abcft.pdfextract.core.table.detectors;

import com.abcft.pdfextract.core.model.Rectangle;
import com.abcft.pdfextract.core.model.TextChunk;
import com.abcft.pdfextract.core.model.TextDirection;
import com.abcft.pdfextract.core.table.*;
import com.abcft.pdfextract.util.FloatUtils;
import org.apache.commons.math3.util.FastMath;

import java.awt.geom.Rectangle2D;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class PostProcessTableRegionsAlgorithm {

    static final Pattern CHART_SERIAL1 = Pattern.compile("(^(图表|圖表).*)");
    static final Pattern CHART_SERIAL2 = Pattern.compile("^\\s*(图|圖)(\\s{0,3})(\\d{1,2})(：|:).*");
    static final Pattern CHART_SERIAL3 = Pattern.compile("(^(图|圖|chart|figure|Chart|Figure)[(（]?\\s?[0-9]{1,2}[)）]?.*)");
    static final Pattern TABLE_SERIAL = Pattern.compile("(^表[(（]?[0-9]{1,2}.*)|(^表[零一二三四五六七八九十].*)" +
            "|(^(table)\\s+((i|ii|iii|iv|v|vi|vii|viii|ix|I|II|III|IV|V|VI|VII|VIII|IX|Ⅰ|Ⅱ|Ⅲ|Ⅳ|Ⅴ|Ⅵ|Ⅶ|Ⅷ|Ⅸ)|(\\d{1,2})).*)" +
            "|(^(the following table).*)|(.*(如下表|如下表所示|见下表)(：|:)$)|(^(下表|以下).*(：|:)$)" +
            "|(.*下表列出.*)|(.*(table)\\s+\\d{1,2}$)|(.*(as follows)[：:.]$)");
    static final Pattern DATA_SOURCE_SERIAL = Pattern.compile("(^(数据来源|资料来源|資料來源|来源|來源|Source|source)[：:].*)");
    static final Pattern CHART_TABLE_SPLIT_SERIAL = Pattern.compile("(^(图|表|圖|表|chart|figure).*)");
    static final Pattern NUM_SERAL = Pattern.compile("(^([+-]?(([0-9]*)|([0-9].*)))([0-9]%?)$)");//Pattern.compile("^([+-]?[0-9].*)([0-9]%?)$");
    static final Pattern CONNECT_SERAL = Pattern.compile(".*(：|:)$");


    public static List<Table> postDetectTableRegions(ContentGroupPage page, List<Table> tables) {
        if (tables.isEmpty()) {
            return new ArrayList<>();
        }

        List<Rectangle> rulingAreas = RulingTableRegionsDetectionAlgorithm.findRulingRectangles(page)
                .stream().map(Rectangle::new).collect(Collectors.toList());
        List<Table> targeTables = new ArrayList<>();
        for (Table table : tables) {

            /*if (table.isStructureTable()) {
                TableDebugUtils.writeCells(page, Arrays.asList(table), "original_struct_table");
            } else {
                TableDebugUtils.writeCells(page, Arrays.asList(table), "original_table");
            }*/

            List<Rectangle> lineAreas = getRulingArea(page, table, rulingAreas);
            // 疑似为chart的表格区域处理
            if (isPossibleChartAreas(page, table, lineAreas)) {
                continue;
            }

            if (table.getTableType() == TableType.LineTable) {
                if (analysisLineTableTextChunks(page, table) != null) {
                    targeTables.add(table);
                }
                continue;
            }

            targeTables.addAll(analysisNoLineTableTextChunks(page, table, lineAreas));
        }
        targeTables = targeTables.stream().filter(t ->(t.getConfidence() > 0.3)).collect(Collectors.toList());
        return targeTables;
    }

    private static boolean isValidLineArea(ContentGroupPage page, Table table) {

        // 每条边框至少存在相交线，否则区域无效
        List<Ruling> hlines = page.getHorizontalRulings();
        List<Ruling> vlines = page.getVerticalRulings();

        Ruling topLine = new Ruling(table.getTop(), table.getLeft(), (float)table.getWidth(),0);
        boolean isLossTopLine = !hlines.stream()
                .anyMatch(r -> (FloatUtils.feq(r.getTop(), topLine.getTop(), 3.5) && r.getWidth() / topLine.getWidth() > 0.9));

        Ruling leftLine = new Ruling(table.getTop(), table.getLeft(), 0, (float)table.getHeight());
        boolean isLossLeftLine = !vlines.stream()
                .anyMatch(r -> (FloatUtils.feq(r.getLeft(), leftLine.getLeft(), 3.5) && r.getHeight() / leftLine.getHeight() > 0.9));

        Ruling rightLine = new Ruling(table.getTop(), table.getRight(), 0, (float)table.getHeight());
        boolean isLossRightLine = !vlines.stream()
                .anyMatch(r -> (FloatUtils.feq(r.getRight(), rightLine.getRight(), 3.5) && r.getHeight() / rightLine.getHeight() > 0.9));

        if (isLossTopLine) {
            List<Ruling> topCrossRulings = vlines.stream()
                    .filter(r->r.nearlyIntersects(topLine, 2)).collect(Collectors.toList());
            if (topCrossRulings.size() < 2
                    && !(topCrossRulings.size() == 1 && isLossLeftLine && isLossRightLine)) {
                return true;
            }
        }

        Ruling bottomLine = new Ruling(table.getBottom(), table.getLeft(), (float)table.getWidth(), 0);
        boolean isLossBottomLine = !hlines.stream()
                .anyMatch(r -> (FloatUtils.feq(r.getBottom(), bottomLine.getBottom(), 3.5) && r.getWidth() / bottomLine.getWidth() > 0.9));
        if (isLossBottomLine) {
            List<Ruling> bottomCrossRulings = vlines.stream()
                    .filter(r->r.nearlyIntersects(bottomLine, 2)).collect(Collectors.toList());
            if (bottomCrossRulings.size() < 2
                    && !(bottomCrossRulings.size() == 1 && isLossLeftLine && isLossRightLine)) {
                return true;
            }
        }


        if (isLossLeftLine) {
            List<Ruling> leftCrossRulings = hlines.stream()
                    .filter(r->r.nearlyIntersects(leftLine, 5)).collect(Collectors.toList());
            if (leftCrossRulings.size() < 2 &&
                    !(leftCrossRulings.size() == 1 && isLossTopLine && isLossBottomLine)) {
                return true;
            }
        }


        if (isLossRightLine) {
            List<Ruling> rightCrossRulings = hlines.stream()
                    .filter(r->(r.nearlyIntersects(rightLine, 5))).collect(Collectors.toList());
            if (rightCrossRulings.size() < 2 &&
                    !(rightCrossRulings.size() == 1 && isLossTopLine && isLossBottomLine)) {
                return true;
            }
        }
        return false;
    }


    private static int getChunkRowCnt(List<TextChunk> textChunks) {

        int rowCnt = 0;

        if (!textChunks.isEmpty()) {
            if (textChunks.size() == 1) {
                rowCnt = 1;
                return rowCnt;
            }

            TextChunk baseChunk = new TextChunk(textChunks.get(0));
            for (int i = 1; i < textChunks.size(); i++) {
                TextChunk otherChunk = new TextChunk(textChunks.get(i));
                if (baseChunk.horizontallyOverlapRatio(otherChunk) > 0.5) {
                    rowCnt++;
                } else {
                    if (baseChunk.verticalOverlapRatio(otherChunk) > 0.5) {
                        baseChunk.merge(otherChunk);
                        continue;
                    }
                }
                baseChunk = otherChunk;
            }
            if (textChunks.size() > 1) {
                rowCnt++;
            }
        }
        return rowCnt;
    }

    private static Table analysisLineTableTextChunks(ContentGroupPage page, Table table) {

        if (table.getTableType() != TableType.LineTable) {
            return null;
        }

        List<TextChunk> upChunks = upSearchChunks(page, table);
        List<TextChunk> clusChunks = upChunks.stream()
                .filter(te -> (te.verticalOverlapRatio(upChunks.get(0)) > 0.8)).collect(Collectors.toList());
        String serials = getSequenceOfChunks(page, clusChunks);
        if (!serials.equals("") && TABLE_SERIAL.matcher(serials.trim().toLowerCase()).matches()) {
            return table;
        }

        List<TextChunk> rowChunks = collectAreaTextChunk(page, table);
        if (!rowChunks.isEmpty() && isOneRowChunks(rowChunks)) {
            rowChunks.sort(Comparator.comparing(Rectangle::getLeft));
            TextChunk baseChunk = rowChunks.get(0);
            List<Ruling> vRulings = Ruling.getRulingsFromArea(page.getVerticalRulings(), table);
            for (int i = 1; i < rowChunks.size(); i++) {
                TextChunk otherChunk = rowChunks.get(i);
                if (!vRulings.stream().anyMatch(vr -> ((vr.getX1() > baseChunk.getRight() || FloatUtils.feq(vr.getX1(), baseChunk.getRight(), 4.f))
                        && (vr.getX1() < otherChunk.getLeft() || FloatUtils.feq(vr.getX1(), otherChunk.getLeft(), 4.f))
                        && vr.toRectangle().verticalOverlapRatio(baseChunk) > 0.95 && vr.toRectangle().verticalOverlapRatio(otherChunk) > 0.95))) {
                    return null;
                }
            }
        }

        if (table.getRowCount() == 1 && table.getCellCount() < 3) {
            if (!(table.getColumnCount() == 2
                    && table.getCells().stream().anyMatch(ce -> (getChunkRowCnt(collectAreaTextChunk(page, ce)) > 1)))) {
                return null;
            }
        }

        Table targetTable;
        if ((page.getPageNumber() == 1 && isHaveDiffTypeChunks(page, table))
                || (table.getHeight() < 4 * page.getHeight() && table.getWidth() < page.getWidth() / 3
                && isVaildTableAreaByAnalysisTextChunks(page, table, new ArrayList<>()))) {
            targetTable = null;
        } else {
            targetTable = table;
        }

        if (targetTable != null && table.getRowCount() <= 3 && isValidLineArea(page, table)) {
            targetTable = null;
        }
        return targetTable;
    }

    // 有线框的无线表格
    private static List<Rectangle> getRulingArea(ContentGroupPage page, Table table, List<Rectangle> lineAreas) {

        List<Rectangle> rulingAreas = lineAreas.stream().filter(lineArea -> (lineArea.nearlyContains(table, 2.f)
                && lineArea.getWidth() > 0.3 * page.getWidth()
                && lineArea.getHeight() > 3 * page.getAvgCharHeight())).collect(Collectors.toList());

        return rulingAreas;
    }

    private static List<Table> analysisNoLineTableTextChunks(ContentGroupPage page, Table table, List<Rectangle> lineAreas) {

        List<TextChunk> tableChunks = page.getTextChunks(table);
        if (tableChunks.isEmpty() || table.getTableType() != TableType.NoLineTable) {
            return new ArrayList<>();
        }

        // 对单行文本的处理，主要指最顶部，底部或中间一些无意义文本
        if (isSingnalRowTexts(page, table)) {
            return new ArrayList<>();
        }

        // 对文本数量很小或零散文本的table处理
        if (isVaildTableAreaByAnalysisTextChunks(page, table, lineAreas)) {
            return new ArrayList<>();
        }

        // 根据单元格内文本流来确定表格是否有效,适用于单元格比较少的情况
        /*if (!isVaildTableAreaByAnalysisTextStream(page, table)) {
            return new ArrayList<>();
        }*/

        // 文本差异很大的table处理
        if (isHaveDiffTypeChunks(page, table)) {
            return new ArrayList<>();
        }

        List<Table> tables = new ArrayList<>();
        tables.add(table);
        return tables;

    }

    private static boolean isOneRowChunks(List<TextChunk> textChunks) {

        if (!textChunks.isEmpty()) {
            if (textChunks.size() == 1) {
                return true;
            }
            int sameCnt = 0;
            TextChunk baseChunk = textChunks.get(0);
            for (int i = 1; i < textChunks.size(); i++) {
                TextChunk otherChunk = textChunks.get(i);
                if (baseChunk.verticalOverlapRatio(otherChunk) > 0.5) {
                    sameCnt++;
                } else {
                    break;
                }
                baseChunk = otherChunk;
            }

            if (sameCnt == textChunks.size() - 1) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSingnalRowTexts(ContentGroupPage page, Table table) {

        List<TextChunk> textChunks = page.getTextChunks(table);

        if (!textChunks.isEmpty()) {

            if (textChunks.size() == 1) {
                return true;
            }

            int sameCnt = 0;
            TextChunk baseChunk = textChunks.get(0);
            for (int i = 1; i < textChunks.size(); i++) {
                TextChunk otherChunk = textChunks.get(i);
                if (baseChunk.verticalOverlapRatio(otherChunk) > 0.5) {
                    sameCnt++;
                } else {
                    break;
                }
                baseChunk = otherChunk;
            }
            TextChunk maxHeightChunk = textChunks.stream().max(Comparator.comparing(TextChunk::getHeight)).get();
            if (sameCnt == textChunks.size() - 1 && table.getHeight() < 1.5 * maxHeightChunk.getHeight()) {

                if (table.getColumnCount() >= 5) {
                    if (table.getConfidence() < 0.3) {
                        table.setConfidence(0.5);
                    }
                    return false;
                }

                List<TextChunk> allChunks = page.getTextChunks();
                // 位于顶部，可能为页眉区域
                TextChunk topChunk = allChunks.stream().min(Comparator.comparing(TextChunk::getTop)).get();
                if (topChunk.verticalOverlapRatio(table) > 0.5) {

                    if ((table.getWidth() < page.getWidth() / 3 || textChunks.size() < 4) && topChunk.isPageHeader()) {
                        return true;
                    }

                    if (upSearchChunks(page, table).size() == 0
                            && downSearchChunks(page, table).size() == 0) {
                        return true;
                    }
                }

                // 位于底部，可能为页脚区域
                TextChunk bottomChunk = allChunks.stream().max(Comparator.comparing(TextChunk::getBottom)).get();
                if (bottomChunk.verticalOverlapRatio(table) > 0.5) {

                    if ((table.getWidth() < page.getWidth() / 3 || textChunks.size() < 4)) {
                        return true;
                    }

                    List<TextChunk> downChunks = downSearchChunks(page, table);
                    List<TextChunk> upChunks = upSearchChunks(page, table);

                    if (downChunks.size() == 0 && upChunks.size() == 0) {
                        return true;
                    }

                    if (downChunks.size() == 0 && isHasDiffColumnsAtUp(page, table, upChunks)) {
                        return true;
                    }

                    if (upChunks.size() == 0 && isHasDiffColumnsAtDown(page, table, downChunks)) {
                        return true;
                    }

                    if (downChunks.size() != 0 && upChunks.size() != 0
                            && (isHasDiffColumnsAtUp(page, table, upChunks) || isHasDiffColumnsAtDown(page, table, downChunks))) {
                        return true;
                    }
                }

                // 其他位置，宽度很小，chunk数量很小，无数字串
                if (table.getWidth() < page.getWidth() / 2) {
                    return true;
                }

                if (!textChunks.stream().anyMatch(te -> (NUM_SERAL.matcher(te.getText().trim().toLowerCase()).matches()))) {
                    return true;
                }

                TextChunk headerChunk0 = textChunks.get(0);
                if (CHART_TABLE_SPLIT_SERIAL.matcher(headerChunk0.getText().toLowerCase()).matches()) {
                    for (TextChunk tmpChunk : textChunks) {
                        if (tmpChunk.equals(headerChunk0)) {
                            continue;
                        }
                        if (CHART_TABLE_SPLIT_SERIAL.matcher(tmpChunk.getText().toLowerCase()).matches()) {
                            return true;
                        }
                    }
                }
                TextChunk tailChunk0 = textChunks.get(0);
                if (DATA_SOURCE_SERIAL.matcher(tailChunk0.getText().toLowerCase()).matches()) {
                    for (TextChunk tmpChunk : textChunks) {
                        if (tmpChunk.equals(headerChunk0)) {
                            continue;
                        }
                        if (DATA_SOURCE_SERIAL.matcher(tmpChunk.getText().toLowerCase()).matches()) {
                            return true;
                        }
                    }
                }
            }

            if (table.getCellCount() < 4) {
                return true;
            }
        }
        return false;
    }

    private static boolean isHasDiffColumnsAtDown(ContentGroupPage page, Table tableArea, List<TextChunk> allChunks) {

        if (allChunks.isEmpty()) {
            return false;
        }

        List<TextChunk> candidateDownChunks = new ArrayList<>(allChunks);
        candidateDownChunks.sort(Comparator.comparing(TextChunk::getTop));
        TextChunk baseTopChunk = candidateDownChunks.get(0);
        List<TextChunk> downChunks0 = candidateDownChunks.stream().filter(te -> (te.verticalOverlapRatio(baseTopChunk) > 0.5)).collect(Collectors.toList());
        Rectangle tmpArea = getTextAreaByChunks(candidateDownChunks);
        int diffCnt = 0;
        List<TextChunk> textChunks = page.getTextChunks(tableArea);
        if (tmpArea.getHeight() > tableArea.getHeight() && tmpArea.getHeight() / tableArea.getHeight() > 2.f) {
            if (downChunks0.size() != textChunks.size()) {
                diffCnt++;
            }
            candidateDownChunks.removeAll(downChunks0);
            if (!candidateDownChunks.isEmpty()) {
                TextChunk baseTopChunk1 = candidateDownChunks.get(0);
                List<TextChunk> downChunks1 = candidateDownChunks.stream().filter(te -> (te.verticalOverlapRatio(baseTopChunk1) > 0.5)).collect(Collectors.toList());
                if (downChunks1.size() != textChunks.size()) {
                    diffCnt++;
                }

                candidateDownChunks.removeAll(downChunks1);
                if (!candidateDownChunks.isEmpty()) {
                    TextChunk baseTopChunk2 = candidateDownChunks.get(0);
                    List<TextChunk> upChunks2 = candidateDownChunks.stream().filter(te -> (te.verticalOverlapRatio(baseTopChunk2) > 0.5)).collect(Collectors.toList());
                    if (upChunks2.size() != textChunks.size()) {
                        diffCnt++;
                    }
                }
            }
            if (diffCnt > 1) {
                return true;
            }
        } else {
            if (downChunks0.size() != textChunks.size()) {
                return true;
            }
        }
        return false;
    }


    private static boolean isHasDiffColumnsAtUp(ContentGroupPage page, Table tableArea, List<TextChunk> allChunks) {

        if (allChunks.isEmpty()) {
            return false;
        }

        List<TextChunk> candidateUpChunks = new ArrayList<>(allChunks);
        candidateUpChunks.sort(Comparator.comparing(TextChunk::getBottom).reversed());
        TextChunk baseBottomChunk = candidateUpChunks.get(0);
        List<TextChunk> upChunks0 = candidateUpChunks.stream().filter(te -> (te.verticalOverlapRatio(baseBottomChunk) > 0.5)).collect(Collectors.toList());
        Rectangle tmpArea = getTextAreaByChunks(candidateUpChunks);
        int diffCnt = 0;
        List<TextChunk> textChunks = page.getTextChunks(tableArea);
        if (tmpArea.getHeight() > tableArea.getHeight() && tmpArea.getHeight() / tableArea.getHeight() > 2.f) {
            if (upChunks0.size() != textChunks.size()) {
                diffCnt++;
            }
            candidateUpChunks.removeAll(upChunks0);
            if (!candidateUpChunks.isEmpty()) {
                TextChunk baseBottomChunk1 = candidateUpChunks.get(0);
                List<TextChunk> upChunks1 = candidateUpChunks.stream().filter(te -> (te.verticalOverlapRatio(baseBottomChunk1) > 0.5)).collect(Collectors.toList());
                if (upChunks1.size() != textChunks.size()) {
                    diffCnt++;
                }

                candidateUpChunks.removeAll(upChunks1);
                if (!candidateUpChunks.isEmpty()) {
                    TextChunk baseBottomChunk2 = candidateUpChunks.get(0);
                    List<TextChunk> upChunks2 = candidateUpChunks.stream().filter(te -> (te.verticalOverlapRatio(baseBottomChunk2) > 0.5)).collect(Collectors.toList());
                    if (upChunks2.size() != textChunks.size()) {
                        diffCnt++;
                    }
                }
            }
            if (diffCnt > 1) {
                return true;
            }
        } else {
            if (upChunks0.size() != textChunks.size()) {
                return true;
            }
        }
        return false;
    }

    public static List<TextChunk> upSearchChunks(ContentGroupPage page, Rectangle tableArea) {
        List<TextChunk> areaChunks = page.getTextChunks(tableArea);
        if (areaChunks.isEmpty()) {
            return new ArrayList<>();
        }
        List<TextChunk> upChunks = new ArrayList<>();

        List<TextChunk> allChunks = page.getTextChunks();
        List<TextChunk> allCandidateChunks = allChunks.stream().filter(te -> (te.horizontallyOverlapRatio(tableArea) > 0.5
                && (te.getBottom() < tableArea.getTop()
                || FloatUtils.feq(te.getBottom(), tableArea.getTop(), 4.f)))).collect(Collectors.toList());

        if (!allCandidateChunks.isEmpty()) {
            List<TextChunk> candidateChunks = new ArrayList<>(allCandidateChunks);
            candidateChunks.sort(Comparator.comparing(TextChunk::getBottom).reversed());
            TextChunk bottomChunk0 = candidateChunks.get(0);
            double height = (bottomChunk0.getDirection() == TextDirection.VERTICAL_DOWN
                    || bottomChunk0.getDirection() == TextDirection.VERTICAL_UP) ? bottomChunk0.getWidth() : bottomChunk0.getHeight();
            List<TextChunk> textChunks0 = candidateChunks.stream()
                    .filter(te -> (((!isHaveThunksBetweenGap(page, tableArea, te, candidateChunks)
                            && tableArea.verticalDistance(te) < 3 *height)
                            || FloatUtils.feq(tableArea.getTop(), te.getBottom(), 4.f))
                            /*&& areaChunks.get(0).getDirection() == te.getDirection()*/
                            && te.verticalOverlapRatio(bottomChunk0) > 0.5)).collect(Collectors.toList());
            Rectangle tmpArea = null;
            if (!textChunks0.isEmpty()) {
                upChunks.addAll(textChunks0);
                tmpArea = getTextAreaByChunks(textChunks0);
                tmpArea.merge(tableArea);
            }
            if (tmpArea != null && !upChunks.isEmpty()) {
                Rectangle finalTmpArea = tmpArea;
                candidateChunks.removeAll(upChunks);
                if (!candidateChunks.isEmpty()) {
                    TextChunk bottomChunk1 = candidateChunks.get(0);
                    List<TextChunk> textChunks1 = candidateChunks.stream()
                            .filter(te -> (((!isHaveThunksBetweenGap(page, finalTmpArea, te, candidateChunks)
                                    && finalTmpArea.verticalDistance(te) < 3 * height)
                                    || FloatUtils.feq(finalTmpArea.getTop(), te.getBottom(), 4.f))
                                    /*&& areaChunks.get(0).getDirection() == te.getDirection()*/
                                    && te.verticalOverlapRatio(bottomChunk1) > 0.5)).collect(Collectors.toList());
                    if (!textChunks1.isEmpty()) {
                        upChunks.addAll(textChunks1);
                        tmpArea = finalTmpArea.merge(getTextAreaByChunks(textChunks1));
                    } else {
                        tmpArea = null;
                    }
                }
            }

            if (tmpArea != null && !upChunks.isEmpty()) {
                Rectangle finalTmpArea = tmpArea;
                candidateChunks.removeAll(upChunks);
                if (!candidateChunks.isEmpty()) {
                    TextChunk bottomChunk2 = candidateChunks.get(0);
                    List<TextChunk> textChunks2 = candidateChunks.stream()
                            .filter(te -> (((!isHaveThunksBetweenGap(page, finalTmpArea, te, candidateChunks)
                                    && finalTmpArea.verticalDistance(te) < 3 * height)
                                    || FloatUtils.feq(finalTmpArea.getTop(), te.getBottom(), 4.f))
                                    /*&& areaChunks.get(0).getDirection() == te.getDirection()*/
                                    && te.verticalOverlapRatio(bottomChunk2) > 0.5)).collect(Collectors.toList());
                    if (!textChunks2.isEmpty()) {
                        upChunks.addAll(textChunks2);
                    }
                }
            }
        }

        return upChunks;
    }

    public static List<TextChunk> downSearchChunks(ContentGroupPage page, Rectangle tableArea) {

        List<TextChunk> areaChunks = page.getTextChunks(tableArea);
        if (areaChunks.isEmpty()) {
            return new ArrayList<>();
        }
        List<TextChunk> downChunks = new ArrayList<>();

        List<TextChunk> allChunks = page.getTextChunks();
        List<TextChunk> allCandidateChunks = allChunks.stream().filter(te -> (te.horizontallyOverlapRatio(tableArea) > 0.5
                && (te.getTop() > tableArea.getBottom()
                || FloatUtils.feq(te.getTop(), tableArea.getBottom(), 4.f)))).collect(Collectors.toList());

        if (!allCandidateChunks.isEmpty()) {
            List<TextChunk> candidateChunks = new ArrayList<>(allCandidateChunks);
            candidateChunks.sort(Comparator.comparing(TextChunk::getTop));
            TextChunk topChunk0 = candidateChunks.get(0);
            double height = (topChunk0.getDirection() == TextDirection.VERTICAL_DOWN
                    || topChunk0.getDirection() == TextDirection.VERTICAL_UP) ? topChunk0.getWidth() : topChunk0.getHeight();
            List<TextChunk> textChunks0 = candidateChunks.stream()
                    .filter(te -> (((!isHaveThunksBetweenGap(page, tableArea, te, candidateChunks)
                            && tableArea.verticalDistance(te) < 3 * height)
                            || FloatUtils.feq(tableArea.getBottom(), te.getTop(), 4.f))
                            /*&& areaChunks.get(0).getFontSize() == te.getFontSize()*/
                            && te.verticalOverlapRatio(topChunk0) > 0.5)).collect(Collectors.toList());
            Rectangle tmpArea = null;
            if (!textChunks0.isEmpty()) {
                downChunks.addAll(textChunks0);
                tmpArea = getTextAreaByChunks(textChunks0);
                tmpArea.merge(tableArea);
            }
            if (tmpArea != null && !downChunks.isEmpty()) {
                Rectangle finalTmpArea = tmpArea;
                candidateChunks.removeAll(downChunks);
                if (!candidateChunks.isEmpty()) {
                    TextChunk topChunk1 = candidateChunks.get(0);
                    List<TextChunk> textChunks1 = candidateChunks.stream()
                            .filter(te -> (((!isHaveThunksBetweenGap(page, finalTmpArea, te, candidateChunks)
                                    && finalTmpArea.verticalDistance(te) < 3 * height)
                                    || FloatUtils.feq(finalTmpArea.getBottom(), te.getTop(), 4.f))
                                    /*&& areaChunks.get(0).getDirection() == te.getDirection()*/
                                    && te.verticalOverlapRatio(topChunk1) > 0.5)).collect(Collectors.toList());
                    if (!textChunks1.isEmpty()) {
                        downChunks.addAll(textChunks1);
                        tmpArea = finalTmpArea.merge(getTextAreaByChunks(textChunks1));
                    } else {
                        tmpArea = null;
                    }
                }
            }

            if (tmpArea != null && !downChunks.isEmpty()) {
                Rectangle finalTmpArea = tmpArea;
                candidateChunks.removeAll(downChunks);
                if (!candidateChunks.isEmpty()) {
                    TextChunk topChunk2 = candidateChunks.get(0);
                    List<TextChunk> textChunks2 = candidateChunks.stream()
                            .filter(te -> (((!isHaveThunksBetweenGap(page, finalTmpArea, te, candidateChunks)
                                    && finalTmpArea.verticalDistance(te) < 3 * height)
                                    || FloatUtils.feq(finalTmpArea.getBottom(), te.getTop(), 4.f))
                                    /*&& areaChunks.get(0).getDirection() == te.getDirection()*/
                                    && te.verticalOverlapRatio(topChunk2) > 0.5)).collect(Collectors.toList());
                    if (!textChunks2.isEmpty()) {
                        downChunks.addAll(textChunks2);
                    }
                }
            }
        }
        return downChunks;
    }

    public static Rectangle getTextAreaByChunks(List<TextChunk> allChunks) {

        if (allChunks.isEmpty()) {
            return null;
        }

        float left = allChunks.stream().min(Comparator.comparing(TextChunk::getLeft)).get().getLeft();
        float width = allChunks.stream().max(Comparator.comparing(TextChunk::getRight)).get().getRight() - left;
        float top = allChunks.stream().min(Comparator.comparing(TextChunk::getTop)).get().getTop();
        float height = allChunks.stream().max(Comparator.comparing(TextChunk::getBottom)).get().getBottom() - top;

        return new Rectangle(left, top, width, height);
    }

    private static boolean isHaveThunksBetweenGap(ContentGroupPage page, Rectangle one, Rectangle other, List<TextChunk> allChunks) {
        if (one.overlapRatio(other) > 0 || allChunks.isEmpty() ) {
            return true;
        }

        Rectangle gapRect = null;
        float left, top, w, h;
        if (one.isVerticallyOverlap(other)) {
            boolean isOneAtLeft = one.getLeft() < other.getLeft();
            left = isOneAtLeft ? one.getRight() : other.getRight();
            top = FastMath.max(one.getTop(), other.getTop());
            w = isOneAtLeft ? (other.getLeft() -left) : (one.getLeft() - left);
            h = FastMath.min(one.getBottom(), other.getBottom()) - top;
            gapRect = new Rectangle(left, top, w, h);
        }

        if (one.isHorizontallyOverlap(other)) {
            boolean isOneAtTop = one.getBottom() < other.getTop();
            left = FastMath.max(one.getLeft(), other.getLeft());
            top = isOneAtTop ? one.getBottom() : other.getBottom();
            w = FastMath.min(one.getRight(), other.getRight()) -left;
            h = isOneAtTop ? (other.getTop() - top) : (one.getTop() - top);
            gapRect = new Rectangle(left, top, w, h);
        }

        if (gapRect != null) {

            Rectangle gapRect1 = gapRect.rectReduce(1.0,1.0, page.getWidth(), page.getHeight());

            if (collectAreaTextChunk(page, gapRect).stream()
                    .filter(r -> (gapRect1.contains(r) || gapRect1.intersects(r))).collect(Collectors.toList()).isEmpty()) {
                return false;
            }
        }

        return true;
    }

    private static Map<Integer, List<Rectangle>> calcRowsByChunks(List<List<TextChunk>> textChunks) {

        Map<Integer, List<Rectangle>> thunkMap = new HashMap<>();
        if (textChunks.isEmpty()) {
            return thunkMap;
        }
        int rows = 0;
        List<Rectangle> textAreas = new ArrayList<>();
        for (List<TextChunk> thunks : textChunks) {
            textAreas.add(getTextAreaByChunks(thunks));
        }
        textAreas.sort(Comparator.comparing(Rectangle::getTop));
        do {
            Rectangle baseArea = textAreas.get(0);
            List<Rectangle> rowAreas = textAreas.stream().filter(re -> (re.verticalOverlapRatio(baseArea) > 0.5)).collect(Collectors.toList());
            rowAreas.sort(Comparator.comparing(Rectangle::getLeft));
            thunkMap.put(rows, rowAreas);
            rows++;
            textAreas.removeAll(rowAreas);

        } while(!textAreas.isEmpty());

        return thunkMap;
    }

    private static List<List<TextChunk>> getBlockCells(ContentGroupPage page, List<TextChunk> areaChunks) {

        if (areaChunks.isEmpty()) {
            return new ArrayList<>();
        }

        List<List<TextChunk>> blockCells = new ArrayList<>();
        TextChunk baseChunk = areaChunks.get(0);
        List<TextChunk> cellChunks = new ArrayList<>();
        cellChunks.add(baseChunk);
        boolean isMerge = false;
        for (int i = 1; i < areaChunks.size(); i++) {
            TextChunk otherChunk = areaChunks.get(i);
            boolean mutiRowFlag = baseChunk.horizontallyOverlapRatio(otherChunk) > 0.9
                    && (baseChunk.getFontSize() == otherChunk.getFontSize()
                    || FloatUtils.feq(baseChunk.getFontSize(), otherChunk.getFontSize(), 2.f))
                    && !CONNECT_SERAL.matcher(baseChunk.getText().toLowerCase()).matches()
                    && baseChunk.verticalDistance(otherChunk) < 2 * page.getAvgCharHeight();
            if (i < areaChunks.size() - 1) {
                TextChunk nextChunk = areaChunks.get(i + 1);
                if (nextChunk.verticalOverlapRatio(otherChunk) > 0.7
                        && nextChunk.isHorizontallyOverlap(baseChunk)) {
                    mutiRowFlag = false;
                }
            }

            if (mutiRowFlag) {
                cellChunks.add(otherChunk);
                isMerge = true;
            }

            if (!isMerge && baseChunk.verticalOverlapRatio(otherChunk) > 0.7
                    && (baseChunk.getMaxFontSize() == otherChunk.getMaxFontSize()
                    || FloatUtils.feq(baseChunk.getFontSize(), otherChunk.getFontSize(), 2.f))) {
                if (baseChunk.getLeft() < otherChunk.getLeft() && CONNECT_SERAL.matcher(baseChunk.getText().toLowerCase()).matches()) {
                    cellChunks.add(otherChunk);
                    isMerge = true;
                }
                float minGap1 = baseChunk.getAvgCharWidth();
                float minGap2 = otherChunk.getAvgCharWidth();
                minGap1 = (minGap1 < minGap2) ? minGap1 : minGap2;
                if (!isMerge && baseChunk.horizontalDistance(otherChunk) < minGap1) {
                    cellChunks.add(otherChunk);
                    isMerge = true;
                }
            } else {
                isMerge = mutiRowFlag;
            }

            if (!isMerge) {
                blockCells.add(new ArrayList<>(cellChunks));
                cellChunks.clear();
                cellChunks.add(otherChunk);
            }

            baseChunk = otherChunk;
        }
        blockCells.add(new ArrayList<>(cellChunks));

        return blockCells;
    }

    private static boolean isSparseTextChunkArea(ContentGroupPage page, Table table) {

        List<TextChunk> textChunks = page.getTextChunks(table);
        if (textChunks.isEmpty()) {
            return true;
        }

        List<List<TextChunk>> blockCells = getBlockCells(page, textChunks);
        Map<Integer, List<Rectangle>> rowMaps = calcRowsByChunks(blockCells);
        List<Rectangle> allCells = new ArrayList<>();
        for (Map.Entry<Integer, List<Rectangle>> entry : rowMaps.entrySet()) {
            List<Rectangle> rowChunks = entry.getValue();
            allCells.addAll(rowChunks);
        }

        List<Rectangle> columSpaces = clusColumAreas(page, allCells, table);
        List<Rectangle> rowSpaces = clusRowAreas(page, allCells, table);

        if (rowSpaces.isEmpty() || columSpaces.isEmpty()
                || rowSpaces.size() == 1 || columSpaces.size() == 1) {
            return true;
        }

        int blankCellCnt = 0;
        List<Rectangle> insertAreas = new ArrayList<>();
        for (int row = 0; row < rowSpaces.size(); row++) {
            for (int col = 0; col < columSpaces.size(); col++) {
                Rectangle insertArea = new Rectangle(rowSpaces.get(row).createIntersection(columSpaces.get(col)));

                List<TextChunk> areaChunks = page.getTextChunks(insertArea);
                if (!(!areaChunks.isEmpty() && areaChunks.stream().anyMatch(te -> (te.getCenterX() > insertArea.getLeft()
                        && te.getCenterX() < insertArea.getRight() && te.getCenterY() > insertArea.getTop()
                        && te.getCenterY() < insertArea.getBottom())))) {
                    blankCellCnt++;
                }
                insertAreas.add(insertArea);
            }
        }


        List<TextChunk> otherDirectionChunks = textChunks.stream().filter(te -> (te.getDirection() != TextDirection.LTR)).collect(Collectors.toList());
        List<TextChunk> normalDirectionChunks = textChunks.stream().filter(te -> (te.getDirection() == TextDirection.LTR)).collect(Collectors.toList());
        if ((double)otherDirectionChunks.size() / textChunks.size() > 0.9 && (double)normalDirectionChunks.size() / textChunks.size() < 0.1) {
            return true;
        }


        if (table.getTableType() == TableType.NoLineTable && columSpaces.size() >= 5 && rowSpaces.size() >= 2) {

            if ((double)blankCellCnt / insertAreas.size() >= 0.4) {
                int sparseRowCnt = 0;
                for (int i = 0; i < rowSpaces.size(); i++) {
                    Rectangle currentRow = rowSpaces.get(i);
                    List<Rectangle> rowCells = insertAreas.stream().filter(ce -> (ce.horizontallyOverlapRatio(currentRow) > 0.95
                            && ce.verticalOverlapRatio(currentRow) > 0.95)).collect(Collectors.toList());
                    List<Rectangle> rowBlankCells = rowCells.stream()
                            .filter(ce -> (collectAreaTextChunk(page, ce).isEmpty())).collect(Collectors.toList());
                    if (rowSpaces.size() == 2) {
                        if ((double)rowBlankCells.size() / rowCells.size() >= 0.8) {
                            sparseRowCnt++;
                        }
                    } else {
                        if ((double)rowBlankCells.size() / rowCells.size() >= 0.7) {
                            sparseRowCnt++;
                        }
                    }
                }

                if (rowSpaces.size() == 2) {
                    if ((double)sparseRowCnt / table.getRowCount() >= 0.5) {
                        return true;
                    }
                } else {
                    if ((double)sparseRowCnt / table.getRowCount() >= 0.3) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private static boolean isVaildTableAreaByAnalysisTextStream(ContentGroupPage page, Table table) {

        List<TextChunk> allChunks = page.getTextChunks();

        if (allChunks.isEmpty() || table.getTableType() == TableType.LineTable || table.getColumnCount() < 3) {
            return false;
        }

        Cell lastRowCell = table.getCell(0, 0);
        List<Cell> rowCells = table.getRow(0);
        List<Cell> colCells = table.getCells().stream().filter(ce -> (ce.getCol() == 0)).collect(Collectors.toList());
        boolean isHorizontalStream = false;
        if (rowCells.size() == 2) {
            for (Cell tmpCell : rowCells) {
                if (tmpCell.equals(table.getCell(0, 0))) {
                    continue;
                }
                int lastIndex = lastRowCell.getElements().stream().max(Comparator.comparing(TextChunk::getGroupIndex)).get().getGroupIndex();
                int currentIndex = tmpCell.getElements().stream().min(Comparator.comparing(TextChunk::getGroupIndex)).get().getGroupIndex();

                if (currentIndex > lastIndex && (currentIndex - lastIndex < 10)) {
                    isHorizontalStream = true;
                } else {
                    lastRowCell = tmpCell;
                    isHorizontalStream = false;
                    break;
                }
                lastRowCell = tmpCell;
            }
        }

        Cell firstCell = table.getCell(0, 0);
        if (!isHorizontalStream && colCells.size() >= 2) {
            for (Cell tmpCell : colCells) {
                if (tmpCell.equals(table.getCell(0, 0))) {
                    continue;
                }
                int lastIndex = firstCell.getElements().stream().max(Comparator.comparing(TextChunk::getGroupIndex)).get().getGroupIndex();
                int currentIndex = tmpCell.getElements().stream().min(Comparator.comparing(TextChunk::getGroupIndex)).get().getGroupIndex();

                if (currentIndex > lastIndex && (currentIndex - lastIndex < 10) && lastRowCell != null) {
                    Cell lastColCell = colCells.stream().max(Comparator.comparing(Cell::getRow)).get();
                    int index1 = lastColCell.getElements().stream().max(Comparator.comparing(TextChunk::getGroupIndex)).get().getGroupIndex();
                    int index2 = lastRowCell.getElements().stream().min(Comparator.comparing(TextChunk::getGroupIndex)).get().getGroupIndex();
                    if (index1 < index2 && (index2 - index1 > 10)) {
                        return true;
                    }
                    break;
                }
            }
        }

        return false;
    }

    private static boolean isVaildTableAreaByAnalysisTextChunks(ContentGroupPage page, Table table, List<Rectangle> lineAreas) {

        List<TextChunk> textChunks = page.getTextChunks(table);
        if (textChunks.isEmpty()) {
            return true;
        }

        if (table.getRowCount() == 1 && table.getColumnCount() >= 5
                && table.getTableType() == TableType.NoLineTable) {
            if (table.getConfidence() < 0.3) {
                table.setConfidence(0.5);
            }
            return false;
        }

        List<TextChunk> whiteChunks = textChunks.stream()
                .filter(te -> (te.getElements().stream().anyMatch(t -> (t.getColor().getRGB() == -1)))).collect(Collectors.toList());
        List<TextChunk> removeChunks = new ArrayList<>();
        for (TextChunk chunk : whiteChunks) {
            if (textChunks.stream().anyMatch(te -> (te.intersects(chunk) && !te.equals(chunk)
                    && !te.getElements().stream().anyMatch(t -> (t.getColor().getRGB() == -1))))) {
                removeChunks.add(chunk);
            }
        }
        textChunks.removeAll(removeChunks);
        if (textChunks.isEmpty()) {
            return true;
        }

        List<List<TextChunk>> blockCells = getBlockCells(page, textChunks);
        Map<Integer, List<Rectangle>> rowMaps = calcRowsByChunks(blockCells);

        if (rowMaps.size() > 3 && table.getCellCount() == blockCells.size()) {
            if (table.getConfidence() < 0.5) {
                table.setConfidence(0.9);
            }
            return false;
        }

        // 表格的行数
        int rowNum = rowMaps.size();
        if (rowNum == 0) {
            return true;
        }

        // noNum: 文字串的个数（非数字），maxNumOfRowChunks:所有行的最大列数， minNumOfRowChunks：所有行的最小列数
        double noNum = 0, maxNumOfRowChunks = 0, minNumOfRowChunks = 1000;
        // numOfMutiRowsCell：多行单元格的个数， cellNums：所有单元格的个数
        double numOfMutiRowsCell = 0, cellNums = 0;

        for (Map.Entry<Integer, List<Rectangle>> entry : rowMaps.entrySet()) {
            List<Rectangle> rowChunks = entry.getValue();
            cellNums += rowChunks.size();

            for (Rectangle thunkCell : rowChunks) {
                List<TextChunk> cellChunks = page.getTextChunks(thunkCell);
                if (cellChunks.size() == 1 && !NUM_SERAL.matcher(cellChunks.get(0).getText().trim().toLowerCase()).matches()) {
                    noNum++;
                }
                if (cellChunks.size() >= 2) {
                    TextChunk chunk0 = cellChunks.get(0);
                    if (cellChunks.stream().anyMatch(te -> (te.horizontallyOverlapRatio(chunk0) > 0.5 && !te.equals(chunk0)))) {
                        numOfMutiRowsCell++;
                        cellChunks.sort(Comparator.comparing(TextChunk::getTop));
                    } else {
                        cellChunks.sort(Comparator.comparing(TextChunk::getLeft));
                    }
                    String serialTexts = getSequenceOfChunks(page, cellChunks);
                    if (!serialTexts.equals("") && !NUM_SERAL.matcher(cellChunks.get(0).getText().trim().toLowerCase()).matches()) {
                        noNum++;
                    }
                }
            }

            int size = rowChunks.size();
            if (maxNumOfRowChunks < size) {
                maxNumOfRowChunks = size;
            }

            if (minNumOfRowChunks > size) {
                minNumOfRowChunks = size;
            }
        }

        List<TextChunk> upChunks = upSearchChunks(page, table);
        if (!upChunks.isEmpty()) {
            do {
                TextChunk baseChunk = upChunks.get(0);
                List<TextChunk> clusChunks = upChunks.stream()
                        .filter(te -> (te.verticalOverlapRatio(baseChunk) > 0.5)).collect(Collectors.toList());
                String hederTexts = getSequenceOfChunks(page, clusChunks);
                if (!hederTexts.equals("") && TABLE_SERIAL.matcher(hederTexts.trim().toLowerCase()).matches()) {
                    if (table.getConfidence() < 0.5) {
                        table.setConfidence(0.9);
                    }
                    return false;
                }
                if (!hederTexts.equals("") && DATA_SOURCE_SERIAL.matcher(hederTexts.trim().toLowerCase()).matches()) {
                    break;
                }
                upChunks.removeAll(clusChunks);
            } while (!upChunks.isEmpty());
        }

        boolean isTable = false;
        // 纯文本的小表格
        if (rowNum <= 4 && (noNum / cellNums > 0.9)
                && (table.getHeight() < 6 * page.getAvgCharHeight())) {

            if (numOfMutiRowsCell == 0.0 && maxNumOfRowChunks >= 4
                    && isCoverByLineOfTable(page, table)) {
                if (table.getConfidence() < 0.5) {
                    table.setConfidence(0.9);
                }
                return false;
            }

            List<Ruling> overLapRulings = getOverLapRulingsOfArea(page, table);
            if (overLapRulings.size() == 2) {
                Rectangle tmpArea = new Rectangle(table);
                for (Ruling line : overLapRulings) {
                    tmpArea.merge(line.toRectangle());
                }
                if (table.getTableType() == TableType.NoLineTable
                        && tmpArea.getWidth() > 0.6 * page.getWidth() && isSameThunksArea(page, tmpArea, table)) {
                    isTable = true;
                }
            }

            if (table.getTableType() == TableType.LineTable && blockCells.size() == table.getCellCount()) {
                if (table.getConfidence() < 0.5) {
                    table.setConfidence(0.9);
                }
                return false;
            }

            if (!isTable) {
                return true;
            }
        }

        // 加入字体颜色对比度，白色背景白色字体
        if (rowNum <= 3 && textChunks.size() < 10) {
            TextChunk maxFontSizeChunk = textChunks.stream().max(Comparator.comparing(TextChunk::getMaxFontSize)).get();
            TextChunk minFontSizeChunk = textChunks.stream().min(Comparator.comparing(TextChunk::getMaxFontSize)).get();
            if (minFontSizeChunk.getHeight() / maxFontSizeChunk.getHeight() < 0.7
                    && textChunks.stream().anyMatch(te -> (te.getElements().stream().anyMatch(t -> (t.getColor().getRGB() == -1))))
                    && textChunks.stream().anyMatch(te -> (te.getElements().stream().anyMatch(t -> (t.getColor().getRGB() == -1))))) {
                return true;
            }
        }

        // 多条直线隔开的表格
        if (isCoverByLineOfTable(page, table) && table.getTableType() == TableType.NoLineTable) {
            if (table.getConfidence() < 0.5) {
                table.setConfidence(0.7);
            }
            return false;
        }

        // 多行一列的无线表格
        if (maxNumOfRowChunks == minNumOfRowChunks && minNumOfRowChunks == 1) {
            return true;
        }

        // 单元格只有一行，且表格列数少于3,且单元格无数字串
        if (numOfMutiRowsCell == 0 && maxNumOfRowChunks < 3 && noNum == cellNums && !isTable) {
            return true;
        }

        boolean isCheckeFlag = false;
        // 列数较少，且大部分为文字
        if (maxNumOfRowChunks != minNumOfRowChunks && maxNumOfRowChunks <= 3
                && noNum / cellNums >= 0.75
                && !(rowMaps.size() > 5 && numOfMutiRowsCell == 0)
                && table.getTableType() == TableType.NoLineTable) {
            table.setConfidence(0.3);
            isCheckeFlag = true;
            //TableDebugUtils.writeCells(page, Arrays.asList(table), "xxx_confidence_table");
        }

        // 多行文字单元格较多
        if (!isCheckeFlag && numOfMutiRowsCell / cellNums > 0.75 && noNum / cellNums > 0.75
                && table.getTableType() == TableType.NoLineTable) {
            table.setConfidence(0.3);
            isCheckeFlag = true;
            //TableDebugUtils.writeCells(page, Arrays.asList(table), "xxx_confidence_table");
        }

        if (!isCheckeFlag && table.getTableType() == TableType.NoLineTable && table.getConfidence() < 0.5) {
            table.setConfidence(0.7);
        }

        if (isSparseTextChunkArea(page, table)) {
            return true;
        }

        return false;
    }

    private static boolean isCoverByLineOfTable(ContentGroupPage page, Table table) {

        if (table.getTableType() != TableType.NoLineTable) {
            return false;
        }

        List<Ruling> hLines = page.getHorizontalRulings().stream()
                .filter(hr -> (hr.toRectangle().horizontallyOverlapRatio(table) > 0.9
                        && (hr.intersects(table) || FloatUtils.feq(table.getTop(), hr.getY1(), 4.f)
                        || FloatUtils.feq(table.getBottom(), hr.getY1(), 4.f)))).collect(Collectors.toList());

        for (Ruling hline : hLines) {
            if (page.getVerticalRulings().stream().anyMatch(vr -> (vr.intersectsLine(hline)
                    && vr.getHeight() > page.getAvgCharHeight()))) {
                return false;
            }
        }

        if (!hLines.isEmpty() && hLines.size() >= 3) {
            hLines.sort(Comparator.comparing(Ruling::getTop));
            Ruling base = hLines.get(0);
            int sameCnt = 0;
            for (int i = 1; i < hLines.size(); i++) {
                Ruling other = hLines.get(i);
                if (FastMath.abs(base.getCenterY() - other.getCenterY()) > page.getAvgCharHeight()) {
                    sameCnt++;
                }
                base = other;
            }
            if (sameCnt == hLines.size() - 1) {
                return true;
            }
        }

        return false;
    }

    private static List<Rectangle> clusRowAreas(ContentGroupPage page, List<Rectangle> candidateRects, Rectangle table) {

        if (candidateRects.isEmpty()) {
            return new ArrayList<>();
        }

        candidateRects.sort(Comparator.comparing(Rectangle::getTop));
        List<Rectangle> targetRects = new ArrayList<>();
        Rectangle baseRect = new Rectangle(candidateRects.get(0));
        for (int i = 1; i < candidateRects.size(); i++) {
            Rectangle otherRect = new Rectangle(candidateRects.get(i));
            if (baseRect.verticalOverlapRatio(otherRect) > 0.5) {
                baseRect.merge(otherRect);
            } else {
                targetRects.add(baseRect);
                baseRect = otherRect;
            }
        }
        targetRects.add(baseRect);

        targetRects.stream().forEach(re -> re.setRect(table.getLeft(), re.getTop(), table.getWidth(), re.getHeight()));

        return targetRects;
    }

    private static List<Rectangle> clusColumAreas(ContentGroupPage page, List<Rectangle> candidateRects, Rectangle table) {

        if (candidateRects.isEmpty()) {
            return new ArrayList<>();
        }
        candidateRects.sort(Comparator.comparing(Rectangle::getLeft));
        List<Rectangle> targetRects = new ArrayList<>();
        Rectangle baseRect = new Rectangle(candidateRects.get(0));
        for (int i = 1; i < candidateRects.size(); i++) {
            Rectangle otherRect = new Rectangle(candidateRects.get(i));
            if (baseRect.horizontallyOverlapRatio(otherRect) > 0.5) {
                baseRect.merge(otherRect);
            } else {
                targetRects.add(baseRect);
                baseRect = otherRect;
            }
        }
        targetRects.add(baseRect);

        targetRects.stream().forEach(re -> re.setRect(re.getLeft(), table.getTop(), re.getWidth(), table.getHeight()));

        return targetRects;
    }

    private static boolean isHaveDiffTypeChunks(ContentGroupPage page, Table table) {

        List<TextChunk> textChunks = page.getTextChunks(table).stream()
                .filter(te -> (te.getElements().stream()
                        .anyMatch(t -> (!FloatUtils.feq(t.getColor().getRGB(), -1, 10))))).collect(Collectors.toList());
        if (textChunks.isEmpty()) {
            return true;
        }

        Map<Float, TextChunk> fontMaps = new HashMap<>();
        fontMaps.put(textChunks.get(0).getMaxFontSize(), textChunks.get(0));
        for (TextChunk chunk : textChunks) {
            if (fontMaps.values().stream().anyMatch(te -> (te.getMaxFontSize() == chunk.getMaxFontSize()))
                    || chunk.isHiddenOrBlank() || NUM_SERAL.matcher(chunk.getText().trim().toLowerCase()).matches()) {
                continue;
            } else {
                fontMaps.put(chunk.getFontSize(), chunk);
            }
        }

        if (fontMaps.size() >= 2) {
            float maxFont = fontMaps.keySet().stream().max(Comparator.comparing(Float::doubleValue)).get();
            float minFont = fontMaps.keySet().stream().min(Comparator.comparing(Float::doubleValue)).get();
            if (maxFont / minFont >= 2.f && table.getHeight() < 4 * fontMaps.get(maxFont).getHeight()) {
                return true;
            }

            if (table.getRowCount() < 4 && table.getColumnCount() < 5) {

                TextChunk maxChunk = fontMaps.get(maxFont);
                TextChunk minChunk = fontMaps.get(minFont);
                TextChunk maxWidthChunk = (maxChunk.getWidth() > minChunk.getWidth()) ? maxChunk : minChunk;
                TextChunk minWidthChunk = (maxChunk.getWidth() > minChunk.getWidth()) ? minChunk : maxChunk;
                if (minFont / maxFont < 0.7 && minWidthChunk.getWidth() / maxWidthChunk.getWidth() < 0.3) {
                    return true;
                }
            }
        }

        return false;
    }

    private static List<TextChunk> collectAreaTextChunk(ContentGroupPage page, Rectangle area) {

        List<TextChunk> chunks = page.getTextChunks(area);
        List<TextChunk> textChunks = new ArrayList<>();

        for (TextChunk te : chunks) {
            Rectangle2D intersectBounds = area.createIntersection(te.getBounds2D());
            float area1 = (float) (intersectBounds.getWidth() * intersectBounds.getHeight());
            float area2 = te.getArea();
            if (area1 / area2 > 0.5 && !te.isHiddenOrBlank()) {
                textChunks.add(te);
            }
        }
        return textChunks;
    }

    // 包含坐标线，数字坐标值，具备稀疏特性，且分类为无线表格
    private static boolean isChartStyle(ContentGroupPage page, Table table, List<Rectangle> rulingAreas) {

        List<TextChunk> tableChunks = page.getTextChunks(table).stream()
                .filter(te -> (NUM_SERAL.matcher(te.getText().trim().toLowerCase()).matches())).collect(Collectors.toList());
        if (/*table.getTableType() == TableType.LineTable || */tableChunks.isEmpty()) {
            return false;
        }

        Rectangle clusLeftArea = null;
        TextChunk leftChunk = tableChunks.stream().min(Comparator.comparing(TextChunk::getLeft)).get();
        List<TextChunk> clusLeftChunks = tableChunks.stream()
                .filter(te -> (te.horizontallyOverlapRatio(leftChunk) > 0.7)).collect(Collectors.toList());
        List<TextChunk> textChunks = new ArrayList<>(page.getTextChunks(table));
        textChunks.removeAll(tableChunks);
        if (clusLeftChunks.size() > 3) {
            clusLeftArea = getTextAreaByChunks(clusLeftChunks);
            Rectangle finalClusLeftArea = clusLeftArea;
            if (textChunks.stream().anyMatch(te -> (te.getLeft() < finalClusLeftArea.getLeft()
                    && te.verticalOverlapRatio(finalClusLeftArea) > 0.9))) {
                clusLeftArea = null;
            }

            if (clusLeftArea != null && table.getTableType() == TableType.LineTable
                    && table.nearlyContains(clusLeftArea)) {
                clusLeftArea = null;
            }
        }

        if (clusLeftArea == null) {
            // 左侧是否有数字串
            List<TextChunk> allChunks = page.getTextChunks().stream().filter(te -> (te.getRight() < leftChunk.getLeft()
                    && NUM_SERAL.matcher(te.getText().trim().toLowerCase()).matches()
                    && !te.isHorizontallyOverlap(leftChunk)
                    && te.horizontalDistance(leftChunk) < 5 * page.getAvgCharWidth()
                    && (te.getTop() > table.getTop() && te.getBottom() < table.getBottom()))).collect(Collectors.toList());
            if (!allChunks.isEmpty()) {
                TextChunk finalLeftChunk = allChunks.stream().min(Comparator.comparing(te -> (leftChunk.horizontalDistance(te)))).get();
                clusLeftChunks = allChunks.stream().filter(te -> (te.horizontallyOverlapRatio(finalLeftChunk) > 0.8)).collect(Collectors.toList());
                if (clusLeftChunks.size() > 3) {
                    clusLeftArea = getTextAreaByChunks(clusLeftChunks);
                }
            }
        }

        Rectangle clusRightArea = null;
        TextChunk rightChunk = tableChunks.stream().max(Comparator.comparing(TextChunk::getRight)).get();
        List<TextChunk> clusRightChunks = tableChunks.stream()
                .filter(te -> (te.horizontallyOverlapRatio(rightChunk) > 0.7)).collect(Collectors.toList());
        if (clusRightChunks.size() > 3) {
            clusRightArea = getTextAreaByChunks(clusRightChunks);
            Rectangle finalClusRightArea = clusRightArea;
            if (textChunks.stream().anyMatch(te -> (te.getRight() > finalClusRightArea.getRight()
                    && te.verticalOverlapRatio(finalClusRightArea) > 0.9))) {
                clusRightArea = null;
            }

            if (clusRightArea != null && table.getTableType() == TableType.LineTable
                    && table.nearlyContains(clusRightArea)) {
                clusLeftArea = null;
            }
        }

        Rectangle clusBottomArea = null;
        if (clusLeftArea != null) {
            Rectangle clusBottomArea0 = null;
            if (clusRightArea != null) {
                float top = (clusLeftArea.getBottom() > clusRightArea.getBottom()) ? clusLeftArea.getBottom() : clusRightArea.getBottom();
                clusBottomArea0 = new Rectangle(clusLeftArea.getLeft(), top, table.getWidth(), table.getBottom() - top);
            } else {
                float top = clusLeftArea.getBottom();
                clusBottomArea0 = new Rectangle(clusLeftArea.getLeft(), top, table.getWidth(), table.getBottom() - top);
            }
            if (clusBottomArea0 != null) {
                List<TextChunk> bottomChunks = collectAreaTextChunk(page, clusBottomArea0);
                bottomChunks.sort(Comparator.comparing(TextChunk::getTop));
                List<TextChunk> bottomChunks1 = bottomChunks.stream()
                        .filter(te -> (te.verticalOverlapRatio(bottomChunks.get(0)) > 0.8)).collect(Collectors.toList());
                if (bottomChunks1.size() > 1) {
                    clusBottomArea = getTextAreaByChunks(bottomChunks1);
                }
            }
        }
        boolean isExistbottom = false;
        if (clusBottomArea != null && isOneRowChunks(page.getTextChunks(clusBottomArea))) {
            isExistbottom = true;
        }

        if (clusLeftArea != null) {

            if (clusRightArea != null && rulingAreas.size() == 1 && rulingAreas.get(0).nearlyContains(table, 2.f)
                    && table.nearlyContains(clusLeftArea) && table.nearlyContains(clusRightArea)) {
                return false;
            }

            if (clusRightArea!= null && clusLeftArea.verticalOverlapRatio(clusRightArea) > 0.8
                    && isExistVerticalNearlyRuling(page, clusRightArea, false)) {
                return true;
            }

            if (clusRightArea == null
                    && isExistVerticalNearlyRuling(page, clusLeftArea, true)) {
                return true;
            }

            if (isExistbottom && isExistVerticalNearlyRuling(page, clusLeftArea, true)
                    && isExistHorizontalNearlyRuling(page, clusBottomArea,false)) {

                return true;
            }
        }
        return false;
    }

    private static boolean isExistVerticalNearlyRuling(ContentGroupPage page, Rectangle numArea, boolean isAtLeft) {

        List<Ruling> vRs = page.getVerticalRulings().stream()
                .filter(vr -> (vr.getHeight() > 2 * page.getAvgCharHeight())).collect(Collectors.toList());
        if (vRs.isEmpty()) {
            return false;
        }

        if (isAtLeft) {
            // 数字区域位于左边，右边是否存在直线
            if (vRs.stream().anyMatch(vr -> (vr.toRectangle().verticalOverlapRatio(numArea) > 0.8
                    && ((vr.getX1() > numArea.getRight()
                    && vr.toRectangle().horizontalDistance(numArea) < 5 * page.getAvgCharWidth()
                    && !isHaveThunksBetweenGap(page, numArea, vr.toRectangle(), page.getTextChunks()))
                    || FloatUtils.feq(vr.getX1(), numArea.getRight(), 4.f))))) {
                return true;
            }
        } else {
            // 数字区域位于右边，左边是否存在直线
            if (vRs.stream().anyMatch(vr -> (vr.toRectangle().verticalOverlapRatio(numArea) > 0.8
                    && ((vr.getX1() < numArea.getLeft()
                    && vr.toRectangle().horizontalDistance(numArea) < 5 * page.getAvgCharWidth()
                    && !isHaveThunksBetweenGap(page, numArea, vr.toRectangle(), page.getTextChunks()))
                    || FloatUtils.feq(vr.getX1(), numArea.getLeft(), 4.f))))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isExistHorizontalNearlyRuling(ContentGroupPage page, Rectangle textArea, boolean isAtTop) {

        List<Ruling> hRs = page.getHorizontalRulings().stream()
                .filter(hr -> (hr.getWidth() > 2 * page.getAvgCharWidth())).collect(Collectors.toList());
        if (hRs.isEmpty()) {
            return false;
        }

        if (isAtTop) {
            // 文本区域位于上边，下方是否存在直线
            if (hRs.stream().anyMatch(hr -> (hr.toRectangle().horizontallyOverlapRatio(textArea) > 0.8
                    && ((hr.getY1() > textArea.getBottom()
                    && hr.toRectangle().verticalDistance(textArea) < 3 * page.getAvgCharHeight()
                    && !isHaveThunksBetweenGap(page, textArea, hr.toRectangle(), page.getTextChunks()))
                    || FloatUtils.feq(hr.getY1(), textArea.getBottom(), 4.f))))) {
                return true;
            }
        } else {
            // 文本区域位于下边，上方是否存在直线
            if (hRs.stream().anyMatch(hr -> (hr.toRectangle().horizontallyOverlapRatio(textArea) > 0.8
                    && ((hr.getY1() < textArea.getTop()
                    && hr.toRectangle().verticalDistance(textArea) < 3 * page.getAvgCharHeight()
                    && !isHaveThunksBetweenGap(page, textArea, hr.toRectangle(), page.getTextChunks()))
                    || FloatUtils.feq(hr.getY1(), textArea.getTop(), 4.f))))) {
                return true;
            }
        }

        return false;
    }


    private static boolean isSameThunksArea(ContentGroupPage page, Rectangle one, Rectangle other) {

        if (one.overlapRatio(other) > 0) {
            List<TextChunk> oneChunks = collectAreaTextChunk(page, one);
            List<TextChunk> otherChunks = collectAreaTextChunk(page, other);
            if (oneChunks.size() == otherChunks.size()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPossibleChartAreas(ContentGroupPage page, Table table, List<Rectangle> lineAreas) {

        if (page.getTextChunks(table).isEmpty()) {
            return true;
        }

        if (isChartStyle(page, table, lineAreas)) {
            return true;
        }

        // 存在两条水平直线将疑似table区域包络
        List<Ruling> targetRulings = getOverLapRulingsOfArea(page, table);
        if (targetRulings.size() == 2) {

            List<TextChunk> allChunks = page.getTextChunks();
            List<TextChunk> upChunks = allChunks.stream().filter(te -> (((te.getBottom() < targetRulings.get(0).getTop()
                    && !isHaveThunksBetweenGap(page, te, targetRulings.get(0).toRectangle(), allChunks))
                    || FloatUtils.feq(te.getBottom(), targetRulings.get(0).getTop(), 4.f))
                    && te.horizontallyOverlapRatio(targetRulings.get(0).toRectangle()) > 0.9)).collect(Collectors.toList());
            if (!upChunks.isEmpty()) {
                upChunks.sort(Comparator.comparing(TextChunk::getBottom).reversed());
                List<TextChunk> finalUpChunks = upChunks;
                upChunks = upChunks.stream().filter(te -> (te.verticalOverlapRatio(finalUpChunks.get(0)) > 0.7)).collect(Collectors.toList());
                String chartHeaderTexts = getSequenceOfChunks(page, upChunks);
                Rectangle lineRect = new Rectangle(targetRulings.get(0).toRectangle());
                lineRect.merge(targetRulings.get(1).toRectangle());
                if (!chartHeaderTexts.equals("") && CHART_SERIAL3.matcher(chartHeaderTexts.trim().toLowerCase()).matches()) {

                    if (isContainTableText(page, table) && lineRect.getWidth() > table.getWidth() * 0.5
                            && lineRect.getHeight() > table.getHeight() * 0.5
                            && isSameThunksArea(page, lineRect, table) /*&& !isChart*/) {
                        return false;
                    }
                    return true;
                }

                if (!chartHeaderTexts.equals("") && CHART_SERIAL1.matcher(chartHeaderTexts.trim().toLowerCase()).matches()
                        && !isSameThunksArea(page, lineRect, table)) {
                    return true;
                }
            }
        }

        if ((splitChartAreas(page, table).size() == 2 || isPartOfChart(page, table))) {
            return true;
        }

        List<TextChunk> headerChunks = upSearchChunks(page, table);
        if (!headerChunks.isEmpty()) {
            headerChunks.sort(Comparator.comparing(TextChunk::getBottom).reversed());
            List<TextChunk> finalHeaderChunks = headerChunks;
            headerChunks = headerChunks.stream()
                    .filter(te -> (te.verticalOverlapRatio(finalHeaderChunks.get(0)) > 0.8)).collect(Collectors.toList());
            if (!headerChunks.isEmpty() && page.getHorizontalRulings().stream()
                    .anyMatch(hr -> (hr.getY1() > finalHeaderChunks.get(0).getCenterY()
                            && hr.getWidth() > getTextAreaByChunks(finalHeaderChunks).getWidth() * 0.5
                            && (hr.getCenterY() < table.getTop()
                            || FloatUtils.feq(hr.getCenterY(), table.getTop(), 4.f))))) {
                String headerTexts = getSequenceOfChunks(page, headerChunks);
                if (!headerTexts.equals("") && CHART_SERIAL3.matcher(headerTexts.toLowerCase()).matches()) {
                    if (isContainTableText(page, table) /*&& !isChart*/) {
                        return false;
                    }
                    return true;
                }

                /*if (!headerTexts.equals("") && CHART_SERIAL1.matcher(headerTexts.trim().toLowerCase()).matches()
                        && isChart) {
                    return true;
                }*/
            }
        }

        if (lineAreas.size() == 1 && lineAreas.get(0).nearlyContains(table, 2.f)
                && !isSameThunksArea(page, table, lineAreas.get(0))) {
            headerChunks = upSearchChunks(page, lineAreas.get(0));
            headerChunks.sort(Comparator.comparing(TextChunk::getBottom).reversed());
            List<TextChunk> finalHeaderChunks = headerChunks;
            headerChunks = headerChunks.stream()
                    .filter(te -> (te.verticalOverlapRatio(finalHeaderChunks.get(0)) > 0.8)).collect(Collectors.toList());
            if (!headerChunks.isEmpty() && page.getHorizontalRulings().stream()
                    .anyMatch(hr -> (hr.getY1() > finalHeaderChunks.get(0).getCenterY()
                            && hr.getWidth() > getTextAreaByChunks(finalHeaderChunks).getWidth() * 0.5
                            && (hr.getCenterY() < table.getTop()
                            || FloatUtils.feq(hr.getCenterY(), table.getTop(), 4.f))))) {
                String headerTexts = getSequenceOfChunks(page, headerChunks);
                if (!headerTexts.equals("") && (CHART_SERIAL3.matcher(headerTexts.toLowerCase()).matches()
                        || CHART_SERIAL1.matcher(headerTexts.trim().toLowerCase()).matches())) {
                    return true;
                }
            }
        }

        List<FillAreaGroup> rectFillGroups = page.getPageFillAreaGroup().stream().filter(fill -> (fill.getGroupAreaType() == FillAreaGroup.AreaType.BAR_AREA
                && table.nearlyContains(fill.getGroupRectArea())
                && fill.getGroupRectArea().getWidth() / fill.getGroupRectArea().getHeight() < 0.3
                && page.getTextChunks(fill.getGroupRectArea()).isEmpty()
                && fill.getGroupRectArea().getHeight() > 3 * page.getAvgCharHeight())).collect(Collectors.toList());

        List<FillAreaGroup> sectorFillGroups = page.getPageFillAreaGroup().stream().filter(fill -> (fill.getGroupFillAreas().size() == 1
                && fill.getGroupAreaType() == FillAreaGroup.AreaType.PIE_AREA
                && table.nearlyContains(fill.getGroupRectArea())
                && FloatUtils.feq(fill.getGroupRectArea().getWidth(), fill.getGroupRectArea().getHeight(), 10.f)
                && (fill.getGroupRectArea().getWidth() / fill.getGroupRectArea().getHeight() > 0.9 ))).collect(Collectors.toList());

        if (rectFillGroups.size() >= 3 || sectorFillGroups.size() > 1) {
            if (rectFillGroups.stream().allMatch(fill -> (fill.getGroupRectArea().getHeight() / table.getHeight() < 0.2
                    && fill.getGroupRectArea().getWidth() < 8.f))) {
                return false;
            }
            return true;
        }

        return false;
    }

    private static boolean isContainTableText(ContentGroupPage page, Table table) {

        if (table.getTableType() == TableType.NoLineTable && isSparseTextChunkArea(page, table)) {
            return false;
        }

        return true;
    }

    public static String getSequenceOfChunks(ContentGroupPage page, List<TextChunk> allChunks) {

        StringBuffer buf = new StringBuffer();
        if (allChunks.isEmpty() || !isOneRowChunks(allChunks)) {
            return buf.toString();
        }
        List<TextChunk> chunks = new ArrayList<>(allChunks);
        chunks.sort(Comparator.comparing(Rectangle::getLeft));

        for (TextChunk text : chunks) {
            buf.append(text.getText());
        }
        String serialTexts = buf.toString();
        return serialTexts;
    }

    private static boolean isPartOfChart(ContentGroupPage page, Table table) {

        List<TableRegion> lineAreas = RulingTableRegionsDetectionAlgorithm.findRulingRectangles(page);
        if (lineAreas.isEmpty()) {
            return false;
        }

        List<TextChunk> textChunks = page.getTextChunks();
        //TableDebugUtils.writeCells(page, Arrays.asList(table), "yyy_tableArea");
        for (Rectangle lineRect : lineAreas) {
            //TableDebugUtils.writeCells(page, Arrays.asList(lineRect), "yyy_lineRect0");

            List<TextChunk> upChunks = textChunks.stream().filter(re -> (re.getHeight() < 2 * page.getAvgCharHeight()
                    && table.nearlyEquals(lineRect, 4.f)
                    && ((re.getBottom() < lineRect.getTop()
                    && !isHaveThunksBetweenGap(page, lineRect, re, textChunks)
                    && lineRect.verticalDistance(re) < 2 * page.getAvgCharHeight())
                    || FloatUtils.feq(re.getBottom(), lineRect.getTop(), 4.f)))).collect(Collectors.toList());

            if (upChunks.size() >= 2) {

                upChunks.sort(Comparator.comparing(TextChunk::getBottom).reversed());
                List<TextChunk> clusChunks = upChunks.stream()
                        .filter(te -> (te.verticalOverlapRatio(upChunks.get(0)) > 0.5)).collect(Collectors.toList());
                if (clusChunks.size() < 2) {
                    return false;
                }

                clusChunks.sort(Comparator.comparing(TextChunk::getLeft));
                TextChunk chunks1 = clusChunks.get(0);
                boolean isHeader = CHART_TABLE_SPLIT_SERIAL.matcher(chunks1.getText().toLowerCase()).matches();
                if (isHeader) {
                    TextChunk secondChunk = null;
                    for (TextChunk chunk : clusChunks) {
                        if (chunk.equals(chunks1)) {
                            continue;
                        }
                        if (CHART_TABLE_SPLIT_SERIAL.matcher(chunk.getText().toLowerCase()).matches()) {
                            secondChunk = chunk;
                            break;
                        }
                    }

                    if (secondChunk != null) {
                        TextChunk finalSecondChunk = secondChunk;
                        List<TextChunk> firstChunks = clusChunks.stream().filter(te -> (te.getRight() < finalSecondChunk.getLeft()
                                || FloatUtils.feq(te.getRight(), finalSecondChunk.getLeft(), 4.f))).collect(Collectors.toList());

                        clusChunks.removeAll(firstChunks);
                        if (!firstChunks.isEmpty() && !clusChunks.isEmpty()) {
                            String chartText1 = getSequenceOfChunks(page, firstChunks);
                            String chartText2 = getSequenceOfChunks(page, clusChunks);
                            if ((!chartText1.equals("") && CHART_SERIAL3.matcher(chartText1.toLowerCase()).matches())
                                    && (!chartText2.equals("") && CHART_SERIAL3.matcher(chartText2.toLowerCase()).matches())) {
                                return true;
                            }
                        }
                    }
                }
            }
        }

        return false;
    }

    private static List<Rectangle> splitChartAreas(ContentGroupPage page, Rectangle tableArea) {

        List<TextChunk> tableChunks = page.getTextChunks(tableArea);
        if (tableChunks.isEmpty()) {
            return new ArrayList<>();
        }

        List<Rectangle> targetAreas = new ArrayList<>();

        List<TextChunk> areaChunks = new ArrayList<>(tableChunks);

        //TableDebugUtils.writeCells(page, Arrays.asList(candidateArea), "yyy_candidateArea");
        areaChunks.sort(Comparator.comparing(TextChunk::getTop));
        List<TextChunk> clusChunks = areaChunks.stream()
                .filter(te -> (te.verticalOverlapRatio(areaChunks.get(0)) > 0.5)).collect(Collectors.toList());
        clusChunks.sort(Comparator.comparing(TextChunk::getLeft));

        List<Rectangle> splitAreas = null;
        if (clusChunks.size() >= 2) {
            TextChunk firstChunk = clusChunks.get(0);
            boolean isHeader = CHART_SERIAL3.matcher(firstChunk.getText().toLowerCase()).matches();
            if (isHeader) {
                TextChunk secondChunk = null;
                for (TextChunk chunk : clusChunks) {
                    if (chunk.equals(firstChunk)) {
                        continue;
                    }
                    if (CHART_SERIAL3.matcher(chunk.getText().toLowerCase()).matches()) {
                        secondChunk = chunk;
                        break;
                    }
                }
                if (secondChunk != null) {
                    TextChunk finalSecondChunk = secondChunk;
                    List<TextChunk> chunks1 = clusChunks.stream().filter(te -> (te.getRight() < finalSecondChunk.getLeft())).collect(Collectors.toList());
                    clusChunks.removeAll(chunks1);
                    splitAreas = getChartSplitAreas(page, chunks1, clusChunks, tableArea);
                    if (splitAreas.size() == 2) {
                        targetAreas.addAll(splitAreas);
                    }
                }
            }
        }
        if (splitAreas == null || splitAreas.isEmpty()) {
            targetAreas.add(tableArea);
        }

        return targetAreas;
    }

    private static List<Rectangle> getChartSplitAreas(ContentGroupPage page, List<TextChunk> baseChunks,
                                                      List<TextChunk> otherChunks, Rectangle tableArea) {
        if (baseChunks.isEmpty() || otherChunks.isEmpty()
                || (baseChunks.get(0).verticalOverlapRatio(otherChunks.get(0)) < 0.5)) {
            return new ArrayList<>();
        }

        List<Rectangle> targetAreas = new ArrayList<>();
        String baseTexts = getSequenceOfChunks(page, baseChunks);
        String otherTexts = getSequenceOfChunks(page, otherChunks);
        if ((!baseTexts.equals("") && (CHART_SERIAL2.matcher(baseTexts.trim().toLowerCase()).matches()
                || CHART_SERIAL3.matcher(baseTexts.trim().toLowerCase()).matches()))
                && (!otherTexts.equals("") && (CHART_SERIAL2.matcher(otherTexts.trim().toLowerCase()).matches()
                || CHART_SERIAL3.matcher(otherTexts.trim().toLowerCase()).matches()))) {

            float left, top, width, height;
            left = baseChunks.stream().min(Comparator.comparing(Rectangle::getLeft)).get().getLeft();
            top = tableArea.getTop();
            width = baseChunks.stream().max(Comparator.comparing(Rectangle::getRight)).get().getRight() - left;
            height = baseChunks.stream().max(Comparator.comparing(Rectangle::getBottom)).get().getBottom() - top;
            Rectangle baseRect = new Rectangle(left, top, width, height);

            left = otherChunks.stream().min(Comparator.comparing(Rectangle::getLeft)).get().getLeft();
            width = otherChunks.stream().max(Comparator.comparing(Rectangle::getRight)).get().getRight() - left;
            height = otherChunks.stream().max(Comparator.comparing(Rectangle::getBottom)).get().getBottom() - top;
            Rectangle otherRect = new Rectangle(left, top, width, height);
            List<TextChunk> allChunks = page.getTextChunks(tableArea);

            if (!allChunks.isEmpty()) {
                List<TextChunk> chunks1 = allChunks.stream().filter(te -> (!te.isHorizontallyOverlap(otherRect)
                        && te.getRight() < otherRect.getLeft())).collect(Collectors.toList());
                float maxRight = chunks1.stream().max(Comparator.comparing(Rectangle::getRight)).get().getRight();
                baseRect.setRect(tableArea.getLeft(), top, maxRight - tableArea.getLeft(), tableArea.getHeight());
                List<TextChunk> chunks2 = allChunks.stream().filter(te -> (!te.isHorizontallyOverlap(baseRect)
                        && te.getLeft() > baseRect.getRight())).collect(Collectors.toList());
                float minLeft = chunks2.stream().min(Comparator.comparing(Rectangle::getLeft)).get().getLeft();
                otherRect.setRect(minLeft, top, tableArea.getRight() - minLeft, tableArea.getHeight());

                chunks1 = new ArrayList<>(page.getTextChunks(baseRect));
                chunks2 = new ArrayList<>(page.getTextChunks(otherRect));
                if (!chunks1.isEmpty() && !chunks2.isEmpty()) {
                    chunks1.sort(Comparator.comparing(TextChunk::getBottom).reversed());
                    List<TextChunk> finalChunks = chunks1;
                    chunks1 = chunks1.stream().filter(te -> (te.verticalOverlapRatio(finalChunks.get(0)) > 0.5)).collect(Collectors.toList());
                    chunks2.sort(Comparator.comparing(TextChunk::getBottom).reversed());
                    List<TextChunk> finalChunks1 = chunks2;
                    chunks2 = chunks2.stream().filter(te -> (te.verticalOverlapRatio(finalChunks1.get(0)) > 0.5)).collect(Collectors.toList());
                    String tailTexts1 = getSequenceOfChunks(page, chunks1);
                    String tailTexts2 = getSequenceOfChunks(page, chunks2);
                    if ((!tailTexts1.equals("") && (DATA_SOURCE_SERIAL.matcher(tailTexts1.trim().toLowerCase()).matches()))
                            && (tailTexts2.equals("") && (DATA_SOURCE_SERIAL.matcher(tailTexts2.trim().toLowerCase()).matches()))) {
                        targetAreas.add(baseRect);
                        targetAreas.add(otherRect);
                    }
                }
                if (targetAreas.isEmpty()
                        && RulingTableRegionsDetectionAlgorithm.findRulingRectangles(page).stream().anyMatch(re -> (tableArea.nearlyContains(re)
                        && (tableArea.getArea() / re.getArea() > 0.8 || re.getArea() / tableArea.getArea() > 0.8)))) {
                    targetAreas.add(baseRect);
                    targetAreas.add(otherRect);
                }
            }
        }
        return targetAreas;
    }

    private static List<Ruling> getOverLapRulingsOfArea(ContentGroupPage page, Table table) {

        if (page.getHorizontalRulings().isEmpty()) {
            return new ArrayList<>();
        }

        // 存在两条水平直线将疑似table区域包络
        List<Ruling> hRulings = page.getHorizontalRulings().stream()
                .filter(hr -> (hr.getWidth() > table.getWidth() / 2
                        && (((hr.getTop() < table.getTop() && hr.toRectangle().verticalDistance(table) < 10 * page.getAvgCharHeight())
                        || FloatUtils.feq(hr.getTop(), table.getTop(), 4.f))
                        || ((hr.getBottom() > table.getBottom() && hr.toRectangle().verticalDistance(table) < 10 * page.getAvgCharHeight())
                        || FloatUtils.feq(hr.getBottom(), table.getBottom(), 4.f)))
                        && table.horizontallyOverlapRatio(hr.toRectangle()) > 0.95)).collect(Collectors.toList());
        if (!hRulings.isEmpty()) {
            List<Ruling> vRulings = page.getVerticalRulings().stream()
                    .filter(vr -> (vr.getHeight() > table.getHeight() / 2)).collect(Collectors.toList());
            List<Ruling> removeRulings = new ArrayList<>();
            if (!vRulings.isEmpty()) {
                for (Ruling hline : hRulings) {
                    if (vRulings.stream().anyMatch(vr -> (vr.intersectsLine(hline)))) {
                        removeRulings.add(hline);
                    }
                }
            }
            hRulings.removeAll(removeRulings);
        }
        List<Ruling> targetRulings = new ArrayList<>();
        List<Ruling> topRulings = new ArrayList<>();
        List<Ruling> bottomRulings = new ArrayList<>();
        if (hRulings.size() >= 2) {
            topRulings.addAll(hRulings.stream().filter(hr -> (hr.getTop() < table.getTop()
                    || FloatUtils.feq(hr.getTop(), table.getTop(), 4.f))).collect(Collectors.toList()));
            bottomRulings.addAll(hRulings.stream().filter(hr -> (hr.getBottom() > table.getBottom()
                    || FloatUtils.feq(hr.getBottom(), table.getBottom(), 4.f))).collect(Collectors.toList()));
        }

        if (topRulings.size() >= 1) {

            topRulings.sort(Comparator.comparing(Ruling::getBottom).reversed());
            Rectangle newArea = null;
            for (Ruling topLine : topRulings) {
                Rectangle newArea1 = new Rectangle(table).merge(topLine.toRectangle());
                List<TextChunk> areaChunks = collectAreaTextChunk(page, newArea1);
                if (areaChunks.stream().anyMatch(te -> (DATA_SOURCE_SERIAL.matcher(te.getText().trim().toLowerCase()).matches()
                        && (te.getBottom() < table.getTop() || FloatUtils.feq(te.getBottom(), table.getTop(), 4.f))))) {
                    return targetRulings;
                }
                List<TextChunk> upChunks = upSearchChunks(page, newArea1);
                List<TextChunk> clusChunks = upChunks.stream()
                        .filter(te -> (te.verticalOverlapRatio(upChunks.get(0)) > 0.8)).collect(Collectors.toList());
                String serials = getSequenceOfChunks(page, clusChunks);

                if (!serials.equals("") && CHART_TABLE_SPLIT_SERIAL.matcher(serials.trim().toLowerCase()).matches()) {
                    targetRulings.add(topLine);
                    newArea = newArea1;
                    break;
                }
            }

            if (targetRulings.size() == 1 && newArea != null) {
                if (!bottomRulings.isEmpty()) {
                    bottomRulings.sort(Comparator.comparing(Ruling::getTop));
                }
                Ruling topLine = targetRulings.get(0);
                for (Ruling bottomLine : bottomRulings) {
                    if ((topLine.getWidth() > table.getWidth() || FloatUtils.feq(topLine.getWidth(), table.getWidth(), 10.f))
                            && FloatUtils.feq(topLine.getWidth(), bottomLine.getWidth(), 4.f)
                            && FloatUtils.feq(topLine.getLeft(), bottomLine.getLeft(), 2.f)
                            && FloatUtils.feq(topLine.getRight(), bottomLine.getRight(), 2.f)) {
                        targetRulings.add(bottomLine);
                        break;
                    }
                }

                if (targetRulings.size() == 1) {
                    List<TextChunk> downChunks = downSearchChunks(page, newArea);
                    for (TextChunk chunk : downChunks) {
                        List<TextChunk> clusDownChunks = downChunks.stream()
                                .filter(te -> (te.verticalOverlapRatio(chunk) > 0.8)).collect(Collectors.toList());
                        String downSerials = getSequenceOfChunks(page, clusDownChunks);
                        if (!downSerials.equals("") && DATA_SOURCE_SERIAL.matcher(downSerials.trim().toLowerCase()).matches()) {
                            Ruling bottomLine = new Ruling(chunk.getTop(), topLine.getLeft(), topLine.getWidth(), topLine.getHeight());
                            targetRulings.add(bottomLine);
                            break;
                        }
                    }
                }
            }
        }
        return targetRulings;
    }

    private static List<TableRegion> correctTableRegions(ContentGroupPage page, List<TextChunk> textChunks, TableRegion table) {

        if (textChunks.isEmpty()) {
            return new ArrayList<>();
        }

        List<TableRegion> tableRegions = new ArrayList<>();

        return tableRegions;
    }
}
