package com.abcft.pdfextract.core.chart;

import java.awt.*;
import java.awt.geom.GeneralPath;
import java.awt.geom.Line2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Rectangle2D;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;

import com.abcft.pdfextract.core.chart.model.PathInfo;
import com.abcft.pdfextract.core.model.TextChunk;
import com.abcft.pdfextract.core.model.TextDirection;
import com.abcft.pdfextract.core.model.TextElement;
import com.abcft.pdfextract.spi.ChartType;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import static com.abcft.pdfextract.core.chart.ChartPathInfosParser.matchPathInfosWithScaleAndLegends;
import static com.abcft.pdfextract.core.chart.ChartUtils.DELTA;

/**
 * 解析Chart内部图形元素对应的刻度属性信息
 * 按照不同类型采用不同方法解析  目前能处理  饼图  折线图  柱状图  面积图  混合图
 * Created by myyang on 17-4-13.
 */
public class ChartContentScaleParser {

    private static Logger logger = LogManager.getLogger(ChartContentScaleParser.class);

    /**
     * 解析Page内Chart集的具体内容　包括饼图各部分比例, 折线图顶点属性信息, 柱状图各矩形高度信息, 等等
     * @param charts Page内Chart集
     */
    public static void parserChartContents(List<Chart> charts) {
        int n = charts.size();
        if (n == 0) {
            return;
        }

        // 遍历Chart集　依次根据类型 解析数据
        boolean bTransforScale = true;
        boolean bParser = true;
        for (int i = 0; i < n; i++) {
            Chart chart = charts.get(i);
            if (chart.type == ChartType.BITMAP_CHART) {
                continue;
            }

            // 标记Chart内部图形组对象
            //ChartUtils.markChartPath(chart);

            // 解析饼图
            if (chart.type == ChartType.PIE_CHART) {
                bParser = parserPieInfo(chart);
                HighChartWriter writer = new HighChartWriter();
                writer.writeBson(chart, null);
                continue;
            }

            getLogicAxis(chart);

            // 再次匹配PathInfo与左右侧刻度和图例
            matchPathInfosWithScaleAndLegends(chart);

            // 左右上下刻度对应的数字信息集
            ChartScaleQuantificationInfo scaleNumberInfo = new ChartScaleQuantificationInfo();
            // 将刻度信息量化　保存起来　方便后续　插值计算 开发中
            bTransforScale = quantizeScales(chart, scaleNumberInfo);
            if (!bTransforScale) {
                continue;
            }

            // 解析　折线图　柱状图　面积图　混合图
            bParser = parserChartPathInfos(chart, scaleNumberInfo);
            if (chart.data == null) {
                logger.debug("page : " + (chart.pageIndex + 1) + " chart : "
                        + chart.title + "   data is null, parser error");
            }
        } // end for i
    }


    private static void getLogicAxis(Chart chart) {
        Iterator<ChartPathInfo> iter = chart.pathInfos.iterator();
//        Map<Double, Integer> frequency = new HashMap<>();
        List<Double> xs = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
        boolean isBarChart = false;

        // 遍历所有的Path　取出柱状对象Path
        while (iter.hasNext()){
            ChartPathInfo current = iter.next();
            if (current.type == PathInfo.PathType.BAR) {
                PathUtils.getPathPtsInfo(current.path, xs, ys);
                isBarChart = true;
            } else if (current.type == PathInfo.PathType.COLUMNAR) {
                PathUtils.getPathPtsInfo(current.path, xs, ys);
            }
        }

        // 如果没有检测到柱状对象，直接返回
        if(xs.isEmpty()) {
            return;
        }
        if (chart.isWaterFall) {
            return;
        }

        if (isBarChart) {
            // 如果是垂直柱状，则取Y值
            List<Float> floatYs = new ArrayList<>();
            for (Double item : ys) {
                floatYs.add(item.floatValue());
            }
            List<Float> yList = ChartPathInfosParser.getFloatModeList(floatYs);
            double y = Collections.max(yList);
            chart.hAxisLogic = new Line2D.Double(Collections.min(xs), y,
                                                Collections.max(xs), y);
        } else {
            // 如果是水平柱状，则取x值
            List<Float> floatXs = new ArrayList<>();
            for (Double item : xs) {
                floatXs.add(item.floatValue());
            }
            List<Float> xList = ChartPathInfosParser.getFloatModeList(floatXs);
            double x = Collections.min(xList);
            chart.hAxisLogic = new Line2D.Double(x, Collections.min(ys),
                                                x, Collections.max(ys));
        }
    }

    /**
     * 匹配图例和扇形对象颜色
     * 某些饼图或是环形图存在扇形块对象和图例颜色rgb值有很大误差，无法完全匹配，导致遗漏图例对象
     * 如果两者数目一直， 则找一个最佳颜色匹配方式，参考扇形块调整图例颜色
     * @param chart
     */
    public static void refixPieLegendColor(Chart chart) {
        // 如果数目不匹配 直接返回
        if (chart.legends.size() != chart.pieInfo.parts.size()) {
            return;
        }

        // 取出扇形颜色集
        List<Color> pclors = new ArrayList<>();
        chart.pieInfo.parts.stream().forEach(arc -> {
            if (!pclors.contains(arc.color)) {
                pclors.add(arc.color);
            }
        });
        // 取出图例颜色集
        List<Color> lclors = new ArrayList<>();
        List<Integer> ids = new ArrayList<>();
        for (Chart.Legend legend : chart.legends) {
            Color color = legend.color;
            if (!lclors.contains(color)) {
                ids.add(lclors.size());
                lclors.add(color);
            }
            else {
                ids.add(lclors.indexOf(color));
            }
        }
        // 匹配最佳颜色
        ChartPathInfosParser.matchPathLegendColor(pclors, lclors);
        if (pclors.size() == lclors.size()) {
            for (int i = 0; i < chart.legends.size(); i++) {
                Chart.Legend legend = chart.legends.get(i);
                int id = ids.get(i);
                legend.color = lclors.get(id);
            }
        }
    }

    /**
     * 解析饼图的内容信息
     * @param chart 给定Chart
     * @return 如果成功解析　则返回 true
     */
    public static boolean parserPieInfo(Chart chart) {
        int nLegends = chart.legends.size();
        int nPieInfoParts = chart.pieInfo.parts.size();

        // 匹配图例和扇形对象颜色
        refixPieLegendColor(chart);

        // 循环遍历　匹配颜色信息
        boolean bMatchColor = false;
        List<Integer> ids = new ArrayList<>();
        Map<Chart.PieInfo.PiePartInfo, Integer> zeros = new HashMap<>();
        List<Chart.Legend> matchStatus = new ArrayList<>();
        Iterator<Chart.PieInfo.PiePartInfo> iter = chart.pieInfo.parts.iterator();
        int i = 0;
//        for (int i = 0; i < nPieInfoParts; i++) {
        while (iter.hasNext()){
            bMatchColor = false;
            Chart.PieInfo.PiePartInfo partInfo = iter.next();
            Color color = partInfo.color;
            ids.clear();
            if (color.equals(Color.white) && Math.abs(partInfo.weight) < 1.E-5){
                zeros.put(partInfo, i);
            }
            for (int j = 0; j < nLegends; j++) {
                Chart.Legend legend = chart.legends.get(j);

                // 筛除饼图和图例都带百分比的情况 要把重复解析的数据删掉
                if (partInfo.text.equals(legend.text) && !color.equals(legend.color)){
                    iter.remove();
                    break;
                    //continue;
                }

                // 如果　颜色匹配　则保存图例内容信息
                if (color.equals(legend.color)) {
                    ids.add(j);
                    //partInfo.text = legend.text;
                    bMatchColor = true;
                    //break;
                    matchStatus.add(legend);
                }
            } // end for j
//            if (!bMatchColor && Math.abs(partInfo.weight) > 1.E-5) {
//                return false;
//            }
            // 如果一个饼图内的扇形配色　只匹配一个图例　则直接保存其含有的文字信息
            if (ids.size() == 1) {
                if(!partInfo.text.contains(chart.legends.get(ids.get(0)).text)){
                    partInfo.text = chart.legends.get(ids.get(0)).text;
                    // for test
                  /*  int idd = ids.get(0);
                    if (partInfo.hasNumberText) {
                        partInfo.text += "  N:";
                        partInfo.text += partInfo.numberText;
                    }
                    if (partInfo.hasPercentText) {
                        partInfo.text += "  P:";
                        partInfo.text += partInfo.percentText;
                    } */
                    // for test
                }
                continue;
            }
            // 如果有多个匹配图例　则循环遍历　过滤掉内容中包含百分号的图例 一般不为饼图显式图例
            for (Integer id : ids) {
                Chart.Legend legend = chart.legends.get(id);
                if (!legend.text.contains("%")) {
                    partInfo.text = legend.text;
                }
                else {
                    legend.text = "";
                }
            } // end for id
            i++;
        } // end for i


        // 删除图例中　内容为空的
        final Iterator<Chart.Legend> each = chart.legends.iterator();
        while (each.hasNext()) {
            Chart.Legend legend = each.next();
            if(!matchStatus.contains(legend)){
                if(!zeros.isEmpty()){
                    Map.Entry<Chart.PieInfo.PiePartInfo, Integer> entry = zeros.entrySet().iterator().next();
                    chart.pieInfo.parts.get(entry.getValue()).text = legend.text;

                    // for test
                    /*if (chart.pieInfo.parts.get(entry.getValue()).hasNumberText) {
                        chart.pieInfo.parts.get(entry.getValue()).text += "  N:";
                        chart.pieInfo.parts.get(entry.getValue()).text += chart.pieInfo.parts.get(entry.getValue()).numberText;
                    }
                    if (chart.pieInfo.parts.get(entry.getValue()).hasPercentText) {
                        chart.pieInfo.parts.get(entry.getValue()).text += "  P:";
                        chart.pieInfo.parts.get(entry.getValue()).text += chart.pieInfo.parts.get(entry.getValue()).percentText;
                    }*/
                    // for test

                    chart.pieInfo.parts.get(entry.getValue()).color = legend.color;
                    zeros.remove(entry.getKey());
                }
            }

            if (legend.text.equals("")) {
                each.remove();
            }
        } // end while
        return true;
    }

    /**
     * 将Chart一侧的刻度信息量化 抽出其位置信息　保存为有序序列 方便后续的属性插值
     * @param axisInfo 刻度字符串信息集
     * @param chunks 刻度对应的TextChunk集
     * @param ocrs 斜着刻度信息集
     * @param ithSide 当前处理的是哪一侧的刻度信息
     * @param type 当前Chart的类型
     * @param scaleNumber 量化后的对象
     * @return 如果量化成功　则返回 true
     */
    public static boolean quantizeOneSideScale(
            List<String> axisInfo,
            List<TextChunk> chunks,
            List<OCRPathInfo> ocrs,
            int ithSide,
            ChartType type,
            ChartScaleQuantificationInfo scaleNumber) {
        // 判断参数的有效性
        boolean hasOCRsTexts = isChartOCRsHasTexts(ocrs);
        if (axisInfo.isEmpty() || (chunks.isEmpty() && !hasOCRsTexts)
                || ithSide < 0 || ithSide > 3) {
            return false;
        }

        // 清空数据
        scaleNumber.infos[ithSide].clear();

        // 保存刻度内容信息　计算位置信息
        double pos = 0.0;
        for (TextChunk chunk : chunks) {
            scaleNumber.infos[ithSide].infos.add(chunk.getText());
            if (ithSide == 0 || ithSide == 1) {
                pos = chunk.getCenterY();
            }
            else {
                TextDirection direction = chunk.getDirection();
                if (direction == TextDirection.ROTATED) {
                    List<TextElement> elements = chunk.getElements();
                    double width = ChunkUtil.getMaxFontWidth(chunk);
                    int n = elements.size();
                    // 如果只有单个字符
                    if (n == 1) {
                        pos = chunk.getCenterX();
                    }
                    // 判断方向
                    else if (elements.get(0).getCenterY() >= elements.get(n - 1).getCenterY()) {
                        pos = chunk.getMaxX() + 0.5 * width;
                    }
                    else {
                        pos = chunk.getMinX() - 0.5 * width;
                    }
                }
                else {
                    pos = chunk.getCenterX();
                }
            }
            scaleNumber.infos[ithSide].xs.add(pos);
        } // end for chunk

        // 如果处理下方刻度 且 斜着刻度信息不为空
        if (ithSide == 2 && hasOCRsTexts) {
            if (scaleNumber.infos[ithSide].infos.isEmpty()) {
                for (int i = 0; i < ocrs.size(); i++) {
                    OCRPathInfo ocr = ocrs.get(i);
                    scaleNumber.infos[ithSide].infos.add(ocr.text);
                    scaleNumber.infos[ithSide].xs.add(ocr.x);
                }
            } else {
                ocrs.clear();
            }
        }

        // 对折线图 将上下侧刻度设置为标签类型
        // 待稳定后  后续扩展到柱状图 组合图
        if ((ithSide == 2 || ithSide == 3) &&
                (type == ChartType.LINE_CHART || type == ChartType.CURVE_CHART)) {
            scaleNumber.infos[ithSide].type = ScaleInfoType.LABEL_SCALE;
            return true;
        }

        boolean bTransfor =  false;
        // 如果是　上下侧　则一般为时间的概率大  水平的柱状图除外　此时水平垂直刻度信息类型对调
        if ((ithSide == 2 || ithSide == 3) && type != ChartType.COLUMN_CHART ) {
            // 作为时间转换
            bTransfor = strings2DateTimes(axisInfo, scaleNumber.infos[ithSide]);
            if (bTransfor) {
                return true;
            }
        }

        // 作为数字转换
        bTransfor = strings2Doubles(axisInfo, scaleNumber.infos[ithSide]);
        if (bTransfor) {
            return true;
        }

        // 作为时间转换
        bTransfor = strings2DateTimes(axisInfo, scaleNumber.infos[ithSide]);
        if (bTransfor) {
            return true;
        }

        if (type != ChartType.COLUMN_CHART) {
            if (ithSide == 0 || ithSide == 1) {
                axisInfo.clear();
                chunks.clear();
                scaleNumber.infos[ithSide].infos.clear();
                scaleNumber.infos[ithSide].xs.clear();
                return true;
            }
        }

        // 如果上诉转换都不成功　则为标签类型
        scaleNumber.infos[ithSide].type = ScaleInfoType.LABEL_SCALE;
        return true;
    }

    /**
     * 量化 Chart 左右上下侧的刻度信息
     * @param chart 给定Chart
     * @param scaleNumber Chart所以刻度对应的量化信息对象
     * @return 如果量化成功　则返回　true
     */
    public static boolean quantizeScales(
            Chart chart,
            ChartScaleQuantificationInfo scaleNumber) {
        // 如果是饼图　则不需要量化
        if (chart.type == ChartType.PIE_CHART) {
            return false;
        }
        scaleNumber.clear();

        // 量化左右上下侧刻度信息
        boolean bTransfor = false;
        if (!chart.vAxisChunksL.isEmpty()) {
            bTransfor = quantizeOneSideScale(
                    chart.vAxisTextL, chart.vAxisChunksL, chart.ocrs, 0,
                    chart.type, scaleNumber);
            if (!bTransfor) {
                return false;
            }
        } // end if left axis
        // 量化右侧刻度信息
        if (!chart.vAxisChunksR.isEmpty()) {
            bTransfor = quantizeOneSideScale(
                    chart.vAxisTextR, chart.vAxisChunksR, chart.ocrs, 1,
                    chart.type, scaleNumber);
            if (!bTransfor) {
                return false;
            }
        } // end if right axis

        // 量化下侧刻度信息
        if (!chart.hAxisChunksD.isEmpty() || isChartOCRsHasTexts(chart.ocrs)) {
            bTransfor = quantizeOneSideScale(
                    chart.hAxisTextD, chart.hAxisChunksD, chart.ocrs, 2,
                    chart.type, scaleNumber);
            if (!bTransfor) {
                return false;
            }
        }
        // 量化上侧刻度信息
        if (!chart.hAxisChunksU.isEmpty()) {
            bTransfor = quantizeOneSideScale(
                    chart.hAxisTextU, chart.hAxisChunksU, chart.ocrs, 3,
                    chart.type, scaleNumber);
            if (!bTransfor) {
                return false;
            }
        }
        return true;
    }

    public static boolean isChartOCRsHasTexts(List<OCRPathInfo> ocrs) {
        if (ocrs == null || ocrs.isEmpty()) {
            return false;
        }
        long icount = ocrs.stream().filter((ocr) -> StringUtils.isNoneEmpty(ocr.text)).count();
        return icount >= 1 ? true : false;
    }

    /**
     * 将字符串集转换为时间序列集(单位为毫秒的长整数)
     * @param strs 给定字符串集
     * @param scaleQua 刻度对应的量化信息对象
     * @return 如果给定字符串集量化成功　则返回　true
     */
    public static boolean strings2DateTimes(
            List<String> strs,
            ScaleQuantification scaleQua) {
        scaleQua.numsL.clear();
        try {
            // 过滤掉字符串中的空白符　提高检测准确度
            List<String> strsNoSpaces = new ArrayList<>();
            strs.stream().forEach(str -> strsNoSpaces.add(str.replaceAll(" ", "")) );
            StringsTimeFormatInfo formatInfo = getStrsTimeFormat(strsNoSpaces);
            if (formatInfo.timestamp == null) {
                return false;
            }
            // 保存最佳匹配的时间戳和时间样式
            scaleQua.numsL = formatInfo.timestamp;
            scaleQua.timeFormat = formatInfo.timeFormat;
        } // end try
        catch (Exception e) {
            logger.warn("解析出错", e);
            scaleQua.numsL.clear();
            return false;
        }

        // 设置时间类别
        scaleQua.type = ScaleInfoType.TIME_SCALE;
        // 过滤掉部分无效时间信息
        removeInvalidScale(scaleQua);
        return true;
    }

    /**
     * 过滤掉部分无效刻度量化信息
     * 比如存在　部分连续相同时间序列　此时后续的插值无法正确进行
     * 暂时过滤时间冲突的时间序列 后续考虑过滤无效数值刻度量化信息
     * @param scaleQua
     */
    public static void removeInvalidScale(ScaleQuantification scaleQua) {
        // 判断数据是否有效
        if (scaleQua.type != ScaleInfoType.TIME_SCALE) {
            return;
        }
        if (scaleQua.infos.size() != scaleQua.numsL.size() ||
                scaleQua.xs.size() != scaleQua.numsL.size() ) {
            return;
        }

        // 判断是否存在连续出现　不满足递增是时间序列
        long textBefore = -1;
        final Iterator<Long> eachLong = scaleQua.numsL.iterator();
        final Iterator<String> eachInfo = scaleQua.infos.iterator();
        final Iterator<Double> eachXs = scaleQua.xs.iterator();
        while (eachLong.hasNext()) {
            Long text = eachLong.next();
            String info = eachInfo.next();
            Double x = eachXs.next();
            if (textBefore == -1 || textBefore < text) {
                textBefore = text;
                continue;
            }

            // 如果出现　则删除后面的　保留最先出现的
            eachLong.remove();
            eachInfo.remove();
            eachXs.remove();
        } // end while
    }

