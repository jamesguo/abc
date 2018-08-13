package com.abcft.pdfextract.core.table;

import java.util.Set;

/**
 * 表示表格的一些外在属性。
 */
public interface TableTags {

    /**
     * 表示表格的标题是否为表格内找到的。
     *
     * @return 如果表格标题是表格内找到的则为 true；未设置或者表格外找到的则为 false。
     */
    boolean isInlineTitle();
    /**
     * 设置一个表格内找到的潜在标题。
     *
     * @param title 表格内标题的文本。
     */
    default void setInlineTitle(String title) {
        setTitle(title, true);
    }

    /**
     * 获取表格的标题。
     *
     * @return 表格的标题文本。
     */
    String getTitle();

    /**
     * 设置表格的标题，允许指定是否为表格内的。
     *
     * @param title 标题的文本。
     * @param inline 是否为表格内找的标题。
     */
    void setTitle(String title, boolean inline);

    /**
     * 设置表格的标题（默认为表格外找到的）。
     *
     * @param title 标题的文本。
     */
    default void setTitle(String title) {
        setTitle(title, false);
    }

    /**
     * 获取表格上方的文本。
     * <p>可能包含说明信息、单位、日期、币种，也可能包含标题上方的无关文本。</p>
     *
     * @return 表格上方的文本。
     */
    String getCaps();

    /**
     * 设置表格上方的文本。
     *
     * @param caps 表格上方的文本。
     */
    void setCaps(String caps);

    /**
     * 获取表格下方的文本。
     * <p>可能包含脚注、备注、附加说明等，也可能包含无关文本。</p>
     *
     * @return 表格下方的文本。
     */
    String getShoes();
    /**
     * 设置表格下方的文本。
     *
     */
    void setShoes(String shoes);

    /**
     * 获取表格的单位等附加信息。
     *
     * @return 表格的单位等附加信息。
     */
    Set<String> getUnitSet();
    /**
     * 获取的单位行。
     * <p>可能包含脚注、备注、附加说明等，也可能包含无关文本。</p>
     *
     * @return 表格下方的文本。
     */
    String getUnitTextLine();
    /**
     * 设置表格的单位行。
     * @param unitLine 单位行的文本。
     * @param unitSet 提取到的单位等附加信息。
     */
    void setUnit(String unitLine, Set<String> unitSet);

    /**
     * 获取表格的标记。
     * <p>可能是表格的逻辑名字等信息。</p>
     *
     * @return 表格的标记。
     */
    String getTag();

    /**
     * 设置表格的标记。
     *
     * @param tag 表格的标记。
     */
    void setTag(String tag);

    /**
     * 由 {@link TableTitle} 设置表格的外在属性。
     * @param title 包含表格外在属性的 {@link TableTitle}。
     */
    default void setTableTitle(TableTitle title) {
        setTitle(title.getTitle(), false);
        setCaps(title.getCaps());
        setShoes(title.getShoes());
        setUnit(title.getUnitTextLine(), title.getUnitSet());
    }

}
