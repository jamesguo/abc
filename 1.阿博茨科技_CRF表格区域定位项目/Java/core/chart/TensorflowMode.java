package com.abcft.pdfextract.core.chart;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import javax.imageio.ImageIO;

import org.tensorflow.*;
import org.tensorflow.framework.GPUOptions;
import org.tensorflow.types.UInt8;

/**
 * Created by myyang on 17-7-17.
 */
public class TensorflowMode {
    private static Logger logger = LogManager.getLogger(TensorflowMode.class);

    // 因为导入的模型及其类别信息 需要多次使用
    public List<String> labels = new ArrayList<>();      // 预测的类别集
    public byte[] graphDef = null;                       // 读入的训练好Tensorflow Model的字节码数组
    public Graph graph;                                  // 导入当前模型后的全局 Graph 对象
    public Session session;                              // 导入当前模型后的全局 Session 对象
    public List<String> modelInfos = new ArrayList<>();  // 当前读入的模型及其相关文件信息
    public int width;                                    // 图片宽度
    public int height;                                   // 图片高度

    protected TensorflowMode() { }

    /**
     * 判断当前模型环境是否准备好 如果准备好则可以对给定图片对象进行预测类别
     * @return
     */
    public boolean isTFModelReady() {
        return (graphDef != null && !labels.isEmpty() && graph != null && session != null);
    }

    /**
     * 重置属性变量状态 释放资源 (当目前模型不需要使用时或这更换其他类似模型文件时 使用)
     */
    public void resetTFModeEnv() {
        labels.clear();
        modelInfos.clear();
        graphDef = null;
        if (session != null) {
            session.close();
            session = null;
        }
        if (graph != null) {
            graph.close();
            graph = null;
        }
    }

    /**
     * 实时动态设置 Tensorflow Model 的相关环境 可以更换不同的模型
     * @param modelDir
     * @param modeFile
     * @param labelsFile
     * @return
     */
    public boolean setTFModelEnv(String modelDir, String modeFile, String labelsFile) {
        // 判断给定的模型及其相关文件信息是否已经在使用中 如果是 则不用再次导入
        if (!modelInfos.isEmpty()) {
            boolean isUsed = modelInfos.stream().anyMatch(info -> info.equals(modelDir)) &&
                    modelInfos.stream().anyMatch(info -> info.equals(modeFile)) &&
                    modelInfos.stream().anyMatch(info -> info.equals(labelsFile));
            if (isUsed) {
                return true;
            }
        }

        // 清空数据
        resetTFModeEnv();

        // 读取给定路径的模型文件和标称文件
        graphDef = readAllBytesOrExit(Paths.get(modelDir, modeFile));
        labels = readAllLinesOrExit(Paths.get(modelDir, labelsFile));
        if (graphDef == null || labels == null) {
            return false;
        }

        // 设置 CPU 或 GPU 相关参数
        try {
            graph = new Graph();
            graph.importGraphDef(graphDef);
            org.tensorflow.framework.ConfigProto.Builder configBuilder = org.tensorflow.framework
                    .ConfigProto.newBuilder().setAllowSoftPlacement(true)
                    .setGpuOptions(GPUOptions.newBuilder().setAllowGrowth(true));
            byte[] sessionConfig = configBuilder.build().toByteArray();
            session = new Session(graph, sessionConfig);
        }
        catch (Exception e) {
            logger.error(e);
            resetTFModeEnv();
            return false;
        }

        // 将当前使用的模型文件信息 保存 方便查询
        modelInfos.add(modelDir);
        modelInfos.add(modeFile);
        modelInfos.add(labelsFile);
        return true;
    }

    public Tensor constructImage(
            Object imageFile, DataType type, boolean expandDim, boolean normalImage) {
        if (imageFile instanceof String) {
            return constructImage((String)imageFile, type, expandDim, normalImage);
        }
        else if (imageFile instanceof BufferedImage) {
            return constructImage((BufferedImage)imageFile, type, expandDim, normalImage);
        }
        else {
            return null;
        }
    }