    /**
     * 找出时间类字符串的有效时间信息集
     * @param strs
     * @return
     */
    public static StringsTimeFormatInfo getStrsTimeFormat(List<String> strs) {
        StringsTimeFormatInfo info = new StringsTimeFormatInfo(null, null);
        List<Integer> matchNums = new ArrayList<>();
        List<TimeFormat> formats = new ArrayList<>();
        List<String> patterns = new ArrayList<>();
        List<String> strSimples = new ArrayList<>();
        for (String str : strs) {
            String strSimple  = str.replaceAll("[\\s　]*", "").replaceAll("[AEFaef]$", "");
            strSimples.add(strSimple);
        }
        Map<String, TimeFormat> dateRegFormat = RegularMatch.getInstance().dateRegFormat;
        Map<String, Pattern> dateRegPattern = RegularMatch.getInstance().dateRegPattern;

        try {
            // 遍历正则表达式集　找出匹配的时间类格式
            for (Map.Entry<String, TimeFormat> entry: dateRegFormat.entrySet()) {
                int count = 0;
                // 遍历字符串集　找出匹配的个数
                for (String str : strSimples) {
                    if (dateRegPattern.get(entry.getKey()).matcher(str).matches()) {
                        count++;
                    }
                }
                matchNums.add(count);
                formats.add(entry.getValue());
                patterns.add(entry.getKey());
            }
        } catch (Exception e) {
            return info;
        }

        // 统计全部匹配的模式的个数
        int n = strSimples.size();
        long nsame = matchNums.stream().filter((num) -> num.equals(n)).count();
        List<StringsTimeFormatInfo> candidateInfos = new ArrayList<>();
        List<Double> scales = new ArrayList<>();

        // 找出全部匹配的正则表达式 作为候选格式集
        for (int i = 0; i < matchNums.size(); i++) {
            int nMatch = matchNums.get(i);
            TimeFormat timeFormat = formats.get(i);
            String pattern = patterns.get(i);
            // 如果全部匹配　则做进一步　判断提取其对应的时间长整数
            if (nMatch == n) {
                List<Long> numsL = getStrsTimeLongInt(strSimples, timeFormat.formatCommon, nsame >= 2);
                if (numsL != null) {
                    // 判断时间戳间距是否大致相同
                    double scale = 0.0;
                    if (nsame >= 2) {
                        Long distOld = 0L;
                        Long dist = 0L;
                        boolean isValid = true;
                        for (int j = 0; j < numsL.size() - 1; j++) {
                            Long t1 = numsL.get(j);
                            Long t2 = numsL.get(j + 1);
                            dist = t2 - t1;
                            if (dist <= 0) {
                                isValid = false;
                                break;
                            }
                            if (j >= 1) {
                               scale = scale + dist * 1.0 / distOld;
                            }
                            distOld = dist;
                        }
                        if (!isValid) {
                            continue;
                        }
                    }
                    // 将满足时间规律的样式作为候选保存起来 　后面再做进一步的帅选
                    StringsTimeFormatInfo candidateInfo = new StringsTimeFormatInfo(numsL, timeFormat);
                    candidateInfos.add(candidateInfo);
                    scales.add(scale / numsL.size());
                }
            }
            /*
            else if (nMatch > n / 2) {
                // 如果有超过一半的match 则尝试用已经match的format去局部匹配未能匹配上的刻度
                // 修复2018 M1-M2 这类问题
                Pattern p = Pattern.compile(pattern);
                List<String> fuzzyStrs = new ArrayList<>();
                int fuzzyMatch = 0;
                for (int k = 0; k < n; k++) {
                    Matcher m = p.matcher(strSimples.get(k));
                    if (m.find()) {
                        fuzzyMatch++;
                        fuzzyStrs.add(m.group());
                    }
                }
                if (fuzzyMatch == n) {
                    // 模糊匹配成功
                    List<Long> numsL = getStrsTimeLongInt(fuzzyStrs, timeFormat.formatCommon, nsame >= 2);
                    if (numsL != null) {
                        // 判断时间戳间距是否大致相同
                        double scale = 0.0;
                        if (nsame >= 2) {
                            Long distOld = 0L;
                            Long dist = 0L;
                            boolean isValid = true;
                            for (int j = 0; j < numsL.size() - 1; j++) {
                                Long t1 = numsL.get(j);
                                Long t2 = numsL.get(j + 1);
                                dist = t2 - t1;
                                if (dist == 0) {
                                    isValid = false;
                                    break;
                                }
                                if (j >= 1) {
                                    scale = scale + dist/distOld;
                                }
                                distOld = dist;
                            }
                            if (!isValid) {
                                continue;
                            }
                        }
                        // 将满足时间规律的样式作为候选保存起来 　后面再做进一步的帅选
                        StringsTimeFormatInfo candidateInfo = new StringsTimeFormatInfo(numsL, timeFormat);
                        candidateInfos.add(candidateInfo);
                        scales.add(scale / numsL.size());
                    }
                }
            }*/
        } // end for i

        // 如果有多个候选时间样式　则根据实际时间刻度的规律，一般含有年份的可能性更大
        if (candidateInfos.size() >= 1) {
            info = candidateInfos.get(0);
            boolean flag = true;
            double candidatescale = 0;
            double scaleMin = Collections.min(scales);
            for (int i = 0; i < candidateInfos.size(); i++) {
                StringsTimeFormatInfo candidateInfo = candidateInfos.get(i);
                // 找出包含年份的
                if (candidateInfo.timeFormat.formatCommon.contains("yy") ||
                        candidateInfo.timeFormat.formatCommon.contains("yyyy")) {
                    if(flag){
                        //scales的序列和candidateInfos是对应的
                        candidatescale = scales.get(i);
                        info = candidateInfo;
                        flag = false;
                    }
                    if(candidatescale > scales.get(i)){
                        candidatescale = scales.get(i);
                        info = candidateInfo;
                    }
                    if(scaleMin==scales.get(i)){
                        break;
                    }

                }
                // 找出间隔最小的
                if (flag && (Math.abs(scales.get(i) - scaleMin) < 1.E-3)) {
                    info = candidateInfo;
                }
            }
        }

        return info;
    }

    public static String[] transferSpecilTimeStr(String str2, String format) {
        String str = str2.replaceAll("[\\s　]*", "");
        String [] res = {str2, format};
        String [] Hs = {"06-30", "12-31"};
        String [] Qs = {"03-31", "06-30", "09-30", "12-31"};
        int ns = format.length();
        if (format.equals("yyyyE")) {
            res[0] = str.substring(0, 4) + "-1-1";
            res[1] = "yyyy-MM-dd";
        }
        else if (format.equals("yyE")) {
            res[0] = str.substring(0, 2) + "-1-1";
            res[1] = "yy-MM-dd";
        }
        else if (format.equals("UyyE")) { // 处理英文中出现的 '14  '18E   debug中
            res[0] = str.substring(1, 3) + "-1-1";
            res[1] = "yy-MM-dd";
        }
        else if (format.equals("Eyy")) {
            res[0] = str.substring(ns - 2, ns) + "-1-1";
            res[1] = "yy-MM-dd";
        }
        else if (format.equals("Eyyyy")) {
            res[0] = str.substring(ns - 4, ns) + "-1-1";
            res[1] = "yyyy-MM-dd";
        }
        else if (format.equals("yyyyH")) {
            String n = str.substring(5, 6);
            res[0] = str.substring(0, 4) + "-" + Hs[Integer.parseInt(n) - 1];
            res[1] = "yyyy-MM-dd";
        }
        else if (format.equals("yyH")) {
            String n = str.substring(3, 4);
            res[0] = str.substring(0, 2) + "-" + Hs[Integer.parseInt(n) - 1];
            res[1] = "yy-MM-dd";
        }
        else if (format.equals("Hyyyy")) {
            String n = str.substring(0, 1);
            res[0] = str.substring(2, 6) + "-" + Hs[Integer.parseInt(n) - 1];
            res[1] = "yyyy-MM-dd";
        }
        else if (format.equals("Hyy")) {
            String n = str.substring(0, 1);
            res[0] = str.substring(2, 4) + "-" + Hs[Integer.parseInt(n) - 1];
            res[1] = "yy-MM-dd";
        }
        else if (format.equals("yyyyQ")) {
            String n = str.substring(5, 6);
            res[0] = str.substring(0, 4) + "-" + Qs[Integer.parseInt(n) - 1];
            res[1] = "yyyy-MM-dd";
        }
        else if (format.equals("yyQ")) {
            String n = str.substring(3, 4);
            res[0] = str.substring(0, 2) + "-" + Qs[Integer.parseInt(n) - 1];
            res[1] = "yy-MM-dd";
        }
        else if (format.equals("Qyyyy")) {
            String n = str.substring(0, 1);
            res[0] = str.substring(2, 6) + "-" + Qs[Integer.parseInt(n) - 1];
            res[1] = "yyyy-MM-dd";
        }
        else if (format.equals("Qnyyyy")) {
            String n = str.substring(1, 2);
            res[0] = str.substring(2, 6) + "-" + Qs[Integer.parseInt(n) - 1];
            res[1] = "yyyy-MM-dd";
        }
        else if (format.equals("Qyy")) {
            String n = str.substring(0, 1);
            int len = str.length();
            res[0] = str.substring(len - 2, len) + "-" + Qs[Integer.parseInt(n) - 1];
            res[1] = "yy-MM-dd";
        }
        else if (format.equals("Qnyy")) {
            String n = str.substring(1, 2);
            int len = str.length();
            res[0] = str.substring(len - 2, len) + "-" + Qs[Integer.parseInt(n) - 1];
            res[1] = "yy-MM-dd";
        }
        else if (format.equals("yyyy-MM-dd-HH-mm-ss")) {
            String HHmmss[] = str.split(":");
            res[0] = "1-15" + HHmmss[0] + ":" + HHmmss[1] + ":" + HHmmss[2];
            res[1] = "yyyy-MM-dd-HH-mm-ss";
        }
        else if (format.equals("yyyy-MM-dd-HH-mm")) {
            String HHmmss[] = str.split(":");
            res[0] = "1-15-" + HHmmss[0] + "-" + HHmmss[1];
            res[1] = "yyyy-MM-dd-HH-mm";
        }
        return res;
    }

    /**
     * 找出时间字符串集对应的长整数集
     * @param strs
     * @param format
     * @param filterYear
     * @return
     */
    public static List<Long> getStrsTimeLongInt(
            List<String> strs, String format, boolean filterYear) {
        int n = strs.size();
        String strStd = "";
        List<Long> numsL = new ArrayList<>();
        Long timeBefore = 0L, timeCurrent = 0L;
        String prefix = "";
        // 如果是 MM-dd 格式　则从右到左　默认年份从17年开始递减 后续可能需要　外面给定候选年份
        int prefixInt = 2017;
        boolean bddUMMUyy = format.equals("dd-MMM-yy");
        boolean bMMU = format.equals("MM-");
        boolean bMMUdd = format.equals("MM-dd");
        boolean bMMUddU = format.equals("MM-dd-");
        boolean bMMdd = format.equals("MMdd");
        boolean bMMMUdd = format.equals("MMM-dd");
        boolean bMMMdd = format.equals("MMMdd");
        boolean bddUMMM = format.equals("dd-MMM");
        boolean bddMMM = format.equals("ddMMM");
        boolean bMMMUyy = format.equals("MMM-yy");
        boolean bMMMyy = format.equals("MMMyy");
        boolean bMMMyyyy = format.equals("MMMyyyy");
        boolean bMMMUyyyy = format.equals("MMM-yyyy");
        boolean byyUMMM = format.equals("yy-MMM");
        boolean byyMMM = format.equals("yyMMM");
        boolean byyyMMM = format.equals("yyyyMMM");
        boolean byyyUMMM = format.equals("yyyy-MMM");
        boolean bMMM = format.equals("MMM");
        boolean bHHmmss = format.equals("HHmmss");
        boolean bHHmm = format.equals("HHmm");
        if (bMMUdd) {
            format = "yyyy-MM-dd";
            prefix = String.valueOf(prefixInt) + "-";
        }
        else if (bMMUddU) {
            format = "yyyy-MM-dd-";
            prefix = String.valueOf(prefixInt) + "-";
        }
        else if (bMMdd) {
            format = "yyyy-MMdd";
            prefix = String.valueOf(prefixInt) + "-";
        }
        else if (bMMMUdd) {
            format = "yyyy-MMM-dd";
            prefix = String.valueOf(prefixInt) + "-";
        }
        else if (bMMMdd) {
            format = "yyyy-MMMdd";
            prefix = String.valueOf(prefixInt) + "-";
        }
        else if (bddUMMM) {
            format = "yyyy-dd-MMM";
            prefix = String.valueOf(prefixInt) + "-";
        }
        else if (bddMMM) {
            format = "yyyy-ddMMM";
            prefix = String.valueOf(prefixInt) + "-";
        }
        else if (bMMM) {
            format = "yyyy-MMM";
            prefix = String.valueOf(prefixInt) + "-";
        }
        else if (bMMU) {
            format = "yyyy-MM-";
            prefix = String.valueOf(prefixInt) + "-";
        }
        else if (bHHmmss) {
            format = "yyyy-MM-dd-HH-mm-ss";
            prefix = String.valueOf(prefixInt) + "-";
        }
        else if (bHHmm) {
            format = "yyyy-MM-dd-HH-mm";
            prefix = String.valueOf(prefixInt) + "-";
        }

        try {
            int yearInvalid = 0;
            int year = 0;
            Calendar calendar = Calendar.getInstance();
            for (int i = n - 1; i >= 0; i--) {
                String s = strs.get(i);
                String[] ss = transferSpecilTimeStr(s, format);
                String str = ss[0];
                String formatNew = ss[1];
                //String str = strs.get(i);
                // 处理月份用英文名前三个字母代替的情况   后续可能添加其他情况处理方法
                if (bMMMUyy || bMMMyy || bMMMUdd || bddUMMUyy || bMMMdd  ||
                        bddMMM  || bddUMMM || bMMMyy || bMMM  || bMMMyyyy ||
                        bMMMUyyyy || byyMMM || byyUMMM  || byyyMMM || byyyUMMM) {
                    strStd = str.replaceAll("[^A-Za-z0-9]", "-");
                }
                else {
                    strStd = str.replaceAll("\\D+", "-");
                }
                String strDate = prefix + strStd;
                DateFormat dateFormat = new SimpleDateFormat(formatNew, Locale.ENGLISH);
                Date date = dateFormat.parse(strDate);

                // 一般年份都是近期  如果年代久远或超前太多很有可能匹配模式出错
                // 如果存在多个匹配项目　则开始统计可能错误的个数
                calendar.setTime(date);
                year = calendar.get(Calendar.YEAR);
                if (filterYear && (year <= 1970 || year >= 2050)) {
                    yearInvalid++;
                }

                timeCurrent = date.getTime();
                if (i < n - 1 && timeBefore < timeCurrent) {
                    // 如果是 MM-dd 格式　则适当递减年份　满足时间变化规律
                    if (bMMUdd || bMMUddU || bMMMUdd || bMMMdd || bddMMM || bddUMMM || bMMM || bMMU || bMMdd) {
                        prefixInt--;
                        prefix = String.valueOf(prefixInt) + "-";
                        strDate = prefix + strStd;
                        dateFormat = new SimpleDateFormat(formatNew, Locale.ENGLISH);
                        date = dateFormat.parse(strDate);
                        timeCurrent = date.getTime();
                    }
                    else if (bHHmmss || bHHmm) {
                        date = setDateBeforeRefDate(new Date(timeBefore), date);
                        timeCurrent = date.getTime();
                    }
                    else if (i == n - 2) {
                        //numsL.clear();  // 保留　和刻度字符串保持一致　后续步骤　删除
                    }
                    else {
                        return null;
                    }
                } // end if
                if (bMMUdd || bMMUddU || bMMMUdd || bMMMdd || bddMMM || bddUMMM || bMMM || bMMU || bMMdd) {
                    prefix = String.valueOf(prefixInt) + "-";
                }
                timeBefore = timeCurrent;
                numsL.add(timeCurrent);
            } // end for i
            // 如果输入字符串集都是出现概率很低的年份　则判断为错误  返回null
            if (yearInvalid == n) {
                return null;
            }
        } catch (Exception e) {
            return null;
        }
        if (numsL.isEmpty()) {
            return null;
        }
        Collections.reverse(numsL);
        return numsL;
    }

    public static Date setDateBeforeRefDate(Date refDate, Date date)
    {
        Calendar calRef = Calendar.getInstance();
        calRef.setTime(refDate);
        Calendar cal = Calendar.getInstance();
        cal.setTime(date);
        // 如果给定时间在参考时间之后　则循环向前退一天 直到在参考时间之前为止
        while(cal.after(calRef)) {
            cal.add(Calendar.DATE, -1); //minus number would decrement the days
        }
        return cal.getTime();
    }

    /**
     * 将字符串转化为浮点数
     * 碰到　很多日期类字符串也可以转换为浮点数
     * 这种情况一般通过Chart的类别和刻度信息的位置做初期的逻辑判断过滤 随着碰到Chart类别的增多　后续有待持续改进
     * 其他非数字的字符串转换时会抛异常　此时返回false
     * @param str 输入字符串
     * @param num 转换后的浮点数
     * @param suffix 浮点数后缀　很有可能是 %
     * @return 如果转换成功　则返回 true
     */
    public static boolean string2Double(
            String str,
            double[] num,
            String[] suffix) {
        try {
            // 判断时间有效性
            if (num == null || suffix == null) {
                return false;
            }

            str = str.replaceAll("\\s", "");
            RegularMatch reg = RegularMatch.getInstance();

            // 清除掉所有特殊字符  一般千位数有逗号
            // 目前碰到很多数字被括号包围　好像是代表负号的意思
            Pattern regExNumberAuxiliaryPattern  = reg.regExNumberAuxiliaryPattern;
            Matcher matcher = regExNumberAuxiliaryPattern.matcher(str);
            String strNum = matcher.replaceAll("").trim();
            strNum = strNum.replace("‒", "-");
            if (strNum.length() == 0) {
                return false;
            }
            // 通常如果只有 -  则表示为数值 0.0
            if (strNum.equals("-") || strNum.equals("\u2010")) {
                num[0] = 0.0;
            } else if (strNum.charAt(0) == '\u2010') {
                // 特殊减号
                num[0] = - Double.parseDouble(strNum.substring(1));
            } else {
                num[0] = Double.parseDouble(strNum);
            }
            if (str.contains(")") && str.startsWith("(")) {
                num[0] *= -1.0;
            }

            // 很多字符串以%结尾　表示百分比数 找出后缀
            Pattern regExSuffixPattern = reg.regExSuffixPattern;
            matcher = regExSuffixPattern.matcher(str);
            suffix[0] = "";
            if (matcher.find()) {
//                suffix[0] = "%";
                suffix[0] = matcher.group();
            }
//            Pattern regExDollarPattern = reg.regExDollarPattern;
//            matcher = regExDollarPattern.matcher(str);
//            if (matcher.find()) {
//                suffix[0] = "$";
//            }
//            Pattern regExUnitPattern = reg.regExUnitPattern;
//            matcher = regExUnitPattern.matcher(str);
//            if (matcher.find()) {
//                suffix[0] = "x";
//            }
        } // end try
        catch (Exception e) {
            return false;
        }
        return true;
    }

