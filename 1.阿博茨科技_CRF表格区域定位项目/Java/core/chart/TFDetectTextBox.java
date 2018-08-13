package com.abcft.pdfextract.core.chart;

import java.util.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.tensorflow.DataType;
import org.tensorflow.Tensor;

/**
 * Created by myyang on 17-7-17.
 */
public class TFDetectTextBox extends TensorflowMode {
    private static Logger logger = LogManager.getLogger(TFDetectTextBox.class);

    private static final TFDetectTextBox instance = new TFDetectTextBox();          // 饿汉单例模式的实例对象

    private TFDetectTextBox() { }

    /**
     * 单例模式返回 唯一的实例对象
     * @return
     */
    public static TFDetectTextBox getInstance() { return instance; }

    public <T> void predictImageTextBoxes(List<T> imageFiles) {
        logger.info("predict images's inner texts, axis and legend's boxes ");
        if (!isTFModelReady() || imageFiles.isEmpty()) {
            return;
        }
        for (T imageFile : imageFiles) {
            try (Tensor image = constructImage(imageFile, DataType.UINT8, true, false)) {
                if (image == null) {
                    continue;
                }
                executeTextBoxGraph(image);
            }
        } // end for imageFile
    }

    private void executeTextBoxGraph(Tensor image) {
        List<Tensor<?>> res = new ArrayList<>();
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
                if (scoresF[i] <= 0.5f) {
                    continue;
                }
                DetectedObjectTF tBox = new DetectedChartTableTF();
                tBox.box[0] = (int)(boxesF[i][1] * width);
                tBox.box[1] = (int)((1.0 - boxesF[i][2]) * height);
                tBox.box[2] = (int)(boxesF[i][3] * width);
                tBox.box[3] = (int)((1.0 - boxesF[i][0]) * height);
                int id = (int)classesF[i];
                tBox.label = labels.get(id);
                tBox.score = scoresF[i];
                tBox.output(); // 输出结果 调试用
                num++;
            } // end for i
            logger.info("detect " + num + " text box !!!");
        }
        catch (Exception e) {
            logger.info(e);
            return;
        }
        finally {
            res.stream().forEach(tensor -> tensor.close());
        }
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            return;
        }

        String modelDir = args[0];
        TFDetectTextBox model = TFDetectTextBox.getInstance();
        model.setTFModelEnv(modelDir, "output_inference_graph.pb", "chart_label_map.txt");
        if (!model.isTFModelReady()) {
            logger.error("Detect image text box env not ready");
            return;
        }

        ArrayList<String> imageFiles = new ArrayList<String>(Arrays.asList(args));
        imageFiles.remove(0);
        model.predictImageTextBoxes(imageFiles);

        model.resetTFModeEnv();
    }
}
