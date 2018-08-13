package com.abcft.pdfextract.core.table;

import com.abcft.pdfextract.core.model.Rectangle;
import com.abcft.pdfextract.core.model.TensorflowManager;
import com.abcft.pdfextract.core.table.extractors.BitmapPageExtractionAlgorithm;
import com.abcft.pdfextract.core.util.DebugHelper;
import com.google.protobuf.ByteString;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.tensorflow.*;
import org.tensorflow.types.UInt8;

import java.awt.image.BufferedImage;
import java.awt.image.RasterFormatException;
import java.io.File;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.util.List;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.Path;

import java.nio.charset.Charset;
import java.util.Arrays;

public class TableClassify {
    private static Logger logger = LogManager.getLogger();
    private static final int TABLE_CLASSIFY_DPI = 72;
    private static final int IMAGE_WIDTH = 224;
    private static final int IMAGE_HEIGHT = 224;
    private static final String INPUT_TENSOR_NAME = "input";
    private static final String OUTPUT_TENSOR_NAME = "MobilenetV2/Predictions/Reshape_1";

    private static final TensorflowManager.TensorflowConfig config = new TensorflowManager.TensorflowConfig();

    /**
     * 表格分类类型
     */
    public static class TableClassifyType {
        public static final int OTHER = 0;//表格类型
        public static final int TABLE = 1;//其他除表格以为的类型

        public static String desc(int imageType) {
            switch (imageType) {
                case TABLE:
                    return "TABLE";
                case OTHER:
                    return "OTHER";
                default:
                    return "TABLE";
            }
        }

        public static boolean imageTypeIsTable(int imageType) {
            switch (imageType) {
                case TABLE:
                    return true;
                default:
                    return false;
            }
        }
    }

    private static Tensor<Float> constructAndExecuteGraphToNormalizeImage(byte[] imageBytes) {
        try (Graph g = new Graph()) {
            GraphBuilder b = new GraphBuilder(g);
            // Some constants specific to the pre-trained model at:
            // https://storage.googleapis.com/download.tensorflow.org/models/inception5h.zip
            //
            // - The model was trained with images scaled to 224x224 pixels.
            // - The colors, represented as R, G, B in 1-byte each were converted to
            //   float using mul * (value / Scale - Mean).
            final int H = IMAGE_HEIGHT;
            final int W = IMAGE_WIDTH;
            final float mean = 0.5f;
            final float scale = 255f;
            final float mul = 2.0f;

            // Since the graph is being constructed once per execution here, we can use a constant for the
            // input image. If the graph were to be re-used for multiple input images, a placeholder would
            // have been more appropriate.
            final Output<String> input = b.constant("input", imageBytes);
            final Output<Float> output =
                    b.mul(
                            b.sub(
                                    b.div(
                                            b.resizeBilinear(
                                                    b.expandDims(
                                                            b.cast(b.decodeJpeg(input, 3), Float.class),
                                                            b.constant("make_batch", 0)),
                                                    b.constant("size", new int[] {H, W})),
                                            b.constant("scale", scale)),
                                    b.constant("mean", mean)),
                            b.constant("mul", mul));

            try (Session s = new Session(g)) {
                // Generally, there may be multiple output tensors, all of them must be closed to prevent resource leaks.
                return s.runner().fetch(output.op().name()).run().get(0).expect(Float.class);
            }
        }
    }

    private static float[] executeInceptionGraph(byte[] graphDef, Tensor<Float> image) {
        try (Graph g = new Graph()) {
            g.importGraphDef(graphDef);
            try (Session s = new Session(g);
                 // Generally, there may be multiple output tensors, all of them must be closed to prevent resource leaks.
                 Tensor<Float> result = s.runner().feed(INPUT_TENSOR_NAME, image).fetch(OUTPUT_TENSOR_NAME)
                         .run().get(0).expect(Float.class)) {
                final long[] rshape = result.shape();
                if (result.numDimensions() != 2 || rshape[0] != 1) {
                    throw new RuntimeException(
                            String.format(
                                    "Expected model to produce a [1 N] shaped tensor where N is the number of labels, instead it produced one with shape %s",
                                    Arrays.toString(rshape)));
                }
                int nlabels = (int) rshape[1];
                return result.copyTo(new float[1][nlabels])[0];
            }
        }
    }

