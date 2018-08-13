package com.abcft.pdfextract.core.chart;

import com.abcft.pdfextract.spi.ChartType;
import com.abcft.pdfextract.spi.OCRClient;
import com.google.common.collect.Lists;
import com.google.gson.JsonObject;
import org.apache.commons.io.IOUtils;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.io.*;
import java.io.IOException;
import java.util.*;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Created by myyang on 17-4-12.
 */
public class OCREngine {
    private static Logger logger = LogManager.getLogger();
    private static OCRClient client = null;

    public static void setOCRClient(OCRClient client) {
        OCREngine.client = client;
    }

    /**
     * 设置从Chart解析出来的斜着快额度的OCRPath 的组编号和有效的X坐标值
     * @param chart
     * @param ocrs
     * @return
     */
    public static boolean setOCRPathsData(
            Chart chart, List<OCRPathInfo> ocrs) {
        // 如果数据无效
        if (ocrs == null || ocrs.isEmpty()) {
            return false;
        }

        // 判断斜着刻度是顺时针还是逆时针方向
        int n = ocrs.size();
        boolean ccw = true;
        if (n >= 2) {
            List<Double> xs = new ArrayList<>();
            List<Double> ys = new ArrayList<>();
            PathUtils.getPathPts(ocrs.get(0).path, xs, ys);
            Point2D p2 = ocrs.get(n - 1).path.getCurrentPoint();
            if (ys.get(0) <= p2.getY()) {
                ccw = false;
            }
        }

        // 计算最大角度
        double maxAngle = 0.0;
        double angle = 0.0;
        for (int i = 0; i < n; i++) {
            Rectangle2D bound = ocrs.get(i).path.getBounds2D();
            angle = Math.atan2(bound.getHeight(), bound.getWidth());
            maxAngle = maxAngle < angle ? angle : maxAngle;
        } // end for i

        double dh = chart.getArea().getHeight() * 0.05;
//        dh = dh > chart.pageHeight * 0.02 ? chart.pageHeight * 0.02 : dh;
        boolean bSmallAngle = maxAngle < 0.45 ? true : false;

        // 遍历找出合适的组编号　和　对象对应的有效X坐标值
        double xR = 0.0, yR = 0.0;
        double xL = 0.0, yL = 0.0;
        double dist = 0.0;
        int groupindex = 0;
        for (int i = 0; i < n; i++) {
            OCRPathInfo ocr = ocrs.get(i);
            GeneralPath path = ocr.path;
            Rectangle2D bound = path.getBounds2D();
            if (ccw) {
                // 左下角
                xL = bound.getMinX();
                yL = bound.getMaxY();
            }
            else {
                // 左上角
                xL = bound.getMinX();
                yL = bound.getMinY();
            }

            // 计算距离 判断是否同组号
            if (i == 0) {
                groupindex++;
            }
            else {
                dist = Math.abs(xR - xL) + Math.abs(yR - yL);
                if (dist >= dh) {
                    groupindex++;
                }
            }
            if (ccw) {
                // 右上角
                xR = bound.getMaxX();
                yR = bound.getMinY();
            }
            else {
                // 右下角
                xR = bound.getMaxX();
                yR = bound.getMaxY();
            }

            // 设置坐标位置
            ocr.groupIndex = groupindex;
            if (bSmallAngle) {
                ocr.xPos = 0;
                ocr.x = bound.getCenterX();
            }
            else {
                if (ccw) {
                    ocr.xPos = 1;
                    ocr.x = bound.getMaxX();
                }
                else {
                    ocr.xPos = -1;
                    ocr.x = bound.getMinX();
                }
            }
            ocr.ccw = ccw;
        } // end for i
        return true;
    }

    /**
     * 将小对象合并成组 (存在斜着刻度信息由多个小对象组成，一般中英文存在不同Path中)
     * @param chart
     * @param ocrs
     */
    public static void groupOCRInfos(Chart chart, List<OCRPathInfo> ocrs) {
        chart.ocrs.clear();
        // 将斜着的小对象合并成组　方便解析
        for (int i = 0; i < ocrs.size(); i++) {
            OCRPathInfo ocr = ocrs.get(i);
            if (chart.ocrs.size() < ocr.groupIndex) {
                chart.ocrs.add(ocr);
                continue;
            }

            // 将相同组号的对象合并成有效的刻度信息
            OCRPathInfo ocrNew = chart.ocrs.get(chart.ocrs.size() - 1);
            ChartPathInfosParser.compTwoGeneralPaths(ocrNew.path, ocr.path);
            // 重新设置其位置对应的X坐标
            if (ocrNew.xPos == 0) {
                ocrNew.x = 0.5 * (ocrNew.x + ocr.x);
            }
            else if (ocrNew.xPos == -1) {
            }
            else {
                ocrNew.x = ocr.x;
            }
        } // end for i

        // 过滤掉宽度非常小的无效 斜着刻度信息
        double w = chart.getArea().getWidth();
        double h = chart.getArea().getHeight();
        final Iterator<OCRPathInfo> ocrIter = chart.ocrs.iterator();
        while (ocrIter.hasNext()) {
            GeneralPath path = ocrIter.next().path;
            Rectangle2D bound = path.getBounds2D();
            if (bound.getWidth() < 0.02 * w || bound.getHeight() < 0.02 * h) {
                ocrIter.remove();
            }
        } // end while
    }

