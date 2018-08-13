package com.abcft.pdfextract.core.chart.parser;

import com.abcft.pdfextract.core.chart.ChartUtils;
import com.abcft.pdfextract.core.chart.model.PathInfo;
import com.abcft.pdfextract.core.chart.model.BarInfo;

import java.awt.geom.GeneralPath;
import java.awt.geom.PathIterator;
import java.awt.geom.Rectangle2D;
import java.util.*;

/**
 * Created by dhu on 2017/5/10.
 */
public class BarParser {
    /**
     * 判断Chart 是否为柱状　即是否为一个或多个有效的矩形区域
     * @param path 路径
     */
    public static List<PathInfo> parseBarPath(GeneralPath path) {
        // 遍历path的点集 判断有效性
        PathIterator iter = path.getPathIterator(null);
        double[] coords = new double[12];
        List<PathInfo> infos = new ArrayList<>();
        double[] xs = new double[4];
        double[] ys = new double[4];
        int count = 0;
        while (!iter.isDone()) {
            switch (iter.currentSegment(coords)) {
                case PathIterator.SEG_MOVETO:
                    if (count == 0) {
                        xs[count] = coords[0];
                        ys[count] = coords[1];
                    } else {
                        return null;
                    }
                    count++;
                    break;

                case PathIterator.SEG_LINETO:
                    if (count == 0) {
                        return null;
                    } else if (count < 4) {
                        xs[count] = coords[0];
                        ys[count] = coords[1];
                        // 下一个点必须和上一个x相等或者y相等
                        if (!ChartUtils.equals(xs[count], xs[count - 1]) && !ChartUtils.equals(ys[count], ys[count - 1])) {
                            return null;
                        }
                    } else {
                        if (count == 4) {
                            // 点数到5个了, 有可能line to到起点
                            if (ChartUtils.equals(coords[0], xs[0]) && ChartUtils.equals(coords[1], ys[0])) {
                                break;
                            } else {
                                return null;
                            }
                        } else {
                            return null;
                        }
                    }
                    count++;
                    break;

                case PathIterator.SEG_CUBICTO:
                    return null;

                case PathIterator.SEG_CLOSE:
                    // 矩形必须是4个点之后close path
                    if (count == 4) {
                        // 最后一个点必须和起点x相等或者y相等
                        if (ChartUtils.equals(xs[0], xs[count - 1]) || ChartUtils.equals(ys[0], ys[count - 1])) {
                            // 添加一个矩形
                            addBar(infos, xs, ys);
                            // 重置计数
                            count = 0;
                            break;
                        } else {
                            return null;
                        }
                    } else {
                        return null;
                    }
                default:
                    return null;
            }
            iter.next();
        }
        return infos;
    }

    private static void addBar(List<PathInfo> list, double[] xs, double[] ys) {
        double minX = min(xs);
        double minY = min(ys);
        double maxX = max(xs);
        double maxY = max(ys);
        boolean vertical = (maxX - minX) < (maxY - minY); // 宽比高小则是竖直的
        BarInfo info = new BarInfo(vertical);
        info.bar = new Rectangle2D.Double(minX, minY, maxX-minX, maxY-minY);
        list.add(info);
    }

    private static double min(double[] values) {
        double min = Double.MAX_VALUE;
        for (double v : values) {
            if (v < min) {
                min = v;
            }
        }
        return min;
    }

    private static double max(double[] values) {
        double max = Double.MIN_VALUE;
        for (double v : values) {
            if (v > max) {
                max = v;
            }
        }
        return max;
    }
}
