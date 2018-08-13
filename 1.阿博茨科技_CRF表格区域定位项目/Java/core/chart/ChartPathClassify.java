package com.abcft.pdfextract.core.chart;

import com.abcft.pdfextract.core.model.PathItem;
import com.abcft.pdfextract.core.util.GraphicsUtil;
import com.abcft.pdfextract.spi.ChartType;
import com.abcft.pdfextract.util.JsonUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.thoughtworks.xstream.XStream;
import smile.classification.*;

import java.awt.geom.*;
import java.io.*;
import java.util.*;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

class PathInfoData {
    public List<Double> data = new ArrayList<>();
    public int label = -1;
    public int groupID = -1;
    public String filePath = "";
    public List<String> types = new ArrayList<>();
    public List<Double> colors = new ArrayList<>();
    public List<Double> boxXs = new ArrayList<>();
    public List<Double> boxYs = new ArrayList<>();
    public JsonObject obj = null;
    PathInfoData() {
    }

    public int getLabel(boolean byVertify) {
        if (!byVertify) {
            return label;
        }
        if (label == 1) {
            boolean hasArc = types.contains(ChartType.PIE_CHART.toString());
            if (hasArc) {
                return -1;
            }
        }
        else if (label == 4 || label == 5) {
            boolean hasColumn = types.contains(ChartType.COLUMN_CHART.toString()) ||
                    types.contains(ChartType.BAR_CHART.toString());
            if (!hasColumn) {
                return -1;
            }
        }
        else if (label == 3) {
            boolean hasLine = types.contains(ChartType.LINE_CHART.toString()) ||
                    types.contains(ChartType.CURVE_CHART.toString());
            if (!hasLine) {
                return -1;
            }
        }
        else if (label == 7) {
            boolean hasArc = types.contains(ChartType.PIE_CHART.toString());
            if (!hasArc) {
                return -1;
            }
        }
        else if (label == 8) {
            boolean hasArea = types.contains(ChartType.AREA_CHART.toString()) ||
                    types.contains(ChartType.AREA_OVERLAP_CHART.toString());
            if (!hasArea) {
                return -1;
            }
        }
        return label;
    }
}

class SubPathData {
    public List<Double> xs = new ArrayList<>();
    public List<Double> ys = new ArrayList<>();
    public List<Integer> ts = new ArrayList<>();
    public GeneralPath path = null;
    public boolean isFill = false;

    public SubPathData(GeneralPath path, boolean isFill) {
        if (path != null) {
            this.path = (GeneralPath) path.clone();
            if (!PathUtils.getPathKeyPtsInfo(path, xs, ys, ts)) {
                this.path = null;
                xs.clear();
                ys.clear();
                ts.clear();
            }
            this.isFill = isFill;
            if (isFill) {
                int n = ts.size();
                double dist = Math.abs(xs.get(0) - xs.get(n - 1)) + Math.abs(ys.get(0) - ys.get(n - 1));
                if (dist > 1E-1 && ts.get(n - 1) != PathIterator.SEG_CLOSE && n >= 2) {
                    xs.add(xs.get(0));
                    ys.add(ys.get(1));
                    ts.add(PathIterator.SEG_CLOSE);
                }
            }
        }
    }

    public Rectangle2D getBound(){
        if (path == null) {
            return null;
        }
        return path.getBounds2D();
    }

    public boolean isSmallXYLine(boolean xDir, double lenBase, double zero) {
        if (path == null) {
            return false;
        }
        Rectangle2D box = getBound();
        double width = box.getWidth();
        double height = box.getHeight();
        if (xDir && width < lenBase && width > zero && height < zero) {
            return true;
        }
        else if (!xDir && width < zero && height > zero && height < lenBase) {
            return true;
        }
        else {
            return false;
        }
    }

    public boolean isLargeXYLine(boolean xDir, double lenBase, double zero) {
        if (path == null) {
            return false;
        }
        Rectangle2D box = getBound();
        double width = box.getWidth();
        double height = box.getHeight();
        if (xDir && width > lenBase && height < zero) {
            return true;
        }
        else if (!xDir && width < zero && height > lenBase) {
            return true;
        }
        else {
            return false;
        }
    }

    public Line2D toXYLine(boolean xLine) {
        if (path == null) {
            return null;
        }
        Rectangle2D box = getBound();
        double xmin = box.getMinX();
        double xmax = box.getMaxX();
        double ymin = box.getMinY();
        double ymax = box.getMaxY();
        double x1 = 0, y1 = 0, x2 = 0, y2 = 0;
        if (xLine) {
            x1 = xmin;
            y1 = 0.5 * (ymin + ymax);
            x2 = xmax;
            y2 = y1;
        }
        else {
            x1 = 0.5 * (xmin + xmax);
            y1 = ymin;
            x2 = x1;
            y2 = ymax;
        }
        return new Line2D.Double(x1, y1, x2, y2);
    }

    public boolean overlapBox(Rectangle2D box) {
        Rectangle2D pBox = getBound();
        double dx = Math.abs(pBox.getWidth() - box.getWidth());
        double dy = Math.abs(pBox.getHeight() - box.getHeight());
        if (dx > 0.1 || dy > 0.1) {
            return false;
        }
        GraphicsUtil.extendRect(pBox, 0.01);
        GraphicsUtil.extendRect(box, 0.01);
        Point2D pt1 = new Point2D.Double(pBox.getCenterX(), pBox.getCenterY());
        Point2D pt2 = new Point2D.Double(box.getCenterX(), box.getCenterY());
        if (!pBox.contains(pt2) || !box.contains(pt1)) {
            return false;
        }
        else {
            return true;
        }
    }

