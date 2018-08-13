package com.abcft.pdfextract.core.office;

import net.arnx.wmf2svg.gdi.svg.SvgGdi;
import net.arnx.wmf2svg.gdi.wmf.WmfParser;
import org.apache.batik.transcoder.TranscoderException;
import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.transcoder.image.PNGTranscoder;
import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.freehep.graphicsio.emf.Emf2Png;
import org.w3c.dom.Document;

import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.Objects;


public class Image {

    private static final Logger logger = LogManager.getLogger();

    private static final int IMAGE_LOCAL = 1;
    private static final int IMAGE_DATA = 2;
    private static final int IMAGE_STREAM = 3;
    private static final int IMAGE_BUFFERED = 4;

    private final int type;
    private final Object image;
    private int pageIndex;
    private int index;
    private String title;
    private String imageFile;
    private String format;
    private Pair<Integer, Integer> size;

    private Image(String localPath) {
        this.type = IMAGE_LOCAL;
        this.image = localPath;
        this.format = localPath.substring(localPath.lastIndexOf("."));
    }

    private Image(byte[] data, String format) {
        this.type = IMAGE_DATA;
        this.image = data;
        this.format = format;
    }

    private Image(OutputStream stream, String format) {
        this.type = IMAGE_STREAM;
        this.image = stream;
        this.format = format;
    }

    Image(BufferedImage buffered) {
        this.type = IMAGE_BUFFERED;
        this.image = buffered;
        this.format = "png";
    }

