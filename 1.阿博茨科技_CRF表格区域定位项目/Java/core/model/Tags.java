package com.abcft.pdfextract.core.model;

/**
 * Created by dhu on 2017/11/13.
 */
public class Tags {
    public static final String IS_UNDER_LINE = "is_under_line";
    public static final String IS_DELETE_LINE = "is_delete_line";

    public static final String PAPER = "paper";                         // 符合文档页面的纸张信息

    public static final String PAGINATION_FRAME = "pagination_frame";       // 推测的页面边距信息
    public static final String PAGINATION_TOP_LINE = "pagination.top";      // 检测到的页眉线的 y 坐标
    public static final String PAGINATION_LEFT_LINE = "pagination.left";    // 检测到的左边线的 x 坐标
    public static final String PAGINATION_RIGHT_LINE = "pagination.right";  // 检测到的右边线的 x 坐标
    public static final String PAGINATION_BOTTOM_LINE = "pagination.bottom";// 检测到的页脚线的 y 坐标
    public static final String CONTENT_GROUP_PAGE = "contentGroupPage";
    public static final String CONTENT_LAYOUT_RESULT = "content_layout_result";// 第一次版面分析结果
    public static final String TABLE_LAYOUT_RESULT = "table_layout_result";// 第二次版面分析结果，table检测时会根据chart检测结果重新进行版面修正
    public static final String TEXT_PAGE = "text_page";
    public static final String EXTRACT_PARAMS = "extract_params";

    public static final String PARAM_ENABLE_OCR = "text.enableOCR";

    public static final String OFFICE_SAVE_PDF = "office.savePDF";

    public static final String IS_CHART_DETECT_IN_TABLE = "is_chart_detect_in_table"; // 表格检测模块中所检测的chart结果
}
