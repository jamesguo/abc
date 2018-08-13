package com.abcft.pdfextract.core.chart;

import java.awt.*;
import java.awt.geom.GeneralPath;
import java.awt.geom.Line2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.*;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.abcft.pdfextract.core.PdfExtractContext;
import com.abcft.pdfextract.core.model.*;
import com.abcft.pdfextract.core.model.Rectangle;
import com.abcft.pdfextract.core.model.TextChunk;
import com.abcft.pdfextract.core.model.TextElement;
import com.abcft.pdfextract.spi.ChartType;
import com.abcft.pdfextract.core.util.GraphicsUtil;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.abcft.pdfextract.core.chart.model.PathInfo;

import static com.abcft.pdfextract.core.chart.ChartPathInfosParser.getPathColumnBox;

/**
 * Created by myyang on 17-4-13.
 */
public class ChartTitleLegendScaleParser {
    private static Logger logger = LogManager.getLogger(ChartTitleLegendScaleParser.class);
    private static final String PAGE_ARG_CANDIDATE_TITLES = "chart.candidateTitles";
    private static final int TITLE_SEARCH_HEIGHT = 50;

    /**
     * 初步设置TextChunk的类型信息
     *
     * @param textChunks
     */
    public static void initChunksType(List<TextChunk> textChunks) {
        for (TextChunk textChunk : textChunks) {
            if (ChunkUtil.getChunkType(textChunk) != ChunkStatusType.UNKNOWN_TYPE) {
                continue;
            }
            // 目前没有出现过 垂直排列的标题信息 设置为垂直类型可以提高标题检查效率
            if (ChunkUtil.isVertical(textChunk)) {
                ChunkUtil.setChunkType(textChunk, ChunkStatusType.VERTICAL_TYPE);
                continue;
            }
            // 如果有数据来源相关特征 一般不可能为标题 设置为忽略类型方便后续的标题解析
            if (isDataSourceTextChunk(textChunk)) {
                ChunkUtil.setChunkType(textChunk, ChunkStatusType.DATA_SOURCE_TYPE);
                continue;
            }
        } // end for textChunk
    }

    /**
     * 忽略掉 chart.chunkMain中在数据来源或注解信息后面的 Chunk 以免干扰刻度的解析
     *
     * @param chunks
     */
    private static void ignoreBehindSourceNoteDateChunk(List<TextChunk> chunks) {
        if (chunks == null) {
            return;
        }
        boolean beginSourceAndNote = false;
        double ySource = 0.0;
        for (TextChunk chunk : chunks) {
            if (ChunkUtil.getChunkType(chunk) == ChunkStatusType.DATA_SOURCE_TYPE) {
                beginSourceAndNote = true;
                ySource = ySource < chunk.getY() ? chunk.getY() : ySource;
            } else if (isNoteTextChunk(chunk)) {
                beginSourceAndNote = true;
                ySource = ySource < chunk.getY() ? chunk.getY() : ySource;
                ChunkUtil.setChunkType(chunk, ChunkStatusType.IGNORE_TYPE);
            } else if (beginSourceAndNote) {
                if (chunk.getY() >= ySource) {
                    ChunkUtil.setChunkType(chunk, ChunkStatusType.IGNORE_TYPE);
                }
            }
        }
    }

    /**
     * 设置可以忽略的Chunk对象的类型为 IGNORE_TYPE
     *
     * @param textChunks
     * @param charts
     */
    public static void setIgnoreChunks(
            List<TextChunk> textChunks,
            List<Chart> charts) {
        // 初步设置类型
        initChunksType(textChunks);
        for (Chart chart : charts) {
            initChunksType(chart.chunksRemain);
            ignoreBehindSourceNoteDateChunk(chart.chunksRemain);
        }

        // 过滤掉 有句点的chunk　此时一般为Page正文的语句 除了在Chart内部的除外
        for (TextChunk textChunk : textChunks) {
            if (ChunkUtil.getChunkType(textChunk) == ChunkStatusType.UNKNOWN_TYPE &&
                    (textChunk.getText().contains("。") ||
                    textChunk.getText().contains("？"))) {
                boolean bIgnore = true;
                for (Chart chart : charts) {
                    if (chart.type == ChartType.BITMAP_CHART) {
                        continue;
                    }
                    if (chart.getArea().contains(textChunk)) {
                        bIgnore = false;
                        break;
                    }
                } // end for i
                if (bIgnore) {
                    ChunkUtil.setChunkType(textChunk, ChunkStatusType.IGNORE_TYPE);
                }
            } // end if
        } // end for chunk

        // 暂时忽略掉水平排列柱状图矩形内部的数字 避免干扰刻度的解析
        ignoreChunkInColumnCharts(charts);
    }

    /**
     * 设置匹配Subtitle格式的Chunk对象的类型为 SUBTITLE_TYPE
     *
     * @param textChunks
     * @param charts
     */
    public static void setSubtitle(
            List<TextChunk> textChunks,
            List<Chart> charts){

        // 筛Merged
        filterSubtitle(textChunks);

        // 筛每个Chart的chunksRemain
        for (Chart chart : charts) {
            filterSubtitle(chart.chunksRemain);
        }
    }

    private static void filterSubtitle (List<TextChunk> textChunks) {
        // GIC项目标题解析优化 将相似的副标题提前分类
        Pattern regExSubtitlePattern = RegularMatch.getInstance().regExSubtitlePattern;
        for (TextChunk textChunk : textChunks) {
            if (textChunk.getText().length() < 35) {
                Matcher matcher = regExSubtitlePattern.matcher(textChunk.getText().trim());
                if (matcher.find()) {
                    ChunkUtil.setChunkType(textChunk, ChunkStatusType.SUBTITLE_TYPE);
                }
            }
        }
    }


    /**
     * 过滤掉完全包含于水平柱状图中的TextChunk 避免干扰刻度解析
     *
     * @param charts
     */
    public static void ignoreChunkInColumnCharts(
            List<Chart> charts) {
        for (Chart chart : charts) {
            ignoreChunkInColumnChart(chart, chart.chunksRemain);
        }
    }

    /**
     * 如果给定Chart为水平柱状图 则过滤掉内部的 TextChunk
     *
     * @param chart
     * @param textChunks
     */
    public static void ignoreChunkInColumnChart(
            Chart chart, List<TextChunk> textChunks) {
        if (chart.type != ChartType.COLUMN_CHART) {
            return;
        }
        List<Double> xs = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
        Rectangle2D box = new Rectangle2D.Double();
        double x = 0.0, y = 0.0, w = 0.0, h = 0.0;
        for (ChartPathInfo pathInfo : chart.pathInfos) {
            // 遍历当前 PathInfo中Path的所有矩形　
            ChartPathInfosParser.getPathColumnBox(pathInfo.path, xs, ys);
            int nBox = xs.size() / 2;
            for (int k = 0; k < nBox; k++) {
                x = xs.get(2 * k);
                w = xs.get(2 * k + 1) - x;
                y = ys.get(2 * k);
                h = ys.get(2 * k + 1) - y;
                box.setRect(x, y, w, h);
                for (TextChunk chunk : textChunks) {
                    if (ChunkUtil.getChunkType(chunk) == ChunkStatusType.IGNORE_TYPE) {
                        continue;
                    }
                    if (!isStringNumber(chunk.getText())) {
                        continue;
                    }
                    if (box.intersects(chunk.getBounds())) {
                        ChunkUtil.setChunkType(chunk, ChunkStatusType.IGNORE_TYPE);
                    }
                }
            } // end for k
        } // end for pathInfo
    }

    static Rectangle2D getLegendArea(Shape path) {
        Line2D line = GraphicsUtil.getLine(path);
        Rectangle2D area;
        if (line != null && GraphicsUtil.isHorizontal(line)) {
            area = line.getBounds2D();
        } else if (GraphicsUtil.isRect(path)) {
            area = path.getBounds2D();
        } else if (line != null && Math.abs(line.getY1() - line.getY2()) < 0.2) {
            area = line.getBounds2D();
        } else {
            return null;
        }
        if (area.getWidth() > 1.0
                && area.getWidth() < 20
                && area.getHeight() < 10) {
            return area;
        } else {
            return null;
        }
    }

    private static void addLegend(Chart chart,Color color, Rectangle2D area) {
        // 检测是否已添加, 有时候某些legend会画多笔, 但是是在同一个区域
        for (Chart.Legend legend : chart.legends) {
            Rectangle2D rect = legend.line.getBounds2D();
            GraphicsUtil.extendRect(rect, 1);//暂时不扩展
            if (rect.intersects(area)) {
                return;
            }
        }
        Chart.Legend legend = new Chart.Legend();
        legend.color = color;
        legend.line = area;
        chart.legends.add(legend);
    }
    /**
     * 如果当前图表里面没有找到任何图例，那么尝试
     * @param chart
     */
    private static void findLegends(Chart chart) {
        if (chart.legends.size() == 0 && chart.type != ChartType.BAR_CHART) {
            return;
        }
        System.out.print("start find legends for " + chart.getName() + "\n");
        int barGroup = 0;
        double miny = -1;
        double maxy = -1;
        for (ChartPathInfo path : chart.pathInfos) {
            if (path.type == PathInfo.PathType.BAR) {
                List<Double> xs = new ArrayList<>();
                List<Double> ys = new ArrayList<>();
                ChartPathInfosParser.getPathColumnBox(path.path, xs,ys);
                double curminy = Collections.min(ys);
                double curmaxy = Collections.max(ys);
                miny = (miny != -1) ? Math.min(miny, curminy) : curminy;
                maxy = (maxy != -1) ? Math.max(maxy, curmaxy) : curmaxy;
                barGroup++;
            }
        }
        if (barGroup == 1) {
            return;
        }
        // 只有柱状图并且有多组不同颜色的柱子，没有找到任何图例的时候才去寻找图例
        List<PathItem> items = chart.contentGroup.getAllPathItems();
        for (PathItem item :items) {
            Color color = item.getRGBColor();
            if (ChartContentDetector.isWhite(color)) {
                continue;
            }
            Rectangle2D area = getLegendArea(item.getItem());
            if (area != null && area.getWidth() < 5) {
                System.out.print("find new lengend11:" + area.toString() + "\n");
            }
            // 图例必须在所有柱子的上方或者 下方
            if (area != null && (area.getMinY() < miny || area.getMaxY() > maxy) ) {
                addLegend(chart,color, area);
                System.out.print("find new lengend:" + area.toString() + "\n");
                continue;
            }
        }
        System.out.print("End find legends for " + chart.getName()+ "\n");
    }

    /**
     * 解析chart图例
     *
     * @param chart
     */
    public static void selectChartLegends(Chart chart) {
        if (chart == null || !chart.isChart() || chart.type == ChartType.BITMAP_CHART) {
            return;
        }

        // 尝试去再次寻找图例
       // findLegends(chart);

        // 饼图不应该有BarInfo 我们将里面类似legend的部分筛选出
        if(chart.type == ChartType.PIE_CHART
                && !chart.barsInfos.isEmpty()
                && !chart.legends.isEmpty()){
            Chart.Legend sample = chart.legends.get(0);
            double width = Math.abs(sample.line.getMaxX() - sample.line.getMinX());
            double height = Math.abs(sample.line.getMaxY() - sample.line.getMinY());
            Iterator<ChartPathInfo> iter = chart.barsInfos.iterator();
            while (iter.hasNext()){
                ChartPathInfo current = iter.next();
                Rectangle2D bound = current.path.getBounds2D();
                double currentW = Math.abs(bound.getMaxX() - bound.getMinX());
                double currentH = Math.abs(bound.getMaxY() - bound.getMinY());
                if (Math.abs(currentW - width) < 5
                        && Math.abs(currentH - height) < 5) {
                    // 这个BarInfo符合Legend的特性，将其归入图例
                    Chart.Legend tempLegend = new Chart.Legend();
                    tempLegend.line = bound;
                    tempLegend.color = current.color;
//                    tempLegend.type =
                    tempLegend.text = current.text;
                    chart.legends.add(0, tempLegend);
                    iter.remove();
                }
            }
        }

        Map<Integer, Double> legendMinX = new HashMap<>();
        Map<Integer, TextChunk> legendChunkMap = new HashMap<>();
        List<TextChunk> candidateLegends = new ArrayList<>();
        List<List<Integer>> candidateLegendsIds = new ArrayList<>();
        if (!chart.legends.isEmpty()) {
            int nlegends = chart.legends.size();
            for (int i = 0; i < nlegends; i++) {
                candidateLegendsIds.add(new ArrayList<>());
            }
        }

        // 遍历所有的chunk，找出legends
        List<TextChunk> textChunks = chart.chunksRemain;
        for (TextChunk textChunk : textChunks) {
            // 跳过其他类型 或 内容为空的对象
            if (ChunkUtil.getChunkType(textChunk) != ChunkStatusType.UNKNOWN_TYPE ||
                    StringUtils.isEmpty(textChunk.getText())) {
                continue;
            }

            // 根据chart中的配色状态,找出匹配的legend 　
            // 已经出现过图例在chart的外部，此时解析legend不准确 而且会干扰标题的解析 　暂时没有处理这种情况
            int ith = 0;
            for (Chart.Legend legend : chart.legends) {
                ith++;
                // 图例对应的文字都在右边
                if (textChunk.getCenterX() <= legend.line.getMaxX()) {
                    continue;
                }
                Line2D legendCheckLine = new Line2D.Float(
                        (float) legend.line.getMaxX(),
                        (float) legend.line.getCenterY(),
                        (float) legend.line.getMaxX() + 3.0f * (float) ChunkUtil.getMaxFontWidth(textChunk),
                        (float) legend.line.getCenterY());
                if (textChunk.intersectsLine(legendCheckLine)) {
                    if (StringUtils.isNotEmpty(legend.text)) {
                        if (legendMinX.get(ith) < textChunk.getBounds().getMinX()) {
                            continue;
                        }
                    }
                    legend.text = textChunk.getText().trim();
                    legendChunkMap.put(ith, textChunk);
                    //chart.legendsChunks.add(textChunk);
                    chart.addFontName(textChunk.getFontNames());
                    //textChunk.setType(ChunkStatusType.LEGEND_TYPE);
                    legendMinX.put(ith, textChunk.getBounds().getMinX());
                    break;
                }
                // textChunk与图例标识近似水平 且在右侧 加入候选集
                else if ((legendCheckLine.getY1() < textChunk.getY() + textChunk.getHeight()) &&
                        (legendCheckLine.getY1() > textChunk.getY()) &&
                        (legendCheckLine.getX2() < textChunk.getX())) {
                    double dist = legendCheckLine.getP2().distance(
                            textChunk.getMinX(), textChunk.getCenterY());
                    if (dist >= 0.1 * chart.getWidth()) {
                        continue;
                    }
                    candidateLegends.add(textChunk);
                    List<Integer> ids = candidateLegendsIds.get(ith - 1);
                    ids.add(candidateLegends.size() - 1);
                }
            } // end for legend
        } // end for chunk

        // 再一次处理　没有添加的候选图列
        int ith = 0, iCandi = -1;
        double xMin = -1.0f, x = -1.0f;
        for (Chart.Legend legend : chart.legends) {
            ith++;
            if (StringUtils.isNotEmpty(legend.text)) {
                TextChunk lchunk = legendChunkMap.get(ith);
                ChunkUtil.setChunkType(lchunk, ChunkStatusType.LEGEND_TYPE);
                chart.legendsChunks.add(lchunk);
                continue;
            }
            List<Integer> ids = candidateLegendsIds.get(ith - 1);
            if (ids.isEmpty()) {
                continue;
            }

            iCandi = -1;
            xMin = -1.0f;
            for (Integer id : ids) {
                TextChunk chunk = candidateLegends.get(id);
                x = chunk.getX();
                if (xMin < 0.0f || xMin > x) {
                    xMin = x;
                    iCandi = id;
                }
            } // end for id
            TextChunk chunk = candidateLegends.get(iCandi);
            legend.text = chunk.getText().trim();
            chart.legendsChunks.add(chunk);
            chart.addFontName(chunk.getFontNames());
            ChunkUtil.setChunkType(chunk, ChunkStatusType.LEGEND_TYPE);
        } // end for legend

        //　水平方向从左到右扩展标题
        expandHorizonLegends(chart, textChunks);

        // 方向向下尝试扩展非饼图图列
        expandUpDownLegends(chart, textChunks, false);
    }

    /**
     * 解析chart图例
     * 注: 目前处理不了复杂标识的图例  如图片或是复杂图案
     *
     * @param textChunks
     * @param charts     当前page的chart集
     */
    public static void selectLegends(
            List<TextChunk> textChunks,
            List<Chart> charts) {

        // 过滤掉重叠的扇形,有些扇形会绘制两次
        for (Chart chart : charts) {
            if (chart.type != ChartType.PIE_CHART) {
                continue;
            }
            int precount = chart.pieInfo.parts.size();
            do {
                for (int i = 0; i <  chart.pieInfo.parts.size(); i++) {
                    Chart.PieInfo.PiePartInfo piePartInfo = chart.pieInfo.parts.get(i);
                    precount = chart.pieInfo.parts.size();
                    for (int j = 0; j < chart.pieInfo.parts.size(); j++) {
                        if(i == j) {
                            continue;
                        }
                        Chart.PieInfo.PiePartInfo piePartInfoCompared = chart.pieInfo.parts.get(j);
                        java.awt.Rectangle pre = piePartInfo.path.getBounds();
                        java.awt.Rectangle comp = piePartInfoCompared.path.getBounds();
                        if(pre.equals(comp)){
                            chart.pieInfo.parts.remove(Math.min(i,j));
                            i=0;
                            break;
                        }
                    }


                }

                int count = chart.pieInfo.parts.size();
            }
            while(precount > chart.pieInfo.parts.size());

        }

        for (Chart chart : charts) {
            selectChartLegends(chart);
        }

        // 有可能内部包含具有隐式图例的饼图
        selectPieChartsLegends(charts);

        // 过滤掉无效图例
        removeInValidLegends(charts);

        // 更新类型
        for (Chart chart : charts) {
            updateChunkType(chart.legendsChunks, textChunks);
            if (!chart.pieInfoLegendsChunks.isEmpty()) {
                updateChunkType(chart.pieInfoLegendsChunks, textChunks);
            }
        }

    }
    public static void updateChunkType(
            TextChunk chunkA, List<TextChunk> chunksB) {
        if (chunkA == null) {
            return;
        }
        List<TextChunk> chunksA = new ArrayList<>();
        chunksA.add(chunkA);
        updateChunkType(chunksA, chunksB);
    }

    public static void updateChunkType(
            List<TextChunk> chunksA,
            List<TextChunk> chunksB) {
        if (chunksA == null || chunksA.isEmpty()) {
            return;
        }
        Set<Integer> markSet = new HashSet<>();
        getChunksGroupInfos(chunksA, markSet);
        ChunkStatusType type = ChunkUtil.getChunkType(chunksA.get(0));
        for (TextChunk chunk : chunksB) {
            if (isChunkInGroupSet(chunk, markSet, 0.8)) {
                ChunkUtil.setChunkType(chunk, type);
            }
        }
    }

    /**
     * 找出给定Chart集中饼图的隐式图例信息
     *
     * @param charts 包含Chart集
     */
    public static void selectPieChartsLegends(List<Chart> charts) {
        for (Chart chart : charts) {
            selectPieChartLegend(chart);
        }
    }

    /**
     * 如果饼图没有找到显式图列　则找隐式图例信息
     *
     * @param chart
     */
    public static void selectPieChartLegend(Chart chart) {

        if (chart == null || !chart.isChart() || chart.type != ChartType.PIE_CHART) {
            return;
        }

        // 如果显式图例包含了百分比数据 则不必查找PieLegend
//        if (chart.legends.size() == chart.pieInfo.parts.size() &&
//                chart.legends.stream().allMatch(legend -> legend.text != null &&
//                        legend.text.matches("(.)*(\\d)(.)*") && legend.text.indexOf('%') > 0)){
//            return;
//        }

        //  存储以百分比结尾的TextChunk
        List<TextChunk> legendsuffixs = new ArrayList<>();
        List<TextChunk> legendNumberSuffixs = new ArrayList<>(); // 包含数字的TextChunk
        //  存储在Chart内部的其他候选的TextChunk
        List<TextChunk> candidateLegends = new ArrayList<>();
        List<String> persionWeights = new ArrayList<>();


        // 遍历所有的chunk，找出legends
        List<TextChunk> textChunks = chart.chunksRemain;
        for (TextChunk textChunk : textChunks) {
            // 如果已经标记过或是垂直排列的　跳过
            if ((ChunkUtil.getChunkType(textChunk) != ChunkStatusType.UNKNOWN_TYPE &&
                    ChunkUtil.getChunkType(textChunk) != ChunkStatusType.LEGEND_TYPE) ||
                    ChunkUtil.isVertical(textChunk))
                continue;

            // 判断是否以百分数结尾　如果不是　则跳过  后续需要改进只有文字信息的情况
            String[] weight = {"0.0%"};
            boolean bValid = isChunkEndWithPersion(textChunk, weight, true);
            if (!bValid) {
                candidateLegends.add(textChunk);
                continue;
            }

            persionWeights.add(weight[0]);
            legendsuffixs.add(textChunk);
        } // end for chunk

        // 判断所有的百分数相加是否超过了100%，如果超过了，则根据和扇形、环形的匹配状态去做过滤
        double total = 0;
        for (int i = 0; i < legendsuffixs.size(); i++) {
            TextChunk curChunk = legendsuffixs.get(i);
            double value = getChunkFirstNumberValue(curChunk,true);
            total += value;
        }
        if (total > 105 /*&& legendsuffixs.size() > chart.pieInfo.parts.size()*/) {
            for (int i = legendsuffixs.size() - 1; i > -1; i--) {
                TextChunk curChunk = legendsuffixs.get(i);
                double wht = getChunkFirstNumberValue(curChunk,true);
                if (!matchPiePartWeight(chart, wht / 100, curChunk, false, true, 0.001, curChunk.getText())) {
                    if (wht / 100 > 0.01) {
                        // TODO: 对于这种，就直接简单粗暴的扔掉吗？还是存起来给用户看
                        legendsuffixs.remove(i);
                        candidateLegends.add(curChunk);
                        persionWeights.remove(i);
                    }
                }
            }
        }

        // 合并百分数图例
        if (legendsuffixs.size() > 0) {
            List<TextChunk> newsuffixs = new ArrayList<>();
            List<TextChunk> newcandiatelegends = new ArrayList<>();
            expansionPieChartLegendChunk(chart,legendsuffixs, candidateLegends, newsuffixs, newcandiatelegends, true);
            legendsuffixs = newsuffixs;
            candidateLegends= newcandiatelegends;
        }

        // 如果没有找到以百分数结尾的对象　则尝试找出以数字结尾的对象并统计其数值总和
        List<String> numberWeights = new ArrayList<>(); // 这里缅存的是0.1这种比例小数
        List<TextChunk> textAroundPie = new ArrayList<>(); //存放围绕在扇形周围不是标准图例的文字块
        if (!candidateLegends.isEmpty()) {
            final Iterator<TextChunk> each = candidateLegends.iterator();
            while (each.hasNext()) {
                TextChunk chunk = each.next();
                // 不从图例文字里面去寻找数字，只从扇形周围的说明文字里面找数值
                if (ChunkUtil.getChunkType(chunk) == ChunkStatusType.LEGEND_TYPE) {
                    continue;
                }
                String[] weight = {"0.0"};
                if (isChunkEndWithPersion(chunk, weight, false)) {
                    numberWeights.add(weight[0]);
                    legendNumberSuffixs.add(chunk);
                }
                //保存围绕在扇形周围的文字块
                else if(chart.legends.size()==0 && ChunkUtil.getChunkType(chunk) == ChunkStatusType.UNKNOWN_TYPE
                        && candidateLegends.size() >= chart.pieInfo.parts.size()){
                    textAroundPie.add(chunk);
                }
            }
        }

        // 判断是否数字的个数和扇形图的个数相等，不相等则做过滤
        if (legendNumberSuffixs.size() >= chart.pieInfo.parts.size() && legendNumberSuffixs.size() > 1 ) {
            List allCombin = new ArrayList();
            getCombination(numberWeights, chart.pieInfo.parts.size(), new ArrayList(), numberWeights.size() -1, allCombin);
            int index = -1;
            List<Double>  weightMinList = new ArrayList<>(); //用于记录函数中最小的weight和
            List<String> curAryMin = new ArrayList<>();      //用于记录产生最小weight时的curAry
            List<String> curWeightsMin = new ArrayList<>();  //用于记录产生最小weight时的curWeights
            double weightMin = 0;                            //记录最小的weight和
            boolean firstIn = true;                          //第一次进入的标志位
            for (int i = 0 ; i < allCombin.size(); i++) {
                List curAry = (List) allCombin.get(i);
                List<String> curWeights = new ArrayList();
                double curTotal = 0;
                for (Object curWeight : curAry){
                    double data = parseToDouble((String)curWeight);
                    curTotal += data;
                }
                if (curTotal <= 0) {
                    continue;
                }
                for (Object curWeight : curAry) {
                    double data = parseToDouble((String)curWeight);
                    curWeights.add(String.valueOf(data / curTotal));
                }
                matchAllPiePartWeight(chart, curWeights, weightMinList);
                //遍历直到选出weight差距最小的
                if(firstIn){
                    weightMin = weightMinList.get(weightMinList.size()-1);
                    curWeightsMin = curWeights;
                    curAryMin = curAry;
                    firstIn = false;
                }
                else{
                    if(weightMinList.get(weightMinList.size()-1) < weightMin){
                        weightMin = weightMinList.get(weightMinList.size()-1);
                        curAryMin = curAry;
                        curWeightsMin = curWeights;
                    }
                }
                //weightMinList.clear();
                if(i < allCombin.size()-1){
                    continue;
                }

                //sum = total;
                numberWeights = curWeightsMin;
                List<TextChunk> legendtmpNumberSuffixs = new ArrayList<>();
                // 当前这个组合符合所有扇形的比例
                for (Object curWeight : curAryMin){
                    // TODO: 这里没有处理2个图例数字完全一样的情况，如果完全一样，那么会有一个
                    // 图例文本被提交重复，后果未知.
                    for (TextChunk chk : legendNumberSuffixs) {
                        //int id = chk.getText().indexOf((String)curWeight);
                        String []weightmp={"0.0"};
                        if(isChunkEndWithPersion(chk, weightmp, false) &&
                                (0 ==weightmp[0].compareToIgnoreCase((String)curWeight)) ) {
                            legendtmpNumberSuffixs.add(chk);
                            legendNumberSuffixs.remove(chk);
                            break;
                        }
                    }
                }
                legendNumberSuffixs = legendtmpNumberSuffixs;
                for (TextChunk tmp : legendNumberSuffixs) {
                    candidateLegends.remove(tmp);
                }
                break;
            }
        }
        else { // 如果数字少于扇形个数，没意义，不处理
            legendNumberSuffixs.clear();
        }


        //

        //TODO: 目前还没有对数字（非百分比）的扇形说明文字做区域扩展！！！
        // do ite...
        // 合并百分数图例
        if (legendNumberSuffixs.size() > 0) {
            List<TextChunk> newsuffixs = new ArrayList<>();
            List<TextChunk> newcandiatelegends = new ArrayList<>();
            expansionPieChartLegendChunk(chart,legendNumberSuffixs, candidateLegends, newsuffixs, newcandiatelegends, false);
            legendNumberSuffixs = newsuffixs;
            candidateLegends= newcandiatelegends;
        }

        // 如果没有任何数字参考信息 则就近合并
        // TODO: 这里需要条件更严格一些：有数字、百分比图例但是是纯数字、纯百分比
        // 注意：计算到这里的 时候，图例已经找到了，副标题已经提取了，但是主标题还在 "candidateLegends"里面
        if (legendNumberSuffixs.size() == 0 && legendsuffixs.size() == 0 && chart.legends.size() == 0 && candidateLegends.size() >= chart.pieInfo.parts.size() && !candidateLegends.isEmpty()) {
            int n = candidateLegends.size();
            for (int i = 0; i < n; i++) {
                TextChunk chunk = candidateLegends.get(i);
                boolean canExpand = expandChunkVertical(chunk, candidateLegends, i + 1, i + 1,
                        false, ChunkStatusType.LEGEND_TYPE, false);
                if (canExpand && i + 1 < n) {
                    groupTwoTextChunk(chunk, candidateLegends.get(i + 1), false);
                    ChunkUtil.setChunkType(chunk, ChunkStatusType.LEGEND_TYPE);
                    candidateLegends.set(i + 1, chunk);
                } else {
                    legendsuffixs.add(chunk);
                }
            }
            int nparts = chart.pieInfo.parts.size();
            if (legendsuffixs.size() == nparts) {
                for (int i = 0; i < nparts; i++) {
                    Chart.PieInfo.PiePartInfo part = chart.pieInfo.parts.get(i);
                    TextChunk chunklegend = legendsuffixs.get(i);
                    part.text = chunklegend.getText();
                    chart.pieInfoLegendsChunks.add(chunklegend);
                }
            }
            return;
        }

        float tolerance = 0.055f;
        // 将扩展至最大的TextChunk的内容作为图例的内容
        for (int i = 0; i < legendsuffixs.size(); i++) {
            String legendText = "";
            TextChunk chunklegend = legendsuffixs.get(i);
            legendText = chunklegend.getText();
            String weight = persionWeights.get(i);
            weight =  weight.substring(0, weight.indexOf('%'));
            double w = parseToDouble(weight);
            w = w / 100.0;

            ChunkUtil.setChunkType(chunklegend, ChunkStatusType.LEGEND_TYPE);
            if (!matchPiePartWeight(chart, w, chunklegend, true, true, tolerance, legendText)
                    && w < 0.05 ) { // 只有小于5%的才补充上，一般饼图不会遗漏>5%的扇形吧???
                Chart.PieInfo.PiePartInfo part = new Chart.PieInfo.PiePartInfo();
                part.color = new Color(0,0,255);
                part.text = legendText;
                part.weight = w;
                chart.pieInfo.parts.add(part);
            } // end if
            chart.pieInfoLegendsChunks.add(chunklegend);
        } // end for i

        for (int i = 0; i < legendNumberSuffixs.size(); i++) {
            TextChunk chunklegend = legendNumberSuffixs.get(i);
            double w = parseToDouble(numberWeights.get(i));

            ChunkUtil.setChunkType(chunklegend, ChunkStatusType.LEGEND_TYPE);
            matchPiePartWeight(chart, w, chunklegend, true, false, tolerance, chunklegend.getText());

        } // end for i

        //把围绕饼图的文字信息合并进chart.pieInfo.parts对应块的“text”中
        double Ratio = 0.0;
        boolean orient = false;
        double ratioMax = 0;
        int ratioMaxIndex =0;
        if (chart.pieInfo.parts.size() > 0){
            orient = orientation(chart.pieInfo.parts.get(0).startAngle,chart.pieInfo.parts.get(0).endAngle,chart.pieInfo.parts.get(0).weight);
        }
        //统计出现次数最多的文字类型，在融合时只融合该类型
        int textStyle = getMaxUsedStyle(textAroundPie);
        List<TextChunk> textTmpAroundPie = new ArrayList<>();
        boolean isTextMore = false;           //文字块个数多于扇形块才开启字体统计，否则不统计字体
        if(textAroundPie.size()>chart.pieInfo.parts.size()){
            isTextMore = true;
        }
        for (int i = textAroundPie.size()-1; i >= 0; i--){
            textTmpAroundPie.add(textAroundPie.get(i));
            if(isTextMore && getMaxUsedStyle(textTmpAroundPie) != textStyle){
                textTmpAroundPie.clear();
                continue;
            }
            textTmpAroundPie.clear();
            for(int j = 0; j < chart.pieInfo.parts.size(); j++){
                Ratio = textRatioParts(textAroundPie.get(i), chart.pieInfo.parts.get(j), orient);
                if(Ratio == -1){
                    break;
                }
                if(Ratio>ratioMax){
                    ratioMax=Ratio;
                    ratioMaxIndex=j;
                }
            }
            if(Ratio != -1 || ratioMax>1E-4){
                chart.pieInfo.parts.get(ratioMaxIndex).text = textAroundPie.get(i).getText()+" "+chart.pieInfo.parts.get(ratioMaxIndex).text;
            }
            ratioMax = 0;
            ratioMaxIndex = 0;
        }

    }

