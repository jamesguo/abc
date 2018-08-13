package com.abcft.pdfextract.core.table;

import com.abcft.pdfextract.core.PaperParameter;
import com.abcft.pdfextract.core.chart.ChartUtils;
import com.abcft.pdfextract.core.model.*;
import com.abcft.pdfextract.core.model.Rectangle;
import com.abcft.pdfextract.core.util.DebugHelper;
import com.abcft.pdfextract.core.util.GraphicsUtil;
import com.abcft.pdfextract.core.util.NumberUtil;
import com.abcft.pdfextract.util.*;
import com.google.common.collect.Lists;
import org.apache.commons.cli.ParseException;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.math3.util.FastMath;
import org.apache.commons.pool2.ObjectPool;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.color.PDColorSpace;
import org.apache.pdfbox.pdmodel.graphics.color.PDICCBased;
import org.apache.pdfbox.pdmodel.graphics.color.PDIndexed;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.state.PDGraphicsState;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.util.Matrix;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.awt.image.RasterFormatException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.*;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * @author manuel
 */
public final class TableUtils {

    private static final Logger logger = LogManager.getLogger(TableUtils.class);

    static final ClosureInt UNUSED = new ClosureInt();

    private static final double RULING_LENGTH_THRESHOLD = .4f;

    static TextElement makeTextElement(int textBlockId, Rectangle2D visibleBBox, float x, float y, float width, float height,
                                       String fontName, float fontSize, String c, int[] charCodes,
                                       float widthOfSpace, float dir) {
        Rectangle2D.Float bounds = new Rectangle2D.Float(x, y, width, height);
        TextElement element = new TextElement(
                visibleBBox,
                bounds,
                bounds,
                widthOfSpace,
                c,
                charCodes,
                fontName,
                fontSize,
                (int) dir,
                Color.BLACK,
                0
        );
        element.setGroupIndex(textBlockId);
        return element;
    }

    static TextElement makeTextElement(int textBlockId, Rectangle2D visibleBBox, Rectangle2D bounds,
                                       PDFont font, float fontSize, String c, int[] charCodes,
                                       Color color,
                                       float widthOfSpace, int rotate) {
        TextElement element = new TextElement(
                visibleBBox,
                bounds,
                bounds,
                widthOfSpace,
                c,
                charCodes,
                FontUtils.getFontName(font, UNUSED),
                fontSize,
                rotate,
                color,
                0
        );
        element.setGroupIndex(textBlockId);
        return element;
    }

    public static Table rotate(Table table, int degrees) {
        if (90 == degrees) {
            // 向右旋转表格
            // A11 A12 A13         A41 A31 A21 A11
            // A21 A22 A23  ====>  A42 A32 A22 A21
            // A31 A32 A33         A43 A33 A23 A13
            // A41 A42 A43
            // A[r - j -1, i]  =       A'[i, j]
            return rotate(table, (dimen, pos) -> Pair.of(dimen.getLeft() - pos.getRight() - 1, pos.getLeft()));
        } else if (-90 == degrees || 270 == degrees) {
            // 向左旋转表格
            // A11 A12 A13         A13 A23 A33 A43
            // A21 A22 A23  ====>  A12 A22 A32 A42
            // A31 A32 A33         A11 A21 A31 A41
            // A41 A42 A43
            // A[j, c - i - 1] =    A'[i, j]
            return rotate(table, (dimen, pos) -> Pair.of(pos.getRight(), dimen.getRight() - pos.getLeft() - 1));
        } else {
            return table;
        }
    }

    private static Table rotate(Table table, BiFunction<Pair<Integer, Integer>, Pair<Integer, Integer>, Pair<Integer, Integer>> mapper) {
        // mapper(A'[i,j], (rows, columns)) -> A[?,?]
        Table newTable = new Table(table);
        int row = table.getRowCount();
        int column = table.getColumnCount();
        Pair<Integer, Integer> dimen = Pair.of(row, column);
        for (int i = 0; i < column; ++i) {
            for (int j = 0; j < row; ++j) {
                Pair<Integer, Integer> newPos = Pair.of(i, j);
                Pair<Integer, Integer> oldPos = mapper.apply(dimen, newPos);
                Cell cell = table.getCell(oldPos.getLeft(), oldPos.getRight());
                boolean hasCell = newTable.hasCell(i, j);
                if (!hasCell && cell != null) {
                    Cell newCell = new Cell();
                    newCell.setFrame(cell);
                    newCell.setTextElements(cell.getElements());
                    newTable.add(newCell, i, j);
                    for (int k = 1; k < cell.getRowSpan(); ++k) {
                        newTable.addRightMergedCell(newCell, i, j + k);
                    }
                    for (int k = 1; k < cell.getColSpan(); ++k) {
                        newTable.addDownMergedCell(newCell, i + k, j);
                    }
                }
            }
        }
        return newTable;
    }

    private static boolean overlap(double y1, double height1, double y2, double height2, double variance) {
        return FloatUtils.within(y1, y2, variance) || (y2 <= y1 && y2 >= y1 - height1) || (y1 <= y2 && y1 >= y2 - height2);
    }

    public static boolean overlap(double y1, double height1, double y2, double height2) {
        return overlap(y1, height1, y2, height2, 0.1f);
    }

    public static String join(String glue, String... s) {
        int k = s.length;
        if (k == 0) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        out.append(s[0]);
        for (int x = 1; x < k; ++x) {
            out.append(glue).append(s[x]);
        }
        return out.toString();
    }

    public static List<Integer> parsePagesOption(String pagesSpec) throws ParseException {
        if (pagesSpec.equals("all")) {
            return null;
        }

        List<Integer> rv = new ArrayList<>();

        String[] ranges = pagesSpec.split(",");
        for (int i = 0; i < ranges.length; i++) {
            String[] r = ranges[i].split("-");
            if (r.length == 0 || !StringUtils.isNumeric(r[0]) || r.length > 1 && !StringUtils.isNumeric(r[1])) {
                throw new ParseException("Syntax error in page range specification");
            }

            if (r.length < 2) {
                rv.add(Integer.parseInt(r[0]));
            } else {
                int t = Integer.parseInt(r[0]);
                int f = Integer.parseInt(r[1]);
                if (t > f) {
                    throw new ParseException("Syntax error in page range specification");
                }
                rv.addAll(NumberUtil.range(t, f + 1));
            }
        }

        Collections.sort(rv);
        return rv;
    }

    // A260_Property_XXXXX_2016-09-30_MGR Report 中有分栏的情况
    // 开启这个选项，考虑点的远近进行平均化
    private static final boolean ONLY_SNAP_NEIGHBORHOODS = false;
    private static final float NEIGHBORHOODS_THRESHOLD = 1.5f;