    /**
     * 初始化斜着刻度信息　设置编号　坐标值　分组
     * @param chart
     * @param ocrs
     * @return
     */
    public static boolean initChartOCRInfos(
            Chart chart, List<OCRPathInfo> ocrs) {
        // 判断数据有效性
        if (chart == null || ocrs == null || ocrs.isEmpty() ||
                chart.type == ChartType.BITMAP_CHART) {
            return false;
        }

        // 设置编号 计算有效X坐标值
        boolean bSet = setOCRPathsData(chart, ocrs);
        if (!bSet) {
            return false;
        }

        // 将小对象合并成组
        groupOCRInfos(chart, ocrs);
        // 如果合并后没有有效组　则返回　false
        if (chart.ocrs.isEmpty()) {
            return false;
        }
        return true;
    }

    /**
     * 判断给定Chart内部斜着刻度信息是否有效
     * @param chart
     * @return
     */
    public static boolean isValidScalseOCRInfos(Chart chart) {
        // 判断Path个数和水平坐标轴是否有效
        int n = chart.ocrs.size();
        if (n <= 2 || chart.hAxis == null) {
            return false;
        }

        // 计算区域范围
        double minX = chart.ocrs.get(0).path.getBounds2D().getMinX();
        double minY = chart.ocrs.get(0).path.getBounds2D().getMinY();
        double maxX = chart.ocrs.get(n - 1).path.getBounds2D().getMaxX();
        double maxY = chart.ocrs.get(n - 1).path.getBounds2D().getMaxY();
        double h = chart.getArea().getHeight();
        double w = chart.hAxis.getBounds2D().getWidth();
        if (maxY - minY < 0.05 * h || maxX - minX < 0.4 * w) {
            return false;
        }

        return true;
    }

    /**
     * 使用指定的OCR引擎识别从Chart内解析出来斜着的刻度信息
     * @param chart
     * @param ocrs
     * @return
     */
    public static boolean parserOCRInfos(
            Chart chart, List<OCRPathInfo> ocrs, boolean saveErrorImage) {
        // 初始化斜着刻度信息
        if (!initChartOCRInfos(chart, ocrs)) {
            return false;
        }

        // 如果只做初始化处理　不调用OCR引擎 则返回
        chart.ocrEngineInfo = new JsonObject();
        if (client == null) {
            chart.ocrEngineInfo.addProperty("ocrClient", "InValid");
            chart.ocrEngineInfo.addProperty("detectOCRImageNumber", 0);
            return false;
        }

        // 用当前时间的长整数　来作为刻度信息的ID 调试输出用
        Date date = new Date();
        long id = date.getTime();

        // 计算Path应该放大的合理倍数　方便OCR解析
        float coef = PathUtils.getOCRPathCoef(chart);

        // 遍历生成 ocr.path 对应的图片 保存到 List 中
        List<BufferedImage> images = new ArrayList<>();
        for (int i = 0; i < chart.ocrs.size(); i++) {
            OCRPathInfo ocr = chart.ocrs.get(i);
            ocr.path.setWindingRule(ocr.windingRule);

            // 将路径Path转换为 BufferedImage
            BufferedImage image = PathUtils.generalPathToImage(ocr.path, !ocr.ccw, coef);
            images.add(image);
        } // end for i

        // 批量式传输　远程访问OCR引擎服务 提升效率
        long start = System.currentTimeMillis();
        List<String> textes = client.ocr(images);
        long costTime = System.currentTimeMillis()-start;
        logger.info("ocr {} images costs {}ms", images.size(), costTime);

        chart.ocrEngineInfo.addProperty("ocrClient", "Valid");
        chart.ocrEngineInfo.addProperty("detectOCRImageNumber", chart.ocrs.size());
        if (textes != null) {
            chart.ocrEngineInfo.addProperty("ocrTextsNumber", textes.size());
        }
        else {
            chart.ocrEngineInfo.addProperty("ocrTextsNumber", 0);
        }
        chart.ocrEngineInfo.addProperty("grpcOCRCostTime", costTime);

        // 将远程解析得到的字符串信息 传递给Chart.ocrs 的内容中
        if (textes != null && images.size() == textes.size()) {
            for (int i = 0; i < chart.ocrs.size(); i++) {
                OCRPathInfo ocr = chart.ocrs.get(i);
                ocr.text = textes.get(i);

                // 输出图片　方便存储解析出错的图片　方便后续调试
                if (saveErrorImage) {
                    String filename = id + "." + i + ".png";
                    PathUtils.savePathToPng(images.get(i), "/tmp/chartocrpics/", filename);
                }
            } // end for i
        }
        return true;
    }