    /**
     *根据提供的绝对角度和占比，判断2个绝对角度的旋转方向（从开始角度到停止角度的方向）
     * @param startAngle 开始的绝对角度
     * @param endAngle 结束的绝对角度
     * @param weight 角度占比（占整个圆的比例）
     * @return false为逆时针，true为顺时针
     */
    public static boolean orientation(double startAngle, double endAngle, double weight){
        double diffAngle =0.0;
        if(startAngle >= endAngle){
            diffAngle = startAngle - endAngle;
            if(Math.abs(diffAngle/(2*Math.PI)-weight)<0.01){
                return false;
            }else{
                return true;
            }
        }else{
            diffAngle = (startAngle+Math.PI) + (Math.PI-endAngle);
            if(Math.abs(diffAngle/(2*Math.PI)-weight)<0.01){
                return false;
            }else{
                return true;
            }
        }
    }

    /**
     * 计算文字块与扇形块重合角度，占扇形块总角度的比值
     * @param textAround 文字块
     * @param part 扇形块
     * @param orient 扇形块旋转方向
     * @return 角度占扇形块的比值
     */
    public static double textRatioParts(TextChunk textAround, Chart.PieInfo.PiePartInfo part, boolean orient){
        double vec01[] = {textAround.x - part.x, textAround.y - part.y};
        double vec02[] = {textAround.x + textAround.width - part.x, textAround.y + textAround.height - part.y};
        double angleLeft = 0.0;
        double angleRight = 0.0;
        double vecTmp = 0;
        //根据文字块与扇形位置的不同，使用不同的点计算重合比例
        if((textAround.x<part.x && textAround.y<part.y) ||
                (textAround.x>part.x && textAround.y>part.y)){
            vecTmp = vec01[0];
            vec01[0] = vec02[0];
            vec02[0] = vecTmp;
        }

        //文字距离离扇形太远就忽略
        double distance1 = Math.sqrt(vec01[0] * vec01[0] + vec01[1] * vec01[1]);
        double distance2 = Math.sqrt(vec02[0] * vec02[0] + vec02[1] * vec02[1]);
        if(distance1 >= 1.748*part.r || distance2 >= 1.748*part.r){
            return -1;
        }
        //计算文字块的绝对起止角度
        if(-1E-4<vec01[0] && vec01[0]<1E-4){
            if(vec01[1]>0){
                angleLeft = Math.PI/2.0;
            }else if(vec01[1]<0){
                angleLeft = -Math.PI/2.0;
            }
        }else if(vec01[0] > 0){
            if(vec01[1]>=0){
                angleLeft = Math.atan(vec01[1]/vec01[0]);
            }else{
                angleLeft = Math.atan(vec01[1]/vec01[0]);
            }
        }else if(vec01[0] < 0){
            if(vec01[1]>=0){
                angleLeft = Math.atan(vec01[1]/vec01[0])+Math.PI;
            }else{
                angleLeft = Math.atan(vec01[1]/vec01[0])-Math.PI;
            }
        }
        if(-1E-4<vec02[0] && vec02[0]<1E-4){
            if(vec02[1]>0){
                angleRight = Math.PI/2.0;
            }else if(vec02[1]<0){
                angleRight = -Math.PI/2.0;
            }
        }else if(vec02[0] > 0){
            if(vec02[1]>=0){
                angleRight = Math.atan(vec02[1]/vec02[0]);
            }else{
                angleRight = Math.atan(vec02[1]/vec02[0]);
            }
        }else if(vec02[0] < 0){
            if(vec02[1]>=0){
                angleRight = Math.atan(vec02[1]/vec02[0])+Math.PI;
            }else{
                angleRight = Math.atan(vec02[1]/vec02[0])-Math.PI;
            }
        }
        //计算文字块的角度占比
        double fcos = (vec01[0] * vec02[0] + vec01[1] * vec02[1])/
                (Math.sqrt(vec01[0] * vec01[0] + vec01[1] * vec01[1])*Math.sqrt(vec02[0] * vec02[0] + vec02[1] * vec02[1]));
        fcos = Math.abs(fcos);
        fcos = (fcos <= 1.001 && fcos >= 0.999) ? 1.0 : fcos;
        double angle=Math.acos(fcos);
        //保证文字块的方向与扇形的方向一致
        boolean orientText = false;
        orientText=orientation(angleLeft,angleRight,angle/(2*Math.PI));
        double angleTemp;
        if(orient != orientText){
            angleTemp = angleLeft;
            angleLeft = angleRight;
            angleRight = angleTemp;
        }

        if(part.weight < 1E-4){
            return 0;
        }

        //double ratio = 0.0;
        if(orient){
            //顺时针
            if(part.startAngle <= part.endAngle){
                if(angleLeft<=part.startAngle && -Math.PI<angleLeft) {
                    if (angleLeft <= angleRight && angleRight <= part.startAngle) {
                        return 0;
                    } else if (part.startAngle < angleRight && angleRight < part.endAngle) {
                        return (angleRight - part.startAngle) / (2 * Math.PI) / part.weight;
                    } else {
                        return 1;
                    }
                }
                else if(part.startAngle<angleLeft && angleLeft<part.endAngle){
                    if(angleLeft<=angleRight && angleRight<=part.endAngle){
                        return (angleRight-angleLeft)/(2*Math.PI)/part.weight;
                    }else if((part.endAngle<angleRight && angleRight<=Math.PI) || (-Math.PI<angleRight && angleRight<=part.startAngle)){
                        return (part.endAngle-angleLeft)/(2*Math.PI)/part.weight;
                    }else{
                        return (angleRight-part.startAngle+part.endAngle-angleLeft)/(2*Math.PI)/part.weight;
                    }
                }
                else if(part.endAngle<=angleLeft && angleLeft<= Math.PI){
                    if((angleLeft<=angleRight && angleRight<=Math.PI) || (-Math.PI<angleRight && angleRight<= part.startAngle)){
                        return 0;
                    }else if(part.startAngle<angleRight && angleRight<=part.endAngle){
                        return (angleRight-part.startAngle)/(2*Math.PI)/part.weight;
                    }else{
                        return 1;
                    }
                }
            }else{
                if(part.startAngle<=angleLeft && angleLeft<=Math.PI){
                    if(angleLeft<=angleRight && angleRight<=Math.PI){
                        return (angleRight-angleLeft)/(2*Math.PI)/part.weight;
                    }else if(-Math.PI<angleRight && angleRight<=part.endAngle){
                        return (Math.PI-angleLeft+angleRight+Math.PI)/(2*Math.PI)/part.weight;
                    }else if(part.endAngle<angleRight && angleRight<=part.startAngle){
                        return (part.endAngle+Math.PI+Math.PI-angleLeft)/(2*Math.PI)/part.weight;
                    }else{
                        return (part.endAngle+Math.PI+Math.PI-angleLeft+angleRight-part.startAngle)/(2*Math.PI)/part.weight;
                    }
                }
                else if(-Math.PI<angleLeft && angleLeft< part.endAngle){
                    if(angleLeft<=angleRight && angleRight<= part.endAngle){
                        return (angleRight-angleLeft)/(2*Math.PI)/part.weight;
                    }else if(part.endAngle<angleRight && angleRight<=part.startAngle){
                        return (part.endAngle-angleLeft)/(2*Math.PI)/part.weight;
                    }else if(part.startAngle<angleRight && angleRight<=Math.PI){
                        return (part.endAngle-angleLeft+angleRight-part.startAngle)/(2*Math.PI)/part.weight;
                    }else{
                        return (part.endAngle-angleLeft+Math.PI-part.startAngle+angleRight+Math.PI)/(2*Math.PI)/part.weight;
                    }
                }
                else if(part.endAngle<=angleLeft && angleLeft<part.startAngle){
                    if(angleLeft<=angleRight && angleRight<=part.startAngle){
                        return 0;
                    }else if(part.startAngle<angleRight && angleRight<=Math.PI){
                        return (angleRight-part.startAngle)/(2*Math.PI)/part.weight;
                    }else if(-Math.PI<angleRight && angleRight<=part.endAngle){
                        return (Math.PI-part.startAngle+angleRight+Math.PI)/(2*Math.PI)/part.weight;
                    }else{
                        return 1;
                    }
                }
            }
        }
        else{
            //逆时针
            if(part.endAngle <= part.startAngle){
                if(angleLeft>=part.startAngle && Math.PI>=angleLeft) {
                    if (angleLeft >= angleRight && angleRight >= part.startAngle) {
                        return 0;
                    } else if (part.startAngle > angleRight && angleRight > part.endAngle) {
                        return (-angleRight + part.startAngle) / (2 * Math.PI) / part.weight;
                    } else {
                        return 1;
                    }
                }
                else if(part.startAngle>angleLeft && angleLeft>part.endAngle){
                    if(angleLeft>=angleRight && angleRight>=part.endAngle){
                        return (-angleRight+angleLeft)/(2*Math.PI)/part.weight;
                    }else if((part.endAngle>angleRight && angleRight>-Math.PI) || (Math.PI>=angleRight && angleRight>=part.startAngle)){
                        return (-part.endAngle+angleLeft)/(2*Math.PI)/part.weight;
                    }else{
                        return (-angleRight+part.startAngle-part.endAngle+angleLeft)/(2*Math.PI)/part.weight;
                    }
                }
                else if(part.endAngle>=angleLeft && angleLeft> -Math.PI){
                    if((angleLeft>=angleRight && angleRight>-Math.PI) || (Math.PI>=angleRight && angleRight>= part.startAngle)){
                        return 0;
                    }else if(part.startAngle>angleRight && angleRight>=part.endAngle){
                        return (-angleRight+part.startAngle)/(2*Math.PI)/part.weight;
                    }else{
                        return 1;
                    }
                }
            }else{
                if(part.startAngle>=angleLeft && angleLeft>-Math.PI){
                    if(angleLeft>=angleRight && angleRight>-Math.PI){
                        return (-angleRight+angleLeft)/(2*Math.PI)/part.weight;
                    }else if(Math.PI>=angleRight && angleRight>=part.endAngle){
                        return (Math.PI+angleLeft-angleRight+Math.PI)/(2*Math.PI)/part.weight;
                    }else if(part.endAngle>angleRight && angleRight>=part.startAngle){
                        return (-part.endAngle+Math.PI+Math.PI+angleLeft)/(2*Math.PI)/part.weight;
                    }else{
                        return (-part.endAngle+Math.PI+Math.PI+angleLeft-angleRight+part.startAngle)/(2*Math.PI)/part.weight;
                    }
                }
                else if(Math.PI>=angleLeft && angleLeft> part.endAngle){
                    if(angleLeft>=angleRight && angleRight>= part.endAngle){
                        return (-angleRight+angleLeft)/(2*Math.PI)/part.weight;
                    }else if(part.endAngle>angleRight && angleRight>=part.startAngle){
                        return (-part.endAngle+angleLeft)/(2*Math.PI)/part.weight;
                    }else if(part.startAngle>angleRight && angleRight>-Math.PI){
                        return (-part.endAngle+angleLeft-angleRight+part.startAngle)/(2*Math.PI)/part.weight;
                    }else{
                        return (-part.endAngle+angleLeft+Math.PI+part.startAngle-angleRight+Math.PI)/(2*Math.PI)/part.weight;
                    }
                }
                else if(part.endAngle>=angleLeft && angleLeft>part.startAngle){
                    if(angleLeft>=angleRight && angleRight>=part.startAngle){
                        return 0;
                    }else if(part.startAngle>angleRight && angleRight>-Math.PI){
                        return (-angleRight+part.startAngle)/(2*Math.PI)/part.weight;
                    }else if(Math.PI>=angleRight && angleRight>=part.endAngle){
                        return (Math.PI+part.startAngle-angleRight+Math.PI)/(2*Math.PI)/part.weight;
                    }else{
                        return 1;
                    }
                }
            }
        }
        return 0;
    }

    private static void getCombination(List list, int number, List paramList,int index, List combList){
        for(int i=index; i>=0; i--){
            List _subList = new ArrayList();
            _subList.addAll(paramList);
            _subList.add(list.get(i));
            if(number == 1){
                combList.add(_subList);
            }else{
                getCombination(list, number-1,_subList,i-1,combList);
            }
        }
    }

    /**
     *
     * @param chart
     * @param weights
     * @return  是否所有weights都能在饼图中找到对应的扇形
     */
    private static boolean matchAllPiePartWeight(Chart chart, List<String> weights, List<Double> weightMinList) {
        double distAccum = 0;
        boolean findAll = true;
        if (weights.size() == 0) {
            return false;
        }

        List<Double> tmpWeights = new ArrayList<>();
        for (String dd : weights) {
            tmpWeights.add(parseToDouble(dd));
        }
        for ( Chart.PieInfo.PiePartInfo pInfo : chart.pieInfo.parts) {
            double distMin = 0.0;
            double dist = 0.0;
            int iMin = -1;
            boolean find = false;
            for (int i = 0 ; i < tmpWeights.size(); i++)  {
                // equals 函数默认零误差阀值0.1太大　故扩大100倍即被叫百分比的数值
                if (ChartUtils.equals(pInfo.weight * 100.0, tmpWeights.get(i) * 100.0)) {
                    iMin = i;
                    find = true;
                    break;
                }
                // 找出最相近的值
                dist = Math.abs(pInfo.weight - tmpWeights.get(i));
                if (iMin == -1 || dist < distMin) {
                    distMin = dist;
                    iMin = i;
                }
            }
            distAccum += distMin;
            // 如果没有找到或是最接近的误差大于给定阀值　则返回 false
            if (find || (!find && iMin != -1 && distMin < 0.055)) {
                tmpWeights.remove(iMin);
            }
            else {
                findAll = false;
                distAccum += distMin;    //累计增倍，防止干扰
                break;
            }
        }
        weightMinList.add(distAccum);
        return findAll;
    }

    /**
     * 给定权重信息　在Chart中的饼图信息集内部寻找最接近权重的部分
     * 存在多个部分的权重一样的情况这时不构成大的影响 后续改进新的匹配方式可以克服这一点
     *
     * @param chart  给定Chart
     * @param weight 给定权重
     * @param chunk   对应的信息字符串
     * @return 如果能找到匹配的　则返回 true
     */
    private static boolean matchPiePartWeight(Chart chart, double weight, TextChunk chunk, boolean mergeText, boolean percent, double tolerance, String origText) {
        int n = chart.pieInfo.parts.size();
        double distMin = 0.0;
        double dist = 0.0;
        int iMin = -1;
        String text = chunk.getText();
        double x = chunk.getCenterX();
        double y = chunk.getCenterY();
        for (int i = 0; i < n; i++) {
            Chart.PieInfo.PiePartInfo part = chart.pieInfo.parts.get(i);
            // 判断是否已经匹配过标记百分比
            /*
            if (part.text.length() >= 1) {
                continue;
            }*/
            if (percent && part.hasPercentText) {
                continue;
            }
            if (!percent && part.hasNumberText) {
                continue;
            }

            // equals 函数默认零误差阀值0.1太大　故扩大100倍即被叫百分比的数值
            if ( (ChartUtils.pointInPath(part.path, x, y) && Math.abs(part.weight - weight) <= 0.02) || ChartUtils.equals(part.weight * 100.0, weight * 100.0)) {
                if (mergeText) {
                    if (part.text != null && !part.text.isEmpty())
                    {
                        part.text += "  ";
                        part.text += text;
                    }
                    else {
                        part.text = text;
                    }

                    part.weight = weight;
                    if (percent) {
                        part.hasPercentText = true;
                        part.percentText =origText;
                    }
                    else {
                        part.hasNumberText = true;
                        part.numberText = origText;
                    }
                }

                return true;
            }

            // 找出最相近的值
            dist = Math.abs(part.weight - weight);
            if (iMin == -1 || dist < distMin) {
                distMin = dist;
                iMin = i;
            }
        } // end for i

        // 如果没有找到或是最接近的误差大于给定阀值　则返回 false
        if (iMin == -1 || distMin >= tolerance) {
            return false;
        }

        // 设置最佳匹配部分的信息
        Chart.PieInfo.PiePartInfo part = chart.pieInfo.parts.get(iMin);
        if (mergeText) {
            if (part.text != null && !part.text.isEmpty())
            {
                part.text += "  ";
                part.text += text;
            }
            else {
                part.text = text;
            }
            //以百分号的weight为准
            if(!part.hasPercentText){
                part.weight = weight;
            }
            if (percent) {
                part.hasPercentText = true;
                part.percentText =origText;
            }
            else {
                part.hasNumberText = true;
                part.numberText = origText;
            }
        }
        return true;
    }


    /**
     * 判断给定的TextChunk是否以百分数结尾　一般饼图的隐式图例都具有这种规律
     *
     * @param chunk  给定的TextChunk
     * @param weight 百分数字符串
     * @return 如果满足规律　则返回 True
     */
    public static boolean isChunkEndWithPersion(
            TextChunk chunk, String[] weight, boolean hasSign) {
        String text = chunk.getText();
        Pattern numberPattern = null;
        if (hasSign) {
            numberPattern = RegularMatch.getInstance().regExFloatPercentPattern;
        } else {
            numberPattern = RegularMatch.getInstance().regExIntFloatNumberPattern;
        }
        Matcher matcher = numberPattern.matcher(text);
        if (matcher.find()) {
            weight[0] = matcher.group();
            return true;
        }
        return false;
    }

    private static double  parseToDouble(String str) {
        if (str.indexOf('.') == 0) {
            str = '0'+ str;
        }
        // 去掉逗号
        String []parts= str.split(",");
        StringBuilder sb = new StringBuilder();
        for (String part:parts) {
            sb.append(part);
        }
        return Double.parseDouble(sb.toString());
    }

    /**
     *
     * @param chunk
     * @param hasSign  是否包括百分号
     * @return  返回数值 5.1% 返回 5.1
     */
    private static double getChunkFirstNumberValue(TextChunk chunk, boolean hasSign) {
        String text = chunk.getText().trim();
        Pattern numberPattern = null;
        if (hasSign) {
            numberPattern = RegularMatch.getInstance().regExFloatPercentPattern;
        } else {
            numberPattern = RegularMatch.getInstance().regExFloatPattern;
        }
        Matcher matcher = numberPattern.matcher(text);
        if (matcher.find()) {
            String valueStr = matcher.group();
            if(hasSign) {
                valueStr = valueStr.substring(0, valueStr.indexOf('%'));
            }

            double value = parseToDouble(valueStr);
            return value;
        }
        return 0;
    }

    /**
     * 判断给定的TextChunk是否以百分数结尾　一般饼图的隐式图例都具有这种规律
     *
     * @param chunk  给定的TextChunk
     * @return 1: %的位置: -2 没有找到  -1： 完全匹配
     */
    public static int getChunkPersionPos(
            TextChunk chunk, boolean hasSign) {
        String text = chunk.getText().trim();
        Pattern numberPattern = null;
        if (hasSign) {
            numberPattern = RegularMatch.getInstance().regExFloatPercentPattern;
        } else {
            numberPattern = RegularMatch.getInstance().regExFloatPattern;
        }
        Matcher matcher = numberPattern.matcher(text);
        if (matcher.find()) {
            if (matcher.end() == text.length() && matcher.start() == 0) {
                return -1;
            }
            if (matcher.end() == text.length())
                return matcher.end();
            else
                return matcher.start();
        }
        return -2;
    }

    public static enum RelativePos {
        left,
        right,
        top,
        bottom,
        inner,
        unkown
    }