    /**
     * 将字符串集依次转换为浮点数集
     * @param strs 输入字符串集
     * @param scaleQua 刻度信息量化对象
     * @return 如果转换成功　则返回 true
     */
    public static boolean strings2Doubles(List<String> strs, ScaleQuantification scaleQua) {
        // 清空现有的容器
        scaleQua.numsD.clear();

        // 遍历　依次转换每一个字符串
        double[] num = {0.0};
        for (int i = 0; i < strs.size(); i++) {
            String str = strs.get(i);
            if (!string2Double(str, num, scaleQua.suffix)) {
                scaleQua.numsD.clear();
                return false;
            }
            scaleQua.numsD.add(num[0]);
        } // end for i

        // 如果构成等差序列 则认为是有效刻度 直接设置类型并返回
        if (isArithmeticSequence(scaleQua.numsD)) {
            // 设置类型
            scaleQua.type = ScaleInfoType.NUMBER_SCALE;
            return true;
        }

        // 考虑到部分数字刻度　用其他颜色或括号表示负数
        // 故从上往下比较　如果相邻的数，后一个大于前一个　则反号 调试中
        int n = scaleQua.numsD.size();
        for (int i = n - 2; i >= 0; i--) {
            double fbefore = scaleQua.numsD.get(i + 1);
            double fafter = scaleQua.numsD.get(i);
            if (fbefore < fafter) {
                scaleQua.numsD.set(i, -1.0 * fafter);
            }
        }

        // 判断是否为有效等差序列
        if (!isArithmeticSequence(scaleQua.numsD)) {
            // 如果不满足等差序列规律 则取最大值最小之差除以刻度个数作为步长 从第二个刻度开始依次调整刻度值
            // 如果最小最大值相同 则基于最小值的绝对值除以刻度个数作为步长
            double first = scaleQua.numsD.get(0);
            double dx = (scaleQua.numsD.get(n - 1) - first) / (n - 1);
            if (Math.abs(dx) < 1.0E-4) {
                String str = String.valueOf(first);
                int index = str.indexOf('.');
                if (index < 0) {
                    dx = 0.9 / (n - 1);
                }
                else {
                    int numFloat = str.length() - 1 - index;
                    dx = (0.9 / Math.pow(10, numFloat)) / (n - 1);
                }
            }
            for (int i = 1; i < n; i++) {
                double newValue = first + i * dx;
                scaleQua.numsD.set(i, newValue);
            }
        }

        // 设置类型
        scaleQua.type = ScaleInfoType.NUMBER_SCALE;
        return true;
    }

    /**
     * 判断给定浮点数集是否构成有效等差序列
     * @param nums
     * @return
     */
    public static boolean isArithmeticSequence(List<Double> nums) {
        int n = nums.size();
        if (n <= 2) {
            return true;
        }
        double dxFirst = nums.get(0) - nums.get(1);
        boolean upSort = dxFirst < 0.0 ? true : false;
        dxFirst = Math.abs(dxFirst);
        if (dxFirst < 0.00001) {
            return false;
        }
        for (int i = 1; i < n - 1; i++) {
            double dx = nums.get(i) - nums.get(i + 1);
            boolean upSort2 = dx < 0.0 ? true : false;
            if (upSort != upSort2) {
                return false;
            }
            if (Math.abs(dxFirst - Math.abs(dx)) >= 0.2 * dxFirst) {
                return false;
            }
        }
        return true;
    }

    /**
     * 给定点集的坐标位置信息　基于刻度信息量化后的对象　插值出相应的刻度属性
     * @param xs 给定点集的坐标位置
     * @param scaleQua 刻度信息量化对象
     * @param ys 插值出的刻度属性集
     * @return 如果都插值成功　则返回　true
     */
    public static boolean interpolationNumbers(
            List<Double> xs,
            ScaleQuantification scaleQua,
            List<Double> ys) {
        // 检测数据有效性
        if (xs == null || ys == null) {
            return false;
        }

        // 循环遍历　插值每一个点的刻度属性
        ys.clear();
        double[] value = {0.0};
        List<Boolean> ptStatus = new ArrayList<>();
        for (int i = 0; i < xs.size(); i++) {
            double x = xs.get(i);
            ptStatus.add(true);
            if (!interpolationNumber(x, scaleQua, value)) {
                // 如果解析异常  则用前一个值代替
                if (!ys.isEmpty() && ptStatus.get(i - 1)) {
                    value[0] = ys.get(ys.size() - 1);
                }
                else {
                    // 如果没有成功插值　则记录状态
                    ptStatus.set(i, false);
                    value[0] = 0.0;
                    //return false;
                }
            }
            ys.add(value[0]);
        }
        // 尝试给没有成功插值的点赋值
        for (int i = 0; i < ptStatus.size(); i++) {
            if (ptStatus.get(i)) {
                continue;
            }
            if (i >= 1) {
                ys.set(i, ys.get(i - 1));
            }
            else if (i == 0 && ptStatus.size() >= 2 && ptStatus.get(i + 1)) {
                ys.set(i, ys.get(i + 1));
            }
            else {
                return false;
            }
        }
        return true;
    }

    /**
     * 根据给定点在有序序列上的位置　用线性方法插值
     * @param x 给定点的位置
     * @param scaleQua 刻度信息量化对象
     * @param y 插值的结果
     * @return 如果插值成功　则返回 true
     */
    public static boolean interpolationNumber(
            double x,
            ScaleQuantification scaleQua,
            double [] y) {
        // 数据有效性检查
        if (y == null) {
            return false;
        }

        // 计算距离插值比例系数
        int [] pos = {0};
        double [] coef = {0.0};
        boolean [] reverse = {false};
        boolean bRate = compDistanceRatio(x, scaleQua.xs, pos, coef, reverse);
        if (!bRate) {
            return false;
        }

        // 用线性插值 后续可以添加多种高精度插值方法
        try {
            int i = pos[0];
            int n = scaleQua.xs.size();
            if (reverse[0]) {
                i = n - i;
                coef[0] = 1.0 - coef[0];
            }
            // 个数必须匹配　　即刻度没有解析正确则数目不一致 返回false
            if (scaleQua.numsD.size() != n) {
                return false;
            }
            double yL = 0.0, yR = 0.0;
            if (0 < i && i < n) {
                yL = scaleQua.numsD.get(i - 1);
                yR = scaleQua.numsD.get(i);
            } else if (i == 0) {
                yR = scaleQua.numsD.get(0);
                if (reverse[0]) {
                    yL = yR + 10.0 * (yR - scaleQua.numsD.get(1));
                }
                else {
                    yL = yR - 10.0 * (scaleQua.numsD.get(1) - yR);
                }
            } else {
                yL = scaleQua.numsD.get(n - 1);
                if (reverse[0]) {
                    yR = yL - 10.0 * (scaleQua.numsD.get(n - 2) - yL);
                }
                else {
                    yR = yL + 10.0 * (yL - scaleQua.numsD.get(n - 2));
                }
            }
            y[0] = coef[0] * yL + (1.0 - coef[0]) * yR;
        } catch (Exception e) {
            logger.warn(e);
            return false;
        }
        return true;
    }

    /**
     * 计算给定值 x 在有序(递增)序列中的位置和插值信息
     * 一般将刻度信息量化　抽出其位置组合成有序序列
     * @param x 给定待查询值
     * @param xsOrigin 有序序列
     * @param pos 插值位置信息
     * @param coef 插值比列系数
     * @param reverse
     * @return 如果找到位置和插值信息　则返回 true
     */
    public static boolean compDistanceRatio(
            double x,
            List<Double> xsOrigin,
            int []pos,
            double []coef,
            boolean []reverse) {
        // 如果参数无效　或　插值参考值太少　或　待插值x超出范围　则返回 false
        int n = xsOrigin.size();
        if (n <= 1 || pos == null || coef == null) {
            return false;
        }

        reverse[0] = false;
        List<Double> xs = new ArrayList<>(xsOrigin);
        if (xs.get(0) > xs.get(1)) {
            Collections.reverse(xs);
            reverse[0] = true;
        }

        // 循环遍历　找出 x  所在的区间
        double xR = 0.0, xL = 0.0;
        double distR = 0.0, distL = 0.0;
        for (int i = 1; i < n; i++) {
            xR = xs.get(i);
            // 如果近似相等
            if (ChartUtils.equals(x, xR)) {
                coef[0] = 0.0;
                pos[0] = i;
                return true;
            }
            // 大于右侧值　则跳过
            else if (x > xR) {
                continue;
            }
            // 计算X到左右侧的距离　计算比例系数
            distR = xR - x;
            xL = xs.get(i - 1);
            distL = x - xL;
            coef[0] = distR / (distR + distL);
            pos[0] = i;
            return true;
        } // end for i

        // 如果 x 在参考信息的左侧
        xR = xs.get(0);
        xL = xR - 10.0 * (xs.get(1) - xR);
        if (xL < x && x < xR) {
            distR = xR - x;
            distL = x - xL;
            coef[0] = distR / (distR + distL);
            pos[0] = 0;
            return true;
        }

        // 如果 x 在参考信息的右侧
        xL = xs.get(n - 1);
        xR = xL + 10.0 * (xL - xs.get(n - 2));
        if (xL < x && x < xR) {
            distR = xR - x;
            distL = x - xL;
            coef[0] = distR / (distR + distL);
            pos[0] = n;
            return true;
        }

        return false;
    }

    /**
     * 基于标称类的参考信息集　用最近邻方法　找出给定点的合适标签
     * @param x 给定点的位置
     * @param scaleQua 刻度信息量化对象
     * @param label 合适的标签
     * @return 如果找到合适标签　则返回　true
     */
    public static boolean interpolationNearestNeighbor(
            double x,
            ScaleQuantification scaleQua,
            String[] label) {
        // 检查数据有效性
        if (label == null) {
            return false;
        }

        // 一般插值的参考信息个数不会少于两个
        int n = scaleQua.xs.size();
        if (n <= 1) {
            return false;
        }

        // 遍历　找出最近邻
        // 其实参考信息集是有序序列　可以用更高效的查找算法找到最近邻　但是目前刻度规模一般很小 所以用了遍历方法
        int iMin = -1;
        double distMin = 0.0;
        double dist = 0.0;
        for (int i = 0; i < n; i++) {
            dist = Math.abs(x - scaleQua.xs.get(i));
            if (i == 0 || dist < distMin) {
                distMin = dist;
                iMin = i;
            }
        } // end for i

        // 设置最近邻的标称为当前点的标签
        label[0] = scaleQua.infos.get(iMin);
        return true;
    }

    /**
     * 给定点集和刻度信息量化对象 依次找出合适的标签
     * @param xs 给定点集
     * @param scaleQua 刻度信息量化对象
     * @param ys 找出的合适标签集
     * @return 如果都找到合适的标签 则返回 true
     */
    public static boolean interpolationLabels(
            List<Double> xs,
            ScaleQuantification scaleQua,
            List<String> ys) {
        // 检测数据有效性
        if (xs == null || ys == null) {
            return false;
        }

        // 循环遍历　用最近邻方法　找出合适的标签
        ys.clear();
        String[] value = {""};
        for (Double x : xs) {
            if (!interpolationNearestNeighbor(x, scaleQua, value)) {
                return false;
            }
            ys.add(value[0]);
        }
        return true;
    }

    /**
     * 将给定点的位置 基于时间刻度信息 进行插值  用的一阶线性插值方法　
     * 目前遇到　时间不完全连续问题　比如周末或节假日一般没有相应信息记录如股票市场 后续有待改进
     * @param x 给定点的位置
     * @param scaleQua 刻度信息量化对象
     * @param label 插值出的相应时间字符串
     * @param bNotSubdivision 是否需要细分插值状态标识 有些情况下时间信息作为标签类型
     * @return 如果插值成功　则返回　true
     */
    public static boolean interpolationDate(
            double x,
            ScaleQuantification scaleQua,
            String[] label,
            boolean bNotSubdivision) {
        // 一般插值的参考信息个数不会少于两个 否则不需要这一个纬度的信息
        int n = scaleQua.xs.size();
        if (n <= 1) {
            return false;
        }

        // 如果不需要细分　则当成是　标称类信息的最近邻插值问题
        if (bNotSubdivision) {
            return interpolationNearestNeighbor(x, scaleQua, label);
        } // end if

        if (scaleQua.numsL.isEmpty()) {
            return false;
        }

        // 如果刻度信息和量化后的信息个数不一致
        if (n != scaleQua.numsL.size() || n != scaleQua.infos.size()) {
            return false;
        }

        // 遍历　找出给定点 所在的 参考位置集 中合适的区间
        long ith = 0;
        double xL = 0.0, xR = 0.0;
        double distL = 0.0, distR = 0.0, coef = 0.0;
        double w = (scaleQua.xs.get(n - 1) - scaleQua.xs.get(0)) / (n - 1);
        for (int i = 0; i <= n - 2; i++) {
            // 取相邻两点
            xL = scaleQua.xs.get(i);
            xR = scaleQua.xs.get(i + 1);
            // 近似相等
            if (ChartUtils.equals(x, xL)) {
                //label[0] = scaleQua.infos.get(i);
                ith = scaleQua.numsL.get(i);
                label[0] = String.valueOf(ith);
                return true;
            }
            // 如果在区间内 则计算比例系数　做一阶线性插值
            if (xL < x && x < xR) {
                distL = x - xL;
                distR = xR - x;
                coef = distR / (distL + distR);
                ith = (long)(coef * scaleQua.numsL.get(i) + (1.0 - coef) * scaleQua.numsL.get(i + 1));
                //Date time = new Date(ith);
                //label[0] = time.toString();
                label[0] = String.valueOf(ith);
                return true;
            }
        } // end for i

        // 如果给定点在参考位置集的左侧或右侧　则分别处理
        xL = scaleQua.xs.get(0);
        xR = scaleQua.xs.get(n - 1);
        long wLong = (scaleQua.numsL.get(n - 1) - scaleQua.numsL.get(0)) / (n - 1), ithL = 0, ithR = 0;
        // 左侧情况
        if (x < xL) {
            distL = x - (xL - w);
            distR = xL - x;
            coef = distR / (distL + distR);
            ithL = scaleQua.numsL.get(0);
            ith = (long)(coef * (ithL - wLong) + (1.0 - coef) * ithL);
        }
        // 右侧情况
        else {
            distL = x - xR;
            distR = xR + w - x;
            coef = distR / (distL + distR);
            ithR = scaleQua.numsL.get(n - 1);
            ith = (long)(coef * ithR + (1.0 - coef) * (ithR + wLong));
        }

        // 将量化的毫秒数　转化为时间　在序列化为字符串
        //Date time = new Date(ith);
        //label[0] = time.toString();
        label[0] = String.valueOf(ith);
        return true;
    }

    /**
     * 给定点集　基于时间刻度信息 进行插值
     * @param xs 给定的点集
     * @param scaleQua 刻度信息量化对象
     * @param ys 插值出的相应时间字符串集
     * @param bNotSubdivision 是否需要细分插值状态标识 有些情况下时间信息作为标签类型
     * @return 如果插值成功　则返回　true
     */
    public static boolean interpolationDates(
            List<Double> xs,
            ScaleQuantification scaleQua,
            List<String> ys,
            boolean bNotSubdivision) {
        // 检测数据有效性
        if (xs == null || ys == null) {
            return false;
        }

        // 循环遍历　对每一个点计算插值
        ys.clear();
        String[] value = {""};
        for (Double x : xs) {
            if (!interpolationDate(x, scaleQua, value, bNotSubdivision)) {
                return false;
            }
            ys.add(value[0]);
        }
        return true;
    }

    /**
     * 解析 Chart中 面积阴影部分上下边界的每一对 对应点 的对应的属性
     * @param chart 给定Chart
     * @param pathInfo 有效路径对象信息
     * @param scaleNumberInfo 刻度信息量化对象
     * @return 如果解析成功　则返回　true
     */
    public static boolean parserAreaInfo(
            Chart chart,
            ChartPathInfo pathInfo,
            ChartScaleQuantificationInfo scaleNumberInfo) {
        // 计算给定面积Path上下边界对应点对　的位置信息
        List<Double> xs = new ArrayList<>();      // 对应点的X坐标值集
        List<Double> ysU = new ArrayList<>();     // 对应点上侧的Y坐标值集
        List<Double> ysD = new ArrayList<>();     // 对应点下侧的X坐标值集
        boolean bGetPts = getPathAreaPoints(pathInfo.path, chart.hAxis, xs, ysU, ysD);
        if (!bGetPts) {
            return false;
        }

        List<Double> valuesU = new ArrayList<>();  // Path上顶点对应的数字序列
        List<Double> valuesD = new ArrayList<>();  // Path下顶点对应的数字序列
        List<String> labels = new ArrayList<>();   // Path顶点对应的标称序列
        boolean bInterpolationU = false, bInterpolationD = false;
        // 解析对于左侧的刻度属性 面积图左右侧一般为数字类属性
        if (!chart.vAxisTextL.isEmpty() && !pathInfo.matchRightScale()) {
            bInterpolationU = interpolationNumbers(ysU, scaleNumberInfo.infos[0], valuesU);
            bInterpolationD = interpolationNumbers(ysD, scaleNumberInfo.infos[0], valuesD);
            if ((!bInterpolationU || !bInterpolationD) && pathInfo.scaleTypeY != ScaleInfoType.UNKNOWN_SCALE ) {
                return false;
            }
            setPathInfoValues(valuesU, ScaleInfoType.NUMBER_SCALE, 0, false, bInterpolationU, pathInfo);
            setAreaThreshold(pathInfo, valuesU, valuesD);
        } // end if left axis
        // 解析面积区域对应的右侧刻度属性信息
        if (!chart.vAxisTextR.isEmpty() &&
                (pathInfo.matchRightScale() || chart.vAxisTextL.isEmpty())) {
            bInterpolationU = interpolationNumbers(ysU, scaleNumberInfo.infos[1], valuesU);
            bInterpolationD = interpolationNumbers(ysD, scaleNumberInfo.infos[1], valuesD);
            if ((!bInterpolationU || !bInterpolationD) && pathInfo.scaleTypeY != ScaleInfoType.UNKNOWN_SCALE ) {
                return false;
            }
            setPathInfoValues(valuesU, ScaleInfoType.NUMBER_SCALE, 1, false, bInterpolationU, pathInfo);
            setAreaThreshold(pathInfo, valuesU, valuesD);
        } // end if right axis

        // 解析对于下侧的刻度属性 面积图上下侧一般为时间类属性
        boolean bInterpolation = false;
        if (!chart.hAxisTextD.isEmpty()) {
            bInterpolation = interpolationDates(xs, scaleNumberInfo.infos[2], labels, false);
            if (!bInterpolation && pathInfo.scaleTypeX != ScaleInfoType.UNKNOWN_SCALE) {
                return false;
            }
            setPathInfoValues(labels, ScaleInfoType.TIME_SCALE, 2, true, bInterpolation, pathInfo);
        }
        // 解析对于上侧的刻度属性
        if (!chart.hAxisTextU.isEmpty()) {
            bInterpolation = interpolationDates(xs, scaleNumberInfo.infos[3], labels, false);
            if (!bInterpolation && pathInfo.scaleTypeX != ScaleInfoType.UNKNOWN_SCALE) {
                return false;
            }
            setPathInfoValues(labels, ScaleInfoType.TIME_SCALE, 3, true, bInterpolation, pathInfo);
        }
        // 设置没有解析出来刻度属性的默认刻度属性
        setDefaultScale(chart.hAxis, pathInfo, true, xs, ysU);
        return true;
    }



