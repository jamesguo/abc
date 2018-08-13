package com.abcft.pdfextract.core.util;

import com.abcft.pdfextract.core.model.Rectangle;
import com.abcft.pdfextract.util.FloatUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.pdfbox.contentstream.PDFStreamEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDColorSpace;
import org.apache.pdfbox.pdmodel.graphics.color.PDPattern;
import org.apache.pdfbox.pdmodel.graphics.pattern.PDAbstractPattern;
import org.apache.pdfbox.pdmodel.graphics.pattern.PDShadingPattern;
import org.apache.pdfbox.pdmodel.graphics.pattern.PDTilingPattern;
import org.apache.pdfbox.pdmodel.graphics.shading.PDShading;
import org.apache.pdfbox.pdmodel.graphics.shading.PDShadingType2;
import org.apache.pdfbox.pdmodel.graphics.state.PDGraphicsState;
import org.apache.pdfbox.util.Matrix;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.imageio.*;
import javax.imageio.metadata.IIOInvalidTreeException;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Iterator;

/**
 * Utility class for graphics.
 *
 * Created by dhu, chzhong on 17-5-3.
 */
@SuppressWarnings("PackageAccessibility")
public class GraphicsUtil {

    private static final Logger logger = LogManager.getFormatterLogger(GraphicsUtil.class);
    private static final Object SYNC_ROOT = new Object();
    // 允许的距离误差
    public static final double DELTA = 0.1;

    private static final int IMAGE_SIZE = 2048;

    private static ThreadLocal<BufferedImage> SHARED_BUFFERED_IMAGE_RGB = ThreadLocal.withInitial(
            () -> new BufferedImage(IMAGE_SIZE, IMAGE_SIZE, BufferedImage.TYPE_INT_RGB)
    );


    /**
     * Creates a {@linkplain BufferedImage} with {@linkplain BufferedImage#TYPE_INT_RGB}.
     * <p>
     * If {@code width} or {@code height} is too big to use shared one,
     *  {@code null} will be returned.
     *
     * @param width the width of the image.
     * @param height the height of the image.
     * @return a {@linkplain BufferedImage} or {@code null} if size is too big.
     */
    public static BufferedImage getBufferedImageRGB(int width, int height) {
        BufferedImage largeImage = SHARED_BUFFERED_IMAGE_RGB.get();
        if (width > largeImage.getWidth() || height > largeImage.getHeight()) {
            logger.warn(String.format("getBufferedImageRGB(%d, %d) failed, width or height too large", width, height));
            return null;
        }
        return largeImage.getSubimage(0, 0, width, height);
    }

    /**
     * Creates a {@linkplain BufferedImage} with {@linkplain BufferedImage#TYPE_INT_RGB}, use shared memory is possible.
     * <p>
     * If {@code width} or {@code height} is too big to use shared one,
     *  a stand-alone {@linkplain BufferedImage} will be created.
     *
     * @param width the width of the image.
     * @param height the height of the image.
     * @return a {@linkplain BufferedImage}.
     */
    public static BufferedImage createBufferedImageRGB(int width, int height) {
        BufferedImage largeImage = SHARED_BUFFERED_IMAGE_RGB.get();
        if (width > largeImage.getWidth() || height > largeImage.getHeight()) {
            logger.warn(String.format("Created large BufferedImage(%dx%d).", width, height));
            return new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        }
        return largeImage.getSubimage(0, 0, width, height);
    }



    /**
     * Writes a buffered image to a file using the given image format. See
     * {@link #writeImage(BufferedImage image, String formatName,
     * OutputStream output, int dpi, float quality)} for more details.
     *
     * @param image the image to be written
     * @param filename used to construct the filename for the individual image.
     * Its suffix will be used as the image format.
     * @param dpi the resolution in dpi (dots per inch) to be used in metadata
     * @return true if the image file was produced, false if there was an error.
     * @throws IOException if an I/O error occurs
     */
    public static boolean writeImage(BufferedImage image, String filename,
                                     int dpi) throws IOException
    {
        File file = new File(filename);
        try
                (FileOutputStream output = new FileOutputStream(file)) {
            String formatName = filename.substring(filename.lastIndexOf('.') + 1);
            return writeImage(image, formatName, output, dpi);
        }
    }

