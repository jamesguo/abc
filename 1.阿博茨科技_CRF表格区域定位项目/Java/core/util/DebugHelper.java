package com.abcft.pdfextract.core.util;

import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Utility methods for drawing debug images.
 *
 * Created by chzhong on 17-5-8.
 */
public final class DebugHelper {

    private static int colorIndex = 0;

    private static final Color[] COLORS = {
            new Color(0x7c, 0xb5, 0xec),
            new Color(0x00, 0x00, 0xff),
            new Color(0x90, 0xed, 0x7d),
            new Color(0xf7, 0xa3, 0x5c),
            new Color(0x80, 0x85, 0xe9),
            new Color(0xf1, 0x5c, 0x80),
            new Color(0xe4, 0xd3, 0x54),
            new Color(0x2b, 0x90, 0x8f),
            new Color(0xf4, 0x5b, 0x5b),
            new Color(0x91, 0xe8, 0xe1),
            new Color(0x2f, 0x7e, 0xd8),
            new Color(0x00, 0xff, 0x00),
            new Color(0x8b, 0xbc, 0x21),
            new Color(0x91, 0x00, 0x00),
            new Color(0x1a, 0xad, 0xce),
            new Color(0x49, 0x29, 0x70),
            new Color(0xf2, 0x8f, 0x43),
            new Color(0x77, 0xa1, 0xe5),
            new Color(0xc4, 0x25, 0x25),
            new Color(0xa6, 0xc9, 0x6a),
    };

    public static final BasicStroke STROKE_DASHED =
            new BasicStroke(1.0f,
                    BasicStroke.CAP_BUTT,
                    BasicStroke.JOIN_MITER,
                    4.0f,  new float[]{ 4.0f }, 0.0f);

    private static final ThreadLocal<PDPageImageCache> PAGE_IMAGE_CACHE = ThreadLocal.withInitial(PDPageImageCache::new);
    public static final int DEFAULT_DPI = 72;

    private DebugHelper() {}

    private static int debugFileNo;

    public static File getDebugFile(String pdfPath, int pageNumber, String postfix, String extension) {
        File fileDir;
        String prefix;
        if (pdfPath != null) {
            File pdfFile = new File(pdfPath);
            fileDir = pdfFile.getParentFile();
            prefix = String.format("%s-%d-%s", pdfFile.getName().replace(".pdf",""),
                    pageNumber, postfix);
        } else {
            fileDir = new File("debug/images");
            if (!fileDir.exists()) {
                fileDir.mkdirs();
            }
            prefix = String.format("%s-%d", postfix, debugFileNo++);
        }
        return new File(fileDir, prefix + extension);
    }

    public static void drawShape(Graphics2D g, Shape shape) {
        g.setColor(nextColor());
        g.draw(shape);
    }

    public static void drawShape(Graphics2D g, Shape shape, Stroke stroke) {
        g.setColor(nextColor());
        g.setStroke(stroke);
        g.draw(shape);
    }

    private static Color nextColor() {
        return COLORS[(colorIndex++) % COLORS.length];
    }

    public static void resetColorIndex() {
        colorIndex = 0;
    }

    public static void drawShapes(Graphics2D g, Collection<? extends Shape> shapes, Stroke stroke) {
        g.setStroke(stroke);
        for (Shape s : shapes) {
            g.setColor(nextColor());
            drawShape(g, s);
        }
    }

    public static void drawShapes(Graphics2D g, Collection<? extends Shape> shapes) {
        drawShapes(g, shapes, new BasicStroke(0.5f));
    }

    public static BufferedImage pageConvertToImage(PDPage page, int dpi, ImageType imageType) throws IOException {
        // Yeah, this sucks. But PDFBox 2 wants PDFRenderers to have
        // a reference to a PDDocument (unnecessarily, IMHO)

        PDDocument document = null;
        try {
            document = new PDDocument();
            document.addPage(page);

            PDFRenderer renderer = new PDFRenderer(document);

            return renderer.renderImageWithDPI(0, dpi, imageType);
        } finally {
            IOUtils.closeQuietly(document);
        }

    }

    /**
     * Retrieve the image of an PDF page.
     * <p>
     * Remember to use {@linkplain #releaseCachedImage(PDPage)} if you specify {@code true} for {@code useCache}.
     *
     * @param page the page.
     * @param useCache whether use and cache generated image.
     * @return the image of the PDF page.
     * @throws IOException something wrong happened.
     */
    public static BufferedImage getPageImage(PDPage page, boolean useCache) throws IOException {
        return getPageImage(page, DEFAULT_DPI, useCache);
    }


    /**
     * Retrieve the image of an PDF page.
     * <p>
     * Remember to use {@linkplain #releaseCachedImage(PDPage)} if you specify {@code true} for {@code useCache}.
     *
     * @param page the page.
     * @param useCache whether use and cache generated image.
     * @return the image of the PDF page.
     * @throws IOException something wrong happened.
     */
    public static BufferedImage getPageImage(PDPage page, int dpi, boolean useCache) throws IOException {
        BufferedImage pageImage;
        if (!useCache) {
            pageImage = pageConvertToImage(page, dpi, ImageType.RGB);
        } else {
            return getPageImageCache().getImage(page, dpi);
        }
        return pageImage;
    }

    /**
     * Release cache image if available.
     *
     * @param page the page to release cache image.
     */
    public static void releaseCachedImage(PDPage page) {
        getPageImageCache().release(page);
    }

