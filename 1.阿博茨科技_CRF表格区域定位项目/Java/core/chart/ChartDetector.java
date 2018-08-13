package com.abcft.pdfextract.core.chart;

import com.abcft.pdfextract.core.ExtractorUtil;
import com.abcft.pdfextract.core.Metas;
import com.abcft.pdfextract.core.PdfExtractContext;
import com.abcft.pdfextract.core.model.ContentGroup;
import com.abcft.pdfextract.core.model.TextChunk;
import com.abcft.pdfextract.core.model.TextDirection;
import com.abcft.pdfextract.core.model.TextElement;
import com.abcft.pdfextract.spi.ChartType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageTree;

import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Created by dhu on 10/01/2017.
 */
public class ChartDetector {
    private static Logger logger = LogManager.getLogger(ChartDetector.class);

    private final ChartExtractParameters params;
    private final PdfExtractContext context;
    private boolean isPPT = false;

    public ChartDetector(ChartExtractParameters params) {
        this.params = params;
        this.context = params.getExtractContext();
    }

    public PDDocument getDocument() {
        return context.getNativeDocument();
    }

    /**
     * 在进入矢量Chart解析时　重置只包含一个TextElement的TextChunk并且Chunk方向为TextDirection.UNKNOWN,
     * 将其内部TextElement也设为TextDirection.UNKNONW
     * 主要用途是避免前面其他解析模块对上述对象内部TextElement方向进行的任何设置
     * 遇到了经过正文解析后，将部分上述对象的TextElement设置为水平方向的情况，会干扰矢量Chart内的文字合并效果
     * @param chunks
     */
    private void resetPageAllTextElementDirection(List<TextChunk> chunks) {
        for (TextChunk chunk : chunks) {
            // 跳过有方向的TextChunk
            if (chunk.getDirection() != TextDirection.UNKNOWN) {
                continue;
            }
            // 跳过包含多个TextElement的TextChunk
            List<TextElement> elements = chunk.getElements();
            if (elements.size() != 1) {
                continue;
            }
            for (TextElement element : elements) {
                element.setDirection(TextDirection.UNKNOWN);
            }
        }
    }

    public void processPage(PdfExtractContext context, int pageIndex, PDPage page, ChartExtractionResult result, ChartCallback callback) {
        boolean pptChecked = params.meta.containsKey(Metas.IS_PPT);
        if (!pptChecked) {
            checkIsPPT();
            params.meta.put(Metas.IS_PPT, isPPT);
        } else {
            isPPT = (boolean) params.meta.get(Metas.IS_PPT);
        }
        try {
            ContentGroup contentGroup = context.getPageContentGroup(page);
            if (null == contentGroup) {
                logger.warn("Page {}: Failed to load content group.", pageIndex + 1);
                return;
            }

            // 如果内部XObject对象个数大于 100   此时一般是包含多个小图片对象, 处理速度慢
            // 目前采取策略是跳过
            int xObjectSize = ExtractorUtil.getPdfPageXObjectSize(page);
            if (xObjectSize > 1500) {
                logger.warn("Page {}: XObjects too much ", pageIndex + 1);
                return;
            }

            // 重置部分文字方向
            List<TextChunk> chunks = contentGroup.getAllTextChunks();
            resetPageAllTextElementDirection(chunks);

            List<DetectedObjectTF> pageDetectedInfo = null;
            // 判断当前Page内部是否有有效Path对象
            boolean valid = ChartContentDetector.hasValidPath(contentGroup, page.getMediaBox());
            // 如果有则尝试调用位图检测服务
            if (valid) {
                // 尝试用本地TF学习模型检测当前Page内部的Chart和Table对象信息
                TFDetectChartTable detectModel = TFDetectChartTable.getInstance();
                pageDetectedInfo = detectModel.predictPageInnerChartTable(getDocument(), pageIndex);
                // 如果没有检测结果　则尝试通过GRPC方式调用从图片中检测Chart区域的服务
                if (pageDetectedInfo == null && params.detectChart) {
                    pageDetectedInfo = DetectEngine.detectChartInfos(getDocument(), pageIndex);
                }
            }

            // 加入手工输入区域信息 判断hint-area有效性
            // 由于grpc传入的页码是从1开始，并且没有经过类似ExtractBase将hintArea的页码减1
            // 所以在判断当前page是否有hint-area数据时将页码加1
            // 本地线下测试时　注意将输入页码加1 这样就能一致处理
            boolean onlyParseHintArea = false;
            if (params.hintAreas != null && !params.hintAreas.isEmpty() && params.hintAreas.containsKey(pageIndex + 1)) {
                List<Rectangle2D> areas = params.hintAreas.getAreas(pageIndex + 1);
                for (int i = 0; i < areas.size(); i++) {
                    Rectangle2D area = areas.get(i);
                    logger.info("hint-area {} :  page {} : {} ", i, pageIndex + 1, area);
                }
                double width = page.getMediaBox().getWidth();
                double height = page.getMediaBox().getHeight();
                List<DetectedObjectTF> pageHintAreas = DetectedChartTableTF.buildByHintAreas(areas, width, height);
                if (pageHintAreas != null && !pageHintAreas.isEmpty()) {
                    // 如果给出了 hint-area 就设置状态
                    onlyParseHintArea = true;
                    if (pageDetectedInfo == null) {
                        pageDetectedInfo = pageHintAreas;
                    }
                    else {
                        pageDetectedInfo.addAll(pageHintAreas);
                    }
                }
            }

            // 准备path组分类模型 暂时关闭
            //ChartPathClassify.getRFModel();

            // 解析ContentGroup
            String item = "pdfName";
            String pdfName = "";
            if (params.meta.containsKey(item)) {
                pdfName = (String) params.meta.get(item);
            }
            List<Chart> charts = ChartContentDetector.detectChart(
                    context, pageIndex, pageDetectedInfo, params.checkBitmap,
                    onlyParseHintArea, params.useChartClassify, pdfName);

            // 设置位图对象的cropImage 从PageImage裁剪区域
            setBitmapChartCropImage(charts, pageIndex);

            // 处理PPT样式的Page
            if (!onlyParseHintArea) {
                dealPPTPage(contentGroup, page, charts);
            }

            if (callback != null && charts != null) {
                int index = 0;
                for (Chart chart : charts) {
                    chart.page = page;
                    chart.pageIndex = pageIndex;
                    chart.setChartIndex(index++);
                    callback.onItemExtracted(chart);
                }
            }
            context.addCharts(charts);
            result.addCharts(charts);
        } catch (Throwable e) {
            logger.warn("process page: {} failed, error: {}", pageIndex, e.getMessage());
            if (callback != null) {
                callback.onFatalError(e);
            } else {
                logger.warn("error", e);
            }
        }
    }