    public static List<SubPathData> splitContentItem(PathItem pathItem, boolean splitMoveTo) {
        List<SubPathData> subPathDatas = new ArrayList<>();
        boolean isFill = pathItem.isFill();
        GeneralPath path = pathItem.getItem();
        List<GeneralPath> subPaths = PathUtils.splitSubPath(path, splitMoveTo);
        for (int i = 0; i < subPaths.size(); i++) {
            subPathDatas.add(new SubPathData(subPaths.get(i), isFill));
        }
        return subPathDatas;
    }

    public boolean isBox() {
        if (path == null) {
            return false;
        }
        List<Double> box = new ArrayList<>();
        return PathClassifyUtils.isBox(xs, ys, box);
    }

    public boolean isValidBar(boolean isVertical, double lenBase) {
        if (!isBox()) {
            return false;
        }
        double len = 0.0;
        if (isVertical) {
            len = Collections.max(xs) - Collections.min(xs);
        }
        else {
            len = Collections.max(ys) - Collections.min(ys);
        }
        if (len > lenBase) {
            return false;
        }
        else {
            return true;
        }
    }

    public boolean isArea(double areaBase) {
        if (path == null || !isFill) {
            return false;
        }
        double area = PathClassifyUtils.getFillPathArea(xs, ys);
        if (area < areaBase) {
            return false;
        }
        else {
            return true;
        }
    }

    public com.abcft.pdfextract.core.chart.model.PathInfo.PathType getLineType() {
        if (path == null || ts.isEmpty()) {
            return com.abcft.pdfextract.core.chart.model.PathInfo.PathType.UNKNOWN;
        }
        if (ts.contains(PathIterator.SEG_CUBICTO)) {
            return com.abcft.pdfextract.core.chart.model.PathInfo.PathType.CURVE;
        }
        else {
            return com.abcft.pdfextract.core.chart.model.PathInfo.PathType.LINE;
        }
    }

    public GeneralPath resetLine(Rectangle2D box) {
        if (path == null || ts.isEmpty()) {
            return null;
        }
        if (isFill) {
            // 判断是否为填充形式的折线对象  如果是则抽出其上边界  并保存为GeneralPath
            GeneralPath pathNew = ChartPathInfosParser.isFilledLine(path, box);
            if (pathNew == null) {
                pathNew = ChartPathInfosParser.isFilledDashLine(path, box);
            }
            if (pathNew != null) {
                return pathNew;
            }
            else {
                return null;
            }
        }
        return (GeneralPath) path.clone();
    }
}

class PathClassifyUtils {

    public static int isXYDirline(List<Double> xs, List<Double> ys) {
        if (xs.size() != ys.size() || xs.size() != 2) {
            return -1;
        }
        double dx = Math.abs(xs.get(1) - xs.get(0));
        double dy = Math.abs(ys.get(1) - ys.get(0));
        double zero = 1E-2;
        if (dx < zero && dy > zero) {
            return 1;
        }
        else if (dx > zero && dy < zero) {
            return 0;
        }
        else {
            return -1;
        }
    }

    public static boolean isBox(List<Double> xs, List<Double> ys, List<Double> box) {
        box.clear();
        double zero = 1E-2;
        int n = xs.size();
        int num_x = 0, num_y = 0;
        double x1 = 0, x2  = 0, y1 = 0, y2 = 0, dx = 0, dy = 0;
        for (int i = 0; i < n - 1; i++) {
            x1 = xs.get(i);
            y1 = ys.get(i);
            x2 = xs.get(i + 1);
            y2 = ys.get(i + 1);
            dx = Math.abs(x2 - x1);
            dy = Math.abs(y2 - y1);
            if (dx < zero && dy > zero) {
                num_y++;
            }
            else if (dx > zero && dy < zero) {
                num_x++;
            }
            else if (dx > zero && dy > zero) {
                return false;
            }
        } // end for i
        if (num_x == 2 && num_y == 2) {
            box.add(Collections.min(xs));
            box.add(Collections.max(xs));
            box.add(Collections.min(ys));
            box.add(Collections.max(ys));
            return true;
        }
        else {
            return false;
        }
    }

