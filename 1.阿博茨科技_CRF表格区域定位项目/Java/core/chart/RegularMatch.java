package com.abcft.pdfextract.core.chart;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 将解析过程中经常用到的正则表达式模式和高频率的字符串共享 提高效率 使用饿汉单例模式
 * Created by myyang on 17-7-3.
 */
public class RegularMatch {
    private static final RegularMatch instance = new RegularMatch();          // 饿汉单例模式的实例对象

    public Map<String, TimeFormat> dateRegFormat = new HashMap<>();           // 时间类字符串正则表达式样式
    public Map<String, Pattern> dateRegPattern = new HashMap<>();             // 时间类字符串正则表达式

    public Pattern regExNumberPattern;                                        // 数字类正则表达式
    public Pattern regExNumberAuxiliaryPattern;                               // 带辅助字符信息数字类正则表达式
    public Pattern regExPureNumberPattern;                                    // 纯数字类正则表达式
    public Pattern regExLetterDigitPattern;                                   // 字母或数字字符正则表达式
    public Pattern regExPureEnglishPattern;                                   // 纯英文加少量字符
    public Pattern regExNumberNewGroupPattern;                                // Chart图形对象数值属性组起始字符正则表达式
    public Pattern regExSuffixPattern;                                        // 含特殊符号数字类正则表达式
    public Pattern regExDollarPattern;                                        // 含美元符数字类正则表达式
    public Pattern regExUnitPattern;                                          // 刻度的单位符号正则表达式
    public Pattern regExFloatPattern;                                         // 浮点数类正则表达式
    public Pattern regExFloatPercentPattern;                                  // 含百分号浮点数类正则表达式
    public Pattern regExChinesePattern;                                       // 中文正则表达式
    public Pattern regExSubtitlePattern;                                      // GIC副标题类似时间类型正则表达式
    public Pattern regExSpaceOnePattern;                                      // 包含一个以上空白符正则表达式
    public Pattern regExSpaceTwoPattern;                                      // 包含两个以上空白符正则表达式
    public Pattern regExSpaceThreePattern;                                    // 包含三个以上空白符正则表达式
    public Pattern regExSpaceFourPattern;                                     // 包含四个以上空白符正则表达式
    public Pattern regLeftAxisPattern;                                        // 左侧刻度关键字正则表达式
    public Pattern regRightAxisPattern;                                       // 右侧刻度关键字正则表达式
    public Pattern regExIntFloatNumberPattern;                                // 正整数或者带小数点数字或者1,233.9这种


    public List<String> unitFormat;                                           // 数值类属性常用单位
    public List<String> titleEnglishFormat;                                   // 图表常用英文标题前缀
    public List<String> titleEnglishFormatTable;                                   // 图表常用英文标题前缀
    public List<String> titleChineseFormat;                                   // 图表常用中文标题前缀
    public List<String> titleChineseFormatTable;                                   // 图表常用中文标题前缀
    public List<String> sourceFormat;                                         // 数据来源信息前缀
    public List<String> noteFormat;                                           // 注释信息前缀
    public List<String> dateSuffixFormat;                                     // 时间类字符串后缀

    private RegularMatch() {
        // 设置日期时间类型正则表达式
        setDatePatterns();

        // 设置数值类型正则表达式
        setNumberRegPattern();

        // 设置语言类型正则表达式
        setLanguagePattern();

        // 设置空白符类型正则表达式
        setSpacePattern();

        // 设置其他常用字符串集
        setCommonFormat();
    }

    /**
     * 单例模式返回 唯一的实例对象
     * @return
     */
    public static RegularMatch getInstance() {
        return instance;
    }