    /**
     * 解析 Chart中的 垂直矩形高度 或 水平矩形宽度  对应的刻度属性
     * @param chart 给定Chart
     * @param pathInfo 有效路径对象信息
     * @param scaleNumberInfo 刻度信息量化对象
     * @return 如果解析成功　则返回　true
     */
    public static boolean parserColumnInfo(
            Chart chart,
            ChartPathInfo pathInfo,
            ChartScaleQuantificationInfo scaleNumberInfo) {
        List<Double> cxs = new ArrayList<>();    // 矩形对应的刻度属性的X坐标
        List<Double> cys = new ArrayList<>();    // 矩形对应的刻度属性的Y坐标
        boolean bVertical = pathInfo.type == PathInfo.PathType.BAR; // 判断是为垂直方向或水平方向的柱状图
        GeneralPath sortedPath = reSortColumn(pathInfo.path, bVertical);
        pathInfo.path = (GeneralPath) sortedPath.clone();


        // 垂直柱状图取水平坐标轴  水平柱状图取垂直坐标轴
        Line2D axis = selectAxis(chart, bVertical);

        // 抽取给定 柱状图 中矩形的属性标识位置信息集
        boolean bGetPts = getPathColumnPoints(pathInfo.path, bVertical, chart.getArea(), axis, cxs, cys);
        if (!bGetPts) {
            return false;
        }

        boolean bInterpolation = false;
        // 解析矩形对应的左侧刻度属性信息
        if (!chart.vAxisTextL.isEmpty() && !pathInfo.matchRightScale()) {
            bInterpolation = parserBarLeftRightInfo(pathInfo, 0, bVertical, cys, scaleNumberInfo);
            if (!bInterpolation && pathInfo.scaleTypeY != ScaleInfoType.UNKNOWN_SCALE) {
                return false;
            }
        } // end if left axis
        // 解析矩形对应的右侧刻度属性信息
        if (!chart.vAxisTextR.isEmpty() &&
                (pathInfo.matchRightScale() || chart.vAxisTextL.isEmpty())) {
            bInterpolation = parserBarLeftRightInfo(pathInfo, 1, bVertical, cys, scaleNumberInfo);
            if (!bInterpolation && pathInfo.scaleTypeY != ScaleInfoType.UNKNOWN_SCALE) {
                return false;
            }
        } // end if right axis

        // 解析矩形对应的下侧刻度属性信息
        if (!chart.hAxisTextD.isEmpty()) {
            bInterpolation = parserBarUpDownInfo(pathInfo, 2, bVertical, cxs, scaleNumberInfo);
            if (!bInterpolation && pathInfo.scaleTypeX != ScaleInfoType.UNKNOWN_SCALE) {
                return false;
            }
        } // end if down axis
        // 解析矩形对应的上侧刻度属性信息
        if (!chart.hAxisTextU.isEmpty()) {
            bInterpolation = parserBarUpDownInfo(pathInfo, 3, bVertical, cxs, scaleNumberInfo);
            if (!bInterpolation && pathInfo.scaleTypeX != ScaleInfoType.UNKNOWN_SCALE) {
                return false;
            }
        } // end if up axis

        // 从Chart内部信息集中　尝试寻找矩形最合适的属性值
        parserBarChunksRemain(chart, pathInfo);

        // bywang, 柱状图的刻度优化：处理左侧刻度丢失掉"%"的情况,如果解析出来的刻度值带%，但是左侧刻度
        if (chart.type == ChartType.BAR_CHART && pathInfo.valuesY.size() > 0) {
            double[] num = {0.0};
            String[] suffix = {""};
            boolean parsed = string2Double(pathInfo.valuesY.get(0), num, suffix);
            if (parsed && suffix[0].equals("%") && scaleNumberInfo.infos[pathInfo.ithSideY].suffix[0].isEmpty()) {
                scaleNumberInfo.infos[pathInfo.ithSideY].suffix[0] = "%";
            }
        }

        // 设置没有解析出来刻度属性的默认刻度属性
        if (chart.type == ChartType.COLUMN_CHART) {
            setDefaultScale(chart.lvAxis != null ? chart.lvAxis : chart.rvAxis, pathInfo, true, cxs, cys);
        }
        else {
            setDefaultScale(chart.hAxis, pathInfo, true, cxs, cys);
        }

        double left = Collections.min(cxs);
        double right = Collections.max(cxs);
        double top = Collections.min(cys);
        double bottom = Collections.max(cys);
        pathInfo.bound = new Rectangle2D.Double(left, top, right - left, bottom - top);
        return true;
    }


    public static Line2D selectAxis(Chart chart, boolean bVertical){
        Line2D axis = bVertical ? chart.hAxis : (chart.lvAxis != null ? chart.lvAxis : chart.rvAxis);
        if ((bVertical && !ChartPathInfosParser.isChartStackedBar(chart))|| axis == null){
            if (chart.hAxisLogic != null) {
                double minX = chart.hAxisLogic.getX1();
                double maxX = chart.hAxisLogic.getX2();
                if (axis != null) {
                    // 如果两个axis差距不大(intersects) 则用axis约束逻辑坐标轴
                    minX = Math.max(minX, axis.getX1());
                    maxX = Math.min(maxX, axis.getX2());
                }

                minX = Math.max(minX, chart.getLeft());
                maxX = Math.min(maxX, chart.getRight());
                double hAxisLogicY1 = chart.hAxisLogic.getY1();
                double hAxisLogicY2 = chart.hAxisLogic.getY2();

                chart.hAxisLogic.setLine(minX, hAxisLogicY1, maxX, hAxisLogicY2);
                axis = chart.hAxisLogic;
            }
        }

        return axis;
    }

    /**
     * 解析柱状图上矩形对应左右侧刻度属性
     * @param pathInfo
     * @param ithSide
     * @param bVertical
     * @param cys
     * @param scaleNumberInfo
     * @return
     */
    public static boolean parserBarLeftRightInfo(
            ChartPathInfo pathInfo,
            int ithSide,
            boolean bVertical,
            List<Double> cys,
            ChartScaleQuantificationInfo scaleNumberInfo) {
        boolean bInterpolation = true;
        if (bVertical) {
            List<Double> values = new ArrayList<>();  // 对应的数字序列
            bInterpolation = interpolationNumbers(cys, scaleNumberInfo.infos[ithSide], values);
            if (bInterpolation) {
                setPathInfoValues(values, ScaleInfoType.NUMBER_SCALE, ithSide, false, bInterpolation, pathInfo);
            }
        }
        else {
            List<String> labels = new ArrayList<>();  // 对应的标称序列
            bInterpolation = interpolationLabels(cys, scaleNumberInfo.infos[ithSide], labels);
            if (bInterpolation) {
                setPathInfoValues(labels, ScaleInfoType.LABEL_SCALE, ithSide, false, bInterpolation, pathInfo);
            }
        }
        return bInterpolation;
    }

    /**
     * 解析柱状图上矩形对应上下侧刻度属性
     * @param pathInfo
     * @param ithSide
     * @param bVertical
     * @param cxs
     * @param scaleNumberInfo
     * @return
     */
    public static boolean parserBarUpDownInfo(
            ChartPathInfo pathInfo,
            int ithSide,
            boolean bVertical,
            List<Double> cxs,
            ChartScaleQuantificationInfo scaleNumberInfo) {
        // 解析矩形对应的上侧 或 下侧刻度属性信息
        boolean bInterpolation = true;
        ScaleQuantification scaleQua = scaleNumberInfo.infos[ithSide];
        List<String> infos = scaleQua.infos;
        if (bVertical) {
            List<String> labels = new ArrayList<>();  // 对应的标称序列
            //if (cxs.size() <= infos.size()) {
            if (!pathInfo.bSubdivideX) {
                bInterpolation = interpolationLabels(cxs, scaleQua, labels);
                if (bInterpolation) {
                    setPathInfoValues(labels, ScaleInfoType.LABEL_SCALE, ithSide, true, bInterpolation, pathInfo);
                }
            }
            else {
                bInterpolation = interpolationDates(cxs, scaleQua, labels, false);
                if (bInterpolation) {
                    setPathInfoValues(labels, ScaleInfoType.TIME_SCALE, ithSide, true, bInterpolation, pathInfo);
                }
            }
        }
        else {
            List<Double> values = new ArrayList<>();  // 对应的数字序列
            bInterpolation = interpolationNumbers(cxs, scaleQua, values);
            if (bInterpolation) {
                setPathInfoValues(values, ScaleInfoType.NUMBER_SCALE, ithSide, true, bInterpolation, pathInfo);
            }
        }
        return bInterpolation;
    }

    /**
     * 解析 Chart中 折线对象 的属性信息
     * @param chart 给定Chart
     * @param pathInfo 有效路径对象信息
     * @param scaleNumberInfo 刻度信息量化对象
     * @return 如果解析成功　则返回　true
     */
    public static boolean parserLineInfo(
            Chart chart,
            ChartPathInfo pathInfo,
            ChartScaleQuantificationInfo scaleNumberInfo) {
        // 找出Line Path 上的点集的X Y 坐标信息
        List<Double> xs = new ArrayList<>();     // 折线顶点X坐标集
        List<Double> ys = new ArrayList<>();     // 折线顶点Y坐标集
        boolean bGetPts = getLinePathPoints(pathInfo.path, chart.hAxis, xs, ys);
        if (!bGetPts) {
            return false;
        }

        // 解析折线对应于左侧刻度信息
        List<Double> values = new ArrayList<>();  // Path上顶点对应的左侧属性序列
        boolean bInterpolation = false;
        if (!chart.vAxisTextL.isEmpty() && !pathInfo.matchRightScale()) {
            bInterpolation = parserLineLeftRightInfo(pathInfo, 0, ys, scaleNumberInfo);
            if (!bInterpolation && pathInfo.scaleTypeY != ScaleInfoType.UNKNOWN_SCALE) {
                return false;
            }
        } // end if left axis
        // 解析折线对应于右侧刻度信息
        if (!chart.vAxisTextR.isEmpty() &&
                (pathInfo.matchRightScale() || chart.vAxisTextL.isEmpty())) {
            bInterpolation = parserLineLeftRightInfo(pathInfo, 1, ys, scaleNumberInfo);
            if (!bInterpolation && pathInfo.scaleTypeY != ScaleInfoType.UNKNOWN_SCALE) {
                return false;
            }
        } // end if right axis

        // 解析折线对应于下侧刻度信息
        if (!chart.hAxisTextD.isEmpty()) {
            bInterpolation = parserLineUpDownInfo(pathInfo, 2, xs, scaleNumberInfo);
            if (!bInterpolation && pathInfo.scaleTypeX != ScaleInfoType.UNKNOWN_SCALE) {
                return false;
            }
        }
        // 解析折线对应于上侧刻度信息
        if (!chart.hAxisTextU.isEmpty()) {
            bInterpolation = parserLineUpDownInfo(pathInfo, 3, xs, scaleNumberInfo);
            if (!bInterpolation && pathInfo.scaleTypeX != ScaleInfoType.UNKNOWN_SCALE) {
                return false;
            }
        }

        // 从Chart内部没有标记的信息集中　尝试解析 开发中
        parserChunksRemain(chart, pathInfo, xs, ys);

        // 设置没有解析出来刻度属性的默认刻度属性
        setDefaultScale(chart.hAxis, pathInfo, true, xs, ys);

        double left = Collections.min(xs);
        double right = Collections.max(xs);
        double top = Collections.min(ys);
        double bottom = Collections.max(ys);
        pathInfo.bound = new Rectangle2D.Double(left, top, right - left, bottom - top);
        return true;
    }

    /**
     * 如果给定PathInfo对应的X轴属性或Y轴属性没有解析出来　则用其点的相应空间坐标属性代替
     * 比如现阶段　斜着的刻度属性还没有用OCR的方式解析出来
     * @param axis
     * @param pathInfo
     * @param bSet
     * @param xs
     * @param ys
     */
    public static void setDefaultScale(
            Line2D axis,
            ChartPathInfo pathInfo,
            boolean bSet,
            List<Double> xs,
            List<Double> ys) {
        boolean isColumn = pathInfo.type == PathInfo.PathType.COLUMNAR; // 判断是为垂直方向或水平方向的柱状图
        if (pathInfo.scaleTypeX == ScaleInfoType.UNKNOWN_SCALE) {
            if (axis != null) {
                listObjSubNumber(xs, axis.getX1());
            }
            if (isColumn) {
                for (int i = 0; i < xs.size(); i++) {
                    xs.set(i, -1.0 * xs.get(i));
                }
            }
            setPathInfoValues(xs, ScaleInfoType.NUMBER_SCALE, 2, true, bSet, true, pathInfo);
        }
        if (pathInfo.scaleTypeY == ScaleInfoType.UNKNOWN_SCALE) {
            if (axis != null) {
                listObjSubNumber(ys, axis.getY1());
            }
            if (!isColumn) {
                for (int i = 0; i < ys.size(); i++) {
                    if (axis != null) {
                        ys.set(i, -1.0 * ys.get(i));
                    }
                }
            }
            setPathInfoValues(ys, ScaleInfoType.NUMBER_SCALE, 0, false, bSet, true, pathInfo);
        }
    }

    /**
     * 给定矩形的水平 或 垂直 边界值 和 坐标轴位置信息  找出合适的表示矩形属性值的位置点 (用来就近寻找属性值用)
     * @param xmin
     * @param xmax
     * @param bAxis
     * @param xaxis
     * @return
     */
    public static double getColumnValuePosition(
            double xmin, double xmax, boolean isVertical, boolean bAxis, double xaxis) {
        if (!bAxis) {
            if (isVertical) {
                return xmin;
            }
            else {
                return xmax;
            }
            //return xmax;
        }
        if (xmin >= xaxis - 2.0) {
            return xmax;
        }
        else if (xmax <= xaxis + 2.0) {
            return xmin;
        }
        return xmax;
    }

    /**
     * 尝试匹配矩形元素与附近的属性值
     * @param chart
     * @param pathInfo
     */
    public static void parserBarChunksRemain(Chart chart, ChartPathInfo pathInfo) {
        boolean vertical = true;
        boolean bAxis = false;
        double axis = 0.0;
        // 判断内部矩形元素是否垂直排列 找出坐标轴信息
        if (pathInfo.type == PathInfo.PathType.BAR) {
            vertical = true;
            if (chart.hAxis != null) {
                bAxis = true;
                axis = chart.hAxis.getY1();
            }
        }
        else if (pathInfo.type == PathInfo.PathType.COLUMNAR) {
            vertical = false;
            if (chart.lvAxis != null) {
                bAxis = true;
                axis = chart.lvAxis.getX1();
            }
            else if (chart.rvAxis != null) {
                bAxis = true;
                axis = chart.rvAxis.getX1();
            }
        }
        else {
            return;
        }

        // 遍历PathInfo内部的矩形集 找出标示属性值的位置信息
        List<Double> xs = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
        List<Double> xsmid = new ArrayList<>();
        List<Double> ysmid = new ArrayList<>();
        List<Double> yspos = new ArrayList<>();
        List<Double> xspos = new ArrayList<>();
        double xmin = 0.0, xmax = 0.0, ymin = 0.0, ymax = 0.0, ypos = 0.0, xpos = 0.0;

        ChartPathInfosParser.getPathColumnBox(pathInfo.path, xs, ys);
        int nBox = xs.size() / 2;
        for (int k = 0; k < nBox; k++) {
            xmin = xs.get(2 * k);
            xmax = xs.get(2 * k + 1);
            ymin = ys.get(2 * k);
            ymax = ys.get(2 * k + 1);
            xsmid.add(0.5 * (xmin + xmax));
            ysmid.add(0.5 * (ymin + ymax));
            if (vertical) {
                ypos = getColumnValuePosition(ymin, ymax, vertical, bAxis, axis);
                yspos.add(ypos);
            }
            else {
                xpos = getColumnValuePosition(xmin, xmax, vertical, bAxis, axis);
                xspos.add(xpos);
            }
        } // end for k

        // 如果是堆叠柱状矩形 则优先在矩形中心点附件找最近值
        boolean bStackedBar = ChartPathInfosParser.isChartStackedBar(chart);
        boolean bParser = false;
        if (bStackedBar) {
            bParser = parserChunksRemain(chart, pathInfo, xsmid, ysmid);
        }
        // 如果是垂直排列柱状图 则优先在矩形的标示属性值的位置处找最近值
        if (vertical) {
            bParser = bParser || parserChunksRemain(chart, pathInfo, xsmid, yspos);
            // 如果以上策略没有找到 则尝试中心点
            if (chart.type != ChartType.COMBO_CHART) {
                bParser = bParser || parserChunksRemain(chart, pathInfo, xsmid, ysmid);
            }
        }
        else {
            bParser = bParser || parserChunksRemain(chart, pathInfo, xspos, ysmid) ||
                      parserChunksRemain(chart, pathInfo, xsmid, ysmid);
        }
    }

    private static boolean parserLineTwoEndPointValue(
            Chart chart,
            ChartPathInfo pathInfo,
            List<Double> xs,
            List<Double> ys,
            boolean findEndPt) {
        List<Double> endPtXs = new ArrayList<>();
        List<Double> endPtYs = new ArrayList<>();
        int n = xs.size();
        if (findEndPt) {
            endPtXs.add(xs.get(n - 1));
            endPtYs.add(ys.get(n - 1));
        }
        else {
            endPtXs.add(xs.get(0));
            endPtYs.add(ys.get(0));
        }

        PtValuePositionType posType = PtValuePositionType.NEAREST_POSITION;
        List<String> infos = new ArrayList<>();
        List<TextChunk> chunks = chart.chunksRemain;
        boolean bParser = parserPtsChunksRemain(chunks, endPtXs, endPtYs, posType, infos);
        if (bParser) {
            for (int i = 1; i < n; i++) {
                infos.add(infos.get(0));
            }
            updatePathInfoPtValue(chart, pathInfo, infos);
        }
        return bParser;
    }