   public static PathInfoData read_json(JsonObject obj, String objPath, boolean trainModel) {
        PathInfoData data = new PathInfoData();
        // 读取验证状态信息
        int verify = obj.get("verify").getAsInt();
        if (verify == -1) {
            return data;
        }
        // other类型特殊处理
        int lable = obj.get("label").getAsInt();
        if (verify != 1 && lable == 9) {
            return data;
        }

        // 读取path所在chart内部path类别信息
        if (!obj.has("chart_type")) {
            return data;
        }
        JsonElement chart_types = obj.get("chart_type");
        if (chart_types.isJsonNull()) {
            //System.out.println(file.getAbsolutePath());
            return data;
        }
        JsonArray types = chart_types.getAsJsonArray();
        if (types.size() == 0) {
            //System.out.println(file.getAbsolutePath());
            return data;
        }
        int hasLine = 0;
        int hasAREA = 0;
        int hasBAR = 0;
        int hasPIE = 0;
        for (JsonElement type : types) {
            String ele = type.getAsString();
            data.types.add(ele);
            switch (ele) {
                case "LINE_CHART":
                case "CUREV_CHART":
                    hasLine = 1;
                    break;
                case "AREA_CHART":
                case "AREA_OVERLAP_CHART":
                    hasAREA = 1;
                    break;
                case "BAR_CHART":
                case "COLUMN_CHART":
                    hasBAR = 1;
                    break;
                case "PIE_CHART":
                    hasPIE = 1;
                    break;
                default:
                    break;
            }
        } // end for type
        // 保存Chart内部Path类型信息
        List<Double> types_status = new ArrayList<>();
        types_status.add(hasLine * 1.0);
        types_status.add(hasAREA * 1.0);
        types_status.add(hasBAR * 1.0);
        types_status.add(hasPIE * 1.0);

        // 读取颜色信息
        JsonArray color = obj.get("color").getAsJsonArray();
        double r = color.get(0).getAsInt() / 255.0;
        double g = color.get(1).getAsInt() / 255.0;
        double b = color.get(2).getAsInt() / 255.0;
        List<Double> color_status = new ArrayList<>();
        color_status.add(r);
        color_status.add(g);
        color_status.add(b);
        data.colors = color_status;

        // 读取path点集信息
        double x = 0.0, y = 0.0;
        int ptType = 0;
        JsonArray paths = obj.get("path").getAsJsonArray();
        List<Double> xsPath = new ArrayList<>();
        List<Double> ysPath = new ArrayList<>();
        List<Integer> ptTypes = new ArrayList<>();
        List<Double> xsSeg = new ArrayList<>();
        List<Double> ysSeg = new ArrayList<>();
        List<Double> xyLineXs = new ArrayList<>();
        List<Double> xyLineYs = new ArrayList<>();
        List<Integer> xyLineTypes = new ArrayList<>();
        int nptsSeg = 0;
        int xyLineType = 0;
        int notXYLineCount = 0;
        double areaSum = 0.0;
        boolean isFill = false;
        List<Double> boxPts = new ArrayList<>();
        for (int i = 0; i < paths.size(); i++) {
            JsonObject path = (JsonObject) paths.get(i);
            isFill = path.has("fill");
            String op = isFill ? "fill" : "stroke";
            JsonArray pts = path.get(op).getAsJsonArray();
            if (pts.size() == 0) {
                continue;
            }
            for (JsonElement pt : pts) {
                JsonArray point = pt.getAsJsonArray();
                x = point.get(0).getAsDouble();
                y = point.get(1).getAsDouble();
                ptType = point.get(2).getAsInt();
                if (ptType == 0) {
                    nptsSeg = xsSeg.size();
                    if (!isFill && nptsSeg == 2) {
                        xyLineType = isXYDirline(xsSeg, ysSeg);
                        if (xyLineType != -1) {
                            xyLineXs.addAll(xsSeg);
                            xyLineYs.addAll(ysSeg);
                            xyLineTypes.add(xyLineType);
                        }
                        else {
                            notXYLineCount++;
                        }
                    }
                    if (isFill && nptsSeg >= 3) {
                        areaSum += getFillPathArea(xsSeg, ysSeg);
                        if (isBox(xsSeg, ysSeg, boxPts)) {
                            if (data.types.contains("BAR_CHART") ||
                                    data.types.contains("COLUMN_CHART")) {
                                data.boxXs.add(boxPts.get(0));
                                data.boxXs.add(boxPts.get(1));
                                data.boxYs.add(boxPts.get(2));
                                data.boxYs.add(boxPts.get(3));
                            }
                        }
                    }
                    xsSeg.clear();
                    ysSeg.clear();
                }
                xsSeg.add(x);
                ysSeg.add(y);
                xsPath.add(x);
                ysPath.add(y);
                ptTypes.add(ptType);
            } // end for pt
            nptsSeg = xsSeg.size();
            if (!isFill && nptsSeg == 2) {
                xyLineType = isXYDirline(xsSeg, ysSeg);
                if (xyLineType != -1) {
                    xyLineXs.addAll(xsSeg);
                    xyLineYs.addAll(ysSeg);
                    xyLineTypes.add(xyLineType);
                }
                else {
                    notXYLineCount++;
                }
            }
            if (isFill && nptsSeg >= 3) {
                areaSum += getFillPathArea(xsSeg, ysSeg);
                if (isBox(xsSeg, ysSeg, boxPts)) {
                    if (data.types.contains("BAR_CHART") ||
                            data.types.contains("COLUMN_CHART")) {
                        data.boxXs.add(boxPts.get(0));
                        data.boxXs.add(boxPts.get(1));
                        data.boxYs.add(boxPts.get(2));
                        data.boxYs.add(boxPts.get(3));
                    }
                }
            }
            xsSeg.clear();
            ysSeg.clear();
        } // end for i

        // 读取位置信息
        JsonObject box = obj.getAsJsonObject("box");
        double cscale = box.get("scale").getAsDouble();
        double cwidth = box.get("width").getAsDouble() * cscale;
        double cheight = box.get("height").getAsDouble() * cscale;

        int numXYLine = xyLineTypes.size();
        int numXLongLine = 0;
        int numXShortLine = 0;
        int numYLongLine = 0;
        int numYShortLine = 0;
        if (notXYLineCount == 0 && numXYLine >= 1) {
            double dist = 0.0;
            for (int i = 0; i < numXYLine; i++) {
                xyLineType = xyLineTypes.get(i);
                if (xyLineType == 0) {
                    dist = Math.abs(xyLineXs.get(2 * i + 1) - xyLineXs.get(2 * i));
                    if (dist > 0.3 * cwidth) {
                        numXLongLine++;
                    }
                    else {
                        numXShortLine++;
                    }
                }
                else {
                    dist = Math.abs(xyLineYs.get(2 * i + 1) - xyLineYs.get(2 * i));
                    if (dist > 0.3 * cheight) {
                        numYLongLine++;
                    }
                    else {
                        numYShortLine++;
                    }
                }
            }
        }

        // 保存填充类型状态
        List<Double> fill_status = new ArrayList<>();
        if (isFill) {
            fill_status.add(1.0);
        }
        else {
            fill_status.add(0.0);
        }

        // 保存面积
        areaSum /= (cwidth * cheight);
        List<Double> area_status = new ArrayList<>();
        area_status.add(areaSum);

        // 读取样式信息
        double lineSize = obj.get("line_size").getAsDouble();
        int linePattern = obj.get("line_pattern").getAsInt();
        List<Double> line_status = new ArrayList<>();
        line_status.add(lineSize);
        line_status.add(linePattern * 1.0);

        // 计算path空间尺寸信息
        double xmin = Collections.min(xsPath);
        double xmax = Collections.max(xsPath);
        double ymin = Collections.min(ysPath);
        double ymax = Collections.max(ysPath);
        xmin = (xmin - 0.5 * cwidth) / cwidth;
        ymin = (ymin - 0.5 * cheight) / cheight;
        xmax = (xmax - 0.5 * cwidth) / cwidth;
        ymax = (ymax - 0.5 * cheight) / cheight;
        // 测试　控制大小
        /*
        xmin = xmin < 0.0 ? 0.0 : xmin;
        xmax = xmax > 1.0 ? 1.0 : xmax;
        ymin = ymin < 0.0 ? 0.0 : ymin;
        ymax = ymax > 1.0 ? 1.0 : ymax;
        */
        double width = xmax - xmin;
        double height = ymax - ymin;
        List<Double> box_status = new ArrayList<>();
        box_status.add(xmin);
        box_status.add(ymin);
        box_status.add(width);
        box_status.add(height);

        // 统计点集类型信息
        int n = ptTypes.size();
        int num_m = 0, num_l = 0, num_q = 0, num_c = 0, num_h = 0;
        for (Integer type : ptTypes) {
            switch (type) {
                case 0:
                    num_m++;
                    break;
                case 1:
                    num_l++;
                    break;
                case 2:
                    num_q++;
                    break;
                case 3:
                    num_c++;
                    break;
                case 4:
                    num_h++;
                    break;
            }
        } // end for i
        // 测试 控制大小
       /*
        num_m = num_m > 10000 ? 10000 : num_m;
        num_l = num_l > 10000 ? 10000 : num_l;
        num_q = num_q > 10000 ? 10000 : num_q;
        num_c = num_c > 10000 ? 10000 : num_c;
        num_h = num_h > 10000 ? 10000 : num_h;
        */
        // 保存点集类型信息
        List<Double> point_status = new ArrayList<>();
        point_status.add(num_m * 1.0);
        point_status.add(num_l * 1.0);
        point_status.add(num_q * 1.0);
        point_status.add(num_c * 1.0);
        point_status.add(num_h * 1.0);

        // 统计path所有字段在XY方向上变化情况
        int num_dx_zero = 0, num_dx_nzero = 0, num_dy_zero = 0, num_dy_nzero = 0;
        double dx = 0.0, dy = 0.0, zero = 1.E-2;
        for (int i = 0; i < n - 1; i++) {
            if (ptTypes.get(i + 1) == 0) {
                continue;
            }
            dx = Math.abs(xsPath.get(i + 1) - xsPath.get(i));
            dy = Math.abs(ysPath.get(i + 1) - ysPath.get(i));
            if (dx < zero && dy < zero) {
                continue;
            }
            if (dx < zero) {
                num_dx_zero += 1;
            }
            else {
                num_dx_nzero += 1;
            }
            if (dy < zero) {
                num_dy_zero += 1;
            }
            else {
                num_dy_nzero += 1;
            }
        } // end for i

        // 保存子线段在XY方向变化信息
        List<Double> dxdy_status = new ArrayList<>();
        dxdy_status.add(num_dx_zero * 1.0);
        dxdy_status.add(num_dx_nzero * 1.0);
        dxdy_status.add(num_dy_zero * 1.0);
        dxdy_status.add(num_dy_nzero * 1.0);

        // 读取计算的额外属性
        int xyLongLine = 0;
        if (obj.has("xy_long_line")) {
            JsonElement xyLongLineEle = obj.get("xy_long_line");
            xyLongLine = xyLongLineEle.isJsonNull() ? 0: xyLongLineEle.getAsInt();
            if (xyLongLine == 0 && numXLongLine + numYLongLine >= 4) {
                xyLongLine = 1;
            }
        }
        int sameColorAndSize = 0;
        if (obj.has("same_color_size")) {
            JsonElement sameColorAndSizeEle = obj.get("same_color_size");
            sameColorAndSize = sameColorAndSizeEle.isJsonNull() ? 0: sameColorAndSizeEle.getAsInt();
        }
        int sameColorSmallPath = 0;
        if (obj.has("same_color_small_path")) {
            JsonElement sameColorSmallPathEle = obj.get("same_color_small_path");
            sameColorSmallPath = sameColorSmallPathEle.isJsonNull() ? 0 : sameColorSmallPathEle.getAsInt();
        }
        int samePositionSize = 0;
        if (obj.has("same_position_size")) {
            JsonElement samePositionSizeEle = obj.get("same_position_size");
            samePositionSize = samePositionSizeEle.isJsonNull() ? 0 : samePositionSizeEle.getAsInt();
            // 测试　控制大小
            //samePositionSize = samePositionSize > 200 ? 200 : sameColorAndSize;
        }

        List<Double> other_status = new ArrayList<>();
        other_status.add(xyLongLine * 1.0);
        other_status.add(sameColorAndSize * 1.0);
        other_status.add(sameColorSmallPath * 1.0);
        other_status.add(samePositionSize * 1.0);

        data.data.addAll(types_status); // 0 - 3
        data.data.addAll(color_status); // 4 - 6
        data.data.addAll(fill_status);  // 7 - 7
        data.data.addAll(area_status);  // 8 - 8
        data.data.addAll(point_status); // 9 - 13
        data.data.addAll(dxdy_status);  // 14 - 17
        data.data.addAll(line_status);  // 18 - 19
        data.data.addAll(box_status);   // 20 - 23
        data.data.addAll(other_status); // 24 - 27

        //System.out.println(data.data);

        // 路径信息
        data.filePath = objPath;
        //System.out.println(data.filePath);

        // 读取类型信息
        if (lable >= 5) {
            lable = lable - 2;
        }
        else {
            lable = lable - 1;
        }
        data.label = lable;
        if (!trainModel) {
            data.obj = obj;
        }
        return data;
    }