    /**
     * Writes a buffered image to a file using the given image format. See
     * {@link #writeImage(BufferedImage image, String formatName,
     * OutputStream output, int dpi, float quality)} for more details.
     *
     * @param image the image to be written
     * @param formatName the target format (ex. "png") which is also the suffix
     * for the filename
     * @param filename used to construct the filename for the individual image.
     * The formatName parameter will be used as the suffix.
     * @param dpi the resolution in dpi (dots per inch) to be used in metadata
     * @return true if the image file was produced, false if there was an error.
     * @throws IOException if an I/O error occurs
     * @deprecated use
     * {@link #writeImage(BufferedImage image, String filename, int dpi)}, which
     * uses the full filename instead of just the prefix.
     */
    @Deprecated
    public static boolean writeImage(BufferedImage image, String formatName, String filename,
                                     int dpi) throws IOException
    {
        File file = new File(filename + "." + formatName);
        try
                (FileOutputStream output = new FileOutputStream(file)) {
            return writeImage(image, formatName, output, dpi);
        }
    }

    /**
     * Writes a buffered image to a file using the given image format. See
     * {@link #writeImage(BufferedImage image, String formatName,
     * OutputStream output, int dpi, float quality)} for more details.
     *
     * @param image the image to be written
     * @param formatName the target format (ex. "png")
     * @param output the output stream to be used for writing
     * @return true if the image file was produced, false if there was an error.
     * @throws IOException if an I/O error occurs
     */
    public static boolean writeImage(BufferedImage image, String formatName, OutputStream output)
            throws IOException
    {
        return writeImage(image, formatName, output, 72);
    }

    /**
     * Writes a buffered image to a file using the given image format. See
     * {@link #writeImage(BufferedImage image, String formatName,
     * OutputStream output, int dpi, float quality)} for more details.
     *
     * @param image the image to be written
     * @param formatName the target format (ex. "png")
     * @param output the output stream to be used for writing
     * @param dpi the resolution in dpi (dots per inch) to be used in metadata
     * @return true if the image file was produced, false if there was an error.
     * @throws IOException if an I/O error occurs
     */
    public static boolean writeImage(BufferedImage image, String formatName, OutputStream output,
                                     int dpi) throws IOException
    {
        return writeImage(image, formatName, output, dpi, 1.0f);
    }

    /**
     * Writes a buffered image to a file using the given image format.
     * Compression is fixed for PNG, GIF, BMP and WBMP, dependent of the quality
     * parameter for JPG, and dependent of bit count for TIFF (a bitonal image
     * will be compressed with CCITT G4, a color image with LZW). Creating a
     * TIFF image is only supported if the jai_imageio library is in the class
     * path.
     *
     * @param image the image to be written
     * @param formatName the target format (ex. "png")
     * @param output the output stream to be used for writing
     * @param dpi the resolution in dpi (dots per inch) to be used in metadata
     * @param quality quality to be used when compressing the image (0 &lt;
     * quality &lt; 1.0f)
     * @return true if the image file was produced, false if there was an error.
     * @throws IOException if an I/O error occurs
     */
    @SuppressWarnings("Duplicates")
    public static boolean writeImage(BufferedImage image, String formatName, OutputStream output,
                                     int dpi, float quality) throws IOException
    {
        ImageOutputStream imageOutput = null;
        ImageWriter writer = null;
        try
        {
            // find suitable image writer
            Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName(formatName);
            ImageWriteParam param = null;
            IIOMetadata metadata = null;
            // Loop until we get the best driver, i.e. one that supports
            // setting dpi in the standard metadata format; however we'd also
            // accept a driver that can't, if a better one can't be found
            while (writers.hasNext())
            {
                if (writer != null)
                {
                    writer.dispose();
                }
                writer = writers.next();
                if (writer != null)
                {
                    param = writer.getDefaultWriteParam();
                    metadata = writer.getDefaultImageMetadata(new ImageTypeSpecifier(image), param);
                    if (metadata != null
                            && !metadata.isReadOnly()
                            && metadata.isStandardMetadataFormatSupported())
                    {
                        break;
                    }
                }
            }
            if (writer == null)
            {
                logger.error("No ImageWriter found for '" + formatName + "' format");
                StringBuilder sb = new StringBuilder();
                String[] writerFormatNames = ImageIO.getWriterFormatNames();
                for (String fmt : writerFormatNames)
                {
                    sb.append(fmt);
                    sb.append(' ');
                }
                logger.error("Supported formats: " + sb);
                return false;
            }

            // compression
            if (param != null && param.canWriteCompressed())
            {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                if (formatName.toLowerCase().startsWith("tif"))
                {
                    // TIFF compression
                    tiffSetCompressionType(param, image);
                }
                else
                {
                    param.setCompressionType(param.getCompressionTypes()[0]);
                    param.setCompressionQuality(quality);
                }
            }

            if (formatName.toLowerCase().startsWith("tif"))
            {
                // TIFF metadata
                tiffUpdateMetadata(metadata, image, dpi);
            }
            else if ("jpeg".equals(formatName.toLowerCase())
                    || "jpg".equals(formatName.toLowerCase()))
            {
                // This segment must be run before other meta operations,
                // or else "IIOInvalidTreeException: Invalid node: app0JFIF"
                // The other (general) "meta" methods may not be used, because
                // this will break the reading of the meta data in tests
                jpegUpdateMetadata(metadata, dpi);
            }
            else
            {
                // write metadata is possible
                if (metadata != null
                        && !metadata.isReadOnly()
                        && metadata.isStandardMetadataFormatSupported())
                {
                    setDPI(metadata, dpi, formatName);
                }
            }

            // write
            imageOutput = ImageIO.createImageOutputStream(output);
            writer.setOutput(imageOutput);
            writer.write(null, new IIOImage(image, null, metadata), param);
        }
        finally
        {
            if (writer != null)
            {
                writer.dispose();
            }
            if (imageOutput != null)
            {
                imageOutput.close();
            }
        }
        return true;
    }


