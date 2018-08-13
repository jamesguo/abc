package com.abcft.pdfextract.core.chart;

import java.awt.*;
import java.awt.geom.*;
import java.util.*;
import java.util.List;

import com.abcft.pdfextract.core.chart.model.PathInfo;
import com.abcft.pdfextract.core.model.*;
import com.abcft.pdfextract.core.util.GraphicsUtil;
import com.abcft.pdfextract.spi.ChartType;
import jnr.ffi.annotations.In;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import static com.abcft.pdfextract.core.chart.ChartContentScaleParser.reSortColumn;

/**
 * Chart内部Path对象数据结构
 */
class ChartPathInfo {
    public GeneralPath path;            // 路径
    public PathInfo.PathType type;    // 类型
    public Color color;                 // 颜色
    public String text;                 // 信息内容

    public int ithSideY;                          // Y轴对应那一侧的刻度
    public ScaleInfoType scaleTypeY;              // Y轴的刻度类型
    public List<String> valuesY;        // Path上对象的Y轴的刻度属性集
    public boolean bSubdivideY;                   // 对应的Y轴刻度是否需要细分插值即非最近邻插值
    public int ithSideX;                          // X轴对应那一侧的刻度
    public ScaleInfoType scaleTypeX;              // X轴的刻度类型
    public List<String> valuesX;        // Path上对象的X轴的刻度属性集
    public boolean bSubdivideX;                   // 对应的X轴刻度是否需要细分插值即非最近邻插值

    public String areaThreshold;                  // 面积图分为某个基准线上下两块时的threshold信息
                                                  // 保存起来在转HighCharts格式时用
    public boolean valueFromNearbyTextInfo;       // 内部数据值是否来着附近标记熬好的文字信息
    public boolean valueByCoordinate;             // 是否属性插值失败或是附近标记属性查找失败，直接用的坐标代替
    public boolean onlyMatchXScale;               // 刻度正上方才赋刻度值, 其他赋空值 和位图解析一致
    public Rectangle2D bound;

    ChartPathInfo() {
        path = new GeneralPath();
        type = PathInfo.PathType.UNKNOWN;
        color = new Color(0, 0, 0);
        text = "";
        ithSideX = 2;
        valuesX = new ArrayList<>();
        scaleTypeX = ScaleInfoType.UNKNOWN_SCALE;
        bSubdivideX = false;
        ithSideY = 0;
        valuesY = new ArrayList<>();
        scaleTypeY = ScaleInfoType.UNKNOWN_SCALE;
        bSubdivideY = false;
        areaThreshold = "";
        valueFromNearbyTextInfo = false;
        valueByCoordinate = false;
        onlyMatchXScale = false;
        bound = null;
    }

    /**
     * 检测当前Path是否匹配右侧的刻度信息
     *
     * @return 如果对应右侧刻度　则返回　true
     */
    boolean matchRightScale() {
        //if (text.contains("右")) {
        if (ithSideY == 1) {
            return true;
        } else {
            return false;
        }
    }
}

/**
 * Created by myyang on 17-4-13.
 */
public class ChartPathInfosParser {

    private static Logger logger = LogManager.getLogger(ChartPathInfosParser.class);

    /**
     * 扇形对象的数据结构  Created by myyang on 2017/1/16.
     */
    public static class ArcObject {
        public double x;        // 扇形圆心 X 坐标
        public double y;        // 扇形圆心 Y 坐标
        public double r;        // 扇形半径
        public double angle;    // 扇形角度
        public boolean isPie;   // 是否普通饼图扇形块　区分与环形图
        public Color color;     // 扇形的填充颜色
        public GeneralPath path;// 扇形的Path
        public double startAngle;//扇形的绝对开始角度
        public double endAngle; //扇形的绝对停止角度

        public ArcObject() {
            x = 0.0;
            y = 0.0;
            r = 0.0;
            angle = 0.0;
            isPie = true;
            color = new Color(255, 255, 255);
            path = null;
            startAngle = 0.0;
            endAngle = 0.0;
        }

        public ArcObject(ArcObject obj) {
            x = obj.x;
            y = obj.y;
            r = obj.r;
            angle = obj.angle;
            isPie = obj.isPie;
            color = obj.color;
            path = (GeneralPath) obj.path.clone();
            startAngle = obj.startAngle;
            endAngle = obj.endAngle;
        }

        /**
         * 输入扇形的中心点 起始点 终止点   计算扇形的内部参数
         *
         * @param x0 中心点 X 坐标
         * @param y0 中心点 Y 坐标
         * @param x1 起始点 X 坐标
         * @param y1 起始点 Y 坐标
         * @param x2 终止点 X 坐标
         * @param y2 终止点 Y 坐标
         */
        public void compArcObectParameter(double x0, double y0,
                                          double x1, double y1,
                                          double x2, double y2) {
            // 计算扇形中心点  半径  角度弧度数
            x = x0;
            y = y0;
            double vec01[] = {x1 - x0, y1 - y0};
            double vec02[] = {x2 - x0, y2 - y0};
            double len01 = Math.sqrt(vec01[0] * vec01[0] + vec01[1] * vec01[1]);
            double len02 = Math.sqrt(vec02[0] * vec02[0] + vec02[1] * vec02[1]);
            r = 0.5 * (len01 + len02);
            double fcos = (vec01[0] * vec02[0] + vec01[1] * vec02[1]) / (len01 * len02);
            fcos = Math.abs(fcos);
            fcos = (fcos <= 1.001 && fcos >= 0.999) ? 1.0 : fcos;
            angle = Math.acos(fcos);

            //计算绝对起止角度(-PI到PI，包含PI)
            if(-1E-4<vec01[0] && vec01[0]<1E-4){
                if(vec01[1]>0){
                    startAngle = Math.PI/2.0;
                }else if(vec01[1]<0){
                    startAngle = -Math.PI/2.0;
                }
            }else if(vec01[0] > 0){
                if(vec01[1]>=0){
                    startAngle = Math.atan(vec01[1]/vec01[0]);
                }else{
                    startAngle = Math.atan(vec01[1]/vec01[0]);
                }
            }else if(vec01[0] < 0){
                if(vec01[1]>=0){
                    startAngle = Math.atan(vec01[1]/vec01[0])+Math.PI;
                }else{
                    startAngle = Math.atan(vec01[1]/vec01[0])-Math.PI;
                }
            }
            if(-1E-4<vec02[0] && vec02[0]<1E-4){
                if(vec02[1]>0){
                    endAngle = Math.PI/2.0;
                }else if(vec02[1]<0){
                    endAngle = -Math.PI/2.0;
                }
            }else if(vec02[0] > 0){
                if(vec02[1]>=0){
                    endAngle = Math.atan(vec02[1]/vec02[0]);
                }else{
                    endAngle = Math.atan(vec02[1]/vec02[0]);
                }
            }else if(vec02[0] < 0){
                if(vec02[1]>=0){
                    endAngle = Math.atan(vec02[1]/vec02[0])+Math.PI;
                }else{
                    endAngle = Math.atan(vec02[1]/vec02[0])-Math.PI;
                }
            }
        }
    }

    /**
     * 判断给定Path是否为图列左侧的标识添充图形
     *
     * @param path
     * @param chart
     * @return
     */
    public static boolean isPathLegendIdentificationShape(GeneralPath path, Chart chart) {
        // 判断 path 的矩形框的　长宽范围是否有效
        Rectangle2D rect = path.getBounds2D();
        double pw = rect.getWidth();
        double cw = chart.getWidth();
        double ph = rect.getHeight();
        double ch = chart.getHeight();
        if (pw >= 0.1 * cw)
            return false;
        if (ph >= 0.075 * ch)
            return false;

        PathIterator iter = path.getPathIterator(null);
        double[] coords = new double[6];
        List<Double> xs = new ArrayList<Double>();
        List<Double> ys = new ArrayList<Double>();
        int nClose = 0;
        int ithpos = 0;
        while (!iter.isDone()) {
            switch (iter.currentSegment(coords)) {
                case PathIterator.SEG_MOVETO:
                case PathIterator.SEG_LINETO:
                    xs.add(coords[0]);
                    ys.add(coords[1]);
                    break;
                case PathIterator.SEG_CUBICTO:
                    xs.add(coords[0]);
                    ys.add(coords[1]);
                    xs.add(coords[2]);
                    ys.add(coords[3]);
                    xs.add(coords[4]);
                    ys.add(coords[5]);
                    break;
                case PathIterator.SEG_CLOSE:
                    nClose++;
                    ithpos = xs.size();
                    break;
                default:
                    return false;
            } // end switch
            iter.next();
        } // end while

        // 如果没有显式  SEG_CLOSE 则判断首末点是否相同
        if (nClose == 0) {
            int n = xs.size();
            if (ChartUtils.equals(xs.get(0), xs.get(n - 1)) &&
                    ChartUtils.equals(ys.get(0), ys.get(n - 1))) {
                nClose++;
                ithpos = xs.size();
            }
        }

        if (nClose != 1 || ithpos != xs.size()) {
            return false;
        }
        return true;
    }

    /**
     * 判断给定Path是否为饼图内的小块三角形
     *
     * @param path
     * @param arcobj
     * @return
     */
    public static boolean isPathArcTriangle(GeneralPath path, ArcObject arcobj) {
        /*
         *  格式为
         *  x0 y0 m
         *  x1 y1 l
         *  x2 y2 l
         */
        PathIterator iter = path.getPathIterator(null);
        double[] coords = new double[6];
        int count = 0;
        List<Double> xs = new ArrayList<Double>();
        List<Double> ys = new ArrayList<Double>();
        while (!iter.isDone()) {
            switch (iter.currentSegment(coords)) {
                case PathIterator.SEG_MOVETO:
                    if (count >= 1) {
                        return false;
                    }
                case PathIterator.SEG_LINETO:
                    xs.add(coords[0]);
                    ys.add(coords[1]);
                    count++;
                    break;
                case PathIterator.SEG_CLOSE:
                    if (count != 3) {
                        return false;
                    }
                    break;
                default:
                    return false;
            }
            iter.next();
        } // end while

        // 判断是否为三个有效点
        if (xs.size() < 3) {
            return false;
        }

        // 判断是否为等要三角形
        double fxA = xs.get(0) - xs.get(1);
        double fyA = ys.get(0) - ys.get(1);
        double distA = Math.sqrt(fxA * fxA + fyA * fyA);
        fxA = xs.get(2) - xs.get(0);
        fyA = ys.get(2) - ys.get(0);
        double distC = Math.sqrt(fxA * fxA + fyA * fyA);
        fxA = xs.get(0) - xs.get(2);
        fyA = ys.get(0) - ys.get(2);
        double distB = Math.sqrt(fxA * fxA + fyA * fyA);
        fxA = xs.get(2) - xs.get(1);
        fyA = ys.get(2) - ys.get(1);
        double distD = Math.sqrt(fxA * fxA + fyA * fyA);
        if (ChartUtils.equals(distA, distB)) {
            arcobj.compArcObectParameter(
                    xs.get(0), ys.get(0), xs.get(1), ys.get(1), xs.get(2), ys.get(2));
        } else if (ChartUtils.equals(distC, distD)) {
            arcobj.compArcObectParameter(
                    xs.get(2), ys.get(2), xs.get(1), ys.get(1), xs.get(0), ys.get(0));
        } else {
            return false;
        }
        return true;
    }