    public static double getFillPathArea(List<Double> xs, List<Double> ys) {
        if (xs.size() <= 2 || xs.size() != ys.size()) {
            return 0.0;
        }
        // 计算封闭区域的面积　折线对象面积通常很小
        double area = 0.0;
        double x1 = 0.0, y1 = 0.0, x2 = 0.0, y2 = 0.0;
        xs.add(xs.get(0));
        ys.add(ys.get(0));
        for (int i = 0; i < xs.size() - 1; i++) {
            x1 = xs.get(i);
            y1 = ys.get(i);
            x2 = xs.get(i + 1);
            y2 = ys.get(i + 1);
            area += 0.5 * (x1 * y2 - x2 * y1);
        }
        return Math.abs(area);
    }

    public static boolean minMaxScaler(double [] X, List<Double> featureRange) {
        int n = 28;
        if (X == null || featureRange == null || featureRange.size() != 2 * n) {
            return false;
        }
        double xmin = 0.0, xmax = 0.0, x = 0.0;
        for (int i = 0; i < n; i++) {
            xmin = featureRange.get(2 * i);
            xmax = featureRange.get(2 * i + 1);
            x = X[i];
            x = (x - xmin) / (xmax - xmin);
            x = x < 0.0 ? 0.0 : x;
            x = x > 1.0 ? 1.0 : x;
            X[i] = x;
        } // end for i
        return true;
    }