    private static final String JPEG_NATIVE_FORMAT = "javax_imageio_jpeg_image_1.0";
    private static final String STANDARD_METADATA_FORMAT = "javax_imageio_1.0";

    /**
     * Sets the ImageIO parameter compression type based on the given image.
     * @param image buffered image used to decide compression type
     * @param param ImageIO write parameter to update
     */
    private static void tiffSetCompressionType(ImageWriteParam param, BufferedImage image)
    {
        // avoid error: first compression type is RLE, not optimal and incorrect for color images
        // TODO expose this choice to the user?
        if (image.getType() == BufferedImage.TYPE_BYTE_BINARY &&
                image.getColorModel().getPixelSize() == 1)
        {
            param.setCompressionType("CCITT T.6");
        }
        else
        {
            param.setCompressionType("LZW");
        }
    }

    /**
     * Updates the given ImageIO metadata with Sun's custom TIFF tags, as described in
     * the <a href="https://svn.apache.org/repos/asf/xmlgraphics/commons/tags/commons-1_3_1/src/java/org/apache/xmlgraphics/image/writer/imageio/ImageIOTIFFImageWriter.java">org.apache.xmlgraphics.image.writer.imageio.ImageIOTIFFImageWriter
     * sources</a>,
     * the <a href="http://download.java.net/media/jai-imageio/javadoc/1.0_01/com/sun/media/imageio/plugins/tiff/package-summary.html">com.sun.media.imageio.plugins.tiff
     * package javadoc</a>
     * and the <a href="http://partners.adobe.com/public/developer/tiff/index.html">TIFF
     * specification</a>.
     *
     * @param image buffered image which will be written
     * @param metadata ImageIO metadata
     * @param dpi image dots per inch
     * @throws IIOInvalidTreeException if something goes wrong
     */
    @SuppressWarnings("Duplicates")
    static void tiffUpdateMetadata(IIOMetadata metadata, BufferedImage image, int dpi)
            throws IIOInvalidTreeException
    {
        String metaDataFormat = metadata.getNativeMetadataFormatName();
        if (metaDataFormat == null)
        {
            logger.debug("TIFF image writer doesn't support any data format");
            return;
        }

        //debugLogMetadata(metadata, metaDataFormat);

        IIOMetadataNode root = new IIOMetadataNode(metaDataFormat);
        IIOMetadataNode ifd;
        if (root.getElementsByTagName("TIFFIFD").getLength() == 0)
        {
            ifd = new IIOMetadataNode("TIFFIFD");
            root.appendChild(ifd);
        }
        else
        {
            ifd = (IIOMetadataNode)root.getElementsByTagName("TIFFIFD").item(0);
        }

        // standard metadata does not work, so we set the DPI manually
        ifd.appendChild(createRationalField(282, "XResolution", dpi, 1));
        ifd.appendChild(createRationalField(283, "YResolution", dpi, 1));
        ifd.appendChild(createShortField(296, "ResolutionUnit", 2)); // Inch

        ifd.appendChild(createLongField(278, "RowsPerStrip", image.getHeight()));
        ifd.appendChild(createAsciiField(305, "Software", "PDFBOX"));

        if (image.getType() == BufferedImage.TYPE_BYTE_BINARY &&
                image.getColorModel().getPixelSize() == 1)
        {
            // set PhotometricInterpretation WhiteIsZero
            // because of bug in Windows XP preview
            ifd.appendChild(createShortField(262, "PhotometricInterpretation", 0));
        }

        metadata.mergeTree(metaDataFormat, root);

        //debugLogMetadata(metadata, metaDataFormat);
    }