    /**
     * 解析Chart内部没有标记的信息
     * @param chart
     * @param pathInfo
     * @param xs
     * @param ys
     */
    public static boolean parserChunksRemain(
            Chart chart,
            ChartPathInfo pathInfo,
            List<Double> xs,
            List<Double> ys) {
        // 面积图不处理 直接返回
        if( chart.type == ChartType.AREA_CHART ||
                chart.type == ChartType.AREA_OVERLAP_CHART) {
            return false;
        }
        //else if (pathInfo.type == PathInfo.PathType.AREA || pathInfo.type == PathInfo.PathType.CURVE) {
        else if (pathInfo.type == PathInfo.PathType.AREA) {
            return false;
        }

        // 判断是否为水平直线 比如虚线或长直线 此时一般只需要 找出两端点的最近属性 即可
        if (pathInfo.type == PathInfo.PathType.LINE && !ys.isEmpty()) {
            double yStart = ys.get(0);
            if (ys.stream().allMatch(y -> Math.abs(y - yStart) < ChartUtils.DELTA)) {
                boolean parser = parserLineTwoEndPointValue(chart, pathInfo, xs, ys, true) ||
                        parserLineTwoEndPointValue(chart, pathInfo, xs, ys, false);
                return parser;
            }
        }

        List<TextChunk> chunks = chart.chunksRemain;
        int nc = chunks.size();
        int npts = xs.size();
        // 如果数目少于点数　则跳过 (此时很有可能是标记少数对象属性值)
        if (nc == 0 || nc < npts) {
            return false;
        }

        // 对折线　柱状图　区分处理
        boolean bParser = false;
        PtValuePositionType posType = PtValuePositionType.NEAREST_POSITION;
        List<String> infos = new ArrayList<>();
        if (pathInfo.type == PathInfo.PathType.LINE ||
                pathInfo.type == PathInfo.PathType.CURVE) {
            bParser = parserPtsChunksRemain(chunks, xs, ys, posType, infos);
            if (bParser && pathInfo.valuesY.size() == infos.size() && !isValidExtractAndLabelValues(pathInfo.valuesY, infos)) {
                return false;
            }
        }
        else if (pathInfo.type == PathInfo.PathType.BAR) {
            posType = PtValuePositionType.X_CENTER_POSITION;
            bParser = parserPtsChunksRemain(chunks, xs, ys, posType, infos);
            if (bParser && pathInfo.valuesY.size() == infos.size() && !isValidExtractAndLabelValues(pathInfo.valuesY, infos)) {
                return false;
            }
        }
        else if (pathInfo.type == PathInfo.PathType.COLUMNAR) {
            posType = PtValuePositionType.Y_CENTER_POSITION;
            bParser = parserPtsChunksRemain(chunks, xs, ys, posType, infos);
            if (bParser && pathInfo.valuesX.size() == infos.size() && !isValidExtractAndLabelValues(pathInfo.valuesX, infos)) {
                return false;
            }
        }

        // 如果解析成功 则分别设置X属性或Y属性
        if (bParser) {
            updatePathInfoPtValue(chart, pathInfo, infos);
        }
        return bParser;
    }

    /**
     * 判断插值属性和匹配的标记属性是否大致一致
     * @param values
     * @param labels
     * @return
     */
    private static boolean isValidExtractAndLabelValues(
            List<String> values, List<String> labels) {
        List<Double> ys1 = new ArrayList<>();
        List<Double> ys2 = new ArrayList<>();
        double [] num = {0.0};
        String [] suffix = {""};
        double extractValue = 0;
        boolean parsed = false, bigThan1 = true, bigThan2 = true;
        for (int i = 0; i < labels.size(); i++) {
            try {
                extractValue = Double.parseDouble(values.get(i));
            }
            catch (NumberFormatException e) {
                continue;
            }
            parsed = string2Double(labels.get(i), num, suffix);
            if (!parsed) {
                continue;
            }
            ys1.add(extractValue);
            ys2.add(num[0]);
            if (Math.abs(num[0] - extractValue) > 5.0 * Math.abs(extractValue)) {
                return false;
            }
            int n = ys1.size();
            if (n >= 2) {
                bigThan1 = ys1.get(n - 1) - ys1.get(n - 2) >= 1E-1 * Math.abs(ys1.get(n - 2));
                bigThan2 = ys2.get(n - 1) - ys2.get(n - 2) >= 1E-1 * Math.abs(ys2.get(n - 2));
                if (bigThan1 != bigThan2) {
                    return false;
                }
            }
        } // end for i

        return true;
    }

    private static void updatePathInfoPtValue(
            Chart chart, ChartPathInfo pathInfo, List<String> infos) {
        if (chart.type == ChartType.COLUMN_CHART) {
            pathInfo.valuesX = infos;
            if (pathInfo.scaleTypeX == ScaleInfoType.UNKNOWN_SCALE) {
                pathInfo.ithSideX = 2;
            }
            pathInfo.scaleTypeX = ScaleInfoType.NUMBER_SCALE;
        }
        else {
            pathInfo.valuesY = infos;
            if (pathInfo.scaleTypeY == ScaleInfoType.UNKNOWN_SCALE) {
                pathInfo.ithSideY = 0;
            }
            else {
                boolean left = isTwoStringsSimilar(chart.vAxisTextL, pathInfo.valuesY);
                boolean right = isTwoStringsSimilar(chart.vAxisTextR, pathInfo.valuesY);
                if (left && !right) {
                    pathInfo.ithSideY = 0;
                }
                else if (!left && right) {
                    pathInfo.ithSideY = 1;
                }
                else if (left && right) {
                    if (pathInfo.type == PathInfo.PathType.LINE) {
                        pathInfo.ithSideY = 1;
                    }
                }
            }
            pathInfo.scaleTypeY = ScaleInfoType.NUMBER_SCALE;
        }
        // 设置数据来源与附近标记的文字信息
        pathInfo.valueFromNearbyTextInfo = true;
    }


    /**
     * 判断给定String集 是否在后缀 范围上 接近
     * @param strsA
     * @param strsB
     * @return
     */
    public static boolean isTwoStringsSimilar(List<String> strsA, List<String> strsB) {
        if (strsA == null || strsA.isEmpty() || strsB == null || strsB.isEmpty()) {
            return false;
        }
        double [] numAfirst = {0.0};
        double [] numAend = {0.0};
        double [] numB = {0.0};
        String [] suffixA = {""};
        String [] suffixB = {""};
        int n = strsA.size();
        // 判断是否为数字类型
        if (!ChartContentScaleParser.string2Double(strsA.get(0), numAfirst, suffixA) ||
                !ChartContentScaleParser.string2Double(strsA.get(n - 1), numAend, suffixA) ||
                !ChartContentScaleParser.string2Double(strsB.get(0), numB, suffixB)) {
            return false;
        }
        // 判断后缀是否相同
        if (!suffixA[0].equals(suffixB[0])) {
            return false;
        }
        // 判断范围上师傅接近
        double numMax = Math.max(Math.abs(numAfirst[0]), Math.abs(numAend[0]));
        for (String str : strsB) {
            if (!ChartContentScaleParser.string2Double(str, numB, suffixB)) {
                return false;
            }
            if (Math.abs(numB[0]) > 1.2 * numMax) {
                return false;
            }
        }
        return true;
    }

    /**
     * 过滤掉非数值类的TextChunk
     * @param chunks
     */
    public static void removeNonNumberChunks(List<TextChunk> chunks) {
        double [] num = {0.0};
        String [] suffix = {""};
        final Iterator<TextChunk> each = chunks.iterator();
        while (each.hasNext()) {
            TextChunk chunk = each.next();
            String info = chunk.getText();
            if (!string2Double(info, num, suffix)) {
                each.remove();
            }
        } // end while
    }

    /**
     * 根据最近邻准则 和 给定寻找位置类型　找出顶点最合适的TextChunk
     * @param chunks
     * @param xs
     * @param ys
     * @param posType
     * @param infos
     * @return
     */
    public static boolean parserPtsChunksRemain(
            List<TextChunk> chunks,
            List<Double> xs,
            List<Double> ys,
            PtValuePositionType posType,
            List<String> infos) {
        infos.clear();
        int npts = xs.size();
        int[] ith = {-1};
        double x = 0.0, y = 0.0;
        double [] num = {0.0};
        String info = "";
        String [] suffix = {""};
        Set<Integer> ithStatus = new HashSet<>();
        Set<String> suffixs = new HashSet<>();
        for (int i = 0; i < npts; i++) {
            x = xs.get(i);
            y = ys.get(i);
            if (!getMinDistanceChunk(chunks, x, y, posType, ithStatus, ith)) {
                return false;
            }
            ithStatus.add(ith[0]);
            if (ith[0] >= 0) {
                info = chunks.get(ith[0]).getText();
                infos.add(info);
            } else {
                return false;
            }

            if (!ChartContentScaleParser.string2Double(info, num, suffix)) {
                return false;
            }
            suffixs.add(suffix[0]);
        } // end for i
        if (suffixs.size() >= 2) {
            return false;
        }
        for (Integer iChunk : ithStatus) {
            ChunkUtil.setChunkType(chunks.get(iChunk), ChunkStatusType.PATH_VALUE_TYPE);
        }
        return true;
    }

    /**
     * 找出点与给定Chunk集距离最近的Chunk
     * @param chunks
     * @param x
     * @param y
     * @param posType
     * @param ithStatus
     * @param ith
     * @return
     */
    public static boolean getMinDistanceChunk(
            List<TextChunk> chunks,
            double x,
            double y,
            PtValuePositionType posType,
            Set<Integer> ithStatus,
            int [] ith) {
        // 判断给定chunks是否为空
        ith[0] = -1;
        int n = chunks.size();
        if (n == 0) {
            return false;
        }
        // 遍历　找出距离最近的TextChunk
        double dxy = Math.min(chunks.get(0).getWidth(), chunks.get(0).getHeight());
        double distmin = 0.0;
        for (int i = 0; i < n; i++) {
            TextChunk chunk = chunks.get(i);
            // 如果是已经标记过的类型 则跳过
            if (ChunkUtil.getChunkType(chunk) == ChunkStatusType.PATH_VALUE_TYPE) {
                continue;
            }
            // 已经在前面的点的匹配中使用过 则跳过
            if (ithStatus.contains(i)) {
                continue;
            }
            // 根据指定位置类型 过滤掉部分 Chunk  目前支持 X 居中 或 Y 居中  后续可能继续添加
            if (posType == PtValuePositionType.X_CENTER_POSITION) {
                if (Math.abs(chunk.getCenterX() - x) > dxy) {
                    continue;
                }
            }
            else if (posType == PtValuePositionType.Y_CENTER_POSITION) {
                if (Math.abs(chunk.getCenterY() - y) > dxy) {
                    continue;
                }
            }
            double dist = getDistanceFromChunkToPt(chunk, x, y);
            if (dist > 3.0 * dxy) {
                continue;
            }
            if (ith[0] == -1 || distmin > dist) {
                distmin = dist;
                ith[0] = i;
            }
        } // end for i
        return (ith[0] == -1) ? false : true;
    }

    /**
     * 计算Chunk与点的近似距离
     * @param chunk
     * @param x
     * @param y
     * @return
     */
    public static double getDistanceFromChunkToPt(
            TextChunk chunk, double x, double y) {
        double xmin = chunk.getBounds2D().getMinX();
        double xmax = chunk.getBounds2D().getMaxX();
        double ymin = chunk.getBounds2D().getMinY();
        double ymax = chunk.getBounds2D().getMaxY();
        double xmid = 0.5 * (xmin + xmax);
        double [] pts = {
                xmin, ymin, xmid, ymin, xmax, ymin,
                xmin, ymax, xmid, ymax, xmax, ymax,
                xmid, 0.5 * (ymax + ymin)};
        double dist = Math.abs(pts[0] - x) + Math.abs(pts[1] - y);
        double distmin = dist;
        for (int i = 1; i < 7; i++) {
            dist = Math.abs(pts[2 * i] - x) + Math.abs(pts[2 * i + 1] - y);
            distmin = distmin > dist ? dist : distmin;
        }
        return distmin;
    }

    /**
     * 将给定数组的每一个内部元素都减去一个数
     * @param xs
     * @param sub
     */
    public static void listObjSubNumber(List<Double> xs, double sub) {
        for (int i = 0; i < xs.size(); i++) {
            double x = xs.get(i);
            xs.set(i, x - sub);
        }
    }

    /**
     * 解析折线图对应上下侧刻度属性
     * @param pathInfo
     * @param ithSide
     * @param cys
     * @param scaleNumberInfo
     * @return
     */
    public static boolean parserLineLeftRightInfo(
            ChartPathInfo pathInfo,
            int ithSide,
            List<Double> cys,
            ChartScaleQuantificationInfo scaleNumberInfo) {
        List<Double> values = new ArrayList<>();  // Path上顶点对应的左右侧属性序列
        boolean bInterpolation = interpolationNumbers(cys, scaleNumberInfo.infos[ithSide], values);
        if (bInterpolation) {
            setPathInfoValues(values, ScaleInfoType.NUMBER_SCALE, ithSide, false, bInterpolation, pathInfo);
        }
        return bInterpolation;
    }

    /**
     * 解析折线图对应上下侧刻度属性
     * @param pathInfo
     * @param ithSide
     * @param cxs
     * @param scaleNumberInfo
     * @return
     */
    public static boolean parserLineUpDownInfo(
            ChartPathInfo pathInfo,
            int ithSide,
            List<Double> cxs,
            ChartScaleQuantificationInfo scaleNumberInfo) {
        // 如果只需匹配正对刻度的图形元素　则直接返回
        if (pathInfo.onlyMatchXScale) {
            return true;
        }

        // 如果待插值点个数少于参考序列个数
        // 则通常参考信息为标称类型　比如时间标称　此时用最近邻插值  不需要精细插值
        // 大部分柱状图都满足这种规律 部分折线也满足
        boolean bNotSubdivision = false;
        if (cxs.size() <= scaleNumberInfo.infos[ithSide].infos.size()) { // 标称类刻度属性
            bNotSubdivision = true;
        }
        boolean bInterpolation = true;
        // 如果X轴是　数值类型
        if (scaleNumberInfo.infos[ithSide].type == ScaleInfoType.NUMBER_SCALE) {
            List<Double> values = new ArrayList<>();  // Path上顶点对应的标签类信息
            bInterpolation = interpolationNumbers(cxs, scaleNumberInfo.infos[ithSide], values);
            if (bInterpolation) {
                ScaleInfoType type = ScaleInfoType.NUMBER_SCALE;
                setPathInfoValues(values, type, ithSide, true, bInterpolation, pathInfo);
            }
        }
        else {
            List<String> labels = new ArrayList<>();  // Path上顶点对应的标签类信息
            bNotSubdivision = !pathInfo.bSubdivideX;
            bInterpolation = interpolationDates(cxs, scaleNumberInfo.infos[ithSide], labels, bNotSubdivision);
            if (bInterpolation) {
                ScaleInfoType type = ScaleInfoType.TIME_SCALE;
                if (bNotSubdivision) {
                    type = ScaleInfoType.LABEL_SCALE;
                }
                setPathInfoValues(labels, type, ithSide, true, bInterpolation, pathInfo);
            }
        }
        return  bInterpolation;
    }

    public static void setAreaThreshold(ChartPathInfo pathInfo, List<Double> valuesU, List<Double> valuesD) {
        if (valuesD.size() != valuesD.size() || valuesD.isEmpty()) {
            return;
        }
        double threshold = valuesD.get(0);
        boolean hasThreshold = valuesD.stream().filter(x -> Math.abs(x - threshold) >= 0.01).count() == 0;
        if (!hasThreshold) {
            return;
        }
        long numDown = IntStream.range(0, valuesU.size()).filter(i -> valuesU.get(i) < valuesD.get(i) - 0.01).count();
        if (numDown >= 1) {
            pathInfo.areaThreshold = String.valueOf(valuesD.get(0));
        }
    }

    /**
     * 设置PathInfo对应的刻度属性
     * @param objs
     * @param type
     * @param ithSide
     * @param bAxisX
     * @param bSet
     * @param pathInfo
     * @param <T>
     */
    public static <T> void setPathInfoValues(
            List<T> objs,
            ScaleInfoType type,
            int ithSide,
            boolean bAxisX,
            boolean bSet,
            ChartPathInfo pathInfo) {
        setPathInfoValues(objs, type, ithSide, bAxisX, bSet, false, pathInfo);
    }

    /**
     * 设置PathInfo对应的刻度属性
     * @param objs
     * @param type
     * @param ithSide
     * @param bAxisX
     * @param bSet
     * @param bCoordinate
     * @param pathInfo
     * @param <T>
     */
    public static <T> void setPathInfoValues(
            List<T> objs,
            ScaleInfoType type,
            int ithSide,
            boolean bAxisX,
            boolean bSet,
            boolean bCoordinate,
            ChartPathInfo pathInfo) {
        if (!bSet || objs.isEmpty()) {
            return;
        }

        if (bAxisX) {
            pathInfo.valuesX = transforListObj2String(objs);
            pathInfo.scaleTypeX = type;
            pathInfo.ithSideX = ithSide;
        }
        else {
            pathInfo.valuesY = transforListObj2String(objs);
            pathInfo.scaleTypeY = type;
            pathInfo.ithSideY = ithSide;
        }
        pathInfo.valueByCoordinate = bCoordinate;
    }

    /**
     * 将给定数组对象　转换为　字符串数组对象
     * @param objs
     * @param <T>
     * @return
     */
    public static <T> List<String> transforListObj2String(List<T> objs) {
        List<String> strs = new ArrayList<>();
        for (T obj : objs) {
            strs.add(obj.toString());
        }
        return strs;
    }

    public static List<Double> transforListString2Double(List<String> objs) {
        List<Double> nums = new ArrayList<>();
        try {
            for (String obj : objs) {
                nums.add(Double.parseDouble(obj));
            }
        }catch (Exception e) {
        }
        return nums;
    }

    /**
     * 重置柱状对象的方向　　假设含有柱状对象的组合Chart中的柱状对象方向为垂直方向
     * 原先基于柱状对象内部面积最大的矩形的宽高关系判断的方向在某些情况下不适用
     * 在解析图形对象的过程中可能会解析出无效有干扰作用的PathInfo
     * 经过后续多次帅选过滤后, 剩下有效的PathInfo
     * 此时再根据Chart是否为包含其他非柱状对象　判定柱状方向
     * @param chart
     */
    public static void resetColumnDirection(Chart chart) {
        // 过滤掉非组合图
        if (chart.type != ChartType.COMBO_CHART) {
            return;
        }
        // 判断是否有非柱状对象
        boolean has_other = chart.pathInfos.stream().anyMatch(pathInfo -> {
            if (pathInfo.type != PathInfo.PathType.COLUMNAR &&
                    pathInfo.type != PathInfo.PathType.BAR) {
                return true;
            }
            else {
                return false;
            }
        });
        if (!has_other) {
            return;
        }

        // 假设含有柱状对象的组合Chart中的柱状对象方向为垂直方向
        ChartPathInfosParser.setColumnChartDirecton(chart.pathInfos, true);

        // 重置方向后 再次计算逻辑坐标轴的位置
        getLogicAxis(chart);
    }

