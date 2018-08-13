package com.abcft.pdfextract.core.chart;

import com.abcft.pdfextract.spi.ChartType;
import com.abcft.pdfextract.spi.algorithm.AlgorithmGrpcClient;
import com.abcft.pdfextract.spi.algorithm.ImageClassifyResult;
import com.abcft.pdfextract.spi.algorithm.ImageType;
import com.google.protobuf.ByteString;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by myyang on 17-12-14.
 */
public class ChartClassify {
    private static Logger logger = LogManager.getLogger();
    private static AlgorithmGrpcClient client = null;
    private static long imageClassifyTime = 0L;
    private static long renderPageImageTime = 0L;
    private static long imageClassifyCount = 0L;
    private static long renderPageImageCount = 0L;
    //public static long savePageImageTime = 0L;
    //public static long savePageImageCount = 0L;

    public static void setChartClassifyClient(AlgorithmGrpcClient client) {
        ChartClassify.client = client;
    }

    /**
     * 判断是否为有效的图片类型 忽略 有线表格　无线表格　正文　LOGO　二维码 other等类型
     * @param imageType
     * @return
     */
    public static boolean isValidImageType(String imageType) {
        switch (imageType) {
            case "GRID_TABLE":
            case "LINE_TABLE":
            case "TEXT":
            case "LOGO":
            case "QR_CODE":
            case "OTHER_MEANINGFUL":
            case "OTHER":
                return false;
        }
        return true;
    }

    /**
     * grpc方式调用位图分类服务
     * @param image
     * @return
     */
    public static List<ImageClassifyResult> getImageClassifyResult(BufferedImage image) {
        // 判断数据有效性
        if (client == null || image == null) {
            return null;
        }

        // 将BufferedImage对象转换为BytesString对象
        ByteString bytes = DetectEngine.bufferedImageToByteString(image);
        if (bytes == null) {
            return null;
        }

        // 通过GRPC方式调用　分类 位图Chart
        List<ImageClassifyResult> objs = null;
        try {
            long start = System.currentTimeMillis();
            objs = client.imageClassify(bytes);
            long costTime = System.currentTimeMillis() - start;
            logger.info("grpc image classify costs {}ms", costTime);
        } catch (Exception e) {
            logger.info("image classify failure");
            return null;
        }
        if (objs == null || objs.isEmpty()) {
            return null;
        }

        return objs;
    }

    public static String imageClassifyString(ImageClassifyResult obj) {
        if (obj == null) {
            return null;
        }
        return ImageType.desc(obj.type);
    }

    /**
     * 判断给定位图分类信息是否有效  目前策略是如果类型为正文, 有线表格, 无线表格, other，　则返回false
     * @param objs
     * @return
     */
    public static boolean isImageValid(List<ImageClassifyResult> objs) {
        if (objs == null || objs.isEmpty()) {
            return false;
        }

        // 遍历判断
        for (int i = 0; i < objs.size(); i++) {
            ImageClassifyResult obj = objs.get(i);
            String typeValue = ImageType.desc(obj.type);
            //logger.info(typeValue);
            if (isValidImageType(typeValue)) {
                return true;
            }
        }
        return false;
    }

    public static List<ChartType> imageClassifyToChartTypes(
            List<ImageClassifyResult> objs) {
        if (objs == null) {
            return null;
        }

        List<ChartType> types = new ArrayList<>();
        for (int i = 0; i < objs.size(); i++) {
            ImageClassifyResult obj = objs.get(i);
            ChartType type = imageClassifyToChartType(obj);
            if (type == ChartType.UNKNOWN_CHART) {
                types.clear();
                types.add(type);
                break;
            }
            else {
                types.add(type);
            }
        } // end for i
        return types;
    }

    public static ChartType imageClassifyToChartType(ImageClassifyResult obj) {
        String typeValue = ImageType.desc(obj.type);
        ChartType type = ChartType.UNKNOWN_CHART;
        if (ImageType.imageTypeIsChart(obj.type)) {
            switch (typeValue) {
                case "LINE_CHART":
                case "LINE_POINT_CHART":
                case "CURVE_CHART":
                    type = ChartType.LINE_CHART;
                    break;
                case "BAR_CHART":
                    type = ChartType.COLUMN_CHART;
                    break;
                case "COLUMN_CHART":
                    type = ChartType.BAR_CHART;
                    break;
                case "AREA_CHART":
                case "AREA_OVERLAP_CHART":
                    type = ChartType.AREA_CHART;
                    break;
                case "PIE_CHART":
                case "DONUT_CHART":
                    type = ChartType.PIE_CHART;
                    break;
                case "SCATTER_CHART":
                    type = ChartType.SCATTER_CHART;
                    break;
                case "RADAR_CHART":
                    type = ChartType.RADAR_CHART;
                    break;
                case "CANDLESTICK_CHART":
                    type = ChartType.CANDLESTICK_CHART;
                    break;
                case "DISCRETE_PLOT":
                    type = ChartType.DISCRETE_PLOT;
                    break;
                case "COMBO_CHART":
                    type = ChartType.COMBO_CHART;
                    break;
                default:
                    break;
            }
        }
        return type;
    }