    public static JsonObject minMaxScaler(double [][] X) {
        int n = X[0].length;
        JsonObject ranges = new JsonObject();
        List<Double> featureRanges = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            minMaxScaler(X, i, featureRanges);
        } // end for i
        JsonArray array = new JsonArray();
        for (int i = 0; i < n; i++) {
            array.add(featureRanges.get(2 * i));
            array.add(featureRanges.get(2 * i + 1));
        }
        ranges.addProperty("feature_num", n);
        ranges.add("featrue_range", array);
        return ranges;
    }

    public static void minMaxScaler(double [][] X, int ith, List<Double> ranges) {
        double xmin = X[0][ith], xmax = X[0][ith];
        for (int j = 0; j < X.length; j++) {
            double x = X[j][ith];
            xmin = x > xmin ? xmin : x;
            xmax = x < xmax ? xmax : x;
        } // end for j
        if (xmax - xmin <= 1E-6) {
            xmax = xmin + 1E-2;
        }
        for (int j = 0; j < X.length; j++) {
            double x = X[j][ith];
            x = (x - xmin) / (xmax - xmin);
            X[j][ith] = x;
        } // end for j
        ranges.add(xmin);
        ranges.add(xmax);
        System.out.println(ith + " ith feature   min : " + xmin + "   max : " + xmax);
    }

    public static int compSamePositionCount(double x1, double x2, List<Double> xs) {
        int count = 0;
        int n = xs.size() / 2;
        if (n == 0) {
            return count;
        }
        double zero = 1.0, x3 = 0.0, x4 = 0.0, distMin = 0.0;
        List<Double> dists = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            x3 = xs.get(2 * i);
            x4 = xs.get(2 * i + 1);
            dists.clear();
            dists.add(Math.abs(x3 - x1));
            dists.add(Math.abs(x4 - x1));
            dists.add(Math.abs(x3 - x2));
            dists.add(Math.abs(x4 - x2));
            distMin = Collections.min(dists);
            if (distMin < zero) {
                count++;
            }
        } // end for i
        return count;
    }

    public static boolean hasSameSizeBox(List<Double> xs1, List<Double> xs2) {
        List<Double> xs = new ArrayList<>();
        if (xs1 != null) {
            xs.addAll(xs1);
        }
        if (xs2 != null) {
            xs.addAll(xs2);
        }
        int n = xs.size() / 2;
        if (n <= 1) {
            return false;
        }
        double boxX1 = 0, boxX2 = 0, y1 = 0, y2 = 0, mean = 0.0;
        List<Double> wh = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            boxX1 = xs.get(2 * i);
            boxX2 = xs.get(2 * i + 1);
            wh.add(boxX2 - boxX1);
            mean += (boxX2 - boxX1);
        } // end for i
        mean /= n;
        double dwh = 0;
        for (int i = 0; i < n; i++) {
            dwh = Math.abs(wh.get(i) - mean);
            if (dwh / mean < 0.01 || dwh < 0.1) {
                return true;
            }
        }
        return false;
    }

    public static int compSamePositionCount(List<Double> xs1, List<Double> xs2) {
        int n = xs1.size() / 2;
        int count = 0;
        List<Integer> counts = new ArrayList<>();
        double x1 = 0.0, x2 = 0.0;
        for (int i = 0; i < n; i++) {
            List<Double> xs = new ArrayList<>();
            xs.addAll(xs2);
            x1 = xs1.get(2 * i);
            x2 = xs1.get(2 * i + 1);
            for (int j = 0; j < n; j++) {
                if (j == i) {
                    continue;
                }
                xs.add(xs1.get(2 * j));
                xs.add(xs1.get(2 * j + 1));
            } // end for j
            count = compSamePositionCount(x1, x2, xs);
            counts.add(count);
        } // end for i
        if (counts.isEmpty()) {
            return 0;
        }
        else {
            return Collections.max(counts);
        }
    }

    public static List<PathInfoData> getBoxObjects(List<PathInfoData> datas) {
        List<PathInfoData> boxes = new ArrayList<>();
        for (PathInfoData data : datas) {
            if (data.boxXs.isEmpty()) {
                continue;
            }
            boxes.add(data);
        } // end for i
        return boxes;
    }

    public static void compMatchSmallPathFeature(List<PathInfoData> datas) {
        int n = datas.size();
        if (n <= 1) {
            return;
        }
        double width = 0.0, heigh = 0.0, num_dy_zero = 0;
        for (int i = 0; i < n; i++) {
            PathInfoData path1 = datas.get(i);
            List<Double> color1 = path1.colors;
            for (int j = 0; j < n; j++) {
                if (i == j) {
                    continue;
                }
                PathInfoData path2 = datas.get(j);
                List<Double> color2 = path2.colors;
                double dist = Math.abs(color1.get(0) - color2.get(0)) +
                        Math.abs(color1.get(1) - color2.get(1)) +
                        Math.abs(color1.get(2) - color2.get(2));
                if (dist > 1E-1) {
                    continue;
                }
                width = path2.data.get(22);
                heigh = path2.data.get(23);
                if (width >= 0.05 || heigh >= 0.03) {
                    continue;
                }
                num_dy_zero = path2.data.get(16);
                if (num_dy_zero < 0.5) {
                    continue;
                }
                path1.data.set(26, 1.0);
                //System.out.println("match small path");
                break;
            } // end for j
        } // end for i
    }

    public static void compBoxSameColorSizeFeature(List<PathInfoData> datas) {
        List<PathInfoData> boxes = getBoxObjects(datas);
        int n = boxes.size();
        if (n == 0) {
            return;
        }
        PathInfoData boxFirst = boxes.get(0);
        int featureCount = boxFirst.data.size();
        boolean isBar = boxFirst.types.contains("BAR_CHART");
        double dist = 0.0;
        List<Double> box1XY = null;
        List<Double> box2XY = null;
        for (int i = 0; i < n; i++) {
            PathInfoData box1 = boxes.get(i);
            List<Double> color1 = box1.colors;
            if (isBar) {
                box1XY = box1.boxXs;
            }
            else {
                box1XY = box1.boxYs;
            }
            if (hasSameSizeBox(box1XY, null)) {
                box1.data.set(featureCount - 3, 1.0);
                //System.out.println("same color size");
                continue;
            }
            for (int j = 0; j < n; j++) {
                if (i == j) {
                    continue;
                }
                PathInfoData box2 = boxes.get(j);
                List<Double> color2 = box2.colors;
                dist = Math.abs(color1.get(0) - color2.get(0)) +
                        Math.abs(color1.get(1) - color2.get(1)) +
                        Math.abs(color1.get(2) - color2.get(2));
                if (dist > 1E-1) {
                    continue;
                }
                if (isBar) {
                    box2XY = box2.boxXs;
                }
                else {
                    box2XY = box2.boxYs;
                }
                if (hasSameSizeBox(box1XY, box2XY)) {
                    box1.data.set(featureCount - 3, 1.0);
                    //System.out.println("same color size");
                    break;
                }
            } // end for j
        } // end for i
    }

    public static void compBoxPositionFeature(List<PathInfoData> datas) {
        List<PathInfoData> boxes = getBoxObjects(datas);
        int n = boxes.size();
        if (n == 0) {
            return;
        }
        PathInfoData boxFirst = boxes.get(0);
        int featureCount = boxFirst.data.size();
        boolean isBar = boxFirst.types.contains("BAR_CHART");
        double dist = 0.0;
        List<Double> box1XY = null;
        List<Double> box2XY = null;
        List<Double> otherBoxXY = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            PathInfoData box1 = boxes.get(i);
            List<Double> color1 = box1.colors;
            otherBoxXY.clear();
            if (isBar) {
                box1XY = box1.boxYs;
            }
            else {
                box1XY = box1.boxXs;
            }
            for (int j = 0; j < n; j++) {
                if (i == j) {
                    continue;
                }
                PathInfoData box2 = boxes.get(j);
                List<Double> color2 = box2.colors;
                dist = Math.abs(color1.get(0) - color2.get(0)) +
                        Math.abs(color1.get(1) - color2.get(1)) +
                        Math.abs(color1.get(2) - color2.get(2));
                if (dist > 1E-1) {
                    continue;
                }
                if (isBar) {
                    box2XY = box2.boxYs;
                }
                else {
                    box2XY = box2.boxXs;
                }
                otherBoxXY.addAll(box2XY);
            } // end for j
            int count = compSamePositionCount(box1XY, otherBoxXY);
            box1.data.set(featureCount - 1, count * 1.0);
            //System.out.println(count);
        } // end for i
    }
}

