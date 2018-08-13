package com.abcft.pdfextract.core.chart;

import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.*;
import java.util.List;

import com.abcft.pdfextract.spi.ChartType;
import com.abcft.pdfextract.spi.algorithm.ImageClassifyResult;
import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.tensorflow.DataType;
import org.tensorflow.Tensor;

import javax.imageio.ImageIO;

/**
 * Created by myyang on 17-9-18.
 */
public class TFDetectChartTable extends TensorflowMode {
    private static Logger logger = LogManager.getLogger(TFDetectChartTable.class);

    private static final TFDetectChartTable instance = new TFDetectChartTable();          // 饿汉单例模式的实例对象
    static int id = 1;

    private TFDetectChartTable() {
    }

    /**
     * 单例模式返回 唯一的实例对象
     *
     * @return
     */
    public static TFDetectChartTable getInstance() {

        TFModelConfig tfConfig = TFModelConfig.getInstance();
        if (tfConfig.isDCTInfoConfigValid()) {
            instance.setTFModelEnv(tfConfig.modelDIr, tfConfig.dctiModelName, tfConfig.dctiLabelsName);
        }
        return instance;
    }

    /**
     * 检测给定图片对象内部所含Chart和Table信息集
     * @param imageFile
     * @param <T>
     * @return
     */
    public <T> List<DetectedObjectTF> predictImageInnerChartTable(T imageFile) {
        //logger.info("predict image's inner Chart and Table object");
        if (!isTFModelReady() || imageFile == null) {
            return null;
        }
        try (Tensor image = constructImage(imageFile, DataType.UINT8, true, false)) {
            if (image == null) {
                return null;
            }
            return executeDetectChartTableGraph(image);
        }
    }

    /**
     * 检测给定图片对象集内部所含Chart和Table信息集
     * @param imageFiles
     * @param <T>
     * @return
     */
    public <T> List<List<DetectedObjectTF>> predictImagesInnerChartTable(List<T> imageFiles) {
        //logger.info("predict images's inner Chart and Table object");
        if (!isTFModelReady() || imageFiles == null || imageFiles.isEmpty()) {
            return null;
        }
        List<List<DetectedObjectTF>> imagesResult = new ArrayList<>();
        for (T imageFile : imageFiles) {
            List<DetectedObjectTF> imageResult = predictImageInnerChartTable(imageFile);
            imagesResult.add(imageResult);
        } // end for imageFile

        // 保存图片　调试用
        save_detect_objs(imagesResult);

        return imagesResult;
    }

    /**
     * 检测给定PDF文档给定Page对象内部所含有的Chart和Table对象集
     * @param document
     * @param pageIndex
     * @return
     */
    public List<DetectedObjectTF> predictPageInnerChartTable(PDDocument document, int pageIndex) {
        if (!isTFModelReady() || document == null || pageIndex < 0) {
            return null;
        }
        PDFRenderer pdfRenderer = new PDFRenderer(document);
        try {
            PDPage page = document.getPage(pageIndex);
            float w = page.getMediaBox().getWidth();
            float h = page.getMediaBox().getHeight();
            float scale = 1.0f;
            // 将Page渲染成 BufferedImage 对象
            BufferedImage imageFile = pdfRenderer.renderImageWithDPI(pageIndex, 72.0f * scale, ImageType.RGB);
            // 本地调用TF模型 检测Chart和Table对象信息
            List<DetectedObjectTF> objects = predictImageInnerChartTable(imageFile);
            // 基于尺寸变化信息  计算检测出来的Chart和Table对象在Page中的位置信息
            if (objects != null) {
                for (DetectedObjectTF obj : objects) {
                    if (obj == null) {
                        continue;
                    }
                    // 从检测图片中抠图
                    obj.setCropSubImage(imageFile);
                    // 变换为基于Page尺寸的位图
                    for (int i = 0; i < 4; i++) {
                        obj.box[i] = (int) (obj.box[i] / scale);
                    }
                    obj.subImage = resizeImage(obj.subImage,obj.box[2] - obj.box[0], obj.box[3] - obj.box[1]);
                }
            }

            // 保存检测出的区域为图片　测试用
            //save_detect_list_objs(objects);
            return objects;
        }
        catch (IOException e) {
            return null;
        }
    }

    /**
     * 保存检测出的对象　保存到本地查看　调试用
     * @param objs
     */
    private void save_detect_list_objs(List<DetectedObjectTF> objs) {
        if (objs == null){
            return;
        }
        List<List<DetectedObjectTF>> objs_big = new ArrayList<>();
        objs_big.add(objs);
        save_detect_objs(objs_big);
    }

    public static void save_detect_obj_image(BufferedImage img, String info) {
        try {
            String formatName = "JPG";
            String path = "/tmp/chart_classify/" + info + ".jpg";
            OutputStream outputStream = new FileOutputStream(path);
            try {
                ImageIO.write(img, formatName, outputStream);
            }
            catch (IOException e) {
            }
            finally {
                IOUtils.closeQuietly(outputStream);
            }
        } catch (IOException e) {
        }
    }