    private static int maxIndex(float[] probabilities) {
        int best = 0;
        for (int i = 1; i < probabilities.length; ++i) {
            if (probabilities[i] > probabilities[best]) {
                best = i;
            }
        }
        return best;
    }

    private static byte[] readAllBytesOrExit(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            logger.info("Failed to read [" + path + "]: " + e.getMessage());
        }
        return null;
    }

    private static List<String> readAllLinesOrExit(Path path) {
        try {
            return Files.readAllLines(path, Charset.forName("UTF-8"));
        } catch (IOException e) {
            System.err.println("Failed to read [" + path + "]: " + e.getMessage());
            System.exit(0);
        }
        return null;
    }

    // In the fullness of time, equivalents of the methods of this class should be auto-generated from
    // the OpDefs linked into libtensorflow_jni.so. That would match what is done in other languages
    // like Python, C++ and Go.
    static class GraphBuilder {
        GraphBuilder(Graph g) {
            this.g = g;
        }

        Output<Float> mul(Output<Float> x, Output<Float> y) {
            return binaryOp("Mul", x, y);
        }

        Output<Float> div(Output<Float> x, Output<Float> y) {
            return binaryOp("Div", x, y);
        }

        <T> Output<T> sub(Output<T> x, Output<T> y) {
            return binaryOp("Sub", x, y);
        }

        <T> Output<Float> resizeBilinear(Output<T> images, Output<Integer> size) {
            return binaryOp3("ResizeBilinear", images, size);
        }

        <T> Output<T> expandDims(Output<T> input, Output<Integer> dim) {
            return binaryOp3("ExpandDims", input, dim);
        }

        <T, U> Output<U> cast(Output<T> value, Class<U> type) {
            DataType dtype = DataType.fromClass(type);
            return g.opBuilder("Cast", "Cast")
                    .addInput(value)
                    .setAttr("DstT", dtype)
                    .build()
                    .<U>output(0);
        }

        Output<UInt8> decodeJpeg(Output<String> contents, long channels) {
            return g.opBuilder("DecodeJpeg", "DecodeJpeg")
                    .addInput(contents)
                    .setAttr("channels", channels)
                    .build()
                    .<UInt8>output(0);
        }

        <T> Output<T> constant(String name, Object value, Class<T> type) {
            try (Tensor<T> t = Tensor.<T>create(value, type)) {
                return g.opBuilder("Const", name)
                        .setAttr("dtype", DataType.fromClass(type))
                        .setAttr("value", t)
                        .build()
                        .<T>output(0);
            }
        }
        Output<String> constant(String name, byte[] value) {
            return this.constant(name, value, String.class);
        }

        Output<Integer> constant(String name, int value) {
            return this.constant(name, value, Integer.class);
        }

        Output<Integer> constant(String name, int[] value) {
            return this.constant(name, value, Integer.class);
        }

        Output<Float> constant(String name, float value) {
            return this.constant(name, value, Float.class);
        }

        private <T> Output<T> binaryOp(String type, Output<T> in1, Output<T> in2) {
            return g.opBuilder(type, type).addInput(in1).addInput(in2).build().<T>output(0);
        }

        private <T, U, V> Output<T> binaryOp3(String type, Output<U> in1, Output<V> in2) {
            return g.opBuilder(type, type).addInput(in1).addInput(in2).build().<T>output(0);
        }
        private Graph g;
    }


    private static ByteString getByteStringImage(BufferedImage pageImage) {
        if (pageImage == null) {
            return null;
        }

        ByteString imageData;
        try {
            imageData = BitmapPageExtractionAlgorithm.getFormatedImageData(pageImage);
        } catch (IOException e) {
            return null;
        }
        if (imageData == null) {
            return null;
        }

        return imageData;
    }

    private static byte[] getByteData(BufferedImage pageImage) {
        if (pageImage == null) {
            return null;
        }

        ByteString imageData;
        try {
            imageData = BitmapPageExtractionAlgorithm.getFormatedImageData(pageImage);
        } catch (IOException e) {
            return null;
        }
        if (imageData == null) {
            return null;
        }

        return imageData.toByteArray();
    }

    private static BufferedImage getPageBufferedImage(Page page, int dpi, boolean useCache) {
        BufferedImage pageImage;
        float scale = (float)dpi / (float)DebugHelper.DEFAULT_DPI;
        try {
            if (useCache) {
                // Use one-shot page to save memory, since we might not use it anymore.
                pageImage = TableUtils.getOneShotPageImage(page, dpi);
            } else {
                pageImage = DebugHelper.pageConvertToImage(page.getPDPage(), dpi, org.apache.pdfbox.rendering.ImageType.RGB);
            }
            if (null == pageImage) {
                return null;
            }
        } catch (RasterFormatException | IOException e) {
            logger.warn("Failed to generate page image ", e);
            return null;
        }
        return pageImage;
    }

    private static BufferedImage getSubBufferedImage(BufferedImage pageImage, int dpi, Rectangle rect, boolean toScale, int toWidth, int toHeight) {
        float scale = (float)dpi / (float)DebugHelper.DEFAULT_DPI;
        if (null == pageImage) {
            return null;
        }
        int x = (int)(scale * rect.x);
        int y = (int)(scale * rect.y);
        int w = (int)(scale * rect.width);
        int h = (int)(scale * rect.height);

        boolean isInRegion = 0 <= x && x + w <= pageImage.getWidth() && 0 <= y && y + h <= pageImage.getHeight();
        if (!isInRegion) {
            logger.warn(String.format("Table area out of the page: Table{x=%d, y=%d, w=%d, h=%d} vs Page{w=%d, h=%d}"
                    , x, y, w, h, pageImage.getWidth(), pageImage.getHeight()));
            x = Math.max(0, Math.min(x, pageImage.getWidth() - 1));
            y = Math.max(0, Math.min(y, pageImage.getHeight() - 1));
            w = Math.max(0, Math.min(w, pageImage.getWidth() - 1 - x));
            h = Math.max(0, Math.min(h, pageImage.getHeight() - 1 - y));
        }

        BufferedImage tableImage;
        try {
            BufferedImage subImage = pageImage.getSubimage(x, y, w, h);
            if (!toScale) {
                return subImage;
            }
            tableImage = new BufferedImage(toWidth, toHeight, pageImage.getType());
            tableImage.getGraphics().drawImage(subImage.getScaledInstance(toWidth, toHeight, BufferedImage.SCALE_DEFAULT)
                    , 0, 0, null);
        } catch (Exception e) {
            logger.info("scale image fail");
            return null;
        }

        return tableImage;
    }

    public static float[] preProcessImageData(byte[] bytes) {
        float[] result = new float[3 * bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            byte rgb = bytes[i];
            int r = (rgb & 0xFF0000) >> 16;
            int g = (rgb & 0xFF00) >> 8;
            int b = rgb & 0xFF;
            //result[i] = 2.0f * ((float) bytes[i] / 255.0f - 0.5f);
            result[3 * i] = 2.0f * (r / 255.0f - 0.5f);
            result[3 * i + 1] = 2.0f * (g / 255.0f - 0.5f);
            result[3 * i + 2] = 2.0f * (b / 255.0f - 0.5f);
        }
        return result;
    }

    public static void classifyTables(Page page, List<Table> tables) {
        if (page == null || tables == null || tables.isEmpty()) {
            return;
        }

        logger.info("当前表格分类的文件:{}, 页数:{}",page.getParams().path, page.getPageNumber());

        //载入模型
        File exportDir = new File(config.getModelDirectory(), TensorflowManager.TABLE_CLASSIFY);
        if (!exportDir.exists()) {
            return;
        }

        byte[] graphDef = readAllBytesOrExit(Paths.get(exportDir.getPath(), "saved_model.pb"));
        if (graphDef == null) {
            return;
        }

        BufferedImage pageImage = getPageBufferedImage(page, TABLE_CLASSIFY_DPI, true);
        if (pageImage == null) {
            logger.warn("can't getOneShotPageImage");
            return;
        }

        for (Table table : tables) {
            BufferedImage subImage = getSubBufferedImage(pageImage, TABLE_CLASSIFY_DPI, table, false, IMAGE_WIDTH, IMAGE_HEIGHT);
            byte[] imageBytes = getByteData(subImage);
            if (imageBytes == null) {
                logger.warn("Failed to get image data for page");
                return;
            }

            try (Tensor<Float> image = constructAndExecuteGraphToNormalizeImage(imageBytes)) {
                float[] labelProbabilities = executeInceptionGraph(graphDef, image);
                if (labelProbabilities.length != 2) {
                    logger.info("the labelProbabilities num is not true");
                    return;
                }
                float score = labelProbabilities[TableClassifyType.TABLE];
                table.setClassifyScore(score);
                if (score > 0.6) {
                    table.updateConfidence(Table.HIGH_CONFIDENCE_THRESHOLD, 1.0);
                } else {
                    if (score > 0.4) {
                        table.updateConfidence(Table.LOW_CONFIDENCE_THRESHOLD, Table.HIGH_CONFIDENCE_THRESHOLD);
                    } else {
                        table.updateConfidence(0.0, Table.LOW_CONFIDENCE_THRESHOLD);
                    }
                }

                String type = TableClassifyType.desc(score > 0.5 ? 0 : 1);
                logger.info("TableId:{}, Table image classified as type: {}, the classify score is: {}", table.getIndex(), type, score);
            }
        }
    }

    public static void classifyTablesBatchTest(Page page, List<Table> tables) {
        if (page == null || tables == null || tables.isEmpty()) {
            return;
        }

        logger.info("当前表格分类的文件:{}, 页数:{}",page.getParams().path, page.getPageNumber());

        //载入模型
        SavedModelBundle savedModelBundle = TensorflowManager.INSTANCE.getSavedModelBundle(TensorflowManager.TABLE_CLASSIFY);
        if (savedModelBundle == null) {
            logger.info("没有表格分类模型文件");
            return;
        }

        //将图像转为tensor batch
        Session session = savedModelBundle.session();

        BufferedImage pageImage = getPageBufferedImage(page, TABLE_CLASSIFY_DPI, true);
        if (pageImage == null) {
            logger.warn("can't getOneShotPageImage");
            return;
        }

        int batchSize = 16;
        for (int i = 0; i < tables.size(); i += batchSize) {
            int batch = Math.min(batchSize, tables.size() - i);
            FloatBuffer floatBuffer = FloatBuffer.allocate(batch * 3 * IMAGE_WIDTH * IMAGE_HEIGHT);
            for (int j = 0; j < batch; j++) {
                BufferedImage subImage = getSubBufferedImage(pageImage, TABLE_CLASSIFY_DPI, tables.get(i + j), true, IMAGE_WIDTH, IMAGE_HEIGHT);
                byte[] bytes = getByteData(subImage);

                if (bytes == null) {
                    logger.warn("Failed to get image data for page");
                    return;
                }
                float[] result = preProcessImageData(bytes);
                floatBuffer.position(j * IMAGE_WIDTH * IMAGE_HEIGHT * 3);
                floatBuffer.put(result);
            }

            floatBuffer.rewind();

            Tensor<Float> inputTensor = Tensor.create(new long[]{batch, IMAGE_WIDTH, IMAGE_HEIGHT, 3}, floatBuffer);

            List<Tensor<?>> result = session.runner().feed(INPUT_TENSOR_NAME, inputTensor)
                    .fetch(OUTPUT_TENSOR_NAME).run();

            float[][] predictions = result.get(0).copyTo(new float[batch][2]);
            for (int j = 0; j < batch; j++) {
                try {
                    float[] labelProbabilities = new float[2];
                    for (int k = 0; k < 2; k++) {
                        labelProbabilities[k] = predictions[j][k];
                    }
                    if (labelProbabilities.length != 2) {
                        logger.info("the labelProbabilities num is not true");
                        return;
                    }
                    float tableScore = labelProbabilities[TableClassifyType.TABLE];
                    Table table = tables.get(i + j);
                    table.setClassifyScore(tableScore);
                    if (tableScore > 0.6) {
                        table.updateConfidence(Table.HIGH_CONFIDENCE_THRESHOLD, 1.0);
                    } else {
                        if (tableScore > 0.4) {
                            table.updateConfidence(Table.LOW_CONFIDENCE_THRESHOLD, Table.HIGH_CONFIDENCE_THRESHOLD);
                        } else {
                            table.updateConfidence(0.0, Table.LOW_CONFIDENCE_THRESHOLD);
                        }
                    }

                    String type = TableClassifyType.desc(tableScore > 0.5 ? TableClassifyType.TABLE : TableClassifyType.OTHER);
                    logger.info("TableId:{}, Table image classified as type: {}, the classify score is: {}", table.getIndex(), type, tableScore);
                } catch (Exception e) {
                    logger.warn("Failed to classify table");
                }
            }
        }
    }

    public static void classifyTablesBatch(Page page, List<Table> tables) {
        if (page == null || tables == null || tables.isEmpty()) {
            return;
        }

        logger.info("当前表格分类的文件:{}, 页数:{}",page.getParams().path, page.getPageNumber());

        //载入模型
        SavedModelBundle savedModelBundle = TensorflowManager.INSTANCE.getSavedModelBundle(TensorflowManager.TABLE_CLASSIFY);
        if (savedModelBundle == null) {
            logger.info("没有表格分类模型文件");
            return;
        }

        //将图像转为tensor batch
        Session session = savedModelBundle.session();

        BufferedImage pageImage = getPageBufferedImage(page, TABLE_CLASSIFY_DPI, true);
        if (pageImage == null) {
            logger.warn("can't getOneShotPageImage");
            return;
        }

        int batchSize = 16;
        for (int i = 0; i < tables.size(); i += batchSize) {
            int batch = Math.min(batchSize, tables.size() - i);
            FloatBuffer floatBuffer = FloatBuffer.allocate(batch * 3 * IMAGE_WIDTH * IMAGE_HEIGHT);
            for (int j = 0; j < batch; j++) {
                BufferedImage subImage = getSubBufferedImage(pageImage, TABLE_CLASSIFY_DPI, tables.get(i + j), false, IMAGE_WIDTH, IMAGE_HEIGHT);
                byte[] bytes = getByteData(subImage);
                if (bytes == null) {
                    logger.warn("Failed to get image data for page");
                    return;
                }
                float[] result = preProcessImageData(bytes);
                floatBuffer.position(j * IMAGE_WIDTH * IMAGE_HEIGHT * 3);
                floatBuffer.put(result);
            }

            floatBuffer.rewind();

            Tensor<Float> inputTensor = Tensor.create(new long[]{batch, IMAGE_WIDTH, IMAGE_HEIGHT, 3}, floatBuffer);
            List<Tensor<?>> result = session.runner().feed(INPUT_TENSOR_NAME, inputTensor)
                    .fetch(OUTPUT_TENSOR_NAME).run();

            float[][] predictions = result.get(0).copyTo(new float[batch][2]);
            for (int j = 0; j < batch; j++) {
                try {
                    float[] labelProbabilities = new float[2];
                    for (int k = 0; k < 2; k++) {
                        labelProbabilities[k] = predictions[j][k];
                    }
                    if (labelProbabilities.length != 2) {
                        logger.info("the labelProbabilities num is not true");
                        return;
                    }
                    float tableScore = labelProbabilities[TableClassifyType.TABLE];
                    Table table = tables.get(i + j);
                    table.setClassifyScore(tableScore);
                    if (tableScore > 0.6) {
                        table.updateConfidence(Table.HIGH_CONFIDENCE_THRESHOLD, 1.0);
                    } else {
                        if (tableScore > 0.4) {
                            table.updateConfidence(Table.LOW_CONFIDENCE_THRESHOLD, Table.HIGH_CONFIDENCE_THRESHOLD);
                        } else {
                            table.updateConfidence(0.0, Table.LOW_CONFIDENCE_THRESHOLD);
                        }
                    }

                    String type = TableClassifyType.desc(tableScore > 0.5 ? TableClassifyType.TABLE : TableClassifyType.OTHER);
                    logger.info("TableId:{}, Table image classified as type: {}, the classify score is: {}", table.getIndex(), type, tableScore);
                } catch (Exception e) {
                    logger.warn("Failed to classify table");
                }
            }
        }
    }

    public static void classifyTablesOneByOne(Page page, List<Table> tables) {
        if (page == null || tables == null || tables.isEmpty()) {
            return;
        }

        logger.info("当前表格分类的文件:{}, 页数:{}",page.getParams().path, page.getPageNumber());

        //载入模型
        SavedModelBundle savedModelBundle = TensorflowManager.INSTANCE.getSavedModelBundle(TensorflowManager.TABLE_CLASSIFY);
        if (savedModelBundle == null) {
            logger.warn("没有表格分类模型文件");
            return;
        }

        //将图像转为tensor batch
        Session session = savedModelBundle.session();

        BufferedImage pageImage = getPageBufferedImage(page, TABLE_CLASSIFY_DPI, true);
        if (pageImage == null) {
            logger.warn("can't getOneShotPageImage");
            return;
        }

        for (int i = 0; i < tables.size(); i++) {
            Table table = tables.get(i);
            BufferedImage subImage = getSubBufferedImage(pageImage, TABLE_CLASSIFY_DPI, tables.get(i), false, IMAGE_WIDTH, IMAGE_HEIGHT);
            byte[] bytes = getByteData(subImage);
            if (bytes == null) {
                logger.warn("Failed to get image data for page");
                continue;
            }

            Tensor<Float> image = constructAndExecuteGraphToNormalizeImage(bytes);
            try (Tensor<Float> result = session.runner().feed(INPUT_TENSOR_NAME, image).fetch(OUTPUT_TENSOR_NAME)
                         .run().get(0).expect(Float.class)) {
                final long[] rshape = result.shape();
                if (result.numDimensions() != 2 || rshape[0] != 1) {
                    logger.warn(String.format(
                            "Expected model to produce a [1 N] shaped tensor where N is the number of labels, instead it produced one with shape %s",
                            Arrays.toString(rshape)));
                    return;
                }
                int nlabels = (int) rshape[1];
                float[] labelProbabilities = result.copyTo(new float[1][nlabels])[0];
                if (labelProbabilities.length != 2) {
                    logger.warn("the labelProbabilities num is not true");
                    return;
                }
                float tableScore = labelProbabilities[TableClassifyType.TABLE];
                table.setClassifyScore(tableScore);
                if (tableScore > 0.6) {
                    table.updateConfidence(Table.HIGH_CONFIDENCE_THRESHOLD, 1.0);
                } else {
                    if (tableScore > 0.4) {
                        table.updateConfidence(Table.LOW_CONFIDENCE_THRESHOLD, Table.HIGH_CONFIDENCE_THRESHOLD);
                    } else {
                        table.updateConfidence(0.0, Table.LOW_CONFIDENCE_THRESHOLD);
                    }
                }

                String type = TableClassifyType.desc(tableScore > 0.5 ? TableClassifyType.TABLE : TableClassifyType.OTHER);
                logger.info("TableId:{}, Table image classified as type: {}, the classify score is: {}", table.getIndex(), type, tableScore);
            }
        }
    }

}
