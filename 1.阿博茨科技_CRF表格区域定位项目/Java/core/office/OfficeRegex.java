package com.abcft.pdfextract.core.office;

import java.util.HashSet;
import java.util.regex.Pattern;

import static java.util.regex.Pattern.compile;

/**
 * office正则
 */
final class OfficeRegex {
    final static Pattern UNIT_PATTERN = compile("单位(：|:)");
    final static Pattern DATA_PATTERN = compile("\\d*(/|-|年)\\d*(/|-|月)\\d*");
    final static Pattern HYPLINK_PATTERN = compile("(https?|ftp|file)://[-A-Za-z0-9+&@#/%?=~_|!:,.;]+[-A-Za-z0-9+&@#/%=~_|]");
    final static Pattern FOOT_PATTERN = compile("\\[footnoteRef:\\d]");
    final static HashSet<Pattern> TABLE_IDS_PATTERNS = new HashSet() {{
        add(compile("\\s*\\[(table|tbl|Table)_.*\\]\\s*"));
        add(compile("\\s*\\[(table|tbl|Table)_.*\\]\\s*"));
    }};
    final static Pattern[] TITLE_PATTERNS = new Pattern[]{
            compile("\\s*[0-9]+年[0-9]+月[0-9]+日\\s*.*"),
            compile("\\s*标题索引\\s*.*"),
            compile("\\s*金融衍生品跟踪报告模板\\s*.*"),
            compile("\\s*金融行业\\s*.*"),
    };

    final static Pattern[] FILTERED_PATTERNS = new Pattern[]{
            compile("统计截止日期[:：]\\s*[0-9]+年[0-9]+月[0-9]+日"),
            compile("([0-9]*年)?([0-9]*月)?([0-9]*日)[)）]?"),
            compile("阅读末页免责条款"),
            compile("S[0-9]+"),
            compile("资料来源[:：].*"),
            compile("^[-\\+]?[.\\d]*$"),
            compile("(.*((单位|注|人民币|單位|人民幣)([0-9]{1,2})?(：|:)).*)")
    };

    final static HashSet<Pattern> HEADER_FOOTERS_PATTERNS = new HashSet() {{
        add(compile("页眉"));
        add(compile("页脚"));
        add(compile("Footer[1-9]*"));
    }};

    final static Pattern TITLE_TBL_CHART_PATTERN = compile("[图|表][\\d]");
    final static Pattern NOTES_PATTERN = compile("注(:|：)");

    final static Pattern DATE_FORMAT_PATTERN = compile("\\d{4}(/|-)\\d{2}(/|-)\\d{2}");

    final static Pattern DOUBLE_PATTERN = compile("\\s*\\d+(\\.\\d+)?");

    final static Pattern SHEET_PATTERN = compile("Sheet\\d*");

}