    /**
     * 对给定的TextChunk, 判定另外一个TextChunk相对于他的位置
     *
     * @param origin 　作为标准位置的text
     * @param comparedText  用来比较的text

     * @return comparedText 相对于origin的位置:上方，下方，左边，右边，没有关联
     */
    private static RelativePos getTextLineRelativePos(TextChunk origin, TextChunk comparedText){

        // 找出当前TextChunk的中心点靠上的位置　然后向上扩展一定的距离　经验值
        double h = ChunkUtil.getMaxFontHeight(origin);
        double xc = origin.getCenterX();
        double xL = origin.getFirstElement().getCenterX();
        double xR = origin.getLastElement().getCenterX();
        double y = origin.getTop();


        float coef = 0.5f;
        if (StringUtils.isBlank(comparedText.getText())) {
            return RelativePos.unkown;
        }

        // 判断两段文字是否在同一行：两段文字Y轴方向上 重叠的高度大于较矮文字的50%，就当作是同一行
        float yOverlappedLength = 0;

        if (origin.getTop() >= comparedText.getTop() && origin.getTop() <= comparedText.getBottom()) {
            yOverlappedLength = Math.min(comparedText.getBottom(), origin.getBottom()) - origin.getTop();
        }
        else if (origin.getBottom() >= comparedText.getTop() && origin.getBottom() <= comparedText.getBottom()){
            yOverlappedLength = origin.getBottom() - Math.max(comparedText.getTop(), origin.getTop());
        }
        else if (origin.getTop() <= comparedText.getTop() && origin.getBottom() >= comparedText.getBottom()) {
            yOverlappedLength = (float) comparedText.getHeight();
        }
        double yOverlappedPos = yOverlappedLength / Math.min(origin.getHeight(), comparedText.getHeight());
        boolean isSameLine =  yOverlappedPos > 0.5 ? true : false;

        // 两段文字 Y轴方向高度没有相交，X方向上，有一段文字>90的宽度在另一段文字X轴范围内
        // 就判定两段文字是上下行
        float xOverlappedLength = 0;
        if (origin.getLeft() >= comparedText.getLeft() && origin.getLeft() <= comparedText.getRight()) {
            xOverlappedLength = Math.min(comparedText.getRight(), origin.getRight()) - origin.getLeft();
        }
        else if(origin.getRight() >= comparedText.getLeft() && origin.getRight() <= comparedText.getRight()) {
            xOverlappedLength = origin.getRight() - Math.max(comparedText.getLeft(), origin.getLeft());
        }
        else if (origin.getLeft() <= comparedText.getLeft() && origin.getRight() >= comparedText.getRight()) {
            xOverlappedLength = (float) comparedText.getWidth();
        }
        double xOverlappedPos = xOverlappedLength / Math.min(origin.getWidth(), comparedText.getWidth());
        boolean isSameColum = xOverlappedPos > 0.8 ? true : false;
        if (yOverlappedPos < 0.5 && isSameColum && origin.getBottom() >= comparedText.getBottom()) {
            return RelativePos.top;
        }
        else if (yOverlappedPos < 0.5 && isSameColum && origin.getTop() <= comparedText.getTop()) {
            return RelativePos.bottom;
        }

        if (isSameLine) {
            if (origin.getRight() <= comparedText.getLeft()) {
                return RelativePos.right;
            }
            else if (origin.getLeft() >= comparedText.getRight()) {
                return RelativePos.left;
            }
        }

        return RelativePos.unkown;
    }

    /**
     *
     * @param chunk  起始
     * @param targetChunk 目标chunk
     * @param pos  targetChunk 相对于 chunk的位置
     * @return  相对距离
     */
    private static float getTextChunkDistance(TextChunk chunk, TextChunk targetChunk, RelativePos pos) {
        if (pos == RelativePos.left) {
            return chunk.getLeft() - targetChunk.getRight();
        }
        else if (pos == RelativePos.right) {
            return targetChunk.getLeft() - chunk.getRight();
        }
        else if (pos == RelativePos.top) {
            return chunk.getTop() - targetChunk.getBottom();
        }
        else if(pos == RelativePos.bottom) {
            return targetChunk.getTop() - chunk.getBottom();
        }

        return -1;
    }


    /**
     * 找到指定方向距离chunk最近的 chunksSuffix 元素
     *
     * @param chunksSuffix 　给定待扩展TextChunk序列
     * @param chunk       候选扩展元素集
     * @param pos
     * @param scale  最远距离不能超过多少字符距离
     * @return -1: 没有符合条件的  >-1: chunksSuffix 索引
     */
    private static int getTheNearestChunk(
            List<TextChunk> chunksSuffix,
            TextChunk chunk,
            RelativePos pos,
            float scale) {
        double minDistance = -1.0f;
        double targetHeight = 0;
        int index = -1;
        for (int i = 0 ; i < chunksSuffix.size(); i++ ) {
            TextChunk currChunk = chunksSuffix.get(i);
            if (getTextLineRelativePos(chunk, currChunk) != pos) {
                continue;
            }
            double distance = getTextChunkDistance(chunk, currChunk, pos);
            if (index == -1 || distance <= minDistance) {
                minDistance = distance;
                //targetHeight = currChunk.getHeight();
                targetHeight = currChunk.getTextHeight();
                index = i;
            }
        }

        double length = 0.0f;
        if (pos == RelativePos.left || pos == RelativePos.right) {
            length = chunk.getMaxTextWidth();
        }
        else {
            // 字符高度 取上下两个文本的最高高度
            length = Math.max(chunk.getTextHeight(), targetHeight);
        }

        if (minDistance > scale * length) {
            return -1;
        }
        return index;
    }

    private static List<TextChunk> deepCopyAry(List<TextChunk> srcary) {
        List<TextChunk> newary = new ArrayList<>();
        for (TextChunk chunk : srcary) {
            TextChunk newchunk = new TextChunk(chunk);
            newary.add(newchunk);
        }
        return newary;
    }

    /**
     *
     * @param chunks
     * @return 获取一组TextChunk中哪一种字体分布的更广泛
     */
    private static int getMaxUsedStyle(List<TextChunk> chunks) {
        // 找到chunks里面
        int maxStyle = 0;
        Map<Integer, Integer> chunkFonts = new HashMap<>();
        for ( TextChunk tchunk : chunks) {
            //Set<String> fonts = tchunk.getFontNames();
            Set<Integer> styles = tchunk.getTextStyles();
            for (Integer style: styles) {
                if (chunkFonts.containsKey(style)) {
                    chunkFonts.put(style,chunkFonts.get(style) + 1);
                }
                else {
                    chunkFonts.put(style,1);
                }
            }
        }

        int maxCount = 0;
        for (Map.Entry<Integer, Integer> entry: chunkFonts.entrySet()) {
            if (entry.getValue() > maxCount) {
                maxCount = entry.getValue();
                maxStyle = entry.getKey();
            }
        }
        return maxStyle;
    }

    /**
     *
     * @param chart
     * @param chunks
     * @return  判断所给的文字在饼图的哪个位置:以圆心为中点坐XY轴，如果文字都在X或Y轴的同一面，就返回:left,right,top,bottom,
     *          否则unkonwn,其实就是判断文字是否围绕着饼图，还是常规的图例布局方式
     */
    private static RelativePos getTextLegendPosition(Chart chart, List<TextChunk> chunks) {
        int pieCount = chart.pieInfo.parts.size();
        if (chart.pieInfo.parts.size() == 0 ) {
            return RelativePos.unkown;
        }
        double x = chart.pieInfo.parts.get(0).x;
        double y = chart.pieInfo.parts.get(0).y;
        double r = chart.pieInfo.parts.get(0).r;
        int leftCount = 0;
        int rightCount = 0;
        int topCount = 0;
        int bottomCount = 0;
        for (TextChunk chunk : chunks) {
            if (chunk.getTop() > y) {
                bottomCount++;
            }
            if (chunk.getBottom() < y) {
                topCount++;
            }
            if (chunk.getLeft() > x) {
                rightCount++;
            }
            if (chunk.getRight() < x) {
                leftCount++;
            }
        }
        int chunkCount = chunks.size();
        if (bottomCount == chunkCount) {
            return RelativePos.bottom;
        }
        if (topCount == chunkCount){
            return RelativePos.top;
        }
        if (leftCount == chunkCount) {
            return RelativePos.left;
        }
        if (rightCount == chunkCount) {
            return RelativePos.right;
        }

        //chart.
        return RelativePos.unkown;
    }

    /**
     * 对给定的TextChunk从中心位置开始往上适当的扩展　找到合理有效的新的组
     *
     * @param chunksSuffix 　给定待扩展TextChunk序列
     * @param chunks       候选扩展元素集
     * @param percent      是根据%扩展还是根据纯数字扩展
     * @return 如果指定序号的待扩展TextChunk找到合适的扩展元素　则返回 true
     * TODO: 文字合并，还需要加上遇到 图例图形就及时中止的逻辑。防止把临近的其它文字合并进来
     */
    private static boolean expansionPieChartLegendChunk(
            Chart chart,
            List<TextChunk> chunksSuffix,
            List<TextChunk> chunks,
            List<TextChunk> chunksSuffixNew,
            List<TextChunk> chunksNew,
            boolean percent
        ) {
        // 如果为空　则无法扩展　返回 false
        if (chunksSuffix.isEmpty() || chunks.isEmpty()) {
            chunksSuffixNew.addAll(chunksSuffix);
            chunksNew.addAll(chunks);
            return true;
        }

        int maxUsedStyle = getMaxUsedStyle(chunks);
        RelativePos chunksPosWithPie = getTextLegendPosition(chart, chunksSuffix);

        // 先对已经合并好的字符看看能否判断出 "%"位置
        int startCount = 0;
        int endCount = 0;
        int allmatch = 0;
        int inner = 0;
        for (int i = 0; i < chunksSuffix.size(); i++) {
            TextChunk cuChunk = chunksSuffix.get(i);
            int pos = getChunkPersionPos(cuChunk, percent);
            if ( pos == 0) {
                startCount++;
            }
            else if (pos == cuChunk.getText().trim().length()) {
                endCount++;
            }
            else if (pos == -1) {
                allmatch++;
            }
            else if (pos > 0 && pos < cuChunk.getText().trim().length()) {
                inner++;
            }
        }

        // 判断左右合并的距离
        float maxDistanceScale = 50.0f;
        if(chunksPosWithPie == RelativePos.unkown) {
            maxDistanceScale = 3.0f;
        }

        // 首先尝试左合并
        List<TextChunk> chunksLeft = deepCopyAry(chunks);
        List<TextChunk> chunksSuffixLeft = deepCopyAry(chunksSuffix);
        //boolean needMergeLeft = false;
        boolean allHaveRightChunk = true;
        for (TextChunk curChunk : chunksSuffixLeft) {
            if (-1 == getTheNearestChunk(chunksLeft,curChunk,RelativePos.right, 30)) {
                allHaveRightChunk = false;
                break;
            }
        }
        if (allHaveRightChunk) {
            mergePieLegendChunksByPos(chunksSuffixLeft, chunksLeft, RelativePos.right, maxDistanceScale);
        }

        //  尝试向右合并
        List<TextChunk> chunksRight = deepCopyAry(chunks);
        List<TextChunk> chunksSuffixRight = deepCopyAry(chunksSuffix);
        boolean allHaveLeftChunk = true;
        for (TextChunk curChunk : chunksSuffixRight) {
            if (-1 == getTheNearestChunk(chunksRight,curChunk,RelativePos.left, 30)) {
                allHaveLeftChunk = false;
                break;
            }
        }
        if (allHaveLeftChunk) {
            mergePieLegendChunksByPos(chunksSuffixRight, chunksRight, RelativePos.left, maxDistanceScale);
        }

        // 判断是否只剩下一行多余的，如果这一行多余，并且这一行的文字风格和其它的明显不一样，
        // 则这一行可能不是图例，而是对图例的说明，扔掉这一行
        if (chunksPosWithPie != RelativePos.unkown && chunksLeft.size() == 1) {
            if (!chunksLeft.get(0).getTextStyles().contains(maxUsedStyle)) {
                chunksSuffixNew.addAll(chunksSuffixLeft);
                chunksNew.addAll(chunksLeft);
                return true;
            }
        }
        if (chunksPosWithPie != RelativePos.unkown && chunksRight.size() == 1) {
            if (!chunksRight.get(0).getTextStyles().contains(maxUsedStyle)) {
                chunksSuffixNew.addAll(chunksSuffixRight);
                chunksNew.addAll(chunksRight);
                return true;
            }
        }

        // 判断左右合并的效果：剩余文本少的判断为效果好，如果一样，那么任选一种
        List<TextChunk> chunksStep1 = null;
        List<TextChunk> chunksSuffixStep1 = null;
        int leftScore = 0;
        int rightScore = 0;
        if (chunksLeft.size() < chunksRight.size()) {
            leftScore = 100;

        }
        else if(chunksLeft.size() > chunksRight.size()){
            rightScore = 100;
        }
        else { // 剩余的相等，那么根据 % 位置去判断
            if(startCount > 0 && endCount ==0 && inner ==0) {
                leftScore =100;
            }
            else if(endCount > 0 && startCount == 0) {
                rightScore = 100;
            }
            else {

            }
        }
        // 默认优先 % 在图例的左侧开始位置
        if (leftScore > rightScore || leftScore == rightScore) {
            chunksStep1 = chunksLeft;
            chunksSuffixStep1 = chunksSuffixLeft;
        }
        else if (rightScore > leftScore) {
            chunksStep1 = chunksRight;
            chunksSuffixStep1 = chunksSuffixRight;
        }

        List<TextChunk> chunksTop = deepCopyAry(chunksStep1);
        List<TextChunk> chunksSuffixTop = deepCopyAry(chunksSuffixStep1);
        mergePieLegendChunksByPos(chunksSuffixTop, chunksTop, RelativePos.bottom, 1.2f);

        List<TextChunk> chunksBottom = deepCopyAry(chunksStep1);
        List<TextChunk> chunksSuffixBottom = deepCopyAry(chunksSuffixStep1);
        mergePieLegendChunksByPos(chunksSuffixBottom, chunksBottom, RelativePos.top, 1.2f);

        if (chunksTop.size() <= chunksBottom.size() ) {
            chunksSuffixNew.addAll(chunksSuffixTop);
            chunksNew.addAll(chunksTop);
        }
        else {
            chunksSuffixNew.addAll(chunksSuffixBottom);
            chunksNew.addAll(chunksBottom);
        }

        return false;
    }

    /**
     *
     * @param chunksSuffix   已经合并好的图例
     * @param chunks  需要合并到图例的文本
     * @param pos  合并方向: 以chunksSuffix为中心的方向，
     */
    private static void mergePieLegendChunksByPos(List<TextChunk> chunksSuffix, List<TextChunk> chunks, RelativePos pos, float maxDistanceScale/*, boolean columFirst*/) {
        int preChunksCount = chunks.size();
        int currChunksCount = chunks.size();

        /*// 多次迭代合并文本
        do{
            preChunksCount = currChunksCount;
            for (int i = chunkIDs.size() -1 ; i >= 0; i--) {
                TextChunk currChunk = chunkIDs.get(i);
                //if (columFirst && getTheNearestChunk(chunksSuffix, currChunk, RelativePos.bottom, 1.5f))
                int nearestChunkIndex = getTheNearestChunk(chunksSuffix, currChunk, pos, maxDistanceScale);
                if (nearestChunkIndex != -1) {
                    TextChunk targetChunk = chunksSuffix.get(nearestChunkIndex);

                    // 注意合并顺序：上面的合并下面的，左边的合并右边的
                    if (pos == RelativePos.left || pos == RelativePos.top) {
                        targetChunk.merge(currChunk);
                    }
                    else {
                        currChunk.merge(targetChunk);
                        chunksSuffix.set(nearestChunkIndex, currChunk);
                    }
                    chunkIDs.remove(i);
                }
            }
            currChunksCount = chunkIDs.size();
        }
        while(currChunksCount < preChunksCount);*/

        // 多次迭代合并文本
        do{
            preChunksCount = currChunksCount;
            for (int i = 0; i < chunksSuffix.size(); i++) {
                TextChunk suffixChunk = chunksSuffix.get(i);
                int nearestChunkIndex = getTheNearestChunk(chunks, suffixChunk, pos, maxDistanceScale);
                if (nearestChunkIndex != -1) {
                    TextChunk targetChunk = chunks.get(nearestChunkIndex);

                    // 注意合并顺序：上面的合并下面的，左边的合并右边的
                    if (pos == RelativePos.left || pos == RelativePos.top) {
                        targetChunk.merge(suffixChunk);
                        chunksSuffix.set(i, targetChunk);
                    }
                    else {
                        suffixChunk.merge(targetChunk);

                    }
                    chunks.remove(nearestChunkIndex);
                }
            }
            currChunksCount = chunks.size();
        }
        while(currChunksCount < preChunksCount);


    }

    /**
     * 对给定的TextChunk从中心位置开始往上适当的扩展　找到合理有效的新的组
     *
     * @param chunksSuffix 　给定待扩展TextChunk序列
     * @param chunks       候选扩展元素集
     * @param ith          处理待扩展TextChunk的序号
     * @param coef         扩展距离比例值　经验值
     * @return 如果指定序号的待扩展TextChunk找到合适的扩展元素　则返回 true
     */
    private static boolean expansionChunk(
            List<TextChunk> chunksSuffix,
            List<TextChunk> chunks,
            boolean setType,
            ChunkStatusType ctype,
            int ith,
            double coef) {
        // 如果为空　则无法扩展　返回 false
        if (chunksSuffix.isEmpty() || chunks.isEmpty()) {
            return false;
        }
        TextChunk chunkStart = chunksSuffix.get(ith);
        Map<TextChunk, Float> candidateChunks = new HashMap<>();

        // 找出当前TextChunk的中心点靠上的位置　然后向上扩展一定的距离　经验值
        double h = ChunkUtil.getMaxFontHeight(chunkStart);
        double xc = chunkStart.getCenterX();
        double xL = chunkStart.getFirstElement().getCenterX();
        double y = chunkStart.getTop();
        for (int i = 0; i < chunks.size(); i++) {
            TextChunk chunk = chunks.get(i);
            if (StringUtils.isBlank(chunk.getText())) {
                continue;
            }

            // 如果中心和左侧位置往上一点的位置在当前TextChunk内部　则扩展
            if (chunk.intersectsLine(xc, y, xc, y - coef * h) ||
                    chunk.intersectsLine(xL, y, xL, y - coef * h)) {
                //if (chunk.getBounds().contains(xc, y + coef * h) ||
                //        chunk.getBounds().contains(xL, y + coef * h)) {
                candidateChunks.put(chunk, chunkStart.getTop() - chunk.getTop());
            }
        } // end for i

        double minDistance = Double.MAX_VALUE;
        TextChunk toAdd = null;
        for (Map.Entry<TextChunk, Float> entry: candidateChunks.entrySet()) {
            float distance = entry.getValue();
            if(distance < minDistance) {
                minDistance = distance;
                toAdd = entry.getKey();
            }
        }

        if (toAdd != null) {
            String[] weight = {"0.0%"};
            if (isChunkEndWithPersion(chunkStart, weight, true) && chunkStart.getHeight() == chunkStart.getFirstElement().getHeight()) {
                // 高度与单个文字相同 并且以百分号结尾 说明这是第一次添加 这时加入空格
                // TODO 如果 “煤炭产出占比 23%" 情况下 合并 可能会有 bug
                groupTwoTextChunk(toAdd, chunkStart, true);
            } else {
                groupTwoTextChunk(toAdd, chunkStart, false);
            }
            if (setType) {
                ChunkUtil.setChunkType(toAdd, ctype);
            }
            chunksSuffix.set(ith, toAdd);
            chunks.remove(toAdd);
            return true;
        }

        return false;
    }

    /**
     * 将给定的两个TextChunk按照给定的方式合并
     *
     * @param chunkstart 合并的起始
     * @param chunkend   合并的结束
     * @param fillSpace  合并过程中是否对内部元素TextElement进行排序
     */
    public static void groupTwoTextChunk(
            TextChunk chunkstart, TextChunk chunkend, boolean fillSpace) {
        chunkstart.merge(chunkend, fillSpace);
    }

    /**
     * 计算TextChunk的左下点与给定点的距离平方
     *
     * @param textChunk 给定TextChunk
     * @param x         给定点的X坐标
     * @param y         给定点的X坐标
     * @return 距离的平方
     */
    private static double distanceToTextChunk(TextChunk textChunk, float x, float y) {
        return (textChunk.getX() - x) * (textChunk.getX() - x) + (textChunk.getY() - y) * (textChunk.getY() - y);
    }

    /**
     * 解析chart标题
     * 注: 当前Page中的chart标题在上一页的底部位置的情况　还在开发调试中
     *
     * @param textChunks 输入的当前page的chunk集
     * @param charts     当前page的chart集
     */
    public static void selectTitle(
            List<TextChunk> textChunks,
            List<Chart> charts) {
        // 遍历charts 尝试找出标题对象
        for (Chart chart : charts) {
            selectChartTitle(chart, textChunks);
        }

        // 过滤掉部分无效类型Chart
        charts.removeIf(chart -> chart.type == ChartType.UNKNOWN_CHART);

        // 特殊情况的标题需要再次在水平方向尝试扩展
        for (Chart chart : charts) {
            if (StringUtils.isEmpty(chart.title) || chart.titleTextChunk == null) {
                continue;
            }
            TextChunk title = chart.titleTextChunk;
            title = expandHorizon(title, textChunks, false, ChunkStatusType.TITLE_TYPE);
            setChartTitle(chart, title);
        }
    }

    public static boolean selectChartTitle(
            Chart chart, List<TextChunk> textChunks) {
        if (!chart.isChart()) {
            return false;
        }

        // 计算三个候选title区域
        float width = chart.getWidth();
        float height = chart.getHeight();
        float titleHeight = chart.getTitleSearchHeight();

        // 对Chart左上角区域　稍微向左扩展一部分  对于某些Chart在中部位置而标题在左侧位置的情况
        float sw = (float) (width * 0.1f);
        // 左上角区域
        Rectangle areaA = chart.getArea().moveBy(-sw, -titleHeight).resize((float) (width * 0.35), titleHeight + titleHeight * 15 / 100);
        // 上面偏中间
        Rectangle areaB = new Rectangle(areaA)
                .moveBy((float) areaA.getWidth(), 0)
                .resize((float) (width * 0.5), titleHeight);
        // chart内部顶部偏中间
        float h = (float) (height * 0.25);
        Rectangle areaC = new Rectangle(areaA)
                .moveBy((float) areaA.getWidth(), (float) areaA.getHeight())
                .resize((float) (width * 0.5), h);

        // 左轴区域
        Rectangle areaAxisL = chart.getArea().resize(sw, height);
        // 右轴区域
        Rectangle areaAxisR = chart.getArea().moveBy(width, 0).resize(sw, height);

        // 遍历所有的textChunks 　找出三个区域最可能的候选title
        String titleA = null, titleB = null, titleC = null, titleCurrent = null;
        TextChunk chunkA = null, chunkB = null, chunkC = null;
        double subtitleDistance = Double.MAX_VALUE;
        // 存在两行标题的情况　存储后续扩展标题
        List<TextChunk> expandTitles = new ArrayList<>();
        for (TextChunk textChunk : textChunks) {
            ChunkStatusType type = ChunkUtil.getChunkType(textChunk);
            if (type != ChunkStatusType.UNKNOWN_TYPE && type != ChunkStatusType.TITLE_TYPE) {
                // 针对GIC副标题类型优化
                // 筛选出距离最近的副标题类型
                if (type == ChunkStatusType.SUBTITLE_TYPE) {
                    Point2D textP = textChunk.getCenter();
                    Point2D chartP = chart.getArea().getCenter();
                    double tempDist = Point2D.distanceSq(textP.getX(), textP.getY(), chartP.getX(), chartP.getY());
                    if (tempDist < subtitleDistance) {
                        subtitleDistance = tempDist;
                        chart.subtitle = textChunk.getText().trim();
                        chart.subtitleTextChunk = textChunk;
                        updateChunkType(textChunk, chart.chunksRemain);
                    }
//                updateChunkType(textChunk, chart.chunksRemain);
                }
                continue;
            }


            // 如果不满足统计规律　则跳过
            if (!isTitleValid(textChunk, chart)) {
                continue;
            }

            // 判断字符串是否为高概率标题
            titleCurrent = textChunk.getText();
            boolean highProb = bHighProbabilityChartTitle(titleCurrent);
            boolean highProbA = (titleA != null && bHighProbabilityChartTitle(titleA));
            boolean highProbB = (titleB != null && bHighProbabilityChartTitle(titleB));
            boolean highProbC = (titleC != null && bHighProbabilityChartTitle(titleC));
            // 找出AreaC部分的候选title
            boolean bSet = false;
            if (textChunk.intersects(areaC) &&
                    !textChunk.intersects(areaAxisL) &&
                    !textChunk.intersects(areaAxisR)) {
                float x = (float) areaC.getCenterX();
                float y = (float) areaC.getMinY();
                if (titleC == null
                        || distanceToTextChunk(textChunk, x, y) < distanceToTextChunk(chunkC, x, y)) {
                    if (!highProbC || highProb) {
                        titleC = textChunk.getText();
                        chunkC = textChunk;
                    }
                    bSet = true;
                }
            }


            // 找出AreaA部分的候选title
            if (!bSet && textChunk.intersects(areaA)) {
                float x = (float) areaA.getMinX();
                float y = (float) areaA.getMaxY();
                if (titleA == null
                        || distanceToTextChunk(textChunk, x, y) < distanceToTextChunk(chunkA, x, y)) {
                    if (!highProbA || highProb) {
                        titleA = titleCurrent;
                        chunkA = textChunk;
                    }
                }
                bSet = true;
                expandTitles.add(textChunk); // 仅保存在 Chart.area　左上方位置处的候选标题
            }

            // 找出AreaB部分的候选title
            if (!bSet && textChunk.intersects(areaB)) {
                float x = (float) areaB.getCenterX();
                float y = (float) areaB.getMaxY();
                if (titleB == null
                        || distanceToTextChunk(textChunk, x, y) < distanceToTextChunk(chunkB, x, y)) {
                    if (!highProbB || highProb) {
                        titleB = textChunk.getText();
                        chunkB = textChunk;
                    }
                    bSet = true;
                }
            }
        } // end for chunk

        // 在三个可能的候选title中找出最好的title  后续可以改进策略
        if (titleA == null && titleB == null && titleC == null) {
            return false;
        }
        if (titleA != null) {
            // 在候选标题集中尝试扩展标题
            TextChunk titleExpand = expandTitle(chunkA, expandTitles);
            String titleANew = titleExpand.getText().trim();
            // 判断是否为表格的高概率标题
            if (chart.type != ChartType.BITMAP_CHART && bHighProbabilityTableTitle(titleANew)) {
                chart.type = ChartType.UNKNOWN_CHART;
                return false;
            }
            if ((titleB == null && titleC == null) || bHighProbabilityChartTitle(titleANew)) {
                setChartTitle(chart, titleExpand);
                return true;
            }
        }
        if (titleB != null) {
            if ((titleA == null && titleC == null) || bHighProbabilityChartTitle(titleB)) {
                setChartTitle(chart, chunkB);
                return true;
            }
        }
        if (titleC != null) {
            if ((titleA == null && titleB == null) || bHighProbabilityChartTitle(titleC)) {
                setChartTitle(chart, chunkC);
                return true;
            }
        }
        // 添加判断候选标题长度　后续打算添加字体类型，大小等参数的比较
        if (titleC != null) {
            if (StringUtils.isEmpty(titleA) ||
                    ChunkUtil.getMaxFontHeight(chunkC) > ChunkUtil.getMaxFontHeight(chunkA) ||
                    titleC.length() >= titleA.length()) {
                setChartTitle(chart, chunkC);
                return true;
            }
        }
        if (titleA != null) {
            setChartTitle(chart, chunkA);
            return true;
        }
        return false;
    }

