package com.abcft.pdfextract.core.chart;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Created by myyang on 17-7-24.
 */
public class TFModelConfig {
    private static Logger logger = LogManager.getLogger(TFModelConfig.class);

    private static final TFModelConfig instance = new TFModelConfig();          // 饿汉单例模式的实例对象

    public String modelDIr;
    public boolean useDetectChartTypeModel = false;
    public boolean useOCRModel = false;
    public boolean useDetectChartTableInfoModel = false;

    final public String ocrModelName = "attention_ocr.pb";
    final public String ocrLabelsName = "attention_ocr_chars.txt";
    final public String dctModelName = "image_classify.pb";
    final public String dctLabelsName = "image_classify_labels.txt";
    //final public String dctiModelName = "image_chart_9.9.1237.pb";
    final public String dctiModelName = "frozen_inference_graph.pb";
    final public String dctiLabelsName = "bitmap_label_map.txt";

    private TFModelConfig() { }

    /**
     * 单例模式返回 唯一的实例对象
     * @return
     */
    public static TFModelConfig getInstance() { return instance; }

    public boolean isOCRConfigValid() {
        if (isModelDirValid() && useOCRModel) {
            // 如果使用 OCR TF Model 判断相应模型和标签文件是否存在
            File ocrModel = new File(modelDIr + "/" + ocrModelName);
            File ocrLabels = new File(modelDIr + "/" + ocrLabelsName);
            if (ocrModel.exists() && ocrLabels.exists()) {
                return true;
            }
        }
        return false;
    }

    public boolean isDCTConfigValid() {
        if (isModelDirValid() && useDetectChartTypeModel) {
            // 如果使用 OCR TF Model 判断相应模型和标签文件是否存在
            File dctModel = new File(modelDIr + "/" + dctModelName);
            File dctLabels = new File(modelDIr + "/" + dctLabelsName);
            if (dctModel.exists() && dctLabels.exists()) {
                return true;
            }
        }
        return false;
    }

    public boolean isDCTInfoConfigValid() {
        if (isModelDirValid() && useDetectChartTableInfoModel) {
            // 如果使用 Page 内部 Chart和Table 信息检测 TF Model  则判断相应模型和标签文件是否存在
            File dctiModel = new File(modelDIr + "/" + dctiModelName);
            File dctiLabels = new File(modelDIr + "/" + dctiLabelsName);
            if (dctiModel.exists() && dctiLabels.exists()) {
                return true;
            }
        }
        return false;
    }

    private boolean isModelDirValid() {
        // 判断模型文件目录是否存在
        if (StringUtils.isEmpty(modelDIr)) {
            return false;
        }
        File file = new File(modelDIr);
        if (!file.exists() || !file.isDirectory()) {
            return false;
        }
        else {
            return true;
        }
    }
}

/**
 * 对象检测学习模型检测出来的对象
 * 包括被检测出对象的 标签 包围框 和 得分等信息
 */
class DetectedObjectTF {
    private static Logger logger = LogManager.getLogger(DetectedObjectTF.class);

    public int typeID;                      // 对象类型
    public String label;                    // 对象标签名
    public int [] box;                      // 对象包围框
    public float score;                     // 对象的检测得分
    public BufferedImage subImage;          // 对象区域的剪切图像

    DetectedObjectTF() {
        typeID = -1;
        label = "none_of_the_above";
        box = new int[] {0, 0, 0, 0};
        score = 0.0f;
        subImage = null;
    }

    public void setTypeID() {}

    // 调试用
    void output() {
        logger.info("typeID : " + typeID);
        logger.info("box : " + box[0] + " " + box[1] + " " + box[2] + " " + box[3]);
        logger.info("label : " + label);
        logger.info("score : " + score);
    }

    /**
     * 在Image内部剪切出当前检测对象区域的子图
     * @param image
     */
    public void setCropSubImage(BufferedImage image) {
        setCropSubImage(image, 1.0f);
    }

    public void setCropSubImage(BufferedImage image, float scale) {
        if (image == null) {
            return;
        }
        int w = image.getWidth();
        int h = image.getHeight();
        int [] size = {(int)(box[0] * scale), (int)(box[1] * scale),
                (int)(box[2] * scale), (int)(box[3] * scale) };
        if (0 <= size[0] && size[0] < size[2] && size[2] <= w &&
                0 <= size[1] && size[1] < size[3] && size[3] <= h) {
            subImage = image.getSubimage(size[0], h - size[3], size[2] - size[0], size[3] - size[1]);
        }
    }
}