    /**
     * 对给定文件名的图片 进行解码 得到可供模型使用的 Tensor 对象
     * @param imageFile
     * @param type
     * @param expandDim
     * @param normalImage
     * @return
     */
    public Tensor constructImage(
            String imageFile, DataType type, boolean expandDim, boolean normalImage) {
        try {
            BufferedImage bufferedImage = ImageIO.read(new FileInputStream(imageFile));  //读取一幅图像到图像缓冲区
            byte[] imageBytes = readAllBytesOrExit(Paths.get(imageFile));
            this.width = bufferedImage.getWidth();
            this.height = bufferedImage.getHeight();
            return constructImage(imageBytes, 3, type, expandDim, normalImage);
        }
        catch (Exception e) {
            logger.error(e);
            return null;
        }
    }

    /**
     * 对给定BufferedImage 进行解码 得到可供模型使用的 Tensor 对象
     * (在解析PDF过程中, 需要将中间状态的 BufferedImage 直接进行预测)
     * @param bufferedImage
     * @param type
     * @param expandDim
     * @param normalImage
     * @return
     */
    public Tensor constructImage(
            BufferedImage bufferedImage, DataType type, boolean expandDim, boolean normalImage) {
        try {
            //int[] imageBytes = ((DataBufferInt) bufferedImage.getData().getDataBuffer()).getData();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(bufferedImage, "jpg", baos);
            baos.flush();
            byte[] imageInByte = baos.toByteArray();
            baos.close();
            this.width = bufferedImage.getWidth();
            this.height = bufferedImage.getHeight();
            return constructImage(imageInByte, 3, type, expandDim, normalImage);
        }
        catch (Exception e) {
            logger.error(e);
            return null;
        }
    }

    /**
     * 给定字节码数组和目标尺寸信息 将图片内容解码 (不同模型输入图片对象要求不同)
     * @param imageBytes
     * @param c
     * @param type
     * @param expandDim
     * @param normalImage
     * @return
     */
    private Tensor constructImage(
            byte[] imageBytes, int c, DataType type, boolean expandDim, boolean normalImage) {
        int [] size = {height, width};
        // 判断是否需要正则化并调整至适当尺寸
        if (normalImage) {
            size = getProperSize(size[0], size[1]);
            c = 1;
        }
        try (Graph g = new Graph()) {
            GraphBuilder b = new GraphBuilder(g);
            final Output input = b.constant("input", imageBytes);
            final Output output =
                    b.resize(
                            b.expandDims(
                                    b.cast(b.decodeImage(input, c), type),
                                    b.constant("make_batch", 0)),
                            b.constant("size", new int[] {size[0], size[1]}), type);
            try (Session s = new Session(g)) {
                Tensor image = s.runner().fetch(output.op().name()).run().get(0);
                if (normalImage) {
                    Tensor tmp = cutOneDim(image, false);
                    return expandOneDim(tmp);
                }
                else if (expandDim) {
                    return image;
                }
                else {
                    return cutOneDim(image, true);
                }
            }
        }
    }

    private int[] getProperSize(int h, int w) {
        float aspect_ratio = 1.0f * w / h;
        int bucket_min_width = 12;
        int bucket_max_width = 512;
        int image_height = 32;
        int [] size = {image_height, 0};
        if (aspect_ratio < 1.0f * bucket_min_width / image_height) {
            size[1] = bucket_min_width;
        }
        else if (aspect_ratio > 1.0f * bucket_max_width / image_height) {
            size[1] = bucket_max_width;
        }
        else if (h != image_height) {
            size[1] = (int)(aspect_ratio * image_height);
            //logger.info(w + " : " + size[1]);
        }
        else {
            size[1] = w;
        }

        // 再次微调 宽度 使得 OCR 识别的 feed 参数 zero_paddings 大小非空
        // (python中允许空Tensor对象, 在java中 目前没有找到生成一个内容为空的Tensor对象 暂时这么处理)
        int [] WS = {16, 40, 64, 88, 128};                // 配合 OCR 识别模块桶缓冲块的大小
        int wnew = (int)Math.floor(size[1] / 4.0);
        for (int ww : WS) {
            if (wnew == ww + 1) {
                size[1] = size[1] - 4;
                break;
            }
        }
        return size;
    }