    /**
     * 对含有柱状的混合图用基于逻辑坐标轴是否对应刻度0值来重置柱状和刻度的对应关系
     * @param chart
     * @param scaleNumberInfo
     */
    public static void baseLogicAxisMatchBarAxis(Chart chart, ChartScaleQuantificationInfo scaleNumberInfo) {
        // 判断有效性
        if (chart.type != ChartType.COMBO_CHART || chart.hAxisLogic == null || chart.pathInfos.isEmpty() ||
                chart.vAxisChunksL.isEmpty() || chart.vAxisChunksR.isEmpty() ||
                scaleNumberInfo.infos[0].numsD.size() <= 1 || scaleNumberInfo.infos[1].numsD.size() <= 1) {
            return;
        }

        // 判断是否有关键字
        boolean noLeftRightKey = true;
        int nLegend = chart.legends.size();
        for (int i = 0; i < nLegend; i++) {
            Chart.Legend legend = chart.legends.get(i);
            if (ChartTitleLegendScaleParser.matchLeftRightAxisKey(legend.text, false) ||
                    ChartTitleLegendScaleParser.matchLeftRightAxisKey(legend.text, true)) {
                noLeftRightKey = false;
                break;
            }
        }
        if (!noLeftRightKey) {
            return;
        }
        // 找出柱状对象对应的刻度侧
        int ithSide = 0;
        for (int i = 0; i < chart.pathInfos.size(); i++) {
            ChartPathInfo pathInfo = chart.pathInfos.get(i);
            if (pathInfo.type == PathInfo.PathType.BAR) {
                ithSide = pathInfo.ithSideY;
                break;
            }
        }
        // 对逻辑坐标轴的Y值做插值计算
        double y = chart.hAxisLogic.getY1();
        List<Double> cys = new ArrayList<>();
        List<Double> values1 = new ArrayList<>();
        List<Double> values2 = new ArrayList<>();
        cys.add(y);
        boolean bInterpolation = interpolationNumbers(cys, scaleNumberInfo.infos[ithSide], values1);
        if (!bInterpolation || values1.isEmpty()) {
            return;
        }
        bInterpolation = interpolationNumbers(cys, scaleNumberInfo.infos[1 - ithSide], values2);
        if (!bInterpolation || values2.isEmpty()) {
            return;
        }

        // 判断是否大致接近于0值
        double dy1 = Math.abs(scaleNumberInfo.infos[ithSide].numsD.get(1) -
                scaleNumberInfo.infos[ithSide].numsD.get(0));
        double dy2 = Math.abs(scaleNumberInfo.infos[1 - ithSide].numsD.get(1) -
                scaleNumberInfo.infos[1 - ithSide].numsD.get(0));
        double value1 = values1.get(0);
        double value2 = values2.get(0);
        boolean validMatch1 = Math.abs(value1) < 0.1 * dy1;
        boolean validMatch2 = Math.abs(value2) < 0.1 * dy2;
        if (validMatch1) {
            return;
        }
        else if (!validMatch1 && !validMatch2){
            return;
        }

        logger.info("reset axis");

        // 重新附上有效刻度侧信息
        for (int i = 0; i < chart.pathInfos.size(); i++) {
            ChartPathInfo pathInfo = chart.pathInfos.get(i);
            if (pathInfo.type == PathInfo.PathType.BAR) {
                pathInfo.ithSideY = 1 - ithSide;
            }
            else {
                pathInfo.ithSideY = ithSide;
            }
        }
    }

    /**
     * 解析给定的Chart内部的有效PathInfo集 即插值对应于左右上下侧的刻度信息
     * @param chart 给定Chart
     * @param scaleNumberInfo Chart四侧刻度信息的量化对象
     * @return 如果所有的信息解析正确　则返回 true
     */
    public static boolean parserChartPathInfos(
            Chart chart,
            ChartScaleQuantificationInfo scaleNumberInfo) {
        // 重置柱状对象的方向
        resetColumnDirection(chart);

        // 对含有柱状的混合图用基于逻辑坐标轴是否对应刻度0值来重置柱状和刻度的对应关系
        baseLogicAxisMatchBarAxis(chart, scaleNumberInfo);

        // 根据Chart内部PathInfos 整体检测判断每一个刻度属性是否需要细分插值  调试中
        judgeChartPathInfosNeedSubdivide(chart, scaleNumberInfo);

        // 微调时间刻度
        fineTurnTimeAxis(chart, scaleNumberInfo);

        // 循环遍历 依次处理Chart内每一个PathInfo 对不同类型做不同的解析方法
        // 目前只处理 折线 柱状 面积 三种类型 后续可能会继续添加新类型解析方法
        for (int i = 0; i < chart.pathInfos.size(); i++) {
            ChartPathInfo pathInfo = chart.pathInfos.get(i);
            boolean bParser = true;
            // 处理折线类型
            if (pathInfo.type == PathInfo.PathType.LINE || pathInfo.type == PathInfo.PathType.CURVE) {
                bParser = parserLineInfo(chart, pathInfo, scaleNumberInfo);
            }
            // 处理柱状类型
            else if (pathInfo.type == PathInfo.PathType.BAR || pathInfo.type == PathInfo.PathType.COLUMNAR) {
                bParser = parserColumnInfo(chart, pathInfo, scaleNumberInfo);
            }
            // 处理面积类型
            else if (pathInfo.type == PathInfo.PathType.AREA) {
                bParser = parserAreaInfo(chart, pathInfo, scaleNumberInfo);
            }

            if (!bParser) {
                pathInfo.type = PathInfo.PathType.UNKNOWN;
                //return false;
            }
        } // end for i

        // 重置找到的最近属性值
        resetFindedAdjacentValue(chart);

        // 基于在柱状对象附近找到的标记属性　推断出其他没有查找出属性的柱状对象
        resetBarValueBaseFindMaskData(chart);

        // 将解析出来的数据转换为HighCharts支持的Json格式
        HighChartWriter writer = new HighChartWriter();
        writer.writeBson(chart, scaleNumberInfo);
        return true;
    }