    public static void snapPoints(List<? extends Line2D.Float> rulings, float xThreshold, float yThreshold) {

        if (null == rulings || rulings.isEmpty()) {
            return;
        }

        // collect points and keep a Line -> p1,p2 map
        Map<Line2D.Float, Point2D[]> linesToPoints = new HashMap<>();
        List<Point2D> points = new ArrayList<>();
        for (Line2D.Float r : rulings) {
            Point2D p1 = r.getP1();
            Point2D p2 = r.getP2();
            linesToPoints.put(r, new Point2D[]{p1, p2});
            points.add(p1);
            points.add(p2);
        }

        // FIXME 如果页面有分栏的情况，这个算法可能存在导致线段分组错误的问题，需要修复

        List<List<Point2D>> groupedPoints;

        // snap by X
        points.sort(Comparator.comparingDouble(Point2D::getX));

        groupedPoints = new ArrayList<>();
        groupedPoints.add(Lists.newArrayList(points.get(0)));

        // 先根据 X 把各个临近的点分组
        for (Point2D p : points.subList(1, points.size() - 1)) {
            List<Point2D> last = groupedPoints.get(groupedPoints.size() - 1);
            if (Math.abs(p.getX() - calPointsAvgX(last)) < xThreshold) {
                groupedPoints.get(groupedPoints.size() - 1).add(p);
            } else {
                groupedPoints.add(Lists.newArrayList(p));
            }
        }

        // 把所有临近点合并到一起
        for (List<Point2D> group : groupedPoints) {
            if (ONLY_SNAP_NEIGHBORHOODS) {
                // 再根据 Y 坐标，把实际上不相邻的点进一步分组
                group.sort(Comparator.comparingDouble(Point2D::getY));

                List<Point2D> snapPoints = null;
                Point2D prevPoint = null;
                for (Point2D p : group) {
                    // 相邻近的表格才视为一组
                    if (null == prevPoint || Math.abs(p.getY() - prevPoint.getY()) > NEIGHBORHOODS_THRESHOLD) {
                        snapXPoints(snapPoints);
                        snapPoints = Lists.newArrayList(p);
                    } else {
                        snapPoints.add(p);
                    }
                    prevPoint = p;
                }
                snapXPoints(snapPoints);
            } else {
                snapXPoints(group);
            }
        }

        // snap by Y
        points.sort(Comparator.comparingDouble(Point2D::getY));

        groupedPoints = new ArrayList<>();
        groupedPoints.add(Lists.newArrayList(points.get(0)));

        // 先根据 Y 把各个临近的点分组
        for (Point2D p : points.subList(1, points.size() - 1)) {
            List<Point2D> last = groupedPoints.get(groupedPoints.size() - 1);
            if (Math.abs(p.getY() - calPointsAvgY(last)) < yThreshold) {
                groupedPoints.get(groupedPoints.size() - 1).add(p);
            } else {
                groupedPoints.add(Lists.newArrayList(p));
            }
        }

        for (List<Point2D> group : groupedPoints) {
            if (ONLY_SNAP_NEIGHBORHOODS) {
                // 再根据 X 坐标，把实际上不相邻的点进一步分组
                group.sort(Comparator.comparingDouble(Point2D::getX));

                List<Point2D> snapPoints = null;
                Point2D prevPoint = null;
                for (Point2D p : group) {
                    // 相邻近的表格才视为一组
                    if (null == prevPoint || Math.abs(p.getX() - prevPoint.getX()) > NEIGHBORHOODS_THRESHOLD) {
                        snapYPoints(snapPoints);
                        snapPoints = Lists.newArrayList(p);
                    } else {
                        snapPoints.add(p);
                    }
                    prevPoint = p;
                }
                snapYPoints(snapPoints);
            } else {
                snapYPoints(group);
            }
        }

        // finally, modify lines
        for (Map.Entry<Line2D.Float, Point2D[]> ltp : linesToPoints.entrySet()) {
            Point2D[] p = ltp.getValue();
            ltp.getKey().setLine(p[0], p[1]);
        }
    }

    private static void snapXPoints(List<Point2D> group) {
        if (null == group || group.isEmpty()) {
            return;
        }
        float avgLoc = 0;
        for (Point2D p : group) {
            avgLoc += p.getX();
        }
        avgLoc /= group.size();
        for (Point2D p : group) {
            p.setLocation(avgLoc, p.getY());
        }
    }

    private static void snapYPoints(List<Point2D> group) {
        if (null == group || group.isEmpty()) {
            return;
        }
        float avgLoc = 0;
        for (Point2D pt : group) {
            avgLoc += pt.getY();
        }
        avgLoc /= group.size();
        for (Point2D pt : group) {
            pt.setLocation(pt.getX(), avgLoc);
        }
    }

    public static List<Ruling> detectImageDotLine(List<? extends Ruling> dotRulings, float xThreshold, float yThreshold) {
        List<Ruling> mergedRulings = new ArrayList<>();
        if (null == dotRulings || dotRulings.isEmpty()) {
            return mergedRulings;
        }

        //根据X坐标把X坐标相似的线进行分组
        List<List<Ruling>> groupedRulings;
        double neighborThres = 2.0f;
        double rulingMinLength = 2 * Ruling.LINE_WIDTH_THRESHOLD;
        dotRulings.sort(Comparator.comparingDouble(Line2D.Float::getX1));
        groupedRulings = new ArrayList<>();
        groupedRulings.add(Lists.newArrayList(dotRulings.get(0)));
        for (Ruling rul : dotRulings.subList(1, dotRulings.size())) {
            List<Ruling> last = groupedRulings.get(groupedRulings.size() - 1);
            if (Math.abs(rul.getCenterX() - Ruling.calAvgCenterX(last)) < xThreshold) {
                groupedRulings.get(groupedRulings.size() - 1).add(rul);
            } else {
                groupedRulings.add(Lists.newArrayList(rul));
            }
        }

        //根据Y坐标将groupedRulings再次进行分组
        List<List<Ruling>> groupedSplitRulings;
        groupedSplitRulings = new ArrayList<>();
        for (List<Ruling> group : groupedRulings) {
            List<List<Ruling>> splitTemp = new ArrayList<>();
            group.sort(Comparator.comparingDouble(Ruling::getY1));
            List<Ruling> splitRulings = null;
            for (Ruling rul : group) {
                if (splitTemp.isEmpty()) {
                    splitRulings = Lists.newArrayList(rul);
                    splitTemp.add(splitRulings);
                } else {
                    List<Ruling> last = splitTemp.get(splitTemp.size() - 1);
                    Rectangle rulRect = Ruling.getBounds(last);
                    if (rulRect == null) {
                        continue;
                    }
                    double minY = rulRect.getMinY();
                    double maxY = rulRect.getMaxY();
                    if (rul.getCenterY() > minY - neighborThres && rul.getCenterY() < maxY + neighborThres) {
                        splitRulings.add(rul);
                    } else {
                        splitRulings = Lists.newArrayList(rul);
                        splitTemp.add(splitRulings);
                    }
                }
            }
            groupedSplitRulings.addAll(splitTemp);
        }

        for (List<Ruling> rulings : groupedSplitRulings) {
            Rectangle rulRect = Ruling.getBounds(rulings);
            if (rulRect != null && rulings.size() >= 2 && rulRect.getHeight() > rulingMinLength) {
                mergedRulings.add(new Ruling(rulRect.getTop(), (float)rulRect.getCenterX(), 0, (float)rulRect.getHeight()));
            }
        }

        //根据Y坐标把Y坐标相似的线进行分组
        dotRulings.sort(Comparator.comparingDouble(Line2D.Float::getY1));
        groupedRulings = new ArrayList<>();
        groupedRulings.add(Lists.newArrayList(dotRulings.get(0)));
        for (Ruling rul : dotRulings.subList(1, dotRulings.size())) {
            List<Ruling> last = groupedRulings.get(groupedRulings.size() - 1);
            if (Math.abs(rul.getCenterY() - Ruling.calAvgCenterY(last)) < yThreshold) {
                groupedRulings.get(groupedRulings.size() - 1).add(rul);
            } else {
                groupedRulings.add(Lists.newArrayList(rul));
            }
        }

        //根据X坐标将groupedRulings再次进行分组
        groupedSplitRulings = new ArrayList<>();
        for (List<Ruling> group : groupedRulings) {
            List<List<Ruling>> splitTemp = new ArrayList<>();
            group.sort(Comparator.comparingDouble(Ruling::getX1));
            List<Ruling> splitRulings = null;
            for (Ruling rul : group) {
                if (splitTemp.isEmpty()) {
                    splitRulings = Lists.newArrayList(rul);
                    splitTemp.add(splitRulings);
                } else {
                    List<Ruling> last = splitTemp.get(splitTemp.size() - 1);
                    Rectangle rulRect = Ruling.getBounds(last);
                    if (rulRect == null) {
                        continue;
                    }
                    double minX = rulRect.getMinX();
                    double maxX = rulRect.getMaxX();
                    if (rul.getCenterX() > minX - neighborThres && rul.getCenterX() < maxX + neighborThres ) {
                        splitRulings.add(rul);
                    } else {
                        splitRulings = Lists.newArrayList(rul);
                        splitTemp.add(splitRulings);
                    }
                }
            }
            groupedSplitRulings.addAll(splitTemp);
        }

        for (List<Ruling> rulings : groupedSplitRulings) {
            Rectangle rulRect = Ruling.getBounds(rulings);
            if (rulRect != null && rulings.size() >= 2 && rulRect.getWidth() > rulingMinLength) {
                mergedRulings.add(new Ruling((float)rulRect.getCenterY(), rulRect.getLeft(), (float)rulRect.getWidth(), 0));
            }
        }

        return mergedRulings;
    }