    private void setNumberRegPattern() {
        String regExNumber = "[-+]|[.]|[0-9]|[$%(),]";
        regExNumberPattern = Pattern.compile(regExNumber);
        String regExNumberAuxiliary = "[$%()'，,=xT¥€M]";
        regExNumberAuxiliaryPattern = java.util.regex.Pattern.compile(regExNumberAuxiliary);
        String regExSuffix = "[%]$|[$]|[x]|[T]|[€]|[¥]|[M]";
        regExSuffixPattern = Pattern.compile(regExSuffix);
//        String intPercentRegexp = "[$]";
//        regExDollarPattern = Pattern.compile(intPercentRegexp);
//        String regExUnit = "[x]";
//        regExUnitPattern = Pattern.compile(regExUnit);
        String regExFloat = "((\\d+\\.)?\\d+)$";
        regExFloatPattern = Pattern.compile(regExFloat);
        String regExFloatPercent = "((\\d+\\.)?\\d+)%";
        regExFloatPercentPattern= Pattern.compile(regExFloatPercent);
        String regExPureNumber = "[\\d]{1,}";
        regExPureNumberPattern = Pattern.compile(regExPureNumber);
        String regExNumberNewGroup = "[-$(]";
        regExNumberNewGroupPattern = Pattern.compile(regExNumberNewGroup);
        String regExLetterDigit = "[A-Za-z0-9]";
        regExLetterDigitPattern = Pattern.compile(regExLetterDigit);
        String regExPureEnglish = "[a-zA-Z\\s-]+";
        regExPureEnglishPattern = Pattern.compile(regExPureEnglish);

        String regExIntFloatNumber = "[0-9]{0,3}([,][0-9]{2,3})*[0-9]*[.]?[0-9]+";
        regExIntFloatNumberPattern = Pattern.compile(regExIntFloatNumber);
    }

    private void setLanguagePattern() {
        String regChinese = "[\\u4E00-\\u9FA5]";
        regExChinesePattern = Pattern.compile(regChinese);
        // GIC项目优化 区分副标题 有一定统一性规律性的时间表达
        String UCD = "[^A-Za-z0-9]";
        String MONTH = "((January)|(February)|(March)|(April)|(May)|(June)|(July)|(August)|(September)|(October)|(November)|(December))";
        String GICSubtitle = "^((As at)|(For the year ended))" + UCD + MONTH + UCD + "\\d{2}.*";
        regExSubtitlePattern = Pattern.compile(GICSubtitle, Pattern.CASE_INSENSITIVE);
    }

    private void setSpacePattern() {
        String regExSpace = "[\\s　]{1,}";
        regExSpaceOnePattern = Pattern.compile(regExSpace);
        regExSpace = "[\\s　]{2,}";
        regExSpaceTwoPattern = Pattern.compile(regExSpace);
        regExSpace = "[\\s　]{3,}";
        regExSpaceThreePattern = Pattern.compile(regExSpace);
        regExSpace = "[\\s　]{4,}";
        regExSpaceFourPattern = Pattern.compile(regExSpace);
    }

    /**
     * 返回指定空白数的正则表达式  使用频率高的事先生成 其他的临时生成
     * @param nSpaces
     * @return
     */
    public Pattern getSpacePattern(int nSpaces) {
        if (nSpaces <= 0) {
            return null;
        }
        else if (nSpaces == 1) {
            return regExSpaceOnePattern;
        }
        else if (nSpaces == 2) {
            return regExSpaceTwoPattern;
        }
        else if (nSpaces == 3) {
            return regExSpaceThreePattern;
        }
        else if (nSpaces == 4) {
            return regExSpaceFourPattern;
        }
        else {
            String info = "[\\s　]{" + nSpaces + ",}";
            return Pattern.compile(info);
        }
    }

    private void setDatePatterns() {
        setDateStr2FormatMap();
        dateRegPattern.clear();
        for (String key : dateRegFormat.keySet()) {
            dateRegPattern.put(key, Pattern.compile(key));
        }
    }