    private void save_detect_objs(List<List<DetectedObjectTF>> objs) {
        if (objs == null || objs.isEmpty()) {
            return;
        }
        for (int i = 0; i < objs.size(); i++) {
            List<DetectedObjectTF> charts = objs.get(i);
            for (int j = 0; j < charts.size(); j++) {
                DetectedObjectTF chart = charts.get(j);
                BufferedImage img = ((DetectedChartTableTF)chart).subImage;
                if (img == null) {
                    continue;
                }
                save_detect_obj_image(img, "chart_" + id++);
            }
        } // end for i
    }

    private BufferedImage resizeImage(BufferedImage originalImage, int newW, int newH){
        BufferedImage resizedImage = new BufferedImage(newW, newH, originalImage.getType());
        Graphics2D g = resizedImage.createGraphics();
        g.drawImage(originalImage, 0, 0, newW, newH, null);
        g.dispose();
        return resizedImage;
    }

    /**
     * 本地执行TF模型 检测对象
     * @param image
     * @return
     */
    private List<DetectedObjectTF> executeDetectChartTableGraph(Tensor image) {
        List<Tensor<?>> res = new ArrayList<>();
        List<DetectedObjectTF> detectedObjects = new ArrayList<>();
        try {
            res = session.runner().feed("image_tensor:0", image).fetch("detection_boxes:0")
                    .fetch("detection_scores:0").fetch("detection_classes:0").run();
            final long[] rshape = res.get(1).shape();
            int nlabels = (int) rshape[1];
            float[][] boxesF = res.get(0).copyTo(new float[1][nlabels][4])[0];
            float[] scoresF = res.get(1).copyTo(new float[1][nlabels])[0];
            float[] classesF = res.get(2).copyTo(new float[1][nlabels])[0];
            int num = 0;
            for (int i = 0; i < scoresF.length; i++) {
                // 跳过置信度小于一定阀值的对象
                if (scoresF[i] <= 0.9f) {
                    continue;
                }
                DetectedChartTableTF tBox = new DetectedChartTableTF();
                tBox.box[0] = (int)(boxesF[i][1] * width);
                tBox.box[1] = (int)((1.0 - boxesF[i][2]) * height);
                tBox.box[2] = (int)(boxesF[i][3] * width);
                tBox.box[3] = (int)((1.0 - boxesF[i][0]) * height);
                tBox.extendDetectBox(width, height, 0.03);
                //extendDetectBox(tBox, 0.03);
                int id = (int)classesF[i];
                tBox.label = labels.get(id);
                tBox.score = scoresF[i];
                tBox.setTypeID();
                tBox.setArea();

                //tBox.output(); // 输出结果 调试用
                num++;
                detectedObjects.add(tBox);
            } // end for i
            //logger.info("detect " + num + " chart and table !!!");
        }
        catch (Exception e) {
            logger.info(e);
            return null;
        }
        finally {
            res.stream().forEach(tensor -> tensor.close());
        }
        return detectedObjects;
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            return;
        }

        String modelDir = args[0];
        TFDetectChartTable model = TFDetectChartTable.getInstance();
        model.setTFModelEnv(modelDir, "frozen_inference_graph.pb", "bitmap_label_map.txt");
        if (!model.isTFModelReady()) {
            logger.error("Detect image inner chart and table env not ready");
            return;
        }

        ArrayList<String> imageFiles = new ArrayList<String>(Arrays.asList(args));
        imageFiles.remove(0);
        // 测试检测单个图片
        //model.predictImageInnerChartTable(imageFiles.get(0));

        // 测试检测多个图片
        model.predictImagesInnerChartTable(imageFiles);

        model.resetTFModeEnv();
    }
}

class DetectedChartTableTF extends DetectedObjectTF {
    public Rectangle2D area = null;
    public List<ChartType> types = new ArrayList<>();
    public List<ImageClassifyResult> imageTypes = new ArrayList<>();
    public boolean byChartDetectModel = false;
    public boolean byHintArea = false;
    public boolean byRecallArea = false;