    /**
     * 部分虚线在相交位置未正确合并，这里结合邻近点进一步合并lineRulings
     * @param lineRulings ：线ruling
     * @param dotRulings：点ruling
     * @param xThreshold：两条垂直线的水平方向最大间隙
     * @param yThreshold：两条水平线的垂直方向最大间隙
     */
    public static void mergeDotAndLine(List<? extends Ruling> lineRulings, List<? extends Ruling> dotRulings, float xThreshold, float yThreshold) {
        if (dotRulings.isEmpty() || lineRulings.isEmpty()) {
            return;
        }

        for (Ruling lineRuling : lineRulings) {
            for (Ruling dotRuling : dotRulings) {
                if (!lineRuling.getDirection().equals(dotRuling.getDirection())) {
                    continue;
                }
                if (lineRuling.vertical() && FloatUtils.feq(lineRuling.getStart(), dotRuling.getStart(), xThreshold)
                        && lineRuling.nearlyIntersects(dotRuling)) {
                    float minY = FastMath.min(lineRuling.getStart(), dotRuling.getStart());
                    float maxY = FastMath.max(lineRuling.getEnd(), dotRuling.getEnd());
                    lineRuling.setLine(lineRuling.getX1(), minY, lineRuling.getX2(), maxY);
                } else if (lineRuling.horizontal() && FloatUtils.feq(lineRuling.getStart(), dotRuling.getStart(), yThreshold)
                        && lineRuling.nearlyIntersects(dotRuling)) {
                    float minX = FastMath.min(lineRuling.getStart(), dotRuling.getStart());
                    float maxX = FastMath.max(lineRuling.getEnd(), dotRuling.getEnd());
                    lineRuling.setLine(minX, lineRuling.getY1(), maxX, lineRuling.getY2());
                }
            }
        }
    }

    /**
     * Save table area as an image to specified file.
     * <p>
     * Remember to use {@link #releaseCachedImage(Table)} when you no longer need it.
     *
     * @param table the table.
     * @param file the file to save to.
     * @return true if success, false otherwise.
     */
    public static boolean saveTableToPng(Table table, File file) {
        return saveTableToPng(table, file, DebugHelper.DEFAULT_DPI, true);
    }

    /**
     * Save table area as an image to specified file.
     * <p>
     * Remember to use {@link #releaseCachedImage(Table)} when you no longer need it.
     *
     * @param table the table.
     * @param file the file to save to.
     * @return true if success, false otherwise.
     */
    public static boolean saveTableToPng(Table table, File file, int dpi) {
        return saveTableToPng(table, file, dpi,true);
    }

    /**
     * Save table area as an image to specified file.
     * <p>
     * If you set {@code useCache} to true, remember to use {@link #releaseCachedImage(Table)} when you no longer need it.
     *
     * @param table the table.
     * @param file the file to save to.
     * @param useCache {@code true} to generate image cache so that next image generation could be faster.
     * @return true if success, false otherwise.
     */
    private static boolean saveTableToPng(Table table, File file, int dpi, boolean useCache) {
        try (FileOutputStream stream = new FileOutputStream(file)) {
            return saveTableToPng(table, stream, dpi, useCache);
        } catch (IOException e) {
            logger.warn("Failed to save table to png for table " + table + " as file " + file,
                    e);
        }
        return false;
    }

    /**
     * Save table area as an image to specified stream.
     * <p>
     * If you set {@code useCache} to true, remember to use {@link #releaseCachedImage(Table)} when you no longer need it.
     *
     * @param table the table.
     * @param stream the stream to save to.
     * @return true if success, false otherwise.
     */
    public static boolean saveTableToPng(Table table, OutputStream stream) {
        return saveTableToPng(table, stream, DebugHelper.DEFAULT_DPI, true);
    }


    /**
     * Save table area as an image to specified stream.
     * <p>
     * If you set {@code useCache} to true, remember to use {@link #releaseCachedImage(Table)} when you no longer need it.
     *
     * @param table the table.
     * @param stream the stream to save to.
     * @return true if success, false otherwise.
     */
    public static boolean saveTableToPng(Table table, OutputStream stream, int dpi) {
        return saveTableToPng(table, stream, dpi, true);
    }

    /**
     * Retrieve the table as an image.
     * <p>
     * If you set {@code useCache} to true, remember to use {@link #releaseCachedImage(Table)} when you no longer need it.
     *
     * @param table the table.
     * @param useCache {@code true} to generate image cache so that next image generation could be faster.
     * @return true if success, false otherwise.
     */
    public static BufferedImage getTableImage(Table table, int dpi, boolean useCache) {
        if (!table.canSaveImage()) {
            return null;
        }
        try {
            BufferedImage pageImage;
            PDPage page = table.page.getPDPage();
            float scale = (float)dpi / (float)DebugHelper.DEFAULT_DPI;
            if (useCache) {
                // Use one-shot page to save memory, since we might not use it anymore.
                pageImage = getOneShotPageImage(table.page, dpi);
            } else {
                pageImage = DebugHelper.pageConvertToImage(page, dpi, ImageType.RGB);
            }
            if (null == pageImage) {
                return null;
            }
            int x = (int)(scale * table.x);
            int y = (int)(scale * table.y);
            int w = (int)(scale * table.width);
            int h = (int)(scale * table.height);

            BufferedImage tableImage = null;
            if (0 <= x && x + w <= pageImage.getWidth()
                    && 0 <= y && y + h <= pageImage.getHeight()) {
                tableImage = pageImage.getSubimage(x, y, w, h);
            } else {
                if (!table.needCombine && table.getNextTable() != null) {
                    // 前页表格有合并
                    return null;
                } else {
                    logger.warn(String.format("Table area out of the page." +
                                    "Table{id=%s, x=%d, y=%d, w=%d, h=%d} vs " +
                                    "Page{w=%d, h=%d}",
                            table.tableId, x, y, w, h,
                            pageImage.getWidth(), pageImage.getHeight()));
                }
            }
            return tableImage;

        } catch (RasterFormatException | IOException e) {
            logger.warn("Failed to generate table to png for table " + table, e);
            return null;
        }
    }

    /**
     * Save table area as an image to specified stream.
     * <p>
     * If you set {@code useCache} to true, remember to use {@link #releaseCachedImage(Table)} when you no longer need it.
     *
     * @param table the table.
     * @param stream the stream to save to.
     * @param useCache {@code true} to generate image cache so that next image generation could be faster.
     * @return true if success, false otherwise.
     */
    private static boolean saveTableToPng(Table table, OutputStream stream, int dpi, boolean useCache) {
        try {
            BufferedImage tableImgae = getTableImage(table, dpi, useCache);
            if (tableImgae == null) {
                return false;
            }
            ImageIO.write(tableImgae, "PNG", stream);
            return true;
        } catch (RasterFormatException | IOException e) {
            logger.warn("Failed to save table to png for table " + table, e);
            return false;
        }
    }