    public static void setChartTitle(Chart chart, TextChunk chunk) {
        chart.title = chunk.getText().trim();
        chart.titleTextChunk = chunk;
        ChunkUtil.setChunkType(chart.titleTextChunk, ChunkStatusType.TITLE_TYPE);
        updateChunkType(chunk, chart.chunksRemain);
    }

    /**
     * 以 图　或　FIGURE 开头的字符串　为标题的可能性非常高
     *
     * @param title
     * @return
     */
    public static boolean bHighProbabilityChartTitle(String title) {
        List<String> titleChineseFormat = RegularMatch.getInstance().titleChineseFormat;
        List<String> titleEnglishFormat = RegularMatch.getInstance().titleEnglishFormat;
        return titleChineseFormat.stream().anyMatch(s -> title.startsWith(s)) ||
                titleEnglishFormat.stream().anyMatch(s -> title.startsWith(s));
    }

    /**
     * 以 表　或　Table 开头的字符串　为标题的可能性非常高
     *
     * @param title
     * @return
     */
    public static boolean bHighProbabilityTableTitle(String title) {
        List<String> titleChineseFormat = RegularMatch.getInstance().titleChineseFormatTable;
        List<String> titleEnglishFormat = RegularMatch.getInstance().titleEnglishFormatTable;
        return titleChineseFormat.stream().anyMatch(s -> title.startsWith(s)) ||
                titleEnglishFormat.stream().anyMatch(s -> title.startsWith(s));
    }

    public static List<Integer> matchStringByIndexOf(String parent, String child)
    {
        int index = 0;
        List<Integer> indexes = new ArrayList<>();
        while( ( index = parent.indexOf(child, index) ) != -1 )
        {
            indexes.add(index);
            index = index + child.length();
        }
        return indexes;
    }

    public static List<TextChunk> splitChunk(TextChunk chunk, List<String> splitInfos) {
        String text = chunk.getText();
        for (String title : splitInfos) {
            List<Integer> indexesC = matchStringByIndexOf(text, title);
            if (indexesC.size() != 2) {
                continue;
            }
            List<TextElement> elements = chunk.getElements();
            int index = indexesC.get(1);
            int len = title.length();
            if (elements.size() - index - len <= 5) {
                return null;
            }

            // 规避“图书”类情况 “图”字现在必须后面有数字才会被认为是关键词
            if (title.equals("图") && index < elements.size() - 1) {
                if (Character.isDigit(text.charAt(1)) && !Character.isDigit(text.charAt(index + 1))) {
                    continue;
                }
            }

            TextChunk beforeChunk = new TextChunk(elements.subList(0, index));
            TextChunk afterChunk = new TextChunk(elements.subList(index, elements.size()));
            List<TextChunk> splitChunks = new ArrayList<>();
            splitChunks.add(beforeChunk);
            splitChunks.add(afterChunk);
            return splitChunks;
        }
        return null;
    }

    public static void splitTwoTitleChunk(List<TextChunk> chunks) {
        if (chunks.size() == 0) {
            return;
        }
        List<String> titleChineseFormat = RegularMatch.getInstance().titleChineseFormat;
        List<String> titleEnglishFormat = RegularMatch.getInstance().titleEnglishFormat;
        for (int i = 0; i < chunks.size(); i++) {
            TextChunk chunk = chunks.get(i);
            String text = chunk.getText();
            if (StringUtils.isEmpty(text) ||
                    text.length() <= 10 ||
                    !bHighProbabilityChartTitle(text)) {
                continue;
            }
            List<TextChunk> splitChunks = splitChunk(chunk, titleChineseFormat);
            if (splitChunks != null) {
                chunks.set(i, splitChunks.get(0));
                chunks.add(i + 1, splitChunks.get(1));
                continue;
            }
            splitChunks = splitChunk(chunk, titleEnglishFormat);
            if (splitChunks != null) {
                chunks.set(i, splitChunks.get(0));
                chunks.add(i + 1, splitChunks.get(1));
                continue;
            }
        } // end for i
    }

