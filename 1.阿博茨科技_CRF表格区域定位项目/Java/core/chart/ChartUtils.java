package com.abcft.pdfextract.core.chart;

import com.abcft.pdfextract.core.ContentGroupImageDrawer;
import com.abcft.pdfextract.core.model.*;
import com.abcft.pdfextract.core.chart.model.PathInfo;
import com.abcft.pdfextract.core.model.Rectangle;
import com.abcft.pdfextract.core.util.GraphicsUtil;
import com.abcft.pdfextract.core.util.NumberUtil;
import com.abcft.pdfextract.spi.ChartType;
import com.abcft.pdfextract.util.JsonUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.apache.batik.dom.GenericDOMImplementation;
import org.apache.batik.svggen.SVGGraphics2D;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDColorSpace;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.state.PDGraphicsState;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.util.Matrix;
import org.jdom.output.Format;
import org.jdom.output.XMLOutputter;
import org.w3c.dom.DOMImplementation;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.*;
import java.awt.geom.Point2D;
import java.io.*;
import java.util.*;
import java.util.List;
import java.io.FileWriter;
import java.util.stream.Collectors;


/**
 * Created by dhu on 2017/1/13.
 */
public class ChartUtils {
    private static Logger logger = LogManager.getLogger(ChartUtils.class);
    public static final float SCALE = 2;
    // 允许的距离误差
    public static final double DELTA = 0.1;

    public static Point2D getPoint(TextPosition textPosition) {
        return new Point2D.Float(textPosition.getXDirAdj(), textPosition.getYDirAdjLowerLeftRot());
    }

    public static double getLineLength(Line2D line) {
        if (Math.abs(line.getX1() - line.getX2()) < DELTA) {
            return Math.abs(line.getY1() - line.getY2());
        } else if (Math.abs(line.getY1() - line.getY2()) < DELTA) {
            return Math.abs(line.getX1() - line.getX2());
        } else {
            return line.getP1().distance(line.getP2());
        }
    }

    /**
     * 保存为 html
     * @param chart
     * @param file
     * @return
     */
    public static boolean saveChartToHTML(JsonObject chart, File file) {
        // 判断数据有效性
        if (chart == null || file == null) {
            return false;
        }

        // HTML文件模板内容
        String html = "<!DOCTYPE HTML>\n"
                + "<html>\n"
                + "<head>\n"
                + "<meta http-equiv=\"Content-Type\" content=\"text/html; charset=utf-8\">\n"
                + "<title>Highcharts Example</title>\n"
                + "<script type=\"text/javascript\"\n"
                + "src=\"http://cdn.bootcss.com/jquery/2.1.4/jquery.min.js\"></script>\n"
                + "<style type=\"text/css\">\n"
                + "${demo.css}\n"
                + "</style>\n"
                + "</head>\n"
                + "<body>\n"
                + "<script src=\"https://code.highcharts.com/highcharts.js\"></script>\n"
                + "<script src=\"https://code.highcharts.com/modules/exporting.js\"></script>\n"
                + "<div id=\"container\" style=\"min-width: 310px; height: 600px; max-width: 800px; margin: 0 auto\"></div>\n"
                + "<script type=\"text/javascript\">\n"
                + "Highcharts.setOptions({"
                + "    lang: {"
                + "        thousandsSep: ','"
                + "    }"
                + "});"
                + "Highcharts.chart('container',\n"
                + JsonUtil.toString(chart, true)
                + ");\n"
                + "</script>\n"
                + "</body>\n"
                + "</html>";
        // 尝试输出为　html 文件
        try {
            FileWriter resultFile = new FileWriter(file);
            PrintWriter myFile = new PrintWriter(resultFile);
            myFile.println(html);
            resultFile.close();
        }
        catch (Exception e) {
            return false;
        }
        return true;
    }

    /**
     * 获得给定Image的子区域
     * @param pageImage
     * @param box
     * @return
     */
    public static BufferedImage getCropImage(BufferedImage pageImage, Rectangle2D box) {
        if (pageImage == null || box == null) {
            return null;
        }
        double width = pageImage.getWidth(), height = pageImage.getHeight();
        // 判断是否无效
        if (box.getMinX() > width - 2 || box.getMinY() > height - 2) {
            return null;
        }
        double xmin = box.getMinX() < 0 ? 0 : box.getMinX();
        double ymin = box.getMinY() < 0 ? 0 : box.getMinY();
        double xmax = box.getMaxX() > width - 2 ? width - 2 : box.getMaxX();
        double ymax = box.getMaxY() > height - 2 ? height - 2 : box.getMaxY();
        return pageImage.getSubimage(
                (int) xmin, (int) ymin, (int) (xmax - xmin), (int) (ymax - ymin));
    }

    /**
     * 将给定矩形对象进行缩放
     * @param box
     * @param scale
     */
    public static void scaleRectange(Rectangle2D box, double scale) {
        double x = scale * box.getMinX();
        double y = scale * box.getMinY();
        double w = scale * box.getWidth();
        double h = scale * box.getHeight();
        box.setRect(x, y, w, h);
    }

    /**
     * 将给定Chart集根据其区域从Page内部裁剪图片
     * @param chartObjs
     * @param pageImage
     * @param scale
     */
    public static void setChartsAreaCropImage(List<Chart> chartObjs, BufferedImage pageImage, double scale) {
        if (pageImage == null || chartObjs == null || chartObjs.isEmpty()) {
            return;
        }

        for (Chart chart : chartObjs) {
            Rectangle2D box = chart.getArea();
            scaleRectange(box, scale);
            BufferedImage cropImage = ChartUtils.getCropImage(pageImage, box);
            if (cropImage != null) {
                chart.cropImage = ChartUtils.deepCopyBufferedImage(cropImage);
            }
        } // end for chart
    }