    /**
     * Retrieve the image of an PDF page.
     * <p>
     * Remember to use {@linkplain #releaseCachedImage(Page)} if you specify {@code true} for {@code useCache}.
     *
     * @param page the page.
     * @param useCache whether use and cache generated image.
     * @return the image of the PDF page.
     * @throws IOException something wrong happened.
     */
    public static BufferedImage getPageImage(Page page, boolean useCache) throws IOException {
        return getPageImage(page, DebugHelper.DEFAULT_DPI, useCache);
    }


    /**
     * Retrieve the image of an PDF page.
     * <p>
     * Remember to use {@linkplain #releaseCachedImage(Page)} if you specify {@code true} for {@code useCache}.
     *
     * @param page the page.
     * @param useCache whether use and cache generated image.
     * @return the image of the PDF page.
     * @throws IOException something wrong happened.
     */
    private static BufferedImage getPageImage(Page page, int dpi, boolean useCache) throws IOException {
        return DebugHelper.getPageImage(page.getPDPage(), dpi, useCache);
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
    public static BufferedImage getOneShotPageImage(Page page) throws IOException {
        return getOneShotPageImage(page, DebugHelper.DEFAULT_DPI);
    }

    /**
     * Retrieve the one-shot image of an PDF page.
     * <p>
     * Only use this method if you ensure you handle page in sequential order.
     *
     * @param page the page.
     * @param dpi the DPI of the image.
     * @return the image of the PDF page.
     * @throws IOException something wrong happened.
     */
    public static BufferedImage getOneShotPageImage(Page page, int dpi) throws IOException {
        return DebugHelper.getOneShotPageImage(page.getPDPage(), dpi);
    }


    /**
     * Release cache image if available.
     *
     * @param table the table to release cache image.
     */
    private static void releaseCachedImage(Table table) {
        releaseCachedImage(table.page);
    }

    /**
     * Release cache image if available.
     *
     * @param page the page to release cache image.
     */
    public static void releaseCachedImage(Page page) {
        DebugHelper.releaseCachedImage(page.getPDPage());
    }


    /**
     * Remove false positive (single column/single row or empty tables).
     *
     * @param tables tables to clean up.
     */
    public static void trimFalsePositiveTables(List<? extends Table> tables) {
        if (null == tables || tables.isEmpty()) {
            return;
        }
        // Remove tables without text
        tables.removeIf(table -> !table.hasText()
                || isTOCTable(table)
                || isNoticeHeaderTable(table)
                || isSpecialNotes(table)
                || isPaginationTable(table));
    }

    private static boolean isSpecialNotes(Table table) {
        if (table.getColumnCount() != 1 || table.getRowCount() != 1) {
            return false;
        }
        String text = table.getCell(0, 0).getText();
        return TableTextUtils.contains(text, TableTextUtils.REPORT_NOTES_RE);
    }

    private static boolean isNoticeHeaderTable(Table table) {
        if (table.getPageNumber() != 1 || table.getRowCount() > 3
                || (table.getColumnCount() != 3 && table.getColumnCount() != 2)) {
            return false;
        }
        String c1 = table.getCell(0, 0).getText();
        String c2 = table.getCell(0, 1).getText();
        boolean partialMatched = (TableTextUtils.matches(c1, TableTextUtils.REPORT_COMPANY_CODE_RE)
                && TableTextUtils.matches(c2, TableTextUtils.REPORT_COMPANY_NAME_RE))
                || (TableTextUtils.matches(c2, TableTextUtils.REPORT_COMPANY_CODE_RE)
                && TableTextUtils.matches(c1, TableTextUtils.REPORT_COMPANY_NAME_RE));
        if (table.getColumnCount() == 3) {
            String c3 = table.getCell(0, 2).getText();
            return partialMatched && TableTextUtils.matches(c3, TableTextUtils.REPORT_NUMBER_RE);
        } else {
            return partialMatched;
        }
    }

    static int xFirstCompare(float arg0X, float arg0Y, float arg1X, float arg1Y) {
        int rv = 0;
        // Compare Y-coordinate first
        //noinspection Duplicates
        if (arg0X > arg1X) {
            rv = 1;
        }
        else if (arg0X < arg1X) {
            rv = -1;
        }
        else if (arg0Y > arg1Y) {
            rv = 1;
        }
        else if (arg0Y < arg1Y) {
            rv = -1;
        }
        return rv;
    }

    static int yFirstCompare(float arg0X, float arg0Y, float arg1X, float arg1Y) {
        int rv = 0;
        // Compare Y-coordinate first
        //noinspection Duplicates
        if (arg0Y > arg1Y) {
            rv = 1;
        }
        else if (arg0Y < arg1Y) {
            rv = -1;
        }
        else if (arg0X > arg1X) {
            rv = 1;
        }
        else if (arg0X < arg1X) {
            rv = -1;
        }
        return rv;
    }

    public static void findAdobeHeaderLine(List<Ruling> rulings, float pageWidth, ClosureBoolean hasHeaderLine, ClosureWrapper<PaperParameter> paperWrapper) {
        if (!hasHeaderLine.get()) {
            PaperParameter currentPaper = paperWrapper.get();

            Ruling headerLine = rulings.stream()
                    .filter(r -> r.getWidth() > Page.HEADER_LINE_FACTOR * pageWidth
                            && PaperParameter.A4_ADOBE.topMarginContains(r.getTop())
                            && r.horizontal() && r.getDrawType() == Ruling.DrawType.LINE)
                    .sorted((r1, r2) -> yFirstCompare(r1.x1, r1.y1, r2.x1, r2.y1))
                    .findFirst().orElse(null);
            if (headerLine != null) {
                paperWrapper.set(currentPaper.withMargins(currentPaper.leftMarginInPt, headerLine.y1,
                        currentPaper.rightMarginInPt, currentPaper.bottomMarginInPt));
                hasHeaderLine.set(true);
            }
        }
    }

    public static void findFooterLine(List<Ruling> rulings, List<TextChunk> textChunks, float pageWidth, ClosureBoolean hasFooterLine, ClosureWrapper<PaperParameter> paperWrapper) {
        if (!hasFooterLine.get()) {
            PaperParameter currentPaper = paperWrapper.get();

            // 先找页面最底部的独立页脚线
            Ruling footerLine = rulings.stream()
                    .filter(r -> r.getWidth() > Page.HEADER_LINE_FACTOR * pageWidth
                            && PaperParameter.A4_ADOBE.bottomMarginContains(r.getBottom())
                            && r.horizontal()
                            && r.getDrawType() == Ruling.DrawType.LINE)
                    .sorted((r1, r2) -> -yFirstCompare(r1.x1, r1.y1, r2.x1, r2.y1))
                    .findFirst().orElse(null);
            if (footerLine != null) {
                paperWrapper.set(currentPaper.withMargins(currentPaper.leftMarginInPt, footerLine.y1,
                        currentPaper.rightMarginInPt, currentPaper.bottomMarginInPt));
                hasFooterLine.set(true);
            } else {
                // 尝试查找色块最低的边框线
                Ruling bottomLine = rulings.stream()
                        .filter(r -> r.getWidth() > Page.HEADER_LINE_FACTOR * pageWidth
                                && FloatUtils.flte(r.getBottom(), currentPaper.heightInPt - 4) && PaperParameter.A4_ADOBE.bottomMarginContains(r.getBottom())
                                && r.horizontal())
                        .sorted((r1, r2) -> -yFirstCompare(r1.x1, r1.y1, r2.x1, r2.y1))
                        .findFirst().orElse(null);
                float bottomY;
                if (bottomLine != null) {
                    bottomY = Math.max(bottomLine.getBottom(), currentPaper.heightInPt - currentPaper.bottomMarginInPt);
                } else {
                    bottomY = currentPaper.heightInPt - currentPaper.bottomMarginInPt;
                }
                List<TextChunk> bottomTexts = textChunks.stream()
                        .filter(textChunk -> FloatUtils.fgte(textChunk.getTop(), bottomY))
                        .collect(Collectors.toList());
                // 确保被框住的文本都是常见页脚文本
                if (bottomTexts.stream().allMatch(textChunk -> likePageFooter(textChunk.getText()))) {
                    paperWrapper.set(currentPaper.withMargins(currentPaper.leftMarginInPt, currentPaper.topMarginInPt,
                            currentPaper.rightMarginInPt, currentPaper.heightInPt - bottomY));
                    hasFooterLine.set(true);
                }
            }
        }
    }

    public static boolean isTableFooter(String footer) {
        return !StringUtils.isBlank(footer)
                && TableTextUtils.contains(footer, TableTextUtils.TABLE_FOOTER_RE);
    }

    public static boolean likePageFooter(String footer) {
        if (StringUtils.isBlank(footer)) {
            return true;
        }
        footer = footer.trim();
        return TableTextUtils.containsAny(footer, TableTextUtils.PAGER_LIKES);
    }

    public static boolean isPageFooter(String footer) {
        return TableTextUtils.containsAny(footer, TableTextUtils.PAGER_RE,
                TableTextUtils.REPORT_HEADER_RE);
    }

    private static boolean isTOCTable(Table table) {
        boolean titleTOC = TableTextUtils.matches(table.getTitle(), TableTextUtils.TOC_TITLE_RE);
        /* TODO 通过检测是否有 ... 这种字符来判断名，但有点麻烦
        if (!titleTOC) {
            int ellipseCount = 0;
            int rowCount = table.getRowCount();
            for(Cell cell : table.getCells()) {
                String text = cell.getText();
                if (StringUtils.containsAny(text, "....", "………", "")) {
                    ++ellipseCount;
                }
            }
            return (float)ellipseCount / rowCount >= 0.7f;
        }
        */
        return titleTOC;
    }

    private static boolean isPaginationTable(Table table) {
        if (table.getRowCount() > 1 || table.getColumnCount() != 2) {
            return false;
        }
        String c1 = table.getCell(0, 0).getText();
        String c2 = table.getCell(0, 1).getText();
        // 排除东方航空如下类型的页脚表格：
        //    -6-     东方航空2017年第三季度报告
        return (TableTextUtils.contains(c1, TableTextUtils.REPORT_HEADER_RE) && TableTextUtils.matches(c2, TableTextUtils.PAGER_RE))
                || (TableTextUtils.contains(c1, TableTextUtils.PAGER_RE) && TableTextUtils.matches(c2, TableTextUtils.REPORT_HEADER_RE));
    }

    /**
     * 判断一个图片是否为单元格线框图片。
     *
     * @param image 待检测的图片。
     * @return true 表示此图疑似为单元格线框图片，false 表示不是。
     */
    static boolean isCellGridImage(PDImage image) {
        int bpc = image.getBitsPerComponent();
        if (1 == bpc) {
            // 单像素图，明显是一个线框图
            return true;
        }/* else if (bpc > 4) {
            // 颜色太丰富，不太像；要么被更早的条件（线宽、高较小）匹配了
            return false;
        }*/
        try {
            PDColorSpace colorSpace = image.getColorSpace();
            if (colorSpace instanceof PDIndexed) {
                // 索引图，明显也是线框图片
                return true;
            }
            if (colorSpace instanceof PDICCBased) {
                PDImageXObject maskImage = ((PDImageXObject)image).getMask();
                if (maskImage != null && maskImage.getBitsPerComponent() == 1) {
                    return true;
                }
            }
            // TODO 考虑内联 RGB 图片的网格线判定和提取
        } catch (IOException e) {
            // It's OK, we don't have to figure it out

        }
        return false;
    }

    /**
     * 从表示单元格线框的图片中提取线框信息。
     *
     * @param imageItem 包含待提取图片的Item
     * @return 由图片中提取的线框（和 bounds 相同的坐标系）。
     */
    static List<Ruling> extractCellGridImageRulings(ImageItem imageItem) {
        List<Ruling> rulings = new ArrayList<>();
        PDImage image = imageItem.getItem();

        // 提取图片中的黑色线或者虚线
        if (1 == image.getBitsPerComponent()) {
            collectRulingsFromCellGridImage(image, imageItem.getImageTransform(), rulings);
        } else if (image instanceof PDImageXObject) {
            try {
                PDImageXObject maskImage = ((PDImageXObject)image).getMask();
                if (maskImage != null) {
                    collectRulingsFromCellGridImage(maskImage, imageItem.getImageTransform(), rulings);
                }
            } catch (IOException e) {
                // It's OK
            }
        } else {
            // TODO 考虑 RGB 图片的网格线提取
        }

        return rulings;

    }

    /**
     * 从带有单元格线框的图片中提取线框信息。
     *
     * @param image 待提取的图片，应当是二值化的图。
     * @param affineTransform 对原始Xobject图片的完整仿射变换，包含尺度缩放、平移以及旋转。
     * @param rulings 用于存放结果的线框列表。
     */
    private static void collectRulingsFromCellGridImage(PDImage image, AffineTransform affineTransform, List<Ruling> rulings) {
        try {

            // 从二值化的图中检测线
            // TODO 如果真是二值化的图，直接读数据是否更加节约内存？

            List<Line2D> lines = detectCellLines(image.getImage());

            // TODO 如果线条太多，证明我们可能找错了图。
            transformCellGridLines(affineTransform, lines, rulings);
        } catch (Throwable e) {
            // It's OK
        }
    }

    private static void transformCellGridLines(AffineTransform at, List<Line2D> lines, List<Ruling> rulings) {
        for (Line2D line : lines) {
            Point2D p1 = line.getP1();
            Point2D p2 = line.getP2();
            at.transform(p1, p1);
            at.transform(p2, p2);
            Ruling ruling = new Ruling(p1, p2);
            ruling.setColor(Color.BLACK); // 目前看来，都是黑色的，后续有变化再说
            ruling.setDashed(true); // 目前看来，用图片绘制的线大多以虚线为主（很可能看起来是实线，但放大后看是虚线）
            ruling.setFill(false);  // 是否视为填充线？好像不太重要
            ruling.setDrawType(Ruling.DrawType.IMAGE_GRID);
            rulings.add(ruling);
        }
    }

    /**
     * 从带有单元格线框的图片中提取线。
     *
     * @param image 待提取的图片，应当是二值化的图。
     * @return 图片中的垂直线或者水平线（图片坐标系）。
     */
    private static List<Line2D> detectCellLines(BufferedImage image) {
        List<Line2D> lines = new ArrayList<>();

        int width = image.getWidth();
        int height = image.getHeight();
        Raster imageData = image.getRaster();
        if (imageData.getNumBands() > 1) {
            return lines;
        }

        // 注意为 List 预分配内存
        // 水平投影统计值
        Map<Integer, List<Integer>> horizontalHist = new HashMap<>(height);
        // 垂直投影统计值
        Map<Integer, List<Integer>> verticalHist = new HashMap<>(width);
        int[] pixels = imageData.getPixels(0, 0, width, height, (int[])null);

        Supplier<List<Integer>> horizontalPixels = () -> new ArrayList<>(width);
        Supplier<List<Integer>> verticalPixels = () -> new ArrayList<>(height);

        // 方法一：采用投影法提取直线
        // 目前默认背景为白色[value=255],前景线为黑色[value=0]
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                // int pixel = imageData.getPixel(x, y, pixelArray)[0];
                int pixel = pixels[row +  x];
                if (0 == pixel) {
                    // 水平投影集
                    StatsUtils.aggregateList(horizontalHist, y, x, horizontalPixels);
                    // 垂直投影集
                    StatsUtils.aggregateList(verticalHist, x, y, verticalPixels);
                }
            }
        }