    /**
     * 将上述解析出的位图对象直接从Page截取图片　根本上解决黑色或是透明背景问题
     * @param charts
     */
    private void setBitmapChartCropImage(List<Chart> charts, int pageIndex) {
        List<Chart> bcharts = new ArrayList<>();
        for (Chart chart : charts) {
            if (chart.type == ChartType.BITMAP_CHART && chart.cropImage == null) {
                bcharts.add(chart);
            }
        }

        if (!bcharts.isEmpty()) {
            //long start = System.currentTimeMillis();
            BufferedImage pageImage = DetectEngine.getOneShutPageImage(context.getNativeDocument(), pageIndex, 72 * 2);
            ChartUtils.setChartsAreaCropImage(bcharts, pageImage, 2.0);
            //long costTime = System.currentTimeMillis() - start;
            //ChartClassify.savePageImageTime += costTime;
            //ChartClassify.savePageImageCount++;
        }
    }

    private void checkIsPPT() {
        boolean isPPT = true;
        PDPageTree pages = context.getNativeDocument().getPages();
        for (int i = 0; i < pages.getCount(); i++) {
            PDPage page = pages.get(i);
            Point2D.Float pageSize = ExtractorUtil.determinePageSize(page);
            if (pageSize.getX() < pageSize.getY()) {
                isPPT = false;
                break;
            }
        }
        this.isPPT = isPPT;
    }

    private void dealPPTPage(ContentGroup pageContentGroup, PDPage page, List<Chart> charts) {
        // 如果不是PPT样式或者未开启PPT解析, 则直接返回
        if (!isPPT || !params.savePPT) {
            return;
        }

        // 单独处理PPT样式的PDPage 解析出来可能含有标题和内部文字信息的特殊 Chart
        // 不需要在进行内部图形元素的解析处理 故跳过 onPageEnd
        Chart chartPPT = PPTChartDetector.detect(pageContentGroup, page);
        // 如果解析出来的Chart为空对象　则直接返回
        if (chartPPT == null) {
            return;
        }

        // 进一步判断是否整页是一个位图
        List<Chart> bitmapCharts = charts.stream()
                .filter(chart -> chart.type.equals(ChartType.BITMAP_CHART))
                .collect(Collectors.toList());
        boolean hasOnlyOneImage = (bitmapCharts.size() == 1 && chartPPT.texts.isEmpty());
        if (hasOnlyOneImage) {
            // 判断面积占比
            Rectangle2D boxBit = bitmapCharts.get(0).getArea();
            Rectangle2D boxPPT = chartPPT.getArea();
            double coef = boxBit.getHeight() * boxBit.getWidth() / (boxPPT.getWidth() * boxPPT.getHeight());
            if (coef > 0.8) {
                bitmapCharts.get(0).isPPT = true;
                return;
            }
        }
        for (Chart chart : charts) {
            if (chart.type == ChartType.BITMAP_CHART || chart.type == ChartType.UNKNOWN_CHART) {
                continue;
            }
            chartPPT.isParsedPPT = true;
            break;
        } // end for chart
        charts.add(chartPPT);
    }

    public ChartExtractParameters getParams() {
        return params;
    }

}
