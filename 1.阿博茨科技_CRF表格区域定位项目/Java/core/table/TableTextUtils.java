package com.abcft.pdfextract.core.table;

import org.apache.commons.lang3.StringUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TableTextUtils {

    private static final Pattern SYMBOL_RE = Pattern.compile("[`~!@#$%^&*()+=|{}\\[\\]':;,.<>/?！￥…（）—【】‘；：”“’。，、？\\\\]");
    private static final Pattern CJK_RE = Pattern.compile("[\u4e00-\u9fff]");
    private static final Pattern NUMBER_RE = Pattern.compile("[+-]?([0-9]*\\.?[0-9]+|[0-9]+\\.?[0-9]*)([eE][+-]?[0-9]+)?[%]?");
    private static final Pattern ALPHA_RE = Pattern.compile("[a-zA-Z]+");

    // 某些公司会使用全角空格或者奇怪的空格字符 (http://jkorpela.fi/chars/spaces.html)
    //private static String SPACE_PATTERN = "[\\s\u2000-\u200B\u202F\u205F\u3000]";
    private static String ANY_SPACE = "\\s*";

    // TODO 需要根据情况更新下面的标题排除、包含功能

    public static final Pattern APPLICABLE_RE = Pattern.compile("[√□]\\s*(适用|是)\\s*[√□]\\s*(不适用|否)");
    public static final Pattern APPLICABLE_AFFIRMATIVE_RE = Pattern.compile("[√□]\\s*(适用|是)");
    public static final Pattern APPLICABLE_NEGATIVE_RE = Pattern.compile("[√□]\\s*(不适用|否)");
    public static final Pattern APPLICABLE_AFFIRMATIVE_RE1 = Pattern.compile("√\\s*(适用|是)");
    public static final Pattern APPLICABLE_AFFIRMATIVE_RE2 = Pattern.compile("(适用|是|Yes)");
    public static final Pattern APPLICABLE_NEGATIVE_RE1 = Pattern.compile("√\\s*(不适用|否)");
    public static final Pattern APPLICABLE_NEGATIVE_RE2 = Pattern.compile("(不适用|否|No)");

    // 常见的单位类型的前缀
    private static final String COMMON_UNIT_PREFIXES = "单位|單位|人民币|人民幣|币种|幣種|金额|金額|比例|占比|数量|數量|单价|單價|RMB|Unit";
    // 常见其他说明属性的前缀
    private static final String COMMON_LABEL_PREFIXES = "业绩预告情况|业绩预告填写数据类型|审计类型|審計類型|编制单位|編制單位|" +
            "实际控制人性质|實際控制人性質|实际控制人类型|實際控制人類型|股东性质|股東性質|股东类型|股東類型|备注|備注|Notes?";

    // 单位提取用正则
    public static final Pattern TABLE_UNIT_STATEMENT_RE = Pattern.compile(".*(" + COMMON_UNIT_PREFIXES + ")[：:]?.*");
    public static final Pattern UNIT_SPLIT_RE_1 = Pattern.compile("人民币百万元|百万元人民币|人民币千元|千元人民币|人民币元|百萬元人民幣|千元人民幣");
    public static final Pattern UNIT_SPLIT_RE_2 = Pattern.compile("(单位|金額單位|币种|幣種|Unit)[:：]");

    // 不带说明前缀的纯单位行
    public static final Pattern TABLE_UNIT_RE = Pattern.compile("(?:港币|港幣|人民币|人民幣)(?:百|千|万|萬|百万|百萬|亿|億)?元");
    public static final Pattern TABLE_UNIT_RE2 = Pattern.compile("(?:百|千|万|萬|百万|百萬|亿|億)?元(?:港币|港幣|人民币|人民幣)?");

    public static final Pattern TABLE_CAPS_RE = Pattern.compile("(?:" + COMMON_LABEL_PREFIXES + "|" + COMMON_UNIT_PREFIXES + ")[：:]");
    public static final Pattern TABLE_DATE_RE = Pattern.compile("(?!.*(?:表|Table)\\s*)\\d{4}\\s*[\\-年]\\s*" +
            "\\d{1,2}\\s*(?:[-—]*\\s*\\d{1,2})?\\s*[\\-月]?" +
            "(?:\\s*\\d{1,2}\\s*日?)?\\s*" +
            "(?!.*(?:表|Table)\\s*(?:[(（]续表?[）)])?)");
    public static final Pattern TABLE_UNTIL_DATE_RE = Pattern.compile("(?!.*(表|Table)\\s*)截至(?:\\d{4}\\s*[\\-年])?\\s*" +
            "\\d{1,2}\\s*月" +
            "(?:\\s*\\d{1,2}\\s*日?)?\\s*" +
            "(?:为?止?\\d{1,2}个月|止?\\d{1,2}個月)\\s*" +
            "(?!.*(?:表|Table)\\s*(?:[(（][续續]表?[）)])?)");
    public static final Pattern TABLE_FY_RE = Pattern.compile("\\d{4}\\s*年?\\s*[全半]?年?度\\s*" +
            "(?!.*(?:表|Table)\\s*(?:[(（]续表?[）)])?)");
    public static final Pattern TABLE_FQ_RE = Pattern.compile("\\d{4}\\s*年?\\s*第?[123一二三]季度\\s*" +
            "(?!.*(?:表|Table)\\s*(?:[(（]续表?[）)])?)");

    public static final Pattern TABLE_REMARK_RE = Pattern.compile("^\\s*[(（][^)）]+[)）]\\s*$");
    public static final Pattern TABLE_FOOTER_RE = Pattern.compile("^\\s*(?:备?注|(数据)?来源)[：:]");

    public static final Pattern REPORT_HEADER_RE = Pattern.compile("(?:季度?|半年度?|中期|年度?)?\\s*(?:报告|公告|财务报表及?附注|招股说明书)(?:正文|全文|摘要|\\s*[(（].*[）)])?\\s*$");
    public static final Pattern REPORT_COMPANY_RE = Pattern.compile("(?:股份有限公司|公司|集团|银行)(?:[（(]续表?[）)])?\\s*$");
    public static final Pattern REPORT_NOTES_RE = Pattern.compile("(?:对报告书的|不存在|没有)(?:任何)?虚假记载、误导性陈述(?:或者?|以?及)重大遗漏");

    public static final Pattern REPORT_COMPANY_CODE_RE = Pattern.compile("^\\s*(公司|证券|股票)代码[:：\\s]*[.0-9A-Za-z/]+\\s*$");
    public static final Pattern REPORT_COMPANY_NAME_RE = Pattern.compile("^\\s*(公司|证券|股票)(简称|名称)[:：\\s]*.+$");
    public static final Pattern REPORT_NUMBER_RE = Pattern.compile("(报告|公告)?(编码|编号)[:：\\s]*.+$");


    public static final Pattern PAGER_RE = Pattern.compile("^\\s*[-–]?\\s*(?:第\\s*\\d+\\s*页|\\d+)\\s*[/,，]?\\s*(?:共\\s*\\d+\\s*页|\\d+)?\\s*[-–]?$");
    public static final Pattern PAGER_RE3 = Pattern.compile("^\\s*\\d+(?:\\s*[-–]\\d+)*\\s*$");

    public static final Pattern PAGER_RE1 = Pattern.compile("^\\s*-?\\s*\\d+\\s*([/,，]?\\s*\\d+\\s*)?-?\\s*$");
    public static final Pattern PAGER_RE2 = Pattern.compile("^\\s*第\\s*\\d+\\s*页(\\s*[/,，]?\\s*共\\s*\\d+\\s()*页\\s*)?\\s*$");

    public static final Pattern PAGER_LIKE1 = Pattern.compile("^\\d{1,3}$");
    public static final Pattern PAGER_LIKE2 = Pattern.compile("(季|半?年)度?(?:[报報公]告?|财务报表及?附注|招股说明书)");
    public static final Pattern PAGER_LIKE3 = Pattern.compile("(?:有限公司|集团|银行)(?:[（(]续表?[）)])?");
    public static final Pattern PAGER_LIKE4 = Pattern.compile("[-|/()（）第共页]");

    public static final Pattern[] PAGER_LIKES = {PAGER_LIKE1, PAGER_LIKE2, PAGER_LIKE3, PAGER_LIKE4};

    // 限定数字编号的长度，最多支持三个字符（几百的数字编号），避免把“2017年”这种东西误识别

    // 数字符号后面带括号、点、冒号等
    private static final Pattern NUMERIC_SECTION_PREFIX_DIGIT1_RE = Pattern.compile(
            "^[(（]?\\d{1,3}(?:\\.\\d{1,3})*[)）\\s.、:：]+(?!\\d)");
    // 数字符号后面不带任何分隔符直接接入标题，如 “1主营收入表”
    private static final Pattern NUMERIC_SECTION_PREFIX_DIGIT2_RE = Pattern.compile(
            "^\\d{1,3}(?:\\.\\d{1,3})*[^.\\d]");
    // 字母序号
    private static final Pattern NUMERIC_SECTION_PREFIX_LETTER_RE = Pattern.compile(
            "^[(（]?[\\da-z]{1,3}(?:\\.[\\da-z]{1,3})*[)）]?\\s*[.、:：\\s](?!\\d)", Pattern.CASE_INSENSITIVE);
    // 汉字序号
    private static final Pattern NUMERIC_SECTION_PREFIX_ZH_RE = Pattern.compile(
            "^[(（]?[〇一二三四五六七八九十]{1,3}[)）.、:：]?");
    // Enclosed Alphanumerics
    // 2460-2473    ①~⑳
    // 2474-2487    ⑴~⒇
    // 2488-249b    ⒈~⒛
    // 249c-24b5    ⒜~⒵
    // 24b6-24cf    Ⓐ~Ⓩ
    // 24d0-24e9    ⓐ~ⓩ
    // 24ea         ⓪
    // 24eb-24f4    ⓫~⓴
    // 24f5-24fe    ⓵~⓾
    // 24ff         ⓿
    // Enclosed CJK
    // 3200-321e    ㈀~㈞
    // 3220-3229    ㈠~㈩
    // 322A-3230    ㈪~㈰
    // 3231-3247    ㈱~㉇
    // 3248-324f    ㉈~㉏
    // 3251-325f    ㉑~㉟
    // 3260-327f    ㉠~㉿
    // 3280-3289     ㊀~㊉
    // 328A-32B0     ㊊~ ㊰
    // 32B1-32BF    ㊱~㊿
    // 32D0-32FE    ㋐~㋾
    private static final Pattern NUMERIC_SECTION_PREFIX_ECLOSED_RE = Pattern.compile(
            "^[\u2460-\u24ff\u3200-\u321e\u3220-\u32bf\u32d0-\u32fe]\\s*[.、:：]?");

    public static final Pattern[] NUMERIC_SECTION_PREFIXS = {
            NUMERIC_SECTION_PREFIX_DIGIT1_RE,
            NUMERIC_SECTION_PREFIX_DIGIT2_RE,
            NUMERIC_SECTION_PREFIX_LETTER_RE,
            NUMERIC_SECTION_PREFIX_ZH_RE,
            NUMERIC_SECTION_PREFIX_ECLOSED_RE
    };
    public static final Pattern NUMERIC_SECTION_PREFIX_RE = Pattern.compile("^[(（]?" +
            "[0123456789A-Za-z〇一二三四五六七八九十]{1,3}(\\.[0123456789A-Za-z〇一二三四五六七八九十]{1,3})*" +
            "[)）]?\\s*[.、:：]?");


    public static final Pattern TABLE_TITLE_PREFIX_RE1 = Pattern.compile("^\\s*[(（]?(?:图?表|Table)\\s*[0123456789〇一二三四五六七八九十.]+[)）]?\\s*[:：.、]?");
    public static final Pattern TABLE_TITLE_PREFIX_RE2 = Pattern.compile("^\\s*(?:图?表|Table|Statement of)\\s*[:：.、]");
    public static final Pattern TABLE_TITLE_SUFFIX_RE = Pattern.compile("(?:表(?:项目)?|Table|Statement)\\s*(?:[(（][续續]表?[）)])?\\s*$", Pattern.CASE_INSENSITIVE);
    public static final Pattern TABLE_TITLE_SUFFIX_PART_RE = Pattern.compile("(?:表(?:项目)?|Table|[:：]?)\\s*(?:[(（][续續]表?[）)])?\\s+");

    // 如下格式：
    // 招商银行招股意向书 117 页：本行应收利息按账龄分析结构如下(单位:人民币千元)。
    public static final Pattern TABLE_TITLE_AS_FOLLOW_RE = Pattern.compile("(如下|情況|说明|說明|资料|資料|following)\\s*(表|Table|含义)?\\s*"
            + "([(（][^)）]+[)）])?\\s*"
            + "[。.：:、]$");

    public static final Pattern TOC_TITLE_RE = Pattern.compile("[图表]*目录|TABLE OF CONTENTS", Pattern.CASE_INSENSITIVE);

    public static boolean contains(String s, Pattern pattern) {
        return !StringUtils.isEmpty(s) && pattern.matcher(s).find();
    }

    public static boolean containsAny(String s, Pattern... patterns) {
        if (StringUtils.isEmpty(s)) {
            return false;
        }
        for (Pattern pattern : patterns) {
            if (pattern.matcher(s).find()) {
                return true;
            }
        }
        return false;
    }

    public static boolean matches(String s, Pattern pattern) {
        return !StringUtils.isEmpty(s) && pattern.matcher(s).matches();
    }

    public static boolean matchesAny(String s, Pattern... patterns) {
        if (StringUtils.isEmpty(s)) {
            return false;
        }
        for (Pattern pattern : patterns) {
            if (pattern.matcher(s).matches()) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasChinese(String str) {
        return contains(str, CJK_RE);
    }

    private static boolean hasSymbol(String str) {
        return contains(str, SYMBOL_RE);
    }

    public static boolean hasAlpha(String str) {
        return contains(str, ALPHA_RE);
    }

    private static boolean hasNumber(String str) {    //12.34 1.234e1 1234% 1234 .234
        return contains(str, NUMBER_RE);
    }

    public static boolean isValidateCharSet(String str) {
        return hasAlpha(str) || hasChinese(str) || hasNumber(str) || hasSymbol(str);
    }

    public static boolean isNumber(String str) {    //12.34 1.234e1 1234% 1234 .234
        return NUMBER_RE.matcher(str).matches();
    }

    public static boolean isAllLetterUpperCase(CharSequence cs) {
        if (StringUtils.isBlank(cs)) {
            return false;
        }
        int sz = cs.length();

        for (int i = 0; i < sz; ++i) {
            if (cs.charAt(i) >= 'a' && cs.charAt(i) <= 'z') {
                return false;
            }
        }
        return true;
    }

}