    public static List<String> ocr(List<BufferedImage> images) {
        return client.ocr(images);
    }

    public static String ocr(BufferedImage image) {
        return client.ocr(Lists.newArrayList(image)).get(0);
    }

}

/**
 * 斜着刻度对应的Path信息
 */
class OCRPathInfo {
    // 预设的类型个数
    public static int NLANG = 5;
    // 经验形扩大图片尺寸的倍数参数集
    public static int [] scales = {4, 2, 2, 2, 3};
    // 用tesseract 解析不同类型OCR问题的语言库参数集
    //public static String [] langs = {"-l chi_sim", " digits ", "-l eng", "-l chi_sim+eng"};
    public static String [] langs = {"-l chi_sim+eng", " -l eng ", "-l chi_sim", "-l eng", "-l chi_sim+eng"};

    public GeneralPath path;     // 对应的路径
    public int windingRule;      // 绘制属性
    public String text;          // 路径绘制对应的内容
    public int groupIndex;       // 所属组编号
    public double x;             // 有效X坐标值
    public int xPos;             // X坐标值的位置  -1 : 左侧　０ : 中间　1 : 右侧
    public int langType;         // 语言类型
    public float w;              // 平均字符的宽度 (用来调整图片大小用，提高识别率)
    public boolean ccw;          // 时针方向, true表示顺时针方向

    OCRPathInfo(GeneralPath ocrPath, int pathWindingRule) {
        path = (GeneralPath)ocrPath.clone();
        windingRule = pathWindingRule;
        text = "";
        groupIndex = -1;
        x = -1.0;
        xPos = 1;
        langType = 0;
        w = 0.0f;
        ccw = true;
    }
    OCRPathInfo(OCRPathInfo other) {
        path = (GeneralPath)other.path.clone();
        windingRule = other.windingRule;
        text = other.text;
        groupIndex = other.groupIndex;
        x = other.x;
        xPos = other.xPos;
        langType = other.langType;
        w = other.w;
        ccw = other.ccw;
    }
}

/**
 * GeneralPath 常用的工具类
 */
class PathUtils {
    private static Logger logger = LogManager.getLogger(PathUtils.class);

    /**
     * 按照给定角度旋转给定点
     * @param x
     * @param y
     * @param angle
     * @return
     */
    private static double[] rotatePoint(double x, double y, double angle) {
        double [] pt = {0.0, 0.0};
        pt[0] = x * Math.cos(angle) - y * Math.sin(angle);
        pt[1] = x * Math.sin(angle) + y * Math.cos(angle);
        return pt;
    }

    /**
     * 按照给定角度旋转给定图片
     * @param img
     * @param angle
     * @return
     * @throws IOException
     */
    public static BufferedImage rotateImage(BufferedImage img, double angle, float[] coefs) throws IOException {
        int width = img.getWidth();
        int height = img.getHeight();
        BufferedImage newImage;
        double[][] newPositions = new double[4][];
        newPositions[0] = rotatePoint(0, 0, angle);
        newPositions[1] = rotatePoint(width, 0, angle);
        newPositions[2] = rotatePoint(0, height, angle);
        newPositions[3] = rotatePoint(width, height, angle);
        double minX = Math.min(Math.min(newPositions[0][0], newPositions[1][0]), Math.min(newPositions[2][0], newPositions[3][0]));
        double maxX = Math.max(Math.max(newPositions[0][0], newPositions[1][0]), Math.max(newPositions[2][0], newPositions[3][0]));
        int newWidth = (int) Math.round(maxX - minX);
        // 对于　tesseract 经验系数取　3.0f 效果比较好
        // 配合其他OCR引擎　修改为1.25f
        int newHeight = (int) (1.25f * coefs[1] * newWidth);
        newImage = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = newImage.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        g.setPaint(Color.white);
        g.fillRect(0, 0, newWidth, newHeight);

        double w = newWidth / 2.0;
        double h = newHeight / 2.0;
        int centerX = (int) Math.round((newWidth - width) / 2.0);
        int centerY = (int) Math.round((newHeight - height) / 2.0);
        g.rotate(angle, w, h);
        g.drawImage(img, centerX, centerY, null);
        g.dispose();
        return newImage;
    }