public class ChartPathClassify {

    private static Logger logger = LogManager.getLogger();
    private static ChartPathClassify client = null;
    private RandomForest rf = null;
    private List<Double> featureRange = null;

    public static void getRFModel() {
        if (client != null && client.rf != null) {
            return;
        }
        String xml = "";
        //String modelFile = "/media/myyang/data2/SMILE_MODELS/PathMLModel.5.txt";
        String modelFile = "/media/myyang/data2/SMILE_MODELS_test/PathMLModel.5.2.xml";
        // 读取模型文件
        long count = 0;
        try(BufferedReader br = new BufferedReader(new FileReader(modelFile))) {
            StringBuilder sb = new StringBuilder();
            String line = br.readLine();
            while (line != null) {
                sb.append(line);
                sb.append(System.lineSeparator());
                line = br.readLine();
                //xml = sb.toString();
                //count++;
                //logger.info(count);
            }
            xml = sb.toString();
        } catch (IOException e) {
            e.printStackTrace();
        }

        if(xml.isEmpty()) {
            // 模型为空错误
            logger.error("Wrong Bar Machine Learning Model------------------------");
            return;
        }

        // 加载模型
        XStream xStream = new XStream();
        client=  new ChartPathClassify();
        client.rf = (RandomForest) xStream.fromXML(xml);

        // 读取特征规范化参数文件
        //String featureRangeFile = "/media/myyang/data2/SMILE_MODELS/PathMLModel.FeatureRange.json";
        String featureRangeFile = "/media/myyang/data2/SMILE_MODELS_test/PathMLModel.FeatureRange.2.json";
        try {
            JsonParser parser = new JsonParser();
            Object object = parser.parse(new FileReader(featureRangeFile));
            JsonObject obj = (JsonObject)object;
            int n = obj.get("feature_num").getAsInt();
            client.featureRange = new ArrayList<>();
            JsonArray jsonArray = obj.getAsJsonArray("featrue_range");
            for (int i = 0; i < n; i++) {
                client.featureRange.add(jsonArray.get(2 * i).getAsDouble());
                client.featureRange.add(jsonArray.get(2 * i + 1).getAsDouble());
            }
        } catch (Exception e) {
            e.printStackTrace();
            client.featureRange = null;
        }
    }