    /**
     * 判断给定字符串的起始端是否为常见标题前缀
     *
     * @param title
     * @return
     */
    public static boolean isTitlePrefix(String title) {
        List<String> titleChineseFormat = RegularMatch.getInstance().titleChineseFormat;
        List<String> titleEnglishFormat = RegularMatch.getInstance().titleEnglishFormat;
        String info = title.replaceAll("\\s", "");
        int n = info.length();
        if (titleEnglishFormat.stream().anyMatch(s -> info.startsWith(s)) && n >= 7) {
            return true;
        } else if (titleChineseFormat.stream().anyMatch(s -> info.startsWith(s)) && n >= 2) {
            String substr = info.substring(1, 2);
            if (substr.equals("表") || substr.equals(":") || isStringNumber(substr)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 根据刻度单位的统计规律 判断给定String是否为高可能性的刻度单位信息
     * @param str
     * @param length
     * @return
     */
    public static boolean bHighProbabilityAxisUnitInfo(String str, int length) {
        if (str == null || str.length() == 0) {
            return false;
        }
        String text = new String(str.toCharArray());
        String filterText =  filterAxisUnitInfo(text);
        int nBefore = text.length(), nAfter = filterText.length();
        return nAfter <= length && nAfter < nBefore ? true : false;
    }

    /**
     * 根据刻度单位的统计规律 过滤掉给定String内部刻度单位的信息
     * @param str
     * @return
     */
    public static String filterAxisUnitInfo(String str) {
        List<String> unitFormat = RegularMatch.getInstance().unitFormat;
        String info2 = new String(str.toCharArray());
        String info = "";
        for (String s : unitFormat) {
            info = info2.replaceAll(s, "");
            info2 = info;
        }
        return info;
    }

    /**
     * 在水平方向扩展标题
     *
     * @param obj
     * @param chunks
     * @param type
     * @return
     */
    public static TextChunk expandTitleHorizon(
            TextChunk obj,
            List<TextChunk> chunks, ChunkStatusType type) {
        //　水平方向从左到右扩展标题
        TextChunk title = expandHorizon(obj, chunks, true, type);
        //　水平方向从右到左扩展标题
        title = expandHorizon(title, chunks, false, type);
        return title;
    }

    /**
     * 在水平方向上按照指定方向扩展给定TextChunk, 设置合并对象的类型
     *
     * @param obj
     * @param chunks
     * @param leftToRight
     * @param type
     * @return
     */
    public static TextChunk expandHorizon(
            TextChunk obj,
            List<TextChunk> chunks, boolean leftToRight, ChunkStatusType type) {
        // 如果是高概率标题　则不进行从右到左的扩展 直接返回
        String info = obj.getText();
        if (StringUtils.isNotEmpty(info) && bHighProbabilityChartTitle(info) && !leftToRight) {
            return obj;
        }
        // 如果合并方向为从右到左则逆序
        if (!leftToRight) {
            Collections.reverse(chunks);
        }

        // 设置扩展时组号最大间隔值
        int numDiffGroup = 5;
        if (type == ChunkStatusType.LEGEND_TYPE) {
            numDiffGroup = 2;
        }

        double ty = obj.getBounds().getCenterY();
        double xleft = obj.getBounds().getMinX();
        double xright = obj.getBounds().getMaxX();
        double fontw = ChunkUtil.getMaxFontWidth(obj);
        // 遍历　找出可以合并的同行的候选 TextChunk
        for (int i = 0; i < chunks.size(); i++) {
            TextChunk chunk = chunks.get(i);
            // 忽略标记为给定类型的Chunk
            if (ChunkUtil.getChunkType(chunk) == type) {
                continue;
            }
            // 忽略相同的对象
            if (obj.equals(chunk)) {
                continue;
            }
            // 找出相邻组号的  合并通常在相邻组号的TextChunk之间
            int tGroupIndex = 0, cGroupIndex = 0;
            if (leftToRight) {
                tGroupIndex = obj.getLastElement().getGroupIndex();
                cGroupIndex = chunk.getFirstElement().getGroupIndex();
            } else {
                tGroupIndex = obj.getFirstElement().getGroupIndex();
                cGroupIndex = chunk.getLastElement().getGroupIndex();
            }
            if (leftToRight && cGroupIndex <= tGroupIndex) {
                continue;
            }
            if (!leftToRight && cGroupIndex >= tGroupIndex) {
                continue;
            }
            if (Math.abs(cGroupIndex - tGroupIndex) >= numDiffGroup) {
                continue;
            }

            // 找出同行的即Y轴高度差不多
            if (!(chunk.getBounds().getMinY() <= ty && ty <= chunk.getBounds().getMaxY())) {
                continue;
            }
            // 相邻在4倍字符宽度范围内
            if (leftToRight && (chunk.getBounds().getMinX() >= xright + 4.0 * fontw ||
                    chunk.getBounds().getMinX() <= xright - 2.0 * fontw)) {
                continue;
            }
            if (!leftToRight && (chunk.getBounds().getMaxX() <= xleft - 2.0 * fontw ||
                    chunk.getBounds().getMaxX() >= xleft + 2.0 * fontw)) {
                continue;
            }

            //　过滤掉空对象或高概率标题对象
            info = chunk.getText();
            if (StringUtils.isEmpty(info) || bHighProbabilityChartTitle(info)) {
                continue;
            }

            // 合并
            if (leftToRight) {
                groupTwoTextChunk(obj, chunk, false);
            } else {
                groupTwoTextChunk(chunk, obj, false);
                obj = chunk;
            }

            // 删除前一个和给定obj相同的对象  实时更新chunks
            if (i - 1 >= 0 && chunks.get(i - 1).equals(obj)) {
                chunks.remove(i - 1);
            }
            ChunkUtil.setChunkType(chunk, type);
            break;
        } // end for chunk
        // 如果合并方向为从右到左则逆序
        if (!leftToRight) {
            Collections.reverse(chunks);
        }
        return obj;
    }

    /**
     * 尝试扩展标题　存在标题分成多行的情况
     * 目前采取从下往上扩展的方法合并两行标题的情况　多行的情况需要改进
     *
     * @param title
     * @param expandTitles
     * @return
     */
    public static TextChunk expandTitle(
            TextChunk title,
            List<TextChunk> expandTitles) {
        // 测试是否需要扩展
        int n = expandTitles.size();
        if (n == 1) {
            return title;
        }
        if (bHighProbabilityChartTitle(title.getText())) {
            // 水平方向扩展标题
            title = expandTitleHorizon(title, expandTitles, ChunkStatusType.TITLE_TYPE);
            // 有时开头为图或Figure的标题　需要向下尝试扩展
            boolean bExpand = true;
            while (bExpand) {
                bExpand = expandChunkVertical(title, expandTitles, false, ChunkStatusType.TITLE_TYPE, true);
            }
            return title;
        } // end if

        // 去除重复的和不以图开头的TextChunk
        final Iterator<TextChunk> each = expandTitles.iterator();
        while (each.hasNext()) {
            TextChunk textChunk = each.next();
            if (textChunk.equals(title)) {
                each.remove();
            } else if (!bHighProbabilityChartTitle(textChunk.getText())) {
                each.remove();
            }
        } // end while
        n = expandTitles.size();
        if (n == 0) {
            return title;
        }

        // 多次尝试向上扩展　因为有的两行标题间距大有的间距小
        // 暂时尝试两次不同参数比例系数的扩展
        List<TextChunk> titles = new ArrayList<>();
        titles.add(title);
        if (expansionChunk(titles, expandTitles, false, ChunkStatusType.TITLE_TYPE, 0, 1.25) ||
                expansionChunk(titles, expandTitles, false, ChunkStatusType.TITLE_TYPE, 0, 0.85)) {
            //logger.debug("expand title successful");
        }
        return titles.get(0);
    }

    /**
     * 解析chart标题 当前Page中的chart标题在上一页的底部位置的情况　还在开发调试中
     *
     * @param textChunks 输入的当前page的chunk集
     * @param charts     当前page的chart集
     */
    public static void selectTitleFromBeforePage(
            List<TextChunk> textChunks,
            List<Chart> charts,
            PdfExtractContext context,
            int pageIndex) {
        // 遍历所有的chunk，找出可能是下一页的部分Chart的后续标题信息
        List<TextChunk> candidateTitles = new ArrayList<>();
        for (TextChunk textChunk : textChunks) {
            if (ChunkUtil.getChunkType(textChunk) != ChunkStatusType.UNKNOWN_TYPE ||
                    ChunkUtil.isVertical(textChunk))
                continue;
            if (textChunk.getElements().isEmpty())
                continue;
            String text = textChunk.getText();

            if (bHighProbabilityChartTitle(text) && text.length() >= 2) {
                candidateTitles.add(textChunk);
            }
        }  // end for
        // 如果不为空　则保存起来
        if (!candidateTitles.isEmpty())
            context.getPageContext(pageIndex).putArg(PAGE_ARG_CANDIDATE_TITLES, candidateTitles);

        // 查询前一页的ChunkInfo信息
        List<TextChunk> prevPageCandidateTitles = null;
        if (pageIndex > 0) {
            try {
                prevPageCandidateTitles = context.getPageContext(pageIndex - 1).getArg(PAGE_ARG_CANDIDATE_TITLES);
            } catch (Exception e) {
                // Failed to get titles from previous page, there might be errors
            }
        }
        if (prevPageCandidateTitles == null) {
            return;
        }

        // 如果当前没有标题的Chart集为空
        int nNoTitle = 0;
        for (Chart chart : charts) {
            if (StringUtils.isEmpty(chart.title)) {
                nNoTitle++;
            }
        }
        if (nNoTitle == 0)
            return;

        // 遍历ChunkInfo中元素　将每个TextChunk的左下角的坐标信息排序
        List<FloatIntPair> sortedChunkInfo = new ArrayList<>();
        for (int i = 0; i < prevPageCandidateTitles.size(); i++) {
            TextChunk chunk = prevPageCandidateTitles.get(i);
            float key = -chunk.getTop();
            float x = chunk.getLeft();
            FloatIntPair pair = new FloatIntPair(key, x, i);
            sortedChunkInfo.add(pair);
        } // end for i
        Collections.sort(sortedChunkInfo);

        // 遍历Chart集　将每个Chart的左上角的坐标信息排序
        List<FloatIntPair> sortedChartsInfo = new ArrayList<>();
        for (int i = 0; i < charts.size(); i++) {
            Chart chart = charts.get(i);
            float key = -chart.getTop();
            float x = chart.getLeft();
            FloatIntPair pair = new FloatIntPair(key, x, i);
            sortedChartsInfo.add(pair);
        } // end for i
        Collections.sort(sortedChartsInfo);

        // 找出最靠上的两个没有标题的chart
        int nChart = sortedChartsInfo.size();
        int count = 0;
        int iFirst = -1, iSecond = -1;
        for (int i = nChart - 1; i >= 0; i--) {
            int ith = sortedChartsInfo.get(i).i;
            Chart chart = charts.get(ith);
            if (!StringUtils.isEmpty(chart.title))
                continue;

            if (count == 0) {
                iFirst = ith;
            } else if (count == 1) {
                iSecond = ith;
            }
            count++;
        } // end for i

        // 如果只有一个Chart 或 TextChunk
        int iCFirst = sortedChunkInfo.get(0).i;
        Chart chartFirst = charts.get(iFirst);
        TextChunk chunkFirst = prevPageCandidateTitles.get(iCFirst);
        int nChunk = sortedChunkInfo.size();
        if (count == 1) {
            if (isTitleMatchChart(chunkFirst, chartFirst)) {
                chartFirst.title = chunkFirst.getText().trim();
                chartFirst.titleTextChunk = chunkFirst;
                return;
            }
            if (nChunk == 1) {
                return;
            }
            int iCSecond = sortedChunkInfo.get(1).i;
            TextChunk chunkSecond = prevPageCandidateTitles.get(iCSecond);
            if (isTitleMatchChart(chunkSecond, chartFirst)) {
                chartFirst.title = chunkSecond.getText().trim();
                chartFirst.titleTextChunk = chunkSecond;
                return;
            }
            return;
        } // end if
        if (nChunk == 1)
            return;

        int iCSecond = sortedChunkInfo.get(1).i;
        TextChunk chunkSecond = prevPageCandidateTitles.get(iCSecond);
        Chart chartSecond = charts.get(iSecond);
        if (isTitleMatchChart(chunkFirst, chartFirst)) {
            chartFirst.title = chunkFirst.getText().trim();
            if (isTitleMatchChart(chunkSecond, chartSecond)) {
                chartSecond.title = chunkSecond.getText().trim();
                chartSecond.titleTextChunk = chunkSecond;
            }
        } else if (isTitleMatchChart(chunkFirst, chartSecond)) {
            chartSecond.title = chunkFirst.getText().trim();
            if (isTitleMatchChart(chunkSecond, chartFirst)) {
                chartFirst.title = chunkSecond.getText().trim();
                chartFirst.titleTextChunk = chunkSecond;
            }
        }
    }

    private static boolean isChartOnPageTop(Chart chart) {
        return chart.getTop() < 140;
    }

    /**
     * 判断给定string是否为时间类型 遍历时间模式正则表达式集　如果存在匹配的时间模式 则返回 true
     *
     * @param str
     * @return
     */
    public static boolean isStringMatchTime(String str) {
        List<String> formats = stringMatchTimeFormat(str);
        return !formats.isEmpty();
    }

    /**
     * 判断是否为不可分割的时间类型的文本对象
     * @param chunk
     * @return
     */
    public static boolean isUnSplitTimeString(TextChunk chunk) {
        String text = chunk.getText().trim();
        if (StringUtils.isEmpty(text) || !isStringMatchTime(text)) {
            return false;
        }
        double space = chunk.getWidthOfSpace();
        double width = chunk.getAvgCharWidth();
        if (space/width < 1.0) {
            return true;
        }
        return false;
    }

    /**
     * 检测给定string匹配的时间格式集
     * 单一字符串一般能匹配多种时间格式　收集起来方便进一步判断
     *
     * @param str
     * @return
     */
    public static List<String> stringMatchTimeFormat(String str) {
        String strSimple = str.replaceAll("[\\s　]*", "").replaceAll("[AEFaef]$", "");
        Map<String, TimeFormat> dateRegFormat = RegularMatch.getInstance().dateRegFormat;
        Map<String, Pattern> dateRegPattern = RegularMatch.getInstance().dateRegPattern;
        List<String> formats = new ArrayList<>();
        for (Map.Entry<String, TimeFormat> entry: dateRegFormat.entrySet()) {
            if (dateRegPattern.get(entry.getKey()).matcher(strSimple).matches()) {
                formats.add(entry.getValue().formatCommon);
            }
        }
        return formats;
    }

    /**
     * 判断给定字符串是否为数字类型
     *
     * @param str
     * @return
     */
    public static boolean isStringNumber(String str) {
        if (StringUtils.isEmpty(str)) {
            return false;
        }
        Pattern regExNumberPattern = RegularMatch.getInstance().regExNumberPattern;
        Matcher m = regExNumberPattern.matcher(str);
        String strNum = m.replaceAll("").trim();
        if (strNum.length() == 0) {
            return true;
        } else {
            return false;
        }
    }

    /**
     * 判断给定Chart的候选标题是否在统计规律上有效
     *
     * @param chunk 给定TextChunk
     * @param chart 给定Chart
     * @return 如果不满足规律　则返回 false
     */
    private static boolean isTitleValid(TextChunk chunk, Chart chart) {
        // 如果文字不是水平方向　则不是有效标题对象
        TextDirection direction = chunk.getDirection();
        if (direction != TextDirection.LTR && direction != TextDirection.RTL) {
            return false;
        }

        String text = chunk.getText();
        // 过滤掉数值类型的 TextChunk
        if (isStringNumber(text)) {
            return false;
        }

        // 如果字符串内容满足时间格式类型 则不作为标题 (目前没有出现时间作为标题的情况)
        if (isStringMatchTime(text)) {
            return false;
        }

        if (bHighProbabilityAxisUnitInfo(text, 0)) {
            return false;
        }

        // 如果以图开头　则返回　true
        if (bHighProbabilityChartTitle(text)) {
            return true;
        }

        // 标题不能比Chart宽太多
        double w = chunk.getWidth();
        double cw = chart.getArea().getWidth();
        if (w > 1.5f * cw) {
            return false;
        }

        // 过滤掉高可能性刻度单位说明性文字的
        text = text.replaceAll("[\\s　]*", "");
        if (bHighProbabilityAxisUnitInfo(text, 0)) {
            return false;
        }

        return true;
    }

    /**
     * 判断候选标题和Chart是否匹配
     */
    private static boolean isTitleMatchChart(TextChunk chunk, Chart chart) {
        if (!isChartOnPageTop(chart))
            return false;

        double xt = chunk.getX();
        double wt = chunk.getWidth();
        double xc = chart.getArea().getX();
        if (chunk.getBottom() + chart.getHeight() + 60 <= chart.pageHeight)
            return false;
        if (xt - 0.5f * wt < xc && xt + 0.5f * wt > xc)
            return true;
        return false;
    }

    /**
     * 尝试找出刻度单位信息
     * @param charts
     */
    public static void selectChartsAxisUnit(List<Chart> charts) {
        for (Chart chart : charts) {
            parserChartAxisUnit(chart);
        }
    }

    /**
     * 解析Chart刻度的单位信息
     * @param chart
     */
    public static void parserChartAxisUnit(Chart chart) {
        // 无效chart 或  饼图没有刻度信息   直接返回
        if (chart == null || !chart.isChart() ||
                chart.type == ChartType.PIE_CHART ||
                chart.type == ChartType.BITMAP_CHART ||
                chart.isPPT) {
            return;
        }
        // 暂时不处理水平方向柱状图 后续考虑优化
        if (chart.type == ChartType.COLUMN_CHART) {
            return;
        }

        double width = chart.getWidth();
        double xLeft = chart.getLeft();
        double xRight = chart.getRight();

        // 只对含有刻度的情况下尝试解析单位信息
        int nScaleL = chart.vAxisChunksL.size();
        int nScaleR = chart.vAxisChunksR.size();
        if (nScaleL == 0 && nScaleR == 0) {
            if (chart.type == ChartType.BAR_CHART) {
                double xmin = chart.getLeft();
                double xmax = xmin + 0.3 * width;
                double ymin = chart.getTop();
                double ymax = ymin + 0.5 * chart.getHeight();
                Rectangle2D box = new Rectangle2D.Double(xmin, ymin, xmax - xmin, ymax - ymin);
                Rectangle area = new Rectangle(box);
                parserAxisUnit(chart, area, chart.chunksRemain, 0);
            }
            return;
        }

        // 左侧
        if (nScaleL >= 1) {
            // 计算左侧刻度单位大致区域信息
            Rectangle2D box = chart.vAxisChunksL.get(0).getBounds2D();
            chart.vAxisChunksL.stream().forEach(chunk -> Rectangle2D.union(box, chunk, box));
            double xmax = box.getMaxX() + 0.2 * width;
            double ymax = box.getMaxY();
            Rectangle area = chart.getArea().resize((float)(xmax - xLeft), (float)(ymax - chart.getTop()));
            parserAxisUnit(chart, area, chart.chunksRemain, 0);
        }
        // 右侧
        if (nScaleR >= 1) {
            // 计算右侧刻度单位大致区域信息
            Rectangle2D box = chart.vAxisChunksR.get(0).getBounds2D();
            chart.vAxisChunksR.stream().forEach(chunk -> Rectangle2D.union(box, chunk, box));
            double xmin = box.getMinX() - 0.2 * width;
            double ymax = box.getMaxY();
            Rectangle area = chart.getArea().moveTo((float)xmin, chart.getTop())
                    .resize((float)(xRight - xmin), (float)(ymax - chart.getTop()));
            parserAxisUnit(chart, area, chart.chunksRemain, 1);
        }
    }

    /**
     * 解析出Chart内给定区域中的单位信息
     * @param chart
     * @param area
     * @param chunks
     * @param axis
     */
    public static void parserAxisUnit(Chart chart, Rectangle area, List<TextChunk> chunks, int axis) {
        // 存储候选单位信息
        List<TextChunk> units = new ArrayList<>();
        for (TextChunk textChunk : chunks) {
            // 过滤掉其他不相关类型的文字
            ChunkStatusType type = ChunkUtil.getChunkType(textChunk);
            if (type == ChunkStatusType.TITLE_TYPE ||
                    type == ChunkStatusType.LEGEND_TYPE ||
                    type == ChunkStatusType.SCALE_TYPE ||
                    type == ChunkStatusType.DATA_SOURCE_TYPE ||
                    type == ChunkStatusType.IGNORE_TYPE) {
                continue;
            }
            // 过滤掉旋转方向的文字
            TextDirection direction = textChunk.getDirection();
            if (direction == TextDirection.ROTATED) {
                continue;
            }
            // 一般chunk在chart内部范围内且与区域相交 才有可能是候选
            if (!textChunk.intersects(area)) {
                continue;
            }
            String text = textChunk.getText().replaceAll("[\\s　]*", "");
            // 过滤掉时间类型的
            if (text.length() == 0 || isStringMatchTime(text)) {
                continue;
            }
            // 过滤掉数字类型
            boolean isNumber = isStringNumber(text);
            boolean isHighProb = bHighProbabilityAxisUnitInfo(text, 3);
            if (isNumber ) {
                if (isHighProb && (text.equals("%") || text.equals("$") ||
                    text.equals("(%)") || text.equals("($)")  )){
                    units.add(textChunk);
                }
                continue;
            }

            // 判断是否为高可能性刻度单位说明性文字
            if (isHighProb) {
                units.add(textChunk);
                continue;
            }

            // 对垂直方向的text判断内部是否含有单位关键信息
            if (direction == TextDirection.VERTICAL_UP || direction == TextDirection.VERTICAL_DOWN) {
                String filterText =  filterAxisUnitInfo(text);
                if (text.length() > filterText.length()) {
                    units.add(textChunk);
                }
            }
        } // end for chunk
        if (units.isEmpty()) {
            return;
        }
        double ymin = Double.MAX_VALUE;
        TextChunk bestUnit = null;
        for (TextChunk unit : units) {
            if (ymin > unit.getCenterY()) {
                bestUnit = unit;
                ymin = unit.getCenterY();
            }
        }
        if (axis == 0) {
            chart.vAxisTextUnitL = bestUnit.getText();
            chart.vAxisChunkUnitL = bestUnit;
            ChunkUtil.setChunkType(bestUnit, ChunkStatusType.SCALE_UNIT_TYPE);
        }
        if (axis == 1) {
            chart.vAxisTextUnitR = bestUnit.getText();
            chart.vAxisChunkUnitR = bestUnit;
            ChunkUtil.setChunkType(bestUnit, ChunkStatusType.SCALE_UNIT_TYPE);
        }
    }

    /**
     * 解析刻度信息
     * 注: 目前处理不了斜着的刻度 或 水平显示但是垂直排列的刻度  目前算法依赖与 chunk的分组方法
     *
     * @param textChunks
     * @param charts     当前page的chart集
     */
    public static void selectScale(
            List<TextChunk> textChunks,
            List<Chart> charts) {
        for (Chart chart : charts) {
            Rectangle2D chartArea = chart.getArea();
            Rectangle2D scaleArea = compChartScaleArea(chart);
            chart.setArea(scaleArea);
            compScaleInfo(chart);
            chart.setArea(chartArea);

            // 更新类型
            updateChunkType(chart.hAxisChunksU, textChunks);
            updateChunkType(chart.hAxisChunksD, textChunks);
            updateChunkType(chart.vAxisChunksL, textChunks);
            updateChunkType(chart.vAxisChunksR, textChunks);
        }
    }

    /**
     * 计算刻度有效区域 在一定程度上缓解Chart区域检测过大引起刻度解析不准的情况
     * @param chart
     * @return
     */
    public static Rectangle2D compChartScaleArea(Chart chart) {
        Rectangle2D textBox = ChartPathInfosParser.getChartChunkMergeBox(chart);
        if (textBox == null) {
            return null;
        }
        if (textBox != null) {
            Rectangle2D.intersect(chart.getArea(), textBox, textBox);
        }
        GraphicsUtil.extendRect(textBox, 1.0);
        if (chart.lvAxis != null) {
            Rectangle2D.union(textBox, chart.lvAxis.getBounds2D(), textBox);
        }
        if (chart.rvAxis != null) {
            Rectangle2D.union(textBox, chart.rvAxis.getBounds2D(), textBox);
        }
        if (chart.hAxis != null) {
            Rectangle2D.union(textBox, chart.hAxis.getBounds2D(), textBox);
        }
        return textBox;
    }

    /**
     * 计算chart左　右　下　上四侧的刻度信息
     * 注: 目前处理不了斜着的刻度 或 水平显示但是垂直排列的刻度  目前算法依赖与 chunk的分组方法
     *
     * @param chart 当前chart
     */
    public static void compScaleInfo(Chart chart) {
        // 无效chart 或  饼图没有刻度信息   直接返回
        if (chart == null || !chart.isChart() ||
                chart.type == ChartType.PIE_CHART || chart.type == ChartType.BITMAP_CHART) {
            return;
        }

        // 基于柱状对象在X方向区间段内的TextChunk不可被分割
        ChartPathInfosParser.setUnSplitText(chart);

        List<TextChunk> textChunks = chart.chunksRemain;

        // 基于chart 的长宽　 设置左右下上四个侧边区域
        float cw = chart.getWidth();
        float ch = chart.getHeight();
        float w = 0.25f * cw;
        float h = 0.4f * ch;
        float wleft = w;
        if (chart.lvAxis != null) {
            wleft = (float) (chart.lvAxis.getX1() - chart.getLeft());
        } else {
            if (chart.type == ChartType.COLUMN_CHART) {
                wleft = 0.5f * cw;
            }
        }
        float wright = w;
        if (chart.rvAxis != null) {
            wright = (float) (chart.getRight() - chart.rvAxis.getX1());
        }

        // 如果存在水平坐标轴　则分上下侧区域时参考水平坐标轴位置
        float downAreaTopY = chart.getTop() + 0.6f * ch;
        double haxisWidth = chart.getWidth();
        double hAxisMaxX = chart.getRight();
        if (chart.hAxis != null) {
            float axisY = (float) chart.hAxis.getY1();
            downAreaTopY = downAreaTopY < axisY ? downAreaTopY : axisY;
            haxisWidth = chart.hAxis.getBounds().getWidth();
            hAxisMaxX = Math.max(chart.hAxis.getX1(), chart.hAxis.getX2());
        }

        // 存储四个侧边区域  可以调整
        List<Rectangle> areas = new ArrayList<>();
        areas.add(chart.getArea().resize(wleft, ch));              // 左侧
        areas.add(chart.getArea().moveTo(chart.getLeft(), downAreaTopY).resize(cw, chart.getBottom() - downAreaTopY));      // 下侧
        areas.add(chart.getArea().moveTo(chart.getRight() - wright, chart.getTop()).resize(wright, ch));           // 右侧
        areas.add(chart.getArea().resize(cw, downAreaTopY - chart.getTop())); // 上侧
        int[] dirs = {0, 2, 1, 3};

        boolean bValidScalseOCR = OCREngine.isValidScalseOCRInfos(chart);

        // 遍历处理四个区域
        float[] bounds = {-1.0f, -1.0f, -1.0f, -1.0f};
        double mx = 0.0, my = 0.0;
        for (int i = 0; i < 4; i++) {
            // 如果有有效的斜着刻度信息　则跳过
            if (dirs[i] == 2 && bValidScalseOCR) {
                continue;
            }
            // 一般上下刻度不同时出现
            if (dirs[i] == 3 && (!chart.hAxisTextD.isEmpty() || bValidScalseOCR)) {
                continue;
            }
            // 如果是水平柱状图
            if (dirs[i] == 1 && !chart.vAxisChunksL.isEmpty()) {
                if (chart.type == ChartType.COLUMN_CHART) {
                    continue;
                }
            }
            if (chart.isWaterFall) {
                if (dirs[i] == 0 || dirs[i] == 1) {
                    continue;
                }
            }

            // 找出各区域的　候选 刻度信息 chunk在chart内部且与区域相交
            List<TextChunk> chunks = new ArrayList<>();
            Rectangle2D area = areas.get(i);
            for (TextChunk textChunk : textChunks) {
                ChunkStatusType type = ChunkUtil.getChunkType(textChunk);
                if (type != ChunkStatusType.UNKNOWN_TYPE
                        && type != ChunkStatusType.TITLE_TYPE
                        && type != ChunkStatusType.VERTICAL_TYPE
                        && type != ChunkStatusType.MERGE_TYPE
                        //&& type != ChunkStatusType.SPLIT_TYPE
                        ) {
                    continue;
                }

                if (dirs[i] == 0) {
                    mx = textChunk.getX() + textChunk.getWidth();
                    my = textChunk.getCenterY();
                } else if (dirs[i] == 1) {
                    //mx = textChunk.getCenterX();
                    mx = textChunk.getX();
                    my = textChunk.getCenterY();
                } else if (dirs[i] == 2) {
                    mx = textChunk.getCenterX();
                    my = textChunk.getTop();
                } else {
                    mx = textChunk.getCenterX();
                    my = textChunk.getBottom();
                }
                //mx = textChunk.getCenterX();
                //my = textChunk.getCenterY();
                if (dirs[i] == 0) {
                    // 水平柱状图　垂直坐标轴可能位于水平坐标轴中部某个位置　所以特殊处理
                    if (chart.type == ChartType.COLUMN_CHART) {
                        if (chart.lvAxis != null && mx >= chart.lvAxis.getX1()) {
                            continue;
                        }
                    } else {
                        // 如果存在水平坐标轴　则左侧刻度信息在水平坐标轴左端左边
                        if (chart.hAxis != null && mx >= chart.hAxis.getX1()) {
                            // 对垂直柱状图特殊处理，只要文字的右边缘在最左边的柱子的右侧，那么就把它放到候选队列
                            if (chart.type == ChartType.BAR_CHART) {
                                double maxX = textChunk.getTextRight();
                                double barLeft = -1;
                                for(ChartPathInfo pathInfo : chart.pathInfos) {
                                    if (pathInfo.type == PathInfo.PathType.BAR) {
                                        List<Double> xs = new ArrayList<>();
                                        List<Double> ys = new ArrayList<>();
                                        ChartPathInfosParser.getPathColumnBox(pathInfo.path, xs,ys);
                                        if ( barLeft == -1 || barLeft > Collections.min(xs) ){
                                            barLeft = Collections.min(xs);
                                        }
                                    }
                                }
                                if (maxX > barLeft) {
                                    continue;
                                }
                            }
                            else {
                                // 存在少数情况　左侧刻度太长　右端部分超过水平坐标轴的左边　但是幅度不大 调试中
                                double x = textChunk.getX() + 0.7 * textChunk.getWidth();
                                if (chart.lvAxis == null && x <= chart.hAxis.getX1() + 0.05 * haxisWidth) {
                                } else {
                                    continue;
                                }
                            }
                        }
                    }
                }
                if (dirs[i] == 1) {
                    // 水平柱状图　垂直坐标轴可能位于水平坐标轴中部某个位置　所以特殊处理
                    if (chart.type == ChartType.COLUMN_CHART) {
                        if (chart.rvAxis != null && mx <= chart.rvAxis.getX1()) {
                            continue;
                        }
                    } else {
                        // 如果存在水平坐标轴　则右侧刻度信息在水平坐标轴右端右边
                        if (chart.hAxis != null && mx <= chart.hAxis.getX2()) {
                            continue;
                        }
                    }
                }
                // 如果存在水平坐标轴 则下侧刻度信息大部分都在水平坐标轴下方
                if (dirs[i] == 2 && chart.hAxis != null) {
                    double y = textChunk.getY() + 0.7 * textChunk.getHeight();
                    if (y <= chart.hAxis.getY1()) {
                        continue;
                    }
                    if (textChunk.getMinX() > hAxisMaxX) {
                        continue;
                    }
                }
                // 一般chunk在chart内部范围内且与区域相交 才有可能是候选
                if (chart.getArea().contains(mx, my) && textChunk.intersects(area)) {
                    chunks.add(textChunk);
                }
            } // end for chunk

            //  如果是 下侧或上侧区域　可能内部chunk的分组间隔为一个空白符 故再次再次细分chunk 作为内部候选集
            if (dirs[i] == 2 || dirs[i] == 3) {
                // 对于乱序的候选刻度　该怎么调整顺序和合并　后续得继续优化　暂时不打开
                //List<TextChunk> merge = resetOrderAndMergeNearText(chunks);

                List<TextChunk> chunksAfter;
                // 只有含有中文的情况下进行Space分割
                Pattern regExPureEnglishPattern = RegularMatch.getInstance().regExPureEnglishPattern;
                if (chunks.stream().allMatch(chunk -> {Matcher m = regExPureEnglishPattern.matcher(chunk.getText());
                                                        return m.matches();})){
                    chunksAfter = chunks;
                } else {
                    chunksAfter = testSplitChunks(chart, chunks, true);
                }

                // 再根据相对间隔　细分
                List<TextChunk> chunksSecond = testSplitChunks(chart, chunksAfter, false);
                List<Double> pos = ChartPathInfosParser.getSplitPosition(chart);
                ChartPathInfosParser.splitAndMergeChartText(chart, chunksSecond, pos, false);
                chunks = chunksSecond;
            } // end if i
            // 如果是左侧或右侧的刻度信息　则除了水平排列柱状图外不能为垂直排列的信息
            else if (chart.type != ChartType.COLUMN_CHART) {
                final Iterator<TextChunk> each = chunks.iterator();
                while (each.hasNext()) {
                    TextChunk textChunk = each.next();
                    if (ChunkUtil.isVertical(textChunk)) {
                        each.remove();
                    }
                }
            }  // end else

            // 如果内部chunk数目过少　则跳过
            if (chunks.size() <= 1) {
                continue;
            }

            // 计算各区域相应的对称　边界信息
            // 初步过滤无效的chunk 根据其与边界的关系和长宽信息
            filterChunk(chunks, ch, cw, dirs[i], chart.type);

            // 如果过滤后的内部chunk数目过少　则跳过
            if (chunks.size() <= 1) {
                continue;
            }

            // 排序各chunk的位置信息
            List<ChunkInfo> sortChunks = new ArrayList<>();
            for (int ii = 0; ii < chunks.size(); ii++) {
                TextChunk chunk = chunks.get(ii);
                TextDirection direction = chunk.getDirection();
                if (dirs[i] == 0 || dirs[i] == 1) {
                    sortChunks.add(new ChunkInfo(chunk.getCenterY(), ii, true));
                }
                else {
                    if (direction == TextDirection.ROTATED) {
                        List<TextElement> elements = chunk.getElements();
                        int n = elements.size();
                        if (elements.get(0).getCenterY() >= elements.get(n - 1).getCenterY()) {
                            sortChunks.add(new ChunkInfo(chunk.getMaxX(), ii));
                        }
                        else {
                            sortChunks.add(new ChunkInfo(chunk.getMinX(), ii));
                        }
                    }
                    else {
                        sortChunks.add(new ChunkInfo(chunk.getCenterX(), ii));
                    }
                }
            } // end for chunk
            Collections.sort(sortChunks);

            // 统计相邻chunk的间距信息  因为刻度值通常是等间隔有序排列
            List<Float> nums = new ArrayList<>();
            for (int ii = 1; ii < sortChunks.size(); ii++) {
                nums.add(Math.abs(sortChunks.get(ii).xy - sortChunks.get(ii - 1).xy));
            }

            // 如果只有两个刻度信息　则间隔不能太大或太小  碰到过下侧刻度相隔比较宽的情况
            if (nums.size() == 1) {
                float d = nums.get(0);
                if ((dirs[i] == 0 || dirs[i] == 1) && (d >= 0.5f * ch || d <= 0.15f * ch))
                    continue;
                if ((dirs[i] == 2 || dirs[i] == 3) && (d >= 0.85f * cw || d <= 0.1f * cw))
                    continue;
            }

            // 取近似众数　设为大致的有效间隔长度
            float len = ChartPathInfosParser.getFloatMode(nums);
            float dist = 0.0f;

            // 遍历计算出相邻间隔长度过大过小的情况
            for (int j = 0; j < sortChunks.size() - 1; j++) {
                ChunkInfo curA = sortChunks.get(j);
                ChunkInfo curB = sortChunks.get(j + 1);
                if (!curB.valid) {
                    continue;
                }
                if (!curA.valid) {
                    for (int jj = j - 1; jj >= 0; jj--) {
                        ChunkInfo curAA = sortChunks.get(jj);
                        if (curAA.valid) {
                            curA = curAA;
                            break;
                        }
                    }
                }
                dist = Math.abs(curA.xy - curB.xy);
                double distCoef = Math.abs(dist - len) / len;
                if (distCoef < 0.2) {
                    continue;
                }
                TextChunk chunkA = chunks.get(curA.ith);
                TextChunk chunkB = chunks.get(curB.ith);
                int groupAID = chunkA.getGroupIndex();
                int groupBID = chunkB.getGroupIndex();
                // 如果是上下侧候选刻度　并且ID号相邻
                if ((dirs[i] == 2 || dirs[i] == 3) && groupBID - groupAID <= 1) {
                    String textA = chunkA.getText().trim();
                    String textB = chunkB.getText().trim();
                    // 如果字符数目相同　则适当放大距离间隔的限制
                    if (textA.length() == textB.length() && distCoef < 2.0) {
                        continue;
                    }
                }
                if (j == 0) {
                    boolean matchBar = chunkA.getElements().size() >= 2 && textMatchBar(chart, chunkA);
                    if (!matchBar) {
                        curA.valid = false;
                    }
                    continue;
                }
                boolean matchBar = chunkB.getElements().size() >= 2 && textMatchBar(chart, chunkB);
                if (!matchBar) {
                    curB.valid = false;
                }
            } // end for j

            // 过滤掉无效的
            Iterator<ChunkInfo> chunkInfoIterator = sortChunks.iterator();
            while (chunkInfoIterator.hasNext()) {
                ChunkInfo cur = chunkInfoIterator.next();
                if (!cur.valid) {
                    chunkInfoIterator.remove();
                }
            }

            // 保存最后过滤的chunk集
            List<TextChunk> chunksFilter = new ArrayList<>();
            for (ChunkInfo chunkInfo : sortChunks) {
                TextChunk chunk = chunks.get(chunkInfo.ith);
                chunksFilter.add(chunk);
            }

            filterNotNumberScale(chart, chunksFilter);

            // 如果是左侧或右侧　则根据包含数字规律做进一步过滤
            if (dirs[i] == 0 || dirs[i] == 1) {
                if (!isValidLeftRightScale(chart, chunksFilter)) {
                    continue;
                }
            }

            // 过滤掉文字方向不一致的TextChunk
            filterInValidDirectionScale(chunksFilter);

            // 如果是水平柱状图且都存在左右侧刻度
            if (chart.type == ChartType.COLUMN_CHART && dirs[i] == 1) {
                if (!chart.vAxisChunksL.isEmpty() && !chunksFilter.isEmpty()) {
                    // 当右侧刻度数目明显少于左侧时　认为是无效刻度
                    double f = 1.0 * chart.vAxisChunksL.size() / chunksFilter.size();
                    if (f > 2.0) {
                        chunksFilter.clear();
                    }
                }
            }

            // 判断非水平柱状图上下刻度是否不连续的百分数
            if (chart.type != ChartType.COLUMN_CHART && (dirs[i] == 2 || dirs[i] == 3)) {
                List<String> suffixs = new ArrayList<>();
                suffixs.add("%");
                if (isDiscontinuitiesSpecialNumber(chunksFilter, suffixs)) {
                    continue;
                }
            }

            // 过滤掉在坐标轴无效侧的候选刻度信息
            filterAxisInvalidSideScale(chart, dirs[i], chunksFilter);

            if (dirs[i] == 0) {
                Collections.reverse(chunksFilter);
                resetColumnLeftAxis(chart, chunksFilter);
                Collections.reverse(chunksFilter);
            }

            // 将最后候选集作为相应刻度信息集   输出信息
            //logger.debug("-------------------- " + i +  " --------------------");
            for (TextChunk chunk : chunksFilter) {
                chart.addFontName(chunk.getFontNames());
                String info = chunk.getText().trim();
                ChunkUtil.setChunkType(chunk, ChunkStatusType.SCALE_TYPE);
                if (dirs[i] == 0) {
                    chart.vAxisTextL.add(info);
                    chart.vAxisChunksL.add(chunk);
                } else if (dirs[i] == 1) {
                    chart.vAxisTextR.add(info);
                    chart.vAxisChunksR.add(chunk);
                } else if (dirs[i] == 2) {
                    chart.hAxisTextD.add(info);
                    chart.hAxisChunksD.add(chunk);
                } else if (dirs[i] == 3) {
                    chart.hAxisTextU.add(info);
                    chart.hAxisChunksU.add(chunk);
                }
                //logger.debug(info);
            }  // end for ii
        } // end for i

        //水平柱状图特殊处理，匹配柱子所在整个行的信息作为刻度
        if( false && chart.type == ChartType.COLUMN_CHART && chart.vAxisChunksL.size()<=2 &&
                chart.vAxisChunksR.size()<=2 && chart.pathInfos.size()>0 &&
                (chart.lvAxis != null || chart.rvAxis != null)){
            searchColumnChartVertialScale(chart);
        }

        // 保存斜着刻度信息
        saveOCRScalesTohAxisTexts(chart);

        // 解析多行上下侧刻度信息
        boolean canExpand = true;
        for (int i = 1; i <= 2; i++) {
            if (canExpand) {
               canExpand = expandUpDownScales(chart, textChunks, false);
            }
        }
    }

    /***
     * 处理水平柱状图垂直刻度未解析出来，或仅解析数个的情况
     * 根据柱子高度范围，筛选范围内的单个刻度信息，存入chart中对应的刻度处
     * @param chart
     */
    public static void searchColumnChartVertialScale(Chart chart){
        List<List<Double>> ysCollect = new ArrayList<>();
        int numberColumn = 0;                //柱子的组数（一组可有多个柱子）
        List<TextChunk> textChunks = chart.chunksRemain;
        boolean columnNumberEqual = true;   //假定每种颜色柱子个数都相等

        //统计所有pathInfo中柱子的Y值
        int minNumberColumn = Integer.MAX_VALUE;            //统计pathInfos所有part中柱子个数的最小值
        int maxNumberColumn = 0;                            //统计pathInfos所有part中柱子个数的最大值
        for (int i = 0; i < chart.pathInfos.size(); i++){
            List<Double> xs = new ArrayList<>();
            List<Double> ys = new ArrayList<>();
            GeneralPath path = chart.pathInfos.get(i).path;
            getPathColumnBox(path, xs, ys);
            //只处理所有种类柱子个数相同的情况
            if(numberColumn == 0){
                numberColumn = ys.size()/2;
            }else{
                if(numberColumn != ys.size()/2){
                    columnNumberEqual = false;
                }
            }
            minNumberColumn = ys.size()/2 < minNumberColumn ? ys.size()/2 : minNumberColumn;
            maxNumberColumn = ys.size()/2 > maxNumberColumn ? ys.size()/2 : maxNumberColumn;
            Collections.sort(ys);
            ysCollect.add(ys);
        }

        //增加某个柱子颜色只出现一次的处理方法
        if(!columnNumberEqual){
            if(chart.pathInfos.size()==2 && minNumberColumn==1){
                ysCollect.get(0).addAll(ysCollect.get(1));
                ysCollect.remove(1);
                Collections.sort(ysCollect.get(0));
                numberColumn = maxNumberColumn + 1;
            }else{
                return;
            }
        }
        //增加每个柱子颜色不同但只出现一次的处理方法
        else if(numberColumn == 1){
            for(int i=ysCollect.size()-1; i>=1; i--){
                ysCollect.get(0).addAll(ysCollect.get(i));
                ysCollect.remove(i);
            }
            Collections.sort(ysCollect.get(0));
            numberColumn = chart.pathInfos.size();
        }

        //不处理解析出的刻度个数大于等于柱子个数的情况
        if(numberColumn <= Math.max(chart.vAxisChunksL.size(),chart.vAxisChunksR.size())){
            return;
        }

        //获得垂直刻度的X值
        double xAxis = 0;
        if(chart.rvAxis != null){
            xAxis = (chart.rvAxis.getX1() + chart.rvAxis.getX2())/2;
        }else{
            xAxis = (chart.lvAxis.getX1() + chart.lvAxis.getX2())/2;
        }

        //循环遍历寻找每组柱子（一组中可以有多个颜色的柱子）Y范围内的刻度值
        List<TextChunk> chunks = new ArrayList<>();
        for(int i = 0 ; i < numberColumn; i++){
            double axisTopMinY = Double.MAX_VALUE;
            double axisBottomMaxY = 0;
            for(int j = 0; j < ysCollect.size(); j++){
                axisTopMinY = ysCollect.get(j).get(2*i) < axisTopMinY ? ysCollect.get(j).get(2*i) : axisTopMinY;
                axisBottomMaxY = ysCollect.get(j).get(2*i+1) > axisBottomMaxY ? ysCollect.get(j).get(2*i+1) : axisBottomMaxY;
            }
            TextChunk chunk = null;
            double distanceToAxis = Double.MAX_VALUE;
            //获得离垂直轴最近的textChunk
            for(TextChunk textChunk : textChunks){
                if(textChunk.getCenterY() < axisBottomMaxY && textChunk.getCenterY() > axisTopMinY){
                    if(Math.min(Math.abs(textChunk.getLeft()-xAxis),Math.abs(textChunk.getRight()-xAxis))
                            < distanceToAxis){
                        distanceToAxis = Math.min(Math.abs(textChunk.getLeft()-xAxis),Math.abs(textChunk.getRight()-xAxis));
                        chunk = textChunk;
                    }
                }
            }
            //chunk的值为空就不添加
            if(chunk == null){
                continue;
            }
            chunks.add(chunk);
        }

        //清除原有的刻度信息
        if(chart.rvAxis != null){
            chart.vAxisTextR.clear();
            chart.vAxisChunksR.clear();
        }else{
            chart.vAxisTextL.clear();
            chart.vAxisChunksL.clear();
        }

        //存储新的刻度信息
        Collections.reverse(chunks);   //倒序（目前所有柱子都是从下往上画的）
        for (TextChunk chunk : chunks) {
            //添加字体类型（目前没用）
            chart.addFontName(chunk.getFontNames());
            String info = chunk.getText().trim();
            ChunkUtil.setChunkType(chunk, ChunkStatusType.SCALE_TYPE);    //若解析不对，设置的“刻度类型”会不会对后面的解析产生影响？？？
            if (chart.lvAxis != null) {
                chart.vAxisTextL.add(info);
                chart.vAxisChunksL.add(chunk);
            } else{
                chart.vAxisTextR.add(info);
                chart.vAxisChunksR.add(chunk);
            }
        }
    }

    /**
     * 对水平柱状图 左侧候选刻度进行适当的合并操作
     * @param chart
     * @param chunks
     */
    private static void resetColumnLeftAxis(Chart chart, List<TextChunk> chunks) {
        if (chart.type != ChartType.COLUMN_CHART) {
            return;
        }
        if (chunks.size() <= 2) {
            return;
        }
        double height = chart.getHeight();
        // 过滤掉垂直坐标轴外部的
        if (chart.lvAxis != null) {
            double ymin = Math.min(chart.lvAxis.getY1(), chart.lvAxis.getY2());
            double ymax = Math.max(chart.lvAxis.getY1(), chart.lvAxis.getY2());
            Iterator<TextChunk> chunkIterator = chunks.iterator();
            while (chunkIterator.hasNext()) {
                TextChunk chunk = chunkIterator.next();
                if (chunk.getMinY() > ymax + 0.02 * height ||
                        chunk.getMaxY() < ymin - 0.02 * height) {
                    chunkIterator.remove();
                }
            }  // end for ii
        }

        // 如果存在非水平方向文本  则直接返回
        if (chunks.stream().anyMatch(chunk -> chunk.getDirection() != TextDirection.LTR)) {
            return;
        }

        // 获取同色柱状的最大矩形数目
        int maxColumn = 0;
        List<Double> xs = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
        for (ChartPathInfo pathInfo : chart.pathInfos) {
            if (pathInfo.type != PathInfo.PathType.COLUMNAR) {
                continue;
            }
            ChartPathInfosParser.getPathColumnBox(pathInfo.path, xs, ys);
            int count = xs.size() / 2;
            maxColumn = maxColumn < count ? count : maxColumn;
        }
        int n = chunks.size();
        if (n <= maxColumn) {
            return;
        }

        // 统计间距
        List<Double> dists = new ArrayList<>();
        double meanHeight = 0.0;
        for (int i = 0; i < n - 1; i++) {
            TextChunk chunkA = chunks.get(i);
            TextChunk chunkB = chunks.get(i + 1);
            meanHeight += chunkA.getTextHeight();
            double dist = chunkB.getCenterY() - chunkA.getCenterY();
            dists.add(dist);
        }
        meanHeight /= (n - 1);
        double minDist = Collections.min(dists);
        double maxDist = Collections.max(dists);
        if ((maxDist - minDist) < 1.0 * meanHeight || minDist > 2.0 * meanHeight || maxDist < 2.0 * meanHeight) {
            return;
        }

        // 就近适当合并
        for (int i = 0; i < n - 1; i++) {
            TextChunk chunkA = chunks.get(i);
            TextChunk chunkB = chunks.get(i + 1);
            double distA = dists.get(i);
            if (distA < 1.5 * minDist) {
                chunkA.merge(chunkB);
                chunks.set(i, null);
                chunks.set(i + 1, chunkA);
            }
        } // end for i

        // 过滤掉null值
        Iterator<TextChunk> chunkIterator = chunks.iterator();
        while (chunkIterator.hasNext()) {
            TextChunk chunk = chunkIterator.next();
            if (chunk == null) {
                chunkIterator.remove();
            }
        }  // end for ii
    }

    /**
     * 判断textchunk是否匹配柱状对象 用来在对刻度进行过滤时提供参考
     * @param chart
     * @param chunk
     * @return
     */
    private static boolean textMatchBar(Chart chart, TextChunk chunk) {
        // 遍历PathInfo内部的矩形集
        List<Double> xs = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
        List<PathInfo.PathType> types = new ArrayList<>();
        types.add(PathInfo.PathType.COLUMNAR);
        types.add(PathInfo.PathType.BAR);
        ChartPathInfosParser.getBarsBoxes(chart.pathInfos, types, xs, ys);
        boolean isVertical = !chart.pathInfos.stream().anyMatch(pathInfo -> pathInfo.type == PathInfo.PathType.COLUMNAR);

        int n = xs.size() / 2;
        double xc = chunk.getCenterX(), yc = chunk.getCenterY();
        double xmin = 0.0, xmax = 0.0, ymin = 0.0, ymax = 0.0;
        // 判断中心代表点是否匹配某个柱状对象
        for (int i = 0; i < n; i++) {
            xmin = Collections.min(xs.subList(2 * i, 2 * (i + 1)));
            xmax = Collections.max(xs.subList(2 * i, 2 * (i + 1)));
            ymin = Collections.min(ys.subList(2 * i, 2 * (i + 1)));
            ymax = Collections.max(ys.subList(2 * i, 2 * (i + 1)));
            if (isVertical) {
                if (xmin < xc && xc < xmax && (yc < ymin || yc > ymax)) {
                    return true;
                }
            }
            else {
                if (ymin < yc && yc < ymax && (xc < xmin || xc > xmax)) {
                    return true;
                }
            }
        } // end for i

        // 判断所有字符中心点匹配某个柱状对象的数目
        List<Double> eleXs = new ArrayList<>();
        List<Double> eleYs = new ArrayList<>();
        for (TextElement textElement : chunk.getElements()) {
            eleXs.add(textElement.getCenterX());
            eleYs.add(textElement.getCenterY());
        }
        int nc = eleXs.size();
        int count = 0;
        for (int j = 0; j < nc; j++) {
            xc = eleXs.get(j);
            yc = eleYs.get(j);
            for (int i = 0; i < n; i++) {
                xmin = Collections.min(xs.subList(2 * i, 2 * (i + 1)));
                xmax = Collections.max(xs.subList(2 * i, 2 * (i + 1)));
                ymin = Collections.min(ys.subList(2 * i, 2 * (i + 1)));
                ymax = Collections.max(ys.subList(2 * i, 2 * (i + 1)));
                if (isVertical) {
                    if (xmin < xc && xc < xmax && (yc < ymin || yc > ymax)) {
                        count++;
                        break;
                    }
                } else {
                    if (ymin < yc && yc < ymax && (xc < xmin || xc > xmax)) {
                        count++;
                        break;
                    }
                }
            }
        }
        if (1.0 * count / nc > 0.5) {
            return true;
        }
        return false;
    }

    /**
     * 判断左侧刻度是否有效
     * 目前总结出：
     * 容易将左侧某个柱状对象上方的标记属性和下次的刻度误检为左侧刻度的情况，　此时左侧刻度只有两个
     * @param chart
     * @param chunks
     * @return
     */
    private static boolean isValidLeftRightScale(Chart chart, List<TextChunk> chunks) {
        if (chart.type == ChartType.BAR_CHART && chunks.size() == 2) {
            Rectangle2D box1 = chunks.get(0);
            Rectangle2D box2 = chunks.get(1);
            double x1 = box1.getCenterX();
            double y1 = box1.getCenterY();
            double x2 = box2.getCenterX();
            double y2 = box2.getCenterY();
            // 遍历PathInfo内部的矩形集
            int n = 0;
            List<Double> pathXs = new ArrayList<>();
            List<Double> pathYs = new ArrayList<>();
            double xmin = 0, xmax = 0, ymin = 0, ymax = 0;
            for (ChartPathInfo pathInfo : chart.pathInfos) {
                ChartPathInfosParser.getPathColumnBox(pathInfo.path, pathXs, pathYs);
                n = pathXs.size() / 2;
                for (int i = 0; i < n; i++) {
                    xmin = pathXs.get(2 * i);
                    xmax = pathXs.get(2 * i + 1);
                    ymin = pathYs.get(2 * i);
                    ymax = pathYs.get(2 * i + 1);
                    if (xmin < x1 && x1 < xmax && xmin < x2 && x2 < xmax) {
                        if  (y1 > ymax || y2 > ymax) {
                            return false;
                        }
                    }
                }
            } // end for i
        }
        return true;
    }


    /**
     * 检查Backup内是否可以加入ChunksFilter
     * 仅针对柱状图下侧刻度开启
     *
     * @param chart
     * @param chunksFilter
     * @param chunksFilterBackup
     * @return
     */
    private static void mergeBackup(Chart chart, List<TextChunk> chunksFilter, List<TextChunk> chunksFilterBackup) {
        if (chunksFilterBackup.isEmpty() || chart.hAxis == null) {
            return;
        }

        List<Double> xs = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
        for (TextChunk textChunk : chunksFilterBackup) {
            for (ChartPathInfo pathInfo : chart.pathInfos) {
                if (ChartContentScaleParser.getPathColumnPoints(pathInfo.path, true, chart.getArea(), chart.hAxis, xs, ys)) {
                    for (int i = 0; i < xs.size() / 2; i++) {
                        double barCenterX = (xs.get(i) + xs.get(i + 1)) / 2;
                        if (textChunk.getRight() > barCenterX && textChunk.getLeft() < barCenterX) {
                            chunksFilter.add(textChunk);
                        }
                    }
                }
            }
        }
    }

    /**
     * 对给定TextChunk集　重排序和合并
     * pdf Chart　内部的刻度TextChunk　顺序可能是乱序
     * @param chunks
     * @return
     */
    public static List<TextChunk> resetOrderAndMergeNearText(List<TextChunk> chunks) {
        // 判断是否都是水平方向文本对象
        if (chunks.stream().allMatch(chunk -> chunk.getDirection() != TextDirection.LTR)) {
            return chunks;
        }

        // 如果数目过少　无法判断是否需要分割
        if (chunks.size() <= 1) {
            return chunks;
        }

        //给chunks中元素排序（按照x的坐标）
        List<ChunkInfo> sortTmpChunks = new ArrayList<>();
        for(int i=0;i<chunks.size();i++){
            sortTmpChunks.add(new ChunkInfo(chunks.get(i).x,i));
        }
        Collections.sort(sortTmpChunks);
        List<TextChunk> sortChunksX = new ArrayList<>();
        for(int i=0;i<sortTmpChunks.size();i++){
            sortChunksX.add(chunks.get(sortTmpChunks.get(i).ith));
        }

        // 判断是否在同一行 即同高度
        double height = sortChunksX.get(0).getHeight();
        for(int i = 1; i < sortChunksX.size(); i++) {
            if (Math.abs(sortChunksX.get(i).y - sortChunksX.get(0).y) >= 0.1 * height) {
                return chunks;
            }
        }

        List<TextChunk> merged = new ChartTextChunkMerger(1.2f).merge(sortChunksX , ChartType.UNKNOWN_CHART);
        // 拆分包含连续空格的TextChunk
        merged = merged.stream()
                .flatMap(textChunk -> ChartTextMerger.squeeze(textChunk, ' ', 3).stream())
                .collect(Collectors.toList());
        return merged;
    }

    /**
     * 判断给定候选水平刻度集是否不需要进行分割操作
     * 满足如下条件之一即可：
     * 1. 非水平方向的文本
     * 2. 同时满足 同行同高度，　等间隔，　大间距
     * @param chunks
     * @return
     */
    public static boolean isNotNeedSplit(List<TextChunk> chunks) {
        // 判断是否都是水平方向文本对象
        if (chunks.stream().allMatch(chunk -> chunk.getDirection() != TextDirection.LTR)) {
            return true;
        }

        // 如果数目过少　无法判断是否需要分割
        if (chunks.size() <= 2) {
            return false;
        }

        //给chunks中元素排序（按照x的坐标）
        List<ChunkInfo> sortTmpChunks = new ArrayList<>();
        for(int i=0;i<chunks.size();i++){
            sortTmpChunks.add(new ChunkInfo(chunks.get(i).x,i));
        }
        Collections.sort(sortTmpChunks);
        List<TextChunk> sortChunksX = new ArrayList<>();
        for(int i=0;i<sortTmpChunks.size();i++){
            sortChunksX.add(chunks.get(sortTmpChunks.get(i).ith));
        }

        // 遍历判断是否 同时满足 同行同高度，　等间隔，　大间距
        double distBase = Math.abs(sortChunksX.get(1).getCenterX() - sortChunksX.get(0).getCenterX());
        double height = sortChunksX.get(0).getHeight();
        for(int i = 1; i < sortChunksX.size(); i++) {
            TextChunk chunkA = sortChunksX.get(i);
            TextChunk chunkB = sortChunksX.get(i - 1);
            // 判断间隔是否大于最大字符宽度的一定倍数　即　间隔比字符宽大不少
            double dist = Math.abs(chunkA.getLeft() - chunkB.getRight());
            double maxChartWidth = Math.max(chunkA.getMaxTextWidth(), chunkB.getMaxTextWidth());
            if (dist < 2.5 * maxChartWidth) {
                return false;
            }
            // 判断是否等间隔
            double distCenter = Math.abs(chunkA.getCenterX() - chunkB.getCenterX());
            double distMax = Math.min(distBase, distCenter);
            if (Math.abs(distBase - distCenter) >= 0.1 * distMax) {
                return false;
            }
            //必须在同一行 即同高度
            if (Math.abs(sortChunksX.get(i).y - sortChunksX.get(i - 1).y) >= 0.1 * height) {
                return false;
            }
        }
        // 如果满足条件　则返回不需要分割的判断
        return true;
    }


    /**
     * 尝试基于空白符或间距分割给定Chunk集中的对象 (上下测 X 轴刻度经常需要分割)
     *
     * @param chart
     * @param chunks
     * @param bySpace
     * @return
     */
    public static List<TextChunk> testSplitChunks(
            Chart chart, List<TextChunk> chunks, boolean bySpace) {
        // 初步判断是否需要细分
        if (isNotNeedSplit(chunks)) {
            return chunks;
        }

        // 如果是柱状图，并且图例的个数已经小于或者等于柱子的分组个数了，那么就不要继续对
        // 图例进行空格切割了
        /*if (bySpace && chart.type == ChartType.BAR_CHART) {
            for (ChartPathInfo pinfo : chart.pathInfos) {
                if (pinfo.type == PathInfo.PathType.BAR)
                {
                    List<Double> xs = new ArrayList<>();
                    List<Double> ys = new ArrayList<>();
                    ChartPathInfosParser.getPathColumnBox(pinfo.path, xs,ys);
                    if(chunks.size() <= (xs.size() / 2)){
                        ArrayList<TextChunk> chunksAfter = new ArrayList<>();
                        chunksAfter.addAll(chunks);
                        return chunksAfter;
                    }
                }
            }
        }*/

        List<String> specialTimeWords = RegularMatch.getInstance().dateSuffixFormat;
        ArrayList<TextChunk> chunksAfter = new ArrayList<>();

        //给chunks中元素排序（按照x的坐标）
        List<ChunkInfo> sortTmpChunks = new ArrayList<>();
        for(int i=0;i<chunks.size();i++){
            sortTmpChunks.add(new ChunkInfo(chunks.get(i).x,i));
        }
        Collections.sort(sortTmpChunks);
        List<TextChunk> sortChunksX = new ArrayList<>();
        for(int i=0;i<sortTmpChunks.size();i++){
            sortChunksX.add(chunks.get(sortTmpChunks.get(i).ith));
        }

        //统计chunks元素间的间隔
        float distanceAverage = 0f;
        float distanceSplit = 0f;
        boolean flag = false;
        boolean isequal = true;
        for(int i=1;i<sortChunksX.size();i++){
            distanceAverage += Math.abs(sortChunksX.get(i).getLeft() - sortChunksX.get(i - 1).getRight());
            //必须在同一行
            if(Math.abs(sortChunksX.get(i).y-sortChunksX.get(i-1).y)>=sortChunksX.get(i-1).height/10){
                isequal = false;
                break;
            }
            //必须除去空格后等长
            if(sortChunksX.get(i).getText().replaceAll(" ","").length()
                    !=sortChunksX.get(i-1).getText().replaceAll(" ","").length()){
                isequal = false;
            }
        }
        if(chunks.size()>1){
            distanceAverage /= (chunks.size()-1);
        }


        for (int i = 0; i < chunks.size(); i++) {
            TextChunk chunk = chunks.get(i);
            // 如果不是水平方向 则通常不需要分割
            if (chunk.getDirection() != TextDirection.LTR) {
                chunksAfter.add(chunk);
                continue;
            }
            /*
            // 过滤掉时间类型的
            String text = chunk.getText().replaceAll("[\\s　]*", "");
            if (isStringMatchTime(text)) {
                chunksAfter.add(chunk);
                continue;
            }
            */

            // 如果是前面基于图形对象平分位置分割后的Chunk 则通常不需要再次分割
            ChunkStatusType type = ChunkUtil.getChunkType(chunk);
            if (type == ChunkStatusType.MERGE_TYPE) {
                chunksAfter.add(chunk);
                continue;
            }

            int nSpace = 1;
            Pattern regExPureEnglishPattern = RegularMatch.getInstance().regExPureEnglishPattern;
            Matcher m = regExPureEnglishPattern.matcher(chunk.getText());
            if (m.matches()) {
                nSpace = 2;
            }

            // 根据给定参数 采用不同分割方式
            List<TextChunk> chunksNew = new ArrayList<>();
            if (bySpace) {
                chunksNew = ChunkUtil.splitBySpaces(chunk, nSpace);
            } else {
                chunksNew = ChunkUtil.splitByInterval(chunk, 1.3f);
            }
            // 如果分割后少于两个对象 则说明没有变化
            if (chunksNew.size() <= 1) {
                chunksAfter.add(chunk);
                continue;
            } else {
                // 根据经验将某些时间信息 再次合并
                TextChunk wordBefore = null;
                final Iterator<TextChunk> eachword = chunksNew.iterator();
                while (eachword.hasNext()) {
                    TextChunk word = eachword.next();
                    String str = word.getText();

                    //根据比值关系判断是否合并
                    if(wordBefore != null){
                        distanceSplit = Math.abs(word.getLeft()-wordBefore.getRight());
                        if(distanceSplit > 1E-3 && (distanceAverage-distanceSplit)/distanceSplit > 2 && isequal){
                            flag = true;
                        }
                    }

                    if (wordBefore != null && (specialTimeWords.stream().anyMatch(s -> str.startsWith(s)) || flag )) {
                        groupTwoTextChunk(wordBefore, word, false);
                        eachword.remove();
                        flag = false;
                    }else {
                        wordBefore = word;
                    }
                }
                for (TextChunk chunkn : chunksNew) {
                    if (!StringUtils.isEmpty(chunkn.getText())) {
                        chunksAfter.add(chunkn);
                    }
                    chart.chunksRemain.add(chunkn);
                }
                int id = chart.chunksRemain.indexOf(chunk);
                if (id >= 0 && id < chart.chunksRemain.size()) {
                    chart.chunksRemain.set(id, null);
                }
                chunks.set(i, null);
            }
        }  // end for
        // 过滤掉分裂过的Chunk
        final Iterator<TextChunk> each = chart.chunksRemain.iterator();
        while (each.hasNext()) {
            TextChunk chunk = each.next();
            if (chunk == null) {
                each.remove();
            }
        }
        return chunksAfter;
    }

    /**
     * 将解析后的斜着刻度信息保存到下侧刻度信息集中
     *
     * @param chart
     */
    private static void saveOCRScalesTohAxisTexts(Chart chart) {
        // 判断数据有效性
        if (!chart.hAxisTextD.isEmpty() || !chart.hAxisTextU.isEmpty() || chart.ocrs.isEmpty()) {
            return;
        }

        // 遍历存储　非空字符串内容
        for (int i = 0; i < chart.ocrs.size(); i++) {
            OCRPathInfo ocr = chart.ocrs.get(i);
            if (ocr.text.equals("")) {
                continue;
            }
            chart.hAxisTextD.add(ocr.text);
        }
        if (chart.hAxisTextD.isEmpty()) {
            //chart.ocrs.clear();
        }
    }

    /**
     * 尝试水平方向扩展图例文字信息
     * @param chart
     * @param chunks
     */
    private static void expandHorizonLegends(Chart chart, List<TextChunk> chunks) {
        if (chart.legendsChunks.size() != chart.legends.size()) {
            return;
        }
        // 遍历下侧刻度信息TextChunk集
        ChunkStatusType type = ChunkStatusType.LEGEND_TYPE;
        for (int i = 0; i < chart.legendsChunks.size(); i++) {
            TextChunk obj = chart.legendsChunks.get(i);

            //排除图例右边存在刻度，从而融合刻度到图例中的情况
//            double expandMaxX = obj.getMaxX();
//            int count = -1;    //图例本身会占有一个
//            for (int j = 0; j < chunks.size(); j++){
//                TextChunk chunkTmp = chunks.get(j);
//                if(chunkTmp.getCenterY()<obj.getBottom() && chunkTmp.getCenterY()>obj.getTop()){
//                    count++;
//                    expandMaxX = chunkTmp.getMaxX() > expandMaxX ? chunkTmp.getMaxX() : expandMaxX;
//                }
//            }
//            if(expandMaxX-obj.getMinX() > 0.85*chart.getWidth() && count >= 5){
//                continue;
//            }

            obj = expandHorizon(obj, chunks, true, type);
            chart.legends.get(i).text = obj.getText().trim();
        }
    }

    /**
     * 扩展上下侧图列信息　解析两行图列信息 开发中
     *
     * @param chart
     * @param chunks
     * @param bUp
     */
    private static void expandUpDownLegends(
            Chart chart,
            List<TextChunk> chunks,
            boolean bUp) {
        if (chart.legendsChunks.size() != chart.legends.size()) {
            return;
        }
        // 遍历下侧刻度信息TextChunk集
        for (int i = 0; i < chart.legendsChunks.size(); i++) {
            TextChunk obj = chart.legendsChunks.get(i);
            boolean bExpand = expandChunkVertical(obj, chunks, bUp, ChunkStatusType.LEGEND_TYPE, true);
            if (bExpand) {
                chart.legends.get(i).text = obj.getText().trim();
            }
        }
    }

    /**
     * 扩展上下侧刻度信息　解析两行刻度信息 开发中
     *
     * @param chart
     * @param chunks
     * @param bUp
     */
    private static boolean expandUpDownScales(
            Chart chart,
            List<TextChunk> chunks,
            boolean bUp) {
        // 遍历下侧刻度信息TextChunk集
        boolean canExpane = false;
        for (int i = 0; i < chart.hAxisChunksD.size(); i++) {
            TextChunk obj = chart.hAxisChunksD.get(i);
            boolean bExpand = expandChunkVertical(obj, chunks, bUp, ChunkStatusType.SCALE_TYPE, true);
            if (bExpand) {
                chart.hAxisTextD.set(i, obj.getText());
                canExpane = true;
            }
        }

        // 遍历上侧刻度信息TextChunk集
        for (int i = 0; i < chart.hAxisChunksU.size(); i++) {
            TextChunk obj = chart.hAxisChunksU.get(i);
            boolean bExpand = expandChunkVertical(obj, chunks, bUp, ChunkStatusType.SCALE_TYPE, true);
            if (bExpand) {
                chart.hAxisTextU.set(i, obj.getText());
                canExpane = true;
            }
        }
        return canExpane;
    }

    /**
     * 在候选TextChunk集指定范围对象中找出与给定TextChunk对象向上或向下紧密相邻的对象
     *
     * @param obj
     * @param chunks
     * @param iStart
     * @param iEnd
     * @param bUp
     * @param ctype
     * @param doExpand
     * @return
     */
    public static boolean expandChunkVertical(
            TextChunk obj,
            List<TextChunk> chunks,
            int iStart,
            int iEnd,
            boolean bUp,
            ChunkStatusType ctype,
            boolean doExpand) {
        // 判断数据有效性
        if (obj == null || chunks == null) {
            return false;
        }
        int n = chunks.size();
        if (iStart < 0 || iStart >= n || iStart > iEnd || iEnd >= n) {
            return false;
        }
        List<TextElement> eles = obj.getElements();
        if (eles.isEmpty() || obj.getDirection() != TextDirection.LTR) {
            return false;
        }
        int objGroupIndex = eles.get(eles.size() - 1).getGroupIndex();

        // 找出当前TextChunk的中心点靠上的位置　然后向上扩展一定的距离　经验值
        double h = ChunkUtil.getMaxFontHeight(obj);
        double w = ChunkUtil.getMaxFontWidth(obj);
        double xc = obj.getBounds2D().getCenterX();
        double xL = obj.getBounds2D().getX();
        double y = obj.getBounds2D().getMaxY();
        double miny = obj.getBounds2D().getMinY();
        double maxy = obj.getBounds2D().getMaxY();
        double coef = 1.0;
        if (bUp) {
            coef *= -1.0;
            y = miny;
        }

        Rectangle2D boxA = new Line2D.Double(xc, y, xc, y + coef*h).getBounds2D();
        GraphicsUtil.extendRect(boxA, 1);
        Rectangle2D boxB = new Line2D.Double(xL, y, xL, y + coef*h).getBounds2D();
        GraphicsUtil.extendRect(boxB, 1);

        // 遍历找出在给定TextChunk正下方的对象
        for (int i = iStart; i <= iEnd; i++) {
            TextChunk chunk = chunks.get(i);
            if (chunk.equals(obj)) {
                continue;
            }
            if (chunk.getText().equals("") || chunk.getElements().isEmpty()) {
                continue;
            }
            // 如果向下扩展则找出组号在给定对象后面的对象
            int cGroupIndex = chunk.getElements().get(0).getGroupIndex();
            if (!bUp) {
                if (cGroupIndex <= objGroupIndex || chunk.getBounds().getCenterY() <= miny) {
                    continue;
                }
            }
            // 如果向上扩展则找出组号在给定对象前面的对象
            else {
                if (cGroupIndex >= objGroupIndex || chunk.getBounds().getCenterY() >= maxy) {
                    continue;
                }
            }
            // 如果是垂直排列方向　则跳过
            if (chunk.getDirection() == TextDirection.VERTICAL_UP ||
                    chunk.getDirection() == TextDirection.VERTICAL_DOWN) {
                continue;
            }
            // 如果已经标记过类型　则跳过
            ChunkStatusType type = ChunkUtil.getChunkType(chunk);
            if (type != ChunkStatusType.UNKNOWN_TYPE) {
                if (type != ChunkStatusType.MERGE_TYPE) {
                    continue;
                }
            }

            // 如果扩展图例信息　则下侧对应左对齐 如果左侧相隔大于一个字符宽度　则跳过
            if (ctype == ChunkStatusType.LEGEND_TYPE) {
                double cxLeft = chunk.getBounds2D().getMinX();
                if (cxLeft <= xL - w || cxLeft >= xL + w) {
                    continue;
                }
            }

            // 如果中心和左侧位置往上一点或往下一点的位置在当前TextChunk内部 且前后元素的组号想邻　则扩展
            if ((chunk.getBounds2D().intersects(boxA) || chunk.getBounds2D().intersects(boxB)) &&
                    cGroupIndex <= objGroupIndex + 2) {
                if (doExpand) {
//                    if (obj.getFirstElement().getColor().equals(chunk.getFirstElement().getColor())) {
                        // TODO 将英文 + 英文的情况 中间加入空格
                        String first = obj.getText();
                        String second = chunk.getText();
                        boolean fillSpace = false;

                        if (first.length() < 2 || second.length() < 2) {
                            groupTwoTextChunk(obj, chunk, false);
                        } else if (Character.isLetter(first.charAt(first.length() - 1)) &&
                                Character.isLetter(first.charAt(first.length() - 2)) &&
                                Character.isLetter(second.charAt(0)) &&
                                Character.isLetter(second.charAt(1))) {
                            // 如果第一个Chunk以两个英文字母结尾
                            // 并且第二个Chunk以两个英文字母开始
                            // 这种情况下 在两个Chunk合并的时候加入空格
                            groupTwoTextChunk(obj, chunk, true);
                        } else {
                            groupTwoTextChunk(obj, chunk, false);
                        }
                        ChunkUtil.setChunkType(chunk, ctype);
                        chunks.remove(i);
//                    }
                }
                return true;
            }
        } // end for i
        return false;
    }

    /**
     * 在给定后续TextChunk集中找出向上或向下紧密相邻的对象
     * 存在多行的刻度信息和图例信息
     *
     * @param obj
     * @param chunks
     * @param bUp
     * @param ctype
     * @param doExpand
     * @return
     */
    public static boolean expandChunkVertical(
            TextChunk obj,
            List<TextChunk> chunks,
            boolean bUp,
            ChunkStatusType ctype,
            boolean doExpand) {
        if (obj == null || chunks == null || chunks.isEmpty()) {
            return false;
        }
        return expandChunkVertical(obj, chunks, 0, chunks.size() - 1, bUp, ctype, doExpand);
    }

    /**
     * 基于刻度信息通常对齐的规律　对不对齐的chunk进行过滤
     *
     * @param chunks 当前区域的chunk集
     * @param ch     当前chart的高度
     * @param cw     当前chart的宽度
     * @param i      当前处理的区域的编号
     * @param type   当前chart的类型
     */
    private static void filterChunk(
            List<TextChunk> chunks,
            float ch, float cw, int i, ChartType type) {
        // 过滤掉空白符
        final Iterator<TextChunk> eachOne = chunks.iterator();
        while (eachOne.hasNext()) {
            if (StringUtils.isEmpty(eachOne.next().getText())) {
                eachOne.remove();
            }
        }
        if (chunks.isEmpty()) {
            return;
        }

        boolean filterSwitch = false;      //开启刻度筛选的开关（true开启，开启后刻度更易匹配）
        double maxTopOrLeft = 0;
        double minBottomOrRight = Double.MAX_VALUE;
        // 计算各区域相应的对称边界信息
        List<Integer> nums = new ArrayList<>();
        double xy = 0.0f;
        double deta = 0.0f;               // 对齐的误差长度
        for (TextChunk textChunk : chunks) {
            if (i == 0) {                 // 左侧区域　找出 chunk 的 right
                xy = textChunk.getRight();
            } else if (i == 1) {            // 右侧区域　找出 chunk 的 left
                xy = textChunk.getLeft();
            } else if (i == 2) {            // 下侧区域　找出 chunk 的 top
                xy = textChunk.getTop();
            } else {                        // 上侧区域　找出 chunk 的 bottom
                xy = textChunk.getBottom();
            }
            nums.add(Math.round((float) xy));
            deta += ChunkUtil.getMaxFontWidth(textChunk);
        } // end for chunk

        // 计算众数　作为对称边界的值
        double bound = 1.0f * ChartPathInfosParser.getMode(nums);
        double boundxy = 0.0f;
        deta /= chunks.size();           // 取平均 作为容差
        deta *= 1.1;

        // 各侧的chunk　都近似对齐 过滤掉不对齐的　或　高度或宽度过大的chunk  其中的经验参数可以调整
        // 一般左右侧和上下侧的规律不一样　上侧出现刻度的情况不多
        final Iterator<TextChunk> each = chunks.iterator();
        boolean containSpecial = false;
        while (each.hasNext()) {
            TextChunk chunk = each.next();
            double coefHeight = chunk.getHeight() / ch;
            double coefWidth = chunk.getWidth() / cw;
            if (i == 0) {                // 左侧区域 的 chunk 的right在对齐边界的一定容差范围内 　高度不能太大
                if(chunk.getText().equals("-")){
                    //处理左侧以“-”为刻度的情况，此时“-”代表数字0
                    containSpecial = true;
                } else {
                    boundxy = chunk.getRight();
                    if (boundxy > (bound + deta) || boundxy < bound - deta) {
                        if(filterSwitch && maxTopOrLeft>1E-4 &&
                                chunk.getCenterX()>maxTopOrLeft && chunk.getCenterX()<minBottomOrRight){
                            continue;
                        }
                        each.remove();
                    } else if (coefHeight > 0.2f) {
                        if (type != ChartType.COLUMN_CHART || coefHeight > 0.5f) {
                            each.remove();
                        }
                    } else if(filterSwitch){
                        maxTopOrLeft = chunk.getLeft() > maxTopOrLeft ? chunk.getLeft() : maxTopOrLeft;
                        minBottomOrRight = chunk.getRight() < minBottomOrRight ? chunk.getRight() : minBottomOrRight;
                    }
                }
            } else if (i == 1) {           // 右侧区域 的 chunk 的left在对齐边界的一定容差范围内 　高度不能太大
                boundxy = chunk.getLeft();
                if (boundxy < (bound - deta) || boundxy > bound + deta) {
                    if(filterSwitch && maxTopOrLeft>1E-4 &&
                            chunk.getCenterX()>maxTopOrLeft && chunk.getCenterX()<minBottomOrRight){
                        continue;
                    }
                    each.remove();
                } else if (coefHeight > 0.2f) {
                    if (type != ChartType.COLUMN_CHART || coefHeight > 0.5f) {
                        each.remove();
                    }
                } else if(filterSwitch){
                    maxTopOrLeft = chunk.getLeft() > maxTopOrLeft ? chunk.getLeft() : maxTopOrLeft;
                    minBottomOrRight = chunk.getRight() < minBottomOrRight ? chunk.getRight() : minBottomOrRight;
                }
            } else if (i == 2) {           // 下侧区域 的 chunk 的top在对齐边界的一定容差范围内 　宽度不能太大
                boundxy = chunk.getTop();
                if (boundxy > (bound + deta) || boundxy < bound - deta) {
                    //不符合对齐的刻度，判断其重心点是否在其他刻度的最小范围内
                    if(filterSwitch && maxTopOrLeft>1E-4 &&
                            chunk.getCenterY()>maxTopOrLeft && chunk.getCenterY()<minBottomOrRight){
                        continue;
                    }
                    each.remove();
                } else if (coefWidth >= 0.5f) {
                    each.remove();
                } else if (filterSwitch){
                    maxTopOrLeft = chunk.getTop() > maxTopOrLeft ? chunk.getTop() : maxTopOrLeft;
                    minBottomOrRight = chunk.getBottom() < minBottomOrRight ? chunk.getBottom() : minBottomOrRight;
                }
            } else if (i == 3) {           // 上侧区域 的 chunk 的bottom在对齐边界的一定容差范围内 　宽度不能太大
                boundxy = chunk.getBottom();
                if (boundxy < (bound - deta) || boundxy > bound + deta) {
                    if(filterSwitch && maxTopOrLeft>1E-4 &&
                            chunk.getCenterY()>maxTopOrLeft && chunk.getCenterY()<minBottomOrRight){
                        continue;
                    }
                    each.remove();
                } else if (coefWidth >= 0.5f) {
                    each.remove();
                } else if(filterSwitch){
                    maxTopOrLeft = chunk.getTop() > maxTopOrLeft ? chunk.getTop() : maxTopOrLeft;
                    minBottomOrRight = chunk.getBottom() < minBottomOrRight ? chunk.getBottom() : minBottomOrRight;
                }
            }
        } // end while

        if(containSpecial){
            for(int j = 0; j < chunks.size(); j++){
                TextChunk current = chunks.get(j);
                if(current.getText().equals("-")){
                    if(j - 1 >= 0){
                        TextChunk prevT = chunks.get(j - 1);
                        if ((Math.abs(prevT.getGroupIndex() - current.getGroupIndex()) > 3)){
                            chunks.remove(current);
                        }
                    }
                    if(j + 1 < chunks.size()){
                        TextChunk nextT = chunks.get(j + 1);
                        if(Math.abs(nextT.getGroupIndex() - current.getGroupIndex()) > 3){
                            chunks.remove(current);
                        }
                    }
                }
            }//end for
        }
    }


    /**
     * 过滤掉位于坐标轴无效侧的候选刻度信息
     *
     * @param chart   给定Chart
     * @param ithside 坐标轴编号
     * @param chunks  给定候选刻度信息chunk集
     */
    private static void filterAxisInvalidSideScale(
            Chart chart, int ithside, List<TextChunk> chunks) {
        if (chunks.size() <= 1) {
            return;
        }

        // 统计　候选刻度信息在坐标轴两侧的状态信息
        List<TextChunk> chunksInvalidSide = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            TextChunk chunk = chunks.get(i);
            if (ithside == 0 && chart.lvAxis != null &&
                    chunk.getBounds().getMaxX() >= chart.lvAxis.getX1()) {
                chunksInvalidSide.add(chunk);
            } else if (ithside == 1 && chart.rvAxis != null &&
                    chunk.getBounds().getMinX() <= chart.rvAxis.getX1()) {
                chunksInvalidSide.add(chunk);
            } else if (ithside == 2 && chart.hAxis != null &&
                    chunk.getBounds().getMaxY() >= chart.hAxis.getY1()) {
                chunksInvalidSide.add(chunk);
            } else if (ithside == 3 && chart.hAxis != null &&
                    chunk.getBounds().getMinY() <= chart.hAxis.getY1()) {
                chunksInvalidSide.add(chunk);
            }
        } // end for i

        // 如果没有可能异常的刻度信息 则直接返回
        if (chunksInvalidSide.isEmpty()) {
            return;
        }

        // 过滤掉　少数一侧的部分后续刻度信息 目前取的经验系数为　0.5  后续可能需要改进
        double f = 1.0 * chunksInvalidSide.size() / chunks.size();
        final Iterator<TextChunk> each = chunks.iterator();
        while (each.hasNext()) {
            TextChunk chunk = each.next();
            if (f < 0.5 && chunksInvalidSide.contains(chunk)) {
                each.remove();
            } else if (f > 0.5 && !chunksInvalidSide.contains(chunk)) {
                each.remove();
            }
        } // end while
    }

    /**
     * 判断给定chunks是否为包含特定前后缀的特殊数值序列
     * @param chunks
     * @param suffixs
     * @return
     */
    private static boolean isDiscontinuitiesSpecialNumber(List<TextChunk> chunks, List<String> suffixs) {
        // 如果数目过少 无法判断断续性
        if (chunks.size() <= 1) {
            return false;
        }
        double [] num = {0.0};
        String [] suffix = {""};
        List<Double> xs = new ArrayList<>();
        // 判断是否为数值类型　和　前后缀是否满足给定条件
        for (TextChunk chunk : chunks) {
            String text = chunk.getText().trim();
            if (ChartContentScaleParser.string2Double(text, num, suffix)) {
                if (StringUtils.isNotEmpty(suffix[0]) && suffixs.contains(suffix[0])) {
                    xs.add(num[0]);
                }
            }
            else {
                return false;
            }
        }
        // 判断数目是否一致
        if (xs.size() != chunks.size()) {
            return false;
        }

        // 如果只有两个文本　则判断group id号是否连续
        if (xs.size() == 2) {
            TextChunk chunk1 = chunks.get(0);
            TextChunk chunk2 = chunks.get(1);
            if (chunk2.getGroupIndex() - chunk1.getGroupIndex() >= 2) {
                return true;
            }
        }

        // 如果有多个文本　则判断数值连续性
        double dxStart = xs.get(1) - xs.get(0), dx = 0.0;
        for (int i = 1; i < xs.size() - 1; i++) {
            dx = xs.get(i + 1) - xs.get(i);
            if (Math.abs(dx - dxStart) >= 1E-3) {
                return true;
            }
        }
        return false;
    }

    /**
     * 如果刻度信息中大部分含有数字，过滤掉没有含有数字的  一般用在左侧右侧刻度信息上
     *
     * @param chart
     * @param chunks 给定候选刻度信息chunk集
     */
    private static void filterNotNumberScale(
            Chart chart,
            List<TextChunk> chunks) {
        // 设置纯数字的正则表达式匹配项
        Pattern regExPureNumberPattern = RegularMatch.getInstance().regExPureNumberPattern;
        int n = chunks.size();
        int count = 0;
        // 统计包含数字的TextChunk所占百分比
        for (TextChunk chunk : chunks) {
            String text = chunk.getText().trim();
            Matcher matcher = regExPureNumberPattern.matcher(text);
            if (matcher.find()) {
                count++;
            } else if (text.equals("-")) {
                count++;
            }
        } // end for chunk

        // 如果数字比例过小或接近1.0　则返回
        double f = 1.0 * count / n;
        if (f < 0.75 || f > 0.999) {
            return;
        }

        // 如果过大　则删除不含数字的TextChunk
        filterNonNumberChunk(chart, chunks);
    }

    private static void filterNonNumberChunk(Chart chart, List<TextChunk> chunks) {
        Pattern regExPureNumberPattern = RegularMatch.getInstance().regExPureNumberPattern;
        final Iterator<TextChunk> each = chunks.iterator();
        while (each.hasNext()) {
            TextChunk chunk = each.next();
            String text = chunk.getText();
            Matcher matcher = regExPureNumberPattern.matcher(text);
            if (!matcher.find() && !chunk.getText().equals("-")) {
                // 如果没有匹配适当矩形对象 则过滤掉
                if (!textMatchBar(chart, chunk)) {
                    each.remove();
                }
            }
        } // end while
    }

    /**
     * 如果候选刻度集中大部分文字是水平方向  则过滤掉垂直方向的
     * 如果候选刻度集中大部分文字是垂直方向  则过滤掉水平方向的
     *
     * @param chunks
     */
    private static void filterInValidDirectionScale(List<TextChunk> chunks) {
        int n = chunks.size();
        if (n <= 2) {
            return;
        }
        // 统计水平方向TextChunk所占百分比
        int countLTR = 0, countRotate = 0;
        TextDirection direction = TextDirection.UNKNOWN;
        for (TextChunk chunk : chunks) {
            direction = chunk.getDirection();
            if (direction == TextDirection.LTR ||direction == TextDirection.RTL) {
                countLTR++;
            }
            else if (direction == TextDirection.ROTATED){
                countRotate++;
            }
        } // end for chunk
        final Iterator<TextChunk> each = chunks.iterator();
        double fLTR = 1.0 * countLTR / n;
        double fRotate = 1.0 * countRotate / n;
        List<TextDirection> filterDirs = new ArrayList<>();
        if (fLTR > 0.5) {
            filterDirs.add(TextDirection.ROTATED);
            filterDirs.add(TextDirection.VERTICAL_UP);
            filterDirs.add(TextDirection.VERTICAL_DOWN);
            removeTextChunks(chunks, filterDirs);
        }
        else if (fRotate > 0.5) {
            filterDirs.add(TextDirection.LTR);
            filterDirs.add(TextDirection.RTL);
            filterDirs.add(TextDirection.VERTICAL_UP);
            filterDirs.add(TextDirection.VERTICAL_DOWN);
            removeTextChunks(chunks, filterDirs);
        } else {
            filterDirs.add(TextDirection.LTR);
            filterDirs.add(TextDirection.RTL);
            filterDirs.add(TextDirection.ROTATED);
            removeTextChunks(chunks, filterDirs);
        }
    }

    public static void removeTextChunks(List<TextChunk> chunks, List<TextDirection> dirs) {
        if (chunks == null || chunks.isEmpty() || dirs == null || dirs.isEmpty()) {
            return;
        }
        TextDirection direction = TextDirection.UNKNOWN;
        final Iterator<TextChunk> each = chunks.iterator();
        while (each.hasNext()) {
            TextChunk chunk = each.next();
            direction = chunk.getDirection();
            if (dirs.contains(direction)) {
                each.remove();
            }
        }
    }

    /**
     * 删除给定Chart集中每一个Chart内的无效图例
     *
     * @param charts
     */
    public static void removeInValidLegends(List<Chart> charts) {
        for (Chart chart : charts) {
            chart.removeEmptyLegends();
        }
    }

    /**
     * 删除内容为空的无效图例
     *
     * @param legends 给定图例集
     */
    public static void removeEmptyLegends(List<Chart.Legend> legends) {
        if (legends == null || legends.isEmpty()) {
            return;
        }
        final Iterator<Chart.Legend> each = legends.iterator();
        while (each.hasNext()) {
            Chart.Legend legend = each.next();
            if (legend.text == null) {
                each.remove();
            }
        }
    }

    /**
     * 获得给定Chart内部标记过Chunk集内部元素的组信息
     *
     * @param chart
     * @param markSet
     */
    public static void getChartChunksGroupInfos(
            Chart chart, Set<Integer> markSet) {
        getChunksGroupInfos(chart.legendsChunks, markSet);
        getChunksGroupInfos(chart.vAxisChunksL, markSet);
        getChunksGroupInfos(chart.vAxisChunksR, markSet);
        getChunksGroupInfos(chart.hAxisChunksD, markSet);
        getChunksGroupInfos(chart.hAxisChunksU, markSet);
        getChunkGroupInfos(chart.dataSourceChunk, markSet);
        getChunksGroupInfos(chart.pieInfoLegendsChunks, markSet);
    }

    /**
     * 获得给定Chunk集的内部元素的标记组号
     *
     * @param chunks
     * @param markSet
     */
    public static void getChunksGroupInfos(List<TextChunk> chunks, Set<Integer> markSet) {
        if (chunks == null) {
            return;
        }
        for (TextChunk chunk : chunks) {
            getChunkGroupInfos(chunk, markSet);
        }
    }

    /**
     * 获得给定Chunk的内部元素的标记组号
     *
     * @param chunk
     * @param markSet
     */
    public static void getChunkGroupInfos(TextChunk chunk, Set<Integer> markSet) {
        if (chunk == null) {
            return;
        }
        for (TextElement text : chunk.getElements()) {
            markSet.add(text.getGroupIndex());
        }
    }

    /**
     * 判断给定Chunk的全部组号在给定的组号集中所占比例是否大于给定值
     *
     * @param chunk
     * @param markSet
     * @param scaleCoef
     * @return
     */
    public static boolean isChunkInGroupSet(TextChunk chunk, Set<Integer> markSet, double scaleCoef) {
        // 判断数据有效性
        if (markSet == null || markSet.isEmpty() || chunk == null || chunk.getElements().isEmpty()) {
            return false;
        }
        // 计算当前Chunk的组信息  计算其在给定组号集中所占比例
        Set<Integer> mark = new HashSet<>();
        getChunkGroupInfos(chunk, mark);
        int n = mark.size();
        mark.retainAll(markSet);
        double f = 1.0 * mark.size() / n;
        if (f >= scaleCoef - 0.01) {
            return true;
        }
        return false;
    }

    /**
     * 找出Chart内部适当的文字Group 用来标记Chart图片上文字信息 故上下两行的信息需要分割开
     *
     * @param chart
     */
    public static void getProperTextGroup(Chart chart) {
        // 判断数据有效性
        if (chart == null || chart.chunksRemain.isEmpty()) {
            return;
        }
        // 从新排序
        Collections.sort(chart.chunksRemain,
                (TextChunk a, TextChunk b) ->
                        ((Integer) a.getFirstElement().getGroupIndex()).compareTo(
                                b.getFirstElement().getGroupIndex()));
        // 遍历 TextChunk
        chart.chunksPicMark.clear();
        for (TextChunk chunk : chart.chunksRemain) {
            List<TextChunk> newGroups = ChunkUtil.splitByRank(chunk, 0.6f);
            if (newGroups.isEmpty()) {
                //chart.chunksPicMark.add(chunk.copy());
                chart.chunksPicMark.add(new TextChunk(chunk));
            } else {
                chart.chunksPicMark.addAll(newGroups);
            }
        } // end for chunk
    }

    /**
     * 找出在Chart内部且不是刻度和图例的信息 并过滤掉纯数值的TextChunk信息
     *
     * @param charts
     * @param chunks
     */
    public static void getChartInnerTexts(
            List<Chart> charts,
            List<TextChunk> chunks) {
        // 如果没有Chart 则直接返回
        if (charts.isEmpty()) {
            return;
        }

        // 统计 charts内部所有刻度 图例 来源信息的内容的组信息
        Set<Integer> markSet = new HashSet<>();
        for (Chart chart : charts) {
            getProperTextGroup(chart);
            getChartChunksGroupInfos(chart, markSet);
            final Iterator<TextChunk> each = chart.chunksRemain.iterator();
            while (each.hasNext()) {
                TextChunk chunk = each.next();
                if (isChunkInGroupSet(chunk, markSet, 0.5)) {
                    each.remove();
                }
            }
            ChartContentScaleParser.removeNonNumberChunks(chart.chunksRemain);
            Collections.sort(chart.chunksRemain,
                    (TextChunk a, TextChunk b) ->
                            ((Integer) a.getFirstElement().getGroupIndex())
                                    .compareTo(b.getFirstElement().getGroupIndex()));
        }

        Pattern regExNumberPattern = RegularMatch.getInstance().regExNumberPattern;
        Pattern regExChinesePattern = RegularMatch.getInstance().regExChinesePattern;
        List<TextChunk> nonNumChunks = new ArrayList<>();
        for (TextChunk chunk : chunks) {
            // 忽略掉已经标记过的对象
            if (isChunkInGroupSet(chunk, markSet, 0.5)) {
                continue;
            }

            // 过滤掉纯数值类型的 TextChunk
            String str = chunk.getText();
            Matcher m = regExNumberPattern.matcher(str);
            String strNum = m.replaceAll("").trim();
            int n = strNum.length();
            if (n == 0) {
                continue;
            } else if (n == 1) {
                if (!regExChinesePattern.matcher(strNum).matches()) {
                    continue;
                }
            }
            nonNumChunks.add(chunk);
        } // end for chunk

        // 找出位于Chart内部的对象
        for (Chart chart : charts) {
            if (chart.type == ChartType.BITMAP_CHART) {
                continue;
            }
            Rectangle box = chart.getArea();
            box.expand(0, chart.getTitleSearchHeight(), 0, 0);
            int textLength = -1;
            TextChunk maxLengthChunk = null;
            for (TextChunk chunk : nonNumChunks) {
                if (box.intersects(chunk.getBounds2D())) {
                    String text = chunk.getText().trim();
                    chart.texts.add(text);
                    chart.textsChunks.add(chunk);
                    if (chart.title == null && textLength < text.length()) {
                        if (!isStringMatchTime(text)) {
                            textLength = text.length();
                            maxLengthChunk = chunk;
                        }
                    }
                }
            } // end for chunk
            if (chart.title == null && maxLengthChunk != null) {
                chart.title = maxLengthChunk.getText().trim();
                chart.titleTextChunk = maxLengthChunk;
            }
        } // end for chart
    }

    /**
     * 计算给定Chunk集的包围框的并集
     *
     * @param chunks
     * @return
     */
    public static Rectangle2D getChunksUnionBox(List<TextChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return null;
        }
        Rectangle2D box = (Rectangle2D) chunks.get(0).getBounds().clone();
        for (int i = 1; i < chunks.size(); i++) {
            Rectangle2D.union(box, chunks.get(i).getBounds(), box);
        }
        return box;
    }

    /**
     * 计算Chart的水平刻度包围框并集
     *
     * @param chart
     * @return
     */
    public static Rectangle2D getChartXAxisBox(Chart chart) {
        List<TextChunk> chunks = chart.hAxisChunksU.isEmpty() ? chart.hAxisChunksD : chart.hAxisChunksU;
        if (chunks.isEmpty()) {
            return null;
        }
        return getChunksUnionBox(chunks);
    }

    /**
     * 优化Page内的Chart集的标题
     *
     * @param charts
     */
    public static void optimizeTitleParser(List<Chart> charts) {
        for (Chart chart : charts) {
            optimizeChartTitleBaseInnerTexts(chart);
        }
    }

    /**
     * 在Chart内部texts中找出离图形元素并集上下边界最近的 TextChunk 作为标题
     *
     * @param chart
     */
    public static void optimizeChartTitleBaseInnerTexts(Chart chart) {
        // 位图跳过
        if (chart.type == ChartType.BITMAP_CHART) {
            return;
        }
        // 如果存在以 图或figure 开头的高可信度的标题 则跳过
        if (chart.title != null && bHighProbabilityChartTitle(chart.title)) {
            return;
        }
        // 没有内部没有参考信息 则跳过
        if (chart.texts.isEmpty()) {
            return;
        }

        // 找出内部图形元素的最大合并包围框
        Rectangle2D box = ChartPathInfosParser.getChartGraphicsMergeBox(chart);
        if (box == null) {
            return;
        }

        // 计算chart的水平刻度的包围框
        Rectangle2D xaxisBox = getChartXAxisBox(chart);
        double ymin = box.getMinY();
        double ymax = box.getMaxY();
        double distMin = -1.0;
        int ithNearestText = -1;
        for (int i = 0; i < chart.texts.size(); i++) {
            String text = chart.texts.get(i);
            TextChunk chunk = chart.textsChunks.get(i);
            if (ChunkUtil.getChunkType(chunk) == ChunkStatusType.DATA_SOURCE_TYPE ||
                    ChunkUtil.getChunkType(chunk) == ChunkStatusType.SUBTITLE_TYPE) {
                continue;
            }
            // 如果文字不是水平方向　则不是有效标题对象
            TextDirection direction = chunk.getDirection();
            if (direction != TextDirection.LTR && direction != TextDirection.RTL) {
                continue;
            }
            if (text.length() <= 3) {
                continue;
            }
            // 判断是否和水平刻度处于同一高度 如果是则跳过
            if (xaxisBox != null) {
                if (xaxisBox.getMinY() <= chunk.getCenterY() &&
                        chunk.getCenterY() <= xaxisBox.getMaxY()) {
                    continue;
                }
            }
            // 过滤掉时间类型的
            if (isStringMatchTime(text)) {
                continue;
            }
            // 过滤掉高可能性刻度单位说明性文字的
            if (bHighProbabilityAxisUnitInfo(text, 3)) {
                continue;
            }
            // 如果完全在 box 内部 则判断上下是否有可以扩展的 chunk  如果有则很有可能是内部说明性文字 跳过
            if (box.contains(chunk.getBounds())) {
                if (expandChunkVertical(chunk, chart.textsChunks, true,
                        ChunkStatusType.IGNORE_TYPE, false) ||
                        expandChunkVertical(chunk, chart.textsChunks, false,
                                ChunkStatusType.IGNORE_TYPE, false)) {
                    continue;
                }
            }
            double y = chunk.getCenterY();
            double dist = Math.min(Math.abs(y - ymin), Math.abs(y - ymax));

            if (ithNearestText == -1 || distMin > dist) {
                // 如果上侧title和下侧title比较 且上侧字号较大 则不替换
                if (ithNearestText != -1) {
                    TextChunk lastChunk = chart.textsChunks.get(ithNearestText);
                    if (lastChunk.getCenterY() < chart.getArea().getCenterY() && y > chart.getArea().getCenterY()
                            && lastChunk.getFontSize() > chunk.getFontSize()) {
                        continue;
                    }
                    if (Math.abs(distMin - dist) < 1) {
                        // 高度接近的情况下 比较左右位置
                        if (Math.abs(lastChunk.getCenterX() - chart.getArea().getCenterX()) < Math.abs(chunk.getCenterX() - chart.getArea().getCenterX())) {
                            continue;
                        }
                    }
                }
                ithNearestText = i;
                distMin = dist;
            }
        } // end for i
        // 判断位置有效性
        if (ithNearestText == -1 || ithNearestText >= chart.texts.size()) {
            return;
        }
        String textNearest = chart.texts.get(ithNearestText).trim();
        if (chart.title == null || !chart.title.contains(textNearest)) {

            // 加入字体大小比较
            if (!chart.titleTextChunk.isEmpty() && chart.titleTextChunk.getFontSize() > chart.textsChunks.get(ithNearestText).getFontSize()) {
                // 不替换
                List<String> st = chart.titleTextChunk.getStructureTypes();
                if (st != null) {
                    // 加入是否是副标题的比较 这里的规则很严
                    boolean issub = st.stream().anyMatch(s -> s.contains("sub"));
                    
                    if (issub) {
                        return;
                    }
                }
            }

            chart.title = chart.texts.get(ithNearestText).trim();
            chart.titleTextChunk = chart.textsChunks.get(ithNearestText);

        }
    }

    /**
     * 找出给定Chart集左下方对应的数据来源信息
     *
     * @param charts
     * @param chunks
     */
    public static void getChartDataSourceInfo(
            List<Chart> charts,
            List<TextChunk> chunks) {
        if (charts.isEmpty()) {
            return;
        }

        // 找出候选的含有数据来源或来源的TextChunks
        List<TextChunk> sourceChunks = getDataSourceTextChunks(chunks);
        if (sourceChunks.isEmpty()) {
            return;
        }

        // 匹配候选TextChunk和Chart
        matchChartDataSourceInfo(charts, sourceChunks);
    }

    /**
     * 判断给定TextChunk是否符合数据来源信息的特征
     * 方便查找Chart数据来源信息和排除掉一部分非标题信息
     *
     * @param chunk
     * @return
     */
    public static boolean isDataSourceTextChunk(TextChunk chunk) {
        return isDataSourceTextChunk(chunk.getText());
    }

    public static boolean isDataSourceTextChunk(String text) {
        List<String> sourceFormat = RegularMatch.getInstance().sourceFormat;
        return sourceFormat.stream().anyMatch(s -> text.startsWith(s));
    }

    public static boolean isNoteTextChunk(TextChunk chunk) {
        List<String> noteWords = RegularMatch.getInstance().noteFormat;
        String text = chunk.getText();
        return noteWords.stream().anyMatch(s -> text.startsWith(s));
    }

    /**
     * 找出候选的含有数据来源或来源的TextChunks
     *
     * @param chunks
     * @return
     */
    public static List<TextChunk> getDataSourceTextChunks(
            List<TextChunk> chunks) {
        // 遍历所有的chunk 找出候选对象 即　开头为　数据来源或来源
        List<TextChunk> sourceChunks = new ArrayList<>();
        for (TextChunk textChunk : chunks) {
            // 过滤掉标记为图列　刻度信息的 Chunks
            ChunkStatusType type = ChunkUtil.getChunkType(textChunk);
            if (type != ChunkStatusType.UNKNOWN_TYPE) {
                if (type == ChunkStatusType.LEGEND_TYPE ||
                        type == ChunkStatusType.SCALE_TYPE) {
                    continue;
                }
            }

            // 判断是否有数据来源信息的特征
            if (isDataSourceTextChunk(textChunk)) {
                sourceChunks.add(textChunk);
            }
        } // end for chunk
        return sourceChunks;
    }

    /**
     * 在给定的候选数据来源信息Chunks中匹配相应的Chart
     * 采取距离就近原则和大致同行位置Chart.datasource相同原则
     *
     * @param charts
     * @param chunks
     */
    public static void matchChartDataSourceInfo(
            List<Chart> charts,
            List<TextChunk> chunks) {
        // 遍历Chart集
        List<Integer> markedchart = new ArrayList<>();
        for (int i = 0; i < charts.size(); i++) {
            Chart chart = charts.get(i);
            // 判断Chart的有效性
            if (!chart.isChart()) {
                continue;
            }
            // 左下角点坐标
            double xmin = chart.getArea().getMinX();
            double ymin = chart.getArea().getMinY();
            double w = chart.getArea().getWidth();
            double h = chart.getArea().getHeight();
            double distmin = -1.0;
            int ithChunkMin = -1;
            int ithChunk = -1;

            // 遍历所有的chunk，找出距离左下角点最近的有效对象
            for (TextChunk textChunk : chunks) {
                double xminc = textChunk.getBounds().getMinX();
                double ymaxc = textChunk.getBounds().getMaxY();
                double dx = Math.abs(xmin - xminc);
                double dy = Math.abs(ymin - ymaxc);
                ithChunk++;
                // 经验性范围　防止正文中出现类型数据来源或来源开头的语句的干扰
                if (xminc < xmin - 0.5 * w || xminc > xmin + 0.8 * w ||
                        ymaxc < ymin - 0.5 * w || ymaxc > ymin + 0.3 * h) {
                    continue;
                }
                // 选出距离最近的一个
                double dist = dx * dx + dy * dy;
                if (distmin < -0.5 || distmin > dist) {
                    distmin = dist;
                    ithChunkMin = ithChunk;
                }
            } // end for chunk
            if (ithChunkMin != -1) {
                chart.dataSourceChunk = chunks.get(ithChunkMin);
                chart.dataSource = chart.dataSourceChunk.getText();
                markedchart.add(i);
            }
        } // end for chart

        // 再次遍历　给没有找到数据来源信息的Chart尽可能匹配相关信息
        // 通过匹配与其大致平行的有数据来源信息的Chart的数据来源信息
        for (Chart chart : charts) {
            if (!chart.isChart() || chart.dataSource != null) {
                continue;
            }
            double ymid = chart.getArea().getCenterY();
            for (Integer ichart : markedchart) {
                Chart mchart = charts.get(ichart);
                if (mchart.getArea().getMinY() < ymid && ymid < mchart.getArea().getMaxY()) {
                    chart.dataSource = mchart.dataSource;
                    chart.dataSourceChunk = mchart.dataSourceChunk;
                    break;
                }
            } // end for ichart
        } // end for chart
    }

    /**
     * 计算给定的text是否含有匹配指定侧刻度的关键词信息
     * @param text
     * @param isLeft
     * @return
     */
    public static boolean matchLeftRightAxisKey(String text, boolean isLeft) {
        text = text.replaceAll("\\s", "");
        if (StringUtils.isEmpty(text)) {
            return false;
        }

        Pattern regExSubtitlePattern = RegularMatch.getInstance().regLeftAxisPattern;
        if (!isLeft) {
            regExSubtitlePattern = RegularMatch.getInstance().regRightAxisPattern;
        }
        Matcher matcher = regExSubtitlePattern.matcher(text);
        if (matcher.find()) {
            return true;
        }
        else {
            return false;
        }
    }
}

/**
 * 支持序关系的浮点数和其他信息的结构
 */
class FloatIntPair implements Comparable<FloatIntPair> {
    float key;
    float x;
    int i;