    /**
     * Returns true if the given path in PDF Document's Resources' XObject' Form is arc.
     *
     * @param path   路径
     * @param arcobj 扇形对象
     * @return
     */
    public static boolean isPathArcAreaInResourcesXObjectForm(GeneralPath path, ArcObject arcobj) {
        /*
         *  Document's Resources' XObject' Form 中的扇形格式为
         *  x1 y1 m
         *  xc1 yc1 xc2 yc2 xc3 yc3 c
         *  . . . .. . . . . .. . .
         *  xc1 yc1 xc2 yc2 xc3 yc3 c
         *  x0 y0 l
         *  (x1 y1 l 和第一个点重合)
         *  h
         *  f*
         *  其中 (x0, y0) 是中心点  (x1,y1) 是起始点  (xc3, yc3) 是每一子段圆弧的端点
         *  起始点和各端点都在以中心点为圆心的，某个长度的半径的圆上
         */
        // 检测path是否为扇形
        PathIterator iter = path.getPathIterator(null);
        double[] coords = new double[6];
        int count = 0;
        boolean b_has_arc = false;
        boolean b_continue_arc = false;
        boolean b_close = false;
        boolean b_has_center_pt = false;
        ArcObject arctmp = new ArcObject();
        List<Double> xs = new ArrayList<Double>();
        List<Double> ys = new ArrayList<Double>();
        while (!iter.isDone()) {
            switch (iter.currentSegment(coords)) {
                // 处理起始点
                case PathIterator.SEG_MOVETO:
                    xs.add(coords[0]);
                    ys.add(coords[1]);
                    count++;
                    break;

                // 处理中心点
                case PathIterator.SEG_LINETO:
                    if (count % 3 != 1 || b_has_center_pt) {
                        // 处理中心点下一个点 且与第一个点重合 跳过
                        if (b_has_center_pt && ChartUtils.equals(coords[0], xs.get(0))
                                && ChartUtils.equals(coords[1], ys.get(0))) {
                            break;
                        }
                        return false;
                    }
                    b_has_center_pt = true;
                    xs.add(coords[0]);
                    ys.add(coords[1]);
                    count++;
                    break;

                // 处理各子段圆弧
                case PathIterator.SEG_CUBICTO:
                    b_has_arc = true;
                    if (count == 1)
                        b_continue_arc = true;

                    xs.add(coords[0]);
                    ys.add(coords[1]);
                    xs.add(coords[2]);
                    ys.add(coords[3]);
                    xs.add(coords[4]);
                    ys.add(coords[5]);
                    count += 3;

                    break;

                // 处理路径最后的封闭状态 判断是否构成扇形
                case PathIterator.SEG_CLOSE:
                    if (!b_has_arc || !b_continue_arc || !b_has_center_pt) {
                        return false;
                    }
                    b_close = isArcPts(true, xs, ys, arcobj);
                    if (!b_close) {
                        return false;
                    }
                    break;
                default:
                    return false;
            }  // end switch
            iter.next();
        }  // end while
        if (count < 5 || !b_has_arc || !b_continue_arc || !b_has_center_pt) {
            return false;
        }

        if (!b_close) {
            if (!isArcPts(true, xs, ys, arcobj)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 判断给定PathItem是否为圆形图例对象
     * @param pathItem
     * @return
     */
    public static boolean isCircleLegend(PathItem pathItem) {
        GeneralPath path = pathItem.getItem();
        Rectangle2D box = path.getBounds2D();
        double width = box.getWidth();
        double height = box.getHeight();
        // 判断尺寸是否过大过小
        if (width > 20.0 || width < 5.0 || height > 20.0 || height < 5.0) {
            return false;
        }

        // 获取点集
        List<Double> xs = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
        List<Double> curveXs = new ArrayList<>();
        List<Double> curveYs = new ArrayList<>();
        List<Integer> types = new ArrayList<>();
        if (!PathUtils.getRingPathPts(path, xs, ys, curveXs, curveYs, types)) {
            return false;
        }
        int n = xs.size();
        if (n <= 4 || n >= 8) {
            return false;
        }

        // 判断点集类型是否合适
        if (types.contains(PathIterator.SEG_LINETO) ||
                types.contains(PathIterator.SEG_QUADTO) ||
                !types.contains(PathIterator.SEG_CUBICTO)) {
            return false;
        }
        double x = box.getCenterX();
        double y = box.getCenterY();

        // 判断是否构成中心点
        double [] distError = {0.0};
        if (!isCenterPt(xs, ys, x, y, 0.3, 2.5, distError)) {
            return false;
        }

        // 计算弧度信息
        ArcObject arcobj = new ArcObject();
        compRingArcInfo(curveXs, curveYs, x, y, arcobj);
        if (arcobj.angle < 2.0 * Math.PI - 0.1) {
            return false;
        }
        return true;
    }

    /**
     * 判断是否为环形对象内部填充圆形对象
     * @param pathItem
     * @param arcs
     * @return
     */
    public static boolean isCirclePath(
            PathItem pathItem,
            List<ArcObject> arcs) {
        // 判断是否已经存在其他扇形对象
        if (arcs.size() <= 1) {
            return false;
        }

        // 计算当前包围框
        GeneralPath path = pathItem.getItem();
        Rectangle2D box = path.getBounds2D();
        if (box.getWidth() < 20.0 && box.getHeight() < 20.0) {
            return false;
        }

        double x = 0.0, y = 0.0;
        Rectangle2D boxMerge = arcs.get(0).path.getBounds2D();
        int nObjs = arcs.size();
        for (int i = 0; i < arcs.size(); i++) {
            ArcObject obj = arcs.get(i);
            Rectangle2D objOox = obj.path.getBounds2D();
            Rectangle2D.union(boxMerge, objOox, boxMerge);
            x += obj.x;
            y += obj.y;
        } // end for i
        x /= nObjs;
        y /= nObjs;

        // 判断以及解析的扇形并集是否完全包含当前包围框
        if (!boxMerge.contains(box)) {
            return false;
        }

        // 获取点集
        List<Double> xs = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
        List<Double> curveXs = new ArrayList<>();
        List<Double> curveYs = new ArrayList<>();
        List<Integer> types = new ArrayList<>();
        if (!PathUtils.getRingPathPts(path, xs, ys, curveXs, curveYs, types)) {
            return false;
        }
        // 判断点集类型是否合适
        if (types.contains(PathIterator.SEG_LINETO) || types.contains(PathIterator.SEG_QUADTO) ||
                !types.contains(PathIterator.SEG_CUBICTO)) {
            return false;
        }

        // 计算坐标平均值
        double xc = 0.0, yc = 0.0;
        for (int i = 0; i < xs.size(); i++) {
            xc = xc + xs.get(i);
            yc = yc + ys.get(i);
        }
        xc = xc / xs.size();
        yc = yc / ys.size();
        double dist = (x - xc) * (x - xc) + (y - yc) * (y - yc);
        dist = Math.sqrt(dist);
        if (dist > 0.1 * (boxMerge.getHeight() + boxMerge.getWidth())) {
            return false;
        }

        // 判断是否构成中心点
        double [] distError = {0.0};
        if (!isCenterPt(xs, ys, x, y, 0.3, 20, distError)) {
            return false;
        }

        // 计算弧度信息
        ArcObject arcobj = new ArcObject();
        compRingArcInfo(curveXs, curveYs, x, y, arcobj);
        if (arcobj.angle < 2.0 * Math.PI - 0.1) {
            return false;
        }

        // 设置环形图状态
        for (ArcObject obj : arcs) {
            obj.isPie = false;
        }
        return true;
    }

    public static boolean isRingPath(
            PathItem pathItem,
            ArcObject arcobj,
            List<ArcObject> arcs,
            List<List<ArcObject>> pies) {
        GeneralPath path = pathItem.getItem();
        Rectangle2D box = path.getBounds2D();
        if (box.getWidth() < 20.0 && box.getHeight() < 20.0) {
            return false;
        }
        List<Double> xs = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
        List<Double> curveXs = new ArrayList<>();
        List<Double> curveYs = new ArrayList<>();
        List<Integer> types = new ArrayList<>();
        if (!PathUtils.getRingPathPts(path, xs, ys, curveXs, curveYs, types)) {
            return false;
        }

        if (!types.contains(PathIterator.SEG_CUBICTO)) {
            return false;
        }

        if (isRingArcPts(xs, ys, curveXs, curveYs, arcobj, arcs, pies, types)) {
            return true;
        }
        else {
            return false;
        }
    }

    public static boolean resetRingArcPts(
            List<Double> xs,
            List<Double> ys,
            List<Double> xsNew,
            List<Double> ysNew,
            List<Set<Integer>> pointIndex,
            List<Integer> types) {
        xsNew.clear();
        ysNew.clear();
        int n = xs.size();
        double x1 = 0.0, x2 = 0.0, y1 = 0.0, y2 = 0.0;
        double dist = 0.0, error = 1.E-2 * 1.E-2;
        x1 = xs.get(0);
        y1 = ys.get(0);
        x2 = xs.get(n - 1);
        y2 = ys.get(n - 1);
        // 判断首尾点是否相同
        dist = (x1 - x2) * (x1 - x2) + (y1 - y2) * (y1 - y2);
        if (dist > error) {
            xs.add(x1);
            ys.add(y1);
        }
        // 过滤掉相邻重复点
        n = xs.size();
        for (int i = 0; i < n - 1; i++) {
            x1 = xs.get(i);
            y1 = ys.get(i);
            x2 = xs.get(i + 1);
            y2 = ys.get(i + 1);
            dist = (x1 - x2) * (x1 - x2) + (y1 - y2) * (y1 - y2);
            xsNew.add(x1);
            ysNew.add(y1);
            Set<Integer> tmp = new HashSet<>();
            tmp.add(types.get(i));
            if (dist < error) {
                i++;
                tmp.add(types.get(i));
            }
            pointIndex.add(tmp);
        } // end for i
        // 大致判断扇形块空间尺寸是否有效
        double xmin = Collections.min(xsNew), ymin = Collections.min(ysNew);
        double xmax = Collections.max(xsNew), ymax = Collections.max(ysNew);
        if (xmax - xmin < 20.0 && ymax - ymin < 20.0) {
            return false;
        }
        else {
            return true;
        }
    }

    /*
    public static int getRingArcCenterPointPosition(
            List<Double> xsNew,
            List<Double> ysNew,
            List<ArcObject> arcs,
            List<List<ArcObject>> pies,
            List<Set<Integer>> pointTypes) {
        List<Double>  distance = new ArrayList<>();
        for (int i = 0; i < xsNew.size(); i++) {

            double xCur = xsNew.get(i);
            double yCur = ysNew.get(i);
            double maxDistance = -1;
            double minDistance = -1;
            for (int j = 0; j < xsNew.size(); j++) {
                if (i == j) {
                    continue;
                }
                double _x = Math.abs(xCur - xsNew.get(j));
                double _y = Math.abs(yCur - ysNew.get(j));
                double _d = Math.sqrt(_x*_x + _y*_y);
                if (maxDistance == -1 || _d > maxDistance) {
                    maxDistance = _d;
                }
                if (minDistance == -1 || _d < minDistance) {
                    minDistance = _d;
                }
            }
            distance.add(maxDistance - minDistance);
        }

        List<Integer> minDistList = new ArrayList<>();
        double min = -1;
        for (int i = 0; i < distance.size(); i++) {
            // c 和  c的前一个点不参与，肯定不是圆心
            if (pointTypes.get(i).contains(3)) {
                continue;
            }
            if ((i+1) < xsNew.size() && pointTypes.get(i+1).contains(3) ) {
                continue;
            }
            if (min == -1) {
                minDistList.add(i);
                min = distance.get(i);
            }
            else if (distance.get(i) < min) {
                minDistList.clear();
                minDistList.add(i);
            }
            else if (distance.get(i) == min) {
                minDistList.add(i);
            }
        }

        if (minDistList.size() > 0) {
            return minDistList.get(0);
        }
        else {
            return -1;
        }
    }
    */

    public static int getRingArcCenterPointPosition(
            List<Double> xsNew,
            List<Double> ysNew,
            List<ArcObject> arcs,
            List<List<ArcObject>> pies,
            List<Set<Integer>> pointTypes) {
        double x1 = 0.0, x2 = 0.0, y1 = 0.0, y2 = 0.0, xc = 0.0, yc = 0.0;
        double dist = 0.0, error = 1.E-2 * 1.E-2;
        boolean findCenterPt = false;
        List<Integer> cpts = new ArrayList<>();
        List<Double> errors = new ArrayList<>();
        double [] meanError = {0.0};
        // 遍历每一个点
        for (int i = 0; i < xsNew.size(); i++) {
            // 判断是否可以作为中心点
            xc = xsNew.get(i);
            yc = ysNew.get(i);
            findCenterPt = isCenterPt(xsNew, ysNew, xc, yc, 0.2, 20.0, meanError);
            if (findCenterPt) {
                cpts.add(i);
                errors.add(meanError[0]);
            }
        }
        // 如果没有
        if (cpts.isEmpty()) {
            return -1;
        }

        // 如果当前arcs不为空  判断其是否已经构成一个独立的饼图对象
        if (!arcs.isEmpty()) {
            double angle = 0.0;
            for (ArcObject arc : arcs) {
                angle += arc.angle;
            }
            // 计算角度和
            if (Math.abs(angle - 2.0 * Math.PI) < 0.1) {
                x1 = xsNew.get(cpts.get(0));
                y1 = ysNew.get(cpts.get(0));
                // 计算当前候选中心点和其他扇形中心点位置最近
                int count = 0;
                for (int j = 0; j < arcs.size(); j++) {
                    ArcObject obj = arcs.get(j);
                    dist = (x1 - obj.x) * (x1 - obj.x) + (y1 - obj.y) * (y1 - obj.y);
                    if (Math.sqrt(dist) > obj.r) {
                        count++;
                    }
                }
                if (count == arcs.size()) {
                    saveArcs(arcs, pies);
                }
            }
        }

        int pos = cpts.get(0);
        double distMin = Double.MAX_VALUE;
        // 如果当前没有其他的扇形对象信息作为参考　且　有多个候选中心点
        if (arcs.isEmpty()) {
            // 选则候选中心点中离其他点误差最小的一个
            distMin = errors.get(0);
            for (int i = 1; i < errors.size(); i++) {
                if (distMin > errors.get(i)) {
                    distMin = errors.get(i);
                    pos = cpts.get(i);
                }
            }
        }
        // 如果当前有其他的扇形对象信息作为参考　且　有多个候选中心点
        else {
            for (int i = 0; i < cpts.size(); i++) {
                x1 = xsNew.get(cpts.get(i));
                y1 = ysNew.get(cpts.get(i));
                double distSum = 0.0;
                // 计算当前候选中心点和其他扇形中心点位置最近
                for (int j = 0; j < arcs.size(); j++) {
                    ArcObject obj = arcs.get(j);
                    dist = (x1 - obj.x) * (x1 - obj.x) + (y1 - obj.y) * (y1 - obj.y);
                    distSum += dist;
                }
                if (distMin > distSum) {
                    distMin = distSum;
                    pos = cpts.get(i);
                }
            } // end for i
        }
        return pos;
    }

    public static void compRingArcInfo(
            List<Double> xsNew,
            List<Double> ysNew,
            double xc,
            double yc,
            ArcObject arcobj) {
        double x1 = 0.0, x2 = 0.0, y1 = 0.0, y2 = 0.0;
        double error = 1.E-2 * 1.E-2;
        double dist1 = 0.0, dist2 = 0.0;
        double r = 0.0;
        int count = 0;
        ArcObject arctmp = new ArcObject();

        // filter points close to center
//        Iterator<Double> iterX = xsNew.iterator();
//        Iterator<Double> iterY = ysNew.iterator();

//        while (iterX.hasNext()) {
//            double tempX = iterX.next();
//            double tempY = iterY.next();
//
//            double dist = (tempX - xc) * (tempX - xc) + (tempY - yc) * (tempY - yc);
//            if (dist < error) {
//                // 接近原点 跳过
//                iterX.remove();
//                iterY.remove();
//            }
//        }

        for (int i = 0; i < xsNew.size() / 2; i++) {
            x1 = xsNew.get(2* i);
            y1 = ysNew.get(2* i);
            x2 = xsNew.get(2* i + 1);
            y2 = ysNew.get(2* i + 1);
            dist1 = (x1 - xc) * (x1 - xc) + (y1 - yc) * (y1 - yc);
            dist2 = (x2 - xc) * (x2 - xc) + (y2 - yc) * (y2 - yc);
            if (dist1 < error || dist2 < error) {
                continue;
            }

            arctmp.compArcObectParameter(xc, yc, x1, y1, x2, y2);
            arcobj.angle += arctmp.angle;
            r += arctmp.r;
            count++;

            //保存扇形的绝对角度
            if(i==0){
                arcobj.startAngle = arctmp.startAngle;
                arcobj.endAngle = arctmp.endAngle;
            }else{
                arcobj.endAngle = arctmp.endAngle;
            }

        } // end for i

        // 保存扇形的参数
        arcobj.x = arctmp.x;
        arcobj.y = arctmp.y;
        arcobj.r = r / count;
    }

    public static boolean isRingArcPts(
            List<Double> xs,
            List<Double> ys,
            List<Double> curveXs,
            List<Double> curveYs,
            ArcObject arcobj,
            List<ArcObject> arcs,
            List<List<ArcObject>> pies,
            List<Integer> types) {
        List<Double> xsNew = new ArrayList<>();
        List<Double> ysNew = new ArrayList<>();
        // 去掉相邻重复点 并　初步判断空间尺寸是否有效
        List<Set<Integer>> pointTypes = new ArrayList<>();
        boolean reset = resetRingArcPts(xs, ys, xsNew, ysNew, pointTypes, types);
        if (!reset) {
            return false;
        }

        // 计算圆弧中心点的位置
        int pos = getRingArcCenterPointPosition(xsNew, ysNew, arcs, pies,pointTypes);
        if (pos == -1) {
            return false;
        }

        // 基于圆弧中心点计算弧度等信息
        double xc = xsNew.get(pos), yc = ysNew.get(pos);
        compRingArcInfo(curveXs, curveYs, xc, yc, arcobj);

        return true;
    }

    public static boolean isCenterPt(
            List<Double> xs,
            List<Double> ys,
            double xc,
            double yc,
            double coef,
            double minRadius,
            double [] meanError) {
        List<Double> dists = new ArrayList<>();
        int n = xs.size();
        double x = 0.0, y = 0.0, dist = 0.0, distSum = 0.0;
        for (int i = 0; i < n; i++) {
            x = xs.get(i);
            y = ys.get(i);
            dist = (x - xc) * (x - xc) + (y - yc) * (y - yc);
            dist = Math.sqrt(dist);
            if (dist < 0.5) {
                continue;
            }
            distSum += dist;
            dists.add(dist);
        } // end for i
        int count = dists.size();
        double distMean = distSum / count;
        if (distMean < minRadius) {
            return false;
        }
        double distError = 0.0;
        meanError[0] = 0.0;
        for (int i = 0; i < count; i++) {
            distError = Math.abs(dists.get(i) - distMean);
            if (distError >= coef * distMean) {
                return false;
            }
            meanError[0] += distError;
        } // end for i
        meanError[0] /= count;
        return true;
    }

    public static boolean isArcPts(
            Boolean cptInEnd,
            List<Double> xs,
            List<Double> ys,
            ArcObject arcobj) {
        int count = xs.size();
        int n = count / 3;
        if (n * 3 == xs.size()) {
            n -= 1;
        }
        if (!cptInEnd) {
            double x = xs.get(0);
            double y = ys.get(0);
            xs.add(x);
            ys.add(y);
            xs.remove(0);
            ys.remove(0);
        }
        int cptPos = count - 1;
        double fx = 0.0, fy = 0.0, sum_dist = 0.0, dist = 0.0;
        List<Double> dists = new ArrayList<Double>();
        for (int ii = 0; ii <= n; ii++) {
            fx = xs.get(3 * ii) - xs.get(cptPos);
            fy = ys.get(3 * ii) - ys.get(cptPos);
            dist = Math.sqrt(fx * fx + fy * fy);
            sum_dist = sum_dist + dist;
            dists.add(dist);
        }
        // 计算每段弧是否满足扇形条件
        ArcObject arctmp = new ArcObject();
        dist = sum_dist / (n + 1);
        for (int ii = 0; ii <= n; ii++) {
            if (Math.abs(dists.get(ii) - dist) >= 0.2 * dist)
                return false;
            if (ii < n) {
                arctmp.compArcObectParameter(
                        xs.get(cptPos),
                        ys.get(cptPos),
                        xs.get(3 * ii),
                        ys.get(3 * ii),
                        xs.get(3 * ii + 3),
                        ys.get(3 * ii + 3));
                arcobj.angle += arctmp.angle;

                //保存扇形的绝对角度
                if(ii==0){
                    arcobj.startAngle = arctmp.startAngle;
                    arcobj.endAngle = arctmp.endAngle;
                }else{
                    arcobj.endAngle = arctmp.endAngle;
                }
            }
        }
        // 保存扇形的参数
        arcobj.x = arctmp.x;
        arcobj.y = arctmp.y;
        arcobj.r = arctmp.r;
        return true;
    }

    /**
     * Returns true if the given path in PDF Document's Contents is arc.
     *
     * @param path   路径
     * @param arcobj 扇形对象
     * @return
     */
    public static boolean isPathArcAreaInContents(GeneralPath path, ArcObject arcobj) {
        /*
         *  contents 中的扇形格式为
         *  x0 y0 m
         *  x1 y1 l
         *  xc1 yc1 xc2 yc2 xc3 yc3 c
         *  . . . .. . . . . .. . .
         *  xc1 yc1 xc2 yc2 xc3 yc3 c
         *  h
         *  f*
         *  其中 (x0, y0) 是中心点  (x1,y1) 是起始点  (xc3, yc3) 是每一子段圆弧的端点
         *  起始点和各端点都在以中心点为圆心的，某个长度的半径的圆上
         */
        // 检测path是否为扇形
        PathIterator iter = path.getPathIterator(null);
        double[] coords = new double[6];
        int count = 0;
        boolean b_has_arc = false;
        boolean b_close = false;
        List<Double> xs = new ArrayList<Double>();
        List<Double> ys = new ArrayList<Double>();
        while (!iter.isDone()) {
            switch (iter.currentSegment(coords)) {
                // 处理中心点
                case PathIterator.SEG_MOVETO:
                    xs.add(coords[0]);
                    ys.add(coords[1]);
                    count++;
                    break;

                // 处理起始点
                case PathIterator.SEG_LINETO:
                    if (count != 1) {
                        // 如果起始点再次出现　则跳过
                        if (ChartUtils.equals(xs.get(0), coords[0]) &&
                                ChartUtils.equals(ys.get(0), coords[1])) {
                            break;
                        }
                        return false;
                    }
                    xs.add(coords[0]);
                    ys.add(coords[1]);
                    count++;
                    break;

                // 处理各子段圆弧
                case PathIterator.SEG_CUBICTO:
                    if (count != 2 && !b_has_arc) {
                        return false;
                    }
                    b_has_arc = true;

                    xs.add(coords[0]);
                    ys.add(coords[1]);
                    xs.add(coords[2]);
                    ys.add(coords[3]);
                    xs.add(coords[4]);
                    ys.add(coords[5]);
                    count += 3;

                    break;

                // 处理路径最后的封闭状态 判断是否构成扇形
                case PathIterator.SEG_CLOSE:
                    if (!b_has_arc || count < 5) {
                        return false;
                    }
                    b_close = isArcPts(false, xs, ys, arcobj);
                    if (!b_close) {
                        return false;
                    }
                    break;
                default:
                    return false;
            }  // end switch
            iter.next();
        }  // end while
        if (count < 5 || !b_has_arc) {
            return false;
        }
        if (!b_close) {
            if (isArcPts(false, xs, ys, arcobj)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns true if the given path is arc.
     *
     * @param path   路径
     * @param arcobj 扇形对象
     * @return
     */
    public static boolean isPathArc(GeneralPath path, ArcObject arcobj) {
        // 判断单个路径是否为扇形 myyang need to debugger
        boolean pathIsArc = (isPathArcAreaInContents(path, arcobj)
                || isPathArcAreaInResourcesXObjectForm(path, arcobj));
        return pathIsArc;
    }

    public static ArcObject parseArc(GeneralPath path) {
        ArcObject arcObj = new ArcObject();
        boolean isArc = isPathArc(path, arcObj);
        if (!isArc) {
            isArc = isPathArcTriangle(path, arcObj);
        }
        return isArc ? arcObj : null;
    }

    /**
     * 判断是否为3D效果图
     * @param chart
     * @return
     */
    public static boolean is3DChart(Chart chart) {
        if (chart == null ||
                chart.innerImageBox.size() <= 2 ||
                chart.type == ChartType.PIE_CHART ||
                !chart.pathInfos.isEmpty()) {
            return false;
        }
        double width = 0.0, height = 0.0, wh = 0.0;
        List<Double> widths = new ArrayList<>();
        List<Double> heights = new ArrayList<>();
        for (int i = 0; i < chart.innerImageBox.size(); i++) {
            Rectangle2D box = chart.innerImageBox.get(i);
            wh = box.getWidth();
            widths.add(wh);
            width += wh;
            wh = box.getHeight();
            heights.add(wh);
            height += wh;
        } // end for i
        int nBox = chart.innerImageBox.size();
        width /= nBox;
        height /= nBox;
        double widthError = 0.0, heigthError = 0.0;
        for (int i = 0; i < nBox; i++) {
            widthError += Math.abs(widths.get(i) - width);
            heigthError += Math.abs(heights.get(i) - height);
        } // end for i
        widthError /= nBox;
        heigthError /= nBox;
        // 判断相对误差是否满足容差
        if (widthError < 0.3 * width || heigthError < 0.3 * height) {
            return true;
        }
        else {
            return true;
        }
    }

    /**
     * 判断目前收集到扇形块信息　是否构成饼图或是多个饼图
     * @param pies
     * @param arcs
     * @param tris
     * @return
     */
    public static boolean isPie(
            List<List<ArcObject>> pies,
            List<ArcObject> arcs,
            List<ArcObject> tris) {
        if (isMulitPie(pies)) {
            return true;
        }
        return isArcsPie(arcs, tris);
    }

    public static boolean isMulitPie(List<List<ArcObject>> pies) {
        // 判断是否少于等于一个饼图对象 且　扇形数目 是否小于2
        int nPie = pies.size();
        if (nPie == 0) {
            return false;
        }
        for (int i = 0; i < nPie; i++) {
            List<ArcObject> pieArcs = pies.get(i);
            boolean isPie = isArcsPie(pieArcs, new ArrayList<>());
            if (!isPie) {
                return false;
            }
        }

        return true;
    }

    /**
     * Returns true if the given ArcObject set arcs is pie.
     *
     * @param arcs 扇形对象集
     * @param tris 小块三角形集
     * @return
     */
    public static boolean isArcsPie(
            List<ArcObject> arcs,
            List<ArcObject> tris) {
        // 判断扇形数目 是否小于2
        int n = arcs.size();
        if (n <= 1) {
            return false;
        }

        // 计算各扇形的角度和 测试是否在 2 * PI 上下  不是很准确 暂时没有使用
        double sum_angle = 0.0;
        double sum_len = 0.0;
        double sum_x = 0.0;
        double sum_y = 0.0;
        for (int i = 0; i < n; i++) {
            sum_angle += arcs.get(i).angle;
            sum_len += arcs.get(i).r;
            sum_x += arcs.get(i).x;
            sum_y += arcs.get(i).y;
        }
        //double ferror = Math.abs((sum_angle - 2.0 * Math.PI) / (2.0 * Math.PI));
        //if (ferror >= 0.05 )
        //    return false;

        // 计算各扇形的半径  中心点的分布的方差是否小于给定范围
        double mean_r = sum_len / n;
        double x = sum_x / n;
        double y = sum_y / n;
        double f_error = 0.0;
        double f_error_x = 0.0;
        double f_error_y = 0.0;
        double f_error_xy = 0.0;
        double variance_r = 0.0;
        for (int i = 0; i < n; i++) {
            f_error = arcs.get(i).r - mean_r;
            variance_r += f_error * f_error;
            f_error_x = arcs.get(i).x - x;
            f_error_y = arcs.get(i).y - y;
            f_error_xy = Math.sqrt(f_error_x * f_error_x + f_error_y * f_error_y);
            if (f_error_xy > 0.5 * mean_r)
                return false;
        }
        variance_r /= n;
        if (Math.sqrt(variance_r) >= 0.2 * mean_r)
            return false;

        int ntris = tris.size();
        for (int j = 0; j < ntris; j++) {
            ArcObject tri = tris.get(j);
            f_error_x = tri.x - x;
            f_error_y = tri.y - y;
            f_error_xy = Math.sqrt(f_error_x * f_error_x + f_error_y * f_error_y);
            double coef = tri.r / mean_r;
            if (f_error_xy > 0.5 * mean_r || coef > 1.5 || coef < 0.3)
                return false;
            arcs.add(tri);
        } // end for j
        tris.clear();
        return true;
    }

    /**
     * 将当前搜集的扇形信息保存起来
     */
    public static void saveArcs(List<ArcObject> arcs, List<List<ArcObject>> pies) {
        List<ChartPathInfosParser.ArcObject> pieArcs = new ArrayList<>();
        for (ChartPathInfosParser.ArcObject arc : arcs) {
            pieArcs.add(new ChartPathInfosParser.ArcObject(arc));
        }
        // 判断当前环形对象　是否和前面某个环形对象重合
        if (!pies.isEmpty()) {
            double xNew = pieArcs.get(0).x;
            double yNew = pieArcs.get(0).y;
            double len = pieArcs.get(0).r;
            final Iterator<List<ChartPathInfosParser.ArcObject>> each = pies.iterator();
            while (each.hasNext()) {
                List<ChartPathInfosParser.ArcObject> pie = each.next();
                double x = pie.get(0).x;
                double y = pie.get(0).y;
                double dist = Math.sqrt((xNew - x) * (xNew - x) + (yNew - y) * (yNew - y));
                // 删除重合的
                if (dist < 0.5 * len) {
                    each.remove();
                }
            }
        }
        pies.add(pieArcs);
        arcs.clear();
    }

    /**
     * 保存已经解析出来的单个或多个饼图信息
     * @param pies
     * @param arcs
     * @param pieInfo
     */
    public static void savePiesInfo(
            List<List<ArcObject>> pies,
            List<ArcObject> arcs,
            Chart.PieInfo pieInfo) {
        int n = pies.size();
        int id = 0;
        for (int i = 0; i < n; i++) {
            savePieInfo(pies.get(i), id, pieInfo);
            id++;
        }
        savePieInfo(arcs, id, pieInfo);
    }

    /**
     * 保存已经解析出来的饼图内容信息
     *
     * @param arcs    扇形信息集
     * @param pieID   饼图序号
     * @param pieInfo 饼图信息
     */
    public static void savePieInfo(
            List<ArcObject> arcs,
            int pieID,
            Chart.PieInfo pieInfo) {
        int n = arcs.size();
        for (int i = 0; i < n; i++) {
            ArcObject arc = arcs.get(i);
            Chart.PieInfo.PiePartInfo part = new Chart.PieInfo.PiePartInfo();
            part.color = arc.color;
            part.weight = arc.angle / (2.0 * Math.PI);
            part.path = (GeneralPath) arc.path.clone();
            part.id = pieID;
            part.isPie = arc.isPie;
            part.x = arc.x;
            part.y = arc.y;
            part.r = arc.r;
            part.startAngle = arc.startAngle;
            part.endAngle = arc.endAngle;
            pieInfo.parts.add(part);
        } // end for i
    }

    /**
     * 判断给定的　Path 是否为斜着的刻度信息
     *
     * @param chart
     * @param path
     * @return
     */
    public static boolean isOCRTextPath(Chart chart, GeneralPath path) {
        // 判断 path 的矩形框的　长宽范围是否有效
        Rectangle2D rect = path.getBounds2D();
        double pw = rect.getWidth();
        double cw = chart.getWidth();
        double ph = rect.getHeight();
        double ch = chart.getHeight();
        // 不会太大太小
        if (pw <= 0.001 * cw || pw >= 0.4 * cw || ph <= 0.001 * ch || ph >= 0.4 * ch) {
            return false;
        }
        // 斜着的Text 长宽比　不会太大太小
        double f = pw / ph;
        if (f <= 0.3 || f >= 3) {
            return false;
        }

        // 通常绘制文字图形的Path对象内部点数目很大
        if (getPtsSizeOfPath(path) < 10) {
            return false;
        }

        // 一般斜着刻度在水平坐标轴下方
        if (chart.hAxis != null && rect.getMinY() <= chart.hAxis.getY1()) {
            return false;
        }

        return true;
    }

    /**
     * 抽取给定包含矩形元素的Path中每一个矩形包围框范围信息
     *
     * @param path 给定路径
     * @param xs   矩形在X方向上的最小最大值集
     * @param ys   矩形在Y方向上的最小最大值集
     */
    public static void getPathColumnBox(
            GeneralPath path,
            List<Double> xs,
            List<Double> ys) {
        xs.clear();
        ys.clear();

        PathIterator iter = path.getPathIterator(null);
        double[] coords = new double[12];
        int n = 0;
        List<Double> cxs = new ArrayList<>();
        List<Double> cys = new ArrayList<>();
        while (!iter.isDone()) {
            switch (iter.currentSegment(coords)) {
                case PathIterator.SEG_MOVETO:
                    if (cxs.size() == 4) {
                        saveColumnBox(cxs, cys, xs, ys);
                    }
                case PathIterator.SEG_LINETO:
                    // 如果起始点再次出现　则跳过
                    n = cxs.size();
                    if (n >= 4 && ChartUtils.equals(cxs.get(n - 4), coords[0]) &&
                            ChartUtils.equals(cys.get(n - 4), coords[1])) {
                        break;
                    }
                    cxs.add(coords[0]);
                    cys.add(coords[1]);
                    break;

                // 存在着矩形在坐标轴的两侧的情况
                case PathIterator.SEG_CLOSE:
                    saveColumnBox(cxs, cys, xs, ys);
                    break;

                default:
                    return;
            }  // end switch
            iter.next();
        }  // end while
        if (cxs.size() == 4) {
            saveColumnBox(cxs, cys, xs, ys);
        }
        return;
    }

    private static void saveColumnBox(
            List<Double> cxs, List<Double> cys, List<Double> xs, List<Double> ys) {
        xs.add(Collections.min(cxs));
        xs.add(Collections.max(cxs));
        ys.add(Collections.min(cys));
        ys.add(Collections.max(cys));
        cxs.clear();
        cys.clear();
    }

    /**
     * Returns the number of points in path.
     * 计算Path内部包含的点的数目
     *
     * @param path 路径
     * @return
     */
    public static int getPtsSizeOfPath(GeneralPath path) {
        PathIterator iter = path.getPathIterator(null);
        double[] coords = new double[12];
        int count = 0;
        // 遍历　当前path
        while (!iter.isDone()) {
            switch (iter.currentSegment(coords)) {
                case PathIterator.SEG_MOVETO:
                    count++;
                    break;
                case PathIterator.SEG_LINETO:
                    count++;
                    break;
                case PathIterator.SEG_CUBICTO:
                    count += 3;
                    break;
                case PathIterator.SEG_QUADTO:
                    count += 4;
                    break;
                case PathIterator.SEG_CLOSE:
                    count++;
                    break;
                default:
                    break;
            } // end switch
            iter.next();
        } // end while
        return count;
    }

    /**
     * 测试　给定组合segmentsPath 是否能和PathInfos合并
     * 即其起始点和同color的PathInfo的终点近似相同
     *
     * @param pathInfos
     * @param segmentsPath
     * @param color
     */
    public static void canSegmentsPathCompPathInfos(
            List<ChartPathInfo> pathInfos,
            GeneralPath segmentsPath,
            Color color) {
        // 判断点数是否为空
        int n = pathInfos.size();
        int npts = getPtsSizeOfPath(segmentsPath);
        if (npts == 0 || n == 0) {
            return;
        }

        // 遍历 PathInfos中的每一个对象
        for (int i = 0; i < n; i++) {
            ChartPathInfo pathInfo = pathInfos.get(i);
            // 颜色不匹配　跳过
            if (!pathInfo.color.equals(color)) {
                continue;
            }
            // 非折线或曲线类型　跳过
            if (pathInfo.type != PathInfo.PathType.LINE && pathInfo.type != PathInfo.PathType.CURVE) {
                continue;
            }

            // 判断两个路径是否可以首尾相连
            if (!canPathCompWithSegmentsPath(segmentsPath, pathInfo.path)) {
                continue;
            }

            // 合并两个Path 重置 segmentsPath
            compTwoGeneralPaths(pathInfo.path, segmentsPath);
            segmentsPath.reset();
            return;
        } // end for i
    }

    /**
     * 判断给定path是否能与当前组合path　匹配相连接
     *
     * @param path
     * @param segmentsPath
     * @return
     */
    public static boolean canPathCompWithSegmentsPath(
            GeneralPath path, GeneralPath segmentsPath) {
        int n = getPtsSizeOfPath(segmentsPath);
        if (n <= 1) {
            return false;
        }

        PathIterator iter = path.getPathIterator(null);
        double[] coords = new double[12];
        // 遍历　当前path
        while (!iter.isDone()) {
            switch (iter.currentSegment(coords)) {
                case PathIterator.SEG_MOVETO:
                    Point2D pt = segmentsPath.getCurrentPoint();
                    double dist = Math.abs(coords[0] - pt.getX()) + Math.abs(coords[1] - pt.getY());
                    if (dist < 1.0) {
                        return true;
                    }
                    break;

                default:
                    return false;
            } // end switch
            iter.next();
        } // end while
        return false;
    }

    /**
     * check if the given path is Continuity in segmentsPath
     * 当前Path是否是组合折线的一部分 如果是则保存起来
     *
     * @param path         当前路径
     * @param segmentsPath 保存的连续性线段集路径
     */
    public static boolean checkPathInSegmentsPath(
            GeneralPath path, GeneralPath segmentsPath) {
        // 因为可能是折线的子部分　所以单个path大致的空间范围判断可能无效 组合起来更利于判断
        PathIterator iter = path.getPathIterator(null);
        double[] coords = new double[12];
        int count = 0;
        int n = getPtsSizeOfPath(segmentsPath);
        // 遍历　当前path
        while (!iter.isDone()) {
            switch (iter.currentSegment(coords)) {
                case PathIterator.SEG_MOVETO:
                    // 判断当前部分与组合容器类数据的连续性 设两个连续点的空间容差为1.0 以后可以根据经验调整
                    if (n >= 2) {
                        Point2D pt = segmentsPath.getCurrentPoint();
                        double dist = Math.abs(coords[0] - pt.getX()) + Math.abs(coords[1] - pt.getY());
                        // 如果组合容器为空或是没有连续性 则清空　添加当前path
                        if (dist > 1.0) {
                            segmentsPath.reset();
                            n = 0;
                        }
                    }
                    segmentsPath.moveTo(coords[0], coords[1]);
                    count++;
                    break;

                case PathIterator.SEG_LINETO:
                    segmentsPath.lineTo(coords[0], coords[1]);
                    count++;
                    break;

                // 处理各子段圆弧
                case PathIterator.SEG_CUBICTO:
                    // 如果　CUBICTO 不是连续出现　则清空清空
                    if ((count - 1) % 3 != 0) {
                        segmentsPath.reset();
                        return false;
                    }
                    segmentsPath.curveTo(coords[0], coords[1], coords[2], coords[3], coords[4], coords[5]);
                    count += 3;
                    break;

                // 如果出现其他标识　则清空返回
                default: {
                    segmentsPath.reset();
                    return false;
                }
            }  // end switch
            iter.next();
        }  // end while
        return count >= 2;
    }

    /**
     * Returns true if the SegmentsPath is Line in Chart.
     * 判断当前保存的线段集是否构成有效的折线
     *
     * @param segmentsPath 连续的线段集构成的path
     * @param chart        指定 Chart
     * @return
     */
    public static boolean isSegmentsPathIsLineInChart(
            GeneralPath segmentsPath, Chart chart, PathInfo.PathType[] type) {
        // 一般连续组合部分的数目很大　远超过 20  　可以提高判断的效率
        int n = getPtsSizeOfPath(segmentsPath);
        if (n <= 20)
            return false;

        return isLineInChart(segmentsPath, chart, type);
    }

    /**
     * 判断给定的候选水平坐标轴是否有效
     *
     * @param lines
     * @param ithStart
     * @return
     */
    public static boolean isCandidateAxisValid(List<Line2D> lines, int ithStart) {
        // 如果没有解析出候选坐标轴 或 当前为第一个候选坐标轴  则直接返回 true
        int nLines = lines.size();
        if (nLines - ithStart <= 0 || (ithStart == 0 && nLines == 1)) {
            return true;
        }

        double zero = 2.0;
        Line2D axisFisrt = lines.get(ithStart);
        double wFirst = axisFisrt.getBounds2D().getWidth();
        double yFirst = axisFisrt.getY1();

        // 判断当前解析出来的第一个对象和以前解析出来的对象 比较
        if (ithStart >= 1) {
            for (int i = 0; i < ithStart; i++) {
                Line2D axis = lines.get(i);
                double w = axis.getX2() - axis.getX1();
                // 平行坐标轴 不能在同一水平线上
                if (Math.abs(axis.getY1() - yFirst) <= zero) {
                    return false;
                }
            }
        }

        double len = wFirst, lenNext = -1.0;
        double y = yFirst, yNext = -1.0, dy = -1.0;
        for (int i = ithStart + 1; i < nLines; i++) {
            Line2D axis = lines.get(i);
            lenNext = axis.getX2() - axis.getX1();
            yNext = axis.getY1();
            // 平行坐标轴 长度必须相同 且 不能在同一水平线上
            if (!(Math.abs(len - lenNext) <= zero) || (Math.abs(y - yNext) <= zero)) {
                return false;
            }

            // 一般连续出现的候选坐标轴为等间距
            double dyNext = Math.abs(yNext - y);
            if (dy < 0.0) {
                dy = dyNext;
            } else if (!(Math.abs(dy - dyNext) <= zero)) {
                //return false;
            }
            len = lenNext;
            y = yNext;
        } // end for i
        return true;
    }

    /**
     * 判断给定Path是否为Chart内部的网格线
     *
     * @param path  给定Path对象
     * @param chart 给定Chart对象
     * @return 如果测试为网格线　则返回 true
     */
    public static boolean isAxisGridInChart(GeneralPath path, Chart chart) {
        // 判断 path 的矩形框的长宽范围是否有效
        Rectangle2D rect = path.getBounds2D();
        double pw = rect.getWidth();
        double cw = chart.getWidth();
        double ph = rect.getHeight();
        double ch = chart.getHeight();
        // 刻度一般由数段短线段组成　或是水平的　或是垂直的
        if (pw <= 0.3 * cw || ph < 0.3 * ch)
            return false;

        // 遍历path的点集 判断有效性
        PathIterator iter = path.getPathIterator(null);
        double[] coords = new double[12];
        double lenBefore = 0.0, len = 0.0;
        int count = 0, istart = 0, idiff = 0, n = 0;
        List<Double> xs = new ArrayList<Double>();
        List<Double> ys = new ArrayList<Double>();
        while (!iter.isDone()) {
            switch (iter.currentSegment(coords)) {
                // 每一段的起点
                case PathIterator.SEG_MOVETO:
                    // 一般相邻线段的起点在X方向或Y方向对齐
                    if (count >= 3) {
                        n = xs.size();
                        if (!ChartUtils.equals(coords[0], xs.get(n - 2)) &&
                                !ChartUtils.equals(coords[1], ys.get(n - 2)))
                            return false;
                    }
                    xs.add(coords[0]);
                    ys.add(coords[1]);
                    istart = count;
                    count++;
                    break;

                // 每一段的终点
                case PathIterator.SEG_LINETO:
                    // 通常网格的格式为　* * m * * l * * m * * l  ...... 即多个子线段连续出现
                    if ((count - istart) != 1)
                        return false;
                    if (count >= 3) {
                        n = xs.size();
                        if (!ChartUtils.equals(coords[0], xs.get(n - 2)) &&
                                !ChartUtils.equals(coords[1], ys.get(n - 2)))
                            return false;
                    }
                    xs.add(coords[0]);
                    ys.add(coords[1]);

                    // 计算子线段的长度 一般都是平行等长的线段
                    len = Math.abs(xs.get(count) - xs.get(count - 1)) + Math.abs(ys.get(count) - ys.get(count - 1));
                    count++;
                    if (count >= 4) {
                        if (!ChartUtils.equals(len, lenBefore)) {
                            idiff++;
                            return false;
                        }
                    }

                    lenBefore = len;
                    break;

                // 如果出现其他类型状态　则返回 false
                default:
                    return false;
            }  // end switch
            iter.next();
        }  // end while

        // 如果点数为奇数或只有一条线 则不构成网格线
        if (count % 2 != 0 || count <= 2) {
            return false;
        }
        return true;
    }

    /**
     * 保存给定的Path 及其 类型　颜色信息 到PathInfo集中
     *
     * @param path
     * @param type
     * @param color
     * @param paths
     * @param pathInfos
     */
    public static void savePathIntoPathInfo(
            GeneralPath path,
            PathInfo.PathType type,
            Color color,
            List<PathInfo.PathType> paths,
            List<ChartPathInfo> pathInfos) {
        if (paths != null) {
            paths.add(type);
        }
        ChartPathInfo pathInfo = new ChartPathInfo();
        pathInfo.path = (GeneralPath) path.clone();
        pathInfo.type = type;
        if (color != null) {
            pathInfo.color = color;
        }
        pathInfos.add(pathInfo);
    }

    /**
     * 判断是否为垂直直线
     * @param path
     * @return
     */
    public static boolean isVerticalLine(GeneralPath path) {
        List<Double> xs = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
        PathUtils.getPathPts(path, xs, ys);
        int n = ys.size();
        if (n <= 1) {
            return false;
        }
        // 判断是否为垂直直线
        double xStart = xs.get(0);
        if (!xs.stream().allMatch(x -> Math.abs(x - xStart) < ChartUtils.DELTA)) {
            return false;
        }
        else {
            return true;
        }
    }

    /**
     * 判断是否为水平直线
     * @param path
     * @param chart
     * @return
     */
    public static boolean isHorizonLongLine(GeneralPath path, Chart chart) {
        List<Double> xs = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
        PathUtils.getPathPts(path, xs, ys);
        int n = ys.size();
        if (n <= 1) {
            return false;
        }
        // 判断是否为水平直线
        double yStart = ys.get(0);
        if (!ys.stream().allMatch(y -> Math.abs(y - yStart) < ChartUtils.DELTA)) {
            return false;
        }

        // 判断相对于chart的宽度 直线的长度是否够大
        double len = xs.get(n - 1) - xs.get(0);
        double width = chart.getWidth();
        if (len >= 0.15 * width && len >= 10.0) {
            return true;
        } else {
            return false;
        }
    }

    /**
     * 判断给定 Path 是否为水平或垂直的 line
     *
     * @param path
     * @param chart
     * @return
     */
    public static boolean isSpecialLine(GeneralPath path, Chart chart) {
        // 一般都是不连续的虚线段组成  点的数目一般很大  设置经验参数 后续可以改进
        int n = getPtsSizeOfPath(path);
        if (n < 50) {
            return false;
        }

        // 判断 path 的矩形框的长宽范围是否有效
        Rectangle2D rect = path.getBounds2D();
        double pw = rect.getWidth();
        double cw = chart.getWidth();
        double ph = rect.getHeight();
        double ch = chart.getHeight();

        // 存在某种水平或垂直的折线　占据大半部分的宽或高
        boolean bValidBound = (pw <= 0.001 * cw && ph >= 0.6 * ch) ||
                (pw >= 0.6 * cw && ph <= 0.001 * ch);
        if (!bValidBound) {
            return false;
        }

        // 遍历path的点集 判断有效性
        // 虚线一般的数据格式为　* * m * * l * * l .... * * l * * l
        PathIterator iter = path.getPathIterator(null);
        double[] coords = new double[12];
        int count = 0;
        List<Double> xs = new ArrayList<Double>();
        List<Double> ys = new ArrayList<Double>();
        while (!iter.isDone()) {
            switch (iter.currentSegment(coords)) {
                case PathIterator.SEG_MOVETO:
                    if (count != 0) {
                        return false;
                    }
                    count++;
                    break;

                case PathIterator.SEG_LINETO:
                    count++;
                    break;

                // 如果出现其他类型状态　则返回 false
                default:
                    return false;
            }  // end switch
            iter.next();
        }  // end while
        return true;
    }

    /**
     * 判断给定Path对象是否为给定 线类型 PathInfo 的指示图标
     *
     * @param chart
     * @param line
     * @param path
     * @param color
     * @return
     */
    public static boolean isLineAuxiliaryIcon(Chart chart, ChartPathInfo line, GeneralPath path, Color color) {
        // 判断数据类型有效性
        if (line.type != PathInfo.PathType.CURVE && line.type != PathInfo.PathType.LINE &&
                line.type != PathInfo.PathType.HORIZON_LONG_LINE && line.type != PathInfo.PathType.DASH_LINE) {
            return false;
        }
        if (!line.color.equals(color)) {
            return false;
        }

        // 判断区域是否相交
        if (!line.path.getBounds2D().intersects(path.getBounds2D())) {
            return false;
        }

        // 取出路径上的点集
        List<Double> linexs = new ArrayList<>();
        List<Double> lineys = new ArrayList<>();
        PathUtils.getPathPts(line.path, linexs, lineys);
        List<Double> pathxs = new ArrayList<>();
        List<Double> pathys = new ArrayList<>();
        PathUtils.getPathPts(path, pathxs, pathys);
        if (pathxs.isEmpty() || linexs.isEmpty()) {
            return false;
        }

        // 在给定容差下 测试path点是否都在线的点集附近
        double error = 0.06 * chart.getHeight();
        for (int i = 0; i < pathxs.size(); i++) {
            if (!isPtInPts(linexs, lineys, pathxs.get(i), pathys.get(i), error)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isPtInPts(List<Double> xs, List<Double> ys, double x, double y, double error) {
        if (xs.isEmpty() || xs.size() != ys.size()) {
            return false;
        }
        double dist = 0.0;
        for (int i = 0; i < xs.size(); i++) {
            dist = Math.abs(xs.get(i) - x) + Math.abs(ys.get(i) - y);
            if (dist < error) {
                return true;
            }
        }
        return false;
    }

    /**
     * 尝试从给定GeneralPath对象内过滤掉非刻度和轴标对象　并将候选刻度和轴标保存为GeneralPath
     * 提升后续检测刻度和轴标的效率和准确性
     * @param pathOrigin
     * @param fiiterSet
     * @return
     */
    public static GeneralPath filterAxisScalePath(GeneralPath pathOrigin, Set<Integer> fiiterSet) {
        List<GeneralPath> paths = new ArrayList<>();
        GeneralPath path = new GeneralPath();
        PathIterator iter = pathOrigin.getPathIterator(null);
        double[] coords = new double[12];
        boolean subPathValid = true;
        while (!iter.isDone()) {
            switch (iter.currentSegment(coords)) {
                case PathIterator.SEG_MOVETO:
                    if (!subPathValid) {
                        path.reset();
                    }
                    else {
                        // 将每个从 m开始，到下一个m前的有效路径信息保存
                        if (path.getBounds2D().getHeight() > 0 ||
                                path.getBounds2D().getWidth() > 0) {
                            paths.add((GeneralPath) path.clone());
                            path.reset();
                        }
                    }
                    path.moveTo(coords[0], coords[1]);
                    subPathValid = true;
                    break;
                case PathIterator.SEG_LINETO:
                    path.lineTo(coords[0], coords[1]);
                    break;
                // 根据输入待过滤类型　如果满足条件　则设置过滤状态
                case PathIterator.SEG_CUBICTO:
                    if (fiiterSet.contains(PathIterator.SEG_CUBICTO)) {
                        subPathValid = false;
                    }
                    else {
                        path.curveTo(coords[0], coords[1], coords[2], coords[3], coords[4], coords[5]);
                    }
                    break;
                case PathIterator.SEG_QUADTO:
                    if (fiiterSet.contains(PathIterator.SEG_QUADTO)) {
                        subPathValid = false;
                    }
                    else {
                        path.quadTo(coords[0], coords[1], coords[2], coords[3]);
                    }
                    break;
                case PathIterator.SEG_CLOSE:
                    if (fiiterSet.contains(PathIterator.SEG_CLOSE)) {
                        subPathValid = false;
                    }
                    else {
                        path.closePath();
                    }
                    break;
                default:
                    break;
            }  // end switch
            iter.next();
        }  // end while
        if (subPathValid) {
            paths.add(path);
        }

        int n = paths.size();
        if (n == 0) {
            return null;
        }
        // 合并
        GeneralPath pathMerge = paths.get(0);
        for (int i = 1; i < n; i++) {
            compTwoGeneralPaths(pathMerge, paths.get(i));
        }
        return (GeneralPath)pathMerge.clone();
    }

    public static boolean isPathAxisScale(PathItem pathItem, List<Line2D> axises) {
        if (axises.isEmpty()) {
            return false;
        }
        List<Double> xs = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
        List<Integer> types = new ArrayList<>();
        GeneralPath path = pathItem.getItem();
        ChartContentScaleParser.getLinePathPoints(path, null, xs, ys, types);
        int n = xs.size();
        if (n >= 3) {
            return false;
        }
        Rectangle2D box = path.getBounds2D();
        GraphicsUtil.extendRect(box, 2.0);
        for (Line2D axis : axises) {
            Rectangle2D lineBox = axis.getBounds2D();
            GraphicsUtil.extendRect(lineBox, 2.0);
            if (lineBox.intersects(box)) {
                return true;
            }
        }
        return false;
    }

    public static List<GeneralPath> getFixAxisAndScale(GeneralPath path, Chart chart) {
        // 设置过滤条件
        Set<Integer> filterSet = new HashSet<>();
        filterSet.add(PathIterator.SEG_CLOSE);
        filterSet.add(PathIterator.SEG_QUADTO);
        filterSet.add(PathIterator.SEG_CUBICTO);
        // 过滤掉非坐标轴和轴标对象
        GeneralPath splitAxisPath = filterAxisScalePath(path, filterSet);
        List<GeneralPath> axises = new ArrayList<>();
        // 在候选坐标轴和轴标Path中　检测坐标轴对象
        List<GeneralPath> axisPaths = detectFixAxisAndScale(splitAxisPath, chart);
        if (axisPaths != null && !axisPaths.isEmpty()) {
            axises.addAll(axisPaths);
        }
        if (axises.isEmpty()) {
            return null;
        }
        else {
            return axises;
        }
    }

    public static List<GeneralPath> detectFixAxisAndScale(GeneralPath path, Chart chart) {
        if (path == null || path.getBounds2D().isEmpty()) {
            return null;
        }
        // 判断 path 的矩形框的长宽范围是否有效
        Rectangle2D rect = path.getBounds2D();
        double pw = rect.getWidth();
        double cw = chart.getWidth();
        double ph = rect.getHeight();
        double ch = chart.getHeight();
        if (pw < 0.3 * cw && ph < 0.3 * ch) {
            return null;
        }

        // 判断宽度和高度相对于Chart是否满足坐标轴尺寸要求
        boolean widthValid = pw / cw > 0.3 ? true : false;
        boolean heightValid = ph / ch > 0.3 ? true : false;

        List<GeneralPath> axiss = new ArrayList<>();
        GeneralPath pathSegment = (GeneralPath) path.clone();
        pathSegment.reset();

        double[] coords = new double[12];
        double lenx = 0.0, leny = 0.0;
        double coef = 0.05;
        int count = 0, istart = 0, iBeforeAxis = -1;
        boolean isXAxis = false, isYAxis = false;
        boolean isXDir = false, isYDir = false;
        boolean beforeAxisIsXAxis = false;
        List<Double> xs = new ArrayList<Double>();
        List<Double> ys = new ArrayList<Double>();
        // 保存坐标轴候选轴标id
        List<Integer> axisScaleLinePtIds = new ArrayList<>();

        PathIterator iter = path.getPathIterator(null);
        while (!iter.isDone()) {
            switch (iter.currentSegment(coords)) {
                case PathIterator.SEG_MOVETO:
                    xs.add(coords[0]);
                    ys.add(coords[1]);
                    istart = count;
                    count++;
                    pathSegment.reset();
                    pathSegment.moveTo(coords[0], coords[1]);
                    break;

                case PathIterator.SEG_LINETO:
                    // 通常刻度的格式为　* * m * * l * * m * * l  ...... 即多个子线段连续出现
                    // 偶尔也出现 第一个线段为 * m * l * l 的情况
                    // 调试 去除掉第一段为* m * l * l * l 的情况
                    if ((count - istart) > 2) {
                        return null;
                    }
                    xs.add(coords[0]);
                    ys.add(coords[1]);

                    // 判断当前线段的方向
                    lenx = Math.abs(xs.get(count) - xs.get(count - 1));
                    leny = Math.abs(ys.get(count) - ys.get(count - 1));
                    isXDir = ChartUtils.equals(leny, 0.0);
                    isYDir = ChartUtils.equals(lenx, 0.0);
                    if (!isXDir && !isYDir) {
                        return null;
                    }
                    isXAxis = Math.abs(lenx - pw) < coef * pw;
                    isYAxis = Math.abs(leny - ph) < coef * ph;
                    if (count == 1) {
                        if (!isXAxis && !isYAxis) {
                            return null;
                        }
                    }

                    if (lenx <= 0.05 * cw && leny <= 0.05 * ch) {
                        axisScaleLinePtIds.add(count - 1);
                        axisScaleLinePtIds.add(count);
                    }

                    //针对m--l--l类型的debug
                    if (count == 2 && isXAxis && lenx < cw * .05) {
                        //mll情况下，把最后lineTo这个点忽略掉
                    } else {
                        pathSegment.lineTo(coords[0], coords[1]);
                        count++;
                        // 判断是否为坐标轴
                        if ((isXAxis && widthValid) || (isYAxis && heightValid)) {
                            // 不能出现相邻的坐标轴
                            if (iBeforeAxis == -1 || count - iBeforeAxis >= 4) {
                                axiss.add((GeneralPath) pathSegment.clone());
                                beforeAxisIsXAxis = isXAxis;
                                iBeforeAxis = count;
                            } else if (count - iBeforeAxis == 2 && beforeAxisIsXAxis != isXAxis) {
                                axiss.add((GeneralPath) pathSegment.clone());
                                beforeAxisIsXAxis = isXAxis;
                                iBeforeAxis = count;
                            } else {
                                return null;
                            }
                        } else if (!axiss.isEmpty()) {
                            if (beforeAxisIsXAxis == isXDir) {
                                // 坐标轴和轴标可能乱序
                                Rectangle2D box = pathSegment.getBounds2D();
                                GraphicsUtil.extendRect(box, 2.0);
                                boolean isValid = false;
                                for (GeneralPath axis : axiss) {
                                    Rectangle2D axisBox = axis.getBounds2D();
                                    GraphicsUtil.extendRect(axisBox, 2.0);
                                    if (axisBox.intersects(box)) {
                                        isValid = true;
                                    }
                                }
                                if (!isValid) {
                                    return null;
                                }
                            }
                        }
                    }
                    break;

                // 如果出现其他类型状态　则返回 false
                default:
                    return null;
            }  // end switch
            iter.next();
        }  // end while
        if (!axiss.isEmpty()) {
            // 保存坐标轴轴标
            for (int i = 0; i < axisScaleLinePtIds.size()/2; i++) {
                int idA = axisScaleLinePtIds.get(2 * i);
                int idB = axisScaleLinePtIds.get(2 * i + 1);
                Line2D axisScaleLine = new Line2D.Double(xs.get(idA), ys.get(idA), xs.get(idB), ys.get(idB));
                chart.axisScaleLines.add(axisScaleLine);
            }
        }
        return axiss;
    }

    /**
     * Returns true if the given path is Axis scale in Chart.
     * 判断path是否为坐标轴上的刻度
     *
     * @param path  路径
     * @param chart 指定 Chart
     * @return 如果检测为坐标刻度线　则返回 true
     */
    public static boolean isAxisScaleInChart(GeneralPath path, Chart chart) {
        // 判断 path 的矩形框的长宽范围是否有效
        Rectangle2D rect = path.getBounds2D();
        double pw = rect.getWidth();
        double cw = chart.getWidth();
        double ph = rect.getHeight();
        double ch = chart.getHeight();
        // 刻度一般由数段短线段组成　或是水平的　或是垂直的
        if (pw >= 0.1 * cw && ph >= 0.1 * ch)
            return false;
        if (pw < 0.1 * cw && ph < 0.1 * ch)
            return false;

        // 遍历path的点集 判断有效性
        PathIterator iter = path.getPathIterator(null);
        double[] coords = new double[12];
        double lenBefore = 0.0, len = 0.0;
        int count = 0, istart = 0, idiff = 0;
        List<Double> xs = new ArrayList<Double>();
        List<Double> ys = new ArrayList<Double>();
        while (!iter.isDone()) {
            switch (iter.currentSegment(coords)) {
                case PathIterator.SEG_MOVETO:
                    xs.add(coords[0]);
                    ys.add(coords[1]);
                    istart = count;
                    count++;
                    break;

                case PathIterator.SEG_LINETO:
                    // 通常刻度的格式为　* * m * * l * * m * * l  ...... 即多个子线段连续出现
                    if ((count - istart) != 1)
                        return false;
                    xs.add(coords[0]);
                    ys.add(coords[1]);

                    // 计算子线段的长度 一般都同类型的刻度长度相同  叶存在在同一坐标轴两侧长度不同的组
                    len = Math.abs(xs.get(count) - xs.get(count - 1)) + Math.abs(ys.get(count) - ys.get(count - 1));
                    count++;
                    if(len > 0.1 * (ch + cw)){
                        return false;
                    }
                    if (count >= 4) {
                        if (!ChartUtils.equals(len, lenBefore))
                            idiff++;
                        // 不同吃长度的个数　如果大于２　则无效
                        if (idiff >= 2)
                            return false;
                    }

                    lenBefore = len;
                    break;

                // 如果出现其他类型状态　则返回 false
                default:
                    return false;
            }  // end switch
            iter.next();
        }  // end while
        // 一般由多段组成　且点数为偶数
        if (count % 2 != 0 || count <= 2) {
            return false;
        }
        // 保存坐标轴轴标
        for (int i = 0; i < count/2; i++) {
            Line2D axisScaleLine = new Line2D.Double(
                    xs.get(2 * i), ys.get(2 * i), xs.get(2 * i + 1), ys.get(2 * i + 1));
            chart.axisScaleLines.add(axisScaleLine);
        }
        return true;
    }

    /**
     * Returns true if the given path is Line in Chart.
     * 判断path是否为Chart中的有效折线
     *
     * @param path  路径
     * @param chart 指定 Chart
     * @param type  折线的类型
     * @return
     */
    public static boolean isLineInChart(
            GeneralPath path, Chart chart,
            PathInfo.PathType[] type) {
        type[0] = PathInfo.PathType.LINE;

        // 判断 path 的矩形框的　长宽范围是否有效
        Rectangle2D rect = path.getBounds2D();
        double pw = rect.getWidth();
        double cw = chart.getWidth();
        double ph = rect.getHeight();
        double ch = chart.getHeight();
        // 一般折线的会构成一定的面积　后续改进　处理垂直或水平的折线
        if ((pw <= 1.E-2 * cw && ph >= 0.05 * ch) ||
                (ph <= 1.E-2 * ch && pw >= 0.05 * cw)) {
            // 水平或垂直折线对象
        } else {
            if (pw <= 0.05 * cw)
                return false;
            if (ph <= 0.01 * ch)
                return false;
        }

        // 遍历path的点集 判断有效性
        PathIterator iter = path.getPathIterator(null);
        double[] coords = new double[12];
        double len = 0.0;
        int count = 0;
        boolean bHasSegment = false;
        boolean bHasCurve = false;
        List<Double> xs = new ArrayList<Double>();
        List<Double> ys = new ArrayList<Double>();
        while (!iter.isDone()) {
            switch (iter.currentSegment(coords)) {
                // 处理 每一个子段的起始点
                case PathIterator.SEG_MOVETO:
                    xs.add(coords[0]);
                    ys.add(coords[1]);
                    count++;
                    break;

                case PathIterator.SEG_LINETO:
                    xs.add(coords[0]);
                    ys.add(coords[1]);
                    // 判断相邻的子线段　是否接近Chart的长或宽　即可能是中间的刻度线或是表格线
                    if (Math.abs(xs.get(count) - xs.get(count - 1)) >= 0.75 * cw)
                        return false;
                    //if (Math.abs(ys.get(count) - ys.get(count - 1)) >= 0.85 * ch)
                    //    return false;
                    count++;
                    bHasSegment = true;
                    break;

                case PathIterator.SEG_CUBICTO:
                    if (count == 0) {
                        return false;
                    }
                    xs.add(coords[0]);
                    ys.add(coords[1]);
                    xs.add(coords[2]);
                    ys.add(coords[3]);
                    xs.add(coords[4]);
                    ys.add(coords[5]);
                    count += 3;
                    bHasCurve = true;
                    break;

                default:
                    return false;
            }  // end switch
            iter.next();
        }  // end while

        // 假设折线不封闭 判断首尾两点是否重合
        if (count >= 2) {
            if (ChartUtils.equals(xs.get(0), xs.get(count - 1)) && ChartUtils.equals(ys.get(0), ys.get(count - 1))) {
                return false;
            }
        }

        if (!bHasSegment && bHasCurve) {
            type[0] = PathInfo.PathType.CURVE;
        }
        return true;
    }

    /**
     * Returns true if the given path is Rectangle boundary and identification of legend in Chart.
     *
     * @param path  路径
     * @param chart 指定 Chart
     * @return
     */
    public static boolean isLegendIdentificationInChart(GeneralPath path, Chart chart) {
        return isLegendIdentificationInChart(path, chart, false);
    }

    /**
     * Returns true if the given path is Rectangle boundary and identification of legend in Chart.
     * @param path
     * @param chart
     * @param isFill
     * @return
     */
    public static boolean isLegendIdentificationInChart(GeneralPath path, Chart chart, boolean isFill) {
        // 判断是否为矩形
        boolean bRect = GraphicsUtil.isRect(path);
        if (!bRect) {
            // 检测其他形状的填充图形　调试中　目前不太稳定
            boolean bValidShape = isPathLegendIdentificationShape(path, chart);
            if (bValidShape) {
                return true;
            }
            else if (isFill && isFillDashLegend(path, chart)) {
                return true;
            }
            else {
                return false;
            }
        }

        return isValidLegendPathSize(path, chart);
    }

    /**
     * 判断path是否为有效的图例尺寸
     * @param path
     * @param chart
     * @return
     */
    public static boolean isValidLegendPathSize(GeneralPath path, Chart chart) {
        // 计算长宽信息
        Rectangle2D area = path.getBounds2D();
        double cw = chart.getWidth();
        double ch = chart.getHeight();
        double w = area.getWidth();
        double h = area.getHeight();
        // 测试大小是否有效相对于chart的长宽
        if (w > 0.15 * cw || w < 0.01 * cw || h > 0.1 * ch) {
            return false;
        }
        else {
            return true;
        }
    }

    public static boolean isFillDashLegend(GeneralPath path, Chart chart) {
        if (!isValidLegendPathSize(path, chart)) {
            return false;
        }

        List<Pair<Double, Double>> pts = splitDashSegment(path, chart.getArea());
        if (pts == null || pts.size() <= 3) {
            return false;
        }

        // 将搜集到的中心点集 排序X
        Collections.sort(pts, new Comparator<Pair<Double, Double>>(){
            public int compare(Pair<Double, Double> p1, Pair<Double, Double> p2){
                return Double.compare(p1.getLeft(), p2.getLeft());
            }
        });
        final double yfirst = pts.get(0).getRight();
        boolean sameValue = pts.stream().allMatch(pt -> Math.abs(yfirst - pt.getRight()) < 0.2);
        if (sameValue) {
            return true;
        }
        else {
            return false;
        }
    }

    /**
     * 判断给定的ChartPathInfo集中的面积部分是否构成重叠
     *
     * @param pathInfos 给定ChartPathInfo集
     * @return
     */
    public static boolean isAreaPathsOverlap(List<ChartPathInfo> pathInfos) {
        Set<Color> colors = new HashSet<>();
        pathInfos.stream().filter(pathInfo -> pathInfo.type == PathInfo.PathType.AREA)
                .forEach(pathInfo -> colors.add(pathInfo.color));
        return colors.size() >= 2 ? true : false;
    }

    /**
     * 将给定柱状Chart内部包含的类型为矩形的PathInfo设置为给定的排列方式
     *
     * @param pathInfos 给定PathInfo集
     * @param bVertical 是否为垂直方向的状态标识
     */
    public static void setColumnChartDirecton(
            List<ChartPathInfo> pathInfos, boolean bVertical) {
        PathInfo.PathType ptype = bVertical ? PathInfo.PathType.BAR : PathInfo.PathType.COLUMNAR;
        for (ChartPathInfo path : pathInfos) {
            if (path.type == PathInfo.PathType.COLUMNAR) {
                path.type = ptype;
            }
        }
    }

    /**
     * 判断给定pathInfos中面积最大的柱状是否为垂直方向
     * @param pathInfos
     * @return
     */
    public static boolean maxAreaColumnarVertical(List<ChartPathInfo> pathInfos) {
        int n = pathInfos.size();
        boolean isVertical = true;
        double dx = 0.0, dy = 0.0, area = 0.0, areaMax = -1.0;
        List<Double> xs = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
//        List<Double> dxs = new ArrayList<>();
//        List<Double> dys = new ArrayList<>();
        // 遍历　PathInfos
        for (int i = 0; i < n; i++) {
            ChartPathInfo pathInfo = pathInfos.get(i);
            if (pathInfo.type != PathInfo.PathType.COLUMNAR) {
                continue;
            }

            // 遍历当前 PathInfo中Path的所有矩形　
            getPathColumnBox(pathInfo.path, xs, ys);
            int nBox = xs.size() / 2;
            for (int j = 0; j < nBox; j++) {
                dx = xs.get(2 * j + 1) - xs.get(2 * j);
                dy = ys.get(2 * j + 1) - ys.get(2 * j);
                area = dx * dy;
//                dxs.add(Math.abs(dx));
//                dys.add(Math.abs(dy));
                // 找出面积最大的矩形  判断长宽的大小
                // 如果高大于宽  则为垂直排列
                // 对某些特殊情况 不太适用 需要找更好的方法
                if (areaMax < area) {
                    areaMax = area;
                    isVertical = dx < dy ? true : false;
                }
            }
        } // end for i
        /*
        // 不太稳定  暂时没有使用
        if (!isVertical) {
            if (!isSameSize(dys)) {
                isVertical = true;
            }
        }
        */

        return isVertical;
    }

    public static boolean isSameSize(List<Double> xs) {
        int n = xs.size();
        if (n <= 1) {
            return true;
        }
        int count = 0;
        for (int i = 0; i < n - 1; i++) {
            double dx = Math.abs(xs.get(i) - xs.get(i + 1));
            double f1 = dx / xs.get(i);
            double f2 = dx / xs.get(i + 1);
            if (f1 > 0.3 || f2 > 0.3) {
                count++;
            }
        }
        return 1.0 * count / n < 0.3;
    }

    /**
     * 判断给定的ChartPathInfo集中的柱状部分是否成垂直排列方式 并设置内部矩形Path对象的排列方式
     *
     * @param pathInfos 给定ChartPathInfo集
     * @return 如果是垂直排列　则返回 true
     */
    public static boolean isColumarPathsVertical(List<ChartPathInfo> pathInfos) {
        boolean isVertical = maxAreaColumnarVertical(pathInfos);
        if (!isVertical) {
            boolean isVerticalBaseSize = isVerticalBarBaseWidthHeight(pathInfos);
            if (isVerticalBaseSize) {
                isVertical = true;
            }
        }

        // 设置内部矩形Path对象的排列方式
        setColumnChartDirecton(pathInfos, isVertical);
        return isVertical;
    }

    /**
     * 判断给定 ChartPathInfo集 内部分对象是否和给定坐标轴相匹配
     *
     * @param pathInfos
     * @param iStart
     * @param axis
     * @param isXAxis
     * @return
     */
    public static boolean isPathInfosMatchAxis(
            List<ChartPathInfo> pathInfos, int iStart, Line2D axis, boolean isXAxis) {
        int n = pathInfos.size();
        if (axis == null || iStart < 0 || iStart >= n) {
            return true;
        }

        List<Double> xs = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
        List<Double> ysD = new ArrayList<>();

        // 计算坐标范围
        double[] range = {0.0, 0.0};
        if (isXAxis) {
            range[0] = Math.min(axis.getX1(), axis.getX2());
            range[1] = Math.max(axis.getX1(), axis.getX2());
        } else {
            range[0] = Math.min(axis.getY1(), axis.getY2());
            range[1] = Math.max(axis.getY1(), axis.getY2());
        }
        double zero = 0.1 * (range[1] - range[0]);
        double xy = 0.0;
        int numNonMatch = 0;
        int numObj = 0;

        // 遍历指定范围的 PathInfo 对象 判断内部点是否完全不在坐标轴范围内部
        for (int ith = iStart; ith < n; ith++) {
            numNonMatch = 0;
            ChartPathInfo pathInfo = pathInfos.get(ith);
            // 饼图没有坐标轴
            if (pathInfo.type == PathInfo.PathType.ARC) {
                return false;
            }
            // 计算矩形的中心点位置 统计不在坐标轴范围内的点数
            else if (pathInfo.type == PathInfo.PathType.COLUMNAR || pathInfo.type == PathInfo.PathType.BAR) {
                ChartPathInfosParser.getPathColumnBox(pathInfo.path, xs, ys);
                numObj = xs.size() / 2;
                for (int i = 0; i < numObj; i++) {
                    if (isXAxis) {
                        xy = 0.5 * (xs.get(2 * i) + xs.get(2 * i + 1));
                    } else {
                        xy = 0.5 * (ys.get(2 * i) + ys.get(2 * i + 1));
                    }
                    if (xy < range[0] - zero || xy > range[1] + zero) {
                        numNonMatch++;
                    }
                }
            } else {
                boolean getPts = false;
                // 计算折线顶点位置
                if (pathInfo.type == PathInfo.PathType.LINE || pathInfo.type == PathInfo.PathType.CURVE) {
                    getPts = ChartContentScaleParser.getLinePathPoints(pathInfo.path, null, xs, ys);
                }
                // 计算面积上侧顶点位置
                else if (pathInfo.type == PathInfo.PathType.AREA) {
                    getPts = ChartContentScaleParser.getPathAreaPoints(pathInfo.path, null, xs, ys, ysD);
                }
                // 如果没有取到合适点集 则跳过
                if (!getPts) {
                    continue;
                }

                // 统计不在坐标轴范围内的点数
                numObj = xs.size();
                for (int i = 0; i < numObj; i++) {
                    if (isXAxis) {
                        xy = xs.get(i);
                    } else {
                        xy = ys.get(i);
                    }
                    if (xy < range[0] - zero || xy > range[1] + zero) {
                        numNonMatch++;
                    }
                }
            }
            // 如果点都在坐标轴范围外部 则表示对象与坐标轴不匹配 返回 false
            if (numNonMatch == numObj) {
                return false;
            }
        }
        return true;
    }

    /**
     * 判断柱状图是否为无效Chart (很多表格和柱状图高度相似)
     *
     * @param chart
     * @return
     */
    public static boolean isColumnarChartInValid(Chart chart) {
        // 只考虑 柱状图
        if (chart == null ||
                (chart.type != ChartType.BAR_CHART && chart.type != ChartType.COLUMN_CHART)) {
            return false;
        }
        boolean isBar = chart.type == ChartType.BAR_CHART;
        boolean hasLegend = !chart.legends.isEmpty();
        double w = chart.getWidth();
        double h = chart.getHeight();
        return isColumnarInValid(chart.pathInfos, isBar, hasLegend, w, h);
    }

    /**
     * 判断给定矩形集 判断垂直排列矩形元素是否相同高度 或 水平排列矩形元素是否相同宽度
     * 再判断 近似叠加后水平方向宽度 或 垂直方向高度 是否过大
     * 再判断叠加颜色方式是否有效
     *
     * @param pathInfos
     * @param isBar
     * @param hasLegend
     * @param w
     * @param h
     * @return
     */
    public static boolean isColumnarInValid(
            List<ChartPathInfo> pathInfos,
            boolean isBar,
            boolean hasLegend,
            double w,
            double h) {
        // 判断是否同宽高
        List<BarStack> vStack = new ArrayList<>();
        List<BarStack> hStack = new ArrayList<>();
        boolean sameWeightHeight = isColumnsSameWeightHeight(pathInfos, isBar, vStack, hStack);
        /*
        if (sameWeightHeight) {
            return true;
        }
        */

        // 判断近似堆叠后的长度和宽度与chart.area的长宽比较 如果占比很大则过滤掉
        if ((isBar && hStack.size() <= 1) || (!isBar && vStack.size() <= 1)) {
            return true;
        } else if (isColumnsStackSizeInValid(vStack, hStack, w, h)) {
            return true;
        }

        // 如果图例为空且各堆叠长度或宽度相同 则判断为表格
        if (!hasLegend) {
            if (isColumnsStackSameLength(vStack, hStack)) {
                return true;
            }
        }

        // 统计同颜色矩形占比 一般表格具有很多相同颜色的矩形
        return isColumnsStackColorInValid(vStack, hStack);
    }

    public static boolean isColumnsSameWeightHeight(
            List<ChartPathInfo> pathInfos,
            boolean isBar,
            List<BarStack> vStack,
            List<BarStack> hStack) {
        List<Double> dxs = new ArrayList<>();
        List<Double> dys = new ArrayList<>();
        List<Double> xs = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
        vStack.clear();
        hStack.clear();
        double dxFirst = 0.0, dyFirst = 0.0, xmid = 0, ymid = 0, dx = 0, dy = 0;
        boolean sameWeightHeight = true;
        // 遍历 PathInfo 判断内部矩形元素是否同高或同宽
        for (ChartPathInfo pathInfo : pathInfos) {
            if (pathInfo.type != PathInfo.PathType.COLUMNAR &&
                    pathInfo.type != PathInfo.PathType.BAR) {
                continue;
            }

            // 遍历当前 PathInfo中Path的所有矩形　
            ChartPathInfosParser.getPathColumnBox(pathInfo.path, xs, ys);
            dxFirst = xs.get(1) - xs.get(0);
            dyFirst = ys.get(1) - ys.get(0);
            for (int k = 0; k < (xs.size() / 2); k++) {
                xmid = 0.5 * (xs.get(2 * k) + xs.get(2 * k + 1));
                ymid = 0.5 * (ys.get(2 * k) + ys.get(2 * k + 1));
                if (!isBar) {
                    dx = xs.get(2 * k + 1) - xs.get(2 * k);
                    if (!ChartUtils.equals(dx, dxFirst)) {
                        sameWeightHeight = false;
                    }
                    addBarStackInfo(ymid, dx, pathInfo.color, vStack);
                } else {
                    dy = ys.get(2 * k + 1) - ys.get(2 * k);
                    if (!ChartUtils.equals(dy, dyFirst)) {
                        sameWeightHeight = false;
                    }
                    addBarStackInfo(xmid, dy, pathInfo.color, hStack);
                }
            } // end for k
            dxs.add(dxFirst);
            dys.add(dyFirst);
        } // end for pathInfo

        // 判断内部对象同高同宽的堆叠大组 是否同高同宽
        final double ylast = dyFirst;
        final double xlast = dxFirst;
        if (isBar && sameWeightHeight && dys.stream().allMatch(y -> ChartUtils.equals(y, ylast))) {
            return true;
        } else if (!isBar && sameWeightHeight && dxs.stream().allMatch(x -> ChartUtils.equals(x, xlast))) {
            return true;
        } else {
            return false;
        }
    }

    public static boolean isColumnsStackSizeInValid(List<BarStack> vStack, List<BarStack> hStack, double w, double h) {
        if (hStack.stream().anyMatch(stack -> (stack.len > 0.95 * h) || (stack.count >= 20))) {
            return true;
        } else if (vStack.stream().anyMatch(stack -> (stack.len > 0.95 * w) || (stack.count >= 20))) {
            return true;
        } else {
            return false;
        }
    }

    public static boolean isColumnsStackSameLength(List<BarStack> vStack, List<BarStack> hStack) {
        if (!vStack.isEmpty()) {
            double len = vStack.get(0).len;
            if (vStack.stream().allMatch(stack -> ChartUtils.equals(stack.len, len))) {
                return true;
            }
        }
        if (!hStack.isEmpty()) {
            double len = hStack.get(0).len;
            if (hStack.stream().allMatch(stack -> ChartUtils.equals(stack.len, len))) {
                return true;
            }
        }
        return false;
    }

    public static boolean isColumnsStackColorInValid(List<BarStack> vStack, List<BarStack> hStack) {
        Set<Color> colors = new HashSet<>();
        int nBar = 0, nBarSameColor = 0;
        for (BarStack stack : vStack) {
            nBar += stack.count;
            nBarSameColor += stack.numSameColor;
            colors.add(stack.color);
        }
        for (BarStack stack : hStack) {
            nBar += stack.count;
            nBarSameColor += stack.numSameColor;
            colors.add(stack.color);
        }
        double coef = 1.0 * nBarSameColor / nBar;
        if (coef > 0.2 || (colors.size() == 1 && 0.1 < coef && coef <= 0.2)) {
            return true;
        } else {
            return false;
        }
    }

    private static void addBarStackInfo(double k, double l, Color color, List<BarStack> hvStack) {
        boolean isFind = false;
        for (int i = 0; i < hvStack.size(); i++) {
            BarStack stack = hvStack.get(i);
            if (ChartUtils.equals(stack.key, k)) {
                stack.len += l;
                stack.count += 1;
                isFind = true;
                // 记录和第一个矩形同颜色的个数
                if (color.equals(stack.color)) {
                    stack.numSameColor++;
                }
                break;
            }
        }
        if (!isFind) {
            hvStack.add(new BarStack(k, l, 1, color));
        }
    }

    /**
     * 判断给定Path是否包含矩形对象 如果包含则返回起内部点
     *
     * @param path
     * @param xs
     * @param ys
     * @return
     */
    public static boolean judgePathContainColumnAndGetPts(GeneralPath path, List<Double> xs, List<Double> ys) {
        // 遍历path的点集 判断有效性
        PathIterator iter = path.getPathIterator(null);
        double[] coords = new double[12];
        double w = 0.0, wnew = 0.0;
        double h = 0.0, hnew = 0.0;
        int count = 0, n = 0;
        int countInvalidColumn = 0;
        boolean bInChart = false;
        boolean hasClose = false;
        xs.clear();
        ys.clear();
        while (!iter.isDone()) {
            switch (iter.currentSegment(coords)) {
                case PathIterator.SEG_MOVETO:
                    if (count % 4 != 0) {
                        return false;
                    }
                    xs.add(coords[0]);
                    ys.add(coords[1]);
                    bInChart = true;
                    count++;
                    break;

                case PathIterator.SEG_LINETO:
                    // 柱状图点数都是４的倍数
                    if (count % 4 == 0) {
                        n = xs.size();
                        // 如果起始点再次出现　则跳过
                        if (n >= 4 && ChartUtils.equals(xs.get(n - 4), coords[0]) &&
                                ChartUtils.equals(ys.get(n - 4), coords[1])) {
                            break;
                        }
                        return false;
                    }
                    xs.add(coords[0]);
                    ys.add(coords[1]);
                    count++;
                    break;

                case PathIterator.SEG_CLOSE:
                    hasClose = true;
                    if (count % 4 != 0) {
                        return false;
                    }
                    if (!bInChart) {
                        break;
                    }

                    // 判断每个子部分　如果不是矩形 则返回 false
                    if (!lastFourPointValidColumnar(xs, ys)) {
                        return false;
                    }

                    n = xs.size();
                    wnew = Math.abs(xs.get(n - 4) - xs.get(n - 2));
                    hnew = Math.abs(ys.get(n - 4) - ys.get(n - 2));
                    // 判断宽度是否相同 存在竖直或水平柱状图 不同柱宽度或高度不一样
                    // 零宽度或高度　一般不构成柱状图的一部分  统计个数  有些柱状对象内部少数矩形高度或宽度为零
                    if (ChartUtils.equals(0.0, wnew) || ChartUtils.equals(0.0, hnew)) {
                        countInvalidColumn++;
                    }
                    if (xs.size() / 4 >= 2) {
                        if ((wnew > 2.0 * w || w > 2.0 * wnew) && (hnew > 2.0 * h || h > 2.0 * hnew))
                            return false;
                    }
                    w = wnew;
                    h = hnew;

                    bInChart = false;
                    break;

                default:
                    return false;
            }  // end switch
            iter.next();
        }  // end while

        // 判断点数的有效性
        n = xs.size();
        if (n == 0 || n % 4 != 0) {
            return false;
        } else if (n == 4 && !hasClose) {
            // 如果不是矩形 则返回 false
            if (!lastFourPointValidColumnar(xs, ys)) {
                return false;
            }
        }
        // 如果给定的 path 内部矩形都是零宽度或高度矩形　则认为是无效柱状对象
        if (n / 4 == countInvalidColumn) {
            return false;
        }
        return true;
    }

    /**
     * 给定点集的最后四个点是否构成矩形
     *
     * @param xs
     * @param ys
     * @return
     */
    private static boolean lastFourPointValidColumnar(List<Double> xs, List<Double> ys) {
        int n = xs.size();
        if (n < 4) {
            return false;
        }
        if (!ChartUtils.equals(xs.get(n - 4), xs.get(n - 3)) && !ChartUtils.equals(ys.get(n - 4), ys.get(n - 3))) {
            return false;
        } else if (!ChartUtils.equals(xs.get(n - 3), xs.get(n - 2)) && !ChartUtils.equals(ys.get(n - 3), ys.get(n - 2))) {
            return false;
        } else if (!ChartUtils.equals(xs.get(n - 2), xs.get(n - 1)) && !ChartUtils.equals(ys.get(n - 2), ys.get(n - 1))) {
            return false;
        } else if (!ChartUtils.equals(xs.get(n - 4), xs.get(n - 1)) && !ChartUtils.equals(ys.get(n - 4), ys.get(n - 1))) {
            return false;
        } else {
            return true;
        }
    }

    public static boolean isFillAxisSacleLine(
            Chart chart,
            Rectangle2D referBox,
            List<Line2D> barInfos,
            int numBefore) {
        // 判断数目有效性
        if (referBox == null || barInfos == null) {
            return false;
        }
        int numAfter = barInfos.size();
        if (numAfter - numBefore <= 2) {
            return false;
        }

        // 判断最后一个pathInfo是否为柱状对象
        double widthChart = chart.getWidth();
        double heightChart = chart.getHeight();
        Rectangle2D axisBox = referBox;
        if (axisBox.getWidth() >= 0.05 * widthChart && axisBox.getHeight() >= 0.05 * heightChart) {
            return false;
        }
        boolean isXAxis = axisBox.getWidth() > axisBox.getHeight() ? true : false;

        // 判断尺寸是否相同
        Line2D barFirst = barInfos.get(numBefore);
        double width = barFirst.getBounds2D().getWidth();
        double height = barFirst.getBounds2D().getHeight();
        boolean isXDir = width > height ? true : false;
        if (isXAxis == isXDir) {
            return false;
        }
        if (isXDir) {
            if (width/widthChart > 0.03 || height/heightChart >= 0.01) {
                return false;
            }
        }
        else {
            if (width/widthChart > 0.01 || height/heightChart >= 0.03) {
                return false;
            }
        }

        GraphicsUtil.extendRect(axisBox, 4.0);
        for (int i = numBefore; i < numAfter; i++) {
            Line2D barNext = barInfos.get(numBefore);
            double widthNext = barFirst.getBounds2D().getWidth();
            double heightNext = barFirst.getBounds2D().getHeight();
            boolean isXDirNext = widthNext > heightNext ? true : false;
            if (isXDir != isXDirNext) {
                return false;
            }
            if (!ChartUtils.equals(width, widthNext) || !ChartUtils.equals(height, heightNext) ) {
                return false;
            }
            Rectangle2D lineBox = barNext.getBounds2D();
            GraphicsUtil.extendRect(lineBox, 4.0);
            if (!lineBox.intersects(axisBox)) {
                return false;
            }
        } // end for i

        for (int i = numBefore; i < numAfter; i++) {
            chart.axisScaleLines.add(barInfos.get(i));
        }
        return true;
    }

    public static List<Line2D> getChartBarInfosLines(Chart chart, int numBefore) {
        List<Line2D> lines = new ArrayList<>();
        List<Double> xs = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
        double width = chart.getWidth();
        double height = chart.getHeight();
        for (int i = numBefore; i < chart.barsInfos.size(); i++) {
            ChartPathInfo pathInfo = chart.barsInfos.get(i);
            if (!judgePathContainColumnAndGetPts(pathInfo.path, xs, ys)) {
                return null;
            }
            int n = xs.size() / 4;
            for (int j = 0; j < n; j++) {
                List<Double> subXs = xs.subList(4 * j, 4 * (j + 1));
                List<Double> subYs = ys.subList(4 * j, 4 * (j + 1));
                double xmin = Collections.min(subXs);
                double ymin = Collections.min(subYs);
                double xmax = Collections.max(subXs);
                double ymax = Collections.max(subYs);
                double dx = xmax - xmin;
                double dy = ymax - ymin;
                if (dx > 0.03 * width || dy > 0.03 * height) {
                    return null;
                }
                Line2D line = null;
                if (dx > dy) {
                    double y = 0.5 * (ymin + ymax);
                    line = new Line2D.Double(xmin, y, xmax, y);
                }
                else {
                    double x = 0.5 * (xmin + xmax);
                    line = new Line2D.Double(x, ymin, x, ymax);
                }
                lines.add(line);
            }
        } // end for pathInfo
        return lines;
    }

    public static List<Line2D> getFillAxisScaleLines(
            GeneralPath path, Chart chart) {
        // 初步判断是否包含矩形对象 并 取出内部点 做进一步的判断
        List<Double> xs = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
        if (!judgePathContainColumnAndGetPts(path, xs, ys)) {
            return null;
        }

        // 判断 path 的矩形框的　长宽范围是否有效
        double cw = chart.getWidth();
        double ch = chart.getHeight();
        double pw = Collections.max(xs) - Collections.min(xs);
        double ph = Collections.max(ys) - Collections.min(ys);
        int n = xs.size();
        if (n == 4 && (pw >= 0.03 * cw || ph >= 0.03 * ch)) {
            return null;
        } else if (n > 4 && (pw < 0.03 * cw && ph < 0.03 * ch)) {
            return null;
        }

        int nline = n / 4;
        List<Double> subXs = xs.subList(0, 4);
        List<Double> subYs = ys.subList(0, 4);
        double xmin = Collections.min(subXs);
        double xmax = Collections.max(subXs);
        double ymin = Collections.min(subYs);
        double ymax = Collections.max(subYs);
        double width = xmax - xmin;
        double height = ymax - ymin;
        boolean isXDir = width > height ? true : false;
        List<Line2D> lines = new ArrayList<>();
        for (int i = 0; i < nline; i++) {
            subXs = xs.subList(i * 4, (i + 1) * 4);
            subYs = ys.subList(i * 4, (i + 1) * 4);
            xmin = Collections.min(subXs);
            xmax = Collections.max(subXs);
            ymin = Collections.min(subYs);
            ymax = Collections.max(subYs);
            double widthNext = xmax - xmin;
            double heightNext = ymax - ymin;
            boolean isXDirNext = widthNext > heightNext ? true : false;
            if (isXDir != isXDirNext) {
                return null;
            }
            // 遇到过轴标高度、粗细不均匀的情况 这里将容差扩大到0.15
//            if (!ChartUtils.equals(width, widthNext) || !ChartUtils.equals(height, heightNext)) {
            if (Math.abs(width - widthNext) > 0.15 && Math.abs(height - heightNext) > 0.15) {
                return null;
            }
            Line2D line;
            if (isXDirNext) {
                double y = 0.5 * (ymin + ymax);
                line = new Line2D.Double(xmin, y, xmax, y);
            }
            else {
                double x = 0.5 * (xmin + xmax);
                line = new Line2D.Double(x, ymin, x, ymax);
            }
            lines.add(line);
        } // end for i

        return lines;
    }

    /**
     * 判断给定的line2d集是否构成table网格线对象
     * @param lines
     * @return
     */
    public static boolean isTableGridLine(List<Line2D> lines) {
        // 区分　水平　垂直方向的line2d对象
        List<Line2D> xlines = new ArrayList<>();
        List<Line2D> ylines = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            Line2D line = lines.get(i);
            if (ChartUtils.isHorizon(line)) {
                xlines.add(line);
            }
            else {
                ylines.add(line);
            }
        } // end for i

        // 初步判断个数有效性
        int m = xlines.size();
        int n = ylines.size();
        if (m <= 1 || n <= 1) {
            return false;
        }

        int countX = 0, countY = 0;
        for (int i = 0; i < m; i++) {
            Line2D xline = xlines.get(i);
            countY = 0;
            for (int j = 0; j < n; j++) {
                Line2D yline = ylines.get(j);
                if (ChartUtils.isTwoLineOrthogonality(xline, yline, false)) {
                    countY++;
                }
            } // end for j
            if (countY == n) {
                countX++;
            }
        } // end for i
        if (countX == m) {
            //System.out.println("table");
            return true;
        }
        else {
            return false;
        }
    }

    /**
     * 判断折线Path是否为候选表格网格线对象　如果是则抽出满足网格线特征的line2d对象
     * @param path
     * @return
     */
    public static List<Line2D> isLineGridLine(GeneralPath path) {
        // 找出Line Path 上的点集的X Y 坐标信息
        List<Double> xs = new ArrayList<>();     // 折线顶点X坐标集
        List<Double> ys = new ArrayList<>();     // 折线顶点Y坐标集
        List<Integer> types = new ArrayList<>();     // 折线顶点类型
        Point2D p1 = new Point2D.Double(), p2 = new Point2D.Double();
        List<Line2D> lines = new ArrayList<>();
        if (!ChartContentScaleParser.getLinePathPoints(path, null, xs, ys, types)) {
            return lines;
        }
        if (types.contains(PathIterator.SEG_CUBICTO)) {
            return null;
        }
        // 判断是否有非水平或垂直方向折线段　　如果存在则直接返回
        int n = 0;
        for (int i = 0; i < types.size() - 1; i++) {
            p1.setLocation(xs.get(i), ys.get(i));
            p2.setLocation(xs.get(i + 1), ys.get(i + 1));
            if (types.get(i + 1) == PathIterator.SEG_MOVETO) {
                continue;
            }
            boolean isX = ChartUtils.isTwoHorizonVerticalLine(p1, p2, true, 0.1);
            boolean isY = ChartUtils.isTwoHorizonVerticalLine(p1, p2, false, 0.1);
            if (!isX && !isY) {
                return null;
            }
            if (p1.distance(p2) < 10) {
                lines.clear();
                return lines;
            }
            Line2D line = new Line2D.Double(p1, p2);
            n = lines.size();
            if (n >= 1 && ChartUtils.isTwoLineOrthogonality(line, lines.get(n - 1), true)) {
                lines.clear();
                return lines;
            }
            lines.add(line);
        } // end for i
        return lines;
    }

    /**
     * 判断柱状Path是否为候选表格网格线对象　如果是则抽出满足网格线特征的line2d对象
     * @param item
     * @return
     */
    public static List<Line2D> isColumnarGridLine(PathItem item) {
        // 初步判断是否包含矩形对象 并 取出内部点 做进一步的判断
        GeneralPath path = item.getItem();
        List<Double> xs = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
        if (!judgePathContainColumnAndGetPts(path, xs, ys)) {
            return null;
        }

        List<Line2D> lines = new ArrayList<>();
        int n = xs.size();
        int nColumn = n / 4;
        double xmin = 0.0, xmax = 0.0, ymin = 0.0, ymax = 0.0, dx = 0, dy = 0;
        double x = 0.0, y = 0.0;
        Line2D line = new Line2D.Double();
        for (int i = 0; i < nColumn; i++) {
            xmin = Collections.min(xs.subList(4 * i, 4 * (i + 1)));
            xmax = Collections.max(xs.subList(4 * i, 4 * (i + 1)));
            ymin = Collections.min(ys.subList(4 * i, 4 * (i + 1)));
            ymax = Collections.max(ys.subList(4 * i, 4 * (i + 1)));
            dx = xmax - xmin;
            dy = ymax - ymin;
            x = 0.5 * (xmin + xmax);
            y = 0.5 * (ymin + ymax);
            if (dx > 2 && dy > 2) {
                return null;
            }
            if (dx > dy) {
                if (dx < 10) {
                    return null;
                }
                line.setLine(xmin, y, xmax, y);
            }
            else {
                if (dy < 10) {
                    return null;
                }
                line.setLine(x, ymin, x, ymax);
            }
            lines.add(line);
        } // end for i
        return lines;
    }

    /**
     * Returns true if the given path is Columnar in Chart.
     * 判断Chart 是否为柱状　即是否为一个或多个有效的矩形区域
     *
     * @param path  路径
     * @param chart 指定 Chart
     * @param color
     * @return
     */
    public static boolean isColumnarInChart(
            GeneralPath path, Chart chart, Color color) {
        // 初步判断是否包含矩形对象 并 取出内部点 做进一步的判断
        List<Double> xs = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
        if (!judgePathContainColumnAndGetPts(path, xs, ys)) {
            return false;
        }

        // 判断 path 的矩形框的　长宽范围是否有效
        double cw = chart.getWidth();
        double ch = chart.getHeight();
        double pw = Collections.max(xs) - Collections.min(xs);
        double ph = Collections.max(ys) - Collections.min(ys);
        // 矩形的长或宽　一般不会太大 (后面杨丽给的测试文件　存在柱状图的范围占据整个Chart的例子 故暂时注释掉)
        //if (pw >= 0.95 * cw || ph >= 0.95 * ch) {
        //if (ph >= 0.95 * ch) {
        //    return false;
        //}
        int n = xs.size();
        if (n == 4 && (pw >= 0.5 * cw && ph >= 0.45 * ch)) {
            return false;
        } else if (n == 4 && (pw < 0.4 || ph < 0.35)) {
            return false;
        }
        // 如果包含多个矩形 且 长宽很小 则认为无效
        if (n >= 8 && (pw < 2 || ph < 1)) {
            return false;
        }

        // 如果矩形集元素尺度太小　保存到chart的容器中　等待后续检测出足够的信息后
        // 再判断是否为柱状图的一部分, 用于柱状图解析数据时用 不能漏掉小矩形
        if (pw <= 0.05 * cw && ph <= 0.05 * ch) {
            savePathIntoPathInfo(path, PathInfo.PathType.COLUMNAR, color, null, chart.barsInfos);
            return false;
        }
        // 如果是单个矩形　也不会太小
        if (n == 4 && (pw <= 0.05 * cw && ph <= 0.05 * ch)) {
            savePathIntoPathInfo(path, PathInfo.PathType.COLUMNAR, color, null, chart.barsInfos);
            return false;
        }
        // 统计矩形的平均高度　如果太小无效　则很有可能是Chart内部网格
        int nColumn = n / 4;
        double hSum = 0.0;
        double wSum = 0.0;
        double w = 0.0, h = 0.0;
        List<Double> hs = new ArrayList<>();
        List<Double> ws = new ArrayList<>();
        for (int i = 0; i < nColumn; i++) {
            h = Collections.max(ys.subList(4 * i, 4 * (i + 1))) - Collections.min(ys.subList(4 * i, 4 * (i + 1)));
            w = Collections.max(xs.subList(4 * i, 4 * (i + 1))) - Collections.min(xs.subList(4 * i, 4 * (i + 1)));
            hSum += h;
            wSum += w;
            hs.add(h);
            ws.add(w);
        }
        double hMean = hSum / nColumn;
        double wMean = wSum / nColumn;
        double hError = 0.0, wError = 0.0;
        for (int i = 0; i < nColumn; i++) {
            hError += Math.abs(hMean - hs.get(i));
            wError += Math.abs(wMean - ws.get(i));
        }
        hError /= nColumn;
        wError /= nColumn;
        if (hMean < 0.025 * ch && wError < 0.02 * cw) {
            if (ph > 0.5 * ch || nColumn >= 60) {
                return false;
            }
            if (wMean > 0.1 * cw && nColumn == 1) {
                if (wMean * hMean > 0.001 * cw * ch) {
                    return true;
                }
            }
            savePathIntoPathInfo(path, PathInfo.PathType.COLUMNAR, color, null, chart.barsInfos);
            return false;
        }

        return true;
    }

    public static boolean isTwoBoxAline(
            double xmin, double xmax, double ymin, double ymax,
            double xminB, double xmaxB, double yminB, double ymaxB,
            boolean testWaterFall, boolean beforeUp, boolean[] isUp) {
        double width = xmax - xmin;
        double widthB = xmaxB - xminB;
        if (Math.abs(width - widthB) > 0.2) {
            return false;
        }
        boolean isTopUp = ChartUtils.equals(ymin - ymaxB, 0.0);
        boolean isTopDown = ChartUtils.equals(ymin - yminB, 0.0);
        boolean isBottomUp = ChartUtils.equals(ymax - ymaxB, 0.0);
        boolean isBottomDown = ChartUtils.equals(ymax - yminB, 0.0);
        if (testWaterFall) {
            if (beforeUp) {
                isUp[0] = isTopUp;
                return isTopUp || isTopDown;
            }
            else {
                isUp[0] = isBottomUp;
                return isBottomDown || isBottomUp;
            }
        }
        return isTopUp || isTopDown || isBottomDown || isBottomUp;
    }

    public static boolean isPathsAline(
            List<ChartPathInfo> pathInfos,
            ChartPathInfo pathInfo,
            boolean testWaterFall) {
        int n = pathInfos.size();
        pathInfos.add(pathInfo);
        boolean isWater = isPathsAline(pathInfos, testWaterFall);
        pathInfos.remove(n);
        return isWater;
    }

    public static void getBarsBoxes(
            List<ChartPathInfo> pathInfos,
            List<PathInfo.PathType> types,
            List<Double> xs, List<Double> ys) {
        List<Double> pathXs = new ArrayList<>();
        List<Double> pathYs = new ArrayList<>();
        xs.clear();
        ys.clear();
        int n = pathInfos.size();
        for (int i = 0; i < n; i++) {
            ChartPathInfo pathInfo = pathInfos.get(i);
            //if (pathInfo.type == PathInfo.PathType.BAR) {
            if (types.contains(pathInfo.type)) {
                ChartPathInfosParser.getPathColumnBox(pathInfo.path, pathXs, pathYs);
                xs.addAll(pathXs);
                ys.addAll(pathYs);
            }
        } // end for i
    }

    public static boolean isPathsAline(
            List<ChartPathInfo> pathInfos, boolean testWaterFall) {
        int n = pathInfos.size();
        if (n <= 1) {
            return false;
        }
        boolean onlyHasBar = pathInfos.stream().allMatch(
                chartPathInfo -> chartPathInfo.type == PathInfo.PathType.BAR ||
                        chartPathInfo.type == PathInfo.PathType.COLUMNAR);
        if (!onlyHasBar) {
            return false;
        }
        // 遍历PathInfo内部的矩形集 找出标示属性值的位置信息
        List<Double> xs = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
        List<PathInfo.PathType> types = new ArrayList<>();
        types.add(PathInfo.PathType.COLUMNAR);
        types.add(PathInfo.PathType.BAR);
        getBarsBoxes(pathInfos, types, xs, ys);
        n = xs.size() / 2;
        if (n == 0) {
            return false;
        }

        // 计算每一个矩形的中心位置 然后排序 从左到右  方便后续顺序分析空间规律
        List<Pair<Double, Integer>> sortXPos = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double xc = 0.5 * (xs.get(2 * i) + xs.get(2 * i + 1));
            sortXPos.add(Pair.of(xc, i));
        }
        if (testWaterFall) {
            Collections.sort(sortXPos, new Comparator<Pair<Double, Integer>>() {
                public int compare(Pair<Double, Integer> p1, Pair<Double, Integer> p2) {
                    return Double.compare(p1.getLeft(), p2.getLeft());
                }
            });
        }

        double xmin = 0, xmax = 0, ymin = 0, ymax = 0;
        double xminB = 0, xmaxB = 0, yminB = 0, ymaxB = 0;
        double xc = 0.0, dx = 0.0;
        boolean isUpLeft = true;
        boolean [] isUp = {false};
        for (int ibar = 0; ibar < n; ibar++) {
            int i = sortXPos.get(ibar).getRight();
            xmin = xs.get(2 * i);
            xmax = xs.get(2 * i + 1);
            ymin = ys.get(2 * i);
            ymax = ys.get(2 * i + 1);
            if (ibar + 1 < n) {
                int j = sortXPos.get(ibar + 1).getRight();
                xminB = xs.get(2 * j);
                xmaxB = xs.get(2 * j + 1);
                yminB = ys.get(2 * j);
                ymaxB = ys.get(2 * j + 1);
                // 在测试瀑布图时  判断是否堆叠或紧邻柱状对象
                xc = sortXPos.get(ibar + 1).getLeft();
                dx = xmaxB - xminB;
                if (testWaterFall && xmin - 0.7 * dx <= xc && xc <= xmax + 0.7 * dx) {
                    return false;
                }
                if (!isTwoBoxAline(
                        xmin, xmax, ymin, ymax,
                        xminB, xmaxB, yminB, ymaxB,
                        testWaterFall, isUpLeft, isUp)) {
                    // 如果测试是否为瀑布图 且 前面已经有数个满足规律 则认为是瀑布图
                    if (testWaterFall && ibar >= 3) {
                        return true;
                    }
                    else {
                        return false;
                    }
                }
                isUpLeft = isUp[0];
            }
        } // end for i
        return true;
    }

    /**
     * 等给定Chart内部足够多信息检测出来后
     * 再次确认后续矩形PathInfo是否为柱状图的一部分 解析柱状图数据用
     *
     * @param chart
     */
    public static void reConfirmChartColumnars(Chart chart) {
        // 再次确认内部矩形对象的方向 (存在某些在开始绘制文字信息时再次出现矩形填充对象, 此时矩形对象没有设置好方向)
        isColumarPathsVertical(chart.pathInfos);

        // 如果显式或隐式候选矩形PathInfo为空集 则返回
        if (chart.barsInfos.isEmpty() || chart.pathInfos.isEmpty()) {
            return;
        }

        List<ChartPathInfo> bars = new ArrayList<>();
        chart.pathInfos.stream().forEach(pathInfo -> {
            if (pathInfo.type == PathInfo.PathType.BAR) {
                bars.add(pathInfo);
            }
        });

        // 设置折线顶点对应的小图形对象 如果存在的话
        setLineChartNodeGraphicObj(chart);

        // 遍历显式柱状PathInfo　测试其内部矩形元素是否与坐标轴位置相近
        boolean bExpBarStartAxis = isPathInfosStartAxis(chart, chart.pathInfos);
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < chart.barsInfos.size(); i++) {
            ChartPathInfo pathInfo = chart.barsInfos.get(i);
            // 跳过 折线顶点小图形对象
            if (pathInfo.type == PathInfo.PathType.LINE_NODE_GRAPHIC_OBJ) {
                continue;
            }
            if (bExpBarStartAxis && isColumnStartFromOneAxis(chart, pathInfo.path)) {
                ids.add(i);
            } else if (isImpBarPathStackedWithExpBarPath(chart, pathInfo.path)) {
                ids.add(i);
            } else if(isPathsAline(bars, pathInfo, false)) {
                ids.add(i);
            }
        } // end for i
        // 找出显示柱状PathInfo的排列方向类型　即垂直还是水平排列
        PathInfo.PathType type = PathInfo.PathType.BAR;
        for (int i = 0; i < chart.pathInfos.size(); i++) {
            ChartPathInfo pathInfo = chart.pathInfos.get(i);
            if (pathInfo.type == PathInfo.PathType.COLUMNAR) {
                type = PathInfo.PathType.COLUMNAR;
            }  // end if
        } // end for i

        // 将隐式柱状PathInfo添加近显式PathInfo中
        for (int i = 0; i < ids.size(); i++) {
            int ith = ids.get(i);
            ChartPathInfo pathInfo = chart.barsInfos.get(ith);
            savePathIntoPathInfo(pathInfo.path, type, pathInfo.color, null, chart.pathInfos);
        }

        // 清空 barsInfos
        chart.barsInfos.clear();
    }

    /**
     * 设置折线对象顶点对应的指示位置用的小图形对象
     * (目前只支持小矩形, 候选添加菱形, 三角形等其他小图形对象)
     *
     * @param chart
     */
    private static void setLineChartNodeGraphicObj(Chart chart) {
        // 如果隐式候选矩形集或pathInfo集为空集 则返回
        if (chart.barsInfos.isEmpty() || chart.pathInfos.isEmpty()) {
            return;
        }

        int zeroColor = 6;
        double zero = 0.01 * chart.getHeight();
        List<Double> xs = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
        List<Double> bxs = new ArrayList<>();
        List<Double> bys = new ArrayList<>();
        for (int i = 0; i < chart.barsInfos.size(); i++) {
            ChartPathInfo barpathInfo = chart.barsInfos.get(i);
            getPathColumnBox(barpathInfo.path, bxs, bys);
            if (bxs.size() != 2) {
                continue;
            }
            Rectangle2D box = barpathInfo.path.getBounds2D();
            double x = 0.5 * (bxs.get(0) + bxs.get(1));
            double y = 0.5 * (bys.get(0) + bys.get(1));
            Color color = barpathInfo.color;
            boolean find = false;
            for (ChartPathInfo pathInfo : chart.pathInfos) {
                // 过滤掉非折线
                if (pathInfo.type != PathInfo.PathType.LINE &&
                        pathInfo.type != PathInfo.PathType.CURVE) {
                    continue;
                }
                // 过滤掉颜色相差很大的对象
                if (Math.abs(color.getRed() - pathInfo.color.getRed()) > zeroColor ||
                        Math.abs(color.getGreen() - pathInfo.color.getGreen()) > zeroColor ||
                        Math.abs(color.getBlue() - pathInfo.color.getBlue()) > zeroColor) {
                    continue;
                }
                // 取出折线顶点集
                boolean getPts = ChartContentScaleParser.getLinePathPoints(pathInfo.path, chart.hAxis, xs, ys);
                if (!getPts) {
                    continue;
                }
                // 循环判断顶点是否在小矩形内 且 与矩形中心距离小于给定阀值
                for (int j = 0; j < xs.size(); j++) {
                    double px = xs.get(j);
                    double py = ys.get(j);
                    double dist = Math.abs(px - x) + Math.abs(py - y);
                    if (dist < zero && box.contains(px, py)) {
                        barpathInfo.type = PathInfo.PathType.LINE_NODE_GRAPHIC_OBJ;
                        find = true;
                        break;
                    }
                }
                if (find) {
                    break;
                }
            } // end for pathInfo
        } // end for i
    }

    /**
     * 尝试从一个矩形Path中 抽取中心线  如果抽取成功 则返回线
     *
     * @param path
     * @return
     */
    public static Line2D getColumnLine(GeneralPath path) {
        // 初步判断是否只包含一个矩形对象
        List<Double> xs = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
        if (!judgePathContainColumnAndGetPts(path, xs, ys) || xs.size() != 4) {
            return null;
        }
        // 计算空间尺寸信息 判断是否为有效水平或垂直直线
        double dx = Math.abs(xs.get(0) - xs.get(2));
        double dy = Math.abs(ys.get(0) - ys.get(2));
        double xmin = Collections.min(xs);
        double xmax = Collections.max(xs);
        double ymin = Collections.min(ys);
        double ymax = Collections.max(ys);
        if (dx > dy) {
            if (dy >= 2.0) {
                return null;
            }
            double y = 0.5 * (ymin + ymax);
            return new Line2D.Double(xmin, y, xmax, y);
        } else {
            if (dx >= 2.0) {
                return null;
            }
            double x = 0.5 * (xmin + xmax);
            return new Line2D.Double(x, ymin, x, ymax);
        }
    }

    /**
     * 判断Chart内部显式柱状Path是否从某个坐标轴出发 很多柱状图都是坐标轴的附近
     *
     * @param chart
     * @param pathInfos
     * @return
     */
    public static boolean isPathInfosStartAxis(
            Chart chart, List<ChartPathInfo> pathInfos) {
        // 遍历显式柱状PathInfo　测试其内部矩形元素是否与坐标轴位置相近
        int icountBar = 0, icountStartAxis = 0;
        for (int i = 0; i < pathInfos.size(); i++) {
            ChartPathInfo pathInfo = pathInfos.get(i);
            if (pathInfo.type == PathInfo.PathType.COLUMNAR || pathInfo.type == PathInfo.PathType.BAR) {
                if (isColumnStartFromOneAxis(chart, pathInfo.path)) {
                    icountStartAxis++;
                }
                icountBar++;
            }  // end if
        } // end for i
        if (icountBar == 0) {
            return false;
        }

        // 如果　大部分的显式柱状Path都是从某个坐标轴出发　则返回 true
        boolean bStartAxis = (1.0 * icountStartAxis / icountBar > 0.1) ? true : false;
        return bStartAxis;
    }

    /**
     * 测试给定Path内的矩形是否在给定Chart的坐标轴上
     *
     * @param chart
     * @param path
     * @return
     */
    public static boolean isColumnStartFromOneAxis(Chart chart, GeneralPath path) {
        // 判断是否与水平　或　左右侧垂直坐标轴位置相近
        // 只要有一个坐标轴符合　则返回 true
        if (isColumnStartFromAxis(chart, path, 2) ||
                isColumnStartFromAxis(chart, path, 0) ||
                isColumnStartFromAxis(chart, path, 1)) {
            return true;
        }
        return false;
    }

    /**
     * 测试给定Path内的矩形是否在给定Chart的指定坐标轴上
     *
     * @param chart
     * @param path
     * @param ithSide
     * @return
     */
    public static boolean isColumnStartFromAxis(Chart chart, GeneralPath path, int ithSide) {
        // 计算指定坐标轴的　位置信息
        double xyAxis = -1.0;
        if (ithSide == 0) {
            if (chart.lvAxis == null) {
                return false;
            }
            xyAxis = chart.lvAxis.getX1();
        } else if (ithSide == 1) {
            if (chart.rvAxis == null) {
                return false;
            }
            xyAxis = chart.rvAxis.getX1();
        } else {
            if (chart.hAxis == null) {
                return false;
            }
            xyAxis = chart.hAxis.getY1();
        }

        // 计算给定Path的范围信息
        List<Double> xs = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
        getPathColumnBox(path, xs, ys);
        int n = xs.size() / 2;
        // 优化比列系数
        double lenMin = 0.01 * chart.getWidth();
        for (int i = 0; i < n; i++) {
            // 如果有一个边界与坐标轴位置相近　则表示挨着坐标轴
            if ((Math.abs(xs.get(2 * i) - xyAxis) < lenMin) ||
                    (Math.abs(xs.get(2 * i + 1) - xyAxis) < lenMin) ||
                    (Math.abs(ys.get(2 * i) - xyAxis) < lenMin) ||
                    (Math.abs(ys.get(2 * i + 1) - xyAxis) < lenMin)) {
                return true;
            }
        } // end for i
        return false;
    }

    public static List<Double> getXAxisScaleLineSplitPts(Chart chart) {
        List<Double> xs = new ArrayList<>();
        int n = chart.axisScaleLines.size();
        for (int i = 0; i < n; i++) {
            Line2D line = chart.axisScaleLines.get(i);
            Rectangle2D box = line.getBounds2D();
            if (box.getWidth() < box.getHeight()) {
                xs.add(box.getCenterX());
            }
        } // end for i
        Collections.sort(xs);
        return xs;
    }

    public static boolean pathSplitMatchXAxisScaleLine(List<Double> pos, List<Double> axisScalePts) {
        int npos = pos.size();
        int n = axisScalePts.size();
        if (npos <= 1 || n <= 1) {
            return false;
        }
        double width = Collections.max(pos) - Collections.min(pos);
        width = width / (npos - 1);
        final double dx = 0.2 * width;
        int nMatch = 0;
        for (int i = 0; i < n; i++) {
            double x = axisScalePts.get(i);
            if (pos.stream().anyMatch(pt -> Math.abs(x - pt) <= dx)) {
                nMatch++;
            }
        } // end for i
        // 如果匹配数目太少 则返回false
        if (1.0 * nMatch / n < 0.5 || nMatch != npos) {
            return false;
        }
        else {
            return true;
        }
    }

    public static void setUnSplitText(Chart chart) {
        List<Double> xs = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
        List<PathInfo.PathType> types = new ArrayList<>();
        types.add(PathInfo.PathType.BAR);
        ChartPathInfosParser.getBarsBoxes(chart.pathInfos, types, xs, ys);
        int n = xs.size() / 2;
        if (n == 0) {
            return;
        }
        for (TextChunk chunk : chart.chunksRemain) {
            if (chunk.getDirection() != TextDirection.LTR) {
                continue;
            }
            double xmin = chunk.getMinX();
            double xmax = chunk.getMaxX();
            for (int i = 0; i < n; i++) {
                double xminBar = xs.get(2 * i);
                double xmaxBar = xs.get(2 * i + 1);
                double dx = 0.1 * (xmaxBar - xminBar);
                if (xminBar - dx < xmin && xmax < xmaxBar + dx) {
                    ChunkUtil.setChunkType(chunk, ChunkStatusType.MERGE_TYPE);
                    break;
                }
            } // end for i
        } // end for chunk
    }

    /**
     * 基于给定的图形对象相邻间隔平分位置  尝试分割或合并适当的Chunk对象 优化刻度
     * @param chart
     * @param pos
     * @param split
     */
    public static void splitAndMergeChartText(Chart chart, List<TextChunk> chunks, List<Double> pos, boolean split) {
        // 暂时只处理有水平坐标轴的
        if (chart.hAxis == null) {
            return;
        }
        if (pos == null || pos.size() <= 1) {
            return;
        }

        // 如果分割点相比轴标数目过多　则直接返回
        List<Double> axisScalePts = getXAxisScaleLineSplitPts(chart);
        int nScalePts = axisScalePts.size();
        if (nScalePts >= 2 && pos.size() > 2 * nScalePts) {
            return;
        }

        boolean match = pathSplitMatchXAxisScaleLine(pos, axisScalePts);
        double width = chart.hAxis.getBounds().getWidth();
        for (int i = 0; i < chunks.size(); i++) {
            TextChunk chunk = chunks.get(i);
            TextDirection direction = chunk.getDirection();
            // 过滤掉非水平方向的
            if (direction != TextDirection.LTR) {
                continue;
            }
            if (chunk.getMinY() <= chart.hAxis.getY1()) {
                continue;
            }
            // 忽略掉设置图例或忽略标记的
            ChunkStatusType type = ChunkUtil.getChunkType(chunk);
            if (type == ChunkStatusType.LEGEND_TYPE ||
                    type == ChunkStatusType.IGNORE_TYPE ||
                    type == ChunkStatusType.DATA_SOURCE_TYPE) {
                continue;
            }
            // 如果是高概率标题对象　则跳过
            String text = chunk.getText().trim();
            if (!StringUtils.isEmpty(text) &&
                    ChartTitleLegendScaleParser.bHighProbabilityChartTitle(text)) {
                continue;
            }
            // 判断是否为不可分割的时间类型文本对象
            if (ChartTitleLegendScaleParser.isUnSplitTimeString(chunk)) {
                continue;
            }
            double scale = chunk.getWidth() / width;
            if (split) {
                // 过滤掉比分割总宽度小的　待分割算法稳定后　可以放宽限制
                if (scale > 1.0) {
                    // 分割当前TextChunk
                    splitChunk(chunks, i, pos);
                }
                else if (0.1 <= scale && match) {
                    splitChunk(chunks, i, axisScalePts);
                }
                else {
                    continue;
                }
            }
            else {
                if (scale >= 0.2) {
                    continue;
                }
                // 如果能合并 则再次重复处理当前TextChunk
                else if (match && mergeChunk(chunks, i, axisScalePts)) {
                    i = i - 1;
                }
            }
        } // end for i
    }

    public static int locateChunkInPosList(TextChunk chunk, List<Double> pos) {
        if (StringUtils.isEmpty(chunk.getText())) {
            return -1;
        }
        List<TextElement> elements = chunk.getElements();
        int neles = elements.size();
        TextElement eleFirst = elements.get(0);
        TextElement eleEnd = elements.get(neles - 1);
        double xFirst = eleFirst.getCenterX();
        double xEnd = eleEnd.getCenterX();
        int iposFirst = locateInPosList(xFirst, pos);
        int iposEnd = locateInPosList(xEnd, pos);
        if (iposFirst == iposEnd) {
            return iposFirst;
        }
        else {
            return -1;
        }
    }

    public static boolean mergeChunk(List<TextChunk> chunks, int i, List<Double> pos) {
        // 如果链表最后一个元素 则不处理
        if (i + 1 >= chunks.size()) {
            return false;
        }
        TextChunk chunk = chunks.get(i);
        TextChunk chunkNext = chunks.get(i + 1);
        // 如果不是水平方向 则不处理
        if (chunk.getDirection() != TextDirection.LTR || chunkNext.getDirection() != TextDirection.LTR) {
            return false;
        }
        // 如果相邻组号相差太多 则不处理
        int groupIndexDiff = chunkNext.getFirstElement().getGroupIndex() - chunk.getLastElement().getGroupIndex();
        if (groupIndexDiff >= 3) {
            return false;
        }
        int ipos = locateChunkInPosList(chunk, pos);
        int iposNext = locateChunkInPosList(chunkNext, pos);
        // 如果落在同一个分割位置
        if (ipos == iposNext && ipos != -1) {
            double dy = chunk.getCenterY() - chunkNext.getCenterY();
            double height = 0.5 * (ChunkUtil.getMaxFontHeight(chunk) + ChunkUtil.getMaxFontHeight(chunk));
            if (Math.abs(dy) < 0.1 * height) {
                chunk.merge(chunkNext);
                chunks.remove(i + 1);
                return true;
            }
        }
        return false;
    }

    public static void splitChunk(List<TextChunk> chunks, int i, List<Double> pos) {
        TextChunk chunk = chunks.get(i);
        // 如果不是水平方向 则不处理
        if (chunk.getDirection() != TextDirection.LTR) {
            return;
        }
        List<TextElement> elements = chunk.getElements();
        int istart = 0, istartpos = 0, countChunk = 0;
        int neles = elements.size();
        for (int j = 0; j < neles; j++) {
            TextElement ele = elements.get(j);
            double x = ele.getCenterX();
            int ipos = locateInPosList(x, pos);
            // 第一个元素的定位
            if (j == 0) {
                istartpos = ipos;
            }
            if (ipos == istartpos) {
                continue;
            }
            else {
                TextChunk chunkNew = buildSubTextChunk(elements, istart, j);
                if (countChunk == 0) {
                    chunks.set(i, chunkNew);
                }
                else {
                    chunks.add(i + countChunk, chunkNew);
                }
                istartpos = ipos;
                istart = j;
                countChunk++;
            }
        } // end for j
        // 添加最后一个分割TextChunk
        if (istart >= 1) {
            TextChunk chunkNew = buildSubTextChunk(elements, istart, neles);
            chunks.add(i + countChunk, chunkNew);
        }
    }

    /**
     * 从给定TextElement集中指定区间元素构建TextChunk
     * @param elements
     * @param istart
     * @param iend
     * @return
     */
    public static TextChunk buildSubTextChunk(
            List<TextElement> elements, int istart, int iend) {
        List<TextElement> subEles = elements.subList(istart, iend);
        List<TextElement> elesNew = new ArrayList<>();
        subEles.stream().forEach(textElement -> {
            if (!(StringUtils.isEmpty(textElement.getText().trim()) &&
                    textElement.isMocked())) {
                elesNew.add(textElement); }});
        TextChunk chunkNew = new TextChunk(elesNew);
        return chunkNew;
    }

    public static int locateInPosList(double x, List<Double> pos) {
        int n = pos.size();
        for (int i = 0; i < n; i++) {
            if (x <= pos.get(i)) {
                return i;
            }
        }
        return n;
    }

    /**
     * 基于Chart内柱状和折线顶点间隔信息　计算刻度分割位置信息
     * @param chart
     * @return
     */
    public static List<Double> getSplitPosition(Chart chart) {
        // PathInfo对象太少 直接返回
        int n = chart.pathInfos.size();
        if (n == 0) {
            return null;
        }

        // 判断是否为堆叠柱状图对象
        boolean bStackedBar = isChartStackedBar(chart);

        // 计算有效宽度  用来判断柱状或折现对象宽度的有效性的参考
        double width = chart.getWidth();
        if (chart.hAxis != null) {
            width = chart.hAxis.getBounds().getWidth();
        }

        // 循环遍历　计算宽度最大的柱状对象和折线对象
        double barWidth = -1.0, lineWidth = -1.0;
        int ithBar = -1, ithLine = -1;
        int countBar = 0, countLine = 0;
        for (int i = 0; i < n; i++) {
            ChartPathInfo pathInfo = chart.pathInfos.get(i);
            // 宽度占比太小的　跳过
            double w = pathInfo.path.getBounds2D().getWidth();
            if (w / width <= 0.6) {
                continue;
            }
            PathInfo.PathType type = pathInfo.type;
            if (type == PathInfo.PathType.BAR || type == PathInfo.PathType.COLUMNAR) {
                countBar++;
                if (barWidth < w) {
                    barWidth = w;
                    ithBar = i;
                }
            }
            else if (type == PathInfo.PathType.LINE || type == PathInfo.PathType.CURVE) {
                countLine++;
                if (lineWidth < w) {
                    lineWidth = w;
                    ithLine = i;
                }
            }
        } // end for i
        List<Double> posBar = new ArrayList<>();
        // 计算柱状对象的分割位置
        if (ithBar != -1) {
            ChartPathInfo barPathInfo = chart.pathInfos.get(ithBar);
            posBar = getBarSplitPosition(barPathInfo);
        }
        // 计算折线对象的分割位置
        List<Double> posLine = new ArrayList<>();
        if (ithLine != -1) {
            ChartPathInfo barPathInfo = chart.pathInfos.get(ithLine);
            posLine = getLineSplitPosition(chart, barPathInfo);
        }
        boolean barValid = posBar != null && !posBar.isEmpty();
        boolean lineValid = posLine != null && !posLine.isEmpty();
        if (barValid && lineValid) {
            if (barWidth <= lineWidth) {
                return posLine;
            }
            else if (!bStackedBar && countBar >= 2){
                return posLine;
            }
            else {
                return posBar;
            }
        }
        else if (barValid && !lineValid) {
            if (bStackedBar || countBar == 1) {
                return posBar;
            }
        }
        else if (!barValid && lineValid) {
            return posLine;
        }
        return null;
    }

    public static List<Double> getLineSplitPosition(Chart chart, ChartPathInfo pathInfo) {
        // 找出Line Path 上的点集的X Y 坐标信息
        List<Double> xs = new ArrayList<>();     // 折线顶点X坐标集
        List<Double> ys = new ArrayList<>();     // 折线顶点Y坐标集
        boolean bGetPts = ChartContentScaleParser.getLinePathPoints(pathInfo.path, chart.hAxis, xs, ys);
        if (!bGetPts) {
            return null;
        }

        List<Double> pos =  new ArrayList<>();
        int n = xs.size();
        //　点太多太少时　　直接返回
        if (n <= 2 || n >= 20) {
            return null;
        }
        double xA = 0.0, xB = 0.0;
        double w = chart.getArea().getWidth();
        double splitPt = 0.0;
        for (int i = 0; i < n - 1; i++) {
            xA = xs.get(i);
            xB = xs.get(i + 1);
            if (Math.abs(xA - xB) < 0.01 * w) {
                return null;
            }
            splitPt = 0.5 * (xA + xB);
            pos.add(splitPt);
        }
        return pos;
    }

    public static List<Double> getBarSplitPosition(ChartPathInfo pathInfo) {
        // 排序
        boolean bVertical = pathInfo.type == PathInfo.PathType.BAR; // 判断是为垂直方向或水平方向的柱状图
        GeneralPath sortedPath = reSortColumn(pathInfo.path, bVertical);

        // 计算柱状Path内部矩形对象的中心点集
        List<Double> cxys = new ArrayList<>();
        getBarPathInfoCenterPts(sortedPath, cxys);
        int n = cxys.size() / 4;
        if (n <= 1 || n >= 20) {
            return null;
        }

        List<Double> pos =  new ArrayList<>();
        double xA = 0.0, yA = 0.0, xB = 0.0, yB = 0.0, w = 0.0, h = 0.0;
        double splitPt = 0.0;
        for (int i = 0; i < n - 1; i++) {
            xA = cxys.get(4 * i);
            xB = cxys.get(4 * (i + 1));
            yA = cxys.get(4 * i + 1);
            yB = cxys.get(4 * (i + 1) + 1);
            w = 0.5 * (cxys.get(4 * i + 2) + cxys.get(4 * (i + 1) + 2));
            h = 0.5 * (cxys.get(4 * i + 3) + cxys.get(4 * (i + 1) + 3));
            // 垂直方向
            if (bVertical) {
                // 间隔太小　很有可能是堆叠柱状　所有跳过
                if (Math.abs(xA - xB) < 0.2 * w) {
                    continue;
                }
                splitPt = 0.5 * (xA + xB);
            }
            // 水平方向
            else {
                if (Math.abs(yA - yB) < 0.2 * h) {
                    continue;
                }
                splitPt = 0.5 * (yA + yB);
            }
            pos.add(splitPt);
        } // end for i
        return pos;
    }

    /**
     * 判断给定的隐式柱状Path是否与Chart内部显式柱状Path在X或Y方向堆叠
     *
     * @param chart
     * @param path
     * @return
     */
    public static boolean isImpBarPathStackedWithExpBarPath(Chart chart, GeneralPath path) {
        // 计算给定隐式柱状Path内部矩形对象的中心点集
        List<Double> cxysImp = new ArrayList<>();
        getBarPathInfoCenterPts(path, cxysImp);

        // 计算给定Chart内部显式柱状Path内部矩形对象的中心点集
        List<Double> cxysExp = new ArrayList<>();
        int icount = 0;
        for (int i = 0; i < chart.pathInfos.size(); i++) {
            ChartPathInfo pathInfo = chart.pathInfos.get(i);
            if (pathInfo.type == PathInfo.PathType.COLUMNAR || pathInfo.type == PathInfo.PathType.BAR) {
                getBarPathInfoCenterPts(pathInfo.path, cxysExp);
                // 判断两个中心点集　是否在其中一个方向上堆叠
                boolean testXAxis = pathInfo.type == PathInfo.PathType.BAR ? true : false;
                if (!isPointsOverlapAxis(cxysImp, cxysExp, testXAxis)) {
                    continue;
                }
                icount++;
            }  // end if
        } // end for i
        if (icount >= 1) {
            return true;
        } else {
            return false;
        }
    }

    /**
     * 用来对判定为水平柱状类型的图表中　再次进行方向的判断
     * 目前计算Chart中最大矩形的面积来判断方向　在某些情况下不太准确
     * @param pathInfos
     * @return
     */
    public static boolean isVerticalBarBaseWidthHeight(List<ChartPathInfo> pathInfos) {
        int nBox = 0;
        double width = 0.0, height = 0.0, wh = 0.0;
        List<Double> widths = new ArrayList<>();
        List<Double> heights = new ArrayList<>();
        List<Double> cxysA = new ArrayList<>();
        for (int i = 0; i < pathInfos.size(); i++) {
            ChartPathInfo pathInfo = pathInfos.get(i);
            if (pathInfo.type != PathInfo.PathType.BAR &&
                    pathInfo.type != PathInfo.PathType.COLUMNAR) {
                continue;
            }
            getBarPathInfoCenterPts(pathInfo.path, cxysA);
            nBox = cxysA.size() / 4;
            for (int j = 0; j < nBox; j++) {
                wh = cxysA.get(4 * j + 2);
                widths.add(wh);
                width += wh;
                wh = cxysA.get(4 * j + 3);
                heights.add(wh);
                height += wh;
            } // end for j
        } // end for i
        nBox = widths.size();
        if (nBox <= 1) {
            return true;
        }
        width /= nBox;
        height /= nBox;
        double widthError = 0.0, heigthError = 0.0;
        for (int i = 0; i < nBox; i++) {
            widthError += Math.abs(widths.get(i) - width);
            heigthError += Math.abs(heights.get(i) - height);
        } // end for i
        if (widthError < heigthError) {
            return true;
        }
        else {
            return false;
        }
    }

    /**
     * 判断Chart是否为堆叠柱状图
     *
     * @param chart
     * @return
     */
    public static boolean isChartStackedBar(Chart chart) {
        // 判断数据有效性
        if (chart == null || (chart.type != ChartType.COLUMN_CHART &&
                chart.type != ChartType.BAR_CHART &&
                chart.type != ChartType.COMBO_CHART)) {
            return false;
        }
        // 如果 pathInfos 内对象过少　返回 false
        if (chart.pathInfos.size() <= 1) {
            return false;
        }
        // 取出 pathInfos 内柱状对象
        List<Integer> barPathInfos = new ArrayList<>();
        for (int i = 0; i < chart.pathInfos.size(); i++) {
            ChartPathInfo pathInfo = chart.pathInfos.get(i);
            if (pathInfo.type == PathInfo.PathType.BAR ||
                    pathInfo.type == PathInfo.PathType.COLUMNAR) {
                barPathInfos.add(i);
            }
        }
        if (barPathInfos.size() <= 1) {
            return false;
        }

        // 选取前两个 PathInfo  计算起内部矩形元素的位置信息
        ChartPathInfo pathInfoA = chart.pathInfos.get(barPathInfos.get(0));
        ChartPathInfo pathInfoB = chart.pathInfos.get(barPathInfos.get(1));
        List<Double> cxysA = new ArrayList<>();
        List<Double> cxysB = new ArrayList<>();
        getBarPathInfoCenterPts(pathInfoA.path, cxysA);
        getBarPathInfoCenterPts(pathInfoB.path, cxysB);

        // 判断两个中心点集　是否在其中一个方向上堆叠
        boolean testXAxis = pathInfoA.type == PathInfo.PathType.BAR ? true : false;
        if (isPointsOverlapAxis(cxysA, cxysB, testXAxis)) {
            return true;
        }
        return false;
    }

    /**
     * 判断两个给定点集是否在X或Y方向上堆叠
     *
     * @param cxysA
     * @param cxysB
     * @return
     */
    public static boolean isPointsOverlapAxis(
            List<Double> cxysA, List<Double> cxysB, boolean testXAxis) {
        // 循环遍历　判断是否堆叠 集X坐标或Y坐标近似相同
        int nA = cxysA.size() / 4;
        int nB = cxysB.size() / 4;
        boolean bSameX;
        boolean bSameY;
        int icount = 0;
        for (int i = 0; i < nA; i++) {
            // 遍历第二个点集
            for (int j = 0; j < nB; j++) {
                bSameX = true;
                bSameY = true;
                // 判断 X 坐标是否一致
                if (!ChartUtils.equals(cxysA.get(4 * i), cxysB.get(4 * j))) {
                    bSameX = false;
                }
                // 判断 Y 坐标是否一致
                if (!ChartUtils.equals(cxysA.get(4 * i + 1), cxysB.get(4 * j + 1))) {
                    bSameY = false;
                }
                // 判断宽度或高度是否都不一致
                if (!ChartUtils.equals(cxysA.get(4 * i + 2), cxysB.get(4 * j + 2)) &&
                        !ChartUtils.equals(cxysA.get(4 * i + 3), cxysB.get(4 * j + 3))) {
                    bSameX = false;
                    bSameY = false;
                }

                // 筛除覆盖而非堆叠
                if (!ChartUtils.equals(Math.abs(cxysA.get(4 * i + 3) + cxysB.get(4 * j + 3)) / 2,
                                        Math.abs(cxysA.get(4 * i + 1) - cxysB.get(4 * j + 1)))
                        && !ChartUtils.equals(Math.abs(cxysA.get(4 * i + 2) + cxysB.get(4 * j + 2)) / 2,
                                                Math.abs(cxysA.get(4 * i) - cxysB.get(4 * j)))){
                    bSameX = false;
                    bSameY = false;
                }

                // 如果有一个坐标相同　则计数加1
                // 如果有一个坐标相同　则计数加1
                if (testXAxis && bSameX) {
                    icount++;
                    break;
                } else if (!testXAxis && bSameY) {
                    icount++;
                    break;
                }
            } // end for j
        } // end for i
        return icount >= 1 ? true : false;
    }

    /**
     * 计算给定Path内矩形对象的中心点集
     *
     * @param path
     * @param cxys
     */
    public static void getBarPathInfoCenterPts(
            GeneralPath path, List<Double> cxys) {
        // 计算给定Path的范围信息
        List<Double> xs = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
        getPathColumnBox(path, xs, ys);
        // 计算每一矩形的中心点
        int n = xs.size() / 2;
        cxys.clear();
        for (int i = 0; i < n; i++) {
            cxys.add(0.5 * (xs.get(2 * i) + xs.get(2 * i + 1)));
            cxys.add(0.5 * (ys.get(2 * i) + ys.get(2 * i + 1)));
            cxys.add(Math.abs(xs.get(2 * i + 1) - xs.get(2 * i)));
            cxys.add(Math.abs(ys.get(2 * i + 1) - ys.get(2 * i)));
        }
    }

    /**
     * Returns true if the given path is Area in Chart.
     * 判断Path是否为Chart的面积区域
     *
     * @param path  路径
     * @param chart 指定 Chart
     * @return
     */
    public static boolean isAreaInChart(
            GeneralPath path, Chart chart) {
        // 判断 path 的矩形框的　长宽范围是否有效
        Rectangle2D rect = path.getBounds2D();
        double pw = rect.getWidth();
        double cw = chart.getWidth();
        double ph = rect.getHeight();
        double ch = chart.getHeight();
        // 一种颜色的面积可能分成很多小块 每小块宽高都很小 故修改空间范围约束条件 后续可能需要持续改进
        if (pw <= 0.002 * cw || ph <= 0.05 * ch) {
            return false;
        }

        // 重置close点为起始点 方便处理
        GeneralPath pathNew = PathUtils.resetPathClosePoint(path);

        // 遍历path的点集 判断有效性 只统计在Chart的区域范围内的点  一般都是path构成的面积很大，然后裁剪区域绘制
        PathIterator iter = pathNew.getPathIterator(null);
        double[] coords = new double[12];
        int count = 0, count_moveto = 0;
//        List<Double> xs = new ArrayList<Double>();
//        List<Double> ys = new ArrayList<Double>();
        while (!iter.isDone()) {
            switch (iter.currentSegment(coords)) {
                case PathIterator.SEG_MOVETO:
                    count_moveto++;
                    if (count_moveto >= 2) {
                        return false;
                    }
                case PathIterator.SEG_LINETO:
                    if (coords[0] >= chart.getLeft()) {
//                        xs.add(coords[0]);
//                        ys.add(coords[1]);
                    } // end if
                    count++;
                    break;

                case PathIterator.SEG_CLOSE:
                    // 一般的面积区域　都是很多点集构成  以后可以调整　用来加速判断用　提高效率
                    if (count <= 10)
                        return false;
                    break;

                default:
                    return false;
            }  // end switch
            iter.next();
        }  // end while

        // 尝试取出面积对象内部有效点对集　如果能取到 则返回 true
        List<Double> xsM = new ArrayList<>();
        List<Double> ysU = new ArrayList<>();
        List<Double> ysD = new ArrayList<>();
        Line2D axis = chart.hAxis;
        if (chart.hAxis == null) {
            Rectangle2D box = chart.getArea();
            axis = new Line2D.Double(box.getMinX(), box.getMinY(), box.getMaxX(), box.getMinY());
        }
        boolean bGetPts = ChartContentScaleParser.getPathAreaPoints(path, axis, xsM, ysU, ysD);
        int n = xsM.size();
        if (!bGetPts || n <= 10) {
            return false;
        }

        double x = xsM.get(0), y = ysU.get(0);
        double maxLen = 0.0, len = 0.0;
        for (int i = n - 1; i >= 0; i--) {
            xsM.add(xsM.get(i));
            ysU.add(ysD.get(i));
            len = Math.abs(ysD.get(i) - ysU.get(i));
            maxLen = maxLen < len ? len : maxLen;
        }
        xsM.add(x);
        ysU.add(y);
        double area = getPathArea(xsM, ysU);
        if (maxLen < 0.001 * ch) {
            return false;
        }
        return area > 0.01 * ch * cw;
    }

    public static List<Pair<Double, Double>> splitDashSegment(GeneralPath path, Rectangle2D box) {
        double cw = box.getWidth();
        double ch = box.getHeight();

        // 获取path关键点信息
        List<Double> xs = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
        List<Integer> types = new ArrayList<>();
        if (!PathUtils.getPathKeyPtsInfo(path, xs, ys, types)) {
            return null;
        }

        // 遍历所有的点  以PathIterator.SEG_CLOSE为分割类型 分成多个小对象
        int n = types.size();
        List<Double> xsNew = new ArrayList<>();
        List<Double> ysNew = new ArrayList<>();
//        List<Double> cxs = new ArrayList<>();
//        List<Double> cys = new ArrayList<>();
        List<Pair<Double, Double>> pts = new ArrayList<>();
        double cx = 0, cy = 0;
        for (int i = 0; i < n; i++) {
            xsNew.add(xs.get(i));
            ysNew.add(ys.get(i));
            if (types.get(i) == PathIterator.SEG_CLOSE) {
                List<Point2D> obb = MinOrientedBoundingBoxComputer.computOBB(xsNew, ysNew);
                double area = MinOrientedBoundingBoxComputer.getObbArea(obb);
                if (area >= 0.002 * cw * ch) {
                    return null;
                }
                // 计算中心点作为代表点
                cx = (obb.get(0).getX() + obb.get(1).getX() + obb.get(2).getX() + obb.get(3).getX()) / 4;
                cy = (obb.get(0).getY() + obb.get(1).getY() + obb.get(2).getY() + obb.get(3).getY()) / 4;
                pts.add(Pair.of(cx, cy));
                xsNew.clear();
                ysNew.clear();
            }
        } // end for i
        return pts;
    }

    public static GeneralPath isFilledDashLine(GeneralPath path, Rectangle2D box) {
        // 宽度相对于给定区域不会太小
        Rectangle2D rect = path.getBounds2D();
        double pw = rect.getWidth();
        double ph = rect.getHeight();
        double cw = box.getWidth();
        double ch = box.getHeight();
        if (pw <= 0.5 * cw || ph < 0.05 * ch) {
            return null;
        }

        List<Pair<Double, Double>> pts = splitDashSegment(path, box);
        if (pts == null || pts.size() < 20) {
            return null;
        }

        // 将搜集到的中心点集 排序Y
        Collections.sort(pts, new Comparator<Pair<Double, Double>>(){
            public int compare(Pair<Double, Double> p1, Pair<Double, Double> p2){
                return Double.compare(p1.getRight(), p2.getRight());
            }
        });
        // 计算最低点同高度点集
        double ymin = pts.get(0).getRight();
        double xmin = pts.get(0).getLeft();
        double xmax = xmin;
        for (Pair<Double, Double> pt : pts) {
            double y = pt.getRight();
            if (ymin - 0.05 * ph <= y && y <= ymin + 0.05 * ph) {
                xmin = Math.min(xmin, pt.getLeft());
                xmax = Math.max(xmax, pt.getLeft());
            }
        }
        // 如果最低位置宽度过大  则很有可能是网格线对象
        if (xmax - xmin >= 0.9 * pw) {
            return null;
        }

        // 将搜集到的中心点集 排序X
        Collections.sort(pts, new Comparator<Pair<Double, Double>>(){
            public int compare(Pair<Double, Double> p1, Pair<Double, Double> p2){
                return Double.compare(p1.getLeft(), p2.getLeft());
            }
        });

        // 将每一个中心点连成新的折线path对象
        GeneralPath pathNew = new GeneralPath();
        pathNew.moveTo(pts.get(0).getLeft(), pts.get(0).getRight());
        for (int i = 1; i < pts.size(); i++) {
            pathNew.lineTo(pts.get(i).getLeft(), pts.get(i).getRight());
        }
        return pathNew;
    }

    /**
     * 判断是否为填充形式的折线对象 即其封闭区域的面积非常小, 视觉上表现为线宽稍大的折线
     * 如果是则抽出其上边界 并保存为GenralPath
     *
     * @param path
     * @param box
     * @return
     */
    public static GeneralPath isFilledLine(GeneralPath path, Rectangle2D box) {
        // 宽度相对于给定区域不会太小
        Rectangle2D rect = path.getBounds2D();
        double pw = rect.getWidth();
        double cw = box.getWidth();
        if (pw <= 0.05 * cw) {
            return null;
        }

        // 遍历path的点集 判断有效性
        PathIterator iter = path.getPathIterator(null);
        double[] coords = new double[12];
        List<Double> xs = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
        // 保持曲线段的控制点　采样时原样采取光滑曲线段用
        List<List<Double>> controlPts = new ArrayList<>();
        while (!iter.isDone()) {
            switch (iter.currentSegment(coords)) {
                case PathIterator.SEG_MOVETO:
                    if (xs.size() >= 1) {
                        return null;
                    }
                case PathIterator.SEG_LINETO:
                    xs.add(coords[0]);
                    ys.add(coords[1]);
                    controlPts.add(new ArrayList<>());
                    break;
                case PathIterator.SEG_CUBICTO:
                    xs.add(coords[4]);
                    ys.add(coords[5]);
                    List<Double> cpts = new ArrayList<>();
                    cpts.add(coords[0]);
                    cpts.add(coords[1]);
                    cpts.add(coords[2]);
                    cpts.add(coords[3]);
                    controlPts.add(cpts);
                    break;

                case PathIterator.SEG_CLOSE:
                    if (xs.size() < 12) {
                        return null;
                    }
                    break;
                default:
                    return null;
            }  // end switch
            iter.next();
        }  // end while
        if (xs.size() < 12) {
            return null;
        }

        // 判断抽取点集的有效性
        if (xs.size() != controlPts.size() || xs.size() != ys.size()) {
            return null;
        }

        // 计算封闭区域的面积　折线对象面积通常很小
        double area = getPathArea(xs, ys);
        if (Math.abs(area) >= 0.02 * box.getWidth() * box.getHeight()) {
            return null;
        }

        // 抽取从左到右的一段采样点 同时保持光滑性
        GeneralPath pathNew = extractPathFromPts(xs, ys, controlPts);

        // 光滑化折线 X轴坐标递增
        return smoothLine(pathNew);
    }

    public static double getPathArea(List<Double> xs, List<Double> ys) {
        // 计算封闭区域的面积　折线对象面积通常很小
        double area = 0.0;
        double x1 = 0.0, y1 = 0.0, x2 = 0.0, y2 = 0.0;
        for (int i = 0; i < xs.size() - 1; i++) {
            x1 = xs.get(i);
            y1 = ys.get(i);
            x2 = xs.get(i + 1);
            y2 = ys.get(i + 1);
            area += 0.5 * (x1 * y2 - x2 * y1);
        }
        return Math.abs(area);
    }

    /**
     * 从给定的点集中抽取左端到右端的一段点集
     * @param xs
     * @param ys
     * @param controlPts
     * @return
     */
    public static GeneralPath extractPathFromPts(
            List<Double> xs, List<Double> ys, List<List<Double>> controlPts) {
        // 计算左右两端位置
        double xmin = Collections.min(xs);
        double xmax = Collections.max(xs);
        int imin = xs.indexOf(xmin);
        int imax = xs.indexOf(xmax);
        // 优化给定点集是封闭状态　为了计算方便　可以连续加载两次
        if (imin > imax) {
            imax += xs.size();
            xs.addAll(xs);
            ys.addAll(ys);
            controlPts.addAll(controlPts);
        }

        // 从左侧点开始
        GeneralPath path = new GeneralPath();
        path.moveTo(xs.get(imin), ys.get(imin));
        for (int i = imin + 1; i <= imax; i++) {
            List<Double> cpts = controlPts.get(i);
            if (cpts != null) {
                // 如果还有２个控制点　则引入光滑曲线段
                if (cpts.size() == 4) {
                    path.curveTo(cpts.get(0), cpts.get(1), cpts.get(2), cpts.get(3), xs.get(i), ys.get(i));
                }
                else {
                    path.lineTo(xs.get(i), ys.get(i));
                }
            }
            else {
                path.lineTo(xs.get(i), ys.get(i));
            }
        } // end for i
        return (GeneralPath)path.clone();
    }

    /**
     * 光滑给定折线对象 让X坐标呈递增方式
     * @param path
     * @return
     */
    public static GeneralPath smoothLine(GeneralPath path) {
        PathIterator iter = path.getPathIterator(null);
        double[] coords = new double[12];
        double x = 0.0, y = 0.0;
        GeneralPath pathNew = (GeneralPath) path.clone();
        pathNew.reset();
        while (!iter.isDone()) {
            Point2D pt = pathNew.getCurrentPoint();
            switch (iter.currentSegment(coords)) {
                case PathIterator.SEG_MOVETO:
                    x = coords[0];
                    y = coords[1];
                    pathNew.moveTo(x, y);
                    break;
                case PathIterator.SEG_LINETO:
                    x = coords[0];
                    y = coords[1];
                    if (x > pt.getX() + 1.03) {
                        pathNew.lineTo(x, y);
                    }
                    break;
                case PathIterator.SEG_CUBICTO:
                    x = coords[4];
                    y = coords[5];
                    if (x > pt.getX()) {
                        pathNew.curveTo(coords[0], coords[1], coords[2], coords[3], coords[4], coords[5]);
                    }
                    break;
                default:
                    break;
            }  // end switch
            iter.next();
        }  // end while
        return (GeneralPath)pathNew.clone();
    }

    /**
     * 计算float数组的近似众数
     *
     * @param a 给定float数组
     * @return 数组的众数
     */
    public static float getFloatMode(List<Float> a) {
        if (a.size() <= 2)
            return a.get(0);

        List<Integer> aa = new ArrayList<>();
        for (int i = 0; i < a.size(); i++) {
            float big = 100.0f * a.get(i);
            aa.add(Math.round(big));
        }
        int ithmode = getMode(aa);
        return ithmode / 100.0f;
    }

    /**
     * 计算整数数组的众数
     *
     * @param a 给定整数数组
     * @return 数组的众数
     */
    public static int getMode(List<Integer> a) {
        int maxValue = 0, maxCount = 0;
        for (int i = 0; i < a.size(); ++i) {
            int count = 0;
            for (int j = 0; j < a.size(); ++j) {
                if (a.get(j).equals(a.get(i)))
                    ++count;
            }
            if (count > maxCount) {
                maxCount = count;
                maxValue = a.get(i);
            }
        } // end for i
        return maxValue;
    }

    /**
     * 计算float数组的近似众数 以数组的形式返回
     *
     * @param a 给定float数组
     * @return 数组的众数
     */
    public static List<Float> getFloatModeList(List<Float> a) {
        if (a.size() <= 2)
            return a;

        List<Integer> aa = new ArrayList<>();
        for (int i = 0; i < a.size(); i++) {
            float big = 100.0f * a.get(i);
            aa.add(Math.round(big));
        }
        List<Float> ithmodeList = new ArrayList<>();
        getModeList(aa).forEach(ithmode -> ithmodeList.add(ithmode / 100.0f));
        return ithmodeList;
    }

    /**
     * 计算整数数组的众数 以数组的形式返回
     * 例如 【1 2 2 2 3 3 3 1】 返回 【2 3】 因为各出现3次最多
     *
     * @param a 给定整数数组
     * @return 数组的众数
     */
    public static List<Integer> getModeList(List<Integer> a) {
        List<Integer> output = new ArrayList<>();

        Map<Integer, Integer> map = new HashMap<>();
        for (int i : a) {
            Integer count = map.get(i);
            map.put(i, count != null ? count + 1 : 0);
        }

        Comparator<Map.Entry<Integer, Integer>> comparator = new Comparator<Map.Entry<Integer, Integer>>() {
            @Override
            public int compare(Map.Entry<Integer, Integer> o1, Map.Entry<Integer, Integer> o2) {
                return o1.getValue().compareTo(o2.getValue());
            }
        };

        boolean accepted = true;

        Integer popular = Collections.max(map.entrySet(), comparator).getKey();
        int value = map.get(popular);

        while (accepted) {
            output.add(popular);
            map.remove(popular);

            if (map.isEmpty()){
                break;
            }
            // 计算下一个众数
            popular = Collections.max(map.entrySet(), comparator).getKey();
            // 如果是下一个众数也出现了value次 则同样加入output
            // 如果下一个众数不等于Value（一定是小于） 则跳出直接返回现有结果
            accepted = value == map.get(popular);
        }

        return output;
    }

    /**
     * 拷贝一维数组对象
     *
     * @param objsCopy
     * @param objsSrc
     */
    public static void copyListCollection(
            List<ChartPathInfo> objsCopy,
            List<ChartPathInfo> objsSrc) {
        if (objsSrc.isEmpty()) {
            return;
        }

        objsCopy.clear();
        for (int i = 0; i < objsSrc.size(); i++) {
            objsCopy.add(objsSrc.get(i));
        }
    }

    /**
     * 合并两个相邻的GeneralPath
     *
     * @param pathA
     * @param pathB
     */
    public static void compTwoGeneralPaths(GeneralPath pathA, GeneralPath pathB) {
        PathIterator iter = pathB.getPathIterator(null);
        double[] coords = new double[12];
        while (!iter.isDone()) {
            switch (iter.currentSegment(coords)) {
                case PathIterator.SEG_MOVETO:
                    pathA.moveTo(coords[0], coords[1]);
                    break;
                case PathIterator.SEG_LINETO:
                    pathA.lineTo(coords[0], coords[1]);
                    break;

                case PathIterator.SEG_CUBICTO:
                    pathA.curveTo(coords[0], coords[1], coords[2], coords[3], coords[4], coords[5]);
                    break;

                case PathIterator.SEG_CLOSE:
                    pathA.closePath();
                    break;

                default:
                    break;
            }  // end switch
            iter.next();
        }  // end while
    }

    /**
     * 如果是混合图 过滤掉和图例颜色不匹配的PathInfo (开发中 还没有使用)
     *
     * @param chart
     * @param pathInfos
     * @param paths
     */
    public static void filterPathInfoNotMatchLegend(
            Chart chart,
            List<ChartPathInfo> pathInfos,
            List<PathInfo.PathType> paths) {
        if (chart == null || chart.type == ChartType.BITMAP_CHART || chart.legends.isEmpty()) {
            return;
        }
        boolean isComboType = false;
        for (int i = 0; i < paths.size() - 1; i++) {
            if (paths.get(i) != paths.get(i + 1)) {
                isComboType = true;
                break;
            }
        }
        if (!isComboType) {
            return;
        }
        final Iterator<ChartPathInfo> each = pathInfos.iterator();
        while (each.hasNext()) {
            ChartPathInfo pathInfo = each.next();
            if (!chart.legends.stream().anyMatch(obj -> obj.color.equals(pathInfo.color))) {
                each.remove();
            }
        }
    }

    /**
     * 重置给定的PathInfo集　过滤掉多余的
     * 矩形和其边界(过滤掉边界), 面积和其边界(过滤掉边界), 其他同颜色不同类型的保留第一个
     *
     * @param pathInfos 保存的PathInfo集
     * @param paths     保存过的Path的类型集
     */
    public static void resetPathInfos(
            List<ChartPathInfo> pathInfos,
            List<PathInfo.PathType> paths) {
        // 如果只有一个　则不需要处理
        if (pathInfos.size() <= 1) {
            return;
        }

        boolean bFind = true;
        boolean[] deleteFirst = {false};
        // 循环　找出可以过滤掉的　PathInfo 直到找不出可以过滤掉的PathInfo时停掉
        while (bFind) {
            int n = pathInfos.size();
            for (int i = 0; i < n; i++) {
                bFind = false;
                ChartPathInfo pathInfo = pathInfos.get(i);
                // 遍历其他的PathInfo
                for (int j = i + 1; j < n; j++) {
                    ChartPathInfo other = pathInfos.get(j);
                    if (!pathInfo.color.equals(other.color) || pathInfo.type.equals(other.type)) {
                        continue;
                    }

                    // 如果找出可以过滤掉的情况　则删除相应的对象
                    bFind = filterInvalidPathInfo(pathInfo, other, deleteFirst);
                    if (!bFind) {
                        continue;
                    }
                    if (deleteFirst[0]) {
                        pathInfos.remove(i);
                    } else {
                        pathInfos.remove(j);
                    }
                    break;
                } // end for j
                if (bFind) {
                    break;
                }
            } // end for i
        } // end while

        // 将类型集信息更新
        if (pathInfos.size() != paths.size()) {
            paths.clear();
            for (ChartPathInfo pathInfo : pathInfos) {
                paths.add(pathInfo.type);
            }
        }
    }

    /**
     * 找出无效的PathInfo
     * 存在面积元素和其边界同时存在且颜色相同的情况 或　面积图中某个小部分　为很窄的柱状
     *
     * @param pathInfoA 第一个PathInfo
     * @param pathInfoB 第二个PathInfo
     * @param deleteA   是否删除第一个的状态标识
     * @return 如果找到需要删除的　则返回　true
     */
    public static boolean filterInvalidPathInfo(
            ChartPathInfo pathInfoA,
            ChartPathInfo pathInfoB,
            boolean[] deleteA) {
        // 找出Path上的有效点集坐标
        List<Double> xsA = new ArrayList<>();
        List<Double> ysA = new ArrayList<>();
        List<Double> xsB = new ArrayList<>();
        List<Double> ysB = new ArrayList<>();
        ChartContentScaleParser.getLinePathPoints(pathInfoA.path, null, xsA, ysA);
        ChartContentScaleParser.getLinePathPoints(pathInfoB.path, null, xsB, ysB);
        int nA = xsA.size();
        int nB = xsB.size();
        int n = nA > nB ? nB : nA;
        boolean bSame = false;
        // 如果点数接近　则判断是否近似相等
        // 如果一个对象和其边界同时出现的情况　过滤掉边界
        if (Math.abs(nA - nB) < 2) {
            bSame = true;
            for (int i = 0; i < n; i++) {
                if (!ChartUtils.equals(xsA.get(i), xsB.get(i)) || !ChartUtils.equals(ysA.get(i), ysB.get(i))) {
                    bSame = false;
                    break;
                }
            }
        }
        deleteA[0] = false;
        if (bSame) {
            if (pathInfoA.type == PathInfo.PathType.LINE) {
                deleteA[0] = true;
            }
            return true;
        }

        // 如果有一个为面积图　则一般去掉另外一个　即此时一般为面积部分的很小部分 这种情况不多
        if (pathInfoA.type == PathInfo.PathType.AREA) {
            deleteA[0] = false;
        } else if (pathInfoB.type == PathInfo.PathType.AREA) {
            deleteA[0] = true;
        }
        // 对应虚线对象或是水平长线对象　先不过滤掉　后续会判断是否有匹配图例再进行相应操作
        else if (pathInfoA.type == PathInfo.PathType.HORIZON_LONG_LINE ||
                pathInfoA.type == PathInfo.PathType.DASH_LINE ||
                pathInfoB.type == PathInfo.PathType.HORIZON_LONG_LINE ||
                pathInfoB.type == PathInfo.PathType.DASH_LINE) {
            return false;
        }
        return true;
    }

    /**
     * 给定Chart集　循环遍历　匹配每一个Chart内部的Legends和PathInfos
     *
     * @param charts 给定Chart集
     */
    public static void matchChartsPathInfosLegends(List<Chart> charts) {
        for (Chart chart : charts) {
            if (chart.type == ChartType.BITMAP_CHART || chart.type == ChartType.UNKNOWN_CHART) {
                continue;
            }

            // 重置当前chart的候选隐式柱状PathInfo
            // 如果参考显式柱状PathInfo位置信息，是柱状元素则添加到PathInfo中去
            reConfirmChartColumnars(chart);

            // 匹配PathInfos与图例
            matchPathLegend(chart);

            // 过滤掉坐标轴轴标即与坐标轴垂直的短线段
            filterAxisCalibration(chart);

            // 组合属于相同对象的 PathInfo 候选解析数据时用
            compSameLegendPathInfo(chart);

            // 将逆序的折线对象调整顺序
            ChartContentScaleParser.reverseChartUnOrderLine(chart);
        } // end for chart
    }

    // 过滤掉坐标轴轴标即与坐标轴垂直的短线段
    public static void filterAxisCalibration(Chart chart) {
        double height = chart.getHeight();
        double width = chart.getWidth();
        double shortCoef = 0.1, sameCoef = 0.01;
        List<Double> xs = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
        for (int i = 0; i < chart.pathInfos.size(); i++) {
            ChartPathInfo pathInfo = chart.pathInfos.get(i);
            // 忽略非折线对象或有匹配图例对象
            if (pathInfo.type != PathInfo.PathType.LINE ||
                    !StringUtils.isEmpty(pathInfo.text)) {
                continue;
            }

            xs.clear();
            ys.clear();
            boolean bGetPts = ChartContentScaleParser.getLinePathPoints(
                    pathInfo.path, chart.hAxis, xs, ys);
            // 如果没有取到有效点集  则设置为无效类型
            if (!bGetPts || xs.size() >= 3) {
                continue;
            }
            double dx = Math.abs(xs.get(0) - xs.get(1));
            double dy = Math.abs(ys.get(0) - ys.get(1));
            boolean vertical = dx < sameCoef * width && dy < shortCoef * height;
            boolean horizon = dy < sameCoef * height && dx < shortCoef * width;
            if (chart.hAxis != null) {
                double axisY = chart.hAxis.getY1();
                boolean onAxis = Math.abs(axisY - ys.get(0)) < sameCoef * height ||
                        Math.abs(axisY - ys.get(1)) < sameCoef * height;
                if (onAxis && vertical) {
                    pathInfo.type = PathInfo.PathType.UNKNOWN;
                }
            }
            if (chart.lvAxis != null) {
                double axisX = chart.lvAxis.getX1();
                boolean onAxis = Math.abs(axisX - xs.get(0)) < 0.001 * width ||
                        Math.abs(axisX - xs.get(1)) < 0.001 * width;
                if (onAxis && horizon) {
                    pathInfo.type = PathInfo.PathType.UNKNOWN;
                }
            }
            if (chart.rvAxis != null) {
                double axisX = chart.rvAxis.getX1();
                boolean onAxis = Math.abs(axisX - xs.get(0)) < 0.001 * width ||
                        Math.abs(axisX - xs.get(1)) < 0.001 * width;
                if (onAxis && horizon) {
                    pathInfo.type = PathInfo.PathType.UNKNOWN;
                }
            }
        }
    }

    /**
     * 匹配给定Chart内部的Legends和PathInfos
     *
     * @param chart 给定Chart
     */
    public static void matchPathInfosWithScaleAndLegends(Chart chart) {
        // 如果个数为空　则直接返回
        int n = chart.legends.size();
        int nPath = chart.pathInfos.size();
        if (n == 0 || nPath == 0) {
            return;
        }

        // 统计有几个垂直刻度信息
        int nVerticalScales = 0;
        if (!chart.vAxisChunksL.isEmpty()) {
            nVerticalScales++;
        }
        if (!chart.vAxisChunksR.isEmpty()) {
            nVerticalScales++;
        }

        // 判断图例对应左侧还是右侧刻度信息
        // 如果只有两个图例　则右侧图例或下侧图例对应右侧刻度信息　后续要改进策略
        int ithLegendMatchRightAxis = -1;
        if (chart.legends.size() == 2 && nVerticalScales == 2 && chart.legendsChunks.size() == 2) {
            TextChunk chunkA = chart.legendsChunks.get(0);
            TextChunk chunkB = chart.legendsChunks.get(1);
            // 位于右侧的图例　其对应右侧的刻度信息
            if (chunkA.getBounds().getX() < chunkB.getBounds().getX()) {
                ithLegendMatchRightAxis = 1;
            }
            // 位于下侧的图例　其对应右侧的刻度信息
            else if (chunkA.getBounds().getCenterY() < chunkB.getBounds().getCenterY()) {
                ithLegendMatchRightAxis = 1;
            } else {
                ithLegendMatchRightAxis = 0;
            }

            // 根据图例中是否包含　文字 右
            Chart.Legend legendA = chart.legends.get(0);
            Chart.Legend legendB = chart.legends.get(1);
            if (ChartTitleLegendScaleParser.matchLeftRightAxisKey(legendA.text, false)) {
                ithLegendMatchRightAxis = 0;
            } else if (ChartTitleLegendScaleParser.matchLeftRightAxisKey(legendB.text, false)) {
                ithLegendMatchRightAxis = 1;
            }
        } // end if

        // 由于某些小柱状对象在前期处理ContentGroup时判断不了是否有效　必须等到大部分柱状对象确定后才能确定有效性
        // 所以pathInfo的顺序需要根据图例顺序做出相应的调整　这样在后续转HighChart时能尽量和原图保持一致性
        List<ChartPathInfo> pathInfosNew = new ArrayList<>();
        List<Boolean> pathInfosStatus = new ArrayList<>();
        for (int i = 0; i < nPath; i++) {
            pathInfosStatus.add(false);
        }
        List<Boolean> legendStatus = new ArrayList<>();
        for (int iLegend = 0; iLegend < n; iLegend++) {
            legendStatus.add(false);
        }
        // 遍历pathInfos
        List<Integer> matchSides = new ArrayList<>();
        for (int iPath = 0; iPath < nPath; iPath++) {
            ChartPathInfo pathInfo = chart.pathInfos.get(iPath);
            List<Integer> pathStatus = new ArrayList<>();
            for (int iLegend = 0; iLegend < n; iLegend++) {
                Chart.Legend legend = chart.legends.get(iLegend);
                if (pathInfo.color.equals(legend.color)) {
                    pathStatus.add(iLegend);
                }
            } // end for iLegend
            int nLegend = pathStatus.size();
            for (int i = 0; i < nLegend; i++) {
                int iLegend = pathStatus.get(i);
                if (!legendStatus.get(iLegend) || i == nLegend - 1) {
                    legendStatus.set(iLegend, true);
                    int ithSide = 0;
                    Chart.Legend legend = chart.legends.get(iLegend);
                    if (ChartTitleLegendScaleParser.matchLeftRightAxisKey(legend.text, false)) {
                        if (!chart.vAxisChunksL.isEmpty() && chart.vAxisChunksR.isEmpty()) {
                            ithSide = 0;
                        }
                        else {
                            ithSide = 1;
                        }
                        matchSides.add(ithSide);
                    }
                    else if (ChartTitleLegendScaleParser.matchLeftRightAxisKey(legend.text, true)) {
                        if (chart.vAxisChunksL.isEmpty() && !chart.vAxisChunksR.isEmpty()) {
                            ithSide = 1;
                        }
                        else {
                            ithSide = 0;
                        }
                        matchSides.add(ithSide);
                    }
                    else {
                        List<String> axisTextL = new ArrayList<>(chart.vAxisTextL);
                        List<String> axisTextR = new ArrayList<>(chart.vAxisTextR);

                        axisTextL.removeAll(chart.vAxisTextR);
                        axisTextR.removeAll(chart.vAxisTextL);

                        // 验证左右轴文字是否完全相同 目前不加入单位考虑
                        if (nVerticalScales == 2 && (!axisTextL.isEmpty() || !axisTextR.isEmpty())) {
                            if (iLegend == ithLegendMatchRightAxis) {
                                ithSide = 1;
                            }
                        }
                        if (n > 2 && nVerticalScales == 2 && chart.type == ChartType.COMBO_CHART) {
                            if (pathInfo.type == PathInfo.PathType.LINE ||
                                    pathInfo.type == PathInfo.PathType.CURVE) {
                                if (matchSides.contains(1)) {
                                    ithSide = 0;
                                }
                                else {
                                    ithSide = 1;
                                }
                            }
                        }
                    }
                    pathInfo.text = legend.text;
                    pathInfo.ithSideY = ithSide;
                    legend.type = pathInfo.type;
                    pathInfosNew.add(pathInfo);
                    pathInfosStatus.set(iPath, true);
                    break;
                }
            } // end for i
        } // end for iPath

        for (int j = 0; j < nPath; j++) {
            ChartPathInfo pathInfo = chart.pathInfos.get(j);
            if (!pathInfosStatus.get(j)) {
                pathInfosNew.add(pathInfo);
            }
        }
        chart.pathInfos = pathInfosNew;
    }

    public static void matchPathLegendColor(
            List<Color> pathColors,
            List<Color> legendColors) {
        // 如果个数为空　则直接返回
        if (pathColors == null || legendColors == null) {
            return;
        }

        // 判断颜色数目是否不相等或是为空
        int n = legendColors.size();
        int nPath = pathColors.size();
        if (n != nPath || n == 0) {
            return;
        }

        // 取出不匹配的颜色
        List<Pair<Color, Integer>> legendUnMatchColors = new ArrayList<>();
        List<Pair<Color, Integer>> pathUnMatchColors = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Color color = legendColors.get(i);
            if (!pathColors.contains(color)) {
                legendUnMatchColors.add(Pair.of(color, i));
            }
            color = pathColors.get(i);
            if (!legendColors.contains(color)) {
                pathUnMatchColors.add(Pair.of(color, i));
            }
        }
        // 判断是否完全匹配  大多数情况下是完全匹配的 只有在渐变色等特殊情况下不匹配
        n = legendUnMatchColors.size();
        nPath = pathUnMatchColors.size();
        if (n != nPath || n == 0) {
            return;
        }

        // 排序不匹配的颜色对象
        sortColors(legendUnMatchColors);
        sortColors(pathUnMatchColors);
        // 直接基于排序后的颜色对象 将对应的pathInfo颜色赋值给legend
        for (int i = 0; i < n; i++) {
            Color color = pathUnMatchColors.get(i).getLeft();
            int idLegend = legendUnMatchColors.get(i).getRight();
            legendColors.set(idLegend, color);
        }
    }

    public static void sortColors(List<Pair<Color, Integer>> colors) {
        Collections.sort(colors, new Comparator<Pair<Color, Integer>>(){
            public int compare(Pair<Color, Integer> c1, Pair<Color, Integer> c2){
                long value1 = c1.getLeft().getRed() * c1.getLeft().getRed() +
                        c1.getLeft().getGreen() * c1.getLeft().getGreen() +
                        c1.getLeft().getBlue() * c1.getLeft().getBlue();
                long value2 = c2.getLeft().getRed() * c2.getLeft().getRed() +
                        c2.getLeft().getGreen() * c2.getLeft().getGreen() +
                        c2.getLeft().getBlue() * c2.getLeft().getBlue();
                return Long.compare(value1, value2);
            }
        });
    }

    /**
     *
     * @param pathLists  要做区域合并的Path列表
     * 对列表里面的Path进行区域合并：如果两个Path有相交，那么就合并起来。
     */
    private static void mergeIntersectPaths(List<GeneralPath> pathLists )    {
        int precount = pathLists.size();
        do {
            precount = pathLists.size();
            for (int i = 0; i < pathLists.size(); i++) {
                GeneralPath curItem = pathLists.get(i);
                for (GeneralPath itemtmp : pathLists) {
                    Rectangle2D itemRect =curItem.getBounds2D();
                    itemRect.setRect(itemRect.getX() - 0.5, itemRect.getY() - 0.5, itemRect.getWidth() + 1, itemRect.getHeight() +1);
                    if (curItem != itemtmp && itemRect.intersects(itemtmp.getBounds())) {
                        compTwoGeneralPaths(itemtmp, curItem);
                        pathLists.remove(i);
                        break;
                    }
                }
            }
        }
        while (precount > pathLists.size());

    }

    public static void matchPathLegend(Chart chart) {
        // 如果个数为空　则直接返回
        int n = chart.legends.size();
        int nPath = chart.pathInfos.size();
        if (n == 0 || nPath == 0) {
            return;
        }

        List<Color> pclors = new ArrayList<>();
        chart.pathInfos.stream().forEach(path -> {
            if (!pclors.contains(path.color)) {
                pclors.add(path.color);
            }
        });
        List<Color> lclors = new ArrayList<>();
        chart.legends.stream().forEach(legend -> {
            if (!lclors.contains(legend.color)) {
                lclors.add(legend.color);
            }
        });


        // 重新根据图的类型合并柱子
        //
        // 按照颜色分组
       /* if (chart.type == ChartType.BAR_CHART) {
            Map<Color, List<GeneralPath>> pathMap = new HashMap<>();
            List<PathItem> items = chart.contentGroup.getAllPathItems();
            for (int i = 0; i < items.size(); i++) {
                PathItem curItem = items.get(i);
                List<GeneralPath> curPaths = pathMap.get(curItem.getColor());
                if(curPaths == null) {
                    curPaths = new ArrayList<>();
                    pathMap.put(curItem.getColor(),curPaths);
                }

                boolean find = false;
                for (GeneralPath itemtmp : curPaths) {
                    Rectangle2D itemRect =itemtmp.getBounds2D();
                    itemRect.setRect(itemRect.getX() - 1.5, itemRect.getY() - 1.5, itemRect.getWidth() + 3, itemRect.getHeight() +3);
                    if (itemRect.intersects(curItem.getBounds())) {
                        compTwoGeneralPaths(itemtmp, curItem.getItem());
                        find = true;
                    }
                }
                if (!find) {
                    curPaths.add(curItem.getItem());
                }
            }

            Set<Color> keysets = pathMap.keySet();
            for (Color cl : keysets) {
                List<GeneralPath> ls = pathMap.get(cl);
                mergeIntersectPaths(ls);

                System.out.print("start======\n");
                for (GeneralPath dd : ls) {
                    System.out.print(dd.getBounds());
                    System.out.print("\n");
                }
                System.out.print("end=====\n");
            }
            System.out.print(pathMap.size());
        } */

        int nBarCount = 0;
        for (ChartPathInfo pinfo : chart.pathInfos) {
            if(pinfo.type == PathInfo.PathType.BAR) {
                nBarCount++;
            }
        }

        if (chart.type == ChartType.BAR_CHART && nBarCount == 0) {
            List<PathItem> items = chart.contentGroup.getAllPathItems();
            // 把已经解析为其它
            List<List> newPaths = new ArrayList<>();
            for (Color c : lclors) {
                newPaths.add(new ArrayList());
            }
            // for test
            double miny = 0;
            double maxy=0;
            PathItem maxyi = null;
            PathItem minyi = null;

            for (int i = 0; i < items.size(); i++) {
                PathItem curItem = items.get(i);
                int index = lclors.indexOf(curItem.getColor());
                if (index != -1) {
                    List<GeneralPath> curPaths = newPaths.get(index);
                    boolean find = false;
                    for (GeneralPath itemtmp : curPaths) {
                        Rectangle2D itemRect =itemtmp.getBounds2D();
                        itemRect.setRect(itemRect.getX() - 0.5, itemRect.getY() - 0.5, itemRect.getWidth() + 1, itemRect.getHeight() +1);
                        if (itemRect.intersects(curItem.getBounds())) {
                            if (curItem.getBounds().getMaxY() > maxy) {
                                maxy = curItem.getBounds().getMaxY();
                                maxyi = curItem;
                            }
                            if (curItem.getBounds().getMinY() < miny || miny == 0) {
                                miny = curItem.getBounds().getMinY();
                                minyi = curItem;
                            }
                            compTwoGeneralPaths(itemtmp, curItem.getItem());
                            find = true;
                        }
                    }
                    if (!find) {
                        curPaths.add(curItem.getItem());
                    }
                }
            }

            for (List<GeneralPath> gpaths : newPaths) {
                 int precount = gpaths.size();
                 do {
                     precount = gpaths.size();
                     for (int i = gpaths.size() - 1; i > 0; i--) {
                         for (int j = 0; j != i && j < gpaths.size(); j++) {
                             if (gpaths.get(j).getBounds2D().intersects(gpaths.get(i).getBounds2D())) {
                                 compTwoGeneralPaths(gpaths.get(j), gpaths.get(i));
                                 gpaths.remove(i);
                                 break; // 注意别重复合并了
                             }
                         }
                     } // end for
                 }
                 while(precount > gpaths.size());
            }

            // 对统计出来的path进行过滤，过滤掉和legend重合的
            for (int i = newPaths.size() - 1; i > -1; i--) {
                List<GeneralPath> dd = newPaths.get(i);
                for (GeneralPath gpath : dd) {
                    boolean find = false;
                    for (Chart.Legend lg: chart.legends) {
                        if(lg.line.intersects(gpath.getBounds2D())) {
                            find = true;
                            break;
                        }
                    }
                    if (!find) {
                        ChartPathInfo newpart = new ChartPathInfo();
                        if (chart.type == ChartType.BAR_CHART ) {
                            newpart.type = PathInfo.PathType.BAR;
                        }
                        else if (chart.type == ChartType.COLUMN_CHART) {
                            newpart.type = PathInfo.PathType.COLUMNAR;
                        }
                        // 这里要创造一个虚拟的柱子，当前图形的外包矩形
                        GeneralPath vPath = (GeneralPath) gpath.clone();
                        vPath.reset();
                        Rectangle2D rect = gpath.getBounds2D();
                        vPath.moveTo(rect.getMinX(), rect.getMaxY());
                        vPath.lineTo(rect.getMinX(), rect.getMinY());
                        vPath.lineTo(rect.getMaxX(), rect.getMinY());
                        vPath.lineTo(rect.getMaxX(), rect.getMaxY());
                        vPath.closePath();

                        newpart.path = vPath;
                        newpart.color = lclors.get(i);
                        newpart.bound = gpath.getBounds2D();
                        chart.pathInfos.add(newpart);
                    }
                }
            } // end for

        }
        nPath = chart.pathInfos.size();

        matchPathLegendColor(pclors, lclors);
        for (int i = 0; i < lclors.size(); i++) {
            Chart.Legend legend = chart.legends.get(i);
            legend.color = lclors.get(i);
        }

        // 遍历Legends
        for (int i = 0; i < n; i++) {
            Chart.Legend legend = chart.legends.get(i);
            Color color = legend.color;
            // 找出所以和legend颜色匹配的PathInfo　然后将其text设置为图例的信息
            for (int j = 0; j < nPath; j++) {
                ChartPathInfo pathInfo = chart.pathInfos.get(j);
                if (color.equals(pathInfo.color)) {
                    pathInfo.text = legend.text;
                    legend.type = pathInfo.type;
                }
            } // end for j
        } // end for i
    }

    /**
     * 组合输入同一个Legend的PathInfo集
     *
     * @param chart
     */
    public static void compSameLegendPathInfo(Chart chart) {
        int n = chart.pathInfos.size();
        for (int i = 0; i < n; i++) {
            ChartPathInfo pathInfoA = chart.pathInfos.get(i);
            if (pathInfoA.type == PathInfo.PathType.UNKNOWN ||
                    pathInfoA.type == PathInfo.PathType.AREA) {
                continue;
            }

            Rectangle2D boxA = pathInfoA.path.getBounds2D();

            // 找出剩下与当前　PathInfo　相同颜色且同类型的对象 然后合并
            // 并将被合并对象的类型设置为无效 方便后面的过滤
            for (int j = i + 1; j < n; j++) {
                ChartPathInfo pathInfoB = chart.pathInfos.get(j);
                if (pathInfoB.type == PathInfo.PathType.UNKNOWN ||
                        !pathInfoA.color.equals(pathInfoB.color) ||
                        pathInfoB.type != pathInfoA.type) {
                    continue;
                }
                if (pathInfoB.type == PathInfo.PathType.AREA) {
                    continue;
                }

                // 存在相同颜色的折线对象　判断在X方向上是否有过高的重叠区间  如果有则不合并
                Rectangle2D boxB = pathInfoB.path.getBounds2D();
                // 对于某些折线的合并问题　可能出现在X方向上 pathInfoA在pathInfoB的后面　此时得调换一下
                boolean fromAToB = true;
                if (pathInfoA.type == PathInfo.PathType.LINE ||
                        pathInfoA.type == PathInfo.PathType.CURVE) {
                    if (isOverlay(pathInfoA.path, pathInfoB.path)) {
                        if (PathUtils.getPathPtsCount(pathInfoA.path) >= PathUtils.getPathPtsCount(pathInfoB.path)) {
                            pathInfoB.type = PathInfo.PathType.UNKNOWN;
                        } else {
                            pathInfoA.type = PathInfo.PathType.UNKNOWN;
//                            break;
                        }
                    }
                    if (isOverlapXAxis(boxA, boxB, 0.7)) {
                        continue;
                    }
                    if (boxA.getCenterX() > boxB.getCenterX()) {
                        fromAToB = false;
                    }
                }

                // 根据空间前后关系　适当合并
                if (fromAToB) {
                    compTwoGeneralPaths(pathInfoA.path, pathInfoB.path);
                    pathInfoB.type = PathInfo.PathType.UNKNOWN;
                    boxA = pathInfoA.path.getBounds2D();
                } else {
                    compTwoGeneralPaths(pathInfoB.path, pathInfoA.path);
                    pathInfoA.type = PathInfo.PathType.UNKNOWN;
                    break;
                }
            } // end for j
        } // end for i

        // 判断匹配相同图例的ChartPathInfo有效性
        judgeSameLegendValid(chart);

        // 判断ChartPathInfo对象有效性
        for (int i = 0; i < n; i++) {
            ChartPathInfo pathInfo = chart.pathInfos.get(i);
            // 忽略无效类型对象
            if (pathInfo.type == PathInfo.PathType.UNKNOWN) {
                continue;
            }
            // 忽略有图例文字信息对应的对象
            if (!StringUtils.isEmpty(pathInfo.text)) {
                continue;
            }
            if (pathInfo.type == PathInfo.PathType.LINE) {
                judgeUnLegendLinePathInfoValid(chart, pathInfo);
            } else if (pathInfo.type == PathInfo.PathType.BAR ||
                    pathInfo.type == PathInfo.PathType.COLUMNAR) {
                judgeUnLegendBarPathInfoValid(chart, pathInfo);
            }
        } // end for i

        // 对Chart中的同类型且颜色近似相同的path对象进行处理
        setApproximationColorPath(chart);

        // 去除类型为无效类型的PathInfo
        final Iterator<ChartPathInfo> each = chart.pathInfos.iterator();
        while (each.hasNext()) {
            ChartPathInfo pathInfo = each.next();
            if (pathInfo.type == PathInfo.PathType.UNKNOWN) {
                each.remove();
            }
        } // end while

        // 如果非饼图类型Chart内部没有PathInfos  则设置为无效类型
        if (chart.type != ChartType.PIE_CHART) {
            if (chart.pathInfos.isEmpty()) {
                chart.type = ChartType.UNKNOWN_CHART;
            }
        }
    }

    /**
     * 对Chart中的同类型且颜色近似相同的path对象进行处理
     * 如果是折线　则判断是否重叠　如果重叠　则去掉没有图例的pathInfo
     * 如果是柱状　则合并
     * @param chart
     */
    public static void setApproximationColorPath(Chart chart) {
        int n = chart.pathInfos.size();
        for (int i = 0; i < n; i++) {
            ChartPathInfo pathInfoA = chart.pathInfos.get(i);
            boolean hasLegendA = StringUtils.isNotEmpty(pathInfoA.text);
            if (pathInfoA.type == PathInfo.PathType.UNKNOWN) {
                continue;
            }

            for (int j = i + 1; j < n; j++) {
                ChartPathInfo pathInfoB = chart.pathInfos.get(j);
                boolean hasLegendB = StringUtils.isNotEmpty(pathInfoB.text);
                if (hasLegendA == hasLegendB || pathInfoA.type != pathInfoB.type) {
                    continue;
                }
                // 如果颜色不相近　则跳过
                if (!ChartUtils.approximationColor(pathInfoA.color, pathInfoB.color, 10.0)) {
                    continue;
                }
                // 如果是折线对象
                if (pathInfoB.type == PathInfo.PathType.LINE ||
                        pathInfoB.type == PathInfo.PathType.CURVE) {
                    // 如果重叠　则去掉无图例的
                    if (isOverlay(pathInfoA.path, pathInfoB.path)) {
                        if (!hasLegendA) {
                            pathInfoA.type = PathInfo.PathType.UNKNOWN;
                            break;
                        }
                        if (!hasLegendB) {
                            pathInfoB.type = PathInfo.PathType.UNKNOWN;
                        }
                    }
                }
                // 如果是柱状对象
                if (pathInfoB.type == PathInfo.PathType.BAR ||
                        pathInfoB.type == PathInfo.PathType.COLUMNAR) {
                    if (hasLegendA) {
                        compTwoGeneralPaths(pathInfoA.path, pathInfoB.path);
                        pathInfoB.type = PathInfo.PathType.UNKNOWN;
                    }
                    else {
                        compTwoGeneralPaths(pathInfoB.path, pathInfoA.path);
                        pathInfoA.type = PathInfo.PathType.UNKNOWN;
                        break;
                    }
                }
            } // end for j
        } // end for i
    }

    /**
     * 判断给定两个浮点数数组是否相等
     * @param a
     * @param b
     * @return
     */
    public static boolean sameList(List<Double> a, List<Double> b) {
        if (a == null || b == null) {
            return false;
        }
        if (a == b) {
            return true;
        }
        int na = a.size(), nb = b.size();
        if (na != nb) {
            return false;
        }
        for (int i = 0; i < na; i++) {
            if (!ChartUtils.equals(a.get(i), b.get(i))) {
                return false;
            }
        }
        return true;
    }

    public static boolean isOverlay(GeneralPath pathA, GeneralPath pathB) {
        ArrayList<Double> axs = new ArrayList<>();
        ArrayList<Double> ays = new ArrayList<>();
        ArrayList<Double> bxs = new ArrayList<>();
        ArrayList<Double> bys = new ArrayList<>();
        int sizeA = PathUtils.getPathPtsInfo(pathA, axs, ays);
        int sizeB = PathUtils.getPathPtsInfo(pathB, bxs, bys);
        int i = 0;
        if (sizeA == sizeB) {
            return sameList(axs, bxs) && sameList(ays, bys);
        } else if (sizeA < sizeB) {
            while ((sizeB - sizeA) > i) {
                if (ChartUtils.equals(axs.get(0), bxs.get(i)) && ChartUtils.equals(ays.get(0), bys.get(i))) {
                    return sameList(axs, bxs.subList(i, i + sizeA)) && sameList(ays, bys.subList(i, i + sizeA));
                }
                i++;
            }
            return false;
        } else {
            while ((sizeA - sizeB) > i) {
                if (ChartUtils.equals(bxs.get(0), axs.get(i)) && ChartUtils.equals(bys.get(0), ays.get(i))) {
                    return sameList(bxs, axs.subList(i, i + sizeB)) && sameList(bys, ays.subList(i, i + sizeB));
                }
                i++;
            }
            return false;
        }
    }

    /**
     * 判断Chart中匹配相同图例的ChartPathInfo的有效性
     * (目前遇到了折线及其同颜色具有说明性的矩形对象, 矩形内部有占比较大的说明性文字)
     *
     * @param chart
     */
    public static void judgeSameLegendValid(Chart chart) {
        int n = chart.pathInfos.size();
        for (int i = 0; i < n; i++) {
            ChartPathInfo pathInfoA = chart.pathInfos.get(i);
            if (pathInfoA.type == PathInfo.PathType.UNKNOWN) {
                continue;
            }
            for (int j = i + 1; j < n; j++) {
                ChartPathInfo pathInfoB = chart.pathInfos.get(j);
                if (pathInfoB.type == PathInfo.PathType.UNKNOWN) {
                    continue;
                }
                judgeSameLegendPathInfosValid(chart, pathInfoA, pathInfoB);
            }
        } // end for i
    }

    /**
     * 判断两个ChartPathInfo匹配相同图例对象的有效性
     *
     * @param chart
     * @param pathInfoA
     * @param pathInfoB
     */
    public static void judgeSameLegendPathInfosValid(
            Chart chart, ChartPathInfo pathInfoA, ChartPathInfo pathInfoB) {
        // 忽略掉同类型或不同图例的对象
        if (pathInfoB.type == pathInfoA.type || !pathInfoA.text.equals(pathInfoB.text)) {
            return;
        }

        // 判断输入两个ChartPathInfo是否为折线和柱状类型
        ChartPathInfo pathInfo = null;
        if ((pathInfoA.type == PathInfo.PathType.LINE ||
                pathInfoA.type == PathInfo.PathType.CURVE) &&
                (pathInfoB.type == PathInfo.PathType.BAR ||
                        pathInfoB.type == PathInfo.PathType.COLUMNAR)) {
            pathInfo = pathInfoB;
        } else if ((pathInfoA.type == PathInfo.PathType.BAR ||
                pathInfoA.type == PathInfo.PathType.COLUMNAR) &&
                (pathInfoB.type == PathInfo.PathType.LINE ||
                        pathInfoB.type == PathInfo.PathType.CURVE)) {
            pathInfo = pathInfoA;
        }

        // 判断柱状对象内部文字信息占比
        // 如果矩形内文字占比都大于阀值 则认为是注释性的矩形区域 设置为无效类型
        if (pathInfo != null) {
            // 计算柱状对象内矩形信息
            List<Double> cxys = new ArrayList<>();
            Rectangle2D box = new Rectangle2D.Double();
            ChartPathInfosParser.getBarPathInfoCenterPts(pathInfo.path, cxys);
            boolean filledByText = true;
            int nBox = cxys.size() / 4;
            // 遍历判断每个矩形内部文字占比是否小于阀值
            for (int k = 0; k < nBox; k++) {
                box.setRect(cxys.get(4 * k), cxys.get(4 * k + 1),
                        cxys.get(4 * k + 2), cxys.get(4 * k + 3));
                double area = 0.0;
                for (TextChunk chunk : chart.chunksRemain) {
                    if (box.intersects(chunk.getBounds())) {
                        area += chunk.getWidth() * chunk.getHeight();
                    }
                }
                if (area < 0.4 * cxys.get(4 * k + 2) * cxys.get(4 * k + 3)) {
                    filledByText = false;
                }
            } // end for k
            // 如果都大于阀值　则设置为无效类型
            if (filledByText) {
                pathInfo.type = PathInfo.PathType.UNKNOWN;
            }
        }
    }

    /**
     * 判断没有匹配图例的柱状对象的有效性
     * Chart中可能存在以绘制fill方式建立坐标轴或刻度小线段 容易和柱状对象混淆, 其通常没有匹配的图例
     *
     * @param chart
     * @param pathInfo
     */
    public static void judgeUnLegendBarPathInfoValid(Chart chart, ChartPathInfo pathInfo) {
        double width = chart.getWidth();
        double height = chart.getWidth();
        List<Double> cxys = new ArrayList<>();
        // 计算柱状对象内的矩形中心及长宽信息集
        getBarPathInfoCenterPts(pathInfo.path, cxys);
        int n = cxys.size() / 4;
        // 如果只有一个矩形　则宽度长度不能太小
        if (n == 1) {
            // 如果不是 水平柱状图  则不可能出现水平方向的矩形
            if (chart.type != ChartType.COLUMN_CHART &&
                    pathInfo.type == PathInfo.PathType.COLUMNAR) {
                pathInfo.type = PathInfo.PathType.UNKNOWN;
            }
            if (cxys.get(2) < 0.002 * width || cxys.get(3) < 0.002 * height) {
                pathInfo.type = PathInfo.PathType.UNKNOWN;
            }
            return;
        }

        // 多个矩形对象　则垂直柱状对象不能同高　水平柱状不能同宽
        boolean sameW = true;
        boolean sameH = true;
        boolean testXAxis = pathInfo.type == PathInfo.PathType.BAR && chart.hAxis != null;
        boolean testYAxis = pathInfo.type == PathInfo.PathType.COLUMNAR && chart.lvAxis != null;
        boolean startAxis = false;
        double coef = 0.5;
        for (int i = 0; i < n - 1; i++) {
            if (Math.abs(cxys.get(4 * i + 2) - cxys.get(4 * (i + 1) + 2)) / width > 0.002) {
                sameW = false;
            }
            if (Math.abs(cxys.get(4 * i + 3) - cxys.get(4 * (i + 1) + 3)) / height > 0.002) {
                sameH = false;
            }
            if (testXAxis) {
                double ymin = cxys.get(4 * i + 1) - 0.5 * cxys.get(4 * i + 3);
                double ymax = cxys.get(4 * i + 1) + 0.5 * cxys.get(4 * i + 3);
                double yaxis = chart.hAxis.getY1();
                if (Math.abs(ymin - yaxis) < coef || Math.abs(ymax - yaxis) < coef) {
                    startAxis = true;
                }
            }
            if (testYAxis) {
                double xmin = cxys.get(4 * i) - 0.5 * cxys.get(4 * i + 2);
                double xmax = cxys.get(4 * i) + 0.5 * cxys.get(4 * i + 2);
                double xaxis = chart.lvAxis.getX1();
                if (Math.abs(xmin - xaxis) < coef || Math.abs(xmax - xaxis) < coef) {
                    startAxis = true;
                }
            }
        }
        if (pathInfo.type == PathInfo.PathType.BAR && sameH && !startAxis) {
            pathInfo.type = PathInfo.PathType.UNKNOWN;
        } else if (pathInfo.type == PathInfo.PathType.COLUMNAR && sameW && !startAxis) {
            pathInfo.type = PathInfo.PathType.UNKNOWN;
        }
    }

    /**
     * 判断没有匹配图例的折线对象的有效性
     * Chart中可能存在辅助图形对象 同颜色的组合起来容易和折线对象混淆, 其通常没有匹配的图例
     *
     * @param chart
     * @param pathInfo
     */
    public static void judgeUnLegendLinePathInfoValid(Chart chart, ChartPathInfo pathInfo) {
        // 如果近似水平或垂直　则设置为无效类型
        Rectangle2D box = pathInfo.path.getBounds2D();
        if (box.getWidth() < 1.E-2 * chart.getWidth() ||
                box.getHeight() < 1.E-2 * chart.getHeight()) {
            pathInfo.type = PathInfo.PathType.UNKNOWN;
            return;
        }

        // 对于没有旋转的Page 判断折线在空间上的有效性  (旋转的情况待开发)
        // 通常折线都沿某个方向  如果在X方向的重叠率过高 则设置为无效类型
        // 取出折线对象的点集
        List<Double> xs = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
        boolean bGetPts = ChartContentScaleParser.getLinePathPoints(
                pathInfo.path, chart.hAxis, xs, ys);
        // 如果没有取到有效点集  则设置为无效类型
        if (!bGetPts) {
            pathInfo.type = PathInfo.PathType.UNKNOWN;
            return;
        }
        //　如果折线对象在X方向重叠率大于阀值  则认为是无效Line
        double w = pathInfo.path.getBounds2D().getWidth();
        double len = 0.0;
        for (int j = 0; j < xs.size() - 1; j++) {
            len += Math.abs(xs.get(j) - xs.get(j + 1));
        }
        if (len > 2.0 * w) {
            pathInfo.type = PathInfo.PathType.UNKNOWN;
            return;
        }

        // 判断是否为多个垂直直线构成的Path对象
        if (judgeMultiVerticalLine(pathInfo.path)) {
            pathInfo.type = PathInfo.PathType.UNKNOWN;
            return;
        }
    }

    /**
     * 判断是否为多个垂直直线构成的Path对象 (出现频率非常低，通常为垂直辅助线或是垂直网格线对象)
     * @param path
     * @return
     */
    public static boolean judgeMultiVerticalLine(GeneralPath path) {
        if (path == null) {
            return false;
        }

        // 获取path关键点信息
        List<Double> xs = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
        List<Integer> types = new ArrayList<>();
        if (!PathUtils.getPathKeyPtsInfo(path, xs, ys, types)) {
            return false;
        }

        // 判断点数是否过少
        int n = xs.size();
        if (n <= 3) {
            return false;
        }

        // 判断是否包含其他类型点
        if (types.contains(PathIterator.SEG_CUBICTO)||
                types.contains(PathIterator.SEG_QUADTO) ||
                types.contains(PathIterator.SEG_CLOSE)) {
            return false;
        }

        // 初步判断是否由垂直线段构成
        int iStart = -1, iEnd = -1;
        List<Integer> startEnds = new ArrayList<>();
        double xStart = 0.0;
        for (int i = 0; i < n; i++) {
            int type = types.get(i);
            if (type == PathIterator.SEG_MOVETO) {
                xStart = xs.get(i);
                if (iStart == -1) {
                    iStart = i;
                }
                else {
                    startEnds.add(iStart);
                    iEnd = i;
                    iStart = iEnd;
                    startEnds.add(iEnd);
                }
            }
            else {
                if (Math.abs(xStart - xs.get(i)) > 1E-6) {
                    return false;
                }
            }
        } // end for i

        // 判断垂直线段个数
        int countVertical = 0;
        for (int i = 0; i < startEnds.size()/2; i++) {
            iStart = startEnds.get(2 * i);
            iEnd = startEnds.get(2 * i + 1);
            if (iEnd - iStart >= 2) {
                double x = xs.get(iStart);
                for (int j = iStart; j < iEnd; j++) {
                    if (Math.abs(x - xs.get(j)) > 1E-6) {
                        return false;
                    }
                }
                countVertical++;
            }
        }
        return countVertical >= 2;
    }

    // 判断两个给定矩形对象是否在X轴上重叠率高于给定参数
    public static boolean isOverlapXAxis(Rectangle2D boxA, Rectangle2D boxB, double coef) {
        double xminA = boxA.getMinX();
        double xmaxA = boxA.getMaxX();
        double xminB = boxB.getMinX();
        double xmaxB = boxB.getMaxX();
        if (xmaxB <= xminA - 1 || xminB >= xmaxA + 1) {
            return false;
        }
        double xmin = Math.max(xminA, xminB);
        double xmax = Math.min(xmaxA, xmaxB);
        double dx = xmax - xmin;
        double widthA = boxA.getWidth();
        double widthB = boxB.getWidth();
        double widthMean = 0.5 * (widthA + widthB);
        double weightA = dx / widthA;
        double weightB = dx / widthB;
        boolean overlapMean = dx / widthMean > coef;
        boolean overlapA = weightA > coef && widthA >= 0.1 * widthMean;
        boolean overlapB = weightB > coef && widthB >= 0.1 * widthMean;
        if (overlapMean || overlapA || overlapB) {
            return true;
        }
        else {
            return false;
        }
    }

    /**
     * 找出给定ChartPathInfo集的包围框的合并集
     *
     * @param pathInfos
     * @return
     */
    public static Rectangle2D getPathInfosMergeBox(List<ChartPathInfo> pathInfos) {
        if (pathInfos.isEmpty()) {
            return null;
        }
        Rectangle2D mergeBox = (Rectangle2D) pathInfos.get(0).path.getBounds2D().clone();
        pathInfos.stream().forEach(
                pathInfo -> Rectangle2D.union(mergeBox, pathInfo.path.getBounds2D(), mergeBox));
        return mergeBox;
    }

    /**
     * 计算扇形对象区域
     *
     * @param chart
     * @return
     */
    public static Rectangle2D getPieInfoBox(Chart chart) {
        if (chart.pieInfo != null && !chart.pieInfo.parts.isEmpty()) {
            Rectangle2D pBox = chart.pieInfo.parts.get(0).path.getBounds2D();
            chart.pieInfo.parts.stream().forEach(
                    part -> {
                        if (part.path != null) {
                            Rectangle2D.union(pBox, part.path.getBounds2D(), pBox);
                        }
                    });
            return (Rectangle2D) pBox.clone();
        }
        return null;
    }

    /**
     * 计算所有PathInfos的包围框
     * @param chart
     * @return
     */
    public static Rectangle2D getChartPathInfosMergeBox(Chart chart) {
        if (chart.type == ChartType.BITMAP_CHART ||
                chart.isPPT) {
            return null;
        }

        // 合并PathInfo集
        Rectangle2D mergeBox = getPathInfosMergeBox(chart.pathInfos);
        // 合并扇形集
        Rectangle2D pieBox = getPieInfoBox(chart);
        // 合并有效图形对象区域
        if (mergeBox == null && pieBox == null) {
            return null;
        }
        else if (mergeBox == null && pieBox != null) {
            mergeBox = (Rectangle2D)pieBox.clone();
        }
        else if (mergeBox != null && pieBox != null){
            Rectangle2D.union(mergeBox, pieBox, mergeBox);
        }
        // 合并坐标轴
        if (chart.lvAxis != null) {
            Rectangle2D box = chart.lvAxis.getBounds2D();
            Rectangle2D.union(mergeBox, box, mergeBox);
        }
        if (chart.rvAxis != null) {
            Rectangle2D box = chart.rvAxis.getBounds2D();
            Rectangle2D.union(mergeBox, box, mergeBox);
        }
        if (chart.hAxis != null) {
            Rectangle2D box = chart.hAxis.getBounds2D();
            Rectangle2D.union(mergeBox, box, mergeBox);
        }
        return mergeBox;
    }

    /**
     * 找出Chart内部图形对象PathInfos区域包围框的并集
     * @param chart
     * @return
     */
    public static Rectangle2D getChartGraphicsMergeBox(Chart chart) {
        // 合并pathInfo对象的包围框
        Rectangle2D mergeBox = getChartPathInfosMergeBox(chart);
        if (mergeBox == null) {
            return null;
        }

        // 计算文字区域并合并
        Rectangle2D textBox = getChartChunkMergeBox(chart);
        if (textBox != null) {
            Rectangle2D.union(mergeBox, textBox, mergeBox);
        }
        return mergeBox;
    }

    /**
     * 计算Chart文字区域范围
     * @param chart
     * @return
     */
    public static Rectangle2D getChartChunkMergeBox(Chart chart) {
        // 合并内部TextChunk
        Rectangle2D mergeBox = null;
        if (chart.contentGroup != null) {
            List<TextChunk> chunks = chart.contentGroup.getAllTextChunks();
            Rectangle2D tBox = chunks.get(0);
            chunks.stream().forEach(
                    chunk -> Rectangle2D.union(tBox, chunk.getBounds(), tBox));
            mergeBox = (Rectangle2D)tBox.clone();
        }
        // 合并OCR区域
        if (!chart.ocrs.isEmpty()) {
            Rectangle2D oBox = chart.ocrs.get(0).path.getBounds2D();
            chart.ocrs.stream().forEach(
                    ocr -> Rectangle2D.union(oBox, ocr.path.getBounds2D(), oBox));
            if (mergeBox == null) {
                mergeBox = (Rectangle2D) oBox.clone();
            }
            else {
                Rectangle2D.union(mergeBox, oBox, mergeBox);
            }
        }
        return mergeBox;
    }

    /**
     * 判断两个水平和垂直方向直线段在给定容差条件下是否相交
     * (用来判断坐标轴是否有效用 如果扩充到任意方向直线 则会很复杂 暂时不扩充)
     *
     * @param lineA
     * @param lineB
     * @param zero
     * @return
     */
    public static boolean isHorizonVerticalLineIntersect(Line2D lineA, Line2D lineB, double zero) {
        // 判断是否为水平 垂直 直线
        boolean isHorizon = GraphicsUtil.isHorizontal(lineA);
        boolean isVertical = GraphicsUtil.isVertical(lineB);
        if (!isHorizon || !isVertical) {
            return false;
        }
        // 分别计算水平和垂直方向上 是否相交
        double xminA = lineA.getBounds2D().getMinX();
        double xmaxA = lineA.getBounds2D().getMaxX();
        double xminB = lineB.getBounds2D().getMinX();
        double xmaxB = lineB.getBounds2D().getMaxX();
        boolean xInterect = (xminA - zero <= xminB && xminB <= xmaxA + zero) ||
                (xminA - zero <= xmaxB && xmaxB <= xmaxA + zero);
        double yminA = lineA.getBounds2D().getMinY();
        double ymaxA = lineA.getBounds2D().getMaxY();
        double yminB = lineB.getBounds2D().getMinY();
        double ymaxB = lineB.getBounds2D().getMaxY();
        boolean yInterect = (yminB - zero <= yminA && yminA <= ymaxB + zero) ||
                (yminB - zero <= ymaxA && ymaxA <= ymaxB + zero);
        // 如果有一个方向不想交 则直线不相交
        if (!xInterect || !yInterect) {
            return false;
        } else {
            return true;
        }
    }
}

class BarStack {
    double key;         // 矩形对齐的中心位置
    double len;         // 各对齐叠加矩形的高度和
    int count;          // 叠加矩形的个数
    int numSameColor;   // 和第一个矩形同颜色的数目
    Color color;        // 第一个矩形的颜色

    BarStack(double k, double l, int c, Color color) {
        key = k;
        len = l;
        count = c;
        numSameColor = 0;
        this.color = color;
    }
}