    private void setDateStr2FormatMap() {
        String UCD = "[^A-Za-z0-9]";
        String yy = "\\d{2}";
        String yyyy = "([12]\\d{3})";
        String MM = "([1-9]|0[1-9]|1[012])";
        String dd = "([1-9]|0[1-9]|1[0-9]|2[0-9]|3[01])";
        String yyyyUMMU = "^(" + yyyy + UCD + MM + UCD + ")$";                                // 2014年11月
        String MMUyyyyU = "^(" + MM + UCD + yyyy + UCD + ")$";                                // 11月2014年
        String yyyyMM = "^(" + yyyy + MM + ")$";                                              // 201411
        String yyMMdd = "^(" + yy + MM + dd + ")$";                                           // 141125
        String MMddyy = "^(" + MM + dd + yy + ")$";                                           // 112514
        String yyyyMMdd = "^(" + yyyy + MM + dd + ")$";                                       // 20141125
        String MMddyyyy = "^(" + MM + dd + yyyy + ")$";                                       // 11252014
        String yyyyUMM = "^(" + yyyy + UCD + MM + ")$";                                       // 2014-11
        String MMUyyyy = "^(" + MM + UCD + yyyy + ")$";                                       // 11-2014
        String yyUMMUdd = "^(" + yy + UCD + MM + UCD + dd + ")$";                             // 14-11-25
        String MMUddUyy = "^(" + MM + UCD + dd + UCD + yy + ")$";                             // 11-25-14
        String ddUMMUyy = "^(" + dd + UCD + MM + UCD + yy + ")$";                             // 25-11-14
        String yyyyUMMUdd = "^(" + yyyy + UCD + MM + UCD + dd + ")$";                         // 2014-11-25
        String yyyyUMMUddU = "^(" + yyyy + UCD + MM + UCD + dd  + UCD + ")$";                 // 2014年11月25日
        String MMUddUyyyyU = "^(" + MM + UCD + dd  + UCD + yyyy + UCD + ")$";                 // 11月25日2014年
        String MMUddUyyyy = "^(" + MM + UCD + dd + UCD + yyyy + ")$";                         // 11-25-2014
        String ddUMMUyyyy = "^(" + dd + UCD + MM + UCD + yyyy + ")$";                         // 25-11-2014
        String ddUMMUyyyyU = "^(" + dd  + UCD + MM + UCD + yyyy + UCD + ")$";                 // 25日11月2014年

        String MMM = "((Jan)|(Feb)|(Mar)|(Apr)|(May)|(Jun)|(Jul)|(Aug)|(Sep)|(Oct)|(Nov)|(Dec)|" +
                        "(jan)|(feb)|(mar)|(apr)|(may)|(jun)|(jul)|(aug)|(sep)|(oct)|(nov)|(dec))";

        String MMU = "^(" + MM + UCD + ")$";                                                    // 11月
        String MMMS = "^(" + MMM + ")$";                                                        // Feb
        String ddUMMMUyy = "^(" + dd + UCD + MMM + UCD + yy + ")$";                             // 25-Feb-14
        String ddUMMMUyyyy = "^(" + dd + UCD + MMM + UCD + yyyy + ")$";                         // 25-Feb-2014
        String MMMyy = "^(" + MMM + yy + ")$";                                                  // Feb14
        String MMMUyy = "^(" + MMM + UCD + yy + ")$";                                           // Feb-14
        String MMMUyyyy = "^(" + MMM + UCD + "?" + yyyy + ")$";                                 // Feb-?2014
        String MMMdd = "^(" + MMM + dd + ")$";                                                  // Feb25
        String MMMUdd = "^(" + MMM + UCD + dd + ")$";                                           // Feb-25

        String MMyy = "^(" + MM + yy + ")$";                                                    // 1114
        String MMUyy = "^(" + MM + UCD + yy + ")$";                                             // 11-14
        String MMUyyU = "^(" + MM + UCD + yy + UCD + ")$";                                      // 11月14年
        String yyMM = "^(" + yy + MM + ")$";                                                    // 1411
        String yyUMM = "^(" + yy + UCD + MM + ")$";                                             // 14-11
        String yyUMMU = "^(" + yy + UCD + MM + UCD + ")$";                                      // 14年11月
        String yyUMMMUdd = "^(" + yy + UCD + MMM + UCD + dd + ")$";                             // 14-Feb-25
        String yyyyUMMMUdd = "^(" + yyyy + UCD + MMM + UCD + dd + ")$";                         // 2014-Feb-25
        String yyMMM = "^(" + yy + MMM + ")$";                                                  // 14Feb
        String yyUMMM = "^(" + yy + UCD + MMM + ")$";                                           // 14-Feb
        String yyyyMMM = "^(" + yyyy + MMM + ")$";                                              // 2014Feb
        String yyyyUMMM = "^(" + yyyy + UCD + MMM + ")$";                                       // 2014-Feb
        String ddMMM = "^(" + dd + MMM + ")$";                                                  // 25Feb
        String ddUMMM = "^(" + dd + UCD + MMM + ")$";                                           // 25-Feb

        // HighChart时间格式的基本样式
        String yyFH = "%y";
        String yyyyFH = "%Y";
        String MMFH = "%m";
        String MMMFH = "%b";
        String ddFH = "%d";

        dateRegFormat.clear();
        dateRegFormat.put("^\\d{4}$", new TimeFormat("yyyy", yyyyFH));              //2014
        dateRegFormat.put("^\\d{2}$", new TimeFormat("yy", yyFH));                      //14
        dateRegFormat.put("^\\d{4}" + UCD + "$", new TimeFormat("yyyy", yyyyFH));//2014年

        //dateRegFormat.put("^([1234])Q\\d{2}$", "Qyy");//1Q14
        dateRegFormat.put("^([1234])Q" + UCD + "?" + "\\d{2}$", new TimeFormat("Qyy", MMFH + yyFH));//1Q14 1Q 14
        dateRegFormat.put("^Q([1234])" + UCD + "?" + "\\d{2}$", new TimeFormat("Qnyy", MMFH + yyFH));//Q214 Q3 14
        dateRegFormat.put("^([1234])Q" + UCD + "?" + "\\d{4}$", new TimeFormat("Qyyyy", MMFH + yyyyFH));//1Q 2014
        dateRegFormat.put("^Q([1234])" + UCD + "?" + "\\d{4}$", new TimeFormat("Qnyyyy", MMFH + yyyyFH));//Q2 2014
        dateRegFormat.put("^\\d{4}Q([1234])$", new TimeFormat("yyyyQ", yyyyFH + MMFH));//2014Q1
        dateRegFormat.put("^\\d{2}Q([1234])$", new TimeFormat("yyQ", yyFH + MMFH));//14Q1
        dateRegFormat.put("^\\d{4}H([12])$", new TimeFormat("yyyyH", yyyyFH + MMFH));//2014H1
        dateRegFormat.put("^\\d{2}H([12])$", new TimeFormat("yyH", yyFH + MMFH));//14H1
        dateRegFormat.put("^([12])H\\d{4}$", new TimeFormat("Hyyyy", MMFH + yyyyFH));//1H2014
        dateRegFormat.put("^([12])H\\d{2}$", new TimeFormat("Hyy", MMFH + yyFH));//1H14
        dateRegFormat.put("^\\d{4}[AEFaef]?$", new TimeFormat("yyyyE", yyyyFH));//2014E
        dateRegFormat.put("^[AEFaef]?\\d{4}$", new TimeFormat("Eyyyy", yyyyFH));//E2014 A2014
        dateRegFormat.put("^\\d{2}[AEFaef]?$", new TimeFormat("yyE", yyFH));//14E
        dateRegFormat.put("^"+ UCD + "\\d{2}[AEFaef]?$", new TimeFormat("UyyE", yyFH));//'10 '14E   出现于英文
        dateRegFormat.put("^[AEFaef]?\\d{2}$", new TimeFormat("Eyy", yyFH));//E14 A14

        dateRegFormat.put(yyyyUMMU, new TimeFormat("yyyy-MM-", yyyyFH + "/" + MMFH));//2014年03月
        dateRegFormat.put(MMUyyyyU, new TimeFormat("MM-yyyy-", MMFH + "/" + yyyyFH));//03月2014年
        dateRegFormat.put(yyyyUMMUddU, new TimeFormat("yyyy-MM-dd-", yyyyFH + "/" + MMFH + "/" + ddFH));//2014年03月12日
        dateRegFormat.put(MMUddUyyyyU, new TimeFormat("MM-dd-yyyy-", MMFH + "/" + ddFH + "/" + yyyyFH));//03月12日2014年
        dateRegFormat.put(ddUMMUyyyyU, new TimeFormat("dd-MM-yyyy-", ddFH + "/" + MMFH + "/" + yyyyFH));//12日03月2014年
        dateRegFormat.put(yyyyMM, new TimeFormat("yyyyMM", yyyyFH + MMFH));//201403
        dateRegFormat.put(yyMMdd, new TimeFormat("yyMMdd", yyFH + MMFH + ddFH));//140309
        dateRegFormat.put(MMddyy, new TimeFormat("MMddyy", MMFH + ddFH + yyFH));//030914
        dateRegFormat.put(yyyyMMdd, new TimeFormat("yyyyMMdd", yyyyFH + MMFH + ddFH));//20140309
        dateRegFormat.put(MMddyyyy, new TimeFormat("MMddyyyy", MMFH + ddFH + yyyyFH));//03092014

        dateRegFormat.put(yyyyUMM, new TimeFormat("yyyy-MM", yyyyFH + "/" + MMFH));//2014-03
        dateRegFormat.put(MMUyyyy, new TimeFormat("MM-yyyy", MMFH + "/" + yyyyFH));//03-2014
        dateRegFormat.put(yyUMMUdd, new TimeFormat("yy-MM-dd", yyFH + "/" + MMFH + "/" + ddFH));//14-03-09
        dateRegFormat.put(MMUddUyy, new TimeFormat("MM-dd-yy", MMFH + "/" + ddFH + "/" + yyFH));//03-09-14
        dateRegFormat.put(ddUMMUyy, new TimeFormat("dd-MM-yy", ddFH + "/" + MMFH + "/" + yyFH));//23-09-14
        dateRegFormat.put(yyyyUMMUdd, new TimeFormat("yyyy-MM-dd", yyyyFH + "/" + MMFH + "/" + ddFH));//2014-03-09
        dateRegFormat.put(MMUddUyyyy, new TimeFormat("MM-dd-yyyy", MMFH + "/" + ddFH + "/" + yyyyFH));//03-09-2014
        dateRegFormat.put(ddUMMUyyyy, new TimeFormat("dd-MM-yyyy", ddFH + "/" + MMFH + "/" + yyyyFH));//24-09-2014

        dateRegFormat.put(ddUMMMUyy, new TimeFormat("dd-MMM-yy", ddFH + "/" + MMMFH + "/" + yyFH));//23-Jan-14
        dateRegFormat.put(ddUMMMUyyyy, new TimeFormat("dd-MMM-yyyy", ddFH + "/" + MMMFH + "/" + yyyyFH));//23-Jan-2014
        dateRegFormat.put(MMMS, new TimeFormat("MMM", MMMFH));//Jan
        dateRegFormat.put(MMMyy, new TimeFormat("MMMyy", MMMFH + yyFH));//Jan14
        dateRegFormat.put(MMMUyy, new TimeFormat("MMM-yy", MMMFH + "/" + yyFH));//Jan-14
        dateRegFormat.put(MMMUyyyy, new TimeFormat("MMM-yyyy", MMMFH + "/" + yyyyFH));//Jan 2014
        dateRegFormat.put(MMMdd, new TimeFormat("MMMdd", MMMFH + ddFH));//Jan22
        dateRegFormat.put(MMMUdd, new TimeFormat("MMM-dd", MMMFH + "/" + ddFH));//Jan-22
        dateRegFormat.put(yyMMM, new TimeFormat("yyMMM", yyFH + MMMFH));//14Jan
        dateRegFormat.put(yyUMMM, new TimeFormat("yy-MMM", yyFH + MMMFH));//14-Jan
        dateRegFormat.put(yyyyMMM, new TimeFormat("yyyyMMM", yyyyFH + MMMFH));//2014Jan
        dateRegFormat.put(yyyyUMMM, new TimeFormat("yyyy-MMM", yyyyFH + MMMFH));//2014-Jan
        dateRegFormat.put(ddMMM, new TimeFormat("ddMMM", ddFH + MMFH));//22Jan
        dateRegFormat.put(ddUMMM, new TimeFormat("dd-MMM", ddFH + "/" + MMMFH));//22-Jan
        dateRegFormat.put(yyUMMMUdd, new TimeFormat("yy-MMM-dd", yyFH + "/" + MMMFH + "/" + ddFH));//08-Jan-14
        dateRegFormat.put(yyyyUMMMUdd, new TimeFormat("yyyy-MMM-dd", yyyyFH + "/" + MMMFH + "/" + ddFH));//2008-Jan-14
        //dateRegFormat.put(MMM, "MMM");

        // 可能有歧义
        dateRegFormat.put(MMU, new TimeFormat("MM-", MMFH));//9月
        dateRegFormat.put(MMyy, new TimeFormat("MMyy", MMFH + yyFH));//0912
        dateRegFormat.put(MMUyy, new TimeFormat("MM-yy", MMFH + "/" + yyFH));//09-12
        dateRegFormat.put(MMUyyU, new TimeFormat("MM-yy-", MMFH + "/" + yyFH));//09月12年
        dateRegFormat.put(yyMM, new TimeFormat("yyMM", yyFH + MMFH));//09-12
        dateRegFormat.put(yyUMM, new TimeFormat("yy-MM", yyFH + "/" + MMFH));//09-12
        dateRegFormat.put(yyUMMU, new TimeFormat("yy-MM-", yyFH + "/" + MMFH));//09年12月

        String strMMUDD = "((0?[13578]|1[02])\\D(0?[1-9]|[12][0-9]|3[01]))|"
                + "((0?[469]|11)\\D(0?[1-9]|[12][0-9]|30))|"
                + "(0?2\\D(0?[1-9]|[1][0-9]|2[0-9]))";
        dateRegFormat.put(strMMUDD, new TimeFormat("MM-dd", MMFH + "/" + ddFH));// 09-09  9-19
        String strMMUDDU = "(([13578]|1[02])\\D(0*[1-9]|[12][0-9]|3[01])\\D)|"
                + "(([469]|11)\\D(0*[1-9]|[12][0-9]|30)\\D)|"
                + "(2\\D(0*[1-9]|[1][0-9]|2[0-9])\\D)";
        dateRegFormat.put(strMMUDDU, new TimeFormat("MM-dd-", MMFH + "/" + ddFH));// 9月19日
        String strMMDD = "((0?[13578]|1[02])(0?[1-9]|[12][0-9]|3[01]))|"
                + "((0?[469]|11)(0?[1-9]|[12][0-9]|30))|"
                + "(0?2(0?[1-9]|[1][0-9]|2[0-9]))";
        dateRegFormat.put(strMMDD, new TimeFormat("MMdd", MMFH + ddFH));// 0909

        String strHHmmss = "([2][0-3]|[0-1][0-9]|[0-9]):[0-5][0-9]:([0-5][0-9]|[6][0])";
        String strHHmm = "([2][0-3]|[0-1][0-9]|[0-9]):[0-5][0-9]";
        dateRegFormat.put(strHHmmss, new TimeFormat("HHmmss", "%H:%M:%S"));// 23:15:40
        dateRegFormat.put(strHHmm, new TimeFormat("HHmm", "%H:%M"));// 23:15
    }