    FloatIntPair(float key, float x, int i) {
        this.key = key;
        this.x = x;
        this.i = i;
    }

    // 实现比较的序关系
    @Override
    public int compareTo(FloatIntPair o2) {
        return Float.compare(this.key, o2.key);
    }
}

/**
 * chunk关于某个坐标值的简单数据结构　用于在某个刻度上排序用
 */
class ChunkInfo implements Comparable<ChunkInfo> {
    float xy;     // 坐标值
    int ith;      // chunk对应的标号
    boolean reverse;
    boolean valid;

    ChunkInfo(double pxy, int pith) {
        this(pxy, pith, false);
    }

    ChunkInfo(double pxy, int pith, boolean reverse) {
        xy = (float) pxy;
        ith = pith;
        this.reverse = reverse;
        valid = true;
    }

    // 实现比较的序关系
    @Override
    public int compareTo(ChunkInfo o2) {
        return reverse ? Float.compare(o2.xy, this.xy) : Float.compare(this.xy, o2.xy);
    }
}

/**
 * Page包含的TextChunk集  初步用来给没有title的chart在上一个Page找候选title用的
 */
class TextChunkInfo {
    public List<TextChunk> chunks;  // TextChunk集
    public int ithpage;             // Page编号

    TextChunkInfo() {
        this.chunks = new ArrayList<>();
        this.ithpage = -1;
    }