    @Override
    public String toString() {
        return String.format("[Image (type=%d) %s]", type, title);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Image image1 = (Image) o;
        return type == image1.type &&
                pageIndex == image1.pageIndex &&
                Objects.equals(image, image1.image) &&
                Objects.equals(title, image1.title) &&
                Objects.equals(format, image1.format);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, image, pageIndex, title, format);
    }

    public int getType() {
        return type;
    }

    public Object getImage() {
        return image;
    }

    public int getPageIndex() {
        return pageIndex;
    }

    public void setPageIndex(int pageIndex) {
        this.pageIndex = pageIndex;
    }

    public int getIndex() {
        return index;
    }

    public void setIndex(int index) {
        this.index = index;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Pair<Integer, Integer> getSize() {
        return size;
    }

    public void setSize(Pair<Integer, Integer> size) {
        this.size = size;
    }

    public void setSize(int width, int height) {
        this.setSize(Pair.of(width, height));
    }

    public void setSize(double width, double height) {
        int w = (int) (width + 0.5);
        int h = (int) (height + 0.5);
        this.setSize(w, h);
    }

    public String getImageFile() {
        return imageFile;
    }

    public void setImageFile(String imageFile) {
        this.imageFile = imageFile;
    }

    public String getFormat() {
        return format;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public void write(OutputStream outputStream) throws IOException {
        switch (type) {
            case IMAGE_DATA:
                outputStream.write((byte[]) this.image);
                return;
            case IMAGE_BUFFERED:
                BufferedImage img = (BufferedImage) this.image;
                boolean result = ImageIO.write(img, this.format, outputStream);
                if (!result) {
                    throw new IOException("Failed to save image " + this.toString());
                }
                return;
            default:
                logger.warn("TODO: Image write type={}", type);
                throw new IOException("TODO: Image write type=" + type);
        }
    }

    @Nullable
    public static Image toImage(byte[] data, String ext) {
        ext = ext.replaceAll("\\.", "").toLowerCase();
        switch (ext) {
            case "png":
            case "jpg":
            case "jpeg":
            case "gif":
            case "bmp":
            case "tiff":
                return new Image(data, ext);
            case "wmf":
                return ImageUtils.wmf2png(data);
            case "emf":
                return ImageUtils.emf2png(data);
            default:
                logger.warn("TODO format={}", ext);
                return null;
        }
    }

    public boolean isTooSmall(int width, int height, int length) {
        if (format.equals("svg")) {
            return false;
        }
        // TODO: magic number: 2000, 100
        switch (type) {
            case IMAGE_DATA: {
                byte[] img = (byte[]) image;
                if (img.length < length) {
                    return true;
                }
            }
            break;
            case IMAGE_BUFFERED: {
                BufferedImage img = (BufferedImage) image;
                if (img.getWidth() < width && img.getHeight() < height) {
                    return true;
                }
            }
            break;
        }
        return false;
    }

}

final class ImageUtils {

    private static final String IMAGE_PREFIX = "IMAGE_";
    private static final String EMF_SUFFIX = ".emf";
    private static final String PNG_SUFFIX = ".png";

    private static final Logger logger = LogManager.getLogger();
    private static boolean existInkscape;
    static {
        /* 目前Java EMF库效果已经比较好了, 不需要通过外部命令调用inkscape来转换emf
        CommandLine cmdLine = CommandLine.parse("inkscape -V");
        DefaultExecutor executor = new DefaultExecutor();
        try {
            executor.execute(cmdLine);
            existInkscape = true;
        } catch (IOException e) {
            logger.info("inkscape is not exist,emf2png use libemf2png");
            existInkscape = false;
        }
        */
    }

    public static Image emf2png0(byte[] data) {
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(data)) {
            BufferedImage image = Emf2Png.renderEmf(inputStream);
            return new Image(image);
        } catch (java.lang.Exception e) {
            logger.warn("emf2png error", e);
        }
        return null;
    }

    public static Image wmf2png(byte[] data) {
        byte[] bytes = wmfToSvg(data);
        return svg2png(bytes);
    }

    public static Image svg2png(byte[] data) {
        // Create a PNG transcoder
        PNGTranscoder t = new PNGTranscoder();
        // Set the transcoding hints.
        t.addTranscodingHint(PNGTranscoder.KEY_FORCE_TRANSPARENT_WHITE, true);
        // Create the transcoder input.
        TranscoderInput input = new TranscoderInput(new ByteArrayInputStream(data));
        // Create the transcoder output.
        ByteArrayOutputStream ostream = new ByteArrayOutputStream();
        TranscoderOutput output = new TranscoderOutput(ostream);
        // Save the image.
        Image image = null;
        try {
            t.transcode(input, output);
            image = Image.toImage(ostream.toByteArray(), "png");
        } catch (TranscoderException e) {
            logger.error("svg2png ERROR", e);
            e.printStackTrace();
        }
        return image;
    }

    private static byte[] wmfToSvg(byte[] data) {

        try(ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayInputStream in = new ByteArrayInputStream(data)) {
            WmfParser parser = new WmfParser();
            final SvgGdi gdi = new SvgGdi(false);
            parser.parse(in, gdi);
            Document doc = gdi.getDocument();
            return output(doc, out);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private static byte[] output(Document doc,ByteArrayOutputStream out) throws Exception {
        TransformerFactory factory = TransformerFactory.newInstance();
        Transformer transformer = factory.newTransformer();
        transformer.setOutputProperty(OutputKeys.METHOD, "xml");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty(OutputKeys.DOCTYPE_PUBLIC,
                "-//W3C//DTD SVG 1.0//EN");
        transformer.setOutputProperty(OutputKeys.DOCTYPE_SYSTEM,
                "http://www.w3.org/TR/2001/REC-SVG-20010904/DTD/svg10.dtd");
        transformer.transform(new DOMSource(doc), new StreamResult(out));
        byte[] bytes = out.toByteArray();
        out.flush();
        out.close();
        return bytes;
    }

    public static Image emf2png(byte[] data) {
        InputStream is = null;
        OutputStream outputStream = null;
        if(existInkscape){
            try {
                File emfFile = File.createTempFile(IMAGE_PREFIX, EMF_SUFFIX);
                String emfPath = emfFile.getAbsolutePath();
                String pngPath = emfPath.replace(EMF_SUFFIX, PNG_SUFFIX);
                outputStream = new FileOutputStream(emfFile);
                outputStream.write(data);
                CommandLine cmdLine = CommandLine.parse("inkscape -w 600 -h 500 -e ");
                cmdLine.addArgument(pngPath);
                cmdLine.addArgument(emfPath);
                DefaultExecutor executor = new DefaultExecutor();
                executor.execute(cmdLine);

                is = new FileInputStream(pngPath);
                byte[] bytes = IOUtils.toByteArray(is);

                File pngFile = new File(pngPath);
                FileUtils.deleteQuietly(emfFile);
                FileUtils.deleteQuietly(pngFile);
                return Image.toImage(bytes, "png");
            } catch (IOException e) {
                // 命令行调用失败执行其他方案
                logger.info("inkscape emf2png fail,use libemf2png tanserform");
                return emf2png0(data);
            } finally {
                if(outputStream != null){
                    try {
                        outputStream.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                if (is != null) {
                    try {
                        is.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }else {
            return emf2png0(data);
        }
    }

    public static void main(String[] args) {
        String file = "/home/kyin/下载/word/media/image5.emf";
        try {
            FileInputStream inputStream = new FileInputStream(file);
            byte[] bytes = IOUtils.toByteArray(inputStream);
            emf2png(bytes);
        } catch (IOException e) {
            e.printStackTrace();
        }
        CommandLine cmdLine = CommandLine.parse("inkscape --help");
        DefaultExecutor executor = new DefaultExecutor();
        try {
            int execute = executor.execute(cmdLine);
            existInkscape = true;
        } catch (IOException e) {
            logger.info("inkscape is not exist,emf2png use libemf2png");
            existInkscape = false;
        }
    }

}