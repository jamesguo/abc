package com.abcft.pdfextract.core.table;

import com.abcft.pdfextract.core.PdfExtractContext;
import com.abcft.pdfextract.core.model.Rectangle;
import com.abcft.pdfextract.core.util.DebugHelper;
import com.abcft.pdfextract.util.JvmHelper;
import static com.abcft.pdfextract.util.StringMatchPredict.regex;
import static com.abcft.pdfextract.util.StringMatchPredict.matcher;
import com.google.common.collect.Sets;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.math3.util.FastMath;
import org.apache.logging.log4j.Logger;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Utility class to handle table debug features.
 *
 * <p>
 * All methods but {@link #enableDebug()} does nothing when {@link #isDebug()} is {@code false}.
 */
public final class TableDebugUtils {

    // ======================================================================
    // IMPORTANT: Remember to set this to false before submit your code.    =
    // ======================================================================
    private static boolean debug = false;
    // ======================================================================
    // IMPORTANT: Remember to set above to false before submit your code.   =
    // ======================================================================

    /**
     * 这里设置了禁止输出图片的 label。
     * <p>
     * {@link #debug} 设置为 true 时，如果 label 匹配下面的规则，
     * {@link #writeImage(Page, Collection, Stroke, String)} (及其他 write* 方法）依然不会输出调试图片。
     *
     * 可以通过取消这里面的一部分注释，来达到单独输出某个子模块调试图片的目的，减少代码的改动和不必要的注释。
     * </p>
     */
    private static final Set<Predicate<CharSequence>> BLACKLIST_LABELS = Sets.newHashSet(
            matcher("<PH>")
            , regex("^rulings_.*$")
            , regex("^cells_.*$")
            , regex("^tables_.*$")
            , matcher("text-frames")
            , regex("^content_.*")
            , regex(".*FillArea$")
            //, matcher("tables")
    );

    private static File tableDebugDir;

    public static final BasicStroke STROKE_DASHED_1 = new BasicStroke(1.0f,
            BasicStroke.CAP_BUTT,
            BasicStroke.JOIN_MITER,
            4.0f,  new float[]{ 4.0f }, 0.0f);
    public static final BasicStroke STROKE_DASHED_2 = new BasicStroke(2.0f,
            BasicStroke.CAP_BUTT,
            BasicStroke.JOIN_MITER,
            4.0f,  new float[]{ 4.0f }, 0.0f);

    public static final BasicStroke STROKE_1 = new BasicStroke(1f);
    public static final BasicStroke STROKE_2 = new BasicStroke(2f);

    static {
        tableDebugDir = JvmHelper.getDebugDirectory("tables");
        if (debug) {
            enableDebug();
        }
    }

    public static void enableDebug() {
        debug = true;
        if (!tableDebugDir.exists() && !tableDebugDir.mkdirs()) {
            System.out.println("Failed to create debug directory " + tableDebugDir);
        }
    }

    public static void disableDebug() {
        debug = false;
    }

    public static boolean isDebug() {
        return debug;
    }

    public static File getDebugDirectory() {
        return tableDebugDir;
    }

    private static boolean isBlacklistedLabel(String label) {
        for (Predicate<CharSequence> predicate : BLACKLIST_LABELS) {
            if (predicate.test(label)) {
                return true;
            }
        }
        return false;
    }

    public static void dumpDebugDocumentInfo(TableExtractParameters params, Logger logger) {
        if (!debug || null == logger) {
            return;
        }
        // 文档信息，均可能为 null
        logger.debug("=== Document Information ===");
        logger.debug("Source: {}, Publisher: {}", params.source, params.publisher);
        for (Map.Entry<String, Object> entry : params.meta.entrySet()) {
            logger.debug("{}: {}", entry.getKey(), entry.getValue());
        }
        PdfExtractContext context = params.getExtractContext();
        // PDF 自带的信息，不一定存在，而且并非所有字段都靠谱
        logger.debug("Creator: {}, Producer: {}", context.creator, context.producer);
        if (context.documentInformation != null) {
            logger.debug("PDF Information: {}", context.documentInformation.getCOSObject());
        } else {
            logger.debug("PDF Information: No information object");
        }
        logger.debug("=== End Information ===");
    }

    /**
     * Draw shape on one current cached page image.
     *
     * @param page the page to draw on.
     * @param shape the shape to draw.
     * @param stroke the stroke to use.
     */
    public static void drawShape(Page page, Shape shape, Stroke stroke) {
        drawShapes(page, Collections.singleton(shape), stroke);
    }

    /**
     * Draw shapes on one current cached page image.
     *
     * @param page the page to draw on.
     * @param shapes the shapes to draw.
     */
    public static void drawShapes(Page page, Shape ... shapes) {
        drawShapes(page, Arrays.asList(shapes), null);
    }

    /**
     * Draw shapes on one current cached page image.
     *
     * @param page the page to draw on.
     * @param shapes the shapes to draw.
     */
    public static void drawShapes(Page page, Collection<? extends Shape> shapes) {
        drawShapes(page, shapes, null);
    }

    /**
     * Draw shapes on one current cached page image.
     *
     * @param page the page to draw on.
     * @param shapes the shapes to draw.
     * @param stroke the stroke to use.
     */
    public static void drawShapes(Page page, Collection<? extends Shape> shapes, Stroke stroke) {
        if (!debug) {
            return;
        }
        if (null == stroke) {
            stroke = STROKE_DASHED_1;
        }
        BufferedImage image;
        try {
            image = TableUtils.getOneShotPageImage(page);
            Graphics2D g = (Graphics2D) image.getGraphics();
            DebugHelper.drawShapes(g, shapes, stroke);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Draw shapes on page image and write it to a file.
     *
     * @param page the page to draw on.
     * @param shapes the shapes to draw.
     * @param label the label of the file.
     */
    public static void writeImage(Page page, Collection<? extends Shape> shapes, String label) {
        writeImage(page, shapes, null, label);
    }

    /**
     * Draw shapes on page image and write it to a file.
     *
     * @param page the page to draw on.
     * @param shapes the shapes to draw.
     * @param stroke the stroke to use.
     * @param label the label of the file.
     */
    public static void writeImage(Page page, Collection<? extends Shape> shapes, Stroke stroke, String label) {
        if (!debug || null == shapes || shapes.isEmpty() || isBlacklistedLabel(label)) {
            return;
        }
        if (null == stroke) {
            stroke = STROKE_DASHED_1;
        }
        BufferedImage image;
        try {
            TableUtils.releaseCachedImage(page);
            image = TableUtils.getOneShotPageImage(page);
            Graphics2D g = (Graphics2D) image.getGraphics();
            DebugHelper.resetColorIndex();
            DebugHelper.drawShapes(g, shapes, stroke);
            String fileName = FilenameUtils.getName(page.params.path);
            String outFilePath;
            if (fileName != null) {
                outFilePath = String.format("%s/%s/%03d-%s.png", tableDebugDir, fileName, page.getPageNumber(), label);
            } else {
                outFilePath = String.format("%s/%03d-%s.png", tableDebugDir, page.getPageNumber(), label);
            }
            File outFile = new File(outFilePath);
            FileUtils.forceMkdirParent(outFile);
            ImageIO.write(image, "PNG", outFile);
            TableUtils.releaseCachedImage(page);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Draw cells (with some insets) on page image and write it to a file.
     *
     * @param page the page to draw on.
     * @param cells the cells to draw.
     * @param label the label of the file.
     */
    public static void writeCells(Page page, List<? extends Rectangle2D> cells, String label) {
        writeRectangles(page, cells, 1.5f, label);
    }

    /**
     * Draw rectangles (with optional insets) on page image and write it to a file.
     *
     * @param page the page to draw on.
     * @param rectangles the rectangles to draw.
     * @param insets the insets of the rectangle.
     * @param label the label of the file.
     */
    public static void writeRectangles(Page page, List<? extends Rectangle2D> rectangles, float insets, String label) {
        if (!debug || null == rectangles || rectangles.isEmpty()) {
            return;
        }
        List<Rectangle2D> newRectangles =  new ArrayList<>();
        if (0 == insets) {
            newRectangles.addAll(rectangles);
        } else {
            for ( Rectangle2D rect : rectangles) {
                float left = (float) (rect.getX() + insets);
                float top = (float) (rect.getY() + insets);
                float width = (float)(rect.getWidth() - insets * 2);
                float height = (float)(rect.getHeight() - insets * 2);
                newRectangles.add(new Rectangle(left, top, width, height));
            }
        }
        writeImage(page, newRectangles, STROKE_1, label);
    }

    /**
     * Draw lines on page image and write it to a file.
     *
     * @param page the page to draw on.
     * @param lines the lines to draw.
     * @param label the label of the file.
     */
    public static void writeLines(Page page, List<? extends Line2D> lines, String label) {
        writeImage(page, lines, STROKE_DASHED_1, label);
    }

    /**
     * Draw lines on page image and write it to a file.
     *
     * @param page the page to draw on.
     * @param lines the lines to draw.
     * @param label the label of the file.
     */
    public static void writeSolidLines(Page page, List<? extends Line2D> lines, String label) {
        writeImage(page, lines, STROKE_1, label);
    }

    /**
     * Draw dots on page image and write it to a file.
     *
     * @param page the page to draw on.
     * @param dots the lines to draw.
     * @param label the label of the file.
     */
    public static void writeDots(Page page, List<? extends Shape> dots, String label) {
        writeImage(page, dots, STROKE_2, label);
    }

    /**
     * Draw tables on page image and write it to a file.
     *
     * @param page the page to draw on.
     * @param tables the tables to draw.
     * @param label the label of the file.
     */
    public static void writeTables(Page page, List<? extends Table> tables, String label) {
        if (!debug || null == tables || tables.isEmpty()) {
            return;
        }
        List<Rectangle2D> shapes = new ArrayList<>();
        for (Table table : tables) {
            shapes.add(table.getBounds2D());
        }
        writeImage(page, shapes, STROKE_2, label);
    }

    public static void writeFillAreas(Page page, List<FillAreaGroup> fillAreaGroups, String label) {
        if (!debug || null == fillAreaGroups || fillAreaGroups.isEmpty()) {
            return;
        }
        writeImage(page, fillAreaGroups.stream().map(FillAreaGroup::getGroupRectArea).collect(Collectors.toList()), label);
    }

    /**
     * write image roi to a file.
     */
    public static void writeImageROI(Page page, Collection<? extends Shape> shapes, String label, int dpi, float centerExpand) {
        if (!debug || null == shapes || shapes.isEmpty() || isBlacklistedLabel(label)) {
            return;
        }

        BufferedImage image;
        try {
            TableUtils.releaseCachedImage(page);
            image = TableUtils.getOneShotPageImage(page, dpi);
            float scaleRatio = (float) dpi / (float)DebugHelper.DEFAULT_DPI;
            String fileName = FilenameUtils.getName(page.params.path);
            int i = 1;
            for (Shape shape : shapes) {
                String outFilePath;
                if (fileName != null) {
                    //文件名+页码+序号+label
                    String realFileName = fileName.toLowerCase().replace(".pdf", "");
                    outFilePath = String.format("%s/%s/%s-%03d-%03d-%s.jpg", tableDebugDir, fileName, realFileName
                            , page.getPageNumber(), i, label);
                } else {
                    outFilePath = String.format("%s/%03d-%s.jpg", tableDebugDir, page.getPageNumber(), label);
                }
                File outFile = new File(outFilePath);
                FileUtils.forceMkdirParent(outFile);
                Rectangle2D bound = shape.getBounds2D();
                int x = (int) (FastMath.min(FastMath.max(scaleRatio * bound.getX() - centerExpand, 0), scaleRatio * page.getWidth()));
                int y = (int) (FastMath.min(FastMath.max(scaleRatio * bound.getY() - centerExpand, 0), scaleRatio * page.getHeight()));
                int w = (int) (FastMath.min(FastMath.max(scaleRatio * bound.getWidth() + 2 * centerExpand, 0), scaleRatio * page.getWidth()));
                int h = (int) (FastMath.min(FastMath.max(scaleRatio * bound.getHeight() + 2 * centerExpand, 0), scaleRatio * page.getHeight()));
                ImageIO.write(image.getSubimage(x, y, w, h), "JPG", outFile);
                i++;
            }

            TableUtils.releaseCachedImage(page);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private TableDebugUtils() {}
}