    /**
     * 将给定Path按照给定比列保存为二值图片格式
     * @param path
     * @param ccw
     * @param coef
     * @return
     */
    public static BufferedImage generalPathToImage(GeneralPath path, boolean ccw, float coef) {
        Rectangle2D cropbBox = path.getBounds2D();
        double angle = Math.atan2(cropbBox.getHeight(), cropbBox.getWidth());
        float picSCALE = coef * 2.0f;
        if (ccw) {
            angle *= -1;
        }

        // 计算转正之后的大小
        AffineTransform drawTransform = new AffineTransform();
        drawTransform.scale(picSCALE, picSCALE);
        drawTransform.rotate(angle);
        drawTransform.translate(-cropbBox.getCenterX(), -cropbBox.getCenterY());

        Rectangle2D imageBox = drawTransform.createTransformedShape(cropbBox).getBounds2D();
        int widthPx = (int) Math.round(imageBox.getWidth());
        int heightPx = 64; // 现在OCR模型默认输入高度是64
        BufferedImage image = null;
        try {
            // 设置图片大小
            image = new BufferedImage(widthPx, heightPx, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            // 设置 Graphics2D 绘制状态
            graphics.setBackground(Color.WHITE);
            graphics.clearRect(0, 0, image.getWidth(), image.getHeight());
            graphics.setPaint(Color.BLACK);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));

            // 让文字居中
            graphics.translate(image.getWidth()/2, image.getHeight()/2);
            graphics.transform(drawTransform);
            graphics.fill(path);

            graphics.dispose();
        } catch (Exception e) {
            logger.warn("fill path failed", e);
        }
        return image;
    }

    /**
     * join two BufferedImage
     * you can add a orientation parameter to control direction
     * you can use a array to join more BufferedImage
     */
    public static BufferedImage joinBufferedImageHorizon(BufferedImage img1, BufferedImage img2) {
        //do some calculate first
        int offset = 5;
        int wid = img1.getWidth() + img2.getWidth() + offset;
        int height = Math.max(img1.getHeight(), img2.getHeight()) + offset;
        //create a new buffer and draw two image into the new image
        BufferedImage newImage = new BufferedImage(wid,height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = newImage.createGraphics();
        Color oldColor = g2.getColor();
        //fill background
        g2.setPaint(Color.WHITE);
        g2.fillRect(0, 0, wid, height);
        //draw image
        g2.setColor(oldColor);
        g2.drawImage(img1, null, 0, 0);
        g2.drawImage(img2, null, img1.getWidth() + offset, 0);
        g2.dispose();
        return newImage;
    }

    public static BufferedImage joinBufferedImageVertical(BufferedImage img1, BufferedImage img2) {
        //do some calculate first
        int offset = 5;
        int wid = Math.max(img1.getWidth(), img2.getWidth()) + offset;
        int height = img1.getHeight() + img2.getHeight() + offset;
        //create a new buffer and draw two image into the new image
        BufferedImage newImage = new BufferedImage(wid,height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = newImage.createGraphics();
        Color oldColor = g2.getColor();
        //fill background
        g2.setPaint(Color.WHITE);
        g2.fillRect(0, 0, wid, height);
        //draw image
        g2.setColor(oldColor);
        g2.drawImage(img1, null, 0, img2.getHeight() + offset);
        g2.drawImage(img2, null, 0, 0);
        g2.dispose();
        return newImage;
    }

    public static float getOCRPathCoef(Chart chart) {
        // 以前保存所有斜着文字的大外包框方法不太实用，故保存每个斜着文字带方向最小外包框 OBB
        int n = chart.ocrs.size();
        float height = 0.0f;
        for (int i = 0; i < n; i++) {
            OCRPathInfo ocr = chart.ocrs.get(i);
            List<Point2D> obb = MinOrientedBoundingBoxComputer.computOBB(ocr.path);
            Point2D p1 = obb.get(0);
            Point2D p2 = obb.get(1);
            Point2D p3 = obb.get(2);
            double f1 = p1.distance(p2);
            double f2 = p2.distance(p3);
            if (f1 < f2) {
                height += f1;
            }
            else {
                height += f2;
            }
        } // endd for i
        height = height / n;
        return 24.0f/height;
    }

    /**
     * 获得给定Path的所有有效点集
     * @param path
     * @param xs
     * @param ys
     */
    public static void getPathPts(GeneralPath path, List<Double> xs, List<Double> ys) {
        getPathPtsInfo(path, xs, ys);
    }

    /**
     * 获得给定Path的有效点数目
     * @param path
     */
    public static int getPathPtsCount(GeneralPath path) {
        return getPathPtsInfo(path, null, null);
    }

    /**
     * 对path中的close点进行重置为起始点 (测试中)
     * @param path
     * @return
     */
    public static GeneralPath resetPathClosePoint(GeneralPath path) {
        PathIterator iter = path.getPathIterator(null);
        double[] coords = new double[12];
        double x = 0, y = 0;
        GeneralPath pathNew = (GeneralPath)path.clone();
        pathNew.reset();
        // 遍历　当前path
        while (!iter.isDone()) {
            int type = iter.currentSegment(coords);
            switch (type) {
                case PathIterator.SEG_MOVETO:
                    pathNew.moveTo(coords[0], coords[1]);
                    x = coords[0];
                    y = coords[1];
                    break;
                case PathIterator.SEG_LINETO:
                    pathNew.lineTo(coords[0], coords[1]);
                    break;
                case PathIterator.SEG_CUBICTO:
                    pathNew.curveTo(coords[0], coords[1], coords[2], coords[3], coords[4], coords[5]);
                    break;
                case PathIterator.SEG_QUADTO:
                    pathNew.quadTo(coords[0], coords[1], coords[2], coords[3]);
                    break;
                case PathIterator.SEG_CLOSE:
                    pathNew.lineTo(x, y);
                default:
                    break;
            } // end switch
            iter.next();
        } // end while
        return (GeneralPath) pathNew.clone();
    }

    public static List<GeneralPath> splitSubPath(GeneralPath path, boolean splitMoveTo) {
        List<GeneralPath> subPaths = new ArrayList<>();
        if (path == null) {
            return subPaths;
        }

        List<Integer> types = new ArrayList<>();
        GeneralPath subPath = (GeneralPath) path.clone();
        subPath.reset();
        PathIterator iter = path.getPathIterator(null);
        double[] coords = new double[12];
        // 遍历　当前path
        while (!iter.isDone()) {
            int type = iter.currentSegment(coords);
            types.add(type);
            switch (type) {
                case PathIterator.SEG_MOVETO:
                    if (splitMoveTo) {
                        if (types.size() > 2) {
                            subPaths.add((GeneralPath) subPath.clone());
                        }
                        types.clear();
                        subPath.reset();
                        types.add(type);
                    }
                    subPath.moveTo(coords[0], coords[1]);
                    break;
                case PathIterator.SEG_LINETO:
                    subPath.lineTo(coords[0], coords[1]);
                    break;
                case PathIterator.SEG_CUBICTO:
                    subPath.curveTo(coords[0], coords[1], coords[2], coords[3], coords[4], coords[5]);
                    break;
                case PathIterator.SEG_QUADTO:
                    subPath.quadTo(coords[0], coords[1], coords[2], coords[3]);
                    break;
                case PathIterator.SEG_CLOSE:
                    subPath.closePath();
                    if (types.size() > 2) {
                        subPaths.add((GeneralPath) subPath.clone());
                    }
                    types.clear();
                    subPath.reset();
                default:
                    break;
            } // end switch
            iter.next();
        } // end while
        if (types.size() >= 2) {
            subPaths.add((GeneralPath) subPath.clone());
        }
        return subPaths;
    }

    public static boolean getPathKeyPtsInfo(
            GeneralPath path, List<Double> xs, List<Double> ys, List<Integer> types) {
        if (path == null || xs == null || ys == null || types == null) {
            return false;
        }

        PathIterator iter = path.getPathIterator(null);
        double[] coords = new double[12];
        double x = 0, y = 0;
        // 遍历　当前path
        while (!iter.isDone()) {
            int type = iter.currentSegment(coords);
            switch (type) {
                case PathIterator.SEG_MOVETO:
                    xs.add(coords[0]);
                    ys.add(coords[1]);
                    x = coords[0];
                    y = coords[1];
                    types.add(type);
                    break;
                case PathIterator.SEG_LINETO:
                    xs.add(coords[0]);
                    ys.add(coords[1]);
                    types.add(type);
                    break;
                case PathIterator.SEG_CUBICTO:
                    xs.add(coords[4]);
                    ys.add(coords[5]);
                    types.add(type);
                    break;
                case PathIterator.SEG_QUADTO:
                    xs.add(coords[2]);
                    ys.add(coords[3]);
                    types.add(type);
                    break;
                case PathIterator.SEG_CLOSE:
                    xs.add(x);
                    ys.add(y);
                    types.add(type);
                default:
                    break;
            } // end switch
            iter.next();
        } // end while
        return true;
    }

    public static boolean getPathAllPtsInfo(
            GeneralPath path, List<Double> xs, List<Double> ys, List<Integer> types) {
        if (path == null || xs == null || ys == null || types == null) {
            return false;
        }

        PathIterator iter = path.getPathIterator(null);
        double[] coords = new double[12];
        double x = 0, y = 0;
        // 遍历　当前path
        while (!iter.isDone()) {
            int type = iter.currentSegment(coords);
            switch (type) {
                case PathIterator.SEG_MOVETO:
                    types.add(type);
                    xs.add(coords[0]);
                    ys.add(coords[1]);
                    x = coords[0];
                    y = coords[1];
                    break;
                case PathIterator.SEG_LINETO:
                    types.add(type);
                    xs.add(coords[0]);
                    ys.add(coords[1]);
                    break;
                // 保存曲线控制点
                case PathIterator.SEG_CUBICTO:
                    types.add(type);
                    types.add(type);
                    types.add(type);
                    xs.add(coords[0]);
                    ys.add(coords[1]);
                    xs.add(coords[2]);
                    ys.add(coords[3]);
                    xs.add(coords[4]);
                    ys.add(coords[5]);
                    break;
                case PathIterator.SEG_QUADTO:
                    types.add(type);
                    types.add(type);
                    xs.add(coords[0]);
                    ys.add(coords[1]);
                    xs.add(coords[2]);
                    ys.add(coords[3]);
                    break;
                case PathIterator.SEG_CLOSE:
                    types.add(type);
                    xs.add(x);
                    ys.add(y);
                default:
                    break;
            } // end switch
            iter.next();
        } // end while
        return true;
    }

    /**
     * 获得给定Path的所有有效点集信息
     * @param path
     * @param xs
     * @param ys
     * @return
     */
    public static int getPathPtsInfo(GeneralPath path, List<Double> xs, List<Double> ys) {
        PathIterator iter = path.getPathIterator(null);
        double[] coords = new double[12];
        boolean storePT = (xs != null && ys != null);
        int num = 0;
        // 遍历　当前path
        while (!iter.isDone()) {
            switch (iter.currentSegment(coords)) {
                case PathIterator.SEG_MOVETO:
                case PathIterator.SEG_LINETO:
                    if (storePT) {
                        xs.add(coords[0]);
                        ys.add(coords[1]);
                    }
                    num++;
                    break;
                case PathIterator.SEG_CUBICTO:
                    if (storePT) {
                        xs.add(coords[0]);
                        ys.add(coords[1]);
                        xs.add(coords[2]);
                        ys.add(coords[3]);
                        xs.add(coords[4]);
                        ys.add(coords[5]);
                    }
                    num += 3;
                    break;
                case PathIterator.SEG_QUADTO:
                    if (storePT) {
                        xs.add(coords[0]);
                        ys.add(coords[1]);
                        xs.add(coords[2]);
                        ys.add(coords[3]);
                    }
                    num += 4;
                    break;
                default:
                    break;
            } // end switch
            iter.next();
        } // end while
        return num;
    }


    /**
     * 获得给定环形对象Path的所有有效点集信息
     * @param path
     * @param xs        Path对象上的点集 这里面包括了m l c所有坐标点x
     * @param ys        Path对象上的点集 这里面包括了m l c所有坐标点y
     * @param curveXs   curve对象上的点集 每两个为一组 为一小段弧形 仅收集了c对象坐标点x1 x2
     * @param curveYs   curve对象上的点集 每两个为一组 为一小段弧形 仅收集了c对象坐标点y1 y2
     * @return
     */
    public static boolean getRingPathPts(
            GeneralPath path,
            List<Double> xs,
            List<Double> ys,
            List<Double> curveXs,
            List<Double> curveYs,
            List<Integer> types) {
        xs.clear();
        ys.clear();
        types.clear();
        PathIterator iter = path.getPathIterator(null);
        double[] coords = new double[12];
        double x = 0.0, y = 0.0;
        int count = 0;
        // 暂存pdf绘制笔尖位置
        double penX = 0.0, penY = 0.0;

        // 遍历　当前path
        while (!iter.isDone()) {
            int pathType = iter.currentSegment(coords);
            switch (pathType) {
                case PathIterator.SEG_MOVETO:
                    if (xs.size() != 0) {
                        return false;
                    }
                    x = coords[0];
                    y = coords[1];
                    xs.add(x);
                    ys.add(y);
                    penX = x;
                    penY = y;
                    types.add(pathType);
                    break;
                case PathIterator.SEG_LINETO:
                    xs.add(coords[0]);
                    ys.add(coords[1]);
                    penX = coords[0];
                    penY = coords[1];
                    types.add(pathType);
                    break;
                case PathIterator.SEG_CUBICTO:
                    xs.add(coords[4]);
                    ys.add(coords[5]);
                    curveXs.add(penX);
                    curveXs.add(coords[4]);
                    curveYs.add(penY);
                    curveYs.add(coords[5]);
                    penX = coords[4];
                    penY = coords[5];
                    types.add(pathType);
                    break;
                case PathIterator.SEG_CLOSE:
                    if (count >= 1) {
                        return false;
                    }
                    count++;
                    xs.add(x);
                    ys.add(y);
                    types.add(pathType);
                    break;
                default:
                    return false;
            } // end switch
            iter.next();
        } // end while
        return true;
    }

    /**
     * 将给定Path按照给定给定路径　文件名　比列 保存为二值图片文件
     * @param image
     * @param dir
     * @param filename
     * @return
     */
    public static boolean savePathToPng(BufferedImage image, String dir, String filename) {
        if (image == null) {
            return false;
        }

        // 如果给定文件夹不存在　则尝试建立　如果不成功　则返回 false
        File myFolderPath = new File(dir);
        try {
            if (!myFolderPath.exists()) {
               myFolderPath.mkdir();
            }
        }
        catch (Exception e) {
            return false;
        }

        // 保存为图片
        return writeImage(image, dir, filename);
    }

    public static boolean writeImage(BufferedImage image, String dir, String filename) {
        // 保存为图片
        boolean result = false;
        FileOutputStream output = null;
        try {
            File saveFile = new File(dir, filename);
            output = new FileOutputStream(saveFile);
            result = ImageIO.write(image, "PNG", output);
            if (!result) {
                logger.warn("savePathToPng failed");
            }
        } catch (Exception e) {
            logger.warn("savePathToPng failed", e);
        } finally {
            IOUtils.closeQuietly(output);
        }
        return result;
    }
}

class MinOrientedBoundingBoxComputer
{
    public static List<Point2D> computOBB(List<Double> xs, List<Double> ys) {
        int n = xs.size();
        double x = 0, y = 0;
        List<Point2D> pts = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            x = xs.get(i);
            y = ys.get(i);
            Point2D.Double pt = new Point2D.Double(x, y);
            pts.add(pt);
        }
        return computeCorners(pts);
    }

    public static List<Point2D> computOBB(GeneralPath path) {
        List<Double> xs = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
        PathUtils.getPathPts(path, xs, ys);
        return computOBB(xs, ys);
    }

    public static List<Point2D> computeCorners(List<Point2D> points) {
        List<Point2D> convexHullPoints = computeConvexHullPoints(points);
        int alignmentPointIndex = computeAlignmentPointIndex(convexHullPoints);
        Rectangle2D r = computeAlignedBounds(convexHullPoints, alignmentPointIndex);

        List<Point2D> alignedCorners = new ArrayList<Point2D>();
        alignedCorners.add(new Point2D.Double(r.getMinX(), r.getMinY()));
        alignedCorners.add(new Point2D.Double(r.getMaxX(), r.getMinY()));
        alignedCorners.add(new Point2D.Double(r.getMaxX(), r.getMaxY()));
        alignedCorners.add(new Point2D.Double(r.getMinX(), r.getMaxY()));

        Point2D center = convexHullPoints.get(alignmentPointIndex);
        double angleRad = computeEdgeAngleRad(convexHullPoints, alignmentPointIndex);

        AffineTransform at = new AffineTransform();
        at.concatenate(AffineTransform.getTranslateInstance(center.getX(), center.getY()));
        at.concatenate(AffineTransform.getRotateInstance(angleRad));

        List<Point2D> corners = transform(alignedCorners, at);
        return corners;
    }

    public static double getObbArea(List<Point2D> points) {
        double lenA = points.get(0).distance(points.get(1));
        double lenB = points.get(1).distance(points.get(2));
        return lenA * lenB;
    }

    public static double getTwoPointAngle(Point2D p1, Point2D p2) {
        double dy = p2.getY() - p1.getY();
        double dx = p2.getY() - p1.getY();
        double angle = Math.atan2(Math.abs(dy), Math.abs(dx));
        return 180.0 * angle / Math.PI;
    }

    public static double getObbAngle(List<Point2D> points) {
        Point2D p1 = points.get(0);
        Point2D p2 = points.get(1);
        Point2D p3 = points.get(2);
        double angle12 = getTwoPointAngle(p1, p2);
        double angle23 = getTwoPointAngle(p2, p3);
        return Math.max(angle12, angle23);
    }

    public static boolean isObbAABB(List<Point2D> points) {
        double dx12 = points.get(0).getX() - points.get(1).getX();
        double dy12 = points.get(0).getY() - points.get(1).getY();
        double dx23 = points.get(1).getX() - points.get(2).getX();
        double dy23 = points.get(1).getY() - points.get(2).getY();
        return (Math.abs(dx12) < 0.1 || Math.abs(dy12) < 0.1) &&
               (Math.abs(dx23) < 0.1 || Math.abs(dy23) < 0.1);
    }

    private static int computeAlignmentPointIndex(List<Point2D> points) {
        double minArea = Double.MAX_VALUE;
        int minAreaIndex = -1;
        for (int i=0; i<points.size(); i++) {
            Rectangle2D r = computeAlignedBounds(points, i);
            double area = r.getWidth() * r.getHeight();
            if (area < minArea) {
                minArea = area;
                minAreaIndex = i;
            }
        }
        return minAreaIndex;
    }

    private static double computeEdgeAngleRad(List<Point2D> points, int index) {
        int i0 = index;
        int i1 = (i0+1)%points.size();
        Point2D p0 = points.get(i0);
        Point2D p1 = points.get(i1);
        double dx = p1.getX() - p0.getX();
        double dy = p1.getY() - p0.getY();
        double angleRad = Math.atan2(dy, dx);
        return angleRad;
    }

    private static Rectangle2D computeAlignedBounds(List<Point2D> points, int index) {
        Point2D p0 = points.get(index);
        double angleRad = computeEdgeAngleRad(points, index);
        AffineTransform at = createTransform(-angleRad, p0);
        List<Point2D> transformedPoints = transform(points, at);
        Rectangle2D bounds = computeBounds(transformedPoints);
        return bounds;
    }

    private static AffineTransform createTransform(double angleRad, Point2D center) {
        AffineTransform at = new AffineTransform();
        at.concatenate(AffineTransform.getRotateInstance(angleRad));
        at.concatenate(AffineTransform.getTranslateInstance(-center.getX(), -center.getY()));
        return at;
    }

    private static List<Point2D> transform(List<Point2D> points, AffineTransform at) {
        List<Point2D> result = new ArrayList<Point2D>();
        for (Point2D p : points) {
            Point2D tp = at.transform(p, null);
            result.add(tp);
        }
        return result;
    }

    private static Rectangle2D computeBounds(List<Point2D> points) {
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (Point2D p : points) {
            double x = p.getX();
            double y = p.getY();
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
        }
        return new Rectangle2D.Double(minX, minY, maxX-minX, maxY-minY);
    }

    static Path2D createPath(List<Point2D> points) {
        Path2D path = new Path2D.Double();
        for (int i=0; i<points.size(); i++) {
            Point2D p = points.get(i);
            double x = p.getX();
            double y = p.getY();
            if (i == 0) {
                path.moveTo(x, y);
            }
            else {
                path.lineTo(x, y);
            }
        }
        path.closePath();
        return path;
    }

    static List<Point2D> computeConvexHullPoints(List<Point2D> points) {
        // NOTE: Converting from Point2D to Point here
        // because the FastConvexHull class expects
        // the points with integer coordinates.
        // This should be generalized to Point2D!
        ArrayList<Point> ps = new ArrayList<Point>();
        for (Point2D p : points) {
            ps.add(new Point((int)p.getX(), (int)p.getY()));
        }
        List<Point> convexHull = FastConvexHull.execute(ps);
        List<Point2D> result = new ArrayList<Point2D>();
        for (Point p : convexHull) {
            double x = p.getX();
            double y = p.getY();
            result.add(new Point2D.Double(x,y));
        }
        return result;
    }
}