    TextChunkInfo(List<TextChunk> chunks, int ithpage) {
        this.chunks = chunks;
        this.ithpage = ithpage;
    }
}

/**
 * 在解析图列　标题　刻度信息时　TextChunk 的状态标识
 */
enum ChunkStatusType {
    UNKNOWN_TYPE(-1),
    TITLE_TYPE(0),
    SUBTITLE_TYPE(1),
    LEGEND_TYPE(2),
    SCALE_TYPE(3),
    IGNORE_TYPE(4),
    VERTICAL_TYPE(5),
    DATA_SOURCE_TYPE(6),
    PATH_VALUE_TYPE(20),
    SPLIT_TYPE(30),
    MERGE_TYPE(40),
    SCALE_UNIT_TYPE(50);           // 刻度单位

    private final int value;       //整数值表示

    ChunkStatusType(int value) {
        this.value = value;
    }

    public int getValue() {
        return this.value;
    }
}

class ChunkUtil {
    private static String CHUNK_TYPE = "ChunkStatusType";
    private static final float TEXT_SPACE = 1.2f;

    public static void setChunkType(TextChunk chunk, ChunkStatusType type) {
        if (chunk != null) {
            chunk.addTag(CHUNK_TYPE, type);
        }
    }

    public static ChunkStatusType getChunkType(TextChunk chunk) {
        if (chunk != null && chunk.hasTag(CHUNK_TYPE)) {
            return (ChunkStatusType) chunk.getTag(CHUNK_TYPE);
        }
        return ChunkStatusType.UNKNOWN_TYPE;
    }