    @SuppressWarnings("Duplicates")
    private static IIOMetadataNode createShortField(int tiffTagNumber, String name, int val)
    {
        IIOMetadataNode field, arrayNode, valueNode;
        field = new IIOMetadataNode("TIFFField");
        field.setAttribute("number", Integer.toString(tiffTagNumber));
        field.setAttribute("name", name);
        arrayNode = new IIOMetadataNode("TIFFShorts");
        field.appendChild(arrayNode);
        valueNode = new IIOMetadataNode("TIFFShort");
        arrayNode.appendChild(valueNode);
        valueNode.setAttribute("value", Integer.toString(val));
        return field;
    }

    @SuppressWarnings("Duplicates")
    private static IIOMetadataNode createAsciiField(int number, String name, String val)
    {
        IIOMetadataNode field, arrayNode, valueNode;
        field = new IIOMetadataNode("TIFFField");
        field.setAttribute("number", Integer.toString(number));
        field.setAttribute("name", name);
        arrayNode = new IIOMetadataNode("TIFFAsciis");
        field.appendChild(arrayNode);
        valueNode = new IIOMetadataNode("TIFFAscii");
        arrayNode.appendChild(valueNode);
        valueNode.setAttribute("value", val);
        return field;
    }

    @SuppressWarnings("Duplicates")
    private static IIOMetadataNode createLongField(int number, String name, long val)
    {
        IIOMetadataNode field, arrayNode, valueNode;
        field = new IIOMetadataNode("TIFFField");
        field.setAttribute("number", Integer.toString(number));
        field.setAttribute("name", name);
        arrayNode = new IIOMetadataNode("TIFFLongs");
        field.appendChild(arrayNode);
        valueNode = new IIOMetadataNode("TIFFLong");
        arrayNode.appendChild(valueNode);
        valueNode.setAttribute("value", Long.toString(val));
        return field;
    }

    @SuppressWarnings("Duplicates")
    private static IIOMetadataNode createRationalField(int number, String name, int numerator,
                                                       int denominator)
    {
        IIOMetadataNode field, arrayNode, valueNode;
        field = new IIOMetadataNode("TIFFField");
        field.setAttribute("number", Integer.toString(number));
        field.setAttribute("name", name);
        arrayNode = new IIOMetadataNode("TIFFRationals");
        field.appendChild(arrayNode);
        valueNode = new IIOMetadataNode("TIFFRational");
        arrayNode.appendChild(valueNode);
        valueNode.setAttribute("value", numerator + "/" + denominator);
        return field;
    }