    public static int[] predict(double [] X) {
        if (client == null || client.rf == null || X == null) {
            return null;
        }
        int n = X.length;
        double [][] XNew = new double[1][n];
        XNew[0] = X;
        return client.rf.predict(XNew);
    }

    public static int[] predictPath(List<PathInfoData> X) {
        if (client == null || client.rf == null || X == null || X.size() == 0) {
            return null;
        }
        int n = X.size();
        int [] results = new int[n];
        for (int i = 0; i < n; i++) {
            PathInfoData data = X.get(i);
            int [] result = predict(data.data);
            if (result == null || result.length == 0) {
                results[i] = -1;
            }
            else {
                results[i] = result[0];
                //System.out.println("predict : " + result[0]);
            }
            if (results[i] <= 3) {
                results[i] = results[i] + 1;
            }
            else {
                results[i] = results[i] + 2;
            }
            data.label = results[i];
            data.obj.addProperty("label", data.label);
        } // end for i
        //System.out.println(results);
        return results;
    }

    public static int[] predict(List<Double> X) {
        if (client == null || client.rf == null || X == null) {
            return null;
        }
        int n = X.size();
        double [] XNew = new double[n];
        for (int i = 0; i < n; i++) {
            XNew[i] = X.get(i);
        }
        //PathClassifyUtils.minMaxScaler(XNew);
        if (!PathClassifyUtils.minMaxScaler(XNew, client.featureRange)) {
            return null;
        }
        return predict(XNew);
    }

    public static int[] predict(double [][] X) {
        if (client == null || client.rf == null || X == null) {
            return null;
        }
        if (X.length <= 0 || X[0].length != 28) {
            return null;
        }
        int[] result = client.rf.predict(X);
        return result;
    }

    private PathInfoData read_path_json(File file) {
        JsonParser parser = new JsonParser();
        PathInfoData data = new PathInfoData();
        //logger.info(file.getAbsolutePath());
        try {
            String objPath = file.getAbsolutePath();
            Object object = parser.parse(new FileReader(objPath));
            JsonObject obj = (JsonObject)object;
            return PathClassifyUtils.read_json(obj, objPath, true);
        } catch (Exception e) {
            logger.info("-------------------------" + file.getAbsolutePath());
            e.printStackTrace();
        }
        return data;
    }