    /**
     * Retrieve the one-shot image of an PDF page.
     * <p>
     * Only use this method if you ensure you handle page in sequential order.
     *
     * @param page the page.
     * @return the image of the PDF page.
     * @throws IOException something wrong happened.
     */
    public static BufferedImage getOneShotPageImage(PDPage page) throws IOException {
        return getOneShotPageImage(page, DEFAULT_DPI);
    }

    /**
     * Retrieve the one-shot image of an PDF page.
     * <p>
     * Only use this method if you ensure you handle page in sequential order.
     *
     * @param page the page.
     * @return the image of the PDF page.
     * @throws IOException something wrong happened.
     */
    public static BufferedImage getOneShotPageImage(PDPage page, int dpi) throws IOException {
        return getPageImageCache().getOneShotImage(page, dpi);
    }

    /**
     * Clear all cache image.
     */
    public static void clearCachedImage() {
        getPageImageCache().clear();
    }

    private static PDPageImageCache getPageImageCache() {
        return PAGE_IMAGE_CACHE.get();
    }


    private static final class PDPageImageCache {
        private final Map<PDPage, PDPageImageItem> cache = new HashMap<>();

        BufferedImage getImage(PDPage page, int dpi) throws IOException {
            PDPageImageItem item = cache.computeIfAbsent(page, PDPageImageItem::new);
            return item.getImage(dpi);
        }

        BufferedImage getOneShotImage(PDPage page, int dpi) throws IOException {
            PDPageImageItem item = cache.computeIfAbsent(page, PDPageImageItem::new);
            BufferedImage bufferedImage = item.getOneShotImage(dpi);
            // 释放掉所有一次性图像
            cache.forEach((k, v) -> {
                if (!k.equals(page))
                    v.releaseOneShot();
            });
            return bufferedImage;
        }

        void release(PDPage page) {
            synchronized (cache) {
                PDPageImageItem item = cache.get(page);
                if (item != null) {
                    item.release();
                    cache.remove(page);
                }
            }
        }



        void clear() {
            synchronized (cache) {
                cache.forEach((k, v) -> {
                    v.release();
                });
                cache.clear();
            }
        }
    }

    private static final class PDPageImageItem {
        private WeakReference<BufferedImage> cachedPageImageRef;
        private final PDPage page;
        private boolean oneShot;
        private int dpi;

        PDPageImageItem(PDPage page) {
            this.page = page;
        }

        BufferedImage getImage(int dpi) throws IOException {
            return getImage(dpi, false);
        }

        BufferedImage getOneShotImage(int dpi) throws IOException {
            return getImage(dpi, true);
        }

        private BufferedImage getImage(int requiredDpi, boolean oneShot) throws IOException {
            BufferedImage pageImage;
            WeakReference<BufferedImage> pageImageRef = cachedPageImageRef;
            if (pageImageRef != null) {
                pageImage = pageImageRef.get();
            } else {
                pageImage = null;
            }
            if (null == pageImage || requiredDpi > dpi) {
                if (oneShot) {
                    pageImage = renderPageImage(requiredDpi / (float)DEFAULT_DPI);
                } else {
                    pageImage = pageConvertToImage(page, requiredDpi, ImageType.RGB);
                }
                this.dpi = requiredDpi;
                this.oneShot = oneShot;
                cachedPageImageRef = new WeakReference<>(pageImage);
            } else if (this.oneShot && !oneShot) {
                // 如果之前的是一个“一次性”的图像，这里转换为非一次性图像
                BufferedImage newImage = new BufferedImage(pageImage.getWidth(), pageImage.getHeight(), pageImage.getType());
                Graphics2D g = newImage.createGraphics();
                g.drawImage(pageImage, 0, 0, null);
                g.dispose();
                this.oneShot = false;
                cachedPageImageRef = new WeakReference<>(pageImage);
            }
            if (requiredDpi != dpi) {
                // Scale image
                float scale = (float)requiredDpi / (float)dpi;
                int w = (int)(pageImage.getWidth() * scale);
                int h = (int)(pageImage.getHeight() * scale);
                BufferedImage newImage = new BufferedImage(w, h, pageImage.getType());
                Graphics2D g = newImage.createGraphics();
                g.drawImage(pageImage, 0, 0, w, h,null);
                g.dispose();
                return newImage;
            } else {
                return pageImage;
            }
        }

        private BufferedImage renderPageImage(float scale) throws IOException {
            // Yeah, this sucks. But PDFBox 2 wants PDFRenderers to have
            // a reference to a PDDocument (unnecessarily, IMHO)

            PDDocument document = null;
            try {
                document = new PDDocument();
                document.addPage(page);

                PDFRenderer renderer = new PDFRenderer(document);

                PDRectangle cropbBox = page.getCropBox();
                int width = (int) Math.ceil(cropbBox.getWidth() * scale);
                int height = (int) Math.ceil(cropbBox.getHeight() * scale);
                int rotationAngle = page.getRotation();

                BufferedImage image;
                // swap width and height
                if (rotationAngle == 90 || rotationAngle == 270)
                {
                    //noinspection SuspiciousNameCombination
                    image =  GraphicsUtil.createBufferedImageRGB(height, width);
                }
                else
                {
                    image = GraphicsUtil.createBufferedImageRGB(width, height);
                }

                renderer.renderToImage(page, image, scale, ImageType.RGB);
                return image;
            } finally {
                IOUtils.closeQuietly(document);
            }

        }

        void releaseOneShot() {
            if (oneShot) {
                releaseInternal();
            }
        }

        void release() {
            releaseInternal();
        }

        private void releaseInternal() {
            WeakReference<BufferedImage> pageImageRef = cachedPageImageRef;
            if (pageImageRef != null) {
                pageImageRef.clear();
                cachedPageImageRef = null;
            }
            oneShot = false;
        }

    }
}
