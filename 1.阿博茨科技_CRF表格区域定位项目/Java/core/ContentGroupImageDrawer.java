package com.abcft.pdfextract.core;

import com.abcft.pdfextract.core.model.ContentGroup;
import com.abcft.pdfextract.core.model.ContentGroupDrawable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.pdfbox.contentstream.PDContentStream;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.state.PDGraphicsState;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.rendering.PageDrawer;
import org.apache.pdfbox.rendering.PageDrawerParameters;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;

import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Stack;

/**
 * Allows to draw a ContentGroup as an Image.
 *
 * Created by chzhong on 17-12-15.
 */
public class ContentGroupImageDrawer extends PageDrawer {

    private static Logger logger = LogManager.getLogger(ContentGroupImageDrawer.class);

    private ContentGroupDrawable drawable;
    private List<Object> tokens = new ArrayList<>();
    private PDRectangle cropBox;
    private boolean baseOnForm;
    private boolean drawText = true;


    /**
     * 基于整个 Page 对象
     *
     * @param page
     * @param drawable
     * @throws IOException
     */
    public ContentGroupImageDrawer(PDPage page, ContentGroupDrawable drawable) throws IOException {
        super(new PageDrawerParameters(null, page));
        this.drawable = drawable;
        this.baseOnForm = false;
    }

    public void setDrawText(boolean drawText) {
        this.drawText = drawText;
    }

    /**
     * 基于虚拟Form内容的方式绘制图片
     * @param form
     * @param graphics
     * @param scale
     * @throws IOException
     */
    public void renderToGraphics(PDFormXObject form, Graphics2D graphics, float scale)
            throws IOException
    {
        transform(graphics, this.getPage(), scale);
        //graphics.scale(scale, scale);

        PDRectangle cropBox = form.getBBox();
        graphics.clearRect(0, 0, (int) cropBox.getWidth(), (int) cropBox.getHeight());

        // the end-user may provide a custom PageDrawer
        try {
            drawPage(graphics, form.getBBox());
        } catch (InterruptedIOException e) {
            return;
        }
    }

    /**
     * 基于整个Page内容方式绘制图片 开发中 (后续阶段逐渐弱化 Form 的作用时用)
     * @param graphics
     * @param scale
     * @throws IOException
     */
    public void renderToGraphics(Graphics2D graphics, float scale) throws IOException
    {
        resetPageInfo();
        try {
            transform(graphics, this.getPage(), scale);
            //graphics.scale(scale, scale);
            drawPage(graphics, getPage().getCropBox());
        } catch (IOException e) {
            logger.error("Failed to render ContentGroup as image.", e);
        } finally {
            getPage().setCropBox(cropBox);
        }
    }

    // scale rotate translate
    private void transform(Graphics2D graphics, PDPage page, float scale)
    {
        graphics.scale(scale, scale);

        int rotationAngle = page.getRotation();
        PDRectangle cropBox = page.getCropBox();

        if (rotationAngle != 0)
        {
            float translateX = 0;
            float translateY = 0;
            switch (rotationAngle)
            {
                case 90:
                    translateX = cropBox.getHeight();
                    break;
                case 270:
                    translateY = cropBox.getWidth();
                    break;
                case 180:
                    translateX = cropBox.getWidth();
                    translateY = cropBox.getHeight();
                    break;
            }
            graphics.translate(translateX, translateY);
            graphics.rotate((float) Math.toRadians(rotationAngle));
        }
    }

