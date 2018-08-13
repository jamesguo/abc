package com.abcft.pdfextract.core.model;

import org.apache.commons.lang3.StringUtils;

import java.util.*;

/**
 * 文本类别。
 */
public class TextClasses {

    /**
     * 正文文本。
     */
    public static final String CLASS_MAIN_TEXT = "main-text";
    /**
     * 其它文本。
     */
    public static final String CLASS_OTHER_TEXT = "other-text";
    /**
     * 页眉文本。
     */
    public static final String CLASS_PAGE_HEADER = "pdf-header";
    /**
     * 页脚文本。
     */
    public static final String CLASS_PAGE_FOOTER = "pdf-footer";
    /**
     * 左边栏文本。
     */
    public static final String CLASS_PAGE_LEFT_WING = "pdf-left-wing";
    /**
     * 右边栏文本。
     */
    public static final String CLASS_PAGE_RIGHT_WING = "pdf-right-wing";
    /**
     * 脚注文本。
     */
    public static final String CLASS_PAGE_FOOT_NOTES = "pdf-footnotes";

    /**
     * 隐藏文本。
     */
    public static final String CLASS_HIDDEN = "hidden";

    /**
     * 目录段落。
     */
    public static final String CLASS_CATALOG_ITEM = "catalog-item";
    /**
     * 1级大纲。
     */
    public static final String CLASS_HEADING1 = "catalog-h1";
    /**
     * 2级大纲。
     */
    public static final String CLASS_HEADING2 = "catalog-h2";
    /**
     * 3级大纲。
     */
    public static final String CLASS_HEADING3 = "catalog-h3";
    /**
     * 4级大纲。
     */
    public static final String CLASS_HEADING4 = "catalog-h4";
    /**
     * 5级大纲。
     */
    public static final String CLASS_HEADING5 = "catalog-h5";
    /**
     * 6级大纲。
     */
    public static final String CLASS_HEADING6 = "catalog-h6";
    /**
     * 7级大纲。
     */
    public static final String CLASS_HEADING7 = "catalog-h7";
    /**
     * 8级大纲。
     */
    public static final String CLASS_HEADING8 = "catalog-h8";
    /**
     * 9级大纲。
     */
    public static final String CLASS_HEADING9 = "catalog-h9";

    /**
     * 一般标题。
     */
    public static final String CLASS_GENERAL_TITLE = "catalog-title";
    /**
     * 图表标题。
     */
    public static final String CLASS_CHART_TITLE = "chart-title";
    /**
     * 图表备注。
     */
    public static final String CLASS_CHART_NOTES = "chart-notes";

    /**
     * 表格标题。
     */
    public static final String CLASS_TABLE_TITLE = "table-title";
    /**
     * 表格上方文本（候选标题）。
     */
    public static final String CLASS_TABLE_CAPS = "table-caps";
    /**
     * 表格下方文本（候选备注）。
     */
    public static final String CLASS_TABLE_SHOES = "table-shoes";
    /**
     * 表格描述。
     */
    public static final String CLASS_TABLE_DESC = "table-desc";
    /**
     * 表格单位。
     */
    public static final String CLASS_TABLE_UNIT = "table-unit";
    /**
     * 表格脚注。
     */
    public static final String CLASS_TABLE_NOTES = "table-notes";

    public static Collection<String> getClassesFromStructureType(Collection<String> structureTypes) {
        List<String> classes = new ArrayList<>(structureTypes.size());
        if (structureTypes.contains("H1")) {
            classes.add(CLASS_CATALOG_ITEM);
            classes.add(CLASS_HEADING1);
        } else if (structureTypes.contains("H2")) {
            classes.add(CLASS_CATALOG_ITEM);
            classes.add(CLASS_HEADING2);
        } else if (structureTypes.contains("H3")) {
            classes.add(CLASS_CATALOG_ITEM);
            classes.add(CLASS_HEADING3);
        } else if (structureTypes.contains("H4")) {
            classes.add(CLASS_CATALOG_ITEM);
            classes.add(CLASS_HEADING4);
        } else if (structureTypes.contains("H5")) {
            classes.add(CLASS_CATALOG_ITEM);
            classes.add(CLASS_HEADING5);
        } else if (structureTypes.contains("H6")) {
            classes.add(CLASS_CATALOG_ITEM);
            classes.add(CLASS_HEADING6);
        } else if (structureTypes.contains("H7")) {
            classes.add(CLASS_CATALOG_ITEM);
            classes.add(CLASS_HEADING7);
        } else if (structureTypes.contains("H8")) {
            classes.add(CLASS_CATALOG_ITEM);
            classes.add(CLASS_HEADING8);
        } else if (structureTypes.contains("H9")) {
            classes.add(CLASS_CATALOG_ITEM);
            classes.add(CLASS_HEADING9);
        }

        return classes;
    }


    private final Set<String> classes = new LinkedHashSet<>();

    public TextClasses() {
    }

    public TextClasses(Collection<String> classes) {
        this.classes.addAll(classes);
    }

    public TextClasses(TextClasses other) {
        this.classes.addAll(other.classes);
    }

    public String getClasses() {
        return StringUtils.join(classes, ' ');
    }

    public List<String> getClassesList() {
        return new ArrayList<>(classes);
    }

    public boolean addClass(String clazz) {
        return this.classes.add(clazz);
    }

    public void addClasses(String... classes) {
        addClasses(Arrays.asList(classes));
    }

    public void addClasses(Collection<String> classes) {
        this.classes.addAll(classes);
    }

    public void addClasses(TextClasses classes) {
        addClasses(classes.classes);
    }

    public boolean removeClass(String clazz) {
        return this.classes.remove(clazz);
    }

    public void removeClasses(String... classes) {
        removeClasses(Arrays.asList(classes));
    }

    public void removeClasses(List<String> classes) {
        this.classes.removeAll(classes);
    }

    public void clearClasses() {
        this.classes.clear();
    }

    public boolean hasClass(String clazz) {
        return classes.contains(clazz);
    }

    public boolean hasAllClasses(String... classes) {
        return hasAllClasses(Arrays.asList(classes));
    }

    public boolean hasAllClasses(List<String> classes) {
        return this.classes.containsAll(classes);
    }

    public boolean hasAnyClass(String... classes) {
        return hasAnyClass(Arrays.asList(classes));
    }

    public boolean hasAnyClass(List<String> classes) {
        for (String clazz : classes) {
            if (classes.contains(clazz)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return '{' + getClasses() + '}';
    }
}
