package com.abcft.pdfextract.core.chart;

import org.apache.pdfbox.contentstream.PDContentStream;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.image.PDInlineImage;
import org.apache.pdfbox.pdmodel.graphics.pattern.PDTilingPattern;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PageDrawer;
import org.apache.pdfbox.rendering.PageDrawerParameters;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;

/**
 * Created by dhu on 12/01/2017.
 */
public class FormRenderer {

    /**
     * Returns the given page as an RGB image at 72 DPI
     * @param form the form to be converted.
     * @return the rendered page image
     * @throws IOException if the PDF cannot be read
     */
    public BufferedImage renderImage(PDFormXObject form) throws IOException
    {
        return renderImage(form, 1);
    }

    /**
     * Returns the given page as an RGB image at the given scale.
     * A scale of 1 will render at 72 DPI.
     * @param form the form to be converted
     * @param scale the scaling factor, where 1 = 72 DPI
     * @return the rendered page image
     * @throws IOException if the PDF cannot be read
     */
    public BufferedImage renderImage(PDFormXObject form, float scale) throws IOException
    {
        return renderImage(form, scale, ImageType.RGB);
    }

    /**
     * Returns the given page as an RGB image at the given DPI.
     * @param form the form to be converted
     * @param dpi the DPI (dots per inch) to render at
     * @return the rendered page image
     * @throws IOException if the PDF cannot be read
     */
    public BufferedImage renderImageWithDPI(PDFormXObject form, float dpi) throws IOException
    {
        return renderImage(form, dpi / 72f, ImageType.RGB);
    }

    /**
     * Returns the given page as an RGB image at the given DPI.
     * @param form the form to be converted
     * @param dpi the DPI (dots per inch) to render at
     * @param imageType the type of image to return
     * @return the rendered page image
     * @throws IOException if the PDF cannot be read
     */
    public BufferedImage renderImageWithDPI(PDFormXObject form, float dpi, ImageType imageType)
            throws IOException
    {
        return renderImage(form, dpi / 72f, imageType);
    }

    /**
     * Returns the given page as an RGB or ARGB image at the given scale.
     * @param form the form to be converted
     * @param scale the scaling factor, where 1 = 72 DPI
     * @param imageType the type of image to return
     * @return the rendered page image
     * @throws IOException if the PDF cannot be read
     */
    public BufferedImage renderImage(PDFormXObject form, float scale, ImageType imageType)
            throws IOException
    {

        PDRectangle cropbBox = form.getBBox();
        float widthPt = cropbBox.getWidth();
        float heightPt = cropbBox.getHeight();
        int widthPx = Math.round(widthPt * scale);
        int heightPx = Math.round(heightPt * scale);

        // swap width and height
        BufferedImage image = new BufferedImage(widthPx, heightPx, imageType.toBufferedImageType());

        // use a transparent background if the imageType supports alpha
        Graphics2D g = image.createGraphics();
        if (imageType == ImageType.ARGB)
        {
            g.setBackground(new Color(0, 0, 0, 0));
        }
        else
        {
            g.setBackground(Color.WHITE);
        }
        g.clearRect(0, 0, image.getWidth(), image.getHeight());

        transform(g, form, scale);

        // the end-user may provide a custom PageDrawer
        PageDrawer drawer = createFormDrawer(form);
        drawer.drawPage(g, form.getBBox());

        g.dispose();

        return image;
    }

    /**
     * Renders a given page to an AWT Graphics2D instance.
     * @param form the zero-based index of the page to be converted
     * @param graphics the Graphics2D on which to draw the page
     * @throws IOException if the PDF cannot be read
     */
    public void renderToGraphics(PDFormXObject form, Graphics2D graphics) throws IOException
    {
        renderToGraphics(form, graphics, 1);
    }

    /**
     * Renders a given page to an AWT Graphics2D instance.
     * @param form the zero-based index of the page to be converted
     * @param graphics the Graphics2D on which to draw the page
     * @param scale the scale to draw the page at
     * @throws IOException if the PDF cannot be read
     */
    public void renderToGraphics(PDFormXObject form, Graphics2D graphics, float scale)
            throws IOException
    {
        transform(graphics, form, scale);

        PDRectangle cropBox = form.getBBox();
        graphics.clearRect(0, 0, (int) cropBox.getWidth(), (int) cropBox.getHeight());

        // the end-user may provide a custom PageDrawer
        PageDrawer drawer = createFormDrawer(form);
        drawer.drawPage(graphics, form.getBBox());
    }

    // scale rotate translate
    private void transform(Graphics2D graphics, PDFormXObject form, float scale)
    {
        graphics.scale(scale, scale);
    }

    protected PageDrawer createFormDrawer(PDFormXObject form) throws IOException {
        PageDrawerParameters parameters = new PageDrawerParameters(null, new PageWrapper(form));
        return new FormDrawer(parameters);
    }

    private static class FormDrawer extends PageDrawer {

        private boolean inPDTilingPattern = false;
        /**
         * Constructor.
         *
         * @param parameters Parameters for page drawing.
         * @throws IOException If there is an error loading properties from the file.
         */
        public FormDrawer(PageDrawerParameters parameters) throws IOException {
            super(parameters);
        }

        @Override
        protected void processStreamOperators(PDContentStream contentStream) throws IOException {
            if (contentStream instanceof PDTilingPattern) {
                inPDTilingPattern = true;
            }
            super.processStreamOperators(contentStream);
            inPDTilingPattern = false;
        }

        @Override
        public void drawImage(PDImage pdImage) throws IOException {
            // 有些柱状图是用图片来填充的, 允许画这些图片
            if (inPDTilingPattern || pdImage instanceof PDInlineImage) {
                super.drawImage(pdImage);
            }
        }

    }

    public static class PageWrapper extends PDPage {
        private PDFormXObject form;
        public PageWrapper(PDFormXObject form) {
            super(form.getCOSObject());
            this.form = form;
        }

        @Override
        public PDRectangle getCropBox() {
            return form.getBBox();
        }

        @Override
        public PDRectangle getBBox() {
            return form.getBBox();
        }

        @Override
        public boolean hasContents() {
            return true;
        }

        @Override
        public InputStream getContents() throws IOException {
            return form.getContents();
        }

        @Override
        public PDResources getResources() {
            return form.getResources();
        }

    }

}