    public static boolean saveChartToFile(Chart chart, File file) {
        try (FileOutputStream outputStream = new FileOutputStream(file)) {
            return saveChartToStream(chart, outputStream, chart.getFormatName());
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean saveChartToFile(Chart chart, File file, String formatName) {
        try (FileOutputStream outputStream = new FileOutputStream(file)) {
            return saveChartToStream(chart, outputStream, formatName);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 拷贝BufferedImage
     * @param source
     * @return
     */
    public static BufferedImage  deepCopyBufferedImage(BufferedImage source){
        BufferedImage b = new BufferedImage(source.getWidth(), source.getHeight(), source.getType());
        Graphics g = b.getGraphics();
        g.drawImage(source, 0, 0, null);
        g.dispose();
        return b;
    }

    public static BufferedImage saveChartToImage(Chart chart) {
        BufferedImage chartImage = null;
        if (chart.type == ChartType.BITMAP_CHART) {
            try {
                if (chart.image != null && chart.cropImage == null) {
                    chartImage = chart.image.getImage();
                } else if (chart.cropImage != null) {
                    // 用从位图中检测Chart区域模型检测出的对象
                    // 但是没有能解析出其中的矢量内容　将区域剪切保存为位图对象
                    chartImage = chart.cropImage;
                }

            } catch (IOException e) {
                chartImage = null;
            }
        }
        if (chartImage != null) {
            return chartImage;
        }

        Rectangle2D cropbBox = chart.getDrawArea();
        double widthPt = cropbBox.getWidth();
        double heightPt = cropbBox.getHeight();

        // 适当调整比例尺寸 使图片长宽都不超过 2048
        double adaptScale = Math.min(2048 / widthPt, 2048 / heightPt);
        adaptScale = Math.min(adaptScale, SCALE);
        int widthPx = (int) Math.round(widthPt * adaptScale);
        int heightPx = (int) Math.round(heightPt * adaptScale);

        try {
            int rotationAngle = chart.page.getRotation();
            if (rotationAngle == 90 || rotationAngle == 270)
            {
                chartImage = GraphicsUtil.getBufferedImageRGB(heightPx, widthPx);
            }
            else
            {
                chartImage = GraphicsUtil.getBufferedImageRGB(widthPx, heightPx);
            }
            if (chartImage == null) {
                return null;
            }
            // use a transparent background if the imageType supports alpha
            Graphics2D graphics = chartImage.createGraphics();
            graphics.setBackground(Color.WHITE);
            graphics.clearRect(0, 0, chartImage.getWidth(), chartImage.getHeight());
            renderChartToGraphics(chart, graphics, (float) adaptScale);

            graphics.dispose();
        } catch (Exception e) {
            logger.warn("render chart failed", e);
            chartImage = null;
        }
        return chartImage;
    }

    /**
     * 输出给定Chart内部Chunk的包围框信息
     * @param chart
     * @param file
     * @return
     */
    public static boolean saveChartChunksBox(Chart chart, File file)  {
        // 如果为位图对象或是从Page裁剪某个区域的位图对象 则跳过
        if (chart.type == ChartType.BITMAP_CHART) {
            return false;
        }
        Rectangle2D cropbBox = chart.getArea();
        double widthPt = cropbBox.getWidth();
        double heightPt = cropbBox.getHeight();
        int widthPx = (int) Math.round(widthPt * SCALE);
        int heightPx = (int) Math.round(heightPt * SCALE);
        return saveChartChunksBox(
                chart, file,
               widthPx / cropbBox.getWidth(), heightPx / cropbBox.getHeight());
    }

    private static int sCurrentNumber = -1;
    public static boolean saveChartToDataset(Chart chart, File dir, String pdfPath) {
        if (chart.type == ChartType.BITMAP_CHART || chart.isPPT) {
            return false;
        }
        File jpgDir = new File(dir, "JPEGImages");
        File pngDir = new File(dir, "PNGImages");  // 配合郭萌某些学习模型样本需要　添加PNG输出
        File annotationDir = new File(dir, "Annotations");
        File highchartDir = new File(dir, "HighCharts");
        if (!jpgDir.isDirectory()) {
            jpgDir.mkdirs();
        }
        if (!pngDir.isDirectory()) {
            pngDir.mkdirs();
        }
        if (!annotationDir.isDirectory()) {
            annotationDir.mkdirs();
        }
        if (!highchartDir.isDirectory()) {
            highchartDir.mkdirs();
        }
        if (sCurrentNumber == -1) {
            sCurrentNumber = 0;
        }
        int num = sCurrentNumber + 1;
        File jpgFile = new File(jpgDir, num + ".jpg");
        File pngFile = new File(pngDir, num + ".png");
        File annotationFile = new File(annotationDir, num + ".xml");
        if (!saveChartToFile(chart, jpgFile, "jpg")) {
            return false;
        }
        if (!saveChartToFile(chart, pngFile, "png")) {
            return false;
        }
        if (!saveChartAnnotation(chart, annotationFile, pdfPath)) {
            return false;
        }

        // 保存Chart内容为highchart格式的json文件　给位图质量评估提供样本数据
        try {
            File highchartFile = new File(highchartDir, num + ".json");
            FileWriter file = new FileWriter(highchartFile);
            file.write(JsonUtil.toString(chart.data, true));
            file.flush();
            file.close();
        }
		catch (IOException e) {
            return false;
        }
        sCurrentNumber++;
        return true;
    }

    public static AffineTransform getChartTransform(Chart chart) {
        // 由于BufferedImage保存有宽高2048限制，故计算图片实际的缩放比例
        //Rectangle2D cropBox = chart.getArea().withCenterExpand(10);
        Rectangle2D cropBox = chart.getDrawArea();
        double widthPt = cropBox.getWidth();
        double heightPt = cropBox.getHeight();
        double adaptScale = Math.min(2048 / widthPt, 2048 / heightPt);
        adaptScale = Math.min(adaptScale, SCALE);
        cropBox = chart.getArea().withCenterExpand(10);
        double chartX = cropBox.getX();
        double chartY = cropBox.getY();
        AffineTransform chartTransform = new AffineTransform();
        chartTransform.scale(adaptScale, adaptScale);
//        chartTransform.translate(0, heightPt);
//        chartTransform.scale(1, -1);
        chartTransform.translate(-chartX, -chartY);
        return chartTransform;
    }

    public static boolean saveChartAnnotation(Chart chart, File file, String pdfPath) {
        // 由于BufferedImage保存有宽高2048限制，故计算图片实际的缩放比例
        Rectangle2D cropbBox = chart.getDrawArea();
        double widthPt = cropbBox.getWidth();
        double heightPt = cropbBox.getHeight();
        double adaptScale = Math.min(2048 / widthPt, 2048 / heightPt);
        adaptScale = Math.min(adaptScale, SCALE);
        int widthPx = (int) Math.round(widthPt * adaptScale);
        int heightPx = (int) Math.round(heightPt * adaptScale);
        AffineTransform chartTransform = getChartTransform(chart);

        Map<String, String> attributes = new HashMap<>();
        attributes.put("folder", "chart");
        attributes.put("pdf", pdfPath);
        attributes.put("page", String.valueOf(chart.pageIndex));
        attributes.put("filename", file.getName().replace(".xml", ".jpg"));
        attributes.put("width", String.valueOf(widthPx));
        attributes.put("height", String.valueOf(heightPx));

        List<org.jdom.Element> elements = new ArrayList<>();

        saveAxis(chart, elements, "haxis", chart.hAxis, chartTransform, widthPx, heightPx);
        saveAxis(chart, elements, "lvaxis", chart.lvAxis, chartTransform, widthPx, heightPx);
        saveAxis(chart, elements, "rvaxis", chart.rvAxis, chartTransform, widthPx, heightPx);

        saveTextChunks("text", chart.chunksPicMark, elements, chart, chartTransform, widthPx, heightPx);
        saveOcrPaths(chart, elements, chartTransform, widthPx, heightPx);
        saveBarPaths(chart, elements, chartTransform, widthPx, heightPx);

        return saveAnnotation(elements, file, attributes);
    }

    public static boolean saveAnnotation(List<org.jdom.Element> elements, File file, Map<String, String> attributes) {
        org.jdom.Element annotation = new org.jdom.Element("annotation");
        org.jdom.Document doc = new org.jdom.Document(annotation);
        doc.setRootElement(annotation);

        for (Map.Entry<String, String> entry : attributes.entrySet()) {
            annotation.addContent(new org.jdom.Element(entry.getKey()).setText(entry.getValue()));
        }
        annotation.addContent(new org.jdom.Element("size")
                .addContent(new org.jdom.Element("width").setText(attributes.get("width")))
                .addContent(new org.jdom.Element("height").setText(attributes.get("height")))
                .addContent(new org.jdom.Element("depth").setText("3"))
        );
        annotation.addContent(new org.jdom.Element("segmented").setText("0"));

        for (org.jdom.Element element : elements) {
            annotation.addContent(element);
        }

        try {
            XMLOutputter xmlOutput = new XMLOutputter();
            xmlOutput.setFormat(Format.getPrettyFormat());
            xmlOutput.output(doc, new FileWriter(file));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public static JsonArray getTextInfoInChart(Chart chart, List<TextChunk> textChunks) {
        AffineTransform chartTransform = getChartTransform(chart);
        JsonArray array = new JsonArray();
        textChunks.forEach(textChunk ->  {
            Rectangle2D area = chartTransform.createTransformedShape(textChunk.getBounds()).getBounds2D();
            JsonObject object = new JsonObject();
            object.addProperty("type", "text");
            object.addProperty("text", textChunk.getText());
            JsonArray bbox = new JsonArray();
            JsonArray xy = new JsonArray();
            xy.add((int)area.getCenterX());
            xy.add((int)area.getCenterY());
            bbox.add(xy);
            JsonArray wh = new JsonArray();
            wh.add((int)area.getWidth());
            wh.add((int)area.getHeight());
            bbox.add(wh);
            bbox.add(0);
            object.add("bbox", bbox);
            array.add(object);
        });
        return array;
    }

    public static Point2D distanceOf(Rectangle2D rect1, Rectangle2D rect2) {
        double x = 0;
        double y = 0;
        // 矩形相交, 距离为0
        if (rect1.intersects(rect2)) {
            return new Point2D.Double(x, y);
        }
        if (rect2.getMinX() > rect1.getMaxX()) {
            // rect2在rect1的右边, 距离为正
            x = rect2.getMinX() - rect1.getMaxX();
        } else {
            //rect2在rect1的左边, 距离为负
            x = rect2.getMaxX() - rect1.getMinX();
        }
        if (rect2.getMinY() > rect1.getMaxY()) {
            // rect2在rect1的上边, 距离为正
            y = rect2.getMinY() - rect1.getMaxY();
        } else {
            //rect2在rect1的下边, 距离为负
            y = rect2.getMaxY() - rect1.getMinY();
        }
        return new Point2D.Double(x, y);
    }

    private static void saveAxis(Chart chart, List<org.jdom.Element> elements, String type, Line2D line,
                                 AffineTransform chartTransform, int widthPx, int heightPx) {
        if (line == null) {
            return;
        }
        Rectangle2D area = line.getBounds2D();
        area = chartTransform.createTransformedShape(area).getBounds2D();
        Rectangle chartBounds = new Rectangle(0, 0, widthPx, heightPx);
        area = area.createIntersection(chartBounds);
        elements.add(buildTextElement(area, type, ""));
    }

    private static void saveOcrPaths(Chart chart, List<org.jdom.Element> elements,
                                     AffineTransform chartTransform, int widthPx, int heightPx) {
        if (chart.ocrs == null || chart.ocrs.size() < 2) {
            return;
        }
        for (int i = 0; i < chart.ocrs.size(); i++) {
            OCRPathInfo ocr = chart.ocrs.get(i);
            Rectangle2D area = ocr.path.getBounds2D();
            if (!chart.getArea().intersects(area)) {
                continue;
            }
            // 以前保存所有斜着文字的大外包框方法不太实用，故保存每个斜着文字带方向最小外包框 OBB
            List<Point2D> obb = MinOrientedBoundingBoxComputer.computOBB(ocr.path);
            List<Integer> xs = new ArrayList<>();
            List<Integer> ys = new ArrayList<>();
            for (Point2D pt : obb) {
                Rectangle2D ptBox = new Rectangle2D.Double(pt.getX(), pt.getY(), 0, 0);
                ptBox = chartTransform.createTransformedShape(ptBox).getBounds2D();
                xs.add((int)ptBox.getX());
                ys.add((int)ptBox.getY());
            }
            // 如果旋转文字是从左下到右上方向
            if (ocr.xPos == 1) {
                elements.add(buildOBBElement(xs, ys, "obb_ld_ru", ""));
            }
            // 如果旋转文字是从左上到右下方向
            else if (ocr.xPos == -1) {
                elements.add(buildOBBElement(xs, ys, "obb_lu_rd", ""));
            }
        } // end for i
    }

    private static void saveTextChunks(String type, Collection<TextChunk> textChunks, List<org.jdom.Element> elements,
                                       Chart chart, AffineTransform chartTransform, int widthPx, int heightPx) {
        if (textChunks == null || textChunks.isEmpty()) {
            return;
        }
        Rectangle chartBounds = new Rectangle(0, 0, widthPx, heightPx);
        for (TextChunk textChunk : textChunks) {
            Rectangle2D area = textChunk.getBounds();
            if (!chart.getArea().intersects(area) || StringUtils.isEmpty(textChunk.getText())) {
                continue;
            }
            area = chartTransform.createTransformedShape(area).getBounds2D();
            area = area.createIntersection(chartBounds);
            // 根据TextChunk文字方向　保存时设置水平和垂直类型　给文字对象检测模型创立合适的样本用
            // 以后能直接检测文字的大致方向　　改进位图Chart的解析
            if ( textChunk.getDirection() == TextDirection.VERTICAL_UP ||
                    textChunk.getDirection() ==  TextDirection.VERTICAL_DOWN) {
                String type_info = "text_vertical";
                elements.add(buildTextElement(area, type_info, textChunk.getText()));
            }
            else {
                String type_info = "text_horizonal";
                elements.add(buildTextElement(area, type_info, textChunk.getText()));
            }
        }
    }

    private static void saveBarPaths(Chart chart, List<org.jdom.Element> elements,
                                     AffineTransform chartTransform, int widthPx, int heightPx) {
        String type = "";
        List<Double> xs = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
        double xmin = 0.0, xmax = 0.0, ymin = 0.0, ymax = 0.0;
        Rectangle chartBounds = new Rectangle(0, 0, widthPx, heightPx);
        String valueInfo = "";
        // 遍历 PathInfo 对内部包含矩形的对象进行处理
        for (ChartPathInfo pathInfo : chart.pathInfos) {
            // 设置水平或垂直柱状图类型
            if (pathInfo.type == PathInfo.PathType.COLUMNAR) {
                type = "bar";
            }
            else if (pathInfo.type == PathInfo.PathType.BAR) {
                type = "columnar";
            }
            else {
                continue;
            }

            boolean isVertical = pathInfo.type == PathInfo.PathType.BAR; // 判断是为垂直方向或水平方向的柱状图
            Line2D axis = isVertical ? chart.hAxis : (chart.lvAxis != null ? chart.lvAxis : chart.rvAxis);
            double [] range = ChartContentScaleParser.getValidPathRange(isVertical, chart.getArea(), axis);

            // 遍历当前 PathInfo中Path的所有矩形　
            ChartPathInfosParser.getPathColumnBox(pathInfo.path, xs, ys);
            int n = xs.size() / 2;
            int ith = -1;
            for (int i = 0; i < n; i++) {
                xmin = xs.get(2 * i);
                xmax = xs.get(2 * i + 1);
                ymin = ys.get(2 * i);
                ymax = ys.get(2 * i + 1);
                Rectangle2D area = new Rectangle2D.Double(xmin, ymin, xmax - xmin, ymax - ymin);
                if (!chart.getArea().intersects(area)) {
                    continue;
                }
                if (isVertical && (xmax < range[0] || xmin > range[1])) {
                    continue;
                }
                else if (!isVertical && (ymax < range[0] || ymin > range[1])) {
                    continue;
                }

                area = chartTransform.createTransformedShape(area).getBounds2D();
                area = area.createIntersection(chartBounds);
                ith++;
                if (ith >= pathInfo.valuesX.size()) {
                    return;
                }
                if (pathInfo.type == PathInfo.PathType.COLUMNAR) {
                    valueInfo = pathInfo.valuesX.get(ith);
                }
                else {
                    valueInfo = pathInfo.valuesY.get(ith);
                }
                elements.add(buildTextElement(area, type, valueInfo));
                //annotation.addContent(buildTextElement(area, type, "", widthPx-1, heightPx-1));
            }
        } // end for pathInfo
    }

    // 建立斜着文字的带方向的最小外包矩形框OBB信息
    private static org.jdom.Element buildOBBElement(
            List<Integer> xs, List<Integer> ys, String type, String text) {
        org.jdom.Element object = new org.jdom.Element("object");
        object.addContent(new org.jdom.Element("name").setText(type));
        object.addContent(new org.jdom.Element("text").setText(text));
        object.addContent(new org.jdom.Element("pose").setText("Unspecified"));
        object.addContent(new org.jdom.Element("truncated").setText("0"));
        object.addContent(new org.jdom.Element("difficult").setText("0"));
        // 依次输出OBB的四个顶点
        object.addContent(new org.jdom.Element("obb")
                .addContent(new org.jdom.Element("x1").setText(String.valueOf(xs.get(0))))
                .addContent(new org.jdom.Element("y1").setText(String.valueOf(ys.get(0))))
                .addContent(new org.jdom.Element("x2").setText(String.valueOf(xs.get(1))))
                .addContent(new org.jdom.Element("y2").setText(String.valueOf(ys.get(1))))
                .addContent(new org.jdom.Element("x3").setText(String.valueOf(xs.get(2))))
                .addContent(new org.jdom.Element("y3").setText(String.valueOf(ys.get(2))))
                .addContent(new org.jdom.Element("x4").setText(String.valueOf(xs.get(3))))
                .addContent(new org.jdom.Element("y4").setText(String.valueOf(ys.get(3))))
        );
        return object;
    }

    private static org.jdom.Element buildTextElement(
            Rectangle2D area, String type, String text, Color color) {
        org.jdom.Element object = new org.jdom.Element("object");
        object.addContent(new org.jdom.Element("name").setText(type));
        object.addContent(new org.jdom.Element("text").setText(text));
        object.addContent(new org.jdom.Element("pose").setText("Unspecified"));
        object.addContent(new org.jdom.Element("truncated").setText("0"));
        object.addContent(new org.jdom.Element("difficult").setText("0"));
        object.addContent(new org.jdom.Element("bndbox")
                .addContent(new org.jdom.Element("xmin").setText(String.valueOf((int) area.getMinX())))
                .addContent(new org.jdom.Element("ymin").setText(String.valueOf((int) area.getMinY())))
                .addContent(new org.jdom.Element("xmax").setText(String.valueOf((int) area.getMaxX())))
                .addContent(new org.jdom.Element("ymax").setText(String.valueOf((int) area.getMaxY())))
        );
        if (type.equalsIgnoreCase("legend") && color != null) {
            object.addContent(new org.jdom.Element("color")
                    .addContent(new org.jdom.Element("red").setText(String.valueOf(color.getRed())))
                    .addContent(new org.jdom.Element("green").setText(String.valueOf(color.getGreen())))
                    .addContent(new org.jdom.Element("blue").setText(String.valueOf(color.getBlue())))
            );
        }
        return object;
    }

    public static org.jdom.Element buildTextElement(Rectangle2D area, String type, String text) {
        return buildTextElement(area, type, text, null);
    }

    public static boolean saveChartToStream(Chart chart, OutputStream output) {
        return saveChartToStream(chart, output, chart.getFormatName());
    }

    public static boolean hasMasks(PDImageXObject pdImage) {
        try {
            return pdImage.getMask() != null || pdImage.getSoftMask() != null;
        } catch (IOException e) {
            return false;
        }
    }

    private static final List<String> JPEG = Arrays.asList(
            COSName.DCT_DECODE.getName(),
            COSName.DCT_DECODE_ABBREVIATION.getName());
    private static final List<String> JPX = Arrays.asList(COSName.JPX_DECODE.getName());

    private static boolean savePDImageToStream(PDImage pdImage, OutputStream output, String formatName) {
        if (!(pdImage instanceof PDImageXObject)) {
            return false;
        }
        String suffix = pdImage.getSuffix();
        if (suffix == null || "jb2".equals(suffix))
        {
            suffix = "png";
        }
        else if ("jpx".equals(suffix))
        {
            // use jp2 suffix for file because jpx not known by windows
            suffix = "jp2";
        }
        PDImageXObject xObject = (PDImageXObject) pdImage;
        if (("jpg".equals(suffix) || "jp2".equals(suffix))
                && !hasMasks(xObject)) {
            // 如果是jpg的格式, PDImageXObject的stream里面保存的是jpg原始的数据, 我们只需要复制流保存就行了
            InputStream data = null;
            try {
                List<String> stopFilters = "jpg".equals(suffix) ? JPEG : JPX;
                data = pdImage.createInputStream(stopFilters);
                IOUtils.copy(data, output);
                output.flush();
                return true;
            } catch (Exception ignored) {
            } finally {
                IOUtils.closeQuietly(data);
                IOUtils.closeQuietly(output);
            }
            return false;
        }
        try {
            // 如果是其他的格式, PDImageXObject的stream里面保存的是图片decode之后的像素信息, 我们需要把数据转成BufferedImage然后保存
            BufferedImage image;

            if (!hasMasks(xObject)) {
                // 不需要apply mask, 直接返回原始的图
                image = xObject.getOpaqueImage();
            } else {
                // pdf有3种mask 分别是ImageMask Mask和SMask（softmask）
                // apply mask和原图合并 避免出现坏图情况 （透明或黑色背景）
                // TODO:有可能OutOfMemory要注意
                image = xObject.getImage();
            }
            return ImageIO.write(image, formatName, output);

        } catch (Exception ignored) {
        }
        return false;
    }

    private static boolean saveOfficeChartToStream(com.abcft.pdfextract.core.office.Chart officeChart, OutputStream output, String formatName) {
        if (officeChart.getType() != ChartType.BITMAP_CHART) {
            // 目前还不支持保存矢量chart
            return false;
        }
        try {
            officeChart.getImage().write(output);
            return true;
        } catch (IOException e) {
            logger.warn("saveOfficeChartToStream failed", e);
        } finally {
            org.apache.pdfbox.io.IOUtils.closeQuietly(output);
        }
        return false;
    }

    public static boolean saveChartToStream(Chart chart, OutputStream output, String formatName) {
        boolean result = false;
        BufferedImage chartImage = null;
        // 处理位图
        //if (chart.type == ChartType.BITMAP_CHART && chart.image != null) {
        //    return savePDImageToStream(chart.image, output, formatName);
        //} else
        if (chart.type == ChartType.BITMAP_CHART && StringUtils.isNoneBlank(chart.imageUrl)) {
            return downloadImageToStream(chart.imageUrl, output);
        } else if (chart.getOfficeChart() != null) {
            return saveOfficeChartToStream(chart.getOfficeChart(), output, formatName);
        } else {
            chartImage = saveChartToImage(chart);
        }
        if (chartImage == null) {
            IOUtils.closeQuietly(output);
            return false;
        }
        try {
            result = ImageIO.write(chartImage, formatName, output);
            if (!result) {
                logger.warn("saveChartToStream failed");
            }
        } catch (Exception e) {
            logger.warn("saveChartToStream failed", e);
        } finally {
            IOUtils.closeQuietly(output);
        }
        return result;
    }

    private static OkHttpClient httpClient = new OkHttpClient();
    private static boolean downloadImageToStream(String imageUrl, OutputStream output) {
        Request request = new Request.Builder().url(imageUrl).build();
        try {
            Response response = httpClient.newCall(request).execute();
            ResponseBody body = response.body();
            if (body != null) {
                IOUtils.copy(body.byteStream(), output);
                return true;
            } else {
                return false;
            }
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean saveChartToSvg(Chart chart, OutputStream output){
        if (chart.type == ChartType.BITMAP_CHART) {
            return false;
        }
        else if (chart.isPPT) {
            return false;
        }
        DOMImplementation domImpl = GenericDOMImplementation.getDOMImplementation();

        // Create an instance of org.w3c.dom.Document.
        String svgNS = "http://www.w3.org/2000/svg";
        Document document = domImpl.createDocument(svgNS, "svg", null);

        // Create an instance of the SVG Generator.
        SVGGraphics2D svgGenerator = new SVGGraphics2D(document);
        Dimension dimension = new Dimension();
        Rectangle2D rect = chart.getDrawArea();
        dimension.setSize(rect.getWidth(), rect.getHeight());
        svgGenerator.setSVGCanvasSize(dimension);

        Writer out = null;
        boolean result = false;
        try {
            svgGenerator.setBackground(Color.WHITE);
            svgGenerator.clearRect(0, 0, (int)dimension.getWidth(), (int)dimension.getHeight());
            renderChartToGraphics(chart, svgGenerator, 1);

            // Finally, stream out SVG to the standard output using UTF-8 encoding.
            out = new OutputStreamWriter(output, "utf-8");
            Element svgRoot = svgGenerator.getRoot();
            //svgRoot.setAttributeNS(null, "viewBox", String.format("0 0 %.0f %.0f", dimension.getWidth()/2, dimension.getHeight()/2));
            svgGenerator.stream(svgRoot, out, false, false);
            out.flush();
            result = true;
        } catch (Exception e) {
            logger.warn("saveChartToSvg failed", e);
        } finally {
            IOUtils.closeQuietly(out);
            svgGenerator.dispose();
        }
        return result;
    }


    public static void renderChartToGraphics(Chart chart, Graphics2D graphics, float scale) throws IOException {
        PDPage page = chart.page;
        PDRectangle cropBox = page.getCropBox();
        try {
            if (!chart.isPPT) {
                ContentGroupImageDrawer drawer = new ContentGroupImageDrawer(chart.page, chart);
                drawer.renderToGraphics(graphics, scale);
            } else {
                Rectangle2D rect = chart.getDrawArea();
                page.setCropBox(new PDRectangle((float)rect.getX(), (float)rect.getY(),
                        (float)rect.getWidth(), (float)rect.getHeight()));
                PDFRenderer pageRenderer = new PDFRenderer(null);
                pageRenderer.renderPageToGraphics(chart.page, graphics, scale);
            }
        } finally {
            page.setCropBox(cropBox);
        }
    }

    /**
     * 判断两个double是否相等, 考虑误差
     * @param v1
     * @param v2
     * @return
     */
    public static boolean equals(double v1, double v2) {
        return Math.abs(v1 - v2) < DELTA;
    }

    /**
     * 比较两条直线的长度, 相等返回0, line1 &lt; line2返回-1, 其他返回1
     * @param line1
     * @param line2
     * @return
     */
    public static int compareLineLength(Line2D line1, Line2D line2) {
        double diff = getLineLength(line1) - getLineLength(line2);
        if (Math.abs(diff) < DELTA) {
            return 0;
        }
        return diff > 0 ? 1 : -1;
    }


    public static String trim(String text) {
        if (text == null) {
            return null;
        }
        return text.replaceAll("^[\\s　]*", "").replaceAll("[\\s　]*$", "");
    }

    public static List<TextChunk> getChartTextChunks(Chart chart, ContentGroup content) {
        List<TextChunk> textChunks = new ArrayList<>();
        Rectangle2D box = chart.getArea();
        if (chart.parserDetectArea) {
            List<TextChunk> chunks = content.getAllTextChunks();
            for (TextChunk chunk : chunks) {
                //if (GraphicsUtil.contains(chart.getArea(), chunk)) {
                if (box.intersects(chunk)) {
                    textChunks.add(chunk);
                }
            }
        }
        else {
            textChunks = content.getAllTextChunks();
        }
        return textChunks;
    }

    public static List<TextChunk> mergeTextChunk(Chart chart, ContentGroup content) {
        // 判断数据有效性
        if (chart == null || chart.type == ChartType.BITMAP_CHART || content == null) {
            return new ArrayList<>();
        }
        List<TextChunk> textChunks = getChartTextChunks(chart, content);
//        textChunks.removeIf(textChunk -> textChunk.getText().trim().isEmpty());
        List<TextChunk> merged = new ChartTextChunkMerger(1.2f).merge(textChunks , chart.getType());
        // 拆分包含连续空格的TextChunk
        merged = merged.stream()
                .flatMap(textChunk -> ChartTextMerger.squeeze(textChunk, ' ', 3).stream())
                .collect(Collectors.toList());

        // 尝试细分空间相邻的同规律的数字
        merged = splitChunksBaseNumberPattern(merged);
        ChunkUtil.reverserRTLChunks(merged);
        return merged;
    }

    public static List<TextChunk> splitChunksBaseNumberPattern(List<TextChunk> chunks) {
        for (int i = 0; i < chunks.size(); i++) {
            TextChunk chunk = chunks.get(i);
            if (chunk.getDirection() != TextDirection.LTR) {
                continue;
            }
            List<TextChunk> chunksNew = splitNumberPattern(chunk);
            if (chunksNew.size() >= 2) {
                chunks.set(i, chunksNew.get(0));
                for (int j = 1; j < chunksNew.size(); j++) {
                    chunks.add(i + j, chunksNew.get(j));
                }
            }
        } // end for i
        return chunks;
    }

    /**
     * 根据相邻同类型数字类型的等分标示符　等间隔风格TextChunk
     * @param chunk
     * @return
     */
    public static List<TextChunk> splitNumberPattern(TextChunk chunk) {
        List<TextChunk> chunksNew = new ArrayList<>();
        String text = chunk.getText();
        // 判断是否为空白符
        text = trim(text);
        if (StringUtils.isEmpty(text)) {
            return chunksNew;
        }
        // 判断长度是否过小
        int n = text.length();
        if (n <= 4) {
            return chunksNew;
        }
        // 如果不是数字类型　则直接返回
        if (!ChartTitleLegendScaleParser.isStringNumber(text)) {
            return chunksNew;
        }
        // 抽出TextChunk中的有效TextElement, 过滤掉空白符
        List<TextElement> elements = chunk.getElements();
        List<TextElement> elementsNew = new ArrayList<>();
        for (TextElement ele : elements) {
            String textEle = ele.getText();
            if (!StringUtils.isEmpty(textEle)) {
                elementsNew.add(ele);
            }
        }
        // 判断数目是否相同
        if (n != elementsNew.size()) {
            return chunksNew;
        }

        int nIndex = 0, matchCase = 0, step = 0;
        // 遍历主要的分割标示
        List<String> numberInfos =new ArrayList<>(Arrays.asList("%", "-", "$", ".", "("));
        for (String number : numberInfos) {
            // 找出匹配的信息
            List<Integer> indexes = ChartTitleLegendScaleParser.matchStringByIndexOf(text, number);
            nIndex = indexes.size();
            // 如果匹配数目过少
            if (nIndex <= 1) {
                continue;
            }
            // 如果不是完全匹配
            if (n % nIndex != 0) {
                continue;
            }
            // 判断是否等间隔
            matchCase = n / nIndex;
            boolean sameStep = true;
            for (int i = 0; i < nIndex - 1; i++) {
                step = indexes.get(i + 1) - indexes.get(i);
                if (matchCase != step) {
                    sameStep = false;
                    break;
                }
            }
            if (!sameStep) {
                continue;
            }
            // 等间距分割
            for (int i = 0; i < nIndex; i++) {
                TextChunk chunkNew = new TextChunk(elementsNew.subList(i * matchCase, (i + 1) * matchCase));
                chunksNew.add(chunkNew);
            }
            break;
        } // end for number
        return chunksNew;
    }

    /**
     * 将给定TextElement集分组　按照给定水平方向或垂直方向 基于现有的TextElement组集信息
     * 即PDF文件中现成的组 一般不相邻或不同字体为不同组 这些组是固有最小组合单元　不能被拆分为不同组
     * @param textElements 给定TextElement集
     * @return 分组TextChunk集
     */
    public static List<TextChunk> groupTextElements(List<TextElement> textElements) {
        List<TextChunk> textChunks = new ArrayList<>();
        NumberUtil.sortByReadingOrder(textElements);
        TextChunk lastTextChunk = null;
        for (TextElement element : textElements) {
            if (lastTextChunk != null && ChunkUtil.canAdd(lastTextChunk, element)) {
                lastTextChunk.addElement(element);
            } else {
                TextChunk textChunk = new TextChunk();
                textChunks.add(textChunk);
                textChunk.addElement(element);
                lastTextChunk = textChunk;
            }
        } // end for TextElement

        // 检测已分好的组里面是否存在大量空白, 有的话则拆开
        final Iterator<TextChunk> each = textChunks.iterator();
        List<TextChunk> newTextChunks = new ArrayList<>();
        while (each.hasNext()) {
            TextChunk textChunk = each.next();
            List<TextChunk> subTextChunks = ChunkUtil.splitBySpaces(textChunk, 4);
            if (subTextChunks.size() > 0) {
                each.remove();
                newTextChunks.addAll(subTextChunks);
            } else if (StringUtils.isEmpty(textChunk.getText())) {
                //删除空的组
                each.remove();
            }
        }
        textChunks.addAll(newTextChunks);
        NumberUtil.sortByReadingOrder(textChunks);
        return textChunks;
    }

    private static COSArray pdColorToArray(PDColor color) {
        COSArray array = new COSArray();
        for (float v : color.getComponents()) {
            array.add(new COSFloat(v));
        }
        if (color.isPattern()) {
            array.add(color.getPatternName());
        }
        return array;
    }

    public static COSName getColorSpaceName(PDColorSpace colorSpace, PDResources resources) {
        COSName colorSpaceName = null;
        if (colorSpace.getCOSObject() != null) {
            for (COSName name : resources.getColorSpaceNames()) {
                PDColorSpace cs = null;
                try {
                    cs = resources.getColorSpace(name);
                } catch (Exception e) {
                    continue;
                }
                if (cs.getCOSObject() != null && colorSpace.getCOSObject().toString().equals(cs.getCOSObject().toString())) {
                    colorSpaceName = name;
                    break;
                }
            }
        }
        if (colorSpaceName == null) {
            colorSpaceName = COSName.getPDFName(colorSpace.getName());
        }
        return colorSpaceName;
    }

    public static List<Object> getGraphicsStateTokens(PDGraphicsState graphicsState, PDResources resources) {
        List<Object> graphicsStateTokens = new ArrayList<>();
        // fill color, stroke color, font size
        try {
            // Non Stroking color
            PDColor color = graphicsState.getNonStrokingColor();
            PDColorSpace colorSpace = color.getColorSpace();
            graphicsStateTokens.add(getColorSpaceName(colorSpace, resources));
            graphicsStateTokens.add(Operator.getOperator("cs"));
            for (COSBase base : pdColorToArray(color)) {
                graphicsStateTokens.add(base);
            }
            graphicsStateTokens.add(Operator.getOperator("sc"));

            // stroke color
            color = graphicsState.getStrokingColor();
            colorSpace = color.getColorSpace();
            graphicsStateTokens.add(getColorSpaceName(colorSpace, resources));
            graphicsStateTokens.add(Operator.getOperator("CS"));
            for (COSBase base : pdColorToArray(color)) {
                graphicsStateTokens.add(base);
            }
            graphicsStateTokens.add(Operator.getOperator("SC"));
            // font size

            // 当前状态的 textState 可能没有 Font, 加上Font 是否为 null 的判断
            PDFont font = graphicsState.getTextState().getFont();
            if (font != null) {
                // items 可能没有 Name item  所有加上此判断
                COSDictionary items = font.getCOSObject();
                if (items.getItem("Name") != null) {
                    COSName name = (COSName) (items.getItem("Name").getCOSObject());
                    graphicsStateTokens.add(name);
                    float size = graphicsState.getTextState().getFontSize();
                    graphicsStateTokens.add(new COSFloat(size));
                    graphicsStateTokens.add(Operator.getOperator("Tf"));
                } else {
                    // 遍历 resource 中的字体 找出和当前状态下字体相同的 (存在PDF中的字体数据中没有 Name 数据的情况)
                    resources.getFontNames().forEach(name -> {
                        try {
                            if (resources.getFont((COSName) name).equals(font)) {
                                graphicsStateTokens.add((COSName)name);
                                float size = graphicsState.getTextState().getFontSize();
                                graphicsStateTokens.add(new COSFloat(size));
                                graphicsStateTokens.add(Operator.getOperator("Tf"));
                            }
                        }
                        catch (IOException e) { }
                    });
                    //logger.debug("has no Font information");
                }
            } // end if font != null

            RenderingMode renderingMode = graphicsState.getTextState().getRenderingMode();
            graphicsStateTokens.add(COSInteger.get(renderingMode.intValue()));
            graphicsStateTokens.add(Operator.getOperator("Tr"));

            // 当前的TransformationMatrix
            Matrix matrix = graphicsState.getCurrentTransformationMatrix();
            if (matrix != null) {
                COSArray array = matrix.toCOSArray();
                graphicsStateTokens.addAll(array.toList());
                graphicsStateTokens.add(Operator.getOperator("cm"));
            }
        } catch (Exception e) {
            logger.warn("build graphicsStateTokens failed", e);
            return null;
        }
        return graphicsStateTokens;
    }

    public static void writeTokensToForm(PDFormXObject form, List<Object> tokens) {
        try {
            PDStream stream = form.getContentStream();
            OutputStream output = stream.createOutputStream(COSName.FLATE_DECODE);
            ContentStreamWriter writer = new ContentStreamWriter(output);
            writer.writeTokens(tokens);
            output.close();
        } catch (IOException e) {
            logger.warn("writeFormTokens failed", e);
        }
    }

    public static void writeTokensToForm(PDFormXObject form, PDGraphicsState graphicsState, List<Object> tokens) {
        // 获取当前绘图状态
        List<Object> graphicsStateTokens = getGraphicsStateTokens(graphicsState, form.getResources());
        if (graphicsStateTokens != null) {
            tokens.addAll(0, graphicsStateTokens);
        }
        // 写入form的内容流
        writeTokensToForm(form, tokens);
    }

    /**
     * 保存chart的单个 Chunk
     * @param chart
     * @param info
     * @param chunk
     * @param pos
     * @param series
     */
    private static void saveChunksBox(
            Chart chart,
            String info,
            TextChunk chunk,
            double [] pos,
            JsonArray series) {
        if (chunk == null) {
            return;
        }
        List<TextChunk> chunks = new ArrayList<>();
        chunks.add(chunk);
        saveChunksBox(chart, info, chunks, pos, series);
    }

    /**
     * 输出给定Chunk集的基于参点的包围框位置信息
     * @param chart
     * @param info
     * @param chunks
     * @param pos
     * @param series
     */
    private static void saveChunksBox(
            Chart chart,
            String info,
            List<TextChunk> chunks,
            double [] pos,
            JsonArray series) {
        if (chunks == null || chunks.isEmpty()) {
            return ;
        }
        for (int i = 0; i < chunks.size(); i++) {
            TextChunk chunk = chunks.get(i);
            Rectangle2D box = chunk.getBounds();
            if (!chart.getArea().contains(box)) {
                continue;
            }

            JsonArray data = getRectangleInfo(box, pos);
            TextDirection direction = chunk.getDirection();
            if (direction == TextDirection.LTR) {
                data.add(0);
            }
            else if (direction == TextDirection.VERTICAL_UP) {
                data.add(90);
            }
            else if (direction == TextDirection.VERTICAL_DOWN) {
                data.add(270);
            }
            else {
                data.add(0);
            }
            JsonObject serieDoc = new JsonObject();
            serieDoc.addProperty("text", chunk.getText());
            serieDoc.add("rectangle", data);
            serieDoc.addProperty("property", info);
            series.add(serieDoc);
        } // end for i
    }

    /**
     * 计算给定Rectangle2D相对于Chart的相关位置信息的中心点和长宽信息
     * @param box
     * @param pos
     * @return
     */
    private static JsonArray getRectangleInfo(Rectangle2D box, double [] pos) {
        JsonArray data = new JsonArray();
        double x = box.getCenterX() - pos[0];
        double y = box.getCenterY() - pos[1];
        data.add(x * pos[2]);
        data.add(y * pos[3]);
        data.add(box.getWidth() * pos[2] + 2.0);
        data.add(box.getHeight() * pos[3] + 2.0);
        return data;
    }

    /**
     * 保存Chart.ocrs信息的包围框信息 如果存在OCR刻度的话
     * @param chart
     * @param info
     * @param pos
     * @param series
     */
    private static void saveOCRsBox(
            Chart chart,
            String info,
            double [] pos,
            JsonArray series) {
        if (chart.ocrs == null || chart.ocrs.isEmpty()) {
            return;
        }
        for (int i = 0; i < chart.ocrs.size(); i++) {
            OCRPathInfo ocr = chart.ocrs.get(i);
            Rectangle2D box = ocr.path.getBounds2D();
            if (!chart.getArea().contains(box)) {
                continue;
            }
            double angle = Math.atan2(box.getHeight(), box.getWidth());
            JsonArray data = getRectangleInfo(box, pos);
            data.add(Math.toDegrees(angle));
            JsonObject serieDoc = new JsonObject();
            serieDoc.addProperty("text", ocr.text);
            serieDoc.add("rectangle", data);
            serieDoc.addProperty("property", info);
            series.add(serieDoc);
        } // end for i
    }

    /**
     * 输出给定Chart内部Chunk的包围框信息
     * @param chart
     * @param file
     * @param xScale
     * @param yScale
     * @return
     */
    private static boolean saveChartChunksBox(
            Chart chart, File file, double xScale, double yScale)  {
        try {
            FileWriter resultFile = new FileWriter(file);
            PrintWriter myFile = new PrintWriter(resultFile);

            double [] pos = { chart.getArea().getX(), chart.getArea().getY(), xScale, yScale };
            JsonObject bson = new JsonObject();
            JsonArray series = new JsonArray();
            saveChunksBox(chart, "axis_left", chart.vAxisChunksL, pos, series);
            saveChunksBox(chart, "axis_right", chart.vAxisChunksR, pos, series);
            saveChunksBox(chart, "axis_up", chart.hAxisChunksU, pos, series);
            saveChunksBox(chart, "axis_down", chart.hAxisChunksD, pos, series);
            saveChunksBox(chart, "legends", chart.legendsChunks, pos, series);
            //saveChunksBox(chart, "title", chart.titleTextChunk, pos, series);
//            saveChunksBox(chart, "others", chart.textsChunks, pos, series);
            saveChunksBox(chart, "others", chart.dataSourceChunk, pos, series);
            saveChunksBox(chart, "fans", chart.pieInfoLegendsChunks, pos,series);
            saveOCRsBox(chart, "axis_down", pos, series);
            bson.add("label", series);
            myFile.println(JsonUtil.toString(bson, true));

            resultFile.close();
        }
        catch (Exception e) {
            return false;
        }
        return true;
    }

    /**
     * 计算给定区域对象在给定绘图状态下实际大小的 Rectangle2D
     * @param state
     * @param shape
     * @return
     */
    public static Rectangle2D getShapeCurrentGraphicsBox(PDGraphicsState state, PDRectangle shape) {
        if (state == null || shape == null) {
            return null;
        }
        Rectangle2D box = new Rectangle2D.Double(shape.getLowerLeftX(), shape.getLowerLeftY(),
                shape.getWidth(), shape.getHeight());
        return getShapeCurrentGraphicsBox(state, box);
    }

    public static Rectangle2D getShapeCurrentGraphicsBox(PDGraphicsState state, Rectangle2D shape) {
        if (state == null || shape == null) {
            return null;
        }
        Matrix mat = state.getCurrentTransformationMatrix();
        if (mat != null) {
            AffineTransform transform = mat.createAffineTransform();
            return transform.createTransformedShape(shape).getBounds2D();
        }
        return null;
    }

    /**
     * 判断给定区域是否过大
     * @param page
     * @param bbox
     * @return
     */
    public static boolean isAreaNotBig(Rectangle2D page, Rectangle2D bbox) {
        double pageWidth = page.getWidth();
        double pageHeight = page.getHeight();
        double width = bbox.getWidth();
        double height = bbox.getHeight();
        if ((width >= pageWidth * 0.8) && (height >= pageHeight * 0.85)) {
            return false;
        }
        return true;
    }

    /**
     * 判断给定区域是否过小
     * @param page
     * @param bbox
     * @return
     */
    public static boolean isAreaNotSmall(Rectangle2D page, Rectangle2D bbox) {
        double pageWidth = page.getWidth();
        double pageHeight = page.getHeight();
        double width = bbox.getWidth();
        double height = bbox.getHeight();

        // 添加区域范围大小的判定　一般有效chart的宽高都在10以上　　以后可以调整大小
        if (width < 20.0 || height < 20.0) {
            return false;
        }
        if (width < pageWidth * 0.08 || height < pageHeight * 0.05) {
            return false;
        }
        double ratio = width / height;
        if (ratio < 0.12 || ratio > 8) {
            return false;
        }
        return true;
    }

    /**
     * 判断两个位图对象内容是否相同
     * @param img1
     * @param img2
     * @return
     */
    public static boolean imageXObjectEqual(PDImageXObject img1, PDImageXObject img2) {
        try {
            if (img1 == null || img2 == null) {
                return false;
            }
            // 先简单判断主要属性是否相同  属性相同可能图不同　　属性不同图一定不同
            if (!img1.getCOSObject().toString().equals(img2.getCOSObject().toString())) {
                return false;
            }
            // 再判断内容流是否相同
            InputStream dctStream1 = img1.createInputStream();
            InputStream dctStream2 = img2.createInputStream();
            return IOUtils.contentEquals(dctStream1, dctStream2);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 将当前位图对象和目前为止解析到的位图对象进行内容匹配 如果不存在 则认为是候选位图对象
     * @param charts
     */
    public static void filterSameBitmapChart(List<Chart> charts) {
        if (charts.isEmpty()) {
            return;
        }
        List<Chart> bitmapCharts = new ArrayList<>();
        charts.stream().forEach(chart -> {
            if (chart.type == ChartType.BITMAP_CHART && chart.image != null) {
                bitmapCharts.add(chart); } });
        int n = bitmapCharts.size();
        if (n == 0) {
            return;
        }
        for (int i = 0; i < n; i++) {
            Chart chartA = bitmapCharts.get(i);
            for (int j = i + 1; j < n; j++) {
                Chart chartB = bitmapCharts.get(j);
                // 判断图片内容是否相同
                if (ChartUtils.imageXObjectEqual(chartA.image, chartB.image)) {
                    chartA.type = ChartType.UNKNOWN_CHART;
                    break;
                }
            }
        } // end for i
        charts.removeIf(chart -> chart.type == ChartType.UNKNOWN_CHART);
    }

    /**
     * 判断给定的operands个数和部分内容是否和给定的参数匹配　用pdfbox对照查看PDF内容，方便调试跟踪
     * @param operands
     * @param num
     * @param content
     * @return
     */
    public static boolean matchOperands(List<COSBase> operands, int num, String content) {
        if (operands.isEmpty() || StringUtils.isEmpty(content) || operands.size() != num) {
            return false;
        }

        List<String> parts = Arrays.asList(content.split(" "));
        List<String> contentparts = new ArrayList<>();
        parts.stream().map((s) -> s.trim()).filter((s) -> s.length() >= 1).forEach(s -> contentparts.add(s));
        for (String str : contentparts) {
            boolean bFind = operands.stream().filter(obj -> obj instanceof COSNumber)
                    .anyMatch(obj -> ((COSNumber)obj).toString().contains(str));
            if (!bFind) {
                return false;
            }
        }
        return true;
    }

    /**
     * 将chunk在区域area中的位置, 内容信息保存为JsonObject对象
     * 为了方便位图解析 将Chart中的有效文字信息保存下来
     * @param area
     * @param chunk
     * @return
     */
    public static JsonObject textChunk2Json(
            Rectangle2D area, TextChunk chunk, AffineTransform chartTransform) {
        JsonObject text = new JsonObject();
        double xminA = area.getMinX();
        double yminA = area.getMinY();
        double xmaxA = area.getMaxX();
        double ymaxA = area.getMaxY();
        Rectangle2D chunkBox = chunk.getBounds2D();
        //chunkBox = chartTransform.createTransformedShape(chunkBox).getBounds2D();
        double xmin = (chunk.getMinX() - xminA) / (xmaxA - xminA);
        double ymin = (chunk.getMinY() - yminA) / (ymaxA - yminA);
        double xmax = (chunk.getMaxX() - xminA) / (xmaxA - xminA);
        double ymax = (chunk.getMaxY() - yminA) / (ymaxA - yminA);
        if (xmin < 0.0 || ymin < 0.0 || xmax > 1.0 || ymax > 1.0) {
            return null;
        }

        text.addProperty("text", chunk.getText());
        text.addProperty("xmin", xmin);
        text.addProperty("ymin", ymin);
        text.addProperty("xmax", xmax);
        text.addProperty("ymax", ymax);
        text.addProperty("size", chunk.getMaxFontSize());

        // 保存方向信息
        TextDirection direction = chunk.getDirection();
        if (direction == TextDirection.LTR) {
            text.addProperty("direction", "LTR");
        }
        else if (direction == TextDirection.RTL) {
            text.addProperty("direction", "RTL");
        }
        else if (direction == TextDirection.VERTICAL_DOWN) {
            text.addProperty("direction", "VERTICAL_DOWN");
        }
        else if (direction == TextDirection.VERTICAL_UP) {
            text.addProperty("direction", "VERTICAL_UP");
        }
        else if (direction == TextDirection.ROTATED) {
            text.addProperty("direction", "ROTATED");
            double angle = Math.atan2(chunk.getHeight(), chunk.getWidth());
            angle = 180.0 * angle / Math.PI;
            List<TextElement> eles = chunk.getElements();
            int n = eles.size();
            if (n <= 1) {
                text.addProperty("angle", -45);
            }
            else {
                if (eles.get(0).getCenterY() >= eles.get(n - 1).getCenterY()) {
                    text.addProperty("angle", -angle);
                }
                else {
                    text.addProperty("angle", angle);
                }
            }
        }
        return text;
    }

    /**
     * 将OCRPathInfo在Chart中位置, 内容信息保存为JsonObject对象
     * 为了方便位图解析 将Chart中的OCR文字信息保存下来
     * @param area
     * @param ocr
     * @param chartTransform
     * @return
     */
    public static JsonObject ocr2Json(
            Rectangle2D area, OCRPathInfo ocr, AffineTransform chartTransform) {
        // 以前保存所有斜着文字的大外包框方法不太实用，故保存每个斜着文字带方向最小外包框 OBB
        List<Point2D> obb = MinOrientedBoundingBoxComputer.computOBB(ocr.path);
        JsonObject text = new JsonObject();
        double xminA = area.getMinX();
        double yminA = area.getMinY();
        double xmaxA = area.getMaxX();
        double ymaxA = area.getMaxY();
        JsonArray pts = new JsonArray();
        for (int i = 0; i < 4; i++) {
            Point2D p = obb.get(i);
            //Rectangle2D ptBox = new Rectangle2D.Double(p.getX(), p.getY(), 0, 0);
            //ptBox = chartTransform.createTransformedShape(ptBox).getBounds2D();
            double x = (p.getX() - xminA) / (xmaxA - xminA);
            double y = (p.getY() - yminA) / (ymaxA - yminA);
            JsonObject pt = new JsonObject();
            pt.addProperty("x", x);
            pt.addProperty("y", y);
            pts.add(pt);
        }
        text.addProperty("text", ocr.text);
        text.add("obb", pts);
        // 计算旋转角度
        double angle = Math.atan2(ocr.path.getBounds2D().getHeight(), ocr.path.getBounds2D().getWidth());
        angle = 180.0 * angle / Math.PI;
        text.addProperty("direction", "ROTATED");
        if (ocr.ccw) {
            text.addProperty("rotation", -angle);
        }
        else {
            text.addProperty("rotation", angle);
        }
        return text;
    }

    /**
     * 将给定Chart内部文字信息保存为Json对象
     * @param chart
     * @return
     */
    public static JsonObject getChartTextInfo(Chart chart) {
        if (chart.isPPT ||
                chart.type == ChartType.BITMAP_CHART) {
            return null;
        }

        // 由于BufferedImage保存有宽高2048限制，故计算图片实际的缩放比例
        Rectangle2D cropbBox = chart.getDrawArea();
        double height = chart.page.getMediaBox().getHeight();
        double ymax = height - cropbBox.getMinY();
        double ymin = height - cropbBox.getMaxY();
        double xmin = cropbBox.getMinX();
        double xmax = cropbBox.getMaxX();
        cropbBox.setRect(xmin, ymin, xmax - xmin, ymax - ymin);
        AffineTransform chartTransform = getChartTransform(chart);

        // 保存Chart区域内部文字信息
        JsonObject textInfo = new JsonObject();
        List<TextChunk> chunks = mergeTextChunk(chart, chart.contentGroup);
        JsonArray texts = new JsonArray();
        for (TextChunk chunk : chunks) {
            JsonObject text = textChunk2Json(cropbBox, chunk, chartTransform);
            if (text != null) {
                texts.add(text);
            }
        }
        textInfo.add("texts", texts);

        JsonArray ocrs = new JsonArray();
        for (OCRPathInfo ocr : chart.ocrs) {
            JsonObject text = ocr2Json(cropbBox, ocr, chartTransform);
            if (text != null) {
                ocrs.add(text);
            }
        }
        if (ocrs.size() >= 1) {
            textInfo.add("ocrs", ocrs);
        }

        // 保存标题信息
        if (chart.title != null) {
            JsonObject title = new JsonObject();
            title.addProperty("text", chart.title);
            textInfo.add("title", title);
        }

        // 保存数据来源信息
        if (chart.dataSource != null) {
            JsonObject source = new JsonObject();
            source.addProperty("text", chart.dataSource);
            textInfo.add("data_source", source);
        }
        return textInfo;
    }

    /**
     * 将Chart内文字信息保存为Json文件  对接位图解析算法
     * 利用两种方法最好结果的初步尝试  后续可能通过GRPC方式调用位图解析服务
     * @param chart
     * @param dir
     */
    public static void saveChartTextJson(Chart chart, File dir) {
        String jsonFileName = String.format("chart_%d_%s.json", chart.pageIndex, chart.getName());
        File saveJsonFile = new File(dir, jsonFileName);
        try {
            FileWriter resultFile = new FileWriter(saveJsonFile);
            PrintWriter myFile = new PrintWriter(resultFile);
            JsonObject textInfo = getChartTextInfo(chart);
            myFile.println(JsonUtil.toString(textInfo, true));
            resultFile.close();
        }
        catch (Exception e) {
            return;
        }
    }

    public static void saveChartPathJson(File dir, Chart chart, JsonObject pathJson, long chartID, int id, String info) {
        if (dir == null || pathJson.isJsonNull()) {
            return;
        }
        String jsonFileName = String.format("%d_chart_%d_%s.%s.%d.json", chartID, chart.pageIndex, chart.getName(), info, id);
        File saveJsonFile = new File(dir, jsonFileName);
        try {
            FileWriter resultFile = new FileWriter(saveJsonFile);
            PrintWriter myFile = new PrintWriter(resultFile);
            myFile.println(JsonUtil.toString(pathJson, true));
            resultFile.close();
        }
        catch (Exception e) {
            return;
        }
    }

    /**
     * 判断给定的两个点是否构成水平或垂直线段
     * @param p1
     * @param p2
     * @param isHorizon
     * @param coef
     * @return
     */
    public static boolean isTwoHorizonVerticalLine(
            Point2D p1, Point2D p2, boolean isHorizon, double coef) {
        if (p1 == null || p2 == null || coef <= 0.0) {
            return false;
        }
        double dx = Math.abs(p1.getX() - p2.getX());
        double dy = Math.abs(p1.getY() - p2.getY());
        if (!isHorizon) {
            return (dx < coef && dy > coef);
        }
        else {
            return (dx > coef && dy < coef);
        }
    }

    public static boolean isTwoLineOrthogonality(Line2D lineA, Line2D lineB, boolean testIntersect) {
        double x1 = lineA.getX1() - lineA.getX2();
        double y1 = lineA.getY1() - lineA.getY2();
        double x2 = lineB.getX1() - lineB.getX2();
        double y2 = lineB.getY1() - lineB.getY2();
        double s = x1 * x2 + y1 * y2;
        if (testIntersect) {
            double dist = Math.min(lineA.ptLineDist(lineB.getP1()), lineA.ptLineDist(lineB.getP2()));
            return Math.abs(s) < 0.1 && dist < 0.1;
        }
        else {
            return Math.abs(s) < 0.1;
        }
    }

    public static boolean isHorizon(Line2D line) {
        double dx = Math.abs(line.getX1() - line.getX2());
        double dy = Math.abs(line.getY1() - line.getY2());
        return dx > 0.1 && dy < 0.1;
    }

    public static boolean approximationColor(Color c1, Color c2, double coef) {
        double dr = Math.abs(c1.getRed() - c2.getRed());
        double dg = Math.abs(c1.getGreen() - c2.getGreen());
        double db = Math.abs(c1.getBlue() - c2.getBlue());
        return dr + dg + db <= coef;
    }

    /**
     * 将chart中contentgroup分组，大致相似为一组
     * @param chart
     */
    public static void markChartPath(Chart chart) {
        //System.out.println("--------------------  " + chart.pageIndex + "  ----------------------");
        if (chart.contentGroup == null) {
            return;
        }
        chart.markPaths.clear();
        ContentGroup groupB = null;
        List<Integer> groupsB = new ArrayList<>();
        List<ContentGroup> allContentGroups = chart.contentGroup.getAllContentGroups(true, true);
        int n = allContentGroups.size();
        Rectangle2D box = chart.getArea();
        for (int id = 0; id < n; id++) {
            ContentGroup group = allContentGroups.get(id);
            if (!group.hasOnlyPathItems()) {
                //if (id == n - 1 && groupsB.size() >= 1) {
                if (groupsB.size() >= 1) {
                    chart.markPaths.add(new ArrayList<>(groupsB));
                    //saveContentGroups(dir, chart, groupsB, ith_path++);
                    groupsB.clear();
                    groupB = null;
                    //break;
                }
                continue;
            }

            // 忽略掉白色背景对象
            ContentItem item = group.getAllItems().get(0);
            PathItem pathItem = (PathItem)item;
            Color color = pathItem.getColor();
            if(color.getRed() == 255 && color.getGreen() == 255 && color.getBlue() == 255) {
                // 如果是最后一个　且groupsB不为空
                if (id == n - 1 && groupsB.size() >= 1) {
                    //chart.markPaths.add(groupsB);
                    chart.markPaths.add(new ArrayList<>(groupsB));
                    //saveContentGroups(dir, chart, groupsB, ith_path++);
                    break;
                }
                continue;
            }
            Rectangle2D pBox = pathItem.getItem().getBounds2D();
            GraphicsUtil.extendRect(pBox, 0.01);
            if (!box.intersects(pBox)) {
                if (groupsB.size() >= 1) {
                    //chart.markPaths.add(groupsB);
                    chart.markPaths.add(new ArrayList<>(groupsB));
                    //saveContentGroups(dir, chart, groupsB, ith_path++);
                    groupsB.clear();
                    groupB = null;
                }
                continue;
            }

            if (groupB == null) {
                groupB = group;
                groupsB.add(id);
                if (id == n - 1) {
                    //chart.markPaths.add(groupsB);
                    chart.markPaths.add(new ArrayList<>(groupsB));
                    //saveContentGroups(dir, chart, groupsB, ith_path++);
                }
                continue;
            }

            // 判断相邻两个ContentGroup是否完全重叠
            if (isOverlapContentGroup(group, groupB)) {
                if (id == n - 1) {
                    //chart.markPaths.add(groupsB);
                    chart.markPaths.add(new ArrayList<>(groupsB));
                    //saveContentGroups(dir, chart, groupsB, ith_path++);
                }
                continue;
            }

            if (!isSimilarContentGroup(group, groupB)) {
                //chart.markPaths.add(groupsB);
                chart.markPaths.add(new ArrayList<>(groupsB));
                //saveContentGroups(dir, chart, groupsB, ith_path++);
                groupsB.clear();
            }
            else if (id == n - 1){
                groupsB.add(id);
                //chart.markPaths.add(groupsB);
                chart.markPaths.add(new ArrayList<>(groupsB));
                //saveContentGroups(dir, chart, groupsB, ith_path++);
                break;
            }
            groupsB.add(id);
            groupB = group;

            // 处理最后一个对象
            if (id == n - 1) {
                //chart.markPaths.add(groupsB);
                chart.markPaths.add(new ArrayList<>(groupsB));
                //saveContentGroups(dir, chart, groupsB, ith_path++);
            }
        } // end for id
    }

    /**
     * 计算Chart内部所有Path的JsonObject对象
     * @param chart
     */
    public static List<PathInfoData> buildChartPathClassifyInfoDatas(Chart chart, File dir, boolean save) {
        List<ContentGroup> allContentGroups = chart.contentGroup.getAllContentGroups(true, true);
        List<JsonObject> pathsJsons = new ArrayList<>();
        List<Integer> groupIDs = new ArrayList<>();
        for (int i = 0; i < chart.markPaths.size(); i++) {
            List<Integer> group = chart.markPaths.get(i);
            List<ContentGroup> pathGroup = new ArrayList<>();
            for (Integer id : group) {
                pathGroup.add(allContentGroups.get(id));
            }
            JsonObject pathJson = getContentGroupPathJsonObject(chart, pathGroup);
            if (pathGroup != null) {
                pathsJsons.add(pathJson);
                groupIDs.add(i);
            }
        } // end for group

        // 计算同一个Chart内 path间的特征
        int n = pathsJsons.size();
        List<PathInfoData> pathsDatas = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            JsonObject obj = pathsJsons.get(i);
            PathInfoData pathData = PathClassifyUtils.read_json(obj, "", false);
            pathData.groupID = groupIDs.get(i);
            pathsDatas.add(pathData);
        } // end for i

        // 计算空间位置对齐 垂直柱状上下侧对齐 或是 水平柱状左右侧对齐
        PathClassifyUtils.compBoxPositionFeature(pathsDatas);

        // 计算是否存在同色且 垂直柱状宽度 或是 水平柱状高度相同的矩形对象
        PathClassifyUtils.compBoxSameColorSizeFeature(pathsDatas);

        // 计算是否存在同色的小path对象即类似图例图形对象
        PathClassifyUtils.compMatchSmallPathFeature(pathsDatas);
        //　TODO 后续可能需要添加其他额外特征

        // 调用path组分类器 分类
        //System.out.println("------------------ predict path -----------------");
        ChartPathClassify.predictPath(pathsDatas);

        if (save && dir != null && dir.exists()) {
            long startTime = System.currentTimeMillis();
            String pngFile = String.format("%d_chart_%d_%s.%s", startTime, chart.pageIndex, chart.getName(), chart.getFormatName());
            File saveFile = new File(dir, pngFile);
            if (!ChartUtils.saveChartToFile(chart, saveFile)) {
                //if (!ChartUtils.saveChartToJpg(chart, saveFile)) {
                saveFile.delete();
            }
            for (int i = 0; i < n; i++) {
                saveChartPathJson(dir, chart, pathsDatas.get(i).obj, startTime, i, "path");
            }
        }
        return pathsDatas;
    }

    /**
     * 比较给定两个Chart　第一个是否比第二个好
     * @param chart1
     * @param chart2
     * @return
     */
    public static boolean betterChart(Chart chart1, Chart chart2) {
        if (chart1.confidence > chart2.confidence + 0.2) {
            return true;
        }
        else if (chart1.confidence < chart2.confidence - 0.2) {
            return false;
        }

        // 处理饼图
        ChartType type1 = chart1.getType();
        ChartType type2 = chart2.getType();
        if (type1 == type2 && type1 == ChartType.PIE_CHART) {
            if (chart1.pieInfos.isEmpty()) {
                chart1.pieInfos.add(chart1.pieInfo);
            }
            if (chart2.pieInfos.isEmpty()) {
                chart2.pieInfos.add(chart2.pieInfo);
            }
            return betterPies(chart1.pieInfos, chart2.pieInfos);
        }

        // 判断图形对象 有效数目
        List<ChartPathInfo> pathInfos1 = chart1.pathInfos;
        List<ChartPathInfo> pathInfos2 = chart2.pathInfos;
        int count3 = matchLegendPathInfoCount(pathInfos1);
        int count4 = matchLegendPathInfoCount(pathInfos2);
        if (count3 > count4) {
            return true;
        }
        else if (count3 < count4) {
            return false;
        }

        // 判断图形对象 有效属性数目
        int count1 = betterPathInfoCount(pathInfos1, pathInfos2);
        int count2 = betterPathInfoCount(pathInfos2, pathInfos1);
        if (count1 > count2) {
            return true;
        }
        else if (count1 < count2) {
            return false;
        }

        // 判断OCR
        if (chart1.ocrs.size() >= 1 && chart2.ocrs.size() >= 1) {
            List<String> ocrs1 = new ArrayList<>();
            for (int i = 0; i < chart1.ocrs.size(); i++) {
                OCRPathInfo ocr = chart1.ocrs.get(i);
                ocrs1.add(ocr.text);
            }
            List<String> ocrs2 = new ArrayList<>();
            for (int i = 0; i < chart2.ocrs.size(); i++) {
                OCRPathInfo ocr = chart2.ocrs.get(i);
                ocrs2.add(ocr.text);
            }
            if (betterOCRText(ocrs1, ocrs2)) {
                return true;
            }
            else if (betterOCRText(ocrs2, ocrs1)) {
                return false;
            }
        }

        return chart1.confidence >= chart2.confidence;
    }

    public static boolean betterOCRText(List<String> texts1, List<String> texts2) {
        int num1 = 0, num2 = 0;
        for (String text : texts1) {
            text = text.trim();
            num1 += text.length();
        }
        for (String text : texts2) {
            text = text.trim();
            num2 += text.length();
        }
        return num1 > num2;
    }

    public static boolean betterPies(List<Chart.PieInfo> pieInfos1, List<Chart.PieInfo> pieInfos2) {
        int num1 = pieInfos1.size();
        int num2 = pieInfos2.size();
        if (num1 > num2) {
            return true;
        }
        else if (num1 < num2) {
            return false;
        }
        for (int i = 0; i < num1; i++) {
            Chart.PieInfo pieInfo1 = pieInfos1.get(i);
            Chart.PieInfo pieInfo2 = pieInfos2.get(i);
            int num3 = pieInfo1.parts.size();
            int num4 = pieInfo2.parts.size();
            if (num3 > num4) {
                return true;
            }
            else if (num3 < num4) {
                return false;
            }
        }
        return true;
    }

    public static boolean betterPathInfo(ChartPathInfo pathInfo1, ChartPathInfo pathInfo2) {
        if (pathInfo1.type != pathInfo2.type || !pathInfo1.text.equals(pathInfo2.text) ||
                pathInfo1.type != pathInfo2.type ||
                !pathInfo1.color.equals(pathInfo2.color)) {
            return false;
        }
        if (pathInfo1.valuesY.size() > pathInfo2.valuesY.size()) {
            return true;
        }
        else {
            return false;
        }
    }

    public static int matchLegendPathInfoCount(List<ChartPathInfo> pathInfos) {
        Set<String> texts = new HashSet<>();
        for (ChartPathInfo pathInfo : pathInfos) {
            if (StringUtils.isNotEmpty(pathInfo.text)) {
                texts.add(pathInfo.text);
            }
        }
        return texts.size();
    }

    public static boolean betterPathInfos(List<ChartPathInfo> pathInfos1, List<ChartPathInfo> pathInfos2) {
        int count1 = matchLegendPathInfoCount(pathInfos1);
        int count2 = matchLegendPathInfoCount(pathInfos2);
        return count1 > count2;
    }

    public static int betterPathInfoCount(List<ChartPathInfo> pathInfos1, List<ChartPathInfo> pathInfos2) {
        int count = 0;
        for (int i = 0; i < pathInfos1.size(); i++) {
            ChartPathInfo pathInfo1 = pathInfos1.get(i);
            for (int j = 0; j < pathInfos2.size(); j++) {
                ChartPathInfo pathInfo2 = pathInfos2.get(j);
                if (betterPathInfo(pathInfo1, pathInfo2)) {
                    count++;
                    break;
                }

            } // end for j
        } // end for i
        return count;
    }

    /**
     * 保存Chart内部图形组对象
     * @param chart
     * @param dir
     */
    public static void saveChartMarkPathGroup(Chart chart, File dir) {
        int ith_path = 0;
        List<ContentGroup> allContentGroups = chart.contentGroup.getAllContentGroups(true, true);
        for (List<Integer> group : chart.markPaths) {
            List<ContentGroup> pathGroup = new ArrayList<>();
            for (Integer id : group) {
                pathGroup.add(allContentGroups.get(id));
            }
            saveContentGroups(dir, chart, pathGroup, ith_path++);
        } // end for group
    }

    /**
     * 保存Chart内部文字对象
     * @param chart
     * @param dir
     */
    public static void saveChartAuxiliaryInfo(Chart chart, File dir) {
        List<TextChunk> chunks = chart.contentGroup.getAllTextChunks();
        double xmin = 0, xmax = 0, ymin = 0, ymax = 0;
        AffineTransform chartTransform = getChartTransform(chart);
        JsonArray textObjs = new JsonArray();
        for (TextChunk chunk : chunks) {
            if (StringUtils.isEmpty(chunk.getText())) {
                continue;
            }
            List<TextElement> elements = chunk.getElements();
            for (TextElement element : elements) {
                String text = element.getText().trim();
                if (StringUtils.isEmpty(text)) {
                    continue;
                }
                xmin = element.getMinX();
                xmax = element.getMaxX();
                ymin = element.getMinY();
                ymax = element.getMaxY();
                Rectangle pBox = new Rectangle(xmin, ymin, 0, 0);
                Rectangle2D ptNew = chartTransform.createTransformedShape(pBox).getBounds2D();
                xmin = ptNew.getCenterX();
                ymin = ptNew.getCenterY();
                pBox = new Rectangle(xmax, ymax, 0, 0);
                ptNew = chartTransform.createTransformedShape(pBox).getBounds2D();
                xmax = ptNew.getCenterX();
                ymax = ptNew.getCenterY();
                JsonArray box = new JsonArray();
                box.add(xmin);
                box.add(ymin);
                box.add(xmax);
                box.add(ymax);
                JsonObject textObj = new JsonObject();
                textObj.addProperty("text", text);
                textObj.add("box", box);
                textObj.addProperty("direction", element.getDirection().toString());
                textObj.addProperty("font_size", element.getFontSize());
                textObj.addProperty("group_index", element.getGroupIndex());
                textObjs.add(textObj);
            } // end for element
        } // end for chunk
        JsonObject auxiliaryInfo = new JsonObject();
        auxiliaryInfo.add("texts", textObjs);
        auxiliaryInfo.addProperty("verify", 0);
        // 保存类型
        JsonArray pathTypes = getChartInnerPathTypes(chart);
        auxiliaryInfo.add("path_types", pathTypes);
        saveChartPathJson(dir, chart, auxiliaryInfo, 0, 0, "auxiliary");
    }

    public static List<ChartType> getChartTypes(Chart chart) {
        Set<ChartType> types = new HashSet<>();
        for (ChartPathInfo pathInfo : chart.pathInfos) {
            PathInfo.PathType pathType = pathInfo.type;
            switch (pathType) {
                case LINE:
                case CURVE:
                case DASH_LINE:
                case SINGLE_LINE:
                case HORIZON_LONG_LINE:
                    types.add(ChartType.LINE_CHART);
                    break;
                case ARC:
                    types.add(ChartType.PIE_CHART);
                    break;
                case BAR:
                case COLUMNAR:
                    types.add(ChartType.BAR_CHART);
                    break;
                case AREA:
                    types.add(ChartType.AREA_CHART);
                    break;
                default:
                    break;
            } // end switch
        } // end for pathInfo
        if (chart.type == ChartType.PIE_CHART) {
            types.add(ChartType.PIE_CHART);
        }
        return new ArrayList<>(types);
    }

    public static JsonArray getChartInnerPathTypes(Chart chart) {
        /*
        JsonArray pathTypes = new JsonArray();
        List<ChartType> types = getChartTypes(chart);
        for (ChartType type : types) {
            pathTypes.add(type.toString());
        }
        */
        JsonArray pathTypes = new JsonArray();
        if (chart.subTypes.isEmpty()) {
            pathTypes.add(chart.getType().toString());
        }
        for (ChartType type : chart.subTypes) {
            pathTypes.add(type.toString());
        }
        return pathTypes;
    }

    /**
     * 判断两个包围框是否近似重合
     * @param boxa
     * @param boxb
     * @return
     */
    private static boolean isApproximationOverlapBox(Rectangle2D boxa, Rectangle2D boxb) {
        // 判断是否相包含
        Rectangle2D boxA = (Rectangle2D) boxa.clone();
        Rectangle2D boxB = (Rectangle2D) boxb.clone();
        GraphicsUtil.extendRect(boxA, 0.2);
        GraphicsUtil.extendRect(boxB, 0.1);
        if (!boxA.contains(boxB)) {
            return false;
        }
        GraphicsUtil.extendRect(boxB, 0.4);
        if (!boxB.contains(boxA)) {
            return false;
        }
        return true;
    }

    /**
     * 判断给定Path对象的代表点集和包围框是否近似重合与给定坐标轴轴标集
     * @param xsA
     * @param ysA
     * @param box
     * @param axises
     * @return
     */
    private static boolean isApproximationOverlapAxisPath(
            List<Double> xsA, List<Double> ysA, Rectangle2D box, List<Line2D> axises) {
        Rectangle2D boxB = axises.get(0).getBounds2D();
        for (Line2D line : axises) {
            Rectangle2D.union(line.getBounds2D(), boxB, boxB);
        }
        // 判断是否相交
        Rectangle2D boxA = (Rectangle2D) box.clone();
        GraphicsUtil.extendRect(boxA, 0.1);
        GraphicsUtil.extendRect(boxB, 0.1);
        if (!boxA.intersects(boxB)) {
            return false;
        }
        // 判断数目
        int nA = xsA.size(), nB = axises.size();
        // 判断前面几个点是否重合
        double xa = 0, ya = 0, x1b = 0, y1b = 0, x2b = 0, y2b = 0;
        double dist1 = 0, dist2 = 0, zero = 1;
        for (int i = 0; i < nA; i++) {
            xa = xsA.get(i);
            ya = ysA.get(i);
            boolean find = false;
            for (int j = 0; j < nB; j++) {
                Line2D line = axises.get(j);
                x1b = line.getX1();
                y1b = line.getY1();
                x2b = line.getX2();
                y2b = line.getY2();
                dist1 = Math.abs(xa - x1b) + Math.abs(ya - y1b);
                dist2 = Math.abs(xa - x2b) + Math.abs(ya - y2b);
                if (dist1 < zero || dist2 < zero) {
                    find = true;
                    break;
                }
            } // end for j
            if (!find) {
                return false;
            }
        } // end for i
        return true;
    }

    /**
     * 判断两个Path对象的代表点集和包围框是否近似重合
     * @param xsA
     * @param ysA
     * @param boxa
     * @param xsB
     * @param ysB
     * @param boxb
     * @param zero
     * @return
     */
    private static boolean isApproximationOverlapPath(
            List<Double> xsA, List<Double> ysA, Rectangle2D boxa,
            List<Double> xsB, List<Double> ysB, Rectangle2D boxb,
            double zero) {
        // 判断数目
        int nA = xsA.size(), nB = xsB.size();
        int n = Math.min(nA, nB);
        n = Math.min(n, 8);
        if (n <= 1) {
            return false;
        }

        // 判断是否相交
        Rectangle2D boxA = (Rectangle2D) boxa.clone();
        Rectangle2D boxB = (Rectangle2D) boxb.clone();
        GraphicsUtil.extendRect(boxA, 0.1);
        GraphicsUtil.extendRect(boxB, 0.1);
        if (!boxA.intersects(boxB)) {
            return false;
        }

        // 判断前面几个点是否重合
        double xa = 0, ya = 0, xb = 0, yb = 0, dist = 0;
        for (int i = 0; i < n; i++) {
            xa = xsA.get(i);
            ya = ysA.get(i);
            boolean find = false;
            for (int j = 0; j < nB; j++) {
                xb = xsB.get(j);
                yb = ysB.get(j);
                dist = Math.abs(xa - xb) + Math.abs(ya - yb);
                if (dist < zero) {
                    find = true;
                    break;
                }
            } // end for j
            if (!find) {
                return false;
            }
        } // end for i
        return true;
    }

    private static Color judgeContentGroupColor(ContentGroup group) {
        // 获取path关键点信息
        ContentItem item = group.getAllItems().get(0);
        PathItem pathItem = (PathItem) item;
        return pathItem.getColor();
    }

    /**
     * 判断Chart内ContentGroup的图形组类型 (用于尝试图形对象新分类方法用)
     * @param chart
     * @param group
     * @return
     */
    private static PathInfo.PathGroupType judgeContentGroupType(Chart chart, ContentGroup group) {
        // 如果是位图类型
        ChartType type = chart.type;
        if (type == ChartType.BITMAP_CHART || type == ChartType.BITMAP_PPT_CHART) {
            return PathInfo.PathGroupType.OTHER;
        }

        // 获取path关键点信息
        ContentItem item = group.getAllItems().get(0);
        PathItem pathItem = (PathItem) item;
        GeneralPath path = pathItem.getItem();
        List<Double> xs = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
        List<Integer> types = new ArrayList<>();
        List<Double> cxs = new ArrayList<>();
        List<Double> cys = new ArrayList<>();
        List<Integer> ctypes = new ArrayList<>();
        if (!PathUtils.getPathKeyPtsInfo(path, xs, ys, types)) {
            return PathInfo.PathGroupType.OTHER;
        }
        Rectangle2D box = path.getBounds2D();

        // 如果是饼图
        if (type == ChartType.PIE_CHART) {
            // 判断是否为扇型对象
            if (chart.pieInfos.isEmpty()) {
                chart.pieInfos.add(chart.pieInfo);
            }
            for (Chart.PieInfo pieInfo : chart.pieInfos) {
                for (Chart.PieInfo.PiePartInfo part : pieInfo.parts) {
                    GeneralPath cpath = part.path;
                    Rectangle2D cbox = cpath.getBounds2D();
                    if (PathUtils.getPathKeyPtsInfo(cpath, cxs, cys, ctypes)) {
                        if (isApproximationOverlapPath(xs, ys, box, cxs, cys, cbox, 1E-2)) {
                            return PathInfo.PathGroupType.ARC;
                        }
                    }
                } // end for PiePartInfo
            } // end for PieInfo
            // 判断是否为图例对象
            for (Chart.Legend legend : chart.legends) {
                Rectangle2D cbox = legend.line.getBounds2D();
                if (isApproximationOverlapBox(box, cbox)) {
                    return PathInfo.PathGroupType.LEGEND;
                }
            }
            return PathInfo.PathGroupType.OTHER;
        }
        // 判断是否和pathInfos吻合
        for (ChartPathInfo pathInfo : chart.pathInfos) {
            GeneralPath cpath = pathInfo.path;
            Rectangle2D cbox = cpath.getBounds2D();
            if (PathUtils.getPathKeyPtsInfo(cpath, cxs, cys, ctypes)) {
                if (isApproximationOverlapPath(xs, ys, box, cxs, cys, cbox, 1E-2)) {
                    if (pathInfo.type == PathInfo.PathType.LINE ||
                            pathInfo.type == PathInfo.PathType.CURVE ||
                            pathInfo.type == PathInfo.PathType.DASH_LINE ||
                            pathInfo.type == PathInfo.PathType.HORIZON_LONG_LINE) {
                        return PathInfo.PathGroupType.LINE;
                    }
                    else if (pathInfo.type == PathInfo.PathType.BAR) {
                        return PathInfo.PathGroupType.COLUMNAR_VERTICAL;
                    }
                    else if (pathInfo.type == PathInfo.PathType.COLUMNAR) {
                        return PathInfo.PathGroupType.COLUMNAR_HORIZON;
                    }
                    else if (pathInfo.type == PathInfo.PathType.AREA) {
                        return PathInfo.PathGroupType.AREA;
                    }
                    else {
                        return PathInfo.PathGroupType.OTHER;
                    }
                }
            }
        } // end for pathInfo
        // 判断是否为图例对象
        for (Chart.Legend legend : chart.legends) {
            Rectangle2D cbox = legend.line.getBounds2D();
            if (isApproximationOverlapBox(box, cbox)) {
                return PathInfo.PathGroupType.LEGEND;
            }
        }
        // 收集坐标轴和轴标
        List<Line2D> axises = new ArrayList<>();
        if (chart.hAxis != null) {
            axises.add(chart.hAxis);
        }
        if (chart.lvAxis != null) {
            axises.add(chart.lvAxis);
        }
        if (chart.rvAxis != null) {
            axises.add(chart.rvAxis);
        }
        if (!chart.axisScaleLines.isEmpty()) {
            axises.addAll(chart.axisScaleLines);
        }
        // 判断是否为坐标轴或轴标
        if (isApproximationOverlapAxisPath(xs, ys, box, axises)) {
            if (isXYAxisLine(xs, ys, types)) {
                return PathInfo.PathGroupType.AXIS;
            }
        }
        // 判断是否为斜着刻度
        for (OCRPathInfo ocr : chart.ocrs) {
            Rectangle2D cbox = ocr.path.getBounds2D();
            GraphicsUtil.extendRect(cbox, 0.1);
            if (cbox.contains(box)) {
                return PathInfo.PathGroupType.TEXT;
            }
        }

        // 其他类型
        return PathInfo.PathGroupType.OTHER;
    }

    /**
     * 判断path内部相邻两点是否都是水平或是垂直线段
     * @param xs
     * @param ys
     * @param types
     * @return
     */
    private static boolean isXYAxisLine(List<Double> xs, List<Double> ys, List<Integer> types) {
        int n = types.size();
        int type = 0;
        double dx = 0.0, dy = 0.0, coef = 1E-2;
        for (int i = 0; i < n -1; i++) {
            type = types.get(i + 1);
            if (type != PathIterator.SEG_MOVETO) {
                dx = xs.get(i + 1) - xs.get(i);
                dy = ys.get(i + 1) - ys.get(i);
                if (Math.abs(dx) > coef && Math.abs(dy) > coef) {
                    return false;
                }
            }
        } // end for i
        return true;
    }

    private static JsonObject getContentGroupPathJsonObject(Chart chart, List<ContentGroup> groups) {
        List<Double> xs = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
        List<Integer> types = new ArrayList<>();
        JsonArray series = new JsonArray();
        AffineTransform chartTransform = getChartTransform(chart);
        double x = 0.0, y = 0.0;
        for (int i = 0; i < groups.size(); i++) {
            ContentGroup group = groups.get(i);
            ContentItem item = group.getAllItems().get(0);
            PathItem pathItem = (PathItem) item;
            GeneralPath path = pathItem.getItem();
            // 获取path关键点信息
            xs.clear();
            ys.clear();
            types.clear();
            //if (!PathUtils.getPathKeyPtsInfo(path, xs, ys, types)) {
            if (!PathUtils.getPathAllPtsInfo(path, xs, ys, types)) {
                return null;
            }
            int n = xs.size();
            JsonArray data = new JsonArray();
            JsonObject serieDoc = new JsonObject();
            for (int j = 0; j < n; j++) {
                JsonArray pt = new JsonArray();
                x = xs.get(j);
                y = ys.get(j);
                Rectangle pBox = new Rectangle(x, y, 0, 0);
                Rectangle2D ptNew = chartTransform.createTransformedShape(pBox).getBounds2D();
                pt.add(ptNew.getCenterX());
                pt.add(ptNew.getCenterY());
                //pt.add(xs.get(j));
                //pt.add(ys.get(j));
                pt.add(types.get(j));
                data.add(pt);
            } // end for j
            if (pathItem.isFill()) {
                serieDoc.add("fill", data);
                int type = types.get(n - 1);
                if (type != PathIterator.SEG_CLOSE) {
                    for (int j = n - 1; j >= 0; j--) {
                        if (types.get(j) == PathIterator.SEG_MOVETO) {
                            JsonArray pt = data.get(j).getAsJsonArray();
                            JsonArray ptNew = new JsonArray();
                            ptNew.add(pt.get(0));
                            ptNew.add(pt.get(1));
                            ptNew.add(PathIterator.SEG_CLOSE);
                            data.add(ptNew);
                            break;
                        }
                    }
                }
            }
            else {
                serieDoc.add("stroke", data);
            }
            series.add(serieDoc);
        } // end for i

        // 写入上述path信息
        JsonObject path = new JsonObject();
        path.add("path", series);

        // 适当调整比例尺寸 使图片长宽都不超过 2048
        Rectangle2D cropbBox = chart.getDrawArea();
        double widthPt = cropbBox.getWidth();
        double heightPt = cropbBox.getHeight();
        double adaptScale = Math.min(2048 / widthPt, 2048 / heightPt);
        adaptScale = Math.min(adaptScale, SCALE);
        double xmin = cropbBox.getMinX();
        double ymin = cropbBox.getMinY();
        double width = cropbBox.getWidth();
        double height = cropbBox.getHeight();
        JsonObject chartCropInfo = new JsonObject();
        chartCropInfo.addProperty("xmin", xmin);
        chartCropInfo.addProperty("ymin", ymin);
        chartCropInfo.addProperty("width", width);
        chartCropInfo.addProperty("height", height);
        chartCropInfo.addProperty("scale", adaptScale);
        path.add("box", chartCropInfo);

        // 获取path关键点信息
        // 保存颜色
        ContentGroup group = groups.get(0);
        ContentItem item = group.getAllItems().get(0);
        PathItem pathItem = (PathItem) item;
        Color color = pathItem.getColor();
        JsonArray colorArray = new JsonArray();
        colorArray.add(color.getRed());
        colorArray.add(color.getGreen());
        colorArray.add(color.getBlue());
        path.add("color", colorArray);
        // 线宽
        path.addProperty("line_size", pathItem.getLineWidth());
        // 线类型 (有点问题)
        path.addProperty("line_pattern", pathItem.getLineDashPattern().getPhase());
        int dashArrayLength = pathItem.getLineDashPattern().getDashArray().length;
        path.addProperty("line_dash_array_length", dashArrayLength);
        // 设置验证状态
        path.addProperty("verify", 0);
        // 添加Chart内部path类型信息 后续可以通过GRPC方式调用图片分类服务 得到 (暂时简单处理)
        JsonArray pathTypes = getChartInnerPathTypes(chart);
        path.add("chart_type", pathTypes);
        // 先设置无效标签
        path.addProperty("label", -1);
        return path;
    }

    /**
     * 保存近似的contentgroup集
     * @param dir
     * @param chart
     * @param groups
     * @param id
     */
    private static void saveContentGroups(File dir, Chart chart, List<ContentGroup> groups, int id) {
        JsonObject path = getContentGroupPathJsonObject(chart, groups);
        if (path == null) {
            return;
        }

        // 判断类型
        ContentGroup group = groups.get(0);
        PathInfo.PathGroupType type = judgeContentGroupType(chart, group);
        path.addProperty("label", type.getValue());
        saveChartPathJson(dir, chart, path, 0, id, "path");
    }

    /**
     * 判断两个GeneralPath对象是否相同
     * @param pathA
     * @param pathB
     * @return
     */
    private static boolean isSamePath(GeneralPath pathA, GeneralPath pathB) {
        // 判断空间区域是否重叠
        Rectangle2D boxA = pathA.getBounds2D();
        Rectangle2D boxB = pathB.getBounds2D();
        GraphicsUtil.extendRect(boxA, 1.);
        GraphicsUtil.extendRect(boxB, 1.);
        double areaA = boxA.getHeight() * boxA.getWidth();
        double areaB = boxB.getHeight() * boxB.getWidth();
        if (Math.abs(areaA - areaB) > 1E-4 ||
                !GraphicsUtil.nearlyOverlap(boxA, boxB, 0.01)) {
            return false;
        }

        List<Double> xsA = new ArrayList<>();
        List<Double> ysA = new ArrayList<>();
        List<Integer> typesA = new ArrayList<>();
        if (!PathUtils.getPathKeyPtsInfo(pathA, xsA, ysA, typesA)) {
            return false;
        }
        List<Double> xsB = new ArrayList<>();
        List<Double> ysB = new ArrayList<>();
        List<Integer> typesB = new ArrayList<>();
        if (!PathUtils.getPathKeyPtsInfo(pathB, xsB, ysB, typesB)) {
            return false;
        }

        // 判断点集是否完全相同
        if (!isTwoPtsSame(xsA, ysA, xsB, ysB, 0.01) ||
                !isTwoPtsSame(xsB, ysB, xsA, ysA, 0.01)) {
            return false;
        }
        else {
            return true;
        }
    }

    /**
     * 判断给定两组点集在给定容差下是否相同
     * @param xsA
     * @param ysA
     * @param xsB
     * @param ysB
     * @param zero
     * @return
     */
    private static boolean isTwoPtsSame(
            List<Double> xsA, List<Double> ysA, List<Double> xsB, List<Double> ysB, double zero) {
        int nA = xsA.size();
        int nB = xsB.size();
        double x1 = 0, x2 = 0, y1 = 0, y2 = 0, dist = 0.0;
        for (int i = 0; i < nB; i++) {
            x1 = xsB.get(i);
            y1 = ysB.get(i);
            boolean findSamePt = false;
            for (int j = 0; j < nA; j++) {
                x2 = xsA.get(j);
                y2 = ysA.get(j);
                dist = (x1 - x2) * (x1 - x2) + (y1 - y2) * (y1 - y2);
                if (dist < zero) {
                    findSamePt = true;
                    break;
                }
            } // end for  j
            if (!findSamePt) {
                return false;
            }
        } // end for i
        return true;
    }

    /**
     * 判断给定两个ContentGroup是否完全重叠
     * @param groupA
     * @param groupB
     * @return
     */
    private static boolean isOverlapContentGroup(ContentGroup groupA, ContentGroup groupB) {
        ContentItem itemA = groupA.getAllItems().get(0);
        GeneralPath pathA = ((PathItem) itemA).getItem();
        ContentItem itemB = groupB.getAllItems().get(0);
        GeneralPath pathB = ((PathItem) itemB).getItem();
        // 判断路径是否完全一致
        if (isSamePath(pathA, pathB)) {
            return true;
        }
        else {
            return false;
        }
    }

    /**
     * 判断给定两个ContentGroup是否相似
     * @param groupA
     * @param groupB
     * @return
     */
    public static boolean isSimilarContentGroup(ContentGroup groupA, ContentGroup groupB) {
        // 判断PathItem是否相似
        ContentItem itemA = groupA.getAllItems().get(0);
        PathItem pathItemA = (PathItem) itemA;
        ContentItem itemB = groupB.getAllItems().get(0);
        PathItem pathItemB = (PathItem) itemB;
        if (!isSamePathItem(pathItemA, pathItemB)) {
            return false;
        }

        // 判断GrahpicsState是否相似
        PDGraphicsState stateA = groupA.getGraphicsState();
        PDGraphicsState stateB = groupB.getGraphicsState();
        if (!isSameGrahpicsState(stateA, stateB)) {
            return false;
        }
        return true;
    }

    /**
     * 判断给定两个PathItem是否相似
     * @param pathItemA
     * @param pathItemB
     * @return
     */
    private static boolean isSamePathItem(PathItem pathItemA, PathItem pathItemB) {
        if (pathItemA.isFill() != pathItemB.isFill()) {
            return false;
        }
        if (!pathItemA.getColor().equals(pathItemB.getColor())) {
            return false;
        }
        if (Math.abs(pathItemA.getLineWidth() - pathItemB.getLineWidth()) > 1E-2) {
            return false;
        }
        if (!pathItemA.getLineDashPattern().equals(pathItemB.getLineDashPattern())) {
            return false;
        }
        return true;
    }

    /**
     * 判断给定两GrahpicsState是否相似
     * @param stateA
     * @param stateB
     * @return
     */
    private static boolean isSameGrahpicsState(PDGraphicsState stateA, PDGraphicsState stateB) {
        try {
            PDColor scolorA = stateA.getStrokingColor();
            PDColor scolorB = stateB.getStrokingColor();
            PDColor nscolorA = stateA.getNonStrokingColor();
            PDColor nscolorB = stateB.getNonStrokingColor();
            if (scolorA == null || scolorB == null || nscolorA == null || nscolorB == null) {
                return false;
            }
            if (scolorA.getComponents().length == 0 ||
                    scolorB.getComponents().length == 0 ||
                    scolorA.toRGB() != scolorB.toRGB()) {
                return false;
            }
            if (nscolorA.getComponents().length == 0 ||
                    nscolorB.getComponents().length == 0 ||
                    nscolorA.toRGB() != nscolorB.toRGB()) {
                return false;
            }
            if (!stateA.getBlendMode().equals(stateB.getBlendMode())) {
                return false;
            }
            if (!stateA.getLineDashPattern().equals(stateB.getLineDashPattern())) {
                return false;
            }
            if (Math.abs(stateA.getLineWidth() - stateB.getLineWidth()) > 1E-2) {
                return false;
            }
        } catch (IOException e) {
            return false;
        }
        return true;
    }

    /**
     * 基于给定时间样式 适当简化给定时间戳
     * @param timestamp
     * @param pattern
     * @return
     */
    public static long simplifyTimestamp(long timestamp, String pattern) {
        Date data = new Date(timestamp);
        Calendar c = Calendar.getInstance();
        c.setTime(data);

        //获取年
        int year = c.get(Calendar.YEAR);
        //获取月份，0表示1月份
        int month = c.get(Calendar.MONTH);
        //int month = c.get(Calendar.MONTH) + 1;
        //获取当前天数
        int day = c.get(Calendar.DAY_OF_MONTH);
        //获取当前小时
        int hour = c.get(Calendar.HOUR_OF_DAY);
        //获取当前分钟
        int min = c.get(Calendar.MINUTE);
        //获取当前秒
        int sec = c.get(Calendar.SECOND);

        // 将毫秒置零
        c.clear();

        // 如果是小时 分钟模式 则直接返回
        boolean hasm = pattern.contains("m");
        if (hasm) {
            c.set(year, month, day, hour, min, sec);
            return timestamp;
        }

        // 如果没有天或月   则将小时 分钟 秒 置零 , 如果将天置零会引起很大误差
        boolean hasM = pattern.contains("M");
        boolean hasD = pattern.contains("d");
        if (!hasD && !hasM) {
            hour = 0;
            min = 0;
            sec = 0;
        }

        c.set(year, month, day, hour, min, sec);
        long timeNew = c.getTime().getTime();
        return timeNew;
    }

    /**
     * 判断点是否在多边形内部
     * @param xs
     * @param ys
     * @param x
     * @param y
     * @return
     */
    public static boolean pointInPoly(double[] xs, double[] ys, double x, double y) {
        int nvert = xs.length;
        boolean c = false;
        int j = nvert - 1;
        for (int i = 0; i < nvert; i++) {
            if ( ((ys[i]> y) != (ys[j]> y)) &&
                    (x < (xs[j]- xs[i]) * (y - ys[i]) / (ys[j]- ys[i]) + xs[i]) ) {
                c = !c;
            }
            j = i;
        } // end for i
        return c;
    }

    /**
     * 判断点是否在给定path内部
     * @param path
     * @param x
     * @param y
     * @return
     */
    public static boolean pointInPath(GeneralPath path, double x, double y) {
        List<Double> xs = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
        List<Integer> types = new ArrayList<>();
        if (!PathUtils.getPathKeyPtsInfo(path, xs, ys, types)) {
            return false;
        }
        int n = xs.size();
        double [] ptXs = new double[n];
        double [] ptYs = new double[n];
        for (int i = 0; i < n; i++) {
            ptXs[i] = xs.get(i);
            ptYs[i] = ys.get(i);
        } // end for i
        return pointInPoly(ptXs, ptYs, x, y);
    }

    /**
     * 将光滑弧线段按给定参数加密
     * @param p0
     * @param p1
     * @param p2
     * @param p3
     * @param n
     * @param pts
     * @return
     */
    public static boolean setCubicLinePts(
            Point2D p0, Point2D p1, Point2D p2, Point2D p3, int n, List<Point2D> pts) {
        pts.clear();
        if (n <= 1) {
            return false;
        }
        double dx = 1.0 / (n - 1);
        pts.add(p0);
        for (int i = 1; i < n; i++) {
            Point2D ptNew = setCubicLinePt(p0, p1, p2, p3, i * dx);
            pts.add(ptNew);
        }
        pts.add(p3);
        return true;
    }

    /**
     * 计算光滑弧线段上给定位置处的点
     * @param p0
     * @param p1
     * @param p2
     * @param p3
     * @param t
     * @return
     */
    public static Point2D setCubicLinePt(
            Point2D p0, Point2D p1, Point2D p2, Point2D p3, double t) {
        double x = Math.pow(1 - t, 3) * p0.getX()
                + 3 * t * Math.pow(1 - t, 2) * p1.getX()
                + 3 * Math.pow(t, 2) * (1 - t) * p2.getX()
                + Math.pow(t, 3) * p3.getX();
        double y = Math.pow(1 - t, 3) * p0.getY()
                + 3 * t * Math.pow(1 - t, 2) * p1.getY()
                + 3 * Math.pow(t, 2) * (1 - t) * p2.getY()
                + Math.pow(t, 3) * p3.getY();
        return new Point2D.Double(x, y);
    }

    /**
     * 加密path中的弧线段, 普通线段不加密
     * @param path
     * @param n
     * @return
     */
    public static GeneralPath refinePath(GeneralPath path, int n) {
        if (path == null || n <= 1) {
            return null;
        }

        PathIterator iter = path.getPathIterator(null);
        double[] coords = new double[12];
        Point2D p = new Point2D.Double(0, 0);
        Point2D p0 = new Point2D.Double(0, 0);
        Point2D p1 = new Point2D.Double(0, 0);
        Point2D p2 = new Point2D.Double(0, 0);
        Point2D p3 = new Point2D.Double(0, 0);
        GeneralPath pathNew = (GeneralPath)path.clone();
        pathNew.reset();
        List<Point2D> pts = new ArrayList<>();
        // 遍历　当前path
        while (!iter.isDone()) {
            int type = iter.currentSegment(coords);
            switch (type) {
                case PathIterator.SEG_MOVETO:
                    pathNew.moveTo(coords[0], coords[1]);
                    break;
                case PathIterator.SEG_LINETO:
                    pathNew.lineTo(coords[0], coords[1]);
                    break;
                // 加密曲线段
                case PathIterator.SEG_CUBICTO:
                    p0 = pathNew.getCurrentPoint();
                    p1.setLocation(coords[0], coords[1]);
                    p2.setLocation(coords[2], coords[3]);
                    p3.setLocation(coords[4], coords[5]);
                    if (setCubicLinePts(p0, p1, p2, p3, n, pts)) {
                        for (int i = 1; i < pts.size(); i++) {
                            p = pts.get(i);
                            pathNew.lineTo(p.getX(), p.getY());
                        }
                    }
                    break;
                // 加密曲线段
                case PathIterator.SEG_QUADTO:
                    p0 = pathNew.getCurrentPoint();
                    p1 = p0;
                    p2.setLocation(coords[0], coords[1]);
                    p3.setLocation(coords[2], coords[3]);
                    if (setCubicLinePts(p0, p1, p2, p3, n, pts)) {
                        for (int i = 1; i < pts.size(); i++) {
                            p = pts.get(i);
                            pathNew.lineTo(p.getX(), p.getY());
                        }
                    }
                    break;
                case PathIterator.SEG_CLOSE:
                    pathNew.closePath();
                default:
                    break;
            } // end switch
            iter.next();
        } // end while
        return pathNew;
    }

    /**
     * 判断给定Chart是否为折线图
     * @param chart
     * @return
     */
    public static boolean isLineChart(Chart chart) {
        if (chart == null) {
            return false;
        }
        if (chart.type == ChartType.LINE_CHART ||
                chart.type == ChartType.CURVE_CHART) {
            return true;
        }
        else {
            return false;
        }
    }

    /**
     * 判断给定Chart是否为柱状图
     * @param chart
     * @return
     */
    public static boolean isBarChart(Chart chart) {
        if (chart == null) {
            return false;
        }
        if (chart.type == ChartType.BAR_CHART ||
                chart.type == ChartType.COLUMN_CHART) {
            return true;
        }
        else {
            return false;
        }
    }
}