    public static void resetBarValueBaseFindMaskData(Chart chart) {
        // 找出在附近找到标记属性值的柱状对象
        List<Integer> idsMark = new ArrayList<>();
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < chart.pathInfos.size(); i++) {
            ChartPathInfo pathInfo = chart.pathInfos.get(i);
            if (pathInfo.type == PathInfo.PathType.BAR) {
                if (pathInfo.valueFromNearbyTextInfo) {
                    idsMark.add(i);
                }
                // 统计没有插值出数值属性的
                else if (pathInfo.valueByCoordinate) {
                    ids.add(i);
                }
            }
        } // end for i
        // 数目为0即不需要调整顺序 则跳过
        if (idsMark.size() == 0 || ids.size() == 0) {
            return;
        }
        ChartPathInfo pathInfo = chart.pathInfos.get(idsMark.get(0));
        List<Double> xs = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
        ChartPathInfosParser.getPathColumnBox(pathInfo.path, xs, ys);
        double dy = ys.get(1) - ys.get(0);
        String value = pathInfo.valuesY.get(0);
        double [] num = {0.0};
        String [] suffix = {""};
        // 判断是否为数字类型
        boolean status = string2Double(value, num, suffix);
        if (!status) {
            return;
        }
        double unit = num[0] / dy;
        boolean bVertical = pathInfo.type == PathInfo.PathType.BAR; // 判断是为垂直方向或水平方向的柱状图
        Line2D axis = selectAxis(chart, bVertical);
        double axisValue = axis != null ? axis.getY1() : 0.0;
        double f = 0.0, ymean = 0.0;
        for (int i = 0; i < ids.size(); i++) {
            ChartPathInfo pathInfoB = chart.pathInfos.get(ids.get(i));
            ChartPathInfosParser.getPathColumnBox(pathInfoB.path, xs, ys);
            List<String> values = new ArrayList<>();
            for (int j = 0; j < ys.size()/2; j++) {
                ymean = 0.5 * (ys.get(2 * j + 1) + ys.get(2 * j));
                dy = ys.get(2 * j + 1) - ys.get(2 * j);
                if (ymean > axisValue && axisValue > 0) {
                    f = -dy * unit;
                }
                else {
                    f = dy * unit;
                }
                String valueNew = String.format("%f%s", f, suffix[0]);
                values.add(valueNew);
            }
            if (pathInfoB.valuesY.size() == values.size()) {
                pathInfoB.valuesY = values;
            }
        } // end for i
    }

    /**
     * 重置找到的最近属性值
     * 寻找基于顶点附近最近的标记属性值的方法在属性值位置相近或原图数据错误时　相邻折线顶点可能找错属性值
     * 多条折线时 同X坐标的上下不同折线顶点最近标记属性值可能错位或是不容易找对
     * @param chart
     */
    public static void resetFindedAdjacentValue(Chart chart) {
        // 找出在附近找到标记属性值的折线
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < chart.pathInfos.size(); i++) {
            ChartPathInfo pathInfo = chart.pathInfos.get(i);
            if (pathInfo.type == PathInfo.PathType.LINE) {
                if (pathInfo.valueFromNearbyTextInfo) {
                    ids.add(i);
                }
            }
        }

        // 折线数目小于2 即不需要调整顺序 则跳过
        int n = ids.size();
        if (n <= 1) {
            return;
        }

        double [] num = {0.0};
        String [] suffix = {""};
        List<Double> xs = new ArrayList<>();     // 折线顶点X坐标集
        List<Double> ys = new ArrayList<>();     // 折线顶点Y坐标集
        Map<Integer, List<ColumnPts>> ptsMap = new HashMap<>();
        // 遍历每条找到附近属性值的ChartPathInfo对象
        for (Integer i : ids) {
            // 取出顶点集
            ChartPathInfo pathInfo = chart.pathInfos.get(i);
            if (!getLinePathPoints(pathInfo.path, chart.hAxis, xs, ys)) {
                continue;
            }
            // 遍历顶点集　对每个点按照X坐标进行堆叠存储
            for (int j = 0; j < xs.size(); j++) {
                int x = (int)(0.2 * xs.get(j));
                // 解析字符所含数值信息
                String valueStr = pathInfo.valuesY.get(j);
                if (!ChartContentScaleParser.string2Double(valueStr, num, suffix)) {
                    continue;
                }

                // 堆叠式进行存储
                List<ColumnPts> objs = ptsMap.get(x);
                ColumnPts cobj = new ColumnPts(i, j, ys.get(j), num[0], valueStr);
                if (objs == null) {
                    objs = new ArrayList<>();
                    objs.add(cobj);
                }
                else {
                    int idFirst = objs.get(0).ithLine;
                    if (chart.pathInfos.get(idFirst).ithSideY != chart.pathInfos.get(i).ithSideY) {
                        objs.clear();
                    }
                    objs.add(cobj);
                }
                ptsMap.put(x, objs);
            } // end for j
        } // end for i

        // 遍历处理每一列对象 即同X坐标值的顶点集 基于顶点Y坐标和抽取到数值的大小同序进行匹配
        for (Map.Entry<Integer, List<ColumnPts>> column : ptsMap.entrySet()) {
            List<ColumnPts> pts = column.getValue();
            if (pts.size() <= 1) {
                continue;
            }
            List<String> values = new ArrayList<>();
            List<ColumnPts> ptsNew = new ArrayList<>();
            pts.stream().sorted((c1, c2) -> ((Double)c1.value).compareTo(c2.value))
                    .forEach(c -> values.add(c.valueStr));
            pts.stream().sorted((c1, c2) -> ((Double)c2.y).compareTo(c1.y))
                    .forEach(c -> ptsNew.add(c));
            for (int i = 0; i < ptsNew.size(); i++) {
                ColumnPts columnPts = ptsNew.get(i);
                ChartPathInfo pathInfo = chart.pathInfos.get(columnPts.ithLine);
                pathInfo.valuesY.set(columnPts.ithPt, values.get(i));
            }
        } // end for map
    }

    /**
     * 判断给定Chart的X轴或Y轴是否为标称类型
     * @param chart
     * @param bXAxis
     */
    public static boolean isChartXYAxisLabel(Chart chart, boolean bXAxis) {
        // 遍历chart内部所有PathInfos 找出指定坐标轴是否为标签类型的状态
        for (int i = 0; i < chart.pathInfos.size(); i++) {
            ChartPathInfo path = chart.pathInfos.get(i);
            if (bXAxis && path.bSubdivideX) {
                return false;
            }
            else if (!bXAxis && path.bSubdivideY) {
                return false;
            }
        }
        return true;
    }

    /**
     * 找出给定 String 的后缀
     * @param str
     * @return
     */
    public static String getStringSuffix(String str) {
        double [] num = {0.0};
        String [] suffix = {""};
        if (ChartContentScaleParser.string2Double(str, num, suffix)) {
            return suffix[0];
        }
        return null;
    }

    /**
     * 获得Chart给定PathInfo对应的Y属性的后缀 (水平柱状图为X属性)
     * @param chart
     * @param scaleQua
     * @param ithPath
     * @return
     */
    public static String getChartPathInfoSuffix(
            Chart chart,
            ChartScaleQuantificationInfo scaleQua,
            int ithPath) {
        String suffix = "";
        // 判断Chart类型 和 给定数据有效性
        if (chart == null || chart.type == ChartType.BITMAP_CHART) {
            return suffix;
        }
        if (ithPath < 0 || ithPath >= chart.pathInfos.size()) {
            return suffix;
        }

        // 获得相应的 PathInfo
        ChartPathInfo pathInfo = chart.pathInfos.get(ithPath);
        if (chart.type == ChartType.COLUMN_CHART) {
            if (!scaleQua.infos[2].infos.isEmpty()) {
                suffix = scaleQua.infos[2].suffix[0];
            }
            else if (!scaleQua.infos[3].infos.isEmpty()) {
                suffix = scaleQua.infos[3].suffix[0];
            }
            // 在自身属性值中找出后缀
            else if (!pathInfo.valuesX.isEmpty()) {
                suffix = getStringSuffix(pathInfo.valuesX.get(0));
                return suffix == null ? "" : suffix;
            }
        }
        else {
            int ithY = pathInfo.ithSideY;
            if (!scaleQua.infos[ithY].infos.isEmpty()) {
                suffix = scaleQua.infos[ithY].suffix[0];
            }
            // 在自身属性值中找出后缀
            else if (!pathInfo.valuesY.isEmpty()) {
                suffix = getStringSuffix(pathInfo.valuesY.get(0));
                return suffix == null ? "" : suffix;
            }
        }
        return suffix;
    }

    /**
     * 微调时间刻度的量化信息
     * @param chart
     * @param scaleNumberInfo
     */
    public static void fineTurnTimeAxis(Chart chart, ChartScaleQuantificationInfo scaleNumberInfo) {
        // 过滤掉无效类型
        if (chart == null || chart.type == ChartType.BITMAP_CHART ||
                chart.type == ChartType.PIE_CHART || chart.type == ChartType.COLUMN_CHART) {
            return;
        }

        // 判断是否为时间刻度
        ScaleQuantification scaleQuantification = scaleNumberInfo.infos[2];
        if (scaleQuantification.type != ScaleInfoType.TIME_SCALE || scaleQuantification.xs.size() <= 1) {
            return;
        }

        if (chart.hAxis != null) {
            double xAxis = Math.min(chart.hAxis.getX1(), chart.hAxis.getX2());
            double xScaleStart = scaleQuantification.xs.get(0);
            double xScaleNext = scaleQuantification.xs.get(1);
            double dxScale = Math.abs(xScaleStart - xScaleNext);
            double dx = xScaleStart - xAxis;
            // 如果第一个时间刻度和坐标轴左侧位置接近
            if (dx < 2.0 * dxScale) {
                double xScale = 0.0;
                for (int i = 0; i < scaleQuantification.xs.size(); i++) {
                    xScale = scaleQuantification.xs.get(i) - dx;
                    scaleQuantification.xs.set(i, xScale);
                }
            }
        }
    }

    /**
     * 判断给定Chart内部PathInfos上对象的相应刻度属性是否需要细分插值
     * @param chart
     * @param scaleNumberInfo
     */
    public static void judgeChartPathInfosNeedSubdivide(
            Chart chart,
            ChartScaleQuantificationInfo scaleNumberInfo) {
        if (chart.pathInfos.isEmpty()) {
            return;
        }
        double rate = 0.0;
//        List<Double> rares = new ArrayList<>();
        Map<PathInfo.PathType, Boolean> subStatus = new HashMap<>();
        for (int i = 0; i < chart.pathInfos.size(); i++) {
            ChartPathInfo pathInfo = chart.pathInfos.get(i);
            boolean bSubdivide = false;
            // 折线类型 比较折线顶点的个数和相应刻度属性的个数 如果大于后者　则细分
            if (pathInfo.type == PathInfo.PathType.LINE || pathInfo.type == PathInfo.PathType.CURVE) {
                List<Double> xs = new ArrayList<>();     // 折线顶点X坐标集
                List<Double> ys = new ArrayList<>();     // 折线顶点Y坐标集
                boolean bGetPts = getLinePathPoints(pathInfo.path, chart.hAxis, xs, ys);
                if (!bGetPts) {
                    return;
                }
                List<String> infosD = scaleNumberInfo.infos[2].infos;
                List<String> infosU = scaleNumberInfo.infos[3].infos;
                if ((!infosD.isEmpty() &&  xs.size() > infosD.size()) ||
                        (!infosU.isEmpty() &&  xs.size() > infosU.size())) {
                    rate = 1.0 * xs.size() / Math.max(infosD.size(), infosU.size());
//                    rares.add(rate);
                    bSubdivide = true;
                }
            } // end if
            // 柱状类型 比较矩形的个数和相应刻度属性的个数 如果大于后者　则细分
            else if (pathInfo.type == PathInfo.PathType.BAR || pathInfo.type == PathInfo.PathType.COLUMNAR) {
                List<Double> cxs = new ArrayList<>();    // 矩形对应的刻度属性的X坐标
                List<Double> cys = new ArrayList<>();    // 矩形对应的刻度属性的Y坐标
                boolean bVertical = pathInfo.type == PathInfo.PathType.BAR; // 判断是为垂直方向或水平方向的柱状图
                Line2D axis = bVertical ? chart.hAxis : (chart.lvAxis != null ? chart.lvAxis : chart.rvAxis);
                boolean bGetPts = getPathColumnPoints(pathInfo.path, bVertical, chart.getArea(), axis, cxs, cys);
                if (!bGetPts) {
                    return;
                }
                // 垂直排列
                if (bVertical) {
                    List<String> infosD = scaleNumberInfo.infos[2].infos;
                    List<String> infosU = scaleNumberInfo.infos[3].infos;
                    if ((!infosD.isEmpty() &&  cxs.size() > infosD.size()) ||
                            (!infosU.isEmpty() &&  cxs.size() > infosU.size())) {
                        rate = 1.0 * cxs.size() / Math.max(infosD.size(), infosU.size());
//                        rares.add(rate);
                        bSubdivide = true;
                    }
                }
                // 水平排列
                else {
                    List<String> infosL = scaleNumberInfo.infos[0].infos;
                    List<String> infosR = scaleNumberInfo.infos[1].infos;
                    if ((!infosL.isEmpty() &&  cys.size() > infosL.size()) ||
                            (!infosR.isEmpty() &&  cys.size() > infosR.size())) {
                        rate = 1.0 * cys.size() / Math.max(infosL.size(), infosR.size());
//                        rares.add(rate);
                        bSubdivide = true;
                    }
                }
            } // end else if
            // 面积类型 一般是由多个点对组成　需要细分插值
            else if (pathInfo.type == PathInfo.PathType.AREA) {
                bSubdivide = true;
            }
            if (subStatus.containsKey(pathInfo.type)) {
                subStatus.put(pathInfo.type, subStatus.get(pathInfo.type) || bSubdivide);
            }
            else {
                subStatus.put(pathInfo.type, bSubdivide);
            }
        } // end for i

        // 判断是否需要细分
        boolean bSubdivide = subStatus.containsValue(Boolean.TRUE);
        if (bSubdivide) {
            boolean hasLine = subStatus.containsKey(PathInfo.PathType.LINE) ||
                    subStatus.containsKey(PathInfo.PathType.CURVE);
            boolean hasArea = subStatus.containsKey(PathInfo.PathType.AREA);
            boolean barNeedSub = false;
            if (subStatus.containsKey(PathInfo.PathType.BAR)) {
                barNeedSub = subStatus.get(PathInfo.PathType.BAR);
            }
            // 没有面积对象　并且　含有折线对象　没有柱状或是柱状不需要细分
            if (!hasArea && hasLine && !barNeedSub) {
                ScaleQuantification scaleInfo = scaleNumberInfo.infos[2].infos.isEmpty() ?
                        scaleNumberInfo.infos[3] : scaleNumberInfo.infos[2];
                boolean needResetLine = true;
                boolean hasNoBar = !subStatus.containsKey(PathInfo.PathType.BAR);
                if (scaleInfo.type == ScaleInfoType.TIME_SCALE && hasNoBar) {
                    needResetLine = false;
                }

                // 如果X刻度为标签类型　并且 没有柱状对象
                // 暂时仅对折线对象　采取类似位图解析方法，　如果效果好　后续推广到其他类型图表上去
                if (scaleInfo.type == ScaleInfoType.LABEL_SCALE && hasNoBar) {
                    // 假设存在水平坐标轴　方便等比切分并且量化X轴刻度
                    if (chart.hAxis != null) {
                        // 构建一个虚拟的刻度量化对象
                        ScaleQuantification virScale = new ScaleQuantification();
                        Rectangle2D box = chart.hAxis.getBounds2D();
                        // 如果构建成功
                        if (virScale.buildAxisScale(scaleInfo.xs, scaleInfo.infos, box.getMinX(), box.getMaxX())) {
                            // 遍历pathInfo 基于虚拟刻度量化对象　重置折线
                            for (int i = 0; i < chart.pathInfos.size(); i++) {
                                ChartPathInfo pathInfo = chart.pathInfos.get(i);
                                PathInfo.PathType oldType = pathInfo.type;
                                resetLinePathMatchLabelAxis(chart, pathInfo, virScale, true);
                                pathInfo.onlyMatchXScale = true;
                                //pathInfo.type = PathInfo.PathType.CURVE;
                                pathInfo.type = oldType;
                            }
                            // 将虚拟刻度量化对象的位置和值信息　赋值并保存至X刻度量化对象中
                            scaleInfo.setExtentData(virScale.xs, virScale.infos);
                            chart.onlyMatchXScale = true;
                            return;
                        }
                    }
                }

                if (needResetLine) {
                    for (int i = 0; i < chart.pathInfos.size(); i++) {
                        ChartPathInfo pathInfo = chart.pathInfos.get(i);
                        resetLinePathMatchLabelAxis(chart, pathInfo, scaleInfo, false);
                    }
                    bSubdivide = false;
                }
            }
        }
        else {
            boolean hasLine = subStatus.containsKey(PathInfo.PathType.LINE) ||
                    subStatus.containsKey(PathInfo.PathType.CURVE);
            if (hasLine) {
                ScaleQuantification scaleInfo = scaleNumberInfo.infos[2].infos.isEmpty() ?
                        scaleNumberInfo.infos[3] : scaleNumberInfo.infos[2];
                for (int i = 0; i < chart.pathInfos.size(); i++) {
                    ChartPathInfo pathInfo = chart.pathInfos.get(i);
                    resetLinePathMatchLabelAxis(chart, pathInfo, scaleInfo, false);
                }
            }
        }

        // 遍历　PathInfo 设置每一个PathInfo对象的细分插值状态
        for (int i = 0; i < chart.pathInfos.size(); i++) {
            ChartPathInfo pathInfo = chart.pathInfos.get(i);
            if (pathInfo.type == PathInfo.PathType.LINE || pathInfo.type == PathInfo.PathType.CURVE) {
                pathInfo.bSubdivideY = true;
                pathInfo.bSubdivideX = bSubdivide;
            }
            else if (pathInfo.type == PathInfo.PathType.BAR || pathInfo.type == PathInfo.PathType.COLUMNAR) {
                if (pathInfo.type == PathInfo.PathType.COLUMNAR) {
                    pathInfo.bSubdivideX = true;
                    pathInfo.bSubdivideY = bSubdivide;
                }
                else {
                    pathInfo.bSubdivideY = true;
                    pathInfo.bSubdivideX = bSubdivide;
                }
            }
            else if (pathInfo.type == PathInfo.PathType.AREA) {
                pathInfo.bSubdivideY = true;
                pathInfo.bSubdivideX = bSubdivide;
            }
        } // end for i

        // 重置刻度类型
        if (!bSubdivide) {
            if (chart.type == ChartType.COLUMN_CHART) {
                ScaleQuantification scaleInfo = scaleNumberInfo.infos[0];
                if (!scaleInfo.infos.isEmpty()) {
                    scaleInfo.type = ScaleInfoType.LABEL_SCALE;
                }
                scaleInfo = scaleNumberInfo.infos[1];
                if (!scaleInfo.infos.isEmpty()) {
                    scaleInfo.type = ScaleInfoType.LABEL_SCALE;
                }
            }
            else {
                ScaleQuantification scaleInfo = scaleNumberInfo.infos[2];
                if (!scaleInfo.infos.isEmpty()) {
                    scaleInfo.type = ScaleInfoType.LABEL_SCALE;
                }
                scaleInfo = scaleNumberInfo.infos[3];
                if (!scaleInfo.infos.isEmpty()) {
                    scaleInfo.type = ScaleInfoType.LABEL_SCALE;
                }
            }
        }
    }

    public static GeneralPath resetLinePathBaseAxis(
            Chart chart,
            ChartPathInfo pathInfo,
            ScaleQuantification scaleInfo,
            boolean fineControl) {
        // 如果刻度太少　直接返回
        if (pathInfo.type != PathInfo.PathType.CURVE &&
                pathInfo.type != PathInfo.PathType.LINE) {
            return null;
        }
        int nScale = scaleInfo.xs.size();
        if (nScale < 2) {
            return null;
        }

        // 找出Line Path 上的点集的X Y 坐标信息
        List<Double> xs = new ArrayList<>();     // 折线顶点X坐标集
        List<Double> ys = new ArrayList<>();     // 折线顶点Y坐标集
        List<Integer> types = new ArrayList<>();     // 折线顶点类型
        if (!getLinePathPoints(pathInfo.path, chart.hAxis, xs, ys, types)) {
            return null;
        }

        // 计算平均间隔宽度
        double width = (scaleInfo.xs.get(nScale - 1) - scaleInfo.xs.get(0)) / nScale;
        int npts = xs.size();
        double [] pt = {0.0, 0.0};
        double [] pt1 = {0.0, 0.0};
        double [] pt2 = {0.0, 0.0};
        int [] ptType = {0, 0};
        double coef = 0.0, dx = 0.0, dist = 0.0;
        List<Double> xsNew = new ArrayList<>();
        List<Double> ysNew = new ArrayList<>();
        List<Integer> typesNew = new ArrayList<>();     // 折线顶点类型
        int segmentStatus = PathIterator.SEG_MOVETO;
        int pointStatus = PathIterator.SEG_MOVETO;
        int ith_pt = 0;
        // 判断是否要精准控制
        double rate = 0.95;
        if (fineControl) {
            rate = 0.995;
        }
        for (int i = 0; i < nScale; i++) {
            pt[0] = scaleInfo.xs.get(i);
            for (int j = ith_pt; j < npts - 1; j++) {
                pt1[0] = xs.get(j);
                pt1[1] = ys.get(j);
                pt2[0] = xs.get(j + 1);
                pt2[1] = ys.get(j + 1);
                ptType[0] = types.get(j);
                ptType[1] = types.get(j + 1);
                if (ptType[1] == PathIterator.SEG_MOVETO) {
                    dist = Math.abs(pt2[0] - pt1[0]) + Math.abs(pt2[1] - pt1[1]);
                    if (dist > 1.0) {
                        segmentStatus = PathIterator.SEG_MOVETO;
                    }
                    continue;
                }
                dx = pt2[0] - pt1[0];
                if (dx < 1.E-3) {
                    continue;
                }
                // 计算刻度位置在折线上对应最佳点
                if (pt1[0] <= pt[0] && pt[0] <= pt2[0]) {
                    coef = (pt2[0] - pt[0]) / dx;
                    if (coef >= rate) {
                        pointStatus = ptType[0];
                        xsNew.add(pt[0]);
                        ysNew.add(pt1[1]);
                    }
                    else if (coef < 1 - rate) {
                        pointStatus = ptType[1];
                        xsNew.add(pt[0]);
                        ysNew.add(pt2[1]);
                    }
                    else {
                        pointStatus = ptType[1];
                        pt[1] = coef * pt1[1] + (1.0 - coef) * pt2[1];
                        xsNew.add(pt[0]);
                        ysNew.add(pt[1]);
                    }
                    ith_pt = j;
                }
                // 如果在MOVETO点左侧一定范围内
                else if (pt1[0] > pt[0] && pt[0] > pt1[0] - 0.5 * width &&
                        ptType[0] == PathIterator.SEG_MOVETO) {
                    pointStatus = ptType[0];
                    xsNew.add(pt[0]);
                    ysNew.add(pt1[1]);
                    ith_pt = j;
                }
                // 如果在折线最后顶点右侧一定范围内
                else if (pt2[0] < pt[0] && pt[0] < pt2[0] + 0.5 * width) {
                    if (j+ 2 <= npts - 1 && types.get(j + 2) == PathIterator.SEG_MOVETO) {
                        pointStatus = ptType[1];
                        xsNew.add(pt[0]);
                        ysNew.add(pt2[1]);
                        ith_pt = j;
                    }
                    else if (j + 1 == npts - 1) {
                        pointStatus = ptType[1];
                        xsNew.add(pt[0]);
                        ysNew.add(pt2[1]);
                        ith_pt = j;
                    } else {
                        continue;
                    }
                }
                else {
                    continue;
                }
                // 保存和修改状态
                typesNew.add(segmentStatus);
                if (pointStatus != segmentStatus) {
                    segmentStatus = pointStatus;
                }
                if (segmentStatus == PathIterator.SEG_MOVETO) {
                    segmentStatus = PathIterator.SEG_LINETO;
                }
                break;
            } // end for j
        } // end for i

        // 判断数目是否匹配
        npts = xsNew.size();
        if (npts != typesNew.size() || npts != ysNew.size()) {
            return null;
        }


        // 构造新Path对象
        GeneralPath path = (GeneralPath)pathInfo.path.clone();
        path.reset();
        for (int i = 0; i < npts; i++) {
            pt[0] = xsNew.get(i);
            pt[1] = ysNew.get(i);
            pointStatus = typesNew.get(i);
            if (pointStatus == PathIterator.SEG_MOVETO) {
                path.moveTo(pt[0], pt[1]);
            }
            else {
                path.lineTo(pt[0], pt[1]);
            }
        } // end for i
        return (GeneralPath)path.clone();
    }

    /**
     * 将给定Chart内非增序的折线对象逆序
     * 以便后续插值操作
     * @param chart
     */
    public static void reverseChartUnOrderLine(Chart chart) {
        // 遍历找出折线对象
        for (ChartPathInfo pathInfo : chart.pathInfos) {
            PathInfo.PathType type = pathInfo.type;
            if (type == PathInfo.PathType.LINE || type == PathInfo.PathType.CURVE) {
                GeneralPath path = reverseLinePath(chart, pathInfo.path);
                pathInfo.path = (GeneralPath) path.clone();
            }
        }
    }

    /**
     * 将给定Chart内path对象逆序 如果折线点是从右到左方向的话
     * @param chart
     * @param path
     * @return
     */
    public static GeneralPath reverseLinePath(Chart chart, GeneralPath path) {
        // 找出Line Path 上的点集的X Y 坐标信息
        List<Double> xs = new ArrayList<>();     // 折线顶点X坐标集
        List<Double> ys = new ArrayList<>();     // 折线顶点Y坐标集
        List<Integer> types = new ArrayList<>();     // 折线顶点类型
        if (!getLinePathPoints(path, chart.hAxis, xs, ys, types)) {
            return (GeneralPath) path.clone();
        }

        // 判断折线顶点的方向
        int count = 0;
        int n = xs.size();
        for (int i = 0; i < n - 1; i++) {
            if (xs.get(i + 1) >= xs.get(i)) {
                count++;
            }
        }
        // 如果过半是升序　则直接返回
        if (1.0 * count / (n - 1) > 0.5) {
            return (GeneralPath) path.clone();
        }

        // 简单逆序　不考虑详细类型
        GeneralPath pathNew = new GeneralPath();
        pathNew.moveTo(xs.get(n - 1), ys.get(n - 1));
        for (int i = n - 2; i >= 0; i--) {
            pathNew.lineTo(xs.get(i), ys.get(i));
        }
        return (GeneralPath) pathNew.clone();
    }

    /**
     * 基于标签类型刻度信息重置折线对象
     * @param chart
     * @param pathInfo
     * @param scaleInfo
     * @param needRefine
     */
    public static void resetLinePathMatchLabelAxis(
            Chart chart,
            ChartPathInfo pathInfo,
            ScaleQuantification scaleInfo,
            boolean needRefine) {
        // 判断是否需要对折线对象进行加密处理
        boolean fineControl = false;
        if (needRefine) {
            //  为了在采样光滑曲线时，尽可能保持光滑性  需要加密每一段光滑曲线段
            GeneralPath pathNew = ChartUtils.refinePath(pathInfo.path, 20);
            if (pathNew != null) {
                // 赋值新path对象
                pathInfo.path = (GeneralPath) pathNew.clone();
                fineControl = true;
            }
        }

        GeneralPath path = resetLinePathBaseAxis(chart, pathInfo, scaleInfo, fineControl);
        if (path == null) {
            return;
        }

        List<Double> xs = new ArrayList<>();     // 折线顶点X坐标集
        List<Double> ys = new ArrayList<>();     // 折线顶点Y坐标集
        List<Integer> types = new ArrayList<>();     // 折线顶点类型
        if (!getLinePathPoints(pathInfo.path, chart.hAxis, xs, ys, types)) {
            return;
        }

        List<Double> xsNew = new ArrayList<>();     // 折线顶点X坐标集
        List<Double> ysNew = new ArrayList<>();     // 折线顶点Y坐标集
        List<Integer> typesNew = new ArrayList<>();     // 折线顶点类型
        if (!getLinePathPoints(path, chart.hAxis, xsNew, ysNew, typesNew)) {
            return;
        }

        // 如果点数和以前一致　则直接返回
        if (xs.size() == xsNew.size()) {
            // 判断原始path在X方向上是否等间距
            int n = xsNew.size();
            double dx = (xsNew.get(n - 1) - xsNew.get(0)) / n;
            double dist = 0.0;
            boolean valid = true;
            for (int i = 0; i < n - 1; i++) {
                dist = xs.get(i + 1) - xs.get(i);
                if (dist < 0.3 * dx || dist > 1.3 * dx) {
                    valid = false;
                    break;
                }
            }
            // 如果近似等间距　则用原始path对象
            if (valid) {
                return;
            }
            //return;
        }

        pathInfo.path = (GeneralPath)path.clone();

        // 根据类型　设置普通折线还是光滑折线
        if (types.contains(PathIterator.SEG_CUBICTO)) {
            pathInfo.type = PathInfo.PathType.CURVE;
        }
        else {
            pathInfo.type = PathInfo.PathType.LINE;
        }
    }

    /**
     * 抽取给定面积图上下边界对应点的位置信息
     * 一般对应点为X坐标相等，Y坐标不等 可以看错由很多连续挨着很窄的柱状组合成面积
     * @param path 给定路径
     * @param axis 水平坐标轴　用来设置有效区域范围用
     * @param xs 对应点的 X 坐标集
     * @param ysU 对应点上侧的 Y 坐标集
     * @param ysD 对应点下侧的 Y 坐标集
     * @return 如果抽取点集坐标信息正确　则返回　true
     */
    public static boolean getPathAreaPoints(
            GeneralPath path,
            Line2D axis,
            List<Double> xs,
            List<Double> ysU,
            List<Double> ysD) {
        // 测试数据的有效性
        if (axis == null || xs == null || ysU == null || ysD == null) {
            return false;
        }
        xs.clear();
        ysU.clear();
        ysD.clear();

        // 重置close点为起始点 方便处理
        GeneralPath pathNew = PathUtils.resetPathClosePoint(path);

        // 遍历path的点集 抽取在坐标轴设定范围内的有效点坐标信息
        PathIterator iter = pathNew.getPathIterator(null);
        double[] coords = new double[12];
        double xmin = axis.getX1() < axis.getX2() ? axis.getX1() : axis.getX2();
        double xmax = axis.getX1() > axis.getX2() ? axis.getX1() : axis.getX2();
        int n = 0;
        List<AreaPoint> pts = new ArrayList<>();
        TreeSet<AreaPoint> upPts = new TreeSet<>();
        TreeSet<AreaPoint> downPts = new TreeSet<>();
        while (!iter.isDone()) {
            switch (iter.currentSegment(coords)) {
                case PathIterator.SEG_MOVETO:
                case PathIterator.SEG_LINETO:
                    // 判断是否在有效范围内
                    if (coords[0] < xmin - DELTA || coords[0] > xmax + DELTA) {
                        break;
                    }
                    AreaPoint apt = new AreaPoint(coords[0], coords[1]);
                    // 如果出现某个点只有一个X坐标即上下边界的交点  则也将其加入 downPts中
                    n = pts.size();
                    if (n >= 2) {
                        if (!apt.equals(pts.get(n - 1)) &&
                                apt.equals(pts.get(n - 2))) {
                            downPts.add(pts.get(n - 1));
                        }
                    }
                    if (upPts.contains(apt)) {
                        downPts.add(apt);
                    }
                    else {
                        upPts.add(apt);
                    }
                    pts.add(apt);
                    break;
                default:
                    break;
            } // end switch
            iter.next();
        } // end while

        // 判断两部分数据个数是否相同
        if (upPts.size() != downPts.size() || upPts.size() == 0) {
            return false;
        }

        // 遍历判断对应位置X坐标是否相同和分成上下两组
        List<AreaPoint> upts = new ArrayList<>(upPts);
        List<AreaPoint> dpts = new ArrayList<>(downPts);
        // 排序
        Collections.sort(upts);
        Collections.sort(dpts);
        for(int i = 0; i < upts.size(); i++) {
            AreaPoint upt = upts.get(i);
            AreaPoint dpt = dpts.get(i);
            // 如果对应序号的点X坐标值不同　则返回 false
            if (!ChartUtils.equals(upt.x, dpt.x)) {
                return false;
            }
            //if (upt.y < dpt.y) {
            if (upt.y > dpt.y) {
                double y = upt.y;
                upt.y = dpt.y;
                dpt.y = y;
            }
            upts.set(i, upt);
            dpts.set(i, dpt);
        }
        // 取出X和上下Y坐标集
        upts.stream().forEach(pt -> xs.add(pt.x));
        upts.stream().forEach(pt -> ysU.add(pt.y));
        dpts.stream().forEach(pt -> ysD.add(pt.y));
        return true;
    }

    /**
     * 将给定柱状图Path 根据垂直矩形的X坐标或水平矩形的Y坐标 从新排序
     * @param path
     * @param isVertical
     * @return
     */
    public static GeneralPath reSortColumn(GeneralPath path, boolean isVertical) {
        GeneralPath pathSorted = (GeneralPath) path.clone();
        PathIterator iter = path.getPathIterator(null);
        double[] coords = new double[12];
        double [] cxs = {0.0, 0.0, 0.0, 0.0};
        double [] cys = {0.0, 0.0, 0.0, 0.0};
        double pos = 0.0;
        List<ColumnPath> bars = new ArrayList<>();
        int icount = 0;
        while (!iter.isDone()) {
            switch (iter.currentSegment(coords)) {
                case PathIterator.SEG_MOVETO:
                case PathIterator.SEG_LINETO:
                    // 如果起始点再次出现　则跳过
                    if (icount == 4 && ChartUtils.equals(cxs[0], coords[0]) &&
                            ChartUtils.equals(cys[0], coords[1])) {
                        break;
                    }
                    // 如果出现多余 4 个点 而且不封闭 则很有可能出错
                    else if (icount == 4) {
                        return pathSorted;
                    }
                    cxs[icount] = coords[0];
                    cys[icount] = coords[1];
                    icount++;
                    break;

                // 存在着矩形在坐标轴的两侧的情况
                case PathIterator.SEG_CLOSE:
                    if (icount < 4) {
                        return pathSorted;
                    }

                    addNewColumnPath(bars, cxs, cys, isVertical);
                    icount = 0;
                    break;

                default:
                    return pathSorted;
            }  // end switch
            iter.next();
        }  // end while
        if (icount == 4) {
            addNewColumnPath(bars, cxs, cys, isVertical);
        }
        if (bars.size() <= 1) {
            return pathSorted;
        }
        pathSorted.reset();
        bars.stream().sorted((b1, b2) -> ((Double)b1.pos).compareTo(b2.pos))
                .forEach(b -> ChartPathInfosParser.compTwoGeneralPaths(pathSorted, b.path));
        return pathSorted;
    }

    public static void addNewColumnPath(List<ColumnPath> bars, double [] cxs, double [] cys, boolean isVertical) {
        double pos = 0.0;
        if (isVertical) {
            pos = 0.25 * (cxs[0] + cxs[1] + cxs[2] + cxs[3]);
        }
        else {
            pos = 0.25 * (cys[0] + cys[1] + cys[2] + cys[3]);
        }
        // 保存单个矩形对象Path 并 放入bars  方便后续从新排序
        GeneralPath pathNew = buildBarPath(cxs, cys);
        // 如果该矩形已经绘制过一次 则略过
        boolean hasDuplicate = bars.stream().anyMatch(bar -> bar.path.getBounds2D().equals(pathNew.getBounds2D()));
        if (!hasDuplicate) {
            bars.add(new ColumnPath(pathNew, pos));
        }
    }

    public static GeneralPath buildBarPath(double [] cxs, double [] cys) {
        GeneralPath pathNew = new GeneralPath();
        pathNew.moveTo(cxs[0], cys[0]);
        pathNew.lineTo(cxs[1], cys[1]);
        pathNew.lineTo(cxs[2], cys[2]);
        pathNew.lineTo(cxs[3], cys[3]);
        pathNew.closePath();
        return (GeneralPath) pathNew.clone();
    }

    public static double[] getValidPathRange(boolean isVertical, Rectangle2D area, Line2D axis) {
        // 计算区域范围 过滤掉不在显示的矩形用
        double rangeMin = area.getMinX();
        double rangeMax = area.getMaxX();
        if (!isVertical) {
            rangeMin = area.getMinY();
            rangeMax = area.getMaxY();
        }
        // 如果有对应的坐标轴信息　则用坐标轴的范围信息 缩小范围
        if (axis != null) {
            if (isVertical) {
                rangeMin = axis.getX1() < axis.getX2() ? axis.getX1() : axis.getX2();
                rangeMax = axis.getX1() > axis.getX2() ? axis.getX1() : axis.getX2();
            }
            else {
                rangeMin = axis.getY1() < axis.getY2() ? axis.getY1() : axis.getY2();
                rangeMax = axis.getY1() > axis.getY2() ? axis.getY1() : axis.getY2();
            }
        }
        // 稍微扩展一点容差
        double dwh = 0.005 * (area.getHeight() + area.getWidth());
        rangeMin -= dwh;
        rangeMax += dwh;
        return new double []{rangeMin, rangeMax};
    }

    /**
     * 抽取给定 柱状图 中矩形的属性标识位置信息集
     * @param path 给定路径
     * @param isVertical 柱状图是否为垂直方向的状态标识
     * @param area
     * @param axis 对应的坐标轴
     * @param xs 矩形在X方向上的位置信息集
     * @param ys 矩形在Y方向上的位置信息集
     * @return 如果抽取信息成功　则返回　true
     */
    public static boolean getPathColumnPoints(
            GeneralPath path,
            boolean isVertical,
            Rectangle2D area,
            Line2D axis,
            List<Double> xs,
            List<Double> ys) {
        // 测试数据的有效性
        if (xs == null || ys == null) {
            return false;
        }
        xs.clear();
        ys.clear();

        // 遍历path的点集 抽取矩形标识坐标信息
        PathIterator iter = path.getPathIterator(null);
        double[] coords = new double[12];
        double [] cxs = {0.0, 0.0, 0.0, 0.0};
        double [] cys = {0.0, 0.0, 0.0, 0.0};
        double maxXY = 0.0;

        // 计算区域范围 过滤掉不在显示的矩形用
        double [] range = getValidPathRange(isVertical, area, axis);
        double rangeMin = range[0];
        double rangeMax = range[1];
        double xyAxis = 0.0;
        int icount = 0;
        while (!iter.isDone()) {
            int ptType = iter.currentSegment(coords);
            switch (ptType) {
                case PathIterator.SEG_MOVETO:
                case PathIterator.SEG_LINETO:
                    // 过滤掉显示区域范围外部的矩形
                    if (isVertical && (coords[0] < rangeMin || coords[0] > rangeMax)) {
                        break;
                    }
                    else if (!isVertical && (coords[1] < rangeMin || coords[1] > rangeMax)) {
                        break;
                    }
                    // 如果起始点再次出现　则跳过
                    if (icount == 4 && ChartUtils.equals(cxs[0], coords[0]) &&
                            ChartUtils.equals(cys[0], coords[1])) {
                        break;
                    }
                    // 如果出现多余 4 个点 而且不封闭 则很有可能出错
                    else if (icount == 4) {
                        if (ptType == PathIterator.SEG_MOVETO) {
                            // 计算矩形位置代表点信息
                            getColumnPositionPt(cxs, cys, isVertical, axis, xs, ys);
                            icount = 0;
                        }
                        else {
                            return false;
                        }
                    }
                    cxs[icount] = coords[0];
                    cys[icount] = coords[1];
                    icount++;
                    break;

                // 存在着矩形在坐标轴的两侧的情况
                case PathIterator.SEG_CLOSE:
                    if (icount < 4) {
                        icount = 0;
                        break;
                    }

                    // 计算矩形位置代表点信息
                    getColumnPositionPt(cxs, cys, isVertical, axis, xs, ys);
                    icount = 0;
                    break;

                default:
                    return false;
            }  // end switch
            iter.next();
        }  // end while
        if (xs.isEmpty() || xs.size() != ys.size()) {
            return false;
        }
        return true;
    }

    public static void getColumnPositionPt(
            double [] cxs, double [] cys,
            boolean isVertical, Line2D axis, List<Double> xs, List<Double> ys) {
        // 如果是垂直的　则计算离水平轴距离的大小　判断对应刻度的位置
        double maxXY = 0.0;
        if (isVertical) {
            if (axis != null) {
                maxXY = getColumnHeightOrWight(cys, true, axis.getY1());
            }
            else {
                maxXY = getColumnHeightOrWight(cys, false, 0.0);
            }
            ys.add(maxXY);
            xs.add(DoubleStream.of(cxs).sum() / 4);
        }
        // 如果是水平的　则计算离垂直轴距离的大小　判断对应刻度的位置
        else {
            if (axis != null) {
                maxXY = getColumnHeightOrWight(cxs, true, axis.getX1());
            }
            else {
                maxXY = getColumnHeightOrWight(cxs, false, 0.0);
            }
            xs.add(maxXY);
            ys.add(DoubleStream.of(cys).sum() / 4);
        }
    }

    /**
     * 给定矩形高度上下值或宽度左右值 和 坐标轴值　计算矩形有效高度或宽度
     * @param xs 矩形相应坐标集
     * @param bAxis
     * @param x
     * @return
     */
    public static double getColumnHeightOrWight(double [] xs, boolean bAxis, double x) {
        // 找出最小最大值
        double x1 = xs[0];
        double x2 = xs[0];
        for (int i = 1; i < xs.length; i++) {
            double xi = xs[i];
            x1 = x1 > xi ? xi : x1;
            x2 = x2 < xi ? xi : x2;
        }
        double dxy1 = x1, dxy2 = x2;
        if (bAxis) {
            dxy1 = Math.abs(x1 - x);
            dxy2 = Math.abs(x2 - x);
        }
        double maxXY = (dxy1 > dxy2) ? x1 : x2;
        if (bAxis && dxy1 > 5.0 && dxy2 > 5.0) {
            double maxLen = Math.abs(dxy1 - dxy2);
            if (x1 > x) {
                maxXY = x + maxLen;
            }
            else {
                maxXY = x - maxLen;
            }
        }
        if (!bAxis) {
            maxXY = dxy1 > dxy2 ? x2 : x1;
        }
        return maxXY;
    }

    /**
     * 计算给定折线Path上顶点坐标信息集
     * 即将路径上有效点集抽取处理　方便后续对应各侧刻度信息的插值
     * @param path 给定路径
     * @param axis 水平坐标轴　用来设置有效区域范围用
     * @param xs 顶点的X坐标集
     * @param ys 顶点的Y坐标集
     * @param types 顶点的类型
     * @return 如果抽取坐标信息成功　则返回　true
     */
    public static boolean getLinePathPoints(
            GeneralPath path,
            Line2D axis,
            List<Double> xs,
            List<Double> ys,
            List<Integer> types) {
        // 检测参数有效性
        if (xs == null || ys == null || types == null) {
            return false;
        }
        xs.clear();
        ys.clear();
        types.clear();

        // 如果传入参考坐标轴 则用其左右端X值过滤掉无效点
        double xmin  = 0.0, xmax = 0.0;
        if (axis != null) {
            xmin = axis.getX1() < axis.getX2() ? axis.getX1() : axis.getX2();
            xmax = axis.getX1() > axis.getX2() ? axis.getX1() : axis.getX2();
            // 稍微扩展一点容差
            double dw = 0.01 * (xmax - xmin);
            xmin -= dw;
            xmax += dw;
        }
        int n = 0;
        // 遍历path的点集 存储顶点的坐标信息
        PathIterator iter = path.getPathIterator(null);
        double[] coords = new double[12];
        while (!iter.isDone()) {
            int ptType = iter.currentSegment(coords);
            switch (ptType) {
                case PathIterator.SEG_MOVETO:
                case PathIterator.SEG_LINETO:
                    if (axis != null && (coords[0] < xmin || coords[0] > xmax)) {
                        break;
                    }
                    // 如果相邻点重复 则只存储前一个
                    n = xs.size();
                    if (n >= 1 && ChartUtils.equals(xs.get(n - 1), coords[0]) &&
                            ChartUtils.equals(ys.get(n - 1), coords[1])) {
                        break;
                    }
                    xs.add(coords[0]);
                    ys.add(coords[1]);
                    types.add(ptType);
                    break;

                case PathIterator.SEG_CUBICTO:
                    if (axis != null && (coords[0] < xmin || coords[0] > xmax)) {
                        break;
                    }
                    xs.add(coords[4]);
                    ys.add(coords[5]);
                    types.add(ptType);
                    break;

                default:
                    break;
            }  // end switch
            iter.next();
        }  // end while
        // 判断异常
        if (ys.isEmpty() || xs.isEmpty() || ys.size() != xs.size() || xs.size() == 1) {
            return false;
        }
        else {
            return true;
        }
    }

    /**
     * 计算给定折线Path上顶点坐标信息集
     * 即将路径上有效点集抽取处理　方便后续对应各侧刻度信息的插值
     * @param path 给定路径
     * @param axis 水平坐标轴　用来设置有效区域范围用
     * @param xs 顶点的X坐标集
     * @param ys 顶点的Y坐标集
     * @return 如果抽取坐标信息成功　则返回　true
     */
    public static boolean getLinePathPoints(
            GeneralPath path,
            Line2D axis,
            List<Double> xs,
            List<Double> ys) {
        List<Integer> types = new ArrayList<>();
        return getLinePathPoints(path, axis, xs, ys, types);
    }
}

