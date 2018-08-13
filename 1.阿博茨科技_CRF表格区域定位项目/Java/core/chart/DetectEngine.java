package com.abcft.pdfextract.core.chart;

import com.abcft.pdfextract.core.table.TableUtils;
import com.abcft.pdfextract.core.util.DebugHelper;
import com.abcft.pdfextract.spi.ChartType;
import com.abcft.pdfextract.spi.algorithm.AlgorithmGrpcClient;
import com.abcft.pdfextract.spi.algorithm.ChartDetectResult;
import com.google.protobuf.ByteString;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.output.ByteArrayOutputStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by myyang on 17-12-14.
 */
public class DetectEngine {
    private static Logger logger = LogManager.getLogger();
    private static AlgorithmGrpcClient client = null;

    // 统计分析各模块耗时  测试用
    private static long renderImageTime = 0L;
    private static long detectChartTime = 0L;
    private static long detectPageImage = 0L;

    public static void setDetectClient(AlgorithmGrpcClient client) {
        DetectEngine.client = client;
    }

    /**
     * 使用GRPC检测图片中Chart区域服务 检测给定PDF Page图片内的Chart区域信息
     * @param document
     * @param pageIndex
     * @return
     */
    public static List<DetectedObjectTF> detectChartInfos(PDDocument document, int pageIndex) {
        if  (client == null || document == null || pageIndex < 0) {
            return null;
        }

        //long startTime = System.currentTimeMillis();
        //BufferedImage imageFile = savePageImage(document, pageIndex, 72.0f);
        BufferedImage imageFile = getOneShutPageImage(document, pageIndex, 72);
        if (imageFile == null) {
            return null;
        }
        //long endTime = System.currentTimeMillis();
        //long renderTime = endTime - startTime;
        //renderImageTime += renderTime;

        // 调用检测图片方法
        List<DetectedObjectTF> results = detectChartInfos(imageFile);
        //long detectTime = System.currentTimeMillis() - endTime;
        //detectChartTime += detectTime;
        detectPageImage++;
        return results;
    }

    /**
     * 和表格解析共用一套保存Page图片方法
     * @param document
     * @param pageIndex
     * @return
     */
    public static BufferedImage getOneShutPageImage(PDDocument document, int pageIndex, int dpi) {
        BufferedImage pageImage = null;
        try {
            pageImage = DebugHelper.getOneShotPageImage(document.getPage(pageIndex), dpi);
        } catch (IOException e) {
            logger.warn("can't getOneShotPageImage");
        }
        if (pageImage == null) {
            return null;
        }
        return pageImage;
    }

    public static BufferedImage savePageImage(PDDocument document, int pageIndex, float dpi) {
        if  (document == null || pageIndex < 0) {
            return null;
        }
        PDFRenderer pdfRenderer = new PDFRenderer(document);
        try {
            // 将Page渲染成 BufferedImage 对象
            BufferedImage imageFile = pdfRenderer.renderImageWithDPI(pageIndex, dpi, ImageType.RGB);
            return imageFile;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 将BufferedImage对象转换为BytesString对象
     * @param image
     * @return
     */
    public static ByteString bufferedImageToByteString(BufferedImage image) {
        try {
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            ImageIO.write(image, "PNG", stream);
            IOUtils.closeQuietly(stream);
            byte[] data = stream.toByteArray();
            ByteString bytes = ByteString.copyFrom(data);
            return bytes;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 使用GRPC检测图片中Chart区域服务 检测给定图片内的Chart区域信息
     * @param image
     * @return
     */
    public static List<DetectedObjectTF> detectChartInfos(BufferedImage image) {
        // 判断数据有效性
        if (client == null || image == null) {
            return null;
        }

        // 将BufferedImage对象转换为BytesString对象
        ByteString bytes = bufferedImageToByteString(image);
        if (bytes == null) {
            return null;
        }

        // 通过GRPC方式调用　从图片中检测Chart对象区域
        List<ChartDetectResult> objs = null;
        try {
            objs = client.chartDetect(bytes);
        } catch (Exception e) {
            logger.info("chart detect failure");
            return null;
        }

        if (objs == null || objs.isEmpty()) {
            return null;
        }

        int width = image.getWidth();
        int height = image.getHeight();
        List<DetectedObjectTF> detectedObjects = new ArrayList<>();
        for (int i = 0; i < objs.size(); i++) {
            ChartDetectResult obj = objs.get(i);
            // 过滤掉非Chart类型的检测对象
            if (obj.type != 1) {
                continue;
            }
            // 过滤掉检测得分小于等于 0.9 的检测对象
            if (obj.score <= 0.9f) {
                continue;
            }

            // 将ChartDetectResult转换胃 DetectedObjectTF对象
            DetectedChartTableTF tBox = new DetectedChartTableTF();
            tBox.box[0] = (int)(obj.imgBound.getMinX());
            tBox.box[2] = (int)(obj.imgBound.getMaxX());
            tBox.box[1] = (int)((height - obj.imgBound.getMaxY()));
            tBox.box[3] = (int)((height - obj.imgBound.getMinY()));
            tBox.label = "chart";
            tBox.score = obj.score;
            tBox.byChartDetectModel = true;
            //tBox.extendDetectBox(width, height, 0.03);
            tBox.extendDetectBox(width, height, 0.00);
            tBox.setTypeID();
            tBox.setArea();

            // 裁剪图片
            tBox.setCropSubImage(image);

            // 尝试调用位图Chart分类服务
            tBox.chartClassify();

            // 如果不是有效Chart对象 直接跳过  (注:在调用了位图Chart分类服务后, 待测试准确率, 暂时关闭)
            //if (!tBox.isChart()) {
            //    continue;
            //}

            // 保存分类类型信息 方便测试用
            //tBox.saveChartTypeInfos();

            tBox.transferLeftUpOriginPoint(height);
            detectedObjects.add(tBox);
        } // end for i
        return detectedObjects;
    }

    /**
     * 输出位图检测Chart区域模块中渲染图片和GRPC调用检测服务的时间信息  测试用
     */
    public static void outRenderAndDetectTime() {
        long allTime = renderImageTime  + detectChartTime;
        if (allTime != 0) {
            logger.info("renderImageTime:");
            logger.info(renderImageTime);
            logger.info("time coef :  ");
            logger.info(1.0 * renderImageTime / allTime);
            logger.info("detectChartTime:");
            logger.info(detectChartTime);
            logger.info("time coef:");
            logger.info(1.0 * detectChartTime / allTime);
        }
    }

    /**
     * 输出调用位图检测次数　(优化减少调用位图检测模型次数)
     */
    public static void outputDetectPageImageStatus() {
        logger.info("Chart Detect Engine detect page image number:");
        logger.info(detectPageImage);
    }
}