    /**
     * Set dpi in a JPEG file
     *
     * @param metadata the meta data
     * @param dpi the dpi
     *
     * @throws IIOInvalidTreeException if something goes wrong
     */
    @SuppressWarnings("Duplicates")
    private static void jpegUpdateMetadata(IIOMetadata metadata, int dpi) throws IIOInvalidTreeException
    {
        //MetaUtil.debugLogMetadata(metadata, MetaUtil.JPEG_NATIVE_FORMAT);

        // https://svn.apache.org/viewvc/xmlgraphics/commons/trunk/src/java/org/apache/xmlgraphics/image/writer/imageio/ImageIOJPEGImageWriter.java
        // http://docs.oracle.com/javase/6/docs/api/javax/imageio/metadata/doc-files/jpeg_metadata.html
        Element root = (Element) metadata.getAsTree(JPEG_NATIVE_FORMAT);
        NodeList jvarNodeList = root.getElementsByTagName("JPEGvariety");
        Element jvarChild;
        if (jvarNodeList.getLength() == 0)
        {
            jvarChild = new IIOMetadataNode("JPEGvariety");
            root.appendChild(jvarChild);
        }
        else
        {
            jvarChild = (Element) jvarNodeList.item(0);
        }

        NodeList jfifNodeList = jvarChild.getElementsByTagName("app0JFIF");
        Element jfifChild;
        if (jfifNodeList.getLength() == 0)
        {
            jfifChild = new IIOMetadataNode("app0JFIF");
            jvarChild.appendChild(jfifChild);
        }
        else
        {
            jfifChild = (Element) jfifNodeList.item(0);
        }
        if (jfifChild.getAttribute("majorVersion").isEmpty())
        {
            jfifChild.setAttribute("majorVersion", "1");
        }
        if (jfifChild.getAttribute("minorVersion").isEmpty())
        {
            jfifChild.setAttribute("minorVersion", "2");
        }
        jfifChild.setAttribute("resUnits", "1"); // inch
        jfifChild.setAttribute("Xdensity", Integer.toString(dpi));
        jfifChild.setAttribute("Ydensity", Integer.toString(dpi));
        if (jfifChild.getAttribute("thumbWidth").isEmpty())
        {
            jfifChild.setAttribute("thumbWidth", "0");
        }
        if (jfifChild.getAttribute("thumbHeight").isEmpty())
        {
            jfifChild.setAttribute("thumbHeight", "0");
        }

        // mergeTree doesn't work for ARGB
        metadata.setFromTree(JPEG_NATIVE_FORMAT, root);
    }


    // sets the DPI metadata
    private static void setDPI(IIOMetadata metadata, int dpi, String formatName)
            throws IIOInvalidTreeException
    {
        IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(STANDARD_METADATA_FORMAT);

        IIOMetadataNode dimension = getOrCreateChildNode(root, "Dimension");

        // PNG writer doesn't conform to the spec which is
        // "The width of a pixel, in millimeters"
        // but instead counts the pixels per millimeter
        float res = "PNG".equals(formatName.toUpperCase())
                ? dpi / 25.4f
                : 25.4f / dpi;

        IIOMetadataNode child;

        child = getOrCreateChildNode(dimension, "HorizontalPixelSize");
        child.setAttribute("value", Double.toString(res));

        child = getOrCreateChildNode(dimension, "VerticalPixelSize");
        child.setAttribute("value", Double.toString(res));

        metadata.mergeTree(STANDARD_METADATA_FORMAT, root);
    }


    /**
     * Gets the named child node, or creates and attaches it.
     *
     * @param parentNode the parent node
     * @param name name of the child node
     *
     * @return the existing or just created child node
     */
    @SuppressWarnings("Duplicates")
    private static IIOMetadataNode getOrCreateChildNode(IIOMetadataNode parentNode, String name)
    {
        NodeList nodeList = parentNode.getElementsByTagName(name);
        if (nodeList.getLength() > 0)
        {
            return (IIOMetadataNode) nodeList.item(0);
        }
        IIOMetadataNode childNode = new IIOMetadataNode(name);
        parentNode.appendChild(childNode);
        return childNode;
    }



    public static BufferedImage toBufferedImageOfType(BufferedImage original, int type) {
        if (original == null) {
            throw new IllegalArgumentException("original == null");
        }

        // Don't convert if it already has correct type
        if (original.getType() == type) {
            return original;
        }

        // Create a buffered image
        BufferedImage image = new BufferedImage(original.getWidth(), original.getHeight(), type);

        // Draw the image onto the new buffer
        Graphics2D g = image.createGraphics();
        try {
            g.setComposite(AlphaComposite.Src);
            g.drawImage(original, 0, 0, null);
        }
        finally {
            g.dispose();
        }

        return image;
    }



    /**
     * Append a rectangle to the current path.
     */
    @SuppressWarnings("Duplicates")
    public static void appendRectangleToPath(GeneralPath linePath, Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
        // to ensure that the path is created in the right direction, we have to create
        // it by combining single lines instead of creating a simple rectangle
        linePath.moveTo((float) p0.getX(), (float) p0.getY());
        linePath.lineTo((float) p1.getX(), (float) p1.getY());
        linePath.lineTo((float) p2.getX(), (float) p2.getY());
        linePath.lineTo((float) p3.getX(), (float) p3.getY());

        // close the subpath instead of adding the last line so that a possible set line
        // cap style isn't taken into account at the "beginning" of the rectangle
        linePath.closePath();
    }