    @Override
    protected void processStreamOperators(PDContentStream contentStream) throws IOException {
        if (!(contentStream instanceof PDPage)) {
            super.processStreamOperators(contentStream);
            return;
        }

        long startTime = System.currentTimeMillis(), endTime = 0;
        List<COSBase> arguments = new ArrayList<COSBase>();
        tokens.clear();
        ContentGroup contentGroup = drawable.getContentGroup();
        if (contentGroup != null) {
            tokens = contentGroup.getAllContentAndGraphicsStateObject(baseOnForm);
        }
        for (Object token : tokens)
        {
            // 计算当前计算耗时  如果保存图片操作时间超过 30s 则抛异常
            endTime = System.currentTimeMillis();
            if (endTime - startTime > 1000 * 30) {
                logger.info("page process time overtime 30s");
                throw new InterruptedIOException("page process time overtime 30s");
            }
            // 重新设置颜色状态
            if (token instanceof PDGraphicsState) {
                Stack<PDGraphicsState> states = saveGraphicsStack();
                states.pop();
                states.push((PDGraphicsState) token);
                restoreGraphicsStack(states);
            }
            else if (token instanceof COSObject)
            {
                arguments.add(((COSObject) token).getObject());
            }
            else if (token instanceof Operator)
            {
                processOperator((Operator) token, arguments);
                arguments = new ArrayList<COSBase>();
            }
            else
            {
                arguments.add((COSBase) token);
            }
        }
    }

    /**
     * 重置Page的Resources 和 cropBox
     */
    private void resetPageInfo() {
        PDPage page = getPage();
        resetPageResources(page);

        Rectangle2D rect = drawable.getDrawArea();
        cropBox = page.getCropBox();
        page.setCropBox(new PDRectangle((float)rect.getX(), (float)rect.getY(),
                (float)rect.getWidth(), (float)rect.getHeight()));
    }

    /**
     * 将page 中Resources中的FormXObject对象的内部 Font, Pattern 和 ExtGState 等资源数据保存到 page.Resources中
     * @param page
     */
    private void resetPageResources(PDPage page) {
        // 遍历当前Page 对象的 XObject 对象
        PDResources resources = page.getResources();
        for (COSName xObjectName : resources.getXObjectNames()) {
            try {
                PDXObject xObject = resources.getXObject(xObjectName);
                if (xObject instanceof PDFormXObject) {
                    PDResources xObjectResource = ((PDFormXObject) xObject).getResources();
                    int ithXObjectLevel = 1;
                    mergeResource(resources, xObjectResource, ithXObjectLevel);
                }
            } catch (IOException e) {
            }
        } // end for xObjectName
    }

    private void mergeResource(PDResources mergeResources, PDResources xObjectResource, int ithXObjectLevel) {
        try {
            if (xObjectResource == null || ithXObjectLevel >= 5) {
                return;
            }
            for (COSName fontResourceName : xObjectResource.getFontNames()) {
                mergeResources.put(fontResourceName, xObjectResource.getFont(fontResourceName));
            }
            for (COSName extResourceName : xObjectResource.getExtGStateNames()) {
                mergeResources.put(extResourceName, xObjectResource.getExtGState(extResourceName));
            }
            for (COSName subXObjectName : xObjectResource.getXObjectNames()) {
                PDXObject subxObject = xObjectResource.getXObject(subXObjectName);
                if (subxObject instanceof PDFormXObject) {
                    ithXObjectLevel++;
                    mergeResource(mergeResources, ((PDFormXObject)subxObject).getResources(), ithXObjectLevel);
                    ithXObjectLevel--;
                }
                else {
                    mergeResources.put(subXObjectName, xObjectResource.getXObject(subXObjectName));
                }
            }
            for (COSName patternName : xObjectResource.getPatternNames()) {
                mergeResources.put(patternName, xObjectResource.getPattern(patternName));
            }
            for (COSName colorspaceName : xObjectResource.getColorSpaceNames()) {
                mergeResources.put(colorspaceName, xObjectResource.getColorSpace(colorspaceName));
            }
            for (COSName propertyName : xObjectResource.getPropertiesNames()) {
                mergeResources.put(propertyName, xObjectResource.getProperties(propertyName));
            }
            for (COSName shadeName : xObjectResource.getShadingNames()) {
                mergeResources.put(shadeName, xObjectResource.getShading(shadeName));
            }
        } catch (IOException e) {
        }
    }


    @Override
    public void showAnnotation(PDAnnotation annotation) { }

    @Override
    protected void showGlyph(Matrix textRenderingMatrix, PDFont font, int code, String unicode, Vector displacement) throws IOException {
        if (drawText) {
            super.showGlyph(textRenderingMatrix, font, code, unicode, displacement);
        }
    }

}