    @Override
    public void setTypeID() {
        // 目前采用只检测Chart区域的简单模型
        if (label.equals("chart")) {
            typeID = ChartType.INDERFINITE_CHART.getValue();
        }
        // 同时检测Chart和Table区域和类型的模型还不稳定
        else if (label.equals("LINE_CHART") || label.equals("LINE_POINT_CHART")) {
            typeID = ChartType.LINE_CHART.getValue();
        }
        else if (label.equals("CURVE_CHART")) {
            typeID = ChartType.CURVE_CHART.getValue();
        }
        else if (label.equals("AREA_CHART")) {
            typeID = ChartType.AREA_CHART.getValue();
        }
        else if (label.equals("AREA_OVERLAP_CHART")) {
            typeID = ChartType.AREA_OVERLAP_CHART.getValue();
        }
        else if (label.equals("COLUMN_CHART")) {
            typeID = ChartType.BAR_CHART.getValue();
        }
        else if (label.equals("BAR_CHART")) {
            typeID = ChartType.COLUMN_CHART.getValue();
        }
        else if (label.equals("PIE_CHART") || label.equals("DONUT_CHART")) {
            typeID = ChartType.PIE_CHART.getValue();
        }
        else if (label.equals("COMBO_CHART")) {
            typeID = ChartType.COMBO_CHART.getValue();
        }
        else if (label.equals("SCATTER_CHART")) {
            typeID = ChartType.SCATTER_CHART.getValue();
        }
        else if (label.equals("RADAR_CHART")) {
            typeID = ChartType.RADAR_CHART.getValue();
        }
        else if (label.equals("CANDLESTICK_CHART")) {
            typeID = ChartType.CANDLESTICK_CHART.getValue();
        }
        else if (label.equals("DISCRETE_PLOT")) {
            typeID = ChartType.DISCRETE_PLOT.getValue();
        }
        else {
            typeID = ChartType.UNKNOWN_CHART.getValue();
        }
    }

    /**
     * 调用位图Chart
     */
    public void chartClassify() {
        // 获取分类类别
        imageTypes = ChartClassify.getImageClassifyResult(subImage);
        // 转换为ChartType
        types = ChartClassify.imageClassifyToChartTypes(imageTypes);
    }

    public void saveChartTypeInfos() {
        long startTime = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%d", startTime));
        if (types == null || types.isEmpty()) {
            sb.append(".null");
        } else {
            for (ChartType chartType : types) {
                String type = chartType.toString();
                sb.append('.');
                sb.append(type);
            }
        }
        TFDetectChartTable.save_detect_obj_image(subImage, sb.toString());
    }

    /**
     * 判断是否为有效Chart对象
     * @return
     */
    public boolean isChart() {
        if (types != null && types.size() == 1) {
            if (types.get(0).equals(ChartType.UNKNOWN_CHART)) {
                return false;
            }
        }
        return true;
    }

    public void setArea() {
        if (box.length != 4) {
            return;
        }
        area = new Rectangle2D.Double(box[0], box[1], box[2] - box[0], box[3] - box[1]);
    }

    /**
     * 直接设置检测区域
     * @param area
     */
    public void setArea(Rectangle2D area) {
        label = "chart";
        this.area = (Rectangle2D) area.clone();
        box[0] = (int) area.getMinX();
        box[1] = (int) area.getMinY();
        box[2] = (int) area.getMaxX();
        box[3] = (int) area.getMaxY();
        typeID = ChartType.INDERFINITE_CHART.getValue();
        score = 1.0f;
    }

    /**
     * 基于给定标记区域集构建检测区域对象
     * @param areas
     * @param width
     * @param height
     * @return
     */
    public static List<DetectedObjectTF> buildByHintAreas(
            List<Rectangle2D> areas, double width, double height) {
        // 判断数据有效性
        if (areas == null || areas.isEmpty()) {
            return null;
        }
        // 设置最小尺寸
        double smallLen = 10.0;
        List<DetectedObjectTF> objectTFS = new ArrayList<>();
        for (Rectangle2D area : areas) {
            // 判断尺寸有效性
            if (area.getWidth() <= smallLen || area.getHeight() <= smallLen) {
                continue;
            }
            // 重置包围框
            double xmin = Math.max(area.getMinX(), 1);
            double xmax = Math.min(area.getMaxX(), width - 2);
            double ymin = Math.max(area.getMinY(), 1);
            double ymax = Math.min(area.getMaxY(), height - 2);
            area.setRect(xmin, ymin, xmax - xmin, ymax - ymin);
            DetectedChartTableTF detectedChartTableTF = new DetectedChartTableTF();
            detectedChartTableTF.setArea(area);
            detectedChartTableTF.byHintArea = true;
            objectTFS.add(detectedChartTableTF);
        } // end for area
        return objectTFS;
    }

    /**
     * 转换原点为左上角点　Y坐标需要变换一下
     * @param height
     */
    public void transferLeftUpOriginPoint(int height) {
        int ymin = height - box[3];
        int ymax = height - box[1];
        box[1] = ymin;
        box[3] = ymax;
        setArea();
    }

    /**
     * 学习模型检测出来的区域可能存在误差， 故参考给定参数， 适当扩展检测到的区域范围
     * @param width
     * @param height
     * @param coef
     */
    public void extendDetectBox(double width, double height, double coef) {
        double w = box[2] - box[0];
        double h = box[3] - box[1];
        int dx = (int)(w * coef);
        int dy = (int)(h * coef);
        box[0] = Math.max(0, box[0] - dx);
        box[1] = Math.max(0, box[1] - dy);
        box[2] = Math.min((int)width, box[2] + dx);
        box[3] = Math.min((int)height, box[3] + dy);
    }
}