/**
 * 刻度信息类型定义 存在数值型　时间型　标称型 后续添加其他类型
 */
enum ScaleInfoType {
    UNKNOWN_SCALE(-1),
    NUMBER_SCALE(0),
    TIME_SCALE(1),
    LABEL_SCALE(2);

    private final int value;       //整数值表示

    private ScaleInfoType(int value) {
        this.value = value;
    }

    public int getValue() {
        return this.value;
    }
}

/**
 * 折线顶点 或 柱状矩形 就近寻找最合适属性值时 按照给定方向搜索
 */
enum PtValuePositionType {
    UNKNOWN_POSITION(-1),
    NEAREST_POSITION(0),
    X_CENTER_POSITION(1),
    Y_CENTER_POSITION(2);

    private final int value;       //整数值表示

    private PtValuePositionType(int value) {
        this.value = value;
    }
    public int getValue() {
        return this.value;
    }
}


/**
 * 刻度量化数据结构　方便后续插值
 */
class ScaleQuantification {
    ScaleInfoType type = ScaleInfoType.UNKNOWN_SCALE;           // 刻度类型
    public List<Double> xs = new ArrayList<>();       // 刻度的中心坐标

    public List<Double> numsD = new ArrayList<>();    // 刻度对应的浮点数
    public List<Long> numsL = new ArrayList<>();      // 刻度对应的整数

    public List<String> infos = new ArrayList<>();    // 刻度对应的字符串集
    public String[] suffix = {""};                              // 刻度数字的后缀信息

    public TimeFormat timeFormat = null;                        // 如果刻度是时间类型　则保存最匹配的时间样式

    public Line2D[] axis = {null};                              // 刻度线即坐标轴　在柱状图中柱同时在轴两侧时提供参考作用

    public List<String> infosExt = new ArrayList<>();           // 扩展后的文本信息　用于标签数据扩充
    public List<Double> xsExt = new ArrayList<>();              // 扩展后的文本信息位置信息

    ScaleQuantification() {}

    // 清空
    void clear() {
        type = ScaleInfoType.UNKNOWN_SCALE;
        xs.clear();
        numsD.clear();
        numsL.clear();
        infos.clear();
        infosExt.clear();
        xsExt.clear();
        suffix[0] = "";
    }

    /**
     * 基于个给定范围　均匀细分刻度　即扩展标签数据　　用于显示和原图一致效果用
     * @param scaleXs
     * @param scales
     * @param min
     * @param max
     */
    public boolean buildAxisScale(List<Double> scaleXs, List<String> scales, double min, double max) {
        clear();
        if (scales.size() != scaleXs.size() || scales.isEmpty()) {
            return false;
        }

        type = ScaleInfoType.LABEL_SCALE;

        // 暂时选择400个采样点　　也可以通过参数传入控制
        int n = 400;
        double dx = (max - min) / n;
        infos = new ArrayList<>();
        xs = new ArrayList<>();

        // 给采样点赋初值并设置位置
        for (int i = 0; i < n; i++) {
            infos.add("");
            xs.add(min + i * dx);
        }

        // 将给定标签根据其位置设置到相应采样点上
        for (int i = 0; i < scaleXs.size(); i++) {
            String text = scales.get(i);
            double x = scaleXs.get(i);
            int ith = (int)(Math.abs(x - min)/dx);
            ith = Math.min(n - 1, ith);
            ith = Math.max(0, ith);
            infos.set(ith, text);
        } // end for i
        return true;
    }

    /**
     * 设置扩展数据
     * @param xsExt
     * @param infosExt
     */
    public void setExtentData(List<Double> xsExt, List<String> infosExt) {
        this.xsExt = xsExt;
        this.infosExt = infosExt;
    }
}

/**
 * Chart内所有信息的量化结构信息
 */
class ChartScaleQuantificationInfo {
    // 左右上下刻度对应的量化信息集
    public ScaleQuantification[] infos = {
         new ScaleQuantification(),
         new ScaleQuantification(),
         new ScaleQuantification(),
         new ScaleQuantification() };

    // 清空
    void clear() {
        for (int i = 0; i < 4; i++) {
            infos[0].clear();
        }
    }

    // 判断给定侧的刻度是否存在
    boolean hasIthScale(int ithSide) {
        if (ithSide < 0 || ithSide > 4) {
            return false;
        }
        return (infos[ithSide].type != ScaleInfoType.UNKNOWN_SCALE);
    }
}

class ColumnPath {
    public GeneralPath path = null;
    public double pos;
    ColumnPath(GeneralPath p, double x) {
        path = (GeneralPath) p.clone();
        pos = x;
    }
}

/**
 * 相邻折线顶点可能找错标记刻度属性
 * 需要对相近X坐标的顶点的刻度属性进行重置优化, 需要用到此结构
 */
class ColumnPts {
    public int ithLine;
    public int ithPt;
    public double y;
    public double value;
    public String valueStr;
    ColumnPts(int ithLine, int ithPt, double y, double value, String valueStr) {
        this.ithLine = ithLine;
        this.ithPt = ithPt;
        this.y = y;
        this.value = value;
        this.valueStr = valueStr;
    }
}

/**
 * 面积对象边界点对象数据结构
 * 如果面积对象从边界任意位置点开始顺时针或逆时针方向旋转一圈
 * 需要将边界点分成上下有序的两部分, 需要用到此结构
 */
class AreaPoint implements Comparable<AreaPoint> {
    public double x;
    public double y;

    public AreaPoint() {
        x = 0.0;
        y = 0.0;
    }

    public AreaPoint(double x, double y) {
        this.x = x;
        this.y = y;
    }

    @Override
    public String toString() {
        return String.format("Point: x: %.3f, y: %.3f", x, y);
    }

    @Override
    public int compareTo(AreaPoint p) {
        if (ChartUtils.equals(x, p.x)) {
            return 0;
        }
        else if (x < p.x) {
            return -1;
        }
        else {
            return 1;
        }
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        AreaPoint objPt = (AreaPoint)obj;
        if (compareTo(objPt) == 0) {
            return true;
        }
        else {
            return false;
        }
    }

    @Override
    public final int hashCode() {
        int hashCode = 17;
        hashCode = hashCode * 31 + 1;
        hashCode = hashCode * 31 + 1;
        return hashCode;
    }
}

/**
 * 一组字符串的最匹配的时间戳和时间样式
 */
class StringsTimeFormatInfo {
    public List<Long> timestamp;
    public TimeFormat timeFormat;

    public StringsTimeFormatInfo(List<Long> timestamp, TimeFormat timeFormat) {
        this.timestamp = timestamp;
        this.timeFormat = timeFormat;
    }
}