    public static boolean isVertical(TextChunk chunk) {
        if (chunk == null) {
            return false;
        }
        TextDirection direction = chunk.getDirection();
        return direction == TextDirection.VERTICAL_UP || direction == TextDirection.VERTICAL_DOWN;
    }

    public static double getMaxFontHeight(TextChunk chunk) {
        double maxFontHeight = 0;
        for (TextElement textElement : chunk.getElements()) {
            if (textElement.getHeight() > maxFontHeight) {
                maxFontHeight = textElement.getHeight();
            }
        }
        return maxFontHeight;
    }

    public static double getMaxFontWidth(TextChunk chunk) {
        double maxFontWidth = 0;
        for (TextElement textElement : chunk.getElements()) {
            if (textElement.getWidth() > maxFontWidth) {
                maxFontWidth = textElement.getWidth();
            }
        }
        return maxFontWidth;
    }

    public static void reverserRTLChunks(List<TextChunk> chunks) {
        for (int i = 0; i < chunks.size(); i++) {
            TextChunk chunk = chunks.get(i);
            TextDirection dir = chunk.getDirection();
            if (dir == TextDirection.RTL) {
                TextChunk chunkNew = reverseDirection(chunk);
                chunks.set(i, chunkNew);
            }
        }
    }

    public static TextChunk reverseDirection(TextChunk chunk) {
        TextDirection dir = chunk.getDirection();
        if (dir != TextDirection.LTR && dir != TextDirection.RTL) {
            return chunk;
        }
        List<TextElement> eles = chunk.getElements();
        List<TextElement> elesNew = new ArrayList<>();
        for (int i = eles.size() - 1; i >= 0; i--) {
            TextElement ele = (TextElement) eles.get(i).clone();
            if (dir == TextDirection.LTR) {
                ele.setDirection(TextDirection.RTL);
            }
            else {
                ele.setDirection(TextDirection.LTR);
            }
            elesNew.add(ele);
        } // end for i
        TextChunk chunkNew = new TextChunk(elesNew);
        return chunkNew;
    }

    public static List<TextChunk> splitByRank(TextChunk chunk, float coef) {
        List<TextElement> elements = chunk.getElements();
        TextDirection direction = chunk.getDirection();
        int n = elements.size();
        double width = 0.0;
        double height = 0.0;
        TextElement eleB = null;
        List<Integer> groupPos = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            TextElement ele = elements.get(i);
            if (direction == TextDirection.LTR) {
                height = height + ele.getHeight();
            } else {
                width = width + ele.getWidth();
            }
            if (i >= 1 && eleB.getGroupIndex() != ele.getGroupIndex()) {
                if (direction == TextDirection.LTR &&
                        Math.abs(eleB.getBounds().getCenterY() - ele.getBounds().getCenterY()) >= coef * height / (i + 1)) {
                    groupPos.add(i);
                } else if (direction != TextDirection.LTR &&
                        Math.abs(eleB.getBounds().getCenterX() - ele.getBounds().getCenterX()) >= coef * width / (i + 1)) {
                    groupPos.add(i);
                }
            }
            eleB = ele;
        }
        if (!groupPos.isEmpty()) {
            groupPos.add(elements.size());
        }
        return splitGroupPosition(chunk, groupPos);
    }

    public static List<TextChunk> splitGroupPosition(TextChunk chunk, List<Integer> groupPos) {
        List<TextElement> elements = chunk.getElements();
        TextDirection direction = chunk.getDirection();
        ChunkStatusType type = ChunkUtil.getChunkType(chunk);
        List<TextChunk> groups = new ArrayList<>();
        // 根据分割位置　将chunk细分
        int ithstart = 0, ithend = 0;
        for (int i = 0; i < groupPos.size(); i++) {
            ithend = groupPos.get(i);
            List<TextElement> group = elements.subList(ithstart, ithend);
            ithstart = ithend;
            TextChunk chunkNew = new TextChunk(group);
            chunkNew.setDirection(direction);
            ChunkUtil.setChunkType(chunkNew, type);
            groups.add(chunkNew);
        }
        return groups;
    }

    /**
     * 根据给定间隔相对字符宽度大小，分拆成多个组
     *
     * @param coef 指定的 间隔相对字符宽度大小
     * @return 细分后的TextChunk集
     */
    public static List<TextChunk> splitByInterval(TextChunk chunk, float coef) {
        List<TextElement> elements = chunk.getElements();
        List<Integer> groupPos = new ArrayList<>();
        double dist = 0.0f, w = 0.0f;
        boolean isVertical = ChunkUtil.isVertical(chunk);
        // 找出间隔过大的位置
        for (int i = 1; i < elements.size(); i++) {
            if (isVertical) {
                dist = elements.get(i).getY() - elements.get(i - 1).getY();
                w = elements.get(i - 1).getHeight();
            } else {
                dist = elements.get(i).getX() - elements.get(i - 1).getX();
                w = elements.get(i - 1).getWidth();
            }

            // 存在X轴刻度信息 共用一个组号的情况 所有注释掉不同组号的对象才能分割的条件限制
            // 相邻两TextElement位于不同组 且 间距大于给定阀值  则需要分割开
            if (dist / w > coef) {
                groupPos.add(i);
            }
        }  // end for i
        if (!groupPos.isEmpty()) {
            groupPos.add(elements.size());
        }
        return splitGroupPosition(chunk, groupPos);
    }

    /**
     * 根据给定空格数目，分拆成多个组
     *
     * @param nSpaces 指定的空格数目
     * @return 细分后的TextChunk集
     */
    public static List<TextChunk> splitBySpaces(TextChunk chunk, int nSpaces) {
        List<TextElement> elements = chunk.getElements();
        TextDirection direction = chunk.getDirection();
        ChunkStatusType type = ChunkUtil.getChunkType(chunk);
        List<TextChunk> textChunks = new ArrayList<>();
        Pattern spacePatternLocal = RegularMatch.getInstance().getSpacePattern(nSpaces);
        if (spacePatternLocal == null) {
            return textChunks;
        }
        String text = chunk.getText();
        Matcher matcher = spacePatternLocal.matcher(text);
        int preSpaceEnd = 0;
        while (matcher.find()) {
            int spaceStart = matcher.start();
            if (spaceStart > preSpaceEnd) {
                int iBeforeStart = spaceStart - 1;
                if (iBeforeStart >= elements.size() || spaceStart >= elements.size()) {
                    textChunks.clear();
                    preSpaceEnd = 0;
                    break;
                }

                // 存在X轴刻度信息 共用一个组号的情况 所有注释掉不同组号的对象才能分割的条件限制
                TextChunk chunkNew = new TextChunk(elements.subList(preSpaceEnd, spaceStart));
                chunkNew.setDirection(direction);
                ChunkUtil.setChunkType(chunkNew, type);
                textChunks.add(chunkNew);
            }
            preSpaceEnd = matcher.end();
        }
        if (preSpaceEnd > 0 && preSpaceEnd < elements.size()) {
            TextChunk chunkNew = new TextChunk(elements.subList(preSpaceEnd, elements.size()));
            ChunkUtil.setChunkType(chunkNew, type);
            textChunks.add(chunkNew);
        }
        return textChunks;
    }

    public static Rectangle2D getChunkCheckBounds(TextChunk chunk) {
        double maxFontHeight = getMaxFontHeight(chunk);
        double maxFontWidth = getMaxFontWidth(chunk);
        Rectangle2D bounds = chunk.getBounds2D();
        Rectangle2D checkBounds = (Rectangle2D) bounds.clone();
        TextDirection direction = chunk.getDirection();
        if (direction == TextDirection.UNKNOWN) {
            // 往右, 上, 下查找
            // 已经有一个字了, 而且文字方向是水平, 则有可能是HORIZONTAL, LAYOUT_VERTICAL, 往右, 下查找
            checkBounds = new Rectangle2D.Float(
                    (float) bounds.getMinX(),
                    (float) (bounds.getMinY() - maxFontHeight * TEXT_SPACE),
                    (float) (bounds.getWidth() + maxFontWidth * TEXT_SPACE),
                    (float) (bounds.getHeight() + maxFontHeight * TEXT_SPACE));
        } else if (direction == TextDirection.VERTICAL_UP) {
            // 往上查找
            checkBounds = new Rectangle2D.Float(
                    (float) bounds.getMinX(),
                    (float) bounds.getMinY(),
                    (float) bounds.getWidth(),
                    (float) (bounds.getHeight() + maxFontHeight * TEXT_SPACE));
        } else if (direction == TextDirection.VERTICAL_DOWN) {
            // 往下查找
            checkBounds = new Rectangle2D.Float(
                    (float) bounds.getMinX(),
                    (float) (bounds.getMinY() - maxFontHeight * TEXT_SPACE),
                    (float) bounds.getWidth(),
                    (float) (bounds.getHeight() + maxFontHeight * TEXT_SPACE));
        } else {
            // 往右查找
            checkBounds = new Rectangle2D.Float(
                    (float) bounds.getMinX(),
                    (float) bounds.getMinY(),
                    (float) (bounds.getWidth() + maxFontWidth * TEXT_SPACE),
                    (float) bounds.getHeight());
        }
        return checkBounds;
    }

    public static boolean isRotate(List<TextElement> elements) {
        int n = elements.size();
        if (n <= 1) {
            return false;
        }
        // 判断是否水平或是垂直
        double dyFirst = elements.get(1).getBounds().getMinY() - elements.get(0).getBounds().getMinY();
        double dxFirst = elements.get(1).getBounds().getCenterX() - elements.get(0).getBounds().getCenterX();
        if (Math.abs(dxFirst) < ChartUtils.DELTA || Math.abs(dyFirst) < ChartUtils.DELTA) {
            return false;
        }
        // 判断空间是否连续
        for (int i = 2; i < n; i++) {
            double dy = elements.get(i).getBounds().getMinY() - elements.get(i - 1).getBounds().getMinY();
            if (dy * dyFirst <= ChartUtils.DELTA) {
                return false;
            }
        }
        // 判断是否有有效的高度差
        double dh = 0.5 * (elements.get(0).getHeight() + elements.get(1).getHeight());
        if (Math.abs(dyFirst) <= 0.2 * dh) {
            return false;
        } else {
            return true;
        }
    }

    /**
     * 基于当前对象内容和给定Element内容判断是否可以合并 (后续添加其他内容模式过滤)
     *
     * @param element
     * @return
     */
    public static boolean isValidMergeBaseContent(TextChunk chunk, TextElement element) {
        String text = chunk.getText();
        if (text.startsWith("图") && element.getText().contains("图")) {
            return false;
        } else {
            return true;
        }
    }

    public static boolean canAdd(TextChunk chunk, TextElement element) {
        List<TextElement> elements = chunk.getElements();
        if (elements.isEmpty()) {
            return true;
        }
        if (StringUtils.isEmpty(chunk.getText())) {
            return false;
        }
        for (TextElement e : elements) {
            if (e.getGroupIndex() == element.getGroupIndex()) {
                return true;
            }
        }
        if (elements.get(elements.size() - 1).getGroupIndex() + 1 != element.getGroupIndex()) {
            return false;
        }

        // 判断输入单个TextElement是否在当前TextChunk的包围框内部  如果不在　则返回 false
        Rectangle2D checkBounds = getChunkCheckBounds(chunk);
        if (!checkBounds.contains(element.getCenterX(), element.getCenterY())) {
            return false;
        }

        // 基于内容信息 判断是否为有效合并
        return isValidMergeBaseContent(chunk, element);
    }

    public static boolean canAdd(TextChunk chunkA, TextChunk chunk) {
        List<TextElement> elements = chunkA.getElements();
        if (elements.isEmpty()) {
            return true;
        }
        String text = chunkA.getText();
        if (StringUtils.isEmpty(text)) {
            return false;
        }
        int neles = chunk.getElements().size();
        if (neles == 0) {
            return false;
        }
        int ithGroupChunk = chunk.getFirstElement().getGroupIndex();
        int ithGroupCurrent = chunkA.getLastElement().getGroupIndex();
        if (ithGroupCurrent + 1 != ithGroupChunk) {
            return false;
        }

        // 取第一个元素
        TextElement firstEle = chunk.getElements().get(0);
        Rectangle2D checkBounds = getChunkCheckBounds(chunkA);
        boolean posValid = checkBounds.contains(firstEle.getCenterX(), firstEle.getCenterY());
        if (!posValid) {
            return false;
        }

        // 判断空间上是否连续 (可能在前面的合并过程中区域范围扩得很大,
        // 可能把不同组的对象的空间也包含进来, 故加上首尾对象空间连续性判断)
        int n = elements.size();
        TextElement endEle = elements.get(n - 1);
        double maxH = Math.max(endEle.getHeight(), firstEle.getHeight());
        double maxW = Math.max(endEle.getWidth(), firstEle.getWidth());
        double maxLen = maxH * maxH + maxW * maxW;
        double dx = endEle.getCenterX() - firstEle.getCenterX();
        double dy = endEle.getCenterY() - firstEle.getCenterY();
        if (dx * dx + dy * dy > 2.0 * maxLen) {
            return false;
        }

        // 如果是水平扩展 则一般从最右端开始
        TextDirection direction = chunkA.getDirection();
        if (direction == TextDirection.LTR ||
                (direction == TextDirection.UNKNOWN && chunk.getDirection() == TextDirection.LTR)) {
            // 判断内容对象集是否存在空间上的旋转 (即非水平或垂直)
            List<TextElement> eles = new ArrayList<>();
            eles.addAll(elements);
            eles.addAll(chunk.getElements());
            if (isRotate(eles)) {
                return true;
            }
            Rectangle2D bounds = chunkA.getBounds2D();
            double x = bounds.getMinX() + 0.7 * bounds.getWidth();
            if (chunk.getBounds2D().getMinX() <= x) {
                // 如果是未知方向　且不能水平扩展　则检测是否能垂直扩展
                if (direction == TextDirection.UNKNOWN) {
                    double y = bounds.getMinY() + 0.7 * bounds.getHeight();
                    if (chunk.getBounds2D().getMaxY() > y) {
                        return false;
                    }
                }
            }
        }

        TextElement lastEle = chunk.getElements().get(neles - 1);
        String strFirst = firstEle.getText();
        String strLast = lastEle.getText();

        // 获取正则表达式
        RegularMatch reg = RegularMatch.getInstance();
        Pattern regExNumberPattern = reg.regExNumberPattern;
        Pattern regExNumberNewGroupPattern = reg.regExNumberNewGroupPattern;
        Pattern regExPureNumberPattern = reg.regExPureNumberPattern;
        String chunkText = chunk.getText();

        // 如果当前内容都为数字类型
        Matcher m = regExNumberPattern.matcher(text);
        String strNum = m.replaceAll("").trim();
        boolean isTextNumber = (strNum.length() == 0);
        m = regExNumberPattern.matcher(chunkText);
        strNum = m.replaceAll("").trim();
        boolean isChunkTextNumber = (strNum.length() == 0);
        if (isTextNumber && isChunkTextNumber) {
            if (neles == 1) {
                // 如果包含 % 符号　则通常不能合并其他单个数值类符号 总结的经验　　有待验证
                if (text.contains("%")) {
                    return false;
                } else {
                    return true;
                }
            }
            if (text.trim().length() == chunkText.trim().length()) {
                if (!text.trim().equals("-")) {
                    return false;
                }
            }
            if (!strFirst.equals(" ") && text.contains(strFirst)) {
                if (regExNumberNewGroupPattern.matcher(strFirst).find()) {
                    return false;
                }
            }
            if (!strLast.equals(" ") && text.contains(strLast)) {
                if (!regExPureNumberPattern.matcher(strLast).find()) {
                    return false;
                }
            }
        }
        return true;
    }
}
