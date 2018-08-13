package com.abcft.pdfextract.core.chart;

import java.awt.image.BufferedImage;
import java.util.*;

import com.abcft.pdfextract.spi.OCRClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.tensorflow.DataType;
import org.tensorflow.Session;
import org.tensorflow.Tensor;

/**
 * Created by myyang on 17-7-17.
 */
public class TFDetectOCRText extends TensorflowMode implements OCRClient {
    private static Logger logger = LogManager.getLogger(TFDetectOCRText.class);

    private static final TFDetectOCRText instance = new TFDetectOCRText();          // 饿汉单例模式的实例对象

    private Map<Integer, String> imgTextMap = new HashMap<>();

    private TFDetectOCRText() { }

    /**
     * 单例模式返回 唯一的实例对象
     * @return
     */
    public static TFDetectOCRText getInstance() {
        TFModelConfig tfConfig = TFModelConfig.getInstance();
        if (tfConfig.isOCRConfigValid()) {
            instance.setTFModelEnv(tfConfig.modelDIr, tfConfig.ocrModelName, tfConfig.ocrLabelsName);
        }
        return instance;
    }

    @Override
    public List<String> ocr(List<BufferedImage> images) {
        if (images == null || images.isEmpty()) {
            return null;
        }
        if (!isTFModelReady()) {
            return null;
        }
        return predictImageOCRTexts(images);
    }

    public <T> List<String> predictImageOCRTexts(List<T> imageFiles) {
        //logger.info("predict images's inner texts ");
        imgTextMap.clear();
        if (!isTFModelReady() || imageFiles.isEmpty()) {
            return null;
        }

        List<String> imagesTexts = new ArrayList<>();
        List<Tensor> imgs = new ArrayList<>();
        List<Integer> ids = new ArrayList<>();
        try {
            for (int i = 0; i < imageFiles.size(); i++) {
                Tensor image = constructImage(imageFiles.get(i), DataType.FLOAT, true, true);
                if (image == null) {
                    continue;
                }
                imgs.add(image);
                ids.add(i);
            }
            setImagesBucketAndExecuteGraph(imgs, ids, 16);
            imgTextMap.values().stream().forEach(text -> imagesTexts.add(text));
        }
        finally {
            imgs.stream().forEach(img -> img.close());
        }
        return imagesTexts;
    }

    private void setImagesBucketAndExecuteGraph(List<Tensor> imgs, List<Integer> ids, int bucketMaxImage) {
        Map<Integer, ImagesBucket> buckets = new HashMap<>();
        for (int i = 0; i < imgs.size(); i++) {
            Tensor img = imgs.get(i);
            long[] shape = img.shape();
            int id = getBucketId(ImagesBucket.BucketSizes, (int)shape[3],2);
            ImagesBucket b = buckets.get(id);
            if (b == null) {
                b = new ImagesBucket();
                buckets.put(id, b);
            }
            b.id = id;
            b.add(img, ids.get(i));
            if (b.datas.size() >= bucketMaxImage) {
                executeTextOCRGraph(b);
                b.reset();
            }
        } // end for i
        // 遍历 处理各 Bucket
        for (ImagesBucket b : buckets.values()) {
            executeTextOCRGraph(b);
            b.reset();
        }
    }

    private void executeTextOCRGraph(ImagesBucket b) {
        if (b.isEmpty()) {
            return;
        }
        try (Tensor image = b.mergeData();
             Tensor zero_paddings = b.getZeroPaddingData()) {
            int encoderInputLen = ImagesBucket.BucketSizes[b.id][0];
            int decoderInputLen = ImagesBucket.BucketSizes[b.id][1];
            //Session.Runner runner = session.runner().feed("img_data:0", image).feed("zero_paddings:0", zero_paddings);
            Session.Runner runner = session.runner().feed("img_data:0", image);
            if (zero_paddings != null) {
                runner.feed("zero_paddings:0", zero_paddings);
            }
            for (int i = 0; i < decoderInputLen; i++) {
                String nameInput = "decoder" + i + ":0";
                String nameWeight = "weight" + i + ":0";
                runner.feed(nameInput, Tensor.create(b.getDecoder(i)));
                runner.feed(nameWeight, Tensor.create(b.getWeight(i)));
            }
            for (int i = 0; i < encoderInputLen; i++) {
                String name = "encoder_mask" + i + ":0";
                runner.feed(name, Tensor.create(b.getEncoderMask(i)));
            }
            String nameInput = "decoder" + decoderInputLen + ":0";
            runner.feed(nameInput, Tensor.create(b.getDecoder(decoderInputLen)));

            String resultName = "bucket_output_" + b.id + ":0";
            runTFSession(runner, resultName, b.imgsIDs);
        } // end try
    }

