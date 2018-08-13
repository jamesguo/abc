package com.abcft.pdfextract.core.util;

import com.abcft.pdfextract.core.chart.Chart;
import com.abcft.pdfextract.core.table.Table;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.jdom.Document;
import org.jdom.Element;
import org.jdom.output.Format;
import org.jdom.output.XMLOutputter;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Created by dhu on 2017/8/22.
 */
public class TrainDataUtil {

    private static Logger logger = LogManager.getLogger();

    public static class AnnotationObject {
        private String type;
        private Rectangle2D area; // 对象的区域, 坐标系为页面左下角为原点, 相对于原始的页面尺寸
        private Map<String, String> attributes = new HashMap<>();

        public AnnotationObject(String type, Rectangle2D area) {
            this.type = type;
            this.area = (Rectangle2D) area.clone();
        }

        public void addAttribute(String key, String value) {
            attributes.put(key, value);
        }

        public String getType() {
            return type;
        }

        public Rectangle2D getArea() {
            return area;
        }

        public void setArea(Rectangle2D area) {
            this.area = area;
        }

        public Map<String, String> getAttributes() {
            return attributes;
        }
    }

    private static class PageScaleInfo {
        int widthPx, heightPx;
        float scale;
        public PageScaleInfo() {
            widthPx = 0;
            heightPx = 0;
            scale = 0;
        }
    }

    private static Element buildAnnotationElement(AnnotationObject annotationObject, Rectangle2D area, int maxX, int maxY, int margin) {
        Element object = new Element("object");
        int xmin = (int)Math.floor(area.getMinX()) - margin;
        int ymin = (int)Math.floor(area.getMinY()) - margin;
        int xmax = (int)Math.floor(area.getMaxX()) + margin;
        int ymax = (int)Math.floor(area.getMaxY()) + margin;
        if (xmin < 0) xmin = 0;
        if (ymin < 0) ymin = 0;
        if (xmax > maxX) xmax = maxX;
        if (ymax > maxY) ymax = maxY;
        object.addContent(new Element("name").setText(annotationObject.getType()));
        object.addContent(new Element("pose").setText("Unspecified"));
        object.addContent(new Element("truncated").setText("0"));
        object.addContent(new Element("difficult").setText("0"));
        object.addContent(new Element("bndbox")
                .addContent(new Element("xmin").setText(String.valueOf(xmin)))
                .addContent(new Element("ymin").setText(String.valueOf(ymin)))
                .addContent(new Element("xmax").setText(String.valueOf(xmax)))
                .addContent(new Element("ymax").setText(String.valueOf(ymax)))
        );
        Element attributesElement = new Element("attributes");
        for (Map.Entry<String, String> attribute : annotationObject.getAttributes().entrySet()) {
            attributesElement.addContent(new Element(attribute.getKey()).setText(attribute.getValue()));
        }
        object.addContent(attributesElement);
        return object;
    }

    private static Document buildPageAnnotation(PDPage page, List<AnnotationObject> objects,
                                                String id, Map<String, String> attributes, PageScaleInfo scaleInfo) {
        String imageFile = id + ".jpg";

        Element annotation = new Element("annotation");
        Document doc = new Document(annotation);
        doc.setRootElement(annotation);
        annotation.addContent(new Element("filename").setText(imageFile));
        annotation.addContent(new Element("size")
                .addContent(new Element("width").setText(String.valueOf(scaleInfo.widthPx)))
                .addContent(new Element("height").setText(String.valueOf(scaleInfo.heightPx)))
                .addContent(new Element("depth").setText("3"))
        );
        annotation.addContent(new Element("segmented").setText("0"));

        Element attributesElement = new Element("attributes");
        for (Map.Entry<String, String> attribute : attributes.entrySet()) {
            attributesElement.addContent(new Element(attribute.getKey()).setText(attribute.getValue()));
        }
        annotation.addContent(attributesElement);

        PDRectangle pageSize = page.getCropBox();
        // 保持和页面绘制的时候一样的操作
        AffineTransform pageTransform = new AffineTransform();
        transform(pageTransform, page, scaleInfo.scale);
        pageTransform.translate(0, pageSize.getHeight());
        pageTransform.scale(1, -1);
        pageTransform.translate(-pageSize.getLowerLeftX(), -pageSize.getLowerLeftY());

        for (AnnotationObject object : objects) {
            // 计算page变换尺寸后的位置信息
            Rectangle2D area = pageTransform.createTransformedShape(object.getArea()).getBounds2D();
            Element element = buildAnnotationElement(object, area, scaleInfo.widthPx, scaleInfo.heightPx, 0);
            annotation.addContent(element);
        }
        return doc;
    }

    // scale rotate translate
    private static void transform(AffineTransform pageTransform, PDPage page, float scale) {
        pageTransform.scale(scale, scale);

        // TODO should we be passing the scale to PageDrawer rather than messing with Graphics?
        int rotationAngle = page.getRotation();
        PDRectangle cropBox = page.getCropBox();

        if (rotationAngle != 0)
        {
            float translateX = 0;
            float translateY = 0;
            switch (rotationAngle)
            {
                case 90:
                    translateX = cropBox.getHeight();
                    break;
                case 270:
                    translateY = cropBox.getWidth();
                    break;
                case 180:
                    translateX = cropBox.getWidth();
                    translateY = cropBox.getHeight();
                    break;
                default:
                    break;
            }
            pageTransform.translate(translateX, translateY);
            pageTransform.rotate((float) Math.toRadians(rotationAngle));
        }
    }

