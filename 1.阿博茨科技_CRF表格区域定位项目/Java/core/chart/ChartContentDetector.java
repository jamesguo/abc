package com.abcft.pdfextract.core.chart;

import com.abcft.pdfextract.core.PdfExtractContext;
import com.abcft.pdfextract.core.model.ContentGroup;
import com.abcft.pdfextract.core.chart.model.PathInfo;
import com.abcft.pdfextract.core.chart.parser.BarParser;
import com.abcft.pdfextract.core.model.*;
import com.abcft.pdfextract.core.model.Rectangle;
import com.abcft.pdfextract.core.util.GraphicsUtil;
import com.abcft.pdfextract.spi.ChartType;
import com.abcft.pdfextract.spi.algorithm.ImageClassifyResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.color.PDIndexed;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Created by dhu on 2017/5/9.
 */

enum ContentGroupType {
    UNKNOWN_TYPE(-1),             //未知类型
    STANDARD_TYPE(0),             //普通类型
    CHART_BEGIN_TYPE(1),          //Chart开始类型
    CHART_TYPE(2),                //Chart类型
    CHART_END_TYPE(3),            //Chart类型
    FORM_BEGIN_TYPE(10),          //Form开始类型
    FORM_TYPE(11),                //Form类型
    NOT_IN_CHART_TYPE(20);        //不在Chart内部类型

    private final int value;       //整数值表示

    ContentGroupType(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }
}  // end enum

public class ChartContentDetector {

    private static Logger logger = LogManager.getLogger(ChartContentDetector.class);
    private static final boolean ALLOW_HIDDEN_CHART = false;
    private static String CONTNET_GROUP_TYPE = "ContentGroupType";
    private static final double LEGEND_MAX_HEIGHT = 10;
    private static final double LEGEND_MIN_WIDTH = 1;
    private static final double LEGEND_MAX_WIDTH = 40;

    /**
     * 给定 Document, Page 和 ContengGroup 对象  解析出内部包含的有效 Chart 集
     * @param context
     * @param pageIndex
     * @param detectedInfo
     * @param checkBitmap
     * @param onlyParseHintArea  是否只解析给定hint-area区域
     * @param useChartClassify   是否开启位图分类服务
     * @param pdfName   文件名  线下本地测试时方便查看用
     * @return
     * @throws TimeoutException
     */
    static List<Chart> detectChart(
            PdfExtractContext context,
            int pageIndex,
            List<DetectedObjectTF> detectedInfo,
            boolean checkBitmap,
            boolean onlyParseHintArea,
            boolean useChartClassify ,
            String pdfName) throws TimeoutException {
        ChartContentDetector ccDetector = new ChartContentDetector(
                context, pageIndex, useChartClassify, onlyParseHintArea);
        ccDetector.setCheckBitmap(checkBitmap);

        // 尝试新方法1 基于Page内容, 建立网格 再找出Chart区域   开发中 暂时注释掉不启用
        //ccDetector.detectChartArea();

        // 如果调用GRPC检测Chart服务或是用本地TF模型预先检测Page图片内的Chart对象区域   则调用新解析方法
        if (detectedInfo != null && !detectedInfo.isEmpty()) {
            // 基于深度学习模型检测出大致Chart区域  在检测区域内部用新的解析方法
            ccDetector.setPageDetectedInfo(detectedInfo);
            ccDetector.detectByTFChartTable();

            // 直接使用位图检测Chart区域 + 位图Chart分类 + path分类新方法  打磨优化中
            //List<Chart> chartsNew = ccDetector.detectBasePathClassify(detectedInfo);
            //return chartsNew;
        }

        // 如果没有声明只用hint-area区域信息解析　 则也用传统方法解析　Chart
        if (!onlyParseHintArea) {
            ccDetector.detectConventional();
        }

        // 融合两种方法解析结果
        List<Chart> chartsMerge = ccDetector.mergeParserCharts();

        // 如果没有打开位图检测功能 则对传统矢量方法的结果中confidence过小的Chart
        // 对这部分Chart.area进行新的解析方法
        if (detectedInfo == null || detectedInfo.isEmpty()) {
            chartsMerge = ccDetector.detectImprove(chartsMerge);
            //chartsMerge = ccDetector.detectImproveByPathClassify(chartsMerge);
        }

        // 过滤检测出的chart集中的table对象
        if  (!onlyParseHintArea) {
            ccDetector.filterTableCharts(chartsMerge);
        }

        // 如果没有声明只用hint-area区域信息解析
        if (!onlyParseHintArea && useChartClassify) {
            // 基于Page内Path对象包围框相交合并　得到候选Chart区域 调用位图分类服务 保留可能的有效图表区域
            List<PathBox> pathBoxes = ccDetector.detectChartAreaBaseMergePath(pdfName);

            // 再次对候选区域进行解析
            chartsMerge = ccDetector.parseChartBasePathBox(pathBoxes);
        }

        // 返回最终结果
        return chartsMerge;
    }

    /**
     * 过滤掉页眉页脚内的对象
     * @param pathBoxes
     */
    static void filterHeadersFootersPath(List<PathBox> pathBoxes, double width, double height, double coef) {
        final Iterator<PathBox> each = pathBoxes.iterator();
        while (each.hasNext()) {
            PathBox pathBox = each.next();
            double cy = pathBox.box.getCenterY();
            double cx = pathBox.box.getCenterX();
            // 判断顶部底部位置是否在page边框附近
            if (pathBox.box.getMaxY() < coef * height || pathBox.box.getMinY() > (1.0 - coef) * height) {
                each.remove();
            }
            else if (pathBox.box.getMaxX() < coef * width || pathBox.box.getMinX() > (1.0 - coef) * width) {
                each.remove();
            }
            // 判断中心点位置是否在page边框附近
            else if (cy < 0.8 * coef * height || cy > (1.0 - 0.8 * coef) * height) {
                each.remove();
            }
            else if (cx < 0.8 * coef * width || cx > (1.0 - 0.8 * coef) * width) {
                each.remove();
            }
        }
    }

    /**
     * 过滤掉左右边侧的对象
     * @param pathBoxes
     */
    static void filterLeftRightSidePath(List<PathBox> pathBoxes, double width, double coef) {
        final Iterator<PathBox> each = pathBoxes.iterator();
        while (each.hasNext()) {
            PathBox pathBox = each.next();
            if (pathBox.box.getMaxX() < coef * width || pathBox.box.getMinX() > (1.0 - coef) * width) {
                each.remove();
            }
        }
    }

    /**
     * 遍历page内所有内容 判断是否有一定数目有效Path对象 (测试中)
     * @return
     */
    static boolean hasValidPath(ContentGroup pageContentGroup, PDRectangle pageArea) {
        List<ContentGroup> allContentGroups = pageContentGroup.getAllContentGroups(true, true);
        double width = pageArea.getWidth();
        double height = pageArea.getHeight();
        List<PathBox> pathBoxes = new ArrayList<>();
        for (int id = 0; id < allContentGroups.size(); id++) {
            ContentGroup group = allContentGroups.get(id);
            if (!group.hasOnlyPathItems()) {
                continue;
            }
            ContentItem item = group.getAllItems().get(0);
            PathItem pathItem = (PathItem)item;
            if (isWhite(pathItem.getRGBColor())) {
                continue;
            }

            // 过滤掉长直线对象
            Rectangle2D itemArea = pathItem.getItem().getBounds2D();
            double h = itemArea.getHeight();
            double w = itemArea.getWidth();
            if (h < 0.005 * height && w > 0.86 * width) {
                continue;
            }
            if (w < 0.005 * width && h > 0.75 * height) {
                continue;
            }

            // 过滤掉过大对象
            if (h * w > 0.7 * height * width) {
                continue;
            }

            // 过滤掉过小对象
            if (h < 0.005 * height && w < 0.005 * width) {
                continue;
            }

            // 判断是不是背景填充区域对象
            if (pathItem.isFill()) {
                // 初步判断是否包含矩形对象 并 取出内部点 做进一步的判断
                List<Double> xs = new ArrayList<>();
                List<Double> ys = new ArrayList<>();
                if (ChartPathInfosParser.judgePathContainColumnAndGetPts(pathItem.getItem(), xs, ys)) {
                    if (xs.size() == 4) {
                        if (h * w > 0.1 * height * width) {
                            continue;
                        }
                    }
                }
            }

            PathBox pathBox = new PathBox(id, itemArea);
            pathBoxes.add(pathBox);
        } // end for id

        // 过滤掉位于页眉页脚的包围框
        filterHeadersFootersPath(pathBoxes, width, height, 0.15);

        // 过滤掉位于左右侧边栏的包围框
        filterLeftRightSidePath(pathBoxes, width, 0.15);

        // 判断有效图形对象个数 (测试阶段保守设置值很小)
        if (pathBoxes.size() <= 2) {
            return false;
        }
        else {
            return true;
        }
    }

    private ChartContentDetector(
            PdfExtractContext context,
            int pageIndex,
            boolean useChartClassify,
            boolean onlyParseHintArea) throws TimeoutException {
        this.context = context;
        this.pageIndex = pageIndex;
        this.useChartClassify = useChartClassify;
        this.onlyParseHintArea = onlyParseHintArea;
        this.pageContentGroup = context.getPageContentGroup(pageIndex);
    }

    private void filterTopPageChunk(List<TextChunk> chunks) {
        double height = pageContentGroup.getArea().getHeight();
        final Iterator<TextChunk> each = chunks.iterator();
        while (each.hasNext()) {
            TextChunk chunk = each.next();
            String text = chunk.getText();
            if (!StringUtils.isEmpty(text) && ChartTitleLegendScaleParser.bHighProbabilityChartTitle(text)) {
                continue;
            }
            if (chunk.getMaxY() <= 0.05 * height) {
                each.remove();
            }
        }
    }

    /**
     * 检测出page内部有效尺寸的位图对象
     * @param root
     * @return
     */
    private void detectBitmapChart(ContentGroup root) {
        if (!checkBitmap) {
            return;
        }
        // 如果只解析hint-area区域　则直接返回
        if (onlyParseHintArea) {
            return;
        }
        // 先取出 root 内部对象及其嵌套的子对象 顺序存储为 allContentGroups
        List<ContentGroup> allContentGroups = root.getAllContentGroups(true);
        List<TextChunk> chunks = pageContentGroup.getAllTextChunks();

        int n = allContentGroups.size();
        // 遍历各子 ContentGroup 对象
        for (int igroup = 0; igroup < n; igroup++) {
            current = allContentGroups.get(igroup);
            if (current.hasImage()) {
                detectBitmapImage(chunks);
            }
        }

        boolean noText = chunks.stream().allMatch(TextChunk::isBlank);
        // 如果这个页面没有文字, 而且只有一个很大的图片, 则认为是扫描件
        if (noText && charts.size() == 1 && charts.get(0).type == ChartType.BITMAP_CHART
                && charts.get(0).getHeight() / root.getArea().getHeight() > 0.6) {
            charts.clear();
        }
    }

    /**
     * 将3D效果的Chart对象 直接从页面截取出来, 保存为位图对象
     */
    private void deal3DCharts() {
        List<Chart> charts3D = new ArrayList<>();
        for (Chart chart : charts) {
            if (chart.type == ChartType.BITMAP_3D_CHART) {
                charts3D.add(chart);
            }
        } // end for i
        if (charts3D.isEmpty()) {
            return;
        }

        buildVirtualBitmapChart(charts3D);
    }


    /**
     * 创建Bitmap_Chart对象集
     * @param chartObjs
     */
    private void buildVirtualBitmapChart(List<Chart> chartObjs) {
        try {
            // 将Page渲染成 BufferedImage 对象
            BufferedImage imageFile = DetectEngine.getOneShutPageImage(context.getNativeDocument(), pageIndex, 72 * 2);
            ChartUtils.setChartsAreaCropImage(chartObjs, imageFile, 2.0);
            for (Chart chart : chartObjs) {
                chart.type = ChartType.BITMAP_CHART;
            }
        } catch (Exception e) {
            return;
        }
    }

    /**
     * 分类现有解析出来的候选图表区域的类型
     */
    private void classifyCharts() {
        // 如果不开启位图分类服务　则直接跳过
        if (!useChartClassify) {
            return;
        }

        // 找出需要做位图分类的Chart和区域
        List<Chart> needClassCharts = new ArrayList<>();
        List<Rectangle2D> areas = new ArrayList<>();
        for (int i = 0; i < charts.size(); i++) {
            Chart chart = charts.get(i);
            if (!chart.parserDetectArea && !chart.parserHintArea) {
                needClassCharts.add(chart);
                areas.add(chart.getArea());
            }
        } // end for i
        if (areas.isEmpty()) {
            return;
        }

        // 调用位图Chart分类服务 得到类型信息
        List<List<ImageClassifyResult>> res = ChartClassify.pageSubAreaClassify(context.getNativeDocument(), pageIndex, areas);
        if (res == null || res.isEmpty() || res.size() != areas.size() ||
                res.stream().anyMatch(re -> re == null)) {
            return;
        }

        // 判断分类的结果　如果是表格　正文　二维码　Logo other等类型　则设置为无效类型
        for (int i = 0; i < res.size(); i++) {
            List<ImageClassifyResult> result = res.get(i);
            Chart chart = needClassCharts.get(i);
            if (result != null && result.size() == 1) {
                String typeValue = ChartClassify.imageClassifyString(result.get(0));
                if (typeValue == "OTHER_MEANINGFUL" && chart.legends != null && !chart.legends.isEmpty()) {
                    continue;
                }
            }

            chart.imageClassifyTypes = result;
            if (!ChartClassify.isImageValid(result)) {
                chart.type = ChartType.UNKNOWN_CHART;
            }
        }

        // 过滤无效类型的
        removeTypeErrorCharts();
    }

    private void onPageEnd() {
        // 分类矢量方法找出的候选图表　得到类别先验信息　辅助解析
        // 考虑到性能问题　暂时关掉
        //classifyCharts();

        // 尝试用图例匹配图形对象
        //legendMatchPathInfos();

        // 检测当前Page内部有效尺寸的位图对象
        detectBitmapChart(pageContentGroup);

        // 处理3D效果图表
        deal3DCharts();

        // 过滤掉无效chart
        removeNoneCharts(false);

        // 基于有效Chart集　将Page内部TextElement　分组
        List<TextChunk> textChunks = pageContentGroup.getAllTextChunks();
//        textChunks.removeIf(textChunk -> StringUtils.isEmpty(textChunk.getText().trim()));
        List<TextChunk> merged = new ChartTextChunkMerger(2).merge(textChunks , ChartType.UNKNOWN_CHART);
        // 拆分包含连续空格的TextChunk
        merged = merged.stream()
                .flatMap(textChunk -> ChartTextMerger.squeeze(textChunk, ' ', 3).stream())
                .collect(Collectors.toList());
        ChunkUtil.reverserRTLChunks(merged);
        ChartTitleLegendScaleParser.splitTwoTitleChunk(merged);

        // 过滤掉页眉区域的TextChunk  通常不构成标题 图列 刻度等重要信息
        filterTopPageChunk(merged);

        // 找出　图例信息　副标题信息
        selectSubtitleLegend(merged, charts);

        // 匹配Legends和PathInfos
        ChartPathInfosParser.matchChartsPathInfosLegends(charts);

        // 如果到最后还没有解析出类型　则过滤掉
        removeTypeErrorCharts();

        filterBarXYAxis(charts);

        // 利用处理后的图形对象　尝试更好的分割刻度信息
        optiSplitChunks(charts);

        // 找出 title  刻度信息 注：斜着刻度没有完成
        selectTitleLegendScale(merged, charts);
        
        // 过滤掉无效 Chart
        filterInValidChart(charts, merged);

        // 如果到最后还没有解析出类型　则过滤掉
        removeTypeErrorCharts();

        // 根据每一个Chart的类型　解析出内部具体数据信息 开发中
        ChartContentScaleParser.parserChartContents(charts);

        // 再次判断类型
        judgeChartsType(charts);

        // 过滤掉无刻度有内部文字的无效chart
        removeNoneScaleInvalidChart();

        // 去掉被矢量Chart覆盖的位图对象
        removeBitmapCoverByChart();

        // 基于位图检测Chart区域信息　再次将没有解析出矢量内容的候选Chart区域　保存为位图对象
        cropPageBitmapChart(merged);

        removeChartBackgroundImage();
        removeNoneCharts(true);

        // 计算矢量Chart的confidence值
        compConfidence();

        // 对通过图表召回再次解析的低置信度的图表 直接尝试用截取区域方式保存为位图对象
        // 保证图表召回同时尽量保留好的解析结果
        reCropLowConfidenceRecallChart(merged);

        // 根据关键元素计算一个Area
        compValidArea();
    }

    /**
     * 从柱状对象中过滤掉高高概率为坐标轴的对象
     * @param charts
     */
    public void filterBarXYAxis(List<Chart> charts) {
        for (Chart chart : charts) {
            filterChartBarXYAxis(chart);
        }
    }

    /**
     * 从柱状对象中过滤掉高高概率为坐标轴的对象
     * @param chart
     */
    public void filterChartBarXYAxis(Chart chart) {
        ChartType type = chart.type;
        if (type == ChartType.BITMAP_CHART || type == ChartType.PIE_CHART
                || type == ChartType.COLUMN_CHART) {
            return;
        }

        List<ChartPathInfo> bars = new ArrayList<>();
        chart.pathInfos.stream().forEach(pathInfo -> {
            if (pathInfo.type == PathInfo.PathType.BAR ||
                    pathInfo.type == PathInfo.PathType.COLUMNAR) {
                bars.add(pathInfo); } });
        if (bars.isEmpty()) {
            return;
        }
        double height = chart.getArea().getHeight();
        double width = chart.getArea().getWidth();
        // 排序
        for (int i = 0; i < bars.size(); i++) {
            ChartPathInfo pathInfo = bars.get(i);
            boolean isVertical = pathInfo.type == PathInfo.PathType.BAR;
            GeneralPath sortedPath = ChartContentScaleParser.reSortColumn(pathInfo.path, isVertical);
            List<Double> cxys = new ArrayList<>();
            ChartPathInfosParser.getBarPathInfoCenterPts(sortedPath, cxys);
            double w = 0.0, h = 0.0;
            int n = cxys.size() / 4;
            int count = 0;
            for (int j = 0; j < n; j++) {
                w = cxys.get(4 * j + 2);
                h = cxys.get(4 * j + 3);
                if (w > 0.4 * width && h < 1.0) {
                    count++;
                }
                else if (h > 0.4 * height && w < 1.0) {
                    count++;
                }
            }
            if (count == n && n <= 3) {
                pathInfo.type = PathInfo.PathType.UNKNOWN;
            }
        } // end for i

        // 去除类型为无效类型的PathInfo
        final Iterator<ChartPathInfo> each = chart.pathInfos.iterator();
        while (each.hasNext()) {
            ChartPathInfo pathInfo = each.next();
            if (pathInfo.type == PathInfo.PathType.UNKNOWN) {
                each.remove();
            }
        } // end while
    }

    /**
     * 过滤掉无刻度信息且有内部文字 或　无任何图形对象的无效Chart对象
     */
    public void removeNoneScaleInvalidChart() {
        for (Chart chart : charts) {
            ChartType type = chart.type;
            if (type == ChartType.BITMAP_CHART ||
                    chart.isPPT ||
                    type == ChartType.PIE_CHART) {
                continue;
            }

            // 如果没有解析出图形对象
            if (chart.pathInfos.isEmpty()) {
                chart.type = ChartType.UNKNOWN_CHART;
                continue;
            }

            // 如果内部有文字对象　但是没有解析出任何一侧刻度信息
            int nAxisL = chart.vAxisTextL.size();
            int nAxisR = chart.vAxisTextR.size();
            int nAxisD = chart.hAxisTextD.size();
            int nAxisU = chart.hAxisTextU.size();
            boolean hasNoText = chart.texts.isEmpty();
            if (!hasNoText && (nAxisD == 0 && nAxisU == 0 &&
                    nAxisL == 0 && nAxisR == 0)) {
                chart.type = ChartType.UNKNOWN_CHART;
            }
        } // end for chart

        removeTypeErrorCharts();
    }

    /**
     * 根据关键元素计算所有chart大致区域
     */
    public void compValidArea() {
        for (Chart chart : charts) {
            Rectangle2D mergeBox = compValidArea(chart);
            if (mergeBox != null) {
                expandChartValidArea(chart, mergeBox, 0.1);
            }
        }
    }


    /**
     * 根据刻度、坐标轴、图例对象 得到一个大致区域并和现有区域求交集
     */
    public Rectangle2D compValidArea(Chart chart) {
        // 面积图没有计算bound 暂时没考虑 所以面积图path的bound应该是null
        if (chart.type.equals(ChartType.BITMAP_CHART) ||
                chart.parserDetectArea){
            return null;
        }

        Rectangle2D mergeBox = null;
        Iterator<ChartPathInfo> pathInfoIterator = chart.pathInfos.iterator();
        while (pathInfoIterator.hasNext()) {
            ChartPathInfo chartPathInfo = pathInfoIterator.next();

            if (chartPathInfo.bound == null) {
                continue;
            }

            if (mergeBox == null) {
                mergeBox = chartPathInfo.bound;
            } else {
                Rectangle2D.union(mergeBox, chartPathInfo.bound, mergeBox);
            }
        }

        if (mergeBox == null) {
            return mergeBox;
        }

        // 合并坐标轴
        if (chart.lvAxis != null) {
            Rectangle2D box = chart.lvAxis.getBounds2D();
            Rectangle2D.union(mergeBox, box, mergeBox);
        }
        if (chart.rvAxis != null) {
            Rectangle2D box = chart.rvAxis.getBounds2D();
            Rectangle2D.union(mergeBox, box, mergeBox);
        }
        if (chart.hAxis != null) {
            Rectangle2D box = chart.hAxis.getBounds2D();
            Rectangle2D.union(mergeBox, box, mergeBox);
        }

        // 合并内部TextChunk
        if (chart.vAxisChunksR != null && !chart.vAxisChunksR.isEmpty()) {
            Rectangle2D tBox = (Rectangle2D)mergeBox.clone();
            chart.vAxisChunksR.stream().forEach(
                    chunk -> Rectangle2D.union(tBox, chunk.getBounds2D(), tBox));
            mergeBox = (Rectangle2D)tBox.clone();
        }
        if (chart.vAxisChunksL != null && !chart.vAxisChunksL.isEmpty()) {
            Rectangle2D tBox = (Rectangle2D)mergeBox.clone();
            chart.vAxisChunksL.stream().forEach(
                    chunk -> Rectangle2D.union(tBox, chunk.getBounds2D(), tBox));
            mergeBox = (Rectangle2D)tBox.clone();
        }
        if (chart.hAxisChunksD != null && !chart.hAxisChunksD.isEmpty()) {
            Rectangle2D tBox = (Rectangle2D)mergeBox.clone();
            chart.hAxisChunksD.stream().forEach(
                    chunk -> Rectangle2D.union(tBox, chunk.getBounds2D(), tBox));
            mergeBox = (Rectangle2D)tBox.clone();
        }
        if (chart.hAxisChunksU != null && !chart.hAxisChunksU.isEmpty()) {
            Rectangle2D tBox = (Rectangle2D)mergeBox.clone();
            chart.hAxisChunksU.stream().forEach(
                    chunk -> Rectangle2D.union(tBox, chunk.getBounds2D(), tBox));
            mergeBox = (Rectangle2D)tBox.clone();
        }


        // 合并OCR区域
        if (!chart.ocrs.isEmpty()) {
            Rectangle2D oBox = (Rectangle2D)mergeBox.clone();
            chart.ocrs.stream().forEach(
                    ocr -> Rectangle2D.union(oBox, ocr.path.getBounds2D(), oBox));
            mergeBox = (Rectangle2D)oBox.clone();
        }

        if (!chart.legends.isEmpty()) {
            Rectangle2D oBox = (Rectangle2D)mergeBox.clone();
            Iterator<Chart.Legend> iter = chart.legends.iterator();
            while (iter.hasNext()) {
                Chart.Legend current = iter.next();
                Rectangle2D.union(oBox, current.line.getBounds2D(), oBox);
            }
            mergeBox = (Rectangle2D)oBox.clone();
        }

        if (!chart.legendsChunks.isEmpty()) {
            Rectangle2D oBox = (Rectangle2D)mergeBox.clone();
            Iterator<TextChunk> iter2 = chart.legendsChunks.iterator();
            while (iter2.hasNext()) {
                TextChunk current = iter2.next();
                Rectangle2D.union(oBox, current.getBounds2D(), oBox);
            }

            mergeBox = (Rectangle2D)oBox.clone();
        }


        // 对饼图处理 添加隐式图例
        if (chart.type == ChartType.PIE_CHART) {
            Rectangle2D tBox = (Rectangle2D)mergeBox.clone();
            chart.pieInfoLegendsChunks.stream().forEach(chunk -> Rectangle2D.union(tBox, chunk.getBounds2D(), tBox));
            mergeBox = (Rectangle2D)tBox.clone();
        }

        return mergeBox;
    }