    public static boolean nearlyContains(Rectangle2D container, Rectangle2D area, double epsilon) {
        return FloatUtils.flte(container.getMinX(), area.getMinX(), epsilon)
                && FloatUtils.flte(area.getMaxX(), container.getMaxX(), epsilon)
                && FloatUtils.flte(container.getMinY(), area.getMinY(), epsilon)
                && FloatUtils.flte(area.getMaxY(), container.getMaxY(), epsilon);
    }

    public static boolean nearlyOverlap(Rectangle2D container, Rectangle2D area, double epsilon) {
        Rectangle2D intersection = container.createIntersection(area);
        if (intersection.getWidth() < 0 || intersection.getHeight() < 0) {
            return false;
        }
        double a1 = (container.getWidth() * container.getHeight());
        double a2 = (area.getWidth() * area.getHeight());
        double a = intersection.getWidth() * intersection.getHeight();
        double ratio = a / Math.min(a1, a2);
        return FloatUtils.flte(Math.abs(1. - ratio), epsilon);
    }

    public static boolean nearlyIntersects(Rectangle one, Rectangle other, int e1, int e2) {
        boolean horizontal = FloatUtils.feq(one.getTop(), other.getTop(), e1)
                && FloatUtils.feq(one.getBottom(), other.getBottom(), e1);

        if (horizontal) {
            return  one.getLeft() <= other.getLeft() - e2 && other.getLeft() <= one.getRight() - e2
                    || other.getLeft() <= one.getLeft() - e2 && one.getLeft() <= other.getRight() - e2;
        }

        boolean vertical = Math.abs(other.getLeft() - one.getLeft()) <= e1
                && Math.abs(one.getRight() - other.getRight()) <= e1;
        return vertical && (one.getTop() <= other.getTop() - e2 && other.getTop() <= one.getBottom() - e2 || other.getTop() <= one.getTop() - e2 && one.getTop() <= other.getBottom() - e2);
    }


    private GraphicsUtil() {}

    public static Color getPaintColor(Paint paint) {
        if (paint instanceof Color) {
            return (Color) paint;
        }
        BufferedImage image = getBufferedImageRGB(1, 1);
        Graphics2D graphics = image.createGraphics();
        graphics.setPaint(paint);
        graphics.scale(10, 10);
        graphics.fillRect(0, 0, 10, 10);
        graphics.dispose();
        return new Color(image.getRGB(0, 0));
    }

    public static Color getShadingColor(COSName shadingName, PDShading shading, PDGraphicsState graphicsState) {
        /*
        if (shading instanceof PDShadingType2) {
            return getType2ShadingColor(shadingName, (PDShadingType2) shading);
        }
        */

        Matrix ctm = graphicsState.getCurrentTransformationMatrix();
        Paint paint = shading.toPaint(ctm);

        Rectangle2D clip = graphicsState.getCurrentClippingPath().getBounds2D();
        BufferedImage image = getBufferedImageRGB(1, 1);
        Graphics2D graphics = image.createGraphics();
        graphics.setPaint(paint);
        graphics.translate(-clip.getMinX(), -clip.getMinY());
        graphics.fill(clip);
        graphics.dispose();
        return new Color(image.getRGB(0, 0));
    }

    /**
     * Convert color values from shading colorspace to RGB color values encoded
     * into an integer.
     *
     * @param values color values in shading colorspace.
     * @return RGB values encoded in an integer.
     * @throws java.io.IOException if the color conversion fails.
     */
    private static final int convertToRGB(PDColorSpace shadingColorSpace, float[] values) throws IOException {
        int normRGBValues;

        float[] rgbValues = shadingColorSpace.toRGB(values);
        normRGBValues = (int) (rgbValues[0] * 255);
        normRGBValues |= (int) (rgbValues[1] * 255) << 8;
        normRGBValues |= (int) (rgbValues[2] * 255) << 16;

        return normRGBValues;
    }

    private static Color getType2ShadingColor(COSName shadingName, PDShadingType2 shading) {
        try {
            // domain values
            float[] domain;
            if (shading.getDomain() != null) {
                domain = shading.getDomain().toFloatArray();
            } else {
                // set default values
                domain = new float[] { 0, 1 };
            }
            float[] values = shading.evalFunction(domain[0]);
            return new Color(convertToRGB(shading.getColorSpace(), values));
        } catch (Exception e) {
            return new Color(shadingName.getName().hashCode() | 0xff0000);
        }
    }