    /**
     * 使用GRPC 分类 位图Chart
     * @param image
     * @return
     */
    public static List<ChartType> chartClassifyInfos(BufferedImage image) {
        // 通过GRPC方式调用　分类 位图Chart
        List<ImageClassifyResult> objs = getImageClassifyResult(image);
        return imageClassifyToChartTypes(objs);
    }

    /**
     * 对给定Page内的pathbox集通过grpc方式调用位图分类　得到分类信息
     * @param document
     * @param pageIndex
     * @param pathBoxes
     * @return
     */
    public static List<List<ImageClassifyResult>> pathBoxImageClassify(
            PDDocument document, int pageIndex, List<PathBox> pathBoxes) {
        // 判断参数有效性
        if  (client == null || document == null || pageIndex < 0 || pathBoxes == null || pathBoxes.isEmpty()) {
            return null;
        }

        // 保存区域信息
        List<Rectangle2D> areas = new ArrayList<>();
        for (PathBox pathBox : pathBoxes) {
            areas.add(pathBox.box);
        }

        // 调用位图分类服务
        List<List<ImageClassifyResult>> res = pageSubAreaClassify(document, pageIndex, areas);
        if (res == null || res.isEmpty() || res.size() != areas.size() ||
                res.stream().anyMatch(re -> re == null)) {
            return null;
        }
        else {
            return res;
        }
    }

    public static List<List<ImageClassifyResult>> pageSubAreaClassify(
            PDDocument document, int pageIndex, List<Rectangle2D> areas) {
        // 判断参数有效性
        if  (client == null || document == null || pageIndex < 0 || areas == null || areas.isEmpty()) {
            return null;
        }

        // 渲染page 尽量调用缓存中的图片
        float scale = 1.0f;
        BufferedImage imageFile = DetectEngine.getOneShutPageImage(document, pageIndex,  72);
        if (imageFile == null) {
            return null;
        }

        int w = imageFile.getWidth();
        int h = imageFile.getHeight();
        // 遍历区域
        List<List<ImageClassifyResult>> res = new ArrayList<>();
        for (Rectangle2D area : areas) {
            List<Integer> subRange = resetSubImageRange(w, h, area, scale);
            // 获取区域图片
            BufferedImage subImage = imageFile.getSubimage(
                    subRange.get(0), subRange.get(1), subRange.get(2), subRange.get(3));
            // 分类
            List<ImageClassifyResult> objs = getImageClassifyResult(subImage);
            if (objs == null) {
                res.clear();
                break;
            }
            res.add(objs);
        } // end for area
        return res;
    }

    /**
     * 分类给定页面内部制定区域的Chart类型
     * @param document
     * @param pageIndex
     * @param areas
     * @return
     */
    public static List<List<ChartType>> pageChartsClassifyInfos(
            PDDocument document, int pageIndex, List<Rectangle2D> areas) {
        List<List<ImageClassifyResult>> res = pageSubAreaClassify(document, pageIndex, areas);
        if (res == null || res.isEmpty() || res.size() != areas.size() ||
                res.stream().anyMatch(re -> re == null)) {
            return null;
        }

        List<List<ChartType>> types = new ArrayList<>();
        for (List<ImageClassifyResult> result : res) {
            List<ChartType> type = imageClassifyToChartTypes(result);
            types.add(type);
        } // end for area
        return types;
    }

    public static List<Integer> resetSubImageRange(int w, int h,Rectangle2D area, double scale) {
        double value = scale * area.getMinX();
        int xmin = value < 0 ? 0 : (int)value;
        value = scale * area.getMaxX();
        int xmax = value > w ? w - 1 : (int)value;
        value = scale * area.getMinY();
        int ymin = value < 0 ? 0 : (int)value;
        value = scale * area.getMaxY();
        int ymax = value > h ? h - 1 : (int)value;
        List<Integer> subRange = new ArrayList<>();
        subRange.add(xmin);
        subRange.add(ymin);
        subRange.add(xmax - xmin);
        subRange.add(ymax - ymin);
        return subRange;
    }

    public static void outTimeLog() {
        if (imageClassifyCount >= 1 && imageClassifyCount > 0 && renderPageImageCount > 0) {
            logger.info("image classify time :");
            logger.info(imageClassifyTime);
            logger.info("image count :  ");
            logger.info(imageClassifyCount);
            logger.info("mean time :");
            logger.info(imageClassifyTime / imageClassifyCount);
            logger.info("render page image time :");
            logger.info(renderPageImageTime);
            logger.info("page count :  ");
            logger.info(renderPageImageCount);
            logger.info("mean time :");
            logger.info(renderPageImageTime / renderPageImageCount);
        }
        /*
        if (savePageImageTime > 0 && savePageImageCount >= 1) {
            logger.info("save page image time :");
            logger.info(savePageImageTime);
            logger.info("page count :  ");
            logger.info(savePageImageCount);
            logger.info("mean time :");
            logger.info(savePageImageTime / savePageImageCount);
        }
        */
    }
}