    private void pathClassificationWithML(){
        //File file = new File("/media/myyang/data2/data/pdf_new/chart_path_0517/chart_path");
        File file = new File("/media/myyang/data2/data/pdf_new/chart_path_0517/chart_path_enhance2");
        File[] files = file.listFiles();
        // 乱序
        Collections.shuffle(Arrays.asList(files));
        List<PathInfoData> datas = new ArrayList<>();
        for (int i = 0; i < files.length; i++) {
            if (i % 1000 == 0) {
                logger.info(i + "/" + files.length);
            }
            File f = files[i];
            if (i >= 10000000) {
                break;
            }
            String fname = f.getName();
            if (fname.endsWith("json") && !fname.contains("auxiliary")) {
                PathInfoData data = read_path_json(f);
                if (data.label != -1) {
                    datas.add(data);
                }
            }
        } // end for f

        int sampleCount = datas.size();
        int trainSize = (int) (sampleCount * 0.9);
        int testSize = sampleCount - trainSize;
        int featureCount = datas.get(0).data.size();

        double[][] X = new double[sampleCount][featureCount];
        for (int i = 0; i < sampleCount; i++) {
            PathInfoData data = datas.get(i);
            for (int j = 0; j < featureCount; j++) {
                X[i][j] = data.data.get(j);
            }
        } // end for i

        // 样本数据标准化 0 - 1
        JsonObject ranges = PathClassifyUtils.minMaxScaler(X);

        // 特征暂时个
        double[][] X_train = new double[trainSize][featureCount];
        int[] Y_train = new int[trainSize];
        double[][] X_test = new double[testSize][featureCount];
        int[] Y_test = new int[testSize];

        // 分配训练和测试数据
        for (int i = 0; i < sampleCount; i++) {
            PathInfoData data = datas.get(i);
            if (i < trainSize) {
                X_train[i] = X[i];
                Y_train[i] = data.label;
            }
            else {
                X_test[i - trainSize] = X[i];
                Y_test[i - trainSize] = data.label;
            }
        } // end for i

        String saveDir = "/media/myyang/data2/SMILE_MODELS_test";
        saveFeatureRanges(ranges, saveDir, "PathMLModel.FeatureRange.json");

        randomForestClass(X_train, Y_train, X_test, Y_test, 1, saveDir);
        randomForestClass(X_train, Y_train, X_test, Y_test, 2, saveDir);
        randomForestClass(X_train, Y_train, X_test, Y_test, 3, saveDir);
        randomForestClass(X_train, Y_train, X_test, Y_test, 4, saveDir);
        randomForestClass(X_train, Y_train, X_test, Y_test, 5, saveDir);
        randomForestClass(X_train, Y_train, X_test, Y_test, 6, saveDir);
        randomForestClass(X_train, Y_train, X_test, Y_test, 7, saveDir);
        randomForestClass(X_train, Y_train, X_test, Y_test, 8, saveDir);
        randomForestClass(X_train, Y_train, X_test, Y_test, 9, saveDir);
    }

    private void saveFeatureRanges(JsonObject featureRanges, String saveDir, String fileName) {
        File saveJsonFile = new File(saveDir, fileName);
        try {
            FileWriter resultFile = new FileWriter(saveJsonFile);
            PrintWriter myFile = new PrintWriter(resultFile);
            myFile.println(JsonUtil.toString(featureRanges, true));
            resultFile.close();
        }
        catch (Exception e) {
            return;
        }
    }

    private void randomForestClass(
            double [][] X_train, int [] Y_train, double [][] X_test, int [] Y_test, int type, String saveDir) {
        logger.info("Start Training------------------------   " + type + "    ---------------------");
        int featureCount = X_train[0].length;
        int trainSize = X_train.length;
        int testSize = X_test.length;
        //int mtry = 20;
        int mtry = (int)Math.floor(Math.sqrt((double)featureCount));
        RandomForest rf = null;
        if (type == 1) {
            rf = new RandomForest(null, X_train, Y_train, 30, 100, 4, mtry, 0.8);
        }
        else if (type == 2) {
            rf = new RandomForest(null, X_train, Y_train, 30, 100, 8, mtry, 0.8);
        }
        else if (type == 3) {
            rf = new RandomForest(null, X_train, Y_train, 30, 50, 4, mtry, 0.8);
        }
        else if (type == 4) {
            rf = new RandomForest(null, X_train, Y_train, 50, 100, 4, mtry, 0.8);
        }
        else if (type == 5) {
            rf = new RandomForest(null, X_train, Y_train, 50, 100, 8, mtry, 0.8);
        }
        else if (type == 6) {
            rf = new RandomForest(null, X_train, Y_train, 50, 50, 4, mtry, 0.8);
        }
        else if (type == 7) {
            rf = new RandomForest(null, X_train, Y_train, 100, 100, 4, mtry, 0.8);
        }
        else if (type == 8) {
            rf = new RandomForest(null, X_train, Y_train, 100, 50, 4, mtry, 0.8);
        }
        else if (type == 9) {
            rf = new RandomForest(null, X_train, Y_train, 100, 100, 8, mtry, 0.8);
        }
        else {
            return;
        }

        int[] trainResult = rf.predict(X_train);
        int[] testResult = rf.predict(X_test);
        int correct = 0;
        for(int i = 0; i < X_train.length; i++) {
            if (trainResult[i] == Y_train[i]) {
                correct++;
            }
        }

        logger.info("训练预测个数: " + X_train.length + " 训练集个数: " + Y_train.length);
        logger.info("训练集正确率: " + ((double) correct / (double) trainSize));
        logger.info("-----------------------------------------------------------------------------------------");

        correct = 0;
        for(int i = 0; i < testResult.length; i++) {
            if (testResult[i] == Y_test[i]) {
                correct++;
            }
        }

        logger.info("预测出个数: " + testResult.length + " 测试集个数: " + Y_test.length);
        logger.info("测试集正确率: " + ((double) correct / (double) testSize));

        XStream xstream = new XStream();
        String xml = xstream.toXML(rf);
        String modelName = saveDir + "/PathMLModel." + type + ".xml";
        try (PrintWriter out = new PrintWriter(modelName)) {
            out.println(xml);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }

        if (type == 5) {
            ChartPathClassify.getRFModel();
            int[] predictResult = ChartPathClassify.predict(X_test);
            int predictCorrect = 0;
            for(int i = 0; i < X_test.length; i++) {
                if (predictResult[i] == Y_test[i]) {
                    predictCorrect++;
                }
            }
            logger.info("测试集正确率: " + ((double) predictCorrect / (double) testSize));
        }
    }

    public static void main(String[] args) {
        // 训练模型
        ChartPathClassify pathML = new ChartPathClassify();
        pathML.pathClassificationWithML();
    }
}