    /**
     * 将Color对象格式化为字符串
     * @param color
     * @return
     */
    public static String color2String(Color color) {
        if (color == null) {
            return null;
        }
        return String.format("#%02x%02x%02x",
                color.getRed(), color.getGreen(), color.getBlue());
    }

    public static Color string2Color(String color) {
        if (StringUtils.equals(color, "auto")) {
            return Color.BLACK;
        }
        int value = Integer.parseInt(color.replace("#", ""), 16);
        return new Color(value);
    }

    /**
     * 检测path是否为矩形
     * @param path the path
     * @return is rect or not
     */
    public static boolean isRect(Shape path) {
        PathIterator iter = path.getPathIterator(null);
        double[] coords = new double[6];
        int count = 0;
        int[] xs = new int[4];
        int[] ys = new int[4];
        while (!iter.isDone()) {
            switch (iter.currentSegment(coords)) {
                case PathIterator.SEG_MOVETO:
                    if (count == 0) {
                        xs[count] = (int) Math.floor(coords[0]);
                        ys[count] = (int) Math.floor(coords[1]);
                    } else {
                        return false;
                    }
                    count++;
                    break;

                case PathIterator.SEG_LINETO:
                    if (count < 4) {
                        xs[count] = (int) Math.floor(coords[0]);
                        ys[count] = (int) Math.floor(coords[1]);
                        if (xs[count] != xs[count - 1] && ys[count] != ys[count - 1]) {
                            return false;
                        }
                    } else {
                        return false;
                    }
                    count++;
                    break;

                case PathIterator.SEG_CUBICTO:
                    return false;

                case PathIterator.SEG_CLOSE:
                    if (xs[0] != xs[count - 1] && ys[0] != ys[count - 1]) {
                        return false;
                    }
                    break;
            }
            iter.next();
        }
        return count == 4;
    }

    /**
     * 检测path是否为直线, 如果为直线则返回Line2D, 否则返回null
     * @param path the path
     * @return is line or not
     */
    public static Line2D getLine(Shape path) {
        PathIterator iter = path.getPathIterator(null);
        float[] coords = new float[6];
        int count = 0;
        float x1 = 0, y1 = 0, x2 = 0, y2 = 0;
        while (!iter.isDone()) {
            switch (iter.currentSegment(coords)) {
                case PathIterator.SEG_MOVETO:
                    if (count == 0) {
                        x1 = coords[0];
                        y1 = coords[1];
                    } else {
                        return null;
                    }
                    count++;
                    break;

                case PathIterator.SEG_LINETO:
                    if (count == 1) {
                        x2 = coords[0];
                        y2 = coords[1];
                    } else {
                        return null;
                    }
                    count++;
                    break;

                case PathIterator.SEG_CUBICTO:
                    return null;

                case PathIterator.SEG_CLOSE:
                    break;
            }
            iter.next();
        }
        if (count == 2) {
            return new Line2D.Float(x1, y1, x2, y2);
        } else {
            return null;
        }
    }

    public static Line2D rectToLine(Rectangle2D rect) {
        return new Line2D.Float((float) rect.getMinX(), (float) rect.getCenterY(),
                (float) rect.getMaxX(), (float) rect.getCenterY());
    }

    public static boolean isHorizontal(Line2D line) {
        return Math.abs(line.getY1() - line.getY2()) < DELTA;
    }

    public static boolean isVertical(Line2D line) {
        return Math.abs(line.getX1() - line.getX2()) < DELTA;
    }

    /**
     * 把矩形所有的边界往外加大
     * @param rect 矩形
     * @param value 加大的值
     */
    public static void extendRect(Rectangle2D rect, double value) {
        rect.setRect(rect.getMinX() - value, rect.getMinY() - value,
                rect.getWidth() + 2 * value, rect.getHeight() + 2* value);
    }

    public static void extendRect(Rectangle2D rect, float left, float top, float right, float bottom) {
        rect.setRect(rect.getMinX() - left, rect.getMinY() - top,
                rect.getWidth() + left + right, rect.getHeight() + top + bottom);
    }

    public static boolean overlapRect(Rectangle2D rectA, Rectangle2D rectB, double coef) {
        if (!rectA.intersects(rectB)) {
            return false;
        }
        Rectangle2D rectC = new Rectangle2D.Float(0.0f, 0.0f, 0.0f, 0.0f);
        Rectangle2D.intersect(rectA, rectB, rectC);
        double sC = rectC.getWidth() * rectC.getHeight();
        double sB = rectB.getWidth() * rectB.getHeight();
        return sB >= 1.0 && sC / sB >= coef;
    }

