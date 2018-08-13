package com.abcft.pdfextract.core.table.extractors;
import com.abcft.pdfextract.core.model.*;
import com.abcft.pdfextract.core.table.*;
import com.abcft.pdfextract.core.table.detectors.PostProcessTableRegionsAlgorithm;
import com.abcft.pdfextract.core.table.detectors.RulingTableRegionsDetectionAlgorithm;
import com.abcft.pdfextract.core.util.NumberUtil;
import com.abcft.pdfextract.util.FloatUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.math3.util.FastMath;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;


public final class LayoutAnalysisAlgorithm {

    private static final Pattern PARAGRAPH_END_RE = Pattern.compile(".*([.。;；:：])$");
    private static final Pattern NUMBER = Pattern.compile(".*\\d+.*");
    private static final Pattern NUMBER_SERIAL = Pattern.compile("[0-9]{1,}");
    static final Pattern NUM_SERAL = Pattern.compile("(^([+-]?(([0-9]*)|([0-9].*)))([0-9]%?)$)");
    static final Pattern CHART_SERIAL0 = Pattern.compile("(^(图表|圖表|chart|figure|Chart|Figure).*)");
    static final Pattern CHART_SERIAL1 = Pattern.compile("(.*(图表|圖表|chart).*)|(^(图|圖)[(（]?\\s?[0-9]{1,2}[)）]?.*)");
    //static final Pattern CHART_SERIAL1 = Pattern.compile("(.*(图表|圖表|chart).*)|(^(图|圖)[(（]?[0-9]{1,2}[)）]?.*)");
    static final Pattern CHART_SERIAL2 = Pattern.compile("^\\s*(图|圖)(\\s{0,3})(\\d{1,2})(：|:).*");
    static final Pattern TABLE_SERIAL = Pattern.compile("(^表[(（]?[0-9]{1,2}.*)|(^表[零一二三四五六七八九十].*)" +
            "|(^(table)\\s+((i|ii|iii|iv|v|vi|vii|viii|ix|I|II|III|IV|V|VI|VII|VIII|IX|Ⅰ|Ⅱ|Ⅲ|Ⅳ|Ⅴ|Ⅵ|Ⅶ|Ⅷ|Ⅸ)|(\\d{1,2})).*)" +
            "|(^(the following table).*)|(.*(如下表|如下表所示|见下表)(：|:)$)|(^(下表|以下).*(：|:)$)" +
            "|(.*下表列出.*)|(.*(table)\\s+\\d{1,2}$)|(.*(as follows)[：:.]$)");
    static final Pattern DATA_SOURCE_SERIAL = Pattern.compile("(^(数据来源|资料来源|資料來源|来源|來源|Source|source)[：:].*)");
    static final Pattern CHART_TABLE_SPLIT_SERIAL = Pattern.compile("(^(图|表|圖|表|chart|figure|Chart|Figure).*)");

    // TODO 定义一个抽象的 Page 替代面向 Table 的 ContentGroupPage。
    // 根据文本流的顺序进行版面分析
    public static void detect(ContentGroupPage page, List<Rectangle> tableLayoutAreas, List<Rectangle> contentLayoutAreas) {
        //get all textChunks
        List<TextChunk> textChunks = page.getTextChunks(new Rectangle(page.getBounds()));
        //textChunks.removeIf(TextChunk::isPagination);
        if (textChunks.size() == 0) {
            tableLayoutAreas.addAll(Arrays.asList(page));
            contentLayoutAreas.addAll(Arrays.asList(page));
            //TableDebugUtils.writeCells(page, tableLayoutAreas, "7_contentLayoutAreas");
            return;
        }

        textChunks = filterInvalidTextChunks(page, textChunks);
        if (textChunks.size() == 0) {
            tableLayoutAreas.addAll(Arrays.asList(page));
            contentLayoutAreas.addAll(Arrays.asList(page));
            //TableDebugUtils.writeCells(page, tableLayoutAreas, "7_contentLayoutAreas");
            return;
        }

        if (getRotationTextChunkRatio(textChunks) > 0.9) {
            tableLayoutAreas.addAll(Arrays.asList(page));
            contentLayoutAreas.addAll(Arrays.asList(page));
            //TableDebugUtils.writeCells(page, tableLayoutAreas, "7_contentLayoutAreas");
            return;
        }

        List<Rectangle> lineRects = RulingTableRegionsDetectionAlgorithm.findRulingRectangles(page).stream()
                .map(Rectangle::new).collect(Collectors.toList());

        //TableDebugUtils.writeLines(page, page.getVisibleRulings(), "0_allRulings");
        //TableDebugUtils.writeCells(page, textChunks, "1_textChunks");
        List<Rectangle> chartAreas = mergeChartAreas(page, textChunks, lineRects);
        if (textChunks.isEmpty() && !chartAreas.isEmpty()) {
            sortContentLayoutAreas(chartAreas);
            contentLayoutAreas.addAll(chartAreas);
            tableLayoutAreas.addAll(chartAreas);
            return;
        }
        List<Rectangle> layoutAreas = new ArrayList<>();
        List<FillAreaGroup> fillGroups = getTextRectFillArea(page);
        List<Rectangle> verticallRects = verticallyProjectiveClustering(page,
                textChunks.stream().map(Rectangle::new).collect(Collectors.toList()), fillGroups);
        //TableDebugUtils.writeCells(page, verticallRects, "2_verticallRects1");
        List<Rectangle> mergeVerticallRects = mergeOverlayRects(page, verticallRects, fillGroups);
        //TableDebugUtils.writeCells(page, mergeVerticallRects, "3_mergeVerticallRects");
        List<Rectangle> allPossibleRegions = findPossibleTextRegions(page, mergeVerticallRects, verticallRects, fillGroups, chartAreas);
        //TableDebugUtils.writeCells(page, allPossibleRegions, "4_allPossibleRegions");
        List<Rectangle> lineAreas = getRegionsByLines(page, lineRects, allPossibleRegions);
        //TableDebugUtils.writeCells(page, lineAreas, "5_lineAreas0");
        List<Rectangle> candidateRegions = filterRegionsByLine(page, lineAreas, allPossibleRegions, fillGroups);
        //TableDebugUtils.writeCells(page, candidateRegions, "6_candidateRegions");
        layoutAreas.addAll(mergeMostPossibleTextRegions(page, candidateRegions, verticallRects, chartAreas));
        contentLayoutAreas.addAll(layoutAreas);
        sortContentLayoutAreas(contentLayoutAreas);
        //TableDebugUtils.writeCells(page, contentLayoutAreas, "7_contentLayoutAreas");
        layoutAreas = correctLayoutAreas(page, layoutAreas);
        TableDebugUtils.writeCells(page, layoutAreas, "layoutAreas2");
        tableLayoutAreas.addAll(layoutAreas);
    }

    private static class GroupInfo {
        public Rectangle groupRect;
        public int groupIndex;

        public GroupInfo() {

        }

        public GroupInfo(Rectangle groupRectangle, int index) {
            this.groupRect = groupRectangle;
            this.groupIndex = index;
        }

        public void setGroupRectIndex(Rectangle groupRectangle, int index) {
            this.groupRect = groupRectangle;
            this.groupIndex = index;
        }
    }


    private static boolean isDistributeAtDiffLineAreas(ContentGroupPage page, Rectangle baseRect, Rectangle otherRect) {

        List<TableRegion> lineAreas = RulingTableRegionsDetectionAlgorithm.findRulingRectangles(page);

        if (lineAreas.isEmpty()) {
            return false;
        }

        List<Rectangle> baseContainAreas = lineAreas.stream().filter(area -> area.nearlyContains(baseRect)).collect(Collectors.toList());
        List<Rectangle> otherContainAreas = lineAreas.stream().filter(area -> area.nearlyContains(otherRect)).collect(Collectors.toList());
        if (baseContainAreas.size() == otherContainAreas.size() && baseContainAreas.size() == 1
                && !baseContainAreas.get(0).equals(otherContainAreas.get(0))) {
            return true;
        }
        return false;
    }


    private static void extendChartAreaByChunks(ContentGroupPage page, Rectangle chartArea) {

        List<TextChunk> upChunks = PostProcessTableRegionsAlgorithm.upSearchChunks(page, chartArea);
        if (!upChunks.isEmpty()) {
            do {
                TextChunk baseChunk = upChunks.get(0);
                List<TextChunk> clusChunks = upChunks.stream()
                        .filter(te -> (te.verticalOverlapRatio(baseChunk) > 0.5)).collect(Collectors.toList());
                String hederTexts = getAllChunksString(page, clusChunks);
                if (!hederTexts.equals("") && (CHART_SERIAL0.matcher(hederTexts.trim().toLowerCase()).matches()
                        || CHART_SERIAL1.matcher(hederTexts.trim().toLowerCase()).matches()
                        || CHART_SERIAL2.matcher(hederTexts.trim().toLowerCase()).matches() )) {
                    chartArea.setTop(baseChunk.getTop());
                    Rectangle textArea = PostProcessTableRegionsAlgorithm.getTextAreaByChunks(clusChunks);
                    if (textArea != null) {
                        if (textArea.getLeft() < chartArea.getLeft()) {
                            chartArea.setLeft(textArea.getLeft());
                        }
                        if (textArea.getRight() > chartArea.getRight()) {
                            chartArea.setRight(textArea.getRight());
                        }
                    }
                    break;
                }
                upChunks.removeAll(clusChunks);
            } while (!upChunks.isEmpty());
        }

        List<TextChunk> downChunks = PostProcessTableRegionsAlgorithm.downSearchChunks(page, chartArea);
        if (!downChunks.isEmpty()) {
            do {
                TextChunk baseChunk = downChunks.get(0);
                List<TextChunk> clusChunks = downChunks.stream()
                        .filter(te -> (te.verticalOverlapRatio(baseChunk) > 0.5)).collect(Collectors.toList());
                String tailTexts = getAllChunksString(page, clusChunks);
                if (!tailTexts.equals("") && (DATA_SOURCE_SERIAL.matcher(tailTexts.trim().toLowerCase()).matches())) {
                    chartArea.setBottom(baseChunk.getBottom());
                    Rectangle textArea = PostProcessTableRegionsAlgorithm.getTextAreaByChunks(clusChunks);
                    if (textArea != null) {
                        if (textArea.getLeft() < chartArea.getLeft()) {
                            chartArea.setLeft(textArea.getLeft());
                        }
                        if (textArea.getRight() > chartArea.getRight()) {
                            chartArea.setRight(textArea.getRight());
                        }
                    }
                    break;
                }
                downChunks.removeAll(clusChunks);
            } while (!downChunks.isEmpty());
        }
    }

    private static void extendChartTextArea(ContentGroupPage page, List<TextChunk> numChunks, Rectangle chartArea, Ruling vLine) {

        if (page.getTextChunks(chartArea).isEmpty() || numChunks.isEmpty()
                || !numChunks.stream().anyMatch(te -> (NUM_SERAL.matcher(te.getText().trim().toLowerCase()).matches()))) {
            return;
        }

        double height = page.getTextChunks(chartArea).stream()
                .min(Comparator.comparing(TextChunk::getHeight)).get().getHeight();

        double avgHeight = (page.getTextChunks(chartArea).stream()
                .max(Comparator.comparing(TextChunk::getHeight)).get().getHeight() + height) * 0.5;


        List<TextChunk> otherChunks = numChunks.stream().filter(te -> (te.isVerticallyOverlap(vLine.toRectangle())
                && te.horizontallyOverlapRatio(chartArea) > 0.95
                && te.verticalDistance(chartArea) < 2 * avgHeight
                && !chartArea.nearlyContains(te))).collect(Collectors.toList());

        if (!otherChunks.isEmpty()) {
            chartArea.merge(getAreaByChunks(otherChunks));
        }
    }

    private static void extendChartAreaChunks(ContentGroupPage page, List<TextChunk> allChunks, Rectangle chartArea) {

        if (allChunks.isEmpty()) {
            return;
        }
        // 只提取最近的chunk
        TextChunk oneChunk = allChunks.get(0);
        List<TextChunk> clusChunks = page.getTextChunks().stream().filter(te -> (te.verticalOverlapRatio(oneChunk) > 0.95
                && te.horizontallyOverlapRatio(chartArea) > 0.3
                && te.getWidth() < page.getWidth() / 4)).collect(Collectors.toList());

        List<TextChunk> diffChunks = new ArrayList<>();
        for (TextChunk clusChunk : clusChunks) {
            if (!allChunks.stream().anyMatch(te -> (te.equals(clusChunk)))) {
                diffChunks.add(clusChunk);
            }
        }
        if (!diffChunks.isEmpty()) {
            allChunks.addAll(diffChunks);
            allChunks.sort(Comparator.comparing(TextChunk::getTop));
        }
    }

    private static List<Rectangle> getChartStyleAreas(ContentGroupPage page, List<TextChunk> textChunks, List<Rectangle> rulingAreas) {

        if (rulingAreas.isEmpty() || page.getVerticalRulings().isEmpty()) {
            return new ArrayList<>();
        }

        List<Rectangle> chartAreas = new ArrayList<>();

        List<TextChunk> numChunks = textChunks.stream()
                .filter(te -> (te.getWidth() < page.getWidth() / 4
                        && NUM_SERAL.matcher(te.getText().trim().toLowerCase()).matches())).collect(Collectors.toList());

        if (!numChunks.isEmpty()) {
            List<TextChunk> removeChunks = new ArrayList<>();
            for (Rectangle area : rulingAreas) {
                List<TextChunk> areaChunks = numChunks.stream().filter(te -> (te.getCenterX() > area.getLeft()
                        && te.getCenterX() < area.getRight() && te.getCenterY() > area.getTop()
                        && te.getCenterY() < area.getBottom())).collect(Collectors.toList());
                removeChunks.addAll(areaChunks);
            }
            numChunks.removeAll(removeChunks);
        }

        List<Ruling> vLines = page.getVerticalRulings().stream()
                .filter(vr -> (vr.getHeight() > 4 * page.getAvgCharHeight())).collect(Collectors.toList());

        if (numChunks.size() > 3 && !vLines.isEmpty()) {
            for (Ruling vline : vLines) {
                Rectangle chartArea = null;
                List<TextChunk> clusLeftChunks = numChunks.stream()
                        .filter(te -> (te.verticalOverlapRatio(vline.toRectangle()) >= 0.5 && ((te.getRight() < vline.getX1()
                                && te.horizontalDistance(vline.toRectangle()) < 5 * page.getAvgCharHeight())
                                || FloatUtils.feq(te.getRight(), vline.getX1(), 4.f)))).collect(Collectors.toList());
                if (clusLeftChunks.size() < 3) {
                    continue;
                }
                Rectangle clusLeftArea = getAreaByChunks(clusLeftChunks);
                extendChartTextArea(page, numChunks, clusLeftArea, vline);
                if (clusLeftArea != null && !page.getHorizontalRulings().isEmpty()) {
                    List<Ruling> hRulings = page.getHorizontalRulings().stream().filter(hr -> (hr.intersectsLine(vline)
                            && hr.getWidth() > 3 * page.getAvgCharWidth()
                            && hr.toRectangle().verticalDistance(clusLeftArea) < 5 * page.getAvgCharHeight())).collect(Collectors.toList());
                    if (!hRulings.isEmpty()) {
                        hRulings.sort(Comparator.comparing(Ruling::getBottom).reversed());
                        Rectangle mergeRect = new Rectangle(clusLeftArea);
                        mergeRect.merge(hRulings.get(0).toRectangle());
                        List<TextChunk> downChunks = PostProcessTableRegionsAlgorithm.downSearchChunks(page, mergeRect);
                        extendChartAreaChunks(page, downChunks, mergeRect);
                        if (downChunks.size() >= 3) {
                            TextChunk oneChunk = downChunks.get(0);
                            List<TextChunk> clusDownChunks = downChunks.stream().filter(te -> (te.verticalOverlapRatio(oneChunk) > 0.95
                                    && te.horizontallyOverlapRatio(mergeRect) > 0.45 && te.getWidth() < page.getWidth() / 4)).collect(Collectors.toList());

                            if (clusDownChunks.size() >= 3) {
                                Rectangle clusDownArea = getAreaByChunks(clusDownChunks);
                                chartArea = mergeRect.merge(clusDownArea);
                            }
                        }
                        if (downChunks.isEmpty()) {
                            chartArea = mergeRect;
                        }
                    }
                }
                Rectangle nearlyRightNumArea = null;
                if (chartArea != null) {
                    Rectangle finalChartArea = chartArea;
                    List<TextChunk> clusRightChunks = numChunks.stream().filter(te -> (te.verticalOverlapRatio(finalChartArea) > 0.5 && ((te.getLeft() > finalChartArea.getRight()
                            && te.horizontalDistance(finalChartArea) < 4 * page.getAvgCharWidth())
                            || FloatUtils.feq(te.getLeft(), finalChartArea.getRight(), 4.f)))).collect(Collectors.toList());
                    if (clusRightChunks.size() > 3) {
                        Rectangle clusRightArea = getAreaByChunks(clusRightChunks);
                        List<Ruling> rightRuling = vLines.stream().filter(vr -> (vr.toRectangle().verticalOverlapRatio(clusRightArea) > 0.9
                                && clusRightArea.horizontalDistance(vr.toRectangle()) < 4 * page.getAvgCharWidth()
                                && (vr.intersects(finalChartArea)
                                || finalChartArea.nearlyContains(vr.toRectangle(), 10.f)))).collect(Collectors.toList());
                        if (!rightRuling.isEmpty()) {
                            rightRuling.sort(Comparator.comparing(Ruling::getX1).reversed());
                            chartArea.merge(clusRightArea);
                        } else {
                            nearlyRightNumArea = clusRightArea;
                        }
                    }

                    List<TextChunk> topChunks = PostProcessTableRegionsAlgorithm.upSearchChunks(page, chartArea);
                    List<TextChunk> tmpTopChunks = new ArrayList<>(topChunks);
                    boolean isExistHeader = false;
                    if (!topChunks.isEmpty()) {
                        do {
                            TextChunk baseChunk = topChunks.get(0);
                            List<TextChunk> clusChunks = topChunks.stream()
                                    .filter(te -> (te.verticalOverlapRatio(baseChunk) > 0.5)).collect(Collectors.toList());
                            String hederTexts = PostProcessTableRegionsAlgorithm.getSequenceOfChunks(page, clusChunks);
                            if (!hederTexts.equals("") && (TABLE_SERIAL.matcher(hederTexts.trim().toLowerCase()).matches()
                                    || CHART_TABLE_SPLIT_SERIAL.matcher(hederTexts.trim()).matches()
                                    || CHART_SERIAL0.matcher(hederTexts.trim()).matches()
                                    || CHART_SERIAL1.matcher(hederTexts.trim()).matches()
                                    || CHART_SERIAL2.matcher(hederTexts.trim()).matches())) {

                                Rectangle mergeClusArea = getAreaByChunks(clusChunks);
                                chartArea.merge(mergeClusArea);
                                isExistHeader = true;
                                Rectangle finalChartArea1 = chartArea;
                                List<TextChunk> insertChunks = tmpTopChunks.stream()
                                            .filter(te -> (te.getCenterY() > mergeClusArea.getBottom()
                                                    && te.getRight() > finalChartArea1.getRight())).collect(Collectors.toList());
                                if (!insertChunks.isEmpty()) {
                                    chartArea.merge(getAreaByChunks(insertChunks));
                                    if (nearlyRightNumArea != null && chartArea.intersects(nearlyRightNumArea)
                                            && chartArea.verticalOverlapRatio(nearlyRightNumArea) > 0.95) {
                                        chartArea.merge(nearlyRightNumArea);
                                    }
                                }
                                break;
                            }
                            topChunks.removeAll(clusChunks);
                        } while (!topChunks.isEmpty());
                    }

                    List<TextChunk> bottomChunks = PostProcessTableRegionsAlgorithm.downSearchChunks(page, chartArea);
                    List<TextChunk> tmpBottomChunks = new ArrayList<>(bottomChunks);
                    boolean isExistTailer = false;
                    if (!bottomChunks.isEmpty()) {
                        do {
                            TextChunk baseChunk = bottomChunks.get(0);
                            List<TextChunk> clusChunks = bottomChunks.stream()
                                    .filter(te -> (te.verticalOverlapRatio(baseChunk) > 0.5)).collect(Collectors.toList());
                            String tailTexts = PostProcessTableRegionsAlgorithm.getSequenceOfChunks(page, clusChunks);

                            if (!tailTexts.equals("") && DATA_SOURCE_SERIAL.matcher(tailTexts.trim().toLowerCase()).matches()) {
                                Rectangle mergeClusArea = getAreaByChunks(clusChunks);
                                chartArea.merge(mergeClusArea);
                                isExistTailer = true;
                                Rectangle finalChartArea1 = chartArea;
                                List<TextChunk> insertChunks = tmpBottomChunks.stream()
                                        .filter(te -> (te.getCenterY() < mergeClusArea.getTop()
                                                && te.getRight() > finalChartArea1.getRight())).collect(Collectors.toList());
                                if (!insertChunks.isEmpty()) {
                                    chartArea.merge(getAreaByChunks(insertChunks));
                                    if (nearlyRightNumArea != null && chartArea.intersects(nearlyRightNumArea)
                                            && chartArea.verticalOverlapRatio(nearlyRightNumArea) > 0.95) {
                                        chartArea.merge(nearlyRightNumArea);
                                    }
                                }
                                break;
                            }
                            bottomChunks.removeAll(clusChunks);
                        } while (!bottomChunks.isEmpty());
                    }

                    if (isExistHeader && !isExistTailer && !page.getHorizontalRulings().isEmpty()) {
                        Rectangle finalChartArea2 = chartArea;
                        List<Ruling> candidateRulings = page.getHorizontalRulings().stream()
                                .filter(hr -> (hr.toRectangle().horizontallyOverlapRatio(finalChartArea2) > 0.95 && hr.getY1() > finalChartArea2.getBottom()
                                        && hr.toRectangle().verticalDistance(finalChartArea2) < 5 * page.getAvgCharHeight())).collect(Collectors.toList());
                        if (!candidateRulings.isEmpty()) {
                            candidateRulings.sort(Comparator.comparing(Ruling::getTop));
                            Ruling chartBottomLine = candidateRulings.get(0);
                            Rectangle tmpArea = new Rectangle(chartArea);
                            tmpArea.setBottom((float)chartBottomLine.getY1());
                            List<TextChunk> charDownChunks = PostProcessTableRegionsAlgorithm.downSearchChunks(page, tmpArea);
                            if (!charDownChunks.isEmpty()) {
                                TextChunk oneChunk = charDownChunks.get(0);
                                List<TextChunk> clusChunks = charDownChunks.stream()
                                        .filter(te -> (te.verticalOverlapRatio(oneChunk) > 0.5)).collect(Collectors.toList());
                                String tailTexts = PostProcessTableRegionsAlgorithm.getSequenceOfChunks(page, clusChunks);

                                if (!page.getTextChunks(tmpArea).stream().anyMatch(te -> (DATA_SOURCE_SERIAL.matcher(te.getText().trim().toLowerCase()).matches()))
                                        && !tailTexts.equals("") && DATA_SOURCE_SERIAL.matcher(tailTexts.trim().toLowerCase()).matches()) {
                                    tmpArea.merge(getAreaByChunks(clusChunks));
                                    chartArea = tmpArea;
                                }
                            }
                        }
                    }
                    chartAreas.add(new Rectangle(chartArea));
                }
            }
        }

        return chartAreas;
    }

    private static List<Rectangle> mergeChartAreas(ContentGroupPage page, List<TextChunk> textChunks, List<Rectangle> rulingAreas) {

        List<Rectangle> chartTextAreas = getChartStyleAreas(page, textChunks, rulingAreas);

        if (!page.getContentGroup().hasTag(Tags.IS_CHART_DETECT_IN_TABLE)) {
            if (!chartTextAreas.isEmpty()) {
                List<TextChunk> cleanChunks = new ArrayList<>();
                chartTextAreas.stream().forEach(chart -> {
                    cleanChunks.addAll(page.getTextChunks(chart));
                });
                textChunks.removeAll(cleanChunks);
            }
            return chartTextAreas;
        }

        List<TableRegion> chartRegions = page.getChartRegions("LayoutAnalysisAlgorithm");
        //TableDebugUtils.writeCells(page, chartRegions, "xxx_chart_regions");
        if (chartRegions.isEmpty()) {
            return chartTextAreas;
        }
        List<TextChunk> removeChunks = new ArrayList<>();
        List<Rectangle> chartAreas = new ArrayList<>();
        for (TableRegion tmpChart : chartRegions) {
            TableRegion chart = new TableRegion(tmpChart);
            extendChartAreaByChunks(page, chart);
            List<TextChunk> chartChunks = page.getTextChunks(chart);
            if (chart.getConfidence() > 0.8 && !chartChunks.isEmpty()) {
                TextChunk topChunk = chartChunks.stream().min(Comparator.comparing(TextChunk::getTop)).get();
                List<TextChunk> clusChunks = chartChunks.stream()
                        .filter(te -> (te.verticalOverlapRatio(topChunk) > 0.8)).collect(Collectors.toList());
                String topStrings = getAllChunksString(page, clusChunks);
                if (CHART_SERIAL0.matcher(topStrings.trim().toLowerCase()).matches()
                        || CHART_SERIAL1.matcher(topStrings.trim().toLowerCase()).matches()
                        || CHART_SERIAL2.matcher(topStrings.trim().toLowerCase()).matches()) {
                    if (chart.getTop() > topChunk.getTop()) {
                        chart.setTop(topChunk.getTop());
                        Rectangle textArea = PostProcessTableRegionsAlgorithm.getTextAreaByChunks(clusChunks);
                        if (textArea.getLeft() < chart.getLeft()) {
                            chart.setLeft(textArea.getLeft());
                        }
                        if (textArea.getRight() > chart.getRight()) {
                            chart.setRight(textArea.getRight());
                        }
                    }
                }
                TextChunk bottomChunk = chartChunks.stream().max(Comparator.comparing(TextChunk::getBottom)).get();
                clusChunks = chartChunks.stream()
                        .filter(te -> (te.verticalOverlapRatio(bottomChunk) > 0.8)).collect(Collectors.toList());
                String bottomStrings = getAllChunksString(page, clusChunks);
                if (DATA_SOURCE_SERIAL.matcher(bottomStrings.trim().toLowerCase()).matches()) {
                    if (bottomChunk.getBottom() > chart.getBottom()) {
                        chart.setBottom(bottomChunk.getBottom());
                        Rectangle textArea = PostProcessTableRegionsAlgorithm.getTextAreaByChunks(clusChunks);
                        if (textArea.getLeft() < chart.getLeft()) {
                            chart.setLeft(textArea.getLeft());
                        }
                        if (textArea.getRight() > chart.getRight()) {
                            chart.setRight(textArea.getRight());
                        }
                    }
                }
                chartAreas.add(chart);
                continue;
            }
            if (chart.getConfidence() > 0.3) {

                if (!chartChunks.isEmpty()) {
                    TextChunk topChunk = chartChunks.stream().min(Comparator.comparing(TextChunk::getTop)).get();
                    String topStrings = getAllChunksString(page, chartChunks.stream()
                            .filter(te -> (te.verticalOverlapRatio(topChunk) > 0.8)).collect(Collectors.toList()));
                    if (CHART_SERIAL0.matcher(topStrings.trim().toLowerCase()).matches()
                            || CHART_SERIAL1.matcher(topStrings.trim().toLowerCase()).matches()
                            || CHART_SERIAL2.matcher(topStrings.trim().toLowerCase()).matches()) {
                        if (chart.getTop() > topChunk.getTop()) {
                            chart.setTop(topChunk.getTop());
                            chartAreas.add(chart);
                        }
                    }
                }
            }
        }

        if (!chartAreas.isEmpty()) {
            chartAreas.stream().forEach(chart -> {
                extendChartAreaByChunks(page, chart);
                removeChunks.addAll(page.getTextChunks(chart));
            });
        }
        textChunks.removeAll(removeChunks);
        chartAreas.addAll(chartTextAreas);
        processRepeateAndContainedAreas(page, chartAreas);
        //TableDebugUtils.writeCells(page, chartAreas, "xxx_chartAreas");
        return chartAreas;
    }

    private static String getAllChunksString(ContentGroupPage page, List<TextChunk> allChunks) {

        StringBuffer buf = new StringBuffer();
        if (allChunks.isEmpty()) {
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

    private static double getRotationTextChunkRatio(List<TextChunk> allTextChunks) {

        double ratio = 0.f;
        if (allTextChunks.isEmpty()) {
            return ratio;
        }

        List<TextChunk> upDownTextChunks = allTextChunks.stream().filter(te -> (te.getDirection() == TextDirection.VERTICAL_UP
                || te.getDirection() == TextDirection.VERTICAL_DOWN)).collect(Collectors.toList());
        float size1 = upDownTextChunks.size();
        float size = allTextChunks.size();
        ratio = (double)(size1 / size);

        return ratio;
    }

    private static void sortContentLayoutAreas(List<Rectangle> layoutAreas) {
        if (layoutAreas.size() <= 1) {
            return;
        }
        NumberUtil.sort(layoutAreas, (o1, o2) -> {
            if (o1.verticalOverlapRatio(o2) > 0.5) {
                return Double.compare(o1.getLeft(), o2.getLeft());
            }
            return Double.compare(o1.getTop(), o2.getTop());
        });
    }

    // 将断开的临近的chunk连接起来，一般出现在英文中
    private static List<Rectangle> splitJointSmallChunkRects(ContentGroupPage page, List<Rectangle> textChunkRects) {

        if (textChunkRects == null || textChunkRects.isEmpty()) {
            return new ArrayList<>();
        }
        List<Rectangle> layoutAreas = new ArrayList<>();
        Rectangle layoutArea;
        Rectangle baseChunkRect = textChunkRects.get(0);
        layoutArea = new Rectangle(baseChunkRect);
        boolean isFirstChunk = true;
        boolean isEnglish = StringUtils.startsWithIgnoreCase(page.getLanguage(), "en");
        for (int i = 1; i < textChunkRects.size(); i++) {
            Rectangle otherChunkRect = textChunkRects.get(i);
            Rectangle tmpRect = isFirstChunk ? baseChunkRect : layoutArea;
            //TableDebugUtils.writeCells(page, Arrays.asList(baseChunkRect), "baseChunkRect");
            //TableDebugUtils.writeCells(page, Arrays.asList(otherChunkRect), "otherChunkRect");
            //TableDebugUtils.writeCells(page, Arrays.asList(layoutArea), "layoutArea");
            if (baseChunkRect.verticalOverlapRatio(otherChunkRect) > 0.5 && !baseChunkRect.isHorizontallyOverlap(otherChunkRect)
                    && !isHaveAllChartAreas(page, baseChunkRect, otherChunkRect)) {
                boolean isBaseAtLeft =  baseChunkRect.getLeft() < otherChunkRect.getLeft();
                float threshold = isEnglish ? 4.f : getAvgCharWidth(page, baseChunkRect, otherChunkRect);
                if (isBaseAtLeft) {
                    if (FloatUtils.feq(baseChunkRect.getRight(), otherChunkRect.getLeft(), threshold)) {
                        layoutArea.setRect(tmpRect.merge(otherChunkRect));
                        isFirstChunk = false;
                    } else {
                        layoutAreas.add(new Rectangle(layoutArea));
                        layoutArea = new Rectangle(otherChunkRect.getBounds());
                        isFirstChunk = true;
                    }
                } else {
                    if (FloatUtils.feq(baseChunkRect.getLeft(), otherChunkRect.getRight(), threshold)) {
                        layoutArea.setRect(tmpRect.merge(otherChunkRect));
                        isFirstChunk = false;
                    } else {
                        layoutAreas.add(new Rectangle(layoutArea));
                        layoutArea = new Rectangle(otherChunkRect.getBounds());
                        isFirstChunk = true;
                    }
                }
            } else {
                layoutAreas.add(new Rectangle(layoutArea));
                layoutArea = new Rectangle(otherChunkRect.getBounds());
                isFirstChunk = true;
            }
            baseChunkRect = otherChunkRect;
        }
        layoutAreas.add(layoutArea);
        return layoutAreas;
    }


    private static boolean isLargeDiff(ContentGroupPage page, Rectangle baseChunkRect, Rectangle otherChunkRect) {
        if (page.getText(baseChunkRect).isEmpty() || page.getText(otherChunkRect).isEmpty()) {
            return false;
        }

        boolean isBaseHeightMax = baseChunkRect.getHeight() > otherChunkRect.getHeight();
        double ratio = isBaseHeightMax ? (otherChunkRect.getHeight() / baseChunkRect.getHeight())
                : (baseChunkRect.getHeight() / otherChunkRect.getHeight());
        Rectangle maxRect = isBaseHeightMax ? baseChunkRect : otherChunkRect;
        Rectangle minRect = isBaseHeightMax ? otherChunkRect : baseChunkRect;
        // 间距比较远并且字号差距较大
        if (baseChunkRect.isVerticallyOverlap(otherChunkRect) && minRect.getHeight() < 2 * page.getAvgCharHeight()
                && maxRect.getHeight() < 3 * page.getAvgCharHeight()) {
            if (baseChunkRect.horizontalDistance(otherChunkRect) > 5 * getAvgCharWidth(page, baseChunkRect, otherChunkRect)
                    && ratio < 0.7 && FastMath.abs(baseChunkRect.getBottom() - otherChunkRect.getBottom()) > 4) {
                return true;
            }

            float maxFontSize = page.getTextChunks(baseChunkRect).stream().max(Comparator.comparing(TextChunk::getMaxFontSize)).get().getMaxFontSize();
            float maxFontSize1 = page.getTextChunks(otherChunkRect).stream().max(Comparator.comparing(TextChunk::getMaxFontSize)).get().getMaxFontSize();
            float maxFont = (maxFontSize > maxFontSize1) ? maxFontSize : maxFontSize1;
            float minFont = (maxFontSize > maxFontSize1) ? maxFontSize1 : maxFontSize;

            float hDistance = getAvgCharWidth(page, baseChunkRect, otherChunkRect);

            if (minFont / maxFont < 0.7 && baseChunkRect.horizontalDistance(otherChunkRect) > 15 * hDistance
                    && (page.getTextChunks(baseChunkRect).stream().anyMatch(TextChunk::isBold))
                    != page.getTextChunks(otherChunkRect).stream().anyMatch(TextChunk::isBold)) {
                return true;
            }

            if (maxFontSize > 13.f && baseChunkRect.horizontalDistance(otherChunkRect) > 15 * hDistance
                    && !FloatUtils.feq(page.getTextChunks(baseChunkRect).get(0).getMaxFontSize(), page.getTextChunks(otherChunkRect).get(0).getMaxFontSize(), 3)
                    && !FloatUtils.feq(page.getTextChunks(baseChunkRect).get(0).getElements().get(0).getColor().getRGB(),
                    page.getTextChunks(otherChunkRect).get(0).getElements().get(0).getColor().getRGB(), 50)) {
                return true;
            }
        }

        if (baseChunkRect.isHorizontallyOverlap(otherChunkRect)) {
            if (baseChunkRect.verticalDistance(otherChunkRect) > 5 * getAvgCharHeight(page, baseChunkRect, otherChunkRect)
                    && ratio < 0.7) {
                return true;
            }
        }

        return false;
    }

    private static boolean isExistverticallySeparatedFills(ContentGroupPage page, Rectangle baseRect, Rectangle otherRect) {

        if (!baseRect.isVerticallyOverlap(otherRect) || page.getPageFillAreaGroup().isEmpty()) {
            return false;
        }

        List<FillAreaGroup> baseFillAreas = page.getPageFillAreaGroup().stream()
                .filter(fill -> (fill.getGroupRectArea().nearlyContains(baseRect))).collect(Collectors.toList());

        List<FillAreaGroup> otherFillAreas = page.getPageFillAreaGroup().stream()
                .filter(fill -> (fill.getGroupRectArea().nearlyContains(otherRect))).collect(Collectors.toList());


        /*if (baseFillAreas.isEmpty() && !otherFillAreas.isEmpty()) {
            if (otherFillAreas.get(0).getGroupRectArea().getWidth() > otherFillAreas.get(0).getGroupRectArea().getHeight()) {
                return true;
            }
        }

        if (!baseFillAreas.isEmpty() && otherFillAreas.isEmpty()) {
            if (baseFillAreas.get(0).getGroupRectArea().getWidth() > baseFillAreas.get(0).getGroupRectArea().getHeight()) {
                return true;
            }
        }*/

        if (baseFillAreas.size() == otherFillAreas.size() && baseFillAreas.size() == 1
                && baseFillAreas.get(0).getGroupRectArea().overlapRatio(otherFillAreas.get(0).getGroupRectArea()) == 0) {
            return true;
        }

        boolean isBaseAtLeft = baseRect.getLeft() < otherRect.getLeft();
        float minX = isBaseAtLeft ? baseRect.getRight() : otherRect.getRight();
        float maxX = isBaseAtLeft ? otherRect.getLeft() : baseRect.getLeft();

        if (page.getPageFillAreaGroup().stream().anyMatch(fill -> (fill.getGroupRectArea().isVerticallyOverlap(baseRect)
                && fill.getGroupRectArea().isVerticallyOverlap(otherRect) && !fill.getGroupRectArea().isHorizontallyOverlap(baseRect)
                && !fill.getGroupRectArea().isHorizontallyOverlap(otherRect)
                && (fill.getGroupRectArea().getLeft() > minX && fill.getGroupRectArea().getRight() < maxX)))) {
            return true;
        }

        return false;
    }

    private static boolean isGroupTextChunks(ContentGroupPage page, Rectangle base, Rectangle other) {

        if (base.horizontallyOverlapRatio(other) > 0.7 && base.verticalDistance(other) < 1.5 * page.getMinCharHeight()) {
            boolean isBaseMax = base.getWidth() > other.getWidth();
            if (isBaseMax) {
                if (base.getLeft() < other.getLeft() && base.getRight() > other.getRight()
                        && base.getWidth() < 11 * page.getAvgCharWidth()
                        && other.getWidth() < 10 * page.getAvgCharWidth()) {
                    return true;
                }
            } else {
                if (base.getLeft() > other.getLeft() && base.getRight() < other.getRight()
                        && base.getWidth() < 10 * page.getAvgCharWidth()
                        && other.getWidth() < 11 * page.getAvgCharWidth()) {
                    return true;
                }
            }
            page.getTextChunks(other).get(0).getTextLength();
        }

        return false;
    }

    private static boolean isDistributeAtDiffFills(ContentGroupPage page, List<FillAreaGroup> fillAreas, Rectangle base, Rectangle other) {

        if (fillAreas.isEmpty()) {
            return false;
        }

        List<FillAreaGroup> baseFills = fillAreas.stream().filter(fi -> (fi.getGroupRectArea().contains(base))).collect(Collectors.toList());
        List<FillAreaGroup> otherFills = fillAreas.stream().filter(fi -> (fi.getGroupRectArea().contains(other))).collect(Collectors.toList());
        if (baseFills.size() > 1 || otherFills.size() > 1) {
            return false;
        }

        if (FastMath.abs(baseFills.size() - otherFills.size()) == 1
                && (overLapOfHeight(base, other) > 0.8 && isHasOneRowChunks(page, base) && isHasOneRowChunks(page, other))) {
            return true;
        }

        if (baseFills.size() == otherFills.size() && baseFills.size() == 1
                && (baseFills.get(0).getGroupFillAreas().get(0).getColor() != otherFills.get(0).getGroupFillAreas().get(0).getColor()
                || base.horizontalDistance(other) > page.getAvgCharWidth())) {
            return true;
        }

        return false;
    }

    private static boolean isDoublePage(ContentGroupPage page, Rectangle baseChunk, Rectangle otherChunk) {

        if (page.getWidth() < 800) {
            return false;
        }

        if (!baseChunk.isVerticallyOverlap(otherChunk)) {
            return false;
        }

        boolean isBaseAtLeft = baseChunk.getLeft() < otherChunk.getLeft();
        if (isBaseAtLeft) {
            if (baseChunk.getRight() < page.getWidth() / 2 && otherChunk.getLeft() > page.getWidth() / 2) {
                return true;
            }
        } else {
            if (otherChunk.getRight() < page.getWidth() / 2 && baseChunk.getLeft() > page.getWidth() / 2) {
                return true;
            }
        }

        return false;
    }

    private static List<Rectangle> getLayoutAreasByGobalView(ContentGroupPage page, List<Rectangle> textRects, List<FillAreaGroup> fillGroups) {

        if (page.getPageNumber() != 1) {
            return textRects;
        }

        List<Ruling> horizontalLines = page.getHorizontalRulings().stream()
                .filter(re -> (re.getWidth() > 3 * page.getAvgCharWidth()) && re.getWidth() < page.getWidth() / 3).collect(Collectors.toList());
        if (horizontalLines.isEmpty()) {
            return textRects;
        }

        List<Ruling> verticallyRulings = page.getVerticalRulings();
        if (!verticallyRulings.isEmpty()) {
            List<Ruling> removeRulings = new ArrayList<>();
            for (Ruling hline : horizontalLines) {
                if (verticallyRulings.stream().anyMatch(vline -> (vline.intersectsLine(hline)))) {
                    removeRulings.add(hline);
                }
            }
            horizontalLines.removeAll(removeRulings);
        }

        List<Rectangle> targetAreas = new ArrayList<>();
        if (!horizontalLines.isEmpty()) {
            horizontalLines.sort(Comparator.comparing(Ruling::getTop));
            for (Ruling hRuling : horizontalLines) {

                if (!targetAreas.isEmpty() && targetAreas.stream().anyMatch(re -> (re.intersectsLine(hRuling)
                        || ((hRuling.getCenterX() > re.getLeft() && hRuling.getCenterX() < re.getRight())
                        && (hRuling.getCenterY() > re.getTop() && hRuling.getCenterY() < re.getBottom()))
                        || FloatUtils.feq(hRuling.getCenterY(), re.getTop(), 4.f)
                        || FloatUtils.feq(hRuling.getCenterY(), re.getBottom(), 4.f)))) {
                    continue;
                }

                List<Ruling> clusRulings = horizontalLines.stream().filter(hr -> (hr.horizontallyOverlapRatio(hRuling) > 0.95
                        && FloatUtils.feq(hRuling.getWidth(), hr.getWidth(), 2.f)
                        && FloatUtils.feq(hRuling.getLeft(), hr.getLeft(), 2.f)
                        && FloatUtils.feq(hRuling.getRight(), hr.getRight(), 2.f))).collect(Collectors.toList());
                if (clusRulings.size() >= 5) {
                    clusRulings.sort(Comparator.comparing(Ruling::getTop));
                    float top = clusRulings.get(0).getTop();
                    float left = clusRulings.stream().min(Comparator.comparing(Ruling::getLeft)).get().getLeft();
                    float width = clusRulings.stream().max(Comparator.comparing(Ruling::getRight)).get().getRight() - left;
                    float height = clusRulings.get(clusRulings.size() - 1).getBottom() - top;
                    if (height > 10 * page.getAvgCharHeight()) {
                        targetAreas.add(new Rectangle(left, top, width, height));
                    }
                }
            }
        }

        List<Rectangle> targetRects = new ArrayList<>();
        for (Rectangle text : textRects) {

            if (targetAreas.isEmpty()) {
                targetRects.addAll(textRects);
                break;
            }

            if (!targetRects.isEmpty() && targetRects.stream().anyMatch(re -> (re.nearlyContains(text)
                    || (text.getCenterY() > re.getTop() && text.getCenterY() < re.getBottom()
                    && text.getCenterX() > re.getLeft() && text.getCenterX() < re.getRight())))) {
                continue;
            }

            List<Rectangle> containAreas = targetAreas.stream().filter(re -> (re.contains(text)
                    && (text.getCenterY() > re.getTop() && text.getCenterY() < re.getBottom()))
                    && (text.getCenterX() > re.getLeft() && text.getCenterX() < re.getRight())).collect(Collectors.toList());

            if (containAreas.size() == 1) {
                targetRects.add(containAreas.get(0));
            } else {
                targetRects.add(text);
            }
        }

        if (!targetAreas.isEmpty() && !targetRects.isEmpty()) {
            processRepeateAndContainedAreas(page, targetRects);
        }
        if (targetAreas.isEmpty()) {
            targetRects = getLayoutAreasByGobalView1(page, targetRects, fillGroups);
        }
        return targetRects;
    }

    private static List<Rectangle> getLayoutAreasByGobalView1(ContentGroupPage page, List<Rectangle> textRects, List<FillAreaGroup> fillAreas) {

        if (page.getPageNumber() != 1 || fillAreas.size() < 2 || textRects.isEmpty()) {
            return textRects;
        }

        float left = textRects.stream().min(Comparator.comparing(Rectangle::getLeft)).get().getLeft();
        float width = textRects.stream().max(Comparator.comparing(Rectangle::getRight)).get().getRight() - left;
        float top = textRects.stream().min(Comparator.comparing(Rectangle::getTop)).get().getTop();
        float height = textRects.stream().max(Comparator.comparing(Rectangle::getBottom)).get().getBottom() - top;

        Rectangle textArea = new Rectangle(left, top, width, height);

        List<Ruling> horizontalLines = page.getHorizontalRulings().stream()
                .filter(re -> (re.getWidth() > 3 * page.getAvgCharWidth()
                        && (textArea.intersectsLine(re)
                        || textArea.nearlyContains(re.toRectangle(), 3.f)))).collect(Collectors.toList());
        if (horizontalLines.isEmpty()) {
            return textRects;
        }

        List<Ruling> verticallyRulings = page.getVerticalRulings();
        if (!verticallyRulings.isEmpty()) {
            List<Ruling> removeRulings = new ArrayList<>();
            for (Ruling hline : horizontalLines) {
                if (verticallyRulings.stream().anyMatch(vline -> (vline.intersectsLine(hline)))) {
                    removeRulings.add(hline);
                }
            }
            horizontalLines.removeAll(removeRulings);
        }

        List<Rectangle> targetAreas = new ArrayList<>();
        if (horizontalLines.size() > 1) {
            List<Ruling> topRulings = horizontalLines.stream().filter(hr -> (hr.getTop() < 120.f)).collect(Collectors.toList());

            List<Ruling> columnRulings = new ArrayList<>();
            if (topRulings.size() > 1) {
                topRulings.sort(Comparator.comparing(Ruling::getTop));
                Ruling baseLine = topRulings.get(0);
                columnRulings = topRulings.stream()
                        .filter(hr -> (FloatUtils.feq(hr.getTop(), baseLine.getTop(), 4.f))).collect(Collectors.toList());
            }

            if (columnRulings.size() == 2) {

                columnRulings.sort(Comparator.comparing(Ruling::getLeft));
                Rectangle leftLineRect = columnRulings.get(0).toRectangle();
                Rectangle rightLineRect = columnRulings.get(1).toRectangle();


                if (textRects.stream().anyMatch(re -> (leftLineRect.horizontallyOverlapRatio(re) > 0
                        && leftLineRect.horizontallyOverlapRatio(re) < 0.95))
                        || textRects.stream().anyMatch(re -> (rightLineRect.horizontallyOverlapRatio(re) > 0
                        && rightLineRect.horizontallyOverlapRatio(re) < 0.95))
                        || horizontalLines.stream().anyMatch(hr -> (leftLineRect.isHorizontallyOverlap(hr.toRectangle())
                        && rightLineRect.isHorizontallyOverlap(hr.toRectangle())))) {
                    return textRects;
                }

                List<FillAreaGroup> fillRects1 = fillAreas.stream().filter(fill -> (fill.getGroupRectArea().horizontallyOverlapRatio(leftLineRect) > 0.8
                        && !fill.getGroupRectArea().isHorizontallyOverlap(rightLineRect))).collect(Collectors.toList());
                List<FillAreaGroup> fillRects2 = fillAreas.stream().filter(fill -> (fill.getGroupRectArea().horizontallyOverlapRatio(rightLineRect) > 0.8
                        && !fill.getGroupRectArea().isHorizontallyOverlap(leftLineRect))).collect(Collectors.toList());

                if (!fillRects1.isEmpty() && !fillRects2.isEmpty()) {
                    List<TextChunk> leftChunks = page.getTextChunks().stream()
                            .filter(te -> (te.horizontallyOverlapRatio(leftLineRect) > 0.95
                                    && textArea.nearlyContains(te))).collect(Collectors.toList());
                    List<TextChunk> rightChunks = page.getTextChunks().stream()
                            .filter(te -> (te.horizontallyOverlapRatio(rightLineRect) > 0.95
                                    && textArea.nearlyContains(te))).collect(Collectors.toList());
                    if (!leftChunks.isEmpty()) {
                        targetAreas.add(getAreaByChunks(leftChunks));
                    }

                    if (!rightChunks.isEmpty()) {
                        targetAreas.add(getAreaByChunks(rightChunks));
                    }

                    if (targetAreas.size() == 2) {
                        return targetAreas;
                    }
                }
            }
        }

        return textRects;
    }

    private static List<Rectangle> verticallyProjectiveClustering(ContentGroupPage page, List<Rectangle> textChunks, List<FillAreaGroup> fillGroups) {

        if (textChunks == null || textChunks.isEmpty()) {
            return new ArrayList<>();
        }

        List<Rectangle> textChunkRects = splitJointSmallChunkRects(page, textChunks);
        textChunkRects = getLayoutAreasByGobalView(page, textChunkRects, fillGroups);

        if (page.getPageNumber() == 1 && textChunkRects.size() == 2
                && textChunkRects.get(0).verticalOverlapRatio(textChunkRects.get(1)) > 0.5
                && textChunkRects.stream().allMatch(re -> (re.getHeight() > 20 * page.getAvgCharHeight()))) {
            return textChunkRects;
        }

        List<Rectangle> layoutAreas = new ArrayList<>();
        Rectangle layoutArea;
        Rectangle baseChunkRect = textChunkRects.get(0);
        layoutArea = new Rectangle(baseChunkRect);
        boolean isFirstChunk = true;
        for (int i = 1; i < textChunkRects.size(); i++) {
            Rectangle otherChunkRect = textChunkRects.get(i);
            Rectangle tmpRect = isFirstChunk ? baseChunkRect : layoutArea;
            boolean isAtDiffFills = isDistributeAtDiffFills(page, fillGroups, baseChunkRect, otherChunkRect);
            // 垂直投影聚类
            if (baseChunkRect.verticalOverlapRatio(otherChunkRect) > 0.5 && !isAtDiffFills && !isTableOrChartHeader(page, baseChunkRect, otherChunkRect, layoutArea)
                    && overLapOfHeight(baseChunkRect, otherChunkRect) > 0.5) {
                if (isSeparatedByVlineBetweenRects(page, baseChunkRect, otherChunkRect) || isExistverticallySeparatedFills(page, baseChunkRect, otherChunkRect)
                        || isLargeDiff(page, baseChunkRect, otherChunkRect) || isDoublePage(page, baseChunkRect, otherChunkRect)) {
                    layoutAreas.add(new Rectangle(layoutArea));
                    layoutArea = new Rectangle(otherChunkRect.getBounds());
                    isFirstChunk = true;
                } else {
                    layoutArea.setRect(tmpRect.merge(otherChunkRect));
                    List<Ruling> boundingLines = isSurroundByRulings(page, baseChunkRect, otherChunkRect);
                    if (!(layoutArea.getHeight() >= (baseChunkRect.getHeight() + otherChunkRect.getHeight()))
                            && boundingLines.size() == 2 && (boundingLines.get(1).getY1() - boundingLines.get(0).getY1() < 3 * page.getAvgCharHeight())) {
                        layoutArea.setRect(boundingLines.get(0).getX1(), boundingLines.get(0).getY1(),
                                boundingLines.get(0).getX2() - boundingLines.get(0).getX1(),
                                boundingLines.get(1).getY1() - boundingLines.get(0).getY1());
                    }
                    isFirstChunk = false;
                }
            } else {
                if (baseChunkRect.horizontallyOverlapRatio(otherChunkRect) > 0.7 && !isDiffTypeTextChunks(page, baseChunkRect, otherChunkRect)
                        && FloatUtils.feq(baseChunkRect.getBottom(), otherChunkRect.getTop(), 1.5 * page.getAvgCharHeight())
                        && (FloatUtils.feq(baseChunkRect.getLeft(), otherChunkRect.getLeft(), 4 * getAvgCharWidth(page, baseChunkRect, otherChunkRect))
                        || isHaveHorizontalSameShadows(page, baseChunkRect, otherChunkRect, textChunkRects)
                        || isGroupTextChunks(page, baseChunkRect, otherChunkRect))
                        && !isSeparatedByHline(page, baseChunkRect, otherChunkRect) && !isAtDiffFills) {

                    List<Rectangle> shadowAreas = new ArrayList<>();
                    Rectangle finalBaseChunkRect = baseChunkRect;
                    Rectangle finalLayoutArea = layoutArea;
                    shadowAreas.addAll(textChunkRects.stream().filter(r -> ((finalLayoutArea.isHorizontallyOverlap(r)
                            || finalBaseChunkRect.isHorizontallyOverlap(r))
                            && (finalLayoutArea.isVerticallyOverlap(otherChunkRect) || r.isVerticallyOverlap(otherChunkRect))
                            && FloatUtils.feq(otherChunkRect.getTop(), r.getTop(), page.getMinCharHeight()))).collect(Collectors.toList()));

                    if (shadowAreas.size() > 1) {
                        if (isExistSeparateHorizontalChunks(page, layoutArea)
                                && shadowAreas.stream().anyMatch(re -> (re.horizontallyOverlapRatio(otherChunkRect) > 0.7
                                && re.verticalDistance(otherChunkRect) < 1.5 * page.getAvgCharHeight()))) {
                            layoutArea.setRect(tmpRect.merge(otherChunkRect));
                            isFirstChunk = false;
                        } else {
                            layoutAreas.add(new Rectangle(layoutArea));
                            layoutArea = new Rectangle(otherChunkRect.getBounds());
                            isFirstChunk = true;
                        }

                    } else {
                        Rectangle tmpMergeRect = new Rectangle(tmpRect);
                        if (!layoutArea.isEmpty() && layoutAreas.stream().anyMatch(r -> (r.intersects(tmpMergeRect.merge(otherChunkRect))))) {
                            layoutAreas.add(new Rectangle(layoutArea));
                            layoutArea = new Rectangle(otherChunkRect.getBounds());
                            isFirstChunk = true;
                        } else {
                            layoutArea.setRect(tmpRect.merge(otherChunkRect));
                            isFirstChunk = false;
                        }
                    }
                } else {
                    if (baseChunkRect.isHorizontallyOverlap(otherChunkRect) && !isDiffTypeTextChunks(page, baseChunkRect, otherChunkRect)
                            && baseChunkRect.verticalDistance(otherChunkRect) > page.getMinCharHeight()
                            && baseChunkRect.verticalDistance(otherChunkRect) < 20 * page.getAvgCharHeight()
                            && baseChunkRect.getWidth() < 3 * page.getAvgCharWidth()
                            && otherChunkRect.getWidth() < 3 * page.getAvgCharWidth() && !isAtDiffFills
                            /*&& !hasGapAreas(page, baseChunkRect, otherChunkRect, textChunkRects)*/) {
                        layoutArea.setRect(tmpRect.merge(otherChunkRect));
                        isFirstChunk = false;
                    } else {
                        List<TextChunk> layoutChunks = page.getTextChunks(layoutArea);
                        if (!layoutChunks.isEmpty() && isDistributeAtDiffFills(page, fillGroups, layoutChunks.get(0).toRectangle(), otherChunkRect)
                                && layoutArea.horizontalDistance(otherChunkRect) > getAvgCharWidth(page, baseChunkRect, otherChunkRect)) {
                            layoutAreas.add(new Rectangle(layoutArea));
                            layoutArea = new Rectangle(otherChunkRect.getBounds());
                            isFirstChunk = true;
                        } else {
                            double maxWidth = page.getWidth();
                            if (!layoutAreas.isEmpty()) {
                                double tmpWidth = layoutAreas.stream().max(Comparator.comparing(Rectangle::getWidth)).get().getWidth();
                                if (tmpWidth > maxWidth / 3) {
                                    maxWidth = tmpWidth;
                                }
                            }
                            if (layoutArea.verticalOverlapRatio(otherChunkRect) > 0.95 && !isTableOrChartHeader(page, baseChunkRect, otherChunkRect, layoutArea)
                                    && !isAtDiffFills
                                    && (layoutArea.getHeight() / otherChunkRect.getHeight() < 3.f)
                                    && (layoutArea.getWidth() < maxWidth / 3 || otherChunkRect.getWidth() < maxWidth / 3)
                                    && diffFontRatio(page, baseChunkRect, otherChunkRect) > 0.8) {
                                layoutArea.setRect(tmpRect.merge(otherChunkRect));
                                isFirstChunk = false;
                            } else {
                                layoutAreas.add(new Rectangle(layoutArea));
                                layoutArea = new Rectangle(otherChunkRect.getBounds());
                                isFirstChunk = true;
                            }
                        }
                    }
                }
            }
            baseChunkRect = otherChunkRect;
        }
        layoutAreas.add(layoutArea);
        mergeAreasByFills(page, fillGroups, layoutAreas);
        processRepeateAndContainedAreas(page, layoutAreas);
        return layoutAreas;
    }

    private static boolean isDiffTypeTextChunks(ContentGroupPage page, Rectangle baseArea, Rectangle otherArea) {

        List<TextChunk> areaChunks = page.getTextChunks(otherArea);
        if (areaChunks.isEmpty() || !baseArea.isHorizontallyOverlap(otherArea) || otherArea.getBottom() < baseArea.getTop()) {
            return false;
        }
        String otherTexts = "";
        for (TextChunk chunk : areaChunks) {
            otherTexts += chunk.getText();
        }

        if (otherTexts != "" && (CHART_SERIAL0.matcher(otherTexts.trim().toLowerCase()).matches()
                || CHART_SERIAL1.matcher(otherTexts.trim().toLowerCase()).matches()
                || CHART_SERIAL2.matcher(otherTexts.trim().toLowerCase()).matches()
                || TABLE_SERIAL.matcher(otherTexts.trim().toLowerCase()).matches())) {
            return true;
        }

        List<TextChunk> baseChunks = page.getTextChunks(baseArea);
        if (!baseChunks.isEmpty() && DATA_SOURCE_SERIAL.matcher(baseChunks.get(0).getText().trim().toLowerCase()).matches()) {
            return true;
        }

        return false;
    }

    // 并排的chart,table标题
    private static boolean isTableOrChartHeader(ContentGroupPage page, Rectangle baseChunkRect, Rectangle otherChunkRect, Rectangle layoutArea) {

        if (page.getText(baseChunkRect).isEmpty() || page.getText(otherChunkRect).isEmpty()
                || baseChunkRect.verticalOverlapRatio(otherChunkRect) < 0.5
                /*|| baseChunkRect.horizontalDistance(otherChunkRect) < getAvgCharWidth(page, baseChunkRect, otherChunkRect)*/) {
            return false;
        }

        List<TextChunk> baseChunks = new ArrayList<>(page.getTextChunks(baseChunkRect));
        baseChunks.sort(Comparator.comparing(TextChunk::getLeft));
        List<TextChunk> otherChunks = new ArrayList<>(page.getTextChunks(otherChunkRect));
        otherChunks.sort(Comparator.comparing(TextChunk::getLeft));
        if (baseChunkRect.getHeight() < 2 * page.getAvgCharHeight() && otherChunkRect.getHeight() < 2 * page.getAvgCharHeight()
                && (CHART_SERIAL0.matcher(baseChunks.get(0).getText().trim().toLowerCase()).matches()
                || CHART_SERIAL1.matcher(baseChunks.get(0).getText().trim().toLowerCase()).matches()
                || CHART_SERIAL2.matcher(baseChunks.get(0).getText().trim().toLowerCase()).matches()
                || TABLE_SERIAL.matcher(baseChunks.get(0).getText().trim().toLowerCase()).matches()
                || DATA_SOURCE_SERIAL.matcher(baseChunks.get(0).getText().trim().toLowerCase()).matches())
                && (CHART_SERIAL0.matcher(otherChunks.get(0).getText().trim().toLowerCase()).matches()
                || CHART_SERIAL1.matcher(otherChunks.get(0).getText().trim().toLowerCase()).matches()
                || CHART_SERIAL2.matcher(otherChunks.get(0).getText().trim().toLowerCase()).matches()
                || TABLE_SERIAL.matcher(otherChunks.get(0).getText().trim().toLowerCase()).matches())
                || DATA_SOURCE_SERIAL.matcher(otherChunks.get(0).getText().trim().toLowerCase()).matches()) {
            return true;
        }

        if (baseChunkRect.getHeight() < 2 * page.getAvgCharHeight()
                && otherChunkRect.getHeight() < 2 * page.getAvgCharHeight()) {

            boolean isEnglish = StringUtils.startsWithIgnoreCase(page.getLanguage(), "en");
            float threshold = 4.f;
            if (baseChunks.size() >= 2 && otherChunks.size() == 1) {
                threshold = isEnglish ? threshold : getAvgCharWidth(page, baseChunks.get(0), baseChunks.get(1));
                String baseText = baseChunks.get(0).getText() + baseChunks.get(1).getText();
                if (baseChunks.get(0).horizontalDistance(baseChunks.get(1)) < threshold
                        && (CHART_SERIAL0.matcher(baseText.trim().toLowerCase()).matches()
                        || CHART_SERIAL1.matcher(baseText.trim().toLowerCase()).matches()
                        || CHART_SERIAL2.matcher(baseText.trim().toLowerCase()).matches()
                        || TABLE_SERIAL.matcher(baseText.trim().toLowerCase()).matches()
                        || DATA_SOURCE_SERIAL.matcher(baseText.trim().toLowerCase()).matches())
                        && (CHART_SERIAL0.matcher(otherChunks.get(0).getText().trim().toLowerCase()).matches()
                        || CHART_SERIAL1.matcher(otherChunks.get(0).getText().trim().toLowerCase()).matches()
                        || CHART_SERIAL2.matcher(otherChunks.get(0).getText().trim().toLowerCase()).matches()
                        || TABLE_SERIAL.matcher(otherChunks.get(0).getText().trim().toLowerCase()).matches())
                        || DATA_SOURCE_SERIAL.matcher(otherChunks.get(0).getText().trim().toLowerCase()).matches()) {
                    return true;
                }

            }

            if (baseChunks.size() == 1 && otherChunks.size() >= 2) {
                threshold = isEnglish ? threshold : getAvgCharWidth(page, otherChunks.get(0), otherChunks.get(1));
                String otherText = otherChunks.get(0).getText() + otherChunks.get(1).getText();
                if (otherChunks.get(0).horizontalDistance(otherChunks.get(1)) < threshold
                        && (CHART_SERIAL0.matcher(baseChunks.get(0).getText().trim().toLowerCase()).matches()
                        || CHART_SERIAL1.matcher(baseChunks.get(0).getText().trim().toLowerCase()).matches()
                        || CHART_SERIAL2.matcher(baseChunks.get(0).getText().trim().toLowerCase()).matches()
                        || TABLE_SERIAL.matcher(baseChunks.get(0).getText().trim().toLowerCase()).matches()
                        || DATA_SOURCE_SERIAL.matcher(baseChunks.get(0).getText().trim().toLowerCase()).matches())
                        && (CHART_SERIAL0.matcher(otherText.trim().toLowerCase()).matches()
                        || CHART_SERIAL1.matcher(otherText.trim().toLowerCase()).matches()
                        || CHART_SERIAL2.matcher(otherText.trim().toLowerCase()).matches()
                        || TABLE_SERIAL.matcher(otherText.trim().toLowerCase()).matches())
                        || DATA_SOURCE_SERIAL.matcher(otherText.trim().toLowerCase()).matches()) {
                    return true;
                }
            }

            if (baseChunks.size() >= 2 && otherChunks.size() >= 2) {
                String baseText = baseChunks.get(0).getText() + baseChunks.get(1).getText();
                threshold = isEnglish ? threshold : getAvgCharWidth(page, baseChunks.get(0), baseChunks.get(1));

                String otherText = otherChunks.get(0).getText() + otherChunks.get(1).getText();
                float otherThreshold = isEnglish ? 4.f : getAvgCharWidth(page, otherChunks.get(0), otherChunks.get(1));

                if (baseChunks.get(0).horizontalDistance(baseChunks.get(1)) < threshold
                        && otherChunks.get(0).horizontalDistance(otherChunks.get(1)) < otherThreshold
                        && (CHART_SERIAL0.matcher(baseText.trim().toLowerCase()).matches()
                        || CHART_SERIAL1.matcher(baseText.trim().toLowerCase()).matches()
                        || CHART_SERIAL2.matcher(baseText.trim().toLowerCase()).matches()
                        || TABLE_SERIAL.matcher(baseText.trim().toLowerCase()).matches()
                        || DATA_SOURCE_SERIAL.matcher(baseText.trim().toLowerCase()).matches())
                        && (CHART_SERIAL0.matcher(otherText.trim().toLowerCase()).matches()
                        || CHART_SERIAL1.matcher(otherText.trim().toLowerCase()).matches()
                        || CHART_SERIAL2.matcher(otherText.trim().toLowerCase()).matches()
                        || TABLE_SERIAL.matcher(otherText.trim().toLowerCase()).matches())
                        || DATA_SOURCE_SERIAL.matcher(otherText.trim().toLowerCase()).matches()) {
                    return true;
                }
            }
        }

        List<TextChunk> layoutChunks = page.getTextChunks(layoutArea);
        if (!layoutChunks.isEmpty() && !otherChunks.isEmpty() && isHasOneRowChunks(page, layoutArea)
                && layoutArea.horizontalDistance(otherChunkRect) > getAvgCharWidth(page, layoutArea, otherChunkRect)) {
            String layoutText = "";
            for (TextChunk text : layoutChunks) {
                layoutText += text.getText();
            }
            if (layoutText != "" && (CHART_SERIAL0.matcher(layoutText.trim().toLowerCase()).matches()
                    || CHART_SERIAL1.matcher(layoutText.trim().toLowerCase()).matches()
                    || CHART_SERIAL2.matcher(layoutText.trim().toLowerCase()).matches()
                    || TABLE_SERIAL.matcher(layoutText.trim().toLowerCase()).matches()
                    || DATA_SOURCE_SERIAL.matcher(layoutText.trim().toLowerCase()).matches())
                    && (CHART_TABLE_SPLIT_SERIAL.matcher(otherChunks.get(0).getText().toLowerCase()).matches()
                    || DATA_SOURCE_SERIAL.matcher(otherChunks.get(0).getText()).matches())) {
                return true;
            }
        }

        return false;
    }

    private static List<Ruling> isSurroundByRulings(ContentGroupPage page, Rectangle baseChunk, Rectangle otherChunk) {

        if (!baseChunk.isVerticallyOverlap(otherChunk) || page.getHorizontalRulings().isEmpty()) {
            return new ArrayList<>();
        }

        float top = (baseChunk.getTop() < otherChunk.getTop()) ? baseChunk.getTop() : otherChunk.getTop();
        float bottom = (baseChunk.getBottom() > otherChunk.getBottom()) ? baseChunk.getBottom() : otherChunk.getBottom();
        float left = (baseChunk.getLeft() < otherChunk.getLeft()) ? baseChunk.getLeft() : otherChunk.getLeft();
        float right = (baseChunk.getRight() > otherChunk.getRight()) ? baseChunk.getRight() : otherChunk.getRight();
        Ruling topLine = new Ruling(top, left, right - left, 0);
        Ruling bottomLine = new Ruling(bottom, left, right - left, 0);

        List<Ruling> coverTopRulings = page.getHorizontalRulings().stream().filter(hr -> ((hr.getY1() < top || FloatUtils.feq(hr.getY1(), top, 4.f))
                && hr.horizontallyOverlapRatio(topLine) > 0.95 && FastMath.abs(hr.getY1() - top) < 2 * page.getAvgCharHeight()
                && (hr.getLeft() < left || FloatUtils.feq(hr.getLeft(), left, 4.f)) && hr.getY1() < bottom
                && (hr.getRight() > right || FloatUtils.feq(hr.getRight(), right, 4.f)))).collect(Collectors.toList());
        List<Ruling> coverBottomRulings = page.getHorizontalRulings().stream().filter(hr -> ((hr.getY1() > bottom || FloatUtils.feq(hr.getY1(), bottom, 4.f))
                && hr.horizontallyOverlapRatio(bottomLine) > 0.95 && FastMath.abs(hr.getY1() - bottom) < 2 * page.getAvgCharHeight()
                && (hr.getLeft() < left || FloatUtils.feq(hr.getLeft(), left, 4.f)) && hr.getY1() > top
                && (hr.getRight() > right || FloatUtils.feq(hr.getRight(), right, 4.f)))).collect(Collectors.toList());

        List<Ruling> targetRulings = new ArrayList<>();
        if (coverTopRulings.size() == 1 && coverTopRulings.size() == coverBottomRulings.size()) {
            Ruling coverTopLine = coverTopRulings.get(0);
            Ruling coverBottomLine = coverBottomRulings.get(0);
            if (coverTopLine.horizontallyOverlapRatio(coverBottomLine) > 0.95 && FloatUtils.feq(coverTopLine.getLeft(), coverBottomLine.getLeft(), 4.f)
                    && FloatUtils.feq(coverTopLine.getRight(), coverBottomLine.getRight(), 4.f)
                    && FloatUtils.feq(coverTopLine.getWidth(), coverBottomLine.getWidth(), 6.f)
                    /*&& FastMath.abs(coverTopLine.getY1() - coverBottomLine.getY1()) < 2.5 * page.getAvgCharHeight()*/) {
                float x1 = (coverTopLine.getLeft() < coverBottomLine.getLeft()) ? coverTopLine.getLeft() : coverBottomLine.getLeft();
                x1 = (x1 < left) ? x1 : left;
                float y1 = (coverTopLine.getY1() < top) ? top : (float)coverTopLine.getY1();
                float x2 = (coverTopLine.getRight() < coverBottomLine.getRight()) ? coverBottomLine.getRight() : coverTopLine.getRight();
                x2 = (x2 > right) ? x2 : right;
                float y2 = (coverBottomLine.getY1() < bottom) ? bottom : (float)coverBottomLine.getY1();
                float finalX = x1;
                float finalX1 = x2;
                if (!page.getVerticalRulings().stream().anyMatch(vr -> (vr.intersectsLine(coverTopLine) || vr.intersectsLine(coverBottomLine)
                        || (vr.getHeight() > 2 * page.getAvgCharHeight() && vr.getX1() > finalX && vr.getX1() < finalX1
                        && vr.getY1() > y1 && vr.getY2() < y2)))) {
                    targetRulings.add(new Ruling(y1, x1, x2 -x1, 0));
                    targetRulings.add(new Ruling(y2, x1, x2 -x1, 0));
                }
            }
        }
        return targetRulings;
    }

    private static List<Ruling> isExistSurroundRulings(ContentGroupPage page, Rectangle targetChunk, List<Rectangle> allAreas) {

        if (page.getHorizontalRulings().isEmpty() || page.getText(targetChunk).isEmpty()) {
            return new ArrayList<>();
        }

        float top = targetChunk.getTop();
        float bottom = targetChunk.getBottom();
        float left = targetChunk.getLeft();
        float right = targetChunk.getRight();
        Ruling topLine = new Ruling(top, left, right - left, 0);
        Ruling bottomLine = new Ruling(bottom, left, right - left, 0);

        List<Ruling> coverTopRulings = page.getHorizontalRulings().stream().filter(hr -> ((hr.getY1() < top || FloatUtils.feq(hr.getY1(), top, 4.f))
                && hr.horizontallyOverlapRatio(topLine) > 0.95
                && (hr.getLeft() < left || FloatUtils.feq(hr.getLeft(), left, 4.f)) && hr.getY1() < bottom
                && (hr.getRight() > right || FloatUtils.feq(hr.getRight(), right, 4.f)))).collect(Collectors.toList());
        if (!coverTopRulings.isEmpty()) {
            coverTopRulings.sort(Comparator.comparing(Ruling::getTop).reversed());
            boolean isExistTopLine = false;
            for (Ruling tmpTopLine : coverTopRulings) {
               Rectangle lineRect = tmpTopLine.toRectangle();
               if (FloatUtils.feq(tmpTopLine.getY1(), targetChunk.getTop(), 4.f) || !hasGapAreas(page, lineRect, targetChunk, allAreas)) {
                   coverTopRulings.removeIf(r -> !r.equals(tmpTopLine));
                   isExistTopLine = true;
                   break;
               }
            }
            if (!isExistTopLine) {
                coverTopRulings.clear();
                return new ArrayList<>();
            }
        }

        List<Ruling> coverBottomRulings = page.getHorizontalRulings().stream().filter(hr -> ((hr.getY1() > bottom || FloatUtils.feq(hr.getY1(), bottom, 4.f))
                && hr.horizontallyOverlapRatio(bottomLine) > 0.95
                && (hr.getLeft() < left || FloatUtils.feq(hr.getLeft(), left, 4.f)) && hr.getY1() > top
                && (hr.getRight() > right || FloatUtils.feq(hr.getRight(), right, 4.f)))).collect(Collectors.toList());
        if (!coverBottomRulings.isEmpty()) {
            coverBottomRulings.sort(Comparator.comparing(Ruling::getTop));
            boolean isExistBottomLine = false;
            for (Ruling tmpBottomLine : coverBottomRulings) {
                Rectangle lineRect = tmpBottomLine.toRectangle();
                if (FloatUtils.feq(tmpBottomLine.getY1(), targetChunk.getBottom(), 4.f)|| !hasGapAreas(page, lineRect, targetChunk, allAreas)) {
                    coverBottomRulings.removeIf(r -> !r.equals(tmpBottomLine));
                    isExistBottomLine = true;
                    break;
                }
            }
            if (!isExistBottomLine) {
                coverBottomRulings.clear();
                return new ArrayList<>();
            }
        }

        List<Ruling> targetRulings = new ArrayList<>();
        if (coverTopRulings.size() == 1 && coverTopRulings.size() == coverBottomRulings.size()) {
            Ruling coverTopLine = coverTopRulings.get(0);
            Ruling coverBottomLine = coverBottomRulings.get(0);
            if (coverTopLine.horizontallyOverlapRatio(coverBottomLine) > 0.95 && FloatUtils.feq(coverTopLine.getLeft(), coverBottomLine.getLeft(), 4.f)
                    && FloatUtils.feq(coverTopLine.getRight(), coverBottomLine.getRight(), 4.f)
                    && FloatUtils.feq(coverTopLine.getWidth(), coverBottomLine.getWidth(), 6.f)
                    /*&& FastMath.abs(coverTopLine.getY1() - coverBottomLine.getY1()) < 2.5 * page.getAvgCharHeight()*/) {
                float x1 = (coverTopLine.getLeft() < coverBottomLine.getLeft()) ? coverTopLine.getLeft() : coverBottomLine.getLeft();
                x1 = (x1 < left) ? x1 : left;
                float y1 = (coverTopLine.getY1() < top) ? top : (float)coverTopLine.getY1();
                float x2 = (coverTopLine.getRight() < coverBottomLine.getRight()) ? coverBottomLine.getRight() : coverTopLine.getRight();
                x2 = (x2 > right) ? x2 : right;
                float y2 = (coverBottomLine.getY1() < bottom) ? bottom : (float)coverBottomLine.getY1();
                float finalX = x1;
                float finalX1 = x2;
                if (!page.getVerticalRulings().stream().anyMatch(vr -> (vr.intersectsLine(coverTopLine) || vr.intersectsLine(coverBottomLine)
                        || (vr.getHeight() > 2 * page.getAvgCharHeight() && vr.getX1() > finalX && vr.getX1() < finalX1
                        && vr.getY1() > y1 && vr.getY2() < y2)))) {
                    targetRulings.add(coverTopLine);
                    targetRulings.add(coverBottomLine);
                }
            }
        }
        return targetRulings;
    }


    private static void processRepeateAndContainedAreas(ContentGroupPage page, List<Rectangle> allAreas) {

        if (allAreas.isEmpty()) {
            return;
        }
        List<Rectangle> targetAreas = new ArrayList<>();
        for (Rectangle baseRect : allAreas) {
            if (!targetAreas.isEmpty() && targetAreas.stream().anyMatch(r -> (FloatUtils.feq(r.overlapRatio(baseRect), 1.0, 0.1))
                    || r.contains(baseRect))) {
                continue;
            }
            Rectangle tmpArea = new Rectangle(baseRect);
            for (Rectangle otherRect : allAreas) {
                if (!targetAreas.isEmpty() && targetAreas.stream().anyMatch(r -> (FloatUtils.feq(r.overlapRatio(otherRect), 1.0, 0.1)
                        || r.contains(otherRect)))
                        && (baseRect == otherRect)) {
                    continue;
                }

                if ((tmpArea.intersects(otherRect) || tmpArea.overlapRatio(otherRect) > 0) && !tmpArea.equals(otherRect)) {
                    /*Rectangle insertArea = getInsertArea(page, tmpArea, otherRect);
                    if (insertArea != null && (insertArea.getHeight() < 1.f || insertArea.getWidth() < 1.f)) {
                        continue;
                    }*/
                    boolean baseIsChart = isSignalChartAreas(page, tmpArea);
                    boolean otherIsChart = isSignalChartAreas(page, otherRect);
                    if (baseIsChart && otherIsChart
                            && !(tmpArea.nearlyContains(otherRect) || otherRect.nearlyContains(tmpArea))) {
                        continue;
                    }

                    tmpArea.merge(otherRect);
                }
            }
            targetAreas.add(tmpArea);
        }
        allAreas.clear();
        targetAreas = targetAreas.stream().distinct().collect(Collectors.toList());
        allAreas.addAll(targetAreas);
    }

    private static List<Rectangle> getTopDownAreaOfBaseRect(ContentGroupPage page, Rectangle baseRect, List<Rectangle> allAreas, boolean isUpSearch) {

        if (allAreas.isEmpty()) {
            return new ArrayList<>();
        }
        List<Rectangle> targetRects = new ArrayList<>();
        List<Rectangle> candidateAreas = new ArrayList<>(allAreas);
        if (isUpSearch) {
            List<Rectangle> topAreas = candidateAreas.stream()
                    .filter(r -> (r.getBottom() < baseRect.getTop() && r.isHorizontallyOverlap(baseRect))).collect(Collectors.toList());
            if (topAreas.isEmpty()) {
                return new ArrayList<>();
            }
            topAreas.sort(Comparator.comparing(Rectangle::getBottom).reversed());
            Rectangle nearestTopArea = topAreas.get(0);
            topAreas = topAreas.stream().filter(r -> (r.isVerticallyOverlap(nearestTopArea))).collect(Collectors.toList());
            if (topAreas.isEmpty()) {
                return new ArrayList<>();
            }

            List<Rectangle> topCandidateAreas = new ArrayList<>();
            for (Rectangle tmp : topAreas) {
                if (!hasGapAreas(page, baseRect, tmp, allAreas)) {
                    if (!topCandidateAreas.isEmpty()) {
                        if (FloatUtils.feq(tmp.getBottom(), topCandidateAreas.get(topCandidateAreas.size() - 1).getBottom(), 3.5)
                                && (tmp.intersects(topCandidateAreas.get(topCandidateAreas.size() - 1)))) {
                            continue;
                        }
                    }
                    topCandidateAreas.add(tmp);
                }
            }
            if (!topCandidateAreas.isEmpty()) {
                topCandidateAreas.sort(Comparator.comparing(Rectangle::getRight).reversed());
                targetRects.addAll(topCandidateAreas);
            }
        } else {
            List<Rectangle> bottomAreas = candidateAreas.stream()
                    .filter(r -> (r.getTop() > baseRect.getBottom() && r.isHorizontallyOverlap(baseRect))).collect(Collectors.toList());
            if (bottomAreas.isEmpty()) {
                return new ArrayList<>();
            }
            bottomAreas.sort(Comparator.comparing(Rectangle::getTop));
            Rectangle nearestBottomArea = bottomAreas.get(0);
            bottomAreas = bottomAreas.stream().filter(r ->r.isVerticallyOverlap(nearestBottomArea)).collect(Collectors.toList());
            if (bottomAreas.isEmpty()) {
                return new ArrayList<>();
            }

            List<Rectangle> bottomCandidateAreas = new ArrayList<>();
            for (Rectangle tmp : bottomAreas) {
                if (!hasGapAreas(page, baseRect, tmp, allAreas)) {
                    bottomCandidateAreas.add(tmp);
                }
            }
            if (!bottomCandidateAreas.isEmpty()) {
                bottomCandidateAreas.sort(Comparator.comparing(Rectangle::getRight).reversed());
                targetRects.addAll(bottomCandidateAreas);
            }

        }
        return targetRects;
    }

    private static boolean hasAreaBetweenTwoSignalChunkRects(ContentGroupPage page, Rectangle baseRect, Rectangle otherRect, List<Rectangle> allRects) {

        if (allRects.isEmpty()) {
            return false;
        }

        Rectangle gapRect = new Rectangle();
        if (baseRect.getRight() < otherRect.getLeft() && baseRect.getBottom() < otherRect.getTop()) {
            gapRect.setRect(baseRect.getRight(), baseRect.getBottom(),
                    otherRect.getLeft() - baseRect.getRight(), otherRect.getTop() - baseRect.getBottom());

        } else if (otherRect.getRight() < baseRect.getLeft() && otherRect.getBottom() < baseRect.getTop()) {
            gapRect.setRect(otherRect.getRight(), otherRect.getBottom(),
                    baseRect.getLeft() - otherRect.getRight(), baseRect.getTop() - otherRect.getBottom());

        } else if (otherRect.getRight() < baseRect.getLeft() && baseRect.getBottom() < otherRect.getTop()) {
            gapRect.setRect(otherRect.getRight(), baseRect.getBottom(),
                    baseRect.getLeft() - otherRect.getRight(), otherRect.getTop() - baseRect.getBottom());

        } else if (baseRect.getRight() < otherRect.getLeft() && baseRect.getTop() > otherRect.getBottom()) {
            gapRect.setRect(baseRect.getRight(), otherRect.getBottom(),
                    otherRect.getLeft() - baseRect.getRight(), baseRect.getTop() - otherRect.getBottom());
        } else {
            if (hasGapAreas(page, baseRect, otherRect, allRects)) {
                return true;
            } else {
                return false;
            }
        }

        List<TextElement> gapElements = page.getText(gapRect);
        if (!gapElements.isEmpty() && allRects.stream().anyMatch(r -> (r.overlapRatio(gapRect) > 0))) {
            if (!(gapElements.size() < 3 && gapElements.stream().allMatch(TextElement::isWhitespace))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAdjacentSingnalChunkRect(ContentGroupPage page, Rectangle baseRect, Rectangle otherRect, List<Rectangle> allRects) {
        if (allRects.isEmpty()) {
            return true;
        }

        boolean isBaseTop = baseRect.getTop() > otherRect.getTop();
        boolean isBaseAreaMax = baseRect.getArea() > otherRect.getArea();
        float ratio = isBaseAreaMax ? (otherRect.getArea() / baseRect.getArea()) : (baseRect.getArea() / otherRect.getArea());
        if (ratio < 0.1) {
            Rectangle minTopRect = allRects.stream().min(Comparator.comparing(Rectangle::getTop)).get();
            Rectangle maxBottomRect = allRects.stream().max(Comparator.comparing(Rectangle::getBottom)).get();
            if (isBaseTop) {
                if (FloatUtils.feq(minTopRect.getTop(), baseRect.getTop(), 4.0)) {
                    return false;
                }
            } else {
                if (FloatUtils.feq(maxBottomRect.getBottom(), otherRect.getBottom(), 4)) {
                    return false;
                }
            }
        }

        if (baseRect.isHorizontallyOverlap(otherRect) || baseRect.isVerticallyOverlap(otherRect)) {
            return false;
        }

        List<Rectangle> baseVencRects = allRects.stream().filter(r ->r.isVerticallyOverlap(baseRect)).collect(Collectors.toList());
        List<Rectangle> otherVencRects = allRects.stream().filter(r ->r.isVerticallyOverlap(otherRect)).collect(Collectors.toList());

        if (baseVencRects.size() == 1
                && otherVencRects.size() == 1
                && !hasAreaBetweenTwoSignalChunkRects(page, baseRect, otherRect, allRects)) {
            return true;
        }
        return false;
    }

    private static boolean hasLargeDistanceAtVertically(ContentGroupPage page, Rectangle one, Rectangle other) {

        if (one == null || other == null) {
            return false;
        }
        boolean isBaseAtTop = one.getBottom() < other.getTop();
        List<TextChunk> baseChunks = page.getTextChunks(one);
        List<TextChunk> mergeChunks = page.getTextChunks(other);
        double avgHeight = 0.0, baseHeight = 0.0, mergeHeight = 0.0, gapHeight = 0.0;
        boolean isSameFont;
        if (isBaseAtTop) {
            baseHeight = baseChunks.get(baseChunks.size() - 1).getAvgCharHeight();
            mergeHeight = mergeChunks.get(0).getAvgCharHeight();
            if (baseHeight > mergeHeight) {
                avgHeight = mergeHeight;
            } else {
                avgHeight = baseHeight;
            }
            isSameFont = (baseChunks.get(baseChunks.size() - 1).getFontSize() == mergeChunks.get(0).getFontSize());
            gapHeight = other.getTop() - one.getBottom();
        } else {
            baseHeight = baseChunks.get(0).getAvgCharHeight();
            mergeHeight = mergeChunks.get(mergeChunks.size() - 1).getAvgCharHeight();
            if (baseHeight > mergeHeight) {
                avgHeight = mergeHeight;
            } else {
                avgHeight = baseHeight;
            }
            isSameFont = (baseChunks.get(0).getFontSize() == mergeChunks.get(mergeChunks.size() - 1).getFontSize());
            gapHeight = one.getTop() - other.getBottom();
        }

        if ((overLapOfWidth(one, other) < 0.2 || !isSameFont) && gapHeight > 3 * avgHeight) {
            return true;
        }

        if (gapHeight >= 5 * avgHeight) {
            return true;
        }

        return false;
    }


    private static boolean checkCanMergeBySpatialPosition(ContentGroupPage page, Rectangle baseRect, Rectangle mergeRect,
                                                          boolean isVertically, List<Rectangle> allRects, List<Rectangle> originalRects) {
        if (allRects.isEmpty()) {
            return false;
        }

        if (isVertically) {
            // 两者之间有文本块不能合并
            if (hasGapAreas(page, baseRect, mergeRect, allRects)) {
                return false;
            }

            boolean isBaseAtTop = baseRect.getBottom() < mergeRect.getTop();

            List<Rectangle> baseCoverRects, mergeCoverRects;
            List<TextChunk> baseChunks = page.getTextChunks(baseRect);
            List<TextChunk> mergeChunks = page.getTextChunks(mergeRect);
            TextChunk baseChunk, mergeChunk;
            if (isBaseAtTop) {
                baseCoverRects = getTopDownAreaOfBaseRect(page, baseRect, allRects, false);
                mergeCoverRects = getTopDownAreaOfBaseRect(page, mergeRect, allRects, true);
                baseChunk = baseChunks.get(baseChunks.size() - 1);
                mergeChunk = mergeChunks.get(0);
            } else {
                baseCoverRects = getTopDownAreaOfBaseRect(page, baseRect, allRects, true);
                mergeCoverRects = getTopDownAreaOfBaseRect(page, mergeRect, allRects, false);
                baseChunk = baseChunks.get(0);
                mergeChunk = mergeChunks.get(mergeChunks.size() - 1);
            }

            if (baseCoverRects.size() == mergeCoverRects.size() && baseCoverRects.size() == 1) {

                if (overLapOfWidth(baseRect, mergeRect) < 0.5 || hasLargeDistanceAtVertically(page, baseRect, mergeRect)) {
                    return false;
                }
                return true;
            } else if (baseCoverRects.size() > mergeCoverRects.size() && mergeCoverRects.size() == 1) {

                List<Rectangle> basicMegreCells = originalRects.stream().filter(r -> (r.overlapRatio(mergeRect) > 0.8)).collect(Collectors.toList());
                if (basicMegreCells.size() == 1 && basicMegreCells.get(0).nearlyContains(mergeRect, 1)) {
                    return false;
                }

                if (baseCoverRects.size() == 2
                        && baseCoverRects.stream().anyMatch(re -> re.equals(mergeRect))
                        && isHaveAllChartAreas(page, baseCoverRects.get(0), baseCoverRects.get(1))) {
                    return false;
                }

                // 可能为表格
                if (!PARAGRAPH_END_RE.matcher(baseChunk.getText()).matches()
                        && !PARAGRAPH_END_RE.matcher(mergeChunk.getText()).matches()) {
                    List<Rectangle> verticallRects = allRects.stream().filter(r -> r.isVerticallyOverlap(baseCoverRects.get(0))).collect(Collectors.toList());
                    for (Rectangle tmpRect : baseCoverRects) {
                        List<TextChunk> tmpChunks = page.getTextChunks(tmpRect);
                        if (PARAGRAPH_END_RE.matcher(tmpChunks.get(tmpChunks.size() - 1).getText()).matches()
                                || (allRects.stream().filter(r -> r.isVerticallyOverlap(tmpRect)).collect(Collectors.toList()).size() != verticallRects.size())) {
                            return false;
                        }
                    }
                } else {
                    // 可能为正文
                    List<Rectangle> verticallRects = allRects.stream().filter(r -> r.isVerticallyOverlap(baseCoverRects.get(0))).collect(Collectors.toList());
                    for (Rectangle tmpRect : baseCoverRects) {
                        if (allRects.stream().filter(r -> r.isVerticallyOverlap(tmpRect)).collect(Collectors.toList()).size() != verticallRects.size()) {
                            return false;
                        }
                        // 含有标点符号为正文
                        List<TextChunk> tmpChunks = page.getTextChunks(tmpRect);
                        TextChunk tmpChunk;
                        if (isBaseAtTop) {
                            tmpChunk = tmpChunks.get(0);
                        } else {
                            tmpChunk = tmpChunks.get(tmpChunks.size() - 1);
                        }

                        if (!tmpChunks.isEmpty() && PARAGRAPH_END_RE.matcher(tmpChunk.getText()).matches()) {
                            return false;
                        }
                    }
                }

                if (hasLargeDistanceAtVertically(page, baseRect, mergeRect)) {
                    return false;
                }

                if (!(isDiscreteRects(page, Arrays.asList(baseRect), isBaseAtTop)
                        && isDiscreteRects(page, Arrays.asList(mergeRect), !isBaseAtTop))) {
                    return false;
                }

                return true;
            } else  {
                return false;
            }
        } else {
            // 两者之间有文本块不能合并
            if (hasGapAreas(page, baseRect, mergeRect, allRects)) {
                return false;
            }
            boolean isBaseAtTop = baseRect.getBottom() < mergeRect.getTop();
            List<Rectangle> baseCoverRects, mergeCoverRects;
            if (isBaseAtTop) {
                baseCoverRects = getTopDownAreaOfBaseRect(page, baseRect, allRects, false);
                mergeCoverRects = getTopDownAreaOfBaseRect(page, mergeRect, allRects, true);
            } else {
                baseCoverRects = getTopDownAreaOfBaseRect(page, baseRect, allRects, true);
                mergeCoverRects = getTopDownAreaOfBaseRect(page, mergeRect, allRects, false);
            }

            List<TextChunk> baseChunks = page.getTextChunks(baseRect);
            List<TextChunk> mergeChunks = page.getTextChunks(mergeRect);
            if (baseCoverRects.size() == mergeCoverRects.size()) {
                return true;
            } else if (baseCoverRects.size() > mergeCoverRects.size() && mergeCoverRects.size() == 1) {
                if (!PARAGRAPH_END_RE.matcher(baseChunks.get(baseChunks.size() - 1).getText()).matches()
                        && !PARAGRAPH_END_RE.matcher(mergeChunks.get(mergeChunks.size() - 1).getText()).matches()) {
                    for (Rectangle tmpRect : baseCoverRects) {
                        List<TextChunk> tmpChunks = page.getTextChunks(tmpRect);
                        if (PARAGRAPH_END_RE.matcher(tmpChunks.get(tmpChunks.size() - 1).getText()).matches()) {
                            return false;
                        }
                    }
                }
                return true;
            } else  {
                return false;
            }
        }
    }

    private static boolean isDiffCoverByHline(ContentGroupPage page, Rectangle baseRect, Rectangle otherRect, List<Rectangle> allRects) {

        if (!baseRect.isHorizontallyOverlap(otherRect) || page.getHorizontalRulings().isEmpty()) {
            return false;
        }

        Rectangle topRect = (baseRect.getTop() < otherRect.getTop()) ? baseRect : otherRect;
        Ruling topLine = new Ruling(topRect.getBottom(), topRect.getLeft(), (float)topRect.getWidth(),0);
        Rectangle downRect = (baseRect.getTop() < otherRect.getTop()) ? otherRect : baseRect;
        Ruling downLine = new Ruling(downRect.getTop(), downRect.getLeft(), (float)downRect.getWidth(), 0);
        List<Ruling> coverLines = page.getHorizontalRulings().stream().filter(r -> ((r.getY1() > topRect.getBottom()
                || FloatUtils.feq(r.getY1(), topRect.getBottom(), 4.f))
                && (r.getY1() < downRect.getTop() || FloatUtils.feq(r.getY1(), downRect.getTop(), 4.f))
                && r.horizontallyOverlapRatio(topLine) > 0.9 && r.horizontallyOverlapRatio(downLine) > 0.9)).collect(Collectors.toList());
        if (coverLines.size() == 1) {
            List<Rectangle> verticalShadows0 = allRects.stream().filter(re -> (re.verticalOverlapRatio(topRect) > 0.5)).collect(Collectors.toList());
            List<Rectangle> verticalShadows1 = allRects.stream().filter(re -> (re.verticalOverlapRatio(downRect) > 0.5)).collect(Collectors.toList());
            if (verticalShadows0.size() != verticalShadows1.size()) {
                return true;
            }
        }
        return false;
    }

    private static List<Rectangle> furtherAnalysisChunksRects(ContentGroupPage page, List<Rectangle> allRects, List<Rectangle> chartAreas,
                                                              List<Rectangle> originalRects, List<FillAreaGroup> fillGroups) {

        if (allRects.isEmpty()) {
            return new ArrayList<>();
        }
        // 进一步确定版面区域, 主要处理分栏/表格类的水平多覆盖问题，即一个文本块与多个文本块有水平投影，判断为表格区域还是分栏区域
        List<Rectangle> targetRegions = new ArrayList<>();
        Rectangle baseRect = allRects.get(0);
        Rectangle layoutArea = new Rectangle(baseRect);
        boolean isFirstRect = true;
        for (int i = 1; i < allRects.size(); i++) {
            Rectangle otherRect = allRects.get(i);
            Rectangle tmpRect = isFirstRect ? baseRect : layoutArea;
            //TableDebugUtils.writeCells(page, Arrays.asList(layoutArea), "xxx_first_LayoutArea");
            //TableDebugUtils.writeCells(page, Arrays.asList(otherRect), "xxx_first_otherRect");
            boolean isLabelOfTableAndChart = false;
            if (baseRect.verticalOverlapRatio(otherRect) > 0.5) {
                isLabelOfTableAndChart = isTableOrChartHeader(page, baseRect, otherRect, layoutArea);

                if (overLapOfHeight(baseRect, otherRect) > 0.3 && overLapOfWidth(baseRect, otherRect) < 0.3
                        && diffFontRatio(page, baseRect, otherRect) > 0.95
                        && baseRect.horizontalDistance(otherRect) > 10 * getAvgCharWidth(page, baseRect, otherRect) && baseRect.equals(layoutArea)) {
                    Rectangle minHeightRect = (baseRect.getHeight() > otherRect.getHeight()) ? otherRect : baseRect;
                    Rectangle mergeRect = new Rectangle(otherRect);
                    mergeRect.merge(layoutArea);
                    if (isHasOneRowChunks(page, minHeightRect) && mergeRect.getWidth() > page.getWidth() * 0.75) {
                        layoutArea.setRect(mergeRect.merge(layoutArea));
                        isFirstRect = false;
                        baseRect = otherRect;
                        continue;
                    }
                }
            }

            if (baseRect.isHorizontallyOverlap(otherRect) && !isSeparatedByHline(page, baseRect, otherRect)) {

                if (!chartAreas.isEmpty() && baseRect.isHorizontallyOverlap(otherRect) && isSignalChartAreas(page, otherRect)
                        && chartAreas.stream().anyMatch(re -> (re.verticalOverlapRatio(otherRect) > 0.5 && !re.equals(otherRect)))) {
                    targetRegions.add(layoutArea);
                    layoutArea = new Rectangle(otherRect);
                    isFirstRect = true;
                    baseRect = otherRect;
                    continue;
                }

                Rectangle finalBaseRect1 = baseRect;
                if (!chartAreas.isEmpty() && baseRect.isHorizontallyOverlap(otherRect) && isSignalChartAreas(page, baseRect)
                        && chartAreas.stream().anyMatch(re -> (re.verticalOverlapRatio(finalBaseRect1) > 0.5 && !re.equals(finalBaseRect1)))) {
                    targetRegions.add(layoutArea);
                    layoutArea = new Rectangle(otherRect);
                    isFirstRect = true;
                    baseRect = otherRect;
                    continue;
                }

                // 判断水平投影是否该合并
                if (checkCanMergeBySpatialPosition(page, baseRect, otherRect, true, allRects, originalRects)
                        /*&& !isDiffCoverByHline(page, baseRect, otherRect, allRects)*/) {
                    isFirstRect = false;
                    layoutArea.setRect(tmpRect.merge(otherRect));
                } else {
                    targetRegions.add(layoutArea);
                    layoutArea = new Rectangle(otherRect);
                    isFirstRect = true;
                }
            } else if (baseRect.isVerticallyOverlap(otherRect) && !isDistributeAtDiffLineAreas(page, baseRect, otherRect)
                    && !isLabelOfTableAndChart
                    && FloatUtils.feq(baseRect.getBottom(), otherRect.getBottom(), 5)
                    && isDiscreteRects(page, Arrays.asList(baseRect), true)
                    && isDiscreteRects(page, Arrays.asList(otherRect), true)) {
                Rectangle finalBaseRect = baseRect;
                if (targetRegions.stream().anyMatch(re -> (re.isVerticallyOverlap(finalBaseRect)
                        && re.horizontallyOverlapRatio(otherRect) > 0.95 && overLapOfWidth(re, otherRect) > 0.95
                        && re.verticalDistance(otherRect) < page.getAvgCharHeight()))
                        || targetRegions.stream().anyMatch(re -> (re.isVerticallyOverlap(otherRect)
                        && re.horizontallyOverlapRatio(finalBaseRect) > 0.95 && overLapOfWidth(re, finalBaseRect) > 0.95
                        && re.verticalDistance(finalBaseRect) < page.getAvgCharHeight()))) {
                    targetRegions.add(layoutArea);
                    layoutArea = new Rectangle(otherRect);
                    isFirstRect = true;
                } else {
                    if (isHaveAllChartAreas(page, baseRect, otherRect) || isHaveAllChartAreas(page, layoutArea, otherRect)) {
                        targetRegions.add(layoutArea);
                        layoutArea = new Rectangle(otherRect);
                        isFirstRect = true;
                    } else {
                        isFirstRect = false;
                        layoutArea.setRect(tmpRect.merge(otherRect));
                    }
                }
            } else {
                if (baseRect.verticalOverlapRatio(otherRect) > 0.9 && !hasOtherVerticalShadowRects(page, baseRect, otherRect, allRects)
                        && !isCoverByDiffFills(page, baseRect, otherRect, fillGroups)
                        && (overLapOfHeight(baseRect, otherRect) < 0.2 || overLapOfWidth(baseRect, otherRect) < 0.2)
                        && overLapOArea(baseRect, otherRect) < 0.1 && !isLabelOfTableAndChart
                        && baseRect.horizontalDistance(otherRect) < 5 * page.getAvgCharWidth()) {
                    isFirstRect = false;
                    layoutArea.setRect(tmpRect.merge(otherRect));
                } else {
                    targetRegions.add(layoutArea);
                    layoutArea = new Rectangle(otherRect);
                    isFirstRect = true;
                }
            }
            // TableDebugUtils.writeCells(page, Arrays.asList(layoutArea), "second_LayoutArea");
            baseRect = otherRect;
        }
        targetRegions.add(layoutArea);

        if (!targetRegions.isEmpty()) {
            List<Rectangle> allMergeAreas = new ArrayList<>();
            Rectangle base = targetRegions.get(0);
            layoutArea = new Rectangle(base);
            for (int i = 1; i < targetRegions.size(); i++) {
                Rectangle other = targetRegions.get(i);
                if (!isTableOrChartHeader(page, base, other, layoutArea) && base.isVerticallyOverlap(other)
                        && isHaveVerticalSameShadows(page, base, other, targetRegions)
                        && base.horizontalDistance(other) < 5 * page.getAvgCharWidth() && !base.isHorizontallyOverlap(other)) {
                    Rectangle finalBase = base;
                    List<Rectangle> allVerticalBaseRects = targetRegions.stream()
                            .filter(r -> (r.isVerticallyOverlap(finalBase))).collect(Collectors.toList());
                    List<Rectangle> allVerticalOtherRects = targetRegions.stream()
                            .filter(r -> (r.isVerticallyOverlap(other))).collect(Collectors.toList());
                    if (allVerticalBaseRects.size() == allVerticalOtherRects.size()
                            && allVerticalBaseRects.size() == 2) {
                        boolean isBaseAreaMax = baseRect.getArea() > other.getArea();
                        float ratio = isBaseAreaMax ? (baseRect.getArea() / other.getArea()) : (other.getArea() / base.getArea());
                        if (ratio > 25) {
                           layoutArea = new Rectangle(layoutArea.merge(other));
                        }
                    }
                }
                Rectangle finalLayoutArea = layoutArea;
                if (allMergeAreas.isEmpty() || !allMergeAreas.stream().anyMatch(r -> (r.nearlyContains(finalLayoutArea)))) {
                    allMergeAreas.add(layoutArea);
                }
                base = other;
                layoutArea = new Rectangle(base);
            }
            allMergeAreas.add(layoutArea);

            for (Rectangle area : allMergeAreas) {
                if (isMultiLineOneColumnChunks(page, area) && area.getHeight() > 3 * area.getWidth()) {
                    //TableDebugUtils.writeCells(page, Arrays.asList(area), "XXXXAAA");
                    List<Rectangle> verticalRects = allMergeAreas.stream()
                            .filter(re -> (re.verticalOverlap(area) > 0.8 && !hasGapAreas(page, area, re, allMergeAreas)
                                    && !FloatUtils.feq(area.overlapRatio(re), 1.0 , 0.1)
                                    && area.horizontalDistance(re) < 5 * page.getAvgCharWidth()
                                    && re.getWidth() > 3 * area.getWidth())).collect(Collectors.toList());
                    if (!verticalRects.isEmpty()) {
                        if (verticalRects.size() == 1 /*&& !isTableOrChartHeader(page, area, verticalRects.get(0), area)*/
                                && !allMergeAreas.stream().anyMatch(re -> (re.verticalOverlapRatio(verticalRects.get(0)) > 0.8)
                                && re.horizontallyOverlapRatio(area) > 0.8 && overLapOfWidth(re, area) > 0.7)) {
                            area.merge(verticalRects.get(0));
                            verticalRects.get(0).markDeleted();
                        }

                        if (verticalRects.size() == 2) {
                            if (verticalRects.get(0).horizontalDistance(area) > verticalRects.get(1).horizontalDistance(area)) {
                                area.merge(verticalRects.get(1));
                                verticalRects.get(1).markDeleted();
                            } else {
                                area.merge(verticalRects.get(0));
                                verticalRects.get(0).markDeleted();
                            }
                        }
                    }
                }
            }
            allMergeAreas.removeIf(Rectangle::isDeleted);
            targetRegions = allMergeAreas;
        }
        return targetRegions;
    }

    // 在一定距离内无其他的垂直投影
    private static boolean hasOtherVerticalShadowRects(ContentGroupPage page, Rectangle baseRect, Rectangle otherRect, List<Rectangle> allRects) {

        if (!baseRect.isVerticallyOverlap(otherRect) || baseRect.horizontalDistance(otherRect) < 5 || baseRect.overlapRatio(otherRect) > 0) {
            return false;
        }

        Rectangle maxHeightRect = (baseRect.getHeight() > otherRect.getHeight()) ? baseRect : otherRect;
        Rectangle minHeightRect = (baseRect.getHeight() > otherRect.getHeight()) ? otherRect : baseRect;

        Rectangle gapRect = null;
        float top, left, width, height;
        if (maxHeightRect.getLeft() < minHeightRect.getLeft()) {
            top = (maxHeightRect.getTop() < minHeightRect.getTop()) ? maxHeightRect.getTop() : minHeightRect.getTop();
            left = maxHeightRect.getRight() + 1;
            width = minHeightRect.getRight() - left;
            height = ((maxHeightRect.getBottom() > minHeightRect.getBottom()) ? maxHeightRect.getBottom() : minHeightRect.getBottom()) - top;
            gapRect = new Rectangle(left, top, width, height);
        } else {
            top = (maxHeightRect.getTop() < minHeightRect.getTop()) ? maxHeightRect.getTop() : minHeightRect.getTop();
            left = minHeightRect.getLeft();
            width = maxHeightRect.getLeft() - left - 1;
            height = ((maxHeightRect.getBottom() > minHeightRect.getBottom()) ? maxHeightRect.getBottom() : minHeightRect.getBottom()) - top;
            gapRect = new Rectangle(left, top, width, height);
        }

        Rectangle finalGapRect = gapRect;
        if (gapRect != null && allRects.stream().anyMatch(re -> ((re.overlapRatio(finalGapRect) > 0.1 || finalGapRect.nearlyContains(re))
                && !re.equals(baseRect) && !re.equals(otherRect)))) {
            return true;
        }
        return false;
    }

    private static Rectangle isExisteOverlayRects(ContentGroupPage page, Rectangle baseRect, Rectangle otherRect, List<Rectangle> allAreas, List<Rectangle> mergetAreas) {

        if (allAreas.isEmpty() || baseRect == null || otherRect == null) {
            return null;
        }
        // 目前只考虑从上到下的情况
        /*
        *                             otherChunk
        *                               chunk
        *                               chunk
        *      baseChunk               targetChunk
        */

        if (baseRect.getTop() < otherRect.getBottom()) {
            return null;
        }

        List<Rectangle> baseTopRects = allAreas.stream().filter(r -> (r.getBottom() < baseRect.getTop()
                && r.isHorizontallyOverlap(baseRect) && !r.isHorizontallyOverlap(otherRect)
                && r.isVerticallyOverlap(otherRect))).collect(Collectors.toList());

        if (!baseTopRects.isEmpty()) {
            return null;
        }

        Rectangle targetRect;
        boolean isBaseAtLeft = baseRect.getRight() < otherRect.getLeft();
        List<Rectangle> baseVencRects;
        if (isBaseAtLeft) {
            baseVencRects = allAreas.stream().filter(r -> (r.verticalOverlapRatio(baseRect) > 0.8 && r.getLeft() > baseRect.getRight())).collect(Collectors.toList());
            baseVencRects.sort(Comparator.comparing(Rectangle::getLeft));
        } else {
            baseVencRects = allAreas.stream().filter(r -> (r.verticalOverlapRatio(baseRect) > 0.8 && r.getRight() < baseRect.getLeft())).collect(Collectors.toList());
            baseVencRects.sort(Comparator.comparing(Rectangle::getRight).reversed());
        }

        if (baseVencRects.isEmpty()) {
            return null;
        } else {
            targetRect = baseVencRects.get(0);
            if (mergetAreas.stream().anyMatch(re -> (re.overlapRatio(targetRect) > 0.95))) {
                return null;
            }

            if (!targetRect.isHorizontallyOverlap(otherRect)) {
                return null;
            }
            List<Rectangle> otherHencRects = allAreas.stream().filter(r -> (r.isHorizontallyOverlap(targetRect)
                    && r.getTop() > otherRect.getBottom() && r.getBottom() < targetRect.getTop())).collect(Collectors.toList());

            otherHencRects.add(otherRect);
            otherHencRects.add(targetRect);
            otherHencRects.sort(Comparator.comparing(Rectangle::getTop).reversed());
            float heightThreshold = page.getTextChunks(targetRect).get(0).getAvgCharHeight();

            int gapCnt = 0;
            Rectangle tmpBaseRect = otherHencRects.get(0);
            for (int i = 1; i < otherHencRects.size(); i++) {
                Rectangle tmpOtherRect = otherHencRects.get(i);
                if (FloatUtils.feq(tmpBaseRect.getTop(), tmpOtherRect.getBottom(), heightThreshold)) {
                    gapCnt++;
                }
                tmpBaseRect = tmpOtherRect;
            }
            if (gapCnt != otherHencRects.size() - 1) {
                return null;
            }

            int spaceCnt = 0;
            for (Rectangle tmp : otherHencRects) {
                Rectangle gapRect = new Rectangle(baseRect.getRight(), tmp.getTop(),
                        tmp.getLeft() - baseRect.getRight(), tmp.getHeight());
                if (allAreas.stream().anyMatch(r -> (r.intersects(gapRect) || r.contains(gapRect) || gapRect.contains(r)))) {
                    spaceCnt++;
                }
            }
            if (spaceCnt == 0 && baseRect.verticalDistance(otherRect) < 5 * getAvgCharHeight(page, baseRect, otherRect)) {
                for (Rectangle tmp : otherHencRects) {
                    targetRect.merge(tmp);
                }
            } else {
                return null;
            }
        }
        return targetRect;
    }

    private static boolean isPossibleCellsOfChart(ContentGroupPage page, Rectangle layerRect, Rectangle otherRect, List<Rectangle> verticallyAreas) {

        if (page.getTextChunks(layerRect).isEmpty() || page.getTextChunks(otherRect).isEmpty() || verticallyAreas.isEmpty()) {
            return false;
        }

        if (layerRect.verticalDistance(otherRect) > 20 * page.getAvgCharHeight()
                || layerRect.horizontalDistance(otherRect) > 20 * page.getAvgCharWidth()) {
            return false;
        }

        boolean isSame = ((layerRect.getWidth() > layerRect.getHeight()) == (otherRect.getWidth() > otherRect.getHeight()));
        if (isSame) {
            return false;
        }

        if (layerRect.getWidth() > layerRect.getHeight()) {
            if (isExistSeparateHorizontalChunks(page, layerRect) && isExistSeparateVerticalChunks(page, otherRect)) {
                Rectangle tmpArea = new Rectangle(layerRect.createUnion(otherRect));
                if (page.getChartRegions("LayoutAnalysisAlgorithm").stream().anyMatch(r -> (r.overlapRatio(tmpArea) > 0))
                        || page.getPageFillAreaGroup().stream().anyMatch(r -> (r.getGroupRectArea().overlapRatio(tmpArea) > 0))) {
                    return true;
                }
            }

        } else {
            if (isExistSeparateHorizontalChunks(page, otherRect) && isExistSeparateVerticalChunks(page, layerRect)) {
                Rectangle tmpArea = new Rectangle(layerRect.createUnion(otherRect));
                if (page.getChartRegions("LayoutAnalysisAlgorithm").stream().anyMatch(r -> (r.overlapRatio(tmpArea) > 0))
                        || page.getPageFillAreaGroup().stream().anyMatch(r -> (r.getGroupRectArea().overlapRatio(tmpArea) > 0))) {
                    return true;
                }
            }
        }

        return false;
    }

    private static boolean isPossibleCellsOfTable(ContentGroupPage page, Rectangle layerRect, Rectangle otherRect,
                                                  List<Rectangle> verticallyAreas, List<Rectangle> mergeRegions) {

        if (verticallyAreas.isEmpty() || !layerRect.isVerticallyOverlap(otherRect) || !(layerRect.getRight() <= otherRect.getLeft())) {
            return false;
        }

        List<TextChunk> baseChunks = page.getTextChunks(layerRect);
        List<TextChunk> otherChunks = page.getTextChunks(otherRect);
        if (!baseChunks.isEmpty() && !otherChunks.isEmpty() && layerRect.horizontalDistance(otherRect) > 4 * page.getAvgCharWidth()) {
            if (diffFontRatio(page, layerRect, otherRect) < 0.6) {
                return false;
            }
        }

        int startIndex = verticallyAreas.indexOf(otherRect);
        Rectangle baseRect;
        if (startIndex >= 1) {
            baseRect = verticallyAreas.get(startIndex - 1);
        } else {
            return false;
        }
        if (isSeparatedByVlineBetweenRects(page, baseRect, otherRect)) {
            return false;
        }

        // 排除分栏的情况
        if (baseRect.getHeight() > 5 * page.getAvgCharHeight() && otherRect.getHeight() > 5 * page.getAvgCharHeight()
                && baseRect.horizontalDistance(otherRect) > getAvgCharWidth(page, baseRect, otherRect)
                && !hasGapAreas(page, baseRect, otherRect, verticallyAreas)
                && baseRect.getWidth() > 5 * page.getAvgCharWidth() && otherRect.getWidth() > 5 * page.getAvgCharWidth()) {

            boolean isBaseAtLeft = baseRect.getLeft() < otherRect.getLeft();
            Rectangle rect1, rect2;
            if (isBaseAtLeft) {
                rect1 = new Rectangle(page.getLeft(), baseRect.getTop(), baseRect.getLeft() - page.getLeft(), baseRect.getHeight());
                rect2 = new Rectangle(otherRect.getRight(), otherRect.getTop(), page.getRight() - otherRect.getRight(), otherRect.getHeight());

            } else {
                rect1 = new Rectangle(page.getLeft(), otherRect.getTop(), otherRect.getLeft() - page.getLeft(), otherRect.getHeight());
                rect2 = new Rectangle(baseRect.getRight(), baseRect.getTop(), page.getRight() - baseRect.getRight(), baseRect.getHeight());
            }

            if (!verticallyAreas.stream().anyMatch(re -> (re.intersects(rect1) || re.intersects(rect2)))) {

                double widthRatio = isBaseAtLeft ? ((otherRect.getRight() - baseRect.getLeft()) / page.getWidth())
                        : ((baseRect.getRight() - otherRect.getLeft()) / page.getWidth());
                if (verticallyAreas.indexOf(baseRect) < verticallyAreas.indexOf(otherRect)
                        && FastMath.abs(baseRect.getBottom() - otherRect.getTop()) > 5 * getAvgCharHeight(page, baseRect, otherRect)) {
                    baseChunks.sort(Comparator.comparing(Rectangle::getBottom).reversed());
                    otherChunks.sort(Comparator.comparing(Rectangle::getTop));
                }

                if (verticallyAreas.indexOf(baseRect) > verticallyAreas.indexOf(otherRect)
                        && FastMath.abs(baseRect.getTop() - otherRect.getBottom()) > 5 * getAvgCharHeight(page, baseRect, otherRect)) {
                    otherChunks.sort(Comparator.comparing(Rectangle::getBottom).reversed());
                    baseChunks.sort(Comparator.comparing(Rectangle::getTop));
                }
                TextChunk baseChunk = baseChunks.get(0);
                TextChunk otherChunk = otherChunks.get(0);
                List<TextChunk> baseVChunks = baseChunks.stream().filter(te -> (te.verticalOverlapRatio(baseChunk) > 0.5)).collect(Collectors.toList());
                List<TextChunk> otherVChunks = otherChunks.stream().filter(te -> (te.verticalOverlapRatio(otherChunk) > 0.5)).collect(Collectors.toList());

                if (baseVChunks.size() == otherVChunks.size() && baseVChunks.size() == 1 && widthRatio > 0.6) {
                    return false;
                }
            }

            // 对区域行进行分析
            baseChunks.sort(Comparator.comparing(Rectangle::getTop));
            int cnt = 0;
            TextChunk chunk1 = baseChunks.get(0);
            for (int i = 1; i < baseChunks.size(); i++) {
                TextChunk chunk2 = baseChunks.get(i);
                if (chunk1.horizontallyOverlapRatio(chunk2) > 0.5) {
                    cnt++;
                    chunk1 = chunk2;
                } else {
                    break;
                }
            }
            if (cnt == baseChunks.size() - 1) {
                cnt = 0;
                otherChunks.sort(Comparator.comparing(Rectangle::getTop));
                chunk1 = otherChunks.get(0);
                for (int i = 1; i < otherChunks.size(); i++) {
                    TextChunk chunk2 = otherChunks.get(i);
                    if (chunk1.horizontallyOverlapRatio(chunk2) > 0.5) {
                        cnt++;
                        chunk1 = chunk2;
                    } else {
                        break;
                    }
                }
                if (cnt == otherChunks.size() - 1) {
                    return false;
                }
            }

            if (!isHaveVerticalSameShadows(page, layerRect, otherRect, verticallyAreas)) {
                return false;
            }

            if ((layerRect.getHeight() > 20 * page.getAvgCharHeight() || otherRect.getHeight() > 20 * page.getAvgCharHeight())
                    && overLapOfWidth(layerRect, otherRect) > 0.7 && layerRect.getWidth() > 15 * page.getAvgCharWidth()) {
                return false;
            }
        }

        if (layerRect.verticalOverlapRatio(otherRect) > 0.95 && layerRect.horizontalDistance(otherRect) > page.getAvgCharHeight()) {
            Rectangle maxRect = (layerRect.getHeight() > otherRect.getHeight()) ? layerRect : otherRect;
            Rectangle minRect = (layerRect.getHeight() > otherRect.getHeight()) ? otherRect : layerRect;
            if (maxRect.getHeight() >= 2 * minRect.getHeight() && isHasOneRowChunks(page, minRect)) {
                List<TextChunk> maxChunks = page.getTextChunks(maxRect);
                List<TextChunk> verticalChunks = maxChunks.stream().filter(re -> (re.isVerticallyOverlap(minRect))).collect(Collectors.toList());
                if (minRect.getHeight() < 1.5 * page.getAvgCharHeight()
                        && (verticalChunks.isEmpty() || verticalChunks.stream().allMatch(te -> (te.verticalOverlapRatio(minRect) < 0.5)))) {
                    return true;
                }
            }
        }


        if (layerRect.verticalOverlapRatio(otherRect) > 0.5 && layerRect.horizontalDistance(otherRect) < 2 * page.getAvgCharWidth()
                && layerRect.getWidth() < page.getWidth() / 5 && otherRect.getWidth() < page.getWidth() / 5
                && (otherRect.getHeight() < 1.5 * page.getAvgCharHeight() || layerRect.getHeight() < 1.5 * page.getAvgCharHeight())) {
            return true;
        }

        List<Rectangle> coverRects = mergeRegions.stream().filter(re -> (re.horizontallyOverlapRatio(layerRect) > 0.9
                && re.horizontallyOverlapRatio(otherRect) > 0.9 && !hasGapAreas(page, layerRect, re, verticallyAreas)
                && !hasGapAreas(page, otherRect, re, verticallyAreas) && re.getBottom() < layerRect.getTop()
                && re.getBottom() < otherRect.getTop())).collect(Collectors.toList());
        if (coverRects.size() == 1) {
            List<Rectangle> coverShadowAreas = verticallyAreas.stream().filter(re -> (re.horizontallyOverlapRatio(coverRects.get(0)) > 0.9
                    && !hasGapAreas(page, coverRects.get(0), re, verticallyAreas)
                    && coverRects.get(0).getBottom() < re.getTop())).collect(Collectors.toList());
            if (coverShadowAreas.size() == 2 && coverShadowAreas.get(0).verticalOverlapRatio(coverShadowAreas.get(1)) > 0.5
                    && !hasGapAreas(page, coverShadowAreas.get(0), coverShadowAreas.get(1), verticallyAreas)) {
                coverShadowAreas.sort(Comparator.comparing(Rectangle::getLeft));
                Rectangle leftRect = (layerRect.getLeft() < otherRect.getLeft()) ? layerRect : otherRect;
                Rectangle rightRect = (layerRect.getLeft() < otherRect.getLeft()) ? otherRect : layerRect;
                if (leftRect.overlapRatio(coverShadowAreas.get(0)) > 0.95 && rightRect.overlapRatio(coverShadowAreas.get(1)) > 0.95) {

                    List<Rectangle> coverChunks = splitJointSmallChunkRects(page, page.getTextChunks(coverRects.get(0))
                            .stream().map(Rectangle::new).collect(Collectors.toList()));
                    coverChunks.sort(Comparator.comparing(Rectangle::getBottom).reversed());
                    List<Rectangle> subVerticalyShadows = coverChunks.stream()
                            .filter(re -> (re.verticalOverlapRatio(coverChunks.get(0)) > 0.5)).collect(Collectors.toList());
                    // 对应的样本：东方证券_行业深度_42db294aa4e94382bf269fd46c6e9d61.pdf
                    if (subVerticalyShadows.size() != 2) {
                        subVerticalyShadows = verticallyAreas.stream().filter(re -> (coverRects.get(0).contains(re))).collect(Collectors.toList());
                    }
                    if (subVerticalyShadows.size() == 2) {

                        subVerticalyShadows.sort(Comparator.comparing(Rectangle::getLeft));
                        List<Rectangle> layerRectShadows = verticallyAreas.stream().filter(re -> (re.verticalOverlapRatio(layerRect) > 0.9)).collect(Collectors.toList());
                        List<Rectangle> otherRectShadows = verticallyAreas.stream().filter(re -> (re.verticalOverlapRatio(layerRect) > 0.9)).collect(Collectors.toList());

                        if (layerRectShadows.size() == otherRectShadows.size() && layerRectShadows.size() == 2) {
                            layerRectShadows.sort(Comparator.comparing(Rectangle::getLeft));
                            otherRectShadows.sort(Comparator.comparing(Rectangle::getLeft));
                            if (layerRectShadows.get(0).equals(otherRectShadows.get(0)) && layerRectShadows.get(1).equals(otherRectShadows.get(1))
                                    && leftRect.horizontallyOverlapRatio(subVerticalyShadows.get(0)) > 0.9
                                    && rightRect.horizontallyOverlapRatio(subVerticalyShadows.get(1)) > 0.9) {
                                double widthRatio;
                                Rectangle minHeightRect = (leftRect.getHeight() < otherRect.getHeight()) ? leftRect : otherRect;
                                Rectangle maxHeightRect = (leftRect.getHeight() < otherRect.getHeight()) ? otherRect : leftRect;
                                Rectangle minWidthRect = (leftRect.getWidth() < otherRect.getWidth()) ? leftRect : otherRect;
                                Rectangle maxWidthRect = (leftRect.getWidth() < otherRect.getWidth()) ? otherRect : leftRect;
                                if (minHeightRect.getHeight() < 3 * page.getAvgCharHeight()) {
                                    widthRatio = minWidthRect.getWidth() / maxWidthRect.getWidth();
                                    if (widthRatio < 0.5 && (minHeightRect.getTop() > maxHeightRect.getTop() + 2)
                                            && (maxHeightRect.getBottom() > minHeightRect.getBottom() + 2)) {
                                        return true;
                                    }
                                }
                            }
                        }

                        if (leftRect.horizontalDistance(rightRect) / subVerticalyShadows.get(0).horizontalDistance(subVerticalyShadows.get(1)) < 0.3) {
                            double ratio;
                            if (page.getWidth() > 800) {
                                ratio = (rightRect.getRight() - leftRect.getLeft()) / (page.getWidth() / 2);
                            } else {
                                ratio = (rightRect.getRight() - leftRect.getLeft()) / page.getWidth();
                            }
                            if (ratio > 0.6 && leftRect.horizontalDistance(rightRect) < 10 * getAvgCharWidth(page, leftRect, rightRect)) {
                                return false;
                            }
                        }
                    }
                }
            }
        }

        if (layerRect.verticalOverlapRatio(otherRect) > 0.5 && !hasGapAreas(page, layerRect, otherRect, verticallyAreas)) {

            Rectangle tmp = new Rectangle(layerRect);
            tmp.merge(otherRect);
            double ratio;
            if (page.getWidth() > 800) {
                ratio = tmp.getWidth() / (page.getWidth() / 2);
            } else {
                ratio = tmp.getWidth() / page.getWidth();
            }

            float avgHeight = getAvgCharHeight(page, layerRect, otherRect);
            float avgWidth = getAvgCharWidth(page, layerRect, otherRect);
            if (ratio > 0.6 && overLapOfWidth(layerRect, otherRect) > 0.7 && overLapOfHeight(layerRect, otherRect) > 0.7
                    && layerRect.horizontalDistance(otherRect) >= 2 * avgWidth
                    && !(layerRect.getHeight() < 3 * avgHeight || otherRect.getHeight() < 3 * avgHeight)) {
                return false;
            }
        }

        List<Rectangle> otherCells = verticallyAreas.stream().filter(r -> (r.isVerticallyOverlap(otherRect))).collect(Collectors.toList());
        List<Rectangle> baseCells = verticallyAreas.stream().filter(r -> (r.isVerticallyOverlap(baseRect))).collect(Collectors.toList());
        if (baseCells.size() == otherCells.size() && (otherCells.size() >= 3
                || (isExistSeparateHorizontalChunks(page, baseRect) || isExistSeparateHorizontalChunks(page, otherRect)))) {
            baseCells.sort(Comparator.comparing(Rectangle::getLeft));
            otherCells.sort(Comparator.comparing(Rectangle::getLeft));
            for (int i = 0; i < otherCells.size(); i++) {
                if (!FloatUtils.feq(baseCells.get(i).overlapRatio(otherCells.get(i)), 1.0, 0.1)) {
                    return false;
                }
            }
        } else {
            if (baseCells.size() > otherCells.size() && isExistSeparateHorizontalChunks(page, baseRect)) {
                baseCells.removeAll(otherCells);
                Rectangle otherLastRect = otherCells.get(otherCells.size() - 1);
                Rectangle baseFirstRect = baseCells.get(0);
                int index1 = verticallyAreas.indexOf(otherLastRect);
                int index2 = verticallyAreas.indexOf(baseFirstRect);
                int cnt = 0;
                float height = (float) (1.5 * getAvgCharHeight(page, baseFirstRect, otherLastRect));
                if (index2 - index1 == 1
                        && FloatUtils.feq(baseFirstRect.getTop(), otherLastRect.getBottom(), height)
                        && baseFirstRect.horizontallyOverlapRatio(otherLastRect) > 0.8) {
                    cnt++;
                }
                for (int i = 1; i < baseCells.size(); i++) {

                    if ((verticallyAreas.indexOf(baseCells.get(i)) - index2) == 1
                            && FloatUtils.feq(verticallyAreas.get(index2).getBottom(), baseCells.get(i).getTop(), height))  {
                        index2 = verticallyAreas.indexOf(baseCells.get(i));
                        cnt++;
                    }
                }
                if (cnt == baseCells.size()) {
                    return true;
                }
            }
            return false;
        }

        // 按照从左到右的流顺序
        List<Rectangle> verticallyCells = verticallyAreas.stream()
                .filter(r -> (r.isVerticallyOverlap(layerRect) && (r != layerRect) && r.getLeft() > layerRect.getRight())).collect(Collectors.toList());
        verticallyCells.sort(Comparator.comparing(Rectangle::getLeft));

        int count = startIndex;
        for (Rectangle cell : verticallyCells) {
            if (startIndex == verticallyAreas.size()) {
                break;
            }
            if (verticallyAreas.get(startIndex) == cell) {
                startIndex++;
            } else {
                break;
            }
        }

        if ((startIndex - count) == verticallyCells.size()) {
            return true;
        }

        return false;
    }

    private static boolean smallCellOfTable(ContentGroupPage page, Rectangle baseRect, Rectangle otherRect, List<Rectangle> allRects) {

        List<TextChunk> baseChunks = page.getTextChunks(baseRect);
        List<TextChunk> otherChunks = page.getTextChunks(otherRect);

        if (baseChunks.isEmpty() || otherChunks.isEmpty()) {
            return false;
        }

        if (baseRect.isVerticallyOverlap(otherRect) && (baseRect.getLeft() < otherRect.getLeft())
                && FloatUtils.feq(baseRect.getBottom(), otherRect.getBottom(), page.getMinCharHeight())
                && (baseRect.getHeight() < otherRect.getLeft()) && (baseRect.getTop() > (otherRect.getTop() + 2)
                && baseRect.horizontalDistance(otherRect) > 2.5 * getAvgCharWidth(page, baseRect, otherRect))
                && otherRect.getHeight() < 3.5 * page.getAvgCharHeight() && baseRect.getWidth() < 15 * page.getAvgCharWidth()
                && otherRect.getWidth() < 15 * page.getAvgCharWidth() && isHaveVerticalSameShadows(page, baseRect, otherRect, allRects)) {

            int baseIndex = page.getTextChunks().indexOf(baseChunks.get(baseChunks.size() - 1));
            int otherIndex = page.getTextChunks().indexOf(otherChunks.get(0));
            if (otherIndex - baseIndex == 1) {
                return true;
            }
        }

        if (baseRect.isHorizontallyOverlap(otherRect) && baseRect.verticalDistance(otherRect) < page.getAvgCharHeight()
                && (baseRect.getWidth() < 10 * page.getAvgCharWidth() || otherRect.getWidth() < 10 * page.getAvgCharWidth())
                && !FloatUtils.feq(baseRect.getLeft(), otherRect.getLeft(), 2 * page.getAvgCharWidth())
                && baseRect.getHeight() < 3 * page.getAvgCharHeight() && otherRect.getHeight() < 3 * page.getAvgCharHeight()
                && (isExistSeparateHorizontalChunks(page, baseRect) || isExistSeparateHorizontalChunks(page, otherRect))
                && baseRect.getTop() < otherRect.getTop()) {
            int baseIndex = page.getTextChunks().indexOf(baseChunks.get(baseChunks.size() - 1));
            int otherIndex = page.getTextChunks().indexOf(otherChunks.get(0));
            if (otherIndex - baseIndex == 1) {
                return true;
            }
        }
        return false;
    }

    private static void extendChartAreaByLines(ContentGroupPage page, Rectangle chartArea) {
        if (page.getVisibleRulings().isEmpty()) {
            return;
        }
        List<Ruling> hLines = page.getHorizontalRulings().stream().filter(r -> (r.intersects(chartArea)
                && (r.getCenterY() > chartArea.getTop() && r.getCenterY() < chartArea.getBottom()))).collect(Collectors.toList());
        List<Ruling> vLines = page.getVerticalRulings().stream().filter(r -> (r.intersects(chartArea)
                && (r.getCenterX() > chartArea.getLeft() && r.getCenterX() < chartArea.getRight()))).collect(Collectors.toList());
        if (!hLines.isEmpty()) {
            Ruling minLeftRuling = hLines.stream().min(Comparator.comparing(Ruling::getLeft)).get();
            Ruling maxRightRuling = hLines.stream().max(Comparator.comparing(Ruling::getRight)).get();
            if (minLeftRuling.getX1() < chartArea.getLeft()) {
                chartArea.setLeft(minLeftRuling.getLeft());
            }
            if (maxRightRuling.getRight() > chartArea.getRight()) {
                chartArea.setRight(maxRightRuling.getRight());
            }
        }

        if (!vLines.isEmpty()) {
            Ruling minTopRuling = vLines.stream().min(Comparator.comparing(Ruling::getTop)).get();
            Ruling maxBottomRuling = vLines.stream().max(Comparator.comparing(Ruling::getBottom)).get();
            if (minTopRuling.getY1() < chartArea.getTop()) {
                chartArea.setTop(minTopRuling.getTop());
            }
            if (maxBottomRuling.getBottom() > chartArea.getBottom()) {
                chartArea.setBottom(maxBottomRuling.getBottom());
            }
        }
    }

    private static boolean hasSameNumChunks(ContentGroupPage page, Rectangle baseRect, Rectangle otherRect) {

        List<TextChunk> baseChunks = new ArrayList<>(page.getTextChunks(baseRect));
        List<TextChunk> otherChunks = new ArrayList<>(page.getTextChunks(otherRect));

        if (baseChunks.size() < 3 || otherChunks.size() < 3 || baseChunks.size() != otherChunks.size()) {
            return false;
        }

        baseChunks.sort(Comparator.comparing(TextChunk::getLeft));
        otherChunks.sort(Comparator.comparing(TextChunk::getLeft));

        for (int i = 0; i < baseChunks.size(); i++) {
            if (baseChunks.get(i).horizontallyOverlapRatio(otherChunks.get(i)) > 0.5) {
                continue;
            } else {
                return false;
            }
        }

        return true;
    }

    private static List<Rectangle> mergeOverlayRects(ContentGroupPage page, List<Rectangle> allAreas, List<FillAreaGroup> fillGroups) {
        if (allAreas.isEmpty()) {
            return new ArrayList<>();
        }

        if (page.getPageNumber() == 1 && allAreas.size() == 2 && allAreas.get(0).verticalOverlapRatio(allAreas.get(1)) > 0.8
                && allAreas.stream().allMatch(re -> (re.getHeight() > 20 * page.getAvgCharHeight()))) {
            return allAreas;
        }

        List<Rectangle> verticallyAreas = new ArrayList<>(allAreas);
        filterMarginText(page, verticallyAreas);
        if (verticallyAreas.isEmpty()) {
            return new ArrayList<>();
        }
        List<Rectangle> mergeRegions = new ArrayList<>();
        // 按照流的顺序，相邻的文本既无水平投影，也无垂直投影，合并疑似为表格的多行文本
        Rectangle baseRect = verticallyAreas.get(0);
        Rectangle layoutArea = new Rectangle(baseRect);
        boolean charLabel = false;
        for (int i = 1; i < verticallyAreas.size(); i++) {
            Rectangle otherRect = verticallyAreas.get(i);
            //TableDebugUtils.writeCells(page, Arrays.asList(otherRect),"xxxotherRect");
            //TableDebugUtils.writeCells(page, Arrays.asList(layoutArea),"xxxlayoutArea");
            //TableDebugUtils.writeCells(page, Arrays.asList(baseRect),"xxxbaseRect");
            //TableDebugUtils.writeCells(page, mergeRegions,"xxxmergeRegions");
            if (otherRect.contains(layoutArea) || otherRect.nearlyContains(layoutArea, 1.0)
                    || (FloatUtils.feq(baseRect.getWidth(), otherRect.getWidth(), page.getAvgCharWidth()) && baseRect.isHorizontallyOverlap(otherRect)
                    && baseRect.getWidth() > 15 * page.getAvgCharWidth() && baseRect.verticalDistance(otherRect) < 1.5 * page.getAvgCharHeight()
                    && hasSameNumChunks(page, baseRect, otherRect))) {
                Rectangle mergeRect = new Rectangle(otherRect);
                layoutArea.setRect(mergeRect.merge(layoutArea));
                baseRect = otherRect;
                if (charLabel) {
                    charLabel = false;
                }
                continue;
            }

            if (layoutArea.nearlyContains(otherRect, 1.0)) {
                baseRect = otherRect;
                continue;
            }

            boolean isAtDiffFills = isDistributeAtDiffFills(page, fillGroups, baseRect, otherRect)
                    && baseRect.horizontalDistance(otherRect) > page.getAvgCharWidth();

            if (baseRect.verticalOverlapRatio(otherRect) > 0.5 && !isAtDiffFills) {
                List<Ruling> baseRulings = isExistSurroundRulings(page, baseRect, verticallyAreas);
                List<Ruling> otherRulings = isExistSurroundRulings(page, otherRect, verticallyAreas);
                if (baseRulings.size() == otherRulings.size() && baseRulings.size() == 2
                        && baseRulings.get(0).equals(otherRulings.get(0)) && baseRulings.get(1).equals(otherRulings.get(1))) {
                    Ruling topLine = baseRulings.get(0);
                    Ruling bottomLine = baseRulings.get(1);
                    Rectangle finalBaseRect = baseRect;
                    List<Rectangle> baseShadows = verticallyAreas.stream().filter(re -> (re.verticalOverlapRatio(finalBaseRect) > 0.8)).collect(Collectors.toList());
                    List<Rectangle> otherShadows = verticallyAreas.stream().filter(re -> (re.verticalOverlapRatio(otherRect) > 0.8)).collect(Collectors.toList());
                    if (baseShadows.size() == otherShadows.size() && baseShadows.size() > 2) {
                        int cnt = 0;
                        for (Rectangle tmpRect : baseShadows) {
                            if (tmpRect.overlapRatio(baseRect) > 0.95 || tmpRect.overlapRatio(otherRect) > 0.95) {
                                cnt++;
                                continue;
                            }
                            List<Ruling> tmpRulings = isExistSurroundRulings(page, tmpRect, verticallyAreas);
                            if (tmpRulings.size() == 2 && tmpRulings.get(0).equals(topLine) && tmpRulings.get(1).equals(bottomLine)) {
                                cnt++;
                            }
                        }
                        if (cnt == baseShadows.size()) {
                            double x1 = baseShadows.stream().min(Comparator.comparing(Rectangle::getLeft)).get().getLeft();
                            double x2 = baseShadows.stream().max(Comparator.comparing(Rectangle::getRight)).get().getRight();
                            double y1 = baseShadows.stream().min(Comparator.comparing(Rectangle::getTop)).get().getTop();
                            double y2 = baseShadows.stream().max(Comparator.comparing(Rectangle::getBottom)).get().getBottom();
                            x1 = (x1 < topLine.getX1()) ? x1 : topLine.getX1();
                            x2 = (x2 > topLine.getX2())? x2 : topLine.getX2();
                            y1 = (y1 < topLine.getY1()) ? y1 : topLine.getY1();
                            y2 = (y2 > bottomLine.getY1()) ? y2 : bottomLine.getY1();
                            layoutArea.setRect(x1, y1,x2 - x1, y2 - y1);
                            baseRect = otherRect;
                            continue;
                        }
                    }
                }

                if (isHasOneRowChunks(page, baseRect) && isHasOneRowChunks(page, otherRect) && isTableOrChartHeader(page, baseRect, otherRect, layoutArea)) {
                    mergeRegions.add(layoutArea);
                    baseRect = otherRect;
                    layoutArea = new Rectangle(otherRect);
                    continue;
                }
            }

            boolean isTextOfTable = isPossibleCellsOfTable(page, layoutArea, otherRect, verticallyAreas, mergeRegions);
            boolean isTextOfChart = isPossibleCellsOfChart(page, layoutArea, otherRect, verticallyAreas);
            if ((isTextOfTable || isTextOfChart || smallCellOfTable(page, layoutArea, otherRect, verticallyAreas)) && !isAtDiffFills) {
                Rectangle mergeRect = new Rectangle(otherRect);
                layoutArea.setRect(mergeRect.merge(layoutArea));
                if (isTextOfChart) {
                    extendChartAreaByLines(page, layoutArea);
                    charLabel = true;
                }
            } else {
                List<Rectangle> tmpMergeAreas = new ArrayList<>();
                tmpMergeAreas.add(layoutArea);
                tmpMergeAreas.add(otherRect);
                boolean hasGap = hasGapAreas(page, layoutArea, otherRect, allAreas);
                if (charLabel && otherRect.isVerticallyOverlap(layoutArea) && !hasGap
                        && otherRect.getHeight() < 2 * page.getAvgCharHeight() && (otherRect.getWidth() / layoutArea.getWidth() < 0.3)
                        && (otherRect.getTop() > (layoutArea.getTop() + otherRect.getHeight()))
                        && (layoutArea.getBottom() > (otherRect.getBottom() + otherRect.getHeight()))
                        && !isSeparatedByVline(page, tmpMergeAreas)) {
                    Rectangle mergeRect = new Rectangle(otherRect);
                    layoutArea.setRect(mergeRect.merge(layoutArea));
                    baseRect = otherRect;
                    continue;
                } else {
                    charLabel = false;
                }

                if (otherRect.verticalOverlapRatio(layoutArea) > 0.8 && !hasGap) {
                    List<Ruling> boundingLines = isSurroundByRulings(page, layoutArea, otherRect);
                    if (boundingLines.size() == 2 && (layoutArea.getHeight() < 10 * page.getAvgCharHeight()
                            && otherRect.getHeight() < 10 * page.getAvgCharHeight())) {
                        Rectangle mergeRect = new Rectangle(otherRect);
                        layoutArea.setRect(mergeRect.merge(layoutArea));
                        layoutArea.setRect(boundingLines.get(0).getX1(), boundingLines.get(0).getY1(),
                                boundingLines.get(0).getX2() - boundingLines.get(0).getX1(),
                                boundingLines.get(1).getY1() - boundingLines.get(0).getY1());
                        baseRect = otherRect;
                        continue;
                    } else {
                        if (baseRect.verticalOverlapRatio(otherRect) > 0.9 && !hasGapAreas(page, baseRect, otherRect, allAreas)
                                && !mergeRegions.isEmpty()) {
                            Rectangle finalBaseRect1 = baseRect;
                            List<Rectangle> tmpBaseShadows = allAreas.stream().filter(re -> (re.isVerticallyOverlap(finalBaseRect1)
                                    && !re.equals(finalBaseRect1))).collect(Collectors.toList());
                            List<Rectangle> tmpOtherShadows = allAreas.stream().filter(re -> (re.isVerticallyOverlap(otherRect)
                                    && !re.equals(otherRect))).collect(Collectors.toList());
                            if (tmpBaseShadows.size() == 1 && tmpBaseShadows.size() == tmpOtherShadows.size()
                                    && tmpBaseShadows.get(0).equals(otherRect) && tmpOtherShadows.get(0).equals(baseRect)) {
                                Rectangle lastRect = mergeRegions.get(mergeRegions.size() - 1);
                                List<TextChunk> lastChunks = page.getTextChunks(lastRect);
                                if (lastRect.getHeight() < 1.5 * page.getAvgCharHeight() && lastChunks.size() == 2
                                        && lastChunks.get(0).horizontalDistance(lastChunks.get(1)) > page.getAvgCharWidth()
                                        && FloatUtils.feq(lastChunks.get(0).getLeft(), baseRect.getLeft(), 4.f)
                                        && FloatUtils.feq(lastChunks.get(1).getLeft(), otherRect.getLeft(), 4.f)) {
                                    Rectangle mergeRect = new Rectangle(otherRect);
                                    layoutArea.setRect(mergeRect.merge(layoutArea));
                                    baseRect = otherRect;
                                    continue;
                                }
                            }
                        }
                    }
                }

                if (layoutArea.isHorizontallyOverlap(otherRect) || layoutArea.isVerticallyOverlap(otherRect)) {
                    mergeRegions.add(layoutArea);
                    if (otherRect.contains(layoutArea)) {
                        baseRect = otherRect;
                        continue;
                    }
                    layoutArea = new Rectangle(otherRect);
                } else {
                    Rectangle mergeRect = isExisteOverlayRects(page, layoutArea, otherRect, verticallyAreas, mergeRegions);
                    if (mergeRect != null) {
                        layoutArea.setRect(mergeRect.merge(layoutArea));
                    } else {
                        mergeRegions.add(layoutArea);
                        if (otherRect.contains(layoutArea)) {
                            baseRect = otherRect;
                            continue;
                        }
                        layoutArea = new Rectangle(otherRect);
                    }
                }
            }
            baseRect = otherRect;
        }
        mergeRegions.add(layoutArea);
        processRepeateAndContainedAreas(page, mergeRegions);
        mergeRegions = processChartOrTableHeaderTextAreas(page, mergeRegions);
        mergeRegions = splitChartOrTableAreas(page, mergeRegions);
        return mergeRegions;
    }


    private static Rectangle getAreaByChunks(List<TextChunk> allChunks) {

        if (allChunks.isEmpty()) {
            return null;
        }

        float left = allChunks.stream().min(Comparator.comparing(TextChunk::getLeft)).get().getLeft();
        float width = allChunks.stream().max(Comparator.comparing(TextChunk::getRight)).get().getRight() - left;
        float top = allChunks.stream().min(Comparator.comparing(TextChunk::getTop)).get().getTop();
        float height = allChunks.stream().max(Comparator.comparing(TextChunk::getBottom)).get().getBottom() - top;

        return new Rectangle(left, top, width, height);
    }

    private static List<Rectangle> processChartOrTableHeaderTextAreas(ContentGroupPage page, List<Rectangle> textRegions) {

        List<Rectangle> targetAreas = new ArrayList<>();
        if (textRegions.isEmpty()) {
            return targetAreas;
        }

        List<Rectangle> mergeRegions = new ArrayList<>(textRegions);
        for (Rectangle headerTextArea : mergeRegions) {

            if (!targetAreas.isEmpty() && targetAreas.stream().anyMatch(re -> re.contains(headerTextArea))) {
                continue;
            }
            List<TextChunk> headerChunks = page.getTextChunks(headerTextArea);
            if (!headerChunks.isEmpty() && isHasOneRowChunks(page, headerTextArea)) {

                String headerTexts = PostProcessTableRegionsAlgorithm.getSequenceOfChunks(page, headerChunks);
                if (!headerTexts.equals("") && CHART_SERIAL0.matcher(headerTexts.trim().toLowerCase()).matches()
                        || CHART_SERIAL1.matcher(headerTexts.trim().toLowerCase()).matches()
                        || CHART_SERIAL2.matcher(headerTexts.trim().toLowerCase()).matches()
                        || TABLE_SERIAL.matcher(headerTexts.trim().toLowerCase()).matches()) {

                    //TableDebugUtils.writeCells(page, Arrays.asList(headerTextArea), "xxx_headerTextArea");

                    List<Rectangle> horizontalShadows = textRegions.stream().filter(re -> (re.horizontallyOverlapRatio(headerTextArea) > 0.5
                            && isHasOneRowChunks(page, re) && re.getTop() > headerTextArea.getBottom())).collect(Collectors.toList());
                    horizontalShadows.sort(Comparator.comparing(Rectangle::getTop));
                    for (Rectangle horizontalShadowRect : horizontalShadows) {
                        List<TextChunk> tailChunks = page.getTextChunks(horizontalShadowRect);
                        String tailTexts = PostProcessTableRegionsAlgorithm.getSequenceOfChunks(page, tailChunks);
                        if (!tailTexts.equals("") && CHART_SERIAL0.matcher(tailTexts.trim().toLowerCase()).matches()
                                || CHART_SERIAL1.matcher(tailTexts.trim().toLowerCase()).matches()
                                || CHART_SERIAL2.matcher(tailTexts.trim().toLowerCase()).matches()
                                || TABLE_SERIAL.matcher(tailTexts.trim().toLowerCase()).matches()) {
                            break;
                        }

                        if (!tailTexts.equals("") && DATA_SOURCE_SERIAL.matcher(tailTexts.trim().toLowerCase()).matches()
                                && headerTextArea.verticalDistance(horizontalShadowRect) <  page.getHeight() / 3) {

                            //TableDebugUtils.writeCells(page, Arrays.asList(horizontalShadowRect), "xxx_horizontalShadowRect");

                            List<Rectangle> mergeAreas = textRegions.stream().filter(re -> ((re.horizontallyOverlapRatio(headerTextArea) > 0.5
                                    || re.horizontallyOverlapRatio(horizontalShadowRect) > 0.5)
                                    && re.getTop() > headerTextArea.getBottom()
                                    && re.getBottom() < horizontalShadowRect.getTop())).collect(Collectors.toList());

                            List<Rectangle> headerTextAreaShadows = mergeRegions.stream().filter(re -> (re.verticalOverlapRatio(headerTextArea) > 0.9)
                                    && overLapOfHeight(re, headerTextArea) > 0.9 && !re.equals(headerTextArea)
                                    && !hasGapAreas(page, re, headerTextArea, mergeRegions)
                                    && isHasOneRowChunks(page, re)).collect(Collectors.toList());
                            Rectangle verticalHeaderArea = null;
                            if (headerTextAreaShadows.size() == 1) {
                                List<TextChunk> verticalHeaderChunks = page.getTextChunks(headerTextAreaShadows.get(0));
                                if (!verticalHeaderChunks.isEmpty()) {
                                    String verticalHeaderTexts = PostProcessTableRegionsAlgorithm.getSequenceOfChunks(page, verticalHeaderChunks);
                                    if (!verticalHeaderTexts.equals("") && CHART_SERIAL0.matcher(verticalHeaderTexts.trim().toLowerCase()).matches()
                                            || CHART_SERIAL1.matcher(verticalHeaderTexts.trim().toLowerCase()).matches()
                                            || CHART_SERIAL2.matcher(verticalHeaderTexts.trim().toLowerCase()).matches()
                                            || TABLE_SERIAL.matcher(verticalHeaderTexts.trim().toLowerCase()).matches()) {
                                        verticalHeaderArea = headerTextAreaShadows.get(0);
                                    }
                                }
                            }
                            Rectangle finalHeaderTextArea = new Rectangle(headerTextArea);
                            List<TextChunk> clusChunks = page.getTextChunks().stream().filter(te -> (te.verticalOverlapRatio(headerTextArea) > 0.5
                                    && !finalHeaderTextArea.nearlyContains(te))).collect(Collectors.toList());
                            headerTextArea.merge(horizontalShadowRect);
                            String serialTexts = PostProcessTableRegionsAlgorithm.getSequenceOfChunks(page, clusChunks);

                            boolean isExistOtherHeader = (CHART_SERIAL0.matcher(serialTexts.trim().toLowerCase()).matches()
                                    || CHART_SERIAL1.matcher(serialTexts.trim().toLowerCase()).matches()
                                    || CHART_SERIAL2.matcher(serialTexts.trim().toLowerCase()).matches()
                                    || TABLE_SERIAL.matcher(serialTexts.trim().toLowerCase()).matches());

                            // 两条直线包含一个区域
                            if (!(!serialTexts.equals("") && isExistOtherHeader)) {
                                extendChartAreaByLines(page, headerTextArea);
                            }

                            Rectangle clusRect = getAreaByChunks(clusChunks);
                            if (!serialTexts.equals("") && isExistOtherHeader
                                    && page.getHorizontalRulings().stream().anyMatch(hr -> (hr.toRectangle().horizontallyOverlapRatio(finalHeaderTextArea) < 0.1
                                    && hr.toRectangle().horizontallyOverlapRatio(clusRect) > 0.9 && (hr.getCenterY() > finalHeaderTextArea.getBottom()
                                    || FloatUtils.feq(hr.getCenterY(), finalHeaderTextArea.getBottom(), 4.f))
                                    && (hr.getCenterY() > clusRect.getBottom()
                                    || FloatUtils.feq(hr.getCenterY(), clusRect.getBottom(), 4.f))))) {
                                extendChartAreaByLines(page, headerTextArea);
                            }

                            for (Rectangle tmpArea : mergeAreas) {
                                //TableDebugUtils.writeCells(page, Arrays.asList(tmpArea), "yyy_tmpArea");
                                if (verticalHeaderArea != null && verticalHeaderArea.isHorizontallyOverlap(tmpArea)) {
                                    boolean isVerticalAreaAtLeft = (verticalHeaderArea.getLeft() < headerTextArea.getLeft());
                                    List<TextChunk> tmpChunks = new ArrayList<>(page.getTextChunks(tmpArea));
                                    if (!tmpChunks.isEmpty()) {
                                        Rectangle tmpMergeArea = new Rectangle(tmpArea);
                                        Rectangle finalVerticalHeaderArea = verticalHeaderArea;
                                        if (isVerticalAreaAtLeft) {
                                            List<TextChunk> nonOvercChunks = tmpChunks.stream().filter(te -> (!te.isHorizontallyOverlap(finalVerticalHeaderArea))).collect(Collectors.toList());
                                            if (!nonOvercChunks.isEmpty()) {
                                                nonOvercChunks.sort(Comparator.comparing(TextChunk::getLeft));
                                                tmpMergeArea.setLeft(nonOvercChunks.get(0).getLeft());
                                                tmpChunks.removeAll(nonOvercChunks);
                                                if (!tmpChunks.isEmpty()) {
                                                    tmpChunks.sort(Comparator.comparing(TextChunk::getRight).reversed());
                                                    tmpArea.setRight(tmpChunks.get(0).getRight());
                                                }
                                            }
                                        } else {
                                            List<TextChunk> nonOvercChunks = tmpChunks.stream().filter(te -> (!te.isHorizontallyOverlap(finalVerticalHeaderArea))).collect(Collectors.toList());
                                            if (!nonOvercChunks.isEmpty()) {
                                                nonOvercChunks.sort(Comparator.comparing(TextChunk::getRight).reversed());
                                                tmpMergeArea.setRight(nonOvercChunks.get(0).getRight());
                                                tmpChunks.removeAll(nonOvercChunks);
                                                if (!tmpChunks.isEmpty()) {
                                                    tmpChunks.sort(Comparator.comparing(TextChunk::getLeft));
                                                    tmpArea.setLeft(tmpChunks.get(0).getLeft());
                                                }
                                            }
                                        }
                                        headerTextArea.merge(tmpMergeArea);
                                    } else {
                                        headerTextArea.merge(tmpArea);
                                    }
                                }
                                if (verticalHeaderArea == null) {
                                    headerTextArea.merge(tmpArea);
                                }
                            }
                            targetAreas.add(headerTextArea);
                            break;
                        }
                    }
                }
            }
            //TableDebugUtils.writeCells(page, Arrays.asList(headerTextArea), "yyy_mmmArea");
        }
        processRepeateAndContainedAreas(page, mergeRegions);
        targetAreas = mergeRegions;
        return targetAreas;
    }

    private static float getAvgCharHeight(ContentGroupPage page, Rectangle baseRect, Rectangle otherRect) {

        float avgHeight = 0;
        List<TextChunk> baseChunks = page.getTextChunks(baseRect);
        List<TextChunk> otherChunks = page.getTextChunks(otherRect);
        if (baseChunks.isEmpty() && otherChunks.isEmpty()) {
            return avgHeight;
        }

        if (baseChunks.isEmpty()) {
            return otherChunks.get(0).getAvgCharHeight();
        }

        if (otherChunks.isEmpty()) {
            return baseChunks.get(0).getAvgCharHeight();
        }

        boolean isBaseAtTop = baseRect.getTop() > otherRect.getTop();
        float baseHeight, otherHeight;
        if (isBaseAtTop) {
            baseHeight = baseChunks.get(baseChunks.size() - 1).getAvgCharHeight();
            otherHeight = otherChunks.get(0).getAvgCharHeight();
            if (baseHeight > otherHeight) {
                avgHeight = otherHeight;
            } else {
                avgHeight = baseHeight;
            }
        } else {
            baseHeight = baseChunks.get(0).getAvgCharHeight();
            otherHeight = otherChunks.get(otherChunks.size() - 1).getAvgCharHeight();
            if (baseHeight > otherHeight) {
                avgHeight = otherHeight;
            } else {
                avgHeight = baseHeight;
            }
        }
        return avgHeight;
    }

    private static float getAvgCharWidth(ContentGroupPage page, Rectangle baseRect, Rectangle otherRect) {

        float avgWidth = 0;
        List<TextChunk> baseChunks = page.getTextChunks(baseRect);
        List<TextChunk> otherChunks = page.getTextChunks(otherRect);
        if (baseChunks.isEmpty() && otherChunks.isEmpty()) {
            return avgWidth;
        }

        if (baseChunks.isEmpty()) {
            return otherChunks.get(0).getAvgCharWidth();
        }

        if (otherChunks.isEmpty()) {
            return baseChunks.get(0).getAvgCharWidth();
        }

        boolean isBaseAtLeft = baseRect.getLeft() < otherRect.getLeft();
        float baseWidth, otherWidth;
        if (isBaseAtLeft) {
            baseWidth = baseChunks.get(baseChunks.size() - 1).getAvgCharWidth();
            otherWidth = otherChunks.get(0).getAvgCharWidth();
            if (baseWidth > otherWidth) {
                avgWidth = otherWidth;
            } else {
                avgWidth = baseWidth;
            }
        } else {
            baseWidth = baseChunks.get(0).getAvgCharWidth();
            otherWidth = otherChunks.get(otherChunks.size() - 1).getAvgCharWidth();
            if (baseWidth > otherWidth) {
                avgWidth = otherWidth;
            } else {
                avgWidth = baseWidth;
            }
        }
        return avgWidth;
    }

    private static boolean isSameOverlapRects(ContentGroupPage page, Rectangle baseRect, List<Rectangle> baseOverLapRects,
                                              Rectangle otherRect, List<Rectangle> otherOverLapRects) {
        if (baseOverLapRects.isEmpty() || otherOverLapRects.isEmpty()) {
            return false;
        }

        if (baseOverLapRects.size() == otherOverLapRects.size() && baseOverLapRects.size() == 1) {
            if (FloatUtils.feq(baseRect.overlapRatio(otherOverLapRects.get(0)), 1.0, 0.1)
                    && FloatUtils.feq(otherRect.overlapRatio(baseOverLapRects.get(0)), 1.0, 0.1)) {
                return true;
            }
        } else {
            return false;
        }
        return false;
    }

    private static boolean isAreaExistLargeDiff(ContentGroupPage page, Rectangle baseRect, Rectangle otherRect, List<Rectangle> allRects) {

        if (allRects.isEmpty() || baseRect.overlapRatio(otherRect) > 0) {
            return false;
        }

        if (isSeparatedByVlineBetweenRects(page, baseRect, otherRect)) {
            return false;
        }

        boolean isExistGapRect = true;

        if (baseRect.getLeft() < otherRect.getLeft()) {
            if (!allRects.stream().anyMatch(r -> (r.isVerticallyOverlap(baseRect) && r.isVerticallyOverlap(otherRect)
                    && r.getLeft() > baseRect.getRight() && r.getRight() < otherRect.getLeft()))) {
                isExistGapRect = false;
            }
        } else {
            if (!allRects.stream().anyMatch(r -> (r.isVerticallyOverlap(baseRect) && r.isVerticallyOverlap(otherRect)
                    && r.getLeft() > otherRect.getRight() && r.getRight() < baseRect.getLeft()))) {
                isExistGapRect = false;
            }
        }

        if (!isExistGapRect) {
            boolean isBaseMax = baseRect.getWidth() > otherRect.getWidth();
            double ratio = isBaseMax ? (otherRect.getWidth() / baseRect.getWidth()) : (baseRect.getWidth() / otherRect.getWidth());
            if (!allRects.stream().anyMatch(re -> (re.isVerticallyOverlap(baseRect) && re.isHorizontallyOverlap(otherRect)
                    && !hasGapAreas(page, otherRect, re, allRects) && !hasGapAreas(page, baseRect, re, allRects))) && ratio < 0.3) {
                return true;
            }
        }

        return false;
    }

    private static boolean isExistSeparateHorizontalChunks(ContentGroupPage page, Rectangle textRect) {
        if (textRect == null) {
            return false;
        }
        List<TextChunk> textChunks = new ArrayList<>(page.getTextChunks(textRect));
        if (textChunks.size() < 2) {
            return false;
        }
        textChunks.sort(Comparator.comparing(Rectangle::getLeft));
        TextChunk baseChunk = textChunks.get(0);
        for (int i = 1; i < textChunks.size(); i++) {
            TextChunk otherChunk = textChunks.get(i);
            if (baseChunk.isVerticallyOverlap(otherChunk)
                    && baseChunk.horizontalDistance(otherChunk) > 2 * getAvgCharWidth(page, baseChunk, otherChunk)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isExistSeparateVerticalChunks(ContentGroupPage page, Rectangle textRect) {
        if (textRect == null) {
            return false;
        }
        List<TextChunk> textChunks = new ArrayList<>(page.getTextChunks(textRect));
        if (textChunks.size() < 2) {
            return false;
        }
        textChunks.sort(Comparator.comparing(Rectangle::getTop));
        TextChunk baseChunk = textChunks.get(0);
        for (int i = 1; i < textChunks.size(); i++) {
            TextChunk otherChunk = textChunks.get(i);
            if (baseChunk.isHorizontallyOverlap(otherChunk)
                    && baseChunk.verticalDistance(otherChunk) > getAvgCharHeight(page, baseChunk, otherChunk)
                    && baseChunk.getWidth() < 2.5 * page.getAvgCharWidth() && otherChunk.getWidth() < 2.5 * page.getAvgCharWidth()) {
                return true;
            }
        }
        return false;
    }

    private static List<Rectangle> findPossibleTextRegions(ContentGroupPage page, List<Rectangle> allRects, List<Rectangle> originalRects,
                                                           List<FillAreaGroup> fillGroups, List<Rectangle> chartAreas) {
        if (allRects.isEmpty()) {
            return new ArrayList<>();
        }

        if (page.getPageNumber() == 1 && allRects.size() == 2
                && allRects.get(0).verticalOverlapRatio(allRects.get(1)) > 0.8
                && allRects.stream().allMatch(re -> (re.getHeight() > 20 * page.getAvgCharHeight()))) {
            return allRects;
        }

        List<Rectangle> verticallyAreas = new ArrayList<>(allRects);
        List<Rectangle> mergeRegions = new ArrayList<>();
        // 结合垂直投影集合和水平投影及空间信息初步确定版面区域
        Rectangle baseRect = verticallyAreas.get(0);
        Rectangle layoutArea = new Rectangle(baseRect);
        boolean isFirstRect = true;
        for (int i = 1; i < verticallyAreas.size(); i++) {

            Rectangle tmpRect = new Rectangle(isFirstRect ? baseRect : layoutArea);
            Rectangle otherRect = verticallyAreas.get(i);

            //TableDebugUtils.writeCells(page, Arrays.asList(layoutArea), "layoutArea");
            //TableDebugUtils.writeCells(page, Arrays.asList(baseRect), "baseRect");
            //TableDebugUtils.writeCells(page, Arrays.asList(otherRect), "otherRect");

            if (!chartAreas.isEmpty() && baseRect.isHorizontallyOverlap(otherRect) && isSignalChartAreas(page, otherRect)
                    && chartAreas.stream().anyMatch(re -> (re.verticalOverlapRatio(otherRect) > 0.5 && !re.equals(otherRect)))) {
                mergeRegions.add(layoutArea);
                layoutArea = new Rectangle(otherRect);
                isFirstRect = true;
                baseRect = otherRect;
                continue;
            }

            Rectangle finalBaseRect1 = baseRect;
            if (!chartAreas.isEmpty() && baseRect.isHorizontallyOverlap(otherRect) && isSignalChartAreas(page, baseRect)
                    && chartAreas.stream().anyMatch(re -> (re.verticalOverlapRatio(finalBaseRect1) > 0.5 && !re.equals(finalBaseRect1)))) {
                mergeRegions.add(layoutArea);
                layoutArea = new Rectangle(otherRect);
                isFirstRect = true;
                baseRect = otherRect;
                continue;
            }

            if (baseRect.isHorizontallyOverlap(otherRect) && !isSeparatedByHline(page, baseRect, otherRect)
                    && !isSeparatedByVerticalTextChunks(page, baseRect, otherRect)) {
                List<Rectangle> vencRects = verticallyAreas.stream().filter(r ->r.isVerticallyOverlap(otherRect)).collect(Collectors.toList());
                boolean isBaseMax = baseRect.getBottom() < otherRect.getTop();
                float height = isBaseMax ? (otherRect.getTop() - baseRect.getBottom()) : (baseRect.getTop() - otherRect.getBottom());
                List<Rectangle> nearlyBaseRects = isBaseMax ? getTopDownAreaOfBaseRect(page, baseRect, allRects, false)
                        : getTopDownAreaOfBaseRect(page, baseRect, allRects, true);
                List<Rectangle> nearlyOtherRects = isBaseMax ? getTopDownAreaOfBaseRect(page, otherRect, allRects, true)
                        : getTopDownAreaOfBaseRect(page, otherRect, allRects, false);
                if (vencRects.size() <= 1 && !hasGapAreas(page, baseRect, otherRect, verticallyAreas)) {
                    if (nearlyBaseRects.size() == nearlyOtherRects.size() && nearlyBaseRects.size() == 1
                            && nearlyBaseRects.get(0).equals(otherRect)
                            && nearlyOtherRects.get(0).equals(baseRect)
                            && height < 6 * page.getAvgCharHeight()) {
                        List<TextChunk> baseChunks = page.getTextChunks(baseRect);
                        List<TextChunk> otherChunks = page.getTextChunks(otherRect);

                        TextChunk baseChunk = isBaseMax ? baseChunks.get(baseChunks.size() - 1) : baseChunks.get(0);
                        TextChunk otherChunk = isBaseMax ? otherChunks.get(0) : otherChunks.get(otherChunks.size() - 1);

                        float fontRatio = (baseChunk.getFontSize() > otherChunk.getFontSize()) ? (otherChunk.getFontSize() / baseChunk.getFontSize())
                                : (baseChunk.getFontSize() / otherChunk.getFontSize());

                        if (!baseChunks.isEmpty() && !otherChunks.isEmpty()
                                && (fontRatio <= 0.6 && baseRect.verticalDistance(otherRect) > 1.5 * getAvgCharHeight(page, baseRect, otherRect)
                                && overLapOfWidth(baseRect, otherRect) < 0.2)
                                && (PARAGRAPH_END_RE.matcher(baseChunks.get(baseChunks.size() - 1).getText()).matches()
                                && !PARAGRAPH_END_RE.matcher(otherChunks.get(otherChunks.size() - 1).getText()).matches())
                                || (!PARAGRAPH_END_RE.matcher(baseChunks.get(baseChunks.size() - 1).getText()).matches()
                                && PARAGRAPH_END_RE.matcher(otherChunks.get(otherChunks.size() - 1).getText()).matches())) {
                            mergeRegions.add(layoutArea);
                            layoutArea = new Rectangle(otherRect);
                            isFirstRect = true;
                        } else {
                            if (otherRect.getLeft() < (layoutArea.getLeft() + (layoutArea.getWidth() / 2))
                                    && fontRatio > 0.6 && !isSignalChartAreas(page, layoutArea)) {
                                isFirstRect = false;
                                layoutArea.setRect(tmpRect.merge(otherRect));
                            } else {
                                mergeRegions.add(layoutArea);
                                layoutArea = new Rectangle(otherRect);
                                isFirstRect = true;
                            }

                        }
                    } else {
                        mergeRegions.add(layoutArea);
                        layoutArea = new Rectangle(otherRect);
                        isFirstRect = true;
                    }

                } else {
                    if ((FloatUtils.feq(baseRect.getLeft(), otherRect.getLeft(), 1.5 * page.getAvgCharWidth())
                            || FloatUtils.feq(baseRect.getRight(), otherRect.getRight(), 1.5 * page.getAvgCharWidth()))
                            && isSameOverlapRects(page, baseRect, nearlyBaseRects, otherRect, nearlyOtherRects)
                            && !hasGapAreas(page, baseRect, otherRect, verticallyAreas)
                            && baseRect.verticalDistance(otherRect) < 4 * getAvgCharHeight(page, baseRect, otherRect)) {

                        List<Rectangle> nearlyLayoutRects = isBaseMax ? getTopDownAreaOfBaseRect(page, layoutArea, allRects, false)
                                : getTopDownAreaOfBaseRect(page, layoutArea, allRects, true);
                        if (isSameOverlapRects(page, layoutArea, nearlyLayoutRects, otherRect, nearlyOtherRects)) {
                            isFirstRect = false;
                            layoutArea.setRect(tmpRect.merge(otherRect));
                        } else {
                            mergeRegions.add(layoutArea);
                            layoutArea = new Rectangle(otherRect);
                            isFirstRect = true;
                        }
                    } else {
                        mergeRegions.add(layoutArea);
                        layoutArea = new Rectangle(otherRect);
                        isFirstRect = true;
                    }
                }
            } else if (!isDoublePage(page, baseRect, otherRect) && baseRect.isVerticallyOverlap(otherRect) && !isSeparatedByHorizontalTextChunks(page, baseRect, otherRect)
                    && !isSeparatedByVlineBetweenRects(page, baseRect, otherRect) && !isLargeDiff(page, baseRect, otherRect)
                    && !isHaveAllChartAreas(page, baseRect, otherRect)
                    && !isDistributeAtDiffFills(page, fillGroups, baseRect, otherRect) && !isPossibleOfColumner(page, baseRect, otherRect, verticallyAreas)
                    && isHaveVerticalSameShadows(page, baseRect, otherRect, allRects) && !isTableOrChartHeader(page, baseRect, otherRect, layoutArea)) {
                if (isExistSeparateHorizontalChunks(page, baseRect) || isExistSeparateHorizontalChunks(page, otherRect) || isAreaExistLargeDiff(page, baseRect, otherRect, allRects)) {
                    if (isPossibleOfTable(page, baseRect) || isPossibleOfTable(page, otherRect)) {
                        mergeRegions.add(layoutArea);
                        layoutArea = new Rectangle(otherRect);
                        isFirstRect = true;
                    } else {
                        Rectangle finalBaseRect = baseRect;
                        List<Rectangle> baseSHadows = allRects.stream().filter(r -> (r.isVerticallyOverlap(finalBaseRect))).collect(Collectors.toList());
                        double width = (baseRect.getLeft() < otherRect.getLeft()) ? (otherRect.getRight() - baseRect.getLeft())
                                : (baseRect.getRight() - otherRect.getLeft());
                        if (baseSHadows.size() == 2 && (width / page.getWidth() >= 0.7)) {
                            mergeRegions.add(layoutArea);
                            layoutArea = new Rectangle(otherRect);
                            isFirstRect = true;
                        } else {
                            isFirstRect = false;
                            layoutArea.setRect(tmpRect.merge(otherRect));
                        }
                    }
                } else {
                    List<Rectangle> nearlyTopRects = getTopDownAreaOfBaseRect(page, baseRect, allRects, true);
                    List<Rectangle> nearlyDownRects = getTopDownAreaOfBaseRect(page, baseRect, allRects, false);

                    if ((!nearlyTopRects.isEmpty() && !nearlyTopRects.get(0).isVerticallyOverlap(otherRect))
                            || (!nearlyDownRects.isEmpty() && !nearlyDownRects.get(0).isVerticallyOverlap(otherRect))) {
                        float avgWidth = getAvgCharWidth(page, baseRect, otherRect);
                        float avgHeight = getAvgCharHeight(page, baseRect, otherRect);
                        if (isHaveVerticalSameShadows(page, baseRect, otherRect, allRects)
                                && !isCoverByDiffFills(page, baseRect, otherRect, fillGroups)
                                && (baseRect.getHeight() < 2 * avgHeight || otherRect.getHeight() < 2 * avgHeight
                                || baseRect.getWidth() < 5 * avgWidth || otherRect.getWidth() < 5 * avgWidth)) {
                            isFirstRect = false;
                            layoutArea.setRect(tmpRect.merge(otherRect));
                        } else {
                            mergeRegions.add(layoutArea);
                            layoutArea = new Rectangle(otherRect);
                            isFirstRect = true;
                        }

                    } else {
                        boolean isMax = baseRect.getWidth() > otherRect.getWidth();
                        double ratio1 = isMax ? (otherRect.getWidth() / baseRect.getWidth()) : (baseRect.getWidth() / otherRect.getWidth());
                        if (ratio1 > 0.9) {
                            mergeRegions.add(layoutArea);
                            layoutArea = new Rectangle(otherRect);
                            isFirstRect = true;
                        } else {
                            if (FastMath.abs(baseRect.getTop() - otherRect.getTop()) > 5 * page.getAvgCharHeight()) {
                                mergeRegions.add(layoutArea);
                                layoutArea = new Rectangle(otherRect);
                                isFirstRect = true;
                            } else {
                                isFirstRect = false;
                                layoutArea.setRect(tmpRect.merge(otherRect));
                            }
                        }
                    }
                }
            } else {
                if (otherRect.getWidth() < otherRect.getHeight() && otherRect.getWidth() < 2.5 * page.getAvgCharWidth()
                        && (isExistSeparateVerticalChunks(page, layoutArea) || isExistSeparateHorizontalChunks(page, layoutArea))
                        && !hasGapAreas(page, layoutArea, otherRect, allRects)) {
                    Rectangle tmpMergeArea = new Rectangle(tmpRect);
                    tmpMergeArea.merge(otherRect);
                    if (!baseRect.isVerticallyOverlap(otherRect) && !baseRect.isHorizontallyOverlap(otherRect)
                            && baseRect.getHeight() < 2.5 * page.getAvgCharHeight() && baseRect.getWidth() < 2.5 * page.getAvgCharWidth()
                            && allRects.stream().anyMatch(re -> (re.intersects(tmpMergeArea) || tmpMergeArea.nearlyContains(re)))) {
                        mergeRegions.add(layoutArea);
                        layoutArea = new Rectangle(otherRect);
                        isFirstRect = true;
                    } else {
                        Rectangle candidateArea = isHorizontallyAtMergeRect(page, layoutArea, otherRect, allRects);
                        if (candidateArea.equals(layoutArea)) {
                            isFirstRect = false;
                            layoutArea.setRect(tmpRect.merge(otherRect));
                        }
                        if (isFirstRect && mergeRegions.stream().anyMatch(re -> re.equals(candidateArea))) {
                            mergeRegions.add(layoutArea);
                            layoutArea = new Rectangle(otherRect);
                            isFirstRect = true;
                            mergeRegions.get(mergeRegions.indexOf(candidateArea)).setRect(candidateArea.merge(otherRect));
                        }
                    }

                } else {
                    if (isAdjacentSingnalChunkRect(page, baseRect, otherRect, verticallyAreas)
                            && baseRect.verticalDistance(otherRect) < 3 * getAvgCharHeight(page, baseRect, otherRect)) {
                        List<TextChunk> baseChunks = page.getTextChunks(baseRect);
                        List<TextChunk> otherChunks = page.getTextChunks(otherRect);
                        if (otherRect.getLeft() < layoutArea.getLeft() && otherRect.getLeft() < baseRect.getLeft()
                                && baseChunks.get(baseChunks.size() - 1).getFontSize() / otherChunks.get(0).getFontSize() < 0.7
                                && otherChunks.get(0).isBold()) {
                            mergeRegions.add(layoutArea);
                            layoutArea = new Rectangle(otherRect);
                            isFirstRect = true;
                        } else {
                            isFirstRect = false;
                            layoutArea.setRect(tmpRect.merge(otherRect));
                        }
                    } else {
                        mergeRegions.add(layoutArea);
                        layoutArea = new Rectangle(otherRect);
                        isFirstRect = true;
                    }
                }
            }
            //TableDebugUtils.writeCells(page, Arrays.asList(layoutArea), "first_LayoutArea");
            baseRect = otherRect;
        }
        mergeRegions.add(layoutArea);
        //TableDebugUtils.writeCells(page, mergeRegions, "mergeRegions");
        // 去掉包含的区域
        processRepeateAndContainedAreas(page, mergeRegions);

        List<Rectangle> targetRegions = furtherAnalysisChunksRects(page, mergeRegions, chartAreas, originalRects, fillGroups);
        //TableDebugUtils.writeCells(page, mergeRegions, "mergeRegions");
        return targetRegions;
    }

    private static boolean isCoverByDiffFills(ContentGroupPage page, Rectangle baseRect, Rectangle otherRect, List<FillAreaGroup> fillAreas) {

        if (baseRect.verticalOverlapRatio(otherRect) < 0.5 || fillAreas.size() < 1) {
            return false;
        }

        List<TextChunk> baseUpChunks = PostProcessTableRegionsAlgorithm.upSearchChunks(page, baseRect);
        List<TextChunk> otherUpChunks = PostProcessTableRegionsAlgorithm.upSearchChunks(page, otherRect);
        if (baseUpChunks.isEmpty() || otherUpChunks.isEmpty()) {
            return false;
        }

        List<FillAreaGroup> baseFills = fillAreas.stream()
                .filter(fill -> (fill.getGroupRectArea().nearlyContains(baseUpChunks.get(0)))).collect(Collectors.toList());
        List<FillAreaGroup> otherFills = fillAreas.stream()
                .filter(fill -> (fill.getGroupRectArea().nearlyContains(otherUpChunks.get(0)))).collect(Collectors.toList());

        if (baseFills.size() == 1 && otherFills.size() == 1) {
            Rectangle baseFill = baseFills.get(0).getGroupRectArea();
            Rectangle otherFill = otherFills.get(0).getGroupRectArea();
            if (!baseFill.equals(otherFill) && baseFill.horizontallyOverlapRatio(baseRect) > 0.9
                    && otherFill.horizontallyOverlapRatio(otherRect) > 0.9
                    && baseFill.verticalOverlapRatio(otherFill) > 0.8
                    && isHasOneRowChunks(page, baseFill) && isHasOneRowChunks(page, otherFill)
                    && baseUpChunks.get(0).horizontalDistance(otherUpChunks.get(0)) > page.getAvgCharWidth()) {
                return true;
            }
        }

        return false;
    }

    private static boolean isPossibleOfColumner(ContentGroupPage page, Rectangle baseRect, Rectangle otherRect, List<Rectangle> allAreas) {

        if (!baseRect.isVerticallyOverlap(otherRect) || allAreas.isEmpty()) {
            return false;
        }

        if (overLapOfWidth(baseRect, otherRect) > 0.6 && baseRect.getHeight() > 20 * page.getAvgCharHeight()
                && otherRect.getHeight() > 20 * page.getAvgCharHeight() && baseRect.getWidth() > 10 * page.getAvgCharWidth()
                && otherRect.getWidth() > 10 * page.getAvgCharWidth()
                && isHaveVerticalSameShadows(page, baseRect, otherRect, allAreas)) {
            return true;
        }

        return false;
    }


    private static boolean isSeparatedByVlineBetweenRects(ContentGroupPage page, Rectangle baseRect, Rectangle otherRect) {

        List<Rectangle> groupRects = new ArrayList<>();
        groupRects.add(baseRect);
        groupRects.add(otherRect);
        return isSeparatedByVline(page, groupRects);
    }


    private static boolean isHaveHorizontalSameShadows(ContentGroupPage page, Rectangle baseRect, Rectangle otherRect, List<Rectangle> allRects) {

        if (allRects.isEmpty() || !baseRect.isHorizontallyOverlap(otherRect)) {
            return false;
        }

        boolean isBaseAtTop = baseRect.getTop() < otherRect.getTop();
        List<Rectangle> baseShadwos, otherShadows;
        if (isBaseAtTop) {
            if (allRects.stream().anyMatch(r -> (r.getCenterY() > baseRect.getBottom() && r.getCenterY() < otherRect.getTop()
                    && (r.isHorizontallyOverlap(baseRect) || r.isHorizontallyOverlap(otherRect))))) {
                return false;
            }

            baseShadwos = allRects.stream().filter(r -> (r.isHorizontallyOverlap(baseRect)
                    && !FloatUtils.feq(r.overlapRatio(baseRect), 1.0, 0.1)
                    && (baseRect.getBottom() < r.getTop() || FloatUtils.feq(baseRect.getBottom(), r.getTop(), 3))
                    && !isSeparatedByVerticalTextChunks(page, baseRect, r))).collect(Collectors.toList());
            otherShadows = allRects.stream().filter(r -> (r.isHorizontallyOverlap(otherRect)
                    && !FloatUtils.feq(r.overlapRatio(otherRect), 1.0, 0.1)
                    && (otherRect.getTop() > r.getBottom() || FloatUtils.feq(otherRect.getTop(), r.getBottom(), 3))
                    && !isSeparatedByVerticalTextChunks(page, otherRect, r))).collect(Collectors.toList());
        } else {
            if (allRects.stream().anyMatch(r -> (r.getCenterY() > otherRect.getBottom() && r.getCenterY() < baseRect.getTop()
                    && (r.isHorizontallyOverlap(baseRect) || r.isHorizontallyOverlap(otherRect))))) {
                return false;
            }

            baseShadwos = allRects.stream().filter(r -> (r.isHorizontallyOverlap(baseRect)
                    && !FloatUtils.feq(r.overlapRatio(baseRect), 1.0, 0.1)
                    && (baseRect.getTop() > r.getBottom() || FloatUtils.feq(baseRect.getTop(), r.getBottom(), 3))
                    && !isSeparatedByVerticalTextChunks(page, baseRect, r))).collect(Collectors.toList());
            otherShadows = allRects.stream().filter(r -> (r.isHorizontallyOverlap(otherRect)
                    && !FloatUtils.feq(r.overlapRatio(otherRect), 1.0, 0.1)
                    && (otherRect.getBottom() < r.getTop() || FloatUtils.feq(otherRect.getBottom(), r.getTop(), 3))
                    && !isSeparatedByVerticalTextChunks(page, otherRect, r))).collect(Collectors.toList());
        }

        if (baseShadwos.size() == otherShadows.size() && baseShadwos.size() == 1
                && FloatUtils.feq(baseShadwos.get(0).overlapRatio(otherRect), 1.0, 0.1)
                && FloatUtils.feq(otherShadows.get(0).overlapRatio(baseRect), 1.0, 0.1)) {
            return true;
        }

        if (baseRect.overlapRatio(otherRect) > 0) {
            return true;
        }

        return false;
    }

    private static boolean isHaveVerticalSameShadows(ContentGroupPage page, Rectangle baseRect, Rectangle otherRect, List<Rectangle> allRects) {
        if (allRects.isEmpty() || !baseRect.isVerticallyOverlap(otherRect)) {
            return false;
        }

        boolean isBaseAtLeft = baseRect.getLeft() < otherRect.getLeft();
        List<Rectangle> baseShadwos, otherShadows;
        if (isBaseAtLeft) {
            baseShadwos = allRects.stream().filter(r -> (r.isVerticallyOverlap(baseRect)
                    && !FloatUtils.feq(r.overlapRatio(baseRect), 1.0, 0.1)
                    && (baseRect.getRight() < r.getLeft() || FloatUtils.feq(baseRect.getRight(), r.getLeft(), 3))
                    && !isSeparatedByHorizontalTextChunks(page, baseRect, r))).collect(Collectors.toList());
            otherShadows = allRects.stream().filter(r -> (r.isVerticallyOverlap(otherRect)
                    && !FloatUtils.feq(r.overlapRatio(otherRect),1.0, 0.1)
                    && (otherRect.getLeft() > r.getRight() || FloatUtils.feq(otherRect.getLeft(), r.getRight(), 3))
                    && !isSeparatedByHorizontalTextChunks(page, otherRect, r))).collect(Collectors.toList());
        } else {
            baseShadwos = allRects.stream().filter(r -> (r.isVerticallyOverlap(baseRect)
                    && !FloatUtils.feq(r.overlapRatio(baseRect),1.0, 0.1)
                    && (baseRect.getLeft() > r.getRight() || FloatUtils.feq(baseRect.getLeft(), r.getRight(), 3))
                    && !isSeparatedByHorizontalTextChunks(page, baseRect, r))).collect(Collectors.toList());
            otherShadows = allRects.stream().filter(r -> (r.isVerticallyOverlap(otherRect)
                    && !FloatUtils.feq(r.overlapRatio(otherRect),1.0, 0.1)
                    && (otherRect.getRight() < r.getLeft() || FloatUtils.feq(otherRect.getRight(), r.getLeft(), 3))
                    && !isSeparatedByHorizontalTextChunks(page, otherRect, r))).collect(Collectors.toList());
        }

        if (baseShadwos.size() == otherShadows.size() && baseShadwos.size() == 1
                && FloatUtils.feq(baseShadwos.get(0).overlapRatio(otherRect), 1.0, 0.1)
                && FloatUtils.feq(otherShadows.get(0).overlapRatio(baseRect), 1.0, 0.1)) {
            return true;
        }

        if (baseRect.overlapRatio(otherRect) > 0) {
            return true;
        }

        return false;
    }

    private static boolean isPossibleOfTable(ContentGroupPage page, Rectangle textArea) {
        if (textArea == null) {
            return false;
        }
        List<TextChunk> textChunks = new ArrayList<>(page.getTextChunks(textArea));
        if (textChunks.size() < 2) {
            return false;
        }

        textChunks.sort(Comparator.comparing(Rectangle::getTop));
        TextChunk baseChunk = textChunks.get(0);
        int separateCnt = 0;
        int newLineCnt = 0;
        for (int i = 1; i < textChunks.size(); i++) {
            TextChunk otherChunk = textChunks.get(i);
            if (baseChunk.isVerticallyOverlap(otherChunk)
                    && baseChunk.horizontalDistance(otherChunk) > 2 * getAvgCharWidth(page, baseChunk, otherChunk)) {
                boolean isBaseAtTop = baseChunk.getTop() < otherChunk.getTop();
                boolean isHasNoGap = isBaseAtTop ? FloatUtils.feq(baseChunk.getBottom(), otherChunk.getTop(), 2)
                        : FloatUtils.feq(baseChunk.getTop(), otherChunk.getBottom(), 2);
                if (!isHasNoGap) {
                    separateCnt++;
                } else {
                    newLineCnt++;
                }
            } else {
                newLineCnt++;
            }
            baseChunk = otherChunk;
        }


        if (newLineCnt >= 3 && separateCnt >= 3) {
            return true;
        }
        return false;
    }


    private static List<Rectangle> getRegionsByLines(ContentGroupPage page,  List<Rectangle> rulingRegions, List<Rectangle> allChunkRects) {

        /*if (!rulingRegions.isEmpty()) {
            // 每条边框至少存在相交线，否则区域无效
            List<Ruling> hlines = page.getHorizontalRulings();
            List<Ruling> vlines = page.getVerticalRulings();
            for (Rectangle tmp : rulingRegions) {
                Ruling topLine = new Ruling(tmp.getTop(), tmp.getLeft(), (float)tmp.getWidth(),0);
                if (!hlines.stream().anyMatch(r -> (FloatUtils.feq(r.getTop(), topLine.getTop(), 3.5) && r.getWidth() / topLine.getWidth() > 0.9))) {
                    List<Ruling> topCrossRulings = vlines.stream().filter(r->r.intersectsLine(topLine)).collect(Collectors.toList());
                    if (topCrossRulings.size() < 2) {
                        tmp.markDeleted();
                        continue;
                    }
                }

                Ruling bottomLine = new Ruling(tmp.getBottom(), tmp.getLeft(), (float)tmp.getWidth(), 0);
                if (!hlines.stream().anyMatch(r -> (FloatUtils.feq(r.getBottom(), bottomLine.getBottom(), 3.5) && r.getWidth() / bottomLine.getWidth() > 0.9))) {
                    List<Ruling> bottomCrossRulings = vlines.stream().filter(r->r.intersectsLine(bottomLine)).collect(Collectors.toList());
                    if (bottomCrossRulings.size() < 2) {
                        tmp.markDeleted();
                        continue;
                    }
                }

                Ruling leftLine = new Ruling(tmp.getTop(), tmp.getLeft(), 0, (float)tmp.getHeight());
                if (!vlines.stream().anyMatch(r -> (FloatUtils.feq(r.getLeft(), leftLine.getLeft(), 3.5) && r.getHeight() / leftLine.getHeight() > 0.9))) {
                    List<Ruling> leftCrossRulings = hlines.stream().filter(r->r.intersectsLine(leftLine)).collect(Collectors.toList());
                    if (leftCrossRulings.size() < 2) {
                        tmp.markDeleted();
                        continue;
                    }
                }

                Ruling rightLine = new Ruling(tmp.getTop(), tmp.getRight(), 0, (float)tmp.getHeight());
                if (!vlines.stream().anyMatch(r -> (FloatUtils.feq(r.getRight(), rightLine.getRight(), 3.5) && r.getHeight() / rightLine.getHeight() > 0.9))) {
                    List<Ruling> rightCrossRulings = hlines.stream().filter(r->r.intersectsLine(rightLine)).collect(Collectors.toList());
                    if (rightCrossRulings.size() < 2) {
                        tmp.markDeleted();
                        continue;
                    }
                }
            }
            rulingRegions.removeIf(Rectangle::isDeleted);
        }*/

        if (!rulingRegions.isEmpty() && !allChunkRects.isEmpty()) {
            List<Rectangle> removeChunkRects = new ArrayList<>();
            for (Rectangle area : rulingRegions) {
                double avgHeight, avgWidth;
                if (page.getTextChunks(area).isEmpty()) {
                    //avgHeight = page.getAvgCharHeight();
                    avgWidth = page.getAvgCharWidth();
                } else {
                    //avgHeight = page.getText(area).parallelStream()
                    //        .mapToDouble(TextElement::getTextHeight).average().orElse(Page.DEFAULT_MIN_CHAR_HEIGHT);
                    avgWidth = page.getText(area).parallelStream()
                            .mapToDouble(TextElement::getWidth).average().orElse(Page.DEFAULT_MIN_CHAR_WIDTH);
                }

                //List<Rectangle> horizontalRects = allChunkRects.stream().filter(re -> (re.horizontalOverlap(area) > 0.9)
                //        && re.verticalDistance(area) < 1.5 * avgHeight && re.getWidth() > re.getHeight()
                //        && re.getHeight() < 1.5 * avgHeight && re.getWidth() < area.getWidth()).collect(Collectors.toList());
                List<Rectangle> verticalRects = allChunkRects.stream().filter(re -> (re.verticalOverlap(area) > 0.9)
                        && re.horizontalDistance(area) < 1.5 * avgWidth && re.getHeight() > 2 * avgWidth
                        && re.getWidth() < 1.5 * avgWidth && re.getHeight() <= area.getHeight()).collect(Collectors.toList());

                if (!verticalRects.isEmpty()) {
                    for (Rectangle tmp : verticalRects) {
                        area.merge(tmp);
                    }
                    removeChunkRects.addAll(verticalRects);
                }
            }
            allChunkRects.removeAll(removeChunkRects);
        }

        return rulingRegions;
    }

    private static void filterSmallRegions(ContentGroupPage page, List<Rectangle> allRects) {
        if (allRects.isEmpty()) {
            return;
        }
        for (Rectangle tmpRect : allRects) {
            if ((tmpRect.getWidth() < page.getAvgCharWidth() || tmpRect.getHeight() < page.getAvgCharHeight())
                    && page.getTextChunks(tmpRect).stream()
                    .anyMatch(text -> (text.getDirection() == TextDirection.VERTICAL_UP || text.getDirection() == TextDirection.VERTICAL_DOWN))) {
                tmpRect.markDeleted();
            }
        }
        allRects.removeIf(Rectangle::isDeleted);
    }

    private static List<Rectangle> upSearchRectsToMerge(ContentGroupPage page, Rectangle maxArea, List<Rectangle> candidateAreas, List<Rectangle> allRects) {

        if (candidateAreas.isEmpty()) {
            return new ArrayList<>();
        }

        candidateAreas.sort(Comparator.comparing(Rectangle::getBottom).reversed());
        List<Rectangle> targetRects = new ArrayList<>();

        for (Rectangle area : candidateAreas) {
            if (area.isHorizontallyOverlap(maxArea)) {
                targetRects.add(area);
            }
        }
        return targetRects;
    }

    private static List<Rectangle> downSearchRectsToMerge(ContentGroupPage page, Rectangle maxArea, List<Rectangle> candidateAreas, List<Rectangle> allRects) {

        if (candidateAreas.isEmpty()) {
            return new ArrayList<>();
        }

        candidateAreas.sort(Comparator.comparing(Rectangle::getTop));
        List<Rectangle> targetRects = new ArrayList<>();

        for (Rectangle area : candidateAreas) {
            if (area.isHorizontallyOverlap(maxArea)) {
                targetRects.add(area);
            }
        }
        return targetRects;
    }

    private static double overLapOfWidth(Rectangle one, Rectangle other) {
        double ratio = 0.0;
        if (one == null || other == null) {
            return ratio;
        }
        if (one.getWidth() > other.getWidth()) {
            ratio = other.getWidth() / one.getWidth();
        } else {
            ratio = one.getWidth() / other.getWidth();
        }
        return ratio;
    }

    private static double overLapOArea(Rectangle one, Rectangle other) {
        double ratio = 0.0;
        if (one == null || other == null) {
            return ratio;
        }
        if (one.getArea() > other.getArea()) {
            ratio = other.getArea() / one.getArea();
        } else {
            ratio = one.getArea() / other.getArea();
        }
        return ratio;
    }

    private static double overLapOfHeight(Rectangle one, Rectangle other) {
        double ratio = 0.0;
        if (one == null || other == null) {
            return ratio;
        }
        if (one.getHeight() > other.getHeight()) {
            ratio = other.getHeight() / one.getHeight();
        } else {
            ratio = one.getHeight() / other.getHeight();
        }
        return ratio;
    }

    private static boolean isIrregularAreas(ContentGroupPage page, List<Rectangle> allAreas) {

        if (allAreas.size() < 2) {
            return false;
        }

        // 宽度相差很大，高度相差很大，且上下不对齐，视为不规则的区域，后续应该和并
        Rectangle baseRect = allAreas.get(0);
        for (int i = 1; i < allAreas.size(); i++) {
            Rectangle otherRect = allAreas.get(i);
            if (overLapOfWidth(baseRect, otherRect) < 0.6
                    && (page.getText(baseRect).get(0).getFontSize() == page.getText(otherRect).get(0).getFontSize())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSeparatedByVline(ContentGroupPage page, List<Rectangle> mergeAreas) {

        if (mergeAreas.size() < 2 || page.getVerticalRulings().isEmpty()) {
            return false;
        }
        mergeAreas.sort(Comparator.comparing(Rectangle::getLeft));
        List<Ruling> vlines = new ArrayList<>(page.getVerticalRulings());
        List<Ruling> hlines = page.getHorizontalRulings();
        List<Ruling> removeRulings = new ArrayList<>();
        if (!hlines.isEmpty()) {
            for (Ruling vline : vlines) {
                /*if (hlines.stream().anyMatch(hr -> (hr.intersectsLine(vline)))) {
                    removeRulings.add(vline);
                }*/
                List<Ruling> intersectLines = hlines.stream().filter(hr -> (hr.intersectsLine(vline) && hr.getWidth() > page.getAvgCharWidth()))
                        .collect(Collectors.toList());
                if (intersectLines.size() == 1) {
                    Ruling intersectLine = intersectLines.get(0);
                    if (!(FloatUtils.feq(intersectLine.getX1(), vline.getX1(), 4)
                            || FloatUtils.feq(intersectLine.getX2(), vline.getX1(), 4))
                            && !(FloatUtils.feq(intersectLine.getY1(), vline.getY1(), 5)
                            || FloatUtils.feq(intersectLine.getY1(), vline.getY2(), 5))) {
                        removeRulings.add(vline);
                    }
                }
            }
            vlines.removeAll(removeRulings);
            if (vlines.isEmpty()) {
                return false;
            }
        }

        Rectangle baseRect = mergeAreas.get(0);
        int separateCnt = 0;
        for (int i = 1; i < mergeAreas.size(); i++) {
            Rectangle otherRect = mergeAreas.get(i);
            if (vlines.stream().anyMatch(vr -> (vr.verticalOverlap(baseRect) > 0.8
                    && vr.verticalOverlap(otherRect) > 0.8 && vr.getHeight() > 3 * getAvgCharHeight(page, baseRect, otherRect))
                    && (vr.getCenterX() > baseRect.getRight() && vr.getCenterX() < otherRect.getLeft()))) {
                separateCnt++;
            }
        }
        if (separateCnt == mergeAreas.size() - 1) {
            return true;
        }

        return false;
    }

    private static boolean isSeparatedByHorizontalTextChunks(ContentGroupPage page, Rectangle baseRect, Rectangle otherRect) {

        if (!baseRect.isVerticallyOverlap(otherRect) || baseRect.overlapRatio(otherRect) > 0) {
            return false;
        }

        boolean isBaseAtLeft = baseRect.getLeft() < otherRect.getLeft();
        float left = isBaseAtLeft ? baseRect.getRight() : otherRect.getRight();
        float top = FastMath.max(baseRect.getTop(), otherRect.getTop());
        float width = isBaseAtLeft ? (otherRect.getLeft() - left) : (baseRect.getLeft() - left);
        float height = FastMath.min(baseRect.getBottom(), otherRect.getBottom()) - top;
        Rectangle gapRect = new Rectangle(left, top, width, height);
        List<TextElement> gapElements = page.getText(gapRect);
        if (gapElements.isEmpty()) {
            return false;
        }

        if (gapElements.size() < 3 && gapElements.stream().allMatch(TextElement::isWhitespace)) {
            return false;
        }

        return true;
    }


    private static boolean isSeparatedByVerticalTextChunks(ContentGroupPage page, Rectangle baseRect, Rectangle otherRect) {

        if (!baseRect.isHorizontallyOverlap(otherRect) || baseRect.overlapRatio(otherRect) > 0) {
            return false;
        }

        boolean isBaseAtTop = baseRect.getTop() < otherRect.getTop();
        float left = FastMath.max(baseRect.getLeft(), otherRect.getLeft());
        float top = isBaseAtTop ? baseRect.getBottom() : otherRect.getBottom();
        float width = FastMath.min(baseRect.getRight(), otherRect.getRight()) - left;
        float height = isBaseAtTop ? (otherRect.getTop() - top) : (baseRect.getTop() - top);
        Rectangle gapRect = new Rectangle(left, top, width, height);
        List<TextElement> gapElements = page.getText(gapRect);
        List<TextChunk> texts = page.getTextChunks();
        if (gapElements.isEmpty()) {
            if (gapRect.getHeight() > page.getMinCharHeight() && !texts.isEmpty()) {
                gapRect.rectReduce(1.0, 1.0, page.getWidth(), page.getHeight());
                if (texts.stream().anyMatch(te -> (te.intersects(gapRect) && te.verticalOverlapRatio(gapRect) > 0.5
                        && te.getWidth() > 2 * page.getAvgCharWidth() && !te.isBlank()))) {
                    return true;
                }
            }
            return false;
        }

        if (gapElements.size() < 3 && gapElements.stream().allMatch(TextElement::isWhitespace)) {
            return false;
        }

        return true;
    }


    private static boolean isSeparatedByHline(ContentGroupPage page, Rectangle baseRect, Rectangle otherRect) {

        if (page.getHorizontalRulings().isEmpty() || baseRect == null || otherRect == null) {
            return false;
        }
        Rectangle topRect, downRect;
        if (baseRect.getTop() < otherRect.getTop()) {
            topRect = baseRect;
            downRect = otherRect;
        } else {
            topRect = otherRect;
            downRect = baseRect;
        }

        List<Ruling> hlines = page.getHorizontalRulings().stream().filter(hr -> (hr.getY1() > topRect.getTop()
                && hr.getY1() < downRect.getBottom())).collect(Collectors.toList());
        List<Ruling> vlines = page.getVerticalRulings();
        List<Ruling> removeRulings = new ArrayList<>();
        if (!hlines.isEmpty()) {
            hlines.sort(Comparator.comparing(Ruling::getTop));
            for (Ruling hline : hlines) {
                if (vlines.stream().anyMatch(vr -> (vr.intersectsLine(hline)) && vr.getHeight() > page.getAvgCharHeight())) {
                    removeRulings.add(hline);
                }
            }
            hlines.removeAll(removeRulings);
            if (hlines.isEmpty()) {
                return false;
            }
        }

        boolean isTopWidthMax = topRect.getWidth() > downRect.getWidth();
        Rectangle maxRect = isTopWidthMax ? topRect : downRect;

        Ruling topLine = new Ruling(topRect.getBottom(), topRect.getLeft(), (float)topRect.getWidth(),0);
        Ruling downLine = new Ruling(downRect.getTop(), downRect.getLeft(), (float)downRect.getWidth(),0);
        List<Ruling> targetRulings = hlines.stream().filter(hr -> (hr.horizontallyOverlapRatio(topLine) > 0.8
                && hr.horizontallyOverlapRatio(downLine) > 0.8)
                && (hr.getCenterY() > (topRect.getBottom() - 2.5) && hr.getCenterY() < downRect.getTop() + 2.5)).collect(Collectors.toList());

        if (targetRulings.size() == 1) {
            Rectangle lineRect = new Rectangle(targetRulings.get(0).getLeft(), targetRulings.get(0).getTop(),
                    targetRulings.get(0).getWidth(), targetRulings.get(0).getHeight());
            if ((FloatUtils.feq(topRect.getLeft(), downRect.getLeft(), getAvgCharWidth(page, topRect, downRect))
                    || FloatUtils.feq(topRect.getRight(), downRect.getRight(), getAvgCharWidth(page, topRect, downRect)))
                    && lineRect.verticalDistance(topRect) < getAvgCharHeight(page, topRect, lineRect)
                    && lineRect.verticalDistance(downRect) < getAvgCharHeight(page, downRect, lineRect) && overLapOfWidth(maxRect, lineRect) > 0.8) {
                return true;
            }
        }

        return false;
    }


    private static List<Ruling> getSeparatedHlineBetweenTwoChunks(ContentGroupPage page, Rectangle baseRect, Rectangle otherRect) {

        if (page.getHorizontalRulings().isEmpty() || page.getText(baseRect).isEmpty() || page.getText(otherRect).isEmpty()) {
            return new ArrayList<>();
        }
        Rectangle topRect, downRect;
        if (baseRect.getTop() < otherRect.getTop()) {
            topRect = baseRect;
            downRect = otherRect;
        } else {
            topRect = otherRect;
            downRect = baseRect;
        }

        List<Ruling> hlines = page.getHorizontalRulings().stream().filter(hr -> (hr.getY1() > topRect.getTop()
                && hr.getY1() < downRect.getBottom())).collect(Collectors.toList());
        List<Ruling> vlines = page.getVerticalRulings();
        List<Ruling> removeRulings = new ArrayList<>();
        if (!hlines.isEmpty()) {
            hlines.sort(Comparator.comparing(Ruling::getTop));
            for (Ruling hline : hlines) {
                if (vlines.stream().anyMatch(vr -> (vr.intersectsLine(hline)) && vr.getHeight() > page.getAvgCharHeight())) {
                    removeRulings.add(hline);
                }
            }
            hlines.removeAll(removeRulings);
            if (hlines.isEmpty()) {
                return new ArrayList<>();
            }
        }

        Ruling topLine = new Ruling(topRect.getBottom(), topRect.getLeft(), (float)topRect.getWidth(),0);
        Ruling downLine = new Ruling(downRect.getTop(), downRect.getLeft(), (float)downRect.getWidth(),0);
        List<Ruling> targetRulings = hlines.stream().filter(hr -> (hr.horizontallyOverlapRatio(topLine) > 0.8
                && hr.horizontallyOverlapRatio(downLine) > 0.8)
                && (hr.getCenterY() > (topRect.getBottom() - 2.5) && hr.getCenterY() < downRect.getTop() + 2.5)).collect(Collectors.toList());

        if (targetRulings.size() == 1) {
            Rectangle lineRect = new Rectangle(targetRulings.get(0).getLeft(), targetRulings.get(0).getTop(),
                    targetRulings.get(0).getWidth(), targetRulings.get(0).getHeight());
            if ((FloatUtils.feq(topRect.getLeft(), downRect.getLeft(), getAvgCharWidth(page, topRect, downRect))
                    || FloatUtils.feq(topRect.getRight(), downRect.getRight(), getAvgCharWidth(page, topRect, downRect)))
                    && lineRect.verticalDistance(topRect) < getAvgCharHeight(page, topRect, lineRect)
                    && lineRect.verticalDistance(downRect) < getAvgCharHeight(page, downRect, lineRect)) {
                return targetRulings;
            }
        }

        return new ArrayList<>();
    }

    private static boolean isCanDownMultiMerges(ContentGroupPage page, Rectangle baseArea, List<Rectangle> mergeAreas, List<Rectangle> allAreas, List<Rectangle> originalChunks) {

        if (allAreas.isEmpty() || mergeAreas.isEmpty()) {
            return false;
        }

        if (mergeAreas.size() == 1) {
            if (baseArea.verticalDistance(mergeAreas.get(0)) > 10 * getAvgCharHeight(page, baseArea, mergeAreas.get(0))) {
                return false;
            }

            List<Rectangle> verticalRects = allAreas.stream().filter(r -> (r.isVerticallyOverlap(mergeAreas.get(0))
                    && !FloatUtils.feq(mergeAreas.get(0).overlapRatio(r), 1.0, 0.1)
                    && !hasGapAreas(page, baseArea, r, allAreas))).collect(Collectors.toList());
            if (!verticalRects.isEmpty()) {
                verticalRects.addAll(mergeAreas);
                verticalRects.sort(Comparator.comparing(Rectangle::getLeft));
                if (isSeparatedByVline(page, verticalRects)) {
                    return false;
                }
            }
        }

        if (isSeparatedByVline(page, mergeAreas)) {
            return false;
        }

        mergeAreas.sort(Comparator.comparing(Rectangle::getLeft));
        if (mergeAreas.size() == 2) {

            if (page.getTextChunks(baseArea).stream().allMatch(te -> (te.isPageHeader())) && baseArea.getHeight() < 1.5 * page.getAvgCharHeight()
                    && originalChunks.stream().allMatch(te -> (te.getBottom() >= baseArea.getBottom()))) {
                return false;
            }

            if (overLapOfWidth(mergeAreas.get(0), mergeAreas.get(1)) > 0.5
                    && FloatUtils.feq(mergeAreas.get(0).getLeft(), baseArea.getLeft(), 2.5 * page.getAvgCharWidth())
                    && FloatUtils.feq(mergeAreas.get(1).getRight(), baseArea.getRight(), 2.5 * page.getAvgCharWidth())
                    && FloatUtils.feq(mergeAreas.get(0).getRight(), mergeAreas.get(1).getLeft(), 5 * page.getAvgCharWidth())) {
                return false;
            }

            if (overLapOfWidth(mergeAreas.get(0), mergeAreas.get(1)) > 0.35) {
                Rectangle maxHeightRect = (mergeAreas.get(0).getHeight() > mergeAreas.get(1).getHeight()) ? mergeAreas.get(0) : mergeAreas.get(1);
                Rectangle minHeightRect = (mergeAreas.get(0).getHeight() > mergeAreas.get(1).getHeight()) ? mergeAreas.get(1) : mergeAreas.get(0);
                if (maxHeightRect.getHeight() > 30 * page.getAvgCharHeight() && minHeightRect.getHeight() > 5 * page.getAvgCharHeight()) {
                    return false;
                }

                List<TextChunk> chunks0 = page.getMergeChunks(maxHeightRect);
                List<TextChunk> chunks1 = page.getMergeChunks(minHeightRect);
                List<TextChunk> mainChunks = page.getTextChunks(baseArea);
                if (!chunks0.isEmpty() && !chunks1.isEmpty() && !mainChunks.isEmpty()) {
                    TextChunk maxChunk0 = chunks0.stream().max(Comparator.comparing(TextChunk::getMaxFontSize)).get();
                    TextChunk minChunk0 = chunks0.stream().min(Comparator.comparing(TextChunk::getMaxFontSize)).get();
                    TextChunk maxChunk1 = chunks1.stream().max(Comparator.comparing(TextChunk::getMaxFontSize)).get();
                    TextChunk minChunk1 = chunks0.stream().min(Comparator.comparing(TextChunk::getMaxFontSize)).get();
                    TextChunk mainChunk = mainChunks.stream().max(Comparator.comparing(TextChunk::getMaxFontSize)).get();

                    if (maxHeightRect.getHeight() < 3 * maxChunk0.getHeight() || maxHeightRect.getHeight() < 3 * maxChunk1.getHeight()) {
                        if (minChunk0.getMaxFontSize() / maxChunk0.getMaxFontSize() < 0.6
                                || minChunk1.getMaxFontSize() / maxChunk1.getMaxFontSize() < 0.6) {
                            if (maxChunk0.getFontSize() > maxChunk1.getFontSize() && maxChunk1.getMaxFontSize() / maxChunk0.getMaxFontSize() < 0.6) {
                                return false;
                            }

                            if (maxChunk0.getFontSize() < maxChunk1.getFontSize() && maxChunk0.getMaxFontSize() / maxChunk1.getMaxFontSize() < 0.6) {
                                return false;
                            }
                        }
                    }

                    if (overLapOfHeight(maxHeightRect, minHeightRect) < 0.2 && maxHeightRect.getHeight() > 20 * maxChunk0.getHeight()
                            && minHeightRect.getHeight() > 2 * maxChunk1.getHeight()) {
                        return false;
                    }

                    if (isHasOneRowChunks(page, baseArea) && baseArea.getHeight() < 1.5 * mainChunk.getHeight()
                            && page.getPageFillAreaGroup().stream().anyMatch(fill -> (fill.getGroupRectArea().contains(baseArea)
                            && fill.getGroupAreaType() == FillAreaGroup.AreaType.BAR_AREA))
                            && maxHeightRect.getHeight() > 5 * mainChunk.getHeight()) {
                        return false;
                    }
                }
            }

            List<TextChunk> mergeChunks0 = page.getTextChunks(mergeAreas.get(0));
            List<TextChunk> mergeChunks1 = page.getTextChunks(mergeAreas.get(1));
            if (mergeChunks0.size() >= 2 && mergeChunks1.size() >= 2 && page.getPageNumber() == 1) {
                if (mergeChunks0.get(0).horizontallyOverlapRatio(mergeChunks0.get(1)) > 0.9) {
                    List<Ruling> separatedRulings = getSeparatedHlineBetweenTwoChunks(page, mergeChunks0.get(0).toRectangle(),
                            mergeChunks0.get(1).toRectangle());
                    if (separatedRulings.size() == 1 && overLapOfWidth(mergeAreas.get(0), mergeAreas.get(1)) < 0.4
                            && !page.getHorizontalRulings().stream().anyMatch(r -> (r.intersects(mergeAreas.get(1))
                            && r.getLeft() < mergeAreas.get(1).getLeft() && r.getRight() > mergeAreas.get(1).getRight()))) {
                        return false;
                    }
                }
                if (mergeChunks1.get(0).horizontallyOverlapRatio(mergeChunks1.get(1)) > 0.9) {
                    List<Ruling> separatedRulings = getSeparatedHlineBetweenTwoChunks(page, mergeChunks1.get(0).toRectangle(),
                            mergeChunks1.get(1).toRectangle());
                    if (separatedRulings.size() == 1 && overLapOfWidth(mergeAreas.get(0), mergeAreas.get(1)) < 0.4
                            && !page.getHorizontalRulings().stream().anyMatch(r -> (r.intersects(mergeAreas.get(0))
                            && r.getLeft() < mergeAreas.get(0).getLeft() && r.getRight() > mergeAreas.get(0).getRight()))) {
                        return false;
                    }
                }
            }

            List<Rectangle> mergetAreaShadows0 = allAreas.stream().filter(r -> (r.isVerticallyOverlap(mergeAreas.get(0))
                    && (r != mergeAreas.get(0)))).collect(Collectors.toList());
            List<Rectangle> mergetAreaShadows1 = allAreas.stream().filter(r -> (r.isVerticallyOverlap(mergeAreas.get(1))
                    && (r != mergeAreas.get(1)))).collect(Collectors.toList());
            boolean isDiscreteRects0 = isDiscreteRects(page, Arrays.asList(mergeAreas.get(0)), false);
            boolean isDiscreteRects1 = isDiscreteRects(page, Arrays.asList(mergeAreas.get(1)), false);
            if (mergetAreaShadows0.size() == mergetAreaShadows1.size() && mergetAreaShadows0.stream().anyMatch(r -> r == mergeAreas.get(1))
                    && mergetAreaShadows1.stream().anyMatch(r -> r == mergeAreas.get(0))) {

                if (overLapOfWidth(mergeAreas.get(0), mergeAreas.get(1)) > 0.7 && overLapOfHeight(mergeAreas.get(0), mergeAreas.get(1)) > 0.8
                        && mergeAreas.get(0).getWidth() > 15 * page.getAvgCharWidth() && mergeAreas.get(0).getHeight() > 15 * page.getAvgCharHeight()
                        && mergeAreas.get(1).getWidth() > 15 * page.getAvgCharWidth() && mergeAreas.get(1).getHeight() > 15 * page.getAvgCharHeight()
                        && baseArea.getWidth() < mergeAreas.get(1).getRight() && mergetAreaShadows0.size() == 1
                        && FloatUtils.feq(mergeAreas.get(1).getTop(), mergeAreas.get(0).getTop(), 5 * page.getAvgCharHeight())) {
                    return false;
                }

                if (isDiscreteRects0 != isDiscreteRects1) {
                    if (mergeAreas.stream().anyMatch(r -> (r.getHeight() > 10 * baseArea.getHeight()))) {
                        return false;
                    }
                    return true;
                }

                List<TextChunk> mainChunks = new ArrayList<>(page.getTextChunks(baseArea));
                if (!mainChunks.isEmpty()) {
                    mainChunks.sort(Comparator.comparing(TextChunk::getBottom).reversed());
                    TextChunk thunks0 = mainChunks.get(0);
                    List<TextChunk> verticalChunks = mainChunks.stream().filter(te -> (te.verticalOverlapRatio(thunks0) > 0.5))
                            .collect(Collectors.toList());
                    if (verticalChunks.size() == 2) {
                        verticalChunks.sort(Comparator.comparing(TextChunk::getLeft));
                        TextChunk base0 = verticalChunks.get(0);
                        TextChunk base1 = verticalChunks.get(1);
                        if (base0.isHorizontallyOverlap(mergeAreas.get(0)) && base1.isHorizontallyOverlap(mergeAreas.get(1))
                                && base1.verticalDistance(mergeAreas.get(1)) < 1.5 * page.getAvgCharHeight()
                                && base0.verticalDistance(mergeAreas.get(0)) < 1.5 * page.getAvgCharHeight()
                                && FloatUtils.feq(base0.getBottom(), base1.getBottom(), 4)
                                && base0.horizontalDistance(base1) > getAvgCharWidth(page, base0, base1)
                                && isHaveHorizontalSameShadows(page, base0, mergeAreas.get(0), allAreas)
                                && isHaveHorizontalSameShadows(page, base1, mergeAreas.get(1), allAreas) ) {
                            return true;
                        }
                    }
                }

                boolean isBaseDiscreteRects = isDiscreteRects(page, Arrays.asList(baseArea), true);
                if (isDiscreteRects0 && isDiscreteRects1 && overLapOfHeight(mergeAreas.get(0), mergeAreas.get(1)) < 0.2) {
                    if (isBaseDiscreteRects) {
                        float avgHeight = getAvgCharHeight(page, mergeAreas.get(0), mergeAreas.get(1));
                        if (mergeAreas.get(0).getHeight() < 1.5 * avgHeight || mergeAreas.get(1).getHeight() < 1.5 * avgHeight) {
                            return true;
                        }
                    }
                }

                if (isBaseDiscreteRects && isDiscreteRects0 && isDiscreteRects1
                        && diffFontRatio(page, mergeAreas.get(0), mergeAreas.get(1)) > 0.8
                        && overLapOfWidth(mergeAreas.get(0), mergeAreas.get(1)) > 0.4
                        && (isHasOneRowChunks(page, mergeAreas.get(0)) || isHasOneRowChunks(page, mergeAreas.get(1)))) {
                    return true;
                }

                return false;
            }

            if (!isDiscreteRects0 && !isDiscreteRects1) {
                return false;
            }

            if (overLapOfWidth(mergeAreas.get(0), mergeAreas.get(1)) < 0.5 && mergeAreas.get(1).horizontallyOverlapRatio(baseArea) > 0.8
                    && mergeAreas.get(0).horizontallyOverlapRatio(baseArea) < 0.5
                    && (baseArea.getLeft() - mergeAreas.get(0).getLeft() > 1.5 * baseArea.getWidth())) {
                return false;
            }
        }

        boolean isBaseExistDiscreteChunks = isDiscreteRects(page, Arrays.asList(baseArea), true);
        boolean isMergeExistDiscreteChunks = isDiscreteRects(page, mergeAreas, false);
        if (isBaseExistDiscreteChunks && isMergeExistDiscreteChunks) {
            return true;
        }

        List<Rectangle> tmpMergeRects = new ArrayList<>(mergeAreas);
        List<Ruling> horizontallRulings = new ArrayList<>(page.getHorizontalRulings());
        List<Ruling> verticalRulings = new ArrayList<>(page.getVerticalRulings());
        if (!horizontallRulings.isEmpty()) {
            float left = mergeAreas.stream().min(Comparator.comparing(Rectangle::getLeft)).get().getLeft();
            float right = mergeAreas.stream().max(Comparator.comparing(Rectangle::getRight)).get().getRight();
            float top = mergeAreas.stream().min(Comparator.comparing(Rectangle::getTop)).get().getTop();

            if (verticalRulings.isEmpty() && horizontallRulings.stream().anyMatch(r -> (r.getY1() > baseArea.getBottom()
                    && r.getY1() < top
                    && (r.getX1() < left || FloatUtils.feq(r.getX1(), left, 2.5))
                    && (r.getX2() > right || FloatUtils.feq(r.getX2(), right, 2.5))
                    && (r.getX1() < baseArea.getLeft() || FloatUtils.feq(r.getX1(), baseArea.getLeft(), 2.5))
                    && (r.getX2() > baseArea.getRight() || FloatUtils.feq(r.getX2(), baseArea.getRight(), 2.5))))
                    && !(isBaseExistDiscreteChunks || isMergeExistDiscreteChunks)) {
                return true;
            }

            tmpMergeRects.sort(Comparator.comparing(Rectangle::getBottom).reversed());
            for (Ruling hline : horizontallRulings) {
                if (hline.getY1() < baseArea.getBottom()) {
                    continue;
                }

                if (hline.getY1() > tmpMergeRects.get(0).getBottom()) {
                    break;
                }

                if (!verticalRulings.isEmpty() && (hline.getY1() < top && hline.getY1() > baseArea.getBottom())
                        && verticalRulings.stream().anyMatch(r -> (r.intersectsLine(hline)))
                        && (hline.getX1() < left || FloatUtils.feq(hline.getX1(), left, 2.5))
                        && (hline.getX2() > right || FloatUtils.feq(hline.getX2(), right, 2.5))
                        && (hline.getX1() < baseArea.getLeft() || FloatUtils.feq(hline.getX1(), baseArea.getLeft(), 2.5))
                        && (hline.getX2() > baseArea.getRight() || FloatUtils.feq(hline.getX2(), baseArea.getRight(), 2.5))) {
                    return false;
                }

                List<Rectangle> insertRects = mergeAreas.stream()
                        .filter(r -> (hline.intersects(r))).collect(Collectors.toList());
                if (insertRects.size() == mergeAreas.size()) {
                    return true;
                }
            }
        }


        if (mergeAreas.size() == 2) {

            float font1 = page.getTextChunks(mergeAreas.get(0)).get(0).getFontSize();
            float font2 = page.getTextChunks(mergeAreas.get(1)).get(0).getFontSize();

            float ratio = (font1 > font1) ? (font2 / font1) : (font1 / font2);

            if (ratio < 0.7) {
                return false;
            }

            if (FloatUtils.feq(mergeAreas.get(0).getLeft(), baseArea.getLeft(),
                    1.5 * getAvgCharWidth(page, baseArea, mergeAreas.get(0)))
                    && FloatUtils.feq(mergeAreas.get(1).getRight(), baseArea.getRight(),
                    1.5 * getAvgCharWidth(page, baseArea, mergeAreas.get(1)))) {
                return false;
            }

            boolean isLargeInterval = false;
            float distance0 = baseArea.verticalDistance(mergeAreas.get(0));
            float distance1 = baseArea.verticalDistance(mergeAreas.get(1));
            if (distance0 > distance1) {
                float minAvgHeight = getAvgCharHeight(page, baseArea, mergeAreas.get(1));
                if (distance1 > 3 * minAvgHeight) {
                    isLargeInterval = true;
                }
                if (distance0 - distance1 > 5 * minAvgHeight) {
                    return false;
                }
            } else {
                float minAvgHeight = getAvgCharHeight(page, baseArea, mergeAreas.get(0));
                if (distance0 > 3 * minAvgHeight) {
                    isLargeInterval = true;
                }
                if (distance1 - distance0 > 5 * minAvgHeight) {
                    return false;
                }
            }
            if (isLargeInterval) {
                return false;
            }
        }
        if (mergeAreas.size() >= 2) {
            for (Rectangle tmpMergeArea : mergeAreas) {
                if (allAreas.stream().anyMatch(r -> (r.isVerticallyOverlap(baseArea) && r.isHorizontallyOverlap(tmpMergeArea)
                        && !hasGapAreas(page, tmpMergeArea, r, allAreas) && !hasGapAreas(page, baseArea, r, allAreas)))) {
                    return false;
                }
            }
        }


        if (isIrregularAreas(page, mergeAreas)) {
            return true;
        }

        float width = 0;
        boolean isAlignLeft = false, isAlignRight = false;
        for (Rectangle mergeRect : mergeAreas) {
            if (FloatUtils.feq(mergeRect.getLeft(), baseArea.getLeft(), page.getAvgCharWidth())) {
                isAlignLeft = true;
            }
            if (FloatUtils.feq(mergeRect.getRight(), baseArea.getRight(), page.getAvgCharWidth())) {
                isAlignRight = true;
            }
            width += mergeRect.getWidth();
        }

        if (baseArea.getWidth() / width < 0.75) {
            return false;
        }

        if (isAlignLeft && isAlignRight && width > baseArea.getWidth() * 0.75) {
            return false;
        }

        List<TextChunk> baseChunks = page.getTextChunks(baseArea);
        if (!baseChunks.isEmpty()) {
            boolean isBaseTableArea = PARAGRAPH_END_RE.matcher(baseChunks.get(baseChunks.size() - 1).getText()).matches();
            int cnt = 0;
            for (Rectangle mergeRect : tmpMergeRects) {
                List<TextChunk> mergeChunks = page.getTextChunks(mergeRect);
                if (!mergeChunks.isEmpty()) {
                    boolean isMergeTableArea = PARAGRAPH_END_RE.matcher(mergeChunks.get(0).getText()).matches();
                    if (!isBaseTableArea && !isMergeTableArea) {
                        cnt++;
                    }
                } else {
                    return false;
                }
            }
            if (cnt == tmpMergeRects.size()) {
                return true;
            }
        }
        return false;
    }


    private static boolean isDiscreteRects(ContentGroupPage page, List<Rectangle> allAreas, boolean isAtTop) {

        if (allAreas.isEmpty()) {
            return false;
        }

        for (Rectangle mergeRect : allAreas) {
            List<TextChunk> baseMergeRectChunks = new ArrayList<>(page.getTextChunks(mergeRect));
            if (baseMergeRectChunks.isEmpty()) {
                continue;
            }
            if (isAtTop) {
                baseMergeRectChunks.sort(Comparator.comparing(Rectangle::getBottom).reversed());
            } else {
                baseMergeRectChunks.sort(Comparator.comparing(Rectangle::getTop));
            }
            int count = 0;
            do {
                Rectangle mergeChunk0 = baseMergeRectChunks.get(count);
                List<Rectangle> mergeRectChunks = baseMergeRectChunks.stream().filter(r -> (r.verticalOverlapRatio(mergeChunk0) > 0.5)).collect(Collectors.toList());
                if (mergeRectChunks.size() > 1) {
                    mergeRectChunks.sort(Comparator.comparing(Rectangle::getLeft));
                    Rectangle mergeBase = mergeRectChunks.get(0);
                    int cnt = 0;
                    for (int i = 1; i < mergeRectChunks.size(); i++) {
                        Rectangle mergeOther = mergeRectChunks.get(i);
                        boolean isEnglish = StringUtils.startsWithIgnoreCase(page.getLanguage(), "en");
                        double ratio = isEnglish ? 2.5 : 1.0;
                        if (mergeOther.getLeft() - mergeBase.getRight() > ratio * getAvgCharWidth(page, mergeBase, mergeOther)) {
                            cnt++;
                        }
                        mergeBase = mergeOther;
                        if (cnt >= 1) {
                            return true;
                        }
                    }
                }
                if (mergeRectChunks.size() == 1 && baseMergeRectChunks.size() > 1) {
                    count++;
                } else {
                    break;
                }

                if (count == baseMergeRectChunks.size()) {
                    break;
                }
            } while (count <= 3);
        }

        return false;
    }

    private static boolean isCanUpMultiMerges(ContentGroupPage page, Rectangle baseArea, List<Rectangle> mergeAreas, List<Rectangle> allAreas, List<Rectangle> originalChunks) {

        if (allAreas.isEmpty() || mergeAreas.isEmpty()) {
            return false;
        }

        if (isSeparatedByVline(page, mergeAreas)) {
            return false;
        }

        if (mergeAreas.size() == 1) {
            if ((mergeAreas.get(0).getWidth() / baseArea.getWidth() < 0.1)
                    && isDiscreteRects(page, Arrays.asList(baseArea), false) && mergeAreas.get(0).getLeft() < (baseArea.getLeft() + baseArea.getWidth() / 2)) {
                return true;
            }

            if (allAreas.stream().anyMatch(re -> (re.verticalOverlapRatio(mergeAreas.get(0)) > 0.5) && re.isHorizontallyOverlap(baseArea)
                    && !hasGapAreas(page, baseArea, re, allAreas) && (re.getHeight() > 5 * baseArea.getHeight() || re.getHeight() > 5 * page.getAvgCharHeight()))) {
                return false;
            }
        }

        mergeAreas.sort(Comparator.comparing(Rectangle::getLeft));
        if (mergeAreas.size() == 2) {

            Rectangle maxBottomArea = allAreas.stream().max(Comparator.comparing(Rectangle::getBottom)).get();
            if (maxBottomArea.equals(baseArea) && baseArea.getHeight() < 3 * page.getAvgCharHeight()
                    && overLapOfWidth(mergeAreas.get(0), mergeAreas.get(1)) > 0.4
                    && mergeAreas.stream().anyMatch(re -> (overLapOfHeight(re, baseArea) < 0.1))) {
                return false;
            }

            if (overLapOfWidth(mergeAreas.get(0), mergeAreas.get(1)) > 0.8
                    && FloatUtils.feq(mergeAreas.get(0).getLeft(), baseArea.getLeft(), 2.5 * page.getAvgCharWidth())
                    && FloatUtils.feq(mergeAreas.get(1).getRight(), baseArea.getRight(), 2.5 * page.getAvgCharWidth())
                    && FloatUtils.feq(mergeAreas.get(0).getRight(), mergeAreas.get(1).getLeft(), 5 * page.getAvgCharWidth())) {
                return false;
            }

            boolean isDiscreteRects0 = isDiscreteRects(page, Arrays.asList(mergeAreas.get(0)), true);
            boolean isDiscreteRects1 = isDiscreteRects(page, Arrays.asList(mergeAreas.get(1)), true);

            List<Rectangle> mergetAreaShadows0 = allAreas.stream().filter(r -> (r.isVerticallyOverlap(mergeAreas.get(0))
                    && (r != mergeAreas.get(0)))).collect(Collectors.toList());
            List<Rectangle> mergetAreaShadows1 = allAreas.stream().filter(r -> (r.isVerticallyOverlap(mergeAreas.get(1))
                    && (r != mergeAreas.get(1)))).collect(Collectors.toList());
            if (mergetAreaShadows0.size() == mergetAreaShadows1.size() && mergetAreaShadows0.stream().anyMatch(r -> r == mergeAreas.get(1))
                    && mergetAreaShadows1.stream().anyMatch(r -> r == mergeAreas.get(0))) {

                if (overLapOfWidth(mergeAreas.get(0), mergeAreas.get(1)) > 0.7 && overLapOfHeight(mergeAreas.get(0), mergeAreas.get(1)) > 0.8
                        && mergeAreas.get(0).getWidth() > 15 * page.getAvgCharWidth() && mergeAreas.get(0).getHeight() > 15 * page.getAvgCharHeight()
                        && mergeAreas.get(1).getWidth() > 15 * page.getAvgCharWidth() && mergeAreas.get(1).getHeight() > 15 * page.getAvgCharHeight()
                        && baseArea.getWidth() < mergeAreas.get(1).getRight() && mergetAreaShadows0.size() == 1
                        && FloatUtils.feq(mergeAreas.get(1).getTop(), mergeAreas.get(0).getTop(), 5 * page.getAvgCharHeight())) {
                    return false;
                }

                if (!isDiscreteRects0 && !isDiscreteRects1 && mergeAreas.get(0).getLeft() > (baseArea.getLeft() + baseArea.getWidth() / 2)) {
                    return true;
                }

                List<TextChunk> mainChunks = new ArrayList<>(page.getTextChunks(baseArea));
                if (!mainChunks.isEmpty()) {
                    mainChunks.sort(Comparator.comparing(TextChunk::getTop));
                    TextChunk thunks0 = mainChunks.get(0);
                    List<TextChunk> verticalChunks = mainChunks.stream().filter(te -> (te.verticalOverlapRatio(thunks0) > 0.5))
                            .collect(Collectors.toList());
                    verticalChunks.sort(Comparator.comparing(TextChunk::getLeft));
                    if (verticalChunks.size() == 2) {
                        TextChunk base0 = verticalChunks.get(0);
                        TextChunk base1 = verticalChunks.get(1);
                        if (base0.isHorizontallyOverlap(mergeAreas.get(0)) && base1.isHorizontallyOverlap(mergeAreas.get(1))
                                && base1.verticalDistance(mergeAreas.get(1)) < 1.5 * page.getAvgCharHeight()
                                && base0.verticalDistance(mergeAreas.get(0)) < 1.5 * page.getAvgCharHeight()
                                && FloatUtils.feq(base0.getBottom(), base1.getBottom(), 4)
                                && base0.horizontalDistance(base1) > getAvgCharWidth(page, base0, base1)
                                && isHaveHorizontalSameShadows(page, base0, mergeAreas.get(0), allAreas)
                                && isHaveHorizontalSameShadows(page, base1, mergeAreas.get(1), allAreas) ) {
                            return true;
                        }
                    }

                    if (verticalChunks.size() > 2) {
                        Rectangle mergeArea0 = mergeAreas.get(0);
                        List<Rectangle> horizonShadows0 = verticalChunks.stream().filter(hr -> (hr.isHorizontallyOverlap(mergeArea0))).collect(Collectors.toList());
                        Ruling ruling0 = new Ruling(mergeArea0.getBottom(),mergeArea0.getLeft(),(float)mergeArea0.getWidth(),0);
                        List<Ruling> hLines0 = page.getHorizontalRulings().stream().filter(hr -> (hr.horizontallyOverlapRatio(ruling0) > 0.8
                                && (hr.getY1() > ruling0.getY1() || FloatUtils.feq(hr.getY1(), ruling0.getY1(), 4.f))
                                && (hr.getY1() < baseArea.getTop() || FloatUtils.feq(hr.getY1(), baseArea.getTop(), 4.f)))).collect(Collectors.toList());
                        boolean isMergeFlag0 = false, isMergeFlag1 = false;
                        if (hLines0.size() == 1) {
                            Rectangle lineRect = new Rectangle(hLines0.get(0).getLeft(), hLines0.get(0).getTop(), hLines0.get(0).getWidth(), hLines0.get(0).getHeight());
                            if (horizonShadows0.stream().allMatch(re -> (re.isHorizontallyOverlap(lineRect)
                                    && (lineRect.getBottom() > re.getTop() || FloatUtils.feq(lineRect.getBottom(), re.getTop(), 4.f))))) {
                                isMergeFlag0 = true;
                            }
                        }

                        Rectangle mergeArea1 = mergeAreas.get(1);
                        List<Rectangle> horizonShadows1 = verticalChunks.stream().filter(hr -> (hr.isHorizontallyOverlap(mergeArea1))).collect(Collectors.toList());
                        Ruling ruling1 = new Ruling(mergeArea1.getBottom(),mergeArea1.getLeft(),(float)mergeArea1.getWidth(),0);
                        List<Ruling> hLines1 = page.getHorizontalRulings().stream().filter(hr -> (hr.horizontallyOverlapRatio(ruling1) > 0.8
                                && (hr.getY1() > ruling1.getY1() || FloatUtils.feq(hr.getY1(), ruling1.getY1(), 4.f))
                                && (hr.getY1() < baseArea.getTop() || FloatUtils.feq(hr.getY1(), baseArea.getTop(), 4.f)))).collect(Collectors.toList());
                        if (hLines1.size() == 1) {
                            Rectangle lineRect = new Rectangle(hLines1.get(0).getLeft(), hLines1.get(0).getTop(), hLines1.get(0).getWidth(), hLines1.get(0).getHeight());
                            if (horizonShadows1.stream().allMatch(re -> (re.isHorizontallyOverlap(lineRect)
                                    && (lineRect.getBottom() > re.getTop() || FloatUtils.feq(lineRect.getBottom(), re.getTop(), 4.f))))) {
                                isMergeFlag1 = true;
                            }
                        }

                        if (isMergeFlag0 && isMergeFlag1) {
                            return true;
                        }
                    }
                }

                return false;
            }

            if (!isDiscreteRects0 && !isDiscreteRects1) {
                return false;
            }
        }

        boolean isBaseExistDiscreteChunks = isDiscreteRects(page, Arrays.asList(baseArea), false);
        boolean isMergeExistDiscreteChunks = isDiscreteRects(page, mergeAreas, true);
        if (isBaseExistDiscreteChunks && isMergeExistDiscreteChunks) {
            return true;
        }

        if (isBaseExistDiscreteChunks == !isMergeExistDiscreteChunks && mergeAreas.size() == 1) {
            return false;
        }

        Rectangle footerArea = new Rectangle(baseArea.getLeft(), baseArea.getBottom(),
                baseArea.getWidth(), page.getBottom() - baseArea.getBottom())
                .rectReduce(1.0, 1.0, page.getWidth(), page.getHeight());

        List<TextChunk> baseChunks = page.getTextChunks(baseArea);

        if (!allAreas.stream().anyMatch(r -> (r.intersects(footerArea) || footerArea.contains(r) || r.contains(footerArea)))) {
            if (FloatUtils.feq(baseArea.getBottom(), page.getBottom(), 4 * baseChunks.get(0).getAvgCharWidth())) {
                return false;
            }
        }

        float threshold = page.getTextChunks(mergeAreas.get(0)).get(0).getAvgCharWidth();
        List<Rectangle> tmpMergeRects = new ArrayList<>(mergeAreas);
        List<Ruling> horizontallRulings = new ArrayList<>(page.getHorizontalRulings());
        List<Ruling> verticalRulings = new ArrayList<>(page.getVerticalRulings());
        if (!horizontallRulings.isEmpty()) {
            float left = mergeAreas.stream().min(Comparator.comparing(Rectangle::getLeft)).get().getLeft();
            float right = mergeAreas.stream().max(Comparator.comparing(Rectangle::getRight)).get().getRight();
            float bottom = mergeAreas.stream().max(Comparator.comparing(Rectangle::getBottom)).get().getBottom();

            if (verticalRulings.isEmpty() && horizontallRulings.stream().anyMatch(r -> (r.getY1() < baseArea.getTop()
                    && r.getY1() > bottom
                    && (r.getX1() < left || FloatUtils.feq(r.getX1(), left, threshold))
                    && (r.getX2() > right || FloatUtils.feq(r.getX2(), right, threshold))
                    && (r.getX1() < baseArea.getLeft() || FloatUtils.feq(r.getX1(), baseArea.getLeft(), threshold))
                    && (r.getX2() > baseArea.getRight() || FloatUtils.feq(r.getX2(), baseArea.getRight(), threshold))))
                    && !(isBaseExistDiscreteChunks || isMergeExistDiscreteChunks)) {
                return true;
            }

            for (Ruling hline : horizontallRulings) {

                if (hline.getY1() > baseArea.getTop()) {
                    break;
                }

                if (!verticalRulings.isEmpty() && (hline.getY1() > bottom && hline.getY1() < baseArea.getTop())
                        && verticalRulings.stream().anyMatch(r -> (r.intersectsLine(hline)))
                        && (hline.getX1() < left || FloatUtils.feq(hline.getX1(), left, threshold))
                        && (hline.getX2() > right || FloatUtils.feq(hline.getX2(), right, threshold))
                        && (hline.getX1() < baseArea.getLeft() || FloatUtils.feq(hline.getX1(), baseArea.getLeft(), threshold))
                        && (hline.getX2() > baseArea.getRight() || FloatUtils.feq(hline.getX2(), baseArea.getRight(), threshold))) {
                    return false;
                }

                List<Rectangle> insertRects = mergeAreas.stream()
                        .filter(r -> (hline.intersects(r))).collect(Collectors.toList());
                if (insertRects.size() == mergeAreas.size()) {
                    return true;
                }
            }
        }

        mergeAreas.sort(Comparator.comparing(Rectangle::getLeft));
        if (mergeAreas.size() == 2) {

            float font1 = page.getTextChunks(mergeAreas.get(0)).get(0).getFontSize();
            float font2 = page.getTextChunks(mergeAreas.get(1)).get(0).getFontSize();

            float ratio = (font1 > font1) ? (font2 / font1) : (font1 / font2);

            if (ratio < 0.7) {
                return false;
            }

            if (FloatUtils.feq(mergeAreas.get(0).getLeft(), baseArea.getLeft(),
                    1.5 * getAvgCharWidth(page, baseArea, mergeAreas.get(0)))
                    && FloatUtils.feq(mergeAreas.get(1).getRight(), baseArea.getRight(),
                    1.5 * getAvgCharWidth(page, baseArea, mergeAreas.get(1)))) {
                return false;
            }
        }

        if (isIrregularAreas(page, mergeAreas)) {
            return true;
        }

        float width = 0;
        boolean isAlignLeft = false, isAlignRight = false;
        for (Rectangle mergeRect : mergeAreas) {
            if (FloatUtils.feq(mergeRect.getLeft(), baseArea.getLeft(), page.getAvgCharWidth())) {
                isAlignLeft = true;
            }
            if (FloatUtils.feq(mergeRect.getRight(), baseArea.getRight(), page.getAvgCharWidth())) {
                isAlignRight = true;
            }
            width += mergeRect.getWidth();
        }

        if (baseArea.getWidth() / width < 0.75) {
            return false;
        }

        if (isAlignLeft && isAlignRight && width > baseArea.getWidth() * 0.75) {
            return false;
        }

        if (!baseChunks.isEmpty()) {
            boolean isBaseTableArea = PARAGRAPH_END_RE.matcher(baseChunks.get(0).getText()).matches();
            int cnt = 0;
            for (Rectangle mergeRect : tmpMergeRects) {
                List<TextChunk> mergeChunks = page.getTextChunks(mergeRect);
                if (!mergeChunks.isEmpty()) {
                    boolean isMergeTableArea = PARAGRAPH_END_RE.matcher(mergeChunks.get(mergeChunks.size() - 1).getText()).matches();
                    if (!isBaseTableArea && !isMergeTableArea) {
                        cnt++;
                    }
                } else {
                    return false;
                }
            }
            if (cnt == tmpMergeRects.size()) {
                return true;
            }
        }
        return false;
    }

    private static List<Rectangle> filterOverLapRects(ContentGroupPage page, Rectangle mainRect, boolean isUp, List<Rectangle> candidateRects, List<Rectangle> allRects) {

        if (candidateRects.isEmpty()) {
            return new ArrayList<>();
        }
        List<Rectangle> targetRects = new ArrayList<>(candidateRects);
        targetRects.sort(Comparator.comparing(Rectangle::getLeft));
        List<Rectangle> removeRects = new ArrayList<>();
        Rectangle baseRect = targetRects.get(0);
        for (int i = 1; i < targetRects.size(); i++) {
            Rectangle otherRect = targetRects.get(i);
            if (hasAreaBetweenTwoSignalChunkRects(page, baseRect, otherRect, allRects)) {
                if (isUp) {
                    if (baseRect.getBottom() > otherRect.getBottom()) {
                        removeRects.add(otherRect);
                    } else {
                        removeRects.add(baseRect);
                    }
                } else {
                    if (baseRect.getTop() < otherRect.getTop()) {
                        removeRects.add(otherRect);
                    } else {
                        removeRects.add(baseRect);
                    }
                }
            } else {
                baseRect = otherRect;
            }

            if (!removeRects.isEmpty() && removeRects.get(removeRects.size() - 1) == baseRect) {
                baseRect = otherRect;
            }
        }
        targetRects.removeAll(removeRects);

        if (!targetRects.isEmpty()) {
            targetRects.sort(Comparator.comparing(Rectangle::getBottom).reversed());
            List<Rectangle> candidateMergeRects = new ArrayList<>();
            Rectangle oneRect = targetRects.get(0);
            candidateMergeRects.add(oneRect);
            for (int i = 1; i < targetRects.size(); i++) {
                Rectangle otherRect = targetRects.get(i);
                if (oneRect.isVerticallyOverlap(otherRect)) {
                    candidateMergeRects.add(otherRect);
                } else {
                    break;
                }
                oneRect = otherRect;
            }
            targetRects = candidateMergeRects;

            List<Rectangle> hornRects;

            for (Rectangle mergeRect : targetRects) {
                if (isUp) {
                    hornRects = getTopDownAreaOfBaseRect(page, mergeRect, allRects, false);
                    for (Rectangle tmp : hornRects) {
                        if (tmp.isVerticallyOverlap(mainRect)
                                && (mainRect != tmp && !mainRect.nearlyContains(tmp, 1.0))) {
                            removeRects.add(mergeRect);
                        }
                    }
                }
            }

            targetRects.removeAll(removeRects);
        }

        return targetRects;
    }


    private static boolean isExistOtherRects(ContentGroupPage page, Rectangle baseArea, Rectangle mergeArea, List<Rectangle> allAreas) {

        boolean isOneAtTop = baseArea.getBottom() < mergeArea.getTop();
        float left = FastMath.min(baseArea.getLeft(), mergeArea.getLeft());
        float top = isOneAtTop ? baseArea.getBottom() : mergeArea.getBottom();
        float w = FastMath.max(baseArea.getRight(), mergeArea.getRight()) -left;
        float h = isOneAtTop ? (mergeArea.getTop() - top) : (baseArea.getTop() - top);
        Rectangle gapRect = new Rectangle(left, top, w, h);
        Rectangle gapRect1 = gapRect.rectReduce(1.0,1.0, page.getWidth(), page.getHeight());

        if (!allAreas.stream().filter(r -> (r.isVerticallyOverlap(gapRect1)
                && !(r.horizontallyOverlapRatio(mergeArea) > 0.9))).collect(Collectors.toList()).isEmpty()) {
            return true;
        }
        return false;
    }

    private static boolean isCoverByLine(ContentGroupPage page, Rectangle baseArea, Rectangle mergeArea, List<Rectangle> allAreas) {
        if (allAreas.isEmpty() || page.getHorizontalRulings().isEmpty()) {
            return false;
        }

        boolean isBaseAtTop = baseArea.getTop() < mergeArea.getTop();
        Ruling baseLine = new Ruling(baseArea.getTop(), baseArea.getLeft(), (float)baseArea.getWidth(),0);
        Ruling mergeLine = new Ruling(mergeArea.getTop(), mergeArea.getLeft(), (float)mergeArea.getWidth(),0);
        List<Ruling> gapLines;
        if (isBaseAtTop) {
            gapLines = page.getHorizontalRulings().stream()
                    .filter(hr -> (hr.horizontallyOverlapRatio(baseLine) > 0.9 && hr.horizontallyOverlapRatio(mergeLine) > 0.9
                            && (hr.getY1() > baseArea.getTop() && hr.getY1() < mergeArea.getBottom()))).collect(Collectors.toList());
        } else {
            gapLines = page.getHorizontalRulings().stream()
                    .filter(hr -> (hr.horizontallyOverlapRatio(baseLine) > 0.9 && hr.horizontallyOverlapRatio(mergeLine) > 0.9
                            && (hr.getY1() > mergeArea.getTop() && hr.getY1() < baseArea.getBottom()))).collect(Collectors.toList());
        }

        if (gapLines.size() == 1) {
            Rectangle gapRect = new Rectangle(gapLines.get(0).getLeft(), gapLines.get(0).getTop(),
                    gapLines.get(0).getWidth(), gapLines.get(0).getHeight());
            List<Rectangle> tmpAllAreas = allAreas.stream().filter(r ->!r.intersectsLine(gapLines.get(0))).collect(Collectors.toList());
            List<Rectangle> tmpMerges;
            if (isBaseAtTop) {
                if (gapRect.intersects(mergeArea)) {
                    gapRect.setBottom(mergeArea.getTop() - 1);
                }
                if (gapRect.intersects(baseArea)) {
                    gapRect.setTop(baseArea.getBottom() + 1);
                }
                tmpMerges = allAreas.stream().filter(r -> (r.horizontallyOverlapRatio(gapRect) > 0.9
                        && r.isVerticallyOverlap(mergeArea) && !hasGapAreas(page, r, gapRect, tmpAllAreas)
                        && (r != mergeArea))).collect(Collectors.toList());
            } else {
                if (gapRect.intersects(mergeArea)) {
                    gapRect.setTop(mergeArea.getBottom() + 1);
                }
                if (gapRect.intersects(baseArea)) {
                    gapRect.setBottom(baseArea.getTop() - 1);
                }
                tmpMerges = allAreas.stream().filter(r -> (r.horizontallyOverlapRatio(gapRect)> 0.9
                        && r.isVerticallyOverlap(baseArea) && !hasGapAreas(page, r, gapRect, tmpAllAreas)
                        && (r != baseArea))).collect(Collectors.toList());
            }

            if (!tmpMerges.isEmpty()) {
                return true;
            } else {
                List<Rectangle> tmpAllAreas1 = new ArrayList<>(allAreas);
                tmpAllAreas1.remove(mergeArea);
                if (!isBaseAtTop) {
                    List<Rectangle> upShadowRects = allAreas.stream()
                            .filter(r -> (r.isHorizontallyOverlap(gapRect) && r.getBottom() < mergeArea.getTop()
                                    && !hasGapAreas(page, r, gapRect, tmpAllAreas1))).collect(Collectors.toList());
                    if (upShadowRects.size() > 1 && upShadowRects.stream()
                            .anyMatch(r -> (FloatUtils.feq(r.getWidth(), baseArea.getWidth(), 2 * getAvgCharWidth(page, baseArea, r))
                                    && FloatUtils.feq(baseArea.getLeft(), r.getLeft(), 2 * getAvgCharWidth(page, baseArea, r))))) {
                        return true;
                    }
                }
            }
        }


        return false;
    }

    private static boolean isCanMerge(ContentGroupPage page, Rectangle baseArea, Rectangle mergeArea, List<Rectangle> allAreas) {

        if (allAreas.isEmpty()) {
            return false;
        }
        boolean isMerge = true;
        if (baseArea.getLeft() > mergeArea.getLeft()) {
            Rectangle tmpRect = new Rectangle(mergeArea.getLeft(), mergeArea.getTop(),
                    baseArea.getLeft() - mergeArea.getLeft(), mergeArea.getHeight());
            if (allAreas.stream().anyMatch(r -> (tmpRect.isHorizontallyOverlap(r)
                    && baseArea.isVerticallyOverlap(r)))) {
                isMerge = false;
            }
        }

        if (isMerge && (baseArea.getRight() < mergeArea.getRight())) {
            Rectangle tmpRect = new Rectangle(baseArea.getRight(), mergeArea.getTop(),
                    mergeArea.getRight() - baseArea.getRight(), mergeArea.getHeight());
            if (allAreas.stream().anyMatch(r -> (tmpRect.isHorizontallyOverlap(r)
                    && baseArea.isVerticallyOverlap(r)))) {
                isMerge = false;
            }
        }
        if (isMerge && baseArea.verticalDistance(mergeArea) > 10 * getAvgCharHeight(page, baseArea, mergeArea)) {
            isMerge = false;
        }

        return isMerge;
    }

    private static boolean isCanMergeGroupRects(ContentGroupPage page, List<Rectangle> groupRects, List<Rectangle> allRects) {

        if (groupRects.isEmpty() || page.getHorizontalRulings().isEmpty()) {
            return false;
        }

        groupRects.sort(Comparator.comparing(Rectangle::getLeft));

        if (groupRects.size() == 2) {
            if (isSeparatedByVline(page, groupRects)) {
                return false;
            }
            Rectangle base = groupRects.get(0);
            Rectangle other = groupRects.get(1);

            if (base.getHeight() < 2 * page.getAvgCharHeight() && other.getHeight() < 2 * page.getAvgCharHeight()
                    && base.horizontalDistance(other) > 5 * page.getAvgCharWidth()) {
                List<TextChunk> baseChunks = page.getTextChunks(base);
                List<TextChunk> otherChunks = page.getTextChunks(other);
                if (!baseChunks.isEmpty() && !otherChunks.isEmpty() && baseChunks.size() == otherChunks.size() && baseChunks.size() == 1) {
                    float font1 = baseChunks.get(0).getFontSize();
                    float font2 = otherChunks.get(0).getFontSize();
                    boolean isBaseMax = font1 > font2;
                    float ratio = isBaseMax ? (font2 / font1) : (font1 / font2);
                    if (ratio < 0.8) {
                        return false;
                    }
                }
            }
        }

        for (Ruling hline : page.getHorizontalRulings()) {
            Rectangle tmp = new Rectangle(hline.getLeft(), hline.getTop(), hline.getWidth(), hline.getHeight());
            if (groupRects.stream().allMatch(r -> (r.horizontallyOverlapRatio(tmp) > 0.8
                    && r.getTop() > tmp.getBottom() && !hasGapAreas(page, tmp, r, allRects) && diffFontRatio(page, tmp, r) >= 0.8
                    && r.verticalDistance(tmp) < 1.5 * page.getAvgCharHeight() && r.getHeight() < 5 * page.getAvgCharHeight()))) {
                return true;
            }
        }
        return false;
    }

    private static float diffFontRatio(ContentGroupPage page, Rectangle base, Rectangle other) {

        float ratio = 0.f;
        if (page.getTextChunks(base).isEmpty() || page.getTextChunks(other).isEmpty()) {
            return ratio;
        }

        List<TextChunk> baseChunks = page.getTextChunks(base);
        List<TextChunk> otherChunks = page.getTextChunks(other);
        float font1, font2;
        boolean isBaseAtLeft = base.getLeft() < other.getLeft();
        if (base.verticalOverlapRatio(other) > 0.5) {
            if (isBaseAtLeft) {
                font1 = baseChunks.get(baseChunks.size() - 1).getFontSize();
                font2 = otherChunks.get(0).getFontSize();
            } else {
                font1 = otherChunks.get(otherChunks.size() - 1).getFontSize();
                font2 = baseChunks.get(0).getFontSize();
            }
            ratio = font1 > font2 ? (font2 / font1) : (font1 / font2);
            return ratio;
        }

        boolean isBaseAtTop = base.getTop() < other.getTop();
        if (isBaseAtTop) {
            font1 = baseChunks.get(baseChunks.size() - 1).getFontSize();
            font2 = otherChunks.get(0).getFontSize();
        } else {
            font1 = otherChunks.get(otherChunks.size() - 1).getFontSize();
            font2 = baseChunks.get(0).getFontSize();
        }
        ratio = font1 > font2 ? (font2 / font1) : (font1 / font2);

        return ratio;
    }

    private static List<Rectangle> mergeAreasByLineOverLap(ContentGroupPage page, List<Rectangle> textRects) {

        if (textRects.isEmpty()) {
            return new ArrayList<>();
        }

        List<Rectangle> mergeRects = new ArrayList<>();
        // 被直线覆盖的分栏应该合并
        List<Rectangle> allRects = new ArrayList<>(textRects);
        allRects.sort(Comparator.comparing(Rectangle::getTop));

        allRects.sort(Comparator.comparing(Rectangle::getTop));
        List<Rectangle> groupRects = new ArrayList<>();
        Rectangle base = allRects.get(0);
        groupRects.add(base);
        for (int i = 1; i < allRects.size(); i++) {
            Rectangle candidate = allRects.get(i);
            if (FloatUtils.feq(base.getTop(), candidate.getTop(), 3.5)) {
                groupRects.add(candidate);
            } else {
                base = candidate;
                if (isCanMergeGroupRects(page, groupRects, textRects)) {
                    Rectangle mergeRect = new Rectangle(groupRects.get(0));
                    for (int j = 1; j < groupRects.size(); j++) {
                        mergeRect.merge(groupRects.get(j));
                    }
                    mergeRects.add(mergeRect);
                } else {
                    mergeRects.addAll(new ArrayList<>(groupRects));
                }
                groupRects.clear();
                groupRects.add(candidate);
            }
        }
        if (isCanMergeGroupRects(page, groupRects, textRects)) {
            Rectangle mergeRect = new Rectangle(groupRects.get(0));
            for (int j = 1; j < groupRects.size(); j++) {
                mergeRect.merge(groupRects.get(j));
            }
            mergeRects.add(mergeRect);
        } else {
            mergeRects.addAll(new ArrayList<>(groupRects));
        }
        return mergeRects;
    }

    private static boolean isExistContainedFillArea(ContentGroupPage page, List<Rectangle> mergeRects) {

        if (mergeRects.isEmpty() || page.getPageFillAreaGroup().isEmpty()) {
            return false;
        }

        List<FillAreaGroup> groupFills = page.getPageFillAreaGroup();
        for (FillAreaGroup area : groupFills) {
            if (mergeRects.stream().allMatch(r -> (area.getGroupRectArea().nearlyContains(r, 1)))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isExistLongDistanceColumnerRect(ContentGroupPage page, Rectangle mainArea, Rectangle mergeArea, List<Rectangle> candidateRects) {

        if (candidateRects.isEmpty()) {
            return false;
        }

        List<Rectangle> columerRects = candidateRects.stream().filter(r -> (r.isHorizontallyOverlap(mainArea)
                && (r.isVerticallyOverlap(mergeArea) || (isSeparatedByVlineBetweenRects(page, mergeArea, r)))
                && (r != mergeArea) && !hasGapAreas(page, r, mainArea, candidateRects))).collect(Collectors.toList());
        if (columerRects.size() == 1) {
            Rectangle columerRect = columerRects.get(0);
            if (isSeparatedByVlineBetweenRects(page, mergeArea, columerRect)) {
                return true;
            }
            List<Rectangle> mergetAreaShadows = candidateRects.stream().filter(r -> (r.isVerticallyOverlap(mergeArea) && (r != mergeArea))).collect(Collectors.toList());
            List<Rectangle> columAreaShadows = candidateRects.stream().filter(r -> (r.isVerticallyOverlap(columerRect) && (r != columerRect))).collect(Collectors.toList());
            if (mergetAreaShadows.size() == columAreaShadows.size() && mergetAreaShadows.stream().anyMatch(r -> r == columerRect)
                    && columAreaShadows.stream().anyMatch(r -> r == mergeArea)) {
                return true;
            }
            if (mergetAreaShadows.size() != columAreaShadows.size()) {
                boolean isMergeAtTop = mainArea.getTop() > mergeArea.getTop();
                boolean isExistDiscreteOfMerge = isMergeAtTop ? isDiscreteRects(page, Arrays.asList(mergeArea), true)
                        : isDiscreteRects(page, Arrays.asList(mergeArea), false);
                boolean isExistDiscreteOfColum = isMergeAtTop ? isDiscreteRects(page, Arrays.asList(columerRect), true)
                        : isDiscreteRects(page, Arrays.asList(columerRect), false);
                if (!isExistDiscreteOfMerge && !isExistDiscreteOfColum) {
                    return true;
                }
            }
        }

        if (columerRects.size() > 1) {
            columerRects.add(mergeArea);
            columerRects.sort(Comparator.comparing(Rectangle::getLeft));
            Rectangle base = columerRects.get(0);
            int cnt = 0;
            for (int i = 1; i < columerRects.size(); i++) {
                Rectangle other = columerRects.get(i);
                if (isSeparatedByVlineBetweenRects(page, base, other)) {
                    cnt++;
                }
                base = other;
            }
            if (cnt == (columerRects.size() - 1)) {
                return true;
            }
        }

        return false;
    }

    private static boolean isExistColumerRects(ContentGroupPage page, Rectangle baseArea, Rectangle mergeArea, List<Rectangle> allAreas, boolean isMainAtUp) {

        if (!baseArea.isHorizontallyOverlap(mergeArea)) {
            return true;
        }

        List<Rectangle> tmpBaseShadowAreas = allAreas.stream().filter(re -> (re.isVerticallyOverlap(baseArea))).collect(Collectors.toList());
        List<Rectangle> tmpMergeShadowAreas = allAreas.stream().filter(re -> (re.isVerticallyOverlap(mergeArea))).collect(Collectors.toList());
        List<Rectangle> vRects = page.getVerticalRulings().stream()
                .map(l ->(new Rectangle(l.getLeft(), l.getTop(), l.getWidth(), l.getHeight()))).collect(Collectors.toList());
        if (!vRects.isEmpty()) {
            if (isSeparatedByVline(page, tmpBaseShadowAreas)) {
                if (baseArea.verticalDistance(mergeArea) < 5 * getAvgCharWidth(page, baseArea, mergeArea)
                        && vRects.stream().anyMatch(re -> ((re.getCenterX() > mergeArea.getLeft() && re.getCenterX() < mergeArea.getRight())
                        && re.isVerticallyOverlap(baseArea)
                        && overLapOfHeight(re, baseArea) > 0.7
                        && mergeArea.getRight() > baseArea.getRight()))) {
                    return true;
                }
            }

            if (isSeparatedByVline(page, tmpMergeShadowAreas)) {
                if (baseArea.verticalDistance(mergeArea) < 5 * getAvgCharWidth(page, baseArea, mergeArea)
                        && vRects.stream().anyMatch(re -> ((re.getCenterX() > baseArea.getLeft() && re.getCenterX() < baseArea.getRight())
                        && re.isVerticallyOverlap(mergeArea)
                        && overLapOfHeight(re, mergeArea) > 0.7
                        && baseArea.getRight() > mergeArea.getRight()))) {
                    return true;
                }
            }
        }

        if (isSeparatedByHline(page, baseArea, mergeArea)) {
            List<Rectangle> mergeShadows = new ArrayList<>();
            if (isMainAtUp) {
                mergeShadows.addAll(allAreas.stream().filter(re -> (re.isVerticallyOverlap(baseArea))).collect(Collectors.toList()));
            } else {
                mergeShadows.addAll(allAreas.stream().filter(re -> (re.isVerticallyOverlap(mergeArea))).collect(Collectors.toList()));
            }

            if (!mergeShadows.isEmpty()) {
                if (isSeparatedByVline(page, mergeShadows)) {
                    return true;
                }
            }
        }
        return false;
    }


    private static List<FillAreaGroup> getRectFillArea1(ContentGroupPage page) {

        if (page.getPageFillAreaGroup().isEmpty()) {
            return new ArrayList<>();
        }

        List<FillAreaGroup> rectFillGroups = page.getPageFillAreaGroup().stream().filter(fill -> (fill.getGroupFillAreas().size() == 1
                && fill.getGroupAreaType() == FillAreaGroup.AreaType.BAR_AREA
                && fill.getGroupRectArea().getHeight() < 3 * page.getAvgCharHeight()
                && fill.getGroupRectArea().getWidth() > 3 * fill.getGroupRectArea().getHeight())).collect(Collectors.toList());

        return rectFillGroups;
    }

    private static List<FillAreaGroup> getTextRectFillArea(ContentGroupPage page) {

        if (page.getPageFillAreaGroup().isEmpty()) {
            return new ArrayList<>();
        }

        List<FillAreaGroup> rectFillGroups = page.getPageFillAreaGroup().stream().filter(fill -> (fill.getGroupAreaType() == FillAreaGroup.AreaType.BAR_AREA
                && fill.getGroupRectArea().getHeight() < 3 * page.getAvgCharHeight()
                && fill.getGroupRectArea().getWidth() / fill.getGroupRectArea().getHeight() > 3
                && !page.getText(fill.getGroupRectArea()).isEmpty()
                && isHasOneRowChunks(page, fill.getGroupRectArea())
                && fill.getGroupRectArea().getWidth() > 5 * fill.getGroupRectArea().getHeight())).collect(Collectors.toList());

        List<FillAreaGroup> sectorFillGroups = page.getPageFillAreaGroup().stream().filter(fill -> (fill.getGroupFillAreas().size() == 1
                && fill.getGroupAreaType() == FillAreaGroup.AreaType.PIE_AREA
                && fill.getGroupRectArea().getWidth() / fill.getGroupRectArea().getHeight() > 3
                && !page.getText(fill.getGroupRectArea()).isEmpty()
                && page.getText(fill.getGroupRectArea()).stream().allMatch(te -> (fill.getGroupRectArea().nearlyContains(te, 1.0)))
                && isHasOneRowChunks(page, fill.getGroupRectArea())
                && fill.getGroupRectArea().getWidth() > 5 * fill.getGroupRectArea().getHeight())).collect(Collectors.toList());

        rectFillGroups.addAll(sectorFillGroups);
        return rectFillGroups;
    }

    private static boolean isHasOneRowChunks(ContentGroupPage page, Rectangle area) {

        if (page.getText(area).isEmpty() || page.getTextChunks(area).isEmpty()) {
            return false;
        }

        List<TextChunk> rowChunks = page.getTextChunks(area);
        if (rowChunks.size() == 1) {
            return true;
        }
        TextChunk base = rowChunks.get(0);
        int sameCnt = 0;
        for (int i = 1; i < rowChunks.size(); i++) {
            TextChunk other = rowChunks.get(i);
            if (base.verticalOverlapRatio(other) > 0.5) {
                sameCnt++;
            } else {
                return false;
            }
            base = other;
        }
        if (sameCnt == rowChunks.size() - 1) {
            return true;
        }

        return false;
    }

    private static List<Rectangle> mergeAreasByFills(ContentGroupPage page, List<FillAreaGroup> fillGroups, List<Rectangle> textRects) {

        if (textRects.isEmpty() || fillGroups.isEmpty()) {
            return textRects;
        }

        List<Rectangle> removeRects = new ArrayList<>();
        for (FillAreaGroup fill : fillGroups) {
            List<Rectangle> fillTexts = textRects.stream().filter(te -> (fill.getGroupRectArea().contains(te))).collect(Collectors.toList());
            if (fillTexts.size() > 1) {
                Rectangle mergeRect = fillTexts.get(0);
                for (int i = 1; i < fillTexts.size(); i++) {
                    mergeRect.merge(fillTexts.get(i));
                    removeRects.add(fillTexts.get(i));
                }
            }
        }
        textRects.removeAll(removeRects);
        return textRects;
    }

    private static List<Rectangle> mergeMostPossibleTextRegions(ContentGroupPage page, List<Rectangle> textRects,
                                                                List<Rectangle> originalChunks, List<Rectangle> chartAreas) {

        if (textRects.isEmpty() || originalChunks.isEmpty()) {
            return new ArrayList<>();
        }

        List<GroupInfo> groupInfos = new ArrayList<>();


        // 通过填充区进行区域融合
        //TableDebugUtils.writeCells(page, textRects, "textRects");
        textRects = mergeAreasByLineOverLap(page, textRects);

        boolean mergeFlag;
        List<Rectangle> tmpAreas = new ArrayList<>(textRects);
        processRepeateAndContainedAreas(page, tmpAreas);

        for (int i = 0; i < tmpAreas.size(); i++) {
            GroupInfo groupInfo = new GroupInfo(tmpAreas.get(i), i);
            groupInfos.add(groupInfo);
        }

        List<Rectangle> mergeAreas = new ArrayList<>();
        // 通过空间信息结构融合区域
        tmpAreas.sort(Comparator.comparing(Rectangle::getArea).reversed());
        for (Rectangle tmpArea : tmpAreas) {

            if ((!mergeAreas.isEmpty() && mergeAreas.stream().anyMatch(r -> (r.contains(tmpArea) || r.nearlyContains(tmpArea, 2.0))))) {
                continue;
            }
            // 上方所有的区域
            do {
                List<Rectangle> upRects = tmpAreas.stream().filter(r -> (r.getBottom() <= tmpArea.getTop() || r.intersects(tmpArea))).collect(Collectors.toList());
                List<Rectangle> candidateUpRects = upSearchRectsToMerge(page, tmpArea, upRects, textRects);
                List<Rectangle> mergeRects = new ArrayList<>();
                for (Rectangle upRect : candidateUpRects) {
                    if (upRect.intersects(tmpArea)) {
                        tmpArea.merge(new Rectangle(upRect));
                        continue;
                    }
                    if (!hasGapAreas(page, tmpArea, upRect, candidateUpRects) && tmpArea.verticalDistance(upRect) < 5 * getAvgCharHeight(page, tmpArea, upRect)) {
                        mergeRects.add(upRect);
                    }

                    if (mergeRects.size() > 1 && isSeparatedByVline(page, mergeRects)) {
                        mergeRects.clear();
                    }
                }

                List<Rectangle> finalMergeRects = mergeRects;
                if (isExistContainedFillArea(page, mergeRects)
                        && mergeRects.stream().allMatch(r -> (r.getWidth() <= tmpArea.getWidth()
                        && r.isVerticallyOverlap(finalMergeRects.get(0))))
                        && mergeRects.size() > 1) {
                    for (Rectangle tmp : mergeRects) {
                        tmpArea.merge(tmp);
                    }
                    mergeFlag = true;
                    continue;
                }

                //TableDebugUtils.writeCells(page, mergeRects, "xxxmergeRects");
                //TableDebugUtils.writeCells(page, Arrays.asList(tmpArea), "xxxtmpArea");
                //TableDebugUtils.writeCells(page, mergeAreas, "xxxmergeAreas");

                if (mergeRects.size() == 1 && isCanMerge(page, tmpArea, mergeRects.get(0), tmpAreas)
                        && isVerticalNearlyAtMergeRect(page, tmpArea, mergeRects.get(0), tmpAreas)
                        && !isCoverByLine(page, tmpArea, mergeRects.get(0), tmpAreas)
                        && !isExistLongDistanceColumnerRect(page, tmpArea, mergeRects.get(0), tmpAreas)
                        && isCanMergeUpByStreamOfTextChunks(page, tmpArea, mergeRects.get(0), tmpAreas, groupInfos)
                        && !isExistColumerRects(page, tmpArea, mergeRects.get(0), tmpAreas, false)
                        && !isHaveAllChartAreas(page, tmpArea,mergeRects.get(0))) {
                    if (!chartAreas.isEmpty()) {
                        List<Rectangle> finalMergeRects1 = mergeRects;
                        if (isSignalChartAreas(page, mergeRects.get(0)) && chartAreas.stream()
                                .anyMatch(re -> ((re.horizontallyOverlapRatio(finalMergeRects1.get(0)) > 0.8 || re.verticalOverlapRatio(finalMergeRects1.get(0)) > 0.5))
                                        && !hasGapAreas(page, re, finalMergeRects1.get(0), tmpAreas))) {
                            mergeFlag = false;
                        } else {
                            tmpArea.merge(mergeRects.get(0));
                            mergeFlag = true;
                        }
                    } else {
                        if (isSignalChartAreas(page, tmpArea) && tmpAreas.stream().anyMatch(re -> ((re.horizontallyOverlapRatio(tmpArea) > 0.8)
                                || re.verticalOverlapRatio(tmpArea) > 0.5)
                                && isSignalChartAreas(page, re) && !hasGapAreas(page, tmpArea, re, tmpAreas))) {
                            mergeFlag = false;
                        } else {
                            tmpArea.merge(mergeRects.get(0));
                            mergeFlag = true;
                        }
                    }
                } else {
                    if (mergeRects.size() > 1) {
                        mergeRects = filterOverLapRects(page, tmpArea, true, mergeRects, tmpAreas);
                        if (isCanUpMultiMerges(page, tmpArea, mergeRects, tmpAreas, originalChunks)) {
                            for (Rectangle tmp : mergeRects) {
                                tmpArea.merge(tmp);
                            }
                            mergeFlag = true;
                        } else {
                            mergeFlag = false;
                        }
                    } else {
                        mergeFlag = false;
                    }
                }
            } while (mergeFlag);
            mergeAreas.add(tmpArea);
        }
        processRepeateAndContainedAreas(page, mergeAreas);

        if (mergeAreas.isEmpty()) {
            return new ArrayList<>();
        }
        mergeAreas.sort(Comparator.comparing(Rectangle::getArea).reversed());
        // 下方所有的区域
        List<Rectangle> targetRects = new ArrayList<>();
        for (Rectangle tmpArea : mergeAreas) {
            if ((!targetRects.isEmpty() && targetRects.stream().anyMatch(r -> (r.contains(tmpArea) || r.nearlyContains(tmpArea, 2.0))))) {
                continue;
            }
            // 下方所有的区域
            do {
                List<Rectangle> downRects = mergeAreas.stream().filter(r -> (r.getTop() >= tmpArea.getBottom())).collect(Collectors.toList());
                List<Rectangle> candidateDownRects = downSearchRectsToMerge(page, tmpArea, downRects, mergeAreas);
                List<Rectangle> mergeRects = new ArrayList<>();
                for (Rectangle downRect : candidateDownRects) {
                    if (!hasGapAreas(page, tmpArea, downRect, candidateDownRects) && tmpArea.verticalDistance(downRect) < 5 * getAvgCharHeight(page, tmpArea, downRect)) {
                        mergeRects.add(downRect);
                    }
                }
                //TableDebugUtils.writeCells(page, mergeRects, "yyy_mergeRects");
                //TableDebugUtils.writeCells(page, Arrays.asList(tmpArea), "yyy_tmpArea");
                //TableDebugUtils.writeCells(page, targetRects, "yyy_targetRects");
                if (mergeRects.size() == 1 && isVerticalNearlyAtMergeRect(page, tmpArea, mergeRects.get(0), mergeAreas)
                        && isCanMerge(page, tmpArea, mergeRects.get(0), mergeAreas)
                        && !isExistOtherRects(page, tmpArea, mergeRects.get(0), mergeAreas)
                        && !isExistLongDistanceColumnerRect(page, tmpArea, mergeRects.get(0), candidateDownRects)
                        && isCanMergeDownByStreamOfTextChunks(page, tmpArea, mergeRects.get(0), mergeAreas, groupInfos)
                        && !isExistColumerRects(page, tmpArea, mergeRects.get(0), tmpAreas, true)
                        && !isHaveAllChartAreas(page, tmpArea,mergeRects.get(0))) {
                    boolean isAlignEdges = FloatUtils.feq(tmpArea.getLeft(), mergeRects.get(0).getLeft(), 5 * getAvgCharWidth(page, tmpArea, mergeRects.get(0)));
                    if (overLapOfWidth(tmpArea, mergeRects.get(0)) < 0.7 && !isAlignEdges) {
                        mergeFlag = false;
                    } else {
                        tmpArea.merge(mergeRects.get(0));
                        mergeFlag = true;
                    }
                } else {
                    if (mergeRects.size() > 1) {
                        mergeRects = filterOverLapRects(page, tmpArea, false, mergeRects, mergeAreas);
                        if (isCanDownMultiMerges(page, tmpArea, mergeRects, mergeAreas, originalChunks)) {
                            for (Rectangle tmp : mergeRects) {
                                tmpArea.merge(tmp);
                            }
                            mergeFlag = true;
                        } else {
                            mergeFlag = false;
                        }
                    } else {
                        mergeFlag = false;
                    }
                }
            } while (mergeFlag);
            targetRects.add(tmpArea);
        }

        processRepeateAndContainedAreas(page, targetRects);
        filterSmallRegions(page, targetRects);
        targetRects = mergeSomeMixArea(page, targetRects, chartAreas);
        // 进行左右融合
        targetRects = mergeLeftRightAreas(page, targetRects);
        mergeMarginAreas(page, targetRects);
        if (targetRects.isEmpty()) {
            targetRects = Arrays.asList(page);
        }
        return targetRects;
    }

    private static boolean isVerticalNearlyAtMergeRect(ContentGroupPage page, Rectangle mainArea, Rectangle mergeArea, List<Rectangle> allRects) {

        if (mainArea.horizontallyOverlapRatio(mergeArea) < 0.8 || allRects.isEmpty()) {
            return false;
        }

        List<Rectangle> allNearlyAreas = allRects.stream().filter(re -> (re.horizontallyOverlapRatio(mergeArea) > 0.9
                && !hasGapAreas(page, mergeArea, re, allRects) && !re.equals(mainArea))).collect(Collectors.toList());

        if (!allNearlyAreas.isEmpty()) {
            float height1 = mainArea.verticalDistance(mergeArea);
            float height2 = 5000.f;
            for (Rectangle nearlyArea : allNearlyAreas) {
                float tmpHeight = nearlyArea.verticalDistance(mergeArea);
                if (tmpHeight < height2) {
                    height2 = tmpHeight;
                }
            }

            if (height1 < height2) {
                return true;
            }
        } else {
            return true;
        }
        return false;
    }

    private static Rectangle isHorizontallyAtMergeRect(ContentGroupPage page, Rectangle mainArea, Rectangle mergeArea, List<Rectangle> allRects) {

        if (mainArea.verticalOverlapRatio(mergeArea) < 0.8 || allRects.isEmpty()) {
            return mainArea;
        }

        List<Rectangle> allNearlyAreas = allRects.stream().filter(re -> (re.verticalOverlapRatio(mergeArea) > 0.9
                && !hasGapAreas(page, mergeArea, re, allRects) && !re.equals(mainArea))).collect(Collectors.toList());

        if (!allNearlyAreas.isEmpty()) {
            float width1 = mainArea.horizontalDistance(mergeArea);
            float width2 = 5000.f;
            Rectangle targetArea = null;
            for (Rectangle nearlyArea : allNearlyAreas) {
                float tmpWidth = nearlyArea.horizontalDistance(mergeArea);
                if (tmpWidth < width2) {
                    width2 = tmpWidth;
                    targetArea = nearlyArea;
                }
            }

            if (width1 > width2 && targetArea != null && overLapOArea(mergeArea, targetArea) < 0.2
                    && overLapOArea(mergeArea, mainArea) < 0.2 && isHaveAllChartAreas(page, mainArea, targetArea)) {
                return targetArea;
            }
        }

        return mainArea;
    }

    private static List<Rectangle> mergeLeftRightAreas(ContentGroupPage page, List<Rectangle> mergeAreas) {

        if (mergeAreas.isEmpty()) {
            return new ArrayList<>();
        }

        List<Rectangle> targetAreas = new ArrayList<>();
        mergeAreas.sort(Comparator.comparing(Rectangle::getArea).reversed());
        boolean mergeFlag, smallFlag;
        for (Rectangle tmpArea : mergeAreas) {
            if ((!targetAreas.isEmpty() && targetAreas.stream().anyMatch(r -> (r.contains(tmpArea) || r.nearlyContains(tmpArea, 2.0))))) {
                continue;
            }
            do {
                mergeFlag = false;
                smallFlag = false;
                if (tmpArea.getWidth() < 15 * page.getAvgCharWidth() && tmpArea.getHeight() < 5 * page.getAvgCharHeight()) {
                    mergeFlag = true;
                    smallFlag = true;
                }

                if (!mergeFlag) {
                    List<Rectangle> verticallyClusAreas = mergeAreas.stream().filter(re -> (re.verticalOverlapRatio(tmpArea) > 0.9
                            && !hasGapAreas(page, tmpArea, re, mergeAreas) && !re.equals(tmpArea)
                            && (re.getTop() > tmpArea.getTop() || FloatUtils.feq(tmpArea.getTop(), re.getTop(), page.getAvgCharHeight()))
                            && (re.getBottom() < tmpArea.getBottom() || FloatUtils.feq(re.getBottom(), tmpArea.getBottom(), page.getAvgCharHeight()))
                            && !page.getTextChunks(re).stream().allMatch(TextChunk::isPageHeader))).collect(Collectors.toList());

                    if (verticallyClusAreas.size() == 1) {
                        Rectangle clusArea = verticallyClusAreas.get(0);
                        if (clusArea.getHeight() < 1.5 * page.getAvgCharHeight() && clusArea.getWidth() < 5 * page.getAvgCharWidth()
                                && overLapOfWidth(tmpArea, clusArea) < 0.2) {
                            tmpArea.merge(verticallyClusAreas.get(0));
                            mergeFlag = true;
                        }

                        if (!mergeFlag && tmpArea.horizontalDistance(clusArea) < 2.5 * page.getAvgCharWidth()
                                && clusArea.getHeight() / clusArea.getWidth() > 5 && tmpArea.getWidth() / clusArea.getWidth() > 5) {
                            tmpArea.merge(verticallyClusAreas.get(0));
                            mergeFlag = true;
                        }

                        if (!mergeFlag && tmpArea.verticalOverlapRatio(clusArea) > 0.95
                                && !hasOtherVerticalShadowRects(page, tmpArea, clusArea, mergeAreas)
                                && overLapOfWidth(tmpArea, clusArea) < 0.2
                                && overLapOArea(tmpArea, clusArea) < 0.1 && clusArea.getWidth() < 5 * page.getAvgCharWidth()
                                && tmpArea.horizontalDistance(clusArea) < 5 * page.getAvgCharWidth()) {
                            tmpArea.merge(verticallyClusAreas.get(0));
                            mergeFlag = true;
                        }
                    }

                    if (verticallyClusAreas.size() == 2) {
                        verticallyClusAreas = verticallyClusAreas.stream().filter(re -> (tmpArea.horizontalDistance(re) < 2.5 * page.getAvgCharWidth()
                                && re.getHeight() / re.getWidth() > 5 && tmpArea.getWidth() / re.getWidth() > 5)).collect(Collectors.toList());
                        if (!verticallyClusAreas.isEmpty()) {
                            for (Rectangle clusArea : verticallyClusAreas) {
                                tmpArea.merge(clusArea);
                            }
                            mergeFlag = true;
                        }
                    }
                }
            } while(mergeFlag && !smallFlag);
            targetAreas.add(tmpArea);
        }

        return targetAreas;
    }

    private static void mergeMarginAreas(ContentGroupPage page, List<Rectangle> mergeAreas) {

        List<TextChunk> marginChunks = page.getPageMarginChunks();
        if (marginChunks.isEmpty() || mergeAreas.isEmpty()) {
            return;
        }

        List<TextChunk> footerChunks = marginChunks.stream().filter(te -> (te.getPaginationType() == PaginationType.FOOTER)).collect(Collectors.toList());
        Rectangle marginTopArea = null, marginBottomArea = null;
        if (!footerChunks.isEmpty()) {
            float left = footerChunks.stream().min(Comparator.comparing(TextChunk::getLeft)).get().getLeft();
            float top = footerChunks.stream().min(Comparator.comparing(TextChunk::getTop)).get().getTop();
            float right = footerChunks.stream().max(Comparator.comparing(TextChunk::getRight)).get().getRight();
            float bottom = footerChunks.stream().max(Comparator.comparing(TextChunk::getBottom)).get().getBottom();
            marginBottomArea = new Rectangle(left, top, right - left, bottom - top);
        }
        if (marginBottomArea != null) {
            mergeAreas.sort(Comparator.comparing(Rectangle::getBottom).reversed());
            Rectangle bottomArea = mergeAreas.get(0);
            float avgHeight;
            avgHeight = (float)footerChunks.get(0).getHeight();
            List<TextChunk> mergeAreaChunks = page.getTextChunks(bottomArea);
            if (!mergeAreaChunks.isEmpty()) {
                float tmpAvgHeight = mergeAreaChunks.get(mergeAreaChunks.size() - 1).getAvgCharHeight();
                avgHeight = tmpAvgHeight < avgHeight ? avgHeight : tmpAvgHeight;
            }

            boolean isMerge = false;
            if (bottomArea.getWidth() > page.getWidth() / 2 &&
                    FloatUtils.feq(marginBottomArea.getTop(), bottomArea.getBottom(), avgHeight)
                    && overLapOfWidth(bottomArea, marginBottomArea) > 0.9 && bottomArea.horizontallyOverlapRatio(marginBottomArea) > 0.95) {
                bottomArea.merge(marginBottomArea);
                isMerge = true;
            }

            if (!isMerge && marginBottomArea.getHeight() < 1.5 * page.getAvgCharHeight() && footerChunks.size() > 3) {
                Rectangle finalMarginBottomArea = marginBottomArea;
                float finalAvgHeight = avgHeight;
                List<Rectangle> bottomAreas = mergeAreas.stream().filter(re -> (re.horizontallyOverlapRatio(finalMarginBottomArea) > 0.95
                        && re.verticalDistance(finalMarginBottomArea) < finalAvgHeight
                        && re.getBottom() < finalMarginBottomArea.getTop())).collect(Collectors.toList());
                if (bottomAreas.size() == 1 && page.getTextChunks(bottomAreas.get(0)).size() > 3
                        && overLapOfWidth(bottomAreas.get(0), finalMarginBottomArea) > 0.95) {
                    List<TextChunk> mergeBottomChunks = new ArrayList<>(page.getTextChunks(bottomAreas.get(0)));
                    mergeBottomChunks.sort(Comparator.comparing(TextChunk::getBottom).reversed());
                    List<TextChunk> finalMergeBottomChunks = mergeBottomChunks;
                    mergeBottomChunks = mergeBottomChunks.stream()
                            .filter(te -> (te.verticalOverlapRatio(finalMergeBottomChunks.get(0)) > 0.9)).collect(Collectors.toList());
                    if (mergeBottomChunks.size() > 3) {
                        bottomAreas.get(0).merge(marginBottomArea);
                    }
                }
            }
        }

        List<TextChunk> headerChunks = marginChunks.stream().filter(te -> (te.getPaginationType() == PaginationType.HEADER)).collect(Collectors.toList());
        if (!headerChunks.isEmpty()) {
            float left = headerChunks.stream().min(Comparator.comparing(TextChunk::getLeft)).get().getLeft();
            float top = headerChunks.stream().min(Comparator.comparing(TextChunk::getTop)).get().getTop();
            float right = headerChunks.stream().max(Comparator.comparing(TextChunk::getRight)).get().getRight();
            float bottom = headerChunks.stream().max(Comparator.comparing(TextChunk::getBottom)).get().getBottom();
            marginTopArea = new Rectangle(left, top, right - left, bottom - top);
        }
        if (marginTopArea != null) {
            mergeAreas.sort(Comparator.comparing(Rectangle::getTop));
            Rectangle topArea = mergeAreas.get(0);
            float avgHeight;
            avgHeight = (float)headerChunks.get(headerChunks.size() - 1).getHeight();
            List<TextChunk> mergeAreaChunks = page.getTextChunks(topArea);
            if (!mergeAreaChunks.isEmpty()) {
                float tmpAvgHeight = mergeAreaChunks.get(0).getAvgCharHeight();
                avgHeight = tmpAvgHeight < avgHeight ? avgHeight : tmpAvgHeight;
            }
            if (topArea.getWidth() > page.getWidth() / 2 && page.getPageNumber() != 1
                    && FloatUtils.feq(marginTopArea.getBottom(), topArea.getTop(), avgHeight)
                    && overLapOfWidth(topArea, marginTopArea) > 0.9 && topArea.horizontallyOverlapRatio(marginTopArea) > 0.95) {
                topArea.merge(marginTopArea);
            }
        }
    }

    private static List<Rectangle> clusterSmallAreasMergeToTable(ContentGroupPage page, List<Rectangle> textAreas) {
        if (textAreas.isEmpty()) {
            return new ArrayList<>();
        }

        textAreas.sort(Comparator.comparing(Rectangle::getArea).reversed());
        List<Rectangle> targetRects = new ArrayList<>();
        boolean isMerge = false;
        for (Rectangle tmpArea : textAreas) {
            if (tmpArea.isDeleted()) {
                continue;
            }
            // 上方所有的区域
            do {
                //TableDebugUtils.writeCells(page, Arrays.asList(tmpArea), "xxxtmpArea");
                List<Rectangle> upRects = textAreas.stream().filter(r -> (r.getBottom() <= tmpArea.getTop()
                        && r.isHorizontallyOverlap(tmpArea) && !r.isDeleted())).collect(Collectors.toList());
                List<Rectangle> mergeRects = new ArrayList<>();
                for (Rectangle upRect : upRects) {
                    if (!hasGapAreas(page, tmpArea, upRect, textAreas) && tmpArea.horizontallyOverlapRatio(upRect) > 0.9
                            && tmpArea.verticalDistance(upRect) < 2 * getAvgCharHeight(page, tmpArea, upRect)) {
                        mergeRects.add(upRect);
                    }
                }
                //TableDebugUtils.writeCells(page, mergeRects, "xxxmergeRects");
                if (mergeRects.size() >= 2 && (isGapRectsOfTables(page, tmpArea, mergeRects,
                        textAreas.stream().filter(re -> (!re.isDeleted())).collect(Collectors.toList()))
                        || isTableGroupRects(page, tmpArea, mergeRects))) {
                    // 可能为页脚
                    Rectangle bottomArea = textAreas.stream().max(Comparator.comparing(Rectangle::getBottom)).get();
                    if (bottomArea.overlapRatio(tmpArea) > 0.95 && tmpArea.getHeight() < 1.5 * page.getAvgCharHeight()
                            && mergeRects.stream().anyMatch(r -> (r.getHeight() > 5 * tmpArea.getHeight()))) {
                        isMerge = false;
                    } else {
                        for (Rectangle mergeRect : mergeRects) {
                            tmpArea.merge(mergeRect);
                            mergeRect.markDeleted();
                        }
                        isMerge = true;
                    }
                } else {
                    if (mergeRects.size() == 1) {
                        if ((isHaveHorizontalSameShadows(page, tmpArea, mergeRects.get(0),
                                textAreas.stream().filter(re -> (!re.isDeleted())).collect(Collectors.toList()))
                                && !isHaveAllChartAreas(page, tmpArea, mergeRects.get(0)))
                                || (upRects.size() == 2 && overLapOfWidth(upRects.get(0), tmpArea) < 0.3
                                && getSelfRatioOfHeight(page, upRects.get(0)) > 0.4 && getSelfRatioOfHeight(page, upRects.get(1)) > 0.4
                                && overLapOfWidth(upRects.get(1), tmpArea) < 0.3 && overLapOfHeight(upRects.get(0), tmpArea) < 0.2
                                && overLapOfHeight(upRects.get(1), tmpArea) < 0.2 && upRects.get(1).verticalOverlapRatio(upRects.get(1)) > 0.9
                                && (upRects.get(0).horizontalDistance(upRects.get(1)) > upRects.get(0).getWidth()
                                && upRects.get(0).horizontalDistance(upRects.get(1)) > upRects.get(1).getWidth()))) {
                            tmpArea.merge(mergeRects.get(0));
                            mergeRects.get(0).markDeleted();
                            isMerge = true;
                        } else {
                            isMerge = false;
                        }
                    } else {
                        isMerge = false;
                    }
                }
            } while (isMerge);
        }
        textAreas.removeIf(Rectangle::isDeleted);
        targetRects.addAll(textAreas);
        return targetRects;
    }

    private static double getSelfRatioOfHeight(ContentGroupPage page, Rectangle mainArea) {

        List<TextChunk> chunks = page.getTextChunks(mainArea);
        double ratio = 1.f;

        if (!chunks.isEmpty()) {
            TextChunk maxChunk = chunks.stream().max(Comparator.comparing(TextChunk::getMaxFontSize)).get();
            ratio = maxChunk.getHeight() / mainArea.getHeight();
        }

        return ratio;
    }

    private static boolean isSignalChartAreas(ContentGroupPage page, Rectangle baseArea) {

        List<TextChunk> baseChunks = new ArrayList<>(page.getTextChunks(baseArea));

        if (!baseChunks.isEmpty() ) {
            baseChunks.sort(Comparator.comparing(TextChunk::getTop));

            List<TextChunk> baseShadowChunks = baseChunks.stream().filter(te -> (te.verticalOverlapRatio(baseChunks.get(0)) > 0.5)).collect(Collectors.toList());
            baseShadowChunks.sort(Comparator.comparing(TextChunk::getLeft));
            String baseString = "";
            for (TextChunk tmpChunk0 : baseShadowChunks) {
                baseString += tmpChunk0.getText();
            }

            if ((baseString != "" && CHART_SERIAL0.matcher(baseString.trim().toLowerCase()).matches()
                    || CHART_SERIAL1.matcher(baseString.trim().toLowerCase()).matches()
                    || CHART_SERIAL2.matcher(baseString.trim().toLowerCase()).matches()
                    || TABLE_SERIAL.matcher(baseString.trim().toLowerCase()).matches())) {
                return true;
            }

            if ((baseArea.getHeight() > 5 * page.getAvgCharHeight())) {
                if ((baseString != "" && CHART_SERIAL0.matcher(baseString.trim().toLowerCase()).matches()
                        || CHART_SERIAL1.matcher(baseString.trim().toLowerCase()).matches()
                        || CHART_SERIAL2.matcher(baseString.trim().toLowerCase()).matches()
                        || TABLE_SERIAL.matcher(baseString.trim().toLowerCase()).matches())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isHaveAllChartAreas(ContentGroupPage page, Rectangle baseArea, Rectangle otherArea) {

        if (baseArea.overlapRatio(otherArea) > 0) {
            return false;
        }

        List<TextChunk> baseChunks = new ArrayList<>(page.getTextChunks(baseArea));
        List<TextChunk> otherChunks = new ArrayList<>(page.getTextChunks(otherArea));
        if (!baseChunks.isEmpty() && !otherChunks.isEmpty()) {
            baseChunks.sort(Comparator.comparing(TextChunk::getTop));
            otherChunks.sort(Comparator.comparing(TextChunk::getTop));
            List<TextChunk> baseShadowChunks = baseChunks.stream().filter(te -> (te.verticalOverlapRatio(baseChunks.get(0)) > 0.5)).collect(Collectors.toList());
            List<TextChunk> otherShadowChunks = otherChunks.stream().filter(te -> (te.verticalOverlapRatio(otherChunks.get(0)) > 0.5)).collect(Collectors.toList());
            baseShadowChunks.sort(Comparator.comparing(TextChunk::getLeft));
            otherShadowChunks.sort(Comparator.comparing(TextChunk::getLeft));
            String baseString = "", otherString = "";
            for (TextChunk tmpChunk0 : baseShadowChunks) {
                baseString += tmpChunk0.getText();
            }
            for (TextChunk tmpChunk1 : otherShadowChunks) {
                otherString += tmpChunk1.getText();
            }
            if ((baseString != "" && CHART_SERIAL0.matcher(baseString.trim().toLowerCase()).matches()
                    || CHART_SERIAL1.matcher(baseString.trim().toLowerCase()).matches()
                    || CHART_SERIAL2.matcher(baseString.trim().toLowerCase()).matches()
                    || TABLE_SERIAL.matcher(baseString.trim().toLowerCase()).matches())
                    && (otherString != "" && CHART_SERIAL0.matcher(otherString.trim().toLowerCase()).matches()
                    || CHART_SERIAL1.matcher(otherString.trim().toLowerCase()).matches()
                    || CHART_SERIAL2.matcher(otherString.trim().toLowerCase()).matches()
                    || TABLE_SERIAL.matcher(otherString.trim().toLowerCase()).matches())) {
                return true;
            }

            if (baseArea.verticalOverlapRatio(otherArea) > 0.8 && overLapOfWidth(baseArea, otherArea) > 0.8
                    && (baseArea.getHeight() > 5 * page.getAvgCharHeight() || otherArea.getHeight() > 5 * page.getAvgCharHeight())) {
                if ((baseString != "" && CHART_SERIAL0.matcher(baseString.trim().toLowerCase()).matches()
                        || CHART_SERIAL1.matcher(baseString.trim().toLowerCase()).matches()
                        || CHART_SERIAL2.matcher(baseString.trim().toLowerCase()).matches()
                        || TABLE_SERIAL.matcher(baseString.trim().toLowerCase()).matches())
                        || (otherString != "" && CHART_SERIAL0.matcher(otherString.trim().toLowerCase()).matches()
                        || CHART_SERIAL1.matcher(otherString.trim().toLowerCase()).matches()
                        || CHART_SERIAL2.matcher(otherString.trim().toLowerCase()).matches()
                        || TABLE_SERIAL.matcher(otherString.trim().toLowerCase()).matches())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isGapRectsOfTables(ContentGroupPage page, Rectangle mainArea, List<Rectangle> mergeRects, List<Rectangle> allAreas) {

        if (mergeRects.size() != 2 || mergeRects.get(0).getHeight() >= 3 * page.getAvgCharHeight()
                || mergeRects.get(1).getHeight() > 3 * page.getAvgCharHeight()) {
            return false;
        }

        Rectangle topRect = (mergeRects.get(0).getTop() < mergeRects.get(1).getTop()) ? mergeRects.get(0) : mergeRects.get(1);

        if (topRect.getTop() > mainArea.getTop()) {
            return false;
        }

        // up rect
        List<Rectangle> topMainAreas = allAreas.stream().filter(re -> (re.horizontallyOverlapRatio(mergeRects.get(0)) > 0.95
                && re.horizontallyOverlapRatio(mergeRects.get(1)) > 0.95 && topRect.getTop() > re.getBottom()
                && !hasGapAreas(page, re, mergeRects.get(0), allAreas) && !hasGapAreas(page, re, mergeRects.get(1), allAreas)
                && re.verticalDistance(mergeRects.get(0)) < 2 * getAvgCharHeight(page, re, mergeRects.get(0))
                && re.verticalDistance(mergeRects.get(1)) < 2 * getAvgCharHeight(page, re, mergeRects.get(1)) )).collect(Collectors.toList());

        if (topMainAreas.size() == 1 && isDiscreteRects(page, Arrays.asList(mainArea), false)
                && isDiscreteRects(page, topMainAreas, true)) {
            mergeRects.sort(Comparator.comparing(Rectangle::getLeft));
            if (topMainAreas.get(0).horizontallyOverlapRatio(mainArea) > 0.9
                    && mergeRects.get(0).getLeft() > mainArea.getLeft() && mergeRects.get(0).getLeft() > topMainAreas.get(0).getLeft()
                    && mergeRects.get(1).getRight() < mainArea.getRight() && mergeRects.get(1).getRight() < topMainAreas.get(0).getRight()
                    && FloatUtils.feq(mergeRects.get(0).getBottom(), mergeRects.get(1).getBottom(), 4.f)) {
                return true;
            }
        }

        return false;
    }

    private static boolean isTableGroupRects(ContentGroupPage page, Rectangle baseArea, List<Rectangle> mergeAreas) {

        if (mergeAreas.size() < 2) {
            return false;
        }

        if (mergeAreas.size() == 2) {
            if (mergeAreas.get(0).horizontallyOverlapRatio(baseArea) > 0.9 && mergeAreas.get(1).horizontallyOverlapRatio(baseArea) > 0.9
                    && mergeAreas.get(0).horizontalDistance(mergeAreas.get(1)) > 2 * getAvgCharWidth(page, mergeAreas.get(0), mergeAreas.get(1))
                    && FloatUtils.feq(mergeAreas.get(0).getBottom(), mergeAreas.get(1).getBottom(), 4.f)) {
                boolean isFristMin = mergeAreas.get(0).getHeight() < mergeAreas.get(1).getHeight();
                Rectangle minRect = isFristMin ? mergeAreas.get(0) : mergeAreas.get(1);
                Rectangle maxRect = isFristMin ? mergeAreas.get(1) : mergeAreas.get(0);
                boolean discreteRects0 = isDiscreteRects(page, Arrays.asList(minRect), true);
                boolean discreteRects1 = isDiscreteRects(page, Arrays.asList(maxRect), true);
                if ((overLapOfWidth(minRect, maxRect) < 0.5
                        || mergeAreas.get(0).horizontalDistance(mergeAreas.get(1)) > 10 * getAvgCharWidth(page, mergeAreas.get(0), mergeAreas.get(1)))
                        && (discreteRects0 || discreteRects1)) {
                    return true;
                }
                mergeAreas.sort(Comparator.comparing(Rectangle::getLeft));
                boolean isDiscreteBase = isDiscreteRects(page, Arrays.asList(baseArea), false);
                if (isDiscreteBase && overLapOfWidth(minRect, maxRect) > 0.8
                        && (mergeAreas.get(1).getLeft() < (baseArea.getLeft() + baseArea.getWidth() / 2)
                        && mergeAreas.get(1).getRight() < (baseArea.getLeft() + (0.7 * (float)baseArea.getWidth())))) {
                    return true;
                }

                boolean isEnglish = StringUtils.startsWithIgnoreCase(page.getLanguage(), "en");
                if (!isEnglish && isDiscreteBase && (mergeAreas.get(0).getLeft() - baseArea.getLeft() > 10 * getAvgCharWidth(page, baseArea, mergeAreas.get(0)))
                        && (baseArea.getRight() - mergeAreas.get(1).getRight() > 5 * getAvgCharWidth(page, baseArea, mergeAreas.get(0)))
                        && mergeAreas.get(0).getHeight() < 3 * page.getAvgCharHeight()
                        && mergeAreas.get(1).getHeight() < 3 * page.getAvgCharHeight()) {
                    return true;
                }
            } else {
                if (mergeAreas.get(0).horizontallyOverlapRatio(baseArea) > 0.9 && mergeAreas.get(1).horizontallyOverlapRatio(baseArea) > 0.9
                        && mergeAreas.get(0).getWidth() < baseArea.getWidth() / 4 && mergeAreas.get(1).getWidth() < baseArea.getWidth() / 4
                        && mergeAreas.get(0).getHeight() < 3 * page.getAvgCharHeight() && mergeAreas.get(1).getHeight() < 3 * page.getAvgCharHeight()
                        && mergeAreas.get(0).horizontalDistance(mergeAreas.get(1)) > 5 * getAvgCharWidth(page, mergeAreas.get(0), mergeAreas.get(1))
                        && isDiscreteRects(page, Arrays.asList(baseArea), false)) {
                    return true;
                }
            }
        } else {
            mergeAreas.sort(Comparator.comparing(Rectangle::getLeft));
            List<Rectangle> clusRects = mergeAreas.stream().filter(re -> (re.isVerticallyOverlap(mergeAreas.get(0)))).collect(Collectors.toList());
            Rectangle base = mergeAreas.get(0);
            int sameCnt = 0;
            for (int i = 1; i < mergeAreas.size(); i++) {
                Rectangle other = mergeAreas.get(i);
                if (FloatUtils.feq(base.getBottom(), other.getBottom(), 4.f)
                        && mergeAreas.stream().filter(re -> (re.isVerticallyOverlap(other))).collect(Collectors.toList()).size() == clusRects.size()) {
                    sameCnt++;
                    base = other;
                } else {
                    break;
                }
            }
            if (sameCnt == mergeAreas.size() - 1) {
                return true;
            }
        }
        return false;
    }

    private static boolean isMultiLineOneColumnChunks(ContentGroupPage page, Rectangle textArea) {
        boolean result = false;
        List<TextChunk> listTextChunk = page.getTextChunks(textArea);
        if (!listTextChunk.isEmpty()) {
            List<TextChunk> textChunks = new ArrayList<>();
            for (TextChunk chunk : listTextChunk) {
                if(!chunk.isBlank() && chunk.getWidth() < 5 * page.getAvgCharWidth()
                        && NUMBER.matcher(chunk.getText()).matches()) {
                    textChunks.add(chunk);
                }
            }
            if (!textChunks.isEmpty()) {
                int size = textChunks.size() - 1;
                TextChunk base = textChunks.get(0);
                for (int i = 1; i < textChunks.size(); i++) {
                    TextChunk other = textChunks.get(i);
                    if (base.isHorizontallyOverlap(other)) {
                        size--;
                    }
                }
                if ((0 == size) && (textChunks.size() >= 3)) {
                    result = true;
                }
            }
        }
        return result;
    }

    private static void filterMarginText(ContentGroupPage page, List<Rectangle> allTextChunkRects) {

        if (allTextChunkRects.isEmpty()) {
            return;
        }

        float baseHeight, baseWidth;
        if (page.getHeight() < 6 * page.getAvgCharHeight()) {
            baseHeight = 40.f;
        } else {
            baseHeight = 6 * page.getAvgCharHeight();
        }

        if (page.getWidth() < 5 * page.getAvgCharWidth()) {
            baseWidth = 30.f;
        } else {
            baseWidth = 5 * page.getAvgCharWidth();
        }

        Rectangle marginLeft = new Rectangle(page.getLeft(), page.getTop() + baseHeight / 2,
                baseWidth,  page.getBottom() - baseHeight);
        Rectangle marginRight = new Rectangle(page.getRight() - baseWidth, page.getTop() + baseHeight / 2,
                baseWidth,  page.getBottom() - baseHeight);

        List<Rectangle> marginRects = allTextChunkRects.stream().filter(re -> (re.overlapRatio(marginLeft) > 0
                || re.overlapRatio(marginRight) > 0)).collect(Collectors.toList());
        if (marginRects.isEmpty()) {
            return;
        }
        List<Rectangle> removeRects = new ArrayList<>();
        for (Rectangle textRect : marginRects) {
            if (page.getText(textRect).isEmpty()) {
                removeRects.add(textRect);
                continue;
            }
            if (textRect.getWidth() > 2 * textRect.getHeight() || textRect.getWidth() > 4 * page.getAvgCharWidth()) {
                continue;
            }
            List<TextChunk> texts = new ArrayList<>(page.getTextChunks(textRect));
            Rectangle gapLeftRect = new Rectangle(page.getLeft(), textRect.getTop(),
                    textRect.getLeft() - page.getLeft(), textRect.getHeight());
            gapLeftRect.rectReduce(1,1, page.getWidth(), page.getHeight());

            boolean isRemoveFlag = false;
            if (page.getText(gapLeftRect).isEmpty() && !texts.isEmpty()) {
                texts.sort(Comparator.comparing(Rectangle::getLeft));
                if (NUMBER_SERIAL.matcher(texts.get(0).getText()).matches() && texts.size() == 1
                        && (texts.get(0).isPageHeader() || texts.get(0).isPageLeftOrRightWing())) {
                    removeRects.add(textRect);
                    isRemoveFlag = true;
                }

                int count = 0;
                if (!isRemoveFlag && texts.size() > 1) {
                    for (TextChunk chunk : texts) {
                        if (texts.stream().anyMatch(te -> (te.getDirection() == TextDirection.VERTICAL_UP || te.getDirection() == TextDirection.VERTICAL_DOWN))) {
                            removeRects.add(textRect);
                            break;
                        }
                        if (chunk.getElements().size() == 1) {
                            count++;
                        }
                        if (chunk.getElements().size() == 2
                                && chunk.getElements().stream().anyMatch(te -> (te.isWhitespace()))) {
                            count++;
                        }
                    }
                    if (count == texts.size()) {
                        removeRects.add(textRect);
                    }
                }
                isRemoveFlag = false;
            }

            Rectangle gapRightRect = new Rectangle(textRect.getRight(), textRect.getTop(),
                    page.getRight() - textRect.getRight(), textRect.getHeight());
            gapRightRect.rectReduce(1,1, page.getWidth(), page.getHeight());
            if (page.getText(gapRightRect).isEmpty() && !texts.isEmpty()) {
                texts.sort(Comparator.comparing(Rectangle::getRight).reversed());
                if (NUMBER_SERIAL.matcher(texts.get(0).getText()).matches() && texts.size() == 1
                        && (texts.get(0).isPageHeader() || texts.get(0).isPageLeftOrRightWing())) {
                    removeRects.add(textRect);
                    isRemoveFlag = true;
                }

                int count = 0;
                if (!isRemoveFlag && texts.size() > 1) {
                    for (TextChunk chunk : texts) {
                        if (chunk.getElements().size() == 1) {
                            count++;
                        }
                        if (chunk.getElements().size() == 2
                                && chunk.getElements().stream().anyMatch(te -> (te.isWhitespace()))) {
                            count++;
                        }
                    }
                    if (count == texts.size()) {
                        removeRects.add(textRect);
                    }
                }
            }
        }
        allTextChunkRects.removeAll(removeRects);
    }

    private static List<Rectangle> mergeSomeMixArea(ContentGroupPage page, List<Rectangle> textRectangles, List<Rectangle> charts) {

        if (textRectangles.isEmpty()) {
            return new ArrayList<>();
        }

        //TableDebugUtils.writeCells(page, textRectangles, "finalTextRectangles");

        List<Rectangle> textAreas = new ArrayList<>(textRectangles);
        filterMarginText(page, textAreas);
        textAreas = clusterSmallAreasMergeToTable(page, textAreas);
        textAreas.sort(Comparator.comparing(Rectangle::getArea).reversed());

        List<Rectangle> mergeAreas = new ArrayList<>();
        boolean mergeFlag;
        for (Rectangle tmpArea : textAreas) {
            if ((!mergeAreas.isEmpty() && mergeAreas.stream().anyMatch(r -> (r.overlapRatio(tmpArea) > 0)))) {
                continue;
            }
            do {
                mergeFlag = false;
                List<Rectangle> verticalRects = textAreas.stream().filter(r -> (r.isVerticallyOverlap(tmpArea)
                        && r.overlapRatio(tmpArea) != 1.0) && !isSeparatedByVerticalTextChunks(page, tmpArea, r)).collect(Collectors.toList());
                verticalRects.removeIf(r -> (r.overlapRatio(tmpArea) > 0.5 || tmpArea.nearlyContains(r, 1)));
                List<Ruling> hLines = page.getHorizontalRulings();

                //TableDebugUtils.writeCells(page, verticalRects, "xxxverticalRects");
                //TableDebugUtils.writeCells(page, Arrays.asList(tmpArea), "xxxtmpArea");
                //TableDebugUtils.writeCells(page, mergeAreas, "xxxmergeAreas");

                for (Rectangle vRect : verticalRects) {
                    if ((!hLines.isEmpty() && hLines.stream().anyMatch(r -> (vRect.intersectsLine(r) && tmpArea.intersectsLine(r))) && page.getPageNumber() != 1) ||
                            (verticalRects.size() == 1 && vRect.getWidth() / tmpArea.getWidth() < 0.2 && !isSeparatedByVlineBetweenRects(page, tmpArea, vRect)
                                    && tmpArea.horizontalDistance(vRect) < page.getAvgCharWidth()
                                    && page.getPageFillAreaGroup().stream()
                                    .anyMatch(r -> (r.getGroupRectArea().overlapRatio(tmpArea) > 0.5 || tmpArea.nearlyContains(r.getGroupRectArea(), 1))))) {
                        if (isHaveAllChartAreas(page, tmpArea, vRect)) {
                            mergeFlag = false;
                            break;
                        } else {
                            tmpArea.merge(vRect);
                            mergeFlag = true;
                        }
                    } else {
                        mergeFlag = false;
                    }
                }
            } while (mergeFlag);
            mergeAreas.add(tmpArea);
        }
        processRepeateAndContainedAreas(page, mergeAreas);

        mergeAreas.sort(Comparator.comparing(Rectangle::getArea).reversed());
        List<Rectangle> targetRects = new ArrayList<>();
        for (Rectangle tmpArea : mergeAreas) {
            if ((!targetRects.isEmpty() && targetRects.stream().anyMatch(r -> (r.overlapRatio(tmpArea) > 0)))) {
                continue;
            }
            do {
                mergeFlag = false;
                List<Rectangle> horzonitalRects = textAreas.stream().filter(r -> (r.isHorizontallyOverlap(tmpArea)
                        && !isSeparatedByHorizontalTextChunks(page, tmpArea, r)
                        && r.overlapRatio(tmpArea) != 1.0) && !hasGapAreas(page, tmpArea, r, textRectangles)).collect(Collectors.toList());

                horzonitalRects.removeIf(r -> (r.overlapRatio(tmpArea) > 0.5));
                List<Ruling> vLines = page.getVerticalRulings();
                for (Rectangle hRect : horzonitalRects) {
                    if (/*(!vLines.isEmpty() && vLines.stream().anyMatch(r -> (hRect.intersectsLine(r) && tmpArea.intersectsLine(r)))) || */
                            (horzonitalRects.size() == 1 && overLapOfWidth(hRect, tmpArea) > 0.95
                                    && hRect.getHeight() / tmpArea.getHeight() < 0.2 && !isSeparatedByHline(page, tmpArea, hRect)
                            && tmpArea.verticalDistance(hRect) < page.getAvgCharHeight()
                            && page.getPageFillAreaGroup().stream()
                            .anyMatch(r -> (r.getGroupRectArea().overlapRatio(tmpArea) > 0.5 || tmpArea.nearlyContains(r.getGroupRectArea()))))){
                        tmpArea.merge(hRect);
                        mergeFlag = true;
                        break;
                    } else {
                        mergeFlag = false;
                    }
                }

            } while (mergeFlag);
            targetRects.add(tmpArea);
        }
        processRepeateAndContainedAreas(page, targetRects);

        if (!page.getContentGroup().hasTag(Tags.IS_CHART_DETECT_IN_TABLE)) {
            List<TableRegion> chartRegions = page.getChartRegions("LayoutAnalysisAlgorithm");
            List<Rectangle> targetMergeAreas = new ArrayList<>();
            List<Rectangle> removeRects = new ArrayList<>();
            if (!chartRegions.isEmpty() && targetRects.size() > 1) {
                targetRects.sort(Comparator.comparing(Rectangle::getArea));
                for (TableRegion chart : chartRegions) {
                    if (targetRects.stream().anyMatch(r -> (r.overlapRatio(chart) > 0 && !chart.nearlyContains(r)))) {
                        continue;
                    }

                    List<Rectangle> chartAreas = targetRects.stream().filter(r -> (r.overlapRatio(chart) > 0
                            && chart.nearlyContains(r))).collect(Collectors.toList());
                    if (!chartAreas.isEmpty()) {
                        Rectangle mergeChareArea = new Rectangle(chart);
                        for (Rectangle tmpChareArea : chartAreas) {
                            mergeChareArea.merge(tmpChareArea);
                        }
                        targetMergeAreas.add(mergeChareArea);
                        removeRects.addAll(chartAreas);
                    }
                }
            }
            targetRects.removeAll(removeRects);
            targetRects.addAll(targetMergeAreas);
            if (!charts.isEmpty()) {
                if (targetMergeAreas.isEmpty()) {
                    targetRects.addAll(charts);
                } else {
                    for (Rectangle chart : charts) {
                        if (targetMergeAreas.stream().anyMatch(re -> (chart.nearlyContains(re) || chart.overlapRatio(re) == 0.f))) {
                            targetRects.add(chart);
                        }
                    }
                }
            }

        } else {
            if (!charts.isEmpty()) {
                targetRects = mergeChartByFinalAreas(page, targetRects, charts);
            }
        }
        processRepeateAndContainedAreas(page, targetRects);
        if (targetRects.size() > 1) {
            targetRects = mergeNearbyAreas(page, targetRects);
        }
        return targetRects;
    }

    private static List<Rectangle> mergeChartByFinalAreas(ContentGroupPage page, List<Rectangle> finalAreas, List<Rectangle> chartAreas) {
        if (chartAreas.isEmpty()) {
            return finalAreas;
        }
        chartAreas.sort(Comparator.comparing(Rectangle::getArea).reversed());
        List<Rectangle> removeRects = new ArrayList<>();
        for (Rectangle chartArea : chartAreas) {
            List<Rectangle> upRects = finalAreas.stream().filter(re -> (re.horizontallyOverlapRatio(chartArea) > 0.95
                    && re.getBottom() < chartArea.getTop() && !isSeparatedByVerticalTextChunks(page, re, chartArea))).collect(Collectors.toList());
            if (upRects.size() == 1 && upRects.get(0).verticalDistance(chartArea) < 3 * page.getAvgCharHeight()
                    && (!chartAreas.stream().anyMatch(re -> (re.verticalOverlapRatio(chartArea) > 0.5) && !re.equals(chartArea)))
                    && !finalAreas.stream().anyMatch(re -> (re.verticalOverlapRatio(chartArea) > 0.5)
                    && upRects.get(0).isHorizontallyOverlap(re))) {
                chartArea.merge(upRects.get(0));
                removeRects.add(upRects.get(0));
            }

            List<Rectangle> downRects = finalAreas.stream().filter(re -> (re.horizontallyOverlapRatio(chartArea) > 0.95
                    && re.getTop() > chartArea.getBottom() && !isSeparatedByVerticalTextChunks(page, re, chartArea))).collect(Collectors.toList());
            if (downRects.size() == 1 && downRects.get(0).verticalDistance(chartArea) < 3 * page.getAvgCharHeight()
                    && !chartAreas.stream().anyMatch(re -> (re.verticalOverlapRatio(chartArea) > 0.5 && !re.equals(chartArea)))
                    && !finalAreas.stream().anyMatch(re -> (re.verticalOverlapRatio(chartArea) > 0.5)
                    && downRects.get(0).isHorizontallyOverlap(re))) {
                chartArea.merge(downRects.get(0));
                removeRects.add(downRects.get(0));
            }
        }
        finalAreas.removeAll(removeRects);
        finalAreas.addAll(chartAreas);
        return finalAreas;
    }

    private static List<Rectangle> mergeNearbyAreas(ContentGroupPage page, List<Rectangle> allAreas) {

        if (allAreas.size() < 2) {
            return allAreas;
        }
        List<Rectangle> mergeAreas = new ArrayList<>(allAreas);
        List<Rectangle> removeRects = new ArrayList<>();
        mergeAreas.sort(Comparator.comparing(Rectangle::getTop));
        Rectangle base = mergeAreas.get(0);
        for (int i = 1; i < mergeAreas.size(); i++) {
            Rectangle other = mergeAreas.get(i);
            //TableDebugUtils.writeCells(page, Arrays.asList(base), "xxxbase");
            //TableDebugUtils.writeCells(page, Arrays.asList(other), "xxxother");
            if (base.horizontallyOverlapRatio(other) > 0.8 && base.verticalDistance(other) < 2 * getAvgCharHeight(page, base, other)
                    && !isHaveAllChartAreas(page, base, other)) {
                Rectangle finalBase = base;
                List<Rectangle> baseShadows = mergeAreas.stream().filter(re -> (re.isVerticallyOverlap(finalBase))).collect(Collectors.toList());
                List<Rectangle> otherShadows = mergeAreas.stream().filter(re -> (re.isVerticallyOverlap(other))).collect(Collectors.toList());
                if (baseShadows.size() == otherShadows.size() && baseShadows.size() == 1) {
                    base.merge(other);
                    removeRects.add(other);
                    continue;
                }
                if (isHaveHorizontalSameShadows(page, base, other, allAreas)) {
                    base.merge(other);
                    removeRects.add(other);
                    continue;
                }
            }
            base = other;
        }
        mergeAreas.removeAll(removeRects);

        if (mergeAreas.size() > 1) {
            mergeAreas.sort(Comparator.comparing(Rectangle::getLeft));
            base = mergeAreas.get(0);
            List<Rectangle> candidateAreas = new ArrayList<>();
            candidateAreas.add(base);
            List<Rectangle> targetMergeAreas = new ArrayList<>();
            for (int i = 1; i < mergeAreas.size(); i++) {
                Rectangle other = mergeAreas.get(i);
                if (base.horizontallyOverlapRatio(other) > 0.9 && FloatUtils.feq(base.getLeft(), other.getLeft(), 5.f)
                        && !isHaveAllChartAreas(page, base, other)) {
                    candidateAreas.add(other);
                } else {
                    targetMergeAreas.addAll(processGroupMergeAreas(page, candidateAreas, allAreas));
                    candidateAreas.clear();
                    base = other;
                    candidateAreas.add(base);
                }
            }
            targetMergeAreas.addAll(processGroupMergeAreas(page, candidateAreas, allAreas));
            mergeAreas = targetMergeAreas;
        }

        return mergeAreas;
    }


    private static List<Rectangle> processGroupMergeAreas(ContentGroupPage page, List<Rectangle> candidateMergeAreas, List<Rectangle> allAreas) {

        if (candidateMergeAreas.size() < 2) {
            return candidateMergeAreas;
        }
        List<Rectangle> mergeTargetAreas = new ArrayList<>();
        candidateMergeAreas.sort(Comparator.comparing(Rectangle::getTop));
        Rectangle base = candidateMergeAreas.get(0);
        Rectangle mergeArea = new Rectangle(base);
        for (int i = 1; i < candidateMergeAreas.size(); i++) {
            Rectangle other = candidateMergeAreas.get(i);
            if (mergeArea.verticalDistance(other) < 2 * getAvgCharHeight(page, base, other)) {
                if (isHaveHorizontalSameShadows(page, mergeArea, other, allAreas)
                        || (FloatUtils.feq(mergeArea.getRight(), other.getRight()) && mergeArea.verticalDistance(other) < 5.f)) {
                    if (!isHaveAllChartAreas(page, mergeArea, other)) {
                        mergeArea.merge(other);
                    } else {
                        mergeTargetAreas.add(mergeArea);
                        mergeArea = new Rectangle(other);
                    }
                } else {
                    mergeTargetAreas.add(mergeArea);
                    mergeArea = new Rectangle(other);
                }
            } else {
                mergeTargetAreas.add(mergeArea);
                mergeArea = new Rectangle(other);
            }
        }
        mergeTargetAreas.add(mergeArea);
        return mergeTargetAreas;
    }

    // 对于空间结构和流顺序不一致的情况合并策略
    private static boolean isCanMergeUpByStreamOfTextChunks(ContentGroupPage page, Rectangle mainArea, Rectangle mergeArea,
                                                          List<Rectangle> mergeAreas, List<GroupInfo> groupInfos) {
        if (mergeAreas.size() < 3 && !mergeAreas.isEmpty()) {
            return true;
        }

        if (mainArea.isHorizontallyOverlap(mergeArea) && mainArea.getTop() > mergeArea.getTop()) {

            List<Rectangle> verticalRects = mergeAreas.stream().filter(r -> (r.isVerticallyOverlap(mergeArea) && !r.isHorizontallyOverlap(mainArea)
                    && !r.isVerticallyOverlap(mainArea) && (!FloatUtils.feq(r.overlapRatio(mergeArea), 1.0, 0.1)))).collect(Collectors.toList());
            List<Rectangle> shadowRects = new ArrayList<>();
            if (!verticalRects.isEmpty()) {
                for (Rectangle verticalRect : verticalRects) {
                    if (!hasAreaBetweenTwoSignalChunkRects(page, mainArea, verticalRect, mergeAreas)) {
                        shadowRects.add(verticalRect);
                    }
                }
            }
            if (shadowRects.size() == 1) {
                Rectangle shadowRect = shadowRects.get(0);
                GroupInfo mainInfo = null, shadowInfo = null;
                float lastShadowRatio = 0.f, lastMainRatio = 0.f;
                for (GroupInfo info : groupInfos) {
                    float shadowRatio = info.groupRect.overlapRatio(shadowRect);
                    float mainRatio = info.groupRect.overlapRatio(mainArea);
                    if (shadowRatio > lastShadowRatio) {
                        lastShadowRatio = shadowRatio;
                        shadowInfo = info;
                    }

                    if (mainRatio > lastMainRatio) {
                        lastMainRatio = mainRatio;
                        mainInfo = info;
                    }
                }

                if (null == mainInfo || shadowInfo == null) {
                    return true;
                }

                /*if (page.getTextChunks(mergeArea).size() == 1 && mainArea.getHeight() / mergeArea.getHeight() > 20
                        && mainArea.getWidth() > mergeArea.getWidth() && mainArea.verticalDistance(mergeArea) < 2 * page.getAvgCharHeight()
                        && mergeArea.getHeight() < 2 * page.getAvgCharHeight()) {
                    return true;
                }*/

                if (overLapOfWidth(mainArea, mergeArea) > 0.95 && mainArea.verticalDistance(mergeArea) < getAvgCharHeight(page, mainArea, mergeArea)
                        && FloatUtils.feq(mainArea.getLeft(), mergeArea.getLeft(), page.getAvgCharWidth())
                        && FloatUtils.feq(mainArea.getRight(), mergeArea.getRight(), page.getAvgCharWidth())
                        && !isSeparatedByHline(page, mainArea, mergeArea)) {
                    return true;
                }

                if (mainInfo.groupIndex > shadowInfo.groupIndex) {
                    return false;
                }
            }

        }
        return true;
    }

    // 对于空间结构和流顺序不一致的情况合并策略
    private static boolean isCanMergeDownByStreamOfTextChunks(ContentGroupPage page, Rectangle mainArea, Rectangle mergeArea,
                                                          List<Rectangle> mergeAreas, List<GroupInfo> groupInfos) {
        if (mergeAreas.size() < 3 && !mergeAreas.isEmpty()) {
            return true;
        }

        if (mainArea.isHorizontallyOverlap(mergeArea) && mainArea.getTop() < mergeArea.getTop()) {

            List<Rectangle> verticalRects = mergeAreas.stream().filter(r -> (r.isVerticallyOverlap(mainArea) && !r.isHorizontallyOverlap(mergeArea)
                    && !r.isVerticallyOverlap(mergeArea) && (!FloatUtils.feq(r.overlapRatio(mainArea), 1.0, 0.1)))).collect(Collectors.toList());
            List<Rectangle> shadowRects = new ArrayList<>();
            if (!verticalRects.isEmpty()) {
                for (Rectangle verticalRect : verticalRects) {
                    if (!hasAreaBetweenTwoSignalChunkRects(page, mergeArea, verticalRect, mergeAreas)) {
                        shadowRects.add(verticalRect);
                    }
                }
            }
            if (shadowRects.size() == 1) {
                Rectangle shadowRect = shadowRects.get(0);
                GroupInfo mergeInfo = null, shadowInfo = null;
                float lastShadowRatio = 0.f, lastMergeRatio = 0.f;
                for (GroupInfo info : groupInfos) {
                    float shadowRatio = info.groupRect.overlapRatio(shadowRect);
                    float mergeRatio = info.groupRect.overlapRatio(mergeArea);
                    if (shadowRatio > lastShadowRatio) {
                        lastShadowRatio = shadowRatio;
                        shadowInfo = info;
                    }

                    if (mergeRatio > lastMergeRatio) {
                        lastMergeRatio = mergeRatio;
                        mergeInfo = info;
                    }
                }

                if (null == mergeInfo || shadowInfo == null) {
                    return true;
                }

                if ((FloatUtils.feq(mainArea.getLeft(), mergeArea.getLeft(), 2 * getAvgCharWidth(page, mainArea, mergeArea))
                        || FloatUtils.feq(mainArea.getRight(), mergeArea.getRight(), 2 * getAvgCharWidth(page, mainArea, mergeArea)))
                        && isSeparatedByHline(page, mainArea, mergeArea)
                        && mainArea.verticalDistance(mergeArea) < 2 * getAvgCharHeight(page, mainArea, mergeArea)) {
                    boolean isMainAtTop = mainArea.getTop() < mergeArea.getTop();
                    Rectangle topRect = isMainAtTop ? mainArea : mergeArea;
                    Rectangle downRect = isMainAtTop ? mergeArea : mainArea;
                    Rectangle maxRect = (mainArea.getWidth() > mergeArea.getWidth()) ? mainArea : mergeArea;
                    List<Ruling> hLines = page.getHorizontalRulings();
                    Ruling mainLine = new Ruling(mainArea.getBottom(), mainArea.getLeft(), (float)mainArea.getWidth(), 0);
                    Ruling mergeLine = new Ruling(mergeArea.getTop(), mergeArea.getLeft(), (float)mergeArea.getWidth(), 0);
                    hLines = hLines.stream().filter(hr -> (hr.horizontallyOverlapRatio(mainLine) > 0.9
                            && hr.horizontallyOverlapRatio(mergeLine) > 0.9)
                            && overLapOfWidth(new Rectangle(hr.getLeft(), hr.getTop(), hr.getWidth(), hr.getHeight()), maxRect) > 0.9
                            && (hr.getCenterY() > (topRect.getBottom() - 2.5) && hr.getCenterY() < (downRect.getTop() + 2.5))).collect(Collectors.toList());

                    if (hLines.size() == 1 && isHaveHorizontalSameShadows(page, mainArea, mergeArea, mergeAreas)) {
                        return true;
                    }
                }

                if (groupInfos.indexOf(mergeInfo) > groupInfos.indexOf(shadowInfo)) {
                    return false;
                }
            }

        }
        return true;
    }

    private static List<TextChunk> filterInvalidTextChunks(ContentGroupPage page, List<TextChunk> allChunks) {

        if (allChunks.isEmpty()) {
            return new ArrayList<>();
        }
        List<TextChunk> textChunks = new ArrayList<>(allChunks);
        textChunks.removeIf(te -> (te.getElements().stream().anyMatch(t -> (t.getColor().getRGB() == -1))
                && (te.getDirection() == TextDirection.VERTICAL_UP || te.getDirection() == TextDirection.VERTICAL_DOWN)));

        if (!textChunks.isEmpty()) {
            textChunks.removeIf(text -> (text.isPageLeftOrRightWing() && !(text.getWidth() / text.getHeight() > 2 && text.getElements().size() > 3)));
        }

        if (textChunks.isEmpty()) {
            return new ArrayList<>();
        }

        List<TextChunk> removeTexts = new ArrayList<>();
        TextChunk maxLeftChunk = textChunks.stream().max(Comparator.comparing(TextChunk::getLeft)).get();
        TextChunk minLeftChunk = textChunks.stream().min(Comparator.comparing(TextChunk::getLeft)).get();

        TextChunk maxBottomChunk = textChunks.stream().max(Comparator.comparing(TextChunk::getBottom)).get();
        TextChunk minTopChunk = textChunks.stream().min(Comparator.comparing(TextChunk::getTop)).get();

        List<Rectangle> allBottomChunks = textChunks.stream().filter(re -> (re.verticalOverlapRatio(maxBottomChunk) > 0.5)).collect(Collectors.toList());
        List<Rectangle> allTopChunks = textChunks.stream().filter(re -> (re.verticalOverlapRatio(minTopChunk) > 0.5)).collect(Collectors.toList());

        if (minLeftChunk.getWidth() < minLeftChunk.getHeight() && minLeftChunk.getWidth() < 2.5 * page.getAvgCharWidth()) {
            List<Rectangle> minLeftShadowRects = textChunks.stream().filter(re -> (re.isHorizontallyOverlap(minLeftChunk)
                    && !re.equals(minLeftChunk))).collect(Collectors.toList());
            if (minLeftShadowRects.isEmpty()) {
                removeTexts.add(minLeftChunk);
            } else {
                minLeftShadowRects.sort(Comparator.comparing(Rectangle::getLeft));
                allBottomChunks.sort(Comparator.comparing(Rectangle::getLeft));
                allTopChunks.sort(Comparator.comparing(Rectangle::getLeft));
                if (allBottomChunks.get(0).getLeft() < allTopChunks.get(0).getLeft()) {
                    if (FloatUtils.feq(allBottomChunks.get(0).overlapRatio(minLeftShadowRects.get(0)), 1.0, 0.1)) {
                        removeTexts.add(minLeftChunk);
                    }
                } else {
                    if (FloatUtils.feq(allTopChunks.get(0).overlapRatio(minLeftShadowRects.get(0)), 1.0, 0.1)) {
                        removeTexts.add(minLeftChunk);
                    }
                }
            }
        }

        if (maxLeftChunk.getWidth() < maxLeftChunk.getHeight() && maxLeftChunk.getWidth() < 2.5 * page.getAvgCharWidth()) {
            List<Rectangle> maxLeftShadowRects = textChunks.stream().filter(re -> (re.isHorizontallyOverlap(maxLeftChunk)
                    && !re.equals(maxLeftChunk))).collect(Collectors.toList());
            if (maxLeftShadowRects.isEmpty()) {
                removeTexts.add(maxLeftChunk);
            } else {
                maxLeftShadowRects.sort(Comparator.comparing(Rectangle::getLeft).reversed());
                allBottomChunks.sort(Comparator.comparing(Rectangle::getLeft).reversed());
                allTopChunks.sort(Comparator.comparing(Rectangle::getLeft).reversed());
                if (allBottomChunks.get(0).getLeft() > allTopChunks.get(0).getLeft()) {
                    if (FloatUtils.feq(allBottomChunks.get(0).overlapRatio(maxLeftShadowRects.get(0)), 1.0, 0.1)) {
                        removeTexts.add(maxLeftChunk);
                    }
                } else {
                    if (FloatUtils.feq(allTopChunks.get(0).overlapRatio(maxLeftShadowRects.get(0)), 1.0, 0.1)) {
                        removeTexts.add(maxLeftChunk);
                    }
                }
            }

        }

        textChunks.removeAll(removeTexts);

        if (minLeftChunk.getPaginationType() == PaginationType.HEADER && minLeftChunk.getElements().size() == 1) {
            List<TextChunk> leftHeaderChunks = textChunks.stream().filter(te -> (te.getPaginationType() == PaginationType.HEADER
                    && te.horizontallyOverlapRatio(minLeftChunk) > 0.9 && overLapOfWidth(te, minLeftChunk) > 0.9)).collect(Collectors.toList());
            textChunks.removeAll(leftHeaderChunks);
        }


        if (maxLeftChunk.getPaginationType() == PaginationType.HEADER && maxLeftChunk.getElements().size() == 1) {
            List<TextChunk> rightHeaderChunks = textChunks.stream().filter(te -> (te.getPaginationType() == PaginationType.HEADER
                    && te.horizontallyOverlapRatio(maxLeftChunk) > 0.9 && overLapOfWidth(te, maxLeftChunk) > 0.9)).collect(Collectors.toList());
            textChunks.removeAll(rightHeaderChunks);
        }

        return textChunks;
    }

    private static List<Rectangle> filterRegionsByLine(ContentGroupPage page, List<Rectangle> lineAreas, List<Rectangle> textRegions, List<FillAreaGroup> fillGroups) {

        if (lineAreas.isEmpty()) {
            List<Rectangle> textAreas = splitChartOrTableAreas(page, textRegions);
            return textAreas;
        }

        if (lineAreas.size() == 1 && lineAreas.get(0).getArea() / page.getArea() > 0.7
                && textRegions.stream().allMatch(te -> (lineAreas.get(0).nearlyContains(te)))
                && textRegions.stream().anyMatch(te -> (overLapOfHeight(lineAreas.get(0), te) > 0.6
                && overLapOArea(lineAreas.get(0), te) > 0.4))) {
            List<Rectangle> tmpAreas = new ArrayList<>(textRegions);
            tmpAreas.sort(Comparator.comparing(Rectangle::getArea).reversed());
            if (fillGroups.stream().anyMatch(fill -> (fill.getGroupRectArea().verticalOverlapRatio(tmpAreas.get(0)) > 0.95
                    && isHasOneRowChunks(page, fill.getGroupRectArea())))) {
                return textRegions;
            }
        }

        List<Rectangle> candidateRects = new ArrayList<>(textRegions);

        for (Rectangle lineRect : lineAreas) {

            if (lineRect.isDeleted()) {
                continue;
            }

            if (page.getText(lineRect).isEmpty()) {
                lineRect.markDeleted();
                continue;
            }

            if (Ruling.getRulingsFromArea(page.getHorizontalRulings(), lineRect).isEmpty()) {
                lineRect.markDeleted();
                continue;
            }
            //TableDebugUtils.writeCells(page, Arrays.asList(lineRect), "xxx_lineRect0");
            List<Rectangle> upRects = candidateRects.stream().filter(re -> (re.getHeight() < 2 * page.getAvgCharHeight()
                    && lineRect.horizontallyOverlapRatio(re) > 0.8
                    && re.getWidth() > 2 * page.getAvgCharWidth() && !re.isDeleted()
                    && ((re.getBottom() < lineRect.getTop()
                    && !hasGapAreas(page, lineRect, re, textRegions) && lineRect.verticalDistance(re) < 2 * page.getAvgCharHeight())
                    || FloatUtils.feq(re.getBottom(), lineRect.getTop(), 4.f)))).collect(Collectors.toList());

            if (upRects.size() == 2 && upRects.get(0).verticalOverlapRatio(upRects.get(1)) > 0.9) {
                List<TextChunk> chunks1 = page.getTextChunks(upRects.get(0));
                List<TextChunk> chunks2 = page.getTextChunks(upRects.get(1));
                if (!chunks1.isEmpty() && !chunks2.isEmpty()) {
                    String headerText1 = "";
                    for (TextChunk tmp : chunks1) {
                        headerText1 += tmp.getText();
                    }
                    String headerText2 = "";
                    for (TextChunk tmp : chunks2) {
                        headerText2 += tmp.getText();
                    }
                    if ((headerText1 != "" && CHART_SERIAL0.matcher(headerText1.trim().toLowerCase()).matches()
                            || CHART_SERIAL1.matcher(headerText1.trim().toLowerCase()).matches()
                            || CHART_SERIAL2.matcher(headerText1.trim().toLowerCase()).matches())
                            || (headerText2 != "" && CHART_SERIAL0.matcher(headerText2.trim().toLowerCase()).matches()
                            || CHART_SERIAL1.matcher(headerText2.trim().toLowerCase()).matches()
                            || CHART_SERIAL2.matcher(headerText2.trim().toLowerCase()).matches())) {
                        // 切分为两个区域
                        upRects.sort(Comparator.comparing(Rectangle::getLeft));
                        List<Rectangle> clusRects = candidateRects.stream().filter(re -> (!re.isDeleted()
                                && !re.isHorizontallyOverlap(upRects.get(1))
                                && lineRect.nearlyContains(re) && re.getRight() < upRects.get(1).getLeft())).collect(Collectors.toList());
                        clusRects.add(upRects.get(0));
                        if (clusRects.size() > 0) {
                            float left = clusRects.stream().min(Comparator.comparing(Rectangle::getLeft)).get().getLeft();
                            float right = clusRects.stream().max(Comparator.comparing(Rectangle::getRight)).get().getRight();
                            left = (left < lineRect.getLeft()) ? left : lineRect.getLeft();
                            float width = right - left;
                            float height = lineRect.getBottom() - upRects.get(0).getTop();
                            Rectangle targetRect =  candidateRects.get(candidateRects.indexOf(upRects.get(0)));
                            targetRect.setRect(left, upRects.get(0).getTop(), width, height);
                            candidateRects.stream().forEach(te -> {
                                if (targetRect.nearlyContains(te) && !te.equals(targetRect)) {
                                    te.markDeleted();
                                }
                            });
                            lineRect.setLeft(targetRect.getRight() + 1);
                            List<Rectangle> clusRect2 = candidateRects.stream().filter(re -> (lineRect.nearlyContains(re)
                                    && !re.isDeleted())).collect(Collectors.toList());
                            clusRect2.add(upRects.get(1));
                            left = clusRect2.stream().min(Comparator.comparing(Rectangle::getLeft)).get().getLeft();
                            left = (left > lineRect.getLeft()) ? left : lineRect.getLeft();
                            width = lineRect.getRight() - left;
                            height = lineRect.getBottom() - upRects.get(1).getTop();
                            lineRect.setRect(left, upRects.get(1).getTop(), width, height);
                            candidateRects.stream().forEach(te -> {
                                if (lineRect.nearlyContains(te)) {
                                    te.markDeleted();
                                }
                            });
                        }
                    }
                }
            }

            //TableDebugUtils.writeCells(page, Arrays.asList(lineRect), "xxx_lineRect1");
            for (Rectangle textRect : candidateRects) {

                if (textRect.isDeleted()) {
                    continue;
                }

                if (isSignalChartAreas(page, textRect) && textRect.intersects(lineRect)
                        && lineRect.getWidth() > textRect.getWidth()
                        && textRect.getHeight() > 5 * page.getAvgCharHeight()
                        && overLapOfWidth(lineRect, textRect) < 0.6) {
                    List<Rectangle> textRects = candidateRects.stream().filter(te -> (lineRect.nearlyContains(te)
                            && !te.equals(textRect) && !te.intersects(textRect)
                            && te.verticalOverlapRatio(textRect) > 0.95)).collect(Collectors.toList());
                    if (!textRects.isEmpty()) {
                        float left = textRects.stream().min(Comparator.comparing(Rectangle::getLeft)).get().getLeft();
                        lineRect.setLeft(left);
                        continue;
                    }
                }

                if (lineRect.nearlyContains(textRect, 4)) {
                    textRect.markDeleted();
                    continue;
                }

                if (textRect.nearlyContains(lineRect, 4)) {
                    lineRect.markDeleted();
                    break;
                }
            }
        }
        candidateRects.removeIf(Rectangle::isDeleted);
        lineAreas.removeIf(Rectangle::isDeleted);
        if (!lineAreas.isEmpty()) {
            candidateRects.addAll(lineAreas);
        }
        processRepeateAndContainedAreas(page, candidateRects);
        candidateRects = splitChartOrTableAreas(page, candidateRects);
        return candidateRects;
    }

    private static List<Rectangle> splitChartOrTableAreas(ContentGroupPage page, List<Rectangle> candidateRects) {
        if (candidateRects.isEmpty()) {
            return new ArrayList<>();
        }

        List<Rectangle> targetAreas = new ArrayList<>();
        for (Rectangle candidateArea : candidateRects) {
            List<TextChunk> areaChunks = new ArrayList<>(page.getTextChunks(candidateArea));
            if (areaChunks.isEmpty()) {
                continue;
            }
            areaChunks.sort(Comparator.comparing(TextChunk::getTop));
            List<TextChunk> clusChunks = areaChunks.stream()
                    .filter(te -> (te.verticalOverlapRatio(areaChunks.get(0)) > 0.5)).collect(Collectors.toList());
            clusChunks.sort(Comparator.comparing(TextChunk::getLeft));

            List<Rectangle> splitAreas = null;
            if (clusChunks.size() >= 2) {
                TextChunk firstChunk = clusChunks.get(0);
                boolean isHeader = CHART_TABLE_SPLIT_SERIAL.matcher(firstChunk.getText().toLowerCase()).matches();
                if (isHeader) {
                    TextChunk secondChunk = null;
                    for (TextChunk chunk : clusChunks) {
                        if (chunk.equals(firstChunk)) {
                            continue;
                        }
                        if (CHART_TABLE_SPLIT_SERIAL.matcher(chunk.getText().toLowerCase()).matches()) {
                            secondChunk = chunk;
                            break;
                        }
                    }
                    if (secondChunk != null) {
                        TextChunk finalSecondChunk = secondChunk;
                        List<TextChunk> chunks1 = clusChunks.stream().filter(te -> (te.getRight() < finalSecondChunk.getLeft())).collect(Collectors.toList());
                        clusChunks.removeAll(chunks1);
                        splitAreas = getChartOrTableSplitAreas(page, chunks1, clusChunks, candidateArea);
                        if (splitAreas.size() == 2) {
                            targetAreas.addAll(splitAreas);
                        }
                    }
                }
            }
            if (splitAreas == null || splitAreas.isEmpty()) {
                targetAreas.add(candidateArea);
            }
        }
        return targetAreas;
    }

    private static List<Rectangle> getChartOrTableSplitAreas(ContentGroupPage page, List<TextChunk> baseChunks,
                                                             List<TextChunk> otherChunks, Rectangle candidateArea) {
        if (baseChunks.isEmpty() || otherChunks.isEmpty()
                || (baseChunks.get(0).verticalOverlapRatio(otherChunks.get(0)) < 0.5)) {
            return new ArrayList<>();
        }

        List<Rectangle> targetAreas = new ArrayList<>();
        String baseTexts = "";
        for (TextChunk tmpChunk : baseChunks) {
            baseTexts += tmpChunk.getText();
        }
        String otherTexts = "";
        for (TextChunk tmpChunk : otherChunks) {
            otherTexts += tmpChunk.getText();
        }
        if ((baseTexts != "" && (CHART_SERIAL0.matcher(baseTexts.trim().toLowerCase()).matches()
                || CHART_SERIAL1.matcher(baseTexts.trim().toLowerCase()).matches()
                || CHART_SERIAL2.matcher(baseTexts.trim().toLowerCase()).matches()
                || TABLE_SERIAL.matcher(baseTexts.trim().toLowerCase()).matches()))
                && (otherTexts != "" && (CHART_SERIAL0.matcher(otherTexts.trim().toLowerCase()).matches()
                || CHART_SERIAL1.matcher(otherTexts.trim().toLowerCase()).matches()
                || CHART_SERIAL2.matcher(otherTexts.trim().toLowerCase()).matches()
                || TABLE_SERIAL.matcher(otherTexts.trim().toLowerCase()).matches()))) {

            float left, top, width, height;
            left = baseChunks.stream().min(Comparator.comparing(Rectangle::getLeft)).get().getLeft();
            top = candidateArea.getTop();
            width = baseChunks.stream().max(Comparator.comparing(Rectangle::getRight)).get().getRight() - left;
            height = baseChunks.stream().max(Comparator.comparing(Rectangle::getBottom)).get().getBottom() - top;
            Rectangle baseRect = new Rectangle(left, top, width, height);

            left = otherChunks.stream().min(Comparator.comparing(Rectangle::getLeft)).get().getLeft();
            width = otherChunks.stream().max(Comparator.comparing(Rectangle::getRight)).get().getRight() - left;
            height = otherChunks.stream().max(Comparator.comparing(Rectangle::getBottom)).get().getBottom() - top;
            Rectangle otherRect = new Rectangle(left, top, width, height);
            List<TextChunk> allChunks = page.getTextChunks(candidateArea);

            if (!allChunks.isEmpty()) {
                List<TextChunk> chunks1 = allChunks.stream().filter(te -> (!te.isHorizontallyOverlap(otherRect)
                        && te.getRight() < otherRect.getLeft())).collect(Collectors.toList());
                float maxRight = chunks1.stream().max(Comparator.comparing(Rectangle::getRight)).get().getRight();
                baseRect.setRect(candidateArea.getLeft(), top, maxRight - candidateArea.getLeft(), candidateArea.getHeight());
                List<TextChunk> chunks2 = allChunks.stream().filter(te -> (!te.isHorizontallyOverlap(baseRect)
                        && te.getLeft() > baseRect.getRight())).collect(Collectors.toList());
                float minLeft = chunks2.stream().min(Comparator.comparing(Rectangle::getLeft)).get().getLeft();
                otherRect.setRect(minLeft, top, candidateArea.getRight() - minLeft, candidateArea.getHeight());

                chunks1 = new ArrayList<>(page.getTextChunks(baseRect));
                chunks2 = new ArrayList<>(page.getTextChunks(otherRect));
                if (!chunks1.isEmpty() && !chunks2.isEmpty()) {
                    chunks1.sort(Comparator.comparing(TextChunk::getBottom).reversed());
                    List<TextChunk> finalChunks = chunks1;
                    chunks1 = chunks1.stream().filter(te -> (te.verticalOverlapRatio(finalChunks.get(0)) > 0.5)).collect(Collectors.toList());
                    chunks1.sort(Comparator.comparing(TextChunk::getLeft));
                    chunks2.sort(Comparator.comparing(TextChunk::getBottom).reversed());
                    List<TextChunk> finalChunks1 = chunks2;
                    chunks2 = chunks2.stream().filter(te -> (te.verticalOverlapRatio(finalChunks1.get(0)) > 0.5)).collect(Collectors.toList());
                    chunks2.sort(Comparator.comparing(TextChunk::getLeft));
                    String tailTexts1 = "";
                    for (TextChunk tmpChunk : chunks1) {
                        tailTexts1 += tmpChunk.getText();
                    }
                    String tailTexts2 = "";
                    for (TextChunk tmpChunk : chunks2) {
                        tailTexts2 += tmpChunk.getText();
                    }
                    if ((tailTexts1 != "" && (DATA_SOURCE_SERIAL.matcher(tailTexts1.trim().toLowerCase()).matches()))
                            && (tailTexts2 != "" && (DATA_SOURCE_SERIAL.matcher(tailTexts2.trim().toLowerCase()).matches()))) {
                        targetAreas.add(baseRect);
                        targetAreas.add(otherRect);
                    }
                }
                if (targetAreas.isEmpty()
                        && RulingTableRegionsDetectionAlgorithm.findRulingRectangles(page).stream().anyMatch(re -> (candidateArea.nearlyContains(re)
                        && overLapOArea(candidateArea, re) > 0.8))) {
                    targetAreas.add(baseRect);
                    targetAreas.add(otherRect);
                }
            }
        }
        return targetAreas;
    }

    private static List<Rectangle> correctLayoutAreas(ContentGroupPage page, List<Rectangle> allAreas) {
        if (allAreas.isEmpty()) {
            return Arrays.asList(page);
        }

        processRepeateAndContainedAreas(page, allAreas);

        allAreas.sort(Comparator.comparing(Rectangle::getTop));
        List<Rectangle> targetRects = new ArrayList<>();
        Rectangle baseRect = allAreas.get(0);
        boolean hasColumn = false;
        for (int i = 1; i < allAreas.size(); i++) {
            Rectangle otherRect = allAreas.get(i);
            if (baseRect.isVerticallyOverlap(otherRect)) {
                hasColumn = true;
                break;
            }
            baseRect = otherRect;
        }
        if (hasColumn) {
            targetRects.addAll(allAreas);
        } else {
            targetRects.add(new Rectangle(page));
        }

        if (targetRects.size() > 1) {
            targetRects = mergeSmallRects(page, targetRects);
            processRepeateAndContainedAreas(page, targetRects);
        }

        if (targetRects.size() == 1 || targetRects.isEmpty()) {
            return Arrays.asList(page);
        }

        sortContentLayoutAreas(targetRects);

        return targetRects;
    }

    private static boolean hasGapAreas(ContentGroupPage page, Rectangle one, Rectangle other, List<Rectangle> allAreas) {

        if (one.overlapRatio(other) > 0 || allAreas.isEmpty() ) {
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

            if (allAreas.stream().filter(r -> (gapRect1.contains(r) || gapRect1.intersects(r))).collect(Collectors.toList()).isEmpty()) {
                return false;
            }
        }

        return true;
    }

    private static List<Rectangle> mergeSmallRects(ContentGroupPage page, List<Rectangle> candidateRects) {

        if (candidateRects.isEmpty()) {
            return new ArrayList<>();
        }

        candidateRects.sort(Comparator.comparing(Rectangle::getArea).reversed());
        List<Rectangle> targetRects = new ArrayList<>();
        boolean isMerge = false;
        for (Rectangle tmpArea : candidateRects) {
            if (tmpArea.isDeleted()) {
                continue;
            }
            // 上方所有的区域
            do {

                //TableDebugUtils.writeCells(page, Arrays.asList(tmpArea), "xxxtmpArea");

                List<Rectangle> upRects = candidateRects.stream().filter(r -> (r.getBottom() <= tmpArea.getTop()
                        && r.isHorizontallyOverlap(tmpArea) && !r.isDeleted())).collect(Collectors.toList());
                List<Rectangle> mergeRects = new ArrayList<>();
                for (Rectangle upRect : upRects) {
                    if (!hasGapAreas(page, tmpArea, upRect, candidateRects) && isHaveHorizontalSameShadows(page, tmpArea, upRect, candidateRects)
                            && tmpArea.horizontallyOverlapRatio(upRect) > 0.8) {
                        mergeRects.add(upRect);
                    }
                }

                //TableDebugUtils.writeCells(page, mergeRects, "xxxmergeRects");
                if (mergeRects.size() == 1 && isCanMerge(page, tmpArea, mergeRects.get(0), candidateRects)
                        && (!isSeparatedByHline(page, tmpArea, mergeRects.get(0))
                        || (mergeRects.get(0).verticalDistance(tmpArea) < 4.f) && overLapOfWidth(tmpArea, mergeRects.get(0)) > 0.9)) {
                    tmpArea.merge(mergeRects.get(0));
                    mergeRects.get(0).markDeleted();
                    isMerge = true;
                } else {
                    isMerge = false;
                }

            } while (isMerge);
        }
        candidateRects.removeIf(Rectangle::isDeleted);
        targetRects.addAll(candidateRects);
        return targetRects;
    }

    private LayoutAnalysisAlgorithm() {

    }

}