    private Tensor expandOneDim(Tensor tensor) {
        long[] shape = tensor.shape();
        int n = shape.length;
        int mul = 1;
        long [] size = new long[n + 1];
        size[0] = 1;
        for (int i = 0; i < n; i++) {
            mul *= shape[i];
            size[i + 1] = shape[i];
        }
        return changeTensorDim(tensor, size, mul);
    }

    private Tensor cutOneDim(Tensor tensor, boolean cutHead) {
        long[] shape = tensor.shape();
        int n = shape.length;
        if ((cutHead && shape[0] != 1) || (!cutHead && shape[n - 1] != 1)) {
            return null;
        }

        int mul = 1;
        int ith = 0;
        long [] size = new long[n - 1];
        for (int i = 0; i < n; i++) {
            if ((cutHead && i == 0) || (!cutHead && i == n - 1) ) {
                continue;
            }
            mul *= shape[i];
            size[ith++] = shape[i];
        }
        return changeTensorDim(tensor, size, mul);
    }

    private Tensor changeTensorDim(Tensor tensor, long[] size, int num) {
        if (tensor.dataType() == DataType.FLOAT) {
            FloatBuffer dst = FloatBuffer.allocate(num);
            tensor.writeTo(dst);
            dst.rewind();
            return Tensor.create(size, dst);
        }
        else if (tensor.dataType() == DataType.UINT8) {
            ByteBuffer dst = ByteBuffer.allocate(num);
            tensor.writeTo(dst);
            dst.rewind();
            return Tensor.create(UInt8.class, size, dst);
        }
        else {
            return null;
        }
    }

    /**
     * 从预测类别概率数组中找出最大概率值对应的类别编号
     * @param probabilities
     * @return
     */
    private int maxIndex(float[] probabilities) {
        int best = 0;
        for (int i = 1; i < probabilities.length; ++i) {
            if (probabilities[i] > probabilities[best]) {
                best = i;
            }
        }
        return best;
    }

    /**
     * 给定路径信息 读入对应的字节码数组
     * @param path
     * @return
     */
    private byte[] readAllBytesOrExit(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            logger.error("Failed to read [" + path + "]: " + e.getMessage());
        }
        return null;
    }

    /**
     * 给定路径信息 读入文件内容所有的行信息数组
     * @param path
     * @return
     */
    private List<String> readAllLinesOrExit(Path path) {
        try {
            return Files.readAllLines(path, Charset.forName("UTF-8"));
        } catch (IOException e) {
            logger.error("Failed to read [" + path + "]: " + e.getMessage());
        }
        return null;
    }

    static class GraphBuilder {
        private Graph g;

        GraphBuilder(Graph g) {this.g = g;}

        Output div(Output x, Output y) {
            return binaryOp("Div", x, y);
        }

        Output sub(Output x, Output y) {
            return binaryOp("Sub", x, y);
        }

        Output resizeBilinear(Output images, Output size) {
            return binaryOp("ResizeBilinear", images, size);
        }

        Output resize(Output images, Output size, DataType type) {
            if (type == DataType.UINT8 || type == DataType.INT32 || type == DataType.INT64) {
                return binaryOp("ResizeNearestNeighbor", images, size);
            }
            else {
                return binaryOp("ResizeBilinear", images, size);
            }
        }

        Output expandDims(Output input, Output dim) {
            return binaryOp("ExpandDims", input, dim);
        }

        Output cast(Output value, DataType dtype) {
            return g.opBuilder("Cast", "Cast").
                    addInput(value).setAttr("DstT", dtype).build().output(0);
        }

        Output decodeImage(Output contents, long channels) {
            return g.opBuilder("DecodeJpeg", "DecodeJpeg")
                    .addInput(contents)
                    .setAttr("channels", channels)
                    .build()
                    .output(0);
        }

        Output constant(String name, Object value) {
            try (Tensor t = Tensor.create(value)) {
                return g.opBuilder("Const", name)
                        .setAttr("dtype", t.dataType())
                        .setAttr("value", t)
                        .build()
                        .output(0);
            }
        }

        private Output binaryOp(String type, Output in1, Output in2) {
            return g.opBuilder(type, type).addInput(in1).addInput(in2).build().output(0);
        }
    }
}