    private void runTFSession(Session.Runner runner, String resultName, List<Integer> imgsIDs) {
        try (Tensor<Long> res = runner.fetch(resultName).run().get(0).expect(Long.class)) {
            int nRes = (int)res.shape()[0];
            int nText = (int)res.shape()[1];
            if (nRes != imgsIDs.size()) {
                return;
            }
            //long[][] texts = res.copyTo(new long[1][nRes][nText])[0];
            long[][] texts = res.copyTo(new long[nRes][nText]);
            for (int iImage = 0; iImage < nRes; iImage++) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < nText; i++) {
                    int ith = (int)texts[iImage][i];
                    if (ith == 2) {
                        break;
                    }
                    sb.append(labels.get(ith - 3));
                }
                String str = sb.toString();
                imgTextMap.put(imgsIDs.get(iImage), str);
                //logger.info("text : " + str);
            } // end for iImage
        } // end try
    }

    private int getBucketId(int [][] buckets, int maxWidth, int maxLabelLen) {
        for (int i = 0; i < buckets.length; i++) {
            if (buckets[i][0] >= (maxWidth / 4 - 1) && buckets[i][1] >= maxLabelLen) {
                return i;
            }
        }
        return -1;
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            return;
        }

        String modelDir = args[0];
        TFDetectOCRText model = TFDetectOCRText.getInstance();

        model.setTFModelEnv(modelDir, "attention_ocr.pb", "attention_ocr_chars.txt");
        if (!model.isTFModelReady()) {
            logger.error("Detect image text box env not ready");
            return;
        }

        ArrayList<String> imageFiles = new ArrayList<String>(Arrays.asList(args));
        imageFiles.remove(0);
        model.predictImageOCRTexts(imageFiles);

        model.resetTFModeEnv();
    }
}

class ImagesBucket {
    public static int [][] BucketSizes = {{16, 32}, {40, 32}, {64, 32}, {88, 32}, {128, 32}};

    public int id = -1;
    public int n = 0;
    public int maxWidth = 0;
    public int realLen = 0;
    public int paddLen = 0;
    public List<ImageDataArray> datas = new ArrayList<>();
    public List<Integer> imgsIDs = new ArrayList<>();

    ImagesBucket() {}

    public void add(Tensor img, int imageID) {
        datas.add(new ImageDataArray(img));
        imgsIDs.add(imageID);
        setInnerDataLen();
    }

    private static class ImageDataArray {
        public int n1, n2, n3;
        public float [][][] data = null;
        ImageDataArray(Tensor<Float> img) {
            n1 = 1;
            long[] shape = img.shape();
            n2 = (int)shape[2];
            n3 = (int)shape[3];
            data = img.copyTo(new float[1][n1][n2][n3])[0];
        }
        public void expandWidth(int width) {
            if (width == n3) {
                return;
            }
            int n3old = n3;
            n3 = width;
            float [][][] dataNew = new float[n1][n2][n3];
            setData(dataNew, 255.0f);
            for (int i = 0; i < n1; i++) {
                for (int j = 0; j < n2; j++) {
                    for (int k = 0; k < n3old; k++) {
                        dataNew[i][j][k] = data[i][j][k];
                    }
                }
            }
            data = dataNew;
        }
        public void setData(float[][][] data, float value) {
            for (int i = 0; i < n1; i++) {
                for (int j = 0; j < n2; j++) {
                    for (int k = 0; k < n3; k++) {
                        data[i][j][k] = value;
                    }
                }
            }
        }
    }

    public boolean isEmpty() {
        return datas.isEmpty();
    }

    public Tensor mergeData() {
        datas.stream().forEach(data -> data.expandWidth(maxWidth));
        ImageDataArray img = datas.get(0);
        float [][][][] dataMerge = new float[n][img.n1][img.n2][img.n3];
        for (int ii = 0; ii < n; ii++) {
            ImageDataArray idata = datas.get(ii);
            for (int i = 0; i < idata.n1; i++) {
                for (int j = 0; j < idata.n2; j++) {
                    for (int k = 0; k < idata.n3; k++) {
                        dataMerge[ii][i][j][k] = idata.data[i][j][k];
                    }
                }
            }
        }
        return Tensor.create(dataMerge);
    }

    public void setInnerDataLen() {
        n = datas.size();
        datas.stream().forEach(data -> maxWidth = Math.max(maxWidth, data.n3));
        realLen = Math.max((int)(Math.floor(maxWidth / 4.0)) - 1, 0);
        paddLen = BucketSizes[id][0] - realLen;
    }

    public Tensor getZeroPaddingData() {
        if (paddLen == 0) {
            return null;
        }
        else {
            float [][][] zeroPaddingData = new float[n][paddLen][1024];
            return Tensor.create(zeroPaddingData);
        }
    }

    public float[][] getEncoderMask(int ith) {
        float value = ith < realLen ? 1.0f : 0.0f;
        float [][] encoderMask = new float[n][1];
        for (int i = 0; i < n; i++) {
            encoderMask[i][0] = value;
        }
        return encoderMask;
    }

    public int[] getDecoder(int ith) {
        int [] decoder = new int[n];
        int value = 0;
        if (ith == 0) {
            value = 1;
        }
        else if (ith == 1) {
            value = 2;
        }
        for (int i = 0; i < n; i++) {
            decoder[i] = value;
        }
        return decoder;
    }

    public float[] getWeight(int ith) {
        float [] weight = new float[n];
        float value = 0.0f;
        if (ith == 0) {
            value = 1.0f;
        }
        for (int i = 0; i < n; i++) {
            weight[i] = value;
        }
        return weight;
    }

    public void reset() {
        datas.clear();
        imgsIDs.clear();
        maxWidth = 0;
        id = -1;
    }
}