        // 提取满足条件的水平线
        lines.addAll(calcLinesFromHist(horizontalHist, width, height, true));

        // 提取满足条件的垂直线
        lines.addAll(calcLinesFromHist(verticalHist, width, height, false));

        // 方法二：采用hough变换提取直线
        // TODO 用霍夫变换提取线

        return lines;
    }

    private static List<Line2D> calcLinesFromHist(Map<Integer, List<Integer>> hist, int width, int height, boolean isHorizontal) {
        List<Line2D> lines = new ArrayList<>();
        for (Map.Entry<Integer, List<Integer>> entry : hist.entrySet()) {
            Integer idxKey = entry.getKey();
            List<Integer> idxSet = entry.getValue();
            if (idxSet.size() < 2) {
                continue;
            }
            int firstIdx = idxSet.get(0);
            int lastIdx = idxSet.get(idxSet.size() - 1);
            // 长度要求大于图像宽高的0.8才认为有效
            int setDistance = lastIdx - firstIdx;
            int setDistanceThresh = (int)(isHorizontal ? width*0.8 : height*0.8);
            if (setDistance == 0 || setDistance < setDistanceThresh) {
                continue;
            }

            // 统计点集的间距
            Map<Integer, Integer> gaps = new HashMap<>();
            for (int i=0, j=1; j<idxSet.size(); i++, j++) {
                int gap = idxSet.get(j) - idxSet.get(i);
                gaps.compute(gap, (k,v) -> v != null ? v+1 : 1);
            }
            int smallGapNum = 0;
            List<Integer> smallGaps = new ArrayList<>();
            for (Map.Entry<Integer, Integer> gap : gaps.entrySet()) {
                if (gap.getKey() <= 3) {
                    smallGaps.add(gap.getKey());
                    smallGapNum += gap.getValue();
                }
            }
            // 小间距子集占比较多时，才认为是有效的虚线
            if (smallGapNum < idxSet.size()*0.9) {
                continue;
            }

            //根据有效点集间隔阈值来分割可能存在的多条线
            Collections.reverse(smallGaps);
            int smallGapThresh = smallGaps.get(0);
            int beginIdx = idxSet.get(0), endinIdx = idxSet.get(0);
            for (int i=1; i<idxSet.size(); i++) {
                int currIdx = idxSet.get(i);
                if (currIdx - endinIdx > smallGapThresh) {
                    if (isHorizontal) {
                        lines.add(new Line2D.Float(beginIdx, idxKey, endinIdx, idxKey));
                    } else {
                        lines.add(new Line2D.Float(idxKey, beginIdx, idxKey, endinIdx));
                    }

                    beginIdx = currIdx;
                }
                endinIdx = currIdx;
            }
            if (endinIdx > beginIdx) {
                if (isHorizontal) {
                    lines.add(new Line2D.Float(beginIdx, idxKey, endinIdx, idxKey));
                } else {
                    lines.add(new Line2D.Float(idxKey, beginIdx, idxKey, endinIdx));
                }
            }
        }
        return lines;
    }


    static class PageContext {

        private static final ObjectPool<PageContext> POOL = GenericPooledObjectFactory.createPool(PageContext.class);

        /**
         * Pool an instance of {@link PageContext}.
         *
         * <b>Please set parameters before use.</b>
         *
         * You must return this object with {@link #releasePageContext(PageContext)} after use.
         *
         * @return an instance of {@link PageContext} that can be used temporally.
         */
        static PageContext poolPageContext() {
            PageContext instance;
            try {
                instance = POOL.borrowObject();
            } catch (Exception e) {
                instance = new PageContext();
            }
            return instance;
        }

        static void releasePageContext(PageContext instance) {
            try {
                POOL.returnObject(instance);
            } catch (IllegalStateException e) {
                // It is OK
            } catch (Throwable e) {
                logger.warn("Error returning PageContext.", e);
            }
        }

        int pageNumber;
        TableExtractParameters params;
        float pageWidth;
        float pageHeight;

        PaperParameter paper;

        boolean isPagination;
        boolean isHeader;
        boolean isFooter;

        boolean hasHeaderLine;
        boolean hasFooterLine;

        PageContext setPageNumber(int pageNumber) {
            this.pageNumber = pageNumber;
            return this;
        }

        PageContext setParams(TableExtractParameters params) {
            this.params = params;
            return this;
        }

        PageContext setPageSize(float pageWidth, float pageHeight) {
            this.pageWidth = pageWidth;
            this.pageHeight = pageHeight;
            return this;
        }

        PageContext setPaper(PaperParameter paper) {
            this.paper = paper;
            return this;
        }

        PageContext setPagination(boolean pagination) {
            this.isPagination = pagination;
            return this;
        }

        PageContext setHeaderState(boolean header, boolean hasHeaderLine) {
            this.isHeader = header;
            this.hasHeaderLine = hasHeaderLine;
            return this;
        }

        PageContext setFooterState(boolean footer, boolean hasFooterLine) {
            this.isFooter = footer;
            this.hasFooterLine = hasFooterLine;
            return this;
        }

        private PageContext() {
        }

        @Override
        public String toString() {
            if (params.meta.containsKey("fileId")) {
                return String.format("Id=%s, Page=%d", params.meta.get("fileId"), pageNumber);
            }
            return String.format("Path=%s, Page=%d", params.path, pageNumber);

        }
    }

    static boolean acceptsClipBounds(Rectangle2D bounds, PageContext pathContext) {
        return !pathContext.isPagination
                && bounds.getWidth() > 4f && bounds.getHeight() > 4f
                && !(bounds.getWidth() >= pathContext.pageWidth * 0.7f && bounds.getHeight() >= pathContext.pageHeight * 0.7f);
    }

    static final class DrawPathContext extends PageContext {

        private static final ObjectPool<DrawPathContext> POOL = GenericPooledObjectFactory.createPool(DrawPathContext.class);

        /**
         * Pool an instance of {@link DrawPathContext}.
         *
         * <b>Please set parameters before use.</b>
         *
         * You must return this object with {@link #releaseDrawPathContext(DrawPathContext)} after use.
         *
         * @return an instance of {@link DrawPathContext} that can be used temporally.
         */
        static DrawPathContext poolDrawPathContext() {
            DrawPathContext instance;
            try {
                instance = POOL.borrowObject();
            } catch (Exception e) {
                instance = new DrawPathContext();
            }
            return instance;
        }

        static void releaseDrawPathContext(DrawPathContext instance) {
            try {
                POOL.returnObject(instance);
            } catch (IllegalStateException e) {
                // It is OK
            } catch (Throwable e) {
                logger.warn("Error returning DrawPathContext.", e);
            }
        }

        boolean isFill;
        boolean isDashed;
        GeneralPath path;
        Color strokingColor;
        Color nonStrokingColor;
        Rectangle2D clipPath;
        List<PathInfo> pathInfos;

        DrawPathContext setPath(GeneralPath path) {
            this.path = path;
            return this;
        }

        DrawPathContext setGraphicState(PDGraphicsState state, Matrix initialMatrix) {
            Color strokingColor = GraphicsUtil.getColor(state.getStrokingColor(), initialMatrix);
            Color nonStrokingColor = GraphicsUtil.getColor(state.getNonStrokingColor(), initialMatrix);
            setStrokingColor(strokingColor);
            setNonStrokingColor(nonStrokingColor);
            return this;
        }

        DrawPathContext setFill(boolean fill) {
            isFill = fill;
            return this;
        }

        DrawPathContext setDashed(boolean dashed) {
            isDashed = dashed;
            return this;
        }

        DrawPathContext setStrokingColor(Color strokingColor) {
            this.strokingColor = strokingColor;
            return this;
        }

        DrawPathContext setNonStrokingColor(Color nonStrokingColor) {
            this.nonStrokingColor = nonStrokingColor;
            return this;
        }

        DrawPathContext setClipPath(Rectangle2D clipPath) {
            this.clipPath = clipPath;
            return this;
        }

        DrawPathContext setPathInfos(List<PathInfo> pathInfos) {
            this.pathInfos = pathInfos;
            return this;
        }

        @Override
        DrawPathContext setPageNumber(int pageNumber) {
            return (DrawPathContext) super.setPageNumber(pageNumber);
        }

        @Override
        DrawPathContext setParams(TableExtractParameters params) {
            return (DrawPathContext) super.setParams(params);
        }

        @Override
        DrawPathContext setPageSize(float pageWidth, float pageHeight) {
            return (DrawPathContext) super.setPageSize(pageWidth, pageHeight);
        }

        @Override
        DrawPathContext setPaper(PaperParameter paper) {
            return (DrawPathContext) super.setPaper(paper);
        }

        @Override
        DrawPathContext setPagination(boolean pagination) {
            return (DrawPathContext) super.setPagination(pagination);
        }

        @Override
        DrawPathContext setHeaderState(boolean header, boolean hasHeaderLine) {
            return (DrawPathContext) super.setHeaderState(header, hasHeaderLine);
        }

        @Override
        DrawPathContext setFooterState(boolean footer, boolean hasFooterLine) {
            return (DrawPathContext) super.setFooterState(footer, hasFooterLine);
        }

        private DrawPathContext() {
        }
    }

    private static Ruling.DrawType determineSubPathDrawType(List<PathInfo> pathInfos, ClosureInt subPathIndex) {
        int index = subPathIndex.get();
        if (null == pathInfos || index >= pathInfos.size()) {
            return Ruling.DrawType.RECT;
        }
        PathInfo pathInfo = pathInfos.get(index);

        Rectangle2D bounds = pathInfo.getBounds2D();

        boolean tinyWidth = bounds.getWidth() <= Ruling.LINE_WIDTH_THRESHOLD;
        boolean tinyHeight = bounds.getHeight() <= Ruling.LINE_WIDTH_THRESHOLD;

        if (1 == pathInfo.getLineCount()) {
            return tinyWidth && tinyHeight ? Ruling.DrawType.DOT : Ruling.DrawType.LINE;
        }

        return Ruling.createDrawType(tinyWidth, tinyHeight);
    }

    // 一般的表格，都是一条一条线绘制的，一个单元格至少需要绘制 4 条线
    // 也有如同 Microsoft Print to PDF，每一行的竖线是一起绘制的。
    // 所以这里考虑用矩形绘制一条直线，需要 4 步（不算 CLOSE），
    // 但是，某项生成器可能用一堆小矩形构成线，考虑到页面大约 591pt×842pt，减去页边距，
    // 表格内线大约可能时 400pt（纵向页面）～600pt（横向页面），所以暂且取 600 个点为上限。

    private static final int MAX_SUB_PATH_ACTIONS = 4;
    private static final int MAX_PATH_ACTIONS = 600 * MAX_SUB_PATH_ACTIONS;

    static List<Ruling> pathToRulings(DrawPathContext pathContext) {
        ArrayList<Ruling> rulings = new ArrayList<>();
        GeneralPath path = pathContext.path;

        List<PathInfo> pathInfos = pathContext.pathInfos;
        int totalActionCount = pathInfos.stream().mapToInt(PathInfo::getTotalActionCount).sum();

        if (totalActionCount > MAX_PATH_ACTIONS) {
            logger.warn("Too many actions, might be a graph: {}", pathContext);
            return rulings;
        }

        if (pathContext.isPagination && pathContext.paper != null) {
            // 查找页眉页脚中可能的线，来确定真正的页眉页脚区域
            for (PathInfo pathInfo : pathInfos) {
                if (!pathInfo.isLinear() || pathInfo.getTotalActionCount() > MAX_SUB_PATH_ACTIONS)
                    continue;
                findPaginationLines(pathContext, pathInfo.getBounds2D());
            }
        }

        boolean linear = pathInfos.stream().allMatch(PathInfo::isLinear);

        Rectangle2D bounds = path.getBounds2D();
        // 如果路径中存在非线性操作——这很可能是绘制矢量图，不再处理
        // 同时，页眉页脚区域的其余线不做处理
        if (pathContext.isPagination
                || !linear
                || (bounds.getWidth() <= .0 && bounds.getHeight() <= .0)) {
            logger.debug("Skipped pagination, non-linear or empty path: {}", pathContext);
            return rulings;
        }

        PathIterator pi /*= path.getPathIterator(null)*/;
        float[] c = new float[6];
        int currentSegment;

        // 下面的操作，已经在上面作出了判断。
        // 区别是”第一个操作如果不是 MOVETO 则会被接受，暂且不管，我们看一下是否有这样的问题吧。
        /*
        // skip paths whose first operation is not a MOVETO
        // or contains operations other than LINETO, MOVETO or CLOSE
        if ((pi.currentSegment(c) != PathIterator.SEG_MOVETO)) {
            return rulings;
        }
        pi.next();
        while (!pi.isDone()) {
            currentSegment = pi.currentSegment(c);
            if (currentSegment != PathIterator.SEG_LINETO
                    && currentSegment != PathIterator.SEG_CLOSE
                    && currentSegment != PathIterator.SEG_MOVETO) {
                return rulings;
            }
            pi.next();
        }
        */

        Color color = pathContext.isFill ? pathContext.nonStrokingColor : pathContext.strokingColor;

        // skip the first path operation and save it as the starting position
        float[] first = new float[6];
        pi = path.getPathIterator(null);
        pi.currentSegment(first);
        // last move
        Point2D.Float start_pos = new Point2D.Float(FloatUtils.round(first[0], 2), FloatUtils.round(first[1], 2));
        Point2D.Float last_move = start_pos;
        Point2D.Float end_pos = null;
        Line2D.Float line;
        PointComparator pc = PointComparator.Y_FIRST;
        ClosureInt subPathIndex = new ClosureInt();
        Ruling.DrawType subPathDrawType = determineSubPathDrawType(pathInfos, subPathIndex);

        pi.next();
        while (!pi.isDone()) {
            currentSegment = pi.currentSegment(c);
            switch (currentSegment) {
                case PathIterator.SEG_LINETO:
                    end_pos = new Point2D.Float(c[0], c[1]);
                    if (null == start_pos) {
                        logger.warn("Bad LINETO in path: {}", pathContext);
                        pi.next();
                        continue;
                    }
                    line = pc.compare(start_pos, end_pos) < 0 ? new Line2D.Float(
                            start_pos, end_pos) : new Line2D.Float(end_pos,
                            start_pos);

                    Ruling r = generateRuling(pathContext, bounds, color, line, subPathDrawType);
                    if (r != null) {
                        rulings.add(r);
                    }
                    break;
                case PathIterator.SEG_MOVETO:
                    last_move = new Point2D.Float(c[0], c[1]);
                    end_pos = last_move;
                    break;
                case PathIterator.SEG_CLOSE:
                    // according to PathIterator docs:
                    // "the preceding subpath should be closed by appending a line
                    // segment
                    // back to the point corresponding to the most recent
                    // SEG_MOVETO."
                    if (null == end_pos) {
                        logger.warn("Bad CLOSE in path: {}", pathContext);
                        pi.next();
                        subPathIndex.increment();
                        subPathDrawType = determineSubPathDrawType(pathInfos, subPathIndex);
                        continue;
                    }
                    line = pc.compare(end_pos, last_move) < 0 ? new Line2D.Float(
                            end_pos, last_move) : new Line2D.Float(last_move,
                            end_pos);

                    Ruling ruling = generateRuling(pathContext, bounds, color, line, subPathDrawType);
                    if (ruling != null) {
                        rulings.add(ruling);
                    }
                    subPathIndex.increment();
                    subPathDrawType = determineSubPathDrawType(pathInfos, subPathIndex);
                    break;
            }
            start_pos = end_pos;
            pi.next();
        }
        return rulings;
    }

    private static Ruling generateRuling(DrawPathContext pathContext,
                                         Rectangle2D bounds, Color color,
                                         Line2D.Float line, Ruling.DrawType drawType) {
        if (!line.intersects(pathContext.clipPath)) {
            return null;
        }
        Ruling r = new Ruling(line.getP1(), line.getP2())
                .intersect(pathContext.clipPath);
        r.setBindingBounds(bounds);
        r.setFill(pathContext.isFill);
        r.setDashed(pathContext.isDashed);
        r.setColor(color);
        r.setDrawType(drawType);

        if (!r.oblique() && r.length() > RULING_LENGTH_THRESHOLD) {
            return r;
        }
        return null;
    }

    private static void findPaginationLines(DrawPathContext pathContext, Rectangle2D bounds) {
        // Probably a pagination separator line
        if (bounds.getWidth() >= pathContext.pageWidth * Page.HEADER_LINE_FACTOR && bounds.getHeight() <= Ruling.LINE_WIDTH_THRESHOLD) {
            // Attempts to adjust top/bottom margin
            // TODO 如果页眉页脚通过填充区块背景来区分页面主体内容或者有多条横线，如何确定页眉边线？
            if (pathContext.isHeader && !pathContext.hasHeaderLine
                    && bounds.getY() < PaperParameter.A4.topMarginInPt * 1.5) {
                pathContext.paper = pathContext.paper.withMargins(
                        pathContext.paper.leftMarginInPt,
                        (float) bounds.getY(),
                        pathContext.paper.rightMarginInPt,
                        pathContext.paper.bottomMarginInPt
                );
                pathContext.hasHeaderLine = true;
            } else if (pathContext.isFooter && !pathContext.hasFooterLine
                    && (pathContext.pageHeight - bounds.getY()) < PaperParameter.A4.bottomMarginInPt * 1.5) {
                pathContext.paper = pathContext.paper.withMargins(
                        pathContext.paper.leftMarginInPt,
                        pathContext.paper.topMarginInPt,
                        pathContext.paper.rightMarginInPt,
                        pathContext.pageHeight - (float)bounds.getY()
                );
                pathContext.hasFooterLine = true;
            }
        }
    }

    private static float calPointsAvgX(List<Point2D> points) {
        if (points == null || points.isEmpty()) {
            return -Float.MAX_VALUE;
        }
        float avgX = 0;
        for (Point2D point : points) {
            avgX += point.getX();
        }
        return avgX / points.size();
    }

    private static float calPointsAvgY(List<Point2D> points) {
        if (points == null || points.isEmpty()) {
            return -Float.MAX_VALUE;
        }
        float avgY = 0;
        for (Point2D point : points) {
            avgY += point.getY();
        }
        return avgY / points.size();
    }

    private static float calRulingsAvgX(List<Ruling> rulings) {
        if (rulings == null || rulings.isEmpty()) {
            return -Float.MAX_VALUE;
        }
        float avgX = 0;
        for (Ruling rul : rulings) {
            avgX += rul.getCenterX();
        }
        return avgX / rulings.size();
    }

    private static float calRulingsAvgY(List<Ruling> rulings) {
        if (rulings == null || rulings.isEmpty()) {
            return -Float.MAX_VALUE;
        }
        float avgY = 0;
        for (Ruling rul : rulings) {
            avgY += rul.getCenterY();
        }
        return avgY / rulings.size();
    }

    public static boolean saveTableToDataset(Table table, File dir, String pdfPath) {
        File jpgDir = new File(dir, "JPEGImages");
        File annotationDir = new File(dir, "Annotations");
        if (!jpgDir.isDirectory()) {
            jpgDir.mkdirs();
        }
        if (!annotationDir.isDirectory()) {
            annotationDir.mkdirs();
        }
        String name = DigestUtils.md5Hex(pdfPath + "_" + table.getPageIndex() + "_" + table.getIndex());
        File pngFile = new File(jpgDir, name + ".png");
        File annotationFile = new File(annotationDir, name + ".xml");

        Rectangle2D cropbBox = table.getDrawArea();
        double widthPt = cropbBox.getWidth();
        double heightPt = cropbBox.getHeight();
        double adaptScale = Math.min(2048 / widthPt, 2048 / heightPt);
        adaptScale = Math.min(adaptScale, 1.6);
        int dpi = (int) (adaptScale * DebugHelper.DEFAULT_DPI);
        if (!saveTableToPng(table, pngFile, dpi)) {
            return false;
        }
        if (!saveTableAnnotation(table, annotationFile, pdfPath, adaptScale)) {
            return false;
        }
        return true;
    }

    public static boolean saveTableAnnotation(Table table, File file, String pdfPath, double adaptScale) {
        Rectangle2D cropbBox = table.getDrawArea();
        double widthPt = cropbBox.getWidth();
        double heightPt = cropbBox.getHeight();
        int widthPx = (int) Math.round(widthPt * adaptScale);
        int heightPx = (int) Math.round(heightPt * adaptScale);

        AffineTransform tableTransform = new AffineTransform();
        tableTransform.scale(adaptScale, adaptScale);
        tableTransform.translate(-cropbBox.getX(), -cropbBox.getY());

        Map<String, String> attributes = new HashMap<>();
        attributes.put("pdf", pdfPath);
        attributes.put("page", String.valueOf(table.getPageIndex()));
        attributes.put("filename", file.getName().replace(".xml", ".png"));
        attributes.put("width", String.valueOf(widthPx));
        attributes.put("height", String.valueOf(heightPx));

        List<org.jdom.Element> elements = new ArrayList<>();

        saveTextChunks(table, elements, tableTransform, widthPx, heightPx);

        return ChartUtils.saveAnnotation(elements, file, attributes);
    }

    private static void saveTextChunks(Table table, List<org.jdom.Element> elements,
                                       AffineTransform tableTransform, int widthPx, int heightPx) {
        Rectangle chartBounds = new Rectangle(0, 0, widthPx, heightPx);
        for (Cell cell : table.getCells()) {
            if (cell.isEmpty()) {
                continue;
            }
            for (TextChunk textChunk : table.getPage().getTextChunks(cell)) {
                if (StringUtils.isBlank(textChunk.getText())) {
                    continue;
                }
                Rectangle2D area = textChunk.getBounds();
                area = tableTransform.createTransformedShape(area).getBounds2D();
                area = area.createIntersection(chartBounds);
                elements.add(ChartUtils.buildTextElement(area, "text", textChunk.getText()));
            }
        }
    }

}
