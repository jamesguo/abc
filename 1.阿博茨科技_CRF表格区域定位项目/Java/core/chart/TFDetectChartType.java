package com.abcft.pdfextract.core.chart;

import java.io.File;
import java.io.FilenameFilter;
import java.util.*;

import com.abcft.pdfextract.spi.ChartType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.tensorflow.DataType;
import org.tensorflow.Tensor;

/**
 * Created by myyang on 17-7-17.
 */
public class TFDetectChartType extends TensorflowMode {
    private static Logger logger = LogManager.getLogger(TFDetectChartType.class);

    private static final TFDetectChartType instance = new TFDetectChartType();          // 饿汉单例模式的实例对象

    private TFDetectChartType() { }

    /**
     * 单例模式返回 唯一的实例对象
     * @return
     */
    public static TFDetectChartType getInstance() {
        TFModelConfig tfConfig = TFModelConfig.getInstance();
        if (tfConfig.isDCTConfigValid()) {
            instance.setTFModelEnv(tfConfig.modelDIr, tfConfig.dctModelName, tfConfig.dctLabelsName);
        }
        return instance;
    }

    /**
     * 用 Tensorflow 模型判断给定位图对象相对应的Chart类型信息
     * @param imageFile 位图绝对路径名称 或 BufferedImage
     * @param <T>
     * @return
     */
    public <T> Map<ChartType, Float> predictImageChartType(T imageFile) {
        return predictImageChartType(imageFile, 0.65f);
    }

    /**
     * 用 Tensorflow 模型判断给定位图对象相对应的Chart类型信息
     * @param imageFile 位图绝对路径名称 或 BufferedImage
     * @param probThreshold 概率阀值 在区间(0, 1) 不宜太小太大
     * @param <T>
     * @return
     */
    public <T> Map<ChartType, Float> predictImageChartType(T imageFile, float probThreshold) {
        if (imageFile == null) {
            return null;
        }
        List<T> imageFiles = new ArrayList<>();
        imageFiles.add(imageFile);
        List<Map<ChartType, Float>> result = predictImageChartType(imageFiles, probThreshold);
        if (result.isEmpty()) {
            return null;
        }
        else {
            return result.get(0);
        }
    }

    /**
     * 用 Tensorflow 模型判断给定位图对象集相对应的Chart类型集
     * 由于目前采用的深度学习模型不能将Chart类型辨别太细
     * 折线和曲线都解析为折线类型  面积图和堆叠面积图都解析为面积类型
     * @param imageFiles 位图绝对路径名称 或 BufferedImage 集
     * @param <T>
     * @return
     */
    public <T> List<Map<ChartType, Float>> predictImageChartType(List<T> imageFiles) {
        return predictImageChartType(imageFiles, 0.65f);
    }

    /**
     * @param imageFiles 位图绝对路径名称 或 BufferedImage 集
     * @param probThreshold 概率阀值 在区间(0, 1) 不宜太小太大
     * @param <T>
     * @return
     */
    public <T> List<Map<ChartType, Float>> predictImageChartType(
            List<T> imageFiles, float probThreshold) {
        if (!isTFModelReady() || imageFiles == null || imageFiles.isEmpty()) {
            return null;
        }
        if (probThreshold < 0.0f || probThreshold > 1.0f) {
            logger.info("predict images Chart type probability threshold invalid");
            return null;
        }
        List<Map<ChartType, Float>> imagesTypes = new ArrayList<>();
        for (T imageFile : imageFiles) {
            try (Tensor image = constructImage(imageFile, DataType.FLOAT, false, false)) {
                if (image == null) {
                    imagesTypes.add(null);
                    continue;
                }
                float[] labelProbabilities = executeInceptionGraph(image);
                if (labelProbabilities == null) {
                    imagesTypes.add(null);
                    continue;
                }
                Map<ChartType, Float> typeMap = getBestLabel(labelProbabilities, probThreshold);
                imagesTypes.add(typeMap);
                //logger.info("Chart type : " + imageLabel);
            }
        } // end for imageFile
        return imagesTypes;
    }