    private static boolean savePageAnnotation(PDPage page, List<AnnotationObject> objects,
                                              File saveDir, String id, Map<String, String> attributes) {
        File imageDir = new File(saveDir, "JPEGImages");
        File annotationDir = new File(saveDir, "Annotations");
        try {
            FileUtils.forceMkdir(imageDir);
            FileUtils.forceMkdir(annotationDir);
        } catch (IOException e) {
            logger.warn("forceMkdir failed", e);
            return false;
        }
        File imageFile = new File(imageDir, id + ".jpg");
        PageScaleInfo scaleInfo = computePageScale(page);
        if (!savePageImage(page, imageFile, scaleInfo)) {
            return false;
        }
        File annotationFile = new File(annotationDir, id + ".xml");
        Document document = buildPageAnnotation(page, objects, id, attributes, scaleInfo);
        return saveXml(document, annotationFile);
    }

    private static AnnotationObject createAnnotationObject(Chart chart) {
        AnnotationObject object = new AnnotationObject(chart.type.name(), chart.getArea());
        object.addAttribute("subTypes",
                String.join(", ", chart.subTypes.stream().map(Enum::name).collect(Collectors.toList())));
        return object;
    }

    private static AnnotationObject createAnnotationObject(PDPage page, Table table) {
        // table的坐标系是页面左上角是原点, 转化为页面的坐标, 左下角是原点
        Rectangle2D area = table.getBounds2D();
        double minX = area.getMinX();
        double maxX = area.getMaxX();
        double minY = page.getCropBox().getHeight() - area.getMaxY();
        double maxY = page.getCropBox().getHeight() - area.getMinY();
        area.setRect(minX, minY, maxX - minX, maxY - minY);
        return new AnnotationObject("TABLE", area);
    }

    public static boolean savePageAnnotation(PDPage page, int pageIndex, String pdfFile, List<Chart> charts,
                                             List<Table> tables, File saveDir, String id) {
        List<AnnotationObject> objects = new ArrayList<>();
        for (Chart chart : charts) {
            objects.add(createAnnotationObject(chart));
        }
        for (Table table : tables) {
            objects.add(createAnnotationObject(page, table));
        }
        if (objects.isEmpty()) {
            return false;
        }
        Map<String, String> attributes = new HashMap<>();
        attributes.put("pageIndex", String.valueOf(pageIndex));
        attributes.put("pdf", pdfFile);
        return savePageAnnotation(page, objects, saveDir, id, attributes);
    }

    public static boolean saveXml(Document document, File toFile) {
        try {
            XMLOutputter xmlOutput = new XMLOutputter();
            xmlOutput.setFormat(Format.getPrettyFormat());
            xmlOutput.output(document, new FileWriter(toFile));
            return true;
        } catch (IOException e) {
            logger.warn("saveXml failed", e);
            return false;
        }
    }

    private static PageScaleInfo computePageScale(PDPage page) {
        return computePageScale(page, 1.0f);
    }

    /**
     * 适当调整比例尺寸, 使页面图片长宽都不超过2048
     * @param page
     * @return
     */
    private static PageScaleInfo computePageScale(PDPage page, float scale) {
        PageScaleInfo sizeScaleInfo = new PageScaleInfo();
        double widthPt = page.getMediaBox().getWidth();
        double heightPt = page.getMediaBox().getHeight();
        double adaptScale = Math.min(2048 / widthPt, 2048 / heightPt);
        // 因为 Page 页面通常比较大 故考虑将其缩减
        adaptScale = Math.min(adaptScale, scale);
        sizeScaleInfo.widthPx = (int) Math.round(widthPt * adaptScale);
        sizeScaleInfo.heightPx = (int) Math.round(heightPt * adaptScale);
        sizeScaleInfo.scale = (float) adaptScale;
        return sizeScaleInfo;
    }

    public static boolean savePageImage(PDPage page, File file) {
        return savePageImage(page, file, 1.0f);
    }

    public static boolean savePageImage(PDPage page, File file, float scale) {
        PageScaleInfo scaleInfo = computePageScale(page, scale);
        return savePageImage(page, file, scaleInfo);
    }

    private static boolean savePageImage(PDPage page, File file, PageScaleInfo scaleInfo) {
        BufferedImage pageImage = null;
        try {
            pageImage = GraphicsUtil.getBufferedImageRGB(scaleInfo.widthPx, scaleInfo.heightPx);
            if (pageImage == null) {
                return false;
            }
            Graphics2D graphics = pageImage.createGraphics();
            graphics.setBackground(Color.WHITE);
            graphics.clearRect(0, 0, pageImage.getWidth(), pageImage.getHeight());
            PDFRenderer pageRenderer = new PDFRenderer(null);
            pageRenderer.renderPageToGraphics(page, graphics, scaleInfo.scale);
            graphics.dispose();
        } catch (Exception e) {
            logger.warn("render page failed", e);
            return false;
        }

        OutputStream output = null;
        try {
            output = new FileOutputStream(file);
            if (ImageIO.write(pageImage, "JPG", output)) {
                return true;
            } else {
                logger.warn("save page image failed");
            }
        } catch (Exception e) {
            logger.warn("save page image failed", e);
        } finally {
            if (output != null) {
                IOUtils.closeQuietly(output);
            }
        }
        return false;
    }

}