    public void expandChartValidArea(Chart chart, Rectangle2D mergeBox, double zoomCoef) {
        // 扩大10%
        double height = mergeBox.getHeight();
        double width = mergeBox.getWidth();
        double x = mergeBox.getX();
        double y = mergeBox.getY();

        mergeBox.setRect(x - width * 0.5 * zoomCoef, y - height * 0.5 * zoomCoef,
                width * (1.0 + zoomCoef), height * (1.0 + zoomCoef));
        Rectangle2D.intersect(chart.getArea(), mergeBox, mergeBox);

        // 和原先的范围进行比较
        Rectangle2D originalArea = chart.getArea();
        if (Math.abs(originalArea.getWidth() - width) / originalArea.getWidth() > 0.3 ||
                Math.abs(originalArea.getHeight() - height) / originalArea.getHeight() > 0.3) {
//            System.out.println("Found cropped chart------------------------------");
            try {
                chart.setArea(mergeBox);
                chart.setLowerLeftArea(pageContentGroup.getPageTransform().createInverse().createTransformedShape(mergeBox).getBounds2D());
            } catch (NoninvertibleTransformException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * 对通过图表召回再次解析的低置信度的图表 直接尝试用截取区域方式保存为位图对象
     * 保证图表召回同时尽量保留好的解析结果
     * @param merge
     */
    public void reCropLowConfidenceRecallChart(List<TextChunk> merge) {
        // 如果检测区域信息为空　直接返回
        if (pageDetectedInfo == null || pageDetectedInfo.isEmpty()) {
            return;
        }

        // 判断是否有通过图表召回的并且置信度很低的图表对象
        boolean hasBadChart = false;
        for (Chart chart : charts) {
            if (chart.type != ChartType.BITMAP_CHART && chart.parserRecallArea && chart.confidence < 0.8) {
                hasBadChart = true;
                chart.type = ChartType.UNKNOWN_CHART;
            }
        }
        // 没有直接返回
        if (!hasBadChart) {
            return;
        }

        // 过滤无效类型的
        removeTypeErrorCharts();

        // 再次截取
        cropPageBitmapChart(merge);
    }


    /**
     * 计算所有chart置信度
     */
    public void compConfidence() {
        judgeChartsShColorStatus(charts);
        for (Chart chart : charts) {
            compConfidence(chart);
        }
    }

    /**
     *　评估Chart有效性指数  后续需要收集大量的搜索产品那边无效Chart反馈　优化计算方法 暂时没有使用
     * 最高为1.0 最低为0.0  　数值越高置信度越高
     * 目前搜索使用时　0.5 以下　只显示原图
     * @param chart
     */
    public void compConfidence(Chart chart) {
        // 默认设置为1.0
        chart.confidence = 1.0;
        // 如果data为空
        if (chart.data == null) {
            chart.confidence = 0.0;
            return;
        }

        boolean hasXAxisLine = chart.hAxis != null;
        boolean hasYAxisLine = chart.lvAxis != null || chart.rvAxis != null;
        int nAxisL = chart.vAxisTextL.size();
        int nAxisR = chart.vAxisTextR.size();
        int nAxisD = chart.hAxisTextD.size();
        int nAxisU = chart.hAxisTextU.size();
        ChartType type = chart.type;
        Set<Color> colors = new HashSet<>();
        // 饼图分析  饼图占比少且相对简单
        if (type == ChartType.PIE_CHART) {
            // 处理多饼图或环形图的情况
            Set<Integer> ids = new HashSet<>();
            chart.pieInfo.parts.stream().forEach(part -> ids.add(part.id));
            for (Integer id : ids) {
                List<Chart.PieInfo.PiePartInfo> parts = new ArrayList<>();
                chart.pieInfo.parts.stream().forEach(piePartInfo -> {
                    if (piePartInfo.id == id) {
                        parts.add(piePartInfo); }});
                colors.clear();
                parts.stream().forEach(piePartInfo -> colors.add(piePartInfo.color));
                int nparts = parts.size();
                // 判断是否有重复颜色
                if (nparts > colors.size()) {
                    double coef = 1.0 * colors.size() / nparts;
                    chart.confidence *= 0.8 * coef;
                }
                if (colors.stream().anyMatch(color -> isWhite(color))) {
                    chart.confidence *= 0.45;
                }
            } // end for id
            // 有坐标轴
            if (hasXAxisLine || hasYAxisLine) {
                chart.confidence *= 0.45;
            }
            // 有刻度
            if (nAxisD + nAxisU + nAxisL + nAxisR >= 1) {
                chart.confidence *= 0.45;
            }
            // 判断颜色数目
            int n = chart.pieInfo.parts.size();
            if (n == 1) {
                chart.confidence *= 0.5;
            }
            if (n == 0) {
                chart.confidence = 0.0;
            }
            // 如果解析出的显式图例指示框没有对应的文字信息
            if (chart.invalidLegends.size() >= 1 &&
                    chart.invalidLegends.stream().anyMatch(legend -> legend.text == null)) {
                chart.confidence *= 0.4;
            }
            return;
        }

        // 没有解析出刻度
        if (nAxisD == 0 && nAxisU == 0 && nAxisL == 0 && nAxisR == 0) {
            chart.confidence = 0.0;
            return;
        }

        if (chart.hAxisChunksD.size() == 2) {
            TextElement text1 = chart.hAxisChunksD.get(0).getFirstElement();
            TextElement text2 = chart.hAxisChunksD.get(1).getFirstElement();
            String fontName1 = text1.getFontName();
            String fontName2 = text1.getFontName();
            boolean diffFont = fontName1 != null && fontName2 != null && !fontName1.equals(fontName2);
            if (diffFont && text1.isBold() != text2.isBold()) {
                chart.confidence = 0;
            }
        }

        // 如果有左右两侧刻度
        if (nAxisL >= 1 && nAxisR >= 1) {
            Set<Integer> axisLR = new HashSet<>();
            if (chart.type != ChartType.COLUMN_CHART) {
                // 非水平柱状图的PathInfos 只对应一侧刻度
                chart.pathInfos.stream().forEach(pathInfo -> axisLR.add(pathInfo.ithSideY));
                if (axisLR.size() == 1) {
                    chart.confidence = 0.4;
                }
            }
        }

        // 没有图形对象
        if (chart.pathInfos.isEmpty()) {
            chart.confidence = 0.0;
            return;
        }

        // 计算左右侧刻度有效性  刻度通常为等差序列
        if (isAxisScaleInValid(chart)) {
            chart.confidence *= 0.4;
        }

        // 水平柱状图对刻度和Path的对应程度进行评估
        if (chart.type.equals(ChartType.COLUMN_CHART) && chart.pathInfos.size() == 1) {
            double ratio = Math.max((double) chart.vAxisChunksL.size() / (double) chart.pathInfos.get(0).valuesY.size(),
                    (double) chart.vAxisChunksR.size() / (double) chart.pathInfos.get(0).valuesY.size());
            if (ratio < 0.5) {
                chart.confidence *= 0;
            }
        }

        // 类型异常
        Set<PathInfo.PathType> types = new HashSet<>();
        chart.pathInfos.stream().forEach(pathInfo -> colors.add(pathInfo.color));
        chart.pathInfos.stream().forEach(pathInfo -> types.add(pathInfo.type));
        int nTypes = types.size();
        // 水平柱状图内含有其他类型PathInfo
        if (types.contains(PathInfo.PathType.COLUMNAR)) {
            // 水平柱状图　大概率含有坐标轴线
            if (!hasXAxisLine && !hasYAxisLine) {
                chart.confidence *= 0.8;
            }
            // 水平柱状图　和　其他类型PathInfo共存概率低
            if (nTypes >= 2) {
                chart.confidence *= 0.3;
            }
        }

        // 同时含有面积对象和柱状对象
        if (types.contains(PathInfo.PathType.AREA)) {
            // 面积高概率含有坐标轴线
            if (!hasXAxisLine && !hasYAxisLine) {
                chart.confidence *= 0.7;
            }
            // 面积和柱状低概率共存
            if (types.contains(PathInfo.PathType.COLUMNAR) ||
                    types.contains(PathInfo.PathType.BAR)) {
                chart.confidence *= 0.3;
            }
        }

        // 评估图例
        double legendConfidence = 1.0;
        if (!chart.legends.isEmpty()) {
            int count = 0;
            for (Chart.Legend legend : chart.legends) {
                if (StringUtils.isEmpty(legend.text)) {
                    count++;
                }
            }
            // 没有找到匹配文字的图例越多　置信度约低
            if (count >= 1) {
                legendConfidence = 0.3 + 1.0 / (count + 1);
            }
            // 如果解析出的显式图例指示框没有对应的文字信息
            if (chart.invalidLegends.size() >= 1 &&
                    chart.invalidLegends.stream().anyMatch(legend -> legend.text == null)) {
                double coef = 0.4 + 1.0 / (chart.invalidLegends.size() + 2);
                legendConfidence *= coef;
                chart.confidence *= 0.8;
            }
        }
        else {
            // 没有图例　并且含有多种类型PathInfo
            if (nTypes >= 2) {
                legendConfidence *= 0.4;
                chart.confidence *= 0.7;
            }
        }

        // 评估PathInfo
        int unLegend = 0;
        boolean hasCoordinateValue = false;
        double pathConfidence = 1.0;
        for (ChartPathInfo pathInfo : chart.pathInfos) {
            if (pathInfo.type == PathInfo.PathType.UNKNOWN) {
                continue;
            }
            if (StringUtils.isEmpty(pathInfo.text)) {
                unLegend++;
            }
            // 如果刻度属性没有解析出来  呈现的纯坐标值 即 highchart会显示 0 100  200   300  ....  这些常见错误
            if (pathInfo.valueByCoordinate) {
                // 如果是水平柱状图 且Y属性用的坐标值
                if (chart.type == ChartType.COLUMN_CHART &&
                        pathInfo.scaleTypeY == ScaleInfoType.NUMBER_SCALE) {
                    hasCoordinateValue = true;
                }
                // 如果是其他类型 且X属性用的坐标值
                else if (chart.type != ChartType.COLUMN_CHART &&
                        pathInfo.scaleTypeX == ScaleInfoType.NUMBER_SCALE) {
                    hasCoordinateValue = true;
                }
            }

            // 如果原Path在原图中的比例和解析后在HighChart内的比例相距甚远 则打压置信度
            // 暂时不打压水平柱状图
            if(!verifyPathValuesY(chart, pathInfo)) {
                pathConfidence *= 0.4;
                chart.confidence *= 0.4;
            }

            // 如果没有解析出属性值
            if (pathInfo.valuesY.isEmpty()) {
                pathConfidence *= 0.4;
                chart.confidence *= 0.4;
            }

            // 如果检测到很差的path直接返回
            if (chart.confidence == 0){
                return;
            }
        }

        // 如果部分pathInfo有图例对象 另一部分没有图例对象
        int nPath = chart.pathInfos.size();
        if (nPath - unLegend >= 1 && nPath >=2 && unLegend >= 1) {
            double coef = 1.0 * (nPath - unLegend) / nPath;
            pathConfidence = 0.3 + 0.7 * coef;
            legendConfidence *= (0.6 + 0.4 * coef);
        }

        // 评估pathInfo中颜色重复情况 重复颜色越多 置信度越低
        int nColor = colors.size();
        pathConfidence *= (0.7 + 0.3 * nColor / nPath);

        // 综合图例和PathInfo
        double legendPathConfidence = 0.3 * legendConfidence + 0.7 * pathConfidence;
        if (hasCoordinateValue && !chart.onlyMatchXScale) {
            chart.confidence = 0.0;
            legendPathConfidence = 0.0;
        }

        if (chart.isWaterFall) {
            chart.confidence *= 0.45;
            legendPathConfidence *= 0.45;
        }

        // 判断是否含有无效面积对象
        if (hasOnlyOneBandArea(chart) || hasSameYValueArea(chart)) {
            chart.confidence *= 0.4;
            legendPathConfidence *= 0.4;
        }

        // 判断是否pathInfos在highchart中取值一样
        if (pathInfosSameValue(chart) && !chart.onlyMatchXScale) {
            chart.confidence *= 0.4;
            legendPathConfidence *= 0.4;
        }

        if (!validXAxisScale(chart)) {
            chart.confidence *= 0.4;
            legendPathConfidence *= 0.4;
        }

        // 如果含有渐变色对象  现阶段渐变色解析难度很大　大部分情况解析不准确　故减低confidence
        if (chart.hasShColor) {
            chart.confidence *= 0.4;
            legendPathConfidence *= 0.4;
        }

        // 加权计算最后指数
        chart.confidence = 0.5 * chart.confidence + 0.5 * legendPathConfidence;
    }

    /**
     * 判断解析出的水平方向刻度的宽度相对于水平坐标轴是否过小
     * @param chart
     * @return
     */
    private boolean validXAxisScale(Chart chart) {
        if (chart.type == ChartType.COLUMN_CHART) {
            return true;
        }
        if (chart.hAxis == null) {
            return true;
        }
        TextChunk chunk1 = null, chunk2 = null;
        if (!chart.hAxisChunksD.isEmpty()) {
            chunk1 = chart.hAxisChunksD.get(0);
            int n = chart.hAxisChunksD.size();
            chunk2 = chart.hAxisChunksD.get(n - 1);
        }
        if (!chart.hAxisChunksU.isEmpty()) {
            chunk1 = chart.hAxisChunksU.get(0);
            int n = chart.hAxisChunksU.size();
            chunk2 = chart.hAxisChunksU.get(n - 1);
        }
        if (chunk1 == null || chunk2 == null) {
            return true;
        }
        double width = chart.hAxis.getBounds().getWidth();
        double widthScale = chunk2.getMaxX() - chunk1.getMinX();
        return widthScale / width > 0.15;
    }

    /**
     * 判断chart.pathinfos是否取值一样
     * @param chart
     * @return
     */
    private boolean pathInfosSameValue(Chart chart) {
        List<List<Double>> pathsValues = HighChartWriter.getPathInfosJsonValues(chart.data);
        if (pathsValues == null || pathsValues.isEmpty()) {
            return true;
        }
        int sameCount = 0;
        for (List<Double> values : pathsValues) {
            if (values.size() >= 2) {
                Double d = values.get(0);
                if (values.stream().allMatch(v -> ChartUtils.equals(v, d))) {
                    sameCount++;
                }
            }
        } // end for values
        if (sameCount == pathsValues.size()) {
            return true;
        }
        else {
            return false;
        }
    }

    /**
     * 判断给定pathInfo是否Y值解析大致正确
     *
     * @param chart
     * @param pathInfo
     */
    private boolean verifyPathValuesY(Chart chart, ChartPathInfo pathInfo) {
        // 过滤掉非折线对象
        boolean isLine = pathInfo.type == PathInfo.PathType.LINE ||
                pathInfo.type == PathInfo.PathType.CURVE ||
                pathInfo.type == PathInfo.PathType.DASH_LINE;
        if (!isLine) {
            return true;
        }

        // 如果原Path在原图中的比例和解析后在HighChart内的比例相距甚远 则打压置信度
        Rectangle2D bound = pathInfo.path.getBounds2D();
        Line2D axisY = pathInfo.ithSideY == 0 ? chart.lvAxis : chart.rvAxis;
        List<String> axisTextY = pathInfo.ithSideY == 0 ? chart.vAxisTextL : chart.vAxisTextR;

        if (!axisTextY.isEmpty()) {
            List<Double> pathDoublesY = new ArrayList<>();
            List<String> pathSuffixs = new ArrayList<>();
            double [] num = {0.0};
            String [] suffix = {""};
            for (String valueY : pathInfo.valuesY) {
                if (ChartContentScaleParser.string2Double(valueY, num, suffix)) {
                    pathDoublesY.add(num[0]);
                    if (StringUtils.isNotEmpty(suffix[0]) && !pathSuffixs.contains(suffix[0])) {
                        pathSuffixs.add(suffix[0]);
                    }
                }
            }
            /*
            pathInfo.valuesY.forEach(valueY -> {double [] num = {0.0};
                String [] suffix = {""};
                if (ChartContentScaleParser.string2Double(valueY, num, suffix)) {
                    pathDoublesY.add(num[0]);
                }
            });
            */
            if (pathDoublesY.isEmpty()) {
                // 可能需要修改置信度
                return false;
            }
            else if (pathDoublesY.size() == 1) {
                return true;
            }

            List<Double> axisDoublesY = new ArrayList<>();
            List<String> axisSuffixs = new ArrayList<>();
            for(String valueAxis : axisTextY) {
                if (ChartContentScaleParser.string2Double(valueAxis, num, suffix)) {
                    axisDoublesY.add(num[0]);
                    if (StringUtils.isNotEmpty(suffix[0]) && !axisSuffixs.contains(suffix[0])) {
                        axisSuffixs.add(suffix[0]);
                    }
                }
            }
            /*
            axisTextY.forEach(text -> {double [] num = {0.0};
                String [] suffix = {""};
                if (ChartContentScaleParser.string2Double(text, num, suffix)) {
                    axisDoublesY.add(num[0]);
                }
            });
            */
            if (axisDoublesY.isEmpty()) {
                // 可能需要修改置信度
                return false;
            }

            double axisMaxY = Collections.max(axisDoublesY);
            double axisMinY = Collections.min(axisDoublesY);
            double pathMaxY = Collections.max(pathDoublesY);
            double pathMinY = Collections.min(pathDoublesY);
            double pathMidY = 0.5 * (pathMaxY + pathMinY);
            double rangeY = pathMaxY - pathMinY;

            // 如果刻度有百分号，但是path解析出的属性没有
            int numAxisSuffix = axisSuffixs.size();
            int numPathSuffix = pathSuffixs.size();
            if (numPathSuffix == 1 && numAxisSuffix == 0 && pathSuffixs.get(0).equals("%")) {
                if (pathMidY > axisMaxY && pathMidY / 100 < axisMaxY) {
                    axisMinY *= 100;
                    axisMaxY *= 100;
                }
            }

            double ratioHighChart = rangeY / (axisMaxY - axisMinY);
            // 计算不在坐标轴刻度范围内的点集数目占比  如果占比过大 则打压置信度
            int noMatchNum = 0;
            for (Double y : pathDoublesY) {
                if (y > axisMaxY || y < axisMinY) {
                    noMatchNum++;
                }
            }
            if (1.0 * noMatchNum / pathDoublesY.size() > 0.2) {
                chart.confidence = 0;
            }

            if (axisY != null) {
                double ratio = (bound.getMaxY() - bound.getMinY()) / (Math.abs(axisY.getY2() - axisY.getY1()));
                if (ratio < 0.05) {
                    // path本身占比就很小 则不考虑其对confidence的影响
                    return true;
                }
                // 如果出现异常ratio差距 返回false 一般返回true
                return !(ratio / ratioHighChart > 50);
            }
        }
        return true;
    }

    /**
     * 判断给定Chart是否含有一个面积对象且是带状对象
     * 目前对于带状面积图解析效果不佳
     * @param chart
     * @return
     */
    boolean hasOnlyOneBandArea(Chart chart) {
        int count = 0;
        // 计算给定面积Path上下边界对应点对　的位置信息
        List<Double> xs = new ArrayList<>();      // 对应点的X坐标值集
        List<Double> ysU = new ArrayList<>();     // 对应点上侧的Y坐标值集
        List<Double> ysD = new ArrayList<>();     // 对应点下侧的X坐标值集
        boolean hasOnlyOneBandArea = true;
        for (ChartPathInfo pathInfo : chart.pathInfos) {
            if (pathInfo.type == PathInfo.PathType.AREA) {
                count++;
                if (!ChartContentScaleParser.getPathAreaPoints(pathInfo.path, chart.hAxis, xs, ysU, ysD)) {
                    return false;
                }
                final double yu = ysU.get(0);
                boolean sameValueU = ysU.stream().allMatch(v -> Math.abs(yu - v) < 0.2);
                final double yd = ysD.get(0);
                boolean sameValueD = ysD.stream().allMatch(v -> Math.abs(yd - v) < 0.2);
                if (sameValueU || sameValueD) {
                    hasOnlyOneBandArea = false;
                }
            }
        }
        return (hasOnlyOneBandArea && count == 1);
    }

    /**
     * 判断是否含有Y值属性都一样的面积对象
     * @param chart
     * @return
     */
    boolean hasSameYValueArea(Chart chart) {
        for (ChartPathInfo pathInfo : chart.pathInfos) {
            if (pathInfo.type == PathInfo.PathType.AREA) {
                if (pathInfo.valuesY.size() <= 2) {
                    continue;
                }
                String y = pathInfo.valuesY.get(0);
                if (pathInfo.valuesY.stream().allMatch(value -> value.equals(y))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 判断Chart保存的HighChart数据的左右侧刻度有效性
     * @param chart
     * @return
     */
    private boolean isAxisScaleInValid(Chart chart) {
        if (chart.data == null || !chart.data.has("yAxis")) {
            return false;
        }
        JsonArray axises = chart.data.get("yAxis").getAsJsonArray();
        for (int i = 0; i < axises.size(); i++) {
            JsonObject axis = axises.get(i).getAsJsonObject();
            if (!axis.has("tickPositions")) {
                continue;
            }
            JsonArray array = axis.get("tickPositions").getAsJsonArray();
            List<Double> values = new ArrayList<>();
            for (int j = 0; j < array.size(); j++) {
                Double value = array.get(j).getAsDouble();
                values.add(value);
            }
            if (!ChartContentScaleParser.isArithmeticSequence(values)) {
                return true;
            }
        } // end for i
        return false;
    }

    /**
     * 基于Chart内部的图形信息　优化分割TextChunk
     * 如果字符在空间上高度相邻　目前的TextChunk可能需要进一步分割
     * @param charts
     */
    private void optiSplitChunks(List<Chart> charts) {
        for (Chart chart : charts) {
            ChartType type = chart.type;
            if (type == ChartType.UNKNOWN_CHART ||
                    type == ChartType.BITMAP_CHART ||
                    type == ChartType.AREA_CHART ||
                    type == ChartType.AREA_OVERLAP_CHART ||
                    type == ChartType.PIE_CHART ||
                    type == ChartType.COLUMN_CHART) {
                continue;
            }
            // 目前只利用了相邻柱状或折线上相邻顶点的中间位置　作为分割点
            // 后续继续优化
            List<Double> pos = ChartPathInfosParser.getSplitPosition(chart);
            ChartPathInfosParser.splitAndMergeChartText(chart, chart.chunksRemain, pos, true);
            // 利用轴标分割
//            splitWithAxisLine(chart);
        } // end for chart
    }

    private void splitWithAxisLine(Chart chart) {
        List<Double> axisScalePts = ChartPathInfosParser.getXAxisScaleLineSplitPts(chart);
        // 轴标太少无法分割
        if (axisScalePts.size() < 2) {
            return;
        }

        List<TextChunk> filtered = new ArrayList<>();
        for (int i = 0; i < chart.chunksRemain.size(); i++) {
            TextChunk chunk = chart.chunksRemain.get(i);
            TextDirection direction = chunk.getDirection();
            // 过滤掉非水平方向的
            if (direction != TextDirection.LTR) {
                continue;
            }
            if (chunk.getMinY() <= chart.hAxis.getY1()) {
                continue;
            }
            // 忽略掉设置图例或忽略标记的
            ChunkStatusType type = ChunkUtil.getChunkType(chunk);
            if (type == ChunkStatusType.LEGEND_TYPE ||
                    type == ChunkStatusType.IGNORE_TYPE ||
                    type == ChunkStatusType.DATA_SOURCE_TYPE) {
                continue;
            }
            // 如果是高概率标题对象　则跳过
            String text = chunk.getText().trim();
            if (!StringUtils.isEmpty(text) &&
                    ChartTitleLegendScaleParser.bHighProbabilityChartTitle(text)) {
                continue;
            }
            filtered.add(chunk);
        }

        if (filtered.size() <= 2 || axisScalePts.size() <= filtered.size()) {
            return;
        }

        int cursor = 0;
//        boolean fitStatus = true;
//        double width = chart.hAxis.getBounds().getWidth();
        // 目前只处理最后一个TextChunk需要分割的情况
        Iterator<TextChunk> filteredIterator = filtered.iterator();
        while (filteredIterator.hasNext()) {
            TextChunk textChunk = filteredIterator.next();
//            double scale = textChunk.getWidth() / width;

            double left = axisScalePts.get(cursor);
            double right = axisScalePts.get(cursor + 1);

            if (textChunk.getLeft() > left && textChunk.getRight() < right) {
                // 文字完美落在轴标之间
            } else {
                break;
            }

            cursor++;
        }

        if (cursor == filtered.size() - 1) {
            // 之前全部是完美落在轴标之间
            ChartPathInfosParser.splitChunk(filtered, cursor, axisScalePts.subList(cursor, axisScalePts.size() - 1));
        }

        chart.chunksRemain = filtered;
    }

    /**
     * 过滤掉无效 Chart 将其类型设置为 ChartType.UNKNOWN_CHART
     * @param charts
     */
    private void filterInValidChart(
            List<Chart> charts, List<TextChunk> textChunks) {
        for (Chart chart : charts) {
            if (chart.type == ChartType.COLUMN_CHART || chart.type == ChartType.BAR_CHART) {
                if (ChartPathInfosParser.isColumnarChartInValid(chart)) {
                    chart.type = ChartType.UNKNOWN_CHART;
                }
            }
        }

        // 找出位图内部有文字信息的对象集 此时一般为背景图片对象 将其类型设置为 UNKNOWN_CHART
        for (Chart chart : charts) {
            if (chart.type == ChartType.BITMAP_CHART) {
                Rectangle area = chart.getArea();
                double x = area.getCenterX();
                double y = area.getCenterY();
                double w = 0.7 * area.getWidth();
                double h = 0.7 * area.getHeight();
                Rectangle2D innerArea = new Rectangle2D.Double(x - 0.5 * w, y - 0.5 * h, w, h);
                boolean hasText = textChunks.stream().anyMatch(chunk ->
                        innerArea.intersects(chunk));
                if (hasText && !chart.is3DChart) {
                    chart.type = ChartType.UNKNOWN_CHART;
                }
            }
        }
    }

    /**
     * 去掉被矢量Chart覆盖的位图对象  通常为小背景图片或是图形对象的阴影图片
     */
    private void removeBitmapCoverByChart() {
        // 取出当前Page内所有的Chart
        removeTypeErrorCharts();
        List<Chart> vectorCharts = new ArrayList<>();
        List<Chart> bitmapCharts = new ArrayList<>();

        // 遍历 charts  找出位图Chart对象和矢量Chart对象
        for (Chart chart : charts) {
            if (chart.type == ChartType.UNKNOWN_CHART ||
                    chart.isPPT) {
                continue;
            }
            if (chart.type == ChartType.BITMAP_CHART) {
                bitmapCharts.add(chart);
            }
            else {
                vectorCharts.add(chart);
            }
        } // end for chart

        // 遍历位图Chart对象
        for (Chart chart : bitmapCharts) {
            // 检测是否存在与某个矢量Chart的有效区域内部
            for (Chart vchart : vectorCharts) {
                // 如果在内部　　即此时很有可能是小背景图片或是图形对象的阴影图片  设置为无效类型
                if (GraphicsUtil.contains(vchart.getArea(), chart.getArea())) {
                    chart.type = ChartType.UNKNOWN_CHART;
                    break;
                }
            }
        } // end for chart

        // 过滤掉无效类型的Chart对象
        removeTypeErrorCharts();
    }

    private void selectSubtitleLegend(
            List<TextChunk> textChunks,
            List<Chart> charts) {
        // 设置可以忽略的Chunk集
        ChartTitleLegendScaleParser.setIgnoreChunks(textChunks, charts);

        // 设置Subtitle类型
        ChartTitleLegendScaleParser.setSubtitle(textChunks, charts);

        // 解析图例
        ChartTitleLegendScaleParser.selectLegends(textChunks, charts);
    }

    /**
     * 解析chart标题 刻度 信息
     * @param textChunks 输入的当前page的chunk集
     * @param charts 当前page的chart集
     */
    private void selectTitleLegendScale(
            List<TextChunk> textChunks,
            List<Chart> charts) {
        // 解析刻度信息
        ChartTitleLegendScaleParser.selectScale(textChunks, charts);

        // 解析标题
        ChartTitleLegendScaleParser.selectTitle(textChunks, charts);

        // 解析部分在上一页的标题情况
        ChartTitleLegendScaleParser.selectTitleFromBeforePage(textChunks, charts, context, pageIndex);

        // 找出给定Chart左下角对应的数据来源信息
        ChartTitleLegendScaleParser.getChartDataSourceInfo(charts, textChunks);

        // 找出刻度的单位信息
        ChartTitleLegendScaleParser.selectChartsAxisUnit(charts);

        // 找出Chart内部除图列和刻度之外的信息 保存起来 作为Chart内容的表示之一
        // 在常规标题解析不出来的情况下 用来判断chart是否解析正确
        ChartTitleLegendScaleParser.getChartInnerTexts(charts, textChunks);

        // 将离Chart内部图形元素包围框最近的有效合理Chunk作为候选Title 开发中
        ChartTitleLegendScaleParser.optimizeTitleParser(charts);
    }

    private void removeNoneCharts() {
        removeNoneCharts(false);
    }

    private void removeNoneCharts(boolean removeEmptyLegends) {
        final Iterator<Chart> each = charts.iterator();
        while (each.hasNext()) {
            Chart chart = each.next();
            if (removeEmptyLegends) {
                chart.removeEmptyLegends();
            }
            if (!chart.isChart()) {
                each.remove();
            }
        }
    }

    /**
     * 去除区域包含常规chart的背景位图对象
     */
    private void removeChartBackgroundImage() {
        List<Chart> bitmap_charts = new ArrayList<>();
        // 找出位图对象集
        charts.forEach(chart -> {
            if (chart.type == ChartType.BITMAP_CHART) {
                bitmap_charts.add(chart);
            }
        });
        if (bitmap_charts.isEmpty()) {
            return;
        }
        // 遍历位图对象集　找出其区域包含其他常规Chart的对象, 将其类型设置为 UNKNOWN_CHART
        for (Chart bchart : bitmap_charts) {
            for (Chart chart : charts) {
                if (chart.type != ChartType.UNKNOWN_CHART &&
                        !chart.isPPT &&
                        chart.type != ChartType.BITMAP_CHART) {
                    if (!bchart.getArea().intersects(chart.getArea())) {
                        continue;
                    }
                    Rectangle2D intersectsArea = new Rectangle2D.Double();
                    Rectangle2D.intersect(bchart.getArea(), chart.getArea(), intersectsArea);
                    double coef = Math.abs(intersectsArea.getWidth() * intersectsArea.getHeight())
                            / (chart.getArea().getWidth() * chart.getArea().getHeight());
                    if (coef >= 0.8) {
                        bchart.type = ChartType.UNKNOWN_CHART;
                        break;
                    }
                }
            } // end for chart
        } // end for bchart
    }

    /**
     * 去掉类型解析错误的Chart
     */
    private void removeTypeErrorCharts() {
        charts.removeIf(chart -> chart.type == ChartType.UNKNOWN_CHART);
    }

    // 基于TF Model 检测出的Chart 和 Table 信息 裁剪Page内部保存为位图
    public void cropPageBitmapChart(List<TextChunk> chunks) {
        // 判断数据有效性
        if (pageDetectedInfo == null || pageDetectedInfo.isEmpty()) {
            return;
        }
        // 遍历 从位图检测Chart区域模型检测出来的所有大致Chart区域
        List<Chart> pageCharts = charts;
        List<Chart> newCharts = new ArrayList<>();
        List<DetectedObjectTF> objs = new ArrayList<>();
        for (int i = 0; i < pageDetectedInfo.size(); i++) {
            DetectedChartTableTF obj = (DetectedChartTableTF)pageDetectedInfo.get(i);
            // 如果是无效对象　则跳过
            if (obj.typeID == ChartType.UNKNOWN_CHART.getValue() &&
                    !ChartClassify.isImageValid(obj.imageTypes) &&
                    !onlyParseHintArea) {
                continue;
            }

            // 判断当前检测对象是否已经出现在 解析出来的 Chart 集中
            boolean sameChart = false;
            Rectangle2D objArea = new Rectangle2D.Float(obj.box[0], obj.box[1],
                    obj.box[2] - obj.box[0], obj.box[3] - obj.box[1]);
            for (Chart chart : pageCharts) {
                if (chart.getArea().contains(objArea.getCenterX(), objArea.getCenterY())) {
                    sameChart = true;
                    break;
                }
            }
            if (sameChart) {
                continue;
            }

            // 如果没有和已经解析出来的矢量和位图Chart对象区域重叠
            // 则新建一个 BITMAP_CROP_CHART 的 Chart对象
            Chart newChart = new Chart();
            obj.setArea();
            newChart.type = ChartType.BITMAP_CHART;
            newChart.setArea(objArea);
            newChart.cropImage = obj.subImage;
            newChart.parserHintArea = onlyParseHintArea;
//            charts.add(newChart);
            newCharts.add(newChart);
            objs.add(obj);
            // 保存　测试调试用
            //String file_id = "chart_" + currentPageIndex + "_" + i;
            //TFDetectChartTable.save_detect_obj_image(obj.subImage, file_id);
        } // end for obj
        if (newCharts.isEmpty()) {
            return;
        }

        // 解析标题
        ChartTitleLegendScaleParser.selectTitle(chunks, newCharts);

        // 找出给定Chart左下角对应的数据来源信息
        ChartTitleLegendScaleParser.getChartDataSourceInfo(newCharts, chunks);

        // 如果新Chart不为空　　则将Page扩大到最大尺寸　然后截取Chart区域
        // 这样保存大分辨率位图　方便后续位图解析算法
        PDFRenderer pdfRenderer = new PDFRenderer(context.getNativeDocument());
        try {
            // 将Page渲染成 BufferedImage 对象
            float scale = 2.0f;     // 扩大两倍　保存大尺寸图片  方便后续调用位图解析算法

            //long start = System.currentTimeMillis();

            BufferedImage bigPage = pdfRenderer.renderImageWithDPI(
                   pageIndex, 72.0f * scale, ImageType.RGB);
            for (int i = 0; i < newCharts.size(); i++) {
                DetectedChartTableTF obj = (DetectedChartTableTF)objs.get(i);
                int heightBefore = (int)(bigPage.getHeight()/scale);
                obj.transferLeftUpOriginPoint(heightBefore);
                obj.setCropSubImage(bigPage, scale);
                obj.transferLeftUpOriginPoint(heightBefore);
                Chart chart = newCharts.get(i);
                charts.add(chart);
                chart.cropImage = obj.subImage;
            }

            //long costTime = System.currentTimeMillis() - start;
            //ChartClassify.savePageImageTime += costTime;
            //ChartClassify.savePageImageCount++;

        } catch (Exception e) {
            return;
        }
    }

    public void setCheckBitmap(boolean check) {
        checkBitmap = check;
    }

    private boolean checkBitmap = false;
    private boolean useChartClassify = false;
    private boolean onlyParseHintArea = false;
    private ContentGroup current;
    private Rectangle2D checkArea = new Rectangle2D.Double();

    // chart绘制的状态
    private enum Status {
        UNKNOWN, // 未知状态
        BEGIN_GRAPHICS, // 开始绘制坐标轴, 折线, 柱状, 扇形
        BEGIN_AXIS_TEXT, // 开始绘制坐标轴文字
        BEGIN_LEGEND, // 开始绘制图例
        BEGIN_OCR, // 开始绘制斜着文字
    }
    private Status status;
    private List<Chart> charts = new ArrayList<>();

    private Chart chart;
    private ContentGroup chartStart;                             // 当前Chart开始的状态
    private ContentGroup chartContentGroup;
    private GeneralPath segmentsPath = new GeneralPath();        // chart 内部连续的 path 对象 组合起来可能构成有效的折线
    private List<Color> segmentsColor = new ArrayList<>();       // chart 内部连续的 segmentsPath 的绘制颜色集合

    private final PdfExtractContext context;
    private final int pageIndex;
    private final ContentGroup pageContentGroup;
    private boolean checkedGraphic = false;                      // 是否解析过当前Chart图形对象的状态
    private PDFormXObject form;                                  // 当前处理的 Form 对象
    private Rectangle2D formBox;                                 // form的实际大小相对于 page

    private List<ChartPathInfosParser.ArcObject> circles = new ArrayList<>(); // chart 内部圆形对象 散点或是图例对象
    private List<ChartPathInfosParser.ArcObject> arcs = new ArrayList<>(); // chart 内部扇形对象
    private List<List<ChartPathInfosParser.ArcObject>> pies = new ArrayList<>(); // chart 内部多个饼图或环形图对象
    private List<ChartPathInfosParser.ArcObject> tris = new ArrayList<>(); // chart 内部等要三角形对象(有可能是饼图中的小块)
    private List<PathInfo.PathType> paths = new ArrayList<>(); // chart 内部的路径对象
    private List<ChartPathInfo> pathInfos = new ArrayList<>();   // Chart 内部有效Path信息集 保存起来　做后续检测
    private List<ChartPathInfo> specialPathInfos = new ArrayList<>();   // Chart 内部特殊Path信息集 保存起来　做后续检测
    private List<Line2D> hAxisLines = new ArrayList<>();
    private List<Line2D> vAxisLines = new ArrayList<>();
    private Set<Color> fillColors = new HashSet<>();
    private Set<Color> strokeColors = new HashSet<>();
    private List<OCRPathInfo> ocrs = new ArrayList<>();      // 当前Chart 内部所有的斜着的刻度信息集
    private List<DetectedObjectTF> pageDetectedInfo = new ArrayList<>();  // 基于深度学习模型检测出的Page图片内Chart区域
    private List<List<Integer>> detectObjStatus = new ArrayList<>();      // 基于模型检测出的Chart区域内数据块序号集

    private static class GraphicsParserStatus {
        public int nPathInfos;
        public int nHAxis;
        public int nArcs;
        public Set<Color> fillColors;   // 保存当填充颜色状态
        public Set<Color> strokeColors;  // 保存当填充颜色状态
        GraphicsParserStatus() {
            nPathInfos = 0;
            nHAxis = 0;
            nArcs = 0;
            fillColors = new HashSet<>();
            strokeColors = new HashSet<>();
        }
    }
    private GraphicsParserStatus gStatus = new GraphicsParserStatus();     // 解析过程中图形对象的解析状态

    /**
     * 判断给定Chart集是否含有渐变色对象的状态
     * @param charts
     */
    private void judgeChartsShColorStatus(List<Chart> charts) {
        for (Chart chart : charts) {
            judgeChartShColor(chart);
        }
    }

    /**
     * 判断Chart内部有无渐变色对象
     * @param chart
     */
    private void judgeChartShColor(Chart chart) {
        // 如果没有内容
        if (chart == null || chart.contentGroup == null) {
            return;
        }

        // 取出所有的shaping 的颜色信息
        List<ContentGroup> groups = chart.contentGroup.getAllContentGroups(true, true);
        Map<String, List<Color>> shColors = getShColor(groups);
        if (!shColors.isEmpty()) {
            chart.hasShColor = true;
        }
    }

    /**
     * 获取给定contentgroup集内部渐变色对象信息
     * @param groups
     * @return
     */
    private Map<String, List<Color>> getShColor(List<ContentGroup> groups) {
        // 取出所有的shaping 的颜色信息
        Map<String, List<Color>> shColors = new HashMap<>();
        for (ContentGroup group : groups) {
            group.forEach(PathItem.class, pathItem -> {
                if (pathItem.isFill()) {
                    String shName = pathItem.getShadingName();
                    if (!StringUtils.isEmpty(shName)) {
                        List<Color> colors = new ArrayList<>();
                        if (shColors.containsKey(shName)) {
                            colors = shColors.get(shName);
                        }
                        colors.add(pathItem.getColor());
                        shColors.put(shName, colors);
                    }
                }
            });
        } // end for group
        return shColors;
    }

    /**
     * 重置渐变色对象的颜色　同名设置相同颜色
     * @param groups
     */
    private void resetShColor(List<ContentGroup> groups) {
        // 取出所有的shaping 的颜色信息
        Map<String, List<Color>> shColors = getShColor(groups);
        if (shColors.isEmpty()) {
            return;
        }

        // 将同名颜色　取平均值
        Map<String, Color> shColorMap = new HashMap<>();
        for (Map.Entry<String, List<Color>> entry: shColors.entrySet()) {
            String name = entry.getKey();
            List<Color> colors = entry.getValue();
            int count = 0;
            int r = 0, g = 0, b = 0;
            for (Color color: colors) {
                if (color == null) {
                    continue;
                }
                r += color.getRed();
                g += color.getGreen();
                b += color.getBlue();
                count++;
            }
            r = (int)(1.0 * r / count);
            g = (int)(1.0 * g / count);
            b = (int)(1.0 * b / count);
            shColorMap.put(name, new Color(r, g, b));
        }

        // 将计算的平均颜色赋值为相应渐变色对象
        for (ContentGroup group : groups) {
            group.forEach(PathItem.class, pathItem -> {
                if (pathItem.isFill()) {
                    String shName = pathItem.getShadingName();
                    if (!StringUtils.isEmpty(shName)) {
                        if (shColorMap.containsKey(shName)) {
                            Color color = shColorMap.get(shName);
                            pathItem.setRGBColor(color);
                        }
                    }
                }
            });
        } // end for group
    }

    /**
     * 用图例的颜色信息匹配图形对象
     * 如果存在和目标图形同色的辅助图形对象　则会出错
     * 目前进展是　
     * 图例匹配折线　效果还不错，但是有些情况会召回很多错误的线对象
     * 图例匹配柱状　效果稍微差一点
     */
    private void legendMatchPathInfos() {
        if (charts == null || charts.isEmpty()) {
            return;
        }

        // 遍历charts
        for (Chart chart : charts) {
            // 处理折线对象
            if (chart.type == ChartType.CURVE_CHART || chart.type == ChartType.LINE_CHART) {
                legendMatchLineAndBar(chart, ChartType.LINE_CHART);
            }

            // 处理柱状对象
            if (chart.type == ChartType.BAR_CHART || chart.type == ChartType.COLUMN_CHART) {
                legendMatchLineAndBar(chart, ChartType.BAR_CHART);
            }
        } // end for chart
    }

    /**
     * 判断给定对象是否和图例对象重叠
     * @param objBox
     * @param pt
     * @param lBoxes
     * @param lPts
     * @return
     */
    private boolean isBoxOverlapWithLegends(
            Rectangle2D objBox, Point2D pt,
            List<Rectangle2D> lBoxes, List<Point2D> lPts) {
        if (lBoxes == null || lBoxes.isEmpty() || lPts == null ||
                lPts.isEmpty() || lBoxes.size() != lPts.size()) {
            return false;
        }
        // 判断是否和某个图例对象重叠
        for (int i = 0; i < lBoxes.size(); i++) {
            Rectangle2D lBox = lBoxes.get(i);
            Point2D lPt = lPts.get(i);
            // 过滤掉位置重叠的　大概率是图例对象
            if (lBox.contains(pt) && objBox.contains(lPt)) {
                return true;
            }
        } // end for i
        return false;
    }

    /**
     * 获取图例位置信息　包括包围框和中心点
     * 方便后续同图例比较空间是否重叠
     * @param legends
     * @param lBoxes
     * @param lPts
     */
    private void getChartLegendPosition(
            List<Chart.Legend> legends, List<Rectangle2D> lBoxes, List<Point2D> lPts) {
        lBoxes.clear();
        lPts.clear();
        // 计算图例位置和代表点信息
        for (int ilegend = 0; ilegend < legends.size(); ilegend++) {
            Chart.Legend legend = legends.get(ilegend);
            Rectangle2D lBox = (Rectangle2D) legend.line.clone();
            GraphicsUtil.extendRect(lBox, 0.1);
            Point2D lpt = new Point2D.Double(lBox.getCenterX(), lBox.getCenterY());
            lBoxes.add(lBox);
            lPts.add(lpt);
        }
    }

    /**
     * 对没有图例并且只存在一种颜色的折线或柱状对象构建虚拟图例对象
     * 后续通过图例颜色召回同色图形对象用
     * @param chart
     * @return
     */
    private Chart.Legend buildChartVirtualLegend(Chart chart) {
        // 如果存在图例或是pathInfos不为空  直接返回
        if (!chart.legends.isEmpty() || chart.pathInfos.isEmpty()) {
            return null;
        }

        double width = chart.getWidth();
        double height = chart.getHeight();
        double wCoef = 0.0, hCoef = 0.0;
        if (ChartUtils.isLineChart(chart)) {
            wCoef = 0.2;
            hCoef = 0.1;
        }
        else if (ChartUtils.isBarChart(chart)) {
            wCoef = 0.3;
            hCoef = 0.2;
        }
        // 其他类别暂时不处理
        else {
            return null;
        }

        // 判断是否同色
        Color color = chart.pathInfos.get(0).color;
        boolean sameColor = chart.pathInfos.stream().allMatch(pathInfo -> pathInfo.color.equals(color));
        if (!sameColor) {
            return null;
        }

        // 合并区域
        Rectangle2D box = chart.pathInfos.get(0).path.getBounds2D();
        for (ChartPathInfo pathInfo : chart.pathInfos) {
            Rectangle2D.union(box, pathInfo.path.getBounds2D(), box);
        }

        // 判断尺寸是否合适
        double w = box.getWidth();
        double h = box.getHeight();
        double wScale = w / width, hScale = h / height;
        if (wScale > wCoef && hScale > hCoef) {
            Chart.Legend legend = new Chart.Legend();
            legend.color = color;
            double x = chart.getLeft() + 20;
            double y = chart.getTop() + 20;
            legend.line = new Rectangle2D.Double(x, y, x + 20, y);
            return legend;
        }

        return null;
    }

    /**
     * 对折线图用图例匹配折线或柱状
     * @param chart
     */
    private void legendMatchLineAndBar(Chart chart, ChartType chartType) {
        // 判断是否为折线图
        if (chartType == ChartType.LINE_CHART) {
            if (!ChartUtils.isLineChart(chart)) {
                return;
            }
        }
        else if (chartType == ChartType.BAR_CHART) {
            if (!ChartUtils.isBarChart(chart)) {
                return;
            }
        }
        else {
            return;
        }

        // 判断是否存在图例对象
        List<Chart.Legend> legends = new ArrayList<>();
        legends.addAll(chart.legends);
        if (legends.isEmpty()) {
            // 尝试对单一颜色折线或是柱状对象 构建虚拟图例对象　方便召回所有同色图形对象
            Chart.Legend vLegend = buildChartVirtualLegend(chart);
            if (vLegend != null) {
                legends.add(vLegend);
            }
            else {
                return;
            }
        }

        // 将Chart内的图形数据分组
        ChartUtils.markChartPath(chart);

        // 清空现有pathInfo集
        pathInfos.clear();

        // 计算图例位置和代表点信息
        List<Rectangle2D> lBoxes = new ArrayList<>();
        List<Point2D> lPts = new ArrayList<>();
        getChartLegendPosition(legends, lBoxes, lPts);

        Rectangle2D box = chart.getArea();
        List<ContentGroup> allContentGroups = chart.contentGroup.getAllContentGroups(true, true);
        Set<Integer> matchGroup = new HashSet<>();

        // 遍历图例对象
        for (int ilegend = 0; ilegend < legends.size(); ilegend++) {
            Chart.Legend legend = legends.get(ilegend);
            Color lcolor = legend.color;
            Rectangle2D lBox = lBoxes.get(ilegend);
            List<Integer> pathIds = new ArrayList<>();
            // 遍历chart内部contentgrou集
            for (int i = 0; i < chart.markPaths.size(); i++) {
                List<Integer> groupIDs = chart.markPaths.get(i);
                if (groupIDs.isEmpty()) {
                    continue;
                }
                int id = groupIDs.get(0);
                ContentGroup contentGroup = allContentGroups.get(id);
                ContentItem item = contentGroup.getAllItems().get(0);
                PathItem pathItem = (PathItem) item;
                Color pcolor = pathItem.getColor();
                // 找出颜色相匹配的
                if (lcolor.equals(pcolor)) {
                    pathIds.addAll(groupIDs);
                }
            } // end for group
            List<ContentGroup> groups = new ArrayList<>();
            for (Integer id : pathIds) {
                if (matchGroup.contains(id)) {
                    continue;
                }
                ContentGroup contentGroup = allContentGroups.get(id);
                ContentItem item = contentGroup.getAllItems().get(0);
                PathItem pathItem = (PathItem) item;
                Rectangle2D pBox = pathItem.getBounds();
                GraphicsUtil.extendRect(pBox, 0.1);
                Rectangle2D cBox = contentGroup.getArea();
                GraphicsUtil.extendRect(cBox, 0.1);
                Rectangle2D.intersect(pBox, cBox, pBox);
                Point2D ppt = new Point2D.Double(pBox.getCenterX(), pBox.getCenterY());
                // 过滤掉位置重叠的　大概率是图例对象
                if (isBoxOverlapWithLegends(pBox, ppt, lBoxes, lPts)) {
                    continue;
                }
                if (lBox.contains(ppt)) {
                    // 判断尺寸是否合适
                    GeneralPath path = pathItem.getItem();
                    Rectangle2D area = getLegendArea(path);
                    if (area != null) {
                        continue;
                    }
                }
                groups.add(contentGroup);
                matchGroup.add(id);
            }

            List<ContentGroup> filterGroups = new ArrayList<>();

            // 判断是否为无效折线对象
            if (chartType == ChartType.LINE_CHART) {
                for (ContentGroup group : groups) {
                    if (!isChartInvalidContentGroup(chart, group, chartType)) {
                        filterGroups.add(group);
                    }
                }
                // 再次过滤其他长XY直线对象当存在常规折线时
                List<ContentGroup> filterGroups2 = filterLineChartNoiceLine(chart, filterGroups);
                // 解析折线对象
                parserLineGroups(box, filterGroups2);
            }

            // 判断是否为无效柱状对象
            if (chartType == ChartType.BAR_CHART) {
                for (ContentGroup group : groups) {
                    if (!isChartInvalidContentGroup(chart, group, chartType)) {
                        filterGroups.add(group);
                    }
                }
                boolean isVertical = chart.type == ChartType.BAR_CHART;
                parserColumnGroups(box, filterGroups, isVertical);
            }
        } // endf for ilegend

        // 保存图形对象
        savePathInfosToChart(chart, chartType);
    }

    /**
     * 保存pathInfos到Chart中
     * @param chart
     * @param chartType
     */
    private void savePathInfosToChart(Chart chart, ChartType chartType) {
        if (!pathInfos.isEmpty()) {
            chart.pathInfos.clear();
            if (chartType == ChartType.BAR_CHART) {
                chart.barsInfos.clear();
            }
            chart.pathInfos.addAll(pathInfos);
            chart.legendMatchPath = true;
            pathInfos.clear();
        }
    }

    /**
     * 基于检测到的Chart区域　和　用新path组分类方法解析Chart对象
     */
    private void detectByPathClassify(List<List<ChartType>> chartTypes) {
        // 如果没有用TF模型进行检测 或是　没有检测出可能的Chart对象  则直接返回
        List<ContentGroup> allContentGroups = pageContentGroup.getAllContentGroups(true, true);
        resetShColor(allContentGroups);
        setDetectChartInfo(allContentGroups);
        int nDetectObj = detectObjStatus.size();
        if (nDetectObj == 0) {
            return;
        }

        File saveDir = new File("/media/myyang/data3/data/ChartPathsSave/pdf_0619");
        try {
            FileUtils.forceMkdir(saveDir);
        } catch (IOException e) {
            logger.error("Error while creating dir at " + saveDir, e);
            saveDir = null;
        }

        // 先取出 root 内部对象及其嵌套的子对象 顺序存储为 allContentGroups
        // 遍历检测到的所有可能的Chart对象　尝试用矢量的方法解析其内部内容
        for (int iObj = 0; iObj < nDetectObj; iObj++) {
            // 判断区域内ContentGroup对象集是否为空
            List<Integer> contentGroupIds = detectObjStatus.get(iObj);
            if (contentGroupIds.isEmpty()) {
                continue;
            }

            // 新建一个Chart对象
            DetectedChartTableTF obj = (DetectedChartTableTF) pageDetectedInfo.get(iObj);
            chart = new Chart();
            chart.parserDetectArea = true;
            chart.parserDetectModelArea = obj.byChartDetectModel;
            chart.parserHintArea = obj.byHintArea;
            chart.setArea(obj.area);
            chart.subTypes = chartTypes.get(iObj);
            chart.pageIndex = pageIndex;
            chart.setChartIndex(100 + iObj);
            chart.page = context.getDocument().getDocument().getPage(pageIndex);

            try {
                chart.setLowerLeftArea(pageContentGroup.getPageTransform().createInverse().createTransformedShape(obj.area).getBounds2D());
            } catch (NoninvertibleTransformException e) {
                e.printStackTrace();
            }

            // 遍历所有的数据块 构建Chart区域范围内的所有内容流数据
            int nGroups = contentGroupIds.size();
            for (int i = 0; i < nGroups; i++) {
                current = allContentGroups.get(contentGroupIds.get(i));
                if (i == 0) {
                    chartContentGroup = new ContentGroup(
                            current.getGraphicsState(), current.getPageTransform(), true);
                }
                chartContentGroup.add(current);
            } // end for i
            chartContentGroup.end();
            chart.contentGroup = chartContentGroup;

            // 将Chart内的图形数据分组
            ChartUtils.markChartPath(chart);

            // 计算所有图形组对象的JsonObject对象
            List<PathInfoData> pathInfoDatas = ChartUtils.buildChartPathClassifyInfoDatas(chart, saveDir, true);

            parserChartPathInfoDatas(chart, pathInfoDatas);

            // 过滤掉饼图对象内的无效图形对象
            filterPieInnerInValidPathInfos();

            // 检测坐标轴信息
            checkAxis();

            // 筛除和图例重复的Pathinfo
            filterIllegalPath();

            // 过滤掉网格线对象　避免干扰解析
            filterGridLinePath();

            // 判断Chart类型
            checkChartType();

            status = Status.BEGIN_AXIS_TEXT;

            chartEndTF(obj);
        } // end for iObj
    }

    /**
     * 基于TF模型检测的Chart对象　检测矢量Chart对象
     * 在一定程度放开　图形对象和文字对象出现的顺序关系
     * 目前假设
     * 1. 斜着刻度信息出现在刻度信息后
     * 2. 图例出现在刻度或斜着刻度后
     * 3. 对图例对象　先出现图例标识对象再出现图例文字
     * 4. 图例对象之后的其他图形对象为辅助性对象
     * @return
     */
    private List<Chart> detectByTFChartTable() {

        // 如果没有用TF模型进行检测 或是　没有检测出可能的Chart对象  则直接返回
        List<ContentGroup> allContentGroups = pageContentGroup.getAllContentGroups(true, true);
        resetShColor(allContentGroups);
        setDetectChartInfo(allContentGroups);
        int nDetectObj = detectObjStatus.size();
        if (nDetectObj == 0) {
            return charts;
        }

        // 先取出 root 内部对象及其嵌套的子对象 顺序存储为 allContentGroups
        // 遍历检测到的所有可能的Chart对象　尝试用矢量的方法解析其内部内容
        for (int iObj = 0; iObj < nDetectObj; iObj++) {
            // 判断区域内ContentGroup对象集是否为空
            List<Integer> contentGroupIds =  detectObjStatus.get(iObj);
            if (contentGroupIds.isEmpty()) {
                continue;
            }

            // 新建一个Chart对象
            DetectedChartTableTF obj = (DetectedChartTableTF) pageDetectedInfo.get(iObj);
            chart = new Chart();

            // 设置是由TF位图Chart检测方法检测出来的状态标识
            chart.parserDetectArea = true;
            chart.parserDetectModelArea = obj.byChartDetectModel;
            chart.parserHintArea = obj.byHintArea;
            chart.imageClassifyTypes = obj.imageTypes;
            chart.parserRecallArea = obj.byRecallArea;
            // 将检测到的区域赋值给 chart.area  由于检测区域存在一定误差
            // 后续可能需要根据内部图形或文字对象对区域进行微调　目前还没有想好策略
            chart.setArea(obj.area);
            double width = chart.getArea().getWidth();
            double height = chart.getArea().getHeight();
            // 统计处理过的ContentGroup的状态类型
            List<Status> statuses = new ArrayList<>();
            int nGroups = contentGroupIds.size();
            // 遍历所有的数据块
            for (int i = 0; i < nGroups; i++) {
                // 取出当前ContentGroup对象
                current = allContentGroups.get(contentGroupIds.get(i));
                // 如果是第一个对象　则初始化 chartContentGroup对象
                if (i == 0) {
                    chartContentGroup = new ContentGroup(
                            current.getGraphicsState(), current.getPageTransform(), true);
                }
                // 将当前内容 current　加入 chartContentGroup
                chartContentGroup.add(current);
                // 当前状态数目
                int nStatus = statuses.size();
                // 判断是否只有图形对象
                if (current.hasOnlyPathItems()) {
                    // 判断是否为圆形对象  处理处理圆形图例和散点对象(暂时不支持)
                    if (parserCirclePath(current)) {
                        continue;
                    }

                    // 计算 current 的区域
                    Rectangle2D box = current.getArea();
                    ContentGroup nextTextGroup = getNextNearTextGroup(
                            allContentGroups, contentGroupIds, i, true);
                    // 判断接下来是否有一个文字快对象　且第一个文字与图形对象近似同高度 即存在匹配的图例文字信息
                    if (nextTextGroup != null) {
                        // 取出接下来的文字对象集
                        List<TextChunk> texts = nextTextGroup.getAllTextChunks();
                        if (!isNotePath(current, nextTextGroup) && !texts.isEmpty()) {
                            // 取第一个文字对象
                            TextChunk text = texts.get(0);
                            // 判断空间关系
                            double dy = box.getCenterY() - text.getCenterY();
                            double dx = box.getMaxX() - text.getX();
                            if (Math.abs(dy) < 0.02 * height && Math.abs(dx) < 0.15 * width) {
                                if (detectLegendLine() ) {
                                    statuses.add(Status.BEGIN_LEGEND);
                                    continue;
                                }
                            }
                        }
                    } else if (nStatus >= 1 && !statuses.contains(Status.BEGIN_LEGEND)) {
                        Status beforeStatus = statuses.get(nStatus - 1);
                        // 如果前一个状态为文字对象　则接下来可能是斜着文字对象
                        if (beforeStatus == Status.BEGIN_AXIS_TEXT) {
                            // 取下一个ContentGroup对象
                            ContentGroup nextGroup = null;
                            if (i + 1 <= nGroups - 1) {
                                nextGroup = allContentGroups.get(contentGroupIds.get(i + 1));
                            }
                            // 斜着OCR 通常是连续出现多个　初步判断下一个 ContentGroup是否为只含有图形对象
                            if (nextGroup != null && nextGroup.hasOnlyPathItems()) {
                                Rectangle2D boxNext = nextGroup.getArea();
                                double dy1 = boxNext.getMinY() - box.getMinY();
                                double dy2 = boxNext.getMaxY() - box.getMaxY();
                                // 相邻两个斜着文字对象顶部或底部对齐
                                if (Math.abs(dy1) < 0.02 * height || Math.abs(dy2) < 0.02 * height ||
                                        isAdjoinRotatedText(current, nextGroup)) {
                                    // 判断是否为斜着的OCR　Text
                                    if (detectRotatedText()) {
                                        statuses.add(Status.BEGIN_OCR);
                                        continue;
                                    }
                                }
                            }
                        }
                        // 如果前一个ContentGroup为OCR对象，则继续判断是否为OCR对象
                        else if (beforeStatus == Status.BEGIN_OCR) {
                            if (detectRotatedText()) {
                                statuses.add(Status.BEGIN_OCR);
                                continue;
                            }
                            else if (i >= 1) {
                                ContentGroup beforeGroup = allContentGroups.get(contentGroupIds.get(i - 1));
                                Rectangle2D beforeBox = (Rectangle2D) beforeGroup.getArea().clone();
                                GraphicsUtil.extendRect(beforeBox, 0.03 * width);
                                if (GraphicsUtil.contains(beforeBox, box)) {
                                    continue;
                                }
                            }
                        }
                    }
                    // 如果不是图例或是斜着文字对象　则当普通图形对象来进行解析
                    if (statuses.contains(Status.BEGIN_LEGEND)) {
                        continue;
                    }
                    parserContentGroup(current);
                    statuses.add(Status.BEGIN_GRAPHICS);
                } else if (current.hasText()) {  // 判断为是否只有含有文字对象
                    if (detectChartText()) {
                        statuses.add(Status.BEGIN_AXIS_TEXT);
                    }
                } else if (current.hasImage()) {
                    // 有可能是水印, 图片画的点, 忽略
                } else {
                    // 设置颜色, 线宽等操作
                }
            } // end for i

            // 根据处理过的ContentGroup的类型　判断最终状态
            if (statuses.contains(Status.BEGIN_AXIS_TEXT) ||
                    statuses.contains(Status.BEGIN_LEGEND) ||
                    statuses.contains(Status.BEGIN_OCR)) {
                status = Status.BEGIN_AXIS_TEXT;
            }
            else if (statuses.contains(Status.BEGIN_GRAPHICS)) {
                status = Status.BEGIN_GRAPHICS;
            }
            else {
                status = Status.UNKNOWN;
            }

            // 过滤掉饼图对象内的无效图形对象
            filterPieInnerInValidPathInfos();

            // 找出非常规图例对象
            findUnconventionalityLegend();

            if (!chart.legends.isEmpty()) {
                statuses.add(Status.BEGIN_LEGEND);
            }

            // 检测坐标轴信息
            checkAxis();

            // 筛除和图例重复的Pathinfo
            filterIllegalPath();

            // 过滤掉网格线对象　避免干扰解析
            filterGridLinePath();

            // 判断Chart类型
            checkChartType();
            // 如果我们自己没识别出来，那么使用分类器的类别。
            if(chart.type == ChartType.UNKNOWN_CHART) {
                if (obj.types != null && obj.types.size() == 1) {
                    chart.type = obj.types.get(0);
                }
                else if (obj.types != null && obj.types.size() > 1 ){
                    chart.type = ChartType.COMBO_CHART;
                    chart.subTypes.addAll(obj.types);
                }
            }

            // 如果分类器识别为了柱状或者水平柱状，那么用分类器的识别结果
            if (obj.types != null && obj.types.size() == 1 && (obj.types.get(0) == ChartType.BAR_CHART || obj.types.get(0) == ChartType.COLUMN_CHART)) {
                chart.type = obj.types.get(0);
            }
            chartEndTF(obj);
        } // end for iObj

        //

        return charts;
    }

    /**
     * 过滤掉chart集中的满足table网格线特征的对象
     * @param tcharts
     */
    private void filterTableCharts(List<Chart> tcharts) {
        charts = tcharts;
        for (Chart chart : charts) {
            filterTableChart(chart);
        }

        //过滤掉类型为UNKNOWN_CHART的Chart
        removeTypeErrorCharts();
    }

    /**
     * 判断Chart是否具有有线表格的特征　如果是　则设置为UNKNOWN_CHART
     * @param chart
     */
    private void filterTableChart(Chart chart) {
        // 忽略特定类型
        ChartType type = chart.type;
        if (type == ChartType.BITMAP_CHART ||
                chart.isPPT ||
                type == ChartType.PIE_CHART ||
                type == ChartType.AREA_CHART ||
                type == ChartType.AREA_OVERLAP_CHART) {
            return;
        }

        // 如果有图例　则忽略
        if (chart.legends.size() >= 1) {
            return;
        }

        // 判断Chart内部折线对象是否构成有效网格线
        for (ChartPathInfo pathInfo : chart.pathInfos) {
            // 忽略曲线和虚线
            if (pathInfo.type == PathInfo.PathType.CURVE ||
                    pathInfo.type == PathInfo.PathType.DASH_LINE) {
                return;
            }
            if (pathInfo.type == PathInfo.PathType.AREA) {
                return;
            }
            if (pathInfo.type == PathInfo.PathType.LINE) {
                List<Line2D> glines = ChartPathInfosParser.isLineGridLine(pathInfo.path);
                if (glines == null) {
                    return;
                }
            }
        } // end for pathInfo

        // 遍历Chart区域内的ContentGroup对象　找出候选水平或垂直折线对象
        List<ContentGroup> groups = chart.contentGroup.getAllContentGroups(true);
        Rectangle2D box = chart.getArea();
        GraphicsUtil.extendRect(box, 1.5);
        List<Line2D> xyLines = new ArrayList<>();
        for (int i = 0; i < groups.size(); i++) {
            current = groups.get(i);
            List<Line2D> lines = findApproximationXYLine(current, box);
            xyLines.addAll(lines);
        }

        // 判断收集到的line2d对象　是否具有table的网格线特征
        if (ChartPathInfosParser.isTableGridLine(xyLines)) {
            chart.type = ChartType.UNKNOWN_CHART;
        }
    }

    /**
     * 过滤掉饼图内部的折线对象和坐标轴 避免干扰解析
     * 像小矩形对象这种保留 可能是图例的指示框对象
     */
    private void filterPieInnerInValidPathInfos() {
       if (ChartPathInfosParser.isPie(pies, arcs, tris)) {
           pathInfos.removeIf(pathInfo -> pathInfo.type == PathInfo.PathType.CURVE ||
                   pathInfo.type == PathInfo.PathType.LINE ||
                   pathInfo.type == PathInfo.PathType.DASH_LINE);
           hAxisLines.clear();
           vAxisLines.clear();
       }
    }

    /**
     * 判断给定包含PathItem的group 和相邻包含文字的textGroup之间的关系
     * 某些辅助性矩形框　内部有文字对象　　应该过滤掉这种对象避免干扰图例的解析
     * @param group
     * @param textGroup
     * @return
     */
    private boolean isNotePath(ContentGroup group, ContentGroup textGroup) {
        // 判断数据有效性
        if (group == null || textGroup == null || !textGroup.hasText()) {
            return false;
        }
        Rectangle2D box = group.getArea();
        if (box.isEmpty()) {
            return false;
        }
        // 适当扩张顶部和底部
        GraphicsUtil.extendRect(box, -0.1f, 0.0f, -0.1f, 0.0f);
        // 取出接下来的文字对象集
        List<TextChunk> chunks = textGroup.getAllTextChunks();
        for (TextChunk chunk : chunks) {
            // 取第一个文字对象
            String text = chunk.getText();
            text = text.trim();
            if (StringUtils.isEmpty(text)) {
                continue;
            }
            if (box.contains(chunk.getCenterX(), chunk.getCenterY())) {
                return true;
            }
            else {
                return false;
            }
        } // end for chunk
        return false;
    }

    private boolean isValidLegendPath(ChartPathInfo barInfo, ChartPathInfo barInfoB) {
        if (barInfo.color.equals(barInfoB.color)) {
            return false;
        }
        if (GraphicsUtil.nearlyOverlap(barInfo.path.getBounds2D(), barInfoB.path.getBounds2D(), 0.01)) {
            return false;
        }
        return true;
    }

    /**
     * 找出非常规图例指示框指示线指示圆对象   在GIC项目碰到了
     * 常规图例指示对象右侧紧邻文字对象 且出现在图形对象和刻度信息之后
     */
    private void findUnconventionalityLegend() {
        // 待大量柱状对象或是其他信息解析出来后　可以从中筛选出有效的图例对象
        findLegendFromBarInfos();

        // 待大量折线对象或是其他信息解析出来后　可以从中筛选出有效的图例对象
        findLegendFromLineInfos();

        // 从候选圆形图例对象集中帅选有效的图例对象
        findLegendFromCircleInfos();
    }

    /**
     * 某些图例对象为圆形对象  和常出现的指示框和指示线对象不同  需要特殊处理
     */
    private void findLegendFromCircleInfos() {
        // 如果有图例对象或圆形对象集为空　则直接返回
        int n = circles.size();
        if (n == 0 || !chart.legends.isEmpty()) {
            return;
        }

        // 判断是否是同半径圆形图例对象  通常圆形图例对象半径都是一样大
        double radius = circles.get(0).r;
        if (!circles.stream().allMatch(circle -> Math.abs(circle.r - radius) < 0.1)) {
            return;
        }

        // 尝试匹配图例和扇形快的颜色  目前只支持一个饼图对象的匹配  后续需要改进
        if (pies.size() == 1) {
            // 取出扇形块颜色集
            List<ChartPathInfosParser.ArcObject> pie = pies.get(0);
            List<Color> pclors = new ArrayList<>();
            pie.stream().forEach(arc -> {
                if (!pclors.contains(arc.color)) {
                    pclors.add(arc.color);
                }
            });
            // 取出候选圆形图例颜色集
            List<Color> lclors = new ArrayList<>();
            circles.stream().forEach(circle -> {
                if (!lclors.contains(circle.color)) {
                    lclors.add(circle.color);
                }
            });
            // 匹配最佳颜色
            ChartPathInfosParser.matchPathLegendColor(pclors, lclors);
            if (circles.size() == lclors.size()) {
                for (int i = 0; i < circles.size(); i++) {
                    ChartPathInfosParser.ArcObject circle = circles.get(i);
                    circle.color = lclors.get(i);
                }
            }
        }

        double width = chart.getArea().getWidth();
        List<TextChunk> chunks = ChartUtils.mergeTextChunk(chart, chartContentGroup);
        for (int i = 0; i < n; i++) {
            ChartPathInfosParser.ArcObject circle = circles.get(i);
            // 判断填充颜色是否出现过
            if (!fillColors.contains(circle.color)) {
                continue;
            }
            Rectangle2D box = circle.path.getBounds2D();
            // 在空间上有水平且相邻的文字对象
            for (TextChunk chunk : chunks) {
                boolean sameY = Math.abs(chunk.getCenterY() - box.getCenterY()) < chunk.getAvgCharHeight();
                boolean nearX = Math.abs(chunk.getMinX() - box.getMaxX()) < 0.03 * width;
                if (sameY && nearX) {
                    addLegend(circle.color, box);
                    break;
                }
            }
        } // end dof i
    }

    /**
     * 某些图例对象顺序在其对应图形对象前面 此时被当成线对象来处理
     * 保存在pathInfos中  可以从中帅选出有效的图例对象
     */
    private void findLegendFromLineInfos() {
        // 如果有图例对象　则直接返回
        if (!chart.legends.isEmpty()) {
            return;
        }

        double width = chart.getArea().getWidth();
        int n = pathInfos.size();
        List<Double> xs = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
        List<TextChunk> chunks = ChartUtils.mergeTextChunk(chart, chartContentGroup);
        for (int i = 0; i < n; i++) {
            ChartPathInfo chartPathInfo = pathInfos.get(i);
            if (chartPathInfo.type == PathInfo.PathType.LINE) {
                // 只处理 * * m  * * l的情况
                ChartContentScaleParser.getLinePathPoints(chartPathInfo.path, null, xs, ys);
                if (xs.size() > 2) {
                    continue;
                }
                Rectangle2D box = chartPathInfo.path.getBounds2D();
                double w = box.getWidth();
                double h = box.getHeight();
                // 忽略非水平方向的折线对象
                if (!ChartUtils.equals(h, 0.0) || w / width >= 0.1) {
                    continue;
                }

                // 在pathInfos中存在同颜色的线对象
                boolean matchLine = pathInfos.stream().anyMatch(pathInfo -> {
                   if (pathInfo != chartPathInfo && pathInfo.color.equals(chartPathInfo.color)) {
                       return true;
                   }
                   else {
                       return false;
                   }
                });
                if (!matchLine) {
                    continue;
                }
                // 在空间上有水平且相邻的文字对象
                for (TextChunk chunk : chunks) {
                    boolean sameY = Math.abs(chunk.getCenterY() - box.getCenterY()) < chunk.getAvgCharHeight();
                    boolean nearX = Math.abs(chunk.getMinX() - box.getMaxX()) < 0.03 * width;
                    if (sameY && nearX) {
                        addLegend(chartPathInfo.color, box);
                        chartPathInfo.type = PathInfo.PathType.UNKNOWN;
                        break;
                    }
                }
            } // end if
        } // end for i
        pathInfos.removeIf(pathInfo -> pathInfo.type == PathInfo.PathType.UNKNOWN);
    }

    /**
     * 某些图例对象尺寸过小或是与图例文字对象顺序杂乱时
     * 解析柱状对象时，保存至chart.barInfos中　
     * 待大量柱状对象或是其他信息解析出来后　可以从中筛选出有效的图例对象
     */
    private void findLegendFromBarInfos() {
        // 如果有图例对象　则直接返回
        if (!chart.legends.isEmpty()) {
            return;
        }

        // 如果是饼图对象　则出现的矩形对象　大概率为图例对象
        List<ChartPathInfo> barInfos = new ArrayList<>();
        if (!pies.isEmpty()) {
            pathInfos.stream().forEach(chartPathInfo -> {
                if (chartPathInfo.type == PathInfo.PathType.COLUMNAR) {
                    barInfos.add(chartPathInfo);
                }
            });
        }
        // 取出保存的小尺寸矩形对象
        chart.barsInfos.stream().forEach(chartPathInfo -> barInfos.add(chartPathInfo));
        int n = barInfos.size();
        List<TextChunk> chunks = ChartUtils.mergeTextChunk(chart, chartContentGroup);
        for (int i = 0; i < n; i++) {
            ChartPathInfo barInfo = barInfos.get(i);
            Rectangle2D box = barInfo.path.getBounds2D();
            // 判断相邻 barInfo　是否同颜色或是相交
            if (i + 1 <= n - 1) {
                ChartPathInfo barInfoB = barInfos.get(i + 1);
                if (!isValidLegendPath(barInfo, barInfoB)) {
                    continue;
                }
            }
            if (i - 1 >= 0) {
                ChartPathInfo barInfoB = barInfos.get(i - 1);
                if (!isValidLegendPath(barInfo, barInfoB)) {
                    continue;
                }
            }
            Rectangle2D legendArea = getLegendArea(barInfo.path);
            boolean isLegend = ChartPathInfosParser.isLegendIdentificationInChart(barInfo.path, chart);
            if (!isLegend || legendArea == null) {
                continue;
            }

            double x = box.getMaxX();
            double y = box.getCenterY();
            TextChunk legendChunk = null;
            double distMin = Double.MAX_VALUE;
            for (TextChunk chunk : chunks) {
                if (chunk.getDirection() != TextDirection.LTR) {
                    continue;
                }
                double cx = chunk.getMinX();
                double cy = chunk.getCenterY();
                if (cx < x || Math.abs(x - cx) >= 30) {
                    continue;
                }
                if (Math.abs(y - cy) >= 20) {
                    continue;
                }
                double dist = Math.sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy));
                if (dist > 30) {
                    continue;
                }
                if (dist < distMin) {
                    distMin = dist;
                    legendChunk = chunk;
                }
            } // end for id
            if (legendChunk != null) {
                addLegend(barInfo.color, box);
                pathInfos.remove(barInfo);
                chart.barsInfos.remove(barInfo);
            }
        } // end for i
        if (pathInfos.isEmpty()) {
            paths.clear();
        }
        //System.out.println(chart.legends.size());
    }

    /**
     * 过滤掉网格线对象　避免干扰解析
     */
    private void filterGridLinePath() {
        if (chart.lvAxis == null && chart.rvAxis == null) {
            return;
        }
        Line2D axis = chart.lvAxis != null ? chart.lvAxis : chart.rvAxis;
        double ymin = Math.min(axis.getY1(), axis.getY2());
        double ymax = Math.max(axis.getY1(), axis.getY2());
        Iterator<ChartPathInfo> iter = pathInfos.iterator();
        List<Double> xs = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
        while (iter.hasNext()){
            ChartPathInfo currentPathInfo = iter.next();
            if (currentPathInfo.type != PathInfo.PathType.COLUMNAR) {
                continue;
            }
            ChartPathInfosParser.getPathColumnBox(currentPathInfo.path, xs, ys);
            if (xs.size() == 0) {
                continue;
            }

            boolean isGrid = true;
            for (int i = 0; i < ys.size()/2; i++) {
                if (Math.abs(xs.get(2 * i + 1) - xs.get(2 * i)) > 2.0) {
                    isGrid = false;
                    break;
                }
                if (Math.abs(ys.get(2 * i) - ymin) > 1.0f ||
                        Math.abs(ys.get(2 * i + 1) - ymax) > 1.0f) {
                    isGrid = false;
                    break;
                }
            }
            if (isGrid) {
                iter.remove();
            }
        } // end while
    }

    private void filterIllegalPath(){
        Iterator<ChartPathInfo> iter = pathInfos.iterator();
        Map<Color, Double> colorSizes = new HashMap();

        while (iter.hasNext()) {
            ChartPathInfo currentPathInfo = iter.next();
            Rectangle2D box = currentPathInfo.path.getBounds2D();

            // 累计计算同色区域的面积 目前只需要计算COLUMNAR类型的柱状
            if (currentPathInfo.type == PathInfo.PathType.COLUMNAR) {
                double size = box.getWidth() * box.getHeight();

                if(colorSizes.isEmpty()){
                    colorSizes.put(currentPathInfo.color, size);
                } else {
                    boolean noMatchingColor = colorSizes.keySet().stream().allMatch(color -> getRGBdiff(color, currentPathInfo.color) > 3);
                    if (noMatchingColor) {
                        colorSizes.put(currentPathInfo.color, size);
                    } else {
                        // found similar color exist
                        colorSizes.forEach((color, aDouble) -> {
                            if (getRGBdiff(color, currentPathInfo.color) <= 3) {
                                double newSizeSum = colorSizes.get(color) + size;
                                colorSizes.replace(color, newSizeSum);
                            }
                        });
                    }
                }
            }

            // filter legends' surrounding box
            boolean matchColor = chart.legends.stream().anyMatch(legend -> legend.color.equals(currentPathInfo.color));
            if (matchColor) {
                continue;
            }

            Iterator<Chart.Legend> legendIterator = chart.legends.iterator();
            while(legendIterator.hasNext()){
                Chart.Legend legend = legendIterator.next();
                if(GraphicsUtil.contains(box, legend.line)) {
                    iter.remove();
                    break;
                }
            } // end legendIterator

        } // end PathInfoIterator


        double chartSize = chart.getWidth() * chart.getHeight();
        colorSizes.forEach((k, v) -> {
            if ((colorSizes.get(k) / chartSize) > 1) {
                removePathByColor(k);
            }
        });
    }


    /**
     * 计算RGB三个数 线性差值
     * @return rgb三个数的差值之和
     */
    private int getRGBdiff(Color c1, Color c2){
        return Math.abs(c1.getRed() - c2.getRed()) + Math.abs(c1.getGreen() - c2.getGreen()) + Math.abs(c1.getBlue() - c2.getBlue());
    }


    /**
     * 从PathInfo中删除含有该颜色（或者颜色特别相近 阈值=3）的Path
     *
     */
    private void removePathByColor(Color c){
        Iterator<ChartPathInfo> iter = pathInfos.iterator();
        while (iter.hasNext()) {
            ChartPathInfo currentPathInfo = iter.next();
            if(getRGBdiff(currentPathInfo.color, c) <= 3) {
                iter.remove();
            }
        }
    }
    

    // 获取下一个满足条件的ContentGroup
    private ContentGroup getNextNearTextGroup(
            List<ContentGroup> allContentGroups, List<Integer> contentGroupIds,
            int idCurrent, boolean isTextType) {
        ContentGroup nextGroup = null;
        int nGroups = contentGroupIds.size();
        int idNext = idCurrent + 1;
        Rectangle2D box = allContentGroups.get(contentGroupIds.get(idCurrent)).getArea();
        GraphicsUtil.extendRect(box, 5.0);
        while (idNext <= nGroups - 1) {
            nextGroup = allContentGroups.get(contentGroupIds.get(idNext));
            if (isTextType) {
                if (nextGroup != null) {
                    Rectangle2D nextBox = nextGroup.getArea();
                    GraphicsUtil.extendRect(nextBox, 3.0);
                    if (nextGroup.hasOnlyPathItems() && !box.intersects(nextBox)) {
                        return null;
                    }
                    if (nextGroup.hasText()) {
                        return nextGroup;
                    }
                }
                idNext += 1;
            }
            else {
                return nextGroup;
            }
        } // end while
        return null;
    }

    /**
     * 合并两种方法解析出来的结果
     * @return
     */
    private List<Chart> mergeParserCharts() {
        int n = charts.size();
        if (n == 0) {
            onPageEnd();
            return charts;
        }

        // 计算有效区域和最后一个基于检测模型方法解析出来的Chart的序号
        List<Rectangle2D> chartAreas = new ArrayList<>();
        List<Boolean> chartSelectStatus = new ArrayList<>();
        int lastTFChart = -1;
        for (Chart chart : charts) {
            if (chart.parserDetectArea) {
                lastTFChart++;
            }
            Rectangle2D box = ChartPathInfosParser.getChartGraphicsMergeBox(chart);
            if (box == null) {
                chartAreas.add(chart.getArea());
            }
            else {
                Rectangle2D.intersect(box, chart.getArea(), box);
                chartAreas.add(box);
            }
            chartSelectStatus.add(true);
        }
        // 如果charts 都是一种方法解析出来的　则直接原样返回
        if (lastTFChart == -1 || lastTFChart == n - 1) {
            onPageEnd();
            return charts;
        }

        // 筛除过大的且包含两个位图检测出Chart区域的误检矢量Chart区域对象
        int count = 0;
        Rectangle2D boxVector = null, boxBitmap = null;
        for (int k = lastTFChart + 1; k < n; k++) {
            if (!chartSelectStatus.get(k)) {
                continue;
            }
            boxVector = chartAreas.get(k);
            count = 0;
            for (int i = 0; i <= lastTFChart; i++) {
                boxBitmap = chartAreas.get(i);
                if (chartSelectStatus.get(i) && areaOverlapValid(boxVector, boxBitmap, 0.6)) {
                    count++;
                }
                if (count > 1) {
                    chartSelectStatus.set(k, false);
                    break;
                }
            }
        } // end for i

        // 筛除过大的且包含两个矢量Chart区域的位图检误检Chart区域对象
        for (int i = 0; i <= lastTFChart; i++) {
            if (!chartSelectStatus.get(i)) {
                continue;
            }
            boxBitmap = chartAreas.get(i);
            count = 0;
            for (int k = lastTFChart + 1; k < n; k++) {
                boxVector = chartAreas.get(k);
                if (chartSelectStatus.get(k) && areaOverlapValid(boxBitmap, boxVector, 0.8)) {
                    count++;
                }
                if (count > 1) {
                    chartSelectStatus.set(i, false);
                    break;
                }
            }
        } // end for k

        // 遍历所有的基于检测模型解析出来的Chart对象
        List<Chart> mergeCharts = new ArrayList<>();
        for (int i = 0; i <= lastTFChart; i++) {
            if (!chartSelectStatus.get(i)) {
                continue;
            }
            Chart chartA = charts.get(i);
            Rectangle2D boxA = chartAreas.get(i);
            boolean isBetterChart = true;
            // 遍历常规矢量解析方法解析出来的Chart对象
            for (int j = lastTFChart + 1; j < n; j++) {
                Chart chartB = charts.get(j);
                Rectangle2D boxB = chartAreas.get(j);
                // 比较不同解析方法解析出来Chart有效区域的重叠情况 如果重叠区域占据检测区域40%及以上
                // 则判断两个Chart对象内部图例　扇形对象的数目关系
                // 目前假设内容越多代表大概率正确
                if (chartSelectStatus.get(j) && areaOverlapValid(boxB, boxA, 0.4)) {
                    if (chartA.legends.size() <= chartB.legends.size()) {
                        isBetterChart = false;
                    }
                    if (chartA.type == ChartType.PIE_CHART && chartB.type == ChartType.PIE_CHART) {
                        if (chartA.pieInfo.parts.size() <= chartB.pieInfo.parts.size()) {
                            isBetterChart = false;
                        }
                    }
                    if (isBetterChart) {
                        chartSelectStatus.set(j, false);
                    }
                    break;
                }
            } // end for j
            chartSelectStatus.set(i, isBetterChart);
        } // end for i
        // 筛选大概率正确的Chart对象
        for (int i = 0; i < n; i++) {
            if (chartSelectStatus.get(i)) {
                mergeCharts.add(charts.get(i));
            }
        }

        // 只对合并后的Chart对象进行　内容解析
        charts.clear();
        charts = mergeCharts;
        onPageEnd();
        return charts;
    }

    /**
     * 对给定Chart候选区域 用基于path组分类方法进行解析
     */
    private List<Chart> detectBasePathClassify(List<DetectedObjectTF> detectedInfo) {
        // 清空解析状态
        charts.clear();
        resetStatus();

        if (detectedInfo == null || detectedInfo.isEmpty()) {
            return charts;
        }
        List<List<ChartType>> chartTypes = new ArrayList<>();
        for (DetectedObjectTF objectTF : detectedInfo) {
            DetectedChartTableTF obj = (DetectedChartTableTF) objectTF;
            if (obj.types == null || !obj.isChart()) {
                return charts;
            }
            chartTypes.add(obj.types);
        }

        // 在伪检测区域内部用新的解析方法
        setPageDetectedInfo(detectedInfo);

        // 用基于path组分类方法解析Chart
        detectByPathClassify(chartTypes);

        // 解析Chart内容 并 关闭解析图片对象功能
        onPageEnd();

        return charts;
    }

    /**
     * 对给定Chart候选区域 用基于path组分类方法进行解析
     */
    private List<Chart> detectImproveByPathClassify(List<Chart> chartsMerge) {
        List<Integer> ids = new ArrayList<>();
        List<Chart> chartsNew = new ArrayList<>();
        chartsNew.addAll(chartsMerge);

        // 清空解析状态
        charts.clear();
        resetStatus();

        // 将Chart区域收集起来  构建伪位图检测区域
        // 这样就需要打开位图检测功能也能用上新的Chart解析方法
        List<DetectedObjectTF> detectedInfo = new ArrayList<>();
        List<List<ChartType>> chartTypes = new ArrayList<>();
        List<Rectangle2D> areas = new ArrayList<>();
        for (int i = 0; i < chartsNew.size(); i++) {
            Chart chartA = chartsNew.get(i);
            ChartType type = chartA.type;
            if (type == ChartType.UNKNOWN_CHART || type == ChartType.BITMAP_CHART) {
                continue;
            }
            // 考虑常规Chart且confidence低的对象
            /*
            if (chartA.confidence > 0.99) {
                continue;
            }
            */

            ids.add(i);
            DetectedChartTableTF tBox = new DetectedChartTableTF();
            tBox.setArea(chartA.getArea());
            detectedInfo.add(tBox);
            chartTypes.add(ChartUtils.getChartTypes(chartA));
            areas.add(chartA.getArea());
        } // end for i

        if (ids.isEmpty()) {
            return chartsNew;
        }

        // 调用位图Chart分类服务 得到类型信息
        List<List<ChartType>> chartClassifyTypes =
                ChartClassify.pageChartsClassifyInfos(context.getNativeDocument(), pageIndex, areas);

        // 在伪检测区域内部用新的解析方法
        setPageDetectedInfo(detectedInfo);

        // 在位图Chart分类类型和规则方法得到的类型之间做个选择  规则方法得到的区域和类型信息是保底方案
        List<List<ChartType>> types = null;
        // 如果调用位图Chart分类服务得到的结果无效
        if (chartClassifyTypes == null || chartClassifyTypes.isEmpty()) {
            types = chartTypes;
        }
        else if (chartClassifyTypes.stream().anyMatch(ctypes -> ctypes == null)) {
            types = chartTypes;
        }
        // 如果调用位图Chart分类服务得到的结果数目不一致
        else if (chartTypes.size() != chartClassifyTypes.size()) {
            types = chartTypes;
        }
        else {
            types = chartClassifyTypes;
        }

        // 用基于path组分类方法解析Chart
        detectByPathClassify(types);

        // 如果完全没有解析出Chart数目不匹配 直接返回
        if (charts.size() == 0) {
            //return chartsNew;
            return charts;
        }

        // 解析Chart内容 并 关闭解析图片对象功能
        boolean checkBitmapStatus = checkBitmap;
        checkBitmap = false;
        setPageDetectedInfo(null);
        onPageEnd();
        checkBitmap = checkBitmapStatus;

        // 测试效果
        /*
        for (int j = 0; j < charts.size(); j++) {
            Chart chartB = charts.get(j);
            chartsNew.add(chartB);
        }
        */

        // 取Chart.confidence较高的
        Set<Integer> compareStatus = new HashSet<>();
        for (int i = 0; i < ids.size(); i++) {
            int id = ids.get(i);
            Chart chartA = chartsNew.get(id);
            for (int j = 0; j < charts.size(); j++) {
                if (compareStatus.contains(j)) {
                    continue;
                }
                Chart chartB = charts.get(j);
                // 判断位置是否高度重叠
                if (!GraphicsUtil.nearlyContains(chartA.getArea(), chartB.getArea(), 20.0)) {
                    continue;
                }
                compareStatus.add(j);
                chartsNew.set(id, chartB);
                break;
                /*
                if (ChartUtils.betterChart(chartB, chartA)) {
                    System.out.println("improve chart ");
                    System.out.println(chartB.title);
                    chartsNew.set(id, chartB);
                    break;
                }
                */
            } // end for j
        } // end for i
        return chartsNew;
    }

    /**
     * 对解析出的Chart 根据计算的置信度  对小confidence再次进行解析
     */
    private List<Chart> detectImprove(List<Chart> chartsMerge) {
        List<Integer> ids = new ArrayList<>();
        List<Chart> chartsNew = new ArrayList<>();
        for (int i = 0; i < chartsMerge.size(); i++) {
            Chart chart = chartsMerge.get(i);
            chartsNew.add(chart);
            // 如果打开了基于位图检测功能  则直接返回
            if (chart.parserDetectArea) {
                return chartsMerge;
            }
            // 考虑常规Chart且confidence低的对象
            if (chart.type != ChartType.BITMAP_CHART &&
                    !chart.isPPT) {
                if (chart.confidence < 0.95) {
                    ids.add(i);
                }
            }
        }
        // 如果不存在低confidence的Chart对象
        if (ids.isEmpty()) {
            return chartsNew;
        }

        // 将Chart区域收集起来  构建伪位图检测区域
        // 这样就需要打开位图检测功能也能用上新的Chart解析方法
        List<DetectedObjectTF> detectedInfo = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            int id = ids.get(i);
            Chart chartA = chartsNew.get(id);
            DetectedChartTableTF tBox = new DetectedChartTableTF();
            tBox.setArea(chartA.getArea());
            detectedInfo.add(tBox);
        }

        // 清空解析状态
        charts.clear();
        resetStatus();

        // 在伪检测区域内部用新的解析方法
        setPageDetectedInfo(detectedInfo);
        detectByTFChartTable();

        // 如果解析出Chart数目不匹配  暂时直接返回  后续可以优化改进
        if (charts.size() != ids.size()) {
            return chartsNew;
        }

        // 解析Chart内容 并 关闭解析图片对象功能
        boolean checkBitmapStatus = checkBitmap;
        checkBitmap = false;
        setPageDetectedInfo(null);
        onPageEnd();
        checkBitmap = checkBitmapStatus;

        // 如果解析出Chart数目不匹配  暂时直接返回  后续可以优化改进
        if (charts.size() != ids.size()) {
            return chartsNew;
        }

        // 取Chart.confidence较高的
        for (int i = 0; i < ids.size(); i++) {
            int id = ids.get(i);
            Chart chartA = chartsNew.get(id);
            Chart chartB = charts.get(i);
            if (ChartUtils.betterChart(chartB, chartA)) {
                chartsNew.set(id, chartB);
            }
            /*
            if (chartA.confidence < chartB.confidence) {
                //System.out.println("improve chart");
                chartsNew.set(id, chartB);
            }
            */
        }
        return chartsNew;
    }

    /**
     * 用常规纯矢量解析方法  解析PDF中的Chart对象
     * @return
     */
    private List<Chart> detectConventional() {
        // 设置 ContentGroup 内部对象的类型 且 设置Form对象内部元素的类型
        List<ContentGroup> groups = pageContentGroup.getAllContentGroups(true, true);
        resetShColor(groups);
        setContentGroupType(groups, true);
        List<Chart> chartsDetectedInForm = detect(groups, true);
        //if (chartsDetected.isEmpty())
        {
            // 如果没有 Form 对象 且没有解析出 Chart 则直接返回
            /*if (groups.stream().noneMatch(g -> getContentGroupType(g) == ContentGroupType.FORM_BEGIN_TYPE)) {
                return chartsDetected;
            }*/

            resetStatus();
            //charts.clear();
            // 设置 ContentGroup 内部对象的类型
            setContentGroupType(groups, true);
            List<Chart> chartsDetectedOutofForm = detect(groups, false);
            //chartsDetectedInForm.addAll(chartsDetectedOutofForm);
            return chartsDetectedOutofForm;
        } /*else {
            return chartsDetected;
        }*/
    }

    /**
     * 判断给定contentgroup是否在已解析出的charts中  用来跳过已识别区域用
     * @param group
     * @return
     */
    private boolean isContentGroupInCharts(ContentGroup group) {
        // 判断是否在当前解析出的Charts中
        boolean inChart = false;
        Rectangle2D box = group.getArea();
        if (charts != null && box != null) {
            for (Chart chart : charts) {
                Point2D cpt = new Point2D.Double(box.getCenterX(), box.getCenterY());
                if (chart.contains(cpt)) {
                    inChart = true;
                    break;
                }
            }
        }
        return inChart;
    }

    /**
     * 判断给定box是否包含某个Chart对象
     * @param box
     * @return
     */
    private boolean pathBoxContainChart(Rectangle2D box) {
        if (charts != null) {
            for (Chart chart : charts) {
                Point2D pt = chart.getArea().getCenter();
                if (box.contains(pt)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 收集所有的只包含Path对象的ContentGroup内部pathitem包围框区域集
     * @return
     */
    private List<PathBox> collectPathBoxes(List<ContentGroup> allContentGroups) {
        List<Double> widthHeight = getPageWidthHeight();
        double width = widthHeight.get(0);
        double height = widthHeight.get(1);
        double area = width * height;
        List<PathBox> pathBoxs = new ArrayList<>();
        for (int id = 0; id < allContentGroups.size(); id++) {
            ContentGroup group = allContentGroups.get(id);

            // 判断是否在当前解析出的Charts中
            if (isContentGroupInCharts(group)) {
                continue;
            }

            if (!group.hasOnlyPathItems()) {
                // 处理位图对象
                if (group.hasImage()) {
                    // 计算尺寸
                    Rectangle2D imageBox = group.getArea();
                    double w = imageBox.getWidth();
                    double h = imageBox.getHeight();
                    // 判断尺寸是否合适
                    if (0.08 * width < w && w < 0.65 * width &&
                            0.05 * height < h && h < 0.3 * height) {
                        // 如果长宽比合适  则保存
                        if (0.25 < w/h && w/h < 4) {
                            PathBox pathBox = new PathBox(id, imageBox);
                            pathBoxs.add(pathBox);
                            continue;
                        }
                    }
                }
                continue;
            }
            ContentItem item = group.getAllItems().get(0);
            PathItem pathItem = (PathItem)item;
            if (isWhite(pathItem.getRGBColor())) {
                continue;
            }

            // 适当微扩区域
            Rectangle2D box = group.getArea();
            GraphicsUtil.extendRect(box, 1);
            Rectangle2D cbox = group.getClipArea();
            GraphicsUtil.extendRect(cbox, 1);
            Rectangle2D itemArea = pathItem.getItem().getBounds2D();
            GraphicsUtil.extendRect(itemArea, 1);

            // 判断是不是背景填充区域对象
            if (pathItem.isFill()) {
                // 初步判断是否包含矩形对象 并 取出内部点 做进一步的判断
                List<Double> xs = new ArrayList<>();
                List<Double> ys = new ArrayList<>();
                if (ChartPathInfosParser.judgePathContainColumnAndGetPts(pathItem.getItem(), xs, ys)) {
                    if (xs.size() == 4) {
                        // 去掉面积过大的
                        double w = itemArea.getWidth();
                        double h = itemArea.getHeight();
                        if (h * w > 0.05 * area) {
                            continue;
                        }
                    }
                }
            }

            // 计算交集区域
            Rectangle2D.intersect(itemArea, box, itemArea);
            Rectangle2D.intersect(itemArea, cbox, cbox);

            // 过滤掉长直线对象
            double h = itemArea.getHeight();
            double w = itemArea.getWidth();
            /*
            if (h < 0.005 * height && w > 0.86 * width) {
                continue;
            }
            */

            // 过来掉面积过大的对象
            if (h * w > 0.6 * height * width) {
                continue;
            }

            PathBox pathBox = new PathBox(id, itemArea);
            pathBoxs.add(pathBox);
        } // end for group
        return pathBoxs;
    }


    /**
     * 过滤掉接近水平或垂直直线对象
     * @param pathBoxes
     */
    private void filterXYLinePath(List<PathBox> pathBoxes) {
        if (pathBoxes == null || pathBoxes.isEmpty()) {
            return;
        }

        List<Double> widthHeight = getPageWidthHeight();
        double width = widthHeight.get(0);
        double height = widthHeight.get(1);
        double area = width * height;
        final Iterator<PathBox> each = pathBoxes.iterator();
        while (each.hasNext()) {
            PathBox pathBox = each.next();
            double w = pathBox.box.getWidth();
            double h = pathBox.box.getHeight();
            // 去掉面积过小的
            int count = PathBox.countIntersectOtherBox(pathBoxes, pathBox);
            if (h * w < 0.005 * area || h * w > 0.99 * area) {
                if (count == 0) {
                    each.remove();
                    continue;
                }
            }
        } // end while
    }

    /**
     * 逻辑相邻合并
     * @param pathBoxes
     * @param allContentGroups
     * @return
     */
    private boolean mergePathBox(List<PathBox> pathBoxes, List<ContentGroup> allContentGroups) {
        List<Double> widthHeight = getPageWidthHeight();
        double width = widthHeight.get(0);
        double height = widthHeight.get(1);
        double area = width * height;
        int n = pathBoxes.size();
        final Iterator<PathBox> each = pathBoxes.iterator();
        PathBox pathBoxBefore = null;
        while (each.hasNext()) {
            PathBox pathBox = each.next();
            if (pathBoxBefore != null) {
                if (pathBoxBefore.intersect(pathBox)) {
                    Rectangle2D boxB = (Rectangle2D) pathBoxBefore.box.clone();
                    Rectangle2D boxC = (Rectangle2D) pathBox.box.clone();
                    Rectangle2D.union(boxB, boxC, boxB);
                    if (pathBoxContainChart(boxB)) {
                        pathBoxBefore = pathBox;
                        continue;
                    }
                    // 如果合并后区域过大 则不合并 防止合并成大部分的page区域
                    if (boxB.getHeight() * boxB.getWidth() >= 0.6 * area) {
                        pathBoxBefore = pathBox;
                        continue;
                    }
                    pathBoxBefore.merge(pathBox);
                    each.remove();
                    // 过滤掉长直线对象
                    double h = pathBoxBefore.box.getHeight();
                    double w = pathBoxBefore.box.getWidth();
                    if (h < 0.005 * height && w > 0.86 * width) {
                        int count = PathBox.countIntersectOtherBox(pathBoxes, pathBoxBefore);
                        if (count == 0) {
                            pathBoxBefore.status = -1;
                            pathBoxBefore = null;
                        }
                    }
                    continue;
                }
                else {
                    int nIDB = pathBoxBefore.items.size();
                    int idB = pathBoxBefore.items.get(nIDB - 1);
                    int nIDC = pathBox.items.size();
                    int idC = pathBox.items.get(nIDC - 1);
                    ContentGroup groupB = allContentGroups.get(idB);
                    ContentGroup groupC = allContentGroups.get(idC);
                    boolean isPathGroup = groupB.hasOnlyPathItems() && groupC.hasOnlyPathItems();
                    if (isPathGroup && ChartUtils.isSimilarContentGroup(groupB, groupC)) {
                        Rectangle2D boxB = groupB.getArea();
                        Rectangle2D.intersect(boxB, pathBoxBefore.box, boxB);
                        Rectangle2D boxC = groupC.getArea();
                        Rectangle2D.intersect(boxC, pathBox.box, boxC);
                        double lenB = Math.min(boxB.getWidth(), boxB.getHeight());
                        double lenC = Math.min(boxC.getWidth(), boxC.getHeight());
                        // 稍微扩展一点 最大幅度为30　　经验性参数
                        double len = Math.max(lenB, lenC);
                        len = Math.max(len, 30);
                        GraphicsUtil.extendRect(boxB, len);
                        GraphicsUtil.extendRect(boxC, len);
                        if (boxB.intersects(boxC)) {
                            pathBoxBefore.merge(pathBox);
                            each.remove();
                            continue;
                        }
                    }
                }
            }

            pathBoxBefore = pathBox;
            if (pathBoxBefore.getArea() >= 0.7 * area) {
                pathBoxBefore.status = -1;
                pathBoxBefore = null;
            }
        } // end while
        // 过滤掉无效pathBox对象
        filterInValidPathBoxes(pathBoxes);
        return pathBoxes.size() < n;
    }

    /**
     * 空间相邻合并
     * @param pathBoxes
     * @return
     */
    private boolean mergeSpacePathBox(List<PathBox> pathBoxes) {
        int n = pathBoxes.size();
        if (n <= 1) {
            return false;
        }
        List<Double> widthHeight = getPageWidthHeight();
        double width = widthHeight.get(0);
        double height = widthHeight.get(1);
        double area = width * height;
        for (int i = 0; i < n - 1; i++) {
            PathBox pathBoxA = pathBoxes.get(i);
            if (pathBoxA.status == -1) {
                continue;
            }
            for (int j = i + 1; j < n; j++) {
                PathBox pathBoxB = pathBoxes.get(j);
                if (pathBoxB.status == -1) {
                    continue;
                }
                if (pathBoxA.intersect(pathBoxB)) {
                    Rectangle2D boxA = (Rectangle2D) pathBoxA.box.clone();
                    Rectangle2D boxB = (Rectangle2D) pathBoxB.box.clone();
                    Rectangle2D.union(boxA, boxB, boxA);
                    if (pathBoxContainChart(boxA)) {
                        continue;
                    }
                    if (boxA.getWidth() * boxA.getHeight() >= 0.7 * area) {
                        continue;
                    }
                    pathBoxA.merge(pathBoxB);
                    pathBoxB.status = -1;
                }
            } // end for j
        } // end for i
        filterInValidPathBoxes(pathBoxes);
        return pathBoxes.size() < n;
    }

    /**
     * 过滤掉无效对象
     * @param pathBoxes
     */
    private void filterInValidPathBoxes(List<PathBox> pathBoxes) {
        if (pathBoxes == null || pathBoxes.isEmpty()) {
            return;
        }
        final Iterator<PathBox> each = pathBoxes.iterator();
        while (each.hasNext()) {
            PathBox pathBox = each.next();
            if (pathBox == null || pathBox.status == -1) {
                each.remove();
            }
        } // end while
    }

    /**
     * 遍历page内所有内容 确定候选Chart区域 (测试中)
     * @param pdfName
     * @return
     */
    private List<PathBox> detectChartAreaBaseMergePath(String pdfName) {
        // 先取出 root 内部对象及其嵌套的子对象 顺序存储为 allContentGroups
        List<ContentGroup> allContentGroups = pageContentGroup.getAllContentGroups(true, true);
        resetShColor(allContentGroups);

        // 取出page的文本
        List<TextChunk> textChunks = pageContentGroup.getAllTextChunks();
        // 合并
        List<TextChunk> merged = new ChartTextChunkMerger(2).merge(textChunks, ChartType.UNKNOWN_CHART);
        // 分割
        merged = merged.stream()
                .flatMap(textChunk -> ChartTextMerger.squeeze(textChunk, ' ', 3).stream())
                .collect(Collectors.toList());
        // 调整方向
        ChunkUtil.reverserRTLChunks(merged);
        // 分割常见的标题错误合并的情况
        ChartTitleLegendScaleParser.splitTwoTitleChunk(merged);

        // 找出常见的标题和数据来源文本
        List<TextChunk> titles = new ArrayList<>();
        List<TextChunk> sources = new ArrayList<>();
        findTitleSourceText(merged, titles, sources);

        // 获取所有图形对象的包围框集
        List<PathBox> pathBoxes = collectPathBoxes(allContentGroups);

        //virsualPathBoxes(pathBoxes, "0", pdfName);

        // 过滤掉页眉页脚的包围框
        List<Double> widthHeight = getPageWidthHeight();
        double width = widthHeight.get(0);
        double height = widthHeight.get(1);
        filterHeadersFootersPath(pathBoxes, width, height, 0.1);
        if (pathBoxes.isEmpty()) {
            return pathBoxes;
        }

        // 可视化
        //virsualPathBoxes(pathBoxes, "1", pdfName);

        // 逻辑顺序相邻合并 减小规模
        while(mergePathBox(pathBoxes, allContentGroups)) {
            //System.out.println(pathBoxes.size());
        }

        // 可视化
        //virsualPathBoxes(pathBoxes, "2", pdfName);

        // 过滤掉接近水平或垂直对象
        filterXYLinePath(pathBoxes);

        // 可视化
        //virsualPathBoxes(pathBoxes, "3", pdfName);

        // 空间相邻合并
        while(mergeSpacePathBox(pathBoxes)) {
            //System.out.println(pathBoxes.size());
        }

        // 可视化
        //virsualPathBoxes(pathBoxes, "3_2", pdfName);

        // 过滤掉高概率为表格正文的对象
        filterTableTextPathBoxes(pathBoxes, merged);

        // 过滤掉和Chart重叠的对象
        PathBox.filterOverlapChart(pathBoxes, charts);

        // 可视化
        //virsualPathBoxes(pathBoxes, "4", pdfName);

        // 合并临近文本对象
        extendPathBoxBaseNearText(pathBoxes, merged);

        // 合并标题和数据来文本对象
        extendPathBoxBaseTitleSource(pathBoxes, titles, sources);

        // 可视化
        //virsualPathBoxes(pathBoxes, "4_2", pdfName);

        // 过滤掉高概率为表格正文的对象
        filterTableTextPathBoxes(pathBoxes, merged);

        // 过滤掉非Chart的对象
        filterNotChartPathBoxes(pathBoxes);

        // 稍微扩展一点
        extendPathBox(pathBoxes);

        // 可视化
        //virsualPathBoxes(pathBoxes, "4_3", pdfName);

        // 调用图像分类服务　判断是否为有效图表类型　如果不是　则过滤掉
        imageClassifyPathBoxes(pathBoxes);

        // 可视化
        //virsualPathBoxes(pathBoxes, "4_4", pdfName);

        // 创建位图对象
        //buildChartBasePathBoxes(pathBoxes, merged);

        return pathBoxes;
    }

    /**
     * 基于给定pathBox对象　再次尝试解析其中的有效图表对象
     * @param pathBoxes
     * @return
     */
    public List<Chart> parseChartBasePathBox(List<PathBox> pathBoxes) {
        // 将Chart区域收集起来  构建伪位图检测区域
        // 这样就需要打开位图检测功能也能用上新的Chart解析方法
        List<DetectedObjectTF> detectedInfo = new ArrayList<>();
        for (int i = 0; i < pathBoxes.size(); i++) {
            PathBox pathBox = pathBoxes.get(i);
            if (pathBox.status == -1 || pathBox.status == 100) {
                continue;
            }
            DetectedChartTableTF tBox = new DetectedChartTableTF();
            tBox.setArea(pathBox.box);
            tBox.imageTypes = pathBox.types;
            tBox.byRecallArea = true;
            detectedInfo.add(tBox);
        } // end for i
        if (detectedInfo.isEmpty()) {
            return charts;
        }

        // 将已解析出的Charts保存起来
        List<Chart> chartsOld = new ArrayList<>();
        chartsOld.addAll(charts);

        // 情况
        charts.clear();

        // 在伪检测区域内部用新的解析方法
        setPageDetectedInfo(detectedInfo);

        // 调用基于检测区域方法　再次对给定区域进行解析 尽可能多的解析出图表
        detectByTFChartTable();

        boolean checkBitmapStatus = checkBitmap;
        checkBitmap = false;
        onPageEnd();
        checkBitmap = checkBitmapStatus;

        // 合并结果
        chartsOld.addAll(charts);
        charts = chartsOld;
        return charts;
    }

    /**
     * 调用图像分类服务判断给定pathboxes区域的有效性 将表格，正文过滤掉
     * @param pathBoxes
     */
    private void imageClassifyPathBoxes(List<PathBox> pathBoxes) {
        List<PathBox> boxes = new ArrayList<>();
        for (int i = 0; i < pathBoxes.size(); i++) {
            PathBox pathBox = pathBoxes.get(i);
            if (pathBox.status == -1 || pathBox.status == 100) {
                continue;
            }
            boxes.add(pathBox);
        }
        if (boxes.isEmpty()) {
            return;
        }

        //long start = System.currentTimeMillis();
        List<List<ImageClassifyResult>> res = ChartClassify.pathBoxImageClassify(context.getNativeDocument(), pageIndex, boxes);
        if (res != null && !res.isEmpty() && res.size() == boxes.size()) {
            for (int i = 0; i < res.size(); i++) {
                List<ImageClassifyResult> result = res.get(i);
                PathBox box = boxes.get(i);
                box.types = result;
                if (!ChartClassify.isImageValid(result)) {
                    box.status = -1;
                }
            }
        }
        else {
            pathBoxes.clear();
        }
        //long costTime = System.currentTimeMillis() - start;
        //logger.info("pathBox grpc image classify costs {}ms", costTime);
        // 如果grpc方式调用位图分类失败　则无法判断有效性，清空

        filterInValidPathBoxes(pathBoxes);
    }

    /**
     * 基于给定pathBox集合和文本信息　构建位图Chart对象
     * @param pathBoxes
     * @param chunks
     */
    private void buildChartBasePathBoxes(List<PathBox> pathBoxes, List<TextChunk> chunks) {
        List<Chart> newCharts = new ArrayList<>();
        for (int i = 0; i < pathBoxes.size(); i++) {
            PathBox pathBox = pathBoxes.get(i);
            if (pathBox.status == -1 || pathBox.status == 100) {
                continue;
            }
            Chart chart = new Chart();
            chart.setArea(pathBox.box);
            newCharts.add(chart);
        } // end for i

        if (newCharts.isEmpty()) {
            return;
        }

        // 裁剪区域
        buildVirtualBitmapChart(newCharts);

        // 解析标题
        ChartTitleLegendScaleParser.selectTitle(chunks, newCharts);

        // 找出给定Chart左下角对应的数据来源信息
        ChartTitleLegendScaleParser.getChartDataSourceInfo(newCharts, chunks);

        charts.addAll(newCharts);
    }

    /**
     * 稍微扩展一点pathBoxex
     * @param pathBoxes
     */
    private void extendPathBox(List<PathBox> pathBoxes) {
        // 遍历尝试扩展区域
        int np = pathBoxes.size();
        for (int i = 0; i < np; i++) {
            PathBox pathBox = pathBoxes.get(i);
            if (pathBox.status == -1 || pathBox.status == 100) {
                continue;
            }
            GraphicsUtil.extendRect(pathBox.box, 3.0);
        }
    }

    /**
     * 基于相邻文字扩展pathBoxes
     * @param pathBoxes
     * @param chunks
     */
    private void extendPathBoxBaseNearText(
            List<PathBox> pathBoxes, List<TextChunk> chunks) {
        if (pathBoxes == null || pathBoxes.isEmpty()) {
            return;
        }
        List<PathBox> mergePathBoxes = addPageChartsIntoPathBoxes(pathBoxes);

        // 遍历尝试扩展区域
        int np = mergePathBoxes.size();
        List<TextChunk> remainChunks = new ArrayList<>();
        for (TextChunk chunk : chunks) {
            if (chunk.getFontSize() > 30.0) {
                continue;
            }
            Point2D pt = chunk.getCenter();
            boolean inChart = false;
            for (int i = 0; i < np; i++) {
                PathBox pathBox = mergePathBoxes.get(i);
                if (pathBox.status == 100) {
                    if (pathBox.box.contains(pt)) {
                        inChart = true;
                        break;
                    }
                }
                else {
                    if (GraphicsUtil.contains(pathBox.box, chunk)) {
                        inChart = true;
                        break;
                    }
                }
            } // end for i
            if (!inChart) {
                remainChunks.add(chunk);
            }
        } // end for chunk

        List<Double> widthHeight = getPageWidthHeight();
        double pageWidth = widthHeight.get(0);
        double coef = 1.5;
        // 遍历文本
        for (int ichunk = 0; ichunk < remainChunks.size(); ichunk++) {
            TextChunk chunk = remainChunks.get(ichunk);
            // 判断是否有连续两个chunk　长度很大并且位置相邻  如果是在跳过因为大概率为正文
            if (ichunk - 1 >= 0 & chunk.getWidth() > 0.5 * pageWidth) {
                TextChunk chunkBefore = remainChunks.get(ichunk - 1);
                double dy = Math.abs(chunk.getCenterY() - chunkBefore.getCenterY());
                double chunkHeight = Math.abs(chunk.getHeight() + chunkBefore.getHeight());
                if (chunkBefore.getWidth() > 0.5 * pageWidth && dy < 3 * chunkHeight) {
                    continue;
                }
            }

            Rectangle2D box = (Rectangle2D) chunk.getBounds2D().clone();
            double len = coef * Math.min(box.getHeight(), box.getWidth());
            TextDirection direction = chunk.getDirection();
            if (direction == TextDirection.VERTICAL_UP ||
                    direction == TextDirection.VERTICAL_DOWN) {
                len *= 2;
            }
            GraphicsUtil.extendRect(box, len);
            double cx = box.getCenterX();
            // 找出相邻pathBox对象
            for (int i = 0; i < np; i++) {
                PathBox pathBox = mergePathBoxes.get(i);
                if (pathBox.status == -1 || pathBox.status == 100) {
                    continue;
                }
                // 如果相交
                if (pathBox.box.intersects(box)) {
                    // 判断是否在左侧或右侧很远的位置  如果是在跳过
                    double pxmin = pathBox.box.getMinX();
                    double pxmax = pathBox.box.getMaxX();
                    double width = pathBox.box.getWidth();
                    if (cx < pxmin && (pxmin - cx)/width > 0.3) {
                        continue;
                    }
                    else if (cx > pxmax && (cx - pxmax)/width > 0.3) {
                        continue;
                    }
                    // 避免合并过长的文本对象　主要防止合并正文用
                    if (box.getWidth() > 1.5 * width) {
                        continue;
                    }
                    PathBox.mergeTextChunkIntoPathBox(pathBox, chunk, mergePathBoxes);
                    break;
                }
            } // end for i
        } // end for chunk
    }

    /**
     * 基于标题和数据来源文本扩展给定pathbox区域
     * @param pathBoxes
     * @param titles
     * @param sources
     */
    private void extendPathBoxBaseTitleSource(
            List<PathBox> pathBoxes, List<TextChunk> titles, List<TextChunk> sources) {
        if (pathBoxes == null || pathBoxes.isEmpty()) {
            return;
        }

        // 判断个数有效性
        int nt = titles.size();
        int ns = sources.size();
        if (nt == 0 || ns == 0) {
            return;
        }

        // 合并已解析出的Charts
        List<PathBox> mergePathBoxes = addPageChartsIntoPathBoxes(pathBoxes);

        // 遍历尝试扩展区域
        int np = mergePathBoxes.size();
        for (int i = 0; i < np; i++) {
            PathBox pathBox = mergePathBoxes.get(i);
            if (pathBox.status == -1 || pathBox.status == 100) {
                continue;
            }

            // 找出最相邻的标题和数据来源信息
            findNearTitleSource(pathBox, titles, "title");
            findNearTitleSource(pathBox, sources, "source");
            if (pathBox.nearTitle == null || pathBox.nearSource == null) {
                continue;
            }
            pathBox.extendBaseTitleSource(mergePathBoxes);
        } // end for i
    }

    private void findNearTitleSource(
            PathBox pathBox, List<TextChunk> chunks, String label) {
        // 计算pathBox位置信息
        Rectangle2D box = pathBox.box;
        double pathmin = box.getMinX(), pathmax = box.getMaxX();
        double pathy = box.getCenterY();
        int nearIth = -1;
        double overlapLen = -100.0;
        double minDy = 10000.0;
        // 遍历chunks 找出最相邻的对象
        for (int j = 0; j < chunks.size(); j++) {
            TextChunk chunk = chunks.get(j);
            double chunkmin = chunk.getMinX(), chunkmax = chunk.getMaxX();
            double chunky = chunk.getCenterY();
            double left = Math.max(pathmin, chunkmin);
            double right = Math.min(pathmax, chunkmax);
            if (right <= left) {
                continue;
            }
            else {
                double dy = -100.0;
                if (label.equals("title") && chunky < pathy) {
                    dy = pathy - chunky;
                }
                else if (label.equals("source") && chunky > pathy) {
                    dy = chunky - pathy;
                }
                if (dy > 0.0 && minDy > dy) {
                    minDy = dy;
                    overlapLen = right - left;
                    nearIth = j;
                }
            }
        } // end for j
        if (overlapLen > 0.0 && nearIth >= 0) {
            if (label.equals("title")) {
                pathBox.nearTitle = chunks.get(nearIth);
            }
            else {
                pathBox.nearSource = chunks.get(nearIth);
            }
        }
    }

    /**
     * 找出高概率为标题和数据来源的文本
     * @param chunks
     * @param titles
     * @param sources
     */
    private void findTitleSourceText(
            List<TextChunk> chunks, List<TextChunk> titles, List<TextChunk> sources) {
        titles.clear();
        sources.clear();
        for (TextChunk chunk : chunks) {
            String text = chunk.getText();
            if (ChartTitleLegendScaleParser.isDataSourceTextChunk(chunk)) {
                sources.add(chunk);
            } else if (ChartTitleLegendScaleParser.bHighProbabilityChartTitle(text) ||
                    ChartTitleLegendScaleParser.bHighProbabilityTableTitle(text)) {
                titles.add(chunk);
            }
        } // end for chunk
    }

    /**
     * 过滤掉非Chart的pathBox对象
     * @param pathBoxes
     */
    private void filterNotChartPathBoxes(List<PathBox> pathBoxes) {
        if (pathBoxes == null || pathBoxes.isEmpty()) {
            return;
        }
        int countTitle = 0, countSource = 0;
        for (PathBox pathBox : pathBoxes) {
            if (pathBox.status == -1 || pathBox.status == 100) {
                continue;
            }
            // 清空计数
            countSource = 0;
            countTitle = 0;
            List<TextChunk> chunks = pathBox.chunks;
            List<TextChunk> titles = new ArrayList<>();
            List<TextChunk> sources = new ArrayList<>();
            // 遍历内部文本对象　统计标题和来源信息个数
            for (TextChunk chunk : chunks) {
                String text = chunk.getText().trim();
                if (ChartTitleLegendScaleParser.isDataSourceTextChunk(chunk)) {
                    sources.add(chunk);
                    countSource++;
                } else if (ChartTitleLegendScaleParser.bHighProbabilityChartTitle(text) ||
                        ChartTitleLegendScaleParser.bHighProbabilityTableTitle(text)) {
                    titles.add(chunk);
                    countTitle++;
                }
            }

            // 如果宽高比过小　并且含有多个标题或是数据来源信息时　设置为无效
            double rate = pathBox.box.getWidth()/pathBox.box.getHeight();
            if (rate > 5) {
                if (countTitle >= 2 || countSource >= 2) {
                    pathBox.status = -1;
                }
                else if (countTitle == 1 && countSource == 1) {
                    if (sources.get(0).getCenterY() < titles.get(0).getCenterY()) {
                        pathBox.status = -1;
                    }
                }
            }
            // 判断是否为高概率矢量表格对象
            if (pathBox.status != -1 && countTitle == 1) {
                String text = titles.get(0).getText().trim();
                if (ChartTitleLegendScaleParser.bHighProbabilityTableTitle(text)) {
                    // 统计候选图表内部区域文本数目
                    double cx = pathBox.box.getCenterX();
                    double cy = pathBox.box.getCenterY();
                    double w = pathBox.box.getWidth();
                    double h = pathBox.box.getHeight();
                    Rectangle2D box = new Rectangle2D.Double(cx, cy, 0.5 * w, 0.5 * h);
                    int num = 0;
                    for (TextChunk chunk : pathBox.chunks) {
                        if (box.contains(chunk)) {
                            num++;
                        }
                    }
                    // 如果数目过大　大概率为矢量表格对象
                    if (num >= 10) {
                        pathBox.status = -1;
                    }
                }
            }
        }
        // 过滤掉
        filterInValidPathBoxes(pathBoxes);
    }

    /**
     * 获取Page的宽高
     * @return
     */
    private List<Double> getPageWidthHeight() {
        PDPage page = context.getDocument().getDocument().getPage(pageIndex);
        PDRectangle pageBox = page.getMediaBox();
        double width = pageBox.getWidth();
        double height = pageBox.getHeight();
        List<Double> widthHeight = new ArrayList<>();
        if (page.getRotation() == 90) {
            widthHeight.add(height);
            widthHeight.add(width);
        }
        else {
            widthHeight.add(width);
            widthHeight.add(height);
        }
        return widthHeight;
    }

    /**
     * 过滤掉高概率为Table对象
     * @param pathBoxes
     */
    private void filterTableTextPathBoxes(List<PathBox> pathBoxes, List<TextChunk> chunks) {
        if (pathBoxes == null || pathBoxes.isEmpty()) {
            return;
        }
        List<Double> widthHeight = getPageWidthHeight();
        double width = widthHeight.get(0);
        double height = widthHeight.get(1);
        double area = width * height;
        for (PathBox pathBox : pathBoxes) {
            for (int i = 0; i < chunks.size(); i++) {
                TextChunk chunk = chunks.get(i);
                if (!pathBox.chunks.contains(chunk) && pathBox.box.contains(chunk)) {
                    pathBox.addChunk(chunk);
                }
            }

            // 如果内部文字数目过多　则高概率为表格和正文
            if (pathBox.chunks.size() >= 120) {
                pathBox.status = -1;
            }

            // 统计文字面积和
            double s = 0.0;
            for (TextChunk chunk : pathBox.chunks) {
                s += chunk.getHeight() * chunk.getWidth();
            }

            double w = pathBox.box.getWidth();
            double h = pathBox.box.getHeight();
            double areaBox = w * h;
            // 如果文字占比多大　则判断为表格对象
            if (s > 0.4 * areaBox) {
                pathBox.status = -1;
            }
            // 如果面积过小 则判断为无效
            if (areaBox > 0.8 * area || areaBox < 0.002 * area) {
                pathBox.status = -1;
            }
            if (w < 20 || h < 20) {
                pathBox.status = -1;
            }
            if ((w / h > 10.0 || w / h < 0.1) && areaBox < 0.01 * area) {
                pathBox.status = -1;
            }
        } // end for PathBox
        filterInValidPathBoxes(pathBoxes);
    }

    private List<PathBox> addPageChartsIntoPathBoxes(List<PathBox> pathBoxes) {
        List<PathBox> mergePathBoxes = new ArrayList<>();
        mergePathBoxes.addAll(pathBoxes);
        for (int i = 0; i < charts.size(); i++) {
            Chart chart = charts.get(i);
            int ith = 100 + i;
            PathBox pChart = new PathBox(ith, chart.getArea());
            pChart.status = 100;
            mergePathBoxes.add(pChart);
        }
        return mergePathBoxes;
    }

    /**
     * 可视化
     * @param pathBoxes
     */
    private void virsualPathBoxes(List<PathBox> pathBoxes, String info, String pdfName) {
        PDFRenderer pdfRenderer = new PDFRenderer(context.getDocument().getDocument());
        BufferedImage imageFile = null;
        try {
            Random rand = new Random();
            // 将Page渲染成 BufferedImage 对象
            imageFile = pdfRenderer.renderImageWithDPI(pageIndex, 72.0f, ImageType.RGB);
            Graphics2D graphics = imageFile.createGraphics();
            graphics.setFont(new Font("TimesRoman", Font.PLAIN, 30));
            // 将解析出的Chart加入　方便可视化
            List<PathBox> mergePathBoxes = addPageChartsIntoPathBoxes(pathBoxes);
            for (int i = 0; i < mergePathBoxes.size(); i++) {
                PathBox pathBox = mergePathBoxes.get(i);
                double x1 = pathBox.box.getMinX();
                double x2 = pathBox.box.getMaxX();
                double y1 = pathBox.box.getMinY();
                double y2 = pathBox.box.getMaxY();
                Line2D line1 = new Line2D.Double(x1, y1, x2, y1);
                Line2D line2 = new Line2D.Double(x2, y1, x2, y2);
                Line2D line3 = new Line2D.Double(x2, y2, x1, y2);
                Line2D line4 = new Line2D.Double(x1, y2, x1, y1);
                float r = rand.nextFloat();
                float g = rand.nextFloat();
                float b = rand.nextFloat();
                graphics.setStroke(new BasicStroke(3));
                Color randomColor = new Color(r, g, b);
                graphics.setColor(randomColor);
                graphics.draw(line1);
                graphics.draw(line2);
                graphics.draw(line3);
                graphics.draw(line4);
                float xc = (float)( 0.5 * (x1 + x2)) + 5;
                float yc = (float)( 0.5 * (y1 + y2)) + 5;
                if (pathBox.status == 100) {
                    graphics.setColor(Color.GREEN);
                    String text = "B : " + Integer.toString(i);
                    graphics.drawString(text, xc, yc);
                }
                else {
                    graphics.setColor(Color.RED);
                    String text = "A : " + Integer.toString(i);
                    graphics.drawString(text, xc, yc);
                }
            }
            graphics.dispose();
        } catch (Exception e) {
            return;
        }

        // 用当前时间的长整数　来作为刻度信息的ID 调试输出用
        Date date = new Date();
        long id = date.getTime();
        String filename = id + "." + info + "." + pdfName + "." + pageIndex + ".png";
        PathUtils.savePathToPng(imageFile, "/media/myyang/data3/page_path_boxes/", filename);
    }

    /**
     * 遍历page内所有内容 确定候选Chart区域 (测试中)
     * @return
     */
    private List<Rectangle2D> detectChartArea() {
        // 先取出 root 内部对象及其嵌套的子对象 顺序存储为 allContentGroups
        List<ContentGroup> allContentGroups = pageContentGroup.getAllContentGroups(true);
        int n = allContentGroups.size();
//        List<Integer> ids = new ArrayList<>();
//        for (int id = 0; id < n; id++) {
//            ids.add(id);
//        }
        Rectangle2D pageArea = pageContentGroup.getArea();
        Grid grid = new Grid(160, 90, pageArea.getWidth(), pageArea.getHeight());
        // 遍历各子 ContentGroup 对象
        for (int igroup = 0; igroup < n; igroup++) {
            current = allContentGroups.get(igroup);
            buildGrid(current, igroup, grid);
        }
        grid.output("/home/myyang/page_img", "page." + pageIndex + "." + (Grid.id++) + ".jpg");
        return null;
    }

    // 解析给定ContentGroup内部 PathItem 对象  判断图形元素的具体信息
    private void buildGrid(ContentGroup group, int igroup, Grid grid) {
        // 存在某个子path对象不在区域范围内的 故跳过这一部分  可以过滤掉不显示的图形对象
        // 在GIC项目中某些ContentGroup 内有多个环形图对象  但是有些不显示 故过滤掉
        Rectangle2D box = group.getArea();
        GraphicsUtil.extendRect(box, 10);
        List<ContentItem> items = group.getAllItems();
        for (int iItem = 0; iItem < items.size(); iItem++) {
            ContentItem item = items.get(iItem);
            if (item instanceof TextChunkItem) {
                TextChunkItem textChunkItem = (TextChunkItem)item;
                Rectangle2D itemArea = textChunkItem.getItem().getBounds2D();
                GraphicsUtil.extendRect(itemArea, 1);
                if (!box.intersects(itemArea)) {
                    continue;
                }
                BaseContent content = new BaseContent(igroup, iItem, BaseContentType.TEXT);
                //grid.addTextChunk(textChunkItem, content);
            } else if (item instanceof PathItem) {
                PathItem pathItem = (PathItem)item;
                Rectangle2D itemArea = pathItem.getItem().getBounds2D();
                GraphicsUtil.extendRect(itemArea, 1);
                if (!box.intersects(itemArea)) {
                    continue;
                }
                if (isWhite(pathItem.getRGBColor())) {
                    continue;
                }
                if (pathItem.isFill()) {
                    BaseContent content = new BaseContent(igroup, iItem, BaseContentType.FILL);
                    grid.addFillPath(pathItem, content);
                }
                else {
                    BaseContent content = new BaseContent(igroup, iItem, BaseContentType.STROKE);
                    grid.addPath(pathItem, content);
                }
            }
            else {
                continue;
            }
        }
    }

    private List<Chart> detect(List<ContentGroup> allContentGroups, boolean checkForm) {
        status = Status.UNKNOWN;

        // 先取出 root 内部对象及其嵌套的子对象 顺序存储为 allContentGroups
        int n = allContentGroups.size();
        List<Integer> ids = new ArrayList<>();
        for (int id = 0; id < n; id++) {
            ids.add(id);
        }
        // 遍历各子 ContentGroup 对象
        for (int igroup = 0; igroup < n; igroup++) {
            ContentGroup contentGroup = allContentGroups.get(igroup);
            current = contentGroup;
            // 根据 current 的类型 做出相应的处理
            ContentGroupType ctype = getContentGroupType(current);
            /*if (ctype == ContentGroupType.UNKNOWN_TYPE) {
                continue;
            }*/
            if (checkForm) {
                if (ctype != ContentGroupType.FORM_BEGIN_TYPE && ctype != ContentGroupType.FORM_TYPE) {
                    continue;
                }
            }
            else {
                if (ctype == ContentGroupType.FORM_BEGIN_TYPE || ctype == ContentGroupType.FORM_TYPE) {
                    continue;
                }
            }

            if (ctype == ContentGroupType.CHART_BEGIN_TYPE) {
                // 判断可能的候选区域对象是否位于当前饼图Chart内部
                if (!isCandidateAreaInPieChart(current)) {
                    chartEnd();
                }
            }
            else if (ctype == ContentGroupType.CHART_END_TYPE) {
                chartEnd();
            }

            // 基于TF Model 检测Page内对象信息 过滤掉无效内容
            // 目前由于准确率不高  故只参考其检测出的非Chart对象
            if (confictPageDetectedInfo(current)) {
                chartEnd();
                continue;
            }

            // 判断 current 是否位于某个 Form 对象内部
            ContentGroup parent = current.getParent();
            if (parent != null && (parent.isPDFormXObject() || parent.inForm())) {
                PDFormXObject formNew= parent.getForm();
                if (!formNew.equals(form)) {
                    //logger.info("current is PDF Form XObject");
                    chartEnd();
                    form = formNew;
                    PDRectangle bbox = form.getBBox();
                    formBox = current.getPageTransform().createTransformedShape(
                            bbox.transform(current.getGraphicsState().getCurrentTransformationMatrix())).getBounds2D();
                }
            }
            else if (form != null) {
                chartEnd();
                form = null;
                formBox = null;
            }

            // 根据当前解析的状态 status 做相应的处理
            Rectangle2D area = current.getArea();
            switch (status) {
                case UNKNOWN:
                    if (current.hasOnlyPathItems() && isValidChartStart(current)) {
                        chartBegin(current);
                        if (!detectGraphics(true)) {
                            resetStatus();
                        }
                    }
                    break;
                case BEGIN_GRAPHICS:
                    if (inChartArea()) {
                        mergeArea(current);
                        if (current.hasOnlyPathItems()) {
                            if (chartStart != null && current.getLevel() < chartStart.getLevel() &&
                                    !GraphicsUtil.contains(chart.getArea(), area)) {
                                chartEnd();
                                detectNewChart();
                            } else {
                                chartContentGroup.add(current);
                                if (!detectGraphics(true)) {
                                    chartEnd();
                                    detectNewChart();
                                }
                            }
                        } else if (current.hasText()) {
                            chartContentGroup.add(current);
                            graphicsEnd();
                            if (status != Status.UNKNOWN) {
                                status = Status.BEGIN_AXIS_TEXT;
                            }
                            detectAxisText();
                        } else if (current.hasImage()) {
                            // 有可能是水印, 图片画的点, 忽略
                            // 最新需求 是要找出3D图表
                            dealWithChartInnerImage();
                            chartContentGroup.add(current);
                        } else {
                            // 设置颜色, 线宽等操作
                            chartContentGroup.add(current);
                        }
                    } else {
                        chartEnd();
                        detectNewChart();
                    }
                    break;
                case BEGIN_AXIS_TEXT:
                    if (inChartArea()) {
                        mergeArea(current);
                        if (current.hasOnlyPathItems()) {
                            // 有可能是斜着的文字, 也有可能是图例
                            ContentGroup nextTextGroup = getNextNearTextGroup(
                                    allContentGroups, ids, igroup, true);
                            if (!isNotePath(current, nextTextGroup) && detectLegendLine()) {
                                status = Status.BEGIN_LEGEND;
                                chartContentGroup.add(current);
                            } else if (detectRotatedText()) {
                                // 判断是否为斜着的OCR　Text
                                chartContentGroup.add(current);
                            } else if (detectLegendBackground()) {
                                // 有可能是图例的背景区域
                                chartContentGroup.add(current);
                            } else {
                                // 其他元素
                                if (!isInValidRectangle(area) && !chart.getArea().intersects(area)) {
                                    chartEnd();
                                    detectNewChart();
                                } else {
                                    if (!detectGraphics(false)) {
                                        chartEnd();
                                        detectNewChart();
                                        break;
                                    }
                                    chartContentGroup.add(current);
                                }
                            }
                        } else if (current.hasText()) {
                            chartContentGroup.add(current);
                            detectAxisText();
                        } else if (current.hasImage()) {
                            chartEnd();
                        } else {
                            // 设置颜色等操作
                            chartContentGroup.add(current);
                        }
                    } else {
                        chartEnd();
                        detectNewChart();
                    }
                    break;
                case BEGIN_LEGEND:
                    if (inChartArea()) {
                        mergeArea(current);
                        if (current.hasOnlyPathItems()) {
                            // 判断是否图例标识
                            ContentGroup nextTextGroup = getNextNearTextGroup(
                                    allContentGroups, ids, igroup, true);
                            if (!isNotePath(current, nextTextGroup) && detectLegendLine()) {
                                chartContentGroup.add(current);
                            }
                            else if (!detectGraphics(false)) {
                                chartEnd();
                                detectNewChart();
                                break;
                            }
                            // 判断current区域是否和 chart 高度重叠  如果面积重叠低 有可能是新的接着的chart
                            else if (inChartArea(0.8)) {
                                // 可能是边框
                                chartContentGroup.add(current);
                            } else {
                                chartEnd();
                                detectNewChart();
                            }
                        } else if (current.hasText()) {
                            chartContentGroup.add(current);
                            detectLegendText();
                        } else if (current.hasImage()) {
                            // 有可能是图片图例
                            if (detectImageLegend()) {
                                chartContentGroup.add(current);
                            } else {
                                chartEnd();
                            }
                        } else {
                            // 设置颜色, 线宽等操作
                            chartContentGroup.add(current);
                        }
                    } else {
                        chartEnd();
                        detectNewChart();
                    }
                    break;
            }
        }
        chartEnd();
        return charts;
    }

    /**
     * 处理Chart内部的位图对象
     */
    private void dealWithChartInnerImage() {
        List<ContentItem> items = current.getAllItems();
        for (int iItem = 0; iItem < items.size(); iItem++) {
            ContentItem item = items.get(iItem);
            if (item instanceof ImageItem) {
                ImageItem imageItem = (ImageItem)item;
                Rectangle2D box = imageItem.getBounds();
                chart.innerImageBox.add(box);
            }
        }
    }

    /**
     * 宽度或高度为零的矩形区域　　在计算 Rectangle2D.intersect时不够鲁棒
     * 在调用 intersect之前判断下
     * @param area
     * @return
     */
    private boolean isInValidRectangle(Rectangle2D area) {
        if (area == null) {
            return true;
        }
        return area.getHeight() <= 1.E-6 || area.getWidth() <= 1.E-6;
    }

    /**
     * 判断给定ContentGroup是否符合Chart开始的空间尺寸大小条件
     * @param current
     * @return
     */
    private boolean isValidChartStart(ContentGroup current) {
        double pageWidth = pageContentGroup.getArea().getWidth();
        double pageHeight = pageContentGroup.getArea().getHeight();
        double width = current.getArea().getWidth();
        double height = current.getArea().getHeight();
        // 有些pdf最开始会给整个页面画个白色区域, 然后再画chart, 通过检测区域大小去除
        if ((width >= pageWidth * 0.8) && (height >= pageHeight * 0.85)) {
            return false;
        }
        // 有些PDF页眉页脚有一条很长的线, 过滤掉
        if (width >= pageWidth * 0.95 && height < 10) {
            return false;
        }
        return true;
    }

    private ContentGroup getLastContentGroup() {
        if (chartContentGroup != null && !chartContentGroup.getItems().isEmpty()) {
            ContentItem item = chartContentGroup.getItems().get(chartContentGroup.getItems().size()-1);
            if (item instanceof ChildItem) {
                return ((ChildItem) item).getItem();
            }
        }
        return null;
    }

    private boolean isBarChartPath() {
        // 检测是不是柱状图, 有时候某些柱状图会先画柱状, 每个柱状之间的间距比较大, 仅仅用区域去检测的话有可能检测不到
        ContentGroup lastContentGroup = getLastContentGroup();
        if (lastContentGroup == null) {
            return false;
        }
        if (!lastContentGroup.hasOnlyPathItems() || !current.hasOnlyPathItems()) {
            return false;
        }
        PathItem lastPathItem = (PathItem) lastContentGroup.getItems().get(0);
        PathItem pathItem = (PathItem) current.getItems().get(0);
        if (!lastPathItem.isFill() || !pathItem.isFill()) {
            return false;
        }
        Rectangle2D lastArea = lastContentGroup.getArea();
        Rectangle2D area = current.getArea();
        if (Math.abs(lastArea.getWidth() - area.getWidth()) < 3
                && (Math.abs(lastArea.getMinY() - area.getMinY()) < 3 || Math.abs(lastArea.getMaxY() - area.getMaxY()) < 3)) {
            // 竖直柱状图
            return true;
        } else if (Math.abs(lastArea.getHeight() - area.getHeight()) < 3
                && (Math.abs(lastArea.getMinX() - area.getMinX()) < 3 || Math.abs(lastArea.getMaxX() - area.getMaxX()) < 3)) {
            // 水平柱状图
            return true;
        } else {
            return false;
        }
    }

    /**
     * 基于当前有效 chart 判断 current 是否为新Chart的内容
     * 如果状态为刻度文字或图例状态 则大概率不会再出现坐标轴
     * 如果current可以构成合理Chart区域 且 与当前 chart的绘图元素包围框没有交集  则很有可能是新Chart的开始
     * (注: 目前为止少数出现交替绘制图像和文字概率小, 暂时不考虑 如果以后出现频繁则需要改进算法)
     * 后续添加其他识别新 Chart 的规律
     * @return
     */
    private boolean isNewChartStart() {
        if (current.hasText()) {
            return false;
        }
        if (status == Status.BEGIN_AXIS_TEXT || status == Status.BEGIN_LEGEND) {
            // 判断current是否为新坐标轴
            current.forEach(PathItem.class, pathItem -> {
                if (!pathItem.isFill() && isAxisLine(pathItem)) {}
            });
            if (!hAxisLines.isEmpty() || !vAxisLines.isEmpty()) {
                hAxisLines.clear();
                vAxisLines.clear();
                return true;
            }
        }
        return false;
    }

    private boolean inChartArea() {
        return inChartArea(-1);
    }

    private boolean inChartArea(double delta) {
        if (current.getItems().isEmpty()) {
            return true;
        }
        if (chart.getArea() == null) {
            return false;
        }
        Rectangle2D area = current.getArea();
        // 文字块处理  后续需要改进解析
        if (current.hasText()) {
            // 过滤超大的文字块 有时文字块不仅包含当前chart内部文字信息 还包含其他不相关区域的文字信息
            if (area.getWidth() > chart.getWidth() * 1.3) {
                // 如果已经开始绘制文字了 则当前内容不能过高
                if (status != Status.BEGIN_GRAPHICS &&
                        area.getHeight() > chart.getHeight() * 1.3) {
                    return false;
                }
                // 绘制的文字区域和chart有一定的重叠面积 不能相隔太远
                else if (!areaOverlapValid(area, chart.getArea(), delta)) {
                    return false;
                }
            }
        }

        // 判断current是否为新Chart的开始
        if (isNewChartStart()) {
            return false;
        }

        if (GraphicsUtil.contains(checkArea, area)) {
            return true;
        }
        if (status == Status.BEGIN_GRAPHICS) {
            if (isBarChartPath()) {
                return true;
            }
            return checkArea.intersects(area);
        }

        if (!checkArea.intersects(area)) {
            return false;
        }

        // 判断重叠面积的有效性
        boolean over = areaOverlapValid(checkArea, area, delta);
        if (!over) {
            return false;
        }

        // 在相邻Chart区域中间　有时会出现横跨Page的近似长线对象
        // 会误连接相邻Chart区域 导致当前Chart区域扩展到附近Chart
        if (isLineOutChart()) {
            return false;
        }
        return true;
    }

    /**
     * 检测当前ContentGroup是否为Chart区域外部　无效线对象
     * @return
     */
    public boolean isLineOutChart() {
        // 是否为出现文字对象后
        boolean textStatus = (status == Status.BEGIN_AXIS_TEXT ||
                status == Status.BEGIN_LEGEND ||
                status == Status.BEGIN_OCR);
        if (!textStatus) {
            return false;
        }

        // 判断区域有效性
        Rectangle2D area = current.getArea();
        Rectangle2D box = chart.getArea();
        if (box == null || area == null) {
            return false;
        }

        // 判断是否为近似水平线
        double coefH = area.getHeight() / box.getHeight();
        double coefW = area.getWidth() / box.getWidth();
        if (coefH >= 0.03 || coefW <= 0.3) {
            return false;
        }

        // 判断是否在外部
        boolean isInvalid = isInValidRectangle(area);
        boolean outofY = area.getMaxY() < box.getMinY() || area.getMinY() > box.getMaxY();
        if (isInvalid) {
            return outofY;
        }
        boolean intersect = box.intersects(area);
        if (!intersect) {
            return outofY;
        }
        // 如果存在水平坐标轴并且当前线的宽度相对于坐标轴过大
        if (chart.hAxis != null) {
            double widthXAxis = chart.hAxis.getBounds().getWidth();
            if (area.getWidth() / widthXAxis >= 1.5) {
                return true;
            }
        }
        return false;
    }

    /**
     * 计算两个矩形对象的重叠部分 判断重叠面积相对于第二个矩形 areaB 占比的有效性
     * @param areaA
     * @param areaB
     * @param delta
     * @return
     */
    private boolean areaOverlapValid(Rectangle2D areaA, Rectangle2D areaB, double delta) {
        // 检测相交的面积
        if (!areaA.intersects(areaB)) {
            return false;
        }
        Rectangle2D intersectsArea = new Rectangle2D.Double();
        Rectangle2D.intersect(areaA, areaB, intersectsArea);
        // 根据不同类型 定义不同合理占比系数
        if (delta < 0) {
            switch (status) {
                case BEGIN_GRAPHICS:
                    delta = 0.5;
                    break;
                case BEGIN_LEGEND:
                    delta = 0.5;
                    // 如果多次处理图例 则一般宽度不发生大的改变
                    if (chart != null && chart.legends.size() >= 2) {
                        delta = 0.9;
                    }
                    break;
                case BEGIN_AXIS_TEXT:
                    delta = 0.3;
                    break;
                default:
                    delta = 0.5;
                    break;
            }
        }
        return Math.abs(intersectsArea.getWidth() * intersectsArea.getHeight())
                > areaB.getWidth() * areaB.getHeight() * delta;
    }

    private boolean isEmpty(Rectangle2D rect) {
        return ChartUtils.equals(rect.getWidth(), 0) && ChartUtils.equals(rect.getHeight(), 0);
    }

    private void mergeArea(ContentGroup contentGroup) {
        Rectangle2D cbox = contentGroup.getArea();
//        Rectangle2D clipbox = contentGroup.getClipArea();
//        Rectangle2D bbox = contentGroup.getArea();
//
//        Rectangle2D cbox = contentGroup.getClipArea().createIntersection(contentGroup.getArea());
        if (isEmpty(cbox)) {
            // 一般是设置颜色, 线宽的组, 没有实际大小, 忽略
            return;
        }
        if (chart.getArea() == null) {
            chart.setArea(cbox);
        } else {
            Rectangle area = chart.getArea();
            Rectangle2D.union(area, cbox, area);
            GraphicsUtil.extendRect(area, 0.1);
            chart.setArea(area);
        }

        switch (status) {
            case BEGIN_GRAPHICS: {
                double w = Math.max(pageContentGroup.getArea().getWidth() * 0.13, chart.getWidth() / 4);
                double h = Math.max(pageContentGroup.getArea().getHeight() * 0.13, chart.getHeight() / 4);
                checkArea.setRect(chart.getArea().withCenterExpand(w, h));
                break;
            }
            default: {
                double w = chart.getWidth() / 4;
                double h = chart.getHeight() / 4;
                Rectangle2D box = (Rectangle2D) checkArea.clone();
                box.setRect(chart.getArea().withCenterExpand(w, h));
                if (!GraphicsUtil.contains(checkArea, box)) {
                    checkArea = (Rectangle2D) box.clone();
                }
                break;
            }
        }
    }

    private boolean detectBitmapImage(List<TextChunk> chunks) {
        for (ContentItem contentItem : current.getItems()) {
            if (!(contentItem instanceof ImageItem)) {
                return false;
            }
            ImageItem imageItem = (ImageItem) contentItem;
            Rectangle2D imageBox = imageItem.getBounds();
            if (!hasTextsCoveredByImage(imageBox, chunks)) {
                checkBitmapChart(imageItem.getItem(), imageBox);
            }
        }
        return true;

    }

    private boolean detectImageLegend() {
        for (ContentItem contentItem : current.getItems()) {
            if (!(contentItem instanceof ImageItem)) {
                return false;
            }
            ImageItem imageItem = (ImageItem) contentItem;
            if (imageItem.getBounds().getWidth() > 10 || imageItem.getBounds().getHeight() > 10) {
                return false;
            }
        }
        return true;
    }

    private boolean detectLegendBackground() {
        for (ContentItem contentItem : current.getItems()) {
            if (!(contentItem instanceof PathItem)) {
                return false;
            }
            PathItem item = (PathItem) contentItem;
            Color color = item.getRGBColor();
            if (isWhite(color)) {
                return true;
            }
        }
        return false;
    }

    private boolean detectLegendLine() {
        boolean isLegend = false;
        for (ContentItem contentItem : current.getItems()) {
            if (!(contentItem instanceof PathItem)) {
                return false;
            }
            PathItem item = (PathItem) contentItem;
            GeneralPath path = item.getItem();
            Color color = item.getRGBColor();
            if (isWhite(color)) {
                continue;
            }
            if (item.isFill() && fillColors.contains(color)) {
                Rectangle2D area = getLegendArea(path);
                if (area != null) {
                    addLegend(color, area);
                    isLegend = true;
                    continue;
                }
                // 检测是否为图例的标识框   存在只用带颜色的矩形区域作为图列的情况
                else if ( ChartPathInfosParser.isLegendIdentificationInChart(path, chart, true)) {
                    area = path.getBounds2D();
                    addLegend(color, area);
                    isLegend = true;
                    continue;
                }
            }
            if (!item.isFill() && strokeColors.contains(color)) {
                Rectangle2D area = getLegendArea(path);
                if (area != null) {
                    addLegend(color, area);
                    isLegend = true;
                    continue;
                }
                if (ChartPathInfosParser.isLegendIdentificationInChart(path, chart)) {
                    area = path.getBounds2D();
                    addLegend(color, area);
                    isLegend = true;
                }
            }
            // 如果当前候选图例对象没有对应的图形对象
            // 则判断和已有图例对象的空间关系　进一步判断是否为图例
            if (!isLegend && !chart.legends.isEmpty()) {
                // 取最近的一个图例对象
                int n = chart.legends.size();
                Chart.Legend legend = chart.legends.get(n - 1);
                // 计算空间尺寸信息
                double xA = path.getBounds2D().getCenterX();
                double xB = legend.line.getBounds2D().getCenterX();
                double yA = path.getBounds2D().getCenterY();
                double yB = legend.line.getBounds2D().getCenterY();
                double dx = xA - xB;
                double dy = yA - yB;
                // 计算距离
                double dist = Math.sqrt(dx * dx + dy * dy);
                double dw = path.getBounds2D().getWidth() - legend.line.getBounds2D().getWidth();
                double dh = path.getBounds2D().getHeight() - legend.line.getBounds2D().getHeight();
                boolean xyAline = ChartUtils.equals(dx, 0.0) || ChartUtils.equals(dy, 0.0);
                boolean sizeSame = ChartUtils.equals(dw, 0.0) && ChartUtils.equals(dh, 0.0);
                // 同高宽　且　在XY一个方向上对齐　且　距离不太远
                if (sizeSame && xyAline && dist < 0.1 * chart.getWidth()) {
                    Rectangle2D area = getLegendArea(path);
                    if (area != null) {
                        addLegend(color, area);
                        isLegend = true;
                        continue;
                    }
                }
            }
        }
        return isLegend;
    }

    private void detectLegendText() {
        if (chart != null) {
            ChartTitleLegendScaleParser.getChunksGroupInfos(current.getAllTextChunks(), chart.groupInfo.legend);
        }
    }

    private void detectAxisText() {
        if (chart != null) {
            ChartTitleLegendScaleParser.getChunksGroupInfos(current.getAllTextChunks(), chart.groupInfo.axis);
        }
    }

    private boolean detectChartText() {
        if (chart != null) {
            Rectangle2D box = chart.getArea();
            List<TextChunk> chunks = current.getAllTextChunks();
            List<TextChunk> chunksIn = new ArrayList<>();
            for (TextChunk chunk : chunks) {
                if (box.contains(chunk)) {
                    chunksIn.add(chunk);
                }
            }
            ChartTitleLegendScaleParser.getChunksGroupInfos(chunksIn, chart.groupInfo.axis);
            if (!chunksIn.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private void resetStatus() {
        status = Status.UNKNOWN;
        chart = null;
        chartStart = null;
        chartContentGroup = null;
        checkArea.setRect(0, 0, 0, 0);
        fillColors.clear();
        strokeColors.clear();
        hAxisLines.clear();
        vAxisLines.clear();
        pathInfos.clear();
        paths.clear();
        segmentsColor.clear();
        circles.clear();
        arcs.clear();
        pies.clear();
        tris.clear();
        ocrs.clear();
        checkedGraphic = false;
        specialPathInfos.clear();
    }

    private void chartBegin(ContentGroup start) {
        status = Status.BEGIN_GRAPHICS;
        chart = new Chart();
        chart.setArea(null);
        chart.pageHeight = (float) pageContentGroup.getArea().getHeight();
        chartStart = start;
        mergeArea(start);
        chartContentGroup = new ContentGroup(
                start.getGraphicsState(), start.getPageTransform(), true);
        chartContentGroup.add(start);
    }

    /**
     * 从当前current对象开始检测新Chart
     */
    private void detectNewChart() {
        if (current.hasOnlyPathItems() && isValidChartStart(current)) {
            chartBegin(current);
            if (!detectGraphics(true)) {
                resetStatus();
            }
        }
    }

    /**
     * 当前 chart 结束时 做相应有效性判断, 构造虚拟 Form 对象 和 检测内部区域的文字信息
     * 默认不传入参数 如果是位图检测的对象，则调用对应的TF函数
     */
    private void chartEnd() {
        chartEndTF(null);
    }

    /**
     * 当前 chart 结束时 做相应有效性判断, 构造虚拟 Form 对象 和 检测内部区域的文字信息
     */
    private void chartEndTF(DetectedChartTableTF obj) {
        if (status == Status.UNKNOWN) {
            return;
        } else if (status == Status.BEGIN_GRAPHICS) {
            if (chart.type != ChartType.PIE_CHART) {
                resetStatus();
                return;
            }
        }

        chartContentGroup.end();
        if (!chart.parserDetectArea) {
            chart.setArea(chartContentGroup.getArea());
        }

        if (checkTable()) {
            if (obj != null) {
                obj.label = "none_of_the_above";
                obj.setTypeID();
            }
            resetStatus();
            return;
        }

        if (!ChartUtils.isAreaNotSmall(pageContentGroup.getArea(), chart.getArea())) {
            resetStatus();
            return;
        }

        // 处理类似有图例对象的虚线等特殊对象
        dealWithSpecialPathInfos();

        chart.pathInfos = new ArrayList<>(pathInfos);

        // 如果是 form 的子部分 则用 root.getForm() 的 Resource
        if (form != null) {
            chart.form = buildForm(context.getNativeDocument(), form.getResources(), chartContentGroup);
            if (areaOverlapValid(chart.getArea(), formBox, 0.5)) {
                chart.setArea((Rectangle2D) formBox.clone());
            }
        }
        else {
//            chart.form = buildForm(context.document, context.document.getPage(pageIndex).getResources(), chartContentGroup);
        }

        try {
            chart.setLowerLeftArea(pageContentGroup.getPageTransform().createInverse().createTransformedShape(chart.getArea()).getBounds2D());
        } catch (NoninvertibleTransformException e) {
            e.printStackTrace();
        }

//        PDRectangle bbox = chart.form.getBBox();
//        bbox.setLowerLeftX((float) chart.getArea().getMinX());
//        bbox.setLowerLeftY((float) chart.getArea().getMinY());
//        bbox.setUpperRightX((float) chart.getArea().getMaxX());
//        bbox.setUpperRightY((float) chart.getArea().getMaxY());
//        chart.form.setBBox(bbox);

        chart.contentGroup = chartContentGroup;

        // OCR模块开发中暂时没有使用
        OCREngine.parserOCRInfos(chart, ocrs, false);

        // 从内容和结构信息判断chart是否无效
        if (isInValidChart()) {
            resetStatus();
            return;
        }

        verifyAxis();

        chart.chunksRemain = ChartUtils.mergeTextChunk(chart, chartContentGroup);
        mergeTextBaseTextTree(chart.chunksRemain);
        this.charts.add(chart);
        resetStatus();
    }

    /**
     * 对环形图上的百分数尝试　水平方向向前扩展第一个非空TextChunk
     * @param chunks
     */
    private void mergeTextBaseTextTree(List<TextChunk> chunks) {
        // 判断数据有效性
        if (chart == null || chunks == null || chunks.isEmpty()) {
            return;
        }
        // 过滤掉非饼图
        if (chart.type != ChartType.PIE_CHART) {
            return;
        }
        // 过滤掉非环形图
        if (chart.pieInfo.parts.get(0).isPie) {
            return;
        }

        for (int i = 0; i < chunks.size(); i++) {
            TextChunk chunk = chunks.get(i);
            if (chunk == null) {
                continue;
            }
            String text = chunk.getText();
            text = text.trim();
            if (StringUtils.isEmpty(text)) {
                continue;
            }
            boolean isNumber = ChartTitleLegendScaleParser.isStringNumber(text);
            if (!isNumber || text.length() == 1) {
                continue;
            }
            if (!text.endsWith("%")) {
                continue;
            }
            int nearChunkPos = findNearChunk(chunks, i);
            if (nearChunkPos == -1) {
                continue;
            }
            TextChunk mergeChunk = chunks.get(nearChunkPos);
            mergeChunk.merge(chunk);
            chunks.set(nearChunkPos, mergeChunk);
            chunks.remove(i);
            i--;
        } // end for i
    }

    private int findNearChunk(List<TextChunk> chunks, int pos) {
        TextChunk base = chunks.get(pos);
        Rectangle2D boxBase = base.getBounds2D();
        double height = boxBase.getHeight();
        for (int i = pos - 1; i >= 0; i--) {
            TextChunk chunk = chunks.get(i);
            if (chunk == null) {
                return -1;
            }
            String text = chunk.getText().trim();
            if (StringUtils.isEmpty(text)) {
                continue;
            }
            if (text.endsWith("%")) {
                return -1;
            }
            Rectangle2D box = chunk.getBounds2D();
            double dy = box.getCenterY() - boxBase.getCenterY();
            if (Math.abs(dy) > height) {
                return -1;
            }
            if (boxBase.getMinX() >= box.getMaxX()) {
                return i;
            }
            else {
                return -1;
            }
        } // end for i
        return -1;
    }

    /**
     * 处理解析过程中保存的特殊 PathInfo 对象 检测是否和相应的图例对象相匹配
     */
    private void dealWithSpecialPathInfos() {
        final Iterator<ChartPathInfo> each = pathInfos.iterator();
        while (each.hasNext()) {
            ChartPathInfo pathInfo = each.next();
            if (pathInfo.type == PathInfo.PathType.DASH_LINE||
                    pathInfo.type == PathInfo.PathType.HORIZON_LONG_LINE) {
                boolean matchLegend = chart.legends.stream().anyMatch(legend ->
                        legend.color.equals(pathInfo.color));
                if (!matchLegend) {
                    each.remove();
                }
                else {
                    pathInfo.type = PathInfo.PathType.LINE;
                    if (chart.type != ChartType.LINE_CHART && chart.type != ChartType.CURVE_CHART) {
                        chart.type = ChartType.COMBO_CHART;
                    }
                }
            }
        }

        // 处理多个虚线段组成的图例对象
        int n = chart.legends.size();
        double height = chart.getHeight();
        double width = chart.getWidth();
        boolean hasMergeLegend = false;
        for (int i = 0; i < n - 1; i++) {
            Chart.Legend legendA = chart.legends.get(i);
            Chart.Legend legendB = chart.legends.get(i + 1);
            // 跳过相邻不同颜色的对象
            if (!legendA.color.equals(legendB.color)) {
                continue;
            }
            // 跳过不同行的候选图例
            double dy = legendA.line.getCenterY() - legendB.line.getCenterY();
            if (Math.abs(dy) >= 0.01 * height) {
                continue;
            }
            // 跳过不相邻的候选图例
            double dx = legendB.line.getMinX() - legendA.line.getMaxX();
            if (dx <= 0.0 || dx >= 0.05 * width) {
                continue;
            }
            // 将连续的虚线段组合起来
            Rectangle2D.union(legendA.line, legendB.line, legendB.line);
            legendA.line = null;
            hasMergeLegend = true;
        } // end for i
        final Iterator<Chart.Legend> eachLegend = chart.legends.iterator();
        while (eachLegend.hasNext()) {
            Chart.Legend legend = eachLegend.next();
            if (legend.line == null) {
                eachLegend.remove();
            }
        }
        // 如果没有合并图例　则直接返回
        if (!hasMergeLegend) {
            return;
        }

        // 鉴别连续同颜色小矩形对象是否为能构成水平折线对象或是其他辅助对象
        int nbar = chart.barsInfos.size();
        List<Integer> matchPos =  new ArrayList<>();
        int istart = -1;
        for (int i = 0; i < nbar  - 1; i++) {
            ChartPathInfo pathA = chart.barsInfos.get(i);
            ChartPathInfo pathB = chart.barsInfos.get(i + 1);
            // 跳过不同色的
            if (!pathA.color.equals(pathB.color)) {
                if (istart != -1) {
                    matchPos.add(i);
                    istart = -1;
                }
                continue;
            }
            Rectangle2D boxA = pathA.path.getBounds2D();
            Rectangle2D boxB = pathB.path.getBounds2D();
            // 跳过不同行的  跳过不同高度的  跳过不相邻的对象
            double dy = boxA.getCenterY() - boxB.getCenterY();
            double dh = boxA.getHeight() - boxB.getHeight();
            double dx = boxA.getCenterX() - boxB.getCenterX();
            if (Math.abs(dy) >= 0.01 * height ||
                    Math.abs(dh) >= 0.01 * height ||
                    Math.abs(dx) >= 0.075 * width) {
                if (istart != -1) {
                    matchPos.add(i);
                    istart = -1;
                }
                continue;
            }
            if (istart == -1) {
                istart = i;
                matchPos.add(istart);
            }
            if ( i == nbar - 2 && istart != -1) {
                matchPos.add(i + 1);
            }
        } // end for i

        // 尝试将近似水平方向的连续小矩形组合成水平线对象
        int iend = 0;
        for(int i = 0; i < matchPos.size(); i+=2) {
            istart = matchPos.get(i);
            iend = matchPos.get(i + 1);
            ChartPathInfo pathA = chart.barsInfos.get(istart);
            Color pColor = pathA.color;
            // 如果存在匹配的图例
            if (chart.legends.stream().anyMatch(legend -> legend.color.equals(pColor))) {
                ChartPathInfo pathB = chart.barsInfos.get(iend);
                double xmin = Math.min(pathA.path.getBounds().getMinX(), pathB.path.getBounds().getMinX());
                double xmax = Math.max(pathA.path.getBounds().getMaxX(), pathB.path.getBounds().getMaxX());
                double y = pathA.path.getBounds().getCenterY();
                GeneralPath pathLine = new GeneralPath();
                pathLine.moveTo(xmin, y);
                pathLine.lineTo(xmax, y);
                ChartPathInfosParser.savePathIntoPathInfo(
                        pathLine, PathInfo.PathType.LINE, pColor, paths, pathInfos);
                for (int j = istart; j <= iend; j++) {
                    chart.barsInfos.set(j, null);
                } // end for j
            } // end if
        } // end for i
        // 删除合并的对象
        final Iterator<ChartPathInfo> eachPath = chart.barsInfos.iterator();
        while (eachPath.hasNext()) {
            ChartPathInfo pathInfo = eachPath.next();
            if (pathInfo == null) {
                eachPath.remove();
            }
        }
    }

    /**
     * 检测给定 PathInfo 是否和 图例对象相匹配  如果匹配 则设置类型并保存至普通 ChartPathInfo集中
     * @param pathInfo
     * @param type
     */
    private void matchPathInfoWithLegend(ChartPathInfo pathInfo, PathInfo.PathType type) {
        for (int i = 0; i <  chart.legends.size(); i++) {
            Chart.Legend legend = chart.legends.get(i);
            if (legend.color.equals(pathInfo.color)) {
                pathInfo.type = type;
                // 逆序匹配
                int n = pathInfos.size();
                int ithLastMatch = n;
                for (int j = n - 1; j >= 0; j--) {
                    ChartPathInfo pathInfoB = pathInfos.get(j);
                    if (!pathInfoB.color.equals(legend.color)) {
                        continue;
                    }
                    ithLastMatch = j + 1;
                    break;
                }
                pathInfos.add(ithLastMatch, pathInfo);
                if (chart.type != ChartType.LINE_CHART && chart.type != ChartType.CURVE_CHART) {
                    chart.type = ChartType.COMBO_CHART;
                }
                return;
            }
        } // end for i
    }

    private boolean checkTable() {
        // 检查是不是table
        if (chart.type == ChartType.COLUMN_CHART) {
            boolean isTableBackground = true;
            for (ChartPathInfo pathInfo : pathInfos) {
                if (pathInfo.path.getBounds2D().getWidth() < chart.getArea().getWidth() * 0.9) {
                    isTableBackground = false;
                    break;
                }
            }
            if (isTableBackground) {
                return true;
            }
        }

        double textArea = 0;
        List<TextChunk> textChunks = ChartUtils.getChartTextChunks(chart, chartContentGroup);
        for (TextChunk textChunk : textChunks) {
            textArea += textChunk.getWidth() * textChunk.getHeight();
        }
        double chartArea = chart.getArea().getWidth() * chart.getArea().getHeight();
        double textPercent = textArea * 100 / chartArea;
//        logger.info("textPercent {}", textPercent);
        return textPercent > 35;
    }

    /**
     * 判断Chart是否无效  柱状图内容信息少于4个 或 只有一个柱状 (后续继续添加其他的规则)
     * @return
     */
    private boolean isInValidChart() {
        if (chart.type == ChartType.COLUMN_CHART || chart.type == ChartType.BAR_CHART) {
            // 判断内容信息个数是否过少
            long ntexts = 0;
            if (chartContentGroup != null) {
                List<TextChunk> chunks = chartContentGroup.getAllTextChunks();
                ntexts = chunks .stream() .filter(
                        (chunk) -> (!chunk.getText().equals("") && !chunk.getText().equals(" "))).count();
            }
            int nocrs = chart.ocrs.size();
            if (ntexts + nocrs < 4) {
                return true;
            }

            // 判断是否只有一个矩形对象
            if (chart.pathInfos.size() == 1 && chart.barsInfos.isEmpty()) {
                ChartPathInfo path = chart.pathInfos.get(0);
                int npts = ChartPathInfosParser.getPtsSizeOfPath(path.path);
                if (npts <= 5) {
                    return true;
                }
            }

            // 判断是否具有表格特征
            if (ChartPathInfosParser.isColumnarInValid(
                    pathInfos, chart.type == ChartType.BAR_CHART,
                    !chart.legends.isEmpty(), chart.getArea().getWidth(), chart.getArea().getHeight())) {
                return true;
            }
        }

        // 基于检测Chart模型检测信息　判断无效性
        if (isInValidChartBaseDetectInfo()) {
            return true;
        }

        return false;
    }

    /**
     * 如果已经调用位图内Chart检测模型检测出了Chart对象区域信息　则参考检测出的区域信息, 判断无效性
     * @return
     */
    private boolean isInValidChartBaseDetectInfo() {
        if (pageDetectedInfo == null || pageDetectedInfo.isEmpty()) {
            return false;
        }

        // 获取pathInfo对象的包围框
        Rectangle2D pathInfoMergeBox = ChartPathInfosParser.getChartPathInfosMergeBox(chart);
        if (pathInfoMergeBox == null) {
            return true;
        }

        // 获取所有文字块
        List<TextChunk> chunks = chartContentGroup.getAllTextChunks();
        if (chunks.isEmpty()) {
            return true;
        }
        if (chunks.stream().allMatch(chunk -> StringUtils.isEmpty(chunk.getText()))) {
            return true;
        }

        // 判断文字块是否和图形块相交
        Rectangle2D tBox = (Rectangle2D) chunks.get(0).getBounds().clone();
        chunks.stream().forEach(chunk -> Rectangle2D.union(tBox, chunk.getBounds(), tBox));
        if (!pathInfoMergeBox.intersects(tBox)) {
            if (tBox.getY() >= pathInfoMergeBox.getMaxY() || tBox.getMaxY() <= pathInfoMergeBox.getY()) {
                return true;
            } else {
                return false;
            }
        }
        Rectangle2D rectC = new Rectangle2D.Float(0.0f, 0.0f, 0.0f, 0.0f);
        Rectangle2D.intersect(pathInfoMergeBox, tBox, rectC);
        if (rectC.getHeight() <= 0.0 || rectC.getWidth() <= 0.0) {
            return true;
        }

        // 判断是否和某一个检测出的Chart区域相交
        /*
        boolean inDetectedChart = pageDetectedInfo.stream().anyMatch(
                obj -> ((DetectedChartTableTF)obj).area.intersects(tBox));
        if (!inDetectedChart) {
            return true;
        }
        */

        return false;
    }

    /**
     * 尝试解析圆形元素  可能是圆形图例对象或是散点元素
     * 目前用作检测图例  后续扩展到散点图元素
     * @param group
     * @return
     */
    private boolean parserCirclePath(ContentGroup group) {
        int n = circles.size();
        group.forEach(PathItem.class, pathItem -> {
            if (pathItem.isFill()) {
                isFillCircleElement(pathItem);
            }
        });
        return circles.size() - n > 0;
    }

    /**
     * 找出ContentGroup在给定区间范围内近似水平或垂直线对象
     * @param group
     * @return
     */
    private List<Line2D> findApproximationXYLine(ContentGroup group, Rectangle2D box) {
        List<Line2D> lines = new ArrayList<>();
        // 考虑只含有pathItem的contentgroup对象
        if (!group.hasOnlyPathItems()) {
            return lines;
        }

        // 过滤掉给定区域外的对象
        Rectangle2D area = group.getArea();
        GraphicsUtil.extendRect(area, 1);
        if (!box.contains(area)) {
            return lines;
        }

        // 遍历 pathItem集　找出候选水平或垂直line2d对象集
        List<Boolean> strokePathStatus = new ArrayList<>();
        group.forEach(PathItem.class, pathItem -> {
            if (!isWhite(pathItem.getColor())) {
                if (pathItem.isFill()) {
                    List<Line2D> flines = ChartPathInfosParser.isColumnarGridLine(pathItem);
                    if (flines != null) {
                       lines.addAll(flines);
                    }
                } else {
                    List<Line2D> slines = ChartPathInfosParser.isLineGridLine(pathItem.getItem());
                    if (slines != null) {
                        lines.addAll(slines);
                    }
                    else {
                        strokePathStatus.add(false);
                    }
                }
            } // end if
        });

        return lines;
    }

    /**
     * 判断给定PathItem是否为填充的圆形元素
     * @param pathItem
     */
    private void isFillCircleElement(PathItem pathItem) {
        Color color = pathItem.getRGBColor();
        ChartPathInfosParser.ArcObject arcobj = new ChartPathInfosParser.ArcObject();
        if (ChartPathInfosParser.isCircleLegend(pathItem)) {
            arcobj.color = color;
            arcobj.path = (GeneralPath)pathItem.getItem().clone();
            arcobj.angle = 2.0 * Math.PI;
            arcobj.r = 0.5 * arcobj.path.getBounds().getWidth();
            circles.add(arcobj);
        }
    }

    // 解析给定ContentGroup内部 PathItem 对象  判断图形元素的具体信息
    private void parserContentGroup(ContentGroup group) {
        // 存在某个子path对象不在区域范围内的 故跳过这一部分  可以过滤掉不显示的图形对象
        // 在GIC项目中某些ContentGroup 内有多个环形图对象  但是有些不显示 故过滤掉
        Rectangle2D box = group.getArea();

        GraphicsUtil.extendRect(box, 10);
        group.forEach(PathItem.class, pathItem -> {
            Rectangle2D pbox = pathItem.getItem().getBounds2D();
            GraphicsUtil.extendRect(pbox, 1);
            if (!box.intersects(pbox)) {
            }
            else if (pathItem.isFill()) {
                oldFillPath(pathItem);
            } else {
                oldStrokePath(pathItem);
            }
        });
    }

    /**
     * 根据上一次保存的状态信息回滚  用来处理图例对象出现后出现的其他图形对象的颜色和pathInfo信息
     */
    private void resetGraphicStatus() {
        // 颜色集回滚
        if (fillColors.size() > gStatus.fillColors.size()) {
            fillColors = new HashSet<>(gStatus.fillColors);
        }

        if (strokeColors.size() > gStatus.strokeColors.size()) {
            strokeColors = new HashSet<>(gStatus.strokeColors);
        }
        // 如果图形对象状态回滚　
        if (pathInfos.size() > gStatus.nPathInfos) {
            pathInfos.subList(gStatus.nPathInfos, pathInfos.size()).clear();
        }
    }

    /**
     * 判断给定ContentGroup的图形元素是否有效
     * @param group
     * @return
     */
    private boolean detectChartGraphicsValid(ContentGroup group) {
        // 保存当前图形对象解析状态
        saveGraphicsStatus();

        // 解析给定 ContentGroup
        parserContentGroup(group);

        // 判断新增的 PathInfo 是否和坐标轴匹配
        if (!judegPathInfosMatchAxis(gStatus.nPathInfos)) {
            resetGraphicStatus();
            return false;
        }

        // page中有时存在很多指示线段和水平坐标轴像似  在处理图形对象时 对候选水平坐标轴做有效性检测
        if (status == Status.BEGIN_GRAPHICS) {
            /*
            if (!ChartPathInfosParser.isCandidateAxisValid(hAxisLines, gStatus.nHAxis)) {
                return false;
            }
            */
        }
        else {
            // 如果当前chart类型确定后
            if (chart != null && chart.type != ChartType.UNKNOWN_CHART) {
                resetGraphicStatus();
                // 再次出现扇形对象 则为新的 Chart的开始
                if (arcs.size() > gStatus.nArcs) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 解析图形对象的有效性
     * @param checkAreaValid
     * @return
     */
    private boolean detectGraphics(boolean checkAreaValid) {
        // 如果当前 chart 或起 区域范围无效 则直接返回
        if (chart == null || chart.getArea() == null) {
            return true;
        }

        // 可能目前图形对象集的并集还没有构成有效Chart区域 故设置可以等构成有效区域后 再检测图形对象
        if (checkAreaValid && !isValidChartArea(pageContentGroup.getArea(), chart.getArea())) {
            return true;
        }

        // 如果是第一次检测图形对象  则将chartContentGroup的内容从开始到当前current都检测一遍
        if (!checkedGraphic) {
            boolean bUntilCurrent = false;
            resetChartContentGroup();
            List<ContentGroup> groups = chartContentGroup.getAllContentGroups(true);
            for (ContentGroup group : groups) {
                if (group == current) {
                    bUntilCurrent = true;
                }
                if (!detectChartGraphicsValid(group)) {
                    return false;
                }
                if (bUntilCurrent) {
                    break;
                }
            }
            checkedGraphic = true;
        }
        // 如果已经检测过 则只检测current
        else {
            if (!detectChartGraphicsValid(current)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 判断给定ContentGroup是否为白色填充对象
     * @param contentGroup
     * @return
     */
    private boolean hasWhiteFill(ContentGroup contentGroup) {
        for (ContentItem item : contentGroup.getItems()) {
            PathItem pathItem = (PathItem) item;
            if (pathItem.isFill() && isWhite(pathItem.getRGBColor())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 重置属于Chart的ContentGroup
     * 基于大部分Chart的开始为大的白色填充区域 过滤掉填充区域之前的对象
     */
    private void resetChartContentGroup() {
        // 如果 items 内对象过少 直接跳过
        int nItems = chartContentGroup.getItems().size();
        if (nItems <= 1) {
            return;
        }

        // 寻找大面积的白色填充区域, 这里很有可能是chart的开始
        int start = -1;
        for (int i = 0; i < nItems; i++) {
            ContentItem contentItem = chartContentGroup.getItems().get(i);
            if (!(contentItem instanceof ChildItem)) {
                continue;
            }
            ChildItem childItem = (ChildItem) contentItem;
            if (childItem.getItem().hasOnlyPathItems()) {
                if (hasWhiteFill(childItem.getItem())
                        && childItem.getItem().getArea().getWidth() > chart.getArea().getWidth() * 0.8) {
                    start = i;
                    break;
                }
            }
            if (start >= 0) {
                break;
            }
        }
        if (start > 0) {
            // 重新生成chartContentGroup
            chart.setArea(null);
            ContentGroup startGroup = (ContentGroup) chartContentGroup.getItems().get(start).getItem();

            ContentGroup newGroup = new ContentGroup(startGroup.getGraphicsState(), startGroup.getPageTransform(), true);
            for (int i = start; i < chartContentGroup.getItems().size(); i++) {
                ContentGroup item = (ContentGroup) chartContentGroup.getItems().get(i).getItem();
                newGroup.add(item);
                mergeArea(item);
            }
            chartContentGroup = newGroup;
        }
    }

    /**
     * 当出现文字信息时  解析Chart图形对象
     * (目前算法对出现的图形对象尽可能的做实时检测 故到出现文字信息时 可能图形对象已经解析完成了)
     */
    private void graphicsEnd() {
        // 如果没有解析过图形对象 则解析全部图形对象
        if (!checkedGraphic) {
            resetChartContentGroup();
            if (!detectGraphics(false)) {
                resetStatus();
                return;
            }
        }

        // 检测坐标轴信息和判断Chart类型
        checkAxis();
        checkChartType();

        // 判断图形对象是否和坐标轴匹配
        if (!judegPathInfosMatchAxis(0)) {
            chart.type = ChartType.UNKNOWN_CHART;
        }

        // 如果类型无法确定 则当前chart无效 重置所有状态
        if (chart.type == ChartType.UNKNOWN_CHART) {
            resetStatus();
        }
        // 如果事饼图 则适当扩充区域 一般饼图内部文字区域比饼图图形区域大
        else if (chart.type == ChartType.PIE_CHART) {
            GraphicsUtil.extendRect(checkArea, 0.25 * chart.getArea().getWidth());
        }
    }

    /**
     * 当 chart 类型确定后 判断指定范围 PathInfo集是否和坐标轴匹配
     * @param iStartPathInfo
     * @return
     */
    private boolean judegPathInfosMatchAxis(int iStartPathInfo) {
        if (chart == null || chart.type == ChartType.UNKNOWN_CHART) {
            return true;
        }
        else if (!ChartPathInfosParser.isPathInfosMatchAxis(pathInfos, iStartPathInfo, chart.hAxis, true) ||
                !ChartPathInfosParser.isPathInfosMatchAxis(pathInfos, iStartPathInfo, chart.lvAxis, false) ||
                !ChartPathInfosParser.isPathInfosMatchAxis(pathInfos, iStartPathInfo, chart.rvAxis, false)) {
            return false;
        }
        else {
            return true;
        }
    }

    /**
     * 基于收集到的坐标轴轴标判断给定line是否为有效坐标轴
     * @param line
     * @return
     */
    private boolean isAxisLineBaseAxisScaleLine(Line2D line) {
        // 判断数据有效性
        if (line == null || chart == null) {
            return false;
        }
        // 判断轴标个数
        int n = chart.axisScaleLines.size();
        if (n <= 1) {
            return true;
        }
        Rectangle2D lineBox = line.getBounds2D();
        boolean isXAxis = lineBox.getWidth() > lineBox.getHeight();
        boolean hasAxisScaleLine = false;
        // 适当扩展坐标轴线的区域
        GraphicsUtil.extendRect(lineBox, 2.0);
        // 遍历轴标 判断是否和相匹配的轴标相交
        for (int i = 0; i < n; i++) {
            Line2D scaleLine = chart.axisScaleLines.get(i);
            Rectangle2D box = scaleLine.getBounds2D();
            GraphicsUtil.extendRect(box, 2.0);
            // 水平轴标
            if (box.getWidth() > box.getHeight()) {
                if (!isXAxis) {
                    hasAxisScaleLine = true;
                    if (lineBox.intersects(box)) {
                        return true;
                    }
                }
            }
            else {
                if (isXAxis) {
                    hasAxisScaleLine = true;
                    if (lineBox.intersects(box)) {
                        return true;
                    }
                }
            }
        } // end for i
        // 如果有坐标轴坐标　但是没有相交　则返回 false
        if (hasAxisScaleLine) {
            return false;
        }
        else {
            return true;
        }
    }

    /**
     * 没有候选坐标轴的情况下　尝试从pathInfos中帅选出高概率的坐标轴线
     * @return
     */
    private boolean filterAxisLine() {
        List<ChartPathInfo> barInfos = new ArrayList<>();
        pathInfos.stream().forEach(pathInfo -> {
            if (pathInfo.type == PathInfo.PathType.COLUMNAR || pathInfo.type == PathInfo.PathType.BAR) {
                barInfos.add(pathInfo);
            }
        });
        // 只有一个柱状对象　暂时无法精准判断 后续可以改进
        if (barInfos.size() <= 1) {
            return false;
        }
        boolean isVertical = ChartPathInfosParser.maxAreaColumnarVertical(barInfos);
        if (!isVertical) {
            return false;
        }

        Rectangle2D box = ChartPathInfosParser.getPathInfosMergeBox(pathInfos);
        for (int i = 0; i < barInfos.size(); i++) {
            Rectangle2D barBox = barInfos.get(i).path.getBounds2D();
            if (box != null && barBox.getHeight() <= 0.02 * box.getHeight() &&
                    barBox.getWidth() >= 0.8 * box.getWidth()) {
                Line2D line = new Line2D.Double(barBox.getMinX(), barBox.getCenterY(),
                        barBox.getMaxX(), barBox.getCenterY());
                hAxisLines.add(line);
            }
        } // end for i
        if (hAxisLines.isEmpty()) {
            return false;
        }
        else {
            return true;
        }
    }

    /**
     * 检测坐标轴信息
     */
    private void checkAxis() {
        // (横/竖)坐标轴是(横/竖)方向上最长的线, 横轴最多1个, 竖轴最多2个
        if (hAxisLines.isEmpty() && vAxisLines.isEmpty()) {
            // 尝试从pathInfos中找出高概率坐标轴线对象
            if (!filterAxisLine()) {
                return;
            }
        }
        // 找出长度最大, 并且离chart中心最远的线, 那就是坐标轴
        Line2D lvAxis = null;
        Line2D rvAxis = null;
        Rectangle2D pathBox = ChartPathInfosParser.getPathInfosMergeBox(pathInfos);
        if (pathBox == null) {
            pathBox = chart.getArea();
        }
        for (Line2D line : vAxisLines) {
            if (!isAxisLineBaseAxisScaleLine(line)) {
                continue;
            }
            if (line.getX1() < pathBox.getCenterX()) {
                if (lvAxis == null || line.getX1() < lvAxis.getX1()) {
                    lvAxis = line;
                }
            } else {
                if (rvAxis == null || line.getX1() > rvAxis.getX1()) {
                    rvAxis = line;
                }
            }
        }

        double zero = 2.0;
        Line2D hAxis = null;
        boolean HLAxisInValid = true, HRAxisInValid = true;
        for (Line2D line : hAxisLines) {
            if (!isAxisLineBaseAxisScaleLine(line)) {
                continue;
            }
            // 判断是否和垂直坐标轴在容差范围内相交
            HLAxisInValid = (line != null && lvAxis != null &&
                    !ChartPathInfosParser.isHorizonVerticalLineIntersect(line, lvAxis, zero));
            HRAxisInValid = (hAxis != null && rvAxis != null &&
                    !ChartPathInfosParser.isHorizonVerticalLineIntersect(hAxis, rvAxis, zero));
            if (HLAxisInValid || HRAxisInValid) {
                continue;
            }
            if (hAxis == null || line.getY1() > hAxis.getY1()) {
                hAxis = line;
            }
        }
        // 判断两个垂直坐标轴是否等高
        boolean LRAxisINValid = (lvAxis != null && rvAxis != null &&
                Math.abs(lvAxis.getBounds2D().getHeight() - rvAxis.getBounds2D().getHeight()) >= zero);
        if (!LRAxisINValid) {
            chart.hAxis = hAxis;
            chart.lvAxis = lvAxis;
            chart.rvAxis = rvAxis;
        }

        // 清空
        hAxisLines.clear();
        vAxisLines.clear();
    }

    private void verifyAxis() {
        // 如果轴标为空
        if (!chart.axisScaleLines.isEmpty()) {
            return;
        }
        double ymin = chart.getTop();
        double ymax = chart.getBottom();
        double xmin = chart.getLeft();
        double xmax = chart.getRight();
        double coef = 0.05;
        List<TextChunk> textChunks = ChartUtils.getChartTextChunks(chart, chart.contentGroup);
        if (chart.hAxis != null && chart.lvAxis == null && chart.rvAxis == null) {
            double y = chart.hAxis.getY1();
            if (y > ymax - coef * (ymax - ymin)) {
                boolean hasText = textChunks.stream().anyMatch(textChunk -> textChunk.getCenterY() > y);
                if (!hasText) {
                    chart.hAxis = null;
                }
            }
            else if (y < ymin +  coef * (ymax - ymin)) {
                boolean hasText = textChunks.stream().anyMatch(textChunk -> textChunk.getCenterY() < y);
                if (!hasText) {
                    chart.hAxis = null;
                }
            }
        } else if (chart.hAxis == null && chart.lvAxis != null && chart.rvAxis == null) {
            double x = chart.lvAxis.getX1();
            if (x < xmin + coef * (xmax - xmin)) {
                boolean hasText = textChunks.stream().anyMatch(textChunk -> textChunk.getCenterX() < x);
                if (!hasText) {
                    chart.lvAxis = null;
                }
            }
        } else if (chart.hAxis == null && chart.lvAxis == null && chart.rvAxis != null) {
            double x = chart.rvAxis.getX1();
            if (x > xmax - coef * (xmax - xmin)) {
                boolean hasText = textChunks.stream().anyMatch(textChunk -> textChunk.getCenterX() > x);
                if (!hasText) {
                    chart.rvAxis = null;
                }
            }
        }
    }

    /**
     * 检测Chart的类型信息
     */
    private void checkChartType() {
        // 如果类型已经确定
        if (chart.type != ChartType.UNKNOWN_CHART) {
            return;
        }

        // 根据Chart 内部折线　圆柱　面积　饼图　的情况　设置类型
        ChartType itype = ChartType.UNKNOWN_CHART;
        if (!paths.isEmpty()) {
            // 判断PathInfo集中是否有无效的部分 过滤掉
            ChartPathInfosParser.resetPathInfos(pathInfos, paths);

            boolean bHasLine = false;
            boolean bHasCurve = false;
            boolean bHasBar = false;
            boolean bHasColumnar = false;
            boolean bHasArea = false;
            boolean bHasMultiArea = false;
            boolean bBar = false;
            int icountArea = 0;
            // 统计每一种类型的个数
            for (PathInfo.PathType type : paths) {
                if (type == PathInfo.PathType.LINE) {
                    bHasLine = true;
                }
                else if (type == PathInfo.PathType.CURVE) {
                    bHasCurve = true;
                }
                else if (type == PathInfo.PathType.COLUMNAR) {
                    bBar = true;
                }
                else if (type == PathInfo.PathType.AREA) {
                    icountArea++;
                    bHasArea = true;
                }
            } // end for
            if (icountArea >= 2) {
                // 检测是否构成重叠面积图  可能同种颜色面积图 分成几个子部分 这种情况不构成重叠面积图
                bHasMultiArea = ChartPathInfosParser.isAreaPathsOverlap(pathInfos);
            }

            if (bBar) {
                // 检查柱状的数量
                List<PathInfo> list = new ArrayList<>();
                pathInfos.forEach(path -> {
                    List<PathInfo> infos = BarParser.parseBarPath(path.path);
                    if (infos != null) {
                        list.addAll(infos);
                    }
                });
                if (list.size() < 2) {
                    bBar = false;
                }
            }

            // 细分柱状图 分为水平和垂直 前面只统计了一级类型 bBar
            if (bBar) {
                if (ChartPathInfosParser.isColumarPathsVertical(pathInfos)) {
                    bHasBar = true;
                } else {
                    bHasColumnar = true;
                }
            }

            // 分析判断类型
            itype = judgeChartType(bHasLine, bHasCurve, bHasBar, bHasColumnar, bHasArea, bHasMultiArea);
            if (itype == ChartType.UNKNOWN_CHART) {
                return;
            }

            // 判断是否为瀑布图
            if (ChartPathInfosParser.isPathsAline(pathInfos, true)) {
                chart.isWaterFall = true;
            }

            // 判断是否为饼图
            if (ChartPathInfosParser.isPie(pies, arcs, tris)) {
                chart.type = ChartType.PIE_CHART;
                ChartPathInfosParser.savePiesInfo(pies, arcs, chart.pieInfo);
                pathInfos.clear();
                chart.lvAxis = null;
                chart.rvAxis = null;
                chart.hAxis = null;
            }
            // 如果非饼图的chart没有检测出　坐标或图例等信息　则说明是无效chart
           else if (chart.isChart()) {
                // 一般折线图都有　至少一个坐标轴 饼图雷达图没有坐标轴
                if (itype == ChartType.LINE_CHART && chart.hAxis == null &&
                        chart.lvAxis == null && chart.rvAxis == null) {
                }
                else {
                    chart.type = itype;
                }
            }

            // 如果最终判断为混合图　则将子类型保存起来
            if (chart.type == ChartType.COMBO_CHART) {
                if (bHasLine) {
                    chart.subTypes.add(ChartType.LINE_CHART);
                }
                if (bHasCurve) {
                    chart.subTypes.add(ChartType.CURVE_CHART);
                }
                if (bHasBar) {
                    chart.subTypes.add(ChartType.BAR_CHART);
                }
                if (bHasColumnar) {
                    chart.subTypes.add(ChartType.COLUMN_CHART);
                }
                if (bHasArea) {
                    chart.subTypes.add(ChartType.AREA_CHART);
                }
                if (bHasMultiArea) {
                    chart.subTypes.add(ChartType.AREA_OVERLAP_CHART);
                }
            } // end if
        } // end if
        // 饼图放后面　因为其在Chart中出现的频率不高 可以提高效率
        else if (ChartPathInfosParser.isPie(pies, arcs, tris)) {
            chart.type = ChartType.PIE_CHART;
            ChartPathInfosParser.savePiesInfo(pies, arcs, chart.pieInfo);
            chart.lvAxis = null;
            chart.rvAxis = null;
            chart.hAxis = null;
        }
        // 判断是否为3D效果图表
        else if (ChartPathInfosParser.is3DChart(chart)) {
            chart.type = ChartType.BITMAP_3D_CHART;
            chart.is3DChart = true;
            chart.legends.clear();
        }
        paths.clear();
        pies.clear();
        circles.clear();
        arcs.clear();
        pies.clear();
        tris.clear();
    }

    /**
     * 待完全解析完Chart后　内部有效图形对象已经解析好　可以更加精准判断类型
     * @param charts
     */
    private void judgeChartsType(List<Chart> charts) {
        for (Chart chart : charts) {
            judgeChartType(chart);
        }
    }

    /**
     * 判断给定Chart的类型
     * @param chart
     */
    private void judgeChartType(Chart chart) {
        // 饼图或位图很特殊　直接返回
        if (chart.type == ChartType.BITMAP_CHART ||
                chart.type == ChartType.PIE_CHART ||
                chart.type == ChartType.UNKNOWN_CHART) {
            return;
        }

        int countLine = 0, countCurve = 0, countBar = 0, countColumnar = 0, countArea = 0;
        for (ChartPathInfo pathInfo : chart.pathInfos) {
            PathInfo.PathType type = pathInfo.type;
            switch (type) {
                case LINE:
                    countLine++;
                    break;
                case CURVE:
                    countCurve++;
                    break;
                case BAR:
                    countBar++;
                    break;
                case COLUMNAR:
                    countColumnar++;
                    break;
                case AREA:
                    countArea++;
                    break;
                default:
                    break;
            }
        }
        boolean bHasLine = countLine >= 1;
        boolean bHasCurve = countCurve >= 1;
        boolean bHasBar = countBar >= 1;
        boolean bHasColumnar = countColumnar >= 1;
        boolean bHasArea = countArea >= 1;
        boolean bHasMultiArea = countArea >= 2;
        chart.type = judgeChartType(bHasLine, bHasCurve, bHasBar, bHasColumnar, bHasArea, bHasMultiArea);
    }

    /**
     * 给定图形对象状态　判断类型
     * @param bHasLine
     * @param bHasCurve
     * @param bHasBar
     * @param bHasColumnar
     * @param bHasArea
     * @param bHasMultiArea
     * @return
     */
    private ChartType judgeChartType(
            boolean bHasLine,
            boolean bHasCurve,
            boolean bHasBar,
            boolean bHasColumnar,
            boolean bHasArea,
            boolean bHasMultiArea) {
        // 统计一级子类型
        boolean bLine = (bHasLine || bHasCurve);
        boolean bBar = (bHasBar || bHasColumnar);
        boolean bArea = (bHasArea || bHasMultiArea);
        ChartType itype = ChartType.UNKNOWN_CHART;

        if (!bLine && !bBar && !bArea) {
            return itype;
        }
        // 先在一级子类型上划分　再在二级子类型上划分
        else if (bLine && !bBar && !bArea) {
            if (bHasLine) {
                itype = ChartType.LINE_CHART;
            }
            else {
                itype = ChartType.CURVE_CHART;
            }
        }
        else if (!bLine && bBar && !bArea) {
            if (bHasBar) {
                itype = ChartType.BAR_CHART;
            }
            else {
                itype = ChartType.COLUMN_CHART;
            }
        }
        else if (!bLine && !bBar && bArea) {
            if (bHasMultiArea) {
                itype = ChartType.AREA_OVERLAP_CHART;
            }
            else {
                itype = ChartType.AREA_CHART;
            }
        }
        else {
            itype = ChartType.COMBO_CHART;
        }
        return itype;
    }

    private void addLegend(Color color, Rectangle2D area) {
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

    private void addLine(Line2D line, List<Line2D> toLines) {
        toLines.add(line);
    }

    /**
     * 判断是否为虚线 如果是保存起来  有可能为辅助线或是有对应图例的有效直线对象
     * @param pathItem
     * @return
     */
    private boolean isDashLine(PathItem pathItem) {
        if (pathItem.getLineDashPattern().getDashArray().length == 0) {
            return false;
        }

        // 判断是否为虚拟垂直直线对象
        if (ChartPathInfosParser.isVerticalLine(pathItem.getItem())) {
            return true;
        }

        // 判断是否为水平方向的长直线
        return isHorizonMeanLine(pathItem, PathInfo.PathType.DASH_LINE);
    }

    /**
     * 判断给定的PathItem是否为已保存的pathInfo中的某个柱状对象包围线 并做相应的处理
     * @param pathItem
     * @return
     */
    private boolean isBarBoxLine(PathItem pathItem) {
        if (pathInfos.isEmpty()) {
            return false;
        }
        // 判断是否为虚线对象　如果是虚线对象且恰好包围某个矩形对象
        // 通常为辅助性说明的矩形填充对象　可以删除以免干扰常规柱状
        boolean deleteBar = pathItem.getLineDashPattern().getDashArray().length > 0;
        return isBarBoxLine(pathItem, deleteBar);
    }

    /**
     * 判断给定的PathItem是否为已保存的pathInfo中的某个柱状对象包围线
     * 根据输入参数deleteBar控制是否删除被包围的矩形对象
     * @param pathItem
     * @param deleteBar
     * @return
     */
    private boolean isBarBoxLine(PathItem pathItem, boolean deleteBar) {
        List<Double> xsA = new ArrayList<>();
        List<Double> ysA = new ArrayList<>();
        List<Double> xsB = new ArrayList<>();
        List<Double> ysB = new ArrayList<>();
        GeneralPath path = (GeneralPath) pathItem.getItem().clone();
        ChartContentScaleParser.getLinePathPoints(path, null, xsA, ysA);
        int nA = xsA.size();
        // 遍历pathInfos   检测是否存在被给定pathItem恰好包围的矩形对象
        final Iterator<ChartPathInfo> each = pathInfos.iterator();
        while (each.hasNext()) {
            ChartPathInfo pathInfo = each.next();
            if (pathInfo.type == PathInfo.PathType.COLUMNAR | pathInfo.type == PathInfo.PathType.BAR) {
                ChartContentScaleParser.getLinePathPoints(pathInfo.path, null, xsB, ysB);
                int nB = xsB.size();
                // 如果点数接近　则判断是否近似相等
                if (Math.abs(nA - nB) >= 2) {
                    continue;
                }
                // 如果一个对象和其边界同时出现的情况　过滤掉边界
                boolean same = true;
                for (int i = 0; i < Math.min(nA, nB); i++) {
                    if (!ChartUtils.equals(xsA.get(i), xsB.get(i)) || !ChartUtils.equals(ysA.get(i), ysB.get(i))) {
                        same = false;
                        break;
                    }
                }
                if (same) {
                    // 如果参数设置为去除　被包围的矩形对象　通常当包围线为虚线时，为注释性矩形，删除掉
                    if (deleteBar) {
                        each.remove();
                    }
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 判断给定 PathItem 对象是否为水平方向的长直线  如果是则保存至特殊 ChartPathInfo 集中
     * @param pathItem
     * @param type
     * @return
     */
    private boolean isHorizonMeanLine(PathItem pathItem, PathInfo.PathType type) {
        // 判断是否为水平方向的长直线
        GeneralPath path = (GeneralPath) pathItem.getItem().clone();
        if (ChartPathInfosParser.isHorizonLongLine(path, chart)) {
            ChartPathInfosParser.savePathIntoPathInfo(path, type, pathItem.getColor(), null, pathInfos);
            //ChartPathInfosParser.savePathIntoPathInfo(path, type, pathItem.getColor(), null, specialPathInfos);
            return true;
        }
        else {
            return false;
        }
    }

    /**
     * 判断给定PathItem 是否为有效候选坐标轴
     * @param pathItem
     * @return
     */
    private boolean isAxisLine(PathItem pathItem) {
        if (pathItem.getLineDashPattern().getDashArray().length != 0) {
            return false;
        }
        GeneralPath path = pathItem.getItem();
        if (isAxisLine(path)) {
            return true;
        }

        // 取出 Path 内部一个或多个可能的坐标轴候选对象
        List<GeneralPath> axisPaths = ChartPathInfosParser.getFixAxisAndScale(path, chart);
        if (axisPaths != null) {
            int numAxisLine = 0;
            for (GeneralPath candidatePath : axisPaths) {
                if (isAxisLine(candidatePath)) {
                    numAxisLine++;
                }
            }
            return numAxisLine >= 1;
        }
        return false;
    }

    private boolean isAxisLine(GeneralPath path) {
        Line2D line = GraphicsUtil.getLine(path);
        if (line == null) {
            line = ChartPathInfosParser.getColumnLine(path);
        }
        if (line != null) {
            return canAddToAxisLines(line);
        }
        else {
            return false;
        }
    }

    private boolean canAddToAxisLines(Line2D line) {
        //判断是水平线还是竖直线, 横/竖坐标轴必须大于chart长度/宽度的一半
        // 碰到水平坐标轴长度只有area的0.37倍  故系数调整为　0.3
        double zero = 2.0;
        if (GraphicsUtil.isHorizontal(line) && line.getBounds2D().getWidth() >= 10.0 &&
                (chart.getArea() != null && ChartUtils.getLineLength(line) > 0.25 * chart.getArea().getWidth())) {
            addLine(line, hAxisLines);
            return true;
        } else if (GraphicsUtil.isVertical(line) && line.getBounds2D().getHeight() >= 10.0 &&
                (chart.getArea() != null && ChartUtils.getLineLength(line) > 0.25 * chart.getArea().getHeight())) {
            addLine(line, vAxisLines);
            return true;
        }
        else {
            return false;
        }
    }

    /**
     * 解析折线对象
     * @param pathItem
     */
    private void dealWithLine(PathItem pathItem) {
        Color color = pathItem.getRGBColor();
        GeneralPath path = pathItem.getItem();
        // 当前Path的具体类型
        PathInfo.PathType[] type = {PathInfo.PathType.UNKNOWN};
        int n = segmentsColor.size();

        // 检测是否为折线
        if (ChartPathInfosParser.isLineInChart(path, chart, type)) {
            // 判断当前path 是否和组合segmentsPath相匹配且可以连接
            // 如果匹配　则将组合segmentsPath保存为PathInfo
            // 判断颜色是否相同
            if (n >= 1 && segmentsColor.get(n - 1).equals(color)) {
                if (ChartPathInfosParser.canPathCompWithSegmentsPath(path, segmentsPath)) {
                    ChartPathInfosParser.savePathIntoPathInfo(segmentsPath, type[0], color, paths, pathInfos);
                    segmentsPath.reset();
                }
            }
            ChartPathInfosParser.savePathIntoPathInfo(path, type[0], color, paths, pathInfos);
        } else {
            // 测试当前path为分段绘制折线的中间一部分　　连接起来可能构成　有效的折线
            boolean addSeg = ChartPathInfosParser.checkPathInSegmentsPath(path, segmentsPath);
            if (addSeg) {
                if (n == 0 || (n >= 1 && !segmentsColor.get(n - 1).equals(color))) {
                    segmentsColor.add(color);
                }
            }
            if ( ChartPathInfosParser.isSegmentsPathIsLineInChart(segmentsPath, chart, type) )
            {
                // 如果已经存在　则将长度最长的组合Path保存起来 方便以后插值数值信息
                boolean add = false;
                int nPath = pathInfos.size();
                for (int ip = nPath - 1; ip >= 0; ip--) {
                    ChartPathInfo pInfo = pathInfos.get(ip);
                    if (pInfo.color.equals(color) &&
                            (pInfo.type == PathInfo.PathType.CURVE || pInfo.type == PathInfo.PathType.LINE) &&
                            ChartPathInfosParser.getPtsSizeOfPath(segmentsPath) >
                                    ChartPathInfosParser.getPtsSizeOfPath(pInfo.path)) {
                        pInfo.path = (GeneralPath)segmentsPath.clone();
                        add = true;
                        break;
                    }
                }  // end for ip
                if (!add) {
                    ChartPathInfosParser.savePathIntoPathInfo(segmentsPath, type[0], color, paths, pathInfos);
                }
            }  // end if
            else {
                ChartPathInfosParser.canSegmentsPathCompPathInfos(pathInfos, segmentsPath, color);
            }
        } // end else
    }

    /**
     * 解析StrokePath 对象 (即非填充区域)
     * @param pathItem
     */
    private void oldStrokePath(PathItem pathItem) {
        Color color = pathItem.getRGBColor();
        GeneralPath path = pathItem.getItem();
        // 如果颜色已经出现过 判断是否为已有 PathInfo 对象的图标
        if (strokeColors.contains(color)) {
            if (isPathAuxiliaryIcon(path, color)) {
                return;
            }
        }
        else {
            strokeColors.add(color);
        }

        // 判断是否为坐标轴线
        if (isDashLine(pathItem)) {
            return;
        }
        // 判断是否为某个矩形对象的包围宽
        if (isBarBoxLine(pathItem)) {
            return;
        }
        // 判断是否为坐标轴线
        if (isAxisLine(pathItem)) {
            return;
        }
        // 判断是否为长的水平线 一般为辅助线或是有图例对象的平均线
        if (isHorizonMeanLine(pathItem, PathInfo.PathType.HORIZON_LONG_LINE)) {
            return;
        }

        // 检测为坐标轴或是图例指示线
        if (isSimpleAxisScaleLine(pathItem)) {
            // 如果是单个坐标轴轴标线对象
        }
        else if (ChartPathInfosParser.isAxisScaleInChart(path, chart)) {
            // 如果是刻度线　暂时不需要作相应的处理 后续精确抽取数据时　再改进
        }
        else if (ChartPathInfosParser.isAxisGridInChart(path, chart)) {
            // 如果是网格线　暂时不需要作相应的处理 后续精确抽取数据时　再改进
        }
        else if (ChartPathInfosParser.isSpecialLine(path, chart)) {
            // 如果是长水平虚线 或 高垂直虚线 此时一般为　折线图中的某个平均指标
            ChartPathInfosParser.savePathIntoPathInfo(path, PathInfo.PathType.LINE, color, paths, pathInfos);
        }
        else {
            // 判断是否为折线
            dealWithLine(pathItem);
        }
    }

    private boolean isSimpleAxisScaleLine(PathItem pathItem) {
        // 判断 path 的矩形框的长宽范围是否有效
        Rectangle2D rect = pathItem.getItem().getBounds2D();
        double pw = rect.getWidth();
        double cw = chart.getWidth();
        double ph = rect.getHeight();
        double ch = chart.getHeight();
        if (pw > 0.02 * cw || ph > 0.02 * ch) {
            return false;
        }
        // 判断是否为垂直或水平方向
        boolean isXDir = ChartUtils.equals(pw, 0.0);
        boolean isYDir = ChartUtils.equals(ph, 0.0);
        if ((!isXDir && !isYDir) || (isXDir && isYDir)) {
            return false;
        }
        List<Line2D> axises = new ArrayList<>();
        hAxisLines.stream().forEach(axis -> axises.add(axis));
        vAxisLines.stream().forEach(axis -> axises.add(axis));
        boolean isAxisScaleLine = ChartPathInfosParser.isPathAxisScale(pathItem, axises);
        if (isAxisScaleLine) {
            Line2D axisScaleLine = GraphicsUtil.getLine(pathItem.getItem());
            if (axisScaleLine != null) {
                chart.axisScaleLines.add(axisScaleLine);
            }
        }
        return isAxisScaleLine;
    }

    public static boolean isWhite(Color color) {
        return color.getRed() == 255 && color.getGreen() == 255 && color.getBlue() == 255;
    }

    /**
     * 判断给定的线对象或是Chart.barInfos最新存放的bar对象是否为填充状态的坐标轴轴标对象
     * @param chart
     * @param numBefore
     * @param color
     * @param scales
     * @return
     */
    private boolean isFillAxisScaleLine(Chart chart, int numBefore, Color color, List<Line2D> scales) {
        // 判断是否有参考坐标轴信息
        int nPath = pathInfos.size();
        int nHAxis = hAxisLines.size();
        int nVAxis = vAxisLines.size();
        if (nPath == 0 && nHAxis == 0 && nVAxis == 0) {
            return false;
        }

        // 从收集到的小矩形集中拿出候选轴标对象
        List<Line2D> lines = ChartPathInfosParser.getChartBarInfosLines(chart, numBefore);
        boolean isBarInfo = true;
        int numAfter = chart.barsInfos.size();
        if (scales != null && !scales.isEmpty()) {
            lines = scales;
            numBefore = 0;
            isBarInfo = false;
        }

        if (nHAxis >= 1) {
            Line2D axis = hAxisLines.get(nHAxis - 1);
            if (ChartPathInfosParser.isFillAxisSacleLine(
                    chart, axis.getBounds2D(), lines, 0)) {
                if (isBarInfo) {
                    for (int i = numBefore; i < numAfter; i++) {
                        chart.barsInfos.remove(numBefore);
                    }
                }
                return true;
            }
        }
        if (nVAxis >= 1) {
            Line2D axis = vAxisLines.get(nVAxis - 1);
            if (ChartPathInfosParser.isFillAxisSacleLine(
                    chart, axis.getBounds2D(), lines, 0)) {
                if (isBarInfo) {
                    for (int i = numBefore; i < numAfter; i++) {
                        chart.barsInfos.remove(numBefore);
                    }
                }
                return true;
            }
        }
        if (nPath >= 1) {
            ChartPathInfo pathInfo = pathInfos.get(nPath - 1);
            if (pathInfo.color.equals(color)) {
                if (ChartPathInfosParser.isFillAxisSacleLine(
                        chart, pathInfo.path.getBounds2D(), lines, 0)) {
                    pathInfos.remove(nPath - 1);
                    if (isBarInfo) {
                        for (int i = numBefore; i < numAfter; i++) {
                            chart.barsInfos.remove(numBefore);
                        }
                    }
                    Rectangle2D box = pathInfo.path.getBounds2D();
                    if (box.getWidth() > box.getHeight()) {
                        Line2D line = new Line2D.Double(box.getMinX(), box.getCenterY(), box.getMaxX(), box.getCenterY());
                        hAxisLines.add(line);
                    } else {
                        Line2D line = new Line2D.Double(box.getCenterX(), box.getMinY(), box.getCenterX(), box.getMaxY());
                        vAxisLines.add(line);
                    }
                    return true;
                }
            }
        }
        return false;
    }


    /**
     * 处理FillPath对象 (即填充区域)
     * @param pathItem
     */
    private void oldFillPath(PathItem pathItem) {
        Color color = pathItem.getRGBColor();
        GeneralPath path = pathItem.getItem();

        // 处理path对象复杂的情况 去掉多余的m操作
        path = filterPath(path);

        // 当前Path的具体类型
        // PathInfo.PathType[] type = {PathInfo.PathType.UNKNOWN};

        // 判断是否为环形对象内部填充圆形对象
        if (ChartPathInfosParser.isCirclePath(pathItem, arcs)) {
            ChartPathInfosParser.saveArcs(arcs, pies);
            return;
        }

        // 判断当前颜色是否为白色 如果是则为Chart背景颜色，即当前的区域范围　不作进一步测试
        int numBefore = chart.barsInfos.size();
        boolean isWhite = isWhite(color);
        if (!isWhite) {
            fillColors.add(color);
            // 判断是否为柱状图的构造元素 即是否含有一个或多个有效的矩形区域
            if (ChartPathInfosParser.isColumnarInChart(path, chart, color)) {
                ChartPathInfosParser.savePathIntoPathInfo(path, PathInfo.PathType.COLUMNAR, color, paths, pathInfos);
                return;
            }
            else {
                List<Line2D> lines = null;
                // 如果不是矩形对象 则尝试从中抽取出坐标轴轴标线集
                if (chart.barsInfos.size() == numBefore) {
                    lines = ChartPathInfosParser.getFillAxisScaleLines(path, chart);
                }
                else {
                    // 判断保存至chart.barsInfos中的小矩形对象是否和前面的柱状对象满足空间尺寸对齐规律
                    // 此时高概率为某个柱状对象内小尺寸的矩形
                    if (ChartPathInfosParser.isPathsAline(
                            pathInfos, chart.barsInfos.get(numBefore), false)) {
                        pathInfos.add(chart.barsInfos.get(numBefore));
                        chart.barsInfos.remove(numBefore);
                        return;
                    }
                }
                // 判断是否为填充的坐标轴轴标线对象
                if (isFillAxisScaleLine(chart, numBefore, color, lines)) {
                    return;
                }
            }

            // 判断是否为面积填充区域
            if (chart.barsInfos.size() == numBefore && ChartPathInfosParser.isAreaInChart(path, chart)) {
                ChartPathInfosParser.savePathIntoPathInfo(path, PathInfo.PathType.AREA, color, paths, pathInfos);
            }
            // 判断是否为坐标轴线
            else if (isAxisLine(pathItem)) {
            }
            else {
                // 判断单个路径是否为扇形 myyang need to debugger
                ChartPathInfosParser.ArcObject arcobj = new ChartPathInfosParser.ArcObject();

                // 判断是否为环形图子图对象
                if (ChartPathInfosParser.isRingPath(pathItem, arcobj, arcs, pies)) {
                    arcobj.color = color;
                    arcobj.path = (GeneralPath) path.clone();
                    arcs.add(arcobj);
                    return;
                }

                boolean pathIsArc = ChartPathInfosParser.isPathArc(path, arcobj);
                if (pathIsArc) {
                    arcobj.color = color;
                    arcobj.path = (GeneralPath) path.clone();
                    arcs.add(arcobj);
                }
                // 判断是否为小块的　等要三角形
                else if (ChartPathInfosParser.isPathArcTriangle(path, arcobj)) {
                    arcobj.color = color;
                    arcobj.path = (GeneralPath)path.clone();
                    tris.add(arcobj);
                }
                else {
                    // 判断是否为填充形式的折线对象  如果是则抽出其上边界  并保存为GeneralPath
                    GeneralPath pathNew = ChartPathInfosParser.isFilledLine(path, chart.getArea());
                    if (pathNew == null) {
                        pathNew = ChartPathInfosParser.isFilledDashLine(path, chart.getArea());
                    }
                    if (pathNew != null) {
                        // 将抽取的GeneralPath 和当前填充对象的状态封装为新的 PathItem 对象
                        PathItem pathItemNew =
                                new PathItem(pathNew, pathNew.getBounds2D(), pathItem.isFill(),
                                pathItem.getColor(), pathItem.getLineWidth(), pathItem.getLineDashPattern());
                        // 判断是否为有效折线对象
                        dealWithLine(pathItemNew);
                    }
                    //dealWithLine(pathItem);
                }
            }
        } // end if
    }

    /**
     * 处理Path对象
     * 去除
     * x x x x re
     * x x m
     * h
     * 识别不出来的情况
     * @param path
     */
    private GeneralPath filterPath(GeneralPath path) {
        // 遍历path的点集 判断有效性
        PathIterator iter = path.getPathIterator(null);
        GeneralPath newPath = (GeneralPath) path.clone();
        List<Double> xs = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
        newPath.reset();
        double[] coords = new double[12];
        int count = 0, n = 0;

        boolean filtered = false;

        List<Integer> operator = new ArrayList<>();

        while (!iter.isDone()) {
            switch (iter.currentSegment(coords)) {
                case PathIterator.SEG_MOVETO:
                    // 柱状图点数都是４的倍数
                    xs.add(coords[0]);
                    ys.add(coords[1]);
                    operator.add(PathIterator.SEG_MOVETO);
                    count++;
                    break;

                case PathIterator.SEG_LINETO:
                    xs.add(coords[0]);
                    ys.add(coords[1]);
                    operator.add(PathIterator.SEG_LINETO);
                    count++;
                    break;

                case PathIterator.SEG_CLOSE:
                    if (operator.get(operator.size() - 1).equals(PathIterator.SEG_MOVETO)){
                        // 跳过的情况 0 4
                        filtered = true;
                        operator.remove(operator.size() - 1);
                        xs.remove(xs.size() - 1);
                        ys.remove(ys.size() - 1);
                        count = 0;
                        break;
                    }
                    if (count % 4 == 0) {
                        // 目前先只限定 m l l l m 5个操作
                        // 筛除最后一个无用 m 操作符
                        int cur = operator.size();
                        if (operator.get(cur - 4).equals(PathIterator.SEG_MOVETO) &&
                                operator.get(cur - 3).equals(PathIterator.SEG_LINETO) &&
                                operator.get(cur - 2).equals(PathIterator.SEG_LINETO) &&
                                operator.get(cur - 1).equals(PathIterator.SEG_LINETO)) {
                            newPath.moveTo(xs.get(cur - 4), ys.get(cur - 4));
                            newPath.lineTo(xs.get(cur - 3), ys.get(cur - 3));
                            newPath.lineTo(xs.get(cur - 2), ys.get(cur - 2));
                            newPath.lineTo(xs.get(cur - 1), ys.get(cur - 1));
                            newPath.closePath();
                        }
                    }
                    count = 0;
                    break;

                default:
                    return path;

            }  // end switch
            iter.next();
        }  // end while

        if (filtered) {
            // filter过的情况下 替换原有path
            return newPath;
        }
        return path;
    }


    private boolean isPathAuxiliaryIcon(GeneralPath path, Color color) {
        for (ChartPathInfo pathInfo : pathInfos) {
            if (ChartPathInfosParser.isLineAuxiliaryIcon(chart, pathInfo, path, color)) {
                return true;
            }
        }
        return false;
    }

    private List<GeneralPath> getContentGroupRotateFillPaths(ContentGroup group) {
        List<GeneralPath> paths = new ArrayList<>();
        double zero = 1E-6;
        for (ContentItem contentItem : group.getItems()) {
            if (contentItem instanceof PathItem) {
                PathItem pathItem = (PathItem) contentItem;
                if (pathItem.isFill()) {
                    // 如果尺寸为0 则跳过
                    Rectangle2D box = pathItem.getBounds();
                    if (box.getHeight() < zero && box.getWidth() < zero) {
                        continue;
                    }
                    paths.add((GeneralPath) pathItem.getItem().clone());
                }
                else {
                    return null;
                }
            }
            else {
                return null;
            }
        }
        return paths;
    }

    /**
     * 判断给定两个ContentGroup是否为相邻斜着OCR对象
     * @param groupA
     * @param groupB
     * @return
     */
    private boolean isAdjoinRotatedText(ContentGroup groupA, ContentGroup groupB) {
        List<GeneralPath> pathsA = getContentGroupRotateFillPaths(groupA);
        List<GeneralPath> pathsB = getContentGroupRotateFillPaths(groupB);
        if (pathsA == null || pathsA.size() == 0 || pathsB == null || pathsB.size() == 0) {
            return false;
        }

        GeneralPath pathA = pathsA.get(0);
        GeneralPath pathB = pathsB.get(0);
        List<Point2D> obbA = MinOrientedBoundingBoxComputer.computOBB(pathA);
        List<Point2D> obbB = MinOrientedBoundingBoxComputer.computOBB(pathB);
        double areaA = MinOrientedBoundingBoxComputer.getObbArea(obbA);
        double areaB = MinOrientedBoundingBoxComputer.getObbArea(obbB);
        double cw = chart.getWidth();
        double ch = chart.getHeight();
        // 如果面积相对Chart区域占比过大　则是无效
        if (areaA >= 0.03 * cw * ch || areaB >= 0.03 * cw * ch) {
            return false;
        }

        if (!ChartPathInfosParser.isOCRTextPath(chart, pathA)) {
            return false;
        }

        // 如果最小外包框是普通矩形 则是无效
        if (MinOrientedBoundingBoxComputer.isObbAABB(obbA) ||
                MinOrientedBoundingBoxComputer.isObbAABB(obbB)) {
            return false;
        }

        // 如果最小外包框的两侧角度中最大角相差10度以上  则认为是不同方向的
        double angleA = MinOrientedBoundingBoxComputer.getObbAngle(obbA);
        double angleB = MinOrientedBoundingBoxComputer.getObbAngle(obbB);
        if (Math.abs(angleA - angleB) >= 10.0) {
            return false;
        }

        return true;
    }

    /**
     * 解析旋转斜着的文字信息
     * @return
     */
    private boolean detectRotatedText() {
        boolean result = false;
        double cw = chart.getWidth();
        double ch = chart.getHeight();
        for (ContentItem contentItem : current.getItems()) {
            if (contentItem instanceof PathItem) {
                PathItem pathItem = (PathItem) contentItem;
                if (!pathItem.isFill()) {
                    continue;
                }
                GeneralPath path = pathItem.getItem();
                // 判断是否为斜着的OCR　Text
                if (ChartPathInfosParser.isOCRTextPath(chart, path)) {
                    // 计算path的带方向外包框　计算最小面积
                    List<Point2D> obb = MinOrientedBoundingBoxComputer.computOBB(path);
                    double area = MinOrientedBoundingBoxComputer.getObbArea(obb);
                    // 如果面积相对Chart区域占比过大　则是无效
                    if (area >= 0.03 * cw * ch) {
                        return false;
                    }
                    else {
                        result = true;
                        ocrs.add(new OCRPathInfo(pathItem.getItem(), pathItem.getItem().getWindingRule()));
                    }
                }
            }
        }
        return result;
    }

    /**
     * 遍历数据块　测试数据块位于那些位图Chart检测到的对象中
     * @param groups
     */
    public void setDetectChartInfo(List<ContentGroup> groups) {
        if (pageDetectedInfo == null || pageDetectedInfo.isEmpty()) {
            return;
        }
        // 适当扩展检测到的区域
        for (int i = 0; i < pageDetectedInfo.size(); i++) {
            DetectedChartTableTF obj = (DetectedChartTableTF) pageDetectedInfo.get(i);
            Rectangle2D box = (Rectangle2D) obj.area.clone();
            double w = box.getWidth();
            double h = box.getHeight();
            double xmin = box.getMinX() - 0.01 * w;
            double ymin = box.getMinY() - 0.03 * h;
            double xmax = box.getMaxX() + 0.01 * w;
            double ymax = box.getMaxY() + 0.03 * h;
            // 尝试左右扩展　 解析对上下扩展很敏感　下一步继续优化
            boolean expendLeft = true;
            boolean expendRight = true;
            boolean expendTop = true;
            boolean expendBottom = true;
            for (int j = 0; j < pageDetectedInfo.size(); j++) {
                DetectedChartTableTF objB = (DetectedChartTableTF) pageDetectedInfo.get(j);
                if (objB.equals(obj)) {
                    continue;
                }
                Rectangle2D boxB = objB.area;
                if (expendLeft && boxB.contains(xmin, 0.5 * (ymin + ymax))) {
                    expendLeft = false;
                    xmin = box.getMinX();
                }
                if (expendRight && boxB.contains(xmax, 0.5 * (ymin + ymax))) {
                    expendRight = false;
                    xmax = box.getMaxX();
                }
                if (expendTop && boxB.contains(0.5 * (xmin + xmax), ymin)) {
                    expendTop = false;
                    ymin = box.getMinY();
                }
                if (expendBottom && boxB.contains(0.5 * (xmin + xmax), ymax)) {
                    expendTop = false;
                    ymax = box.getMaxY();
                }
            } // end for j
            box.setRect(xmin, ymin, xmax - xmin, ymax - ymin);
            obj.area = box;
        } // end for i

        // 遍历数据块　检测数据块都位于那些检测出的区域内部　以便后续对区域内数据块进行解析
        detectObjStatus.clear();
        pageDetectedInfo.stream().forEach(obj -> detectObjStatus.add(new ArrayList<>()));
        for (int i = 0; i < groups.size(); i++) {
            ContentGroup group = groups.get(i);
            boolean onlyHasText = group.hasText() && !group.hasOnlyPathItems();
            List<TextChunk> chunks = new ArrayList<>();
            if (onlyHasText) {
                chunks = group.getAllTextChunks();
            }
            Rectangle2D box = group.getArea();
            for (int j = 0; j < pageDetectedInfo.size(); j++) {
                 DetectedChartTableTF obj = (DetectedChartTableTF) pageDetectedInfo.get(j);
                 List<Integer> objStatus = detectObjStatus.get(j);
                 if (obj.area == null) {
                     continue;
                 }
                 if (GraphicsUtil.contains(obj.area, box)) {
                     objStatus.add(i);
                 }
                 else {
                     if (onlyHasText && obj.area.intersects(box)) {
                         if (chunks.stream().anyMatch(textChunk -> GraphicsUtil.contains(obj.area,  textChunk))) {
                             objStatus.add(i);
                         }
                     }
                     else {
                         if (obj.area.contains(group.getItemsArea(0.1))) {
                             objStatus.add(i);
                         }
                     }
                 }
            } // end for j
        } // end for i
    }

    /**
     * 设置 ContentGroup集 元素的类型
     * @param groups
     * @param setFormType
     */
    public void setContentGroupType(List<ContentGroup> groups, boolean setFormType) {
        // 重置类型
        for (ContentGroup group : groups) {
            setContentGroupType(group, ContentGroupType.STANDARD_TYPE);
        }

        boolean hasForm = false;
        PDFormXObject formCurrent = null;
        Rectangle2D box = new Rectangle2D.Double(0, 0, 0, 0);
        Rectangle2D boxBefore = (Rectangle2D) box.clone();
        int ithBoxBefor = -1;
        for (int i = 0; i < groups.size(); i++) {
            ContentGroup group = groups.get(i);
            // 标记 Form对象 内部元素类型
            if (setFormType) {
                if (group.isPDFormXObject() || group.inForm()) {
                    hasForm = true;
                    PDFormXObject formNew = group.getForm();
                    if (!formNew.equals(formCurrent)) {
                        setContentGroupType(group, ContentGroupType.FORM_BEGIN_TYPE);
                        formCurrent = formNew;
                    } else {
                        setContentGroupType(group, ContentGroupType.FORM_TYPE);
                    }
                }
            }

            // 如果是文字信息对象 则检查是否为标题或来源信息 如果是 则设置类型
            if (group.hasText()) {
                List<TextChunk> chunks = group.getAllTextChunks();
                StringBuilder sb = new StringBuilder();
                for (TextChunk chunk : chunks) {
                    sb.append(chunk.getText());
                }
                String text = sb.toString();

                boolean titleOrSource = ChartTitleLegendScaleParser.isDataSourceTextChunk(text);
                // || ChartTitleLegendScaleParser.isTitlePrefix(text);
                if (titleOrSource) {
                    setContentGroupType(group, ContentGroupType.CHART_END_TYPE);
                }
            }
            else if (group.hasOnlyPathItems() && group.getItems().size() <= 2) {
                if (!isChartBeginSign(group, box)) {
                    continue;
                }

                PathItem item = (PathItem)group.getItems().get(0);
                if (!isWhite(item.getColor())) {
                    continue;
                }
                GeneralPath path = item.getItem();
                box = (Rectangle2D) path.getBounds2D().clone();
                //if (!ChartDetectDrawerV2.isValidChartArea(page, path.getBounds2D())) {
                if (!isValidChartArea(pageContentGroup.getArea(), box)) {
                    continue;
                }

                // 如果相邻的两个填充区域 相包含 则忽略掉
                if (ithBoxBefor + 1 == i && GraphicsUtil.contains(boxBefore, box)) {
                    continue;
                }

                setContentGroupType(group, ContentGroupType.CHART_BEGIN_TYPE);
                boxBefore = (Rectangle2D) box.clone();
                ithBoxBefor = i;
            }
        } // end for

      /*  if (hasForm) { // 吧其它类型设置为unkonown_type
            // 设置没有特殊类型的对象
            for (ContentGroup group : groups) {
                if (getContentGroupType(group) == ContentGroupType.STANDARD_TYPE) {
                    setContentGroupType(group, ContentGroupType.UNKNOWN_TYPE);
                }
            }
        }*/
    }

    // 保存当前图形对象解析状态信息
    public void saveGraphicsStatus() {
        gStatus.nPathInfos = pathInfos.size();
        gStatus.nHAxis = hAxisLines.size();
        gStatus.nArcs = arcs.size();
        gStatus.fillColors = new HashSet<>(fillColors);
        gStatus.strokeColors = new HashSet<>(strokeColors);
    }

    public void setPageDetectedInfo(List<DetectedObjectTF> detectedInfo) {
        pageDetectedInfo = detectedInfo;
        filterInvalidDetectedInfo();
    }

    /**
     * 过滤掉位图检测得到的重复Chart区域
     */
    private  void filterInvalidDetectedInfo() {
        if (pageDetectedInfo == null || pageDetectedInfo.size() <= 1) {
            return;
        }
        // 找出大面积重复且面积较大的Chart区域
        int n = pageDetectedInfo.size();
        for (int i = 0; i < n - 1; i++) {
            DetectedChartTableTF objA = (DetectedChartTableTF) pageDetectedInfo.get(i);
            if (objA.typeID == -1) {
                continue;
            }
            double areaA = objA.area.getHeight() * objA.area.getWidth();
            for (int j = i + 1; j < n; j++) {
                DetectedChartTableTF objB = (DetectedChartTableTF) pageDetectedInfo.get(j);
                if (objB.typeID == -1) {
                    continue;
                }
                double areaB = objB.area.getHeight() * objB.area.getWidth();
                if (areaA > areaB && areaOverlapValid(objA.area, objB.area, 0.8)) {
                    objA.typeID = -1;
                }
                else if (areaA < areaB && areaOverlapValid(objB.area, objA.area, 0.8)) {
                    objB.typeID = -1;
                }
            } // end for j
        } // end for i
        // 过滤掉
        final Iterator<DetectedObjectTF> each = pageDetectedInfo.iterator();
        while (each.hasNext()) {
            DetectedObjectTF obj = each.next();
            if (obj.typeID == -1) {
                each.remove();
            }
        }
    }

    /**
     * 判断给定ContentGroup对象是否与用TF Model检测Page内部对象信息冲突
     * 即中心位于无效Chart内部
     * @param content
     * @return
     */
    private boolean confictPageDetectedInfo(ContentGroup content) {
        double x = content.getArea().getCenterX();
        double y = content.getArea().getCenterY();
        if (pageDetectedInfo != null && !pageDetectedInfo.isEmpty()) {
            for (DetectedObjectTF obj : pageDetectedInfo) {
                // 如果对象为无效Chart类型 则判断当前 ContentGroup 是否在其中
                if (obj.typeID == ChartType.UNKNOWN_CHART.getValue()) {
                    if (obj.box[0] <= x && x <= obj.box[2] &&
                            obj.box[1] <= y && y <= obj.box[3]) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * 判断给定ContentGroup是否为候选Chart区域类型且在当前饼图Chart内部
     * 饼图内部可能将图例及其信息置于某个填充区域内, 该区域可能构成候选Chart区域
     * 须识别出这种情况, 不然可能漏掉饼图中的图例信息, 出现与英文PDF中
     * @param content
     * @return
     */
    private boolean isCandidateAreaInPieChart(ContentGroup content) {
        if (getContentGroupType(content) == ContentGroupType.CHART_BEGIN_TYPE &&
                chart != null && chart.type == ChartType.PIE_CHART) {
            if (content.hasOnlyPathItems() &&
                    areaOverlapValid(checkArea, content.getArea(), 0.9)) {
                return true;
            }
        }
        return false;
    }

    private void setContentGroupType(ContentGroup contentGroup, ContentGroupType type) {
        if (contentGroup != null) {
            contentGroup.addTag(CONTNET_GROUP_TYPE, type);
        }
    }

    private ContentGroupType getContentGroupType(ContentGroup contentGroup) {
        if (contentGroup != null && contentGroup.hasTag(CONTNET_GROUP_TYPE)) {
            return  (ContentGroupType)contentGroup.getTag(CONTNET_GROUP_TYPE);
        }
        return ContentGroupType.UNKNOWN_TYPE;
    }

    public boolean isChartBeginSign(ContentGroup contentGroup, Rectangle2D box) {
        // 初步判断内部数据个数
        List<ContentItem> items = contentGroup.getAllItems();
        if (items.size() == 0) {
            return false;
        }
        List<Object> tokens = contentGroup.getAllTokens();
        int n = tokens.size();
        if (n < 6 || n > 100) {
            return false;
        }
        // 判断最后一个是否为 Q
        Object objLast = tokens.get(n - 1);
        if (!(objLast instanceof Operator)) {
            return false;
        }
        if (((Operator) objLast).getName().equalsIgnoreCase("Q")) {
            if (n < 7) {
                return false;
            }
            n = n - 1;
        }

        Object objFloat1 = tokens.get(n - 6);
        Object objFloat2 = tokens.get(n - 5);
        Object objFloat3 = tokens.get(n - 4);
        Object objFloat4 = tokens.get(n - 3);
        if (!(objFloat1 instanceof COSNumber) || !(objFloat2 instanceof COSNumber) ||
                !(objFloat3 instanceof COSNumber) || !(objFloat4 instanceof COSNumber)) {
            return false;
        }
        Object objRe = tokens.get(n - 2);
        Object objFill = tokens.get(n - 1);
        if (!(objFill instanceof Operator) || !(objRe instanceof Operator)) {
            return false;
        }
        boolean bFill = ((Operator) objFill).getName().equalsIgnoreCase("f*") ||
                ((Operator) objFill).getName().equalsIgnoreCase("f") ||
                ((Operator) objFill).getName().equalsIgnoreCase("B*");
        boolean bRe = ((Operator) objRe).getName().equalsIgnoreCase("re");
        if (!bFill || !bRe) {
            return false;
        }
        box.setRect(((COSNumber) objFloat1).doubleValue(), ((COSNumber) objFloat2).doubleValue(),
                ((COSNumber) objFloat3).doubleValue(), ((COSNumber) objFloat4).doubleValue());
        return true;
    }

    public PDFormXObject buildForm(
            PDDocument document, PDResources resources,
            ContentGroup contentGroup) {
        PDFormXObject form = new PDFormXObject(document);
        form.setResources(resources);
        // 设置bbox的区域
        Rectangle2D area = contentGroup.getArea();
        PDRectangle rect = new PDRectangle((float) area.getX(), (float) area.getY(),
                (float) area.getWidth(), (float) area.getHeight());
        form.setBBox(rect);
        form.setMatrix(new AffineTransform());
        //ChartUtils.writeTokensToForm(form, graphicsState, getAllTokens());
        return form;
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
        if (area.getWidth() > LEGEND_MIN_WIDTH
                && area.getWidth() < LEGEND_MAX_WIDTH
                && area.getHeight() < LEGEND_MAX_HEIGHT) {
            return area;
        } else {
            return null;
        }
    }

    /**
     * 判断一个范围是否为合理的Chart区域
     * @param bbox 给定范围
     * @return
     */
    private static boolean isValidChartArea(Rectangle2D page, Rectangle2D bbox) {
        return ChartUtils.isAreaNotBig(page, bbox) && ChartUtils.isAreaNotSmall(page, bbox);
    }

    // 是否有文字在图片对象区域的内部区域，忽略掉部分边界区域
    private boolean hasTextsCoveredByImage(Rectangle2D bounds, List<TextChunk> chunks) {
        if (chunks.isEmpty()) {
            return false;
        }
        // 忽略掉部分边界区域，长宽都取80%的范围
        double x = bounds.getCenterX();
        double y = bounds.getCenterY();
        double w = 0.7 * bounds.getWidth();
        double h = 0.7 * bounds.getHeight();
        Rectangle2D innerArea = new Rectangle2D.Double(x - 0.5 * w, y - 0.5 * h, w, h);
        return chunks.stream().anyMatch(textChunk -> innerArea.intersects(textChunk.getBounds2D()));
    }

    /**
     * 判断给定的PDImage对象是否构成有效位图Chart对象
     * @param pdImage
     * @param bounds
     */
    private void checkBitmapChart(PDImage pdImage, Rectangle2D bounds) {
        if (pdImage instanceof PDImageXObject) {
            PDImageXObject xObject = (PDImageXObject) pdImage;
            try {
                if (xObject.getBitsPerComponent() != 8 && xObject.getBitsPerComponent() != 4) {
                    return;
                }
                if (pdImage.getColorSpace() instanceof PDIndexed) {
                    if (((PDIndexed) pdImage.getColorSpace()).getBaseColorSpace().getNumberOfComponents() < 3) {
                        return;
                    }
                } else if (pdImage.getColorSpace().getNumberOfComponents() < 3) {
                    return;
                }
            } catch (Exception e) {
                return;
            }
        } else {
            return;
        }

        // 过滤掉图片包围框太小的对象
        PDPage page = context.getNativeDocument().getPage(pageIndex);
        float w = page.getBBox().getWidth();
        float h = page.getBBox().getHeight();
        Rectangle2D pageBox = new Rectangle2D.Float(0.0f, 0.0f, w, h);

        if (bounds.getWidth() < 0.15 * w || bounds.getHeight() < 0.07 * h) {
            return;
        }

        // 判断图片是否在page的页眉页脚区域  目前经验性的取页面尺寸的90%以上为页眉 10%以下为页脚
        if (bounds.getMinY() > 0.9 * h || bounds.getMaxY() < 0.1 * h) {
            return;
        }
        if (bounds.getCenterY() > 0.92 * h || bounds.getCenterY() < 0.08 * h) {
            return;
        }

        /*
        * 有些PDF中每一个Page的Resources都存有一些位图对象，但是部分或全部没有引用
        * 所以依据位图对象计数机制判断是否为背景或logo等无效图片已经不适用
        * 将当前位图对象和目前为止解析到的位图对象进行内容匹配 如果不存在 则认为是候选位图对象
        */
        for (Chart chart : charts) {
            if (chart.type == ChartType.BITMAP_CHART) {
                // 判断图片内容是否相同
                if (chart.image != null && ChartUtils.imageXObjectEqual((PDImageXObject) pdImage, chart.image)) {
                    return;
                }
            }
        }

        // 检测图片有没有可能是chart
        Rectangle2D imageArea = new Rectangle2D.Float(0.0f, 0.0f, (float) pdImage.getWidth(), (float) pdImage.getHeight());
        if (ChartUtils.isAreaNotSmall(pageBox, bounds) &&
                ChartUtils.isAreaNotSmall(pageBox, imageArea)) {
            createBitmapChart((PDImageXObject) pdImage, bounds);
        }
    }

    private void createBitmapChart(PDImageXObject image, Rectangle2D bounds) {
        Point2D cpt = new Point2D.Double(bounds.getCenterX(), bounds.getCenterY());
        final Iterator<Chart> each = charts.iterator();
        // 遍历当前Chart对象  过滤掉阴影位图对象用
        while (each.hasNext()) {
            Chart chart = each.next();
            if (chart.type == ChartType.BITMAP_CHART) {
                Rectangle area = chart.getArea();
                Point2D pt = area.getCenter();
                // 如果彼此中心点在对方内部　则删除前面出现的位图对象
                if (area.contains(cpt) && bounds.contains(pt)) {
                    each.remove();
                }
            }
        }

        Chart newChart = new Chart();
        newChart.type = ChartType.BITMAP_CHART;
        newChart.setArea(bounds);
        newChart.image = image;
        charts.add(newChart);
    }

    /**
     * 对单个类型图表 用图例颜色去匹配相应的图形对象
     * 如下情况 方法失效
     * 1. 存在同色辅助图形
     * 2. 渐变色或是颜色计算不准时
     * 3. 不存在图例对象 或是 图例没有找准
     * @param chart
     * @param pathInfoDatas
     */
    private void legendMatchPathInfo(Chart chart, List<PathInfoData> pathInfoDatas) {
        // 尝试对折线图用图例颜色匹配折线对象
        boolean useLegendMatchLine = true;
        if (useLegendMatchLine) {
            List<ChartType> objTypes = new ArrayList<>();
            objTypes.add(ChartType.LINE_CHART);
            objTypes.add(ChartType.CURVE_CHART);
            legendMatchPathInfo(chart, pathInfoDatas, objTypes, 3);
        }

        // 尝试对柱状图用图例颜色匹配折线对象
        boolean useLegendMatchBar = true;
        if (useLegendMatchBar) {
            List<ChartType> objTypes = new ArrayList<>();
            objTypes.add(ChartType.BAR_CHART);
            objTypes.add(ChartType.COLUMN_CHART);
            legendMatchPathInfo(chart, pathInfoDatas, objTypes, 4);
        }

        // 尝试对面积图用图例颜色匹配面积对象
        boolean useLegendMatchArea = true;
        if (useLegendMatchArea) {
            List<ChartType> objTypes = new ArrayList<>();
            objTypes.add(ChartType.AREA_CHART);
            objTypes.add(ChartType.AREA_OVERLAP_CHART);
            legendMatchPathInfo(chart, pathInfoDatas, objTypes, 8);
        }

        // 尝试对饼图用图例颜色匹配扇形对象
        boolean useLegendMatchPie = true;
        if (useLegendMatchPie) {
            List<ChartType> objTypes = new ArrayList<>();
            objTypes.add(ChartType.PIE_CHART);
            legendMatchPathInfo(chart, pathInfoDatas, objTypes, 7);
        }
    }

    /**
     * 对折线图 用图例匹配折线对象
     * 对柱状图 用图例匹配柱状对象
     *  (为了高效测试可行性, 直接基于path分类的结果)
     * @param chart
     * @param pathInfoDatas
     * @param objTypes
     * @param objLebel
     */
    private void legendMatchPathInfo(
            Chart chart, List<PathInfoData> pathInfoDatas, List<ChartType> objTypes, int objLebel) {
        // 判断Chart类型是否为指定类型
        List<ChartType> chartTypes = chart.subTypes;
        boolean isObjTypeChart = false;
        if (chartTypes.size() == 1) {
            ChartType chartType = chartTypes.get(0);
            for (ChartType objType : objTypes) {
                if (chartType == objType) {
                    isObjTypeChart = true;
                    break;
                }
            }
        }
        // 如果不是 则直接返回
        if (!isObjTypeChart) {
            return;
        }

        // 收集图例对象的颜色集
        List<List<Double>> legendColors = new ArrayList<>();
        int numPathInfoDatas = pathInfoDatas.size();
        for (int i = 0; i < numPathInfoDatas; i++) {
            PathInfoData pathInfoData = pathInfoDatas.get(i);
            int label = pathInfoData.getLabel(true);
            if (label == 2) {
                legendColors.add(pathInfoData.colors);
            }
        }

        // 遍历path组数据  找出颜色和图例颜色相匹配的
        double dist = 0.0;
        double r1 = 0.0, g1 = 0.0, b1 = 0.0;
        double r2 = 0.0, g2 = 0.0, b2 = 0.0;
        for (int i = 0; i < numPathInfoDatas; i++) {
            PathInfoData pathInfoData = pathInfoDatas.get(i);
            int label = pathInfoData.getLabel(true);
            // 如果是无效或是图例类型 则跳过
            if (label == -1 || label == 9 || label == 2) {
                continue;
            }
            // 获取path组颜色
            List<Double> color = pathInfoData.colors;
            r1 = color.get(0);
            g1 = color.get(1);
            b1 = color.get(2);
            // 遍历图例颜色集
            for (List<Double> legendColor : legendColors) {
                r2 = legendColor.get(0);
                g2 = legendColor.get(1);
                b2 = legendColor.get(2);
                // 判断颜色误差
                dist = Math.abs(r1 - r2) + Math.abs(g1 - g2) + Math.abs(b1 - b2);
                if (dist < 1E-1) {
                    if (pathInfoData.label != objLebel) {
                        logger.info("----------  change from {} to {} --------", pathInfoData.label, objLebel);
                    }
                    pathInfoData.label = objLebel;
                    break;
                }
            }
        } // end for i
    }

    private void parserChartPathInfoDatas(Chart chart, List<PathInfoData> pathInfoDatas) {
        // 判断数据有效性
        int numPath = chart.markPaths.size();
        int numPathInfoDatas = pathInfoDatas.size();
        if (numPath == 0 || numPathInfoDatas == 0 || numPath < numPathInfoDatas) {
            chart.type = ChartType.UNKNOWN_CHART;
            return;
        }

        // 尝试用图例颜色匹配图形对象
        legendMatchPathInfo(chart, pathInfoDatas);

        // 遍历所有的path组对象 根据预测的类别做相应的处理
        Rectangle2D box = chart.getArea();
        List<ContentGroup> allContentGroups = chart.contentGroup.getAllContentGroups(true, true);
        for (int i = 0; i < numPathInfoDatas; i++) {
            PathInfoData pathInfoData = pathInfoDatas.get(i);
            // 跳过无效类型
            //int label = pathInfoData.label;
            int label = pathInfoData.getLabel(true);
            if (label == -1 || label == 9) {
                continue;
            }
            // 获取相应的ContentGroup对象
            List<Integer> contentGroupIDs = chart.markPaths.get(pathInfoData.groupID);
            List<ContentGroup> groups = new ArrayList<>();
            for (Integer id : contentGroupIDs) {
                groups.add(allContentGroups.get(id));
            }
            switch (label) {
                case 1:
                    parserAxisPath(box, groups, pathInfoData);
                    break;
                case 2:
                    parserLegendPath(box, groups, pathInfoData);
                    break;
                case 3:
                    parserLinePath(box, groups, pathInfoData);
                    break;
                case 4:
                case 5:
                    parserColumnPath(box, groups, pathInfoData);
                    break;
                case 6:
                    parserTextPath(groups, pathInfoData);
                    break;
                case 7:
                    parserArcPath(groups, pathInfoData);
                    break;
                case 8:
                    parserAreaPath(box, groups, pathInfoData);
                    break;
                default:
                    break;
            } // end switch
        } // end for i
    }

    private List<ContentItem> getContentItems(List<ContentGroup> groups) {
        List<ContentItem> items = new ArrayList<>();
        for (int i = 0; i < groups.size(); i++) {
            ContentGroup group = groups.get(i);
            ContentItem item = group.getAllItems().get(0);
            PathItem pathItem = (PathItem) item;
            items.add(pathItem);
        } // end for i
        return items;
    }

    private List<SubPathData> splitContentItem(ContentItem item, boolean splitMoveTo) {
        List<SubPathData> subPathDatas = new ArrayList<>();
        if (item instanceof PathItem) {
            subPathDatas = SubPathData.splitContentItem((PathItem)item, splitMoveTo);
        }
        return subPathDatas;
    }

    private List<SubPathData> splitContentItems(List<ContentItem> items, boolean splitMoveTo) {
        List<SubPathData> subPathDatas = new ArrayList<>();
        for (ContentItem item : items) {
            subPathDatas.addAll(splitContentItem(item, splitMoveTo));
        }
        return subPathDatas;
    }

    private void parserAxisPath(Rectangle2D box, List<ContentGroup> groups, PathInfoData pathInfoData) {
        // 过滤掉虚线　(前期训练样本的特征没有加进来)
        if (pathInfoData.obj.get("line_dash_array_length").getAsInt() >= 1) {
            return;
        }

        List<ContentItem> items = getContentItems(groups);
        List<SubPathData> subPathDatas = splitContentItems(items, true);
        if (subPathDatas.isEmpty()) {
            return;
        }

        List<Integer> xAxis = new ArrayList<>();
        List<Integer> yAxis = new ArrayList<>();
        List<Integer> xAxisScale = new ArrayList<>();
        List<Integer> yAxisScale = new ArrayList<>();
        int n = subPathDatas.size();
        double width = box.getWidth();
        double height = box.getHeight();
        for (int i = 0; i < n; i++) {
            SubPathData subPathData = subPathDatas.get(i);
            if (subPathData.isLargeXYLine(true, 0.1 * width, 0.01 * height)) {
                xAxis.add(i);
            }
            else if (subPathData.isLargeXYLine(false, 0.1 * height, 0.01 * width)) {
                yAxis.add(i);
            }
            else if (subPathData.isSmallXYLine(true, 0.1 * width, 0.005 * height)) {
                xAxisScale.add(i);
            }
            else if (subPathData.isSmallXYLine(false, 0.1 * height, 0.005 * width)) {
                yAxisScale.add(i);
            }
        }

        // 如果数目过大　则直接返回
        if (xAxis.size() >= 3 || yAxis.size() >= 3 || xAxis.size() + yAxis.size() >= 4) {
            return;
        }

        // 添加至相应集合容器中
        for (Integer id : xAxis) {
            SubPathData subPathData = subPathDatas.get(id);
            hAxisLines.add(subPathData.toXYLine(true));
        }
        for (Integer id : yAxis) {
            SubPathData subPathData = subPathDatas.get(id);
            vAxisLines.add(subPathData.toXYLine(false));
        }
        for (Integer id : xAxisScale) {
            SubPathData subPathData = subPathDatas.get(id);
            chart.axisScaleLines.add(subPathData.toXYLine(true));
        }
        for (Integer id : yAxisScale) {
            SubPathData subPathData = subPathDatas.get(id);
            chart.axisScaleLines.add(subPathData.toXYLine(false));
        }
        // TODO
    }

    private void parserLegendPath(Rectangle2D box, List<ContentGroup> groups, PathInfoData pathInfoData) {
        List<ContentItem> items = getContentItems(groups);
        List<SubPathData> subPathDatas = splitContentItems(items, true);
        if (subPathDatas.isEmpty()) {
            return;
        }
        int n = subPathDatas.size();
        Color color = ((PathItem)items.get(0)).getColor();
        double width = box.getWidth();
        double height = box.getHeight();
        for (int i = 0; i < n; i++) {
            SubPathData subPathData = subPathDatas.get(i);
            Rectangle2D subPathBox = subPathData.getBound();
            if (subPathBox.getHeight() > 0.3 * height || subPathBox.getWidth() > 0.3 * width) {
                continue;
            }
            addLegend(color, subPathData.getBound());
        }
        // TODO
    }

    /**
     * 收集坐标轴轴标对象
     * @param chart
     * @return
     */
    private List<Rectangle2D> getChartAxisBoxes(Chart chart) {
        List<Rectangle2D> axisBoxes = new ArrayList<>();
        if (chart.lvAxis != null) {
            Rectangle2D axis = chart.lvAxis.getBounds2D();
            GraphicsUtil.extendRect(axis, 0.01);
            axisBoxes.add(axis);
        }
        if (chart.rvAxis != null) {
            Rectangle2D axis = chart.rvAxis.getBounds2D();
            GraphicsUtil.extendRect(axis, 0.01);
            axisBoxes.add(axis);
        }
        for (Line2D line : chart.axisScaleLines) {
            Rectangle2D axis = line.getBounds2D();
            GraphicsUtil.extendRect(axis, 0.01);
            axisBoxes.add(axis);
        }
        return axisBoxes;
    }

    /**
     * 判断给定的subpathdata对象是否和chart内部坐标轴轴标高度重叠
     * @param axisBoxes
     * @param subPathDatas
     * @return
     */
    private boolean isOverlapChartAxis(
            List<Rectangle2D> axisBoxes, List<SubPathData> subPathDatas) {
        int n = subPathDatas.size();
        for (int i = 0; i < n; i++) {
            SubPathData subPathData = subPathDatas.get(i);
            for (Rectangle2D axisBox : axisBoxes) {
                if (subPathData.overlapBox(axisBox)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 过滤掉折线对象中偶尔出息的干扰的长水平线，通常是同色坐标轴或网格线
     * @param chart
     * @param groups
     * @return
     */
    private List<ContentGroup> filterLineChartNoiceLine(Chart chart, List<ContentGroup> groups) {
        // 如果数目过少　则直接返回
        if (groups.size() <= 1) {
            return groups;
        }

        // 遍历判断是否有非长XY线对象
        List<ContentGroup> nonLongXYLine = new ArrayList<>();
        List<ContentGroup> longXYLine = new ArrayList<>();
        for (ContentGroup group : groups) {
            if (!isLongXYLine(chart, group)) {
                nonLongXYLine.add(group);
            }
            else {
                longXYLine.add(group);
            }
        }

        // 如果同时存在长直线和非长直线对象
        if (nonLongXYLine.size() != groups.size() && nonLongXYLine.size() >= 1) {
            // 计算包围框
            Rectangle2D box1 = longXYLine.get(0).getArea();
            for (ContentGroup group : longXYLine) {
                Rectangle2D.union(box1, group.getArea(), box1);
            }
            double ymin1 = box1.getMinY(), ymax1 = box1.getMaxY();
            Rectangle2D box2 = nonLongXYLine.get(0).getArea();
            for (ContentGroup group : nonLongXYLine) {
                Rectangle2D.union(box2, group.getArea(), box2);
            }
            double ymin2 = box2.getMinY(), ymax2 = box2.getMaxY();
            double dist = 3;
            // 判断Y方向是否高度重叠
            if ((ymin1 - dist <= ymin2 && ymax2 <= ymax1 + dist) &&
                 (ymin2 - dist <= ymin1 && ymax1 <= ymax2 + dist)) {
                return groups;
            }
            else {
                return nonLongXYLine;
            }
        }
        else {
            return groups;
        }
    }

    /**
     * 判断给定Chart内部某个ContentGroup是否为长的水平或垂直线对象
     * @param chart
     * @param group
     * @return
     */
    private boolean isLongXYLine(Chart chart, ContentGroup group) {
        // 分割为一段段对象
        List<ContentGroup> groups = new ArrayList<>();
        groups.add(group);
        List<ContentItem> items = getContentItems(groups);
        List<SubPathData> subPathDatas = splitContentItems(items, true);
        if (subPathDatas.isEmpty()) {
            return false;
        }

        double width = chart.getWidth();
        double height = chart.getHeight();
        int n = subPathDatas.size();
        int count = 0;
        for (int i = 0; i < n; i++) {
            SubPathData subPathData = subPathDatas.get(i);
            if (subPathData.isLargeXYLine(true, 0.5 * width, 0.002 * height)) {
                count++;
            }
            else if (subPathData.isLargeXYLine(false, 0.5 * height, 0.002 * width)) {
                count++;
            }
        } // end for i
        if (count == n) {
            return true;
        }
        else {
            return false;
        }
    }

    /**
     * 过滤掉高概率为坐标轴，轴标，网格线
     * @param chart
     * @return
     */
    private boolean isChartInvalidContentGroup(Chart chart, ContentGroup group, ChartType chartType) {
        if (group == null) {
            return false;
        }
        // 分割为一段段对象
        List<ContentGroup> groups = new ArrayList<>();
        groups.add(group);
        List<ContentItem> items = getContentItems(groups);
        List<SubPathData> subPathDatas = splitContentItems(items, true);
        if (subPathDatas.isEmpty()) {
            return false;
        }

        // 收集坐标轴轴标对象
        List<Rectangle2D> axisBoxes = getChartAxisBoxes(chart);
        // 判断是否重叠
        if (isOverlapChartAxis(axisBoxes, subPathDatas)) {
            return true;
        }

        // 判断是否为相应类型的无效对象
        if (chartType == ChartType.LINE_CHART) {
            return isChartInvalidLineObj(chart, subPathDatas);
        }
        else if (chartType == ChartType.BAR_CHART) {
            return isChartInvalidBarObj(chart, subPathDatas);
        }

        return false;
    }

    /**
     * 判断给定subpathdata是否为无效柱状对象
     * @param chart
     * @param subPathDatas
     * @return
     */
    private boolean isChartInvalidBarObj(Chart chart, List<SubPathData> subPathDatas) {
        double width = chart.getWidth();
        double height = chart.getHeight();
        int n = subPathDatas.size();
        for (int i = 0; i < n; i++) {
            SubPathData subPathData = subPathDatas.get(i);
            // 如果不是填充对象
            if (!subPathData.isFill) {
                return true;
            }
            // 如果不是矩形对象
            if (!subPathData.isBox()) {
                return true;
            }
            // 如果尺寸过大
            Rectangle2D box = subPathData.getBound();
            double h = box.getHeight(), w = box.getWidth();
            double hScale = h / height;
            double wScale = w / width;
            if (hScale > 0.5 && wScale > 0.5) {
                return true;
            }
            // 尺寸过小
            if (h < 1E-3 || w < 1E-3) {
                return true;
            }
        } // end for i
        return false;
    }

    /**
     * 判断给定subpathdata是否为无效折线对象
     * @param chart
     * @param subPathDatas
     * @return
     */
    private boolean isChartInvalidLineObj(Chart chart, List<SubPathData> subPathDatas) {
        double width = chart.getWidth();
        double height = chart.getHeight();
        int n = subPathDatas.size();
        int count = 0;
        for (int i = 0; i < n; i++) {
            SubPathData subPathData = subPathDatas.get(i);
            if (subPathData.isBox()) {
                Rectangle2D box = subPathData.getBound();
                if (box.getHeight() > 0.5 * height && box.getWidth() > 0.1 * width) {
                    return true;
                }
                else if (box.getHeight() > 0.1 * height && box.getWidth() > 0.5 * width) {
                    return true;
                }
            }
            if (PathClassifyUtils.isXYDirline(subPathData.xs, subPathData.ys) == -1) {
                continue;
            }
            if (subPathData.isLargeXYLine(true, 0.5 * width, 0.01 * height)) {
                count++;
            }
            else if (subPathData.isLargeXYLine(false, 0.5 * height, 0.01 * width)) {
                count++;
            }
        } // end for i
        if (count >= 2) {
            return true;
        }
        return false;
    }

    /**
     * 解析给定ContentGroup中的线对象
     * @param box
     * @param groups
     */
    private void parserLineGroups(Rectangle2D box, List<ContentGroup> groups) {
        if (groups.isEmpty()) {
            return;
        }

        List<ContentItem> items = getContentItems(groups);
        List<SubPathData> subPathDatas = splitContentItems(items, false);
        if (subPathDatas.isEmpty()) {
            return;
        }

        int n = subPathDatas.size();
        Color color = ((PathItem)items.get(0)).getColor();
        for (int i = 0; i < n; i++) {
            SubPathData subPathData = subPathDatas.get(i);
            PathInfo.PathType pathType = subPathData.getLineType();
            GeneralPath path = subPathData.resetLine(box);
            if (path == null) {
                continue;
            }
            ChartPathInfosParser.savePathIntoPathInfo(path, pathType, color, paths, pathInfos);
        }
    }

    private void parserLinePath(Rectangle2D box, List<ContentGroup> groups, PathInfoData pathInfoData) {
        parserLineGroups(box, groups);
    }

    /**
     * 解析给定ContentGroup中的柱状对象
     * @param box
     * @param groups
     * @param isVertical
     */
    private void parserColumnGroups(Rectangle2D box, List<ContentGroup> groups, boolean isVertical) {
        if (groups.isEmpty()) {
            return;
        }

        List<ContentItem> items = getContentItems(groups);
        List<SubPathData> subPathDatas = splitContentItems(items, true);
        if (subPathDatas.isEmpty()) {
            return;
        }
        int n = subPathDatas.size();
        if (n >= 200) {
            return;
        }
        double width = box.getWidth();
        double height = box.getHeight();
        double lenBase = isVertical ? 0.8 * width : 0.8 * height;
        Color color = ((PathItem)items.get(0)).getColor();
        for (int i = 0; i < n; i++) {
            SubPathData subPathData = subPathDatas.get(i);
            if (!subPathData.isValidBar(isVertical, lenBase)) {
                continue;
            }
            ChartPathInfosParser.savePathIntoPathInfo(subPathData.path, PathInfo.PathType.COLUMNAR, color, paths, pathInfos);
        }
    }

    private void parserColumnPath(Rectangle2D box, List<ContentGroup> groups, PathInfoData pathInfoData) {
        parserColumnGroups(box, groups, pathInfoData.types.contains("BAR_CHART"));
    }

    private boolean isSeparatedPathItem(PathItem pathItemA, PathItem pathItemB, double extendValue) {
        Rectangle2D boxA = (Rectangle2D) pathItemA.getBounds().clone();
        Rectangle2D boxB = (Rectangle2D) pathItemB.getBounds().clone();
        GraphicsUtil.extendRect(boxA, extendValue);
        GraphicsUtil.extendRect(boxB, extendValue);
        if (boxA.intersects(boxB)) {
            return false;
        }
        else {
            return true;
        }
    }

    private boolean isSpaceAlignment(PathItem pathItemA, PathItem pathItemB, double lenBase) {
        Rectangle2D boxA = pathItemA.getBounds();
        Rectangle2D boxB = pathItemB.getBounds();
        double lenTop = Math.abs(boxA.getMinY() - boxB.getMinY());
        double lenBottom = Math.abs(boxA.getMaxY() - boxB.getMaxY());
        if (lenTop < lenBase || lenBottom < lenBase) {
            return true;
        }
        else {
            return false;
        }
    }

    private boolean isValidTextPaths(List<ContentItem> items) {
        if (items == null || items.isEmpty()) {
            return false;
        }
        // 判断空间是否对齐
        int n = items.size();
        for (int i = 0; i < n - 2; i++) {
            PathItem pathItemA = (PathItem)items.get(i);
            PathItem pathItemB = (PathItem)items.get(i + 1);
            PathItem pathItemC = (PathItem)items.get(i + 2);
            if (isSeparatedPathItem(pathItemA, pathItemB, 2.0) &&
                    isSeparatedPathItem(pathItemB, pathItemC, 2.0)) {
                if (!isSpaceAlignment(pathItemA, pathItemB, 10.0)) {
                    return false;
                }
            }
        } // end for i
        // 判断尺寸
        Rectangle2D box = (Rectangle2D) ((PathItem)items.get(0)).getBounds().clone();
        for (int i = 1; i < n; i++) {
            Rectangle2D box2 = (Rectangle2D) ((PathItem)items.get(i)).getBounds().clone();
            Rectangle2D.union(box, box2, box);
        }
        double width = box.getWidth();
        double height = box.getHeight();
        if (Math.max(width, height) < 5.0) {
            return false;
        }
        if (Math.min(width, height) < 3.0) {
            return false;
        }
        // 分割成小图形
        List<SubPathData> subPathDatas = splitContentItems(items, true);
        if (subPathDatas.isEmpty()) {
            return false;
        }
        // 判断小图形有效性
        for (SubPathData subPathData : subPathDatas) {
            if(subPathData.isBox()) {
                return false;
            }
            Rectangle2D subPathDataBound = subPathData.getBound();
            width = subPathDataBound.getWidth();
            height = subPathDataBound.getHeight();
            if (Math.max(width, height) > 50) {
                return false;
            }
        }
        return true;
    }

    private void parserTextPath(List<ContentGroup> groups, PathInfoData pathInfoData) {
        List<ContentItem> items = getContentItems(groups);
        // 判断数据有效性
        if (!isValidTextPaths(items)) {
            return;
        }
        int n = items.size();
        for (int i = 0; i < n; i++) {
            PathItem pathItem = (PathItem)items.get(i);
            ocrs.add(new OCRPathInfo(pathItem.getItem(), pathItem.getItem().getWindingRule()));
        }
    }

    private void parserArcPath(List<ContentGroup> groups, PathInfoData pathInfoData) {
        List<ContentItem> items = getContentItems(groups);
        int n = items.size();
        for (int i = 0; i < n; i++) {
            PathItem pathItem = (PathItem)items.get(i);
            Color color = pathItem.getColor();
            GeneralPath path = pathItem.getItem();

            // 判断是否为环形对象内部填充圆形对象
            if (ChartPathInfosParser.isCirclePath(pathItem, arcs)) {
                ChartPathInfosParser.saveArcs(arcs, pies);
                continue;
            }

            // 判断单个路径是否为扇形 myyang need to debugger
            ChartPathInfosParser.ArcObject arcobj = new ChartPathInfosParser.ArcObject();

            // 判断是否为环形图子图对象
            if (ChartPathInfosParser.isRingPath(pathItem, arcobj, arcs, pies)) {
                arcobj.color = color;
                arcobj.path = (GeneralPath) path.clone();
                arcs.add(arcobj);
                continue;
            }

            boolean pathIsArc = ChartPathInfosParser.isPathArc(path, arcobj);
            if (pathIsArc) {
                arcobj.color = color;
                arcobj.path = (GeneralPath) path.clone();
                arcs.add(arcobj);
            }
            // 判断是否为小块的　等要三角形
            else if (ChartPathInfosParser.isPathArcTriangle(path, arcobj)) {
                arcobj.color = color;
                arcobj.path = (GeneralPath)path.clone();
                tris.add(arcobj);
            }
        }
    }

    private void parserAreaPath(Rectangle2D box, List<ContentGroup> groups, PathInfoData pathInfoData) {
        List<ContentItem> items = getContentItems(groups);
        List<SubPathData> subPathDatas = splitContentItems(items, true);
        if (subPathDatas.isEmpty()) {
            return;
        }
        int n = subPathDatas.size();
        double width = box.getWidth();
        double height = box.getHeight();
        Color color = ((PathItem)items.get(0)).getColor();
        for (int i = 0; i < n; i++) {
            SubPathData subPathData = subPathDatas.get(i);
            if (!subPathData.isArea(0.01 * width * height)) {
                continue;
            }
            ChartPathInfosParser.savePathIntoPathInfo(subPathData.path, PathInfo.PathType.AREA, color, paths, pathInfos);
        }
    }
}

/**
 * 基本内容类型
 */
enum BaseContentType {
    UNKNOWN(-1),                   //未知类型
    TEXT(1),                       //文字
    FILL(10),                      //填充
    STROKE(20);                    //轮廓
    private final int value;       //整数值表示

    BaseContentType(int value) { this.value = value; }
    public int getValue() { return value; }
}  // end enum

/**
 * 网格单元内存储的Page内容流的基本内容
 */
class BaseContent {
    public int iFirst = -1;                                 // 一级序号
    public int iSecond = -1;                                // 二级序号
    public BaseContentType type = BaseContentType.UNKNOWN;  // 基本内容类型

    BaseContent(int iFirst, int iSecond, BaseContentType type) {
        this.iFirst = iFirst;
        this.iSecond = iSecond;
        this.type = type;
    }
}

/**
 * 网格单元格对象
 */
class GridContent {
    public int i = 0;                                       // 行号
    public int j = 0;                                       // 列号
    public Set<BaseContent> ids = new HashSet<>();          // 单元格内存储的内容

    GridContent(int i, int j) {
        this.i = i;
        this.j = j;
    }

    // 添加内容
    public void addContent(BaseContent content) {
        ids.add(content);
    }

    // 统计指定类型元素数目
    public long getContentNum(BaseContentType type) {
        return ids.stream().filter(content -> content.type == type).count();
    }

    // 评估一个单元格内容的代表颜色 方便可视化
    public Color getColor() {
        if (ids.isEmpty()) {
            return new Color(255, 255, 255);
        }
        long nText = getContentNum(BaseContentType.TEXT);
        long nFill = getContentNum(BaseContentType.FILL);
        long nStroke = getContentNum(BaseContentType.STROKE);
        if (nText >= 1) {
            if (nFill + nStroke == 0) {
                return new Color(0, 255, 255);
            }
            else {
                return new Color(0, 0, 255);
            }
        }
        else if (nText == 0){
            if (nFill >=1 && nStroke == 0) {
                return new Color(255, 0, 0);
            }
            else if (nFill == 0 && nStroke >= 1) {
                return new Color(0, 255, 0);
            }
            else if (nFill >= 1 && nStroke >= 1) {
                return new Color(255, 255, 0);
            }
        }
        return new Color(255, 255, 255);
    }
}

/**
 * Page的均匀网格
 */
class Grid {
    public int nLine = 0;                                   // 网格行数
    public int nColumn = 0;                                 // 网格列数
    public double width = 0.0;                              // Page宽度
    public double height = 0.0;                             // Page高度
    public double dx = 0.0;                                 // 单元格列步长
    public double dy = 0.0;                                 // 单元格行步长
    public List<GridContent> elements = new ArrayList<>();  // 网格系统存储的所有的单元集

    public static long id = 1;

    Grid(int nLine, int nColumn, double width, double height) {
        this.nLine = nLine;
        this.nColumn = nColumn;
        this.width = width;
        this.height = height;
        this.dx = width / nColumn;
        this.dy = height / nLine;
        for (int i = 0; i < nLine; i++) {
            for (int j = 0; j < nColumn; j++) {
                elements.add(new GridContent(i, j));
            }
        }
    }

    // 获取指定行列号的网格单元格
    GridContent get(int iline, int icolumn) {
        int ith = iline * nColumn + icolumn;
        return elements.get(ith);
    }

    // 计算给定点所在的网格行列号信息
    Pair<Integer, Integer> locatPoint(Point2D p) {
        double x = p.getX(), y = p.getY();
        if (x < 0.0 || x > width || y < 0.0 || y > height) {
            return null;
        }
        return Pair.of((int)(x/dx), (int)(y/dy));
    }

    // 给定点和类型信息 添加进网格中
    void addPoint(Point2D p, BaseContent content) {
        Pair<Integer, Integer> pos = locatPoint(p);
        if (pos == null) {
            return;
        }
        GridContent grid = get(pos.getRight(), pos.getLeft());
        grid.addContent(content);
    }

    // 给定线段和类型信息 添加进网格中
    void addLine(Point2D p1, Point2D p2, BaseContent content) {
        double len = p1.distance(p2);
        double dxy = 0.5 * Math.min(dx, dy);
        int n = (int)Math.floor(len / dxy);
        double x1 = p1.getX(), y1 = p1.getY();
        double x2 = p2.getX(), y2 = p2.getY();
        double x = 0, y = 0;
        Point2D p = new Point2D.Double(x1, y1);
        addPoint(p, content);
        for (int i = 1; i < n; i++) {
            x = x1 + (x2 - x1) * i / n;
            y = y1 + (y2 - y1) * i / n;
            p.setLocation(x, y);
            addPoint(p, content);
        }
        p.setLocation(x2, y2);
        addPoint(p, content);
    }

    // 给定Path和类型信息 添加进网格中
    void addPath(PathItem item, BaseContent content) {
        // 过滤页眉页脚
        Rectangle2D box = item.getItem().getBounds2D();
        if (box.getMaxY() < 0.1 * height ||
                box.getMinY() > 0.9 * height) {
            return;
        }

        // 获取path关键点信息
        List<Double> xs = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
        List<Integer> types = new ArrayList<>();
        GeneralPath path = item.getItem();
        if (!PathUtils.getPathKeyPtsInfo(path, xs, ys, types)) {
            return;
        }
        int type = 0;
        int n = types.size();
        Point2D p0 = new Point2D.Double(0, 0);
        Point2D p1 = new Point2D.Double(0, 0);
        Point2D p2 = new Point2D.Double(0, 0);
        for (int i = 0; i < n; i++) {
            type = types.get(i);
            p1.setLocation(xs.get(i), ys.get(i));
            if (type == PathIterator.SEG_MOVETO) {
                p0.setLocation(xs.get(i), ys.get(i));
            }
            else if (type == PathIterator.SEG_CLOSE) {
                addLine(p1, p0, content);
                continue;
            }
            if (i + 1 < n) {
                p2.setLocation(xs.get(i + 1), ys.get(i + 1));
                addLine(p1, p2, content);
            }
        } // end for i
    }

    // 给定填充Path和类型信息 添加进网格中
    void addFillPath(PathItem item, BaseContent content) {
        addPath(item, content);
    }

    // 给定矩形对象和类型信息 添加进网格中
    void addRectange2D(Rectangle2D box, BaseContent content) {
        Point2D p1 = new Point2D.Double(box.getMinX(), box.getMinY());
        Point2D p2 = new Point2D.Double(box.getMinX(), box.getMaxY());
        addLine(p1, p2, content);
        Point2D p3 = new Point2D.Double(box.getMaxX(), box.getMaxY());
        addLine(p2, p3, content);
        Point2D p4 = new Point2D.Double(box.getMaxX(), box.getMinY());
        addLine(p3, p4, content);
        addLine(p4, p1, content);
    }

    // 给定TextChunk对象和类型信息 添加进网格中
    void addTextChunk(TextChunkItem item, BaseContent content) {
        TextChunk chunk = item.getItem();
        if (StringUtils.isEmpty(chunk.getText().trim())) {
            return;
        }
        Rectangle2D box = chunk.getBounds2D();
        addRectange2D(box, content);
    }

    // 可视化网格  方便查看网格存储的内容流特征信息  提高开发效率
    void output(String dir, String filename) {
        if (elements.isEmpty()) {
            return;
        }
        // 如果给定文件夹不存在　则尝试建立　如果不成功　则返回 false
        File myFolderPath = new File(dir);
        FileOutputStream output = null;
        try {
            if (!myFolderPath.exists()) {
                myFolderPath.mkdir();
            }
            File saveFile = new File(dir, filename);
            output = new FileOutputStream(saveFile);
            BufferedImage chartImage = GraphicsUtil.getBufferedImageRGB(nColumn, nLine);
            for (int i = 0; i < nLine; i++) {
                for (int j = 0; j < nColumn; j++) {
                    GridContent ele = get(i, j);
                    Color color = ele.getColor();
                    chartImage.setRGB(j, i, color.getRGB());
                }
            }

            ImageIO.write(chartImage, "JPG", output);
        }
        catch (Exception e) {
            return;
        } finally {
            IOUtils.closeQuietly(output);
        }
    }
}

class PathBox {
    public int status = -1;
    public Rectangle2D box = null;
    public List<Integer> items = new ArrayList<>();
    public List<TextChunk> chunks = new ArrayList<>();
    public List<ImageClassifyResult> types = new ArrayList<>();
    public TextChunk nearTitle = null;
    public TextChunk nearSource = null;
    public boolean extendTitle = false;

    public PathBox(int id, Rectangle2D box) {
        this.box = (Rectangle2D) box.clone();
        items.add(id);
        status = 0;
    }

    public boolean intersect(PathBox pathBox) {
        return box != null && pathBox.box != null && box.intersects(pathBox.box);
    }

    public void merge(PathBox pathBox) {
        if (box == null || pathBox.box == null) {
            return;
        }
        Rectangle2D.union(box, pathBox.box, box);
        items.addAll(pathBox.items);
        chunks.addAll(pathBox.chunks);
    }

    public double getArea() {
        if (box == null) {
            return 0.0;
        }
        return box.getWidth() * box.getHeight();
    }

    public void addChunk(TextChunk chunk) {
        if (box != null && box.contains(chunk)) {
            chunks.add(chunk);
        }
    }

    /**
     * 基于标题和来源文本　尝试扩展区域
     * @param pathBoxes
     */
    public void extendBaseTitleSource(List<PathBox> pathBoxes) {
        if (nearTitle != null) {
            mergeTextChunkIntoPathBox(this, nearTitle, pathBoxes);
            extendTitle = true;
        }
        if (nearSource != null) {
            mergeTextChunkIntoPathBox(this, nearSource, pathBoxes);
        }
    }

    /**
     * 统计给定pathBox同其他对象相交的个数
     * @param pathBoxes
     * @param pathBox
     * @return
     */
    public static int countIntersectOtherBox(List<PathBox> pathBoxes, PathBox pathBox) {
        int count = 0;
        for (int i = 0; i < pathBoxes.size(); i++) {
            PathBox box = pathBoxes.get(i);
            if (box.equals(pathBox)) {
                continue;
            }
            if (pathBox.box.intersects(box.box)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 尝试合并给定矩形和pathbox　如果合并后的区域不和给定其他pathBoxes相交
     * @param pathBox
     * @param chunk
     * @param pathBoxes
     */
    public static void mergeTextChunkIntoPathBox(
            PathBox pathBox, TextChunk chunk, List<PathBox> pathBoxes) {
        // 判断合并的文字对象在内部标题或是来源文字上方下方  过滤掉标题和来源之外的文字
        double yc = chunk.getCenterY();
        double yp = pathBox.box.getCenterY();
        if (pathBox.nearSource != null) {
            double ys = pathBox.nearSource.getCenterY();
            if (ys < yc && ys > yp) {
                return;
            }
        }
        if (pathBox.nearTitle != null) {
            double yt = pathBox.nearTitle.getCenterY();
            if (yt > yc && yt < yp) {
                return;
            }
        }

        // 尝试合并
        Rectangle2D initBox = (Rectangle2D)pathBox.box.clone();
        Rectangle2D.union(pathBox.box, chunk, pathBox.box);
        // 如果合并后区域和其他pathBoxes相交　则不合并　直接返回
        int count = countIntersectOtherBox(pathBoxes, pathBox);
        if (count >= 1) {
            pathBox.box = initBox;
            return;
        }

        // 判断是否标题或是来源文本信息　如果是则保存下来
        String text = chunk.getText().trim();
        if (ChartTitleLegendScaleParser.isDataSourceTextChunk(chunk)) {
            pathBox.nearSource = chunk;
        } else if (ChartTitleLegendScaleParser.bHighProbabilityChartTitle(text) ||
                ChartTitleLegendScaleParser.bHighProbabilityTableTitle(text)) {
            pathBox.nearTitle = chunk;
        }

        // 添加chunk
        if (!pathBox.chunks.contains(chunk)) {
            pathBox.addChunk(chunk);
        }
    }

    /**
     * 过滤掉和现有Chart区域存在重叠的对象
     * @param pathBoxes
     */
    public static void filterOverlapChart(List<PathBox> pathBoxes, List<Chart> charts) {
        // 判断数据是否有效
        if (pathBoxes.isEmpty() || charts == null || charts.isEmpty()) {
            return;
        }

        // 遍历判断　是否存在重叠
        Iterator<PathBox> each = pathBoxes.iterator();
        while (each.hasNext()) {
            PathBox pathBox = each.next();
            Point2D pt = new Point2D.Double(pathBox.box.getCenterX(), pathBox.box.getCenterY());
            for (Chart chart : charts) {
                Rectangle area = chart.getArea();
                Point2D cpt = area.getCenter();
                // 如果存在彼此中心点存在对方区域内部　则删除
                if (area.contains(pt) || pathBox.box.contains(cpt)) {
                    each.remove();
                    break;
                }
            }
        }
    }

}