    private ChartType tfPredictLabelToChartType(int labelId) {
        String res = labels.get(labelId);
        if (res.equals("LINE_CHART")) {
            return ChartType.LINE_CHART;
        }
        else if (res.equals("AREA_CHART")) {
            return ChartType.AREA_CHART;
        }
        else if (res.equals("BAR_CHART")) {
            return ChartType.BAR_CHART;
        }
        else if (res.equals("COLUMN_CHART")) {
            return ChartType.COLUMN_CHART;
        }
        else if (res.equals("PIE_CHART")) {
            return ChartType.PIE_CHART;
        }
        else {
            return ChartType.UNKNOWN_CHART;
        }
    }

    private Map<ChartType, Float> getBestLabel(float [] labelProbabilities, float probThreshold) {
        // 遍历找出概率大于 给定概率阀值 的标称
        Map<ChartType, Float> labelMap = new HashMap<>();
        for (int i = 0; i < labelProbabilities.length; i++) {
            float prob = labelProbabilities[i];
            if (prob > probThreshold) {
                ChartType label_type = tfPredictLabelToChartType(i);
                labelMap.put(label_type, prob);
            }
        }
        return labelMap;
    }

    /**
     * 将图片解码后的Tensor对象 代入模型 预测结果
     * @param image
     * @return
     */
    private float[] executeInceptionGraph(Tensor image) {
        try (Tensor<Float> result = session.runner().feed("Cast", image).fetch("final_result").run().get(0).expect(Float.class)) {
            final long[] rshape = result.shape();
            if (result.numDimensions() != 2 || rshape[0] != 1) {
                logger.error("Expected model to produce a [1 N] shaped tensor");
                return null;
            }
            int nlabels = (int) rshape[1];
            return result.copyTo(new float[1][nlabels])[0];
        }
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            return;
        }

        // 读取模型文件和标称数据文件
        String modelDir = args[0];
        TFDetectChartType model = TFDetectChartType.getInstance();
        model.setTFModelEnv(modelDir, "image_classify.pb", "image_classify_labels.txt");
        if (!model.isTFModelReady()) {
            logger.error("Detect image chart type env not ready");
            return;
        }

        // 测试给定目录下所有 jpg 和 png 图片的Chart类型
        File imageDir = new File(args[1]);
        if (imageDir == null || !imageDir.isDirectory()) {
            return;
        }
        FilenameFilter RESULT_FILTER = (dir, name) -> name.endsWith(".jpg") || name.endsWith(".png");
        File[] files = imageDir.listFiles(RESULT_FILTER);
        List<String> imageFiles = new ArrayList<>();
        Arrays.stream(files).forEach(f -> imageFiles.add(f.getAbsolutePath()));
        if (imageFiles.isEmpty()) {
            return;
        }

        //List<String> imageFiles = new ArrayList<String>(Arrays.asList(args));
        //imageFiles.remove(0);
        List<Map<ChartType, Float>> typesMap = model.predictImageChartType(imageFiles);
        int n = imageFiles.size();
        if (n != typesMap.size()) {
            logger.info("tf model predict image chart type error");
            return;
        }
        for (int i = 0; i < n; i++) {
            Map<ChartType, Float> map = typesMap.get(i);
            logger.info(imageFiles.get(i), " tf model predict result ");
            outPredictResult(map);
        }

        // 测试单个位图类型检测
        int ith = 0;
        Map<ChartType, Float> res = model.predictImageChartType(imageFiles.get(ith));
        logger.info(imageFiles.get(ith), " tf model predict result ");
        outPredictResult(res);

        // 释放相关资源
        model.resetTFModeEnv();
    }

    private static void outPredictResult(Map<ChartType, Float> map) {
        if (map == null) {
            logger.info("UNKNOW_CHART");
        }
        for (Map.Entry<ChartType, Float> entry : map.entrySet()) {
            logger.info("Type = " + entry.getKey() + ", Prob Value = " + entry.getValue());
        }
    }
}