    public static boolean contains(Rectangle2D rect1, Rectangle2D rect2) {
        if (rect2.getWidth() < DELTA || rect2.getHeight() < DELTA) {
            // rect2 is line
            if (rect1.contains(rect2.getMinX(), rect2.getMinY())
                    && rect1.contains(rect2.getMaxX(), rect2.getMaxY())) {
                return true;
            }
        } else if (rect1.contains(rect2)) {
            return true;
        }
        return false;
    }

    // 考虑矩形的小距离扩张 再检测包含关系
    public static boolean contains(Rectangle2D rect, Point2D point, double delta) {
        Rectangle2D rect2 = (Rectangle2D) rect.clone();
        extendRect(rect2, delta);
        return rect2.contains(point);
    }

    private static float clampColor(float color) {
        return color < 0 ? 0 : (color > 1 ? 1 : color);
    }


    public static Color getStrokingColor(PDFStreamEngine engine) {
        return getColor(engine.getGraphicsState().getStrokingColor(), engine.getInitialMatrix());
    }

    public static Color getNonStrokingColor(PDFStreamEngine engine) {
        return getColor(engine.getGraphicsState().getNonStrokingColor(), engine.getInitialMatrix());
    }

    public static Color getColor(PDColor color, PDFStreamEngine engine) {
        return getColor(color, engine.getInitialMatrix());
    }

    public static Color getColor(PDColor color, Matrix initialMatrix) {
        PDColorSpace colorSpace = color.getColorSpace();
        if (!(colorSpace instanceof PDPattern)) {
            try {
                float[] rgb = colorSpace.toRGB(color.getComponents());
                return new Color(clampColor(rgb[0]), clampColor(rgb[1]), clampColor(rgb[2]));
            } catch (Exception e) {
                return new Color(color.toString().hashCode());
            }
        } else {
            PDPattern patternSpace = (PDPattern) colorSpace;
            try {
                PDAbstractPattern pattern = patternSpace.getPattern(color);
                if (pattern instanceof PDTilingPattern) {
                    PDTilingPattern tilingPattern = (PDTilingPattern) pattern;
                    return new Color(tilingPattern.getResources().getXObjectNames().toString().hashCode());
                } else {
                    PDShadingPattern shadingPattern = (PDShadingPattern) pattern;
                    PDShading shading = shadingPattern.getShading();
                    Paint paint = shading.toPaint(Matrix.concatenate(initialMatrix, shadingPattern.getMatrix()));
                    return getPaintColor(paint);
                }
            } catch (Exception e) {
                return new Color(color.toString().hashCode());
            }
        }
    }

    public static int getAngleInDegrees(AffineTransform at) {
        // TODO 是否有更好的方式？或者用 FastMath？
        Point2D p0 = new Point();
        Point2D p1 = new Point(1,0);
        Point2D pp0 = at.transform(p0, null);
        Point2D pp1 = at.transform(p1, null);
        double dx = pp1.getX() - pp0.getX();
        double dy = pp1.getY() - pp0.getY();
        return (int) Math.toDegrees(Math.atan2(dy, dx));
    }

    public static float transformWidth(Matrix ctm, float width) {
        float x = ctm.getScaleX() + ctm.getShearX();
        float y = ctm.getScaleY() + ctm.getShearY();
        return width * (float)Math.sqrt((x * x + y * y) * 0.5);
    }

    public static float getTransformedLineWidth(PDGraphicsState graphicsState) {
        return transformWidth(graphicsState.getCurrentTransformationMatrix(), graphicsState.getLineWidth());
    }


    public static Rectangle2D normalize(Rectangle2D rect) {
        if (rect.getWidth() >= 0 && rect.getHeight() >= 0) {
            return rect;
        }
        double x1 = rect.getX();
        double y1 = rect.getY();
        double x2 = x1 + rect.getWidth();
        double y2 = y1 + rect.getHeight();

        double x = Math.min(x1, x2);
        double y = Math.min(y1, y2);
        double w = Math.abs(x2 - x1);
        double h = Math.abs(y2 - y1);
        return new Rectangle.Double(x, y, w, h);
    }


}