    private void setCommonFormat() {
        unitFormat = new ArrayList<>(Arrays.asList(
                "公斤", "千克", "克", "吨", "磅", "盎司", "蒲式耳", "公顷", "辆", "千瓦时", "瓦",
                "百萬", "百万","人民幣", "十億元", "單位",
                "立方米", "米", "公里", "千米", "桶", "人民币", "人", "个", "万头", "立方米",
                "单位", "美元", "美金", "美分", "港元", "欧元", "日元", "元", "百", "千", "万", "亿", "百分点", "倍",
                "(x)", "(X)", "US$", "mn", "CNY", "bbl", "day", "bp", "OAS", "bn", "HK$",
                "RMB", "Rmb", "YoY", "YTD", "USD", "HKD", "trn", "GW", "unit", "million",
                "%", "%", "\\(", "\\)", "（", "）", ":",
                "＄", "$", "€", "¥", "￥", "£", "￠", "₣", "₩", "/"));
        titleEnglishFormat = new ArrayList<>(Arrays.asList("FIGURE", "Figure", "figure", "Fig", "fig",
                "EXHIBIT", "Exhibit", "exhibit", "Chart", "chart"));
        titleEnglishFormatTable = new ArrayList<>(Arrays.asList("TABLE", "Table", "table"));
        titleChineseFormat = new ArrayList<>(Arrays.asList("图", "圖"));
        titleChineseFormatTable = new ArrayList<>(Arrays.asList("表", "附表"));
        sourceFormat = new ArrayList<>(Arrays.asList(
                "数据来源", "资料来源", "来源", "信息来源", "数据源",
                "數據來源", "資料來源", "來源", "信息來源", "數據源", // 繁体字
                "图片来源", "圖片來源",
                "source", "Source", "SOURCE"));
        noteFormat = new ArrayList<>(Arrays.asList("注释", "注釋", "注:", "Note", "note"));
        dateSuffixFormat = new ArrayList<>(Arrays.asList("年", "月", "日"));

        String leftAxisKey = "((左)|(\\(L\\))|(lhs)|(left))";
        String rightAxisKey = "((右)|(\\(R\\))|(rhs)|(right))";
        regLeftAxisPattern = Pattern.compile(leftAxisKey, Pattern.CASE_INSENSITIVE);
        regRightAxisPattern = Pattern.compile(rightAxisKey, Pattern.CASE_INSENSITIVE);
    }
}

class TimeFormat {
    public String formatCommon = "";            // 时间类型字符串对应的普通样式
    public String formatHighChart = "";         // 时间类型字符串对应的HighChart样式

    public TimeFormat(String formatCommon, String formatHighChart) {
        this.formatCommon = formatCommon;
        this.formatHighChart = formatHighChart;
    }
}