class FastConvexHull
{
    public static ArrayList<Point> execute(ArrayList<Point> points)
    {
        ArrayList<Point> xSorted = (ArrayList<Point>) points.clone();
        Collections.sort(xSorted, new XCompare());

        int n = xSorted.size();
        Point[] lUpper = new Point[n];
        lUpper[0] = xSorted.get(0);
        lUpper[1] = xSorted.get(1);

        int lUpperSize = 2;
        for (int i = 2; i < n; i++) {
            lUpper[lUpperSize] = xSorted.get(i);
            lUpperSize++;

            while (lUpperSize > 2 &&
                !rightTurn(lUpper[lUpperSize - 3], lUpper[lUpperSize - 2],
                    lUpper[lUpperSize - 1])) {
                // Remove the middle point of the three last
                lUpper[lUpperSize - 2] = lUpper[lUpperSize - 1];
                lUpperSize--;
            }
        }

        Point[] lLower = new Point[n];
        lLower[0] = xSorted.get(n - 1);
        lLower[1] = xSorted.get(n - 2);

        int lLowerSize = 2;
        for (int i = n - 3; i >= 0; i--) {
            lLower[lLowerSize] = xSorted.get(i);
            lLowerSize++;

            while (lLowerSize > 2 &&
                !rightTurn(lLower[lLowerSize - 3], lLower[lLowerSize - 2],
                    lLower[lLowerSize - 1])) {
                // Remove the middle point of the three last
                lLower[lLowerSize - 2] = lLower[lLowerSize - 1];
                lLowerSize--;
            }
        }

        ArrayList<Point> result = new ArrayList<Point>();

        for (int i = 0; i < lUpperSize; i++) {
            result.add(lUpper[i]);
        }

        for (int i = 1; i < lLowerSize - 1; i++) {
            result.add(lLower[i]);
        }

        return result;
    }

    private static boolean rightTurn(Point a, Point b, Point c) {
        return (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x) > 0;
    }

    private static class XCompare implements Comparator<Point> {
        @Override
        public int compare(Point o1, Point o2) {
            return Integer.compare(o1.x, o2.x);
        }
    }
}
